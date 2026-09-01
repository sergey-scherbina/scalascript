# The projection: UniML's `SpikeAst` → v3's `Ast`

> Why this shape rather than reading the CST directly is [`40-front-on-uniml.md`](40-front-on-uniml.md)
> §5a. What it is accepted by is §7 of the same file. This document is the CONTRACT: node by node,
> what becomes what, and what must be refused.

## 1 · The one-sentence version

`SpikeAst` says **what was written**. v3's `Ast` says **what v3 will lower**. The distance between
them is v3's desugaring, and this projection is where that distance is crossed — which is why it is
the work rather than the overhead (§5a).

Measured, so the size of the job is not a guess: `SpikeAst` has **47 node kinds**, v3's `Ast` about
**25**. Nothing is lost in the difference; every surface form UniML keeps has a v3 form it collapses
into, and this table is that mapping.

## 2 · Declarations

| `SpikeAst` | v3 | note |
|---|---|---|
| `Module(decls)` | `Program(defs, topLevel, classes, objects, traits)` | one pass, sorting decls into v3's five buckets |
| `Def(name, params, ret, body)` | `Def(name, params, body)` | `ret` is DISCARDED — Tier 0 has no checker |
| `Param(name, tpe, default, using_)` | `Param(name, pos, default)` | `tpe` discarded; **`using_ = true` must REFUSE**, not be dropped |
| `CaseClass(name, fields, parent, methods)` | `ClassDef(name, fields, methods, parents)` | see §6, open question 1 |
| `EnumDecl(name, cases)` | one `ClassDef` PER CASE | v3 has no enum node: an enum case is a constructor with a tag, which is what `Parser.parseEnum` already produces |
| `EnumCase(name, fields)` | `ClassDef(name, fields, Nil, Nil)` | |
| `ObjectDecl(name, members)` | `ObjectDef(name, defs)` | a non-`def` member must REFUSE — v3 supports namespaces only |
| `TopExpr(expr)` | `Stmt.Exp` or `Stmt.Val` | `ValDef`/`Assign` at top level become statements, everything else an expression |
| `ImportDecl(path, selectors, wildcard)` | — | see §5 |
| `Given`, `GivenObject`, `EffectDecl`, `Extension`, `NoOpDecl` | **REFUSE by name** | outside Tier 0. `given`/`using` is the one item that cannot be reached by adding syntax — it needs type-directed resolution |
| `UnsupportedDecl(kind)` | **REFUSE, quoting `kind`** | it is UniML saying it did not model this; v3 repeating that is honest, inventing a node is not |

## 3 · Expressions — the collapses

The three groups where several UniML nodes become one v3 node, and the two where one becomes many.

**Calls.** `Apply(fn, args)` reads its `fn`:

| written | `SpikeAst` | v3 |
|---|---|---|
| `f(a)` | `Apply(Ident(f), [a])` | `Call("f", [a])` |
| `r.m(a)` | `Apply(Select(r, m), [a])` | `MethodCall(r, "m", [a])` |
| `r.m` | `Select(r, m)` | `MethodCall(r, "m", Nil)` |
| `f { … }` | `BlockApply(fn, arg)` | the same as `Apply(fn, [arg])` |

`BlockApply` is kept apart from `Apply` in `SpikeAst` on purpose — the CST keeps them apart, and
collapsing there would decide at projection time a question that belongs to the lowering. Here it IS
the lowering's front door, so collapsing is correct.

**Operators.** `Infix(op, l, r)` → `Bin(op, l, r)`; `Prefix("-", e)` → `Neg`; `Prefix("!", e)` →
`Not`. `RangeOp` REFUSES — v3 has no ranges.

**Literals and containers.**

| `SpikeAst` | v3 |
|---|---|
| `IntLit(text)` / `FloatLit(text)` / `StrLit` / `UnitLit` | the matching literal, parsed from the text |
| `Ident(n)` | `Name(n)` |
| `ListLit(es)` | `Call("List", es)` |
| `Tuple(es)` | `Call("Tuple" + es.length, es)` — v3's tuples ARE synthetic case classes |
| `NamedArg(n, v)` | `NamedArg(n, v)` |

**Interpolation is the one that needs a RE-LEX.** `Interp(prefix, raw)` keeps the raw string with
the embedded expressions still inside it as text — the dialect does not decompose it, and its
docstring explains why: `spike.interp` holds exactly two tokens, so nothing is lost by keeping the
text. v3's `Expr.Interp(parts, exprs)` is decomposed. **`Parser.interp` already does exactly this
split** and must be reused rather than rewritten: two implementations of `${…}` nesting is two
implementations that will disagree about a brace inside a string.

A `prefix` other than `s` must REFUSE. v3 supports `s"…"` only, and an `f"…"` silently treated as
`s"…"` is a wrong answer rather than a refusal.

