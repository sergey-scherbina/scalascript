# Cluster management — peer-cluster orchestration

Status: **fully landed in v1.23** — membership view + membership
events + per-link + cluster-wide Phi-accrual failure detection +
Bully leader election (with auto re-elect) + auto-reconnect on
outbound link drops + periodic gossip re-discovery + LWW cluster
config distribution (snapshot on handshake) + rolling-restart drain
protocol + cluster metrics aggregation (per-node gauges, snapshot
on handshake) + `std.cluster.Cluster.*` wrapper + Raft leader
election (opt-in via `useRaftLeaderElection`) + external-coordinator
hook (opt-in via the 4-arg `useExternalCoordinator`) + leaderHistory
ring buffer for auditable leadership.  Real-coordinator adapters
(etcd, Consul, ZooKeeper) wrap their wire protocols around the 4-arg
hook at the app level — see [`docs/cluster-raft.md`](cluster-raft.md)
§5 for the shape.

Companion to [`docs/coroutines.md`](coroutines.md) §3.4
(v1.6 Phase 3 distributed actors — the foundation),
[`docs/mapreduce.md`](mapreduce.md) §11 (the explicit
out-of-scope boundary), and `MILESTONES.md` v1.6 Phase 3
(distributed actors landed).

## 1. What "cluster management" means here

A **peer-cluster** — a set of ScalaScript nodes running
the same application code, connected via WS (per v1.6
Phase 3), cooperating on workload via actors and
map-reduce.

In scope (when promoted):

- **Peer discovery** — how nodes find each other
- **Membership view** — which nodes are currently alive
- **Leader election** — a single node coordinates
  cluster-wide decisions (work assignment, schema
  changes, etc.)
- **Configuration distribution** — shared config across
  the cluster
- **Health checks / heartbeats** — already in v1.6 Phase 3
  at the per-link level; cluster-wide aggregation is the
  gap
- **Rolling restarts** — graceful node replacement
- **Metrics aggregation** — cluster-wide observability

Explicitly NOT in scope, ever:

- Container orchestration (Kubernetes territory)
- Scheduling / placement (also Kubernetes territory)
- Persistent storage / volumes
- Network policy / CNI plugins
- Multi-tenancy / authentication mesh
- Service mesh / sidecar proxies

The line: **what you'd build on top of a Kubernetes
deployment of ScalaScript**, not how Kubernetes itself
deploys ScalaScript.

## 2. Why deferred

Three reasons:

1. **No concrete consumer yet.**  v1.6 Phase 3 distributed
   actors just landed; v1.22 distributed map-reduce isn't
   started.  Until real apps demand cluster-wide
   coordination, the design space is too open to commit to.

2. **The "wrong abstraction" risk is high.**  Cluster
   management is famously over-engineered: Akka Cluster's
   gossip protocol vs Erlang's `net_kernel` vs Kubernetes
   etcd vs HashiCorp Serf — all reasonable, all very
   different.  Picking before users tell us their shape
   commits us to maintenance debt.

3. **Most v1.x users don't need it.**  A typical app is
   1-3 nodes manually connected via `Cluster.connect(...)`
   from v1.22.  Manual is fine at that scale.  Cluster
   management starts paying back at 10+ nodes that come
   and go dynamically.

## 3. The four design quadrants

When cluster management becomes real, four orthogonal
decisions need locking:

### 3.1 Discovery — how nodes find each other

| Option | Trade-off |
|--------|-----------|
| **Static seed list** in config | Simple; new nodes need config-push to all peers |
| **Multicast** (LAN-only) | Auto-discovery on a flat network; doesn't work in cloud / containers |
| **DNS SRV records** | Cloud-friendly; depends on external DNS infrastructure |
| **Gossip from any seed** | Erlang / Cassandra / Riak model; resilient but complex |
| **External coordinator** (etcd, Consul, ZK) | Strong consistency; external dependency |

Likely outcome: **static seed list + opt-in gossip**.
Seed list is the floor; gossip optional for self-healing.

### 3.2 Membership — who's in

| Option | Trade-off |
|--------|-----------|
| **Static after discovery** | New nodes only join on operator action |
| **Auto-join** | Anyone reaching a seed is added |
| **Quorum-gated** | Majority of existing members must agree |

Strongly skewed by the security model.  For
trusted-deployment (single-team, single-network) auto-join
is fine.  For multi-tenant / untrusted networks, quorum
+ auth.

### 3.3 Leader election

| Option | Trade-off | Status |
|--------|-----------|--------|
| **No leader** (all nodes equal) | Simplest; coordination via consensus on every decision | App-level |
| **Bully** | Simple but flaky on network partitions | ✓ v1.23 (default) |
| **Raft** | Strong consistency; well-understood; ~500 LOC of careful protocol | ✓ v1.23 (opt-in via `useRaftLeaderElection`) |
| **External coordinator** | Outsource to etcd/Consul leadership lease | ✓ v1.23 (opt-in via `useExternalCoordinator`) |

