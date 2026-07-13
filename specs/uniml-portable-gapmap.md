# UniML × ScalaScript v2 — compile gap map (uniml-portable Phase 0.5)

Goal of the `uniml-portable` program: UniML's single Scala 3 source compiles both with standard
scalac **and** with the self-hosted ScalaScript v2 compiler. This document is the measured baseline —
what v2's Scala-flavored `.ssc` frontend accepts today, and the concrete gaps that must close for it
to compile UniML. It is the TODO for the v2-side track (`uniml-portable-3-v2compile`).

## UPDATE 2026-07-13 (after the immutable rewrite, Phase 1 + 1d): the wall moved

The Phase-0.5 "wall" below (mutable object fields → needs mutable-`Array`/anon-trait/plain-class v2
support) is **obsolete for UniML**: Phase 1 + 1d rewrote all of UniML to be immutable, so the
standalone lib (`uniml/core` + json/yaml/markdown) now uses **no** `new Array`, **no** anonymous
`new Trait:`, and **no** mutable object fields. Re-probing the *immutable* core against v2 found:

- **`uniml/core` is otherwise v2-ready.** v2 already accepts everything the immutable core uses:
  generic `case class`es, a generic **3-param** `trait Proc[S,I,O]` + `object` impl + dispatch,
  `enum` with payloads + nested `match`, case-class `.copy` (named arg), `Option.forall`,
  `var`/`while`, and `Vector` literals + `:+` / `.last` / `.length` / `.size` / `.isEmpty` /
  `.nonEmpty` / `.updated`. (Probes: `probe-core.ssc` and friends.)
- **The one real blocker was `Vector`/`List` `.dropRight` (and `.takeRight`) — not implemented in v2**
  (`no dispatch for .dropRight on <foreign>`). This matters because the immutable rewrite uses
  `xs.dropRight(1)` as the **stack-pop idiom** everywhere (`TreeVm` frame stack, `XmlScanner` element
  stack, `MarkdownBlocks` container stack). `.drop`/`.take`/`.init`/`.last` were already supported.
- **Fixed v2-side (this commit):** added `dropRight`/`takeRight` to the `isList` method block in
  `v2/src/Runtime.scala` (2 additive cases mirroring `drop`/`take`). The full core probe now runs
  end-to-end on v2 (`run=a:2`, `merged=1,2,2`).

**Remaining UniML-side for full dual-compilation** (the DIALECTS, not the core): plain `class`es in
`YamlSemanticParser`/`YamlStructure` (v2 gap: plain class), regex `.matches` (YAML scalar typing),
`java.lang.Character.getType`/`isSpaceChar` (CommonMark flanking) + `Character.digit`, and local
`scala.collection.mutable` collections in the projection/structure/semantic layers. These are the
`uniml-portable-1c-compat` scope; the core does **not** need them.

### Dialect gaps — fully probed 2026-07-13 (the 1c-compat surface)

Confirmed by isolated `.ssc` probes (each a real v2 crash/miss, not a guess):

| construct | v2 result | UniML users | fix |
|---|---|---|---|
| `StringBuilder()` | `unbound global: StringBuilder` | all 4 lexers' token buffer, `MdLine.split`, `MarkdownInlines` | UniML-side: `Vector[String]` + `.mkString` (v2-supported, fully immutable) |
| `ArrayBuffer.empty` | `unbound global: ArrayBuffer` | `JsonStructure`, structure/projection/semantic layers | UniML-side: immutable `Vector` |
| plain (non-case) `class` | crash (compile) | `YamlSemanticParser.Parser`/`FlowParser`, `YamlStructure.BlockFrame` | UniML-side: immutable rewrite (nested defs / case classes) |
| regex `"…".r` / `.matches` | `no dispatch for .r` | `YamlSemanticParser` scalar typing (7 patterns) | UniML-side: hand-rolled char-scan predicates |
| `Character.getType`/`isSpaceChar`/`digit` | unresolved `Op(...)` | `MdChars` (CommonMark flanking), `YamlSemanticParser` hex | UniML-side portable Unicode table (**the hard one**) OR v2-side `Character` |
| mutable `var` field in case class | v2 object-model wall | `JsonStructure.Frame`, similar | UniML-side: immutable state (copy-on-transition) |

Two more v2 collection gaps surfaced probing YamlStructure:
- **`.indices`** → `no dispatch for .indices on <foreign>`. FIXED v2-side (additive `isList` case in
  `Runtime.scala`, mirroring `dropRight`).
