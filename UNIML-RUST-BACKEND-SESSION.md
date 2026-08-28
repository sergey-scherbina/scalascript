# uniML → Rust backend hardening — session summary

**Status:** all changes UNCOMMITTED, in worktree `fix-private-qualifier-bracket`
(branch of the same name). Two files touched:

- `v1/lang/core/src/main/scala/scalascript/parser/Parser.scala` (~11 lines)
- `v1/runtime/backend/rust/src/main/scala/scalascript/codegen/rust/RustCodeWalk.scala`
  (~1030 lines added/changed)

**Goal:** get `scalascript.uniml`'s core module (`uniml/core/src/main/scala/scalascript/uniml/*.scala`,
9 files) compiling through `ssc emit-rust` to a real, `cargo build`-clean Rust crate — a
prerequisite for using uniML (compiled to native Rust, zero JVM at runtime) as the lossless-CST
chunker for a planned project-source RAG feature in `rozum`.

**Result:** on the merged `uniml/core` module (all 9 files concatenated into one synthetic
`.scala` file — see "Multi-file gap" below), `cargo build` errors went **64 → 13** (an 80%
reduction) across ~30 distinct fixes. Not yet zero.

**Verification standard used throughout:** `emit-rust --print-only` reporting zero `[error]`
lines is NOT sufficient — several fixes here were only caught by an actual `cargo build` on the
generated crate. Trust `cargo build`, not the diagnostic scanner, for any claim of "this compiles."

## How to reproduce / continue

```bash
cd /Users/sergiy/work/my/scalascript/.worktrees/fix-private-qualifier-bracket
./install.sh --dev   # rebuild ssc-tools after any RustCodeWalk.scala edit

# Merge uniml/core into one synthetic file (strip each file's own `package` line,
# keep everything else) — the emit-rust CLI compiles each argument file into its
# OWN isolated crate with no cross-file symbol resolution (see below), so testing
# uniml/core as a whole requires this workaround.
{ for f in uniml/core/src/main/scala/scalascript/uniml/*.scala \
           uniml/core/src/main/scala/scalascript/uniml/dialect/*.scala; do
    tail -n +2 "$f"; echo
  done
} > /tmp/uniml-merge/merged.scala

# The two Processor[S,I,O]-returning defs (trait-object gap, see below) still need
# to be stripped to get a clean crate emission at all — see "What's NOT fixed" §1.

SSC_NO_BUILD_CHECK=1 ./bin/ssc-tools emit-rust /tmp/uniml-merge/merged-noproc.scala --print-only \
  2>&1 | grep '^\[error\]' | sort -u        # codegen-level diagnostics

rm -rf /tmp/uniml-merge/merged-noproc-out
./bin/ssc-tools emit-rust /tmp/uniml-merge/merged-noproc.scala -o /tmp/uniml-merge/merged-noproc-out
cd /tmp/uniml-merge/merged-noproc-out && cargo build 2>&1 | grep '^error' | sort | uniq -c | sort -rn
```

## What's fixed (verified via real `cargo build`)

### Parser
- `Parser.scala`: `private[X]` / `protected[X]` was mis-parsed as forcing list-literal
  interpretation of the following `[...]` in `preprocessListLiterals` — fixed with a targeted
  pass-through case for `private`/`protected`.

### Codegen — genuinely general bugs (would affect any module, not just uniML)
- **`renderEnumCase` mapped an enum case's field types against only the enum's own type
  parameters, not the full set of known user types** — any enum case field referencing ANOTHER
  user-defined type silently fell back to `i64`. This is probably the highest-value single fix in
  the session.
- **Two enums sharing a case NAME collided in the flat `ctorMap`** (`Severity.Error` vs
  `TokenChannel.Error`, both real cases in `uniml`'s own `Diagnostics.scala`/`Tree.scala`) — the
  map, keyed by bare ctor name, silently kept only the last one written, so a QUALIFIED reference
  to the other resolved through the wrong entry and emitted invalid Rust with no diagnostic at
  all. Fixed with a `(enumName, ctorName)`-keyed lookup (`_qualifiedCtors`) used everywhere a
  qualified select/pattern/call needs to resolve a specific variant.
- Generic case classes (`final case class ProcessBatch[A](...)`, `Stepped[S, O]`) dropped their
  type parameters entirely when rendered as Rust structs — `pub struct ProcessBatch { ... }` with
  no `<A>` — while call sites correctly said `ProcessBatch<i64>`. Fixed in the struct header, the
  companion `impl` block (`impl<A: Clone> ProcessBatch<A>`), and — separately — in a generic
  METHOD's own signature (`def map[B](f: A => B): ProcessBatch[B]`, needing both the owning
  struct's `A` and the method's own `B` in scope for `mapType`).
