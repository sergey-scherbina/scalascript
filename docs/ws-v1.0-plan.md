# v1.0 — WebSocket production-readiness — execution plan

Long-lived feature branch: `worktree-feature-ws-v1.0`.
Source milestone: `MILESTONES.md` → `## v1.0 — WebSocket production-readiness`.

## Goal

Close the production gaps in the WebSocket stack across all three
server-capable backends — Interpreter (NIO proxy), JvmGen (blocking
sockets), JsGen (Node `http` + raw upgrade) — while keeping the
existing user-facing API additive.  Three sprints from `MILESTONES.md`:

- **Sprint 4 — observability** (3 items): structured logs, `metrics()`
  native, HTTP access log.
- **Sprint 5 — architectural debt** (3 items): full NIO HTTP on JvmGen,
  Loom vs NIO decision, `permessage-deflate`.
- **Sprint 6 — convenience helpers** (6 items): per-route max-conns,
  rate limit, auth at upgrade, `ws.id`, `ws.subprotocol`,
  close-echo wait.

## Backend matrix

| Subsystem            | Interpreter                                  | JvmGen                                        | JsGen                                |
|----------------------|----------------------------------------------|-----------------------------------------------|--------------------------------------|
| HTTP server          | JDK `HttpServer` (`WebServer.scala`)         | JDK `HttpServer` emitted into output          | Node `http.createServer`             |
| WS upgrade & framing | NIO proxy (`WsProxy.scala`) + `WsConnection` | Blocking accept loop emitted into output      | Raw socket on Node, JS framing       |
| Registry             | `WsRoutes.scala` (in-process)                | Emitted `wsRoutes: List[...]`                 | Emitted module-level registry        |

Every Sprint-6 helper touches all three backends (≈ "× 3" in the
milestone notes).  Sprint-4 items 13–14 same.  Sprint-4 item 15 (HTTP
access log) is meaningful on Interp + JvmGen only — JsGen doesn't
proxy.

## Sprint ordering rationale

Default order in `MILESTONES.md`: 4 → 5 → 6.  Recommended order for
this branch: **4 → 6 → 5**.  Reasons:

1. Sprints 4 and 6 are additive and small (10–80 LOC each).  They
   can land iteratively on the feature branch with the suite green
   between every iteration.
2. Sprint 5 is explicitly marked in `MILESTONES.md` as *"Defer until
   1-4 are settled and a real workload demands them."*  Item 5.16
   (full NIO HTTP migration on JvmGen) is ~1500 LOC and rewrites the
   request/response path.  Item 5.17 (Loom-only or NIO-only) is a
   one-way architectural decision that needs explicit sign-off
   before any code, not after.
3. Sprint 5 item 5.18 (`permessage-deflate`) is independent of 5.16
   and 5.17 — it could land before them if there's a use case.
4. If we land 4 + 6 first, the surface area for Sprint 5 is smaller
   (we won't be migrating `ws.id` / per-route limits at the same time
   as ~1500 LOC of NIO work) and the user can decide whether to
   actually do 5 after seeing the cost-benefit on a green-branch.

**Decision required from user:** do we ship Sprint 5 in this branch,
or stop after Sprints 4 + 6 and split 5 into its own (later) branch?
See "Open questions" below.

---

## Sprint 4 — observability

### 4.1 — Structured connect/disconnect/error logs (`#13` in MILESTONES)

What lands: every WS lifecycle event prints one structured line on
the server log.  Format: tab-separated key=value, single line, parseable
by `grep` / `cut` / `awk`.

```
ws.connect  ts=2026-05-17T10:23:11Z id=8c3e ip=127.0.0.1 route=/chat origin=https://app.com proto=v2
ws.message  ts=…                     id=8c3e dir=in  size=42
ws.close    ts=…                     id=8c3e code=1000 reason="" duration=12.4s in=23 out=18 bytes_in=512 bytes_out=410
ws.error    ts=…                     id=8c3e where=onMessage err="…"
```

Touch sites:

- **Interp:** `WsConnection.scala` — add four log helpers, call from
  `closeNow` (with stats), `onFrame` (per message), upgrade site in
  `WsProxy.scala` `tryUpgrade`.
- **JvmGen:** mirror in the emitted preamble around line 2800–3380 of
  `JvmGen.scala`.
- **JsGen:** mirror in `JsGen.scala` ~line 250+ (server-side block).

Depends on **4.2**'s per-connection counters and Sprint-6 **6.4**'s
`ws.id`.  Plan: implement `ws.id` first (6.4 — trivial) so 4.1 can use
it, then 4.1 lands with counters seeded from 4.2.

~ 60 LOC × 3 backends.

### 4.2 — `metrics()` native (`#14`)

What lands: a `metrics(): Map[String, Long]` native returning current
counter snapshot.

