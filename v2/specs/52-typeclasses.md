# 52 — Typeclasses (instance resolution + dictionary passing)

> Status: **v1 (2026-06-27)** — `v2/lib/typeclass.ssc0`. Typeclasses are an **elaboration**:
> type-directed *instance resolution* producing *dictionaries* (records of methods) passed
> explicitly. The kernel needs nothing — typeclasses erase to plain function/record passing.

## The mechanism

- An **instance** is a dictionary (here, a `Show` dictionary is just the `show` function).
- **Resolution** maps a type to its dictionary, including **conditional instances**:
  ```
  resolveShow TIntR       = showInt
  resolveShow TBoolR      = showBool
  resolveShow (TListR e)  = showListWith (resolveShow e)     -- Show a => Show (List a)
  ```
- A **constrained** generic `show : Show a => a -> String` is, after elaboration,
  `show t v = (resolveShow t) v` — it receives (or resolves) the dictionary and dispatches.

The conditional instance `Show a ⇒ Show (List a)` is the heart of typeclasses: resolution is
*recursive* over the type structure. `resolveShow (TListR (TListR TIntR))` builds the
`List(List Int)` dictionary from the `List` and `Int` ones.

## Examples (`conformance/check.sh`)

```
show (List Int)        [1,2,3]          =>  "[1, 2, 3]"
show (List (List Int)) [[1,2],[3]]      =>  "[[1, 2], [3]]"   (deep recursive resolution)
show (List Bool)       [true,false]     =>  "[true, false]"
```

## Integrated into the ssct typer — IMPLEMENTED (2026-06-27)

`lib/ssct.ssc0` now does **automatic** resolution. A `show e` use site (term `ShowM e`) does
*not* name an instance; the typer:

1. **enforces the constraint** — `infer (ShowM e)`: infer `e`'s type `T`; if `T` has a `Show`
   instance (`Int`/`Bool`) then `ShowM e : String`, else a **type error** (`show` on a function
   is rejected — `tc-show-err.ssc0` ⇒ `TypeError("no Show instance …")`);
2. **resolves + inserts the dictionary** — `elaborate` walks the well-typed term and rewrites
   each `ShowM e` to `ShowDispatch(instFor(typeOf e), e')`, choosing `showInt`/`showBool` from
   the *inferred* type. `check` = `infer` (reject ill-typed) → `elaborate` → run.

So the use site is instance-agnostic; the typer picks the instance from the type. Examples:
`show (1+2)` ⇒ `Typed("String", "3")`, `show true` ⇒ `Typed("String", "true")`,
`show (fun x:Int. x)` ⇒ type error. (`lib/typeclass.ssc0` remains the standalone resolver with
recursive/conditional instances, e.g. `Show a ⇒ Show (List a)`.)

## Why this matters

ssc 1.0 has full `given`/`using` resolution. v2 reproduces the semantics — type-directed
dispatch with conditional instances — as a ~25-line ssc0 library. Multi-method classes
(`Eq`, `Num`, `Ord`) are dictionaries with several fields; superclasses are dictionary fields
pointing at other dictionaries. All elaboration, no kernel change.
