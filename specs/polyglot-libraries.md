# ScalaScript as portable polyglot libraries (minimal core + cross-language reuse)

Status: **living spec; major implementation slices landed (updated 2026-06-23)**.

Implementation update (2026-06-23): the original "design only" baseline below is now
mostly implemented. The block-form SPI, typed/lossless `SpiValue` boundary, plugin
`preludeSymbols`, logger/random/clock/env/state/retry/cache/http effect migrations,
JS/JVM/Rust runtime-resource extraction, no-domain bundled plugin distribution split,
advanced plugin strict opt-in, actors provider/session seam, and JS/JVM/Rust/Java optics
host libraries have landed. The standalone `Backend.effectHandlers` map sketched in early
sections was superseded by the implemented `Backend.blockForms` + per-block
`EffectHandler` contract described in §2d and proven by the effect plugins. Remaining
implementation work is tracked by sharper items: `core-min-value-unification` for the deep
data-value refactor, plus deliberately deferred/optional Stream/Actors interpreter-internal
code moves that should wait for a concrete consumer.

This spec unifies two sprint directives from Sergiy:

- **A — Minimize the core.** Shrink every runtime and compiler to its irreducible
  kernel; move every *feature/domain* (effects, actors, cluster, optics, storage,
  signals, http, sql, ui, …) out of core into self-contained modules behind an SPI.
- **B — Make everything reusable from any host language.** Each ScalaScript feature
  should be consumable as a *native library* in each host ecosystem — from Scala and
  Java (a JVM jar), from JavaScript (an npm package), from Rust (a crate) — not only
  from a `.ssc` program.

They are one program. **A self-contained module is the unit of reuse.** Once a feature
is extracted behind a stable SPI (A), packaging it as a per-host library (B) is the
*same artifact* exported with a host-facing API. A thin core + clean module boundaries
is the prerequisite for clean cross-language libraries.

---

## 1. Current state (measured 2026-06-22)

The plugin spine already exists and works:

- **SPI:** `runtime/backend/spi/.../IntrinsicImpl.scala` (`InlineCode` / `RuntimeCall`
  / `HostCallback` / `NativeImpl`) + `Backend.scala` (`intrinsics`, `runtimePreamble`,
  `sqlBlockRunner`, `interpolators`, `preprocessors`).
- **Discovery + dispatch:** `BackendRegistry` (ServiceLoader for in-process plugins;
  `.sscpkg`/`plugin.yaml` for out-of-process, lazily spawned). The interpreter
  lazy-loads plugin intrinsics on the first unresolved name (`Interpreter.ensurePluginsLoaded`).
- **40** `runtime/std/*` plugins + **13** `backend/*` plugins already ship.

But the core still bakes in feature/domain code that the SPI *cannot yet* express:

| Where | What's still hardcoded | LOC (≈) |
|---|---|---|
| `EvalRuntime.scala` | **27 block-form keywords** matched as literal `Term.Apply(Term.Name("run*"/"httpClient"/"validate"/"bench"/…), body)` | ~700 |
| `ActorInterp.scala` | actors **+** an entire cluster/raft/gossip/phi stack fused in one file | 2890 |
| `EffectHandlers.scala` + `StdEffectsRuntime.scala` | Logger/Random/Clock/Env/Http/State/Stream effect handlers + `Perform(…)` emitters | ~710 |
| `OpticsRuntime` / `StorageRuntime` / `SignalRuntime` / `DatasetRuntime` / `ClusterRoutesRuntime` / `OpenApiRuntime` | self-contained feature subsystems (state lives in closures / no interp state) | ~1500 |
| `Typer.scala` | `effectBuiltins` (~120 names incl. ~50 cluster verbs), `pluginObjects=List("oauth","oidc","spark","http")`, `pluginBuiltins` (payment/storage/distributed) | ~150 |
| `transform/` | feature desugarings: `RouteDeriver` (http), `SqlBindRewriter`/`DirectAnorm` (sql), `MarkupLiteralLower` (ui), `ContentToolkitLint` (ui), `RemoteClientDeriver` | ~1100 |
| codegen backends | feature runtime strings baked in backend-core (JS `JsRuntimeGraphql`/`IndexedDb`/`Mcp`/`Signals`/`Payment`; JVM `JvmRuntimeMcp`; …), already `Capability`-gated but still stored in backend-core | ~12000 (JS preamble) |

