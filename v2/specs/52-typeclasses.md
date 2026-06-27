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

## Relation to the typed layer

`lib/typeclass.ssc0` shows the *elaborated* form (the type rep is passed explicitly). The
`ssct` typer (`specs/40`) is where the **automatic** step lives: from the *inferred* type of
each `show` use site, insert the corresponding `resolveShow` (the dictionary), then erase to
ordinary calls. So "typeclasses" = ssct inference + this resolution + erasure — every piece a
program on the frozen kernel; the kernel sees only records and functions.

## Why this matters

ssc 1.0 has full `given`/`using` resolution. v2 reproduces the semantics — type-directed
dispatch with conditional instances — as a ~25-line ssc0 library. Multi-method classes
(`Eq`, `Num`, `Ord`) are dictionaries with several fields; superclasses are dictionary fields
pointing at other dictionaries. All elaboration, no kernel change.
