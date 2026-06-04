# Actors Phase 2 + Phase 3 — Architecture & Implementation Design

Design document covering v1.6 Phase 2 (supervision) and Phase 3
(distributed actors over WS).  Written after codebase exploration
(2026-05-18); captures the exact state of the runtime, all
architectural decisions, and the implementation plan.

---

## Current state (Phase 1 — landed)

Phase 1 primitives are built into the compiler as special forms,
not std-library functions.  The surface is:

```
runActors { body }
spawn { () => body }: Pid
self(): Pid
pid ! msg
receive { case … }
receive(timeout = N) { case … }: Option[A]
exit(pid, reason)
```

### Pid representation (per backend)

| Backend | Wire type |
|---------|-----------|
| Interpreter | `Value.InstanceV("Pid", Map("id" -> Value.IntV(localId: Long)))` |
| JVM (emitted Scala) | `case class _Pid(id: Long)` |
| JS (emitted JS) | `{ _type: 'Pid', id: Number }` |

Pid is purely local — no nodeId, not serializable.

### ActorRuntime (interpreter only)

```scala
private class ActorRuntime:
  val mailboxes = mutable.LongMap.empty[mutable.Queue[Value]]
  val blocked   = mutable.LongMap.empty[
    (List[Case], Env, Value => Computation, Option[Long], Boolean)]
  val pending   = mutable.LongMap.empty[Computation]
  val ready     = mutable.Queue.empty[Long]
  var nextId    = 0L
  var currentId = -1L
```

`links`, `monitors`, `trapExit` fields are **absent** — they exist
in the MILESTONES spec but are not yet in the code.  The current
`exit` handler removes the actor but does **not** propagate EXIT
signals.

### handleActorOp (interpreter): implemented ops

`spawn`, `self`, `send`, `receive`, `receive_t`, `exit`.
Missing: `link`, `monitor`, `demonitor`, `trap_exit`.

---

## Phase 2 — Supervision

### Data structures to add to ActorRuntime

```scala
// Bidirectional: links(a) contains b iff links(b) contains a
val links     = mutable.LongMap.empty[mutable.Set[Long]]
// watchedId → Map(monRef → observerId)
val monitored = mutable.LongMap.empty[mutable.Map[Long, Long]]
// observerId → Map(monRef → watchedId)  (reverse index for demonitor)
val monitorOf = mutable.LongMap.empty[mutable.Map[Long, Long]]
val trapExitM = mutable.LongMap.empty[Boolean]
var nextMonRef = 0L
```

### New handleActorOp cases (interpreter)

```
"link" ->
  val targetId = extractPidId(args(0))
  links.getOrElseUpdate(id, mutable.Set.empty) += targetId
  links.getOrElseUpdate(targetId, mutable.Set.empty) += id
  k(UnitV)

"monitor" ->
  val targetId = extractPidId(args(0))
  val ref = nextMonRef; nextMonRef += 1
  monitored.getOrElseUpdate(targetId, mutable.Map.empty)(ref) = id
  monitorOf.getOrElseUpdate(id, mutable.Map.empty)(ref) = targetId
  k(IntV(ref))   // MonitorRef is an opaque Long

"demonitor" ->
  val ref = args(0).asLong
  monitorOf.get(id).flatMap(_.remove(ref)).foreach { targetId =>
    monitored.get(targetId).foreach(_.remove(ref))
  }
  k(UnitV)

"trap_exit" ->
  trapExitM(id) = args(0).asBool
  k(UnitV)
```

### killActor — EXIT / DOWN propagation

When actor `id` dies with `reason`:

```
1. Remove from pending / blocked / mailboxes / ready-queue (stale ids filtered at dispatch).

2. For each linked actor `lId` in links(id):
   - Remove the link entry in links(lId).
   - If trapExitM(lId) == true:
       Enqueue Exit(from=Pid(id), reason=reason) into mailboxes(lId)
       Wake lId if blocked (tryDeliver).
   - Else if reason != "normal":
       Recursively killActor(lId, reason)  // cascade kill

3. For each (monRef, observerId) in monitored(id):
   - Enqueue Down(ref=monRef, from=Pid(id), reason=reason) into mailboxes(observerId)
   - Wake observerId if blocked.

4. Remove links(id), monitored(id), monitorOf(id), trapExitM(id).
```

