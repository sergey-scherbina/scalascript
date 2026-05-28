# Cluster Operations

Status: planned. Companion to [`docs/placement-and-remoting.md`](placement-and-remoting.md),
which defines the API surface for local↔remote↔distributed execution. This document
covers the operational concerns of running a ScalaScript cluster in production:
auth-token rotation, persistent cluster state, rolling upgrades, multi-AZ/multi-region
deployment, and autoscaling.

Cross-references:

- [`docs/placement-and-remoting.md`](placement-and-remoting.md) — placement API, handler
  registry, code identity, cluster front matter.
- [`docs/actors-dist.md`](actors-dist.md) — Pid model, gossip, phi-accrual, leader election.
- [`docs/cluster-management.md`](cluster-management.md) — `startNode`, `joinCluster`, drain
  protocol, operational routes.
- [`docs/cluster-raft.md`](cluster-raft.md) — Raft leader-only election, per-node vote file.
- [`docs/deploy.md`](deploy.md) — `DeployTarget` SPI, `DeployGroup`, `StateBackend` SPI.

## 1. Auth Token Rotation

### Current State

`setClusterAuthToken(token)` loads a static token at startup (default: `SSC_CLUSTER_TOKEN`
env var). There is no rotation mechanism; changing the token requires a full restart.

### Rotation Protocol

Token rotation uses an overlap window so all nodes accept both tokens simultaneously:

```scala
// Node that initiates the rotation.
// All peers receive the new token and begin dual-accept immediately;
// the old token stops being accepted after overlapMs milliseconds.
extern def rotateClusterToken(newToken: String, overlapMs: Int = 30_000): Unit
```

Wire message broadcast to every connected peer (extends `distributed-wire-protocol.md`):

```json
{ "$t": "token_rotate", "new": "<sha256-of-new-token>", "valid_from": <epoch-ms> }
```

Node behaviour on receipt:

1. Add `new` token to the accept-set alongside the current token.
2. Schedule a timer for `valid_from - now()` ms; on fire, drop the old token.
3. Acknowledge the initiator with `{ "$t": "token_rotate_ack", "nodeId": "..." }`.

The initiating node waits for acks from a quorum of peers before considering the
rotation safe. If a quorum is not reached within `overlapMs / 2`, the rotation is
aborted and the old token remains the sole valid token.

### StateBackend persistence

When a non-noop `StateBackend` is configured, the accepted token hash is persisted
under a well-known key so that restarted nodes pick up the rotated token without
requiring manual env-var changes:

```scala
// Internal key — not user-facing.
val tokenKey = StateKey(env = "_cluster", target = "_auth", slot = Some("token"))
backend.write(tokenKey, StateRecord(revision = sha256(newToken), ...))
```

On startup, `setClusterAuthToken` checks the state backend for a stored hash before
falling back to the `SSC_CLUSTER_TOKEN` env var.

### Open question

Should rotation be coordinated through a Raft commit (serialised, but requires a leader)
or broadcast-with-quorum (as above, more resilient to leader absence)?

## 2. Persistent Cluster State

### Current State

Cluster gossip uses last-write-wins (LWW) in-memory state. The only durable
artefact is the per-node Raft vote file (`<workDir>/.ssc-raft-state.json`).
`DeployGroup` and `DeployEnvironment` track deployment records via `StateBackend`,
but `clusterConfigSet` / `clusterConfigGet` (in `runtime/std/actors.ssc`) write
only to in-memory gossip — not to the state backend.

### Proposal

Wire cluster-wide configuration to the existing `StateBackend` SPI
(`runtime/std/deploy-plugin/.../StateBackend.scala`):

```scala
// User-visible API (placement-and-remoting.md §Cluster front matter drives these).
extern def clusterConfigSet(key: String, value: String): Unit   // persists when backend != Noop
extern def clusterConfigGet(key: String): Option[String]        // reads backend-first, then gossip
```

Internally each call translates to:

```scala
val cfgKey = StateKey(env = "_cluster", target = nodeId, slot = Some(key))
backend.write(cfgKey, StateRecord(revision = ..., outputs = Map("value" -> value), ...))
```

