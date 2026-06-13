# Bug tracker

Durable ledger of bugs reported in the `scalascript` rozum room (or found locally).
See the `rozum` skill — "The bug-tracking loop". Newest first. Status flow:
`open → needs-info → fixed → (confirmed) → done`. Keep fixed/done entries with their
commit SHA until the reporter confirms, then they can be trimmed.

| Status legend | |
|---|---|
| `open` | reproduced / accepted, work to do |
| `needs-info` | blocked on a repro question asked in the room |
| `fixed` | landed on `origin/main`, reporter not yet re-confirmed |
| `done` | reporter confirmed fixed (safe to trim) |

---

## interp-cons-in-effect-handler — `fixed` (example) (2026-06-13, `721ee62b9`)

- **FINAL diagnosis (two earlier mis-diagnoses corrected):** NOT a `::` bug and NOT a
  "resume result not forced to ListV" bug. `resume(())` **correctly** returns the
  continuation's pure result `()` (Unit); `println(rest)` after `val rest = resume(())`
  prints `()`. The `algebraic-effects.ssc` Logger handler did `msg :: resume(())`, i.e.
  `msg :: ()` → "No method '::' on StringV" — it assumed `resume(())` of the final
  continuation would be `Nil`. That is the **deep-handler list-accumulation** pattern
  (Koka/Eff `return x => []`), which needs a handler **return clause**. ScalaScript's
  `handle` has **no return clause** (the spec's own Logger example just does `resume(())`,
  returning Unit), so the pattern is unsupported. **Example bug, not an interp bug.**
- **Fixed:** rewrote the Logger section to a working accumulator (append each msg + resume)
  producing the same `List(Hello, World!)`, with a comment on the return-clause gap.
  Also corrected the State section (stdlib `State` + `set`, dropped a broken parameterized
  redecl — see `interp-parameterized-effect-decl`).
