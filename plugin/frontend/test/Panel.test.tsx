// docs/features/003-browse-tree.md, 004-use-a-file.md, 006-filename-search.md,
// 008-show-outlines.md, 009-use-a-cte.md, 012-use-a-query.md,
// 013-search-by-outline.md and 015-panel-polish.md: the panel against a real
// code-source.
import React from 'react';
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent, {
  PointerEventsCheckLevel,
} from '@testing-library/user-event';
import { StyleProvider } from '@ant-design/cssinjs';
import { computeAccessibleName } from 'dom-accessibility-api';
import '../src/index';
import { createdTabs, registeredViews } from './fakes/superset-core';
import { FixtureRepo } from './fixtureRepo';
import { CodeSource } from './codeSource';
import { forwardBackendTo } from './backend';

// Skips user-event's check that a click target accepts pointer events: it
// calls getComputedStyle up the tree, which is slow in jsdom with antd's
// styles loaded (see setup.ts). The panel never sets pointer-events.
const setupUser = () =>
  userEvent.setup({ pointerEventsCheck: PointerEventsCheckLevel.Never });

let repo: FixtureRepo;
let codeSource: CodeSource;

async function serve(files: Record<string, string>) {
  repo = new FixtureRepo();
  repo.commit(files);
  codeSource = await CodeSource.start(repo.url);
  forwardBackendTo(() => codeSource.url);
}

afterEach(async () => {
  await codeSource?.remove();
  repo?.remove();
});

// antd's styles go into a detached element, not the document. With them
// all in the document (over 1,000 selectors once the search box's are
// added), jsdom's selector cache thrashes and every getComputedStyle call
// (made for each accessible name) takes a fraction of a second. No test
// depends on antd's styles.
const antdStyles = document.createElement('div');

function renderPanel() {
  const view = registeredViews.find(v => v.id === 'quillfern.sql-examples.panel');
  expect(view?.location).toBe('sqllab.leftSidebarBottom');
  return render(
    <StyleProvider container={antdStyles}>{view!.provider()}</StyleProvider>,
  );
}

/** The tree's rows by name, top to bottom. Decoration on a row (008's
 * dates) describes it rather than naming it. */
function rows(): string[] {
  return within(screen.getByRole('tree'))
    .queryAllByRole('treeitem')
    .map(row => computeAccessibleName(row));
}

// A row's accessible name may start with its icons' labels ("folder orders").
const row = (name: string) =>
  within(screen.getByRole('tree')).getByRole('treeitem', {
    name: (accessibleName: string) => accessibleName.split(' ').pop() === name,
  });

// What a user clicks: the row's name.
const nameOf = (name: string) => within(row(name)).getByText(name);

test('lists the top level: folders first, then SQL files, without dot-entries', async () => {
  await serve({
    'b.sql': 'select 2',
    'A.SQL': 'select 1',
    'README.md': '# examples',
    'notes.txt': 'notes',
    '.hidden.sql': 'select 0',
    '.github/ci.yml': 'on: push',
    'orders/top.sql': 'select 3',
    'customers/new.sql': 'select 4',
    'docs/README.md': '# no SQL in here',
  });

  renderPanel();

  expect(await screen.findByRole('tree')).toBeInTheDocument();
  expect(rows()).toEqual(['customers', 'docs', 'orders', 'A', 'b']);
});

test('shows that it is loading until the top level arrives', async () => {
  await serve({ 'a.sql': 'select 1' });

  renderPanel();

  expect(screen.getByText(/loading/i)).toBeInTheDocument();
  expect(await screen.findByRole('tree')).toBeInTheDocument();
  expect(screen.queryByText(/loading/i)).not.toBeInTheDocument();
});

