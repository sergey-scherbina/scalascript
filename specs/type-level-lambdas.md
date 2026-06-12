# Type-level lambdas

Status: **COMPLETE 2026-06-12.** Native `[X] =>> F[X]` and placeholder `Map[Int, _]`
both parse, run, and round-trip across all five backends (interp/jvm/js/rust + `.sscc`
v3 cache); placeholder aliases desugar at parse time (top-level + nested in
object/trait/class); the typer / `ssc check` β-reduces a type-lambda alias at its use
site (`IntKey[Long]` → `Map[Int, Long]`, p3). `TypeLambdaProgressTest` is all-green
(no pending).

## 1  Motivation & scope

ScalaScript is interpreter-first: **types are erased at runtime**. Type lambdas
are therefore *surface-only at runtime* — like `SType.HigherKinded` (`F[_]`) and
`SType.Match` (match types), they parse and round-trip through interface artifacts
and carry no runtime cost. They are NOT fully inert at *check* time, however: the
typer β-reduces a type-lambda alias applied at a use site (`type IntKey = [V] =>>
Map[Int, V]; IntKey[Long]` → `Map[Int, Long]`, p3 below), mirroring the rust
backend's codegen-side reduction so `ssc check` agrees with the emitted type. They
still never participate in value-type *unification* (a bare unapplied lambda is not
unified). The deliverable is *parse + represent + round-trip + use-site reduction*,
and which backends accept the syntax (tracked by `bench/corpus/type-lambda-*.ssc`
and `lang/core/.../typer/TypeLambdaProgressTest.scala`).

## 2  Surface syntax — two equivalent forms

Decision (2026-06-12): support **both**, equivalent.

- **Scala-3 native:** `[X] =>> F[X]`, `[A, B] =>> Map[B, A]`.
- **Placeholder / wildcard short form:** `Map[Int, _]` — each `_` is a fresh
  lambda parameter, bound left→right in source order. `Map[Int, _]` ≡
  `[X] =>> Map[Int, X]`; `Either[_, _]` ≡ `[A, B] =>> Either[A, B]`. (`_` is free
  to repurpose — ScalaScript has no existential types.) A `F[_]` whose args are
  **all** `_` stays `HigherKinded` (a kind, not a lambda); a *mixed* application
  like `Map[Int, _]` is the placeholder lambda.

Canonical form is the native `=>>` (what `SType.show` emits); `_` is sugar-in.

## 3  Representation

`SType.TypeLambda(params: List[String], body: SType)` (Types.scala). Surface-only:
`show` = `[p1, p2] =>> body.show`; `containsAny` recurses into `body`; `subst` /
`containsFreeVar` fall through (params are bound, never substituted). No reduction.

## 4  Parsing

- **Source (`.ssc`):** scalameta's Scala3 dialect already parses `[X] =>> T` and
  `Map[Int, _]` natively. The only blocker was `Parser.preprocessListLiterals`,
  which rewrote a `[A]` after `= ` (operator + space) into `List(A)` — corrupting
  `type F = [A] =>> …`. Fixed by peeking past the matching `]` for `=>>` and
  treating such a bracket as a type-param clause (never a list literal). The
  placeholder form already passed through (its `[` follows a type name).
- **Artifact (`InterfaceScope.parseSType`):** a `[` at the *start* of a type can
  only begin a type lambda (HigherKinded shows name-first, tuples paren-first), so
  `parseType` dispatches to `parseTypeLambda` → `[params] =>> body` →
  `SType.TypeLambda`. Round-trips with `show`.

## 5  Backend handling

interp / jvm / js **erase** type lambdas (surface-only) — once the source parses,
the type annotation is dropped at runtime. **rust** uses real types, so it
**β-reduces** a type-lambda alias application instead: `RustCodeWalk` collects
`type Pair = [A] =>> body` aliases (`collectTypeLambdaAliases`) and, in `mapType`,
an application `Pair[Long]` substitutes the params in the body (`substType`) and
maps the result — `Pair[Long]` → `(Long, Long)` → `(i64, i64)`. Status
(`bench.sh`): native `[X] =>> F[X]` is **green on all five backends**
(ssc/ssc-asm/jvm/js/rust). The placeholder form is **also green on all five
backends** since p2b (see §5b).

## 5b  Placeholder status — green on all backends (2026-06-12)

`type-lambda-placeholder` (`Either[_, Int]` applied as `RightInt[String]`) is green
on **all five backends** (ssc / ssc-asm / jvm / js / rust).

The jvm blocker was resolved at the **parser/AST level** (the "cleaner alternative"
below), not via an `emitStat` arm: `Parser.desugarPlaceholderTypeAliases` rewrites a
`type` alias RHS containing `_` to the native `type RightInt = [A] =>> Either[A, Int]`
right after parsing, so **every consumer (interp/jvm/js/rust + artifacts) sees one
canonical native form**. `JvmGenTermAnalysis.blockContainsTypeLambda` then routes any
block declaring a `Type.Lambda` through the tree-emit (`emitStats`) instead of verbatim
`block.src`, so Scala 3 receives the lambda rather than a wildcard.

