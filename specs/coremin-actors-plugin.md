# Core-Min Actors Plugin

## Overview

Extract the interpreter `runActors { ... }` scheduler and Actor effect operation handler from interpreter core into a bundled `runtime/std/actors-plugin`, without changing the `.ssc` actor surface. Actors are the largest remaining core effect because `runActors` owns a cooperative scheduler, blocked receive continuations, timers, distributed-node event queues, and cluster state; it is not a simple one-shot `BlockForm.reply(op,args)` effect like Logger/State/Http.

This spec covers the interpreter extraction seam only. JVM/JS codegen actor runtimes keep their current emitted-runtime implementations.

## Interface

The user-facing ScalaScript surface stays unchanged:

```scala
runActors { body }
spawn { () => body }
spawnBounded(capacity, overflow) { () => body }
spawn_link { () => body }
self()
pid ! msg
receive { case ... }
receive(timeout = n) { case ... }
exit(pid, reason)
link(pid)
monitor(pid)
demonitor(ref)
trapExit(on)
startNode(nodeId, url)
connectNode(url, token)
register(name, pid)
whereis(name)
joinCluster(seeds, token)
globalRegister(name, pid)
globalWhereis(name)
```

The implementation adds an interpreter-local extension seam, not a host-neutral backend SPI:

```scala
package scalascript.interpreter

trait ActorRuntimeProvider:
  def open(host: ActorRuntimeHost): ActorRuntimeSession

trait ActorRuntimeSession:
  def runActors(body: Computation): Computation

trait ActorRuntimeHost:
  def out: java.io.PrintStream
  def actorCallValue(fn: Value, args: List[Value], env: Env): Computation
  def actorCallValue1(fn: Value, arg: Value, env: Env): Computation
  def actorReceiveSpec(specId: Long): (List[scala.meta.Case], Env)
  def actorMatchReceive(cases: List[scala.meta.Case], env: Env, msg: Value): Option[Computation]
  def actorNativeFeatureGet(key: String): Option[Any]
  def actorNativeFeatureSet(key: String, value: Any): Unit
  def actorNativeFeatureRemove(key: String): Option[Any]
  def actorOpenWsClient(url: String, headers: Map[String, String], protocols: List[String]): InterpreterWsClientSession
  def actorRegisterWsRoute(path: String, handler: Value, protocols: List[String]): Unit
  def actorRegisterClusterRoutes(): Unit
  def runCoreActorRuntime(initial: Computation): Computation
```

Exact method names may change during implementation, but the boundary must preserve the direction: the provider opens a session bound to one interpreter host; that session owns actor runtime state and scheduling. The provider object itself may be a ServiceLoader singleton and must not hold actor/cluster mutable state. The host exposes only the interpreter services needed to run ScalaScript closures, match `receive` cases, access the output sink, and reuse existing feature-state stores.

`EvalRuntime` keeps only a thin `runActors` dispatch:

1. ensure bundled plugins are loaded if the actor provider is not installed yet,
2. call the installed `ActorRuntimeProvider`,
3. fail loudly with `runActors requires the actors plugin` if absent.

## Behavior

- [ ] With the bundled actors plugin available, existing interpreter actor tests pass unchanged: local send/receive, bounded mailboxes, `stop()` behavior, supervision, groups, distributed actors, cluster visibility, leader election, config, metrics, raft, and persistence tests.
- [ ] Without the actors plugin, `runActors { ... }` fails with a clear missing-plugin diagnostic; it must not silently ignore actor effects or fall back to stale core behavior.
- [ ] `receive { case ... }` and `receive(timeout = n) { case ... }` preserve current pattern semantics, timeout wrapping (`Some`/`None`), and environment capture.
- [ ] Remote WebSocket, cluster event, leader event, config event, drain event, metric event, publish queue, scheduled-send, and node-down drains remain driven by the actor scheduler.
- [x] `ActorRuntimeProvider.open(host)` creates an interpreter-bound `ActorRuntimeSession`; installing a provider resets the cached session so mutable actor/cluster state cannot leak through a reused ServiceLoader backend singleton.
- [x] `ActorRuntimeHost` exposes an explicit interpreter-service API for the moved runtime (`out`, closure calls,
  receive-spec lookup/matching, and native feature state) instead of requiring a broad `Interpreter` self-type in
  the plugin runtime.
