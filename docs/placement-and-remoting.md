# Placement and Remoting

Status: planned. This document defines how local, remote, and distributed
execution surfaces should compose across streams, actors, and asynchronous
function calls.

This spec builds on:

- [`docs/actors-dist.md`](actors-dist.md) - current actor cluster model.
- [`docs/distributed-streams.md`](distributed-streams.md) - `DStream[T]`.
- [`docs/typed-route-clients.md`](typed-route-clients.md) - typed RPC-like
  route clients.
- [`docs/distributed-wire-protocol.md`](distributed-wire-protocol.md) -
  future JSON/MsgPack/CBOR internal wire layer.
- [`docs/deploy.md`](deploy.md) and architecture deploy docs - packaging and
  target deployment foundations.

## Problem

ScalaScript has several execution surfaces that can be local, remote, or
distributed:

- `Source[A]` local streams.
- `DStream[A]` distributed streams.
- local actors and actor clusters.
- typed route clients, which are already typed remote calls over HTTP.
- Dataset/MapReduce workers.
- object-sync client/server routes.

Today each surface exposes its own vocabulary. Users need to understand whether
they should use `Source[A]`, `DStream[A]`, `Pid`, typed route clients, or raw
actor messages. The distinction is real and must remain visible, but the
transition points should be explicit and ergonomic.

## Goals

- Provide a shared placement vocabulary: `local`, `remote`, `distributed`,
  `proxy`, `materialize`, `publish`, and `spawnRemote`.
- Make network boundaries explicit in types and effects.
- Let users move between local and remote/distributed views where semantics are
  well-defined.
- Define how code is made available on remote nodes.
- Define a convenient cluster creation and deployment model.
- Reuse typed data codecs and the distributed wire protocol for payloads.
- Keep the MVP practical: pre-deployed same application on every node before
  dynamic code shipping.

## Non-goals

- Do not pretend local and distributed semantics are identical. Latency,
  partial failure, serialization, retries, cancellation, ordering, and
  backpressure must remain visible.
- Do not ship arbitrary closures in the first implementation.
- Do not require public HTTP APIs to use ScalaScript internal wire formats.
- Do not replace Spark/Kafka/Flink/Beam native deployment models.
- Do not make cluster deployment depend on Kubernetes; K8s is one deployment
  target, not the only one.

## Core Concepts

### Placement

Placement answers "where does this computation execute?"

```scala
enum Placement:
  case Local
  case Remote(node: NodeSelector)
  case Distributed(policy: DistributionPolicy)
```

### Operation Name

An operation name is a stable logical id for a remotely callable behavior:

```text
users.get
reports.render
workers.image.resize
streams.orders.events
```

`users.get` is not a URL and not necessarily a Scala method path. It is a
registry key. A transport maps it to HTTP, WebSocket RPC, actor messages, or an
in-process call.

Example:

```scala
def getUser(id: UserId): Future[User] = ...

val remoteGetUser = getUser.remote("users.get")
```

Here:

- `getUser` is the local top-level function.
- `.remote("users.get")` adapts it to a named remote operation.
- `users.get` is the stable operation name used in manifests, registries, logs,
  metrics, tracing, and wire envelopes.

For clarity the explicit form is preferred in specs and examples:

```scala
remote def users.get(id: UserId): User = getUser(id)
val getUserClient = Remote.function[UserId, User]("users.get")
```

The `.remote("users.get")` extension is a convenience only when the compiler can
prove the function is named, exported, serializable at the boundary, and
available on the target node.

### Code Identity

Remote execution requires code availability. Every node must know which code it
is running.

```scala
case class CodeIdentity(
  appName: String,
  moduleHash: String,
  artifactHash: String,
  runtimeVersion: String,
  exports: Set[String],
)
```

Cluster peers reject remote execution requests when code identity is
incompatible. Initial rule: same `artifactHash` and same `runtimeVersion` for
internal remote calls.

### Handler Registry

Remote calls do not invoke arbitrary closures. They invoke named handlers:

```yaml
remoteHandlers:
  users.get:
    function: getUser
    request: UserId
    response: User
  reports.render:
    function: renderReport
    request: ReportRequest
    response: Report
```

Equivalent source-level form:

```scala
remote def users.get(id: UserId): User =
  getUser(id)
```

Handlers are compiled into a registry:

```scala
trait RemoteHandlerRegistry:
  def describe(): List[RemoteHandlerInfo]
  def invoke(name: String, payload: WireValue): Future[WireValue]
```

The registry is local to each node. Cluster discovery advertises which handler
names each node can serve.

## Code Deployment

### MVP: Pre-deployed Same App

Every node runs the same built artifact:

```text
ssc package app.ssc --target jvm --out dist/app.jar
scp dist/app.jar node1:
scp dist/app.jar node2:
java -jar app.jar --cluster-role server ...
java -jar app.jar --cluster-role worker ...
```

This is the first implementation target because it avoids sandboxing and
dependency shipping. Remote operation names resolve against code already loaded
on the node.

### Worker Bundle

For distributed workers, ScalaScript should support a worker artifact:

```text
ssc package app.ssc --target worker --out dist/app-worker.zip
ssc cluster deploy dist/app-worker.zip --nodes workers.yaml
```

The worker bundle contains:

- compiled code or runnable script artifact;
- runtime version;
- dependency metadata;
- exported remote handler registry;
- code identity hash;
- optional static assets needed by handlers.

### Container and K8s

Existing deploy/container/K8s work can launch the same artifact across nodes.
This spec adds cluster-oriented metadata on top:

```yaml
cluster:
  name: analytics
  roles:
    server:
      replicas: 2
    worker:
      replicas: 8
  wire:
    format: cbor
```

Deploy targets should expose the advertised URLs and role labels back to the
cluster manifest.

### Dynamic Code Shipping

Dynamic code shipping is planned but not part of the MVP. It requires:

- signed code bundles;
- dependency cache and hash verification;
- sandbox policy;
- resource limits;
- rollback/unload behavior;
- compatibility checks;
- audit logging.

Until this phase lands, `.remote` and `.distributed` can only target nodes that
already run compatible code.

## Cluster Creation

### Front Matter

```yaml
cluster:
  name: demo
  nodeId: api-1
  role: server              # server | worker | client | scheduler
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

### CLI

MVP commands:

```text
ssc cluster run app.ssc --role server --node-id api-1 --bind 0.0.0.0:9100
ssc cluster run app.ssc --role worker --node-id worker-1 --join ws://api-1:9100/_ssc-actors
ssc cluster status --seed ws://api-1:9100/_ssc-actors
ssc cluster handlers --seed ws://api-1:9100/_ssc-actors
```

Packaging/deployment commands:

```text
ssc cluster package app.ssc --target worker --out dist/app-worker.zip
ssc cluster deploy dist/app-worker.zip --nodes cluster.yaml
ssc cluster stop --name demo
```

The implementation can initially lower these to existing `startNode`,
`connectNode`, `serve`, deploy target, and package commands.

## Streams

### Types

```scala
type Source[A]        // local stream, in-process
type RemoteSource[A]  // remote stream view over HTTP/WS/internal wire
type DStream[A]       // distributed stream DAG/runner
```

### Adapters

```scala
extension [A](s: Source[A])
  def remote(name: String, policy: RemoteStreamPolicy = RemoteStreamPolicy.Default): RemoteSource[A]
  def distributed(partitions: Int = 1): DStream[A]

extension [A](rs: RemoteSource[A])
  def local(buffer: Int = 1024): Source[A]
  def distributed(partitions: Int = 1): DStream[A]

extension [A](ds: DStream[A])
  def local(buffer: Int = 1024): Source[A]
  def remote(name: String): RemoteSource[A]
```

### Semantics

- `Source[A] -> RemoteSource[A]` publishes or exposes a local stream.
- `RemoteSource[A] -> Source[A]` subscribes and materializes a local view.
- `Source[A] -> DStream[A]` partitions local input into a distributed runner.
- `DStream[A] -> Source[A]` collects or tails distributed output locally.

Ordering and delivery:

- `Source[A]` has local in-process ordering.
- `RemoteSource[A]` preserves per-producer ordering when the transport supports
  it, but can fail on disconnect.
- `DStream[A]` has partition-local ordering; global ordering requires an
  explicit operator.

Backpressure:

- Local `Source[A]` uses existing credit/backpressure semantics.
- `RemoteSource[A]` maps credit to WS pull/ack frames where possible.
- SSE fallback is push-only and uses bounded buffers/drop/fail policy.
- `DStream[A]` backpressure is runner-owned.

## Actors

### Current Base

Current actor distribution already extends `Pid` with `nodeId` and routes
messages over the cluster WebSocket. This spec adds a placement vocabulary on
top of that base.

### APIs

```scala
def spawnLocal(body: () => Unit): Pid

