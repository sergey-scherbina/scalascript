# Distributed Runtime

Status: planned. This is the canonical specification for ScalaScript
local/remote/distributed execution, cluster lifecycle, and code deployment.

The low-level wire format remains in
[`docs/distributed-wire-protocol.md`](distributed-wire-protocol.md). This spec
defines the user-facing and runtime architecture that uses that wire layer.
Detailed operational design for token rotation, persistent cluster state,
rolling upgrades, multi-region deployment, and autoscaling is defined in
the Operations section of this document.

## Goals

1. Provide one coherent placement vocabulary for streams, actors, Dataset /
   MapReduce workers, object sync, typed route clients, and remote async calls:
   `local`, `remote`, and `distributed`.
2. Make network and process boundaries explicit in types and effects. APIs may
   share safe operators, but must not hide latency, partial failure, buffering,
   retries, cancellation, ordering, or serialization.
3. Let users move between local, remote, and distributed views where semantics
   are well-defined: `Source[A].distributed`, `DStream[A].local`,
   `Source[A].remote("events.orders")`, `RemoteSource[A].local`, typed
   `ActorRef[M]`, `spawnRemote`, and future `@remote def` / `remoteStub[Api]`.
4. Define how code becomes available on remote nodes: same pre-deployed app
   first, signed worker bundles next, controlled dynamic code shipping later.
5. Define cluster creation, seed discovery, deployment, rolling upgrade, token
   rotation, and operational lifecycle across local processes, Kubernetes,
   Docker Compose, Nomad, ECS, and bare-metal SSH.
6. Use the ScalaScript-native async model: `A ! Async` is canonical.
   `Future[A]` is compatibility sugar or interop, not the strategic API shape.
7. Reuse typed codecs and the distributed wire protocol for internal
   ScalaScript-to-ScalaScript traffic, with JSON fallback plus MsgPack/CBOR
   profiles when `WireCodec[A]` lands.

## Non-goals

- Do not pretend local and distributed semantics are identical.
- Do not collapse all local and distributed stream operators into one trait.
  Windowing, watermarks, keyed state, timers, and distributed joins remain
  distributed-only. Throttle, debounce, local buffering, broadcast, balance,
  concat, and local zip remain local-only.
- Do not serialize arbitrary closures in the MVP. Remote functions and actor
  behaviours are named, registered, and available on the target node.
- Do not make public HTTP APIs use ScalaScript internal binary formats.
- Do not replace Spark, Kafka, Flink, Beam, Kubernetes, Nomad, ECS, or systemd
  deployment models. ScalaScript integrates with them.
- Do not implement full Raft log replication, ZooKeeper coordination, sagas, or
  distributed transactions in this milestone.

## Current State

### Streams

| Item | Location | Status |
|---|---|---|
| `Source[A]` / `Stream[A]` | `runtime/std/streams.ssc` | Implemented |
| `DStream[T]` / `Pipeline` | `runtime/std/dstreams.ssc` | Implemented |
| `DSource.fromLocalSource[A](src: Source[A])` | `runtime/std/dstreams-plugin/.../DStreamsIntrinsics.scala` (`_dag_source_local`) | Implemented |
| `DStream[A].toLocalSource` | `docs/distributed-streams.md` | Specced, not implemented |
| Shared `map` / `filter` / `merge` / `run*` shapes | `Source` and `DStream` | Signature-aligned, no shared trait |

Correctly divergent operators:

| Local-only | Distributed-only |
|---|---|
| `throttle`, `debounce`, `buffer`, `broadcast(n)`, `balance(n)`, `concat`, `zip`, `zipWith` | `window`, `withTrigger`, `withAllowedLateness`, `withAccumulationMode`, `statefulMap`, `timer`, `broadcastState`, `sideInput`, `sideOutput`, `leftOuterJoin`, `rightOuterJoin` |

`flatMap` is deliberately not part of a common trait: local `flatMap` has
shape `A => Source[B]`, while distributed `flatMap` has shape
`A => Iterable[B]`.

### Actors

| Item | Location | Status |
|---|---|---|
| `Pid` with `nodeId` | `runtime/backend/interpreter/.../ActorInterp.scala` | Implemented; `pid ! msg` routes local or remote |
| `startNode` / `connectNode` / `register` / `whereis` | `runtime/std/nodes.ssc` | Implemented |
| `joinCluster(seeds, token)` | `runtime/std/cluster/membership.ssc` | Implemented |
| Cluster membership, phi failure detector, leader election | `runtime/std/actors.ssc`, `runtime/std/cluster/` | Implemented |
| `setClusterAuthToken(token)` / `SSC_CLUSTER_TOKEN` | `runtime/std/actors.ssc`, `JvmGen.scala` | Implemented for interpreter and JVM lowering |
| Typed ScalaScript wrapper `ActorRef[M]` | `runtime/std/actors.ssc`, interpreter dispatch | Implemented as a typed surface over `Pid` |
| Typed Scala wrapper `ActorRef[M]` | `lang/interop/src/main/scala/scalascript/interop/runtime/Actors.scala` | v0.1 stub |
| `spawnRemote` / `cluster_spawn` | `runtime/std/actors.ssc`, `ActorInterp.scala` | Implemented over JSON `ssc-actors-v1`; binary wire integration remains v1.62 |
| `ClusterCapability`, `SeedResolver.staticList`, `codeIdentity` | `runtime/std/cluster/types.ssc`, backend SPI, interpreter dispatch | Implemented; DNS/K8s/Consul descriptors exist, runtime resolution remains planned |
| Operational routes | `/_ssc-cluster/status`, `drain`, `events`, `step-down`, `metrics-prom` | Implemented |

