# 40 — `ssct`: the typed layer (types as a library)

> Status: **v1 IMPLEMENTED** (2026-06-27) — `v2/lib/ssct.ssc0` (136 lines of ssc0).
> This is the conceptual heart of the design (decision **D1**): the type checker is a
> **program written in ssc0** — the layer below — not a feature of the Scala kernel. Types
> are an *outer library*. The kernel stays untyped; `ssct` runs on it like any other ssc0
> program (`ssc run examples/typed.ssc0`).

## What it is

A small typed lambda calculus, with a type checker and an erased evaluator, both written
in ssc0:

```
Type = TyInt | TyBool | TyFun(a, b)
Term = Lit n | BoolLit b | Var x | Lam(x, ty, body) | App(f, a)
     | Add(a, b) | If(c, t, e) | Let(x, e, body)
```

- **`infer : env -> term -> Ok ty | Err msg`** — synthesizes a type or a type error.
  Env is an assoc list `Cons(Pair(name, ty), …)`; `Ok`/`Err` is the result ADT.
- **`evalTerm : env -> term -> Value`** — the *erased* evaluator (`Value = IntVal | BoolVal
  | Closure`); it ignores type annotations entirely. Erasure = "drop the types, run the
  untyped term."
- **`check term`** = `infer Nil term` then, iff well-typed, `evalTerm Nil term`. Returns
  `Typed(showType ty, result)` or `TypeError(msg)`.

The tower in action: `ssc0 → (ssct = an ssc0 program) → typed terms`. The typed layer is
built **on** the untyped one, exactly as the architecture promised.

## Typing rules (as implemented)

```
Lit n              : Int
BoolLit b          : Bool
Var x              : env(x)                         (else "unbound variable")
Lam(x, ty, body)   : ty -> infer(env, x:ty ⊢ body)
App(f, a)          : r        where infer f = (p -> r) and infer a = p   (else mismatch)
Add(a, b)          : Int      where infer a = Int and infer b = Int
If(c, t, e)        : T        where infer c = Bool and infer t = infer e = T
Let(x, e, body)    : infer(env, x:(infer e) ⊢ body)
```

Type equality (`tyEq`) is structural over `TyInt`/`TyBool`/`TyFun`. Errors are plain
strings; checking is monomorphic, synthesis-only (no unification yet).

## Examples (`conformance/check.sh`)

```
typed.ssc0     (\x:Int. x + 1) 41                     => Typed("Int", 42)
typed-fn.ssc0  let f = \x:Int. x+1 in f (f 40)        => Typed("Int", 42)
illtyped.ssc0  1 + true                               => TypeError("Add expects Int operands")
```

## Why this matters (the thesis, made concrete)

The Scala kernel never gained a type checker. The whole type system is ~130 lines of ssc0
that runs *on* the kernel. To make the type system richer (more types, inference,
type classes), you grow the **ssc0 program**, not the kernel — the kernel size is decoupled
from the language's type-system richness. That is the v2 bet: a tiny frozen core, with all
the richness expressed in the language itself.

## Textual surface (`.ssct`) — IMPLEMENTED (2026-06-27)

`v2/lib/ssct-front.ssc0` (170 lines of ssc0) is a **lexer + parser** for a real `.ssct`
concrete syntax, turning text into the `Term` AST. The whole `ssct` tool — front + checker +
evaluator — is ssc0; the kernel is unchanged. Run with `./v2/ssct <file.ssct>` (a launcher
over the ssc0 driver `bin/ssct.ssc0`).

```
expr := 'fun' x ':' ty '=>' expr | 'let' x '=' expr 'in' expr
      | 'if' expr 'then' expr 'else' expr | add
add  := app ('+' app)*        app := atom atom*        (application by juxtaposition)
atom := int | 'true' | 'false' | ident | '(' expr ')'
ty   := 'Int' | 'Bool' | '(' ty '->' ty ')'
```

The lexer scans by code unit using the `δ` string primitives (`slen`/`scodeAt`/`sslice`/
`str->i`); the parser is combinator-style (each step returns `Pair(node, restTokens)`).
That a real lexer+parser fits in ~170 lines of ssc0 is the skill a self-hosting compiler
needs. Examples (`conformance/check.sh`):

```
id.ssct    let f = fun x : Int => x + 1 in f (f 40)   => Typed("Int", 42)
cond.ssct  if true then 1 else 0                       => Typed("Int", 1)
bad.ssct   1 + true                                    => TypeError("Add expects Int operands")
```

## Deferred / next

- **Erase to ir** — instead of a bundled `evalTerm`, lower a well-typed `Term` to a Core IR
  `Data` tree and `coreir.encode` it, so the erased program runs on the VM proper (closes
  the loop with `ssc run-ir`). Needs the `coreir.encode` primitive.
- **Richer types** — type variables + unification (HM), products/sums, recursive types.
- These are tower growth — all in ssc0, none in the kernel.
