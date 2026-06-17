# Macro Expansion on the Generated Backends (JVM / JS)

Status: **in progress** — JVM slice first. Tracked as `macro-codegen-backends` in
`BACKLOG.md`. Unblocks `arch-metaprogramming-v2.md` Track B3.

## Problem

Restricted quoted macros (`specs/arch-metaprogramming-v2.md` P4 + Track B) run **only on
the interpreter** today. The `emit` / `build` / `run --backend jvm|js` codegen path runs
`JvmGen.generate` / `JsGen.generate` on the parsed `ast.Module`:

- `JvmGen`/`JsGen` resolve + inline imported modules at the **source/tree level**
  themselves (`JvmGen.scala` `ImportResolver.resolve` + `Parser.parse`) and rely on
  **scalac's own `inline`** for cross-module `inline def` — which is why P3 cross-module
  inline "works" on JVM with no Linker involvement.
- Macros break because scalac cannot run ScalaScript's `__ssc_macro__` / `Expr` /
  `QuotedContext` / `__ssc_quote__` constructs. The macro entrypoint
  `inline def label(x: Int): String = ${ labelImpl('x) }` and the impl
  `def labelImpl(x: Expr[Int]): Expr[String] = …` are emitted verbatim → scalac errors.
- `Linker.expandMacroSource` (the B1/B2 const-fold) only runs in the **separate
  `ssc link` artifact pipeline**, which does not feed `JvmGen` (type gap: the Linker
  emits `NormalizedModule`; codegen consumes `ast.Module` / `cb.tree`).

There is no JVM/JS macro test.

## Design

A **pre-codegen `ast.Module` transform** — `scalascript.artifact.MacroCodegen.expand` —
applied at the top of `JvmGen.generate` (and later `JsGen.generate`). It reuses the
tested B1/B2 const-fold (`Linker.expandMacroSource`).

**Strict no-op invariant:** if the module declares no quoted-macro entrypoints, `expand`
returns the module unchanged. Macro-free modules (the overwhelming majority) are byte-for-byte
untouched, so the change **cannot regress** working codegen. Modules with macros currently
fail to compile, so any improvement is strictly better.

### Algorithm

1. **Detect macros.** Scan the module's parseable code-block trees for:
   - *entrypoints* — `inline def NAME(params) = __ssc_macro__(IMPL(__ssc_quote__("p", p), …))`
     (the parser-preprocessed shape of `${ IMPL('p) }`). Record `NAME → (IMPL, quotedParams)`.
   - *impls* — `def IMPL(params): Expr[…] = BODY` whose `BODY` is a direct quote
     (`__ssc_quote_expr__(…)`) or an `asValue match`. Record `IMPL → BODY.syntax`.
   - Build the macro table `NAME → MacroExpansion(quotedParams, BODY)` and the **strip-set**
     `{ every NAME } ∪ { every IMPL }`.
   - If the table is empty → return the module unchanged (no-op).
2. **Per code block**, rewrite the tree:
   - Extract the block's top-level statements (`Source.stats` / `Term.Block.stats`; if the
     tree is some other shape, leave the block unchanged — safe).
   - **Strip** statements that are macro defs (a `Defn.Def` whose name is in the strip-set).
   - **Render** the kept statements to source and run `Linker.expandMacroSource(src, table)`
     — literal-arg call sites fold to the `Some` branch, others to the `None` direct quote;
     direct-quote macros expand by lambda-lifting (existing behaviour).
   - **Re-parse** the expanded source (`Parser.parseScalaWithDiagnostic`) to a fresh
     `cb.tree`; rebuild the `Content.CodeBlock` with the new source + tree.
3. Reassemble the module with rewritten sections.

### Why source-level expand + re-parse

scalameta has no `Tree.transform`; the const-fold (`expandMacroSource`) already operates on
source text and is unit-tested. Stripping by **statement name** (not text/line range) is
robust to formatting. Re-parsing yields a tree JvmGen consumes unchanged.

## Conformance

`examples/quoted-macro-constfold.ssc` (`asValue match`, literal arg) and a direct-quote
macro (`'{ $x + 1 }`) must produce identical output on the **interpreter** and on **JVM via
scala-cli** (mirror `MirrorOfJvmConformanceTest`). Expected: `label(7)` → `literal: 7`,
`plusOne(41)` → `42`.

## Scope

- **JVM first** (this slice). **JS** is a follow-up applying the same `MacroCodegen.expand`
  at the top of `JsGen.generate`.
- `@wasm` / native: out of scope (no macro demand there yet).
- Non-product / sum-type / multi-clause macros: out of scope (Track B literal-arg slice).
