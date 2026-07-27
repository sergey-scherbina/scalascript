# Portable save-region reification (§10.2) — design for the reference VM

> The build plan for generating a **closed CoreIR resume program** from a compiler-declared
> saveable region, so a Portable capsule (`v2/src/Capsule.scala`, `ssc run-capsule`) can be
> produced from ordinary code rather than a hand-authored resume lambda. This is the missing
> half of conformance vector `15-cross-host-resume`
> (`tests/interop-conformance/pending/15-cross-host-resume.pending`); it does not by itself
> flip the vector (a second admitting backend for the §14.4 N→M matrix is separate).
>
> Grounded in `specs/control-interoperability.md` §10.1-10.2, §14.3 items 10-12.

## 1. Why a syntactic region, not whole-program CPS

In this VM an effect is a runtime value `DataV("Op", [label, arg, k: ClosV])`
(`v2/src/Runtime.scala:237, 334-362`): the continuation `k` at an `effect.perform` is a
**live `ClosV`** built during evaluation. It is the *dynamic extent* from the perform to its
`effect.handle` — there is no static CoreIR term for it. Control-interoperability §10.2 is
explicit that a capsule *"is never reconstructed from a live stack, VM frame, `ClosV`, native
closure, or arbitrary object"* (`control-interoperability.md:737`). So the runtime continuation
is exactly the thing we may **not** serialize.

Two ways to get a *static* resume program:

- **(A) Whole-program CPS** — transform the entire program so every continuation is an explicit
  lambda, then the resume segment is a syntactic lambda. Correct but invasive: it rewrites all
  control flow and interacts with the existing runtime `Op`/handler machinery.
- **(B) Compiler-declared saveable region** — the source syntactically delimits the resume
  region as a lambda `(input) => body`; the pass only has to **close it over its live free
  variables** (the frame) to make it a standalone program. This is precisely what §10.2 means by
  *"a compiler-declared saveable region"* and by the pipeline
  `liveness → closure conversion → defunctionalization → resume-segment closure`.

**This design takes (B).** Whole-program CPS (A) is out of scope; it is only needed to make an
*arbitrary* `perform` point saveable, which is a later, larger effort.

## 2. The saveable-region marker

A saveable region is a CoreIR term

```text
Prim("save.region", [ frameSeed, Lam(1, body) ])
```

- `frameSeed` — the term the source captures as the initial state (the `state` in
  `Continuation.savable(state, machine, codec)`), evaluated once at save time.
- `Lam(1, body)` — the resume region `(input) => body`. `body` may reference `input`
  (the resume value) and **outer live variables** (which become frame slots). At runtime,
  evaluating `save.region` yields a `SavedCapsule` value; the pass below is what makes it
  Portable.

The frontend lowers a `.ssc` saveable construct to this marker. (The exact `.ssc` surface —
whether it is `save { s => ... }` sugar or a library call — is a frontend decision tracked
separately; this spec fixes only the CoreIR marker and the pass.)

## 3. The reification pass `SaveRegion.reify`

`reify(frameSeed, Lam(1, body))` produces a **closed** resume program and the frame value:

```text
reify : (Term, Term)  ->  (frame: Term, resume: Program)
```

Stages (the §10.2 pipeline, for region B):

1. **Liveness.** Collect the free **outer** de Bruijn locals of `body` — every `Local(k)`
   whose index reaches above the region lambda's own binders. These, together with `frameSeed`,
   are the frame slots. (A `Local` bound *inside* `body` is not a slot.)
2. **Frame construction.** Emit `frame = Ctor("frame", [frameSeed, slot1, slot2, ...])` — a
   flat tuple of the captured seed plus each live outer value, evaluated at save time. This is
   the `DurableValue` graph for the frame (first-order scalars in slice 1; nominal/graph codecs
   are §9.1/§9.3, later).
3. **Closure conversion.** Rewrite `body` into a closed 2-argument lambda
   `Lam(2, body')` = `(frameParam, input) => body'`, where every free outer `Local(k)` is
   replaced by a field read `Prim("data.at", [Local(1 shifted), Lit(slotIndex)])` from
   `frameParam`, and the region's `input` becomes `Local(0 shifted)`. The result references no
   free variables — it is a standalone `Program(defs, Lam(2, body'))`.
4. **Resume-segment closure.** Any top-level defs `body` transitively calls are carried into the
   resume `Program.defs` (so `Global` references stay closed under
   `Reader.validate`). Slice 1: bodies that call no user globals, so `defs = Nil`.

The `(frame, resume)` pair feeds `Capsule.encode` unchanged in shape (the frame slot generalizes
from a single int to a CoreIR value; see §5).

## 4. Correctness obligation

For every admitted `input`, the reified capsule must observe the region's original semantics:

```text
run(reify(frameSeed, region), input)  ==  eval( App(region, [input]) )   -- with frameSeed live
```

i.e. running the *closed, frame-folded* resume on `(frame, input)` yields exactly what inlining
the region lambda on `input` (with its captured environment) would. The pass test asserts this
differentially for a range of inputs, and asserts the reified capsule round-trips through
`freeze` → bytes → `run-capsule` (fresh process, no machine) with the same result.

## 5. Capsule frame generalization

