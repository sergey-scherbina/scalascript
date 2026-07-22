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
| FastCode/SelfRec removal | ≈ −800 | ~~typed IR softens the perf cost~~ **REFUTED 2026-07-22 (§4.1): typed IR ≠ perf-neutral; needs a bytecode/native compile of numeric recursion** |
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

## 4.1 Reconciled state + Stage 1b concrete sub-plan (2026-07-22)

**What is DONE and landed (verified against the gates in `specs/v2-f5-kernel-shrink.md §1`):**
- **Stage 0** — F is `parse→AST→erase`, byte-identical (landed `6290a4e81`).
- **Stage 1 approach A** — typed arithmetic/comparison by *IR-prefix type recovery* (landed `7c74c1280`).
  `emitBin`/`emitPlus`/`emitArithT`/`emitEqT`/`emitPP` route `+ - * / % < … == ++` to
  `i.*`/`big.*`/`sconcat`/`seq` when the **erased operand IR prefix** proves the type
  (`isIntCode`/`isStrCode`/`isBigCode`/`isFloatCode`, all *certain-only* prefixes; unknown → `"?"` →
  keep `__arith__`/`__eq__`). Types literal/structural arith (`1+2*3`, `"a"+"b"`, `charAt(i)>=97`,
  `s.length+1` where `.length` is `slen`). Verified: semantic **248/248**, typed fixpoint
  byte-identical, corpus byte-identity to the untyped oracle 417→225 (−192 arith programs diverge **by
  design**, 0 spurious).

**Measured deletion reality (`SPRINT.md S1-6`): δ-arm deletion is Δ=0 today.** Approach A cannot type a
**bare variable** operand — a param `a:Int` erases to `(local N)`, which has no type prefix — so
`def add(a,b)=a+b`, `n<2`, `n-1` (fib-shaped numeric recursion) all still emit `__arith__`. The ssc0
tower (`ssc1-lower.ssc0`) *also* still emits `__arith__`×12 / `__eq__`×10, and conformance `.ssc` are
lowered by that tower. So every numeric/string/cmp δ arm stays LIVE. **This bare-variable gap is also
exactly the perf gap** the mission cares about: `FastCode`/`SelfRec` removal is perf-neutral only once
fib-shaped numeric recursion emits direct `i.*` instead of `__arith__` tag-dispatch.

**`__unary__` is NOT a safe deletion (contract-drift trap, 2026-07-22).** S1-6 flagged `__unary__` as
the one δ arm with 0 emitters across F + tower + conformance. But it is still handled by the **JS, Rust,
and Swift backends** (`v2/backend/{js,rust,swift}`), so it remains a live prim in the *multi-backend
Core IR contract*. Deleting only the interpreter arm (`Runtime.scala:3022-3029`) would make a hand-
written or future-front `(prim __unary__ …)` run on three backends but crash the interpreter —
asymmetric contract drift a "0 corpus emitters" reading hides. **Leave it** until `__unary__` is retired
across all backends together (a separate, coordinated change), or keep it as the documented dynamic
fallback.

### Stage 1b — bare-variable / param typing (approach B), scoped as small self-compiling slices

The genuine lever. Chosen mechanism (rejected alternatives noted): give **`env` per-slot type info** so
a bare `(local N)` recovers its declared type, then route `emitBin` by that type. `env` is a de-Bruijn
**name list** threaded through ~40 parser fns / 280 call-sites; a bare `(local N)` operand string
already encodes `N`, and `climbStep` already holds `env`, so **no new thread and no node-type rework**
is required — `climbStep` maps `(local N)` → `env[N]`'s type by index.

- **Encoding decision — embed the type in the env *name string* (`"name:Type"`), NOT env-as-pairs.**
  Rejected `env: List[(name,type)]` (would touch all 27 `:: env` construction sites + risk an `fst` crash
  if any site is missed) and a parallel `tenv` thread (the 280-site problem) and a per-def side-table
  (breaks under nested binder shadowing). Name-embedding touches only **`lookupAt`** (compare
  `envNameOf(h)==nm`) and **`parseParam`** (embed the declared type) — 2 edit sites, de-Bruijn-correct,
  and transparent to the mangled `@`/`@@`/`__m`/`__u` entries (no colon → whole string is the name).
- **Fixpoint-safe by construction:** F's own 1129 defs annotate **zero** param types (the 4 colon-in-
  paren hits are all comments), so `parseParam` embeds nothing when F compiles itself → self-output
  byte-identical → `stage1==stage2` holds trivially. Typing fires only for `.ssc` programs that annotate
  params; their *output* is verified unchanged by the semantic gate (`i.*` vs `__arith__` agree).
