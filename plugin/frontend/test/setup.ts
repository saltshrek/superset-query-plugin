import '@testing-library/jest-dom';
import { configure } from '@testing-library/react';

// Skip the visibility check in *ByRole queries. It calls getComputedStyle on
// every element, which jsdom makes very slow once antd's styles are loaded
// (seconds per query). Collapsed folders' rows aren't rendered at all, so
// that only matters for the tree while search results replace it; tests
// check that with toBeVisible().
configure({ defaultHidden: true });

// antd reads these; jsdom doesn't have them.
window.matchMedia ??= ((query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addListener: () => {},
  removeListener: () => {},
  addEventListener: () => {},
  removeEventListener: () => {},
  dispatchEvent: () => false,
})) as typeof window.matchMedia;
