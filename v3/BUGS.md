# v3 bugs

Defects whose FIX goes in `v3/`. Layout and entry format: `specs/work-tracking-layout.md`,
`specs/bugs-index.md`. Cross-module defects — the same defect in more than one implementation —
belong in the repository-root `BUGS.md` instead, not here.

Query: `scripts/bugs-report --module v3`.

## point-free-class-method-reference-never-eta-expands — a bare selection on a class instance always dynamic-Invoked with zero args

<!-- status: open
     lane: v3
     kind: feature
     area: front
     gate: v3/tests/front/eta-expansion-class-method.ssc
     found-by: claude-code
     found-at: 2026-08-29
     fixed-in: - -->

Found while checking whether the repository-root `BUGS.md` entries
`point-free-class-method-never-eta-expands-on-native` (v2) and `-on-int` (v1) — both already
fixed independently this session — also affect v3. They do not reproduce as *regressions*: v3
never implemented this at all, on EITHER of its lanes.

**Repro**, minimized from the same source that found the v1/v2 pair:

```scalascript
case class ConstMonoid(z: Int):
  def combine(a: Int, b: Int): Int = a + b

def main(): Unit =
  val cm = ConstMonoid(0)
  println(List(1,2,3).foldLeft(0)(cm.combine))
```

`v3/ssc3 run` (own executor): `method 'combine' on ConstMonoid(0)' is not implemented by v3's
executor`. `v3/ssc3 run --bridge` (v2 VM): `Cons.foldLeft was called but does not exist, and the
result reached output` — the fold's OWN dispatch works; its folding-function argument is what
never resolved.

**Root cause — a missing case in `Lower.scala`'s `Expr.MethodCall` lowering, not a regression in
an existing one.** That lowering already has two `Nil`-argument arms, each building a `Switch`
with one arm per class that could own the name and a dynamic-`Invoke` default: one when some
class's METHOD of that name has arity 0 (a genuine nullary call), one when some class has a
FIELD of that name. A bare selection whose name matches a method wanting ONE OR MORE arguments —
exactly the point-free case — matched NEITHER arm (0 declared params, needed for the first; not
a field, for the second) and fell straight through to the generic fallback, emitting a dynamic
`Invoke` with an empty argument list. That is indistinguishable, at the VM boundary, from a
genuine zero-arg call — v3's own executor has nothing that turns it into a function value, and v2
never even had the class's methods to fall back on (this lowerer builds a static per-tag
`Switch`, never `__regmethod__`-registering anything in v2's tagged-method table, unlike v2's own
front — the mechanism the repository-root entries' fix builds on).

**Two workarounds pin the diagnosis, same shape as the v1/v2 pair:** `object M: def add(...)`
methods point-free already worked (`v3/tests/front/eta-expansion.ssc`, an EARLIER fix — a
singleton object has exactly one owner, so no per-tag ambiguity ever existed for it); an explicit
lambda `(a, b) => cm.combine(a, b)` also always worked, for any class.

**Fixed** by a third `Nil`-argument arm in the same `Expr.MethodCall` match, guarded on `some
class's method of this name takes 1+ parameters`: the same per-owner `Switch` shape, but each arm
is `Instr.MkClos(dst, functionIndex, captures = [receiver])` instead of `Call`+`Move` — a closure
over the class's own already-lifted function, closing over the receiver, whose arity comes out to
exactly the method's declared parameter count. `MkClos` already had a correct `BridgeV2`
translation (built for lambda lifting), so no backend change was needed — both v3's own executor
and its v2 bridge pick it up for free. New coverage:
`v3/tests/front/eta-expansion-class-method.ssc` (the `object`-method test's class-instance
sibling; a second class overloading the same method name pins that dispatch is by TAG, not name
alone). Measured on the full conformance corpus: bridge lane 259 → 275 / 375, exec lane → 279 /
375 (`v3/corpus-report.sh` / `--exec`); the one remaining DIFF and one remaining CRASH on the exec
lane are unrelated, pre-existing (confirmed against `origin/main` before this fix): a
division-by-zero formatting case and an unregistered-native-result-field case scoped to the `int`
backend only.

## a-type-annotation-runs-past-its-line-and-steals-the-next-definition — the definition vanished, silently

<!-- status: fixed
     lane: v3
     kind: bug
     area: front
     gate: v3/tests/front/abstract-val-declares.ssc
     found-by: claude-code
     found-at: 2026-08-19
     fixed-in: 11ab3c2cd -->

**`skipTypeAnnotation` HAD NO LINE LIMIT.** It stops at `=`, `,`, `;`, `{` or `}`, and a newline was
not among them — so from a `val` with no right-hand side it walked forward until it found somebody
ELSE's `=`:

    extern class UploadedFile:
      val name: String

    def main(): Unit = println("ok")

    (program
      (trait "UploadedFile" (parents) (methods))
      (val "name" (call "println" (str "ok"))))

`main` is GONE — its body became the declaration's right-hand side — and the tree printed with no
complaint. The brackets in `def main(): Unit` balance, so the depth counter returns to zero and does
not protect anything. Every declaration in that block after the first was lost the same way.

**NOBODY SAW IT BECAUSE THE ONE FILE THAT USES THE CONSTRUCT ENDS THE BLOCK RIGHT THERE.**
`std/http.ssc:169-175` is a fenced literate block whose fence closes immediately after the last
`val`, so the annotation ran to end-of-input and the front reported the OTHER symptom — `the
abstract val 'name' is outside SSC3 core Tier 0`. Two corpus cases carried that refusal and it read
like a Tier 0 question. It was two defects wearing one message: a boundary decision AND a theft.

**IT IS THE THIRD SITE OF ONE MISSING RULE, all three found on 2026-08-19.** `postfix` guards a
trailing `(` with `c.peekLine == c.prevEndLine` and explains it; `parseIdOrCall` did not have it
(`uniml-applies-an-identifier-to-a-parenthesis-on-the-next-line`); this is the third. The rule is
ssc1-front's: layout inserts `;` at a newline, so nothing after one continues the construct.

**FIXED** by stopping at depth 0 when the next token starts on a later line than the previous one
ended. A genuine multi-line type is inside brackets, so `depth > 0` leaves it alone — `: Map[String,`
newline `Int]` still parses.

## v3-own-front-has-no-extern-class — `expected an expression, found class`

<!-- status: open
     lane: v3
     kind: feature
     area: front
     gate: v3/front-diff.sh (curried-extern-import and js-http-client-config are declared uniml-only)
     found-by: claude-code
     found-at: 2026-08-19 -->

**v3's own front knows `extern` only before a top-level `def`.** `extern class UploadedFile:` — the
way `std/http.ssc:169` and `std/geo.ssc:101` describe a host type's fields — is refused with
`expected an expression, found class`.

**IT BECAME VISIBLE WHEN THE OTHER FRONT STOPPED REFUSING.** Until 2026-08-19 the uniml front
refused those files too, at the abstract `val` inside the class, so the two cases were `neither` and
the differential never compared them. With the declaration admitted, uniml reads them and v3 does
not: the one-sided count rose 67 -> 69 and the capability gate named both by name.

**WHAT UNIML DOES WITH IT IS NOT A MODEL TO COPY.** `extern class C: def f(): Int` becomes an EMPTY
trait plus a top-level `def f` — the members are LIFTED out of the class rather than attached to it,
which is why an abstract `val` written inside one arrives at `UniFront`'s top-level sorter with no
context left. Whatever v3's front does here should attach them.

## abstract-val-in-an-extern-class-is-a-field-declaration — a declaration emits nothing

<!-- status: fixed
     lane: v3
     kind: feature
     area: front
     gate: v3/tests/front/abstract-val-declares.ssc
     found-by: claude-code
     found-at: 2026-08-19
     fixed-in: 11ab3c2cd -->

**`val name: String` WITH NO `=` DECLARES that a value exists and that someone else provides it.**
The owner admitted it on 2026-08-19, second of three Tier 0 groups. It emits nothing: `std/http.ssc`
and `std/geo.ssc` write host types this way, which is the same fact `registerFieldNames` carries on
the plugin side, and the field is read at run time through the host.

**ONE SHAPE ADMITTED, ONE DELIBERATELY NOT.** A TRAIT's abstract state stays refused, by both fronts
and with its own message — `20-core-language.md` and `UniFront`'s own comment already said v3's
traits carry methods, not abstract state.

**AND THE GATE CAUGHT ME PUTTING THAT BACK.** My first version of the v3-front half returned an empty
statement list for the declaration, and `parseMembers` counts non-`def` members by counting
statements — so a trait's abstract `val` produced zero of them and was accepted and DROPPED. That is
the exact defect `front-capability-gate.sh` records as already fixed once ("accepted a trait's
non-`def` member and dropped it SILENTLY"), and its `absval` probe went red on the first run after my
change. An empty parse is not the same as no member, and the arm now says so.

**BOTH FRONTS READ IT**, which is why the fixture is two-sided and no divergence is declared for it:
teaching only the projection would have cost a declared one-sided row and a raised ceiling — a
weakened guard bought to place a test.

## operator-outside-tier0-refuses-a-library-operator — the core's set was the language's set

<!-- status: fixed
     lane: v3
     kind: feature
     area: front
     gate: v3/tests/front/operator-as-method.ssc
     found-by: claude-code
     found-at: 2026-08-19
     fixed-in: fa93acc63 -->

**`Lower.binOp` threw for any operator outside a fixed list** — `+ - * / % < <= > >= == != & | ^ <<
>> >>>` — so `replyTo ! msg` was refused with `operator '!' is outside SSC3 core Tier 0`. Three
corpus cases stopped there. The list is the operators the CORE implements as instructions; treating
it as the operators the LANGUAGE has is the defect.

**THE MECHANISM WAS ALREADY THERE, for the other half of the alphabet.** An ALPHANUMERIC infix
operator has lowered to a method call for a long time — `a to b` is `a.to(b)`, with a comment saying
so — and `++` had an arm of its own doing exactly that. So the fix is to stop treating the symbolic
operators as a closed set, not to add a mechanism: `coreBinOp` ANSWERS `Option[BinOp]` and the caller
lowers a `None` to `Invoke`. The `++` arm is deleted rather than moved, because it is now one case of
the rule instead of an exception to it — verified byte-identical before and after on `List ++ List`
and on `"a" ++ "b"`, which the executor does not support either way.

**AND THE ADAPTER HAD TO LEARN THE OTHER SPELLING, or this would have been a regression.** v2 does
not treat `!` as a member: `Prims.arithOp` routes it to the registered `actor.send`. Lowering to a
call therefore turned an honest refusal into `method '!' on <handle Mailbox>` escaping as an uncaught
`ExecThrow` — a CRASH where there had been an UNSUPPORTED. `V2Fleet.methodOn` now answers a
one-argument SYMBOLIC name through `arithOp`, which is where the two models meet, and
`actors-cluster-discovery` and `actors-distributed-basic` run.

## infix-application-does-not-reach-a-declared-class-method — `b add 2` fails where `b.add(2)` works

<!-- status: open
     lane: v3
     kind: bug
     area: runtime
     gate: none yet
     found-by: claude-code
     found-at: 2026-08-19 -->

**THE SAME CALL, TWO SPELLINGS, TWO ANSWERS:**

    case class Box(v: Int):
      def add(other: Int): Int = v + other

    Box(40).add(2)    ->  42
    Box(40) add 2     ->  method 'add' on #8(40)' is not implemented by v3's executor

**PRE-EXISTING, and measured as such** — identical on `origin/main` with the operator work stashed.
It is filed here because that work is what put a spotlight on it: making a symbolic operator lower
to `Invoke` is only worth what `Invoke` can reach, and on a user-declared class method it reaches
nothing. The two corpus cases the operator change fixes are host handles, where the fleet answers;
a user type still cannot define an operator that works.

So the infix arm lowers to `Instr.Invoke(name)` while a selection lowers to something the executor
resolves against the class's own method table. One of those two paths knows about declared methods
and the other does not.

## v3-front-diff-ceiling-is-derived-by-word-counting-and-a-comment-changes-it — 23, 76 or 83 for one list

<!-- status: fixed
     lane: v3
     kind: bug
     area: build
     gate: v3/front-diff.sh (the ceiling is re-derived from the declared list)
     found-by: claude-code
     found-at: 2026-08-18
     fixed-in: 8d3067169 -->

**THE ONE-SIDED CEILING IS NOT A CONSTANT — it is re-derived from the names declared in
`v3/front-capability-gate.sh`,** so that declaring a case both raises the ceiling and names the
reason in one edit. It was derived like this:

    m = re.search(r"declare -a " + var + r"=\(([^)]*)\)", s, re.S)
    tot += len(m.group(1).split())

which reads the text between the first bracket and the first CLOSING bracket, and counts its WORDS.
Neither half survives a comment written inside the array:

    the list, untouched                     76      (correct)
    + a comment citing `foo.ssc (here)`     23      the bracket ENDS the list; 53 names vanish
    + the same comment without brackets     83      the prose is COUNTED as seven more names

**BOTH DIRECTIONS ARE SILENT, and the downward one is the dangerous one.** A ceiling that drops
turns green into red for work that is fine — I spent a cycle chasing a rise to 76 that had not
happened, because the ceiling had fallen to 63 in the same commit that grew the list to 76. A
ceiling that RISES is worse in kind: it admits regressions nobody declared, and a comment is exactly
the sort of edit nobody re-measures after.

**FIXED IN 8d3067169 — read the list rather than match it:** scan from the `declare -a` line, strip `#`
comments per line, stop at the first bracket that survives stripping, and REFUSE — rather than
count — if the list is unterminated or holds a token that is not a name. The old silent fallback to
the literal `85` now applies only when the capability gate is ABSENT; present-but-unreadable fails
loudly, because a fallback that cannot tell those apart is how an unreadable list passes for a
ceiling.

## v3-corpus-report-degrades-to-a-weaker-front-without-saying-so — half an hour comparing two FRONTS

<!-- status: fixed
     lane: v3
     kind: apparatus
     area: build
     gate: v3/corpus-report.sh (refuses when uniml.cp is absent and the front was not chosen)
     found-by: claude-code
     found-at: 2026-08-19
     fixed-in: a39986c8e -->

**`v3/.jars/uniml.cp` IS GITIGNORED AND PER-CHECKOUT, so every fresh worktree starts without one** —
and `corpus-report.sh` responded by measuring v3's own front and printing a number in exactly the
shape of a uniml number:

    front_used="v3"
    if [ "${SSC3_FRONT:-auto}" != "v3" ] && [ -s "$ROOT/v3/.jars/uniml.cp" ]; then … front_used="uniml"

**MEASURED, not imagined.** Taking `origin/main` as a control for the JVM-interop change, I made the
control a fresh worktree and built it with `ssc3 selftest` — which does not build this file. The
control ran v3's own front and the experiment ran uniml. The tell was in the refusal histograms,
which shared almost nothing: the control was full of `expected an expression, found [`, `dedent to
column 12` and `a catch arm binds one name at Tier 0`, none of which an import change can remove.
Four corpus runs, about half an hour, comparing two FRONTS rather than two commits.

**THE GATES ALREADY DECIDED THIS AND THE REPORT WAS NEVER GIVEN THE SAME TREATMENT** — a twin, one
side fixed. `v3/exec-gate.sh` and `v3/front-gate.sh` go RED rather than skip when this file is
missing, and `v3-gates-open-red-in-every-fresh-worktree-because-uniml-cp-is-per-checkout` calls that
deliberate and correct: a gate that goes green with fixtures unrun reports less than it claims. A
report that prints a number from the weaker front reports something worse than less — a number that
COMPARES.

**REFUSED AHEAD OF THE BUILD**, so it costs a tenth of a second rather than the several minutes of
packaging it would otherwise sit behind, and it names the one-line fix. `SSC3_FRONT=v3` still
measures v3's own front, silently and on purpose; what is refused is the DEFAULT silently becoming
that. The front-selection block itself is unchanged — 31 insertions, zero deletions — so a checkout
that HAS the classpath behaves identically.

## uniml-applies-an-identifier-to-a-parenthesis-on-the-next-line — the DEFAULT front refuses a tuple line

<!-- status: fixed
     lane: v3
     kind: bug
     area: front
     gate: v3/front-diff.sh (corpus DISAGREEMENTS, ceiling 0)
     found-by: claude-code
     found-at: 2026-08-19
     fixed-in: 3c880698a -->

**THE FRONT EVERY PROGRAM USES BY DEFAULT REFUSES THIS:**

    def g(k: Int, xs: Any): Any =
      val value = xs
      (k, value)

    uniml  ssc3: 3:7: unknown name 'value'
    v3     (7, 1)

`Nil` on the next line is applied to `(k, value)`, so the tuple becomes an argument list and `value`
never becomes a binding.

**ONE OF TWO DECISION SITES HAD THE RULE.** `postfix` guards chained application with
`c.peekLine == c.prevEndLine` and states the reason in a comment — ssc1-front's layout inserts `;` at
a newline, so a `(` on a LATER line begins a fresh statement. `parseIdOrCall`, which is where an
application is built for a BARE IDENTIFIER, had no such guard and applied whatever `(` came next,
however many lines away.

**A LITERAL HID IT FOR MONTHS.** The right-hand side has to be a bare NAME to reach that site at all:

    val v = 0        followed by a tuple line   fine — a literal takes no argument list
    val v = List(1)  followed by a tuple line   fine — the call already consumed its brackets
    val v = k + 1    followed by a tuple line   fine
    val v = xs       followed by a tuple line   REFUSED

**FOUND BY FIXING THE OTHER FRONT.** `std/mapreduce/shuffle.ssc:438` is written this way, and while
v3's own front could not read the file at all the two fronts were never compared on it. The moment
`v3-own-front-cannot-parse-a-parenthesised-match` was fixed, `front-diff` reported
`corpus DISAGREEMENTS rose to 1` on `distributed-shuffle` — and v3 was the one that was right.

**FIXED** by giving `parseIdOrCall` the same `c.peekLine == c.prevEndLine` test its twin already had.

## v3-own-front-cannot-parse-a-parenthesised-match — two lines, and it is a capability gap

<!-- status: fixed
     lane: v3
     kind: feature
     area: front
     gate: v3/tests/front/unlayered-match-arms.ssc
     found-by: claude-code
     found-at: 2026-08-18
     fixed-in: 3c880698a -->

**v3's OWN front cannot read a `match` used as a parenthesised expression:**

    val v: Any = 1
    val s = (v match
      case _ => "a"
    ).length

    v3     ssc3: expected an expression, found )
    uniml  parses

