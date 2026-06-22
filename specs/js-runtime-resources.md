# JS runtime fragments as `.mjs` resources (not Scala string constants)

Status: **in progress** — optics pilot landing 2026-06-22.

## Problem

The JS backend ships its runtime support code (the helper functions the emitted JS calls) as
**large Scala string constants** — `JsRuntimeOptics`, `JsRuntimePart2a/2b`, `JsRuntimeSignals`,
`JsRuntimeFs`, `JsRuntimeMcp`, `JsRuntimeGraphql`, `JsRuntimeAsyncA/B`, … (a dozen-plus). Each is a
`val X: String = """ …real JavaScript… """` inside a `.scala` file.

Downsides:
- **No tooling on the embedded JS** — no editor syntax highlighting, no `prettier`/`eslint`, no
  `node --check`, no `tsc --checkJs`. A JS syntax error is only caught at runtime / by a node smoke
  test, late.
- Two languages tangled in one file; awkward to read and diff.

This is the cleanup flagged in [`polyglot-libraries.md`](polyglot-libraries.md) §3 item 8 ("move
codegen feature runtime strings into plugin `runtimePreamble`"). It also aligns with Task B: these
runtime fragments literally become publishable host libraries (e.g. `@scalascript/optics`), so
storing them as real `.js` is a step toward that.

## Approach (A — chosen)

Move each fragment into a real `.mjs` resource file and load it through a tiny cached loader,
**keeping the public `val X: String` API unchanged** so no call site changes:

```
runtime/backend/js/src/main/resources/scalascript/js-runtime/optics.mjs   ← real JavaScript
```
```scala
// JsRuntimeOptics.scala
val JsRuntimeOptics: String = JsRuntimeResource.load("optics.mjs")
```

`JsRuntimeResource.load(name)` reads `/scalascript/js-runtime/<name>` from the classpath as UTF-8,
caches it in a `ConcurrentHashMap`, and fails loudly if the resource is absent.

### Hard invariant: byte-identical emitted JS

The migration must **not change a single byte** of generated output. `JsGen` appends
`JsRuntimeOptics` verbatim and depends on its exact shape (e.g. it special-cases the trailing
newline; `JsLibPackager` does `stripPrefix("\n")` on the leading newline). Therefore the `.mjs`
content is the *verbatim* triple-quoted body — including the **leading newline** and trailing
newline — extracted mechanically, not retyped. Guards: the existing optics conformance + the
`JsLibPackager` golden + node-ESM smoke must stay green unchanged.

### Why optics is the pilot

Smallest self-contained fragment (depends only on `_None`/`_Some`/`_isMap`), and it already has a
golden `.d.ts` + an `assume(node)` ESM runtime smoke (`JsLibPackagerTest`) — so the "string →
resource" refactor is immediately and end-to-end verifiable at minimal risk. Once proven, the same
`JsRuntimeResource.load(...)` mechanically absorbs the other fragments.

## Rejected alternatives

- **B — build-time source generator** (sbt `sourceGenerators` bakes `.mjs` files into the Scala
  constants at compile time). Keeps the string compiled-in (no runtime resource IO) but adds build
  complexity; only worth it under a no-runtime-IO constraint (e.g. GraalVM native-image). Not needed
  for the JVM/CLI packaging used today.
- **C — runtime as a standalone TS project** compiled separately. The endgame (real type-checking,
  matches Task B), but pulling a TS toolchain into the build is a larger commitment; defer.

## Validation

- `node --check` on the raw `.mjs` resource (assume-gated test) — catches JS syntax errors at test
  time, the concrete win.
- `JsLibPackagerTest` (golden `.d.ts` + node ESM smoke) unchanged green ⇒ behavior + byte-identity.
- Optics conformance (`tests/conformance/optics-*.ssc`) unchanged green.

`tsc --checkJs` / `eslint` as a CI gate is a follow-up (the runtime JS is intentionally loose;
gating on `tsc` would need JSDoc annotations first).

## Follow-ups

- Migrate the remaining `JsRuntime*` string fragments to `.mjs` resources via the same loader
  (closes polyglot-libraries §3 #8 for JS). Each is mechanical + guarded by its existing tests.
- Mirror the pattern for JVM/Rust runtime-string fragments if the win proves out.
