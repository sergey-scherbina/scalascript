# F5b Design — teach `F` to emit typed/monomorphized Core IR

**Status:** DESIGN, ratified direction (Sergiy chose F5b, 2026-07-20). This is the plan for the
largest phase of v2: make the self-hosting front `F` (`specs/v2.2-p6.5-fsub.ssc`) emit **typed**
Core IR so the kernel can retire its ~1,300-line runtime-dispatch δ-table. Companion:
`specs/v2-language-surface.md §7` (F4/F5 split), and the F5 feasibility findings in SPRINT.

## 0. Bottom line

- **The typed-emit machinery already exists and is proven in the tower: `v2/lib/mira-emit.ssc0`.**
  Mira does full HM (Algorithm W — `mira.ssc0`) and a typed `erase` that emits `i.add`/`f.add`/
  `sconcat` **by inferred type** (`mira-emit.ssc0:50-70`), monomorphizes Show/Eq/Ord per concrete
  type (`:157/:212`), and dict-passes only for genuinely Num/Ord-polymorphic functions (`:25-40`).
  This IS the F5b target IR — but Mira works on its own clean typed-λ surface + its own `Term` ADT,
  **not full `.ssc`, not F's representation.**
- **The kernel typed prims F must target already exist** (`Runtime.scala:2566-2662`: `i.*`/`f.*`/
  `big.*`/`str.*`, dup'd in `resolve1/2/3` fast paths `:4257-4315`). The δ-table
  (`__arith__`→`arithFast`→`arithOp`; `__method__` fan-out) is **pure runtime indirection over prims
  that already exist statically.** Typed IR bypasses it with **zero new prims.**
- **The hard part is not the emit — it's that F has no AST and no types** (F is a *streaming string
  lowerer*: parse IS emit, fused; 40 `emit*` functions concatenating IR text). Folding Mira in
  requires giving F an AST, an HM inferencer over the *full* `.ssc` subset, and — because typed IR
  **diverges from the untyped oracle by design** — a NEW verification gate.

## 1. Honest reachability (the scope reality-check)

Typed-IR **alone** deletes ~1,100–1,500 lines of dispatch fan-out → kernel **6,035 → ~4,500–4,900**.
It is the single biggest lever and it makes numeric code *faster* (direct call vs tag dispatch). But
it does **not** by itself reach ~2,800. The measured 6,035→~2,800 decomposition (≈ −3,235):

| Effort | Δlines | Independent of typing? |
|---|---:|---|
| **Typed IR (this project, F5b)** | −1,100…−1,500 | — (the enabler; also perf-positive) |
| Effects → tower (PortableEffects + effect δ + V2EffectContext) | ≈ −300 | yes (K3 redesign) |
| Decimal → tower (PortableDecimal + `dec.*`) | ≈ −200 | yes |
| FastCode/SelfRec removal | ≈ −800 | yes (perf-risky; typed IR softens the perf cost) |
| Fast-path dedup + interop glue trim | ≈ −150…−250 | partly |

**So ~2,800 = F5b typed-IR + three more orthogonal deep efforts.** Typed-IR gets ~40% of the shrink
(the largest contiguous block) and lands ~4,700. Each other effort was already proven "irreducible
within byte-identical mechanical scope" by the prior F5 pass — they each need their own redesign.

**Effort estimate:** ~17–27 focused sessions for typed-IR to ~4,700; the other three are separate
multi-session efforts on top.

## 2. F's pipeline and the insertion point

F today: `compile(src,dq): String` is `parse ⇒ emit-text`, **fused** — no lower, no types, no tree.
The only "typing" is literal-peeking (`typeOfArg :1333`). F5b splits F into three phases:

```
.ssc subset ──parse──▶ AST(F) ──infer(HM)──▶ typed AST ──erase──▶ typed Core IR text
```
- **parse→AST:** change the 40 `emit*` to *build nodes* instead of strings (return type
  `(Node,toks)` not `(String,toks)`). Mechanical but pervasive.
- **infer:** a Mira-style HM pass over AST(F), extended to F's value model (Map/Set, `BigInt`≠`Int`,
  char/codepoint, optics, `direct{}`, interpolators — Mira's type language lacks these).
- **erase:** the current `emit*` bodies, now reading the resolved type to choose `i.add`/`f.add`/
  `big.add`/`sconcat`, and `(app (global _sel_map) …)` / monomorphized instance call vs `__method__`.

## 3. ★ The new verification regime (the crux)

Today's gate = `F(P)` **byte-identical** to the *untyped* oracle `ssc1-front+ssc1-lower(P)`, with the
fixpoint following algebraically. Typed F **diverges from the untyped oracle by construction**, so
byte-identity-to-oracle is gone. Replace with three independently-checkable legs (each must
COMPUTE BOTH SIDES AND COMPARE — never pre-judge; see `AGENTS.md` measurement rule):

- **(a) Semantic equivalence — the new ground truth.** FREEZE a golden **output** set from the
  untyped oracle *before any F change* (`out_untyped = run-ir(oracle(P))` for the whole corpus +
  tower examples). Then for typed F: `ASSERT run-ir(F_typed(P)) == out_untyped`. This is the immovable
  truth; it does not move when F's IR changes. (Guard the "equal empties"/LF-normalization trap —
  print `expected=…got=…` on every mismatch.)
