# Cluster federation — multi-cluster addressing and routing

Status: **deferred until a trigger condition fires** (see §6).  Spec
lives in this file so that when we promote, we are not designing
from scratch under deadline pressure.  No code lands in
`std/cluster/*` until a real consumer surfaces — this document is
design only.

Companion to [`cluster-management.md`](cluster-management.md) — which
lists federation as a Tier 3 deferred item (§11) and as one of the
post-promotion open questions (§8) — and
[`cluster-raft.md`](cluster-raft.md), whose external-coordinator hook
(§5) is the most likely structural foundation for the federation
backbone.

## 1. What "federation" means here

A **federation** is a set of cooperating ScalaScript peer-clusters,
each retaining its own leader, membership view, and per-link failure
detection, but sharing:

- a **global addressing scheme** so an actor on cluster A can address
  an actor on cluster B by a single canonical name;
- a **registry** of clusters and per-cluster leader leases that any
  member can consult to discover where a given cluster lives;
- a **routing mechanism** for messages whose target cluster is not
  the local one.

A federation is **not** a "bigger cluster".  Each member cluster
keeps its independent identity, lifecycle, and consistency boundary.
A federation is a thin coordination layer over already-working
clusters, much like the BGP-of-AS-numbers layer over already-working
internal IGP routing.  The membership of a federation changes on the
order of minutes to days (a new region brought online, a region
decommissioned); the membership of a single cluster changes on the
order of seconds (rolling restart, node failure).  The two layers
have intentionally different cadences.

In scope (when promoted):

- **Federation registry** — which clusters exist, who their leaders
  are, how to reach them.
- **Global addressing** — `cluster-id/node-id/actor-name` canonical
  form; resolution from name to wire route.
- **Cross-cluster message routing** — how an actor on cluster A
  delivers a `send` to an actor on cluster B.
- **Cross-cluster Singleton** — a singleton that lives on one node
  of one cluster of the federation, with the federation as the
  outer election domain.
- **Cross-cluster `clusterConfigSet` rollup** — config keys
  scoped to either a single cluster (current behaviour) or the
  whole federation (new).
- **Failure semantics** at the inter-cluster level — what `send`
  returns when the target cluster is unreachable, partially
  reachable, or has no current leader.

Explicitly NOT in scope, ever:

- **Cross-cluster consensus on every actor message.**  Inter-cluster
  routes are best-effort + bounded retry + visible failure, matching
  the v1.6 Phase 3 within-cluster shape.  Strong consistency is
  available *per cluster* via Raft; it does not extend across the
  federation boundary.
- **Replacing per-cluster leader election.**  Each cluster elects
  its leader exactly as today (Bully / Raft / external coordinator,
  per [`cluster-raft.md`](cluster-raft.md) §6).  Federation only
  reads the result.
- **Clusters spanning incompatible backends.**  Each cluster is still
  bound by the [`cluster-management.md`](cluster-management.md) §7
  hard-no: members of a single cluster all run the same backend
  family (JVM / JS Node / INT) and connect via WS.  A federation
  may contain a JVM cluster and a JS Node cluster cooperating
  through the registry, but no single cluster mixes them.
- **Cross-cluster strict ordering.**  Within a cluster, v1.6 Phase 3
  gives FIFO per (sender, receiver).  Across clusters, the route
  may traverse two independent peer links and possibly a relay; the
  per-pair FIFO guarantee weakens to "FIFO if both endpoints are
  reachable on the direct route".  Apps that need cross-cluster
  ordering use idempotent / sequence-numbered messages.
- **Federation-wide map-reduce.**  The v1.22 distributed map-reduce
  is per-cluster; cross-cluster map-reduce is a separate, larger
  question that would build *on* federation but is not part of it.
- **Federated identity / auth mesh.**  Mutual trust between clusters
  is assumed at the deployment level (TLS + pinned certificates).
  ScalaScript does not ship an SSO / OAuth / SPIFFE layer; that is
  infrastructure territory, the same hard-no boundary as
  [`cluster-management.md`](cluster-management.md) §1.

