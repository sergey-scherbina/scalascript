# Terminal DataTable: a failed fetch must not take the app down

Status: spec (2026-08-05)
Owner: `tui-table-fetch-failure`
Affected: static `frontend/tui` (`TuiEmitter`) only
Found by: rozum, running the generated meeting client as a shipped binary for the first time

## The defect

A remote table's rows are parsed with

```rust
let __rows = fetch_rows(&__json, "…", "…", &[…]).expect("invalid DataTable row identity");
```

`fetch_rows` returns `Err` for anything that is not the expected JSON — which includes every
ordinary operational answer: a `401` while a credential is missing, a `500`, an HTML error page, an
empty body because the daemon is not running. So the first frame panics and the process dies:

```
thread 'main' panicked at src/main.rs:281:
invalid DataTable row identity: "table response is not JSON"
```

For a client that ships to other people this is disqualifying. A terminal app that exits with a
Rust panic when its server is down is not one you can hand to anyone.

**Why it survived this long is the part worth keeping.** Every gate that exercises a remote table —
the PoC smoke, the dual-target gate, the cargo probes — points it at a healthy fixture that always
answers with well-formed JSON. The failure path had never been executed. This is the same shape as
the missing headers support and the baked token: *a capability proven against a fixture is proven
only for fixtures.*

## Required behavior

1. A table whose body does not parse renders **empty** — header row, no data rows — and the app
   keeps running.
2. The app must not lose what it already had: while a refresh fails, the table keeps showing the
   last rows it successfully parsed, exactly as a fetch already retains its last-good body.
3. `expect` stays for the case it was written for: `StaticRows`, where the row identity is known at
   emit time and a bad one is a source defect that SHOULD stop the build's own tests. Only the
   remote path becomes tolerant.
4. Nothing is silently swallowed: the failure is visible in the UI as an empty table rather than a
   frozen one, which is what a reader can act on. (A status line is a product decision for the app,
   not something the emitter should invent.)

## Verification

Emitter test: a remote table emits a fallible parse and no `expect`; a static table still emits the
strict one — the negative half, because making everything tolerant would hide real source defects.

Cargo gate, which is the one that matters: a local server that answers `401` with a plain-text body,
then `200` with rows. The probe renders a frame while the server is refusing and asserts the process
is still alive and the table is empty; then it refreshes against the healthy response and asserts
the rows appear. A string assertion cannot see a panic — only running the binary can.

## Non-goals

- Retry, backoff, or any error UI beyond "no rows".
- Distinguishing "empty result" from "failed fetch" in the emitted table; an app that needs that can
  bind a `SignalText` to the same body.
