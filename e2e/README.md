# End-to-end smoke harness

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

The harness needs `bin/ssc`, `bin/sscc`, `bin/jssc` available — run
`./scripts/install.sh bin/ssc` once to set them up.  It binds port 8765
and kills any process already holding it.

## Why not in `conformance/`

Conformance tests run a `.ssc` file once, compare stdout against an
`expected/*.txt` fixture, and exit.  The REST surface is request/response
shaped and needs a live server + HTTP client, plus orchestration to start
each backend separately on the same port.  Keeping it under `e2e/` makes
that distinction explicit and lets the harness stay opinionated about
process management.
