# Scala 3 lexical direct-style control macros

Status: **M1 fourth-review strict-polymorphic-value remediation specified;
implementation pending** (2026-07-15). The first review rejected owner
splitting, lazy-marker lowering,
and inline marker wrappers. The next independent rereview found stale dependent
types in freshened declarations, a direct marker hidden in `ShiftBody`, an
incorrect transparent-inline primary position, and deferred
`scala.util.boundary.break`. The contract below now covers all four and the full
local verification matrix is green; M1 does not land until a new independent
review approves the frozen checkpoint. An adversarial pre-review then found one
more owner-safety gap: the `ShiftBody` survival scan exempted the eager prompt
argument of a nested managed reset together with that reset's managed body. The
contract below now distinguishes those two evaluation regions. The packaged-
consumer regression and full local verification matrix are green; M1 still does
not land until a new independent review approves the frozen checkpoint. Fresh
independent review of `708dec2f1` then found three remaining owner graphs outside
that evidence: captured result type `A`, nested/inferred types in moved terms, and
forward/mutual compiler-lazy givens. The contract below covers those graphs, and
feature commit `2ee8527e1` implements them with faithful source and packaged
regressions plus the full local gate. M1 remains unlanded until a new independent
review approves the frozen checkpoint. Fresh review of candidate
`f4e860ed7..408f23c11` then found an independent strict polymorphic function value
whose structural `apply` selection retained an invalid `<none>` member after
capture. The contract below now covers structural member resolution and complete
method/poly binder closure; the new behavior item remains unchecked until the
faithful packaged regression and full gate pass.

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
A declaration's type participates in the same rebinding as its term initializer:
singleton/path-dependent references to an earlier freshened local are rewritten to
that local's generated symbol before the new declaration is created. Thus the
common local prompt shape (`val inner = freshPrompt[R]`, followed by a value typed
as `Prompt[inner.Key, R]`) and ordinary `owner.type` aliases remain accepted across
capture. Rebinding must preserve an explicit source widening when possible and
must never leave an old owner in `ValDef.tpt.tpe`. If an uncommon dependent type
shape cannot be rebuilt soundly, M1 rejects its declaration with
`DIRECT_STYLE_UNSUPPORTED`; it never delegates the failure to a raw E007/quote
owner diagnostic. Ordinary mutable, contextual/given, and pattern-generated
bindings retain the same rules, flags, and shared-cell behavior.

The captured result type `A` is part of the same ownership graph. If `A` names a
freshened prefix value, the transform rebinds it before opening the type or typing
the generated explicit continuation. This includes ordinary singleton capture
(`owner.type`) and a local prompt value (`Prompt[inner.Key, R]`). The rank-2 shift
body, explicit shift instantiation, and generated bind continuation all use that
one rebound type. A type that cannot be rebuilt soundly fails at the source marker
with `DIRECT_STYLE_UNSUPPORTED`, never as generated E007/owner² output.

Ownership also includes every type attached to a moved term, not only the outer
declaration's written `tpt`. Nested lambda/result symbols, inferred type trees, and
declarations created in the captured suffix must refer only to the fresh owner
graph. The common `val f: () => owner.type = () => owner` shape is accepted both
before capture and when declared in the suffix. Any richer moved definition graph
that cannot be rebuilt is rejected before generated code is constructed.

The accepted strict-value grammar includes independent polymorphic function values,
for example `val identity: [A] => A => A = [A] => (a: A) => a`, and ordinary
monomorphic structural function applications. Such a value may cross capture and
be called in the prefix or captured suffix, using either `identity[Int](value)` or
the explicit `identity.apply[Int](value)` spelling. Moving the call must preserve a
callable member resolved from the moved qualifier's current type; an absent or
structural `<none>` source selection is not a symbol that may be copied into the
generated tree. Nested generic/polyfunction values whose result captures a local
owner follow the same owner-rebinding rules. If Quotes cannot close a richer
structural or dependent binder graph soundly, M1 rejects the source declaration or
call with stable `DIRECT_STYLE_UNSUPPORTED`, never raw typer output.

A captured local `var` remains one Scala closure cell shared by every reusable
resume; the transform must not copy its current value into each continuation.
Scala may represent a parameterless source `given` with both `Given` and `Lazy`
flags; that compiler encoding remains in the accepted contextual-value case and
is cloned with both flags intact. Supported crossing contextual values may refer
forward or mutually to other parameterless givens in the same prefix: all fresh
symbols are allocated before their RHS trees move, so a still-lazy, unused cycle
remains lazy and observationally matches ordinary Scala. A dependent type cycle
that cannot be allocated soundly is rejected at its declaration. This does not
admit an ordinary source `lazy val`, which remains the explicit fail-closed case
below.

