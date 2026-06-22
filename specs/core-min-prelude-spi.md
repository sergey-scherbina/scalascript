# Core-min: typed prelude SPI (`Backend.preludeSymbols`)

> Status: in progress (2026-06-22). Part of the core-minimization program
> (`specs/polyglot-libraries.md` §7a, decision: **B → A, names + full type-signatures**).
> This is the **keystone**: it makes plugin extraction *check-clean*.

## Problem

`ssc check` must resolve a plugin's public symbols (`runLogger`, `httpClient`, `Wallets`, …)
WITHOUT running the plugin. Today the Typer prelude **hardcodes** them:

- `Typer.createPrelude()` (`Typer.scala`) defines ~150 names across three hardcoded lists —
  `effectBuiltins`, `pluginObjects`, `pluginBuiltins` — each as `Symbol(name, variadic, Def)`
  (i.e. **type `Any`**, no real signature).
- `ssc check` (`Main.scala:5485`) additionally feeds `pluginBuiltins: Set[String]` =
  `BackendRegistry.inProcess.flatMap(_.intrinsics.keys)` into `extraBuiltins` —
  **names only**, again resolved to `Any`.

Consequences: (1) every feature extracted to a plugin must STILL have its names hand-added to a
core list, which defeats "minimize core"; (2) calls to plugin symbols are never type-checked
(everything is `Any`), so `ssc check` can't catch a wrong-arity / wrong-type plugin call.

## Goal

A plugin declares its public prelude symbols **with type signatures**; `ssc check` resolves and
**type-checks** calls to them — with no hardcoded core list. Reuse the existing typed-symbol
machinery (`reuse, don't invent`):

- `ExportedSymbol(name, fqn, kind, tpe: String = "Any", …)` already encodes a typed symbol; `tpe`
  is a rendered `SType.show` string.
- `InterfaceScope.parseSType(tpeStr): SType` (`private[scalascript]`) is the inverse of
  `SType.show` and already turns an `ExportedSymbol.tpe` back into an `SType`.
- `InterfaceScope.fromInterfaces` already builds a `Scope` of `Symbol(name, parseSType(tpe), kind)`
  from `.scim` `ModuleInterface`s for imports.

So a plugin contributes `ExportedSymbol`s; the Typer prelude defines them exactly like the
hardcoded builtins, but with the **declared** type instead of `variadic`.

## Design

### 1. SPI hook

`runtime/backend/spi/.../Backend.scala`:

```scala
/** Public symbols this plugin contributes to the `ssc check` PRELUDE — names + type
 *  signatures (as `SType.show` strings in `ExportedSymbol.tpe`). Lets `ssc check` resolve
 *  AND type-check calls to a plugin's intrinsics/objects/effect-runners without the names
 *  being hardcoded in the Typer prelude. Default empty. See specs/core-min-prelude-spi.md. */
def preludeSymbols: List[scalascript.ir.ExportedSymbol] = Nil
```

A plugin declares e.g.:
```scala
override def preludeSymbols = List(
  ExportedSymbol("runLogger", "runLogger", "def", "(String) => Unit"),
  ExportedSymbol("Logger",    "Logger",    "object", "Any"),
)
```

### 2. Typer

New constructor param `preludeSymbols: List[ExportedSymbol] = Nil` (threaded through
`typeCheckStrict` / `typeCheckWithInterfaces` overloads, beside `extraBuiltins`). In
`createPrelude()`, after the hardcoded lists:

```scala
preludeSymbols.foreach { es =>
  s.define(Symbol(es.name, InterfaceScope.parseSType(es.tpe), symbolKindOf(es.kind)))
}
```

`symbolKindOf`: `"object" → Object`, `"type" → Type`, else `Def` (mirrors `InterfaceScope`).
A plugin symbol with `tpe == "Any"` degrades to today's names-only behaviour (no regression).

### 3. `ssc check` wiring

`Main.scala`: collect `BackendRegistry.inProcess.flatMap(_.preludeSymbols)` once and thread it
into the Typer entry points in `checkOneFile`, alongside the existing `pluginBuiltins`. Keep
`pluginBuiltins` (names-only intrinsic keys) as a **fallback** so any symbol a plugin has not yet
migrated to `preludeSymbols` still resolves (no regression during incremental migration).

### 4. Prove it (this slice — DONE)

The keystone mechanism is proven at the **Typer layer** (which is exactly what `ssc check` invokes),
without depending on plugin-classpath loading in tests — `TyperPreludeSymbolsTest`:
- without `preludeSymbols`, strict mode flags the plugin name as undefined;
- with `preludeSymbols = [ExportedSymbol("plug","plug","def","(Int) => String")]`, the name RESOLVES;
- the declared TYPE flows — `def g(): Int = plug(1)` is flagged (plug returns String), and the same
  call typed correctly (`def g(): String = plug(1)`) passes. So it's the *type*, not just the name.

`ssc check` wiring (`Main.scala`) collects `BackendRegistry.inProcess.flatMap(_.preludeSymbols)` and
threads it into the Typer; `pluginBuiltins` (names-only) stays as the fallback. Compiles clean;
typer+artifact suites 499/0.

**Follow-on (own task `coremin-prelude-migrate`):** migrate real plugins to *declare* their public
symbols via `preludeSymbols` and *remove* them from the core `effectBuiltins`/`pluginObjects`/
`pluginBuiltins` lists (and run `ssc check` with the plugin loaded end-to-end). Block-form keywords
(`runLogger { … }`) need a signature convention vs ordinary `def` intrinsics — decide per plugin.

## Non-goals (follow-on)

- Migrating ALL ~150 hardcoded names off the core lists (incremental; the hook + one proof first).
- Codegen backends (JVM/JS/Rust/Wasm) learning plugin symbols — separate concern; this is
  `ssc check` / Typer only.
- Effect-handler / block-form SPI (separate keystone hooks — see SPRINT `coremin-effecthandlers-spi`).

## Verification

- New SPI hook compiles; default `Nil` keeps every existing plugin unchanged.
- A migrated plugin: `ssc check` resolves its symbol with the declared type; a typed-mismatch call
  is flagged; `pluginBuiltins` fallback keeps un-migrated names resolving.
- Full Typer suite + plugin-tests green (no regression from the new prelude entries).
