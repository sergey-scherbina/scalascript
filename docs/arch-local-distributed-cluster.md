# Architecture: Local ↔ Distributed API Unification + Cluster Lifecycle

## 1. Goals

1. Give ScalaScript users a **single coherent set of extension methods** (`Source[A].distributed`, `DStream[A].local`, typed `ActorRef[M]`, `spawnRemote`, future `@remote def`) that lift local primitives into distributed ones and back — without rewriting producer/consumer logic.
2. Define how a ScalaScript **cluster is created, configured, and deployed** to real environments (Kubernetes, Docker Compose, Nomad, ECS, bare-metal SSH) from a first-class `cluster { … }` block in `.ssc` source — rather than the current manual `startNode` + `connectNode` + hand-written K8s YAML.
3. Record the **strategic async decision**: the universal async target is `! Async` (algebraic effect row), not `scala.concurrent.Future`. `Future[A]` becomes syntactic sugar for `() => A ! Async` once the foundation lands.

Cross-references: `docs/streams.md`, `docs/distributed-streams.md`, `docs/actors-dist.md`, `docs/cluster-management.md`, `docs/cluster-raft.md`, `docs/cluster-federation.md`, `docs/deploy.md`, `docs/cluster-codegen-gap.md`, `docs/distributed-wire-protocol.md`, `docs/typed-route-clients.md`.

## 2. Non-Goals

- Hiding the inherent cost difference between local and distributed execution. `DStream[A].local(using Cluster)` is explicit about materialising to the driver; users cannot accidentally OOM.
- Making all stream operators available on both surfaces. Windowing/watermarks/keyed-state are distributed-only; throttle/debounce/buffer/broadcast/balance are local-only. This divergence is **correct** and will not be collapsed.
- Hiding the latency/failure asymmetry of remote actors (remote crash is heartbeat-detected, up to 40 s lag; local crash is immediate). The typed wrapper surface will document this prominently.
- Automatic function serialisation. `foo.remote(addr)` routes through a registered HTTP route — it cannot serialise a closure. Functions must be `@remote`-annotated at definition site.
- A full Raft log replication engine. The existing v1.23 Raft covers leader-election only (no log replication). Phase K.5 wires Raft leader state to the `StateBackend` SPI, not a new log engine.
- ZooKeeper coordinator (explicitly deferred in `docs/cluster-management.md:344-359`).
- Saga / distributed transactions (deferred Tier 3 per `docs/cluster-management.md:368-373`).

## 3. Current State (verified against the codebase)

### 3.1 Streams

| Item | Location | Status |
|---|---|---|
| `Source[A]` (alias `Stream[A]`) | `runtime/std/streams.ssc:25` | Implemented |
| `DStream[T]` / `Pipeline` | `runtime/std/dstreams.ssc:36` | Implemented |
| `DSource.fromLocalSource[A](src: Source[A]): DStream[A]` | `runtime/std/dstreams-plugin/.../DStreamsIntrinsics.scala:80-93` (`_dag_source_local`) | Implemented |
| `DStream[A].toLocalSource` | `docs/distributed-streams.md §11.1` | Specced, NOT implemented |
| Shared operators (map/filter/merge/run*) | Both types | Signature-aligned but no shared trait |

Operators that are correctly divergent:

| Local-only | Distributed-only |
|---|---|
| `throttle`, `debounce`, `buffer`, `broadcast(n)`, `balance(n)`, `concat`, `zip`, `zipWith` | `window`, `withTrigger`, `withAllowedLateness`, `withAccumulationMode`, `statefulMap`, `timer`, `broadcastState`, `sideInput/Output`, `leftOuterJoin`, `rightOuterJoin` |

`flatMap` signature diverges: local `A => Source[B]`, distributed `A => Iterable[B]`. The shared trait (`BasicStreamOps`) omits it.

### 3.2 Actors

