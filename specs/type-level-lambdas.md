# Type-level lambdas

Status: **p2 part 1 landed 2026-06-12** (native `[X] =>> F[X]` across interp/jvm/js).
Placeholder desugaring + rust + `.sscc` round-trip are follow-ups (p2b/p3).

## 1  Motivation & scope

ScalaScript is interpreter-first: **types are erased at runtime**. Type lambdas
are therefore *surface-only* — like `SType.HigherKinded` (`F[_]`) and
`SType.Match` (match types), they parse and round-trip through interface
artifacts but never participate in unification or runtime semantics. There is no
runtime cost to benchmark; the deliverable is *parse + represent + round-trip*,
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
(ssc/ssc-asm/jvm/js/rust). The placeholder form is green on ssc/js but `n/a` on
jvm/rust until p2b.

## 5b  Placeholder status + the jvm blocker (2026-06-12)

`type-lambda-placeholder` (`Either[_, Int]` applied as `RightInt[String]`) is green
on **ssc / ssc-asm / js / rust**, `n/a` on **jvm**.

- **rust** ✓ — `collectTypeLambdaAliases` + `desugarPlaceholders` turn an alias RHS
  with `_` into `[A] =>> body` and the existing reduction maps `RightInt[String]`
  → `Either<String, i64>`.
- **interp / js** ✓ — erase the annotation.
- **jvm** ✗ — JvmGen passes a `type` alias through verbatim, and **Scala 3 reads
  `type RightInt = Either[_, Int]` as a WILDCARD, not a lambda**, so `RightInt[String]`
  fails ("does not take type parameters"). The fix is to desugar the placeholder
  alias to the native `type RightInt = [A] =>> Either[A, Int]` (which Scala 3
  accepts — the native form is already green on jvm).
  **BLOCKER (recorded):** an `emitStat` arm that does this desugaring **never
  fires** — a top-level user `type` alias reaches the emitted Scala via a path
  **other than `emitStat`** (verified: a forced-rebuilt arm before the `.syntax`
  catch-all had no effect; the alias still printed verbatim). This is the SAME
  "top-level user declaration bypasses the obvious emitter" trap documented for the
  effect CPS work in `specs/effect-cps-loops.md`. FIRST STEP: map where JvmGen
  actually emits a top-level `Defn.Type` (it is not `emitStats`/`emitStat`). A
  cleaner alternative that fixes jvm AND unifies parseSType: desugar placeholder
  aliases to native `=>>` at the **parser/AST** level (post-parse `Defn.Type`
  rewrite), so every consumer sees the native form.

## 6  Follow-ups

- **p2b — placeholder desugaring (NEEDS CONTEXT — not a naive parser tweak):**
  `_` is **context-dependent**. In a *value-type* position `Map[Int, _]` is a
  wildcard (`Map[Int, ?]`) and must stay `Named("Map", [Int, _])` —
  `ParseSTypeTest` "mixed `_` … stays a Named app" pins this on purpose. It is a
  *type lambda* only in a *type-constructor* position — a `type` alias whose result
  is later applied (`type IntKey = Map[Int, _]; … IntKey[Long]`). So desugaring
  `F[a, _, b]` → `TypeLambda([X], F[a, X, b])` must be driven by the *use site*
  (alias-RHS-that-gets-applied), not by `parseSType` blindly. Approach: detect the
  alias-applied case in the typer/normalizer, or only desugar when the alias is
  used with type args. Until then `Map[Int, _]` runs via erasure on interp/js
  (green) and is `n/a` on jvm/rust. Flip the two `[target]` desugaring tests when
  done.
- **p2b — `.sscc` v3 artifact round-trip** for `TypeLambda`.
- **p3 (optional) — semantics:** β-reduce `([X] =>> F[X])[A]` → `F[A]` in
  `ssc check` + HKT bound checking. Only if a typed backend / strict check needs it.
- ~~**rust:** decide whether to erase or diagnose type lambdas.~~ ✓ DONE
  2026-06-12: `RustCodeWalk` β-reduces a type-lambda alias application in `mapType`
  (`collectTypeLambdaAliases` + `substType`). `type-lambda-native` is green on rust.
