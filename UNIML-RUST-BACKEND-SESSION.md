# uniML → Rust backend hardening — session summary

**Status:** COMMITTED, in worktree `fix-private-qualifier-bracket` (branch of the same name).
Three commits so far:

- `fef8df1cc` — parser: `private[X]`/`protected[X]` bracket fix
- `09fd7a3b8` — rust backend: local-def lambda-lifting, generic-type fixes, variant-narrowing
  destructure (prior session, 64 → 13 errors)
- `6bde8ac02` — rust backend: dyn-dispatch trait support + collection/closure clone fixes
  (this session, 13 → 2 errors)

**Goal:** get `scalascript.uniml`'s core module (`uniml/core/src/main/scala/scalascript/uniml/*.scala`,
9 files, plus the shared `alphabet/src/Alphabet.scala`) compiling through `ssc emit-rust` to a
real, `cargo build`-clean Rust crate — a prerequisite for using uniML (compiled to native Rust,
zero JVM at runtime) as the lossless-CST chunker for a planned project-source RAG feature in
`rozum`.

**Result:** on the merged module (all files concatenated into one synthetic `.scala` file — see
"Multi-file gap" below), `cargo build` errors went **64 → 13 → 2** across the two sessions. Not
yet zero, but both remaining errors are isolated, well-understood, and in code paths never
actually invoked by anything in this merged corpus.

