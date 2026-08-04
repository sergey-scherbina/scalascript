# Terminal managed-fetch: the URL may come from a signal

Status: spec (2026-08-04)
Owner: `tui-fetch-url-signal`
Affected: `frontend/core` (model), `v1/runtime/std/fetch-plugin` (site), static `frontend/tui`
Reported by: rozum — `INBOX.md` entry `tui-fetch-url-signal` (`reported-at: 2026-08-04`)

Sibling of [`frontend-tui-fetch-refresh.md`](frontend-tui-fetch-refresh.md) (the managed-GET
contract) and [`frontend-tui-fetch-headers.md`](frontend-tui-fetch-headers.md) (the credential half
of the same reporter's screen). Same acceptance shape as both.

## Goal

`fetchUrlSignalTo` — a GET whose URL is a `Signal[String]` resolved at fetch time, re-fetching
whenever that signal changes — must work on the terminal target, so one `.ssc` source can drive a
picker (choose a room, page to another day, switch a filter) on both frontends.

## Baseline defect — and it is NOT emitter-only

The reporter described this as the emitter baking the URL, which is true and is the visible half.
Reading the code turns up the deeper reason: **the static-frontend path cannot express a
signal-URL fetch at all.**

```scala
sealed class FetchUrlSignal(id2, val fetchUrl: String, val tickId: String,
                            val headersId: Option[String] = None)
```

`fetchUrl` is a plain `String`; there is no `urlId`. And `FetchIntrinsics` has a
`QualifiedName("fetchUrlSignal")` site but **no `fetchUrlSignalTo`** — that primitive is implemented
in the JS runtime (`_ssc_ui_fetchUrlSignalTo`) and in v2's `UiNativePlugin`, both of which are other
paths. So a source calling it does not reach `TuiEmitter` in a form the emitter could honour even if
it wanted to.

Consequence for a consumer: on the web the picker retargets the fetch; the terminal binary keeps
reading whichever endpoint was resolved at emit time. The list renders, the selection moves, `Enter`
fires, and the content below never changes — **a client that looks right and is quietly wrong**,
which is the same failure shape as the dropped headers.

## Required behavior

1. `fetchUrlSignalTo(name, urlSignal, refreshTick[, headers])` is constructible on the static path
   and produces a `FetchUrlSignal` carrying the URL signal's id.
2. The emitted crate resolves the URL **at fetch time** from the signal store — never at emit time.
3. A change to the URL signal schedules a new GET before the next frame, exactly as a tick change
   does. This is the point of the feature: the tick is not the only trigger.
4. An absent or empty URL performs **no request** and leaves the last good value intact. A picker
   with nothing selected must not issue `GET ""`, and must not blank a table that was already
   populated.
5. Headers compose: a signal-URL fetch that also carries headers sends them, resolved at the same
   moment, on both the bootstrap and the retarget path.
6. A fetch with a literal URL keeps behaving exactly as before — same emitted code, same
   dependencies.

## Generated-runtime shape

`FetchInfo` becomes `(url, tickId, headersId, urlId: Option[String])`, `url` staying as the literal
for the ordinary case.

The change with the sharp edge is the **change-detection map**. Today the loop remembers the last
observed *tick* per fetch and re-fetches when it moves. A signal URL adds a second trigger, so what
is remembered must be the pair — remembering only the tick means a retarget with an unchanged tick
never re-fetches (the exact bug this spec exists to remove), and remembering only the URL means a
plain refresh stops working. Both halves need a test; the first is the one a string-matching
emitter test will happily miss.

## Verification

Emitter tests (fast):

- a fetch built from `fetchUrlSignalTo` emits URL resolution from the signal store, and the
  observed-state entry covers both the tick and the URL;
- a fetch with a literal URL emits the unchanged literal path — proving no regression and no new
  dependency for sources that never asked for this.

Cargo integration test (the real proof), a local HTTP server with two endpoints returning
distinguishable bodies:

- boot with the URL signal set to the first → the first body renders;
- set the signal to the second, tick unchanged → the second body renders;
- set the signal to empty → the previous body is retained and no request is made;
- bump the tick with the URL unchanged → still re-fetches.

## Non-goals

- The v2 path and `UiNativePlugin` — they already implement the primitive.
- Other static emitters. The model field is additive and they ignore it; teaching react/vue/solid to
  read `urlId` is separate work with its own reporter.
- `fetchActionTo` (the write-side counterpart) — that belongs with `tui-fetch-post`.