`NoopStateBackend` keeps the current behaviour (LWW gossip only, data lost on restart).
Opting in:

```yaml
state:
  backend: etcd
  endpoints: [etcd1:2379, etcd2:2379]
```

This is not Raft log replication. The Raft vote file remains local per-node; this
section covers cluster-wide *configuration* values (handler registrations, quorum
settings, advertised URLs) that currently evaporate on restart.

## 3. Rolling Cluster Upgrade

### Current State

The v1.23 drain protocol (`setDraining`, `POST /_ssc-cluster/drain`,
`subscribeDrainEvents`) is implemented. There is no orchestrator that drives a
coordinated rolling restart across all cluster nodes.

### `Deploy.rollingCluster`

```scala
enum RollingStrategy:
  case Rolling(maxUnavailable: Int = 1, waitDrainMs: Int = 30_000)
  case BlueGreen(observeMs: Int = 60_000)
  case Canary(percent: Int, observeMs: Int = 120_000)

// Upgrade every node in the cluster to the new artifact revision.
// Uses the existing DeployTarget.restartPod / DeployTarget.status infrastructure.
def Deploy.rollingCluster(
  env:      DeployEnvironment,
  target:   DeployTarget,
  strategy: RollingStrategy = RollingStrategy.Rolling(),
): Unit
```

Per-node sequence for `Rolling`:

1. Assert node is not already draining.
2. `POST /_ssc-cluster/drain` → wait for `drainDone` event or `waitDrainMs` timeout.
3. Call `target.deploy(ctx)` for this node (replace artifact, then `target.status` polls ready).
4. `POST /_ssc-cluster/drain` with `{ "enabled": false }` to re-activate the node.
5. Wait until peer heartbeats confirm the node is healthy (phi-accrual score drops below
   threshold).
6. Move to next node (at most `maxUnavailable` nodes draining simultaneously).

`BlueGreen` and `Canary` reuse the existing deploy-plugin blue-green and slot
infrastructure; `rollingCluster` adds the cluster-drain step as a pre-cutover gate.

Abort path: if any node fails to come healthy within `2 * waitDrainMs`, `rollingCluster`
stops and leaves the cluster in a mixed-version state, emitting a `CodeMismatch`
diagnostic. The operator can issue `ssc cluster rollback`.

Reuses `DeployGroup(mode = ExecMode.Sequence, onFailure = FailurePolicy.AbortRemaining)`
for per-node scheduling. No new orchestration primitives are required.

## 4. Multi-AZ / Multi-Region

### Current State

`FaultToleranceConfig` (`DeployEnvironment.scala:38–42`) carries:

```scala
case class FaultToleranceConfig(
  multiRegion:      List[String],   // region names, e.g. ["us-east-1", "eu-west-1"]
  quorum:           Int,            // cross-region quorum count
  failoverStrategy: FailoverStrategy,  // ActiveActive | ActivePassive | ActiveStandby
)
```

These fields are parsed but not yet lowered to any deploy behaviour.

### Proposed lowering

#### Topology declaration

```yaml
fault_tolerance:
  multi_region: [us-east-1, eu-west-1]
  quorum: 2
  failover_strategy: active-passive
```

#### Deploy lowering

When `multi_region` is non-empty, the deploy plugin emits one `DeployGroup` per region
containing the same target configuration:

```
DeployGroup(mode = Parallel, targets = [target-us-east-1, target-eu-west-1])
```

Each regional group is independent so a region-level failure does not block the other.
`quorum` constrains `rollingCluster` (§3): a region is not upgraded if doing so would
leave fewer than `quorum` regions healthy.

#### `globalRegister` for cross-region actor rendezvous

Named actors published via `pid.publish(name)` use the cluster gossip to propagate the
name. In multi-region deployments the gossip overlay already propagates across regions
(same `joinCluster` mechanism, possibly over a WAN link). No additional change required
for actor discovery.

#### External traffic routing