## 2. Why federation exists

A single flat ScalaScript cluster works well up to the limit where
its assumptions stop holding.  Three forces push past that limit:

1. **Cross-region deployments.**  Latency between regions (50–200 ms)
   is two orders of magnitude higher than within a region (sub-ms).
   Phi-accrual failure detection tuned for intra-region links
   ([`cluster-management.md`](cluster-management.md) §3.4) declares
   cross-region peers dead under normal jitter.  Lowering the
   threshold to tolerate inter-region latency makes intra-region
   failure detection slow.  The clean fix is to keep one cluster per
   region and let federation handle the inter-region links with
   different timing constants.

2. **Isolation domains.**  Teams or business units want operational
   isolation: independent rolling restarts, independent
   `clusterConfigSet` blast radius, independent metrics
   aggregation.  A flat cluster forces global coordination on
   anything cluster-wide; federation lets each isolation domain
   stay autonomous while still addressing each other when needed.

3. **Compliance boundaries.**  Some workloads must run in
   jurisdiction A (data sovereignty); others in jurisdiction B.
   A flat cluster doesn't model "this actor may not be placed on
   that subset of nodes".  A federation makes the boundary
   explicit: each jurisdiction is a cluster, the federation knows
   the membership, the placement question becomes "which cluster
   handles this message?" — answerable at the addressing layer
   before any data leaves the legal boundary.

The common thread: **a single cluster is the wrong shape when the
membership has internal structure**.  Federation models that
structure as first-class.

## 3. Architecture

### 3.1 Addressing scheme

Canonical actor name extends the v1.6 Phase 3 `<node-id>/<actor>`
form with a cluster prefix:

```text
<cluster-id>/<node-id>/<actor-name>
```

The cluster prefix is **optional**.  Names without it default to
the local cluster, preserving v1.6 Phase 3 source compatibility:

```text
node-a/worker-7              // resolves on the local cluster
us-east/node-a/worker-7      // resolves federation-wide
```

Resolution rules:

- A name with no slash is a local actor on the local node
  (unchanged).
- A name with one slash is a local-cluster actor on the named node
  (unchanged from v1.6 Phase 3).
- A name with two slashes is a federation-routed actor; the first
  segment is the cluster id, looked up via the federation
  registry.

Cluster ids are short strings (`us-east`, `eu-west-1`, `prod`,
`staging`) following the same lexical rules as node ids (DNS-label
shape: alphanumeric + hyphen, no underscore, no dot, ≤ 63 chars).
A federation member cluster picks its id once at startup
(`Cluster.setFederationId("us-east")`); changing it mid-flight is
not supported.

### 3.2 Federation registry

The registry is a key-value store containing, for each member
cluster:

```text
/federation/clusters/<cluster-id>
  leader          : <node-id>
  leaderEndpoint  : <ws-url>          // wss://… of the leader's actor port
  members         : List[<node-id>]   // optional, last-known snapshot
  protocol        : "bully" | "raft" | "coord"
  leaseExpiresAt  : <epoch-ms>
  metadata        : Map[String, String]   // free-form, app-defined
```

Three implementation options.  None of them is locked at spec time;
the choice is a Phase 1 open question (§7).

| Option | Mechanism | Trade-off |
|--------|-----------|-----------|
| **External coordinator** (etcd / Consul / ZK) | Each cluster's leader writes its entry on election + heartbeat; readers `watch` the prefix | Strong consistency; reuses the [`cluster-raft.md`](cluster-raft.md) §5 adapter trait; adds an external dependency at the federation level |
| **Gossip of clusters** | Each cluster's leader gossips its entry to a configured list of peer-cluster seed leaders; receivers gossip onward | No external dependency; eventual consistency only; vulnerable to partition like any gossip protocol |
| **Static config** | Federation membership baked into config; leader endpoints discovered via DNS SRV or pinned in config | Trivially simple; no auto-failover-aware routing — readers re-resolve on connect failure |

