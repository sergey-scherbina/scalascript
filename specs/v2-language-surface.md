# ScalaScript v2 — Language Surface Contract

**Status:** DRAFT for ratification (2026-07-19). This is the **F4 contract**: it defines what
ScalaScript v2's language *is*, and therefore what the canonical self-hosting front `F`
(`specs/v2.2-p6.5-fsub.ssc`) must cover before it replaces the `FrontendBridge` and lets the
kernel shrink.

This document is the durable form of **Decision C** (Sergiy, 2026-07-19): *v2 defines its own
ideal surface; it does not inherit v1's warts.* See `SPRINT.md` §v2-finish.

---

## 1. Why this document exists

v2's north star is: **ideal, small, powerful, fully self-hosted.** Two of those three
(small, self-hosted) are gated behind one decision — *what is the language?* — because:

- The kernel (`v2/src`, ~5,936 lines) still carries a `FrontendBridge` (~1,200 δ-prims) whose
  only job is to serve **v1's entire messy surface** through the bridge. It exists because we were
  measuring `F` against *byte-identity to the whole corpus as the v1 oracle lowers it*.
- Decision C changes the target: `F` must cover **v2's ideal surface**, not reproduce v1's
  lowering artifacts. Once "v2's surface" is pinned, `F` can be declared **canonical** for it,
  the bridge retired, and the kernel shrunk to the ~2,400–2,800 lines the vision calls for.

So this contract has real teeth: **everything IN must be covered by `F`; everything OUT is
explicitly not `F`'s responsibility and not a gap.**

---

## 2. The principle (Decision C)

A construct is **IN** v2's surface if a *clean, ideal ScalaScript* should have it — judged on its
own merits, not on whether the v1 oracle happens to emit it.

A construct is **OUT** if it is one of:
- **an oracle bug** — the v1 front/lower produces something wrong or degraded, and `F` already
  emits the *correct* Core IR. Here v2 is simply **better than v1**; matching the oracle would mean
  regressing `F`.
- **a v1 lowering wart** — a mechanical artifact of *how* v1 lowers a feature (not the feature
  itself), with no place in a clean language.
- **deferred** — a legitimate feature that needs a kernel change we have chosen not to make yet.
  Listed separately so it is never confused with "excluded."

The practical check for anything IN stays **byte-identity of `F`'s Core IR against the `v2/lib`
oracle** — because for genuine features the oracle *is* the correct target. The check is a proxy;
the *goal* is a clean complete language.

---

## 3. Current coverage (measured)

