# Durable cancellation (vector 26) — the contested points, for the semantic owner

Companion to [`durable-cancellation-proposal.md`](durable-cancellation-proposal.md). That document
is one self-consistent option; this one isolates **where a different choice would give materially
different semantics**, so the decision is made on the forks rather than on the whole document.

Written 2026-07-27 at Sergiy's request ("покажи спорные места, потом решу"). Nothing here is
implemented; no vector flips until these are settled.

Five forks, ordered by how much they cost to change *later*. D1–D3 are hard to reverse once the
diagnostic and the capsule format ship; D4–D5 are cosmetic-to-cheap.

---

## D1. `resume` then `cancel` → the proposal reports `AlreadyResumed`

**Proposal (§4.1, row 2):** a `cancel()` that arrives after the resume claim loses the race and
returns `AlreadyResumed(op)`.

**Why it is contested.** The caller asked to *cancel* and receives a diagnostic named after
*resumption*. §13's own rule is that diagnostics are **non-collapsible** — but this collapses two
distinct facts ("your resume lost" and "your cancel was too late") into one category. A caller that
wants to know *whether it managed to stop the work* now has to interpret `AlreadyResumed` as "no",
and that meaning is context-dependent: the same value means "someone else resumed first" in the
resume path.

| Option | Consequence |
|---|---|
| **(a) `AlreadyResumed`** (proposal) | No new failure row. Reuses the frozen claim law verbatim. Ambiguous for the caller; mild tension with the non-collapsibility rule. |
| **(b) A distinct `TooLateToCancel`** | Unambiguous, and honours non-collapsibility literally. Costs a **second** new §13 row (the arc adds `Cancelled` already) and a second boundary projection. |
| **(c) `cancel()` succeeds idempotently, reporting that the suffix already ran** | Friendliest surface: cancel is "make sure it does not run *from here on*", which is already the reusable semantics (§4.2). Cost: cancel is no longer a symmetric competitor for the one-shot claim, so §4.1 and §4.2 stop sharing one model. |

**My recommendation: (b).** The extra row is cheap and it is exactly the kind of distinction §13
exists to preserve; (a) saves a row by spending clarity in the place the spec says not to.

---

## D2. In-flight runs are not stopped

**Proposal (§4.2, §8):** `cancel()` blocks only *new* admissions. Runs already admitted continue to
completion; interrupting a running suffix is out of the base contract as irreducibly target-specific
(JVM interrupt / JS cooperative / native signals).

**Why it is contested.** It is defensible and portable, but it means `cancel()` can return
`Cancelled` while the very work the caller wanted stopped is still running — and the API gives no
way to find out. For a *durable* continuation that may be a long-running resumed suffix, that is the
common case, not an edge case.

| Option | Consequence |
|---|---|
| **(a) Block new admissions only** (proposal) | Uniform across every target, no per-profile divergence. `cancel()` is weaker than the word suggests. |
| **(b) Base contract requires a cooperative cancellation checkpoint** | `cancel()` means something on in-flight work everywhere. Requires every admitting backend to thread a cancellation token through the resume entry — real work in each host, and it constrains future backends. |
| **(c) (a) as the base, plus a mandatory `cancellationScope` capability bit** | Callers can *detect* which they are on (`blocks-new` vs `interrupts-in-flight`) instead of guessing. Cheap; makes the weakness explicit rather than silent. |

**My recommendation: (c).** It keeps the portable contract uniform, and turns "cancel is weaker than
it sounds" from a footnote into a machine-readable fact — the honest version of (a).

---

## D3. `cancellationStatus` is NOT in the capsule

**Proposal (§7):** cancellation is host-local lifecycle, not durable frame state. Cancelling on host
N does not travel to host M; fleet-wide forbidding is provider-backed revocation
(`ExpiredOrRevoked`), a separate authenticated channel.

**Why it is contested.** This is the fork with the longest tail. The whole point of a durable
continuation is that it outlives the process; a user who cancels one and then restores the same
capsule on another host gets a live continuation again. The proposal's answer — "use revocation" —
is coherent, but it means **cancel and revoke are two different verbs the user must choose between
correctly**, and the wrong choice fails silently (the work runs).

| Option | Consequence |
|---|---|
| **(a) Host-local only** (proposal) | No capsule format bump, no change to the cross-lane golden vectors, cancellation stays orthogonal to the byte format. Cancel does not survive a save/restore — arguably surprising for a *durable* API. |
| **(b) A cancelled flag in the capsule** | Cancel survives the round-trip on the same bytes. Costs a **format v4** bump and re-freezing the cross-lane goldens; and it is still not fleet-wide (an older copy of the bytes is still live), so it buys less than it appears to. |
| **(c) (a) + require `cancel()` to state it is host-local** in the API name/docs (e.g. `cancelLocally`) | Zero format cost; removes the silent-failure mode by making the scope visible at the call site. |

**My recommendation: (a) + (c).** (b) looks like the "safe" choice but does not actually deliver
fleet-wide semantics — only revocation does — so it pays a format bump for a partial guarantee.

---

## D4. Where `Cancelled` sits in the §11.1 admission order

**Proposal (§5):** alongside lifecycle (step 2), distinct from `ExpiredOrRevoked`.

**Contested only when both apply**: a continuation that is *both* expired and cancelled reports
whichever is checked first, and that choice is observable in conformance goldens. Options: cancelled
first (caller intent wins — "you cancelled it, that is why"), expiry first (provider policy wins),
or the proposal's "same phase" which leaves the tie unspecified — that last one should not ship as
written, because an unspecified tie becomes a per-lane accident.

**My recommendation: cancelled first**, and say so explicitly. The caller's own action is the more
informative answer to "why did my run not happen".

---

## D5. Naming and ABI surface (cheap, but decide before the vector flips)

- `Cancelled` vs `RunCancelled` vs `ControlCancelled` — the proposal uses `Cancelled`.
- Boundary code `CANCELLED` and the exact `message` wording (§6.2).
- Whether `tryCancel` is public host ABI or an internal capability behind the `.ssc` `cancel()`
  sugar.

These bind the conformance oracle text, so they need to be fixed before vector 26 can be written —
but none of them changes the semantics.

---

## What happens after the decision

Only once D1–D5 are settled does vector `26-cancellation-transitions` become writable:
host-only (`structured`) on both lanes, oracle demonstrating the transition table
(cancel-then-resume, resume-then-cancel, reusable-cancel-blocks-run, idempotent-cancel), moving the
durable count to **25/26**. Vector 15 is the other open one and is a separate, much larger arc
(§10.2 global closures + effectful regions + a second admitting backend).

**Do not flip 26 by bending the realization to the pending's stated mechanism** — the pending record
forbids inventing these rules precisely so that the owner's answer, not the harness's convenience,
fixes them.