**Strongly likely outcome**: external coordinator as the floor, with
the same `acquireLease` / `renewLease` / `releaseLease` /
`currentHolder` adapter shape as
[`cluster-raft.md`](cluster-raft.md) §5.  The lease primitive does
double duty: it is already the per-cluster leader-election backbone
when the cluster opts into external-coordinator leader election, so
reusing it for the federation registry adds zero new wire protocols
to the picture.  Apps that already run etcd for cluster-raft get
federation discovery for free; apps that run Bully in-cluster pay a
one-time "stand up etcd for the federation" cost they would have
paid anyway for cross-region operational reasons.

### 3.3 Per-cluster vs federation-wide layers

```text
   Application code
        │
        ▼
   Cluster handle (per-cluster)              ← v1.6 Phase 3 + cluster-management.md
        │
        ▼
   Federation handle (federation-wide)       ← NEW
        │
        ▼
   Federation registry  ◄── coordinator / gossip / static
        │
        ▼
   Wire routing (cross-cluster send)
```

The per-cluster layer is unchanged.  All existing v1.23 features
(`electLeader`, `subscribeLeaderEvents`, `clusterConfigSet`,
`clusterMetricSet`, `setDraining`, `leaderHistory`,
`SubscribeMembership`) continue to operate at the per-cluster scope
they always had.  Federation is purely additive: when no federation
is configured, the runtime behaves identically to v1.23.

A new `Cluster.federation(...)` handle exposes the federation-wide
surface:

```scala
object Cluster:
  // Existing v1.23 surface — per-cluster.
  def membership():    Membership
  def electLeader():   Unit
  def currentLeader(): String
  // ...

  // NEW — federation-wide.
  def federation(): Federation

trait Federation:
  /** Set this cluster's federation id (must be called once before joinFederation). */
  def setId(clusterId: String): Unit

  /** Join the federation via the given registry. */
  def join(registry: FederationRegistry): Unit

  /** All currently-known clusters in the federation. */
  def clusters(): List[ClusterEntry]

  /** Resolve a federation-qualified actor name to a wire route. */
  def resolve(name: String): Option[Route]

  /** Subscribe to federation membership events. */
  def watch(handler: FederationEvent => Unit): Unit

  /** Leave the federation gracefully (releases the registry entry). */
  def leave(): Unit
```

`FederationRegistry` is the same four-function adapter shape as
[`cluster-raft.md`](cluster-raft.md) §5.1 — `put` /
`renew` / `delete` / `list`.  This intentional symmetry is the main
structural decision of this spec.

### 3.4 Message routing between clusters

Two options:

| Option | Mechanism | Trade-off |
|--------|-----------|-----------|
| **Direct WS, leader-to-leader** | Sending node opens a WS to the destination cluster's leader; leader forwards to the target node via its own intra-cluster routing | Single hop within each cluster; only N² leader connections, not N×M node-to-node; predictable failure mode (target cluster's leader is the single point of contact) |
| **Direct WS, node-to-node** | Sending node opens a WS directly to the destination node | Lower latency; no leader bottleneck; but O(nodes × nodes) connection matrix, and senders need full cross-cluster membership view |
| **Relay through a federation gateway** | Dedicated gateway process (one per cluster) forwards inter-cluster traffic | Operationally clean (one box per region to firewall / TLS-terminate); adds a hop; gateway becomes a new component to operate |

**Likely outcome**: **direct WS leader-to-leader as the default**,
with node-to-node available as an opt-in optimisation for hot paths.
Reasons:

- The leader already exists, already has a stable identity in the
  registry, and already runs the per-cluster routing logic for
  intra-cluster `send`.  Forwarding inbound federation traffic
  through the same code path is one extra branch, not a new
  subsystem.
- Connection count stays manageable: N clusters means N×(N−1)
  leader-to-leader WS links at the federation tier, regardless of
  node count.