**Total movable out of the interpreter core: ~6,000–7,500 LOC** before touching codegen
strings. **Genuinely-core, stays:** `DispatchRuntime` (value semantics), `PatternRuntime`,
the `EvalRuntime` eval kernel + numeric JIT ladder, `Interpreter` orchestrator, the
`vm/jit/*` tiers, `DerivesRuntime`, `Parser`, `CapabilityCheck`, and the `Perform`/`FlatMap`
effect-trampoline mechanism (the *mechanism* plugins register into).

---

## 2. The keystone: two small SPI additions

The single structural blocker for the biggest wins is that the SPI registers *named
function intrinsics* but has **no way to register a syntactic block-form or an
effect handler**. Two small extensions to existing machinery unblock everything:

### 2a. Block-form registration

```scala
// Backend SPI addition
trait Backend:
  def blockForms: Map[String, BlockForm] = Map.empty

/** A `keyword(args*) { body }` form. `runBody` is the already-built body Computation
 *  the runner drives; `args` are the head-clause argument values. */
trait BlockForm:
  def run(ctx: BlockContext, args: List[Value], body: Computation): Computation
```

`EvalRuntime` replaces its 27 hardcoded `Term.Apply(Term.Name("runLogger"), …)` cases
with one generic lookup: `BackendRegistry.blockForms.get(name).map(_.run(…))`. The
keyword stays reserved in the parser/typer (a tiny name set the plugin contributes —
see 2c), but the *behaviour* ships from the plugin.

### 2b. Effect-handler registration

```scala
trait Backend:
  def effectHandlers: Map[String, EffectHandler] = Map.empty

/** Resolves a `Perform(effectName, op, args)` and resumes the continuation. */
trait EffectHandler:
  def handle(ctx: EffectContext, op: String, args: List[Value], resume: Value => Computation): Computation
```

The `EffectsRuntime` `Perform`-resolver loop consults `effectHandlers` for an effect
name it doesn't have a built-in for. `EffectHandlers.scala`'s Logger/Clock/Random/Env/…
move into plugins; the `"Actor"` effect resolver moves into an actor plugin.

### 2c. Prelude-symbol / type-signature contribution

```scala
trait Backend:
  def preludeSymbols: Set[String] = Set.empty               // names the typer must accept
  def typeSignatures: Map[String, SType] = Map.empty        // optional precise types
```

Deletes the hardcoded `effectBuiltins` / `pluginObjects` / `pluginBuiltins` tables from
`Typer.scala` (the `extraBuiltins` hook at `Typer.scala:1955` is the seam that already
exists for `ssc check`). Each plugin declares its own names; the typer unions them.

These three hooks are *additive* and backward-compatible: core keeps its built-ins until
each is migrated, one plugin at a time.

### 2d. Resolving the `Computation`-exposure question (the keystone design fork)

Probing the interpreter (2026-06-22) found the real blocker: every feature being extracted
(Logger, optics, storage, actors) is coupled to **core types** — `Computation` / `Pure` /
`Perform` / `FlatMap` / `Value` and `interp.callValue` — and several are wired into the
`EvalRuntime` evaluator at *syntactic* points (`Focus[T]`, `.copy`, `runLogger { }`). A naive
effect-handler SPI that passed the raw `Computation` tree to a plugin would just **re-couple
the plugin to interpreter internals** — defeating the point.

**Resolution: the core owns the `Computation` trampoline; a plugin only ever sees
`(op, args) → reply` over `Value`.** This is exactly the shape the Rust runtime already uses
(`effect.rs`: `Handler = Box<dyn Fn(&[EffArg]) -> EffArg>` + a `run_with` driver that owns the
loop). Concretely:

The values crossing the boundary are typed — **not `Any`** — via a host-neutral, closed
`SpiValue` ADT defined in the spi module (the interp converts its runtime `Value` ↔
`SpiValue`). Effect ops are almost all primitives, so the closed cases cover them; an
`Opaque(AnyRef)` case round-trips a closure/case-class unchanged. (Landed 2026-06-22 —
`SpiValue.scala`.) The contract (also in the spi module, depends only on `SpiValue`):

