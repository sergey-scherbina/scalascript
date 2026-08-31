#!/usr/bin/env bash
#
# v1-jit-size.sh — no NEW method in the v1 tree may exceed HotSpot's HugeMethodLimit.
#
# WHY THIS EXISTS, and why it is separate from v2-jit-size.sh.
#
# `-XX:+DontCompileHugeMethods` is ON by default, and a method whose bytecode exceeds
# `-XX:HugeMethodLimit` (8000) is NEVER JIT-compiled — not by C1, not by C2. It runs in the
# bytecode interpreter for the life of the process. No warning, no log line, no correctness
# signal. In v2 exactly this cost 2.4–10.8× until `Prims.__method__` (49 384 bytecodes) was
# split, which is why `tests/e2e/v2-jit-size.sh` exists.
#
# That gate scans `v2/{src,backend-jvm-bytecode,jvm-runtime}` ONLY. Nobody ever pointed it at v1 —
# the tree that is 4.3× larger (302 210 lines vs 70 844). This one does, over the SHIPPED artifacts.
#
# WHY A FROZEN DEBT LIST AND NOT A HARD FAIL. Pre-existing offenders cannot be fixed in the commit
# that adds the gate, and a gate that is red on arrival gets disabled within a day. So the known
# ones are frozen BY NAME with their measured size; the gate fails on a NEW one, and it also fails
# when a frozen method GROWS. It is the shape already used by the negtc release gate: freeze the
# hard invariant, derive the rest.
#
# It also fails when a frozen method DISAPPEARS from the census — but read that failure carefully,
# because it has two causes and only one of them means "fixed"; see the note on the check itself.
#
# ── 2026-08-12: THIS GATE HAD NEVER ONCE RUN, AND COULD NOT HAVE ────────────────────────────────
#
# Three independent defects, each enough on its own:
#
#   1. NOT WIRED. No workflow, no suite, no script invoked it. The only things naming it were a
#      BUGS entry, a source comment and the orphan probe. Its twin v2-jit-size.sh is in ci.yml.
#   2. SILENT ABORT. The observed-set pipeline ended in `grep -E '^[0-9]+ '`; grep exits 1 on zero
#      matches and `set -euo pipefail` turned that into rc=1 with EMPTY stderr. Run it by hand and
#      you got a failure with no message.
#   3. BLIND SCOPE. It scanned `v1/**/target/scala-*/classes`, which do not exist after the
#      `install.sh --dev` its own header tells you to run — that build restores `bin/lib` from the
#      toolchain cache and never invokes sbt. One unrelated Scala 2.12 directory from the sbt plugin
#      was enough to slip past the "no classes found" guard and into defect 2.
#
# What it cost, visible the moment the scan was pointed at the right artifacts: four frozen methods
# had grown (renderTerm by 3204 bytecodes), two new offenders had appeared, and the largest method
# in the tree had never been censused at all because plugin bytecode ships nested inside a .sscpkg.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

LIMIT=8000
CENSUS="$ROOT/scripts/bytecode-size-census"
[[ -x "$CENSUS" ]] || { echo "missing $CENSUS" >&2; exit 2; }

