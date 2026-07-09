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

## route-deriver-path-param-unit-client — `open` (2026-07-09)

- **Found by:** codex, during `tkv2-typed-client` prep.
- **Repro:** with no explicit `apiClients:` front matter, derive a client from
  `route("GET", "/api/todos/:id") { ... }` or
  `route("DELETE", "/api/todos/:id") { ... }`, then inspect
  `bin/ssc emit-js examples/derived-route-clients.ssc`. Current output warns
  `path param ':id' cannot be filled — request type is Unit` and emits methods
  such as `getApiTodosById(headers, cancelToken)`, so a browser client cannot
  pass the id.
- **Observed failure:** route-derived non-body endpoints with path parameters
  are not callable as typed browser clients; the generated method shape treats
  the first user argument as headers instead of the path value.
- **Impact:** toolkit-v2's no-manual-`apiClients:` route-derived browser client
  path is not production-safe for common detail/delete routes.
- **Fix direction:** have `RouteDeriver` use `String` for one path parameter
  and `Any` for multiple path parameters when no typed handler evidence exists;
  keep explicit `apiClients:` behavior unchanged.
- **Done-when:** RouteDeriver, JS codegen, JVM/Swing codegen, a JS-only
  conformance smoke, docs/example, and affected tests agree; fixed SHA and
  gates are recorded here.

## std-auth-webauthn-signature-drift — `fixed` (2026-07-09)

- **Found by:** codex, during `tkv2-webauthn` spec/implementation prep.
- **Repro:** compare `v1/runtime/std/auth.ssc` declarations with the existing
  implementations in `v1/runtime/std/auth-plugin/.../AuthIntrinsics.scala` and
  `v1/runtime/backend/js/.../JsRuntimeWebAuthn.scala`, or run the existing
  `tests/conformance/webauthn-server-verify.ssc` / `examples/webauthn-demo.ssc`
  call shapes. The implementations and shipped examples use:
  `webauthnStoreFind(userId, credentialId)`,
  `webauthnUpdateSignCount(userId, credentialId, newSignCount)`,
  `webauthnVerifyRegistration(clientDataJSONb64, attestationObjectB64, expectedOrigin)`,
  and `webauthnVerifyAssertion(clientDataJSONb64, authenticatorDataB64,
  signatureB64, credentialIdB64, expectedOrigin)`.
- **Observed failure:** the public std declarations still document older/wrong
  arities and return types for those four WebAuthn helpers, so new user code
  can be guided into calls the runtime does not implement.
- **Impact:** WebAuthn is production-sensitive; browser-client helpers would be
  confusing if the adjacent server verifier declarations stay stale.
- **Fix direction:** update `std/auth.ssc` declarations to the runtime-backed
  arities/return shapes without changing the verifier semantics, then keep
  `webauthn-server-verify` green on INT+JS.
- **Root cause:** `std/auth.ssc` lagged behind the already-shipped JVM/JS
  WebAuthn verifier/store implementations; examples and runtime code had moved
  to user-scoped credential lookup, boolean sign-count updates, and verifier
  inputs split into browser response fields.
- **Fixed in:** `e61a89b4c` (`feat: add tkv2 webauthn browser actions`).
- **Gates:** `scripts/sbtc "backendJs/compile; frontendPlugin/compile; backendInterpreter/compile"`;
  `scripts/sbtc "backendInterpreter/testOnly scalascript.JsRuntimeWebAuthnClientTest scalascript.JsGenStdImportTest"` (43 tests);
  `tests/conformance/run.sh --only 'tkv2-webauthn,webauthn-server-verify' --no-memo`
  (2/2 cases, INT+JS pass); `emit-spa --frontend custom` smoke for
  `examples/frontend/webauthn-toolkit-demo/webauthn-toolkit-demo.ssc`.
- **Done-when:** the declaration file, examples, runtime intrinsics, and
  conformance call shapes agree; fixed SHA and gates are recorded here.

## v2-case-class-instance-methods-stub — `fixed` (2026-07-08)

- **Found by:** codex, during `p3-connectnode-node-sim` verification.
- **Repro:** after `scripts/sbtc "installBin"`, run a `.ssc` program that
  constructs a case-class value with an instance method and calls it, or run
  `bin/ssc run examples/distributed-word-count.ssc` before the local shutdown
  workaround. `cluster.close()` lowers to a runtime `Stub("Cluster.close")`
  instead of executing the case-class method body.
- **Observed failure:** v2 registers case-class fields and can read them, but
  methods defined inside `case class ...:` are not emitted as callable closures.
  Runtime method dispatch therefore falls through from field lookup to a
  `Stub(Tag.method)` value.
- **Impact:** std APIs that rely on case-class instance methods are not fully
  production-safe on the default v2 lane. `p3-connectnode-node-sim` avoids this
  in the distributed examples by sending `ShutdownWorker()` directly; the
  language/runtime gap remains.
- **Fix direction:** extend `v2/frontend-bridge` lowering for case-class
  template methods, likely by reusing the existing tag-dispatched extension
  method path and binding constructor fields from the receiver before compiling
  the method body. Add a focused CLI/v2 regression for a case-class method that
  reads a constructor field and a std-facing regression for `Cluster.close`.
- **Done-when:** `cluster.close()` executes without printing a stub under the
  assembled default v2 CLI, the focused regression is green, and the fixed SHA
  is recorded here.
- **Root cause:** `FrontendBridge` registered case-class field names but ignored
  `Defn.Def` members inside case-class templates when emitting v2 CoreIR.
  Calls such as `Cluster.close()` therefore had no generated closure and fell
  through to `Stub(Tag.method)`.
- **FIXED (2026-07-08, `f12cad127`):** v2 now lowers case-class template
  methods through the existing tag-dispatched extension machinery, binding
  constructor fields from the receiver before compiling method bodies.
  `__methodOrExt__` also preserves registered `DataV` field precedence so a
  generated method name such as `name` does not hijack ordinary `.name` fields
  on other case classes. The distributed examples were restored to the public
  `cluster.close()` shutdown API.
- **Verified:** `V2CaseClassMethodCliTest` passed 3/3 through the assembled CLI;
  `V2TuplePatternCliTest` stayed 4/4 green; direct default-v2 runs of
  `distributed-word-count`, `distributed-log-aggregation`, and
  `distributed-join` passed with `cluster.close()`; affected conformance
  `cluster-connect,distributed-*` passed 6/6 and
  `data-types,lenses,optional,traversal,fn-typed-field` passed 4/4.

## v2-mapreduce-handler-registry-tuple-lookup — `fixed` (2026-07-08)

- **Found by:** codex, during `p3-connectnode-node-sim` implementation after
  adding the local loopback map-reduce worker helper.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `SSC_DEBUG_ACTORS=1 timeout 60 bin/ssc run examples/distributed-word-count.ssc`
  from the assembled CLI in the worktree. Minimal lowering repro:
  `val pair: Any = ("ada", 1); pair match { case (w: String, _: Int) => w }`
  fails under `bin/ssc run` with `unbound global: w`, while
  `bin/ssc run --v1` prints `ada`.
- **Observed failure:** the original `Cluster.connect(...)` address-string hang
  is replaced by actor deaths:
  `[actor-death] lookup(v, key)`, followed by main-task failure
  `RuntimeException: match: no arm for Exit/1`.
- **Impact:** offline distributed map-reduce examples cannot be used as a v2
  production smoke until the worker handler registry survives the real actor
  worker boundary.
- **Root cause:** the local worker path exposed several v2 lowering/library
  gaps that were previously masked by the `Cluster.connect` hang:
  typed tuple patterns did not bind names; nested tuple patterns inside
  constructor patterns such as `Some((_, found))` stayed on the fast
  non-recursive match path and lost binders; tuple `val` destructuring ignored
  wildcard field positions; unqualified `lookup(name)` inside
  `HandlerRegistry.apply` resolved to the JSON `lookup` intrinsic; top-level
  map-reduce calls were hoisted before handler registration; and std
  map-reduce relied on tuple selectors, as-patterns, `List.reduce`, and
  `List.flatMap(Option)` shapes that are not production-safe on the current v2
  lane.
- **Fix direction:** keep the map-reduce API unchanged, remove selector use from
  the std/examples path as a narrow hardening step, and fix v2 tuple
  pattern/selector lowering with a focused regression so tuple values crossing
  actor/map-reduce boundaries remain ordinary tuples.
- **Done-when:** direct `bin/ssc run examples/distributed-word-count.ssc` prints a
  non-empty result, affected conformance
  `tests/conformance/run.sh --only 'cluster-connect,distributed-*' --no-memo`
  passes, and the fixed SHA/root cause are recorded here.
- **FIXED (2026-07-08, `6c0e39559`):** added `localLoopbackCluster`, hardened
  std map-reduce against the v2 tuple/collection gaps, fixed v2 tuple pattern
  and tuple val-destructuring lowering, qualified the handler registry lookup
  self-call, blocked eager hoisting for impure map-reduce method-object calls,
  and rewired the offline distributed examples to use local workers plus
  explicit shutdown.
- **Verified:** `scripts/sbtc "cli/assembly; cli/testOnly scalascript.cli.V2TuplePatternCliTest"`
  passed 4/4; direct default-v2 runs of `distributed-word-count`,
  `distributed-log-aggregation` with a temp log, and `distributed-join` with
  temp CSVs passed; affected conformance
  `tests/conformance/run.sh --only 'cluster-connect,distributed-*' --no-memo`
  passed 6/6.

## v2-vm-effect-handlers-return-raw-op — `fixed` (2026-07-08)

- **Found by:** codex, during `p4-rust-wasm-lanes` full
  `./v2/conformance/check.sh` after list/float expectation realignment.
- **Repro:** run `./v2/conformance/check.sh`, or focus these rows:
  `examples/async-tasks.ssc0`, `examples/hm-async.hm`,
  `examples/hm-eff-multiop.hm`, and the inline `handleM row composition`
  program through `bin/mirac.ssc0` + `run-ir`.
- **Observed failure:** the VM `run`/`run-ir` lane returns unhandled effect
  values such as `Op("log", 1, <closure>)`, `Op("yield", 0, <closure>)`, and
  `Op("QA", Pair("ask", 0), <closure>)` where the JS/Rust generated lanes for
  the same typed programs produce the expected results (`List(...)` or `42`).
- **Impact:** the self-hosted v2 conformance gate remains red after the
  Rust/WASM target fixes, and production cannot treat the VM as authoritative
  for typed async / multi-op effect handler examples.
- **Fix direction:** inspect VM effect handling in `v2/src/Runtime.scala` and
  the generated CoreIR for the failing programs. Do not update expectations to
  raw `Op(...)`; the JS/Rust rows show the intended semantics still runs to a
  value.
- **Done-when:** the focused rows above and the full `./v2/conformance/check.sh`
  pass, with the fix SHA and root cause recorded here.
- **FIXED (2026-07-08, `84d7ac77f`):** VM `Let`/`Seq` auto-threading treated
  every `DataV("Op", ...)` as a runtime statement effect. That is correct for
  bridge-emitted runtime ops with dotted labels such as `Console.writeLine`, but
  wrong for pure v2/typed free-monad values such as `Op("log", ...)`,
  `Op("yield", ...)`, and `Op("QA", ...)`, which handlers/schedulers must match
  as ordinary data. `Runtime.isAutoThreadOp` now limits statement/binding
  auto-threading to dotted bridge labels, preserving free-monad Ops as values.
- **Verified:** minimal `run-ir` repro now returns `List(1)` instead of raw
  `Op("log", 1, <closure>)`; focused rows
  `async-tasks.ssc0`, `hm-async.hm`, `hm-eff-multiop.hm`, and `handleM row
  composition` pass; full `./v2/conformance/check.sh` is green.

