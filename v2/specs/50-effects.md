# 50 — Algebraic effects & handlers (as a library)

> Status: **v1 (2026-06-27)** — `v2/lib/effects.ssc0`. Effects, one of ssc 1.0's signature
> features, are a **pure ssc0 library on the frozen kernel** — no kernel continuation node,
> no `call/cc`. This is the design promise (D7): the kernel is strict, untyped, effect-node
> free; effects "lower" to ordinary data + closures, in the language.

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

A **handler** is a fold over this tree that interprets operations. Because `resume` (`k`) is
an ordinary, **reusable** closure, a handler may call it **zero times** (abort), **once**
(one-shot), or **many times** (multi-shot) — the full power of algebraic effects.

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

## Next

- `do`-style sugar for `bind` in the `ssct`/surface layer (today: explicit `bind`).
- Async as an effect: a `fork`/`yield`/`await` op + a scheduler handler (see
  `specs/51-async.md`).
- Typed effects (effect rows) in the `ssct` type layer.
