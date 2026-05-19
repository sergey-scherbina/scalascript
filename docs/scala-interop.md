# Scala ↔ ScalaScript Interop — Specification

Status: **Tiers 1 and 2 landed.**  Tier 1 = compiler-emitted
`scalaFacade` field on `ModuleInterface`.  Tier 2 = the
`scalascript-interop` library (`interop/` subproject) — `FacadeGenerator`,
`ScalascriptLoader`, `Effects`, `Actors`; 34 tests green.
Tier 3 (sbt plugin) and Tier 4 (`--emit-scala-facade` compiler flag) are
still open.  Tracking: see "Scala ↔ ScalaScript interop" milestone in
`MILESTONES.md`.

This document specifies how a regular Scala 3 project (sbt, Mill, Maven,
plain `scala-cli`) consumes code that was authored and compiled in
ScalaScript (`.ssc` source → `.scjvm` / linked JAR via the v2.0
separate-compilation pipeline).

The goal is to make ScalaScript-built artifacts a **first-class JVM
library citizen** — drop-in usable from Scala code without manual FQN
demangling, without surprising runtime errors, and without coupling the
consumer to internal compiler details.

---

## 1. Problem statement

A `.ssc` file compiled via `ssc compile-jvm --bytecode` + `ssc link
--backend jvm --bytecode -o lib.jar` produces a perfectly valid JVM JAR.
A Scala consumer can put it on the classpath today, but the developer
experience is rough:

1. **FQN mangling.** ScalaScript collapses `package: std.foo` + `def add`
   into the JVM symbol `std_foo_add` (underscore-separated, to avoid
   linker collisions across modules sharing a package).  Scala consumers
   must write `import _ssc_runtime.std_foo_add` instead of the natural
   `import std.foo.add`.

2. **Runtime preamble dependency.** Every linked JAR ships its user-code
   classes plus a shared `_runtime.scjvm-runtime` artifact (~570 KB,
   wrapped in `object _ssc_runtime`) carrying `effectsRuntime`,
   `_println`, async helpers, etc.  The classes inside `_ssc_runtime`
   are an undocumented internal API — consumers don't know what they
   may rely on.

3. **Effects + actors interop.** ScalaScript functions that use the
   effect / actor runtime (`Perform`, `Handle`, `runActors`, …)
   compile to CPS-transformed Scala that needs to be invoked within a
   matching runtime scope (`_handle { … }`).  A Scala consumer who
   calls such a function directly without setting up the runtime gets
   either a `UnhandledEffect` exception or a hang.

4. **Source attach.** IDEs that source-attach a JAR show the emitted
   Scala source, not the original `.ssc`.  Phase 4's JSR-45 SMAP fixes
   stack traces but not the "go to definition / step into" experience
   for Scala-side consumers.

5. **Typeclass instances** (anonymous `given` bridges).  ScalaScript's
   `given Eq[Int] with { … }` becomes `std_eq_given_Eq_Int` (Stage
   5.7 — stable structural identity).  Scala-side `summon[Eq[Int]]`
   doesn't find it unless an explicit re-export is in scope.

None of these are bugs.  They're consequences of the v2.0 mangling +
runtime-split design.  This spec proposes a layered solution that
removes the friction without changing the compiler's output ABI.

---

## 2. Design principles

1. **Zero runtime overhead.**  Bridges are pure delegation (`export` /
   `inline` aliases).  No reflection on the hot path.
2. **Backward-compatible with the v2.0 ABI.**  Adds optional fields to
   existing artifacts; never breaks existing readers.
3. **Layered, opt-in.**  Three tiers, each usable independently:
   compiler-emitted metadata → consumer library → build-tool plugin.
   A consumer can stop at any tier that meets their needs.
4. **Match Scala 3 idioms.**  Re-export everything via `export`, not
   `def x = _ssc_runtime.x` shims.  Typeclasses use `given` re-exports.
   Effect helpers use context functions / `using`-clauses.