> **★ MEASURED CORRECTION (2026-07-20, F4 reversible-staging).** The **byte-identity** numbers below
> are PRE-typed-regime and no longer the coverage oracle. Since F5b Stage 1, F emits **TYPED** Core IR
> that diverges from the untyped oracle byte-for-byte **by design** — the byte gate
> (`specs/v2.2-p6.5-corpus.sh`) now measures **MATCH 225/510**, which is a typed-divergence artifact, not
> a gap count. The trustworthy cutover metric is **output-equivalence** (`specs/v2.2-p6.5-semantic.sh`):
> over 659 programs (510 corpus + 149 tower) — **246 output-equivalent, 401 oracle-can't-run-in-the-bare-
> kernel-jar (rc≠0, need plugins/servers/effects drivers), 1 too-large, 0 F-emits-no-IR, and exactly 12
> F-DISAGREE** (the real gaps: effects×4, extensions, for-comprehensions, tagless-multi-file,
> standard-scala-multifence, scala-js-demo, dsl-multi-pass, wasm-primes/sorting). All 12 = oracle correct,
> F emits **invalid IR** (dangling `(global …)` / arity) → all are **GAP** (F incomplete), none are OUT.
> Under the output lens §5's byte-level OUT cases do NOT appear as disagreements (no output change, or the
> oracle can't run them). The F4 cutover gate is therefore output-based: `specs/v2.2-p6.5-semantic.sh
> classify` (self-maintaining, manifest `specs/v2.2-p6.5-classify.expected`). GREEN today: 0 genuine-FAIL.

- Corpus MATCH: **417 / 510 (81%)** — `specs/v2.2-p6.5-corpus.sh` (was 408/509; +9 across deep6/deep7:
  given/using/summon + top-level extensions + extension-methods-in-given + Mirror/derived-given summon).
- `F` self-compiles: X1 fixpoint `stage1 == stage2`, byte-identical (368,086 B); `--self`
  153 ok / 0 FAIL — `specs/v2.2-p6.5-fsub.sh --self`.
- Of the remaining DIFFs: **~29 are OUT** (§5), **~2 are deferred** (§6), and the rest fall
  into two large but legitimate arcs (§4, now partially built) plus a handful of clean 1-offs.
- **Clean ceiling ≈ 471 / 509** — the most `F` can match while staying correct (509 minus the OUT
  set). 100% is neither achievable nor desirable: the gap above the ceiling *is* v2 being right.

---

## 4. IN — v2's language surface

Everything `F` covers today (the 408) is IN. In addition, these are IN and are the remaining
**legitimate feature arcs** an ideal ScalaScript should have. They need no kernel change (`F` and
the oracle share one kernel, so any prim the oracle emits, `F` can emit too); they are simply
large, multi-part front-end arcs not yet built.

### 4.1 Covered today (representative, not exhaustive)
- Core: `val`/`var`/`def`, top-level and nested; generic top-level defs with type params;
  default parameters (declaration + call-site synthesis); curried application; blocks and
  multi-statement lambda bodies.
- Data: `case class`, `enum`, `sealed trait` hierarchies; field access; `type`/`opaque type`
  aliases (erased); tuples; `Some`/`None`/`Option`, collections literals.
- Control: `if`/`else`, `while`, `for`/`yield`, `match` — including **nested constructor
  patterns**, **tuple-outer nested patterns**, type-test patterns (`case _: T`), guards, literal
  and char-literal patterns, `case (a, Some(x))` etc. (ordered resolver + recursive obligation
  discharge).
- Destructuring: block-local `val (a, b) = e`; destructuring binds only pattern vars.
- Interpolation, string/number/char literals, arithmetic, Int-as-I64 semantics.

### 4.2 IN but not yet built — the two open arcs
- **`given` / `using` / `summon` (implicit resolution).** ~10–15 corpus programs. The `X_method`
  naming flips first; the substance is a **given-table + dictionary synthesis** (`__mk_method_obj__`).
  Assessed **tractable in-subset, no kernel δ** — but a large arc. *An ideal ScalaScript has
  principled implicits, so this is IN.*
- **Extension methods (`extension (r: T) def ...`).** A few programs, but a **big arc**: needs the
  deferred **layout E/EB/X frames** so the `extension` header delimits its group and the receiver
  becomes a real parameter (today `F` parses the inner `def` as `(lam 0 … (global n))` — receiver
  dropped). *Extension methods are a first-class ideal-language feature, so this is IN.*

### 4.3 IN — clean 1-offs / architectural
- `_sel_` list-variable registry (~4 programs) — architectural, medium.
- Indexed assignment `a(i) = v`; user symbolic operators (`<~`, `~>`); a few misc medium items.

---

## 5. OUT — deliberately excluded (not gaps)

**~29 corpus DIFFs.** `F` is correct on these; the oracle is not, or the feature is a v1 artifact.
Matching them would regress `F`. They define the space above the clean ceiling.

| Excluded | Count | Why OUT |
|---|---:|---|
| `@`-annotated `case class` field collapse | ~12 | v1 oracle collapses annotated fields to `_`; `F` keeps them. **Oracle bug** (graph-* corpus). |
| `@`-annotated `val` → `_err` | few | oracle mis-lexes the annotation to an error node. **Oracle bug** (spark-*-hive). |
| custom interpolator raw-triple leak (`html"""…"""`, `id"""…"""`) | few | oracle leaks the raw triple-quoted body. **Oracle bug.** |
| actors-`receive` `let(match)` / `if __isTag__` | ~10 | mechanical shape of *how* v1 lowers actor receive; not a language feature. **v1 lowering wart.** |

**Rule:** if a corpus program's only DIFF is one of the above, it is *expected* to DIFF forever.
The corpus gate must treat these as classified-OUT, never as failures (mirrors the negtc gate's
self-maintaining classification, `project_negtc_gate_self_maintaining_0719`).

---

## 6. DEFERRED — legitimate, needs a kernel decision

- **Float E-notation literals** (~2 programs). A genuine feature, but formatting lives in the
  shared `Writer.floatStr`/`floatLit` seam (see `project_coreir_canonical_contract_0716` — `floatStr`
  is SHARED with v1-parity `2.0→"2"` behavior and must not be edited casually). Needs a deliberate
  kernel δ. **Not OUT** (an ideal language has E-notation), but **not blocking F4** either.

---

## 7. F4 (front swap) and F5 (kernel shrink) — two SEPARATE efforts

> **★ RECORD CORRECTION (2026-07-20, from the F4 readiness assessment).** Earlier text here (and
> in SPRINT/ROADMAP) conflated F4 with the kernel shrink. The code says otherwise, on evidence:
> - **The Scala `FrontendBridge` is already gone** — `v2/frontend-bridge` + `v2/plugin-bridge`
>   were deleted from the build and git (`build.sbt:506-509`); the `--v2` bridge lane is retired
>   (`Main.scala:1590-1593`). There is nothing left to "retire" there.
> - **The `__method__`/`__arith__`/`__eq__`/… δ-prims are emitted BY `F`** (F emits *untyped* Core
>   IR, so dispatch is resolved at runtime) and live in `Runtime.scala:2996-3045+`. They are needed
>   to *run F's own output* — declaring F canonical does **not** delete them.
> - Therefore **F4 does not shrink the kernel.** F4 is a front-*library* swap; the kernel
>   (~6,035 lines) is unchanged by it. The kernel shrink is **F5**, a separate deep effort.

### F4 — front swap (the "fully self-hosted" axis)

Replace the current staged front — `v2/lib/ssc1-front.ssc0` + `ssc1-lower.ssc0` (**8,921 lines of
ssc0**) — with `F` (`specs/v2.2-p6.5-fsub.ssc`, **1,847 lines**). `F` becomes the production front
that `bin/ssc` runs (today it is exercised only by the gate scripts). Net: ~8,900 lines of
redundant front library removed; self-hosting realized in the product, not just in the fixpoint gate.
`bin/ssc`/`RunNativeV2` are unchanged — only what `installBin` stages as the tower front changes.

Preconditions:
1. `F` covers every IN construct (§4.1–4.2). **Both arcs now complete (417/510).**
2. A gate distinguishes MATCH / classified-OUT (§5) / DEFERRED (§6) / genuine-FAIL (self-maintaining,
   like the negtc gate) — **✅ MET (2026-07-20): `specs/v2.2-p6.5-semantic.sh classify`** (output-
   equivalence basis; the byte gate is design-divergent post-typed-regime, see §3). Buckets against the
   committed manifest `specs/v2.2-p6.5-classify.expected`; exits 1 on any UNEXPECTED disagreement. Green
   today (0 genuine-FAIL); reports 12 GAP as the pre-flip precondition.
3. The checker (`ssc1-check.ssc0`, a separate pre-pass in `RunNativeV2`) is kept beside `F` or
   folded into it — **open item, F is a parser+lowerer, not a checker.**
4. `F` self-compiles (fixpoint) + `v2/conformance/check.sh` green on the cutover SHA.

Reversible sequence (irreversible step isolated): (1) build the gate classification → (2) stage `F`
behind a flag → (3) dual-run corpus+conformance in CI → **(4) ✅ flip the default front DONE 2026-07-22
(`5e5e1d194`) — F is now default, `SSC_FRONT=legacy` opts out** → (5) delete the old ssc0 front (the
~8,900-line win — still deferred; the fallback depends on it).

#### F4 staging — landed (steps 1-3, REVERSIBLE, default UNCHANGED). 2026-07-20, `v2-f4`.

- **Step 1 — the cutover ratchet:** `specs/v2.2-p6.5-semantic.sh classify` + manifest
  `specs/v2.2-p6.5-classify.expected` (see §8/§3). Green: 0 unexpected disagreement; 12 GAP reported.
- **Step 2 — `SSC_FRONT=F` flag.** The native tier runs `F` when `SSC_FRONT=F` (or `fsub`), else the
  default `ssc1-front`+`ssc1-lower`. Mechanism, all reversible/additive:
  - `v2/bin/ssc1-run-fsub.ssc0` — a copy of `ssc1-run.ssc0` (fence extraction, multi-file source
    closure, YAML front-matter, content projection, and the `NativeCompilation` structural ABI all
    reused unchanged) whose ONLY change is the user program's IR: it is produced by `F`, not by
    `lowerProg`, via `#coreir.decode(#coreir.eval(F_defs ++ expr:compile(userSrc, dq, bs)))` — validated
    byte-identical to the F0.ir gate path. `F`'s source arrives as `--fsub-src`.
  - `RunNativeV2.nativeFrontLayout` reads `SSC_FRONT`; F-mode picks the `-fsub` runner + the staged
    `tower/bin/fsub.ssc` and threads `--fsub-src`. The **checker (`ssc1-check-run.ssc0`) is kept beside
    `F` in both modes** — F is a parser+lowerer, not a checker.
  - `build.sbt installBin` stages `ssc1-run-fsub.ssc0` + `specs/v2.2-p6.5-fsub.ssc`→`fsub.ssc` beside the
    default runner (default lane never touches them).
  - PROVEN: `SSC_FRONT=F bin/ssc run <prog>` produces byte-identical output to the default front
    end-to-end (through the real structural path) on the corpus spread; a GAP program fails cleanly with
    `unbound global`.