- **Underlying language gap (future feature, not filed as a bug):** a handler **return
  clause** would make `msg :: resume(())` work (the spec types `resume` as returning the
  *handler body's* type, which requires bridging the pure/base case). Large feature
  (parser + typer + interp + 4 backends) — out of scope; noted in BACKLOG.

## interp-parameterized-effect-decl — `fixed` (2026-06-13, `2a818e45c`)

- **Fixed:** `Parser.effectLinePat` (the regex that rewrites `effect Name:` →
  `object Name { … }`) had no type-param clause after the name, so `effect State[S]:` /
  `effect Box[T]:` were left un-rewritten and reached the Scala parser as a bare
  `effect Name[T]` expression → `No method 'Name' on NativeFnV(<native:effect>)`. Added an
  optional `(?:\[[^\]]*\])?` after `(\w+)` (the `object` drops the type param; op
  signatures may still mention it — the interpreter erases types). Shared `lang/core`
  Parser, so all backends benefit. Regress: `StdEffectsTest` (`effect Box[T]:` decl + handle).

## interp-effect-multishot-in-subsection — `fixed` (2026-06-13, `2a818e45c`)

- **CORRECTION:** filed as `interp-effect-multishot-cross-section-leak` — that "global state
  leaks from an earlier one-shot `handle`" diagnosis was **wrong**. Real cause: `multiShotEffects`
  was **never populated for subsection code blocks at all**. `Interpreter.runInit` collected the
  effect-analysis trees only from top-level `module.sections` content, not the nested `##`/`###`
  subsections where the blocks actually live (`[DBG] sections=1 allTrees=0 multiShotEffects=Set()`).
  So a `multi effect` declared in a subsection was never registered → its handler defaulted to
  one-shot → `One-shot violation` on the 2nd `resume`. A `multi effect` directly under the top-level
  `#` worked, which made it look order/leak-dependent.
- **Fixed:** `runInit`'s tree collection now recurses `s.subsections`. Regress: `StdEffectsTest`
  (`multi effect` in a `##` subsection multi-shots); `examples/algebraic-effects.ssc` runs
  end-to-end and is in `ExamplesSmokeTest`. Interp-only — JVM/JS codegen already gather all
  blocks recursively.

## interp-toString-on-collection — `fixed` (2026-06-13, `225aacc18`)

- **Fixed:** intercept `toString` (0-arg) at the top of `DispatchRuntime.dispatch`
  (alongside the `asInstanceOf` early-return) → render via `Value.show`, the canonical
  println / string-interpolation path, so `x.toString == s"$x"` for every value. A
  case-class instance with a user-defined `toString` method keeps it (checked via
  `lookupTypeMethod` first). Needed to intercept at the TOP because type-specific
  dispatchers mis-handle the name first (`map.toString` → key lookup → "No key
  'toString'"). Interp-only fix (JVM/JS codegen emit native `toString`). Regress:
  `BugReproTest` (list render + composite canonical-render invariant across
  List/Map/tuple/Option/case-class); 65 `.toString`-dependent tests across 7 suites
  green; `examples/async-parallel-demo.ssc` now runs end-to-end.
- **Found:** by me, expanding `ExamplesSmokeTest` (`examples/async-parallel-demo.ssc`
  fails). Reproduces on `origin/main` (`e73fd9a73`) via the interpreter.
- **Symptom:** `.toString` is universal in Scala (every value has it) but the
  interpreter has no `.toString` dispatch for a `ListV` (and likely other collection /
  composite Values) → `No method 'toString' on ListV(List(50, 50, 50))`.
- **Repro:**
  ```scalascript
  val xs = List(50, 50, 50)
  println("result=" + xs.toString)   // No method 'toString' on ListV
  ```
- **Note:** broadly useful, likely small — add a universal `.toString` fallback in the
  interpreter's method dispatch (render via the same path as `println`/string-concat).
  Check Map/Set/tuple/Option/Either too. Cross-backend regression.

## interp-typed-data-not-callable (a.k.a. bare-fn-ref auto-invoke) — `fixed` (2026-06-13, `175c01d72`)

- **Root cause (narrowed):** NOT a rare typed-data construct — it was the common
  `xs.foreach(println)` idiom. Normalize rewrote **every** bare `println` → `Console.println`
  (a `Select` to an InstanceV native-fn field); the interpreter evaluates a bare member `a.b`
  as a 0-arg field access, so `Console.println` was auto-invoked → `()` → `Not callable: ()`.
  Minimal repro: `List("a","b").foreach(println)` and `val f = println; f("x")`.
- **Fixed:** Normalize now rewrites `println`/`print` to `Console.*` **only when applied**
  (a `(?=\s*\()` lookahead). A bare reference stays the plain name → every backend binds it
  to the intrinsic function value (interp globals, JVM Predef, JS `_println`, Rust intrinsic
  table). Surgical: only `println`/`print`, so paren-less 0-arg method calls like
  `gen.zipWithIndex` are untouched (an earlier dispatch-level `bareSelect` attempt regressed
  exactly those — reverted). Regress: `BugReproTest` (foreach(println), val-bound println,
  explicit `println()`/`println(x)`, `nanoTime()`); `examples/typed-data.ssc` runs end-to-end
  and is now in `ExamplesSmokeTest`'s curated run-set (which goes through Normalize); Rust +
  JS codegen + interp suites green.

## js-self-handling-cps-fn-not-run — `fixed` (2026-06-12)

- **Fixed:** `JsGen.runIfEffectful` wraps a non-CPS-context call to an effectful
  function in `_run`, so a self-handling CPS fn's lazy `_FlatMap` resolves at the
  value boundary (`println(workload())`). `_run` is idempotent on an already-resolved
  plain value (so a direct-runner result like `_handleOneShot(…)` is unaffected) and
  throws loudly on an unhandled effect; CPS-context calls go through `genCpsApply`,
  never `genApply`, so they're untouched. Verified via node: non-loop self-handling →
  3, multi-shot handled-in-while → 204, one-shot regression → 5. backendInterpreter 1678
  green. Regress: `JsEffectLoopTest` (self-handling + multi-shot). **effect-multishot now
  runs on JS.** Diagnosis below.
- **Found:** while landing `effect-cps-loops-js` (the perform-in-while lowering).
- **Symptom:** on the **JS backend only**, a function that handles its OWN effects
  internally (so it has no unresolved `perform`) but is still CPS-emitted (because its
  body contains `handle`/effect machinery) returns an **un-run lazy `_FlatMap`**. A
  value-position call to it (`println(workload())`) prints `[object Object]` instead of
  the result. Blocks the `effect-multishot` corpus on JS (and any self-handling block).
- **Repro (JS only; jvm + interp are correct):**
  ```scalascript
  multi effect NonDet:
    def choose(options: List[Int]): Int
  def program(): Int ! NonDet =
    val a = NonDet.choose(List(1, 2, 3))
    a
  def workload(): Int =
    val all = handle(program()) { case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt)) }
    all.length
  println(workload())   // JS: prints [object Object]; expected 3
  ```
  Note: NO `while` needed — this is **not** a perform-in-loop bug; the `while` fix is
  orthogonal. `effect-oneshot` (where `workload` is a *direct* `handle(...)` → a runner
  call → plain value) works on JS.
- **Root cause:** JS `_bind(c, f)` is **always lazy** (`return new _FlatMap(c, f)`),
  unlike JVM's `_bind` which is eager on a non-`Perform` value. A CPS'd self-handling
  function's chain has no `Perform` nodes, so on JVM it eager-resolves to a plain value,
  but on JS it stays a lazy `_FlatMap` that nothing runs at the (non-CPS) call site.
- **Verified fix hypothesis:** wrapping the value-position call in `_run` resolves it
  (`_run(workload())` → 3 / 12 / 204). The fix is to emit `_run(...)` at a non-CPS value
  boundary for a call whose result is a CPS'd (effectful) function — `_run` is idempotent
  on plain values, so it's safe for the direct-runner case too. Needs care in `genApply`
  to avoid wrapping calls that are themselves inside a CPS context (those go through
  `genCpsApply`). HIGH-ish risk — gate on the full effect suite + node tests.
- **Status:** open. The `effect-cps-loops-js` perform-in-while lowering landed without
  it; `effect-multishot` stays `n/a` on JS until this is fixed.

## interp-module-loader-dedup — `done` (busi confirmed, rozum seq-137)

- **Reported:** busi (`@busi-claude-code`), rozum `scalascript` seq-132 (2026-06-12).
- **Symptom:** interpreting (not `ssc check`) an entry that imports a large module via
  **two edges** (diamond) — e.g. `server.ssc` imports `dispatch.ssc` (~7942 lines)
  directly *and* via a small `route_spi.ssc` that also imports `dispatch` — blows up:
  pathological re-evaluation → OOM / hang at load time, 0 lines of the program run.
  `ssc check` is green (typer memoizes module loads; the interpreter loader did not).
- **Repro:** 3 modules — `big` (large/with a load-time side effect) + `spi` importing
  `big` + `entry` importing both `big` and `spi`. Without dedup, `big` is evaluated
  once per DAG path (exponential in diamond layers). See
  `runtime/.../InterpModuleDedupTest.scala`.
- **Root cause:** `SectionRuntime.runImport` created a fresh `Interpreter` and re-ran
  the imported module on **every import edge** — no cache keyed by module path.
- **Fix:** shared `moduleCache: Map[os.Path, Interpreter]` threaded through child
  interpreter constructors; `getOrElseUpdate(resolvedPath)` in `runImport` → each module
  evaluated once per run (init side effects run once, matching the typer). Spec
  `specs/interp-module-loader-dedup.md`.
- **Verify:** rebuild `installBin` on the landing pin, re-run the busi diamond (drop the
  `Any`-typed `route_spi` workaround). Regression: `InterpModuleDedupTest` (diamond +
  3-layer stacked diamond; asserts shared module loads exactly once).
- **Landed:** `f6d3245a3` (origin/main, 2026-06-12).
- **Confirmed:** busi bumped to `7470392e` + `installBin`, removed the `Any` workaround,
  their phase23 diamond (was OOM at load) now loads + passes (30 checks), full regression
  green, ph-2 domain-module split unblocked (rozum seq-137). **Closed.**