5. **No ScalaScript-specific runtime types leak.**  The Scala consumer
   only sees regular Scala types (`Int`, `String`, case classes,
   typeclasses).  Internal runtime types (`_ssc_runtime.Cont`,
   `_handle`) are bridged to standard Scala equivalents
   (`Future`, `Either`, etc.) where possible.

---

## 3. Architecture — three tiers

```
┌──────────────────────────────────────────────────────────────┐
│  Tier 3: scalascript-sbt-plugin (or Mill/Maven equivalent)   │
│  Reads .scim, generates target/src_managed/*.scala facades   │
└──────────────────────┬───────────────────────────────────────┘
                       │ reads
                       ▼
┌──────────────────────────────────────────────────────────────┐
│  Tier 2: scalascript-interop library                         │
│   - package scalascript.interop                              │
│   - Runtime: Effects, Actors, ClassLoader helpers            │
│   - Facade-generation primitives (source for the plugin)     │
└──────────────────────┬───────────────────────────────────────┘
                       │ reads
                       ▼
┌──────────────────────────────────────────────────────────────┐
│  Tier 1: compiler emits scalaFacade metadata in .scim         │
│   - ModuleInterface.scalaFacade: Map[String, String]         │
│   - natural-name → mangled-FQN mapping                       │
│   - emitted by InterfaceExtractor; consumed by the library   │
└──────────────────────────────────────────────────────────────┘
```

Each tier is a separate deliverable.  Tier 1 lives in this repo; Tier 2
and Tier 3 can live in this repo or in `scalascript-interop` /
`scalascript-sbt-plugin` repos — TBD when implementation starts.

---

## 4. Tier 1 — compiler-emitted facade metadata

### 4.1 New field on `ModuleInterface`

```scala
// ir/src/main/scala/scalascript/ir/Ir.scala
case class ModuleInterface(
  magic:           String,
  abiVersion:      String,
  pkg:             List[String],
  moduleName:      Option[String],
  // ... existing fields ...
  scalaFacade:     Map[String, String] = Map.empty   // NEW
) derives ReadWriter
```

**Semantics.**  `scalaFacade` maps **natural dotted FQN** (the name a
Scala consumer wants to write) → **mangled JVM FQN** (the actual
class/object/def in the compiled `.class` file).

Example for `std/eq.ssc` with `package: std.eq` exporting `Eq` and
`given Eq[Int]`:

```json
"scalaFacade": {
  "std.eq.Eq":              "_ssc_runtime.std_eq_Eq",
  "std.eq.given_Eq_Int":    "_ssc_runtime.std_eq_given_Eq_Int",
  "std.eq.eqv":             "_ssc_runtime.std_eq_eqv",
  "std.eq.neqv":            "_ssc_runtime.std_eq_neqv"
}
```

### 4.2 Emit logic

In `core/artifact/InterfaceExtractor.scala`, after collecting
`exports: List[ExportedSymbol]`, build `scalaFacade` by computing
`Linker.mangle(pkg, exportName)` for each entry.  Re-use the existing
`Linker.mangle` so the table is exactly what the linker will produce.

`InterfaceExtractor.MaxNestedDepth = 3` already supports nested
`ExportedSymbol.nested` walks; the facade entry for nested members
joins with `.` on the natural side and `_` on the mangled side
(`std.foo.Bar.apply` → `_ssc_runtime.std_foo_Bar_apply`).

### 4.3 ABI compatibility

`scalaFacade` defaults to `Map.empty`, making it absent-tolerant under
the existing ABI policy (see `docs/v2.0-artifact-format.md` §6).
Add 4 new tests to `ArtifactAbiCompatibilityTest`:

1. New `.scim` with `scalaFacade` round-trips identically.
2. Legacy `.scim` (without `scalaFacade`) reads back as `Map.empty`.
3. `scalaFacade` keys are case-sensitive (Scala is case-sensitive).
4. Empty map serialises as `"scalaFacade": {}` (canonical).

### 4.4 Effort