For v1.23: **Bully is the default**; apps that hit its split-brain
edge cases call `useRaftLeaderElection()` once to switch to Raft, or
`useExternalCoordinator(acquire, renew, release, holder)` to delegate
to etcd / Consul / ZooKeeper.  All three protocols share the same
`electLeader` / `currentLeader` / `subscribeLeaderEvents` /
`leaderHistory` surface — only the dispatch backing it changes.
See [`docs/cluster-raft.md`](cluster-raft.md) §6 for the unified
API and §4–5 for the per-protocol details.

### 3.4 Failure detection

Mostly settled: **per-link heartbeats** from v1.6 Phase 3
already do this.  Cluster-wide aggregation ("node N is
down according to 2/3 peers, mark it dead") is the gap.
Phi accrual failure detector (Akka, Cassandra) is the
standard answer.

## 4. Sketch of `std/cluster/*` layout (when promoted)

Following the v1.18 nesting policy:

```
std/cluster/
├── types.ssc       # NodeId, Membership, ClusterEvent, FailureDetector
├── seed.ssc        # static-seed-list discovery
├── gossip.ssc      # gossip protocol (opt-in)
├── membership.ssc  # join / leave / fail events
├── failure.ssc     # cluster-wide failure detection
├── config.ssc      # configuration distribution
└── index.ssc       # aggregator
```

Each is a hand-written ScalaScript module on top of v1.6
Phase 3 actors + v1.22 cluster-handle.  No new runtime
primitives.

Likely starting point if a real consumer surfaces:

```scala
[Cluster, Node, Membership, joinAuto, watch](../std/cluster)

val cluster = Cluster.joinAuto(seeds = List(
  Node("seed-1@10.0.0.10:9100"),
  Node("seed-2@10.0.0.11:9100")
))

cluster.watch { event => event match
  case Membership.NodeJoined(n)  => log.info(s"$n joined")
  case Membership.NodeLeft(n, r) => log.warn(s"$n left: $r")
}

println(cluster.currentMembers)
```

## 5. Coexistence with the rest of the stack

| Feature | Relationship |
|---------|--------------|
| **v1.6 Phase 3 distributed actors** | Foundation — cluster builds on actor messaging + per-link heartbeats |
| **v1.22 distributed map-reduce** | Uses `Cluster.connect(...)` as a thin handle today; would use the richer cluster API once it exists.  Map-reduce is one of many consumers, not the only |
| **v1.4 std-lib effects (Logger, Metrics)** | Cluster-wide log aggregation + metrics rollup — clean fit but not built-in; an integration layer when both ship |
| **v1.7 plugin packaging** | Cluster's `std/cluster/*` ships as a regular std module set; no plugin-level concerns |
| **Modularity (v1.18 / v1.19)** | `std/cluster/*` follows the nested-area pattern (v1.18 layout policy) and depends on registry / dep imports (v1.19) for distribution if ever sold as a community package |

## 6. Promotion criteria

Move out of "future / deferred" into a real milestone
**when any of these fire**:

1. **A real .ssc application running on 5+ nodes** asks
   for auto-failover, dynamic membership, or coordinated
   restarts.  Today no such app exists.
2. **v1.22 distributed map-reduce gets traction** (10+
   user-reported workloads) and asks "how do I avoid
   manually maintaining the node list?".
3. **A community-package author** ships a 3rd-party
   `scalascript-cluster` library that meets the design
   constraints, and demand suggests folding it into std.

Until then: stay in this document.  Each new release
revisits the "promote?" question; if no, stays deferred.

## 7. Hard-no list (locked even pre-promotion)

| Feature | Reason |
|---------|--------|
| **Kubernetes / container orchestration scope** | Out of bounds — ScalaScript runs *on* K8s, not *as* K8s |
| **Network policy / mesh layer** | CNI / service mesh territory; layered above ScalaScript |
| **Strong consistency by default** | Erlang's eventual-consistency model is the right default for actor-cluster shape; strong consistency is a per-decision opt-in (Raft for leader election if it ever ships) |
| **Mandatory dependency on external coordinator** | etcd / Consul / ZK should be opt-in adapters, not the only option.  Trusted deployments do fine with seed lists |
| **Cluster spanning incompatible backends** | Cluster members all run the same backend or compatible backends (JVM ↔ JVM ↔ JS Node ↔ INT).  No browser-SPA in cluster (no inbound WS) |

## 8. Open questions (for when we promote)

Locked answers ARE the hard-no list (§7).  Real open
questions emerge with promotion; this section is a
placeholder.

- Discovery protocol — seed-list-only or gossip too?
- Failure detector — phi accrual or simple-threshold?
- Leader election — none, Raft-lite, external?
- Configuration distribution — Bus or per-node-pull?
- Sub-cluster federation — single global cluster or
  federated nested clusters?
- Schema evolution — adding new members with new code
  versions; how to handle?

Each becomes a §3-quadrant when the milestone moves out
of deferred.

## 9. Why this document exists at all

Three reasons to write this before we need it:

1. **Mark the boundary**: distributed map-reduce (v1.22)
   shouldn't try to grow cluster management organically.
   This doc says "no, that goes here when it's time."
2. **Capture the option space** so when a real consumer
   asks, we're not designing from scratch under deadline
   pressure.
3. **Prevent feature creep**: every future milestone that
   touches multi-node coordination has an explicit place
   to point ("does that belong here or in cluster
   management?")

It is **not** a commitment to build cluster management.
It's a commitment to know what we'd build if and when.
