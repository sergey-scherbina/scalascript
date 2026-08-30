# JVM backend (`jvm` lane) — bugs

Scope: defects whose FIX goes in `v1/runtime/backend/jvm/`. Layout and routing rules:
`specs/work-tracking-layout.md`. Entry format (the header is parsed, the prose is not):
`specs/bugs-index.md`. Query with `scripts/bugs-report --module v1/runtime/backend/jvm`, never by
grepping for status.

Newest first.

## jvm-gen-emits-flatmap-on-an-unconstrained-generic-type-param — a `Monad[F]`-based extension call on `F[Acc]` transpiles to real Scala with no evidence `F` supports it

<!-- status: fixed
     lane: jvm
     kind: bug
     area: codegen
     gate: tests/conformance/std-aggregator.ssc (known-red jvm)
     reported-by: claude-code
     reported-at: 2026-08-30
     fixed-in: 042f6d1ef
     confirmed: yes -->

Found landing `std/aggregator.ssc`'s `runEffAggregator` (`specs/aggregation-algebra.md` §11.1):

```scalascript
def runEffAggregator[F[_], In, Acc, Out](
    xs: List[In], agg: EffAggregator[F, In, Acc, Out], m: Monad[F]
): F[Out] =
  val accF: F[Acc] = xs.foldLeft(m.pure(agg.monoid.empty)) { (accF, in) =>
    accF.flatMap(acc => agg.prepare(in).flatMap(prepared => m.pure(agg.monoid.combine(acc, prepared))))
  }
  accF.flatMap(agg.present)
```

`flatMap` is declared as an `extension` method on `Monad[F]`'s `given` instance
(`std/functor-applicative-monad.ssc`), reached here via the VALUE parameter `m: Monad[F]`, not a
context bound — `int`/`native` both resolve `accF.flatMap(...)` correctly against whichever
`Monad[F]` instance `m` holds at the call site. `run-jvm` (JvmGen: transpile to real Scala 3 source,
compile with scala-cli) instead emits `F` as a completely bare, unconstrained type parameter and
calls `.flatMap` on it directly — real Scala 3 rightly refuses:

```
value flatMap is not a member of F[Acc]
where:    F is a type in method runEffAggregator with bounds <: [_] =>> Any
```

The generated Scala never threaded `m`'s extension method into scope for `F` — it needed
something like `m.flatMap(accF)(...)` (calling the value's own method) or a proper Scala `given`
conversion, not a bare `accF.flatMap(...)` on an unconstrained `F`. Repro:
`bin/ssc-tools run-jvm tests/conformance/std-aggregator.ssc` (the file's `known-red: jvm` entry
named this bug); `bin/ssc-tools run --v1` and the default native lane both run the same source
correctly. Not specific to `Monad`/`flatMap` — the mechanism applies to any extension method
reached via a value-level (not context-bound) typeclass parameter on a higher-kinded generic.

**FIXED** by a new whole-source splice pass, `JvmGen.threadTypeclassGivensInSource` (same
parse→splice mechanism as `fuseLazyListInSource`, chained at both source-assembly sites): for
every emitted def whose type params include a higher-kinded one (`F[_]`) and every value param
whose declared type is a SINGLE-argument application of a plain name to exactly that HK param
(`m: Monad[F]` — the classic typeclass-dictionary shape; `agg: EffAggregator[F, In, Acc, Out]`
deliberately does not match), the body is wrapped as `{ given Monad[F] = m; <body> }`. Real
Scala 3 then resolves the trait's extension methods through the local given — exactly how the
hand-written equivalent works, and no call-site change is needed since `m` stays an ordinary
positional parameter. Conservative guards: defs whose tparams carry context bounds are skipped
(the bound already supplies an equivalent given — a second would be ambiguous), a def where two
params map to the SAME type is skipped for the same reason, and overlapping (nested-def) splices
keep only the outermost. Verified: a minimal `Monad[F]`-shaped repro now prints `Some(42)` on
`run-jvm`, matching `--v1`; `tests/conformance/std-aggregator.ssc` on `run-jvm` no longer
reports the `value flatMap is not a member of F[Acc]` error — its jvm lane's ONLY remaining
errors are `fmix64`'s 64-bit `Long` literals (the pre-existing, already-tracked `int-width`
non-conformance, present since §6 put `fmix64` in the same module), so the case stays known-red
on `jvm` with its declaration updated to cite that instead.

## jvm-gen-row-payload-helpers-only-exist-for-serving-programs — `fieldsPayload` emits a call to a function it never defines

<!-- status: fixed
     lane: jvm
     kind: bug
     area: codegen
     gate: v1/runtime/backend/interpreter/src/test/scala/scalascript/JvmGenRowsPathTest.scala
     reported-by: claude-code
     reported-at: 2026-08-26
     confirmed: yes
     fixed-in: e1b97aeac -->

**FIXED 2026-08-26 (`e1b97aeac`) — and the heading above is WRONG about what happened.** The helper
IS defined; it is out of SCOPE. See the third-pass section below for the four lines of the generated
file that say so. The fix keeps the extern drop and excepts the one module it is false for, in
`hoistSscImportsIntoObjectStd` where the generated source is in hand — a name already defined at top
level (`serve`, measured as the only one of the 37) is left out, or the import makes it ambiguous.

Measured on the landed tree: `JvmGenRowsPathTest` 2/2 (was 1/2), every `JvmGen*` suite 76/76,
`tests/e2e/smoke-lane-breadth.sh` PASS on all four lanes — the gate that forced the revert of
`4e42a79ce` — conformance 373/373, smoke 114/114.

**One of the five `sbt test` failures the 2026-08-26 dispatch surfaced** — the first time that tier
had a verdict at all. `JvmGenRowsPathTest`'s row "scala-cli executes emitted JVM row payload
rejection" fails, and not on what it is testing:

```
-- [E006] Not Found Error: …
10940 |locally { val _auto: Any = fieldsPayload(List()); … }
      |                           ^^^^^^^^^^^^^
      |                           Not found: fieldsPayload
```

The test asserts that the EMITTED program rejects a bad payload with `fields must be unique
non-empty dotted field paths`. It never gets there: the emitted Scala does not compile.

**WHERE THEY LIVE.** `fieldsPayload`, `fieldPayload` and `wholeRowPayload` are ordinary
`std/ui/primitives.ssc` exports (`extern def fieldsPayload(names: List[String]): Any`, :325) and the
JVM preamble does define them — `JvmGenPreamble.scala:809-811` — but those lines are inside
`serveRuntime`, which `JvmGen.scala:506` emits only `if usesHttpServer`. A program that merely CALLS
one of them, as any program may, gets the call and not the definition.

**A FIX WAS LANDED AND REVERTED THE SAME NIGHT (`4e42a79ce`, reverted here).** Extracting the two
private validators and the three builders into their own `rowPayloadRuntime` DID clear this row —
`JvmGenRowsPathTest` 2 passed 0 failed, every `JvmGen*` class 76 passed 0 failed — and it broke
`tests/e2e/smoke-lane-breadth.sh`, which is in the smoke suite:

```
effects, --lanes jvm:  Not found: scalascript
  7121 |    case name: String => scalascript.frontend.…
```

The `effects` case names none of the three builders and does not serve, so it should not receive
that preamble at all — yet its emitted program carries `_ssc_exactRowPayload` and cannot resolve
`scalascript.frontend`. **Bisected rather than argued**: at `4e42a79ce~1` the gate PASSES, at
`4e42a79ce` it FAILS.

**AND MY FIRST BISECT SAID THE OPPOSITE.** Checking the three codegen files out at the parent inside
a worktree and rebuilding reported the same failure, which is what let the change land. Only a
separate worktree checked out at the parent commit gave the true answer. A partial checkout plus an
incremental rebuild is not a revert.

**⚠️ AN EARLIER NOTE HERE WAS WRONG AND IS CORRECTED.** It said that widening the condition to
`usesHttpServer || blocksUseRowPayload(blocks)` "made it worse — 7 compile errors instead of 1". Six
of those seven were `value swing is not a member of scalascript.frontend` and the javafx twin: this
test builds its classpath from `frontend/{core,custom,swing,javafx}/target/…/classes`, and a FRESH
WORKTREE has not compiled them. They are an artefact of the tree, not of the change. Only one error
was ever real, and the first attempt very likely fixed it too.

**The extraction is still the better shape**, for the reason the first attempt could not settle:
emitting a whole server preamble into a non-serving program is a much larger blast radius than
emitting five definitions. But "it made things worse" was a measurement error — a control run in a
worktree missing four compiled modules — and reverting on it cost a round.

### 2026-08-26, third pass — the heading was WRONG: the definition is emitted, two scopes away

**`def fieldsPayload` IS in the generated file** — line 10882 of it, inside
`object std { object ui { object primitives {` — while the failing call sits at line 10940, at TOP
LEVEL. "emits a call to a function it never defines" was the wrong reading of `Not found`, and every
attempt above was aimed at the wrong question.

Read straight out of the file `scala-cli` was compiling, whose path the error message names — no
instrumentation needed, which is the part worth remembering after the stderr trap recorded above:

```
10678:  import ui.primitives.{View, EventHandler, signal, …, fieldsPayload, …}   ← inside object std
10882:      def fieldsPayload(names: List[String]): Any = …                      ← inside the object
10938:import std.ui.primitives.{Signal, View, EventHandler}                      ← top level
10940:locally { val _auto: Any = fieldsPayload(List()); … }                      ← top level, unbound
```

The test program says `[fieldsPayload](std/ui/primitives.ssc)`, so it DID ask for the name. It was
dropped by the extern filter in `importLinesFor` (`JvmGen.scala`):

```scala
// Drop the module's `extern def` names: the emitter strips those stubs from the module
// object and the host defines them at the script's top level, where they resolve without an
// import.
.filterNot(sp => externHere.contains(sp.name) && sp.alias.isEmpty)
```

**That premise is false for exactly one module.** `JvmRuntimeUiPrimitives.source` is MERGED INTO the
module object (`JvmRuntimeUiPrimitives.scala:6`) rather than emitted at top level, so its members are
reachable only through `std.ui.primitives` — and dropping them from the import leaves nothing that
defines them where the call is.

So `usesHttpServer`, `serveRuntime.length` and "the block is present" never had to fit together: they
were all about the wrong file region. `serveRuntime` was emitted and is fine.

**AND THE EXTRACTION WOULD NOT HAVE HELPED**, which retires the "still the better shape" note above:
those helpers name `scalascript.frontend.RowPayload` in every line, so hoisting them to top level
moves the error from `Not found: fieldsPayload` to `Not found: scalascript` for any program without
that jar — which is precisely the `smoke-lane-breadth` red that forced the revert. The revert was
right; the reason recorded for it was not.

The fix keeps the drop and excepts the one module, against the SAME member list the in-object import
already uses so the two cannot drift.

### 2026-08-26, second attempt — measured further, still not fixed, and the instrument is the trap

Re-applied the extraction and went after the one question the revert left open: **why does the
`effects` case receive this preamble at all?**

| measured | value |
|---|---|
| `usesHttpServer` for `effects` | **true** — it includes `effectOps.nonEmpty`, and that case uses effect operations |
| `blocksUseRowPayload(blocks)` | false |
| `serveRuntime.length` | 157043 — the value is not empty |
| the emitted program | carries `_ssc_exactRowPayload` (3 hits) and **no** `rowDeleteAction` / `rowPostAction` / `RowActionDef` |
| every compile error | inside the extracted block, lines 7119-7135; nothing else in the file references `scalascript.frontend` |

So `usesHttpServer` is true, `serveRuntime` is non-empty and is appended two lines below the block —
and the emitted program contains the block WITHOUT serveRuntime's own definitions. Those three facts
do not fit together, and the reason they do not is the next thing to find.

**⚠️ THE INSTRUMENT IS PART OF THE PROBLEM, which is why this is written down.** `run-jvm` swallows
the generator's stderr: a `System.err.println` in `JvmGen` printed exactly once — from the BUILD, not
from the run — and every later invocation printed nothing while still failing. Half of this session's
readings were of a `.scala-build` directory that had been reused between runs. Anyone continuing
here should dump the generated program to a file from inside `JvmGen` rather than trusting stderr or
a temp directory's freshness.

**Not fixed. The revert stands.**

## jvm-listdir-answers-empty-where-every-other-lane-raises

<!-- status: fixed
     lane: jvm
     area: runtime
     kind: bug
     gate: tests/conformance/std-fs-failure-raises.ssc
     fixed-in: ad918e80b401828d0f066c525158fb10f0abe268
     reported-by: nadia (sibling repo, rozum meeting room: nadia-ucc)
     reported-at: 2026-08-04
     confirmed: no -->

**FIXED 2026-08-07.** `java.io.File.list()` returns NULL when the path is missing or is not a
directory, and this lane mapped that to `Nil`. It now uses `Files.list`, which is what
`specs/std-fs-os.md` has always DOCUMENTED for this lane and what the reference plugin
(`FsIntrinsics`) actually does — so the change makes the implementation match both the majority and
the spec rather than picking a new contract. Parity checked against the reference rather than
assumed: jvm now raises `NoSuchFileException` for a missing path and `NotDirectoryException` for a
file, the same two the int lane raises.

The gate is the `backends:` line of `std-fs-failure-raises`, which gained `jvm` in the same commit —
that case was written with jvm excluded precisely so this edit would be the closure.

`confirmed: no`: nadia has not confirmed it against their own build.

`listDir` on a missing directory returns `List()` on the jvm lane, silently, exit 0. Every other
lane raises: int `NoSuchFileException`, js `ENOENT`, native `RuntimeException`. The same holds for
`listDir` on a FILE — jvm answers `List()`, the others raise `NotDirectoryException` / `ENOTDIR`.

Measured 2026-08-07 while answering `std-fs-failure-contract`; the full four-lane table is in
`specs/std-fs-os.md` §2.1.

**Why this one and not the general "the lanes disagree".** The reporter argued in the same message
AGAINST making `fs` total by default, in these words: *"a `listDir` that answers `[]` for a missing
directory hides a typo, and the caller can no longer tell empty from not-there."* One lane already
does exactly that, undocumented, so a program moved from `int` to `jvm` loses an error it was
relying on and gets an empty loop body instead.

Which side is wrong is a decision, not an obvious bug — `[]` may be the better contract. What is
not defensible is that the lanes differ and nothing says so. Whichever way it goes, the fix is one
lane changing to match the other three or a documented, deliberate exception.

**No gate.** No rostered case exercises `listDir` on a missing path, which is why a silent
divergence survived. A fix should land the §2.1 row as a conformance case across all four lanes.

## jvm-std-import-inside-a-fence-plus-def-main-emits-broken-scala

<!-- status: fixed
     lane: jvm
     area: codegen
     kind: bug
     gate: v1/runtime/backend/interpreter/src/test/scala/scalascript/codegen/JvmGenInFenceImportTest.scala
     fixed-in: 35b01be29 -->

### FIXED 2026-08-13 — the block's SOURCE was emitted verbatim, and the import line came with it

Read off the generated file rather than inferred. `emitBlock` passes a block's source through
UNCHANGED whenever the block needs no rewriting, and `Block.src` was `cb.source` — the raw fence
text, import link and all. The tails of the two generated files, one line each:

```text
def main       …}\n\n[exists](std/fs.ssc)\ndef main(): Unit = println(1)\n\n// entrypoint…\nmain()
top-level stmt …}\n\nlocally { val _auto: Any = println(1); if _auto != () … }
```

**That is why exactly two conditions reproduce it, and the matrix below now has a cause.** An
entrypoint `def main` is the shape with NOTHING to rewrite, so the raw text is emitted as-is; a
top-level statement needs auto-output wrapping, and rebuilding the block from its parsed tree drops
the line as a side effect. A document-level import is not in the fence text at all. The IMPORT was
never the problem — `inlineImports` already inlines it, which was
`js-jvm-codegen-in-fence-imports-not-followed`. What was left behind is the LINE.

**Fix: hand the block the preprocessed source.** `Parser.rewriteInlineImports` replaces the line
with `// list-import: …`, the same rewrite the parser itself runs (`PreprocessorRegistry`, priority
10) before producing `cb.tree` — so the block's text and its tree now agree instead of diverging.
For the single-line form it is an in-place substitution and nothing shifts; a multi-line import span
collapses to one comment line, exactly as it already does for the tree.

**Only this lane.** int, v2 and js all print `1` for the program below — measured, not assumed, so
this is not a two-front pair.

**Gate: `JvmGenInFenceImportTest`**, in the interpreter module's test tree beside the other JvmGen
unit tests. It carries BOTH conditions rather than the failing one, because a fix that only handled
the loud shape would pass a one-row test; it adds the BARE `.ssc` shape, which is where most
programs put the import now that fences are optional; and it asserts on the emitted source MINUS
comment lines, so the rewritten `// list-import:` cannot pass for the wrong reason. Each row asserts
the program SURVIVED as well — a fix that dropped the whole block would satisfy the link assertion
otherwise.

**One row parses the emitted file rather than pattern-matching it**, because the report was scalac
refusing the file and a second junk line of another shape would slip past a regex. Getting that row
right took two corrections worth keeping: what JvmGen emits is a SCRIPT (`object … :` then a
top-level `main()`), so plain `dialects.Scala3` rejects it at `main()` — *illegal start of
definition*, against a generator that was already fixed. `JvmGen.scala:3214` had learned exactly
this before and says so in a comment; the row now uses `withAllowToplevelTerms`, the dialect
scalameta provides for that shape.

Control: with the fix reverted, three rows fail — def-main, bare-file, and the parse row, which
points straight at the offending text:

```text
<emitted>:7109: error: `=>` expected but `(` found
[exists](std/fs.ssc)
        ^
```

scalameta words it differently from scalac's `expression expected but '[' found`, and the line
number is its own (scala-cli wraps the file before compiling), but it is the same line and the same
defect.

A `std/…` import written INSIDE a `scalascript` fence, together with an entrypoint `def main`,
makes the jvm backend emit Scala that does not parse:

    -- [E018] Syntax Error: …/src_generated/main/….scala:7067:82
    7067 |  sys.error("Http effect requires a serve runtime; call runHttp{} or add serve()")
         |                                                                                ^
         |  expression expected but '[' found

Minimal case — no call, the imported name is not even used:

```text
---
name: t
version: 1.0.0
---

# h

​```scalascript
[exists](std/fs.ssc)
def main(): Unit = println(1)
​```
```

**Two conditions, and each alone is green** — measured, alternating, stable over two rounds:

| shape | jvm |
|---|---|
| fence import + `def main` | **E018** |
| fence import + top-level statement | ok |
| `def main`, no import | ok |
| DOCUMENT-level import + `def main` | ok |

So it is neither the import nor the entrypoint: it is the pair, and only when the import is inside
the fence. Reproduced with `std/fs`, `std/os`, `std/json` and `std/process`, so it is not one
module. `@main def m` fails the same way. When the imported name is USED, E006 `Not found: <name>`
cascades on top of the E018.

**Why nothing is red.** Four corpus files combine `def main()` with a `](std/` import, and all three
of the conformance ones declare `backends:` WITHOUT jvm — `[int, js]`, `[int, v2]`, `[int, js]`. The
fourth is `scripts/smoke-ci.ssc`, which is the smoke driver and is not run through this lane. So the
combination exists in the corpus and no rostered case runs it here. `examples/fs-roundtrip.ssc`
passes on jvm and uses top-level statements, which is the shape that works.

Found 2026-08-07 while measuring the `std.fs` failure contract for `std-fs-failure-contract` — the
probe could not be written in its natural form on this lane.

## jvm-package-import-qualifies-the-link-name — the package ROOT could not be imported

<!-- status: fixed
     lane: jvm
     area: codegen
     kind: bug
     gate: tests/e2e/package-keyword-smoke.sh
     fixed-in: 06513400e -->

**FIXED 2026-08-05.** The gate's JVM row is green and its known-red declaration is gone — forced
rather than chosen, since the gate FAILS a declared row that starts passing.

**The heading was too broad and is corrected.** "No module declaring `package:` can be imported" was
wrong: member names always worked. The shape matrix, four lanes against one module declaring
`package: org.example.ui` with an `object Card` and a top-level `def helper` — before / after:

```
link text            int   native  js    jvm
[org]      root      OK    OK      OK    err -> OK
[org as o] root+alias OK   -       err   err -> OK
[Card]     member    OK    OK      OK    OK  (unchanged)
[helper]   member fn OK    OK      OK    OK  (unchanged)
[ui]       inner seg err   OK      OK    err (unchanged — int rejects it too)
[cards]    arbitrary err   OK      OK    err (unchanged — int rejects it too)
```

Only the first two rows were this defect: three lanes agree the package ROOT is importable. The last
two stay errors ON PURPOSE — the interpreter answers `'cards' not found in ./cards.ssc`, so a fix
making them work would be jvm disagreeing with the golden lane in the other direction. The
native/js permissiveness there is `package-root-import-needs-an-exports-entry-on-int` in the root
file.

**Cause, read off the GENERATED Scala rather than inferred.** `object org {` sits at line 7063 of it,
at top level, and the emitted `import org.example.ui.{org}` at 7072 — the object was already in
scope and there was nothing to import. `aliasBlock` builds that line from the markdown link's
bindings, and a link binds the MODULE, whose name here is the package root, not a member of the
package. The fix drops such a binding, one line from the pre-existing `externHere` filter that
handles the identical failure shape for `extern def` names (`value route is not a member of object
std.http`). An ALIASED root emits `val o = org` instead of being dropped, or `[org as o]` — a
surface the interpreter resolves — would leave `o` unbound.

### Original report (superseded 2026-08-05)

Found 2026-08-05 while fixing `native-front-has-no-package-namespace`, by pointing that gate's JVM
row at a RUN (`ssc-tools run-jvm`) instead of at `bin/sscc`, which is the COMPILER and therefore
printed "JVM artifact written to …" without ever compiling the generated Scala.

Three lanes agreed, one did not:

```
--- cards.ssc                          --- consumer.ssc
---                                    ---
name: cards                            name: consumer
package: org.example.ui                ---
---                                    [org](./cards.ssc)
object Card:                           println(org.example.ui.Card.render("hi"))
  def render(t: String): String =
    "ui-card-" + t

int / native / js   ->  ui-card-hi
run-jvm             ->  value org is not a member of object …$_.this.org.example.ui
                        (from a generated `import org.example.ui.{org}`)
```

`JvmGen.scala:2767` emits `import ${targetPkg.mkString(".")}.{$specText}`, where `specText` comes
from `importBindings` — the names bound by the markdown link. For `[org](./cards.ssc)` that binding
is the whole MODULE under the name `org`, not a member of the package, so qualifying it with the
module's own package produces a name that cannot exist. The interpreter handles the same binding
through `SectionRuntime.lookupExport`/`packageMembers`, which look in the package's members AND in
the exported globals.

**Controlled, so this is about `package:` and not about imports.** The identical two files with the
`package:` line deleted (and the call written `Card.render("hi")`) print `ui-card-hi` on `run-jvm`.
Renaming the link to `[cards]` changes only the name in the message — `value cards is not a member
of object … org.example.ui` — so it is not a collision between the link name and the package root.

The gate declared this row KNOWN-RED by slug and would fail if it started passing — which is what
removed the declaration when the fix landed.

## jvm-lane-cannot-compile-a-json-import — 14 type errors before a single line runs
<!-- status: fixed
     lane: jvm
     area: codegen
     kind: bug
     gate: tests/e2e/jvm-json-import-gate.sh
     fixed-in: 6c822d306 -->

**FIXED 2026-08-05.** `[jsonParse](std/json.ssc)` compiles and runs on the jvm lane, and the whole
documented navigable surface behaves **identically to the interpreter**, byte for byte:

```
             a=1  s=hi  miss=[]  wrongType=[]  nested=true  idx=20  oob=[]  round="hi"
int          ✓
jvm          ✓   (was: 14 errors, then 10, then 5, then 0)
```

The gate no longer declares anything: all five cells count, and it **failed the moment the jvm cell
started passing** — which is exactly what the known-red block existed to do, on my own gate.

`JvmGen.jsonHostBridge` emits the bridge after the modules, and only when `__jsonCoreWrap` appears
in the output. Three things had to be true at once, and each was found by trying:

1. **appended, not preambled** — the hooks return `JsonCore*` values whose types live in
   `object std.json.core`, which the fixed preamble cannot name;
2. **extension methods, not a wrapper class** — `JsonValue` is `opaque type = Any` and the module
   declares no accessors, because js and native dispatch them dynamically at runtime. A static lane
   cannot reach a wrapper's methods through an opaque type, which is what the first attempt failed
   on (`value get is not a member of std.json.JsonValue`). Reading the ADT directly from extensions
   also makes `__jsonCoreWrap` the identity;
3. **total, not throwing** — a miss yields Null, a wrong shape yields the type's zero, nothing
   raises. The preamble's older `class JsonValue` does the opposite, which is why it could not
   simply serve this import.

**`std/http.ssc` — 6 errors → 4, and the last four are a DIFFERENT KIND of gap.**

`sessionSetCookie` is fixed the same way, by `JvmGen.httpHostBridge`, and it **delegates rather than
reimplements**: the preamble already carries `_buildSetCookie`, which is
`SessionCookie.toSetCookie` from the shared `scalascript-http-session` module. The native plugin's
own comment is the reason — the signing is HMAC-SHA256 over `SSC_SESSION_SECRET`, and a second
implementation would be a second HMAC scheme that has to agree byte for byte for a cookie to survive
a lane change.

**What is left is not a missing implementation — it is an import that cannot resolve:**

```
import std.http.{route, serve, Response, Request, UploadedFile}
                 ^^^^^  ^^^^^
value route is not a member of object std.http
```

`route` and `serve` are `extern def` in `std/http.ssc`, so the emitter STRIPS them from the module
object, while the host implementations live at the script's TOP LEVEL. The import asks the module
object for names that were deliberately removed from it. **Importing an `extern def` from a module
cannot work on this lane**, and no bridge fixes it: the names resolve fine at top level, it is the
`import std.http.{…}` line that must not name them.

**FIXED 2026-08-05, and `std/http.ssc` now compiles AND RUNS on the jvm lane:**

```
[route, serve, Response, Request](std/http.ssc)     int: registered     jvm: registered
```

`importedExternNames` records, per imported package, the names that module declares `extern def` —
collected in `recordImportMetadata` where the sibling registries already are — and the
import-emission site drops them. They resolve unqualified from the top level, which is where the
host defines them; the import was asking the module object for names deliberately removed from it.
Aliased specs are left alone: `a as b` needs a real member to alias.

**`components-smoke` passes**, having been red through this whole chain, and `upload` and
`validation` with it.

**`middleware-smoke` still fails on jvm, and it is a THIRD kind of gap — REDUCED 2026-08-05 to a
signature mismatch.** 44 errors, and the distinct ones are two:

```
10909 |      next().withHeader("X-Request-Id", id)      value withHeader is not a member of Any
10915 |      resp.withHeader("X-Response-Time-Ms", …)   value withHeader is not a member of Any
```

The declaration and the host disagree about the type:

| | signature |
|---|---|
| `std/http.ssc` (the contract) | `extern def use(fn: (Request, () => Response) => Response)` |
| jvm host (`RestRuntime.scala:721`) | `def use(fn: (Request, () => Any) => Any)` |

`std/middleware.ssc` is unambiguous about which is right — it documents `type Handler = Request =>
Response` and a middleware as `(Request => Response) => (Request => Response)`. **The host signature
is WIDER than the declared contract**, so on a lane with type inference the lambda's `next` infers
`() => Any` and every `.withHeader` under it is unreachable. On js and native nothing notices,
because they dispatch the method dynamically at runtime — **a wider host signature is invisible
until a static lane reads it.**

**The fix is to align them, and the obstacle is named:** the chain is `Any`-typed on purpose one
layer down, because *route handlers* may return a String or anything else and
`_ssc_ui_backend_response` coerces at the end. So narrowing `use` needs an `Any → Response`
coercion at the point `next()` is handed to the middleware, and that coercion does not exist at that
layer today. Handlers returning `Any` and middleware declared `Response` are two different contracts
that currently share one buffer.

**AND THE COERCION HAS A HAZARD, which is why this is a design question and not a patch.** The
final conversion's rules are exact:

```
Response         → as-is
_StreamResponse  → drained into a string body
anything else    → 200, Content-Type: text/plain; charset=utf-8, _show(other)
```

Narrowing `next()` to `Response` means coercing at the handler boundary — and that would **drain a
`_StreamResponse` before the middleware ever sees it**, turning a streaming response into a buffered
one. Silently, and only where a middleware is installed. So the declared contract
(`() => Response`) cannot express what the host actually supports.

**CORRECTION 2026-08-06 — I suggested widening the shared contract, and that was the wrong shape.**
`_StreamResponse` does **not** appear in `std/http.ssc` at all (grep: 0 hits). It is a jvm-host
construct. The shared contract already says what middleware is — `Response` in, `Response` out — and
it is the HOST that invented a wider signature to accommodate its own type. Nothing needs to be
added to the contract.

**What the host says about why, in its own comment** (`RestRuntime.scala:671`):

> Route handler returns Any (Response | _StreamResponse | primitive auto-wrapped) … The wider Any
> return type is what lets MCP transports use `route("GET", path) { req => sse(req) { … } }`:
> `sse()` returns `_StreamResponse`, not `Response`.

So the `Any` is deliberate and load-bearing for **route handlers**. The question this entry actually
poses is narrower and different: **may a MIDDLEWARE see a streaming response?**

- **If no** — which is what the declared contract says — then a `_StreamResponse` should bypass the
  middleware chain, the chain becomes `Response`-typed, `next().withHeader(...)` compiles, and
  nothing is drained. The cost is a behaviour change for anyone whose middleware currently wraps an
  SSE route.
- **If yes**, the contract in `std/http.ssc` is wrong rather than the host, and widening belongs
  there — but then it must name a concept the other three lanes can honour, not a jvm type.

**ATTEMPTED 2026-08-06 — and the contract answer is right, but it is BLOCKED by something else.**

The stream worry turned out not to exist: `std/http.ssc` declares `sse(req)(block): Response`, so a
stream already IS a `Response` at the contract level and `StreamResponse` is only the host's second
representation of it. Nothing needs draining or bypassing — the two representations just had no
common type. So I gave them one (`sealed trait HttpResponse` in `HttpModel.scala`, both extending
it) and typed all THREE middleware chains — `HttpDispatchLoop`, `RestRuntime`, `ProxyRuntime` —
plus the preamble's own. It compiles.

**The coercion equality was checked, not assumed:** the old `case other` arm wrote
`Response(200, text/plain, String.valueOf(other))`, and coercing at the handler boundary with the
same rule makes the dispatcher take its `case resp: Response` arm and emit byte-identical output.
That is why moving the conversion earlier is safe.

**What blocks it is a SECOND `Response` type.** `middleware-smoke` went 44 → 42 errors and the shape
changed to:

```
10974 |  req => Response.json("echo " + req.path)
      |     Found:    Response                  (class in the preamble)
      |     Required: std.http.Response²        (class in object http)
```

The jvm lane has **two `Response` classes in scope**: the one inlined from
`runtime-server-common/HttpModel.scala`, and the one `std/http.ssc` declares and the emitter puts in
`object std.http`. `Response.json(...)` is an *extension on the preamble companion*
(`JvmGenPreamble` says so in its own comment, because "the case class lives in a different
compilation unit and Scala doesn't let two modules merge a companion object"), so it yields the
preamble's type where the module's is required. **While every chain was `Any`, the two could never
meet in one constraint.** Typing it makes them meet.

**It does not break output today** — measured: a module `Response(201, …, "body-here")` reaches the
wire as `body-here|201` on the jvm lane. So this is a latent duplication, not a live defect, and it
is the same family as the two `jsonParse`/`JsonValue` implementations recorded above.

**Reverted rather than half-landed.** The typing is correct and ready to re-apply the moment there
is ONE `Response` on this lane; landing it now would trade a compile error nobody sees for 42 that
everybody does.

**THE TWO `Response`s ARE DELIBERATE, NOT SLOPPY — read before removing either.** They differ in
shape, and `std/http.ssc` explains why in its own comment:

| | fields |
|---|---|
| `std/http.ssc` (portable) | `Response(status, headers, body)` — three, all required |
| preamble (`HttpModel.scala`) | `Response(status = 200, headers = Map.empty, body = "", setSession = None)` |

The portable one is three fields because that IS the native lane's wire format (`the native Response
is a three-field DataV and stays one`), and `withHeader` moved into ScalaScript precisely because
native had none and served the not-implemented sentinel as a 200 body. The preamble's fourth field
exists because the jvm lane also runs a server-side session store with SSID rotation, which the
native lane does not have — so v1 defers session handling to the dispatch loop while the portable
module computes it at the Response.

**Which one actually runs, measured rather than assumed.** Both are emitted — the preamble's at the
top level, the module's inside `object std.http` — and the emitted handler is unqualified
`Response(200, Map(), "hi")` with both in scope. The PREAMBLE's wins: a module-shaped
`Response(201, …, "body-here")` reaches the wire as `body-here|201`, which only the dispatcher's
`case resp: Response` arm produces. So the module's class is emitted and, for construction,
effectively dead — it binds only where a type is named explicitly, which is exactly what typing the
middleware chain started doing.

**DONE 2026-08-06 — `JvmGen.dropHostShadowedTypeImports`.** An `import std.X.{…}` no longer names a
type the preamble already defines at top level, on the same reasoning as the `extern def` filter:
the host's definition resolves without an import, and naming it binds the module's second copy
instead. The set is **derived from the emitted preamble**, not listed, so it cannot go stale.
smoke-ci 69/69; `std/json.ssc` and `std/http.ssc` both still compile and run on the lane.

**AND THE MIDDLEWARE TYPING WAS RE-APPLIED ON TOP, to find out whether that was the whole blocker.
It is not — but it is no longer the `Any` problem.** With the supertype in place:

```
before   44 errors, all `not a member of Any`
after     1 × E007 + 40 × E049  — `not a member of Any` is GONE
```

Two further things surfaced, both name collisions rather than type-system problems:

1. Naming the trait `HttpResponse` collided with `java.net.http.HttpResponse`, which the preamble
   imports — 41 `Reference to HttpResponse is ambiguous`. Renamed to `RouteResult`, which is what it
   is and cannot clash.
2. What remains is **`Reference to Request is ambiguous`** — `Request`, like `Response`, is defined
   by the preamble AND imported from `std.http`. The new filter misses that copy: after
   `hoistSscImportsIntoObjectStd` re-imports names inside `object std`, the line reads
   `import http.{…}` with the `std.` prefix dropped, and the filter's pattern requires it.

**DONE 2026-08-06 — `middleware-smoke` is green on INT, JVM and JS**, and with it the last red in
`tests/BUGS.md orphaned-e2e-gates-52`.

It was one regex, as predicted. `hoistSscImportsIntoObjectStd` re-imports names INSIDE `object std`
and drops the prefix while doing it, so the same import appears TWICE in the output —
`import std.http.{…}` at file level and `import http.{…}` two spaces in. The filter was anchored on
`std.`, caught only the first, and that is precisely why `Request` stayed ambiguous after `Response`
stopped being: one copy was filtered, the other was not. `java.`/`javax.`/`scala.`/`_root_.` are now
excluded explicitly rather than left to the predicate — the predicate alone is safe today only
because no top-level class shares those names, and that is a property someone can change.

The chain is typed `RouteResult` in all five places that build one: `HttpDispatchLoop`,
`RestRuntime`, `ProxyRuntime`, the preamble's own, and the trait itself. Coercion happens ONCE, at
the handler boundary, with the rule copied verbatim from the `case other` arm each site used to end
with — so a `Response` built that way takes the dispatcher's `case resp: Response` arm and writes
byte-identically. Nothing is drained: a `StreamResponse` is a `RouteResult` and passes through.

| | before | after |
|---|---|---|
| `middleware-smoke` jvm | 44 errors, all `not a member of Any` | **PASS** |
| smoke-ci | — | 69/69 |
| `components` · `validation` · `upload` · `url-import` | — | all rc=0 |
| `std/json.ssc`, `std/http.ssc` on jvm | — | compile and run |

That is the next step. It is neither an extern nor an import question, and it is not the opaque-type
problem either — this one is a module declaring a narrower world than its host implements, and the
fix is to widen the declaration honestly rather than to narrow the host and lose streaming.

**Measured 2026-08-04.** Five lines, one import, four lanes:

```scalascript
[jsonParse](std/json.ssc)
val v = jsonParse("{\"a\":1}")
println("parsed")
```

| lane | result |
|---|---|
| int | `parsed` |
| native | `parsed` |
| js | `parsed` |
| **jvm** (`ssc-tools run-jvm`) | **14 × `[E007] Type Mismatch`** — the emitted Scala does not typecheck |

**Control, in the same gate:** the same program *without* the json import compiles and runs on the
jvm lane (`control ok`). So this is not "run-jvm is broken today"; it is specifically what the json
core lowers to.

**ROOT CAUSE CORRECTED 2026-08-04, and my first diagnosis was wrong.** I wrote that "the generator
emits the bindings without their element types". It does not: the emitted Scala is a faithful copy
of the source, and `case class JsonCoreOk(value: Any, next: Int)` is what `json-core.ssc:48` says.
`value` is genuinely polymorphic — a codepoint here, a `JsonCoreString`/`Number`/`Array`/`Object`
elsewhere — so `Any` is the correct declaration.

**The real cause is `Cons`.** `json-core.ssc` matches lists as `case Cons(head, tail)`, which is
ssc's spelling. Scala has no `Cons` extractor, so the pretty-printer emits the name verbatim and the
compiler answers `[E189] Not Found: Cons`. Every binder under an unresolved extractor then degrades
to `Any`, which fails again at each use — that is where `Found: (tail : Any)` ×7 comes from. **One
unlowered name produces fourteen errors.** The interpreter has always known it:
`PatternRuntime.scala` special-cases `Cons` by name. This lane never learned it.

Reduced to eight lines, no json:

```scalascript
def size(xs: List[Int], n: Int): Int =
  xs match {
    case Nil => n
    case Cons(_, tail) => size(tail, n + 1)
  }
```

`run-jvm` emits `case Cons(_, tail) =>` verbatim and fails; int, js and native all print `3`.

**PARTIALLY FIXED 2026-08-05, and the earlier note about the failed attempt was wrong on its
reason.** I had guessed the splice arm was "unreachable for pattern nodes, or the sibling `None`/
`Nil` arm is dead too". Instrumenting the pass — the step this entry told the next reader to take —
answered it in one build: **`rewriteActorAstCallsInSource` is never CALLED on this path.** It runs
inside `genUserOnlyWithLineMap`, the bytecode path. `emit-scala` and `run-jvm` go through
`generate` → `genModule`, which applies `fuseLazyListInSource` and nothing else. **The two emit
paths apply different fixups**, so anything repaired in one is unrepaired in the other — including
that `case None()` rewrite.

**What now works.** A new `lowerConsPatternsInSource` pass, wired into BOTH paths behind a
`contains("Cons(")` guard so the common emit does not pay for a parse:

```
def size(xs: List[Int], n: Int): Int =
  xs match { case Nil => n; case Cons(_, tail) => size(tail, n + 1) }

run-jvm  →  3        (was [E189] Not Found: Cons)
```

**THE `Cons` HALF IS NOW FIXED, 2026-08-05 — including imported modules.** The slice that would not
parse is a **script**: `object L: …` followed by a top-level `println(…)`. `parse[Source]` rejects
the top-level statement (`8:1 illegal start of definition`) and `parse[Term]` rejects the leading
`object` (`1:1 illegal start of simple expression`), so both fallbacks fail **by construction** on
exactly the shape this emitter produces. That is why a `Cons` in the main file lowered while the
same pattern in an imported module did not — the main-file slice happened to parse.
`dialects.Scala3.withAllowToplevelTerms(true)` is the shape scalameta provides for this, and is now
tried first.

Getting that answer took two diagnostic fixes worth keeping:

- the pass reported *that* it gave up but not *why*; it now carries the parse position and message;
- it reported the LAST failure, which was always the `Term` fallback complaining about the leading
  `object` — true, useless, and it masked the `Source` error that names the real construct. First
  failure wins now.

Measured on the emitted source, before and after: `case Cons(` 9 → 0, nine `::` splices applied, all
`def`s intact. Both repros print `3` on int, js, native **and jvm**.

**`std/json.ssc` still does not compile on the jvm lane — for ten entirely different errors** that
the fourteen `Cons` ones were hiding:

```
2 × Not found: jsonCoreRender      2 × Not found: jsonCoreParseStrict
1 × Not found: jsonCoreParseTolerant   1 × __jsonCoreWrapStrict   1 × __jsonCoreWrap
1 × Not found: __jsonCoreEncodeValue
```

**Pre-existing, and checked rather than assumed:** three emitted-source dumps taken BEFORE this fix
all show `__jsonCoreEncodeValue` with 0 definitions and 1 use, exactly as now, while `case Cons(`
went 9 → 0.

**THOSE TEN ARE TWO DIFFERENT THINGS, 2026-08-05.** Reading the emitted source rather than the error
list separates them cleanly:

```
object std {              10266
  object json {           10269
    object core {         10270 … closes at 10747
      def jsonCoreRender          10719   (inside core)
    }
    def jsonStringify(v: Any): String = jsonCoreRender(__jsonCoreEncodeValue(v))   10755
```

1. **An emitter defect — a cross-object call is emitted unqualified.** `jsonStringify` lives in
   `std.json`; `jsonCoreRender`, `jsonCoreParseStrict` and `jsonCoreParseTolerant` live in
   `std.json.core`, because an imported module becomes a NESTED OBJECT. The call is written bare, and
   Scala will not resolve it from the enclosing scope — it needs `core.jsonCoreRender`. That is 6 of
   the 10 errors. Not a forward-reference problem: inside an `object`, forward references between
   `def`s are legal, and these definitions are inside objects, not blocks — which is what the
   "defined seven times and still not found" reading above got wrong.

2. **A missing implementation, not a defect — the jvm lane has no json externs.** The other 4 are
   `__jsonCoreWrap`, `__jsonCoreWrapStrict` and `__jsonCoreEncodeValue`, which `json.ssc` declares as
   `extern def`: host hooks with no `.ssc` body by design. They are implemented on **js**
   (`core-collections.mjs`) and on **native** (`JsonNativePlugin.scala`, `native(context,
   "__jsonCoreWrap")`). There is no jvm implementation anywhere under `v1/runtime/backend/jvm`. That
   makes it a FEATURE — the jvm lane needs those three hooks — and not something to patch in the
   emitter.

**(1) IS FIXED, 2026-08-05, and it was one line of filter.** `hoistSscImportsIntoObjectStd` already
exists for exactly this hazard — after `mergeDuplicatePackageObjects` merges the `object std` blocks,
the file-level `import std.…` lines land outside them, so the pass re-imports names INSIDE
`object std`. It took **uppercase names only**, with this reasoning in its own comment:

> Lowercase value-level names (functions/vals such as `mapRight` or `serve`) are left in place —
> those are only needed by user code at the file level, while nested std package code needs the
> type-like names.

**That is false the moment one std module imports another.** `std/json.ssc` calls `jsonCoreRender`,
`jsonCoreParseStrict` and `jsonCoreParseTolerant` — lowercase, value-level, and needed by *nested
std package code*, not by user code. Widened to hoist value-level names too. The pass only ADDS
imports inside `object std`; the file-level lines are untouched, so user code resolves as before.

```
before   10 errors:  6 × Not found: jsonCore*   +  4 × Not found: __jsonCore*
after     5 errors:  0                          +  5 × Not found: __jsonCore*
```

The six that were the emitter's fault are gone; what is left is exactly category (2).

**A THIRD instance of the same shape, found and fixed 2026-08-05.** `genUserOnlyWithLineMap` also
carries `qualifiedSrc.replace("__extern__", "???")` — for `extern def` stubs nested inside plain
classes or non-recursing objects, which pass through scalameta's `.syntax` with the body marker
intact. **`genModule` never applied it**, so those markers reached the compiler:
`components-smoke`'s JVM lane reported **nine `Not found: __extern__` out of eighteen errors**, and
they vanished the moment the same one-line patch was applied on the default path. 18 → 9.

That is now three defects in one file with one cause: **the two emit paths apply different
fixups**, and anything repaired in the bytecode path stays broken in the one `emit-scala` and
`run-jvm` actually use. The `Cons` lowering, the `__extern__` marker, and — still unverified — the
`case None()` rewrite in `rewriteActorAstCallsInSource`. Worth fixing as a structure rather than
one pass at a time.

**Measured chain across the day**, `std/json.ssc` on the jvm lane and the gates behind it:

| | errors |
|---|---|
| start | 14 × `Cons` |
| after `Cons` lowering | 10 (6 scope + 4 extern hooks) |
| after value-level import hoisting | 5 (extern hooks only) |
| `components-smoke` JVM, after the `__extern__` patch | 18 → 9 |

`upload-smoke` **passes now** — it was red before these three and needed none of the json hooks.

**THE FRAMING WAS WRONG AND IS CORRECTED, 2026-08-05: the jvm lane is not without json.** It has a
SECOND, OLDER implementation, emitted in the runtime preamble and marked `v1.5 Tier 5 #22 option
(c)` — `def jsonParse`, `def jsonStringify`, and a `class JsonValue(val raw: Any)` with
`apply` / `get` / `asString` navigation. It works:

```
val v = jsonParse("{\"a\":1}"); println(jsonStringify(v))     // no import

int {"a":1}      js {"a":1}      jvm {"a":1}
```

So there are two paths, and only one is broken:

| path | jvm |
|---|---|
| builtin globals, no import | works |
| `[jsonParse](std/json.ssc)` — the portable json-core module | 5 × `Not found: __jsonCore*` |

**The remaining harm is precise, and it is not small.** `std/http.ssc` imports `std/json.ssc`, so
**any program importing std/http does not compile on this lane**:

```
[route, serve, Response, Request](std/http.ssc)     int: registered     jvm: 9 errors
```

**That makes the fork decidable, which it was not when this entry said "a plugin the lane does not
have":**

1. **Implement the five hooks on jvm.** They must return JsonCore ADT values, and those types are
   emitted into `object std.json.core` — user-module code the fixed preamble cannot name. Whatever
   holds the implementations has to be emitted AFTER the modules, or build the ADTs structurally.
   That constraint is the real content of this option.
2. **Let the builtin serve the import.** The preamble already defines exactly the names
   `std/json.ssc` exports, and `JvmCapabilities` already maps `QualifiedName`s to `RuntimeCall`s —
   the machinery for "this module resolves to the host implementation on this lane" exists. This
   looks like the intended architecture rather than a workaround, but it is a decision about the
   portable-json design, not a codegen patch, and it belongs to whoever owns that design.

**OPTION 2 IS REFUTED, and it is the more dangerous of the two.** Measured, not argued:

- **Coverage is 6 of 15.** The preamble defines `JsonValue`, `jsonParse`, `jsonRead`,
  `jsonStringify`, `lookup`, `lookupOpt`. It does not define `jsonValue`, `jStr`, `jNum`, `jBool`,
  `jDecimal`, `jField`, `jObj`, `jArr`, `jsonEscape`. So it is not "reuse what is there" — it is
  reuse 40% and write the rest.
- **The semantics are OPPOSITE.** The module documents itself as total: *"Null `JsonValue` /
  zero-default, never a crash"*. The preamble's `JsonValue` throws — `apply`, `asString`, `asInt`
  all raise. Same operation, missing key:

  ```
  builtin (jvm)        Exception: JsonValue: no key 'nope'
  portable module      |end                    (empty string, no crash)
  ```

  Wiring the import to the builtin would make a program that returns a default on int/js/native
  **crash at runtime on jvm** — compiling identically, diverging only when executed, on one lane.
  That is the class of defect this repository spends most of its effort on, and it would be
  introduced deliberately.

**OPTION 3 — implement the hooks in `.ssc` instead — is also refuted, cheaply.** `extern def` has no
fallback body: the host provides the name or it is missing. Giving them `.ssc` bodies would take js
and native OFF their host implementations, which is a far larger blast radius than the jvm gap.

**OPTION 1 IS PROVEN TO BE PLACEABLE, 2026-08-05.** The constraint stated above — the hooks must
return `JsonCore*` ADT values whose types live in emitted module code the fixed preamble cannot name
— is satisfied by emitting them AFTER the modules. Verified by hand on the real emitted source:
appended the five definitions to the end, naming `std.json.core.JsonCoreNull` and friends, and

```
scala-cli compile probe2.sc   →  Compiled
scala-cli run     probe2.sc   →  parsed
```

They are visible from inside `object std.json` (top-level script definitions resolve across the
file) and they can name the nested module's types. **The `.sc` extension matters**: as a `.scala`
compilation unit the same text fails with `Illegal start of toplevel definition` — the emitted
output is a SCRIPT, which is the same fact that explained the `Cons` parse failure.

**ATTEMPTED, AND IT FOUND A HARDER CONSTRAINT THAN "write a total wrapper", 2026-08-05.** I wrote
the wrapper and the five hooks against the real emitted source and compiled. It fails, and not on
the wrapper:

```
value get is not a member of jx3$_.this.std.json.JsonValue
```

**`JsonValue` is an opaque type in the module, and the accessors are not declared anywhere.**
`std/json.ssc` has no `extension` block and no `def get` / `def asString` — the documented surface
(`get` / `at` / `isNull` / `as*` / `opt*` / `getOrElse` / `raw`) is provided **by the host at
runtime**, which is what "the bridge wraps portable JsonCore ADTs for method navigation" in that
file's own comment actually means. js and native dispatch methods dynamically on the wrapped value;
**the jvm lane dispatches statically**, so a wrapper's methods are unreachable through an
`opaque type JsonValue = Any`.

It is also a **name collision**, which sharpens the two-implementations finding: the preamble's
navigable `class JsonValue(val raw: Any)` and the module's opaque `JsonValue` are both in scope, and
`import std.json.JsonValue` makes the one without accessors win.

**So the jvm bridge is not five hooks — it is five hooks PLUS the whole navigable surface as
extension methods on the opaque type**, emitted in the same block. That is expressible (the opaque
type is `Any` at runtime, so an extension can cast through it) but it is a different size of job,
and it is the honest estimate this entry should carry rather than "ordinary work".

**One sub-problem remains and it is the real work.** `__jsonCoreWrap` must return a value that
navigates **totally** — `get` on a miss yields a Null wrapper, `asString` on a non-string yields
`""`, never an exception (js does this in `_jsonValueTotal`). The jvm preamble's `JsonValue` is the
opposite. So the emitted block needs its own total wrapper as well as the five hooks; it cannot
delegate to what is already there. That is the piece to write, and it is ordinary work now rather
than a design question.

**(2) remains open and is a FEATURE, not a fix.** `__jsonCoreWrap`, `__jsonCoreWrapStrict`,
`__jsonCoreEncodeValue`, `__jsonCoreRawStrict` and `__jsonCoreInstallRenderer` are `extern def`
host hooks. js has them in `core-collections.mjs`, native in `JsonNativePlugin.scala`, the jvm lane
has none. Until they exist, `std/json.ssc` — and therefore `std/http.ssc` — will not compile on this
lane, and this entry stays open for that reason and no other.

**A lead, not a claim:** the sibling pass `rewriteActorAstCallsInSource` parses the same way —
`Source` then `Term`, no script dialect — so its `case None()` → `case None` rewrite is probably
dead on any script-shaped slice too. Worth measuring before trusting it.

**Also partial, landed earlier:** two sites in `json-core.ssc` now narrow in the pattern
(`case JsonCoreOk(low: Int, afterLow)`), which is correct on its own terms — the arm is only
meaningful for a codepoint, and anything else falls to the existing error arm. Verified on all four
lanes. It removes 2 of the 14 errors and does NOT unblock the lane by itself; `Cons` is the blocker.

**Shape of the original failure** — the cascade from the unresolved extractor:

```
7 ×  Found: (tail : Any)   Required: Int
5 ×  Found: (unit : Any)   Required: List[Any] / List[Int]
2 ×  Found: (low  : Any)   Required: Int
```

all downstream of a `case Cons(...)` the compiler could not resolve.

**The blast radius is not json.** `std/http.ssc` imports it, so the jvm lane cannot compile a
program that serves HTTP either. That is how this was found: `components-smoke` and
`middleware-smoke` both reported **`the server process EXITED before it listened`** — a serving
symptom for a defect that happens before a single line runs. Neither gate was reporting a server
problem, and neither had been run in months.

**Declared, not left red.** The gate counts the four cells that pass and prints
`KNOWN GAP jvm with json`. It **fails if that cell starts passing**, saying to delete the
declaration and close this entry.

## jvm-string-literal-s-concat-inserts-x — the string `"s"` on the left of a `+` emits an extra `x`
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: f2941736a
     gate: tests/conformance/jvm-string-s-literal.ssc -->

**Found 2026-07-31** by `v2-char-value` while establishing the golden for `char-as-value`. Found by
accident and worth saying how, because the accident is the reproducible part: the `v2-char-is-an-int`
entry records the repro line as `println("s" + 'b')`, so a case written from it straddles this defect
and reads as a Char problem.

**Reproduce** — the right operand does not matter, and neither does `Char`:

```scalascript
def main() =
  println("a" + 'k')      // ak    correct
  println("s" + 'k')      // sxk   WRONG
  println("f" + 'k')      // fk    correct
  println("raw" + 'k')    // rawk  correct
  println("as" + 'k')     // ask   correct
  println("s" + "k")      // sxk   WRONG — a String right operand too
```

**The trigger is a left operand that is EXACTLY the one-character string `"s"`.** `"f"`, `"raw"`,
`"md"` — the other interpolator prefixes — are all correct, and so is `"as"`, so it is not "any short
string" and not "any prefix name".

**Three lanes against one, and the odd one out runs real Scala**, so the semantics are not in doubt
and the defect is in the generator:

| lane | `"s" + "k"` |
|---|---|
| int | `sk` |
| js | `sk` |
| v2 | `sk` |
| **jvm** | **`sxk`** |

Measured on a cleared artifact cache (`rm -rf .ssc-artifacts`) — `run-jvm` memoizes on SOURCE, so a
stale artifact was the first thing ruled out.

**Not investigated further**, deliberately: it is a different module from the claim that found it.
The shape (`s` + an inserted `x`) reads like the `s"…"` interpolation path being taken for a bare
`"s"` literal, with `x` coming from a generated temp name — that is a hypothesis, not a measurement.


**FIXED 2026-08-04.** The hypothesis in this entry was right in spirit and wrong in mechanism. It
guessed "the `s"…"` interpolation path being taken for a bare `"s"` literal, with `x` from a
generated temp name". There is no temp name: the JVM backend post-processes its EMITTED SOURCE with

```
out.replaceAll("(?<![$\w])s(\"{1,3})", "sx$1")
```

`sx` is a real interpolator defined in the preamble — it routes each interpolated value through
`_show` so a whole-number Double prints `4`, not `4.0`. The rewrite turns `s"…"` into `sx"…"`. Its
lookbehind correctly excludes `bytes"…"` and `$s"…"`, and the comment above it says the pattern is
"conservative enough not to match inside identifiers" — which is true, and says nothing about string
LITERALS, where the hole actually was: in `println("s")` the `s` sits immediately before the closing
quote and matches `s"`.

**Two measurements the entry did not have**, both of which changed the fix:

- `val v = "s"; println(v + "k")` is wrong too, so it is not about literal syntax — it is any
  literal whose text ends with the standalone word `s`. `" s"` → `" sx"`; `"as"`, `"ss"`, `"s1"`
  are fine.
- The SAME function has a twin: `.replaceAll("\.mkString\(", …)` corrupts
  `println("a.mkString(b)")` into `a.map(_show).mkString(b)`. Fixed in the same commit — it is one
  regex-over-source mistake made twice, and fixing one would have left the other.

Widening the lookbehind was rejected: it fixes `"s"` but not `" s"`. The only reliable
discriminator is where a literal STARTS, so `rewriteCodeRegions` scans instead of matching, applying
both rewrites to code runs only. `${ … }` holes are code and are rewritten recursively; comments and
char literals are skipped whole so a `"` in either cannot desynchronise the scan.

**A trap worth recording**: the first version of the scanner used `out.append(src, i, stop)`. Scala 3
AUTO-TUPLED it to `append((src, i, stop))` against `append(x: Any)` — it compiled without error and
spliced tuple `toString`s like `,23,24)` into the generated program. The symptom was a Scala syntax
error thousands of lines from the edit. Explicit `src.substring(a, b)` everywhere.

**Gate:** `tests/conformance/jvm-string-s-literal.ssc`, 10 rows on all four lanes. Verified to fail
without the fix: **7 of 10 rows wrong**. The other 3 are guards — `s"d=$d"`, a nested interpolation
and a `Double` `.mkString` — and they are IDENTICAL before and after, which is the point: the
interesting way to break this fix is to delete the rewrites and bring `4.0` back.

## run-jvm-silent-success — `ssc-tools run-jvm` prints nothing and exits 0, including for a file that does not exist
<!-- status: fixed
     kind: bug
     lane: jvm
     area: cli
     gate: none
     fixed-in: unrecorded -->

**Status:** OPEN, low priority — that lane is already documented as NON-CONFORMING and *"slated for
deletion with the v1 hybrid tier"* (`Main.scala:3088`). Filed because a silent `exit 0` is the one
failure shape this repo treats as always worth recording. Found 2026-07-30 by `json-number-policy`
while checking whether the jvm lane needed the same number-policy fix.

**Reproduce — two runs, both `exit 0`, both with no stdout at all:**

```
$ printf 'def main() = println(1+1)\n' > tests/conformance/_t.ssc
$ ssc-tools run-jvm tests/conformance/_t.ssc ; echo EXIT=$?
EXIT=0                                    # no "2"

$ ssc-tools run-jvm /does/not/exist.ssc ; echo EXIT=$?
Error: File not found: /does/not/exist.ssc
EXIT=0                                    # says Error, exits 0
```

**Not caused by a stale build and not local to one worktree:** identical on a freshly built worktree
and on the shared main checkout. Checked before filing.

**Why nothing caught it.** `contract.sc`'s default lanes are `int,js,v2`; `jvm` is opt-in
(`--lanes int,js,jvm`). If it were ever switched on, `laneCmd("jvm")` is exactly the command above, so
every case would compare EMPTY output against its golden — the whole lane would read as DIVERGE rather
than as "the lane is broken".

**Two separable defects, and the second is the one that matters regardless of deletion plans:**
1. no program output (the lane compiles via `scala-cli` and something in that path yields nothing);
2. **`exit 0` on `File not found`** — a fail-open in argument handling, independent of the backend.

Fixing (2) alone is cheap and stops the lane from lying about its own invocation.


**CLOSED 2026-08-04 — re-measured, and BOTH halves are correct now.** Nothing was fixed here; this
records the measurement so the entry stops sending people after a defect that is not there.

Run verbatim from this entry's own repro block, same file path and all:

```
$ printf 'def main() = println(1+1)\n' > tests/conformance/_t.ssc
$ ssc-tools run-jvm tests/conformance/_t.ssc ; echo EXIT=$?
2                                          # was: no output at all
EXIT=0

$ ssc-tools run-jvm /does/not/exist.ssc ; echo EXIT=$?
Error: File not found: /does/not/exist.ssc
EXIT=1                                     # was: EXIT=0
```

**`fixed-in: unrecorded`, deliberately** (the documented value for "fixed, commit not named" — specs/bugs-index.md). I could not identify a commit that changed either behaviour,
and would rather say so than name a plausible one:

- `RunJvmCmd` in `v1/tools/cli/src/main/scala/scalascript/cli/Main.scala` reads
  `if !os.exists(path) then System.err.println(s"Error: File not found: $file"); System.exit(1)` at
  HEAD, and `git log -S` on that string finds only refactors that MOVED the file
  (`b433a41e4`, `a9f7943f3`, `d0665660a`) — no commit that introduced the exit code.
- `bin/ssc-tools` ends in `exec java … "$@"`, which propagates the child's status, so the wrapper is
  not swallowing it either.

Which leaves two possibilities and no evidence to choose between them: the exit code was always 1
and the original observation came from something else in the reporter's shell, or a change between
2026-07-30 and 2026-08-02 fixed it without touching that string. The binary that measures correct
here was built at `ec70eb062` (2026-08-02), i.e. after this entry was filed.

**The paragraph worth keeping is the one about why nothing caught it**, and it is still true:
`contract.sc`'s default lanes are `int,js,v2`, so `jvm` is opt-in. If the lane ever regressed to
silence again, every case would compare EMPTY output against its golden and the whole lane would
read as DIVERGE rather than as "the lane is broken" — a shape that hides the cause behind 600
identical failures. That is a gap in the harness, not in this lane, and it outlives this entry.

## negtc-both-fail-derived-route-clients — the one case keeping the release gate red
<!-- status: fixed
     kind: apparatus
     lane: jvm
     area: codegen
     fixed-in: unrecorded
     gate: tests/conformance/corpus-baseline.tsv -->

**Status:** RESOLVED 2026-07-28 as a CLASSIFICATION, not a code fix — the case is genuinely
un-runnable and the two gates disagreed about that.

**Measured, in seconds** (the sweep now takes `--only`, so this cost minutes rather than the
56-minute gate):

```
derived-route-clients.ssc  both-fail  vm_rc=1  bytecode_rc=1
detail = ssc: unhandled runtime effect: Api.postApiTodos
```

`both-fail` means the VM and bytecode lanes AGREE — both fail. So it was never a parity divergence.

**The golden cannot run it either, which is the whole point:**

```
$ bin/ssc-tools run --v1 examples/derived-route-clients.ssc
[ERROR] [line 6, col 15] Undefined: awaitClient
```

The example demonstrates `apiClients:` derivation; its client block calls `awaitClient(Api.*)`
against a server nobody starts, and `awaitClient` does not exist on the INT lane at all. It is not
in `examples/run-all.sc`'s executable list and it is not a conformance case. And
`tests/conformance/corpus-baseline.tsv` **already marked it `SKIP`** — the corpus contract had
answered this question and frozen the answer; `bc-parity-sweep` simply did not read it.

**Fix.** The sweep now classifies a both-fail whose case the golden is declared unable to run as
`both-fail-no-golden`, reported in its own `NO-GOLDEN:` list and excluded from the `--strict`
verdict. Both lanes still RUN and their exit codes and detail are still recorded — the comparison is
performed and only the classification consults the declaration, which is the order AGENTS.md
requires.

Verified in BOTH directions, because a one-directional check would not distinguish this from a hole:

| baseline declaration | category | `--strict` |
|---|---|---|
| present (as frozen) | `both-fail-no-golden`, `no-golden: 1` | exit 0 |
| removed | `both-fail: 1` | **exit 1** |

`tests/e2e/negtc-shard-gate.sh` pins the invariants: the set is read from `corpus-baseline.tsv` and
never hand-maintained (the same reason `skipped-oversized-bytecode` avoids a per-example list),
`strict` still counts `both-fail` and never counts `no-golden`, and no-golden cases are NAMED rather
than only counted.

**Follow-up caught by the gate itself, worth keeping.** The first version named the bucket
`both-fail-no-golden`, which matched neither `$2 == "both-fail"` nor `$2 ~ /^skipped-/` in the
release gate's metric extraction. The freeze's ACCOUNTING CLOSURE — `identical + skipped + delegated
+ both-fail + mismatch + one-sided == frontend.total` — then failed with
`parity accounting 213 != frontend.total 214`. The check did exactly the job its comment claims
("catches any miscount/loss") and it caught mine. The bucket is now `skipped-no-golden` and
increments both `nogolden` and `skipped`, following this script's existing idiom for a sub-kind of
skip (`timedout++; skipped++`, reported as `skipped: N (timeout: M, no-golden: K)`).

Verified against the real `scripts/v21-negative-toolchain-freeze` on synthesized reports carrying
the CI numbers, all three states:

| state | freeze verdict |
|---|---|
| original (`both-fail: 1`) | `parity.both-fail expected 0, got 1` |
| first fix (`skipped: 133`) | `parity accounting 213 != frontend.total 214` |
| current (`skipped: 134`) | **`PASS`** |

Note what the first row says: the freeze had been demanding `parity.both-fail = 0` all along, with
the comment "a real deterministic both-lane gap must be classified away". The classification above is
what it was asking for.

**Left open deliberately:** `Api.postApiTodos` being an unhandled runtime effect IS a real native-lane
gap (derived API clients are not implemented there) — the same shape as the `System.nanoTime` gap.
It is not a *parity* finding and does not belong to this gate; if the example is ever meant to run,
that gap is the work.

## v2-distributed-failure-retry — retry path degrades to Stub
<!-- status: fixed
     kind: bug
     lane: jvm
     area: front
     fixed-in: ea21eb8a5
     gate: tests/conformance/distributed-failure-retry.ssc -->

**Status:** FIXED 2026-07-28 in `ea21eb8a5` (regression `a373460c3`);
reported by the SSC v2 corpus audit and accepted by `codex`, SPRINT
`v2-distributed-failure-retry`.

**Reported real-harness reproduction.**

```bash
bin/ssc run --native tests/conformance/distributed-failure-retry.ssc
```

Fresh assembled baseline (2026-07-28): the declared JVM lane passes 1/1.
Default/legacy × `--native`/`--bytecode` is 0/4 with identical exit-0 output:
`failures: 0`, then `11`, `12`, `Stub`, `15`, `16`. The single breadcrumb
replaces the failed partition's two expected values, `13` and `14`; frontend
and VM/direct-ASM are therefore ruled out.

**Root cause and fix.** The actor coordinator retained retry payloads with
`List[(partitionId, partition)].toMap`. V2 has no dynamic `List.toMap`
dispatch, so the lookup itself became `Stub`; the coordinator then sent that
placeholder to a healthy worker and accepted its placeholder result as
success. Both ordinary and typed-wire retry lookups now build a real portable
Map through `foldLeft` + `updated`. State remains in the shared actor
coordinator; the deterministic local-loopback provider and its no-remote-
failure contract are unchanged.

**Verification.** The unchanged corpus case prints zero failures and ordered
values 11 through 16 on JVM and V2; default/legacy × VM/direct ASM is exact
4/4. The full `distributed-*` slice is 6/6, actors-plugin is 4/4, distributed-
plugin is 5/5, and the partial/map/heterogeneous/shuffle neighbors are exact
16/16 across the same frontend/engine matrix.

## v2-generator-provider — Dataset cannot consume the native Generator provider
<!-- status: fixed
     kind: bug
     lane: jvm
     area: front
     fixed-in: 2f5bb135b
     gate: tests/conformance/dataset-from-generator.ssc -->

**Status:** **DONE 2026-07-28** by `codex` (`2f5bb135b`; regression
`e583c4203`; reported by the SSC v2 corpus audit, SPRINT
`v2-generator-provider`).

**Reported real-harness reproduction.**

```bash
bin/ssc run --native tests/conformance/dataset-from-generator.ssc
# Dataset.fromGenerator requires the standard generator provider
```

Fresh assembled baseline (2026-07-28): the declared INT/JS/JVM lanes pass
3/3. V2 fails 0/4 in the exact default/legacy × `--native`/`--bytecode`
matrix; every mode exits 1 with the same message above before any output.
This isolates the defect to the missing provider bridge rather than frontend
or VM/direct-ASM behavior.

**Fix acceptance.** `Dataset.fromGenerator` must lazily consume the existing
native Generator value and `Dataset.toGenerator` must return one owned by the
same provider. Pin both directions at the real plugin boundary, preserve
ordered output, and avoid a duplicate generator implementation in
dataset-plugin.

**Root cause and verification.** The Dataset provider still contained explicit
placeholder throws even though the Generator provider already exposed the
portable `next`, `generator`, and `suspend` surface. Dataset now drains `next`
only when a terminal evaluates and resolves `generator`/`suspend` lazily
through the existing plugin context; Generator remains the sole owner of
queue, backpressure, cancellation, and suspend state. The Dataset suite moved
from the expected fail-first 4/6 to 6/6, Generator remains 10/10, focused
conformance is 2/2, and both the bridge case and neighboring provider fixture
compare exactly across default/legacy × VM/direct ASM (8/8 total routes).

## v1-jvm-coroutine-generic-surface — generated runtime rejects the public typed API
<!-- status: open
     kind: bug
     lane: jvm
     area: codegen
     gate: tests/conformance/coroutine-basic.ssc -->

**Status:** OPEN (found 2026-07-21 by codex-q4 while enabling additive native-bytecode
conformance; deferred outside native-provider Q4 to `BACKLOG.md`).

**Reproduce:** `bin/ssc-tools run-jvm tests/conformance/coroutine-basic.ssc` fails compilation:
`coroutineCreate` does not take type parameters, and the value returned by `suspend` is `Any`, so
the two-way `b + "-pong"` expression has no `+`. The JVM runtime fragment currently exposes
`coroutineCreate(body: () => Any)` and `suspend(v: Any): Any`, while the normative/public API is
`coroutineCreate[Y, R, T](body: () => T)` and `suspend[Y, R](out: Y): R`.

**Notes / fix gate:** this is the pre-existing v1 Scala-codegen lane, not the new v2 native VM/ASM
provider. A faithful fix must preserve the independent yield type `Y` and resume type `R`; merely
adding unused type parameters to `coroutineCreate` does not repair inference for `suspend`. Until
that compatibility lowering is fixed, coroutine conformance must still run and diff the JVM lane as
an expiring `known-red` while the additive native VM and direct-ASM lanes compare normally.

## jvmgen-forked-e2e-wire-core-unpublished-snapshot — clean CI cannot resolve generated scripts
<!-- status: fixed
     kind: apparatus
     lane: jvm
     area: front
     fixed-in: 2e097848b -->

**Status:** FIXED 2026-07-20 by `frontend-tui-fetch-refresh` (`2e097848b`). Found only after the
extended exact-SHA run reached the complete `sbt test` tail.

**Symptom/reproduce:** exact-SHA run `29719191208` completed lint, validation, conformance, every
release gate, and most of the full test sweep, then failed two typed cases in
`JvmGenSqlRuntimeTest` and the front-matter schema case in `JvmGenEffectsRuntimeTest`. All three
generated scala-cli scripts contained this unresolved directive:

```scala
//> using dep "io.scalascript::scalascript-wire-core:0.1.0-SNAPSHOT"
```

The snapshot was absent from both the clean runner's Ivy local repository and Maven Central. A
developer machine with a previous `wireCore/publishLocal` silently hid the failure.

**Root cause:** the scala-cli e2e suites run in forked JVMs whose working directories are their SBT
subprojects. `JvmGen.sscJarDirective` therefore cannot discover the sibling `backend/wire/target`
JAR and correctly falls back to the publishable dependency form. The existing test-resource
contract already packages SQL, typed-data, and graph runtimes and rewrites their unpublished
directives to absolute `using jar` paths, but `wireCore` was omitted when typed-data began depending
on it.

**Fix/verification:** `backendInterpreter / Test / resourceGenerators` now packages `wireCore` and
exports its exact path. The SQL and OpenAPI e2e harnesses replace only the generated unpublished
wire directive with that fresh local JAR; production emission and Maven fallback semantics are
unchanged. With `COURSIER_REPOSITORIES=central` (so the local Ivy snapshot cannot rescue the test),
the three exact CI failures pass 3/3; the complete affected suites pass 4/4 and 35/35, and
`frontendTui/test` remains 36/36.

## jvm-swiftui-row-path-invalid-escape — generated Scala emits `"\."`
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: 236a02de2
     confirmed: no -->

**Status:** fixed (2026-07-12, `236a02de2`), awaiting Sergiy confirmation;
found by codex in the combined Swift CLI release gate and corrected in the
`scalascript` Rozum room after inspecting source bytes.

- **Real-harness repro:** run the four Swift CLI suites including
  `SwiftUiRealFixtureBuildTest`. The first three complete 53/53, then the real
  `.ssc` → generated Scala → Swift package lane aborts at generated lines
  10500/10512 with `invalid escape character` in `name.split("\.", -1)`.
- **Root cause:** the table payload helpers added in `1ecbc80ca` appear in an
  `s"""..."""` runtime template in `JvmGenPreamble`; its `\\.` source spelling
  loses one escaping layer. The parallel non-interpolated triple string in
  `JvmRuntimeUiPrimitives` already retains two slashes and must not change.
- **Fix/done-when:** use four source slashes for the two interpolated preamble
  sites so emitted Scala contains two, pin the generated runtime text, and pass
  the real fixture plus the combined 53-test Swift CLI set.
- **Fix/result:** only the two interpolated preamble sites were corrected; the
  generated-source assertion and fresh combined 54-test Swift CLI set pass.

## v21-native-graph-provider-missing — standard Graph facade has no core-free owner
<!-- status: fixed
     kind: feature
     lane: jvm
     area: front
     fixed-in: eb69124e2
     confirmed: no -->

**Status:** fixed (2026-07-12, provider `eb69124e2`, taxonomy `ff42d5d57`),
awaiting Sergiy confirmation; found by codex while continuing the TI-8.2d3
blocking taxonomy after distributed local-loopback landed.

- **Installed baseline:** VM and direct ASM both exit 1 with empty stdout.
  `graph-storage-interpreter.ssc` stops at `unhandled runtime effect:
  Graph.putVertex`; `graph-rdf4j-http-storage.ssc` stops at `Graph.putRdf`.
  Explicit compatibility proves the semantic split: the local example prints
  `imports:b.ssc` and exits zero, while the HTTP example prints
  `Stored two books.` then fails at `Sparql.select is not available in
  interpreter mode; use ssc run-jvm with backend: rdf4j-memory`. Its front
  matter requires an external URL and credentials, so remote query execution
  is not a standard local blocker.
- **Expected/done-when:** a required core-free provider owns the portable
  process-local property/RDF graph operations actually promised by the standard
  library, with deterministic values/order/errors and no v1 graph plugin,
  Scalameta, compiler, remote-service emulation, or transparent fallback.
  Exact standard examples pass VM/ASM/build-jvm and only genuinely resolved
  taxonomy rows are retired; explicitly remote backends receive an honest
  optional/external classification or bounded diagnostic.
- **Fix/result:** `v2/runtime/std/graph-plugin` owns deterministic process-local
  property/RDF storage and exact remote-query diagnostics through the native
  SPI. The local public example is exact on VM/ASM/build-jvm; the external
  RDF4J HTTP row is non-blocking and names the required explicit backend.
  Provider tests are 4/4 and the exhaustive release gate is green at 47
  identical / 19 both-fail / 6 blockers with zero mismatch/one-sided rows.

## v2-swift-distribution-authority-gaps — signed routes can rebuild or select an unverified product
<!-- status: fixed
     kind: bug
     lane: jvm
     area: front
     fixed-in: c75f49fe2 -->

**Status:** done (2026-07-11, through `c75f49fe2`); reported by `nativeui-reviewer` in the
`scalascript` Rozum room during the `v2-swiftui-apple-distribution-adapters`
pre-code audit at 22:07–22:08, after unsigned Xcode closure `1ff9b2e76`.

- **Real routing repro:** non-v1 `run --target ios --device` currently drops
  `--device`; `package` parses the source with v1 `Parser` before target routing
  and hard-stops; legacy device/archive helpers call `JvmGen` and infer
  `Debug-iphoneos`/`<App>.app`; generated fastlane lanes call `gym`, allowing a
  second build and product selection outside the checked-v2 artifact.
- **Expected:** one checked `SwiftV2Cli.emit` creates a
  `V2AppleDistributionContext`. Device, archive, export, notarization, DMG, and
  publish consume only its `XcodeAppArtifact`, explicit project/scheme, shared
  build-settings query, archive-relative app path, and common
  APPL/bundle/non-CLI verifier. No v1 parser/generator or inferred product path.
- **Credential/safety gap:** team id must be CLI then `SSC_TEAM_ID`; API key
  JSON must be flag then env; notarization must use an explicit keychain
  profile and bounded timeout. No prompt, secret logging, stale/duplicate
  export, archive traversal, or missing-tool stack trace is accepted.
- **Spec review residual (22:11 BLOCKED):** freeze Mac App Store as a checked
  Release archive plus canonical `app-store-connect` automatic export yielding
  exactly one PKG; make DMG input the codesign-verified app and require
  staple/validate only when notarization ran; transport release notes only via
  `SSC_RELEASE_NOTES` and pin custom lane names plus hostile-text gates.
- **Implementation review round 1 (22:33 BLOCKED):** route every explicit v2
  Apple package before `Parser` and reject signed `--v2 package` without a
  target before parse. Preflight flag-dependent codesign/ditto/xcrun/hdiutil,
  parse timeout with an exact bounded diagnostic, and reject incomplete API-key
  JSON before archive. Generated pilot/deliver must consume `SSC_BUNDLE_ID`.
  Extend secret-free fakes across device, Developer-ID/notary toggles, Mac PKG,
  generated/custom fastlane, tool absence, archive path/plist/app identity, and
  zero/duplicate app/PKG outputs; assembled negatives must prove no v1/stack.
- **Implementation review round 2 (22:53 BLOCKED):** generated Mac publication
  must invoke the platform-scoped lane as `fastlane mac mac_appstore`; the
  current bare `mac_appstore` resolves under `default_platform(:ios)` and the
  fake runner hides the real Fastlane failure. App verification must choose
  iOS versus macOS bundle layout from `SwiftPlatform`, never from which plist
  happens to exist; current device/archive fakes encode a mac-shaped iOS app.
  Fastlane API-key validation requires non-empty `key_id` and `key`, with
  optional `issuer_id` for supported individual keys. Add independent
  notarize/DMG toggle combinations and an assembled plain non-v1
  `package --target macos` Parser-bypass gate before round 3.
- **Plan/done-when:** commit the exact distribution authority spec delta and
  obtain Rozum APPROVE before code. Then implement secret-free command plans,
  verified archive/export handoffs, device deploy, Developer-ID/notary/DMG, and
  explicit IPA/PKG fastlane upload; gate with pure argv/env, fake-runner,
  synthetic archive/export, syntax, and assembled bounded-negative tests.
- **Root cause/fix:** signed routes still delegated product selection to legacy
  parser/generator/path conventions and allowed upload tooling to rebuild.
  They now share one checked-v2 context, platform-strict app/archive verifier,
  canonical fresh exports, bounded preflight, and explicit artifact-only
  fastlane lanes. `nativeui-reviewer` confirmed round 3; Swift is 43/43, the
  combined CLI matrix 53/53, assembled e2e passes, and `tkv2-*` is 12/12.

## v2-swift-xcode-contract-gaps — application metadata and product authority are ambiguous
<!-- status: open
     kind: feature
     lane: jvm
     area: front
     gate: tests/e2e/v2-swift-cli.sh -->

**Status:** CORE FIXED by swift-sibling work since the report; only the signed

**RE-CHECKED 2026-08-06 and the verification below no longer holds.** The documented `build --v2 --target macos` now fails with `swift backend: unsupported global 'forJsonView'` — a UI primitive added on 2026-07-20 (`221c940f2`) to the shared `.ssc` primitives and implemented for the DOM lane only. Filed as `v2/BUGS.md swift-macos-build-broken-by-forJsonView`. Status moved `unknown` → `open`: the remaining slice this entry names is still real, and now so is a regression in the part it called done. Nothing noticed for two and a half weeks because no gate builds the Swift target.
distribution-adapter slice remains (feature-scale). Verified end-to-end (opus, 07-13,
real Xcode 26.5): `ssc-tools build --v2 --target macos examples/frontend/ios-hello/ios-hello.ssc`
writes a real Swift package + `ios_hello.xcodeproj` + scheme; `xcodebuild -showBuildSettings`
shows `PRODUCT_BUNDLE_PACKAGE_TYPE=APPL`, `PRODUCT_BUNDLE_IDENTIFIER=com.example.ios-hello`,
`FULL_PRODUCT_NAME=ios_hello.app`; `xcodebuild build` → BUILD SUCCEEDED, producing
`ios_hello.app` with a non-CLI `Contents/MacOS/ios_hello` and `Info.plist`
`CFBundlePackageType=APPL` / correct id / executable. All three "can't prove it's a real
.app" claims are satisfied. REMAINING: the named codesign/notarize/TestFlight distribution
adapter (feature-scale, swift specialists). _Original report:_ reported by `nativeui-reviewer` in the
`scalascript` Rozum room during the pre-code Xcode-project audit at seq/time
20:55, after Trusted HTML closure `2c1db9f1d`.

- **Design repro:** the v2 generator returns one ambiguous executable string and
  writes SwiftPM/Apple sources but no PBX application artifact. Apple CLI lanes
  either run the debug CLI or stop, while legacy device/archive/publish helpers
  call v1 `Parser`/`JvmGen`, hard-code `Debug-*` product paths, and do not prove
  that the selected bundle is an application rather than `<AppName>Cli`.
- **Expected:** metadata is extracted with the checked v2 source pass; UI mode
  requires validated reverse-DNS bundle identity and validated Apple versions.
  One deterministic multi-platform application target/scheme owns all AppCore,
  AppleApp, and resource inputs. Apple commands consume an explicit
  `XcodeAppArtifact`, discover the product through build settings, then verify
  `.app`, `CFBundlePackageType=APPL`, exact bundle id, and non-CLI executable.
- **Spec review residual (BLOCKED):** target-scoped cleanup is not precise
  enough. Current emit removes `Sources`/`Package.swift` but can retain an old
  product's xcodeproj/`@main` files; broader deletion could destroy user-owned
  resources. Freeze a sorted `.ssc-swift-generated.json` of exact owned paths,
  validate every entry as relative with no `..`, delete only the previous list
  and empty owned directories, preserve unlisted files, and atomically replace
  the manifest last. Gate product rename, UI→domain→UI, and preserved resources.
- **Implementation review round 1 (BLOCKED):** the metadata helper treats
  indented nested YAML keys as top-level and drops empty values into defaults;
  explicit `frontend: swiftui` does not force UI mode. Preserved user Resources
  are absent from the PBX phase, and ownership falls back to a non-atomic move.
  `XcodeAppArtifact` is not yet consumed: build/macOS run still use SwiftPM CLI,
  iOS still rejects, and no shared showBuildSettings/APPL/bundle/non-CLI verifier
  exists. The real gate hand-builds Program, hard-codes Debug paths, weakly
  matches `-list`, uses a generic simulator, and omits plist/product discovery
  and bounded launch. Record/fix all before round 2.
- **Implementation review round 2 (BLOCKED on evidence only):** production
  residuals above are closed, but the checked gate does not byte-compare two
  complete written UI trees including ownership manifest or assert the frozen
  destination-specific Xcode settings (target/product, bundle/display/version,
  deployments/platforms, Catalyst off, no team). macOS smoke calls unbounded
  `waitFor()` after `destroy`; use timed wait and forced kill in `finally` so
  both process and temporary-tree cleanup are bounded.
- **Unsigned application closure (2026-07-11):** generator `d1b4350b7`, common
  unsigned adapters `abf9943c8`, and acceptance evidence `3942297ca` are on
  `origin/main`. Checked metadata, deterministic PBX/scheme/resources,
  manifest-scoped atomic ownership, build-setting discovery, and plist
  verification drive real macOS and concrete iOS Simulator apps. Reviewer
  round 3 APPROVE; Swift 43/43, CLI 8/8, assembled e2e, and `tkv2-*` 12/12.
  Status remains open only for the named signed distribution-adapter slice.
- **Plan/done-when:** freeze the exact metadata/default/validation, PBX id and
  target settings, source/resource membership, artifact split, discovery, and
  distribution sub-slice in `specs/v2-swift-swiftui-native.md`; obtain Rozum
  design APPROVE before implementation. Close only after real unsigned macOS
  build/inspection/bounded launch, installed iOS 26.5 Simulator build, signed
  adapter routing tests, full affected suites, and reviewer confirmation.

**Gate named 2026-08-14: `tests/e2e/v2-swift-cli.sh`** — and the entry itself says why one is
needed: *"Nothing noticed for two and a half weeks because no gate builds the Swift target."* That
is the finding, not a detail of it. The existing gate already drives `ssc-tools` down the Swift
path, so the macOS target build belongs beside it rather than in a new file.

**Done when** `build --v2 --target macos` succeeds on the fixture again — it currently fails with
`swift backend: unsupported global 'forJsonView'`, a UI primitive added 2026-07-20 (`221c940f2`)
for the DOM lane only, filed as `v2/BUGS.md swift-macos-build-broken-by-forJsonView` — and the
regression is asserted there, so the next lane-only primitive cannot repeat it silently.

**What is NOT in scope, stated so the entry can close:** the codesign/notarize/TestFlight
distribution adapter is feature-scale and belongs to swift specialists. The bundle-identity claims
this entry was opened for were verified end-to-end against a real Xcode and are done.

## v21-native-zero-arg-println-arity — blank println fails before later statements
<!-- status: fixed
     kind: bug
     lane: jvm
     area: front
     fixed-in: e74241f5e
     confirmed: no -->

**Status:** fixed (2026-07-11, `e74241f5e`), awaiting Sergiy confirmation;
found by codex while running the new core-free YAML provider against
`yaml-parse.ssc`.

- **Real-harness repro:** after staging the YAML provider, run
  `bin/ssc run --native examples/yaml-parse.ssc` or its `--bytecode` lane. Both
  print the first five YAML-derived lines and then fail at the source
  `println()` with `arity: 1 expected, 0 given`.
- **Expected:** the established zero-argument `println()` emits one empty line
  and document execution continues identically on VM/direct ASM/build-jvm.
- **Root cause:** the self-hosted lowerer exposes `println` only as an arity-one
  lambda and lowers an empty source application without the compatibility
  frontend's empty-string adaptation.
- **Fix/evidence:** lower only zero-argument global `println()` to the portable
  print primitive with an empty string. The focused real-harness fixture is
  exact on VM/direct ASM, the complete native-entry smoke passes, the YAML
  example advances to its independent fenced-section boundary, and fresh
  affected conformance passes 11/11.

## v2-bytecode-x402-unhandled-op-success — bytecode lane exits 0 with unresolved `Wallets.metaMask` op
<!-- status: fixed
     kind: bug
     lane: jvm
     area: front
     fixed-in: 7192cd6e4
     confirmed: no -->

**Status:** fixed (2026-07-10, `7192cd6e4`); waiting for human confirmation
before `done`.

- **Found by:** codex during the TI-2 VM/ASM corpus baseline at `7ba4d413b`.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, compare
  `bin/ssc run examples/x402-metamask.ssc` with
  `bin/ssc run --bytecode examples/x402-metamask.ssc` (the focused portable
  repro is `scripts/bc-parity-sweep --only x402-metamask.ssc --strict`).
- **Observed:** the VM exits 1 with source-located `Undefined: Wallets` at
  `Wallets.metaMask(Network.Base)`. The bytecode lane exits 0 and prints
  `Op("Wallets.metaMask", Base, <closure>)` as if the unresolved effect were a
  successful program result.
- **Expected:** VM and ASM agree. Until the wallet plugin/global is available,
  both lanes must fail with a stable unresolved-symbol/effect diagnostic; once
  it is available, both must execute it and produce the same observable output.
- **Root cause direction:** the v2 bytecode route preserves an unhandled plugin
  method fallback as a top-level `Op` value and the CLI treats that value as a
  successful result, while the VM/default route rejects the undefined
  `Wallets` global earlier. Inspect FrontendBridge method-object registration,
  `PluginBridge` fallback dispatch, and bytecode top-level result handling.
- **Owner/slice:** `v21-ti-native-front-parity` plus VM/ASM parity; add a
  focused conformance row before closing.
- **Root cause:** all four public v2 execution routes independently treated any
  non-Unit result as printable success. The bridge ASM path correctly preserved
  the unresolved dotted plugin fallback as `DataV("Op", ...)`, but its CLI
  result branch did not distinguish that diagnostic sentinel from ordinary
  user values.
- **Fix:** route bridge/native and VM/ASM final values through one result
  validator. A dotted auto-thread `Op` now raises `unhandled runtime effect`;
  the related missing-method `Stub` sentinel raises `unresolved runtime
  dispatch`; undotted user free-monad `Op` data remains printable.
- **Verified:** assembled `tests/e2e/v21-unhandled-effect-smoke.sh` rejects a
  native missing dispatch on VM and ASM and the exact bridge-ASM
  `Wallets.metaMask` repro; native-entry smoke PASS; CLI argv tests 2/2;
  affected `v2-*` conformance 8/8.

## v2-indent-conformance-demos-skipped — indent demo cases are skipped by conformance but fail directly
<!-- status: fixed
     kind: apparatus
     lane: jvm
     area: front
     fixed-in: 886502d64
     confirmed: no
     gate: tests/conformance/indent-config-format.ssc -->

**Status:** fixed (2026-07-10, demo fix `886502d64`, conformance lane
`bcffa0019`); waiting for human confirmation before `done`.

- **Found by:** codex while fixing `v2-dsl-yaml-tuple-accessor`.
- **Repro before fix:** after `scripts/sbtc "installBin"`, direct v2 runs
  failed: `bin/ssc run tests/conformance/indent-config-format.ssc` crashed with
  `__method__: no dispatch for ._1 on "host"`, and
  `bin/ssc run tests/conformance/indent-block-statements.ssc` crashed with
  `__method__: no dispatch for ._1 on "x"`. The conformance harness also
  reported both cases as `SKIP (no expected/*.txt)`, so it did not catch the
  direct production v2 failures.
- **Root cause:** the demo parsers relied on infix precedence in expressions
  like `identifier <~ ... ~ value`; v2 grouped the `~` under the `<~`, so the
  mapper received the left scalar (`"host"`/`"x"`) instead of a Tuple2. The
  config demo also used a nullable blank-line regex that could match the empty
  string before consuming separators, leaving the second section in `rest`.
  Finally, `tests/conformance/run.sc` had only INT/JS/JVM lanes, so there was no
  honest way to activate v2-only expected-output cases.
- **Fix:** parenthesized the parser sequences so maps receive the intended
  Tuple2 shapes, changed config blank-line skipping to a non-nullable
  `blankLine.many()`, added a `while`/`for` sample to cover nested tuple shape,
  added expected outputs, and taught the conformance runner an opt-in `V2` lane
  for files declaring `backends: [v2]`.
- **Verified:** direct `bin/ssc run --v2` for both files; `bash -n
  tests/e2e/indent-layout-v2-smoke.sh &&
  tests/e2e/indent-layout-v2-smoke.sh`; `tests/conformance/run.sh --only
  'indent-config-format,indent-block-statements' --no-memo` now reports 2/2
  with `PASS [V2 ]`; `tests/conformance/run.sh --only 'parsing-*' --no-memo`
  passes 3/3; `git diff --check` passes.

## v2-bytecode-param-long-nontail-self-loop — `fixed` (2026-07-09)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: front
     fixed-in: unrecorded -->

- **Found by:** codex while rerunning final gates for
  `v2-source-jvm-recursion-fib-perf` after rebasing onto `origin/main`.
- **Repro:** on fresh `origin/main` `8ec03cfbf`, run
  `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest -- -z recursive"`.
- **Observed failure:** `v2 bytecode self-recursive int arithmetic keeps VM result`
  returns `IntV(1)` instead of `IntV(832040)` for:
  `def fib(n: Int): Int = if n <= 1 then n else fib(n - 1) + fib(n - 2); fib(30)`.
- **Expected:** the bytecode lane returns the same `IntV(832040)` as the v2 VM.
- **Root-cause hypothesis:** `JvmByteGen.emitParamLong` emits every self-call in a
  Long-specialized helper as a parameter-rebinding loop jump. That is valid only
  for tail self-calls. Non-tail recursive calls inside expressions such as
  `fib(n - 1) + fib(n - 2)` must call the Long helper recursively and leave a
  value on the operand stack.
- **Impact:** the current v2 bytecode recursive-Int fast path is semantically
  wrong for non-tail recursion; this blocks using the focused recursive bridge
  gate as a production signal.
- **Fix:** `41e2fe1ed` threads tail-position information through
  `JvmByteGen.emitParamLong`: tail self-calls keep the parameter-rebinding loop,
  while non-tail self-calls emit recursive `invokestatic <helper>(J...)J`.
- **Verified:** focused recursive bridge test 3/3, self-tail bridge test 1/1,
  affected recursion conformance 3/3, and `git diff --check`.
- **Status:** fixed; waiting for human confirmation before `done`.

## jvm-artifact-cache-codegen-invalidation — `fixed` (2026-07-09)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     gate: tests/conformance/litdoc.ssc
     fixed-in: unrecorded -->

- **Found by:** codex, while fixing `v2-litdoc-js-jvm-backend-lanes`.
- **Repro:** generate a `.scjvm` artifact for a fixture, change JVM codegen
  without changing the `.ssc` source bytes, run
  `bin/ssc run-jvm tests/conformance/litdoc.ssc`.
- **Observed failure:** `run-jvm` reused
  `tests/conformance/.ssc-artifacts/litdoc.scjvm` because the artifact cache is
  invalidated only by the source SHA. `bin/ssc emit-scala
  tests/conformance/litdoc.ssc` showed the fixed generated Scala, but
  `bin/ssc run-jvm tests/conformance/litdoc.ssc` still compiled the stale
  `.scjvm` until that one generated file was removed.
- **Impact:** after upgrading compiler/codegen/runtime bits, a user can keep
  running stale JVM generated source for unchanged `.ssc` files. This can hide
  fixes or preserve old failures in production verification.
- **Root cause:** `.scjvm` freshness only compared the `.ssc` source SHA, so
  source-fresh artifacts survived JVM backend/runtime codegen changes.
- **Fix:** `322ee868f` added `codegenVersion` to `ModuleJvmArtifact`, writes
  the current JVM codegen cache key, and makes `ModuleGraph.isJvmStale`
  invalidate legacy/old-key artifacts. `14aa2819d` adds a CLI regression that
  proves `run-jvm` regenerates a source-fresh `.scjvm` with an old key.
- **Gates:** `core/testOnly scalascript.artifact.ModuleGraphTest`;
  `cli/assembly; cli/testOnly scalascript.cli.JvmIncrementalCliTest`;
  `scripts/sbtc "installBin"`; `tests/conformance/run.sh --only 'litdoc' --no-memo`.
- **Status:** fixed; waiting for reporter/human confirmation before `done`.

## v1-jvm-state-threaded-handler-codegen — `fixed` (2026-07-12, opus)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Root cause (refined 2026-07-12, opus) — THREE layers, all Any-typing of the
  deep-handler in JVM codegen:**
  1. `emitCaseBody` rendered a handler-arm lambda `(s: Int) => …` as bare `s => …`
     (dropped the type) → "could not infer parameter s". **FIXED** (571921446):
     annotate each param with its decltpe or `: Any`, mirroring JvmGenCpsTransform.
  2. `resume(())(x)` — `resume` is `Any => Any`, so `resume(())` is `Any`; applying
     `(x)` to it → E050 "Function1 does not take more parameters". Needs the inner
     `resume(())` cast to `Any => Any` before the outer application.
  3. `threaded(0)` — the `_handleWithReturn(...)` result is bound to an `Any` val;
     `threaded(0)` → E050 "threaded does not take parameters". Needs the general
     application codegen to cast an Any-typed callee to `Any => Any`.
  Layers 2–3 FIXED (571921446 + this commit): cast the Any-typed intermediate/callee to
  `Any => Any` at both application sites. run-jvm now matches INT/JS. `effects`/`head-field-effect-shadow`
  conformance stay green on all lanes after layer 1.

- **Found by:** claude-fable-5, while shaping the effect-multiarg-op regression.
- **Symptom:** `bin/ssc run-jvm` fails to COMPILE any handler whose arms return
  lambdas (the state-threading deep-handler idiom, busi's `runJournal`):
  "I could not infer the type of the parameter s / Expected type for the whole
  anonymous function: Any". Arity-independent (1-arg op repros identically);
  INT and JS lanes run the same code fine.
- **Repro:** `effect Cnt: def tick(n: Int): Unit` + handler arms
  `case Cnt.tick(n, resume) => (s: Int) => resume(())(s + n)` /
  `case Return(x) => (s: Int) => x`, applied as `threaded(0)` → `run-jvm` fails,
  `run --v1` / `emit-js` pass.
- **Impact:** low today — busi runs the interpreter lane; no corpus case uses the
  idiom on the JVM lane (that's why it was never seen).

## jvm-scjvm-cache-codegen-version — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: 322ee868f
     gate: tests/conformance/.ssc-artifacts/std-ui-extended.scjvm -->

- **Found by:** codex, while fixing `conformance-jvm-std-ui-generated-braces`.
- **Repro:** keep a stale generated artifact such as
  `tests/conformance/.ssc-artifacts/std-ui-extended.scjvm` from before a JVM
  backend codegen fix, rebuild/install the CLI, then run
  `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc`.
- **Observed:** `run-jvm` reused the source-fresh `.scjvm` artifact and still
  failed with the old generated Scala `'}' expected, but eof found`. Removing only
  `tests/conformance/.ssc-artifacts/std-ui*.scjvm` forced regeneration and the same
  assembled command passed. This means the `.scjvm` freshness key does not account
  for backend codegen/runtime changes.
- **Status:** fixed in `322ee868f`. Root cause was that `.scjvm` artifacts used
  only the `.ssc` `sourceHash` as their freshness key, so generated Scala from an
  older JVM backend survived source-fresh after codegen/runtime fixes.
- **Fix:** `.scjvm` artifacts now carry `codegenVersion =
  "jvm-codegen-2026-07-08-1"` when emitted by the normal JVM artifact writer.
  `ModuleGraph.isJvmStale` treats missing/old codegen versions as stale while
  preserving ABI compatibility: legacy artifacts remain readable, then regenerate.
- **Verified:** `scripts/sbtc "core/testOnly scalascript.artifact.ModuleGraphTest"`
  (**15/15 green**, including legacy/old-version source-fresh `.scjvm`
  invalidation), `scripts/sbtc "cli/testOnly scalascript.cli.VerifyCliTest"`
  (**7/7 green**), `scripts/sbtc "installBin"`, and
  `tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
  (**5/5 green**).

## conformance-int-variables-while-update — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: cli
     fixed-in: 4e67a2f41
     gate: tests/conformance/run.sh -->

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `tests/conformance/run.sh --only 'variables' --no-memo` or
  `bin/ssc run --v1 tests/conformance/variables.ssc`.
- **Observed:** INT prints `5`, `10`, `720`, `55`; expected line 2 is `15`.
  JS/JVM pass. The first `while x < 5` loop increments `x` but does not accumulate
  the updated `x` into `sum`.
- **Status:** fixed in `4e67a2f41`. Root cause was the interpreter closed-form
  while optimizer, not the generic assignment path: it folded a body shaped like
  `x = x + 1; sum = sum + x` as if `sum` read the pre-update counter, producing
  `0+1+2+3+4 = 10`. ScalaScript assignment order requires `sum` to read the
  post-update `x`, producing `1+2+3+4+5 = 15`.
- **Verified:** `scripts/sbtc 'backendInterpreter/testOnly scalascript.SscVmTest -- -z "closed-form"'`
  (**6/6 green**); `scripts/sbtc "installBin"`; direct
  `bin/ssc run --v1 tests/conformance/variables.ssc`; and
  `tests/conformance/run.sh --only 'variables' --no-memo` (**1/1 green**).

## conformance-std-typeclass-int-jvm-gaps — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: f92d147b0
     gate: tests/conformance/std-index.ssc -->

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`, run either
  `bin/ssc run --v1 tests/conformance/std-index.ssc` or
  `bin/ssc run-jvm tests/conformance/std-index.ssc`.
- **Observed:** INT prints the first two lines of `std-index` and then hits
  `StackOverflowError` in `EvalRuntime.evalApplyGeneral`/`DispatchRuntime.dispatch1`.
  JVM generated Scala rejects imports from `std/index.ssc` and sibling typeclass
  modules: `Left`/`Right` are imported from module objects that do not define them,
  and aggregate exports such as `std.intSum` are missing. Related full-gate misses:
  `std-foldable-traversable`, `std-functor-applicative-monad`, `std-index`,
  `std-bifunctor`, `std-monaderror`, and `std-selective`.
- **Status:** fixed in `f92d147b0` and `7328e35db`. Root causes were split
  across both lanes: INT extension dispatch preferred an imported same-named
  extension over built-in members, so `Option.map` in `map2Option` recursed;
  JVM import generation lost std typeclass re-export provenance, omitted
  standalone top-level extension imports, and emitted explicit context-bound /
  `using` instance calls as flat Scala calls. The std typeclass manifests also
  needed explicit type exports/imports so the strict import gate can resolve
  aggregator exports deterministically.
- **Verified:** `scripts/sbtc "backendJvm/compile"`;
  `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenUsingTest"`;
  `scripts/sbtc "installBin"`; direct INT/JVM repros for `std-index`,
  `std-selective`, `std-monaderror`, and `std-foldable-traversable`; and
  `tests/conformance/run.sh --only 'std-functor-applicative-monad,std-foldable-traversable,std-index,std-bifunctor,std-monaderror,std-selective' --no-memo`
  (**6/6 green**).

## conformance-dsl-multi-pass-js — `fixed` (2026-07-08)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: front
     gate: tests/conformance/run.sc
     fixed-in: unrecorded -->

- **Found by:** codex, while verifying the docs-only `v2-prod-corpus-scope` slice.
- **Repro:**
  `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo`
- **Observed:** `dsl-multi-pass` passes the INT and JVM lanes but fails JS:
  expected line 2 `[name-resolve] undefined: z` and line 3 `ok: 8`, got
  `[parse] unrecognised token: x` for both. The failing source parses
  `"x + z"` / `"x + y"`; `parseExpr("x")` should produce `Var("x")`.
- **Scope:** JS backend/conformance lane. Not caused by the corpus-scope docs
  change and not a default output-parity blocker, but it is a production hygiene
  gate before claiming broad green status.
- **Hypothesis:** JS lowering/runtime mishandles the string-character predicate
  shape used by `t.forall(c => (c >= 'a' && c <= 'z') || c == '_')`, so alphabetic
  identifiers are rejected as parse errors.
- **Root cause:** JS `String.forall` passes boxed `_Char` values to the predicate, but
  `_arith` ordered `_Char` against a one-character JS string literal with native JS
  object-vs-string comparison. Equality already normalized `_Char`; ordering did not,
  so `c >= 'a' && c <= 'z'` was false for alphabetic characters.
- **Fix:** `39ebb6fda` adds a shared `_charCodeOrNull` helper and normalizes `<`, `>`,
  `<=`, and `>=` when either operand is `_Char`, while preserving normal string
  concatenation and ordinary string-vs-string comparison.
- **Verification:** after `scripts/sbtc "installBin"`,
  `scala-cli tests/conformance/run.sc -- --only 'dsl*' --no-memo` passes
  `dsl-multi-pass` in INT/JS/JVM. Neighbor slice
  `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
  confirms `collections` and `dsl-multi-pass` still pass; the remaining `parsing*`
  INT-only failures are tracked separately as `conformance-parsing-int-empty-output`.

## bytecode-shared-runtime-routes-unbound — `fixed` (2026-07-07)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** claude (green-main takeover), unmasked by fixing EmitScalaFacadeCliTest's missing
  `-Dssc.lib.path` (the CompilerLoader env error hid it).
- **Symptom:** `ssc compile-jvm --bytecode` fails: "shared runtime compile failed …
  `_ssc_runtime.scala:4931: Not found: _routes` / `Not found: route`" — `JvmGen.genRuntime`'s
  capability gating emits runtime code that references the route registry without emitting its
  definitions (route infra lives in the http/serve runtime piece; the gate combination for the
  bytecode shared runtime includes the referencing piece but not the defining one).
- **Repro:** `sbt 'cli/testOnly *EmitScalaFacadeCliTest'` — 5 of 7 fail on this (2 pass after the
  lib-path harness fix). Or any `compile-jvm --bytecode` invocation.
- **Impact:** the whole `--bytecode` separate-compilation happy path (compile-jvm/link facade family).
- **Root cause:** self-contained `JvmGen.genModule` always emitted either `serveRuntime` or
  `stubServeRuntime`, but split `JvmGen.genRuntime` omitted both when the unioned capability set did
  not contain `Serve`. The always-included common/effects runtime still references route/http/ws
  dispatch symbols, so the shared `_ssc_runtime.scala` could not compile for no-server bytecode
  artifacts.
- **Fix:** `83fc339e2` emits `stubServeRuntime` in split runtime when `Serve` is absent and adds a
  `JvmGen.generateRuntime(Set.empty)` regression test for `_routes`, `route`, `onWebSocket`, and
  `_httpDoRequest`.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JvmGenRuntimeSeparationTest"`;
  `scripts/sbtc "installBin"`; `scripts/sbtc "cli/assembly"`; `scripts/sbtc "cli/testOnly *EmitScalaFacadeCliTest"`;
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`.

## scjvm-artifact-cache-ignores-compiler-version — `fixed` (2026-07-07)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: 322ee868f -->

- **Found by:** claude (green-main takeover), while fixing jvmgen-block-call-empty-parens: after
  rebuilding `bin/ssc` with a codegen fix, `ssc run-jvm` kept producing byte-identical BROKEN output.
- **Symptom:** `run-jvm` reuses `<file-dir>/.ssc-artifacts/<name>.scjvm` whenever the SOURCE sha
  matches (`ModuleGraph.isJvmStale`) — the cache key ignores the compiler/binary version, so codegen
  fixes are invisible until the artifact is deleted by hand. Cost ~4 rebuild-and-scratch-head cycles.
- **Repro:** run any `.ssc` via `run-jvm` (caches the emission), change JvmGen, `installBin`, run
  again — the old emission runs.
- **Fix:** duplicate of `jvm-artifact-cache-codegen-invalidation`, fixed by
  `322ee868f` (`codegenVersion` on `.scjvm` artifacts +
  `ModuleGraph.isJvmStale` key comparison) and guarded by `14aa2819d`
  (`run-jvm` regenerates a source-fresh `.scjvm` whose codegen key is old).
- **Gates:** see `jvm-artifact-cache-codegen-invalidation` above:
  `ModuleGraphTest`, `JvmIncrementalCliTest`, `installBin`, and conformance
  `litdoc` on INT/JS/JVM.
- **Workaround:** `rm -rf <dir>/.ssc-artifacts` after rebuilding the binary.
- **Status:** fixed; retained as a historical duplicate of the 2026-07-09
  cache-invalidation fix.

## jvmgen-block-call-empty-parens — `fixed` (2026-07-07)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: front
     fixed-in: unrecorded -->

- **Fixed by:** claude (green-main takeover), SHA: see `fix(jvm): conformance JVM-lane...` on main.
  Peeling the symptom exposed THREE stacked root causes; all four tests now PASS
  (`bin/ssc run-jvm` on signals / effects / rest-validate / distributed-map), with regression
  guards frontendSwiftUI 118/118 (widget `f() { block }` form preserved for curried callees) and
  backendInterpreter `*JvmGen* *Effect*` 193/193.
  1. **empty-parens** (signals, rest-validate): `JvmGen.emitExprDeep` emitted `f() { block }` for
     EVERY bare-name single-block call (fa5d3c821, Jun 2 — swiftui widgets). Broke single-thunk
     callees (`computed`/`effect`/`validate`/`runActors`). Now curried form only when
     depDefs/localDefSigs shows ≥2 param clauses. Exposed 2026-07-06 when bp2-2 enabled the
     warm JVM batch lane — the bug is older than the "suspect window" guessed below.
  2. **Console duplicate** (effects): the preamble's `object Console` println-shadow collided with
     a user `effect Console:` lowering. Preamble shadow is now omitted when the module defines its
     own top-level `Console` (JvmRuntimePreamble.sourceFor + collectUserTopNames bare-Stat arm);
     the effect-object lowering carries the println/print bridges instead.
  3. **premature declared-type cast** (effects, distributed-map): CPS-emitted defs whose ops are
     handled at the CALL SITE (`handle(greet())`) were cast to their declared type
     (`.asInstanceOf[String]`) while still holding an unresolved Free → ClassCastException
     `_FlatMap → String`/`DistributedResult`. Declared type is now preserved only for
     SELF-handling bodies (contains a `handle` form), extending codex's
     `preserveTotalEffectfulReturnTypes`.
  Diagnosis gotchas that cost cycles, recorded for the next agent: `.ssc-artifacts` scjvm cache
  (see the new open bug above), a stale sbt/bloop daemon from a deleted worktree answering builds
  (`scripts/kill-stale-builders --kill`), and macOS Xcode `strings` silently failing on JVM
  `.class` files (use `grep -ac` / `unzip -p … | grep -ac` instead).

### Original report (superseded 2026-07-07)

- **Found by:** claude (tkv2-components slice), via the full-corpus A/B: 4 tests
  (signals, effects, rest-validate, distributed-map) fail the JVM lane in any FRESH
  build of origin/main, while the shared main checkout "passes" only because its
  `bin/ssc` is STALE (pre-2026-07-03 source — its generated preamble lacks the
  webauthn `configureStore` block and `Bench.opaque`). Reproduced on a pristine
  origin/main worktree + fresh `installBin`.
- **Symptom:** `ssc run-jvm tests/conformance/signals.ssc` — user code
  `val doubled = computed { … }` is emitted as `computed() { … }` (empty first arg
  list + trailing block) → Scala compile error "missing argument for parameter
  thunk". Same for `effect { … }`.
- **Repro:** `scripts/new-worktree probe && cd ../scalascript-wt-probe &&
  scripts/sbtc installBin && bin/ssc run-jvm tests/conformance/signals.ssc`.
- **Suspect window:** whatever changed JvmGen's call-with-block emission between the
  main checkout's stale binary (~2026-07-03) and current origin/main. Not caused by
  the tkv2 slice (repro has none of its commits). NOTE: rebuild the shared main
  checkout's bin/ssc after fixing — its staleness masks this class of regression
  in any corpus run executed from the main checkout.

## jvmgen-handle-result-mainpath — `fixed` (2026-06-15, all contexts incl. Any-taint propagation)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (effect-result × main-path composition probes).
- **Symptom:** a `val r = handle(...)` (Any-typed `_handle` result) used in a NON-arithmetic main-path
  context fails JVM scala-cli; interp + JS run it fine. A cluster of related JVM-only divergences:
  - `r match { case _ => r * 2 }` → `value * is not a member of Any` (`emitExprDeep` had no `Term.Match` case → arm fell to `.syntax`).
  - `if r > 5 then r * 10 else 0` → `Found Any / Required Boolean` (the `_binOp(">", r, 5)` cond wasn't cast to Boolean).
  - `dbl(r)` (user fn) → `Found Any / Required Int` (main-path call didn't cast the arg to the callee param type; only the CPS path did).
- **FIXED (2026-06-15):** in `emitExprDeep` — added a `Term.If` Boolean cast when the cond is an Any-typed handle-result comparison, a `Term.Match` case that recurses scrutinee + arm bodies + guards, and a `Term.Tuple` case; cast main-path call args that reference a handle-result val to the callee's `calleeParamType` (reusing the CPS `localDefSigs`/`depDefs` index). Routed any term that references a handle-result val through `emitExprDeep` via a new `termRefsHandleResultVal` in `termNeedsCustomEmit`. Guard: `CrossBackendPropertyTest` "effect-result main-path composition cross-backend" (match / if-cmp / fn-arg / multishot-arith / nested-handles — interp == JS == JVM).
- **ALSO FIXED (2026-06-15, Any-taint propagation):** the two formerly-deferred contexts:
  - `List(r, r).sum` → `No given Numeric[Any]` — broadened the `emitExprDeep` `_anyCall0` Select routing from "qual IS a handle-result-val Name" to "qual REFERENCES one" (`termRefsHandleResultVal(qual)`), so `List(r, r).sum` → `_anyCall0(List(r, r), "sum")`.
  - tuple-accessor arithmetic `val t = (r, r+1); t._1 + t._2` — added `anyTypedVals`, a superset of `handleResultVals` populated by Any-taint PROPAGATION: an untyped val whose rhs references an Any-typed val (`val t = (r, r+1)`) is itself Any-typed. The routing predicates now key off `anyTypedVals`, and the arith-operand check also recognizes `Select(anyTypedVal, _)` (so `t._1 + t._2` lowers to `_binOp`). Only ever non-empty for effect programs (seeded by `handleResultVals`), so pure code is unaffected. Guard: `result-in-list-sum` + `result-in-tuple` added to the composition test (interp == JS == JVM).

## agent-streaming-test-port-collision — `fixed` (2026-06-15, 26dae7699)
<!-- status: fixed
     kind: apparatus
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex during `rozum-agent-endpoint-pool` regression check.
- **SHA at filing:** `2334d0be4` (feature worktree).
- **Symptom:** running the sync and streaming agent SDK suites in the order
  `AgentSdkInterpreterTest AgentSdkStreamingInterpreterTest` aborts the streaming
  suite with `java.net.BindException: Address already in use`.
- **Repro:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest"`.
- **Root cause:** `examples/rozum-agent.ssc` binds `19694`, the same port as
  `AgentSdkStreamingInterpreterTest`; when the sync suite ran first, the
  streaming suite could immediately rebind the same port and abort.
- **Fix:** moved `AgentSdkStreamingInterpreterTest` to port `19698`.
- **Verify:** `cd /Users/sergiy/work/my/scalascript/.worktrees/feature/rozum-agent-endpoint-pool && sbt "backendInterpreterPluginTests/testOnly scalascript.AgentSdkInterpreterTest scalascript.AgentSdkStreamingInterpreterTest"` — 14 tests passed in the formerly failing order.



---

## jvmgen-handle-result-arith — `fixed` (2026-06-15)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (new effect-composition shapes — handle result fed into main-path arithmetic).
- **Symptom:** using a `val` bound to `handle(...)` as an operand of an arithmetic/comparison infix fails JVM scala-cli with `value * is not a member of Any`. `handle(...)` lowers to `_handle(...)` (returns `Any`), so `val r = handle(...){…}; println(r * 2 + base)` emits `r * 2` raw on the Any-typed result, which Scala 3 rejects. interp + JS run it fine.
- **Repro:** a one-shot effect program ending `val r = handle(loop(n)){ case Counter.tick(resume) => resume(k) }; println(r * 2 + base)` (or two results: `println(r1 + r2)`).
- **Root cause:** `termNeedsCustomEmit` only routed a handle-result-val through `emitExprDeep` (where `ApplyInfix` lowers `+ - * / % < > <= >=` to `_binOp`) when the val appeared in a 0-arg method `Select` (`termContainsHandleResultCall`), NOT when it appeared as an arithmetic operand — so `r * 2` fell to `emitExpr`'s `.syntax` raw fallback.
- **FIXED:** added `termContainsHandleResultArith` (walks for a handle-result-val `Term.Name` used as an operand of an arithmetic/comparison `ApplyInfix`) and wired it into `termNeedsCustomEmit`; the existing `emitExprDeep` `ApplyInfix` → `_binOp` path then lowers it (nested arith re-fires the predicate via `emitCallArg`→`emitExpr`). Guard: `CrossBackendPropertyTest` effect sub-shapes 8 (`r*2+base`) and 9 (`r1+r2`), run through scala-cli on seeds 11/47 and 155/191. Property test green.

---

## jvmgen-multishot-handle-result-any — `fixed` (2026-06-15, 23a33c976)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (its multi-shot effect shape).
- **Symptom:** a method call on the result of `handle(...)` fails JVM scala-cli with e.g. `value sum is not a member of Any` — `handle(...)` lowers to `_handle(...)` which returns `Any`, so `val all = handle(prog()){…}; all.sum` (typical for a multi-shot handler whose result is a `List`) doesn't type-check. interp + JS (dynamically typed) run it fine.
- **Repro:** a `multi effect NonDet` program ending `val all = handle(prog()){ case NonDet.choose(opts, resume) => opts.flatMap(o => resume(o)) }; println(all.sum)`.
- **Severity / why deferred at filing:** harder than the emitCaseBody class — it is about the `_handle` RESULT type (Any), not the handler body. A real fix needed the codegen to know the handled-program's result type (here `List[Int]`) and cast, or `_handle` to be generically typed; `List[Any].sum` would still need `Numeric[Any]`.
- **FIXED (23a33c976):** runtime `_anyCall0(recv, m)` dynamically dispatches 0-arg collection methods on an Any Iterable (numeric folds via `_binOp`); codegen tracks vals bound to `handle(...)` (`handleResultVals`), routes a `x.method` on them through `emitExprDeep` (via `termContainsHandleResultCall` in `termNeedsCustomEmit`) → `_anyCall0`. Property test re-added the multi-shot `all.{sum,max,min,length}` shape as the guard; 96 tests green.
---

## jvmgen-effect-handler-arg-arith — `fixed` (2026-06-15, 7c843b121)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (its broadened multi-arg / arithmetic effect handlers).
- **Symptom:** a handler that does arithmetic on op-args, e.g. `case Combine.mix(a, b, resume) =>
  resume(a * b + 1)`, fails JVM scala-cli with `value * is not a member of Any`. The op-args are
  bound `val a = _args(0)` (type `Any`) and `emitCaseBody` had no arithmetic case → `a * b`
  emitted raw, which Scala 3 rejects on `Any`. interp + JS run it fine.
- **Repro:** `println(handle(loop(5)) { case Combine.mix(a, b, resume) => resume(a * b + 1) })`
  for `effect Combine: def mix(a: Int, b: Int): Int` → scala-cli "value * is not a member of Any".
- **FIXED (7c843b121):** `emitCaseBody` now lowers an arithmetic/comparison `ApplyInfix` to the
  `_binOp("op", l, r)` runtime helper (same as `emitExpr` for Any operands; mirrors the existing
  `::` Any-cast case). Guard: `CrossBackendPropertyTest` effect shapes (arg-carrying / two-op) run
  through scala-cli. 101 effect+jvmgen tests green.
- **ALSO FIXED (78d1ce178) — control-flow case:** an `if` in a handler body with a comparison on Any-typed op-args (`if k > 2 then resume(k) else resume(0)`) — `emitCaseBody` had no `Term.If` case so `k > 2` emitted raw. Added a `Term.If` case that recurses (lowers `k > 2` to `_binOp`) + casts the condition to `Boolean`. Property test gained a conditional-resume effect shape (run through scala-cli).

---

## jvmgen-handle-in-arg-position — `fixed` (2026-06-15, 91fc574f5)
<!-- status: fixed
     kind: bug
     lane: jvm
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (xbackend-property-equivalence — the generated
  cross-backend differential, found this on its first effects run).
- **Symptom:** JVM codegen emits a `handle(...)` effect expression RAW (unqualified) when it
  appears in **call-argument position**, e.g. `println(handle(body){cases})`, so scala-cli fails
  with `Not found: handle - did you mean _handle?`. interp **and** JS run it correctly.
- **Works (idiomatic):** binding the result first — `val r = handle(body){cases}; println(r)` —
  lowers correctly to `_handle(() => body, Set(...), Map(...))`. Only the inline/nested form breaks.
- **Repro (minimal):**
  ```scalascript
  effect Counter:
    def tick(): Int
  def loop(n: Int): Int ! Counter =
    var acc = 0
    var i = 0
    while i < n do
      acc = acc + Counter.tick()
      i = i + 1
    acc
  println(handle(loop(3)) { case Counter.tick(resume) => resume(2) })
  ```
  `ssc emit-jvm` / scala-cli the output → "Not found: handle". Change last line to
  `val r = handle(loop(3)) { ... }
println(r)` → works.
- **Root cause:** `JvmGen` lowers `handle` via `emitExpr` (case `handle(body){cases}` →
  `emitHandleForm`) and special-cases the `val x = handle(...)` / statement forms, but an
  effectful term nested inside another `Term.Apply` arg falls to the `.syntax` raw fallback
  instead of recursing the arg through `emitExpr`/`emitHandleForm`. (Likely the same for other
  effectful forms — `runAsync`, etc. — as direct call args.)
- **Severity:** low — narrow corner case, trivial workaround (bind to a `val`). Fix touches the
  core CPS emission path (would need care vs the 33 JvmGenEffects tests), so deferred from the
  property-test slice that found it.
- **FIXED (91fc574f5):** `termContainsEffectExpr` (walks children for any effectful sub-expr) added to `termNeedsCustomEmit` so a `handle`/effect nested in a call arg routes through `emitExprDeep` and lowers to `_handle(...)`. Regression guard: `CrossBackendPropertyTest` effect kind uses the inline `println(handle(...))` form (interp==JS==JVM via scala-cli). 119 effect+jvmgen tests green, no regression.

---