- [ ] `ActorRuntimeHost` exposes distributed server hooks for the moved runtime (`openWsClient`, `_ssc-actors`
  WebSocket route registration, and cluster-control HTTP route registration) without requiring the plugin runtime
  to reach into `InterpreterServerSupport`, `wsRoutes`, or `ClusterRoutesRuntime` directly.
- [ ] No actor scheduler/mailbox/cluster state remains in interpreter core except the minimal host bridge and dispatch stub.
- [ ] Existing `.ssc` examples and conformance files under `tests/conformance/actors-*.ssc` require no source changes.

## Out of Scope

- Moving JVM/JS emitted actor runtimes into plugins.
- Redesigning actor semantics, supervision, cluster protocols, wire formats, or public actor APIs.
- Moving the `receive` syntax recognizer out of core. It is an AST special form because it captures ScalaMeta `Case` trees and lexical `Env`; this extraction only moves the runtime scheduler.
- Solving `core-min-value-unification`. The first actor plugin may still use `scalascript.interpreter.Value`; value unification is a separate SPRINT item.
- Extracting Stream; `coremin-stream-migrate` is deliberately deferred.

## Design

### Current Shape

`runtime/backend/interpreter/src/main/scala/scalascript/interpreter/ActorInterp.scala` currently contains:

- `ActorRuntime`: mailboxes, blocked receives, pending computations, ready queue, supervision state, timers, bounded-mailbox state.
- Distributed/cluster node state: peer channels, remote inbox, node-down queue, cluster/leader/config/drain/metric/publish event queues, raft/coordinator state, persistence helpers.
- `actorInterp(initial: Computation)`: installs a fresh `ActorRuntime`, drives the cooperative loop, drains remote and timer/event queues, and returns the root actor result.
- `stepActor` and `handleActorOp`: interpret `Perform("Actor", op, args)` nodes.
- Cluster wire helpers and peer connection handling.

`EvalRuntime` currently hardcodes `runActors { body }` to `interp.actorInterp(eval(body, env, interp))`.
`ActorGlobals` currently installs globals that create `Perform("Actor", op, args)` nodes.
`receive` remains a core special form: it stores `(cases, env)` in `interp.receiveSpecs` and performs `Actor.receive` with an integer token.

### Target Shape

Create `runtime/std/actors-plugin/` with an interpreter-only provider:

- `ActorsInterpreterPlugin extends Backend` contributes actor `preludeSymbols` for `runActors` and actor primitive names that remain expected by `ssc check`.
- `ActorsRuntimeProvider` owns the code moved out of `ActorInterp.scala`.
- A ServiceLoader-visible registration makes the provider visible to the interpreter when bundled plugins load.

Interpreter core keeps:

- `receiveSpecs` storage and `receive` special-form lowering.
- `ActorRuntimeHost` implementation, forwarding only required interpreter services.
- A tiny `runActors` dispatch stub in `EvalRuntime`.
- Existing non-actor `Perform` trampoline mechanics.

### Migration Slices

1. **Spec slice** — this file. No code changes.
2. **Provider seam slice** — add `ActorRuntimeProvider`/`ActorRuntimeHost`, provider registration, and a no-op/mirror provider that delegates to the existing core implementation. Tests must remain green.
3. **Session lifecycle seam slice** — change the provider contract to `open(host): ActorRuntimeSession`, cache one session per interpreter host, and reset it when a provider is installed. This makes state ownership explicit before any runtime move.
4. **Host-service seam slice** — enumerate the current runtime's direct `Interpreter` dependencies and expose the
   minimal typed methods on `ActorRuntimeHost`. Keep `runCoreActorRuntime` only as the temporary core delegate.