| Item | Location | Status |
|---|---|---|
| `Pid` with `nodeId` field (`""` = local, non-empty = remote) | `runtime/backend/interpreter/.../ActorInterp.scala:67` | Implemented — `pid ! msg` dispatches via `peerChannels.get(nodeId)` or local mailbox |
| `startNode / connectNode / register / whereis` | `runtime/std/nodes.ssc:42-46` | Implemented |
| `joinCluster(seeds, token)` | `runtime/std/cluster/membership.ssc:48` | Implemented |
| Cluster membership, phi-accrual, leader election | `runtime/std/actors.ssc:155-547`, `runtime/std/cluster/` | Implemented (v1.23) |
| `setClusterAuthToken(token)` env `SSC_CLUSTER_TOKEN` | `runtime/std/actors.ssc:315` | Implemented (JVM codegen gap: term-lowering missing, see `docs/cluster-codegen-gap.md:116-120`) |
| Typed Scala wrapper `ActorRef[M]` | `lang/interop/src/main/scala/scalascript/interop/runtime/Actors.scala:22` | v0.1 stub — `spawn` throws `NotImplementedError`, no remote support |
| `spawnRemote` / `cluster_spawn` wire message | `docs/distributed-wire-protocol.md` | NOT implemented |
| Operational routes | Auto-registered: `/_ssc-cluster/{status,drain,events,step-down,metrics-prom}` | Implemented (v1.23) |

### 3.3 Async Calls

| Item | Location | Status |
|---|---|---|
| `! Async` algebraic effect | `runtime/backend/interpreter/.../BuiltinsRuntime.scala:530-541`, `AsyncRuntime.{asyncInterp,asyncParInterp}` | Implemented |
| `Future[A]` as user-land monad (map/flatMap/etc.) | — | NOT implemented — `Future` is `Value.InstanceV("Future", ...)` opaque cell only |
| `BackendTransport` SPI | `runtime/backend/spi/.../BackendTransport.scala` | Implemented |
| Typed route clients (REST/JSON stubs) | `docs/typed-route-clients.md`, `JvmGen.scala:1788-1953` (string-templated) | Partial |
| `WireCodec[A]` | `docs/distributed-wire-protocol.md`, WORK_QUEUE v1.62.1 | Planned, NOT implemented |
| `@remote def` / `remoteStub` | — | NOT implemented |

`RemoteSigningClient` (`payments/wallet/vault-mpc/.../RemoteSigningClient.scala:31-58`) is the closest pattern: a `trait` with `Future[A]`-returning methods, implemented as `HttpRemoteSigningClient` with `decorateRequest` auth hook. This is a **constructor-injection SPI**, not a `.local`/`.remote` extension — callers receive the impl via DI. The K.3 `remoteStub[Api](addr)` pattern will feel similar but be inline-derived rather than hand-written.

`JsonCodec.derived` (`backend/typed-data/.../JsonCodec.scala:7-12`) demonstrates Scala 3 `inline given derived[A](using Mirror.Of[A])` — the same pattern will be used for `Stub[F]` generation in K.3.

### 3.4 Cluster Create / Deploy

| Item | Location | Status |
|---|---|---|
| `startNode(nodeId) / joinCluster(seeds, token)` | `runtime/std/nodes.ssc`, `runtime/std/cluster/membership.ssc:48` | Manual seed list only |
| DNS-SRV seed discovery | — | NOT implemented |
| K8s headless-Service seed discovery | — | NOT implemented |
| `K8sTarget` (replicas, Deployment+Service+Ingress) | `runtime/std/deploy-plugin/.../K8sTarget.scala:197` | Implemented — NOT cluster-aware (no StatefulSet, no peer-URL injection, no `SSC_CLUSTER_TOKEN` Secret) |
| `DeployTarget` SPI | `runtime/std/deploy-plugin/.../DeployTarget.scala:63-73` | Implemented |
| `DeployGroup`, `DeployEnvironment`, blue-green, StateBackend | `runtime/std/deploy-plugin/` (26 Scala files) | Implemented (v1.52) |
| `cluster { … }` block in `.ssc` | — | NOT implemented |
| Cluster-aware k8s deploy (StatefulSet + headless Service) | — | NOT implemented |
| Rolling cluster upgrade orchestrator | — | NOT implemented (drain protocol in v1.23 is the foundation) |
| Cluster auth token rotation | — | NOT implemented |
| Persistent cluster state via StateBackend | — | Only Raft per-node vote file persists (`<workDir>/.ssc-raft-state.json`) |
| Multi-AZ / multi-region (implemented) | `DeployEnvironment.scala:38-42` (`FaultToleranceConfig.multiRegion`) | Declared but NOT wired (just parallel deploys) |
| Autoscaling (HPA) | — | NOT implemented |
| Nomad / ECS / Compose targets | — | NOT implemented (`K8s`, `Container`, `FaaS`, `Static`, `Traditional` exist) |