# Frozen debt: <bytecodes> <fully.qualified.Class::method>.
#
# ⚠ SIZES MUST BE MEASURED FROM A FRESHLY BUILT TREE. The first version of this list was taken from
# whatever `target/*/classes` happened to be on disk in the shared checkout — built 2026-07-23 while
# HEAD was 07-30. The very next run in a fresh worktree then reported
# `frozen method GREW: JsGen::genExpr 24984 -> 25100` with NO commit to JsGen.scala in between: the
# baseline, not the method, was wrong. Re-baselined 2026-07-30 from a tree built out of current main.
# Before touching a number here, run `bash install.sh --dev` (or `scripts/sbtc cli/installBin`) first.
#
# Sizes below are the 2026-07-30 fresh-build measurement;
# growth beyond the recorded number is a regression even while the method stays exempt.
# SHRINKING this list is the goal. Do not add to it without a measured reason in the commit.
# RE-BASELINED 2026-08-12 from the shipped artifacts of a fresh build, and the deltas are the cost
# of this gate never having run. It is referenced by no workflow and no suite — until today the only
# things naming it were a BUGS entry, a source comment and the orphan probe. In that time:
#
#   renderTerm   16346 -> 19550   (+3204)
#
# AND 19550 -> 19630 (+80) ON THE SAME DAY, from `fix(rust): the last four, and the BADRUST column
# reaches zero` (189b8b111, 10:10) -- thirty-two minutes after this gate first became capable of
# catching anything, and its author had no reason to expect it. The number is raised rather than the
# work reverted, and raised OUT LOUD: by this gate's own definition growth is a regression, so a
# silent bump would be accepting one. What the bump does not mean is that the method got worse in
# any way a user feels -- at 19630 it is 2.45x the 8000-bytecode JIT limit and has not been
# JIT-compiled for a long time. +80 is DRIFT, and the freeze exists to make drift visible.
# The debt itself now has a slug: tests/BUGS.md `renderTerm-is-two-and-a-half-times-the-jit-limit`.
#   genExpr      25100 -> 25328   (+228)
#   evalCore     15330 -> 15428   (+98)
#   dispatchString 9839 -> 10013  (+174)
#   StaticJsEmitter$Ctx::compile  11387   NEW
#   SolidEmitter$Ctx::compile     10670   NEW
#
# dispatchString DELETED 2026-08-12: split into dispatchString/B at 5781/3437.
#
# WITH THIS THE V1 INTERPRETER HAS NO METHOD OVER THE LIMIT AT ALL. The five that remain below are
# other subsystems — the actors plugin, the JS and Rust code generators, and two frontend emitters —
# each its own debt with its own owner, not the interpreter's hot path.
#
# Acceptance was a NAMED asymmetry, not a timing: on a string-heavy workload dispatchString was
# compiled 5 times with the limit on and 8 with it off. It is now 8/8, and at 5782 bytes it reaches
# tier 2 and tier 4 with the default limit in force. Measured on a STRING-heavy workload on purpose:
# it is not hot on a list-heavy one (0 either way), where the measurement would have said nothing.
#
# evalCore DELETED 2026-08-12: split into evalCore/B/C at 4455/5120/3616. Checked the source first,
# as the disappeared-check instructs — alive, simply no longer over the limit. PrintCompilation on
# the shipped build: `evalCore (4456 bytes)` reaches tier 3 AND tier 4 with the default limit in
# force, where at 15429 bytes it was never submitted at all.
#
# The interpreter now has exactly ONE method left over the limit: dispatchString, 10013.
#
# dispatchList DELETED 2026-08-12: split into dispatchList/B/C at 4123/6043/2999, all under the
# limit, so the exemption expired and the list SHRANK — which is the whole point of freezing it.
# Checked the source before deleting, as the disappeared-check now tells you to: the method is
# alive, it is simply no longer over the limit. Verified by PrintCompilation on the shipped build:
# `dispatchList (4124 bytes)` reaches tier 3 AND tier 4 with the default limit in force, where at
# 14697 bytes it was never submitted at all.
#
# renderTerm 19630 -> 20042 the NEXT DAY, from b68389c3b (rust extension methods lower to a function
# taking the receiver first). RAISED, NOT REVERTED, and raised out loud: by this gate's own
# definition growth IS a regression, so a constant bumped quietly is how a freeze stops meaning
# anything. The commit is named, the delta is +412, and the debt underneath has its own entry —
# renderTerm is 20042 bytecodes, 2.51x the limit, so it is never JIT-compiled and has not been for
# a long time. That is the hazard; the +412 is drift. Third growth caught in two days, which is what
# the gate is for: before it ran, renderTerm went 16346 -> 19550 with nobody noticing.
#
# renderTerm 20042 -> 20333, from 3ae3258ce (std/i18n and std/ui/i18n compile — five defects behind
# one refusal). RAISED, NOT REVERTED, on the same terms as the +412 above, and this time with the
# alternative MEASURED rather than assumed: the two new arms' BODIES were extracted into helpers
# first — `renderMapContains` and `renderNegate` — and that bought EIGHT bytecodes of the 291.
# The cost of an arm is the pattern-match dispatch, not the body, so extraction does not shrink this
# method; it only adds indirection. The extraction was reverted and the number raised instead.
#
# THAT IS THE USEFUL PART OF THIS ENTRY: "split the big method up" is the obvious response to a
# frozen-size failure and it does not work here. Shrinking renderTerm means having FEWER ARMS —
# routing whole families of syntax to a separate renderer — not moving their bodies elsewhere.
#
# I ALSO GOT THIS WRONG BEFORE CI CAUGHT IT: v1-jit-size was already red locally when I pushed, and
# I filed it as a known sibling-owned red without reading the message, which named my own method.
# A red you have decided to ignore has to be re-read on every run, or it stops being a signal.
#
# Measured on a build whose stamp has b68389c3b as an ancestor — checked, not assumed, because a
# number taken from a tree fast-forwarded but not rebuilt is how this same line went out 80 short
# yesterday.
#
# renderTerm 19550 -> 19630 SAME DAY, and the miss is instructive: `189b8b111` (the Rust BADRUST
# work) had already landed when I re-baselined, but I measured against a toolchain built BEFORE the
# rebase that brought it in. The gate ran green on stale bytecode and the number went out 80 short —
# which turned main red the moment the gate reached the push path. Rebuild AFTER the rebase, not
# before; the repo has that lesson written down and it still cost a red.
#
# renderTerm 20085 -> 20673, from six lowering fixes behind std/ui/form.ssc. ATTRIBUTED BY REMOVAL,
# not by guess, because guessing about this method has been wrong four times:
#
#   two Map-apply arms (`f.drafts(k)` and `drafts(k)`) .......... +492   (measured by deleting them)
#   resolving a local fn alias for argument coercion ............ +104
#   keying the field lookup by receiver instead of an
#     interpolated "r.f" string ................................. -8
#
# The last line is the fourth local restructuring of this method to buy exactly 8 bytecodes. It is
# kept because it removes a String allocation on every `Apply` node walked, which is worth having on
# its own terms — but it is NOT a size fix and the comment beside it says so.
#
# What the growth pays for: six gaps that any module with those shapes hits — a Map apply on a
# struct field and on a local bound to one, a combinator chain that is still a sequence, a local
# holding a `Value` from a map apply, and two ways an argument can be a `Value` that the coercion
# test could not see. std/ui/form.ssc went from nine rustc errors to three.
#
# renderTerm 20345 -> 20085. THE FIRST TIME THIS NUMBER HAS EVER GONE DOWN, and it went down by
# acting on the mechanism proved in the note below rather than on the advice that preceded it.
#
# Five of the six `ctx.copy(...)` sites in the method were the SAME SHAPE — bind a closure's params,
# set `inClosure` — and each was materialising all 24 fields of `Ctx`. One helper, `enteringClosure`,
# replaced all five: -260 bytecodes, and no behaviour change (backendRust/test 278/278, the std
# corpus unmoved at REFUSED 81 / COMPILES 51 / BADRUST 0).
#
# AND THE FOLLOW-UP QUESTION IS ANSWERED TOO: with the copies folded, does making `Ctx` narrower
# help? No. Two probe fields on the 24-field record moved `renderTerm` 20085 -> 20085 (zero) while
# `walk`, where the record is CONSTRUCTED, went 2378 -> 2402 — the same 12 per field, now landing in
# a 2.4 KB method against an 8000-byte limit. The cost did not go away, it RELOCATED to somewhere it
# does not matter. Nobody should spend a cycle narrowing this record for size.
#
# THE NUMBER IS LOWERED, NOT LEFT AT 20345. A freeze kept above the measurement is free headroom for
# the next drift, which is the opposite of what a ratchet is for. The marginal cost of a new `Ctx`
# field also falls with it: one copy site left in the method instead of six.
#
# renderTerm 20333 -> 20345, and the +12 is the most useful number in this file.
#
# IT IS NOT AN ARM. The change that grew it adds ONE FIELD to the `Ctx` record. Proved by control
# rather than inferred, after three wrong guesses in a row: adding a SECOND field that is never read
# anywhere took it to 20357. Twelve bytecodes per field, read or unread, because `renderTerm`
# contains five `ctx.copy(...)` call sites and each one materialises every field.
#
# THAT IS WHY LOCAL RESTRUCTURING KEEPS BUYING NOTHING, and it was measured four ways today:
#
#   extract two arms' BODIES into helpers .......... 8 bytecodes of 291
#   fold a new arm into an existing dispatch ....... 8 bytecodes of 96
#   both together .................................. 36 of 96
#   lift a nine-set guard out of the method ........ 0
#
# The arms are not where the bytecode is. THE CONTEXT RECORD'S WIDTH IS, multiplied by the number of
# `copy` sites — so the lever that would actually shrink this method is fewer fields on `Ctx`, or
# the copies factored into one helper, and NOT the "split it along its term cases" that the debt
# entry has been recommending. tests/BUGS.md `renderTerm-is-two-and-a-half-times-the-jit-limit` is
# corrected to say so.
#
# The growth itself is a real fix — a local bound to a def is now callable — and the alternative was
# to leave a lowering that every other backend performs. Raised, announced, and with the mechanism
# named so the next person spends their build cycles on the lever that works.
#
# ─────────────────────────────────────────────────────────────────────────────────────────────
# WHAT THE JIT LIMIT COSTS renderTerm: NOTHING MEASURABLE. Measured 2026-08-13, because three
# raises in one day had turned "it is 2.5x the JIT limit" into a phrase nobody had checked.
#
# The instrument was validated BEFORE the result was believed — `-XX:-DontCompileHugeMethods`
# has to actually change the thing under test, and a first attempt did not:
#
#   one module, one JVM:   renderTerm submitted 0 times WITH the flag and 0 without   <- vacuous
#   51 modules, one JVM:   0 without the flag; 1 WITH it, tier 3, (20334 bytes)       <- valid
#
# Then the A/B, alternating, on that same 51-module workload — the most favourable one there is,
# since it is the only way the method gets hot at all:
#
#   capped   (renderTerm NEVER JIT-compiled)   2.25 s
#   uncapped (renderTerm JIT-compiled)         2.29 s     delta +0.040 s, INSIDE the spread
#
# Allowing the JIT to compile it changes nothing, and even when allowed it only ever reaches
# tier 3, never tier 4. The work is in the ARMS — 402 RustCodeWalk methods do get compiled, as
# separate lambda and anon-class methods — not in the dispatch. And that measurement is generous:
# in production this compiler runs ONE SHORT-LIVED JVM PER MODULE, where renderTerm is never
# submitted even with the flag, because it never becomes hot.
#
# SO STOP CITING THE JIT FOR THIS ENTRY. The freeze on renderTerm is worth keeping, but for the
# reason that is real: 198 arms in one 2000-line match, where ORDER IS SEMANTICS. A `Map.contains`
# arm must precede the str-receiver arm; an `extension` call arm must come first among the Select
# arms; a signal read must be decided before the generic call arms. Each of those was a defect
# before it was a rule. That is a maintainability ratchet, not a performance one, and it is the
# only argument that survives measurement.
#
# THIS DOES NOT GENERALISE TO `handleActorOp`, and the distinction is the point. That one is the
# ACTOR SCHEDULER — it runs inside the user's long-lived program, millions of times, where a
# never-JIT-compiled dispatch loop is exactly the hazard this limit describes; splitting such a
# method elsewhere in this repo bought 2.4-10.8x. The four codegen/emitter entries run at COMPILE
# time in a process that exits in seconds. Same list, same number, opposite meaning. Anyone
# reusing this measurement must re-take it for the method they are actually looking at.
# ─────────────────────────────────────────────────────────────────────────────────────────────
#
# The two NEW entries are frontend emitters, not the INT hot path; they are frozen with that as the
# measured reason rather than fixed here. `handleActorOp` is UNCHANGED at 28036 — see the nested-jar
# note below for why it briefly looked as though it had gone away.
#
# renderTerm 20673 -> 23885 (+3212), from three commits hardening the uniml/core rust-backend target
# (6bde8ac02, afdfc01b2 — dyn-dispatch trait support for `DialectAdapter`/`Processor` plus ~a dozen
# follow-on arms: `.copy()` -> struct-update syntax, `Set + elem`/`Map ++ Map` idioms, a zero-arg
# trait/struct method call recognised without parens, `Some(x.field)`/tuple-literal clone-insertion,
# the Either-combinator placeholder-lambda shape). RAISED, NOT REVERTED, on the same terms as every
# entry above: by this gate's own definition growth is a regression, so the number is bumped OUT
# LOUD rather than silently, and per the note above ("split it up" does not work here — the cost is
# `Ctx.copy(...)` call sites × field count, not the arm count) no attempt was made to shrink it back
# down by extraction; each new arm is a genuine `error[E0xxx]` this repo's own `cargo build` caught,
# not a refactor. `uniml/core` went from 64 cargo errors to 0 across the three commits.
#
# renderTerm 23885 -> 27888 (+4003), from the uniml/xml dialect-module hardening batch (55 -> 3
# diagnostics against the merged xml+markup+core module): `Left`/`Right` patterns over the built-in
# Either, `Term.Return`, `.isInstanceOf[T]` -> `matches!`, `.mkString`/`.toMap` no-paren dispatch,
# `.collect(pf)` -> `filter_map`, `.takeWhile`/`.dropWhile`/`.sortBy` on a Vec,
# `.flatMap(Obj.member)` (object-qualified function reference), `ListBuffer`/`ArrayBuffer`/
# `LinkedHashMap`/`Vector.newBuilder`/`HashSet` constructors plus `+=`/`m(k)=v`/`.result()`/
# `.add`/`.remove`/`.reverseIterator.foreach`. Same terms as the entry above: each arm is a genuine
# lowering gap this session's own `--print-only` diagnostic count and `cargo build` caught, not a
# refactor, and "split it up" still does not apply for the reason recorded there.
#
# renderTerm 27888 -> 28756 (+868), from landing MUTABLE-CLASS support (a plain, non-`case` Scala
# `class` with genuinely mutated body-level `var` fields, rendered as a Rust struct + `&mut self`
# impl — `uniml/xml`'s `Doc.scala`'s hand-written recursive-descent `Parser`, the first class this
# backend ever compiled of that shape) plus the tuple-bound-closure-param String-typing batch that
# rode along with it (`isOptionExpr`'s missing `Term.If` case, `withTupleStringLocals`). Verified
# against a REAL `cargo build`, not just `--print-only` — this is the milestone that made the
# 3 remaining `--print-only` diagnostics on `uniml/xml` go to 0. Same terms as both entries above.
#
# renderTerm 28756 -> 29696 (+940), from the FIRST round of REAL-`cargo-build`-driven fixes on
# uniml/xml (55 -> 0 `--print-only` diagnostics does not mean 0 `cargo build` errors — this corpus
# started that gate at 157): the `.startsWith(prefix, toffset)` two-arg overload, `StringBuilder` as
# a PARAMETER type (not just a local), qualified `Markup.X(...)` CONSTRUCTION sharing the pattern
# side's enum-name-from-qualifier-text bug, a chained `.append(a).append(b)` (flattened into one
# statement sequence — `String::push`/`push_str` return `()`, not the receiver), a default-argument
# fill for a bare self-method call, enum-variant field reads/`.copy` on a value never bound through
# a `match` arm (`document.root`, a plain parameter), `Either`'s `.left` projection, `.getMessage`
# on a reconstructed exception value, and an owner-qualified return-type table (`_ownedReturnTypes`)
# for a qualified call whose bare name collides with several unrelated defs. Same terms as every
# entry above — measured against `cargo build`, not shrunk back via extraction. 83 errors remain,
# next round not yet started.
#
# renderTerm 29696 -> 30632 (+936), from the SECOND round of `cargo-build`-driven fixes on
# uniml/xml (83 -> 70): `.forall`/`.exists` on a String (`.chars()`, not the Vec-shaped `.iter()`
# the general case assumed), `Option.forall` (`.map_or(true, p)`, Rust has no `.forall` at all),
# the `.indexOf(needle, fromIndex)` two-arg overload (a new runtime helper, `_str_index_of_from`,
# same Unicode-safety reason as `_str_starts_with_at`), `Integer.parseInt`/`math.max`, and a Map
# method (`.get`/`.contains`) used AS A VALUE (eta-expansion) over a KNOWN map — plus one fix
# outside `renderTerm` itself: `collectLocalMaps`'s walk had a `Defn.Val` case but no `Defn.Var`
# one, so a Map-typed local declared MUTABLE (`var bindings = inherited`, reassigned later) was
# never tracked at all. Same terms as every entry above. 70 errors remain.
#
# renderTerm 30632 -> 30740 (+108), from a THIRD round of `cargo-build`-driven fixes on uniml/xml
# (60 -> 57 — 63 was the count right after the `Nothing -> !` fix; a separate E0499 borrow-checker
# fix landed in between, in a prior commit, without touching renderTerm at all). Two fixes this
# time: `selectOrNiladicCtor`'s companion-topval case gained an INLINE branch for a topval's own
# init referencing an EARLIER topval with no preamble mechanism live (`XmlLimits`'s `val default =
# XmlLimits()` filling `core` from `Limits.default`), and the pre-existing zero-arg-def-call arm
# (`vm.start` with no parens) gained an exclusion so a qualifier that owns a TOPVAL by this name
# wins over the name-only "some def somewhere is called this" guess (`Limits.default` vs
# `MarkupCodec`'s unrelated `def default`) — that exclusion is an extra boolean literally inside
# `renderTerm`'s own match guard, which is the growth. `bareNameOrNiladicCtor`'s OWN new fallback
# (an eta-expanded bare sibling-def reference, `digits.forall(isHexDigit)`) sits OUTSIDE
# `renderTerm` and cost it nothing directly. Same terms as every entry above. 57 errors remain.
#
# renderTerm 30740 -> 30920 (+180), from two NEW dispatch-trait features on uniml/xml (57 -> 56 ->
# 53, across two commits — the dispatch-trait sibling-self-reference fix in between, 57 -> 56, cost
# renderTerm nothing: it only touched `renderDispatchTrait`). First: `object X extends Trait` used
# BARE AS A VALUE (`PureMarkupCodec`, `renderValueObjectImpl` — a unit struct + thin forwarding
# impl, the object twin of the class-only `renderDispatchTraitImpl`). Second: GLOBAL mutable
# companion-object state (`MarkupCodec._default`, `renderMutableCompanionObject` — `thread_local!`
# + `RefCell`, since `Rc<dyn Trait>` is not `Sync` and a plain `static Mutex` cannot hold one). The
# growth is ONE new top-level `Term.Assign` case (the WRITE side of the mutable-companion-state
# field, `_default = codec` — a literal new match arm + guard inside `renderTerm` itself); the READ
# side (`bareNameOrNiladicCtor`'s `moduleMutFields` case) and the value-object case both sit outside
# `renderTerm` and cost it nothing. Same terms as every entry above. 53 errors remain.
#
# renderTerm 30920 -> 31044 (+124), starting the "fix everything remaining" pass on uniml/xml (53
# -> 50, across one commit): a lifted local `fn` item (`liftLocalDefs`) referencing a topval —
# bare (`bareNameOrNiladicCtor`'s new `inLiftedFn` branch) or qualified
# (`selectOrNiladicCtor`'s existing topval-inline case, widened to the same condition) — now
# INLINES the topval's own init text instead of naming an outer `let` binding a Rust `fn` item
# cannot capture (`error[E0434]`); `String.valueOf(c)` on a `char` (Java's static factory reached
# through Scala's companion) is a new `renderTerm` arm, `.to_string()`. The growth is the new
# `String.valueOf` arm plus the `qualifiedReferenced`-style guard widening; the two topval-inline
# fixes sit in `bareNameOrNiladicCtor`/`selectOrNiladicCtor`, both outside `renderTerm`. Same terms
# as every entry above. 50 errors remain.
#
# renderTerm 31044 -> 32105 (+1061), fixing everything remaining on uniml/xml per operator request
# ("исправь все ошибки") — 50 -> 38 in two more commits. Largest single piece: the typed `catch case
# e: ParseError => Left(e)` case gained a full struct-reconstruction path (`ecOpt`/`zeroFor`,
# rebuilding `ParseError { message: <caught string>, line: 0i64, column: 0i64 }` instead of binding
# a bare `String` a `Left(e): Either<ParseError, _>` cannot accept) — inline in `renderTerm`'s own
# `Term.Try` arm, the biggest contributor. Also new/widened `renderTerm` arms: `.toChar` now
# renders `i64` (this lane's SscChar convention) instead of a real `char`, with the ONE consumer
# (`String.valueOf`) widening it back; the plain `Term.If` arm gained a "unify a lone `.charAt`
# branch with `.0`" rule; a new curried `Option.fold` -> `.map_or` arm; the self-method-call arm
# gained the SAME `_paramTypes`-driven SscChar coercion the ordinary call path already had. Several
# other fixes (E0223 struct-vs-variant destructure, `.copy` element-type threading through
# `.filter`, `eitherSideCtorName`'s qualified-call + collision-safe `_ownedDefBodies` case) sit
# outside `renderTerm` and cost it nothing. Same terms as every entry above. 38 errors remain.
#
# renderTerm 32105 -> 32294 (+189), continuing "fix everything remaining" on uniml/xml, 38 -> 29
# across two more commits. `yieldsSscChar` split in two (a genuine regression caught and fixed in
# the SAME round): the narrow original stayed for `.0`-newtype-unwrap decisions, a new
# `isConceptuallyChar` (broader — also a bare self-method whose OWN decltpe is `Char`) took over the
# append-push/indexOf-needle "does this need char::from_u32" decisions the extension had actually
# been for. Also fixed here: an enum-variant field-read now DEREFS a boxed recursive field
# (`doc.root: Elem`, boxed for enum sizing) instead of leaving a `Box<Node>` where a plain `Node`
# was declared; `opts.indent * depth` (`StringOps.*`, string REPEAT, not numeric multiply) is a new
# arm, `.repeat(n as usize)`; `paramVariantDestructures`'s own preamble gained the SAME boxed-field
# deref for a destructured PARAMETER's boxed field. The growth is these new/widened `renderTerm`
# arms; `ctorNameOfExpr`'s new `fieldCtorNames` fallback and `collectTupleDestructureCtorNames` (a
# local bound via tuple-destructuring from a collection-of-tuples method call, e.g. `val (element,
# inherited) = stack.remove(...)`, now recovers `element`'s own ctor name) both sit outside
# `renderTerm` and cost it nothing. Same terms as every entry above. 29 errors remain.
#
# renderTerm 32294 -> 32890 (+596), continuing "fix everything remaining" on uniml/xml, 29 -> 24
# across one more commit. Biggest piece: the enum-variant `.copy` reconstruction's OVERRIDE branch
# gained the SAME `Box::new(…)` wrap the non-override branch and the ordinary constructor path
# already had (`document.copy(root = resolveElement(…))`, a boxed recursive field). Also: a new
# call-site default-arg fill for a QUALIFIED call (`PureMarkupCodec.parse(source)`, reading the
# collision-safe `_ownedDefBodies` rather than the bare-name `_defaultsMap`, mirroring
# `eitherSideCtorName`'s own qualified-call case); the ungated `xs.foreach(f)` arm gained an
# exclusion for `xs.reverseIterator.foreach(f)` — a LATENT shadow (present since before this
# session, invisible until `isKnownVecReceiver` learned to recognize the receiver at all) where the
# generic arm, having no receiver-type guard by design, always won over the dedicated
# `.reverseIterator.foreach` case positioned later in the same match. `isKnownVecReceiver` gained
# two cases (a Vec-yielding `.toVector`/`.toList`/…, and a `ctorNameOfExpr`-resolved struct/variant
# field) and `ctorNameOfExpr` itself gained a `destructuredCtorNames` fallback; `collectLocalMaps`
# gained the tuple-destructure-position case `collectTupleDestructureCtorNames` already has for
# ctor names. Same terms as every entry above. 24 errors remain.
#
# renderTerm 32890 -> 33150 (+260), continuing "fix everything remaining" on uniml/xml, 24 -> 17
# across one more commit. New/widened arms: `opt.orElse(other)` (a pure `or_else` rename);
# `lexeme.count(pred)` on a String (`.chars().filter(pred).count() as i64`, `Iterator::filter`'s
# own `&Item` signature needing an extra deref its `.forall`/`.exists` siblings do not); a struct-
# field STRING LITERAL pattern (`QName(None, "xmlns", _)` / `Some("xmlns")` nested one level in) now
# binds a fresh name and bubbles an equality guard up into the arm via a new `_pendingPatternGuards`
# scratch channel (`renderPattern`'s own signature carries only pattern text, no guard channel back
# to the caller); `Term.Assign`'s fallback and `renderStrPatternArg` (`.contains('<')`/`.startsWith`/
# `.endsWith`) both gained the SscChar/`char` coercion an if/else branch and a call argument already
# had. `variantBodyCtxExtra` (a new shared helper, the ONE enrichment `renderMatch`'s own per-arm
# bodyCtx applies for a with-fields-variant typed bind) is now ALSO applied by the separate
# PartialFunction-in-`.map`/`.filter`/`.collect` renderer, which never had it before. Also fixed: a
# lifted local def's OWN `.charAt`-bound locals were invisible to `yieldsSscChar` (the enclosing
# function's `localSscChars` pre-pass does not descend into a nested def's body at all), now
# recomputed from the lifted def's own body. Same terms as every entry above. 17 errors remain.
#
# renderTerm 33150 -> 33726 (+576), continuing "fix everything remaining" on uniml/xml, 17 -> 14
# across one more commit. New/widened arms: `.drop`/`.take` on a KNOWN String receiver (UTF-16-
# indexed `_str_substring_from`/`_str_substring`, not the Vec-shaped `.into_iter()` a few lines
# below with no receiver-type guard at all); `.takeWhile`/`.dropWhile` widened to the same String
# case (with the SAME `&Item`-vs-`Item` deref `.count`'s own fix already needed, for
# `take_while`/`skip_while` specifically); `.replace(from, to)` keeps `to` a `&str` even when it is
# a char literal (only `from` is a genuine `Pattern`); `.equalsIgnoreCase` renamed to
# `.eq_ignore_ascii_case`. Also tried and REVERTED after measuring net WORSE (17 -> 24 errors):
# threading `elemType` through the generic `.foreach` dispatch the way `.map`'s own case already
# does — it fixed the one `.flatMap`-on-Option case it targeted but surfaced a `move`-closure-
# reassigning-a-captured-HashMap issue this lane does not have a fix for yet; left as a documented,
# narrower gap instead. `isStringExpr` gained a `.substring` case, and a new `isStringReceiverChain`
# helper (which does NOT touch `renderTerm`) follows a `.drop`/`.take` chain down to a bare-name
# base via `ctx.localStrings`, something `isStringExpr` alone (no `ctx`) cannot do. Same terms as
# every entry above. 14 errors remain.
#
# renderTerm 33726 -> 33874 (+148), continuing "fix everything remaining" on uniml/xml, 11 -> 9
# across four more commits (byRefMut/cloneIfMoved E0507 fix; dropping `move` from two
# PartialFunction-closure sites, fixing an E0382 capture-then-reuse-after-move; a
# collectLocalSscChars cross-sibling-def name-collision fix, the long-deferred `quote = char`
# mystery; and this one). This entry: the String `.takeWhile`/`.dropWhile` case (added in the
# entry above) special-cases an explicit-param lambda argument to render as `{ let p = argExpr;
# body }` instead of calling a rendered closure literal as an IIFE (`(move |p| body)(argExpr)`) —
# the IIFE form hit `error[E0282]: type annotations needed`, the SAME closure-literal-call
# inference gap `renderVecIterBody`'s own doubly-nested-closure comment already documents
# elsewhere; this is a NEW arm inside the SAME match case, hence it grows `renderTerm` itself.
# Also tried and RE-REVERTED (re-tested after all of the above landed, in case any of them
# incidentally covered the earlier regression too — they did not): the SAME `.foreach` `elemType`
# threading noted in the entry above, still net WORSE (9/10 -> 13 errors) for the identical
# reassigned-HashMap reason. 9 errors remain.
#
# renderTerm 33874 -> 33902 (+28), continuing "fix everything remaining" on uniml/xml, 9 -> 8. The
# self-method-call arm (`readContent(name)` inside another method of the same mutable class,
# rendered as `self.readContent(name)`) is a WHOLE SEPARATE rendering path from the ordinary call
# machinery — it exists only to prepend `self.` and fill defaults — and never called
# `cloneIfMoved` on its own arguments: `error[E0382]: borrow of moved value: name`, `name` used
# again later in the same method. Now applies the identical `cloneIfMoved` the ordinary path
# already does. 8 errors remain.
#
# renderTerm 33902 -> 33930 (+28), continuing "fix everything remaining" on uniml/xml, 8 -> 5 in
# one commit, two more call/expression-rendering paths found missing `cloneIfMoved`: (1) a call to
# a def THIS lift lifted out of the enclosing body (`emitKnownRange(start, lexeme, …)` then
# `lexeme` read again at the tail of the same function) — a THIRD separate call-rendering path,
# after the ordinary one and the self-method one fixed in the entry above, that builds its own arg
# list and never called `cloneIfMoved` either; (2) `xs :+ x` (`attributes :+ attribute` then
# `attribute` read again in a later `format!`) — the one-element array literal `[$r]` OWNS its
# element, moving it out from under a later read, and had never been routed through
# `cloneIfMoved` at all. Same terms as every entry above. 5 errors remain.
#
# renderTerm 33930 -> 33934 (+4), continuing "fix everything remaining" on uniml/xml, 5 -> 3. The
# Option `.flatMap` -> `.and_then` case (`name.prefix.and_then(…)` inside `QName { namespace:
# name.prefix.and_then(…), ..name }`, uniml/xml's Doc.scala resolveElement) never called
# `cloneIfMoved` on its OWN receiver either: `Option::and_then` takes `self` by value, so a FIELD
# projection receiver (not a bare name) partially moves the owning struct, and the struct-update's
# `..name` spread reading `name` as a whole right after can no longer borrow it — `error[E0382]:
# borrow of partially moved value: name`. Same terms as every entry above. 3 errors remain: an
# untyped `.foreach` closure param needing the same `elemType` this session tried and reverted
# twice (`E0599: no method named flatMap`); a `&mut Vec<String>` PARAMETER captured by a `move`
# closure passed to a runtime helper (`scanOpaque`) whose OWN Rust signature needs a `'static`
# bound it structurally cannot satisfy (`E0521`/`E0382: elements`) — a runtime-signature-level fix,
# not a codegen one. Both left open; see the session's own commit history for the full analysis.
#
# renderTerm 33934 -> 33974 (+40), SOLVING the `scanOpaque` gap the entry above left open, 3 -> 1.
# Root cause was deeper than "the closure captures a &mut param": `liftLocalDefs` gave EVERY
# var-capture `&mut T` unconditionally, so `scanOpaque` (`uniml/xml`'s `Doc.scala`'s `scanCData`)
# took `elements: &mut Vec<String>` even though it only ever READS `elements.nonEmpty` — the
# reborrow-prelude fix from the entry above (`{ let elements = &mut *elements; move |_| … }`)
# just traded E0521 for `error[E0499]: cannot borrow *elements as mutable more than once at a
# time` (the closure's own reborrow and the trailing plain `elements` argument, both mutable,
# alive simultaneously across the same call). Real fix: a SECOND fixed point (`writes`, parallel
# to `captures`' own) over the local-def call graph, tracking which var-captures a def's
# TRANSITIVE subtree actually WRITES (`collectDirectWrites` — direct reassignment AND the
# `x += 1`-shaped `Term.ApplyInfix` form `computeMutatingSelfMethods` already had to solve the
# identical problem for once, missed on the first pass here and caught by a fresh `E0594` sweep
# across `index`/`nextTokenId`/`diagCount`/`rootCount` after the first attempt). A def that never
# writes a var-capture now takes `&T` (shared, `Copy`) instead of `&mut T` — a `&T` can be copied
# freely, so BOTH the closure's own capture and the trailing forward become harmless copies of the
# same shared reference, no reborrow trick needed at all (new `Ctx.byRefMutWrite`, a strict subset
# of `byRefMut`, narrows the reborrow-prelude fix to only the genuinely-mutable case it was
# written for). Verified `std/ui/input.ssc`'s `selectFrom` — the real, earlier case the blanket
# `+ 'static` bound exists for — still keeps it unchanged. 1 error remains: the untyped `.foreach`
# closure param (`elemType` threading tried and reverted twice, still net-regresses elsewhere).
#
# renderTerm 33974 -> 33982 (+8), CLOSING THE LOOP: `--elemType`-threading `.foreach` (the LAST
# remaining error, tried and reverted TWICE before) fixed its own target (`error[E0599]: no method
# named flatMap`) but again surfaced 4 NEW errors — this time each individually root-caused and
# fixed rather than reverted a third time, since three of this session's OWN earlier fixes this
# round were the exact same "never called cloneIfMoved" shape: `set.add(x)` (HashSet insert),
# `Map(k -> v)` literal construction (neither the key NOR the value had ever been routed through
# it — only the key-mentions-value special case did), the `.collect{}` PartialFunction's own
# IMPLICIT `Some(...)` wrap (the backend's OWN lowering, not a user-written `Some(x)` call — a
# DIFFERENT site from the one two entries up), and — this one genuinely NEW, not just another
# missed call site — a bare TUPLE LITERAL `(child, bindings)` read inside a loop body run once per
# iteration, where `bindings` is a captured `var`: `renderTupleElems` (feeding every `(a, b, …)`
# tuple literal in the file) now runs each element through `cloneIfMoved` too, the one shape
# `cloneIfMoved`'s existing call sites never covered at all (a tuple's ELEMENTS, not the tuple
# itself). **uniml/xml's real `cargo build` now succeeds with ZERO errors** (603 warnings, all
# cosmetic — snake_case naming, unused variables). 0 errors remain.
#
# renderTerm 33982 -> 34574 (+592), starting on `uniml/json` (the same effort, one dialect module
# over) — 35 -> 17 real cargo-build errors across three fixes: (1) `xs.filterNot(p)` had NO
# lowering at all (`filterNot` emitted verbatim as a Rust method that does not exist) — new arm
# negating via an IIFE (`!($p)(x)`), works uniformly whether `p` is a bare function reference or a
# closure literal; (2) `Map.empty[K, V]` (the explicit-type-args spelling) parses as
# `Term.ApplyType` wrapping the SAME `Term.Select` the existing bare `Map.empty` case matches —
# two new arms dropping the (unneeded — `HashMap::new()`/`Vec::new()` already infer from usage)
# type args, mirroring the existing bare-spelling cases for `Vector`/`List`/`Array`/`Set`/`Map`.
# Both arms are inline in `renderTerm`'s own match, hence the growth. The THIRD fix (bare-typed
# variant pattern text, `renderPattern`'s own with-fields case previously required a QUALIFIED
# `Type.Select` — `case frame: ObjectFrame =>` on a TOP-LEVEL case class, `uniml/json`'s
# `JsonStructure.scala`, uses a bare `Type.Name` and fell through to the generic "drop the type"
# fallback, rendering an untyped catch-all `frame` while `bodyCtx` still believed it was
# destructured — `error[E0425]: cannot find value state`) lives in `renderPattern`, a SEPARATE
# function, and costs nothing here.
#
# renderTerm 34574 -> 34578 (+4), continuing on uniml/json, 17 -> 14. A `match`/`if` used as a
# STATEMENT (its own value discarded) can have arms/branches whose NATURAL Rust types disagree
# (`frame.state match { case A => closeObject(); case B => if cond then closeArray(…) else
# consumeValue(…) }`, `uniml/json`'s `JsonStructure.scala` — `closeObject`/`closeArray` return
# `()`, `consumeValue` returns `bool`) — Scala freely discards a mismatched branch/arm in
# statement position, but Rust's `if`/`match` require ALL arms/branches to unify to ONE type
# FIRST, regardless of whether the whole thing is later discarded with a trailing `;`.
# `renderStmt`'s own top-level `renderTerm(t, ctx).map(_ + ";")` only appended `;` to the
# OUTERMOST expression, which does nothing for arms that disagree internally: `error[E0308]: if
# and else have incompatible types` / `match arms have incompatible types`. New recursive
# `renderUnitTerm` (called from `renderStmt`'s Term case, `renderMatch` under a new `isUnit` flag,
# and `renderBody`'s own single-expression-body case) descends into `if`/`match`/block so EVERY
# leaf branch/arm gets its OWN unconditional trailing `;` — `expr;` is ALWAYS `()`-typed
# regardless of `expr`'s own type, which is what makes every branch/arm agree. Needed a second,
# smaller fix in `renderMatch` itself: its arm-body template is `pat => bod,` (a bare expression,
# never `expr;`), so `isUnit`'s own leaf fallback needed its `needsBlock` decision widened to
# ALWAYS brace-wrap on the `isUnit` path (`pat => { bod },` — always valid, whatever shape `bod`
# is) — missing this exact case broke `RustGenMultiShotTest` first (`Some(x) => println(x);,`,
# "expected one of `,` … found `;`"), caught before landing since the whole test suite runs before
# every corpus re-measurement. Small growth here is just the `renderStmt` call-site change to
# `renderUnitTerm`; the bulk of the new logic sits in the separate `renderUnitTerm` itself.
# renderTerm 34578 -> 34630 (+52), continuing on uniml/json, 14 -> 12 across two fixes. (1) bare
# no-paren `Option.get` (`lexed.issue.get`, `uniml/json`'s `JsonLexer.scala`) had no lowering —
# `error[E0609]: no field get on type Option<JsonLexIssue>` — new arm alongside the existing
# `nonEmpty`/`isEmpty` no-paren-Option cases, inline in renderTerm's own match (the growth). (2)
# `def consumeKey(frame: ObjectFrame): Unit = … frame.copy(state = …) …` (`JsonStructure.scala`) —
# a LIFTED local def's OWN (non-captured) parameters never populated `paramCtorNames` at all (only
# the top-level `renderDef` does, before `liftLocalDefs` ever splits a nested def out) —
# `error[E0599]: no method named copy found for enum Frame` (frame's Rust type collapses to its
# owning enum; `.copy` needs the ORIGINAL specific variant to rebuild via match). Bundled with the
# SAME fix `paramCtorNames`'s own top-level building block got two commits ago: bare `Type.Name`
# accepted alongside qualified `Type.Select` (`ObjectFrame` is a top-level case class). Both
# paramCtorNames fixes live in `renderDef`/`liftLocalDefs`, separate functions, costing nothing
# here. Re-verified uniml/xml still builds clean (0 errors) after every step in this entry.
# renderTerm 34630 -> 34814 (+184), finishing uniml/json, 12 -> 0 (clean `cargo build`). Six
# fixes this round, most in SEPARATE functions (guard-only `.to_string()` coercion via a new
# `Ctx.guardRawStrVars` in `renderMatch`; `isMapExpr`/`accumMap` threading a `foldLeft` zero's Map-
# ness into its closure's `localMaps` in `renderVecIterBody`; `declIsSeq` widened to recognise
# `Set[T]` as a seq-typed DECLARATION, `collectLocalSeqs`; the qualified-enum-ctor-construction
# case now overriding `ctx.ctorMap` with the disambiguated `_qualifiedCtors` entry before
# delegating, instead of re-resolving the bare name ambiguously — `JsonValue.StringValue(value,
# lexeme)` collided with `JsonMode`'s own bare `StringValue` case and silently dropped both
# constructor args; `cloneIfMoved`'s `Term.Select` case now also asks `needs(rendered)`, not just
# `needs(selectRoot(sel))`, catching a QUALIFIED AMBIGUOUS-topval reference — `Some(JsonDialect.
# id)` inside a loop rewrites to the flat local `JsonDialect_id`, and only the REWRITTEN name, not
# the original qualifier `JsonDialect`, is ever a topval; `hasFieldDestructurePat` widened to
# `Some((a, b))` — a var-typed `Option[(K, V)]` matched by value across loop iterations moved its
# tuple out from under itself, `error[E0382]`). The one INLINE growth is a genuinely NEW match arm
# directly in `renderTerm`'s own match: bare no-paren `.head`/`.last` on a STRING (`lexeme.head !=
# '"'`, `uniml/json`'s `JsonProjection.scala`'s `unquote`) had no lowering at all — the existing
# no-paren-Vec-member case explicitly excludes strings — and fell to a bare Rust field access:
# `error[E0609]: no field head on type String`. Lowered via the same `_str_char_at` runtime helper
# `.charAt` already uses; `SscChar`'s existing `PartialEq<i64>` impl makes the comparison against a
# `Lit.Char` (rendered as a bare `i64` code point) typecheck with no further coercion. Re-verified
# uniml/xml still builds clean (0 errors) after every step in this entry.
# renderTerm 34814 -> 36133 (+1319), starting uniml/yaml: first a genuine PARSER-level trap (not
# this file at all) — a `'${expr}'`-shaped string-interpolation splice, bare-quote-wrapped with no
# other text between the quote and `${`, trips this toolchain's parser somewhere downstream in a
# large enough merged program (`` `)` expected but `macro` found `` at the interpolation's own
# position) — fixed at the SOURCE level (plain concatenation) in `YamlStructure.scala`/
# `YamlProjection.scala`, two occurrences; a SECOND, unrelated parser trap in the same module,
# `!"lit"` (a unary op with NO SPACE against a non-numeric literal, tokenized as one combined node
# the same way `-1` becomes a negative literal), fixed the same way in `YamlSemanticParser.scala`
# (one occurrence, explicit parens). Then eleven real codegen fixes, taking `--print-only` on the
# merged module from a hard parse failure to 0 diagnostics: (1) `xs :+ (a, b)`/`x +: xs` — an infix
# operator followed by a parenthesized group parses as MULTIPLE positional args, not one tuple term,
# so `:+`'s existing `rargs.size == 1` guard silently refused a tuple-shaped append; widened to
# reassemble into a `Term.Tuple`, and `+:` (prepend, unhandled at all) added the same way. (2)
# `xs.indices` (a `Range`, not a `Vec`) plus its OWN `.map`/`.filter`/`.foreach` chain rendering —
# `.filter` already had a range lane, `.map`/`.foreach` did not and fell through to the shared
# Vec-shaped template, which calls `.iter()`, a method `Range<i64>` does not have. (3) `hasTab ||=
# …` — Rust has no `||`/`&&`-assign operator at all, unlike `+=`/`-=` (which exist and this lane
# already passes through); desugared to `l = l || r`. (4) `&`/`>>>` bitwise ops, absent from
# `mapInfixOp` entirely. (5) `.stripTrailing()` on a String. (6) a case class's OWN method calling
# bare `copy(...)` on the implicit `this` — both existing `.copy` cases require an explicit
# `Term.Select` receiver, a different AST shape; rendered via `Self { .. }` field-by-field (each
# un-overridden field read from the case-class-method alias prelude's OWN local, not `self.field`
# — an ordinary immutable case class never populates `Ctx.trueSelfFields`). (7) the QUALIFIED
# ENUM-CONSTRUCTOR-PATTERN twin of an already-fixed construction-side bug: `case YamlValue.Alias
# (name) =>` delegated to the bare spelling and re-resolved through the ambiguous bare-keyed
# `ctorMap`, landing on a DIFFERENT same-named zero-field case (`YamlPropertyKind`'s own `Alias`)
# and refusing the real 1-arg pattern — same `_qualifiedCtors`-override fix the constructor side
# already got. Four fixes live in SEPARATE functions, costing nothing here: `collectLocalStrings`
# widened for a TUPLE-PATTERN destructure from a call returning a declared tuple type, AND for
# indexing into a `Vector[String]` parameter; `seqCtor` widened for an INFIX `++` chain of
# Vec-returning calls (neither shape any existing case matched, both syntactically distinct from a
# bare call/select); `isKnownStringField` widened to resolve a field read off a `.last`/`.head` on
# a `Vec<Struct>` receiver (`elementTypeOf` already answers this, just was never asked here); and
# `isStringReceiverChain` widened past `.drop`/`.take` to also chain through `.reverse`/
# `.dropWhile`/`.takeWhile` (INCLUDING the already-existing `.takeWhile`/`.dropWhile`-on-String
# lowering a few lines down, which reuses the SAME chain check for free once widened — an early,
# now-reverted attempt to duplicate that case here regressed an established IIFE-avoidance test,
# `s.takeWhile(char => …)` on a String, and was caught before landing since the full suite runs
# before every corpus re-measurement). Re-verified uniml/xml and uniml/json both still build clean
# (0 errors) after every step in this entry.
#
# renderTerm 36133 -> 36393 (+260), starting a REAL `cargo build` pass on uniml/yaml (184 genuine
# errors, `--print-only` was already clean). Two structural gaps in `liftLocalDefs`, both about a
# def NESTED TWO (or more) BLOCK LEVELS below the state it needs, not one — the existing capture
# machinery only ever looked at the IMMEDIATE block's own locals plus the enclosing DEF's params:
# (1) `def visit(...) = … allDiagnostics = … ; visit(...)` nested inside a `foreach` closure inside
# `validate` — `allDiagnostics` (a `var` in `validate`'s OWN top-level body, one block further out
# than `visit`'s immediate enclosing closure) never reached `visit`'s capture pool at all, so it
# rendered as a nested Rust `fn` referencing a free name from an enclosing scope — a nested `fn`
# item cannot do that in Rust regardless of nesting depth: `error[E0434]: can't capture dynamic
# environment in a fn item`. Fixed with three new `Ctx` fields (`enclosingVarNames`/
# `enclosingValNames`/`enclosingLocalTypes`) that `liftLocalDefs` folds its OWN block's var/val
# names (and their resolved types) into on EVERY call — even a block with no local defs of its own,
# since such a block can still sit between an outer var and a deeper lift that needs to see it — so
# the capture pool and `inferCaptureType`'s fallback both see arbitrarily far outward. (2)
# `parseNode` (lifted out of `flowParse`'s OWN body, a SECOND nested `liftLocalDefs` pass) calling
# `quotedSingle`/`problem` (lifted one level UP, siblings of `flowParse` itself, each needing
# `diagnostics: &mut Vec<Diagnostic>`) — `baseCtx` OVERWROTE `liftedDefExtraArgs`/
# `liftedMutableCaptures` with just THIS level's own captures instead of merging, so `quotedSingle`
# silently stopped being recognized as a lifted-def call two levels down and its call sites emitted
# with no capture arguments at all: `error[E0061]: this function takes N arguments but N-1 (or
# N-2) were supplied`. Fixed by merging instead of overwriting, plus a new `outerCalls`/
# `liftedDefMutWrites` mechanism so a def that merely RELAYS a capture on to an outer-level callee
# (never reading/writing it itself) still receives it, and receives it `&mut` when the callee
# writes it. A THIRD, unrelated gap in the same batch: a local def's OWN trailing DEFAULT parameter
# (`def problem(code, message, span, severity: Severity = Severity.Error)`) was invisible to the
# module-wide `_defaultsMap` fill (which deliberately never descends into a def's own body to find
# local defs) and to the lifted-call rendering arm (which builds its own argument list rather than
# reaching the `_defaultsMap`-aware ordinary call machinery) — omitted-default call sites
# (`problem(code, msg, span)`, most of its ~26 call sites) undercounted by exactly the trailing
# default: fixed with a new `Ctx.liftedDefDefaults` field and a matching fill at that same call
# site. The growth is ONLY the third fix's own default-fill, inline in the
# `ctx.liftedDefExtraArgs.contains(n)` call-rendering arm inside `renderTerm`'s own match; the
# first two fixes live entirely in `liftLocalDefs`, a separate function, and cost nothing here.
# 184 -> 138 real cargo errors on uniml/yaml. Re-verified uniml/xml and uniml/json both still build
# clean (0 errors).
#
# renderTerm 36393 -> 36885 (+492), continuing the real `cargo build` pass on uniml/yaml, 138 -> 120.
# Seven independent fixes, all inline `renderTerm` match arms (the growth) plus one in a separate
# function (free): (1) `opt.exists(p)`/`opt.contains(v)` — Rust's `Option` has neither method
# (`.exists` doesn't exist at all; `.contains` is nightly-only), and no case existed for either —
# `tag.exists(...)`/`tag.contains(...)` (`plainScalar`) reached rustc unmapped. Lowered to
# `.is_some_and(p)` and `.as_ref().is_some_and(|v| *v == x)`. (2) A LIFTED local def's OWN parameter
# feeding `localOptions` for a `.map`-chain local built INSIDE that same def
# (`explicitTag.map(normalizeTag)`) — `collectLocalOptions` is a pre-pass run ONCE at top-level
# `renderDef` with a bare `Ctx` carrying NO param types at all (not even the top-level def's own),
# so a lifted def's own param used this way never registered its local as an Option; recomputed per
# lifted def in `liftLocalDefs`, seeded with that def's own resolved param types (`ownParamTypes`,
# factored out for reuse) — THIS fix's own growth is zero (lives in `liftLocalDefs`, not
# `renderTerm`), it just unblocks (1) from firing at all on `tag`. (3) `renderVecIterBody`'s
# `Term.Function` branch (a named-param key, as opposed to the placeholder-`_` shape a SIBLING
# branch already had a `sortBy` case for) had no `"sortBy"` case, so `ranges.sortBy(range => …)`
# fell to the generic fallback and re-emitted the Scala method name verbatim. (4) `.count(pred)` on
# a Vec receiver — the existing case only ever fired for a String; `lexed.tokens.count(...)` reused
# `renderVecIterBody`'s own `.filter` dispatch (avoiding a duplicate one) then wrapped `.len()`.
# (5)/(6) `.stripPrefix`/`.indexWhere` on a String — neither has a same-named Rust `String` method
# at all; lowered to `.strip_prefix(...).map(...).unwrap_or_else(...)` and
# `.chars().position(pred)`, reusing this lane's established SscChar code-point convention. (7)
# `ctorNameOfExpr` had no case for a bare `Term.Placeholder` receiver (`frames.map(_.copy(last =
# lineEnd))`) — the placeholder is still a raw AST node, not yet the literal name `__p0`, when this
# ctor-lookup runs (`isKnownStringField`'s own identical `Term.Placeholder` case, added earlier this
# session, is the same fix for the same reason) — a new case reading `ctx.paramTypes.get("__p0")`
# closes it, in a SEPARATE function so it costs nothing here. Two more, in `renderTerm` too and
# folded into this same growth: the STRING-receiver guards on `.drop`/`.take` and `.length` widened
# to also check `isKnownStringField` (a field READ off a known ctor), not just a bare name or pure
# syntax — `line.raw.drop(...)`/`line.raw.takeWhile(...).length` (`indentOf`/`parseBlockScalar`)
# took the Vec-shaped lowering otherwise. Re-verified uniml/xml and uniml/json both still build
# clean (0 errors). Remaining, not attempted this entry: `.groupBy` on a Range (still deliberately
# deferred, a genuinely new HashMap-shaped feature) and a tuple-destructured local's element type
# (`val (a, b, c) = someCall()` then using `c` as an Option — two levels removed from the call that
# would name its type, unlike the direct-destructure shape an earlier entry already covers).
#
# renderTerm 36885 -> 36921 (+36). Two fixes, continuing the uniml/yaml real `cargo build` pass
# (two intervening commits — Unit-as-generic-type-argument, and the `continue`-keyword escape —
# needed no bump, both living entirely in separate functions). (1) `scanBlockHeader(char, ...)`
# where `char` is a known `SscChar` local and the CALLEE's own declared parameter is `Int`, not
# `Char` — a lifted-local-def call builds its own argument list (bypassing the ordinary call
# machinery's existing `.0`-unwrap for exactly this newtype-vs-`i64` mismatch), so the unwrap
# never applied there. New `Ctx.liftedDefParamTypes` (def name -> its own declared, non-captured
# parameter types, mirroring `liftedDefDefaults`'s shape) lets the call-rendering arm ask "does
# position i expect i64" the same way the ordinary path already does; the growth is the arm's own
# new per-argument check, inline in `renderTerm`'s match — `liftedDefParamTypes` itself is
# populated in `liftLocalDefs`, a separate function, and costs nothing. (2) `match token.lexeme {
# "[" | "{" => …, }` — a `|`-combined ALTERNATIVE pattern whose leaves are string literals is
# still a string-literal match overall, but `hasStringPat` only ever checked a case's pattern
# being DIRECTLY a `Lit.String`, never recursing through `Pat.Alternative` — the match subject
# never got its `.as_str()` coercion, so string-literal ARMS (valid `&str` patterns) were matched
# against an owned `String` subject: `error[E0308]: expected String, found &str`. Lives entirely
# in `renderMatch`, a separate function, and costs nothing here. uniml/yaml: 85 -> 74 real cargo
# errors. Re-verified uniml/xml and uniml/json both still build clean (0 errors).
#
# renderTerm 36921 -> 37197 (+276), continuing the uniml/yaml real `cargo build` pass, 74 -> 63 ->
# 54 (two intervening commits needed no bump: the collectCopyNames String-detection gap lives
# entirely in a separate function). Three fixes this entry: (1) `def boundaryFailure(scan:
# YamlPropertyScan, ...) = scan.failure...` — `scan` is an ordinary PARAMETER here, but ALSO the
# name of a SIBLING top-level def in the same object (`YamlPropertySyntax.scan`, flattened to
# `YamlPropertySyntax_scan`); `bareNameOrNiladicCtorTail`'s fallback never checked whether a bare
# name is ALSO a known local/param before asking SITE 3's intrinsics map whether it names a
# sibling def, so the PARAMETER lost to the FUNCTION: `scan.failure` rendered as
# `YamlPropertySyntax_scan.failure`, a field read on a function item. New case in that function
# (a SEPARATE function from `renderTerm`, costs nothing) checks `ctx.defParams` first. (2) `val
# starts = if … then 0 +: documentStarts else 0 +: documentStarts.tail` then `starts.indices` —
# `:+`/`+:` (append/prepend) had no case in `rootedInSeq` (`collectLocalSeqs`'s own helper,
# ALSO a separate function) at all, and neither did an if/else whose every branch is seq-rooted;
# without both, `starts` never registered as a seq, so `.indices` (needing `isKnownVecReceiver`)
# reached rustc as a plain field access. (3) `validateTagSpelling(spelling).left.toOption` —
# Scala's `Either.left` projection then `.toOption`; this lane's own fallback `Either<L, R>` has
# no `.left` field at all. THIS is the only fix actually inline in `renderTerm`'s own match (the
# growth) — and needed CARE about ORDER: a fully-generic, unconditional bare-`Term.Select`
# fallback sits earlier in the same match and silently swallowed the new case as unreachable
# (`-Werror` caught it) until moved above that fallback, right where existing narrowly-scoped
# bare-Select cases (`getMessage`, map `.get`/`.contains`) already sit for the identical reason.
# Re-verified uniml/xml and uniml/json both still build clean (0 errors).
#
# renderTerm 37197 -> 37385 (+188), continuing the uniml/yaml real `cargo build` pass, 54 -> 47 ->
# 45. Two fixes: (1) `Option.when(valid, result)` — Scala's static `Option` factory (`Some` if
# `cond` else `None`); no case existed for it at all, so it reached rustc as a literal call on
# the `Option` TYPE itself: `error[E0423]: expected value, found enum Option`. New case, inline
# in `renderTerm`'s own match — the growth. (2) `private def isSubDelimiter(value: Char): Boolean
# = "!$&'()*+,;=".contains(value)` — `isConceptuallyChar`'s bare-name case only ever checked
# `_defBodies` for a NILADIC-DEF reference (a Scala parameterless method read bare), never
# whether the name is the CURRENT def's OWN parameter declared `Char` — so a `Char` param passed
# to `.contains`/`.startsWith`/`.endsWith` kept its raw `i64` form where Rust's `Pattern` trait
# needs an actual `char`: `error[E0277]: the trait bound &i64: Pattern is not satisfied`. Lives
# entirely in `isConceptuallyChar`, a separate function, and costs nothing here — BUT the first
# attempt used `p.decltpe.contains(m.Type.Name("Char"))` (`Option.contains`, i.e. `==`), the EXACT
# mistake this same function's own docstring three lines above already warns about by name
# (scalameta `Tree` equality is position-sensitive, so a freshly-built `Type.Name("Char")` never
# equals the parser's own instance) — caught by testing the standalone repro before trusting it,
# not by the test suite (a `.contains` guard that is always `false` produces no compile error,
# just silently never fires); fixed to a real pattern match. Re-verified uniml/xml and uniml/json
# both still build clean (0 errors).
#
# renderTerm 37385 -> 37389 (+4), continuing the uniml/yaml real `cargo build` pass, 40 -> 39. The
# SscChar `.0`-unwrap batch two commits ago needed no bump (all three fixes lived in helper
# functions). `opt.exists(p)`'s own `Option::is_some_and` CONSUMES `self` — unlike `.contains`'s
# `.as_ref()` two cases down, which only borrows — so `tag.exists(...)`, read again later in the
# same `if/else` chain (`plainScalar`), needed the same multi-use clone every other by-value
# position already gets: `error[E0382]: use of moved value: tag`. `cloneIfMoved` on the rendered
# qualifier, inline in this arm.
#
# renderTerm 37389 -> 37393 (+4), continuing the uniml/yaml real `cargo build` pass, 37 -> 35 (the
# while-loop-clone commit in between needed no bump). Two fixes: (1) the `.count(pred)` case
# (added earlier this session) never passed `elemType` to `renderVecIterBody`, so a placeholder
# predicate's `__p0` never got typed — a tiny inline addition, all four bytecodes of growth. (2)
# `renderVecIterBody`'s OWN `Term.AnonymousFunction` branch: `find`/`filter`/`takeWhile`/
# `dropWhile` wrapped the WHOLE rendered closure in an IIFE (`|__f| ($f)(__f.clone())`) to bridge
# `Iterator::filter`'s `&Item` signature — but a closure LITERAL called like that is exactly the
# shape rustc cannot infer a type through (this file's OWN documented doubly-nested-closure
# limitation), and this was a PRE-EXISTING gap, not something this session introduced —
# `ranges.filter(_.start == index)` (`blockRanges`) hit it too. Fixed by rendering `af.body`
# SEPARATELY (replicating `_phCounters`, not reusing the wrapping case) and splicing it into a
# `let`-binding instead, mirroring the sibling `Term.Function` branch's OWN already-working
# convention for the identical `&Item` cases. Lives entirely in `renderVecIterBody`, a separate
# function — the growth is `.count`'s own `elemType` argument alone. Full 403-test suite stayed
# green with no golden changes despite the shape change touching every placeholder-predicate
# `.filter`/`.find`/`.takeWhile`/`.dropWhile` call in the corpus. Re-verified uniml/xml and
# uniml/json both still build clean (0 errors).
#
# renderTerm 37393 -> 37405 (+12), continuing the uniml/yaml real `cargo build` pass, 34 -> 32 (the
# localStrings-for-a-lifted-def's-own-param commit in between needed no bump). `declared + handle`
# where `declared: Set[String]` and `handle: String` (`uniml/yaml`'s
# `YamlTagEnvironment.register`) — the `String + any`/`any + String` `format!`-concat case's own
# guard only ever checked whether EITHER operand looks like a string, and `handle` alone always
# does; positioned BEFORE the Set/Vec single-element-add case further down, it always won,
# rendering a genuine `Set[String] + String` (Scala never means string concatenation here — the
# LEFT side is never itself a String when this shape typechecks) as `format!("{}{}", declared,
# handle)`: `error[E0308]: expected Vec<String>, found String`. Fixed by excluding a known-Vec LHS
# from the string-concat guard, the same shape the OTHER case's own guard already checks FOR — a
# Vec/Set LHS now always wins that case regardless of what the right operand looks like. Inline in
# `renderTerm`'s own match, the growth. Re-verified uniml/xml and uniml/json both still build
# clean (0 errors).
#
# renderTerm 37405 -> 37629 (+224), continuing the uniml/yaml real `cargo build` pass, 32 -> 30.
# Four fixes, three living in separate functions (`collectSeqParams`, `liftLocalDefs`,
# `renderStrPatternArg` — cost nothing here): (1) `collectSeqParams`'s `isSeqType` was missing
# `Set` even though this lane maps it to `Vec<T>` throughout — a `Set`-declared PARAMETER never
# registered as a seq, so `visiting + name` (the single-element-add rewrite) fell to the generic
# `+` path: `error[E0369]: cannot add String to Vec<String>`. (2) the SAME
# localOptions/localStrings two-deep gap from earlier this session, for `ctx.localSeqs` — a
# lifted local def's OWN Vec/Set-typed param was invisible to it, recomputed per lifted def now,
# seeded with `collectSeqParams(d)`. (3) `renderStrPatternArg`'s `isConceptuallyChar` case
# (`.contains(char)`-shaped String Pattern arguments) cast a genuine `SscChar` NEWTYPE straight to
# `u32` without the `.0` unwrap `yieldsSscChar` (narrower, and deliberately kept separate — its
# own docstring already warns against widening this to the broader check) says it needs: `as u32`
# on a struct is not valid Rust at all — `error[E0605]: non-primitive cast`. (4) `Int.MaxValue`/
# `Int.MinValue` (Scala's boxed-numeric static constants, `Int` maps to `i64` throughout this
# lane) had no case at all, reaching rustc as a bare reference to a nonexistent value — THIS one
# is inline in `renderTerm`'s own match (positioned carefully, like `getMessage`/map
# `.get`/`.contains` before it, ahead of the fully-generic bare-Select fallback that would
# otherwise swallow it as unreachable) and is the growth. Re-verified uniml/xml and uniml/json
# both still build clean (0 errors).
#
# renderTerm 37629 -> 37637 (+8), continuing the uniml/yaml real `cargo build` pass, 24 -> 23 (the
# two move-closure-clone commits in between needed no bump — `wrapMove` compiles to its own
# synthetic method, not inline here). Vec `.take(n)`/`.drop(n)` (non-range) lower to
# `.into_iter().take/skip(...).collect()` — a genuine CONSUME, unlike Scala's own `.take`, which
# builds a new collection without touching the receiver — and neither case ever called
# `cloneIfMoved` on the receiver: `tokens.take(documentStarts.head)` then `tokens` again later in
# the same def (`uniml/yaml`'s `YamlStructure.scala`'s `streamAndDocuments`) moved it out from
# under the later read: `error[E0382]: borrow of moved value: tokens`. Inline in both arms' own
# `yield`, the growth. Re-verified uniml/xml and uniml/json both still build clean (0 errors).
#
# renderTerm 37637 -> 37817 (+180), continuing the uniml/yaml real `cargo build` pass, 23 -> 18 —
# the single biggest win of this whole pass. `tokens.indices.groupBy(index =>
# tokens(index).span.start.line)` (`uniml/yaml`'s `YamlStructure.scala`'s `blockRanges`) —
# genuinely no lowering existed for `.groupBy` at all, deliberately deferred much earlier this
# session as "a new HashMap-shaped feature" — but it turned out to be the SOLE root cause behind
# FIVE of the remaining errors, not one: `byLine`'s own type staying unresolved (rustc had nothing
# to lower `.groupBy` to at all) cascaded through every local built from it three and four `.map`/
# `.filter` chains deep, each surfacing as its OWN `error[E0282]: type annotations needed`. Two new
# cases (a Vec-receiver one, and a SEPARATE Range-receiver one — `Range<i64>` has no `.iter()` at
# all, so routing it through the Vec case's own `renderVecIterBody` dispatch would just trade one
# `error[E0599]` for another) both render `Vec<(K, Vec<V>)>` — this lane's OWN Map convention,
# INSERTION order, not a genuine `std::collections::HashMap` — since the group key's type is never
# independently known here the way a real HashMap's turbofish would need; inferred instead from
# how the Vec is used, the same way a bare `Vec::new()` already is throughout this file. The Range
# case is the growth (both its own dispatch and its full loop-based grouping algorithm sit inline
# in `renderTerm`'s own match); the Vec case's OWN algorithm lives in `renderVecIterBody`, a
# separate function, and costs nothing here. Re-verified uniml/xml and uniml/json both still build
# clean (0 errors).
#
# renderTerm 37817 -> 37821 (+4), continuing the uniml/yaml real `cargo build` pass, 18 -> 17 -> 16
# (the collectLocalStrings tuple/->-pair commit in between needed no bump). `lastSpan.getOrElse(…)`
# where `lastSpan: &mut Option<SourceSpan>` (a captured `var`) — `renderTerm` derefs a `byRefMut`
# name to `(*lastSpan)`, and `.unwrap_or` CONSUMES its receiver; moving out of a dereferenced
# borrow is illegal UNCONDITIONALLY, the moment there is a borrow at all, not just on multi-use:
# `error[E0507]: cannot move out of *lastSpan which is behind a shared reference`.
# `cloneIfMoved` already has a `ctx.byRefMut` case for exactly this; it was never asked here
# because a method's OWN receiver, unlike an ordinary argument, never reached it. Inline in this
# arm's own `yield`, the growth. Re-verified uniml/xml and uniml/json both still build clean (0
# errors).
#
# renderTerm 37821 -> 37869 (+48), continuing the uniml/yaml real `cargo build` pass, 7 -> 6.
# `pred(s.charAt(i))` where `pred: Char => Boolean` (`allFrom`) — a call to a FUNCTION-TYPED
# PARAMETER never went through the SscChar `.0`-unwrap coercion at all, since that coercion is
# keyed off `_paramTypes` (module-level def names only), and `pred` is a parameter, not a def:
# `error[E0308]: expected i64, found SscChar`. The lookup itself (`closureCalleeParamTypes`,
# parsing a closure param's `impl Fn(...)` signature string back into a `List[String]`) is a
# SEPARATE function and costs nothing; the growth is the call site's own widened condition
# (`_paramTypes.contains(calleeName) || closureWant.nonEmpty`) and the `want` selection between
# the two sources, both inline in this arm's own body. Re-verified uniml/xml and uniml/json both
# still build clean (0 errors).
#
# renderTerm 37869 -> 37885 (+16), FINISHING the uniml/yaml real `cargo build` pass: 6 -> 0 (clean
# `cargo build`), the last fix a genuine SEMANTIC bug, not just a borrow-check false positive.
# `stream: YamlValue.Stream`'s destructured field `documents` (`resolve`) collided by pure NAME
# COINCIDENCE with an UNRELATED `var documents` declared later in the SAME function; Rust's own
# shadowing rules meant `stream.documents.foreach{…}`'s iteration source silently resolved to the
# WRONG (accumulator) binding instead of the field — `error[E0506]` was the borrow checker
# noticing the SYMPTOM (the loop reassigning the very Vec its own iterator borrowed), not the
# actual defect. Fixed by renaming a destructured field's binder (`__dstruct_documents`) whenever
# it collides with any other local in the def, threaded through a new `ctx.destructuredFieldRenames`
# map consulted at the existing `Select(Name(n), Name(field)) -> bare field` rewrite site (inline
# in `renderTerm`'s own case, the growth); the rename computation itself lives in `renderDef`, not
# `renderTerm`, and costs nothing here. Re-verified uniml/xml and uniml/json both still build
# clean (0 errors). uniml/yaml: 184 -> 0 real `cargo build` errors this session, across many
# small, individually-tested commits.
#
# renderTerm 37885 -> 38205 (+320), starting on `uniml/markdown` (a NEW dialect module, `--print-
# only` diagnostics 64 -> 37 -> 29 across two fixes so far). `edges.collectFirst { case p if g =>
# v }` had NO lowering at all — a genuinely new `renderTerm` arm (the growth), the first-match twin
# of the pre-existing `.collect` arm just above it: same case-based partial-function rendering
# (each arm wrapped in `Some(...)`), but `Iterator::find_map` already IS "first Some(...) wins,
# short-circuiting" — no trailing `.collect()` needed, since `find_map` itself returns the
# `Option<T>` `collectFirst` means. Re-verified uniml/xml, uniml/json, and uniml/yaml all still
# build clean (0 errors).
#
# renderTerm 38205 -> 39137 (+932), continuing uniml/markdown, 29 -> 28. `firstMarker(edges).
# exists(m => m.nonEmpty && …)` — a bare `Term.Function` argument to Option's `.exists` renders
# through PLAIN `renderTerm` (there is no `renderVecIterBody`-style dispatch for Option methods,
# the way there is for a Vec receiver's `.exists`/`.forall`), so `m`'s own type never got seeded
# from `firstMarker`'s declared `Option[String]` return type — `m.nonEmpty` (no-paren) reached
# `isKnownStringField`/`isStringExpr` with nothing to check: "reads nonEmpty without parentheses
# ... it is a collection member, not a field". Fixed with a `predCtx` computed inline in this arm's
# own case (the growth: a pattern match plus a `Ctx.copy(...)` call), seeding the closure param the
# SAME way `renderVecIterBody`'s `Term.Function` branch already does for a Vec receiver — mirrored
# rather than shared, since that function's own dispatch is Vec-specific throughout. The lookup
# itself (`optionElementTypeOf`, the `Option` twin of `elementTypeOf`) is a separate function and
# costs nothing. Re-verified uniml/xml, uniml/json, and uniml/yaml all still build clean (0 errors).
#
# renderTerm 39137 -> 39241 (+104), continuing uniml/markdown, 26 -> 25. `titleLex.isEmpty` inside
# `def title: Option[String] = …` (`MarkdownBlocks.scala`'s `RefDef`) — `titleLex` is the case
# class's OWN constructor param, read bare (implicit `this.`) from one of its own methods; it is
# never added to `ctx.localStrings` (that set only ever collects LOCAL `val`s a method's own body
# declares), but `ctx.paramTypes` already carries it as `"String"` (`renderDef`'s own
# `ownFieldTypes`, folded into `paramTypes` at `Ctx`-construction time). Widened the existing
# `.nonEmpty`/`.isEmpty` guard's bare-name disjunct — inline in this arm's own case, the growth.
# Re-verified uniml/xml, uniml/json, and uniml/yaml all still build clean (0 errors).
#
# renderTerm 39241 -> 39320 (+79), continuing uniml/markdown, 25 -> 22 across three fixes. (1)
# `edges.collectFirst { … }.flatten` — a NEW `renderTerm` arm lowering Option's own `.flatten`
# (Rust's `Option<Option<T>>` has the identical method), guarded on `isOptionExpr`, which needed
# `collectFirst` added to its own "collection methods that return an Option" list first (a separate
# widening, in a helper, costs nothing) before the new arm could ever be reached. This new arm is
# the growth. (2) `val rows = edges.collect { case … }` then `rows.headOption` — `.collect` added
# to `collectLocalSeqs`'s own `SeqMethods` set (a separate function, costs nothing): it always
# returns a `Vec` on this lane, matching every other method already in that set. (3) `Set("a",
# "b")` — added to the list of constructor names lowering to `vec![...]` (this lane's own Set-as-
# Vec convention, already used for a Set-typed PARAMETER); inline in the SAME pre-existing `Term.
# Apply` case as `List`/`Vector`/`Array`, so no separate arm, no separate growth. Re-verified
# uniml/xml, uniml/json, and uniml/yaml all still build clean (0 errors).
#
# renderTerm 39320 -> 39368 (+48), part of the post-emailLocalBackscan uniml/markdown real-
# cargo-build backlog (--print-only diagnostics were already at 0; this is fixing SILENT codegen
# bugs a real `cargo build` of the full corpus surfaced, 155 errors down to 79 across several
# commits). `inner.stripSuffix(">")` (`MarkdownInlines.scala`'s `autolinkFor`) — `String.
# stripSuffix` had NO lowering at all (`stripPrefix`, its prefix twin two arms up, already did);
# a NEW `renderTerm` arm mirroring `stripPrefix`'s own shape exactly (`.strip_suffix(...).map(...)
# .unwrap_or_else(...)`) is the growth. Re-verified uniml/xml, uniml/json, and uniml/yaml all still
# build clean (0 errors).
#
# renderTerm 39368 -> 39400 (+32), continuing the same real-cargo-build backlog, 79 -> 78.
# `content.forall(c => …)` inside `case class MdLine(content: String, …): def isBlank = content.
# forall(…)` (`MarkdownBlocks.scala`) — `content` is a case-class FIELD bound to a local by the
# method's own preamble (`let content = self.content.clone();`), never in `ctx.localStrings` (LOCAL
# `val`s only), so the existing `.forall`/`.exists`-on-String case's guard missed it and it fell to
# the generic Vec-shaped `.iter()` case: `error[E0599]: no method named iter found for struct
# String`. Widened the existing guard's bare-name disjunct to also check `ctx.paramTypes` — inline
# in this arm's own case, the growth (the SAME fix shape `titleLex.isEmpty`'s own earlier entry
# already used for `.nonEmpty`/`.isEmpty`). Re-verified uniml/xml, uniml/json, and uniml/yaml all
# still build clean (0 errors).
#
# renderTerm 39400 -> 39512 (+112), continuing the same backlog, 74 -> 66. `val opener = nodes(
# found).asInstanceOf[WDelim]` (`MarkdownInlines.scala`'s `processEmphasis`) — `.asInstanceOf[T]`'s
# existing case is a no-op identity, right for a value consumed where it's written but wrong for one
# BOUND to a `val`: a later `opener.lexeme` has no such field on the whole `WNode` enum (`error
# [E0609]`). Two NEW inline cases are the growth: (1) `renderLetBinding`'s own new case renders a
# genuine destructuring `let-else` instead of a plain `let`, and (2) the field-read site
# (`renderTerm`'s own `Term.Select` case) reads the destructured field back through a uniquely
# name-prefixed scheme (`opener_lexeme`) rather than the ordinary bare-name one, needed because
# `opener`/`closer` narrow the SAME `WDelim` variant in one function via TWO different mechanisms
# (this `val`, and an ordinary `ref`-borrowing match arm) and the flat, field-name-only `byRefMut`
# table has no way to tell their same-named fields apart otherwise. `collectAsInstanceOfCtorNames`/
# `asInstanceOfNarrowedCtor` (new helpers, POSITION-aware so a match-arm binder in a genuinely
# SIBLING scope is left alone rather than over-conservatively excluded) and the match-arm bodyCtx's
# own `asInstanceOfBindings -= n` clear (so a NESTED, actually-conflicting arm's OWN reads stay bare)
# are separate functions/cases and cost nothing here. Re-verified uniml/xml, uniml/json, and
# uniml/yaml all still build clean (0 errors).
#
# renderTerm 39512 -> 39524 (+12), continuing the same backlog, 58 -> 55. `scanRefDef(lines, i)
# match { case Some(defn) => … defn.label … }` (`MarkdownBlocks.scala`) — `defn` is bound by a
# `Some(x)` match pattern, so its specific struct type lives in `ctx.paramCtorNames`
# (`renderMatch`'s own bodyCtx case), not `ctx.paramTypes` — the existing precise no-paren-method
# case (`_structZeroArgMethods`) only ever read the latter: `error[E0615]: attempted to take value
# of method label on type RefDef` (a genuine zero-arg METHOD read as a field, since the name-only
# `_zeroArgDefNames` fallback refuses "label"/"destination"/"title" everywhere — common enough
# words to also be genuine fields elsewhere in this corpus). Widened the existing guard's lookup to
# `ctx.paramTypes.get(n).orElse(ctx.paramCtorNames.get(n))` — inline in this arm's own case, the
# growth. Re-verified uniml/xml, uniml/json, and uniml/yaml all still build clean (0 errors).
#
# renderTerm 39524 -> 39668 (+144), continuing the same backlog, 55 -> 54. `lex.lastIndexOf(']')`
# (`MarkdownInlines.scala`'s `linkOrImage`) — `String.lastIndexOf` had NO lowering at all anywhere
# in this backend (unlike `indexOf`, its forward twin): `error[E0599]: no method named lastIndexOf
# found for struct String`. A NEW `renderTerm` arm mirroring the one-arg `indexOf` case exactly
# (same Unicode-safe find-then-UTF-16-count technique, `str::rfind` in place of `str::find`) is the
# growth. Re-verified uniml/xml, uniml/json, and uniml/yaml all still build clean (0 errors).
#
# renderTerm 39668 -> 39736 (+68), continuing the same backlog, 54 -> 52. `content.regionMatches(
# true, i, "www.", 0, 4)` (`MarkdownInlines.scala`'s `autolinkAtWWW`/`autolinkScheme`) —
# `String.regionMatches` (the 5-arg overload) had NO lowering at all: `error[E0599]: no method
# named regionMatches found for struct String`. A NEW `renderTerm` arm routing to a NEW runtime
# helper (`_str_region_matches`, added to `RuntimeModRs` — same Unicode-safe UTF-16-code-unit basis
# and out-of-range-answers-false contract every OTHER indexed String helper in this file already
# uses) is the growth; the helper itself lives in the runtime template, not renderTerm, and costs
# nothing here. Re-verified uniml/xml, uniml/json, and uniml/yaml all still build clean (0 errors).
#
# renderTerm 39736 -> 39740 (+4), continuing the same backlog, 51 -> 50. `out.updated(out.size - 1,
# last.copy(instruction = rewritten))` (`MarkdownBlocks.scala`'s `spliceSwallowedBreaks`) — the
# existing `.updated(index, elem)` case had no RECEIVER-TYPE guard at all and always rendered
# through `insertOwning` (`m2.insert(k, v)`) — correct for a Map's own insert-or-replace-by-KEY
# semantics, but WRONG for a Vec: `Vec::insert` SHIFTS elements rather than replacing one, a silent
# correctness bug whenever the index type happened to already be usize and a compile error here
# since it never is (`error[E0308]: expected usize, found i64`). A genuine Vec receiver now takes an
# INDEX ASSIGNMENT (`m2[k as usize] = v`) instead — one new `if` branch inline in this arm's own
# case, the growth. Re-verified uniml/xml, uniml/json, and uniml/yaml all still build clean (0
# errors).
#
# renderTerm 39740 -> 39848 (+108), continuing the same backlog, 50 -> 47. `MdLine.split(text)`
# (`MarkdownBlocks.scala`'s `parse`, `object MdLine: def split(text: String): Vector[MdLine] = ...`)
# — the one-arg `(s: String).split(sep)` case had NO receiver-type guard at all and matched on the
# bare method name "split" alone, so it fired for this companion-object STATIC call too and
# rendered the bare object reference `MdLine` followed by `.split(...)` as if it were a String:
# `error[E0423]: expected value, found struct MdLine`. The case now steps aside via an inline
# `_objectMembers` lookup (the same map an existing SITE-2 callee-name lookup elsewhere in this
# method already keys off of) whenever `qual` is a bare object name that itself declares a "split"
# member, letting the ordinary object-member call machinery resolve it instead — the added `match`
# guard inline in this arm's own case is the growth. Re-verified uniml/xml, uniml/json, and
# uniml/yaml all still build clean (0 errors).
# renderTerm 39848 -> 39856 (+8), continuing the same backlog, 47 -> 46. `htmlBlockType(t,
# paragraphOpen = true)` (`uniml/markdown`'s `MarkdownBlocks.scala`'s `couldOpenParagraphInterrupt`)
# — the implicit-receiver `self.method(...)` call arm (`ctx.selfMethods`) rendered every argument
# with a bare `renderTerm`, never stripping a `Term.Assign(Term.Name(param), value)` named-argument
# shape down to just `value` the way the ordinary call path already does a few hundred lines down:
# `error[E0425]: cannot find value paragraphOpen in this scope`. Same strip, applied here too — one
# `.map` over the arg list before rendering, the growth. Re-verified uniml/xml, uniml/json, and
# uniml/yaml all still build clean (0 errors).
# renderTerm 39856 -> 40040 (+184), continuing the same backlog, 46 -> 42 (the E0425x4 bucket's
# two Java-interop calls, PLUS two PRE-EXISTING, previously-unrelated `Doc.scala` failures this
# same fix incidentally cleared). `Integer.parseInt(body.substring(1))` (`uniml/markdown`'s
# `MarkdownProjection.scala`'s `resolveEntity`) — the ONE-arg overload of `Integer.parseInt`; only
# the existing TWO-arg (radix) case matched, so this fell through to the generic Apply path and
# rendered the bare type name `Integer` as a value: `error[E0425]`. New inline arm, radix-10
# literal, mirroring the existing two-arg case. `Character.toLowerCase(c)` (`uniml/markdown`'s
# `MarkdownLexer.scala`'s `foldCase`) — the SAME shape, nothing lowered it at all; routed to a NEW
# runtime helper (`_char_to_lowercase`, added to `RuntimeModRs` — costs nothing here, only the
# dispatching arm does) with its own `.0` unwrap (`c` is bound from `s.charAt(i)`, a genuine
# SscChar). Fixing that name resolution then surfaced a THIRD, deeper bug in the SAME expression:
# `foldCase`'s sibling if-arm (bare `{ c }`, next to a branch that resolves to `i64`) had never
# been type-checked before, because the whole expression's unresolved `Character.toLowerCase` arm
# unified with anything — once fixed, rustc could finally see `{ c }` itself was never coerced.
# The existing if/else-arm SscChar coercion only recognized a branch SYNTACTICALLY `.charAt(...)`
# (`isBareCharAt`), not a NAME bound from it — widened to the full `yieldsSscChar` (which already
# tracks exactly this via `Ctx.localSscChars`), a same-cost predicate swap that ALSO cleared two
# more PRE-EXISTING instances of the identical gap in `Doc.scala`'s `numericReferenceValue`,
# masked until now for the same "error-typed arm unifies with anything" reason. Re-verified
# uniml/xml, uniml/json, and uniml/yaml all still build clean (0 errors).
# renderTerm 40040 -> 40788 (+748), continuing the same backlog, 41 -> 39 (three more instances
# of one root cause: `_zeroArgDefNames`'s name-only catch-all refuses a genuine zero-arg METHOD
# read without parens whenever that SAME bare name is ALSO a genuine struct FIELD somewhere else
# in this large corpus — by design, "it would rather miss a call than wrongly rewrite someone
# else's field" — leaving only the two PRECISE, receiver-type-aware fallbacks above it (bare
# `Term.Name` qualifier, keyed off `ctx.paramTypes`/`ctx.paramCtorNames`) to catch what the
# catch-all won't touch). `window.iterator.map(_.raw)` / `window(linesUsed).raw.length`
# (`uniml/markdown`'s `MarkdownBlocks.scala`'s `scanRefDef`, `MdLine.raw` colliding with
# `MarkdownValue.scala`'s `RawHtml(raw: String)`/`HtmlBlock(raw: String)` fields) — TWO new
# precise cases: a `Term.Placeholder` qualifier (rendered as `__p0`, the SAME convention
# `renderVecIterBody`'s own `AnonymousFunction` arm seeds into `ctx.paramTypes`) and an INDEXING
# `Term.Apply` qualifier (element type via `elementTypeOf` on the indexed receiver). And
# `dialectFor(profile).id` (`uniml/markdown`'s `MarkdownDialect.scala`'s `dialectId`, colliding
# with `Source.scala`'s `SourceToken.id: Long`) — a THIRD new precise case for a call-result
# qualifier, keyed off `_returnTypes` instead of `ctx.paramTypes`. All three are the identical
# `dynTraitNameOfRustType`/`_structZeroArgMethods` lookup already established just above, applied
# to a receiver SHAPE neither existing precise case covered. Re-verified uniml/xml, uniml/json,
# and uniml/yaml all still build clean (0 errors).
# renderTerm 40788 -> 40836 (+48), continuing the same backlog, 38 -> 37 (one E0425 compound bug
# fully cleared: `MarkdownEntitiesGenerated.scala`'s `table`/`namedEntities`, four separate
# lowering gaps entangled in one expression chain — `encoded.split(sep).iterator.map { record =>
# … record.indexOf(controlChar.toInt) … }.toMap`). Three of the four fixes live OUTSIDE
# `renderTerm`'s own match (`contentTopVals`'s sibling-topval `loopCtx` accumulation,
# `isKnownVecReceiver`'s and `elementTypeOf`'s new `.split` cases, `isConceptuallyChar`'s new
# `Lit.Char.toInt` case) and cost nothing here. The ONE inline arm is a genuine Vec-of-tuples
# `.toMap` -> `.into_iter().collect::<HashMap<_, _>>()` conversion, the growth — placed AHEAD of
# the generic no-paren refusal a few lines below it (not after, where a first attempt put it and
# a real `cargo build` immediately proved dead: widening `isKnownVecReceiver` to recognize
# `.split` made that SAME receiver satisfy the refusal's own guard too, and match order means
# whichever comes first wins). Re-verified uniml/xml, uniml/json, and uniml/yaml all still build
# clean (0 errors).
# renderTerm 40836 -> 41252 (+416), continuing the same backlog, 35 -> next (E0599x2: two of the
# ten). `trimmed.filter(c => …)` (`uniml/markdown`'s `MarkdownBlocks.scala`'s `isThematicBreak`)
# and `raw.map(c => …)` (`MarkdownProjection.scala`'s `codeSpanValue`) — `.filter`/`.map` on a
# STRING receiver had NO dedicated lowering at all anywhere in this backend (unlike their siblings
# `.forall`/`.exists`/`.count`, which already special-case a String receiver a few hundred lines
# up): the generic Vec-shaped `.map`/`.filter` cases have no receiver-type guard, so a String
# reached `renderVecIterBody`'s `.iter().cloned()` shape and `String` has no `.iter()`:
# `error[E0599]`. Two new inline arms, `.chars()` + this lane's i64-code-point convention (same
# idiom `.forall`/`.exists` already use), placed AHEAD of the generic Vec cases for the identical
# match-ordering reason the `.toMap` fix just needed — the growth. Re-verified uniml/xml,
# uniml/json, and uniml/yaml all still build clean (0 errors).
# renderTerm 41252 -> 41352 (+100), continuing the same backlog. `spaced.head`/`spaced.last`
# (`uniml/markdown`'s `MarkdownProjection.scala`'s `codeSpanValue`, `spaced` now a genuine String
# local after the `.map`-on-String fix just above) — `yieldsSscChar`'s own `isStringReceiverChain`
# case already knew this shape needed the `.0`-unwrap coercion at a CONSUMER position, but nothing
# ever LOWERED the access itself: `error[E0609]: no field head on type String`. Two new inline
# arms routing to `crate::runtime::_str_char_at` at index `0` / `length - 1`, mirroring every
# other indexed String read on this lane — the growth. Also needed: `"map"` added to
# `collectLocalStrings`'s `StringPreserving` set (a separate helper, costs nothing here) — without
# it `spaced` never joined `ctx.localStrings` and this fix's own guard never fired. Re-verified
# uniml/xml, uniml/json, and uniml/yaml all still build clean (0 errors).
# renderTerm 41352 -> 41888 (+536), continuing the same backlog. `namedEntities.getOrElse(body,
# lex)` (`uniml/markdown`'s `MarkdownProjection.scala`'s `decodeEntity`; `namedEntities`, a
# zero-arg def returning a Map) — Scala elides `()` on a NILADIC def, but nothing ever rendered
# the implied call: the bare reference fell through to the ordinary bare-name fallback (the
# FUNCTION ITEM, uncalled), `error[E0599]: no method named getOrElse found for fn item fn() ->
# HashMap<...> {namedEntities}`. A GENERAL fix in `bareNameOrNiladicCtorTail` (call ANY bare name
# matching a known zero-arg def) was tried first and reverted within the same round — a real
# `cargo build` immediately turned 29 errors into 38, because that fallback is shared by every
# unresolved bare name in the file, including local vals that merely SHARE a name with some
# unrelated zero-arg def elsewhere in this large corpus (`let frame = stack[...].clone()` next to
# `sealed trait Container: def frame: String`). Landed instead as two narrow, call-site-local
# `renderTerm` cases (`.get`/`.getOrElse`, both gated on the qualifier being a bare name that IS a
# known zero-arg def whose OWN declared return type is a Map) — the growth. Re-verified
# uniml/xml, uniml/json, and uniml/yaml all still build clean (0 errors).
# renderTerm 41888 -> 41956 (+68), continuing the same backlog. `itemEdges.indexWhere { case
# UniEdge(_, UniNode.Token(t)) => t.kind == MdKind.Blank; case _ => false }` (`uniml/markdown`'s
# `MarkdownProjection.scala`'s `listLoose`) — Rust's `Vec` has no `indexWhere` under any spelling,
# and the generic method-call fallback re-emitted the Scala name verbatim (`error[E0599]`, latent
# behind the same closure's `item.edges` E0609 until the zipWithIndex-tuple param threading in the
# same commit fixed that). One new `renderTerm` dispatch case (the Vec-receiver twin of the
# String `indexWhere` case directly above it, `Iterator::position` + the `-1` sentinel tail) —
# the growth; the rendering itself lives in `renderVecIterBody`'s own dispatch tables (separate
# helper, no cost here). Re-verified uniml/xml, uniml/json, and uniml/yaml all still build clean
# (0 errors).
# renderTerm 41956 -> 41960 (+4), the LAST error of the backlog: `UniML.parse(source,
# ConfiguredMarkdownDialect(profile, limits), limits.core)` (`uniml/markdown`'s
# `MarkdownDialect.scala`'s `Markdown.parse`) — a constructed implementor into a `Rc<dyn Trait>`
# parameter needs the explicit `Rc::new` Scala's implicit trait upcast never spells
# (`error[E0308]: expected Rc<dyn DialectAdapter>, found ConfiguredMarkdownDialect`). One new
# coercion arm in the call-argument block, its whole predicate factored to a top-level helper
# (`needsRcDynWrap` — which reads the RAW declared param type off `_ownedDefBodies`, never the
# bare-name want-list this corpus's four `def parse` shadow) precisely to keep the inline cost at
# these four bytecodes. The sibling per-arm `Rc::new` wrap for a dyn-trait-returning MATCH landed
# in `renderDef`/`renderMatch` (separate methods, no cost here). With this the full uniml/markdown
# merged corpus reaches 0 real `cargo build` errors, from 155 at the start of the run.
# Re-verified uniml/xml, uniml/json, and uniml/yaml all still build clean (0 errors).
# genExpr 25328 -> 25384 (+56), from the fix for js-codegen-does-not-resolve-a-sibling-zero-arg-def-
# from-another-method (v1/runtime/backend/js/BUGS.md): one new Term.Name arm dispatches a bare
# reference to a class's own sibling parenless `def` on `_self` instead of emitting it as a
# free-standing (and unbound) identifier. RAISED, NOT REVERTED, on the same terms as every entry
# above: genExpr is already 3.17x the 8000-bytecode limit and has not been JIT-compiled for a long
# time, so +56 is drift, not a new performance hazard — the arm was necessary to fix a real
# ReferenceError, not something to extract elsewhere (this method's cost is dispatch-arm COUNT,
# same lesson as renderTerm's Ctx.copy() sites, and this is one new arm, not a restructuring).
# genExpr 25384 -> 25468 (+84), from the fix for js-codegen-drops-generic-typeclass-resolution-when-
# multiple-instances-exist (v1/runtime/backend/js/BUGS.md): the summon[TC[T]] arm's fallback for an
# explicit user given now emits `_ssc_givens[key] ?? _resolveGiven(key)` instead of a bare identifier
# spelled like the registry key, which was never bound anywhere and threw ReferenceError. RAISED, NOT
# REVERTED, same terms as above: drift on a method already 3.18x the limit and never JIT-compiled,
# in exchange for a real ReferenceError fix; not a restructuring candidate for the same arm-count
# reason as every prior entry.
# genExpr 25468 -> 25540 (+72), from the fix for js-codegen-map-dot-empty-has-no-companion-handling
# (v1/runtime/backend/js/BUGS.md): one new Term.Select arm lowers `Map.empty` to `_Map()` — a bare
# `Map` in receiver position otherwise reaches JavaScript's own built-in Map class and the dispatch
# throws `Method not found: empty on <function>`. RAISED, NOT REVERTED, same terms as above: one
# necessary arm on a method whose cost is arm count, already 3.19x the never-JIT'd limit.
# (The three genExpr entries above landed on main in parallel with this branch's renderTerm work —
# merged here by UNION: both sides' comment histories kept, FROZEN takes each side's own method's
# final value — genExpr 25540 from main, renderTerm 41960 from feature/uniml-dialect-modules.)
# renderTerm 41960 -> 42564 (+604), from the SELF-APPEND lowering: `xs = xs :+ x` now emits
# `xs.push(x)` instead of `[&(xs)[..], &[x][..]].concat()`. This is the one entry here bought with
# a COMPLEXITY measurement rather than an error message: the concat form allocates a new Vec and
# clones every element already in it on EVERY append, so a parser appending one token at a time
# (uniml/markdown's `MarkdownBlocks.parse`) paid O(n) per token and O(n²) per file — 2 KB 0.015 s
# → 32 KB 2.768 s, ~3.8× per doubling, with a `sample` profile that was nothing but String/Vec
# clone + malloc/free. 96 of the emitted crate's 117 concat sites became pushes. The arm is inline
# in renderTerm's own match because it has to see BOTH sides of the assignment (the lhs name and
# the `:+` receiver must be the same); its two guards — the appended element must not read the
# collection (E0502), and the receiver must be a known Vec (Scala's `:+` is also defined on
# String) — are what keep it a rewrite of one shape rather than of every `:+`. RAISED, NOT
# REVERTED, on the same terms as every entry above: renderTerm is 5.25× a limit it has not been
# under for a long time, and this is a real asymptotic fix, not a restructuring candidate.
# renderTerm 42564 -> 42576 (+12), from giving `(s: String).toVector` a lowering that COMPILES.
# The pre-existing String arm emitted `Vec<char>` (code POINTS, disagreeing with `charAt`'s code
# UNITS on every surrogate) and its receiver test was purely syntactic, so a parameter declared
# `String` — the shape the arm exists for — missed it and fell to the generic branch, which
# emitted `String::into_iter()`: a method that does not exist. Now `encode_utf16()` collected as
# `Vec<i64>`, matching how a char value travels everywhere else on this lane. Twelve bytes: the
# arm was already there, this widens its guard by one disjunct (`isDeclaredStringName`, a new
# top-level helper that costs nothing here) and changes the emitted string. RAISED, NOT REVERTED,
# same terms as every entry above.
# renderTerm 42576 -> 42828 (+252), from the SELF-EXTEND lowering: `xs = xs ++ ys` now emits
# `xs.extend(ys.iter().cloned())` instead of `[&(xs)[..], &(ys)[..]].concat()`. The `++` twin of
# the self-append entry above, and the one that sat in `UniML_parse`'s OWN per-token loops — eight
# sites on `tokens`, `roots` and `diagnostics`, each copying the whole accumulated vector once per
# TOKEN of the document. Measured on a 256 KB markdown parse: 61.8 s -> 14.4 s. Inline for the same
# reason the self-append arm is (it must see both sides of the assignment); the guard is shared and
# was itself fixed here — `readsName` counted a FIELD spelled like the variable
# (`stepped.batch.diagnostics`) as a read of it, so the guard silently refused the very sites it
# most wanted; `readsNameAsValue` (a new top-level helper, no cost here) walks only a Select's
# qualifier. RAISED, NOT REVERTED, same terms as every entry above: a real asymptotic fix on a
# method already 5.35x a limit it has long been over.
# renderTerm 42828 -> 42864 (+36), from taking a class method's read-only `Vec` parameter by
# SHARED REFERENCE. Measured with a sampling allocator, which is the instrument this needed — a
# leaf profile cannot see clones inlined into generated code: 93% of a 128 KB markdown parse's
# 70 M allocations came from `MarkdownBlocks::parse::dispatchLeaf` and `MarkdownBlocks::scanRefDef`,
# and both grew x16 when the input grew x4 (quadratic), while `tokenize` beside them grew x3.8.
# The cause was `__self.scanRefDef((*lines).clone(), …)`: the whole document's line vector copied
# once per LINE. Allocations 69.6M -> 26.7M, bytes 3.1 GB -> 1.15 GB, time 2.11 s -> 1.41 s.
# The +36 here is the implicit-receiver call arm learning to pass a borrow; the rest of the change
# lives in renderParams, the def's own ctx and the `self.`-prefixed call arm (separate methods, no
# cost here). RAISED, NOT REVERTED, same terms as every entry above.
read -r -d '' FROZEN <<'EOF' || true
28036 scalascript.interpreter.ActorScheduler::handleActorOp
25540 scalascript.codegen.JsGen::genExpr
42864 scalascript.codegen.rust.RustCodeWalk$::renderTerm
11387 scalascript.frontend.custom.StaticJsEmitter$Ctx::compile
10670 scalascript.frontend.solid.SolidEmitter$Ctx::compile
EOF

