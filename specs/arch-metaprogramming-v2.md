# Metaprogramming v2.x Roadmap

Status: **partially implemented** (corrected 2026-06-17 — was stale "deferred / planning").
All three phases have a working base in the code; what remains is the explicitly-"Planned"
extension bullets in §4 (NOT a from-scratch build). See §4b for the remaining-work slice
breakdown. Tracked as `arch-metaprogramming-v2` in `BACKLOG.md`.
Prerequisite: [`specs/arch-dsl-hooks.md`](arch-dsl-hooks.md) (DSL platform hooks, v1.x).
Existing v1.x surface: [`specs/metaprogramming.md`](metaprogramming.md).

**Landed bases (audited 2026-06-17, origin/main):**
- **P3 cross-module `inline`** — `Linker` builds an `inlineTable` from foreign `.scim` and
  expands call sites via lambda-lifting (`expandInlineSource`, `arch-meta-v2-p3`); `LinkerRewriteTest`.
- **P4 `QuotedMacro[A]`** — `${ impl('x) }` entrypoints + direct `'{ $x + 1 }` bodies; parser
  lowering, `MacroImpl`/`MacroImplRef` IR (`Ir.scala:423`), link-time expansion, interpreter
  run-path parity (`Expr.asValue`/`asTerm`), tiered diagnostics; `examples/quoted-macro-interpreter.ssc`.
- **P5 user `derives` via `Mirror`** — summon-able `Mirror.Of/ProductOf/SumOf`, `Mirror.of[T]`,
  `label/elemLabels/elemTypes/variants/fromProduct/ordinal`, user `derived(m: Mirror)` dispatch
  (interpreter); `examples/custom-derives-mirror.ssc`, `InlineDerivesTest`.

---

## 1. Scope decision recap

`specs/metaprogramming.md §1` defines the v1.x boundary:

- ✅ **`inline` + `derives`** — shipped (v1.14).
- ✅ **User-defined `StringContext` interpolators** — shipped; typed return
  via `InterpolatorRegistry` (arch-dsl-hooks Phase 1).
- ✅ **Compile-time interpolator validators** — shipped via `InterpolatorCheck`
  (arch-dsl-hooks Phase 4).
- ❌ **`quoted.Expr` / `quotes.reflect` user macros** — deferred to v2.x.
- ❌ **User-defined compiler phase injection** — out of scope indefinitely.
- ❌ **User-defined keywords / operators** — out of scope indefinitely.

This document specifies the v2.x macro work that becomes relevant once
the plugin ecosystem (arch-stable-spi, arch-distribution) has validated
demand for full compile-time metaprogramming.

## 2. Goals (v2.x target)

- **Phase 3 — Compile-time `inline` expansion** across module boundaries.
  Today `inline` is intra-module only; cross-module `inline` requires the
  function to be in a separately compiled module with IR-level inlining.
- **Phase 4 — Restricted `quoted.Expr[A]` surface** — a subset of Scala 3's
  `quotes.reflect` that covers the 80% use case without exposing
  `scala.quoted.Quotes` internals.  Plugin authors write code-generating
  macros that produce typed ScalaScript IR, not Scala trees.
- **Phase 5 — `Mirror`-based generic derivation for user typeclasses** — full
  `derives` for arbitrary user-defined typeclasses (today only the stdlib
  typeclasses in `runtime/std/{eq,show,hash,order}.ssc` are derivable).

## 3. Non-goals (permanent)

These are explicitly listed in `specs/dsl.md §9` and will not be revisited:

- User-defined keywords or syntax forms.
- Compiler phase injection (typer / normaliser / codegen phases).
- Reflection over JVM bytecode at runtime (Java `reflect` is always available
  on the JVM, but ScalaScript semantics do not expose it).
- Unhygienic macros (string → source text → re-parse).

## 4. Architecture sketch (v2.x)

### Phase 3 — Cross-module `inline`

IR-level inlining: when `ssc link` encounters a call to a cross-module
`inline def`, it reads the callee's `.scim` IR, splices the function body
at the call site, and re-typechecks.  This is purely an IR transform;
no new surface syntax.

Prerequisite: v2.0 separate compilation (already landed, 2026-05-20).

### Phase 4 — `QuotedMacro[A]` surface

ScalaScript-specific `quoted.Expr`-like surface:

