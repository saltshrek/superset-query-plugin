// Stands in for the extension's backend route (plugin/backend), which needs
// a running Superset. It forwards to a real code-source and answers as the
// feature specs say for each route (003 for the tree, 004 for files, 006
// and 013 for search, 013 for suggestions); the backend's own tests (plugin/backend/tests) check the real routes against
// the same contract.
// Node's fetch, put on the global object by environment.js. The panel's
// `fetch` is something else (see backend.ts).
declare const nodeFetch: typeof fetch;

// Our backend's routes, the code-source endpoint each forwards to, and the
// parameters it passes on (each repeated as given).
const ROUTES: Record<string, { endpoint: string; params: string[] }> = {
  '/extensions/quillfern/sql-examples/code-source/tree': {
    endpoint: '/api/v1/tree',
    params: ['path'],
  },
  '/extensions/quillfern/sql-examples/code-source/file': {
    endpoint: '/api/v1/file',
    params: ['path'],
  },
  '/extensions/quillfern/sql-examples/code-source/search': {
    endpoint: '/api/v1/search',
    params: ['q', 'limit', 'ext', 'reference', 'function', 'keyword', 'unqualified'],
  },
  '/extensions/quillfern/sql-examples/code-source/suggestions': {
    endpoint: '/api/v1/suggestions',
    params: ['q', 'field', 'ext', 'limit'],
  },
};
const FORWARDED_ERROR_STATUSES = [400, 404, 413, 422, 503];

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Makes the panel's `fetch` reach code-source at `codeSourceUrl()`. */
export function forwardBackendTo(codeSourceUrl: () => string): void {
  window.fetch = async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://superset.test');
    const route = ROUTES[url.pathname];
    if (!route) return json(404, { message: 'Not Found' });
    const forwarded = new URLSearchParams(
      [...url.searchParams].filter(([name]) => route.params.includes(name)),
    ).toString();

    let upstream: Response;
    try {
      upstream = await nodeFetch(
        `${codeSourceUrl()}${route.endpoint}${forwarded ? `?${forwarded}` : ''}`,
      );
    } catch {
      return json(502, { message: 'code-source is unreachable' });
    }
    const body = await upstream.json();
    if (upstream.status === 200) return json(200, { result: body });
    if (FORWARDED_ERROR_STATUSES.includes(upstream.status)) {
      return json(upstream.status, {
        message: body.error.message,
        code: body.error.code,
      });
    }
    return json(502, { message: 'code-source answered unexpectedly' });
  };
}