## v2-ssc0-target-display-drift — `fixed` (2026-07-08)

- **Found by:** codex, during `p4-rust-wasm-lanes` baseline.
- **Repro:** from a fresh worktree with Rust and Node available, run
  `./v2/conformance/check.sh`.
- **Observed failure:** the v2 VM now renders proper `Cons`/`Nil` chains as
  `List(...)` and whole floats with collapsed `Writer.floatStr` output such as
  `10`, but the self-hosted JS/Rust target backends and parts of
  `v2/conformance/check.sh` still expect or emit the older `Cons(..., Nil)` /
  `10.0` shape. Representative failures:
  `js map.ssc0 vm=[List(2, 4, 6)] node=[Cons(...)]`,
  `rust map.ssc0 vm=[List(2, 4, 6)] rust=[Cons(...)]`,
  `run-ir map.coreir got [List(...)] want [Cons(...)]`, and
  `kc-float Double math got [10 ...] want [10.0 ...]`.
- **Impact:** the self-hosted v2 target gate is red even though the Scala
  `v2/backend/check.sh` CoreIR source-generator harness is green. This blocks a
  credible Rust/WASM lane gate for Phase 4.
- **Fix direction:** update `v2/lib/backend-js-gen.ssc0` and
  `v2/lib/backend-rust-gen.ssc0` display helpers to match VM `Show.show`
  semantics for proper lists, and update `v2/conformance/check.sh` expectations
  for the accepted list/float display contract. Keep WASM expectations aligned
  because `ssc0-wasm` reuses the Rust generator.
- **Done-when:** `./v2/conformance/check.sh` no longer reports list/float display
  mismatches; JS/Rust/WASM target rows compare against the VM output.
- **FIXED (2026-07-08, `84d7ac77f`):** self-hosted JS and Rust target preludes
  now render proper `Cons`/`Nil` chains as `List(...)`, matching VM `Show.show`
  and the Scala source-generator backends. `v2/conformance/check.sh` was
  rebaselined to the accepted kernel display contract for proper lists and
  collapsed whole-float display.
- **Verified:** full `./v2/conformance/check.sh`; `./v2/backend/check.sh`;
  affected conformance
  `tests/conformance/run.sh --only 'effects,effect-*,async*,direct-*,js-*-effect-*,std-functor-applicative-monad,std-foldable-traversable,std-index' --no-memo`;
  `tests/conformance/run.sh --only 'rust*,wasm*' --no-memo` has no matching
  top-level cases, so Rust/WASM coverage is the v2 gate.

## v2-ssc0-rust-float-literal-emits-int — `fixed` (2026-07-08)

- **Found by:** codex, during `p4-rust-wasm-lanes` baseline.
- **Repro:** run `./v2/conformance/check.sh` and inspect the diagnostics log.
- **Observed failure:** several Rust target rows fail at `rustc` with
  `error[E0308]: mismatched types`, because the self-hosted Rust backend emits
  collapsed whole-float literals such as `V::Fl(2)` / `V::Fl(1)` after
  `#f->str`, but Rust's `V::Fl` variant requires `f64`. Representative rows:
  `numops Rust`, `numcmp Rust`, `div Rust`, `float math Rust`, `mathx* Rust`,
  `letrec poly Rust`, `dict-passing Rust`, `dict ord Rust`.
- **Impact:** real Rust target compilation is broken for typed numeric programs
  that contain whole-valued float constants. WASM inherits this through
  `ssc0-wasm` because it compiles the same Rust source to `wasm32-wasip1`.
- **Fix direction:** normalize generated Rust float literals in
  `v2/lib/backend-rust-gen.ssc0`: whole finite values need a Rust float suffix
  or decimal (`2.0`), while `nan`/`inf`/`-inf` should map to valid Rust
  constants if they surface.
- **Done-when:** the Rust rows above compile and pass in
  `./v2/conformance/check.sh`, and the WASM quicksort/TCO gate remains green.
- **FIXED (2026-07-08, `84d7ac77f`):** the self-hosted Rust backend normalizes
  `IrFloat` literals after `#f->str`: whole finite values get a decimal
  (`2.0`), existing decimal/exponent spellings are preserved, and
  `nan`/`inf`/`-inf` map to Rust `f64` constants.
- **Verified:** full `./v2/conformance/check.sh`; Rust numeric rows including
  `hm-numops`, `hm-numcmp`, `hm-div`, mathx, rounding, dict-passing, and
  method-poly/self compile and pass; WASM quicksort and 1e6-tail-call TCO remain
  green.

## v2-run-cli-argv-not-forwarded — `fixed` (2026-07-08)

- **Found by:** codex, during `p4-js-lane-bridge` direct argv smoke.
- **Repro:** after `scripts/sbtc "installBin"`, run a temp `.ssc`:
  `println(args.length); println(args(0))`. Then compare
  `bin/ssc run-js --v2 /tmp/args.ssc one two` with
  `bin/ssc run --v2 /tmp/args.ssc one two`.
- **Observed failure:** `run-js --v2` prints `2` and `one`; `run --v2`
  prints `0` and then fails with `IndexOutOfBoundsException: 0`.
- **Impact:** the v2 VM runner has an `argv` parameter internally, and
  `PluginBridge` documents `args` as runner-provided command-line args, but
  `RunCmd` currently treats every non-flag as another source file and calls
  `RunV2.run(..., Nil)`. User code reading `args` under the default/explicit v2
  runner cannot receive program argv.
- **Fix direction:** add an explicit argv separator for `ssc run`, most likely
  `ssc run [flags] <file.ssc> -- [args...]`, so existing multi-file run
  semantics are not reinterpreted. Forward the trailing argv to `RunV2.run` and
  `RunV2.runBytecode`; keep legacy/default behavior clear in usage text.
- **Done-when:** a real assembled-CLI regression covers `run --v2 <file> --
  one two`, default v2 if applicable, and `run-js --v2` remains green.
- **FIXED (2026-07-08, `64de9b9af`):** `ssc run` now treats `--` as the
  explicit separator between source files and program argv for v2 VM runners.
  Default `ssc run <file> -- one two`, explicit `ssc run --v2 <file> -- one
  two`, and `ssc run --bytecode <file> -- one two` forward argv into
  `Runtime.argv`. The bytecode lane also gained list-application fallback parity
  so `args(0)` works through `Emit.app`.
- **Verified:** `scripts/sbtc "cli/compile; cli/assembly; cli/testOnly
  *V2RunArgvCliTest"`; `scripts/sbtc "installBin"`; direct installed CLI smokes
  for default/`--v2`/`--bytecode`; conformance `collections`; combined
  assembled-CLI smoke `*V2RunArgvCliTest *V2JsLaneCliTest`.

## root-test-cli-spark-submit-dry-run-deps — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` focused
  `scripts/sbtc "cli/test"` after the Electron fork-exit blocker was fixed.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "cli/testOnly scalascript.cli.SubmitCommandTest"`.
- **Observed failure:** `SubmitCommandTest` reports two failed assertions:
  `--dry-run prints package + submit argv with default master` no longer includes
  `org.apache.spark::spark-core:4.0.0`, and `--spark-version threads through to
  both deps` no longer includes `spark-core:3.5.1`.
- **Impact:** the CLI aggregate remains red; Spark submit dry-run output may have
  intentionally moved dependency information or stopped emitting it. The test and
  command contract must agree before root `test` can be a production gate.
- **Fix direction:** inspect `submit` dry-run output generation and the current
  intended Spark dependency surface. If deps are intentionally no longer present in
  the package argv, update the test to assert the current contract; otherwise
  restore dependency lines/options.
- **Done-when:** focused `SubmitCommandTest` is green and full `cli/test` no
  longer reports this suite.
- **FIXED (2026-07-08, `cea0c3aed`):** the dry-run contract is the generated
  package source, not inline `spark-submit --dep` argv. `SubmitCommandTest` now
  parses the `# source:` path from dry-run output and asserts Spark dependency
  directives in that generated source.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.SubmitCommandTest"`;
  full `scripts/sbtc "cli/test"` (554 succeeded, 29 canceled, 0 failed);
  bounded root `scripts/sbtc "test"` (elapsed 1668s, success).

## root-test-cli-toolkit-electron-duplicate-seqmap — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` focused
  `scripts/sbtc "cli/test"` after the Electron fork-exit blocker was fixed.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "cli/testOnly scalascript.cli.ToolkitElectronSmokeTest"`.
- **Observed failure:** `ToolkitElectronSmokeTest` case
  `toolkit-demo Electron bundle renders, routes Add, and persists after restart`
  fails with renderer error
  `Uncaught SyntaxError: Identifier '_seqMap' has already been declared`; the
  smoke then reports `SMOKE_FAIL initial render missing`.
- **Impact:** toolkit Electron smoke is a real browser/Electron bundle execution
  gate, not just a string assertion. Duplicate JS helper declarations in emitted
  bundles can blank desktop UI startup.
- **Fix direction:** reproduce focused, inspect the generated Electron bundle, and
  deduplicate or scope duplicate helper preamble emission (`_seqMap`) so runtime
  helpers are emitted once per bundle.
- **Done-when:** focused `ToolkitElectronSmokeTest` is green and full `cli/test`
  no longer reports this suite.
- **FIXED (2026-07-08, `cea0c3aed`):** the renderer bundle had a broader
  strict-mode duplicate/binding chain: collection sequencing helpers existed in
  both `core-collections.mjs` and `async.mjs`; session HMAC reused the core
  crypto helper name; the typed JSON facade could be included twice; browser
  patch assignments lacked stable bindings; and `_ssc_frontend_name` was split
  between ws/server and injected frontend code. The runtime now has a single
  collection helper source, repeat-safe typed JSON facade bindings, distinct
  session HMAC name, and a base frontend-name binding.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.ToolkitElectronSmokeTest"`;
  full `scripts/sbtc "cli/test"` (554 succeeded, 29 canceled, 0 failed);
  bounded root `scripts/sbtc "test"` (elapsed 1668s, success).

## root-test-cli-fork-exit-after-green — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after bounded sbt Test concurrency was added.
- **Repro observed in root gate:** `cli / Test / test` reported
  `Total number of tests run: 488`, `Tests: succeeded 488, failed 0, canceled 19`,
  and `All tests passed.`, then sbt still failed the task with
  `Error during tests: Running java with options ... sbt.ForkMain ... failed with exit code 1`.
- **Impact:** the CLI aggregate is red even though ScalaTest reports no failing
  test. Root `test` cannot be treated as a production gate until the forked JVM
  exits cleanly or the late exit is traced to a real failing resource cleanup path.
- **Fix direction:** reproduce with focused `cli/testOnly` suites first, starting
  from the last emitted suite in the root stream and then widening to `cli/test` if
  needed. Inspect late JVM/process cleanup and generated files such as
  `v1/tools/cli/ssc-storage.json`; do not paper over the non-zero fork exit.
- **Done-when:** targeted repro is understood and fixed, `scripts/sbtc "cli/test"`
  exits 0, and the final root-equivalent gate no longer reports this task failure.
- **Progress (2026-07-08, uncommitted worktree):** minimal
  `ElectronJvmRestCliTest` fork exit was caused by stale fake-Electron greps in
  the typed-route client smoke. The generated client now accepts
  `headers, cancelToken` and the HTTP runtime assigns `response = await fetch(...)`
  in a retry loop. Updating those smoke assertions made focused
  `ElectronJvmRestCliTest` pass with fork exit 0. Full `cli/test` now reaches
  ordinary assertion failures tracked separately above instead of the old
  after-green fork exit.