5. **Server/WS seam slice** — expose the distributed hooks the moved runtime needs for outbound WebSocket clients,
   `_ssc-actors` route registration, and cluster-control HTTP routes.
6. **Move runtime slice** — move `ActorRuntime`, scheduler loop, actor op handling, and cluster helpers into `runtime/std/actors-plugin`; core dispatches through the session.
7. **Prelude/check slice** — move actor runner/primitive names that are safe to type as `Any` into plugin `preludeSymbols`; keep syntax-only `receive` handling in core.
8. **Cleanup slice** — remove stale actor scheduler state from interpreter core and update `SPRINT.md`/`CHANGELOG.md`.

## Decisions

- **Interpreter-local seam, not generic `Backend.blockForms`.** Chosen because actors own a cooperative scheduler and must step `Computation` trees, manage blocked continuations, and drain external event queues. Rejected: widening host-neutral `BlockForm` to expose `Computation`, because that would re-couple ordinary plugins to interpreter internals and undo the `SpiValue` boundary.
- **Keep `receive` syntax in core.** Chosen because `receive` captures ScalaMeta case trees plus lexical `Env`; the plugin should consume an opaque receive-spec token, not parse syntax. Rejected: moving `receive` into the plugin, because plugins do not own the parser/evaluator AST dispatch.
- **Move all interpreter actor runtime state together.** Chosen because local actors, supervision, distributed delivery, cluster visibility, raft/coordinator, and event queues share the same scheduler thread and mailbox wakeup path. Rejected: moving only `runActors` local scheduling while leaving cluster state in core, because that would leave the main entanglement in place.
- **Keep actor/cluster mutable state per interpreter, not on the ServiceLoader backend singleton.** `BackendRegistry.inProcess`
  may reuse provider objects across interpreter sessions, while today's `ActorInterp` fields are per `Interpreter`
  instance. The move slice must allocate `ActorRuntime` per `runActors` and store node/cluster state in a per-host
  holder keyed by the `ActorRuntimeHost` / `Interpreter`, or otherwise bind provider state to the interpreter lifetime.
  Rejected: putting `peerChannels`, `remoteInbox`, raft/coordinator state, or registries directly on the backend object,
  because that would leak state across tests, servers, and embedded interpreters.
- **Use a per-host session, not a stateful provider method.** Chosen because `ActorRuntimeProvider` instances are
  supplied by backend plugins and may be reused by ServiceLoader; `open(host): ActorRuntimeSession` gives future
  moved code a natural per-interpreter holder. Rejected: leaving `runActors(host, body)` on the provider as the only
  hook, because that API makes it too easy for a plugin author to store cluster/node state on the provider singleton.
- **No public API change.** Chosen because this is a core-minimization refactor. Rejected: using this slice to redesign ActorRef/Pid or cluster protocols.
- **First move attempt is a host-service seam, not a blind file move.** A 2026-06-23 dependency audit found the runtime
  still reaches interpreter services directly: `out`, `callValue`/`callValue1`, `receiveSpecs`, `eval` through receive
  guards/bodies, `nativeFeature*`, and distributed server/WS integration. Moving the file before naming those seams
  would either keep a broad `Interpreter` dependency in the plugin or fail at the distributed actor path. Chosen first
  slice: make the non-server services explicit on `ActorRuntimeHost`, then move scheduler code behind that host API in
  later slices.

## Results

- 2026-06-22 — spec slice landed as `6538c10c6 spec: coremin-actors-plugin`.
- 2026-06-22 — provider seam slice landed as `ea898ca82 feat(interpreter): add actor runtime provider seam`.
  `ActorRuntimeProvider` / `ActorRuntimeHost` now exist in interpreter core, and `ActorInterp.actorInterp`
  dispatches through `CoreActorRuntimeProvider`, which delegates to the existing core scheduler. No actor
  runtime code has moved yet.