The shift body's rank-2 lambda is an opaque argument of the marker. Its own lambda
syntax is not classified as a crossed callback frame. Opaque here means that its
ordinary explicit `Eff`/`scalascript.control.shift` program is not transformed or
rejected. It does not exempt the body from the marker-survival invariant: an exact
`direct.shift` nested inside another marker's `ShiftBody` is outside M1 and is
rejected at that inner call. A separately managed nested `direct.reset` owns and
lowers markers in its contextual body, so that managed body is not rejected by the
outer-region scan. The exception starts only at the nested delimiter: its prompt
expression is an eager argument evaluated before the nested reset is entered and
therefore remains part of the enclosing `ShiftBody` audit. An exact direct marker
in that prompt expression is rejected at the marker call. If a future reset shape
has additional eager arguments, they follow the same rule; only the proven managed
body/inline expansion is delegated to the nested transform.

M1 rejects a direct marker nested inside any of these boundaries:

- a lambda, SAM conversion, anonymous class, local method, lazy initializer, or
  by-name argument;
- `try`, `catch`, `finally`, `synchronized`, monitor/lock, transaction, resource,
  or finalizer scope;
- `while`, `do`, `for`, non-local `return`, or another unsupported control tree;
- any `scala.util.boundary.break`, even when a source `boundary` appears lexically
  nearby; M1 does not prove that library-level boundary control remains dynamically
  enclosed after `Eff.defer` or CPS movement;
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
wrapper around `direct.shift` is outside M1. If Scala leaves the wrapper as an
unexpanded inline application in the typed reset body, the reset transform rejects
that application directly; if an expansion boundary is present, it rejects the
boundary. Both paths use `DIRECT_STYLE_UNSUPPORTED` before moving the tree.

A marker targeting an outer `Scope` from inside a nested `direct.reset` is rejected
as `UNMANAGED_CAPTURE` in M1. It is not silently narrowed through the nested reset's
declared `Fx`: doing so would make the generated explicit effect row unsound.

A non-local `return` is rejected anywhere in a `direct.reset` body, including a
pure body or a suffix after capture. Explicit `reset` defers its by-name body under
`Eff.defer`; by the time that computation runs, the Scala method targeted by the
source `return` has already completed. Moving such a return would therefore create
exception-based escaped control rather than direct-style effect semantics.

`scala.util.boundary.break` is conservatively outside M1 in every position. Unlike
a `Return` tree, it is library/compiler control whose target is not represented by
the direct transform's lexical owner test. The macro identifies the exact break
symbol or its inline call provenance and rejects the invocation before lowering.
Resolution is by exact symbol, independent of source spelling: a directly selected
call, imported method alias, module alias, explicit label application, and
transparent-inline provenance are all the same forbidden operation and retain the
nearest truthful invocation position.
This rule intentionally rejects even a shape that a future transform might prove
safe; it prevents a source delimiter from returning before delayed code invokes
the break. A return wholly local to a nested method remains accepted under the
separate owner rule.

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

At every capture split, the implementation first selects every preceding supported
strict `ValDef` that crosses the split and allocates its fresh symbol under the
current generated owner. Type allocation proceeds in source order through already
available type dependencies; an unsupported forward dependent-type cycle fails
closed. Only after the complete old-symbol to fresh-ref term map exists are RHS
trees moved in source order. This two-phase scheme preserves ordinary backward,
forward, and mutual term references, including compiler-lazy givens, without
changing strict source evaluation. The map is carried recursively through
sequential markers. Mutable flags are preserved so normal Scala closure conversion
owns the shared cell; given and synthetic/pattern flags are preserved so implicit
selection and destructuring dependencies keep their source meaning. A definition
kind that M1 cannot freshen is rejected before constructing the continuation,
never left for a raw quote-owner error.

Cloning includes type ownership. Before each `Symbol.newVal`, the transform rebinds
every supported dependent/singleton path in the declared type to the fresh term
references already allocated for its type dependencies. After the full symbol map
exists, it moves the initializer and rebinds/audits all nested owner-bearing types
in that tree. The captured `A`, prompt, shift body, suffix, and every nested moved
definition receive the same stale-symbol audit. If the source type has no captured
path, its exact ascription is retained; if it has a supported captured path, the
ascription is rebuilt. An unsupported path-dependent mutable/contextual/pattern or
nested term shape fails at its source declaration/marker with the stable unsupported
diagnostic rather than being narrowed silently or delegated to the Scala compiler.