### Async Calls

| Item | Location | Status |
|---|---|---|
| `! Async` algebraic effect | `runtime/backend/interpreter/.../BuiltinsRuntime.scala`, `AsyncRuntime.scala` | Implemented |
| User-land `Future[A]` monad | - | Not implemented; current value is an opaque cell |
| `BackendTransport` SPI | `runtime/backend/spi/.../BackendTransport.scala` | Implemented |
| Typed route clients | `docs/typed-route-clients.md`, `JvmGen.scala` | Partial |
| `WireCodec[A]` | `docs/distributed-wire-protocol.md`, `WORK_QUEUE.md` v1.62.1 | Planned |
| `@remote def` / `remoteStub[Api]` | - | Planned |

### Cluster Create / Deploy

| Item | Location | Status |
|---|---|---|
| `startNode(nodeId)` / `joinCluster(seeds, token)` | `runtime/std/nodes.ssc`, `runtime/std/cluster/membership.ssc` | Manual seed list only |
| DNS-SRV and Kubernetes headless-Service seed discovery | - | Planned |
| `K8sTarget` | `runtime/std/deploy-plugin/.../K8sTarget.scala` | Implemented, not cluster-aware |
| `DeployTarget` SPI | `runtime/std/deploy-plugin/.../DeployTarget.scala` | Implemented |
| Deploy groups, environments, blue-green, state backends | `runtime/std/deploy-plugin/` | Implemented |
| `cluster Demo:` block in `.ssc` | - | Planned |
| Rolling cluster upgrade, token rotation, persistent cluster state | - | Planned |
| Nomad / ECS / Compose targets | - | Planned |

## Core Model

### Placement

Placement answers "where does this computation execute?"

```scala
enum Placement:
  case Local
  case Remote(node: NodeSelector)
  case Distributed(policy: DistributionPolicy)
```

Network placement is not transparent. APIs that cross a process or network
boundary must expose async effects, remote errors, serialization requirements,
timeouts, cancellation, and retry policy.

### Operation Name

An operation name is a stable logical id for a remotely callable handler,
stream, or behaviour:

```text
users.get
reports.render
workers.image.resize
streams.orders.events
actors.thumbnail
```

It is not a URL and not necessarily a Scala method path. It is a registry key
used in manifests, discovery, logs, metrics, tracing, and wire envelopes.

Transport routes are separate:

```scala
@remote(name = "users.get", path = "/api/v1/users/:id")
def getUser(id: UserId): User ! Async =
  Db.queryOne[User]("select * from users where id = ?", id.value)
```

`name` is the stable operation identity. `path` is an HTTP/transport detail and
may be generated or overridden.

### Code Identity

Remote execution requires code availability and compatibility:

```scala
case class CodeIdentity(
  appName: String,
  moduleHash: String,
  artifactHash: String,
  runtimeVersion: String,
  exports: Set[String],
)
```

Initial compatibility rule for internal calls: same `artifactHash`, same
`runtimeVersion`, and required exported operation/behaviour present. Mixed
versions are deferred until the wire/schema compatibility phase.

### Registries

Remote execution does not invoke arbitrary closures. It invokes named entries
in local registries:

```scala
trait RemoteHandlerRegistry:
  def describe(): List[RemoteHandlerInfo]
  def invoke(name: String, payload: WireValue): WireValue ! Async | RemoteCallError

trait RemoteSourceRegistry:
  def describe(): List[RemoteSourceInfo]
  def subscribe(name: String, params: WireValue): RemoteStreamSession ! Async | RemoteCallError

trait BehaviorRegistry:
  def describe(): List[BehaviorInfo]
  def spawn(name: String, args: WireValue): Pid ! Async | RemoteCallError
```

Source and manifest declarations are both valid:

```scala
@remote(name = "users.get", path = "/api/v1/users/:id")
def getUser(id: UserId): User ! Async = ...

actor behavior workers.thumbnail(args: ThumbnailWorkerArgs):
  ThumbnailWorker.run(args)
```

```yaml
remoteHandlers:
  users.get:
    function: getUser
    path: /api/v1/users/:id
    request: UserId
    response: User
```

## Async and Error Model

Canonical async remote calls return `A ! Async` plus a typed remote error row:

```scala
enum RemoteCallError:
  case Unavailable(node: String)
  case Timeout(operation: String, durationMs: Long)
  case Decode(message: String)
  case HandlerNotFound(name: String)
  case CodeMismatch(localHash: String, remoteHash: String)
  case Unauthorized
  case Cancelled
  case RemoteFailed(code: String, message: String)
  case NetworkError(message: String)
```

Example:

```scala
trait UsersApi:
  def get(id: UserId): User ! Async | RemoteCallError

val users: UsersApi = remoteStub[UsersApi]("https://node.example")
val user = users.get(UserId("42"))
```

`Future[A]` may become sugar for `() => A ! Async` or an interop wrapper, but
new specs and APIs should use effect rows directly.

Retries and fallbacks are explicit:

```scala
Remote.function[UserId, User]("users.get")
  .withTimeout(1000)
  .withRetries(2, idempotency = Idempotent.ReadOnly)
  .localFallback(getUserLocal)
```

## Streams

```scala
type Source[A]        // local in-process stream
type RemoteSource[A]  // remote stream view over HTTP/WS/internal wire
type DStream[A]       // distributed stream DAG/runner
```

Only the identical subset belongs in a common trait:

```scala
trait BasicStreamOps[F[_]]:
  extension [A](fa: F[A])
    def map[B](f: A => B): F[B]
    def filter(p: A => Boolean): F[A]
    def merge(other: F[A]): F[A]
    def runForeach(f: A => Unit): Unit
    def runFold[B](z: B)(f: (B, A) => B): B
    def runToList: List[A]
```

`flatMap` is excluded. Local-only and distributed-only operators stay on their
native surfaces.

Bridge APIs:

```scala
extension [A](src: Source[A])
  def distributed(partitions: Int = 1): DStream[A] =
    DSource.fromLocalSource(src)

extension [A](ds: DStream[A])
  def local(using Cluster): Source[A]
  def localBounded(maxBytes: Long = 256 * 1024 * 1024L)(using Cluster): Source[A]
```

`DStream.local` materializes the distributed stream on the driver. It is
deliberately explicit because it can buffer large or unbounded data. The
bounded variant fails with `StreamTooLargeError`.

Remote stream APIs are a later layer:

```scala
extension [A](s: Source[A])
  def remote(name: String, policy: RemoteStreamPolicy = RemoteStreamPolicy.Default): RemoteSource[A]

extension [A](rs: RemoteSource[A])
  def local(buffer: Int = 1024): Source[A]
  def distributed(partitions: Int = 1): DStream[A]

extension [A](ds: DStream[A])
  def remote(name: String): RemoteSource[A]
```

WebSocket transport maps demand to pull/ack frames. SSE fallback is push-only
and uses explicit bounded buffer/drop/fail policy.

## Actors

The low-level base is the current `Pid` with `nodeId`. The user-facing surface
adds typed refs and named remote spawn:

```scala
opaque type ActorRef[+M] = Pid
opaque type LocalActorRef[+M] <: ActorRef[M] = Pid
opaque type NodeId = String

object ActorRef:
  def apply[M](pid: Pid): ActorRef[M] = pid

  extension [M](ref: ActorRef[M])
    def !(msg: M): Unit
    def address: Option[NodeId]
    def isLocal: Boolean
    def tryLocal: Option[LocalActorRef[M]]
    def publish(name: String): ActorRef[M]

def spawnRemote[M](
  node: NodeSelector,
  behavior: BehaviorDescriptor[M],
  args: WireValue = WireValue.Unit,
): ActorRef[M] ! Async | RemoteCallError
```

`BehaviorDescriptor[M]` resolves to a registered behaviour name. The wire layer
uses `cluster_spawn` and `cluster_spawn_ack`; it does not ship arbitrary
closures.

Actor groups are a future layer:

```scala
object ActorGroup:
  def router[M](name: String, route: RoutingPolicy): ActorGroup[M]
  def sharded[M](name: String, key: M => String): ActorGroup[M]
  def role[M](roleName: String): ActorGroup[M]
```

Local actor failure is visible on the local scheduler tick. Remote actor
failure is detected through heartbeat/failure detector timeout; monitoring a
remote ref is required when latency matters.

## Async Remote Calls

Remote function calls are typed RPC over the same placement and registry model:

```scala
trait RemoteFn[A, B]:
  def apply(a: A): B ! Async | RemoteCallError
  def withTimeout(ms: Long): RemoteFn[A, B]
  def withRetries(n: Int, idempotency: IdempotencyPolicy): RemoteFn[A, B]
  def localFallback(f: A => B ! Async): RemoteFn[A, B]

object Remote:
  def function[A, B](name: String): RemoteFn[A, B]

trait EchoApi:
  def echo(s: String): String ! Async | RemoteCallError

val client: EchoApi = remoteStub[EchoApi]("https://node.example")
```

Stub generation should use Scala 3 inline derivation where possible, following
the same style as `JsonCodec.derived`.