- **(b) Typed fixpoint.** `stage1 = F_typed(F_src)`; `stage2 = run(stage1)(F_src)`; `ASSERT
  stage1 == stage2` — byte-identical *typed* output. Same `--self` algebra, now at the typed level.
  Proves reproducibility, not correctness (that's leg a).
- **(c) Differential typed-IR snapshot per stage.** After a stage lands, snapshot `F_typed(P)` as a
  golden *typed-IR* set; the next stage must keep (a) green and only change typed IR intentionally.

**Deletion lags emission by a full stage.** Delete a δ arm only when **nothing on the kernel** emits
it — not just F, but the ssc0 tower and the conformance surface too. Verified by the full
`v2/conformance/check.sh` (the only gate that catches shared-seam regressions — the `floatStr`
lesson). So: type a class → (a)+(b) green → snapshot → THEN delete the now-dead δ arm → (a) green again.

## 4. Staged incremental plan (each independently verifiable)

- **Stage 0 — foundation (no IR change, no deletion).** (i) Freeze the golden-output set (leg a) —
  safe, valuable regardless. (ii) Re-architect F parse→AST with a trivial `erase` reproducing today's
  *untyped* strings byte-identically. Verifying it changes NOTHING (still byte-identical to oracle) is
  the safety net for the risky refactor. **Kernel Δ 0.** (~3–5 sessions.)
- **Stage 1 — arithmetic/comparison.** Minimal HM for numeric/String/Bool; erase `+ - * / % < … == ++`
  to `i.*`/`f.*`/`big.*`/`sconcat`/`seq` by type. Types most of F's own emission (F emits `__arith__`
  ~1432×) and makes it faster. Delete numeric/String/cmp arms of `arithFast`/`arithOp`/`__arith__`/
  `__unary__`/`__eq__` (keep Op-lifting arms until effects; keep `->`/`to`/`Map+`/`List-` until St.3).
  **Δ ≈ −150…−220.** (~3–4 sessions.)
- **Stage 2 — string/char methods** → `slen`/`scodeAt`/`str.*`. **Δ ≈ −60…−120.** (~1–2 sessions.)
- **Stage 3 — collections + overloaded operators** (List/Option/Either → `_sel_*`; typed `->`/`to`/
  `until`/`Map+`/`List-` → constructors). Subsumes the stalled `_sel_` list-var-registry via inferred
  type. **Largest deletion, Δ ≈ −500…−700.** (~4–6 sessions.)
- **Stage 4 — user types: case-class methods/fields/`.copy`, typeclasses (Show/Eq/Ord), given/using.**
  Port Mira's monomorphized `emitShowFromType`/`emitEqFromType`/instance resolution. **Δ ≈ −200…−350.**
  Hardest inference. (~5–8 sessions.)
- **Stage 5 — cleanup + fast-path dedup** (`resolve1/2/3` become the only copy). **Δ ≈ −80…−150.**

Running total Stages 1–5: **≈ −1,100…−1,500 → kernel ~4,500–4,900.**

## 5. Risks

- **Effects (Op-lifting):** `__arith__`/`__method__` thread unhandled `DataV("Op")` through arithmetic;
  typed `i.add` keeps an Op-lifting fallback (`liftArith2`, `resolve2:4306`) — **verify per stage**
  (silent-wrong risk). Effects themselves stay in-kernel until the separate K3 effects-to-tower.
- **`BigInt`≠`Int`:** Mira defaults numerics to `Int` and has no `TyBig`; F's inferencer MUST add it
  or silently truncate.
- **Overloaded `+`/`-`:** `arithOp` overloads them (tuple `->`, range `to`/`until`, `Map+`, `List-`,
  char/codepoint) — typed emit must route each by type to its specific prim (real semantic branches,
  not indirection).
- **Plugins:** a narrow dynamic `__methodOrExt__` path likely stays for genuine plugin dispatch — do
  NOT assume the whole `__method__` block deletes.
- **The apparatus trap:** the biggest project risk is a gate that passes while wrong. Freeze the golden
  set from the untyped oracle BEFORE F changes; every leg prints its diff. (`AGENTS.md`; the F7
  "equal empties" near-miss.)

## 6. Recommendation

Pursue typed-IR in the five staged slices, gated by the frozen golden-output set + typed fixpoint.
It is the highest-value, perf-positive lever; each stage is independently shippable and verifiable.
**Set expectations: it lands the kernel near ~4,700, and ~2,800 is F5b PLUS effects-to-tower +
decimal-to-tower + FastCode removal (three orthogonal efforts already scoped in SPRINT).**

Key refs: kernel δ `Runtime.scala:2561-3879`; F emit fusion `v2.2-p6.5-fsub.ssc:271-505`; Mira
`mira.ssc0:11-141` + `mira-emit.ssc0:50-212`; gate `specs/v2.2-p6.5-fsub.sh:14-80`.