- Failure mode is legible: "cluster B is unreachable" is a single
  observable signal (the leader-to-leader WS is down), not a fan-out
  of partial-reachability noise across every node-to-node pair.
- Apps that need lower latency can opt their hot actors into
  node-to-node routing via a registry hint
  (`metadata["routing"]="direct"`), without changing the default.

### 3.5 Failure semantics

What does `send("us-east/node-a/worker-7", msg)` do when cluster
`us-east` is unreachable from cluster `eu-west` ?  Three layers of
failure, each with its own observable signal:

1. **Cluster id unknown in the registry.**  `resolve` returns
   `None`; `send` fails synchronously with
   `FederationError.UnknownCluster`.  This is a configuration
   error or a startup-ordering bug, not a transient outage.

2. **Cluster id known but the leader-to-leader WS is down.**
   The runtime buffers the message in a bounded per-destination
   queue (default 1024 envelopes, same shape as the v1.6 Phase 3
   intra-cluster outbox), retries the WS dial with exponential
   backoff, and fires
   `FederationEvent.ClusterUnreachable(clusterId, since)` on the
   subscriber bus.  If the queue overflows before the link
   recovers, the oldest envelopes drop with a
   `FederationEvent.MessageDropped(clusterId, count)` event.
   This matches the v1.6 Phase 3 intra-cluster shape: dropped
   messages are visible, not silent.

3. **Cluster id known, link up, but the target node-id within
   the cluster is unknown / down.**  The destination leader sees
   the inbound envelope, fails to find the target, replies with
   a `FederationError.UnknownActor` envelope.  The sending node
   surfaces it as a `send`-side error event subscribable via
   `Federation.onError(handler)`.

The federation registry's `leaseExpiresAt` field provides a
secondary signal: if the leader lease is past its TTL and no
renewal has been observed, the registry entry is stale.  Readers
can choose to treat stale entries as `UnknownCluster` (strict) or
attempt the dial anyway (best-effort) — the choice is a per-app
config flag, default strict.

### 3.6 Cross-cluster Singleton

`Singleton.use(name, factory)` ([`cluster-management.md`](cluster-management.md)
§10) currently elects a singleton across one cluster.  Federated
variant:

```scala
Singleton.federated(name, factory)
```

Election runs at the federation tier: the registry holds a
secondary key per federated singleton (`/federation/singletons/<name>`
→ `<cluster-id>/<node-id>`).  The cluster currently holding the
lease runs the singleton via the existing per-cluster `Singleton.use`
machinery, scoped to its own leader.  On lease loss (cluster outage
or graceful step-down), any other cluster's leader may attempt to
acquire it.