- `Vector.empty` / `List.empty` / `Map.empty` weren't lowered at all — emitted the literal Scala
  name (`Vector.empty`) into Rust, `error[E0425]`.
- `dropRight(n)` / `takeRight(n)` had a real move-out-of-reference bug (`let __v = $q;` moved a
  non-Copy receiver even when it was needed again right after, e.g. `stack = stack.dropRight(1)
  :+ x`) — now clones.
- A companion `object X: def y = ...` DEF sharing a bare Rust identifier with some UNRELATED
  companion's topval (`ProcessBatch.empty` the def, `DialectRegistry.empty` the val — genuinely
  different things, coincidentally same name) resolved through the wrong one — `_ambiguousMembers`
  didn't know about topval names as a source of collision. Fixed by feeding topval names into that
  set too, and by tracking each topval's OWNING object (`_topValOwners`) so a qualified reference
  only substitutes when the qualifier actually matches.
- No enum in this backend's whole output ever derived `PartialEq` — any `==` comparison on ANY
  enum value was uncompilable. Added `PartialEq` to both enum and struct derives (struct too,
  since a user enum holding a struct field only derives `PartialEq` correctly if every field type
  does).
- `enum X: case Emit(role: Option[String] = None)` called with empty parens (`Emit()`, all fields
  defaulted) fell to the "bare niladic enum value" path since the zero-arg call site never reached
  `namedCtorAsPositional` at all (guarded on `args.nonEmpty`) — fixed with a dedicated zero-arg
  case, PLUS a separate real gap: `_ctorDefaults` (default-value lookup) was built only from
  standalone case classes and sealed-trait case classes, never from Scala-3 `enum` cases, so even
  a non-zero-arg qualified call (`VmInstruction.Report(code = ..., message = ...)`, `severity`
  defaulted) reported "missing field severity" despite the default being right there in the
  source.
- `HashMap.contains` → `.contains_key` rewrite existed only for the ordinary-call shape
  (`byName.contains(id)`); a METHOD-REFERENCE shape (`names.find(byName.contains)`, eta-expansion)
  bypassed it entirely.
- A case-class METHOD reading its OWN field bare (`byName.contains(id)` inside
  `DialectRegistry.register`, no `self.`/`this.` prefix — Scala elides it) wasn't tracked as
  Map/Vec-typed at all, since `collectLocalMaps`/`collectLocalSeqs` only scan a def's own local
  var/val statements, never `selfMethod`'s later, purely-textual `let f = self.f.clone();`
  preamble. Fixed by feeding each method's OWN field types (already computed for other purposes)
  into `localMaps`/`localSeqs` too.

### Codegen — local-def lambda-lifting (new capability, built this session)
`TreeVm.scala`'s `step`/`stop` define local helper `def`s (`record`/`addTop`/`attach`/
`closeFrame`) that close over shared mutable local state and call each other — Rust nested `fn`
items can't capture their environment at all, and sibling CLOSURES that would alias the same
mutable local are exactly the shape rustc's borrow checker refuses even when never called
concurrently. Built real capture analysis (transitive, via fixed-point over the local call graph)
+ type inference for un-annotated captured locals, lowering each lifted def to a nested Rust `fn`
with captures as explicit `&mut`/cloned parameters. Bugs found and fixed along the way, specific
to this feature:
- Local defs were ALSO being picked up by the module-wide top-level def collector
  (`node.tree.collect { case d: m.Defn.Def => d }`, an unconditional deep collect) and rendered a
  SECOND time as an independent top-level function with a fresh, empty Ctx — fixed by adding
  `topLevelDefs`, which does not descend into a `Defn.Def`'s own body.
