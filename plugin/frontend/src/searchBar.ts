import { theme } from '@apache-superset/core';

/** The space at the panel's sides and top: the schema browser's, from the
 * fork's StyledSidebar, so the two line up (015). */
export const panelGutter = ({ theme }: { theme: { sizeUnit: number } }) =>
  theme.sizeUnit * 2.5;

// The search box and its chips stay at the top of the panel as it scrolls
// (013), over the panel's own background. Their height is published as
// SEARCH_BAR_HEIGHT, so rows scrolled into view stop below them. The
// background reaches across the panel's gutters, so rows don't show in
// them either side of the box.
export const SearchBar = theme.styled.div`
  position: sticky;
  top: 0;
  z-index: 1;
  margin: 0 -${panelGutter}px;
  padding: 0 ${panelGutter}px;
  background: ${({ theme }) => theme.colorBgBase};
`;

/** The CSS variable holding the sticky search bar's height. */
export const SEARCH_BAR_HEIGHT = '--sql-examples-search-bar-height';