# ── self-test: a detector only ever observed staying quiet is not a detector ─────────────────
# Same reasoning as v2-jit-size.sh: prove the census still measures before trusting a clean report.
if [[ "${1:-}" == "--self-test" ]]; then
  command -v javac >/dev/null || { echo "self-test needs javac" >&2; exit 2; }
  TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-v1-jit-selftest.XXXXXX")"
  trap 'rm -rf "$TMP"' EXIT
  gen() { { printf 'public class %s { public static int f(int x) {\n' "$1"
            for ((i = 0; i < $2; i++)); do printf '    x += 1;\n'; done
            printf '    return x; } }\n'; } > "$TMP/$1.java"
          javac -d "$TMP/classes-$1" "$TMP/$1.java"; }
  gen Huge 5000; gen Small 10
  [[ -n "$("$CENSUS" "$TMP/classes-Huge" "$LIMIT")" ]] \
    || { echo "SELF-TEST FAIL: census stayed quiet on a method built to exceed $LIMIT" >&2; exit 1; }
  [[ -z "$("$CENSUS" "$TMP/classes-Small" "$LIMIT")" ]] \
    || { echo "SELF-TEST FAIL: census flagged a 10-statement method" >&2; exit 1; }

  # An EMPTY census must flow on to the frozen-list checks, not abort the script.
  #
  # This is the defect that made this gate useless: the observed-set pipeline ended in
  # `grep -E '^[0-9]+ '`, `grep` exits 1 when nothing matches, and `set -euo pipefail` turned that
  # into rc=1 with EMPTY stderr — a failure with no message, indistinguishable from a real one.
  # It fired exactly when the census found nothing, which is the state a mis-scoped scan produces.
  # Asserted on the pipeline's own EXIT STATUS, not by wrapping it in a subshell and hoping `set -e`
  # fires. It does not: bash suppresses `-e` inside a compound command whose status is tested, so
  # `( set -e; … ) || fail` passes whatever happens. The first version of this assertion was written
  # that way, and re-running it against a copy with the `|| true` deliberately REMOVED still
  # reported PASS — it was a check that could not fail. Take the status directly instead.
  set +e
  ( set -o pipefail; printf 'no numbers here\n' | { grep -E '^[0-9]+ ' || true; } | sort -u >/dev/null )
  empty_rc=$?
  set -e
  [[ $empty_rc -eq 0 ]] \
    || { echo "SELF-TEST FAIL: the empty-census pipeline exits $empty_rc instead of 0 —" >&2
         echo "  under 'set -euo pipefail' that aborts this gate with NO message at all." >&2
         echo "  The grep needs its '|| true'. (Verified to fail when that is removed.)" >&2; exit 1; }

  # The SIZE PREFILTER must not be able to hide an over-limit method. The property it rests on is
  # that a method's Code attribute lives inside the class file, so `bytecodes <= file size` — assert
  # it on the generated over-limit class rather than trusting the reasoning, because the filter is
  # the one place where making the gate fast could quietly make it blind.
  huge_class="$(find "$TMP/classes-Huge" -name 'Huge.class' | head -1)"
  huge_bytes="$(wc -c < "$huge_class" | tr -d ' ')"
  [[ "$huge_bytes" -ge "$LIMIT" ]] \
    || { echo "SELF-TEST FAIL: a class holding a >$LIMIT-bytecode method is only $huge_bytes bytes," >&2
         echo "  so the 'file smaller than the limit cannot hold an over-limit method' prefilter" >&2
         echo "  would DISCARD it and this gate would go green while blind." >&2; exit 1; }

  echo "v1-jit-size self-test: PASS (census detects over-limit, stays quiet under it,"
  echo "                            an empty census does not abort the run,"
  echo "                            and a class holding an over-limit method survives the size filter)"
  # FALL THROUGH to the census, matching v2-jit-size.sh, whose usage line says
  # "assert BOTH verdicts, then check the artifacts". One CI invocation must do both: wiring only
  # `--self-test` would run the detector's self-check and never look at the tree — the exact shape
  # of uselessness this gate was already in.