```scala
enum SpiValue:
  case IntV(Long); case DoubleV(Double); case StrV(String); case BoolV(Boolean); case UnitV
  case ListV(List[SpiValue]); case TupleV(List[SpiValue]); case OptV(Option[SpiValue])
  case MapV(List[(SpiValue, SpiValue)]); case Opaque(AnyRef)   // closure/user object, passed through

trait EffectHandler:                              // a stateful per-block-entry handler
  /** Reply to one operation; the CORE drives the Perform/FlatMap trampoline and resumes with
   *  this value — the plugin never touches `Computation`, and matches `SpiValue` type-safely. */
  def reply(op: String, args: List[SpiValue]): SpiValue

trait BlockForm:
  def effectName: String                                          // e.g. "Logger"
  def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler  // sink/format/accumulator
  def result(bodyResult: SpiValue, handler: EffectHandler): SpiValue      // e.g. (result, collectedLogs)
```

This remains deliberately a one-reply plugin SPI. It is not the managed
continuation ABI from
[`scala3-bidirectional-control.md`](scala3-bidirectional-control.md): plugins
using this contract never receive a continuation and cannot make a one-shot
`SpiValue` reply multi-shot by saving or copying it.

The `EffectsRuntime` `Perform`/`FlatMap` loop (which stays core) gains one fallback: for a
`Perform(name, op, args)` it doesn't handle built-in, look up `BackendRegistry.effectHandlers(name)`
and call `handler.reply(...)`, resuming with the returned `Value`. `EvalRuntime` replaces the
27 hardcoded `runX { }` cases with: look up `blockForms(name)`, build `newState`, eval the body
with that handler installed, return `result(bodyResult, state)`.

What this buys:
- **Plugins depend only on `Value` + the spi module**, never on the interpreter's `Computation`
  internals — a genuine decoupling, and the same `(op,args)→Value` contract maps directly to the
  per-host library boundary in §4 (a Java/JS/Rust host supplies the same reply function).
- It covers **all one-shot-reply standard effects** (Logger.info→Unit, Random.nextInt→Int,
  Clock.now→Long, Env.get→Option, State.get/put). **Multi-shot** (NonDet) and handlers that
  inspect the *continuation* stay core/out-of-scope (same R.6 boundary as rust effects).
- **Syntactic forms** (`Focus[T]`, `.copy`, `effect`/`handle`) are *language* forms, not feature
  runners — they stay core. Optics' value-builders (`lensGet`/`lensSet`/`buildPrism`) can still
  move to a plugin as plain `Value`-returning intrinsics; only the `Focus[T](_.a.b)` *path-syntax*
  recogniser stays a thin core hook that calls the plugin's builder. So optics splits: ~450 LOC
  of pure builders → plugin, ~40 LOC of syntax-recognition → core.

With 2d resolved, the Logger keystone (phase 1) is implementable without re-coupling, and the
same `(op,args)→reply` contract is the seam Task B's host libraries plug into.

---

## 3. Task A — core-minimization roadmap (phased, each independently shippable)

Ranked by impact × tractability (from the extraction analysis):

1. **`logger-effect-plugin`** — the keystone proof. Extract `runLogger`/`runLoggerJson`/
   `runLoggerToList` (block-forms in `EvalRuntime:3467`, handler in `EffectHandlers:15`,
   emitters in `StdEffectsRuntime:40`). Deterministic text/JSON output → trivial golden
   test in `interpreter-plugin-tests`. Lands hooks 2a+2b.
2. **`clock` / `random` / `env` / `state` effects → plugin(s)** — same template, high
   LOC-to-effort. Reuses the keystone.
3. **`optics-plugin`** — `OpticsRuntime` (492 LOC, state-in-closures, near-zero
   coupling). No block-form needed (pure globals) — extractable *today*.
4. **`storage` / `signals` → plugin** — `StorageRuntime` ("no Interpreter state"),
   `SignalRuntime`. Low coupling.
5. **`actors-plugin`** — `ActorInterp` mailbox/scheduler (single call site
   `EvalRuntime:3658`; already on the `Perform("Actor", …)` channel). Needs 2a+2b.
6. **`cluster-plugin`** — the ~500 LOC of raft/gossip/phi inside `ActorInterp` +
   `ClusterRoutesRuntime` + the ~50 cluster verbs in `Typer.effectBuiltins`. Depends on
   #5 and on 2c. Highest single-extraction impact.