Exit message shape (matches MILESTONES + std/actors.ssc):
```
InstanceV("Exit",  Map("from" -> Pid, "reason" -> Value))
InstanceV("Down",  Map("ref" -> IntV(monRef), "from" -> Pid, "reason" -> Value))
```

### ScalaScript surface

```
extern def link(pid: Pid): Unit
extern def monitor(pid: Pid): MonitorRef
extern def demonitor(ref: MonitorRef): Unit
extern def trap_exit(on: Boolean): Unit
```

Registered in `InterpreterIntrinsics` (interpreter), `JvmIntrinsics` / `JsIntrinsics`
(backends) via the existing `extern def` → `IntrinsicImpl` pipeline.

### JVM backend

Add to the `actorRuntime` Scala string literal emitted by `JvmGen.scala`
(currently ~5007-5286):

```scala
// New fields on _RunActors state object
val _links     = LongMap[mutable.Set[Long]]()
val _monitored = LongMap[mutable.Map[Long, Long]]()   // watchedId → monRef→observerId
val _monitorOf = LongMap[mutable.Map[Long, Long]]()   // observerId → monRef→watchedId
val _trapExit  = LongMap[Boolean]()
var _nextMonRef = 0L
```

The `handleActorOp` closure already exists in the emitted string —
add cases for `"link"`, `"monitor"`, `"demonitor"`, `"trap_exit"`.
Update the existing `"exit"` case to call a new `_killActor` helper
that propagates EXIT/DOWN into mailboxes.

### JS backend

Same pattern in `JsGen.scala`'s `_runActors` JS runtime string:

```js
const _links     = new Map();  // actorId → Set<actorId>
const _monitored = new Map();  // watchedId → Map<monRef, observerId>
const _monitorOf = new Map();  // observerId → Map<monRef, watchedId>
const _trapExit  = new Map();  // actorId → bool
let _nextMonRef  = 0;
```

### std/actors.ssc

`Supervisor` is already sketched in the milestone; it uses `link`,
`trapExit`, `spawn`, `receive`, `exit`.  Once Phase 2 primitives
land, `Supervisor.start` can be implemented as pure ScalaScript
on top of them with no new runtime changes.

### Conformance tests (Phase 2)

```
actors-supervision
  ✓ link: crash propagates to linked actor (reason != "normal")
  ✓ trap_exit: EXIT becomes a message instead of crash
  ✓ monitor: Down delivered when watched actor exits normally
  ✓ monitor: Down delivered when watched actor crashes
  ✓ demonitor: no Down after demonitor
  ✓ OneForOne supervisor: worker crashes → restarted once
  ✓ MaxRestarts exceeded → supervisor crashes
```

---

## Phase 3 — Distributed actors via WS

### Architectural decisions (recorded 2026-05-18)

| Decision | Choice | Rationale |
|---|---|---|
| `startNode` binding | WS route on existing `serve()` | Reuses established WS stack; no second TCP listener |
| Backends | INT + JVM + JS (all three) | Conformance spec requires cross-backend |
| Scope | Full: core + register/whereis + heartbeat + node-down | One coherent milestone |
| Serializer | JSON (binary uPickle deferred) | Debuggable; cross-language compatible |

Binary wire follow-up: [`specs/distributed-wire-protocol.md`](distributed-wire-protocol.md)
defines the planned opt-in `json` / `msgpack` / `cbor` negotiation layer for
actors and the rest of ScalaScript's internal distributed runtime. This
document keeps the current JSON `ssc-actors-v1` contract as the implemented
baseline.

### Node identity and Pid extension

`startNode("name@host:port")` registers the node's identity in a
global (per-process) variable.  It does NOT open a TCP listener by
itself; instead it registers a special WS handler at path
`/_ssc-actors` on the active server.

**Pid changes — all three backends:**

```
Interpreter: InstanceV("Pid", Map("nodeId" -> StringV(nodeId), "localId" -> IntV(id)))
             nodeId = "" means "local" (backward-compatible default)
JVM:         case class _Pid(nodeId: String = "", id: Long)
JS:          { _type: 'Pid', nodeId: '', id: Number }
```