## 4. Design

### 4.1 Streams — Phase K.1

**New surface** (one new file `runtime/std/streams-bridge.ssc`):

```scala
extension [A](src: Source[A])
  // Lift a local stream into a DStream via the existing _dag_source_local intrinsic.
  // Equivalent to DSource.fromLocalSource(src) but ergonomic.
  def distributed: DStream[A] = DSource.fromLocalSource(src)

extension [A](ds: DStream[A])
  // Materialise the distributed stream on the driver node.
  // Honest about cost: runs the DAG through DirectRunner, buffers all elements to a
  // local queue, then returns a Source backed by that queue.
  // OOM risk if the stream is large or unbounded — see localBounded for safety.
  def local(using Cluster): Source[A] = ...

  // Bounded variant — fails with StreamTooLargeError if collected bytes exceed limit.
  def localBounded(maxBytes: Long = 256 * 1024 * 1024L)(using Cluster): Source[A] = ...
```

**Shared trait** (added to `runtime/std/streams.ssc`):

```scala
// Operators with identical signatures across Source[A] and DStream[A].
// Enables generic code like: def pipeline[F[_]: BasicStreamOps](s: F[Int]) = s.map(_*2)
trait BasicStreamOps[F[_]]:
  extension [A](fa: F[A])
    def map[B](f: A => B): F[B]
    def filter(p: A => Boolean): F[A]
    def merge(other: F[A]): F[A]
    def runForeach(f: A => Unit): Unit
    def runFold[B](z: B)(f: (B, A) => B): B
    def runToList: List[A]

given BasicStreamOps[Source]    = ...
given BasicStreamOps[DStream]   = ...
```

`flatMap` is deliberately excluded (divergent signatures). All throttle/debounce/buffer/broadcast/balance/concat/zip stay on `Source` only. All window/state/trigger/sideInput stay on `DStream` only.

**Implementation notes**:
- `DStream.local` is backed by a new `_dag_sink_local` DAG node in `DStreamsIntrinsics.scala` that runs the pipeline through `DirectRunner`, collects to a `java.util.concurrent.LinkedBlockingQueue[Value]`, and returns a `Source` that drains it.
- `Cluster` context is the capability guard — code must have called `startNode` before `DStream.local` is valid. If `selfNode() == ""`, fail early with a clear error.
- `localBounded` counts serialised bytes via `WireCodec` once it lands; until K.7 it counts `Value.estimatedSize()`.

### 4.2 Actors — Phase K.2

**Typed wrapper completion** (`lang/interop/src/main/scala/scalascript/interop/runtime/Actors.scala`):

```scala
// Phase K.2 — wires the phantom-typed wrapper to the real Pid dispatcher.
// Location-transparency is already at the Pid level; this surface just exposes it.

opaque type ActorRef[+M] = Pid

object ActorRef:
  def apply[M](pid: Pid): ActorRef[M] = pid
  extension [M](ref: ActorRef[M])
    def !(msg: M): Unit                        = ref.asInstanceOf[Pid] ! msg
    def address: Option[NodeId]                = Option(ref.nodeId).filter(_.nonEmpty)
    def isLocal: Boolean                       = ref.nodeId.isEmpty
    def tryLocal: Option[LocalActorRef[M]]     = if isLocal then Some(LocalActorRef(ref)) else None
    def publish(name: String): ActorRef[M]     = { globalRegister(name, ref); ref }
    def at(node: NodeId): ActorRef[M]          = ActorRef(Pid(node, ref.localId))

opaque type LocalActorRef[+M] <: ActorRef[M] = Pid  // only constructable when nodeId == ""

opaque type NodeId = String
```