def spawnRemote(
  node: NodeSelector,
  behavior: String,
  args: WireValue = WireValue.Unit,
): Future[Pid]

extension (pid: Pid)
  def publish(name: String): Unit
  def localProxy(): Pid

object ActorGroup:
  def router(name: String, route: RoutingPolicy): ActorGroup
  def sharded(name: String, key: String => String): ActorGroup
```

### Named Behaviors

Remote spawn uses named behaviors, not arbitrary closures:

```yaml
actorBehaviors:
  workers.thumbnail:
    function: thumbnailWorker
    role: worker
```

```scala
actor behavior workers.thumbnail(args: ThumbnailWorkerArgs):
  ThumbnailWorker.run(args)
```

`spawnRemote(node, "workers.thumbnail", args)` asks the target node to start a
behavior it already has in its registry.

### Proxies and Groups

`localProxy()` creates a local actor whose only job is to forward messages to a
remote pid and translate remote failures into local `Exit` / `Down` messages.

Actor groups provide higher-level distribution:

- round-robin router;
- least-mailbox router;
- consistent-hash sharding;
- broadcast group;
- role-based group, for example all `worker` nodes.

## Async Functions / RPC

Remote functions are typed RPC over the same placement model.

```scala
trait RemoteFn[A, B]:
  def apply(a: A): Future[B]
  def withTimeout(ms: Long): RemoteFn[A, B]
  def withRetries(n: Int): RemoteFn[A, B]
  def localFallback(f: A => Future[B]): RemoteFn[A, B]

object Remote:
  def function[A, B](name: String): RemoteFn[A, B]
```

Convenience extension:

```scala
extension [A, B](f: A => Future[B])
  def remote(name: String): RemoteFn[A, B]
  def distributed(policy: DistributionPolicy): A => Future[B]
```

Rules:

- `f.remote(name)` is valid only for named top-level/exported functions or
  generated handlers.
- Inputs and outputs require `WireCodec[A]` and `WireCodec[B]`.
- Timeouts and cancellation are explicit.
- Retries require either idempotency or a caller-provided idempotency key.
- Distributed function calls require partitioning or routing policy.

Example:

```scala
case class UserId(value: String)
case class User(id: UserId, name: String)

remote def users.get(id: UserId): User =
  Db.queryOne[User]("select * from users where id = ?", id.value)

val getUser = Remote.function[UserId, User]("users.get")
val user = await(getUser(UserId("42")))
```

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

Compiler/runtime diagnostics should reject impossible placements:

- remote handler not exported;
- target role cannot run the handler;
- missing codec;
- incompatible code identity;
- unsupported transport/wire format;
- non-idempotent retry policy.

## Failure Semantics

All placement adapters surface network failure explicitly:

```scala
enum RemoteError:
  case Unavailable(node: String)
  case Timeout(operation: String)
  case Decode(message: String)
  case HandlerNotFound(name: String)
  case CodeMismatch(localHash: String, remoteHash: String)
  case Unauthorized
  case Cancelled
  case RemoteFailed(code: String, message: String)
```

Local fallback is explicit:

```scala
Remote.function[UserId, User]("users.get")
  .withTimeout(1000)
  .localFallback(getUser)
```

## Architecture

```text
source API
  -> placement adapter
      -> handler/behavior/source registry
          -> transport selection
              -> in-process
              -> HTTP typed RPC
              -> WebSocket RPC/actor wire
              -> Dataset/DStream runner
          -> distributed wire codec
              -> json / msgpack / cbor