## 4 · The desugarings — where the distance actually is

Each of these is one UniML node becoming a v3 SHAPE, and each already exists in v3's parser. The
projection must produce the SAME shape, or the front-diff gate reports it — which is the point of
having the gate before the projection.

| `SpikeAst` | v3 shape | already in |
|---|---|---|
| `For(gens, body, isYield)` | `foreach` / `map`, `filter` per guard, `flatMap` for all but the innermost generator | `Parser.parseFor` |
| `PartialFn(arms)` | `Lambda([$m], Match(Name($m), arms))` | `Parser.parseBraceBlock` |
| `TupleVal(names, rhs)` | `Val($tup, rhs)` then one `Val(n_i, MethodCall(Name($tup), "_i"))` per name | `Parser.parseStmt` |
| `IndexAssign(target, value)` | `Update(arr, index, value)` — **`target` must be destructured** from `Apply(arr, [index])`; anything else refuses | `Parser.parseStmt` |
| `LocalDef(decl)` | v3 has no local `def`. **REFUSE** — measured 2026-08-06 as not supported by v3 either | — |
| `Lambda(params, body)` | `Lambda(params.map(Param(_)), body)` | |
| `Throw(v)` | `Call("__throw__", [v])` — v3's throw is a prim call | `Parser` |
| `Try(body, handler, finalizer)` | `Try(body, name, handler)`. A `finalizer` REFUSES; a handler that is not a `PartialFn` of exactly one binding arm REFUSES | `Parser.parseTry` |
| `CompoundAssign(n, op, v)` | `Assign(n, Bin(op-minus-the-`=`, Name(n), v))` |
| `RangeOp(op, a, b)` | `Bin(op, a, b)` — an ordinary binary operator in the TREE. The desugaring into a cons list lives in `Lower`, one implementation for two fronts; emitting the desugared form here made the fronts print different trees. **`RangeOp` carries EVERY id-infix application, not just `to`/`until`** (see below) |
| `Summon`, `Quote`, `Splice`, `QuotedName`, `Marker` | **REFUSE.** `Marker` is `direct[F] { … }` and the optics markers: erasing one to its contents would run a monadic-do block as an ordinary block, which is a wrong ANSWER rather than a smaller tree |
| `NotImplemented` in EXPRESSION position | `Prim("__throw__", ["an implementation is missing"])` — Scala's `???` throws WHEN EVALUATED, and refusing the file is stricter than the language: a stub in a branch nobody takes used to block the whole program |
| `NotImplemented` as a def BODY | `Name("__abstract__")` — no `=` was written. ONE marker, and the SITE says what it means: in a trait or class it is an abstract member, at top level it is an `extern`, i.e. a host function |

