# v2 JS Lane Bridge

## Overview

The v2 production path already owns the default VM runner and the JVM bytecode
lane. The JS lane bridge makes the existing CoreIR-to-JavaScript generator usable
from the main CLI for `.ssc` sources through the same frontend bridge:

```text
.ssc source -> FrontendBridge -> CoreIR Program -> v2 JsGen -> node
```

This is an additive opt-in path until its gate is broad enough to replace the
legacy v1 `run-js`/`emit-js` implementation for default JS-target execution.

## Interface

- `ssc run-js --v2 <file.ssc> [args...]` runs the source through the v2 JS lane.
- `ssc run-js <file.ssc>` without `--v2` keeps the existing v1 JsGen behavior.
- The v2 JS backend is buildable from sbt as a normal module, not only through
  `scala-cli run v2/backend/js`.
- The v2 JS generator remains callable as a library API:
  `ssc.js.JsGen.generate(program)`.

## Behavior

- [ ] `v2JsBackend/compile` builds the CoreIR JS generator under sbt.
- [ ] `ssc run-js --v2 examples/hello.ssc` prints the same output as
      `ssc run --v2 examples/hello.ssc`.
- [ ] CLI argv reaches generated JS through Node's `process.argv`, so
      `io.args`-style programs see arguments after the source file.
- [ ] `ssc run-js <file.ssc>` without `--v2` keeps the legacy v1 JsGen path.
- [ ] The existing CoreIR backend harness still passes the JS lane for kernel
      fixtures.
- [ ] A focused CLI regression covers the `run-js --v2` route and the legacy
      route-selection guard.

## Out of Scope

- Switching `ssc run --target js`, front-matter `backend: js`, or plain
  `run-js` to the v2 JS lane.
- Browser/SPA output, DOM rendering, npm package installation, or JS plugin
  intrinsic parity beyond what the current CoreIR JS preamble already supports.
- Rust or WASM lane work.

## Design

The first slice reuses the existing `v2/backend/js/JsBackend.scala` generator
instead of introducing a second JS emitter. A new sbt project should point at
that source directory and depend on `v2Core`, mirroring `v2JvmBytecode`'s role
for the JVM lane. The CLI can then add `RunV2.runJs(...)`: load plugin jars for
FrontendBridge conversion, convert the source to CoreIR, emit JavaScript, write a
temporary `.cjs`, and execute it with the existing `runNodeAndWait` helper.

The generated JS runtime owns `io.args` by reading Node argv, so the CLI should
pass user args after the temp script path rather than trying to mutate
`ssc.Runtime.argv` as the VM lane does.

## Decisions

- **Opt-in `run-js --v2` first** — chosen because the existing v1 JS lane powers
  conformance and browser-related flows. Rejected: flipping `run-js` immediately,
  because the v2 JS preamble does not yet cover plugin/browser/npm surfaces.
- **Sbt module before CLI use** — chosen so the fat-jar CLI can compile without
  shelling out to `scala-cli` for its own backend. Rejected: invoking
  `scala-cli run v2/backend/js` from the CLI, because that would make production
  JS execution depend on a developer toolchain.
- **Node-only first slice** — chosen because `run-js` is already the Node runner.
  Rejected: mixing SPA/browser output into this slice; that belongs to the
  existing frontend toolkit gates.

## Results

Fill this after verification with exact commands and counts.
