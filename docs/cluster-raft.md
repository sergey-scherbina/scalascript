# Cluster leader election — strong-consistency protocols

Status: **deferred until a trigger condition fires** (see §3).
Spec lives in this file so that when we promote, we are not designing
from scratch under deadline pressure.

Companion to [`docs/cluster-management.md`](cluster-management.md)
(the deferred-pieces list), which routes all "strong-consistency
leader election" questions here.

## 1. Why this exists at all

v1.23 ships **Bully** as the built-in leader-election protocol:
`electLeader` / `currentLeader` / `subscribeLeaderEvents` /
`setAutoReelect`.  Bully is enough for v1.x trusted-deployment use
cases — small clusters, cooperative peers, eventual consistency on
the leadership view.  Apps that elect "the singleton work-queue
owner" or "the schema-migration runner" do not need more.

Bully fails when:

- **Symmetric partitions split the cluster.**  Each side picks the
  locally-highest nodeId as leader.  When the partition heals, two
  nodes claim leadership at the same `coordinator` epoch and the
  app sees two `LeaderElected` events with different IDs.  Bully
  alone cannot tell whose claim wins.
- **The leader role guards exclusive resources.**  Two nodes
  believing they are leader can each issue a DB migration or hand
  out the same job ID.  Bully gives "probably one leader most of
  the time" but not "at most one leader at any time".
- **The app needs an authoritative log of leadership changes.**
  Bully fires events but does not record an ordered history; on
  restart the app has no way to ask "who was leader when X
  happened?"

When any of those start mattering, the app needs a real consensus
protocol or an external coordinator — both promoted out of this doc.

## 2. Goals and non-goals

In scope:

- A **Raft-based leader-election protocol** (`std/cluster/raft.ssc`)
  that uses the existing v1.6 Phase 3 actor messaging.  At most one
  leader per term; quorum-required claims; election after timeout;
  log of `(term, leaderId)` pairs queryable on demand.
- **External-coordinator adapter trait** with concrete implementations
  for etcd, Consul, ZooKeeper.  Apps pick one via configuration.
  The runtime delegates leadership to the coordinator; on lease loss
  it fires `LeaderLost` and steps down.
- **Coexistence with Bully**: the choice of protocol is an
  application-level configuration switch, not a fork of the runtime.
  Existing `electLeader` / `currentLeader` / `subscribeLeaderEvents`
  surface continues to work; internally it routes to whichever
  protocol is active.

Out of scope, forever:

- **Implementing a full Raft log replication.**  We only do leader
  election; the data plane stays in the app's hands.  Adding log
  replication would mean re-implementing a small key-value store,
  which is what `clusterConfigSet` already does at a much weaker
  consistency level.  Apps that need log-replication should use an
  external KV store (etcd / FoundationDB / SQLite-with-Litestream).
- **Multi-Raft / sharded consensus.**  One Raft group per cluster
  is all v1.x will ever ship.
- **Byzantine fault tolerance.**  Crash-stop model only; peers are
  trusted not to send malicious envelopes.

## 3. Promotion criteria

Promote one of the variants when *any* of these fire for a
specific deployment:

1. **A production .ssc app running on 5+ nodes reports a
   split-brain incident** caused by Bully.  Two leaders simultaneously
   issued conflicting commands; the bug is reproducible.
2. **A regulated environment (finance, healthcare, etc.) requires
   an auditable leadership log.**  "At most one leader per epoch,
   recorded" is a compliance requirement.
3. **A community-package author** ships a third-party
   `scalascript-raft` library that meets the design constraints in
   this document, and demand suggests folding it into std.
4. **An infrastructure team standardises on etcd / Consul** and
   wants ScalaScript apps to plug into the existing coordinator.

The Raft variant ships first only if conditions 1 or 2 fire.
The external-coordinator adapters ship first only if condition 3
or 4 fires.  Both variants share the API surface in §6, so the order
does not lock anyone out.

## 4. Design — Raft leader election

We implement only the **leader election sub-protocol** of Raft — see
the original paper (Ongaro & Ousterhout, 2014) §5.2.  No log
replication.  No snapshotting.  No membership changes.  Membership
is whatever `clusterMembers()` reports at the moment of the vote.

### 4.1 State per node

```text
currentTerm    : Long              // monotonically increasing
votedFor       : Option[String]    // nodeId we voted for in currentTerm
state          : Follower | Candidate | Leader
lastHeartbeat  : Long              // wall-clock ms; reset on every leader heartbeat
electionDue    : Long              // randomised deadline; reset on heartbeat
leaderId       : Option[String]    // None until first AppendEntries from a leader
```

Persisted to disk (one small file per node) so a restart cannot
double-vote in the same term.  Persistence path is configurable;
default `<workDir>/.ssc-raft-state.json`.