**`Block` is the subtle one.** `SpikeAst.Block(stmts: Vector[Expr])` is a flat vector in which
`ValDef`, `Assign` and `LocalDef` are ordinary `Expr` cases. v3's `Block(stmts: List[Stmt], result:
Option[Expr])` separates the statements from the block's VALUE. The rule is v3's and is already
written down: the last element is the result **unless it is a `val`**, because a `val` tail prints
nothing (`Lower.markAutoOutput` depends on it). Getting this wrong does not crash — it changes what
a block evaluates to and what auto-output prints, which is a wrong answer.

## 5 · Patterns — CORRECTED 2026-08-09

This table said "v3 has no typed pattern" and "alternatives may not bind" as if both were fixed
facts about v3. The first stopped being true on 2026-08-07, and saying so here matters more than
the row itself: a spec that describes a refusal the code no longer makes sends the next reader to
add something that exists.

| `SpikeAst` | v3 |
|---|---|
| `PatVar(n)` | `PBind(n)` |
| `PatWild` | `PWild` |
| `PatLit(expr)` | `PLit(expr)` — it carries the literal's own EXPRESSION NODE, not text. It was a `String` and lost the literal's KIND: the integer arm passed the raw lexeme, the string arm passed decoded content with the quotes gone, and `case '\n'` matched character 92 |
| `PatCtor(n, args)` | `PCtor(n, args)` |
| `PatTuple(es)` | `PCtor("Tuple" + es.length, es)` |
| `PatCons(h, t)` | `PCtor("Cons", [h, t])` |
| `PatAlt(alts)` | `PAlt(alts)`. Alternatives bind nothing — Scala requires every alternative to bind the same names and v3 has no analysis to check it — and they NEST, so `C("a" \| "b", k)` is ordinary. The reference front cannot parse that one |
| `PatTyped(inner, tpe)` | `PType(typeHead(tpe), inner)`. The test is `__isTag__(value, name, -1)`, the reference's own shape, which v2 implements — so the bridge lane costs nothing. The type is taken by its HEAD: `List[Int]` tests `List`, since a type argument carries no runtime evidence |
| `PatBind(alias, inner)` | **REFUSE** — v3 has no `x @ pat` |
| `PatUnsupported(kind)` | **REFUSE, quoting `kind`** |

**What the LOWERING still refuses, which is not the same thing.** A typed alternative
(`case _: Int \| _: String =>`) projects correctly and then meets `Lower.altTest`, which handles a
literal and a nullary constructor and nothing else. A parse refusal became a lowering refusal; both
are positioned, neither is a wrong answer, and the fix is a few lines in a file that belongs to
another claim.

## 5a · What the projection does that this document never described — ADDED 2026-08-09

Written after the fact, which is the honest order to admit: these landed one measurement at a time
while the corpus number moved, and a contract that only records the parts decided in advance is a
contract for a program that no longer exists.

**A HOST FUNCTION IS A DECLARATION, NOT A REFUSAL.** `extern def readFile(path: String): String`
arrives as a def with no body. Refusing it cost **144 corpus cases**: the standard library declares
these in blocks — twenty in `fs.ssc`, fifteen in `os.ssc` — so importing such a module failed
outright for a program that called none of them. It stays a function whose body throws, and a
REACHABLE one is refused at BUILD time from a call graph rooted at the entry. That reachability is
deliberately UNDER-approximated (direct calls only): over-approximating would mark a host function
reachable through any same-named method and refuse the 144 again.

**THE PLACEHOLDER LAMBDA IS NOT HERE.** `xs.map(_ * 2)` desugars in `Lower`, not in this
projection, because v3's own front hands `_` over as an ordinary name too. Two fronts implementing
one desugaring is two implementations that will disagree. Same for curried application, `to`/`until`
and boxing a captured `var` — every rule that both fronts need lives past the fork, not in it.

**`RangeOp` IS NOT A RANGE — IT IS EVERY ID-INFIX APPLICATION** (`8f26b983f`, 2026-09-01).
`b add 2` is `b.add(2)`, Scala's rule for an identifier in operator position, and the spike front
builds the same `spike.rangeop` node for it that `to`/`until` have always used, because that node
projects to `Bin(op, a, b)` and lowers to `lhs.op(rhs)` — which is exactly what the application
means. The kind keeps the old spelling deliberately: renaming it would move byte-exact CST pins for
a cosmetic gain, and the row above says what it actually holds.

**WHERE THIS IS TRUE, STATED NARROWLY.** Both of v3's fronts do it. The v2 lane does NOT, and it
does not merely refuse — `println(b add 2)` there compiles, exits 0 and prints the RECEIVER
(`v2/BUGS.md v2-front-drops-an-id-infix-application-and-prints-the-receiver`). So this is not yet a
language guarantee to document in `docs/user-guide.md`, and `v3/tests/front/infix-class-method.ssc`
is deliberately not a `tests/conformance` case: it would be red on every v2 lane.

**AN `object` IS A NAMESPACE, AND ITS CLASSES ARE HOISTED.** `def`s become `O.name`, `val`s become
the object's state, and a `case class` declared inside is lifted to the top level under its PLAIN
name — v3 has no nested classes and an object is not a scope for types. Plain names make the
references inside the object work without rewriting them; the cost is a COLLISION, which is refused
by name rather than resolved silently, and the check covers every class because an `enum` case can
collide just as easily.

**A `case object` CARRIES ITS PARENTS AND ITS METHODS.** It is a NULLARY CONSTRUCTOR, so it becomes
a `ClassDef` with no fields. Both halves were dropped once: parents were erased in the CST, which
left a constructor belonging to no hierarchy that no `match` on the trait could reach, and members
were dropped by an arm written for the bare-marker shape.

**A `catch` KEEPS ITS SIMPLE SHAPE.** One arm that only BINDS projects to v3's `Try` unchanged,
because that is what v3's own front produces and the two must print the same tree wherever both can
parse. Several arms, or one that tests a TYPE, want a `match` on the caught value with a rethrow
arm — written, measured, and NOT landed: `Exec.scala` binds the message STRING where the bridge
binds the thrown value, so making the type mean something turns a shared wrong answer into a lane
divergence. The order is: fix that line first.

## 6 · Imports stay with `Loader`, and this was verified

`ImportDecl(path, selectors, wildcard)` is real and carries its path — an earlier note in
[`40-front-on-uniml.md`](40-front-on-uniml.md) said the path was absent, and that was corrected once
the code was read rather than the sprint prose.

**But it does not serve v3's dominant import form.** Measured 2026-08-06:

| written | what the projection yields |
|---|---|
| `import a.b.c` | `ImportDecl(a.b.c, [], false)` |
| `import a.b.{x, y}` | `ImportDecl(a.b, [x, y], false)` |
| `[Node, Cluster](std/mapreduce/cluster.ssc)` | **nothing** — only `TopExpr` |

The markdown link is OUTSIDE the code fence, so it belongs to the markdown layer and the ScalaScript
dialect never sees it. That is correct behaviour, not a gap: `Loader.importsOf` scans the raw source
TEXT and always has. **The projection therefore does not build the module graph; `Loader` keeps
doing it**, and a `ImportDecl` that does reach the projection is a Scala-style import, which v3
refuses today anyway.

Recording it because the opposite assumption is the natural one, and it would have produced a front
that resolved no imports while looking correct.

## 7 · Open questions, to be MEASURED before the code is written — ANSWERED 2026-08-07

Each was a probe, and this is where the answers go. The apparatus is
`uniml/scala/src/test-jvm/…/ssc/Ssc3ProjectionCensusSpec.scala`, which re-runs every census and
pins each ANSWER — not a threshold — so a corpus change that flips one goes red here rather than
being discovered while writing the projection.

| # | question | answer, 2026-08-07 |
|---|---|---|
| 1 | `case class C(…) extends A with B`? | **NO.** 723 case classes, 151 with a parent, **0** with a second. `Option[String]` is faithful; no node growth to request. |
| 2 | typed / defaulted lambda params? | **YES, 164 source lines.** `Vector[String]` drops a type the source wrote. |
| 3 | an object holding a nested class? | **YES, 180**, across 257 objects — member kinds `Def=651, TopExpr=417, ObjectDecl=96, CaseClass=84`. |
| 4 | spans | **The mapping is NOT mechanical: offsets are CODE POINTS.** |

**Q1 — no, and the first measurement said yes.** A naive "the text after `extends` contains a
comma" reported four hits and all four were false: three were commas inside type ARGUMENTS
(`extends Either[A, B]`, `extends Proc[Int, Int]`) and one was a comment. The census now scans at
bracket depth zero. Had the first number been believed, this document would carry a request to grow
a node that nothing in the corpus needs.

**Q2 — yes, so "believed lossless" needs its qualifier.** 164 lines write a typed lambda parameter
(`(state: String) =>`, `(a: A, as: List[A]) =>`). `Lambda.params: Vector[String]` is lossless **for
v3 only while v3's lambdas take no parameter types** — it is not lossless about the source. That
distinction is the answer; the projection may proceed, but not on the grounds that nothing is being
dropped. This detector was also wrong first: a regex counted `case Some(s: String) =>` (a typed
PATTERN) and `case class C(f: () => Any)` (a function-typed FIELD) among 195 hits.

**Q3 — yes, emphatically, so the refusal is on a HOT PATH.** 180 nested declarations, and the
histogram is the argument: against 651 `def` members there are 96 nested objects and 84 nested case
classes. §7's phrasing — "confirm the refusal fires rather than the class vanishing" — is the right
worry rather than a formality, and a green corpus sweep will NOT exercise it by accident.

**Q4 — the mapping is not mechanical, and this is the answer that changes code.** `SourceSpan`
offsets are **code points**, not UTF-16 code units. A projection computing a `Pos` with
`src.substring(0, offset)` is off by one per astral character, and the corpus already reaches it:
`examples/control-center-live.ssc` line 93 carries four emoji, and the naive arithmetic put a token
at column 181 of a 177-character line. Verified in both directions — with code-point arithmetic all
**778,738 tokens across 1,240 files** agree, and a dedicated case asserts the two arithmetics
DISAGREE on an astral input, so the trap cannot quietly stop being one.

The questions as originally written:

1. **`CaseClass.parent` is an `Option[String]`; v3's `ClassDef.parents` is a list.** Does the corpus
   write `case class C(…) extends A with B`? If it does, the projection cannot be faithful until
   UniML's node grows, and that is a request to file rather than a workaround to invent.
2. **`Lambda.params` is `Vector[String]`** — no types, no defaults. v3's lambdas take neither today,
   so this is believed lossless; confirm against the corpus rather than assume.
3. **`Def` in `ObjectDecl.members` is a `Decl`**, so an object could hold a nested class. v3 supports
   `def` members only. Confirm the refusal fires rather than the class vanishing.
4. **Spans.** v3's `Pos` is line and column; `SourceSpan` carries offsets too. The mapping is
   mechanical, but a diagnostic pointing one column off is a real regression and the front-diff gate
   deliberately does NOT compare positions (§7 of the front spec) — so this needs its own check.

## 8 · Acceptance

`v3/front-diff.sh`, unchanged. It compares `AstText.render` from both fronts over every fixture and
every corpus case v3 compiles, and it already self-tests its comparator. The projection is done when
that gate is green with `Front.available` naming two fronts — and not one construct sooner, because
the gate is the only thing in this design that can say NO.
