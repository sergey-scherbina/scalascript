# Scala 3 lexical direct-style control macros

Status: **M1 implemented; independent-review remediation in progress**
(2026-07-15). The first review rejected owner splitting, lazy-marker lowering,
and provenance-bearing inline wrappers. The fail-closed and symbol-rebinding
contract below is normative before M1 may land.

This feature is the bounded inline-macro tier of
[`scala3-bidirectional-control.md`](scala3-bidirectional-control.md). It translates
ordinary Scala 3 syntax inside a lexically visible `direct.reset` region into the
compiler-independent explicit API in `scalascript-control_3`. It does not define a
second effect or continuation semantics: the generated program uses only the
existing public `Eff`, `reset`, `shift`, `flatMap`, and `pure` operations.

The target-neutral laws remain owned by
[`control-interoperability.md`](control-interoperability.md). This feature is a
Scala-host source transform and never changes ScalaScript syntax, UniML, CoreIR,
the canonical codec, a backend, the seed, or the self-hosted compiler image.

## Scope

M1 provides local direct-style `shift` inside a lexical `reset` region in ordinary
Scala 3. It is independently useful and testable before the ScalaScript X1 gate,
but it does not claim a ScalaScript call bridge, generated facade, cross-method
compiler-plugin transform, managed foreign callback, durable save plan, or mixed
Scala/ScalaScript tail graph.

The capability evidence is deliberately narrow:

```text
ScalaInlineDirectStyle.M1
  = lexical same-source reset/shift regions
  + immutable/sequential ANF binds
  + exact explicit-API differential semantics
  + fail-closed callback/resource/control barriers
```

The broader `ScalaInlineDirectStyle` profile capability is not inferred for code
outside the accepted M1 grammar.

## Module, artifact, and package

M1 stays in the existing compiler-independent leaf:

```text
repository home: v2/host/scala/control
sbt project id:  scala3ControlApi
Maven artifact:  io.scalascript:scalascript-control_3
public package:  scalascript.control
public object:   scalascript.control.direct
```

There is no `scalascript-control-macros_3` coordinate. Keeping the inline surface
beside the explicit ABI avoids a second version axis and preserves the canonical
artifact name already chosen for Scala users. The production artifact gains no
dependency beyond the Scala libraries. `scala.quoted` is used only by the private
compile-time implementation and never appears in an exported `direct` source
signature. Scala 3 necessarily emits the compiler-invoked macro implementation and
an inline accessor in classfiles; those private-source/synthetic members are not a
supported user ABI and may change without compatibility guarantees.

This is normal Scala 3 binary cross-versioning (`_3`), not `CrossVersion.full`.
Full compiler-version coupling remains reserved for the later compiler-plugin
artifact.

## Public API

The M1 source ABI is:

```scala
object direct:
  @scala.annotation.implicitNotFound(
    "error [UNMANAGED_CAPTURE]: direct.shift must be lexically enclosed " +
    "by direct.reset for the same prompt and effect row"
  )
  sealed abstract class Scope[P, Fx <: Effect, R] private[control] ()

  transparent inline def reset[P, Fx <: Effect, R](prompt: Prompt[P, R])(
    inline body: Scope[P, Fx, R] ?=> R
  ): Eff[Fx, R]

  @scala.annotation.compileTimeOnly(
    "error [UNMANAGED_CAPTURE]: direct.shift escaped its enclosing " +
    "direct.reset transform"
  )
  def shift[P, A, Fx <: Effect, R](prompt: Prompt[P, R])(
    body: ShiftBody[P, A, Fx, R]
  )(using Scope[P, Fx, R]): A
```

Canonical use keeps the direct and explicit names visibly distinct:

