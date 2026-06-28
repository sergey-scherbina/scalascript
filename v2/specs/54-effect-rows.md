# 54 — Effect rows for ssct-hm (research + light implementation)

> Status: **research, 2026-06-28.** Row-unification spike validated; light implementation in
> progress. This documents the design, the algorithm (validated on the kernel), the scope of the
> "light" version being shipped, and the path to a full Koka-style system.

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

## Surface

Effect operations must carry their label in the type, which the type system can't read from a runtime
string — so operations come from a declaration:

```
effect State get put in           -- declares effect `State` with operations get, put
do { x <- get unit ; put (x + 1) ; get unit }    -- : Comp {State | ρ} Dyn
```

A declared operation `op` of effect `L` gets type `Dyn -> Comp {L | ρ} Dyn` (fresh `ρ` per use);
its runtime is `Op "op" arg (fun r => Pure r)` over the universal `Comp`. `pure : a -> Comp ρ a`,
`bind : Comp ρ a -> (a -> Comp ρ b) -> Comp ρ b`. A handler for `L` has type
`Comp {L | ρ} a -> … -> Comp ρ b` (removes `L`); `run : Comp {} a -> a` demands the empty row, so a
program with an unhandled effect is a **type error** — the guarantee effect rows buy.

## Path to full (Koka-style) — deferred

Add operation signatures to declarations (`effect State { get : () -> Int ; put : Int -> () }`); type
`perform`/operations against them (no `Dyn`); type handlers' resume/return. This gives full payload
safety on top of the tracking. It is a larger, multi-session effort (the rows are the shared core,
already done here); deferred with the user's agreement.

## Components

| Where | What |
|---|---|
| `lib/ssct-hm.ssc0` | `TyRow*` type forms + `rowUnify`/`rowRewrite` + `unify` dispatch + appTy/occurs/freeTy/renameTy/showTyR |
| `lib/ssct-hm-front.ssc0` | row syntax in the type parser (`{}` / `{l, …}` / `{l | r}`); `effect L ops in …` decl |
| `examples/` | a `State` program that type-checks; an unhandled-effect program that is rejected |
