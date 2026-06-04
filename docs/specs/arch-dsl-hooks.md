# DSL Platform Hooks — Specification

Status: **implemented through Phase 4**.  Tracked as `arch-dsl-hooks` milestone
in `BACKLOG.md`.
Companion: [`docs/specs/dsl.md`](dsl.md), [`docs/specs/plugin-architecture.md`](plugin-architecture.md),
[`docs/specs/arch-stable-spi.md`](arch-stable-spi.md).

---

## 1. Goals

A library author can register:
- A new **fenced code-block language** (e.g. `` ```graphql `` ) — without
  editing the compiler.
- A new **string interpolator with a typed return value** (e.g. `gql"…"`
  returning `GqlQuery`) — without editing the five hard-coded interpolator
  tables in `Typer`, `EvalRuntime`, `JvmGen`, `JsGen`, `CapabilityCheck`.
- A new **preprocessor sugar pass** (e.g. a custom keyword or shorthand) —
  via a registered preprocessor, not a change to `Parser.scala`.

## 2. Non-goals

- User-defined keywords at the parser level (a full-parser extension, not a
  preprocessor — out of scope, explicitly listed in `docs/specs/dsl.md §9`).
- User-defined operator precedence or fixity declarations.
- Compiler phase injection (e.g. adding a new typer pass from a plugin).
- `quoted.Expr` / `quotes.reflect` macros (tracked separately in
  [`docs/specs/metaprogramming.md`](metaprogramming.md) §1 as v2.x).

## 3. Current state (pain points)

### 3a. Interpolator tables — 5-file churn

Adding `sql"…"` with a typed return `Query[A]` today requires changes in:
1. `lang/core/.../typer/Typer.scala:702` — type inference for the interpolator
2. `runtime/backend/interpreter/.../EvalRuntime.scala:554` — eval path
3. `runtime/backend/jvm/.../JvmGen.scala:4751` — JVM codegen
4. `runtime/backend/js/.../JsGen.scala:9732` and `:10718` — JS codegen (2 places)
5. `lang/core/.../validate/CapabilityCheck.scala:187-190` — capability gate

The `extension (sc: StringContext) def myDsl(args: Any*) = …` surface already
works end-to-end (generic `StringContext` fallback at `EvalRuntime.scala:597`)
for interpolators whose return type is *inferred by the compiler*.  The problem
is interpolators that need a **compiler-known return type** (so the typer
assigns the right `SType` instead of `SType.String`).

### 3b. Fenced language tags — in/out asymmetry

Built-in tags (`html`, `css`, `sql`, `xml`, `javascript`) now register through
`SourceLanguageRegistry` like third-party fenced languages. `sql` and
`transaction` own bind-aware lowering in SourceLanguage implementations; the
core normalizer keeps compatibility fallbacks only for library/test consumers
that use `core` without the bundled SourceLanguage plugins on the classpath.

### 3c. Preprocessors — hard-coded chain

`Parser.parseScalaWithDiagnostic` (line 978) calls:
```
preprocessExtern → preprocessEffects → preprocessSlashImports →
preprocessListLiterals → preprocessInlineImports
```
Each preprocessor is a `String => String` function inside `Parser.scala`.
There is no extension point; adding new sugar requires modifying `Parser.scala`.

## 4. Architecture

### 4a. `InterpolatorRegistry` — typed interpolator SPI

New registry in `lang/core/src/main/scala/scalascript/compiler/plugin/InterpolatorRegistry.scala`:

```scala
trait InterpolatorImpl {
  def name: String            // e.g. "gql", "graphql"
  def returnType: SType       // type assigned by the typer
  def capabilities: Set[Feature] = Set.empty  // optional gate
  def evalInterp(parts: List[String], args: List[PluginValue]): PluginComputation  // interpreter
  def jvmEmit(parts: List[String], args: List[JvmExpr]): JvmExpr                  // JVM codegen
  def jsEmit(parts: List[String],  args: List[JsExpr]):  JsExpr                   // JS codegen
}

object InterpolatorRegistry {
  def register(impl: InterpolatorImpl): Unit
  def lookup(name: String): Option[InterpolatorImpl]
}
```

Plugins register via `Backend.interpolators: Seq[InterpolatorImpl]` (new
field on `Backend` trait; default `Nil`).  `BackendRegistry` calls
`InterpolatorRegistry.register(impl)` for each plugin's interpolators at
startup.

Typer (`Typer.scala:702` switch): after the hard-coded list, fall through to
`InterpolatorRegistry.lookup(name).map(_.returnType).getOrElse(SType.String)`.

EvalRuntime (`EvalRuntime.scala:554`): after hard-coded list, try
`InterpolatorRegistry.lookup(name).map(_.evalInterp(...))`.

JvmGen / JsGen: same pattern — registry lookup before existing fallback.

CapabilityCheck: `InterpolatorRegistry.lookup(name).fold(())(impl =>
  impl.capabilities.foreach(checkFeature(_, node)))`.

The generic `StringContext` fallback (`EvalRuntime.scala:597`) stays for
interpolators without a registry entry — they return `String` as today.

**Migration target**: once all built-in interpolators are in the registry,
the hard-coded tables in the five files become the registry initialiser and
the switch arms are removed.  Built-ins become self-registering plugins.

### 4b. `SourceLanguage` SPI — migrate built-ins

Existing: `SourceLanguageRegistry` + `SourceLanguage` SPI already handle
third-party fenced languages.  Built-ins (`html`, `css`, `sql`, `xml`,
`javascript`) have separate hard-coded paths.

Change: Each built-in becomes a `SourceLanguage` class and registers itself via
`META-INF/services`.  The implementation now covers `scala`, `html`, `css`,
`javascript`/`js`, `xml`, bind-aware `sql`, and bind-aware `transaction`.
`Lang.scala` helpers remain as compatibility predicates for older codegen and
capability checks; new fenced-language routing should go through
`SourceLanguageRegistry`.

After the migration, adding `graphql` fenced blocks from an external plugin
is identical to what `html`/`sql` do internally — there is no asymmetry.

### 4c. `PreprocessorRegistry` — extensible sugar pipeline

New `PreprocessorRegistry` in `lang/core/src/main/scala/scalascript/parser/`:

```scala
trait Preprocessor {
  def name: String
  def priority: Int = 100  // lower = earlier in chain
  def apply(source: String): String
}

object PreprocessorRegistry {
  def register(p: Preprocessor): Unit
  def applyAll(source: String): String  // sorted by priority, fold left
}
```

The 5 existing preprocessors become `Preprocessor` instances registered at
static init time with priorities 10/20/30/40/50 (matching current order).
`Parser.parseScalaWithDiagnostic` calls `PreprocessorRegistry.applyAll(source)`.

Plugin preprocessors register via `Backend.preprocessors: Seq[Preprocessor]`
(new field; default `Nil`).

**What this enables**: a `csv-plugin` could register a preprocessor that
rewrites `csv"col1,col2"` → a `StringContext` extension call before the
parser even sees the source.  Or a `graphql-plugin` can add `graphql { … }`
block desugaring.  The limit is that preprocessors are `String => String` —
they run before parsing, so they can only manipulate text, not AST.

### 4d. Compile-time validators as plugins

Existing: `MarkupInterpolatorCheck.scala` validates `xml"…"` at compile time.
It's a hand-coded pass in `lang/core/src/main/scala/scalascript/transform/`.

Generalize:

```scala
trait InterpolatorCheck {
  def interpolatorName: String  // which interpolator to check
  def check(parts: List[String]): List[Diagnostic]
}

object InterpolatorCheckRegistry {
  def register(c: InterpolatorCheck): Unit
  def checkAll(name: String, parts: List[String]): List[Diagnostic]
}
```

`MarkupInterpolatorCheck` becomes an `InterpolatorCheck` registered at init.
The transform pass calls `InterpolatorCheckRegistry.checkAll` instead.
Plugin `xml-plugin` (or future `graphql-plugin`) can register their own
compile-time well-formedness checks without touching the transform pipeline.
Backends expose checks through `Backend.interpolatorChecks`; `BackendRegistry`
registers checks, interpolators, and preprocessors when ServiceLoader backends
are discovered.

## 5. Migration

| Existing | New | Compatibility |
|---------|-----|---------------|
| Hard-coded interpolator tables (5 files) | `InterpolatorRegistry` | Switch arms become registry entries; additive |
| `Lang.isXxx` / `Parser.scala:477` fenced routing | `SourceLanguage` SPI for all built-ins | Hard-coded paths become migration shim |
| `Parser.parseScalaWithDiagnostic` preprocessor chain | `PreprocessorRegistry` | Old functions become `Preprocessor` instances |
| `MarkupInterpolatorCheck` transform pass | `InterpolatorCheckRegistry` | Existing logic preserved, just registered |

No user-facing `.ssc` syntax changes.  Existing examples continue to work.

## 6. Phases

### Phase 1 — `InterpolatorRegistry` + first migration

- `InterpolatorRegistry` trait + `Backend.interpolators` field.
- Migrate `json"…"` and `html"…"` as showcase.
- Typer / EvalRuntime / JvmGen / JsGen / CapabilityCheck all consult registry.
- Tests: `json-plugin` and `markup-core` register interpolators; type-check +
  eval both return correct typed values.

### Phase 2 — `PreprocessorRegistry`

- `PreprocessorRegistry` + `Preprocessor` trait.
- 5 existing preprocessors converted to registered instances.
- `Parser.parseScalaWithDiagnostic` uses `PreprocessorRegistry.applyAll`.
- Tests: custom preprocessor registered in a test; source transformed
  correctly before parse.

### Phase 3 — `SourceLanguage` built-in migration

- `html`, `css`, `sql`, `xml`, `javascript` fenced tags become
  `SourceLanguage` implementations.
- `SourceLanguage.compileBlock` gets a backward-compatible attrs overload so
  `sql` can preserve `@db` and `@side`.
- `Normalize` routes built-in fenced blocks through `SourceLanguageRegistry`
  when the bundled plugins are present, with core-only SQL/transaction
  compatibility fallbacks.
- `transaction` uses the same path as `sql` even though it was not part of the
  original Phase 3 list, because it shares the same bind-aware fenced-language
  machinery.
- Tests: registry discovery for all built-ins, JS alias lookup, SQL and
  transaction bind-aware dispatch, XML/JS dispatch, and legacy SQL
  normalize/capability regression.

### Phase 4 — `InterpolatorCheckRegistry`

- `InterpolatorCheckRegistry` + `InterpolatorCheck` trait.
- `MarkupInterpolatorCheck` migrated.
- `Backend.interpolatorChecks` and `BackendRegistry` registration wiring.
- Tests: compile-time error for malformed `xml"…"` still emitted; custom
  registry check runs through the shared interpolation traversal.

## 7. Testing strategy

- Phase 1: `InterpolatorRegistryTest` — register a mock `MockInterpolator`,
  verify typer assigns correct return type, eval returns correct value,
  both JVM and JS codegen compile.
- Phase 2: `PreprocessorRegistryTest` — register a preprocessor that rewrites
  `FOO { x }` → `_foo(x)`, verify source after `applyAll` is correct.
- Phase 3: full regression for all fenced-block examples in `examples/`.
- Phase 4: `InterpolatorCheckRegistryTest` — malformed interpolator string
  emits expected `Diagnostic`.

## 8. Open questions

1. Should `InterpolatorImpl.jvmEmit` / `jsEmit` return strings (source
   fragments) or proper AST nodes?  AST is type-safe but requires exposing
   backend-internal types.  Recommendation: strings for Phase 1 (same as
   `Backend.runtimePreamble`), AST in v2.x.
2. Preprocessors are `String => String` — too coarse for complex sugar.
   Should Phase 2 introduce a `TokenPreprocessor` that runs after lexing?
3. Should built-in fenced languages (`html`, `css`) retain their hard-coded
   optimised paths as a fast-path fallback, or should the registry call be
   the only path (cleaner but slightly slower)?
