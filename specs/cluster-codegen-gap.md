# Cluster intrinsics — per-backend gap matrix (Tier 4 prerequisite)

Status: audit snapshot taken 2026-05-20; refreshed 2026-05-20 after
`serveAsync` and `/_ssc-cluster/*` route auto-registration landed on
both codegen backends.  Re-run before scoping codegen multi-node
tests.

Companion to [`specs/cluster-management.md`](cluster-management.md) §11
(Tier 4) and [`specs/cluster-raft.md`](cluster-raft.md) §6.

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
| `serveAsync(port)`                            | ✓   | ✓       | ✓    | landed `ea02fe06` (JVM), `7df3aa83` (JS) |
| `serveAsync(port, tls(certPath, keyPath))`    | ✓   | ✓       | ✓    | TLS overload same commits |
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
| `setClusterAuthToken(token)`                  | ✗   | ✓ (Actor facade) | ✓ | JS: `Actor.setClusterAuthToken` landed `c82c1203`; term-level lowering for bare `setClusterAuthToken(...)` calls still missing |
| `GET /_ssc-cluster/status` route auto-register| ✓   | ✓       | ✓    | landed `0b97e2df` (JVM), `c82c1203` (JS) |
| `POST /_ssc-cluster/drain` route auto-register| ✓   | ✓       | ✓    | same commits |
| `GET /_ssc-cluster/events` route auto-register| ✓   | ✓       | ✓    | same commits — `_clusterEventLog` ring (cap 200) added in both |
| `POST /_ssc-cluster/step-down` route auto-register | ✓ | ✓   | ✓    | same commits |
| `GET /_ssc-cluster/metrics-prom` route auto-register | ✓ | ✓ | ✓    | same commits |

## Notes

- `serveAsync` (JVM, landed): `runtime-server-jvm/ProxyRuntime.scala`
  exposes `serveAsync(port, tlsCfg = null)` that performs the same
  SPI bootstrap as `serve` but launches `backend.start(...)` on a
  JDK 21+ `Thread.ofVirtual()` and returns immediately.  `JvmGen`
  registers `serveAsync` in `JvmHttpIntrinsics` and adds a
  `Term.Name("serveAsync")` case to `blocksUseRoutes` so a bare
  `serveAsync(8080)` module pulls in `serveRuntime`.  Validated by
  scala-cli e2e in `JvmGenEffectsRuntimeTest`.
- `serveAsync` (JS Node, landed): `JsGen` emits a `serveAsync(port,
  _tlsCfg)` JS runtime function that delegates to the existing
  `serve` (Node's `server.listen` is already non-blocking; the event
  loop holds the process alive).  Validated by 3 tests in
  `NodeBackendTest` including an integration test that runs the
  emitted bundle under `node`, schedules an external TCP probe,
  confirms the listener bound, and verifies clean shutdown.
- `serveAsync` (INT): implemented at
  `backend-interpreter/src/main/scala/scalascript/interpreter/intrinsics/Http.scala:46`,
  both 1-arg and 2-arg-with-TLS forms.  Runs the WS server on a
  virtual thread so the actor scheduler keeps draining envelopes.
  Exercised by `cli/src/test/scala/scalascript/cli/MultiNodeClusterTest.scala`
  (which runs through the interpreter via `ssc.jar`).
- `publish` / `subscribePublish` / `unsubscribePublish` (JVM, JS):
  not yet ported to codegen — only `Interpreter.scala` dispatches
  these cases (search `case "publish"`).  Cluster-wide pub/sub from
  commit `a213bd8e` only landed on the interpreter side.
- `clusterAtomic*` (JVM, JS): same story as pub/sub — only the
  interpreter implements the LWW-gossip + handshake-snapshot atomic
  counter intrinsics from commit `b2121759`.
- `setClusterAuthToken` (JS Node, partially landed): `Actor.setClusterAuthToken`
  facade lives in the JS runtime (commit `c82c1203`) and dispatches
  via `_perform`.  Token defaults to `process.env.SSC_CLUSTER_TOKEN`
  at construction.  Missing: a `Term.Apply(Term.Name("setClusterAuthToken"),
  ...)` lowering in JsGen's surface codegen, so users calling
  unqualified `setClusterAuthToken("...")` from `.ssc` source still
  do not compile.  Same status as `setQuorumSize` (also `Actor` facade
  only).
