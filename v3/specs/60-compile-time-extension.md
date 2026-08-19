# Compile-time extension — how v3 grows syntax from outside the kernel

> The runtime SPI (`v3/src/Plugins.scala`) lets a plugin supply **values**: a function behind a name,
> a method on a host handle, a package, a rendering. This file is the other half — how a plugin
> supplies **syntax**, without the kernel learning what that syntax means.

## The problem, stated exactly

`html"…"` was answered by making the interpolator an ordinary CALL: `pfx"a${x}b"` lowers to
`pfx(List("a","b"), List(x))`, so `def html(parts, args)` in a library defines one and the kernel
learns nothing. That answer works because **the pieces are values**.

It does not extend to these:

```scalascript
Focus[Person](_.address.city)      // needs the FIELD NAMES out of the lambda's syntax
direct[Option] { x = Some(40); … } // needs the block's statements as syntax, unevaluated
```

A call receives values. `Focus` never sees `_.address.city` as a path — it sees a function. `direct`
never sees `x = Some(40)` as a binding — the block is already an expression by the time a call could
look at it. That is the whole difference between a function and a macro, and it is why these nine
corpus cases were refused by NAME rather than by a missing definition.

## The precedent: the registry is already asked at compile time

This is not a new kind of coupling. `Lower` consults the plugin registry at three sites and `Loader`
at one, all before anything runs:

| question | door | asked by | answers |
| --- | --- | --- | --- |
| is this name provided? | `canProvide` | `Lower` | yes / no |
| is this a package you own? | `hostPackage` | `Loader` | yes / no |
| what is this method on this value? | `method` | `Exec` | a value |
| how do you print your own handle? | `showHost` | `Exec` | a string |
| **rewrite this syntax** | **`registerRewrite`** | **a pass before `Lower`** | **an `Expr`, or a positioned refusal** |

Only the last row is new, and only it takes a TREE and returns a TREE.

## Where the pass runs, and why it is not in a front

```
source ──▶ front ──▶ Expr ──▶ [ rewrite pass ] ──▶ Lower ──▶ IR ──▶ { Exec | BridgeV2 }
             ▲                                                        executor   bridge
       v3's own parser
       or the UniML projection
```

**At `Expr`, which both fronts already produce.** Three reasons, and each is a failure this tree has
actually had:

1. **Two fronts, one rewrite.** A feature written in each front separately drifts. On 2026-08-19
   three separate defects were exactly that: one layout rule stated at one site in the grammar and
   missing at two others, twice silently eating a declaration.
2. **I-3 falls out for free.** The rewrite happens before lowering, so the executor and the v2
   bridge receive the same IR by construction rather than by agreement.
3. **I-1 is kept.** The kernel gains one AST node and one pass. No dependency; the meaning lives in
   `v3/plugins/`.

## The one open node

```scala
case Marker(name: String, typeArgs: List[String], args: List[Expr], pos: Pos)
```

ONE node kind, deliberately. All three known clients fit it:

| written | reaches the pass as |
| --- | --- |
| `direct[Option] { … }` | `Marker("direct", ["Option"], [block], p)` |
| `Focus[Person](_.age)` | `Marker("Focus", ["Person"], [lambda], p)` |
| `html"a${x}b"` | `Marker("html", [], [parts, args], p)` |

**What does NOT fit, said plainly.** New STATEMENT forms, new binding constructs, anything that
changes how the file is tokenised or blocked. Those are parser work and this door does not pretend
otherwise. The claim is narrower and checkable: *syntax that is a name, optional type arguments and
argument trees needs no kernel change.*

## Which names are markers: the REGISTRY decides, not the grammar

**Both fronts must build the same tree or the differential fires, and that is not a detail — it is
what fixes the rule.** Today the nine marker cases are declared `KNOWN_CONF_V3_ONLY`: v3's own front
READS them, because `Focus[Person](_.age)` is an ordinary call with type arguments and
`direct[Option] { … }` is an ordinary call with a block argument, while the UniML projection refuses
them by name. If the projection started building a `Marker` and v3's front went on building a call,
those nine would stop being one-sided and start DISAGREEING — nine differences against a DIFF floor
of zero.

