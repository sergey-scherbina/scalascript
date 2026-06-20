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
@main def main(name: String, count: Int): A
@main def main(args: String*): A
```

`A` may be `Unit` or any compileable return type. The generated WASM wrapper is
always `Unit`-returning and discards the user entry value after evaluating it.
The parameter list must be a Scala 3 `@main`-compatible parameter clause. Raw
CLI-style trailing arguments are represented as `String*`, not `Array[String]`.

## Behavior

- [x] An effectful WASM module with `@main def main(): Int` compiles and runs; the
      wrapper evaluates the user entry and discards the `Int`.
- [x] An effectful WASM module with `@main def main(args: String*): Unit`
      compiles and runs; the wrapper preserves the repeated-`String` parameter
      clause and calls the lowered user entry with `args*`.
- [x] An effectful WASM module with `@main def main(args: String*): Int`
      compiles and runs; the wrapper preserves the repeated-`String` parameter
      clause and discards the `Int`.
- [x] Existing zero-arg effectful WASM programs keep compiling and running.
- [x] An effectful WASM module with raw `Array[String]` `@main` args fails before
      scala-cli with a clear "use `String*`" diagnostic.

## Out of scope

- Changing non-effectful WASM entry point handling. That path is owned directly by
  Scala.js / scala-cli and already sees the user's `@main`.
- Defining browser-specific argument delivery. The wrapper only preserves the
  Scala.js-compatible `@main` parameter shape; the Scala.js launcher decides what
  argument values are supplied in a given runtime.
- Supporting raw `Array[String]` `@main` parameters. Scala 3 `@main` does not
  accept raw argv arrays; use `String*` for raw CLI-style arguments.
- Supporting multiple parameter clauses or `using` clauses on `@main`.

## Design

`JvmGen.generateUserOnly` lowers the effectful user module and strips the `@main`
annotation, leaving a plain user function. `WasmGen` then emits a synthetic
`@main def _ssc_wasm_main(...)`. The synthetic wrapper should be derived from the
source `@main` declaration:

- zero-arg source main -> `_ssc_wasm_main(): Unit = { entry(); () }`
- single Scala 3 `@main` parameter clause -> the wrapper reuses that clause and
  forwards each parameter; repeated parameters are spliced (`args*`)

The wrapper must keep the explicit trailing `()` so a non-`Unit` user return does
not become the synthetic main's return value.

## Decisions

- **Keep this WASM-only** - chosen because the bug is introduced by the WASM
  effect wrapper. Rejected: touching JVM/JS entry point code (larger surface with
  no evidence of the same issue).
- **Mirror Scala 3 `@main` parameters instead of raw argv arrays** - chosen
  because scala-cli/Scala.js rejects `@main def run(args: Array[String])` without
  a `CommandLineParser.FromString[Array[String]]`, while `String*` and typed
  parameters are the supported `@main` surface. Rejected: promising
  `Array[String]` forwarding (not a valid Scala 3 `@main` contract).
- **Discard non-Unit returns in the wrapper** - chosen because an executable
  backend runs for side effects; the observable contract is stdout/assets, not a
  returned value. Rejected: printing or exporting the return value (would create
  new semantics unrelated to this edge).

## Results

Verified 2026-06-20 with:

```bash
cd /Users/sergiy/work/my/scalascript/.worktrees/feature/wasm-main-edge && sbt "backendWasm/testOnly scalascript.codegen.WasmBackendTest"
```

Result: `WasmBackendTest` 40/40 green. The regression set covers non-`Unit`
effectful main, `String*` effectful main, `String*` + non-`Unit`, and a clear
pre-scala-cli diagnostic for raw `Array[String]` args.

Gotcha: a direct Scala.js/WASM ES-module probe with `@main def run(args: String*)`
and `node main.js red blue` produced empty args. That is launcher/runtime argument
delivery, not the ScalaScript effect wrapper. This feature preserves the
Scala.js-compatible main parameter clause and makes effectful WASM compile/run;
it does not define a new argv transport for browser/ES-module WASM.