**New top-level function**:

```scala
// Spawn an actor on a remote node using the cluster_spawn wire message.
// Requires the remote node to have called startNode and be reachable.
// Returns a remote ActorRef — do NOT expect tryLocal to succeed.
def spawnRemote[M](node: NodeId)(behavior: Behavior[M]): ActorRef[M]
```

**Wire message extension** (`docs/distributed-wire-protocol.md` gets a new `$t: "cluster_spawn"` entry):

```json
{ "$t": "cluster_spawn",
  "behavior": "<serialised-behavior-descriptor>",
  "replyTo": "<pid-json>" }
```

The remote node receives the message, spawns a local actor running `behavior`, and sends back `{ "$t": "cluster_spawn_ack", "pid": "<new-pid>" }`. The `behavior` descriptor is a registered name — behaviours must be registered on both sides (no arbitrary closure serialisation).

**Failure-model asymmetry documented in Scaladoc** (not hidden):
```
 * IMPORTANT: Remote actor failure is detected by heartbeat timeout, which takes
 * up to 40 seconds by default (configurable via setHeartbeatTimeout).
 * Local actor failure raises Exit/Down immediately on the calling scheduler tick.
 * Monitor the remote ref if latency matters.
```

**Codegen gap fix**: `setClusterAuthToken` term-lowering on JVM (`docs/cluster-codegen-gap.md:116-120`) is fixed as part of Phase K.2 since the typed wrapper uses it.

### 4.3 Cluster Create — Phase K.3

#### 4.3.1 `Cluster` capability

Make `Cluster` a first-class capability rather than implicit global state. Introduce a new SPI file `runtime/backend/spi/src/main/scala/scalascript/backend/spi/Cluster.scala`:

```scala
// Cluster is a runtime handle; obtained via clusterOf() after startNode/joinCluster.
// Used as a context parameter by DStream.local, spawnRemote, remoteStub, etc.
trait Cluster:
  def localNodeId: String
  def peers: List[NodeId]
  def authToken: Option[String]
  def seedResolver: SeedResolver
  // WireCodec registry (used by DStream.local and K.3 async stubs once WireCodec lands)
  def wireCodecFor[A]: Option[WireCodec[A]]
```

`clusterOf()` returns the active `Cluster` — fails fast with a clear error if `startNode` was not called.

#### 4.3.2 `SeedResolver` SPI

```scala
// Pluggable seed-node discovery strategy.
trait SeedResolver:
  def resolve(): List[String]   // returns ws:// or wss:// peer URLs

object SeedResolver:
  def staticList(urls: List[String]): SeedResolver
  def dnsSrv(serviceName: String, port: Int = 9100): SeedResolver
  def k8sHeadlessService(serviceName: String, namespace: String = "default",
                          port: Int = 9100, scheme: String = "ws"): SeedResolver
  def consulCatalog(serviceName: String, consulAddr: String = "localhost:8500"): SeedResolver
```

`k8sHeadlessService` resolves via the K8s DNS convention `<svc>.<ns>.svc.cluster.local` (all pod IPs via A records). `dnsSrv` issues a DNS SRV query. `consulCatalog` calls Consul's `/v1/health/service/<name>?passing` API.

#### 4.3.3 `cluster { … }` block syntax

First-class topology declaration in `.ssc`:

```ssc
cluster Demo:
  nodes = 3
  seedDiscovery = SeedResolver.k8sHeadlessService("ssc-demo")
  leaderElection = Raft
  authTokenFrom  = K8sSecret("ssc-cluster-token", key = "token")
  heartbeat(intervalMs = 5000, deadAfterMs = 40000)
  quorum(2)
```

The block lowers to:
```scala
setClusterAuthToken(readK8sSecret("ssc-cluster-token", "token"))
Cluster.join(SeedResolver.k8sHeadlessService("ssc-demo").resolve(), authToken)
useRaftLeaderElection()
setQuorumSize(2)
setHeartbeatTimeout(5000, 40000)
```

