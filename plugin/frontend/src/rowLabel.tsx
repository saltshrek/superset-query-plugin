// What a tree row shows (008): a name, tables, a file's date sharing the
// row's right end with its actions, and a tooltip saying what the row is for
// and when a file changed. Rows are folders, files, the queries of files
// that have several, and queries' CTEs (009). Search results (013) show
// files the same way.
import React, { useEffect, useRef, useState } from 'react';
import { Tooltip } from 'antd';
import { DownOutlined, RightOutlined } from '@ant-design/icons';
import { theme } from '@apache-superset/core';
import { Cte, Entry, Query, queriesOf } from './api';
import { actionButtonsWidth, queryName, shownName } from './fileActions';
import { fullDate, parseTimestamp, shortDate } from './dates';
import { renderInline } from './markdown';
import { TablePills, tablesId } from './tablePills';

// Long enough that moving the pointer down the tree doesn't flash a tooltip
// on every row.
const TOOLTIP_DELAY_S = 0.5;

const Label = theme.styled.span`
  flex: 1;
  display: flex;
  align-items: center;
  min-width: 0;
`;

// A long name gives way to the date and actions, never the other way round,
// but table pills only get the room it leaves.
const Name = theme.styled.span`
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
`;

// The right of the row, where the date and the actions take turns. It stays
// at the right when there are no pills to fill the row. The
// date stays in the layout while hidden, and the actions lie over the slot,
// which is at least as wide as they are. So the slot keeps one width as they
// swap, and a long name doesn't shift.
const Slot = theme.styled.span<{ withActions: boolean }>`
  position: relative;
  flex: none;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  margin-left: auto;
  padding-left: ${({ theme }) => theme.sizeUnit * 2}px;
  min-width: ${({ theme, withActions }) =>
    withActions ? actionButtonsWidth(theme) : 0}px;
`;

const ActionsOverlay = theme.styled.span`
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
`;

// Says that a file's or query's row opens to show its CTEs (009), and
// whether it's open. Folders show it with their icon instead. The
// splitters' chevrons, right closed and down open (016), at the action
// buttons' icon size. Spaced on the
// left only: the pill or date slot after it brings its own.
const Chevron = theme.styled.span`
  flex: none;
  display: flex;
  margin-left: ${({ theme }) => theme.sizeUnit * 2}px;
  color: ${({ theme }) => theme.colorTextSecondary};
  font-size: ${({ theme }) => theme.fontSize}px;
`;

// Quiet decoration.
const DateText = theme.styled.span`
  padding: 0 ${({ theme }) => theme.sizeUnit}px;
  color: ${({ theme }) => theme.colorTextSecondary};
  font-size: ${({ theme }) => theme.fontSizeSM}px;
  white-space: nowrap;
`;

const Paragraph = theme.styled.div`
  & + & {
    margin-top: ${({ theme }) => theme.sizeUnit * 2}px;
  }
  a {
    text-decoration: underline;
  }
`;

// Where a search result's file is (014): supplementary, so small and
// quieter than the tooltip's text. Words that matched there are bold, but
// not in the primary colour, which on a row means a match that counts for
// a lot. (Tooltips have their own colours, so this fades rather than
// using the secondary text colour.)
const Folder = theme.styled.div`
  margin-bottom: ${({ theme }) => theme.sizeUnit}px;
  font-size: ${({ theme }) => theme.fontSizeSM}px;
  opacity: 0.75;
  overflow-wrap: anywhere;
  mark {
    padding: 0;
    background: none;
    color: inherit;
    font-weight: ${({ theme }) => theme.fontWeightStrong};
  }
`;

/** What a folder's or file's row is called: a file without `.sql` (016). */
export const entryName = (entry: Entry) =>
  entry.type === 'file' ? shownName(entry.name) : entry.name;