## Placement Policies

```scala
enum NodeSelector:
  case AnyNode
  case NodeId(id: String)
  case Role(name: String)
  case WithHandler(operation: String)
  case LocalPreferred(fallback: Box[NodeSelector])

enum DistributionPolicy:
  case Broadcast
  case RoundRobin
  case LeastBusy
  case ConsistentHash(key: String)
  case Partitioned(partitions: Int)
```

Compiler/runtime diagnostics must reject missing handlers, missing behaviours,
missing codecs, incompatible code identity, unsupported transport/wire format,
non-idempotent retry policy, unauthorized nodes, and target roles that cannot
run the selected operation.

## Cluster Lifecycle

Cluster state becomes a first-class runtime capability:

```scala
trait Cluster:
  def localNodeId: String
  def peers: List[NodeId]
  def authToken: Option[String]
  def seedResolver: SeedResolver
  def codeIdentity: CodeIdentity

trait SeedResolver:
  def resolve(): List[String] // ws:// or wss:// peer URLs

object SeedResolver:
  def staticList(urls: List[String]): SeedResolver
  def dnsSrv(serviceName: String, port: Int = 9100): SeedResolver
  def k8sHeadlessService(serviceName: String, namespace: String = "default", port: Int = 9100, scheme: String = "ws"): SeedResolver
  def consulCatalog(serviceName: String, consulAddr: String = "localhost:8500"): SeedResolver
```

Current implementation status: `runtime/backend/spi` provides `Cluster`,
`SeedResolver`, and `CodeIdentity` types. The ScalaScript standard surface
exports `ClusterCapability`, `SeedResolver`, `clusterOf`, `resolveSeeds`,
`codeIdentity`, and `assertCodeIdentity`; the interpreter supports static seed
lists and deterministic SHA-256 identity for `.ssc` source modules and `.sscc`
artifacts. DNS/Kubernetes/Consul resolver values are represented in the public
API. The interpreter resolves static lists plus DNS and Kubernetes headless
Service descriptors; Consul remains planned and fails with an explicit
diagnostic. `cluster:`, `remoteHandlers:`,
`remoteSources:`, and `remoteBehaviors:` front matter now parse into typed
AST/IR metadata and survive `.sscc` round-trip; lowering them into generated
startup code remains planned. Parser-time validation rejects registry entries
whose `function`, `source`, or `behavior` target is not defined locally.
Top-level `cluster Name:` blocks lower into the same `ClusterDecl` metadata
shape, preserving nodes, seed discovery, leader election, auth source,
heartbeat, and quorum settings.

The source-level typed declaration is primary:

```ssc
cluster Demo:
  nodes = 3
  seedDiscovery = SeedResolver.k8sHeadlessService("ssc-demo")
  leaderElection = Raft
  authTokenFrom = K8sSecret("ssc-cluster-token", key = "token")
  heartbeat(intervalMs = 5000, deadAfterMs = 40000)
  quorum(2)
```

Front matter remains the manifest/deploy override layer:

```yaml
cluster:
  name: demo
  nodeId: api-1
  role: server
  bind: 0.0.0.0:9100
  advertiseUrl: ws://api-1:9100/_ssc-actors
  seedNodes:
    - ws://api-1:9100/_ssc-actors
    - ws://worker-1:9200/_ssc-actors
  authToken: ${env:SSC_CLUSTER_TOKEN}
  placement:
    defaultTimeoutMs: 10000
    defaultRetries: 2
  wire:
    enabled: true
    format: cbor
```

MVP CLI:

```text
ssc cluster run app.ssc --role server --node-id api-1 --bind 0.0.0.0:9100
ssc cluster run app.ssc --role worker --node-id worker-1 --join ws://api-1:9100/_ssc-actors
ssc cluster status --seed ws://api-1:9100/_ssc-actors
ssc cluster handlers --seed ws://api-1:9100/_ssc-actors
ssc cluster package app.ssc --target worker --out dist/app-worker.zip
ssc cluster deploy dist/app-worker.zip --target k8s --env production
ssc cluster stop --name demo
```

## Code Deployment

### MVP: Pre-deployed Same App

Every node runs the same artifact:

```text
ssc package app.ssc --target jvm --out dist/app.jar
scp dist/app.jar node1:
scp dist/app.jar node2:
java -jar app.jar --cluster-role server ...
java -jar app.jar --cluster-role worker ...
```

Remote operation names resolve against code already loaded on the node. This is
the first implementation target because it avoids sandboxing, dependency
shipping, and dynamic trust decisions.

### Worker Bundle

A worker bundle contains compiled code or runnable script artifact, runtime
version, dependency metadata, exported handler/source/behaviour registries, code
identity hash, and optional static assets required by handlers.

### Dynamic Code Shipping

Dynamic code shipping is later work. It requires signed bundles, dependency
cache and hash verification, sandbox/resource policy, compatibility checks,
audit log, unload, and rollback behavior.

