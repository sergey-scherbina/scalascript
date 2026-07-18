# v2 front convergence — decision brief (R3)

> **Analysis only.** Nothing is converged, refactored, or changed here — this is the fact
> base for Sergiy's architectural call: which of v2's two self-hosting-front efforts becomes
> **THE** canonical v2 front, and how to get from two-fronts-today to one clean front.
> Every number below was **reproduced from a clean build**, not read off a checkbox.
> Author: `v2-front-convergence` agent, 2026-07-18. Reproduction commands + file:line index at the end.

## 0. TL;DR

- **The oracle is NOT a v1 artifact.** Both fronts anchor on `ssc1-front.ssc0` + `ssc1-lower.ssc0`,
  which live in **`v2/lib/`** (not `v1/`), are written in **ssc0** (v2's own bootstrap language), and
  ARE v2's current production native front. `v1/` contains no copy. So the task-prompt framing "both
  lean on `ssc1-front` in v1's tree" is **factually wrong** — the convergence is **entirely inside v2**,
  and v2 is *already* independent of the `v1/` directory for its front. (§2 — the crux, with proof.)
- **The two efforts are not the same design twice — they are complementary halves of the same endgame:**
  - **newfront** = **breadth, in Scala.** A 2447-line **Scala** parser (`ScalaSpike.scala`) that reproduces
    `ssc1-front`'s parse on **491/504 (97 %)** of the **real** corpus, then **reuses the existing
    `ssc1-lower`** to reach Core IR. It has **breadth on real programs** but **does not self-host** (it is
    Scala) and **does not own a lowerer**.
  - **P6.5 `F`** = **self-hosting, in the subset.** A **338-line ScalaScript-subset** compiler with its
    **own** lexer+parser+lowerer that emits Core IR byte-identical to `ssc1-front`+`ssc1-lower` and
    **compiles its own source** (X1 fixpoint, verified). It **self-hosts** and **owns its lowerer**, but
    only over the subset it is written in — **~0 % of the real corpus today** (measured: 0/5, §5).
- **Which is closer to the goal ("compiles ALL of ScalaScript, self-hosted")?** Neither is close to *both*
  axes. newfront has the breadth axis and none of the self-host axis; P6.5 has the self-host axis and
  ~none of the breadth axis. They are ahead on **different** axes (§4–§5).
- **Recommendation (with tradeoffs, §6):** make **P6.5's architecture the canonical target** (it is the
  only one of the two that can reach *fully self-hosted + small*, because it owns its lowerer and is
  written in the object language), and **fold newfront in as the breadth map + real-corpus acceptance
  gate + oracle-quirk spec** — retire newfront's Scala spike to a test-oracle role, do not ship it.
  The honest counter-tradeoff: this puts the **larger** body of work (reproduce all of `ssc1-lower`'s
  lowering in the subset, not just reuse it) on the critical path. The alternative (ship newfront,
  keep `ssc1-lower`) is faster to breadth but never retires the 5665-line ssc0 lowerer — i.e. it is
  *not* "small / fully self-hosted."

---

## 1. What each front physically is

| | **newfront** | **P6.5 `F`** |
|---|---|---|
| Seed / impl file | `uniml/core/src/test/scala/scalascript/uniml/spike/ScalaSpike.scala` | `specs/v2.2-p6.5-fsub.ssc` |
| **Language it is written in** | **Scala** (a UniML dialect, in a `test/` dir) | **ScalaScript** (the subset `S`) |
| Size | **2447 lines** of Scala | **338 lines / 214 defs** of subset |
| What it produces | a **Pair-AST projection** (`Pair("block",…)`, `mkVal`, …) | **Core IR text directly** (`(prim __arith__ …)`, `(def … (lam …))`) |
| Lowerer | **reuses `ssc1-lower.ssc0`** (variant A — same Pair-AST) | its **own** lowerer, emits Core IR itself |
| Replaces (of the ssc0 tier) | `ssc1-front` (parser, 3231 ln) **only** | `ssc1-front` **and** `ssc1-lower` (both) |
| Test corpus | the **real** `examples/`+`tests/conformance/` (504 extracted) | **85 hand-written micro-programs** + `F`'s own source |
| Self-hosts (compiles its own source)? | **No** (it is Scala) | **Yes** (X1 fixpoint, verified) |
| Harness | `specs/newfront-diff.sh` (single), `specs/newfront-diff-multi.sh` (multi) | `specs/v2.2-p6.5-fsub.sh [--self]` |
| Predecessor | — | P6.6 `C_min` (`specs/v2.2-p6.6-cmin.L`, 74 defs, 32,824 B fixpoint) |