- **Slice 1b-1 (this increment) — INT/String/BigInt params → typed leaf arithmetic.** Two commits:
  1. *refactor, byte-identical:* add tag helpers (`prefixTag`/`operandTag`/`localTyOf` + `emitBinT`/
     `emitPlusT`/`emitArithTT`/`emitEqTt`/`emitPPt` mirroring the existing routing but keyed on a tag
     `∈{I,F,S,B,?}`); `climbStep` calls the tag path. Untyped ⇒ tag = `prefixTag` ⇒ **byte-identical**
     (corpus stays 225, semantic 248, typed fixpoint byte-identical). Existing string-classifier
     `emit*` are left intact for their other callers (`assignRhs`, `interpChain2`, int-match `emitEq`).
  2. *feat:* `parseParam` embeds a bare `Int`/`String`/`BigInt` annotation as `name:Type`; typed locals
     now route to `i.*`/`big.*`/`seq`. Gate: semantic **248/248** (output unchanged), typed fixpoint
     byte-identical, corpus byte-identity drops below 225 (newly-typed programs diverge by design).
- **Slice 1b-2 DONE (`317e0b495`, 2026-07-22) — def RETURN-type registry.** A top-level `def f(…): T = …`
  with a simple `T∈{Int,String,BigInt}` registers `(f,T)` in a new `retTab` (deepest cx slot, alongside
  `objVarargs`); `operandTag` types a `(app (global f) …)` operand by `f`'s return type (`callRet`, name
  extracted from the erased string via `startsW "(app (global "` + `takeNm`). **Closes `fib`:**
  `def fib(n: Int): Int = if n<2 then n else fib(n-1)+fib(n-2)` →
  `(if (i.lt n 2) n (i.add (app (global fib) (i.sub n 1)) (app (global fib) (i.sub n 2))))` — the top `+`
  is now `i.add`. Fixpoint-safe (F annotates no own return types either). Gates: semantic 248/248, X1
  fixpoint stage1==stage2 byte-identical (398,412 B), corpus MATCH 207→204 (typed-by-design), EMPTY 0.
  (The typed-`val`/`var` half is deferred to 1b-2b — `fib` needed only the return-type registry.)

### ★★★ MEASURED PERF FINDING (2026-07-22) — the typed-IR-makes-removal-perf-neutral hypothesis is REFUTED

The whole "typed IR softens the FastCode/SelfRec perf cost" premise (§0, §1, `v2-f5-kernel-shrink §0`) is
**wrong, measured on the closed `fib`.** With `fib` now emitting fully-typed IR (`i.add`/`i.lt`/`i.sub`),
`fib(34)` on the v2 kernel (`run-ir`, min of 3, minus a 0.18 s JVM+compile baseline → compute-only):

| fib(34) compute | fastpaths ON | fastpaths OFF |
|---|---|---|
| **TYPED** (`i.add`) | ~0.02 s | **~0.80 s** |
| UNTYPED (`__arith__`) | ~0.03 s | ~0.81 s |

**Typed vs untyped is a ~1% difference; fastpaths ON vs OFF is ~30× (compute) / ~5× (wall).** So the
FastCode/SelfRec win is the recursion/loop **specialization** (SelfRecLL arity-1 Long→Long tight loop;
FastCode no-`Done`-boxing), which is **orthogonal to arith-prim dispatch** — the `__arith__` tag-dispatch
was never the bottleneck for numeric recursion (frame alloc + boxing + closure dispatch is). Typed IR
does **not** make the fast paths removable. **Decision: DO NOT remove FastCode/SelfRec** — it would be a
naked ~5× regression even with full typed IR. The removal is not "deferred until typed IR"; it is
**blocked on a different lever**: a typed-IR-driven **bytecode/native compilation of the numeric-recursion
class** (replace the tree-walker for `Int`-typed recursive defs, not just the arith prim). That is a
separate, larger backend effort. Typed IR's real payoff stands (kernel-size δ-table retirement +
directness/correctness), but **not** as the enabler of the FastCode removal.
- **Slice 1b-2b (optional) — typed `val`/`var` locals.** Embed `val x: T`/`var` declared types (or infer
  from the RHS tag) at the block-binder push sites. Coverage only (fib already closed without it); no perf
  or deletion unlock beyond 1b-1/1b-2.
- **Slice 1b-3 — typed `.length`/`.charAt`/`.substring` on a String-typed local** (`postDot`/`emitLen`
  become env-type-aware: a String-typed `(local N)` receiver lowers `.length`→`slen`, `.charAt`→
  `scodeAt`). Subsumes part of Stage 2.
- **Deletion still lags a full tower pass:** even after F types all bare-var arith, the δ arms stay LIVE
  until the **ssc0 tower** also emits typed IR (it lowers the conformance corpus). δ-arm deletion is a
  later phase gated on the full `v2/conformance/check.sh` (shared-seam; the `floatStr` rule).

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