```scala
import scalascript.control.*

val scoped = freshPrompt[Int]
val prompt = scoped.prompt

val computation: Eff[Nothing, Int] =
  direct.reset[scoped.Key, Nothing, Int](prompt) {
    val selected = direct.shift[scoped.Key, Int, Nothing, Int](prompt)(
      [Residual >: Nothing <: Effect] =>
        (k: Continuation[Int, Residual, Int]) =>
          k.resume(10).flatMap(a => k.resume(20).map(b => a + b))
    )
    selected + 1
  }
```

The explicit type arguments above are the normative spelling when inference is
insufficient; ordinary Scala inference may omit them. `ShiftBody` remains the
existing rank-2 residual-row type. M1 does not introduce a monomorphic convenience
overload because that could hide effects appended after capture.

`Scope` is a lexical typing token, not a runtime continuation, handler, prompt, or
capability registry. It has no public constructor or user implementation. A nested
`direct.reset` supplies a distinct scope; a shift selects the scope whose
`P`, `Fx`, and `R` match its prompt and row. Thus an inner region cannot consume an
outer marker accidentally. M1 does not lower a marker across a nested
`direct.reset`: the nested region is an effect-row boundary, and forwarding an outer
prompt through it needs the later compiler-plugin transform to make that residual
row explicit.

The `implicitNotFound` diagnostic covers a call outside a matching region. The
`compileTimeOnly` diagnostic is a second fail-closed guard: if a supported compiler
ever leaves the marker after expansion, compilation fails instead of executing a
stub.

## M1 accepted grammar

The reset body is typed before transformation and is then normalized to a bounded
ANF/CPS sequence. M1 accepts:

- pure prefix and suffix statements with Scala's ordinary left-to-right strict
  evaluation;
- strict immutable, mutable, contextual/given, and pattern-generated local value
  declarations whose initializer contains no marker; when such a value remains
  live across capture, lowering freshens its symbol in declaration order and
  rebinds every shift-body and suffix reference to that one generated binding;
- `val name = direct.shift(...)(...)` at block level;
- a tail-position `direct.shift(...)(...)` when its result type is the reset answer
  type;
- multiple sequential block-level shift binds;
- pure `if`, `match`, method application, construction, selection, and arithmetic
  subtrees that contain no direct marker;
- nested `direct.reset` regions, each owning only markers for its own matching
  `Scope`;
- mutation of ordinary captured local/heap state, with the existing local
  multi-shot rule: control is copied, the current local heap is shared.

The strict-value rule includes dependencies between synthetic `ValDef`s emitted
for a destructuring pattern and values declared between two sequential markers.
A captured local `var` remains one Scala closure cell shared by every reusable
resume; the transform must not copy its current value into each continuation.
Scala may represent a parameterless source `given` with both `Given` and `Lazy`
flags; that compiler encoding remains in the accepted contextual-value case and
is cloned with both flags intact. It does not admit an ordinary source `lazy val`,
which remains the explicit fail-closed case below.

The shift body's rank-2 lambda is an opaque argument of the marker. Its own lambda
syntax is not classified as a crossed callback frame.

M1 rejects a direct marker nested inside any of these boundaries:

- a lambda, SAM conversion, anonymous class, local method, lazy initializer, or
  by-name argument;
- `try`, `catch`, `finally`, `synchronized`, monitor/lock, transaction, resource,
  or finalizer scope;
- `while`, `do`, `for`, non-local `return`, or another unsupported control tree;
- an unknown inline expansion or application shape for which the transform cannot
  prove evaluation order and ownership;
- a local method, class, type alias/declaration, or lazy value declared before a
  later marker when that declaration would cross the capture split; M1 does not
  clone those definition graphs or lazy-cell machinery;
- a marker whose contextual `Scope` belongs to no enclosing region handled by the
  current expansion.

In particular, `lazy val selected = direct.shift(...)` is never an accepted bind:
the marker is inside a lazy initializer and receives `CAPTURE_BARRIER` without
forcing that initializer. A pure lazy value before a later shift is also rejected
fail-closed rather than being made strict or copied across capture. An inline
wrapper that expands to `direct.shift` with call provenance or local inline
bindings is outside M1 and receives `DIRECT_STYLE_UNSUPPORTED`; the transform may
not erase those bindings and then attempt to lower the exposed marker.

