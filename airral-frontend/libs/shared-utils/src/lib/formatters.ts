// libs/shared-utils/src/lib/formatters.ts

export function formatDate(date: string | Date): string {
  const d = typeof date === 'string' ? new Date(date) : date;
  return d.toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' });
}

export function formatDateTime(date: string | Date): string {
  const d = typeof date === 'string' ? new Date(date) : date;
  return d.toLocaleString('en-US');
}

export function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount);
}

export function truncateText(text: string, length = 100): string {
  return text.length > length ? text.substring(0, length) + '...' : text;
}

export function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1).toLowerCase();
}

/**
 * The unit a pay figure carries, for each interval the API reports in
 * salaryPeriod.
 *
 * <p>A year is left unsaid on purpose: "$120k" already reads as a salary, and
 * the API's own labels follow the same rule (salaryPeriodSuffix in
 * CandidateJobSearchService). ONE_TIME is said in words because a bare "$10k"
 * stipend would otherwise read as a year's pay. An interval missing from this
 * map, or no interval at all, adds nothing: a bare amount is shown rather than
 * a guessed unit.
 */
const PAY_PERIOD_SUFFIXES: Record<string, string> = {
  HOUR: '/hr',
  DAY: '/day',
  WEEK: '/wk',
  MONTH: '/mo',
  ONE_TIME: ' total',
};

/**
 * A unit the label already states, in the spellings the boards and the API
 * use: "/hr", "per hour", "an hour", "hourly", "a year", "annual", "total".
 */
const STATED_PAY_UNIT =
  /\/\s*(?:hr|hour|h|yr|year|mo|month|wk|week|day)\b|\b(?:per|an?|each)\s+(?:hour|year|annum|month|week|day)\b|\b(?:hourly|annually|annual|yearly|monthly|weekly|daily|total)\b/i;

/**
 * Whether a salary label is a figure an employer posted.
 *
 * <p>The feed fills salaryLabel with "Salary not listed" rather than leaving it
 * blank. A label whose only digits are zeros is dropped too: the feed rounds to
 * thousands whenever the board stated no interval, so an amount that was really
 * a rate came back as "USD $0k-$0k" -- which reads as an employer saying the
 * job pays nothing.
 */
export function hasPostedPay(label: string | null | undefined): boolean {
  const salary = (label || '').trim().toLowerCase();
  const onlyZeros = /[0-9]/.test(salary) && !/[1-9]/.test(salary);
  return Boolean(
    salary
    && !onlyZeros
    && !salary.includes('not listed')
    && !salary.includes('benchmark needed')
    && salary !== 'n/a'
  );
}

/**
 * Pay as a figure worth reading at a glance, or '' when none was posted.
 *
 * <p>The currency prefix is dropped for US dollars, where the dollar sign
 * already says it; ranges get an en dash; "$21.3" becomes "$21.30". When the
 * API says the figure is not yearly and the label does not already say so, the
 * unit is added: an hourly "USD $25" read as $25 a year. A label that already
 * states a unit is left as it is, so "/hr" is never doubled.
 */
export function formatPayLabel(label: string | null | undefined, salaryPeriod?: string | null): string {
  if (!hasPostedPay(label)) {
    return '';
  }

  const trimmed = (label || '').trim().replace(/^USD\s+/i, '');
  // "$21.3" is a rounding artifact; money is written with two decimals.
  const cents = trimmed.replace(/(\d)\.(\d)(?![\dkKmM])/g, '$1.$20');
  const dashed = cents.startsWith('$')
    ? cents.replace(/\s*-\s*\$?/g, '–$')
    : cents.replace(/\s*-\s*/g, '–');

  const suffix = PAY_PERIOD_SUFFIXES[(salaryPeriod || '').trim().toUpperCase()] ?? '';
  return suffix && !STATED_PAY_UNIT.test(dashed) ? `${dashed}${suffix}` : dashed;
}

/**
 * A location as it should read, with the debris some boards leave in it.
 *
 * <p>Store postings arrive as "Park Meadows Mall, Lone Tree, CO ( )": the board
 * has a slot for a second line and sends it empty. Empty brackets, doubled or
 * dangling separators and runs of spaces are taken out here, at display time,
 * so the stored posting stays exactly what the source sent.
 */
export function cleanLocationLabel(location: string | null | undefined): string {
  return (location || '')
    // Brackets with nothing in them but spaces or separators.
    .replace(/[([][\s,;:·|/–—-]*[)\]]/g, ' ')
    // A space before a comma or semicolon.
    .replace(/\s+([,;])/g, '$1')
    // The same separator twice, or a comma run into a semicolon.
    .replace(/([,;])(?:\s*[,;])+/g, '$1')
    .replace(/·(?:\s*·)+/g, '·')
    .replace(/\s{2,}/g, ' ')
    // Separators left hanging at either end.
    .replace(/^[\s,;:·|/–—-]+|[\s,;:·|/–—-]+$/g, '')
    .trim();
}

/**
 * The moment an interview starts. Interviews store the wall-clock time the
 * booker entered and the booker's IANA time zone, so a teammate elsewhere sees
 * it in their own time. Without a zone (interviews booked before zones were
 * kept) the time is read as this browser's local time, as it always was.
 *
 * Across a clock change it settles the time the way the API's calendar invites
 * do (Java's LocalDateTime.atZone): a time that happens twice, when the clocks
 * go back, is the first of the two; a time the clocks skip, when they go
 * forward, moves on by the length of the skip (2:30 becomes 3:30).
 */
export function wallTimeToDate(wallTime: string, timeZone?: string | null): Date {
  const local = wallTime.replace(/Z$/, '');
  if (!timeZone) return new Date(local);
  const asUtc = new Date(`${local}Z`);
  if (Number.isNaN(asUtc.getTime())) return new Date(local);
  try {
    const wall = asUtc.getTime();
    // The zone's offsets a day either side: any clock change near this time
    // lies between them.
    const before = zoneOffsetMs(wall - DAY_MS, timeZone);
    const after = zoneOffsetMs(wall + DAY_MS, timeZone);
    const valid = [wall - before, wall - after].filter((instant) => zoneOffsetMs(instant, timeZone) === wall - instant);
    return new Date(valid.length > 0 ? Math.min(...valid) : wall - before);
  } catch {
    return new Date(local);
  }
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** One formatter per zone: building one is far slower than using it. */
const zoneFormatters = new Map<string, Intl.DateTimeFormat>();

function zoneFormatter(timeZone: string): Intl.DateTimeFormat {
  let formatter = zoneFormatters.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    zoneFormatters.set(timeZone, formatter);
  }
  return formatter;
}

/** How far the zone's clocks are ahead of UTC at this instant, in milliseconds. */
function zoneOffsetMs(instant: number, timeZone: string): number {
  const parts = zoneFormatter(timeZone).formatToParts(new Date(instant));
  const part = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  const wall = Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second'));
  return wall - Math.floor(instant / 1000) * 1000;
}

/** This browser's IANA time zone, e.g. America/New_York, when it reports one. */
export function browserTimeZone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined;
  } catch {
    return undefined;
  }
}
