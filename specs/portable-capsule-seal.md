# Portable capsule seal — giving the VM lane the host's format-v3 promise

> Sergiy's decision (c), 2026-07-27 (`BACKLOG.md portable-capsule-integrity`): the VM Portable
> capsule adopts the **host lane's** security envelope so both lanes promise the same thing about
> what a capsule guarantees. Rejected: (a) document the hole, (b) extend the digest over the frame.

## 1. The problem this closes

`v2/src/Capsule.scala` admission validates the **resume program** (`Reader.validate`) and re-checks
`resume-digest`, which is by design a **code** digest (§10.1 `Portable(resumeCodeDigest, …)`). The
**frame** — the data half — is covered by neither. Measured: editing `(lit (int 5))` → `(lit (int 99))`
in a frozen capsule is accepted silently and the run returns `111` instead of `17`, exit 0.

The host lane (`v2/host/scala/control/.../DurableCapsule.scala`) already has an answer: a keyed
HMAC-SHA256 over the canonical body, plus audience/tenant binding and a budget. So the two lanes
disagreed about what a capsule *is* — right as vector-15 E4 heads for a cross-backend N→M matrix
that would freeze goldens on that disagreement.

## 2. What is adopted, verbatim where possible

From the host's `AdmissionPolicy`:

| Field | Meaning | Failure |
|---|---|---|
| `signingKey` | symmetric HMAC-SHA256 key. **Non-empty ⇒ freeze signs and admission rejects a missing / forged / tampered signature.** Empty ⇒ the trusted in-process path, unsigned. | tampered |
| `audience` / `tenant` | the runner this capsule is addressed to; a mismatch is a rejection | tampered |
| `requiredBudget` | the budget the capsule demands; more than the runner has is a **resource** rejection, a different category | resource-limit |

**The key never travels in the capsule** and is never part of the envelope — same as the host.

The conditional nature is deliberate and is *itself* the parity: the host's promise is also
conditional on a key being configured, so "both lanes promise the same thing" means both are sealed
when keyed and both are trusted-in-process when not. An unkeyed runner still accepts an unsigned
capsule; that is the host's contract, not a residue of the old hole.

## 3. Envelope v1 → v2

```text
v1:  (portable-capsule (version 1) (resume-digest HEX) (frame VALUE) (resume PROGRAM))
v2:  (portable-capsule (version 2) (resume-digest HEX) (audience A) (tenant T) (budget N)
                       (signature HEX) (frame VALUE) (resume PROGRAM))
```

- The **signature covers the canonical body with an EMPTY signature slot**, domain-separated by
  `ssc-portable-capsule-sig-v1\0` — the same construction as the host's `signingMessage`, so an edit
  to *any* other field (including the frame) changes the message and breaks the HMAC. This is what
  probe A of `BUGS.md portable-capsule-frame-unvalidated` was missing.
- `resume-digest` stays and keeps its meaning: it is the **code** digest, checked independently, so a
  swapped resume program is still caught on the unsigned path.
- **v1 capsules remain admissible** as legacy/unsigned. This is not a loophole left open: a runner
  *with* a key rejects them, exactly as it rejects an unsigned v2. Keeping v1 readable is also what
  lets the committed fixture `v2/conformance/fixtures/fx-open.portable` — which the current tool can
  no longer produce, by design — go on testing the Fx-closed run guard.

## 4. Configuration (VM side)

The host takes an `AdmissionPolicy` object; the VM lane is a CLI, so the same fields come from the
environment, read once at freeze and at run:

| Env | Meaning |
|---|---|
| `SSC_CAPSULE_KEY` | raw UTF-8 signing key. Unset/empty ⇒ unsigned/trusted-in-process. |
| `SSC_CAPSULE_AUDIENCE` / `SSC_CAPSULE_TENANT` | bound at freeze, checked at admission |
| `SSC_CAPSULE_BUDGET` | demanded at freeze; at admission compared against `SSC_CAPSULE_RUNNER_BUDGET` |

## 5. Admission order (fail-closed, cheapest and most specific first)

1. envelope shape / version — unsupported version rejects
2. **signature** — if the runner is keyed: a missing, malformed or non-matching signature rejects.
   If the runner is unkeyed: a signature present in the capsule is *not* verified (no key to verify
   with) and does not admit anything extra — the capsule is treated as unsigned.
3. **audience / tenant** — a capsule bound to a different runner rejects
4. **budget** — `required > runner` rejects, as a *resource* failure, kept distinct from tampering
   (the §13 non-collapsibility rule: a quota problem must not be reported as an attack)
5. `Reader.validate` on the resume program — unchanged
6. `validateFrame` (E2) — unchanged; the frame is DATA, never code
7. `resume-digest` — unchanged

The Fx-closed run guard (E3) is unchanged and still fires at run time, after all of the above.

## 6. Verification

`v2/conformance/portable-capsule.sh` gains, alongside its existing 25 lines:

- keyed freeze → run **with the same key** succeeds
- keyed freeze → **frame edited** → rejected. *This is the line that proves (c) bought something*:
  the same edit on the unsigned path still runs (the existing `data-only frame edit still runs`
  line), so the two together show the seal is what makes the difference, not a blanket refusal.
- keyed freeze → run with a **different key** → rejected
- keyed freeze → run **unkeyed** → treated as unsigned, still runs (documents the trusted path)
- unsigned freeze → run **keyed** → rejected (a keyed runner does not accept unsigned input)
- audience/tenant mismatch → rejected; budget over the runner's → rejected as a *resource* failure
- v1 legacy fixture still admitted unkeyed (the Fx-open guard keeps working)