- **FIXED (2026-07-08, `cea0c3aed`):** after updating the stale Electron typed
  client smoke assertions and fixing the later deterministic CLI/runtime
  blockers, the full forked `cli/test` exits 0.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.ElectronJvmRestCliTest"`;
  full `scripts/sbtc "cli/test"` (554 succeeded, 29 canceled, 0 failed);
  bounded root `scripts/sbtc "test"` (elapsed 1668s, success).

## root-test-js-rowpost-runtime-contract — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after bounded sbt Test concurrency was added.
- **Repro observed in root gate:** `backendInterpreter / Test / test` failed
  `scalascript.JsGenStdImportTest` case
  `JS signal runtime defines the std/ui row-data natives` at
  `JsGenStdImportTest.scala:403`: generated runtime did not contain the expected
  `_RowPost` body payload line `body: resolvePayload(r, act.bodyField)`.
- **Impact:** JS std/ui row-data runtime contract may no longer send row POST
  body payloads through the same resolver used by other row fields. If this is a
  true runtime regression, browser UI row actions can submit stale or unresolved
  bodies; if only the string assertion is stale, the production contract still
  needs a sharper test.
- **Fix direction:** run the focused `JsGenStdImportTest` filter, inspect the
  emitted JS runtime around `_RowPost` / `resolvePayload`, then either restore the
  payload resolution path or update the structural assertion to match the current
  equivalent implementation.
- **Done-when:** focused `JsGenStdImportTest` is green, the row POST body
  contract is covered by the test, and affected std/ui conformance runs before
  pushing.
- **FIXED (2026-07-08, `cea0c3aed`):** the runtime still resolves row POST
  bodies through `resolvePayload`; the old string assertion expected the whole
  object literal inline shape and missed the current `_postBody` local. The test
  now asserts the resolver assignment and the fetch body use separately.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest"`;
  affected conformance `tests/conformance/run.sh --only
  'collections,dataset-from-file,dataset-shape,json-*,std-ui-*,tkv2-*'
  --no-memo` (19/19); bounded root `scripts/sbtc "test"` (elapsed 1668s,
  success).

## root-test-sbt-aggregate-heap-oom — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` root retest
  after v2 `V2ConformanceTest` was green and pushed through `ab37c7d0b`.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "test"` on `origin/main@c9d300335`.
- **Observed failure:** the aggregate root test run progressed through many
  payments/crypto/config/server suites, then entered a long silent section with
  the sbt JVM pegged at >1000% CPU, ~5.7 GB RSS despite `-Xmx4G`, and 47 idle
  node children. It printed repeated
  `Exception in thread "pool-453-thread-..." java.lang.OutOfMemoryError: Java heap space`.
  `jcmd <pid> Thread.print` could not attach within 10.5s. Ctrl-C did not stop
  the run; SIGTERM removed the node children but the sbt JVM required SIGKILL.
- **Impact:** root `sbt "test"` is not yet a reliable production gate even after
  deterministic v2 conformance blockers are fixed.
- **Fix direction:** determine whether the failure is root-aggregate parallelism,
  Scala.js jsEnv node fan-out, or a specific test module leaking heap. Start by
  reproducing with a bounded/constrained root test invocation or focused
  Scala.js module groups, then encode the fix in build/test settings or the
  project wrapper. Record the exact command that becomes the production gate.
- **Done-when:** a root-equivalent gate completes without heap OOM/hung sbt JVM,
  and the chosen command is recorded in `SPRINT.md`/`CHANGELOG.md`.
- **Progress (2026-07-08, uncommitted worktree):** adding
  `Global / concurrentRestrictions += Tags.limit(Tags.Test,
  SSC_SBT_TEST_CONCURRENCY default 4)` to `build.sbt` made the next root
  `scripts/sbtc "test"` complete in about 27m32s without the previous OOM/hung
  sbt JVM pattern. The gate still exited 1 because it exposed the two separate
  blockers tracked above: `root-test-js-rowpost-runtime-contract` and
  `root-test-cli-fork-exit-after-green`.
- **FIXED (2026-07-08, `cea0c3aed`):** bounded root `scripts/sbtc "test"`
  completed successfully with the `Tags.Test` cap defaulting to 4. No heap OOM,
  hung sbt JVM, or lingering fork-exit failure remained.
- **Verified:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  rm -f v1/tools/cli/ssc-storage.json && scripts/sbtc "test"`:
  `[success] elapsed: 1668 s (0:27:48.0)`.

## v2-busi-testsweep-gaps batch — `fixed` (2026-07-08)

Seven root causes closed working busi tests/v2 47/61 → 61/61 on --v2 (v1 = 61/61
same launcher; every fail was a real engine gap). One entry per cause:

- **v2-topvar-def-split-cell** — a top-level `var` referenced from def bodies was
  TWO cells: entry-local Let cell vs the defs' auto-created `Global("@name")`.
  Assignments from defs vanished (busi persistence: `def saveLocal(t) = localCell = t`).
  Fix: convertStats pre-pass (sharedTopVars) + global.reg of the entry cell under
  "@name". Regression: `tests/conformance/var-topdef-shared.ssc`. Killed sync,
  sync_http, local_journal, deferred_action.
- **v2-fbc-string-eq-optimistic** — tryFBc kept `==`/`!=` UNGUARDED on the Long
  fast path while ordering ops required provably-Long operands; tryFLC reads a
  StrV Local as 0L → string equality of two locals was ALWAYS true inside If
  conditions (`if p == period` matched every period — July facts leaked into June
  folds). Fix: extend the flcProvablyLong guard to equality. Regression:
  `tests/conformance/string-eq-locals.ssc`. Killed income, invoicing, trust, vat,
  meeting_room.
- **v2-hof-effect-threading** — a perform inside a list-HOF lambda returned a raw
  Op that map/filter/fold/foreach collected as DATA (busi operator: hPlan was a
  list of Ops). Fix: mapThreadOp/foldThreadOp — per-element Op results defer the
  REST of the traversal into the op's continuation (letThreadOp protocol);
  bridge-only by construction (__method__ dispatch). Killed operator.
- **v2-array-companion-list** — `Array.fill/tabulate` returned Cons-lists;
  `m(i) = v` then hit arr.set with "expected Array, got List". Fix: Array
  companion returns ForeignV(ArrayBuffer); + ArrayBuffer indexing in
  applyFallback, ArrayBuffer length/size/isEmpty/toList in dispatch. Part 1 of qr.
- **v2-fastcode-length-tolerant** — the length/size FastCode returned `0L` for ANY
  unrecognized receiver (and cons-cell field count 2 for lists): every
  `while i < msg.length` over an Array.fill result ran ZERO iterations (busi qr:
  a data-less, mask-only QR matrix that STILL passed structural checks). Fix:
  honest lengths (unlist walk for Cons/Nil, ArrayBuffer size) + sys.error for
  unknown receivers. Part 2 of qr.
