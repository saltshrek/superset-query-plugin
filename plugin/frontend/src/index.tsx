import React from 'react';
import { views } from '@apache-superset/core';
import { Panel } from './Panel';

// Superset loads this module via Module Federation and runs it for its side
// effects. 'sqllab.leftSidebarBottom' (below the schema browser in SQL
// Lab's left sidebar) is added by our Superset 6.1.0 fork; it doesn't exist
// upstream.
views.registerView(
  { id: 'quillfern.sql-examples.panel', name: 'SQL Examples' },
  'sqllab.leftSidebarBottom',
  () => <Panel />,
);
