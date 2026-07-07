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

## plugin-lazyload-extern-imports — `open` (2026-07-07)

- **Found by:** claude (tkv2-pwa-adopt slice) — the stock `examples/pwa/pwa-demo.ssc`
  fails on clean origin/main.
- **Symptom:** extern defs provided by lazy-loaded std plugins are unreachable from
  `.ssc`: `[smtpSend](std/smtp.ssc)` / `[tcpListen](std/tcp.ssc)` → "'X' not found in
  std/Y.ssc" at import; `requires: [std.pwa]` + `pwa(...)` → "Undefined: pwa".
  Preloaded plugins (std/ui frontend/fetch) are unaffected. Reproduced with a fresh
  origin/main worktree build — pre-existing, likely from the recent plugin-loading /
  stable-SPI stream.
- **Repro:** `bin/ssc examples/pwa/pwa-demo.ssc` (stock example) or a 5-line probe
  importing `[smtpSend](std/smtp.ssc)`.
- **Impact:** every opt-in plugin capability (smtp send, raw TCP / IMAP sim, pwa) is
  dead from user code on main. busi's live deploys pin an older ssc, so production
  is unaffected until a bump.
- **Note:** `tests/conformance/tkv2-pwa.ssc` is `pending:` on this — flip it on once
  fixed. The pwa generators are covered by `PwaPluginTest` meanwhile.

## jvmgen-block-call-empty-parens — `open` (2026-07-07)

- **Found by:** claude (tkv2-components slice), via the full-corpus A/B: 4 tests
  (signals, effects, rest-validate, distributed-map) fail the JVM lane in any FRESH
  build of origin/main, while the shared main checkout "passes" only because its
  `bin/ssc` is STALE (pre-2026-07-03 source — its generated preamble lacks the
  webauthn `configureStore` block and `Bench.opaque`). Reproduced on a pristine
  origin/main worktree + fresh `installBin`.
- **Symptom:** `ssc run-jvm tests/conformance/signals.ssc` — user code
  `val doubled = computed { … }` is emitted as `computed() { … }` (empty first arg
  list + trailing block) → Scala compile error "missing argument for parameter
  thunk". Same for `effect { … }`.
- **Repro:** `scripts/new-worktree probe && cd ../scalascript-wt-probe &&
  scripts/sbtc installBin && bin/ssc run-jvm tests/conformance/signals.ssc`.
- **Suspect window:** whatever changed JvmGen's call-with-block emission between the
  main checkout's stale binary (~2026-07-03) and current origin/main. Not caused by
  the tkv2 slice (repro has none of its commits). NOTE: rebuild the shared main
  checkout's bin/ssc after fixing — its staleness masks this class of regression
  in any corpus run executed from the main checkout.

## jsgen-signal-type-import-vs-preamble — `fixed` (2026-07-07)

- **Found by:** claude (tkv2-components slice) — first .ssc module importing the opaque
  `Signal` TYPE from `std/ui/primitives.ssc` and emitting to JS.
- **Symptom:** `ssc emit-js` of any file importing `[Signal, …](std/ui/primitives.ssc)`
  dies on Node with `SyntaxError: Identifier 'Signal' has already been declared` —
  the import emits `const Signal = std.ui.primitives.Signal`, colliding with the
  signals.mjs preamble `function Signal`.