Send path — updated `"send"` case:
```
if pid.nodeId == "" || pid.nodeId == localNodeId:
  local delivery (existing path)
else:
  serialize msg → JSON envelope
  peerChannels(pid.nodeId).send(envelope)
```

### Wire protocol (envelope format)

All peer-to-peer messages are JSON text frames over the `/_ssc-actors`
WebSocket:

```json
{ "t": "msg",
  "to": { "nodeId": "node2@host:9200", "localId": 5 },
  "from": { "nodeId": "node1@host:9100", "localId": 3 },
  "body": <serialized Value> }

{ "t": "reg",   "name": "myActor", "pid": { "nodeId": "...", "localId": 5 } }
{ "t": "where", "name": "myActor", "replyTo": { "nodeId": "...", "localId": 3 } }
{ "t": "found", "name": "myActor", "pid": { "nodeId": "...", "localId": 5 }, "ok": true }
{ "t": "ping" }
{ "t": "pong" }
{ "t": "down",  "node": "node2@host:9200" }   // node-down notification (internal use)
```

Subprotocol advertised during WS handshake: `"ssc-actors-v1"`.
Mismatched version → peer rejects with HTTP 400 before any resources
are allocated.

### Value → JSON serialization

```
IntV(n)                 → { "$t": "i", "v": n }
DoubleV(d)              → { "$t": "d", "v": d }
StringV(s)              → { "$t": "s", "v": s }
BoolV(b)                → { "$t": "b", "v": b }
UnitV                   → { "$t": "u" }
ListV(vs)               → { "$t": "l", "v": [...] }
MapV(kvs)               → { "$t": "m", "v": [[k,v], ...] }
TupleV(vs)              → { "$t": "tp", "v": [...] }
InstanceV(cls, fields)  → { "$t": "o", "cls": cls, "f": { field: val, ... } }
Pid(nodeId, localId)    → { "$t": "pid", "n": nodeId, "id": localId }
FunV / NativeFnV        → runtime error: "functions cannot be sent to remote nodes"
```

For JVM backend: emit `_serializeValue` / `_deserializeValue` helper
functions in the runtime preamble.  For JS backend: same in the
JS runtime literal.  ujson (already in deps via upickle) provides
the JSON library on JVM; native JSON on JS.

### Thread-safe remote inbox (interpreter)

The actor scheduler is single-threaded.  Incoming WS messages arrive
on JDK `HttpClient` pool threads.  Solution: add a separate
`remoteInbox: java.util.concurrent.ConcurrentLinkedQueue[(Long, Value)]`
to `ActorRuntime`.  The scheduler drains it at the top of each
iteration before checking `ready`.

```scala
// top of scheduler while-loop:
while remoteInbox.nonEmpty do
  val (targetId, msg) = remoteInbox.poll()
  rt.mailboxes.get(targetId).foreach { mb =>
    mb.enqueue(msg)
    // wake if blocked
    rt.blocked.get(targetId) match { ... }
  }
```

`wsConnect`'s `onMessage` callback runs on a JDK thread and calls
`remoteInbox.offer((targetId, value))` then interrupts the scheduler
thread if it is sleeping (via an `AtomicReference[Thread]`).

### Peer channel management

```scala
// Global, per-Interpreter-process
val peerChannels = new ConcurrentHashMap[String, PeerChannel]()
var localNodeId  = ""   // "" = not a distributed node

case class PeerChannel(nodeId: String, send: String => Unit, close: () => Unit)
```

`connectNode(url, token, serializer)` — interpreter:
1. Calls `wsConnect(url, Map("Authorization" -> s"Bearer $token"), List("ssc-actors-v1"))`
2. In the WS handler: sets `peerChannels(peerNodeId) = PeerChannel(...)` using the nodeId
   received in the first handshake frame.
3. `onMessage` deserializes the envelope and dispatches:
   - `"msg"` → `remoteInbox.offer(targetLocalId, deserializedValue)`
   - `"reg"` / `"where"` / `"found"` → registry operations
   - `"ping"` → send `"pong"`
   - `"down"` → node-down propagation (see below)
4. `onClose` → node-down propagation + remove from `peerChannels`.

### `startNode` WS route

