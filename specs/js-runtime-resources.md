# JS runtime fragments as `.mjs` resources (not Scala string constants)

Status: **done for JS** (2026-06-22) — all 18 `JsRuntime*` string fragments migrated to `.mjs`
resources (optics pilot + the remaining 17). Closes polyglot-libraries §3 #8 for the JS backend.

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

## Migration result (2026-06-22)

All 18 `JsRuntime*` fragments now load from `.mjs` resources via `JsRuntimeResource.load`:
`optics` (pilot) + `part1a`/`part1b`/`part1c`/`part1d`, `part2a`/`part2b`, `asynca`/`asyncb`,
`signals`, `dataset`, `indexeddb`, `browserpatch`, `graphql`, `mcp`, `mcpbrowser`, `payment`,
`v14effects`. Each `.mjs` body is **byte-identical** to the prior Scala triple-quoted literal
(mechanically extracted + `diff`-verified vs `git HEAD`), so the emitted JS is unchanged. The two
aggregator `JsRuntime` in `JsGen.scala` is computed and stays as-is. Verified: `backendJs` compiles +
65 JS codegen tests (tree-shaking, transitive imports, content-toolkit, optics node-smoke) green.

Note on the "lintable" win: self-contained capability fragments (optics, signals, payment, graphql,
mcp, …) are independently `node --check`-able; the always-included core (`part1a`, `part2a`, `part2b`)
is split for concatenation/interleaving (gated `optics` sits between `part2a` and `part2b`), so per-file
parse may span a boundary — those still gain editor highlighting + clean diffs, and lint as the
concatenated `JsRuntime` blob.

### Consolidating size-only splits (2026-06-22)

The `.mjs` move removed the JVM 65 535-byte string-constant cap that originally forced some fragments
to be split. Keeping **logical** boundaries, only the genuinely size-driven split was consolidated:
`asynca.mjs` + `asyncb.mjs` → **`async.mjs`** (`JsRuntimeAsync` now `load("async.mjs")`; the
`JsRuntimeAsyncA`/`B` vals + files are gone). The `part1*` fragments are NOT size-splits —
`part1b`/`part1c`/`part1d` are each **capability-gated** (`HtmlDsl` / `Jwt` / `WsServer`; see
`JsGen.generateRuntime`'s `if caps.contains(...)`), so they stay separate to preserve per-capability
tree-shaking. `part2a`/`part2b` stay separate because gated `optics` is concatenated between them.
Byte-identical (`async.mjs` == `asynca` + `asyncb`, `cmp`-clean; async/streams/effects tests green).

## Follow-ups

- Mirror the pattern for JVM/Rust runtime-string fragments if the win proves out.
- Optional `tsc --checkJs`/`eslint` CI gate on the self-contained `.mjs` (needs JSDoc first).