Preprocessor hook: `cluster { … }` is added to `PreprocessorRegistry` (Theme F) once that SPI lands. Until then, it's a dedicated preprocessor in `lang/core/.../parser/Parser.scala` alongside the existing `preprocessExtern/Effects/…` chain.

### 4.4 Cluster Deploy — Phase K.4

#### 4.4.1 `ClusterTarget` trait

Extends `DeployTarget` (`runtime/std/deploy-plugin/.../DeployTarget.scala:63`) with cluster-awareness:

```scala
trait ClusterTarget extends DeployTarget:
  def seedUrlsFor(env: DeployEnvironment): List[String]
  def injectAuthToken(env: DeployEnvironment, secretRef: String): Unit
  def emitWorkloadManifest(mode: WorkloadMode): String  // Deployment | StatefulSet | DaemonSet
  def emitHeadlessService(): String
  def emitHpa(policy: ScalePolicy): Option[String]

enum WorkloadMode: case Deployment, StatefulSet, DaemonSet
enum ScalePolicy:
  case Cpu(targetPercent: Int)
  case Custom(metricName: String, targetValue: Long)
  case None
```

#### 4.4.2 Cluster-aware K8s target

`K8sTarget` (`runtime/std/deploy-plugin/.../K8sTarget.scala`) is extended to:

1. When `cluster { … }` is present in the deployed manifest:
   - Emit `StatefulSet` (not `Deployment`) with stable DNS hostnames.
   - Emit a `Service` with `clusterIP: None` (headless) for `k8sHeadlessService` seed resolution.
   - Emit a `ConfigMap` with peer URL list (derived from `statefulset_name-{0..N-1}.headless_service_name`).
   - Emit a `Secret` for the cluster auth token; inject as `SSC_CLUSTER_TOKEN` env var.
   - Add an `initContainer` that runs `ssc cluster wait-for-peers --count=N --timeout=120s` before the main container starts — ensures orderly join.
2. Blue-green and rolling upgrade: leverage existing `K8sTarget.deployBlueGreen` / `rollout`. New: `Deploy.rollingCluster(env, target, strategy)` — orchestrator that drains one node at a time using `/_ssc-cluster/drain` before pod restart.

#### 4.4.3 Additional targets (Phase K.6)

| Target | New adapter | Key additions |
|---|---|---|
| Docker Compose | `ComposeTarget` | `depends_on: [node1]` ordering; shared `ssc-net` network; env injection |
| Nomad | `NomadTarget` | Nomad Job HCL codegen; Consul service registration for seed discovery |
| AWS ECS | `EcsTarget` | Task Definition + Service codegen; ECS Service Connect for peer discovery |

#### 4.4.4 Multi-AZ / multi-region

Concretise the declared `FaultToleranceConfig.multiRegion` (`DeployEnvironment.scala:38-42`):

```ssc
cluster Demo:
  regions = ["us-east-1", "eu-west-1"]
  crossRegionQuorum = 2                // minimum regions for writes
  failoverStrategy  = ActivePassive    // or ActiveActive
```

Lowers to two `DeployGroup` targets with independent `K8sTarget` instances per region, plus a cross-region `globalRegister` rendezvous point (existing gossip infrastructure). Multi-region traffic routing delegates to the cluster's external load balancer (e.g., Route 53 health checks) — no new ScalaScript LB code.

### 4.5 Phase K.5 — Persistent State + Rolling Upgrade + Token Rotation

**Persistent cluster config via StateBackend**: Wire `clusterConfigSet/Get` to the existing `StateBackend` SPI (`runtime/std/deploy-plugin/.../StateBackend.scala:18-29`). The `EtcdStateBackend` and `ConsulStateBackend` implementations already support read-your-writes; routing cluster config through them gives persistence across restarts without adding a new store.

**Auth token rotation protocol**:
```scala
// On the rotating node (coordinator):
def rotateClusterToken(newToken: String, overlapMs: Long = 30_000): Unit
```
1. Broadcast `{ "$t": "token_rotate", "new": "<hashed-new>", "validFrom": T+overlapMs }` to all peers.
2. Each peer accepts either token during the overlap window.
3. After `validFrom`, drop the old token.
4. Store new token in `StateBackend` (if configured) so restarts pick it up.

