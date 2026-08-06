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
| `CompoundAssign`, `Summon`, `Quote`, `Splice`, `QuotedName`, `Marker`, `NotImplemented` | **REFUSE by name** | — |

**`Block` is the subtle one.** `SpikeAst.Block(stmts: Vector[Expr])` is a flat vector in which
`ValDef`, `Assign` and `LocalDef` are ordinary `Expr` cases. v3's `Block(stmts: List[Stmt], result:
Option[Expr])` separates the statements from the block's VALUE. The rule is v3's and is already
written down: the last element is the result **unless it is a `val`**, because a `val` tail prints
nothing (`Lower.markAutoOutput` depends on it). Getting this wrong does not crash — it changes what
a block evaluates to and what auto-output prints, which is a wrong answer.

## 5 · Patterns, and what v3 does not have

| `SpikeAst` | v3 |
|---|---|
| `PatVar(n)` | `PBind(n)` |
| `PatWild` | `PWild` |
| `PatLit(text)` | `PLit(literal parsed from text)` |
| `PatCtor(n, args)` | `PCtor(n, args)` |
| `PatTuple(es)` | `PCtor("Tuple" + es.length, es)` |
| `PatCons(h, t)` | `PCtor("Cons", [h, t])` |
| `PatAlt(alts)` | `PAlt(alts)` — and v3 additionally requires that none of them BIND |
| `PatBind(alias, inner)` | **REFUSE** — v3 has no `x @ pat` |
| `PatTyped(inner, tpe)` | **REFUSE** — v3 has no typed pattern, and erasing the type would make `case x: Int` match everything |
| `PatUnsupported(kind)` | **REFUSE, quoting `kind`** |

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

## 7 · Open questions, to be MEASURED before the code is written

Not answered here on purpose. Each is a probe, and this document is where the answers go.

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