### 4.2 RPCs

Two envelopes on the existing peer WebSocket:

```text
{"t":"raft_vote_req", "from":<nid>, "term":<n>, "lastLogTerm":<n>}
{"t":"raft_vote_resp","from":<nid>, "term":<n>, "granted":<bool>}
{"t":"raft_append",   "from":<nid>, "term":<n>}    // heartbeat, no entries
```

`lastLogTerm` is sent but always 0 in our log-less variant; reserved
for future expansion if we ever ship log replication.

### 4.3 Election

```text
on election timeout:
  state       = Candidate
  currentTerm = currentTerm + 1
  votedFor    = Some(selfNode())
  votes       = 1
  electionDue = now + random(150..300 ms)
  broadcast raft_vote_req

on raft_vote_resp(term, granted):
  if term == currentTerm and granted: votes += 1
  if votes > clusterMembers().size / 2:
    state    = Leader
    leaderId = Some(selfNode())
    fire LeaderElected(selfNode())
    start heartbeat loop

on heartbeat tick (Leader only, every 50 ms):
  broadcast raft_append(term = currentTerm)

on raft_vote_req(from, term, lastLogTerm):
  if term < currentTerm: respond granted=false
  else:
    if term > currentTerm: currentTerm = term; votedFor = None; state = Follower
    granted =
      votedFor.isEmpty || votedFor.contains(from)
    if granted: votedFor = Some(from); reset electionDue
    respond granted

on raft_append(from, term):
  if term >= currentTerm:
    currentTerm   = term
    state         = Follower
    leaderId      = Some(from)
    lastHeartbeat = now
    electionDue   = now + random(150..300 ms)
    if previousLeaderId != Some(from):
      fire LeaderElected(from)
```

The random election timeout window (150–300 ms) is the standard
Raft tuning — long enough to absorb network jitter, short enough
that elections complete within ~1 s.  Heartbeat interval (50 ms)
must be ≤ 1/3 of the lower bound.

### 4.4 Quorum and partitions

A candidate must collect a **strict majority** of
`clusterMembers().size + 1` (including self) — the `+1` because
`clusterMembers()` excludes self.  In a 5-node cluster, 3 votes
including self win.

