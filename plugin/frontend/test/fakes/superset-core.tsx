// Stands in for @apache-superset/core, which Superset provides at runtime
// (window.superset). Only what the panel uses.
import React from 'react';
import { Alert as AntdAlert } from 'antd';
import type { AlertProps } from 'antd/es/alert';
import styled from '@emotion/styled';

type ViewProvider = () => React.ReactElement;

export const registeredViews: {
  id: string;
  location: string;
  provider: ViewProvider;
}[] = [];

export const views = {
  registerView(
    view: { id: string; name: string },
    location: string,
    provider: ViewProvider,
  ) {
    registeredViews.push({ id: view.id, location, provider });
    return { dispose: () => {} };
  },
};

// Superset's Alert: antd's, with the message as children.
export const components = {
  Alert: ({
    children,
    ...rest
  }: React.PropsWithChildren<Omit<AlertProps, 'message'>>) => (
    <AntdAlert role="alert" message={children} {...rest} />
  ),
};

// SQL Lab's tabs. The real createTab adds a tab to SQL Lab's store and makes
// it active; here we only record what was asked for.
export const createdTabs: { sql?: string; title?: string }[] = [];

export const sqlLab = {
  async createTab(options: { sql?: string; title?: string } = {}) {
    createdTabs.push({ sql: options.sql, title: options.title });
    return { id: `tab-${createdTabs.length}`, title: options.title ?? '' };
  },
};

// Superset's theme module re-exports Emotion's styled.
export const theme = { styled };
