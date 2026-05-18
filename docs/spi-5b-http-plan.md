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

### Stage A — prereq (folded into Iter 1)
- [x] Extend `NativeContext` in `backend-spi/IntrinsicImpl.scala`
- [x] Extract `registerHealthDefaults()` to class-level private method in `Interpreter.scala`
- [x] Extend anonymous `NativeContext` in `installNativeIntrinsics`

### Iter 1 — route()  ✅
- Remove `nativeP("route")` from `Interpreter.initBuiltins`
- Add `QualifiedName("route") -> NativeImpl(...)` to `InterpreterCapabilities`
- Add `QualifiedName("route") -> RuntimeCall("route")` to `JvmIntrinsics` / `JsIntrinsics`
- Tests green

### Iter 2 — serve()  ✅
- Remove `nativeP("serve")` from `Interpreter.initBuiltins`
- Add `QualifiedName("serve") -> NativeImpl(...)` to `InterpreterCapabilities`
- Add `QualifiedName("serve") -> RuntimeCall("serve")` to `JvmIntrinsics` / `JsIntrinsics`
- Tests green

### Iter 3 — stop()  ✅
- Add `QualifiedName("stop") -> NativeImpl((_, _) => ())` to `InterpreterCapabilities`
- Add `QualifiedName("stop") -> RuntimeCall("stop")` to `JvmIntrinsics` / `JsIntrinsics`
- Add `def stop(): Unit = ()` to JVM runtime preamble
- Add `function stop() {}` to JS runtime preamble
- Tests green

### Iter 4 — std/http.ssc Request / Response  ✅
- Replace TODO-stub comment with proper case class declarations
- Fields: `method`, `path`, `headers`, `body`, `form`, `files`, `cookies`,
  `session`, `json`
- Existing tests must pass without changes

### Iter 5 — extract to intrinsics/ files  ✅
- `backend-interpreter/.../intrinsics/Http.scala` — HTTP NativeImpl entries
- `backend-jvm/.../intrinsics/Http.scala` — HTTP RuntimeCall entries
- `backend-js/.../intrinsics/Http.scala` — HTTP RuntimeCall entries
- Import in the respective Capabilities files

### Iter 6 — MILESTONES.md  ✅
- Mark SPI 5+/B as landed
- Add SPI 5+/D (std.ws / auth / fs / crypto) as next step

## Open questions resolved before coding

| Question | Decision |
|---|---|
| How to give NativeImpl access to `this: Interpreter`? | Extend `NativeContext` in SPI with default-no-op HTTP methods |
| `stop()` implementation? | No-op stub; real shutdown deferred to TLS branch |
| JvmGen match-arm cleanup? | Add RuntimeCall to JvmIntrinsics; leave `blocksUseRoutes` intact |
