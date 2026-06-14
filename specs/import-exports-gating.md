# import-exports-gating — the interpreter must honor a module's `exports:`

Status: spec (2026-06-14).

## Problem

A module declares its public surface in the `exports:` frontmatter list. The **JS and JVM
backends already gate imports by it** (`JsGen` builds a binding only when
`childExports.isEmpty || childExports.contains(name)`; `JvmGen` likewise uses
`manifest.exports`). The **tree-walking interpreter does not**:

```scala
// Interpreter.scala
def exportedGlobals: Map[String, Value] = globals.toMap   // the ENTIRE binding map
```

`runImport` resolves an explicit `[x](M)` binding against `M.exportedGlobals` — i.e. M's whole
globals map, which includes (a) M's own defs, (b) names M imported, and (c) names dumped into M's
globals by the transitive **call-time** resolution loop (a dependency's helpers, so M's exported
functions can call them). So `[x](M)` resolves `x` if it is reachable **anywhere** in M's
transitive import-closure, regardless of M's `exports:` list — the list is advisory.

**Concrete divergence (busi):** `queryPit37Xml` is defined only in `dispatch_polish.ssc`, is not
listed in `dispatch.ssc`'s `exports:`, and is not even named in `dispatch.ssc`'s import of
`dispatch_polish` — yet `[queryPit37Xml](dispatch.ssc)` resolves under the interpreter. The same
program **fails under `emit-js`/`emit-scala`**, because those backends honor `exports:`. So the
interpreter accepts programs the compilers reject: a real interpreter↔codegen inconsistency.

## Fix

In `runImport`, when the imported module **declares** an `exports:` list, gate each explicit
binding by it — mirroring the JS/JVM backends exactly:

```
notExported = childExports.nonEmpty && !childExports.contains(sourceName)
```

A `notExported` name is **not importable by name** from that module. A module that declares **no**
`exports:` stays permissive (legacy behavior — its whole surface is importable).

The transitive **call-time** dump (childCtx → parent globals, so an exported function can call its
own/imported helpers) is **unchanged** — that is a separate, legitimate mechanism. Only the
explicit `[x](M)` binding resolution is gated. So `exportedGlobals` (full globals) still feeds
`childCtx`; a new `exportedNames` (the declared `exports:` set) gates the binding lookup.

### Error vs skip

The backends silently *skip* a non-exported binding (a codegen concern — another import line may
satisfy the name). The interpreter resolves per-binding, so the clearer, more correct behavior is a
**hard error** at import: `'<name>' is not exported by <module>`. (If the corpus proves to rely on
non-exported imports beyond a fixable handful, fall back to skip-with-warning to match the backends
byte-for-byte; the test suite is the arbiter.)

## Impact / migration

A module that re-exposes a dependency's symbol must now **list it in its own `exports:`** (the
facade re-export pattern, already used widely). Measured blast radius:
- ScalaScript `runtime/std`: 2 missing type exports (`Either` in `either.ssc`, `Functor` in
  `functor-applicative-monad.ssc`) — add them.
- busi (separate repo, adopts on its next ssc bump): 54 symbols across 3 modules
  (`api/dispatch.ssc` 44, `domain/events.ssc` 9, `domain/polish_buyer_check.ssc` 1) — add to the
  respective `exports:` lists.

## Verification

- New interpreter test: a module with `exports: [a]` defining `a` and `b`; `[a](M)` resolves,
  `[b](M)` is rejected (or skipped); a module with no `exports:` stays permissive; a transitively
  reachable-but-not-exported name is not importable from the facade, while an exported function that
  internally calls a non-exported helper still works (call-time dump intact).
- Full interpreter test suite green (after adding the std exports).
