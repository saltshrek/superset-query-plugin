// How the panel shows when a file last changed (008).

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** Up to how many days old a date is shown relatively. */
const RELATIVE_DAYS = 30;

/**
 * `date` as seen from `now`: relative under 30 days ("3 hours ago",
 * "yesterday"), otherwise month and year ("Mar 2025"). A date in the future
 * (a clock out of step) is "just now".
 */
export function shortDate(date: Date, now: Date, locale?: string): string {
  const ago = now.getTime() - date.getTime();
  if (ago < MINUTE) return 'just now';
  const relative = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  if (ago < HOUR) return relative.format(-Math.floor(ago / MINUTE), 'minute');
  if (ago < DAY) return relative.format(-Math.floor(ago / HOUR), 'hour');
  const days = Math.floor(ago / DAY);
  if (days < RELATIVE_DAYS) return relative.format(-days, 'day');
  return new Intl.DateTimeFormat(locale, {
    month: 'short',
    year: 'numeric',
  }).format(date);
}

/** `date` in full, to the minute ("12 March 2025 at 14:03"). */
export const fullDate = (date: Date, locale?: string): string =>
  new Intl.DateTimeFormat(locale, {
    dateStyle: 'long',
    timeStyle: 'short',
  }).format(date);

/** An API timestamp as a Date, or null if it isn't one. */
export function parseTimestamp(value: unknown): Date | null {
  if (typeof value !== 'string') return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}