test('expanding a folder loads and shows its contents', async () => {
  await serve({
    'orders/top.sql': 'select 1',
    'orders/regional/by_region.sql': 'select 2',
    'orders/NOTES.md': 'notes',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  expect(rows()).toEqual(['orders']);

  await user.click(nameOf('orders'));

  await waitFor(() =>
    expect(rows()).toEqual(['orders', 'regional', 'top']),
  );
  expect(row('orders')).toHaveAttribute('aria-expanded', 'true');

  await user.click(nameOf('regional'));

  await waitFor(() =>
    expect(rows()).toEqual(['orders', 'regional', 'by_region', 'top']),
  );

  await user.click(nameOf('orders'));

  await waitFor(() => expect(rows()).toEqual(['orders']));
});

test('a folder with nothing to show says so', async () => {
  await serve({ 'docs/README.md': '# no SQL', 'a.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await user.click(nameOf('docs'));

  expect(await screen.findByText(/no sql examples here/i)).toBeInTheDocument();
});

test('says so when the repository has no SQL examples', async () => {
  await serve({ 'README.md': '# nothing yet', '.github/ci.yml': 'on: push' });

  renderPanel();

  expect(await screen.findByText(/no sql examples found/i)).toBeInTheDocument();
});

// Presses an arrow key on whatever has focus. Not user.keyboard(): rc-tree
// reads the legacy KeyboardEvent.keyCode, which browsers set and user-event
// doesn't.
const KEY_CODES = {
  Enter: 13,
  ArrowLeft: 37,
  ArrowUp: 38,
  ArrowRight: 39,
  ArrowDown: 40,
};
function press(key: keyof typeof KEY_CODES) {
  fireEvent.keyDown(document.activeElement!, {
    key,
    code: key,
    keyCode: KEY_CODES[key],
  });
}

test('folders expand and collapse with the keyboard', async () => {
  await serve({
    'customers/new.sql': 'select 1',
    'orders/top.sql': 'select 2',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await user.tab(); // the Reload button
  await user.tab(); // the search box
  await user.tab(); // the tree
  press('ArrowDown');
  press('ArrowDown');
  press('ArrowRight');

  await waitFor(() =>
    expect(rows()).toEqual(['customers', 'orders', 'top']),
  );

  press('ArrowLeft');

  await waitFor(() => expect(rows()).toEqual(['customers', 'orders']));
});

test('moving with the keyboard scrolls the active row into view', async () => {
  await serve({
    'customers/new.sql': 'select 1',
    'orders/top.sql': 'select 2',
    'regions/by_region.sql': 'select 3',
  });
  // jsdom has no layout, nor scrollIntoView: record which rows were shown.
  const shown: Element[] = [];
  Element.prototype.scrollIntoView = function scrollIntoView(this: Element) {
    shown.push(this);
  };
  try {
    const user = setupUser();
    renderPanel();
    await screen.findByRole('tree');

    await user.tab(); // the Reload button
    await user.tab(); // the search box
    await user.tab(); // the tree
    press('ArrowDown');
    press('ArrowDown');
    press('ArrowDown');

    await waitFor(() => expect(shown[shown.length - 1]).toBe(row('regions')));
    press('ArrowUp');
    await waitFor(() => expect(shown[shown.length - 1]).toBe(row('orders')));
  } finally {
    delete (Element.prototype as Partial<Element>).scrollIntoView;
  }
});

test('the keyboard works straight after clicking a row', async () => {
  await serve({
    'customers/new.sql': 'select 1',
    'orders/top.sql': 'select 2',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await user.click(nameOf('orders'));
  await waitFor(() =>
    expect(rows()).toEqual(['customers', 'orders', 'top']),
  );

  press('ArrowLeft');
  await waitFor(() => expect(rows()).toEqual(['customers', 'orders']));

  press('ArrowUp');
  press('ArrowRight');
  await waitFor(() =>
    expect(rows()).toEqual(['customers', 'new', 'orders']),
  );
});

test('a failure to load the top level can be retried', async () => {
  await serve({ 'a.sql': 'select 1' });
  await codeSource.stop();
  const user = setupUser();

  renderPanel();

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent(/unreachable/i);

  await codeSource.start();
  await user.click(within(alert).getByRole('button', { name: /retry/i }));

  expect(await screen.findByRole('tree')).toBeInTheDocument();
  expect(rows()).toEqual(['a']);
});

test('a failure to load a folder is shown in the folder and can be retried', async () => {
  await serve({ 'orders/top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await codeSource.stop();

  await user.click(nameOf('orders'));

  expect(await screen.findByText(/unreachable/i)).toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument(); // the tree stays usable

  await codeSource.start();
  await user.click(screen.getByRole('button', { name: /retry/i }));

  await waitFor(() => expect(rows()).toEqual(['orders', 'top']));
});

test('Reload shows refreshed contents and collapses to the top level', async () => {
  await serve({ 'orders/top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await user.click(nameOf('orders'));
  await waitFor(() => expect(rows()).toEqual(['orders', 'top']));

  repo.commit({
    'orders/top.sql': 'select 1',
    'customers/new.sql': 'select 2',
  });
  await codeSource.refresh();
  expect(rows()).toEqual(['orders', 'top']); // nothing changes until Reload

  await user.click(screen.getByRole('button', { name: 'Reload' }));

  await waitFor(() => expect(rows()).toEqual(['customers', 'orders']));
  expect(row('orders')).toHaveAttribute('aria-expanded', 'false');
});

// 004: use a file.

beforeEach(() => {
  createdTabs.length = 0;
});

// A file's row, found by its name.
const fileRow = (name: string) =>
  within(screen.getByRole('tree')).getByRole('treeitem', { name });

const openButton = (name: string) =>
  within(fileRow(name)).getByRole('button', { name: 'Open in new tab' });

test('"Open in new tab" opens the file in a new tab named after it', async () => {
  await serve({
    'orders/top_products.sql': 'SELECT *\nFROM products;\n',
    'orders/other.sql': 'select 2',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await user.click(nameOf('orders'));
  await waitFor(() => expect(rows()).toContain('top_products'));

  await user.click(openButton('top_products'));

  await waitFor(() =>
    expect(createdTabs).toEqual([
      { sql: 'SELECT *\nFROM products;\n', title: 'top_products' },
    ]),
  );
  // The click opened the file and nothing else.
  expect(row('orders')).toHaveAttribute('aria-expanded', 'true');
});

test('double-clicking a file opens it in a new tab', async () => {
  await serve({ 'Top.SQL': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await user.dblClick(within(fileRow('Top')).getByText('Top'));

  await waitFor(() =>
    expect(createdTabs).toEqual([{ sql: 'select 1', title: 'Top' }]),
  );
});

test('Enter on the active file opens it in a new tab', async () => {
  await serve({ 'orders/top.sql': 'select 1', 'a.sql': 'select 2' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await user.tab(); // the Reload button
  await user.tab(); // the search box
  await user.tab(); // the tree
  press('ArrowDown'); // orders
  press('Enter');
  press('ArrowDown'); // a
  press('Enter');

  await waitFor(() =>
    expect(createdTabs).toEqual([{ sql: 'select 2', title: 'a' }]),
  );
});

test('folders offer no file actions', async () => {
  await serve({ 'orders/top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  expect(
    within(row('orders')).queryByRole('button', { name: 'Open in new tab' }),
  ).not.toBeInTheDocument();

  await user.dblClick(nameOf('orders'));
  await waitFor(() => expect(rows()).toContain('top'));
  expect(createdTabs).toEqual([]);
});

test('a failure to load the file is shown, naming the file', async () => {
  await serve({ 'top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await codeSource.stop();

  await user.click(openButton('top'));

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('top');
  expect(alert).toHaveTextContent(/unreachable/i);
  expect(createdTabs).toEqual([]);

  // Trying again is the retry.
  await codeSource.start();
  await user.click(openButton('top'));

  await waitFor(() => expect(createdTabs).toHaveLength(1));
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});

test('a failure names the file without .sql (016)', async () => {
  await serve({ 'top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await codeSource.stop();

  await user.click(openButton('top'));

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent(/Couldn't use top:/);
  expect(alert).not.toHaveTextContent('.sql');
});

test('the error can be closed', async () => {
  await serve({ 'top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await codeSource.stop();
  await user.click(openButton('top'));
  const alert = await screen.findByRole('alert');

  await user.click(within(alert).getByRole('button', { name: /close/i }));

  await waitFor(() =>
    expect(screen.queryByRole('alert')).not.toBeInTheDocument(),
  );
});

test('each use gets the contents code-source serves now', async () => {
  await serve({ 'top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await user.click(openButton('top'));
  await waitFor(() => expect(createdTabs).toHaveLength(1));

  repo.commit({ 'top.sql': 'select 2' });
  await codeSource.refresh();
  await user.click(openButton('top'));

  await waitFor(() =>
    expect(createdTabs.map(t => t.sql)).toEqual(['select 1', 'select 2']),
  );
});

// user-event's setup() puts a clipboard stub on navigator (jsdom has none),
// which the panel writes to and the tests read back.
const copyButton = (name: string) =>
  within(fileRow(name)).getByRole('button', { name: 'Copy to clipboard' });

test('"Copy to clipboard" copies the file and confirms it', async () => {
  await serve({ 'orders/top.sql': 'SELECT *\nFROM orders;\n' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await user.click(nameOf('orders'));
  await waitFor(() => expect(rows()).toContain('top'));

  await user.click(copyButton('top'));

  expect(await screen.findByRole('tooltip')).toHaveTextContent('Copied');
  expect(await navigator.clipboard.readText()).toBe('SELECT *\nFROM orders;\n');
  expect(createdTabs).toEqual([]);
});

test('a failure to load the file for copying is shown, naming the file', async () => {
  await serve({ 'top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await navigator.clipboard.writeText('before');
  await codeSource.stop();

  await user.click(copyButton('top'));

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('top');
  expect(alert).toHaveTextContent(/unreachable/i);
  expect(await navigator.clipboard.readText()).toBe('before');
});

test('a refused clipboard write is shown', async () => {
  await serve({ 'top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  jest
    .spyOn(navigator.clipboard, 'writeText')
    .mockRejectedValue(new DOMException('Denied', 'NotAllowedError'));

  await user.click(copyButton('top'));

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('top');
  expect(alert).toHaveTextContent(/didn't allow copying/i);
});

// 006: search by file name.

const searchBox = () => screen.getByRole('textbox', { name: 'Search examples' });

// The results by name, which is each one's path. Decoration on a result
// (013's tables and dates) describes it rather than naming it, and 013's
// suggestions, in the same list, aren't results. Nor are the rows of
// queries and CTEs under them (014), named as in the tree.
const resultGroup = () => screen.queryByRole('group', { name: 'Examples' });

/** Every row of the results, by name: files, and the queries and CTEs under them. */
function resultRows(): string[] {
  const group = resultGroup();
  return group
    ? within(group)
        .queryAllByRole('option')
        .map(option => computeAccessibleName(option))
    : [];
}

// Results are SQL files, which unlike the rows under them have no icon (015).
const PART_ICONS = ['console-sql', 'block'];
function results(): string[] {
  const group = resultGroup();
  return group
    ? within(group)
        .queryAllByRole('option')
        .filter(
          option =>
            !within(option)
              .queryAllByRole('img')
              .some(icon => PART_ICONS.includes(icon.getAttribute('aria-label') ?? '')),
        )
        .map(option => computeAccessibleName(option))
    : [];
}

const result = (path: string) =>
  within(resultGroup()!).getByRole('option', { name: path });

async function searchFor(user: ReturnType<typeof setupUser>, text: string) {
  await user.clear(searchBox());
  await user.type(searchBox(), text);
}

test('typing shows the SQL files whose path has every word, the last as a prefix', async () => {
  await serve({
    'customers/new_customers_by_month.sql': 'select 1',
    'customers/churn.sql': 'select 2',
    'orders/new_orders.sql': 'select 3',
    'customers/new_customer_notes.md': 'notes',
    '.github/new_customers.sql': 'select 4',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'new cust');

  await waitFor(() =>
    expect(results()).toEqual(['customers/new_customers_by_month']),
  );
  expect(screen.getByRole('tree')).not.toBeVisible();

  await searchFor(user, 'New');

  await waitFor(() =>
    expect(results()).toEqual([
      'customers/new_customers_by_month',
      'orders/new_orders',
    ]),
  );
});

test('words are split at punctuation and case changes', async () => {
  await serve({ 'reports/monthlyChurnRate.sql': 'select 1', 'other.sql': '' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'churn_rate');

  await waitFor(() => expect(results()).toEqual(['reports/monthlyChurnRate']));
});

test('matches in the file name rank above matches only in folder names', async () => {
  await serve({
    'orders/summary.sql': 'select 1',
    'reports/orders_by_day.sql': 'select 2',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'orders');

  await waitFor(() =>
    expect(results()).toEqual(['reports/orders_by_day', 'orders/summary']),
  );
});

test('each result highlights the parts of its path that matched', async () => {
  await serve({ 'customers/new_customers_by_month.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'new cust');

  const found = await waitFor(() =>
    result('customers/new_customers_by_month'),
  );
  const marks = (element: Element) =>
    Array.from(element.querySelectorAll('mark')).map(m => m.textContent);
  expect(marks(found)).toEqual(['new', 'cust']);
  // The folder is in the tooltip (014).
  await user.hover(within(found).getByText('new'));
  const tooltip = await screen.findByRole('tooltip', {}, TOOLTIP_WAIT);
  expect(tooltip).toHaveTextContent('customers');
  expect(marks(tooltip)).toEqual(['cust']);
});

test("a word matched in a file's .sql isn't shown, since its name isn't (016)", async () => {
  await serve({ 'top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'top sql');

  await waitFor(() => expect(results()).toEqual(['top']));
  const found = result('top');
  expect(found).toHaveTextContent(/^top/);
  expect(Array.from(found.querySelectorAll('mark')).map(m => m.textContent)).toEqual([
    'top',
  ]);
});

test('a result can be opened in a new tab or copied, as in the tree', async () => {
  await serve({ 'orders/top_products.sql': 'select 1', 'top.sql': 'select 2' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await searchFor(user, 'top');
  await waitFor(() =>
    expect(results()).toEqual(['orders/top_products', 'top']),
  );

  await user.click(
    within(result('orders/top_products')).getByRole('button', {
      name: 'Open in new tab',
    }),
  );
  await waitFor(() =>
    expect(createdTabs).toEqual([{ sql: 'select 1', title: 'top_products' }]),
  );

  await user.dblClick(result('top'));
  await waitFor(() => expect(createdTabs).toHaveLength(2));
  expect(createdTabs[1]).toEqual({ sql: 'select 2', title: 'top' });

  await user.click(
    within(result('top')).getByRole('button', { name: 'Copy to clipboard' }),
  );
  expect(await screen.findByRole('tooltip')).toHaveTextContent('Copied');
  expect(await navigator.clipboard.readText()).toBe('select 2');
});

test('a search that finds nothing says so and suggests fewer words', async () => {
  await serve({ 'top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'top zzz');

  expect(await screen.findByText(/no sql examples match/i)).toHaveTextContent(
    /fewer words/i,
  );
  expect(results()).toEqual([]);
});

test('a search with more than 50 results shows 50 and says how many there are', async () => {
  const files: Record<string, string> = {};
  for (let i = 1; i <= 55; i += 1) files[`f_${String(i).padStart(2, '0')}.sql`] = '';
  await serve(files);
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'f');

  expect(await screen.findByText(/showing 50 of 55/i)).toHaveTextContent(
    /add words/i,
  );
  expect(results()).toHaveLength(50);
});

test('clearing the search restores the tree, with its expanded folders', async () => {
  await serve({ 'orders/top.sql': 'select 1', 'customers/new.sql': 'select 2' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await user.click(nameOf('orders'));
  await waitFor(() => expect(rows()).toEqual(['customers', 'orders', 'top']));

  await searchFor(user, 'new');
  await waitFor(() => expect(results()).toEqual(['customers/new']));

  await user.clear(searchBox());

  expect(screen.getByRole('tree')).toBeVisible();
  expect(rows()).toEqual(['customers', 'orders', 'top']);
  expect(results()).toEqual([]);
});

test('search can be used with the keyboard alone', async () => {
  await serve({ 'a_top.sql': 'select 1', 'b_top.sql': 'select 2', 'c.sql': '' });
  const user = setupUser();
  render(<textarea aria-label="SQL editor" />);
  renderPanel();
  await screen.findByRole('tree');
  screen.getByRole('textbox', { name: 'SQL editor' }).focus();

  fireEvent.keyDown(document.activeElement!, {
    key: 'S',
    code: 'KeyS',
    ctrlKey: true,
    shiftKey: true,
  });
  expect(searchBox()).toHaveFocus();

  await user.keyboard('top');
  await waitFor(() => expect(results()).toEqual(['a_top', 'b_top']));

  press('ArrowDown'); // into the results: a_top
  press('ArrowDown'); // b_top
  press('Enter');
  await waitFor(() =>
    expect(createdTabs).toEqual([{ sql: 'select 2', title: 'b_top' }]),
  );

  press('ArrowUp'); // a_top
  press('ArrowUp'); // back to the box
  expect(searchBox()).toHaveFocus();

  press('ArrowDown');
  fireEvent.keyDown(document.activeElement!, { key: 'Escape', code: 'Escape' });

  expect(searchBox()).toHaveFocus();
  expect(searchBox()).toHaveValue('');
  expect(screen.getByRole('tree')).toBeVisible();
});

test('with the search box empty, the arrow keys loop between it and the tree', async () => {
  await serve({ 'a.sql': 'select 1', 'b.sql': 'select 2', 'c.sql': 'select 3' });
  renderPanel();
  await screen.findByRole('tree');
  searchBox().focus();

  press('ArrowDown'); // into the tree: a
  press('Enter');
  await waitFor(() =>
    expect(createdTabs).toEqual([{ sql: 'select 1', title: 'a' }]),
  );
  press('ArrowUp'); // back to the box
  expect(searchBox()).toHaveFocus();

  press('ArrowUp'); // into the tree from the bottom: c
  press('Enter');
  await waitFor(() =>
    expect(createdTabs).toEqual([
      { sql: 'select 1', title: 'a' },
      { sql: 'select 3', title: 'c' },
    ]),
  );
  press('ArrowDown'); // back to the box
  expect(searchBox()).toHaveFocus();
});

test('a failed search is shown and can be retried', async () => {
  await serve({ 'top.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await codeSource.stop();

  await searchFor(user, 'top');

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent(/unreachable/i);

  await codeSource.start();
  await user.click(within(alert).getByRole('button', { name: /retry/i }));

  await waitFor(() => expect(results()).toEqual(['top']));
});

test('after code-source refreshes, search finds added files and not removed ones', async () => {
  await serve({ 'old_report.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await searchFor(user, 'report');
  await waitFor(() => expect(results()).toEqual(['old_report']));

  repo.commit({ 'new_report.sql': 'select 2' });
  await codeSource.refresh();
  await user.click(screen.getByRole('button', { name: 'Reload' }));

  await waitFor(() => expect(results()).toEqual(['new_report']));
});

// 008: richer information in the tree.

const DAY_MS = 24 * 60 * 60 * 1000;
const daysAgo = (days: number) => new Date(Date.now() - days * DAY_MS);
const MARCH_2024 = new Date('2024-03-12T14:03:00Z');

/** Serves a repository made of `commits`, oldest first. */
async function serveHistory(
  commits: { files: Record<string, string>; date: Date }[],
) {
  repo = new FixtureRepo();
  commits.forEach(({ files, date }) => repo.commit(files, { date }));
  codeSource = await CodeSource.start(repo.url);
  forwardBackendTo(() => codeSource.url);
}

// Long enough for the tooltip's delay, short of the test's timeout.
const TOOLTIP_WAIT = { timeout: 3000 };

/** The tooltip that hovering `name`'s row shows. */
async function hoverTooltip(user: ReturnType<typeof setupUser>, name: string) {
  await user.hover(nameOf(name));
  return screen.findByRole('tooltip', {}, TOOLTIP_WAIT);
}

test('each file shows when it last changed: relatively within 30 days, then month and year', async () => {
  await serveHistory([
    { files: { 'old.sql': 'select 1' }, date: MARCH_2024 },
    {
      files: { 'old.sql': 'select 1', 'recent.sql': 'select 2' },
      date: daysAgo(3),
    },
  ]);

  renderPanel();
  await screen.findByRole('tree');

  expect(row('recent')).toHaveAccessibleDescription('3 days ago');
  expect(within(row('recent')).getByText('3 days ago')).toBeVisible();
  expect(row('old')).toHaveAccessibleDescription('Mar 2024');
});

test('hovering a file shows what its query is for and when it last changed', async () => {
  await serveHistory([
    {
      files: { 'top.sql': '-- Best sellers by revenue.\nselect 1\n' },
      date: MARCH_2024,
    },
  ]);
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  const tooltip = await hoverTooltip(user, 'top');

  expect(tooltip).toHaveTextContent('Best sellers by revenue.');
  expect(tooltip).toHaveTextContent(/Changed .*March.*2024/);
});

test('hovering a file of several queries says how many it has', async () => {
  await serve({
    'two.sql': '-- First.\nselect 1;\n\n-- Second.\nselect 2;\n',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  const tooltip = await hoverTooltip(user, 'two');

  expect(tooltip).toHaveTextContent('2 queries');
  expect(tooltip).not.toHaveTextContent('First.');
});

/** The tooltips shown, by their text. */
const openTooltips = () =>
  screen.queryAllByRole('tooltip').map(tooltip => tooltip.textContent);

test('moving to a row with the keyboard shows its tooltip, and using the mouse hides it', async () => {
  await serve({
    'a.sql': '-- First file.\nselect 1\n',
    'b.sql': '-- Second file.\nselect 2\n',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await user.tab(); // the Reload button
  await user.tab(); // the search box
  await user.tab(); // the tree
  press('ArrowDown');
  await waitFor(
    () => expect(openTooltips()).toEqual([expect.stringContaining('First file.')]),
    TOOLTIP_WAIT,
  );

  press('ArrowDown');
  await waitFor(
    () => expect(openTooltips()).toEqual([expect.stringContaining('Second file.')]),
    TOOLTIP_WAIT,
  );

  fireEvent.mouseMove(screen.getByRole('tree'));
  await waitFor(() => expect(openTooltips()).toEqual([]), TOOLTIP_WAIT);
});

test('clicking a row makes it active without showing its tooltip', async () => {
  await serve({ 'a.sql': '-- First file.\nselect 1\n' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  fireEvent.click(nameOf('a'));
  await new Promise(resolve => setTimeout(resolve, 1000));

  expect(openTooltips()).toEqual([]);
});

test('moving to a search result with the keyboard shows its tooltip', async () => {
  await serve({
    'a_top.sql': '-- First file.\nselect 1\n',
    'b_top.sql': '-- Second file.\nselect 2\n',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await user.click(searchBox());
  await user.keyboard('top');
  await waitFor(() => expect(results()).toEqual(['a_top', 'b_top']));

  press('ArrowDown'); // into the results: a_top
  press('ArrowDown'); // b_top

  await waitFor(
    () => expect(openTooltips()).toEqual([expect.stringContaining('Second file.')]),
    TOOLTIP_WAIT,
  );

  fireEvent.mouseMove(screen.getByRole('listbox', { name: 'Search results' }));
  await waitFor(() => expect(openTooltips()).toEqual([]), TOOLTIP_WAIT);
});

test('hovering a folder shows its description, with its Markdown rendered safely', async () => {
  await serve({
    'orders/README.md':
      '# Orders\n\n' +
      'Queries over **orders**, from [the wiki](https://wiki.example.com/orders), ' +
      '[the guide](guide.md), [a script](javascript:alert(1)), ' +
      '![a diagram](https://example.com/d.png) and <b>raw HTML</b>.\n',
    'orders/top.sql': 'select 1',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  const tooltip = await hoverTooltip(user, 'orders');

  expect(within(tooltip).getByText('orders').tagName).toBe('STRONG');
  const wiki = within(tooltip).getByRole('link', { name: 'the wiki' });
  expect(wiki).toHaveAttribute('href', 'https://wiki.example.com/orders');
  expect(wiki).toHaveAttribute('target', '_blank');
  expect(within(tooltip).getAllByRole('link')).toEqual([wiki]);
  expect(tooltip).toHaveTextContent('the guide, a script, a diagram and');
  expect(tooltip.querySelector('img')).toBeNull();
  expect(tooltip).toHaveTextContent('<b>raw HTML</b>');
});

test('after code-source refreshes, Reload shows new descriptions and dates', async () => {
  await serveHistory([
    { files: { 'top.sql': '-- Old purpose.\nselect 1\n' }, date: MARCH_2024 },
  ]);
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  expect(row('top')).toHaveAccessibleDescription('Mar 2024');

  repo.commit({ 'top.sql': '-- New purpose.\nselect 1\n' });
  await codeSource.refresh();
  await user.click(screen.getByRole('button', { name: 'Reload' }));

  await waitFor(() =>
    expect(row('top')).toHaveAccessibleDescription('just now'),
  );
  expect(await hoverTooltip(user, 'top')).toHaveTextContent(
    'New purpose.',
  );
});

// A row's tables are one pill saying how many, whose tooltip lists them
// (016).
async function expectTablesPill(
  user: ReturnType<typeof setupUser>,
  row: HTMLElement,
  tables: string[],
) {
  const pill = within(row).getByText(String(tables.length));
  expect(pill).toBeVisible();
  await user.hover(pill);
  await waitFor(() =>
    expect(screen.getAllByRole('tooltip').map(t => t.textContent)).toContain(
      tables.join(', '),
    ),
  );
}

test("a file's row shows the tables its query reads, its CTEs' included", async () => {
  await serve({
    'top.sql':
      'with recent as (select * from sales.returns)\n' +
      'select * from recent r\n' +
      'join orders o on o.id = r.order_id\n' +
      'join customers c on c.id = o.customer_id\n',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  expect(row('top')).toHaveAccessibleDescription(
    'customers, orders, sales.returns just now',
  );
  await expectTablesPill(user, row('top'), [
    'customers',
    'orders',
    'sales.returns',
  ]);
});

test('a file of several queries shows no tables on its own row', async () => {
  await serve({
    'two.sql': 'select * from orders;\n\nselect * from customers;\n',
  });
  renderPanel();
  await screen.findByRole('tree');

  expect(row('two')).toHaveAccessibleDescription('just now');
  expect(within(row('two')).queryByText('orders')).not.toBeInTheDocument();
});

test('after code-source refreshes, Reload shows new tables', async () => {
  await serve({ 'top.sql': 'select * from orders' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  expect(row('top')).toHaveAccessibleDescription('orders just now');

  repo.commit({ 'top.sql': 'select * from customers' });
  await codeSource.refresh();
  await user.click(screen.getByRole('button', { name: 'Reload' }));

  await waitFor(() =>
    expect(row('top')).toHaveAccessibleDescription('customers just now'),
  );
});

const TWO_QUERIES = {
  'two.sql':
    '-- Orders by day.\n--\n-- Returns are left out.\nselect * from orders;\n\n' +
    'select * from customers c join regions r on r.id = c.region_id;\n',
};

const queryRow = (name: string) =>
  within(screen.getByRole('tree')).getByRole('treeitem', { name });

test('a file of several queries has a row for each under it, named by its place', async () => {
  await serve(TWO_QUERIES);
  renderPanel();
  await screen.findByRole('tree');

  expect(rows()).toEqual(['two', 'Query 1', 'Query 2']);
  expect(queryRow('Query 1')).toHaveAccessibleDescription('orders');
  expect(queryRow('Query 2')).toHaveAccessibleDescription('customers, regions');
});

// 015: a query has the query icon wherever it is; a file of several has
// the file icon, which marks it out.
test('a file of one query has the query icon, and a file of several has the file icon', async () => {
  await serve({
    ...TWO_QUERIES,
    'one.sql': 'select 1',
    'with_cte.sql': 'with x as (select 1) select * from x',
  });
  renderPanel();
  await screen.findByRole('tree');
  const icons = (name: string) =>
    within(row(name))
      .queryAllByRole('img')
      .map(icon => icon.getAttribute('aria-label'))
      .filter(label => label === 'console-sql' || label === 'file');

  expect(icons('one')).toEqual(['console-sql']);
  expect(icons('with_cte')).toEqual(['console-sql']);
  expect(icons('two')).toEqual(['file']);
  expect(within(queryRow('Query 1')).getByRole('img', { name: 'console-sql' }))
    .toBeInTheDocument();
});

test("hovering a query's row shows its whole description", async () => {
  await serve(TWO_QUERIES);
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await user.hover(within(queryRow('Query 1')).getByText('Query 1'));
  const tooltip = await screen.findByRole('tooltip', {}, TOOLTIP_WAIT);

  expect(tooltip).toHaveTextContent('Orders by day.');
  expect(tooltip).toHaveTextContent('Returns are left out.');
});

test("a file's query rows stay shown when the file is clicked or the keyboard collapses it", async () => {
  await serve(TWO_QUERIES);
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await user.click(nameOf('two'));
  await user.keyboard('{ArrowLeft}');

  expect(rows()).toEqual(['two', 'Query 1', 'Query 2']);
});

// 012: use one query of a file.

// Query 2 is described from a comment above a blank line, and its first
// CTE's comment sits between that and its first line.
const QUERY_1 = '-- Orders by day.\nselect * from orders;\n';
const QUERY_2 =
  '-- Customers and their regions.\n\n-- One row per customer.\n' +
  'with c as (\n  select * from customers\n)\n' +
  'select * from c join regions r on r.id = c.region_id;\n';
const QUERIES = {
  'orders/two.sql': `/* Licence header. */\n\n${QUERY_1}\n${QUERY_2}`,
};

const queryButton = (query: string, name: string) =>
  within(queryRow(query)).getByRole('button', { name });

async function showQueries(user: ReturnType<typeof setupUser>) {
  renderPanel();
  await screen.findByRole('tree');
  await user.click(nameOf('orders'));
  await waitFor(() => expect(rows()).toContain('Query 2'));
}

test('"Open in new tab" on a query row opens just that query, with its comments, in a tab named after it', async () => {
  await serve(QUERIES);
  const user = setupUser();
  await showQueries(user);

  await user.click(queryButton('Query 2', 'Open in new tab'));
  await user.click(queryButton('Query 1', 'Open in new tab'));

  await waitFor(() =>
    expect(createdTabs).toEqual([
      { sql: QUERY_2, title: 'two › Query 2' },
      { sql: QUERY_1, title: 'two › Query 1' },
    ]),
  );
});

test('double-clicking a query row, or Enter on it, opens it in a new tab', async () => {
  await serve(QUERIES);
  const user = setupUser();
  await showQueries(user);

  await user.dblClick(within(queryRow('Query 1')).getByText('Query 1'));
  await waitFor(() => expect(createdTabs).toHaveLength(1));
  await user.click(within(queryRow('Query 2')).getByText('Query 2'));
  await user.keyboard('{Enter}');

  await waitFor(() =>
    expect(createdTabs).toEqual([
      { sql: QUERY_1, title: 'two › Query 1' },
      { sql: QUERY_2, title: 'two › Query 2' },
    ]),
  );
});

test('"Copy to clipboard" on a query row copies just that query and confirms it', async () => {
  await serve(QUERIES);
  const user = setupUser();
  await showQueries(user);

  await user.click(queryButton('Query 2', 'Copy to clipboard'));

  expect(await screen.findByRole('tooltip')).toHaveTextContent('Copied');
  expect(await navigator.clipboard.readText()).toBe(QUERY_2);
  expect(createdTabs).toEqual([]);
});

test('a file of several queries still opens and copies whole from its own row', async () => {
  await serve(QUERIES);
  const user = setupUser();
  await showQueries(user);

  await user.click(openButton('two'));
  await user.click(
    within(fileRow('two')).getByRole('button', { name: 'Copy to clipboard' }),
  );

  const whole = QUERIES['orders/two.sql'];
  await waitFor(() =>
    expect(createdTabs).toEqual([{ sql: whole, title: 'two' }]),
  );
  expect(await navigator.clipboard.readText()).toBe(whole);
});

test('a query row uses the query at its place in the file as code-source serves it now', async () => {
  await serve(QUERIES);
  const user = setupUser();
  await showQueries(user);
  const changed = `-- Products.\nselect * from products;\n\n${QUERY_1}\n${QUERY_2}`;
  repo.commit({ 'orders/two.sql': changed });
  await codeSource.refresh();

  await user.click(queryButton('Query 2', 'Open in new tab'));

  await waitFor(() =>
    expect(createdTabs).toEqual([{ sql: QUERY_1, title: 'two › Query 2' }]),
  );
});

test('a query no longer in the file is shown as an error, naming the file and query', async () => {
  await serve(QUERIES);
  const user = setupUser();
  await showQueries(user);
  await navigator.clipboard.writeText('before');
  repo.commit({ 'orders/two.sql': QUERY_1 });
  await codeSource.refresh();

  await user.click(queryButton('Query 2', 'Open in new tab'));

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('two › Query 2');
  expect(alert).toHaveTextContent(/no longer in the file/i);
  expect(createdTabs).toEqual([]);

  await user.click(queryButton('Query 2', 'Copy to clipboard'));

  await waitFor(() =>
    expect(screen.getByRole('alert')).toHaveTextContent(/no longer in the file/i),
  );
  expect(await navigator.clipboard.readText()).toBe('before');
});

test("a failure to load a query's file is shown, naming the file and query", async () => {
  await serve(QUERIES);
  const user = setupUser();
  await showQueries(user);
  await codeSource.stop();

  await user.click(queryButton('Query 1', 'Copy to clipboard'));

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('two › Query 1');
  expect(alert).toHaveTextContent(/unreachable/i);
});

// 009: use a CTE.

// A one-query file whose second CTE uses its first, and a file of several
// queries whose second has a CTE (QUERIES, from 012).
const SHARE =
  "-- Each publisher's share of global sales.\n\n" +
  '-- Global sales per publisher.\n' +
  'WITH by_publisher AS (\n' +
  '  SELECT publisher, SUM(sales) AS sales\n' +
  '  FROM games\n' +
  '  GROUP BY publisher\n' +
  '),\n' +
  '-- Publishers ranked by sales, best first.\n' +
  'ranked AS (\n' +
  '  SELECT *, RANK() OVER (ORDER BY sales DESC) AS rnk\n' +
  '  FROM by_publisher\n' +
  ')\n' +
  'SELECT publisher FROM ranked WHERE rnk <= 10;\n';
const BY_PUBLISHER =
  '-- Global sales per publisher.\n' +
  'WITH by_publisher AS (\n' +
  '  SELECT publisher, SUM(sales) AS sales\n' +
  '  FROM games\n' +
  '  GROUP BY publisher\n' +
  ')\n' +
  'SELECT * FROM by_publisher\n';
const RANKED =
  '-- Global sales per publisher.\n' +
  'WITH by_publisher AS (\n' +
  '  SELECT publisher, SUM(sales) AS sales\n' +
  '  FROM games\n' +
  '  GROUP BY publisher\n' +
  '),\n' +
  '-- Publishers ranked by sales, best first.\n' +
  'ranked AS (\n' +
  '  SELECT *, RANK() OVER (ORDER BY sales DESC) AS rnk\n' +
  '  FROM by_publisher\n' +
  ')\n' +
  'SELECT * FROM ranked\n';
// The WITH is the panel's own, so in its own case.
const C = '-- One row per customer.\nWITH c as (\n  select * from customers\n)\nSELECT * FROM c\n';
const CTES = {
  ...QUERIES,
  'orders/share.sql': SHARE,
  'orders/plain.sql': 'select * from orders;\n',
};

const cteRow = (name: string) =>
  within(screen.getByRole('tree')).getByRole('treeitem', { name });

const cteButton = (cte: string, name: string) =>
  within(cteRow(cte)).getByRole('button', { name });

async function showCtes(user: ReturnType<typeof setupUser>) {
  renderPanel();
  await screen.findByRole('tree');
  await user.click(nameOf('orders'));
  await waitFor(() => expect(rows()).toContain('share'));
  await user.click(nameOf('share'));
  await user.click(within(queryRow('Query 2')).getByText('Query 2'));
  await waitFor(() => expect(rows()).toContain('ranked'));
  await waitFor(() => expect(rows()).toContain('c'));
}

test("a query's CTEs are rows under its row, in order, shown and hidden by clicking it", async () => {
  await serve(CTES);
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await user.click(nameOf('orders'));
  await waitFor(() => expect(rows()).toContain('share'));
  const collapsed = ['orders', 'plain', 'share', 'two', 'Query 1', 'Query 2'];
  expect(rows()).toEqual(collapsed);

  await user.click(nameOf('share'));
  await user.click(within(queryRow('Query 2')).getByText('Query 2'));
  await user.click(within(queryRow('Query 1')).getByText('Query 1'));
  await user.click(nameOf('plain'));

  await waitFor(() =>
    expect(rows()).toEqual([
      'orders',
      'plain',
      'share',
      'by_publisher',
      'ranked',
      'two',
      'Query 1',
      'Query 2',
      'c',
    ]),
  );

  await user.click(nameOf('share'));
  await user.click(within(queryRow('Query 2')).getByText('Query 2'));

  await waitFor(() => expect(rows()).toEqual(collapsed));
});

test("a query's CTE rows are shown and hidden with the keyboard", async () => {
  await serve({ 'share.sql': SHARE });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await user.tab(); // the Reload button
  await user.tab(); // the search box
  await user.tab(); // the tree
  press('ArrowDown');
  press('ArrowRight');

  await waitFor(() =>
    expect(rows()).toEqual(['share', 'by_publisher', 'ranked']),
  );

  press('ArrowLeft');

  await waitFor(() => expect(rows()).toEqual(['share']));
});

test('a CTE row shows its description on hover, and the tables its SQL reads', async () => {
  await serve(CTES);
  const user = setupUser();
  await showCtes(user);

  // ranked reads no table itself, but by_publisher, which it uses, does.
  expect(cteRow('ranked')).toHaveAccessibleDescription('games');
  expect(cteRow('c')).toHaveAccessibleDescription('customers');

  await user.hover(within(cteRow('ranked')).getByText('ranked'));
  expect(
    await screen.findByRole('tooltip', {}, TOOLTIP_WAIT),
  ).toHaveTextContent('Publishers ranked by sales, best first.');
});

test("opening a CTE row gives a tab with it, the CTEs it uses, and a SELECT from it, named after it", async () => {
  await serve(CTES);
  const user = setupUser();
  await showCtes(user);

  await user.click(cteButton('ranked', 'Open in new tab'));
  await waitFor(() => expect(createdTabs).toHaveLength(1));
  await user.dblClick(within(cteRow('by_publisher')).getByText('by_publisher'));
  await waitFor(() => expect(createdTabs).toHaveLength(2));
  await user.click(within(cteRow('c')).getByText('c'));
  await user.keyboard('{Enter}');

  await waitFor(() =>
    expect(createdTabs).toEqual([
      { sql: RANKED, title: 'share › ranked' },
      { sql: BY_PUBLISHER, title: 'share › by_publisher' },
      { sql: C, title: 'two › Query 2 › c' },
    ]),
  );
});

test('copying a CTE row copies the same SQL, and confirms it', async () => {
  await serve(CTES);
  const user = setupUser();
  await showCtes(user);

  await user.click(cteButton('ranked', 'Copy to clipboard'));

  expect(await screen.findByRole('tooltip')).toHaveTextContent('Copied');
  expect(await navigator.clipboard.readText()).toBe(RANKED);
  expect(createdTabs).toEqual([]);
});

test("a CTE's SQL is cut where code-source's outline says, whatever the layout", async () => {
  // A recursive WITH, names indented on lines of their own, code after a
  // closing parenthesis, and characters outside the BMP before a CTE.
  const content =
    'WITH RECURSIVE\n' +
    "  emoji AS (SELECT '😀😀' AS e), n AS (\n" +
    '    SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 5\n' +
    '  ),\n' +
    '  doubled AS (SELECT i * 2 AS d FROM n) SELECT * FROM doubled, emoji\n';
  await serve({ 'loops.sql': content });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await user.click(nameOf('loops'));
  await waitFor(() => expect(rows()).toContain('doubled'));

  await user.click(cteButton('doubled', 'Copy to clipboard'));

  await waitFor(async () =>
    expect(await navigator.clipboard.readText()).toBe(
      'WITH RECURSIVE n AS (\n' +
        '    SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 5\n' +
        '  ),\n' +
        '  doubled AS (SELECT i * 2 AS d FROM n)\n' +
        'SELECT * FROM doubled\n',
    ),
  );
});

test('a CTE row uses the CTE of that name in the query as code-source serves it now', async () => {
  await serve(CTES);
  const user = setupUser();
  await showCtes(user);
  repo.commit({
    'orders/share.sql': SHARE.replace(
      '-- Global sales per publisher.\nWITH by_publisher AS (',
      'WITH unused AS (SELECT 1),\n-- Global sales per publisher.\nby_publisher AS (',
    ).replace('FROM games', 'FROM all_games'),
  });
  await codeSource.refresh();

  await user.click(cteButton('by_publisher', 'Open in new tab'));

  await waitFor(() =>
    expect(createdTabs).toEqual([
      {
        sql: BY_PUBLISHER.replace('FROM games', 'FROM all_games'),
        title: 'share › by_publisher',
      },
    ]),
  );
});

test('a CTE no longer in the file is shown as an error, naming the file, query and CTE', async () => {
  await serve(CTES);
  const user = setupUser();
  await showCtes(user);
  await navigator.clipboard.writeText('before');
  repo.commit({
    'orders/two.sql': QUERIES['orders/two.sql'].replace(/\bc\b/g, 'cust'),
  });
  await codeSource.refresh();

  await user.click(cteButton('c', 'Open in new tab'));

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('two › Query 2 › c');
  expect(alert).toHaveTextContent(/no longer in the file/i);
  expect(createdTabs).toEqual([]);

  await user.click(cteButton('c', 'Copy to clipboard'));

  await waitFor(() =>
    expect(screen.getByRole('alert')).toHaveTextContent(/no longer in the file/i),
  );
  expect(await navigator.clipboard.readText()).toBe('before');
});

test("a failure to load a CTE's file is shown, naming the file and CTE", async () => {
  await serve(CTES);
  const user = setupUser();
  await showCtes(user);
  await codeSource.stop();

  await user.click(cteButton('ranked', 'Copy to clipboard'));

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('share › ranked');
  expect(alert).toHaveTextContent(/unreachable/i);
});

// 013: search by tables, functions and keywords.

test('each result shows its tables and when it last changed, with the tree\'s tooltip', async () => {
  await serveHistory([
    {
      files: {
        'sales/top.sql':
          '-- Best sellers.\nselect * from orders o join customers c on c.id = o.customer_id\n',
      },
      date: MARCH_2024,
    },
  ]);
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'top');

  await waitFor(() => expect(results()).toEqual(['sales/top']));
  expect(result('sales/top')).toHaveAccessibleDescription(
    'customers, orders Mar 2024',
  );
  expect(within(result('sales/top')).getByText('Mar 2024')).toBeVisible();
  await user.hover(within(result('sales/top')).getByText('top'));
  const tooltip = await screen.findByRole('tooltip', {}, TOOLTIP_WAIT);
  expect(tooltip).toHaveTextContent('Best sellers.');
  expect(tooltip).toHaveTextContent(/Changed .*March.*2024/);
  await expectTablesPill(user, result('sales/top'), ['customers', 'orders']);
});

const suggestionGroup = () =>
  screen.queryByRole('group', { name: 'Suggestions' });

/** The suggestions by name: kind, value and how many examples, in order. */
function suggestions(): string[] {
  const group = suggestionGroup();
  return group
    ? within(group)
        .queryAllByRole('option')
        .map(option => computeAccessibleName(option))
    : [];
}

const suggestion = (name: string) =>
  within(suggestionGroup()!).getByRole('option', { name });

/** The chosen filters, by their remove buttons. */
function chips(): string[] {
  const group = screen.queryByRole('group', { name: 'Search filters' });
  return group
    ? within(group)
        .getAllByRole('button')
        .map(button => computeAccessibleName(button))
    : [];
}

test('typing part of a table suggests it, by its name as written or a shorter one', async () => {
  await serve({
    'a.sql': 'select * from sales.orders',
    'b.sql': 'select * from orders',
    'c.sql': 'select * from sales.customers',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'ORD');
  await waitFor(() => expect(suggestions()).toEqual(['Table orders, 2 examples']));

  await searchFor(user, 'sales.o');
  await waitFor(() =>
    expect(suggestions()).toEqual(['Table sales.orders, 2 examples']),
  );
  expect(within(suggestion('Table sales.orders, 2 examples')).getByText('2')).toBeVisible();
});

test('functions and keywords are suggested as separate kinds, ignoring case, most examples first', async () => {
  await serve({
    'x.sql': 'select round(a), row_number() over (order by a) from t',
    'y.sql': 'select round(a) from t group by a',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'ro');
  await waitFor(() =>
    expect(suggestions()).toEqual([
      'Function ROUND, 2 examples',
      'Function ROW_NUMBER, 1 example',
    ]),
  );

  await searchFor(user, 'Group');
  await waitFor(() =>
    expect(suggestions()).toEqual(['Keyword GROUP BY, 1 example']),
  );
});

test('choosing a suggestion turns the word into a chip and shows only the examples that match it', async () => {
  await serve({
    'sales/top.sql': 'select * from orders',
    'sales/returns.sql': 'select * from returns',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await searchFor(user, 'sales ord');
  await waitFor(() => expect(suggestions()).toEqual(['Table orders, 1 example']));

  await user.click(suggestion('Table orders, 1 example'));

  expect(chips()).toEqual(['Remove table orders']);
  expect(screen.getByText('Table: orders')).toBeVisible();
  expect(searchBox()).toHaveValue('sales ');
  expect(searchBox()).toHaveFocus();
  await waitFor(() => expect(results()).toEqual(['sales/top']));
  expect(suggestions()).toEqual([]);
});

test('a table chip finds examples that wrote it in full or in part, those in full first', async () => {
  await serve({
    'a_bare.sql': 'select * from orders',
    'b_full.sql': 'select * from sales.orders',
    'c_other.sql': 'select * from hr.orders',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await searchFor(user, 'sales.orders');

  await user.click(await waitFor(() => suggestion('Table sales.orders, 2 examples')));

  await waitFor(() => expect(results()).toEqual(['b_full', 'a_bare']));
});

test('chips combine with each other and with words in the box', async () => {
  await serve({
    'east/both.sql': 'select * from orders join customers using (id)',
    'east/orders_only.sql': 'select * from orders',
    'west/both.sql': 'select * from orders join customers using (id)',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'orders');
  await user.click(await waitFor(() => suggestion('Table orders, 3 examples')));
  await user.type(searchBox(), 'cust');
  await user.click(await waitFor(() => suggestion('Table customers, 2 examples')));
  await waitFor(() =>
    expect(results()).toEqual(['east/both', 'west/both']),
  );

  await user.type(searchBox(), 'west');

  await waitFor(() => expect(results()).toEqual(['west/both']));
  expect(chips()).toEqual(['Remove table orders', 'Remove table customers']);
});

test('chips are removed one at a time by their button or by Backspace, and Escape clears everything', async () => {
  await serve({
    'both.sql': 'select round(a) from orders',
    'orders_only.sql': 'select * from orders',
    'other.sql': 'select 1',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await searchFor(user, 'ord');
  await user.click(await waitFor(() => suggestion('Table orders, 2 examples')));
  await user.type(searchBox(), 'round');
  await user.click(await waitFor(() => suggestion('Function ROUND, 1 example')));
  await waitFor(() => expect(results()).toEqual(['both']));

  await user.click(screen.getByRole('button', { name: 'Remove table orders' }));
  expect(chips()).toEqual(['Remove function ROUND']);
  expect(searchBox()).toHaveFocus();

  await user.type(searchBox(), 'ord');
  await user.click(await waitFor(() => suggestion('Table orders, 2 examples')));
  expect(chips()).toEqual(['Remove function ROUND', 'Remove table orders']);
  await user.keyboard('{Backspace}');
  expect(chips()).toEqual(['Remove function ROUND']);
  await waitFor(() => expect(results()).toEqual(['both']));

  await user.type(searchBox(), 'both');
  fireEvent.keyDown(searchBox(), { key: 'Escape', code: 'Escape' });

  expect(chips()).toEqual([]);
  expect(searchBox()).toHaveValue('');
  expect(screen.getByRole('tree')).toBeVisible();
});

test('choosing the same suggestion twice adds one chip', async () => {
  await serve({ 'a.sql': 'select * from orders' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  for (let i = 0; i < 2; i += 1) {
    await user.type(searchBox(), 'ord');
    await user.click(await waitFor(() => suggestion('Table orders, 1 example')));
  }

  expect(chips()).toEqual(['Remove table orders']);
});

test('suggestions and results can be used with the keyboard alone', async () => {
  await serve({
    'orders_by_day.sql': 'select * from orders',
    'returns.sql': 'select * from returns',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await searchFor(user, 'orders');
  await waitFor(() => {
    expect(suggestions()).toEqual(['Table orders, 1 example']);
    expect(results()).toEqual(['orders_by_day']);
  });

  press('ArrowDown'); // into the list: the suggestion
  expect(suggestion('Table orders, 1 example')).toHaveAttribute('aria-selected', 'true');
  press('ArrowUp'); // back to the box
  expect(searchBox()).toHaveFocus();
  press('ArrowDown');
  press('ArrowDown'); // on into the results
  expect(result('orders_by_day')).toHaveAttribute('aria-selected', 'true');
  press('ArrowUp'); // the suggestion
  press('Enter');

  expect(chips()).toEqual(['Remove table orders']);
  expect(searchBox()).toHaveFocus();
  expect(searchBox()).toHaveValue('');
  // The previous results and suggestions stay until the new ones arrive.
  await waitFor(() => expect(suggestions()).toEqual([]));
  expect(results()).toEqual(['orders_by_day']);
  press('ArrowDown'); // no suggestions now: the result
  press('Enter');
  await waitFor(() =>
    expect(createdTabs).toEqual([
      { sql: 'select * from orders', title: 'orders_by_day' },
    ]),
  );
});

// 014: search descriptions and CTE names.

const resultRow = (name: string) =>
  within(resultGroup()!).getByRole('option', { name });

const marks = (element: Element) =>
  Array.from(element.querySelectorAll('mark')).map(m => m.textContent);

test('a CTE that matched is a row under its result, its name highlighted, and CTEs that did not match are not', async () => {
  await serve({ 'orders/share.sql': SHARE, 'other.sql': 'select 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'rank');

  await waitFor(() => expect(resultRows()).toEqual(['orders/share', 'ranked']));
  // Its name, then its description's snippet ("Publishers ranked by ...").
  expect(marks(resultRow('ranked'))).toEqual(['rank', 'rank']);
  // ranked reads no table itself, but by_publisher, which it uses, does.
  expect(resultRow('ranked')).toHaveAccessibleDescription('games');

  // by_publisher by its name, ranked by its description ("Publishers").
  await searchFor(user, 'publisher');

  await waitFor(() =>
    expect(resultRows()).toEqual(['orders/share', 'by_publisher', 'ranked']),
  );
  // Each name, where it matched, then each description's snippet.
  expect(marks(resultRow('by_publisher'))).toEqual(['publisher', 'publisher']);
  expect(marks(resultRow('ranked'))).toEqual(['Publisher']);
});

test("in a file of several queries, a query's row shows when it or one of its CTEs matched, with those CTEs under it", async () => {
  await serve(QUERIES);
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  // Query 2's description ("Customers ...") and its CTE c's ("... customer.").
  await searchFor(user, 'customer');
  await waitFor(() =>
    expect(resultRows()).toEqual(['orders/two', 'Query 2', 'c']),
  );
  expect(resultRow('Query 2')).toHaveAccessibleDescription('customers, regions');

  // Query 1's description ("Orders by day.").
  await searchFor(user, 'day');
  await waitFor(() => expect(resultRows()).toEqual(['orders/two', 'Query 1']));
});

test("a one-query file whose query's description matched shows no row for its query: the file's row is it", async () => {
  await serve({ 'a.sql': '-- Revenue per day.\nselect 1' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'revenue');

  await waitFor(() => expect(resultRows()).toEqual(['a']));
});

test('query and CTE rows in the results can be opened and copied, as in the tree', async () => {
  await serve(CTES);
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'ranked');
  await waitFor(() => expect(resultRows()).toEqual(['orders/share', 'ranked']));
  await user.click(
    within(resultRow('ranked')).getByRole('button', { name: 'Open in new tab' }),
  );
  await waitFor(() =>
    expect(createdTabs).toEqual([{ sql: RANKED, title: 'share › ranked' }]),
  );

  await searchFor(user, 'customer');
  await waitFor(() =>
    expect(resultRows()).toEqual(['orders/two', 'Query 2', 'c']),
  );
  await user.dblClick(within(resultRow('Query 2')).getByText('Query 2'));
  await waitFor(() => expect(createdTabs).toHaveLength(2));
  expect(createdTabs[1]).toEqual({ sql: QUERY_2, title: 'two › Query 2' });

  await user.click(
    within(resultRow('c')).getByRole('button', { name: 'Copy to clipboard' }),
  );
  expect(await screen.findByRole('tooltip')).toHaveTextContent('Copied');
  expect(await navigator.clipboard.readText()).toBe(C);
});

test('the keyboard moves through results and the rows under them, and Enter opens the active one', async () => {
  await serve({ 'share.sql': SHARE });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');
  await searchFor(user, 'ranked');
  await waitFor(() => expect(resultRows()).toEqual(['share', 'ranked']));

  press('ArrowDown'); // into the results: share
  press('ArrowDown'); // ranked
  expect(resultRow('ranked')).toHaveAttribute('aria-selected', 'true');
  press('Enter');
  await waitFor(() =>
    expect(createdTabs).toEqual([{ sql: RANKED, title: 'share › ranked' }]),
  );

  press('ArrowUp'); // share
  press('ArrowUp'); // back to the box
  expect(searchBox()).toHaveFocus();
});

test('a search by chips alone shows only the files', async () => {
  await serve({ 'share.sql': SHARE });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await user.type(searchBox(), 'gam');
  await user.click(await waitFor(() => suggestion('Table games, 1 example')));

  await waitFor(() => expect(resultRows()).toEqual(['share']));
});

test('a description that matched shows as a snippet under the row it describes, its words highlighted', async () => {
  await serve({ 'orders/share.sql': SHARE });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  // The query's description ("... share of global sales."), which is the
  // file's row, and by_publisher's ("Global sales per publisher.").
  await searchFor(user, 'global');

  await waitFor(() =>
    expect(resultRows()).toEqual(['orders/share', 'by_publisher']),
  );
  expect(resultRow('orders/share')).toHaveTextContent(
    "Each publisher's share of global sales.",
  );
  expect(marks(resultRow('orders/share'))).toEqual(['global']);
  expect(resultRow('by_publisher')).toHaveTextContent('Global sales per publisher.');
  expect(marks(resultRow('by_publisher'))).toEqual(['Global']);
});

test('a part whose name matched, but not its description, shows no snippet', async () => {
  await serve({
    'a.sql': '-- Daily totals.\n\n-- One row each.\nWITH monthly AS (select 1 as x) select * from monthly',
  });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'monthly');

  await waitFor(() => expect(resultRows()).toEqual(['a', 'monthly']));
  expect(resultRow('a')).not.toHaveTextContent('Daily totals.');
  expect(resultRow('monthly')).not.toHaveTextContent('One row each.');
});

test('a snippet of a long description starts near its first match', async () => {
  const description =
    'Revenue per country from both sales datasets, stacked with UNION ALL ' +
    'and then totalled. The source column shows where each figure came from.';
  await serve({ 'combined.sql': `-- ${description}\nselect 1` });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'source');

  await waitFor(() => expect(resultRows()).toEqual(['combined']));
  expect(resultRow('combined')).toHaveTextContent(
    '…then totalled. The source column shows where each figure came from.',
  );
  expect(resultRow('combined')).not.toHaveTextContent('Revenue per country');
});

test("a result's folder heads its tooltip rather than showing on its row, and a top-level result has none", async () => {
  await serve({ 'sales/top.sql': '-- Best sellers.\nselect 1', 'top.sql': 'select 2' });
  const user = setupUser();
  renderPanel();
  await screen.findByRole('tree');

  await searchFor(user, 'top');

  await waitFor(() => expect(results()).toEqual(['sales/top', 'top']));
  expect(result('sales/top')).not.toHaveTextContent('sales');

  await user.hover(within(result('sales/top')).getByText('top'));
  const tooltip = await screen.findByRole('tooltip', {}, TOOLTIP_WAIT);
  expect(tooltip.textContent).toMatch(/^salesBest sellers\.Changed /);

  await user.unhover(within(result('sales/top')).getByText('top'));
  await waitFor(() => expect(openTooltips()).toEqual([]), TOOLTIP_WAIT);
  await user.hover(within(result('top')).getByText('top'));
  await waitFor(
    () => expect(openTooltips()).toEqual([expect.stringMatching(/^Changed /)]),
    TOOLTIP_WAIT,
  );
});