Key structural fact, verified by reading the harness (`specs/newfront-diff.sh:7,66,75`): **both sides of
the newfront diff call the *same* `lowerProg` from `ssc1-lower.ssc0`.** newfront's projection is only a
**parser** substitute; the lowerer stays ssc0. P6.5's `F` instead emits Core IR end-to-end — but it
"cheats" the constant part: `ssc1-lower` emits a **fixed 7258-byte prelude** (~50 `_sel_*`/`__list_*`
defs), byte-identical across all programs, and `F` carries that blob as a **hardcoded string constant**
rather than reconstructing it (SPRINT §"X1 architecture", fact 2). So `F` has reimplemented `ssc1-lower`'s
*per-construct* lowering for its subset, **not** the whole 5665-line lowerer.

---

## 2. THE ORACLE QUESTION (the crux — load-bearing for "v2 independent of v1")

**Question:** both fronts use `ssc1-front` (+`ssc1-lower`) as the byte-identity oracle/seed. If v2 must be
independent of v1 and v1 must not be touched, how does the canonical v2 front stop depending on a v1
artifact? Is `ssc1-front` a v1 thing or already part of v2?

**Answer, measured:**

1. **`ssc1-front.ssc0` and `ssc1-lower.ssc0` live ONLY in `v2/lib/`** — `v2/lib/ssc1-front.ssc0` (3231 ln)
   and `v2/lib/ssc1-lower.ssc0` (5665 ln). `find v1 -name 'ssc1-*'` returns **nothing**. There is **no v1
   copy**.
2. **They were born in v2/lib.** `git log --follow v2/lib/ssc1-front.ssc0` bottoms out at
   `a7f34a9ef feat(ssc0): KC3 v1.0-compat parser in lib/ssc1-front.ssc0`. The "ssc1" in the name means
   **ScalaScript *language* v1.0 (K61)** — not the `v1/` project directory (file header:
   `ssc1-front.ssc0` = "lexer + functional-subset parser for ScalaScript v1.0 (K61). Written in ssc0.").
3. **They ARE v2's current production native front.** `bin/ssc` → `scalascript.cli.StandardMain` →
   `RunNativeV2` loads the staged tower `bin/lib/standard/native-front/tower/bin/ssc1-run.ssc0`, which is
   `installBin`-copied from **`v2/bin/ssc1-run.ssc0`** (`build.sbt:2000`) and imports
   **`v2/lib/ssc1-lower.ssc0`** → which imports `ssc1-front.ssc0`. It runs on the **v2 kernel**
   (`_root_.ssc.*` = `v2/src/*.scala`, `package ssc`).
4. **v1 has a *different*, unrelated front** — a scalameta plugin at `v1/runtime/std/frontend-plugin/`.
   The v2 native path is explicitly severed from it: `RunNativeV2.scala:3-4` — *"This class deliberately
   has no reference to the Scalameta frontend or v1 PluginBridge."*
5. **Caveat, so the claim stays honest:** the *CLI wrapper* classes (`StandardMain`, `RunNativeV2`) are
   physically packaged under **`v1/tools/cli/`**. That is a build-layout wrinkle of the single sbt
   monorepo, **not** a semantic v2→v1 dependency: they reference only the v2 kernel (`ssc.*`) and native
   plugins, never v1's front. (Where the CLI module *should* live is a packaging question for R1/R4, not
   a front-convergence blocker.)

**So the real dependency is not on v1 — it is on the ssc0 TIER inside v2:**
- the front is written in **ssc0** (a lower bootstrap language), and
- ssc0 itself is parsed by **Scala** (`v2/src/Ssc0.scala`, 311 ln).

**"Front-independence" for v2 therefore means:** replace the ssc0-written front with a **ScalaScript-written,
self-compiling** one, after which the ssc0 front can be **frozen as a golden oracle and eventually retired**
(and, once nothing else needs ssc0, so can `Ssc0.scala`). The oracle is only a **seed + acceptance
reference** — the target front does not *depend* on it at runtime; it must merely be *byte-identical* to it
until it earns the right to replace it. **This is already true in miniature: P6.5's `F` self-compiles today
without the oracle in its runtime path** (the oracle is used only to *check* `F`, and to *bootstrap* `F0`
once). The distance to "front-independent" is therefore **not** an architecture gap — it is the **breadth**
of getting a self-hosting subset front to cover the whole language. That breadth is the same work whichever
seed is chosen; §6 is about which seed makes it cheapest.