- `setClusterAuthToken` (JVM): not yet ported.  The JVM cluster-routes
  emission picks up the token from `SSC_CLUSTER_TOKEN` env var at
  startup but exposes no runtime setter.  Follow-up: small wiring
  task — `_runActors` already has the field; need `Actor.setClusterAuthToken`
  facade + `_perform` dispatch + term-level lowering.
- `/_ssc-cluster/*` route auto-registration (JVM, JS — landed): the
  `status` / `drain` / `events` / `step-down` / `metrics-prom`
  endpoints are now auto-installed inside the emitted `_runActors`
  setup on both codegen backends.  Bearer-token gate, idempotency
  check, and a `_clusterEventLog` ring buffer (cap 200, byte-compatible
  with `Interpreter.clusterEventLog`) are emitted alongside.  JVM
  validated by 5 scala-cli e2e tests in `JvmGenEffectsRuntimeTest`;
  JS Node validated by e2e under `node` in `NodeBackendTest`.
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
every backend.  As of the 2026-05-20 refresh, the three original
gating gaps are closed:

1. **`serveAsync(port)` — closed.**  Landed on JVM (`ea02fe06`) and
   JS Node (`7df3aa83`).  The interpreter limitation that
   `cluster-raft.md` §9 called out is now fixed on codegen too.
2. **`serveAsync(port, tls(...))` — closed.**  TLS overload shipped
   in the same commits as the plaintext form.
3. **`/_ssc-cluster/*` operational route auto-registration — closed.**
   All five routes auto-install inside `_runActors` on JVM
   (`0b97e2df`) and JS Node (`c82c1203`), with a Bearer-token gate
   and a `_clusterEventLog` ring buffer.

Remaining gaps before the codegen multi-backend matrix test can be
declared fully unblocked:

- **Secondary intrinsics** (`publish` / `subscribePublish` /
  `unsubscribePublish`, `clusterAtomic*`) — interpreter-only.  Not
  required for Bully / Raft / coordinator parity but block any
  `.ssc` app that uses cluster-wide pub/sub or atomic counters
  from cross-compiling to JVM or JS.
- **`setClusterAuthToken` term-level lowering** — `Actor` facade
  exists on JS Node; term-level bare-name lowering still missing on
  both JS and JVM, and JVM lacks even the facade.  Same shape gap
  exists for `setQuorumSize` on JS.
- **JS-codegen `_runActors` scheduler blocks the Node event loop.**
  Uncovered while writing the Tier 4 multi-backend matrix test
  (`cli/src/test/scala/scalascript/cli/ClusterMultiBackendMatrixTest.scala`,
  currently `ignore(...)`).  The JS scheduler is a synchronous
  `while (true)` loop that calls `_asyncSleep(ms)` → `Atomics.wait`
  on the main thread to wait for the next deadline.  In Node ≥ 16
  `Atomics.wait` on the main thread is allowed, but it blocks the
  event loop entirely — incoming HTTP requests queued by libuv accept
  never get dispatched while any actor is blocked on a long-armed
  `receive`.  Every real cluster node has such an actor (at minimum
  to keep the node alive past the election window), so JS-codegen
  cluster nodes' `/_ssc-cluster/*` HTTP endpoints are permanently
  unreachable in the runActors-driven shape the matrix test needs.
  Bare `runActors { startNode(...) }` followed by `serveAsync(port)`
  OUTSIDE `runActors` works (see [`NodeBackendTest`](../backend-node/src/test/scala/scalascript/codegen/NodeBackendTest.scala)
  "integration: emitted bundle binds /_ssc-cluster/status"), but
  that pattern can't drive cluster convergence — the scheduler exits
  as soon as the body returns and no peer connection survives.
  Fix belongs in a separate PR: replace the synchronous scheduler
  loop with a `setImmediate`-driven tick (or wrap `_asyncSleep` in a
  Promise that resolves via `setTimeout`) so Node's event loop gets
  to drain its I/O queue between scheduler ticks.  Tier 4 multi-
  backend deliverable remains blocked until that lands.

The harness work (real-WS multi-process integration test, see
`cluster-raft.md` §9) is now the active workstream.  The codegen-
side intrinsics are in place; the JS-codegen scheduler block above
is the remaining hard prerequisite before the Tier 4 matrix test
can be enabled.