When a partition isolates the minority side, no candidate in that
partition can collect a majority and the minority side simply never
elects a leader.  Apps subscribed to `LeaderLost` see the event
fire (the leader's peer link drops); `currentLeader()` returns
empty string until the partition heals and the majority side's
leader's heartbeats reach the minority again.

This is the key property Bully lacks: **the minority cannot elect
its own leader, so dual-leader split-brain is impossible.**

## 5. Design — external coordinator adapters

Apps that already run an external coordinator (etcd, Consul,
ZooKeeper) plug into it instead of running Raft inside ScalaScript.
The runtime acquires a coordinator-managed lease; the lease holder
is the leader.

### 5.1 Adapter shape

Landed as **four function arguments to `useExternalCoordinator`**
rather than a wrapping case class or trait.  Reasons:

- The runtime calls back into adapter code from its generated
  `_runActors` body.  Calling a record's method via `productElement`
  works on JVM but the interpreter's import path doesn't yet expose
  user-defined case classes to other modules (same limitation as
  `ChildSpec` in `std.actors`).  Four-args avoids that limitation —
  apps don't need an import to construct an adapter.
- The fifth `onLeaderChanged` callback from the original spec is
  deferred — apps poll `currentHolder()` from a timer for the same
  effect; it can come back as a fifth function arg later if real
  consumers need push.

```scala
extern def useExternalCoordinator(
  // Try to acquire the leader lease.  Blocks up to `timeoutMs`.
  // Returns true if we became leader, false otherwise.
  acquireLease:  (String, Long) => Boolean,

  // Renew the lease.  Must be called more often than the lease TTL.
  // Returns false if the lease was lost (network partition / coordinator
  // revoked it); the runtime then fires LeaderLost and steps down.
  renewLease:    String => Boolean,

  // Release the lease (graceful step-down).
  releaseLease:  String => Unit,

  // Look up the current lease holder, or None if no leader.
  currentHolder: () => Option[String]
): Unit
```

The runtime synchronously calls `acquireLease(localNodeId, 5_000)`
once at switch time so callers see leadership immediately without
waiting a tick, then spawns a background tick (1 s on JVM/INT via
virtual threads; `setInterval(...).unref()` on JS Node).  Lease
renewal failure fires `LeaderLost` and steps the local node down;
the tick then keeps retrying `acquireLease` on every iteration.

### 5.2 Concrete adapters

| Adapter | Lease primitive | TTL default |
|---------|----------------|-------------|
| `EtcdLeaderCoordinator(endpoints, leaseKey)` | etcd lease + Compare-And-Swap | 10 s |
| `ConsulLeaderCoordinator(addr, lockKey)` | Consul session + KV lock | 15 s |
| `ZkLeaderCoordinator(addr, znode)` | Ephemeral sequential znode | session-bound |

Each adapter ships as a separate `std/cluster/coord-<name>.ssc` module
so apps that pick one of them depend only on the matching client
library.  Default install pulls none — coordinator support is opt-in.

### 5.3 Wire protocol

External coordinator does not use ScalaScript peer envelopes.  Each
node talks directly to the coordinator over its native protocol.
The runtime then *publishes* the current leader via a regular
`coordinator` envelope (same shape Bully uses) so the rest of the
cluster sees a consistent `currentLeader()` view even if some nodes
talk to the coordinator and others rely on gossip.

## 6. Unified API surface

Existing v1.23 surface continues to work unchanged.  Apps switch
protocol via one of these init-time hooks:

```scala
// Default — keep Bully (current behaviour).
// No call needed.

// Switch to Raft.  Must be called before any electLeader().
useRaftLeaderElection()

// Switch to an external coordinator.  Apps wire each function to the
// coordinator's native protocol (etcd lease + CAS, Consul session +
// KV lock, ZooKeeper ephemeral znode, etc.).
val etcd = EtcdSession.connect(List("https://etcd-1:2379"))
useExternalCoordinator(
  acquireLease  = (nid, ms) => etcd.acquireLease("/cluster/leader", nid, ms),
  renewLease    = nid       => etcd.renewLease("/cluster/leader", nid),
  releaseLease  = nid       => etcd.releaseLease("/cluster/leader", nid),
  currentHolder = ()        => etcd.getLeaseHolder("/cluster/leader")
)
```

Both new hooks land as plain `extern def`s in `std.actors` and as
`Cluster.useRaft()` / `Cluster.useCoordinator(adapter)` wrappers in
`std/cluster/membership.ssc`.

`electLeader()` becomes a no-op when an external coordinator is
active (the coordinator drives elections).  `setAutoReelect(true)`
keeps working under Raft: on `LeaderLost`, we re-enter the candidate
state with a fresh random timeout.

New observable, mostly for the auditable-log requirement:

```scala
// Returns the ordered history of accepted leader claims this node
// has observed.  Each entry is (term, leaderId, wallClockMs).
// Bounded to the last N entries (default 100); apps that need
// long-term history persist them themselves.
extern def leaderHistory(): List[(Long, String, Long)]
```

`leaderHistory` is populated by both Bully and Raft so apps can
adopt it without committing to a protocol up front.  Under Bully,
the `term` is the local node's monotonic election counter (not
globally agreed); under Raft, it is the Raft term.

## 7. Coexistence with v1.23 features

| Feature | Raft | External coord |
|---|---|---|
| `electLeader()` | Triggers a manual election | No-op (coordinator drives) |
| `currentLeader()` | Locally-known leader for `currentTerm` | Lease holder (cached) |
| `subscribeLeaderEvents()` | Same events as Bully | Same events as Bully |
| `setAutoReelect(true)` | Re-enter candidate on `LeaderLost` | Re-attempt `acquireLease` |
| `clusterConfigSet` | Unchanged | Unchanged |
| `setDraining(true)` | Step down if leader (releases lease) | Step down if leader |
| `clusterMetricSet` | Unchanged | Unchanged |
| `broadcastHealth` / `clusterIsDown` | Unchanged (Phi-accrual is per-link) | Unchanged |

Step-down on drain is the only cross-feature integration that
needs new wiring — it works for Bully too (current behaviour: drain
is a hint, leader stays put), but under Raft / coordinator it
matters more, so it becomes part of the protocol.

## 8. Implementation phases

If the trigger condition fires:

**Phase 1 — Spec freeze (1 day).**  Convert this document into
`docs/cluster-raft-plan.md` with locked-in numbers (timeouts,
quorum formula, persistence format).  Pick whether Raft or external
coordinator ships first based on the triggering use case.

**Phase 2 — Runtime hooks (2–3 days).**  Add a `LeaderProtocol`
indirection inside `_runActors` (JVM/JS/INT) so the existing Bully
logic becomes one implementation of the trait.  Existing
`electLeader` / `currentLeader` calls dispatch through the trait.
No behaviour change yet.

**Phase 3a — Raft (1 week).**  Implement the algorithm in §4.
Persist `(currentTerm, votedFor)` to disk.  New conformance test
exercises a 5-node cluster under simulated partitions (kill peer
links via the existing `_peerChannels.remove` hook).