- **Root cause / fix:** the `jsgen-toplevel-name-vs-preamble` (#5) class. `Signal` is
  now pre-seeded into `declaredBindings` (like the std/fs file-ops): the import const
  is skipped; type positions erase, and value uses correctly resolve to the preamble
  reactivity constructor. `JsGen.scala` declaredBindings init.
- **Guard:** `tests/conformance/tkv2-component.ssc` (imports the type transitively via
  `std/ui/component.ssc`, INT==JS).

## jsgen-reserved-param-body-rename — `fixed` (2026-07-07)

- **Found by:** claude (tkv2-components slice) — `std/ui/component.ssc`'s
  `ctxSignal(ctx, name, default)` parameter named `default`.
- **Symptom:** a def with a JS-reserved-word parameter (e.g. `default`), emitted through
  the namespace/object member path (`const f = (a, b, default_p) => …`), renames the
  formal via `safeJsParam` but NOT the body references — the body emits bare `default`
  → Node `SyntaxError: Unexpected token 'default'`.
- **Root cause / fix:** the object-member def emission built `bodyJsRaw` without
  `withParamRenames` (unlike the top-level def paths at JsGen.scala:2462-2482). Now the
  same `objDefRenames` map wraps body generation. Any .ssc module function with a
  reserved-word param was affected on the JS lane.
- **Guard:** `tests/conformance/tkv2-component.ssc` (ctxSignal carries a `default`
  param, INT==JS).

## green-main-full-sbt-test-gating — `open` (2026-07-07)

- **Found by:** codex, while verifying the `plugin-cli-oslib-shadow` fix.
- **Symptom:** after the `PluginCliTest` compile blocker is fixed, the root
  `sbt "test"` gate is still red in unrelated integration suites.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "test"`.
  The second full run completed in 29:08 with non-zero exit. It confirmed
  `PluginCliTest` now passes, then reported:
  - `CrossBackendIntrinsicParityTest`: JS-only drift for `webauthnConfigureStore`
    and `webauthnStoreRemove`.
  - `JvmGenSwingRuntimeTest`: failed inside the `cli / Test / test` aggregate.
  - `StableSpiEnforcementTest`: `tcp-plugin` still imports
    `scalascript.interpreter.Value` from a value-surface plugin.
  - `AgentConformanceTest`: suite aborted with `java.net.BindException:
    Address already in use` in `beforeAll`.
  - JS test-framework fallout: several Scala.js modules report
    `RPCCore$ClosedException` after a Node-side non-zero exit.
- **Notes:** the first full run hit a transient Scala 3 compiler crash in
  `clientEvm/Test/compile`; targeted `clientEvm/Test/compile` passed immediately,
  so the durable gate is the second run's failure set above.
- **Status:** open. Split into focused fixes or intentional skips/pending policy,
  then rerun the affected suites before another root `sbt "test"` attempt.
- **Progress (2026-07-07, `8dfd2989e`):** `CrossBackendIntrinsicParityTest`
  fixed by documenting `webauthnConfigureStore` and `webauthnStoreRemove` as
  JS-core/JVM-`auth-plugin` exceptions; targeted parity test passes.
- **Progress (2026-07-07, `484d56101`):** `StableSpiEnforcementTest` fixed by
  migrating `tcp-plugin` from direct `scalascript.interpreter.Value` constructors
  to `PluginValue`; `StableSpiEnforcementTest` and `tcpPlugin/test` pass.
- **Progress (2026-07-07, `395e8aab3`):** `JvmGenSwingRuntimeTest` fixed by
  replacing the local `v1`-anchored repo-root finder with `TestPaths.repoRoot`;
  targeted Swing runtime test passes 5/5.
- **Progress (2026-07-07, `eae491e11`):** `AgentConformanceTest` fixed by
  binding its mock OpenAI gateway to a loopback ephemeral port instead of
  hard-coded `19694`; targeted conformance test passes 3/3.
- **Progress (2026-07-07, `7e2650e2c`):** `PluginBridgeTest` fixed by aligning
  the test stub with the bridge's stable SPI raw-value contract
  (`IntV` args arrive at `NativeImpl` as `Long`, and raw `Long` returns wrap back
  to v2 `IntV`); targeted `v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest`
  passes 22/22.
- **Progress (2026-07-07, `2e1f2c287`):** `V2ConformanceTest` fixed by letting
  real `.ssc` `Defn.Def` bodies shadow same-named plugin globals after
  `stripExternDecls`; `std/mcp/types.ssc` `requireString` now wins over the
  `validate {}` helper in mcp imports. Also renamed the conformance fixture's
  local `args` to `mcpArgs` so the JS lane avoids the known
  `jsgen-toplevel-name-vs-preamble` collision. Verified full
  `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` (62/62) and
  `scripts/conformance -- --only mcp-types --no-memo` (INT/JS pass).
- **Remaining targeted blockers (2026-07-07):**
  `backendWasm/testOnly scalascript.codegen.WasmBackendTest` still has 7
  effectful-WASM failures: handler/resume and `String*` effectful mains print
  empty output or throw under Node v26.4.0, arithmetic/HOF effect bodies print
  empty output, and the cross-module imported-effect case prints empty output.
  Scala.js `loadedTestFrameworks` fallout still needs re-checking after the
  deterministic JVM/v2/WASM failures are fixed.

## plugin-cli-oslib-shadow — `fixed` (2026-07-07)

- **Found by:** codex, while stabilizing the red `origin/main` CI run
  `28832706348`.
- **Symptom:** CI `sbt - compile and test` builds the launcher but fails during
  `Test via sbt` while compiling
  `v1/tools/cli/src/test/scala/scalascript/plugin/PluginCliTest.scala`.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "cli/Test/compile"`.
  The failure reports `type Path is not a member of scalascript.compiler.plugin.os`
  and missing `temp`, `remove`, `makeDir`, `read`, `write`, `exists`, `list`,
  `copy`, and `walk` members.
- **Root cause:** because `PluginCliTest` is in package `scalascript.compiler.plugin`,
  the local `scalascript.compiler.plugin.os` package shadows os-lib's root `os`
  package.
- **FIXED (2026-07-07, `6d133361a`):** qualified all os-lib references in
  `PluginCliTest` as `_root_.os`, so the test no longer resolves the local plugin
  package.
- **Verified:** `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "cli/Test/compile"`;
  `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "cli/testOnly scalascript.compiler.plugin.PluginCliTest"`
  (8/8).

## v2-cellset-flc-corruption — `fixed` (2026-07-05)

- **Found by:** claude (v2-recursion-opt slice), via `map-ops` regressing to
  `SKIP(no-main)` in the v2 bench sweep.
- **Symptom:** `expected Map, got 0` crash on `map-ops` via the ssc1c pipeline; the
  failure class is broader — SILENT data corruption: any generic-cell `var` reassigned
  from a non-Int expression could store `IntV(0)` instead of the real value.
- **Root cause:** the `cell.set` FLC fast path in `FastCode` (`v2/src/Runtime.scala`)
  assumed "tryFLC fails for Float/String expressions", but the FastCode phase-1/2 batch
  (2026-07-04) added OPTIMISTIC leaves to `tryFLC` (`App(Global)`, `cell.get`,
  `arr.get`, `fieldAt`, `Local`) that coerce non-Int values to `0L`. So
  `m = m.updated(k, v)` (App body returning a Map) FLC-compiled and stored `IntV(0)`
  over the map.
- **Fix:** new `flcProvablyLong(t)` structural predicate; `cell.set` takes the FLC
  fast path ONLY when the body is provably Long (int literals, `lcell.get`, int
  arith, `.toInt/.toLong/.length/.size`). `lcell.set` is unaffected (lcells hold Long
  by construction).
- **Blast radius audit:** all 10 var-reassign corpus programs compared old-vs-new —
  identical outputs everywhere except map-ops (now correct: 124750); no other corpus
  program had engaged the corrupt path.

## v2-conformance-empty-output-flake — `fixed` (2026-07-01)

- **Found by:** codex, while continuing K49 after the K48 multi-op typed handler work.
- **Symptom:** `cd v2 && ./conformance/check.sh` can report a contiguous block of unrelated
  `got []` failures. Direct reruns of the first failing examples pass, so the useful failure
  signal is lost when Java/Rust stderr is discarded.
- **Repro:** run the assembled-jar harness, not a dev runner: `cd v2 && ./conformance/check.sh`.
  K48 observed two full runs failing in different sections after otherwise unrelated Rust/Java
  activity.
- **Root cause:** the harness built every run into the shared path `/tmp/ssc-conformance.jar`.
  Parallel agents or repeated harness runs could overwrite that jar while an earlier run was still
  executing it, producing `NoClassDefFoundError: ssc/Program$` followed by `Invalid or corrupt
  jarfile`. Rust failures were downstream: empty generated `.rs` files had no `main`.
- **FIXED (2026-07-01, `d4ca120bf`):** `check.sh` now builds the assembled jar inside the run's
  unique diagnostic log directory, captures Java/Rust stderr and stdout artifacts, retries empty
  Java stdout once, and prints a diagnostic summary on failure.
- **Verified:** `bash -n v2/conformance/check.sh`; reproduced the old flake once and captured the
  corrupt-jar root cause; after switching to the per-run jar, two consecutive full
  `cd v2 && ./conformance/check.sh` runs passed (`run1 exit=0`, `run2 exit=0`). After rebasing on
  KC7, a final full run with the KC7 tests also passed (`final exit=0`).

## js-spa-hashchange-bridge-sync — `fixed` (2026-06-29)

- **Reported by:** Sergiy, from the rozum Unified Control Center (`clients/control/control-center-live.ssc`).
- **Symptom:** clicking a hash-route navigation control changed `location.hash`, but the visible SPA route did
  not switch until a manual browser refresh. The reactive `hashSignal()` / `computedSignal` graph updated, but
  mounted `data-ssc-cond` branches still kept their previous `display:none` / `display:contents` state.
- **Repro:** mount a JS browser SPA with `hashSignal()` feeding an `eqSignal` / route guard, then change
  `window.location.hash` and dispatch `hashchange`; before the fix the computed signal value changed while the
  mounted branch styles did not.
- **Root cause:** `_ssc_ui_hashSignal()` already registered a `hashchange` listener and updated the reactive
  graph, but `_ssc_ui_mount()` only pushed computed values from the reactive graph into the DOM bridge store
  (`_sv` / `_sb`) through `_syncBridgeSignals()` after bridge-owned `_set(...)` calls. Native browser
  `hashchange` events never called that bridge sync.
- **FIXED (2026-06-29, `23789503d8b9c2a4cba41545ba5ae7ba0219bc1b`):** `_ssc_ui_mount()` now listens for
  `hashchange` and calls `_syncBridgeSignals()`, so `data-ssc-cond`, signal text, and other mounted bridge
  subscribers observe hash-derived computed changes without a refresh.
- **Guard:** `JsGenStdImportTest` now dispatches `hashchange` after mount and asserts the `data-ssc-cond`
  branches toggle. Also re-ran `SpaComputedBodyBridgeTest` to cover the adjacent computed-to-bridge path.

## v2-conformance-echo-backticks — `fixed` (2026-06-29)

- **Found by:** codex, while running full `v2/conformance/check.sh` for K46 async/actor breadth.
- **Symptom:** the conformance assertions were green, but the harness printed shell noise such as
  `show: command not found`, `method: command not found`, `effect: command not found`, and
  `a,b,c,d: command not found` to stderr.
- **Repro:** `cd v2 && conformance/check.sh`; the offending `echo "..."` lines contained Markdown
  backticks, so the shell performed command substitution before printing the heading.
- **Root cause:** double-quoted shell strings around headings that intentionally contained literal
  backticks.
- **FIXED (2026-06-29):** changed those headings to single-quoted strings. `bash -n
  v2/conformance/check.sh` passes, and the final full K46 conformance rerun completed successfully
  with captured stdout/stderr checked for `FAIL` and `command not found` (none present).

## parser-trysplitparse-quadratic-hang — `fixed` (2026-06-28)

- **Found by:** busi (phone-demo hub). A `/api/issue` route used `given` as a local val name: `val given = req.form.getOrElse("number", ""); val number = if given.length > 0 then given else …`. Loading the ~3500-line `demo_server.ssc` pegged one core at ~100% CPU and never bound (>90s); the *same* code in a tiny file instead fast-failed with `illegal start of definition`. (busi originally mis-attributed this to the `if <param> then <param> else …` shape and to a `View[Int]` — both red herrings; the trigger is purely the identifier name.)
- **Root cause:** `given` is a Scala-3 soft keyword, so scalameta rejects it as an identifier → in `Parser.parseScalaWithDiagnostic` BOTH the Source-mode parse and the `{…}` Term-mode parse fail → the `trySplitParse` fallback runs. That fallback tried EVERY split point (`lines.length - 1 to 1 by -1`), each re-parsing an O(N)-line `prefix` as `Source` plus a `suffix` as `Term`. For a large block that is O(N) parses over O(N)-line prefixes = **O(N²)** total. Confirmed size-driven, not single-parse-exponential: a 1010-line block ≈ 6s, a ~3500-line block ≈ 90s; a `jstack` mid-hang showed `main` in `Parser$.trySplitParse$…` → `…prefix.parse[Source]` → scalameta `argumentExprsInParens` recursion.
- **Minimal repro:** `val given = "x"; val number = if given.length > 0 then given else "z"` in a code block — a fast `illegal start` in a small file, a ~quadratic hang in a multi-thousand-line one. Renaming `given` → `gv` parses and runs fine.
- **FIXED (2026-06-28):** bounded `trySplitParse` to small trailing suffixes — `private val MaxSplitSuffixLines = 48`, range `lines.length - 1 to math.max(1, lines.length - MaxSplitSuffixLines) by -1`. The handler-file pattern this fallback targets (class defs + a trailing lambda) always has a short trailing term, so only the last few split points are useful; small blocks (≤48 lines) keep the original full-range behaviour. Turns the 90s hang into a fast diagnostic (busi hub: 90s → ~3s `illegal start`).
- **Guard:** `ParseErrorPositionTest` — "large block with `given` as an identifier yields a fast diagnostic, not a quadratic hang" (2500-line block; asserts a populated `parseError` in <15s). All 146 `scalascript.parser.*` tests pass; the handler-file trailing-lambda split still parses; busi `make v2-test` + `make v2-test-js` are 47/47 on both backends with the rebuilt jar.
- **Note:** verified against this branch's base (`origin/main` @ ce0554245) — `trySplitParse` is byte-identical to the commit busi pins (72d0196f3), where it was first reproduced + the fix built/tested. busi keeps its own workaround (no `given` val, `getOrElse` auto-number) so it is unaffected; this lands the parser-robustness fix for everyone.

## jsgen-emitjs-capability-standalone — `fixed` (2026-06-22)

- **Found by:** busi (deep-offline browser/Node bundle) — the standalone-bundle frontier after `jsgen-emitjs-effect-handler`: `inbox`/`ksef`/`repo*` (clock) and the crypto path failed under raw `ssc emit-js | node`, while the JIT path (`SSC_JIT_BACKEND=js`) was green.
- **Symptom (two distinct bugs):**
  - **(clock)** A `RuntimeCall` intrinsic (`nowMillis` → `Date.now`) called inside an *effectful* (CPS-lowered) function emitted the bare source name (`nowMillis()`) → `ReferenceError: nowMillis is not defined`. `dispatchIntrinsicJs` rewrote it for `Term.Apply` sites in `genExpr`, but `genCpsApply`'s "regular call" path didn't.
  - **(crypto)** Importing a `std/crypto` extern (`[sha256](std/crypto.ssc)`) emitted `const sha256 = std.crypto.sha256` AND added `sha256` to `declaredBindings` (disabling the `sha256` → `_sha256` intrinsic rewrite at call sites). The namespace member was `(typeof _ssc_ui_sha256 !== 'undefined') ? _ssc_ui_sha256 : undefined` = `undefined` under Node → `not callable: ()`.
- **FIXED (2026-06-22):**
  - **(clock)** `genCpsApply` now handles `Term.Name(fname)` whose `intrinsicRuntimeTarget(fname)` is defined: it binds the args CPS-style and emits `target(args)` (e.g. `Date.now()`). New `private[codegen]` helper `JsGen.intrinsicRuntimeTarget`.
  - **(crypto)** In `genObjectAsExpr`, an extern namespace member falls back to its `RuntimeCall` intrinsic target (`_sha256`) instead of `undefined` when the host UI stub is absent — guarded by an inner `typeof` (stays `undefined` if the target isn't emitted) and by `target != fname` (so identity intrinsics like std/auth's `webauthnChallenge` don't self-reference → TDZ). Browser still prefers the `_ssc_ui_*` host stub.
- **Guards:** `tests/conformance/js-cps-intrinsic-rewrite.ssc` (nowMillis in a CPS body) + `tests/conformance/js-crypto-extern-standalone.ssc` (`sha256("abc")` standalone), both INT==JS. busi standalone `ssc emit-js tests/v2/<f>.ssc | node` sweep: **13/21 → 20/21** v2 domain files (only `auth` remains — its WebAuthn externs are host-only, no Node preamble, a separate feature). busi `make v2-test` + `make v2-test-js` green (26 files); before/after emit-js+node sweep over all conformance tests: **zero PASS→FAIL regressions** (84→84).
- **Still open (separate):** `auth.ssc` standalone needs Node WebAuthn impls (`webauthnChallenge`/`webauthnVerify*` are identity-`RuntimeCall` host externs with no `_webauthn*` preamble). `jsgen-toplevel-name-vs-preamble` (#5, general preamble-shadow) also still open.

## rust-index-read-moves-noncopy — `fixed` (2026-06-22)

- **Found by:** mellow-shrew (self), via an end-to-end `cargo run` smoke against the just-landed rust-web-toolkit follow-ons (`origin/main` @ d0141a1d4). The `backendRust` unit suite is string-match only (no `cargo` compile), so it missed a generated-Rust move error.
- **Symptom:** an index *read* on a non-Copy element sequence panicked the Rust compiler, not the program — `error[E0507]: cannot move out of index of Vec<String>`. Minimal repro:
  ```scalascript
  @main def run(): Unit =
    val parts: List[String] = "a,b,c".split(",").toList
    println(parts(1))      // → parts[(1i64) as usize]  — moves the String out of the Vec
  ```
  `Vec<i64>` indexing was fine (i64 is `Copy`), so the bug only surfaced once `f2afd3378` made `.split`/`.toList` results indexable (`Vec<String>`, non-Copy).
- **Root cause:** the `seq(i)` index-read lowering (`RustCodeWalk.scala`) emitted a bare `seq[(i) as usize]`. Using a `Vec`'s `Index` output by value moves it; legal only for `Copy` elements.
- **FIXED (2026-06-22):** index *reads* now emit `seq[(i) as usize].clone()` — required for `Vec<String>`/structs, elided by rustc for `Copy` elements (i64/char/bool), so zero cost. The `seq(i) = v` *store* path is now handled explicitly in `Term.Assign` (new `asSeqIndexTarget` helper) so the assignment **target** stays bare — you can't assign to a clone.
- **Guard:** `RustGenCollectionTest` — "index read on a String seq clones the element" + "index store on a mutable array stays bare". Verified end-to-end with a throwaway `cargo run` smoke (all new collection/string ops compile + run): output `30 70 70 30 100 6 1 a-b-c true true true b 3`. `backendRust` 235/0.
- **Follow-up (filed in BACKLOG):** the rust backend has no `cargo`-compile coverage in its unit suite — this whole bug class (move/borrow errors in valid-looking generated Rust) is invisible to string-match tests.

## jsgen-emitjs-effect-handler — `fixed` (2026-06-22)

- **Found by:** busi (deep-offline browser bundle) — blocker #3 of 5 in `src/v2/specs/lf-1-browser-bundle.md`. Only the raw `emit-js` standalone path was affected; the JIT path (`SSC_JIT_BACKEND=js`) was always green.
- **Symptom:** raw `emit-js` of code using an effect + deep handler (an effectful `query` that folds over `Eff.read`, run inside a `handle`) failed at runtime — `TypeError: arr.reduce is not a function`, or `Unhandled effect: …`.
- **FIXED (2026-06-22) for the direct + single-import case — two layers:**
  - (a) **Imported effect now recognised.** `genImport` runs `analyzeEffects` on the imported module and merges the discovered `effectOps`/`effectfulFuns`/`multiShotEffects` back into the importer, so an effect-performing function defined in an imported module (e.g. `query` calling `Box.read`) lowers its op to `_perform`+`_bind` (not a generic `_dispatch`) and is CPS-transformed — the `_Perform` no longer leaks into the fold.
  - (b) **Effectful lambdas emit a CPS body.** `genExpr` for `Term.Function` now emits the body via the CPS path when `jsForTermPerforms(body)` — so an effect-performing call in a handler-body thunk (`runBox(() => query(...))`) returns the Free computation for the handler to interpret instead of being `_run`-wrapped (which threw "Unhandled effect"). (`jsForTermPerforms` made `private[codegen]`.)
  - **Guard:** `tests/conformance/effect-imported-handler.ssc` (+ `lib/effect-box.ssc`) — an imported effect + generic effectful reader + deep handler, run twice; INT==JS==JVM. busi `make v2-test-js` (full effectful v2 core on JS) green; `CrossBackendPropertyTest` effect cases green. Single-file and 2-level-import effect+handler code now runs under raw `emit-js`.
- **FIXED — transitive multi-level imports (3+ levels) (2026-06-22, whole-program pass).** Spec `specs/emitjs-effect-whole-program.md`. `JsGen.analyzeEffects` now collects trees across the ENTIRE import graph (recursively resolve imports — reusing `genImport`'s resolution — parse each once, visited-set for diamonds/cycles) and runs `EffectAnalysis.analyze` on the union, so a function calling a transitively-imported effectful function (busi: `ledger.accountBalance` → `journal.query` → `Journal`) is marked effectful and CPS-lowered. `effectOps`/`effectfulFuns`/`multiShotEffects` are now SHARED constructor params threaded to child generators (like `topLevelConsts`), populated once by the entry generator's whole-program pre-pass — every module emits against the same view; the per-`genImport` `analyzeEffects`+merge (the single-import fix) is dropped as redundant. **Result:** `ssc emit-js tests/v2/ledger.ssc | node` runs end-to-end (all checks pass), as do obligation/plan/payment/gate/income standalone. Guard: `tests/conformance/effect-transitive-handler.ssc` (+ `lib/eff-a.ssc`, `lib/eff-b.ssc`), INT==JS==JVM; busi `make v2-test` + `make v2-test-js` green; cross-backend green.
- **Remaining busi standalone-bundle frontiers — UPDATED 2026-06-22 (claude-code, `fix/js-standalone-frontiers`).** All three originally-listed frontiers are now CLOSED under raw `emit-js | node`:
  - `trust.ssc` ✅ — the CPS gap was a **unary operator on an effectful operand** (`!x` / `-x` where the operand performs an effect) falling through to `genExpr`, which `_run`-wrapped it and ran the effect outside the handler. `Term.ApplyUnary` now CPS-lowers via `_bind(operand, v => op(v))`. Guard: `tests/conformance/js-applyunary-effect-cps.ssc`.
  - `qr.ssc` ✅ — the `Method not found` was `Array.fill(n)(x)` (+ `tabulate`/`range`/`empty`): `Array(...)` emits a JS array literal, so the bare `Array` value at a `_dispatch` site is the native constructor, which lacks the Scala statics. `_dispatch` now routes these to the `List` companion (shared JS array repr). Guard: `tests/conformance/array-companion-statics.ssc`. (Plus the `fn-typed-field` dispatch refinement above.)
  - `ksef.ssc` ✅ (syntax) — the duplicate global `const readFile` is gone: the std/fs file-ops (`readFile`/`writeFile`/`exists`/… 14 names) are extern decls whose real impl is the preamble (`JsRuntimeFs`), so they're seeded into `declaredBindings` and never re-emitted as a colliding top-level `const`. `node --check` now passes. This closes the std/fs subset of the `jsgen-toplevel-name-vs-preamble` (#5) class.
- **New frontier exposed (next):** `ksef.ssc`/`inbox.ssc`/`repo*` now reach runtime and hit `ReferenceError: nowMillis is not defined` / `not callable: ()` — the `nowMillis` clock capability (`JsCapabilities`: `QualifiedName("nowMillis") -> RuntimeCall("Date.now")`) is wired on the JIT path but not emitted into the raw `emit-js` preamble; `auth.ssc` hits a similar crypto-capability gap. **This overlaps the active `core-min-clock-env-migrate` (Clock/Env→plugin) work and is left for that stream / a follow-up.** Standalone emit-js+node sweep: **85/113 conformance + 13/21 busi v2 domain files** pass; the rest are clock/crypto capability gaps + infra (actors/cluster/distributed/sql).

## jsgen-toplevel-name-vs-preamble — `open` (2026-06-22)

- **Found by:** busi (deep-offline browser bundle) — blocker #5 of 5 in `src/v2/specs/lf-1-browser-bundle.md`.
- **Symptom:** a top-level user binding named exactly like a preamble helper (e.g. `val scope = …` vs the runtime's user-facing `function scope(scopeName)` for CSS scoping, SPEC §8.4) emits a colliding top-level `const scope = …` → `SyntaxError: Identifier 'scope' has already been declared` under `node --check`. Other preamble names (`doc`, `escape`, `assert`, `List`, `Decimal`, …) can collide the same way.
- **Additional repro (2026-07-07):** `scripts/conformance -- --only mcp-types`
  passes INT but JS fails before printing anything because
  `tests/conformance/mcp-types.ssc` used `val args = ...`, colliding with the
  JS preamble's `function args()` from `std/os`. The conformance fixture should
  avoid this unrelated known bug (`mcpArgs`), while the general name-mangling
  fix remains open here. Fixture workaround landed in `2e1f2c287`; the broad
  top-level user-binding rename is still open.
- **Workaround (documented in the lf-1 spec):** name the top-level binding something the preamble doesn't define (e.g. `lfScope`). Low frequency.
- **Fix sketch (deferred):** a robust fix needs the set of names the (capability-gated) preamble declares; emit a colliding top-level user binding under a renamed identifier (propagating references) or as a shadow. There is a curated `preambleConsts = Set("Console","attr","scope")` in JsGen used today only for *object* declarations (via `Object.assign`); it would need to cover `val`/`def`/`enum` and the full preamble surface. Left as a documented limitation pending the dedicated effort.

## jsgen-fn-typed-field-autoinvoke — `fixed` (2026-06-22)

- **Found by:** busi (deep-offline browser bundle) — facet of blocker #4 of 5 in `src/v2/specs/lf-1-browser-bundle.md` ("a generic `View.step` fold reaches syntax-valid JS but the `step` field is not callable").
- **Symptom:** a case-class field whose value is a function (e.g. `View.step: (S, Int) => S`), passed as a *value* to a HOF (`xs.foldLeft(v.init)(v.step)`), threw `TypeError: fn is not a function` on the JS backend. A direct call `v.step(1, 2)` worked; only the eta/value position failed. interp + JVM were correct.
- **Root cause:** lambdas are emitted variadic (`(...__a) => …`, to support tuple-destructuring), so their `.length` is 0. Accessing the field as a value lowers to `_dispatch(v, 'step', [])`, and `_dispatch`'s zero-arg branch auto-invoked any property whose `typeof === 'function' && .length === 0` — so it CALLED the variadic field-lambda (no args → NaN) instead of returning it.
- **FIXED (2026-06-22):** in `_dispatch`, a no-arg property access on a case-class / enum instance (`obj._type !== undefined`) returns the data field as-is — case-class methods live in `_extensions`, never on the object, so an existing own-property is always a data field and must never be auto-invoked. Direct calls (`args.length > 0`) and all non-`_type` objects are unchanged.
- **REFINED (2026-06-22):** the blanket `obj._type !== undefined && args.length === 0 → return as-is` guard was too broad — it also suppressed *genuine* zero-arg methods that SHOULD auto-invoke (`JsonValue.asString` and friends, emitted as a real `() => …` / `function(){…}` own-property), so `tests/conformance/json-value.ssc` failed under raw `emit-js`. The guard is replaced with a precise test inside the function branch: a zero-arg-arity own-property is returned as a reference only when its source is a **variadic-emitted lambda** (`(...__a) => …`, detected via `Function.prototype.toString`); a genuine zero-arg function is still auto-invoked. Net: `json-value` now passes standalone (FAIL→PASS in the emit-js+node conformance sweep) while `fn-typed-field` stays green; before/after sweep over all 113 conformance tests showed **zero PASS→FAIL regressions**.
- **Guard:** `tests/conformance/fn-typed-field.ssc` (variadic field as value) + `tests/conformance/json-value.ssc` (genuine zero-arg method auto-invoke), INT==JS==JVM. busi `make v2-test-js` + `CrossBackendPropertyTest`/`MoneyCrossBackendTest`/`CustomDerivesMirrorCrossBackendTest` green; `tests/v2/qr.ssc` now runs as a raw `emit-js` standalone bundle.

## jsgen-dup-enum-global — `fixed` (2026-06-22)

- **Found by:** busi (deep-offline browser bundle) — blocker #2 of 5 in `src/v2/specs/lf-1-browser-bundle.md` ("emit-js / emit-spa for tests/v2/local_journal.ssc fails syntax checks with duplicate global `Pending` declarations from ObligationStatus.Pending and DeferredActionStatus.Pending").
- **Symptom:** two enums (in the same file or different modules) that share a *parameterless* case name each emitted a top-level global `const <Case> = {_type:'<Case>', _tag:N}`; child generators share the global scope, so the bundle had a duplicate `const` and Node rejected it: `SyntaxError: Identifier 'Pending' has already been declared`. `SSC_JIT_BACKEND=js` was fine; only raw `emit-js`/`emit-spa` failed (`node --check`).
- **Root cause:** the top-level (`genStat`) `Defn.Enum` emission unconditionally emitted `const <Case>`/`function <Case>` per case. Enum-case tags are global-by-name, so the two `Pending` objects are byte-identical; qualified refs already go through the companion (`_dispatch(ObligationStatus, 'Pending', [])`), not the bare global.
- **FIXED (2026-06-22):** a shared `declaredEnumCases: Set[String]` (threaded to child gens like `declaredBindings`) skips re-declaring a global enum-case binding already emitted by another enum; each companion still references the surviving (structurally identical) global. Only the global `genStat` path is guarded — module-IIFE (`genObjectAsExpr`) enum cases are scoped and don't collide. JIT/JVM/interp paths untouched.
- **Guard:** `tests/conformance/enum-shared-casename.ssc` (+expected) — two enums with a shared `Pending` case; within-enum equality + `.values.size` identical on INT/JS/JVM (cross-enum equality is intentionally NOT asserted: after dedup the JS objects are shared, which never matters in well-typed code). `EnumCrossBackendTest` 3/3; busi `tests/v2/local_journal.ssc` emit-js now passes `node --check`.

## jvm-multishot-result-type — `fixed` (2026-06-21, `39b7c665f`)

- **Found by:** benchmark perf-divergence sweep (`./bench.sh`), accepted from `SPRINT.md`.
- **SHA at filing:** `0ee00a29f` (`feature/jvm-multishot-result-type` worktree, after
  `sbt -no-colors cli/installBin`).
- **Symptom:** `bench/corpus/effect-multishot.ssc` reports `n/a` on the JVM backend even though the
  source declares `def workload(seed: Long): Long`. The bench wrapper uses an `AtomicLong` sink and
  emits `_ssc_sink.getAndAdd(workload(_ssc_sink.get()))`, but `emit-scala` currently lowers the CPS
  effectful `workload` as `def workload(seed: Long): Any`, so `scala-cli` rejects the wrapper with
  `Found: Any; Required: Long`.
- **Repro (real harness):** `./bench.sh effect-multishot --backend jvm` -> `n/a`; then
  `scala-cli --java-opt -XX:CompileThreshold=100 --java-opt -XX:-BackgroundCompilation --server=false
  /tmp/ssc-bench-jvm-effect-multishot.sc` shows the three `getAndAdd(workload(...))` type errors.
- **Root cause:** the top-level CPS def emitter always generated `def f(...): Any = ...` for any
  transitively effectful function. That is correct for effect-row defs (`A ! Eff`) that may return a
  Free computation, but wrong for total wrappers such as `def workload(seed: Long): Long` that handle
  their effects internally. The earlier handle-result fixes made `all.foldLeft(...)` compile, but the
  def boundary still widened the declared `Long` to `Any`.
- **FIXED (2026-06-21, `39b7c665f`):** JVM CPS def emission now keeps declared non-effect-row result
  types and casts the final CPS result at the boundary; `A ! Eff` defs still emit `Any`. The same helper
  is used for nested CPS defs inside CPS blocks. Regression guard: `JvmGenEffectsRuntimeTest` proves
  `addLong(workload(0L))` compiles and runs, so the total CPS def has static type `Long`.
- **Verified:** `sbt -no-colors "backendInterpreter/testOnly scalascript.JvmGenEffectsRuntimeTest"` =
  34/34; `sbt -no-colors cli/installBin`; `./bench.sh effect-multishot --backend jvm` = 0.075 ms/iter
  (was `n/a`); `./bench.sh effect-oneshot --backend jvm` = 0.160 ms/iter (same root cause).

## asm-jit-effect-pathology — `fixed` (2026-06-21, `0d5e03b87`)

- **Found by:** benchmark perf-divergence sweep (`./bench.sh`), accepted from `SPRINT.md`.
- **Symptom:** the synthetic `ssc-asm` backend (`SSC_JIT_BACKEND=asm`) is orders of magnitude slower than
  default `ssc` on `bench/corpus/effect-oneshot.ssc`, a hot loop that performs and handles a one-shot
  algebraic effect (`Bump.tick(resume) => resume(1)`). Current worktree repro after `sbt cli/installBin`:
  `./bench.sh effect-oneshot --backend ssc` = 0.043 ms/iter; `./bench.sh effect-oneshot --backend ssc-asm`
  = 9.46 ms/iter.
- **Root cause:** `JavacJitBackend.walkLong` already lowered one-shot tail-resume effect calls to
  `JitGlobals.resolveEffectLong*`, but `AsmJitBackend.walkLong` did not. The `Bump.tick().toLong` expression
  therefore made ASM bytecode JIT bail and left the workload on the slow effect trampoline.
- **FIXED (2026-06-21, `0d5e03b87`):** ASM now mirrors the Javac lowering for active one-shot effect
  resolvers (`resolveEffectLong`, `resolveEffectLong1`, `resolveEffectLong2`) and treats a resolved effect
  call as Long-shaped for `.toLong`/`.toInt` routing. Regression guard: `AsmEffectJitTest` compiles and runs
  `acc + Bump.tick().toLong` through `AsmJitBackend` with an active resolver.
- **Verified:** `sbt -no-colors "backendInterpreter/testOnly scalascript.interpreter.vm.jit.AsmEffectJitTest
  scalascript.EffectOneShotFastPathTest scalascript.JitLintTest"` = 85/85; `sbt -no-colors cli/installBin`;
  `./bench.sh effect-oneshot --backend ssc` = 0.025 ms/iter; `./bench.sh effect-oneshot --backend ssc-asm`
  = 0.032 ms/iter (was 9.46 ms/iter in the accepted repro).

## rust-foreach-list-realloc — `fixed` (2026-06-21, `abbc98eee`)

- **Found by:** benchmark perf-divergence sweep (`./bench.sh`), accepted from `SPRINT.md`.
- **Symptom:** Rust codegen re-inlines a top-level collection `val` at each use site instead of referencing
  the `let` binding emitted in each def preamble. In hot loops this rebuilds the whole `vec![...]` every
  iteration: `pattern-match-heavy` emits `for s in vec![Circle { .. }, Rect { .. }, ..].iter().cloned()`
  inside `while i < 100000`, leaving the preamble `let shapes = vec![...]` dead. `list-fold` has the same
  shape for `xs`.
- **Repro:** inspect generated Rust for `pattern-match-heavy` / `list-fold` with the real Rust emitter, then
  run `./bench.sh pattern-match-heavy list-fold --backend rust`.
- **FIXED (2026-06-21):** `RustCodeWalk` now references top-level vals by their generated `let` binding
  instead of re-inlining the initializer at every use site, and only injects a top-val preamble into defs
  that actually reference it. `collectMultiUse` also stops counting lambda/def parameter binders as reads,
  removing the spurious `area(s.clone())` for a single-use foreach parameter. Guard:
  `RustGenCollectionTest` asserts one `let xs = vec![...]`, `for x in xs.iter()`, no `for x in vec!`, and
  no `inc(x.clone())`. Verified emitted Rust: `area` has no dead `shapes` preamble, `workload` builds
  `shapes`/`xs` once and iterates the binding. Bench: `./bench.sh pattern-match-heavy list-fold --backend rust`
  improved `list-fold` 0.153→0.044 ms and `pattern-match-heavy` 4.16→1.37 ms.

## effect-op-trailing-comment — `fixed` (2026-06-20)

- **Found by:** busi (building the v2 KSeF inbound port `effect Ksef`).
- **Symptom:** a trailing `//` line-comment on an effect operation's declaration silently broke the
  WHOLE effect. `effect Ksef:` / `  def pull(t: String, s: String): List[String]  // FA(3) docs`
  made `Ksef` parse as a plain object, so every `Ksef.pull(...)` perform threw
  `No method 'pull' on InstanceV(Ksef)` at runtime (the handler never caught it). Root: `preprocessEffects`
  appended the synthetic `= __effectOp__` body at the absolute end of the op line, so a trailing comment
  swallowed it → the op had no body → not an effect op. The same `!bodyLine.contains("=")` guard also
  wrongly skipped an op whose param had a function type (`f: Int => Int`).
- **FIXED (2026-06-20):** `preprocessEffects` now splits off any trailing line-comment first
  (`splitLineComment`, string-literal aware) and inserts `= __effectOp__` into the CODE part, before the
  comment; the "already has a body" check ignores `=>`. Guard: `PreprocessEffectsTest` (7 cases). 53
  existing effect/parser tests green; real-harness repro now returns the handler's value, not a throw.

## jsgen-module-section-scope — `fixed` (2026-06-22)

- **Found by:** busi (deep-offline browser bundle) — the #1 raw `emit-js` full-bundle blocker codex recorded in `src/v2/specs/lf-1-browser-bundle.md` ("importing `std/money.ssc` fails at runtime with `Currency` not initialized before `defaultCurrencies`").
- **Symptom:** any program that `emit-js`'d a markdown module split across sections (e.g. `std/money`, whose `Currency`/`Money` constructors are under one heading and `defaultCurrencies`/`currencyOf` under another) threw on Node — `ReferenceError: Currency is not defined`, or (when reached via the import binding) `not callable: ()`. `SSC_JIT_BACKEND=js` (the JIT path) was fine; only raw `emit-js`/`run-js` failed.
- **Root cause:** each module section is emitted by a *separate* child `JsGen` sharing `topLevelConsts`; the first declares `const std = (()=>{ const money = (()=>{ function Currency… })(); … })()` and later sections merge via `_ssc_mergeDeep(std, (()=>{ const money = (()=>{ … defaultCurrencies = … Currency … })(); … })())`. Each section's IIFE is its own lexical scope, so a later section's bare reference to an earlier section's `Currency` had nothing to resolve to — even though `std.money.Currency` existed at runtime.
- **FIXED (2026-06-22):** a shared `namespaceMembers: Map[path, Set[name]]` (threaded to child gens like `topLevelConsts`) records the members each section declares per namespace path. When emitting a section, `genObjectAsExpr(d, path)` prepends `const { <prior members not declared here> } = <path>;` (e.g. `const { Currency, Money } = std.money;`) so cross-section references resolve from the live, already-merged namespace. `mergeDeep` is unchanged. Keeps the JIT path identical.
- **Guard:** `tests/conformance/money-multisection.ssc` (+ `expected/money-multisection.txt`) — imports `std/money`, calls `currencyOf` (which reaches `Currency` via `defaultCurrencies` and via its `getOrElse` fallback); runs identically on INT/JS/JVM. `MoneyCrossBackendTest` "money.ssc — JS output matches the interpreter" + busi `make v2-test-js` (full v2 core on JS) stay green.

## collection-ctor-aliases — `fixed` (2026-06-15)

- **Found by:** a collections survey (prompted by a "do we only have List/Map?" question).
- **Symptom:** despite the user guide listing `Seq`/`List`/`Vector`/`Set`/`Array`/`Map`, the interpreter only had `List`/`Map`/`Set` companions — `Seq(1,2,3)`, `Vector(...)`, `Array(...)`, `IndexedSeq(...)` all threw `Undefined: Seq` (etc.); `.toVector`/`.toSeq`/`.toIndexedSeq` and `Map.toSeq` were also missing. (JVM, real Scala, was fine.)
- **FIXED (2026-06-15):** the interpreter backs every sequence type with a single `ListV` (JS with arrays), so `Seq`/`Vector`/`Array`/`IndexedSeq`/`Iterable`/`LazyList` companions now alias `List`'s (`BuiltinsRuntime`), JsGen emits those constructors as arrays, and `toList`/`toSeq`/`toVector`/`toIndexedSeq`/`toArray`/`toIterable` are identity conversions on List + Map (interp `dispatchList`/`dispatchMap`, JS array/Map `_dispatch`). On the **JVM backend** each stays its REAL Scala type (raw emit — `Vector(1,2,3)` → a real `Vector`, etc.); a guard asserts JvmGen preserves the companion call so a future change can't silently collapse them to List. Guard: `CrossBackendPropertyTest` "Seq/Vector/Array constructors + conversions cross-backend" (9 shapes incl. LazyList, interp == JS == JVM). Caveat: off-JVM these are NOT distinct runtime types (Vector/Array = List/array, `LazyList` is eager — an infinite LazyList won't work off-JVM). Available collections: List, Map, Set, Seq, Vector, Array, IndexedSeq, Iterable, LazyList, plus Option, Either, Tuple, Range.

## jsgen-enum-payload-extract — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-6 enum probes).
- **Symptom:** matching an `enum` case WITH a payload bound the wrong value on JS — `enum Shape: case Circle(r: Int); … case Circle(r) => …` bound `r` to the case's `_tag` (0/1), not the field. `area(Circle(2)) + area(Square(3))` gave `1` instead of `21`; interp + JVM correct. `genPattern`'s Extract used field NAMES from `caseClassFieldsByType` when known, else the positional `Object.values(scrut).slice(1)[i]` — but enum cases carry an extra `_tag` field, and `caseClassFieldsByType` was populated only for `Defn.Class`, not enum cases, so `slice(1)[0]` returned `_tag`.
- **FIXED (2026-06-15):** `caseClassFieldsInModule` now also indexes `Defn.Enum` cases (name → field list), so enum-case Extract binds by field name. Guard: `CrossBackendPropertyTest` "enum payload, collect, Option.fold cross-backend" (enum-payload-match + enum-nullary).

## interp-collect-partial / jsgen-collect-partial — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-6 collection probes).
- **Symptom:** `xs.collect { case x if x % 2 == 0 => x * 10 }` (a partial function with a guard) threw `Match failure: 1` in the INTERPRETER (it called the PF as a total function), and on JS threw `Method not found: collect` (no `collect` in the array `_dispatch`); JVM correct. `collect` must SKIP elements the PF isn't defined on.
- **FIXED (2026-06-15):** interp — a `collectStep` helper catches the located "Match failure" and skips (reusing the existing `None`-skip path). JS — added a `collect` array-dispatch case that calls the element fn and skips when it throws a "Match failure" (the emitted PF closure's no-match error). Guard: `CrossBackendPropertyTest` collect-guard.

## jsgen-option-fold-curried — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-6 Option probes).
- **Symptom:** `Some(5).fold(0)(x => x * 2)` failed on JS — the curried `Option.fold(ifEmpty)(f)` was absent from the `_Some`/`_None` dispatch (only `Either.fold(fa, fb)` uncurried was present). interp + JVM correct.
- **FIXED (2026-06-15):** added `fold` to the JS Option dispatch — `_Some`: `(f) => f(value)`, `_None`: `(f) => ifEmpty` — handling the curried second clause. Also added `exists`/`forall` and fixed `Some.contains` to use structural `_eq`. Guard: `CrossBackendPropertyTest` option-fold-some/-none.

## xbackend-range-by-step — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-6/7).
- **Symptom:** `(0 to 10 by 2)` — a Range with a `by` step — threw on interp (`No method 'by' on List`) and on JS; JVM correct. interp + JS materialize a Range as a List/array, which had no `by`; the JS `by` infix also fell to an invalid `(range by step)` emission.
- **FIXED (2026-06-15):** `by(step)` keeps every step-th element of the materialized range — added to interp `dispatchList`/`dispatchList1` and the JS array `_dispatch`; JsGen now emits the `by` infix as `_dispatch(range, 'by', [step])`. Guard: `CrossBackendPropertyTest` "ranges, collection + string method gaps cross-backend" (range-by-sum/-until).

## jsgen-collection-method-gaps / jsgen-string-padto — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-7).
- **Symptom:** several stdlib methods were missing from the JS `_dispatch` (and one from interp), failing with `Method not found` / `No method`: `List.scanLeft`/`scanRight`/`indexWhere`, tuple `.swap`, `String.padTo`; interp also lacked `indexWhere`. interp + JVM (or JVM alone) were correct.
- **FIXED (2026-06-15):** added JS array dispatch `scanLeft`(curried)/`scanRight`/`indexWhere`/`swap`, JS string `padTo` (Char arg arrives as a char-code number), and interp `indexWhere` (`dispatchList`). Guard: `CrossBackendPropertyTest` string-pad / list-scanleft / list-indexwhere / tuple-swap.

## interp-js-string-map-nonchar — `fixed (interp + js)`

- **Found by:** `CrossBackendPropertyTest` (wave-7).
- **Symptom:** `"abc".map(c => c.toInt).sum` threw (`No method 'sum'`) on interp + JS — mapping a String's chars to a NON-Char value should yield a `Seq[Int]` (then `.sum`), but interp/JS `String.map` rebuild a String. JVM correct (294).
- **FIXED (interp, 2026-06-15):** `String.map` returns a `String` only when EVERY mapped element is a `Char` (interp has a real `CharV`); otherwise a `List` (`strMapResult`). `"abc".map(_.toInt)` → `List(97,98,99)`; char-to-char maps stay Strings.
- **FIXED (JS, 2026-06-21):** added a JS Char wrapper. A char produced by iterating a String (`map`/`filter`/`foreach`/`flatMap`/`charAt`/`head`/`last`/`toList`/`forall`/`exists`/`count`) is now boxed as a `_Char(code)` (`JsRuntimePart2a`): `valueOf` returns the code point and `toString` the 1-char string, so concatenation/arithmetic/`_show` coerce naturally. `_dispatch` gains a `_Char` branch mirroring the interp's `dispatchChar` (`toInt`→code point, `isDigit`/`isLetter`/`toUpper`/`asDigit`/…), and `String.map` now returns a String only when every result is a `_Char` (else a Seq) — mirroring `strMapResult`. `_eq` bridges `_Char` to a 1-char String literal and to an Int (the interp allows `CharV == IntV`), so `c == 'a'` and predicates work even though char *literals* stay JS strings. Verified: interp == JS == JVM on `"abc".map(_.toInt).sum` (294) and char-method map/filter; `CrossBackendPropertyTest` "String.map char vs non-char cross-backend" now asserts all three agree.
- **Residual (minor, by design):** a char *literal*'s `.toInt` (`'5'.toInt` → 5 on JS vs 53 on interp/JVM) still diverges — char literals stay JS strings to avoid touching literal-pattern codegen (which compares with `===`, not `_eq`). The actionable bug (`String.map(nonChar)` + iterated-`Char` methods) is closed; literal coercion is left as a separate, lower-value follow-up.

## jvmgen-autooutput-after-classdef — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 case-class probes).
- **Symptom:** a JVM program with a top-level `case class` (or trait/object) followed by ANY auto-output/expression statement printed NOTHING — `case class P(x: Int)\nprintln(if P(1) == P(1) then 10 else 0)` produced empty output; interp + JS correct. `wrapAutoOutput` emitted a bare `{ … }` block, and `case class P(x: Int)` on one line followed by `{ … }` on the next is parsed by Scala as **P's body template**, so the statement was swallowed (never run).
- **FIXED (2026-06-15):** `wrapAutoOutput` now emits `locally { … }` (an unambiguous method call) instead of a bare `{ … }`, so the block can't attach to a preceding definition. Guard: `CrossBackendPropertyTest` "collections, case-class equality, num+string cross-backend" (caseclass-eq/-ne/-output).

## jsgen-structural-equality — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 case-class probes).
- **Symptom:** `==` on the JS backend used JS reference equality (`===`), so two structurally-equal case-class instances / tuples / Lists compared unequal — `P(1) == P(1)` → `false`; interp + JVM correct.
- **FIXED (2026-06-15):** added a `_eq(a, b)` deep-structural-equality runtime helper (arrays elementwise, objects by `_type` + own keys, primitives by `===`) and routed `_arith('==' / '!=', …)` through it. Also used for Set dedup. Guard: `CrossBackendPropertyTest` caseclass-eq/-ne, tuple-eq.

## jsgen-set-constructor — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 Set probes).
- **Symptom:** `Set(1, 2, 3)` failed on JS with `TypeError: Constructor Set requires 'new'` — JsGen had `Map`/`List` constructor cases but no `Set`, so `Set(...)` fell through to the JS global `Set`.
- **FIXED (2026-06-15):** added a `Set(...)` / `Set[T](...)` case emitting `_setOf(...)` — a runtime helper that builds a structurally-deduplicated array, so the existing array `_dispatch` methods (`size`/`toList`/`sorted`/`contains`/…) apply. Guard: `CrossBackendPropertyTest` set-dedup-ops, set-contains.

## interp-num-string-concat — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-5 Map probes).
- **Symptom:** `6 + "_"` (a number `+` a String — Scala's `any2stringadd`) threw in the interpreter (`No method '+' on IntV`); JS + JVM correct. interp's `Int + …` only handled numeric operands.
- **FIXED (2026-06-15):** `dispatchInt` / `dispatchInt1` now concatenate when the `+` operand is a `StringV` (`n.toString + s`). Guard: `CrossBackendPropertyTest` num-string-concat.

## js-supertype-typetest — `fixed` (2026-06-15)

- **Found by:** busi (UI session). A `cardWithHeader(header)` card title rendered on **no**
  screen in the SPA — money, compliance, and the new UA ФОП cockpit alike — while the card
  body rendered fine and the interpreter (`ssc render`) was correct, so every `.ssc` test
  passed. Browser DOM inspection showed the card-header `<div>` absent; the page heading
  (`thView(2,…)`) and standalone section headings (`thView(3,…)` in a vstack) rendered.
- **Symptom:** on the **JS backend**, a type-test against a supertype — sealed trait /
  parent enum / abstract class — never matches a subtype instance. `sealed trait TkNode;
  case class HeadingNode(t) extends TkNode; (x: Any) match { case h: TkNode => … }` skips the
  `TkNode` arm for a `HeadingNode`. Emitted objects carry only their leaf `_type`
  (`{_type:'HeadingNode'}`); `JsGenCpsCodegen.genPattern`'s `Pat.Typed` branch emitted an
  exact `scrut._type === 'TkNode'` check, which a subtype never satisfies. `cardWithHeader`
  lowers `header match { case h: TkNode => render; case _ => [] }` (header field typed `Any`),
  so the title fell to the empty wildcard. The JS analogue of the interp/JIT fix for #1/#3.
- **FIXED — single-module (commit 775a10e68):** scanned type decls + `extends` into
  `supertypeName → Set[concrete leaf _type]` per module; `genPattern`'s `Pat.Typed` widens a
  no-tag (supertype) check to an `_type` OR over that closure. Guard `SupertypeTypeTestJsTest`.
- **FIXED — cross-module (follow-up):** the first commit was insufficient for the actual busi
  case and the single-module test gave **false confidence**. The JS backend emits each imported
  module with a *fresh child `JsGen`* (genImport), and `TkNode` + subtypes live in `nodes.ssc`
  (a `package:` module) while `case h: TkNode` lives in `lower.ssc` — so the importer's matcher
  had no record of the subtype graph and still fell back to the broken exact check (browser
  re-verify after the rebuild still showed dropped titles + `_type === 'TkNode'` in the emitted
  SPA). Fix: accumulate the subtype edges ACROSS imports — `collectSubtypeEdgesFromModule`
  (descends into `package:` wrapping objects) + `recomputeSubtypeClosure`, folded in for the
  entry module and, in genImport, for each imported module + propagated into the child gen
  (mirrors `importedParamOrder`). Guard `SupertypeTypeTestXModuleJsTest` (multi-file: imported
  `package:` trait/subtypes + transitive enum across the import boundary) — the multi-file test
  the `bugs` rule requires. Spec `specs/js-supertype-typetest.md`.
- **Repro:**
  ```scalascript
  sealed trait TkNode
  case class HeadingNode(text: String) extends TkNode
  def isTk(x: Any): String = x match
    case h: TkNode => "tk"
    case _         => "other"
  println(isTk(HeadingNode("hi")))  // interp/JVM: "tk" ; JS (buggy): "other"
  ```

## jsgen-collection-dispatch-gaps — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-4 collection-HOF probes).
- **Symptom:** `xs.sortWith((a,b) => a < b)`, `xs.sorted`, `xs.partition(p)` fail on the JS backend (node) — they were simply MISSING from the `_dispatch` runtime method table (`JsRuntimePart2b.scala`); interp + JVM correct. `val (a, b) = xs.partition(…)` then also failed for lack of `partition`.
- **FIXED (2026-06-15):** added `sortWith` (`lt(a,b)?-1:lt(b,a)?1:0`), `sorted`, `partition` (→ `[yes, no]`), and `span` to the JS `_dispatch` array-method table. The `val (a, b) = …` tuple destructuring already works (`genPatDestructure`). Guard: `CrossBackendPropertyTest` "collection HOFs and pattern matching cross-backend".

## jsgen-match-guard-bind — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-4 pattern-match probes).
- **Symptom:** a `match` with a case GUARD (`case x if x < 0 => …`) fails on the JS backend (node syntax error); interp + JVM correct. `genMatchAsStmts` and the coroutine `genGenStmt` match dropped `c.cond` entirely, so a guarded `case x if …` got pattern-cond `"true"` and was treated as a catch-all mid-chain → malformed `{ … } else if (…)` JS. (`genReceiveMatcher` ANDed the guard but evaluated it with the pattern bindings out of scope.)
- **FIXED (2026-06-15):** all three JS match paths now fold the guard into the arm condition via an IIFE that scopes the pattern bindings: `(cond) && (() => { <bindings>; return (<guard>); })()`. Guarded arms are no longer mistaken for catch-alls (the switch fast-path also excludes them since the cond is no longer `"true"`). Guard: `CrossBackendPropertyTest` "collection HOFs and pattern matching cross-backend" (match-guard-bind shape).

## interp-monadic-forcomp — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (wave-4 comprehension probes).
- **Symptom:** a `for`-comprehension over `Option` / `Either` (non-`List` monad) threw **in the interpreter**; JS + JVM were correct.
  - `for x <- Some(3); y <- Some(4) yield x + y` → interp `No method 'getOrElse' on List` (interp desugared the Option for-comp as a List op → result was a `List`, not an `Option`).
  - `for x <- Right(3); y <- Right(4) yield x * y` → interp `Cannot iterate over Right(3)`.
- **FIXED (2026-06-15):** made `PatternRuntime.evalForYield` monad-polymorphic. When a generator's evaluated value is NOT a `ListV` (and the pattern is irrefutable + the tail is all simple generators), it desugars to `recv.flatMap(pat => <rest>)` / `recv.map(pat => body)` dispatched on the actual value via `DispatchRuntime.dispatch1` + a `NativeFnV` closure — exactly what the JS/JVM backends emit. `List` keeps its allocation-light fast path; guards / refutable patterns over a non-List monad fall through unchanged. Guard: `CrossBackendPropertyTest` "monadic for-comprehension cross-backend" (option some/none, either right/left, single-generator, + a List regression — interp == JS == JVM).

## xbackend-wave4-jvm-transient — `wontfix` (2026-06-15, not reproduced)

- Two wave-4 shapes (`xs.zip(ys).map((a,b)=>a+b).sum`, `(1,(2,3)) match { case (a,(b,c)) => … }`) reported a JVM `scala-cli failed` ONCE, but did NOT reproduce on a clean re-run (interp == JS == JVM all green). The original failure coincided with two contending `sbt`/`scala-cli` processes corrupting temp compiles. Kept as cross-backend guards in "collection HOFs and pattern matching cross-backend"; no code change.

## jvmgen-js-curried-partial — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (main-path edge-case probes).
- **Symptom:** PARTIAL application of a curried def fails on the **JS backend** (`not callable: NaN`); interp + JVM are correct. `def add(a: Int)(b: Int) = a + b; val f = add(3); f(4)` — JsGen flattens curried params to `function add(a, b)`, so `add(3)` runs the body with `b === undefined` → `3 + undefined` = `NaN`. FULL application `add(1)(2)` works (it arrives flattened as `add(1, 2)`); only under-applied calls break. Reproduced for 2- and 3-clause defs.
- **FIXED (2026-06-15):** added a `_curry(fn, arity, args)` JS runtime helper (accumulates args, applies when arity reached) and an auto-curry guard at the top of plain multi-clause def emission: `if (arguments.length < N) return _curry(fname, N, arguments);`. Only emitted for multi-clause defs with no defaults / using / context-bounds; single-clause defs and full applications are unaffected (arity already reached). Guard: `CrossBackendPropertyTest` "curried partial application cross-backend" (2-/3-clause, full + partial, interp == JS == JVM).

## effect-perform-in-fordo — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (effects-in-HOF/loop probes).
- **Symptom:** an effect op performed inside a `for i <- 0 until n do …` loop diverged across all three backends. interp was CORRECT; **JVM** failed scala-cli (`None of the overloaded alternatives of method + in class Int` — `acc + Counter.tick()` where `tick()` is the Any `_perform`), and **JS** printed garbage (`0[object Object][object Object]…`). The `while`-loop form of the same program works on all backends (dedicated CPS while-trampoline); the `for … do` → `foreach(i => …)` desugar did NOT CPS-thread the effect in the closure body. `.map` / `.foldLeft` closures DO thread effects — only `foreach`-from-`for-do` was broken.
- **FIXED (2026-06-15):** added for-do recognizers to BOTH CPS emitters (`JvmGenCpsTransform.emitCpsExpr` + `JsGenCpsCodegen.genCpsExpr`) that desugar to the same while-trampoline the `while` form uses, so the body's `perform`s thread through `_bind`:
  - **Range** `for i <- (lo until/to hi) do body` → index `var`/`let` + trampoline (covers `until` exclusive + `to` inclusive + bodies reading the loop var).
  - **Collection** `for x <- coll do body` (pure non-Range `coll`) → `.iterator` (JVM) / array-index (JS) + trampoline.
  Multi-generator / guarded / complex-pattern for-do falls through to the existing (raw / `_forEach`) path unchanged. Guard: `CrossBackendPropertyTest` "effect perform in for-do loop cross-backend" — 5 shapes (range until/to/loop-var + collection elem/side-effect), interp == JS == JVM.
- **Repro:**
  ```scalascript
  effect Counter:
    def tick(): Int
  def prog(): Int ! Counter =
    var acc = 0
    for i <- 0 until 3 do
      acc = acc + Counter.tick()
    acc
  println(handle(prog()) { case Counter.tick(resume) => resume(5) })  // interp: 15 ; jvm: COMPILE ERROR ; js: garbage
  ```

## jvmgen-handle-result-mainpath — `fixed` (2026-06-15, all contexts incl. Any-taint propagation)

- **Found by:** `CrossBackendPropertyTest` (effect-result × main-path composition probes).
- **Symptom:** a `val r = handle(...)` (Any-typed `_handle` result) used in a NON-arithmetic main-path
  context fails JVM scala-cli; interp + JS run it fine. A cluster of related JVM-only divergences:
  - `r match { case _ => r * 2 }` → `value * is not a member of Any` (`emitExprDeep` had no `Term.Match` case → arm fell to `.syntax`).
  - `if r > 5 then r * 10 else 0` → `Found Any / Required Boolean` (the `_binOp(">", r, 5)` cond wasn't cast to Boolean).
  - `dbl(r)` (user fn) → `Found Any / Required Int` (main-path call didn't cast the arg to the callee param type; only the CPS path did).
- **FIXED (2026-06-15):** in `emitExprDeep` — added a `Term.If` Boolean cast when the cond is an Any-typed handle-result comparison, a `Term.Match` case that recurses scrutinee + arm bodies + guards, and a `Term.Tuple` case; cast main-path call args that reference a handle-result val to the callee's `calleeParamType` (reusing the CPS `localDefSigs`/`depDefs` index). Routed any term that references a handle-result val through `emitExprDeep` via a new `termRefsHandleResultVal` in `termNeedsCustomEmit`. Guard: `CrossBackendPropertyTest` "effect-result main-path composition cross-backend" (match / if-cmp / fn-arg / multishot-arith / nested-handles — interp == JS == JVM).
- **ALSO FIXED (2026-06-15, Any-taint propagation):** the two formerly-deferred contexts:
  - `List(r, r).sum` → `No given Numeric[Any]` — broadened the `emitExprDeep` `_anyCall0` Select routing from "qual IS a handle-result-val Name" to "qual REFERENCES one" (`termRefsHandleResultVal(qual)`), so `List(r, r).sum` → `_anyCall0(List(r, r), "sum")`.
  - tuple-accessor arithmetic `val t = (r, r+1); t._1 + t._2` — added `anyTypedVals`, a superset of `handleResultVals` populated by Any-taint PROPAGATION: an untyped val whose rhs references an Any-typed val (`val t = (r, r+1)`) is itself Any-typed. The routing predicates now key off `anyTypedVals`, and the arith-operand check also recognizes `Select(anyTypedVal, _)` (so `t._1 + t._2` lowers to `_binOp`). Only ever non-empty for effect programs (seeded by `handleResultVals`), so pure code is unaffected. Guard: `result-in-list-sum` + `result-in-tuple` added to the composition test (interp == JS == JVM).

## agent-streaming-test-port-collision — `fixed` (2026-06-15, 26dae7699)

- **Found by:** codex during `rozum-agent-endpoint-pool` regression check.
- **SHA at filing:** `2334d0be4` (feature worktree).
- **Symptom:** running the sync and streaming agent SDK suites in the order
  `AgentSdkInterpreterTest AgentSdkStreamingInterpreterTest` aborts the streaming
  suite with `java.net.BindException: Address already in use`.
- **Repro:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest"`.
- **Root cause:** `examples/rozum-agent.ssc` binds `19694`, the same port as
  `AgentSdkStreamingInterpreterTest`; when the sync suite ran first, the
  streaming suite could immediately rebind the same port and abort.
- **Fix:** moved `AgentSdkStreamingInterpreterTest` to port `19698`.
- **Verify:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest"` — 14 tests passed in the formerly failing order.



---

## jvmgen-handle-result-arith — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` (new effect-composition shapes — handle result fed into main-path arithmetic).
- **Symptom:** using a `val` bound to `handle(...)` as an operand of an arithmetic/comparison infix fails JVM scala-cli with `value * is not a member of Any`. `handle(...)` lowers to `_handle(...)` (returns `Any`), so `val r = handle(...){…}; println(r * 2 + base)` emits `r * 2` raw on the Any-typed result, which Scala 3 rejects. interp + JS run it fine.
- **Repro:** a one-shot effect program ending `val r = handle(loop(n)){ case Counter.tick(resume) => resume(k) }; println(r * 2 + base)` (or two results: `println(r1 + r2)`).
- **Root cause:** `termNeedsCustomEmit` only routed a handle-result-val through `emitExprDeep` (where `ApplyInfix` lowers `+ - * / % < > <= >=` to `_binOp`) when the val appeared in a 0-arg method `Select` (`termContainsHandleResultCall`), NOT when it appeared as an arithmetic operand — so `r * 2` fell to `emitExpr`'s `.syntax` raw fallback.
- **FIXED:** added `termContainsHandleResultArith` (walks for a handle-result-val `Term.Name` used as an operand of an arithmetic/comparison `ApplyInfix`) and wired it into `termNeedsCustomEmit`; the existing `emitExprDeep` `ApplyInfix` → `_binOp` path then lowers it (nested arith re-fires the predicate via `emitCallArg`→`emitExpr`). Guard: `CrossBackendPropertyTest` effect sub-shapes 8 (`r*2+base`) and 9 (`r1+r2`), run through scala-cli on seeds 11/47 and 155/191. Property test green.

---

## interp-returnclause-effect-in-while — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` diagnostic (return-clause shape localization).
- **Symptom:** a deep return-clause handler over a program that performs an effect inside a `while` loop threw **in the interpreter** with `Unhandled effect: Log.emit (no handler in scope)`, even for a single iteration. **JS and JVM both produce the correct result.** This made the property test's case-7 (return-clause) shape vacuous: interp threw → seed skipped → JS/JVM never compared.
- **Repro:**
  ```scalascript
  effect Log:
    def emit(): Int
  def prog(): Int ! Log =
    var i = 0
    while i < 3 do
      Log.emit()
      i = i + 1
    0
  val xs = handle(prog()) {
    case Log.emit(resume) => 7 :: resume(())
    case Return(_) => List()
  }
  println(xs.length)   // js/jvm: 3 ; interp: THROWS
  ```
- **Root cause:** the handler body `7 :: resume(())` is NOT a clean tail-resume, so `evalHandle` installs no inline resolver for `Log.emit`. The op then has to thread as a `Computation` (Perform/FlatMap) through `handleInterp`, but the fast-while path (`tryFastWhileAssign`, `EvalRuntime.scala`) drove the loop's leading applies eagerly via `Computation.run`, so the `Perform` escaped the handler. A direct (non-loop) emit works; only the while-loop shape failed.
- **FIXED (2026-06-15):** captured `EffectAnalysis.effectOps` into `Interpreter.effectOpNames` (alongside `multiShotEffects`) at module init, and added an up-front guard `whileBodyHasUnresolvedEffect` at the top of `tryFastWhileAssign`: if the loop body performs an effect op with NO active inline resolver (`EffectsRuntime.lookupResolver(eff, op) == null`), bail (return null) to the monadic trampoline, which threads effects via `FlatMap`. The one-shot tail-resume fast path keeps a live resolver, so the guard returns false for it and the fast/JIT path is preserved (no perf regression — `EffectVmContinuationsTest` / `EffectOneShotFastPathTest` stay green). Guard: `CrossBackendPropertyTest` "effect return-clause cross-backend (… / while)" now runs the while shape interp == JS == JVM, and the generated JVM differential rose from 17 → 19 checked seeds (the formerly-skipped return-clause seeds 23/59 now produce an interp baseline). 366 effect/JIT/VM tests green.

---

## jvmgen-returnclause-effect-in-recursion — `fixed` (2026-06-15)

- **Found by:** `CrossBackendPropertyTest` diagnostic (return-clause shape localization).
- **Symptom:** a return-clause handler over a **recursive** effectful function fails JVM scala-cli compilation: `Found: (_t3 : Any) / Required: Int`. **interp and JS both produce the correct result.**
- **Repro:**
  ```scalascript
  effect Log:
    def emit(): Int
  def go(n: Int): Int ! Log =
    if n <= 0 then 0
    else
      Log.emit()
      go(n - 1)
  def prog(): Int ! Log =
    go(3)
  val xs = handle(prog()) {
    case Log.emit(resume) => 7 :: resume(())
    case Return(_) => List()
  }
  println(xs.length)   // interp/js: 3 ; jvm: scala-cli COMPILE ERROR
  ```
- **Root cause:** the CPS transform emits `def go(n: Int): Any = _bind(..., (_t3: Any) => go(_t3))` — the recursive call passes the Any-typed `_bind` continuation result `_t3` to `go`, whose param stays declared `Int`. The existing `applyCalleeCasts` (which casts CPS call args to the callee's declared param types) only consulted `depDefs`/`depClasses` (IMPORTED deps), never the user module's own defs, so a recursive/sibling call got no cast. (Widening the param to `Any` is NOT a valid fix — params keep their declared type so field access like `node.nodes` type-checks; the design casts at call sites instead.)
- **FIXED (2026-06-15):** added `localDefSigs` — a pre-pass index of the user module's own `Defn.Def`s — and made `applyCalleeCasts` / `calleeParamType` / `calleeTypeArgMap` consult it as a fallback after `depDefs`. `go(_t3)` now emits `go(_t3.asInstanceOf[Int])`. Guard: `CrossBackendPropertyTest` "effect return-clause cross-backend (direct / recursion)" (interp == JS == JVM). 120 effect/CPS unit tests stay green.

---

## jvmgen-multishot-handle-result-any — `fixed` (2026-06-15, 23a33c976)

- **Found by:** `CrossBackendPropertyTest` (its multi-shot effect shape).
- **Symptom:** a method call on the result of `handle(...)` fails JVM scala-cli with e.g. `value sum is not a member of Any` — `handle(...)` lowers to `_handle(...)` which returns `Any`, so `val all = handle(prog()){…}; all.sum` (typical for a multi-shot handler whose result is a `List`) doesn't type-check. interp + JS (dynamically typed) run it fine.
- **Repro:** a `multi effect NonDet` program ending `val all = handle(prog()){ case NonDet.choose(opts, resume) => opts.flatMap(o => resume(o)) }; println(all.sum)`.
- **Severity / why deferred at filing:** harder than the emitCaseBody class — it is about the `_handle` RESULT type (Any), not the handler body. A real fix needed the codegen to know the handled-program's result type (here `List[Int]`) and cast, or `_handle` to be generically typed; `List[Any].sum` would still need `Numeric[Any]`.
- **FIXED (23a33c976):** runtime `_anyCall0(recv, m)` dynamically dispatches 0-arg collection methods on an Any Iterable (numeric folds via `_binOp`); codegen tracks vals bound to `handle(...)` (`handleResultVals`), routes a `x.method` on them through `emitExprDeep` (via `termContainsHandleResultCall` in `termNeedsCustomEmit`) → `_anyCall0`. Property test re-added the multi-shot `all.{sum,max,min,length}` shape as the guard; 96 tests green.
---

## jvmgen-effect-handler-arg-arith — `fixed` (2026-06-15, 7c843b121)

- **Found by:** `CrossBackendPropertyTest` (its broadened multi-arg / arithmetic effect handlers).
- **Symptom:** a handler that does arithmetic on op-args, e.g. `case Combine.mix(a, b, resume) =>
  resume(a * b + 1)`, fails JVM scala-cli with `value * is not a member of Any`. The op-args are
  bound `val a = _args(0)` (type `Any`) and `emitCaseBody` had no arithmetic case → `a * b`
  emitted raw, which Scala 3 rejects on `Any`. interp + JS run it fine.
- **Repro:** `println(handle(loop(5)) { case Combine.mix(a, b, resume) => resume(a * b + 1) })`
  for `effect Combine: def mix(a: Int, b: Int): Int` → scala-cli "value * is not a member of Any".
- **FIXED (7c843b121):** `emitCaseBody` now lowers an arithmetic/comparison `ApplyInfix` to the
  `_binOp("op", l, r)` runtime helper (same as `emitExpr` for Any operands; mirrors the existing
  `::` Any-cast case). Guard: `CrossBackendPropertyTest` effect shapes (arg-carrying / two-op) run
  through scala-cli. 101 effect+jvmgen tests green.
- **ALSO FIXED (78d1ce178) — control-flow case:** an `if` in a handler body with a comparison on Any-typed op-args (`if k > 2 then resume(k) else resume(0)`) — `emitCaseBody` had no `Term.If` case so `k > 2` emitted raw. Added a `Term.If` case that recurses (lowers `k > 2` to `_binOp`) + casts the condition to `Boolean`. Property test gained a conditional-resume effect shape (run through scala-cli).

---

## jvmgen-handle-in-arg-position — `fixed` (2026-06-15, 91fc574f5)

- **Found by:** `CrossBackendPropertyTest` (xbackend-property-equivalence — the generated
  cross-backend differential, found this on its first effects run).
- **Symptom:** JVM codegen emits a `handle(...)` effect expression RAW (unqualified) when it
  appears in **call-argument position**, e.g. `println(handle(body){cases})`, so scala-cli fails
  with `Not found: handle - did you mean _handle?`. interp **and** JS run it correctly.
- **Works (idiomatic):** binding the result first — `val r = handle(body){cases}; println(r)` —
  lowers correctly to `_handle(() => body, Set(...), Map(...))`. Only the inline/nested form breaks.
- **Repro (minimal):**
  ```scalascript
  effect Counter:
    def tick(): Int
  def loop(n: Int): Int ! Counter =
    var acc = 0
    var i = 0
    while i < n do
      acc = acc + Counter.tick()
      i = i + 1
    acc
  println(handle(loop(3)) { case Counter.tick(resume) => resume(2) })
  ```
  `ssc emit-jvm` / scala-cli the output → "Not found: handle". Change last line to
  `val r = handle(loop(3)) { ... }
println(r)` → works.
- **Root cause:** `JvmGen` lowers `handle` via `emitExpr` (case `handle(body){cases}` →
  `emitHandleForm`) and special-cases the `val x = handle(...)` / statement forms, but an
  effectful term nested inside another `Term.Apply` arg falls to the `.syntax` raw fallback
  instead of recursing the arg through `emitExpr`/`emitHandleForm`. (Likely the same for other
  effectful forms — `runAsync`, etc. — as direct call args.)
- **Severity:** low — narrow corner case, trivial workaround (bind to a `val`). Fix touches the
  core CPS emission path (would need care vs the 33 JvmGenEffects tests), so deferred from the
  property-test slice that found it.
- **FIXED (91fc574f5):** `termContainsEffectExpr` (walks children for any effectful sub-expr) added to `termNeedsCustomEmit` so a `handle`/effect nested in a call arg routes through `emitExprDeep` and lowers to `_handle(...)`. Regression guard: `CrossBackendPropertyTest` effect kind uses the inline `println(handle(...))` form (interp==JS==JVM via scala-cli). 119 effect+jvmgen tests green, no regression.

---

## interp-import-cycle-stackoverflow — `fixed` (2026-06-14)

- **Reported:** busi (`@busi-claude-code`), during the busi `p5` `dispatch.ssc`
  decomposition (the facade re-export / strict-DAG work).
- **Symptom:** a true module **import cycle** (`A→B→A`, e.g. a sub-module importing
  back from the facade that imports it) aborts with a bare `java.lang.StackOverflowError`
  and **no module-resolution message** — the cause (a cycle) is invisible. Distinct
  from the FIXED `interp-module-loader-dedup` (a *diamond* is acyclic and handled by
  the cache; a *cycle* is not).
- **Repro:** 3–4 modules forming a cycle: `a` imports `b`, `b` imports `a` (or the
  facade↔leaf variant: `a` imports back from `facade`, `facade` imports `a`). Run the
  entry → `StackOverflowError`. See `runtime/.../InterpImportCycleTest.scala`.
- **Root cause:** `SectionRuntime.runImport`'s `moduleCache.getOrElseUpdate(path, …)`
  only **inserts after the thunk returns**; while a module's body is still running its
  path is absent from the cache, so a cyclic re-import re-runs it → unbounded recursion.
- **Fix:** a shared, insertion-ordered `moduleLoading: LinkedHashSet[os.Path]` threaded
  into child interpreters like `moduleCache`. `runImport` checks it **before**
  `getOrElseUpdate` — a re-entry on a still-loading path throws
  `InterpretError("Import cycle detected: a.ssc → b.ssc → a.ssc")`; the path is added
  before the body runs and removed in a `finally`, so a later legitimate import of the
  same (finished) module is unaffected. Purely diagnostic — no semantic change for
  acyclic graphs / diamonds. Spec `specs/import-cycle-diagnostic.md`.
- **Verify:** `InterpImportCycleTest` (2-cycle + facade↔leaf cycle → legible error not
  `StackOverflowError`; acyclic re-export control still computes) + `InterpModuleDedupTest`
  green (no regression).
- **Landed:** (this branch → origin/main).

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