```scala
// In a macro module (compiled first):
inline def myMacro(x: Int): String = ${ myMacroImpl('x) }

def myMacroImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
  x.asValue match
    case Some(n) => Expr(s"literal: $n")
    case None    => '{ "dynamic: " + $x.toString }
```

`QuotedContext` exposes:
- `Expr[A].asValue: Option[A]` — extract compile-time constant.
- `Expr[A].asTerm: ScalaScriptTerm` — opaque IR reference.
- `'{...}` quoting syntax for building `Expr[A]` values.

Does NOT expose `quotes.reflect.TypeRepr`, `Symbol`, `Flags`, etc.  The
simpler surface covers most practical macro use cases.

Backends see the macro as a `MacroImpl` call in the IR; expansion happens at
link time after separate compilation.

Landed first slice (2026-05-29):

- Parser preprocessing accepts the restricted syntax and lowers it to stable
  helper calls for Scalameta only; original `Content.CodeBlock.source` stays
  unchanged.
- `.scim` interfaces carry `MacroImplRef` metadata on the inline entrypoint
  and `macroQuotedBodySource` metadata on direct implementation helpers.
- IR includes `MacroImpl`, so macro call sites can be represented before link
  expansion.
- `Linker` expands simple quoted-expression macro bodies at link time using
  the same lambda-lifted shape as cross-module `inline`:
  `plusOne(n)` → `((x) => x + 1)(n)`.

Landed interpreter/runtime parity slice (2026-05-29):

- Parser helper lowering now carries both the quoted parameter name and the
  runtime value: `'x` becomes `__ssc_quote__("x", x)` and `$x` becomes
  `__ssc_splice__("x", x)`.
- The interpreter registers lightweight `Expr` / `QuotedContext` helpers plus
  `__ssc_macro__`, `__ssc_quote__`, `__ssc_quote_expr__`, and `__ssc_splice__`
  so direct quoted macro bodies can run without first going through `ssc link`.
- `Expr[A].asValue` returns the quoted runtime value as `Option[A]` in this
  restricted interpreter slice; `Expr[A].asTerm` returns an opaque
  `ScalaScriptTerm` value with `name` and `value` fields.
- Example: `examples/quoted-macro-interpreter.ssc`.

Landed diagnostics slice (2026-05-29):

- Unsupported macro entrypoints such as `${ impl(x) }` are rewritten to an
  explicit diagnostic helper instead of silently becoming a non-expanding
  macro call. The diagnostic explains that restricted quoted macros require
  quoted arguments, for example `${ impl('x) }`.
- The interpreter reports `quoted macro error: ...` for unsupported helper
  forms on the `ssc run` path.
- `Linker.normalizeQuotedMacroBody` rejects non-quoted implementation bodies
  with a direct message: restricted quoted macros must return a direct quoted
  expression such as `'{ $x + 1 }`.

Landed richer unsupported-body diagnostics slice (2026-05-29):

- Unsupported implementation bodies are now classified before the generic
  fallback. `Expr.asValue match` reports that compile-time branching in macro
  implementations is not implemented yet.
- `Expr(...)` construction reports that link-time expansion currently requires
  direct quote syntax, `'{ ... }`.
- Nested/non-top-level quotes and splices outside a direct quoted expression
  report targeted guidance while preserving the direct quoted-expression happy
  path.

Current implementation boundary:

- Implemented: `${ impl('x) }` entrypoints, direct `'{ $x + ... }` quoted
  bodies, cross-module source expansion in `ssc link`, and interpreter/run-path
  parity for the same direct quoted-body subset including runtime
  `Expr.asValue` / `Expr.asTerm`; unsupported entrypoints and non-quoted
  macro bodies now produce explicit diagnostics, with targeted hints for common
  unsupported body shapes.
- Planned: compile-time constant folding inside macro implementations, richer
  quoted terms, source-positioned diagnostics, and broader generated backend
  conformance.

### Phase 5 — Full `derives` for user typeclasses

Today `derives` is hard-coded for the stdlib typeclasses.  Phase 5:

- `Mirror.Of[A]` exposed to user code via `scalascript.reflect.Mirror`.
- `inline match` on `Mirror.Product` / `Mirror.Sum` works in `derives` blocks
  for user-defined typeclasses.
- Example: a user-defined `Csv[A]` typeclass with `derived` method using
  `Mirror` works exactly like `Eq.derived`.

Landed first runtime slice (2026-05-29):

