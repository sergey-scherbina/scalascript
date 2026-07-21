# W5 — Does a ` ```scala ` fence change `Int` width vs a ` ```scalascript ` fence?

Status: **MEASURED — no divergence today; a LATENT hole recorded. Decision owed to Sergiy.**
Measured 2026-07-21 by `w5-int-width-measure`. Reproduces (and strengthens) the
`int-width-conformance` W5 measurement in `BACKLOG.md` §"`scala` fences vs `scalascript` fences".

This is a **measure-and-report** artifact. No language/compiler/runtime behaviour was changed.

---

## 0. Verdict (one line)

A ` ```scala ` fence is **LIVE (executed on every lane) but its `Int` width follows the BACKEND,
not the fence tag** — its output is **byte-identical** to a ` ```scalascript ` fence on all five lanes
measured. The routing that *would* make a `scala` fence 32-bit (real `scalac`/Scala.js) is
**unreachable dead code today**. But `README.md` + `SPEC.md` explicitly promise that dead routing, and
a direct probe confirms it would yield 32-bit. So the hole is **latent, one case-reorder away**, and the
docs describe the dangerous version. A language-identity **decision is owed** (options in §5).

---

## 1. Live-or-dead verdict

"Live or dead" has two distinct answers, and conflating them is the trap:

| Aspect | Verdict | Evidence |
|---|---|---|
| Is a `scala` fence **executed** at all? | **LIVE** — not a no-op | `mixed.ssc` (one `scalascript` + one `scala` block) prints **both** lines on every lane; a `scala`-only file produces output. |
| Does the `scala` fence route through **real `scalac`/Scala.js** (where `Int`=32)? | **DEAD** — unreachable | `emit-js` output for a `scala` fence is **byte-identical** to a `scalascript` fence and contains **0** Scala.js markers (measured). |

**Why (code path).** `Lang.isParseable = isScalaScript || isStandardScala`
(`v1/lang/core/.../ast/Lang.scala:99-101`) — so a `scala` block is parsed by scalameta and executed by
the **ScalaScript** toolchain, exactly like a `scalascript` block. In
`JsGen.genModuleSegmented`→`walkSection` (`v1/runtime/backend/js/.../codegen/JsGen.scala:2063` vs
`:2068`) the `Lang.isParseable(cb.lang)` case is matched **before** the `Lang.isStandardScala(lang)`
case. Since `isParseable` is a strict superset, the `isStandardScala` arm — the one that builds
`JsGen.Segment.ScalaSource` and hands it to `ScalaJsBackend.compileSourceToJs` (which shells out to
`scala-cli --power package --js` ⇒ real `scalac` ⇒ `Int`=32,
`v1/runtime/backend/scalajs/.../ScalaJsBackend.scala:53-77`) — **can never be reached**. Same ordering
in the non-segmented `genSection` (`:2142-2147`), where the `isStandardScala` arm only emits a comment.
`Lang.scala`'s own doc comment (`:10-14`) is the honest one: *"The JavaScript backend **will
eventually** compile them via Scala.js; for now they are skipped… The interpreter runs them using the
same Scala 3 subset it already supports."*

**Side finding (doc-vs-code).** `SPEC.md`/`README.md` say `scala` fences are *illustrative* unless the
front-matter opts in with `runScalaFences: true`. **No Scala source reads `runScalaFences`** — only the
shell script `scripts/bc-parity-sweep:127` inspects it. So `scala` fences execute **by default**,
regardless of the flag (confirmed: `mixed.ssc` ran both blocks with no front-matter). Tangential to the
width question, but the same doc-vs-reality gap.

---

## 2. Measured width divergence (there is none — byte outputs)

**Reproduction.** Identical text in each fence, all values **computed from literals ≤ 2^31−1** to dodge
the known v1 interp literal fail-open bug (`BUGS.md §v1-interp-int-literal-above-2^31-becomes-null`):

```
println(2147483647 + 1)     # 64-bit ⇒ 2147483648 ; 32-bit ⇒ -2147483648
val c: Int = 2147483647
println(c * 2 + 1)          # 64-bit ⇒ 4294967295 ; 32-bit(JVM) ⇒ -1 ; 32-bit(JS double) ⇒ 4294967295
```

`w5/scalascript.ssc` wraps it in a ` ```scalascript ` fence; `w5/scala.ssc` in a ` ```scala ` fence
(same body). Commands and outputs (assembled harness, not `sbt runMain`):