---

## 3. Exact scope each front handles TODAY (measured)

### newfront — single-file **491/504 (97 %)**, reproduced 2026-07-18

Ran `specs/newfront-diff.sh`'s projection (from the **root** build — see the harness bug in §7) + the
byte-compare step over the 504 extracted programs:

```
total compared : 504
MATCH : 491 (97%)   DROP : 0   HOLE : 2   DIFF : 11
```

Remaining **13** non-matches, each a documented single (no cluster ≥ 2):
- **2 HOLE:** `js-symbolic-infix-operator`, `wasm-scalascript`.
- **11 DIFF:** `bureau-demo`, `dsl-multi-pass`, `graph-rdf4j-http-storage`, `graphql-client`,
  `mcp-search-server`, `openapi-annotation`, `scljet-readonly-btree-pure`, `tagless-context-bounds`,
  `typed-sql-crud`, `wasm-http`, `wasm-primes`.

This matches the SPRINT-documented "485/499 → grown to 491/504 as the corpus grew to 515" and its tail list
exactly. So newfront's **parse coverage of the real language is essentially complete** — the residue is a
handful of oracle quirks (context bounds, symbolic-operator-def truncation, braceless multi-line `for`).

**Multi-file: 43/216 (20 %)** — documented, **not re-run here** (`specs/newfront-diff-multi.sh` ≈ 25 min).
This is the honest weak spot of newfront's breadth: single-file says 97 %, but 216/499 roots load ≥1 module,
and across-file byte-identity is only 20 %. Module loading was *entirely unmeasured* until the multi-file
gate existed (SPRINT Phase 2.0). The cause is characterized (variant-A registry ordering under the loader's
parse order, SPRINT 2.3) but not closed.

### P6.5 `F` — 89 ok / 0 FAIL, X1 fixpoint holds, reproduced 2026-07-18

```
SSC_JAR=…/ssc-r3.jar V2_DIR=…/v2 bash specs/v2.2-p6.5-fsub.sh --self
  → 89 ok / 0 FAIL, exit 0
  ok  F0 bootstrapped (79853 bytes)
  ok  F(F_src) == ssc1-front(F_src) byte-identical (79667 bytes)
  ok  C1 (self-produced compiler) byte-identical to reference AND its IR runs -> 120
  ok  *** X1 FIXPOINT: stage1 == stage2 (byte-identical, 79667 bytes) ***
```

`F` covers, **byte-identically to `ssc1-front`+`ssc1-lower`** (the 85 `d`-cases in the harness):
arithmetic/comparison/booleans, `def`+params+recursion, `if`, strings + `charAt`/`length`/`substring`/`==`/`++`,
`match` (Cons/Nil/tuple/int-lit/wildcard/cons-infix), `val`-blocks, **lambdas + HOFs**, and **case classes**
end-to-end. **Still out** (each documented as bounded-mechanical breadth, no design question):
**given/summon** dict-passing, **enums**, **extensions**, **for-comprehensions**, **`var`/`while`**, string
**interpolation**, the **prelude-selector table** (`._1`/`.trim`/`.mkString`), the **List-var registry**
(`.length`→`_sel_length`).

---

## 4. Overlap and difference

**They are genuinely different architectures, not the same design twice.**

| Axis | newfront | P6.5 `F` |
|---|---|---|
| Breadth **on real programs** | ✅ 97 % single-file (491/504) | ❌ ~0 % (0/5 measured, §5) |
| Breadth **on its own test corpus** | real 504-corpus | 85 hand-picked micros |
| Multi-file / imports | 20 % (measured) | not attempted |
| **Self-hosts** | ❌ (Scala) | ✅ (X1 fixpoint, verified) |
| **Owns a lowerer** (emits Core IR itself) | ❌ (reuses `ssc1-lower`) | ✅ (own lowerer + hardcoded prelude blob) |
| Retires which ssc0 files | `ssc1-front` only | `ssc1-front` **and** `ssc1-lower` |
| Written in target language | ❌ | ✅ |
| Lines | 2447 (Scala) | 338 (subset) |