`v2/src/Capsule.scala` carries `(frame VALUE)`. **Updated 2026-07-27 (slice 3):** `VALUE` is a
`Lit` **or a `Ctor` whose fields are recursively values** — not only a flat `Ctor` of `Lit`s — so a
nominal/structured slot (`Pair(3,4)`, a nested record, a list cell) travels as data. Decode
reconstructs the value; `run` applies the resume entry to `(frameValue, input)`.

**That set is enforced, not merely described** — `Capsule.validateFrame` rejects anything else at
admission, naming the offending node (`BUGS.md portable-capsule-frame-unvalidated`). Before it, the
frame was taken as an arbitrary `Term`: `(global g)` injected a closure into the resume and
`(local 0)` reached the compiler as an out-of-scope index. `Lam` is rejected as well — a lambda is a
value at runtime but *code* in the bytes, and the whole point of Portable CodeMode is that code
travels only as validated bytes.

The resume-digest keeps covering the resume program bytes only — it is a *code* digest (§10.1).
**RESOLVED 2026-07-27 (Sergiy's decision (c)):** the data half is now covered by a keyed **seal**
rather than by the digest — envelope v2 carries audience/tenant/budget/signature, the signature
being HMAC-SHA256 over the canonical body with an empty signature slot, so an edit to any field
(including the frame) breaks it. See `specs/portable-capsule-seal.md`. The seal is conditional on a
key, exactly as on the host lane, and that conditionality is what makes the two lanes' promises
equal. v1 envelopes stay admissible as legacy/unsigned (a keyed runner rejects them), which is also
what keeps the committed `fx-open.portable` fixture usable.

## 6. Staging (each an independently landable slice)

1. **First-order scalar/tuple frame** — ✅ **LANDED** (`SaveRegion.reify`, explicit slots):
   frame construction + closure conversion of a closed region lambda into `Lam(2, …)`, end-to-end
   via `run-capsule`.
1a. **Automatic liveness** — ✅ **LANDED** (`SaveRegion.reifyAuto`): the frame slots are *derived*
   from a free-outer-variable analysis (`freeOuterIndices`) of a region `Lam(1, body)`, and `body`
   is folded over the frame tuple by a depth-aware de-Bruijn `rewrite` (verified on a nested-lambda
   region, `(input) => a + input*b`). First-order scalars; no user-`Global` calls, no inner effects.
2. **Global closure** — ✅ **LANDED** (`ebc576bec`, `SaveRegion.closeGlobals`): the transitive
   closure of the globals the closed body reaches is emitted as `resume.defs`, in the source
   program's own relative order, with a name marked reached before its body is scanned (so
   recursion terminates) and a LOUD error for a root that names no def.
3. **Nominal / graph frame** — ⚠️ **HALF LANDED (2026-07-27).** *Done:* non-scalar slots are
   admitted and pinned (`frameOfTerms`, `ssc freeze-region-nominal`, `Capsule.validateFrame`
   defining the legal value set — see §5). *Not done:* aligning the VM frame with the host
   `DurableCodec` **byte format** for cross-lane identity, which is blocked on the format decision
   in `BACKLOG.md portable-capsule-integrity` (the two lanes currently promise different things
   about a capsule). Settle that before slice 5.
4. **Effectful continuation** — ✅ **LANDED 2026-07-27, and the premise below was WRONG.** This
   slice was planned as "needs a local CPS of the region"; **measured, it needed none.** An
   Fx-CLOSED region — the `effect.perform` *and* its `effect.handle` both inside — already reifies
   and runs machine-less as-is, because the pass treats the effect prims as ordinary `Prim`s and
   the runtime folds them (`Runtime.scala:1231` → `PortableEffects.eval`; a plain `Lam(1, …)`
   handler is always `HandlerDispatch.Matched`). Verified end-to-end on
   `(input) => effect.handle(effect.perform("E.get", input), (event) => a * 10)` with the frame
   slot read from **inside the handler lambda** (so the depth-aware rewrite is exercised): `50` in
   a fresh process for every input.

   What the measurement DID find is the opposite failure — the **Fx-OPEN** case was fail-open: a
   region performing with no handler froze happily and the resume returned
   `Op("E.get", 8, <closure>)`, handing a **live continuation** to a runner that holds no machine
   and no handlers. §11.3 requires Fx-closedness, so it is now refused in two places:
   `SaveRegion.assertFxClosed` at **reify time** (before any bytes exist; a `handle` protects its
   *computation* only, not its own handler body, and a call to a performing def counts as a perform
   at the call site), and `Capsule.run` at **run time** for capsules from any other producer —
   pinned by a committed fixture `v2/conformance/fixtures/fx-open.portable`, frozen by the
   pre-guard build, since the current tool can no longer produce one.

   Still out of scope (unchanged, §7): an effect whose handler is OUTSIDE the region — that is
   whole-program CPS.
5. **Second admitting backend** — a non-JVM runtime that admits+runs the Portable capsule, for
   the §14.4 cross-backend N→M matrix. Only after this does vector 15 flip.

## 7. Non-goals

Whole-program CPS (making an *arbitrary* `perform` point saveable), higher-order defunctionalization
of escaping closures, `DurableRef` replacement, and the frontend `.ssc` surface are out of scope
here. This document fixes the CoreIR marker, the reification pass, and the correctness law so the
staged slices have a stable target.
