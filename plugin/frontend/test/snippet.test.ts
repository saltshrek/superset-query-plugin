// docs/features/014-search-descriptions-and-ctes.md (Snippet of a
// description): what a search result shows of a description that matched.
import { snippet } from '../src/snippet';

const m = (start: number, end: number) => ({ start, end });

test('a short description is shown whole, its matches where they were', () => {
  expect(snippet('Orders and revenue per day.', [m(11, 18)])).toEqual({
    text: 'Orders and revenue per day.',
    cut: false,
    matches: [m(11, 18)],
  });
});

test('whitespace runs, paragraph breaks included, become single spaces, and the matches move with the text', () => {
  expect(snippet('  Revenue  per\nmonth.\n\nOne row each. ', [m(2, 9), m(15, 20)])).toEqual({
    text: 'Revenue per month. One row each.',
    cut: false,
    matches: [m(0, 7), m(12, 17)],
  });
});

test('a first match that ends within the first 60 characters shows from the start', () => {
  const description = `${'aaaa '.repeat(11)}match and more`; // "match" ends at 60
  expect(snippet(description, [m(55, 60)])).toEqual({
    text: description,
    cut: false,
    matches: [m(55, 60)],
  });
});

test('a first match further in starts three words before its word, never inside a word', () => {
  // "join" is at 59 to 63, inside the word "self-join", which starts at 54.
  const description = `${'aaaa '.repeat(8)}one two three self-join pairs them`;
  expect(snippet(description, [m(59, 63)])).toEqual({
    text: 'one two three self-join pairs them',
    cut: true,
    matches: [m(19, 23)],
  });
});

test('every match is kept, after the first too', () => {
  const description = `${'aaaa '.repeat(12)}total so far, and a total for the year`;
  // "total" at 60 and 79.
  expect(snippet(description, [m(60, 65), m(79, 84)])).toEqual({
    text: 'aaaa aaaa aaaa total so far, and a total for the year',
    cut: true,
    matches: [m(15, 20), m(34, 39)],
  });
});

test('with fewer than three words before the first match, it starts at the start', () => {
  const description = `${'a'.repeat(70)} total`;
  expect(snippet(description, [m(71, 76)])).toEqual({
    text: description,
    cut: false,
    matches: [m(71, 76)],
  });
});

test('characters are counted in code points', () => {
  // Thirty emoji words: 60 code points, but 90 UTF-16 units.
  const description = `${'😀 '.repeat(30)}total`;
  expect(snippet(description, [m(60, 65)])).toEqual({
    text: '😀 😀 😀 total',
    cut: true,
    matches: [m(6, 11)],
  });
});

test('matches outside the description are left out', () => {
  expect(snippet('Revenue.', [m(0, 7), m(5, 40), m(3, 3)])).toEqual({
    text: 'Revenue.',
    cut: false,
    matches: [m(0, 7)],
  });
});
