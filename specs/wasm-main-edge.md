# WASM main entry edge

## Overview

The WASM backend's effectful path reuses JVM CPS lowering and then re-adds a
Scala.js `@main` wrapper. The wrapper must preserve the user entry point shapes
that Scala.js accepts for executable modules, while keeping the generated wrapper
itself `Unit`-returning.

## Interface

This feature affects only `ssc emit-wasm` / the `wasm` backend when a module uses
ScalaScript algebraic effects and therefore goes through `WasmGen.compileEffectfulToWasm`.

Supported user entry point forms:

```scala
@main def main(): A
@main def main(args: Array[String]): A
```

`A` may be `Unit` or any compileable return type. The generated WASM wrapper is
always `Unit`-returning and discards the user entry value after evaluating it.

## Behavior

- [ ] An effectful WASM module with `@main def main(): Int` compiles and runs; the
      wrapper evaluates the user entry and discards the `Int`.
- [ ] An effectful WASM module with `@main def main(args: Array[String]): Unit`
      compiles and runs; the wrapper accepts `Array[String]` and forwards it to
      the lowered user entry.
- [ ] An effectful WASM module with `@main def main(args: Array[String]): Int`
      compiles and runs; the wrapper forwards args and discards the `Int`.
- [ ] Existing zero-arg effectful WASM programs keep compiling and running.

## Out of scope

- Changing non-effectful WASM entry point handling. That path is owned directly by
  Scala.js / scala-cli and already sees the user's `@main`.
- Defining browser-specific argument delivery. The wrapper only preserves the
  Scala.js-compatible `Array[String]` parameter shape.
- Supporting arbitrary `@main` parameter lists beyond zero args and a single
  `Array[String]` parameter.

## Design

`JvmGen.generateUserOnly` lowers the effectful user module and strips the `@main`
annotation, leaving a plain user function. `WasmGen` then emits a synthetic
`@main def _ssc_wasm_main(...)`. The synthetic wrapper should be derived from the
source `@main` declaration:

- zero-arg source main -> `_ssc_wasm_main(): Unit = { entry(); () }`
- single `Array[String]` source main -> `_ssc_wasm_main(args: Array[String]): Unit = { entry(args); () }`

The wrapper must keep the explicit trailing `()` so a non-`Unit` user return does
not become the synthetic main's return value.

## Decisions

- **Keep this WASM-only** - chosen because the bug is introduced by the WASM
  effect wrapper. Rejected: touching JVM/JS entry point code (larger surface with
  no evidence of the same issue).
- **Support only Scala.js-compatible CLI arg shape** - chosen because `Array[String]`
  is the standard Scala `@main` CLI args shape. Rejected: varargs and arbitrary
  typed main params (scala-cli owns those for non-effectful code, and reproducing
  that parser in `WasmGen` would be speculative).
- **Discard non-Unit returns in the wrapper** - chosen because an executable
  backend runs for side effects; the observable contract is stdout/assets, not a
  returned value. Rejected: printing or exporting the return value (would create
  new semantics unrelated to this edge).

## Results

To be filled during verification.