/** The id of the element holding a file's date, which describes the row with `key`. */
export const dateId = (key: string) =>
  `sql-examples-modified-${encodeURIComponent(key)}`;

/** The tables shown on a file's row: its query's, when it has just one. */
const rowTables = (entry: Entry): string[] => {
  const queries = queriesOf(entry);
  return queries.length === 1 ? queries[0].tables : [];
};

/**
 * The ids of what describes `entry`'s row (its tables and date), if any.
 * `rowKey` tells apart rows of the same entry (in the tree and in results).
 */
export function describedBy(
  entry: Entry,
  rowKey: string = entry.path,
): string | undefined {
  if (entry.type !== 'file') return undefined;
  const ids = [
    rowTables(entry).length > 0 && tablesId(rowKey),
    parseTimestamp(entry.modified) !== null && dateId(rowKey),
  ].filter(Boolean);
  return ids.length > 0 ? ids.join(' ') : undefined;
}

const paragraphs = (description: string) => description.split(/\n\s*\n/);

/** What a file's tooltip says it's for: its query's description, or how many queries it has. */
function filePurpose(entry: Entry): string[] {
  const queries = queriesOf(entry);
  if (queries.length > 1) return [`${queries.length} queries`];
  const description = queries[0]?.description;
  return description ? paragraphs(description) : [];
}

const asTooltip = (texts: string[]): React.ReactNode =>
  texts.length > 0
    ? texts.map((text, i) => <Paragraph key={i}>{text}</Paragraph>)
    : null;

function tooltipFor(entry: Entry): React.ReactNode {
  if (entry.type === 'directory') {
    return entry.description ? (
      <Paragraph
        dangerouslySetInnerHTML={{ __html: renderInline(entry.description) }}
      />
    ) : null;
  }
  const texts = filePurpose(entry);
  const modified = parseTimestamp(entry.modified);
  if (modified) texts.push(`Changed ${fullDate(modified)}`);
  return asTooltip(texts);
}

/**
 * What any row shows: `name`, `tables` as pills (identified by `tablesKey`),
 * a date and actions sharing its right end, and `tooltip`. `expanded` is
 * whether rows under it are shown, for a file or query with CTEs.
 * `keyboardActive` is whether the keyboard has made it the active row,
 * which shows its tooltip as hovering does (008).
 */
const RowContents = ({
  name,
  tables,
  tablesKey,
  date,
  actions,
  tooltip,
  expanded,
  keyboardActive = false,
}: {
  name: React.ReactNode;
  tables: string[];
  tablesKey: string;
  date?: { id: string; text: string };
  actions?: React.ReactNode;
  tooltip: React.ReactNode;
  expanded?: boolean;
  keyboardActive?: boolean;
}) => {
  // The tooltip belongs to the whole row, so it opens beyond the row's
  // right end, clear of the actions, but not while the pointer is on them:
  // they have tooltips of their own.
  const [open, setOpen] = useState(false);
  const overActions = useRef(false);
  // Opened by the keyboard after the same delay as hovering, so moving
  // down the tree doesn't flash one on every row.
  const [keyboardOpen, setKeyboardOpen] = useState(false);
  useEffect(() => {
    if (!keyboardActive) {
      setKeyboardOpen(false);
      return undefined;
    }
    const timer = setTimeout(
      () => setKeyboardOpen(true),
      TOOLTIP_DELAY_S * 1000,
    );
    return () => clearTimeout(timer);
  }, [keyboardActive]);
  const label = (
    <Label>
      <Name>{name}</Name>
      {expanded !== undefined && (
        <Chevron aria-hidden>
          {expanded ? <DownOutlined /> : <RightOutlined />}
        </Chevron>
      )}
      <TablePills tables={tables} id={tablesId(tablesKey)} />
      {(date || actions) && (
        <Slot withActions={!!actions}>
          {date && (
            <DateText className="entry-date" id={date.id}>
              {date.text}
            </DateText>
          )}
          {actions && (
            <ActionsOverlay
              onMouseEnter={() => {
                overActions.current = true;
                setOpen(false);
              }}
              onMouseLeave={() => {
                overActions.current = false;
              }}
            >
              {actions}
            </ActionsOverlay>
          )}
        </Slot>
      )}
    </Label>
  );
  return tooltip ? (
    <Tooltip
      title={tooltip}
      placement="right"
      mouseEnterDelay={TOOLTIP_DELAY_S}
      open={open || keyboardOpen}
      // Gone once closed, rather than kept hidden for every row ever shown.
      destroyOnHidden
      onOpenChange={next => setOpen(next && !overActions.current)}
    >
      {label}
    </Tooltip>
  ) : (
    label
  );
};