**Phase 3b — External coordinator (1 week).**  Implement
`EtcdLeaderCoordinator` first (most common in production).
Consul and ZooKeeper variants ship in subsequent point releases
once the trait shape is proved.  *Iter 1 (locked surface): the
`LeaderCoordinator` case class lands in `std.actors` and
`useExternalCoordinator(coord)` accepts it; runtime lease lifecycle
is not yet driven from it (TODO iter 2).*

**Phase 4 — `leaderHistory` (1 day).**  Bounded ring buffer per
node; populated by every `LeaderElected` firing regardless of
protocol.  Conformance test pins the ordering.

**Phase 5 — Documentation (1 day).**  Promote this file into
`docs/cluster-management.md` as a regular section; collapse the
"deferred" line into "✓ Landed in v1.2x".

Total effort: ~2 weeks for Raft, ~2 weeks for external-coordinator
adapters.  Both can ship independently.

## 9. Test plan

Unit-level (single-process simulation):

- **Term increments.**  Two `electLeader()` calls produce two
  successive terms.
- **Vote granting.**  A node that already voted in term *t* refuses
  a second vote in the same term.
- **Heartbeat resets election timer.**  Followers do not start an
  election while heartbeats arrive.

### Multi-process integration test — current state

Real multi-node testing (two `ssc.jar` subprocesses on distinct
loopback ports, joined via the v1.6 actor WS, verifying they
converge on a single Bully leader) currently hits a runtime
limitation: `serve(port)` blocks the caller thread, and the actor
scheduler runs in that same thread, so `runActors { ... serve(p) }`
deadlocks before the actor scheduler can drain inbound peer
envelopes.  Concrete fix needs one of:

1. A non-blocking `serveAsync(port)` intrinsic that returns
   immediately and runs the WS server on a virtual thread.
2. An option for `runActors` to release the calling thread to a
   background scheduler so user code can call `serve` after it.

Until then the conformance tests cover single-node behaviour and
the e2e ping-pong tests cover three-backend agreement.  Real-WS
multi-node integration testing is deferred to a follow-up release
that ships the non-blocking-serve intrinsic.

Integration (real WS, multiple processes, killed at the kernel
level so we exercise the real Phase 3 distributed actor stack):

- **5-node election in <1 s.**  All nodes elected, single agreed
  leader, `leaderHistory()` consistent on every node.
- **Leader killed (`kill -9`).**  Surviving 4 nodes elect a new
  leader in <1 s.  No two leaders observed at the same term.
- **Symmetric 2|3 partition.**  Only the 3-node side elects a
  leader.  The 2-node side reports `currentLeader() == ""` until
  the partition heals.  Healed cluster converges on the 3-side's
  leader (or runs a fresh election if heartbeats time out).
- **Asymmetric partition.**  Same as above but each node loses
  connectivity to a different subset.  Cluster either elects a
  single leader (if a majority can still reach each other) or no
  leader at all.
- **Coordinator lease loss.**  Kill the coordinator endpoint mid-
  lease; the runtime fires `LeaderLost`, steps down, retries
  `acquireLease` with backoff.

Conformance suite gains four new `.ssc` files
(`actors-cluster-raft-*.ssc`) covering the single-process branches.
The integration tests live under `e2e/` as shell scripts that
orchestrate multiple `ssc` processes.

## 10. Open questions

Locked when this becomes a real milestone:

- **Persistence format.**  JSON file at a configurable path, or
  embed in an existing on-disk artifact?  Trade-off: JSON is human-
  readable but a corrupt write loses both fields.
- **Quorum for 2-node clusters.**  A 2-node Raft is degenerate
  (every election needs both nodes).  Document this as "use 3+
  nodes for Raft" or special-case n=2 to defer to Bully?
- **Lease TTL vs heartbeat interval defaults.**  Match etcd
  defaults (10 s lease, 3 s heartbeat) or tune lower for faster
  failover?  Probably configurable via `useExternalCoordinator`
  options.
- **leaderHistory size bound.**  100 entries is a guess; revisit
  if real apps need more.
- **`useRaftLeaderElection()` vs `setLeaderProtocol("raft")`** —
  string-keyed lookup vs typed function.  Typed is safer; string
  is more extensible if community adapters appear.

## 11. Why this document exists at all

Three reasons to spec this before the trigger fires:

1. **Mark the boundary.**  Bully landed in v1.23; this doc says
   "the upgrade path is Raft / external coord, not a fork or rewrite."
2. **Lock the API contract.**  `electLeader()` /
   `currentLeader()` / `subscribeLeaderEvents()` already work
   under Bully.  This doc commits to those same intrinsics still
   working under Raft so the app's leader-election code is portable.
3. **Capture the option space.**  When a real consumer asks, we
   already know which questions are decided (§7), which need
   real-data input (§10), and which are explicit non-goals (§2).

It is **not** a commitment to build strong-consistency leader
election.  It is a commitment to know what we would build if and
when.