fi

# ── WHAT IS SCANNED: the SHIPPED JARS, not `v1/**/target/*/classes` ──────────────────────────────
#
# This gate scanned `target/*/classes` and, measured 2026-08-12, that made it BLIND in exactly the
# state its own header tells you to be in. `install.sh --dev` restores `bin/lib` from the toolchain
# cache when the inputs digest matches, and then sbt never runs — a fresh worktree has NO
# `v1/**/target/scala-3*/classes` at all. What it does have is one unrelated Scala 2.12 directory
# from the sbt plugin, which was enough to get past the "no classes found" guard below.
#
# The jars are also the RIGHT artifact on the merits: `bin/lib/jars/*.jar` is what `bin/ssc-tools`
# puts on its classpath, so it is the bytecode that actually runs. `target/classes` is an
# intermediate that may be stale, absent, or from another build. Verified identical where both
# exist: EvalRuntime 15428, dispatchList 14696, dispatchString 10013 from the jar and from a fresh
# `backendInterpreter/compile`.
#
# THIRD-PARTY JARS ARE EXCLUDED BY NAME. `bin/lib/jars` also holds scalameta, postgresql, h2 and
# ujson, each with its own over-limit methods that are none of our business and that we cannot fix.
# Only `scalascript-*.jar` is ours. The v2 tree has its own gate (v2-jit-size.sh), so `-v2-` jars
# are left to it rather than double-reported here.
jars=()
while IFS= read -r j; do jars+=("$j"); done < <(
  # -prune on the BASENAME, not the path. `grep -v -- '-v2-'` matched the whole
  # line, so a worktree whose directory name contained `-v2-` — e.g.
  # scalascript-wt-v2-mcp-mrtr-surface — filtered out every jar it had just
  # built and the gate exited 2 with "no shipped scalascript jars found". A
  # correct build, reported as a missing one, in a check that then measures
  # nothing. `-not -name` asks the question about the file, which is what was
  # meant all along.
  find "$ROOT/bin/lib/jars" -name 'scalascript-*.jar' -not -name '*-v2-*' 2>/dev/null | sort)