This composes cleanly: per-cluster Singleton handles intra-cluster
leader failover (~1 s under Raft); federated Singleton handles
cross-cluster failover (~lease-TTL seconds, typically 10–30 s,
matching the registry's lease tuning).  The two timescales are
intentionally separate so a single cluster's transient leader
churn does not trigger a federation-wide migration.

### 3.7 Cross-cluster `clusterConfigSet` rollup

Today `clusterConfigSet(key, value)` is per-cluster: it gossips
within the cluster and converges via LWW
([`cluster-management.md`](cluster-management.md) §10).  Federated
variant introduces a key namespace:

```scala
clusterConfigSet("cluster:foo", "bar")          // unchanged — per-cluster
clusterConfigSet("federation:rateLimit", "100") // NEW — federation-wide
```

Federation-namespaced keys are owned by the federation registry and
propagated via the same `watch` mechanism used for the cluster
list.  Within each cluster, the federation key is then re-published
via the regular `clusterConfigSet` bus so every node sees it
through the existing v1.23 API.  Apps observe federation config
exactly the same way they observe cluster config; the namespace
is the only distinguishing feature.

LWW semantics extend cleanly: federation writes carry the
writer-cluster-id + a hybrid logical clock so concurrent writes
from different clusters converge deterministically.

## 4. Migration

A single-cluster app upgrades to federated in three steps, none of
which touches actor business logic:

1. **Pick a cluster id and a registry.**  Add to startup:
   ```scala
   Cluster.federation().setId("us-east")
   Cluster.federation().join(EtcdFederationRegistry(
     endpoints = List("https://etcd-1:2379"),
     prefix    = "/scalascript/federation"
   ))
   ```

2. **Optionally qualify actor names.**  Existing `send("node-a/worker")`
   calls continue to route locally.  To address a remote cluster,
   prefix the name: `send("eu-west/node-a/worker")`.  No new
   intrinsic — the same `send` parses the two-slash form.

3. **Optionally migrate singletons / configs.**  `Singleton.use(...)`
   becomes `Singleton.federated(...)` only where the application
   actually wants cross-cluster failover.  `clusterConfigSet(...)`
   gains the `federation:` prefix only for keys that need
   federation-wide visibility.  Existing per-cluster behaviour is
   preserved for keys without the prefix.

What stays unchanged:

- **All v1.6 Phase 3 actor code.**  `spawn`, `send`, `receive`,
  `globalRegister`, `globalWhereis` continue to work identically.
- **All v1.23 cluster intrinsics.**  `electLeader`,
  `subscribeMembership`, `setDraining`, `leaderHistory`,
  `clusterMetricSet` remain per-cluster and behave exactly as
  before.
- **The HTTP cluster-control routes** (`/_ssc-cluster/status`,
  `/drain`, `/events`).  A new `/federation/status` route appears
  alongside them when federation is active; existing routes do not
  change shape.

What apps explicitly do **not** need to do:

- **Recompile under a new backend.**  Federation lives entirely in
  `std/cluster/federation*.ssc` modules and a small runtime hook
  for the cross-cluster wire envelope; the source-level surface is
  additive.
- **Change deployment topology immediately.**  Joining a federation
  with N=1 cluster is legal and useful: the federation registry
  exists, the cluster publishes itself, but no cross-cluster
  routing happens until a second cluster joins.  This is the
  natural staging path — bring up one cluster federation-ready,
  then add the second.

## 5. Phases

Each phase is independently shippable and reversible up to the
point a downstream phase starts using it.

**Phase 1 — Federation registry (1–2 weeks).**  Just discovery, no
message routing.  Implement `Federation.setId`, `Federation.join`,
`Federation.clusters`, `Federation.watch`.  Reuse the
[`cluster-raft.md`](cluster-raft.md) §5 lease adapter trait shape
for the registry; ship the etcd-backed `EtcdFederationRegistry`
first (matches Phase 3b of cluster-raft).  Validation: 2-cluster
single-process simulation — both clusters publish themselves; each
sees the other appear in `clusters()` within one registry tick.
Cross-cluster `send` is not yet wired; calls with a two-slash name
return `UnsupportedOperation` until Phase 2.

**Phase 2 — Cross-cluster actor send (1–2 weeks).**  Wire the
leader-to-leader WS routing path (§3.4).  Two-slash names in
`send`, `globalRegister`, `globalWhereis` start resolving via the
registry and forwarding via the destination cluster's leader.
Implement the bounded per-destination outbox, retry-with-backoff,
and the `FederationEvent.ClusterUnreachable` /
`FederationEvent.MessageDropped` /
`FederationError.UnknownActor` signals.  Validation: 2-cluster
single-process simulation with cross-cluster ping/pong; partition
test that kills the leader-to-leader link and verifies queue +
event behaviour.  Real-WS 2-cluster integration test via a
shared embedded etcd; deferred to Phase 2.5 if the
non-blocking-`serve` limitation noted in
[`cluster-raft.md`](cluster-raft.md) §9 is not yet resolved.

**Phase 3 — Cross-cluster Singleton (1 week).**  Build on Phase 2.
Implement `Singleton.federated(name, factory)` via a federation-tier
lease (§3.6).  Cleanly compose with per-cluster `Singleton.use` —
the federated layer picks the cluster, the per-cluster layer picks
the node, both fail over independently.  Validation: 2-cluster
single-process simulation, federation-wide counter actor; kill the
hosting cluster, verify the other cluster's leader acquires the
lease and resumes within one TTL.

**Phase 4 — Cross-cluster `clusterConfigSet` rollup (1 week).**
Add the `federation:` namespace (§3.7); writes propagate via the
registry, reads surface through the existing per-cluster config
bus on every node.  Validation: 2-cluster single-process
simulation; concurrent writes from both clusters; LWW convergence
checked against the hybrid-logical-clock ordering.

**Phase 5 — Documentation + production hardening (1 week).**
Promote this file into the regular `cluster-management.md` flow.
Add `/federation/status` HTTP route and `ssc federation` CLI
wrapper symmetric with `ssc cluster`.  Document the
ConsulFederationRegistry and ZkFederationRegistry adapters as
follow-ons (each ships in a subsequent point release the same way
the cluster-raft external-coordinator adapters do).

Total effort if greenlit: ~5–7 weeks for Phases 1–4, plus 1 week
of documentation.  Phases 1 and 5 are the only ones with hard
dependencies on cluster-raft Phase 3b (the etcd adapter
infrastructure); Phases 2–4 are pure ScalaScript-level work on top
of the abstract registry trait.

## 6. Promotion criteria

Move out of "future / deferred" into a real milestone **when any of
these fire**:

1. **A production .ssc deployment that already runs 2+ regions**
   asks for cross-region addressing without a manual load-balancer
   layer.  Today no such deployment exists.
2. **A compliance-driven multi-jurisdiction app** needs explicit
   per-jurisdiction clusters with federation-level addressing —
   "messages bound for actors in the EU must traverse only the EU
   cluster" is the kind of constraint that motivates first-class
   federation.
3. **Cluster-management Tier 3 ZK adapter ships** and the same
   etcd / Consul / ZK abstraction now wants to back the federation
   registry — the marginal cost of adding federation drops sharply
   once the shared lease-adapter infrastructure is real.
4. **A community-package author** ships a 3rd-party
   `scalascript-federation` library that meets the design
   constraints in this document, and demand suggests folding it
   into std.

Until then: stay in this document.  Each new release revisits the
"promote?" question; if no, stays deferred.  No code lands in
`std/cluster/federation*.ssc` until promotion.

## 7. Open questions

Decisions required before Phase 1 starts:

- **Mandatory coordinator, or gossip-of-clusters as an alternative?**
  The default in §3.2 is "coordinator is the floor".  The argument
  for keeping gossip on the table: trusted-deployment users who
  already accept gossip within a cluster
  ([`cluster-management.md`](cluster-management.md) §3.1) may want
  to avoid the operational cost of standing up an external
  coordinator just for federation discovery.  The argument
  against: gossip across regions with WAN latency / partition rates
  is a noticeably harder problem than gossip within a region; the
  failure modes of inter-cluster gossip have not been studied
  enough to recommend it as a default.  Resolution: ship Phase 1
  with the coordinator-backed registry only; revisit gossip after
  6 months of production usage.

- **Trust model — every cluster trusts every other?**  v1.6 Phase 3
  treats peer nodes as trusted; federation can either inherit
  that (every cluster in the federation is mutually trusted) or
  introduce per-cluster permissions.  The latter is a full
  authentication / authorisation subsystem and matches the
  [`cluster-management.md`](cluster-management.md) §7 hard-no on
  multi-tenant auth.  Resolution: federation is mutually-trusted
  by deployment policy (TLS + pinned certs at the WS layer);
  per-cluster ACLs are deferred to a separate spec if and when a
  multi-tenant use case appears.

- **Cluster id collisions on simultaneous join.**  Two clusters
  configured with the same id both attempt to publish; second one
  wins (LWW)?  First one wins (claim-and-hold)?  Both refuse to
  start?  The third option is safest (config error visible at
  startup) but blocks legitimate "rolling-restart of a single
  cluster" cases where the old instance hasn't yet released the
  lease.  Resolution: claim-and-hold with a TTL — second cluster
  fails startup with a clear error message containing the
  conflicting cluster's lease holder; operator either releases
  manually or waits for TTL expiry.

- **Federation membership churn rate.**  How often do clusters
  join / leave?  Affects registry implementation choices —
  high-churn favours coordinator-backed `watch`; low-churn admits
  static-config viability.  Resolution: design for low-churn
  (joins / leaves on the order of minutes to days); document the
  upper bound (~1 churn event per second across the whole
  federation) as the supported envelope.

- **Cross-cluster outbox sizing.**  Default 1024 envelopes per
  destination cluster (§3.5) — same as v1.6 Phase 3 intra-cluster.
  Cross-cluster routes have higher latency and longer outage
  windows; the cap may need to be higher.  Resolution: ship Phase
  2 with the v1.6 default + a `setFederationOutboxCap(n)` knob;
  revisit defaults once real production buffering pressure is
  measured.

- **Backwards-compatible two-slash names.**  v1.6 Phase 3 reserves
  the slash character in actor names for the node-id separator.
  Federation extends that convention but does not introduce a new
  separator (no `@`, no `#`, no `::`).  Resolution: prose-clear
  that actor names containing literal slashes were already
  illegal under v1.6 Phase 3, so the two-slash federation form
  doesn't collide with anything in the wild.  Add a startup
  validation pass that rejects actor names matching the regex
  `^[^/]+/[^/]+/[^/]+$` from local registration — they would be
  unaddressable under federation.

- **`leaderHistory` vs federation history.**  Should the
  per-cluster `leaderHistory()`
  ([`cluster-raft.md`](cluster-raft.md) §6) gain a federation-tier
  counterpart `federationHistory()` recording cluster-leader
  changes?  Likely yes for the same compliance-audit reasons that
  motivated `leaderHistory` in the first place; defer the API
  shape until Phase 1 starts.

## 8. Coexistence with the rest of the stack

| Feature | Relationship |
|---------|--------------|
| **v1.6 Phase 3 distributed actors** | Foundation — federation routes traffic via the same WS envelopes; cross-cluster `send` is the existing intra-cluster `send` with an extra resolution step |
| **v1.23 cluster management** | Per-cluster layer; federation sits above it and reads (but does not write) per-cluster leader state |
| **v1.23 Raft / external coordinator** | Lease-adapter trait is the structural twin of `FederationRegistry`; the same etcd / Consul / ZK adapters back both, but at different scopes |
| **v1.23 cluster-wide Singleton** | Composed: federated Singleton picks the cluster, per-cluster Singleton picks the node |
| **v1.22 distributed map-reduce** | Out of scope at the federation level; map-reduce remains per-cluster.  Cross-cluster map-reduce would be a separate spec built on federation |
| **v1.4 std-lib effects (Logger, Metrics)** | Federation events surface through the existing event bus; no new effect-layer surface |
| **Modularity (v1.18 / v1.19)** | `std/cluster/federation*.ssc` follows the nested-area pattern; the registry adapters ship as separate modules so apps depend only on their chosen backend |

## 9. Hard-no list (locked even pre-promotion)

| Feature | Reason |
|---------|--------|
| **Cross-cluster consensus on every message** | Inter-cluster routes are best-effort, matching the intra-cluster shape; consensus at every send would multiply latency by orders of magnitude for no clear benefit |
| **Replacing per-cluster leader election** | Federation reads cluster leader state; it does not own it.  Each cluster's protocol is chosen per [`cluster-raft.md`](cluster-raft.md) §6 |
| **Clusters spanning incompatible backends** | Inherits the [`cluster-management.md`](cluster-management.md) §7 boundary; a federation may mix clusters of different backends but each cluster stays homogeneous |
| **Federated identity / auth mesh** | Infrastructure territory; ScalaScript expects mutual trust at the deployment level (TLS + pinned certs) |
| **Mandatory external coordinator at the per-cluster layer** | Federation may require a coordinator; per-cluster Bully usage stays free of that dependency |
| **Cross-cluster strict ordering** | Per-pair FIFO is preserved on the direct route; not across relays or under partition.  Apps that need stronger ordering use idempotent / sequenced messages |
| **Federated map-reduce in this spec** | Separate, larger question; would build *on* federation but is not part of it |

## 10. Testing strategy

Per-phase:

- **Phase 1** — single-process simulation of N clusters
  (default N=2), each running `Cluster.federation().join(...)`
  against an in-memory registry.  Assertions: every cluster
  appears in every other cluster's `clusters()` within one tick;
  `watch` fires on join / leave; lease expiry surfaces as a
  `ClusterUnreachable` event.
- **Phase 2** — same single-process N-cluster harness, but cross-
  cluster `send` is wired.  Test ping/pong across the boundary;
  inject a "leader-to-leader link down" event and verify the
  outbox + retry + event flow; cap the outbox and verify
  `MessageDropped`.
- **Phase 3** — federated Singleton across the simulation;
  kill the hosting cluster (drop it from the registry); verify
  the lease migrates to a survivor within one TTL.  Verify
  per-cluster leader churn within the *hosting* cluster does not
  trigger a federation-tier migration.
- **Phase 4** — concurrent `federation:` config writes from both
  clusters; assert LWW convergence on the hybrid-logical-clock
  ordering; assert per-node visibility through the regular
  `clusterConfigSet` subscriber API.

Real-WS integration (deferred to follow-up release, same as
[`cluster-raft.md`](cluster-raft.md) §9):

- **2-cluster real-WS via a shared embedded etcd.**  Two
  cluster's worth of `ssc` subprocesses on distinct loopback port
  ranges, joined through an in-process embedded etcd
  ([jetcd-test] or equivalent).  Assertions: registry round-trip,
  cross-cluster `send`, partition behaviour, lease loss.
- **3-cluster real-WS** for the federated Singleton tests, so the
  lease can plausibly migrate to a non-trivial choice of
  surviving cluster.

Scale tests (Tier 4, deferred):

- **5+ clusters, each with 5+ nodes.**  Federation membership
  churn under sustained inter-cluster traffic; failure detector
  tuning for WAN-latency links; outbox memory pressure measured
  under simulated 200 ms inter-cluster RTT.
- **Cross-backend federation matrix.**  JVM cluster + JS Node
  cluster + INT cluster sharing one federation registry, verifying
  the leader-to-leader WS envelope is byte-for-byte compatible
  across backends.  Prerequisite: per-backend implementation
  gap-check of every federation intrinsic across all three
  backends, identical to the cluster-management Tier 4 prerequisite.

The single-process N-cluster simulation is the minimum bar before
each phase merges.  Real-WS integration is the bar before any
phase reaches "✓ Landed" in `MILESTONES.md`.  Scale tests are
gating but not blocking — they catch regressions before users hit
them in production federations.

## 11. Why this document exists at all

Three reasons to spec this before the trigger fires:

1. **Mark the boundary.**  Per-cluster management landed in v1.23;
   this doc says "the next tier up — multi-cluster federation —
   builds on the lease-adapter trait, not on a new protocol."
   Future cluster-management work has an explicit place to point
   for "is that per-cluster or federation?".
2. **Lock the API contract.**  `Cluster.federation()` /
   `setId` / `join` / `clusters` / `watch` are committed shape;
   apps writing pre-promotion design docs can reference them.
3. **Capture the option space.**  When the first cross-region
   deployment asks, we already know which questions are decided
   (§9), which need real-data input (§7), and which are explicit
   non-goals (§1).

It is **not** a commitment to build federation.  It is a commitment
to know what we would build if and when.
