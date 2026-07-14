# 50 — Algebraic effects & handlers (as a library)

> Status: **v1 (2026-06-27)** — `v2/lib/effects.ssc0`. Effects, one of ssc 1.0's signature
> features, are a **pure ssc0 library on the frozen kernel** — no kernel continuation node,
> no `call/cc`. This is the design promise (D7): the kernel is strict, untyped, effect-node
> free; effects "lower" to ordinary data + closures, in the language.

[`../../specs/control-interoperability.md`](../../specs/control-interoperability.md)
builds target-neutral typed multi-prompt `shift`/`reset` and reusable `save`/`run`
on this exact `Pure | Op` semantic substrate. Host profiles, including
[`Scala 3/JVM`](../../specs/scala3-bidirectional-control.md), refine physical APIs
without replacing this representation or adding a CoreIR continuation node. A
saved portable continuation is a compiler-generated closed CoreIR resume program,
never a serialized current runtime closure.

## Representation

A computation is data:

```
Comp = Pure v | Op(label, arg, resume)        resume : reply -> Comp
```

- `pure v        = Pure v`
- `perform l a   = Op(l, a, (r) => Pure r)`     — a single operation that resumes with the reply
- `bind c f`      threads the rest of the program through every operation:
  ```
  bind (Pure v)        f = f v
  bind (Op l a k)      f = Op(l, a, (r) => bind (k r) f)
  ```

A **raw library handler** is a fold over this tree that interprets operations.
Because raw `resume` (`k`) is an ordinary, **reusable** closure, it may be called
**zero times** (abort), **once**, or **many times** (multi-shot) — the full power
of the untyped/Mira `Comp` substrate.

This is deliberately not the multiplicity default of the typed `.ssc`
declaration sugar. `effect E` lowers its initial continuation through
`effect.perform.oneshot` and may resume at most once; `multi effect E` lowers
through reusable raw `effect.perform`. Both still produce the same three-field
`Op(label, argument, continuation)` data. The distinction lives in continuation
behavior, never in a new kernel/CoreIR node or an extra `Op` field. See
[`../../specs/control-one-shot-guard.md`](../../specs/control-one-shot-guard.md).

## Handlers (examples in the lib)

**State** (one-shot resume, threads the state):
```
runState (Pure v)   s = Pair(v, s)
runState (Op l a k) s = if l == "get" then runState (k s)  s
                        else if l == "put" then runState (k ()) a
                        else Op(l, a, (r) => runState (k r) s)   -- forward unknown ops
```
`examples/effects-state.ssc0`: `get; put(get+1); get; put(get+1); get` from `0` ⇒ `Pair(2, 2)`.

**Nondeterminism** (MULTI-SHOT — resume run once per choice):
```
runAll (Pure v)   = [v]
runAll (Op l a k) = if l == "choose" then concatMap (\x -> runAll (k x)) a else []
```
`examples/effects-nondet.ssc0`: `choose{1,2}` then `choose{10,20}`, return `x+y` ⇒
`[11, 21, 12, 22]` — every combination, by resuming the same continuation per choice.

## Why this matters

ssc 1.0 has a whole effects-VM (TLS, JIT bridges, multi-shot detection). v2 reproduces the
observable semantics — including **multi-shot continuations** — as ~40 lines of ssc0 on a
kernel that knows nothing about effects. To add an effect you write a `perform` + a handler;
no kernel change. Forwarding (`else Op(...)`) composes handlers.

## Typed surface (Mira) — K7, 2026-06-28

The same effects are now available in the **typed** `Mira` surface (HM-inferred, compiling
to VM / JS / native Rust), via two complementary designs. `do`-notation provides the `bind`
sugar (`specs/41-Mira.md`).

**Track P — type-safe, per-effect free monads** (no `Dyn`, no existentials). Each effect is a
free monad over its *own* functor — an ordinary `data` type with **function-typed fields**:

```
data St a = Ret a | Get (Int -> St a) | Put Int (St a)        -- examples/hm-eff-state.hm  (one-shot)
data Nd a = RetN a | Choose [Int] (Int -> Nd a)               -- examples/hm-eff-nondet.hm (MULTI-SHOT)
```
`get`/`put`/`choose` are typed (`get : St Int`), handlers are plain folds (`runState`, `runAll`
via `concatMap` for multi-shot), and the user writes `do { x <- get ; put (x+1) ; … }`. Fully
type-checked; the only language feature this needed was function types in data fields.

**Track E — universal `Comp` via a `Dyn` escape-hatch.** One monad for *all* effects, matching
this library's `Pure | Op(label, arg, resume)` exactly. The operation payload/reply are
existential, so they ride a `Dyn` type (unifies with any type — the single, localized unsafe
coercion); user code casts at the boundary with `(e : T)`:

```
data Comp a = Pure a | Op String Dyn (Dyn -> Comp a)          -- examples/hm-eff-comp.hm
perform l a = Op l a (fun r => Pure r)                        -- generic; one handler dispatches by label
```

**Update:** the **light** effect-row system (tracking *which* effects in the type; `runE` rejects any
unhandled effect; user-extensible `perform`/`handle` with deep/multi-shot/stateful handlers; `doE`
do-notation) is now **implemented and runs on all three backends** — see [`54-effect-rows.md`](54-effect-rows.md).
Still deferred (research): **typed payloads** (Koka/Frank operation signatures, no `Dyn`) on top of the
tracking.

## Next

- Async as an effect: a `fork`/`yield`/`await` op + a scheduler handler (see
  `specs/51-async.md`).