A marker targeting an outer `Scope` from inside a nested `direct.reset` is rejected
as `UNMANAGED_CAPTURE` in M1. It is not silently narrowed through the nested reset's
declared `Fx`: doing so would make the generated explicit effect row unsound.

A non-local `return` is rejected anywhere in a `direct.reset` body, including a
pure body or a suffix after capture. Explicit `reset` defers its by-name body under
`Eff.defer`; by the time that computation runs, the Scala method targeted by the
source `return` has already completed. Moving such a return would therefore create
exception-based escaped control rather than direct-style effect semantics.

These are fail-closed M1 limits, not claims that every shape is semantically
impossible. The later compiler plugin may represent additional frames explicitly.
Grammar expansion is additive and must gain differential and diagnostic tests
before it is advertised.

## Lowering

For one accepted bind:

```scala
direct.reset(prompt) {
  prefix()
  val a = direct.shift(prompt)(body)
  suffix(a)
}
```

the observable lowering is:

```scala
scalascript.control.reset(prompt) {
  prefix()
  scalascript.control.shift(prompt)(body).flatMap { a =>
    Eff.pure(suffix(a))
  }
}
```

Multiple binds nest `flatMap` in source order. A tail marker is passed directly to
the explicit reset body without an artificial resume. Pure statements before the
first marker execute once when the resulting `Eff` is interpreted, not when the
macro expands and not once per resume. Statements after capture execute once per
explicit resume, exactly like the equivalent explicit `flatMap` continuation.

The implementation must:

1. identify `direct.shift` by its exact method symbol and the contextual `Scope`
   argument owned by the current/nested region, never by source text;
2. preserve owner, source position, left-to-right evaluation, and single
   evaluation of every source expression;
3. emit only calls to the public explicit control API;
4. introduce no exception, `boundary`, thread-local, reflection, stack inspection,
   Promise/Future, or target-private continuation protocol;
5. reject any marker not proven to be removed from the returned tree.

At every capture split, the implementation clones each preceding strict
`ValDef` under the current generated owner and extends one old-symbol to fresh-ref
map before moving the next initializer, prompt, shift body, or suffix. This map is
carried recursively through sequential markers. Mutable flags are preserved so
normal Scala closure conversion owns the shared cell; given and synthetic/pattern
flags are preserved so implicit selection and destructuring dependencies keep
their source meaning. A definition kind that M1 cannot freshen is rejected before
constructing the continuation, never left for a raw quote-owner error.

Marker normalization may remove only ownership-neutral typing wrappers and an
`Inlined` node with neither call provenance nor bindings. A provenance-bearing or
binding-bearing inline expansion is not a direct M1 marker shape. Its exposed
`direct.shift` is diagnosed before prompt/body movement, so wrapper arguments are
neither dropped, duplicated, nor evaluated by the failed expansion.

The generated bind continuation is an ordinary reusable explicit continuation.
Zero, one, or many resumes and deep reset reinstallation therefore come from the
same implementation already verified by the explicit API.

## Diagnostics and positions

Compile-time failures use stable codes and primary positions:

| Code | Meaning | Primary position |
|---|---|---|
| `UNMANAGED_CAPTURE` | marker is outside a matching lexical scope or survived transformation | missing contextual-argument insertion site for a typer rejection; exact `direct.shift` call for a macro rejection |
| `CAPTURE_BARRIER` | first enclosing callback/resource/control boundary forbids M1 capture | exact `direct.shift` call; detail names barrier kind and its line/column |
| `DIRECT_STYLE_UNSUPPORTED` | tree is safe to reject but outside the accepted M1 ANF grammar | exact unsupported tree; for a rejected marker shape, the `direct.shift` call; detail gives an ANF rewrite hint |

