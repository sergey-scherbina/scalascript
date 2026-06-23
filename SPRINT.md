# Sprint

Agent task queue — **active pending tasks only**. A task is "available" only if
`.work/active/<slug>.claim` does not exist on `origin/main`. Completed work lives in
`CHANGELOG.md`; open (not-yet-started) work lives in `BACKLOG.md`.

**Loop control** — pause: push `.work/paused` to `origin/main`; resume: remove it and push.
Start: tell the agent "go" / "работай". Status: ask "status" / "статус".

---

## Active tasks

Driven by the agreed roadmap (BACKLOG.md → "Roadmap — agreed priority order, 2026-06-17").
Work top-to-bottom, one major theme at a time. **Maven/centralized publication is LAST.**

### ▶ Promoted to active by Sergiy (2026-06-23 — "все эти задачи внеси в спринт")
Sergiy explicitly OVERRODE the deferred/backlog status of these four — they are now active sprint work, to be
done (each is genuinely codeable; the external parts are called out). Drive top-to-bottom.

- [ ] **coremin-actors-codemove** (A, big — multi-session refactor) — move the actor scheduler/cluster runtime
      out of `core` into the bundled `actors-plugin`, completing the coremin extraction. Scope (probed): it is an
      ATOMIC ~3500-LOC move — `ActorInterp.scala` (2904) + `ActorGlobals` (519) + `ActorWireProtocol` (55), all
      `private[interpreter]`-coupled and consumed via the `ActorRuntimeProvider` seam. Approach: the seam already
      lets the runtime live on either side, so the move is mechanical IF the `private[interpreter]` access is
      solved — either (a) widen the specific core internals the actor runtime needs into a small public/`spi`
      surface the plugin can call, or (b) keep a thin `private[interpreter]` shim in core and move only the
      self-contained scheduler/cluster logic. Prefer (a): enumerate exactly which core internals `ActorInterp`
      touches, lift them to a typed seam, then move the file. No user-visible change. **Verify:** `ActorDistributedTest`
      + `ActorBinaryWsTest` + the actor suites green on both sides of the move; core no longer references actor
      scheduler types. Do as a dedicated multi-session refactor; land in green sub-steps where possible.
      **Slice 0/1 plan (2026-06-23, codex):** dependency audit found direct runtime use of `out`,
      `callValue`/`callValue1`, `receiveSpecs` + receive guard/body `eval`, `nativeFeature*`, and distributed
      server/WS integration. First green slice is therefore a host-service seam: add explicit `ActorRuntimeHost`
      methods for the non-server services and lock them with provider tests, while keeping `runCoreActorRuntime`
      as the temporary delegate. Do not attempt the 3500-LOC file move in the same commit.

- [ ] **theme-a-stable-plugin-spi — Phase 3** (versioned stable API module) — Phases 1+2 landed (the stable
      surface exists: `PluginValue`/`PluginError`/`PluginComputation`/`JsonCodec`/`PluginContext` in
      `scalascript-plugin-api`; plugins depend only on it). Phase 3 = make it a **versioned, compatibility-gated**
      module: pick a SemVer line for the plugin API, add a stable-API version constant + a compat check at plugin
      load (a plugin built against API vX refuses/warns on an incompatible host), and a golden signature test
      that fails on any breaking change to the stable surface (mirrors `SpiVersion`/`spiRange` for the backend
      SPI). Spec: `specs/arch-stable-spi.md` Phase 3. **Verify:** golden API-signature test locks the surface;
      a deliberately-bumped version is rejected/warned at load; existing plugins still load.

- [ ] **remote-package-registry** (Tier 3 strategic — unlocks the 3rd-party plugin ecosystem) — the local story
      is done (`~/.scalascript/registry.yaml` + `pkg:` resolver + `ssc install`, `.sscpkg` archives). Build the
      REMOTE half: a registry protocol (publish/search/resolve/download a `.sscpkg` by name+version) + `ssc publish`
      / `ssc search` / remote `pkg:` resolution against a **configurable endpoint**, all buildable + testable
      against a local/mock registry server (no public hosting needed to land the code). EXTERNAL (separate, not a
      code blocker): standing up `registry.scalascript.io` (hosting/domain) is a deploy step done when ready.
      Spec: extend `specs/arch-build-registry.md`. **Verify:** round-trip integration test (publish → search →
      install → run) against an in-process/local registry; auth + version-pinning + checksum covered.

- [ ] **FROST-Ed25519** (threshold Ed25519 signing — wallet MPC stack) — implement the FROST flexible
      round-optimized Schnorr threshold signature scheme over Ed25519 in the wallet/MPC vault stack (alongside
      the existing MPC variants). Codeable now (the "concrete partner" deferral is overridden). Approach: add a
      `walletVaultMpcFrost` variant implementing FROST key-gen (DKG) + threshold signing producing standard
      Ed25519 signatures; reuse the `walletSpi`/MPC-vault seam the other variants use. **Verify:** key-gen +
      t-of-n signing produces signatures that verify against the aggregate public key with a standard Ed25519
      verifier; threshold/abort edge cases (insufficient shares, malformed share) covered; cross-check vectors if
      a FROST test vector set is available. Spec: `specs/` wallet-MPC section.

### ▶ Autonomous queue (2026-06-23, with Sergiy — "все кроме мавена — в спринт и делай")
When the clean autonomous coremin slices ran out (value-unification is sibling-active; NFC/wallet-ws are
device/browser-blocked; Maven publish is explicit-go only), Sergiy directed: queue everything except Maven
and execute autonomously. In priority order:

**▶▶ stable-SPI Phase 3 — FULL breakdown (2026-06-23, Sergiy: "делай Phase 3 автономно … заноси в спринт
сразу всё, потом делай постепенно").** GOAL: the **28** plugin `*Intrinsics.scala` that `import
scalascript.interpreter.{Value, InterpretError, Computation, …}` depend ONLY on the stable
`scalascript-plugin-api`, so a core/interpreter refactor (or a third-party plugin) can't break them, and the
build can reject any plugin jar containing `scalascript/interpreter/`. **PROBED FINDING:**
`PluginValue`/`PluginComputation` are opaque `Any` with NO accessors; `evalLegacy`'s own doc says full
Value-decoupling is "v2.x". So import-removal is GATED on a **Value-surface in the stable API** — it does NOT
come from `evalLegacy` (which only decouples the *context*). Cycle-checked: `pluginApi → core` is acyclic
(core deps = `valueData, backendSpi, …`, not pluginApi). Do gradually, one plugin/small-batch per slice, each
validated + pushed:
- [x] **p3-foundation** ✓ DONE 2026-06-23 — `scalascript-plugin-api` now `dependsOn(core)` (acyclic seam);
      `PluginValue` exposes stable extractors (`asString/asInt/asDouble/asBool/asChar/asList/asTuple/asMap/
      asOption`) + constructors (`string/int/double/bool/char/list/tuple/map/some/none/unit`) + `show`, backed
      by the interpreter `Value`; `PluginError` builds the real `InterpretError` + `raise(msg)`. PROOF:
      `mime-plugin` migrated off `scalascript.interpreter` end-to-end. `pluginApi/test` 14/0, `mimePlugin/test`
      4/0, `PluginExamplesSmokeTest` 1/0. The surface may need a few more accessors as later batches surface new
      shapes — extend `PluginValue` as needed.
  ~~- [ ] p3-foundation (original)~~ — expose a stable Value-surface through
      `scalascript-plugin-api` so plugins stop importing `scalascript.interpreter.Value`. DESIGN (decided):
      `pluginApi` gains a `core` dep = the ONE controlled seam (moves the coupling 28→1; opaque `PluginValue` +
      stable extractors/constructors keep the plugin ABI stable even as core's `Value` repr changes — e.g.
      value-unification). Add to `PluginValue`: extractors `asString/asInt/asDouble/asBool/asChar/asList/asTuple/
      asMap/asOption` + constructors `string/int/double/bool/list/tuple/map/unit/some/none` + `show`; keep
      `PluginError(msg)` (= InterpretError) + `PluginComputation.pure`. Stable bridges for the non-Value imports:
      `JsonParser`/`jsonToJson` → `JsonCodec` (exists) or a parser bridge; `OAuthBridge` (mcp/oauth) → a
      capability/stable surface. PROOF in this slice: migrate `mime-plugin` (simplest) end-to-end off
      `scalascript.interpreter`. VERIFY: `pluginApi` compiles with the core dep (no cycle); mime compiles with no
      `scalascript.interpreter` import + its tests green.
- [~] **p3-batch-A** (10 simple — Value-only): **mime ✓, pdf ✓ (8/0), fs ✓ (14/0)** (3/10). Remaining clean
      ones (Value-only, swap `Value.X`→`PluginValue.x`, drop import): auth, fetch, nfc, payments/crypto,
      payments/payment-request. NEED MORE WORK: **graph, yaml** use `Value.InstanceV` (need surface
      `instance(typeName, fields)`/`asInstance`) + `Value.OptionV` (need `option`), AND hold interpreter `Value`
      in their internal store (`GraphStore`/Y* records) → those store types must also move to `PluginValue`/`Any`
      (more than a swap). Extend `PluginValue` then migrate them.
- [ ] **p3-batch-B** (7 — Value + Computation): oauth, json (JsonParser→JsonCodec), dstreams, graphql, pwa,
      streams, ws. Adds `PluginComputation` usage.
- [ ] **p3-batch-C** (10 — ctx-heavy + special bridges): http (jsonToJson), mcp (OAuthBridge),
      remote (JsonParser+RemoteCap), content, frontend, request, smtp, sql (DbCap), uuid, os. Migrate ctx →
      capability traits (`evalLegacy`/`eval`) AND the value-surface.
- [ ] **p3-enforce** (after all 28 clean) — remove `PluginNative.evalLegacy`; add the build/CI check rejecting
      any plugin jar containing `scalascript/interpreter/`; delete residual shims; set `specs/arch-stable-spi.md`
      Status to "Phase 3 complete".

In priority order:
- [x] **autonomous-hardening** ✓ DONE 2026-06-23 — broad sweep of the coremin-affected surface (cli
      `ExamplesSmokeTest` + interpreter `StdEffectsTest`/`InterpreterTest`/`Actor*`/`*Effect*`/`Stream*`):
      **all green, 2/0 + 338/0, no new breakages.** The one real stale-example breakage (`algebraic-effects.ssc`
      ran `Undefined: runState` in the no-plugin cli smoke) was already caught+fixed in the advanced-optin turn.
      So the effect extractions + prelude minimization did not leave other regressions in the high-signal areas.
      (Did NOT run the ~20-min scala-cli `CrossBackendPropertyTest` — that's a codegen-vs-interp regression
      catcher, orthogonal to the coremin churn; siblings exercise it.)
- **coremin-actors-codemove** → PROMOTED to active 2026-06-23 (Sergiy "внеси в спринт") — see the "Promoted to
      active" queue at the top of Active tasks. (Probe context retained there: atomic ~3500-LOC move of
      `ActorInterp`+`ActorGlobals`+`ActorWireProtocol`, `private[interpreter]`-coupled via the `ActorRuntimeProvider`
      seam; prefer lifting the touched core internals into a typed seam, then moving the file.)
- [x] **strategic-theme-survey** ✓ DONE 2026-06-23 — surveyed BACKLOG strategic themes: the audit shows
      Themes A/E/F/H/J are ALREADY BUILT (FFI = `GlueClasspathRegistry`/`GlueJsPreambleRegistry` landed;
      modularity = `SsclibManifest` landed; stable-SPI Phases 1+2 landed). The only open strategic item is
      `remote-package-registry` (registry.scalascript.io), explicitly DEMAND-DRIVEN (build when a real external
      plugin author needs it — needs hosting/domain, not codeable autonomously). So no greenfield strategic
      slice is ready. Maven publication stays EXCLUDED per Sergiy.
- [x] **advanced-example-check-ux** ✓ DONE 2026-06-23 — concrete follow-up to advanced-optin: the 7 examples
      using advanced-plugin names (`x402-*`→payments, `oauth`/`oidc`→oauth) now `ssc check`-flag unless the
      plugin is added (verified: `undefined name: DefaultSyncBackend/basicRequest`). Added a uniform "Advanced
      plugin" note to each pointing at `--plugin`. Fence-lint + cli smoke 2/0.
- [x] **check-autoload-plugin-by-import** ✓ DONE 2026-06-23 (Sergiy: build it) — `ssc check` now auto-resolves
      advanced names when the file imports the plugin's namespace, no manual `--plugin`. SHIPPED: SPI
      `Backend.providesImports: List[String] = Nil`; payments→`scalascript.x402`, oauth→`scalascript.oauth`+
      `scalascript.oidc`, spark→`scalascript.spark` declare it. `importPrefixesOf(module)` extracts import refs
      from the ```scalascript code-block trees (`scala.meta.Import.importers.ref.syntax`) + doc-level
      `Content.Import`; `BackendRegistry.importMatchedPreludeSymbols(prefixes, availableDirs)` scans
      `lib/compiler/plugin-available` `.sscpkg` packages with a THROWAWAY `URLClassLoader` (non-matching plugins
      never committed to the runtime) and folds in matching `preludeSymbols`. Wired into `ssc check` (Main ~5293)
      AND `check-with-iface`; `-Dscalascript.pluginAvailableDir=` override for tests/custom layouts.
      **Verified end-to-end** against the real staged `payments-plugin.sscpkg`: `ssc check examples/x402-client.ssc`
      → `OK` (was `undefined DefaultSyncBackend/basicRequest`); still errors without the dir; `hello.ssc` unaffected
      (import-gated). `CheckAutoloadImportTest` 3/0, plugin-tests 712/0, cli smoke 2/0. The 7 advanced-example notes
      were updated to reflect the auto-detection. GOTCHA: Scala 3 nested comments — `/*` inside a `/** */` opens a
      nested comment (bit me in a test doc-string).

- [x] **board-spec-hygiene** ✓ DONE 2026-06-23 — reconciled stale core-min/polyglot board/spec wording.
      Updated `specs/polyglot-libraries.md` to the 2026-06-23 landed state, removed future-looking optics
      follow-ups from completed SPRINT entries now that JS/JVM/Rust/Java optics all ship, clarified that
      advanced opt-in prelude cleanup landed after `coremin-hybrid-split`, and changed old block-form template
      notes from "next work" to historical "later landed" wording. No code changed; active `core-min-value-unification`
      claim/worktree untouched.
- [x] **backlog-hygiene** ✓ DONE 2026-06-23 — docs-only classification pass for stale BACKLOG open items.
      Added a status-hygiene note to `BACKLOG.md`; marked `@wasmExport/@wasmImport` out-of-scope by design;
      converted history-only perf rows (`hof-glue-jit-compile`, `vectorize-pure-loop`, `direct-style-eval`) and
      `demand-driven-from-busi` to non-checkbox notes; consolidated duplicate `registry.scalascript.io` under
      `remote-package-registry`; and labelled the remaining intentional `[ ]` rows as `BLOCKED` or `DEFERRED`
      where appropriate. No code changed; active value-unification work untouched.

### ▶ Unblocked & claimable now (2026-06-22 eve, with Sergiy — "занеси в спринт всё что не заблокировано")

These need NO design decision — claimable immediately, in priority/tractability order. Full blueprints
live in the `polyglot-phase2-optics-allhosts` entry below (Task B = cross-language reuse, proven on the JS
slice). Each is one host of the optics-library packaging, individually claimable.

- [x] **polyglot-optics-board-hygiene** ✓ DONE 2026-06-22 — reconciled stale optics packaging entries at the top of `SPRINT.md`.
      **How:** compare the open `emit-lib-cli` / `polyglot-optics-jvm` entries here with the later completed
      `optics-emit-lib-cli`, `optics-jvm-facade`, `polyglot-optics-rust`, and `polyglot-optics-java` entries
      plus `CHANGELOG.md`; mark stale duplicates as done/superseded instead of letting agents re-claim already
      landed work. Do not touch implementation. **Verify:** grep shows no open `[ ]` optics packaging duplicate
      remains in the top claimable queue; active claims are unchanged.
- [x] **emit-lib-cli** ✓ SUPERSEDED/DONE 2026-06-22 — duplicate of the later `optics-emit-lib-cli` entry:
      `ssc emit-lib --host js --feature optics -o <dir>` is already user-reachable through `EmitLibCmd`
      (`EmitLibCmdTest` 2/2, README/user-guide updated).
- [x] **polyglot-optics-jvm** ✓ SUPERSEDED/DONE 2026-06-22 — duplicate of the later `optics-jvm-facade`
      entry: `emit-lib --host jvm` already emits the native Scala optics library with a compiled smoke and
      golden API coverage.
- [x] **polyglot-optics-rust** ✓ DONE 2026-06-22 (`f13427d4b`, mellow-shrew) — `RustLibPackager`
      (counterpart of Js/JvmLibPackager) emits a dependency-free `ssc-optics` Rust crate (Cargo.toml +
      src/lib.rs + README) via `emit-lib --host rust --feature optics`. lib.rs = faithful dynamic port of
      the JS/JVM optics over a `Value` enum (Obj/Arr/Opt/Str/Int/Bool/Null + `_type` sums): Lens/Optional/
      Traversal/Prism + steps field/index/at/some/each. `RustLibPackagerTest` 4/4: golden (file-set + API +
      dep-free) + a Rust-toolchain-gated cargo smoke (writes the crate + an integration test exercising all
      4 optics + `cargo test` — the emitted Rust compiles AND behaves). user-guide + README updated. 3rd of
      4 optics hosts; Java landed next, so all four hosts now ship.
- [x] **polyglot-optics-java** ✓ DONE 2026-06-22 (`09e174612`, mellow-shrew) — `JavaLibPackager` emits a
      dependency-free `ssc-optics` Java/Maven project (pom.xml + Optics.java + README) via `emit-lib --host
      java`. Optics.java = faithful Java 17 port over dynamic `Object` (Map/List/Optional/`_type` sums):
      Lens/Optional_/Traversal/Prism + steps. `JavaLibPackagerTest` 5/5: golden + emit-lib layout + a
      javac-gated compile/run smoke (exercises all 4 optics → 5/9/10/false/[1, 2]/true/false). **ALL FOUR
      optics hosts now ship: JS (npm) + JVM (sbt) + Rust (cargo) + Java (maven) — Task B optics COMPLETE.**

### ▶ JS-runtime + polyglot follow-ups (2026-06-22 eve, with Sergiy — "запиши в спринт все эти задачи и делай автономно")

Queued after the JS `.mjs`-resource cleanup + rename. Drive top-to-bottom (tractability order).

- [x] **optics-emit-lib-cli** ✓ DONE 2026-06-22 — `ssc emit-lib --host js --feature optics -o <dir>` writes the
      `@scalascript/optics` npm package (package.json + index.mjs + optics.d.ts) from `JsLibPackager`. New
      `EmitLibCmd` registered via the ServiceLoader `CliCommand` SPI; `EmitLibCmdTest` 2/2; README CLI row +
      user-guide section. The optics packager is now user-reachable (was test-only). More host/feature combos
      follow the same shape (see `optics-jvm-facade`).
- [x] **jvm-rust-runtime-resources** ✓ DONE 2026-06-22 (JVM + Rust; §3 #8 closed all backends) — mirror the JS `.mjs`-resource cleanup (polyglot §3 #8) for JVM
      (`JvmGenRuntimeSources`) + Rust (`RustRuntimeTemplates`). **PROBED 2026-06-22 (bright-quail) — NOT a clean
      mechanical copy like JS; more involved:**
      • **JVM** `JvmGenRuntimeSources.scala` (3656 lines): 13 runtime strings, each
        `JvmGenRuntimeCache.memo("key"): """|…|""".stripMargin` — plain (NOT interpolated) but **margin-based**,
        and lazily memo-cached. Migratable: strip the `|` margins → write the post-`stripMargin` content to a
        resource (a `.scala`-fragment file), replace body with `memo("key"): JvmRuntimeResource.load("key")`.
        Byte-identity = `stripMargin` output == resource (NOT a verbatim source copy like JS). Needs a new
        `JvmRuntimeResource` loader.
      • **Rust** `RustRuntimeTemplates.scala` (1570 lines): ~17 `stripMargin` strings (migratable, same shape) +
        **1 `s"""` INTERPOLATED** template (computed at runtime — CANNOT move to a static resource; leave it).
        Needs a `RustRuntimeResource` loader.
      • Scope: feasible + bounded per backend, but each string needs `stripMargin`-output verification and the
        win is smaller than JS (the `|`-margin source is already editable; gain = a real `.scala`/`.rs` file with
        no margin noise + lint/highlight). Do JVM and Rust as **separate slices**. NOT a one-shot mechanical
        sweep — budget per-backend. Spec: extend `specs/js-runtime-resources.md`.
- [x] **optics-jvm-facade** ✓ DONE 2026-06-22 (emit-lib --host jvm; native Scala optics lib, scala-cli-compiled; Rust crate + Java facade + typed/macro optics remain) — Phase 2 next host (`specs/polyglot-libraries.md` §4/§6): publish optics as a JVM
      jar facade + golden API-signature test. Optics has no `.ssc` defs (AST-level) → author a thin Scala facade
      object `Ssc.Optics` (or a `.ssc` facade) over the same 4 optic shapes; reuse `FacadeGenerator`/`ssc link
      --emit-scala-facade`/`JarCommands`. Golden: mirror the JS `optics.d.ts` golden with a Scala signature golden.
      Rust crate and Java facade later followed the same packager shape; all four optics hosts now ship.
- [x] **rust-multishot-unbounded** ✓ DONE 2026-06-23 — **Tier-3 UNBOUNDED (recursion)**: a `multi effect`
      performed inside recursion (dynamic depth) lowers via a Free-monad `MComp` builder (`fn __comp`) +
      multi-shot interpreter (`fn __run`, `resume(v)`→`__run(k(Value::from(v)))`, re-invokable `Rc<dyn Fn>`);
      runtime `MComp`+`and_then` in `runtime/effect.rs`. Recursive Amb `program(2)` → `4`, cargo-run;
      `backendRust` 252/0. + recursive/nested effectful-call reborrow fix (`&mut *_eff`). **Multi-shot effects
      on Rust are now COMPLETE for realistic programs** (Tier-1 List/Option, Tier-2 static-nested, Tier-3
      unbounded recursion). Follow-ups (additive, no consumer): loop-form unbounded, op-args/multi-op in Tier-3.
- [x] **rust-effects-multishot-r6** ✓ ACTIONABLE SCOPE DONE 2026-06-22 — bounded Rust multi-shot support is done:
      Tier-1 List (`effect-multishot` bench now runs on rust), Tier-1 Option, and Tier-2 static-depth general
      handlers all landed and cargo-ran (`RustGenMultiShotTest`: List, Option, 1-flip Amb, 2-nested-flip Amb).
      Unbounded **recursion** later landed too (`rust-multishot-unbounded`, 2026-06-23, Free-monad MComp); only
      the *loop* form (vs recursion) remains additive with no current consumer. No Rust code in this closeout.
- [x] **rust-multishot-r6-closeout** ✓ DONE 2026-06-22 — docs-only closeout for R.6 after bounded Rust multi-shot
      slices landed. Updated the detailed `rust-effects-multishot-r6` SPRINT entry to actionable-scope done and
      replaced the obsolete BACKLOG wording that said the Rust bench was unavailable; the only deferred work is unbounded
      perform-in-loop / explicit trampoline, with no current consumer.
- [x] **rust-multishot-board-reconcile** ✓ DONE 2026-06-22 — docs-only cleanup after R.6 Tier-2 nested/static-depth landed.
      The older open `[ ] rust-effects-multishot-r6` entry later in `SPRINT.md` is stale/duplicative: Tier-1 List,
      Tier-1 Option, and Tier-2 static-depth are all done; only unbounded perform-in-loop remains, explicitly
      additive with no current consumer. Marked the duplicate open entry as superseded by the detailed `[~]`
      status above; no Rust code touched. Verify: `rg -n "^- \\[ \\] \\*\\*rust-effects-multishot-r6" SPRINT.md`
      returns no matches.

### ▶ Newly queued (2026-06-22, with Sergiy — "бери все эти задачи если других нет, заноси в спринт")

Queued after closing rust-web-toolkit follow-ons + fixing the index-read move bug it shipped.

- [x] **worktree-guardrail** ✓ DONE 2026-06-22 (`bffef3447`, mellow-shrew, with Sergiy) — structural fix so
      feature commits can't land in the shared `main` checkout again (root cause of the parked-feature-branch
      mess: a prior session committed rust-web-toolkit directly in shared main instead of a worktree, partly
      due to the `EnterWorktree` false-positive, claude-code #27881). **`.githooks/pre-commit`** blocks a
      non-`.work/` commit when in the main checkout (`git-dir==git-common-dir`) OR on branch `main`; feature
      worktrees unaffected; `--no-verify` escape hatch. **`scripts/new-worktree <name>`** = external-path
      worktree recipe (NOT under `.worktrees/`, which siblings prune). **`scripts/setup-hooks`** sets
      `core.hooksPath`. Spec `specs/worktree-guardrail.md`; `scripts/test-worktree-guardrail` 5/5.
      **ACTIVATED** on the shared repo (`core.hooksPath=.githooks`) + verified live: a feature commit in
      shared main is refused, a `.work/` coordination commit passes. (Other clones: run `scripts/setup-hooks`
      once; worktrees off current `origin/main` already carry `.githooks/`.)

- [x] **rust-cargo-smoke-coverage** ✓ DONE 2026-06-22 (`2c8032a5c`, mellow-shrew) — `RustGenCargoSmokeTest`:
      a Rust-toolchain-gated suite (`assume(cargoAvailable)` — probes `cargo --version` directly, since
      `backendRust` doesn't depend on the CLI's `RustToolchain`) that emits a feature-exercising program
      to a temp crate, `cargo run`s it, and asserts real stdout. Covers collection ops (take/drop/
      takeRight/dropRight/sorted/distinct/sum), string ops (replace/startsWith/endsWith/contains), and
      the `Vec<String>` index-read regression (E0507). Closes the move/borrow/type bug class the
      string-match suite can't see. `backendRust` 236/0. BACKLOG `rust-backend-cargo-smoke-coverage` landed.

- [x] **metaprogramming-v2-track-c2** ✓ DONE 2026-06-22 (mellow-shrew, with Sergiy — CONSERVATIVE slice).
      Probed first: the full ambition (Typer over expanded code + map errors to `.ssc` positions) is a real
      trap — both expanders flatten trees→string→re-parse (positions destroyed; a position map would have to
      be built inside 4 hand-written char-scanners) AND full inference over expanded macro-runtime constructs
      risks false positives (confirmed; spec deferred it for good reason). Built the SAFE slice instead:
      `MacroCodegen.expansionTypeWarnings` (wired into `ssc check` `checkOneFile`) catches a macro/inline
      **expansion** that references an undefined name (source type-checks, expansion doesn't). **Zero false
      positives** via a pre/post `Reference to undefined name` DIFF (machinery cancels; user's own undefined
      names stay with the normal check); warning-only; file-level (no position map); excludes builtins/stripped
      names/`_`-helpers; never breaks `ssc check`. Reach is bounded by the strict Typer's position-sensitive
      undefined-name check (val-rhs/bare-stmt). `MacroCodegenTest` +5 (broken→1, valid const-fold/direct-quote/
      interpreter→0, no-op→0); core artifact+typer 496/0; verified end-to-end via `ssc check`. Spec
      `specs/arch-metaprogramming-v2.md` C2 updated. DEFERRED still: precise positions + full-inference recheck.

### ▶ emit-js whole-program effect analysis (2026-06-22, with Sergiy — "берись, запиши в спринт, напиши спеку, и делай") — busi-reported #3, transitive piece

Closes the last open piece of the emit-js effect-handler cluster (BUGS.md
`jsgen-emitjs-effect-handler`; #1/#2/#4 done, #3 core done on `6def53541`, #5
documented). Spec: **`specs/emitjs-effect-whole-program.md`**. The per-module
`EffectAnalysis` doesn't see effects reachable through a 3+-level import chain
(busi: `ledger.accountBalance` → `journal.query` → `Journal`), so a function
calling a transitively-imported effectful function isn't CPS-lowered and its Free
value leaks at runtime. Raw `emit-js` of such a program throws on Node; the JIT
path is fine.

- [x] **emitjs-effect-whole-program** ✓ DONE 2026-06-22 — busi `ledger.ssc` (+ obligation/plan/payment/gate/income) now run end-to-end as raw `emit-js` standalone bundles on Node; guard `tests/conformance/effect-transitive-handler.ssc` (3-level, INT==JS==JVM); busi `make v2-test`+`v2-test-js` + cross-backend green. (1) `JsGen.analyzeEffects` collects trees
      recursively across the import graph (reuse `genImport`'s resolution; parse
      once; visited-set for cycles) and runs `EffectAnalysis.analyze` on the union;
      (2) `effectOps`/`effectfulFuns`/`multiShotEffects` become shared constructor
      params threaded to child gens (like `topLevelConsts`), populated once by the
      entry gen's whole-program pre-pass; (3) drop the now-redundant per-`genImport`
      `analyzeEffects`+merge. Guard: `tests/conformance/effect-transitive-handler.ssc`
      (3-level, INT==JS==JVM) + `ssc emit-js tests/v2/ledger.ssc | node` runs e2e +
      `CrossBackendPropertyTest`/conformance/busi `make v2-test`+`v2-test-js` green.

- [x] **emitjs-standalone-frontiers** ✓ DONE 2026-06-22 (claude-code, `fix/js-standalone-frontiers`) —
      closes the three remaining busi standalone-bundle frontiers recorded under
      `jsgen-emitjs-effect-handler` so `tests/v2/{trust,qr}.ssc` now run end-to-end as raw
      `emit-js | node` bundles and `ksef.ssc` passes `node --check`. Three JS-codegen fixes +
      one refinement: (1) `Term.ApplyUnary` CPS-lowers an effectful operand (`!x`/`-x`) via
      `_bind` instead of `_run`-wrapping it outside the handler (fixes `trust.ssc`); (2) `_dispatch`
      routes `Array.fill/tabulate/range/empty` to the `List` companion since `Array(...)` emits a
      bare native-constructor value (fixes `qr.ssc`); (3) the 14 std/fs file-ops are seeded into
      `declaredBindings` so importing them never re-emits a colliding top-level `const readFile`
      (fixes `ksef.ssc` syntax); (4) refined the `fn-typed-field` `_dispatch` guard from a blanket
      "_type instance → return field as-is" to a precise variadic-lambda check, so genuine zero-arg
      methods (`JsonValue.asString`) auto-invoke again (`json-value` FAIL→PASS). Guards:
      `tests/conformance/{js-applyunary-effect-cps,array-companion-statics}.ssc` + the existing
      `fn-typed-field`/`json-value`. **Before/after emit-js+node sweep over all 113 conformance
      tests: zero PASS→FAIL regressions** (82→85 PASS); busi `make v2-test`+`v2-test-js` green
      (26 files, both backends).

- [x] **emitjs-standalone-capability** ✓ DONE 2026-06-22 (claude-code) — the follow-on frontier:
      emit `nowMillis` (clock) + crypto capabilities into the raw `emit-js` standalone bundle so
      `inbox`/`ksef`/`repo*` run under `ssc emit-js | node`. Two bugs (see BUGS.md
      `jsgen-emitjs-capability-standalone`): (1) a `RuntimeCall` intrinsic (`nowMillis`→`Date.now`)
      reached via the CPS path wasn't rewritten — `genCpsApply` now applies it (new helper
      `intrinsicRuntimeTarget`); (2) a `std/crypto` extern (`sha256`) bound to the `undefined` host
      stub and shadowed its `_sha256` intrinsic — `genObjectAsExpr` now falls back to the intrinsic
      target (guarded by `typeof` + `target != fname` so std/auth's identity webauthn externs don't
      self-reference→TDZ). Standalone emit-js+node sweep **13/21 → 20/21** v2 domain files; guards
      `tests/conformance/{js-cps-intrinsic-rewrite,js-crypto-extern-standalone}.ssc` (INT==JS);
      before/after conformance sweep **zero PASS→FAIL** (84→84); busi `make v2-test`+`v2-test-js`
      green. **Remaining:** `auth.ssc` standalone needs Node WebAuthn impls (host-only externs, no
      `_webauthn*` preamble) — a separate feature, not a capability-emission gap.

### ▶ Core-minimization + polyglot-libraries program (2026-06-22, with Sergiy — "минимизировать ядро всех рантаймов и компиляторов, все вынести в библиотеки и плагины" + "сделать все переиспользуемым со всех рантаймов — из скалы, джавы, джаваскрипт, раста — в виде библиотек, сначала написать спеку")

Two complementary directives, ONE program. **Design spec written: `specs/polyglot-libraries.md`**
(grounded in a full core-vs-plugin extraction analysis). A self-contained module is the unit of reuse:
extract a feature behind the SPI (A) → publish it as a per-host library (B) is the same artifact.

**DECIDED DIRECTION (2026-06-22, with Sergiy — "вынести в плагины всё что возможно"; spec §7a):**
**B→A (enabler-first)**; language forms + hot-path stdlib stay core **forever**; **hybrid** distribution
(essential plugins bundled, advanced opt-in via `pkg:`). Task sequence:

- [x] **coremin-prelude-spi** ✓ KEYSTONE DONE 2026-06-22 (`0ef0bde11`, mellow-shrew) — the SPI hook so a
      plugin declares its check-time public symbols WITH type-signatures and `ssc check` resolves AND
      type-checks calls to them, no hardcoded core list. Decided shape: names+full signatures. Reuse, don't
      invent: `ExportedSymbol` already encodes typed symbols; `InterfaceScope.parseSType`/`parseKind`
      (made `private[scalascript]`) invert `SType.show`. **`Backend.preludeSymbols: List[ExportedSymbol]`**
      (chose the flat symbol list over a full `ModuleInterface` wrapper — no magic/abiVersion/sourceHash
      boilerplate); Typer gains a `preludeSymbols` ctor param → `createPrelude` defines each with its declared
      type (not the untyped `variadic`); `ssc check` (`Main.scala`) collects
      `BackendRegistry.inProcess.flatMap(_.preludeSymbols)` + threads it in; `pluginBuiltins` (names-only) kept
      as fallback. Additive/no-op when empty. Proof `TyperPreludeSymbolsTest` (without→undefined; with→resolves;
      declared type flows — return-mismatch flagged, correct call passes); typer+artifact 499/0. Spec
      `specs/core-min-prelude-spi.md`. NOTE: hook lives at the Typer/`check` layer only (codegen backends are a
      separate concern).
- [x] **sprint-stale-open-items-reconcile** ✓ DONE 2026-06-22 — reconciled stale open items that are already superseded/done.
      **How:** mark `coremin-prelude-migrate-ORIG` as superseded by the immediately preceding
      `coremin-prelude-migrate` finding, and mark `polyglot-phase2-optics-allhosts` as complete because
      JS/JVM/Rust/Java optics hosts now all ship (`optics-emit-lib-cli`, `optics-jvm-facade`,
      `polyglot-optics-rust`, `polyglot-optics-java`). Do not change code. Leave genuinely open items
      (`coremin-actors-migrate`, `coremin-hybrid-split`, `core-min-phase3plus`, etc.) untouched.
      **Verify:** grep shows no open `[ ]` entries for `coremin-prelude-migrate-ORIG` or
      `polyglot-phase2-optics-allhosts`; active claims remain unchanged.
- [x] **coremin-prelude-migrate** ✓ ACTIONABLE SCOPE DONE 2026-06-22 — bundled-effect runner prelude migration is complete: 16 bundled-effect runner names moved from the hardcoded Typer prelude into plugin `preludeSymbols`, and the unused typed `runnerType2` helper was removed. This closes the safe actionable scope for this item. Remaining prelude work is split into separate items: advanced/non-bundled `pluginObjects`/`pluginBuiltins` strict opt-in via complete plugin `preludeSymbols`, plus Stream/Actors runner extraction.
  **UPDATE 2026-06-22: finding (2) partially DISPROVED for VARIADIC runner names.** `runRandom` (proof, `754139832`) + a batch of 6 more (`runRetry`/`runRetryNoSleep`/`runCache`/`runCacheBypass`/`runClock`/`runEnv`) now migrate cleanly off `effectBuiltins` into their plugins' `preludeSymbols` — a variadic block-form runner needs NO effect-type to travel (it types as `def … : Any`), so it does NOT wait on `coremin-effecthandlers-spi`. **7 bundled-effect runner names now off the core prelude; locked by `PreludeMigratedRunnersTest` (668/0).** STILL blocked: the NON-bundled `pluginObjects`/`pluginBuiltins` names (→ `coremin-hybrid-split`). Remaining bundled variadic runner candidates: audit `effectBuiltins` for any not-yet-migrated (e.g. `runStorage`/`runTx`/`runActors`/`runAsync` — only if their plugin is default-bundled AND the keyword is variadic).
  **UPDATE-2 2026-06-22: finding (2) FULLY DISPROVED for bundled runners — even the TYPED ones migrate.** `runRandomSeeded`/`runClockAt`/`runEnvWith` (formerly `runnerType2` `s.define`s) are now in their plugins' `preludeSymbols` too. The unlock: the typer does **NOT enforce effect discharge** (no "unhandled effect" diagnostic anywhere in `lang/core/.../typer/`), so the runner's `! Eff` row is tracked-but-not-checked → declaring the name `Any` is sufficient for `ssc check`; the interpreter resolves the runner via the plugin's block-form, not the typer type. So typed runners do NOT wait on `coremin-effecthandlers-spi` after all. **Production-soundness CONFIRMED:** `installBin` stages all of `allPlugins` (effect plugins included) onto the shipped classpath, so `BackendRegistry.inProcess.flatMap(_.preludeSymbols)` loads them in the real `ssc check` (the `cli/run` compile classpath lacking them is a dev-only artifact). **10 bundled-effect runner names now off the core prelude** (`runRandom` + 6 variadic + 3 typed); `PreludeMigratedRunnersTest` 671/0.
  **UPDATE-3 2026-06-22: SWEEP COMPLETE — the last 6 bundled runners migrated.** `runLogger`/`runLoggerJson`/`runLoggerToList` (logger-plugin), `runState` (state-plugin), `runHttp`/`runHttpStub` (http-plugin) are now in their plugins' `preludeSymbols`; the now-unused typed `runnerType2` prelude helper was removed (`runnerType` stays for `runStream`). **16 bundled-effect runner names total are off the core prelude; only `runStream` remains** (owned by `coremin-stream-migrate`). Verified runtime-unaffected: `StdEffectsTest` runs `runHttp`/`runState`/… end-to-end (15/0). `PreludeMigratedRunnersTest` locks all 15 migrated runners (677/0). **This sub-thread of `coremin-prelude-migrate` (bundled effect runners) is now DONE.** Remaining prelude work is entirely on the OTHER two axes: NON-bundled `pluginObjects`/`pluginBuiltins` names (→ `coremin-hybrid-split`) and the Stream/Actors runners (entangled, separate SPI additions).
  **UPDATE-4 2026-06-23: runStream prelude name MIGRATED — the runner prelude axis is now 100% (`coremin-stream-prelude-migrate`).** `runStream` + the `Stream` object moved from the hardcoded Typer prelude into `StreamsInterpreterPlugin.preludeSymbols` (`ExportedSymbol("runStream","runStream","def","Any")` + `("Stream","Stream","object","Any")`); the now-dead `runnerType`/`bodyWithEff` typer helpers were removed (core compiles strict `-Werror`). This is the **prelude-name** axis only — Stream's RUNTIME (Free-monad driver + `tryStreamEmitWhileFast` FastTier + `installStreamGlobal`) stays in core per `coremin-stream-migrate` (a `BlockForm` only sees `SpiValue`, no AST). streams-plugin is bundled (installBin stages it; META-INF/services Backend provider) → production `ssc check` resolves via `BackendRegistry.inProcess`. `PreludeMigratedRunnersTest` now locks 16 runners incl. `runStream` (16/16). **NO effect-runner name is hardcoded in the core Typer prelude anymore.** (Pre-existing unrelated failure observed: `StreamsPluginInterpreterTest` "runStream result supports runForeach" — `var buf` captured in `runForeach` loses the first emission; fails on clean origin/main too → filed separately as a runtime var-capture bug, NOT introduced here.)
  **UPDATE-5 2026-06-23: ACTORS keyword set + ADVANCED-OPTIN prelude names DONE — the prelude is now fully minimized.** (a) actors-prelude (`2d9b02588`): ~55 actor/process/cluster keywords → `ActorsInterpreterPlugin.preludeSymbols`. (b) advanced-optin (Sergiy chose "strict opt-in for advanced names"): the hardcoded `pluginObjects`/`pluginBuiltins` PLUGIN-owned names moved to their owning plugins' `preludeSymbols` by tier — essential (Source→streams, setHttpServerBackend→ws, http→http; auto-loaded, no UX change), advanced (oauth/oidc→oauth, Wallets/X402*/Cardano*/PaymentConfig/DefaultSyncBackend/basicRequest→payments, spark/PipelineModel→SparkBackend; resolve only via `--plugin` = strict opt-in). `pluginObjects` deleted; `pluginBuiltins` 21→11 (only interpreter-core globals Async/Await/Signal/Future/Storage + stdlib-.ssc HandlerRegistry/Cluster/ShuffleStage/Stage/runDistributed/runDistributedShuffle remain — no owning compiled plugin). `AdvancedOptInPreludeTest` (710/0). **Caught+fixed a PRE-EXISTING regression**: `algebraic-effects.ssc` (uses runState/runLogger/… = extracted plugins) was still in the cli core-smoke `runnableExamples` (no plugins) → failed at runtime `Undefined: runState` since the first effect extraction; moved it to `PluginExamplesSmokeTest`. **The Typer prelude `effectBuiltins` (language forms + not-yet-extracted runners runAsync/runAuthWith/runStorage/runTx/httpClient/async-primitives + test helpers) and `pluginBuiltins` (11 core/stdlib names) are now the irreducible hardcoded remainder** — everything plugin-owned is declared by its plugin. LESSON: run the cli `ExamplesSmokeTest` after ANY effect extraction (effect examples become plugin-backed, the cli smoke interp has no plugins).
- [x] **coremin-prelude-board-closeout** ✓ DONE 2026-06-22 — docs-only closeout for `coremin-prelude-migrate`
      after UPDATE-3. Marked the actionable scope done, kept future work explicit under the advanced strict
      opt-in and Stream/Actors entries, and added the `CHANGELOG.md` note. No Typer/plugin code changed.
      **Verify:** grep shows no open `[~] coremin-prelude-migrate` and no open
      `[ ] coremin-prelude-board-closeout`; conflict-marker grep is clean.
- [x] **coremin-prelude-migrate-ORIG** ✓ SUPERSEDED 2026-06-22 — original blind-migration plan is superseded
      by the `coremin-prelude-migrate` finding above. The original blocker framing is now stale:
      `coremin-hybrid-split` landed, bundled-effect runner typing proved unnecessary for plugin
      `preludeSymbols`, and the remaining prelude work belongs to separate advanced strict opt-in and
      Stream/Actors tasks. Do not re-claim this original plan as-is.
- [x] **coremin-http-migrate** ✓ DONE 2026-06-22 (`f8f9ac4d3`, mellow-shrew) — the Http effect runner
      (`runHttp` real I/O + `runHttpStub(routes)` stub) extracted from interpreter core into the
      already-bundled `http-plugin`'s `blockForms` — 8th effect off core. Two new SPI capabilities:
      `BlockContext.makeRecord` (handler replies with a `Response` record) + `BlockContext.featureLocal`
      (handler reads the base-url/timeout/retry config the core `httpClient(baseUrl)` form sets).
      `HttpEffectRunner` ports the java.net request logic (Option-based). Removed from core: EvalRuntime
      cases + 2 `reservedApplyHeads` + `EffectHandlers.httpRun`/`doHttpRequest`. `httpClient(baseUrl)` setter
      stays core by design. Tests moved StdEffectsTest→HttpEffectPluginTest (4/4, lazy ServiceLoader);
      StdEffectsTest 15/15. NOTE follow-up: `Interpreter.mkHttpCtx` now dead (minor cleanup).

- [x] **coremin-actors-board-reconcile** ✓ DONE 2026-06-22 — collapsed duplicate open `coremin-actors-migrate` entries.
      **How:** keep one actionable actors item that states the real blocker (scheduler/message-loop seam)
      and mark the older duplicate as superseded; do not touch code or claim the actual actors migration.
      **Verify:** grep shows exactly one open `[ ] **coremin-actors-migrate**` in `SPRINT.md`.
- [x] **coremin-actors-migrate** ✓ SUPERSEDED 2026-06-22 — duplicate of the more precise
      `coremin-actors-migrate (A, entangled)` item below; keep that one as the single open actors entry.
- [x] **coremin-effecthandlers-spi** ✓ RECONCILED → SUBSUMED 2026-06-22 (mellow-shrew). The "3rd keystone
      hook" turned out already covered by the **block-form SPI** (the 1st keystone): a plugin owns a custom
      effect's `Perform` resolution via `Backend.blockForms` (`BlockForm.effectName` + `EffectHandler.reply`),
      dispatched through the core `runWithHandler` trampoline — proven by **8 effects** migrated this way
      (Logger/Random/Clock/Env/State/Retry/Cache/Http). The capability set is complete: stateful per-op reply,
      config args (`newHandler`), closure-apply (`applyFn`), record-build (`makeRecord`), feature-local-read
      (`featureLocal`), result-combination (`result`), stdout (`out`). No separate hook needed.
- [x] **coremin-stream-migrate** ✓ ACTIONABLE SCOPE CLOSED 2026-06-22 — investigated and deliberately deferred; the Stream effect stays in core for now because extraction is low-ROI without a clean consumer for new SPI.
      `runStream` has a **FastTier** (`tryStreamEmitWhileFast`, AST-level `while … Stream.emit` bypass of the
      Free-monad trampoline — zero-FlatMap fast path) that is interp-internal and CANNOT move to a plugin
      (a `BlockForm` only sees `SpiValue` replies, no AST). So a migration is necessarily *partial*: the
      ~40-line `streamRun` handler could move (it'd need a new trampoline **terminate-signal** SPI for
      `Stream.complete/error` short-circuit + `BlockContext.callGlobal` for `Source.from`), but the
      `runStream` case + FastTier + `installStreamGlobal` stay in core. ~40 lines shrunk for real complexity +
      a shared-trampoline change → not worth it. The two new SPI capabilities (terminate-signal + callGlobal)
      are designed + validated (runWithHandler: a resolver returning `Pure(term)` abandons the body) — add
      them only when a clean consumer appears. No code changed for this closeout.
- [~] **coremin-actors-migrate** (A, entangled) — SPEC + PROVIDER SEAM LANDED 2026-06-22.
      `specs/coremin-actors-plugin.md` (`6538c10c6`) defines the interpreter-local actor runtime seam.
      `ea898ca82` adds `ActorRuntimeProvider` / `ActorRuntimeHost`; `ActorInterp.actorInterp` now dispatches
      through `CoreActorRuntimeProvider`, which delegates to the existing core scheduler, so behavior is unchanged.
      `539105e3c` adds the essential bundled `runtime/std/actors-plugin` skeleton, ServiceLoader descriptor,
      provider installation via `ActorRuntimeProviderBackend`, actor `preludeSymbols`, and
      `ActorsPluginProviderTest` (2/0). `cli/installBin` passed and now stages 26 essential `.sscpkg` files
      plus 13 advanced.
      Verified: `backendInterpreter/compile` passed; actor targeted suites
      (`ActorSupervisionTest`, `ActorStopOutsideTest`, `ActorGroupTest`, `ActorDistributedTest`) passed 29/0
      (ScalaTest printed a reporter `InterruptedException`, but sbt finished `[success]`).
      **PRELUDE-NAMES SLICE DONE 2026-06-23 (this session):** the ~55-name actor/process/cluster keyword set
      (`runActors` + spawn/self/send/receive/timeout/recvFrom + membership/leader/gossip/config/drain/metric +
      timers) is now removed from the Typer `effectBuiltins` and DECLARED in `ActorsInterpreterPlugin.preludeSymbols`
      (bundled → production `ssc check` resolves via `BackendRegistry.inProcess`; runtime stays in core via the
      seam, so `spawn`/`self`/… still resolve through `ActorInterp`/`ActorGlobals`). Verified runtime-unaffected:
      `ActorDistributedTest`+`ActorBinaryWsTest` 53/0; `ActorsPreludeMigrationTest` locks a representative name per
      category; typer 196/0, plugin-tests 693/0. `effectBuiltins` now holds only language forms + the not-yet-bundled
      runners (runAsync/runAuthWith/runStorage/runTx/httpClient/async primitives) + test helpers.
      **SESSION-SEAM SLICE DONE 2026-06-23:** `ActorRuntimeProvider` now opens a per-host
      `ActorRuntimeSession`; `ActorInterp` lazily caches one session per `Interpreter` and clears it when a
      replacement provider is installed. This records the state ownership boundary before any future runtime code
      move, without moving scheduler code today. Verified:
      `cd /Users/sergiy/work/my/scalascript-wt-core-min-phase3plus && sbt "actorsPlugin/compile" "backendInterpreter/compile" "backendInterpreterPluginTests/testOnly scalascript.ActorsPluginProviderTest"`
      passed 3/0, and `cd /Users/sergiy/work/my/scalascript-wt-core-min-phase3plus && sbt "backendInterpreter/testOnly scalascript.ActorSupervisionTest scalascript.ActorStopOutsideTest scalascript.ActorGroupTest scalascript.ActorDistributedTest scalascript.ActorBinaryWsTest"`
      passed 53/0 (known ScalaTest reporter `InterruptedException`, sbt `[success]`).
      **Remaining (the hard code-move, optional):** move `ActorRuntime`, scheduler loop, `handleActorOp`, and
      cluster/event drains behind the provider into `runtime/std/actors-plugin`; keep `receive` syntax capture in
      core. **Gotcha:** do not store actor/cluster mutable state on the ServiceLoader backend singleton; today's
      state is per `Interpreter`, so the move slice needs per-host/per-interpreter state ownership. This code-move is
      a large interpreter-internal refactor with NO user-visible change (the seam already lets the runtime live
      either side); deferred as low-ROI like Stream. **Net: the coremin prelude + extraction program is at its
      practical end — all bundled effects + actor names off core, hybrid-split done; only the optional Stream/Actors
      interpreter-internal code-moves remain, both deliberately deferred.**
- [x] **coremin-hybrid-split** ✓ DONE 2026-06-22 (codex) — no-domain hybrid plugin distribution slice.
      `PluginSpec` now carries an essential/advanced tier; `installBin` stages 25 essential bundled
      `.sscpkg` files in `bin/lib/compiler/plugins` (auto-loaded) and 13 advanced bundled `.sscpkg`
      files in `bin/lib/compiler/plugin-available` (opt-in via `ssc --plugin <path>` or
      `ssc plugin install <path>`). No registry domain or hosting required. This slice deliberately did NOT remove
      Typer hardcoded advanced compatibility names; that strict opt-in prelude cleanup later landed in
      `advanced-optin` (2026-06-23). Verification: `cd /Users/sergiy/work/my/scalascript-wt-coremin-hybrid-split && sbt "cli/compile"` passed in 82s; `cd /Users/sergiy/work/my/scalascript-wt-coremin-hybrid-split && sbt "cli/installBin"` passed and produced the two directories/counts above. Bonus guardrail: `installBin` now fails if the explicit `pluginPkgs` list is missing or duplicating an `allPlugins` id; this caught and fixed the pre-existing omission of `fs`/`os`/`yaml` from staged `.sscpkg` files.

- [x] **polyglot-libraries-spec** ✓ SPEC CLOSED 2026-06-22 — `specs/polyglot-libraries.md` now reflects that the
      original draft has implementation slices landed. It unifies A (minimize core) + B (cross-language reuse);
      the original baseline found ~6–7.5K LOC of feature code still baked into interpreter core, but since then
      the block-form SPI, typed `SpiValue`, plugin `preludeSymbols`, multiple effect migrations, JS runtime-resource
      extraction, and no-domain bundled plugin distribution split have landed. Remaining implementation work is
      tracked by separate active/deferred items (`coremin-actors-migrate` optional hard code-move,
      `core-min-value-unification` deep value refactor).
- [x] **core-min-phase1-logger-keystone** (A — the SPI keystone) ✓ KEYSTONE PROVEN END-TO-END 2026-06-22. The
      block-form + effect-handler plugin SPI now works: a plugin can contribute a `keyword { body }` effect-runner
      and the interpreter dispatches to it. 5 increments on origin/main: (1) `c2eec8d3c` generic effect trampoline
      `EffectHandlers.runWithHandler`; (2) `f2d8b5304` SPI contract `BlockForm`/`EffectHandler`/`BlockContext`;
      (3) `7dc508c3b` made it **type-safe** — a host-neutral `SpiValue` ADT instead of `Any` (per Sergiy's review);
      (4) `af58335bc` interp wiring — `valueToSpi`/`spiToValue`, a `_blockForms` registry populated by
      `installPlugins`/`ensurePluginsLoaded`, and an `EvalRuntime` generic block-form case; (5) `0a578ab88` **proof**:
      `reservedApplyHeads` fast-path also excludes `interp.blockForms` names so a plugin keyword reaches the
      dispatch (empty until a plugin loads → plugin-free scripts unchanged). `BlockFormSpiTest`: a `runTally { }`
      plugin block-form + stateful handler → `25`, Int args/replies round-tripped `Value↔SpiValue`. **No
      regression** (StdEffectsTest 48/0, InterpreterTest 141/0). Historical follow-up status: the template was
      used for Logger/Random/Clock/Env/State/Retry/Cache/Http; actors use the separate provider/session seam
      because they own a scheduler rather than a simple block-form handler.
- [x] **core-min-logger-migrate** (A) — ✓ DONE 2026-06-22 (`0353e51ae`). Logger fully extracted from
      interpreter core into `runtime/std/logger-effect-plugin` (`LoggerEffectPlugin extends Backend` with
      `blockForms = Map(runLogger→text, runLoggerJson→json, runLoggerToList→collect-with-`result`-tuple)`,
      handlers over `SpiValue`/`ctx.out`) + `META-INF/services/scalascript.backend.spi.Backend`; build.sbt wired
      via the `allPlugins` registry (`PluginSpec("logger", …)` → auto aggregate + `installBin` + plugin-tests
      classpath). Removed from core: 3 `runLogger*` cases + the 3 names in `reservedApplyHeads` (`EvalRuntime`),
      `loggerRun`/`loggerToListRun`/`loggerJsonStr` (`EffectHandlers`; generic `runWithHandler` stays). The 4
      Logger tests moved `StdEffectsTest`→`LoggerPluginTest` (`interpreter-plugin-tests`) and run with NO
      `installPlugins` — proving production lazy-ServiceLoader dispatch. Verified: StdEffectsTest+InterpreterTest
      **185 green**, LoggerPluginTest+BlockFormSpiTest **7 green**. This became the reusable template for the
      later Random/Clock/Env/State/Retry/Cache/Http plugin migrations; actors use the separate scheduler seam.
- [x] **core-min-random-migrate** (A) — ✓ DONE 2026-06-22 (`2d525ea59`). Random extracted to
      `runtime/std/random-effect-plugin` (`RandomEffectPlugin`; one `RandomBlockForm` registered under both
      `runRandom` and `runRandomSeeded`; per-block `java.util.Random`, replies over `SpiValue` —
      nextInt/nextDouble/uuid/pick, `pick` round-trips arbitrary list elements via `SpiValue.Opaque`). **This
      slice GENERALIZED the block-form SPI to CONFIG ARGS** — `keyword(config…){body}`, not just `keyword{body}`:
      `dispatchBlockForm` now evaluates leading config terms → `newHandler(ctx, cfgArgs)` (the seed). Added the
      generic *curried* block-form cases in `EvalRuntime` (loaded + lazy-load mirror), placed AFTER all hardcoded
      curried special-forms (runClockAt/runEnvWith/httpClient/…) so they only catch genuinely-unmatched applies.
      Removed core `randomRun` + 2 cases + 2 `reservedApplyHeads` names. Tests moved
      `StdEffectsTest`→`RandomPluginTest` (no `installPlugins`). Verified: StdEffectsTest+InterpreterTest **179
      green**, RandomPluginTest+LoggerPluginTest+BlockFormSpiTest **13 green** + full-suite sweep.
- [x] **core-min-clock-env-migrate** (A) — ✓ DONE 2026-06-22. Clock + Env extracted to
      `clock-effect-plugin` + `env-effect-plugin` (one effect = one library). Both curried-config siblings, so
      they REUSE the config-args SPI path from `core-min-random-migrate` with ZERO new dispatch machinery:
      `runClockAt(t0)` → `newHandler` reads frozen-ms; `runEnvWith(map)` → reads the overlay (exercises the
      SPI's `MapV` config path). `ClockBlockForm`/`EnvBlockForm` registered under both plain+curried keywords;
      handlers reply over `SpiValue` (Clock now/nowIso/sleep, frozen=no-op; Env get/set/required with per-block
      mutable overlay + real-`getenv` fallback). Removed core `clockRun`/`envRun` + 4 cases + 4
      `reservedApplyHeads` names. Tests moved `StdEffectsTest`→`ClockPluginTest`+`EnvPluginTest`. Verified:
      interpreter **169 green**, full plugin-tests **647 green** (1 env-gated cancel). FOUR effects are now
      plugins: Logger, Random, Clock, Env.
- [x] **core-min-state-migrate** (A) — ✓ DONE 2026-06-22. State extracted to `state-effect-plugin`. State is
      the first NON-pure-reply effect: `State.modify(f)` must *apply a ScalaScript closure*, which the
      pure-reply SPI couldn't do. **Grew the SPI by exactly one capability — `BlockContext.applyFn(fn, args)`**
      (defaulted to throw → backward-compatible; the interpreter overrides it, routing back through
      `callValue` + synchronous `Computation.run`, parity with the old `callValue1`). `StateBlockForm` under
      `runState`; `newHandler` takes the initial state (config arg); get/set/modify reply over `SpiValue`;
      the `result` hook returns `(finalState, bodyResult)`. Removed core `stateRun` + case + `reservedApplyHeads`
      name. Tests `StdEffectsTest`→`StatePluginTest`. Verified: interpreter **165 green**, full plugin-tests
      **651 green** (1 env cancel). **FIVE effects now plugins: Logger, Random, Clock, Env, State.** Probed and
      recorded: the REMAINING runners (Retry/Cache/Http/Actors) also need interp callbacks — Retry/Cache via
      `applyFn` (thunks); Http additionally needs to construct a `Response` record (no `SpiValue` record case
      yet → would need a `BlockContext.makeRecord` or an Opaque-instance helper); Actors need the message loop.
- [x] **core-min-retry-cache-migrate** (A) — ✓ DONE 2026-06-22. Retry + Cache extracted to `retry-effect-plugin` +
      `cache-effect-plugin`, copying the State template (both re-invoke the body thunk via `BlockContext.applyFn`).
      `RetryBlockForm(sleep)` under `runRetry`/`runRetryNoSleep`; `CacheBlockForm(bypass)` under
      `runCache`/`runCacheBypass`. The Cache TTL store moved into the plugin (process-local `object CacheStore`,
      was `interp._cacheStore`); per-block `bypass` replaces the `_cacheBypass` ThreadLocal (each block's handler
      carries it; trampoline dynamic-scope == ThreadLocal). Removed from core: 4 `EvalRuntime` cases + 4
      `reservedApplyHeads` names; `EffectHandlers.retryRun`/`cacheRun`; `Interpreter._cacheStore`/`_cacheBypass`.
      Wired into `allPlugins` (auto aggregate + plugin-tests classpath) + the explicit `pluginPkgs` installBin list.
      Tests moved `StdEffectsTest`→`RetryPluginTest`(3)+`CachePluginTest`(2) (no `installPlugins`, lazy dispatch).
      Verified: plugin-tests **656/0** (1 env-gated cancel) + InterpreterTest+StdEffectsTest **160/0**. **SEVEN
      effects now plugins: Logger, Random, Clock, Env, State, Retry, Cache.** NOTE: emitters (`Retry`/`Cache`
      globals in `StdEffectsRuntime`) stay in core per the State precedent — only the heavy handlers move.
- [x] **polyglot-phase2-optics-allhosts** ✓ DONE 2026-06-22 — per-host optics library packaging now ships for
      all four hosts: JS/npm (`optics-emit-lib-cli`), JVM/Scala (`optics-jvm-facade`), Rust/cargo
      (`polyglot-optics-rust`), and Java/Maven (`polyglot-optics-java`). Spec §4 + §6. Historical blueprint:
      • Optics is **NOT** a `.ssc` module or named intrinsics — it's AST-level: `Focus[T](_.a.b)`
        (`EvalRuntime.scala:4591`→`OpticsRuntime.evalFocus`) + `Prism[Outer,Variant]` (`:4318`→`buildPrism`); JS at
        `JsGen.scala:4542`/`3746`, runtime `JsRuntimeOptics.scala` gated by `Capability.Optics`. **There is no
        exported symbol table to read — the public facade must be AUTHORED.** The canonical contract is the 4 synth
        optic shapes: Lens(get/set/modify/andThen), Optional(getOption/set/modify/andThen),
        Traversal(getAll/modify/set/andThen), Prism(getOption/reverseGet/set/modify/andThen) — IDENTICAL between
        `OpticsRuntime` (interp/JVM) and `JsRuntimeOptics` (JS). `PathStep`=Field/Some/Each/Index/AtKey.
      • Packaging infra TODAY: `ssc package --lib` (`SsclibPackaging.scala`) emits a `.ssclib` SOURCE zip (NOT a
        host artifact). `emit-js`/`emit-rust`/`emit-scala` emit programs. `ssc link --backend jvm --bytecode
        --emit-scala-facade` (`FacadeGenerator`) is the closest jar/facade path. **Spec §4's `emit-rust --lib` is
        FICTIONAL** — Rust lib mode = "module has no `@main`" (`RustGen.scala:62` → `renderLibRs()`/`src/lib.rs`,
        Cargo `[lib]`, golden-tested in `RustGenRuntimeFilesTest`/`RustGenCargoTomlTest`).
      • Per-host state: **JS = most tractable** (runtime exists+gated; only need ESM wrapper + `package.json` +
        hand-written `.d.ts`; no new codegen). **JVM** = facade/link-to-jar exists but optics has no compilable
        `.ssc` defs → author a thin facade. **Rust** = lib-crate skeleton exists but optic `pub fn` codegen is
        GREENFIELD. **Java** = fully greenfield (`JavaFacadeEmitter` + value-mapping seam). Golden pattern: mirror
        `RustGenCargoTomlTest` exact-string asserts, or `WireGoldenVectorTest` table.
      • **First slice = JS optics npm package**: call `JsGen.generateRuntime(Set(Capability.Optics,Core))`, wrap as
        ESM re-exporting `makeLens/makeOptional/makeTraversal/makePrism`, emit `package.json` + curated `optics.d.ts`
        (the 4 shapes above); golden test asserts the `.d.ts` + exported symbols. Then JVM/Rust/Java follow the
        same packager shape. Rank to ship: JS → JVM → Rust → Java.
      • **✓ JS SLICE LANDED 2026-06-22** — `JsLibPackager` (in `backendJs`) emits the `@scalascript/optics` npm
        ESM package (`package.json` + `index.mjs` + curated `optics.d.ts`); bundles the `JsRuntimeOptics` `_make*`
        factories + only the `_None`/`_Some`/`_isMap` deps (HAMT narrowed to native `Map` at the edge) + step
        builders; re-exports stable `makeLens/makeOptional/makeTraversal/makePrism/Some/None/field/index/at/some/each`.
        `JsLibPackagerTest` 5/5 incl. a node ESM smoke that imports the generated package + exercises all 4 optics.
        The `.d.ts` is the frozen API golden. **Later slices all landed:** (a) user-reachable
        `emit-lib --host js --feature optics -o <dir>` via `EmitLibCmd`; (b) JVM facade jar; (c) Rust crate;
        (d) Java facade. Golden API-signature tests now cover each host.
- [x] **js-runtime-resources** ✓ DONE 2026-06-22 (optics pilot) — first slice of polyglot-libraries §3 #8:
      move JS backend runtime fragments out of big Scala string constants into real `.mjs` resource files
      (lintable / `node --check`-able / editor-friendly). `JsRuntimeResource.load(name)` reads + caches a
      classpath resource under `/scalascript/js-runtime/`; `JsRuntimeOptics` is now a thin wrapper
      (`load("optics.mjs")`) keeping its `val X: String` API → call sites + emitted JS unchanged, verified
      **byte-identical** (7555B, `diff`-empty; `JsLibPackager` golden+node-smoke unchanged). `JsRuntimeResourceTest`
      5/5. Spec `specs/js-runtime-resources.md`. **✓ REST DONE 2026-06-22 (js-runtime-resources-rest):** the
      remaining 17 fragments (`Part1a`–`d`, `Part2a/2b`, `AsyncA/B`, `Signals`, `Dataset`, `IndexedDb`,
      `BrowserPatch`, `Graphql`, `Mcp`, `McpBrowser`, `Payment`, `V14Effects`) all migrated — `diff`-verified
      byte-identical, backendJs compiles, 65 JS codegen tests green. **§3 #8 closed for JS** (all 18 fragments
      now `.mjs`; the `JsRuntime`/`JsRuntimeAsync` aggregators in `JsGen.scala` stay computed). FOLLOW-UPS: same
      pattern for JVM/Rust runtime strings; optional `tsc --checkJs`/`eslint` CI gate (needs JSDoc first).
- [x] **rust-effects-multishot-r6** ✓ SUPERSEDED 2026-06-22 — duplicate of the detailed `[~] rust-effects-multishot-r6`
      status above. Tier-1 List, Tier-1 Option, and Tier-2 static-depth are done; remaining unbounded
      perform-in-loop is additive with no current consumer. ORIGINAL: multi-shot algebraic effects on Rust (resume invoked
      more than once, e.g. NonDet `{1,2}×{10,20}`). One-shot handle/resume already SHIPPED (`a87afba34`, tagless-
      final, no trampoline). lucky-otter flagged multi-shot as out-of-scope/hard: needs an `FnMut` continuation
      that can be re-invoked — the tagless-final one-shot lowering (`resume(v)`→`v` tail-substitution) can't express
      it. RESEARCH slice: probe whether a captured-closure continuation (`Box<dyn FnMut>`) or a CPS/defunctionalized
      re-entry is tractable in `RustCodeWalk`'s handle lowering; if not bounded, SCOPE DOWN + document the blocker
      in `specs/rust-effects.md` §R.6 and BACKLOG. Spec `specs/rust-effects.md`. Lower confidence than the other two.
- [x] **core-min-phase3plus** ✓ ACTIONABLE SCOPE DONE 2026-06-23 — the practical core-min/polyglot Phase 3+
      queue has landed or been split into sharper items. Landed: Logger/Random/Clock/Env/State/Retry/Cache/Http
      effect runners moved to plugins; JS/JVM/Rust runtime resources moved out of backend string blobs where
      bounded; optics ships as native JS/JVM/Rust/Java host libraries via `emit-lib`; bundled prelude names are
      minimized (`runStream`/`Stream`, actors keyword set, and advanced/essential plugin-owned names now come from
      plugin `preludeSymbols`); actors have a provider + per-interpreter session seam. Not closed here:
      `core-min-value-unification` stays as its own deep refactor, and the hard Stream/Actors interpreter-internal
      code moves stay deferred/optional because they have low ROI without a new consumer.
- [x] **core-min-value-unification** ✓ SCALARS-ONLY SCOPE DONE 2026-06-23 — **SPEC + Slices 1-6 LANDED**
      (`specs/value-unification.md`), on two complementary tracks. PROBED the real surface: **4387
      `Value.<Case>` sites across 46 files**; `Value` = sealed trait co-defined with `Computation`/`Env`/
      `FrameMap` (circular) + perf pools; the SPI conversion was lossless via `Opaque` EXCEPT `Char`→`StrV`
      and `Vector`→`ListV` (coerced). **Structural blockers found:** a sealed trait can't be split across
      modules, and data cases can't `extend` a core type if they must live *below* core (a `DataValue extends
      Value` marker is the WRONG direction) → end-state = standalone low-module `DataValue` enum + `Value =
      DataValue | carriers`, `type SpiValue = DataValue`, conversion deleted. NO early slice deletes duplication
      (payoff lands at the final merge), so the work is a sequence of safe always-green slices.
      **Track A — SpiValue completion:** added `SpiValue.CharV`/`VectorV` so the SPI boundary is LOSSLESS for
      all immutable data cases (mutable `Array` + case instances stay `Opaque`, correct); `SpiValueDataRoundTripTest`,
      plugin-tests 712/0. **Track B — disentangle `Value.scala`:** extracted `Computation`+runtime signals →
      `Computation.scala` and `Env`/`FrameMap`/`MutableEnvView` → `Env.scala` (byte-identical, zero-behavior;
      InterpreterTest 158/0, effects 33/0, closure/pattern/tuple 186/0). **Slice 3 spike DONE 2026-06-23:**
      validated `type Value = DataValue | Callable` (union) + `export DataValue.*` from `object Value` — existing
      `Value.IntV(n)` construct + `case Value.IntV(n)` patterns compile unchanged, DataValue lives below core,
      exhaustiveness preserved under -Werror (rejected: `DataValue extends Value` marker; bare union w/o export).
      **SCOPE DECISION 2026-06-23 (Sergiy): SCALARS-ONLY — full merge OFF the table.** The container/closure
      obstacle: the interp stores closures INSIDE containers (`List(() => 10)` = `ListV(List(FunV))`), so a
      fully-merged low data type would force closures-as-`Opaque` → a cast on the HOT function-dispatch path
      (perf regression Sergiy declined). So only the scalar leaves are shared; containers + carriers stay core;
      the conversion shrinks (scalars→identity) but is NOT deleted. **Slice 4 DONE 2026-06-23:** flipped `Value`
      to a union `type Value = DataValue | ValueRest` — `DataValue` (new enum, `DataValue.scala`) = 9 scalar
      leaves; `ValueRest` (sealed) = 14 container/instance/carrier cases; `object Value` re-exports scalars via
      `export DataValue.*` so all ~4387 sites are UNCHANGED. Astonishingly clean: the ONLY friction was one
      `java.util.Arrays.sort` over a union array (→ `Array[AnyRef]` cast); exhaustiveness preserved. Verified
      core+backendInterpreter+all plugins+server+dap compile; core/test 1019/0, plugin-tests 712/0, broad
      interp/value/effects 218/0, numeric/collection/JIT 77/0 (~2026 green). **Slice 5 DONE:** moved `DataValue`
      to a new low leaf module `lang/value-data` (below core+backendSpi). **Slice 6 DONE:** `SpiValue` is now
      `type SpiValue = DataValue | SpiRest` — scalar leaves are the SAME shared `DataValue` classes (SpiRest =
      SPI-private containers + Opaque; `object SpiValue` re-exports `DataValue` w/ `StringV as StrV`, so the 9
      plugins + all `SpiValue.*` sites are unchanged); `valueToSpi`/`spiToValue` convert scalars by IDENTITY.
      **✅ SCALARS-ONLY UNIFICATION COMPLETE** — one shared set of scalar classes across `Value` + `SpiValue`;
      the scalar half of the conversion is gone; the container half stays by design (closure-bearing obstacle).
      plugin-tests 712/0, round-trip+effects+numeric 183/0. The actionable scope of this task is now CLOSED
      (full merge deliberately off — perf). Original goal/notes below (NOTE: the "delete the conversion / one
      type" end-state is SUPERSEDED by the scalars-only decision — the container half is correct to keep).
      <br>**Goal (original):** collapse the duplication
      between the interpreter's `Value` and the SPI's `SpiValue` into ONE value type. Today they're separate by
      necessity: `interpreter.Value` (in `core`) is entangled with *execution* — `FunV(closure: Env)`,
      `NativeFnV(f: List[Value] => Computation)`, mutable `InstanceV`, `type Env = Map[String, Value]` — and
      `backendSpi` (which `core` depends on, not vice versa) can't reference it, so the boundary uses the
      host-neutral `SpiValue` (+ a `Value↔SpiValue` conversion). **Goal:** un-entangle `Value` from execution —
      split the *pure-data* cases (`Int/Double/Str/Bool/Char/Unit/List/Vector/Array/Map/Tuple/Option/Instance`)
      from the *runtime-carrier* cases (closures/native-fns hold an `Env`/`Computation`), moving closures +
      `Computation` out of the `Value` ADT into a separate runtime structure. Then the data ADT can live in a
      low shared module and **be** `SpiValue` — one value type across interp + SPI + host libraries (Task B),
      deleting the conversion. **Caveat (why it's LATER):** it's a deep refactor touching every `Value` match in
      the interpreter (DispatchRuntime/PatternRuntime/EvalRuntime), and it still privileges the interpreter's
      shape, so it's lower-priority than the keystone extractions; the current `SpiValue` (= the safe data
      subset) is correct in the meantime. **Verify:** full interp suite green; `Value↔SpiValue` conversion gone;
      no `Env`/`Computation` reachable from the SPI value type.

### ▶ Prioritized build queue (2026-06-18, with Sergiy — "внеси всё и делай автономно")

The genuine remaining **autonomously-actionable** build work, in priority order. Drive top-to-bottom,
one theme at a time, per-feature worktrees + claims. Everything below the queue is either history (`[x]`)
or blocked/deferred (kept for record, NOT actionable now — see "Excluded from the sprint").

> **Status 2026-06-18 (autonomous pass):** queue worked top-to-bottom. #1 meta-v2-track-c —
> verified already complete (no build). #2 sbt-plugin dep-resolution — ✓ built + tested (residuals
> design-/Maven-gated). #3 wasm-effects — **effectively COMPLETE**: arithmetic (2a) + `_dispatch`
> collection-HOFs (2b) + multi-shot (2c) + cross-module (2d) all built + run-verified on node (36 tests);
> `@main` args/non-Unit edge later closed by `wasm-main-edge` (40 tests). #4 build-registry-phase4 — assessed, no concrete target → no
> action. Then `sscBackends` cross-build ✓ DONE (user picked spec open-Q #2 → parallel outputs in one
> `compile`; scripted `cross-build/`). **What remains is Maven-gated only:** Maven Central + Plugin Portal
> publication (LAST, explicit-go). No bounded autonomous build work left.

### ▶ Quality / perf queue (2026-06-20, with Sergiy — "все эти задачи занеси в спринт и начинай делать")

After the perf series (foldLeft VM compile + typeclass-fold memo) micro-throughput is at the floor. The
next autonomously-actionable work is quality + unmeasured-axis perf, priority order. Drive top-to-bottom,
per-feature worktrees + claims.

> **Status 2026-06-20 (queue worked top-to-bottom — ALL DONE):** #1 real-workload-perf ✓ all three axes:
> (a) cold-start AppCDS −51% + harness, (b)+(c) steady-state server RSS+GC harness (~195 MB STABLE, no leak).
> #2 xbackend full+CI ✓ generator already broad (12 kinds) + wired into CI. #3 xbackend-test-hardening ✓
> `runCaptured` hang-proof runner. #4 rust-web-toolkit ✓ verified essentially complete + shipped the one
> bounded deferred slice (set/toggle client wiring); rest is browser/rozum-driven. **Queue fully resolved.**
> Follow-ups also DONE 2026-06-20 (per "сделай всё кроме maven"): **xbackend hang-proof sweep** — converted
> all 17 deadlock-risk (both-streams) subprocess-test files to `ProcTestUtil.runOrThrow`/`runCaptured` (the
> 22 single-stream `redirectErrorStream` files are deadlock-safe + behaviour-subtle → left as-is, standard
> set for new tests); 54 converted tests run green. **Server leak-hunt** — 4-min sustained-load run:
> definitively no leak (RSS peaked 205 MB, *ended 80 MB* as the JVM reclaimed heap; GC light/steady). **Only
> Maven publication (gated, excluded) + rozum/browser-driven rust refinements remain.**

### ▶ Rust-web computed-signal queue (2026-06-20, with Sergiy — "делай всё, заноси в спринт и делай")

The rust-web S5 refinements turned out to be autonomously buildable + curl/cargo-verifiable (set/toggle,
SSE, computed-read compile+SSR all DONE). Remaining, priority order:

- [x] **computed-live-recompute** ✓ DONE 2026-06-20 — computed signals are now fully reactive. Moved the
      signal store to `value.rs` (so `signal_value` can read it) + a computed-closure registry +
      `ssc_register_computed`/`ssc_recompute_all`; `_ui_computed_signal` is a re-runnable `Fn` returning a
      NAMED signal; `/__ssc/push` recomputes before broadcasting (SSE). **Verified cargo+curl:** push a dep →
      the computed signal auto-updates (`{"__c0":"fr"}` → `{"__c0":"de"}`). `backendRust` 224/0.
- [x] **computed-typed-reads** ✓ DONE 2026-06-20 — `collectLocalSignals` carries the element type; the apply
      emits `.parse::<i64>()`/`.parse::<f64>()` for `Signal[Int]`/`[Double]`, `.show()` for String. Verified:
      `signal("n", 10)` + `n() + 5` → `15`. `backendRust` 225/0.
- [x] **direct-WS** ✓ DONE 2026-06-20 — a `serve(view)` program also exposes a WS signal endpoint on
      `port + 1` for external clients (rozum bridge), bidirectional + sharing the SSE store/broadcast/recompute.
      `ssc_ws_serve` (accept_async) sends state on connect, streams updates, and an incoming `name=value` frame
      sets+recomputes. **Verified cargo + raw-WS client (python):** WS-push `locale=de` → `{"__c0":"de"}`.
      `backendRust` 226/0. **rust-web S5 now FULLY COMPLETE** (set/toggle, SSE, computed compile+SSR + live
      recompute, typed reads, direct-WS — all built + cargo/curl/WS-verified).

### ▶ Benchmark perf-divergence queue (2026-06-21, with Sergiy — "разбирайся в чем дела — в jit? В codegen? В bench?")

The big per-workload outliers from the same `./bench.sh` sweep, each ROOT-CAUSED by hand (emit + read the
generated code / toggle the JIT). Verdict per case: **codegen**, **jit**, or **bench** (intentional anti-fold).

- [x] **asm-jit-effect-pathology** (JIT) ✓ DONE 2026-06-21 — `ssc-asm` `effect-oneshot` **9.46 → 0.032
      ms/iter**, now effectively matching default `ssc` (0.025 ms/iter). Root cause: Javac bytecode JIT lowered
      active one-shot tail-resume effect ops through `JitGlobals.resolveEffectLong*`, but ASM `walkLong` did
      not, so `Bump.tick().toLong` bailed out to the slow effect trampoline. Fix `0d5e03b87`: ASM mirrors the
      resolver lowering and treats resolved effect calls as Long-shaped for `.toLong`/`.toInt`. Verified with
      `AsmEffectJitTest`, `EffectOneShotFastPathTest`, `JitLintTest` (85/85), `sbt -no-colors cli/installBin`,
      and `./bench.sh effect-oneshot --backend ssc{,-asm}`.
- [x] **js-tuple-monoid-alloc** (CODEGEN) ✓ DONE 2026-06-21 — **`js` `tuple-monoid` 7.40 → 2.60 ms (2.85×)**,
      no longer the slowest cell. Two general JsGen fixes: (1) `t._N` on a statically-known tuple lowers to a
      direct `t[N-1]` array read (new `tupleVars` tracking + `isTupleExpr`), skipping the megamorphic
      `_dispatch(t,'_N',[])`; case classes never match `isTupleExpr` so their Product `._N` is untouched.
      (2) a tuple-LITERAL concat `(a,b) ++ (c,d)` flattens into ONE `Object.assign([a,b,c,d],{_isTuple:true})`
      instead of `_tupleConcat(Object.assign(..),Object.assign(..))` (3 allocs → 1); a variable operand still
      uses `_tupleConcat`. **Verified:** 281 JS unit tests green; interp == js on tuple flatten/`._N`/show/eq.
      NOT done (left): native `+` for the `_arith('+')` on tuple-element reads (needs tuple-element type
      tracking) — lower value. The `s` LCG interp/js delta in this workload is the separate 64-bit-Long-on-JS
      precision limitation, not a tuple bug.
- NOTE (no task — **bench**, intentional): rust `arith-loop` **1.52 ms (4.7× jvm)** is largely the harness's
      anti-fold — `run.sc` wraps every rust closure body + per-iter reassignment in `std::hint::black_box(...)`,
      blocking LLVM loop optimization (the comment at `run.sc:176` even tunes this so rust "stops looking 3–4×
      slower"). Not a codegen bug; leave as-is unless we want a lighter rust anti-fold.

### ▶ Benchmark backend-gap queue (2026-06-21, with Sergiy — "Запиши в спринт все n/a")

Every `n/a` from a full `./bench.sh` sweep (31 workloads × ssc/ssc-asm/jvm/js/rust), each VERIFIED by hand
against the current toolchain (the corpus comments were stale). The bench measures time only (no correctness
check — that's `CrossBackendPropertyTest`, green); `n/a` = that backend's emit/build/run failed.

- [x] **rust-effects-handle-resume** (R.4.2, ONE-SHOT) ✓ DONE 2026-06-22 — **`effect-oneshot` n/a → 0.0020 ms
      on rust** (the fastest backend on it). Custom algebraic effects with explicit `handle`/`resume` now
      compile + run on rust via **tagless-final traits** (per `specs/rust-effects.md §10`), NOT the Free-monad
      CPS port the old `rust-backend.md §R.4` implied — so the `while`-loop case needs **no trampoline** (the
      loop runs directly; `Bump.tick()` is `_eff.tick()`). 3 gaps implemented: (1) a custom `effect E:` object
      emits a `trait ${E}Effect` with required methods (`collectEffectOps` + `renderTaglessEffectsRs`); (2)
      `Eff.op(args)` → `_eff.op(args)`; (3) `handle(body){ case Eff.op(binders, resume) => arm }` → a handler
      `struct __H_E; impl ${E}Effect for __H_E { fn op(&mut self, binders) -> ret { <resume(v)⇒v> } }` +
      `{ let mut _eff = __H_E; <body> }`. **Verified:** minimal probe cargo-builds → `10`; the real
      `effect-oneshot.ssc` workload → `962` (== interp/jvm); `backendRust` 230/0 + 3 new `RustGenR44Test`
      cases. **Remaining (R.6 follow-up, NOT this task): multi-shot.** `effect-multishot` stays `n/a` — its
      `opts.flatMap(opt => resume(opt))` calls `resume` many times, which a single trait-method return can't
      model (needs FnMut continuation re-invocation); it fails cargo cleanly (out of scope by design).
- [x] **jvm-multishot-result-type** ✓ DONE 2026-06-21 — `effect-multishot` was `n/a` on **jvm** because
      CPS def emission widened total handled-effect wrappers from their declared result type to `Any`:
      `def workload(seed: Long): Long` emitted as `def workload(seed: Long): Any`, and the bench wrapper's
      typed sink failed with `Found: Any; Required: Long`. Fix (`39b7c665f`): keep declared non-effect-row
      result types at CPS def boundaries and cast the final CPS result there; effect-row defs (`A ! Eff`)
      still return `Any` so handlers can unwrap Free computations. Guard: `JvmGenEffectsRuntimeTest`
      `addLong(workload(0L))` e2e. **Verified:** `backendInterpreter/testOnly scalascript.JvmGenEffectsRuntimeTest`
      34/34; `sbt -no-colors cli/installBin`; `./bench.sh effect-multishot --backend jvm` `n/a` -> 0.075 ms.
- [x] **rust-either-chain-closure-type** (E0282) ✓ DONE 2026-06-21 — `either-chain` was `n/a` on **rust**
      (`cargo build` → `error[E0282]: type annotations needed` because the chained `match match match …`
      emitted each Either arm as `(move |x| { … })(v)`, whose closure param type rustc couldn't infer). Fix:
      a new `inlineArm` lowers a 1-param Either map/flatMap/fold arm to a `{ let x = v; body }` block instead
      of an immediately-applied closure — the `let` flows `x`'s type straight from `v`. Function-reference args
      keep `(f)(v)`. **Verified:** `cargo build` green; interp == rust (`R=632`); `./bench.sh either-chain
      --backend rust` n/a → **0.0040 ms**; `backendRust` 229/0 + a new `RustGenR23Test` E0282 regression test.
- [x] **bench-stale-jvm-na-hygiene** ✓ DONE 2026-06-21 — the stale JVM `n/a` was not a cache issue; it shared
      the `jvm-multishot-result-type` root cause. Total CPS wrappers declared as `Long` emitted as `Any`, so
      the bench sink rejected both `effect-oneshot` and `effect-multishot`. Corpus comments were refreshed.
      **Verified:** `./bench.sh effect-oneshot --backend jvm` = 0.160 ms; `./bench.sh effect-multishot --backend jvm`
      = 0.075 ms; `./bench.sh effect-oneshot effect-multishot --backend js` = 0.347 / 0.224 ms.

### ▶ Improvement queue (2026-06-20, with Sergiy — "занеси все в спринт и делай")

Fresh do-soon queue after rust-web S5 closed. Work top-to-bottom, one claim/worktree per slice. Maven Central
publication remains explicit-go only; the registry work below is intentionally domain-independent first.

- [x] **wasm-main-edge** ✓ DONE 2026-06-20 — closed the last WASM effects tail. Effectful WASM now derives
      the user `@main` from the AST, preserves a single Scala 3 `@main` parameter clause (including
      `String*` splicing), discards non-`Unit` returns in the synthetic wrapper, and rejects raw
      `Array[String]` `@main` args with a clear "use `String*`" diagnostic. **Verified:**
      `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/wasm-main-edge && sbt "backendWasm/testOnly scalascript.codegen.WasmBackendTest"`
      → 40/40 green. Gotcha recorded in `specs/wasm-main-edge.md`: Scala.js ES-module launcher argument
      delivery is out of scope; a direct Node probe supplies empty `String*` args.
- [x] **stable-plugin-spi-p3** ✓ DONE 2026-06-21 — completed one small Phase 3 SPI cleanup slice:
      `bench-plugin` now implements `Bench.opaque` through `PluginNative.eval` / `PluginValue` instead of
      importing `scalascript.interpreter.Value` directly. Added `BenchIntrinsicsTest` to lock identity
      behavior (including empty args -> `Unit`) and to scan `bench-plugin/src/main` for direct interpreter
      imports so this slice does not regress. **Verified:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/stable-plugin-spi-p3 && sbt -no-colors "benchPlugin/test; pluginApi/test; benchPlugin/checkPluginBoundary"`
      → `BenchIntrinsicsTest` 2/2 green, `PluginApiTest` 14/14 green, `benchPlugin/checkPluginBoundary` green.
- [x] **js-char-wrapper-string-map** ✓ DONE 2026-06-21 — added a JS `_Char` box (`JsRuntimePart2a`):
      `valueOf`→code point, `toString`→1-char string (so concat/arith/`_show` coerce). Iterated chars
      (`map`/`filter`/`foreach`/`flatMap`/`charAt`/`head`/`last`/`toList`/`forall`/`exists`/`count`) box;
      `String.map` returns a String only when every result is a `_Char`, else a Seq (mirrors `strMapResult`).
      `_dispatch` got a `_Char` branch mirroring the interp `dispatchChar` (`toInt`→code, `isDigit`/`toUpper`/
      `asDigit`/…); `_eq` bridges `_Char` ↔ 1-char String literal and ↔ Int. `CrossBackendPropertyTest`
      "String.map char vs non-char" now asserts interp == JS == JVM (+ a char-method map/filter case).
      **Verified:** 280 JS unit tests green (23 suites, 0 fail); String.map + string-method-gaps cross-backend
      green on all 3 backends; direct node probe matches interp byte-for-byte. Residual (BUGS.md): a char
      *literal*'s `.toInt` (`'5'.toInt`) still diverges (literals stay strings to avoid literal-pattern
      `===` codegen) — separate, lower-value follow-up.
- [x] **rust-web-example** ✓ DONE 2026-06-21 (a55e101f2) — added `examples/rust/web-signals.ssc`
      (signal + computedSignal + signalText + serve), emit-rust + `cargo build` green, binary serves SSR and
      `/__ssc/push?name=locale&value=de` recomputes the computed signal (`{"__c0":"fr"}` → `{"__c0":"de"}`).
      Building it (vs the string-match tests, which never cargo-build) surfaced + fixed **two real bugs**:
      (1) computed move-closure use-after-move (cargo E0382) — `renderClosure` now clone-captures read signal
      locals; new regression test, backendRust 228/0; (2) docs showed `POST /__ssc/push -d` but the endpoint
      reads query params `?name=&value=` — corrected example + rust-backend.md + user-guide.md.
- [x] **real-workload-perf** (roadmap-next #1) ✓ DONE 2026-06-20 (all three axes). **(a) cold-start:**
      `tests/perf/coldstart/` + AppCDS in `bin/ssc`/`install.sh` → **378 → 182 ms (−51%)**, peak RSS −32%.
      **(b)+(c) steady-state RSS + GC:** `tests/perf/serverrss/` boots a real server under load → interp
      server **~195 MB RSS, STABLE** (no leak), light GC (~41 pauses/27 ms). Long minutes-scale leak-hunt
      left to demand (`secs=300+`). BACKLOG `real-workload-perf`.
- [x] **xbackend-property-equivalence (full + CI)** ✓ DONE 2026-06-20 — broaden was already complete (12
      kinds incl. effects/Option/Either/closures/nested; node leg 74 programs / 0 skipped) so the work was
      reconciling that + **wiring into CI**: added Node.js setup to the `sbt` job so the interp==JS
      differential now runs in CI (it was skipping). Made hang-safe first (next item). BACKLOG `xbackend-property-equivalence`.
- [x] **xbackend-test-hardening** ✓ DONE 2026-06-20 — root cause was NOT bloop per se: `runProc` read
      subprocess streams with blocking `mkString` BEFORE the bounded `awaitExit`, so a wedged child parked
      the read forever (and could pipe-buffer-deadlock). Fixed via `ProcTestUtil.runCaptured` (threaded
      stream drain + hard timeout that actually fires); `ProcTestUtilTest` proves a `sleep 60`@2s returns
      <15s + a stderr flood doesn't deadlock. `CrossBackendPropertyTest.runProc` delegates. (~9 other test
      files share the old antipattern but run fixed small programs — follow-up sweep, lower risk.)
- [x] **rust-web-toolkit finish** ✓ VERIFIED ESSENTIALLY COMPLETE 2026-06-20 (the "~56 cargo errors" was
      badly stale). Checked against the authoritative signal: **`backendRust` 221/0**, **`RustGenWebToolkitTest`
      17/17** green. Per `specs/rust-web-toolkit.md`: cargo `build` of the std/ui crate is **290 → 0** (whole
      toolkit compiles on Rust), **S4** named/curried args DONE, **S5a** (SSR initial value) + **S5b.1** (local
      client reactivity) + **S5b.2 A/B/C** (generic push / rozum bridge / computed-derived) all DELIVERED at
      poll-transport depth. **REMAINING = explicitly-deferred refinements**, NOT bounded build work: SSE/WS
      streaming transport, client recompute of computed signals, set/toggle/show client wiring, direct-WS
      client. All are **browser-dependent** (can't verify autonomously without a browser) and **rozum-driven**
      (spec method: "drive from the target … ultimately `rozum-web.ssc`"). Hand back to the rozum driver; do
      NOT push speculative client-JS refinements onto `feature/rust-web-toolkit` (rozum's active branch).


- [x] **meta-v2-track-c** ✓ DONE 2026-06-18 (verified, no build needed) — Track C is COMPLETE. C1
      (multi-clause inline) ✓ done 2026-06-18. C2's high-value slice ✓ already done + wired:
      `MacroCodegen.codegenWarnings(module)` is computed in `ssc check` (`Main.scala:5265`, merged into
      `CheckResult.errors:5267`) and warns up-front on interpreter-only macros that can't compile to JVM/JS —
      `MacroCodegenTest` 6/6 green. The remaining C2 ambition (run the Typer over *arbitrary* macro-expanded
      source, map type-errors to `.ssc` positions) is **DEFERRED by design** in the spec: needs a position
      map (re-parse loses positions) + risks false positives (Typer may not grok expanded macro-runtime
      constructs), niche audience — low ROI vs the codegen warning that covers the real failure mode. Building
      it now = busywork against the spec's own judgment. **→ Next pick: sbt-plugin-finish.**
- [x] **board-meta-v2-reconcile** ✓ DONE 2026-06-21 — removed stale meta-v2 Track C/C2 "still open"
      guidance from the board.
      **How:** reconcile `SPRINT.md`'s later `[~] metaprogramming-v2` paragraph and `BACKLOG.md` roadmap text
      with the authoritative `meta-v2-track-c` done entry plus `specs/arch-metaprogramming-v2.md` §4b, which
      says the remaining arbitrary post-expansion re-typecheck ambition is deferred by design. Keep the
      historical spec rationale; change only active queue/backlog wording so future agents do not pick C2 as
      buildable work. **Verify:** targeted grep now leaves only spec/history/deferred wording; active
      `SPRINT.md`/`BACKLOG.md` guidance no longer presents C2 as buildable work.
- [~] **sbt-plugin-finish** (roadmap #4, Phase 5) — **dep-resolution ✓ DONE 2026-06-18**: the concrete
      actionable Phase 5 slice. `SscFrontMatter` lifts `.ssc` front-matter `dependencies:` `dep:` Maven
      coords into `sscManagedDependencies` → `libraryDependencies` (Java `%`, Scala-cross `%%`, local paths
      ignored); scripted `dep-resolution/` + full scripted suite green (9). Spec §3h/Phase 5 reconciled.
      **`sscBackends` cross-build ✓ DONE 2026-06-18** (user picked spec open-Q #2 → design A = parallel
      outputs in one `compile`): `sscBackends: Seq[String]` (default `Seq(sscBackend)`); `sscCompile` forks
      `ssc build --backend <b>` per backend — single = flat dir (backward-compat), multiple = per-backend
      subdirs. Scripted `cross-build/`; full suite green (10). RESIDUALS (NOT done): (a) LSP/BSP "polish" —
      `BspIntegration`/`sscBspSetup` already landed Phase 4, no concrete remaining deliverable; (b) Maven
      Central publish + Plugin Portal — Maven-gated (LAST). So the only buildable remainder here is
      Maven-gated.
- [x] **wasm-effects** ✓ COMPLETE 2026-06-20 — additive, wasm-only.
      **arithmetic ✓ DONE (slice 2a):** `_binOp` (+`_bigIntOp`/`_bigDecOp`) — `a + b`/`sum * 2` over effect-op
      results link + run (test → 40). **`_dispatch` ✓ DONE (slice 2b):** collection HOFs on `Any` —
      `xs.map(..).filter(..).head` in a handler links + runs (test → 6); copied the pure subset of `_dispatch`
      + `_seqX`/`_seq`/`_isFree`, reflection fallback → clear error. **multi-shot ✓ DONE (slice 2c):** did NOT
      need a `_handle` rewrite (probe disproved it) — just the pure `_anyFlatMap` helper + a `usesEffects` fix
      to recognise `multi effect Foo:`; NonDet `{1,2}×{10,20}` runs on node (test → 4). **cross-module ✓ DONE
      (slice 2d, no code change):** an imported `effect` already works — `generateUserOnly` resolves imports via
      `baseDir`; run test → `hello\nworld`. **`@main` args/non-Unit edge ✓ DONE (wasm-main-edge):** effectful
      `@main` wrappers preserve Scala 3 main parameter clauses, discard non-Unit returns, and reject invalid raw
      `Array[String]` args clearly. **Complete:** common + advanced cases all run; `WasmBackendTest` 40/40 green.
      BACKLOG `wasm-effects`.
- [x] **build-registry-phase4** ✓ ASSESSED → no action 2026-06-18 (demand-driven). Surveyed the ~24
      `*Registry` classes: they are domain-distinct (Preprocessor / Interpolator / Backend / Capability /
      Route / Command / GlueClasspath / GlueJsPreamble / …), each registering a different kind of thing —
      **not** a duplicated template. The closest pairs (`Glue*`, `Interpolator*`) are small and cohesive;
      consolidating them would be speculative refactoring, exactly what the spec's "only where they remove
      real duplication" guard rules out. No concrete duplication target → no build. Revisit only if one
      appears. (Phases 1–2 landed; Phase 3 moot/load-bearing.)

---

- [x] **rust-web-toolkit** (external driver: rozum) — bring the declarative std/ui toolkit
      (`vstack/heading/text` → `lower(theme)` → `View` → `serve(view, port)`), which works on JVM,
      up on the **Rust** backend via an HTML/SSR binding (operator path A; native GUI rejected as
      too costly). **DONE 2026-06-19:** I1 `s"…${expr}…"` splices + S1a HTML/SSR View primitives
      (`element/textNode/fragment` → `runtime/ui.rs`, gated) + S1b `renderHtml` SSR — `textNode`/
      `fragment` compile AND run end-to-end (`renderHtml(...)` → escaped HTML via `ssc run-rust`).
      `backendRust` 211/0. + S1c `element` (`->` → tuple; non-empty `Map(k->v)` → HashMap-insert;
      `_ui_element` key-sorted attrs) — `renderHtml(element("div",Map("class"->"root"),…))` →
      `<div class="root" …>…</div>` end-to-end, `backendRust` 212/0. + S2 `serve(view, port)` SSR
      overload (`_ui_serve` in `http.rs`, gated on uiUsage) — `curl :8099` → SSR'd HTML, proven
      end-to-end, `backendRust` 214/0. + S1d void elements (`<meta>` self-close) + **capstone
      `examples/ssr-page.ssc`**: full nested HTML page built from primitives → `ssc build-rust` →
      `curl :8123` returns the SSR'd page. **The Rust-SSR web goal is reachable today via primitives.**
      + **S3 (a–k) the std/ui library now CODEGEN-transpiles** (import inliner + block exprs +
      partial fns + patterns + placeholder `_`-lambdas + varargs type + `++`/try/null + struct
      field types + String-match `.as_str()` + opaque-type mapping + signal SSR stubs). Cascade:
      codegen 28→11→6→3→**0**; cargo 290→170→108→70→**56**. **REMAINING:** a finicky cargo
      type-reconciliation tail (~56: TkNode/i64 + String/Value + struct-field i64 + curried-vararg
      **call-site** `vec![]` wrapping + `defaultTheme` val) — converging, multi-session. Then S4
      named/curried args · S5 signal reactivity (stubs are static-only). Spec `specs/rust-web-toolkit.md`.
      **✓ CLOSED 2026-06-22:** S1–S5 all landed on `origin/main` (S4 named/curried args + omitted-default
      fill; S5 SSR + local client + server-push + SSE/direct-WS + computed live recompute + typed signal
      reads — see CHANGELOG 2026-06-19/06-20). The driving use case `examples/rozum-meeting.ssc` builds to a
      binary and SSRs over hyper. General Rust-backend follow-ons (Vec `take/drop/sorted/distinct`, String
      `.replace`, http prefix-routing/no-store/POST-body/MIME, indexable `split/toList`) landed on main via
      `rwt-followons` (613c2bb21, `backendRust` 233/0). The `feature/rust-web-toolkit` branch is rozum's own.

- [x] **agent-sdk-remainder** ✓ DONE 2026-06-17 (actionable scope) — consolidated `specs/agent-sdk.md`
      + **P3a MCP bridge both directions** (`runtime/std/agent-mcp.ssc`: `serveAgentToolsMcp` +
      `mcpToolSource`; examples `agent-mcp-{server,toolsource}.ssc`; all `ssc check` OK). Loop
      conformance already covered by `AgentSdkInterpreterTest`. DEFERRED (reasons in spec): bridge
      round-trip test (heavy jvm/js infra for thin glue), golden transcripts, P3b embedded (blocked
      on rozum `rozum-embed`). spec `specs/agent-sdk.md`. → **Next: package-registry.**

- [x] **package-registry** (roadmap #3) ✓ DONE 2026-06-17 — found ALREADY BUILT (spec was stale):
      `ssc search`/`info`/`add` over `RegistryClient` (URL-priority + 1h-TTL cache + `--refresh`) +
      seed `registry/packages.yaml`. spec `specs/arch-registry.md` reconciled. Added the minor
      `--offline` flag (cached-only search, `RegistryClient.loadOffline()`). REMAINING (external only):
      the `scalascript/registry` GitHub repo + Pages HTML + validate/publish CI.

- [x] **sbt-plugin-finish** ✓ ACTIONABLE SCOPE DONE 2026-06-18 — this duplicate open marker was stale.
      Front-matter `dependencies:` → Coursier and `sscBackends` cross-build are done + scripted-tested;
      LSP/BSP Phase 4 already landed with no concrete remaining deliverable. Publishing the plugin artifact
      itself is the deferred Maven Central / sbt Plugin Portal step and remains excluded from autonomous work.

- [x] **metaprogramming-v2** ✓ ACTIONABLE SCOPE DONE 2026-06-21 — AUDIT 2026-06-17: NOT a from-scratch build. All three
      phases have working bases (P3 Linker `inlineTable`/`expandInlineSource`; P4 `${impl('x)}` + direct
      `'{ $x+1 }` + interp parity + `MacroImpl` IR; P5 runtime `Mirror` + user `derived(m: Mirror)`).
      PROGRESS: **Track A** (P5 cross-backend derives conformance) ✓ DONE (A1a/b/c + A2 + A3,
      2026-06-17; only deferred edge cases remain — sum-type/enum mirrors, generics, mixed-derives clauses).
      **Track B** (P4 const-folding `Expr.asValue match`): **B1 + B2 ✓ DONE 2026-06-18** (interp splice
      unwraps `Expr(v)`; `Linker.expandMacroSource` const-folds literal args to the `Some` branch, else the
      `None` direct quote; `LinkerRewriteTest` +7 / `InlineDerivesTest`; `examples/quoted-macro-constfold.ssc`).
      **B3 ✓ DONE 2026-06-18 — JVM + JS** (was blocked — quoted macros were interpreter-only): the
      `macro-codegen-backends` pass (`MacroCodegen.expand`, hooked into `JvmGen` + `JsGen` generate entry
      points) expands + strips macros pre-codegen, no-op for macro-free modules;
      `QuotedMacroJvmConformanceTest` (scala-cli) + `QuotedMacroJsConformanceTest` (node) match interp.
      **Track B is complete (B1+B2+B3).** **Track C:** C1 (multi-clause inline) ✓ DONE 2026-06-18
      (curry tail clauses into the body — no scanner/wire change); C2's practical backend guard is already
      wired through `MacroCodegen.codegenWarnings`, and the broader arbitrary post-expansion re-typecheck +
      source-positioned-error ambition is deferred by design (position-map requirement + false-positive risk).
      No bounded autonomous meta-v2 build slice remains on the board.

### Tier 2 — AUDIT 2026-06-17: most "themes" are already BUILT (specs stale)

While pulling these in I audited each against the code — and like agent-sdk + package-registry,
most are already implemented; the specs/BACKLOG were stale. So Tier 2 is mostly **reconcile +
verify residuals**, NOT from-scratch builds:

**RECONCILED 2026-06-18 (`tier2-spec-reconcile`)** — verified each theme against the code:
- [x] **theme-f-dsl-platform-hooks** — spec Status already accurate ("implemented through Phase 4",
      `InterpolatorRegistry`). No change needed.
- [x] **theme-h-library-modularity** — spec Status already accurate ("implemented through Phase 6",
      `SsclibManifest`). No change needed.
- [x] **theme-j-lightweight-ffi** — ✓ DONE: `@jvm`/`@js` (Phases 1–4) + `@rust` + **`@wasm`** all wired.
      The WASM backend exists (`runtime/backend/wasm`, Scala.js → `.wasm`); `WasmGen` lowers `@wasm("expr")`
      externs to a `def` (2026-06-18, `WasmBackendTest`). Only `@wasmExport`/`@wasmImport` (raw WASM ABI)
      stay out of scope **by design** (the Scala.js path owns the ABI). The "no WASM backend wiring" note
      was stale.
- [~] **theme-a-stable-plugin-spi** — Phases 1+2 landed (stable surface exists). Residual = **Phase 3 versioned
      stable API module → PROMOTED to active 2026-06-23** (Sergiy "внеси в спринт"); see the "Promoted to active"
      queue at the top of Active tasks.
- [x] **ssc-new-audit** ✓ DONE 2026-06-19 — verified and tightened the local `ssc new` /
      standalone-install surface without touching Maven/publication. Fixed `NewProject.create` to best-effort
      `git init -q`; fixed `ssc new` usage to list all bundled templates; made root `install.sh` match docs
      (`./install.sh` prints standalone Coursier/Homebrew/curl guidance, `./install.sh --dev` runs monorepo
      staging); clarified `specs/arch-ssc-new.md` (plugin template intentionally has no `project/plugins.sbt`;
      live channel publication remains deferred); updated the old benchmark note to use `install.sh --dev`.
      Added tests for all six templates, output-dir aliases, placeholder-free rendering, git-init, and release
      fixtures. Verify: `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/ssc-new-audit && sbt
      "cli/testOnly scalascript.cli.NewProjectTest scalascript.cli.StandaloneInstallFixturesTest"` → 8/8 green.
- [x] **board-ledger-hygiene** ✓ DONE 2026-06-19 — docs-only cleanup. Marked the duplicate
      `sbt-plugin-finish` open item as actionable-scope done/Maven-gated, and removed three stale
      `Status: open` lines inside fixed `BUGS.md` entries (`jvmgen-multishot-handle-result-any`,
      `jvmgen-handle-in-arg-position`, `js-self-handling-cps-fn-not-run`). Verify:
      `git grep -n "\*\*Status:\*\* open\|Status: open" -- BUGS.md` → no matches, and
      `git grep -n "^- \[ \] \*\*sbt-plugin-finish" -- SPRINT.md` → no matches.
- [x] **theme-b-build-registry-consolidation** — Phase 3 is **MOOT** (triaged 2026-06-18):
      `PluginManifest`/`LocalRegistry` are the **implementation** the facade is built on (not removable
      wrappers — `BackendRegistry` uses `PluginManifest`; `ImportResolver`/`PluginCommands` use
      `LocalRegistry`), and `isStdPluginInterpreterTest` is already gone. Nothing to remove. OPTIONAL
      Phase 4 (family registries) remains, demand-driven.
- [x] **module-graph-grouping** — ✓ INVESTIGATED → leave-as-is (2026-06-18, `docs/module-graph-findings.md`):
      197 modules; the per-impl module IS the SPI boundary; grouping either collapses it or is a no-op on
      the graph. No action.
- [ ] **std-nfc-packager-adapters** — BLOCKED autonomously: needs real iOS/Android/Web-NFC packager
      integration + device/browser harnesses. Native platform follow-up; can't verify without targets.
- [ ] **wallet-browser-ws-itest** — BLOCKED autonomously: real browser-WebSocket integration; full run
      needs a browser.

**Genuine remaining BUILD work** (across Tiers): no bounded autonomous build slice is currently ready here.
The old sbt-plugin build pieces are done and publication is Maven-gated; build-registry Phase 3 is moot and
Phase 4 is demand-driven; meta-v2 Tracks A/B/C are actionable-scope done with only deferred edge cases. The
small residuals above are blocked by real browser/device/external inputs. See BACKLOG "Roadmap reality check".

### Excluded from the sprint (deferred / blocked — stay in BACKLOG, NOT actionable now)

- **Maven Central + sbt Plugin Portal** (roadmap #8 / Theme C) — LAST, explicit-go only.
- **direct-style-eval** — DEFERRED, data-disproven ("do not start").
- **hof-glue-jit-compile**, **vectorize-pure-loop** — deferred perf (sub-15% ceiling / speculative SIMD).
- **agent P3b embedded transport** — blocked on rozum shipping the `rozum-embed` crate.
- **WalletConnect project-ID** — blocked on an external decision.
- **Hardware-wallet Vault (Ledger)**, **MPC Vault** — need real hardware / external SDKs; can't verify autonomously.
