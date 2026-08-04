# Terminal managed-fetch: a handler may WRITE

Status: spec (2026-08-04)
Owner: `tui-fetch-post` (implemented under the `tui-fetch-url-signal` claim — same file, same
reporter's screen; see that claim's `note:`)
Affected: static `frontend/tui` only
Reported by: rozum — `INBOX.md` entry `tui-fetch-post` (`reported-at: 2026-08-04`)

Third of the same reporter's three, after [`frontend-tui-fetch-headers.md`](frontend-tui-fetch-headers.md)
and [`frontend-tui-fetch-url-signal.md`](frontend-tui-fetch-url-signal.md). Same acceptance shape.

## Goal

`fetchAction("POST", url, bodySignal, onSuccessTick)` — the write half of the managed fetch — must
work on the terminal target, so one `.ssc` source can drive a composer (type, press Enter, the list
refreshes) on both frontends.

## Baseline defect — emitter-only this time

Unlike its two siblings, nothing is missing below the emitter. `EventHandler.FetchAction(method,
url, body, onSuccessTick, clearBody, headers)` is already in the core model, and `FetchIntrinsics`
already builds it for both `fetchAction` and `fetchActionTo`. The gap is one match:

```scala
private def activationOf(h: EventHandler): Option[Mutation] = h match
  case EventHandler.SetSignalLiteral(s, value) => Some(Mutation.Set(s.id, valueExpr(value)))
  case EventHandler.IncrementSignal(s, by)     => Some(Mutation.Incr(s.id, by))
  case EventHandler.ToggleSignal(s)            => Some(Mutation.Toggle(s.id))
  case _                                       => None          // ← FetchAction lands here
```

So a `TextInput` + `Button` composer emits a focusable whose activation is `None`: it renders, it
takes keystrokes, `Enter` moves the focus ring, and nothing is ever sent. **A client that looks
right and is quietly wrong** — the same failure shape as the other two, which is why all three were
reported together.

## Required behavior

1. A focusable whose handler is `FetchAction` performs the request on activation (`Enter`).
2. The body is read from the body signal **at activation time**.
3. `method` is honoured (`POST`/`PUT`/…), `Content-Type: application/json` is sent, and a bound
   headers signal is applied on top — resolved at the same moment, exactly as a GET's are.
4. **On success only**: `onSuccessTick` is incremented, and the body signal is cleared when
   `clearBody` is set. A failed request must leave both alone — a composer that clears the box on a
   failed send has eaten the user's message, which is worse than not sending it.
5. Because the tick is a fetch trigger, incrementing it makes the bound GET re-read on the next
   frame. That is how "post a message, see the list update" works with no extra wiring, and it
   composes with the signal-URL work: the re-read uses whatever URL is current then.
6. An app with no fetch-bound signal but a `FetchAction` still gets `ureq` — the manifest is derived
   from the emitted source, so this follows automatically and must be asserted, not assumed.

## Generated-runtime shape

`Mutation` gains a `Post(method, url, bodyId, tickId, clearBody, headersId)` arm, and `mutationRust`
emits a call to one helper rather than inlining the request in the match arm — the arms are
single-expression blocks and a multi-line request would not fit readably.

```rust
fn send_action(signals: &mut HashMap<String, Value>, method: &str, url: &str,
               body_id: &str, tick_id: &str, clear_body: bool, headers: &[(String, String)]) { … }
```

The success/failure asymmetry lives entirely in that helper: mutate the store *after* a 2xx, never
before.

## Verification

Emitter tests:

- a composer (`TextInput` + `Button(fetchAction)`) emits `send_action` with the method, URL, body id
  and tick id, and pulls in `ureq`;
- the failure path is visible in the emitted source: the tick bump and the body clear are inside the
  success branch;
- an app with only local handlers emits no `send_action` and no `ureq` — the negative half that
  keeps non-fetch crates lean.

Cargo integration test (a local HTTP server): activating the composer's button POSTs the typed body
and the bound GET re-reads afterwards; a 500 leaves the body signal untouched.

**Not landed with this change** — every cargo test lives in `TuiCargoSmokeTest.scala`, which
`tui-widget-compile-coverage` holds in its claimed paths. Asked in the room rather than driving
through another agent's claim; the emitter tests above are what gate this commit meanwhile, and they
are string assertions, which is exactly the kind the headers spec warns can miss a non-compiling
crate.

## Non-goals

- `DeleteItem` / `ItemAction` (the `ForModel` row handlers) — same shape, separate reporters.
- Streaming (`fetchStreamSignal`).