- **Step 3 — dual-run gate:** `specs/v2.2-p6.5-dualrun.sh` — `bin/ssc run` vs `SSC_FRONT=F bin/ssc run`
  (the FAITHFUL production path: same launcher, ambient std prelude, checker, plugin host for both
  fronts) on a corpus slice + typed fixpoint byte-identity (`fsub.sh --self`). Expected divergences =
  `specs/v2.2-p6.5-dualrun.expected` (a SEPARATE list — see below). Measured: **29/31 EQUAL** on the
  default single-file slice; fixpoint byte-identical (366,123 B).
- **★ LOAD-BEARING FINDING (step 3): F has an AMBIENT-PRELUDE / PLUGIN gap class BEYOND the 12.** The
  classify gate feeds F per-file `.code` with NO ambient injection, so it OVERSTATES F's front-coverage.
  The real path injects the ambient std prelude (`RunNativeV2.ambientPrelude`, e.g. std/json for
  `jsonRead`) and runs the plugin host; F must then also compile the injected std-module SOURCE and
  resolve plugin-backed globals, which it does NOT yet cover — measured F-worse-than-default on
  `json-read` (unbound `__jsonCoreWrap`), `generators` (unbound `generator`), and similar. So the honest
  "F output-equivalent on 246" is a per-file-coverage number, NOT a raw-drop-in number. **Largely
  RESOLVED by F4a (`a73fb0d2a`): the delegate-fallback makes the PRODUCT (`SSC_FRONT=F bin/ssc`) never
  worse than default for every UNBOUND-GLOBAL gap** (F where it covers, the old front where it leaves a
  dangling ref) — EXCEPT a small documented multi-file runtime-correctness class (F lowers a value wrong
  but with all globals resolved; e.g. dsl-ast-builder, multi-link-imports) that needs F fixes. See §7 F4a.