**Rolling upgrade**:
```scala
Deploy.rollingCluster(
  env      = Production,
  target   = k8sTarget,
  strategy = Rolling(maxUnavailable = 1, waitDrainMs = 30_000)
)
```
Sequence per node: call `/_ssc-cluster/drain` → wait for `PipelineState.Done` on in-flight work (or timeout) → kubectl rollout restart that pod → wait healthy → repeat next node.

### 4.6 Phase K.7 — Async / Remote-Call Unification (gated)

**Gate**: WORK_QUEUE v1.62.1 (`WireCodec[A]`) must land first.

**`! Async` as universal target**: `Future[A]` becomes `() => A ! Async` (syntactic sugar, desugared by the preprocessor). The `Value("Future", ...)` opaque cell is deprecated in favour of the existing `Computation = Pure | Perform | FlatMap` ADT. `runAsync` / `runAsyncParallel` remain the runners.

**`@remote def` annotation**:
```ssc
@remote("/api/v1/echo")
def echo(s: String): String ! Async = s
```
Codegen: `JvmGen` emits an HTTP route registration at startup. The route handler deserialises `s` using `WireCodec[String]`, calls `echo(s)`, serialises the result back.

**`remoteStub[Api](addr)` inline derivation**:
```scala
trait EchoApi:
  def echo(s: String): String ! Async

val client: EchoApi = remoteStub[EchoApi]("https://node.example")
// Generated stub calls BackendTransport.request(...) for each method
```
Stub generation uses `inline given Stub[F]` derivation via `scala.compiletime.summonInline` + `Mirror.Of[F]` — same pattern as `JsonCodec.derived` (`backend/typed-data/.../JsonCodec.scala:7-12`).

**Error model**: `RemoteCallError` sealed hierarchy — `Timeout(durationMs)`, `DecodeError(msg)`, `AuthError`, `NetworkError(cause)`, `ServerError(statusCode, body)`. Surfaced as `! Async | RemoteCallError` in the return effect row.

## 5. Sequencing

| Phase | Description | Scope | Dependency |
|---|---|---|---|
| K.1 | Streams unification: `Source.distributed`, `DStream.local`, `BasicStreamOps` | ~1–2 weeks | None — standalone |
| K.2 | Actors typed wrapper: `ActorRef[M]`, `spawnRemote`, `cluster_spawn` wire message | ~2–3 weeks | None — standalone |
| K.3 | Cluster create: `Cluster` capability, `SeedResolver` SPI, `cluster { … }` block | ~2–3 weeks | After K.1+K.2 validated the surface |
| K.4 | Cluster-aware k8s deploy: StatefulSet, headless Service, secret injection | ~3 weeks | After K.3 |
| K.5 | Rolling upgrade orchestrator + token rotation + persistent state | ~2–3 weeks | After K.4 |
| K.6 | Multi-AZ/multi-region + autoscaling + Nomad/ECS/Compose adapters | ~4 weeks | After K.4 |
| K.7 | Async/remote-call unification (`@remote`, `remoteStub`, `WireCodec`) | Months | Gated on WORK_QUEUE v1.62.1 |

## 6. Testing Strategy