Messages never contain an absolute source path. Fast macro-negative tests assert
the exact message, `lineContent`, and zero-based column from
`scala.compiletime.testing.typeCheckErrors`. A separate real-scalac fixture may
assert `<file>:<line>:<column>` when a physical line number is required; tests must
not invent a nonexistent `Error.line` member.

An out-of-region call is rejected by Scala's `implicitNotFound` path before any
enclosing macro can inspect it. Scala 3 therefore owns that primary position and,
for a multiline invocation, reports the missing contextual-argument insertion site
(normally the closing delimiter). Structural errors found by `direct.reset` point at
the `direct.shift` source line itself. Both position forms are frozen by tests; the
macro does not forge a less truthful source span.

If several markers fail, diagnostics are reported in deterministic source order.
The first structural barrier from a marker outward is the one named.

The review-remediation diagnostics freeze these families:

- a marker in a lazy initializer uses `CAPTURE_BARRIER` and names the
  `lazy-initializer boundary`;
- a strict capture preceded by a local method, class, type, or lazy declaration
  uses `DIRECT_STYLE_UNSUPPORTED` at that declaration and tells the user to move
  it outside `direct.reset` (or make a lazy value strict);
- a binding/provenance-bearing inline wrapper uses
  `DIRECT_STYLE_UNSUPPORTED` at the exposed marker/wrapper invocation and tells
  the user to write `direct.shift` directly at block level.

## Semantic-vector lane

The shared catalog gains this exact stable binding only after the implementation is
green:

```text
lane id:       scala-direct
adapter id:    scala3-control-macros-test
status:        ready
capabilities:  shift-reset
```

M1 differentially executes the applicable `shift-reset` catalog rows (currently
18 and 23) against the explicit Scala oracle. Vector 22 additionally requires
`prompt-isolation`: its outer prompt must forward through a nested different-prompt
reset, which is the residual-row case deliberately deferred from M1. Local tests additionally cover
sequential markers, shared heap, evaluation order, nested scope hygiene, and every
diagnostic. The lane does not claim generated Scala/ScalaScript interop, durable
save/run, callbacks, descriptors, runners, or cancellation.

## Behavior

- [x] `direct.reset`/`direct.shift` compile to the existing explicit API with no
      additional runtime semantics or production dependency.
- [x] Pure prefix code is evaluated once; suffix code is evaluated once per
      explicit resume; source evaluation remains left-to-right.
- [x] Zero, one, and many resumes, nearest same-prompt reset, true `shift` rather
      than `shift0`, residual-row typing, and shared local heap match explicit API
      results.
- [x] Multiple sequential shift binds and nested lexical scopes preserve typed
      ownership without marker capture by the wrong region.
- [x] Markers outside a matching scope or across callback/resource/control
      barriers fail closed with stable code and exact source position.
- [x] Unsupported M1 trees fail with `DIRECT_STYLE_UNSUPPORTED`, never a runtime
      stub, exception-based control path, or silent explicit-style fallback.
- [x] Exported `direct` source signatures expose no `scala.quoted`, CoreIR,
      interpreter value, reflection, TLS, or source-forgeable `Scope` constructor;
      the compiler-required macro implementation stays private at Scala source
      level and isolated from runtime semantics.
- [x] The `scala-direct` catalog lane and the full `scala3ControlApi` suite pass,
      the POM keeps only Scala production libraries, and affected common
      conformance remains green.
- [ ] Strict local `val`/`var`/`given` and pattern bindings remain owner-correct
      across capture, including shift-body use and sequential markers; reusable
      resumes share one local mutable cell.
- [ ] Lazy marker initializers, crossing local method/class/type/lazy
      declarations, and binding/provenance-bearing inline wrappers fail closed
      with the frozen diagnostic family and never execute rejected side effects.

## Verification