Selection ownership is rebuilt even when no captured term owner appears in the
outer type. After replacing a qualifier, the transform resolves a structural
selection through that qualifier's current type/member graph instead of blindly
copying an absent original `Select.symbol`; `PolyFunction.apply` therefore cannot
emit an undefined `<none>` member. A self-contained `MethodType`/`PolyType` binder
graph may be preserved as one closed unit. When owner/type substitution does rebuild
such a graph, every `ParamRef` must point to the corresponding rebuilt binder,
including refs that occur only in result types or type bounds. Nested method/poly
binder graphs must be closed before code construction and are included in the
stale-symbol audit. Failure to resolve a callable member or close a binder graph is
diagnosed at the source declaration/call with `DIRECT_STYLE_UNSUPPORTED`.

Marker normalization may remove only ownership-neutral typing wrappers; it never
removes an `Inlined` node. Even `Inlined(None, Nil, ...)` can retain references to
owner-sensitive synthetic parameters after earlier compiler phases have erased
its visible call/binding metadata. Any inline expansion is therefore not a direct
M1 marker shape. Likewise, an unexpanded application whose callee symbol is
`Inline` is not safe to move under `Eff.defer`: the transform cannot inspect a
separately compiled expansion and prove it contains no marker or stale synthetic
owner. The expansion/application is diagnosed before prompt/body movement, so
wrapper arguments are neither dropped, duplicated, nor evaluated by the failed
expansion.

For an inline expansion, the diagnostic retains a stack of non-empty
`Inlined.call` provenance and uses the nearest wrapper invocation as the primary
position. It falls back to the marker only when the compiler supplied no call
position. The separately compiled/unexpanded-inline application path continues to
report the application itself.

Before an accepted marker body is moved, a narrow survival scan enters its rank-2
`ShiftBody`. It ignores ordinary explicit control calls, but rejects the exact
`direct.shift` symbol. On an exact nested managed `direct.reset`, the scan parses
the known curried call shape, recursively audits the eager prompt argument, and
delegates only the contextual body/inline expansion to that reset's own transform.
It never skips the whole reset call. If the call shape cannot be separated into
eager arguments and managed body, M1 fails closed with
`DIRECT_STYLE_UNSUPPORTED` rather than guessing which subtree is protected. This
scan is independent from callback-barrier classification: the rank-2 body stays
legal, while an unlowered direct marker can never reach emitted code.

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
the exact unsupported source construct: normally the `direct.shift` call, the
nearest wrapper invocation for a transparent-inline expansion, or the declaration /
`boundary.break` call named by the diagnostic. These position forms are frozen by
tests; the macro does not forge a less truthful source span.

If several markers fail, diagnostics are reported in deterministic source order.
The first structural barrier from a marker outward is the one named.

The review-remediation diagnostics freeze these families:

- a marker in a lazy initializer uses `CAPTURE_BARRIER` and names the
  `lazy-initializer boundary`;
- a strict capture preceded by a local method, class, type, or lazy declaration
  uses `DIRECT_STYLE_UNSUPPORTED` at that declaration and tells the user to move
  it outside `direct.reset` (or make a lazy value strict);
- an inline marker wrapper or other unexpanded inline application uses
  `DIRECT_STYLE_UNSUPPORTED` at the wrapper/application and tells the user to
  write `direct.shift` directly at block level or move the pure inline call outside
  the reset.
- an exact `direct.shift` nested in another marker's `ShiftBody` uses
  `DIRECT_STYLE_UNSUPPORTED` at the inner call and tells the user to use the
  explicit `scalascript.control.shift` protocol or a separately managed nested
  `direct.reset`; this includes a marker in the eager prompt argument of that
  nested reset, while markers in the nested reset's managed body remain owned by
  its own transform;
- an unsupported dependent prefix type uses `DIRECT_STYLE_UNSUPPORTED` at its
  declaration and tells the user to move the declaration outside `direct.reset`;
- `scala.util.boundary.break` uses `DIRECT_STYLE_UNSUPPORTED` at the break
  invocation and tells the user to move boundary control outside `direct.reset`;
  imported aliases, explicit-label calls, module aliases, and transparent-inline
  provenance preserve the same code and nearest truthful source position.

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
- [x] Strict local `val`/`var`/`given` and pattern bindings remain owner-correct
      across capture, including shift-body use and sequential markers; reusable
      resumes share one local mutable cell.
- [x] Lazy marker initializers, crossing local method/class/type/lazy
      declarations, and inline marker wrappers fail closed
      with the frozen diagnostic family and never execute rejected side effects.