`startNode("name@host:port")`:
1. Sets `localNodeId = "name@host:port"`.
2. Registers `onWebSocket("/_ssc-actors")` with the handler that:
   - Accepts only connections with subprotocol `"ssc-actors-v1"`.
   - Reads the first frame (handshake: `{ "nodeId": "..." }`).
   - Registers the sender in `peerChannels`.
   - Dispatches subsequent frames identically to `connectNode`'s `onMessage`.

This requires that `serve(port)` is already (or concurrently) called
so the WS stack is active.  Typical pattern:

```
startNode("node1@localhost:9100")
connectNode("ws://localhost:9200/_ssc-actors")
serve(9100)   // blocks; startNode's route is live now
```

### `register` / `whereis`

Per-node registry (interpreter):
```scala
val nodeRegistry = new ConcurrentHashMap[String, Long]()  // name → localId

"register" → nodeRegistry.put(name, localId); k(UnitV)
"whereis"  → local lookup → k(Option[Pid])

// Cross-node whereis:
"whereis_remote" → send { "t":"where", "name": name, "replyTo": self } to peer
                   then block actor with receive(timeout = 5000) waiting for "found" reply
```

### Heartbeat and node-down

Each peer channel sends a `{ "t": "ping" }` every 30 seconds and
expects a `{ "t": "pong" }` back within 10 seconds.

On timeout or WS close, the `onClose` / heartbeat timeout fires:
1. Remove from `peerChannels`.
2. Collect all remote Pids (from `links` and `monitored`) whose
   `nodeId` matches the dead node.
3. For each linked actor that had a remote link: fire EXIT or kill
   (same as Phase 2 killActor, but from the link-maintenance side).
4. For each monitor watching a now-dead remote Pid: enqueue `Down`.

Remote link tracking needs a new reverse index:
```scala
// nodeId → Set of local actor ids that have links to that node
val remoteLinks    = new ConcurrentHashMap[String, mutable.Set[Long]]()
// nodeId → Map(monRef → localObserverId) for remote monitors
val remoteMonitors = new ConcurrentHashMap[String, mutable.Map[Long, Long]]()
```

These are populated on `link(remotePid)` / `monitor(remotePid)` and
cleared when the node goes down.

### Conformance tests (Phase 3)

```
actors-distributed-basic
  ✓ 2-node ping-pong: INT↔INT
  ✓ cross-backend: INT↔JVM

actors-distributed-supervision
  ✓ link across nodes: remote actor dies → Exit delivered locally
  ✓ kill one node → Down fires on the other (via heartbeat timeout)

actors-distributed-registry
  ✓ register/whereis: local lookup
  ✓ whereis across nodes: query sent + reply received

actors-distributed-serializer
  ✓ all Value types round-trip through JSON serializer (all backends)
```

### Implementation sequence

1. **Phase 2**: `link`/`monitor`/`demonitor`/`trap_exit` + propagation
   in all three backends.  Conformance: `actors-supervision`.
2. **Phase 3 — core**: Pid extension (nodeId), Value→JSON serializer,
   `startNode`/`connectNode`, remote send routing.  Conformance:
   `actors-distributed-basic`.
3. **Phase 3 — registry + node-down**: `register`/`whereis`,
   heartbeat, node-down propagation, remote link/monitor cleanup.
   Conformance: `actors-distributed-supervision`,
   `actors-distributed-registry`.

Each step merges independently and is useful in isolation.

---

## WS client architecture (key facts for Phase 3)

From codebase exploration of `wsConnect` across all backends:

- **No application-level framing** — raw WS text frames.  Phase 3
  must implement its own JSON envelope.
- **One WS per peer** — no built-in sub-channel multiplexing.  Actor
  messages for all actors between two nodes share one WS connection.
- **JVM/interpreter `onMessage` runs on JDK pool threads** — NOT the
  scheduler thread.  All delivery into actor mailboxes must go through
  the thread-safe `remoteInbox`.
- **JS `recv()` is non-blocking** — Phase 3 WS handling must use
  `onMessage` callback style on JS, not `recv()`.
- **No reconnect** — reconnect logic (if desired) must be owned by
  the actor framework.
- **Auth**: `wsConnect(url, Map("Authorization" -> "Bearer …"))` passes
  headers in the HTTP upgrade; `onWebSocketAuth` on the server side
  provides `ws.user`.
- **Subprotocol negotiation** works on all three backends — use
  `"ssc-actors-v1"` to reject version-mismatched peers at handshake.