So the rule is:

> A name is a marker **iff a rewrite is registered for it.** Both fronts ask
> `Plugins.hasRewrite(name)` at the same point, so both build `Marker` or both build a call.

Three things follow, and each is worth more than the node itself:

- **`SSC3_FLEET=off` is the control, exactly as rule 5 wants.** With no plugins there are no
  registered rewrites, so no name is a marker, and every one of these files parses precisely as it
  does today. The switch is not bolted on; it falls out of where the question is asked.
- **The kernel never learns a marker's NAME.** `Focus`, `direct` and `Prism` appear in no kernel
  source — only in the plugin that claims them. That is the difference between this and the
  hardcoded `s` interpolator prefix that R5 removes.
- **R1 is measurable on its own.** When both fronts agree, those nine stop diverging and the
  capability gate says so by name: the declared list loses nine entries and the derived ceiling
  falls with them, while N does not move — the cases still refuse, one layer later, because no
  rewrite is registered yet.

**Type arguments are the reason this needs a node at all.** Without one, a rewrite could match
`Call(Name("Focus"), args)` and no AST change would be needed — but v3's parser ERASES type
arguments (`skipBrackets`, `skipTypeParams`), and `Prism[T]` needs them: ScalaSpike already captures
its type-argument tokens deliberately. A node that carries `typeArgs` is the smallest thing that
does not throw away what a client needs.

## The door

```scala
final case class Refusal(msg: String, pos: Pos)
trait Ctx:
  def fresh(prefix: String): String          // hygiene: the pass owns generated names
  def rewrite(e: Expr): Either[Refusal, Expr] // recurse, for a client that builds new markers
type Rewrite = (Expr.Marker, Ctx) => Either[Refusal, Expr]
def registerRewrite(name: String, fn: Rewrite): Unit
```

## Six rules, each with the failure it prevents

1. **A claim is EXCLUSIVE.** Two plugins registering `Focus` is an error at registration, not a
   race. *Why:* v2's registry tables are last-registered-wins and say so in their own comment; on
   2026-08-19 a last-wins read of a duplicated key turned main red about a value two lines above
   the one it named.
2. **Bottom-up, to a bounded fixed point.** Children are rewritten before their parent, so a client
   sees finished arguments and a rewrite may itself produce a marker. The bound is a constant and
   exceeding it is a positioned refusal naming the marker — a runaway rewrite must not look like a
   hang.
3. **Refusals carry a position, always.** `Refusal(msg, pos)` becomes the same `:line:col:` sentence
   a front produces. *Why:* `corpus-report.sh` classifies UNSUPPORTED by that shape, so an exception
   escaping a rewrite is counted as CRASH — a floor. That exact conversion happened twice on
   2026-08-19 while unblocking the operator path, once per lane.
4. **Generated names come only from `Ctx.fresh`.** A client that invents a binder captures. v3
   already spells generated binders `$m<line>_<col>`; the pass keeps that convention and the client
   never chooses.
5. **The mechanism switches off.** With `SSC3_FLEET=off` there are no rewrites and every marker
   refuses exactly as it does today. *Why:* a mechanism that cannot be switched off cannot be
   measured, and every floor this fleet holds was established by running the corpus with it off.
6. **The output is ORDINARY `Expr`.** No new IR node, no new instruction, nothing for a backend to
   learn. A rewrite that cannot be expressed in the existing AST is out of scope by construction —
   which is what keeps `Lower`, the verifier, the executor, the bridge and every emitter untouched.

## The gate

`v3/rewrite-gate.sh`, with a test plugin that registers one trivial marker, asserts:

- the pass runs and the marker becomes the client's tree;
- an UNCLAIMED marker refuses with a position, and the sentence names the marker;
- a client returning `Refusal` produces that same shape rather than a stack trace;
- a rewrite that keeps producing markers stops at the bound and says which one;
- registering a name twice is refused;
- with `SSC3_FLEET=off` the marker refuses exactly as it did before this file existed.

A gate that only proves the happy path would not have caught any of the six failures above.

## Where this meets the effects machinery — and where it deliberately does not

Asked by the owner on 2026-08-19, after `bridge-caller-cps` and `bridge-join-points` landed: does
this door serve selective CPS? The answer is in two halves, and both are useful.

**THE ORDERING MAKES THE NOTATION HALF FREE, and this is checked rather than hoped:**

```
front ──▶ Expr ──▶ [ rewrite pass ] ──▶ Lower ──▶ Module ──▶ Cps ──▶ TailCalls ──▶ { Exec | BridgeV2 }
```

`Lower.scala` ends with `TailCalls(Cps(Module(…)))`, so this pass runs strictly before the effects
machinery. Anything a marker expands into therefore flows through ALL of it untouched: a plugin can
define its own effect NOTATION — a `resource { … }` block, do-notation over an effect, a retry or
timeout combinator — expand it to ordinary `handle`/`perform` surface syntax, and `Cps` picks the
result up knowing nothing about the plugin. No coordination between the two mechanisms is needed,
and none should be added.

**BUT A REWRITE MUST NOT MINT `Expr.Perform`.** That node carries a RESOLVED op id (`Int`), and it
is produced by a rewrite inside `Lower.programOf` "where the `effect` declarations are in scope".
A pass that runs before lowering does not have that scope. It emits the SURFACE call and lets the
existing rewrite resolve it — which is rule 6 doing its job rather than a limitation to work around.

**AND THE TRANSFORM ITSELF IS NOT THIS DOOR'S BUSINESS.** `Cps.scala` is `Module => Module`: it
splits a function at a `Perform` INSTRUCTION, captures registers, mints new functions and changes
the calling convention. Forcing it through here would cost three of the six rules above:

| rule | why selective CPS breaks it |
| --- | --- |
| output is ordinary `Expr` | its whole product is new FUNCTIONS plus a changed convention |
| a rewrite sees its own marker | whether a call must split depends on whether the CALLEE performs |
| the trigger is a name someone wrote | `Perform` is DERIVED by the compiler from effect declarations |

That is the boundary, stated so nobody has to rediscover it: **syntax comes through this door,
machinery does not.** A transform that needs the control-flow graph, produces new functions, or
changes a calling convention is an IR pass and belongs where `Cps` is.

**IF IR-LEVEL EXTENSION IS EVER WANTED, it is a different design and a harder one.** There are
already at least two module-to-module passes with an ORDER between them (`Cps` before `TailCalls`),
so a registry alone would not be enough: it needs an ordering contract, a rule that a pass is
provably a no-op when idle — `Cps` already states its own ("returns the module unchanged when there
is nothing to split, so applying it to a program without effects is free and provably a no-op") —
and the verifier re-run between passes. Worth designing when a SECOND machinery-level client exists,
and not before.

## The first three clients

| client | cases | needs |
| --- | --- | --- |
| `direct[F] { … }` | 3 | the rewrite only — a `flatMap` chain over ordinary methods |
| `Focus` / `Prism` | 6 | the rewrite, plus a small `Lens` type in `std` |
| interpolators | 6 | the rewrite — and it REPLACES the hardcoded `s` in both fronts |

The third row is the argument for building the door rather than two rewrites. `html"…"` is already
decided and already needs a front change in BOTH fronts; done here it is one client of one
mechanism instead of a second mechanism with the same shape.

**`Focus` needs no runtime door, and that is worth stating because it was assumed to.** A lens's
`set` looks like it needs to write a field named at run time — but the field chain in
`Focus[Person](_.address.city)` is a LITERAL in the source, so the rewrite emits the nested
`copy(address = …)` that `Lower` already compiles statically. `get`, `modify` and `andThen` are then
ordinary library code over a pair of closures.