| Lane | Command | `scalascript` fence | `scala` fence | Equal? |
|---|---|---|---|---|
| v2 native | `bin/ssc run` | `2147483648` / `4294967295` | `2147483648` / `4294967295` | ✅ identical — 64-bit |
| v1 interp (INT reference) | `bin/ssc-tools run --v1` | `2147483648` / `4294967295` | `2147483648` / `4294967295` | ✅ identical — 64-bit |
| v2 JS | `bin/ssc-tools run-js --v2` | `2147483648` / `4294967295` | `2147483648` / `4294967295` | ✅ identical — 64-bit |
| v1 JS codegen `[NON-CONFORMING]` | `bin/ssc-tools emit-js \| node` | `-2147483648` / `4294967295` | `-2147483648` / `4294967295` | ✅ identical — 32-bit fold + double carrier |
| v1 JVM codegen `[NON-CONFORMING]` | `bin/ssc-tools run-jvm` | `-2147483648` / `-1` | `-2147483648` / `-1` | ✅ identical — 32-bit |

**Emitted-JS byte comparison:** `emit-js scalascript.ssc` vs `emit-js scala.ssc` → `diff` empty
(byte-identical), 0 Scala.js markers in either.

**Conclusion.** On every lane, the `scala` fence output equals the `scalascript` fence output.
**The width follows the BACKEND, not the fence tag.** No divergence exists today.

---

## 3. The latent hazard, measured directly (not reasoned)

The dead branch can't be reached through the harness, so its `Int` width is measured by feeding the
**same body to real `scalac`** via `scala-cli run` (this is exactly what `ScalaJsBackend`/`JvmGen`
would invoke if the branch were revived):

```
$ scala-cli run RealScala.scala      # println(2147483647 + 1); val c: Int = 2147483647; println(c*2+1)
-2147483648
-1
```

⇒ real `scalac` gives **32-bit**. So **if** anyone "fixes" the unreachable `isStandardScala` branch so
`scala` fences really compile via Scala.js (README literally promises this), the *same fence text*
becomes `Int`=32 on the JS lane while a `scalascript` fence stays `Int`=64 — **one word meaning two
widths, silently, exit 0**. This directly violates AGENTS.md design principle #1 (*"backends translate;
they do not reinterpret"*), now *within a single file*. Note `AbiPrimitive.I32` is documented as
*"currently unreachable from ScalaScript source — ssc `Int` is 64-bit"*
(`v2/interop/descriptor/.../Model.scala:21-29`); the revived scalac path would bypass that ABI entirely.

---

## 4. Blast radius

**Dead-code that implements the dangerous path (do not revive without deciding §5 first):**
- `JsGen.Segment.ScalaSource` — `v1/runtime/backend/js/.../JsGen.scala:27` (enum), `:2049-2052`
  (`flushScala` constructs it), guarded unreachable at `:2068`.
- Consumers of `Segment.ScalaSource` → `ScalaJsBackend.compileSourceToJs`:
  `v1/tools/cli/.../EmitCommands.scala:62,359,489-490` (`emit-spa`/`emit-wc`),
  `v1/tools/cli/.../Main.scala:324`, `v1/runtime/backend/js/.../JsBackend.scala:43`.
- `ScalaJsBackend.compileSourceToJs` — `v1/runtime/backend/scalajs/.../ScalaJsBackend.scala:53-77`
  (real `scala-cli --power package --js`). Also reached by `emit-wasm` per its help text.

