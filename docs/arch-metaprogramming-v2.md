# Metaprogramming v2.x Roadmap

Status: **deferred / planning**.  Tracked as `arch-metaprogramming-v2` in `BACKLOG.md`.
Prerequisite: [`docs/arch-dsl-hooks.md`](arch-dsl-hooks.md) (DSL platform hooks, v1.x).
Existing v1.x surface: [`docs/metaprogramming.md`](metaprogramming.md).

---

## 1. Scope decision recap

`docs/metaprogramming.md §1` defines the v1.x boundary:

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

These are explicitly listed in `docs/dsl.md §9` and will not be revisited:

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

Current implementation boundary:

- Implemented: `${ impl('x) }` entrypoints, direct `'{ $x + ... }` quoted
  bodies, cross-module source expansion in `ssc link`.
- Planned: `Expr[A].asValue`, `Expr[A].asTerm`, constant folding inside macro
  implementations, richer quoted terms, diagnostics for unsupported macro
  bodies, and interpreter/run-path expansion parity.

### Phase 5 — Full `derives` for user typeclasses

Today `derives` is hard-coded for the stdlib typeclasses.  Phase 5:

- `Mirror.Of[A]` exposed to user code via `scalascript.reflect.Mirror`.
- `inline match` on `Mirror.Product` / `Mirror.Sum` works in `derives` blocks
  for user-defined typeclasses.
- Example: a user-defined `Csv[A]` typeclass with `derived` method using
  `Mirror` works exactly like `Eq.derived`.

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

| Phase | Scope | Estimate |
|-------|-------|----------|
| 3 — cross-module inline | IR linker + inlining pass | 2-3 weeks |
| 4 — `QuotedMacro[A]` | New IR node + backends (3) | 6-8 weeks |
| 5 — full Mirror derives | Typer + IR + stdlib | 2-3 weeks |

Total: ~3 months of focused work for all three phases.

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