- The interpreter registers summon-able `Mirror.Of[T]` /
  `Mirror.ProductOf[T]` / `Mirror.SumOf[T]` values as types are declared.
- `Mirror.of[T]` returns the same runtime metadata.
- Mirror values expose `label`, `fields` (legacy alias), `elemLabels`,
  `elemTypes`, `variants`, `isProduct`, `isSum`, `fromProduct`, and `ordinal`.
- User-defined typeclasses can now implement `derived(m: Mirror)` and be used
  from `case class T(...) derives MyTypeclass` in the interpreter path.

Current implementation boundary:

- Implemented: interpreter/runtime Mirror metadata and custom typeclass
  `derived(m: Mirror)` dispatch.
- Planned: source-level `inline match` over `Mirror.Product/Sum`, richer
  compile-time tuple operations, and explicit cross-backend conformance for
  generated JVM/JS/WASM paths.

## 4b. Remaining-work slice breakdown (2026-06-17)

Audit conclusion: the §7 effort estimate ("2–3 + 6–8 + 2–3 weeks") is for a from-scratch
build that **already happened in part**. The remaining work is the "Planned" bullets above,
which decompose into small, independently-shippable slices (one worktree/claim/PR each,
`on==off`-style verified). The tracks are independent of one another.

**Track A — P5 cross-backend conformance** *(crisp acceptance = "make JVM/JS match the interpreter").*

**SCOPE CORRECTION (verified 2026-06-17, A1 investigation):** this is BIGGER than the original
"smallest/days" estimate. `derives` is **interpreter-only** on the generated backends — confirmed by
running both stdlib and custom cases through scala-cli/node:
- `JvmGen` emits the `derives` clause **verbatim** and passes it to scalac: stdlib `case class P(...) derives Eq`
  → scalac error (Eq has no Scala-3 `derived`); custom `derived(m: Mirror)` → `Not found: type Mirror` +
  `method derived takes explicit term parameters` (SS contract ≠ Scala-3 derivation contract).
- `JsGen` never synthesizes the instance → `summon[Csv[Person]]` resolves to an undefined `Csv_Person`.
- There is **no** `derives`→given desugaring in `AstToIr`/transform; only the interpreter's
  `DerivesRuntime.synthesizeDerivedInstance` exists.

So Track A = **implement `derives` typeclass synthesis on the generated backends** (a real feature in the
4200-line `JvmGen` + its ~180KB preamble, and in `JsGen`). Decomposition:
- **A1a** — ✓ DONE 2026-06-17. JVM `Mirror` runtime type in the preamble (phantom-typed
  `_SscMirror[A]` + `object Mirror{type Of/ProductOf/SumOf}` + bare `type Mirror`, emitted when the
  module references `Mirror`) + a per-top-level-product-type `given _SscMirror[T]` appended after the
  user blocks → `summon[Mirror.Of[T]]` resolves on the JVM with label/elemLabels/elemTypes/isProduct/
  fromProduct, matching the interpreter. `MirrorOfJvmConformanceTest` (interp baseline + scala-cli JVM).
  DEFERRED to follow-ups: sum-type mirrors (enum / sealed trait — variants/ordinal) and generic case
  classes (skipped for now); `fromProduct().field` round-trip on JVM needs dynamic field-access typing.
- **A1b** — ✓ DONE 2026-06-17. Custom `derives TC` synthesis on JVM. `_SscMirror[+A]` made covariant
  (so a per-type mirror is accepted where `def derived(m: Mirror)` expects `_SscMirror[Any]`).
  `JvmGen` detects user typeclasses with a `derived` method (`object Csv: def derived(m: Mirror)`),
  strips all-custom `derives TC` clauses from the emitted case classes (both the parsed tree and
  `block.src`, via tree positions), and appends
  `given TC[T] = TC.derived(summon[Mirror.Of[T]]).asInstanceOf[TC[T]]`. The JVM case of
  `CustomDerivesMirrorCrossBackendTest` now passes (flipped `ignore`→`test`). DEFERRED: `derives`
  clauses that MIX user + non-user typeclasses (left untouched — passthrough); sum-type derives.
