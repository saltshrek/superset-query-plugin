# 003: A row's date shows under its actions in older Chrome

**Status:** fixed
**Found:** 2026-10-29, using the panel in Chrome 100
**Area:** plugin frontend (`plugin/frontend/src/Panel.tsx`,
`plugin/frontend/src/SearchResults.tsx`)

## Symptom

Hovering a row, or making it active with the keyboard, shows its action
buttons, but its date stays visible underneath them, in the tree and in
search results. 008 says the actions take the date's place.

## Cause

The rule that hides the date lists three selectors: hovered, active,
and a row whose actions are busy. The busy one uses `:has()`, which
Chrome supports from 105. A browser that doesn't understand one selector
in a list drops the whole rule, so the date is never hidden. The rule
that shows the actions has no `:has()`, so the actions still appear.

## Fix

The row says it's busy itself, rather than CSS working that out from
inside it. The tree's node for a busy row gets a class, and a busy
search result an attribute. The date is hidden by plain selectors on
those, so the rule has nothing Chrome 100 lacks. Both lists of rows
already know which rows are busy: their keys match the keys that file
actions mark busy.

Alternatives considered:

- Splitting the busy selector into a rule of its own: a one-line fix
  for hover and active, but in Chrome before 105 a busy row's spinner
  would still sit over its date once the pointer moved off.
- A `busy` prop through `RowLabel`, `QueryLabel` and `CteLabel`: works,
  but threads state through three components to set what is a row's
  style.

The panel supports Chrome 100 and later from now on (see
`docs/decisions.md`). No other CSS or JS in the panel is newer than
that: the JS is compiled to ES2019.

## Testing

No automated test. jsdom supports `:has()`, so a test can't reproduce
the symptom. Checking the stylesheet's text for `:has(` would test the
code rather than the behaviour. Checked by eye in Chrome 100 (or a
Chromium of that version) against the dev stack: hover, keyboard-active
and busy rows, in the tree and in results.
