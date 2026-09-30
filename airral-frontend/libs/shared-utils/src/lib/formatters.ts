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