**Where they overlap:** both reproduce the **same oracle's parse** (`ssc1-front`) to **the same Core IR**,
byte-for-byte, using the **variant-A additive-collector** discipline for parser-owned cells (case-method
registry, subtype registry, using-sig, func-defaults). The lexer/parser *logic* is close kin — P6.5's
`parseExpr` is "the same precedence-climb algorithm as the spike's" (SPRINT F2 note), and both mirror
`ssc1-front` function-for-function.

**Where each is strictly ahead:**
- newfront is ahead on **everything breadth**: real-corpus parse coverage, error-recovery fidelity,
  the full operator/layout/annotation/interpolation surface, and multi-file loading (even at 20 %).
  Its Scala source + the SPRINT Phase-1 trajectory are a **construct-by-construct spec of every
  `ssc1-front` quirk** (annotations-as-error-recovery, `inline` is a var, `:::`→`++`, symbolic-op-def
  truncation, colon-lambda `_err` recovery, …). That knowledge is the expensive part and it is captured.
- P6.5 is ahead on **everything self-hosting**: it is in the object language, it owns its lowerer (so it
  can retire `ssc1-lower`), and it **re-proves `stage1==stage2` on every breadth slice** — the hardest
  property is continuously guarded, not deferred.

**The deep point:** the endgame both are aiming at is **one artifact** — a ScalaScript-subset front that
(a) covers the whole corpus byte-identically **and** (b) self-compiles. That artifact is *exactly*
newfront's unstarted **Phase 4** ("rewrite the front cleanly in that subset") **==** P6.5's `X1i`-and-beyond.
**They converge on the same point from opposite corners.** newfront brings the breadth; P6.5 brings the
self-hosting spine and the subset `S` the front must be written in.

---

## 5. Which is closer to "compiles ALL of ScalaScript, self-hosted"

The goal has **two** axes. Measuring each against reality:

- **newfront on the self-host axis: 0 %.** It is Scala; it cannot compile its own source, and it does not
  emit Core IR (it leans on `ssc1-lower`). Self-hosting is its Phases 3–6 — **unstarted**.
- **P6.5 on the breadth axis: ~0 % of real programs.** I rebuilt `F0` and ran it against real extracted
  corpus programs:

  ```
  hello       : DIVERGE (ref 7506 B, F 7398 B)
  json-read   : DIVERGE (ref 9563 B, F 7309 B — most of the program lost)
  _bug1_httpclient, js-applyunary-effect-cps, effect-transitive-handler : DIVERGE
  → 0 of 5 real programs match  (F was built for its own 338-line source + 85 micros, per its HONEST BOUNDARY)
  ```

So **neither is close to the goal**, and they are close on **different** axes. The right question is not
"which is further along" (unanswerable — different units) but **"which foundation reaches BOTH axes with
less total work."** That turns on one fact:

**Breadth is the larger, more uncertain body of work, and newfront has already paid most of it** — but
**only for the parser, and only in Scala.** P6.5's remaining-breadth list (given/summon, enums, extensions,
for-comp, var/while, interpolation, prelude-selectors, List-registry) is the *same* set of constructs
newfront already conquered, and newfront's 33-slice trajectory shows each was a real fight with the oracle,
not a rubber stamp. **The catch:** P6.5 must reproduce not just `ssc1-front`'s parse (which newfront
documents) but also `ssc1-lower`'s *lowering* for each of those constructs **in the subset** — the part
newfront got for free by reusing `ssc1-lower`. That extra work (case-class Mirror/`_sel_`/`__regfields__`,
given/summon dict-passing, effect-handler dispatch, enum ctor paths, the List-var registry — i.e. most of
`ssc1-lower`'s 5665 lines) is the true cost of "fully self-hosted + small," and it lands on whichever seed
owns its lowerer. **Only P6.5's architecture pays it; newfront's variant-A defers it forever.**

---

## 6. Convergence cost + recommendation (options for Sergiy)

### The one decision under everything: **does the canonical front own its lowerer, or keep `ssc1-lower`?**

- **Keep `ssc1-lower` (newfront variant-A):** cheapest to breadth, but the 5665-line ssc0 lowerer stays
  alive forever. The front is then only *half* self-hosted (parser in ScalaScript, lowerer still ssc0),
  and "small" is not achieved (the biggest ssc0 file remains). Contradicts "ideal, small, **fully**
  self-hosted."
- **Own the lowerer (P6.5):** the only path to *fully self-hosted + small* — retires **both** ssc0 front
  files — but puts reproducing all of `ssc1-lower`'s lowering in the subset on the critical path.

Sergiy's stated vision ("ideal, small, powerful, **fully self-hosted**") selects **own-the-lowerer** →
**P6.5's architecture is the correct target.**

### Recommended option — **A: P6.5 is the seed, newfront is the map**

1. **Adopt P6.5's `F` as the canonical front skeleton.** It already self-hosts, owns its lowerer, is in
   the object language, and is small (338 ln). This is the artifact that becomes "the v2 front."
2. **Adopt newfront's `newfront-diff.sh` REAL-corpus harness as `F`'s acceptance gate.** P6.5's single
   biggest weakness is that it is tested on **85 hand-picked micros** — exactly the "point-example corpus
   hides gaps" failure mode this project keeps hitting. Wire `F` into the **504-program** byte-diff so its
   breadth is scored against reality. (Fix the harness bit-rot first — §7.)
3. **Use newfront's Scala spike + the SPRINT Phase-1 trajectory as the per-construct oracle spec.** Every
   quirk `F` must reproduce is already reverse-engineered and written down there. Grow `F` construct by
   construct against the real corpus, re-proving `--self` each slice (P6.5 already does this).
4. **Retire newfront's Scala spike to a test-oracle / documentation role** once `F` reaches parity — keep
   it as a differential cross-check and as the readable spec of `ssc1-front` behavior; **do not ship it**.
5. **Keep `ssc1-front`/`ssc1-lower` as the frozen byte-identity oracle** until `F` covers the whole corpus
   AND self-compiles; then flip `bin/ssc` to `F` (newfront Phase 6 cutover) and freeze/retire the ssc0
   front.

**Cost of A:** large but bounded and de-risked — it is "reproduce `ssc1-lower`'s lowering for the
remaining constructs, in the subset, gated by the real corpus." No unknowns (every primitive `F` needs is
proven runnable; every oracle quirk is documented). Multi-session, mechanical. The self-host property is
never at risk (guarded every slice).

**Tradeoff of A:** slowest to *first* full-corpus green, because it does the lowerer work newfront skipped.
If Sergiy values "cover the real language soon" over "fully self-hosted + small," B is faster.

### Alternative — **B: newfront is the seed, P6.5 proves the method**

Finish newfront breadth to ~100 % single + multi-file (Scala), ship it as a **parser** replacing
`ssc1-front` while **keeping `ssc1-lower`**; treat P6.5 as the *proof* that a subset front can self-compile,
and only later execute newfront Phases 3–5 (define subset `S` — P6.5 already did — port the 2447-line Scala
spike into `S`, then optionally re-own the lowerer).

**Cost of B:** fast to full breadth; but "self-hosted" requires a **full Scala→subset port** of 2447 lines
(Phase 4) that is unstarted, and "small / fully self-hosted" requires *also* re-owning the lowerer
(Phase 5) — i.e. it eventually converges to A's work anyway, after a detour through a shipped-but-
non-self-hosting intermediate. Risk: the intermediate becomes the permanent state and "fully self-hosted"
never lands.

### Non-option — keep both fronts

Two fronts is the current state and directly violates "ideal / one clean path." Both anchor on the same
oracle and aim at the same endgame artifact; maintaining both is pure duplication. The only question is
*which* becomes canonical and *what role* the other keeps (test-oracle vs shipped) — answered above.

### My recommendation

