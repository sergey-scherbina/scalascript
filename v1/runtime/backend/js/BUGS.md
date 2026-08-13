# JS backend (`js` lane) — bugs

Scope: defects whose FIX goes in `v1/runtime/backend/js/`. Layout and routing rules:
`specs/work-tracking-layout.md`. Entry format (the header is parsed, the prose is not):
`specs/bugs-index.md`. Query with `scripts/bugs-report --module v1/runtime/backend/js`, never by
grepping for status.

Newest first.

## jsgen-genexpr-is-three-times-the-jit-limit — 25328 bytecodes, refusal demonstrated, hot cost NOT established

<!-- status: open
     lane: js
     area: codegen
     kind: perf
     reported-by: claude-code
     reported-at: 2026-08-12
     confirmed: yes
     gate: tests/e2e/v1-jit-size.sh -->

`scalascript.codegen.JsGen::genExpr` is **25328 bytecodes — 3.17× the 8000-byte HotSpot
`HugeMethodLimit`**, past which a method is never JIT-compiled by C1 or C2. It is the largest
offender in the tree after `handleActorOp`, and it is on the path of every JS emission.

**The refusal is REAL and was demonstrated**, on the same artifact that was censused, with
`-XX:+PrintCompilation` and `-XX:-DontCompileHugeMethods` as the control (same artifact both arms):

| workload | limit ON | limit OFF |
|---|---|---|
| one small program, single `emit-js` | 0 | 0 — never warm enough to be considered |
| a generated 400-def, 800-line program | **0** | **1** (tier 3) |

**AND THE HOT COST IS NOT ESTABLISHED — read that before spending a day on this.** One tier-3
submission on an 800-line program is barely warm. Compare the interpreter methods this technique
was just applied to (`v1-interpreter-hot-path-never-jits`): `dispatchList` and `evalCore` were
submitted 3-4 times INCLUDING tier 4 on modest workloads, which is what made splitting them worth
doing. `genExpr` is a compile-TIME method invoked once per emission, and a one-shot `emit-js` exits
long before C2 would look at it.

**Where it would actually cost, and what to measure first:** a long-lived process performing many
emissions — a watch/server mode, or a corpus build sharing one JVM. Measure THAT before splitting:
run the emission repeatedly in one process and check whether `genExpr` reaches tier 4 with the limit
off. If it does not, the 25328 is a hazard on the census and nothing more, and the honest outcome is
to record that rather than to split.

**Why it is filed rather than fixed:** the size is a fact and the gate now holds it frozen, so it
cannot grow unnoticed the way it did while `v1-jit-size.sh` was wired to nothing (24984 → 25100 →
25328). Splitting is the same shape as the interpreter work — sequential decomposition along the
term cases — and is cheap to do once a measurement says it pays.

## js-char-is-a-plain-string — a char literal never widened, so `'x' == 120` was false and `'a' + 1` was `"a1"`
<!-- status: fixed
     lane: js
     area: codegen
     kind: wrong-output
     gate: tests/e2e/js-char-is-a-char.sh
     fixed-in: a95f3abda -->

**FIXED 2026-08-10** in two commits: `b37f7b0fc` (literals) and `a95f3abda` (names bound to one).
Six shapes disagreed with `bin/ssc` and `--v1`, which already agreed with each other:

| | `'x' == 120` | `1 + 'a'` | `'a' + 1` | `'a' * 2` | `'a' < 98` |
|---|---|---|---|---|---|
| `bin/ssc`, `--v1` | true | 98 | 98 | 194 | true |
| `run-js` | **false** | **`1a`** | **`a1`** | **`aa`** | **false** |

`'a' * 2` returning `aa` is the one that shows the shape of it: JS represents a char literal as a
one-character STRING, so every numeric operator silently became a string operator. `.toInt`,
`charAt` and printing were already right — only literals in a numeric context were wrong.

**The representation was NOT the bug, and that is why the fix is three lines of decision rather than
a rewrite.** `JsGen` carries an explicit note that char-as-string backs char/char equality, pattern
matching and the name-keyed numeric evidence (46 sites), that `===` does not call `valueOf`, and
that making the representation uniform "is a separate change with its own corpus run". That note is
correct. What was missing is the widening Scala does at the OPERATOR: a char in a numeric context is
its code point. So the infix arm widens the *operand* — before constant folding, so the folder and
the emitter cannot disagree — and only when the SIBLING operand is provably numeric. `'a' == 'a'`,
`'a' + "b"`, `case 'a' =>` and `List('a', 'b')` are untouched, and the gate asserts that half too: a
fix that widened unconditionally would break every char comparison in the corpus while passing a
gate that only checked the numeric group.

This completes the owner's Char decision (2026-08-10: *"`println(s.charAt(0))` должен печатать
правильно символ а не число, везде на всех бекендах… в остальных случаях по возможности просто
приводить тип"*) on the last backend that still diverged. `bin/ssc`, `--v1` and `--bytecode` were
done under `scharAt`; JS is coercion-only, exactly as the decision asks.

**The two extra shapes came from the negative control, not from reading.** Reverting the fix and
re-running the gate is what surfaced `'a' * 2` → `aa` and `1 + 'a'` → `1a`; by hand I had found only
the two the report named.

**And the literal was only half of it.** `val c = 'a'` emits `const c = "a"`, so `c == 97` and
`c + 1` were wrong in exactly the same way while every literal shape read green — the more common
shape, since a char usually reaches an operator through a name. Fixed with the machinery already
there for this class of question: `rebindNumericEvidence` makes a `val`/`var` binding authoritative
for its name, so a `charLitVars` entry is set and cleared beside `longVars`, and the operand widens
to `(c).charCodeAt(0)` when its sibling is numeric.

That evidence is **name-keyed and module-global**, which is what makes it dangerous: `js-char-int-eq-namescope-collision`
(fixed `d034e2798`) is the same set leaking a sibling function's param. So a declared param clears
`charLitVars` at both registration sites, and the gate carries a `def shifted(c: Int)` next to a
`val c = 'a'` in `main`. Without the clear that emits `c.charCodeAt(0)` on a NUMBER — a TypeError at
runtime, not a wrong answer.

Verified in the emitted JS rather than only through the lane output, so "the branch fires" is not an
inference: `c == 97` → `_arith('==', (c).charCodeAt(0), 97)`, and `c == d` → `_arith('==', c, d)`,
unwidened, because two char names compare as characters.

**SUPERSEDED the same day, and the replacement is smaller.** `js-char-type-test-cannot-tell-Char-from-String`
made a char literal carry the `_Char` box, whose `valueOf` IS the code point — so every numeric
operator widens on its own and none of the machinery above is needed. `charLitVars`, its two param
clears and the operand rewrite were removed; the 25-row gate stays green without them. The
name-keyed evidence hazard they carried went with them.

Left standing: the term-level fold of `'x' == 120` to a constant, which is free and still correct.
The entry stays FIXED — the behaviour it was filed for never regressed, only the mechanism changed —
and it is recorded here because a reader finding `charLitVars` referenced in the commit and absent
from the file should not have to guess why.

**The TYPE TEST is a separate, still-open entry:** `js-char-type-test-cannot-tell-Char-from-String`.
`case _: Char` needs the value to carry the `_Char` box — the representation change this fix
deliberately does not make. Both defects were filed under one slug and `bugs-index-gate` refused the
duplicate on the way in, which is how they came to be told apart at all.

## js-control-direct-tests-never-run — the repair landed with 496 lines of tests and nothing runs them
<!-- status: fixed
     lane: js
     area: build
     kind: apparatus
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: fa28ae2ad -->

**FIXED 2026-08-07, same day.** The suite runs, from a clean `npm install`, and CI runs it
(`.github/workflows/js-packages.yml`). 16 of its 39 tests could not execute at all: every fixture
mentioning a marker type pulls in the package's own `index.d.ts`, whose `import type … from
"@scalascript/control"` resolves by walking up from the PACKAGE ROOT, not from the fixture directory
where the harness had symlinked the sibling. Supplied through TypeScript `paths` in three places —
the mechanism the README names and `tsconfig.json` already used.

**Not through a dependency entry, and that is the part worth keeping.** I tried that first;
`test/package.test.js` went red on it, because `"@scalascript/control": "file:../control"` in
devDependencies IS a filed bug of this package — `js-control-direct-packed-local-dev-dependency`,
fixed in `9baf6d2bf`. The suite stopped me from reintroducing a defect it was written to catch.

All twelve sibling `js-control-direct-*` entries were then triaged on evidence and closed: every
named repair is reachable from `origin/main`, and each entry now names the test that guards it.

**Found 2026-08-07 while checking why eleven `js-control-direct-*` entries are open and ungated.**
They are not ungated. Their fix landed, WITH tests, and the tests are dead.

`c19d42401` (2026-07-15, *fix(js-control): close direct transform review blockers*) is **reachable
from `origin/main`** — several of those entries still say it "awaits landing" — and it added:

```
 v2/host/js/control-direct/transform.js            +398
 v2/host/js/control-direct/test/transform.test.js  +326
 v2/host/js/control-direct/test/cli.test.js        +170
 v2/host/js/control-direct/cli.js                  +68
```

**Nothing invokes them.** `control-direct` appears in no workflow, not in `scripts/smoke-ci.ssc`, and
in no script under `scripts/` or `tests/e2e/`. The package declares
`"test": "node --test --test-concurrency=1 test/*.test.js"` and that command is never issued by
anything in this repository.

**And they cannot run as checked out:** every test needs the `typescript` devDependency, so without
an `npm install` in that directory the suite dies at import —
`Cannot find package 'typescript'` for `transform.test.js`, and
`compatible TypeScript compiler API not found` for the four CLI cases. A gate that requires an
uninstalled dependency is a gate nobody will accidentally discover.

**Consequence.** Eleven entries have sat `open` for three weeks with **no signal either way**: the
work to close them may be entirely done, and nobody can tell without running a command no CI job
knows about. That is worse than a red test — a red test at least says something.

**Smallest useful fix:** run `npm install && npm test` for this package in the tier that already
handles npm work, and record the result. Until then, no `js-control-direct-*` entry can honestly be
moved to `fixed`, and this entry is the reason.

## js-aliased-package-root-import-is-unbound — `[org as o]` throws, while every other alias works

<!-- status: fixed
     lane: js
     area: codegen
     kind: bug
     gate: tests/e2e/package-keyword-smoke.sh
     fixed-in: f708bf9f9 -->

**FIXED 2026-08-06.** `[org as o]` prints `ui-card-hi`, and the gate's `alias/root` row is green —
the last red cell of a four-lane × four-form alias matrix.

**Diagnosed by reading the GENERATED js, and the whole defect is one identifier:**

```js
3070: const org = (() => { … })();          // the namespace object, top level
3071: const o = org.example.ui.org;         // selects `org` out of its own namespace -> undefined
3072: _dispatch(o, 'example', [])           // -> Method not found
```

so the `Method not found` came from a `_dispatch` on `undefined`, with nothing in it pointing at an
import. The whole change to the emitted file, diffed before/after, is that one line becoming
`const o = org;`.

A binding naming the package ROOT is the namespace OBJECT, not a member of it, so qualifying it with
`pkgPrefix` cannot resolve. The UNALIASED form was already correct via the `!topLevelConsts.contains`
guard below it — its local name IS `org`, so it matched — and the aliased form slipped past because
the local name is `o`. Same shape as the jvm fix, which emits `val o = org`
(`jvm-package-import-qualifies-the-link-name`, jvm/BUGS.md).

### Original report (superseded 2026-08-06)

Measured 2026-08-06, from a four-form alias matrix. This lane bound `as` aliases correctly in every
form EXCEPT the package root:

```
[org as o]     the package root          int OK   js err   jvm OK
[Card as C]    a member object           int OK   js OK    jvm OK
[helper as h]  a member def              int OK   js OK    jvm OK
[greet as g]   a module with NO package  int OK   js OK    jvm OK
```

So it was not "js ignores aliases" — three of four rows are green, and the same fixture without
`as` is green too. The failure was a RUNTIME one, not an unbound name:

```
[stdin]:1475   throw new Error('Method not found: ' + method + ' on ' + _show(obj));
```

which is the generated `_dispatch`, so `o` was bound to something that was not the module namespace
object. That was a different cause from the native lane's (`native-import-link-alias-is-ignored`,
where the alias is discarded before it reaches any consumer), and it got its own look.

The jvm fix for the same row is the shape worth reading first: `JvmGen.aliasBlock` now emits
`val o = org` for an aliased root instead of `import org.example.ui.{org}`
(`jvm-package-import-qualifies-the-link-name`, `06513400e`).

## a-char-literal-is-not-boxed-so-its-methods-are-not-found

<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: bf7f833da
     gate: tests/e2e/js-char-classification-parity.sh -->

**FIXED at the function boundary, not in the runtime.** `intParamGuardLines` already coerced
declared `Int` params on entry; a `Char` param now gets the symmetric half, `c = _asChar(c);`.
Scoped to the function that DECLARED the type, which is the point: seeding name-keyed
module-global evidence instead would have made a same-named String elsewhere turn `"5".toInt`
into 53 — this same defect inverted. `_asChar` passes through anything that is not a
one-character string, so an unexpected value behaves exactly as before. A char literal used
directly as a receiver is boxed at the call site by `genReceiver`.

**Still open, and not pretended otherwise:** a Char inside a generic container, where no static
type is available. Only a uniform representation closes that — see the note below on why it is a
separate change.


A `Char` obtained by iterating a String is boxed as `_Char` and dispatches correctly. A char
LITERAL reaches the runtime as a plain JS string, so it takes the String branch of `_dispatch`,
which has no classification methods:

```scala
def show(c: Char): String = "" + c.isDigit
def main(): Unit = println(show('A'))
```

    $ bin/jssc probe.ssc
    Error: Method not found: isDigit on A

The interpreter answers `false` for the same source. `"A".foreach(c => c.isDigit)` works on both,
which is why this survived: the common idiom takes the boxed path.

**A thrown error is the mild half. The silent half is worse:**

    'a'.toInt   ->  97 on the interpreter, NaN on js, no error either way

`toInt` exists on the String branch as `parseInt(obj)`, which is correct FOR A STRING, so the char
literal takes it and gets `NaN` without anything going wrong. Measured with the rest of the char
surface: `==`, `match`, concatenation, `Map` keys, `List.mkString` and `<` all agree between the
lanes; `toInt` and the classification predicates are where it breaks.

**Which is why the fix cannot go in the runtime.** A `Char` literal and a one-character `String`
have the SAME representation there, so no dispatch branch can answer both — `"12".toInt` must stay
`12` while `'a'.toInt` must become `97`. The static type is known only at codegen, at
`JsGen.scala:4547` where `Lit.Char` is emitted as a JS string literal.

**And that is not a one-line change**, which is why it is filed rather than done in passing. JsGen
carries name-keyed numeric evidence (`intVars`/`numericVars`) built around the current
representation, with a documented hazard already recorded in it: `===` does not call `valueOf`, so
a comparison that takes the numeric fast path is always false against a boxed `_char`. Changing
what a char literal IS touches equality, pattern matching and those fast paths, and wants the whole
corpus on every lane rather than a probe.

Found while building `tests/e2e/js-char-classification-parity.sh`, which had to draw its probes
from a String rather than from literals to reach the predicates it compares. Unfixed and
deliberately not worked around in the runtime — the fix is in how a char literal is EMITTED or
boxed, not another `case` in the String branch, and picking between those is the actual task.

## char-classification-diverges-from-the-interpreter-on-seven-predicates

<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: b8b58dfc3
     gate: tests/e2e/js-char-classification-parity.sh -->

`core-collections.mjs:373-384` implements `Char`'s classification with JS regex property escapes
that are NOT the predicates Java answers, so the same ScalaScript program gives different answers
on the js lane and on the interpreter. Measured character by character over the whole BMP,
JDK 21 against Node 22:

| predicate | js impl | disagreeing code points | why |
|---|---|---|---|
| `isDigit` | `_c >= 48 && _c <= 57` | **360** | ASCII only; Java is every `Nd` — Arabic-Indic, Devanagari, fullwidth |
| `isSpaceChar` | `_s === ' '` | **18** | Java is `Zs Zl Zp` |
| `isLower` | `/\p{Ll}/u` | **199** | Java adds `Other_Lowercase` |
| `isUpper` | `/\p{Lu}/u` | **42** | Java adds `Other_Uppercase` — Roman numerals `U+2160..` |
| `isWhitespace` | `/\s/u` | **8** | JS counts NBSP / `U+2007` / `U+202F`; Java counts `U+001C..1F` |
| `toUpper` | `.toUpperCase().charCodeAt(0)` | — | takes the FIRST char of a multi-char expansion: `ß` becomes `S`, `ﬀ` becomes `F`, `ǰ` becomes `J`. Java returns the character unchanged when its uppercase form is not one char |
| `isLetter` | `/\p{L}/u` | 16 | correct logic; see the residue below |

**The fix is verified, not proposed.** With `isDigit` -> `/\p{Nd}/u`, `isSpaceChar` ->
`/[\p{Zs}\p{Zl}\p{Zp}]/u`, `isUpper` -> `/\p{Uppercase}/u`, `isLower` -> `/\p{Lowercase}/u`,
`isWhitespace` -> Java's explicit set, and `toUpper` returning the character unchanged unless the
uppercase form is exactly one char, the first four rows and `toUpper` become **byte-identical to
Java over the whole BMP**.

**What CANNOT be fixed in code, and it is the interesting half.** 16 code points still differ, and
every one is Unicode VERSION skew: `U+A7CB`, `U+1C89`, `U+088F` and friends exist in Node's ICU and
not in JDK 21's Unicode 15.0, and `U+0295` moved from `Ll` to `Lo` between them. Two runtimes that
ship their own Unicode data will never agree by construction, whatever the code says. That is the
argument for baking a generated table into anything the LANGUAGE's meaning depends on — which is
what `uniml`'s `UniAlphabet` now does for the one place case is load-bearing.

**Blast radius, which is why this is filed rather than fixed in passing.** Every `.isDigit` /
`.isUpper` / `.isWhitespace` in user ScalaScript changes behaviour on the js lane. Widening
`isDigit` from ASCII to all `Nd` is the language becoming MORE permissive on one lane, and a
hand-written parser that leaned on `isDigit` being ASCII would silently change meaning. This needs
the conformance corpus on both lanes, not a unit test.

**FIXED, and the reference lane turned out to be wrong too.** Each predicate now uses the escape
Java actually implements. Building the gate then showed that `DispatchRuntime` answered
`isSpaceChar` with `c.isWhitespace` — a different predicate, disagreeing on 24 BMP characters —
so a non-breaking space was not a space character on the interpreter while being one everywhere
else. Fixing only js would have left the gate permanently red for a reason outside js.

The gate needed no planted defect to prove it can fail: it went red on that divergence and
localised it to two lines of output.

Conformance after: 338 PASS int / 302 PASS js, the only failure being
`content-linked-namespaces`, which fails identically in a tree WITHOUT the change — verified by
running it there rather than assumed.

Found while measuring the case question for `uniml-ssc3-frontend-readiness`; the harness is
reproducible from the entry above.

## js-package-import-emits-a-second-self-referential-const — `SyntaxError: Identifier 'org' has already been declared`

<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: c338542dfff45e34e942b7c805032fab05fa2282
     gate: tests/e2e/package-keyword-smoke.sh -->

**FIXED. The emitter had three guards and needed a fourth.** `JsGen.scala` ~2549 emitted the import
binding when `targetJsName != localJsName && !notExported && !declaredBindings.contains(localJsName)`.
`declaredBindings` only tracks bindings emitted by that same loop, so it could not see the namespace
`const org` the object path had already emitted — the check now also consults `topLevelConsts`.

When the imported name IS the top-level namespace there is nothing to bind: `org.example.ui.Card`
resolves through the object that is already there. Clean A/B on the same tree: reverted and rebuilt
gives 2 × `const org` and node refusing the module, fixed gives 1 and `ui-card-hi`. Ordinary
imports still bind (`[Helper](./h.ssc)` → `went`), which is the thing a guard like this could
plausibly swallow.

`package-keyword-smoke`'s JS row passes now. Its INT row still fails on
`native-front-has-no-package-namespace` (v2/BUGS.md) and its JVM row is the gate invoking a compiler.

Importing a module that declares `package: org.example.ui` emits the namespace correctly and then
emits a SECOND binding for the same name, which selects the name out of its own namespace:

```js
const org = (() => { const example = (() => { const ui = (() => { … })(); return { ui }; })(); return { example }; })();
const org = org.example.ui.org;                                    // <- line 3031
```

    $ bin/jssc consumer.ssc
    SyntaxError: Identifier 'org' has already been declared

The module does not load at all — this is a parse-time error, not a wrong answer at runtime.

Ten lines, measured 2026-08-04:

```
--- cards.ssc                          --- consumer.ssc
package: org.example.ui                [org](./cards.ssc)
object Card:                           println(org.example.ui.Card.render("hi"))
  def render(t: String): String =
    "ui-card-" + t
```

**Two faults, and the first line shows the second one is the mistake.** Line 3030 builds the
package namespace properly, so JsGen knows how to bind `org`. The import binding then treats the
selected name `org` as a MEMBER of the package (`<pkg-path>.org`) rather than as the package ROOT it
already emitted. Dropping the second binding is likely the whole fix, but the emitter is not read
here and the entry does not claim it.

**How it surfaced, which is the part worth keeping.** `tests/e2e/package-keyword-smoke.sh` sent
stderr to `/dev/null`, so all three lanes reported an identical empty `got:` and the orphan census
recorded the gate as "empty output". With stderr kept, one run separated three different states: the
native lane says `unbound global: org` (`native-front-has-no-package-namespace`, v2/BUGS.md), the JVM
row is the gate invoking a COMPILER and reporting its artifact message as program output, and JS is
this. Three diagnoses that were one blank.

## js-collection-companion-empty-is-not-callable — `Set.empty` dies with "Method not found: empty on <function>"

<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/conformance/set-ops-infix.ssc
     fixed-in: b8ec91192 -->

Found 2026-08-04 while giving `Set` a representation of its own on this lane
(`type-ascription-tuple-and-set-arms-missing`). Not a regression from that work — verified against
the pre-change toolchain.

**Correction to this entry's first version, which named the wrong scope.** It said "`List.empty`
fails identically" and called this a general companion-select hole. It is not: the first probe put
`List.empty` and `Map.empty` in one file, the program died on the SECOND line, and I attributed the
failure to the first. Re-measured one expression per run:

```
List.empty        js: List()        int: List()
List.fill(2)(7)   js: List(7, 7)    int: List(7, 7)
List.range(0, 3)  js: List(0, 1, 2) int: List(0, 1, 2)
Set.empty         js: Error: Method not found: empty on <function>   int: Set()
Map.empty         js: Error: Method not found: empty on <function>   int: [ERROR] No key 'empty'
```

So only **`Set.empty`** is this lane's defect. `List`'s statics work because the emitted `List` is
the RUNTIME's own function, which carries `.empty`/`.fill`/`.range` as own properties; `Set(...)`
is emitted as `_setOf(...)`, so the bare `Set` in a value position is the **native JS Set
constructor**, which has none of them. `core-collections.mjs` already routes exactly this situation
for `Array` (also a native constructor) to `List`'s companion.

`Map.empty` fails on `int` too, so it is a different question and not this entry's.

The APPLICATION forms were always fine, which is why this went unnoticed: `Set()` works and is
pinned by `tests/conformance/set-distinct.ssc`.

**FIXED 2026-08-04.** One line in `core-collections.mjs`, beside the block that already routes the
native `Array` constructor's statics to `List`'s companion — `Set` is the identical situation and
the fix is the identical shape: `if (obj === Set && method === 'empty') return _setOf();`

`Map.empty` is deliberately NOT included: it fails on the interpreter too, so it is a question about
the reference semantics, not a js defect, and it has no lane to be graded against.

## js-object-apply-not-callable — `O(7)` on an object with an `apply` member throws `not callable`
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 84fc359e7
     gate: tests/conformance/object-apply.ssc -->

**Found 2026-08-02** by `v2-object-apply` while fixing the same shape on the native lane, and
**filed late**: that claim's conformance case said "filed separately" before this entry existed. The
reference was dangling for one commit; this is it.

```scalascript
object O:
  def apply(x: Int): String = "user-apply:" + x.toString
println(O(7))        // js: Error: not callable
println(O.apply(7))  // js: user-apply:7
```

**A record is not callable, and that asymmetry is the whole bug.** `genObjectAsExpr` returned
`{ apply }`, so the property read worked and the application reached `_dispatch`'s last resort.

**Fix:** when an object has an `apply` member the IIFE returns the `apply` FUNCTION with the record's
members attached (`Object.assign(apply, { … })`), so `O(7)` and `O.apply(7)` are the same code and no
member is lost.

**Not applied when a member is named `name` or `length`** — both are non-writable on a function, so
`Object.assign` would throw at module load and turn a wrong answer into a dead bundle. Such an object
keeps the record shape: `O.apply(x)` still works, `O(x)` still does not. That is the previous
behaviour preserved, not a new failure, and it is a guard rather than a hope — the two names are
checked at emit time.


## js-tls-reads-cert-files-eagerly — `tls()` opens the files the case says it does not touch
<!-- status: fixed
     fixed-in: 63fcb65eb
     lane: js
     area: runtime
     kind: bug
     gate: tests/conformance/tls-smoke.ssc (JS lane) -->

> **FIXED 2026-08-02, and MY OWN FRAMING OF IT WAS WRONG.** I filed this saying "both readings are
> defensible… the choice is the runtime's to make, not a passing agent's" — having checked neither
> of the other three lanes. They all agree, and they agree with the case:
>
> | lane | what `tls()` stores |
> |---|---|
> | v1 http-plugin | `PluginValue.string(cert)` — the PATH |
> | v2 native | `Value.StrV(path)` |
> | JVM ProxyRuntime | `_TlsConfig(cert, key)` — the paths |
> | **JS** | `fs.readFileSync(...)` — **the bytes** |
>
> and `std/http.ssc` declares `tls(certPath: String, keyPath: String)`. There was no design question:
> JS was the outlier. The read moved to `_ssc_http_serve`, where the bytes are needed and where a
> missing cert is genuinely fatal rather than a difference between backends.
>
> `tls-smoke` is now PASS [INT] PASS [JS]; the `known-red: js` expired by itself, exactly as the
> mechanism intends — the suite failed with "STALE known-red: this lane now PASSES" until the
> declaration and its baseline row came out.

**Found 2026-08-02 by fixing the defect on top of it.** `js-tls-intrinsic-missing-from-the-wsserver-capability-trigger`
meant `tls(...)` emitted a call with no definition, so the body never ran. With the trigger added,
it runs — and dies:

```
Error: ENOENT: no such file or directory, open 'server.crt'
    at tls (…:1767:21)
```

```js
function tls(cert, key) {
  const fs = require('fs');
  return { cert: fs.readFileSync(cert), key: fs.readFileSync(key) };   // ws-server.mjs:511
}
```

`tests/conformance/tls-smoke.ssc` says in its own prose: *"tls() builds a TlsContext value without
reading the files"*, and the INT lane does exactly that — it PASSES with no such files present.

**Not filed with a recommendation, because both readings are defensible and the choice is the
runtime's to make, not a passing agent's:**

* **defer the read** — matches the case, matches INT, and makes `tls()` a pure value constructor
  that `serve()` resolves later. Cross-backend agreement comes free.
* **keep it eager** — a server that will not be able to start should say so at configuration time,
  not at bind time. Then the CASE is wrong and should create the files or expect the error.

What is not defensible is the current state, where one lane reads and the other does not and the
case's prose describes only one of them.

Declared `known-red: js` on the case meanwhile, naming this entry.

## js-compound-assign-dispatches — `x += 1` compiles to a dynamic method call that always throws
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 261607982
     gate: tests/conformance/js-compound-assign.ssc -->

**FIXED 2026-08-01** by `js-compound-assign-and-gate-registry`.

**The rewrite already existed and was reachable from exactly one place.**
`genGeneratorCompoundAssign` turns `i += 1` into `i = i + 1` — and only the for-comprehension
GENERATOR path called it, so an ordinary statement fell through to the generic infix arm and became
`_dispatch(i, '+=', [1])`: a dynamic lookup for a method literally named `+=`. Now a
`CompoundAssign` extractor sits ahead of that arm, so every position gets the same rewrite.

**One hazard the widening created, closed in the same edit:** `:=` is a DSL assign operator in its
own right (`attr.x := v`, SBT-style settings — `ssc1-front` `opPrec` lists it), and it ends in `=`
like the compound operators. Confined to generators it was unreachable; widened, it would have
become `x = x : v`. It is now excluded explicitly beside the existing `>=`/`<=`/`!=`/`==` carve-outs.

`tests/conformance/js-compound-assign.ssc` covers `+=`, `-=`, `*=`, String `+=`, the while-loop
shape the bench wrapper uses, and — deliberately — a for-comprehension generator, the ONE position
that already worked, so a later change cannot fix one position by breaking the other. int, js and v2
all answer `2 7 20 ab 10 6`.


**MOVED here and its `lane:` CORRECTED 2026-08-01** by `v2-source-backend-lanes`, which re-measured
it. The header said `lane: v2-jvm`; it reproduces on the **v1 js lane** (`ssc-tools run-js`) and the
throw comes from `JsGen`'s own `_dispatch` preamble, so the fix goes in
`v1/runtime/backend/js/src/main/scala/scalascript/codegen/JsGen.scala` — which is what decides the
module, per `specs/work-tracking-layout.md`. Left OPEN rather than fixed: that file is held by the
live `ssc3-core` claim. The v2 native lane answers `1` correctly, so this is not a v2 defect at all.


**Status:** OPEN (found 2026-07-28 by `bench-tier-dead-lanes`; filed, not fixed — the JS codegen
paths belong to another lane).

**Reproduce** — three lines:

```scalascript
var i = 0
i += 1
println(i)
```

| lane | result |
|---|---|
| INT (`ssc-tools run --v1`) | `1` |
| JS (`ssc-tools run-js`) | `Error: Method not found: += on 0` |

**Root cause.** `emit-js` emits `_dispatch(i, '+=', [1])` — the dynamic
method-dispatch fallback — instead of `i = i + 1`. `_dispatch` has no `+=` entry, so it reaches
`throw new Error('Method not found: ...)`. The operator is not compiled at all, it is *looked up*.

**Impact beyond the repro:** any `.ssc` program using `+=`/`-=`/`*=` is silently JS-broken. It took
down the entire `js` benchmark column because the bench wrapper itself used `+=`.


## js-tls-intrinsic-missing-from-the-wsserver-capability-trigger — `tls(...)` alone emits a call to nothing
<!-- status: open
     lane: js
     area: codegen
     kind: bug
     gate: tests/conformance/tls-smoke.ssc (JS lane) -->

**Found 2026-08-01 while surveying CI stability — NOT MINE, and `JsGen.scala` is held by the live
`ssc3-core` claim, so this is diagnosed and handed over rather than fixed.** The fix is one entry in
one list.

`tls` is `RuntimeCall("tls")` (`codegen/intrinsics/Http.scala:15`) and its implementation lives in
the **WsServer** runtime chunk (`js-runtime/ws-server.mjs:511`). That chunk is included only when
`hasWsServer` fires, and its trigger list (`JsGen.scala:1444`) is:

    WsConnection(  WsRoom(  serveAsync(  sse(  cors(
    httpGet(  httpPost(  httpPut(  httpPatch(  httpDelete(  httpClient(

`tls(` is not in it. So a program whose only WsServer-chunk call is `tls(...)` emits the call and
none of the definition:

    const ctx = tls("server.crt", "server.key");   // …and no `function tls` anywhere
    ReferenceError: tls is not defined

**WHY IT SURFACED NOW, and the answer is a good one.** `tls-smoke` was GREEN on run 30682235399 and
RED on 30683833380. The suspect was `4f8c8cb9f` ("the shaker stops deleting side effects"), landed
in between — and it is *involved*, but not as the cause. A/B measured, old shaker vs new, on the
same tree:

| | `tls(...)` in the emitted bundle | `run-js` |
|---|---|---|
| old shaker | absent — the whole `val ctx = …` was deleted as unreferenced | **still ReferenceError** |
| new shaker | present | ReferenceError |

`run-js` — the lane the conformance runner uses — **never shook at all**, so the capability gap has
been there the whole time. What the old shaker did was delete the *statement* on the `emit-js` path,
which is exactly the defect `4f8c8cb9f` fixed. The case's JS lane was passing because the call it
exists to make was being thrown away.

So: not a regression from that commit. A pre-existing hole it stopped hiding.

**Fix:** add `tls(` to `hasWsServer`. Worth a moment first on whether the list should be derived
from the intrinsic table instead of hand-maintained — every `RuntimeCall` whose implementation lives
in a chunk needs a trigger, and this one was simply forgotten. `serve(` is also absent, though it
may reach the chunk through HtmlDsl; that is worth checking in the same pass.

## js-long-arith-no-64bit-wrap — `Long` arithmetic never wrapped: wrong answers, and an unbounded accumulator
<!-- status: open
     lane: js
     area: runtime
     kind: bug
     gate: tests/conformance/long-overflow-wrap.ssc -->

> **REOPENED 2026-08-01 — fixed on ONE of the two emitters.** `6567328660` closed the v1 JS lane
> (`PASS [JS ]`) and the entry went to `fixed`, but its own declared gate still fails on **`JS/v2`**:
>
> ```
> long-overflow-wrap:  PASS [INT]  PASS [JS ]  FAIL [JS/v2]  PASS [JVM]  PASS [JVM/v2]
>   line 12: expected=1000000000000000000000000000000000000  got=-5527149226598858752
> ```
>
> That number is the exact truncation this entry is about — `BigInt(1e9)^4` masked to 64 bits — so
> the v2 front's JS output still routes Long and BigInt through one unmasked helper. The v1 fix put
> the discriminator in `JsGen.isLongExpr`; the v2 front has its own emitter and did not get it.
> Reopened rather than declared done: an entry saying `fixed` while its gate is red is how the next
> reader concludes the lane is covered. Measured 2026-08-01 on `--no-memo`.
> The v1 half stays fixed — do not undo it. See `known-red: js-v2` on the case.

**Found 2026-07-31 by running `bench.sh`** — the `tuple-monoid` js cell had been burning 100 % of a
core for 16 minutes and looked like a hang. It was not hung.

**Root cause.** `_arith`'s bigint branch (`core-dispatch.mjs`) returned the raw `BigInt` for
`+ - * / %`. ssc `Int`/`Long` are 64-bit and WRAP (`specs/numeric-widths.md` §2); a JS BigInt is
unbounded. The sibling `_bit` had masked with `BigInt.asIntN(64, …)` since the Long→BigInt work
(`v1-js-long-precision-and-bitops`, 2026-07-13) — arithmetic was simply never given the same
treatment, and nothing executed a runtime overflow on the js lane to notice.

**Two defects, one line.** Correctness: the js lane disagrees with INT/JVM/v2 on any overflow.
Performance: in a loop the value never stops growing. Measured on `bench/corpus/tuple-monoid.ssc`'s
LCG (100k × `s = s*2862933555777941757 + 3037000493`), one pass:

| | wall | final `s` | `acc` |
|---|---|---|---|
| masked (correct) | **5 ms** | 64 bits | 399872 |
| unmasked (the bug) | **43548 ms** | 6,131,220 bits | 550000 |

8700×, and a different answer. At 100 reps that one benchmark cell needs ~72 minutes.

**The fix is NOT a mask inside `_arith`, and that matters.** ssc `BigInt` is arbitrary-precision and
has the SAME runtime representation (a JS bigint), so masking in the shared helper truncated
`BigInt(1000000000)⁴` from `1e36` to `-5527149226598858752` — measured on the first attempt, caught
only because the conformance case was extended to cover the twin. Only the compiler knows which of
the two a value is. So: a new `_larith` (= `_arith`, then `asIntN(64)` when the result is a bigint),
emitted by `JsGen` from the *existing* `isLongExpr` guard; comparisons stay on `_arith`; `_idiv`/
`_imod` mask too, since they fire only when both operands are statically `Int` and
`Long.MinValue / -1` overflows back to `MinValue` on INT/JVM.

- **Real-harness repro (pre-fix):** `bin/ssc-tools run-js tests/conformance/long-overflow-wrap.ssc`
  disagrees with the golden on **7 of 11** lines — that is the number the gate prints, and it is why
  the case is worth its length.
- **Gate:** `tests/conformance/long-overflow-wrap.ssc` (+ `expected/`), which deliberately holds BOTH
  halves — Long wraps, BigInt does not — so neither can be "fixed" by breaking the other.

## js-long-accum-plus-hoisted-const-mixes-bigint — `Long` accumulator + hoisted constant threw at runtime
<!-- status: fixed
     lane: js
     area: codegen
     kind: bug
     gate: tests/conformance/long-accum-invariant-fold.ssc
     fixed-in: 79e054ccaa5c89ee8bddeb2597b73a7235b7fed7 -->

**Found 2026-07-31 in the same `bench.sh` run** (`list-fold`'s js cell reports `node failed` and the
column is `n/a`). Separate defect from `js-long-arith-no-64bit-wrap` above — that one is the runtime,
this one is emission — and it survives that fix.

**Repro** (INT prints `165`; js throws):

```scalascript
val xs: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

def main(): Unit =
  var sum = 0L
  var i = 0
  while i < 3 do
    xs.foreach(x => { sum = sum + x })
    i = i + 1
  println(sum)
```

```
while ((i < 3)) { sum = sum + _k0; i = (i + 1); }
                            ^
TypeError: Cannot mix BigInt and other types, use explicit conversions
```

**Root cause (unconfirmed, one probe deep).** The loop-invariant `xs.foreach` fold is hoisted to a
constant `_k0` emitted as a plain JS Number, and the rewritten `sum = sum + _k0` is a RAW `+` — the
Long guard that would have routed it through `_arith`/`_larith` is not consulted on the rewritten
node. `sum` is a BigInt, so the operator throws. Same shape as `js-effect-multishot-long-fold` and
`js-long-param-arith`, which were fixed at their own emission sites; the hoisting path is a third one.

**FIXED 2026-07-31.** Root cause confirmed as scoped above: `inlineForeachOrGenStat`'s
invariant-accumulation hoist (`JsGen.scala`) builds its output as TEXT — `let s = 0` for the inner
sum and `acc = acc + _kN` for the outer add — so neither addition ever reached the BigInt-aware
runtime helper. When the accumulator or the addend is statically `Long`, the inner sum is now seeded
`0n` and both additions go through `_larith`.

**The mask is load-bearing, not defensive.** This optimisation REORDERS `acc + a + b + …` into
`acc + (a + b + …)`, and that is exact only modulo 2^64 — so the same helper that stops the crash is
what keeps the reduction sound when the accumulator crosses the top of the range. The `Int` path
keeps its native `+`; verified in the emitted JS that `ints = ints + _k0` is byte-for-byte what it
was and only the `Long` rows moved to `_larith`.

- **Gate:** `tests/conformance/long-accum-invariant-fold.ssc` (+ golden), covering all three shapes —
  `Int` accumulator (the optimisation must survive), `Long` accumulator (this crash), `Long`
  accumulator that OVERFLOWS (the reordering under wrapping, where a wrong implementation answers
  differently rather than throwing).
- **Fail-first, and a trap worth recording:** the case lives inside a `def` ON PURPOSE. The hoist
  only fires for a while loop in a FUNCTION BODY, so the first draft — same code at top level — was
  already green on the broken toolchain and would have gated nothing.
- **Measured:** `bench.sh`'s `list-fold` js cell went from `n/a` to **0.1007 ms/iter**.

## js-no-tail-call-elimination-overflows-scljet-large-page — a loop the INT lane TCOs blows Node's stack
<!-- status: fixed
     fixed-in: 2246b8d5e
     lane: js
     area: codegen
     kind: bug
     gate: tests/e2e/js-self-tco-gate.sh -->

**Found 2026-07-31 by a passer-by** (I was verifying an unrelated ci.yml change on dispatched run
30649090567 and this came back red in `Conformance shard 3/4`). NOT MINE — recorded rather than
fixed. The diff I was verifying touches no compiler, runtime or backend code.

```
scljet-large-page:
  PASS [INT]
  FAIL [JS ]
    line 1: expected=1|ann ; 2|bob  got=<exit:1>
    RangeError: Maximum call stack size exceeded
        at writeZerosLoop ([stdin]:3729:6940)
        at _call ([stdin]:20:40)
        at writeZerosLoop ([stdin]:3729:7028)      <- repeats to the stack limit
```

**The source says out loud what the JS lane does not honour** — `scljet/write.ssc:73`:

```scala
// Tail-recursive so the interpreter can TCO the large zero-fills (a leaf page's
// free space can be hundreds of bytes); order is irrelevant for zeros.
def writeZerosLoop(count: Int, acc: List[Int]): List[Int] =
  if count <= 0 then acc else writeZerosLoop(count - 1, 0 :: acc)
```

A self tail call in tail position. The INT lane eliminates it; the JS lane emits real recursion
through `_call`, so the depth is the zero-fill length and a large page reaches V8's limit. So this is
a LATENT codegen gap, not a new one — what changed is only whether the corpus crosses the limit.

**What is measured, and what is not.** Green in run 30597944542 (02:02, shard 0) and 30635743363
(13:45, shard 3, SHA `6c6bc1120`); red in 30649090567 (16:55, SHA `f02d77f65`). NO JS backend code
changed in that window — the only `v1/runtime/backend/js/` edit is prose in this file. What did
change is `build.sbt` and two module moves (`markup-core` → `markup/` → `uniml/markup`), which alter
bundle shape, and the failing frame is at `[stdin]:3729` in a single generated bundle.

**Do not take the trigger as diagnosed.** Two greens do not exclude a marginal-stack FLAKE, and the
difference between "a module move grew the bundle" and "this has been one page-size away from
failing for weeks" changes who should care. Cheapest way to settle it: run the case on
`6c6bc1120` and on `f02d77f65` a few times each — if `6c6bc1120` ever fails, it is depth, not the
move.

**The fix is the tail call, whichever way that goes.** Self-tail-call → loop in `JsGen` is the
general answer and would close the whole class; raising Node's stack only moves the page size at
which it breaks.

---

**FIXED 2026-07-31, and the diagnosis above was HALF WRONG in the way that mattered.** "The JS lane
emits real recursion" is not true of the JS lane; it is true of ONE OF ITS TWO function-emission
paths. JsGen has had self-TCO for a long time:

| where the `def` lives | emitted as | self-TCO |
|---|---|---|
| top level | `function f(_a, _b) { let a = …; while(true) { … } }` | yes, `JsGen.scala` genDef |
| inside an `object` / package module | `const f = (a, b) => … _call(f, …)` | **no** — `JsGen.scala:3276` |

So the same function loops at top level and recurses for real once it moves into a module, and no
output comparison can tell until a case gets deep enough. `writeZerosLoop` lives in
`scljet/write.ssc`, which is imported as a module. Measured before the fix:

```js
writeZerosLoop = (count, acc) => { count = _charCodeOrNull(count) ?? count;
  return ((count <= 0) ? acc : _call(writeZerosLoop, (count - 1), [0, ...(acc)])); };
```

and after it, the same shape the top-level path has always produced:

```js
writeZerosLoop = (_count, _acc) => { let count = (_charCodeOrNull(_count) ?? _count), acc = …;
  while(true) { … } };
```

**The trigger question in the paragraph above is now moot but the answer is worth recording**: it was
never the module move. The gap is latent and old; the corpus simply crossed V8's limit.

⚠️ **One guard was copied across too strictly and the symptom was SILENCE.** The top-level path
folds Int parameter coercions into the `let` initialisers; the first version of this fix instead
required `entryGuards.isEmpty`, which is never true for an `Int` parameter — so the transform was
disabled for exactly the numeric loops it exists for, with no error and no diagnostic, just the old
output. Caught by reading the emitted JS rather than the exit status.

**CI-CONFIRMED** on dispatched run 30682235399: `Conformance shard 0/4` reports
`scljet-large-page … PASS [JS ]`, the exact case that produced the RangeError. That run's two red
shards are unrelated and separately attributed — `long-overflow-wrap` (JS/v2, the sibling's own new
case, A/B'd as identical with and without this fix) and `map-getorelse-expr-receiver` (frozen
`v2 FAIL` in the corpus baseline).

Gate: `tests/e2e/js-self-tco-gate.sh`, in `scripts/smoke-ci`. It asserts BOTH placements and asserts
the emitted LOOP, not just the answer — output alone would stay green if someone dropped the
transform and raised the stack instead. Verified against the unfixed compiler: top level stays green
(that path always worked) and both in-object assertions fail, which is the discrimination the gate
exists for.

## js-identity-named-runtime-fn-unreachable-from-a-package-module — the whole std/os surface binds to `undefined`
<!-- status: fixed
     lane: js
     area: codegen
     kind: bug
     fixed-in: 935784308
     gate: tests/conformance/std-os-doc-import.ssc -->

**ALREADY FIXED — bookkeeping only, 2026-08-02.** `935784308` ("a document-level std import binds
its runtime functions instead of `undefined`") added the `__ssc_cap` capture this entry's own
"Fix direction" called for, and the entry was never moved off `open`.

Verified rather than trusted, both `std/os` corpus cases on both lanes:

```
std-os             int: /usr/local/bin/ssc ssc .md          js: /usr/local/bin/ssc ssc .md
std-os-doc-import  int: /usr/local/bin report.md cwd-nonempty  js: /usr/local/bin report.md cwd-nonempty
```

The mechanism is the one the entry predicted and the reason it works is worth keeping: the capture
object is built at the IIFE's **call site**, in the ENCLOSING scope, where the bare name still
resolves to the outer `function` declaration — so an identity-named runtime function becomes
reachable without the TDZ self-reference (`const env = … : env`) that made the intrinsic-rename
branch skip exactly this case.

`gate:` was `none` and is now the two cases above, which is what made this verifiable at all.

**Found 2026-07-31 while asking why `std.os.readLine` could not be added to the js lane.** The answer
was that nothing in `std/os` works there — `env`, `args`, `cwd`, `pathJoin`, `platform`, `exit`, all
of it — and the reason is one line of the extern shim rather than eighteen missing implementations.

**The implementations are all present.** `JsRuntimeFs.scala` emits
`env envOrElse args exit cwd sep pathJoin pathDirname pathBasename pathExtname pathResolve
pathIsAbsolute tempDir tempFile platform homedir hostname exec`, and the emitted bundle contains
`function envOrElse(key, def) { … }` at top level. What fails is the BINDING built for the
`package:`-module object (`JsGen.scala:3168`):

```js
const envOrElse = (typeof _ssc_ui_envOrElse !== 'undefined') ? _ssc_ui_envOrElse
                : (typeof globalThis.envOrElse === 'function' ? globalThis.envOrElse : undefined);
```

Two probes, and for these names both miss:

* `_ssc_ui_envOrElse` — the host-stub spelling; `std/os` does not use it;
* `globalThis.envOrElse` — a plain `function` declaration in a CommonJS module is NOT a property of
  `globalThis`.

There is a third branch, the intrinsic-rename fallback, and it is skipped precisely BECAUSE these
names are honest: it fires only when the intrinsic renames to a DIFFERENT target (`target != fname`),
which is deliberate — an identity rename would emit `const csrfToken = … : csrfToken`, a TDZ
self-reference to the const being declared. So a runtime function that keeps its own name is exactly
the case with no route in.

`std.os.envOrElse` is therefore `undefined`, and the failure surfaces far from its cause as
`Error: not callable: ()` — the same shape as `js-extern-shim-shadows-globalthis-implementation`,
which fixed the `globalThis` half of this and left the identity-named half.

**⚠ CORRECTION to the message of `935784308`, which fixed this.** That commit's message explains an
earlier inconsistency by claiming the js lane's batch emission hides ~34 per-file failures, so "the
shard's verdict depended on which path ran". **That is false, and the inconsistency it explains never
existed.** Measured afterwards on a clean `origin/main`, shard 0/4:

```
batch     -> 88 passed / 2 failed   (map-getorelse-expr-receiver V2; object-var-member-scope JS+V2)
no-batch  -> 88 passed / 2 failed   (the same, plus object-var-member-scope INT)
```

Identical verdicts, and the shard never disagreed with itself on the same code either: 55/35 came
twice from the build carrying the TDZ bug and 88/2 from the build without it. One variable, one
effect.

How the false claim happened, since that is the reusable part: I ran two individual cases against
both builds, they behaved identically, and I concluded my change was not implicated — but both
happened to be PRE-EXISTING failures, so that test could not have shown a difference either way.
Instead of calling the sample inconclusive I invented a confounder that would explain a contradiction
I had never established, and wrote it down as fact.

The fix and its attribution numbers stand (base / broken / fixed = 88-2 / 55-35 / 88-2 under one
emission path). Only the story about why the earlier readings looked inconsistent is retracted.
`object-var-member-scope` failing on js is a genuine pre-existing defect, visible in both modes.

**Fix direction, and it dodges the TDZ rather than fighting it:** capture the function OUTSIDE the
scope that shadows it. Function declarations hoist, so a top-level
`const __ssc_cap_envOrElse = (typeof envOrElse === 'function') ? envOrElse : undefined;` emitted
before the module IIFE sees the real one regardless of order, and the shim can use it as a fallback
ahead of `globalThis`.

**Blast radius is the reason this is filed rather than done in passing:** the shim builds EVERY
extern binding for every `package:` module on the js lane, so the change wants the js corpus lane as
its check, not one case. `tests/conformance/std-os-readline.ssc` gates `[int, v2]` today and should
gain `js` in the same commit that fixes this.

## js-object-var-member-is-never-emitted — the codegen drops `var` members of an `object`
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 4b1460e20
     gate: tests/conformance/object-var-member-scope.ssc -->

**FIXED 2026-07-31** by `object-var-member-family`. `genObjectAsExpr` had a `case Defn.Val` arm and
**no `Defn.Var` arm at all**, so a `var` member was silently dropped: reads from outside reached
`_dispatch` and threw `Method not found: n on [object Object]`, and the object's own methods assigned
a bare name that resolved to whatever outer binding existed.

Two halves, and the second is the one a naive fix misses:

- `let`, not `const` — the object's own methods mutate it;
- it leaves the IIFE as a **get/set accessor pair**, not a shorthand property. A shorthand copies the
  value at return time, so `Counter.bump()` would update the closed-over `let` while `Counter.n` kept
  answering the initial value — the object would look immutable from outside while its methods
  "worked".

All ten rows of `object-var-member-scope` now match the jvm golden on js, including the shadowing
block (a top-level `var n` beside `object Counter { var n }`) that the case exists for. Its baseline
`js FAIL` row is removed in the same commit.


A `var` member of an `object` produces no binding at all. The emitted IIFE closes over a name that
was never declared, and the member is absent from the returned object:

```scalascript
object O:
  var n: Int = 7
  def value(): Int = n
```

emits

    const O = (() => { const value = () => n; return { value }; })();

so `O.value()` is `ReferenceError: n is not defined` and `O.n` is
`Error: Method not found: n on [object Object]`. A `val` member is emitted correctly
(`const k = 7`), which is what makes this a `var`-only gap rather than a general object problem.

FIX DIRECTION: emit `let n = 7;` inside the IIFE and expose it on the returned object as an
accessor pair, so reads, the object's own method writes, and an external `O.n = v` all land on one
cell:

    const O = (() => { let n = 7; const value = () => n;
                       return { value, get n(){return n}, set n(v){n=v} }; })();

Found by `object-var-member-scope`, the gate for the INT-lane twin
(`v1/runtime/backend/interpreter/BUGS.md` `object-var-member-assignment-writes-a-top-level-global`).
Same source shape, three lanes, three different failures — this one is the only one that is loud.

## js-int-and-double-are-the-same-type-test — five type names shared one predicate, so ARM ORDER decided the answer
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded
     gate: tests/conformance/type-ascription-number.ssc -->

**Found and fixed 2026-07-31** by the type-ascription census (`specs/type-ascription-matrix.md`).

`Int`, `Long`, `Double`, `Float` and `Number` all emitted `typeof x === 'number'`, so `case _: Int`
and `case _: Double` were literally the same test and whichever arm came FIRST won:

| arms as written | `1.5` | `7` | jvm says |
|---|---|---|---|
| `Int` then `Double` | **`Int`** | `Int` | `Double`, `Int` |
| `Double` then `Int` | `Double` | **`Double`** | `Double`, `Int` |

Wrong in both orders, in **opposite directions** — the signature of a missing DISTINCTION, not a
missing arm. Adding arms would not have helped; the order was carrying the answer.

Fixed by reconstructing the distinction from integrality: `Int`/`Long` require
`Number.isInteger`, `Double`/`Float` require its negation, `Number` stays any number.

**Known limit, stated rather than hidden:** JS has one number type, so `2.0` is indistinguishable
from `2` and answers `Int`. That is a representation gap of the same family as Char-vs-String and
Set-vs-List on this lane, and it needs a boxed Double before a type test can do better. Strictly less
wrong than before, where the answer depended on the order the arms happened to be written in.

**The gate runs BOTH arm orders**, because a case written with one order would have passed on the old
code for whichever value suited it — green in both states, the failure this repo keeps paying for.

## js-char-type-test-cannot-tell-Char-from-String — `case _: Char` matches a String
<!-- status: fixed
     lane: js
     area: codegen
     kind: wrong-output
     gate: tests/e2e/char-type-test.sh
     fixed-in: 32613ef58 -->

**FIXED 2026-08-10 on all four lanes, in two steps — and the second one was supposed to be
impossible.** Re-measured on a fresh build across four
lanes, this was TWO defects wearing one entry, and only one of them is the representation gap:

| `kind(x)` with arms Char / String / Int / _ | `'c'` | `"c"` | `99` | `s.charAt(0)` |
|---|---|---|---|---|
| `--v1` (the census oracle) | Char | String | Int | Char |
| `bin/ssc` before → after | `Int` → **Char** | String | Int | `Int` → **Char** |
| `--bytecode` before → after | `Int` → **Char** | String | Int | `Int` → **Char** |
| `run-js` before → after | `String` → `String` | String | Int | `other` → **Char** |

* **native / bytecode — one arm, and the ORDER is the fix.** `CharV extends IntV`, so a Char fell
  into the `IntV` arm of `__isTag__` and answered `Int` — the one question where being an IntV is
  the wrong answer. A `CharV` arm placed BEFORE it. This lane now answers Char for the literal too,
  because a char literal there really is a `CharV`.
* **js Char RESULT — one arm.** `s.charAt(0)` is a `_Char` box and the type-test mapping had no
  `Char` case at all, so it fell to the `_type === 'Char'` default, which is false for a box whose
  only own key is `__c`. Now `_isChar`.
* **js char LITERAL — needed the box, and the box was affordable.** `'c'` was a plain
  one-character string, and `case _: String` on a genuine one-character string must keep working, so
  no arm could serve both. `JsGen` carried a note that making the representation uniform "is a
  separate change with its own corpus run". **The corpus was run and it passed**: smoke 81/81,
  JsGen suites 101/101, with `Lit.Char` emitting `_char(code)`.

  The warning had been read as a verdict for weeks; measured, the surface was small. Eleven sites
  read `Lit.Char` and only **two** emitted the string — expression position and the pattern literal
  — and they have to move TOGETHER, or `case 'a' =>` compares a box with a string and never matches.
  Everything else was already in place: `_Char.valueOf` is the code point, `_toString` is the
  character, `_eq` compares two boxes by their only own key `__c`, and `_asChar` normalises a
  `Char`-typed param. So `'a' + 1` is 98, `"x" + 'a'` is `"xa"`, `case 'a' =>` matches, and
  `case _: Char` finally has something true to say.

  **It also DELETED code.** `js-char-is-a-plain-string` had just fixed the numeric half by widening
  a char-literal operand and tracking `charLitVars` — name-keyed, module-global evidence with a
  param-shadowing hazard that needed clearing at two registration sites. The box subsumes all of it:
  `valueOf` does the widening at every operator, so the tracking, the clears and the operand rewrite
  came out and the 25-row differential gate stayed green without them.

**The census said this was not fixable, and the census was out of date.**
`specs/type-ascription-matrix.md` (2026-07-31) filed native under "a missing TYPE — not fixable in a
type test at all", citing `v2-char-is-an-int`. True when written; `f39448c96` then moved `charAt`
onto a distinct `CharV` class, which silently promoted the row from "needs a representation" to
"needs one arm" — and nothing re-measured it for ten days. The matrix now says so, because a spec
that says *impossible* about a one-line fix costs more than no spec.

**The gate asserts the gap, not just the fixes.** It pins js still answering `String` for a literal,
so the day that changes the gate reports it instead of passing quietly, and it refuses to judge at
all if `--v1` stops matching the census.

**Found 2026-07-31** by the same census. A char literal compiles to a plain JS string
(`val c = 'c'` emits `const c = "c";`), so `kind('c')` answers `String` where the JVM, INT and the
reference all answer `Char`.

The runtime DOES have a `_Char` box with an `_isChar` predicate — it exists to tell a Char RESULT
from a Seq result — but literals do not use it. So this is not a missing arm: a test would have to
guess, and `case _: String` on a genuine one-character string must keep working.

**Representation gap.** Same family as `Set`-vs-`List` on this lane and `v2-char-is-an-int` on the
native one; it needs char literals to carry the box before the question has an answer. Answering
`String` is at least consistent with what the value IS.

**Renamed 2026-08-10 — it was NOT the entry the slug now refers to.** `b37f7b0fc` filed a new entry
the same day under `js-char-is-a-plain-string` (a char literal not widening in a numeric context),
which turned `bugs-index` red on `main`: two entries, one slug, in this same file. The NEWER one
keeps the name because two things already cite it — `specs/gateless-triage.md` and
`tests/e2e/v2-char-numeric-position.sh` — and both describe that defect, not this one. This entry is
about the TYPE TEST, and its heading now says so.

**Still open after that fix, and it is the harder half.** The widening is done by COERCION at the
operator; the type test needs the value to carry the `_Char` box, which is the representation change
that fix deliberately does not make. Re-measured 2026-08-10: `bin/ssc` answers `other`, not `Char` —
the native lane has its own gap (`v2-char-is-an-int`), so this entry's original claim that "the JVM,
INT and the reference all answer Char" no longer holds for `bin/ssc`.

## js-pass-error-not-formatted-by-its-module-function — a case class from a PACKAGED module lost its body methods, so `toString` printed the raw record
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded
     gate: examples/dsl-mini-language.ssc -->

**FIXED 2026-07-30**, and it was not a formatting bug — it was HALF THE EMITTER MISSING.

JsGen compiles a case class's body methods (`override def toString`, a trait method implemented on the
class) into `_registerExt('method', …, 'Type')` so `_dispatch` finds them via
`_extensions['Type:method']`. That compilation existed ONLY on the top-level path. `genObjectAsExpr` — the
path every module with `package:` front-matter goes through, i.e. every std module — had no body-method
compilation at all.

Measured directly, same declaration in both positions:

| `case class E(…)` with `override def toString` | `_registerExt('toString'` in the emitted JS |
|---|---|
| declared locally | **1** |
| imported from a `package:` module | **0** |

So `PassError` printed `PassError(name-resolve, undefined variable: z, <unknown>, 0, 0)` where int and v2
both print `[name-resolve] undefined variable: z`. **A local case class worked, which is exactly what kept
this hidden** — the feature looked present.

**Fixed by EXTRACTING a shared helper, not by copying the arm.** `caseClassBodyMethodRegistrations` returns
the lines, because the two callers place them differently (top level writes to `sb`; `genObjectAsExpr`
collects into `decls`). Copying would have set up the next drift, which is the failure this session hit
four separate times — the JS string escaper twice, the markdown inline scanner, and the literal-pattern
compiler.

**Outcome:** `dsl-mini-language` goes from a corpus SKIP with no verdict on any lane to int golden +
**js PASS** + **v2 PASS**. Full three-lane corpus: 1034/1082 PASS cells, **zero regressions**, one
improvement — this case.

**Original report (superseded 2026-07-30):**

**Found 2026-07-30** by `int-ext-on-alias-dispatch`, after two interpreter fixes let `dsl-mini-language`
run at all. int and v2 now agree; js is the only lane that differs, and only in formatting:

```
int / v2   [name-resolve] undefined variable: z
js         PassError(name-resolve, undefined variable: z, <unknown>, 0, 0)
```

So the case reaches the same result on js and then renders it with the DEFAULT record `toString` instead
of `std/dsl/passes.ssc`'s `formatReport`. The likely shape, given what the same module already exposed
today: a function from an imported module is not being reached on js, the way
[[js-imported-extension-method-not-dispatched]] was not — that one was an extension emitted outside its
module's IIFE. Worth checking whether `formatReport` (a plain `def`, not an extension) hits a different
version of the same scope problem before assuming it is a `Show`/`toString` issue.

**Not fixed here on purpose:** it is a different cause from the two interpreter defects that surfaced it,
and folding it in would have made a three-lane claim out of a one-lane fix.

## js-unused-val-drops-side-effecting-call — `val unused = eff()` elides the call, side effect and all
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 4f8c8cb9f
     gate: tests/e2e/js-shaker-keeps-effectful-binding.sh -->

**FIXED 2026-08-01** by `js-codegen-pair`. The fix is in the **TreeShaker**, not the emitter, and
the entry's own suggestion ("whatever prunes unused local bindings in JsGen") would have made it
worse: `isReachableStat` filters on the NAME, so keeping the statement without making the name a
root leaves the initialiser referring to an `eff` the shaker has already pruned — a `ReferenceError`
traded for a silent no-op. A top-level `val`/`var` whose initialiser is not *trivially* pure is now
a reachability ROOT, which keeps the statement AND drags its dependencies in.

`isTriviallyPure` is deliberately tiny — literals, names, and tuples of those. Everything else (any
call, selection, `new`, block) counts as effectful. A selection is NOT pure: `O.x` can be a
parameterless `def`. Being wrong this way costs bundle bytes; being wrong the other way deletes a
side effect.

**The mode matters and the original report did not name it.** Tree-shaking runs on `emit-js`, NOT on
`run-js` — which is what the conformance runner uses. A conformance case would have printed
`SIDE-EFFECT` before the fix and after it and gated nothing, so the gate is an e2e script that emits
the bundle and runs it. It also asserts that a genuinely PURE unused binding is still dropped, so a
"fix" that merely stopped shaking would fail it.



**Found 2026-07-30**, isolated to six lines, while repairing a conformance case that had quietly
stopped testing anything because of it.

```scalascript
def eff(): String =
  println("SIDE-EFFECT")
  "r"
val unused = eff()
println("end")
```

| lane | output |
|---|---|
| INT | `SIDE-EFFECT` then `end` |
| native | `SIDE-EFFECT` then `end` |
| **js** | **`end` only** |

The binding is unused, so the JS lane drops it — **and the call with it.** Eliding a call is only
sound when the call is pure, and nothing here establishes that; `eff` prints.

**Why it is worse than one missing line.** A test that calls something for its effect and binds the
result to a name it does not use is a normal shape, and on this lane that test silently stops
executing the thing it is testing. That is exactly how it was found: `v2js-unit-pattern` gates a
CRASH by calling the pattern that used to crash, and with the result bound to an unused `val` the
call vanished on JS — a green lane that ran none of the code it was written for. The case now uses
the value (`.length > 0`) to defeat the elision, which is a workaround in the test, not a fix here.

**Where to look:** whatever prunes unused local bindings in `JsGen` — the decision needs an
effect check on the initialiser, or it must simply not drop initialisers that are calls.

## js-imported-extension-method-not-dispatched — an extension body was hoisted OUT of its module's scope, so its free names were undefined
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded
     gate: examples/dsl-yaml-like.ssc -->

**FIXED 2026-07-30.** `genObjectAsExpr` collects every object member into `decls`, which only becomes the
IIFE body at the end — but an extension group went through `genStat`, which writes straight to `sb`. So the
extension landed AFTER the `})()` that closes the module's scope. The emitted text was literally

```
… return { …, runLayout }; })(); return { parsing }; })());
function _ext_Parser_withIndent(p, n) { … return _call(PWithIndent, p, n); }
```

and `PWithIndent` is declared INSIDE that IIFE. The only module names visible at top level are the ones a
CONSUMER named in its import list (each emitted as `const <name> = <pkg>.<name>;`), so anything else was out
of scope — which is why no consumer's import list should have been able to affect whether a module's own
extension works.

The extension is now emitted into `decls`, i.e. inside the IIFE, captured by offset the same way
`compileJsSegments` does (`sb.substring(ssStart)`). Registration stays global because `_registerExt` is a
global function: it needs the function reachable, not global.

**Measured:** `dsl-yaml-like` on js goes from `ReferenceError: PWithIndent is not defined` (surfacing as
`Method not found: withIndent`) to **byte-identical to the int lane**.

⚠️ It took TWO more defects to get there, each masked by the previous one — worth stating because the chain
is the interesting part:
1. `_dispatch`'s untyped fallback swallowed the ReferenceError and reported `Method not found`, naming a
   symbol two levels from the cause. Fixed first, which is what made the real error readable.
2. with the scope fixed, the case still diverged by two extra lines: a top-level ASSIGNMENT was being
   auto-printed. Filed and fixed as [[js-toplevel-assignment-auto-printed]].

**Original report (superseded 2026-07-30):**

**RE-DIAGNOSED 2026-07-30 — my own first diagnosis was wrong, and wrong about the mechanism, not just
the wording.** It said the js lane "does not dispatch an extension method that came in through a module
import". Dispatch works fine. Three checks killed that story:

| check | result |
|---|---|
| is `_registerExt('parseLayoutWith', …)` emitted? | **yes**, and 21 extensions register in total |
| does it come before the call site? | yes |
| does `_dispatch` have an untyped fallback? | yes — typed key, then every candidate in `_extensions[method]` |

So the method IS found and IS called. It **throws**, and the fallback's `catch` discarded the error.

**The real cause, two symbols and two levels away from the message.** Patching the emitted JS to log what
the fallback swallowed:

```
[SWALLOWED] withIndent:        ReferenceError: PWithIndent is not defined
[SWALLOWED] parseLayoutWith:   Error: Method not found: withIndent on PChoice(…)
```

`withIndent` is an extension in `std/parsing/layout.ssc` whose body constructs `PWithIndent`. JsGen emits
an extension as a TOP-LEVEL function (`_ext_Parser_withIndent`) whose body keeps the module's names
UNQUALIFIED — but the only module names that exist at top level are the ones the CONSUMER named in its
import list, each emitted as `const <name> = std.parsing.<name>;`. Verified 1:1: the consumer imports
`PSameIndent` (which gets a const) and not `PWithIndent` (which does not), and `PWithIndent` lives only
inside the module's IIFE.

**So the determinant is the consumer's import list, not the module's `exports:`** — which means adding
`PWithIndent` to the module's exports is correct for consistency (`withIndent` constructs it and its two
siblings are exported) but does NOT fix this: no consumer should have to import a module's internals to
make that module's own extension work.

**Three candidate fixes, in increasing order of correctness:**
1. emit `const <name> = <pkg>.<name>;` for every name an extension body references, not only for the
   imported subset — smallest change, but adds top-level bindings the consumer did not ask for and can
   collide with the consumer's own names exactly as the current scheme can;
2. emit the extension function INSIDE the module's IIFE and call the global `_registerExt` from there —
   the closure then captures the module scope, which is what the interpreter effectively does;
3. qualify the module's names inside extension bodies (`std.parsing.PWithIndent`), which needs the package
   prefix available at extension-emission time.

(2) looks right and is close to how `_registerExt` already works — the registration is a global side
effect, so it does not need the function to be global.

**Half of the pair is FIXED:** the swallowing is gone. `_dispatch` now remembers the first candidate error
and reports it, so the message names the real cause instead of hiding it. That is what turned this from a
two-hour trace into a one-line read.

**Original report (superseded 2026-07-30):**

**Found 2026-07-30** by `jsgen-char-escape`: with [[jsgen-char-literal-escape]] fixed, `dsl-yaml-like`
stops failing to PARSE on js and fails one step later.

```
Method not found: parseLayoutWith
```

`parseLayoutWith` is declared at `v1/runtime/std/parsing/layout.ssc:292`, inside
`extension [A](p: Parser[A])` (line 288). So the js lane does not dispatch an extension method that came
in through a module import.

**The interpreter had the same surface and needed TWO fixes** (`91c326d1f`): the import-binding loop
refused to even NAME such a method, and `exportedExtensions` had to be copied wholesale for the method
to dispatch. The js lane inlines imports, so its failure mode differs — but it is worth attacking as one
family rather than as this one case.

**Blocks** `dsl-yaml-like` on js (int and v2 both PASS it).

## js-toplevel-assignment-auto-printed — `r = expr` as a block's last statement printed its value; an assignment is Unit
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded
     gate: examples/dsl-yaml-like.ssc -->

**FIXED 2026-07-30.** JsGen auto-prints the value of a top-level block's last expression
(`{ const _auto = …; if (_auto !== undefined …) _println(_show(_auto)); }`). A `Term.Assign` was going
through that path, so

```scalascript
var yamlValueRef: Parser[Any] = Parser.fail("…")
…
yamlValueRef = scalarValue | yamlMapping | yamlSeq     // last statement of its block
```

dumped a `PChoice(PChoice(PMapped(…), …), …)` parser tree on js that the int lane does not print. An
assignment evaluates to Unit in Scala, so printing its value is wrong on any lane.

**Found only because another fix unmasked it.** The program used to die earlier with
`ReferenceError: PWithIndent is not defined`
([[js-imported-extension-method-not-dispatched]]), so the extra output had never been reachable. Third time
in one day that fixing one defect exposed the next — worth expecting rather than being surprised by.

`Term.Assign` now emits as a plain statement. `dsl-yaml-like` on js became byte-identical to int.

## jsgen-char-literal-escape — an escaped Char literal was emitted RAW, so the generated JS did not parse
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 409939e3e
     gate: tests/conformance/char-literal-escapes.ssc -->

**Fixed 2026-07-30** (`409939e3e`). `JsGen.scala:4191` escaped ONLY the double quote in a Char literal,
so `'\n'` / `'\t'` / `'\r'` / `'\\'` landed as a real control character inside a JS string literal and
left it unterminated — `SyntaxError: Invalid or unexpected token`, before a line of the program ran.

Impact was never one case: `std/parsing/layout.ssc:67` computes a column with `lastIndexOf('\n')`, so
EVERY consumer of that module was dead on js. It stayed invisible while `dsl-yaml-like` was a corpus
SKIP; removing that SKIP (`c66ed4825`) exposed it.

Fixed at the cause. `Lit.String` (`:4187`) carried the full escape chain INLINE while the Char arm
carried a hand-written subset — that duplication is HOW the arms drifted. Both now call the existing
`escapeJsString` (`:5699`), as does the third partial copy at `:5494` (`jsLit`, which handled only `\\`
and `"`).

Emitted bytes, before -> after:

```
_println(_dispatch("a\nb", 'lastIndexOf', ["<RAW NEWLINE>"]));
_println(_dispatch("a\nb", 'lastIndexOf', ["\n"]));
```

Fail-first proven on the exact case that landed: revert, rebuild, `run-js` exits 1 with the SyntaxError;
restore, rebuild, green on int / js / v2. `backendInterpreter/test` (where the JsGen tests live —
`backendJs` has no test tree): 1854 succeeded, 0 failed.

⚠️ One intermediate observation looked like a second defect and was not: with the fix reverted, Node's
error display appeared to show the STRING literal broken too. Reading the emitted bytes settled it —
`Lit.String` was always correct, and Node was printing a physical source line that the raw newline had
split.

## js-class-method-named-arg-nan — a named arg to a CLASS method is `NaN` on the JS lane
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 4f8c8cb9f
     gate: tests/conformance/named-arg-defaults.ssc -->

**FIXED 2026-08-01** by `js-codegen-pair`. **Two independent causes behind one symptom**, and
either one alone still produced a wrong answer — the first fix moved `6/NaN` to `NaN/3`, which is
what showed there was a second.

1. **A class's METHODS were never registered in the param-order table.** `collectParamOrdersFromModule`
   descended into objects and recorded a case class's CONSTRUCTOR, but nothing walked a class BODY.
   With no param order, named arguments were not reordered and landed in slots the callee does not
   read. Now `deep` descends into every `Defn.Class` body, case or not.
2. **Class-method DEFAULTS were dropped entirely.** `caseClassBodyMethodRegistrations` built the
   emitted signature from bare parameter names, so an omitted parameter stayed `undefined`.

The defaults are applied **in the body**, not as JS default parameters, and that is forced rather
than chosen: a default may read a FIELD (`def shift(dx: Int = x, …)`), and the fields only exist
after `const {x, y} = _self`. As a JS default it would evaluate in the parameter scope, before that
`const` — a TDZ error or the wrong binding.

`tests/conformance/named-arg-defaults.ssc` now covers class methods, which it could not before.
Verified against the jvm oracle, which is also how a THIRD defect surfaced — on the INT lane, filed
as `int-field-valued-default-undefined-on-empty-call`: `P2(5).shift()` with no arguments dies there
while jvm and js both answer `10/2`. js only started agreeing with the oracle in this commit, which
is why a lane comparison had never shown it.



**Found 2026-07-30** while fixing [[v1-interp-object-method-named-arg-wrong-slot]]. Object methods
are fine on every lane now; **class** methods are not, on JS only.

```scalascript
class K:
  def m(a: Int, b: Int = 5, c: Int = 7): Int = a + b * 10 + c * 100

case class P(x: Int, y: Int = 2):
  def shift(dx: Int = x, dy: Int = 0): String = (x + dx).toString + "/" + (y + dy).toString

println(K().m(1, c = 9))
println(P(5).shift(dy = 1))
```

| | INT | JS | JVM (real Scala) |
|---|---|---|---|
| `K().m(1, c = 9)` | 951 | **`NaN`** | 951 |
| `P(5).shift(dy = 1)` | 10/3 | **`6/NaN`** | 10/3 |

`NaN` rather than a wrong number, so the value reaching the parameter is `undefined` — the emitted
call passes the named argument in a slot the callee does not read, and arithmetic on `undefined`
gives `NaN`. The second row shows it is not only the ORDER: `dy = 1` also loses its value, and the
`dx` default (which reads the field `x`) is not applied either.

This is why `tests/conformance/named-arg-defaults.ssc` covers object methods but **not** class
methods — the case would pin a divergence rather than a behaviour. INT and JVM agree, so JS is the
odd lane and the JVM answer is the target.

## bench-jvm-js-lanes-dead-silently — two whole benchmark columns were unmeasurable, and printed `n/a`
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-28** (`bench-tier-dead-lanes`). Found while running a full 9-backend
sweep for the first time since the v2 work.

**The defect.** `ssc bench --backend jvm|js` shells out to `sscCmd` to run `emit-scala` / `emit-js`,
and `sscCmd` resolved to `<ssc.lib.path>/bin/ssc` — the **standard-tier** launcher. Both commands
are TOOLS-tier: the standard launcher answers `'emit-scala' requires the optional ScalaScript
tools/compatibility tier` and exits non-zero. `timeJvm`/`timeJs` then hit
`if emit.exitCode != 0 then return None` and the lane printed `n/a`.

**Why it stayed hidden.** That early `return None` sits ABOVE the `catch` that honours
`SSC_BENCH_DEBUG`, so even the debug switch printed nothing. And `n/a` is the *honest* output for a
workload a backend genuinely cannot run (rust has no `LazyList`), so two structurally different
claims — "unsupported" and "broken" — printed the same character. Same shape as the v2 column in
`specs/v2-runtime-perf-vs-v1.md` §0, different cause. It presumably broke when the standard/tools
tier split landed; `bench`'s launcher resolution was never updated.

**Fix.** `sscCmd` prefers `bin/ssc-tools` and falls back to `bin/ssc`; every no-measurement path in
`timeJvm`/`timeJs` now prints `bench: <backend> produced no measurement — <stage> failed: <reason>`
on stderr, unconditionally. Verified: `jvm` 0.2752 ms/iter, `js` 18.1 ms/iter on `arith-loop`, both
previously `n/a`.

**Second, independent defect found by the new diagnostic** (this is what a loud lane buys): the
generated bench wrapper used `x += 1` in its pre-warm and timing loops, and the JS backend cannot
compile compound assignment (below). So even with the right launcher, every js measurement died —
the harness was reporting its own incompatibility as the backend's `n/a`. The wrapper now spells
those counters `x = x + 1`.

## scljet-full-suite-int-lane-drops-one-case — exactly one INT case per full run produces no output at all
<!-- status: fixed
     lane: js
     area: conformance
     fixed-in: unrecorded
     gate: tests/conformance/run.sc -->

**Status:** FIXED 2026-07-29 in `tests/conformance/run.sc` (ARCH-2). Root cause found; the original
diagnosis in this entry was WRONG and is corrected below.

**Signature.** A full run failed exactly one INT case with every line `got=<missing>` — zero output,
not wrong output — and the case CHANGED between runs (`scljet-sql-real-literal` once,
`scljet-sql-right-full-join` another), with and without an unrelated change, 115/116 each time.

**What I first wrote here was wrong.** This entry claimed *"the INT lane calls `run(sscTools(...))`
and, unlike the JS/JVM lanes, does not route through `outputWithFailureContext`, so the child's
stderr and exit code are discarded."* That is false: `run(cmd)` **does** call
`outputWithFailureContext`. Reading the helper instead of trusting the note is what found the real
cause.

**The real cause: a truncated batch is used as if it were data.** The INT and JS lanes do not spawn
a process per case — `batchLane` runs the whole corpus in ONE JVM via `ssc-tools run-batch`, whose
documented contract is:

> the marker line is written to STDOUT and flushed **before** each case … a case that calls `exit()`
> terminates the JVM (unavoidable) — consumers must fall back to per-case runs **for files missing
> from the batch output**.

When that JVM dies mid-case (OOM-kill under a loaded runner, `exit()`, anything), the marker for the
in-flight case has already been flushed and nothing follows it. `splitBatch` therefore yields
`sections(case) = ""` — **present and empty, not missing**. The call site
`intBatch.getOrElse(name, run(...))` finds the key, never takes the fallback, and hands the emptiness
to the comparison as though the program had genuinely printed nothing. The letter of the contract was
honoured; its intent was missed. `batchLane` also passed `check = false` and never read `res.err`, so
neither the exit code nor the child's stderr reached anyone.

That explains every observed property: exactly ONE case (the JVM dies once), always the in-flight one,
a different case each run (whichever met the limit), all-`<missing>` output, and passing when run
alone.

**Fix.** `batchLane` now inspects the exit code. On a non-zero exit it drops the LAST marked case
from the map — the one that was in flight — so it falls back to its own per-case run, which goes
through `run(...)` and therefore reports `<exit:N>` plus stderr. Only the last section is distrusted,
so cases whose expected output is legitimately empty are not forced onto the slow path. The batch's
stderr tail is printed too, so the next occurrence says WHY.

**Proven red-then-green** with a deterministic reproduction rather than by waiting for the flake: a
wrapper around `bin/ssc-tools` truncates `run-batch` output after the second marker and exits 137.

```text
old code:  coroutine-basic FAIL [INT]  line 1: expected=Yielded(1) got=<missing>   3 passed, 2 failed
new code:  [batch] run-batch exited 137 — distrusting the in-flight case
           'coroutine-basic'; it will be re-run on its own                          5 passed, 0 failed
```

Unwrapped control run: 5/5, no `[batch]` line, no regression. Both batch lanes (INT and JS) are
covered — the reproduction tripped each.

## scljet-wal-recover flakes under parallel load — INT lane produces no output at all
<!-- status: open
     lane: js
     area: conformance
     gate: tests/conformance/run.sh -->

**Status:** open (found 2026-07-28 by `ssc-fork-heap-measurement`; not reported by a user).

**Symptom.** In a 121-case run (`--only 'scljet-*,uniml-*,json*' --no-memo`) with ~70 concurrent
build processes on the host, `scljet-wal-recover` failed on the **INT lane only**, with every
expected line `got=<missing>` — i.e. the lane produced NO output, not wrong output. The JS lane
passed in the same run.

```
scljet-wal-recover:
  FAIL [INT]
    line 1: expected=frames: 2, dbSize: 2  got=<missing>
    line 2: expected=page2 latest=ccc: true, page1 from base: true  got=<missing>
    line 3: expected=corrupt frame dropped: true  got=<missing>
```

**It is NOT a memory-cap regression, and that was checked before filing.** Compare-first, same case,
both ways:

```
JDK_JAVA_OPTIONS=-Xmx2g tests/conformance/run.sh --only 'scljet-wal-recover' --no-memo   -> 1 passed
                        tests/conformance/run.sh --only 'scljet-wal-recover' --no-memo   -> 1 passed
```

It passes in isolation under a heap cap and without one. No `OutOfMemoryError` appears anywhere in
the failing run's output (0 matches). So the trigger is concurrency/load, not heap.

**Why it matters.** `<missing>` output on a WAL *recovery* test is the shape a timeout or a killed
subprocess makes, and a recovery test that silently produces nothing under load is exactly the case
you least want to be flaky. It also makes the corpus non-deterministic under CI contention, where a
red is supposed to mean a real regression.

**Next step for whoever picks this up:** re-run the 121-case slice a few times to establish the
flake rate, then capture the INT lane's stderr for the failing attempt (the runner reports
`<missing>` without saying whether the process timed out, crashed, or exited 0 silently — that
distinction is the whole diagnosis). Owner lane: `scljet-*`.

## js-treeshake-prunes-mirror-ctor — a Mirror's `fromProduct` calls a constructor the shaker deleted
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/conformance/v2-mirror-surface.ssc
     fixed-in: dd56c4b8d -->

**Re-verified 2026-08-02:** the named gate is green — `contract.sc --only v2-mirror-surface
--lanes int,js,v2` reports 2/2 PASS. `dd56c4b8d` resolves and is an ancestor of `origin/main`, so
the citation is one everyone can follow.

**Status: FIXED 2026-07-28** in `dd56c4b8d` (`v2-mirror-fromproduct`) — found while EXTENDING
`tests/conformance/v2-mirror-surface.ssc` to cover `fromProduct` — the case was green before the
new line, which is the point of adding it).

**Reproduction, before the fix** (`emit-js` is what the conformance JS lane runs):

```scalascript
case class P(x: Int, s: String)
val m = summon[Mirror.Of[P]]
println(m.fromProduct(List(1, "hi")))
```

```
ssc-tools emit-js  <case> | node   -> ReferenceError: P is not defined
                                        at Object.fromProduct
ssc-tools emit-js --no-tree-shake  -> P(1, hi)
```

`grep -c '^function P(' ` on the shaken bundle is **0** — the constructor is gone, while the mirror
that calls it is still emitted.

**Root cause.** JsGen emits `_sscMirror_<T>` for every product class of a module that mentions
`Mirror`, and that mirror's `fromProduct` closure calls `T(...)`. The shaker cannot see that
reference: it is created by the EMITTER, is not in the AST, and the only mentions of `T` in source
are type positions (`summon[Mirror.Of[T]]`) which `collectNames` skips by design. Exactly the shape
of the `derives` root fixed earlier the same day
(`js-lane-missing-derives-and-coroutinecancel`), one step over — and it survived that fix because
`derives` was made a root while plain `Mirror` use was not.

**Fix.** A product class of a Mirror-using module is a reachability root. Gated on the module
actually mentioning `Mirror` (mirroring JsGen's own `moduleUsesMirror`), so a module that never uses
reflection shakes exactly as before; it can only ever KEEP code the emitter is about to reference.

**Lesson worth keeping:** every emitter-synthesized reference is invisible to the shaker. Two of
them have now bitten in one day. When adding an emitter that names a user symbol, add its root in
the same change.

## v2-mirror-fromproduct-stub — the last missing `Mirror` member still evaluates to `Stub`
<!-- status: fixed
     lane: js
     area: front
     fixed-in: dd56c4b8d -->

**Status: FIXED 2026-07-28** in `dd56c4b8d` (`v2-mirror-fromproduct`). The Mirror now carries the constructor as
a FOURTH field, pre-shaped to take the argument list (`(xs) => T(xs(0), …)`), and `fromProduct` is
one tag-level `__regmethod__` that applies it — so it needs no per-type method and no VM change.

Two details that decided the shape:
* the element reads use the APPLICATION form `xs(k)`, not `xs.apply(k)`, because the latter is
  itself unimplemented on the native lane (`v2-list-apply-method-stub`) — using it would have made
  this fix depend on that one;
* a tagged method is invoked with `(self :: args)`, and in a `lam 2` the FIRST parameter is
  `local 1`, so self = `local 1` and the list = `local 0`. Getting that backwards produces a
  plausible-looking closure that fails only at call time.

Both fronts, as always for the differential gate. Verified `P(1, hi)` and `Solo(true)` on INT,
native, JS and JVM; fsub 173 ok / 0 FAIL with the X1 fixpoint byte-identical.

Extending the conformance case for this immediately surfaced a SECOND defect on the JS lane —
`js-treeshake-prunes-mirror-ctor` — which is the argument for pinning a real construction rather
than just the metadata.

### Original report (kept for context)

**Status:** OPEN (found 2026-07-28 by `v2-mirror-isproduct-stub` while fixing the sibling member;
filed rather than folded in, because it needs a different change).

**Reproduction** (real harness, fresh `sbt installBin`):

```scalascript
case class P(x: Int, s: String)
val m = summon[Mirror.Of[P]]
println(m.fromProduct(List(1, "hi")))
```

| lane | result |
|---|---|
| `bin/ssc-tools run --v1` | `P(1, hi)` |
| `bin/ssc run` (default, native) | **`Stub`** |

**Why it is not the same fix as `isProduct`.** `isProduct` is a constant for every Mirror the
fronts emit, so registering `(self) => true` as a tagged method completes it. `fromProduct` has to
CONSTRUCT a `T`, which is per-type: the Mirror value would have to carry the constructor — a fourth
field holding a `(xs: List) => T(xs(0), …)` lambda, which each front can build because it knows the
arity — and then `__regmethod__("Mirror", "fromProduct", (self, xs) => …)` applies it. Both fronts
again, since the differential gate compares them.

**Not on the path of the motivating example.** Measured, not assumed:
`examples/rozum-agent-schema-derived.ssc` runs clean on the default lane after the `isProduct` fix
(`Done / Derived posted. / Explicit posted. / 2`, byte-identical to v1), even though
`std/agent.ssc:73` contains a `m.fromProduct(values)` call — that call sits in the tool-args decoder
and this example's flow does not reach it. So this is a real hole with no currently-failing
consumer, which is exactly why it is filed with a repro instead of left as a note.

**Deliberately absent from `tests/conformance/v2-mirror-surface.ssc`** — pinning it there would
make that case fail for a gap that is filed, not fixed. Add it to that case in the same change that
fixes this.

**⚠️ Both halves shared one property worth remembering:** the missing member did not raise, it
evaluated to a `Stub` sentinel, and the program continued at exit 0. `isProduct`'s sentinel then
drove an `if`. A gate keying on exit status sees success.


## js-jvm-codegen-in-fence-imports-not-followed — only the INT lane loads a module imported inside a fence
<!-- status: fixed
     lane: js
     area: front
     gate: tests/conformance/native-import-in-fence.ssc
     fixed-in: 0796774ad -->

**FIXED — all four lanes now load an in-fence import.** `tests/conformance/native-import-in-fence.ssc`
prints `25 / 27 / 720` on int, js and jvm, so its `known-red: js,jvm` is gone along with the
baseline row and the paired digest.

**The scan already existed; nobody called it.** `Parser.inlineImports` recovers the in-fence form —
commonmark reads a `[names](path.ssc)` line inside a fence as literal text, never a link, so it never
becomes a `Content.Import`. `SectionRuntime` called it, which is the whole reason INT worked, and so
did `build-rust` after its own version of this bug. JsGen and JvmGen did not.

**One collector, because there were FIVE call sites.** `Parser.sectionImports` now returns both
surfaces for a section and the five sites route through it — three in JsGen, two in JvmGen. Five
copies of a two-line `collect` is five chances for the walks to disagree, which is how a surface goes
missing in the first place; the entry's own fix direction asked for exactly this.

Collecting the path is not enough — the module has to reach the OUTPUT. JsGen emits `genImport` for
each in-fence import before the block's own code, and JvmGen prepends `inlineImport`'s blocks plus
the alias block, in both cases in the position a link above the fence would have occupied.

**Three controls, measured, not assumed:**

- the above-the-fence twin `modules.ssc` still passes on all three lanes — the working surface is
  untouched;
- a file importing the SAME module both ways prints `36` on all three lanes and defines the function
  **exactly once** in the js bundle, so `inlineImport`'s dedup covers the new caller. That claim was
  in my comment before it was measured, and measuring it is what makes it a comment worth keeping;
- the second fence in the case still sees the module imported by the first, which is the case's own
  reason for having two.

**Status:** OPEN (found 2026-07-28 by `v2-native-import-graph` while fixing the native half,
`v2-native-front-in-fence-imports-not-followed`). Declared `known-red: js,jvm` on
`tests/conformance/native-import-in-fence.ssc`, so both lanes still RUN, still diff, and the
declaration expires by itself the day they start passing.

**Symptom.** The Markdown link form `[names](path.ssc)` is an import wherever it stands. Move it
inside the ` ```scalascript ` fence and only the v1 interpreter still loads the module:

| lane | in-fence import | same file, link ABOVE the fence |
|---|---|---|
| INT (`ssc-tools run --v1`) | PASS | PASS |
| JS (`emit-js` + node) | **`ReferenceError: square is not defined`** | PASS |
| JVM (`run-jvm`) | **`Not found: square`, `cube`, `factorial` (4 errors)** | PASS |
| V2 (native) | PASS (fixed 2026-07-28) | PASS |

**Reproduce.** `tests/conformance/native-import-in-fence.ssc` and `tests/conformance/modules.ssc`
are the same program and differ only in where the link sits; run both through
`tests/conformance/run.sh --only 'modules,native-import-in-fence' --no-memo`. `modules` is
5/5 green; the in-fence twin is green on INT and V2 and red on JS and JVM.

**Why it matters.** This is the same silent-divergence class the native bug was: a v1 codegen
emits a program whose module graph is missing, and the first sign is an unbound name far from
the import. It also means "v1 resolves in-fence imports fine" — the claim in the original native
entry — is true only of the *interpreter*, not of the v1 codegen family.

**Fix direction.** Whatever collects imports for `JsGen` / `JvmGen` has the same
skip-to-closing-fence shape the native scanner had. The native fix is the reference: scan
standalone link lines inside `scalascript`/`scala` fences, keep every other fence opaque, and use
the same code/doc predicate the source collector uses so the two walks cannot disagree. Not taken
by `v2-native-import-graph`: that claim's paths are `v2/bin/`, and this lives in the v1 tree.

## scljet-live-dml-does-not-reclaim-pages — SQL DELETE/UPDATE bypass reclaiming deletion
<!-- status: open
     lane: js
     area: conformance
     gate: tests/conformance/run.sh -->

**Status:** OPEN (found 2026-07-28 during SPRINT `SC-2a.1`;
reporter: Codex production-completion audit; baseline `b6967a79f`).

**Real-harness reproduction.** The INT and JS lanes of
`tests/conformance/run.sh --only 'scljet-sql-live-reclaim' --no-memo` both
produce freelist count 0 for a 512-byte, 24-row split tree after
`DELETE FROM t WHERE id <= 20`; the direct `pagerDeleteRebalanced` fixture
produces 10 and preserves rows 21 through 24. The same live baseline performs
`UPDATE t SET v = v WHERE id <= 20` without exposing any reclaimed page.

**Root cause / impact.** `deleteRowidLoop` and the delete phase of
`applyUpdates` call `pagerDeleteBalanced`, which rewrites emptied leaves in
place and never invokes the already-implemented underflow merge/freelist path.
Long-running live SQL workloads therefore retain empty B-tree pages and cannot
feed later free-page reuse.

**Fix acceptance.** Route both live paths through a fail-closed
`pagerDeleteRebalanced`; require the permanent INT+JS gate to observe
DELETE pages/header/root/freelist `14/14/5/10`, exact survivors and change
counts, plus an UPDATE reclaim result. Land strict staged-freelist validation
before enabling the live helper.

## uniml-yaml-property-lexical-boundaries — tag/anchor delimiters are guessed without parser context
<!-- status: open
     lane: js
     area: front -->

**Status:** OPEN (found 2026-07-28 during the UPR-2a.2 corpus and grammar audit;
SPRINT `UPR-2a.2`).

**Real-harness reproduction.** On the corrected 402-case corpus baseline:

- `2SXE` is valid but its legal `&a:` / `*a:` names are cut at `:` and produce
  13 actual events instead of 10;
- `LHL4` is expected-error but `!invalid{}tag` is accepted by splitting an
  apparently complete tag from a following flow collection;
- `U99R` is expected-error but block-context `!!str,` is accepted because comma
  unconditionally terminates a property as if it were inside a flow collection.

The same context-free rule rejects the legal property-only empty nodes `[!, x]`,
`[!]`, and `{a: !}` even though bare `!` must still reject adjacent collection
content such as `![x]` and `!{x: y}`.

**Impact.** Lexer, semantic property splitting, and mapping-colon discovery use
different delimiter heuristics. Legal anchor/tag spellings can change structure,
invalid spellings can be silently reinterpreted as multiple nodes, and a fix for
block input can overreject valid flow input.

**Fix acceptance.** Introduce one JVM/Scala.js-portable property syntax scanner
shared by lexical and semantic paths. Enforce the YAML 1.2.2 tag/anchor/alias
character productions, exact `%HH` preservation, context-sensitive flow
termination, and full expanded global-URI validation. The complete 402-row diff
may change only `2SXE`, `LHL4`, and `U99R`; target census from the corrected
baseline is actual errors `218`, validity `216`, semantics `138`, strict `126`,
failures `276`, source/chunks `402/402`, and zero crashes.

## uniml-yaml-bare-tag-uses-primary-handle — `%TAG !` rewrites the non-specific `!` tag
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 3341a35a9 -->

**Status:** FIXED 2026-07-28 in `3341a35a9` (found during independent
UPR-2a.1 review; SPRINT `UPR-2a.1`).

**Fail-first reproduction.**

```yaml
%TAG ! tag:example.com,2000:app/
---
! value
```

At local feature commit `7ba41ae36`, semantic projection assigns
`tag:example.com,2000:app/`. YAML 1.2.2 defines bare `!` as the non-specific tag;
the primary-handle override applies only to shorthands with a non-empty suffix.
The source token itself remains lossless, so this is a semantic expansion defect.

**Fix and verification.** Expansion now recognizes bare `!` before consulting the
primary handle. JVM and Scala.js regressions prove that `%TAG ! ...` expands
`!name` while bare `!` remains `!`; the same suite pins raw tokens, expanded
representation spelling, and malformed-percent rejection.

## uniml-yaml-bare-tag-missing-separation — `![...]` is accepted as a tagged collection
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 3341a35a9 -->

**Status:** FIXED 2026-07-28 in `3341a35a9` (found during independent
UPR-2a.1 review; SPRINT `UPR-2a.1`).

**Fail-first reproduction.**

```yaml
![a]
```

The UPR-2a.1 lexer relaxation treats the one-character `!` token as legal without
checking the following source character. Because tag scanning stops before a flow
indicator, both `![a]` and `!{a: b}` become complete, diagnostic-free projections.
YAML requires separation between a bare non-specific tag property and its node.

**Fix and verification.** The lexer now accepts bare `!` only before YAML
separation or end-of-input. Root and mapping-value regressions on JVM and
Scala.js reject adjacency to `[`/`{` with `uniml.yaml.invalid-tag`; a literal
end-of-input case remains a valid non-specific tag.

## uniml-yaml-alias-resolution-last-wins — earlier aliases bind to a later duplicate anchor
<!-- status: fixed
     lane: js
     area: front
     gate: uniml/yaml/src/test/scala/scalascript/uniml/dialect/yaml/YamlDialectSpec.scala
     fixed-in: edefdf55abac38d806ac7acafad4007eb478d9ab -->

**FIXED 2026-08-08.** `resolve` is now source-ORDERED: it registers each anchor as the clone walk
reaches that node, so an alias binds to the nearest PRECEDING definition and a duplicate `&name`
rebinds only from its own position onward.

**The correct algorithm was already in the file, thirty lines up.** `validate` builds its anchor map
incrementally as it walks and uses it to report `alias '*name' has no preceding anchor` — source
order, exactly right. `resolve` pre-walked the whole document into a last-wins `Map` first. Two
halves of one file disagreeing about one rule, with the accepting half warning about the duplicate
and moving on, so the document was accepted and then resolved against the wrong graph.

Registering at node ENTRY rather than after the children is what keeps a recursive anchor
(`&a [*a]`) reachable from inside its own subtree; the existing `visiting` set still stops the loop.

**Reproduced before fixing and the failure is quoted, not paraphrased**: with the document below the
new test read `` `before: *slot` resolved to 'two', expected 'one' `` and every other test in the
suite passed — so the defect was this and nothing around it. `collectAnchors` had one caller and is
gone with it.

**Two controls, because a fix that simply stopped seeing later anchors would also turn the first
assertion green**: `after: *slot` must still see `two`, and a document with a single anchor must
still resolve at all.

**No corpus movement.** `unimlYaml/test` is 55/55, and that includes `YamlOfficialCorpusSpec`'s
frozen baseline over all 402 cases — the check that would have caught this changing any official
outcome. It did not.

**Routing note, not acted on:** this entry sits in `v1/runtime/backend/js/BUGS.md` with `lane: js`,
but the fix is in `uniml/yaml`. P-3.2 puts an entry where the FIX goes. Left where it is rather than
moved silently, since re-routing an entry is a tracking change someone should make deliberately.

**Status:** FIXED (found 2026-07-28 during UPR-2 architecture audit).

**Reproduction.** Parse and resolve:

```yaml
first: &slot one
before: *slot
second: &slot two
after: *slot
```

`YamlProjection.collectAnchors` walks the whole document into one last-wins `Map` before cloning
aliases, so both aliases resolve to `two`. The YAML serialization contract requires `before` to
resolve to `one`; only `after` sees the replacement binding. A forward alias is likewise present in
that pre-collected table even though validation separately reports it.

**Impact.** `AliasPolicy.Resolve` changes source-order graph semantics around duplicate anchors.

**Fix acceptance.** Add fail-first duplicate/forward-anchor tests. Resolve in source order with the
binding visible at each alias occurrence, retain the duplicate warning, reject forward aliases, and
keep expansion/cycle/node limits bounded.

## js-worker-source-joined-with-literal-backslash-n — every outbound HTTP request timed out
<!-- status: fixed
     lane: js
     area: runtime
     gate: tests/e2e/js-selfcall-gate.sh
     fixed-in: 066d4f281 -->

**FIXED 2026-08-03.** `_httpSyncFetch` and `_httpStreamFetch` in `ws-server.mjs` built the Worker's
source as an array of lines and joined it with `'\\n'` — a *literal* backslash-n. In a real `.mjs`
file that is not a newline: the whole worker program collapses onto one line studded with stray
backslashes and fails to parse. The Worker then died before `Atomics.store`, the flag was never
set, and `Atomics.wait` sat out the full timeout. Every outbound HTTP request on the js lane
returned `status 0` / `body "timeout"` — which reads as an ordinary network failure, so nothing
about it looked like a compiler bug.

**Introduced 2026-06-22 by `311d501ff`** ("migrate remaining 17 JsRuntime* fragments to .mjs
resources"). `'\\n'` was *correct* while this text lived inside a Scala string literal and became
wrong the moment the same text became the file. `async.mjs:1052` and `jwt-auth.mjs:200` build their
workers the same way and join with `'\n'` — the two right copies and the two wrong ones sat in the
same directory for six weeks.

**Attribution** — a fixture with no `serveAsync` in it at all, against a live local server:

```
toolchain without the fix    FOREIGN status=0   body=timeout     (+ SyntaxError from the worker)
toolchain with    the fix    FOREIGN status=200 body=pong
```

**Why nothing caught it.** The only conformance case for outbound HTTP,
`tests/conformance/http-client.ssc`, is parked: `pending: external httpbin.org dependency; replace
with local deterministic fixture before enabling default conformance`. Nobody wrote the local
fixture, so the lane had no coverage to lose. Case 4 of `tests/e2e/js-selfcall-gate.sh` is now that
fixture for the js lane — it stands up a real server on a foreign port and requires
`status=200 body=foreign-ok`, which a broken client cannot produce.

**The near-miss worth recording:** that gate case originally asserted only `status=0` from an
*unserved* port, to prove the loopback short-circuit was narrow. `status=0` is also exactly what
this defect produces for every URL in existence — the assertion was green whether or not the client
worked, and it was written while the client was broken.

## js-httppost-to-own-serveasync-deadlocks — a program that calls its OWN server hangs forever on the js lane
<!-- status: fixed
     lane: js
     area: runtime
     kind: bug
     gate: tests/e2e/js-selfcall-gate.sh
     fixed-in: 066d4f281 -->

**FIXED 2026-08-03.** `_httpSyncFetch` short-circuits a request whose host is loopback
(`localhost`, `127.0.0.1`, `::1`) **and** whose port is the port we are currently serving: the
route table is walked in-process and the handler runs on the caller's own thread, so program state
is shared with the caller. That last property is why the obvious alternative — move the listener
into a Worker — is wrong: it would answer the request and silently stop sharing state. Unrouted
paths 404 exactly as the socket would, recursion is depth-guarded at 8, and a handler returning a
thenable throws loudly rather than yielding a half-built response.

**The evidence below was confounded and had to be re-established.** Every measurement in this entry
is `status 0 / "timeout"`, which is also what
[`js-worker-source-joined-with-literal-backslash-n`](#js-worker-source-joined-with-literal-backslash-n)
produced for *every* URL — that defect was live the whole time this entry was being written, so
none of these numbers could distinguish a deadlock from a client that never ran. Re-established
with a standalone Node probe, no ScalaScript involved: main-thread `http.createServer`, a
*correctly* joined worker fetching our own port, `Atomics.wait(flag, 0, 0, 4000)` →
`waited=4010ms  reply=undefined  handlerRuns=0`. The handler never ran. The deadlock is real and
architectural.

*(The ten-line repro below calls `Response(200, "{}")`. `Response` takes THREE arguments —
`(status, headers, body)`. It does not affect this entry, since the call never returns, but the
same slip cost an hour in the gate fixture.)*

**Found 2026-08-02 by bisecting the hang recorded under
[`js-lane-missing-derives-and-coroutinecancel`](#js-lane-missing-derives-and-coroutinecancel)**, and
it explains that residual completely. Nothing to do with `derives`, schemas or the agent.

**Repro — ten lines, no agent machinery:**

```scalascript
[route, serveAsync, stop, httpRetry, httpPost, Request, Response](std/http.ssc)

val port = 19741
route("POST", "/echo") { req => Response(200, "{}") }
serveAsync(port)
httpRetry(20, 50)
val r = httpPost("http://localhost:" + port + "/echo", "{}", List())   // ← never returns on js
println(r.status.toString)
stop()
```

| lane | result |
|---|---|
| `ssc-tools run --v1` | completes, prints a status, exits 0 |
| `ssc-tools run-js`   | blocks ~6.5 min, then returns **`status 0`, body `timeout`** |

**CORRECTION 2026-08-02 — it is not an infinite hang, and the truth is worse.** It terminates and
returns a WRONG ANSWER quietly. Quantified from the runtime's own constants and then measured:
`_httpTimeoutMs` is 30 s and `_httpSyncFetchWithRetry` runs `min(_httpMaxRetries, 10) + 1` attempts,
so the example's `httpRetry(20, 50)` buys **11** attempts × 30.5 s plus exponential backoff ≈ 6.5 min.
Verified by shrinking the clock rather than waiting: with `httpTimeout(1000)` the same program
returns after **73 s** (predicted ≈ 68 s) with `status=0 body=timeout`. Earlier probes killed it at
60 s and 180 s, which is why it first looked infinite.

A caller that checks `status` sees a plain failure, not a defect — and `httpRetry`, added to make
startup ROBUST, is what multiplies the damage elevenfold.

**Root cause, from the emitted runtime rather than inferred.** `std/http`'s client is SYNCHRONOUS on
the js lane, and the emitted code says how:

```
// We bridge the gap with worker_threads + Atomics.wait: spawn a Worker
// … the main thread blocks on Atomics.wait until the …
```

So `httpPost` blocks the MAIN thread while a Worker performs the request. `serveAsync`'s HTTP server
lives on that same main thread's event loop — so while the caller waits, the server it is waiting on
cannot accept or answer. The process deadlocks against itself.

**Bisected, with each step ruled out by running it** (recorded so nobody repeats the ladder):

| probe | result |
|---|---|
| `route` + `serveAsync` + `stop` | js OK — exits 0 |
| + `httpRetry(20, 50)` | js OK — exits 0, so readiness probing does not block |
| + `httpPost` to our own port | **js HANGS** |
| `httpPost` with NO server of ours in the process | js returns immediately (`status=0`) |
| the original example minus `derives`/`agentToolFor` | still hangs — the derived schema is NOT the cause |

The last two rows are the discriminating pair: the client is not broken, and the server is not
broken; the combination in ONE process is.

**Why it is worth a fix rather than a doc note.** "Serve and call yourself" is the shape every
self-contained example, demo and integration test uses — it is exactly how
`examples/rozum-agent-schema-derived.ssc` is written, and that example runs fine on INT. Anything
written that way is silently js-only-broken, and it hangs rather than failing, so a CI step waits
for its timeout instead of reporting.

**Not attempted here, deliberately:** the fix is a design call — make the js client asynchronous
(and the language surface await-shaped), or run `serveAsync`'s listener off the main thread. Both
are larger than a bug fix and neither should be picked by whoever happened to find the deadlock.

## js-lane-missing-derives-and-coroutinecancel — two real gaps behind one confusing error
<!-- status: fixed
     lane: js
     area: codegen
     kind: bug
     gate: tests/conformance/js-derives-segmented.ssc
     fixed-in: c8168ef90 -->

### CLOSED 2026-08-13 — both halves fixed, and the RESIDUAL this entry was held open for is gone too

Measured today on the shipped toolchain, not read off the text below:

```text
                                                  js lane            int oracle
emit-js js-derives-segmented.ssc | node           Point#2            Point#2
                                                  Person/3           Person/3
emit-js --no-tree-shake … | node                  identical          —
examples/rozum-agent-schema-derived.ssc, run-js   Done               Done
                                                  Derived posted.    Derived posted.
                                                  Explicit posted.   Explicit posted.
                                                  2  (rc=0)          2
```

**The example is the part worth stating.** This entry stayed open on it after the `derives` half
was fixed: it no longer raised a `ReferenceError`, it HUNG — 180 s and killed. That hang was traced
to [`js-httppost-to-own-serveasync-deadlocks`](#js-httppost-to-own-serveasync-deadlocks) and fixed
there (`066d4f281`), so the residual belonged to another entry and this one had nothing left to
hold. It now terminates on the js lane with the same four lines as `--v1`, in well under the cap.

**`gate: none` was stale.** `tests/conformance/js-derives-segmented.ssc` exists, is rostered, and
pins BOTH scan paths this entry names — a same-module `object Tagged` with `def derived`, and an
imported `Labelled` that reaches `emitMirrorAndDerives` only via `importedTypeclassObjects`. The
`elemLabels.length` in each derived value (`Point#2`, `Person/3`) is what proves the MIRROR was
emitted and not merely some object under the right name; a Mirror without labels reports `0`.

`fixed-in` names `c8168ef90`, the commit that restored `emitMirrorAndDerives` on the segmented path
and made a `derives` clause a tree-shaker root. The `coroutineCancel` half was already fixed when
this was filed and the text below says so.

**Re-checked 2026-08-02 at `ec70eb062` (freshly built): the residual is STILL THERE but it is NO
LONGER THE RECORDED SYMPTOM.** `examples/rozum-agent-schema-derived.ssc` on the js lane does not
produce a `ReferenceError` any more — it **does not terminate at all**.

| lane | result |
|---|---|
| `ssc-tools run --v1` | exits 0, prints `Done / Derived posted. / Explicit posted. / 2` |
| `ssc-tools run-js`   | still running at 180 s, killed by `timeout` (exit 124), no output |

**Split in halves, so the runner is not blamed for the program.** `emit-js` succeeds (8491 lines,
exit 0) and running that file directly under `node` hangs identically — so the defect is in the
emitted program, not the CLI. The `java.lang.IllegalStateException: Shutdown in progress` the CLI
prints from `runNodeAndWait` is a SECONDARY artifact of being killed mid-shutdown, not the cause;
anyone starting from that stack trace will be reading the wrong file.

**What it is not** (each ruled out by measurement, so nobody repeats them):
- not a spin — the node process sits at **0.0 % CPU**, state sleeping; it waits, it does not loop;
- not stdin — `< /dev/null` on both the CLI and the bare-node path changes nothing;
- not `serveAsync`/`stop` failing to release the listening handle in the simple case — a minimal
  `route` + `serveAsync(port)` + `stop()` program prints `served` / `stopped` and exits 0 on js.

**RESOLVED 2026-08-02 — the next step below was taken and it found the cause.** The hang is
[`js-httppost-to-own-serveasync-deadlocks`](#js-httppost-to-own-serveasync-deadlocks): the js
`httpPost` blocks the main thread with `Atomics.wait` while `serveAsync`'s listener lives on that
same thread, so the program deadlocks against its own server. Not `derives`, not the agent — the
example minus the derived tool hangs identically. What remains under THIS entry is only whatever
the original `ReferenceError` was about, and that symptom no longer appears.

*(original next step, kept because the ladder is reusable: the example starts a stub LLM server,
drives two tool calls through `httpRetry(20, 50)`, then stops it. Bisect THAT sequence rather than
the whole file — the plain serve/stop pair is already cleared.)*

⚠ The `> file` redirection used here means node's stdout is block-buffered, so "no output" may be
unflushed rather than unproduced. It does not change the verdict — the process not terminating is
the defect — but do not conclude from it that nothing ran.

**Status:** the **`derives` half is FIXED 2026-07-28** in `c8168ef90` (`js-derives-instance-undefined`); (b)
`coroutineCancel` was already fixed. What remains for
`examples/rozum-agent-schema-derived.ssc` on the JS lane is a DIFFERENT gap, recorded at the
bottom of this section.

**⚠️ THE RECORDED ROOT CAUSE WAS WRONG, and the correction is the useful part.** Everything below
under (a)/(a-3) reads the residual `ReferenceError: AgentSchema_PostTransaction is not defined` as
a gap in the *imported-typeclass scan*. It is not — the A/B that shows it needs no import at all,
because a typeclass declared in the SAME module failed identically:

| command | result |
|---|---|
| `ssc-tools run --v1 same.ssc` | `P` |
| `ssc-tools run-js same.ssc` | `P` |
| `ssc-tools emit-js same.ssc \| node` | `ReferenceError: MySchema_P is not defined` |
| `ssc-tools emit-js --no-tree-shake same.ssc \| node` | identical — so not only the shaker |

**Two defects, both on the `emit-js` side, either one sufficient to break the case:**

1. `JsGen.genModuleSegmented` **never called `emitMirrorAndDerives`**; `genModule` did (:1169).
   `run-js` → `compileViaBackend` → `generate` → `genModule` (worked); `emit-js` →
   `compileJsSegments` → `generateSegmented` → `genModuleSegmented` (no Mirror, no
   `_ssc_def_given`, not even the `arch-meta-v2-p5` header line). That call also populates
   `jsSyntheticGivenKeys`, which is what routes a summon through `_resolveGiven` instead of the
   bare `TC_T` name — so restoring it fixes both halves of the symptom.
2. `TreeShaker` pruned the typeclass object **and** the derived case class. `collectNames` skips
   `Type.Name` ("type references don't create JS-level dependencies") — true everywhere except
   here: with `case class T(…) derives TC` plus `summon[TC[T]]`, the only mentions of `T` and `TC`
   are type positions and the `derives` clause. A `derives` clause is now a reachability root,
   exactly as a named given's `_ssc_givens` registration already was.

**Why it survived a fix that was already aimed at it:** every hand-check used `run-js`, and the
conformance JS lane runs `sscTools("emit-js", …)` piped to node — i.e. the broken path. Two entry
points into the same codegen, one of which never got the feature, and no gate comparing them. The
new regression `tests/conformance/js-derives-segmented.ssc` pins BOTH scan paths (same-module and
imported typeclass) because they are different code and only the second was ever suspected;
`elemLabels.length` inside each derived value proves the Mirror was emitted too, not just an object
under the right name. INT / JS / JVM all `Point#2` / `Person/3`; fail-first taken with the pre-fix
binary. Corpus contract green on a 9-case derives/Mirror slice (11 PASS cells, 12 baseline rows
matched).

**What is STILL open for `examples/rozum-agent-schema-derived.ssc` on JS** (measured 2026-07-28,
after the fix): the `ReferenceError` is gone and `_ssc_def_given("AgentSchema_PostTransaction", …)`
is emitted, but the bundle then **produces no output and never exits** (`node < bundle` → rc=124 at
90 s, 0 bytes on stdout AND stderr), where INT prints six lines and returns. That is a
server/async-lifecycle divergence, not a derives one — the error moved, which by this entry's own
rule means a layer was peeled. Not filed as fixed, and not swept into a `backends:` gate.

**Original status:** OPEN (found 2026-07-28 by `js-not-callable-unit`, after two shim defects were
fixed out from in front of them). Both are missing JS-lane capability, not codegen bugs — filed as
work, not papered over.

**How they were hiding.** `examples/rozum-agent-schema-derived.ssc` and `examples/coroutine-demo.ssc`
both failed with a byte-identical `Error: not callable: ()`, which names nothing. Fixing the two
shim defects (`route`, then `serveAsync`) moved each failure forward to its real cause. The
displacement is the diagnostic: an error that moves is a layer peeled, an error that stays is the
same bug.

**(a) `derives` against an IMPORTED typeclass — instance now emitted (2026-07-28); the case still
fails one layer deeper.** `emitMirrorAndDerives` scanned only the importing module for
`object T { def derived }`, so `case class P(...) derives AgentSchema` with `AgentSchema` living in
`std/agent.ssc` produced NO instance, and the summon site emitted the bare name `AgentSchema_P`
(the `_resolveGiven` route is taken only for keys the emitter registered). Fixed by scanning
imported modules too — whole-program for the same reason the effect analysis in the same file
already is.

Two things that made the first attempt look like a no-op, both worth remembering:
  1. the scan must DESCEND into objects — a std module declares `package: std.agent`, so
     `wrapSectionInPackage` nests everything inside `object std: object agent: …` and the typeclass
     sits at depth 2, invisible to a top-level scan;
  2. `grep -c _ssc_def_given` counts the runtime helper's own DEFINITION, so "1 match" read as
     "registered" when nothing was. Grep for the registration form, not the name.

Remaining, and it is a different subsystem: with the instance emitted the derived code now RUNS and
dies with `ReferenceError: jObj is not defined` inside `agentSchemaProperties` — `std/agent.ssc`
calls `jObj` from `std/json.ssc`, and the emitted JS namespace does not wire that TRANSITIVE import.
Filed below as its own item.

**(a-2) RETRACTED — that diagnosis was wrong.** I read `jObj is not defined` off a MINIMAL fixture
I had written myself, which imported `std/agent.ssc` but not `std/json.ssc`, and concluded that
JsGen fails to wire transitive imports. The motivating example imports both, and once (a) was fixed
it sailed past that point — so the fixture was under-imported, not the compiler. Recorded rather
than deleted: the failure was mine, and the lesson is that a minimal repro can be minimal in the
wrong dimension.

**(a-3) the JS Mirror reported `Any` for every applied type — FIXED 2026-07-28.**
`caseClassFieldTypesInModule` collected `pv.decltpe.collect { case Type.Name(t) => … }`, so simple
types were recorded and EVERY applied type was silently dropped: `tags: List[String]` and
`note: Option[String]` were absent from the map, and the Mirror emitted `"Any"` for them. That made
`std/agent.ssc` reject its OWN derived schema — `unsupported field 'tags' type 'Any'` — a
self-inflicted domain error with no hint that a compiler map was the source. Fixed by recording the
full declared syntax; the consumer parses the bracket form (`isAgentListType`,
`agentInnerType(clean, "List[")`), so full syntax is the contract. Safe for the numeric-width
consumers: `Type.Name(n).syntax == n`, so simple types are unchanged and this only ADDS entries.
`bin/ssc-tools run-js examples/rozum-agent-schema-derived.ssc` now reaches
`ReferenceError: AgentSchema_PostTransaction is not defined` — the case declares
`case class PostTransaction(...) derives AgentSchema` and `summon[AgentSchema[PostTransaction]]`.
INT resolves it; the JS backend emits a reference to a derived instance it never defines. Note this
is a *reference error*, not the `not callable` fail-open — the JS lane is at least loud here.

**(b) `coroutineCancel` has no JS implementation — FIXED 2026-07-28.** Implemented in
`js-runtime/async.mjs` against the interpreter as the reference, not against intuition:
`gen.return()` is the JS analogue of the interpreter's `thread.interrupt()` (it drives the generator
to completion so a `finally` in the body still runs); a body never resumed has not started, because
a JS generator does not execute until its first `next()` — which is what `coroutine-demo` asserts
with `cancelled before start`; and a second cancel is a no-op, matching the interpreter, where it
simply finds no handle to remove.

Fixed alongside it: `_coroutineResume` said `coroutine already completed` where the interpreter says
`coroutine already completed or cancelled`. The wording is CONTRACT — the program catches and prints
it, and `tests/conformance/expected/coroutine-native-lifecycle.txt` pins that exact string. Verified
that nothing depended on the old wording before changing it.

**Result:** `examples/coroutine-demo.ssc` is now byte-identical on **INT, JS and v2**. That case
began the day SKIPped on every lane by an import cycle.

**Until they are closed,** neither example can pass the corpus contract on the `js` lane, and the
honest way to say so is a `backends:` gate naming the reason — not a baseline row, which is for
known gaps in things that DO run.

## MEASURED, NOT YET EXPLAINED — the non-extern residue
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: unrecorded -->

For the same program, after the block-comment fix, 53 unbound globals remain. `jvmVfs*` are the
externs above. The rest — `error ×6`, `leftChild ×4`, `seen ×2`, `cellPtr ×2`, `refTrunk ×2` — are
NOT externs and NOT top-level defs. In the source they are a case-class FIELD
(`case class TableInteriorCell(leftChild: Long, …)`, `scljet/page.ssc:59`) and pattern binders
(`case Right(ByteRead(cellPtr, _)) =>`, `scljet/sql.ssc:3729`; `case Right(refTrunk) =>`,
`scljet/write.ssc:2385`), which suggested a nested-pattern binder leak.

**Two minimal repros were built for that hypothesis and BOTH failed to reproduce it** — F handles
`case Some(Pair(a, b))` and `case Right(ByteRead(cellPtr, _))` with a user case class cleanly, no
unbound globals. So the mechanism is something else, still unknown, and the guessed one is recorded
here only so the next person does not spend the same two attempts on it. Start from the real program
and bisect it down, rather than from a plausible-looking shape.

## js-extern-shim-shadows-globalthis-implementation — the shim bound `undefined` over a working runtime function
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-28** by `js-not-callable-unit`.

**Symptom.** `Error: not callable: ()` thrown from `_call` deep in a generated bundle, with nothing
in the message naming what failed. Hit `examples/rozum-agent-schema-derived.ssc` (JS lane).

**Root cause.** `std/http.ssc` declares `extern def route`. The JS runtime DOES implement it — as
`_ssc_http_route`, and it even publishes `globalThis.route = _ssc_http_route`. But `JsGen`'s extern
shim looked only for `_ssc_ui_<name>` and for an intrinsic RENAME, and on finding neither emitted
`const route = … : undefined` — **shadowing the working implementation** inside the module
namespace. Calling it then threw, thousands of lines away from the cause.

**Fix.** Add a last tier to the fallback chain: `globalThis.<name>` when it is a function. A property
lookup deliberately — writing the bare name would be a TDZ self-reference to the `const` being
declared, the same trap the intrinsic-rename branch guards against with `target != fname`. The
`typeof` chain preserves the old behaviour (stay `undefined`) when nothing provides the name.

**Verified.** The failure MOVED from the `route` call site (line 7877) to the next unresolved name
(7912) — that displacement, not the absence of an error, is the evidence `route` now resolves.
Conformance: extern-heavy slice `http-*,json-*,crypto-*,auth-*,std-*,uuid-*,effects*,coroutine*`
27/27, and a full run reached 1023 lines with zero FAIL before being interrupted.

**What this did NOT fix, and the hypothesis it corrected.** This work was claimed on the theory that
`coroutine-demo` and `rozum-agent-schema-derived` shared ONE defect, because both failed with a
byte-identical `not callable: ()`. They share the SHAPE, not the cause — there are three:

| name | cause |
|---|---|
| `route` | shim shadowed a working `globalThis` implementation — **fixed here** |
| `serveAsync` | runtime has `_ssc_http_serve` under a DIFFERENT name; nothing bridges them |
| `coroutineCancel` | no JS implementation at all |

The second needs a decision about whether `serve` and `serveAsync` mean the same thing (blocking vs
not); the third is a missing feature. Both are JS-runtime surface questions, not codegen bugs, and
are left open rather than papered over.

## scljet-sql-blob-comparison-collapses-values — all BLOBs compare equal
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 9d96146b3 -->

**Status:** FIXED (found 2026-07-27 by `scljet-production-completion`; reproduced
on assembled `bin/lib/ssc.jar` from `9d96146b3` through both
`bin/ssc-tools run --v1` and emitted JS/Node).

**Real-harness reproduction.** Insert BLOB values `x'03'`, `x'ff'`, `x'00'`,
and `x'02'`, then run `ORDER BY v` and `COUNT(DISTINCT v)`. SclJet INT and JS
preserve insertion order and report one distinct value; reference SQLite sorts
them as `00,02,03,ff` and reports four. Joining two three-row tables on equal
BLOBs produces a nine-row Cartesian product in SclJet instead of the three
byte-equal pairs.

**Root cause.** `sqlCompare` in `scljet/sql.ssc` returns zero for every
same-class BLOB pair. The physical comparator in `scljet/write.ssc` already has
the required bytewise implementation, so SC-1c should expose/reuse that
comparator for SQL total ordering while keeping SQL NULL predicate state
separate.

**Fix:** `f36f951ba` exports the physical comparator through the public SclJet
module and makes every SQL comparison path reuse it. Bytewise filter/order,
DISTINCT, JOIN, and indexed cases pass on INT and JS. The compare-first live
matrix in `3f5d8f6c1` also passes unindexed and persisted-index BLOB
equality/order against Xerial sqlite-jdbc 3.45.3.0 (embedding SQLite 3.45.3),
then reopens the file with Xerial and requires `PRAGMA integrity_check = ok`.
Status remains FIXED until reporter confirmation.

## scljet-sql-numeric-comparison-rounds-through-double — INTEGER/REAL loses precision above 2^53
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 9d96146b3 -->

**Status:** FIXED (found 2026-07-27 by `scljet-production-completion`; reproduced
on assembled `bin/lib/ssc.jar` from `9d96146b3` through both
`bin/ssc-tools run --v1` and emitted JS/Node).

**Real-harness reproduction.** With INTEGER `9007199254740993` and REAL
`9007199254740992.0`, SclJet INT treats them as equal: equality selects both,
the INTEGER is not greater, and `ORDER BY` leaves the INTEGER before the REAL.
Reference SQLite selects only the REAL for equality, selects the INTEGER for
greater-than, and orders the REAL first. JS happens to agree on these vectors,
so an INT+JS gate is required rather than accepting one backend as a proxy.

**Root cause.** `sqlCompare` converts both numeric operands to host `Double`.
`scljet/write.ssc` already contains exact INTEGER/REAL comparison that avoids
this loss; SC-1c should make one comparator drive SQL ordering/equality and
physical index behavior.

**Fix:** `f36f951ba` removes the SQL-side binary64 conversion and delegates to
the exact physical comparator. Mixed INTEGER/REAL filtering, ordering,
DISTINCT, grouping, JOIN, and indexed boundary cases pass on INT and JS. The
compare-first live matrix in `3f5d8f6c1` confirms the >2^53 and signed-64
boundaries through both unindexed and persisted-index paths against Xerial
sqlite-jdbc 3.45.3.0 (embedding SQLite 3.45.3); the Xerial reopen and integrity
checks also pass. Status remains FIXED until reporter confirmation.

## scljet-sql-null-three-valued-logic — scalar and IN predicates treat UNKNOWN as true/false
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 9d96146b3 -->

**Status:** FIXED (found 2026-07-27 by `scljet-production-completion`; reproduced
on assembled `bin/lib/ssc.jar` from `9d96146b3` through both
`bin/ssc-tools run --v1` and emitted JS/Node).

**Real-harness reproduction.** On rows `(1,NULL),(2,NULL),(3,1),(4,1)`,
unindexed `WHERE a = NULL` returns ids `1,2`, `a = a` returns all four,
`a <> 1`/`a < 1` return the NULL rows, and `a >= NULL` returns all four;
reference SQLite returns no row for the first/fourth groups and only `3,4` for
`a = a`. Literal `1 NOT IN (NULL,2)`, non-correlated
`id NOT IN (SELECT NULL)`, and correlated NULL RHS cases likewise return rows
that SQLite excludes.

An index can hide the defect: after creating a suitable index, candidate
selection happens to make `a = NULL` and `a < 1` empty before the broken
predicate runs. The regression must compare unindexed and indexed paths rather
than treating the indexed result as evidence for scalar semantics.

**Root cause.** `sqlCompare` is intentionally a total storage-order comparator
and returns equality for NULL/NULL; `predHolds` incorrectly reuses it for SQL
predicates. `valueInList` is Boolean and cannot represent UNKNOWN, correlated
subqueries blindly invert it for `NOT IN`, and the non-correlated value token
rewrites NULL to numeric zero. SC-1b needs a tri-state predicate/IN layer while
retaining total comparison for ORDER/GROUP/DISTINCT/index operations.

**Fix:** `b63206552` adds a separate TRUE/FALSE/UNKNOWN predicate layer,
NULL-aware IN/NOT IN, simple-CASE/HAVING/JOIN handling, exact bound subquery
values, and indexed/unindexed plus DML coverage. `71d8a6f0e` then closes the
joined correlated context and error-propagation defects: every visible outer
table is bound, NULL-extended rows stay visible, direct inner tables shadow
outer names, and structural preflight carries subquery failures instead of
turning them into FALSE. The focused `scljet-sql-null-semantics`,
`scljet-sql-correlated-join`, and `scljet-correlated-subquery-errors` gates pass
on INT and JS; the complete portable `scljet-sql-*` sweep is 57/57.

The five SC-1b tests in the seven-test compare-first live suite confirm scalar,
scan, join, subquery, CASE/HAVING, indexed residual, real DML, correlated
multi-table, and error-phase behavior against Xerial sqlite-jdbc 3.45.3.0
(embedding SQLite 3.45.3). SclJet and Xerial now reject the named malformed and
missing-table cases during prepare with the same semantic category; persisted
queries and integrity also pass after an Xerial reopen. The complete live split
is SC-1b 5/5 plus SC-1c 2/2. Status remains FIXED until reporter confirmation.
The broader capability remains `subset`: subqueries in
ON/HAVING/projection/ORDER, bare outer references and aliases, complete
recursive prepare-time scope/unknown-column resolution, and compiled/cached
preparation are not yet complete.

## scljet-correlated-dml-predicates-ignored — UPDATE/DELETE turn correlated predicates into no-op
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 71d19c6ef -->

**Status:** FIXED (found 2026-07-28 by `scljet-production-completion`;
reproduced from `71d19c6ef` in the real conformance harness on INT and emitted
JS/Node, with successful outcomes pinned independently from SQLite 3.51.0;
fixed in `8172f60f1` and `483bef42b`).

**Real-harness reproduction.** The fail-first `scljet-correlated-dml` gate
executes UPDATE and DELETE with correlated EXISTS, IN, and scalar predicates.
All six statements should affect one row and report `changes=1`; both SclJet
backends report `changes=0` and leave all rows unchanged. Missing-table and
malformed subqueries should fail even for an empty outer table or an earlier
`id=999` miss; all four cases instead return successful `changes=0`.

**Root cause.** SELECT filtering now uses the database-aware
`Either[String, Int]` correlated condition pipeline, but mutation selection
still goes through Boolean `whereHolds` in `matchingRowids`,
`buildUpdateEdits`, `updatedSqlRows`, and `matchedRowCount`. Those paths have
neither an outer table binding nor an error channel. In addition,
`resolveSubqueries` discovers depth-zero FROM/JOIN tables but not an UPDATE
target, so correlated IN/scalar UPDATE subqueries can be prematurely evaluated
without the outer row. The mutation and affected-row-count paths must share one
database-aware selection result; otherwise a fix to the write path alone would
still return a false JDBC update count.

**Fix:** `8172f60f1` makes UPDATE targets visible to correlation detection,
selects mutation rowids through the shared database-aware error channel, and
feeds that one list to DELETE, both UPDATE branches, and `changes()`.
`483bef42b` additionally materializes a physical-NULL INTEGER PRIMARY KEY from
the B-tree rowid before DELETE predicate binding while preserving raw records
for indexed rebuilds. The fourteen-line `scljet-correlated-dml` oracle passes
on INT and JS across UPDATE/DELETE × EXISTS/IN/scalar, missing/malformed
preflight on empty and missed scans, affected-row counts, and a canonical
physical-NULL-IPK delete.

## scljet-correlated-nested-same-name-shadowing — nested local table is replaced by outer binding
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 71d19c6ef -->

**Status:** FIXED (found 2026-07-28 by independent
`scljet-production-completion` review; reproduced from `71d19c6ef` through the
assembled v1 harness on INT and emitted JS/Node, and compared with both SQLite
CLI 3.51.0 and Xerial's embedded SQLite 3.45.3; fixed in `4c6045266`).

**Real-harness reproduction.** With outer `t` rows whose `v` values are
`1,999,2` and a non-empty `s`, run:
`SELECT id FROM t WHERE EXISTS (SELECT 1 FROM s WHERE EXISTS
(SELECT 1 FROM t WHERE t.v=999)) ORDER BY id`. The innermost `t` is local and
contains `999`, so both reference pins return every outer id. SclJet returns
only the outer row whose own `t.v` is `999`. A control whose innermost query
uses a different table and deliberately references grandparent `t.v` currently
matches both references and must remain green.

**Root cause.** `referencesOuterTables` and `substituteOuterRefs` compute only
the immediate subquery's depth-zero table set, then scan or rewrite every token
inside all nested SELECTs with that one set. An innermost local `t.v` is
therefore mistaken for the grandparent binding. Resolution must track each
SELECT scope recursively: blindly skipping all nested tokens would hide the
bug but break legitimate grandparent correlation. Alias parsing is a separate,
already declared open grammar capability and is not evidence for this defect.

**Fix:** `4c6045266` recursively accumulates table names along each SELECT
scope, applies the shadow set independently to sibling branches, and rebuilds
nested token groups after exact outer-value substitution. The expanded
twelve-line `scljet-sql-correlated-join` gate passes on INT and JS. Its EXISTS
cases cover runtime substitution; its IN cases force correlation detection
through the scoped walker. Same-name locals return all reference rows while
the deliberately correlated grandparent controls retain their narrower result.

## markdownlint-bugs-lane-labels — repository markdownlint is red on prose syntax
<!-- status: fixed
     lane: js
     area: front
     fixed-in: ffb7b4695 -->

**Status:** **FIXED 2026-07-27** in `ffb7b4695` (reporter confirmation
pending). Found by Codex while running the exact CI markdownlint command for
Corpus Contract E7; queued as SPRINT E8.

**Reproduce.**
`npx --yes markdownlint-cli@latest '**/*.md' --ignore node_modules` exits 1
with ten diagnostics:

- MD052 at the two plain-text summaries `PASS [INT][JS][JVM]` and
  `PASS [INT][JS]` in `BUGS.md`. CommonMark parses adjacent brackets as
  reference-link labels, but no `INT`/`JS`/`JVM` definitions exist.
- Eight MD010 diagnostics for the literal tab-separated example rows in
  `specs/claim-mutex.md`. The TSV content is intentional, but invisible hard
  tabs are not required to explain the wire format.

**Fix / done-when.** Render each complete lane summary as inline code and show
the TSV example with explicit `<TAB>` markers plus a literal-tab note. Then run
the exact CI command successfully. Do not silence MD052/MD010, add fake link
definitions, or weaken the example's delimiter contract.

**Result.** The exact full-repository command now exits 0 with no diagnostics
(A/B: 10 before, 0 after), while MD052 and MD010 remain enabled. The mandatory
`arithmetic` conformance slice is 1/1 with INT, JS, and JVM all passing.
**CI evidence level 3:** exact-SHA run `30307690766` for bookkeeping commit
`90745ec27` was still `pending` with zero jobs at release (no CI verdict); the
named local gates are the full-repository markdownlint command (exit 0),
`git diff --check` (exit 0), and `arithmetic` conformance (1/1).

## coroutine-started-after-resume-int-vs-v2 — INT and the native lane disagree on a coroutine's started flag
<!-- status: fixed
     lane: js
     area: conformance
     fixed-in: 57d107739 -->

**Status:** **FIXED** — see `imported-builtin-native-runs-callback-in-defining-interpreter` above,
which is the actual defect this was a symptom of. Original report below.

**Symptom.** `examples/coroutine-demo.ssc`, line 3 of the output:

| lane | line 3 |
|---|---|
| `int` | `started after resume: false` |
| `v2` | `started after resume: true` |

Everything else in the 8-line output now matches.

**Why it appears now.** This case has been invisible on both counts: it was `SKIP`ped on every lane
by `coroutine-demo-import-cycle-on-interpreter`, and once that was fixed the comparison was drowned
by a 9-line prefix that INT printed and v2 did not
(`literate-import-executes-example-blocks`). With both resolved the two lanes finally line up
8-for-8, and this is the one genuine semantic disagreement underneath. Pin which lane is right
against `specs/` before changing either — the flag is observable API.

## js-int-boundary-const-lambda — JS `Int` normalization assigns to immutable typed-lambda bindings
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 57d107739 -->

**Status:** FIXED 2026-07-27 in `57d107739` (found by exact-SHA CI run `30286311782`,
`Conformance Suite` job `90044864452`, at `35a70f095`; regression introduced by `b672b0d41`).

**Symptom/reproduce.** The JS lanes of `std-foldable-traversable`,
`std-functor-applicative-monad`, and `std-index` exit with
`TypeError: Assignment to constant variable`; INT and JVM pass. The generated multi-argument
typed lambda has this shape:

```javascript
((...__a) => {
  const [a, b] = (__a.length === 1 && Array.isArray(__a[0])) ? __a[0] : __a;
  a = _charCodeOrNull(a) ?? a;
  // ...
})
```

**Root cause.** The `Char` → `Int` fix normalizes every declared `Int` parameter once at function
entry. Typed lambdas destructure their rest argument into `const` bindings, unlike regular
function parameters, so the shared assignment-based entry code is invalid for this emitter shape.
The earlier focused typed-lambda regression had one parameter and did not exercise the destructured
multi-argument path.

**Fix / done-when.** Add a minimal two-argument typed-lambda conformance case that fails before the
fix, then emit normalization without assigning to immutable bindings. The new case and all three
standard-library witnesses must pass on JS while preserving the original Char-to-Int, ordinary-Int,
and String negative controls.

**Resolution / verification.** Multi-parameter typed lambdas now destructure with `let` only when
an `Int` entry guard needs to normalize a binding; lambdas without such guards retain `const`.
`js-int-boundary-const-lambda` was proven fail-loud before the change (PASS INT / FAIL JS with the
same exception), then passed together with all three CI witnesses: 4/4 cases green on every
declared lane. Full `CrossBackendPropertyTest` passed 17/17 (74 generated INT↔JS + 19 INT↔JVM,
zero skips), including the original Char widening and String/ordinary-Int controls. Evidence level
3 under AGENTS.md rule 4c: exact-SHA run `30297788784` was queued for `57d107739` at release time;
the named local gates above are green and no job for the fixed SHA had reported red.

## js-int-division-by-zero-yields-infinity — `10 / 0` is `Infinity` on the JS lane instead of throwing
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded
     gate: tests/conformance/js-int-division-by-zero.ssc -->

**Status:** **FIXED 2026-07-27** by opus (`js-int-div-by-zero`, `2079f466a..f21a9428b`). JsGen
emitted `Math.trunc(a / b)`, and `Math.trunc(Infinity)` is `Infinity` — nothing threw, so no `catch`
was entered and the program printed `Infinity` at exit 0 where INT and JVM printed the caught value.

New `_idiv`/`_imod` in the JS runtime throw `/ by zero` (the JVM's ArithmeticException wording, as a
plain `Error`, which is what this runtime's catch support already understands); `JsGen` routes only
the **statically-Int** `/` and `%` there.

**Deliberately narrow:** the dynamic `_arith` number path is untouched, because `4.0 / 0` is
legitimately `Infinity` in Scala too and a runtime `Number.isInteger` guess would break it. The
BigInt path already threw natively. The conformance case pins that boundary with an explicit Double
line, so a later "improvement" cannot quietly extend this into floating point.

**Verified:** new case `tests/conformance/js-int-division-by-zero.ssc` `PASS [INT][JS][JVM]` — JS now
matches INT line for line (`div-failed` / `mod-failed` / `3` / `1` / `Infinity`);
`try-catch-exception-delivery`, which had been restricted to `[int, jvm]` *because of this bug*, is
back on `js` and green on all three; arithmetic/numeric sweep 14/14 with 0 failures (the two
KNOWN-RED lanes are the declared v1-codegen 64-bit non-conformance, pre-existing and out of scope).

**Historical report:** OPEN (found 2026-07-27 by opus, surfaced by the new
`try-catch-exception-delivery` conformance case on its first JS run). **v1 JS backend.**

`ssc` `Int` is 64-bit integer arithmetic, so `10 / 0` must fail, not produce a float. On the JS lane
it yields JS's `Infinity`:

```
line 1: expected=caught-throwable  got=Infinity
```

i.e. the `try` body never throws, so no `catch` is entered and the program prints `Infinity` where
INT and JVM print the caught value. Silent wrong answer, exit 0.

**Scope note:** the case that found this is restricted to `backends: [int, jvm]` so it pins the
delivery fix rather than this divergence — deliberately, and recorded here instead of swept under
the restriction.

## coroutine-demo-js-not-callable — the coroutine demo dies on the v1 JS backend
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-28** (`js-not-callable-unit`) — three layers: the extern shim shadowed
`route`'s working implementation, `serveAsync` was implemented but unpublished, and
`coroutineCancel` had no JS implementation at all. `examples/coroutine-demo.ssc` is now
byte-identical on INT, JS and v2. Original report:

**Was:** OPEN — **diagnosed to the line 2026-07-27 by opus**, fix blocked on territory (the v1 JS
backend is inside `v1/runtime/**`, held by `corpus-gate-remaining-reds`).

**Cause.** `not callable: ()` is `_show(undefined)`: an `extern def` whose JS binding resolved to
`undefined` and was then `_call`ed. `v1/runtime/std/coroutine.ssc` declares four externs; `JsGen`
lowers three of them as intrinsics (`_coroutineCreate`, `_coroutineResume`, and `suspend` → `yield`)
but has NO case for `coroutineCancel`, and `async.mjs` has no `_coroutineCancel` either. So the
generated module binding falls through to
`const coroutineCancel = (typeof _ssc_ui_coroutineCancel !== 'undefined') ? _ssc_ui_coroutineCancel : undefined;`
and the demo's `coroutineCancel(cancelled)` becomes `_call(undefined, …)`.

**Fix shape** (three parts, all in `v1/runtime/backend/js/`):
1. `async.mjs`: add `_coroutineCancel(co)` — guard on `co._done`, set it, then `co._gen.return()`.
   On an UNSTARTED generator `.return()` does not run the body, which is exactly what the demo
   asserts (`cancelled before start: true`), and the `_done` guard makes a second cancel a no-op
   (the demo cancels twice on purpose).
2. `JsGen`: lower `coroutineCancel(co)` next to the `coroutineResume` case (~line 4900).
3. `JsGen:1334`: add `coroutineCancel` to the capability probe that decides whether `async.mjs` is
   emitted at all — otherwise a program that only cancels links against a missing runtime.

**Is it the same defect as `rozum-agent-schema-derived-js-and-v2-gaps`?** The entry asked; the answer
is **same class, different cause — do not fix them as one.** Both are an extern binding that silently
became `undefined`, but: `coroutineCancel` has NO JS implementation anywhere, while `route` (the rozum
one, `_call(_call(route, "POST", …))`) IS implemented — `_ssc_http_route`, exported as
`globalThis.route` — and the module binding `const route = std.http.route` SHADOWS it with the same
`_ssc_ui_route`-or-`undefined` probe. One needs a runtime function; the other needs the binding to
find the implementation that already exists. Details recorded on that entry.

**The aggravating factor is shared and worth fixing once:** the probe's `: undefined` tail is
fail-OPEN. An extern with no JS implementation should be a loud link-time error naming the extern,
not a value that survives until someone calls it and gets `not callable: ()` with no name in the
message.

## portable-capsule-frame-unvalidated — the capsule's frame half escapes the fail-closed admission
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded -->

**Status:** **FIXED 2026-07-27** by opus (`portable-nominal-frame`, landed `7edee1a51..2050eeafc`).
`Capsule.validateFrame` admits exactly the first-order value terms a frame may contain — `Lit`, or
`Ctor` whose fields are recursively values — and rejects everything else naming the offending node
(`Lam` too: a lambda is a value at runtime but *code* in the bytes). Probes B and C are now refused
at admission; **probe A is deliberately NOT fixed here** and is queued as an owner-level format
decision, `BACKLOG.md portable-capsule-integrity`. Gate: `v2/conformance/portable-capsule.sh`
21/21 → 25/25, including `data-only frame edit still runs`, which is what keeps the rejection lines
honest — a blanket "any frame edit fails" guard would satisfy them for the wrong reason.

**Historical status:** OPEN → being fixed under `portable-nominal-frame` (found 2026-07-27 by opus while starting
vector-15 arc slice 3, which has to define what a frame may legally contain). **VM Portable capsule**
(`v2/src/Capsule.scala`), the `run-capsule` admission path.

`Capsule.decode` re-parses the envelope, runs `Reader.validate` on the **resume program**, and re-checks
the `resume-digest` — the fail-closed, untrusted-capsule contract. It does **none of that for the
`frame`**: the frame is taken as an arbitrary `Term` (`Reader.toTerm`) and spliced straight into the
driver `App(entry, [frame, input])`, which is then compiled and run. So half of the capsule's input
bypasses admission entirely.

**Measured (real harness, `java -jar ssc.jar run-capsule`, capsule from `freeze-region-global`; baseline
`17` = `quad(3) + a` with `a = 5`):**

| Probe | What the bytes say | Result | Should be |
|---|---|---|---|
| **A** — swap the captured value | `(frame (ctor frame (lit (int 99))` | **`111`, exit 0 — silently accepted** | detected, or documented as out of scope for a *code* digest |
| **B** — put CODE in the frame | `(frame (ctor frame (global dbl)` | admitted; injects a **closure** into the resume (here it happened to die in `Prims.asInt`, "expected Int, got \<closure\>") | rejected at admission |
| **C** — out-of-scope local | `(frame (ctor frame (local 0)` | admitted; `ArrayIndexOutOfBoundsException: Index -1` from `Compiler.compile` | rejected at admission with a diagnostic |

**Why B is the serious one.** Portable CodeMode's whole claim is that code travels as *validated* bytes
(§10.1) — the frame is supposed to be **data**. With E1 (`ebc576bec`) the capsule now also carries the
defs the region reaches, so a frame slot `(global <carried-def>)` resolves to a real closure: a capsule
author can hand the resume a *function* where it expects a value. That it crashed in this probe is an
accident of the demo resume body (`i.add` forced the slot); a resume that **applies** its slot would call
attacker-chosen carried code. B and C also fail with raw internal exceptions rather than the fail-closed
diagnostic the format promises.

**A is different — and is NOT fixed by validation.** The envelope's digest is deliberately a *code*
digest (`resume-digest`, §10.1 `Portable(resumeCodeDigest, closedResumeProgram)`); the frame is data that
legitimately differs per capsule, so "the digest didn't catch it" is by design. The real statement is
that the **VM-side Portable capsule has no integrity/authenticity seal at all** over its data half, while
the **host-side lane already has one** (format v3: HMAC signature + audience/tenant/quota, see
`project_durable_continuation_save_run_0721`). Closing that is a **format decision** (v1 → v2 envelope +
a key/authority model), not a unilateral bump, so it is recorded here and queued in `BACKLOG.md` rather
than fixed inside this slice.

**Fix in progress (B + C only):** a `validateFrame` that admits exactly the first-order VALUE terms a
frame may contain — `Lit`, or `Ctor(tag, fields)` recursively — and rejects everything else naming the
offending node, mirroring `Reader.validate`'s style. This is also the precondition for slice 3's nominal
frames: "what may a frame contain" has to be answered before non-scalar slots are allowed in.

**Follow-up (final run `30285845478`).** The 90 s lane budget cleared 8 of the 9 timeouts;
**`scljet-jdbc v2` still times out**, i.e. the largest scljet case needs >90 s under F where legacy
is a few seconds. Do NOT answer that by raising the budget again — at some point the honest reading
is that the case is too slow to gate, and the cost itself is the thing to fix. (`scljet-jdbc` also
carries `scljet-jdbc-facade-bytecode-class-too-large`, so it may be two problems stacked.)

## f-bytecode-default-switch-int64-ci-red — RESOLVED: NOT-A-BUG (misattribution; v2-bytecode is int64-exact, the CI divergence lines were the v1-codegen KNOWN-RED)
<!-- status: wontfix
     lane: js
     area: codegen -->

**Status:** NOT-A-BUG / MISATTRIBUTION — **RESOLVED 2026-07-22 by opus** (`bytecode-int64-verify-fix`).
Hypothesis (b) below is CONFIRMED decisively: there is **no v2-bytecode int64 wrap**. The CI Conformance
"64-bit-integer divergence" lines the coordinator quoted are the pre-existing **v1-codegen KNOWN-RED**
(`JVM` = `run-jvm`, `JS ` = `emit-js`+node), which `run.sc` *tolerates* (they print `KNOWN-RED [...]` +
a capped diff but do NOT increment `failed`). The v2-bytecode lane (`JVM/v2` = `ssc run --bytecode`) and
the v2-JS lane (`JS/v2`) both **PASS**. The actual CI-red that reverted the F5c switch was
`durable-save-run` (effect verifier — its own entry, since fixed), not this. **No fix to the bytecode
lane is needed; the F5c switch re-apply is DE-RISKED on the int64 axis.** The switch `9ddd2b501` was
`git revert`ed the same day it landed; keep it opt-in until re-attempted per the gate below.

**RESOLUTION EVIDENCE (measured 2026-07-22, this worktree, freshly-built `installBin` jar):**
- **Decisive probe** — a top-level program printing `2147483648` (2^31 literal), `9223372036854775807`
  (max64), `9007199254740993` (2^53+1, the double-precision witness), `1000000 * 1000000` (1e12 mul that
  overflows Int32), computed `2147483647+1` and `twoTo31*4194304+1`, recursive `sumTo(100000,0)=5000050000`
  (> 2^32), and a `while` accumulator `3 * 2147483648 = 6442450944` — is **byte-identical** on
  `ssc run --bytecode` (v2 asm) and `ssc run` (vm interpreter), and **every value is 64-bit exact**
  (no Int32 wrap, no double-precision loss). Exact on BOTH fronts (default and `SSC_FRONT=F`).
- **Conformance int slice** (`run.sh --only int-width,int-literal`): `JVM/v2` **PASS**, `JS/v2` **PASS**,
  `INT` **PASS**; the KNOWN-RED lines are the v1-codegen lanes and reproduce the CI values exactly —
  `KNOWN-RED [JVM]`: `2147483648→-2147483648`, `9007199254740993→1`, `9223372036854775807→-1`;
  `KNOWN-RED [JS ]`: `2147483648→-2147483648`, `9007199254740993→-9007199254740991`,
  `9223372036854775807→9223372036854776000`. Suite verdict: `2 passed, 0 failed`. So the CI's
  `expected=2147483648 got=-2147483648`, `…got=9223372036854776000`, `…got=-9007199254740991 / 1`
  are precisely these v1-codegen KNOWN-RED diffs (JVM gives `1`/`-1`, JS gives the double-folded values).
- **Why the switch is causally decoupled:** `run.sc` uses **explicit** lane flags — `INT` via
  `ssc-tools run --v1`, `JVM/v2` via `ssc run --bytecode`, `JVM` via `ssc-tools run-jvm`, `JS` via
  `emit-js`. It **never** invokes bare `ssc run`, the only thing the switch changed. Flipping the default
  backend has zero effect on the conformance harness.

**v1-codegen known-red status (verified in scope):** still LIVE and correctly documented in
`specs/numeric-widths.md` §4 and `int-width.ssc`'s `known-red:` front-matter — the v1 JVM/JS codegen
lanes still truncate to 32 bits (JVM) / fold-at-32 + carry-in-double (JS), so the declaration has NOT
expired (run.sc would FAIL a stale known-red that started passing). **Do NOT fix the v1 codegen** — it
is slated for deletion (`MILESTONES.md` stream 1); the two rows expire with it.

--- original triage (kept for the record) ---

**Original status:** OPEN (found 2026-07-22 by opus during the `v2-f5c` default-execution switch; the switch
`9ddd2b501` was **REVERTED** (a `git revert` of `9ddd2b501`, this branch) the same day). Blocked re-attempting the bytecode-lane
default switch (f5c-SWITCH) and therefore the FastCode/SelfRec kernel removal (f5c-4) that was gated on it.
Reported by the coordinator from the switch's CI (Conformance Suite job).

**Symptom (CI, authoritative):** with the bytecode lane as the DEFAULT `ssc run` execution backend
(switch `9ddd2b501`), CI's full Conformance Suite reported 64-bit-integer divergences —
`expected=2147483648 got=-2147483648` (2^31 wrapped to signed Int32),
`expected=9223372036854775807 got=9223372036854776000` (max64 carried in a double → precision loss),
`expected=9007199254740993 got=-9007199254740991 / 1` (64-bit exactness lost). ssc `Int` is 64-bit
(`specs/numeric-widths.md`), so a default lane that produces these corrupts every large-integer program.
The examples sweep + the 9-test conformance slice used as the switch's maturity gate contained NO
integer-boundary program, so they were green — an OUT-OF-CORPUS gap the full conformance closes
(same F4-flip failure mode as `f-native-out-of-corpus-smoke-regressions`).

**⚠ Unreconciled: local investigation does NOT reproduce a v2-bytecode int64 wrap — the causal path is NOT yet localized.** Do not "fix the bytecode lane" blind; first localize the exact CI-failing path. Observed locally (reverted state, this worktree, macOS):
- The **v2 JvmByteGen bytecode lane is int64-CORRECT** via `bin/ssc-standard run --bytecode`:
  `tests/conformance/int-width.ssc` and `int-literal.ssc` both print byte-identical to their
  `expected/*.txt` (2147483648 / 9007199254740993 / 9223372036854775807 / −9223372036854775808). Typed
  `i.*` arithmetic (the f5c-1 unboxed-Long fast path) is also correct: `add(2147483647,1)=2147483648`,
  `mul(2147483648,2)=4294967296`, recursive `sumTo`, and a `while` accumulator `+2147483648` all exact.
- `tests/conformance/run.sc` uses **explicit** lane flags — INT via `ssc-tools run-batch --v1` (:349),
  the bytecode/V2 lane via explicit `run --bytecode` (:496). It **never relies on `ssc run`'s default**,
  which is the only thing the switch changed. So the switch does not causally touch the conformance harness.
- The exact divergence VALUES CI reported match the **v1-codegen KNOWN-RED** lanes (v1 JVM maps ssc Int→host
  int32 and truncates; v1 JS folds at 32 bits and carries a double), documented in `specs/numeric-widths.md`
  §4 and tolerated by run.sc as a declared-red (`int-width` = "3 passed, 0 failed" locally, both pre- and
  post-switch since run.sc is switch-independent). These EXPIRE when v1 codegen is deleted — do NOT fix v1.

**Two hypotheses — DISCRIMINATED (2026-07-22):** (a) a real v2-bytecode int64 wrap — **REJECTED**: the
JVM/v2 lane is int64-exact on int-width/int-literal and on the decisive probe above. (b) a **misattribution**
— **CONFIRMED**: the CI red is the pre-existing v1-codegen KNOWN-RED (visible-but-tolerated) plus the
separately-fixed `durable-save-run`, NOT switch-caused. The switch's true blocker was `durable-save-run`,
which is fixed.

**Revert (done):** `git revert 9ddd2b501` restored `StandardMain.runNative` default to the interpreter
(`var bytecode = false`); `--bytecode` stays the opt-in. Prereq #1 (link-time bytecode fallback) and
prereq #2 (stack-safe effectful loops) are correct standalone bytecode-lane improvements and were KEPT.
Post-revert local gates: conformance int tests 3 passed/0 failed, semantic 248/248, X1 fixpoint byte-identical.

**Fix gate (future f5c-SWITCH re-attempt):** the int64 axis is now CLEAR — no v2-bytecode int64 fix is
required. The switch's maturity gate SHOULD still run the FULL conformance suite (not just the examples
sweep + a hand-picked slice) to catch any *other* out-of-corpus regression, but the integer-boundary set
(2^31, 2^53±1, max64, min64) is already exact on `ssc run --bytecode` and byte-identical to the interpreter.
Do NOT edit `RunNativeV2.frontIsF` (sibling-owned).

## scljet-js-conformance-stale-prebuilt-jar-after-symlink-drop — dropped compat symlink breaks scljet JS emit against the pre-built bin/lib jar
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 638b4f610
     gate: tests/conformance/run.sh -->

**Status:** DONE 2026-07-21 (`638b4f610`, `v21-negtc-red-triage`). Restoring the
`v1/runtime/std/scljet → ../../../scljet` symlink — which the same commit did to fix the v21 gate red —
IS this bug's documented "recreate the symlink" workaround, now committed permanently. With the symlink
present the OLD (pre-`65a9a7e8a`) resolver in any stale pre-built `bin/lib` jar can again locate
`std/scljet`, so the JS lane resolves; per this entry's own note "with the symlink present, all `scljet-*`
INT+JS conformance passes (verified)". The symlink drop is reverted; the standalone-library polish is
re-queued to BACKLOG (deep fix must teach both the Scala AND the native-front resolvers before the symlink
can go).

**Status (historical):** open (found 2026-07-21 by the `scljet-mutable-pager` agent; owner: `scljet-standalone-library-symlink-drop`).

**Reproduce:** with the pre-built `bin/lib` jar dated before `65a9a7e8a`
(`feat(scljet): drop compat symlink, first-class scljet/ library root`, Tue Jul 21 21:29),
run `tests/conformance/run.sh --only 'scljet-balance-insert' --no-memo`. INT passes; the **JS lane
fails** — `bin/ssc-tools emit-js tests/conformance/scljet-balance-insert.ssc` prints
`JS generation error: The path created has enough ..s that it would start outside the root directory`
and node then chokes on the non-JS output (`Unexpected token '<'`). Affects EVERY `scljet-*` JS lane.

**Root cause:** `65a9a7e8a` deleted the `v1/runtime/std/scljet -> ../../../scljet` symlink and taught
the resolver (`ImportResolver.discoverScljetRoot`) a first-class repo-root `scljet/` fallback — but that
fix lives only in **source**. The committed pre-built `bin/lib/{ssc.jar,standard}` (used by the
serverless conformance harness locally) predates it, so the OLD resolver can no longer locate
`std/scljet` and JsGen derives a broken relative path. INT works because its module load path differs.
Not a CI regression: CI's `install.sh --dev` rebuilds the jar with the new resolver, so CI's JS lane is
green (the sibling's commit claims full conformance was verified). It only bites agents running the
JS lane against the stale local pre-built jar.

**Workaround (local):** recreate the symlink `ln -s ../../../scljet v1/runtime/std/scljet` (uncommitted)
OR rebuild the jar with `bash install.sh --dev`. With the symlink present, all `scljet-*` INT+JS
conformance passes (verified). **Fix:** regenerate/commit a fresh pre-built `bin/lib` (or document that
a jar rebuild is mandatory after the symlink drop) so `KEY FACTS: bin/ssc + ssc-tools are prebuilt`
stays true for the JS lane.

## f-enum-case-default-arg-qualified — F skipped trailing defaults on the QUALIFIED enum-case ctor path
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 886df94fe -->

**Status:** FIXED (2026-07-21, `886df94fe`; f-caseclass-default-arg family). F-lane only. This was the
**last remaining v2-F4 dualrun residual** (`mcp-types`); the residual set 10→1→0 across 2026-07-21.

**Root cause (live-traced, both fronts).** F's `parseEnumCaseApp` lowered a QUALIFIED enum-case ctor
`E.Case(args)` by folding straight to `(ctor Case <args>)` with NO default synthesis. So an under-applied
`Transport.Http(8080)` — `case Http(port: Int, path: String = "/mcp")` (a defaulted 2nd param) — emitted a
1-FIELD value `(ctor Http (lit (int 8080)))`. The pattern `case Transport.Http(p, _)` compiles to an
`(arm Http 2 ..)` (arity 2) in BOTH fronts, so F's 1-field value failed the arity test → fell through to
the wildcard → `?`. The default front prints `http:8080`. Note the miss was specific to the QUALIFIED path:
the UNqualified ctor call (`parseCtorGen`) and the plain def-call path already synthesized defaults; only
`E.Case(args)` did not. (Distinct from `f-object-method-varargs` / `f-imported-caseclass-default-arg` — a
separate lowering path, exactly as those entries predicted.)

**Fix (specs/v2.2-p6.5-fsub.ssc, mirrors the oracle byte-for-byte at the value site).** The oracle lowers
`E.Case(args)` to a `ctorap` node and calls `ctorApplyDefaults` (ssc1-lower :1829): when under-applied with
every omitted trailing param carrying a REAL default, it **INLINE-APPENDS** those resolved default exprs
onto the ctor args — NOT the nested-lambda `expandDefaultCall` the def/unqualified-ctor path uses. F now
mirrors that: `parseEnumCaseApp` → `parseEnumCaseAppD`/`E` scans the positional args (`dfltGo` decides fire,
reusing the SHARED `funcDflts` entry that `enumDfltEntries` already keys by CASE name), and `enumCtorDfltTail`
appends `dropL(nprov, pd)`'s parsed default exprs, giving `(ctor Http (lit (int 8080)) (lit (str "/mcp")))`.
A fully-applied, named, or defaultless call keeps the plain `(ctor Case args)` path — every non-defaulted
enum case is byte-unchanged.

**Verified GREEN:** minimal repro (`E.C(1)` with `case C(a, b = 7)` → 8; F `?`→correct before/after);
`Transport.Http(8080)` emits the 2-field ctor; `bin/ssc run mcp-types.ssc` == `SSC_FRONT=F bin/ssc run
mcp-types.ssc` byte-identical (14 lines incl. `http:8080`); FIXPOINT (fsub.sh --self) stage1==stage2
byte-identical (385 827 B) with the new `d enum_case_dflt` tower case; semantic.sh 248/248 goldens (added
`tower__enum_case_dflt`); dualrun `SSC_DUALRUN_ALL=1` 0 unexpected, mcp-types now EQUAL (GAP removed from
`specs/v2.2-p6.5-dualrun.expected`).

## v2-native-import-wildcard-drops-ssc-case-classes — `import std.x.*` misses pure-`.ssc` definitions
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 49cc3b0e5 -->

**Status:** FIXED 2026-07-20 by `ci-native-front-defects` (`452677b73`). `V2CaseClassMethodCliTest`
"std mapreduce Cluster.close executes without a stub under default v2 runner" verified green on BOTH
default v2 (native) and `--v1`, without switching to the Markdown-link form.

**Documented hypothesis — HELD in mechanism, but was incomplete (two corrections, verified):**
1. *Mechanism confirmed.* The dotted `import std.<pkg>.*` (and `.{names}` / `.Name`) was a silent
   no-op on BOTH lanes — only the Markdown-link form `[Name](path.ssc)` loaded a pure-`.ssc` module.
   The dotted form never triggered a module load.
2. *"`Node` works, masks the gap" was imprecise.* Measured: with `import std.mapreduce.*`, `Node`
   ALSO fails on `--v1` ("Undefined: Node"); on the native front `Node(...)` returns a fail-OPEN
   `Stub` (not a real value). So neither lane "resolved" `Node` via the dotted import — the masking
   was the native fail-open, not a working `Node`.
3. *A SECOND latent defect the hypothesis missed.* Even via the WORKING Markdown-link import, the
   test's exact `Cluster(List())` (one arg) fails with an arity error on both lanes, because the std
   case class is `Cluster(nodes, pids)` (two params). Import resolution alone would NOT have made the
   test green.

**Fix (three parts, all minimal):**
- v1 interpreter (`StatRuntime.execStat`): a dotted `import std.<pkg>.*` inside a fenced block now
  resolves `std.a.b` → `std/a/b` and loads it through the existing `runImport` path (which already
  dumps every exported name into scope). Scoped to the `std` root; a ref that doesn't resolve to a
  std module stays a no-op, so ordinary Scala imports are unaffected.
- native front (`RunNativeV2`): a dotted std import synthesizes a leading Markdown-link prelude root
  that the self-hosted runner already loads correctly — NO tower change.
- `std/mapreduce/cluster.ssc`: `pids` defaults to `Nil`, so `Cluster(nodes)` is a valid one-arg
  handle and `close()` on an unconnected handle is a no-op.

---
### Original report (kept for context)

Surfaced by `V2CaseClassMethodCliTest` → "std mapreduce Cluster.close executes without a stub under
default v2 runner", one of the 23 failures that the `System.exit` fix (`deb5e6c90`) made visible.

**Symptom.** `import std.mapreduce.*` then `Cluster(List())`:
- default v2 (native front): `java.lang.RuntimeException: unbound global: Cluster`
- `--v1` interpreter: `[ERROR] Undefined: Cluster`

`std/mapreduce/index.ssc` DOES re-export `Cluster` (from `cluster.ssc`), yet the dotted-package
wildcard import does not bring the pure-`.ssc` case class into scope on **either** lane. Symbols that
are ALSO registered as plugin globals resolve fine (`Node` works, via `DistributedNativePlugin`),
which is what masks the gap — only the `.ssc`-only definitions (`Cluster`, `ClusterError`, `Dataset`)
are missing.

**Reproduce (both lanes fail):**
```scalascript
import std.mapreduce.*
val cluster = Cluster(List())    // unbound global: Cluster (native) / Undefined: Cluster (--v1)
cluster.close()
```
**The markdown-link import form WORKS on --v1** — so the case class itself is fine; the defect is the
dotted-wildcard resolver:
```scalascript
[Cluster, Node](std/mapreduce/cluster.ssc)
val c = Cluster.connectList(List()); c.close()   // prints, works on --v1
```

**Root cause (hypothesis).** `import std.<pkg>.*` resolution pulls plugin-registered globals for the
package but does not elaborate/load the package's `.ssc` module definitions (case classes, defs). The
markdown-link import path (`[names](path.ssc)`) loads the module and works. On the native front there
is the additional layer that only `DistributedNativePlugin` intrinsics exist — the `.ssc` `Cluster`
is never loaded at all.

**Not a harness gap.** The test's `ssc.lib.path` harness gap was fixed in the same sweep
(`53a687f43`); the remaining failure is this real defect. Fixing it is a compiler import-resolution
change affecting both lanes — out of scope for the CI sweep. **Done-when:** `import std.mapreduce.*`
brings `Cluster`/`ClusterError`/`Dataset` into scope on the native front (and v1), and the test goes
green without switching to the markdown-link form.

## v1-cluster-bully-no-convergence-over-ws — multi-node Bully election does not converge to lex-greatest
<!-- status: fixed
     lane: js
     area: front
     fixed-in: da63bb96a -->

**Status:** FIXED 2026-07-20 by `ci-cluster-bully`. Root cause was the WS *transport*, not the
Bully/election logic. All 7 multi-node cluster tests now pass (verified by running them and observing
the correct lex-greatest leader on every node — see Resolution). Found 2026-07-20 by the
`ci-fork-failures` sweep; node fixtures run via `java -jar ssc.jar --v1 …` (switched to `--v1` in
`da63bb96a`, Jul 8 — the native front cannot run the cluster WS stack). Masked until `deb5e6c90` by
the `System.exit` fork death.

**Failing suites/tests:** `MultiNodeClusterTest` (two-node leader, spawnRemote, five-node, three-node
re-elect), `ClusterBullyStatusConvergenceTest` (2-node status), `PartitionTest` (5-node static
partition), `PartitionHealingTest` (partition heals).

**Symptom (consistent, not random).** Nodes fail to converge on the lex-greatest node (Bully winner):
- 2-node status: `node-bbb` correctly self-elects (`leader:"node-bbb"`), but `node-aaa` never adopts
  it — its `leader` stays `""`. The coordinator announcement does not update the lower node.
- 5-node print test: both surviving nodes report `leader=node-a` (lex-SMALLEST) though each node's
  `members` gossip view lists all peers.
- 3-node failover phase-1: each node self-elects itself → 3 distinct leaders, no convergence.
- partition tests: each node's `members` shows only ONE peer, and election picks `node-c` not
  `node-e`.
- `spawnRemote`: remote behavior never runs (`REMOTE_SPAWN_TIMEOUT`).

**Root cause (documented hypothesis — DISPROVEN).** The filed hypothesis blamed `startElection`
(ActorScheduler.scala) for deriving higher-id peers from `peerChannels` instead of the gossip
membership view. Verified false: `clusterMembers` already reads the *same* `peerChannels` map, and in
the 2-node repro `startElection`/`broadcastCoordinator` ran exactly as intended. The Bully logic was
correct all along.

**Root cause (ACTUAL — verified with `SSC_CLUSTER_DEBUG=1`).** A WS-transport defect in the
fast-engine (default backend). `ssc.plugin.httpfast.WsConnection.dispatch` fed **only TEXT** frames
into `recvQueue` (which backs the blocking `recv()` the cluster server-inbound handler pulls from);
**BINARY** frames went to `onBinary` only and were dropped from `recv()`. The cluster wire protocol
negotiates `ssc-actors-v2.cbor` — every post-handshake envelope is a **binary** frame. Each node both
dials and accepts, so `peerChannels[peer]` (one entry per peer, last-write-wins) routes replies over
whichever socket registered last; when that is the peer→*server*-inbound direction, the receiver's
`recv()` never sees the frame. The only frames that reliably arrived were those delivered to a node's
**client** socket (the client session's `recv()` correctly surfaces binary frames as ISO-8859-1
strings). Deterministic given connection order → "consistent, not random". Trace: node-bbb correctly
self-elected and called `broadcastCoordinator → node-aaa`, but node-aaa logged **zero** dispatched
envelopes (no `alive`, no `coordinator`, no gossip `peers_resp`) — its server-inbound `recv()` was
parked forever on binary frames while `members` stayed populated (loop blocked, not exited). Explains
every symptom: dropped coordinator (2-node), fragmented gossip → wrong/partial membership and
lex-smallest winners (5-node/partition), dropped `cluster_spawn_ack` (`spawnRemote` timeout).

**Resolution.** `WsConnection.dispatch` now also enqueues BINARY frames into `recvQueue` as a Latin-1
byte-view string (`new String(bytes, ISO_8859_1)`) when `recvActive` — the exact convention the WS
client's `recv()` and the `onBinary→onMessage` bridge already use, and what `ActorWireProtocol.decodeV2`
expects (`isoStr.getBytes("ISO-8859-1")`). One-file, transport-level fix; the Bully/election code was
untouched. Verified: all 7 tests pass and converge to the correct lex-greatest leader on ALL nodes
(2-node → node-b/node-bbb; 5-node → node-e on all five; 3-node re-elect node-c→node-b; partition
quorum=3 → minority leaderless, majority elects node-e; healing → node-e on all five; `spawnRemote` →
`REMOTE_SPAWN_OK`). No regression: `httpFastEngine/test` 43/43, cluster CLI suites green.

## cli-cluster-election-timing-flake-under-ci-load — snapshot-based WS cluster tests flake in CI
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: 648dbfec5 -->

**Status:** FIXED (mitigated) 2026-07-21 by `ci-last-red` (`15280bb8b`). Root cause is timing, not
logic — the Bully convergence itself is correct (see `v1-cluster-bully-no-convergence-over-ws` above).

**Symptom.** The `sbt — compile and test` CI job went red on ~2/3 of recent runs, always
`Tests: succeeded 619, failed 1`, on a DIFFERENT `scalascript.cli` cluster test each run:
- run `29848826436`: `SingletonFailoverTest` — node-b won re-election but never spawned the migrated
  singleton before the tick landed ("node-b never spawned its migrated singleton instance").
- run `29845877358`: `PartitionHealingTest` — phase-1 `Some("") was not equal to Some("node-e")`: all
  five nodes read an EMPTY `LEADER1` while phase-2 (a longer window) converged to node-e fine.
The same SHA family is green when timing is met (run `29850150239`, SHA `b218aa5e8`, all 4 jobs green),
which disproves the earlier "origin/main was never green" premise.

**Root cause.** `PartitionTest`, `PartitionHealingTest`, `SingletonFailoverTest`, and
`MultiNodeClusterTest` spawn real `ssc.jar` subprocesses that print the elected leader / counter ONCE,
at a fixed `sendAfter` window in the node `.ssc` script (e.g. phase-1 election settle was 3 s). Under
CI contention (5 JVMs + GC pauses) an election occasionally misses that window; the node snapshots an
empty/stale value and the single-shot assertion fails. A node that prints once cannot be recovered by
test-side polling — which is exactly why the sister `ClusterBullyStatusConvergenceTest` (polls
`/_ssc-cluster/status` until convergence) never flakes.

**Fix (mitigation).** `ClusterTestSupport.retrying(n)` re-runs the whole scenario with fresh ports up
to n times — a real regression fails EVERY attempt (last failure rethrown), a transient miss passes on
retry; `TestCanceledException` never retried. All 8 snapshot scenarios wrapped in `retrying(3)`. Plus
widened the tightest windows to proven-sufficient sizes (phase-1 election 3000→4500 ms; failover
migration 12000→18000 ms + survivor deadlines 30→36 s / 25→32 s). NOT a determinism fix — real WS +
real processes are non-deterministic by nature; the robust long-term shape is to convert the snapshot
tests to the status-polling pattern of `ClusterBullyStatusConvergenceTest`.

## js-char-into-int-param — a `Char` passed into an `Int` parameter stays boxed on the v1 JS backend
<!-- status: fixed
     lane: js
     area: front
     fixed-in: b672b0d41 -->

**Status:** FIXED 2026-07-27 in `b672b0d41` (found 2026-07-20 while building
`std/markdown-html.ssc` for the docs site). Exact-SHA CI subsequently exposed the separate
`js-int-boundary-const-lambda` regression, which must be fixed before this claim is released.

**Symptom/reproduce:** six lines, no imports. `ssc-tools run --v1` vs `ssc-tools emit-js | node`:

```scalascript
def isSpace(c: Int): Boolean = c == 32
val s = "a b"
println(s.charAt(1) == 32)      // INT true  | JS true
println(isSpace(s.charAt(1)))   // INT true  | JS FALSE   <-- divergence
println(isSpace(32))            // INT true  | JS true
```

An **inline** comparison against a `charAt` result unboxes correctly on both lanes. Passing the
same value **through a call boundary** into an `Int` parameter does not: on JS the argument
arrives as a boxed `_Char` (`charAt`) or a one-character string (char literal), so the generated
strict `c === 32` does not apply Scala's widening. Note the failure is silent — no error, just a
wrong boolean.

**Blast radius — this breaks the language's own Markdown scanner on that lane.**
`runtime/std/markdown-core.ssc` routes every whitespace test through
`markdownSpace(c: Int)` (:47), called as `markdownSpace(raw.charAt(level))` in `markdownHeading`
(:100) and throughout `markdownBlockStart` (:312). So on the v1 JS backend:

```
markdownParse("# Title")     INT -> H1@1(Title|)     JS -> P@1(T(# Title))
markdownParse("- one\n- two") INT -> UL@1(one,two)   JS -> P@1(T(- one\n- two))
```

Headings, bullet lists, ordered lists and GFM tables all silently degrade to paragraphs, and
trailing spaces survive trimming (an image src came out as `"./logo.png "`). Everything built on
markdown-core inherits this.

**Why nobody noticed:** the markdown-core conformance case is `backends: [v2]`
(`tests/conformance/v2-self-hosted-markdown-core.ssc:4`), so the JS lane has **never** run it.
The gate is green because it never compared on that lane — the failure mode AGENTS.md warns about.

**Fix / done-when:** the JS backend must unbox a `Char` at a call boundary when the parameter is
declared `Int`, exactly as it already does for an inline `==`. Done when the repro above prints
`true` three times on both lanes, `markdownParse("# Title")` yields `H1@1(Title|)` on the JS lane,
and the real Markdown renderer conformance can include `js` without diffs.
`tests/conformance/markdown-html.ssc` was pinned to `backends: [int]` until this fix.

**Resolution / verification (`b672b0d41`).** The code generator now normalizes only parameters
declared `Int`, once at each emitted function boundary, through the existing
`_charCodeOrNull(p) ?? p`; ordinary numbers are unchanged, `_Char` and char literals become their
code point, and String parameters remain untouched. The same helper is used by regular/effectful
defs, object/given/class methods, typed lambdas, TCO wrappers, extensions, and product/enum
constructors, so indirect calls do not depend on call-site name recovery.

The assembled repro is `true/true/true` on both interpreter and `emit-js | node`. A focused
differential covers top-level def, object method, typed lambda, char literal, ordinary Int, and a
negative String parameter (7/7 true on INT/JS/JVM). Full `CrossBackendPropertyTest` is 17/17,
including 74 generated INT↔JS and 19 generated INT↔JVM programs. The previously pinned real
consumer is widened and green:
`tests/conformance/run.sh --only 'markdown-html' --no-memo` → PASS INT + PASS JS.

## ci-testtimeout — "Test via sbt" CI budget expires (cumulative runtime, NOT a WS hang)
<!-- status: fixed
     lane: js
     area: cli
     fixed-in: e25faeb79 -->

**Status:** FIXED 2026-07-20 by `ci-testtimeout`, with the final job/step budget correction in
`e25faeb79`. Root cause diagnosed from the CI logs themselves.

**Symptom:** on run 29700272865 the sbt job's final step `Test via sbt` (`sbt test`, ci.yml) hit the
GitHub 90-min hard `timeout-minutes` and was killed (whole job 2h7m). Log stderr showed
`RejectedExecutionException ... scalascript.server.jvm.WebSocket$$Lambda ... [Terminated]` and
`java.net.SocketException: Socket is closed` on thread `ws-proxy-conn-N`, which pointed suspicion at a
hanging WebSocket test.

**Real root cause (measured, not prejudged):** the WS exceptions are a RED HERRING — benign
shutdown-race stderr noise. Every WS/server suite completes in seconds both locally (whole
`backendInterpreterServer` module 26s; all six `http-server` modules 21s) and in CI
(`backendInterpreterServer` finished at 20:31:08). Parsing the CI log's per-suite timestamps shows NO
infinite hang; the step is dominated by slow cross-backend differential suites. `CrossBackendPropertyTest`
alone took **44.7 min** (half the budget) — it compiles+runs generated programs through
`scala-cli run --server=false` (deliberate cold per-program compile for isolation) and `node`. Top-10
suites = 70.8 min; total measured suite time before the kill = 86.6 min and the phase was STILL running
(all passing) when the 90-min cap fired. The full `sbt test` simply needs > 90 min.

**150-minute follow-up evidence:** exact-SHA run `29713194883` for
`frontend-tui-fetch-refresh` (`f8e688308`) completed lint, validation, and conformance successfully,
then the sbt job was cancelled at its old 150-minute **job** cap while `Test via sbt` was still
running. Every emitted test result before cancellation was passing. The last printed suite,
`GeneratorNativePluginTest`, passes 5/5 in 0.7 seconds when run in isolation; it was only the last
parallel reporter before the slow cross-backend differential tail. This independently confirms that
the job cap, not a generator deadlock or the 150-minute step cap, was binding.

The independent F7 exact-SHA run `29714603655` (`07a4b74f9`) reproduced the same boundary: lint,
validation, and conformance passed, while the sbt job ran from `04:05:23Z` to `06:35:39Z` and was
cancelled at the 150-minute outer cap. Its `Test via sbt` step had been running for 108 minutes with
every emitted result green; the final line was again a parallel `GeneratorNativePluginTest` reporter.

**Fix:**
1. The initial correction raised the `Test via sbt` step cap 90 → 150. Run `29713194883` then proved
   the enclosing 150-minute job cap still bound before that step could finish.
2. `e25faeb79` raises the sbt job cap 150 → 240 and the test-step cap 150 → 200. No test coverage is
   dropped or gated out; both limits remain bounded and now include the measured release-gate plus
   differential-test tail.
3. Guard the benign submit-after-shutdown race in the shared WS runtime
   (`WebSocketRuntime.scala`): `_startHeartbeat` now catches `RejectedExecutionException` from
   `scheduleAtFixedRate` (scheduler `shutdownNow()` raced the accept), and `_runReadLoop` moves
   `socket.getInputStream` inside its existing try so a "Socket is closed" during a teardown race no
   longer escapes the per-connection thread as a spurious `##[error]`. Covers JDK backend + interpreter
   WsProxy + inlined codegen servers. Verified: `runtimeServerJvm`/`runtimeServerJvmFast`/
   `backendInterpreterServer` green after the change, residual noise 0.

**Follow-up (BACKLOG `ci-crossbackend-differential-runtime`):** the 2h+ CI is fragile. Optimize the
cross-backend differential suites (warm/pooled scala-cli compile, or shard the step) to bring the test
phase back under ~60 min without losing the interp==JVM==JS coverage.

## scljet-js-large-page-byteslice-recursion-overflow — a 4096-byte page overflows the JS stack on a byte-slice update
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: unrecorded
     gate: tests/conformance/scljet-large-page.ssc -->

**Status:** **FIXED 2026-07-27** by opus (`scljet-js-copyloop-stack`). `copyLoop` is now iterative
(the var/while idiom `collectBytes` in the same file already used for this exact reason), so a
4096-byte page no longer recurses 4096 frames deep on a runtime without TCO.

⚠️ **The diagnosis below was WRONG about which function.** `replaceAt` walks a 64-byte CHUNK — it is
bounded at 64 frames regardless of page size and could not have caused this. The depth was always in
`copyLoop`, which recursed once per byte of the SLICE. Anyone following the original entry would have
rewritten the wrong function and seen no improvement; the correction is also written next to the fix.

**Verified** by rebuilding both ways: with the recursive `copyLoop` the JS lane crashes outright,
with the fix it prints the correct rows. New case `tests/conformance/scljet-large-page.ssc` uses
4096 ON PURPOSE — every other scljet case uses 512 precisely because 4096 crashed, so the page size
is the assertion. `PASS [INT][JS]`; full scljet sweep 106/106.

**Historical report:** OPEN (found 2026-07-19 by `scljet-hello-example` while writing `examples/scljet-hello.ssc`).
**JS backend / non-tail recursion**, not the engine's logic. Low severity — clear workaround.

**Symptom/reproduce:** `buildTableDatabase(4096, …)` then a façade `INSERT`/`UPDATE` on the JS lane:

```
bin/ssc-tools run-js …  → RangeError: Maximum call stack size exceeded
                          at replaceAt (…) / rawUpdated (…)
```

The same program with `buildTableDatabase(512, …)` runs fine on JS, and 4096 runs fine on the
interpreter and the native lane. `replaceAt`/`rawUpdated` (`scljet/bytes.ssc`) recurse once per byte
of the page during a mutable update; a 4096-byte page recurses ~4096 deep and blows the JS engine's
stack, while 512 stays shallow. The interpreter and native VM trampoline / have deeper stacks, so
they tolerate it.

**Fix direction:** make the byte-slice update iterative (or chunked) rather than one recursion per
byte — an engine change in the `scljet-m3-writes` lane. Until then, examples and `[int, js]`
conformance use 512-byte pages (as the existing scljet cases already do). Filed with a pointer;
`examples/scljet-hello.ssc` uses 512 deliberately.

## jvm-actor-electleader-omits-leader-history — JVM disagrees with INT/JS on accepted self claim
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 34685277c
     confirmed: no
     gate: tests/conformance/run.sc -->

**Status:** FIXED (2026-07-17, JVM runtime `34685277c`, activated oracle `f403cb952`; awaiting
exact-SHA CI confirmation). Found by `ci-red-main` while activating the previously empty
`actors-leader-protocol` conformance gate. Running the tracked source through the same installed
lanes as `tests/conformance/run.sc` and comparing normalized stdout bytes originally gave:

```text
INT: hist1=1
JS:  hist1=1
JVM: hist1=0
```

The surrounding `proto0=bully`, `proto1=raft`, and `ok` lines are identical. The exact JVM repro is
`SSC_SCALACLI_SERVER=0 bin/ssc-tools run-jvm tests/conformance/actors-leader-protocol.ssc`; INT is
`bin/ssc-tools run --v1 ...`, and JS is installed `emit-js` piped to Node. Because the runner strips
trailing whitespace, the comparison did the same before `cmp`/`diff`; this is semantic output, not
a newline artifact.

**Expected/fix plan.** Keep the expected fixture absent while outputs disagree. Trace the JVM actor
lowering/runtime path for synchronous single-node `electLeader()` and `leaderHistory()` against the
actor cluster spec and the working INT/JS implementations. Fix the shared lowest correct layer,
add a JVM-faithful regression, rerun the three raw lanes, then activate both leader fixtures only
after byte equality. Do not copy the majority output into expected while JVM is still wrong.

**Root cause/fix.** Generated JVM `_startElection()` kept both the change notification and history
write behind `prev != _localNodeId`. In empty-id single-node mode both values start as `""`, so it
accepted the claim but silently dropped history. INT and JS already recorded every accepted claim
and guarded only the notification. `34685277c` makes JVM symmetric and adds a generated-source e2e
that failed `0 != 1` before the fix. The complete `JvmGenEffectsRuntimeTest` passes 35/35. Rebuilt
installed INT/JS/JVM outputs are byte-identical for both leader cases; the forced conformance run
passes 2/2 with all three lanes.

## js-actor-stop-cps-emits-unbound-call — completed actor body crashes on raw `stop()`
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 4a4425f68
     confirmed: no -->

**Status:** FIXED (2026-07-17, `4a4425f68`; awaiting exact-SHA CI confirmation). Found by
`ci-red-main` while adding the faithful Long-timer Node regression. The generated bundle
successfully delivered Long `sendAfter`, Long `sendInterval`, and a Long timed receive, printed
`once`, `interval`, `timeout`, then evaluated source `stop()` as a raw JS call and exited with
`ReferenceError: stop is not defined` from the actor continuation.

**Expected/fix plan.** Inspect the shared normal/CPS actor bare-name lowering and route `stop()`
through the same `_perform('Actor', 'stop', ...)` contract as other actor operations. Keep the real
Node execution in the timer regression; do not remove `stop()` merely to make the test terminate.
Rerun the full Node backend suite and the cross-backend cluster matrix.

**Root cause/fix.** CPS actor lowering recognized the other actor operations but let bare `stop()`
fall through as an ordinary source call. `4a4425f68` lowers it to the shared `Actor.stop()` effect
operation and keeps `stop()` in the real Node fixture. `backendNode/test` passes 60/60, the staged
JVM-codegen + JS-codegen Bully matrix passes 1/1 with zero cancellations, and
`actors-supervision` passes INT/JS/JVM.

## cluster-multibackend-js-actor-mixes-bigint-number — generated node dies before Bully convergence
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 4a4425f68
     confirmed: no -->

**Status:** FIXED (2026-07-17, runtime `4a4425f68`, staged matrix `74ab54c90`; awaiting exact-SHA
CI confirmation). Found by `ci-red-main` during SPRINT 5r's separate staged audit.
`scripts/sbtc "cli/testOnly scalascript.cli.ClusterMultiBackendMatrixTest"` executes the one test
with zero cancellations, starts the JVM-codegen node, and generates/launches the JS-codegen node.
The latter prints `Listening on http://localhost:<port>/ (backend=node)` and then exits before the
HTTP-ready probe can connect:

```text
TypeError: Cannot mix BigInt and other types, use explicit conversions
    at handleActorOp (.../node-bbb.js:6828:37)
    at stepActor (.../node-bbb.js:6871:21)
```

**Expected/fix plan.** Preserve the real two-process convergence assertion and inspect the emitted
`handleActorOp` expression plus its typed source/IR to identify which actor state/message operand
crosses the `BigInt`/`Number` boundary. Before editing, check authoritative claims for actor/JS
runtime ownership; consume an owner fix if overlapping, otherwise add a faithful regression at the
lowest shared codegen layer and rerun this full matrix. Do not weaken HTTP readiness or coerce the
test fixture merely to hide the generated runtime type error.

**Root cause confirmed.** Commit `70dfb5a1f` correctly made source `Long` values JS `BigInt`, but
the actor timer boundary still does native date arithmetic. The matrix fixture's
`sendAfter(500L, ...)` reaches `const fireAt = Date.now() + delayMs` with a `BigInt` delay and throws
at the reported `handleActorOp` line. `sendInterval` has the same expression, and timed receive adds
its timeout to `Date.now()` likewise. Convert these millisecond inputs explicitly to `Number` at the
JS host timer boundary and cover Long one-shot + interval delivery under a real Node bundle.

**Fix/verification.** `4a4425f68` converts all three source-millisecond values to `Number` exactly
at the JS host timer boundary and adds a real Node integration for Long one-shot delivery, interval
delivery, timed receive, and clean actor stop. `74ab54c90` moves the two-process matrix to installed
`ssc-tools` plus resource-discovered Scala runtime JARs; once prerequisites exist, compile/link
failures are assertions with exit/stdout/stderr rather than cancellations. `backendNode/test`
passes 60/60, the staged JVM-codegen + JS-codegen matrix passes 1/1 with zero cancellations and
converges on `node-bbb`, and `actors-supervision` passes INT/JS/JVM.

## examples-run-all-standard-launcher-tools-command — all JS/JVM example lanes call forbidden commands
<!-- status: fixed
     lane: js
     area: front
     fixed-in: ef335ee2c
     confirmed: no -->

**Status:** FIXED (2026-07-17, routing `ef335ee2c` + same-frontend correction `aea328279`; awaiting
CI confirmation). Found by `ci-red-main` in run `29547121050`, SHA `1e6ccb394`. The conformance
corpus immediately before it is green: `282 passed, 0 failed (+ 2 pending)`. The next CI step,
`scala-cli examples/run-all.sc`, printed the canonical INT output for all 17 examples and then
reported a non-zero JS and JVM lane for every example.

**Real-harness repro.** Build the full distribution and run `scala-cli examples/run-all.sc`. The
harness binds only `bin/ssc`; `runJvm` calls `bin/ssc run-jvm`, and the JS fallback calls
`bin/ssc emit-js`. Since the 2.1 cutover, `bin/ssc` is the compiler-free standard tier and correctly
rejects both commands with `requires the optional ScalaScript tools/compatibility tier; run
ssc-tools explicitly`. The harness therefore tests launcher routing, not backend parity.

**Expected/fix plan.** Keep all three comparisons on one frontend/runtime family: route INT through
installed `bin/ssc-tools run --v1`, JS through `emit-js`+node, and JVM through `run-jvm`. Do not
re-enable tools commands in the standard launcher or treat a v2-vs-v1 semantic difference as a v1
backend parity failure.

**Root cause/fix.** The first correction routed JS/JVM to tools but left INT on post-cutover v2
native, exposing a legitimate separate v2 auto-output gap. The matrix's declared INT/JS/JVM contract
is the v1 backend family, matching conformance, so the final fix uses one installed `ssc-tools` for
all lanes. Missing staging now names its exact path. The full 17-file matrix exits 0 with
byte-identical output on all three lanes; the v2 gap remains independently open.

## v2-js-regfields-unimplemented — installed `run-js --v2` crashes before a case-class program starts
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 2f23fd9ec
     confirmed: no -->

**Status:** FIXED 2026-07-17 in `2f23fd9ec` (awaiting CI confirmation). Found by making
`V2JsLaneCliTest` use the real installed launcher.
The prior regression used `java -jar` against the fat assembly and did not exercise the staged
`bin/ssc-tools` path; after that apparatus was corrected, the imported-companion case failed in
node with `Error: unimplemented primitive: __regfields__`.

**Real-harness repro.** Build `cli/assembly` + `installBin`, then run
`scripts/sbtc "cli/testOnly *V2JsLaneCliTest"`. The first installed-launcher test (simple output)
passes after the `run-js` exit-code fix; the imported `case class Box(value: Int)` + companion test
exits 1 at module initialization, before its expected `0 / 5 / 8` output. `ssc1-lower` emits one
`__regfields__(tag, names)` registration for each case class. The portable VM consumes it; Swift
explicitly treats it as a no-op because its field accesses are already index-resolved; JsBackend
has no case and falls into `$prim`, which always throws.

**Root cause/fix.** The JS backend, like Swift, lowers case-class field selection to index-based
access and therefore needs no runtime name registry, but unlike Swift it had not declared the
registration primitive as an explicit no-op. JsBackend now emits `null` for `__regfields__` with
that invariant documented. A direct generated-code assertion plus the installed companion e2e
both pass; `V2JsLaneCliTest` is 3/3.

**Done when.** JsBackend gives `__regfields__` an explicit, documented meaning, the installed CLI
test passes, and a direct backend unit test prevents the primitive from falling through to `$prim`.

## dataset-from-generator-js-compound-assign-dispatch — generated JS calls method `+=` on a number
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 1e6ccb394
     confirmed: no
     gate: tests/conformance/run.sh -->

**Status:** FIXED 2026-07-17 in `1e6ccb394` (awaiting CI confirmation). Reproduced by
`ci-red-main` from `origin/main` `771b67d45`. This is
the second and independent failure in the otherwise 279/281 conformance suite; it is not caused by
the global `run-js --v2` exit-code defect because node throws before producing the expected output.

**Real-harness repro.** Build the worktree distribution with
`scripts/sbtc "compile; cli/assembly; installBin"`, then run:

```bash
tests/conformance/run.sh --only 'dataset-from-generator' --no-memo
```

INT and JVM pass. JS exits 1 in generated code with
`Error: Method not found: += on 1`; the stack enters `_dispatch`, the generated `Generator.next`,
`toList`, `_Dataset._sourceFn`, and `_Dataset.collect`. Expected output is four lines:
`12, 14, 16, 18, 20`, `1`, `9`, `25`. The failure must be pinned at the generated-JS/runtime
boundary and fixed there; changing the expected output or suppressing the exception would preserve
the wrong program semantics.

**Root cause/fix.** Scala-meta preserves `i += 1` as `Term.ApplyInfix`, and the interpreter's
block runtime explicitly turns a mutable-name compound assignment into read/base-op/write. JsGen's
dedicated generator statement emitter omitted that rule and sent the literal method name `+=` to
`_dispatch`. It now reconstructs the base infix (`+` here) through the ordinary numeric generator
and writes the result back. `GeneratorTest` is 15/15 and focused conformance is 1/1 on INT/JS/JVM.

**Done when.** The focused conformance case passes on every declared lane, a regression separately
asserts that mutable generator state increments numerically instead of method-dispatching `+=`, and
the full CI conformance job is green.

## run-js-v2-always-exits-1 — `run-js --v2` returns exit 1 on success, for every program
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 8333cf97a
     confirmed: no -->

**Status:** FIXED 2026-07-17 in `8333cf97a` (awaiting CI confirmation). It blocked conformance
`deep-tail-recursion` (`FAIL [JS]`, `line 4: expected=<missing> got=<exit:1>`), one of the last two
red cases in an otherwise 279/281 suite.

**Actual root cause (the earlier exit-handler hypothesis was wrong).** The one-line Scala 3 catch
in `RunNativeV2.runNodeAndWait` was written as
`catch case _: InterruptedException => proc.destroy(); System.exit(1)`. The semicolon ended the
catch body for code generation: `javap -c -l -p` showed both the successful `waitFor` path and the
exception path joining immediately before an unconditional `iconst_1; System.exit`. Node really
returned 0; the JVM then always replaced it with 1. A multiline catch keeps destroy/re-interrupt/
exit inside the exceptional arm.

**Correction of the previous diagnosis.** The statement below that instrumentation proved
`runNodeAndWait` “falls off the end” was false: it proved only that `exitCode == 0` skipped the
*conditional* exit. Source-level tracing missed the second, unconditional bytecode exit. The
installed-launcher regression now exercises `bin/ssc-tools` (not the fat `java -jar` proxy) and
asserts the real child process exit. `deep-tail-recursion` is 1/1 on INT/JS/JVM.

**Symptom.** `bin/ssc-tools run-js --v2 <any file>` prints the correct output and then exits **1**.
Nothing is wrong with the program: stdout is byte-correct, stderr is EMPTY.

**It is not about recursion or stack** — the obvious hypothesis (node's ~1 MB default stack, the
JS analogue of `tower-thread-hardcoded-64m-stack` below) is **wrong**. Measured:

```bash
printf 'println("hi")\n' > /tmp/tiny.ssc
bin/ssc-tools run-js /tmp/tiny.ssc          # v1 codegen  → hi, exit 0
bin/ssc-tools run     --v2 /tmp/tiny.ssc    # v2 VM       → hi, exit 0
bin/ssc-tools run-js  --v2 /tmp/tiny.ssc    # v2 JS       → hi, exit 1   ← every program
```

`deep-tail-recursion` prints all 3 expected lines (`0`, `5000050000`, `12`) and still exits 1. The
harness renders that as a phantom 4th line `<exit:1>`, which is why it reads as a truncated/crashed
run rather than a bad exit code.

**What is already ruled out — do not re-do this work:**

- **node is NOT the source.** A shim placed on `PATH` in front of the real node logged both
  invocations: `node --version` → exit 0, and `node /tmp/ssc-native-js-*.cjs` → **exit 0**.
- **`runNodeAndWait` returns normally.** Instrumented `RunNativeV2.runNodeAndWait`: it observes
  `exitCode=0`, skips its `System.exit`, and falls off the end of the method. So the process is
  still healthy at that point and the 1 is manufactured afterwards.
- **The generated JS does not exit.** `JsBackend` emits `process.exit` for exactly one thing,
  `io.exit`; the entry epilogue only `console.log`s a non-Unit result.
- **No stale/duplicate classes.** `scalascript/cli/RunJsCmd` exists in exactly one jar
  (`bin/lib/ssc.jar`), which is the one on `ssc-tools`' classpath.

**The one loose thread, unexplained.** With debug prints compiled into the jar (verified present
with `grep -a` on the extracted classes — a plain `grep` lies here, the strings are in the class
constant pool), `RunNativeV2.runNodeAndWait`'s trace printed but the *next* statement in
`RunJsCmd.run` after `RunNativeV2.runJs(...)` did **not**, nor did one added at
`dispatchCommand(...).exitIfFailure()` in `Main$package`. So the JVM appears to leave between
`runNodeAndWait` returning and its caller resuming — with `main` never reaching the dispatcher tail.
There is no global catch in `@main def ssc` and stderr is empty, so an uncaught exception is
unlikely. Suspect a `System.exit` reached via a path not yet traced (a shutdown hook, or
`ssc.Runtime.exitHandler` — `runTower` swaps it for `code => throw new TowerExit(code)` and restores
it in a `finally`; what the DEFAULT handler does was not checked and is the next thing to look at).

## cli-launcher-default-stack-platform-dependent — every `scljet-*` case fails on Linux and passes on macOS
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: unrecorded
     confirmed: no
     gate: tests/conformance/scljet-byte-codec.ssc -->

**Status:** FIXED (2026-07-16, `build.sbt` installBin launcher templates + regenerated `bin/ssc`),
awaiting the CI run that proves it. Found while investigating why `origin/main` CI was red.

**Symptom.** All 51 `scljet-*` conformance cases FAIL `[INT]` in GitHub CI with
`java.lang.StackOverflowError` (stack: `DispatchRuntime.infix2` → `Interpreter.infix2` →
`EvalRuntime.evalCore`), while the same cases pass on every developer's mac. Conformance in CI:
228 passed / 53 failed of 281 — **51 of the 53 were scljet**.

**Root cause.** The generated launchers (`bin/ssc`, `bin/ssc-tools`, `bin/ssc-provider`) passed no
`-Xss`, so the tree-walking interpreter — which recurses once per AST node — ran on the JVM's
**default main-thread stack**. That default is platform-dependent: **2m on macOS/arm64, 1m on
Linux/x86_64**. scljet is a large pure-`.ssc` program that recurses past 1m, so the whole family
overflows on Linux only. Nothing about it is scljet-specific: any deep program hits it, so this was
also a real user-facing bug for Linux users, not just a CI artifact.

**Reproduce** (the INT lane is `bin/ssc-tools run --v1`):

```bash
java -Xss2m -cp "bin/lib/jars/*:bin/lib/ssc.jar" scalascript.cli.ssc \
  run --v1 tests/conformance/scljet-byte-codec.ssc   # exit 0, byte-identical to expected
java -Xss1m -cp "bin/lib/jars/*:bin/lib/ssc.jar" scalascript.cli.ssc \
  run --v1 tests/conformance/scljet-byte-codec.ssc   # java.lang.StackOverflowError, exit 1
```

**Two traps that cost time here — read before re-investigating:**

1. **`JAVA_TOOL_OPTIONS=-Xss…` does NOT reproduce it.** The `java` launcher sizes the main thread
   from the *command-line* `-Xss`; `JAVA_TOOL_OPTIONS` is read later by the VM and silently does
   nothing for that thread. Using it made the stack hypothesis look *disproven* (cases "passed" even
   at `-Xss256k`). Pass `-Xss` on the command line.
2. **Plain `bin/ssc run` does not reproduce it either** — that is the v2 NATIVE lane, and
   `RunNativeV2.scala:194` already runs its interpreter on a thread with an explicit 64 MB stack.
   Only the v1 INT lane (`ssc-tools run --v1`) inherits the platform default.

**Fix.** `-Xss64m` in all three launcher templates in `build.sbt` — matching the stack
`RunNativeV2` already gives its interpreter thread, so the INT lane is consistent with the native
lane instead of inheriting whatever the OS defaults to. Edited in **`build.sbt`, not `bin/*`**: the
launchers are generated by `installBin`, and a direct edit to `bin/ssc-tools` would be silently
overwritten by the next build.

**Why nobody noticed.** Agents verify on macOS, where the default is 2m, so local gates were
genuinely green while CI was genuinely red — and nobody was reading CI. `origin/main` had **192
consecutive red runs, zero green**. See `ci-red-main` in SPRINT.

## scala-direct-inline-wrapper-owner-escape — inline marker wrapper loses bindings and provenance
<!-- status: fixed
     fixed-in: unrecorded
     lane: js
     area: runtime
     gate: sbt scala3ControlApi/test -->

**Status (SUPERSEDED — see the verification at the end of this entry):** was open; remediation and regression are green on the feature branch
(2026-07-15), but fresh independent rereview and the landing SHA are pending.
Reported as P1 by the independent `scala3-control-macros` review of frozen
checkpoint `fa992fd92`.

**Symptom/reproduce:** define an inline method that accepts ordinary parameters
and a matching `direct.Scope`, then delegates to `direct.shift`. Calling that
wrapper in a block-level direct bind fails during macro expansion with a raw
`reference to parameter contextual$2 was used outside the scope where it was
defined` ownership error. A side-effectful prompt argument also makes dropping or
duplicating inline bindings an observable single-evaluation risk.

**Root cause/plan:** marker normalization strips every quoted `Inlined` node and
therefore discards its bindings and call provenance before the lowering can prove
their owners or evaluation order. M1 will accept only a directly proven marker
shape: provenance-bearing or binding-bearing inline expansions must fail closed at
the exact marker with stable `DIRECT_STYLE_UNSUPPORTED`. Add a side-effectful
wrapper regression that proves no wrapper code is executed.

**Implementation/verification:** marker normalization now preserves every
`Inlined` node, and both inline expansion boundaries and unexpanded inline
applications fail closed before their prompt/body arguments can be moved. The
side-effectful wrapper regression passes in the 16/16 clean-compiled diagnostic
suite; keep this entry open until rereview approves and the fix lands.

**VERIFIED FIXED 2026-08-09.** This entry says its fix exists and awaits landing or review. **No cited sha landed under that hash** — the commits here are frozen review checkpoints, which is what made this look unlanded. The BEHAVIOUR is implemented and pinned.

Pinned by:
  - `an inline wrapper around a marker fails closed before prompt evaluation` — the plan's words were "must fail closed"

`sbt scala3ControlApi/test`: **165 tests, 165 pass**.

Same shape as the thirteen entries closed on the root board earlier today: written the same day as
its fix, describing a state that stopped being true, with nobody re-running the one command that
could tell. **Matched by SUBJECT and by the entry's own stated plan, not read line by line** — except
where the pin is the implementation itself, which is quoted with its file and line.


## scala-direct-deferred-nonlocal-return — OPEN / fix planned (2026-07-15, Codex)
<!-- status: fixed
     fixed-in: unrecorded
     lane: js
     area: runtime
     gate: sbt scala3ControlApi/test -->

**Status (SUPERSEDED — see the verification at the end of this entry):** was open; implementation and regressions are green on the feature branch,
but fresh independent rereview and the landing SHA are pending. Found by Codex
during the pre-integration fail-closed audit of `scala3-control-macros`.

**Symptom:** a source `return` in a pure `direct.reset` body, or in the suffix
after `direct.shift`, could be moved into the explicit reset's deferred
computation. The enclosing Scala method has already returned when that `Eff`
runs, so preserving the tree would turn source return into escaped exception-based
control instead of the specified explicit effect semantics.

**Reproduce:** compile a method returning `Eff[Nothing, Int]` whose
`direct.reset` body executes `return Eff.pure(7)`, and a second method that first
binds `direct.shift` and then executes `return Eff.pure(selected)`. Both shapes
must fail at the exact `return` with `DIRECT_STYLE_UNSUPPORTED`; neither may
produce a runnable `Eff`.

**Root cause:** the bounded lowering validated markers under `Return` nodes, but
did not independently reject a `Return` targeting a method outside the contextual
reset body before moving that body under `Eff.defer` and generated continuations.

**Fix/verification:** `DirectMacros` now inspects the typed body before lowering
and rejects only returns whose target is outside the lexical reset owner; returns
local to a nested method remain owned by that method. Pure-body, captured-suffix,
and safe local-return regressions pass; the full control leaf is 92/92 and the
packaged-JAR example compiles and runs. Keep this entry open until rereview
approves and the fix lands.

**VERIFIED FIXED 2026-08-09.** This entry says its fix exists and awaits landing or review. **No cited sha landed under that hash** — the commits here are frozen review checkpoints, which is what made this look unlanded. The BEHAVIOUR is implemented and pinned.

Pinned by:
  - `a pure reset body cannot defer a non-local return`
  - `a captured suffix cannot contain a non-local return`

`sbt scala3ControlApi/test`: **165 tests, 165 pass**.

Same shape as the thirteen entries closed on the root board earlier today: written the same day as
its fix, describing a state that stopped being true, with nobody re-running the one command that
could tell. **Matched by SUBJECT and by the entry's own stated plan, not read line by line** — except
where the pin is the implementation itself, which is quoted with its file and line.


## js-control-direct-packed-local-dev-dependency — tarball escapes to repository sibling
<!-- status: fixed
     lane: js
     area: build
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: 9baf6d2bf -->

**FIXED — verified 2026-08-07.** The repair landed as `9baf6d2bf`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `exact tarball manifest has no local dependency and installs at a clean boundary`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`. Independently re-measured on 2026-08-07 without using the suite at all: `npm pack`, extract, and the published manifest carries `devDependencies: {typescript: 5.9.3}` and nothing local; `npm install --ignore-scripts` in the extracted package with no sibling checkout succeeds and the ESM import loads. THIS ENTRY IS ALSO WHY THE TEST EXISTS: `file:../control` in devDependencies WAS this bug. I proposed re-adding exactly that to make the suite resolve its sibling, and test/package.test.js went red on it — the design defending itself.

**Symptom/reproduce:** run `npm pack` for `v2/host/js/control-direct`, extract the
result into a fresh directory with no `../control`, and inspect
`package/package.json`. Its published `devDependencies` contains
`"@scalascript/control": "file:../control"`. An ordinary
`npm install --ignore-scripts` in the extracted package creates a control dependency
pointing outside the payload (or otherwise relies on that missing sibling), and an
ESM import fails with `ERR_MODULE_NOT_FOUND`. The existing packed-consumer test uses
`--omit=dev`, so it never exercises the broken published development manifest.

**Required fix/verification:** the exact tarball manifest—not merely the working-tree
JSON—must contain no repository-local dependency specifier in any production,
optional, peer, development, or override/resolution-style dependency field. Forbid
`file:`, `link:`, `workspace:`, absolute paths, and relative local escapes; retain
only the qualified non-local TypeScript development pin and preserve empty
production/optional/peer dependency maps. Extract the exact tarball at a clean
boundary, run ordinary install without a sibling checkout, and prove it does not
create a dangling local control dependency. Existing packed CLI installation and
production execution must remain green.

**Root cause/fix candidate:** `package.json` and `package-lock.json` used the sibling explicit
control package as a publishable `file:../control` development dependency for local
tests/typechecking. npm includes `package.json` in the exact payload, so the repository
topology leaks even though `npm pack --dry-run` reports no bundled dependencies.
The candidate removes that entry from manifest and lock, supplies the type declaration
through a TypeScript path, and gives compiler/runtime tests explicit temporary
symlinks. Its exact-tar regression reads the extracted manifest, ordinary-installs
with no sibling, proves no control link exists, and imports both published subpaths.
Direct package tests pass 39/39; its exact pack, explicit control 31/31, catalog
26/9, negative validator 9/9, and affected conformance 5/5 are green. The bug remains
open until landing and a new independent reviewer confirmation.

## js-control-direct-import-equals-bypass — runtime marker require evades fail-closed import scan
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: fabad7d84 -->

**FIXED — verified 2026-08-07.** The repair landed as `fabad7d84`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `external marker import-equals is fail-closed except when explicitly type-only`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`.

**Symptom/reproduce:** compile a CommonJS/Node10 source containing
`import markers = require("@scalascript/control-direct")`, with or without a use of
`markers.direct`. The exact-module syntax is an `ImportEqualsDeclaration`, not the
`ImportDeclaration` shape checked by the current default/namespace rejection, so the
runtime marker require can survive without `JS_DIRECT_UNSUPPORTED`.

**Required fix/verification:** recognize an external-module
`ImportEqualsDeclaration` whose string-literal module reference is exactly the marker
package. Reject every runtime form once with a stable source span and file-atomic
unchanged emit when diagnostics are ignored. Accept `import type markers =
require(...)` as an erased, non-runtime form. Test used/unused runtime imports, the
type-only form, and packed-CLI CommonJS/Node10 JavaScript plus declarations.

**Root cause/fix candidate:** exact-module import collection handled only ES
`ImportDeclaration`, so external `ImportEqualsDeclaration` never entered marker
ownership. The candidate recognizes only a string-literal `ExternalModuleReference`,
diagnoses runtime used/unused forms at the local name, and JavaScript-erases the
explicit type-only form while declaration emit retains it. Programmatic and real
packed-CLI CommonJS/Node10 regressions are green under both verbatim modes; the full
direct package passes 38/38.

## js-control-direct-type-export-runtime-link — erased export retains dev-only module link
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: fabad7d84 -->

**FIXED — verified 2026-08-07.** The repair landed as `fabad7d84`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `type-only source exports drop empty runtime links and preserve mixed values`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`.

**Symptom/reproduce:** with `verbatimModuleSyntax: true`, compile
`export { type direct as Marker } from "@scalascript/control-direct"`. The transform
does not select the erased specifier-level source export for JavaScript normalization;
TypeScript emits `export {} from "@scalascript/control-direct"`. Running production
output without the dev-only marker package fails with `ERR_MODULE_NOT_FOUND`.

**Required fix/verification:** in the JavaScript channel, select exact-module
type-only source exports, discard their erased specifiers, remove the whole export
when no runtime specifier remains, and preserve mixed ordinary runtime specifiers.
Declaration emit must see the original source form. Test declaration- and
specifier-level aliases, mixed exports, both verbatim modes, packed CLI syntax,
production execution without the marker package, and emitted `.d.ts`.

**Root cause/fix candidate:** collection skipped specifier-level type-only source
exports, so the JavaScript transformer never selected their file and verbatim emit
preserved an empty module-linked export. The candidate selects exact-module
type-only `direct` exports, removes every erased specifier in the JavaScript channel,
drops an empty declaration, and preserves mixed runtime specifiers. Both verbatim
modes retain the original `.d.ts`, and packed production runs without the marker
package; the full direct package passes 38/38.

## js-control-direct-mixed-type-import-invalid-js — marker erasure leaves TypeScript syntax
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: fabad7d84 -->

**FIXED — verified 2026-08-07.** The repair landed as `fabad7d84`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `mixed marker imports normalize JavaScript but preserve declarations`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`.

**Symptom/reproduce:** compile a transformed file containing
`import { direct, type DirectMarkerContractError as ErrorType } from
"@scalascript/control-direct"`. The after-transform import rewrite removes the runtime
marker but retains the type-only specifier, producing `import { type ErrorType }` in a
`.js` file. The real packed CLI exits zero, yet `node --check` fails with `SyntaxError`.

**Required fix/verification:** treat JavaScript and declaration emit independently.
The JavaScript rewrite must drop type-only specifiers while preserving unrelated
runtime values and remove the declaration if nothing runtime remains; declaration
emit must retain the original TypeScript import and type information. Exercise both
`verbatimModuleSyntax: false` and `true` through the packed CLI, validate JavaScript
with Node, and assert the generated `.d.ts` surface.

**Root cause/fix candidate:** `rewriteMarkerImport` rebuilt a selected TypeScript
import after normal type analysis but retained specifier-level `isTypeOnly` nodes;
the JavaScript emitter therefore printed TypeScript syntax instead of erasing it.
The candidate explicitly removes completed marker and type-only specifiers in the
JavaScript channel while leaving TypeScript's declaration pipeline on the original
source. Both verbatim modes pass real packed-CLI `node --check`, `.d.ts`, and
production-without-marker regressions; the full direct package passes 38/38.

## js-control-direct-type-only-export-false-positive — erased exports are rejected
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: ec95c4c65 -->

**FIXED — verified 2026-08-07.** The repair landed as `ec95c4c65`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `type-only marker exports erase without runtime diagnostics`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`.

**Symptom/reproduce:** TypeScript files using local
`export type { direct as Marker }`, direct
`export type { direct } from "@scalascript/control-direct"`, or inline
`export { type direct } from "@scalascript/control-direct"` receive
`JS_DIRECT_UNSUPPORTED` even though all three forms are erased and cannot leave a
runtime marker value in JavaScript.

**Required fix/verification:** the accepted grammar must distinguish declaration-
level and specifier-level type-only exports before runtime owned-marker checks. Keep
the erased forms diagnostic-free, preserve normal TypeScript emit, and retain stable
`JS_DIRECT_UNSUPPORTED` for local runtime exports/re-exports and aliases. Cover all
three type-only spellings plus shadowing in the real compiler harness.

**Root cause/fix candidate:** export collection treated every direct-module export
as runtime syntax, while generic type-only detection stopped at an export without
reading declaration/specifier flags. The candidate classifies `isTypeOnly` before
runtime ownership and proves five local/source declaration/specifier spellings erase
without a diagnostic; a shadowed local runtime export remains ordinary. Direct
package tests pass 35/35.

## js-control-direct-marker-shorthand-export-survivor — owned values evade scanning
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: ec95c4c65 -->

**FIXED — verified 2026-08-07.** The repair landed as `ec95c4c65`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `marker shorthand and local runtime export aliases fail file atomically`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`.

**Symptom/reproduce:** place runtime shorthand `{ direct }`, local
`export { direct }`, or `export { direct as alias }` in a file selected for marker
transformation. Checker lookup at the shorthand/export identifier does not resolve
to the imported runtime value under the current scan, so no direct diagnostic is
reported; completed-import rewriting can then erase the import while emitted code
retains an unbound shorthand or export.

**Required fix/verification:** resolve the runtime value behind shorthand properties
and local export specifiers (including aliases) through the TypeScript checker, then
reuse exact owned-marker identity. Every surviving runtime use must receive one
stable `JS_DIRECT_UNSUPPORTED` and cancel the entire source-file rewrite; ignored-
diagnostic emit must retain the original import and marker syntax. Avoid duplicate
diagnostics when an export node is reachable through more than one scan.

**Root cause/fix candidate:** raw identifier lookup exposes a shorthand property or
exported-name symbol rather than its local runtime value. The candidate centralizes
value-symbol resolution, uses `getExportSpecifierLocalTargetSymbol`, and handles an
export specifier once without visiting its identifier children. Runtime shorthand,
assignment-initializer shorthand, and local/source aliases each get exactly one
file-atomic diagnostic with unchanged ignored-diagnostic emit.

## js-control-direct-prefix-tdz-binding-escape — moved suffix exposes an outer binding
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: 4c6b8e2a9 -->

**FIXED — verified 2026-08-07.** The repair landed as `4c6b8e2a9`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `real JavaScript prefix references cannot cross into a marker suffix binding`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`.

**Symptom/reproduce:** define outer `const later = 99`; inside a direct reset, read
`later` in a pure prefix declaration before the first marker, then declare inner
`const later = 42` after the marker. The original block resolves the prefix read to
the inner binding and throws from its temporal dead zone. Lowering moves that inner
declaration into the continuation callback, so the unchanged prefix read resolves
at runtime to the outer `99`; the reproduced candidate returned `141` with no
direct diagnostic. TypeScript reports 2448/2454, but real JavaScript under
`allowJs: true, checkJs: false` has the same runtime semantic change without a type
gate.

**Required fix/verification:** for every marker boundary, use checker symbol identity
to reject value references from that boundary's pure prefix statements or shift body
to the marker's own or any later suffix binding. Preserve type-only references and
genuine shadowing. Add a real-JavaScript outer-shadow/TDZ regression proving stable
`JS_DIRECT_CAPTURE_BARRIER`, no transformed file, and unchanged emit when diagnostics
are ignored; retain accepted preceding-binding evaluation order.

**Root cause/fix candidate:** the first repair scanned only each marker's shift body,
not the pure statements kept before that marker's generated continuation. The
candidate now checks the complete marker layer by checker-symbol identity, reports
the first crossing value reference, preserves type-only/shadowed references, and
keeps ignored-diagnostic emit file-atomic. Direct package tests pass 31/31.

## js-control-direct-wrapped-marker-receiver-missed — transparent TS wrappers evade ownership
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: c19d42401 -->

**FIXED — verified 2026-08-07.** The repair landed as `c19d42401`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `transparent TypeScript wrappers preserve exact marker ownership`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`.

**Symptom/reproduce:** exact-import marker calls written as `(direct).reset(...)`,
`direct!.reset(...)`, or `(direct as typeof direct).reset(...)` are not consistently
recognized as the imported marker symbol. Depending on the surrounding tree they
can survive emit or receive a generic unsupported-shape result instead of the same
bounded transform/diagnostic contract as an unwrapped `direct.reset(...)` call.

**Required fix/verification:** recursively unwrap only semantically transparent
parentheses, `as`, non-null, and type-assertion expressions before symbol ownership
checks. Positive reset/shift cases and negative unsupported cases must prove stable
source spans and must leave no marker call in emitted JavaScript.

**Root cause/fix candidate:** ownership recognition examined the raw receiver/callee
node. The candidate recursively removes only parentheses, `as`, non-null, and type
assertions before exact checker-symbol matching; positive and negative wrapper
regressions prove that no owned marker call survives a clean emit.

## js-control-direct-consumer-typescript-resolution — CLI resolves the tool's compiler, not the consumer's
<!-- status: fixed
     lane: js
     area: runtime
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: c19d42401 -->

**FIXED — verified 2026-08-07.** The repair landed as `c19d42401`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `packed installed bin uses the consumer compiler and emits production-only imports`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`. The store holding the installed CLI has no `typescript` at all — it is symlinked only into the consumer, and the bin runs with `cwd` set there, so the test cannot pass by finding an ambient compiler.

**Symptom/reproduce:** place the published runtime files under an external
tool/store path, install `typescript` only in a consuming project, and run the real
CLI with `--project <consumer>/tsconfig.json`. `cli.js` performs bare
`import("typescript")` relative to itself and exits with “TypeScript compiler API not
found”, contradicting the contract that the CLI uses the consuming project's
compiler. Strict/symlinked stores and external/npx tools exhibit the same boundary.

**Required fix/verification:** resolve TypeScript with a consumer-owned Node issuer
anchored at the explicit project/config directory or current working directory; do
not fall back to an ambient/global compiler. An extracted packed-package fixture
must keep TypeScript only under the consumer, invoke the installed CLI, succeed, and
also prove that an otherwise identical consumer without TypeScript gets a stable
actionable error.

**Root cause/fix candidate:** bare module import resolved from the tool package's
location. The candidate uses `createRequire` from the explicit project/config
directory or cwd, never falls back to the store/global environment, and exercises
both present and missing consumer compilers through the packed installed bin.

## js-control-direct-marker-import-survives-emit — build-time marker becomes a production dependency
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: c19d42401 -->

**FIXED — verified 2026-08-07.** The repair landed as `c19d42401`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `packed installed bin uses the consumer compiler and emits production-only imports`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`. The specific assertion is `assert.doesNotMatch(readFileSync(output, "utf8"), /@scalascript\/control-direct/)` on the emitted JavaScript, in two places.

**Symptom/reproduce:** transform a valid source importing named `direct` from
`@scalascript/control-direct`, inspect the emitted JavaScript, then deploy it with
development dependencies omitted. The marker calls are lowered but the original
value import remains, so Node fails module resolution even though documentation
installs the marker package with `--save-dev`.

**Required fix/verification:** after every value use of an exact owned marker binding
has been transformed, remove only that marker import specifier (and the now-empty
declaration), preserving unrelated imports/specifiers. Diagnose every surviving
marker value use rather than emitting it. A packed production-consumer fixture must
run emitted JavaScript with `@scalascript/control` present and
`@scalascript/control-direct` absent.

**Root cause/fix candidate:** lowering rewrote marker calls but did not prove that all
owned value uses were consumed or update the corresponding import declaration. The
candidate diagnoses surviving uses, removes only completed marker specifiers, keeps
unrelated imports intact, and runs packed production output without the direct
package.

## js-control-direct-cli-symlink-noop — installed npm bin exits successfully without compiling
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: c19d42401 -->

**FIXED — verified 2026-08-07.** The repair landed as `c19d42401`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `packed installed bin uses the consumer compiler and emits production-only imports`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`. The specific assertion is that `node_modules/.bin/ssc-control-tsc` — the symlink, not the real file — both emits at status 0 and fails at status 1 with `TS5023` on an invalid option, which is what the entry asked for.

**Symptom/reproduce:** invoke `ssc-control-tsc` through the normal
`node_modules/.bin` symlink. `cli.js` compares the real module `import.meta.url` to
the unresolved symlink in `process.argv[1]`; the comparison is false, `main()` is
skipped, and the process exits 0 with no output. Invoking `node <real-cli.js>` does
run, which is why the existing test missed the published path.

**Required fix/verification:** entry detection must normalize both paths through
realpath/inode-safe logic with deterministic handling of absent or unreadable
`argv[1]`. Pack and install the tarball into a fresh consumer, invoke exactly its
`.bin/ssc-control-tsc`, and prove both successful emit and non-zero failure for an
invalid compiler option.

**Root cause/fix candidate:** entry detection compared a real module URL with an
unresolved npm-bin symlink. The candidate compares deterministic realpath/filesystem
identity with explicit missing/unreadable handling; the exact packed `.bin` now
compiles and invalid options fail non-zero.

## js-control-direct-js-marker-binding-semantics — lowering erases const/let declaration behavior
<!-- status: fixed
     lane: js
     area: runtime
     gate: tests/e2e/js-control-direct-gate.sh
     fixed-in: c19d42401 -->

**FIXED — verified 2026-08-07.** The repair landed as `c19d42401`, which is reachable from
`origin/main`. What kept this entry open was not the code: the repair shipped with its own
test and NOTHING RAN IT for three weeks, and it could not run as checked out
(js-control-direct-tests-never-run). The suite is green 39/39 from a clean `npm install` and
now runs on every push touching the package, so this is guarded rather than merely believed.

**Guarded by:** `real JavaScript retains const/let declarations and fresh resume names`
in the @scalascript/control-direct suite, via `tests/e2e/js-control-direct-gate.sh`.

**Symptom/reproduce:** compile real JavaScript with `allowJs: true` and
`checkJs: false`, using `const` or `let x = direct.shift(...)`. Lowering replaces the
source declaration with a `.flatMap(x => ...)` parameter. This loses the original
declaration kind (for example, assignment to source `const x` no longer has native
runtime behavior) and can capture/collide with names introduced by the rewrite.

**Required fix/verification:** use a collision-safe fresh resume parameter and begin
the continuation suffix with the original `const`/`let` declaration initialized
from it. A real `.js` fixture must exercise both declaration kinds and a name
collision under `allowJs: true, checkJs: false`, preserving runtime behavior and
source-map ownership.

**Root cause/fix candidate:** the source binding itself became the generated callback
parameter, erasing declaration kind and weakening lexical ownership. The candidate
uses a collision-safe resume parameter followed by the original authored
declaration; real JavaScript const/let/mutation/collision tests are green.

## js-control-packed-readme-broken-spec-link — npm README links outside payload
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: cf8f96200 -->

**Status:** done in reachable `origin/main` landing `cf8f96200`; the independent
rereviewer confirmed the packed README contract link. Reported as P2 on 2026-07-15
by the second pre-integration review.

**Symptom/reproduce:** `npm pack --dry-run --json` correctly emits only the frozen
five-file payload, but its `README.md` ends with a relative
`../../../../specs/javascript-typescript-bidirectional-control.md` link. The target
is not in the tarball, so installed-package and registry README consumers receive a
broken contract link.

**Root cause:** the package README reused a repository-checkout-relative link even
though the exact npm allow-list intentionally excludes the repository spec tree.

**Fix/verification:** the README now uses the absolute canonical HTTPS source URL.
A package regression scans every Markdown destination, requires that canonical
link, and rejects an escaping or absent relative target. The exact five-entry pack
remains unchanged in shape.

## js-control-runtime-opacity-forgeable — public values leak and clone private authority
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: 1497623b5 -->

**Status:** done in `0d0ffcfd3`; confirmed closed by independent second
pre-integration review. Originally reported as P1 on 2026-07-15; affected pre-land
runtime commit `2a34d7ed3`.

**Symptom/reproduce:** returned request/prompt objects expose enumerable internal
state such as `resumption`, `key`, and `shiftOperation`; a caller can pre-claim a
one-shot resumption or intercept prompt authority. Public instances also expose
their JavaScript `.constructor`, and internal constructors accept caller values,
so expressions such as `new key.constructor(...)`, `new computation.constructor(...)`,
operation cloning, and `new prompt.constructor()` can mint objects that pass the
module's WeakSet membership checks.

**Root cause:** class constructors registered instances in public-valid WeakSets
without requiring private authority, while their state lived in ordinary own
properties. Hiding class exports therefore did not hide either state or the
constructor reached through the standard prototype chain.

**Fix/verification:** every class-backed capability now stores state in a private
WeakMap and every reachable internal constructor checks one unexported authority
token before registration. Plain-JavaScript tests prove empty authority-bearing own
keys/symbols, absent request/prompt properties, successful one-shot resume after
inspection, and rejection of constructor calls, prototype grafts, operation clones,
and forged prompts. The complete package suite passes 30/30.

## scala3-control-operation-key-snapshot — FIXED / awaiting confirmation (2026-07-14, Codex)
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: 528d73af3
     confirmed: no -->

**Status:** fixed in `528d73af3`; awaiting reporter confirmation. Found during the final
black-box review of the uncommitted `scala3ControlApi` reference model; reported
by codex-interop in the `scalascript` rozum room.

**Symptom:** `perform` retained only the user-supplied `Operation` and a handler
later called its `effect` getter again. Safe Scala can implement that getter as
`null` or mutable state. The handler then treats the malformed handled operation
as residual, removes its static row, and returns an `Eff[Nothing, ...]` whose step
is still a request.

**Reproduce:** define `Operation[Owner.type, Int]` with
`effect: EffectKey[Owner.type] = null`, call `perform`, then
`handle[Owner.type, Nothing, ...]` and pass the accepted result to `runPure`. The
draft forwards the request despite the empty result row. A getter that changes
between `perform` and `handle` has the same failure mode.

**Fix/verification:** `perform` now validates the operation, key, id, descriptor,
and multiplicity once. The private pending node and every forwarded/public step
retain the key snapshot, and handlers match it without re-running `effect`.
Regression tests cover a null key, a changing getter read exactly once, and an
operation id owned by another descriptor; the full 39-test suite is green.

## interp-boolean-operators-no-short-circuit — `&&`/`||` evaluate both operands in the interpreter
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 14d707653 -->

**Status:** FIXED 2026-07-15 (`EvalRuntime.scala`, commit `14d707653`). `Term.ApplyInfix`
for `&&`/`||` is now intercepted BEFORE the general infix case (which eagerly evaluated
the arg clause) and lowered to short-circuiting control flow: `a && b` ≡ `if a then b
else false`, `a || b` ≡ `if a then true else b`. Non-Boolean left operands (overloaded
`&&`/`||`) fall back to the general two-arg dispatch, so their behaviour is unchanged.
Because control flow is owned by the shared `EvalRuntime.eval`, the fix covers all
non-JIT tiers (tree-walk + bytecode/dispatch VM funnel through the same intercept); the
JIT already short-circuited independently via `LAnd`/`LOr`. Verified: repro below prints
`other`; full interpreter suite 1829/0.

**Original report (found 2026-07-14 while building `scljet/sql.ssc`; interpreter-only,
all tiers).** The interpreter evaluated BOTH operands of `&&` and `||`, unlike Scala and
the JS backend, so a guarded access on the right crashed when the guard was meant to
short-circuit.

**Symptom / reproduce:**
```
def test(xs: List[Int]): String =
  if xs.nonEmpty && xs.head > 0 then "pos" else "other"
println(test(Nil))
```
- interpreter (all tiers: tree-walk, bytecode VM, JavaC/ASM JIT): `head on Nil`.
- JS backend (`emit-js | node`): `other` (correct — JS short-circuits).

Idioms like `while r.nonEmpty && r.head.kind == …`, `if toks.isEmpty || toks.head…`,
`i < n && isDigit(s.charAt(i))` are now SAFE on the interpreter. (Pre-fix workarounds —
bounds-safe accessors that never touch the guarded element, e.g. `sql.ssc` `charCode`
returns -1 past end and `tkKind`/`tkIsKw` return ""/false on `Nil` — remain correct but
are no longer required for short-circuit safety.)

## coreir-spec-node-inventory-drift — frozen CoreIR spec omits canonical `While` and `Seq`
<!-- status: fixed
     lane: native
     area: front
     gate: none
     fixed-in: 7bcb6d87a -->

**FIXED 2026-07-16 by `7bcb6d87a` ("docs(coreir): reconcile the canonical contract with the …"), two
days after this was filed — and the entry was never moved off `open`.** Re-measured 2026-08-08
against the entry's own three claims:

| the entry says the spec | today |
|---|---|
| "freezes a ten-value/eleven-node kernel" | **13 nodes** pinned twice (§ header and §"Counts, pinned"), stated as 11 semantic + 2 optimization |
| "says no loop node is needed" | §invariant 7 now ends with the reconciliation: *"There **is** one — `While` (§3.1)"* |
| "explicitly says that `Seq` is dropped" | §3.1 is titled *"The two optimization nodes: `While` and `Seq`"* |

The spec carries its own drift note naming the old wording and saying the prose was reconciled **to
the code**, which is the right direction for a contract the fixpoint bytes already depend on.

**`lane:` corrected `js` → `native`.** Every artefact in this entry is `v2/specs/10-core-ir.md` and
`v2/src/CoreIR.scala`; nothing about it is the js backend, and `lane:` is what routes an entry to the
module that owns the fix.

**A probe of mine that asked the wrong question, recorded because it nearly became the verdict:** I
first checked the spec for the lowercase WIRE tags (`"app"`, `"lam"`, …) and got seven "MISSING from
spec". The spec names nodes in their abstract form (`App e₁ e₂`), so that grep was measuring my own
encoding, not the contract. The claims worth testing were the entry's three sentences, and those are
what the table above answers.

**Status:** open (found 2026-07-14, Codex, during the Scala 3 bidirectional-control
architecture audit; observed at `3ae003279`; introduced by at least `975f8dce4`).

**Symptom:** `v2/specs/10-core-ir.md` still freezes a ten-value/eleven-node kernel,
states that no loop node is needed, and explicitly says that `Seq` is dropped. The
canonical implementation and its Reader/Writer instead define, parse, and serialize
both `Term.While` and `Term.Seq`. A new portable artifact such as a saved-continuation
CoreIR capsule therefore cannot name one authoritative node inventory or format version.

**Reproduce:** on the repository source used to build the real v2 compiler/runtime, run:

```sh
rg -n "10 shapes|11 nodes|no loop node|Seq a b.*dropped" v2/specs/10-core-ir.md
rg -n "case (While|Seq)|case \"(while|seq)\"" v2/src/CoreIR.scala
git show --stat 975f8dce4 -- v2/src/CoreIR.scala v2/specs/10-core-ir.md
```

The first command shows the frozen contract excluding the nodes; the second shows the
canonical AST plus decoder/encoder accepting them; the introducing optimization commit
changed `CoreIR.scala` without changing the spec.

**Notes:** tracked as `coreir-canonical-contract-reconcile` in `SPRINT.md`. The fix must
audit `v2/specs/12-ir-format.md`, the seed, evaluator, and every backend, then either
version and specify the two canonical nodes or lower them before canonical serialization.
It must not add a continuation-specific CoreIR node. This task only records the drift;
status remains open until the dedicated reconciliation is implemented and verified.

## js-imported-def-int-division-loses-truncation — FIXED (2026-07-14, opus)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

**Status:** FIXED. Root cause was NOT the emission path — it was NAME-KEYED evidence
pollution: intVars/longVars/numericVars are keyed by param NAME and populated globally
by recordDefTypeEvidence, so bytes.ssc's many `value: Long` params leaked `value` into
longVars, and write.ssc's `writeBe32(value: Int)`'s `value / 16777216` then took the
isLongExpr `_arith('/')` path — but at runtime `value` is a plain JS Number, so `_arith`
did FLOAT division (`1/16777216`=5.96e-8) instead of `Math.trunc`, serializing bytes as
`2^-k` floats. Fix: a `withParamTypeEvidence` helper SCOPES each def's declared-param
evidence per body (sets the param's own type AND removes wrong-set membership, restores
after), applied at the genObjectAsExpr namespace-member def emission. All 6
`scljet-write-*` now IDENTICAL int/js; JS/numeric/collection/import suites 372 pass.
(genStat top-level/direct-import defs have the same latent pollution — a follow-up if
it ever manifests; the scljet chain is imported → namespace-member.)

The 6 `scljet-write-*` cases are js DIVERGE in the corpus contract: their B-tree page
serialization emits `2^-k` FLOATS (`5.96e-8`, `0.0039`) where the interpreter emits the
integer bytes (`0`, `1`). Root cause: `std/scljet/write.ssc`'s `def writeBe32(value:
Int) = List((value / 16777216) % 256, …)` uses integer division, but for THIS imported
def JsGen lowers `value / N` to the float `_arith('/', value, N)` instead of
`Math.trunc(value / N)` — because `value` isn't in `intVars` at the point the def's body
is emitted. Inline and 1-/2-level direct imports lower it correctly (`Math.trunc`), and
`registerImportedTypeEvidence` is called per imported module; but for `writeBe32`
(defined in write.ssc, pulled in TRANSITIVELY via std/scljet/index.ssc and emitted as a
`const writeBe32 = (value) => …` namespace-member const-arrow) the Int-param evidence
does not reach the emitting gen. There are 3+ def-emission paths (top-level `function`,
genObjectAsExpr `const … =>`, genStat CPS); a `withParamTypeEvidence` wrap on
genObjectAsExpr was a confirmed no-op for these, so the fix needs the exact
childGen/grandchild path that emits transitively-imported namespace-member defs to apply
the param evidence. Not a semantic bug — a type-evidence-plumbing bug in the JS import
graph. (Found via the портируем DIVERGE sweep; the contract's js column tracks it.)

## js-examples-differential-sweep-0713 — INT-vs-JS over the examples corpus: 55 PASS, 11 DIVERGE, 44 JS-FAIL
<!-- status: unknown
     lane: js
     area: front -->

Ran an INT-vs-JS differential over the top-level examples corpus (205 cases:
`ssc-tools run --v1` golden vs `run-js`). Result: 55 PASS, 11 DIVERGE, 44 JS-FAIL,
95 SKIP-INT (INT itself non-deterministic — servers/actors/async/arg-requiring;
out of scope). Fixed the clean, systemic bugs (each its own commit + conformance
case, full `backendInterpreter/test` green):

- **js-effect-multishot-in-while-loop** — FIXED. CPS-lowered `+` on a Long
  (`foldLeft(0L)` under `handle`) emitted a raw JS `+` → BigInt+Number crash.
- **js-effect-runner-preamble** — FIXED. State/Http/Cache/Retry weren't in the
  JsGen effect builtin seed → not CPS-lowered (`runState` reported the initial
  state) + preamble missing; effect-runner tuples rendered as `List(…)`.
- **js-namespace-dup-const-serve** — FIXED. Overloaded externs sharing a JS name
  (std/http `serve`/…) emitted duplicate `const` → parse-time SyntaxError.
- **js-symbolic-infix-op** — FIXED. Symbolic user operators (`~`, `~>`, `<~`, `++`)
  named the extension fn `_ext_T_<sym>` and emitted the infix use as a raw JS
  operator → SyntaxError; now mangled + dispatched via the extension registry.
- **js-parser-choice-pipe-bitwise** — FIXED (see below). `p | q` bitwise mis-lowering
  + missing `String.matchPrefix`.
- **js-http-config-namespace-tdz** — FIXED (see below). `httpTimeout`/`httpRetry`
  namespace member resolved to `undefined`.
- **js-algebraic-effects-residual** — FIXED (see below). Stream.complete + logger
  pair render + user `Logger.log`.
- **js-wildcard-destructure-dup** — FIXED. A `_` wildcard in `val (x, _) = …` was
  emitted as the literal JS identifier `_`, so two such bindings in one scope threw
  "Identifier '_' has already been declared". Each wildcard now gets a fresh unique
  throwaway name. (Found while writing the Stream conformance case.)
- **js-generator-next-option** — FIXED. A pull-based `generator[T] { }`'s `next()`
  returned a bespoke `{_isSome,_value}`/null shape (rendered `[object Object]`/`null`
  by `_show`, and unmatchable by `case Some(v)`); now returns a real `Option`. The
  one dependent site (the `Source.fromGenerator` async-stream bridge) was updated.
- **js-long-param-evidence** — FIXED (found via lang-split). JsGen recorded Long
  param/return evidence with `decltpe.contains(Type.Name("Long"))`, which NEVER
  matched a parsed type — scalameta tree equality includes source position, so a
  freshly-built `Type.Name("Long")` never equals the parsed one. Long params/returns
  thus never reached `longVars`/`longFunctions`, so `a + b` (Long params), `f() + 1`
  (Long return), and Long-accumulator TCO recursion emitted a raw JS `+` and threw
  "Cannot mix BigInt and other types" whenever an Int (Number) met a Long (BigInt).
  Fixed both sites (recordDefTypeEvidence + rebindNumericEvidence) to pattern-match.
  Systemic — affects every Long param/return on JS, not just lang-split.
- **js-scala-fence-emit** — FIXED (found via lang-split). The interpreter and JVM
  backend run every `Lang.isParseable` block (`scalascript` OR plain `scala`), but
  JsGen collected only `isScalaScript` blocks, so a ` ```scala ` fence ran on
  INT/JVM but was silently dropped on JS (its output vanished). Switched all 25
  block-filter sites across JsGen/JsGenContentEmit/TreeShaker to `isParseable`,
  matching the other backends. lang-split.ssc now matches INT end-to-end.

The above `js-parser-choice-pipe-bitwise`, `js-http-config-namespace-tdz`, and
`js-algebraic-effects-residual` were also all FIXED (details in their own bullets
above). **Twelve JS bugs fixed total** this sweep, each with a conformance case and a
green full `backendInterpreter/test`.

**Remaining (NOT bugs) — the rest of the 43 JS-FAIL / 4 DIVERGE are one of:**

- **Non-deterministic / environment-dependent** (correctly diverge): `uuid-v7` (random
  UUIDs), `os-env` (platform/cwd/native).
- **Code-GENERATION examples** whose `--v1` "output" is emitted source, not a program
  result: `indexeddb-drafts`, `dsl-ast-builder` (pretty-printer), `typed-object-codec`,
  `graph-codecs`.
- **Large feature gaps — entire subsystems not implemented on the JS/Node lane by
  design** (each is feature work, not a bug): Spark (`spark-*`, `word-count`), JDBC/SQL
  (`object-store-jdbc`, `sql-h2-quickstart`, `typed-sql-crud`, `v2-http-sql-demo`), PDF
  render (`htmlToPdfBase64` — invoice/pdf), native crypto (`aesGenKey`/`verifyEd25519`/
  `totp`), `Dataset`/`DatasetCodec` + distributed-dataset, `Graph`/graph-storage,
  quoted macros (`__ssc_macro__`), `oauth`, MCP servers + agents (`mcp-*`, `rozum-*` —
  "not callable"), scljet VFS (`scljet-*`), scala.js APIs (`scala-js-demo`), actor
  remote routing (`_routes`), NFC (`nfc-ndef`), browser UI signals (`fetchUrlSignal`),
  `Sync`, `ConfigBlockInlineYAML`, and `javascript`-fence `${}` interpolation
  (`js-glue-component`). These fail with a clear `ReferenceError`/`not callable` for the
  missing capability rather than producing wrong output.

## standard-tier-named-arg-skip-default — `bin/ssc run` (self-hosted standard-tier pipeline) mis-binds a named arg for a non-first trailing defaulted param
<!-- status: fixed
     lane: js
     area: front
     fixed-in: b61a78b24 -->

**Re-verified 2026-08-02 at `1305736e1`** on the entry's own signature
(`def f(a, b = "B0", c = "C0", d = "D0")`), all three skip shapes, both lanes:

```
f("x", b = "B1") -> x|B1|C0|D0      f("x", c = "C1") -> x|B0|C1|D0      f("x", d = "D1") -> x|B0|C0|D1
```

`bin/ssc run` and `ssc-tools run --v1` agree line for line. `b61a78b24` resolves and is an
ancestor of `origin/main`.

**Status: FIXED 2026-07-28** in `b61a78b24` (`f-named-arg-skips-default`), on BOTH self-hosted fronts.

**⚠️ TWO CORRECTIONS to the original report, both measured before any edit.**

1. **"Naming defaulted params in order starting from the first one overridden works fine" is no
   longer true** — `f("x", b = "B1")` fails identically. EVERY named-arg call to a defaulted
   parameter is affected. Only all-positional and all-defaults calls work.
2. **The default lane no longer fails silently — it fails LOUD**, and the silent version moved to
   the legacy front. Re-measured 2026-07-28 on `def f(a: String, b: String = "B0",
   c: String = "C0", d: String = "D0")`:

| lane | `f("x", c = "C1")` |
|---|---|
| `bin/ssc run` (default, F front) | `ssc: arity: 4 expected, 2 given` — LOUD; correct programs do not run |
| `SSC_FRONT=legacy bin/ssc run` (ssc1 oracle) | `b=C1 c=C0 d=D0` — SILENT wrong slot (the filed symptom) |
| `bin/ssc-tools run --v1` (reference) | `b=B0 c=C1 d=D0` |
| `bin/ssc-tools emit-js` + node | `b=B0 c=C1 d=D0` |

**Root cause — two halves of one missing feature: bind named args by NAME, then fill the rest
from defaults.**

- **F** (`specs/v2.2-p6.5-fsub.ssc`): `dfltGo2` bails on `anyNamedSlice`, so default synthesis
  never fires for a call carrying a named arg, and `parseArgExpr` then STRIPS the label and lowers
  the value positionally → a short application. Fixed with a separate `nargGo` decision +
  `assignSlots` (one slot per param: its named slice, else the next unconsumed positional, else
  EMPTY) wired into `parseCallS`, `parseCtorGenS` and the object-method path. `synthW1` reads an
  empty slot as "evaluate this param's default" — that is what lets a MIDDLE param be skipped.
  `dfltGo` is left alone on purpose: the enum-case path consumes a positional COUNT
  (`dropL(nprov, …)`) that a reordered call would falsify.
- **Oracle** (`v2/lib/ssc1-lower.ssc0`): `expandDefaultCall` bailed on any `narg`, and the label
  was then dropped by `stripNargs` ("best-effort positional lowering"), so the value landed in the
  first omitted slot. Case-class construction already had the correct shape (`reorderByFieldsD`);
  plain defs did not. Fixed with `expandNamedDefaultCall` + `nargBindings`. `O.m(…)` is still a
  `sel` at that point — the callee is not mangled to `O_m` until `resolveCallee`, which runs
  later — so the named branch derives the mangled name itself (`selDfltName`), which is the key
  the defaults registry mirrors object members under.

**Fixing only F was not an option:** the fsub differential gate asserts F is output-equivalent to
the oracle, so a one-sided fix converts a two-sided wrong answer into a reported DIVERGE.

**Gates.** `specs/v2.2-p6.5-fsub.sh --self`: 161 ok / 0 FAIL, X1 FIXPOINT stage1 == stage2
byte-identical (416071 B). Nine new differential cases — middle / last / **first** / two /
**out-of-order** / ctor / object-method / no-named / fully-positional — all `output == oracle`.
`specs/v2.2-p6.5-semantic.sh check` 247/247 against the frozen goldens. New cross-lane case
`tests/conformance/named-arg-defaults.ssc`, rostered with the baseline digest reproduced first.

**Found while verifying, and filed separately:** the v1 interpreter mis-binds named args on OBJECT
METHODS — see `v1-interp-object-method-named-arg-wrong-slot`. Both compilers are now correct there
and the *reference* is wrong, which is why that shape is deliberately absent from the cross-lane
conformance case (the `int` golden would be the thing failing) and is covered against the
self-hosted oracle instead.

### Original report (kept for context)

**Status:** open (found 2026-07-13, claude-sonnet-5, while building `std-ui-select`
(`specs/std-ui-select.md`, on top of `a0eb3b984`)). Not fixed in this task — out of
scope for a std/ui widget slice (this is a compiler/argument-binding bug in a
different, actively-developed pipeline, not UI-specific), and risky/cross-cutting to
touch opportunistically. Flagging for a dedicated follow-up.

**Symptom:** for a function/constructor with 2+ **trailing defaulted** parameters,
calling it via `bin/ssc run` (the "ScalaScript 2.1 standard tier" — self-hosted
frontend/checker + v2 VM/ASM; this is the default when no compat flag is given, and
`bin/ssc run --v2` reproduces it too) with a single named argument that is **not**
the first defaulted parameter silently binds the value to the **first** defaulted
parameter instead — no error, wrong value, wrong slot. Naming defaulted params **in
order starting from the first one overridden** works fine; so does an all-positional
call.

**Important scope correction (verified after initial filing):** this is *not* the
old v1 tree-walking interpreter, and *not* what this repo's own test/conformance
harness exercises. Four lanes tested on the identical repro:

| Command | Result |
|---|---|
| `bin/ssc run <file>` (default) | **WRONG** |
| `bin/ssc run --v2 <file>` | **WRONG** |
| `bin/ssc-tools run <file>` (v1 — matches `StdUiSmokeTest.scala`'s direct `Interpreter`/`Parser` use and the conformance `int` lane) | correct |
| `bin/ssc-tools run --v2 <file>` (older v2-VM-bridge compat mode) | correct |
| `bin/ssc-tools emit-js <file>` + `node` (JsGen — the `js` conformance lane) | correct |

So this only affects `bin/ssc`'s standalone "standard tier" binary specifically — the
newest self-hosted pipeline (the same one under heavy concurrent development in this
repo right now, e.g. the `v2.2-p6.2*` match/patterns/case-class/typeclass spikes
landing the same day this was found). It does **not** affect `tests/conformance/run.sh`
(uses `ssc-tools`), `StdUiSmokeTest.scala` (uses the v1 `Interpreter` directly), or the
`js`/`emit-spa` production path — so `std-ui-select` itself is unaffected by this for
every path this repo actually verifies through. It *may* matter for a downstream
consumer whose own wrapper script invokes `bin/ssc run`/`--v2` directly (check what
your `--v2` flag actually dispatches to before assuming safety).

**Repro** (after `scripts/sbtc "installBin"`):

```scalascript
def f(a: String, b: String = "B0", c: String = "C0", d: String = "D0"): String =
  s"b=$b c=$c d=$d"

println(f("x", c = "C1"))           // bin/ssc run:        "b=C1 c=C0 d=D0"  (WRONG: c's value bound to b)
                                     // bin/ssc-tools run:  "b=B0 c=C1 d=D0"  (correct)
println(f("x", d = "D1"))           // bin/ssc run:        "b=D1 c=C0 d=D0"  (WRONG)
println(f("x", c = "C1", d = "D1")) // bin/ssc run:        "b=C1 c=D1 d=D0"  (WRONG on both)
```

Save as a `.ssc` file and diff `bin/ssc run <file>` against `bin/ssc-tools run <file>`
(or `bin/ssc-tools emit-js <file> | node`) to see the divergence directly. The pattern
holds regardless of parameter count (reproduced with 3 and 4 total params, 1 required
+ up to 3 defaulted).

**Hypothesis (unconfirmed — not root-caused):** the standard-tier pipeline's
call-argument resolver appears to treat each named argument as "the next unfilled
positional slot, in call-site order" rather than matching by parameter name once any
earlier default is skipped — i.e. named-arg counting degrades to positional counting
as soon as a defaulted param is skipped by name. Likely lives in the self-hosted
frontend/checker's call-binding logic (`v2/` — not investigated further; note this is
a *different* codebase area from `v1/runtime/backend/interpreter`, which was ruled
out by the `bin/ssc-tools run` result above).

**Why it wasn't caught before:** grepping the existing `.ssc` corpus (examples/,
runtime/std/) for the trigger shape (a named arg for a non-first trailing default,
skipping an earlier one) found no existing call sites — every existing multi-default
call either passes all args positionally or names them in left-to-right order. `select`
(`runtime/std/ui/input.ssc`) is the first primitive whose natural call shape
(`select(options, selected, disabled = true)`, skipping `label`/`placeholder`) exercises
the bug, so it was worked around there (see `specs/std-ui-select.md`) rather than
relied upon: examples/docs for `select` always name every trailing param they touch,
starting from the first one overridden.

**Impact:** silent wrong-value binding (not a crash) in any `.ssc` program run via
`bin/ssc run` (or `--v2`) specifically, that skips a middle defaulted parameter by
name. Does not affect `bin/ssc-tools run` (v1), `bin/ssc-tools run --v2`, or code
compiled via `emit-js`/`emit-spa` — i.e. not this repo's own conformance/test harness,
and not (as far as verified) busi's production build path.

**Suggested regression test once fixed:** a small conformance case (e.g.
`tests/conformance/standard-tier-named-arg-skip-default.ssc`) plus a `bin/ssc run`
smoke assertion, asserting the repro above matches `bin/ssc-tools run`'s output.

## js-effect-multishot-in-while-loop — multi-shot effect handled inside a `while` crashes on JS
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-13, opus). Root cause was NOT the `while` and NOT multi-shot
itself — it was the **Long accumulator** in the fold `total = total + all.foldLeft(0L)((acc,
x) => acc + x.toLong)`. Because the fold runs under a `handle` block, its body is lowered by
the JsGen **CPS** codegen (`JsGenCpsCodegen.genCpsExpr`), whose `ApplyInfix` handler emitted
arithmetic via a **raw** JS operator (`case other => ($vl $other $vr)`) — unlike the non-CPS
`ApplyInfix` path (`JsGen.scala`), which routes any not-provably-Int operand through the
BigInt/Decimal-aware `_arith`. A `0L` seed is a JS **BigInt** and the list elements are plain
**Numbers**, so the emitted `(acc + _t)` threw `TypeError: Cannot mix BigInt and other types`
at runtime. Minimized: the crash reproduces with NO `while` at all — any `foldLeft(0L)` over a
multi-shot result (or any effectful Long arithmetic) triggered it; a plain `foldLeft(0)` (Int)
was fine. Fix: the CPS `ApplyInfix` handler now mirrors the non-CPS path — arithmetic/
comparison route through `_arith` (and bit-ops through `_bit`) whenever operands are not
provably both plain Int, using the original `lhs`/`rhs` terms for the `isLongExpr`/`isIntExpr`/
`isNumericExpr` checks (both-Int keeps the fast raw operator). Verified: `JsEffectLoopTest`
8/8 (the multi-shot-in-while case now 204); a seed×toLong matrix (0L/0, ±toLong) all → 102 on
both `run-js` and `run --v1`; new conformance `js-effect-multishot-long-fold` [int, js] → 204;
full `backendInterpreter/test` **1828 pass, 0 failed** (was 1827 pass + this 1 fail).

## js-caseclass-body-method-params-dropped — JS drops case-class body methods that take parameters
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 8204d588a -->

**Status:** FIXED (2026-07-13, opus). `8204d588a`.

**Symptom:** a `case class C(...) extends SomeTrait:` whose body defines trait
methods *with parameters* — e.g. `scljet-readonly-pager-btree.ssc`'s
`FixtureVfs.fullPath(path: String)` — throws on `run-js`:
`Method not found: fullPath on FixtureVfs`. Only the class's **zero-parameter**
body methods (`name`, `currentTimeMillis`) were emitted; every method with a
parameter clause was silently dropped.

**Root cause:** `JsGen.scala`'s `Defn.Class` case registered body methods via
`_registerExt('m', (_self) => …, 'Type')` under a guard that only matched
methods whose (non-implicit) parameter clauses were empty
(`…flatMap(_.values).isEmpty`); parameterized methods fell to `case _ => ()`.
`_dispatch` finds a case class's trait methods through
`_extensions['Type:method']`, so a dropped method is unreachable.

**Fix:** register body methods with ≤1 (non-implicit) parameter clause as
`_registerExt('m', (_self, p1, p2, …) => { const {fields} = _self; return body; }, 'Type')`.
Reserved-word params (`delete`) are escaped via `safeJsParam`/`paramRenameMap`
+ `withParamRenames` in both the lambda header and the body; a param that
shadows a field is not re-destructured (a `const` redeclaration of a lambda
param is a JS syntax error). Curried body methods (>1 clause) stay unregistered
(their calling convention would not match `_dispatch`'s flat args). Zero-param
emission is byte-identical to before. Verified: `scljet-readonly-pager-btree`
passes `[JS]` (scljet now 6/6 on JS); JsGen suite 242/242; full conformance
`--no-memo` 195/195 (exit 0).

**Repro:** `bin/ssc-tools emit-js tests/conformance/scljet-readonly-pager-btree.ssc`
then run under node — pre-fix it threw at the first `_dispatch(vfs, 'fullPath', …)`.

## js-char-int-eq-namescope-collision — Char `==` Int miscompiles to strict `===` on JS
<!-- status: fixed
     lane: js
     area: front
     fixed-in: d034e2798 -->

**Status:** FIXED (2026-07-13, opus); found by opus via a full conformance sweep
(`json-deep-import` FAIL [JS]). Latent pre-existing JsGen bug, EXPOSED for std/json
by the `registerImportedTypeEvidence` fix (`d034e2798`) extending it to imported
modules. Root cause: `intVars`/`numericVars` are name-keyed and module-global, so a
`Char`-valued local `val c = s.charAt(i)` inherits the "numeric" evidence of an
Int param `c` from a SIBLING function (e.g. `def jsonCoreIsDigit(c: Int)` next to
`jsonCoreParseValue`'s `val c`). The `==` numeric fast path (`JsGen.scala:~4715`)
then emits `c === 34` — but JS `charAt` returns a boxed `_char`, and `===` never
calls `valueOf`, so `_char === 34` is ALWAYS false (while `==`/`<` work via valueOf).
Net effect: on JS the self-hosted json-core parser rejected every string/array/object
(`unexpected token @0`), so `jsonValue`/`jsonParse`/`jsonRead` returned null/empty.
Fix: `rebindNumericEvidence(name, rhs)` — a local `val`/`var` binding is now
AUTHORITATIVE for its name, setting intVars/numericVars to match its RHS and REMOVING
same-named leaked param evidence (applied at all 4 val/var binding sites in
`genStat`/`genStatInline`). Verified: entry-module repro (`parse("\"x") → 1` not 99),
`json-deep-import` green on INT/JS/V2, json roundtrips exact; JsGen 213/213; the
`imported-int-division` fix still holds (its `absolute = bytes.start + index` RHS is
numeric, so it stays in intVars).

## v1-js-scljet-readonly-leaf-depth — valid two-level B-tree fails common-depth validation
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded
     gate: tests/conformance/scljet-readonly-btree-pure.ssc -->

**Status:** FIXED (2026-07-12, opus). SHARED root cause with
v1-js-scljet-shm-lock-divergence: a field-less `case object` lowered to a bare
`{}` with no `_type` discriminator (`genObjectAsExpr`, JsGen.scala), so the
user-level `==` operator — structural `_eq` — found two empty records with
matching (undefined) `_type` equal, making EVERY field-less case object `==`
every other. Here `read.page.header.kind == TableLeafPage` was wrongly `true`
for the interior root, so it was misclassified as a leaf and
`cursorCheckLeafDepth` recorded `leafDepth=Some(1)`; the real first leaf at
depth 2 then failed the common-depth check. Fix: `genObjectAsExpr` now emits
`{_type: 'Name'[, _tag: N]}` for `case object`s (guarded on `Mod.Case`, so
namespace/companion objects are untouched) — additive, since pattern matching
already keys on `._type`. Guarded by a JsGenStdImportTest case; JsGen 213/213.
_Original report:_ found by codex during the SclJet M2c explicit
JavaScript capability probe.

- **Real-harness repro:** run `bin/ssc-tools run-js
  tests/conformance/scljet-readonly-btree-pure.ssc`. Interpreter, native VM,
  and direct ASM traverse table rows `1,9` and index records `1,5,9`; Node
  instead throws `B-tree leaves do not have a common depth` while advancing
  the same cached two-level table.
- **Boundary/hypothesis:** all page bytes, cursor transitions, and expected
  output are host-free `.ssc`; this is a JS lowering/runtime divergence around
  immutable cursor stack length or `Option[Int]` state, not malformed SQLite
  input. It is independent of the already tracked Long/bitwise and SHM gaps.
- **Done-when:** reduce the first/second leaf transition, fix the JS backend so
  `leafDepth` remains stable across sibling pages, and make the complete
  three-line pure cursor golden exact on interpreter/VM/ASM/Node.

## v2-js-imported-method-object-primitive — SclJet stops at __mk_method_obj__
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded
     gate: tests/conformance/scljet-byte-codec.ssc -->

**Status:** FIXED (2026-07-12, opus). The v2 JS backend (`v2/backend/js/JsBackend.scala`)
had no `genPrim` case for the `__mk_method_obj__` CoreIR primitive (emitted by
FrontendBridge for an imported explicit companion / `object Foo { def m … }` /
`given … with {…}`), so it fell to the `$prim` fallback → `throw unimplemented
primitive`. And because it lowers to an eager top-level initializer, Node threw at
module load. Fix mirrors the v2 NATIVE runtime (Runtime.scala:2222/2054): added a
`__mk_method_obj__` genPrim case → `$mkMethodObj([...])` (flat name/lambda pairs →
`{$mo:{name→fn}}`), plus a method-object dispatch branch in the `$method` runtime
(look up name; call the fn with args, or return it as a reference when arity>0 and
no args). Verified via a multi-file imported companion repro: `run-js --v2` now
prints `0/5/8` matching `run --v1`. Regression: V2JsLaneCliTest "dispatches an
imported explicit companion". (`scljet-byte-codec.ssc` gets past this primitive but
then hits separate pre-existing gaps — `$method` List `.drop` + the tracked
v1-js-long-precision-and-bitops — which are out of scope for this bug.)
- **Real-harness repro:** run `bin/ssc-tools run-js --v2
  tests/conformance/scljet-byte-codec.ssc`; Node exited at startup with
  `unimplemented primitive: __mk_method_obj__`.

## v1-js-scljet-shm-lock-divergence — two shared owners are rejected
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded
     gate: tests/conformance/scljet-memory-vfs.ssc -->

**Status:** FIXED (2026-07-12, opus). SHARED root cause + fix with
v1-js-scljet-readonly-leaf-depth (field-less `case object` → bare `{}` → all
`==` equal under structural `_eq`; `genObjectAsExpr` now emits a `_type` tag).
Here `mode == ShmExclusiveLock` was wrongly `true` for a `ShmSharedLock` acquire,
so `exclusive` became true for a shared request → the availability check took the
exclusive branch and rejected the 2nd shared lock (line 18), which then left the
later exclusive request unblocked (line 19). Both restored to the INT golden;
full 33-line memory-VFS output identical to `run --v1`. See that entry for detail.
_Original report:_ found by codex in the SclJet memory-VFS Node
differential after byte updates became portable.

- **Real-harness repro:** compare `bin/ssc-tools run --v1` and `run-js` for
  `tests/conformance/scljet-memory-vfs.ssc`. Lines 18–19 differ: the JS lane
  reports the second shared SHM lock as failed and consequently does not report
  the conflicting exclusive request as busy; the remaining 31 lines match.
- **Root cause:** pending reduction in JsGen object/Option/equality lowering for
  `MemoryShmByteLock`; the pure transition algorithm is exact on INT/VM/ASM.
- **Done-when:** a reduced two-handle SHM lock test and the complete 33-line
  memory-VFS golden are exact on Node.

## v1-js-long-precision-and-bitops — SQLite 64-bit codecs are not exact
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-13, opus). Approach A (Long-only): represent ssc `Long`
as a JS **BigInt** (`Lit.Long` → `${v}n`) — the SclJet codecs type every 64-bit value
`Long`, so Int can stay a JS number (far smaller blast radius than making all Int
BigInt). Added a `longVars`/`isLongExpr`/`longFunctions` track (Long params/returns,
Long-typed val/var bindings via `rebindNumericEvidence`, `.toLong`, bit-op/arith
results, Long case-class fields); a dedicated infix case for `& | ^ << >> >>>` →
`_bit('op', a, b)` (BigInt with `asIntN(64,…)` masking, `>>>` via `asUintN`); a
Long-guarded infix case routing any Long-operand arithmetic/compare through `_arith`
(so an Int operand is coerced, not mixed BigInt+Number); and `.toInt` → `_toI32(x)`
(BigInt-safe). Verified through the real CLI: `1L<<40`=1099511627776, `255<<24`=
4278190080, `0x…L & 0xffL`=240 (INT==JS); `scljet-byte-codec` + `scljet-page-record-codec`
JS lanes exact vs golden (conformance now `[int, js]`). JsGen 248/248 (2 perf-test
assertions updated to the new emission). Original scoping below is superseded.
<details><summary>original scoping (superseded)</summary>
ROOT CAUSE: JsGen emits ssc `Int`/`Long` as JS **`number`**, not BigInt
(`Lit.Int`/`Lit.Long` → `v.toString`, JsGen.scala:~3800; no `longVars` set, no
Long→BigInt path). So (a) any 64-bit value above 2^53 loses precision at JS parse
time, and (b) the bitwise/shift operators `& | ^ << >> >>>` have no dedicated infix
case — they fall through to the generic `($lhs $op $rhs)`, i.e. raw JS ops that are
32-bit (ToInt32/ToUint32) and mask shift counts mod 32. Measured JS vs interp:
`1L<<40` → 256 (want 1099511627776); `255<<24` → -16777216 (want 4278190080);
`0x…L & 0xffL` → 0 (want 240). The runtime has exact BigInt paths (`_arith`/`_dispatch`
bigint branches) but they only fire when an operand is already a BigInt, which
Int/Long lowering never produces.
WHY DEFERRED: ssc `Int` is itself 64-bit (interp: `255<<24 == 4278190080`), so the
correct fix is to represent Int/Long as JS **BigInt** (`${v}n`) and mask 64-bit ops
with `BigInt.asIntN(64,…)`/`asUintN(64,…)` — a backend-wide representation change
with large blast radius (perf + every numeric codepath) and a genuine perf tradeoff.
A bitwise-only patch would NOT close the done-when (SQLite codecs need exact 64-bit
*literals + arithmetic*, not just bit ops). Needs a dedicated design decision, not a
drive-by fix. _Original report:_ found by codex in the SclJet byte-codec Node
differential.
</details>

- **Real-harness repro:** `bin/ssc-tools run-js
  tests/conformance/scljet-byte-codec.ssc` now executes all 31 lines, but
  `readU64Be(0x123456789abcdef0)` becomes `-1698898192`, signed 32-bit decode
  yields `-4294967298`, and the 11-value varint round trip is `false`.
- **Root cause:** v1 JsGen represents source `Long` literals/accumulators as JS
  `Number` and emits native 32-bit bitwise operators; the runtime supports
  BigInt arithmetic but typed `Long` lowering does not consistently use it.
- **M2 codec impact:** the 35-line `scljet-page-record-codec` golden matches
  34/35 lines on Node. Header/page/cell/overflow/serial/UTF behavior is exact;
  reconstructing the binary64 bits for `1.5` yields `0` because the u64/shift
  path has already lost the bit pattern.
- **Done-when:** `Long` arithmetic, shifts and bitwise operations use exact
  signed 64-bit semantics and both the 31-line M1 and 35-line M2 codec goldens
  are identical on Node.

## v1-js-imported-int-division-loses-type — byte chunk index becomes fractional
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

**Status:** FIXED (2026-07-12, opus). Root cause: JsGen only emits truncating
integer division (`Math.trunc(a / b)`) when it can statically prove both operands
are Int — evidence held in `intVars`/`instanceVars`/`caseClassFieldTypeMap`,
populated by `genModule`'s pre-pass (`collectFuncParamOrders`). Imported bodies
are emitted by a fresh `childGen` via `genScalaNode`, which BYPASSES that pre-pass,
so an imported `def rawGet(bytes: ByteSlice, index: Int)` had empty type evidence:
`bytes.start` degraded to `_dispatch`, `absolute` never entered `intVars`, and `/`
fell through to floating `_arith('/')` → `131/64 = 2.046875` → Map key miss → `()`.
Fix: extracted the per-def type-evidence into `recordDefTypeEvidence`, added
`registerImportedTypeEvidence(module)` (populates intVars/instanceVars/intFunctions
+ caseClassFieldTypeMap/caseClassFieldsByType, descending into namespace objects),
and call `childGen.registerImportedTypeEvidence(childModule)` in `genImport` before
emitting imported bodies; nested imports recurse. Guarded by `examples/js-imported-int-div`
+ a JsGenStdImportTest case (== "2"). Full JsGen suite 212/212 green.
_Original report:_ found by codex in the SclJet Node golden after
companion and list-pattern lowering were repaired.

- **Real-harness repro:** `bin/ssc-tools run-js examples/scljet-bytes.ssc`
  prints `List(18, (), (), ())` for four input bytes. Emitted `rawGet` computes
  `chunk = absolute / 64`; for indices 1–3 JsGen uses JavaScript floating
  division, asks the persistent Map for keys `1/64`, `2/64`, `3/64`, and gets
  no chunk.
- **Root cause (partial):** imported-function local `val absolute` loses the
  inferred `Int` fact even though it is derived from `Int` fields/parameters,
  so `/` bypasses the integer-division lowering.
- **Current SclJet workaround:** compute the chunk as
  `(absolute - (absolute % 64)) / 64`; the numerator is exactly divisible on
  every backend and preserves the specified floor division for non-negative
  byte indices.
- **Done-when:** imported local integer arithmetic retains type evidence and a
  focused JS regression proves positive and negative `Int / Int` truncation
  without source-level rewrites.

## v1-js-list-pattern-array-mismatch — Cons/Nil reject emitted List literals
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 830c0db27
     confirmed: no
     gate: tests/conformance/scljet-byte-codec.ssc -->

**Status:** fixed (2026-07-12, `830c0db27`), awaiting Sergiy confirmation;
found by codex after the SclJet explicit-companion JS fix allowed the byte-codec
program to begin execution.

- **Real-harness repro:** run `bin/ssc-tools run-js
  tests/conformance/scljet-byte-codec.ssc`. `List(0, 1, 127, 128, 255)` is a
  JavaScript array, while `validateBytes` enters a `values match` with
  `case Nil` / `case Cons(value, tail)` and falls through to `Match failure`.
- **Root cause:** `genPattern` emitted case-class `_type` checks for `Cons` and
  the singleton check for `Nil`, but JsGen represents ordinary ScalaScript
  lists as native arrays. Pattern lowering did not recognize the backend's own
  list representation.
- **Fix/result:** `genPattern` now handles the native-array and legacy data
  representations of recursive `Nil`/`Cons`; focused Node coverage passes and
  both SclJet programs execute past all list matches. Remaining Long/SHM output
  differences are tracked independently above.

## v1-js-case-companion-duplicate-declaration — imported companion redeclares ByteSlice
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 830c0db27
     confirmed: no
     gate: tests/conformance/scljet-byte-codec.ssc -->

**Status:** fixed (2026-07-12, `830c0db27`), awaiting Sergiy confirmation;
found by codex while running the SclJet M1 cross-backend verification.

- **Real-harness repro:** after `scripts/sbtc "reload; installBin"`, run
  `bin/ssc-tools run-js tests/conformance/scljet-byte-codec.ssc`. The generated
  CommonJS file is rejected by Node with `SyntaxError: Identifier 'ByteSlice'
  has already been declared`; the case imports SclJet through
  `std/scljet/index.ssc`, which re-exports definitions from `bytes.ssc`.
- **Root cause:** inside a `package:` namespace IIFE, `Defn.Class` emitted
  `function ByteSlice(...)` while its explicit `Defn.Object` companion emitted
  `const ByteSlice = ...` in the same scope. The JS backend did not merge the
  companion's static members onto the constructor binding. INT/native VM/direct
  ASM represent the two Scala namespaces separately and therefore succeeded.
- **Fix/result:** JsGen merges explicit companion members onto the constructor
  with `Object.assign` at top level and inside package IIFEs. The multi-file
  package import regression executes on Node and SclJet no longer emits a
  duplicate `ByteSlice` binding.

## v21-scljet-readonly-unclassified-lane — new read-only JVM example became both-fail
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 2b61b6611
     confirmed: no -->

**Status:** fixed (2026-07-12, `2b61b6611`), awaiting Sergiy confirmation;
found by codex when `examples/scljet-readonly.ssc` landed during the final
199-row release gate.

- **Real-harness repro:** run strict `scripts/bc-parity-sweep` after the example
  lands. Its declared `ssc-tools run --v1` JVM VFS operation cannot execute in
  compiler-free standard v2 VM or ASM, so an unregistered row is `both-fail`.
- **Root cause:** the M2c example landed after the exact 14-row census while its
  real filesystem smoke existed outside the v2.1 explicit-lane manifest.
- **Fix/result:** the existing assembled `ssc-tools --v1` smoke is wrapped by an
  exact target-lane regression, including exact schema/row output, close, and
  file deletion. The freeze is 200 rows / 15 delegated (8 provider / 7 target),
  with zero ordinary and negative parity gaps; SclJet conformance is 6/6.

## uniml-yaml-property-only-node — anchor/tag-only value lost its nested node
<!-- status: fixed
     lane: js
     area: front
     fixed-in: c9f599589
     confirmed: no -->

**Status:** fixed (2026-07-12, `c9f599589`), awaiting Sergiy confirmation;
found by codex while extending the UniML YAML alias-cycle verification after
`48720429c`.

- **Real-harness repro:** parse and project `root: &root\n  child: value\n` through
  `Yaml.parse` followed by `Yaml.project`. The projected `root` value is an
  anchored empty scalar instead of an anchored nested mapping.
- **Root cause:** `parseAfterIndicator` sent every non-empty post-colon spelling
  directly to `parseInline`; after consuming `&root`, the empty remainder was
  resolved as a scalar without consulting the following indented node.
- **Done-when:** property-only mapping and sequence values attach their tag/anchor
  to the following nested node, self/mutual alias cycles stay preservable, and
  bounded `Resolve` reports cycles/expansion limits on JVM and Scala.js.
- **Fix/result:** property-only values now parse the following indented node and
  apply their validated properties to it. The focused suite is 14/14 on both JVM
  and Scala.js, including nested mapping/sequence anchors, mutual cycles, and both
  alias-expansion budgets.

## js-ssc-ui-jsonvalue-duplicate — two `_ssc_ui_jsonValue` in the assembled JS runtime
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 1ecbc80ca -->

**Status:** FIXED (2026-07-12, opus). Root cause: `1ecbc80ca` (2026-07-11 17:53)
added a 3-arg row-validator `_ssc_ui_jsonValue(value, operation, seen)` to
`signals.mjs`, coincidentally reusing the name of the canonical 1-arg `jsonValue`
intrinsic impl `_ssc_ui_jsonValue(s)` in `core-collections.mjs` (from `46571d5f8`,
17h earlier). Any Signals+json bundle (`Capability.all`) had both top-level defs, so
`JsGenStreamsTest` "no duplicate top-level function declarations" was RED — and JS
function-hoisting silently let the 3-arg version win, breaking the `jsonValue`
intrinsic. Fix: renamed the intruder (the internal 3-arg validator, only ever called
from within signals.mjs) to `_ssc_ui_rowJsonValue` at all 5 sites, keeping the
load-bearing `_ssc_ui_jsonValue` name for the extern-bound 1-arg intrinsic. Verified:
`JsGenStreamsTest` + `JsGenStdImportTest` = 87/87 green.
_Original report:_ found by opus while running `backendInterpreter/test`
for an unrelated interp fix (v1-args-native-method-gap). Pre-existing on origin/main,
independent of that fix (which is Scala-only).

- **Repro:** `sbt backendInterpreter/testOnly scalascript.JsGenStreamsTest` →
  "full runtime has no duplicate top-level function declarations *** FAILED ***
  List(\"_ssc_ui_jsonValue\") ... duplicate top-level functions: _ssc_ui_jsonValue".
  1799/1800 of the full interp suite otherwise pass.
- **Root cause:** two DIFFERENT functions share the name across two concatenated
  runtime files:
  - `core-collections.mjs:702` `function _ssc_ui_jsonValue(s)` — parses a JSON
    STRING (`_jsonConvert(JSON.parse(s))`), added by `46571d5f8`
    (fix(js): implement __jsonCore* natives).
  - `signals.mjs:1595` `function _ssc_ui_jsonValue(value, operation, seen)` — the
    recursive value validator that IS the `jsonValue` intrinsic impl
    (`Json.scala:11` maps `jsonValue -> _ssc_ui_jsonValue`; `JsGenStdImportTest:580`
    calls it 3-arg). Callers at signals.mjs:1067/1073/1603/1606.
  Whichever is concatenated last wins. Currently the 3-arg version wins (the
  `jsonValue` intrinsic works, JsGenStdImportTest passes), so the 1-arg
  JSON-string-parse helper is dead-shadowed — the feature that needed it is broken.
- **Owning area:** JS JSON-natives (the 1-arg intruder) + the `jsonValue` intrinsic.
  Left for that owner — renaming safely needs knowing the 1-arg helper's callers
  (not visible in `.mjs`; likely generated `__jsonCore*` code).
- **Fix (suggested):** rename the `core-collections.mjs` 1-arg `_ssc_ui_jsonValue(s)`
  (e.g. `_ssc_json_parse_value`) + its `__jsonCore*` callers; keep the 3-arg
  `jsonValue` intrinsic impl as `_ssc_ui_jsonValue`.

## v1-explicit-companion-shadows-case-constructor — later case-class construction resolves to the companion value
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 422a5b7c8 -->

**Status:** fixed (2026-07-12, opus) — see git; conformance companion-case-class-order + 73 binding unit tests green.
module is unblocked by routing construction through a helper declared before
the explicit companion.

- **Real-harness repro:** in an imported module declare `case class B(...)`, a
  helper/extension that returns `B(...)`, and then `object B` with factories.
  Invoke the later constructor path with `bin/ssc-tools run --v1`; it exits with
  `Instance is not callable` at the `B(...)` expression. A constructor helper
  declared before `object B` remains callable.
- **Root cause (partial):** v1 name resolution/dispatch binds the explicit
  companion instance where the generated case-class constructor should be used
  in some later function/extension bodies.
- **Done-when:** a multi-file v1 conformance case combines a case class and
  explicit companion and can construct from a later ordinary function and
  method without helper-order sensitivity.

## wasm-emitter-js-import-name-mismatch — generated JS cannot find emitted module
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 422a5b7c8
     confirmed: no -->

**Status:** fixed (2026-07-12, `422a5b7c8`), awaiting Sergiy confirmation;
found by codex while retiring the v2.1 WASM `both-fail` target row.

- **Real-harness repro:** run `bin/ssc-tools emit-wasm
  examples/wasm-scalascript.ssc` in a fresh directory, then run the generated
  `wasm-scalascript.js` with Node. The emitter writes `module.wasm`, but the JS
  contains `__load("./main.wasm", ...)`, so execution fails until the file is
  manually copied to `main.wasm`.
- **Root cause:** the artifact copy/name chosen by the ScalaScript WASM command
  diverges from the fixed module name emitted by the Scala.js linker.
- **Done-when:** the emitted JS references the actual emitted `.wasm` filename
  (or the artifact is named exactly as linked), the pure WASM example executes
  directly under Node with exact output, and the HTTP WASM document compiles
  and validates without public-network execution.
- **Fix/result:** the backend now preserves Scala.js' linked `main.wasm` name;
  the generated JS runs directly under Node with the full exact pure-example
  output, while the HTTP example compiles to a valid module/JS pair and is not
  executed against the public URL. The focused backend suite is 40/40.

## v21-native-direct-do-unlowered — direct[M] remains an unbound call
<!-- status: fixed
     lane: js
     area: front
     fixed-in: e8065f02b
     confirmed: no -->

**Status:** fixed (2026-07-12, language `e8065f02b`, taxonomy `602c91cb2`),
awaiting Sergiy confirmation; found by codex in TI-8.2d3m audit.

- **Real-harness repro:** standard VM/direct ASM fail before stdout with
  `unbound global: direct`; explicit compatibility prints 11 canonical Option/
  List rows.
- **Root boundary:** type application is erased and every block remains
  `app(global direct, lam0(seq(cell.set(@bind, rhs), ...)))`; bind statements
  have already lost the monadic control-flow semantics.
- **Done-when:** the self-hosted frontend lowers Option/List direct blocks,
  short-circuiting/cartesian binds, pure vals/vars, and nesting generically with
  exact public output and no v1 `DirectAnorm` dependency or fallback.
- **Fix/result:** the parser retains a dedicated direct node and frontend
  desugaring emits existing portable `flatMap` control flow with lexical var
  tracking. The focused positive/negative fixtures, public 11-line example,
  VM/ASM/build-jvm release gate, and fresh 11/11 conformance pass; parity is
  50/16 with three blockers and zero mismatch/one-sided rows.

## v2-swift-core-stale-testing-command — spec names an absent e2e script
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: 7e4b2e563
     gate: tests/e2e/v2-swift-core.sh -->

**Status:** done (2026-07-11, `7e4b2e563`); found by codex during final spec verification.

- **Real-harness repro:** `tests/e2e/v2-swift-core.sh` exits 127 because the
  file has never existed, although the feature spec lists it as a release gate.
- **Expected/fix:** the testing strategy names the real full Swift backend gate,
  `scripts/sbtc 'v2SwiftBackend/test'`, which passed 43/43 and already executes
  the checked money/effect/NativeUi sources through real Swift. Keep the
  assembled CLI and Apple scripts as the two distinct end-to-end gates.
- **Result:** the verified spec now names the real 43/43 suite and both
  assembled scripts; no absent command remains in the release procedure.

## tkv2-js-duplicate-nodecrypto — generated JS declares `_nodeCrypto` twice
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: aab53ab3c
     gate: tests/conformance/run.sh -->

**Status:** done (2026-07-11, `aab53ab3c`); found by codex in the mandatory no-memo
`tkv2-*` landing gate for Apple distribution round 2 after rebasing current
`origin/main`.

- **Real-harness repro:** fresh `scripts/sbtc installBin`, then
  `tests/conformance/run.sh --only 'tkv2-*' --no-memo`. INT passes all eleven
  applicable cases, but every JS case exits 1 at generated stdin line 2098 with
  `SyntaxError: Identifier '_nodeCrypto' has already been declared`; only the
  INT-only PWA case completes, so the corpus is 1/12.
- **Expected:** the assembled JS runtime declares or imports Node crypto once;
  all twelve toolkit-v2 cases retain their established exact output on the
  requested lanes.
- **Plan/done-when:** identify the two concatenated authorities introduced on
  current main, keep one environment-safe declaration without weakening
  browser/Node crypto behavior, add a generated-source duplicate-declaration
  regression, then require isolated JS repro plus full `tkv2-* --no-memo`
  12/12 before the Swift distribution push.
- **Root cause/fix:** security hardening `ed5fcc52a` added a second
  `var _nodeCrypto` to `JsRuntimeFs`, while the earlier core-collections
  preamble already owns `const _nodeCrypto` and is concatenated first. The fs
  runtime now reuses that binding; the focused declaration gate passes 22/22,
  isolated INT+JS execution passes, and the assembled no-memo corpus is 12/12.

## v21-json-parser-pmapped-match — JSON DSL reaches an unhandled `PMapped/2`
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 5b16df6df -->

**Status:** done (2026-07-11; regression `5b16df6df`, taxonomy
`06a1ae9bb`); found and confirmed by codex after native `case object` support
advanced `dsl-json-parser.ssc` beyond `unbound global: NoContext`.

- **Historical real-harness repro:** a staged `bin/ssc-standard run --native
  examples/dsl-json-parser.ssc` failed identically on VM/ASM with `match: no arm
  for PMapped/2`; a clean assembly at the same source state does not reproduce.
- **Expected:** the imported combinator evaluator's existing `PMapped(inner, f)`
  arm matches the reified parser node and applies the mapping closure.
- **Diagnosis:** a clean `scripts/sbtc "installBin"` at `c227b40ee` makes both
  examples exit 0 on native VM and direct ASM with byte-identical output. No
  source commit after the extension receiver fix `878474b8d` changed the
  frontend/runtime path. The earlier failure therefore came from a staged
  distribution assembled before that fix, not from a missing `PMapped/2` arm.
- **Fix/result:** no matcher patch was needed. The exact multi-file regression
  imports the evaluator and prints `22/0/0` on VM/direct ASM; the focused parser
  DSL smoke proves zero-exit, empty stderr, and byte parity. Both stale
  `PMapped/2` taxonomy rows are retired. Later JSON/YAML placeholder semantics,
  the `functional.ssc` parity mismatch, and HTTP release-tail failure have
  separate owners.

## v2-swiftui-fetch-wrapper-silent-default — non-text fetch bindings render an empty value
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the third
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** bind a deferred `fetch` signal to a text control or a
  signal-backed style. `NativeUiSignalText` reports sourced Unsupported, but
  the other wrappers read the fetch signal's empty default and render it as if
  a request had completed.
- **Expected:** until the async slice lands, every rendered seam rejects a
  fetch-kind signal with the same source-located Unsupported diagnostic.
- **Plan/done-when:** centralize the guard in the observation/binding seam and
  execute signal-text, text-control, toggle/style, and keyed-items probes; none
  may expose the empty fetch default.

## v2-swiftui-shipped-inventory-semantic-loss — accepted tags/styles render different semantics
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the third
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** shipped `align-items:center` is accepted while stacks
  remain hard-coded leading/top; `font-weight:500` is accepted but unapplied;
  `strong`/`em`/`code` are plain children; an href-only anchor is a no-op
  button; and `ol start` still renders bullet list items.
- **Expected:** every shipped inventory value maps to its native behavior or a
  sourced Unsupported node; accepted-but-ignored semantics are forbidden.
- **Plan/done-when:** implement the exact native mapping where bounded, use
  sourced Unsupported for any deferred value, and execute behavior-or-
  Unsupported probes for alignment, medium weight, semantic text, href-only
  navigation, and ordered-list numbering/start.
  The real `content.ssc` ordered-list `start` field is an `Int`, not the String
  used by the first draft gate. Recognized CSS also needs value-total handling:
  `display`, `flex-direction`, `gap`, `flex`/`flex-grow`, `text-align`,
  `text-decoration`, and border shorthands must map or diagnose invalid values.
  Until a route-signal seam exists, hash/relative href is sourced Unsupported;
  treating `#/path` as a generic `Link` violates the frozen route contract.
  Fifth review found the remaining recognized-value holes: parse the shipped
  `box-shadow` grammar exactly (or source Unsupported) rather than applying one
  hard-coded shadow to every value, and require the exact accepted border
  shorthand instead of accepting trailing junk.
  Sixth review adds that an explicit `border-color` must not mask an invalid
  third color token in the shorthand itself; validate the shorthand first,
  then apply the explicit override.

## v2-swiftui-owner-hint-closure-clone-leak — node identity mutates ABI and retains refresh tombstones
<!-- status: fixed
     lane: js
     area: conformance
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the third
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** two `NativeUiForKeyed` nodes reuse the same render
  closure. The draft clones that closure solely to key owner hints, violating
  the frozen constructor identity contract. Every successful refresh of a
  surviving owner also retains superseded cloned closure/hint entries because
  pruning runs only for deleted owner subtrees.
- **Expected:** constructors preserve the exact original closure identity;
  owner metadata binds to the concrete returned node/instance, remains bounded
  across refreshes, rolls back transactionally, and disappears on deletion.
- **Plan/done-when:** attach host-only exact-node identity without adding or
  changing ABI fields, prune superseded hints within the owner transaction,
  and real-gate shared closure identity plus bounded counts across repeated
  refresh, failed rollback, and committed delete.

## v2-swiftui-owner-hint-fifo-swap — reversed tree construction exchanges repeated-site state
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` in the second
read-only SwiftUI store/renderer review in Rozum.

- **Real-harness repro:** construct two `NativeUiForKeyed` nodes at the same
  lexical site under distinct component/occurrence owners, then place them in
  the returned tree in reverse construction order. The draft records owner
  paths in a per-site FIFO and assigns them during later tree traversal, so the
  nodes exchange owners and may inherit each other’s component state.
- **Expected:** an owner hint is bound to the exact returned node/structural
  instance independently of construction order.
- **Plan/done-when:** replace FIFO correlation with node-bound identity without
  changing ABI fields and add a real reversed construction/tree-order gate
  covering move and fresh reinsertion.

## v21-parity-backends-list-ignored — JS-only examples run on the standard JVM lane
<!-- status: fixed
     lane: js
     area: front
     fixed-in: d4c953b9c
     confirmed: no -->

**Status:** fixed (2026-07-11, `d4c953b9c`), awaiting Sergiy confirmation;
found by codex while starting the TI-8.2d4 example/config blocker sweep.

- **Real-harness repro:** run full `scripts/bc-parity-sweep --ssc
  bin/ssc-standard`. Both `sql-browser-{sqlite,duckdb}.ssc` declare
  `backends: [js, node, wasm]`, yet the harness executes them on VM/direct ASM
  and records `both-fail` for their browser-only SQL result bindings.
- **Expected:** a front-matter list containing only JS-family backends is a
  reviewed backend-specific source classification, identical in status to the
  existing singular `backend:`/`target:` rules. Lists that include `jvm` (for
  example `dataset-parallel-sum.ssc`) must not hide standard-runtime debt.
- **Root cause:** `bc-parity-sweep` recognizes only singular `backend:` and
  `target:` keys; it never inspects the established plural `backends:` key.
- **Plan/done-when:** add a bounded inline-list classifier for JS/Node/Wasm-only
  lists, cover a real browser SQL example in the portable-gates smoke, rerun
  all 195 parity rows, and remove only the two newly skipped runtime-taxonomy
  rows while preserving `dataset-parallel-sum.ssc` as a blocker.
- **Fix/verified:** the harness now recognizes only inline lists composed of
  `js`/`node`/`wasm`; both browser SQL rows are `skipped-backend`, while the
  focused `[jvm]` dataset row still executes and remains `both-fail`. Full
  parity is 21/45/129 with zero mismatch or one-sided error.

## v2-swiftui-keyed-owner-lifecycle — deleted keys retain and resurrect component state
<!-- status: fixed
     lane: js
     area: other
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` during the first
read-only Apple store/renderer review in Rozum.

- **Real-harness repro:** the draft Swift renderer invokes a keyed render
  closure directly and relies only on SwiftUI `ForEach`; `NativeUiHost`
  stores signals by `(scope,id)` and has no structural owner transaction,
  scope refcounts, rollback, or deletion. Removing then reinserting a key can
  reuse stale component signals, while duplicate/non-String keys are silently
  dropped/coerced.
- **Expected:** Host-owned begin/commit/rollback/delete transactions use the
  frozen root/owner/site/occurrence path, preserve moved keys, dispose a scope
  after its last owner, and create fresh state after committed deletion. Store
  orchestrates those calls and removes returned observation/dependency keys.
- **Plan/done-when:** expose the transaction across `NativeUiSession`, render
  keyed entries provisionally, retain the last committed tree on error, reject
  the first duplicate/non-String key, and pass executable Swift
  insert/move/update/delete/reinsert/rollback probes.

## v2-swiftui-unobserved-signal-read — dynamic nodes do not rerender
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: 70bee065d
     confirmed: no -->

**Status:** fixed (2026-07-11, `70bee065d`), awaiting Sergiy confirmation; found by `nativeui-reviewer` during the first
read-only Apple store/renderer review in Rozum.

- **Real-harness repro:** only `NativeUiSignalTextView` owns an
  `@ObservedObject` cell/token. Show conditions, keyed item signals,
  value/checked bindings, and signal-backed style/attribute reads call
  `store.read` directly, so a write does not invalidate those view subtrees.
- **Expected:** every rendered signal read is owned by a stable observed cell
  and exact appearance token; first/last subscriptions activate/release
  dependencies and rerender only the affected subtree.
- **Plan/done-when:** route each dynamic node/binding/style seam through a small
  observed wrapper and pin token/revision behavior with executable Swift probes
  plus real SwiftUI typecheck.

## v21-sentinel-taxonomy-parity-success — parser sentinel becomes unclassified when both lanes exit zero
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 07c1d9b55
     confirmed: no -->

**Status:** fixed (2026-07-10, `07c1d9b55`); found by codex while verifying
TI-8.2c2i, waiting for Sergiy confirmation before `done`.

- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `scripts/native-front-corpus --report target/v21-native-front-current.tsv`,
  `scripts/bc-parity-sweep --report
  target/v21-standard-bc-parity-current.tsv`, then
  `scripts/v21-sentinel-taxonomy`. The fresh reports contain 74 frontend
  sentinel rows, while parity classifies 63 documents `identical`; taxonomy
  exits nonzero with ten `unclassified sentinel` / stale-override diagnostics,
  including the six remaining standard DSL rows and four reviewed tools rows.
- **Expected:** readiness classification is driven by the frontend sentinel
  column. An identical VM/ASM exit does not erase `_err`; backend/server/source
  categories and reviewed tools overrides still apply, and otherwise the row is
  a standard syntax gap.
- **Root cause:** `scripts/v21-sentinel-taxonomy` accepts a standard gap and
  reviewed override only when the parity category is `both-fail`. Uncalled or
  non-observable `_err` nodes can let both lanes exit zero identically, so parity
  success and frontend completeness are independent axes.
- **Fix:** readiness now accepts `both-fail` or `identical` parity after
  source-derived categories, while mismatch/one-sided rows remain rejected.
  Reviewed overrides follow the same rule, so an identical unobserved sentinel
  cannot become stale merely because neither lane executes it.
- **Verified:** synthetic identical standard/tools sentinels pass; the real
  normative standard-tier report classifies all 74 sentinels as 6 standard, 26
  server, 36 backend, 5 tools/backend, and 1 nondeterministic. Native-entry and
  fresh affected conformance 9/9 pass.
- **Done-when:** a synthetic regression covers an `identical` sentinel plus an
  `identical` reviewed tools row, the real 74-row report classifies completely,
  category ceilings shrink to the measured counts, and affected conformance is
  green. Keep `fixed` until Sergiy confirms the release-gate behavior.

## v21-native-front-dsl-pair-match-crash — valid tuple pattern aborts the self-hosted frontend
<!-- status: fixed
     lane: js
     area: front
     fixed-in: d4513cb8a
     confirmed: no -->

**Status:** fixed (2026-07-10, `d4513cb8a`, diagnostic gate
`ac441ef62`); waiting for human confirmation before `done`.

- **Found by:** codex from `scripts/native-front-corpus`.
- **Real-harness repro:** the assembled self-hosted frontend on
  `examples/dsl-mini-language.ssc` aborts before CoreIR with
  `RuntimeException: match: no arm for Pair/2`, while the checker-only route
  reports `OK`. The source uses nested tuple patterns such as
  `case Some((l, '+', r))`.
- **Expected:** supported tuple/constructor patterns lower or produce a
  source-located compile diagnostic; the compiler must never crash inside its
  own pattern matcher.
- **Root cause:** `=>` was not a native layout opener. A multiline lambda whose
  first statement was `val (a, c) = ac` therefore left the lambda after one
  expression and fed detached tuple AST nodes into the lowerer, which crashed
  while matching an unexpected `Pair/2`. Separately, 3+-element tuple patterns
  used synthetic `TupleN` tags although tuple expressions lower to right-nested
  `Pair` constructors.
- **Fix:** `=>` now opens an offside block, and tuple patterns recursively build
  the same right-nested `Pair` shape as tuple expressions. The standard launcher
  source-locates any remaining parser sentinel instead of exposing a host stack.
- **Verified:** a multiline-lambda/local-tuple plus nested
  `Some((left, '+', right))` fixture prints `left` and `left+right` identically
  on assembled VM/direct ASM. The real DSL row is frontend/checker OK and fails
  only through its filename-bearing bounded sentinel diagnostic; no `Pair/2`
  host exception remains. Native-entry passes, affected conformance is 8/8, and
  the full frontend corpus has zero host errors/timeouts. Remaining unsupported
  DSL surface syntax is tracked by TI-8.2c, not this crash bug.

## v21-native-front-prefix-postfix-precedence — `!exists(path)` applies the call after prefix `!`
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 6e8464ea8
     confirmed: no -->

**Status:** fixed (2026-07-10, `6e8464ea8`); waiting for human confirmation
before `done`.

- **Found by:** codex while classifying the final sentinel-clear native-checker
  corpus rejection after `66b7c4ede`.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc run --native examples/fs-roundtrip.ssc`. The smaller source shape is
  `println("Deleted: " + !exists(path))`.
- **Observed:** the native frontend lowers the operand as
  `app(if(global exists, false, true), global path)`; the checker consequently
  rejects an attempted Bool-as-function application with `cannot unify Bool
  with non-Bool`.
- **Expected:** postfix application binds within the prefix operand:
  `if(app(global exists, global path), false, true)`. The checker accepts the
  valid source and the VM/ASM paths evaluate the negated call.
- **Root cause direction:** `parsePrefix` parses only the atom/name after `!`,
  while the enclosing postfix phase attaches `(path)` after constructing the
  prefix node. Make postfix selection/application part of the prefix operand
  without regressing unary minus or operator-section parsing.
- **Owner/slice:** `v21-ti-native-front-parity`; rebase the active hex/frontend
  sibling before editing `ssc1-front.ssc0`, then add an assembled native
  regression with the exact call shape.
- **Fix:** each unary operator parses the operand atom and completes its postfix
  selection/application chain before wrapping it in the prefix AST node. This
  applies uniformly to `!`, unary `-`, and bitwise `~`.
- **Verified:** the staged native-entry smoke exercises `!flag()`, `-one()`,
  and `~one()` on both VM and direct ASM; the original `fs-roundtrip.ssc`
  native checker result is now `OK`; checker smoke PASS and affected `v2-*`
  conformance 8/8.

## v21-native-front-prose-self-import-loop — raw link scan follows prose links as module imports
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 0ccecb44d
     confirmed: no -->

**Status:** fixed (2026-07-10, `0ccecb44d`); waiting for human confirmation
before `done`.

- **Found by:** codex during the TI-2 native-front corpus baseline at
  `7ba4d413b`.
- **Real-harness repro:** after `scripts/sbtc "installBin"`, run
  `SSC_NO_CDS=1 scripts/run-with-timeout 20 java -Xss512m -cp 'bin/lib/jars/*'
  ssc.cli run v2/bin/ssc1-run.ssc0 examples/components-demo.ssc`.
- **Observed:** the native loader repeatedly prefixes `./` until
  `java.nio.file.FileSystemException: .../components/././.../button.ssc: File
  name too long`.
- **Expected:** prose links are not imports, and a canonical module path is
  loaded at most once.
- **Root cause:** `sscImports` scans the entire raw Markdown document for every
  `](...ssc)` substring. The prose in `examples/components/button.ssc` contains
  the documentation example `` `[Button](./button.ssc)` ``, so the module
  imports itself. `sscResolve` concatenates paths without canonicalizing `./`,
  and the textual `seen` set therefore never recognizes the cycle.
- **Fix direction:** derive imports from actual top-level Markdown import nodes
  (or at minimum exclude fenced/inline-code/prose link examples) and normalize
  path segments before DFS/load-once comparison. Add this repro to the native
  corpus gate; do not merely special-case `button.ssc`.
- **Owner/slice:** `v21-ti-native-front-production-entry` / frontend parity.
- **Fix:** the native loader now recognizes only standalone Markdown import
  links outside fenced code, normalizes `.`/`..` path segments lexically before
  the shared DFS seen-set comparison, and runs the frontend tower on a dedicated
  bounded-stack thread so a remaining frontend failure cannot consume the CLI
  thread stack.
- **Verified:** the multi-file relative-import fixture includes the former prose
  self-link shape and completes with `42`; the real `components-demo.ssc` repro
  now terminates with a bounded parser-sentinel diagnostic instead of `File name
  too long` or `StackOverflowError`. Assembled native-entry smoke PASS and
  affected `v2-*` conformance 8/8.

## tkv2-spa-i18n-serve-intrinsic-shadow — emitted custom SPA calls bare `serve` instead of imported `serve__ssc`
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 7e5d55e4f
     confirmed: no -->

**Status:** fixed (2026-07-10, `7e5d55e4f`); waiting for human confirmation
before `done`.

- **Found by:** codex during `tkv2-spa-i18n-parity`.
- **Repro:** emit `examples/std-ui/i18n-demo.ssc` through the custom SPA path and
  execute it in a browser/jsdom harness. The generated module imports
  `std.ui.primitives.serve` as `serve__ssc`, but the top-level auto-call was
  emitted as bare `serve(...)`; jsdom failed before mounting `.ssc-page` with
  `ReferenceError: serve is not defined`.
- **Expected:** imported/user bindings, including collision-renamed imports,
  must take precedence over JS intrinsic dispatch. The i18n demo should mount
  and live-switch EN/RU/UK/PL without reload.
- **Root cause:** `JsGen.dispatchIntrinsicJs` checked only
  `declaredBindings.contains(fname)` before stealing `Term.Name(fname)` calls.
  Collision-renamed imports bind `emittedName(fname)` (`serve__ssc`), so the
  hardcoded `serve` intrinsic path incorrectly won over the imported binding.
- **Fix:** `dispatchIntrinsicJs` now also skips intrinsic dispatch when the
  emitted binding name is declared or when a top-level user rename exists. The
  generated call falls through to regular `_call(serve__ssc, ...)`.
- **Verified:** standalone patched-`JsGen` jsdom harness prints
  `i18n-spa-live-ok`; CLI-shaped `emit-spa --frontend custom` with patched
  classes emits `_call(serve__ssc, ...)` and the emitted HTML passes the same
  jsdom live-switch check; affected conformance
  `tests/conformance/run.sh --only 'std-ui-i18n,tkv2-*' --no-memo` passes
  10/10; `git diff --check` passes.

## v2-dsl-yaml-tuple-accessor — `pair._2` on a parser result hits fieldAt OOB (long-standing, NOT a fresh regression)
<!-- status: fixed
     lane: js
     area: front
     fixed-in: 4def0c749
     confirmed: no -->

**Status:** fixed (2026-07-10, code fix `4def0c749`); waiting for human or
external reporter confirmation before `done`.

- **Found by:** claude/codex while auditing the corpus v2 failures.
- **Repro before fix:** after `scripts/sbtc "installBin"`,
  `bin/ssc run examples/dsl-yaml-like.ssc` and
  `bin/ssc run --v2 examples/dsl-yaml-like.ssc` printed `Parsed successfully.`
  and then crashed with `ArrayIndexOutOfBoundsException: Index 1 out of bounds
  for length 1` at the v2 runtime `fieldAt` path. Instrumentation showed a
  `YStr` receiver being accessed at tuple field index 1 (`._2`). The v1 lane was
  not a clean reference: `bin/ssc run --v1 examples/dsl-yaml-like.ssc` failed
  earlier with unresolved `withIndent` imports.
- **Root cause:** three parser/layout issues compounded. First,
  `Parser.withIndent(n)` lowered through the generic `PWithLocalContext` shape;
  on v2 that captured the incoming context as the new current level, so
  `withIndent(3)` behaved like `withIndent(IndentContext(...))`. Second,
  `PSameIndent`/`block` checked the current column without consuming leading
  indentation or guarding the first block item, letting nested mapping lines fall
  through as scalars. Third, the YAML demo grammar wrapped an already parsed
  nested `YMap` in another `YMap(List(...))` and required a newline at EOF.
- **Fix:** added an explicit `PWithIndent` parser node handled directly by
  `runLayout`, made layout indentation guards skip blank lines and consume the
  required indentation before parsing each block item, and changed the YAML demo
  to parse one nested value with `yamlValueRef.withIndent(level + 2)` plus
  EOF-aware `eol`.
- **Verified:** `scripts/sbtc "installBin"`; `tests/conformance/run.sh --only
  'parsing-*' --no-memo` passed 3/3; new
  `tests/e2e/dsl-yaml-like-v2-smoke.sh` passed and checked
  `server.host = localhost`, `database.name = myapp`, and
  `database.pool.max = 10`; `git diff --check` passed before the code commit.


Durable ledger of bugs reported in the `scalascript` rozum room (or found locally).
See the `rozum` skill — "The bug-tracking loop". Newest first. Status flow:
`open → needs-info → fixed → (confirmed) → done`. Keep fixed/done entries with their
commit SHA until the reporter confirms, then they can be trimmed.

| Status legend | |
|---|---|
| `open` | reproduced / accepted, work to do |
| `needs-info` | blocked on a repro question asked in the room |
| `fixed` | landed on `origin/main`, reporter not yet re-confirmed |
| `done` | reporter confirmed fixed (safe to trim) |

## scripts-bench-wall-all-na — `fixed` (2026-07-09)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 966a530e6
     confirmed: no -->

- **Found by:** codex, while measuring `v2-prod-performance-gate-baseline`.
- **Repro:** after staging `bin/ssc`, run `scripts/bench wall` from the repo
  root.
- **Observed failure:** every cell prints `n/a` for `fib`, `sum`, and
  `list-ops`, even though `tests/bench/{fib,sum,list-ops}.{ssc,scala,js}`
  exist.
- **Impact:** the mandated `scripts/bench wall` entrypoint cannot produce
  useful cross-language wall-clock numbers, so production performance notes
  would have to rely on `bench.sh` only.
- **Root cause:** two stale assumptions in `tests/bench/run.sc`: it set
  `dir = os.pwd / "bench"`, so `scripts/bench wall` from repo root looked for
  `/repo/bench/fib.ssc` instead of `/repo/tests/bench/fib.ssc`; and its
  missing-`sscc` fallback used the obsolete `ssc compile <file>` command.
- **Fix:** `966a530e6` resolves the data directory from either repo root or
  direct script-directory execution, and changes the JVM fallback to
  `ssc run-jvm <file>`.
- **Gates:** `scripts/bench wall` now reports usable rows:
  `fib 50/2/0/0/2`, `sum 51/4/1/0/2`, and
  `list-ops 110/n/a/33/73/2` for `ssc-int/ssc-js/ssc-jvm/scala-cli/node`.
  The remaining `list-ops` JS `n/a` is an unsupported-row caveat, not the
  all-`n/a` runner failure.
- **Status:** fixed; waiting for human confirmation before `done`.

## jvmgen-litdoc-mapped-string-mkstring — `fixed` (2026-07-09)
<!-- status: fixed
     lane: js
     area: front
     gate: tests/conformance/litdoc.ssc
     fixed-in: unrecorded -->

- **Found by:** codex, while enabling `tests/conformance/litdoc.ssc` expected
  output during `v2-litdoc-inline-bold-parity`.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `bin/ssc run-jvm tests/conformance/litdoc.ssc`.
- **Observed failure:** generated Scala fails to compile around the litdoc fence
  line with `missing argument for parameter i of method apply in class StringOps`
  for a generated expression shaped like
  `doc.nodes.filter(...).map(...).map(_show).mkString()`.
- **Impact:** backend-lane only. The default v2 VM path (`bin/ssc run --v2`) is
  the production gate for this slice and now matches v1; `litdoc.ssc` is marked
  `backends: [int]` until the JVM generator issue is fixed.
- **Root cause:** two backend-lane issues stacked on the same litdoc line. The
  generated JVM preamble always emitted `def doc(args: Any*)`, so a user
  top-level `val doc = parseDoc(md)` lived in the same generated Scala scope as
  the helper. After that was fixed, the remaining `StringOps.apply` error came
  from rewriting no-arg `.mkString()` to `.map(_show).mkString()`: Scala's
  no-arg `Iterable.mkString` is parameterless, so `mkString()` applies the
  returned `String` as a function.
- **Fix:** `782f07438` omits the JVM `doc` helper when user code owns top-level
  `doc`, rewrites no-arg `.mkString()` to `.map(_show).mkString`, and enables
  `tests/conformance/litdoc.ssc` across all backend lanes.
- **Gates:** `scripts/sbtc "backendJs/compile; backendJvm/compile; installBin"`;
  direct `bin/ssc run-jvm tests/conformance/litdoc.ssc`;
  `tests/conformance/run.sh --only 'litdoc' --no-memo`; focused
  `backendInterpreter/testOnly scalascript.JvmGenBackendBlockTest`.
- **Status:** fixed; awaiting any external confirmation before trimming.

## v2-litdoc-inline-bold-parity — `fixed` (2026-07-09)
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/conformance/litdoc.ssc
     fixed-in: unrecorded -->

- **Found by:** codex, while verifying `v2-arith-unification` against the
  litdoc real harness.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v1 tests/conformance/litdoc.ssc` and
  `bin/ssc run --v2 tests/conformance/litdoc.ssc`, then diff stdout.
- **Observed failure:** all litdoc lines match except inline emphasis rendering:
  v1 prints `inline: P(buy a )B(new)P( dress)`, while v2 prints
  `inline: P(buy a **new** dress)`.
- **Impact:** this is an output-parity mismatch in a non-example conformance
  document. It is not caused by the arith dispatch split fixed in
  `v2-arith-unification`; the map/data line now agrees, but inline bold parsing
  still diverges.
- **Root cause:** v1 string `.split` uses Java/Scala regex semantics
  (`s.split(sep, -1)`), but v2 quoted the delimiter with
  `Pattern.quote(...)` in the primitive, string-method, and FastCode
  `str.split` paths. The litdoc delimiter `"\\*\\*"` therefore became a literal
  backslash-star pattern under v2 and never split the bold marker.
- **Fix:** `2b5a36660` restores regex semantics in all three v2 split paths and
  adds `tests/conformance/expected/litdoc.txt`. The fixture is marked
  `backends: [int]` because JS/JVM have separate backend-lane blockers tracked
  in `jsgen-toplevel-name-vs-preamble` and
  `jvmgen-litdoc-mapped-string-mkstring`.
- **Gates:** `scripts/sbtc "installBin"` passed;
  `tests/conformance/run.sh --only 'litdoc' --no-memo` passed INT and skipped
  JS/JVM by `backends: [int]`; direct
  `bin/ssc run --v1 tests/conformance/litdoc.ssc` vs
  `bin/ssc run --v2 tests/conformance/litdoc.ssc` diff is empty.

## v2-cluster-stdlib-import-gap — `fixed` (2026-07-09)
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: unrecorded -->

- **Found by:** codex, after `cdd032f03` fixed standard `scala` fence
  extraction during `v2-parity-current-errors`.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v1 examples/cluster-capability.ssc` and
  `bin/ssc run --v2 examples/cluster-capability.ssc`. v1 prints:
  `demo-node`, `ws://seed:9100/_ssc-actors`, `sha256`, `64`; v2 now reaches
  program execution but exits with `RuntimeException: unbound global: clusterOf`.
- **Observed failure:** the standard-fence targeted parity slice after
  `cdd032f03` reports `1/6 identical · 4 mismatch · 1 v2-error`; the remaining
  v2-error is `cluster-capability.ssc`.
- **Impact:** the cluster stdlib import/export path is not production-safe under
  the v2 default runner; `clusterOf` is visible to v1 but unresolved in v2.
- **Root cause:** v2's actor compatibility bridge registered `runActors`,
  `startNode`, and related actor globals, but not the cluster capability globals
  that v1 installs through `ActorGlobals` (`clusterOf`, `resolveSeeds`,
  `codeIdentity`, `assertCodeIdentity`, `SeedResolver`). After those globals were
  added, the imported case-class method `ClusterCapability.resolveSeeds` exposed
  a second shape bug: the generated case-class method global named `resolveSeeds`
  shadowed the plugin extern/global of the same name and recursively treated a
  `SeedResolver` as a `ClusterCapability`. `__methodOrExt__` now gives registered
  plugin method dispatch a chance before falling back to the user extension
  global when a DataV has no real field by that name.
- **Fixed in:** `70969362f` (`fix(v2): bridge cluster capability globals`).
- **Gates:** targeted v2 regression
  `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest -- -z cluster`;
  `v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest` (22/22);
  `v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest` (17/17);
  `scripts/sbtc "installBin"`; real harness
  `bin/ssc run --v1/--v2 examples/cluster-capability.ssc` (both print
  `demo-node`, `ws://seed:9100/_ssc-actors`, `sha256`, `64`); targeted six-example
  parity is now `2/6 identical · 4 mismatch · 0 v2-error`; full production gate is
  `64/98 identical · 11 mismatch · 0 v2-error · 23 v1-only`.

## v2-standard-scala-fences-skipped — `fixed` (2026-07-09)
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded -->

- **Found by:** codex, during `v2-parity-current-errors` full output-parity
  refresh.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v1 examples/cluster-capability.ssc` and
  `bin/ssc run --v2 examples/cluster-capability.ssc`. v1 prints:
  `demo-node`, `ws://seed:9100/_ssc-actors`, `sha256`, `64`; v2 exits 0 with
  empty stdout. A minimal repro is a document containing only:
  ````markdown
  ```scala
  println("scala-block-ok")
  ```
  ````
  which prints on v1 and is empty on v2, while the same fence tagged
  `scalascript` prints on both.
- **Observed failure:** the fresh production gate
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` reports
  `62/93 identical · 7 mismatch · 6 v2-error · 18 v1-only`; the six v2-error
  rows all use standard `scala` fenced blocks:
  `cluster-capability`, `distributed-streams`, `graph-neo4j-storage`,
  `graph-storage`, `scala-js-demo`, and `streams`.
- **Impact:** default v2 silently treats standard-Scala-only `.ssc` examples as
  empty programs, so `ssc run --v2` can exit 0 without running user code.
- **Root cause:** `FrontendBridge.convertSource` uses
  `extractCode(..., allFences = true)`, whose non-SQL runnable-fence regex
  includes `scalascript` but excludes `scala`; `RunV2` does not use the
  `ModuleBridge.convert(Parser.parse(...))` path that walks all parseable
  `Content.CodeBlock`s.
- **Fix:** `cdd032f03` teaches the v2 source extraction path to include standard
  `scala` fences when they are the runnable source for the document, without
  re-enabling illustrative Scala snippets in mixed ScalaScript docs that the
  existing comment warns about.
- **Gates:** `scripts/sbtc "v2FrontendBridge/testOnly ssc.bridge.FrontendBridgeTest"`
  (17/17); `tests/conformance/run.sh --only 'standard-scala-fence' --no-memo`
  (INT/JS/JVM pass); `scripts/sbtc "installBin"`; minimal real harness
  `bin/ssc run --v1/--v2 <standard-scala-fence>` prints `scala-block-ok` on both;
  targeted six-example parity changed the failure mode to one remaining
  `clusterOf` v2-error plus four non-empty mismatches, with `graph-storage.ssc`
  now matching.
- **Follow-up:** `cluster-capability.ssc` now exposes
  `v2-cluster-stdlib-import-gap`; that is tracked as a separate bug.

## route-deriver-path-param-unit-client — `fixed` (2026-07-09)
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded -->

- **Found by:** codex, during `tkv2-typed-client` prep.
- **Repro:** with no explicit `apiClients:` front matter, derive a client from
  `route("GET", "/api/todos/:id") { ... }` or
  `route("DELETE", "/api/todos/:id") { ... }`, then inspect
  `bin/ssc emit-js examples/derived-route-clients.ssc`. Current output warns
  `path param ':id' cannot be filled — request type is Unit` and emits methods
  such as `getApiTodosById(headers, cancelToken)`, so a browser client cannot
  pass the id.
- **Observed failure:** route-derived non-body endpoints with path parameters
  are not callable as typed browser clients; the generated method shape treats
  the first user argument as headers instead of the path value.
- **Impact:** toolkit-v2's no-manual-`apiClients:` route-derived browser client
  path is not production-safe for common detail/delete routes.
- **Fix direction:** have `RouteDeriver` use `String` for one path parameter
  and `Any` for multiple path parameters when no typed handler evidence exists;
  keep explicit `apiClients:` behavior unchanged.
- **Root cause:** `RouteDeriver.makeEndpoint` defaulted every non-body endpoint
  without typed handler evidence to `requestType = Unit`, ignoring path params.
  JS/JVM client codegen correctly treats `Unit` as a no-input method, so the
  first user argument was interpreted as headers and the route id could never
  fill `:id`.
- **Fixed in:** `4656f9629` (`feat: derive typed clients for path params`).
- **Gates:** `scripts/sbtc "core/testOnly scalascript.transform.RouteDeriverTest"`
  (16/16); `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenTypedRouteClientTest scalascript.JvmGenTypedRouteClientTest"`
  (57/57); `scripts/sbtc "core/compile; backendJs/compile; backendJvm/compile; backendInterpreter/compile"`;
  `scripts/sbtc "installBin"`; `tests/conformance/run.sh --only 'tkv2-typed-client-derived' --no-memo`
  (1/1 JS case); `emit-js` and `emit-spa --frontend custom --server-url`
  smokes for `examples/derived-route-clients.ssc`.
- **Done-when:** RouteDeriver, JS codegen, JVM/Swing codegen, a JS-only
  conformance smoke, docs/example, and affected tests agree; fixed SHA and
  gates are recorded here.

## v2-case-class-instance-methods-stub — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded -->

- **Found by:** codex, during `p3-connectnode-node-sim` verification.
- **Repro:** after `scripts/sbtc "installBin"`, run a `.ssc` program that
  constructs a case-class value with an instance method and calls it, or run
  `bin/ssc run examples/distributed-word-count.ssc` before the local shutdown
  workaround. `cluster.close()` lowers to a runtime `Stub("Cluster.close")`
  instead of executing the case-class method body.
- **Observed failure:** v2 registers case-class fields and can read them, but
  methods defined inside `case class ...:` are not emitted as callable closures.
  Runtime method dispatch therefore falls through from field lookup to a
  `Stub(Tag.method)` value.
- **Impact:** std APIs that rely on case-class instance methods are not fully
  production-safe on the default v2 lane. `p3-connectnode-node-sim` avoids this
  in the distributed examples by sending `ShutdownWorker()` directly; the
  language/runtime gap remains.
- **Fix direction:** extend `v2/frontend-bridge` lowering for case-class
  template methods, likely by reusing the existing tag-dispatched extension
  method path and binding constructor fields from the receiver before compiling
  the method body. Add a focused CLI/v2 regression for a case-class method that
  reads a constructor field and a std-facing regression for `Cluster.close`.
- **Done-when:** `cluster.close()` executes without printing a stub under the
  assembled default v2 CLI, the focused regression is green, and the fixed SHA
  is recorded here.
- **Root cause:** `FrontendBridge` registered case-class field names but ignored
  `Defn.Def` members inside case-class templates when emitting v2 CoreIR.
  Calls such as `Cluster.close()` therefore had no generated closure and fell
  through to `Stub(Tag.method)`.
- **FIXED (2026-07-08, `f12cad127`):** v2 now lowers case-class template
  methods through the existing tag-dispatched extension machinery, binding
  constructor fields from the receiver before compiling method bodies.
  `__methodOrExt__` also preserves registered `DataV` field precedence so a
  generated method name such as `name` does not hijack ordinary `.name` fields
  on other case classes. The distributed examples were restored to the public
  `cluster.close()` shutdown API.
- **Verified:** `V2CaseClassMethodCliTest` passed 3/3 through the assembled CLI;
  `V2TuplePatternCliTest` stayed 4/4 green; direct default-v2 runs of
  `distributed-word-count`, `distributed-log-aggregation`, and
  `distributed-join` passed with `cluster.close()`; affected conformance
  `cluster-connect,distributed-*` passed 6/6 and
  `data-types,lenses,optional,traversal,fn-typed-field` passed 4/4.

## v2-mapreduce-handler-registry-tuple-lookup — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: runtime
     fixed-in: unrecorded -->

- **Found by:** codex, during `p3-connectnode-node-sim` implementation after
  adding the local loopback map-reduce worker helper.
- **Repro:** after `scripts/sbtc "installBin"`, run
  `SSC_DEBUG_ACTORS=1 timeout 60 bin/ssc run examples/distributed-word-count.ssc`
  from the assembled CLI in the worktree. Minimal lowering repro:
  `val pair: Any = ("ada", 1); pair match { case (w: String, _: Int) => w }`
  fails under `bin/ssc run` with `unbound global: w`, while
  `bin/ssc run --v1` prints `ada`.
- **Observed failure:** the original `Cluster.connect(...)` address-string hang
  is replaced by actor deaths:
  `[actor-death] lookup(v, key)`, followed by main-task failure
  `RuntimeException: match: no arm for Exit/1`.
- **Impact:** offline distributed map-reduce examples cannot be used as a v2
  production smoke until the worker handler registry survives the real actor
  worker boundary.
- **Root cause:** the local worker path exposed several v2 lowering/library
  gaps that were previously masked by the `Cluster.connect` hang:
  typed tuple patterns did not bind names; nested tuple patterns inside
  constructor patterns such as `Some((_, found))` stayed on the fast
  non-recursive match path and lost binders; tuple `val` destructuring ignored
  wildcard field positions; unqualified `lookup(name)` inside
  `HandlerRegistry.apply` resolved to the JSON `lookup` intrinsic; top-level
  map-reduce calls were hoisted before handler registration; and std
  map-reduce relied on tuple selectors, as-patterns, `List.reduce`, and
  `List.flatMap(Option)` shapes that are not production-safe on the current v2
  lane.
- **Fix direction:** keep the map-reduce API unchanged, remove selector use from
  the std/examples path as a narrow hardening step, and fix v2 tuple
  pattern/selector lowering with a focused regression so tuple values crossing
  actor/map-reduce boundaries remain ordinary tuples.
- **Done-when:** direct `bin/ssc run examples/distributed-word-count.ssc` prints a
  non-empty result, affected conformance
  `tests/conformance/run.sh --only 'cluster-connect,distributed-*' --no-memo`
  passes, and the fixed SHA/root cause are recorded here.
- **FIXED (2026-07-08, `6c0e39559`):** added `localLoopbackCluster`, hardened
  std map-reduce against the v2 tuple/collection gaps, fixed v2 tuple pattern
  and tuple val-destructuring lowering, qualified the handler registry lookup
  self-call, blocked eager hoisting for impure map-reduce method-object calls,
  and rewired the offline distributed examples to use local workers plus
  explicit shutdown.
- **Verified:** `scripts/sbtc "cli/assembly; cli/testOnly scalascript.cli.V2TuplePatternCliTest"`
  passed 4/4; direct default-v2 runs of `distributed-word-count`,
  `distributed-log-aggregation` with a temp log, and `distributed-join` with
  temp CSVs passed; affected conformance
  `tests/conformance/run.sh --only 'cluster-connect,distributed-*' --no-memo`
  passed 6/6.

## v2-ssc0-target-display-drift — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex, during `p4-rust-wasm-lanes` baseline.
- **Repro:** from a fresh worktree with Rust and Node available, run
  `./v2/conformance/check.sh`.
- **Observed failure:** the v2 VM now renders proper `Cons`/`Nil` chains as
  `List(...)` and whole floats with collapsed `Writer.floatStr` output such as
  `10`, but the self-hosted JS/Rust target backends and parts of
  `v2/conformance/check.sh` still expect or emit the older `Cons(..., Nil)` /
  `10.0` shape. Representative failures:
  `js map.ssc0 vm=[List(2, 4, 6)] node=[Cons(...)]`,
  `rust map.ssc0 vm=[List(2, 4, 6)] rust=[Cons(...)]`,
  `run-ir map.coreir got [List(...)] want [Cons(...)]`, and
  `kc-float Double math got [10 ...] want [10.0 ...]`.
- **Impact:** the self-hosted v2 target gate is red even though the Scala
  `v2/backend/check.sh` CoreIR source-generator harness is green. This blocks a
  credible Rust/WASM lane gate for Phase 4.
- **Fix direction:** update `v2/lib/backend-js-gen.ssc0` and
  `v2/lib/backend-rust-gen.ssc0` display helpers to match VM `Show.show`
  semantics for proper lists, and update `v2/conformance/check.sh` expectations
  for the accepted list/float display contract. Keep WASM expectations aligned
  because `ssc0-wasm` reuses the Rust generator.
- **Done-when:** `./v2/conformance/check.sh` no longer reports list/float display
  mismatches; JS/Rust/WASM target rows compare against the VM output.
- **FIXED (2026-07-08, `84d7ac77f`):** self-hosted JS and Rust target preludes
  now render proper `Cons`/`Nil` chains as `List(...)`, matching VM `Show.show`
  and the Scala source-generator backends. `v2/conformance/check.sh` was
  rebaselined to the accepted kernel display contract for proper lists and
  collapsed whole-float display.
- **Verified:** full `./v2/conformance/check.sh`; `./v2/backend/check.sh`;
  affected conformance
  `tests/conformance/run.sh --only 'effects,effect-*,async*,direct-*,js-*-effect-*,std-functor-applicative-monad,std-foldable-traversable,std-index' --no-memo`;
  `tests/conformance/run.sh --only 'rust*,wasm*' --no-memo` has no matching
  top-level cases, so Rust/WASM coverage is the v2 gate.

## root-test-js-rowpost-runtime-contract — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after bounded sbt Test concurrency was added.
- **Repro observed in root gate:** `backendInterpreter / Test / test` failed
  `scalascript.JsGenStdImportTest` case
  `JS signal runtime defines the std/ui row-data natives` at
  `JsGenStdImportTest.scala:403`: generated runtime did not contain the expected
  `_RowPost` body payload line `body: resolvePayload(r, act.bodyField)`.
- **Impact:** JS std/ui row-data runtime contract may no longer send row POST
  body payloads through the same resolver used by other row fields. If this is a
  true runtime regression, browser UI row actions can submit stale or unresolved
  bodies; if only the string assertion is stale, the production contract still
  needs a sharper test.
- **Fix direction:** run the focused `JsGenStdImportTest` filter, inspect the
  emitted JS runtime around `_RowPost` / `resolvePayload`, then either restore the
  payload resolution path or update the structural assertion to match the current
  equivalent implementation.
- **Done-when:** focused `JsGenStdImportTest` is green, the row POST body
  contract is covered by the test, and affected std/ui conformance runs before
  pushing.
- **FIXED (2026-07-08, `cea0c3aed`):** the runtime still resolves row POST
  bodies through `resolvePayload`; the old string assertion expected the whole
  object literal inline shape and missed the current `_postBody` local. The test
  now asserts the resolver assignment and the fetch body use separately.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest"`;
  affected conformance `tests/conformance/run.sh --only
  'collections,dataset-from-file,dataset-shape,json-*,std-ui-*,tkv2-*'
  --no-memo` (19/19); bounded root `scripts/sbtc "test"` (elapsed 1668s,
  success).

## root-test-sbt-aggregate-heap-oom — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: conformance
     fixed-in: unrecorded -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` root retest
  after v2 `V2ConformanceTest` was green and pushed through `ab37c7d0b`.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  scripts/sbtc "test"` on `origin/main@c9d300335`.
- **Observed failure:** the aggregate root test run progressed through many
  payments/crypto/config/server suites, then entered a long silent section with
  the sbt JVM pegged at >1000% CPU, ~5.7 GB RSS despite `-Xmx4G`, and 47 idle
  node children. It printed repeated
  `Exception in thread "pool-453-thread-..." java.lang.OutOfMemoryError: Java heap space`.
  `jcmd <pid> Thread.print` could not attach within 10.5s. Ctrl-C did not stop
  the run; SIGTERM removed the node children but the sbt JVM required SIGKILL.
- **Impact:** root `sbt "test"` is not yet a reliable production gate even after
  deterministic v2 conformance blockers are fixed.
- **Fix direction:** determine whether the failure is root-aggregate parallelism,
  Scala.js jsEnv node fan-out, or a specific test module leaking heap. Start by
  reproducing with a bounded/constrained root test invocation or focused
  Scala.js module groups, then encode the fix in build/test settings or the
  project wrapper. Record the exact command that becomes the production gate.
- **Done-when:** a root-equivalent gate completes without heap OOM/hung sbt JVM,
  and the chosen command is recorded in `SPRINT.md`/`CHANGELOG.md`.
- **Progress (2026-07-08, uncommitted worktree):** adding
  `Global / concurrentRestrictions += Tags.limit(Tags.Test,
  SSC_SBT_TEST_CONCURRENCY default 4)` to `build.sbt` made the next root
  `scripts/sbtc "test"` complete in about 27m32s without the previous OOM/hung
  sbt JVM pattern. The gate still exited 1 because it exposed the two separate
  blockers tracked above: `root-test-js-rowpost-runtime-contract` and
  `root-test-cli-fork-exit-after-green`.
- **FIXED (2026-07-08, `cea0c3aed`):** bounded root `scripts/sbtc "test"`
  completed successfully with the `Tags.Test` cap defaulting to 4. No heap OOM,
  hung sbt JVM, or lingering fork-exit failure remained.
- **Verified:** `cd /Users/sergiy/work/my/scalascript-wt-green-main-full-sbt-test-gating &&
  rm -f v1/tools/cli/ssc-storage.json && scripts/sbtc "test"`:
  `[success] elapsed: 1668 s (0:27:48.0)`.

## root-test-cluster-cli-runtime-readiness — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** codex, during `green-main-full-sbt-test-gating` root
  `scripts/sbtc "test"` after the bytecode split-runtime and Scala.js npm
  dependency fixes.
- **Repro observed in root gate:** the full root test PTY was lost before the final
  sbt summary, but the running output showed a deterministic cluster failure
  family: `ClusterStepDownCliTest`, `ClusterStatusCliTest`, and
  `ClusterAuthCliTest` fail because nodes do not bind within 8s; `MultiNodeClusterTest`
  fails to print `LEADER:` / `REMOTE_SPAWN_OK`; `ClusterBullyStatusConvergenceTest`
  never sees the status HTTP endpoint; `PartitionHealingTest` reports every node
  as `LEADER=<none>`; `SingletonFailoverTest` never propagates `SENT1:true`.
- **Targeted repro to run before fixing:** `scripts/sbtc "cli/testOnly
  scalascript.cli.ClusterStepDownCliTest scalascript.cli.ClusterStatusCliTest
  scalascript.cli.ClusterAuthCliTest scalascript.cli.MultiNodeClusterTest
  scalascript.cli.ClusterBullyStatusConvergenceTest scalascript.cli.PartitionHealingTest
  scalascript.cli.SingletonFailoverTest"`.
- **Initial hypothesis:** this is one cluster-runtime/CLI readiness family, not
  separate assertion issues. The subprocesses start and print the web banner, but
  the cluster markers/status endpoints are missing or late. Confirm whether the
  regression is runtime startup, test timeout/readiness detection, or an interaction
  with concurrent root-suite execution before changing semantics.
- **Status:** fixed in `da63bb96a`. Actual root cause was the v2 default switch in
  the fat-jar launch path: the cluster suites spawn node fixture scripts with
  `java -jar ssc.jar <node.ssc>`, so those v1 actor-cluster fixtures started on
  v2/default. Minimal repro showed `sendAfter` actor flows print under `--v1` but
  exit 0 with no delayed message under default/`--v2`. The test harness now runs
  node fixture subprocesses with explicit `--v1`; CLI subcommands such as
  `cluster status`, `cluster drain`, and `cluster step-down` still run normally
  against those nodes.
- **Verified:** `scripts/sbtc "cli/testOnly scalascript.cli.ClusterStepDownCliTest
  scalascript.cli.ClusterStatusCliTest scalascript.cli.ClusterAuthCliTest
  scalascript.cli.MultiNodeClusterTest scalascript.cli.ClusterBullyStatusConvergenceTest
  scalascript.cli.PartitionHealingTest scalascript.cli.SingletonFailoverTest
  scalascript.cli.ClusterDrainCliTest scalascript.cli.ClusterEventsCliTest
  scalascript.cli.PartitionTest"` (**13/13 green**); `tests/conformance/run.sh
  --only 'actors*,cluster-connect,distributed*' --no-memo` (**14 passed,
  0 failed**).

## conformance-jvm-std-ui-generated-braces — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 9bd6cb87d
     gate: tests/conformance/std-ui-extended.ssc -->

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc`.
- **Observed:** JVM generated Scala fails with `'}' expected, but eof found`; the
  warnings start where many imported `std/ui` component `object`s begin, so the
  conformance harness reports missing stdout for `std-ui-aggregator` and
  `std-ui-extended*`. INT/JS pass.
- **Status:** fixed in `9bd6cb87d`. Root cause was two string-level JVM source
  transforms treating braces inside imported UI triple-quoted JavaScript/CSS
  literals as Scala structure. `colonObjectsToBraces` also stopped collecting an
  `object Name:` body when a triple-quoted literal continued at column 0, which
  prematurely inserted `}` inside `SubmitButton.js`. `JvmGen` now tracks
  triple-quoted strings while collecting colon-object bodies and uses a shared
  string/comment-aware brace matcher for duplicate object/package merges.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenUsingTest"`
  (**14/14 green**); direct
  `bin/ssc run-jvm tests/conformance/std-ui-extended.ssc` after regenerating the
  stale local `.scjvm` artifact; and
  `tests/conformance/run.sh --only 'std-ui-aggregator,std-ui-extended*' --no-memo`
  (**5/5 green**).

## conformance-js-json-stringify-missing-global — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: 718d04027
     gate: tests/conformance/json-read.ssc -->

- **Found by:** codex, during full `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `bin/ssc run-js tests/conformance/json-read.ssc`.
- **Observed:** JS crashes before stdout with `ReferenceError: jsonStringify is not
  defined` at the first `jsonStringify(42)` call. INT/JVM pass.
- **Status:** fixed in `718d04027`; root cause was JS intrinsic registration still
  targeting bare `jsonStringify` / `jsonValue` after the runtime helpers were
  intentionally renamed to `_ssc_ui_jsonStringify` / `_ssc_ui_jsonValue` to avoid
  duplicate top-level declarations with std import bindings.
- **Verified:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest"`;
  `scripts/sbtc "installBin"`; `bin/ssc run-js tests/conformance/json-read.ssc`;
  `tests/conformance/run.sh --only 'json-read' --no-memo` (**1/1 green**).

## conformance-jvm-cps-local-unit-effect-cast — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/conformance/run.sh
     fixed-in: unrecorded -->

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `tests/conformance/run.sh --only 'cluster-connect' --no-memo`.
- **Observed:** the JVM lane compiled and ran, but printed
  `unhealthy nodes: 3` instead of `unhealthy nodes: 0`.
- **Root cause:** a local actor loop declared as `def workerLoop(): Unit =
  receive { ... }` inside a CPS block emitted as
  `Actor.receive_(...).asInstanceOf[Unit]`. The cast discarded the unresolved Free
  computation before `runActors` could schedule it, so the worker actors exited
  immediately and never answered the health-check messages.
- **Fix:** `df7cfb613` makes local CPS-emitted defs follow the same result-type
  rule as top-level effectful defs: preserve the declared return type only when
  the def handles its own effects; otherwise return the unresolved computation as
  `Any`.
- **Verification:** `bin/ssc run-jvm tests/conformance/cluster-connect.ssc` prints
  `unhealthy nodes: 0`; the full targeted slice
  `tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`
  passes 6/6.

## conformance-jvm-cps-any-typing-and-effect-args — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/conformance/run.sh
     fixed-in: unrecorded -->

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`.
- **Observed:** the JVM lane failed during generated Scala compilation. Examples:
  `effect-transitive-handler` widened a handled value to `Any` before `+`;
  cluster/distributed cases widened `cluster` to `Any` before method calls; and
  `DatasetWirePartition(_t197, _t198)` passed `Any` where the constructor expects
  `Int` and `Vector[JsonValue]`.
- **Root cause:** the CPS transform only preserved explicit val ascriptions.
  Untyped vals bound from known dep constructors/defs were passed to
  continuations as `Any`; casts for known constructors did not include the JVM
  runtime `DatasetWirePartition` case; and effectful lambdas nested under call
  argument clauses could stay raw because effect detection only recursed through
  direct `Term` children and intrinsic dispatch emitted `.syntax` args.
- **Fix:** `df7cfb613` infers CPS val continuation types from known dep class/def
  result signatures, qualifies dep type names at generated call sites, adds the
  `DatasetWirePartition` external constructor signature, recursively detects
  effects through non-`Term` tree nodes, and routes effectful call args through
  CPS emission.
- **Verification:** `scripts/sbtc "backendInterpreter/compile"`,
  `scripts/sbtc "installBin"`, and
  `tests/conformance/run.sh --only 'cluster-connect,distributed-failure-*,distributed-heterogeneous,distributed-shuffle,effect-transitive-handler' --no-memo`
  pass.

## conformance-http-client-external-httpbin — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: front
     gate: tests/conformance/http-client.ssc
     fixed-in: unrecorded -->

- **Found by:** codex, while refreshing `green-main-conformance-gating`.
- **Repro:** after `scripts/sbtc "installBin"`,
  `scripts/conformance -- --only 'http-client' --no-memo`.
- **Observed:** the fixture calls live `https://httpbin.org`; the INT lane returned
  five `503` statuses instead of `200/204`, and the JS lane produced no stdout and
  stalled until interrupted. This is an external-network fixture, not a deterministic
  default conformance gate.
- **Fix:** mark `tests/conformance/http-client.ssc` as `pending:` with an explicit
  reason. Follow-up: replace it with a local deterministic HTTP fixture before
  re-enabling it in default conformance.
- **Verification:** `scripts/conformance -- --only 'http-client' --no-memo` reports
  `PENDING` and exits green without hanging.

## conformance-parsing-int-empty-output — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: front
     gate: tests/conformance/run.sc
     fixed-in: unrecorded -->

- **Found by:** codex, while running a neighbor conformance slice for
  `v2-prod-js-dsl-conformance`.
- **Repro:**
  `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
- **Observed:** `collections` and `dsl-multi-pass` pass, but three INT-only parsing
  cases produce empty output:
  `parsing-error-node`, `parsing-parse-all`, and `parsing-recover-until`. JS/JVM are
  skipped for those cases by backend metadata, so this is not the JS char-ordering
  failure and not a v2 default output-parity blocker.
- **Scope:** std/parsing conformance hygiene. Fix before claiming broad repo-wide
  conformance green; do not mix with the v2 default-switch slice unless its gate
  explicitly requires these parser-combinator cases.
- **Root cause:** `std/parsing/recovery.ssc` documented and defined
  `recoverUntil`, `errorNode`, `parseAll`, `advanceToSync`, and `runParserAll`, but
  its front-matter `exports:` omitted those public names. The conformance files
  explicitly import `runParserAll` / `advanceToSync`, so INT failed during import on
  stderr before any `println`, which the conformance harness reported as missing
  stdout.
- **Fix:** `d65c678bd` exports the recovery extension methods and runner helpers
  from `std/parsing/recovery.ssc`.
- **Verification:** after `scripts/sbtc "installBin"`,
  `bin/ssc run --v1 tests/conformance/parsing-error-node.ssc` prints the expected
  eight lines; `scala-cli tests/conformance/run.sc -- --only 'parsing*' --no-memo`
  passes all three INT parsing cases; the neighbor slice
  `scala-cli tests/conformance/run.sc -- --only 'dsl*,collections,parsing*,indent*' --no-memo`
  passes 5/5 runnable cases with the two indent cases skipped for missing expected
  files.

## v2-content-toolkit-section-parity — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded -->

- **Found by:** codex, during `v2-prod-post-p3-baseline` full-corpus production
  parity verification.
- **Symptom:** the latest full `scripts/v2-output-parity --all` has exactly one
  v2-error: `examples/content-toolkit-yaml-controls.ssc`. The same content/toolkit
  section family also has a mismatch in `examples/content-slot.ssc`, where v2 prints
  an extra `Unsupported: TermSelectPostfixImpl` before the expected
  `content-slot:ok` line.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `bin/ssc run --v2 examples/content-toolkit-yaml-controls.ssc` and
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/content-slot.ssc examples/content-toolkit-yaml-controls.ssc`.
- **Observed direct error:** `contentToolkitNode: table column builder 'fieldColumn'
  is not available — import it from std/ui/data (fcol/mcol/scol/dcol/lcol)`.
- **Root cause:** the v2 plugin `MinimalCtx` did not implement `resolveGlobal` or
  `invokeCallback`, so the real content plugin could not call imported toolkit
  builders such as `fieldColumn` while lowering inline YAML table columns. The
  sibling `content-slot.ssc` mismatch was a separate FrontendBridge lowering issue:
  `[bodyEl]` after the spaced infix operator in `headerParts ++ [bodyEl] ++
  footerParts` was not desugared as a list literal, so scalameta produced an
  unsupported `TermSelectPostfixImpl` node.
- **Fix (7dee6daf0):** `MinimalCtx` now resolves v2 globals and invokes v2/v1
  callbacks with value conversion; FrontendBridge treats spaced operator-following
  list literals as expression-position list literals and has a regression test for
  `xs ++ [y]`.
- **Verification:** `bin/ssc run --v2 examples/content-toolkit-yaml-controls.ssc`
  and `bin/ssc run --v2 examples/content-slot.ssc` both print only the expected
  `:ok` line; targeted parity for both examples is **2/2 MATCH**; `scala-cli
  tests/conformance/run.sc -- --only 'content*' --no-memo` passes **5/5**; full
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity --all` has
  **0 v2-error** and now measures **57/81 identical · 8 mismatch · 16 v1-only**.

## v2-content-document-context — `fixed` (2026-07-08)
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded -->

- **Found by:** codex, during the `v2-production-readiness` output-parity baseline.
- **Symptom:** `ssc run --v2` produced non-v1 output for structured Markdown content
  examples: `content-linked-namespaces.ssc` leaked a `Stub`/`Op` shape instead of the
  imported section title, `content-to-markdown.ssc` rendered empty content, and
  `content-tables.ssc` differed on the toolkit table value.
- **Repro:** after `scripts/sbtc "installBin"`, run:
  `PARITY_TIMEOUT=45 SSC="bin/ssc" scripts/v2-output-parity examples/content-linked-namespaces.ssc examples/content-tables.ssc examples/content-to-markdown.ssc`.
- **Root cause:** the v2 bridge populated only the current source document and then
  let batch content stubs override the real content plugin natives. The FrontendBridge
  import walk also did not register imported Markdown documents under
  `ContentImportedModules`, so `contentModuleSection` had no namespace table. After
  enabling the real content plugin path, bridged println still rendered
  `TableNode.sortCol` as `None` where v1 case-class output uses `null`.
- **Fix (146779cb6):** `PluginBridge.setDocumentFromSource` resets/seeds content
  document/current-section context, `FrontendBridge` registers imported content
  documents by namespace, content introspection/module/markdown natives use the real
  plugin path, and bridge display preserves the v1 `TableNode(..., null)` rendering.
  Only `contentToolkitSection` remains a selective batch stub until section-level
  toolkit lowering is fixed.
- **Verification:** `examples/content*.ssc` parity is **10/10 identical** (one
  v1 long-running skip), `scala-cli tests/conformance/run.sc -- --only 'content*'
  --no-memo` passes **5/5**, and the full parity gate is **54/88 identical ·
  10 mismatch · 1 v2-error · 23 v1-only**.

## scalajs-jsenv-run-terminated — `fixed` (2026-07-07)
<!-- status: fixed
     lane: js
     area: conformance
     gate: tests/conformance/run.sh
     fixed-in: unrecorded -->

- **Found by:** claude (green-main takeover), root `sbt test` + serial retest.
- **Symptom:** Scala.js test modules (walletVaultEncryptedJs, walletStrategyErc4337Js,
  blockchainEvmAbiJs, markupNode, cryptoNobleJs, walletConnectJs) die in `loadedTestFrameworks`
  with `JSEnvRPC$RunTerminatedException` / `ExternalJSRun$NonZeroExitException: exited with code 1`
  — the node process exits immediately. Reproduces SERIALLY (not load-related) on node v26.4.0
  locally AND in CI (18 occurrences in the failed CI log).
- **Root cause:** the Node process was exiting because required npm packages were not installed in
  the module-local `node_modules` trees. The first concrete repro was `cryptoNobleJs/test` failing
  with `MODULE_NOT_FOUND: '@noble/ciphers/aes'`; the other failing Scala.js modules had the same
  manual-install assumption for their `package.json` dependencies.
- **Fix:** `1da48bfd5` adds an idempotent `npmInstallForScalaJsTest` sbt task and wires it into
  `Test / loadedTestFrameworks` for the npm-dependent Scala.js test projects, so clean worktrees and
  CI run `npm ci` automatically before the Scala.js test runner loads.
- **Verified:** `scripts/sbtc "cryptoNobleJs/test"`;
  `scripts/sbtc "walletVaultEncryptedJs/test; walletStrategyErc4337Js/test; blockchainEvmAbiJs/test; walletConnectJs/test; markupNode/test"`;
  `tests/conformance/run.sh --only 'std-semigroup-monoid' --no-memo`.
- **Repro:** `scripts/sbtc "cryptoNobleJs/test"` in a clean worktree with no
  `payments/crypto/noble-js/node_modules` previously failed before the fix.

## jsgen-signal-type-import-vs-preamble — `fixed` (2026-07-07)
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/conformance/tkv2-component.ssc
     fixed-in: unrecorded -->

- **Found by:** claude (tkv2-components slice) — first .ssc module importing the opaque
  `Signal` TYPE from `std/ui/primitives.ssc` and emitting to JS.
- **Symptom:** `ssc emit-js` of any file importing `[Signal, …](std/ui/primitives.ssc)`
  dies on Node with `SyntaxError: Identifier 'Signal' has already been declared` —
  the import emits `const Signal = std.ui.primitives.Signal`, colliding with the
  signals.mjs preamble `function Signal`.
- **Root cause / fix:** the `jsgen-toplevel-name-vs-preamble` (#5) class. `Signal` is
  now pre-seeded into `declaredBindings` (like the std/fs file-ops): the import const
  is skipped; type positions erase, and value uses correctly resolve to the preamble
  reactivity constructor. `JsGen.scala` declaredBindings init.
- **Guard:** `tests/conformance/tkv2-component.ssc` (imports the type transitively via
  `std/ui/component.ssc`, INT==JS).

## jsgen-reserved-param-body-rename — `fixed` (2026-07-07)
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/conformance/tkv2-component.ssc
     fixed-in: unrecorded -->

- **Found by:** claude (tkv2-components slice) — `std/ui/component.ssc`'s
  `ctxSignal(ctx, name, default)` parameter named `default`.
- **Symptom:** a def with a JS-reserved-word parameter (e.g. `default`), emitted through
  the namespace/object member path (`const f = (a, b, default_p) => …`), renames the
  formal via `safeJsParam` but NOT the body references — the body emits bare `default`
  → Node `SyntaxError: Unexpected token 'default'`.
- **Root cause / fix:** the object-member def emission built `bodyJsRaw` without
  `withParamRenames` (unlike the top-level def paths at JsGen.scala:2462-2482). Now the
  same `objDefRenames` map wraps body generation. Any .ssc module function with a
  reserved-word param was affected on the JS lane.
- **Guard:** `tests/conformance/tkv2-component.ssc` (ctxSignal carries a `default`
  param, INT==JS).

## green-main-full-sbt-test-gating — `fixed` (2026-07-07)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: cea0c3aed -->

- **Found by:** codex, while verifying the `plugin-cli-oslib-shadow` fix.
- **Symptom:** after the `PluginCliTest` compile blocker is fixed, the root
  `sbt "test"` gate is still red in unrelated integration suites.
- **Repro:** `cd /Users/sergiy/work/my/scalascript-wt-finish-green-main && sbt "test"`.
  The second full run completed in 29:08 with non-zero exit. It confirmed
  `PluginCliTest` now passes, then reported:
  - `CrossBackendIntrinsicParityTest`: JS-only drift for `webauthnConfigureStore`
    and `webauthnStoreRemove`.
  - `JvmGenSwingRuntimeTest`: failed inside the `cli / Test / test` aggregate.
  - `StableSpiEnforcementTest`: `tcp-plugin` still imports
    `scalascript.interpreter.Value` from a value-surface plugin.
  - `AgentConformanceTest`: suite aborted with `java.net.BindException:
    Address already in use` in `beforeAll`.
  - JS test-framework fallout: several Scala.js modules report
    `RPCCore$ClosedException` after a Node-side non-zero exit.
- **Notes:** the first full run hit a transient Scala 3 compiler crash in
  `clientEvm/Test/compile`; targeted `clientEvm/Test/compile` passed immediately,
  so the durable gate is the second run's failure set above.
- **Status:** fixed in `cea0c3aed`; the root `sbt "test"` gate is green.
- **Progress (2026-07-07, `8dfd2989e`):** `CrossBackendIntrinsicParityTest`
  fixed by documenting `webauthnConfigureStore` and `webauthnStoreRemove` as
  JS-core/JVM-`auth-plugin` exceptions; targeted parity test passes.
- **Progress (2026-07-07, `484d56101`):** `StableSpiEnforcementTest` fixed by
  migrating `tcp-plugin` from direct `scalascript.interpreter.Value` constructors
  to `PluginValue`; `StableSpiEnforcementTest` and `tcpPlugin/test` pass.
- **Progress (2026-07-07, `395e8aab3`):** `JvmGenSwingRuntimeTest` fixed by
  replacing the local `v1`-anchored repo-root finder with `TestPaths.repoRoot`;
  targeted Swing runtime test passes 5/5.
- **Progress (2026-07-07, `eae491e11`):** `AgentConformanceTest` fixed by
  binding its mock OpenAI gateway to a loopback ephemeral port instead of
  hard-coded `19694`; targeted conformance test passes 3/3.
- **Progress (2026-07-07, `7e2650e2c`):** `PluginBridgeTest` fixed by aligning
  the test stub with the bridge's stable SPI raw-value contract
  (`IntV` args arrive at `NativeImpl` as `Long`, and raw `Long` returns wrap back
  to v2 `IntV`); targeted `v2PluginBridge/testOnly ssc.bridge.PluginBridgeTest`
  passes 22/22.
- **Progress (2026-07-07, `2e1f2c287`):** `V2ConformanceTest` fixed by letting
  real `.ssc` `Defn.Def` bodies shadow same-named plugin globals after
  `stripExternDecls`; `std/mcp/types.ssc` `requireString` now wins over the
  `validate {}` helper in mcp imports. Also renamed the conformance fixture's
  local `args` to `mcpArgs` so the JS lane avoids the known
  `jsgen-toplevel-name-vs-preamble` collision. Verified full
  `v2FrontendBridge/testOnly ssc.bridge.V2ConformanceTest` (62/62) and
  `scripts/conformance -- --only mcp-types --no-memo` (INT/JS pass).
- **Remaining targeted blockers (2026-07-07):**
  `backendWasm/testOnly scalascript.codegen.WasmBackendTest` still has 7
  effectful-WASM failures: handler/resume and `String*` effectful mains print
  empty output or throw under Node v26.4.0, arithmetic/HOF effect bodies print
  empty output, and the cross-module imported-effect case prints empty output.
  Scala.js `loadedTestFrameworks` fallout still needs re-checking after the
  deterministic JVM/v2/WASM failures are fixed.
- **Final verification (2026-07-08, `cea0c3aed`):** full `cli/test` is green
  (554 succeeded, 29 canceled, 0 failed), affected conformance
  `collections,dataset-from-file,dataset-shape,json-*,std-ui-*,tkv2-*` is green
  (19/19), and bounded root `scripts/sbtc "test"` is green
  (`[success] elapsed: 1668 s (0:27:48.0)`).

## js-spa-hashchange-bridge-sync — `fixed` (2026-06-29)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Reported by:** Sergiy, from the rozum Unified Control Center (`clients/control/control-center-live.ssc`).
- **Symptom:** clicking a hash-route navigation control changed `location.hash`, but the visible SPA route did
  not switch until a manual browser refresh. The reactive `hashSignal()` / `computedSignal` graph updated, but
  mounted `data-ssc-cond` branches still kept their previous `display:none` / `display:contents` state.
- **Repro:** mount a JS browser SPA with `hashSignal()` feeding an `eqSignal` / route guard, then change
  `window.location.hash` and dispatch `hashchange`; before the fix the computed signal value changed while the
  mounted branch styles did not.
- **Root cause:** `_ssc_ui_hashSignal()` already registered a `hashchange` listener and updated the reactive
  graph, but `_ssc_ui_mount()` only pushed computed values from the reactive graph into the DOM bridge store
  (`_sv` / `_sb`) through `_syncBridgeSignals()` after bridge-owned `_set(...)` calls. Native browser
  `hashchange` events never called that bridge sync.
- **FIXED (2026-06-29, `23789503d8b9c2a4cba41545ba5ae7ba0219bc1b`):** `_ssc_ui_mount()` now listens for
  `hashchange` and calls `_syncBridgeSignals()`, so `data-ssc-cond`, signal text, and other mounted bridge
  subscribers observe hash-derived computed changes without a refresh.
- **Guard:** `JsGenStdImportTest` now dispatches `hashchange` after mount and asserts the `data-ssc-cond`
  branches toggle. Also re-ran `SpaComputedBodyBridgeTest` to cover the adjacent computed-to-bridge path.

## jsgen-emitjs-capability-standalone — `fixed` (2026-06-22)
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded -->

- **Found by:** busi (deep-offline browser/Node bundle) — the standalone-bundle frontier after `jsgen-emitjs-effect-handler`: `inbox`/`ksef`/`repo*` (clock) and the crypto path failed under raw `ssc emit-js | node`, while the JIT path (`SSC_JIT_BACKEND=js`) was green.
- **Symptom (two distinct bugs):**
  - **(clock)** A `RuntimeCall` intrinsic (`nowMillis` → `Date.now`) called inside an *effectful* (CPS-lowered) function emitted the bare source name (`nowMillis()`) → `ReferenceError: nowMillis is not defined`. `dispatchIntrinsicJs` rewrote it for `Term.Apply` sites in `genExpr`, but `genCpsApply`'s "regular call" path didn't.
  - **(crypto)** Importing a `std/crypto` extern (`[sha256](std/crypto.ssc)`) emitted `const sha256 = std.crypto.sha256` AND added `sha256` to `declaredBindings` (disabling the `sha256` → `_sha256` intrinsic rewrite at call sites). The namespace member was `(typeof _ssc_ui_sha256 !== 'undefined') ? _ssc_ui_sha256 : undefined` = `undefined` under Node → `not callable: ()`.
- **FIXED (2026-06-22):**
  - **(clock)** `genCpsApply` now handles `Term.Name(fname)` whose `intrinsicRuntimeTarget(fname)` is defined: it binds the args CPS-style and emits `target(args)` (e.g. `Date.now()`). New `private[codegen]` helper `JsGen.intrinsicRuntimeTarget`.
  - **(crypto)** In `genObjectAsExpr`, an extern namespace member falls back to its `RuntimeCall` intrinsic target (`_sha256`) instead of `undefined` when the host UI stub is absent — guarded by an inner `typeof` (stays `undefined` if the target isn't emitted) and by `target != fname` (so identity intrinsics like std/auth's `webauthnChallenge` don't self-reference → TDZ). Browser still prefers the `_ssc_ui_*` host stub.
- **Guards:** `tests/conformance/js-cps-intrinsic-rewrite.ssc` (nowMillis in a CPS body) + `tests/conformance/js-crypto-extern-standalone.ssc` (`sha256("abc")` standalone), both INT==JS. busi standalone `ssc emit-js tests/v2/<f>.ssc | node` sweep: **13/21 → 20/21** v2 domain files (only `auth` remains — its WebAuthn externs are host-only, no Node preamble, a separate feature). busi `make v2-test` + `make v2-test-js` green (26 files); before/after emit-js+node sweep over all conformance tests: **zero PASS→FAIL regressions** (84→84).
- **Still open (separate):** `auth.ssc` standalone needs Node WebAuthn impls (`webauthnChallenge`/`webauthnVerify*` are identity-`RuntimeCall` host externs with no `_webauthn*` preamble). `jsgen-toplevel-name-vs-preamble` (#5, general preamble-shadow) also still open.

## jsgen-emitjs-effect-handler — `fixed` (2026-06-22)
<!-- status: fixed
     lane: js
     area: codegen
     gate: tests/conformance/effect-import
     fixed-in: unrecorded -->

- **Found by:** busi (deep-offline browser bundle) — blocker #3 of 5 in `src/v2/specs/lf-1-browser-bundle.md`. Only the raw `emit-js` standalone path was affected; the JIT path (`SSC_JIT_BACKEND=js`) was always green.
- **Symptom:** raw `emit-js` of code using an effect + deep handler (an effectful `query` that folds over `Eff.read`, run inside a `handle`) failed at runtime — `TypeError: arr.reduce is not a function`, or `Unhandled effect: …`.
- **FIXED (2026-06-22) for the direct + single-import case — two layers:**
  - (a) **Imported effect now recognised.** `genImport` runs `analyzeEffects` on the imported module and merges the discovered `effectOps`/`effectfulFuns`/`multiShotEffects` back into the importer, so an effect-performing function defined in an imported module (e.g. `query` calling `Box.read`) lowers its op to `_perform`+`_bind` (not a generic `_dispatch`) and is CPS-transformed — the `_Perform` no longer leaks into the fold.
  - (b) **Effectful lambdas emit a CPS body.** `genExpr` for `Term.Function` now emits the body via the CPS path when `jsForTermPerforms(body)` — so an effect-performing call in a handler-body thunk (`runBox(() => query(...))`) returns the Free computation for the handler to interpret instead of being `_run`-wrapped (which threw "Unhandled effect"). (`jsForTermPerforms` made `private[codegen]`.)
  - **Guard:** `tests/conformance/effect-imported-handler.ssc` (+ `lib/effect-box.ssc`) — an imported effect + generic effectful reader + deep handler, run twice; INT==JS==JVM. busi `make v2-test-js` (full effectful v2 core on JS) green; `CrossBackendPropertyTest` effect cases green. Single-file and 2-level-import effect+handler code now runs under raw `emit-js`.
- **FIXED — transitive multi-level imports (3+ levels) (2026-06-22, whole-program pass).** Spec `specs/emitjs-effect-whole-program.md`. `JsGen.analyzeEffects` now collects trees across the ENTIRE import graph (recursively resolve imports — reusing `genImport`'s resolution — parse each once, visited-set for diamonds/cycles) and runs `EffectAnalysis.analyze` on the union, so a function calling a transitively-imported effectful function (busi: `ledger.accountBalance` → `journal.query` → `Journal`) is marked effectful and CPS-lowered. `effectOps`/`effectfulFuns`/`multiShotEffects` are now SHARED constructor params threaded to child generators (like `topLevelConsts`), populated once by the entry generator's whole-program pre-pass — every module emits against the same view; the per-`genImport` `analyzeEffects`+merge (the single-import fix) is dropped as redundant. **Result:** `ssc emit-js tests/v2/ledger.ssc | node` runs end-to-end (all checks pass), as do obligation/plan/payment/gate/income standalone. Guard: `tests/conformance/effect-transitive-handler.ssc` (+ `lib/eff-a.ssc`, `lib/eff-b.ssc`), INT==JS==JVM; busi `make v2-test` + `make v2-test-js` green; cross-backend green.
- **Remaining busi standalone-bundle frontiers — UPDATED 2026-06-22 (claude-code, `fix/js-standalone-frontiers`).** All three originally-listed frontiers are now CLOSED under raw `emit-js | node`:
  - `trust.ssc` ✅ — the CPS gap was a **unary operator on an effectful operand** (`!x` / `-x` where the operand performs an effect) falling through to `genExpr`, which `_run`-wrapped it and ran the effect outside the handler. `Term.ApplyUnary` now CPS-lowers via `_bind(operand, v => op(v))`. Guard: `tests/conformance/js-applyunary-effect-cps.ssc`.
  - `qr.ssc` ✅ — the `Method not found` was `Array.fill(n)(x)` (+ `tabulate`/`range`/`empty`): `Array(...)` emits a JS array literal, so the bare `Array` value at a `_dispatch` site is the native constructor, which lacks the Scala statics. `_dispatch` now routes these to the `List` companion (shared JS array repr). Guard: `tests/conformance/array-companion-statics.ssc`. (Plus the `fn-typed-field` dispatch refinement above.)
  - `ksef.ssc` ✅ (syntax) — the duplicate global `const readFile` is gone: the std/fs file-ops (`readFile`/`writeFile`/`exists`/… 14 names) are extern decls whose real impl is the preamble (`JsRuntimeFs`), so they're seeded into `declaredBindings` and never re-emitted as a colliding top-level `const`. `node --check` now passes. This closes the std/fs subset of the `jsgen-toplevel-name-vs-preamble` (#5) class.
- **New frontier exposed (next):** `ksef.ssc`/`inbox.ssc`/`repo*` now reach runtime and hit `ReferenceError: nowMillis is not defined` / `not callable: ()` — the `nowMillis` clock capability (`JsCapabilities`: `QualifiedName("nowMillis") -> RuntimeCall("Date.now")`) is wired on the JIT path but not emitted into the raw `emit-js` preamble; `auth.ssc` hits a similar crypto-capability gap. **This overlaps the active `core-min-clock-env-migrate` (Clock/Env→plugin) work and is left for that stream / a follow-up.** Standalone emit-js+node sweep: **85/113 conformance + 13/21 busi v2 domain files** pass; the rest are clock/crypto capability gaps + infra (actors/cluster/distributed/sql).

## jsgen-toplevel-name-vs-preamble — `fixed` (2026-06-22)
<!-- status: fixed
     lane: js
     area: front
     gate: tests/conformance/mcp-types.ssc
     fixed-in: unrecorded -->

- **Found by:** busi (deep-offline browser bundle) — blocker #5 of 5 in `src/v2/specs/lf-1-browser-bundle.md`.
- **Symptom:** a top-level user binding named exactly like a preamble helper (e.g. `val scope = …` vs the runtime's user-facing `function scope(scopeName)` for CSS scoping, SPEC §8.4) emits a colliding top-level `const scope = …` → `SyntaxError: Identifier 'scope' has already been declared` under `node --check`. Other preamble names (`doc`, `escape`, `assert`, `List`, `Decimal`, …) can collide the same way.
- **Additional repro (2026-07-07):** `scripts/conformance -- --only mcp-types`
  passes INT but JS fails before printing anything because
  `tests/conformance/mcp-types.ssc` used `val args = ...`, colliding with the
  JS preamble's `function args()` from `std/os`. The conformance fixture
  workaround landed in `2e1f2c287` (`mcpArgs`); the broad top-level
  name-mangling fix is now covered by this entry.
- **Additional repro (2026-07-09):** after enabling an expected file for
  `tests/conformance/litdoc.ssc`, `bin/ssc emit-js tests/conformance/litdoc.ssc
  | node` failed with `SyntaxError: Identifier 'doc' has already been declared`
  because the fixture has top-level `val doc = parseDoc(md)`, colliding with the
  JS preamble surface.
- **Fixed subset (2026-07-09, `782f07438`):** JS generation now derives the
  runtime top-level declaration set and renames colliding user top-level
  `val`/`var` bindings plus normal references. This fixes the litdoc `val doc`
  repro and is guarded by `JsGenStdImportTest` plus
  `tests/conformance/run.sh --only 'litdoc' --no-memo`. This left
  non-`val`/`var` declaration forms for the follow-up fixed below.
- **Fixed remainder (2026-07-09, `854a87f1b`):** top-level JS collision
  handling now covers user/import bindings that actually emit flat-scope JS:
  `def`, `@js`/`@jvm` extern stubs, `object`, case class constructors, enum
  companions/cases, explicit named givens, and import aliases. Emission and
  call sites share the same `emittedName` map; recursive/effect/TCO analysis
  still keys off original source names. Object collisions now create a renamed
  binding instead of mutating the runtime helper with `Object.assign(scope, ...)`.
- **Root cause:** the first fix only renamed `val`/`var`; other declaration
  emitters and several direct call-site fast paths still used the source name,
  so a renamed `def doc` declaration would still call the runtime `doc(...)`.
- **Guards:** `scripts/sbtc "backendInterpreter/testOnly scalascript.JsGenStdImportTest"`
  (49/49), `tests/conformance/run.sh --only 'litdoc' --no-memo` (INT/JS/JVM),
  and `tests/conformance/run.sh --only 'mcp-types' --no-memo` (INT/JS; JVM
  intentionally skipped by fixture frontmatter).

## jsgen-fn-typed-field-autoinvoke — `fixed` (2026-06-22)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** busi (deep-offline browser bundle) — facet of blocker #4 of 5 in `src/v2/specs/lf-1-browser-bundle.md` ("a generic `View.step` fold reaches syntax-valid JS but the `step` field is not callable").
- **Symptom:** a case-class field whose value is a function (e.g. `View.step: (S, Int) => S`), passed as a *value* to a HOF (`xs.foldLeft(v.init)(v.step)`), threw `TypeError: fn is not a function` on the JS backend. A direct call `v.step(1, 2)` worked; only the eta/value position failed. interp + JVM were correct.
- **Root cause:** lambdas are emitted variadic (`(...__a) => …`, to support tuple-destructuring), so their `.length` is 0. Accessing the field as a value lowers to `_dispatch(v, 'step', [])`, and `_dispatch`'s zero-arg branch auto-invoked any property whose `typeof === 'function' && .length === 0` — so it CALLED the variadic field-lambda (no args → NaN) instead of returning it.
- **FIXED (2026-06-22):** in `_dispatch`, a no-arg property access on a case-class / enum instance (`obj._type !== undefined`) returns the data field as-is — case-class methods live in `_extensions`, never on the object, so an existing own-property is always a data field and must never be auto-invoked. Direct calls (`args.length > 0`) and all non-`_type` objects are unchanged.
- **REFINED (2026-06-22):** the blanket `obj._type !== undefined && args.length === 0 → return as-is` guard was too broad — it also suppressed *genuine* zero-arg methods that SHOULD auto-invoke (`JsonValue.asString` and friends, emitted as a real `() => …` / `function(){…}` own-property), so `tests/conformance/json-value.ssc` failed under raw `emit-js`. The guard is replaced with a precise test inside the function branch: a zero-arg-arity own-property is returned as a reference only when its source is a **variadic-emitted lambda** (`(...__a) => …`, detected via `Function.prototype.toString`); a genuine zero-arg function is still auto-invoked. Net: `json-value` now passes standalone (FAIL→PASS in the emit-js+node conformance sweep) while `fn-typed-field` stays green; before/after sweep over all 113 conformance tests showed **zero PASS→FAIL regressions**.
- **Guard:** `tests/conformance/fn-typed-field.ssc` (variadic field as value) + `tests/conformance/json-value.ssc` (genuine zero-arg method auto-invoke), INT==JS==JVM. busi `make v2-test-js` + `CrossBackendPropertyTest`/`MoneyCrossBackendTest`/`CustomDerivesMirrorCrossBackendTest` green; `tests/v2/qr.ssc` now runs as a raw `emit-js` standalone bundle.

## jsgen-dup-enum-global — `fixed` (2026-06-22)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** busi (deep-offline browser bundle) — blocker #2 of 5 in `src/v2/specs/lf-1-browser-bundle.md` ("emit-js / emit-spa for tests/v2/local_journal.ssc fails syntax checks with duplicate global `Pending` declarations from ObligationStatus.Pending and DeferredActionStatus.Pending").
- **Symptom:** two enums (in the same file or different modules) that share a *parameterless* case name each emitted a top-level global `const <Case> = {_type:'<Case>', _tag:N}`; child generators share the global scope, so the bundle had a duplicate `const` and Node rejected it: `SyntaxError: Identifier 'Pending' has already been declared`. `SSC_JIT_BACKEND=js` was fine; only raw `emit-js`/`emit-spa` failed (`node --check`).
- **Root cause:** the top-level (`genStat`) `Defn.Enum` emission unconditionally emitted `const <Case>`/`function <Case>` per case. Enum-case tags are global-by-name, so the two `Pending` objects are byte-identical; qualified refs already go through the companion (`_dispatch(ObligationStatus, 'Pending', [])`), not the bare global.
- **FIXED (2026-06-22):** a shared `declaredEnumCases: Set[String]` (threaded to child gens like `declaredBindings`) skips re-declaring a global enum-case binding already emitted by another enum; each companion still references the surviving (structurally identical) global. Only the global `genStat` path is guarded — module-IIFE (`genObjectAsExpr`) enum cases are scoped and don't collide. JIT/JVM/interp paths untouched.
- **Guard:** `tests/conformance/enum-shared-casename.ssc` (+expected) — two enums with a shared `Pending` case; within-enum equality + `.values.size` identical on INT/JS/JVM (cross-enum equality is intentionally NOT asserted: after dedup the JS objects are shared, which never matters in well-typed code). `EnumCrossBackendTest` 3/3; busi `tests/v2/local_journal.ssc` emit-js now passes `node --check`.

## effect-op-trailing-comment — `fixed` (2026-06-20)
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded -->

- **Found by:** busi (building the v2 KSeF inbound port `effect Ksef`).
- **Symptom:** a trailing `//` line-comment on an effect operation's declaration silently broke the
  WHOLE effect. `effect Ksef:` / `  def pull(t: String, s: String): List[String]  // FA(3) docs`
  made `Ksef` parse as a plain object, so every `Ksef.pull(...)` perform threw
  `No method 'pull' on InstanceV(Ksef)` at runtime (the handler never caught it). Root: `preprocessEffects`
  appended the synthetic `= __effectOp__` body at the absolute end of the op line, so a trailing comment
  swallowed it → the op had no body → not an effect op. The same `!bodyLine.contains("=")` guard also
  wrongly skipped an op whose param had a function type (`f: Int => Int`).
- **FIXED (2026-06-20):** `preprocessEffects` now splits off any trailing line-comment first
  (`splitLineComment`, string-literal aware) and inserts `= __effectOp__` into the CODE part, before the
  comment; the "already has a body" check ignores `=>`. Guard: `PreprocessEffectsTest` (7 cases). 53
  existing effect/parser tests green; real-harness repro now returns the handler's value, not a throw.

## jsgen-module-section-scope — `fixed` (2026-06-22)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** busi (deep-offline browser bundle) — the #1 raw `emit-js` full-bundle blocker codex recorded in `src/v2/specs/lf-1-browser-bundle.md` ("importing `std/money.ssc` fails at runtime with `Currency` not initialized before `defaultCurrencies`").
- **Symptom:** any program that `emit-js`'d a markdown module split across sections (e.g. `std/money`, whose `Currency`/`Money` constructors are under one heading and `defaultCurrencies`/`currencyOf` under another) threw on Node — `ReferenceError: Currency is not defined`, or (when reached via the import binding) `not callable: ()`. `SSC_JIT_BACKEND=js` (the JIT path) was fine; only raw `emit-js`/`run-js` failed.
- **Root cause:** each module section is emitted by a *separate* child `JsGen` sharing `topLevelConsts`; the first declares `const std = (()=>{ const money = (()=>{ function Currency… })(); … })()` and later sections merge via `_ssc_mergeDeep(std, (()=>{ const money = (()=>{ … defaultCurrencies = … Currency … })(); … })())`. Each section's IIFE is its own lexical scope, so a later section's bare reference to an earlier section's `Currency` had nothing to resolve to — even though `std.money.Currency` existed at runtime.
- **FIXED (2026-06-22):** a shared `namespaceMembers: Map[path, Set[name]]` (threaded to child gens like `topLevelConsts`) records the members each section declares per namespace path. When emitting a section, `genObjectAsExpr(d, path)` prepends `const { <prior members not declared here> } = <path>;` (e.g. `const { Currency, Money } = std.money;`) so cross-section references resolve from the live, already-merged namespace. `mergeDeep` is unchanged. Keeps the JIT path identical.
- **Guard:** `tests/conformance/money-multisection.ssc` (+ `expected/money-multisection.txt`) — imports `std/money`, calls `currencyOf` (which reaches `Currency` via `defaultCurrencies` and via its `getOrElse` fallback); runs identically on INT/JS/JVM. `MoneyCrossBackendTest` "money.ssc — JS output matches the interpreter" + busi `make v2-test-js` (full v2 core on JS) stay green.

## collection-ctor-aliases — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** a collections survey (prompted by a "do we only have List/Map?" question).
- **Symptom:** despite the user guide listing `Seq`/`List`/`Vector`/`Set`/`Array`/`Map`, the interpreter only had `List`/`Map`/`Set` companions — `Seq(1,2,3)`, `Vector(...)`, `Array(...)`, `IndexedSeq(...)` all threw `Undefined: Seq` (etc.); `.toVector`/`.toSeq`/`.toIndexedSeq` and `Map.toSeq` were also missing. (JVM, real Scala, was fine.)
- **FIXED (2026-06-15):** the interpreter backs every sequence type with a single `ListV` (JS with arrays), so `Seq`/`Vector`/`Array`/`IndexedSeq`/`Iterable`/`LazyList` companions now alias `List`'s (`BuiltinsRuntime`), JsGen emits those constructors as arrays, and `toList`/`toSeq`/`toVector`/`toIndexedSeq`/`toArray`/`toIterable` are identity conversions on List + Map (interp `dispatchList`/`dispatchMap`, JS array/Map `_dispatch`). On the **JVM backend** each stays its REAL Scala type (raw emit — `Vector(1,2,3)` → a real `Vector`, etc.); a guard asserts JvmGen preserves the companion call so a future change can't silently collapse them to List. Guard: `CrossBackendPropertyTest` "Seq/Vector/Array constructors + conversions cross-backend" (9 shapes incl. LazyList, interp == JS == JVM). Caveat: off-JVM these are NOT distinct runtime types (Vector/Array = List/array, `LazyList` is eager — an infinite LazyList won't work off-JVM). Available collections: List, Map, Set, Seq, Vector, Array, IndexedSeq, Iterable, LazyList, plus Option, Either, Tuple, Range.

## jsgen-enum-payload-extract — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-6 enum probes).
- **Symptom:** matching an `enum` case WITH a payload bound the wrong value on JS — `enum Shape: case Circle(r: Int); … case Circle(r) => …` bound `r` to the case's `_tag` (0/1), not the field. `area(Circle(2)) + area(Square(3))` gave `1` instead of `21`; interp + JVM correct. `genPattern`'s Extract used field NAMES from `caseClassFieldsByType` when known, else the positional `Object.values(scrut).slice(1)[i]` — but enum cases carry an extra `_tag` field, and `caseClassFieldsByType` was populated only for `Defn.Class`, not enum cases, so `slice(1)[0]` returned `_tag`.
- **FIXED (2026-06-15):** `caseClassFieldsInModule` now also indexes `Defn.Enum` cases (name → field list), so enum-case Extract binds by field name. Guard: `CrossBackendPropertyTest` "enum payload, collect, Option.fold cross-backend" (enum-payload-match + enum-nullary).

## interp-collect-partial / jsgen-collect-partial — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-6 collection probes).
- **Symptom:** `xs.collect { case x if x % 2 == 0 => x * 10 }` (a partial function with a guard) threw `Match failure: 1` in the INTERPRETER (it called the PF as a total function), and on JS threw `Method not found: collect` (no `collect` in the array `_dispatch`); JVM correct. `collect` must SKIP elements the PF isn't defined on.
- **FIXED (2026-06-15):** interp — a `collectStep` helper catches the located "Match failure" and skips (reusing the existing `None`-skip path). JS — added a `collect` array-dispatch case that calls the element fn and skips when it throws a "Match failure" (the emitted PF closure's no-match error). Guard: `CrossBackendPropertyTest` collect-guard.

## jsgen-option-fold-curried — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-6 Option probes).
- **Symptom:** `Some(5).fold(0)(x => x * 2)` failed on JS — the curried `Option.fold(ifEmpty)(f)` was absent from the `_Some`/`_None` dispatch (only `Either.fold(fa, fb)` uncurried was present). interp + JVM correct.
- **FIXED (2026-06-15):** added `fold` to the JS Option dispatch — `_Some`: `(f) => f(value)`, `_None`: `(f) => ifEmpty` — handling the curried second clause. Also added `exists`/`forall` and fixed `Some.contains` to use structural `_eq`. Guard: `CrossBackendPropertyTest` option-fold-some/-none.

## xbackend-range-by-step — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-6/7).
- **Symptom:** `(0 to 10 by 2)` — a Range with a `by` step — threw on interp (`No method 'by' on List`) and on JS; JVM correct. interp + JS materialize a Range as a List/array, which had no `by`; the JS `by` infix also fell to an invalid `(range by step)` emission.
- **FIXED (2026-06-15):** `by(step)` keeps every step-th element of the materialized range — added to interp `dispatchList`/`dispatchList1` and the JS array `_dispatch`; JsGen now emits the `by` infix as `_dispatch(range, 'by', [step])`. Guard: `CrossBackendPropertyTest` "ranges, collection + string method gaps cross-backend" (range-by-sum/-until).

## jsgen-collection-method-gaps / jsgen-string-padto — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-7).
- **Symptom:** several stdlib methods were missing from the JS `_dispatch` (and one from interp), failing with `Method not found` / `No method`: `List.scanLeft`/`scanRight`/`indexWhere`, tuple `.swap`, `String.padTo`; interp also lacked `indexWhere`. interp + JVM (or JVM alone) were correct.
- **FIXED (2026-06-15):** added JS array dispatch `scanLeft`(curried)/`scanRight`/`indexWhere`/`swap`, JS string `padTo` (Char arg arrives as a char-code number), and interp `indexWhere` (`dispatchList`). Guard: `CrossBackendPropertyTest` string-pad / list-scanleft / list-indexwhere / tuple-swap.

## jvmgen-autooutput-after-classdef — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-5 case-class probes).
- **Symptom:** a JVM program with a top-level `case class` (or trait/object) followed by ANY auto-output/expression statement printed NOTHING — `case class P(x: Int)\nprintln(if P(1) == P(1) then 10 else 0)` produced empty output; interp + JS correct. `wrapAutoOutput` emitted a bare `{ … }` block, and `case class P(x: Int)` on one line followed by `{ … }` on the next is parsed by Scala as **P's body template**, so the statement was swallowed (never run).
- **FIXED (2026-06-15):** `wrapAutoOutput` now emits `locally { … }` (an unambiguous method call) instead of a bare `{ … }`, so the block can't attach to a preceding definition. Guard: `CrossBackendPropertyTest` "collections, case-class equality, num+string cross-backend" (caseclass-eq/-ne/-output).

## jsgen-structural-equality — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-5 case-class probes).
- **Symptom:** `==` on the JS backend used JS reference equality (`===`), so two structurally-equal case-class instances / tuples / Lists compared unequal — `P(1) == P(1)` → `false`; interp + JVM correct.
- **FIXED (2026-06-15):** added a `_eq(a, b)` deep-structural-equality runtime helper (arrays elementwise, objects by `_type` + own keys, primitives by `===`) and routed `_arith('==' / '!=', …)` through it. Also used for Set dedup. Guard: `CrossBackendPropertyTest` caseclass-eq/-ne, tuple-eq.

## jsgen-set-constructor — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-5 Set probes).
- **Symptom:** `Set(1, 2, 3)` failed on JS with `TypeError: Constructor Set requires 'new'` — JsGen had `Map`/`List` constructor cases but no `Set`, so `Set(...)` fell through to the JS global `Set`.
- **FIXED (2026-06-15):** added a `Set(...)` / `Set[T](...)` case emitting `_setOf(...)` — a runtime helper that builds a structurally-deduplicated array, so the existing array `_dispatch` methods (`size`/`toList`/`sorted`/`contains`/…) apply. Guard: `CrossBackendPropertyTest` set-dedup-ops, set-contains.

## interp-num-string-concat — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-5 Map probes).
- **Symptom:** `6 + "_"` (a number `+` a String — Scala's `any2stringadd`) threw in the interpreter (`No method '+' on IntV`); JS + JVM correct. interp's `Int + …` only handled numeric operands.
- **FIXED (2026-06-15):** `dispatchInt` / `dispatchInt1` now concatenate when the `+` operand is a `StringV` (`n.toString + s`). Guard: `CrossBackendPropertyTest` num-string-concat.

## js-supertype-typetest — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** busi (UI session). A `cardWithHeader(header)` card title rendered on **no**
  screen in the SPA — money, compliance, and the new UA ФОП cockpit alike — while the card
  body rendered fine and the interpreter (`ssc render`) was correct, so every `.ssc` test
  passed. Browser DOM inspection showed the card-header `<div>` absent; the page heading
  (`thView(2,…)`) and standalone section headings (`thView(3,…)` in a vstack) rendered.
- **Symptom:** on the **JS backend**, a type-test against a supertype — sealed trait /
  parent enum / abstract class — never matches a subtype instance. `sealed trait TkNode;
  case class HeadingNode(t) extends TkNode; (x: Any) match { case h: TkNode => … }` skips the
  `TkNode` arm for a `HeadingNode`. Emitted objects carry only their leaf `_type`
  (`{_type:'HeadingNode'}`); `JsGenCpsCodegen.genPattern`'s `Pat.Typed` branch emitted an
  exact `scrut._type === 'TkNode'` check, which a subtype never satisfies. `cardWithHeader`
  lowers `header match { case h: TkNode => render; case _ => [] }` (header field typed `Any`),
  so the title fell to the empty wildcard. The JS analogue of the interp/JIT fix for #1/#3.
- **FIXED — single-module (commit 775a10e68):** scanned type decls + `extends` into
  `supertypeName → Set[concrete leaf _type]` per module; `genPattern`'s `Pat.Typed` widens a
  no-tag (supertype) check to an `_type` OR over that closure. Guard `SupertypeTypeTestJsTest`.
- **FIXED — cross-module (follow-up):** the first commit was insufficient for the actual busi
  case and the single-module test gave **false confidence**. The JS backend emits each imported
  module with a *fresh child `JsGen`* (genImport), and `TkNode` + subtypes live in `nodes.ssc`
  (a `package:` module) while `case h: TkNode` lives in `lower.ssc` — so the importer's matcher
  had no record of the subtype graph and still fell back to the broken exact check (browser
  re-verify after the rebuild still showed dropped titles + `_type === 'TkNode'` in the emitted
  SPA). Fix: accumulate the subtype edges ACROSS imports — `collectSubtypeEdgesFromModule`
  (descends into `package:` wrapping objects) + `recomputeSubtypeClosure`, folded in for the
  entry module and, in genImport, for each imported module + propagated into the child gen
  (mirrors `importedParamOrder`). Guard `SupertypeTypeTestXModuleJsTest` (multi-file: imported
  `package:` trait/subtypes + transitive enum across the import boundary) — the multi-file test
  the `bugs` rule requires. Spec `specs/js-supertype-typetest.md`.
- **Repro:**
  ```scalascript
  sealed trait TkNode
  case class HeadingNode(text: String) extends TkNode
  def isTk(x: Any): String = x match
    case h: TkNode => "tk"
    case _         => "other"
  println(isTk(HeadingNode("hi")))  // interp/JVM: "tk" ; JS (buggy): "other"
  ```

## jsgen-collection-dispatch-gaps — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-4 collection-HOF probes).
- **Symptom:** `xs.sortWith((a,b) => a < b)`, `xs.sorted`, `xs.partition(p)` fail on the JS backend (node) — they were simply MISSING from the `_dispatch` runtime method table (`JsRuntimePart2b.scala`); interp + JVM correct. `val (a, b) = xs.partition(…)` then also failed for lack of `partition`.
- **FIXED (2026-06-15):** added `sortWith` (`lt(a,b)?-1:lt(b,a)?1:0`), `sorted`, `partition` (→ `[yes, no]`), and `span` to the JS `_dispatch` array-method table. The `val (a, b) = …` tuple destructuring already works (`genPatDestructure`). Guard: `CrossBackendPropertyTest` "collection HOFs and pattern matching cross-backend".

## jsgen-match-guard-bind — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (wave-4 pattern-match probes).
- **Symptom:** a `match` with a case GUARD (`case x if x < 0 => …`) fails on the JS backend (node syntax error); interp + JVM correct. `genMatchAsStmts` and the coroutine `genGenStmt` match dropped `c.cond` entirely, so a guarded `case x if …` got pattern-cond `"true"` and was treated as a catch-all mid-chain → malformed `{ … } else if (…)` JS. (`genReceiveMatcher` ANDed the guard but evaluated it with the pattern bindings out of scope.)
- **FIXED (2026-06-15):** all three JS match paths now fold the guard into the arm condition via an IIFE that scopes the pattern bindings: `(cond) && (() => { <bindings>; return (<guard>); })()`. Guarded arms are no longer mistaken for catch-alls (the switch fast-path also excludes them since the cond is no longer `"true"`). Guard: `CrossBackendPropertyTest` "collection HOFs and pattern matching cross-backend" (match-guard-bind shape).

## xbackend-wave4-jvm-transient — `wontfix` (2026-06-15, not reproduced)
<!-- status: wontfix
     lane: js
     area: codegen -->

**Classified 2026-08-02 from the entry's own content**, which is unusually clear about it: the two
shapes failed ONCE, did not reproduce on a clean re-run (interp == JS == JVM all green), and the
failure coincided with contending `sbt`/`scala-cli` processes corrupting temp compiles. No code
changed; the shapes were kept as cross-backend guards. `wontfix` is the enum value for exactly
that — a report closed without a fix because there was nothing to fix.

- Two wave-4 shapes (`xs.zip(ys).map((a,b)=>a+b).sum`, `(1,(2,3)) match { case (a,(b,c)) => … }`) reported a JVM `scala-cli failed` ONCE, but did NOT reproduce on a clean re-run (interp == JS == JVM all green). The original failure coincided with two contending `sbt`/`scala-cli` processes corrupting temp compiles. Kept as cross-backend guards in "collection HOFs and pattern matching cross-backend"; no code change.

## jvmgen-js-curried-partial — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (main-path edge-case probes).
- **Symptom:** PARTIAL application of a curried def fails on the **JS backend** (`not callable: NaN`); interp + JVM are correct. `def add(a: Int)(b: Int) = a + b; val f = add(3); f(4)` — JsGen flattens curried params to `function add(a, b)`, so `add(3)` runs the body with `b === undefined` → `3 + undefined` = `NaN`. FULL application `add(1)(2)` works (it arrives flattened as `add(1, 2)`); only under-applied calls break. Reproduced for 2- and 3-clause defs.
- **FIXED (2026-06-15):** added a `_curry(fn, arity, args)` JS runtime helper (accumulates args, applies when arity reached) and an auto-curry guard at the top of plain multi-clause def emission: `if (arguments.length < N) return _curry(fname, N, arguments);`. Only emitted for multi-clause defs with no defaults / using / context-bounds; single-clause defs and full applications are unaffected (arity already reached). Guard: `CrossBackendPropertyTest` "curried partial application cross-backend" (2-/3-clause, full + partial, interp == JS == JVM).

## effect-perform-in-fordo — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` (effects-in-HOF/loop probes).
- **Symptom:** an effect op performed inside a `for i <- 0 until n do …` loop diverged across all three backends. interp was CORRECT; **JVM** failed scala-cli (`None of the overloaded alternatives of method + in class Int` — `acc + Counter.tick()` where `tick()` is the Any `_perform`), and **JS** printed garbage (`0[object Object][object Object]…`). The `while`-loop form of the same program works on all backends (dedicated CPS while-trampoline); the `for … do` → `foreach(i => …)` desugar did NOT CPS-thread the effect in the closure body. `.map` / `.foldLeft` closures DO thread effects — only `foreach`-from-`for-do` was broken.
- **FIXED (2026-06-15):** added for-do recognizers to BOTH CPS emitters (`JvmGenCpsTransform.emitCpsExpr` + `JsGenCpsCodegen.genCpsExpr`) that desugar to the same while-trampoline the `while` form uses, so the body's `perform`s thread through `_bind`:
  - **Range** `for i <- (lo until/to hi) do body` → index `var`/`let` + trampoline (covers `until` exclusive + `to` inclusive + bodies reading the loop var).
  - **Collection** `for x <- coll do body` (pure non-Range `coll`) → `.iterator` (JVM) / array-index (JS) + trampoline.
  Multi-generator / guarded / complex-pattern for-do falls through to the existing (raw / `_forEach`) path unchanged. Guard: `CrossBackendPropertyTest` "effect perform in for-do loop cross-backend" — 5 shapes (range until/to/loop-var + collection elem/side-effect), interp == JS == JVM.
- **Repro:**
  ```scalascript
  effect Counter:
    def tick(): Int
  def prog(): Int ! Counter =
    var acc = 0
    for i <- 0 until 3 do
      acc = acc + Counter.tick()
    acc
  println(handle(prog()) { case Counter.tick(resume) => resume(5) })  // interp: 15 ; jvm: COMPILE ERROR ; js: garbage
  ```

## jvmgen-returnclause-effect-in-recursion — `fixed` (2026-06-15)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Found by:** `CrossBackendPropertyTest` diagnostic (return-clause shape localization).
- **Symptom:** a return-clause handler over a **recursive** effectful function fails JVM scala-cli compilation: `Found: (_t3 : Any) / Required: Int`. **interp and JS both produce the correct result.**
- **Repro:**
  ```scalascript
  effect Log:
    def emit(): Int
  def go(n: Int): Int ! Log =
    if n <= 0 then 0
    else
      Log.emit()
      go(n - 1)
  def prog(): Int ! Log =
    go(3)
  val xs = handle(prog()) {
    case Log.emit(resume) => 7 :: resume(())
    case Return(_) => List()
  }
  println(xs.length)   // interp/js: 3 ; jvm: scala-cli COMPILE ERROR
  ```
- **Root cause:** the CPS transform emits `def go(n: Int): Any = _bind(..., (_t3: Any) => go(_t3))` — the recursive call passes the Any-typed `_bind` continuation result `_t3` to `go`, whose param stays declared `Int`. The existing `applyCalleeCasts` (which casts CPS call args to the callee's declared param types) only consulted `depDefs`/`depClasses` (IMPORTED deps), never the user module's own defs, so a recursive/sibling call got no cast. (Widening the param to `Any` is NOT a valid fix — params keep their declared type so field access like `node.nodes` type-checks; the design casts at call sites instead.)
- **FIXED (2026-06-15):** added `localDefSigs` — a pre-pass index of the user module's own `Defn.Def`s — and made `applyCalleeCasts` / `calleeParamType` / `calleeTypeArgMap` consult it as a fallback after `depDefs`. `go(_t3)` now emits `go(_t3.asInstanceOf[Int])`. Guard: `CrossBackendPropertyTest` "effect return-clause cross-backend (direct / recursion)" (interp == JS == JVM). 120 effect/CPS unit tests stay green.

---

## interp-typed-data-not-callable (a.k.a. bare-fn-ref auto-invoke) — `fixed` (2026-06-13, `175c01d72`)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Root cause (narrowed):** NOT a rare typed-data construct — it was the common
  `xs.foreach(println)` idiom. Normalize rewrote **every** bare `println` → `Console.println`
  (a `Select` to an InstanceV native-fn field); the interpreter evaluates a bare member `a.b`
  as a 0-arg field access, so `Console.println` was auto-invoked → `()` → `Not callable: ()`.
  Minimal repro: `List("a","b").foreach(println)` and `val f = println; f("x")`.
- **Fixed:** Normalize now rewrites `println`/`print` to `Console.*` **only when applied**
  (a `(?=\s*\()` lookahead). A bare reference stays the plain name → every backend binds it
  to the intrinsic function value (interp globals, JVM Predef, JS `_println`, Rust intrinsic
  table). Surgical: only `println`/`print`, so paren-less 0-arg method calls like
  `gen.zipWithIndex` are untouched (an earlier dispatch-level `bareSelect` attempt regressed
  exactly those — reverted). Regress: `BugReproTest` (foreach(println), val-bound println,
  explicit `println()`/`println(x)`, `nanoTime()`); `examples/typed-data.ssc` runs end-to-end
  and is now in `ExamplesSmokeTest`'s curated run-set (which goes through Normalize); Rust +
  JS codegen + interp suites green.

## js-self-handling-cps-fn-not-run — `fixed` (2026-06-12)
<!-- status: fixed
     lane: js
     area: codegen
     fixed-in: unrecorded -->

- **Fixed:** `JsGen.runIfEffectful` wraps a non-CPS-context call to an effectful
  function in `_run`, so a self-handling CPS fn's lazy `_FlatMap` resolves at the
  value boundary (`println(workload())`). `_run` is idempotent on an already-resolved
  plain value (so a direct-runner result like `_handleOneShot(…)` is unaffected) and
  throws loudly on an unhandled effect; CPS-context calls go through `genCpsApply`,
  never `genApply`, so they're untouched. Verified via node: non-loop self-handling →
  3, multi-shot handled-in-while → 204, one-shot regression → 5. backendInterpreter 1678
  green. Regress: `JsEffectLoopTest` (self-handling + multi-shot). **effect-multishot now
  runs on JS.** Diagnosis below.
- **Found:** while landing `effect-cps-loops-js` (the perform-in-while lowering).
- **Symptom:** on the **JS backend only**, a function that handles its OWN effects
  internally (so it has no unresolved `perform`) but is still CPS-emitted (because its
  body contains `handle`/effect machinery) returns an **un-run lazy `_FlatMap`**. A
  value-position call to it (`println(workload())`) prints `[object Object]` instead of
  the result. Blocks the `effect-multishot` corpus on JS (and any self-handling block).
- **Repro (JS only; jvm + interp are correct):**
  ```scalascript
  multi effect NonDet:
    def choose(options: List[Int]): Int
  def program(): Int ! NonDet =
    val a = NonDet.choose(List(1, 2, 3))
    a
  def workload(): Int =
    val all = handle(program()) { case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt)) }
    all.length
  println(workload())   // JS: prints [object Object]; expected 3
  ```
  Note: NO `while` needed — this is **not** a perform-in-loop bug; the `while` fix is
  orthogonal. `effect-oneshot` (where `workload` is a *direct* `handle(...)` → a runner
  call → plain value) works on JS.
- **Root cause:** JS `_bind(c, f)` is **always lazy** (`return new _FlatMap(c, f)`),
  unlike JVM's `_bind` which is eager on a non-`Perform` value. A CPS'd self-handling
  function's chain has no `Perform` nodes, so on JVM it eager-resolves to a plain value,
  but on JS it stays a lazy `_FlatMap` that nothing runs at the (non-CPS) call site.
- **Verified fix hypothesis:** wrapping the value-position call in `_run` resolves it
  (`_run(workload())` → 3 / 12 / 204). The fix is to emit `_run(...)` at a non-CPS value
  boundary for a call whose result is a CPS'd (effectful) function — `_run` is idempotent
  on plain values, so it's safe for the direct-runner case too. Needs care in `genApply`
  to avoid wrapping calls that are themselves inside a CPS context (those go through
  `genCpsApply`). HIGH-ish risk — gate on the full effect suite + node tests.

## v2-db-url-scheme-not-jdbc — `databases:` front-matter parser only recognizes `jdbc:`-prefixed URLs
<!-- status: fixed
     lane: js
     area: front
     fixed-in: unrecorded
     gate: tests/conformance/v2-db-url-scheme-not-jdbc.ssc -->

**Status:** FIXED 2026-07-09 (1e43ba347) — registerDb normalizes sqlite:/h2:/postgres(ql)/mysql to jdbc:; guarded by
tests/conformance/v2-db-url-scheme-not-jdbc.ssc)

busi's `databases:` convention uses the `sqlite:` scheme (`sqlite::memory:`,
`sqlite:./data.db`, `sqlite:${env:VAR}` — v1's own JsGenSqlBlockTest /
WasmBackendSqlTest pin this as first-class, tested, alongside `jdbc:`). v2's
ad hoc line-by-line parser (`FrontendBridge.parseDatabasesFromFrontmatter`,
v2/frontend-bridge/.../FrontendBridge.scala ~line 129) only calls
`PluginBridge.registerDb` when `rawUrl.startsWith("jdbc:")` — any other
scheme is silently skipped (the `if` guard no-ops; nothing is logged). At
runtime `Db.query`/`Db.execute` hits `MinimalCtx.dbConnect`, finds nothing
registered, and crashes: "No database registered for '<name>' — add a
databases: section to front-matter" — even though the front-matter IS
present and correct. This is the busi hub-boot-adjacent `repo_sqlite_index`
v2-sweep failure. Repro: `bin/ssc --v2
tests/conformance/v2-db-url-scheme-not-jdbc.ssc`. Fix candidate: recognize
`sqlite:` (and ideally any scheme, deferring validity to the JDBC driver)
instead of hardcoding `jdbc:`.
Found+minimized 2026-07-09 by busi (fable).

## js-passerror-tostring-not-used — DUPLICATE of js-pass-error-not-formatted-by-its-module-function
<!-- status: fixed
     fixed-in: 4b92ac864
     lane: js
     area: front -->

**Filed and closed the same day, by two agents, from opposite ends.** I filed this from the
`dsl-mini-language  js  DIVERGE` row in Corpus Contract run 30542056391 and spent the narrowing effort
before checking `git log` on the suspect file — `4b92ac864` had already fixed it, 47 minutes earlier,
while I was reducing. The operational lesson, worth more than the entry: **before narrowing a bug from
a nightly run, `git log -S` the suspected file — the run can be an hour stale.** Checking claims and
heartbeats is not the same check.

Verified independently against a build of current `main`: `dsl-mini-language` int == js on stdout, the
contract row is closed.

**One measurement from the reduction is kept because it is not in the other entry — the
discriminator is a SINGLE LINE.** Bisecting `std/dsl/passes.ssc` down: the trigger is the front-matter
`package:` declaration. Remove that one line and the `override def toString` is honoured; keep it and
the js lane prints the raw record. A local case class was always fine, which is what kept it hidden.
Three earlier reduction attempts (same-file case class, explicit `.toString`, an imported module via a
relative `[..](./x.ssc)` link with the same default arguments and branching body) ALL rendered
correctly on both lanes — so any test that exercises one emission path proves nothing about the other.

**The fix shipped without a test**, and the only thing that had ever noticed the defect was a corpus
row `contract.sc` was SKIPping. Gate added: `JsPackagedBodyMethodTest` (in the interpreter module's
tests, where `JsGen` is already unit-tested, so no freeze file is touched). It asserts the
`_registerExt('toString', …, 'PassError')` registration on the top-level path, on the `package:` path,
and — the shape that made the bug possible — that the two paths AGREE.
