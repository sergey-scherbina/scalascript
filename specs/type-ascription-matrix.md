# Type-ascription census — what `case _: T` answers on every lane

One probe, fourteen values, four lanes, taken 2026-07-31 on a build of current `main`. The JVM lane
runs real Scala and is the oracle.

```
value        jvm (oracle)  int      native   js
Unit         Unit          Unit     Unit     Unit
Boolean      Boolean       Boolean  Boolean  Boolean
Int          Int           Int      Int      Int
Double       Double        Double   Double   Int      ✗   (fixed — see below)
String       String        String   String   String
Char         Char          Char     Char     String ✗  (native fixed 08-10)
Map          Map           Map      Map      Map
Set          Set           Set      List  ✗  -      ✗
List         List          List     List     -      ✗
Vector       Vector        Vector   List  ✗  -      ✗
Option       Option        Option   Option   Option     (fixed 07-31)
Tuple2       Tuple2        Tuple2   Tuple2   Tuple2
Tuple3       Tuple3        Tuple3   Tuple3   Tuple3
Box (user)   Box           Box      Box      Box
```

**INT matches the oracle on all fourteen.** After the fixes of 2026-07-31 native diverges on three
and js on four — and **every remaining divergence is a missing TYPE, not a missing arm**. The
closable ones are closed: `Map` (js), `List` (native), `Int`-vs-`Double` (js), `Option` (both). What
is left needs a representation before a type test can answer at all.

## Why this exists as a census and NOT as one conformance case

That was the original plan and the measurement killed it. A corpus case has **one golden shared by
all lanes**, and `known-red:` is declared **per lane, for the whole case**. A 14-row matrix would
therefore record exactly one fact — "native DIVERGEs" — and would then be **blind to a new
divergence on native**, because that lane is already red. A gate that cannot see the state it exists
for is the failure mode this repository keeps paying for; building one deliberately would be worse
than none.

So: the census lives here, the per-type cases pin the rows where the lanes AGREE
(`type-ascription-unit`, `-tuple`, `-set`, `-map`, `-list`, `-number`), and each divergence is a
tracker entry. A row that gets fixed graduates into its own case.

## The three kinds of divergence, which need different work

**1. A missing table arm — cheap, closable.** The lane CAN represent the type; the test simply has
no arm. `Map` on js, `List` on native and `Option` on BOTH were this, and all are now fixed. Option
is the clearest case of the distinction this census exists to draw: it looks exactly like `Set` from
the outside — a container answering nothing — but `Some`/`None` carry `DataV` tags on native and
`_type` markers on js, so the question HAS an answer there while `Set`'s does not.

**2. A missing DISTINCTION — looks like an ordering bug.** `Int` and `Double` on js shared one
predicate (`typeof x === 'number'`), so the two arms were the same test and **whichever came first
won**: `1.5` answered `Int` with the arms one way round, `7` answered `Double` the other way.
Adding arms would not have helped — the order was carrying the answer. Fixed by reconstructing the
distinction from integrality, with a stated limit: `2.0` is indistinguishable from `2` in JS.

**3. A missing TYPE — not fixable in a type test at all.** The value simply is not distinct on that
lane, so any arm would have to lie:

- native: `Set(1,2)` **prints** `List(1, 2)` — a Set IS a list there;
- js: `List(...)` is `[...args]` and `_setOf(...)` is a plain array too;
- js: a char literal is a plain string, so `Char` and `String` are one value.

These need a representation before the question means anything. Answering `other` is the honest
result meanwhile — better than a confident wrong type.

**A row in this kind can GRADUATE, and one did — which is the reason to re-measure a census instead
of citing it.** `native: a char is an int (v2-char-is-an-int)` was listed here on 2026-07-31 and was
true then. `f39448c96` later moved `charAt` onto `CharV extends IntV`, giving the lane a distinct
Char class — so by 2026-08-10 the type EXISTED and only the test was missing, which is kind 1, a
one-line arm. Measured 08-10 on a fresh build, native and bytecode answered `Int` for a char and the
fix was ordering the `CharV` arm before `IntV` in `__isTag__`. js's Char RESULT (`s.charAt(0)`, a
`_Char` box) was kind 1 as well and answered `other`; only the js LITERAL is still kind 3.

The lesson is about the census, not about Char: **a "not fixable" verdict is dated evidence.** It
records what the lane could represent on the day it was taken, and a representation landing
elsewhere silently promotes the row. Anything reading this file should re-measure the row it cares
about before concluding the work is impossible — three months of this file said a one-line fix
could not be done.

## What this census is FOR

Five defects of this one shape were found between 2026-07-29 and 07-31, one probe at a time, each
costing a claim, a build and a case: `Unit` (int+js), `TupleN` (int+js), `Set`, `Map` (js), `List`
(native). **One 14-row probe found nine**, including six that appeared in no tracker entry at all.

The lesson generalises past this table and is worth more than it: a differential suite compares the
lanes **to each other**, so a gap they all share reads as green. `Set` survived exactly that way.
Pinning expectations against **Scala** — the JVM lane — is what makes a shared gap visible, and it
costs one extra column.