7. **Migrate Typer feature tables onto `preludeSymbols`/`typeSignatures`** (2c). Do once
   a few plugins validate the SPI shape.
8. **Move codegen feature runtime strings into plugin `runtimePreamble`** — JS
   `JsRuntimeGraphql/IndexedDb/Mcp/Signals/Payment`, JVM `JvmRuntimeMcp`. Mechanical;
   JS already capability-gates them, so this is "stop storing the strings in backend-core."

Definition of done per phase: the feature builds + runs identically with the plugin
loaded, and FAILS LOUDLY (clear "feature X needs plugin Y") when it isn't — never a
silent miscompile.

---

## 4. Task B — each module as a per-host native library

Once a feature is a self-contained module (Task A), it already *emits* per-backend code.
Task B is **packaging those emissions as stable, standalone libraries** with a host-facing
API — so a Scala/Java/JS/Rust developer can use ScalaScript's crypto, optics, effects,
actors, etc. without writing `.ssc`.

The unit is the module. For each module `M` and each host target, the build produces:

| Host | Artifact | API surface | Build path (today) |
|---|---|---|---|
| **Scala (JVM)** | a `.jar` | a Scala facade object `Ssc.M` exposing the module's public defs as typed Scala methods | `JvmGen` already emits Scala 3; add a `--emit-lib` mode that emits a *stable public facade* + `sbt publish` (the `arch-sbt-plugin` is the lever) |
| **Java (JVM)** | same `.jar` | a Java-friendly facade (no Scala-isms: `java.util.List`, no implicits, overloads for defaults) generated from the same module signatures | a `JavaFacadeEmitter` over the module's exported signatures |
| **JavaScript** | an npm package (ESM + `.d.ts`) | the module's emitted JS + its `runtimePreamble`, wrapped as a tree-shakeable ES module with a hand-stable export map + generated TypeScript types | `JsGen` already emits ESM + tree-shakes; add a `package.json`/`.d.ts` emit + a stable export surface |
| **Rust** | a crate | the module's `RustRuntimeTemplates` + emitted `pub fn`s, packaged as a `Cargo` library crate (`[lib]`) with a stable `pub` API | `RustGen` already emits a crate; the `emit-rust --lib` path (`src/lib.rs`) exists — add `Cargo.toml` metadata + a curated `pub` surface |

### 4.1 The hard part — a stable, idiomatic host API

Emitting code is solved; the design problem is the **public API contract**:

1. **Value mapping.** Each host needs a faithful mapping of ScalaScript values
   (`Int`=64-bit, `List`, `Map`, `Option`, `Either`, tuples, `Char`, case classes,
   effects). Most of this is already specified per backend (e.g. `Int` is 64-bit; JS
   `_Char`; rust tagless-final effects). The library boundary must expose *host-native*
   types at the edge (`java.util.List`, JS arrays, Rust `Vec`) and convert at the seam,
   not leak the internal representation (`{_isTuple:true}` arrays, `_Char` boxes).

   **Compatibility note (2026-07-14):** the 64-bit statement describes the current
   v1/v2 runtime representation and is not the new cross-language public ABI.
   [`scala3-bidirectional-control.md`](scala3-bidirectional-control.md) requires
   canonical `I32`/`I64` descriptor types (`Int`/`Long` at the language boundary),
   explicit v2 normalization, and rejection of ambiguous legacy exports until width
   evidence is retained.
2. **Effects at the boundary.** A pure/`Logger`-style effectful def must be callable from
   the host with the host supplying the handler (a Java `Logger`, a JS callback, a Rust
   `impl Trait`). The tagless-final rust design (§ `rust-effects.md`) and the
   effect-handler SPI (2b) generalise to "host provides the handler struct/closure."
3. **Stability.** Generated internals churn; the *public facade* must be curated +
   versioned (semver per module), with a golden API-signature test per host so a codegen
   change can't silently break the published surface.
4. **No `.ssc` runtime dependency.** A published library must be self-contained in its
   host ecosystem (the JS npm package bundles only the preambles it uses; the rust crate
   has no `ssc` build-dep; the jar carries the runtime it needs). The `Capability`-gated
   preamble split already makes this tractable.

### 4.2 What's reusable vs. backend-specific

- **Reusable across all hosts:** pure value logic, collections, optics, typeclasses,
  effects-as-handlers, crypto, json/codecs, math, string ops. These should publish to all
  four hosts.
