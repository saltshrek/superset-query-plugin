// The tables a query reads, as one pill on its row (008, 016): decoration
// for an analyst who knows the data. It says how many, and its tooltip
// lists them.
import React from 'react';
import { Tag, Tooltip } from 'antd';
import { TableOutlined } from '@ant-design/icons';
import { theme } from '@apache-superset/core';

// Smaller than the theme's small text, which crowded the row. Its next size
// down (fontSizeXS, 8px) is too small to read.
const Pill = theme.styled(Tag)`
  && {
    flex: none;
    display: inline-flex;
    align-items: center;
    gap: ${({ theme }) => theme.sizeUnit * 0.75}px;
    margin: 0 0 0 ${({ theme }) => theme.sizeUnit * 2}px;
    padding: 0 ${({ theme }) => theme.sizeUnit * 0.75}px;
    font-size: ${({ theme }) => theme.fontSizeSM - 1}px;
    line-height: ${({ theme }) => theme.sizeUnit * 3.5}px;
    border-radius: ${({ theme }) => theme.borderRadiusXS}px;
    /* The action buttons' hover colour, as a light tint. */
    color: ${({ theme }) => theme.colorPrimary};
    background: ${({ theme }) => theme.colorPrimaryBg};
  }
`;

// What describes the row: every table, not just how many.
const VisuallyHidden = theme.styled.span`
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
`;

/** The id of the element listing a row's tables, which describes the row. */
export const tablesId = (key: string) =>
  `sql-examples-tables-${encodeURIComponent(key)}`;

/** `tables` as one pill, with `id` (see `tablesId`) on the element listing them. */
export const TablePills = ({ tables, id }: { tables: string[]; id: string }) => {
  if (tables.length === 0) return null;
  const list = tables.join(', ');
  return (
    <>
      <VisuallyHidden id={id}>{list}</VisuallyHidden>
      <Tooltip title={list}>
        <Pill bordered={false} aria-hidden="true">
          <TableOutlined />
          {tables.length}
        </Pill>
      </Tooltip>
    </>
  );
};