- [x] Top-level declared types of freshened strict locals are rebound without old
      owner references for the covered local nested-prompt, `owner.type`, mutable,
      given, and pattern declaration cases.
- [x] A captured result `A` that names a freshened `owner.type` or
      `Prompt[inner.Key, R]` is rebound consistently through the explicit shift and
      rank-2 continuation; unsupported types fail at the marker without raw E007.
- [x] Owner-bearing types on nested moved RHS symbols and suffix declarations are
      rebound/audited, including `() => owner.type`; no old owner reaches emitted
      code and any unsupported graph fails closed at its source construct.
- [x] Supported compiler-lazy parameterless givens are allocated before any RHS is
      moved, so unused forward/mutual term references compile and retain ordinary
      Scala laziness while unsupported dependent type cycles fail closed.
- [x] An exact direct marker inside an outer `ShiftBody` fails at the inner call;
      ordinary explicit control and nested managed `direct.reset` remain accepted.
- [x] A nested managed reset exempts only its contextual body from the enclosing
      `ShiftBody` survival scan: an exact outer direct marker in its eager prompt
      argument fails with the stable source-located diagnostic, while the ordinary
      nested managed body and explicit `scalascript.control.shift` remain accepted.
- [x] Transparent-inline rejection reports the nearest wrapper invocation, while
      the unexpanded-inline application diagnostic remains stable.
- [x] Every `scala.util.boundary.break` inside M1 fails before defer/CPS movement
      with a stable source-located direct diagnostic through direct selection,
      imported alias, explicit label, module alias, and transparent-inline
      provenance; safe nested-method returns remain accepted.
- [ ] Independent strict polymorphic function values and monomorphic structural
      applications cross capture without undefined selections; prefix/suffix and
      explicit `.apply` calls retain closed method/poly binders, while genuinely
      unsupported structural or owner-dependent graphs fail with stable
      `DIRECT_STYLE_UNSUPPORTED` instead of raw typer output.

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
- **Types are ownership-bearing.** Chosen because a cloned term whose type still
  names an old local is not a sound clone. This includes captured `A`, nested
  definition symbol infos, and suffix declarations, not just an outer `ValDef.tpt`.
  Rejected: term-only/top-level-type substitution and relying on the Scala compiler
  to surface a raw generated-tree E007.
- **Two-phase crossing-value allocation.** Chosen so supported lazy contextual
  values may retain ordinary forward/mutual term references while all RHS trees
  move against a complete replacement map. Rejected: sequential move-then-allocate,
  which leaks an old owner for forward references, and eager evaluation of givens.
- **Resolve moved selections and preserve closed binders atomically.** Chosen
  because a structural selection may have no reusable source symbol, while a
  self-contained `PolyType` and all of its `ParamRef`s can remain one closed unit.
  Owner-dependent graphs are rebuilt binder-for-binder. Rejected: rebuilding
  `Select` with the original `<none>` symbol, or partially rebuilding a
  MethodType/PolyType while leaving result/bound ParamRefs on an old binder.
- **ShiftBody is opaque but marker-free.** Chosen to preserve arbitrary explicit
  effect code while enforcing that every direct marker is consumed by its owning
  transform. A nested managed reset protects only its contextual body, because its
  prompt is evaluated before entering that delimiter. Rejected: skipping either
  the whole `ShiftBody` or the whole nested-reset call and allowing a compile-time
  marker stub to escape through an eager argument.
- **All boundary breaks fail closed in M1.** Chosen because inline/library boundary
  targets are not represented in the bounded transform's owner model. Rejected:
  assuming lexical source nesting survives `Eff.defer` and generated continuations.
- **Direct marker shape only.** Chosen because inline expansion bindings and call
  provenance participate in evaluation and ownership. Rejected: stripping every
  `Inlined` wrapper and hoping the exposed marker remains well-owned.
- **Fail closed.** Chosen because an untransformed direct marker is a semantic
  boundary violation. Rejected: runtime stubs, exceptions, or best-effort fallback.

## Results

- `scripts/sbtc "scala3ControlApi/test;scala3ControlApi/packageBin;scala3ControlApi/makePom"`
  passes 109/109 tests across ten suites. The direct slice contributes twenty-one
  runtime semantic tests, twenty-six exact compile-time diagnostic tests, three
  catalog-lane tests, and source-access safety checks.
- `tests/interop-conformance/run.sh --validate` accepts 26 vectors and nine lanes;
  all nine validator-negative cases pass. `--lane scala-direct` passes vector 18,
  vector 23, and catalog/program coverage (3/3), each with an explicit-API
  differential result.
