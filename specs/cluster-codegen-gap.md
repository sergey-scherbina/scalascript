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
- **JS-codegen `_runActors` scheduler block — FIXED (2026-06-11 re-diagnosis).**
  The synchronous `while (true)` + `Atomics.wait` scheduler that blocked Node's
  event loop is gone: `_runActors` is now `async`, sleeps via `await setTimeout`,
  and yields via `setImmediate` between ticks (`JsRuntimeAsyncA.scala`
  `async function _runActors`, `JsRuntimeAsyncB.scala` `_yieldToIO`). A
  JS-codegen cluster node's `/_ssc-cluster/*` HTTP routes now stay reachable
  while actors block on long-armed `receive`s.
- **JVM-codegen WS subprotocol echo — FIXED (2026-06-11, `481190610`).** The
  JVM-codegen `/_ssc-actors` route now registers with
  `protocols = List("ssc-actors-v1")` (both codegen peer clients offer only v1),
  so its WS upgrade echoes `Sec-WebSocket-Protocol` and the JS `ws` client no
  longer rejects. The no-op `onWebSocket` stub was widened to mirror the real
  signature (else non-cluster JVM programs failed to compile). Verified: the
  matrix test's WS now connects and the JVM node reaches + elects the JS peer
  (`leader=node-bbb`); full `backendInterpreter/test` 1612 green.
- **JS-codegen `/_ssc-cluster/status` empty during election — the active blocker.**
  With the WS connected, the JVM node converges to see the JS peer, but polling the
  JS node's status endpoint during the election returns an empty body (it works in
  isolation per `NodeBackendTest`), so the matrix test's "both report the same
  non-empty leader" gate fails (`jvm=leader:node-bbb`, `js=` empty). A JS-codegen
  clustering-under-load issue — serving HTTP status while the async actor scheduler
  drives the election. **Verify/re-enable recipe:** flip the matrix test
  (`tools/cli/.../ClusterMultiBackendMatrixTest.scala`) `ignore(...)`→`test(...)`
  and run with `-Dssc.lib.path=<root>` (after `sbt installBin` stages the compiler
  jars) so the test's `compile-jvm` subprocess resolves them; `require('ws')` is
  worked around via `npm install ws`.

The harness (real-WS multi-process integration test, see `cluster-raft.md` §9) is in
place and the subprotocol blocker is cleared; the JS status-during-election gap above
is the remaining prerequisite before the Tier 4 matrix test can be enabled.