`std/mapreduce/distributed.ssc:424` is the shape in the wild — `}).asInstanceOf[…]` closing a
parenthesised `match`.

**THE TITLE OF THIS ENTRY IS TOO NARROW, corrected 2026-08-19 when the fix was written.** The
defect is not about `match` in brackets: it is an ARM LIST WITH NO LAYOUT OF ITS OWN. Inside round
brackets the lexer suppresses INDENT, DEDENT and NEWLINE — `Lexer.scala` skips a continuation line's
indentation because "its width means nothing here" — so an arm list written there has neither a `}`
nor a DEDENT to end it, and only a closing bracket can. Adding `)` and `]` as terminators moved the
refusal from line 424 to line 464 rather than clearing it, because the shape in the wild is one step
further in:

    (receive {
      case Exit(pid, reason) =>
        val a = assignments.find { case (_, p, _) => p == pid }
        a match                       // <- THIS list is the unlayered one
          case None    => …
          case Some(x) => …
    }).asInstanceOf[DistributedResult[Any]]

The INNER `match` is what has no terminator, and the token that ends it is the OUTER `}`. Chasing
the refusal one line at a time is what turned a guess into the rule: any closing bracket ends an
unlayered arm list.

**IT WAS INVISIBLE UNTIL A JVM PACKAGE BECAME IMPORTABLE.** The five `distributed-*` cases died at
`import scalascript.typeddata.…` on BOTH fronts, so they counted as `neither` rather than one-sided.
With the import admitted, uniml reads them and v3's own front stops here — the one-sided count rose
71 -> 76 and `front-diff` said so on the first run.

**DECLARED RATHER THAN HIDDEN, and the distinction matters.** `markdown-html` was over the same
ceiling a day earlier and was NOT declared, because that was a lexer DEFECT — an identifier and a
string on two lines read as an interpolator. This is a CONSTRUCT one front has and the other does
not, which is exactly what `KNOWN_CONF_UNIML_ONLY` is for.

## v3-fleet-classpath-unvalidated-and-silent-in-ci — `Not found: ssc` ×75, shown as "CANNOT RUN" two layers away

<!-- status: fixed
     fixed-in: 99122b7f4
     lane: v3
     area: build
     kind: bug
     gate: .github/workflows/v3.yml (the new "Build the plugin fleet" step)
     found-by: claude-code
     found-at: 2026-08-18 -->

**v3.yml went red twice this morning (46bccf742, de11d1380), and the visible symptom named neither
the file nor the cause.** The capability job printed `front-capability-gate: CANNOT RUN — only these
fronts are registered: v3`; the gates job showed fixtures failing with empty output. The actual
failure sat in the middle of the log: compiling `v3/plugins/V2Fleet.scala` produced **75 errors,
`Not found: ssc`** — the v2 core classes were absent from `plugins.cp`.

**THE CHAIN, each link fine on its own terms:**

1. `46bccf742` turned the fleet ON by default, so `fleet_cp` now builds `plugins.cp` on first use
   with output DISCARDED — by design, "a build failure has no business making `ssc3 run` fail".
2. `plugin-classpath.sh` took `tail -1` of each module's sbt stdout AS the classpath, unvalidated.
   On CI's cold sbt the last line was not a classpath; the file was written anyway — non-empty and
   wrong.
3. `uniml_classpath` compiled `v3/plugins` against it, failed, and the driver's silent fallback
   unregistered the UNIML FRONT — so the front everyone tests vanished because a RUNTIME fleet's
   classpath was garbage. Locally everything passed: warm sbt prints the export alone.

A plausible non-empty file is worse than an absent one, and `[ -s "$PLUGIN_CP_FILE" ]` cannot tell
them apart.

**Two changes, both landed with this entry:**

- `plugin-classpath.sh` VALIDATES every `:`-separated entry of every exported line — each must
  exist on disk, or the script refuses naming the module and the offending entry, and writes
  nothing. Control run: a planted `[warn] …` line is refused with the module's name and
  `plugins.cp` stays absent; the good path still writes 8 modules / 112 entries, then
  capability-gate OK and exec-gate GREEN 90 with the fleet on.
- `v3.yml` gets a loud `Build the plugin fleet` step in both jobs, before the gates. The fleet is
  the default users get, so CI must measure it or say loudly that it cannot — a silent fleet-off
  would shrink what the gates measure by the owner's 20 cases with nothing going red.

**OPEN, not fixed, because the root question is still unanswered:** WHAT did CI's sbt print as the
last line? The validation converts the next occurrence into a one-line answer naming the module and
the entry; close this when a red "Build the plugin fleet" step (or a clean week of green ones) has
shown it. If the loud step never fires red, the cold-runner line was transient and this entry
closes on that evidence.

**CLOSED 2026-08-18, ON EXACTLY THE EVIDENCE IT ASKED FOR.** The loud step fired on its first CI
run and named the culprit: the last line of `sbt -batch --error "export …"` on a cold runner is
**`ESC[0J`** — supershell's erase-display control sequence — so `tail -1` captured terminal
decoration instead of a classpath. A warm local sbt never prints it, which is the whole
local-green/CI-red split. Fixed in `99122b7f4`: `-Dsbt.supershell=false` removes the source, an
escape-strip plus drop-blank-lines guards the next decoration sbt invents, and the
exists-validation stays — it is what turned two days of archaeology into a one-line answer.
Control: a planted `ESC[0J` trailing line is stripped and the real classpath line above it wins;
good path 8 modules / 112 entries / 0 missing.

## v3-a-marker-is-a-compile-time-rewrite-nothing-in-a-library-can-answer — Focus, direct, prism

<!-- status: open
     lane: v3
     kind: feature
     area: front
     gate: v3/corpus-report.sh (lenses, optic-polish, tagless-direct-syntax and six more)
     found-by: claude-code
     found-at: 2026-08-17 -->

**Nine corpus cases are refused with `the marker '…' is outside SSC3 core Tier 0`, and the name in
that message is not a construct — it is a placeholder the front leaves where a REWRITE should have
happened.** Identified 2026-08-17 because neither the owner nor I could say what a "marker" was:

- `focusmarker` (5 cases — `lenses.ssc:31`, `optic-polish.ssc:23`): `Focus[Person](_.age)`, an
  OPTICS constructor. `_.age` is a field selector that has to become a lens over the named field.
- `direct` (3 cases — `tagless-direct-syntax.ssc:17`): `direct[Option] { x = Some(10); … }`, a
  DIRECT-STYLE monadic block that has to become a `flatMap` chain.
- `prism` (1): the same family as `Focus`.

**SO NO LIBRARY CAN ANSWER THEM.** Both are compile-time rewrites over the shape of the expression —
a field selector into a name, a block of bindings into a chain — and a function in `std` never sees
either shape. This is what separates them from the interpolator question, where the answer WAS a
library function once the front stopped hardcoding prefixes.

**THE REFUSAL IS CORRECT AS IT STANDS**, which is why this is a feature entry and not a bug: v3 says,
with a position, that it will not pretend. Nine cases is the largest single group behind the Tier 0
boundary, and closing it means designing front rewrites rather than widening a list.

## v3-an-interpolator-prefix-is-hardcoded-in-both-fronts — it is a call now, definable in a library

<!-- status: fixed
     lane: v3
     kind: feature
     area: front
     fixed-in: f92e3c644
     gate: v3/front-diff.sh (interpolator-is-a-call, run on BOTH fronts)
     found-by: claude-code
     found-at: 2026-08-17 -->

**FIXED IN f92e3c644.** `pfx"a${x}b"` is `pfx(List("a", "b"), List(x))`, so

    def html(parts: List[String], args: List[Any]): String = …

defines an interpolator with nothing added to the kernel. `s` stays a node — hot, fixed in meaning,
and the one the AST was built for. Parts are always one longer than args, as in Scala.

**THE CORPUS IS UNCHANGED AND THAT IS THE HONEST RESULT:** exec 259 DIFF 1 CRASH 3, bridge 257
DIFF 2 CRASH 1, before and after. The six `html"…"`/`md"…"`/`f"…"` cases moved from `outside SSC3
core Tier 0` to `unknown function 'html'`. Nothing new passes; what changed is that the refusal now
names something a program can define, and defining them in `std` is ordinary library work rather
than a language decision.

**THE FIXTURE CAUGHT TWO MISTAKES THAT A HAND PROBE DID NOT**, which is why it went in with the
feature and why `front-diff` runs it on both fronts:

1. v3's own lexer treated only `s`, `f` and `raw` as interpolators, so `tag"…"` lexed as an
   identifier and a separate string — while the comment directly above that guard stated the general
   rule. Harmless while every other prefix was refused anyway; a front divergence the moment one
   became a call.
2. Broadening the rule then BROKE THREE FILES that used to read — `content` and both
   `std-ui-native-html-lambda` cases — which interpolate with TRIPLE quotes. `front-diff` reported
   one-sided files rising 77 -> 79, and the saved one-sided list named all three. Triple-quoted
   interpolators are lexed now, with `${…}` going through the same hole parser so the two spellings
   cannot drift.

**WHAT REMAINS FOR THE SIX CASES:** somebody writes `def html(parts, args)` and `def md(parts, args)`
in `std`. That is a library task with no front or kernel change behind it.
## v3-a-plugin-global-that-is-a-plain-value-cannot-answer-a-zero-arg-extern — it can now

<!-- status: fixed
     lane: v3
     kind: bug
     area: runtime
     fixed-in: c8138d90c
     gate: v3/corpus-report.sh --exec (std-os-doc-import)
     found-by: claude-code
     found-at: 2026-08-17 -->

**FIXED IN c8138d90c.**

    exec    257 DIFF 1 CRASH 5   ->   258 DIFF 1 CRASH 4
    bridge  255 DIFF 2 CRASH 1   ->   256 DIFF 2 CRASH 1

Against a control taken on origin/main immediately before the change; every gate green.