~30 LoC in `InterfaceExtractor`; ~10 LoC in `Ir.scala`; 4 ABI tests;
1 `InterfaceExtractorTest` round-trip case.  **Half a day.**

---

## 5. Tier 2 — `scalascript-interop` library

A separate Scala 3 module / artifact: `scalascript-interop_3-X.Y.jar`.
Two source files would lay the foundation:

### 5.1 `scalascript.interop.facade`

Reads a `.scim` from a classpath resource or a path, generates Scala
source for `export` declarations.  Used by the SBT plugin (Tier 3) and
by anyone who wants to embed facade generation in their own tooling.

```scala
package scalascript.interop.facade

object FacadeGenerator:
  /** Read all .scim files under `artifactDir`, return generated Scala
   *  source that re-exports every entry in `scalaFacade` under its
   *  natural name. */
  def generate(artifactDir: os.Path): String

  /** Same but for a single .scim, returning per-package source files. */
  def generateOne(scimPath: os.Path): Map[String, String]
```

Generated output structure for `std/eq.ssc`:

```scala
// auto-generated by scalascript-interop
package std.eq:
  export _root_._ssc_runtime.std_eq_Eq as Eq
  given Eq[Int] = _root_._ssc_runtime.std_eq_given_Eq_Int
  inline def eqv[A](a: A, b: A)(using Eq[A]): Boolean =
    _root_._ssc_runtime.std_eq_eqv(a, b)
  inline def neqv[A](a: A, b: A)(using Eq[A]): Boolean =
    _root_._ssc_runtime.std_eq_neqv(a, b)
```

The `inline` wrapper for term-level re-exports gives Scala 3's
inliner everything it needs to elide the bridge call at the use site.

### 5.2 `scalascript.interop.runtime` — effects + actors

```scala
package scalascript.interop.runtime

/** Run a ScalaScript effectful computation from regular Scala code. */
object Effects:
  /** Wrap a no-arg ScalaScript function that uses Perform/Handle.
   *  Returns Either[EffectError, A] — never throws. */
  def runEffects[A](body: => A): Either[EffectError, A]

  /** Async variant returning Future[A]. */
  def runEffectsAsync[A](body: => A): scala.concurrent.Future[A]

/** Spawn typed actors from Scala. */
object Actors:
  type ActorRef[T]
  def spawn[T](behavior: ActorContext[T] ?=> T => Unit): ActorRef[T]
  extension [T](ref: ActorRef[T])
    def !(msg: T): Unit
    def ?(msg: T): scala.concurrent.Future[Any]
```

Internally these wrap `_ssc_runtime._handle`, `_ssc_runtime.runActors`,
`_ssc_runtime.spawn` with appropriate Scala-friendly result types.

### 5.3 `scalascript.interop.loader`

For dynamic / plugin-style use:

```scala
package scalascript.interop.loader

class ScalascriptLoader(jar: os.Path):
  def call[A](naturalFQN: String, args: Any*): A
  def lookupGiven[T](typeName: String): Option[T]
```

Reads the embedded `.scim` (we'd embed it in the linked JAR for this —
see §7) to translate natural FQNs to mangled JVM symbols, then uses
reflection to invoke.  Slow path; not for hot loops.

### 5.4 Versioning

`scalascript-interop_3` versions track ScalaScript ABI versions:

| ScalaScript ABI | Compatible interop |
|----------------|--------------------|
| 2.0            | 0.x                |
| 2.1 (future)   | 1.x                |

Within a major ScalaScript ABI, interop is `+ minor` backward-compatible.

### 5.5 Effort

~500 LoC across 3-4 files; ~30 tests.  **~1 week.**

---

## 6. Tier 3 — `scalascript-sbt-plugin`

A separate repo / artifact: `sbt-scalascript-interop`.

### 6.1 sbt API

```scala
addSbtPlugin("org.scalascript" %% "sbt-scalascript-interop" % "0.1.0")

// In build.sbt
enablePlugins(ScalascriptInteropPlugin)
scalascriptArtifactDir := file(".ssc-artifacts")
scalascriptInteropVersion := "0.1.0"   // optional, defaults to compatible
```