- A NAMED call argument to a lifted def (`emit(lexeme, valid = true)`) wasn't stripped of its
  `Term.Assign` wrapping the way the ordinary call machinery does.
- A BARE reference to a lifted def passed as a value (`closeBefore.foreach(close)`,
  `.foreach(record)` on an Option) needs wrapping in a closure so its captures can be spliced in —
  the early "is this an actual CALL to a lifted name" case only ever saw real calls.
- That wrapper closure must NOT be `move` — it's consumed synchronously within one statement, and
  `move` forces by-value capture of anything named even through `&mut`, which broke exactly the
  case of the SAME lifted def being called from two different `.foreach` sites in sequence
  (`closeBefore.foreach(close)` then `closeAfter.foreach(close)` two lines later — the first
  `move` closure moved `kinds`/`problem` themselves, leaving nothing for the second).

### Codegen — Scala variant-narrowing has no direct Rust equivalent
Scala's `case r: SomeEnum.Variant =>` and `def f(x: SomeEnum.Variant)` both narrow to a specific
enum case; Rust has no per-variant subtype a binding or parameter can narrow to — only real
pattern destructuring gets at a variant's own fields. Built:
- `case r: VmInstruction.Reframe => ... r.open ...` (`TreeVm.scala`'s `preflight`) now renders as
  `ref r @ VmInstruction::Reframe { ref closeBefore, ref open, ref closeAfter, ref role }` (a
  BORROW, not a move — binding the whole value while also moving its own fields out would make `r`
  itself only partially valid afterward), with a body-side rewrite turning `r.field` into the bare
  destructured `field`, and `r` marked in `ctx.byRefMut` so `r.clone()` correctly deref-then-clones
  instead of hitting Rust's blanket `impl<T> Clone for &T` and cloning the reference itself.
- A def PARAMETER declared with a qualified with-fields variant type (`reframeProblem(instruction:
  VmInstruction.Reframe, ...)`) gets a preamble `let VmInstruction::Reframe { closeBefore, open,
  closeAfter, role } = instruction.clone() else { unreachable!() };`, with the same body-side
  `instruction.field` → bare `field` rewrite.
- A match SUBJECT is now cloned before matching whenever any arm uses this destructure shape —
  `input.instruction` was matched twice (in two separate expressions inside `preflight`) and the
  first match's destructure invalidated the second's ability to match the same field again.
- `case instruction @ VmInstruction.Reframe(closeBefore, open, closeAfter, role) =>`
  (`Pat.Bind` over an extractor pattern) is a separate, simpler case that was entirely unsupported
  before this session (`renderPattern` had no `Pat.Bind` case at all) — added as a direct
  pass-through, since Rust's own `name @ Pattern` syntax matches it exactly.
- A qualified enum-case CONSTRUCTOR call (`VmInstruction.Report(code = ..., ...)`) — turned out to
  already have working delegation deep in `applyNonListCtor`; an earlier, higher-priority
  duplicate I added in this session was redundant (not harmful, but removed once the real bug —
  the `_ctorDefaults` enum-case gap above — was found).

### Multi-file gap (discovered, worked around, NOT fixed)
`ssc emit-rust` compiles each file argument into its OWN independent Cargo crate
(`EmitCommands.scala:133`, `for file <- files.toList do`) — there is no cross-file symbol
resolution at all. `userTypeNames`/`ctorMap`/every other per-module table is scoped per file. This
is why testing `uniml/core` as a whole requires manually concatenating all 9 files into one
synthetic `.scala` file first (see "How to reproduce" above) — real multi-file/multi-module Cargo
crate emission would be a substantial separate feature, not attempted this session.

## What's NOT fixed (13 remaining cargo errors, categorized)

1. **Trait-object support — the biggest remaining item, a real new feature, not a bug fix.**
   `trait DialectAdapter: def id: String; def aliases: Set[String] = Set.empty; def
   instructions(source: SourceInput): Processor[String, SourceChunk, VmToken]; ...` and `trait
   Processor[S, I, O]: def start: S; def step(...): Stepped[S, O]; def stop(...): ProcessBatch[O]`
   are genuine Scala traits used for dynamic dispatch (`DialectRegistry`'s `byName: Map[String,
   DialectAdapter]` holds different concrete adapters). This backend has no concept of a Scala
   `trait` becoming a real Rust `trait` + `impl Trait for EachType` + `Box<dyn Trait>` at all —
   every current trait usage in the corpus was either an intrinsics-only interface or a sealed
   hierarchy lowered to an enum, neither of which applies here. A trait-typed value/parameter
   currently falls to `mapType`'s unknown-name default (`i64`), producing: `adapter.aliases`
   (E0610, `i64` has no fields), `dialect.instructions(...)` (E0599), `vm.start` (E0615 — a
   DIFFERENT, narrower symptom: a user-defined class's own zero-arg method called without parens
   isn't recognized as callable the way stdlib Vec/Option members are — likely a smaller, separate
   fix if tackled alone), and `scalascript.alphabet.Alphabet.isUpperStart(char)` (E0425 — a call
   into a package/module this session's merge never included; may need `uniml/core/UniAlphabet.scala`
   pulled in too, separately from the trait question). The two `Processor[...]`-returning defs
   (`Literal.scala`'s `instructions`, `DialectAdapter`'s own abstract declaration) currently have
   to be stripped from the source entirely just to get the REST of `uniml/core` to emit a crate at
   all — see "How to reproduce" above.
2. **`.copy(...)`** — Scala's implicit case-class copy method (`problem.copy(span = ...)`) isn't
   supported by this backend at all; 1 error (`no method named copy found for struct Diagnostic`).
   A real, moderately-scoped, broadly useful feature if built generally.
3. **`Either.flatMap`** — the BUILT-IN `Either` enum this backend synthesizes
   (`renderBuiltinEitherEnum`) has no `flatMap` method; `Dialect.scala`'s `DialectRegistry.apply`
   needs it (`adapters.fold(Right(empty), (result, adapter) => result.flatMap(...))`).
4. **Clone-insertion doesn't recurse into wrapper-constructor arguments.** `cloneIfMoved` only
   inspects the OUTERMOST shape of a call argument (a bare name or a bare field-select); it never
   looks INSIDE something like `Some(frame.openingSpan)` to find the nested `frame.openingSpan`
   select that ALSO needs its own `.clone()`. Two errors: `frame` (partially moved via
   `Some(frame.openingSpan)`, then `frame.clone()` fails since the whole value is now only
   partially valid) and `token.span` (moved across two loop iterations the same way, inside
   `Diagnostic { ..., span: Some(token.span), ... }`).
5. **A handful of `E0308`/`E0282` errors are very likely downstream of #1** (a value that's `i64`
   because of the trait-typing gap propagating into a comparison/inference site elsewhere) — worth
   re-checking the error COUNT after #1 lands before assuming they need separate work.

## Suggested order if picked up again

1. Item 1 (trait objects) first — it's the biggest single win and item 5 may partly resolve for
   free once it lands.
2. Item 4 (recursive clone-insertion) next — bounded, mechanical, and the kind of general fix
   (like the enum-case-field-type one above) that's likely to matter beyond this one module.
3. Items 2 and 3 (`.copy()`, `Either.flatMap`) are small and independent — either order, whenever
   convenient.
4. Re-run the full merged-crate `cargo build` after each; re-check the error list rather than
   assuming a fix's error count matches its category exactly (several fixes this session resolved
   MORE than their own named error, and a couple briefly regressed something else — always
   diff the full list, not just the count).
5. Once `uniml/core` is fully `cargo build`-clean, the original "8 more files" scope
   (`uniml/markdown`, `uniml/json`, `uniml/xml`, `uniml/yaml`) is still open and untouched.

## Commit strategy — not yet decided with the user

Nothing in this worktree is committed. Given the scope (one architectural bug fix bleeding into
several others, a new lambda-lifting feature, and a handful of narrower fixes, all in one
1000-line diff to one file), it's worth discussing with Sergiy whether to split this into several
focused commits (e.g., "enum-case field-type fix", "qualified-ctor collision fix", "local-def
lambda-lifting", "variant-narrowing destructure") before merging, rather than one large commit —
each is independently defensible and independently testable, and the project's own `BUGS.md`
convention favors one entry per root cause.