| Phase | Tests |
|---|---|
| K.1 | `Source(1,2,3).distributed.map(_*2).local.runToList == List(2,4,6)` through DirectRunner; bounded variant fails on 1-byte limit; `BasicStreamOps` compiles against both `Source` and `DStream` |
| K.2 | Local `spawn` still works; `spawnRemote` delivers message cross-node in two-node interpreter test; `ref.isLocal` / `ref.address` correct; `ref.tryLocal` returns `None` for remote refs; codegen-gap fix for `setClusterAuthToken` on JVM (existing test) |
| K.3 | `joinCluster(DnsSrv("svc", 9100).resolve(), token)` resolves peers in mock-DNS test; `cluster { … }` block lowers correctly; `clusterOf()` fails before `startNode` with clear error |
| K.4 | `K8sManifestGenerator` emits `StatefulSet` (not `Deployment`) when `clusterMode = true`; headless `Service` present; `SSC_CLUSTER_TOKEN` env injected from Secret; rolling upgrade drains node before restart |
| K.5 | Token rotation test: two peers accept new token during overlap, reject old after `validFrom`; cluster config persists through restart when `EtcdStateBackend` configured |
| K.6 | Compose target: `sdc-compose.yaml` correct with `depends_on` and shared network |
| K.7 | `@remote def echo` + `remoteStub[EchoApi]` round-trip on two nodes; `Timeout` / `DecodeError` / `AuthError` each surface as typed `RemoteCallError` |

## 7. Open Questions (require decisions before Phase K.3 starts)

1. **`cluster { … }` parser integration**: add as a named `Preprocessor` entry (requires Theme F SPI to land first), or add to the existing hard-coded chain in `Parser.scala` as a temporary shortcut? Recommendation: temporary hard-coded entry first; migrate to `PreprocessorRegistry` when Theme F ships.
2. **`WorkloadMode` default**: StatefulSet for cluster nodes vs Deployment with sticky sessions? Recommendation: StatefulSet for cluster members (stable DNS hostnames required for headless seed discovery), Deployment for stateless workload replicas.
3. **Behaviour serialisation for `spawnRemote`**: behaviours are registered by name (string handle). Should the registry be in-memory (per-node, cleared on restart) or persisted via StateBackend? Recommendation: in-memory with a `BehaviorRegistry.register("name", behavior)` call that application code must include.

## 8. Critical Files Index

**To read / modify in implementation**:
- `runtime/std/streams.ssc` — add `BasicStreamOps` trait
- `runtime/std/dstreams.ssc` — implement `DStream.local` / `DStream.localBounded`
- `runtime/std/dstreams-plugin/src/main/scala/scalascript/compiler/plugin/dstreams/DStreamsIntrinsics.scala` — add `_dag_sink_local` node
- New `runtime/std/streams-bridge.ssc` — `Source.distributed` extension only
- `lang/interop/src/main/scala/scalascript/interop/runtime/Actors.scala` — typed wrapper completion
- `runtime/std/actors.ssc` — `globalRegister/globalWhereis` wiring for `publish`
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/ActorInterp.scala` — `cluster_spawn` handler
- `docs/distributed-wire-protocol.md` — add `cluster_spawn` / `cluster_spawn_ack` messages
- New `runtime/backend/spi/src/main/scala/scalascript/backend/spi/Cluster.scala`
- New `runtime/backend/spi/src/main/scala/scalascript/backend/spi/SeedResolver.scala`
- `runtime/std/cluster/membership.ssc` — `joinCluster` updated to accept `SeedResolver`
- `lang/core/src/main/scala/scalascript/parser/Parser.scala` — `cluster { … }` preprocessor
- `runtime/std/deploy-plugin/src/main/scala/scalascript/compiler/plugin/deploy/K8sTarget.scala` — cluster-aware extensions
- `runtime/std/deploy-plugin/src/main/scala/scalascript/compiler/plugin/deploy/K8sManifestGenerator.scala` — StatefulSet + headless Service
- New `runtime/std/deploy-plugin/.../ComposeTarget.scala`, `NomadTarget.scala`, `EcsTarget.scala`
- `runtime/backend/jvm/src/main/scala/scalascript/codegen/JvmGen.scala:1788-1953` — generalise typed-route codegen for K.7
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/{AsyncRuntime,BuiltinsRuntime}.scala` — `Future[A]` sugar for K.7
- `backend/typed-data/src/main/scala/scalascript/typeddata/JsonCodec.scala` — reference for `inline given derived` pattern used in K.7 stubs

**Existing docs to cross-reference (do not modify, reference only)**:
- `docs/{streams,distributed-streams,actors-dist,distributed-wire-protocol,cluster-management,cluster-raft,cluster-federation,cluster-codegen-gap,deploy,typed-route-clients}.md`