**Option A.** It is the only path that satisfies all four words ("ideal, small, powerful, fully
self-hosted") in one artifact, it keeps the hardest property (self-compilation) continuously proven, and it
wastes nothing from newfront — newfront's breadth work becomes `F`'s spec and gate rather than throwaway
code. Accept the tradeoff that first-full-corpus-green is later than B; the project's own recurring lesson
(status ≠ truth; point-corpora hide gaps) argues against shipping the non-self-hosting intermediate that B
risks making permanent. **If Sergiy prioritizes covering the real language quickly over smallness/full
self-hosting, choose B — but with an explicit commitment to the Phase-4 port, or it will stall as a
half-self-hosted front with a 5665-line ssc0 lowerer that never dies.**

---

## 7. A real finding: the newfront single-file harness is currently BROKEN (bit-rot)

Running `specs/newfront-diff.sh` as documented reported **MATCH 0 / 504** — and **exited 0** while doing so
(the exact silent-green failure mode AGENTS.md §"measurement apparatus" warns about). Root cause: the
harness runs the projection via `cd "$ROOT/uniml" && sbt "uniml/testOnly …ScalaSpikeSpec …"` (line 45–47),
but the `ci-last-red` fix (`d7256b534`) **moved `ScalaSpikeSpec` into `src/test-jvm/`**, which is wired only
in the **root** `build.sbt` (`unimlCross`, line 597) — **not** in the `uniml/build.sbt` core sub-build the
harness `cd`s into. So sbt reports *"No tests to run"*, zero `.proj` files are written, and the report prints
`MATCH 0 (0%)`. Running the projection from the **repo-root** build works (504 projected, 0 crash), which is
how I reproduced 491/504. **Fix (for whoever owns newfront):** point the harness's sbt step at the root
build, or wire `test-jvm` into `uniml/build.sbt`'s core project. Flagged here, not fixed (stay-in-lane +
`ScalaSpike*` is a live claim). This is exactly why a real-corpus gate for `F` (Option A step 2) must be
*seen to fail when broken* before it is trusted.

---

## 8. Reproduction — exact commands + file:line index

**Build the run-ir kernel jar (both harnesses need it):**
```
scala-cli --power package v2/src --assembly -o /tmp/ssc.jar   # thin bin/lib/ssc.jar has NO run-ir
```

**P6.5 self-host fixpoint (verified 89 ok / 0 FAIL, 79,667 B):**
```
SSC_JAR=/tmp/ssc.jar V2_DIR=<repo>/v2 bash specs/v2.2-p6.5-fsub.sh --self
```

**newfront single-file (verified 491/504) — run the sbt step from the REPO ROOT, not `uniml/`:**
```
# 1. extract + spike-project (root build; the in-harness `cd uniml` step is broken, §7):
NEWFRONT_CODE=<codedir> NEWFRONT_PROJ=<projdir> \
  sbt -batch 'uniml/testOnly scalascript.uniml.spike.ScalaSpikeSpec -- -z "newfront corpus batch"'
# 2. then the byte-compare step of specs/newfront-diff.sh (steps 3–4) over <projdir>.
```

**P6.5 `F` vs real corpus (verified 0/5 — the breadth gap):** rebuild `F0` via the driver in
`specs/v2.2-p6.5-fsub.sh:39-53`, then `java -jar ssc.jar run-ir F0.ir <real-program.ssc>` and `cmp` against
`java -jar ssc.jar run v2/bin/ssc1-run.ssc0 <real-program.ssc>`.

**File:line index (all in the repo):**
- Oracle: `v2/lib/ssc1-front.ssc0` (3231 ln), `v2/lib/ssc1-lower.ssc0` (5665 ln); provenance commit
  `a7f34a9ef`; header at `v2/lib/ssc1-front.ssc0:1-2`.
- Production load path: `bin/ssc:15` → `v1/tools/cli/.../StandardMain.scala` → `RunNativeV2.scala`
  (`:3-4` no-scalameta comment; `:381-395` `nativeFrontLayout` staged tower); staging `build.sbt:2000`;
  `v2/bin/ssc1-run.ssc0:6` imports `ssc1-lower`; v2 kernel `package ssc` = `v2/src/Runtime.scala:1`;
  ssc0 parser `v2/src/Ssc0.scala` (311 ln).
- newfront: `uniml/core/src/test/scala/scalascript/uniml/spike/ScalaSpike.scala` (2447 ln, Scala);
  batch test `uniml/core/src/test-jvm/scala/scalascript/uniml/spike/ScalaSpikeSpec.scala:545`;
  variant-A / reuse-`ssc1-lower` at `specs/newfront-diff.sh:7,66,75`; multi `specs/newfront-diff-multi.sh`.
- P6.5: `specs/v2.2-p6.5-fsub.ssc` (338 ln / 214 defs); harness `specs/v2.2-p6.5-fsub.sh` (85 `d`-cases +
  `--self`); prelude-blob-as-constant + architecture facts in SPRINT §"X1 architecture" and the "HONEST
  BOUNDARY" note; predecessor P6.6 `specs/v2.2-p6.6-cmin.L` + `specs/v2.2-p6.6-fixpoint.sh`.
- SPRINT sources: `SPRINT.md` §`v2-finish` (R3), §`new-self-hosting-front` (Phases 0–6), §`v2.2 STATUS` +
  the P6.5 X1 item.
