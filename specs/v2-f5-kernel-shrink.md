# F5 Kernel-Shrink — study + safe off-kernel relocations

**Status:** STUDY COMPLETE + measured. Direction ratified by Sergiy (2026-07-21):
*study + SAFE off-kernel relocations only* — NOT the deep δ-table retirement (that is F5b,
`specs/v2-f5b-typed-ir-design.md`). This document is the per-region map with an **honest,
fixpoint-verified** target and the measured re-study of the prior "irreducible" claim.

## 0. Bottom line (honest)

- The v2 kernel is **6,035 lines** (`v2/src`: Runtime 4,818 + CoreIR 415 + Ssc0 311 +
  PortableEffects 221 + PortableDecimal 171 + Main 97). Target ~2,800.
- **No mechanical shrink is SAFE right now.** Every shrink candidate falls into exactly one of:
  (a) needs F to emit **typed IR** (the δ-table, ~2,057 L — explicitly OUT of this task; it is F5b),
  (b) needs a **redesign to move to the ssc0 tower** (PortableEffects/PortableDecimal — they wrap
  host `java.math.BigDecimal` and the effect substrate; the tower cannot host them as-is), or
  (c) is a **perf layer** (FastCode/SelfRec, ~1,186 L) that is removable with **both gates staying
  byte-identical/green** but at a **measured 4.3× numeric-recursion regression** the gates cannot see.
- **The prior "irreducible" claim, re-studied with data, is REFINED, not overturned:** FastCode/
  SelfRec are *not* irreducible for **correctness** — the X1 self-compile fixpoint stays
  byte-identical (385,827 B, stage1==stage2) and the semantic gate stays 248/248 with them fully
  disabled, and the compiler workload is even marginally *faster* without them. They are irreducible
  for **perf**: numeric hot loops regress (fib(34): 0.215 s → 0.928 s = 4.3×). Deleting them now
  would be the *reverse* of the AGENTS.md apparatus trap — a green gate blessing a hidden regression.
  Correct call: **defer** until F5b typed IR "softens the perf cost" (direct typed calls replace tag
  dispatch), then re-measure and remove. This matches `specs/v2-f5b-typed-ir-design.md` §1.
- **Step B outcome:** the only thing landed is the **measurement instrument** (`SSC_FASTPATHS=off`,
  +7 net kernel lines) that made this study reproducible and turns the eventual perf-layer removal
  into a verified one-line flip + delete. The actual −1,186 L removal and the effects/decimal
  relocations are queued to `BACKLOG.md`, each gated on its enabler.

## 1. Gates used (verified fail-loud before relied upon)

Per AGENTS.md "measurement apparatus must COMPARE, never PRE-JUDGE":

- **X1 self-compile fixpoint (typed):** `specs/v2.2-p6.5-fsub.sh --self` — `stage1 = F(F_src)`,
  `stage2 = C1(F_src)`, ASSERT byte-identical. Baseline **385,827 B**, ~153 s.
- **C_min literal fixpoint:** `specs/v2.2-p6.6-fixpoint.sh` — smaller self-compiler, **32,824 B**, ~10 s.
- **Semantic gate (immovable ground truth):** `specs/v2.2-p6.5-semantic.sh check` — run-ir(F(P))
  output == frozen oracle golden for **248** goldens. Baseline **248/248**, ~37 s.
- **Fail-loud confirmed:** corrupting one golden `.out` → the semantic gate reports `MISMATCH 1` with
  a printed `expected=… got=…` diff and exits 1 (not silently green). ✓
- Build: `scala-cli --power package v2/src --assembly -o /tmp/ssc.jar --force`.

## 2. Per-region map (Runtime.scala + the 5 sibling files)

| Region (file:lines) | ≈L | Verdict | Why |
|---|---:|---|---|
| preamble: types/Step/exceptions/EffectId (Runtime 1-57) | 57 | **must-stay** | VM contract |
| `object Value` (58-145) | 88 | **must-stay** | value ADT |
| `object Runtime` core: run/trampoline/value/handlerClosure (146-458) | 313 | **must-stay** | the interpreter itself |
| `object SelfRecLL` (459-517) | 59 | **perf — DEFER** | arity-1 Long→Long fib specialization |
| `object SelfTailRecLL2` (518-600) | 83 | **perf — DEFER** | arity-2 tail-rec Long loop |
| `object Compiler` base compile (601-1637, minus perf helpers) | ~810 | **must-stay** | base IR→closure compiler |
| ↳ embedded closed-form loop helpers in Compiler (≈639, 751-975) | ~224 | **perf — DEFER** | `closedLongCellSumLoop*`, `staticFloatForeachLoop*`, `mayProduceAutoThreadOp` |
| `object FastCode` (1638-2457) | 820 | **perf — DEFER** | value-returning-closure JIT (no Done boxing) |
| `object V2PluginRegistry` (2458-2530) | 73 | **must-stay** | plugin SPI lookup |
| `object V2EffectContext` (2531-2552) | 22 | move-w/-effects | legacy JVM BlockForm plugin adapter |
| `object Prims` δ-table (2553-4609) | 2057 | **OUT (F5b)** | `__arith__`/`__method__`/`__eq__`/`__unary__` runtime dispatch; retire only when F emits typed IR |
| `object IrEncode/IrDecode/IrToData` (4610-4773) | 164 | **must-stay** | Core IR codec (⚠ shared contract; `coreir-codec-h4-h5` sibling active) |
| `object Show` (4774-4818) | 45 | **must-stay** | ⚠ `floatStr` shared v1-parity seam — never edit (memory: floatStr split) |
| `PortableEffects.scala` | 221 | move-to-tower = **redesign** | effect substrate; ~6 kernel dispatch sites; K3 redesign |
| `PortableDecimal.scala` | 171 | move-to-tower = **redesign** | wraps `java.math.BigDecimal`; ~24 kernel `__method__`/δ sites; ssc0 has no BigDecimal |
| `CoreIR.scala` / `Ssc0.scala` / `Main.scala` | 823 | **must-stay** | IR defs / ssc0 loader / CLI entry |