```text
scripts/sbtc "scala3ControlApi/test;scala3ControlApi/packageBin;scala3ControlApi/makePom"
tests/interop-conformance/run.sh --validate
tests/interop-conformance/validation-test.sh
tests/interop-conformance/run.sh --lane scala-direct
tests/conformance/run.sh --only 'effect*,effects*'
```

Changed Markdown is linted and the final branch must pass `git diff --check`.

## Out of scope

- ScalaScript frontend/lowering or any CoreIR/codec/seed/self-hosting byte change;
- cross-method control, arbitrary expression-position markers, transformed loops,
  callbacks, resources, `try/finally`, async, precompiled frames, or an outer marker
  crossing a nested `direct.reset`;
- generated Scala↔ScalaScript value/call bridges and descriptor consumers;
- compiler-plugin managed callbacks, saveable-frame metadata, or mixed tail SCCs;
- successful durable `save`, exact-artifact or portable runners;
- undelimited `callCC`, answer-type modification, hidden exception control, or JVM
  stack capture.
- cloning a local method/class/type definition graph or lazy-cell implementation
  across a capture split in M1; move those definitions outside the reset or use a
  supported strict value until the compiler-plugin tier represents them.

## Decisions

- **Same artifact and module.** Chosen to preserve the canonical
  `scalascript-control_3` coordinate and zero production dependency. Rejected: a
  new macros artifact with an unnecessary version/installation axis.
- **Lexical typed `Scope`.** Chosen to make ownership and nested regions explicit
  to the Scala typer and macro. Rejected: matching prompt source text or ambient
  thread-local/compiler state. An outer marker crossing a nested reset is deferred
  because its residual row must be represented explicitly; M1 rejects it rather
  than narrowing the row.
- **Rank-2 `ShiftBody`.** Chosen to preserve residual-row soundness. Rejected: a
  monomorphic convenience continuation that could hide widened effects.
- **Bounded ANF/CPS M1.** Chosen because it has a small auditable evaluation-order
  surface and deterministic barriers. Rejected: claiming a whole-program async/CPS
  compiler from one inline macro.
- **Fresh strict values, conservative rich definitions.** Chosen to support the
  ordinary `val`/`var`/`given` and pattern state that lexical users expect while
  preserving owner hygiene and multi-shot shared cells. Rejected: moving original
  symbols across generated lambdas, and cloning local methods/classes/types/lazy
  machinery without a complete ownership model.
- **Direct marker shape only.** Chosen because inline expansion bindings and call
  provenance participate in evaluation and ownership. Rejected: stripping every
  `Inlined` wrapper and hoping the exposed marker remains well-owned.
- **Fail closed.** Chosen because an untransformed direct marker is a semantic
  boundary violation. Rejected: runtime stubs, exceptions, or best-effort fallback.

## Results

- `scripts/sbtc "scala3ControlApi/test;scala3ControlApi/packageBin;scala3ControlApi/makePom"`
  passes 82/82 tests across ten suites. The direct slice contributes ten runtime
  semantic tests, ten exact compile-time diagnostic tests, three catalog-lane
  tests, and source-access safety checks.
- `tests/interop-conformance/run.sh --validate` accepts 26 vectors and nine lanes;
  all nine validator-negative cases pass. `--lane scala-direct` passes vector 18,
  vector 23, and catalog/program coverage (3/3), each with an explicit-API
  differential result.
- The generated POM has only `scala3-library_3` in production scope (ScalaTest is
  test scope). The runnable example prints `Vector(10, 20)`, `42`, and direct-style
  `42`.
- `tests/conformance/run.sh --only 'effect*,effects*'` passes all five affected
  cases on every declared lane, and `git diff --check` is clean.
- Independent review of frozen checkpoint `fa992fd92` rejected three P1 families:
  prefix declaration symbols split from their uses, lazy marker binds made eager,
  and inline-wrapper bindings/provenance erased. The pre-fix suite remained 82/82,
  proving those original tests did not exercise the rejected shapes; the two
  unchecked behavior items above are the required closure evidence.
