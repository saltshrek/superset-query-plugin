# SQL Examples Explorer

A panel for Superset's SQL Lab that lets users browse a git repository of
example SQL queries and pull them into the editor. It has two components: a
Superset extension (the panel UI) and a standalone server that serves the
repository over HTTP.

Purpose, users and scope: see @docs/vision.md

## Layout

- `plugin/` – Superset extension. Has its own AGENTS.md.
- `code-source/` – standalone, read-only code source service (git-backed). Has its own AGENTS.md.
- `dev/` – Docker Compose dev environment (Superset + code-source + sample repo).
- `docs/vision.md` – what we're building and why.
- `docs/api.md` – the HTTP contract between plugin and server.
- `docs/features/` – one spec per feature, `NNN-short-name.md`.
- `docs/bugs/` – known bugs that need addressing, `NNN-short-name.md`.
- `docs/decisions.md` – decisions that affect more than one feature.

## Boundary rules

- The plugin talks to code-source **only** through the HTTP API in `docs/api.md`.
- code-source must not depend on anything Superset-specific. It will be
  extracted into its own repo and used by other clients.
- Any change to the API updates `docs/api.md` in the same commit.

## How we work

- Every feature starts as a spec in `docs/features/` (copy `_template.md`).
  Don't build anything an agreed spec doesn't cover. If the spec is missing
  something, stop and propose a change to the spec rather than guessing.
- Plan before editing. Keep changes small: one logical step per commit.
- Add tests for new behaviour with every change. Change existing tests only
  when the spec they assert has changed (see Testing).
- I review every diff. Explain anything non-obvious in your reply, and say
  which alternatives you considered when you made a design choice.
- Before finishing a feature, update its spec (Decisions, Implementation
  notes) and add to `docs/decisions.md` if the decision is cross-cutting.

## Testing

These are guiding principles, not rules. Depart from them when it's
practical to, and say why in your reply.

### What we value

- **Low test churn over high coverage.** When a test that asserted some
  behaviour is unchanged and still passing, that's evidence the behaviour
  still works. That evidence is what lets a reviewer trust a diff. Every
  change to existing test code weakens it, so test changes should be rare
  and deliberate.
- **Tests that resemble real use.** "The more your tests resemble the way
  your software is used, the more confidence they give you." (Kent C. Dodds)

### What that means in practice

- **Default to integration tests through public interfaces:** the HTTP API
  for code-source, the rendered panel for the plugin. Exercise as much of
  the real system as is practical, and assert what a user or API client
  would notice if it went missing.
- **Tests follow specs, not code.** Each test should trace to behaviour in a
  spec, usually an acceptance criterion. Test code should change only when
  a spec changes. Implementations should be refactorable without touching
  any test.
- **If a refactor forces a test change,** the test was coupled to
  implementation. Say so in your reply, and rewrite it against behaviour
  rather than patching it.
- **Coverage is not a goal.** Don't add tests just to cover lines.

### External boundaries

Prefer real dependencies wherever they're cheap to run. Fake only at the
narrowest seam where the real thing is impractical.

- **code-source:** no fakes for git. Tests build real repositories on local
  disk as fixtures and clone them via `file://` URLs.
- **plugin:** run the panel against a real code-source over HTTP. Superset's
  host APIs (e.g. the SQL Lab editor) may be faked at the narrowest seam
  that works.
- **End to end:** a few browser smoke tests against the Docker Compose
  stack, not a full suite.

### Isolated complex logic

Some code is complicated in its own right and can be tested with less of the
system running, e.g. path normalisation. That suits a narrower, unit-style
test, and the same principles still apply:

- Its "user", in Dodds' sense, is the surrounding software that relies on it
  to contain that complexity. Test it the way that software uses it.
- It deserves its own specification. Discuss what it promises before
  writing it, and write tests that change only when that specification
  does.
- It should need no mocking. If it does, it isn't really isolated: look
  for a better boundary, or test it through the integration route instead.

## Dev environment

<!-- Fill in after the dev-setup task. -->
See `dev/README.md` for details, including building the Superset fork image.

- Start: `docker compose -f dev/compose.yaml up`
- Smoke test (stack running): `dev/smoke-test.sh`
- Run tests: see the component AGENTS.md files

## Conventions

<!-- Add a line here whenever the agent makes the same mistake twice. -->