#### F4a — delegate-fallback LANDED (`a73fb0d2a`). F is now never-worse-than-default → FLIP-READY.

Sergiy chose F4a. The **delegate-fallback** is implemented in `RunNativeV2.compile` (the `frontIsF`
path): after F produces its decoded `Program`, `_root_.ssc.Reader.validate` runs as an unbound-global
pre-check inside a `try`; on ANY failure (validate throws, or `#coreir.decode` inside the F runner
already threw at lower time — `parseProgram` calls `validate` at CoreIR.scala:114) the file is
transparently re-lowered through the **default runner** (`ssc1-run.ssc0`, `fsubSrc=None`) and that result
is used. F covers its subset directly; the old front (kept, now the fallback) covers the rest; output is
byte-identical to default wherever F falls short. `SSC_FRONT_TRACE=1` logs each delegation to stderr.

- **The unbound-global gap classes are caught by the one pre-check** — the 12 single-file gaps AND the
  ambient-prelude/plugin class all emit an *unbound global* (F's incomplete lowering leaves a dangling
  ref), which `validate` (globalOk = a top-level def or an `@`-cell) rejects → fall back. **Runtime-only
  gap handling:** chose the static pre-check + documented-known-gap over a run-time try/rerun (a rerun
  would DUPLICATE already-emitted external side effects — file/DB writes; ~half the corpus is multi-file
  incl. scljet DB writers — so it is unsafe).
- **A small RUNTIME-CORRECTNESS residual survives the pre-check** (task item 2's anticipated class, found
  by the full-corpus sweep): MULTI-FILE programs where F's source-concatenation lowering produces a
  semantically WRONG value with all globals resolved — `dsl-ast-builder` (a `<closure>` where `0`
  expected), `multi-link-imports` (a `()` → `no dispatch for .getOrElse`). The pre-check passes and F
  runs to a wrong result (rc=1). These are DOCUMENTED known-gaps (`specs/v2.2-p6.5-dualrun.expected`) that
  need F's **multi-file lowering fixed**, not a fallback. So F-with-fallback is never-worse-than-default
  **except for this documented multi-file-correctness class** — the honest flip caveat.
