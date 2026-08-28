# uniML → Rust backend hardening — session summary

**Status:** COMMITTED, in worktree `fix-private-qualifier-bracket` (branch of the same name).
`uniml/core` now compiles through a real `cargo build` with **ZERO errors**. Four commits:

- `fef8df1cc` — parser: `private[X]`/`protected[X]` bracket fix
- `09fd7a3b8` — rust backend: local-def lambda-lifting, generic-type fixes, variant-narrowing
  destructure (session 1, 64 → 13 errors)
- `6bde8ac02` — rust backend: dyn-dispatch trait support + collection/closure clone fixes
  (session 2, 13 → 2 errors)
- `afdfc01b2` — rust backend: last two uniml errors — `Diagnostic.copy()` and generic erasure
  (session 3, 2 → 0 errors)

**Goal:** get `scalascript.uniml`'s core module (`uniml/core/src/main/scala/scalascript/uniml/*.scala`,
9 files, plus the shared `alphabet/src/Alphabet.scala`) compiling through `ssc emit-rust` to a
real, `cargo build`-clean Rust crate — a prerequisite for using uniML (compiled to native Rust,
zero JVM at runtime) as the lossless-CST chunker for a planned project-source RAG feature in
`rozum`. **Done** for the `uniml/core` module itself — see "What's left" at the bottom for the
larger goal's remaining scope (the other 4 dialect modules, real multi-file crate emission, and
actually wiring compiled uniML into rozum).

**Result:** on the merged module (all files concatenated into one synthetic `.scala` file — see
"Multi-file gap" below), `cargo build` errors went **64 → 13 → 2 → 0** across three sessions.
Confirmed via `cargo build`'s own exit code after a `cargo clean` (not just absence of `error[`
lines in the output — see the "verification standard" below for why that distinction matters).

**Verification standard used throughout:** `emit-rust --print-only` reporting zero `[error]`
lines is NOT sufficient — most of the real bugs found across all three sessions were only caught
by an actual `cargo build` on the generated crate. Trust `cargo build`'s own exit code, not the
diagnostic scanner and not a piped command's exit code, for any claim of "this compiles." The
rust backend's own Scala test suite (`sbt backendRust/test`, 278 tests) was also run after every
session's changes — 277 pass, 1 fails, and that one failure is pre-existing (verified against the
base commit before session 2's changes) and unrelated: a stale assertion that `Set[Long]` is an
unsupported type, which stopped being true independently of this work.

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

## What's fixed in session 3 — the last two errors, plus one more they uncovered

- **`.copy(...)` (case-class functional update)** is now a real, general lowering — to Rust
  struct-update syntax, `StructName { field: value, ..recv.clone() }`. The general mechanism:
  `renderTerm` gets a `Term.Apply(Term.Select(recv, "copy"), namedArgs)` case that reads the
  struct name off `ctx.paramCtorNames.get(recv)` (refusing anything with a non-named or
  unknown-field argument, rather than guessing a positional correspondence). The missing part was
  getting a STRUCT name into `paramCtorNames` for `problem` (`TreeVm.scala`'s `step`) at all — it's
  a match-arm binder from `Option[Diagnostic]`, not a def parameter or a field, neither of which
  `paramCtorNames` was ever populated from before. `renderMatch`'s `case Some(x) =>` handling now
  reads the scrutinee call's OWN declared return type (`_returnTypes`, the same table `renderDef`
  already builds) when the scrutinee is a call, and threads the struct name into the arm's Ctx the
  same way a def parameter of a qualified variant type already does — so `.copy(...)` itself
  doesn't care which of the two ways a binder learned its type.
