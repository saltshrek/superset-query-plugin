// docs/features/008-show-outlines.md (Last modified): how a file's date is
// shown in its row.
import { shortDate } from '../src/dates';

const now = new Date('2025-06-15T12:00:00Z');
const before = (ms: number) => new Date(now.getTime() - ms);
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

test.each([
  [before(0), 'just now'],
  [before(59 * SECOND), 'just now'],
  [new Date(now.getTime() + HOUR), 'just now'], // a clock out of step
  [before(MINUTE), '1 minute ago'],
  [before(5 * MINUTE + 30 * SECOND), '5 minutes ago'],
  [before(59 * MINUTE), '59 minutes ago'],
  [before(HOUR), '1 hour ago'],
  [before(23 * HOUR), '23 hours ago'],
  [before(DAY), 'yesterday'],
  [before(3 * DAY), '3 days ago'],
  [before(29 * DAY), '29 days ago'],
  [before(30 * DAY), 'May 2025'],
  [new Date('2024-03-12T14:03:00Z'), 'Mar 2024'],
])('%s is shown as "%s"', (date, shown) => {
  expect(shortDate(date, now, 'en-US')).toBe(shown);
});