```
metrics() = Map(
  "ws.active"       -> 12,
  "ws.upgraded"     -> 5_234,
  "ws.rejected"     -> 17,        // origin/proto/cap denials
  "ws.messages.in"  -> 1_205_432,
  "ws.messages.out" -> 1_205_001,
  "ws.bytes.in"     -> 89_412_001,
  "ws.bytes.out"    -> 99_120_882,
  "http.requests"   -> 23_400,    // populated by 4.3
  "http.4xx"        -> 12,
  "http.5xx"        -> 0
)
```

Storage: process-global `AtomicLong`s in a new `Metrics.scala` (interp)
+ matching emitted `object Metrics` in JvmGen and a JS module-level
`_metrics` in JsGen.  Reads are atomic; the map is built on demand.

Touch sites: same three files as 4.1 + register the native at
`Interpreter.scala:~1000` (next to `setMaxWsConnections`).  Conformance:
new test `ws-metrics-smoke` that opens a WS, sends 3 messages, closes,
checks counter deltas across all three backends.

~ 100 LOC of new code + ~ 50 LOC of touchups in existing call sites.

### 4.3 — HTTP access log (`#15`)

What lands: every HTTP request that flows through the NIO proxy /
JDK `HttpServer` prints one line on completion.

```
http  ts=…  ip=127.0.0.1  method=GET  path=/users/12  status=200  bytes_out=412  duration=1.2ms  ua="curl/8.…"
```

Touch sites:
- **Interp:** `WebServer.scala` `handle(...)` — wrap with timing,
  emit on `finally`.  Need to capture `status` and bytes-written —
  add an `HttpExchange` wrapper or read back via `getResponseBody`.
  Cheapest: a small `AccessLog` helper that takes
  `(method, path, status, bytesOut, durationNs, ip, ua)`.
- **JvmGen:** mirror in the emitted `handle` body.
- **JsGen:** wrap the `http.createServer` callback.

~ 40 LOC × 3.  No new tests — covered by existing HTTP tests
checking stderr.

---

## Sprint 6 — convenience helpers

Order inside Sprint 6: smallest / unblocking first.

### 6.4 — `ws.id` (UUID-v4) (`#22`)

Trivial.  Add a `val id: String = UUID.randomUUID().toString` to
`WsConnection`, surface it in `asValue` as a new `"id"` key in the
`InstanceV("WebSocket", …)` map.  Mirror in JvmGen / JsGen.  ~10 LOC × 3.
Conformance: `ws-id-smoke` — two consecutive opens get different
ids on every backend.

Lands first so 4.1 can include `id=…` in its log lines.

### 6.5 — `ws.subprotocol` (`#23`)

Surface the protocol the server selected during upgrade negotiation
as an `InstanceV` field.  Already computed in `WsProxy.tryUpgrade`
(`chosenProtocol` var, line ~284); plumb it into the `WsConnection`
constructor and expose as `"subprotocol"`.  Empty string when none
negotiated.  Mirror in JvmGen / JsGen.  ~15 LOC × 3.
Conformance: `ws-subprotocol-smoke`.

### 6.3 — Per-route `maxConnections` (`#19`)

Add a fifth field to `WsRoutes.Entry` and a new overload of
`onWebSocket(path, origins, protocols, maxConnections)(handler)`.
Atomic per-route counter; reservation done at upgrade time, release
on close.  Process-wide `setMaxWsConnections` is unchanged — both
caps apply (a connect must pass both).  ~40 LOC × 3.
Conformance: `ws-route-cap-smoke` — register `/chat` with cap=2,
open 3 connections, third gets 503.

### 6.6 — Close-handshake echo wait (`#24`)

`WsConnection.closeNow` currently tears down the channel
immediately after `sendClose`.  RFC SHOULD wait briefly for the
peer's close-echo first.  Insert a `scheduler.schedule(closeNow,
200ms)` between `sendClose(...)` and channel-cancel in the
server-initiated path (peer-initiated is fine — peer just echoed).
Mirror in JvmGen / JsGen.  ~10 LOC × 3.
No new conformance test; existing close tests already cover this
indirectly (just changes timing).

### 6.2 — Auth helper at upgrade time (`#21`)

`onWebSocket("/x", auth = bearer(t => validate(t))) { … }`.  Pre-upgrade
hook that receives `(headers, cookies)` and returns `Option[Value]`
(Some=accept-with-this-userValue, None=close with 1008).  Wire as a
new `auth: Option[Value]` field on `WsRoutes.Entry`; invoked from
`WsProxy.tryUpgrade` before reserving the slot.
~ 50 LOC + ~ 20 LOC for the `bearer(...)` / `cookieSession(...)`
helper API × 3.
Conformance: `ws-auth-bearer-smoke` — valid token upgrades, invalid
gets 401, no token gets 401.

### 6.1 — Per-connection rate limit (`#20`)

