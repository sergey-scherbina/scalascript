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

- Corpus MATCH: **408 / 509 (80%)** — `specs/v2.2-p6.5-corpus.sh`.
- `F` self-compiles: X1 fixpoint `stage1 == stage2`, byte-identical (326,331 B); `--self`
  153 ok / 0 FAIL — `specs/v2.2-p6.5-fsub.sh --self`.
- Of the 101 remaining DIFFs: **~29 are OUT** (§5), **~2 are deferred** (§6), and the rest fall
  into two large but legitimate arcs (§4) plus a handful of clean 1-offs.
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

## 7. The F4 cutover contract

**F4 = declare `F` canonical for the surface above, retire the `FrontendBridge`, shrink the kernel.**

Preconditions to declare `F` canonical:
1. `F` covers every construct marked IN §4.1 (met: the 408) — the two open arcs §4.2 may be
   completed **before or after** cutover (they are additive to `F`, no kernel δ, no rework).
2. The corpus gate distinguishes MATCH / classified-OUT (§5) / DEFERRED (§6) / genuine-FAIL, so a
   real regression is loud while the OUT set stays green (self-maintaining, like negtc).
3. `F` self-compiles (fixpoint holds) — standing invariant, currently green.

On cutover:
- `bin/ssc` (v2 native tier) uses `F` as the front for v2's surface.
- The `FrontendBridge` (~1,200 δ-prims serving v1's full surface) is removed from the kernel.
- Kernel target: **~2,400–2,800 lines** (from 5,936), realizing the "small" axis of the vision.

**Sequencing is an open decision** (surfaced to Sergiy): *cutover-first* (declare canonical on the
408 surface now → retire bridge → shrink kernel → add given/summon + extensions to `F` afterward)
vs *surface-first* (build the two arcs to ~460+ first, then cut over). Both are valid; the arcs
being additive-with-no-kernel-δ is what makes cutover-first safe.

---

## 8. Verification

- Surface coverage: `specs/v2.2-p6.5-corpus.sh` (MATCH count) + the OUT/DEFERRED classification
  from this document.
- `F` correctness on its own source: `specs/v2.2-p6.5-fsub.sh --self` (fixpoint).
- No kernel regression from cutover: full `v2/conformance/check.sh` (only it catches shared-seam
  regressions like `floatStr` — the run-ir-only fixpoint cannot).