**A ZERO-ARGUMENT EXTERN AND A CONSTANT ARE THE SAME THING FROM THE CALLER'S SIDE.** `extern def
cwd: String` does not say whether the provider computed the value once at install or would compute
it per call, and no program can tell — so a datum in `globalValues` answers a nullary call. Arguments
are still refused BY NAME: the reason the `ClosV` test existed stands, since passing a datum
arguments is a program error that must say so rather than become a class cast inside the bridge.

**BOTH LANES, because lowering is shared** and emits one `Prim` for both — the same arm goes into
v2's handler table through `V2Cli`.

**`v2NativeHostPlugin` IS WIRED HERE FOR THE FIRST TIME**, the eighth module of the twenty-six the
fleet could carry. The rule from `v3-the-fleet-wires-two-plugin-modules-of-twenty-six` is unchanged:
a provider joins the list when v3 can both call it and carry what it returns.
## v3-uniml-reads-a-line-ending-name-and-the-next-line-string-as-an-interpolator — fixed; front-diff is green

<!-- status: fixed
     lane: v3
     kind: bug
     area: front
     fixed-in: 347672d38
     gate: v3/front-diff.sh
     found-by: claude-code
     found-at: 2026-08-16 -->

**FIXED IN 347672d38.** An interpolator now needs the same LINE as well as the same offset.
`peekAbutsNext` compared offsets alone and that did not reject a line break — established by probe,
not by reading: a blank line between the two did NOT help while parenthesising the first expression
DID, which says the test was passing rather than the tokens being genuinely adjacent.

**THE PREFIX LIST WAS DELIBERATELY LEFT ALONE.** `isInterpPrefix` accepts any word — that is what
makes the accident reachable — but narrowing it to `s`/`f`/`raw`/`md`, as its own comment claims, would
break `html"…"`: genuinely adjacent, genuinely an interpolator, and it must keep its honest
`outside SSC3 core Tier 0` refusal instead of falling apart into two tokens.

**IT BUYS A GATE, NOT A CASE, and that is stated because the numbers say so.** The corpus is
IDENTICAL with and without the fix — bridge 255 DIFF 2 CRASH 1, exec 256 DIFF 1 CRASH 6 — since
`markdown-html` does not change bucket. What moves is `v3/front-diff.sh`: one-sided files 78 -> 77,
at the ceiling, GREEN. It had been RED on main.

**AND THE ALTERNATIVE WOULD HAVE HIDDEN IT.** `markdown-html` was the one file over the declared
capability list, so adding it to `KNOWN_CONF_V3_ONLY` turns the same gate green in one line — while
leaving the default front unable to read a line break between a name and a literal. The list is for
capabilities the fronts do not share, not for defects.
## v3-a-val-bound-to-another-val-does-not-type-the-receiver — two gaps, and neither half worked alone

<!-- status: fixed
     lane: v3
     kind: bug
     area: front
     fixed-in: e053c165f
     gate: v3/corpus-report.sh (indent-config-format)
     found-by: claude-code
     found-at: 2026-08-16 -->

**FIXED IN e053c165f.**

    bridge  control PASS 252  DIFF 4  CRASH 1   ->  PASS 254  DIFF 2  CRASH 1
    exec    control PASS 254  DIFF 1  CRASH 7   ->  PASS 255  DIFF 1  CRASH 6

Both floors FALL. `indent-config-format` was a wrong answer and passes now, and the bridge's DIFF
list is down to the two effects cases, which belong to another claim.

**TWO GAPS, NOT ONE, and that is why the obvious half changed nothing:**

    val itemAtCurrentIndent: Parser[Any] =
      Parser.readCtx { … }.flatMap(_ => p)             // a BLOCK, whose result is the call
    val firstItem: Parser[Any] = itemAtCurrentIndent   // a NAME bound to that block

A block's type is its result's — the receiver typer stopped at the block — and a binding types from
what it was bound to, which may itself have just typed, so the map iterates to a fixed point. Fixing
only the name chain left `itemAtCurrentIndent` untyped and therefore `firstItem` untyped as well.

**THE PREVIOUS VERSION OF THIS ENTRY NAMED THE WRONG CAUSE, and the correction is the lesson.** It
said the collector never reaches those bindings — read off a probe piped through
`sort -u | head -5`, in which `inits=1` sorts before `inits=3`. Re-run whole, the walker finds all
four bindings including both names; what it could not do was look inside a block or follow a name. A
conclusion from a truncated sample reads exactly like a conclusion from data, and it went into a
shared board before it was checked.

**WHAT IS LEFT.** `indent-block-statements` now runs and prints its first line before stopping — no
longer a crash, and no longer this defect.
## v3-an-extension-is-disabled-by-a-prelude-class-of-the-same-member-name — a type decides it now; one layer remains

<!-- status: fixed
     lane: v3
     kind: bug
     area: front
     fixed-in: dcc8e98f6
     gate: v3/corpus-report.sh (js-parser-combinator-choice)
     found-by: claude-code
     found-at: 2026-08-16 -->

**FIXED IN dcc8e98f6 by giving the decision to the RECEIVER'S TYPE, which is the only thing that can
make it.** `map` must stay the built-in for a `List` and become the extension for a `Parser`; a name
cannot tell those apart and the eligibility test was working from names alone. The new arm fires only
when the receiver's declared type equals the type the extension declares its own receiver to be, and
an untypeable receiver behaves exactly as before.

    bridge  control PASS 251  DIFF 4  CRASH 1   ->  PASS 252  DIFF 4  CRASH 1
    exec    control PASS 253  DIFF 1  CRASH 8   ->  PASS 254  DIFF 1  CRASH 7

`js-parser-combinator-choice` was a CRASH and passes now, so the executor's floor falls again. Every
gate green, including `front-diff` and `front-gate` — this touched both fronts.

**THE DECLARED RESULT TYPE IS NEW, AND READING IT IS NOT A BREACH OF I-2.** v3's parser discarded it
by design and uniml's AST had it all along with the projection throwing it away. The invariant says
there is no CHECKER at Tier 0 — not that a front may not read what a declaration says — and
`Param.tpe` has been read this way since the given-instance resolver needed it. It decides; it never
checks.

**FOUR THINGS WERE LOSING IT, each found by probe rather than by reading.** Object members
(`Parser.regex`) and top-level `extension` blocks build their `Def`s in separate arms of the
projection. The rewrite needs a FIXED POINT, because a top-down pass meets the outer `.map` while its
receiver is still an unrewritten `MethodCall`. And an object member arrives as a `MethodCall` on the
NAME `Parser` while the def it calls is `Parser.regex`, so a bare-name lookup missed all six sites.

**THE PRELUDE HALF TURNED OUT NOT TO NEED FIXING.** The plan was to stop `Dataset.map` from blocking,
by recording class origins in `Program.origin`. Once the receiver is typed that test is unnecessary:
a class member of another type is not a competitor, and one of the same type still wins. The
`Loader.scala` change was written, measured to be unobservable on its own, and dropped.

**ONE LAYER REMAINS AND IT IS THE SAME MECHANISM.** `indent-config-format` and
`indent-block-statements` now get past `map` and stop at `flatMap` on a receiver bound by a local
`val` whose initialiser this pass does not type — `firstItem`, in both. Typing a `val` from its
initialiser transitively is what closes them; filed here rather than left as a surprise.
## v3-a-local-def-captures-a-var-by-value-while-a-lambda-does-not — fixed; the analysis had to move, not the rule

<!-- status: fixed
     lane: v3
     kind: bug
     area: front
     fixed-in: e87d7d703
     gate: v3/corpus-report.sh (parameterless-def-local)
     found-by: claude-code
     found-at: 2026-08-16 -->

**FIXED IN e87d7d703, and the DIFF floor FALLS on both lanes rather than merely holding:**

    bridge  control PASS 250  DIFF 5  CRASH 1   ->  PASS 251  DIFF 4  CRASH 1
    exec    control PASS 252  DIFF 2  CRASH 8   ->  PASS 253  DIFF 1  CRASH 8

`parameterless-def-local` was a wrong answer, not a refusal, and it is gone from both lists.

**THE RULE WAS ALREADY WRITTEN AND ALREADY RIGHT — only its reach was wrong.** `assignedFree` and
`boxLocals` were built for the lambda half of this exact defect on 2026-08-08
(`v3-loses-a-mutation-to-a-captured-var`), and the comment there describes this mechanism word for
word: lifting passes captures as leading PARAMETERS, so an assignment inside mutates a copy. The
collector simply matched `Expr.Lambda` and nothing else.

**THE FIRST ATTEMPT WAS DEAD CODE, and why is the part worth keeping.** Adding a `Stmt.LocalDef` arm
to `boxedNames` changed nothing, because boxing runs LAST — deliberately, since `expandPlaceholders`
creates lambdas late — while `liftLocals` runs early and REMOVES the very nodes the new arm reads.
The analysis had to move before the lifting; the rewriting stayed where it was.

**IT SURVIVED BECAUSE BOTH LANES AGREED ON THE WRONG ANSWER.** Neither the parity gate nor the front
differential can see a defect the executor and the bridge share — only the corpus oracle can, which
is exactly how the lambda half was found.
## v3-a-toplevel-def-used-as-a-value-is-an-unknown-name — eta-expansion, one instruction

<!-- status: fixed
     lane: v3
     kind: feature
     area: front
     fixed-in: bf4220b5b
     gate: v3/corpus-report.sh
     found-by: claude-code
     found-at: 2026-08-16 -->

**FIXED IN bf4220b5b.** A bare name that is a top-level function lowers to `MkClos(d, idx, Nil)` — a
closure over no captures is exactly what a top-level function is. It sits AFTER the zero-arity arm on
purpose: `def empty: List[A] = Nil` referenced as `empty` is a CALL, and swapping the order would
turn every parameterless def into a function value nobody asked for.

    bridge  control PASS 246  DIFF 5  CRASH 1   ->  PASS 249  DIFF 5  CRASH 1
    exec    control PASS 248  DIFF 2  CRASH 8   ->  PASS 251  DIFF 2  CRASH 8

Both floors held on both lanes and the DIFF lists are identical name for name.

**IT COST TWO PROVIDERS, AND THAT IS THE FINDING RATHER THAN A FOOTNOTE.** With the fleet as it was,
the same change read PASS 251 / DIFF 10 on the bridge and CRASH 14 on the executor — five `content-*`
cases and `json-self-hosted-import` went from an honest refusal to a wrong answer, because the
refusal upstream had been keeping them out of providers v3 cannot actually serve. See
`v3-the-content-provider-has-no-root-document` and `v3-has-no-decimal-so-the-json-core-cannot-cross`.
Unwiring both keeps every floor and still leaves +3 on each lane.
## v3-the-content-provider-has-no-root-document — it is a pipeline to integrate, not a value to hand over

<!-- status: open
     lane: v3
     kind: feature
     area: runtime
     gate: v3/corpus-report.sh (content-binding and four more)
     found-by: claude-code
     found-at: 2026-08-16 -->

**`contentDocument() is unavailable: native compilation has no explicit root content`** — from
`ContentNativePlugin`, on every content entry point, so `v2NativeContentPlugin` stays out of
`v3/plugin-classpath.sh` and five `content-*` cases stay honest refusals.

**SIZED PROPERLY 2026-08-17, AND THE EARLIER NOTE IN THIS ENTRY UNDER-ESTIMATED IT.** It said v3
parses the `.ssc` including its front matter so "the document exists on this side". It does not. The
chain, read end to end:

1. `NativePluginHost.loadAll(config)` DOES take `NativeRuntimeConfig(contentModules = …)` — the door
   exists, and `V2Fleet` currently calls the no-argument overload. That part is one line.
2. A `NativeContentModule` carries `document: Value`, which must be a validated `DocumentContent/6`.
3. `std/content.ssc` does NOT build one: it declares `extern def contentDocument(): DocumentContent`
   and receives it. Nothing self-hosted turns source text into that value on its own.
4. The values come from a SELF-HOSTED STRUCTURAL PASS whose output the v1 CLI decodes
   (`NativeV2Structural.scala:35` destructures `(programValue, manifestValues, markdownValues,
   sourceValues)`), with ABI checks on source identity and root identity, and only then are they
   encoded into `META-INF/scalascript/content.bin` for the runtime to read.

**SO THE WORK IS AN INTEGRATION, NOT A HAND-OVER:** v3 would have to run that structural pass over
the program's source, decode `NativeContentModule` values from its result, and pass them through
`loadAll`. Feasible — it lives in `v3/plugins`, so invariant I-1 is untouched — but it couples v3's
runtime to v1's front, which is a decision rather than a slice, and it is a day of work rather than
an hour.

**THE FIVE CASES ARE NOT BLOCKED BY ANYTHING SMALLER.** `content-tables` and `content-to-markdown`
consume real parsed markdown; a synthesised empty document would satisfy the provider's check and
then produce wrong answers, which is the trade the DIFF floor exists to refuse.
## v3-has-no-decimal-so-the-json-core-cannot-cross — it has one, and it is a string

<!-- status: fixed
     lane: v3
     kind: feature
     area: runtime
     fixed-in: a95a337c5
     gate: v3/corpus-report.sh (json-self-hosted-import)
     found-by: claude-code
     found-at: 2026-08-16 -->

**FIXED IN a95a337c5, on the owner's approval.**

    exec    control 259 DIFF 1 CRASH 3   ->   262 DIFF 0 CRASH 3
    bridge  control 257 DIFF 2 CRASH 1   ->   259 DIFF 2 CRASH 1

The executor's DIFF floor is ZERO: no wrong answer anywhere in the corpus. All twelve gates green.

**A DECIMAL IS ITS CANONICAL TEXT** — which is how v2 already carries one (`DecimalV(text: String)`,
`PortableDecimal.canonicalText`). `Value.VDec` therefore brings no arbitrary-precision library into
the kernel and no Tier 0 type: no literal, no method, nothing a program can name, exactly like
`VBytes`. ARITHMETIC IS NOT ADDED: v2's `dec.*` family is fifteen prims and no case needs one, so
they are refused by name rather than guessed at.

**THE HARDER HALF WAS PRINTING.** `jsonRead(…).get("missing")` answers a `JsonBox` carrying
`optional`/`present` — an Option represented as a handle, which v2 prints as `None` and v3 printed as
`<handle JsonBox>`: a description of the container where the program asked for the value.
`Plugins.showHost` is a third door on the same SPI, and `None` from it means "not mine".

**GIVING IT `VHostData` TOO BROKE THREE PASSING CASES, and the correction is the rule worth keeping.**
v3 CAN read a host datum — a tag and fields — and must print it by the LANGUAGE's rules rather than
v2's: `Returned(done)` here against v2's `Returned("done")`. The three coroutine cases regressed on
the executor lane alone; twelve green gates did not see it and the corpus DIFF floor did, at 1 -> 3.
Render what you can read; ask only about what you cannot.
## v3-capability-list-outlived-the-divergence-it-declared — the front-capability gate was RED in CI for four rows that had already closed

<!-- status: fixed
     kind: apparatus
     fixed-in: 4222bd4d0
     lane: v3
     area: build
     gate: v3/front-capability-gate.sh
     found-by: claude-code
     found-at: 2026-08-15 -->

**The job `the two fronts accept the same programs` failed on every v3 run that got far enough to
report.** Not for a divergence that appeared — for four that DISAPPEARED and were still declared:

    FAIL   std-ui-i18n     no longer diverges; drop it from KNOWN_uniml in this commit
    FAIL   tkv2-component  no longer diverges; drop it from KNOWN_uniml in this commit
    FAIL   tkv2-offline    no longer diverges; drop it from KNOWN_uniml in this commit
    FAIL   tkv2-webauthn   no longer diverges; drop it from KNOWN_uniml in this commit

That is the gate working exactly as designed — `check_set` is bidirectional so a declared row that
stops diverging is as red as an undeclared one that starts. It was red for five days because the
red was invisible: the suite was being cancelled before it could report
(`v3-workflow-is-cancelled-before-it-can-report`), so nobody saw the gate that was telling them.

**ONE CAUSE, NOT FOUR.** `ssc3 ast` compares `Loader.closure`, which FOLLOWS IMPORTS, and all four
cases reach `std/ui/primitives.ssc` — three `opaque type` declarations that v3's own parser refused
and UniML's accepted. The divergence was in an import, not in any of the four cases. `28c34951e`
taught BOTH fronts `opaque type` and closed all four at once, and did not take the rows out.

**The claim is bounded by a check, not by the story sounding right:** no other corpus case reaches
`primitives.ssc`, so the set that stopped diverging is exactly the set that imported it, with
nothing left over. This is also what a row-per-file list buys over a count — a ceiling would have
dropped by four and said nothing about one cause.

**Fixed** by removing the four rows, with the mechanism recorded above the list so the next reader
does not re-derive it. Gate GREEN locally: "the two fronts differ on exactly the declared rows".

## v3-workflow-is-cancelled-before-it-can-report — closed on the measurement it asked for: zero

<!-- status: fixed
     lane: v3
     kind: apparatus
     area: build
     fixed-in: d2c84c5a2
     gate: .github/workflows/v3.yml
     found-by: claude-code
     found-at: 2026-08-15 -->

**CLOSED ON THE MEASUREMENT THIS ENTRY DEMANDED, a day out and split at the fix, exactly as written:**

    before d2c84c5a2   76 runs   cancelled 56   failure 11   success  9
    after              24 runs   cancelled  0   failure  2   success 22

On push events alone: 20 success, 2 failure, **zero cancelled**. The prediction recorded here was
"`cancelled` on a push event should go to ZERO", and it did — not fell, went to zero, because with
one concurrency group per commit eviction is structurally impossible rather than merely less likely.

**THE TWO FAILURES ARE THE POINT.** A suite that reports 2 real reds out of 24 is protecting v3; the
one that reported 9 usable verdicts in 100 runs was not. What the entry set out to fix was never the
colour, it was the silence.

**THE FIX WAS THE OPTION THREE ROUNDS HAD REJECTED, and the reason they rejected it did not exist.**
Each round treated per-commit groups as the expensive lever and "fewer arrivals" as the cheap one,
reasoning carefully about runner budget. This repository is PUBLIC and GitHub bills no minutes for
standard runners on a public repository — one `gh repo view --json visibility` settles it, and no
round made that call until the owner answered from memory when finally asked.

**WHAT REMAINS TRUE AND WORTH KEEPING.** The trigger no longer fires on `.md` under `v3/` (40 of 85
commits in three days were board-only edits, and no gate step reads a board file), and a pull request
still cancels the run for a commit it replaces, because only a branch tip needs an answer. The real
limit is now the account's 20-concurrent-job cap against two jobs per run, and past it GitHub QUEUES
rather than cancels — slowness, not a lost verdict.
## v3-parser-rejects-opaque-type — `opaque` fell through to the expression parser, and the tool could not read its own standard library

<!-- status: fixed
     kind: bug
     fixed-in: 28c34951e
     lane: v3
     area: front
     gate: v3/front-gate.sh
     found-by: claude-code
     found-at: 2026-08-15 -->

**`opaque type X = Y` was refused; `type X = Y` beside it was accepted and erased.** The two
spellings mean the same thing at this tier, and only one parsed.

    ssc3: std/ui/primitives.ssc:74:26: expected an expression, found =

Column 26 is the `=` of `opaque type Signal[T] = Any`. `opaque` is not in `keywords`, so it fell
through to the expression parser, became a top-level statement reading a name, and the file died on
the `=` that followed — **the fourth occurrence of one pattern**, after `type`, `sealed` and
`extern`, each recorded in the comment block at that branch in `v3/src/Parser.scala`.

**Where it was found is the part worth keeping.** Not by a corpus sweep — by trying to use `ssc3 ir`
as a DIAGNOSTIC on an unrelated bug (`f-placeholder-u0-reduced-but-not-solved`). The tool could not
read the file it was pointed at, so the instrument was unavailable exactly when it was wanted. Seven
occurrences in four shipped modules were blocked behind it: `std/uuid.ssc`, `std/json.ssc`,
`std/graphql.ssc` (two), `std/ui/primitives.ssc` (three).

**This was two fronts disagreeing, not a feature gap.** F already ships the decision:
`specs/v2.2-p6.5-fsub.ssc:2301`, whose `isTypeHead` accepts `type` and `opaque type` alike and emits
nothing for either — "alias — erased, emits nothing". v3's own parser accepted one spelling and
refused the other.

**Fix.** One branch beside the existing `type` alias branch, testing the WHOLE shape before
consuming anything — `opaque`, then `type`, then an alias that reaches its `=` — for the reason the
neighbouring comment already gives: a branch keyed on the word alone would consume nothing on a real
use of the name and the top-level loop would spin. That guard is not theoretical: `std/bench.ssc:26`
declares `extern def Bench.opaque[A](x: A): A`, called from `bench/corpus/streams-pipeline.ssc` and
`bench/corpus/typeclass-monoid.ssc`.

Erasure only — `opaque` asks a type checker to hide the right-hand side outside the defining scope,
and Tier 0 keeps no types at run time and has no checker, so the modifier has nothing to change.
Recorded as a row in `v3/specs/20-core-language.md` beside `generics`, so the tier's answer is
written down rather than inferred from the parser not objecting.

**Gate: `v3/front-gate.sh`, fixture `v3/tests/front/opaque-type.ssc`.** It covers both shapes the
corpus uses — a plain opaque alias and one with a type parameter — and, deliberately, a `def` named
`opaque` that is called, so a future branch keyed on the bare word fails here rather than in a
benchmark. DISCRIMINATION MEASURED, not assumed: with `v3/src/Parser.scala` reverted the fixture
fails at `opaque-type.ssc:10:18: expected an expression, found =`, the same shape as the original;
with the fix it prints `abc/41/5/8`.

**Payoff, measured.** `v3/ssc3 ir std/ui/primitives.ssc` now lowers end to end — 10,167 bytes of SSC
IR with an `(entry 58)` — where it used to produce nothing. `std/ui/content.ssc`, which imports it,
advances past this blocker and stops at a DIFFERENT gap, `content.ssc:57:51: expected an
expression, found [`. That one is not filed here: it is a separate construct and this entry should
not grow to cover whatever the next file needs.

## v3-gates-open-red-in-every-fresh-worktree-because-uniml-cp-is-per-checkout — and nothing warns you until a gate run has been spent

<!-- status: fixed
     kind: apparatus
     fixed-in: 5758cdb0d
     lane: v3
     area: build
     gate: v3/exec-gate.sh
     found-by: claude-code
     found-at: 2026-08-15 -->

**`v3/exec-gate.sh` and `v3/front-gate.sh` are RED on a clean, correct tree the first time any agent
runs them in a new worktree**, because `v3/.jars/uniml.cp` is a gitignored build artifact and every
worktree starts without one. Two `.uniml-only` fixtures — `annotation-own-line` and
`object-nested-class` — then cannot be read, and the gates go RED rather than skip, which is
DELIBERATE and correct: a gate that goes green with fixtures unrun reports less than it claims.

**Measured three times in one day**, in three separate worktrees (`f-placeholder-u0-fix`,
`j4-cmp-branch-peephole`, `j4-fuse-decide-once`). Each time the fix is the same and the gate says so
itself — run `v3/uniml-classpath.sh`, re-run — and each time it cost a full gate run to find out.

**Nothing tells you beforehand.** `scripts/new-worktree` does not mention uniml; `AGENTS.md` does not
mention `uniml-classpath.sh` at all. `.github/workflows/v3.yml` gets it right — it has a "Register
the UniML front" step before the gates — so CI never sees this and the cost falls entirely on agents
working locally, which is also why it has survived.

**Not the same as the two entries it looks like.** `BUGS.md`'s `uniml-classpath.sh --check` entry is
about a classpath going STALE when UniML's sources change; `v3-uniml-drops-a-parenthesised-parameter-type`
is about the two fronts disagreeing. This one is about the artifact being ABSENT, which is the normal
state of a worktree on its first day.

**Done when** an agent cannot pay for this twice. Three shapes, any one of which closes it, and they
differ in who pays:

1. `scripts/new-worktree` runs `v3/uniml-classpath.sh` when the tree has a `v3/` — correct but
   expensive, since it builds an sbt project for every worktree including those that never touch v3.
2. The two gates check for `v3/.jars/uniml.cp` FIRST and refuse in seconds with the one-line remedy,
   instead of after a full fixture sweep. Cheapest, and it keeps the RED that is deliberate.
3. `AGENTS.md` names the step beside the worktree mechanics. Cheapest of all and the weakest — it
   relies on being read before the gate is run, which is exactly the order that failed three times.

Shape 2 is the one worth taking: it preserves the current, correct verdict and only moves WHEN it is
delivered.

### CLOSED 2026-08-15 — `5758cdb0d`, shape 2, and both directions measured

Both gates now refuse the moment they learn the front is unregistered, through the `ssc3 fronts`
call they already make — not by testing for the file, because the driver owns that decision and a
second copy would be a second place to be wrong.

| gate | without uniml | with uniml |
|---|---|---|
| `exec-gate.sh` | **refuses in 35 s** (was 244 s to the same verdict) | does not fire — GREEN, 85 cases, 244 s |
| `front-gate.sh` | **refuses in 1 s** (was 51 s) | does not fire — GREEN, 89 cases, 51 s |

The 35 s is the v3 kernel compile the gate needs anyway; `front-gate` answers in one second against
a warm cache. The saving is larger than the table suggests, because the old cost was paid TWICE —
once to discover it and once after the fix.

**The RED did not change and was never the defect.** A gate that goes green with fixtures unrun
reports less than it claims, so an unregistered front still fails these gates deliberately. Only the
delivery moved.

**Proven in both directions rather than only the useful one:** with the classpath moved aside the
refusal fires and the gate exits 1; with it restored the refusal does not fire at all — `grep -c`
of its message returns 0 — and both gates complete green. A fail-fast that also fired on a healthy
tree would have been worse than the problem it replaced.

CI was unaffected by construction and that is why this survived: `.github/workflows/v3.yml`
registers the UniML front in a step of its own before the gates, so `uniml=1` there and this path is
unreachable. The whole cost fell on local work and never appeared in a run anybody reviews.

## v3-workflow-does-not-trigger-on-uniml-and-uniml-is-half-of-what-the-front-gate-runs

<!-- status: fixed
     kind: apparatus
     lane: v3
     area: build
     gate: .github/workflows/v3.yml
     found-by: claude-code
     found-at: 2026-08-15
     fixed-in: ac924a41628a9103772d3fc5a90f52ae096ff412 -->

> **FIXED `ac924a416` — and the cost was measured before adding, not after.** Of the last 300 commits on
> `main`, `uniml/` is touched by ONE, and by ZERO that do not already touch `v3/` or `v2/`. So the
> trigger adds no runs to today's traffic; what it closes is the case where somebody works on uniml
> alone, which is exactly when nobody is watching the front gate's other half. That is the same hole
> the `v2/**` note in that file describes, and there it cost four commits of undetected lane
> divergence.

**`.github/workflows/v3.yml` triggers on `v3/**`, `v2/**` and itself. It does not trigger on
`uniml/**` — and `v3/front-gate.sh`'s verdict depends on `uniml/**`.**

This is the SAME defect the workflow's own header describes, one path down. That header opens
"`v2/**` IS IN THE TRIGGER, and leaving it out cost a red `main` nobody could have seen coming",
and explains it: the bridge lane runs on the v2 runtime, so `v2/src/Runtime.scala` "is not a
neighbouring project — it is half of what `exec-gate.sh` compares". `charAt` diverged for four
commits before an unrelated push happened to trigger the workflow.

**The same is true of uniml, and it is measured rather than argued.** The workflow's own
"Register the UniML front" step runs `v3/uniml-classpath.sh` before the gates, so in CI `ssc3 run`
takes the UniML front — and `v3/front-gate.sh` is therefore a verdict on
`uniml/scala/.../ScalaSpike.scala` as much as on `v3/src/Parser.scala`. Measured today while adding
`opaque type` support:

| tree | `v3/front-gate.sh` |
|---|---|
| `v3/src/Parser.scala` fixed, uniml untouched | **RED** — `opaque-type.ssc:10:1: unknown name 'opaque'` |
| both fronts fixed | GREEN, 88 cases |

The RED came from a file the trigger does not watch. An edit confined to `uniml/**` can turn v3's
front gate red and this workflow will not run — which is the four-commit blind spot the header was
written about, still open on the other side.

**Sharper still: the same registration makes the gate blind in the other direction.** With uniml
registered, `ssc3 run` uses it, so the gate no longer exercises v3's OWN parser at all. Both halves
of today's fix were confirmed only because they were run with `SSC3_FRONT` pinned, by hand:

    SSC3_FRONT=v3     opaque-type.ssc -> abc/41/5/8   (fails 10:18 with v3's half reverted)
    SSC3_FRONT=uniml  opaque-type.ssc -> abc/41/5/8   (failed 10:1 before uniml's half)

So a regression in v3's own parser passes `front-gate.sh` in any environment where uniml is
registered — which is every CI run.

**Done when** two things hold, and they are separable — take either alone. (1) `uniml/**` is in the
`push` and `pull_request` path filters of `.github/workflows/v3.yml`, for the reason its header
already gives for `v2/**`. (2) `v3/front-gate.sh` runs its fixtures on BOTH fronts when both are
registered, rather than on the default one, so that "the two fronts agree" is asserted instead of
assumed — the `.uniml-only` marker already proves the gate knows the fronts differ in what they
accept. Not attempted here: this was found while landing `v3-parser-rejects-opaque-type`, the
workflow is outside that claim, and (1) makes every push touching `uniml/**` run v3's gates, which
is a cost paid by every agent and should be someone's deliberate decision rather than a side effect
of a parser fix.

## v3-extern-member-in-an-object-has-no-meaning — one front refused it, the other silently made it an unpositioned crash

<!-- status: fixed
     kind: bug
     fixed-in: 9ca1f4da2
     lane: v3
     area: front
     gate: v3/front-gate.sh (v3/tests/front/extern-object-member.ssc)
     found-by: claude-code
     found-at: 2026-08-14 -->

**`object math: extern def sqrt(x: Double): Double` was not expressible, and the two fronts failed
differently — which was the part that mattered.**

    before   SSC3_FRONT=v3   …:2:3: only `def` members are supported in a object at Tier 0, found extern
             uniml (default) (def "sqrt" (params (p "x")) (prim "__throw__" (str "an implementation is missing (`???`)")))
                             → at run time: a JVM stack trace, no position, no name

    after    SSC3_FRONT=v3   …:4:26: the host function 'fsx.exists' is not implemented on this lane
             uniml (default) …:4:22: the host function 'fsx.exists' is not implemented on this lane

**FIXED IN THREE PLACES, AND EACH ONE MIRRORS A RULE THAT ALREADY EXISTED AT TOP LEVEL** rather than
inventing one:

- `Parser.scala` steps over `extern` before a member `def` — the same two-token test it already
  applies at top level. **Only when the member list belongs to an `object`.** The loop is shared
  with `trait` and `class`, where a body-less def means "dispatch to a subclass"; accepting the
  keyword there would erase a word instead of honouring it, which is this bug moved to a new site.
  Verified: `trait Fs: extern def exists(…)` is still refused, by name, with a position.
- `UniFront.scala` projects a body-less OBJECT member as `hostGap`, not as `???`. `???` is right in
  a trait or a class and wrong here for a reason that is structural: an object is a NAMESPACE,
  nothing extends it, so "no body" has exactly one reading left — the same one it has at top level.
- `Lower.scala` partitions the object's members and sends the abstract ones through `resolveExtern`,
  **the same function top-level externs use**, keyed on the QUALIFIED name. No new branch: a dotted
  key would bind exactly as a plain one does, and with no key the extern gets a body that throws
  naming itself and its position.

**Qualified rather than plain, and the alternative is the reason to say so.** Keying on the member
name would let `object anything: extern def exists(p: String)` silently capture the host `exists`
that `hostPrims` already answers, handing a program a working function it never asked for. A
qualified key is one somebody has to write on purpose.

**A SECOND DEFECT WAS UNCOVERED BY THE FIRST FIX, and it is why this is not a two-line change.**
With both fronts carrying the keyword, the refusal still arrived at RUN time — positioned, but as a
stack trace. Two walkers decide the difference: one computes reachability from the entry, the other
turns a reachable gap into a refusal at the call. **Both matched only `Expr.Call`**, and a call to
an object's member is still a `MethodCall` at that stage, so the gap was reachable and unrefused.
Adding the one form to BOTH is what turns it into a lowering refusal. It stays an
under-approximation — dispatch on a value is still invisible, which is what keeps the 113 importers
of `jvm-vfs.ssc` compiling.

**The `Lower` change this entry recorded as REVERTED is the one that landed**, now that a front can
reach it. The comment at the `objectDefs` site is replaced by the code it described.

**Measured after, all four v3 gates and the corpus:** front-gate GREEN 91 (the new fixture refuses
with a position), exec-gate GREEN 86, front-capability-gate OK, corpus 223/369 with CRASH 9 —
**unchanged**, which is the number that matters for the reachability widening: seeing a new call
form could have refused programs that used to run, and refused none.

**The prelude keeps its `__mathSqrt` workaround, deliberately.** It could now be written as
`object math: extern def sqrt(…)`, and it should not be: the delegating body carries `.toDouble`,
and that widening is load-bearing — v2's `flt` refuses an Int, so `math.sqrt(16)` died on the bridge
while the executor answered 4. Moving the declaration into the object would move that one line of
`.ssc` both lanes run into a builder in Scala. The workaround is not what this entry was about.

## v3-the-fleet-wires-two-plugin-modules-of-twenty-six — wired seven; the rest is not a wiring problem

<!-- status: fixed
     kind: bug
     lane: v3
     area: runtime
     fixed-in: 93726d1da
     gate: v3/corpus-report.sh (with v3/.jars/plugins.cp present)
     found-by: claude-code
     found-at: 2026-08-15 -->

**FIXED IN 93726d1da by adding the five modules the corpus actually reaches — ui, content, json,
crypto, actors — chosen by measurement rather than by reading the directory:**

    two modules    PASS 228  DIFF 3  UNSUPPORTED 126  CRASH 9  EXCL 3
    seven modules  PASS 230  DIFF 3  UNSUPPORTED 122  CRASH 9  EXCL 5

Both floors held and the lists are identical — the same three DIFFs, the same nine CRASHes, all nine
being the single `v2 bridge V-0 does not translate perform/handle` cause.

**THE REFUSAL SHORT-CIRCUITS, SO THE FIRST CENSUS WAS A LOWER BOUND.** Before the change the names
were `element ×7`, `signal`, `contentDocument`, `sha256` and the rest; after it the list is not those
minus the fixed ones but a DIFFERENT set led by `forJsonView ×8`, which no earlier sweep could see
because the case died on an earlier name. Anyone re-measuring this bucket should expect the same:
each round of unblocking reveals the next layer, and no single count is the total.

**WHAT REMAINS IS NOT UNWIRED, IT IS UNIMPLEMENTED.** `forJsonView`, `actorGroupTell`,
`webauthnRegister` and `webauthnChallenge` appear in NO plugin source anywhere in the tree, so no
module list answers them. Adding the remaining nineteen modules buys nothing measurable and costs an
sbt build each in `v3/plugin-classpath.sh`, which is why the list stays at what the corpus reaches.

**`add` LOOKED LIKE A SIXTH MODULE AND IS NOT.** A source grep put it in http-fast, but there it is
`registerTaggedMethod("WsRoom", "add")` — a method on a tagged handle — while the refusing case is
`node-basic.ssc:20:9`, a graph node's `add`. Wiring http-fast for it would have been a change
justified by a name collision.

**AND THAT NAMED A REAL BOUND ON THE WHOLE PATH: `V2PluginRegistry` HAS THREE TABLES** — `handlers`,
`taggedApply` and `taggedMethods` — and `v3/plugins/V2Fleet.scala` bridges only the first. So a
plugin's tagged-handle surface is invisible to v3. It FAILS SAFE rather than silently: lowering gates
on the same table it bridges, so a tagged name is refused at compile time on BOTH lanes and I-3
holds. Filed as `v3-the-fleet-bridges-one-of-the-registrys-three-tables`.
## v3-the-fleet-bridges-one-of-the-registrys-five-tables — all five bridged; the chain was five links, not one

<!-- status: fixed
     kind: bug
     lane: v3
     area: runtime
     fixed-in: adf346eb1
     gate: v3/corpus-report.sh (with v3/.jars/plugins.cp present)
     found-by: claude-code
     found-at: 2026-08-15 -->

**FIXED IN b519f4cc8 AND adf346eb1. Both lanes, each against a control on the SAME tree — other
agents moved these numbers underneath the work, and reading the older baseline would have credited
their CRASH 9 -> 1 to this:**

    exec    control PASS 235  DIFF 2  CRASH 9   ->  PASS 248  DIFF 2  CRASH 8
    bridge  control PASS 235  DIFF 5  CRASH 1   ->  PASS 246  DIFF 5  CRASH 1

Both floors held on both lanes, DIFF lists identical name for name, and the executor's CRASH ends
one BELOW its control — `std-ui-i18n` and `tkv2-component` were crashing before any of this.

**THE ENTRY ASKED FOR ONE TABLE AND THE ANSWER WAS A CHAIN OF FIVE LINKS, each invisible until the
one before it was done.** `globalValues`, so `suspend` resolves at all. An OPAQUE HANDLE, because
`coroutineCreate` answers a `CoroutineState` v3 must carry and hand back. CLOSURES v3 -> v2, which
was the real blocker — the globals bridge alone bought ZERO cases, because the first thing every one
of these programs does is pass a function. CLOSURES v2 -> v3, since a provider can RETURN one.
Finally methods and calls on host-owned values: `taggedMethods`, `taggedApply` and the field-name
vector, behind ONE dispatcher in `Plugins` rather than a table per mechanism, because the keys are
v2's and mirroring them is how the two registries would drift.

**THE EXECUTOR LANE IS NOT WHAT THE REPORT READS BY DEFAULT, and that nearly hid the damage.** On
the bridge this looked finished at +9 while the executor had gone from CRASH 9 to 15: programs the
work unblocked ran on to the next missing link and died there instead of refusing, which is an
honest refusal traded for a crash. Measure `--exec` as well as the default whenever the plugin path
changes; `corpus-report.sh` with no flag answers only its own question.

**A CONSTRUCTOR THE PROGRAM NEVER DECLARED now travels by name** (`Value.VHostData`), because v2
names constructors with a string and v3 numbers them. A program that WRITES `case Errored(_) =>`
declares it and gets the ordinary indexed `VData` with matching intact; a program that only prints
one gets the by-name value, which renders and nothing else. No pattern can mention a constructor the
program does not declare, so being un-matchable costs nothing.

**WHAT IS LEFT IS ONE CASE AND IT IS BY DESIGN.** `v2-native-result-unregistered-field` is about a
native result whose case class was never declared in the compile unit — its field names are
deliberately absent from the registry, so no lookup can answer `exitCode`. It is `backends: [int]`
and unchanged by this work.
## v3-plugin-fleet-regresses-four-cases-when-enabled — it does not; the fleet now RAISES N by five

<!-- status: fixed
     kind: apparatus
     lane: v3
     area: runtime
     fixed-in: fbf16fb97
     gate: v3/corpus-report.sh (with v3/.jars/plugins.cp present)
     found-by: claude-code
     found-at: 2026-08-15 -->

**FIXED IN fbf16fb97. Measured on the rebased tree, the only difference being whether
`v3/.jars/plugins.cp` exists:**

    fleet off   PASS 223  DIFF 3  UNSUPPORTED 132  CRASH 9  EXCL 2   <- the control, identical to before
    fleet on    PASS 228  DIFF 3  UNSUPPORTED 126  CRASH 9  EXCL 3

Both floors held and the three DIFFs are the SAME three the control reports, so the fleet costs
nothing and gains five. Six cases leave the host bucket; five pass and one differs on a case that
does not hold the v2 lane.

**THE FOUR WERE THREE SEPARATE DEFECTS AND ONLY THE FIRST WAS THE ONE THIS ENTRY PREDICTED.**

1. *The value surface*, as filed: `VMap`, `VSet` and `VArr` cross now, both directions. Each was
   found by a failing program rather than by reading the enum, because the refusal names the shape
   it met — `VData` first, then `VMap`. v2 has no array case at all, so an array crosses as the
   `ForeignV(ArrayBuffer)` handle itself.
2. *A thrown failure is part of a host function's contract.* `listDir` on a missing directory is
   SUPPOSED to raise; the plugin's Java exception escaped the executor and surfaced as
   `cannot read '<the .ssc>': NoSuchFileException` — a message about the SOURCE FILE, from a handler
   that assumes anything thrown came from reading it.
3. *This report kept its own copy of the v2 invocation*, which is why the cases still counted DIFF
   long after they matched by hand on both lanes. It BUILT the IR with the front — which has the
   fleet, so lowering emits `(prim "mkdirs" …)` rather than a refusal — and RAN it on a plain
   `ssc.cli` with no plugins. `v3/ssc3 __v2-run` prints the whole command now and the report uses it.

**THE ENTRY SAID THE LAST THREE WERE "NOT DIAGNOSED" AND THAT THE DIFFERENCE WAS IN THE HARNESS.
That was right about the domain and wrong about every guess inside it** — not the parallel jobs, not
stdin, but the harness's own v2 entry. The guesses were named as guesses, which is the only reason
they cost nothing.

**A LATENT HARNESS DEFECT CAME OUT WITH IT, worth more than the fix.** The dispatch loop reads the
case list on fd 0 and a background job inherits it, so once the os plugin made `std-os-readline`
really read stdin it ATE THE REST OF THE LIST. The report still announced `running 369 case(s)` —
that count is taken upfront — while its buckets summed to 294. Seventy-five cases were never
dispatched, and no error was printed anywhere: it looked like an ordinary report with a lower N.
The quantity is what named the mechanism, being exactly the tail behind the reading case.
`< /dev/null` on the dispatch closes it for every case and every configuration.

**THE FLEET IS STILL OPT-IN**, but no longer because it regresses anything — only because
`v3/.jars/plugins.cp` needs an sbt build, and availability is a cached fact here exactly as the
second front's is. Turning it on by default is a separate decision with a separate cost.
## v3-a-toplevel-extension-shadows-a-given-instance-one-of-the-same-name — was: v3-handleError-on-a-val-bound-None-matches-no-arm

<!-- status: fixed
     kind: bug
     lane: v3
     area: runtime
     fixed-in: 5e8b9c2dc
     gate: v3/corpus-report.sh (std-index, std-monaderror)
     found-by: claude-code
     found-at: 2026-08-15 -->

**FIXED IN 5e8b9c2dc BY THE PASS ORDER, not by a new table.** `rewriteExtensionCalls` rewrites by NAME and
never looks at the receiver, and it ran BEFORE the pass that can type one. Swapped: the resolver
that knows the receiver's type claims what it can, the name-only rewrite takes the rest — the same
"more specific wins" the subtrait preference encodes one level down. No new guard was needed:
`instanceOnly` already subtracts `defs.map(_.name)`, so a method a top-level extension also provides
cannot be refused by the resolver running first.

**RENAMED, BECAUSE THE TITLE I FILED WAS THE SYMPTOM AND NOT THE MECHANISM.** It read
`handleError-on-a-val-bound-None`, and none of those three words is load-bearing: not `val`, not
`None`, not `handleError`.

**A TOP-LEVEL `extension` AND A `given`-INSTANCE `extension` OF THE SAME NAME COLLIDE, and the
top-level one wins whatever the receiver is.** `std/monaderror.ssc` declares `handleError` twice —
once inside `given optionUnitError: MonadError[Option, Unit]` for `Option`, and once as a top-level
`extension [A](fa: Either[String, A])` for `Either`. A call on an `Option` is rewritten to the
EITHER body, whose `fa match { case Right(_) …; case Left(e) … }` matches no arm. The throw comes
from `Exec.prim` with NO position, which is why the case lands in DIFF rather than UNSUPPORTED.

**Reproducer, fourteen lines, no `std` and no `val`:**

    trait ME[F[_], E]:
      def raise[A](e: E): F[A]

    given oue: ME[Option, Unit] with
      def raise[A](e: Unit): Option[A] = None
      extension [A](fa: Option[A]) def he(h: Unit => Option[A]): Option[A] = fa match
        case Some(_) => fa
        case None    => h(())

    extension [A](fa: Either[String, A])
      def he(h: String => Either[String, A]): Either[String, A] = fa match
        case Right(_) => fa
        case Left(e)  => h(e)

    println(Some(4).he((_: Unit) => Some(0)))    // match: no arm matched

**FOUR REDUCTIONS GOT HERE AND THE FIRST THREE KILLED A HYPOTHESIS EACH**, which is why the original
title was wrong: `case Some(_)` and `case Some(v)` patterns work; the same extension body at top
level works; the receiver shape is irrelevant (`val`-bound `None`, `val`-bound `Some`, and the bare
constructor `Some(42)` all failed identically); a multi-param trait with TWO instances works. Only
the name collision reproduces it.

**A SEPARATE DEFECT — `v3-multi-param-typeclass-never-resolves` — was found and FIXED on the way**
(`headAndArg` keyed the instance table on the string `Option, Unit`), and fixing it did NOT fix
this. Two defects behind one message.

**IT IS INVISIBLE TODAY, which is the only reason it has not been filed before.** Both cases are
refused earlier by the `is provided by a given instance` diagnostic, so the line is never reached.
Fixing that refusal is what exposes this.

## v3-given-instance-as-a-receiver-is-refused — `intSum.combine(a, b)` is a member call, not extension dispatch

<!-- status: fixed
     kind: bug
     lane: v3
     area: front
     fixed-in: 86ba35237
     gate: v3/corpus-report.sh (the `is provided by a given instance` histogram line)
     found-by: claude-code
     found-at: 2026-08-15 -->

**FIXED IN 86ba35237, AT THE THIRD ATTEMPT, AND THE TWO WITHDRAWALS ARE WHY IT IS CORRECT.** Shipped alone
it took DIFF from 3 to 5 twice, because it unblocked cases that then hit two OTHER defects and
produced wrong answers instead of honest refusals. The floor refused it both times; the second
refusal is what sent me looking for the cause instead of the symptom, and found them:
`v3-multi-param-typeclass-never-resolves` and
`v3-a-toplevel-extension-shadows-a-given-instance-one-of-the-same-name`. With both fixed, this arm
gives **N 218 -> 222 with DIFF back to 3 and CRASH 9** — the number predicted in the claim before it
was measured. `std-monaderror` and `std-index`, the two that had become DIFF, now MATCH.

**The receiver IS the instance and its member is refused.** `given intSum: Monoid[Int] with { def
empty; def combine(a, b) }` makes `intSum` an object, so `intSum.combine(intSum.empty, 42)` is an
ordinary qualified call that the object flattening already resolves. It is refused by
`rewriteGivenExtensionCalls`, whose guard asks two true questions — `receiverType` cannot type the
NAME `intSum`, and `combine` is declared only by given instances — from which the conclusion does
not follow.

Three cases, one shape: `std-semigroup-monoid:21` `intSum.combine(intSum.empty, 42)`,
`tagless-multi-file:39` `listLogged.pure(42)`, `std-monaderror:21` `optionUnitError.raise[Int](())`.

**THE FIX IS WRITTEN AND MEASURED AND DELIBERATELY NOT SHIPPED.** One arm, before the refusal, and
the distinction it encodes is the whole of it:

    extension        xs.foldMap(f)        -> listFoldable.foldMap(xs, f)   receiver PREPENDED,
                                                                          it is the value operated on
    instance member  intSum.combine(a, b) -> intSum.combine(a, b)          receiver NOT prepended,
                                                                          it is the OWNER

    val instanceMember = recv match
      case Expr.Name(n, _) if instanceDefs.contains(n + "." + m) => Some(n)
      case _                                                     => None
    if instanceMember.isDefined then Expr.Call(instanceMember.get + "." + m, args, p)
    else <the existing receiverType/pick chain>

`instanceDefs` already holds `object.member` for every given instance, so this adds no table and no
new source of types — it only asks the question in the right order.

**WHY IT IS NOT SHIPPED: the DIFF floor.** Measured 2026-08-15, control against the same tree minus
exactly this arm:

    control (no arm)   PASS 218  DIFF 3  UNSUPPORTED 137  CRASH 9
    with the arm       PASS 220  DIFF 5  UNSUPPORTED 133  CRASH 9

The `is provided by a given instance` histogram line goes from 6 to ZERO — two cases reach PASS, two
move to a different honest refusal, and two become DIFF. Lists compared rather than counts: control
DIFF is {parameterless-def-local, indent-block-statements, indent-config-format}; with the arm it is
those three plus `std-index` and `std-monaderror`. NEITHER was passing before, so there is no
PASS -> DIFF regression — but an honest refusal became a silent wrong answer, and the floor exists
for exactly that trade.

**BOTH NEW DIFFS ARE ONE DEFECT** — `v3-handleError-on-a-val-bound-None-matches-no-arm`, filed
above. Land that first, then this arm, then re-measure; the expectation is DIFF back to 3 with PASS
higher than 220.

## v3-stmt-val-discards-a-type-the-author-wrote — WORKED AROUND, not fixed: the resolver follows the initialiser instead

<!-- status: wontfix
     kind: bug
     lane: v3
     area: front
     gate: v3/tests/conformance/std-bifunctor.ssc (via the corpus report)
     found-by: claude-code
     found-at: 2026-08-15 -->

**CLOSED AS `wontfix` IN 32ac55842, and the reason is a count rather than a shrug.** The receiver problem
this entry was filed for is solved by a different route: `rewriteGivenExtensionCalls` now follows a
`val` ONE STEP to its initialiser's constructor, which is source 1 of §4a1 applied one binding away
— no unification, no type variable, no propagation through a function. `std-bifunctor` and
`tagless-sealed-dispatch` both pass; N 216 -> 218.

**Why not the route this entry proposed.** Carrying the author's written type on `Stmt.Val` means
39 use sites over six files, 21 of them PATTERNS on four positional fields that a fifth breaks, and
BOTH fronts populating it — which at the time the default front could not do, because it dropped a
parenthesised parameter type entirely (`v3-uniml-drops-a-parenthesised-parameter-type`, since
FIXED; the 39 use sites are what still decides this, not the front). The route taken is one file and
covers STRICTLY MORE: `val xs = List(1, 2, 3)` has no written type at all and resolves.

**What the proposal would still buy, so this is a wontfix and not a never:** a declared type is the
only source when the initialiser's constructor does not name the type — `val f: Foo = makeFoo()`.
Nothing in the corpus needs it today. Reopen with a case, not with a preference.

The original report follows, unchanged.

**`Stmt.Val` is `Val(name, value, mutable, pos)` — there is nowhere to put a declared type**, so
`val t: (Int, String) = (10, "ok")` and `val t = (10, "ok")` reach the lowering as the same thing.
`rewriteGivenExtensionCalls` then has no type for `t` and refuses `t.bimap(…)`
(`tests/conformance/std-bifunctor.ssc:19`).

**THIS IS NOT THE INFERENCE HALF OF THE DEBT, and separating the two is the point of this entry.**
`v3/specs/20-core-language.md` §4a1's first bullet says *"`Stmt.Val` records NO declared type.
`val xs = List(1, 2, 3)` gives the lowering nothing … Inferring `List[Int]` from the initialiser is
type INFERENCE and is not being built."* That sentence covers two different situations and prices
them as one:

- `val xs = List(1, 2, 3)` — nothing was written; recovering `List[Int]` needs INFERENCE, and that
  is the type-checker project, correctly deferred.
- `val t: (Int, String) = …` — the author WROTE the type and the front discarded it. Keeping what
  the source says is not inference and needs no checker. It is the same fact `Param.tpe` already
  carries for a parameter, on the other binder.

**The second is small and is what `std-bifunctor` needs:** `tpe: Option[String]` on `Stmt.Val`,
populated by both fronts, read where the parameter map is built in `rewriteGivenExtensionCalls`.

**MEASURED, so the yield is not overstated:** on v3's own front, with the tuple head fixed, a
PARAMETER declared `(Int, String)` resolves `bimap` and a `val` with the identical declared type
does not. That is the whole difference. When this was written, fixing it alone still would not have
made `std-bifunctor` pass on the DEFAULT front, because
`v3-uniml-drops-a-parenthesised-parameter-type` masked it there. That mask is GONE — the entry is
fixed and the default front now keeps the parameter's type.

## v3-uniml-drops-a-parenthesised-parameter-type — `def go(t: (Int, String))` loses its type on the default front

<!-- status: fixed
     kind: bug
     fixed-in: 482e3393b
     lane: v3
     area: front
     gate: v3/front-gate.sh (v3/tests/front/paren-param-type-tuple.ssc)
     found-by: claude-code
     found-at: 2026-08-15 -->

**A two-front pair, and the default front is the one that lost.** uniml's parameter loop read

    if c.peekKind == "spike.lparen" then skipBalancedParens(c)
    else expectType(c, …).foreach(kids += _)

— so a parenthesised parameter type was CONSUMED AND NOT CAPTURED, and `Param.tpe` arrived as
`None`. v3's own parser keeps it: `skipType` consumes the balanced parens and `typeTextOf`
reassembles the text.

**FIXED by using the mechanism that was already there.** `captureType` opens with
`if c.peekKind == "spike.lparen" then takeBalanced(…)` under the comment `` `(A, B)` domain `` and
returns a `Frame` carrying the role; `SpikeTyped.text` concatenates it into a `TypeRef`, and its own
comment says the same thing from the other side — "types are captured as token runs
(`ScalaSpike.captureType`)". Both ends were built for this. The one call site not using it was this
one, so the fix is `kids += captureType(c, role)` in place of the skip. It also swallows a trailing
`=> C`, which the skip left behind for the arrow branch — that is the whole function type instead of
half of it, and it is what the reference front records too.

**Measured on the same host, one probe, both fronts, before and after:**

    def go(t: (Int, String)) = t.bimap(…)
      before   SSC3_FRONT=v3   (12, ok)     uniml (default)   'bimap' is provided by a `given`
                                                              instance, and the type of the
                                                              receiver is not known here
      after    SSC3_FRONT=v3   (12, ok)     uniml (default)   (12, ok)

**THE CORPUS DOES NOT MOVE, AND THAT IS REPORTED RATHER THAN OMITTED.** `corpus-report.sh` was run
on a pre-fix build and a post-fix build in the same worktree, and the two reports are identical byte
for byte — PASS 223, DIFF 3, CRASH 9, UNSUPPORTED 132, N = 223 / 369. 8 corpus files do parse
through the changed branch (5 with a parenthesised parameter type, 6 with a function-typed
parameter, overlapping), so the path is exercised; none of them NEEDS the type downstream, which is
the difference between a case that touches a defect and a case that can decide it. The one of those
8 that is a defect after the fix — `head-field-effect-shadow` — crashes on `v2 bridge V-0 does not
translate handle`, a bridge refusal that has nothing to do with parsing. Identical totals could in
principle hide a swap; they cannot here, because only those 8 files can change verdict and none of
them did.

**The regression is a front-gate fixture, and the control was run.** `paren-param-type-tuple.ssc`
passes now (front-gate GREEN 90, exec-gate GREEN 86 with both lanes agreeing); with the fix reverted
and uniml rebuilt, the same fixture FAILS with exactly the original diagnostic and the gate is RED.
A fixture that is green either way would have measured nothing.

**It went in front-gate rather than front-capability-gate**, which is the gate FOR two-front
divergence, because that gate asks `ssc3 ast <file> <front>` over the corpus — and the corpus has no
program this can decide, as the paragraph above measures. This defect is also not a refusal AT the
front: the front accepted the program on both fronts and threw the type away, and the refusal came
later, from the resolver. An `ast`-level accept/refuse differential is blind to information lost
INSIDE an accepted parse.

**The pairing note this entry shipped with was already stale when it was written.**
`v3-stmt-val-discards-a-type-the-author-wrote` is `wontfix` as of `32ac55842` — the receiver problem
it was filed for is solved by a different route, `rewriteGivenExtensionCalls` following a `val` one
step to its initialiser — and `std-bifunctor` passes. So "fix both, then measure" was wrong twice
over: there was nothing to fix on the other side, and this side needed no help to be measured.

## v2-f-round-is-three-different-roundings-across-the-backends — `rint`, `Math.round` and `.round()` disagreed at exactly `.5`

<!-- status: fixed
     kind: bug
     fixed-in: d47dbf7e3
     lane: multi
     area: codegen
     gate: v2/conformance/float-round-ties.coreir (v2/backend/check.sh — NOT wired to CI, see below)
     found-by: claude-code
     found-at: 2026-08-15 -->

**THE CONTRACT IS HALF TO EVEN.** Owner's decision, taken after this entry was filed asking for one:
`round(2.5)` is 2, `round(3.5)` is 4, `round(-2.5)` is -2. That is IEEE-754's default and what
`math.rint` means, it is what three of the five implementations already did, and it is what v3's
shipped parity probes already pinned. Written into `v2/specs/10-core-ir.md` beside the prim list,
because the list alone could not settle it and five implementations had settled it three ways.

**FIXED IN TWO BACKENDS, AND THE CONTROL SAYS IT IS FOUR LANES.** js emitted `Math.round` (half up)
and rust `.round()` (half away from zero); js now emits a `$frint` helper and rust
`round_ties_even()`. Measured with the fix reverted and the new fixture in place:

    jvm   ok
    js    FAIL  row 1: expected 2, got 3
    rust  FAIL  row 1: expected 2, got 3
    wasm  FAIL  row 1: expected 2, got 3      <- NOT in the original census

**The wasm lane is generated through the rust backend**, so it inherited the away-from-zero rule and
was a fourth wrong answer the entry did not know about. With both fixes: ALL GREEN, 4 backends.
swift was read rather than run — `SwiftRuntime.scala:1112` is `.rounded(.toNearestOrEven)`, already
correct, and this harness has no swift lane.

**THE GATE IS HONEST ABOUT ITS REACH.** `v2/conformance/float-round-ties.coreir` is run by
`v2/backend/check.sh`, and **nothing in CI runs check.sh** — it appears only in changelogs and bug
entries. So this is a manual instrument, and the fix is not protected by a job. A conformance case
was written to cover it in CI and then WITHDRAWN, because the `int`, `jvm` and `v2` lanes answer a
DIFFERENT question — see the entry below.

**`math.round` IS NOT `f.round`, and that is now filed separately.** Measured on the shipped lanes:
`math.round(2.5)` prints **3** on both `--v1` and native, half UP and integer-valued, against **2**
for the Core IR prim. Filed as `math-round-and-f-round-disagree-at-a-tie` in `v2/BUGS.md` — it is a
consequence of this decision rather than part of it, and changing v1's semantics is its own call.

## v3-math-pow-fractional-needs-a-v2-prim — a fractional exponent had no answer on the bridge, and no v3-only fix could match

<!-- status: fixed
     kind: feature
     lane: multi
     area: runtime
     fixed-in: 58f866033
     gate: v3/tests/parity/math-pow-frac.ssc, math-pow-irrational.ssc, math-pow-int-args.ssc
     found-by: claude-code
     found-at: 2026-08-14 -->

**`math.pow` (SSC3-14) is partial: an integer exponent is a multiply loop, a fractional one raises
with a message naming the reason.** The owner asked for that to be fixed. It can be, in one line —
but not in v3.

**WHAT A FIX HAS TO MATCH, measured before choosing one:**

    reference lane   pow(2.0, 0.5) = 1.4142135623730951
                     pow(2.0, 0.1) = 1.0717734625362931
                     pow(10.0,1.5) = 31.622776601683793

libm-exact, bit for bit what JVM `Math.pow` gives. **That rules out every approximation.** A
ScalaScript series, or the repeated-sqrt expansion — which is the tempting one, since `b^f` follows
from the binary expansion of `f` and BOTH lanes already have `f.sqrt` — lands within about 1e-12 and
differs in the last bits, so v3 would disagree with the reference on every fractional pow. Replacing
an honest refusal with a plausible wrong number is the defect this repository keeps paying for.

**AND IT CANNOT BE DONE ON ONE LANE.** v3's executor could call `Math.pow` in a line; the bridge
emits `(prim …)` to v2, and v2 has no `f.pow`, no `f.exp` and no `f.log` — checked name by name
against `v2/src/Runtime.scala`. A v3-only prim runs on the executor and is refused by the bridge,
which is invariant I-3 and exactly the defect closed this morning in
`v3-flatmap-nonlist-lane-divergence`.

**THE FIX**, beside the existing `f.sqrt` at `v2/src/Runtime.scala:1434`:

    case "f.pow" => a => FloatV(math.pow(flt(a, 0), flt(a, 1)))

then v3 wires it exactly as it wired `f.sqrt`: a `hostPrims` entry, an `Exec` case, and a prelude
`def pow(b: Double, e: Double) = __mathPow(b.toDouble, e.toDouble)`. Additive — nothing currently
emits that name.

**FIXED 2026-08-15.** The owner chose the kernel when the question was put to him, and the change
was SIX sites rather than the one line this entry first claimed: the VM, the js, jvm, rust and swift
backends, and swift's allowed-prim LIST. `v2/specs/10-core-ir.md` said "(transcendentals such as
`sin`/`cos`/`log`/`exp` live in the `Mira` prelude, not the kernel)" and was NARROWED in the same
commit rather than left to contradict the code — `f.sqrt` was already kernel and is exactly
`pow(x, ½)`, `pow` is not in that list, and no `f.exp`/`f.log`/`f.pow` existed anywhere, so the
prelude named there was an intention and not an implementation.

All three lanes now agree bit for bit, including the row that fails first if anyone ever swaps in an
approximation:

    1.4142135623730951  1.0717734625362931  31.622776601683793  256  0.125  1  8

N held at 216/369 with DIFF 3 and CRASH 9, which is the expected answer: no corpus case uses a
fractional exponent. Ten v3 gates green; the four non-VM backends are COMPILED and not executed —
`v2/backend/rust` through scala-cli, which is what builds it, since no sbt project references it.

## v3-concat-nonlist-splits-three-ways — `List ++ nonList` wrapped on native, refused on the v2 VM, and v3 picked one

<!-- status: fixed
     kind: bug
     fixed-in: 2e6da244d
     lane: multi
     area: runtime
     gate: v3/parity-gate.sh (v3/tests/parity/list-concat-nonlist.ssc, restored)
     found-by: claude-code
     found-at: 2026-08-14 -->

**THE ANSWER IS WRAP.** Owner's decision: a non-list right operand is ONE ELEMENT, everywhere. The
fix went where this entry said it had to — v2 first, so its `++` agrees with its own `flatMap`, and
v3 widened in the same commit.

    val n = 5;  List(1,2) ++ n          before                  after
      reference native (bin/ssc)        1,2,5                   1,2,5
      v2 VM (ssc3 run --bridge)         RuntimeException,       1,2,5
                                        uncaught, no position
      v3 exec (ssc3 run)                refused, with position  1,2,5

**TWO DECISION SITES IN ONE FILE, and only one of them was in the entry.** `v2/src/Runtime.scala`
answers `++` twice: `arithOp` for the infix spelling — which is what the reference native lane runs
— and `methodOp` for the method call, which is what the bridge runs. The first wrapped, the second
refused. Fixing only the one the entry named would have left the same program answering two ways
depending on how it was spelled.

**A DUPLICATE PROBE FOUND A SECOND DEFECT, and it is the reason this is not a one-line change.**
`arithOp` wrapped with `.distinct`, so on the reference lane:

    List(1,2) ++ List(2)   ->  1,2,2      keeps it
    List(1,2) :+ 2         ->  1,2,2      keeps it
    List(1,2) ++ 2         ->  1,2        DROPS it

Silently. `.distinct` was there for `set + stringElement`, which the `+`→`++` string heuristic
lowers to `++`; real sets never need it, since a `SetV` receiver is routed to `methodOp("union", …)`
first, and only a set REPRESENTED AS A PLAIN LIST reached that arm. It is gone, so `xs ++ y` on a
non-list `y` is exactly `xs :+ y` on both paths. Measured after: `1,2,2` on the native lane, the
bridge and v3's executor.

**THE WITHDRAWN PROBE IS BACK, AND IT NOW MEASURES SOMETHING** —
`v3/tests/parity/list-concat-nonlist.ssc`. The entry withdrew it because two refusals differing only
in SHAPE would have sat red; that reasoning was itself off, because this gate compares OUTPUT and
two refusals both print nothing, so it would have read `neither` and been GREEN AND VACUOUS. Control
run: with v3's half reverted the probe FAILS `bridge [1,2,5/] executor []`, so it distinguishes the
lanes now that the bridge answers.

**Regression:** the full conformance corpus, 366 passed of 367 — and the one failure is
`native-import-in-fence`, a STALE `known-red` for the js lane that this suite is telling us to
delete. It uses neither `++` nor a list, and a sibling's `f770dad20` is what made that lane pass, so
it is not this change. v3 parity 60/64 agreeing with none diverging, exec-gate GREEN 86, front-gate
GREEN 91.

**`v1`'s interpreter is NOT part of this** and answers a third way entirely — `List(1,2) ++ 5` builds
a TUPLE there. Filed separately with its measurement, because making it agree is a change to a
shipped lane's semantics rather than a consequence of this decision.

## v3-flatmap-nonlist-lane-divergence — `flatMap` and `++` refused a non-list that v3's OWN BRIDGE accepted (was: v3-multishot-handler-without-a-return-clause)

<!-- status: fixed
     kind: bug
     lane: v3
     area: runtime
     fixed-in: 54eccf31f
     gate: v3/bench-corpus-gate.sh (row effect-multishot)
     found-by: claude-code
     found-at: 2026-08-14 -->

**RENAMED, BECAUSE THE TITLE I FILED THIS MORNING NAMED THE WRONG MECHANISM.** It read
`v3-multishot-handler-without-a-return-clause` and said the fixture's handler was missing a return
clause. Nothing about multi-shot handlers was wrong. The defect was `flatMap` on a NON-LIST result,
and the effect fixture was merely the loudest place it showed. The wrong name came from taking the
refusal's own advice at face value — the message said "if this is a handler, its return clause is
what lifts the final value" — instead of measuring another lane. That advice was in the code and it
was wrong, which is the kind of diagnostic that costs more than silence.

**THE FACT THAT SETTLES IT NEEDS NO OTHER IMPLEMENTATION: the two lanes of one compiler
disagreed.** `v3/ssc3 run --bridge` printed `List(10, 20, 30)` for a program `v3/ssc3 run` refused.
That is invariant I-3.

    op                     reference (bin/ssc run)    v2 VM (v3 --bridge)   v3 exec, before
    List.flatMap non-list  List(10, 20, 30)           List(10, 20, 30)      REFUSED
    List ++ non-list       List(1, 2, 5)              —                     REFUSED
    List.zip non-list      refuses "expected a list"  —                     refuses

A non-list is ONE ELEMENT on every lane that answers. `zip` refuses everywhere, so it was already
right and is untouched.

**THREE BEHAVIOURS, NOT TWO, AND COLLAPSING THEM IS WHAT WENT WRONG.** `7730f6039` (2026-08-12)
changed this walk from SWALLOWING a non-list to REFUSING it, on the owner's instruction, and it was
right that the swallow was a defect: a swallowed element made `xs.flatMap(f)` produce the EMPTY list
and a `foldLeft` over that returned a number that looked like an answer. What it got wrong is one
sentence — *"v2's runtime still swallows"*. It does not; it WRAPS, and
`git log -S flatMap 4a93c440c..HEAD -- v2/src/` is empty, so it wrapped then too. Swallow, wrap and
refuse are three different answers and only one of them is every other lane's.

**FIXED in `65a4a6d90` by a SECOND function rather than a flag on the shared one.** `listOut` serves
`flatMap`, `zip` and `++`; relaxing it would have made `zip` agree with nobody. So `listOrOne` wraps
a bare value and is used at the `flatMap` and `++` sites only.

**THE CHECK THAT TELLS WRAP FROM SWALLOW**, and it is the one worth keeping:
`tests/conformance/js-effect-multishot-long-fold.ssc` carries a checked-in **204**, v3 answered 0,
and **0 is also what swallowing produces** — so removing the refusal is not evidence on its own. It
answers 204 now, which means the element is contributing rather than merely no longer being refused.
`bench-corpus-gate` goes 33 -> 34 of 36 rows.

**N DID NOT MOVE — 212/369, DIFF 3, CRASH 9 — AND I PREDICTED THAT IT WOULD.** The prediction was
wrong and the reason is structural rather than surprising: both affected programs are invisible to
`corpus-report.sh` by construction. `js-effect-multishot-long-fold` declares `backends: [int, js]`,
so it is LANE-EXCLUDED and never counts in either direction, and `effect-multishot` lives in
`bench/corpus`, which that report does not read at all. I carried the price recorded in
`7730f6039` (CRASH 3 -> 4) forward without re-checking that the same case is lane-excluded today.
**The gate that covers this defect is `bench-corpus-gate`, and reading N for it was reading the
wrong instrument.**

## v3-mixed-int-double-arith — the executor refused `1 * 2.0` while its own bridge computed it

<!-- status: fixed
     lane: v3
     area: runtime
     kind: bug
     fixed-in: 9447789af
     gate: v3/exec-gate.sh (fixture v3/tests/front/mixed-numeric.ssc, run on BOTH lanes) -->

**Measured 2026-08-11.** `binOp` in `v3/src/Exec.scala` had only HOMOGENEOUS arms — `(VInt, VInt)`
and `(VFloat, VFloat)` — so every mixed pair missed all of them and fell through to the failure
case:

    println(1 * 2.0)   ->  ssc3: Mul on Int 1 and Double 2
    println(1 + 2.0)   ->  ssc3: Add on Int 1 and Double 2
    println(7 / 2.0)   ->  ssc3: Div on Int 7 and Double 2
    var r = 4
    println(r * 1000000.0)  ->  ssc3: Mul on Int 4 and Double 1000000

**This was a two-lane divergence, not only a missing feature — which is what makes it an I-3
defect rather than a gap.** The same file on the same commit:

    v3/ssc3 run --bridge mix.ssc   ->  2 2 3 3.5
    v3/ssc3 exec mix.ssc           ->  ssc3: Mul on Int 1 and Double 2

**The direction was measured before it was chosen**, because the other reading — that v3 is
deliberately strict about numeric towers — would have made a widening fix a regression. It is not:
interp, native and the v2 bridge all widen, and all three print `2 2 3 3.5` for the four lines
above. v3 was alone.

**Fixed** by two arms placed beside the existing `VChar` widening arms, which do the same thing for
the same reason. Deliberately narrow in two ways, both measured rather than assumed:

- **Four operators, not five.** `%` on a Double is refused by EVERY lane (native `TYPEERR: %
  requires Int left operand`, interp `No method '%' on Double`, v3 `Rem on Double`), so widening
  `Rem` would only exchange one refusal's message for another while claiming support no lane has.
- **Arithmetic only, no comparisons.** On mixed comparison the lanes disagree with each OTHER:
  interp evaluates `1 < 2.0` to `true`, while native and v2 refuse it at type-check time with
  `cannot unify Int vs Float`. v3 still refuses it, which is the majority position; picking a side
  of that divergence is a separate decision and is NOT made here. That divergence is between v1 and
  v2 and so belongs in the root `BUGS.md`, where it is NOT yet filed — it is carried as a follow-up
  in `v3/SPRINT.md` rather than referenced here as if it already existed.

  **SUPERSEDED the next day — see `v3-mixed-int-double-compare` below.** The paragraph above was
  right about what it had measured and wrong about what it concluded, and the missing measurement
  was v3's OWN bridge: it widens comparisons too, so the executor's refusal was not "the majority
  position", it was v3 disagreeing with itself. Left in place rather than rewritten, because the
  shape of the error is worth keeping: a survey of the OTHER lanes answered a question that was
  about THIS one.

**Guard.** `v3/tests/front/mixed-numeric.ssc` + `.expected`. Fixtures in that directory are run by
`v3/exec-gate.sh` on the executor AND through the bridge, and the two outputs are compared, so a
future one-lane fix cannot pass it. Verified to fail without the fix: reverting `Exec.scala` alone
makes the fixture die on its first line with `Add on Int 1 and Double 2`.

**Found from.** §55 B1 (one timing wrapper for every column): the shared bench wrapper's last line
is `_ssc_reps * 1000000.0` with an Int counter, so this refusal — not any parser gap — is what
stopped the wrapper from running on v3 at all.

## v3-mixed-int-double-compare — `1 == 1.0` was `false` on the executor and `true` on the bridge

<!-- status: fixed
     fixed-in: d1648e07a
     lane: v3
     area: runtime
     kind: bug
     gate: v3/exec-gate.sh (fixture v3/tests/front/mixed-compare.ssc, run on BOTH lanes) -->

**Measured 2026-08-11**, the day after `v3-mixed-int-double-arith` fixed the arithmetic half and
deliberately left this one alone. The deferral was wrong, and one measurement it had not taken says
why — the same file on the same commit, executor versus bridge:

| expression   | `ssc3 run` (executor) | `ssc3 run --bridge` | Scala |
|---|---|---|---|
| `1 < 2.0`    | `Lt on Int 1 and Double 2` | `true`  | `true`  |
| `2.0 > 1`    | `Gt on Double 2 and Int 1` | `true`  | `true`  |
| `1 == 1.0`   | **`false`**                | `true`  | `true`  |
| `1 != 1.0`   | **`true`**                 | `false` | `false` |

The earlier entry declined to widen comparisons on the grounds that interp and v2 disagree with
each other, so any choice would take a side. That survey asked about the OTHER lanes and the
question was about THIS one: v3's two lanes disagreed on every mixed comparison, which is I-3, and
the bridge's answers are also Scala's.

**Equality was the dangerous half.** An ordering comparison REFUSED stops the program and gets
looked at. `1 == 1.0` returning `false` is a wrong answer that nothing reports: the program simply
takes the other branch.

**Fixed** by adding `Lt/Le/Gt/Ge/Eq/Ne` to the widening arms already in `binOp`, so a mixed pair is
retried as two Doubles.

**Where it is NOT fixed, deliberately.** The first draft widened inside `eq` — the helper shared by
scalar `==`, collection equality and pattern matching — which also made `List(1) == List(1.0)` true.
That is Scala's answer, but measurement says every lane here disagrees with Scala together: the
executor, the bridge AND interp all answer `false`. Widening there would have repaired one
divergence by opening another where the lanes were consistent. Fix what disagrees; leave what
agrees. The both-lanes fixture caught this in one run — it is asserted at its current value so the
regression cannot come back silently.

**Still open, and not v3's:** interp answers `1 == 1.0` with `false` while the v2 runtime answers
`true`, and the native front refuses every mixed comparison at type-check with `cannot unify
Int vs Float`. Filed as `lanes-disagree-on-mixed-numeric-comparison` in the root `BUGS.md`.

## v3-takewhile-on-a-string-is-executor-only — the bridge answers, the executor refuses

<!-- status: fixed
     lane: v3
     kind: bug
     area: runtime
     gate: v3/rewrite-gate.sh — no; v3/extension-gate.sh derives Lower's vocabulary from Exec's table
     found-by: claude-code
     found-at: 2026-08-21
     fixed-in: cd58fa4f5 -->

**FIXED IN cd58fa4f5.** `takeWhile` and `dropWhile` on a String are two arms beside the other String
methods in `v3/src/Exec.scala`; the predicate takes a `Char`, exactly as it does on a list of chars.
All three lanes now answer `Circle` and `(3)` for `"Circle(3)"` split at `'('` — v3's executor, v3's
bridge and the v2 reference. `v3/extension-gate.sh` derives `Lower`'s built-in vocabulary from
`Exec`'s table, so the two stayed in step without a second edit.

**`"Circle(3)".takeWhile(c => c != '(')` prints `Circle` on the bridge and is REFUSED on the
executor:**

    ssc3: method 'takeWhile' on Circle(3)' is not implemented by v3's executor —
          `ssc3 run --bridge` runs it on v2

`takeWhile` on a LIST works on both lanes; it is the String receiver that only one lane answers.
That makes this a lane pair rather than a missing feature: the two lanes are supposed to answer the
same IR, and here one of them declines a method the other performs.

**FOUND WHILE MEASURING SOMETHING ELSE, and the measurement is the point.** The corpus row
`standard-scala-multifence` refuses with `call to unknown function 'f'`, so it was counted as an
interpolator case waiting for `def f(parts, args)`. Defining `f` moves the refusal one line down, to
this. **The interpolator was never its blocker** — a refusal short-circuits, so every "N cases need
X" is a lower bound until X exists, and this row's real need is a string method.

**Repro:**

    println("Circle(3)".takeWhile(c => c != OPEN))    # OPEN is the character literal for `(`

    v3/ssc3 run f.ssc            # refused, the message above
    v3/ssc3 run --bridge f.ssc   # Circle

**Fix goes in** `v3/src/Exec.scala`, beside the other String methods — and `v3/extension-gate.sh`
derives `Lower`'s built-in vocabulary from `Exec`'s table, so adding it there is what keeps the two
in step.

## v3-an-interpolator-prefix-and-an-ordinary-function-share-one-namespace — `raw` cannot be both

<!-- status: open
     lane: v3
     kind: bug
     area: front
     found-by: claude-code
     found-at: 2026-08-21 -->

**`pfx"…"` lowers to `pfx(parts, args)`, so an interpolator's prefix IS an ordinary global name** —
which is what made interpolators definable in a library (`v3-an-interpolator-prefix-is-hardcoded-in-
both-fronts`, fixed f92e3c644) and is also this defect. A program cannot have both `raw"…"` and a
one-argument `raw(value)`, because v3 has no arity overloading: two `def`s of one name leave the
first winning.

**MEASURED ON A REAL FILE, not imagined.** `tests/conformance/std-ui-native-html-lambda-lib.ssc:11`
is

    html"""<p>${raw(value)}</p>"""

so that case needs `html(parts, args)` AND a one-argument `raw(value)` — the HTML helper that marks a
value as already-escaped. Define `raw(parts, args)` for the interpolator and the file's refusal turns
from `unknown function 'raw'` into `call to 'raw' passes 1 argument(s)`. The two cannot coexist.

**CORRECTED TWICE ON 2026-08-21, and the second correction is the one that matters.**

FIRST: the collision I filed was not the file's need but my probe's — I had defined `raw` as an
interpolator. Swept: no file in the corpus or in `std/` uses the `raw"…"` interpolator at all (the one
grep hit, `std/cluster/coord-consul.ssc:123`, is `"?raw"` inside a URL). Within v3, defining
`html(parts, args)` and a one-argument `raw(value)` is enough, and `std/html.ssc` now does — the case
`std-ui-native-html-lambda` PASSES on both v3 lanes.

SECOND, AND THE REAL FINDING: **the collision is live on v2, in the other direction.** There the
prefixes are FRONT constructs — `html"…"` never becomes a call, and `raw` is a BUILT-IN answering
`_Raw(v)`. So a library `def raw` is shadowed on that lane, measured: `html(parts, [raw("<em>ok</em>")])`
answers `<p><em>ok</em></p>` on both v3 lanes and `<p>&lt;em&gt;ok&lt;/em&gt;</p>` on v2. And it
cannot be worked around from a portable file, because honouring v2's wrapper needs a `case _Raw(x)`
pattern and **v2 cannot match a constructor named with a leading underscore**
(`v2-a-constructor-pattern-named-with-a-leading-underscore-never-matches`, filed alongside).

So the shape stands and is sharper than filed: an interpolator prefix is an ordinary global name in
v3 and an owned front word in v2. A file that wants to be read by both can share the WORK — the
escaping in `std/html.ssc` runs identically on all three lanes — and cannot share the ENTRY POINT.
That is what to decide before more prefixes are written into `std`.

**Scala does not have this problem** because `raw"…"` is a method on `StringContext` and `raw(x)` is
a plain function — two namespaces. v3 has one.

**WHAT THIS IS NOT:** it is not an argument for putting interpolators back in the kernel. The
library encoding is what made `md` a twelve-line function this week. It is an argument for deciding
where an interpolator's name lives — an owner decision, recorded before anyone writes `def html`,
because writing it is what makes the collision permanent.

## v3-an-optic-cannot-print-itself-because-no-lane-renders-a-value-by-its-own-rule — it can now

<!-- status: fixed
     lane: v3
     kind: feature
     area: runtime
     gate: v3/rewrite-gate.sh (the optic labels, both lanes)
     found-by: claude-code
     found-at: 2026-08-21
     fixed-in: c8ab12722 -->

**FIXED IN c8ab12722. A VALUE NAMES ITS OWN RENDERING BY DECLARING A `_show` FIELD** — the owner's
decision of 2026-08-22, taken against the alternative of asking a plugin, which would have kept the
IR still and put one rule in two places. `Ir.TypeDef` carries field names, `Exec.showV` reads them,
and v2's two renderers do the same from their own registry through one shared helper.

**IT TOOK A THIRD HALF NOBODY PLANNED FOR.** v2 applies the rule only to a class whose field names it
KNOWS, and the bridge had never told it — so with both renderers taught, the executor printed
`Lens(_.x)` and the bridge still printed `Optic(Lens, .x, <closure>, <closure>)`: the rule was in
both places and the DATA to apply it reached only one. The bridge now emits the same `__regfields__`
prim v2's own front uses, for the types that have names and no others.

**`tests/conformance/optic-polish.ssc` expects `println(xLens)` to print `Lens(_.x)`**, and the
optic knows both halves — its kind and its path are ordinary fields. What is missing is a way for a
ScalaScript value to say how it renders. Every lane renders a data value STRUCTURALLY:

    Optic(Lens, .x, <closure 77>, <closure 78>)     v3 executor
    Optic(Lens, .x, <closure>, <closure>)           v3 bridge (v2 renders it)

**A USER `toString` DOES NOT DO IT, and that was measured on all three lanes:** `def toString()` is
callable and `println(x)` ignores it — `X(1)` from v3's executor, v3's bridge and the v2 reference
alike.

**THE REFERENCE SOLVES IT FOR HOST VALUES ONLY.** `v2/src/Runtime.scala` renders a `ForeignV` whose
`NamedMethodObj` exposes a `_show` field by that string — the comment there names optics as the
reason — and it has a second precedent one screen away: `case DataV("_Raw", fields) => anyStr(fields.head)`
renders one specific tag by its first field. Neither reaches a value written in ScalaScript.

**WHY IT IS NOT A SMALL FIX, stated so nobody re-derives it:** the bridge lane renders with v2, so
any rule has to hold in BOTH lanes or the two disagree — and v3 cannot implement the obvious form.
`Ir.TypeDef` is `(name: String, fields: Int)`: the IR keeps a class's ARITY and not its field names,
so `showV` cannot look for a field called `_show`. The candidates are therefore

  1. carry field names in `TypeDef` — an IR change, `specs/10-ssc-ir.md` §vocabulary, and every
     reader of the IR;
  2. ask the PLUGIN registry to render a data value, as `Plugins.showHost` already does for a
     foreign one — the kernel learns nothing, but v2 still needs its own half for the bridge;
  3. a `_show` field rule in v2's `Show` plus (2) — two mechanisms, one per lane, both outside the
     kernels.

**This is an owner decision, not a defect to fix quietly**, because option 1 changes the IR and
option 3 puts one rule in two places. Recorded with the measurement so the decision starts from
facts rather than from a guess.

## v3-copy-with-positional-or-mixed-arguments — landed, with what was holding it

<!-- status: fixed
     lane: v3
     kind: bug
     area: codegen
     gate: v3/rewrite-gate.sh (all seven copy forms, both lanes)
     found-by: claude-code
     found-at: 2026-08-21
     fixed-in: c8ab12722 -->

**FIXED IN c8ab12722, AND THE HOLD IS WHAT THIS ENTRY WAS FOR.** The patch was written and measured
on 2026-08-21 and deliberately not landed: alone it turned `optic-polish` from an honest refusal into
a wrong answer, DIFF 0 → 1, and DIFF is a floor. The rendering rule landed with it and the case now
passes on both lanes. All four legal forms answer the same on v3's two lanes and on the v2
reference, and the three illegal ones are refused everywhere.

**`copy` took named arguments only, and the two other spellings failed in two different places:**

    p.copy(10, 20, 30)     "method 'copy' … is not implemented by v3's executor" — it fell
                           through `copyFits` to a runtime method dispatch
    p.copy(10, z = 99)     "a named argument 'z' in a call whose signature is not known" — refused
                           at lowering

Both are ordinary Scala, the v2 reference answers both, and `tests/conformance/optic-polish.ssc`
uses both.

**THE FIX IS WRITTEN AND MEASURED** — `copyFits` accepts positional arguments first and named ones
after, refusing a named argument that names a field a positional one already filled and a positional
argument after a named one; the lowering fills each field by position, then by name, then off the
receiver. All three forms then answer what the reference answers, on both lanes.

**IT IS HELD, AND THE REASON IS THE FLOOR RULE.** With `copy` fixed, `optic-polish` stops being
refused and starts producing output — which is WRONG output, because printing an optic needs a
rendering hook no lane has (the entry above). A refusal is honest; a wrong answer is a DIFF, and DIFF
is a floor that does not rise. Measured exactly that way: landing both moved DIFF 0 → 1 on both
lanes, landing neither keeps it at 0, and landing only the optics keeps `optic-polish` refused at the
mixed `copy` one line earlier.

So this lands the day the rendering question is answered, together with it. The patch is kept with
the claim rather than in the tree; re-deriving it is twenty lines in `Lower.copyFits` and the `copy`
arm beside it.

## v3-front-cannot-parse-a-curried-member-method — the projection parses it, v3's own front stops at the class

<!-- status: open
     lane: v3
     kind: bug
     area: front
     gate: v3/front-capability-gate.sh (curried-def-member-methods is declared uniml-only)
     found-by: claude-code
     found-at: 2026-08-24 -->

**TWO LINES:**

    class Box(n: Int):
      def plus(a: Int)(b: Int): Int = n + a + b

    SSC3_FRONT=v3   ssc3: …:1:1: expected an expression, found class
    uniml (default) parses it

The refusal points at LINE 1, the `class` itself, which is what makes it read as a class problem
rather than a currying one; the file parses the moment the member takes a single clause. A top-level
curried `def` is fine on both fronts — it is the member position that fails.

**FOUND WHILE MEASURING SOMETHING ELSE, and the way it surfaced is worth keeping.** `6fbafea93`
(2026-08-23, a curried-def fix) added `tests/conformance/curried-def-member-methods.ssc` and declared
nothing, so `front-capability-gate.sh` went red on `origin/main` — and with it the ceiling
`front-diff.sh` derives from that list. I found it as a red on my own branch and could have declared
it as mine; the control worktree at `origin/main` said otherwise. **A red inherited from main is not
a red you caused, and the only way to tell is to run the gate on main.**

The row is declared uniml-only now so the number means something again. The declaration comes OUT the
day this parses.