- **A1c** — stdlib structural `derives Eq/Show/Hash/Order`. ✓ DONE on **JVM + JS** 2026-06-17.
  JVM: the A1b strip generalized to also handle the stdlib four; synthesizes structural givens using
  Scala `Product` — `Eq` = `a == b`, `Hash` = `a.hashCode`, `Show` = `_ssc_structShow`
  (`TypeName(field=value, ...)` via `productElementName`), `Order` = `_ssc_structCompare`. JS: `JsGen`
  registers EAGER structural givens in `_ssc_givens` (they depend only on runtime helpers, not a user
  `const`) — `Eq` reuses `_eq`, `Show`/`Order`/`Hash` use new `_ssc_structShow`/`_ssc_structCompare`/
  `_ssc_structHash` helpers (reading object keys minus `_type`/`_tag`). Both match
  `DerivesRuntime.structuralShow`/`structuralCompare`. A clause mixing a handled TC with an unknown one
  is left untouched (conservative). `StdlibDerivesJvmConformanceTest` (interp + JVM + JS).
- **A2** — ✓ DONE 2026-06-17. A1a+A1b equivalents on JS (`JsGen`). JsGen already emits case classes
  as plain constructor functions (the `derives` clause is dropped — no stripping needed). Added a JS
  `_ssc_mkMirror` + `_ssc_def_given` (lazy getter) runtime; `JsGen` registers a per-product-type
  Mirror object (eager) and a custom-derives instance (LAZY — JS `const` objects aren't hoisted, so
  the getter defers `TC.derived(...)` until the summon site runs) in `_ssc_givens` BEFORE the user
  blocks, and routes `summon[Mirror.Of[T]]` / `summon[TC[T]]` for these synthesized keys through
  `_resolveGiven` (explicit user-given summon untouched).
- **A3** — ✓ DONE. The pinned cross-backend bar is fully green (interp + JVM + JS).

**Conformance bar (2026-06-17):** `CustomDerivesMirrorCrossBackendTest` (custom derives) +
`StdlibDerivesJvmConformanceTest` (stdlib `Eq/Show/Hash/Order`) both PASS on interp + JVM + JS —
`derives` (custom AND stdlib) is now cross-backend complete for product (case-class) types.
`MirrorOfJvmConformanceTest` covers A1a (`summon[Mirror.Of[T]]` metadata on the JVM). **Remaining
Track A (deferred edge cases):** sum-type mirrors / derives (enum, sealed trait — variants/ordinal),
generic case classes, and `derives` clauses mixing user + stdlib + unknown typeclasses.

**Track B — P4 compile-time constant folding** *(self-contained; turns today's diagnostics at
`Linker.scala:~384/387` into real folds).* 
- **B1** — ✓ DONE 2026-06-18. `Expr.asValue match` const-fold in `Linker` macro expansion for the
  literal-arg case + interpreter parity. `InterfaceExtractor.extractMacroQuotedBody` now also captures
  `asValue match` bodies (not just direct `'{…}` quotes) so the entrypoint carries
  `expansionBodySource`. `Linker.expandMacroSource` splits the macro table: asValue-match macros are
  const-folded per call site (`parseAsValueFold` + scalameta `isLiteralArg`) by lambda-lifting the
  selected branch — literal arg → `Some(n)` branch, non-literal → `None` direct-quote fallback.
  Interpreter parity: the `${ }` splice (`__ssc_macro__`) unwraps an `Expr(v)` result to `v`.
  `examples/quoted-macro-constfold.ssc`; `LinkerRewriteTest` (7 cases) + `InlineDerivesTest`.
- **B2** — ✓ DONE 2026-06-18 (with B1). `Expr(e)` is unwrapped to its inner expression `e` at link
  time in `unwrapMacroBranch` (scalameta `Term.Apply(Term.Name("Expr"), …)`); the folded branch emits
  plain source the backend compiles.
- **B3** — ✓ **DONE 2026-06-18 — JVM + JS.** generated-backend conformance for a folded macro. The
  blocker (macros were interpreter-only on the generated backends) is resolved via the
  `macro-codegen-backends` pass: `scalascript.artifact.MacroCodegen.expand` runs at the top of
  `JvmGen.generate`/`generateUserOnly`(`+WithLineMap`) and `JsGen.generate`/`generateUserOnly`/
  `generateSegmented`, builds the macro table + strip-set from the module's own macro defs, drops the
  entrypoint + impl defs, and rewrites each call site to its beta-reduced expansion (reusing
  `parseAsValueFold`/`normalizeQuotedMacroBody`/`isLiteralArg`; literal arg → `Some` branch / const
  value, else the `None` direct quote). Strict no-op for macro-free modules. The emitter substitutes the
  bound variable directly (parenthesised) — scalac rejects the lambda-lift form and a block argument
  re-renders as a brace-arg. `QuotedMacroJvmConformanceTest` (scala-cli) +
  `QuotedMacroJsConformanceTest` (node) + `MacroCodegenTest`. spec `specs/macro-codegen-backends.md`.
  **Track B is now complete** (B1, B2, B3 all done).