## Deployment Targets

```scala
trait ClusterTarget extends DeployTarget:
  def seedUrlsFor(env: DeployEnvironment): List[String]
  def injectAuthToken(env: DeployEnvironment, secretRef: String): Unit
  def emitWorkloadManifest(mode: WorkloadMode): String
  def emitHeadlessService(): String
  def emitAutoscaler(policy: ScalePolicy): Option[String]

enum WorkloadMode:
  case Deployment
  case StatefulSet
  case DaemonSet

enum ScalePolicy:
  case Cpu(targetPercent: Int)
  case Custom(metricName: String, targetValue: Long)
  case None

case class CpuTarget(percent: Int)
case class CustomTarget(metric: String, value: Int)
enum AutoscaleTarget:
  case Cpu(t: CpuTarget)
  case Custom(t: CustomTarget)

case class HpaConfig(min: Int, max: Int, targets: List[AutoscaleTarget])
```

When cluster mode is present, `K8sTarget` emits a `StatefulSet`, headless
`Service`, peer URL `ConfigMap`, auth token `Secret`, `SSC_CLUSTER_TOKEN` env
injection, optional `ssc cluster wait-for-peers` init container, and rolling
upgrade orchestration using `/_ssc-cluster/drain`.
When `autoscale:` is present, it also emits a `HorizontalPodAutoscaler` with
CPU and custom metric targets as defined in `docs/cluster-operations.md`.
The HPA `scaleTargetRef` points to a `StatefulSet` for cluster members and to a
`Deployment` for stateless workloads.

Additional targets:

| Target | Adapter | Key additions |
|---|---|---|
| Docker Compose | `ComposeTarget` | Shared network, env injection, basic startup ordering |
| Nomad | `NomadTarget` | Job HCL generation, Consul service registration |
| AWS ECS | `EcsTarget` | Task Definition, Service, Service Connect peer discovery |
| Bare-metal SSH | existing traditional deploy work | systemd unit env, static seed list, token file/secret |

## Operations

### Auth Token Rotation

Current state: `setClusterAuthToken(token)` loads a static token at startup,
usually from `SSC_CLUSTER_TOKEN`; changing it requires a full restart.

Planned API:

```scala
extern def rotateClusterToken(
  newToken: String,
  overlapMs: Int = 30_000,
): Unit
```

Protocol:

1. The initiating node broadcasts `token_rotate` to connected peers:

   ```json
   { "$t": "token_rotate", "new": "<sha256-of-new-token>", "valid_from": <epoch-ms> }
   ```

2. Receivers add the new token to the accept set while keeping the old token.
3. Receivers schedule `valid_from - now()`; when the timer fires, they drop the
   old token.
4. Receivers acknowledge with:

   ```json
   { "$t": "token_rotate_ack", "nodeId": "..." }
   ```

5. The initiator considers the rotation safe after quorum acks. If quorum is
   not reached within `overlapMs / 2`, the rotation aborts and the old token
   remains the sole valid token.

Recommendation for v1.63.7: use broadcast-with-quorum first because it works
without a current Raft leader. Raft-committed rotation remains a future
hardening option.

When `StateBackend` is configured, the accepted token hash is persisted under a
cluster-internal key so restarted nodes pick up the rotated token before
falling back to `SSC_CLUSTER_TOKEN`.

### Persistent Cluster State

Current state: cluster gossip is in-memory LWW state. The per-node Raft vote
file is durable, but `clusterConfigSet` / `clusterConfigGet` only update
in-memory gossip.

Planned API:

```scala
extern def clusterConfigSet(key: String, value: String): Unit
extern def clusterConfigGet(key: String): Option[String]
```

When `state:` front matter selects a real backend, cluster config writes go to
the existing deploy `StateBackend` SPI:

```scala
val cfgKey = StateKey(env = "_cluster", target = nodeId, slot = Some(key))
backend.write(cfgKey, StateRecord(revision = ..., outputs = Map("value" -> value), ...))
```

Reads are backend-first, then gossip. `NoopStateBackend` preserves current
behavior: LWW gossip only, lost on restart.

This is not Raft log replication. It is persistence for cluster configuration
values such as quorum settings, advertised URLs, handler metadata, and rotated
token metadata.

### Rolling Cluster Upgrade

Planned API:

```scala
enum RollingStrategy:
  case Rolling(maxUnavailable: Int = 1, waitDrainMs: Int = 30_000)
  case BlueGreen(observeMs: Int = 60_000)
  case Canary(percent: Int, observeMs: Int = 120_000)

def Deploy.rollingCluster(
  env: DeployEnvironment,
  target: DeployTarget,
  strategy: RollingStrategy = RollingStrategy.Rolling(),
): Unit
```

Per-node `Rolling` sequence:

1. Assert the node is not already draining.
2. `POST /_ssc-cluster/drain`, then wait for `drainDone` or
   `waitDrainMs`.
