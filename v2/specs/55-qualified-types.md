# 55 — Qualified types for ssct-hm (design; implementation deferred)

> Status: **design, 2026-06-28.** This is the one genuinely multi-session item left. It documents the
> approach precisely enough to implement in a focused follow-up, and explains why it is *not* landed as a
> single green-every-commit increment in the autonomous pass. Everything else on the K11 frontier shipped.

## What it buys

Today numeric overloading uses **eager defaulting** (K8): at an operator node the operands are unified and
an unresolved type variable defaults to `Int`. That is sound and covers the vast majority of code, but it
has a documented sharp edge — a function whose numeric type is *unanchored* monomorphises to `Int` and
cannot be used at `Float`:

```
let twice = fun x => x + x in (twice 5, twice 2.5)   -- today: `twice` is Int -> Int; `twice 2.5` is a type error
```

User typeclasses (`method`/`instance`, spec 52) have the dual limitation: a `method` is resolved
**monomorphically by the argument's type-head at the call site**, so a method used on a *type variable*
(inside a polymorphic function) cannot be resolved. Qualified types remove both limitations:

```
twice : ∀a. Num a => a -> a          -- works at Int and Float
let m = fun x => compare x x in …    -- Ord a => …, instance chosen by the caller
```

## Why it is multi-session (and was deferred in the autonomous pass)

The type-level half (constraints) is moderate; the **runtime half is the cost**. HM-with-classes needs one
of two whole-program transforms, both of which touch the erase pass deeply and risk the project's
green-every-commit invariant if rushed:

1. **Dictionary passing** — a constrained function gains a hidden parameter per constraint; constrained
   operations become dictionary applications; every call site supplies the resolved dictionary, *threading*
   an enclosing function's dictionary parameter when the type argument is still polymorphic. The threading
   (call site → which dict) is the classic hard part.
2. **Monomorphisation** — specialise each constrained function per type it is used at and rewrite calls.
   Avoids dict params but needs whole-program use-set collection + cloning + call rewriting.

A runtime tag on numeric values (dispatch `+` at run time) is **not** viable across backends: JS cannot
distinguish `3.0` from `3` (both `number`), and Rust values are unboxed (`i64` vs `f64`). So the operation,
not the value, must carry the type information — i.e. dictionaries/specialisation. That is exactly why K8
chose static eager-defaulting.

## Recommended design (dictionary passing, "tag" dictionaries)

The values at any concrete call stay concretely typed (Int values for an `Int` instance, Float for `Float`)
— only the *operations* are passed. So a `Num` dictionary can be just a **type-tag string** plus global
tag-dispatching helpers; no record dictionaries, no per-class plumbing for the built-in classes.

1. **Constraints.** `Constraint(className, var)`. Inference threads a constraint set alongside the
   substitution. At an overloaded op / `method` use on a still-unresolved var `a`, emit `Num a` /
   `Ord a` instead of defaulting; id-tag the node and record "tag comes from var `a`" in `tcReg`.
2. **Schemes.** `Forall(qs, constraints, ty)`. `generalize` quantifies the constrained vars and keeps the
   constraints that mention them; `instantiate` freshens both, adding the fresh constraints to the current
   set. A constraint whose var resolves to a concrete type is **discharged** (look up the instance / verify
   `Int`·`Float` is `Num`); one still unresolved at the top is **defaulted** (`Num`→`Int`) — this preserves
   today's behaviour for monomorphic programs exactly.
3. **Dictionary-passing erase.** A `let`-bound value with `k` constraints erases to a lambda with `k`
   leading dict params (prepend their names to the `scope`, so de Bruijn indices auto-adjust). A constrained
   op/method node erases to a dict application: `__nadd(tag, a, b)` for `Num` (global helper:
   `__nadd("Int",x,y)=i.add` / `__nadd("Float",x,y)=f.add`), or `dict arg` for a user class. Every call of a
   constrained binding supplies the dict: a **literal tag** if the type argument is concrete, or the
   **enclosing dict param** (via the `scope`) if it is still polymorphic. Backends are unchanged — dicts are
   plain string/closure values and `__n*` are ordinary global helpers.
4. **User classes** need method **signatures** (`method compare : a -> a -> Int in …`) so a polymorphic use
   has a known result type; the dict is a record of the instance's impls. (Built-in `Num`/`Ord` already have
   known signatures, so they can ship first, before user-class signatures.)

## Suggested slicing (for the focused follow-up)

- K11.3a constraint set in `infer` (thread it; collect `Num`/`Ord` on unresolved op/method vars).
- K11.3b `Forall(qs, constraints, ty)`; generalize/instantiate/discharge/default (top-level Num→Int).
- K11.3c dict-passing erase for **built-in `Num`** (tag dicts + `__nadd`/… helpers + call-site tags).
- K11.3d extend to `Ord` and to **user classes** (method signatures + record dicts).
- K11.3e demo + conformance: `twice` at Int & Float; a generic `min3` over a user `Ord`; `r*r*pi` unanchored.

## Components (when implemented)

| Where | What |
|---|---|
| `lib/ssct-hm.ssc0` | `Constraint`; constraint set threaded in `infer`; `Forall` w/ constraints; discharge + top-level default; `tcReg` records the dict source per node |
| `lib/ssct-hm-front.ssc0` | (user classes) `method m : T` signature syntax |
| `lib/ssct-hm-emit.ssc0` | dict-param prepend on constrained lets; op/method → dict application; call-site dict args; `__nadd`/`__nsub`/… global helpers |
| backends | none — dicts are plain values, helpers are ordinary globals |
