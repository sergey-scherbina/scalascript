# Durable cancellation semantics — PROPOSAL (for semantic-owner ratification)

> **Status: PROPOSAL, not a frozen spec.** This document does **not** flip conformance
> vector `26-cancellation-transitions`, and the reference implementation does **not**
> implement cancellation on its strength. The vector's pending record
> (`tests/interop-conformance/pending/26-cancellation-transitions.pending`) deliberately
> leaves cancellation unspecified and warns that the harness *"must not become the
> accidental owner by inventing those rules."* These are **recommended answers** for the
> semantic owner (Sergiy / codex-interop) to accept, amend, or reject. Until ratified,
> vector 26 stays `pending-spec` with oracle `UNSPECIFIED`.
>
> Related: `specs/control-interoperability.md` (§3 reference model, §8 save/run, §11
> admission/run phases, §13 failures, §14.1 semantic vectors),
> `specs/durable-continuation-save-run.md`, `specs/durable-capsule-envelope.md`.

## 1. The three open questions (from pending/26)

The pending record names exactly what is unfrozen:

1. **Race vs the resume claim** — does a cancellation race *before* or *after* a resume
   claim, and who wins when they are concurrent?
2. **Cancelled reusable continuation** — how does a cancelled *reusable* saved
   continuation report a *later* `run(input)`?
3. **Portable diagnostic** — what exact target-neutral typed diagnostic does a
   cancellation produce?

The pending `needs` field also asks for **target-neutral public cancellation states**,
**transition ordering**, and the **one-shot interaction**. This proposal answers each,
reusing the models the core spec already froze so cancellation stays consistent rather
than introducing a new algebra.

## 2. Design principle: reuse the frozen models, add nothing target-specific

Three existing, ratified mechanisms already answer most of the hard parts; cancellation
should mirror them rather than invent:

- **The atomic, eager one-shot claim** (§3.1): *"exactly one concurrent caller wins, and
  every loser receives `AlreadyResumed` before it can invoke the continuation or execute
  any part of the suffix."* Cancellation vs resume is the same shape of race.
- **The typed failures table** (§13): a closed set of typed categories, *"never hidden
  exception class-name protocols."* Cancellation needs exactly one new row.
- **The stable boundary projection** (§3.1): `ControlRunFailure(AlreadyResumed(op))` with
  separated `code` / `message` / `rendered`. Cancellation should get an analogous one.

The one hard part the core spec did **not** freeze — *interrupting an already-running
suffix* — is deliberately kept **out of the base contract** here (§8), because it is
irreducibly target-specific (JVM `interrupt`, JS single-thread cooperative, native
signals) and would make the portable contract non-uniform.

## 3. Proposed public cancellation states (target-neutral)

A saved continuation / resume slot carries a caller-side cancellation status, orthogonal
to the durable frame bytes:

```text
cancellationStatus = Live | Cancelled
```

- `cancel()` is the single transition `Live → Cancelled`. It is **idempotent**: a second
  `cancel()` on an already-`Cancelled` value succeeds as a no-op (returns the same
  `Cancelled` evidence), never a failure.
- Cancellation is a property of the **continuation/saved value**, not of an individual
  `run`. Cancelling a reusable saved value latches it for **all future** runs.
- `cancellationStatus` is **not** part of the capsule bytes (§7). It is a live, host-local
  lifecycle fact of a particular consumer, not durable frame state.

There is intentionally no `Cancelling` intermediate state: the transition is atomic
(§4), so no observer sees a half-cancelled slot.

## 4. Transition ordering vs the resume claim (answers Q1)

Model `cancel` as a competing atomic claim on the **same slot** as `resume`, so the
existing eager-claim law extends without a new race algebra.

### 4.1 One-shot continuations

`cancel` and `resume` compete for the single atomic claim:

| Sequence | Winner | Result |
|---|---|---|
| cancel, then resume | cancel | `cancel()` → `Cancelled`; the later `resume` is rejected `Cancelled` (§6) **before** the resume entry runs. |
| resume, then cancel | resume | `resume` proceeds; the later `cancel()` loses the claim and returns `AlreadyResumed(op)` — cancellation is too late, the suffix has already begun. |
| concurrent | exactly one | Same eager law as §3.1: one wins atomically; the loser gets its typed rejection (`Cancelled` for a losing resume, `AlreadyResumed` for a losing cancel) **before** touching the suffix. |

The host API surface mirrors `tryResume`:

```text
tryCancel(): Either[ResumeRejected, Cancelled]
```

A losing `tryCancel` (a resume already claimed the slot) returns
`Left(AlreadyResumed(op))`; a winning one returns `Right(Cancelled)`. The `.ssc` sugar
`cancel()` projects a losing race to the boundary envelope, exactly as one-shot `resume`
does for `AlreadyResumed`.

### 4.2 Reusable continuations

There is no single slot to lose, so `cancel` is a **monotonic latch** on the saved value:

- `cancel()` transitions `Live → Cancelled` once; idempotent thereafter.
- Every **new** `run(input)` after the latch is rejected `Cancelled` **at admission**,
  atomically, before the resume entry runs (§5).
- Runs already **admitted / in flight** when `cancel()` lands are **not** force-killed by
  the base contract (see §8); they run to completion and report their own outcome
  normally. Only *new* admissions are blocked.

This keeps the reusable contract simple and side-steps cross-target interrupt semantics.

## 5. Cancelled reusable continuation reports later resumes (answers Q2)

A `run(input)` on a `Cancelled` reusable saved continuation is **rejected at admission**
(§11.1), before allocating an `ExecutionId` or invoking the resume entry, with the typed
`Cancelled` failure (§6).

Placement in the §11.1 admission order: with **lifecycle** (step 2, alongside
expiry/revocation). Cancellation is caller-initiated lifecycle; it is checked in the same
phase as, but is **distinct from**, provider/lifecycle `ExpiredOrRevoked`:

| Failure | Initiator | Meaning |
|---|---|---|
| `Cancelled` (new) | the **caller** | the caller cancelled this continuation before this run |
| `ExpiredOrRevoked` | the **provider/lifecycle** | a lifecycle/retention policy forbids a new admission |
| `AlreadyResumed` | the **runtime** | a one-shot slot was already claimed |

Keeping them distinct preserves the §13 rule that diagnostics are non-collapsible.

## 6. Exact portable diagnostic (answers Q3)

### 6.1 New §13 failure row

```text
| Cancelled | a run was requested on a continuation the caller cancelled |
```

A typed value/effect, target-neutral, never an exception class-name protocol — like every
other §13 category.

### 6.2 Stable boundary projection

Mirroring the `ONESHOT_VIOLATION` projection (§3.1), the `.ssc` boundary form is:

```text
code     = "CANCELLED"
message  = "Cancelled: <Effect>.<op> was cancelled before this run"
rendered = "error [CANCELLED]: Cancelled: <Effect>.<op> was cancelled before this run"
```

As with `ControlRunFailure(AlreadyResumed(op))`, this envelope is not a second rejection
algebra, user `.ssc try/catch` does not intercept it, and the structured `OperationId` +
constructor (not message parsing) is the embedding contract. The Scala host API exposes
the same law without raising, by returning the typed `Cancelled` value.

## 7. Save / restore / cross-host interaction

`cancellationStatus` is **not** serialized into the capsule (§10 of the core spec).
Rationale:

- A capsule is inert bytes; whether a caller later cancels the *restored* continuation is
  a fresh decision on the **consuming** host.
- Cross-host: cancelling on host N does **not** implicitly travel to host M. If a producer
  needs to forbid a restore fleet-wide, that is **provider-backed revocation**
  (`ExpiredOrRevoked`), a different, authenticated lifecycle channel — not local
  cancellation.

This keeps cancellation orthogonal to the durable byte format and to the cross-lane golden
vectors (no capsule-format bump).

## 8. Deliberately left to the owner / out of base contract

- **Interrupting an in-flight suffix** (cooperative or forced) is **out of the base
  contract**: it is target-specific (JVM interrupt, JS cooperative, native signals).
  Recommendation: the base contract blocks only *new* admissions/claims; interrupt is a
  per-profile extension with its own descriptor metadata (§5.2 already reserves a
  `cancellation` slot in the callback descriptor).
- The exact **name** (`Cancelled` vs `RunCancelled` vs `ControlCancelled`).
- The exact **§11.1 ordering slot** if the owner prefers cancellation before or after
  expiry/revocation rather than alongside it.
- Whether `tryCancel` is part of the **public host ABI** or an internal capability.

## 9. Conformance vector 26 realization (only IF ratified)

Once (and only once) the owner ratifies §3–§6, vector `26-cancellation-transitions` would
flip host-only (`structured`) on both host lanes with an oracle demonstrating the
transition table, e.g. a `|`-joined summary:

```text
cancel-then-resume=Cancelled | resume-then-cancel=AlreadyResumed
 | reusable-cancel-blocks-run=Cancelled | idempotent-cancel=Cancelled
```

Sketch (both lanes): build a savable/saved continuation; (a) cancel a one-shot then
attempt resume → `Cancelled`; (b) resume a one-shot then attempt cancel → `AlreadyResumed`;
(c) cancel a reusable saved value then `run` → `Cancelled` at admission; (d) cancel twice →
idempotent `Cancelled`. Caps would be `cancellation` on `scala-explicit` (and JS if the
non-interrupt subset is JS-coverable — it is, since it needs no true concurrency). This
would move the count to 25/26.

## 10. Non-goals (restated)

This proposal invents nothing that binds the owner. It flips no vector, adds no failure
row to the live spec, and changes no reference code. It exists so the cancellation axis
has a concrete, self-consistent option on the table for the semantic owner to decide.