- **`ProcessBatch`'s companion `def value[A](value: A): ProcessBatch[A]`** — narrowed the
  "parameter typed by the def's own type parameter is erased to `Value`" rule (both in
  `renderParams` and its parallel copy in the `_paramTypes` builder, which have to agree) with one
  exception: skip the erasure when the RETURN type is `Type.Apply(StructName, args)`, `StructName`
  is a real struct already known to `ctorMap`, and `args` reuses the SAME parameter name. Scoped
  tightly enough that `ctxSignal[T](ctx: Ctx, name: String, default: T): T` — a bare, non-struct
  return — doesn't match and keeps erasing exactly as before; verified against the wider `sbt
  backendRust/test` suite (278 tests, not just uniml) precisely because this rule is shared
  corpus-wide.
- **Fixing `.copy()` made `reframeProblem`'s calling arm reachable for the first time**, and it
  had its own real bug: `case instruction @ VmInstruction.Reframe(closeBefore, open, closeAfter,
  role) =>` (`TreeVm.scala`'s `step`) is a `Pat.Bind` over a POSITIONAL extractor — a different AST
  shape from the WITH-FIELDS `Pat.Typed` case fixed in session 2, but the identical underlying bug:
  rendered BY VALUE, so destructuring `role` (an `Option<String>`, not `Copy`) out of `instruction`
  left `instruction` itself only partially valid for its own later use — `error[E0382]`. Same fix:
  `ref` on the whole binding and every field. One real difference from the `Pat.Typed` twin: there,
  every destructured field is read only through a method call (autoderefs a reference for free);
  here `role` is handed directly to a constructor argument (`UniEdge(role, tokenNode)`), which does
  NOT autoderef — so `ctx.byRefMut` needed every destructured name here, not just the outer binder.
  Also found and fixed along the way: the qualified spelling (`VmInstruction.Reframe(...)`, a
  `Term.Select` callee) needed its own match arm — Scala's `|` pattern alternation can't bind a
  variable inside itself, so both the pattern-rendering and the `byRefMut`-marking sites read the
  callee through a small new `ctorNameOf` helper instead.

## Multi-file gap (discovered, worked around, NOT fixed)

`ssc emit-rust` compiles each file argument into its OWN independent Cargo crate
(`EmitCommands.scala:133`, `for file <- files.toList do`) — there is no cross-file symbol
resolution at all. `userTypeNames`/`ctorMap`/every other per-module table is scoped per file. This
is why testing `uniml/core` as a whole requires manually concatenating all files (including the
separately-located `alphabet/src/Alphabet.scala`, an unmanaged shared source uniml/core's own
sbt build includes at compile time) into one synthetic `.scala` file first (see "How to
reproduce" above) — real multi-file/multi-module Cargo crate emission would be a substantial
separate feature, not attempted across any of the three sessions.

## What's left (beyond `uniml/core`, which is now done)

`uniml/core` itself is `cargo build`-clean with zero errors — the goal this session's work set out
for is met. What's still open, for whoever picks this up next, in roughly the order it would
need doing:

1. **The other 4 dialect modules** (`uniml/markdown`, `uniml/json`, `uniml/xml`, `uniml/yaml`) —
   untouched by any of these three sessions. They will very likely need a real value-representation
   for an OBJECT implementor of a dyn-dispatch trait (`object Literal extends DialectAdapter` in
   `uniml/core` itself renders as free functions today, with no backing struct, because nothing in
   `uniml/core` alone ever hands `Literal` around as a `Rc<dyn DialectAdapter>` value — see session
   2's dyn-trait section above) — a dialect module that actually CONSTRUCTS and REGISTERS an
   adapter (`DialectRegistry(Literal, Markdown, Json, ...)`) will need that gap closed first.
2. **Real multi-file/multi-module Cargo crate emission** — the workaround (manually concatenating
   every file into one synthetic `.scala` file, `/tmp/uniml-merge/build-merge.sh`) is fine for
   verifying a bounded set of files but does not scale to a real multi-crate uniML build or to
   anything outside this throwaway scratch setup.
3. **Wiring compiled uniML into rozum's planned RAG indexer** — the original motivating goal from
   well before any of these three sessions, entirely untouched so far.
