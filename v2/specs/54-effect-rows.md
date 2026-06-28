# 54 — Effect rows for ssct-hm (implemented; light payloads)

> Status: **IMPLEMENTED, 2026-06-28.** The light (label-tracking) version is done and **runs on all
> three backends** (run-ir / JS / native-Rust). Effects are **user-extensible** (`perform` / `handle`,
> no compiler change per effect), handlers are **deep + multi-shot** and can be **stateful**
> (parameterized), and `runE` rejects any unhandled effect as a **type error**. This documents the
> design, the validated row-unification algorithm, the surface as shipped, and the path to a full
> Koka-style (typed-payload) system, which remains deferred.

## Goal

Today ssct-hm has **algebraic effects** in the typed surface ([`50`](50-effects.md)): type-safe
per-effect free monads (Track P) and a universal `Comp` over a `Dyn` escape-hatch (Track E). What's
missing is **effect tracking in the type**: nothing stops you from running a computation that still
has unhandled effects. Effect rows fix that — the type of a computation records *which* effects it
may perform, and a top-level `run` is only well-typed when every effect has been handled.

```
Comp {get, put | ρ} a     -- a computation producing `a` that may use get/put (ρ = "and maybe more")
```

## Two orthogonal problems

1. **Rows (which effects).** A new *kind* of type — a row of effect labels with a tail that is either
   closed (`{}`) or a **row variable** `ρ` (open, for polymorphism). Needs row unification and
   threading through `perform` / `bind` / `handle`.
2. **Payloads (operation arg/reply types).** `Op(label, arg, resume)` is existential per operation. To
   type the payloads you need **effect declarations** with operation signatures (`get : () -> Int`).
   Without them the payloads stay `Dyn` and the row tracks labels only.

The **light** version (this spec) does (1) and skips (2): payloads are `Dyn`, the row tracks labels,
handlers remove labels, and `run : Comp {} a -> a` enforces "no unhandled effects". The **full**
version adds (2) — see *Path to full* below.

## Representation

A row is `REmpty | RExt(label, rest) | RVar(n)` (scoped-labels / Rémy style), unordered as a set —
unification reorders. As ssct-hm `Type`s: `TyRowEmpty`, `TyRowExt(label, row)`, `TyRowVar(n)` (row
variables are a *separate* constructor from `TyVar` so kinds don't mix — the shared fresh-var counter
still gives unique `n`). The effect monad is `TyCon("Comp", [row, resultType])`.

## Row unification (validated spike)

```
rapp(s, r)            -- apply substitution to a row (REmpty | RExt l rest | RVar n→lookup)
occursR(n, r)         -- does row-var n occur in r
-- rowRewrite(s, c, l, r): bring one `l` to the front of r → ROk(tail-without-l, s', c') | RErr
rowRewrite(s, c, l, r) = match rapp(s, r):
  REmpty       => RErr                                  -- l absent in a closed row → fail
  RExt(l2, t)  => if l2 == l then ROk(t, s, c)
                  else map (rowRewrite s c l t) over (\t2 -> RExt(l2, t2))
  RVar(n)      => ROk(RVar c, s[n := RExt(l, RVar c)], c+1)   -- extend the row variable
rowUnify(s, c, r1, r2) = match rapp(s, r1):
  REmpty       => match rapp(s,r2): REmpty→ok ; RVar n→s[n:={}] ; RExt→ERR
  RVar(n)      => match rapp(s,r2): RVar m (n=m)→ok ; r2a→ occursR n r2a ? ERR : s[n:=r2a]
  RExt(l, t1)  => match rowRewrite(s, c, l, r2): RErr→ERR ; ROk(t2,s2,c2)→ rowUnify(s2,c2,t1,t2)
```

Validated by a standalone ssc0 spike (the three canonical cases):
- `{get | ρ0} ~ {put, get}`   ⇒ `ρ0 = {put}`
- `{get} ~ {put}`             ⇒ error (closed rows differ)
- `{get | ρ1} ~ {put | ρ2}`   ⇒ `ρ1 = {put | ρ3}`, `ρ2 = {get | ρ3}` (shared fresh tail)

`unify` dispatches to `rowUnify` whenever it meets row forms; `TyCon("Comp", [r1,a1])` vs
`TyCon("Comp", [r2,a2])` unifies `r1~r2` as rows and `a1~a2` as types. `appTy`/`occurs`/`freeTy`/
`renameTy`/`showTyR` gain the three row cases; `freeTy` collects row variables for `let`-generalization.

## Surface (as shipped)

An effect operation must carry its effect **label** in the type, but the type system can't read a value
out of a runtime string. So the label is a **string literal** in the surface and the type checker reads
it at compile time. There are two general primitives — no per-effect declaration or compiler change:

```
perform "Eff" "op" arg                 -- perform operation "op" of effect "Eff"  : Comp {Eff | ρ} Dyn
handle  "Eff" comp                     -- general DEEP handler (removes the label "Eff")
        (fun v => …)                   --   value/return clause : a -> Comp ρ b
        (fun op => fun arg => fun k => …)  -- operation clause; k = resume : Dyn -> Comp ρ b
```

plus the monad combinators and do-notation:

```
pureE : a -> Comp ρ a
bindE : Comp ρ a -> (a -> Comp ρ b) -> Comp ρ b
runE  : Comp {} a -> a                 -- demands the EMPTY row: an unhandled effect is a TYPE ERROR
doE { x <- m ; … ; result }            -- do-notation; desugars `<-` to bindE (the final stmt = result)
```

`perform "Eff" "op" arg : Comp {Eff | ρ} Dyn` (fresh `ρ` per use; payload `Dyn` — the light scope).
`handle "Eff" comp ret op : Comp ρ b` reads the literal `"Eff"` to remove exactly that label from the
row. At runtime the handler **forwards** operations of other effects (so handlers compose in any order),
and because the resume `k` re-enters the handler it is a **deep** handler: it supports **multi-shot**
resumption (call `k` zero / one / many times) and **parameterized / stateful** handlers (let the result
type `b` be a function of the state). `runE` demands `{}`, so a program with any unhandled effect does
not type-check — the guarantee effect rows buy.

**Runtime representation.** The universal `Comp = Pure v | Op effectLabel (Pair op arg) (Dyn -> Comp)` —
the `Op`'s label is the **effect** name (handlers match on it) and the operation name lives in the `Pair`
payload (handlers dispatch on it). `__effHandle` / `__effBind` / `__effRun` are global helper defs emitted
(once, when any effect is used) by `erase`; they are pure data folds, so multi-shot is just re-running an
immutable `Comp`.

**Convenience built-ins.** Two common effects ship as built-ins: `getE` / `putE` +
`runStateE comp s0 : Comp ρ (Pair a Int)`, and `logE` + `runLogE comp : Comp ρ (Pair a [Dyn])`. These are
conveniences, **not** a fixed capability set — `examples/hm-eff-userstate.hm` re-implements `runState`
entirely in user source via the general `handle` (a parameterized handler), with the same result.

**`let`-generalization of row variables.** A `let`-bound polymorphic handler quantifies over its row
variable; instantiation must produce a fresh **row** variable, not an ordinary one. `appTy` / `renameTy`
re-tag a freshened ordinary var as a row var at row positions (`asRow`) — without this a generic handler
fails with `not an effect row`.

## Path to full (Koka-style) — deferred

Add operation signatures to declarations (`effect State { get : () -> Int ; put : Int -> () }`); type
`perform`/operations against them (no `Dyn`); type handlers' resume/return. This gives full payload
safety on top of the tracking. It is a larger, multi-session effort (the rows are the shared core,
already done here); deferred with the user's agreement. Ergonomic sugar — `effect`/`handler` keywords
over `perform`/`handle`, and `{}` / `{l | r}` row syntax in the *type* parser for user annotations —
is also still open (optional).

## Components

| Where | What |
|---|---|
| `lib/ssct-hm.ssc0` | `TyRow*` type forms + `rowUnify`/`rowRewrite` + `unify` dispatch + appTy/occurs/freeTy/renameTy/showTyR; `asRow` row-var instantiation; infer for `EffOp`/`EffBind`/`EffRun`/`EffRunSt`/`EffRunLog`/`EffHandle` |
| `lib/ssct-hm-front.ssc0` | `perform` (3-arg) / `handle` (4-arg) recognized in `dsApp` via the application spine (`strOf` extracts the literal label); `doE` block (`parseDoStmts` parameterized by bind name); `getE`/`putE`/`logE`/`runStateE`/`runLogE` built-ins |
| `lib/ssct-hm-emit.ssc0` | `erase` lowers to the universal `Comp` + global helpers `__effBind`/`__effRun`/`__effRunSt`/`__effRunLog`/`__effHandle` (+`__effRevApp`, `irFst`/`irSnd`); appended by `progOf` when effects are used |
| backends | `f.*`/`i.*` prims already present; effects need no new prim (they are plain `Comp` data) |
| `examples/` | `hm-effrow` (State), `hm-eff2` (State+Log, both must be handled), `hm-eff-handle` (multi-shot nondeterminism, user effect), `hm-eff-userstate` (State via general `handle`), `hm-eff-do`(-nondet) (`doE`); + unhandled-effect programs that are rejected |
