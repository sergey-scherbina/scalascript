# client-zookeeper — minimal ZooKeeper client (deferred spec)

Status: **deferred** — spec lives in this file so the ZooKeeper
`LeaderCoordinator` adapter (Tier 3 in
[`cluster-management.md`](cluster-management.md) §11) is not designed
under deadline pressure when a consumer surfaces.  No code lands
until a real app asks for ZooKeeper-backed leader election; per
[`cluster-raft.md`](cluster-raft.md) §8 / Phase 3b, etcd ships first
and ZooKeeper waits its turn behind Consul.

Companion to [`specs/cluster-management.md`](cluster-management.md)
§11 (Tier 3 deferred work — the consumer for this client) and
[`specs/cluster-raft.md`](cluster-raft.md) §5 (the unified
`LeaderCoordinator` adapter shape — `acquireLease` / `renewLease` /
`releaseLease` / `currentHolder` — that this client must support).

## 1. Goals

A **minimal** ZooKeeper client, written in pure Scala, that provides
exactly the subset of ZK primitives the `ZkLeaderCoordinator` adapter
needs and nothing more.  The deliverable is a `client-zookeeper`
module sitting alongside `client-kafka`, `client-redis`,
`client-postgres`, etc., with the same shape: an sbt subproject, a
`scalascript.zookeeper` package, a small public surface, an
integration-test suite gated on a Docker container.

Concretely the client must support:

- **`connect`** — open a TCP session, exchange `ConnectRequest` /
  `ConnectResponse`, persist the negotiated `(sessionId,
  sessionPassword, timeout)` for reconnect.
- **`ping`** — session keepalive at `sessionTimeout / 3`.
- **`create`** — create a znode; specifically the ephemeral-sequential
  flavour (`createMode = 0x3`) used by the leader-election pattern.
- **`getChildren`** — list children of a path, optionally with a
  one-shot watch fired when the child set changes.
- **`getData`** — read a znode's payload (used to read the data
  written by the current lease holder).
- **`delete`** — remove a znode (graceful step-down releases the
  ephemeral lease znode; ZK also removes it on session expiry).
- **Watches** — surfaced as ZK natively defines them (one-shot,
  fired exactly once, must be re-armed by re-reading).
- **Session resumption** — on a TCP-level drop, reconnect to another
  ZK ensemble member and resend the negotiated `(sessionId,
  sessionPassword)` to resume the session within `sessionTimeout`;
  on `SessionExpired` (server rejects the resume), surface a
  `ZkSessionExpired` event so the adapter can drop leadership and
  re-acquire from scratch.

Everything else — ACLs beyond `OPEN_ACL_UNSAFE`, multi-op
transactions, async batched ops, `setData`, persistent watches,
SASL/Kerberos auth, observer-mode discovery, the admin-server JSON
endpoints — is out of scope for v1.  Add only when a consumer
demands it.

## 2. Non-goals

- **NOT a general-purpose ZooKeeper client library.**  No attempt to
  match `org.apache.zookeeper.ZooKeeper`'s feature set, API shape, or
  thread model.  This is a leader-election plumbing client; calling
  it `client-zookeeper` reflects the wire endpoint, not the
  ambition.
- **NOT a replacement for `org.apache.zookeeper`.**  Apps that
  already depend on Curator / the official client for non-leader
  use cases keep using it.  Our `client-zookeeper` is for ScalaScript
  apps that want ZK leader election *without* dragging in 8 MB of
  Apache ZK + SLF4J + Netty.
