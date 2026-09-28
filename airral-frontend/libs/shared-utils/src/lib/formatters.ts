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
 */
export function wallTimeToDate(wallTime: string, timeZone?: string | null): Date {
  const local = wallTime.replace(/Z$/, '');
  if (!timeZone) return new Date(local);
  const asUtc = new Date(`${local}Z`);
  if (Number.isNaN(asUtc.getTime())) return new Date(local);
  try {
    // Two passes settle the offset across a daylight-saving change.
    let guess = asUtc.getTime() - zoneOffsetMs(asUtc, timeZone);
    guess = asUtc.getTime() - zoneOffsetMs(new Date(guess), timeZone);
    return new Date(guess);
  } catch {
    return new Date(local);
  }
}

function zoneOffsetMs(at: Date, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(at);
  const part = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  const wall = Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second'));
  return wall - Math.floor(at.getTime() / 1000) * 1000;
}

/** This browser's IANA time zone, e.g. America/New_York, when it reports one. */
export function browserTimeZone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined;
  } catch {
    return undefined;
  }
}
