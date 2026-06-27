# 60 — Backend: Core IR → JavaScript

> Status: **v1 (2026-06-27)** — `v2/lib/backend-js.ssc0`. The first *target* backend: a
> translator from Core IR to JavaScript, **written in ssc0**. v2 now emits runnable artifacts
> with no JVM. This is "one source, many targets": same `ir`, a new target, all on the frozen
> kernel.

## How

The backend reuses `ssc0c`'s front (`source → IrProg` Data), then walks the IR emitting JS —
the same tree `coreir.encode` serializes, rendered as JavaScript instead of S-expr:

| Core IR | JavaScript |
|---|---|
| `IrLocal i` (de Bruijn) | `v{d-1-i}` — a named var by absolute binder depth |
| `IrLam n body` | `(v{d}, …) => body` (arrow) |
| `IrApp f args` | `f(args…)` |
| `IrGlobal name` | `$name` (top-level `const`) |
| `IrIf` | `(c ? t : e)` |
| `IrPrim "i.add" …` | `(a + b)`, etc. |
| `IrCtor tag fs` | `({t:"tag", f:[fs…]})` |
| `IrMatch` | IIFE dispatching on `$s.t`, binding `$s.f[j]` |
| `IrLet` / `IrLetRec` | IIFE with consts |

A small `show` prelude renders values exactly like the VM's `Show`, so outputs are
comparable. `./ssc0-js f.ssc0 | node`.

## TCO via a trampoline (2026-06-27)

JavaScript has no guaranteed tail calls, so the codegen is **tail-aware**: functions take an
args array and return a *Step* — a value, or a `bounce(f, a)` object. A tail `IrApp` emits a
`bounce` instead of calling; the universal `app(f, a)` loops over bounces in a `while`. So a
tail call runs in **constant stack** — the IR/VM TCO guarantee (invariant 7), now honored in
JS. `genE(d, term, tail)` carries the tail flag: it bounces only a tail `IrApp`, and threads
`tail` through `If`/`Match`/`Let`/`LetRec` tail positions; everything else is value mode.

## Conformance (`conformance/check.sh`)

`node (ssc0-js X)` == `ssc run X` for `fact`, `map`, `calc`, **and `tco`** (the 1e6-deep tail
loop, `500000500000`, now runs in constant stack).

## Why this matters

The backend is itself a program on the tower (`ssc0c` front + a ~110-line ssc0 code
generator). To add a target you write a walker over the same IR — the kernel does not change.
Next: `ir → Rust` (`specs/61-backend-rust.md`), then WASM; a TCO-trampoline pass for JS.