**Docs that already promise the dangerous (Scala.js, 32-bit) path:**
- `README.md:18` (block-tag table: `scala → interpreter · **Scala.js** (JS) · JVM · Rust`),
  `README.md:269-271` ("the JS backend compiles them via Scala.js"),
  `README.md:856-864` ("`scala` blocks — compiled by Scala.js via `scala-cli --js`… the
  Scala.js-compiled section runs first").
- `SPEC.md:149` (§3.3: "JS backend compiles via Scala.js"), `SPEC.md:156-162` (`runScalaFences`
  opt-in), `SPEC.md:1833` (`scalajs-spa` target), `SPEC.md:1885-1892` (backend-treatment table).
- `specs/backend-specific-blocks.md` §2.1 (` ```scala ` → "Scala 3 source, compiled via scala-cli").

**Does any current example/test rely on the 32-bit `scala`-fence path?** **No.** Every `scala` fence
today runs through the ScalaScript engine (64-bit); emitted JS carries 0 Scala.js markers. The test
inputs `tests/conformance/standard-scala-mixed-runnable.ssc` and `examples/lang-split.ssc` set
`runScalaFences`, but that key is a **no-op** in compiler/interpreter code (§1 side finding).
`deep-tail-recursion` and the other 32-bit witnesses are `scalascript` fences (W3/W4 territory), not
`scala` fences. So nothing depends on the dangerous path — it is safe to delete *or* to guard.

---

## 5. DECISION NEEDED (Sergiy)

The question is a **language-identity call**, not a bug fix: *do ` ```scala ` fences promise real
Scala 3 (host semantics, `Int`=32), or are they a ScalaScript-subset alias (`Int`=64)?* Today the
**code implements the second and the docs promise the first.** Three concrete resolutions:

**Option A — `scala` fences are a ScalaScript-subset (keep `Int`=64), delete the dead path.**
Rename the docs' "Standard Scala 3 / compiled via Scala.js" claim to "Scala-3-subset, ScalaScript
semantics (64-bit `Int`)". Delete `Segment.ScalaSource` + the `isStandardScala` arms + the
`ScalaJsBackend` Scala.js route (they are unreachable dead code with no dependents, §4).
- *Pro:* matches what the code actually does; zero divergence anywhere; one width everywhere; removes
  the trap entirely. Cheap (docs + dead-code delete).
- *Con:* gives up the README's "full Scala 3 fidelity" selling point; `scala` becomes a syntax alias
  for `scalascript`.

**Option B — `scala` fences are genuinely Scala 3 (`Int`=32 is a *declared* boundary).**
Revive/finish the Scala.js path; carve the boundary into `specs/numeric-widths.md` §2 (`scala` fence =
host Scala semantics, `Int`=32); add a **compiler diagnostic** when a `scala` fence and a `scalascript`
fence in the same file exchange integer values.
- *Pro:* honours today's docs; real Scala 3 fidelity for the escape-hatch block.
- *Con:* reintroduces "one word, two widths" *within one file* — the exact design-principle-#1
  violation this whole `int-width` effort exists to kill. To be self-consistent it would also force the
  interpreter and JVM lanes to run `scala` fences at 32-bit, contradicting the interpreter being the
  64-bit conformance reference. Effectively un-shippable without breaking the conformance model.

**Option C — document current behaviour as intended; keep the dead code but guard it (minimal).**
Keep `Int`=64 for `scala` fences (as-is). Fix docs to describe reality (`scala` fences run through the
ScalaScript engine at 64-bit, **not** Scala.js today; mark Scala.js as "future, will be a separately
widthed target"). Add a **guard test** asserting `scala`-fence output == `scalascript`-fence output on
every lane, so any future revival of the dead branch fails loudly instead of diverging silently.
- *Pro:* cheapest; keeps the door open to a real, explicitly-widthed Scala.js target later; converts the
  latent trap into a loud one at the moment someone reorders the cases.
- *Con:* leaves dead code in the tree (now fenced by a test rather than deleted).

**Framing for the decision:** A and C both keep the 64-bit contract and differ only in whether the dead
branch is **deleted** (A) or **kept-but-guarded** (C). B is the only option that honours today's docs,
but it is the one that breaks the core conformance invariant. Recommended reading: pick A or C unless
`scala` fences are meant to be a true Scala-3 escape hatch — in which case B, and accept the declared
32-bit boundary + diagnostic. **No code was changed pending this decision.**

---

## Appendix — exact reproduction

```bash
# probe bodies (identical text; only the fence tag differs)
#   println(2147483647 + 1)
#   val c: Int = 2147483647
#   println(c * 2 + 1)
# scalascript.ssc = ```scalascript … ```   scala.ssc = ```scala … ```

bin/ssc run scalascript.ssc            # 2147483648 / 4294967295   (64-bit)
bin/ssc run scala.ssc                  # 2147483648 / 4294967295   (identical)
bin/ssc-tools run --v1 scala.ssc       # 2147483648 / 4294967295   (64-bit)
bin/ssc-tools run-js --v2 scala.ssc    # 2147483648 / 4294967295   (64-bit)
bin/ssc-tools emit-js scala.ssc | node # -2147483648 / 4294967295  (32-bit fold + double)
bin/ssc-tools run-jvm scala.ssc        # -2147483648 / -1          (32-bit)
diff <(bin/ssc-tools emit-js scalascript.ssc) <(bin/ssc-tools emit-js scala.ssc)  # empty (byte-identical)

# latent hazard, direct: same body through real scalac
scala-cli run RealScala.scala          # -2147483648 / -1          (32-bit — what the dead path would give)
```
```

Cross-refs: `BACKLOG.md` §"`scala` fences vs `scalascript` fences" (the open decision item),
`specs/numeric-widths.md` (the normative width law), AGENTS.md design principle #1.