- **`.groupBy`** → returns a **list of `(key, values)` pairs, not a `Map`**, so `m.getOrElse(k, …)`
  yields `Stub`. `.sortBy`/`.filter`/`.map`/`.exists` work. Two shapes of use: `groupBy(_.k).getOrElse`
  (Map — INCOMPATIBLE, rewrite to `.filter`) vs `groupBy(_.k).toVector.sortBy(_._1).foreach{(k,v)=>}`
  (iterate pairs — COMPATIBLE with v2's list-of-pairs).

Note: v2 supports `Vector`/`List` `:+` `.mkString` `.head` `.last` `.dropRight`(added) `.indices`(added)
`.copy` `.sortBy` `.filter` `.map` — so `StringBuilder`→`Vector` and `ArrayBuffer`→`Vector` compile on v2.

**JSON dialect — 1c DONE (this commit).** JSON's only gaps were `StringBuilder` (JsonLexer) +
`ArrayBuffer`/mutable-`Frame.state` (JsonStructure) — no regex/Character/plain-class. Both rewritten
immutably with v2-supported constructs (JsonLexer buffer → `Vector[String]`; JsonStructure → nested
defs + `Vector` stack, `Frame` states → immutable case classes, transitions via `dropRight`+`:+`
copy). Green unimlJson 16/16 JVM+JS. So core + JSON dialect are now v2-construct-free. (Gold-standard
full-concatenation v2 run of the JSON dialect deferred — every construct used is already v2-probed.)
**YAML dialect — parse-path structure done (this commit).** YamlLexer was already offset-based/clean.
`YamlStructure` rewritten immutable: plain `class BlockFrame(var last)` → immutable `case class`
(`last` advances via `frames.map(_.copy(last = …))`); three `mutable.ArrayBuffer`s → `Vector`
(push `:+`, pop `dropRight`); `Vector.newBuilder` → `Vector`; the Map-incompatible
`ranges.groupBy(_.start).getOrElse(index)` → `ranges.filter(_.start == index)`. Green unimlYaml 18/17
JVM+JS. (The `byLine` groupBy uses the pairs-iteration pattern, kept; a few functional-API constructs
`.toVector`/`Option.when`/tuple-key `.sortBy` remain to be probed — full v2 verification deferred, as
for JSON.) Remaining YAML = the OPTIONAL semantic/projection layers (`YamlSemanticParser` plain classes
+ 7 regexes + `Character.digit`; `YamlProjection` mutable) — not the parse path.

**Markdown dialect — parse path done (this commit).** The parse path (MarkdownLexer/MarkdownInlines/
MarkdownBlocks/MdChars) is now v2-construct-free. All `StringBuilder`/`Vector.newBuilder` token buffers
→ immutable `Vector[String]` + `.mkString` (the `pending` hard-break `setLength(len-1)` → `dropRight(1)`,
correct because a trailing backslash is always a single-char element). The hard part — `MdChars`'
`Character.getType`/`isSpaceChar` for CommonMark emphasis flanking — replaced by a portable BMP range
table (199 ranges, binary search) + an enumerated Unicode-whitespace set. The table is **generated
from `Character.getType`** and a JVM-only `MdCharsParitySpec` proves it EXACTLY equivalent for all
0x0000–0xFFFF (so no conformance risk, and JVM/JS now share one impl). Green markdown 34/34 JVM (32
behavioural + 2 parity) / 32 JS + bridge 11/11. (v2 handles the 398-int `Vector[Int]` literal + binary
search — probed.) Remaining Markdown = the OPTIONAL `MarkdownProjection` semantic layer
(`StringBuilder`/`Vector.newBuilder`/`Character.toChars`) — not the parse path.

**All four dialect parse paths are now v2-construct-free** (core + JSON + YAML + Markdown). Remaining
1c = the OPTIONAL semantic/projection layers (YAML `YamlSemanticParser` plain classes + regex +
`Character.digit`; `*Projection` mutable collections) + a few unprobed functional-API constructs.

## Method

Probes are small bare `.ssc` programs (`def main(): Unit = …`) exercising one or a few UniML-shape
constructs, run through the v2 self-hosted pipeline:

```
v2/ssc1 <probe>.ssc      # extract scalascript blocks → compile (front+checker) → run on the v2 VM
```

A construct "works" if the probe compiles and prints the expected result; a "gap" surfaces either as
a compile/`[E…]` error, or a runtime `unbound global: _err` (the lowering emitted an unsupported
stub), or wrong behavior. Probe sources live in `uniml/v2-smoke/`. (Ignore the deprecation warnings
from compiling `v2/src` itself — those are not from the probe.)

## Works today (confirmed compile + run on v2 `.ssc`)

| Construct | Probe result |
|---|---|
| `enum` with ADT payloads + **nested** pattern match | `sumT(Node(Leaf 1, Leaf 2))` → `3` |
| Generic `def` (`def id[A](x: A): A`) | `id(42)` → `42` |
| Generic `case class` (`case class Box[A](value: A)`) | `Box(7).value` → `7` |
| `var` + `while` | `sumTo(10)` → `55` |
| `trait` + `case class … extends` + method dispatch | `En().greet("bob")` → `hi bob` |
| **Generic trait** `Proc[I,O]` + impl + generic method dispatch | `runProc(Inc(), 41)` → `42` |
| `String`: `.length`, `.charAt`, `.substring`, concat, `Int.toString` | `"hello".substring(1,3)+len` → `el5` |

This is most of UniML's structural surface — the ADT/CST model, the `Processor[I,O]`/`DialectAdapter`
generic-trait abstraction, and string scanning all fall inside what v2 already compiles. **v2 is far
more capable than the `v2/examples/*.ssc` corpus (no enum/trait/generics there) suggested.**

## ⚠️ The wall: v2's object model is immutable (critical)

Deeper probing revealed the real blocker — **v2 supports only immutable `case class`es with
constructor params + methods.** Everything UniML relies on for stateful objects is unsupported:

| Construct | Probe | Symptom |
|---|---|---|
| plain `class Foo(...)` (non-case) | `class Counter(start): var n = start` | `unbound global: class` |
| mutable object field `var` in a (case) class body | `case class Inc(step): var calls = 0` | `unbound global: calls` |
| **derived `val` field in a class body** | `case class C(x): val y = x + 1` | `unbound global: y` |
| anonymous instance `new Trait[..]:` | `new Proc[Int,Int]: def push…` | `unbound global: _err` |
| `new Array[T](n)` + indexed `apply`/`update` | `val a = new Array[Int](3); a(1)=…` | `IndexOutOfBounds` (mis-sized) |

Only **local `var`/`while` inside `def` bodies** work; there is **no way to hold mutable state in an
object**, no body fields at all (`val` or `var`), no plain classes, no anonymous instances, no arrays.
This is essentially a **pure functional / immutable data model** (`data` + functions).

**UniML is pervasively imperative-stateful** — mutable lexer/VM fields (`TreeVm` node counters and the
frame `stack`, every dialect lexer's dozens of `var`s, `MarkdownBlocks`' open-leaf/segment state,
`TokenSink`'s `pos`/`nextId`), growable buffers, and the two anonymous `Processor` instances. So it is
**far from compilable on v2 as-is**, and even the small `1b` (anonymous→named) is blocked, because the
named processors still need a mutable `finished` field.

### The strategic fork (needs a decision — see SPRINT)

- **(A) Grow v2's imperative/OO surface** — mutable object fields (`var`/`val` in class bodies), plain
  classes, anonymous instances, working mutable arrays. Big v2-side effort, and it runs against v2's
  functional/immutable core (the "compile-to-closures VM").
- **(B) Rewrite UniML in a purely functional/immutable style** — thread all state explicitly through
  returns instead of mutable fields, immutable persistent data structures. A large rewrite of the
  whole library's internals (VM, lexers, block engine).
- **(C) Rescope** — e.g. keep UniML dual-compilable only for a *pure* subset, or accept UniML as
  scalac-only for now and revisit when v2's object model grows.

## Other gaps (secondary to the object-model wall)

| Gap | Symptom | UniML dependency | Resolution options |
|---|---|---|---|
| **Mutable `Array[T]`** (`new Array[Int](n)`, `a(i) = v`) | `IndexOutOfBounds` — array not sized to `n` | the compat-layer floor | v2-side: fix `new Array[T](n)` + indexed apply/update; or a v2-native buffer primitive. |

## Untested / deferred

- **Variance** `[+A]` (`ProcessBatch[+A]`) — probe inconclusive (timed out). UniML-side fallback if
  unsupported: drop `+`, use invariant type params (trivial).
- **`package` / `import` / multi-file** — UniML is multi-file under `package scalascript.uniml`. v2's
  `.ssc` module/import story (vs `import "path"`) must be reconciled for real multi-file compilation.
- **Unicode char classification** (`Character.getType`, categories) and **`Integer.parseInt`** — these
  are JDK, forbidden/absent in v2 by design. They are **replaced by `uniml.compat`** (a compact
  portable Unicode table + hand-rolled int↔hex), so they are not v2 gaps — they are Phase 1 work.

## Conclusions

**UniML-side (Phases 1–2), no v2 changes needed:**
- Replace anonymous `new Trait[..]:` instances with **named classes** throughout.
- Replace `scala.collection.mutable.*` / `StringBuilder` / `Character.*` / `Integer.*` with
  `uniml.compat` — but the compat mutable primitives still need a working **mutable-array floor** in v2.
- Drop variance annotations if v2 can't parse them (invariant is fine).

**v2-side (Phase 3), the concrete asks, in priority order:**
1. **Fix `new Array[T](n)` + indexed `apply`/`update`** — this is the floor everything else builds on.
2. **Multi-file `package`/`import`** so UniML's modules compile together.
3. (Optional) anonymous class instances — else UniML uses named classes.
4. (Optional) covariance annotations — else UniML uses invariant.

The v2-compile smoke (`uniml/v2-smoke/`) is currently **RED** on the Array and anonymous-instance
probes and **GREEN** on the rest; it becomes fully green as the gaps close.

## Results

To be updated as gaps close (Phase 3).
