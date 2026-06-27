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

## Conformance (`conformance/check.sh`)

For `fact`, `map`, `calc`: `node (ssc0-js X)` == `ssc run X` (same observable output).

## Known limitation

`tco.ssc0` (a 1e6-deep tail loop) overflows in node — **JavaScript has no guaranteed tail
calls**. The Core IR guarantees TCO (the VM honors it); a faithful JS backend would lower
tail calls to a trampoline/`while`. Deferred (a codegen pass, in ssc0). Everything non-deeply-
recursive runs.

## Why this matters

The backend is itself a program on the tower (`ssc0c` front + a ~110-line ssc0 code
generator). To add a target you write a walker over the same IR — the kernel does not change.
Next: `ir → Rust` (`specs/61-backend-rust.md`), then WASM; a TCO-trampoline pass for JS.
