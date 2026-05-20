# Saga — coordinated multi-actor state changes with compensation

Status: **deferred until a real consumer appears** (see §6).  Spec
lives in this file so that when we promote, we are not designing
from scratch under deadline pressure.

Companion to [`cluster-management.md`](cluster-management.md) §11
(which routes "saga / distributed transactions" here),
[`cluster-raft.md`](cluster-raft.md) §6 (the unified leader-election
surface that saga coordinators ride on top of), and
[`coroutines.md`](coroutines.md) §3.4 / v1.6 Phase 3 (distributed
actors — the foundation saga executes over).

This spec is **design only**.  No code lands in `std/cluster/saga.ssc`
until the promotion criteria in §6 fire.

## 1. What "saga" means here

A **saga** is a sequence of locally-atomic steps, each touching one
actor's state, executed under a single logical transaction ID with
the contract: *either every step commits, or every committed step is
later compensated*.  Compensation is the user-defined inverse of a
step — "credit was added, so debit it back"; "a job was enqueued, so
cancel it"; "an artifact was published, so retract it".

The pattern is Garcia-Molina & Salem 1987, in the microservices-folklore
shape: orchestrated, not choreographed; eventually-consistent, not
strongly-consistent; intra-cluster, not cross-trust-boundary.  The
ScalaScript specialisation is that the orchestrator is a **leader-
elected Singleton actor** (see [`cluster-management.md`](cluster-management.md)
§10's `Singleton.use` / `Singleton.useStateful`) and the journal is
**durable cluster config** ([`cluster-raft.md`](cluster-raft.md)'s
`clusterConfigSet`).  Both already ship in v1.23; saga is a module
on top.

In scope (when promoted):

- A `SagaCoordinator` Singleton that runs steps in order, persists
  outcomes between steps, and reverses partial progress on failure.
- A small Scala-flavored DSL for defining sagas as a sequence of
  `step { … } compensateWith { … }` pairs.
- Durable journal entries — each step transition and each compensation
  is recorded in `clusterConfig` so a coordinator restart picks up
  where the previous instance left off.
- Standard observability — saga lifecycle events surface through the
  v1.23 `/_ssc-cluster/events` ring buffer and through
  `subscribeClusterEvents`.

Explicitly NOT in scope, ever (locked, see §3 below for rationale):

- Two-phase commit / three-phase commit / any locking protocol.
- Strong-consistency guarantees on the steps themselves.
- Database-transaction semantics — a saga does not roll back a SQL
  `UPDATE`; it issues a compensating `UPDATE`.
- Cross-cluster sagas — federation (cluster-management.md §11 Tier 3)
  handles cross-cluster coordination via its own protocol; saga is
  strictly intra-cluster.
- Automatic compensation derivation — users write each step's inverse
  themselves.  The runtime sequences them; it does not guess them.

## 2. Why deferred

Three reasons:

1. **No concrete consumer yet.**  v1.23 ships `Singleton` and
   `clusterConfigSet`; no user-facing `.ssc` workload today coordinates
   more than one actor's state on commit/rollback boundaries.  The
   shapes a saga API needs to expose (timeouts, compensation policy,
   visibility) are driven by real workloads; designing them in a
   vacuum guarantees we ship the wrong knobs.

2. **The "wrong abstraction" risk is real.**  Saga frameworks in the
   wild — Axon, Eventuate, Temporal, Camunda — disagree on the same
   surface decisions ("are step bodies allowed to await?",
   "is compensation re-tried on its own failures?", "do users register
   sagas declaratively or build them imperatively?"), and each
   choice is irreversibly visible in user code.  Picking before
   users tell us their shape commits us to maintenance debt.

3. **Plain `try/finally` inside an actor body solves 80% of the
   real cases today.**  A user who needs to debit-then-credit can
   wrap both in `try { debit(); credit() } catch { case _ => debit_undo() }`
   inside a single coordinating actor.  Saga earns its complexity
   only when (a) more than two steps are involved, (b) some steps run
   on remote nodes, and (c) the orchestrator itself can crash mid-flight.
   Until 2-3 real apps hit (c), `try/finally` is fine.

## 3. Goals

The shape saga aims to provide, once promoted:

### 3.1 Coordination beyond a single actor

Today's primitives — `spawn`, `send`, `receive`, `clusterConfigSet`,
`Singleton` — give the user atomic state changes *inside a single
actor*, plus eventually-consistent gossip *between actors*.  What's
missing is the join: **a single logical operation that touches several
actors' states with all-or-nothing semantics at the saga level**.

Examples of operations awkward today:

- *Cross-actor money transfer*: debit one account-actor, credit
  another.  If the credit fails after the debit committed, the user
  needs to re-credit the source — and remember to do so even if their
  own process crashes between the debit and the discovery of the
  failure.
- *Multi-stage workflow*: provision a customer record, allocate a
  quota slot, send a welcome message.  Failure halfway through must
  roll the first two back; ad-hoc try/catch doesn't survive a
  coordinator-actor crash.
- *Distributed compaction*: mark N data-actors as "merging into M",
  produce M's snapshot, retire the N inputs.  A crash mid-snapshot
  leaves the cluster confused unless N's "merging" flag is reversed.

### 3.2 Crash-safe orchestration

`try/finally` in an actor body protects against exceptions inside the
body.  It does not protect against the entire process dying between
steps.  Saga's coordinator runs as a Singleton, so a node crash
re-elects a new coordinator on another node, which reads the journal
and resumes from the last durably-recorded step.  Users get
"orchestrator survives node failure" for free.

### 3.3 Auditable compensation history

Each saga writes a journal entry per step and per compensation.  The
journal is queryable: operators can answer "did saga `tx-xyz` complete?
which step failed?  did its compensations all run?".  Today this
information lives in scattered logs across actors; saga centralises it.

## 4. Non-goals (locked even pre-promotion)

| Non-goal | Reason |
|---|---|
| **Two-phase commit / lock-based atomicity** | 2PC blocks; saga is non-blocking by design.  The whole point of choosing saga over 2PC is to avoid the cross-node lock-holding period.  Apps that genuinely need 2PC should use an external transactional store (Postgres, FoundationDB), not a saga. |
| **Strong consistency on step bodies** | Step bodies execute on whichever actor owns the affected state.  Between steps, other writers may interleave.  Apps that require strong consistency *within* a saga must use a single-writer actor as their consistency boundary, just like they would without saga. |
| **Database transaction substitute** | A saga does not start a SQL transaction; it sends actor messages.  If a step's body happens to write to Postgres, that's the step's business — saga's compensation is a separate `UPDATE`, not a `ROLLBACK`. |
| **Cross-cluster operation** | Federation territory; see [`cluster-management.md`](cluster-management.md) §11 Tier 3.  Saga assumes a single cluster's membership / leadership / config view. |
| **Automatic compensation synthesis** | The runtime has no way to know that the inverse of `account.debit(100)` is `account.credit(100)` and not `account.debit(-100)`.  Users write both halves. |
| **Saga-of-sagas / nested sagas** | Until a real use case asks, this is an explicit no.  Nesting introduces failure-mode combinatorics (parent commits, child compensates, what now?) that aren't worth pre-designing. |
| **Inline saga forking (run-step-B-and-C-in-parallel)** | All saga steps are sequential.  Parallel branches multiply compensation complexity (one branch fails, what about the other's effects?) and aren't a v1.0 concern.  Apps that want parallelism within a step can `spawn` and `join` inside the step body. |
| **Visibility of in-flight saga state from outside actors** | Other actors observe only the *final* outcome of a saga (a single message after `commit` or after all compensations ran).  Intermediate journal state is for the coordinator and operator tooling — not for cross-actor data-flow. |

## 5. Architecture

### 5.1 The `SagaCoordinator` actor

One Singleton per logical saga, named by the saga's stable ID:

```scala
Singleton.useStateful(
  name         = s"saga:$sagaId",
  initialState = "{}",
  factory      = state => SagaCoordinator.spawn(sagaId, definition, state)
)
```

The coordinator's actor loop is a small state machine over the
*definition* (the user-built list of steps + compensations) and the
*journal* (the durable record of which steps committed and which
compensations ran).  At every state transition the coordinator writes
a journal entry *before* sending the next step's message — never the
other way around, so a crash always leaves the journal at least as
advanced as the cluster's actual state.

### 5.2 The DSL

Illustrative — final shape locks during Phase 1:

```scala
[Saga, step, compensateWith](std/cluster/saga.ssc)

val transfer = Saga.define("transfer-tx") {
  step("debit") { (ctx: SagaCtx) =>
    accountA ! Debit(100)
    awaitReply()
  } compensateWith { (ctx: SagaCtx) =>
    accountA ! Credit(100)
    awaitReply()
  }

  step("credit") { (ctx: SagaCtx) =>
    accountB ! Credit(100)
    awaitReply()
  } compensateWith { (ctx: SagaCtx) =>
    accountB ! Debit(100)
    awaitReply()
  }

  step("notify") { (ctx: SagaCtx) =>
    notifier ! Notify(accountA.id, accountB.id)
    awaitReply()
  } compensateWith { (ctx: SagaCtx) =>
    // Best-effort retraction.  If it fails, the dead-letter queue
    // surfaces it for the operator.
    notifier ! Retract(accountA.id, accountB.id)
    awaitReply()
  }
}

// Kick off the saga on the cluster.  Runs on the current leader,
// migrates on failover.
Saga.run(transfer, sagaId = "tx-2026-05-20-001")
```

Each `step` body returns the value the next step receives via `ctx`;
each `compensateWith` body is the inverse, invoked in reverse order
when any later step fails.  Step bodies and compensation bodies are
both ordinary actor code — they may `send`, `ask`, `await`,
`receive`, `spawn`.

The `Saga.define { … }` builder collects steps in the order they
appear in source and freezes the list before returning; sagas are
immutable values once defined and may be reused for multiple `run`
calls with different IDs.

### 5.3 Journal — where the state lives

The journal is the **only** durable record the coordinator trusts on
recovery.  Each entry is a small JSON blob keyed under
`saga:<id>:journal:<seq>` in `clusterConfig`:

```text
{ "seq": 0, "phase": "started",   "step": null,     "ts": 1779... }
{ "seq": 1, "phase": "committed", "step": "debit",  "ts": 1779... }
{ "seq": 2, "phase": "committed", "step": "credit", "ts": 1779... }
{ "seq": 3, "phase": "failed",    "step": "notify", "ts": 1779..., "reason": "..." }
{ "seq": 4, "phase": "compensated", "step": "credit", "ts": 1779... }
{ "seq": 5, "phase": "compensated", "step": "debit",  "ts": 1779... }
{ "seq": 6, "phase": "rolled_back", "step": null,     "ts": 1779... }
```

Two design choices to lock in §10 before Phase 1:

- **Whether to reuse `clusterConfigSet` directly** (LWW gossip,
  cap'd at the existing per-key value size; one config key per
  journal entry) **or introduce a `sagaJournalAppend(sagaId, entry)`
  intrinsic** that bundles ordered append + read-back semantics in a
  single primitive.  Reusing `clusterConfigSet` is the smaller
  surface; a dedicated intrinsic is cleaner if many sagas churn the
  journal at once.  Provisional pick: **reuse `clusterConfigSet`** —
  saga is the first consumer of this pattern, and we can promote a
  new intrinsic in Phase 2 if cluster-config keys become a hotspot.
- **Whether the journal is per-saga or per-cluster.**  Per-saga keys
  (`saga:<id>:journal:<seq>`) scale linearly with the number of
  sagas and let `clusterConfigKeys()` enumerate them.  A single
  per-cluster journal is simpler but hits the `clusterConfigSet`
  value-size cap quickly.  Pick: **per-saga**.

### 5.4 Recovery — what happens on coordinator failover

When the leader running `SagaCoordinator` for saga `s` dies, the
Singleton supervisor on the new leader instantiates a fresh
coordinator with `useStateful`.  The new coordinator reads
`saga:s:journal:*` from `clusterConfig`, replays it to determine the
last durably-recorded phase, and continues from there:

| Last journal phase | Action on resume |
|---|---|
| `started`, no `committed` entries | Re-run step 0 from scratch.  Step bodies MUST be idempotent (see §10). |
| `committed: step_k`, no failure | Run step `k+1`. |
| `failed: step_k`, no `compensated` entries | Begin compensation of step `k-1`, then `k-2`, …, then `0`. |
| `compensated: step_j`, more steps committed below | Continue compensating step `j-1`. |
| `rolled_back` | Saga terminated; coordinator exits. |
| `committed: step_N` (final step) | Write `completed` entry, coordinator exits. |

The contract is one-direction: the journal never goes backwards in
phase, so the new coordinator never needs to undo a journal entry —
only to read what's there and resume.

### 5.5 Failure semantics of compensation itself

Compensations are ordinary code; they can fail.  The runtime's
behaviour on compensation failure is the most loaded design choice
in the spec and is staged across phases:

| Phase | Compensation-failure policy |
|---|---|
| Phase 1 | Compensation failure logs and *halts*.  The journal records `compensation_failed: step_k`; the saga stops there with the operator on the hook. |
| Phase 3 | Retry with exponential backoff (bounded retry count, configurable per saga).  After the retry budget, route the failed compensation to a **dead-letter** queue (`saga:dead-letter:<id>`) for operator action.  The rest of the saga's compensations *do continue* — one failed compensation does not block the others. |

Phase 3's dead-letter queue is a regular cluster config key the
operator polls (or subscribes to via `subscribeClusterEvents`); no
new intrinsic.

### 5.6 Components and module layout

`std/cluster/saga.ssc` is one hand-written ScalaScript module on top
of the existing v1.23 surface — `Singleton`, `clusterConfigSet`,
`subscribeClusterEvents`, the actor `send` / `ask` / `await` family.
No new interpreter intrinsics ship in Phase 1.  The module exports:

```text
std/cluster/saga.ssc
├── Saga              # builder façade — define / run / status
├── SagaCoordinator   # internal actor; users don't construct directly
├── SagaDef           # immutable list of (step, compensation) pairs
├── SagaCtx           # passed to each step body — gives access to prior step results, saga ID, attempt count
├── SagaPhase         # enum (Started / Committed / Failed / Compensated / RolledBack / Completed / DeadLetter)
└── SagaEvent         # surfaces on subscribeClusterEvents
```

### 5.7 Composition with existing actor patterns

Saga step bodies are plain actor code and integrate as follows:

- **Fire-and-forget `send`**: legal in a step body, but the step
  must still return *something* that indicates success/failure to
  the coordinator.  Convention: the step's last expression is its
  result; throwing an exception (or returning `Failure(...)`) flags
  the step as failed.
- **Synchronous `ask` / `await`**: the typical case.  The step calls
  `targetActor ! Msg(...); awaitReply()` and uses the reply to
  decide success.  Timeouts on `await` propagate to the saga
  coordinator as a step failure.
- **Spawning sub-actors inside a step**: allowed, but the step's
  body must `await` those sub-actors before returning — saga sees
  only the step's return value, not any actors the step spawned.
- **Cross-node sends inside a step**: legal; saga inherits whatever
  reliability guarantees v1.6 Phase 3 distributed actors provide
  (at-most-once delivery with reconnect-on-drop).  Saga's
  durability is at the **coordinator** level, not the message level
  — if a peer link drops mid-send, the step body's own retry policy
  must handle it.

## 6. Promotion criteria

Move out of "future / deferred" into a real milestone **when any of
these fire**:

1. **A real .ssc application with 3+ actor state changes per logical
   operation** asks for crash-safe orchestration.  Today no such app
   exists in the repo — the closest is `examples/distributed-log-aggregation.ssc`,
   which is one-way (no compensation).
2. **A `Singleton`-based ad-hoc orchestrator** appears in user code
   (or in `std/cluster/*`) and grows journal-and-recovery logic
   inline — at that point the right move is to extract the pattern
   into saga rather than reinvent it per consumer.
3. **A community-package author** ships a third-party
   `scalascript-saga` library that meets the constraints in §4, and
   demand suggests folding it into std.
4. **A finance / regulated workload** asks for the audit log
   property of §3.3 specifically — durable record of every cross-actor
   transition.

Until then: stay in this document.  Each new release revisits the
"promote?" question; if no, stays deferred.

## 7. Migration

There is no existing saga API in ScalaScript to migrate from — this
is a clean greenfield design.  What needs spelling out is **how saga
slots in alongside existing patterns** that handle the same problem
space today:

| Existing pattern | Saga relationship |
|---|---|
| `try { … } catch { case e => … }` inside a single actor | Saga is the multi-actor / multi-node generalisation.  Single-actor try/catch keeps working unchanged; saga earns its place once more than one actor's state is involved. |
| `Singleton.useStateful` with hand-rolled journal | Exactly the pattern saga *automates*.  Apps using `useStateful` to track multi-step progress can migrate to `Saga.define` once available; their state-key conventions become saga's journal-key conventions. |
| `clusterConfigSet` as a workflow flag | Saga's journal subsumes this for orchestration-shaped workflows.  Apps that use `clusterConfigSet` for plain configuration distribution are unaffected. |
| External orchestrator (Temporal, Camunda, Airflow) | Saga is intentionally lighter-weight.  Apps with existing external orchestration don't need saga; saga is the in-cluster zero-deps option. |

The migration policy when saga lands: **opt-in, no auto-migration**.
Existing actor code keeps working with no source changes.  Apps that
want the new property explicitly `import` the saga module and
restructure their multi-step operations into `Saga.define { … }`.

## 8. Phases

If a promotion criterion fires, the implementation walks four
phases.  Each phase is independently shippable per AGENTS.md Rule 3;
each phase has its own conformance tests.

### Phase 1 — `SagaCoordinator` Singleton with in-memory journal (~3 days)

Goal: prove the basic orchestration shape on the happy path and the
compensation path, without committing to persistence semantics.

- `std/cluster/saga.ssc` exports `Saga.define` + `Saga.run`.
- Coordinator runs as `Singleton.use("saga:<id>", ...)`.
- Journal is an in-memory `var journal: List[Entry]` inside the
  coordinator actor — no `clusterConfig` writes yet.
- Compensation runs in reverse order on the first step failure.
- Conformance test: happy-path 3-step saga commits; compensation-path
  3-step saga with step-2 failure compensates step-1 only.

**Crucially**: Phase 1 does NOT survive coordinator failover.  Killing
the leader mid-saga loses the journal and the saga is dropped.  This
is acceptable for the first phase because no real consumer is using
saga yet — Phase 1 exists to lock the DSL shape and the actor-level
state machine before adding persistence concerns.

### Phase 2 — Durable journal via cluster config (~3 days)

Goal: survive coordinator failover.

- Every journal entry written through `clusterConfigSet("saga:<id>:journal:<seq>", entryJson)`.
- On coordinator restart, `clusterConfigKeys()` enumerates this
  saga's journal; coordinator replays per §5.4.
- New conformance test: 3-node cluster, mid-saga `kill -9` on the
  leader, surviving cluster re-elects, new coordinator resumes from
  the last committed step.  No double-commit, no missed compensation.
- Step bodies become **idempotent-required** (see §10) — Phase 2
  conformance test deliberately re-runs a step body after a kill to
  verify the user code handles that.

Phase 2 is the **MVP** — past this point, saga is genuinely useful
in production.  Phases 3 and 4 are quality improvements.

### Phase 3 — Compensation retry + dead-letter (~3 days)

Goal: handle "compensation itself fails" gracefully.

- Each compensation retries with exponential backoff up to a
  configurable budget (default 3 retries; pluggable per saga in
  `Saga.define`).
- After the retry budget, the compensation is written to
  `clusterConfigSet("saga:<id>:dead-letter:<step>", failureBlob)` and
  the saga continues compensating the remaining earlier steps.
- The dead-letter entries surface as `SagaEvent.DeadLetter` events;
  operators may inspect, manually compensate, and call
  `Saga.acknowledgeDeadLetter(sagaId, step)` to clear the entry.
- New conformance test: 3-step saga with compensation-failure on the
  middle step; verify compensations for steps 0 and 2 still run, dead-
  letter for step 1 surfaces in `subscribeClusterEvents`.

### Phase 4 — Observability via the `/_ssc-cluster/events` ring buffer (~2 days)

Goal: operators see saga state without writing custom code.

- Every `SagaEvent` (`Started` / `StepCommitted` / `StepFailed` /
  `Compensated` / `DeadLetter` / `RolledBack` / `Completed`) is
  appended to the v1.23 cluster events ring buffer.
- The buffer's existing JSON shape extends with `{"type": "saga", ...}`
  variants; `ssc cluster events` shows them inline.
- A new CLI sub-command `ssc cluster sagas <url>` lists in-flight
  sagas, their last journal entry, and their phase.
- Conformance test: poll `/_ssc-cluster/events?since=...` during a
  3-step saga, verify all six lifecycle events appear in order.

Total effort: **~11 working days** end-to-end if all four phases are
green-fielded in sequence.

## 9. Testing strategy

Conformance tests live alongside the rest of `std/cluster/*` under
`conformance/`.  Each phase adds tests that the previous phase's
implementation may not satisfy.

Test families:

### 9.1 Happy path

`saga-happy-3-step.ssc`: define a 3-step saga, run it, assert every
step commits and the saga's final phase is `Completed`.  Journal
contains exactly `[started, committed×3, completed]`.

### 9.2 Compensation path

`saga-compensate-on-step-2.ssc`: step 2's body throws.  Assert that
step 1's compensation runs, step 0's compensation runs (in that
order — reverse of commit), and the saga's final phase is `RolledBack`.
Journal contains `[started, committed:0, committed:1, failed:2,
compensated:1, compensated:0, rolled_back]`.

### 9.3 Failover during commit

`saga-failover-mid-commit.ssc` (Phase 2+): 3-node cluster.  Saga
starts on the leader; after step 1 commits, send `kill -9` to the
leader process.  Surviving cluster re-elects; new coordinator reads
the journal, observes `committed:1` as the last entry, and resumes
from step 2.  Assert the saga eventually completes.  Tolerates one
duplicate `committed:1` journal entry if the new coordinator re-runs
step 1 (idempotency is a step-body responsibility).

### 9.4 Failover during compensation

`saga-failover-mid-compensate.ssc` (Phase 2+): same setup but the
crash occurs mid-compensation.  New coordinator reads the journal,
sees `failed:2` followed by `compensated:1` (but no `compensated:0`),
and resumes by compensating step 0.  Asserts no double-compensation
of step 1.

### 9.5 Compensation failure

`saga-compensation-fails.ssc` (Phase 3+): the user-written
compensation for step 1 throws on every call.  Assert: compensation
for step 0 still runs (one failure does not block siblings); step 1
surfaces as a `DeadLetter` event; the saga's final phase is
`RolledBackWithDeadLetters`.  Operator simulates acknowledging the
dead letter and the entry clears.

### 9.6 Observability

`saga-events-ring-buffer.ssc` (Phase 4): consume
`/_ssc-cluster/events` during a 3-step saga and verify every
lifecycle event appears in the buffer with the right shape.

### 9.7 Multi-saga isolation

`saga-multi-concurrent.ssc` (Phase 2+): two sagas run concurrently on
the same cluster with disjoint actors.  Assert they do not see each
other's journal entries (key namespacing is correct) and both
complete independently.  A failure in one does not affect the other.

## 10. Open questions

Decisions to lock before Phase 1 begins:

1. **Idempotency requirements for step bodies.**  The runtime
   guarantees "at-least-once" execution of each step body across
   coordinator failover (a kill between "step ran" and "journal
   wrote committed" replays the step on recovery).  Two options:
   - **Caller-guaranteed** (recommended): users write step bodies
     that are safe to re-execute.  Most actor operations already are
     (idempotent message handlers), and the doc explicitly says so.
     Zero runtime cost.
   - **Runtime-deduped**: the coordinator writes a "step k started"
     journal entry before running the body, and on recovery it
     checks whether the step body actually ran (e.g. by querying the
     target actor).  Doubles journal writes; requires a target-actor
     query protocol that doesn't exist yet.
   Pick before Phase 1.  Provisional: **caller-guaranteed**.

2. **Timeout policy for stuck steps.**  A step that `await`s an
   actor reply that never arrives currently hangs the saga
   indefinitely.  Options:
   - **No timeout** — the coordinator waits forever.  Simple; matches
     `Singleton.useStateful` semantics today.  Operators must kill
     the saga manually.
   - **Per-step timeout, configurable in `Saga.define`** — on
     timeout, treat as `StepFailed` and begin compensation.  Adds
     one more knob to the DSL.
   - **Cluster-wide saga timeout** — every saga gets a default 10-
     minute deadline beyond which it's killed.  Catches truly stuck
     sagas; risks false positives.
   Pick before Phase 1.  Provisional: **per-step timeout, default
   60 s, override in `Saga.define`**.

3. **Saga visibility from other actors.**  Three sub-questions:
   - Can a non-coordinator actor query "is saga X in progress?"
     Provisional: **yes**, via `Saga.status(id)` returning the last
     journal phase.  Read-only; no transactional semantics across
     the read and any subsequent action.
   - Can a non-coordinator actor *abort* a running saga?  Provisional:
     **no in Phase 1**, **yes in Phase 3** via `Saga.abort(id, reason)`
     which writes a synthetic `Failed` journal entry and lets the
     coordinator pick it up on the next tick.
   - Can a saga's intermediate step results be observed?  Provisional:
     **no**, by design.  Other actors see only `Completed` /
     `RolledBack` via `subscribeClusterEvents`.  Intermediate state
     is for the coordinator and the operator.

4. **Reuse `clusterConfigSet` vs new `sagaJournalAppend` intrinsic.**
   See §5.3.  Provisional: **reuse `clusterConfigSet`**.  Promote to
   a dedicated intrinsic if Phase 2 finds the LWW-gossip + per-key
   value cap becomes a bottleneck.

5. **Saga ID generation and uniqueness.**  Users pass `sagaId` to
   `Saga.run`; the runtime does not generate IDs.  Two related sub-
   questions:
   - What if two `Saga.run` calls use the same ID?  Provisional:
     **second call returns `AlreadyRunning(id)` without starting a
     new saga**, treating saga IDs as cluster-wide locks (the
     `Singleton` machinery already enforces this).
   - Are completed-saga IDs reusable?  Provisional: **no within the
     ring-buffer retention window** (200 entries from
     `/_ssc-cluster/events`), **yes once the saga's journal entries
     have been pruned**.  Pruning is a Phase 3 operator action; not
     automatic.

6. **Persistence of `SagaDef` itself.**  The user-built saga
   definition (the list of steps + compensations) is a code value.
   On coordinator failover, the new coordinator must hold the *same*
   definition — but it's reconstructed from user code on the new
   node, not loaded from the journal.  This is fine for "user-code
   identical across all nodes" (the standard distributed-actors
   assumption) but explicitly does NOT support **rolling upgrades
   that change saga definitions mid-flight**.  Spelling this out
   in §4 as a hard non-goal: rolling upgrades must drain in-flight
   sagas before changing definitions.  Pick before Phase 1:
   **yes, this constraint is in the non-goals list**.

## 11. Why this document exists at all

Three reasons to spec this before the trigger fires:

1. **Mark the boundary.**  Multi-actor coordination questions land
   somewhere — without this doc, they grow organically inside the
   first consumer's code, locking that consumer's particular shape
   into the runtime.  This doc says "saga is the answer when it's
   time, and here is the shape it will take."
2. **Lock the non-goals.**  §4 prevents the spec from drifting into
   2PC / strong-consistency / cross-cluster territory under deadline
   pressure once a real consumer surfaces.  The cheapest place to
   say no is *before* the user has built half a wrong thing.
3. **Capture the open questions.**  §10's six items are the choices
   that will rightly take a meeting to lock when promotion happens.
   Recording them here means that meeting starts from "decide
   between options A and B" rather than from "rediscover the
   problem space."

It is **not** a commitment to build saga.  It is a commitment to
know what we'd build if and when.
