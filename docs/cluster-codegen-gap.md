# Cluster intrinsics — per-backend gap matrix (Tier 4 prerequisite)

Status: audit snapshot taken 2026-05-20.  Re-run before scoping codegen
multi-node tests.

Companion to [`docs/cluster-management.md`](cluster-management.md) §11
(Tier 4) and [`docs/cluster-raft.md`](cluster-raft.md) §6.

## Scope

Backends audited:

- **JVM** — `backend-jvm` (`JvmGen` emits a Scala-cli script with an
  inlined `_runActors` runtime + `object Actor` facade dispatching
  through `_perform("Actor", op, …)`).
- **JS Node** — `backend-js` (`JsGen` emits Node-targeted JavaScript;
  `backend-node`'s `NodeBackend` re-uses `JsGen.generate` + Node glue,
  so the matrix below applies to both `js` and `node` target IDs).
- **INT** — `backend-interpreter` (tree-walking `Interpreter.scala`;
  the live runtime that `ssc` and `ssc.jar` execute today).

Out of scope: browser SPA (no inbound WS — cluster membership cannot
include browser nodes per `cluster-management.md` §7 hard-no list).

## Matrix

| Intrinsic                                     | JVM | JS Node | INT  | Notes |
|-----------------------------------------------|-----|---------|------|-------|
| `startNode(nodeId, url)`                      | ✓   | ✓       | ✓    | |
| `connectNode(url, token?)`                    | ✓   | ✓       | ✓    | foundational dial primitive |
| `joinCluster(seeds, token?)`                  | ✓   | ✓       | ✓    | |
| `serveAsync(port)`                            | ✗   | ✗       | ✓    | |
| `serveAsync(port, tls(certPath, keyPath))`    | ✗   | ✗       | ✓    | |
| `tls(certPath, keyPath)`                      | ✓   | ✓       | ✓    | helper for `serve(Async)?` |
| `electLeader()`                               | ✓   | ✓       | ✓    | Bully + Raft + coord branches |
| `currentLeader()`                             | ✓   | ✓       | ✓    | protocol-aware accessor |
| `subscribeLeaderEvents()`                     | ✓   | ✓       | ✓    | event stream into actor mailbox |
| `setAutoReelect(bool)`                        | ✓   | ✓       | ✓    | |
| `setQuorumSize(n)`                            | ✓   | ✓       | ✓    | gates Bully self-claim |
| `useRaftLeaderElection()`                     | ✓   | ✓       | ✓    | full §4.3 algorithm in each |
| `useExternalCoordinator(acquire, renew, release, holder)` | ✓ | ✓ | ✓ | 4-arg form; tick thread per backend |
| `leaderHistory()`                             | ✓   | ✓       | ✓    | bounded 100-entry ring |
| `clusterConfigSet(k, v)`                      | ✓   | ✓       | ✓    | LWW gossip + handshake snapshot |
| `clusterConfigGet(k)`                         | ✓   | ✓       | ✓    | |
| `clusterMetricSet(name, value)`               | ✓   | ✓       | ✓    | per-node gauge |
| `setDraining(bool)`                           | ✓   | ✓       | ✓    | drain-aware step-down wired in JVM + JS + INT |
| `broadcastHealth()`                           | ✓   | ✓       | ✓    | phi-vector broadcast |
| `clusterIsDown(nid, threshold?)`              | ✓   | ✓       | ✓    | majority phi-accrual vote |
| `globalRegister(name, ref)`                   | ✓   | ✓       | ✓    | |
| `globalWhereis(name)`                         | ✓   | ✓       | ✓    | |
| `subscribeClusterEvents()`                    | ✓   | ✓       | ✓    | NodeJoined/NodeLeft delivery |
| `clusterMembers()`                            | ✓   | ✓       | ✓    | local membership view |
| `selfNode()`                                  | ✓   | ✓       | ✓    | |
| `clusterHealth()`                             | ✓   | ✓       | ✓    | phi vector for connected peers |
| `phiOf(nid)` / `isSuspect(nid, thr?)`         | ✓   | ✓       | ✓    | per-link FD |
| `setReconnectPolicy(initialMs, maxMs[, giveUpMs])` | ✓ | ✓     | ✓    | |
| `setHeartbeatTimeout(intervalMs, deadAfterMs)`| ✓   | ✓       | ✓    | |
| `requestGossip()`                             | ✓   | ✓       | ✓    | |
| `clusterConfigKeys()`                         | ✓   | ✓       | ✓    | |
| `subscribeConfigEvents()`                     | ✓   | ✓       | ✓    | |
| `isDraining()` / `drainingPeers()` / `subscribeDrainEvents()` | ✓ | ✓ | ✓ | |
| `clusterMetricGet/Sum/Names()` / `subscribeMetricEvents()` | ✓ | ✓ | ✓ | |
| `leaderProtocol()`                            | ✓   | ✓       | ✓    | observes "bully"/"raft"/"coord" |
| `register(name, pid)` / `whereis(name)` (per-node) | ✓ | ✓     | ✓    | local-only counterpart to global |
| **— operational extras (not strictly required for Tier 4) —** | | | | |
| `publish(topic, msg)`                         | ✗   | ✗       | ✓    | cluster-wide pub/sub |
| `subscribePublish(topic)` / `unsubscribePublish(topic)` | ✗ | ✗ | ✓ | |
| `clusterAtomicGet/Set/Add/CompareAndSet`      | ✗   | ✗       | ✓    | cluster-wide atomic counters |
| `setClusterAuthToken(token)`                  | ✗   | ✗       | ✓    | bearer-token guard for `/_ssc-cluster/*` |
| `GET /_ssc-cluster/status` route auto-register| ✗   | ✗       | ✓    | ops HTTP endpoint |
| `POST /_ssc-cluster/drain` route auto-register| ✗   | ✗       | ✓    | |
| `GET /_ssc-cluster/events` route auto-register| ✗   | ✗       | ✓    | |
| `POST /_ssc-cluster/step-down` route auto-register | ✗ | ✗   | ✓    | |
| `GET /_ssc-cluster/metrics-prom` route auto-register | ✗ | ✗ | ✓    | |