- The generated POM has only `scala3-library_3` in production scope (ScalaTest is
  test scope). The runnable example prints `Vector(10, 20)`, `42`, and direct-style
  `42` both through the sbt test classpath and when Scala CLI compiles it against
  only the packaged control JAR plus the Scala library.
- `tests/conformance/run.sh --only 'effect*,effects*'` passes all five affected
  cases on every declared lane, and `git diff --check` is clean.
- Independent review of frozen checkpoint `fa992fd92` rejected three P1 families:
  prefix declaration symbols split from their uses, lazy marker binds made eager,
  and inline-wrapper bindings/provenance erased. The pre-fix suite remained 82/82,
  proving those original tests did not exercise the rejected shapes. Four new
  semantic and six new exact-diagnostic regressions now close those families, and
  both focused suites pass after a clean test compilation.
- A fresh independent rereview of frozen pre-rebase checkpoint `ec4eb279e` rejected
  four additional P1 families: stale dependent/singleton type owners, a direct
  marker hidden in an outer `ShiftBody`, transparent-inline diagnostics anchored
  at `direct.reset`, and `scala.util.boundary.break` escaping after defer. The
  seven new regressions now cover dependent prompt/`owner.type` flow (including
  mutable/given/pattern references), a stable fail-closed dependent polymorphic
  shape, explicit and nested-managed control inside `ShiftBody`, exact inner-marker
  rejection, nearest transparent-inline position, and pure/suffix boundary breaks.
  Clean focused tests pass 37/37; the full leaf is 99/99, package/POM and packaged-
  JAR example are green, catalog validation is 26 vectors/9 lanes with 9/9 negative
  cases, the direct lane is 3/3, and affected conformance is 5/5. Another
  independent rereview remains the landing gate.
- Adversarial pre-review of the post-remediation feature checkpoint `9c6850904`
  found that the nested-reset exception skipped its eager prompt together with the
  managed body. Before the fix, a Scala CLI 3.8.3 consumer compiled against only
  the packaged JAR reproduced raw owner output (`contextual$2 was used outside the
  scope where it was defined`) at the nested call's closing delimiter. The narrow
  call-shape audit now rejects the exact marker at its own position while preserving
  the nested managed-body and explicit-shift positives. Clean focused tests pass
  39/39; the full leaf is 101/101, package/POM and both packaged consumers are
  green, catalog validation is 26 vectors/9 lanes with 9/9 negative cases, the
  direct lane is 3/3, and affected conformance is 5/5. Fresh independent rereview
  remains the landing gate.
- Fresh independent review of frozen checkpoint `708dec2f1` rejected three further
  P1 owner families. A packaged Scala CLI 3.8.3 singleton-captured-`A` consumer
  reports E007 `owner` versus `owner²`; a moved `() => owner.type` RHS reports the
  same split; and unused forward/mutual parameterless givens fail with raw macro
  output that `second` escaped its scope. Their explicit equivalents compile and
  print `42`. The review also required committed exact regressions for four
  symbol-preserving `boundary.break` spellings/provenance forms and correction of
  the over-broad dependent-owner completion wording. These are pre-fix baselines;
  another independent review remains mandatory after implementation and full gates.
- Post-`708dec2f1` owner remediation is implemented in feature commit `2ee8527e1`.
  Captured `A` is rebound before type opening; moved definition types and common
  prefix/suffix owner-dependent lambdas are rebuilt and stale-symbol audited; and
  supported crossing givens are allocated in two phases with `Given`/`Lazy` flags
  preserved. Clean focused suites pass 47/47 (21 semantics and 26 diagnostics),
  and the full leaf/package/POM pass 109/109. A Scala CLI 3.8.3 consumer compiled
  only against the packaged JAR prints eight differential `42` results; the exact
  nested-reset-prompt negative reports stable `DIRECT_STYLE_UNSUPPORTED` at the
  inner marker. The POM retains only `scala3-library_3` in production scope,
  catalog validation is 26 vectors/9 lanes with 9/9 validator negatives, the
  direct lane is 3/3, and affected conformance is 5/5. Fresh independent review
  remains the landing gate.
- Fresh independent review of candidate `f4e860ed7..408f23c11` rejected the owner
  remediation on one further P1. A Scala CLI 3.8.3 consumer compiled only against
  the packaged JAR moves
  `val identity: [A] => A => A = [A] => (a: A) => a` across capture and calls
  `identity[Int](2)` in the suffix. Direct source fails twice with raw typer output
  `undefined: identity.<none> ... TermRef(... val <none>)`; the explicit equivalent
  compiles and prints `42`. This is the pre-fix baseline for the unchecked
  structural-selection/binder-closure behavior above.
