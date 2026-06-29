# 57 — Multi-op typed handler resumes

## Overview

`handleM` closes the gap between typed effect operation signatures and user-written handlers.
The existing general `handle` stays as the dynamic, fully general handler surface; `handleM`
is the typed convenience form for declared operations of one effect label. It gives every
operation arm its own argument type and resume type while still erasing to the existing
`__effHandle` runtime helper.

## Interface

```ssc
handleM "Effect" comp {
  | op1 arg k => body1
  | op2 arg k => body2
} ret
```

Typing rule:

```text
comp : Comp {Effect | rho} a
ret  : a -> Comp rho b
op_i : Arg_i -> Reply_i
arg  : Arg_i
k    : Reply_i -> Comp rho b
body : Comp rho b
--------------------------------
handleM "Effect" comp { arms } ret : Comp rho b
```

The label must be a string literal. Arm names are operation names declared for that effect,
either through `effect Effect op...` or `effect Effect { op : Arg -> Reply, ... }`.
For typed declarations, `arg` and `k` use the declared `Arg` and `Reply`; for untyped
declarations they fall back to `Dyn`, matching the existing light effect-row surface.

## Behavior

- [ ] A typed effect with multiple operations gives each arm an independent resume type.
- [ ] Resuming an arm with the wrong reply type is rejected at type-check time.
- [ ] A missing declared operation arm is rejected before erase/codegen.
- [ ] Duplicate operation arms are rejected before erase/codegen.
- [ ] Unknown operations, or operations declared under a different effect label, are rejected.
- [ ] `handleM` removes exactly the handled effect label from the row and composes with other
      effects through the same row-polymorphic rule as `handle`.
- [ ] VM IR, JS, and Rust output all use the existing `__effHandle` runtime; no backend or
      kernel change is required.

## Out of scope

- Replacing or changing the existing general `handle` form.
- New runtime representation for effects, new backend primitives, or kernel continuation support.
- Polymorphic/higher-rank operation signatures.
- Forwarding missing operations of the handled label. `handleM` is total for the declared ops it
  removes; use general `handle` for dynamic/partial dispatch.

## Design

The parser recognizes `handleM` as a keyword form and produces `EffHandleM(label, comp, ret, arms)`.
Each arm stores the operation name, argument binder, resume binder, and body.

The type checker first validates the arm set against `effOpReg`: every operation declared for the
label must appear exactly once, and no arm may name an operation from another label or an unknown
operation. It then infers the handled computation and return clause, allocates `rho` and result
type `b`, and checks each arm body in an environment extended with:

```text
arg : Arg_i
k   : Reply_i -> Comp rho b
```

`effSigReg` supplies `Arg_i`/`Reply_i` when present; otherwise both use `Dyn`.

The eraser generates a normal `op` function for `__effHandle`:

```ssc
fun opName => fun realArg => fun resume =>
  if opName == "op1" then body1
  else if opName == "op2" then body2
  else <unreachable>
```

The fallback is intentionally unreachable because type checking rejects incomplete or invalid
arm sets.

## Decisions

- **Dedicated `handleM` keyword** — chosen because it preserves `handle`'s dynamic dispatch
  contract and avoids changing its existing four-argument application-spine parsing.
- **Total coverage for declared ops** — chosen because `handleM` removes the handled label from
  the row. A partial typed handler would need a different surface that forwards the same label.
- **Erase to `__effHandle`** — chosen to keep the feature frontend/typer/emit-only and maintain
  identical runtime behavior across VM, JS, and Rust.

## Results

TBD after conformance verification.