- **Host/backend-specific:** anything tied to one runtime (JVM Spark, JS IndexedDb/DOM,
  rust hyper-serve). These publish only to their host. The module's `Capability`
  declaration already encodes this; the library build just filters by host support.

---

## 5. How A and B compose

```
         ┌──────────────────────────────────────────────┐
         │  feature module  (e.g. optics, logger, actors)│   ← Task A: extracted behind SPI
         │   • intrinsics / blockForms / effectHandlers  │
         │   • per-backend runtime (JS str / JVM / Rust) │
         │   • preludeSymbols / typeSignatures           │
         └───────────────┬───────────────┬───────────────┘
                         │               │
            Task A: run inside `.ssc`     Task B: publish as a host library
            via BackendRegistry           via per-host packagers
                         │               │
              ┌──────────┘     ┌─────────┼──────────┬─────────┐
              ▼                ▼         ▼          ▼         ▼
          interpreter        Scala/JVM  Java     npm(JS)    crate(Rust)
          + emit backends      jar      facade   +.d.ts     [lib]
```

The same module definition serves both: the SPI registration (A) and the library
packaging (B) read the *same* per-backend emission + signature metadata.

---

## 6. Phasing

- **Phase 0 (this spec).** Agree the two keystone SPIs (block-form, effect-handler) +
  the prelude-symbol hook, and the per-host library packaging contract.
- **Phase 1 — prove A.** Land the keystone SPIs via `logger-effect-plugin` (smallest,
  deterministic). Parity test green.
- **Phase 2 — prove B.** Take one *pure* module (proposal: **optics** — zero effects,
  zero host coupling) and publish it to all four hosts (jar + Java facade + npm + crate)
  with a golden API-signature test per host. This validates the value-mapping + stable-API
  design end-to-end on the easy case before effects/actors.
- **Phase 3 — widen A.** Effects (clock/random/env/state), storage, signals.
- **Phase 4 — actors + cluster** (the big extraction), then the Typer-table + codegen-string
  cleanups.
- **Phase 5 — widen B.** Publish the effectful + crypto/json modules per host, with the
  host-supplies-the-handler effect boundary.

Each phase is independently shippable and never regresses the previous (the core keeps a
built-in until its plugin lands; loud failure if a needed plugin is absent).

---

## 7. Risks / open questions

- **Block-form SPI shape.** Some block-forms (`handle`, `effect`) are *language* forms,
  not plugin features — they must stay core. The SPI is only for *feature* runners
  (`runLogger`, `runActors`, `httpClient`). Draw the line explicitly.
- **Lazy-loading + typing.** `ssc check` must know a plugin's `preludeSymbols` without
  running it. Needs the plugin's signature metadata available at check-time (the
  `extraBuiltins` seam, extended to carry types).
- **Stable host APIs vs. generated churn.** Requires a curated public facade per module +
  per-host golden signature tests; not free. This is the main ongoing cost of B.
- **JS/Rust effect handlers from the host.** The host-provides-the-handler model is proven
  for rust (tagless-final) and for JS (callback); the Scala/Java boundary needs a small
  handler-interface convention.
- **Versioning.** Per-module semver across four ecosystems is real release engineering;
  start with one module (optics) to shake out the pipeline.

## 7a. Decided direction (2026-06-22, with Sergiy)

Sergiy's directive: *"вынести в плагины вообще всё что возможно."* Four decisions set the charter:

1. **Strategy: B → A (enabler-first).** Build the Typer-prelude SPI hook
   (`preludeSymbols`/`typeSignatures`) FIRST — so every plugin (the 40 existing + all future ones)
   declares its check-time symbols and `ssc check` resolves them without the ~150 hardcoded names.
   That hook is the keystone that makes aggressive extraction safe (otherwise `check` breaks or you
   re-hardcode). THEN finish the effect layer (Http, Actors, the `effectHandlers` hook). Then extract
   further cold features as the SPI allows.
2. **The irreducible kernel is fixed.** Language forms — `handle`/`effect`/`resume`, `match`,
   `for`/`while`/`if`, `val`/`def`, application — stay core **forever** (they are the language: syntax
   + the effect trampoline). Only *features* (runners / handlers / intrinsics / cold stdlib) extract.