- 2026-06-22 — bundled plugin skeleton landed as `539105e3c feat(actors): add bundled actors provider plugin`.
  `runtime/std/actors-plugin` now builds as an essential `.sscpkg`, registers through ServiceLoader, contributes
  actor `preludeSymbols`, and installs the current provider through `ActorRuntimeProviderBackend`.
- 2026-06-23 — session lifecycle seam landed. `ActorRuntimeProvider` now opens an `ActorRuntimeSession`
  bound to one `ActorRuntimeHost`; `ActorInterp` lazily caches that session per `Interpreter` and clears it
  when a replacement provider is installed. The bundled actors plugin still delegates to the core scheduler,
  so no actor runtime code moved in this slice.
- 2026-06-23 — host-service seam landed. `ActorRuntimeHost` now exposes the non-server interpreter services
  the moved runtime needs: `out`, closure calls, receive-spec lookup/matching, and native feature state. The
  current core delegate remains in place through `runCoreActorRuntime`, so this is a behavior-preserving
  preparatory slice before moving the scheduler/cluster runtime itself.
- Verification for the host-service seam:
  - `cd /Users/sergiy/work/my/scalascript-wt-coremin-actors-codemove && sbt "actorsPlugin/compile" "backendInterpreter/compile" "backendInterpreterPluginTests/testOnly scalascript.ActorsPluginProviderTest"`
    passed: provider plugin test 4/0, including a custom provider that uses the explicit host services without
    a direct `Interpreter` self-type.
  - `cd /Users/sergiy/work/my/scalascript-wt-coremin-actors-codemove && sbt "backendInterpreterPluginTests/testOnly scalascript.ActorsPluginProviderTest" "backendInterpreter/testOnly scalascript.ActorSupervisionTest scalascript.ActorStopOutsideTest scalascript.ActorGroupTest scalascript.ActorDistributedTest scalascript.ActorBinaryWsTest"`
    passed after rebase: provider 4/0 and actor targeted suites 53/0. ScalaTest printed the known reporter
    `InterruptedException`, but sbt completed with `[success]` and all tests passed.
- Verification for the session lifecycle seam:
  - `cd /Users/sergiy/work/my/scalascript-wt-core-min-phase3plus && sbt "actorsPlugin/compile" "backendInterpreter/compile" "backendInterpreterPluginTests/testOnly scalascript.ActorsPluginProviderTest"`
    passed: provider plugin test 3/0, including the session cache/reset regression.
  - `cd /Users/sergiy/work/my/scalascript-wt-core-min-phase3plus && sbt "backendInterpreter/testOnly scalascript.ActorSupervisionTest scalascript.ActorStopOutsideTest scalascript.ActorGroupTest scalascript.ActorDistributedTest scalascript.ActorBinaryWsTest"`
    passed: actor targeted suites 53/0. ScalaTest printed the known reporter `InterruptedException`, but sbt
    completed with `[success]` and all tests passed.
- Verification for the seam slice:
  - `cd /Users/sergiy/work/my/scalascript-wt-coremin-actors-migrate && sbt "backendInterpreter/compile"`
    passed.
  - `cd /Users/sergiy/work/my/scalascript-wt-coremin-actors-migrate && sbt "backendInterpreter/testOnly scalascript.ActorSupervisionTest scalascript.ActorStopOutsideTest scalascript.ActorGroupTest scalascript.ActorDistributedTest"`
    passed: 29 tests, 0 failed/canceled. ScalaTest printed a reporter `InterruptedException`, but sbt
    completed with `[success]` and all tests passed.
  - `cd /Users/sergiy/work/my/scalascript-wt-coremin-actors-migrate && sbt "actorsPlugin/compile" "backendInterpreter/compile" "backendInterpreterPluginTests/testOnly scalascript.ActorsPluginProviderTest"`
    passed: provider plugin test 2/0.
  - `cd /Users/sergiy/work/my/scalascript-wt-coremin-actors-migrate && sbt "cli/installBin"` passed and
    staged 26 essential `.sscpkg` files plus 13 advanced `.sscpkg` files.
- Remaining optional slice: move the runtime implementation behind the provider without changing `.ssc` sources.
