import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  Button,
  ConfigProvider,
  Empty,
  Input,
  Spin,
  Tree,
  theme as antdTheme,
} from 'antd';
import type { InputRef } from 'antd';
import type { DataNode } from 'antd/es/tree';
import {
  BlockOutlined,
  ConsoleSqlOutlined,
  FileOutlined,
  ReloadOutlined,
  SearchOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { components, theme } from '@apache-superset/core';
import { Entry, Filter, fetchTree, queriesOf } from './api';
import {
  FileActionButtons,
  cteKey,
  ctesOf,
  queryKey,
  queryName,
  useFileActions,
} from './fileActions';
import {
  CteLabel,
  QueryLabel,
  RowLabel,
  describedBy,
  entryName,
  queryDescribedBy,
} from './rowLabel';
import { SearchResults } from './SearchResults';
import { FilterChips, sameFilter, withoutWordBeingTyped } from './filters';
import { SEARCH_BAR_HEIGHT, SearchBar, panelGutter } from './searchBar';

type FolderState =
  | { status: 'loading' }
  | { status: 'loaded'; entries: Entry[] }
  | { status: 'error'; message: string };

const ROOT = '';

/** What an analyst can use: folders and SQL files, no dot-entries. */
function visible(entries: Entry[]): Entry[] {
  return entries
    .filter(e => !e.name.startsWith('.'))
    .filter(e => e.type === 'directory' || /\.sql$/i.test(e.name))
    .sort((a, b) =>
      a.type !== b.type
        ? a.type === 'directory'
          ? -1
          : 1
        : entryName(a).localeCompare(entryName(b), undefined, { numeric: true }),
    );
}

// Look like SQL Lab's schema browser above the panel (TableExploreTree in
// Superset): 28px rows, 16px per level, no expand arrows (the folder icon
// shows open or closed), large icons. Tree tokens cover most of it; the
// rest has no token.
const SCHEMA_TREE_ROW_HEIGHT = 28;
const SCHEMA_TREE_INDENT = 16;

/** Whether `event` is the shortcut that focuses the search box (006). Ctrl
 * on every platform, like SQL Lab's own shortcuts. */
// How often relative dates ("5 minutes ago") are brought up to date.
const CLOCK_TICK_MS = 60 * 1000;

const isSearchShortcut = (event: KeyboardEvent) =>
  event.ctrlKey &&
  event.shiftKey &&
  !event.altKey &&
  !event.metaKey &&
  (event.code === 'KeyS' || event.key.toLowerCase() === 's');

// A file's actions show while its row is hovered, like the schema
// browser's, and also on the keyboard's active row. They take the place of
// its date (008).
const TreeContainer = theme.styled.div`
  .ant-tree-switcher {
    display: none;
  }
  .ant-tree-iconEle {
    font-size: ${({ theme }) => theme.fontSizeLG}px;
    margin-right: ${({ theme }) => theme.sizeUnit}px;
  }
  .ant-tree-node-content-wrapper {
    display: flex;
    min-width: 0;
  }
  /* A row's icon starts at the panel's gutter, as the schema browser's
     do (015). antd pads the row and centres the icon in a square. These
     have to outrank antd's own selectors. */
  .ant-tree .ant-tree-treenode .ant-tree-node-content-wrapper {
    padding-left: 0;
  }
  .ant-tree .ant-tree-node-content-wrapper .ant-tree-iconEle {
    width: auto;
  }
  .ant-tree-title {
    flex: 1;
    display: flex;
    min-width: 0;
  }
  /* Scrolled into view below the sticky search bar (013), not under it. */
  .ant-tree-treenode {
    scroll-margin-top: var(${SEARCH_BAR_HEIGHT}, 0px);
  }
  .ant-tree-treenode:hover .file-actions,
  .ant-tree-treenode-active .file-actions {
    display: flex;
  }
  /* The row says when its actions are busy, rather than this asking with
     :has(), which Chrome before 105 lacks and would drop the rule for. */
  .ant-tree-treenode:hover .entry-date,
  .ant-tree-treenode-active .entry-date,
  .ant-tree-treenode.row-busy .entry-date {
    visibility: hidden;
  }
`;

const PanelContainer = theme.styled.div`
  padding: 0 ${panelGutter}px;
`;

// It was cramped against the top of its area, under the splitter's
// handle (015).
const Heading = theme.styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: ${panelGutter}px;
  font-weight: ${({ theme }) => theme.fontWeightStrong};
`;

export const Panel = () => {
  const { token } = antdTheme.useToken();
  const treeTheme = useMemo(
    () => ({
      components: {
        Tree: {
          titleHeight: SCHEMA_TREE_ROW_HEIGHT,
          indentSize: SCHEMA_TREE_INDENT,
          controlItemBgHover: token.colorBgTextHover,
        },
      },
    }),
    [token],
  );
  // Contents of each folder that has been opened, by path ('' = top level).
  const [folders, setFolders] = useState<Record<string, FolderState>>({});
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);
  // The row arrow keys move from (rc-tree's "active" row).
  const [activeKey, setActiveKey] = useState<React.Key | null>(null);
  // The active row when the keyboard made it so, whose tooltip shows (008).
  const [keyboardKey, setKeyboardKey] = useState<React.Key | null>(null);
  const usingKeyboard = useRef(false);
  const treeContainer = useRef<HTMLDivElement>(null);
  const actions = useFileActions();
  // What's in the search box, and the filters chosen from its suggestions
  // (013). Either shows results instead of the tree.
  const [searchText, setSearchText] = useState('');
  const [filters, setFilters] = useState<Filter[]>([]);
  const searching = searchText.trim() !== '' || filters.length > 0;
  // Bumped on Reload, which runs the search again.
  const [reloads, setReloads] = useState(0);
  const searchBox = useRef<InputRef>(null);
  const searchBar = useRef<HTMLDivElement>(null);
  const [searchBarHeight, setSearchBarHeight] = useState(0);
  const resultList = useRef<HTMLDivElement>(null);
  // Bumped on Reload, so answers to requests made before it are dropped.
  const generation = useRef(0);
  const mounted = useRef(true);
  // What relative dates are relative to.
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), CLOCK_TICK_MS);
    return () => clearInterval(timer);
  }, []);

  const load = useCallback(async (path: string) => {
    const requestGeneration = generation.current;
    setFolders(f => ({ ...f, [path]: { status: 'loading' } }));
    const result = await fetchTree(path);
    if (!mounted.current || requestGeneration !== generation.current) return;
    setFolders(f => ({
      ...f,
      [path]: result.ok
        ? { status: 'loaded', entries: visible(result.entries) }
        : { status: 'error', message: result.message },
    }));
  }, []);

  useEffect(() => {
    load(ROOT);
    return () => {
      mounted.current = false;
    };
  }, [load]);

  // rc-tree moves the active row but doesn't scroll the panel to it (003).
  useEffect(() => {
    if (activeKey === null) return;
    treeContainer.current
      ?.querySelector('.ant-tree-treenode-active')
      ?.scrollIntoView?.({ block: 'nearest' });
  }, [activeKey]);

  // Chips wrap onto more lines as they're added, so it's measured as it
  // changes.
  useEffect(() => {
    const element = searchBar.current;
    if (!element) return undefined;
    const measure = () => setSearchBarHeight(element.offsetHeight);
    measure();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  // In the capture phase, so the editor (or anything else focused) can't
  // swallow it first.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!isSearchShortcut(event)) return;
      event.preventDefault();
      event.stopPropagation();
      searchBox.current?.focus();
      searchBox.current?.select();
    };
    document.addEventListener('keydown', onKeyDown, true);
    return () => document.removeEventListener('keydown', onKeyDown, true);
  }, []);

  const clearSearch = () => {
    setSearchText('');
    setFilters([]);
    searchBox.current?.focus();
  };

  // The suggestion takes the place of the word it was suggested for.
  const chooseFilter = (filter: Filter) => {
    setFilters(f => (f.some(g => sameFilter(g, filter)) ? f : [...f, filter]));
    setSearchText(withoutWordBeingTyped);
    searchBox.current?.focus();
  };

  const removeFilter = (filter: Filter) => {
    setFilters(f => f.filter(g => !sameFilter(g, filter)));
    searchBox.current?.focus();
  };

  const onSearchKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      clearSearch();
    } else if (
      event.key === 'Backspace' &&
      searchText === '' &&
      filters.length > 0
    ) {
      event.preventDefault();
      removeFilter(filters[filters.length - 1]);
    } else if (event.key === 'ArrowDown' && resultList.current) {
      event.preventDefault();
      resultList.current.focus();
    } else if (event.key === 'ArrowDown' && !searching) {
      // With the box empty, it and the tree make one loop (015).
      event.preventDefault();
      enterTree(rowKeys[0]);
    } else if (event.key === 'ArrowUp' && !searching) {
      event.preventDefault();
      enterTree(rowKeys[rowKeys.length - 1]);
    }
  };

  // Moves the keyboard into the tree, at `key`, with its tooltip showing.
  const enterTree = (key: React.Key | undefined) => {
    if (key === undefined) return;
    usingKeyboard.current = true;
    setActiveKey(key);
    setKeyboardKey(key);
    treeContainer.current
      ?.querySelector('input')
      ?.focus({ preventScroll: true });
  };

  // Up from the tree's first row, or Down from its last, goes back to the
  // search box (015) rather than wrapping round as rc-tree does. Caught
  // before rc-tree sees the key.
  const onTreeKeyDownCapture = (event: React.KeyboardEvent) => {
    usingKeyboard.current = true;
    const last = rowKeys[rowKeys.length - 1];
    const leaving =
      (event.key === 'ArrowUp' && activeKey === rowKeys[0]) ||
      (event.key === 'ArrowDown' && activeKey === last);
    if (activeKey === null || !leaving) return;
    event.preventDefault();
    event.stopPropagation();
    searchBox.current?.focus();
  };

  const reload = () => {
    generation.current += 1;
    setFolders({});
    setExpandedKeys([]);
    setActiveKey(null);
    setKeyboardKey(null);
    setReloads(r => r + 1);
    load(ROOT);
  };

  // Files currently shown in the tree, by path.
  const files = useMemo(() => {
    const byPath = new Map<string, Entry>();
    Object.values(folders).forEach(folder => {
      if (folder.status !== 'loaded') return;
      folder.entries
        .filter(e => e.type === 'file')
        .forEach(e => byPath.set(e.path, e));
    });
    return byPath;
  }, [folders]);

  // Rows for parts of files, by key: query rows (012) and CTE rows (009),
  // with their file, the query's place in it (none for a one-query file's
  // CTEs), and the CTE's name.
  const partRows = useMemo(() => {
    type Part = { file: Entry; query?: number; cte?: string };
    const byKey = new Map<string, Part>();
    const addCtes = (file: Entry, query?: number) =>
      ctesOf(file, query).forEach(({ name }) =>
        byKey.set(cteKey(file.path, query, name), { file, query, cte: name }),
      );
    files.forEach(file => {
      const queries = queriesOf(file);
      if (queries.length < 2) {
        addCtes(file);
        return;
      }
      queries.forEach((_, index) => {
        byKey.set(queryKey(file.path, index), { file, query: index });
        addCtes(file, index);
      });
    });
    return byKey;
  }, [files]);

  // Folders currently shown in the tree, by path.
  const directories = useMemo(() => {
    const paths = new Set<string>();
    Object.values(folders).forEach(folder => {
      if (folder.status !== 'loaded') return;
      folder.entries
        .filter(e => e.type === 'directory')
        .forEach(e => paths.add(e.path));
    });
    return paths;
  }, [folders]);

  // Opens the file, query or CTE at a row, if it's one.
  const openRow = (key: React.Key) => {
    const file = files.get(String(key));
    if (file) actions.open(file);
    const part = partRows.get(String(key));
    if (part) actions.open(part.file, part.query, part.cte);
  };

  // Enter on the active row opens a file, query or CTE. (rc-tree only
  // selects it.)
  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key !== 'Enter' || activeKey === null) return;
    openRow(activeKey);
  };

  const onDoubleClick = (_: React.MouseEvent, node: DataNode) =>
    openRow(node.key);

  // rc-tree takes keyboard input through a hidden input, which a click on a
  // row doesn't focus. Focus it and start from the clicked row, so the arrow
  // keys work straight after a click, as they do after tabbing in.
  // The input sits at the top of the tree, so focusing it mustn't scroll
  // there (bug 002).
  const onRowClick = (_: React.MouseEvent, node: DataNode) => {
    setActiveKey(node.key);
    setKeyboardKey(null);
    treeContainer.current
      ?.querySelector('input')
      ?.focus({ preventScroll: true });
  };

  // rc-tree moves the active row for the arrow keys, and only then.
  const onActiveChange = (key: React.Key | null) => {
    setActiveKey(key);
    setKeyboardKey(usingKeyboard.current ? key : null);
  };

  // Using the mouse closes the keyboard's tooltip, so it and a hovered
  // row's never show at once.
  const onPointer = () => {
    usingKeyboard.current = false;
    setKeyboardKey(null);
  };

  const onExpand = (
    keys: React.Key[],
    info: { expanded: boolean; node: DataNode },
  ) => {
    setExpandedKeys(keys);
    // Files and queries expand too, to show their CTEs (009).
    const path = String(info.node.key);
    if (info.expanded && !folders[path] && directories.has(path)) load(path);
  };

  // Files of several queries show their query rows always (008): they're
  // kept expanded whatever the tree asks.
  const shownKeys = useMemo(() => {
    const keys = new Set(expandedKeys);
    files.forEach((entry, path) => {
      if (queriesOf(entry).length > 1) keys.add(path);
    });
    return Array.from(keys);
  }, [expandedKeys, files]);

  // A row inside a folder that isn't an entry: loading, failed, or empty.
  const statusRow = (
    path: string,
    icon: React.ReactNode,
    title: React.ReactNode,
  ): DataNode => ({
    key: `${path}\u0000status`,
    title,
    icon,
    isLeaf: true,
    selectable: false,
  });

  const childrenOf = (path: string): DataNode[] | undefined => {
    const folder = folders[path];
    if (!folder) return undefined;
    if (folder.status === 'loading') {
      return [statusRow(path, <Spin size="small" />, 'Loading…')];
    }
    if (folder.status === 'error') {
      return [
        statusRow(
          path,
          <WarningOutlined />,
          <span>
            {folder.message}{' '}
            <Button
              type="link"
              size="small"
              onClick={e => {
                e.stopPropagation();
                load(path);
              }}
            >
              Retry
            </Button>
          </span>,
        ),
      ];
    }
    if (folder.entries.length === 0) {
      return [statusRow(path, null, 'No SQL examples here')];
    }
    return folder.entries.map(toNode);
  };

  // A row is named by its entry's name alone, and described by its tables
  // and date, so the decoration doesn't become part of its name. (rc-tree passes aria-*
  // props to the row.)
  const toNode = (entry: Entry): DataNode => {
    const node: DataNode =
      entry.type === 'directory'
        ? {
            key: entry.path,
            title: (
              <RowLabel
                entry={entry}
                now={now}
                keyboardActive={keyboardKey === entry.path}
              />
            ),
            isLeaf: false,
            children: childrenOf(entry.path),
          }
        : fileNode(entry);
    return Object.assign(node, {
      'aria-label': entryName(entry),
      'aria-describedby': describedBy(entry),
    });
  };

  // A query's CTEs have rows under its row, collapsed at first (009).
  const cteNodes = (entry: Entry, query?: number): DataNode[] =>
    ctesOf(entry, query).map(cte => {
      const key = cteKey(entry.path, query, cte.name);
      return Object.assign(
        {
          key,
          title: (
            <CteLabel
              cte={cte}
              rowKey={key}
              keyboardActive={keyboardKey === key}
              actions={
                <FileActionButtons
                  entry={entry}
                  query={query}
                  cte={cte.name}
                  actions={actions}
                />
              }
            />
          ),
          icon: <BlockOutlined />,
          isLeaf: true,
        },
        {
          'aria-label': cte.name,
          'aria-describedby': queryDescribedBy(cte, key),
        },
      );
    });

  // Whether a row with CTEs under it shows them; undefined for a row
  // without.
  const expandedState = (key: string, ctes: DataNode[]) =>
    ctes.length > 0 ? shownKeys.includes(key) : undefined;

  // A file of several queries has a row for each under it, with the same
  // actions as a file (012). A one-query file's CTEs are under the file's
  // row, and a query's under its query row (009). A query has the query
  // icon wherever it is, so a one-query file does too, and a file of
  // several keeps the file icon to mark it out (015).
  const fileNode = (entry: Entry): DataNode => {
    const queries = queriesOf(entry);
    if (queries.length < 2) {
      const ctes = cteNodes(entry);
      return {
        key: entry.path,
        title: fileTitle(entry, expandedState(entry.path, ctes)),
        icon: <ConsoleSqlOutlined />,
        ...(ctes.length > 0
          ? { isLeaf: false, children: ctes }
          : { isLeaf: true }),
      };
    }
    return {
      key: entry.path,
      title: fileTitle(entry),
      // Not a folder, though it has rows under it.
      icon: <FileOutlined />,
      isLeaf: false,
      children: queries.map((query, index) => {
        const key = queryKey(entry.path, index);
        const ctes = cteNodes(entry, index);
        return Object.assign(
          {
            key,
            title: (
              <QueryLabel
                query={query}
                index={index}
                rowKey={key}
                actions={
                  <FileActionButtons
                    entry={entry}
                    query={index}
                    actions={actions}
                  />
                }
                expanded={expandedState(key, ctes)}
                keyboardActive={keyboardKey === key}
              />
            ),
            icon: <ConsoleSqlOutlined />,
            ...(ctes.length > 0
              ? { isLeaf: false, children: ctes }
              : { isLeaf: true }),
          },
          {
            'aria-label': queryName(index),
            'aria-describedby': queryDescribedBy(query, key),
          },
        );
      }),
    };
  };

  const fileTitle = (entry: Entry, expanded?: boolean) => (
    <RowLabel
      entry={entry}
      now={now}
      actions={<FileActionButtons entry={entry} actions={actions} />}
      expanded={expanded}
      keyboardActive={keyboardKey === entry.path}
    />
  );

  const root = folders[ROOT];
  const treeData =
    root?.status === 'loaded' ? root.entries.map(toNode) : [];
  // Rows whose actions are running hide their date (bug 003). File actions
  // key what's busy as the tree keys its rows.
  const markBusy = (nodes: DataNode[]): void =>
    nodes.forEach(node => {
      if (actions.busy[node.key as string]) node.className = 'row-busy';
      if (node.children) markBusy(node.children);
    });
  markBusy(treeData);
  // The tree's rows top to bottom, as the arrow keys move through them.
  const rowKeys: React.Key[] = [];
  const addRows = (nodes: DataNode[]) =>
    nodes.forEach(node => {
      rowKeys.push(node.key);
      if (node.children && shownKeys.includes(node.key)) addRows(node.children);
    });
  addRows(treeData);
  let body: React.ReactNode;
  if (!root || root.status === 'loading') {
    body = (
      <div>
        <Spin size="small" /> Loading examples…
      </div>
    );
  } else if (root.status === 'error') {
    body = (
      <components.Alert
        type="error"
        closable={false}
        action={
          <Button size="small" onClick={() => load(ROOT)}>
            Retry
          </Button>
        }
      >
        {root.message}
      </components.Alert>
    );
  } else if (root.entries.length === 0) {
    body = (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description="No SQL examples found"
      />
    );
  } else {
    body = (
      <ConfigProvider theme={treeTheme}>
        <TreeContainer
          ref={treeContainer}
          onKeyDownCapture={onTreeKeyDownCapture}
          onMouseDown={onPointer}
          onMouseMove={onPointer}
        >
          {/* Nothing uses selection, and its highlight stayed on the clicked
              row while the keyboard moved on (008), so rows aren't
              selectable. The keyboard's active row is highlighted instead. */}
          <Tree.DirectoryTree
            selectable={false}
            treeData={treeData}
            expandedKeys={shownKeys}
            onExpand={onExpand}
            activeKey={activeKey}
            onActiveChange={onActiveChange}
            onBlur={() => setKeyboardKey(null)}
            onClick={onRowClick}
            onDoubleClick={onDoubleClick}
            onKeyDown={onKeyDown}
          />
        </TreeContainer>
      </ConfigProvider>
    );
  }

  return (
    <PanelContainer
      style={
        { [SEARCH_BAR_HEIGHT]: `${searchBarHeight}px` } as React.CSSProperties
      }
    >
      <Heading>
        <span>SQL Examples</span>
        <Button
          type="text"
          size="small"
          icon={<ReloadOutlined />}
          aria-label="Reload"
          title="Reload"
          onClick={reload}
        />
      </Heading>
      <SearchBar ref={searchBar}>
        <Input
          ref={searchBox}
          aria-label="Search examples"
          placeholder="Search examples (Ctrl+Shift+S)"
          prefix={<SearchOutlined />}
          allowClear
          value={searchText}
          onChange={e => setSearchText(e.target.value)}
          onKeyDown={onSearchKeyDown}
          style={{ margin: `${token.marginXS}px 0` }}
        />
        <FilterChips filters={filters} onRemove={removeFilter} />
      </SearchBar>
      {actions.error && (
        <components.Alert type="error" closable onClose={actions.closeError}>
          Couldn't use {actions.error.name}: {actions.error.message}
        </components.Alert>
      )}
      {searching && (
        <SearchResults
          text={searchText}
          filters={filters}
          reloads={reloads}
          now={now}
          actions={actions}
          listRef={resultList}
          onChoose={chooseFilter}
          onUpFromFirst={() => searchBox.current?.focus()}
          onEscape={clearSearch}
        />
      )}
      {/* Kept while searching, so its expanded folders survive. */}
      <div hidden={searching}>{body}</div>
    </PanelContainer>
  );
};