- **v2-fence-regex-midline** — the all-fences extractor matched fence opens
  MID-LINE (inside a string literal holding markdown), desyncing the fence walk —
  prose after the next real close parsed as code ("illegal unicode codepoint:
  0xab"). Fix: (?m)^ anchors + newline-anchored first-fence search. Killed model.
- **v2-arith-table-divergence** — TWO arith implementations: Prims.arithOp (full:
  Map+(k->v), char semantics) for LITERAL op names vs the resolve-table __arith__
  (string-concat fallback) for non-literal names. OpAnf's letify was binding
  `Lit("+")` into a Local — demoting map-extend to string concat (busi litdoc:
  attrs became "Map()(id, Str(demo))…"). Fix: OpAnf keeps pure args (Lit/Global/
  Lam/Local) IN PLACE (also preserves FastCode shapes), and the table arith gained
  the Map+Tuple2 case. Full unification of the two ariths → BACKLOG. Killed
  litdoc_content.
- **v1-content-imported-doc-fallback** — contentToolkitSection/contentSection/
  contentBlock/contentData resolve only the CURRENT document; on v1 an imported
  module runs with its own document, but the v2 bridge inlines imports under the
  entry file's document, so a module's sections were unreachable from its own
  code. Fix (v1 content-plugin, fires only where it previously errored/None'd):
  fall back to the registered ContentImportedModules documents. Killed
  content_toolkit.
## root-test-v2-conformance-toolkit-regressions — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` full root
  `scripts/sbtc "test"` after the sealed-extension and cluster blockers were fixed.
- **Repro observed in root gate:** `V2ConformanceTest` failed:
  `std-ui-jobpanel` rendered `?` labels instead of `2:Jobs` / `2:New job`;
  `tkv2-busi-home`, `tkv2-forms`, and `tkv2-offline` threw
  `RuntimeException: __method__: no field 'set' on named-method-obj (None)`;
  `tkv2-pwa` threw `RuntimeException: unbound global: pwa`.
- **Impact:** v2 default is not production-ready for the tk/std-ui conformance
  cluster until these cases either pass or are explicitly classified out of the
  production gate with a documented reason.
- **Fix direction:** split into focused repros from `V2ConformanceTest` and fix
  the underlying bridge/runtime gaps. Start with the shared `named-method-obj.set`
  family because it blocks three tk cases, then `pwa`, then the jobpanel label
  rendering gap. Verify the selected `V2ConformanceTest` cases and the affected
  conformance slice before pushing.
- **Progress (2026-07-08, `dad57a70b`):** the shared
  `named-method-obj.set` family is fixed. v1 `ReactiveSignal` values converted
  into v2 `NamedMethodObj`s now expose `get`/`set` and writes use host raw
  values, so `tkv2-busi-home`, `tkv2-forms`, and `tkv2-offline` pass targeted
  `V2ConformanceTest` filters. Affected conformance
  `tkv2-busi-home,tkv2-forms,tkv2-offline` is 3/3 green across INT+JS.
  Remaining failures from this root entry: `tkv2-pwa` (`unbound global: pwa`)
  and `std-ui-jobpanel` heading labels (`?` instead of `2:...`).
- **Progress (2026-07-08, `a9028b830`):** `tkv2-pwa` is fixed. Root causes:
  `pwaPlugin` was absent from the v2 plugin bridge classpath, `pwa(...)` named
  args were not pre-registered in the v2 frontend bridge, and plugin-owned
  `ctx.registerRoute(...)` calls were no-ops under `MinimalCtx`, so PWA routes
  never reached the v2 web server registry. Gates: `V2ConformanceTest -z
  tkv2-pwa` green, `V2ConformanceTest -z tkv2` green (6/6), and
  `tests/conformance/run.sh --only 'tkv2-pwa' --no-memo` green (INT pass;
  JS/JVM skipped by metadata). Remaining failure from this root entry:
  `std-ui-jobpanel` heading labels (`?` instead of `2:...`).
- **Progress (2026-07-08, `0facf7506`):** `std-ui-jobpanel` is fixed
  on the rebased `green-main-full-sbt-test-gating` branch. Root cause:
  `FrontendBridge` registered curried vararg defs such as
  `cardWithHeader(header)(body*)` as ordinary direct-vararg defs, so the first
  clause call lowered as `cardWithHeader(List(heading))`; the UI header became
  `List(List(HeadingNode(...)))` and the label extractor fell through to `?`.
  Gates after rebasing on `origin/main@9e48204e5`: `V2ConformanceTest -z
  std-ui-jobpanel` green, `V2ConformanceTest -z tkv2` green (6/6), and
  `tests/conformance/run.sh --only 'std-ui-jobpanel' --no-memo` green
  (INT+JS pass; JVM skipped by metadata).
- **New blocker after rebase (2026-07-08, `origin/main@9e48204e5`):** full
  `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` now fails only
  `array-companion-statics` with
  `RuntimeException: __method__: no dispatch for .sum on <foreign>`. Repro:
  `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest"`.
  Root cause confirmed below: the fresh real-array runtime semantics batch left
  read-only collection dispatch list-only.
- **FIXED (2026-07-08, `f6e6383ac`):** `array-companion-statics` is fixed.
  `ForeignV(ArrayBuffer)` is now list-like for read-only collection dispatch
  (`sum`, `mkString`, HOFs, etc.) while keeping mutable array operations
  (`arr.get/set`, indexed apply, `length`) on the real ArrayBuffer. Gates:
  `V2ConformanceTest -z array-companion-statics` green,
  `tests/conformance/run.sh --only 'array-companion-statics' --no-memo` green
  (INT+JS+JVM), and full
  `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` green
  (76 succeeded, 54 ignored, 0 failed). No known deterministic blocker remains
  in this `V2ConformanceTest` root entry.

## root-test-stable-spi-os-plugin-import — `fixed` (2026-07-08)

- **Found by:** codex, during the same full root `scripts/sbtc "test"` gate.
- **Repro observed in root gate:** `StableSpiEnforcementTest` failed
  `value-surface plugins depend only on scalascript-plugin-api` with
  `os-plugin/scala/scalascript/compiler/plugin/os/OsIntrinsics.scala: import
  scalascript.interpreter.InterpretError`.
- **Impact:** stable plugin API enforcement is red; value-surface plugins are not
  fully isolated from interpreter internals.
- **Root cause:** OS plugin had already migrated to `PluginNative`/`PluginValue`,
  but one fallback still imported and threw interpreter `InterpretError` directly.
- **Fix:** `c3e277723` replaces the direct interpreter error with
  `PluginError.raise("exit(code: Int)")`, keeping the value-surface plugin on
  `scalascript-plugin-api`; the existing NUL separator literal was also normalized
  to `"\u0000"` so future diffs stay text-friendly.
- **Verified:** `scripts/sbtc "backendInterpreterPluginTests/testOnly scalascript.StableSpiEnforcementTest"`
  2/2 green; `scripts/sbtc "osPlugin/testOnly scalascript.compiler.plugin.os.OsPluginTest"`
  14/14 green; `tests/conformance/run.sh --only 'std-process-import' --no-memo`
  1/1 green.

## root-test-verify-default-srcdir-parent-scan — `fixed` (2026-07-08)

- **Found by:** codex, during the same full root `scripts/sbtc "test"` gate.
- **Repro observed in root gate:** `VerifyCliTest` cases such as
  `verify .../ssc-verify-noruntime-* --strict` and `verify
  .../ssc-verify-json-* --json` spent about 1-2 minutes each on tiny temp
  directories. Thread dump showed the child process hot in
  `runVerify(Main.scala:4125)` at `os.walk(srcDir).filter(os.isFile)`; default
  `srcDir` was `artifactDir / os.up`, so temp sandboxes scanned the entire
  `/var/.../T` parent containing many other root-suite temp directories.
- **Impact:** root `sbt test` becomes needlessly slow and can look hung after the
  first real failures; production `ssc verify <dir>` can also scan far outside
  the requested artifact set by default.
- **Root cause:** default `srcDir` was always `artifactDir / os.up`; custom
  artifact directories such as temp `out/` folders therefore indexed every
  sibling `.ssc` file in the parent tree before checking a tiny artifact set.
- **Fix:** `6c996bd63` changes the implicit default to the artifact directory
  itself, preserving parent lookup only for conventional `.ssc-artifacts`
  output dirs, and adds a subprocess regression for a custom `out/` directory.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.VerifyCliTest"` 8/8
  green; `tests/conformance/run.sh --only 'std-process-import' --no-memo` 1/1
  green.

## v2-actors-sendafter-cli-default-noop — `fixed` (2026-07-08)

- **Found by:** codex, while fixing `root-test-cluster-cli-runtime-readiness`.
- **Repro:** after `scripts/sbtc "cli/assembly"`, run a fat-jar script containing
  `runActors { val me = spawn { () => val pid = self(); sendAfter(10, pid,
  "hello"); receive { case msg => println("got: " + msg) } } }`.
  `java -jar v1/tools/cli/target/scala-3.8.3/ssc.jar <file>` and `--v2`
  exit 0 with no `got: hello`; `--v1` prints `got: hello`.
- **Impact:** v2/default does not yet execute delayed actor flows that v1's actor
  scheduler supports. The root `sbt test` blocker below was fixed by making v1
  cluster integration fixtures explicit `--v1`; this entry tracks the actual v2
  production gap separately instead of hiding it behind test harness selection.
- **Fix direction:** implement/parity-check actor timer handling in the v2 runtime
  path or explicitly reject unsupported actor APIs under `--v2` with a diagnostic
  until v2 actor support is complete. Verify with the fat-jar repro above plus
  actor/cluster conformance slices.
- **Root cause:** `PluginBridge.registerActors` had a partial v2 actor runtime:
  `spawn`, `receive`, `self`, and `runActors` existed, but scheduled sends were
  not registered and `runActors` waited only for the root actor thread. In the
  fat-jar path the root actor completed immediately after `spawn`, so the JVM
  exited with virtual child/timer work still pending and no diagnostic.
- **Fix:** `a6c9d8b7c` adds v2 actor run-state/quiescence tracking, real
  `sendAfter` / `sendInterval` / `cancelTimer` globals, queue wakeups, and a
  real assembled-CLI regression covering default and `--v2`.
- **Verified:** `scripts/sbtc "v2PluginBridge/compile"`; `scripts/sbtc
  "cli/assembly"`; the original fat-jar repro now prints `got: hello` under
  default, `--v2`, and `--v1`; `scripts/sbtc "cli/testOnly *V2ActorCliTest"`;
  `scripts/sbtc "installBin"` followed by `tests/conformance/run.sh --only
  'actors-*' --no-memo` (8/8 passed; first pre-install run was invalid because
  the conformance runner uses `bin/ssc` / `bin/lib/ssc.jar`, not the freshly
  assembled `v1/tools/cli/.../ssc.jar`).

## root-test-cluster-cli-runtime-readiness — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after the bytecode split-runtime and Scala.js npm
  dependency fixes.
- **Repro observed in root gate:** the full root test PTY was lost before the final
  sbt summary, but the running output showed a deterministic cluster failure
  family: `ClusterStepDownCliTest`, `ClusterStatusCliTest`, and
  `ClusterAuthCliTest` fail because nodes do not bind within 8s; `MultiNodeClusterTest`
  fails to print `LEADER:` / `REMOTE_SPAWN_OK`; `ClusterBullyStatusConvergenceTest`
  never sees the status HTTP endpoint; `PartitionHealingTest` reports every node
  as `LEADER=<none>`; `SingletonFailoverTest` never propagates `SENT1:true`.
- **Targeted repro to run before fixing:** `scripts/sbtc "cli/testOnly
  scalascript.cli.ClusterStepDownCliTest scalascript.cli.ClusterStatusCliTest
  scalascript.cli.ClusterAuthCliTest scalascript.cli.MultiNodeClusterTest
  scalascript.cli.ClusterBullyStatusConvergenceTest scalascript.cli.PartitionHealingTest
  scalascript.cli.SingletonFailoverTest"`.
- **Initial hypothesis:** this is one cluster-runtime/CLI readiness family, not
  separate assertion issues. The subprocesses start and print the web banner, but
  the cluster markers/status endpoints are missing or late. Confirm whether the
  regression is runtime startup, test timeout/readiness detection, or an interaction
  with concurrent root-suite execution before changing semantics.
- **Status:** fixed in `da63bb96a`. Actual root cause was the v2 default switch in
  the fat-jar launch path: the cluster suites spawn node fixture scripts with
  `java -jar ssc.jar <node.ssc>`, so those v1 actor-cluster fixtures started on
  v2/default. Minimal repro showed `sendAfter` actor flows print under `--v1` but
  exit 0 with no delayed message under default/`--v2`. The test harness now runs
  node fixture subprocesses with explicit `--v1`; CLI subcommands such as
  `cluster status`, `cluster drain`, and `cluster step-down` still run normally
  against those nodes.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.ClusterStepDownCliTest
  scalascript.cli.ClusterStatusCliTest scalascript.cli.ClusterAuthCliTest
  scalascript.cli.MultiNodeClusterTest scalascript.cli.ClusterBullyStatusConvergenceTest
  scalascript.cli.PartitionHealingTest scalascript.cli.SingletonFailoverTest
  scalascript.cli.ClusterDrainCliTest scalascript.cli.ClusterEventsCliTest
  scalascript.cli.PartitionTest"` (**13/13 green**); `tests/conformance/run.sh
  --only 'actors*,cluster-connect,distributed*' --no-memo` (**14 passed,
  0 failed**).

## root-test-command-registry-other-category — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after the bytecode split-runtime and Scala.js npm
  dependency fixes.
- **Repro observed in root gate:** `CommandRegistryTest` failed
  `every command category is in the help ordering` with `List("Other") was not
  empty` at `CommandRegistryTest.scala:57`.
- **Targeted repro to run before fixing:** `scripts/sbtc "cli/testOnly
  scalascript.cli.CommandRegistryTest"`.
- **Initial hypothesis:** at least one command provider now reports or defaults to
  category `Other`, but the help category ordering omits it. Either assign the
  provider a real existing category or deliberately add `Other` to the ordering
  if it is now a supported category; do not silence the test without preserving
  deterministic help grouping.
- **Status:** fixed in `631ed8052`. Root cause was `VersionCmd` explicitly using
  the fallback-style `Other` category. `Other` is the default for unclassified
  commands, while `CommandRegistryTest` intentionally requires every visible
  command to be placed into an ordered help bucket. `version` is metadata/help
  output, so it now uses the existing `Help` category instead of normalising
  `Other` as a public bucket.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.CommandRegistryTest"`
  (**8/8 green**) and `tests/conformance/run.sh --only 'std-semigroup-monoid'
  --no-memo` (**1/1 green**).

## root-test-sealed-extension-option-dispatch — `fixed` (2026-07-08)

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after the bytecode split-runtime and Scala.js npm
  dependency fixes.
- **Repro observed in root gate:** `SealedExtensionDispatchTest` failed
  `Some dispatches extension on Option`: expected stdout `42\n99`, actual
  `Some(42)\n99` at `SealedExtensionDispatchTest.scala:81`.
- **Targeted repro to run before fixing:** `scripts/sbtc "backendInterpreter/testOnly
  scalascript.SealedExtensionDispatchTest"`.
- **Initial hypothesis:** the `Some` receiver path dispatches an extension found on
  the sealed parent `Option`, but passes the case instance instead of the expected
  unwrapped payload for this extension shape. Inspect the test before changing
  dispatch: `None` still prints `99`, so the bug may be specific to case payload
  extraction for `Some`.
- **Status:** fixed in `1e503de04`. Actual root cause was built-in dispatch
  applicability, not payload extraction: interpreter `Option.orElse` accepted any
  single argument, so `Some(42).orElse(0)` returned the built-in receiver `Some(42)`
  before the user extension `def orElse(default: A): A` could run. Built-in
  `Option.orElse` now handles only Option-valued alternatives; non-Option
  defaults fall through to extension dispatch.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly
  scalascript.SealedExtensionDispatchTest"` (**4/4 green**);
  `scripts/sbtc "backendInterpreter/testOnly scalascript.SealedExtensionDispatchTest
  scalascript.InterpreterTest -- -z \"built-in members take precedence\" -z
  \"option orElse\""` (filtered invariant slice green); and
  `tests/conformance/run.sh --only
  'option,optional,typeclass-extension,std-functor-applicative-monad,std-monaderror'
  --no-memo` (**5/5 green** on INT/JS/JVM).

## v2-args-global-shadowed-by-native — `fixed` (2026-07-08)

- **Found by:** claude-fable-5, unmasked while testing OpAnf (entry below): the
  If-cond Let-wrap re-routed `if args.length > 0` from the length FastCode (whose
  tolerant `case _ => 0L` swallowed the wrong receiver) to the honest generic
  dispatch — which crashed `.length on <closure>` (dataset-word-count et al).
- **Root cause:** `loadAll()`'s SPI bridging registers a native FUNCTION global
  under "args"; the args VALUE-list registration was guarded by `if isEmpty` and
  never fired — `args` was a closure everywhere on v2. `args.length`/`args(0)`
  (the documented semantics, examples/dataset-word-count.ssc) only "worked" via
  the FastCode accident. Pre-existing on origin/main, INDEPENDENT of OpAnf; the
  v1 lane has the same gap (`No method 'length' on NativeFnV(<native:args>)`) —
  v1 side left open (BACKLOG note).
- **Fix:** register the args Cons/Nil list AFTER the plugin loop (same
  post-plugins override pattern as cwd/sep/platform), built from `Runtime.argv`
  (now set BEFORE loadAll in RunV2/bridgeCli); `scalascript.args` prop stays as
  the embedder fallback.
- **Verified:** `println(args)` → `List()`, `args.length` → `0` through the
  generic dispatch; dataset-word-count PASSES honestly (not via `0L` tolerance).

## v2-op-arg-lifting — `fixed` (2026-07-08)

- **Found by:** claude-fable-5, working busi's ledger repro past the append/2 fix.
- **Symptom:** a strict call (user fn OR native) with an unresolved effect `Op` as an
  ARGUMENT executes immediately instead of deferring into the Op's continuation.
  busi `tests/v2/ledger.ssc` now fails at check #2: `accountBalance` (imported fn
  performing `Journal.read` inside) returns a raw `Op(Journal.read, …)`; `formatMoney`
  / `println` then consume the Op as a value. Same family: conformance
  `js-applyunary-effect-cps.ssc` on the v2 lane (`__unary__: - on Op(...)`), and a
  perform whose argument is effectful (`Journal.append("sum", xs.foldLeft(...))`
  where `xs` came from a read) leaks the Op into the handler's payload.
- **What works vs not:** val-binding (`letThreadOp`), statement sequencing
  (`seqThreadOp`), method-receiver (`methodOp`), arith operands, and fn-position
  (`applyFallback`) all lift Ops; **call-argument position does not** — neither for
  closures nor for plugin natives.
- **Repro (minimal):** inside `runJournal(() => { … })`:
  `println("balance=" + formatMoney(accountBalance(...)))` prints the Op raw.
  Full: `cd ~/work/my/busi && scalascript/bin/ssc --v2 --plugin crypto,auth,smtp,tcp,sql tests/v2/ledger.ssc`
  (on ≥ d2340f85e) → `FAIL: cash debit = 100.00` after `ok: entry balances`.
- **Fix (landed):** NOT at the runtime call chokepoint — a blanket runtime lift
  would break the Mira/hm kernel lane, where passing Op VALUES to functions is
  legitimate (`runState(k(r), s)` must receive the op raw; deferring forwards it
  past its own handler). Instead: `OpAnf` — a bridge-side CoreIR pass (bridged
  lane only) that Let-binds potentially-Op arguments (App args, Prim args, Ctor
  fields, Match scrutinees, If conditions), so the kernel's existing
  letThreadOp/seqThreadOp threading performs the deferral. De Bruijn
  cutoff-shifting for the inserted binders. Exclusions: `handle(expr)(handler)`
  paren form (the body's Op must reach handle RAW — effect-multishot bench
  caught this); `While` untouched (per-iteration re-evaluation). GATED: the pass
  runs only when the merged source mentions `effect `/`handle` — ops cannot
  materialize otherwise (context runners intercept pre-Op), and unconditional
  wrapping made pattern-match-heavy 3-4× slower; with the gate it's at baseline.
- **Bench A/B (bin/ssc bench --machine --backend v2):** pattern-match-heavy
  26.2-26.9 vs baseline ~28 ✓; effect-multishot 5.19 vs 5.04-6.01 ✓;
  streams-pipeline 0.0080 vs 0.0085 ✓; arith-loop/nested-loop/list-fold/
  hof-pipeline parity.
- **Verified:** busi ledger.ssc ALL OK on --v2 (was: FAIL check #2); examples
  corpus 153/9 = baseline; tests/conformance v2 batch 109/39 (was 108/40 —
  `js-applyunary-effect-cps` FLIPPED TO PASS: `-Op` unary operand now threads);
  run.sh effect family 4/4 INT/JS/JVM; v2 kernel check.sh 8×3 ALL GREEN
  (kernel lane untouched by construction).
- **busi full sweep (tests/v2, 61 files):** --v2 47/61 PASS (was 0 — died on the
  first test); --v1 same launcher/flags 61/61 → the 14 remaining fails are real
  v2 parity gaps, queued as SPRINT `v2-busi-testsweep-gaps`. run.sh full
  conformance 123/123.

## v1-jvm-state-threaded-handler-codegen — `open` (2026-07-08)

- **Found by:** claude-fable-5, while shaping the effect-multiarg-op regression.
- **Symptom:** `bin/ssc run-jvm` fails to COMPILE any handler whose arms return
  lambdas (the state-threading deep-handler idiom, busi's `runJournal`):
  "I could not infer the type of the parameter s / Expected type for the whole
  anonymous function: Any". Arity-independent (1-arg op repros identically);
  INT and JS lanes run the same code fine.
- **Repro:** `effect Cnt: def tick(n: Int): Unit` + handler arms
  `case Cnt.tick(n, resume) => (s: Int) => resume(())(s + n)` /
  `case Return(x) => (s: Int) => x`, applied as `threaded(0)` → `run-jvm` fails,
  `run --v1` / `emit-js` pass.
- **Impact:** low today — busi runs the interpreter lane; no corpus case uses the
  idiom on the JVM lane (that's why it was never seen).

## v2-effect-multiarg-op — `fixed` (2026-07-08)

- **Found by:** busi agent (rozum `scalascript` room, 2026-07-08 seq31), while bumping
  busi to scalascript pin `0a6358787` with `v2-prod-default-switch` active.
- **Repro:** `cd ~/work/my/busi && scalascript/bin/ssc --v2 --plugin crypto,auth,smtp,tcp,sql tests/v2/ledger.ssc`
  → `RuntimeException: match: no arm for append/2` at `PluginBridge.runEffectLoop`
  (PluginBridge.scala:2165 → Runtime.scala:367). Reproduced locally 1:1.
- **Root cause:** multi-argument effect operations lost their arity crossing the
  Free-monad Op protocol. `Runtime.scala` effect dispatch packed
  `Journal.append(scope, fact)` as `DataV("Op", [label, Tuple2(scope, fact), k])`;
  `PluginBridge.runEffectLoop` called the handler with **append/2** while the user
  arm `case Journal.append(scope, fact, resume)` compiles to **append/3**. Nothing
  in the corpus exercised a >1-arg effect op. v1 delivers payload args unpacked.
- **Fix:** `2ef288004` — multi-arg payloads pack under the internal `__EffArgs__`
  marker (NOT `TupleN`: a genuine single tuple argument must stay op/2);
  `runEffectLoop` unpacks to `op(a1…aN, resume)`. Companion fix `d2340f85e` —
  std/ imports on the v2 lane now fall back to `libPath/runtime/<path>` (mirrors
  v1's ImportResolver), fixing `unbound global: money` when running the assembled
  jar from busi's cwd (std resolution was cwd-sensitive).
- **Verified:** regression `tests/conformance/effect-multiarg-op.ssc` (+
  `lib/effect-journal.ssc`, imported-module handler, 2-arg + 1-arg ops) green on
  INT/JS/JVM + v2 engine; `run.sh --only 'effect*'` 4/4; examples corpus at the
  153/9 baseline; busi ledger repro gets past both failures (now blocked by
  `v2-op-arg-lifting` above — reported to busi).
- **Awaiting:** busi re-runs its 62-test suite on `--v2` (blocked on
  v2-op-arg-lifting for ledger.ssc at least).

## conformance-int-std-semigroup-monoid — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating` after the
  `.scjvm` cache invalidation fix.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo` or direct
  `bin/ssc run --v1 tests/conformance/std-semigroup-monoid.ssc`.
- **Observed:** full conformance reports `std-semigroup-monoid` failing only on
  INT: expected lines 4-6 are `Some(24)`, `42`, and `foo`, but INT prints fewer
  lines (`<missing>` for those entries). JS and JVM pass.
- **Status:** fixed in `e571fd3ae`. Root cause was INT given registration:
  `given intSum: Monoid[Int]` was registered only as `Monoid[Int]`, while
  `combineAllOption[A: Semigroup]` needs `Semigroup[Int]`. Scala/JS/JVM accept
  this because `Monoid extends Semigroup`; the interpreter did not expose that
  parent typeclass key.
- **Fix:** concrete and parametric `given` registration now follows the
  interpreter's `parentTypes` chain and registers parent typeclass aliases such
  as `Semigroup[Int]` for `Monoid[Int]`. Exact concrete keys still own ambiguity
  tracking; aliases fill only missing parent keys.
- **Verified:** direct `bin/ssc run --v1 tests/conformance/std-semigroup-monoid.ssc`
  prints all six expected lines; `scripts/sbtc "backendInterpreter/testOnly scalascript.FinalTaglessConformanceTest scalascript.GivenUsingTest"`
  (**17/17 green**); and
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`
  (**1/1 green** across INT/JS/JVM).

## jvm-scjvm-cache-codegen-version — `fixed` (2026-07-08)

- **Found by:** codex, while fixing `conformance-jvm-std-ui-generated-braces`.
- **Repro:** keep a stale generated artifact such as
  `tests/conformance/.ssc-artifacts/std-ui-extended.scjvm` from before a JVM
  backend codegen fix, rebuild/install the CLI, then run
  `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc`.
- **Observed:** `run-jvm` reused the source-fresh `.scjvm` artifact and still
  failed with the old generated Scala `'}' expected, but eof found`. Removing only
  `tests/conformance/.ssc-artifacts/std-ui*.scjvm` forced regeneration and the same
  assembled command passed. This means the `.scjvm` freshness key does not account
  for backend codegen/runtime changes.
- **Status:** fixed in `322ee868f`. Root cause was that `.scjvm` artifacts used
  only the `.ssc` `sourceHash` as their freshness key, so generated Scala from an
  older JVM backend survived source-fresh after codegen/runtime fixes.
- **Fix:** `.scjvm` artifacts now carry `codegenVersion =
  "jvm-codegen-2026-07-08-1"` when emitted by the normal JVM artifact writer.
  `ModuleGraph.isJvmStale` treats missing/old codegen versions as stale while
  preserving ABI compatibility: legacy artifacts remain readable, then regenerate.
- **Verified:** `scripts/sbtc "core/testOnly scalascript.artifact.ModuleGraphTest"`
  (**15/15 green**, including legacy/old-version source-fresh `.scjvm`
  invalidation), `scripts/sbtc "cli/testOnly scalascript.cli.VerifyCliTest"`
  (**7/7 green**), `scripts/sbtc "installBin"`, and
  `tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
  (**5/5 green**).

## conformance-int-variables-while-update — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `tests/conformance/run.sh --only 'variables' --no-memo` or
  `bin/ssc run --v1 tests/conformance/variables.ssc`.
- **Observed:** INT prints `5`, `10`, `720`, `55`; expected line 2 is `15`.
  JS/JVM pass. The first `while x < 5` loop increments `x` but does not accumulate
  the updated `x` into `sum`.
- **Status:** fixed in `4e67a2f41`. Root cause was the interpreter closed-form
  while optimizer, not the generic assignment path: it folded a body shaped like
  `x = x + 1; sum = sum + x` as if `sum` read the pre-update counter, producing
  `0+1+2+3+4 = 10`. ScalaScript assignment order requires `sum` to read the
  post-update `x`, producing `1+2+3+4+5 = 15`.
- **Verified:** `scripts/sbtc 'backendInterpreter/testOnly scalascript.SscVmTest -- -z "closed-form"'`
  (**6/6 green**); `scripts/sbtc "installBin"`; direct
  `bin/ssc run --v1 tests/conformance/variables.ssc`; and
  `tests/conformance/run.sh --only 'variables' --no-memo` (**1/1 green**).

## conformance-jvm-std-ui-generated-braces — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc`.
- **Observed:** JVM generated Scala fails with `'}' expected, but eof found`; the
  warnings start where many imported `std/ui` component `object`s begin, so the
  conformance harness reports missing stdout for `std-ui-aggregator` and
  `std-ui-extended*`. INT/JS pass.
- **Status:** fixed in `9bd6cb87d`. Root cause was two string-level JVM source
  transforms treating braces inside imported UI triple-quoted JavaScript/CSS
  literals as Scala structure. `colonObjectsToBraces` also stopped collecting an
  `object Name:` body when a triple-quoted literal continued at column 0, which
  prematurely inserted `}` inside `SubmitButton.js`. `JvmGen` now tracks
  triple-quoted strings while collecting colon-object bodies and uses a shared
  string/comment-aware brace matcher for duplicate object/package merges.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenUsingTest"`
  (**14/14 green**); direct
  `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc` after regenerating the
  stale local `.scjvm` artifact; and
  `tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
  (**5/5 green**).

## conformance-std-typeclass-int-jvm-gaps — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`, run either
  `bin/ssc run --v1 tests/conformance/std-index.ssc` or
  `bin/ssc run-jvm tests/conformance/std-index.ssc`.
- **Observed:** INT prints the first two lines of `std-index` and then hits
  `StackOverflowError` in `EvalRuntime.evalApplyGeneral`/`DispatchRuntime.dispatch1`.
  JVM generated Scala rejects imports from `std/index.ssc` and sibling typeclass
  modules: `Left`/`Right` are imported from module objects that do not define them,
  and aggregate exports such as `std.intSum` are missing. Related full-gate misses:
  `std-foldable-traversable`, `std-functor-applicative-monad`, `std-index`,
  `std-bifunctor`, `std-monaderror`, and `std-selective`.
- **Status:** fixed in `f92d147b0` and `7328e35db`. Root causes were split
  across both lanes: INT extension dispatch preferred an imported same-named
  extension over built-in members, so `Option.map` in `map2Option` recursed;
  JVM import generation lost std typeclass re-export provenance, omitted
  standalone top-level extension imports, and emitted explicit context-bound /
  `using` instance calls as flat Scala calls. The std typeclass manifests also
  needed explicit type exports/imports so the strict import gate can resolve
  aggregator exports deterministically.
- **Verified:** `scripts/sbtc "backendJvm/compile"`;
  `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenUsingTest"`;
  `scripts/sbtc "installBin"`; direct INT/JVM repros for `std-index`,
  `std-selective`, `std-monaderror`, and `std-foldable-traversable`; and
  `tests/conformance/run.sh --only 'std-functor-applicative-monad,std-foldable-traversable,std-index,std-bifunctor,std-monaderror,std-selective' --no-memo`
  (**6/6 green**).

## conformance-int-sql-block-scope — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run --v1 tests/conformance/sql-basic.ssc`.
- **Observed:** SQL block interpolation fails with
  `[line 1, col 1] Undefined: newId` even though the preceding Scala block defines
  `val newId = 1L`; `sql-basic` and `sql-transaction` therefore produce missing
  stdout. JS/JVM are skipped by backend metadata.
- **Status:** fixed in `c31389b25`; root cause was the CLI backend path
  normalizing parseable fenced `scala` blocks to `ir.Content.EmbeddedBlock` and
  denormalizing them back to AST code blocks without a parsed tree. The interpreter
  therefore skipped those blocks, so globals such as `newId` / `personId` never
  existed when SQL bind expressions were evaluated. `Denormalize` now re-parses
  parseable embedded blocks (`scala`, `ssc`, `scalascript`) while keeping opaque
  foreign blocks tree-less.
- **Verified:** `scripts/sbtc "sqlPlugin/testOnly scalascript.compiler.plugin.sql.SqlPluginInterpreterTest"`;
  `scripts/sbtc "installBin"`; direct `bin/ssc run --v1 tests/conformance/sql-basic.ssc`
  and `bin/ssc run --v1 tests/conformance/sql-transaction.ssc`; and
  `tests/conformance/run.sh --only 'sql-basic,sql-transaction' --no-memo`
  (**2/2 green**).

## conformance-js-product-show-synthetic-tag — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run-js tests/conformance/prisms.ssc`.
- **Observed:** JS prints product values with an extra synthetic numeric field, e.g.
  `Circle(0, 5)` instead of `Circle(5)`, `Rect(1, 3, 4)` instead of `Rect(3, 4)`,
  and `User(0, bob, false)` in optics/optional cases. INT/JVM pass.
- **Status:** fixed in `4e8cbb635`; root cause was JS runtime product handling
  treating the internal `_tag` field as user data. `_show` now skips `_tag`, and
  positional `.copy(...)` maps arguments over user fields only (`_type` / `_tag`
  excluded), so enum tags remain available for pattern matching without leaking
  into display or copy semantics.
- **Verified:** `scripts/sbtc "installBin"`; direct
  `bin/ssc run-js tests/conformance/prisms.ssc` and
  `bin/ssc run-js tests/conformance/optic-polish.ssc`; and
  `tests/conformance/run.sh --only 'prisms,optic-polish,optics-index-at,optional' --no-memo`
  (**4/4 green**).

## conformance-js-json-stringify-missing-global — `fixed` (2026-07-08)

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run-js tests/conformance/json-read.ssc`.
- **Observed:** JS crashes before stdout with `ReferenceError: jsonStringify is not
  defined` at the first `jsonStringify(42)` call. INT/JVM pass.
- **Status:** fixed in `718d04027`; root cause was JS intrinsic registration still
  targeting bare `jsonStringify` / `jsonValue` after the runtime helpers were
  intentionally renamed to `_ssc_ui_jsonStringify` / `_ssc_ui_jsonValue` to avoid
  duplicate top-level declarations with std import bindings.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest"`;
  `scripts/sbtc "installBin"`; `bin/ssc run-js tests/conformance/json-read.ssc`;
  `tests/conformance/run.sh --only 'json-read' --no-memo` (**1/1 green**).

## conformance-jvm-cps-local-unit-effect-cast — `fixed` (2026-07-08)

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `tests/conformance/run.sh --only 'cluster-connect' --no-memo`.
- **Observed:** the JVM lane compiled and ran, but printed
  `unhealthy nodes: 3` instead of `unhealthy nodes: 0`.
- **Root cause:** a local actor loop declared as `def workerLoop(): Unit =
  receive { ... }` inside a CPS block emitted as
  `Actor.receive_(...).asInstanceOf[Unit]`. The cast discarded the unresolved Free
  computation before `runActors` could schedule it, so the worker actors exited
  immediately and never answered the health-check messages.
- **Fix:** `df7cfb613` makes local CPS-emitted defs follow the same result-type
  rule as top-level effectful defs: preserve the declared return type only when
  the def handles its own effects; otherwise return the unresolved computation as
  `Any`.
- **Verification:** `bin/ssc run-jvm tests/conformance/cluster-connect.ssc` prints
  `unhealthy nodes: 0`; the full targeted slice
  `tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`
  passes 6/6.

## conformance-jvm-cps-any-typing-and-effect-args — `fixed` (2026-07-08)

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`.
- **Observed:** the JVM lane failed during generated Scala compilation. Examples:
  `effect-transitive-handler` widened a handled value to `Any` before `+`;
  cluster/distributed cases widened `cluster` to `Any` before method calls; and
  `DatasetWirePartition(_t197, _t198)` passed `Any` where the constructor expects
  `Int` and `Vector[JsonValue]`.
- **Root cause:** the CPS transform only preserved explicit val ascriptions.
  Untyped vals bound from known dep constructors/defs were passed to
  continuations as `Any`; casts for known constructors did not include the JVM
  runtime `DatasetWirePartition` case; and effectful lambdas nested under call
  argument clauses could stay raw because effect detection only recursed through
  direct `Term` children and intrinsic dispatch emitted `.syntax` args.
- **Fix:** `df7cfb613` infers CPS val continuation types from known dep class/def
  result signatures, qualifies dep type names at generated call sites, adds the
  `DatasetWirePartition` external constructor signature, recursively detects
  effects through non-`Term` tree nodes, and routes effectful call args through
  CPS emission.
- **Verification:** `scripts/sbtc "backendInterpreter/compile"`,
  `scripts/sbtc "installBin"`, and
  `tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`
  pass.

## conformance-effects-choose-one-shot — `fixed` (2026-07-08)

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `scripts/conformance -- --only 'effects' --no-memo`.
- **Observed:** the INT lane printed only `Alice` and then failed with
  `One-shot violation: Choose.pick resumed more than once`; JS/JVM printed the
  expected nondeterminism list and passed.
- **Root cause:** `tests/conformance/effects.ssc` documented the `Choose` block as
  "Multi-shot: nondeterminism" but declared it as plain `effect Choose`. Per
  `specs/algebraic-effects.md` and existing interpreter tests, multi-resume
  handlers must opt in with `multi effect`.
- **Fix:** `edda7c5d3` changes the conformance declaration to
  `multi effect Choose`.
- **Verification:** `bin/ssc run --v1 tests/conformance/effects.ssc` prints all
  three expected lines, and
  `scripts/conformance -- --only 'effects' --no-memo` passes INT/JS/JVM.

## conformance-actors-exit-os-shadow — `fixed` (2026-07-08)

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run --v1 tests/conformance/actors-supervision.ssc` or
  `scripts/conformance -- --only 'actors-supervision' --no-memo`.
- **Observed:** the INT lane printed only `worker starting`; JS/JVM passed. A
  focused trace showed `link(worker)` registered but `exit(me, "crash")` never
  reached `ActorScheduler.killActor`.
- **Root cause:** lazy plugin loading registered `std.os.exit(code)` under the same
  bare global name as the core actor primitive `exit(pid, reason)`, overwriting the
  actor native. The OS intrinsic treated non-code argument shapes as `sys.exit(0)`,
  so the worker actor stopped the process path instead of sending an actor exit
  signal.
- **Fix:** `96bf969ed` keeps a pre-existing native binding as a fallback when a
  plugin intrinsic reports a usage mismatch, and changes `std.os.exit` to throw a
  usage error for non-`Int` arguments. A regression test loads actors+os plugins
  together and verifies actor `exit(pid, reason)` still drives supervision.
- **Verification:** `scripts/sbtc "backendInterpreterPluginTests/testOnly scalascript.ActorSupervisionTest"`
  passes 10/10; after `scripts/sbtc "installBin"`,
  `scripts/conformance -- --only 'actors-supervision' --no-memo` passes INT/JS/JVM.

## conformance-http-client-external-httpbin — `fixed` (2026-07-08)

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `scripts/conformance -- --only 'http-client' --no-memo`.
- **Observed:** the fixture calls live `https://httpbin.org`; the INT lane returned
  five `503` statuses instead of `200/204`, and the JS lane produced no stdout and
  stalled until interrupted. This is an external-network fixture, not a deterministic
  default conformance gate.
- **Fix:** mark `tests/conformance/http-client.ssc` as `pending:` with an explicit
  reason. Follow-up: replace it with a local deterministic HTTP fixture before
  re-enabling it in default conformance.
- **Verification:** `scripts/conformance -- --only 'http-client' --no-memo` reports
  `PENDING` and exits green without hanging.

## conformance-parsing-int-empty-output — `fixed` (2026-07-08)

- **Found by:** codex, while running a neighbor conformance slice for
  `v2-prod-js-dsl-conformance`.
- **Repro:**
  `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
- **Observed:** `collections` and `dsl-multi-pass` pass, but three INT-only parsing
  cases produce empty output:
  `parsing-error-node`, `parsing-parse-all`, and `parsing-recover-until`. JS/JVM are
  skipped for those cases by backend metadata, so this is not the JS char-ordering
  failure and not a v2 default output-parity blocker.
- **Scope:** std/parsing conformance hygiene. Fix before claiming broad repo-wide
  conformance green; do not mix with the v2 default-switch slice unless its gate
  explicitly requires these parser-combinator cases.
- **Root cause:** `std/parsing/recovery.ssc` documented and defined
  `recoverUntil`, `errorNode`, `parseAll`, `advanceToSync`, and `runParserAll`, but
  its front-matter `exports:` omitted those public names. The conformance files
  explicitly import `runParserAll` / `advanceToSync`, so INT failed during import on
  stderr before any `println`, which the conformance harness reported as missing
  stdout.
- **Fix:** `d65c678bd` exports the recovery extension methods and runner helpers
  from `std/parsing/recovery.ssc`.
- **Verification:** after `scripts/sbtc "installBin"`,
  `bin/ssc run --v1 tests/conformance/parsing-error-node.ssc` prints the expected
  eight lines; `scala-cli tests/conformance/run.sc -- --only 'parsing*' --no-memo`
  passes all three INT parsing cases; the neighbor slice
  `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
  passes 5/5 runnable cases with the two indent cases skipped for missing expected
  files.

## conformance-dsl-multi-pass-js — `fixed` (2026-07-08)

- **Found by:** codex, while verifying the docs-only `v2-prod-corpus-scope` slice.
- **Repro:**
  `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo`
- **Observed:** `dsl-multi-pass` passes the INT and JVM lanes but fails JS:
  expected line 2 `[name-resolve] undefined: z` and line 3 `ok: 8`, got
  `[parse] unrecognised token: x` for both. The failing source parses
  `"x + z"` / `"x + y"`; `parseExpr("x")` should produce `Var("x")`.
- **Scope:** JS backend/conformance lane. Not caused by the corpus-scope docs
  change and not a default output-parity blocker, but it is a production hygiene
  gate before claiming broad green status.
- **Hypothesis:** JS lowering/runtime mishandles the string-character predicate
  shape used by `t.forall(c => (c >= 'a' && c <= 'z') || c == '_')`, so alphabetic
  identifiers are rejected as parse errors.
- **Root cause:** JS `String.forall` passes boxed `_Char` values to the predicate, but
  `_arith` ordered `_Char` against a one-character JS string literal with native JS
  object-vs-string comparison. Equality already normalized `_Char`; ordering did not,
  so `c >= 'a' && c <= 'z'` was false for alphabetic characters.
- **Fix:** `39ebb6fda` adds a shared `_charCodeOrNull` helper and normalizes `<`, `>`,
  `<=`, and `>=` when either operand is `_Char`, while preserving normal string
  concatenation and ordinary string-vs-string comparison.
- **Verification:** after `scripts/sbtc "installBin"`,
  `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo` passes
  `dsl-multi-pass` in INT/JS/JVM. Neighbor slice
  `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
  confirms `collections` and `dsl-multi-pass` still pass; the remaining `parsing*`
  INT-only failures are tracked separately as `conformance-parsing-int-empty-output`.

## v2-rozum-schema-streaming-parity — `fixed` (2026-07-08)

- **Found by:** codex, during the v2 production parity loop after
  `v2-quoted-macro-interpreter-parity` was fixed.
- **Symptom:** the latest full
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` has **0
  v2-error** cases and only one remaining production-relevant blocker cluster:
  `examples/rozum-agent-schema-derived.ssc` and
  `examples/rozum-agent-streaming.ssc` still mismatch, while
  `rozum-agent.ssc` and `rozum-agent-pool.ssc` already match.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/rozum-agent-schema-derived.ssc examples/rozum-agent-streaming.ssc`.
  If needed, compare direct outputs:
  `bin/ssc run examples/rozum-agent-schema-derived.ssc`,
  `bin/ssc run --v2 examples/rozum-agent-schema-derived.ssc`,
  `bin/ssc run examples/rozum-agent-streaming.ssc`, and
  `bin/ssc run --v2 examples/rozum-agent-streaming.ssc`.
- **Notes:** decide by evidence whether this is a v2 bridge/server/batch bug or a
  scope classification issue. Do not normalize `scripts/v2-output-parity`; fix the
  real output path or document an explicit lane/scope exclusion.
- **Root cause:** two independent v2 bridge/runtime gaps. `AgentSchemaInstance` is a
  case class with a method body (`decode`), but v2 only dispatched its fields, so
  `schema.decode(argsJson)` returned `Stub` and the typed handler later matched on
  `Stub` instead of `Some`/`None`. Separately, FrontendBridge's constructor lowering
  dropped positional args whenever any named arg was present, so
  `AgentEvent("TextDelta", text = content)` produced `kind = Unit`; streaming callbacks
  ran but every `event.kind == ...` guard was false.
- **Fix:** `e80b1e70b` preserves positional constructor args in mixed
  positional/named case-class calls, adds `AgentSchemaInstance.decode` dispatch to the
  v2 runtime, and adds v2 regression tests for both shapes.
- **Verification:** after `scripts/sbtc "installBin"`, targeted parity
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/rozum-agent-schema-derived.ssc examples/rozum-agent-streaming.ssc`
  is **2/2 MATCH**; the full rozum cluster (`rozum-agent`, `rozum-agent-pool`,
  `rozum-agent-schema-derived`, `rozum-agent-streaming`) is **4/4 MATCH**. Affected
  conformance `scala-cli tests/conformance/run.sc -- --only 'rozum*' --no-memo`
  has **0 matching cases**. Full parity is now **60/81 identical · 5 mismatch ·
  0 v2-error · 16 v1-only**.

## v2-quoted-macro-interpreter-parity — `fixed` (2026-07-08)

- **Found by:** codex, during the v2 production parity sweep after content-toolkit
  section parity was fixed.
- **Symptom:** `examples/quoted-macro-interpreter.ssc` was a remaining production
  mismatch. v1 printed three lines (`42`, `literal: 7`, `x`), while v2 printed
  only `42` or, after registering the first helper, returned curried closures for
  the computed-body macros.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/quoted-macro-interpreter.ssc`.
  Direct check:
  `bin/ssc run examples/quoted-macro-interpreter.ssc` versus
  `bin/ssc run --v2 examples/quoted-macro-interpreter.ssc`.
- **Root cause:** the v2 run path used `MacroCodegen.expand` only for generated-
  backend-expandable macro bodies. That correctly left interpreter-only bodies
  (`x.asValue.getOrElse(...)`, `x.asTerm.name`) in helper form, but the v2 bridge
  did not register the v1 interpreter's helper globals (`__ssc_macro__`,
  `__ssc_quote__`, `Expr`, `QuotedContext`, etc.) or `Expr.asValue` /
  `Expr.asTerm` method dispatch. A second forward-reference bug meant an inline
  macro entrypoint that appeared before its `impl(...)(using QuotedContext)` helper
  converted before FrontendBridge knew the helper needed a synthesized `using`
  argument, so the impl call returned a curried closure.
- **Fix (387c804da):** `PluginBridge.registerInterpreterBuiltins` registers the
  restricted quoted-macro helper globals for v2 runs, `Prims.__method__` handles
  `DataV("Expr").asValue/asTerm`, `__resolve_given__` supplies the built-in
  `QuotedContext`, and FrontendBridge pre-records `using` metadata before converting
  top-level bodies.
- **Verification:** `bin/ssc run examples/quoted-macro-interpreter.ssc` and
  `bin/ssc run --v2 examples/quoted-macro-interpreter.ssc` both print `42`,
  `literal: 7`, `x`; targeted parity for `quoted-macro-interpreter.ssc` plus
  `quoted-macro-constfold.ssc` is **2/2 MATCH**; affected conformance
  `scala-cli tests/conformance/run.sc -- --only '*quoted*' --no-memo` has **0
  matching cases**; full
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` is now
  **58/81 identical · 7 mismatch · 0 v2-error · 16 v1-only**.

## v2-content-toolkit-section-parity — `fixed` (2026-07-08)

- **Found by:** codex, during `v2-prod-post-p3-baseline` full-corpus production
  parity verification.
- **Symptom:** the latest full `scripts/v2-output-parity --all` has exactly one
  v2-error: `examples/content-toolkit-yaml-controls.ssc`. The same content/toolkit
  section family also has a mismatch in `examples/content-slot.ssc`, where v2 prints
  an extra `Unsupported: TermSelectPostfixImpl` before the expected
  `content-slot:ok` line.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v2 examples/content-toolkit-yaml-controls.ssc` and
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/content-slot.ssc examples/content-toolkit-yaml-controls.ssc`.
- **Observed direct error:** `contentToolkitNode: table column builder 'fieldColumn'
  is not available — import it from std/ui/data (fcol/mcol/scol/dcol/lcol)`.
- **Root cause:** the v2 plugin `MinimalCtx` did not implement `resolveGlobal` or
  `invokeCallback`, so the real content plugin could not call imported toolkit
  builders such as `fieldColumn` while lowering inline YAML table columns. The
  sibling `content-slot.ssc` mismatch was a separate FrontendBridge lowering issue:
  `[bodyEl]` after the spaced infix operator in `headerParts ++ [bodyEl] ++
  footerParts` was not desugared as a list literal, so scalameta produced an
  unsupported `TermSelectPostfixImpl` node.
- **Fix (7dee6daf0):** `MinimalCtx` now resolves v2 globals and invokes v2/v1
  callbacks with value conversion; FrontendBridge treats spaced operator-following
  list literals as expression-position list literals and has a regression test for
  `xs ++ [y]`.
- **Verification:** `bin/ssc run --v2 examples/content-toolkit-yaml-controls.ssc`
  and `bin/ssc run --v2 examples/content-slot.ssc` both print only the expected
  `:ok` line; targeted parity for both examples is **2/2 MATCH**; `scala-cli
  tests/conformance/run.sc -- --only 'content*' --no-memo` passes **5/5**; full
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` has
  **0 v2-error** and now measures **57/81 identical · 8 mismatch · 16 v1-only**.

## v2-invoice-email-nondet — `fixed` (2026-07-08)

- **Found by:** codex, during `v2-prod-plugin-boundary` full-corpus production parity
  verification.
- **Symptom:** a full `scripts/v2-output-parity --all` run after the dataset stack fix
  reported one extra mismatch in `examples/invoice-email.ssc`: the generated artifact
  byte-count line differed (`2681` vs `2685`). An immediate targeted rerun matched, so
  this is treated as nondeterministic/generated-output exposure rather than a v2-error.
- **Repro:** after `scripts/sbtc "installBin"`, run targeted parity repeatedly:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/invoice-email.ssc`.
- **Suspect:** the example prints generated MIME/PDF byte length instead of stable
  semantic facts. Byte-exact generated artifacts can vary across runners and should not
  be the user-facing example contract for the v2 production gate.
- **Root cause:** the user-facing example contract exposed exact generated MIME/PDF
  message length. That length is not semantically important and can vary when the PDF
  renderer/MIME generator changes incidental bytes.
- **Fix (d8e0ecee4):** keep building the PDF and MIME message, but print a stable
  semantic line after confirming the message is non-empty:
  `MIME message assembled: PDF attached`.
- **Verification:** direct v1/v2 runs print the same stable line; repeated targeted
  parity for `examples/invoice-email.ssc` was **5/5 MATCH**; neighbor parity for
  `examples/invoice*.ssc examples/pdf-extract-demo.ssc` was **3/3 MATCH**. Conformance
  globs `invoice*`, `*pdf*`, and `*mime*` have **0 cases**, so there is no affected
  conformance case to run.

## v2-list-unlist-stack-overflow — `fixed` (2026-07-08)

- **Found by:** codex, during `v2-prod-plugin-boundary` production parity work.
- **Symptom:** `examples/dataset-parallel-sum.ssc` was the only remaining full-parity
  v2-error. Direct v2 execution crashed before stdout with `StackOverflowError`.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v2 examples/dataset-parallel-sum.ssc`.
- **Root cause:** `Prims.unlistPub` recursively converted ScalaScript `Cons/Nil`
  lists to Scala `List`; `Dataset.fromList(List.range(1, 100_001))` crosses that
  boundary with 100k elements and overflowed the JVM stack. `Prims.listOf` also used
  `foldRight`, which had the same large-list stack-risk in the opposite direction.
- **Fix (44f3d4a24):** `Prims.unlistPub` and `Prims.listOf` are iterative. The stale
  `dataset-parallel-int` expected snapshot was updated to match the numeric sorted
  output already produced by both v1 and v2 direct runs.
- **Verification:** `dataset-parallel-sum.ssc` parity is MATCH; `scala-cli
  tests/conformance/run.sc -- --only 'dataset*' --no-memo` passes **15/15**;
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/dataset*.ssc`
  has **0 v2-error**; full corpus has **0 v2-error**.

## v2-content-document-context — `fixed` (2026-07-08)

- **Found by:** codex, during the `v2-production-readiness` output-parity baseline.
- **Symptom:** `ssc run --v2` produced non-v1 output for structured Markdown content
  examples: `content-linked-namespaces.ssc` leaked a `Stub`/`Op` shape instead of the
  imported section title, `content-to-markdown.ssc` rendered empty content, and
  `content-tables.ssc` differed on the toolkit table value.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/content-linked-namespaces.ssc examples/content-tables.ssc examples/content-to-markdown.ssc`.
- **Root cause:** the v2 bridge populated only the current source document and then
  let batch content stubs override the real content plugin natives. The FrontendBridge
  import walk also did not register imported Markdown documents under
  `ContentImportedModules`, so `contentModuleSection` had no namespace table. After
  enabling the real content plugin path, bridged println still rendered
  `TableNode.sortCol` as `None` where v1 case-class output uses `null`.
- **Fix (146779cb6):** `PluginBridge.setDocumentFromSource` resets/seeds content
  document/current-section context, `FrontendBridge` registers imported content
  documents by namespace, content introspection/module/markdown natives use the real
  plugin path, and bridge display preserves the v1 `TableNode(..., null)` rendering.
  Only `contentToolkitSection` remains a selective batch stub until section-level
  toolkit lowering is fixed.
- **Verification:** `examples/content*.ssc` parity is **10/10 identical** (one
  v1 long-running skip), `scala-cli tests/conformance/run.sc -- --only 'content*'
  --no-memo` passes **5/5**, and the full parity gate is **54/88 identical ·
  10 mismatch · 1 v2-error · 23 v1-only**.

## plugin-lazyload-extern-imports — `fixed` (2026-07-07)

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
- **Fix (2026-07-07):** the essential/advanced .sscpkg split (fast startup) never
  wired the advanced set into any load path. Now: Main registers the
  `plugin-available/` dirs (`BackendRegistry.setAvailableDirs`) and the
  interpreter's LAZY `ensurePluginsLoaded()` (first missing name/extern) commits
  them via `BackendRegistry.loadAvailableNow()` (idempotent, best-effort per pkg).
  Startup stays fast; smtp/tcp/pwa/sql/auth/crypto… are reachable again. Verified:
  probes ([smtpSend](std/smtp.ssc), [tcpListen](std/tcp.ssc), bare tcpConnect
  extern), stock pwa-demo boots, `tkv2-pwa` conformance un-pended and green.
- **Note:** `tests/conformance/tkv2-pwa.ssc` covers the .ssc-level path;
  `PwaPluginTest` covers the generators.

## bytecode-shared-runtime-routes-unbound — `fixed` (2026-07-07)

- **Found by:** claude (green-main takeover), unmasked by fixing EmitScalaFacadeCliTest's missing
  `-Dssc.lib.path` (the CompilerLoader env error hid it).
- **Symptom:** `ssc compile-jvm --bytecode` fails: "shared runtime compile failed …
  `_ssc_runtime.scala:4931: Not found: _routes` / `Not found: route`" — `JvmGen.genRuntime`'s
  capability gating emits runtime code that references the route registry without emitting its
  definitions (route infra lives in the http/serve runtime piece; the gate combination for the
  bytecode shared runtime includes the referencing piece but not the defining one).
- **Repro:** `sbt 'cli/testOnly *EmitScalaFacadeCliTest'` — 5 of 7 fail on this (2 pass after the
  lib-path harness fix). Or any `compile-jvm --bytecode` invocation.
- **Impact:** the whole `--bytecode` separate-compilation happy path (compile-jvm/link facade family).
- **Root cause:** self-contained `JvmGen.genModule` always emitted either `serveRuntime` or
  `stubServeRuntime`, but split `JvmGen.genRuntime` omitted both when the unioned capability set did
  not contain `Serve`. The always-included common/effects runtime still references route/http/ws
  dispatch symbols, so the shared `_ssc_runtime.scala` could not compile for no-server bytecode
  artifacts.
- **Fix:** `83fc339e2` emits `stubServeRuntime` in split runtime when `Serve` is absent and adds a
  `JvmGen.generateRuntime(Set.empty)` regression test for `_routes`, `route`, `onWebSocket`, and
  `_httpDoRequest`.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JvmGenRuntimeSeparationTest"`;
  `scripts/sbtc "installBin"`; `scripts/sbtc "cli/assembly"`; `scripts/sbtc "cli/testOnly *EmitScalaFacadeCliTest"`;
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`.

## scalajs-jsenv-run-terminated — `fixed` (2026-07-07)

- **Found by:** claude (green-main takeover), root `sbt test` + serial retest.
- **Symptom:** Scala.js test modules (walletVaultEncryptedJs, walletStrategyErc4337Js,
  blockchainEvmAbiJs, markupNode, cryptoNobleJs, walletConnectJs) die in `loadedTestFrameworks`
  with `JSEnvRPC$RunTerminatedException` / `ExternalJSRun$NonZeroExitException: exited with code 1`
  — the node process exits immediately. Reproduces SERIALLY (not load-related) on node v26.4.0
  locally AND in CI (18 occurrences in the failed CI log).
- **Root cause:** the Node process was exiting because required npm packages were not installed in
  the module-local `node_modules` trees. The first concrete repro was `cryptoNobleJs/test` failing
  with `MODULE_NOT_FOUND: '@noble/ciphers/aes'`; the other failing Scala.js modules had the same
  manual-install assumption for their `package.json` dependencies.
- **Fix:** `1da48bfd5` adds an idempotent `npmInstallForScalaJsTest` sbt task and wires it into
  `Test / loadedTestFrameworks` for the npm-dependent Scala.js test projects, so clean worktrees and
  CI run `npm ci` automatically before the Scala.js test runner loads.
- **Verified:** `scripts/sbtc "cryptoNobleJs/test"`;
  `scripts/sbtc "walletVaultEncryptedJs/test; walletStrategyErc4337Js/test; blockchainEvmAbiJs/test; walletConnectJs/test; markupNode/test"`;
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`.
- **Repro:** `scripts/sbtc "cryptoNobleJs/test"` in a clean worktree with no
  `payments/crypto/noble-js/node_modules` previously failed before the fix.

## scjvm-artifact-cache-ignores-compiler-version — `open` (2026-07-07)

- **Found by:** claude (green-main takeover), while fixing jvmgen-block-call-empty-parens: after
  rebuilding `bin/ssc` with a codegen fix, `ssc run-jvm` kept producing byte-identical BROKEN output.
- **Symptom:** `run-jvm` reuses `<file-dir>/.ssc-artifacts/<name>.scjvm` whenever the SOURCE sha
  matches (`ModuleGraph.isJvmStale`) — the cache key ignores the compiler/binary version, so codegen
  fixes are invisible until the artifact is deleted by hand. Cost ~4 rebuild-and-scratch-head cycles.
- **Repro:** run any `.ssc` via `run-jvm` (caches the emission), change JvmGen, `installBin`, run
  again — the old emission runs.
- **Fix direction:** mix a compiler-build fingerprint (e.g. the backend-jvm jar's sha/mtime or a
  build-stamped version string) into the staleness check.
- **Workaround:** `rm -rf <dir>/.ssc-artifacts` after rebuilding the binary.

## jvmgen-block-call-empty-parens — `fixed` (2026-07-07)

- **Fixed by:** claude (green-main takeover), SHA: see `fix(jvm): conformance JVM-lane...` on main.
  Peeling the symptom exposed THREE stacked root causes; all four tests now PASS
  (`bin/ssc run-jvm` on signals / effects / rest-validate / distributed-map), with regression
  guards frontendSwiftUI 118/118 (widget `f() { block }` form preserved for curried callees) and
  backendInterpreter `*JvmGen* *Effect*` 193/193.
  1. **empty-parens** (signals, rest-validate): `JvmGen.emitExprDeep` emitted `f() { block }` for
     EVERY bare-name single-block call (fa5d3c821, Jun 2 — swiftui widgets). Broke single-thunk
     callees (`computed`/`effect`/`validate`/`runActors`). Now curried form only when
     depDefs/localDefSigs shows ≥2 param clauses. Exposed 2026-07-06 when bp2-2 enabled the
     warm JVM batch lane — the bug is older than the "suspect window" guessed below.
  2. **Console duplicate** (effects): the preamble's `object Console` println-shadow collided with
     a user `effect Console:` lowering. Preamble shadow is now omitted when the module defines its
     own top-level `Console` (JvmRuntimePreamble.sourceFor + collectUserTopNames bare-Stat arm);
     the effect-object lowering carries the println/print bridges instead.
  3. **premature declared-type cast** (effects, distributed-map): CPS-emitted defs whose ops are
     handled at the CALL SITE (`handle(greet())`) were cast to their declared type
     (`.asInstanceOf[String]`) while still holding an unresolved Free → ClassCastException
     `_FlatMap → String`/`DistributedResult`. Declared type is now preserved only for
     SELF-handling bodies (contains a `handle` form), extending codex's
     `preserveTotalEffectfulReturnTypes`.
  Diagnosis gotchas that cost cycles, recorded for the next agent: `.ssc-artifacts` scjvm cache
  (see the new open bug above), a stale sbt/bloop daemon from a deleted worktree answering builds
  (`scripts/kill-stale-builders --kill`), and macOS Xcode `strings` silently failing on JVM
  `.class` files (use `grep -ac` / `unzip -p … | grep -ac` instead).

## jvmgen-block-call-empty-parens — original report (was `open`, 2026-07-07)

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

## green-main-full-sbt-test-gating — `fixed` (2026-07-07)

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
- **Status:** fixed in `cea0c3aed`; the root `sbt "test"` gate is green.
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
- **Final verification (2026-07-08, `cea0c3aed`):** full `cli/test` is green
  (554 succeeded, 29 canceled, 0 failed), affected conformance
  `collections,dataset-from-file,dataset-shape,json-*,std-ui-*,tkv2-*` is green
  (19/19), and bounded root `scripts/sbtc "test"` is green
  (`[success] elapsed: 1668 s (0:27:48.0)`).

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