**Verification standard used throughout:** `emit-rust --print-only` reporting zero `[error]`
lines is NOT sufficient — most of the real bugs found across both sessions were only caught by an
actual `cargo build` on the generated crate. Trust `cargo build`, not the diagnostic scanner, for
any claim of "this compiles." The rust backend's own Scala test suite (`sbt backendRust/test`,
278 tests) was also run after this session's changes — 277 pass, 1 fails, and that one failure is
pre-existing (verified against the base commit before this session's changes) and unrelated: a
stale assertion that `Set[Long]` is an unsupported type, which stopped being true independently
of this work.

## How to reproduce / continue

```bash
cd /Users/sergiy/work/my/scalascript/.worktrees/fix-private-qualifier-bracket
./install.sh --dev   # rebuild ssc-tools after any RustCodeWalk.scala edit

# Merge uniml/core + the shared alphabet module into one synthetic file (strip each file's own
# `package`/`import scalascript.uniml.*` lines, fix the one fully-qualified alphabet reference —
# the emit-rust CLI compiles each argument file into its OWN isolated crate with no cross-file
# symbol resolution, see "Multi-file gap" below, so testing uniml/core as a whole needs this).
# The script: /tmp/uniml-merge/build-merge.sh (ephemeral scratch dir, not in the repo — recreate
# it from this session's transcript if needed, or just concatenate the 10 files by hand).
/tmp/uniml-merge/build-merge.sh   # writes /tmp/uniml-merge/merged-noproc.scala

rm -rf /tmp/uniml-merge/out
./bin/ssc-tools emit-rust /tmp/uniml-merge/merged-noproc.scala -o /tmp/uniml-merge/out
cd /tmp/uniml-merge/out && cargo build 2>&1 | grep '^error' | sort | uniq -c | sort -rn
```

## What's fixed this session — dyn-dispatch trait support (new feature)

`trait DialectAdapter` and `trait Processor[S, I, O]` (`Dialect.scala`/`Processor.scala`) are
genuine Scala traits used for RUNTIME polymorphism — `DialectRegistry.byName: Map[String,
DialectAdapter]` holds different concrete adapters, resolved by name. This backend previously had
no concept of a Scala trait becoming a real Rust `trait` at all; every other trait usage in the
broader std/* corpus is either an intrinsics-only interface or a sealed hierarchy lowered to an
enum. Built:

- **Detection is deliberately narrow**: a non-sealed trait qualifies only if its name is used as
  an ordinary value/param/return type SOMEWHERE OUTSIDE a `given ... : Trait[...] with` typeclass
  template (`collectDynTraitNames` explicitly prunes `Defn.Given` subtrees from its scan). This
  keeps the existing typeclass machinery (`Monoid`, `Eq`, ... across std/*) untouched — those
  trait declarations still render to nothing, exactly as before, since they're referenced only
  inside a `given` template.
- The trait itself renders as a real `pub trait Name<Tparams> { ... }`, with an abstract member
  (a scalameta `Decl.Def`, no Scala body) as a semicolon-terminated signature and a defaulted one
  (`DialectAdapter.aliases: Set[String] = Set.empty`) with its body rendered inline.
- A concrete `class`/`object extends TraitName` gets a THIN FORWARDING `impl Trait for X` block —
  every method just calls the SAME-NAMED inherent method the ordinary struct/method pipeline
  already renders. This works without any duplicate-body machinery because Rust's method
  resolution always prefers an inherent method over a trait one, even from inside that trait's
  own `impl` block, so the forwarder can't recurse into itself. Only CLASS implementors get this
  (`LiteralProcessor`, `TreeVm`) — an OBJECT implementor (`object Literal extends DialectAdapter`)
  still renders as free qualified functions with no backing struct, so it can't be handed around
  as a `Rc<dyn DialectAdapter>` value yet; nothing in this corpus needs it to be.
- The type itself lowers to `std::rc::Rc<dyn Trait>` (with type args for a generic trait like
  `Processor`) EVERYWHERE it appears — param, field, local, return — not just in a field the way
  a closure's `Rc<dyn Fn>` is. `Rc`, not `Box`: the same reason as the closure case — a struct
  holding one still needs `#[derive(Clone)]`, and `Rc<dyn Trait>` is unconditionally `Clone`
  (bumps the refcount; no `T: Clone` bound needed) while `Box<dyn Trait>` is not `Clone` at all.
- A def whose declared return type is a dyn-trait type but whose body constructs a concrete value
  (`Literal.instructions`'s `LiteralProcessor(source.source)`) gets that body wrapped in
  `Rc::new(...)` — Scala's implicit upcast to the trait type, spelled explicitly.
- Widened the EXISTING closure-specific `Rc<dyn Fn...>` prefix checks (Debug/PartialEq
  exclusion, the Any-boundary Value-lift skip, and the field-construction-site `Rc::new` wrap) to
  match `Rc<dyn ...>` generally, in two different ways depending on what the check decides:
  - the derive-dropping checks (Debug/PartialEq/Value-lift) needed `.contains`, not `.startsWith`
    — `DialectRegistry.byName: Map[String, Rc<dyn DialectAdapter>>` has the problem type NESTED
    one level inside a `HashMap`, not at the front of the field's own type string.
  - the construction-site `Rc::new`-wrap check needed to STAY `.startsWith` — it wraps the WHOLE
    argument, and doing that for a `HashMap<..., Rc<dyn ...>>` field (which merely CONTAINS the
    substring) wrapped the entire map in an `Rc` it was never declared to have.
- Two new precise "is this a zero-arg trait/struct method call without parens" call-site fixes
  (see next section) needed to know a LOCAL's type, not just a def's own params — added
  `collectLocalRustTypes` (a narrow, non-inferring reader: a direct `val x = Ctor(...)` or
  `val x = recv.method(...)` where `method`'s declared return type is known) feeding the same
  `ctx.paramTypes` table a parameter already populates.

## What's fixed this session — everything else (mostly surfaced by real cargo builds against the
trait feature landing, i.e. these were masked by upstream errors before)

- **`Some(x.field)` bypassed clone-insertion entirely.** `cloneIfMoved` (the move-vs-clone
  decision every ordinary call argument gets) was never invoked for the term WRAPPED inside a
  `Some(...)` constructor call — only for a call's own direct arguments. Fixed both E0382
  partial-move errors in `TreeVm.scala` (`frame`, `token.span`) with one change.
- **A bare zero-arg def call without parens** (`vm.start`, `lexer.start` — Scala elides `()` on a
  parameterless method) reached the field-access fallback and rendered as one:
  `error[E0615]: attempted to take value of method 'start'`. Two guarded fixes, layered by
  precision: a name-only one (real zero-arg def name, AND never a struct field anywhere in the
  module — conservative in both directions) for cases with no ambiguity, and two more precise
  ones (receiver's exact dyn-trait or struct type, via `ctx.paramTypes`/`collectLocalRustTypes`)
  for the two names that collided with a real field elsewhere (`start` vs `SourceSpan.start`,
  `id` vs `SourceToken.id`).
- **`Either.flatMap`/`map`/`fold` didn't recognize a `foldLeft` accumulator as Either-typed** when
  nothing about its OWN syntax said so (`result` in `adapters.foldLeft[Either[...]](Right(empty))
  { (result, adapter) => result.flatMap(...) }`) — added `Ctx.eitherLocals`, populated from
  whether the fold's ZERO value is itself Either-shaped.
- **`inlineArm` (the Either-combinator arm renderer) only special-cased a NAMED one-arg lambda**,
  not the placeholder shorthand (`_.register(adapter)`) — which fell to a generic IIFE-wrapping
  fallback and reintroduced the exact nested-closure type-inference gap (`error[E0282]`) the
  named-lambda case exists to avoid. Added the same `{ let __p0 = v; body }` treatment for a
  one-placeholder `Term.AnonymousFunction`.
- **`Set[T] + elem`** (Scala's single-element add, spelled the same as numeric `+`) fell to plain
  arithmetic rendering. Lowered to the same borrowed-slice-concat idiom as `:+`, gated on a NEW
  `isKnownVecReceiver` case recognizing a zero-arg trait/struct method call by its `_returnTypes`
  entry (`adapter.aliases`, a parameterless call, not a field or local).
- **`Map ++ Map`** used the same borrowed-SLICE-concat idiom as `List ++ List` — which doesn't
  apply to a `HashMap` at all (you can't slice one: `error[E0308]: expected &_, found RangeFull`).
  Added a `HashMap::extend`-based case, gated on `isKnownMapReceiver`.
- **A method-reference closure captured its receiver by `move`** even when passed to a REPEATED
  iterator call (`find`/`map`/`filter`/...), which runs the closure once per element —
  `error[E0507]: cannot move out of ..., a captured variable in an FnMut closure` on the second
  call. The closure only ever needs to borrow its receiver (every use is a method call), so
  dropping `move` fixes every repeated-call site this shape reaches, not just the one that
  surfaced it.
- **A tuple literal's two operands (`a -> b`) never went through clone-insertion at all** — same
  fix as the `Some(...)` one above, one syntax shape over; needed for a closure-captured value
  read from inside a `.map()` closure that runs once per element.
- **`names.find(byName.contains)` (a method-reference passed to `find`) needed an explicit closure
  parameter type** the doubly-nested-closure shape this lowering already produces is past what
  rustc's own inference reaches (`error[E0282]`) — added a best-effort element-type lookup
  (`elementTypeOf`, extended with one more case for a `Set + elem`-shaped declaration) threaded
  through to an explicit `&T` annotation, with `.clone()` dropped in favor of passing the
  reference directly once it's typed (the untyped default path is unchanged).

## Multi-file gap (discovered, worked around, NOT fixed)

`ssc emit-rust` compiles each file argument into its OWN independent Cargo crate
(`EmitCommands.scala:133`, `for file <- files.toList do`) — there is no cross-file symbol
resolution at all. `userTypeNames`/`ctorMap`/every other per-module table is scoped per file. This
is why testing `uniml/core` as a whole requires manually concatenating all files (including the
separately-located `alphabet/src/Alphabet.scala`, an unmanaged shared source uniml/core's own
sbt build includes at compile time) into one synthetic `.scala` file first (see "How to
reproduce" above) — real multi-file/multi-module Cargo crate emission would be a substantial
separate feature, not attempted in either session.

## What's NOT fixed (2 remaining cargo errors, both isolated, both dead code in this corpus)

1. **`Diagnostic.copy(span = ...)`** — Scala's implicit case-class copy method isn't supported by
   this backend at all. The receiver here (`problem` in `TreeVm.scala`'s `step`) is a match-arm
   binder from `Option[Diagnostic]`, not a def parameter or a field — so even a narrow fix needs
   new match-arm type-tracking (binding a `case Some(x) =>` arm's `x` to the struct type read off
   the scrutinee call's known return type), which nothing in this backend currently does. A real,
   moderately-scoped feature (Rust's answer is straightforward — struct-update syntax,
   `Diagnostic { span: ..., ..problem.clone() }` — the missing part is knowing WHICH struct name
   to write) if built generally, but risks nothing else if left alone: only reached from
   `reframeProblem`'s `Some(problem)` branch, never exercised by anything else in this merge.
2. **`ProcessBatch`'s companion `def value[A](value: A): ProcessBatch[A]`** — the parameter `value:
   A` is deliberately erased to `crate::value::Value` by `renderParams`'s existing rule ("a
   parameter typed by the def's own type parameter is untyped at the call boundary"), for
   consistency with `_paramTypes` (the same table drives argument coercion at every call site to
   this def). That's right for a truly opaque type parameter, but wrong here: the RETURN type
   (`ProcessBatch[A]`) reuses the SAME `A`, so the struct's own field (`values: Vec<A>`) expects
   the real type, not `Value` — `error[E0308]: expected type parameter A, found Value`. Fixing
   this safely means threading "does the return type reuse this same tparam AND is the return
   type a real generic struct" into `renderParams`, which doesn't currently see `structTparams` at
   all — NOT attempted, because `renderParams`'s erasure rule is shared across the whole std/*
   corpus and a broad change to it risks a regression nothing in this session's testing would
   catch (uniml/core is the only corpus module exercising this exact shape). Also unreached by
   anything else in this merge (`ProcessBatch.value` is never called).

## Suggested order if picked up again

1. Item 2 (`ProcessBatch.value[A]`) first if a NARROW fix is wanted — e.g. skip the erasure only
   when `d.decltpe` is `Type.Apply(Type.Name(retName), args)`, `retName` names a struct actually
   in `structTparams` (would need passing that map down into `renderParams`, currently a
   `renderDef`-local `val`), and `args` contains the same tparam name. Verify against the WIDER
   std/* corpus's own test suite (`sbt backendRust/test`), not just uniml, before trusting it.
2. Item 1 (`.copy()`) is a real feature — start from `renderMatch`'s `case Some(x) =>` handling
   (a `Pat.Extract` over `"Some"`) and thread the scrutinee's element type (via `_returnTypes` on
   the scrutinee call, when it's a call) into the arm's `Ctx.paramCtorNames`, then add a
   `Term.Apply(Term.Select(qual, "copy"), namedArgs)` case in `renderTerm` using it to emit
   `StructName { ..namedArgs, ..qual.clone() }`.
3. Once `uniml/core` is fully `cargo build`-clean, the original "8 more files" scope
   (`uniml/markdown`, `uniml/json`, `uniml/xml`, `uniml/yaml`) is still open and untouched — and
   an OBJECT implementor of a dyn-dispatch trait (`object Literal extends DialectAdapter`) will
   very likely need real value-representation (not just free functions) once those dialect
   modules actually construct and register adapters — see this session's "object implementors"
   note above.
4. Then: the multi-file crate-emission gap (real feature, not the merge-script workaround), and
   finally wiring compiled uniML into rozum's planned RAG indexer — both untouched so far.
