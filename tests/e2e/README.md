# End-to-end smoke harness

Two scripts live here:

- `rest-smoke.sc` — boots `examples/rest-api.ssc` through each of the
  three backends and diffs HTTP responses (REST runtime parity).
- `spa-smoke.sc` — runs `emit-spa examples/spa-demo.ssc`, structurally
  validates the bundle, `node --check`s the embedded JS, and (if `jsdom`
  is on `NODE_PATH`) drives the rendered DOM to assert link clicks +
  popstate navigate through the SPA runtime.

## rest-smoke.sc

`e2e/rest-smoke.sc` boots `examples/rest-api.ssc` on `http://localhost:8765`
through each of the three backends in turn:

- `ssc-int` — JVM tree-walking interpreter via `bin/ssc`
- `ssc-jvm` — Scala 3 compile via `bin/sscc` (JvmGen + scala-cli)
- `ssc-js`  — Node via `bin/jssc` (JsGen)

For each backend it waits for the server to become ready (polling
`GET /api/todos`), runs an identical sequence of HTTP requests, captures
the `(status, body)` of every response, then diffs the three captures.
Any drift is reported and the script exits non-zero.

## What it covers

- `GET /api/todos` initial state
- `POST /api/todos` adds a todo (201 + echo body)
- `GET` after POST shows the new state
- `DELETE /api/todos/:id` (path capture)
- `GET` after DELETE shows the state minus the deleted entry
- `GET /` renders the HTML view (matches by markers, not byte-equal —
  the three `html"..."` interpolators preserve different amounts of
  whitespace, by design)
- `GET /no-such-route` returns 404 — body intentionally not compared
  since the interpreter additionally falls back to static `.ssc` page
  rendering, while the JVM and JS backends are pure REST runtimes

## Usage

```bash
scala-cli e2e/rest-smoke.sc
```

`bin/ssc`, `bin/sscc`, `bin/jssc` are picked up when present (faster
cold-start).  Otherwise the harness falls back to `scala-cli run compiler
-- <subcommand>` so it works from a fresh checkout or worktree.  It binds
port 8765 and kills any process already holding it.

## spa-smoke.sc

Generates `examples/spa-demo.ssc` via `emit-spa` and validates the
resulting HTML bundle in three layers:

1. **Structural** — `<!doctype>`, `<title>spa-demo</title>`, exactly one
   `</script>`, the `_spaDispatch` sentinel that proves the browser
   overlay is present, and route registrations for `/`, `/about`,
   `/contact`.
2. **Syntax** — `node --check` on the embedded JS catches regressions
   in the runtime overlay or codegen output.
3. **Runtime (optional)** — if `jsdom` is importable (probed via
   `NODE_PATH=$(npm root -g)`), the harness loads the page in jsdom,
   asserts the initial `serve()` dispatch renders `<h1>Home</h1>`,
   clicks `/about` and `/contact`, exercises `history.back()` +
   `popstate`, and diffs `location.pathname` + the rendered `<h1>` at
   each step.  When jsdom isn't installed the runtime test is skipped
   with a hint (`npm install -g jsdom`) — the harness still passes.

```bash
scala-cli e2e/spa-smoke.sc
```

## Why not in `conformance/`

Conformance tests run a `.ssc` file once, compare stdout against an
`expected/*.txt` fixture, and exit.  The REST surface is request/response
shaped and needs a live server + HTTP client, plus orchestration to start
each backend separately on the same port.  Keeping it under `e2e/` makes
that distinction explicit and lets the harness stay opinionated about
process management.