**Track C — P3 robustness** *(extends the existing `Linker` inline base).* 
- **C1** — ✓ DONE 2026-06-18. Multi-clause inline (`inline def f(a)(b) = body`) cross-module expansion.
  Implemented without a scanner or `.scim` wire change: `InterfaceExtractor.extractInlineInfo` curries
  the tail parameter clauses into the body (params = first clause, body = `(b) => body`), so the
  existing single-clause call-site scanner rewrites the first clause and leaves the trailing `(y)` as an
  ordinary application → `((a) => (b) => body)(x)(y)`. `using`/`given` clauses dropped. `LinkerRewriteTest`
  (curried 2-/3-clause) + `InterfaceExtractorTest` (multi-clause body + using-drop).
- **C2** — post-expansion re-typecheck pass + source-positioned errors when an expansion doesn't typecheck.
  *(Open — the remaining Track C slice. Needs the Typer over expanded source + a position map back to the
  `.ssc`; the expansion currently only catches parse errors, not type errors.)*

**Recommended order:** originally "Track A first (smallest)" — but the 2026-06-17 A1 investigation
shows Track A is the LARGEST of the three (it's a from-scratch backend feature, not a parity tweak).
**Track B** (P4 const-fold) and **Track C** (P3 robustness) are the genuinely small, self-contained
slices that extend an existing working `Linker` base — prefer them if a quick win is wanted; reserve
Track A for when cross-backend `derives` is explicitly prioritized. Effort: B/C days-per-slice; A multi-day.

## 5. Dependency graph

```
v1.14  inline + derives (stdlib typeclasses)         ✓ landed
arch-dsl-hooks Phase 1  InterpolatorRegistry          planned v1.x
arch-dsl-hooks Phase 4  InterpolatorCheck             planned v1.x
  │
  └── arch-metaprogramming-v2 Phase 3  cross-module inline   v2.x
        │
        └── Phase 4  QuotedMacro surface                     v2.x
              │
              └── Phase 5  full Mirror derives               v2.x
```

## 6. Trigger conditions

Work on Phase 3 begins when:
- At least 3 external plugin authors have requested cross-module `inline`.
- `arch-stable-spi` and `arch-distribution` are landed (plugin ecosystem
  exists to validate demand).

Work on Phase 4 begins when:
- Phase 3 is landed and stable for ≥ 2 releases.
- A concrete community use case is identified that `InterpolatorCheck` +
  preprocessors cannot address.

Phase 5 is low-priority; the existing `derives` surface covers most demand.
Revisit after Phase 4 validation.

## 7. Effort estimates

**OUTDATED (2026-06-17):** these are the original from-scratch estimates. A large part of all
three phases has since landed (see Status header + §4 "Landed" notes). The *remaining* work is
the §4b slice breakdown — days-per-slice, not weeks. The table below is kept only for historical
context.

| Phase | Scope | Original estimate (from-scratch) |
|-------|-------|----------|
| 3 — cross-module inline | IR linker + inlining pass | 2-3 weeks |
| 4 — `QuotedMacro[A]` | New IR node + backends (3) | 6-8 weeks |
| 5 — full Mirror derives | Typer + IR + stdlib | 2-3 weeks |

Total (original): ~3 months for all three from scratch. **Actual remaining: the §4b slices.**

## 8. Open questions

1. Should `QuotedMacro` generate ScalaScript IR or Scala 3 trees?  IR is
   more portable (same macro works on JVM+JS); Scala 3 trees give access
   to the full Scala 3 type system but couple macros to JVM-only backends.
2. Should Phase 4 macros run in a forked JVM process (isolation) or in the
   compiler JVM (performance)?  Recommendation: forked for v2.x, in-process
   as an optimisation if demand warrants.
3. How does `QuotedMacro` interact with the `SourceLanguage` SPI?  A
   `graphql-plugin` could use a macro to parse the GraphQL schema at compile
   time and emit typed access code.  This is a compelling use case for Phase 4.