The plugin:
- Adds `scalascript-interop_3` to `libraryDependencies` automatically.
- Adds a `Compile / sourceGenerators` task that runs
  `FacadeGenerator.generate` against `scalascriptArtifactDir` and writes
  to `target/scala-3.x/src_managed/scalascript-facade/*.scala`.
- Adds the linked `.jar` (output of `ssc link --bytecode`) as an
  `unmanagedJars` entry.
- Adds the Scala 3 source path of `.ssc.scala` sidecars (Phase 3+
  source maps) to the source-jar so IDE source-attach surfaces both
  layers.

### 6.2 Mill / scala-cli equivalents

Mill: a `ScalascriptInteropModule` trait providing the same task set.
scala-cli: a `directive:` block (`//> using interop "scalascript-interop"
artifactDir ".ssc-artifacts"`).

These can be added on demand.

### 6.3 Effort

~300 LoC for sbt plugin + ~15 tests against a fixture project.
**~1 week.**

---

## 7. Optional — compiler-level `--emit-scala-facade` flag

If Tiers 1-3 prove the design and adoption is real, a compiler-side
shortcut for users who want a fully self-contained JAR without
build-tool support:

```bash
ssc link --backend jvm --bytecode --emit-scala-facade artifacts/ -o lib.jar
```

This:
1. Runs `FacadeGenerator.generate` over the artifact dir.
2. Compiles the generated Scala source via the existing `Scala3Driver`
   (in-process, ~120 ms).
3. Adds the resulting `.class` files to the linked JAR.
4. Embeds the per-module `.scim` files as JAR resources at
   `META-INF/scalascript/<module>.scim` (lets `ScalascriptLoader` find
   them without a separate file).

After this, a Scala consumer can drop the JAR on classpath, write
`import std.eq.given`, and everything Just Works™.  No SBT plugin
needed, no metadata downloads, no version coordination.

### 7.1 Effort

~150 LoC in `cli/Main.scala` + `cli/JvmBytecode.scala` + 5 CLI tests.
**~2 days** — once Tier 2 exists.

---

## 8. Compatibility & out of scope

### 8.1 In scope (v0.x)

- Scala 3 consumer projects (3.5+).  Use of `export` requires 3.5+.
- JVM target only (the v2.0 separate-compilation pipeline only emits
  JVM bytecode under `--bytecode`).
- ScalaScript ABI 2.0.

### 8.2 Out of scope (future iterations)

- **Scala 2 consumers.**  Scala 2 lacks `export`; we'd need an alias
  generator using `type` aliases + `val` shims.  Defer until demand.
- **JS-side interop.**  Linked `out.js` is already classpath-free;
  consumers `require`/`import` it like any UMD bundle.  No facade
  needed in JS; a separate doc would cover ergonomic patterns.
- **Cross-compilation type-checking via TASTy.**  Our JVM bytecode
  ships `.tasty`, but our `.scim` doesn't carry enough type info for
  the Scala 3 compiler to type-check against a `.ssc` without
  importing the JAR.  Once Stage 5.4's `parseSType` covers enough
  types (and once `Typer` infers more precisely), TASTy-aware
  cross-compilation becomes feasible.
- **REPL integration.**  `scala-cli repl` with a `--scalascript-deps`
  flag — sugar on top of Tier 3, defer.

---

## 9. Open questions

1. **Where do the libraries live?**  Decision needed when Tier 2 work
   starts: in this repo as `interop/` subproject, or in a separate
   `scalascript-interop` repo?  Tier 1 (the compiler change) lives here
   regardless.

2. **Should `scalaFacade` include private symbols?**  Today
   `InterfaceExtractor` respects `exports:` front-matter — private
   helpers don't appear in `.scim`.  The facade should match — only
   exported symbols.  Confirmed implicit; pin with an
   `InterfaceExtractor` test.