3. Deploy the new artifact to that node through the existing `DeployTarget`.
4. Re-activate the node by clearing drain mode.
5. Wait until peer heartbeats show the node healthy.
6. Continue to the next node, respecting `maxUnavailable`.

`BlueGreen` and `Canary` reuse existing deploy-plugin blue-green/slot
infrastructure; `rollingCluster` adds cluster drain as the pre-cutover gate.
If a node does not become healthy within `2 * waitDrainMs`, the orchestrator
stops, reports `CodeMismatch` / health diagnostics, and leaves rollback to
`ssc cluster rollback`.

`rollingCluster` uses `DeployGroup(mode = ExecMode.Sequence, onFailure =
FailurePolicy.AbortRemaining)` for per-node scheduling. No new orchestration
primitives are needed.

### Multi-AZ / Multi-Region

`FaultToleranceConfig` already carries:

```scala
case class FaultToleranceConfig(
  multiRegion: List[String],
  quorum: Int,
  failoverStrategy: FailoverStrategy,
)
```

Planned lowering:

```yaml
fault_tolerance:
  multi_region: [us-east-1, eu-west-1]
  quorum: 2
  failover_strategy: active-passive
```

When `multi_region` is non-empty, deploy emits one `DeployGroup` per region and
runs the regional groups independently. `quorum` constrains
`Deploy.rollingCluster`: a region is not upgraded if doing so would leave fewer
than the configured quorum of healthy regions.

Named actors published through `pid.publish(name)` continue to use cluster
gossip for cross-region rendezvous. External traffic routing remains the
operator's responsibility; deploy outputs expose per-region load balancer URLs
for Route 53 / GCP LB / Azure Traffic Manager or similar tools.

### Autoscaling

Autoscaling is target-specific. Kubernetes supports:

```yaml
k8s:
  replicas: 2
  autoscale:
    min: 2
    max: 10
    target:
      - kind: cpu
        percent: 70
      - kind: custom
        metric: requests_per_second
        value: 1000
```

`K8sManifestGenerator` emits a `HorizontalPodAutoscaler` manifest alongside
the workload manifest when `autoscale:` is present. For cluster members the
`scaleTargetRef` points to the `StatefulSet`; for stateless workloads it
points to the `Deployment`.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: ${name}-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment   # or StatefulSet for cluster members
    name: ${name}
  minReplicas: ${autoscale.min}
  maxReplicas: ${autoscale.max}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: ${cpu.percent}
    # Custom metrics require KEDA or a compatible metrics adapter.
    - type: External
      external:
        metric:
          name: ${custom.metric}
        target:
          type: AverageValue
          averageValue: "${custom.value}"
```

Non-Kubernetes targets ignore `autoscale:` unless they add their own scaling
adapter. Default: no HPA emitted.

## Architecture

```text
source API
  -> placement adapter
      -> handler / behavior / source registry
          -> code identity check
          -> placement policy
          -> transport selection
              -> in-process
              -> HTTP typed RPC
              -> WebSocket RPC/actor wire
              -> Dataset / DStream runner
          -> distributed wire codec
              -> json / msgpack / cbor