```

Key modules planned:

| Module | Purpose |
|--------|---------|
| `runtime/placement-core` | Placement types, policies, errors, code identity |
| `runtime/remote-registry` | Remote handler/source/behavior registry |
| `runtime/cluster-runner` | CLI/front-matter cluster startup and join UX |
| `runtime/std/placement-plugin` | Interpreter intrinsics and std functions |
| `backend/typed-data` | `WireCodec` derivation bridge |
| `runtime/distributed-wire` | Wire protocol from `distributed-wire-protocol.md` |

## Migration

Existing APIs keep working:

- `Source[A]` remains local.
- `DStream[A]` remains the distributed stream abstraction.
- raw actor `Pid ! msg` keeps current behavior.
- typed route clients keep JSON HTTP by default.

New APIs are additive. The first implementation should ship examples that show
the same domain operation running local, remote, and distributed without hiding
the boundary.

## Phases

### Phase 0 - Spec and Backlog

Land this document, README links, and backlog items. No runtime changes.

### Phase 1 - Placement Core and Code Identity

- Add placement policy types and `RemoteError`.
- Add `CodeIdentity` generation from `.ssc` / `.sscc` artifacts.
- Add runtime same-code checks.
- Parse `cluster:` and `remoteHandlers:` front matter into metadata.
- Add diagnostics for missing codecs and missing handler names.

### Phase 2 - Remote Handler Registry and Function RPC

- Compile `remote def` / manifest `remoteHandlers` into a registry.
- Add `Remote.function[A, B](name)`.
- Support in-process, HTTP typed client, and WS/internal-wire transports.
- Add timeout, cancellation, retries, and explicit idempotency keys.

### Phase 3 - Cluster Runner and Worker Bundle

- Add `ssc cluster run/package/status/handlers`.
- Add worker bundle packaging with code identity.
- Add seed-node join, role labels, advertised URLs, and auth token wiring.
- Reuse existing deploy targets where possible.

### Phase 4 - Stream Placement Adapters

- Add `Source[A].remote`, `RemoteSource[A].local`, `Source[A].distributed`,
  `RemoteSource[A].distributed`, and `DStream[A].local`.
- Implement WS binary-capable remote stream transport with JSON fallback.
- Define SSE fallback constraints.
- Add backpressure/overflow tests.

### Phase 5 - Actor Placement Adapters

- Add named actor behaviors.
- Add `spawnRemote`.
- Add local proxy actors.
- Add router/sharded actor groups.
- Add code-identity and role compatibility checks.

### Phase 6 - Distributed Function Helpers

- Add `f.distributed(policy)` for handler-backed functions.
- Add partition/routing helpers for batch inputs.
- Connect Dataset and DStream runner placement policies.

### Phase 7 - Dynamic Code Shipping

- Signed worker bundles.
- Remote artifact cache.
- Dependency verification.
- Sandboxing/resource policy.
- Audit log and unload/rollback semantics.

### Phase 8 - Compatibility and Operational Hardening

- Mixed-version placement policy after wire/schema compatibility lands.
- Metrics/tracing for placement decisions.
- Circuit breakers and load-shed policies.
- Production deployment cookbook.

## Testing Strategy

- Metadata parse tests for `cluster:`, `remoteHandlers:`, and named actor
  behaviors.
- Code identity hash stability tests.
- Local/in-process remote function tests.
- HTTP and WS remote function tests with JSON/MsgPack/CBOR once wire core
  exists.
- Cross-node actor `spawnRemote` and proxy tests.
- Stream adapter tests for ordering, cancellation, overflow, and reconnect.
- Cluster runner smoke tests with two local processes.
- Negative diagnostics for missing handler, missing codec, code mismatch,
  unauthorized node, and invalid retry policy.

## Open Questions

- Whether `remote def users.get` syntax should be first-class parser syntax or
  library syntax built from annotations/front matter.
- Whether operation names should be validated as DNS-like paths, Scala-like
  paths, or free strings.
- Whether `RemoteFn[A, B]` should return `Future[B]`, `EitherT`, or a
  ScalaScript-native `Result` once that type is stable.
- How much of cluster deployment belongs in `ssc cluster` versus existing
  `ssc deploy`.
- Whether dynamic code shipping should support source `.ssc`, typed IR, JVM
  bytecode, JS bundles, or only signed worker packages.

