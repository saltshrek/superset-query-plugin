# 001: "folders offer no file actions" fails intermittently

**Status:** open
**Found:** 2026-10-12, while building 006
**Area:** plugin frontend tests (`plugin/frontend/test/Panel.test.tsx`)

## Symptom

The 004 test "folders offer no file actions" fails whenever it runs on
its own, and sometimes in the full suite:

```
● folders offer no file actions

  expect(received).toContain(expected) // indexOf

  Expected value: "top.sql"
  Received array: ["orders"]
```

The folder ends up collapsed: the test double-clicks `orders` and then
waits for its contents to show.

```ts
test('folders offer no file actions', async () => {
  await serve({ 'orders/top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  expect(
    within(row('orders')).queryByRole('button', { name: 'Open in new tab' }),
  ).not.toBeInTheDocument();

  await user.dblClick(nameOf('orders'));
  await waitFor(() => expect(rows()).toContain('top.sql'));
  expect(createdTabs).toEqual([]);
});
```

## Reproduce

In `plugin/frontend/` (`nvm use` first):

```
npx jest -t "folders offer no file actions"
```

## What we know

- **It isn't caused by 006.** It fails on its own on `f306424`, the
  commit before 006 began.
- **Run on its own: fails every time** (5 of 5 runs on 2026-10-12).
- **With one other test before it: still fails.** We tried "Enter on the
  active file opens it in a new tab" and "lists the top level…".
- **Full suite: usually passes,** but failed once in three runs on
  2026-10-12.
- **Same failure each time:** the folder is collapsed after the
  double-click.

## Hypothesis (unconfirmed)

- **Two toggles from one double-click.** user-event's `dblClick` sends
  click, click, dblclick. antd's `DirectoryTree` toggles a folder on each
  click (its `expandAction` is `click`), so the folder opens and closes
  again.
- **So what decides the outcome is probably timing.** The panel loads a
  folder's contents in `onExpand`. Whether the folder ends up open seems
  to depend on how the two toggles interleave with that load, which other
  tests in the run (warm JIT, a warm code-source) may shift.
- **In a real browser,** double-clicking a folder probably also opens and
  closes it. That may be fine, since 004 only promises that folders offer
  no file actions. But it means the test may be asserting something the
  spec doesn't promise (that a double-clicked folder ends up open).

## Possible directions

- **Assert only what 004 promises:** no tab is created and no file
  actions appear. Don't depend on the folder's final state. The test
  could wait for the folder's load to settle instead of waiting for
  `top.sql` to appear.
- **Or decide what a double-click on a folder should do,** add that to
  003's spec, and test it.
