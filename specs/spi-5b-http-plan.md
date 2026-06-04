# SPI 5+/B — std.http intrinsic table migration

Branch: `feature/spi-5b-http`

## Goal

Replace hardcoded `nativeP("route")` / `nativeP("serve")` dispatch in
`Interpreter.initBuiltins` with the shared `IntrinsicImpl` pipeline, and
register `route` / `serve` / `stop` in `JvmIntrinsics` / `JsIntrinsics` so
`CapabilityCheck` can validate them end-to-end.

Done criteria (from task spec):
- `grep -c "nativeP(\"route\")\|nativeP(\"serve\")\|nativeP(\"stop\")" Interpreter.scala == 0`
- `sbt test` green on all three backends
- conformance green
- `examples/rest-api.ssc` runs

## Architecture decision

`route()` and `serve()` need interpreter-level access (`Routes.register`,
`headless`, `registerHealthDefaults`).  `NativeContext` (SPI layer) will be
extended with three default-no-op methods so HTTP `NativeImpl` entries can
live in `InterpreterCapabilities.scala` without a circular dependency:

```scala
trait NativeContext:
  def out: java.io.PrintStream
  def err: java.io.PrintStream
  // HTTP-server hooks — default no-op; interpreter overrides in
  // installNativeIntrinsics' anonymous NativeContext.
  def headless: Boolean = false
  def registerRoute(method: String, path: String, handler: Any): Unit = ()
  def registerHealthDefaults(): Unit = ()
```

`Interpreter.installNativeIntrinsics` will override all five methods.
The local `registerHealthDefaults` in `initBuiltins` will be extracted to a
private class-level method so the override can reference `Interpreter.this`.

`stop()` is a no-op stub in all backends (real shutdown deferred to the TLS
branch).

JvmGen / JsGen have no `emitExpr` match arms for HTTP; `blocksUseRoutes` is
left untouched.  Only `JvmIntrinsics` / `JsIntrinsics` receive new
`RuntimeCall` entries for `CapabilityCheck`.

## Stages

### Stage A — prereq (folded into Iter 1)  ✅ DONE 2026-05-18

- [x] Extend `NativeContext` in `backend-spi/IntrinsicImpl.scala`
- [x] Extract `registerHealthDefaults()` to class-level private method in `Interpreter.scala`
- [x] Extend anonymous `NativeContext` in `installNativeIntrinsics`

### Iter 1 — route()  ✅ DONE 2026-05-18

- [x] Remove `nativeP("route")` from `Interpreter.initBuiltins`
- [x] Add `QualifiedName("route") -> NativeImpl(...)` to `HttpIntrinsics`
- [x] Add `QualifiedName("route") -> RuntimeCall("route")` to `JvmHttpIntrinsics` / `JsHttpIntrinsics`
- [x] Tests green (123/123)

### Iter 2 — serve()  ✅ DONE 2026-05-18

- [x] Remove `nativeP("serve")` from `Interpreter.initBuiltins`
- [x] Add `QualifiedName("serve") -> NativeImpl(...)` to `HttpIntrinsics`
- [x] Add `QualifiedName("serve") -> RuntimeCall("serve")` to `JvmHttpIntrinsics` / `JsHttpIntrinsics`
- [x] Tests green

### Iter 3 — stop()  ✅ DONE 2026-05-18

- [x] Add `QualifiedName("stop") -> NativeImpl((_, _) => ())` to `HttpIntrinsics`
- [x] Add `QualifiedName("stop") -> RuntimeCall("stop")` to `JvmHttpIntrinsics` / `JsHttpIntrinsics`
- [x] Add `def stop(): Unit = ()` to JVM serveRuntime preamble
- [x] Add `function stop() {}` to JS JsRuntimePart1b
- [x] Tests green

### Iter 4 — std/http.ssc Request / Response  ✅ DONE 2026-05-18

- [x] Replaced TODO-stub comment with proper case class declarations
- [x] Fields: `method`, `path`, `headers`, `body`, `form`, `files`, `cookies`, `session`, `json`
- [x] Existing tests pass without changes

### Iter 5 — extract to intrinsics/ files  ✅ DONE 2026-05-18

- [x] `backend-interpreter/.../intrinsics/Http.scala` — `HttpIntrinsics` (NativeImpl)
- [x] `backend-jvm/.../intrinsics/Http.scala` — `JvmHttpIntrinsics` (RuntimeCall)
- [x] `backend-js/.../intrinsics/Http.scala` — `JsHttpIntrinsics` (RuntimeCall)
- [x] Capabilities files use `++ HttpIntrinsics` / `++ JvmHttpIntrinsics` / `++ JsHttpIntrinsics`

### Iter 6 — MILESTONES.md  ✅ DONE 2026-05-18

- [x] SPI 5+/B marked as landed
- [x] SPI 5+/D noted as next step

## Open questions resolved before coding

| Question | Decision |
|---|---|
| How to give NativeImpl access to `this: Interpreter`? | Extend `NativeContext` in SPI with default-no-op HTTP methods |
| `stop()` implementation? | No-op stub; real shutdown deferred to TLS branch |
| JvmGen match-arm cleanup? | Add RuntimeCall to JvmIntrinsics; leave `blocksUseRoutes` intact |