```

Planned module surfaces:

| Module | Purpose |
|---|---|
| `runtime/placement-core` | Placement types, policies, remote errors, code identity |
| `runtime/remote-registry` | Handler/source/behaviour registries |
| `runtime/cluster-runner` | CLI/front-matter cluster startup and join UX |
| `runtime/std/placement-plugin` | Interpreter intrinsics and std helpers |
| `runtime/backend/spi` | `Cluster`, `SeedResolver`, transport SPI additions |
| `backend/typed-data` | `WireCodec` derivation bridge |
| `runtime/distributed-wire` | Wire protocol runtime once v1.62 lands |
| `runtime/std/deploy-plugin` | Cluster-aware deploy targets |

Existing `Source[A]`, `DStream[A]`, raw `Pid ! msg`, typed route clients, and
deploy targets keep working. New APIs are additive.

## Roadmap

### v1.63.0 - Canonical Distributed Runtime Spec

Land this document, redirect the two older specs, and update README / backlog /
work queue links. No runtime changes.

### v1.63.1 - Stream Bridge and BasicStreamOps

- Add `runtime/std/streams-bridge.ssc`.
- Add `Source[A].distributed`.
- Add `DStream[A].local` and `localBounded`.
- Add `BasicStreamOps[F[_]]`.
- Implement `_dag_sink_local`.
- Add tests for bounded collection and shared ops.

### v1.63.2 - Typed Actors and Remote Spawn

- Complete typed `ActorRef[M]`. ✓ Landed 2026-05-28: `ActorRef[M]`,
  `LocalActorRef[M]`, `NodeId`, `actorRef`, `ref.tell`, `ref.address`,
  `ref.isLocal`, `ref.tryLocal`, and `ref.publishAs`.
- Add `spawnRemote`. ✓ Landed 2026-05-28 for named behavior spawn over the
  existing JSON actor WebSocket transport.
- Add `BehaviorRegistry`. ✓ Landed 2026-05-28 as `registerBehavior(name,
  behavior)`.
- Add `cluster_spawn` / `cluster_spawn_ack` wire messages. ✓ Landed
  2026-05-28 on the `ssc-actors-v1` JSON control channel; MsgPack/CBOR
  profiles remain under `docs/distributed-wire-protocol.md`.
- Fix JVM lowering for `setClusterAuthToken`. ✓ Landed 2026-05-28.
- Add two-node actor tests. ✓ Landed 2026-05-28 as a jar-gated CLI
  multi-node smoke test plus interpreter local-path coverage.

### v1.63.3 - Cluster Capability, Seed Discovery, and Code Identity

- Add `Cluster` capability. ✓ Landed 2026-05-28 as backend SPI `Cluster` and
  ScalaScript `ClusterCapability`.
- Add `SeedResolver` SPI. ✓ Landed 2026-05-28 with static, DNS, Kubernetes
  headless-Service, and unsupported resolver descriptors; interpreter resolves
  static/DNS/Kubernetes descriptors and gives clear diagnostics for Consul.
- Add code identity generation/checks for `.ssc` / `.sscc` artifacts. ✓ Landed
  2026-05-28 for interpreter `codeIdentity()` / `assertCodeIdentity(...)`.
- Parse `cluster:` and registry front matter. ✓ Landed 2026-05-28 as typed
  `ClusterDecl`, `RemoteHandlerDecl`, `RemoteSourceDecl`, and
  `RemoteBehaviorDecl` metadata in AST/IR/`.sscc`.
- Add source `cluster Demo:` lowering. ✓ Landed 2026-05-28 for top-level
  source cluster blocks into `ClusterDecl` metadata.
- Add diagnostics for missing handlers/codecs/code mismatch. Partial landed
  2026-05-28 for missing front-matter registry definitions and code identity
  mismatch; codec validation remains planned.

### v1.63.4 - Remote Registries and Async RPC

- Compile `@remote` / `remote def` / manifest handlers into
  `RemoteHandlerRegistry`.
- Add `Remote.function[A, B](name)` returning `B ! Async | RemoteCallError`.
- Add `remoteStub[Api]`.
- Support in-process, HTTP, and WebSocket/internal-wire transports.
- Gate binary payloads on v1.62.1 `WireCodec[A]`; keep JSON fallback.

### v1.63.5 - Cluster Runner and Worker Bundles

- Add `ssc cluster run/package/status/handlers/stop`.
- Add worker bundle packaging with code identity and registry metadata.
- Add role labels, advertised URLs, auth-token wiring, and deploy-target
  integration.
- Add two-local-process smoke tests.

### v1.63.6 - Stream and Actor Placement Adapters

- Add `Source[A].remote`, `RemoteSource[A].local`,
  `RemoteSource[A].distributed`, and `DStream[A].remote`.
- Add WebSocket remote stream transport with JSON fallback.
- Define SSE fallback constraints.
- Add local proxy actors.
- Add router/sharded/role actor groups.

### v1.63.7 - Cluster-aware Deployment and Operations

- Add `ClusterTarget`.
- Extend `K8sTarget` for StatefulSet/headless Service/token Secret.
- Add token rotation: `rotateClusterToken`, `token_rotate`,
  `token_rotate_ack`, dual-accept overlap, quorum ack, persisted token hash.
- Persist cluster state through `StateBackend`: `clusterConfigSet`,
  `clusterConfigGet`, backend-first reads, gossip fallback.
- Add `Deploy.rollingCluster` with `Rolling`, `BlueGreen`, and `Canary`
  strategies over the existing drain protocol and `DeployGroup`.
- Lower `FaultToleranceConfig.multiRegion` to regional `DeployGroup`s and
  constrain rolling upgrades by quorum.
- Add K8s HPA/autoscale emission through `HpaConfig` and
  `K8sManifestGenerator.hpa`.
- Add Docker Compose target; Nomad/ECS may follow in the same or next slice.

### v1.63.8 - Dynamic Code Shipping and Ops Hardening

- Add signed worker bundles.
- Add remote artifact cache and dependency verification.
- Add sandbox/resource policy.
- Add audit log and unload/rollback semantics.
- Add mixed-version placement after wire/schema compatibility.
- Add metrics/tracing, circuit breakers, load shedding, and production
  cookbook.

## Testing Strategy

| Phase | Tests |
|---|---|
| v1.63.1 | `Source(1,2,3).distributed.map(_*2).local.runToList == List(2,4,6)` through DirectRunner; bounded variant fails on a tiny byte limit; `BasicStreamOps` compiles against both `Source` and `DStream` |
| v1.63.2 | Local spawn still works; `spawnRemote` delivers through the local interpreter path and jar-gated two-node CLI smoke; `ref.isLocal` / `ref.address` / `ref.tryLocal` correct; JVM `setClusterAuthToken` test |
| v1.63.3 | `clusterOf()` exposes local node, peers, auth token, seed resolver, and code identity; static/DNS/K8s seeds resolve; Consul fails clearly until its resolver backend lands; `assertCodeIdentity` reports expected/actual digest mismatch; `cluster:` and registry front matter parse into typed metadata and survive Normalize/Denormalize + `.sscc`; missing registry function/source/behavior targets are rejected; top-level `cluster Demo:` lowers into `ClusterDecl`. Remaining: codec validation |
| v1.63.4 | `@remote def echo` plus generated client round-trip; timeout/decode/auth errors surface as typed `RemoteCallError`; JSON fallback works without binary wire |
| v1.63.5 | `ssc cluster run` two local processes; `handlers` lists exported operations; worker bundle contains code identity and registry metadata |
| v1.63.6 | Remote stream subscribe/order/cancel/reconnect; SSE overflow policy; proxy actor failure translation; actor group routing |
| v1.63.7 | K8s manifest emits StatefulSet/headless Service/Secret/env/HPA; token rotation reaches quorum and rejects the old token after overlap; `clusterConfigSet/Get` persists through restart with `StateBackend`; `Deploy.rollingCluster` drains before restart and aborts on unhealthy node; multi-region lowering emits regional `DeployGroup`s and respects quorum |
| v1.63.8 | Signed bundle verification; artifact-cache mismatch rejection; sandbox/resource limit diagnostics; mixed-version opt-in vectors |

## Critical Files Index

- `runtime/std/streams.ssc`
- `runtime/std/dstreams.ssc`
- `runtime/std/dstreams-plugin/src/main/scala/scalascript/compiler/plugin/dstreams/DStreamsIntrinsics.scala`
- `runtime/std/streams-bridge.ssc`
- `lang/interop/src/main/scala/scalascript/interop/runtime/Actors.scala`
- `runtime/std/actors.ssc`
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/ActorInterp.scala`
- `docs/distributed-wire-protocol.md`
- `runtime/backend/spi/src/main/scala/scalascript/backend/spi/Cluster.scala`
- `runtime/backend/spi/src/main/scala/scalascript/backend/spi/SeedResolver.scala`
- `runtime/std/cluster/membership.ssc`
- `lang/core/src/main/scala/scalascript/parser/Parser.scala`
- `runtime/std/deploy-plugin/src/main/scala/scalascript/compiler/plugin/deploy/K8sTarget.scala`
- `runtime/std/deploy-plugin/src/main/scala/scalascript/compiler/plugin/deploy/K8sManifestGenerator.scala`
- `runtime/std/deploy-plugin/src/main/scala/scalascript/compiler/plugin/deploy/DeployGroup.scala`
- `runtime/std/deploy-plugin/src/main/scala/scalascript/compiler/plugin/deploy/DeployEnvironment.scala`
- `runtime/std/deploy-plugin/src/main/scala/scalascript/compiler/plugin/deploy/StateBackend.scala`
- `runtime/std/deploy-plugin/src/main/scala/scalascript/compiler/plugin/deploy/HpaConfig.scala`
- `runtime/backend/jvm/src/main/scala/scalascript/codegen/JvmGen.scala`
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/AsyncRuntime.scala`
- `runtime/backend/interpreter/src/main/scala/scalascript/interpreter/BuiltinsRuntime.scala`
- `backend/typed-data/src/main/scala/scalascript/typeddata/JsonCodec.scala`

## Open Questions

1. Should `cluster Demo:` wait for `PreprocessorRegistry`, or land first as a
   dedicated parser preprocessor? Recommendation: land dedicated first, migrate
   later.
2. Should cluster front matter override source `cluster Demo:` values by
   default? Recommendation: allow deploy/runtime-only overrides; reject topology
   shape changes unless explicitly enabled.
3. Should `@remote(name, path)` be the only source syntax, or should
   `remote def users.get(...)` also be first-class? Recommendation: implement
   annotation first; add `remote def` sugar after parser hooks stabilize.
4. Should behaviour registry entries be persisted? Recommendation: in-memory
   registry built at startup; persist only cluster config and deploy state.
5. Which dynamic artifact formats should be supported first? Recommendation:
   signed worker package only.
6. Should token rotation be Raft-committed or broadcast-with-quorum?
   Recommendation: broadcast-with-quorum for v1.63.7; Raft commit can harden
   it after cluster leadership semantics are stable.
7. Should multi-region quorum count regions or nodes? Recommendation: count
   regions in the deploy/runtime UX; allow lower-level node quorum later for
   advanced deployments.
8. Should `ActiveStandby` differ from `ActivePassive`? Recommendation: treat
   `ActiveStandby` as passive replicas with no inbound traffic; `ActivePassive`
   may accept reads or warm traffic depending on target adapter.
9. Should HPA codegen live in `K8sTarget` or a helper? Recommendation:
   introduce `HpaConfig` plus `K8sManifestGenerator.hpa`; keep policy parsing
   in `K8sTarget`.