3. **Effect-runtime API surface.**  `runEffects[A]` must decide what
   to do when the user's body performs an effect that the wrapper
   doesn't handle.  Options: re-throw as `UnhandledEffect` (current
   internal behaviour); convert to `Left(UnhandledEffect)`; require
   the caller to register handlers explicitly via `runEffectsWith`.
   Conservative default: `Left(UnhandledEffect)` so Scala consumers
   can't get a hard crash from purity-broken `.ssc` code.

4. **Anonymous given identity stability.**  Stage 5.7 made
   `given Eq[Int] with { … }` produce `given_Eq_Int`.  The facade
   re-export uses this name.  If the user refactors `Eq[List[A]]` →
   `Eq[List[Int]]`, the witness name changes (the type-arg-folding
   rule drops type variables).  Consumers binding the witness directly
   would see a breaking name change.  Mitigation: encourage consumers
   to use `summon[Eq[List[Int]]]` instead of the witness name.
   Document in §1.5 of any consumer-facing tutorial.

5. **`_root_._ssc_runtime` collision.**  If a Scala consumer happens to
   have an unrelated `_ssc_runtime` package on classpath (extremely
   unlikely but possible), facades break.  Add a fallback: emit
   `_root_.org.scalascript.runtime` as the user-visible name in the
   facade when an environment variable `SSC_RUNTIME_PKG` is set at
   compile time.  Defer until first reported.

---

## 10. Migration plan

For existing v2.0 artifacts (no `scalaFacade` field):
- The interop library detects absence and falls back to computing the
  facade table on the fly using `Linker.mangle` against the artifact's
  `exports`.  Result is equivalent (modulo perf — one-shot mangle vs.
  table lookup).  This means users can adopt the library before
  recompiling their `.ssc` sources.

For consumers upgrading from manual `import _ssc_runtime.*` patterns:
- Tier 2 ships an opt-in `compat-runtime` re-export so existing
  patterns keep working; deprecated with a `@deprecated` note pointing
  to the new natural-FQN form.

---

## 11. Implementation order

1. **Tier 1 — compiler-emitted metadata.** (½ day)
   - `scalaFacade` field on `ModuleInterface`; emit in extractor;
     ABI tests; round-trip test.
   - Lands in this repo, in main `core/` module.
2. **Tier 2 — interop library.** (~1 week)
   - 3 source files (`facade`, `runtime`, `loader`); 30 tests.
   - Lands in `interop/` subproject here OR in a new repo (TBD).
3. **Tier 3 — sbt plugin.** (~1 week)
   - sbt plugin + Mill module + scala-cli directive.
   - Lands in `scalascript-sbt-plugin` repo (separate, easier
     publish cadence).
4. **Tier 4 — `--emit-scala-facade` flag.** (~2 days)
   - Only after Tiers 2-3 prove the design.

Tier 1 is the foundation; Tiers 2-4 can be tackled in any order once
Tier 1 lands.

---

## 12. Related work

- `docs/separate-compilation-plan.md` — the v2.0 separate-compilation
  pipeline this interop builds on.
- `docs/v2.0-artifact-format.md` — wire spec for `.scim` / `.scjvm`.
- `docs/v2.0-getting-started.md` — current end-user flow for the
  ScalaScript side.

Comparable language ecosystems:
- **Kotlin → Java interop**: Kotlin generates `@JvmName` annotations
  + companion-object facade `.class` files.  Tier 1's `scalaFacade` is
  the analogue, but the consumer-side facade is generated externally
  instead of bundled.
- **Clojure → Java interop**: `gen-class` + `:methods` declaration in
  `ns` form.  Strict, manual, opt-in.  Our facade is fully automatic.
- **Rust `cbindgen`**: generates a `.h` header from Rust source for C
  consumers.  Tier 3 is the SBT equivalent: builds a Scala-shaped
  header from `.scim`.

These references inform the design but the closest analogue is
**Scala 3's own `export` keyword** — which we leverage directly rather
than re-inventing a shim mechanism.