if [[ ${#jars[@]} -eq 0 ]]; then
  echo "v1-jit-size: no shipped scalascript jars found — build first (bash install.sh --dev)" >&2
  echo "  looked for: bin/lib/jars/scalascript-*.jar" >&2
  exit 2
fi
echo "v1-jit-size: scanning ${#jars[@]} shipped jar(s), limit $LIMIT"

# ── PLUGIN BYTECODE SHIPS NESTED, AND NO CENSUS HAD EVER LOOKED AT IT ────────────────────────────
#
# A v1 compiler plugin ships as `bin/lib/compiler/plugins/<name>.sscpkg` — a zip whose payload is
# `intrinsics/<name>.jar`, a jar INSIDE a zip. All 27 plugins are built that way and none of them
# had ever been censused by anything.
#
# This is not a completeness nicety, it is what keeps the disappeared-check honest. `handleActorOp`
# (28036 bytecodes, the largest offender on the list) lives in `actors-plugin.sscpkg`. Scanning only
# the flat jars made the gate report it as "no longer over the limit — DELETE it from FROZEN", which
# is FALSE: the method is alive, unchanged, and still shipping. Following that instruction would
# have dropped the biggest offender in the tree out of the census permanently, and the gate would
# have gone green doing it.
#
# A frozen entry that a scan cannot see is indistinguishable from one that was fixed. The
# disappeared-check is only safe when coverage is complete, so coverage comes first.
pkgtmp="$(mktemp -d "${TMPDIR:-/tmp}/v1jit-pkg.XXXXXX")"
classes="$(mktemp -d "${TMPDIR:-/tmp}/v1jit-cls.XXXXXX")"
observed="$(mktemp)"
# ${TMP:-} too: --self-test now falls through to here, and a second `trap ... EXIT` REPLACES the
# first, so the self-test's own scratch dir would leak on every CI run.
trap 'rm -rf "$pkgtmp" "$classes" "$observed" ${TMP:+"$TMP"}' EXIT

# Everything is unpacked into ONE tree and censused ONCE. Per-jar invocation cost 68 s of an 85 s
# run — 56 unzip+javap pipelines instead of one. Same 8 methods, same sizes, 53 s.
for j in "${jars[@]}"; do unzip -q -o "$j" '*.class' -d "$classes" 2>/dev/null || true; done
npkg=0
while IFS= read -r p; do
  d="$pkgtmp/$(basename "${p%.sscpkg}")"
  unzip -q -o "$p" 'intrinsics/*.jar' -d "$d" 2>/dev/null || continue
  while IFS= read -r nj; do
    unzip -q -o "$nj" '*.class' -d "$classes" 2>/dev/null || true
    npkg=$((npkg + 1))
  done < <(find "$d" -name '*.jar' 2>/dev/null)
done < <(find "$ROOT/bin/lib/compiler/plugins" -name '*.sscpkg' 2>/dev/null | sort)
echo "v1-jit-size: plus $npkg nested plugin jar(s) from .sscpkg payloads"

# ── SIZE PREFILTER, and it is exact rather than a heuristic ──────────────────────────────────────
#
# A method's Code attribute is stored INSIDE the class file, so its length can never exceed the
# file's own size: a `.class` smaller than $LIMIT bytes cannot hold a method of $LIMIT bytecodes.
# Filtering on that is therefore lossless, not a sampling trade — and it takes the census from 5650
# class files to 563, and from 50 s to 12 s, with byte-identical output on all 8 known methods.
#
# Derived from $LIMIT rather than written as a number, so raising the limit cannot silently make the
# filter too aggressive. The self-test asserts the property directly on a generated over-limit class.
big="$(mktemp -d "${TMPDIR:-/tmp}/v1jit-big.XXXXXX")"
trap 'rm -rf "$pkgtmp" "$classes" "$big" "$observed" ${TMP:+"$TMP"}' EXIT
while IFS= read -r -d '' f; do
  rel="${f#$classes/}"; mkdir -p "$big/$(dirname "$rel")"; cp "$f" "$big/$rel"
done < <(find "$classes" -name '*.class' -size +$((LIMIT - 1))c -print0 2>/dev/null)
echo "v1-jit-size: $(find "$big" -name '*.class' | wc -l | tr -d ' ') of $(find "$classes" -name '*.class' | wc -l | tr -d ' ') class files are large enough to hold an over-limit method"

# `|| true` on the grep, and it is load-bearing: `grep` exits 1 on ZERO matches, and under
# `set -euo pipefail` that killed this script with rc=1 and an EMPTY stderr — a silent failure
# indistinguishable from a real one, and impossible to diagnose. It fired whenever the census came
# back empty, which is precisely the blind state described above. An empty census must reach the
# "frozen method disappeared" check below and be reported there, not abort the run.
"$CENSUS" "$big" "$LIMIT" 2>/dev/null \
  | sed -E 's/^ *([0-9]+) +([A-Za-z0-9_.$]+) :: .*[ (]([A-Za-z0-9_$]+)\(.*/\1 \2::\3/' \
  | { grep -E '^[0-9]+ ' || true; } | sort -u > "$observed"

fail=0
declare -A frozen_size=()
while read -r size name; do [[ -n "${name:-}" ]] && frozen_size["$name"]="$size"; done <<< "$FROZEN"

# NEW offenders, and frozen ones that GREW
while read -r size name; do
  [[ -n "${name:-}" ]] || continue
  if [[ -z "${frozen_size[$name]+x}" ]]; then
    echo "FAIL  NEW method over HugeMethodLimit — it will NEVER be JIT-compiled:" >&2
    echo "        $size  $name" >&2
    echo "        Split it, or add it to FROZEN with a measured reason in the commit." >&2
    fail=1
  elif (( size > ${frozen_size[$name]} )); then
    echo "FAIL  frozen method GREW: $name  ${frozen_size[$name]} -> $size" >&2
    fail=1
  fi
done < "$observed"

# Frozen entries that no longer appear — the exemption expired, shrink the list
while read -r size name; do
  [[ -n "${name:-}" ]] || continue
  grep -qE " ${name//$/\\$}$" "$observed" \
    || { echo "FAIL  frozen method is not in the census: $name" >&2
         echo "        Either it was fixed — then DELETE it from FROZEN, an exemption that outlives" >&2
         echo "        its need is the same rot as a stale known-red — OR THE SCAN NO LONGER REACHES" >&2
         echo "        IT, in which case deleting it drops a live offender from the census forever." >&2
         echo "        CHECK THE SOURCE BEFORE DELETING: grep for the method name in v1/." >&2
         echo "        Measured 2026-08-12: handleActorOp reported exactly this while alive and" >&2
         echo "        unchanged at 28036 — its class ships in a jar NESTED inside a .sscpkg, which" >&2
         echo "        the scan did not open. That is a coverage hole, not a fix." >&2
         fail=1; }
done <<< "$FROZEN"

if [[ "$fail" -ne 0 ]]; then
  echo "" >&2
  echo "v1-jit-size: FAIL" >&2
  exit 1
fi
echo "v1-jit-size: PASS ($(wc -l < "$observed" | tr -d ' ') known over-limit method(s), none new, none grown)"