- **Gates GREEN with the fallback:** `dualrun` **43/43 EQUAL, 0 DIVERGE** on the default slice (which
  includes every unbound-global gap class); the full-corpus sweep lists only the runtime-correctness
  residuals above. Typed fixpoint byte-identical (the fallback doesn't touch F's self-compile). `classify`
  stays green (raw F coverage, 12 GAP; output notes the product-level fallback).

#### The flip (step 4) — ✅ DONE 2026-07-22 (`5e5e1d194`). F IS the default native front.

- **The flip was one line in `RunNativeV2.frontIsF`:** inverted opt-IN → opt-OUT —
  `sys.env.get("SSC_FRONT").exists(v => v == "F" || v.equalsIgnoreCase("fsub"))` →
  `!sys.env.get("SSC_FRONT").exists(_.equalsIgnoreCase("legacy"))` (a `legacy` escape hatch opts out to
  the old front). No re-stage of resources (installBin already stages both runners + F's source + the
  fallback path). Fully revertible by restoring the opt-in test. **Now: F is the production front,
  ssc1-front+ssc1-lower is the safe F4a fallback** for anything F cannot yet lower — so the flip cannot
  regress any program.
- **Verified before flipping:** a clean full-corpus dual-run (`SSC_DUALRUN_ALL=1`, 528/528 programs,
  default front vs `SSC_FRONT=F`, **0 unexpected divergence**). The sole divergence — `actors-supervision`
  — is the documented concurrent-actor scheduler race (front-independent; adjudicated benign: identical
  line-set on both fronts, F drops no `receive` handler, so NOT `f-stmt-partial-function-block-dropped`).
- **Verified after flipping:** typed fixpoint byte-identical (stage1==stage2, 385,827 B), semantic gate
  248/248, post-flip dual-run 45/45 EQUAL + fixpoint OK. End-to-end with no `SSC_FRONT`: single-file,
  multi-file, and unbound-global-fallback programs each produce output byte-identical to the legacy front;
  `SSC_FRONT_TRACE=1` shows the delegate-fallback firing on gaps.
- Step 5 (deleting the old front, the ~8,900-line win) stays deferred until F covers the fallback set on
  its own, since the fallback depends on it.

### F5 — kernel shrink (the "small" axis), SEPARATE and DEEP

Kernel is **~6,035 lines** (`Runtime.scala` 4,818 + CoreIR 415 + Ssc0 311 + PortableEffects 221 +
PortableDecimal 171 + Main 97). Reaching ~2,800 is **not** a consequence of F4. It requires deep
change — the two candidate levers:
- **Retire the ~1,328-line δ-prim/method-dispatch table** — only possible if `F` emits
  typed/monomorphized IR (direct dispatch). F is untyped today, so this is a large front change.
- **Relocate the perf layers** (FastCode/SelfRec, ~962 lines) and `PortableEffects`/`PortableDecimal`
  off-kernel as tower programs (a prior F5 pass reported these "irreducible" — under re-study).

**Sergiy's decision (2026-07-20): pursue F5 directly.** A feasibility study is establishing the
honest achievable target per-region (move-to-tower / delete / must-stay), fixpoint-verified.

---

## 8. Verification

- **F4 cutover ratchet (the one that gates the flip): `specs/v2.2-p6.5-semantic.sh classify`** —
  output-equivalence, self-maintaining, manifest `specs/v2.2-p6.5-classify.expected`. Green = 0
  UNEXPECTED disagreement. Reports the GAP count (must be 0-after-handling before step 4).
- Output-equivalence on the frozen golden set: `specs/v2.2-p6.5-semantic.sh check` (246/246).
- Surface coverage (byte lens, INFORMATIONAL only post-typed-regime — divergence is by design):
  `specs/v2.2-p6.5-corpus.sh` (MATCH count) + the OUT/DEFERRED classification from this document.
- `F` correctness on its own source: `specs/v2.2-p6.5-fsub.sh --self` (fixpoint).
- No kernel regression from cutover: full `v2/conformance/check.sh` (only it catches shared-seam
  regressions like `floatStr` — the run-ir-only fixpoint cannot).