## Notes

- `serveAsync` (JVM): no codegen mapping in `backend-jvm`.  `JvmGen`
  recognises `serve` (case `Term.Name("serve")`) and pulls in
  `serveRuntime`, but `serveAsync` falls through with no `Actor.*`
  alias and no symbol in the emitted runtime preamble — generated
  Scala fails to type-check.  Only `serve(port)` (blocking) and
  `serve(port, tls)` (blocking) exist in `runtime-server-jvm`.
- `serveAsync` (JS Node): same shape — no `Term.Name("serveAsync")`
  case in `JsGen` and no `serveAsync` entry in `JsHttpIntrinsics`.
  Bare `serveAsync(port)` would emit verbatim and `ReferenceError`
  at runtime.
- `serveAsync` (INT): implemented at
  `backend-interpreter/src/main/scala/scalascript/interpreter/intrinsics/Http.scala:46`,
  both 1-arg and 2-arg-with-TLS forms.  Runs the WS server on a
  virtual thread so the actor scheduler keeps draining envelopes —
  this is the unblock for multi-node clustering and is exercised by
  `cli/src/test/scala/scalascript/cli/MultiNodeClusterTest.scala`
  (which runs through the interpreter via `ssc.jar`).
- `publish` / `subscribePublish` / `unsubscribePublish` (JVM, JS):
  not yet ported to codegen — only `Interpreter.scala` dispatches
  these cases (search `case "publish"`).  Cluster-wide pub/sub from
  commit `a213bd8e` only landed on the interpreter side.
- `clusterAtomic*` (JVM, JS): same story as pub/sub — only the
  interpreter implements the LWW-gossip + handshake-snapshot atomic
  counter intrinsics from commit `b2121759`.
- `setClusterAuthToken` (JVM, JS): the shared-secret Bearer-token
  guard for `/_ssc-cluster/*` endpoints (commit `3e5fa64e`) is only
  recognised by the interpreter.  Codegen targets that try to call
  it will not compile.
- `/_ssc-cluster/*` route auto-registration (JVM, JS): the
  `status` / `drain` / `events` / `step-down` / `metrics-prom`
  endpoints are auto-installed by the interpreter as part of
  `_runActors` setup; codegen backends do not emit equivalent route
  registrations.  Apps compiled with `ssc compile-jvm` or the JS
  backend lose the entire ops surface even though the underlying
  intrinsic state (`currentLeader`, `_clusterEventQueue`, drain
  flags) is all present in the emitted runtime — the wiring to HTTP
  is just missing.
- Bully + Raft + external-coordinator algorithms are implemented in
  full on all three backends — `JvmGen` emits the algorithm as Scala
  in the `_runActors` body (search `_raftCurrentTerm`,
  `_ensureRaftTickThread`, `_ensureCoordTickThread`), `JsGen` emits
  the algorithm as JS (search `_raftCurrentTerm`, `_ensureCoordTickThread`),
  and the interpreter implements it directly in `Interpreter.scala`
  (search `raftCurrentTerm`, `startRaftElection`).

## What this gates

A codegen multi-backend cluster test only makes sense once every row
in the "core cluster intrinsics" portion of the matrix is ✓ or n/a on
every backend.  Today the gating gaps are:

1. **`serveAsync(port)` on JVM and JS Node.**  Without a non-blocking
   serve, codegen-compiled cluster nodes cannot bind a WS endpoint
   AND drive their actor scheduler in the same process — `serve()`
   blocks the calling thread and the scheduler stalls.  This is the
   identical limitation `cluster-raft.md` §9 calls out as already
   resolved on the interpreter but not yet on codegen.  Without this,
   the multi-backend matrix test cannot even spin up a JVM-compiled
   node and a JS-compiled node next to an INT node.

2. **`serveAsync(port, tls(...))` on JVM and JS Node.**  Same root
   cause as (1); the TLS overload is also INT-only today.  Less
   urgent than the plaintext form — a Tier 4 test can start without
   `wss://`, but production-shape parity demands it land before
   declaring Tier 4 done.

3. **`/_ssc-cluster/*` operational route auto-registration on JVM
   and JS Node.**  Not strictly required to validate
   `_runActors` peer-envelope parity (the matrix test can probe state
   via in-process actor sends), but without these the codegen-built
   binaries can't be polled by `ssc cluster status` / `drain` /
   `events` from a test harness — the existing CLI integration tests
   would all have to be rewritten or skipped for codegen targets.

The interpreter-only extras (`publish`, `clusterAtomic*`,
`setClusterAuthToken`) are not strictly required for the Tier 4 core
matrix — they're orthogonal cluster features built on top of the
underlying intrinsics — but they should land in codegen before any
*.ssc app that uses them can be cross-compiled to JVM or JS.

Once the gating gaps above land, the harness work (real-WS
multi-process integration test, see `cluster-raft.md` §9) is the
next blocker — separate workstream.