- **rust** ✓ — `collectTypeLambdaAliases` + `desugarPlaceholders` (codegen-side) reduce
  `RightInt[String]` → `Either<String, i64>`.
- **interp / js** ✓ — erase the annotation.
- **jvm** ✓ — sees the desugared native `=>>` and applies it like any type lambda.

The desugar covers **top-level aliases AND aliases nested at any depth inside
`object`/`trait`/`class` bodies** (`type-lambda-nested-aliases`, 2026-06-12 — it
recurses into `templ.body.stats`, reconstructing a template only when a member
actually changed so unrelated nodes keep their original positions). Why the desugar
is unconditional for an alias RHS: ScalaScript has no existentials, so an alias whose
RHS contains `_` is always a type-constructor (it gets applied). The interface-artifact
parser `parseSType` deliberately keeps a use-site `Map[Int, _]` a wildcard `Named`
(`ParseSTypeTest` pins this) — it has no use-site context to disambiguate a
partial-application lambda from a wildcard.

## 6  Follow-ups

- ~~**p2b — placeholder desugaring**~~ ✓ DONE 2026-06-12. Resolved at the
  parser/AST level (`Parser.desugarPlaceholderTypeAliases`): an alias RHS with `_`
  is unconditionally a type-constructor (no existentials in ScalaScript), so it is
  rewritten to native `=>>` at parse time — top-level and nested in
  `object`/`trait`/`class` bodies (`type-lambda-nested-aliases`). `parseSType` keeps
  a use-site `Map[Int, _]` a wildcard `Named` by design (no use-site context). Green
  on all five backends; see §5b.
- ~~**p2b — `.sscc` v3 artifact round-trip** for `TypeLambda`~~ ✓ DONE 2026-06-12.
  Native `[X] =>> F[X]` round-trips via the stored `=>>` token (`TypeLambdaArrow`,
  kind 55). The placeholder form did **not** — the `.sscc` v3 read (`ScalaNode.deferred`)
  raw-parses the reconstructed source and the write stores the *placeholder* token
  stream (`Map[Int, _]`) verbatim (the desugar is a tree rewrite, not a string
  preprocessor), so a cached placeholder alias reverted to a wildcard, diverging from
  the direct `Parser` parse. Fixed by applying the same desugar on the read path
  (`Parser.desugarTypeLambdaAliases`, called from `ScalaNode.deferred`). Regress:
  `TypeLambdaProgressTest` "survives a `.sscc` v3 artifact round-trip" (both surfaces)
  + two `SsccFormatV3Test` phase-B cases.
- ~~**p3 — semantics:** β-reduce `([X] =>> F[X])[A]` → `F[A]` in `ssc check`~~ ✓ DONE
  2026-06-12. The typer ignored type lambdas twice: `typeAnnotToSType` had no
  `Type.Lambda` case (a `[V] =>> …` rhs fell through to `SType.Any`), and `expandAlias`
  returned a no-own-params alias's rhs verbatim — so `IntKey[Long]` dropped its `[Long]`
  application. Fixed: parse `Type.Lambda` → `SType.TypeLambda`, and β-reduce a
  `TypeLambda` rhs against the use-site args in `expandAlias` (wrong arity → error), via
  new `SType.substNames` (name-keyed, shadowing-aware) + `SType.applyTo`. Matches the
  rust codegen reduction. Regress: `TypeLambdaProgressTest` (pure `applyTo` +
  `ssc check` integration, native + placeholder + arity error).
- ~~**p3b — HKT / kind-bound checking** in `ssc check`~~ ✓ DONE 2026-06-12. A kind
  registry `typeCtorKinds: Map[String, List[Int]]` (name → its params' kinds; `0` =
  proper type, `k>0` = higher-kinded `F[_…]`) is populated from built-ins, user
  class/trait/enum tparams, and `type` aliases (a type-lambda alias takes the lambda's
  param kinds). `checkTypeApplication` (called from `typeAnnotToSType`'s `Type.Apply`
  case) flags (1) ARITY — a known constructor applied to the wrong number of args
  (`List[Int, String]`, `Map[Int]`); (2) KIND-BOUND — an argument whose kind ≠ the
  filled parameter's declared kind (`Functor[Int]`/`Functor[Map]` where
  `trait Functor[F[_]]`; `Fix[Map]` where `type Fix = [F[_]] =>> F[Int]`; `Box[List]`
  where `Box[A]`). CONSERVATIVE: only known names are checked and only known-kind args
  are flagged, so a local HK type param (`Functor[F]` inside `Applicative[F[_]]`) and
  imported/unknown constructors are never falsely flagged (validated against the real
  `runtime/std/functor-applicative-monad.ssc` hierarchy). Arity errors moved out of
  `expandAlias` into the single checker. Regress: 7 `TypeLambdaProgressTest` cases
  (arity ±, HK-bound ±, conservative, proper-type-rejects-constructor, real HKT
  typeclass hierarchy). core 968 + backendInterpreter 1673 green.
- ~~**rust:** decide whether to erase or diagnose type lambdas.~~ ✓ DONE
  2026-06-12: `RustCodeWalk` β-reduces a type-lambda alias application in `mapType`
  (`collectTypeLambdaAliases` + `substType`). `type-lambda-native` is green on rust.