Route 53 / GCP LB / Azure Traffic Manager weight distribution is the operator's
responsibility. `deploy.outputs` can expose the per-region load-balancer URLs so a
third-party DNS-management tool can consume them:

```yaml
outputs:
  us-east-1-lb: https://lb-us-east-1.example.com
  eu-west-1-lb: https://lb-eu-west-1.example.com
```

This keeps the deploy plugin target-agnostic for external traffic routing, consistent
with the non-goal in `placement-and-remoting.md`: "Do not make cluster deployment
depend on Kubernetes."

### Open questions

- Should `quorum` count regions or nodes? (Regions is simpler to reason about for
  operators; nodes is more precise for availability math.)
- Should `ActiveStandby` lower to a passive replica with no inbound traffic, or is that
  identical to `ActivePassive`?

## 5. Autoscaling

This section is k8s-specific. Other targets that do not support autoscaling ignore
`autoscale:` configuration silently.

### Declaration

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

### HPA emission

When `autoscale:` is present, `K8sManifestGenerator` emits an `HorizontalPodAutoscaler`
manifest alongside the `Deployment`:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: ${name}-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
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
    # custom metrics require KEDA or similar adapter
    - type: External
      external:
        metric:
          name: ${custom.metric}
        target:
          type: AverageValue
          averageValue: "${custom.value}"
```

`K8sTarget.build` produces a list of manifest strings; `K8sManifestGenerator` gains
an `hpa(cfg: HpaConfig): String` method that is called when `autoscale:` is present.

### `HpaConfig` model

```scala
case class CpuTarget(percent: Int)
case class CustomTarget(metric: String, value: Int)
enum AutoscaleTarget:
  case Cpu(t: CpuTarget)
  case Custom(t: CustomTarget)

case class HpaConfig(min: Int, max: Int, targets: List[AutoscaleTarget])
```

Parsed from the `autoscale:` YAML block in `K8sTarget`. Default: no HPA emitted.

### Open question

Should HPA codegen live in `K8sTarget` or in a new `K8sAutoscalingPolicy` companion
that `K8sTarget` delegates to? (The companion is cleaner but adds a file; the inline
approach keeps the manifest generator self-contained.)

## 6. Critical Files

| File | Change |
|------|--------|
| `runtime/std/actors.ssc` | Add `rotateClusterToken`; wire `clusterConfigGet/Set` to `StateBackend` |
| `runtime/std/cluster/membership.ssc` | Token rotation broadcast + ack; dual-accept window |
| `docs/distributed-wire-protocol.md` | Add `token_rotate` / `token_rotate_ack` `$t` tags |
| `runtime/std/deploy-plugin/.../StateBackend.scala` | No change — existing SPI is sufficient |
| `runtime/std/deploy-plugin/.../DeployGroup.scala` | No change — `rollingCluster` uses existing orchestration |
| `runtime/std/deploy-plugin/.../DeployEnvironment.scala` | Lower `FaultToleranceConfig.multiRegion` to regional `DeployGroup` |
| `runtime/std/deploy-plugin/.../K8sTarget.scala` | Parse `autoscale:` block; call `K8sManifestGenerator.hpa` |
| `runtime/std/deploy-plugin/.../K8sManifestGenerator.scala` | Add `hpa(cfg: HpaConfig): String` method |
| New: `runtime/std/deploy-plugin/.../HpaConfig.scala` | `HpaConfig`, `AutoscaleTarget` model |

## 7. Phases

- **Phase Op.1** — auth token rotation protocol + wire message + quorum ack.
- **Phase Op.2** — StateBackend persistence for `clusterConfigSet/Get` + `state:` front matter.
- **Phase Op.3** — `Deploy.rollingCluster` orchestrator using existing drain + `DeployGroup`.
- **Phase Op.4** — `FaultToleranceConfig.multiRegion` lowering to regional `DeployGroup`.
- **Phase Op.5** — HPA codegen in `K8sManifestGenerator`.

Each phase is independently mergeable. Phases Op.1 and Op.2 depend only on existing
cluster infrastructure; Op.3–Op.5 depend only on the existing deploy plugin.