Per-connection sliding window: `maxMsgsPerSec`.  On overflow:
`close(1008, "rate-limited")`.  Stored on `WsConnection` as
`(windowStartMs: Long, msgsInWindow: Int)`; checked at the top of
`dispatchMessage`.  Knob: 4th arg of `onWebSocket` overload, default
0 = unlimited.  ~30 LOC × 3.
Conformance: `ws-rate-limit-smoke` — flood 100 msg/s with cap=10/s,
expect 1008.

---

## Sprint 5 — architectural debt — **conditional**

Listed for completeness; recommend deferring per "Sprint ordering
rationale" above.  Each item below assumes Sprints 4 + 6 are in.

### 5.1 — Loom-vs-NIO decision (`#17`) — **first if Sprint 5 proceeds**

Not code: a `docs/ws-runtime-decision.md` and a 1-paragraph entry
under "Decisions already made" in `AGENTS.md`.  Either path commits
us to a single threading model for *both* JVM backends:

- **Pick NIO** → 5.16 lands (~1500 LOC); JvmGen migrates to NIO;
  interpreter unchanged; Loom code in emitted preamble deleted.
- **Pick Loom** → 5.16 cancelled; interpreter NIO proxy rewritten
  to Loom + blocking I/O (~1000 LOC across `WsProxy.scala` +
  `WsConnection.scala`); JvmGen unchanged.

**Cannot proceed without user input on this one.**

### 5.2 — `permessage-deflate` (`#18`)

RFC 7692.  Negotiates `permessage-deflate` extension during upgrade,
applies per-message DEFLATE (zlib) on outbound, INFLATE on inbound.
Adds two opcodes-extension bits to framing (`RSV1` flag = compressed
message).  ~ 200 LOC × 3.  Independent of 5.1; can ship even if
the runtime-model decision is deferred.  Use Java's
`java.util.zip.Inflater` / `Deflater` on JVM, `zlib` on Node.
Conformance: `ws-deflate-smoke` — round-trip a 4 KB JSON payload,
verify it compresses on the wire (small TCP capture or `bytes_out`
delta from 4.2 metrics).

### 5.3 — Full NIO HTTP on JvmGen (`#16`)

~ 1500 LOC.  Replaces emitted JDK `HttpServer` with an emitted NIO
selector loop folded together with the existing WS-proxy code.
Eliminates the loopback hop.  Touches the entire HTTP request/response
path in `JvmGen.scala` (~ lines 2700–3500).  Only do this if 5.1
picks NIO.

---

## Iteration checklist (applied to every commit on the branch)

Per `AGENTS.md` "Iteration-discipline checklist":

1. Implement on the feature branch.
2. Run the full check suite:
   ```
   sbt compile
   sbt test
   scala-cli conformance/run.sc
   scala-cli e2e/examples-run-all.sc       # if examples touched
   ```
3. Update `MILESTONES.md` in the same commit — strike the closed item.
4. `git fetch origin && git rebase origin/main` if origin moved.
5. **Do NOT push to `origin/main` yet** — big-feature workflow keeps
   intermediate commits on the branch.  Push to the *feature branch*
   so the user can pull / review.
6. Update this plan document in the same commit — strike the
   iteration, add any new follow-ups under the right sprint.

## Final integration

When every iteration of Sprints 4 + 6 (and Sprint 5 if approved) is
green and `MILESTONES.md` reads forward without stale Sprint-4/5/6
items:

1. Final `git fetch && git rebase origin/main`.
2. Full check suite once more.
3. Documentation pass: README "What works" / SPEC §WS / any
   examples updated for new APIs.
4. Single merge commit (or fast-forward) into `main`.  Push once.
5. Delete this plan doc and `ExitWorktree(action: "remove")`.

## Open questions for the user

1. **Sprint 5 inclusion.**  Ship 4 + 6 only, or 4 + 6 + 5?
   - 4 + 6 only: ~ 2-3 days, all additive, low risk.
   - + 5.2 (`permessage-deflate`): + ~ 2 days, still additive.
   - + 5.1 / 5.3 (NIO migration): + ~ 2 weeks, architectural,
     requires the Loom-vs-NIO decision up front.

2. **Sprint 5.1 — Loom or NIO?**  Only matters if (1) includes 5.
   Current state:
   - Interpreter: NIO.
   - JvmGen: Loom + blocking sockets (`Thread.startVirtualThread`).
   - Convergence direction unsettled.

3. **JsGen scope for new features.**  Some Sprint-6 items
   (`permessage-deflate`, NIO migration) have no JsGen equivalent —
   Node WS uses its own socket abstraction.  OK to leave JsGen at
   feature-parity-minus-compression for v1.0?

4. **Backwards compatibility.**  All new `onWebSocket` overloads are
   strictly additive.  Existing 1-/2-/3-arg call sites keep working.
   `ws.id`, `ws.subprotocol`, `ws.recv` (already present) — new
   fields on the WebSocket instance, no breakage.  Confirm this
   is the contract for v1.0.