3. **Hot-path stdlib stays core.** Collections / strings / numerics and the JIT path stay in core — no
   dispatch/boxing regression. Extract only cold features. (Revisit only behind a zero-cost fast-path
   SPI, not now.)
4. **Distribution: hybrid.** Core + *essential* plugins ship bundled+default-loaded (UX unchanged);
   *advanced* plugins (crypto / sql / ui / blockchain / payments / …) become opt-in via the existing
   `pkg:` registry + `ssc add`. The essential/advanced split is a follow-on categorization (not
   blocking the SPI work). "Minimize core" therefore means *minimize the code in the `core` module*,
   not shrink the default UX.

### 7b. Hybrid split implementation slice: no-domain bundled opt-in

This slice makes the distribution decision concrete without requiring a public package
registry, DNS name, or hosting.

**Behavior**

- [x] The build registry classifies every in-tree std plugin as either **essential**
  (bundled and auto-loaded from `bin/lib/compiler/plugins`) or **advanced** (bundled
  locally but not auto-loaded).
- [x] `installBin` stages essential `.sscpkg` files in the existing auto-load directory
  and stages advanced `.sscpkg` files in a sibling local-availability directory
  (`bin/lib/compiler/plugin-available`). No network, registry domain, or hosting is
  required for this artifact split.
- [x] Advanced plugins remain usable explicitly through existing local mechanisms:
  `ssc --plugin <path-to-sscpkg> ...` for one command, or `ssc plugin install
  <path-to-sscpkg>` to copy the archive into the user's plugin directory.
- [x] The split must not remove advanced names from the Typer prelude in the same
  change. Strict opt-in typing is a follow-up after each advanced plugin publishes
  adequate `preludeSymbols`; otherwise this slice would regress `ssc check` for
  existing examples without giving users a local install path first.

**Initial classification**

Essential plugins are the first-party capabilities that are either already part of the
default language experience, low-dependency utility modules, or migrated effect runners
whose default behavior must remain available: `json`, `content`, `frontend`, `request`,
`fetch`, `graph`, `http`, `ws`, `mcp`, `remote`, `streams`, `deploy`, `uuid`, `mime`,
`fs`, `os`, `yaml`, `bench`, `logger`, `random`, `clock`, `env`, `state`, `retry`, and
`cache`. `deploy` is transitional-essential because the CLI still imports deploy
types directly and stages its jar on the startup classpath.

Advanced plugins are opt-in because they are provider-specific, security-sensitive,
heavy, peripheral, deployment-oriented, or UI/platform-specific: `swing`, `auth`,
`oauth`, `sql`, `pwa`, `nfc`, `dstreams`, `graphql`, `payment-request`, `payments`,
`crypto`, `pdf`, and `smtp`.

**Results (2026-06-22)**

- `cd /Users/sergiy/work/my/scalascript-wt-coremin-hybrid-split && sbt "cli/compile"`
  passed in 82s.
- `cd /Users/sergiy/work/my/scalascript-wt-coremin-hybrid-split && sbt "cli/installBin"`
  passed in 10s after the compile warmup and staged **25** essential `.sscpkg` files in
  `bin/lib/compiler/plugins` plus **13** advanced `.sscpkg` files in
  `bin/lib/compiler/plugin-available`.
- The explicit `installBin` package list now validates against `allPlugins` and fails
  on missing or duplicate std plugin ids. The validation caught the pre-existing omission
  of `fs`, `os`, and `yaml` from the staged `.sscpkg` list; those are now staged as
  essential plugins.

**Out of scope**

- Publishing an online package registry, reserving a domain, or adding remote hosting.
- Changing `pkg:` URL resolution semantics.
- Removing `pluginObjects` / `pluginBuiltins` compatibility names from `Typer.scala`.
  That belongs to a later strict-opt-in prelude cleanup after advanced plugins declare
  precise prelude metadata.

---

## 8. Related specs

- [`arch-library-modularity.md`](arch-library-modularity.md) — existing library/module layering.
- [`arch-stable-spi.md`](arch-stable-spi.md) — the stable plugin/SPI boundary work.
- [`arch-registry.md`](arch-registry.md) — `BackendRegistry` discovery.
- [`rust-effects.md`](rust-effects.md) — the tagless-final effect boundary (the host-handler model for rust).
- [`arch-sbt-plugin.md`](arch-sbt-plugin.md) — the JVM publish lever for Task B.
