// Filters in the search box (013): tables, functions and keywords chosen
// from suggestions, shown as chips under the box.
import React from 'react';
import { Button, Tag } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import { theme } from '@apache-superset/core';
import { Filter, FilterField } from './api';

/** What the panel calls each field. A reference, in SQL, is a table. */
export const fieldName = (field: FilterField) =>
  ({ reference: 'Table', function: 'Function', keyword: 'Keyword' })[field];

/** The word being typed at the end of `text`, if any: what's suggested for. */
export const wordBeingTyped = (text: string) => /\S+$/.exec(text)?.[0] ?? '';

/** `text` without the word being typed, or nothing if no other words remain. */
export const withoutWordBeingTyped = (text: string) => {
  const rest = text.replace(/\S+$/, '');
  return rest.trim() ? rest : '';
};

export const sameFilter = (a: Filter, b: Filter) =>
  a.field === b.field && a.value === b.value;

const Chips = theme.styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: ${({ theme }) => theme.sizeUnit}px;
  margin-bottom: ${({ theme }) => theme.marginXS}px;
`;

// Tinted like the tree's table pills (008), so a table reads the same in
// both places.
const Chip = theme.styled(Tag)`
  && {
    display: inline-flex;
    align-items: center;
    margin: 0;
    max-width: 100%;
    color: ${({ theme }) => theme.colorPrimary};
    background: ${({ theme }) => theme.colorPrimaryBg};
  }
  .chip-text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  && .ant-btn {
    height: auto;
    width: auto;
    min-width: 0;
    padding: 0 0 0 ${({ theme }) => theme.sizeUnit}px;
    color: inherit;
  }
`;

/** The chosen filters, each removable with its own button. */
export const FilterChips = ({
  filters,
  onRemove,
}: {
  filters: Filter[];
  onRemove: (filter: Filter) => void;
}) =>
  filters.length === 0 ? null : (
    <Chips role="group" aria-label="Search filters">
      {filters.map(filter => {
        const name = `${fieldName(filter.field).toLowerCase()} ${filter.value}`;
        return (
          <Chip key={`${filter.field} ${filter.value}`} bordered={false}>
            <span className="chip-text">
              {fieldName(filter.field)}: {filter.value}
            </span>
            <Button
              type="text"
              size="small"
              icon={<CloseOutlined />}
              aria-label={`Remove ${name}`}
              onClick={() => onRemove(filter)}
            />
          </Chip>
        );
      })}
    </Chips>
  );
