# Deploying the SQL Examples extension

For Superset operators installing the panel. There are two parts:

1. **code-source**, a service that serves the examples repository. Its
   image is built from `code-source/Dockerfile`, and
   `code-source/deploy/kubernetes.yaml` is an example deployment.
2. **This extension**, a `.supx` bundle that Superset loads. Its backend
   runs inside Superset and calls code-source over the private network.
   Browsers never call code-source directly.

```
browser ──▶ Superset (+ this extension's backend) ──▶ code-source ──▶ git remote
```

## Requirements

- Superset 6.1.0 **with the SQL Lab change below**. Upstream 6.1.0 loads
  the extension, but the panel never appears.
- code-source deployed where Superset's backend can reach it, for example
  a ClusterIP Service in the same cluster. Keep it unreachable from
  browsers: it has no authentication.

## 1. Build the bundle

In `plugin/`, with Node 22 and the extensions CLI
(`pip install apache-superset-extensions-cli==0.1.0`):

    superset-extensions bundle

This writes `sql-examples-<version>.supx`. With the dev stack running, you
can build it there instead:

    docker compose -f dev/compose.yaml exec plugin-dev superset-extensions bundle

## 2. Install it

Put the `.supx` file in a directory that every Superset web process can
read: bake it into your Superset image, or mount it. Superset loads every
`*.supx` in `EXTENSIONS_PATH`, so this directory can hold other
extensions too.

Superset imports an extension's backend only at startup, so restart
Superset after installing or upgrading the bundle.

## 3. Configure Superset

In `superset_config.py`:

```python
import os

FEATURE_FLAGS = {
    # ...your other flags
    "ENABLE_EXTENSIONS": True,
}

# The directory holding the .supx bundle.
EXTENSIONS_PATH = "/app/extensions"

# Where the extension's backend calls code-source. Required: without it
# every panel request is a 500, "SQL_EXAMPLES_CODE_SOURCE_URL is not
# configured". os.environ[...] makes Superset fail at startup instead.
SQL_EXAMPLES_CODE_SOURCE_URL = os.environ["SQL_EXAMPLES_CODE_SOURCE_URL"]
```

Superset reads `superset_config.py` from the module path, or from the file
named by `SUPERSET_CONFIG_PATH`.

### Environment variables

| Variable | Example | Notes |
|---|---|---|
| `SQL_EXAMPLES_CODE_SOURCE_URL` | `http://code-source.examples.svc:8080` | code-source's base URL, from Superset's backend. Read by the config above. Superset doesn't read it on its own. |
| `SUPERSET_FEATURE_ENABLE_EXTENSIONS` | `true` | Optional: Superset reads `SUPERSET_FEATURE_*` variables as feature flags, so this can replace the `FEATURE_FLAGS` entry. |

`EXTENSIONS_PATH` has no environment variable of its own. Set it in the
config, or read your own variable there as above.

Don't set `LOCAL_EXTENSIONS` in production. It's for the dev environment,
and an extension loaded from there takes precedence over its `.supx`.

## 4. Permissions

The extension adds no permission of its own. Anyone who can use SQL Lab
(`can_read` on `SQLLab`, e.g. the `sql_lab` role) can use the panel. Its
routes answer 403 to everyone else.

## 5. Check it

As a SQL Lab user, the backend's health route should report code-source
healthy:

    curl -H "Authorization: Bearer <token>" \
      https://<superset>/extensions/quillfern/sql-examples/code-source/health
    {"result":{"status":"ok"}}

Then open SQL Lab: the panel is below the schema browser in the left
panel. Until code-source has loaded the repository the panel shows that
it isn't ready yet; that clears after the first clone.

## The Superset fork's change

The panel registers at the view location `sqllab.leftSidebarBottom`,
which upstream SQL Lab doesn't have. The fork adds it by splitting SQL
Lab's left panel vertically, with extension views below the schema
browser. In `superset-frontend/src/SqlLab/components/AppLayout/index.tsx`
(6.1.0), roughly:

```tsx
const bonusLeft = views.getViews('sqllab.leftSidebarBottom') || [];

// The left Splitter.Panel's content, which was just <StyledSidebar>…:
<Splitter layout="vertical">
  <Splitter.Panel>
    <StyledSidebar>
      <SqlEditorLeftBar key={queryEditorId} queryEditorId={queryEditorId} />
    </StyledSidebar>
  </Splitter.Panel>
  {bonusLeft.length > 0 && (
    <Splitter.Panel collapsible={{ start: true, end: true, showCollapsibleIcon: true }}>
      <ContentWrapper>
        <ViewListExtension viewId="sqllab.leftSidebarBottom" />
      </ContentWrapper>
    </Splitter.Panel>
  )}
</Splitter>
```

`ContentWrapper` and `ViewListExtension` are already in that file, for
the right sidebar's extension views. Build the image with it as in
`dev/README.md` ("The Superset image").

## Known gaps

- Only checked with Talisman (Superset's Content Security Policy) turned
  off, as in the dev environment. The bundle is served from Superset's
  own origin, so the default policy should allow it, but that hasn't
  been tried.