- **NOT JS-backend portable.**  `client-zookeeper` ships JVM-only
  and the `ZkLeaderCoordinator` adapter is JVM-only.  Per
  [`cluster-management.md`](cluster-management.md) §7 ("Cluster
  spanning incompatible backends" — hard-no), a cluster using
  ZK-backed coordination is JVM-only end-to-end.  Browser-SPA and
  JS-Node nodes do not participate; they would never reach a ZK
  ensemble anyway because ZK is a TCP-only service.
- **NOT a ZK ensemble.**  We are strictly a client; the server is
  someone else's (Apache ZK 3.7 or later running operationally).
- **NOT log-replication or KV-store features.**  `setData` is
  trivial to add later if needed, but is not used by leader
  election; we leave it out to keep the v1 surface honest.
- **NOT a chroot-namespace abstraction yet.**  The wire protocol
  supports a `chroot` suffix on the connect string (`host:port/app`)
  — we document it as an open question in §7 rather than committing
  to a v1 implementation.

## 3. Architecture

### 3.1 Module layout

```
client-zookeeper/
├── build.sbt                                   # sbt entry only — see below
└── src/
    ├── main/scala/scalascript/zookeeper/
    │   ├── ZkConfig.scala                      # endpoints, sessionTimeout, retry
    │   ├── ZkClient.scala                      # public surface — connect / ops
    │   ├── ZkSession.scala                     # (id, password, timeout) tuple
    │   ├── ZkNode.scala                        # path + stat record
    │   ├── ZkWatchEvent.scala                  # NodeCreated, NodeDeleted, ...
    │   ├── ZkError.scala                       # ConnectionLoss, SessionExpired, ...
    │   ├── jute/
    │   │   ├── Jute.scala                      # primitive codecs (int, long, ustring, buffer)
    │   │   ├── Records.scala                   # request/response case classes
    │   │   └── Opcodes.scala                   # numeric opcode constants
    │   ├── transport/
    │   │   ├── ZkTransport.scala               # TCP connect + length-prefixed framing
    │   │   ├── PendingRequest.scala            # xid -> Promise[Response] map
    │   │   └── IoLoop.scala                    # single thread reading frames, dispatching by xid
    │   └── package.scala                       # type aliases, constants
    └── test/scala/scalascript/zookeeper/
        ├── JuteCodecTest.scala                 # Phase 1 — pure round-trip
        ├── ZkClientIntegrationTest.scala       # Phase 2/3 — gated on Docker ZK
        └── fixtures/                           # hex-dump captures for Phase 1
```

The sbt entry sits in the root `build.sbt` next to `clientKafka`:

```scala
lazy val clientZookeeper = project
  .in(file("client-zookeeper"))
  .settings(
    name := "scalascript-client-zookeeper",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
```

**Zero new runtime dependencies.**  Jute is hand-rolled in
`scalascript.zookeeper.jute`; TCP is `java.net.Socket` (blocking on
a dedicated thread, same pattern used elsewhere in the repo where
async is not load-bearing); promises are `scala.concurrent.Promise`.

### 3.2 Public surface

```scala
package scalascript.zookeeper

import scala.concurrent.Future
import scala.concurrent.duration.Duration

case class ZkConfig(
  // ZK ensemble — "host1:2181,host2:2181,host3:2181".  Comma-separated.
  // The client picks one randomly, reconnects to another on TCP drop.
  endpoints:       List[String],
  sessionTimeoutMs: Long       = 30_000,
  connectTimeoutMs: Long       = 5_000,
  // Per-request response timeout.  ZK servers do not enforce this — we do.
  requestTimeoutMs: Long       = 10_000,
  // If true, automatically resume the session on TCP-level drop until the
  // server returns SessionExpired.  Disable for tests that want to see the
  // raw events.
  autoReconnect:   Boolean     = true,
)

case class ZkSession(id: Long, password: Array[Byte], negotiatedTimeoutMs: Long)

// Mirror of ZK's Stat record.  Only fields actually consumed by the
// leader-election adapter are exposed; the rest are folded into `raw`
// for forward compatibility.
case class ZkStat(
  czxid:          Long,
  mzxid:          Long,
  ctime:          Long,
  mtime:          Long,
  version:        Int,
  cversion:       Int,
  aversion:       Int,
  ephemeralOwner: Long,    // sessionId of the creator if ephemeral, else 0
  dataLength:     Int,
  numChildren:    Int,
  pzxid:          Long,
)

case class ZkNode(path: String, data: Array[Byte], stat: ZkStat)

enum ZkWatchEvent:
  case NodeCreated(path: String)
  case NodeDeleted(path: String)
  case NodeDataChanged(path: String)
  case NodeChildrenChanged(path: String)
  // Session-level events surface as watches too in ZK; we keep them here.
  case SessionConnected
  case SessionDisconnected
  case SessionExpired

enum CreateMode(val flag: Int):
  case Persistent          extends CreateMode(0x0)
  case Ephemeral           extends CreateMode(0x1)
  case PersistentSequential extends CreateMode(0x2)
  case EphemeralSequential extends CreateMode(0x3)

enum ZkError:
  case ConnectionLoss
  case SessionExpired
  case NodeExists(path: String)
  case NoNode(path: String)
  case BadVersion
  case NotEmpty
  case Other(code: Int, message: String)

trait ZkClient:
  def session: ZkSession

  // Returns the actual created path — for sequential modes ZK appends
  // a 10-digit counter, e.g. /leader/lease- -> /leader/lease-0000000007.
  def create(
    path: String,
    data: Array[Byte] = Array.empty,
    mode: CreateMode  = CreateMode.Persistent,
  ): Future[String]

  def getChildren(
    path:  String,
    watch: Boolean = false,
  ): Future[List[String]]

  def getData(
    path:  String,
    watch: Boolean = false,
  ): Future[ZkNode]

  def delete(path: String, version: Int = -1): Future[Unit]

  // Session + watch event stream.  See §3.4 for the choice rationale.
  def events: scalascript.runtime.Signal[ZkWatchEvent]

  def close(): Unit

object Zk:
  def connect(config: ZkConfig): Future[ZkClient]
  // Resume an existing session — used by the auto-reconnect loop, but
  // also exposed publicly for app-level persistence (save session id,
  // crash, reload, resume) if a consumer ever asks.
  def resume(config: ZkConfig, session: ZkSession): Future[ZkClient]
```

The shape mirrors `client-redis` / `client-kafka` conventions:
`Zk.connect(config)` factory, `ZkClient` trait with `Future`-returning
ops, `close()` for cleanup.  No effect-system intrusion — callers
that want `Async[A]` wrap themselves; the JVM ZK adapter only ever
runs on the JVM so plain `Future` is fine.

### 3.3 TCP framing and Jute serialization

ZooKeeper's wire protocol is **length-prefixed Jute records over TCP**
— that is the entire framing story.  Every message (client→server
and server→client) is:

```
+-----------------+----------------------------------+
| length: int32BE | <length> bytes of Jute payload   |
+-----------------+----------------------------------+
```

`length` is a big-endian 4-byte integer giving the byte count of
the following payload; the framer is allowed to read until exactly
that many bytes have arrived before parsing the payload.

Jute itself is a small Hadoop-derived schema language with these
primitives (only the ones we actually need are listed):

| Jute type | Wire encoding |
|-----------|--------------|
| `int`     | 4 bytes, big-endian |
| `long`    | 8 bytes, big-endian |
| `bool`    | 1 byte, `0` or `1` |
| `buffer`  | `int32 length` then `length` bytes (or `-1` for null) |
| `ustring` | `int32 length` then `length` bytes UTF-8 (or `-1` for null) |
| `vector<T>` | `int32 count` then `count` Ts |

That's it.  Records are concatenations of those primitives in a
fixed order.  No tags, no field IDs, no optionals — the order is
the schema.  This is why the client is small: there is no general
codec, only a fixed set of hand-written `encodeXxx` /
`decodeXxx` functions, one per record we send or receive.

The records we need for v1:

| Record | Direction | Opcode |
|--------|-----------|--------|
| `ConnectRequest`        | client → server | (no opcode; first frame on connect) |
| `ConnectResponse`       | server → client | (response to above)                  |
| `RequestHeader`         | client → server | varies (per-op)                      |
| `ReplyHeader`           | server → client | varies (per-op)                      |
| `PingRequest`           | client → server | `11`                                 |
| `CreateRequest`         | client → server | `1`                                  |
| `CreateResponse`        | server → client | `1`                                  |
| `DeleteRequest`         | client → server | `2`                                  |
| `GetChildrenRequest`    | client → server | `8`                                  |
| `GetChildrenResponse`   | server → client | `8`                                  |
| `GetDataRequest`        | client → server | `4`                                  |
| `GetDataResponse`       | server → client | `4`                                  |
| `WatcherEvent`          | server → client | `-1` (notification, no request)      |
| `Close`                 | client → server | `-11`                                |

Opcodes are stable across ZK 3.4–3.9; v1 targets the ZK **3.9.1**
wire (the current stable on `apache/zookeeper:3.9.1` Docker image
at the time of writing — same opcodes back to 3.7, so a 3.7 server
also works).  Confirmed against the upstream
`org.apache.zookeeper.ZooDefs.OpCode` constants and the WatcherEvent
record in `src/zookeeper.jute`.

The `xid` field on every `RequestHeader` is a client-generated
monotonically-increasing 32-bit integer; the server echoes it on
the matching `ReplyHeader` so the IO loop can demux concurrent
in-flight requests by xid.  Pings get a special reserved
xid `-2`; watch notifications get `-1`; auth packets get `-4`.
The pending-request table keys off `xid → Promise[Response]`.

### 3.4 Threading model

One **IO thread** per `ZkClient` (a JDK platform thread, not a
virtual thread — it spends its life blocked on `Socket.read`):

- Owns the socket.  Reads length-prefixed frames in a loop, parses
  the `ReplyHeader` (or detects watch notification by `xid == -1`),
  resolves the matching `Promise` from the pending-request table.
- Drives the ping scheduler: every `sessionTimeout / 3` ms it
  enqueues a `PingRequest`.

One **writer queue** — a thread-safe deque — that the IO thread
drains.  Public `create` / `getData` / etc. enqueue a serialized
frame plus a `Promise[Response]`; the IO thread writes the frame
and stores the promise under its xid.

Watch notifications and session-level events are dispatched to
user callbacks on a **virtual thread** (Loom) — never on the IO
thread, which must keep draining frames or the session times out.
Watch callbacks are fundamentally user code; long-running watch
handlers must not block protocol progress.

**Watches: `Signal[ZkWatchEvent]`, not a callback.**  ZK natively
fires a watch exactly once per registration; the client could
surface that either as a `WatcherCallback`-style trait method or as
a reactive `Signal[ZkWatchEvent]`.  We pick `Signal` for three
reasons:

1. **Consistency with the rest of the codebase.**
   `scalascript.runtime.Signal` is the standard reactive primitive
   here; cluster events, leader events, link-health events all use
   it.  An adapter built on `Signal[ZkWatchEvent]` composes
   trivially with the rest of `std/cluster/*`.
2. **One-shot semantics still work.**  After ZK fires a watch, the
   adapter re-arms it by calling `getChildren(path, watch = true)`
   again from the `Signal` subscriber.  The Signal abstraction does
   not pretend the watch is persistent; the spec is explicit that
   one-shot is the wire-level reality.
3. **Backpressure-free.**  ZK fires at most one watch per
   registration before the client must explicitly re-arm; there is
   no possible flood that a Signal cannot absorb.

Session-level events (`SessionConnected` / `SessionDisconnected` /
`SessionExpired`) flow through the same `Signal` so the adapter can
treat them uniformly.

### 3.5 Why not just depend on `org.apache.zookeeper`?

Same reason `client-redis` is a small wrapper over Lettuce instead
of pulling Spring Data, and `client-kafka` ships `kafka-clients`
alone without Confluent Schema Registry: **the project keeps
its `client-*` modules small, dependency-clean, and shippable as a
single JAR addition.**

| Concern | Apache ZK client | Pure-Scala client (this module) |
|---------|----------------|---------------------------------|
| Jar size | ~3.5 MB ZK + 2 MB Netty + SLF4J + Jline | ~50 KB |
| Transitive dependencies | Netty, Log4j shim, Jline, JLine native | None |
| Logging | SLF4J — fights with whatever the host app uses | `println` to `System.err` or a user-supplied callback |
| Thread count | 2 platform threads per connection + Netty pool | 1 platform thread + virtual-thread dispatch |
| Feature surface area | Full ZK (~80 ops, recipes, ACLs, multi, async) | ~6 ops |
| Forking patches for bugs | Must wait for upstream release | We own the code |
| Native deps | Netty epoll/kqueue native libs | None — `java.net.Socket` |

The 80%-of-Apache-ZK we *don't* need is the bulk of the surface
area: ACLs beyond `OPEN_ACL_UNSAFE`, the Curator-style recipes,
async/batch transactions, observer-mode discovery, the admin
endpoints, JMX hooks, SASL.  Re-implementing the 20% we *do* need
fits in well under 1000 lines of Scala (Apache's own client is
~12 000 LOC across `org.apache.zookeeper.*`), which is comparable
to the size of `client-postgres` already in the tree.

The other half of the answer is **operational**: ScalaScript apps
deployed in restricted environments (a regulated bank's GraalVM
native-image build, an embedded JVM, a customer-supplied container)
benefit from one fewer 3.5 MB transitive dependency, especially one
that historically has had its own CVE footprint
(Log4Shell touched ZK via SLF4J binding choice).  Hand-rolling
Jute over `java.net.Socket` is a one-day exercise; carrying
Apache's full dep tree is forever.

### 3.6 ZkLeaderCoordinator adapter shape (lives in std/cluster, not here)

The adapter lives in `std/cluster/coord-zookeeper.ssc`, not in this
module — `client-zookeeper` is pure JVM Scala; the adapter is a
ScalaScript module that *uses* the client and exposes the four
functions `useExternalCoordinator` expects.  See
[`cluster-raft.md`](cluster-raft.md) §5 for the contract:

```scalascript
val zk = Zk.connect(ZkConfig(endpoints = List("zk-1:2181", "zk-2:2181")))

useExternalCoordinator(
  acquireLease = (nid, timeoutMs) =>
    ZkLeaderCoordinator.tryAcquire(zk, "/myapp/leader", nid, timeoutMs),

  renewLease = nid =>
    // Ephemeral znodes auto-renew via the session ping loop — renew is
    // a no-op as long as the session is alive; returns true iff the
    // session is still SessionConnected.
    zk.sessionAlive,

  releaseLease = nid =>
    ZkLeaderCoordinator.release(zk, "/myapp/leader"),

  currentHolder = () =>
    ZkLeaderCoordinator.holder(zk, "/myapp/leader"),
)
```

The classic ZK leader-election recipe (see ZK Programmer's Guide,
"Recipes"): each candidate creates `/myapp/leader/n-` as
`EphemeralSequential`; the candidate whose suffix is the lowest is
leader; non-leaders watch the next-lower sibling and re-check on its
deletion.  Mapping to the four-function adapter:

- `acquireLease(nid, t)` — create `/myapp/leader/n-` ephemeral-seq
  with payload `nid`; list children; if our suffix is lowest return
  `true`; else set a watch on the predecessor and block up to `t` ms;
  return `true` if we become lowest before `t` elapses, else `false`.
- `renewLease(nid)` — ZK does this for us via session ping; we
  surface "true iff the session is still healthy" (the ephemeral
  znode is bound to the session — if session dies, the znode dies).
- `releaseLease(nid)` — delete our ephemeral child explicitly
  (faster than waiting for session expiry).
- `currentHolder()` — list children of `/myapp/leader`, find the
  lowest suffix, read its data (the `nid`).

This is the same shape Curator's `LeaderLatch` recipe uses; we
re-implement it in 30-ish lines of ScalaScript on top of the
six client ops listed in §1.

## 4. Migration

**Greenfield — no migration.**  There is no existing ZK client
code in the repository to migrate from; `client-zookeeper` lands as
a new sbt subproject under `build.sbt`.

The consumer side has one wiring point:

- `std/cluster/coord-zookeeper.ssc` is added as a sibling of the
  future `std/cluster/coord-etcd.ssc` and
  `std/cluster/coord-consul.ssc`.  It depends on
  `client-zookeeper` (JVM classpath); apps that don't import it
  pay zero dependency cost.
- `cluster-management.md` §11 Tier 3 "ZooKeeper LeaderCoordinator
  adapter — Blocked on a prerequisite" — that prerequisite is this
  client.  When this lands the Tier 3 item unblocks; when the
  adapter lands, [`cluster-raft.md`](cluster-raft.md) §5.2 gains
  its third row alongside etcd and Consul.

No existing module's behaviour changes.  Apps that elect their
leader via Bully, Raft, or etcd/Consul coordinators see no
difference; they only gain the ZK option.

## 5. Phases

Each phase is independently shippable per
[`AGENTS.md`](../AGENTS.md) Rule 3.  Estimates assume one focused
developer-day equals roughly six hours of code+test+review work;
total client effort lands in the **10–12 day** range.

### Phase 1 — Jute codecs (2 days)

`client-zookeeper/src/main/scala/scalascript/zookeeper/jute/*`.
Pure data round-trip; no network code.

- `Jute.encodeInt` / `decodeInt`, `encodeLong` / `decodeLong`,
  `encodeUstring` / `decodeUstring`, `encodeBuffer` /
  `decodeBuffer`, `encodeBool` / `decodeBool`, `encodeVector` /
  `decodeVector`.
- Records: `ConnectRequest`, `ConnectResponse`, `RequestHeader`,
  `ReplyHeader`, `PingRequest`, `CreateRequest`, `CreateResponse`,
  `DeleteRequest`, `GetChildrenRequest`, `GetChildrenResponse`,
  `GetDataRequest`, `GetDataResponse`, `WatcherEvent`, `Close`.
- `Opcodes` object with the integer constants from §3.3.

**Test:** `JuteCodecTest` — for each record, encode → decode →
assert equal.  At least one fixture per record uses a hex dump
captured from a real ZK 3.9.1 server (or hand-built if the WebFetch
path stays unavailable) to catch encoder bugs that would round-trip
but not match wire reality.

**Ships:** the codec module compiles standalone; no public API
exposed to `scalascript.zookeeper.*` yet.

### Phase 2 — TCP transport + simple ops (3 days)

`client-zookeeper/src/main/scala/scalascript/zookeeper/transport/*`
plus the public `ZkClient` skeleton.

- `ZkTransport.connect(endpoint, sessionTimeout)` — TCP connect,
  write `ConnectRequest`, read `ConnectResponse`, store
  `ZkSession`.
- `IoLoop` — reader thread + writer queue + pending-request map
  keyed by xid.
- Ping scheduler — fires every `negotiatedTimeout / 3` ms.
- Public `ZkClient.create`, `getData`, `delete` returning `Future`s.
  `getChildren` lands here without watches.
- `close()` — sends a Close (`opcode = -11`) and shuts down the IO
  thread.

**Test:** integration test gated on a Docker ZK container, same
pattern as `client-kafka`'s `KafkaClientTest` and `client-redis`'s
`RedisClientTest` — `assume(hasZk, "ZooKeeper not available on
localhost:2181")`.  Verified by reading those test files: both
gate on a probe at class-init time (try to connect; if it fails
flag the suite as skipped).  Compose file:

```yaml
zookeeper:
  image: zookeeper:3.9.1
  ports: ["2181:2181"]
```

Tests cover:

- Connect → assert `session.id > 0` and `session.timeout > 0`.
- Create `/test-{nanos}` as `Persistent`; read it back; delete it.
- Create ephemeral; close session; verify the znode vanishes from
  the second connection.
- Concurrent in-flight ops — fire 100 `create`s in parallel; assert
  all complete; assert xid demux did not cross-wire any response.

**Ships:** the client can do non-watching CRUD; the
`ZkLeaderCoordinator` still cannot run.

### Phase 3 — Watches + session lifecycle (3 days)

- `getChildren(path, watch = true)` and `getData(path, watch = true)`
  install a one-shot watch with the request.
- `ZkClient.events: Signal[ZkWatchEvent]` — server-pushed
  `WatcherEvent` frames are decoded and emitted; the same Signal
  carries `SessionConnected` / `SessionDisconnected` /
  `SessionExpired`.
- Auto-reconnect loop — on TCP drop, the IO thread loops over the
  ensemble endpoints, reconnects, resends `ConnectRequest` with the
  saved `sessionId` and `sessionPassword`.  If the server returns a
  zero `sessionId` the session has expired; emit `SessionExpired`
  and stop.
- The pending-request map is replayed across reconnect — requests
  that were in flight when the link dropped get `ConnectionLoss`
  errors so callers can decide whether to retry; the alternative
  (silently re-issuing) is unsafe for `create`.

**Test:** integration tests for:

- Create a parent path; set a children watch; create a child;
  assert `NodeChildrenChanged` arrives on the Signal.
- Kill the ZK container (`docker kill`); wait for
  `SessionDisconnected`; restart the container; assert
  `SessionConnected` arrives and the same `sessionId` is reused.
- Configure `sessionTimeoutMs = 5_000`; kill ZK; wait > 5 s;
  restart; assert `SessionExpired` (the server refused the resume
  because the lease elapsed).

**Ships:** the client surface is feature-complete for the adapter.
No new ops will be added in Phase 4.

### Phase 4 — ZkLeaderCoordinator adapter (2 days)

`std/cluster/coord-zookeeper.ssc` — a pure ScalaScript module on
top of the JVM client.

- The four-function block from §3.6.
- The classic ZK leader-election recipe (lowest-suffix sibling
  - watch the predecessor on loss).
- Wires through `useExternalCoordinator` per
  [`cluster-raft.md`](cluster-raft.md) §6.

**Test:** end-to-end leader-election test under `e2e/` — three
ScalaScript JVM processes, one Docker ZK, all three call
`useExternalCoordinator(...)` against `/test-{nanos}/leader`.
Assertions:

- Exactly one node observes `LeaderElected(self)` within 2 s of
  start.
- The other two observe `LeaderElected(<the winner's nid>)` and
  not their own nodeId.
- `kill -9` the leader; one of the survivors becomes leader within
  `sessionTimeoutMs` ms (default 30 s — the test uses 5 s for
  speed).
- The newly elected node sees `LeaderElected(self)`; the other
  survivor sees `LeaderElected(<the new winner>)`.

**Ships:** Tier 3 first item from
[`cluster-management.md`](cluster-management.md) §11 lands.
The third row in [`cluster-raft.md`](cluster-raft.md) §5.2 flips
from "needs client" to "shipped".

### Phase totals

| Phase | Work | Days |
|-------|------|------|
| 1 | Jute codecs | 2 |
| 2 | TCP transport + CRUD | 3 |
| 3 | Watches + session lifecycle | 3 |
| 4 | `ZkLeaderCoordinator` adapter | 2 |
| **Total** | | **10** |

A two-week sprint with one developer covers the whole arc; phases
are independently mergeable, so a partial landing (just Phase 1+2
say, while the adapter waits) is a real option.

## 6. Testing strategy

Three tiers, mirroring how the existing client-* modules test:

### Phase 1 — pure unit tests, no Docker

`JuteCodecTest` — hex-dump fixtures in `src/test/resources/fixtures/*.hex`
captured from a real ZK 3.9.1 server (one-time `tcpdump` capture
during a manual interaction).  For each fixture, decode → assert
matches the corresponding case class, encode → assert byte-for-byte
matches the fixture.  Round-trip is necessary but not sufficient —
catching a missing field or wrong endianness needs the real-server
reference.

If WebFetch is unavailable when authoring the fixtures, they can be
captured from a local Docker ZK with `nc -k -l 5555 | xxd` between
client and server.  Either path produces the same artifact.

### Phase 2 + 3 — integration, gated on Docker

`ZkClientIntegrationTest` uses the same idiom as
`RedisClientTest` (`/Users/sergiy/.../client-redis/.../RedisClientTest.scala:15`):

```scala
private lazy val hasZk: Boolean =
  try
    val client = Await.result(Zk.connect(ZkConfig(endpoints = List("localhost:2181"))), 5.seconds)
    client.close()
    true
  catch case _: Throwable => false

test("create + get"):
  assume(hasZk, "ZooKeeper not available on localhost:2181")
  ...
```

`KafkaClientTest` (`client-kafka/src/test/.../KafkaClientTest.scala:21`)
uses the identical pattern — probe on init, `assume(...)` in every
test.  We adopt it for ZK.

The Docker container is run manually for local dev (`docker run
--rm -p 2181:2181 zookeeper:3.9.1`) and via the existing CI Docker
infrastructure for CI runs.  No `testcontainers-scala` dependency
— matches Kafka/Redis convention.

### Phase 4 — end-to-end

Under `e2e/`, a shell script orchestrates: one `docker run
zookeeper:3.9.1` in the background, three `ssc` JVM processes
each running an `actors-cluster-zk-coord.ssc` script that calls
`useExternalCoordinator(...)` with the `coord-zookeeper.ssc`
adapter, then asserts via the `ssc cluster status` JSON endpoint
that exactly one node reports itself as leader.  Same orchestration
shape `cluster-raft.md` §9 defers under "Multi-process integration
test" — the non-blocking `serveAsync` intrinsic landed in v1.23,
which removes the blocker.

## 7. Open questions

These need answers before Phase 1 starts; they do not block this
spec from landing.

- **Wire-protocol version target — 3.7 or 3.9?**  Opcodes are
  stable across the range; 3.9.1 is the current stable upstream.
  Going with 3.9 by default; downgrading is a one-line config
  matter if a deployment site is stuck on an older ensemble.
  Resolution: target 3.9, document that 3.7 also works.

- **Chroot namespace support.**  ZK lets the connect string carry
  a `/path` suffix that prefixes every subsequent path — useful
  for multi-tenancy on a shared ZK ensemble.  Implementation cost
  is one line per outbound request (prefix the path); the
  question is whether v1 ships it or defers.  Resolution leaning
  *defer to v1.1*: the leader-election adapter uses one well-known
  path and the multi-tenant deployment pattern can use distinct
  paths instead of distinct chroots.  Revisit if a real consumer
  asks.

- **Auth — digest, SASL, both, neither?**  ZK supports several
  auth schemes (`world:anyone`, `digest:user:pass`, `sasl:user`,
  IP allowlist).  Leader-election in a trusted internal network
  needs none; production ZK deployments often need digest at
  minimum.  Resolution leaning *digest opt-in for v1, SASL deferred*:
  ship `ZkConfig(... auth = None | DigestAuth(user, pass))`; SASL
  (Kerberos) is significantly heavier and only matters for
  enterprise deployments that probably already use the official
  client.

- **Pending-request behaviour across reconnect.**  Phase 3
  proposes failing them with `ConnectionLoss`.  An alternative
  is to silently re-issue idempotent reads (`getChildren` /
  `getData`) — but that requires per-op idempotency reasoning
  and risks double-issuing in adversarial timing.  Resolution
  leaning *fail fast*: the adapter retries at the application
  layer via the `setAutoReelect(true)` mechanism in
  [`cluster-raft.md`](cluster-raft.md) §6; the client should not
  hide partial failures.

- **`Signal` vs `EventStream` for watches.**  §3.4 picks `Signal`
  because that is what the rest of `std/cluster/*` already uses.
  If a refactor of the reactive primitives happens before this
  client lands (none currently planned), revisit.

## 8. Promotion criteria

This document moves from "deferred" to "ship Phase 1 next sprint"
when:

1. **A real .ssc app on JVM** asks for ZK-backed leader election —
   typically because the deployment already runs a ZK ensemble for
   Kafka / HBase / other services, and operating teams prefer
   re-using it over standing up etcd or Consul.
2. **A community-package author** ships a third-party
   `scalascript-zookeeper` and the demand signal suggests folding
   it into `client-*`.

Until then, this spec sits next to
[`cluster-raft.md`](cluster-raft.md) (deferred), as the place
the question is answered if it ever fires.

Per [`cluster-raft.md`](cluster-raft.md) §8 / Phase 3b, etcd is
first; Consul second; ZooKeeper third.  Demand will likely follow
that ordering — but the spec lands ahead of the work either way,
because cheap to write now, expensive to design under deadline.

## 9. Why this document exists at all

Three reasons, parallel to the equivalent sections in
[`cluster-management.md`](cluster-management.md) and
[`cluster-raft.md`](cluster-raft.md):

1. **Unblock Tier 3.**  `cluster-management.md` §11 lists the ZK
   adapter as "blocked on a `client-zookeeper` prerequisite".  This
   spec is that prerequisite, in document form.  When the trigger
   fires, code follows; the design is already done.
2. **Lock the "no Apache ZK dep" decision.**  §3.5 is the
   one-paragraph answer to the reasonable question "why not just
   use the official client?"  Without that answer in writing, the
   first PR review will re-litigate it.
3. **Bound the scope.**  ZooKeeper has 80 operations; we want six.
   §1 says explicitly which six, and §2 says explicitly what we
   will refuse to add.  This prevents the v1 PR from drifting into
   "while we're at it, let's add `setData` / `multi` / `addWatch`"
   territory.

It is **not** a commitment to build the client.  It is a
commitment to know what we would build if and when.
