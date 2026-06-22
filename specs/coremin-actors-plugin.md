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
  def runActors(host: ActorRuntimeHost, body: Computation): Computation

trait ActorRuntimeHost:
  def out: java.io.PrintStream
  def callValue(fn: Value, args: List[Value], env: Env): Computation
  def callValue1(fn: Value, arg: Value, env: Env): Computation
  def receiveSpecs: scala.collection.mutable.LongMap[(List[scala.meta.Case], Env)]
  def nextReceiveSpecId(): Long
  def matchReceive(cases: List[scala.meta.Case], env: Env, msg: Value): Option[Computation]
  def nativeFeatureGet(key: String): Option[Any]
  def nativeFeatureSet(key: String, value: Any): Unit
  def nativeFeatureRemove(key: String): Option[Any]
```

Exact method names may change during implementation, but the boundary must preserve the direction: the provider owns actor runtime state and scheduling; the host exposes only the interpreter services needed to run ScalaScript closures, match `receive` cases, access the output sink, and reuse existing feature-state stores.

`EvalRuntime` keeps only a thin `runActors` dispatch:

1. ensure bundled plugins are loaded if the actor provider is not installed yet,
2. call the installed `ActorRuntimeProvider`,
3. fail loudly with `runActors requires the actors plugin` if absent.

## Behavior

- [ ] With the bundled actors plugin available, existing interpreter actor tests pass unchanged: local send/receive, bounded mailboxes, `stop()` behavior, supervision, groups, distributed actors, cluster visibility, leader election, config, metrics, raft, and persistence tests.
- [ ] Without the actors plugin, `runActors { ... }` fails with a clear missing-plugin diagnostic; it must not silently ignore actor effects or fall back to stale core behavior.
- [ ] `receive { case ... }` and `receive(timeout = n) { case ... }` preserve current pattern semantics, timeout wrapping (`Some`/`None`), and environment capture.
- [ ] Remote WebSocket, cluster event, leader event, config event, drain event, metric event, publish queue, scheduled-send, and node-down drains remain driven by the actor scheduler.
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
3. **Move runtime slice** — move `ActorRuntime`, scheduler loop, actor op handling, and cluster helpers into `runtime/std/actors-plugin`; core dispatches through the provider.
4. **Prelude/check slice** — move actor runner/primitive names that are safe to type as `Any` into plugin `preludeSymbols`; keep syntax-only `receive` handling in core.
5. **Cleanup slice** — remove stale actor scheduler state from interpreter core and update `SPRINT.md`/`CHANGELOG.md`.

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
- **No public API change.** Chosen because this is a core-minimization refactor. Rejected: using this slice to redesign ActorRef/Pid or cluster protocols.

## Results

- 2026-06-22 — spec slice landed as `6538c10c6 spec: coremin-actors-plugin`.
- 2026-06-22 — provider seam slice landed as `ea898ca82 feat(interpreter): add actor runtime provider seam`.
  `ActorRuntimeProvider` / `ActorRuntimeHost` now exist in interpreter core, and `ActorInterp.actorInterp`
  dispatches through `CoreActorRuntimeProvider`, which delegates to the existing core scheduler. No actor
  runtime code has moved yet.
- 2026-06-22 — bundled plugin skeleton landed as `539105e3c feat(actors): add bundled actors provider plugin`.
  `runtime/std/actors-plugin` now builds as an essential `.sscpkg`, registers through ServiceLoader, contributes
  actor `preludeSymbols`, and installs the current provider through `ActorRuntimeProviderBackend`.
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
- Next slice: move the runtime implementation behind the provider without changing `.ssc` sources.
