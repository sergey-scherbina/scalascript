# Macro Expansion on the Generated Backends (JVM / JS)

Status: **DONE 2026-06-18 — JVM + JS.** Tracked as `macro-codegen-backends` in `BACKLOG.md`.
Unblocks `arch-metaprogramming-v2.md` Track B3 on both generated backends:
`QuotedMacroJvmConformanceTest` (scala-cli) and `QuotedMacroJsConformanceTest` (node) each run
an `asValue match` + a direct-quote macro and match the interpreter. The same backend-agnostic
`MacroCodegen.expand` pass is hooked into `JvmGen.generate`/`generateUserOnly`(`+WithLineMap`)
and `JsGen.generate`/`generateUserOnly`/`generateSegmented`. The emitter beta-reduces by **direct
substitution** of the bound variable (wrapped in parens), not the lambda-lift form: scalac
rejects `((n) => body)(7)` ("missing parameter type") and a block argument
`f({ val n = 7; body })` re-renders as a brace-arg — a substituted parenthesised expression
embeds cleanly on both backends.

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

## Scope (single-module — DONE)

- **JVM + JS** — both done (the same `MacroCodegen.expand` pass, hooked into each backend's
  generate entry points), for macros **defined and used in the same module**.
- `@wasm` / native: out of scope (no macro demand there yet).
- Non-product / sum-type / multi-clause macros: out of scope (Track B literal-arg slice).

---

## Cross-module macros — design (NOT yet implemented)

**Status: design / open.** Tracked in `BACKLOG.md` as `macro-crossmodule`.

### Problem

A macro **defined in an imported module** and **called from a consumer** does not yet work on the
generated backends. `MacroCodegen.expand(consumerModule)` scans only the consumer's *own* code-block
trees for macro defs (`collectMacros`), finds none (the `inline def label = ${ … }` lives in the
imported module), and so the consumer's `label(7)` call is left unexpanded. `JvmGen`/`JsGen` then inline
the imported module's source verbatim (JvmGen `~2486`, JsGen `genImport` `~1946`) — there is **no
tree-shaking**, so the imported `inline def label = __ssc_macro__(…)` is emitted and the target compiler
fails on `__ssc_macro__`/`Expr`/`QuotedContext`.

So two things must happen: (1) the consumer's call sites must expand using the **imported** macro
table, and (2) the imported macro defs must be **stripped** when they are inlined.

### Approach A — `MacroCodegen` resolves imports itself

`MacroCodegen.expand(module, baseDir, moduleDeps, lockPath)` resolves the module's `Content.Import`s
(via `ImportResolver.resolve`), parses each, runs `collectMacros` on it, and merges the result into the
**call table** (used to expand call sites). The **strip-set stays local** (only this module's own defs).
Then `JvmGen`/`JsGen` additionally apply `MacroCodegen.expand` to each *imported* module at the inlining
point, which strips that module's macro defs.

- **Pro:** localized; reuses the existing `MacroCodegen` pass.
- **Con:** **double-parses every module's imports** — `MacroCodegen` parses them to build the table,
  then `JvmGen`/`JsGen` parse them again to inline. This is a **build-time perf regression on all
  import-having modules**, not just macro ones, unless gated. A correct cheap gate is hard: a macro call
  looks like any `name(args)` call, so you cannot tell whether an import is needed without parsing it.
  Also needs `moduleDeps`/`lockPath` threaded into `MacroCodegen` + transitive-import recursion.

### Approach B — expand after the backend inlines imports (RECOMMENDED)

Run macro expansion **after** `JvmGen`/`JsGen` have assembled the full block set (consumer + inlined
imports). At that point the macro defs and their call sites coexist in one unit, so the existing
single-module `collectMacros` + strip + expand logic applies directly — no import resolution inside
`MacroCodegen`, **no double-parse**.

- **Pro:** no perf regression (reuses the modules the backend already parsed); no `moduleDeps`/`lockPath`
  threading; transitive imports handled for free (everything is already inlined).
- **Con:** a deeper hook than the current `generate`-entry-point wrapper — the pass must run over the
  **assembled** blocks/trees (after `collectBlocks` in `JvmGen`, after segment assembly in `JsGen`),
  which is closer to the codegen core. The current `MacroCodegen.expand(module)` operates on an
  `ast.Module`; Approach B needs it to operate on the assembled representation (or to expand the
  assembled *source* before final emit).

**Recommendation: Approach B.** The double-parse cost of Approach A on every build is the dealbreaker;
Approach B has no steady-state cost and no new threading. The work is to find the assembled-block hook
in each backend and run the (already-written) expand+strip there, keeping the strict no-op for
macro-free units.

### Conformance (when implemented)

A library module that exports a macro + a consumer module that imports and calls it with a literal
argument must produce the same output on the **interpreter**, **JVM** (scala-cli), and **JS** (node).
Mirror `QuotedMacroJvmConformanceTest` but with a two-module fixture (lib `.ssc` + consumer `.ssc`).