Perf-layer total (DEFER): SelfRecLL 59 + SelfTailRecLL2 83 + Compiler closed-form helpers ~224 +
FastCode 820 = **≈1,186 L**. (Task's "962" counts the three named objects only; the in-Compiler
closed-form loop JIT is additional perf code guarded by the same instrument.)

## 3. Measured re-study of the perf-layer "irreducible" claim

Instrument: `val fastPathsOn = sys.env.getOrElse("SSC_FASTPATHS","on") != "off"` guards all 9
fast-path entry points (SelfRecLL/SelfTailRecLL2 `compile`; `tryClosedLongCellSumLoop{,FC}`;
`tryStaticFloatForeachLoop{,FC}`; FastCode `tryFC`/`tryFBc`/`tryFCLongSet`). Default `on` is proven
byte-identical to the un-instrumented kernel. `off` forces the base Compiler path everywhere.

| Measurement | fast paths ON | fast paths OFF | verdict |
|---|---|---|---|
| C_min fixpoint (p6.6) | 32,824 B, 10 s | 32,824 B **byte-identical**, 10 s | no change |
| X1 typed fixpoint (`--self`) | 385,827 B, 153 s | 385,827 B **byte-identical**, 148 s | **green, marginally faster** |
| Semantic gate | 248/248, 37 s | 248/248 **green**, 29 s | **green** |
| fib(34) numeric recursion | 0.215 s | 0.928 s | **4.3× slower** |

**Reading:** the numeric fast paths do nothing for the compiler's own workload (string scanning +
pattern matching), so both self-hosting gates are indifferent to them — the probing cost even nets
out slightly negative. Their entire value is numeric hot loops, which **no gate here measures**.
Removing them is therefore *correct-but-slow*: a size win that silently trades away the project's
JIT perf wins (vm-jit 198×fib, while-hoist, hof-frame-reuse, …). Not a "safe win" today.

## 4. Honest target decomposition (fixpoint-verified basis)

Confirms and sharpens `specs/v2-f5b-typed-ir-design.md` §1:

| Effort | Δlines | Enabler / blocker |
|---|---:|---|
| **δ-table retirement** (Prims fan-out) | −1,100…−1,500 | **needs F5b typed IR** (OUT of this task) |
| FastCode/SelfRec removal | −1,186 (measured; task said −800/−962) | **perf-gated**: green today but 4.3× numeric regression → defer until typed IR softens it |
| PortableEffects → tower | ≈ −220 + δ sites | K3 effects redesign (ssc0 effect runtime) |
| PortableDecimal → tower | ≈ −170 + δ sites | ssc0 exact-decimal impl (no host BigDecimal) |
| fast-path dedup / interop trim | −150…−250 | partly enabled by typed IR |

**Honest achievable target NOW (safe, mechanical, no perf/correctness cost): ~0 lines.** No dead
code exists in the perf layer (every helper is live and interconnected). The 6,035 → ~2,800 path is
**four orthogonal deep efforts**, none of which is a mechanical relocation, and the largest (δ-table)
is explicitly OUT. The single most impactful next step is F5b typed IR (→ ~4,700), after which
FastCode/SelfRec removal becomes perf-neutral and this instrument makes it a verified one-liner.

## 5. Step B — what landed vs what is queued

**Landed (this task):** the `SSC_FASTPATHS` instrument only (+7 net kernel lines). It is the
reproducible measurement apparatus for §3 and the safe removal mechanism for the future: once F5b
lands, re-run the §3 table; when fib parity is within tolerance, delete the guarded regions and flip
nothing else. Gates verified green in **both** modes.

**Queued to BACKLOG (the deep, OUT-of-scope remainder), each with its enabler:**
1. FastCode/SelfRec removal (−1,186 L) — after F5b typed IR; re-measure §3, require fib within tol.
2. PortableEffects → ssc0 tower (K3 effects redesign).
3. PortableDecimal → ssc0 tower (ssc0 exact decimal).
4. δ-table retirement — the F5b project itself (`specs/v2-f5b-typed-ir-design.md`).

## 6. Constraints honored

Did NOT touch `specs/v2.2-p6.5-fsub.ssc` (F front, sibling-owned), `v2/lib` oracle,
`RunNativeV2.frontIsF`, or `CoreIR.scala` (sibling `coreir-codec-h4-h5` active). Kernel edits are
confined to the additive `SSC_FASTPATHS` guard in `Runtime.scala`.