/**
 * A folder's or file's row: a file's date and `actions` too. `now` is for
 * relative dates. `name` shows in place of the entry's name, and `rowKey`
 * is as for `describedBy`. `folder`, for a search result, heads its
 * tooltip (014). `expanded` and `keyboardActive` are as for `RowContents`.
 */
export const RowLabel = ({
  entry,
  now,
  actions,
  name = entryName(entry),
  rowKey = entry.path,
  folder,
  expanded,
  keyboardActive,
}: {
  entry: Entry;
  now: Date;
  actions?: React.ReactNode;
  name?: React.ReactNode;
  rowKey?: string;
  folder?: React.ReactNode;
  expanded?: boolean;
  keyboardActive?: boolean;
}) => {
  const modified = entry.type === 'file' ? parseTimestamp(entry.modified) : null;
  const tooltip = tooltipFor(entry);
  return (
    <RowContents
      name={name}
      tables={entry.type === 'file' ? rowTables(entry) : []}
      tablesKey={rowKey}
      date={
        modified
          ? { id: dateId(rowKey), text: shortDate(modified, now) }
          : undefined
      }
      actions={actions}
      tooltip={
        folder ? (
          <>
            <Folder>{folder}</Folder>
            {tooltip}
          </>
        ) : (
          tooltip
        )
      }
      expanded={expanded}
      keyboardActive={keyboardActive}
    />
  );
};

/** The ids of what describes a query or CTE row (its tables), if any. */
export const queryDescribedBy = (part: Query | Cte, rowKey: string) =>
  part.tables.length > 0 ? tablesId(rowKey) : undefined;

const descriptionTooltip = (description: string | null) =>
  description ? asTooltip(paragraphs(description)) : null;

/** The row of the `index`th query of a file that has several, named by its
 * place (its description is in its tooltip), with its `actions` (012).
 * `expanded` and `keyboardActive` are as for `RowContents`. */
export const QueryLabel = ({
  query,
  index,
  rowKey,
  actions,
  expanded,
  keyboardActive,
}: {
  query: Query;
  index: number;
  rowKey: string;
  actions: React.ReactNode;
  expanded?: boolean;
  keyboardActive?: boolean;
}) => (
  <RowContents
    name={queryName(index)}
    tables={query.tables}
    tablesKey={rowKey}
    actions={actions}
    tooltip={descriptionTooltip(query.description)}
    expanded={expanded}
    keyboardActive={keyboardActive}
  />
);

/** A CTE's row, under its query's (009): like a query row, named as in the
 * file. `name` shows in place of its name (search results highlight it,
 * 014). `keyboardActive` is as for `RowContents`. */
export const CteLabel = ({
  cte,
  rowKey,
  actions,
  name = cte.name,
  keyboardActive,
}: {
  cte: Cte;
  rowKey: string;
  actions: React.ReactNode;
  name?: React.ReactNode;
  keyboardActive?: boolean;
}) => (
  <RowContents
    name={name}
    tables={cte.tables}
    tablesKey={rowKey}
    actions={actions}
    tooltip={descriptionTooltip(cte.description)}
    keyboardActive={keyboardActive}
  />
);
