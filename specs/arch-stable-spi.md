# Stable Plugin SPI — Specification

Status: **partially implemented**.  Phase 1 landed 2026-05-29; Phase 2
landed 2026-05-29 as a source-compatible capability bridge.  Tracked as
`arch-stable-spi` milestone in `BACKLOG.md`.
Companion specs: [`specs/plugin-architecture.md`](plugin-architecture.md),
[`specs/spi-intrinsics-design.md`](spi-intrinsics-design.md).

---

## 1. Goals

- **Decouple plugin authors from interpreter internals.**  Today all 16
  `*Intrinsics.scala` files import `scalascript.interpreter.{Value,
  Computation, InterpretError, JsonParser, OAuthBridge, …}`.  Any refactor
  inside `core` breaks every plugin.
- Introduce a **versioned, stable** `scalascript-plugin-api` module with
  opaque / sealed re-exports of the runtime types plugins legitimately need.
- Replace the **god-interface `NativeContext`** (~30 default methods spanning
  HTTP, WS, DB, validation, mount, storage) with a composable set of
  capability traits so plugins declare exactly what they use.
- **Close the in/out asymmetry**: the third-party plugin path already works
  (ServiceLoader + sscpkg); the only barrier is that plugins written outside
  this repo still bind transitively to `scalascript.interpreter.*`.

## 2. Non-goals

- Removing existing `scalascript.interpreter.*` types — they stay; only
  the **export surface** changes.
- Binary stability guarantees for v1.x — only source-level stability.
- Migrating all 16 existing plugins at once — migration is phased.
- Changing the `Backend` or `IntrinsicImpl` trait signatures — only what
  `NativeContext` exposes changes.

## 3. Architecture

### 3a. New module: `scalascript-plugin-api`

Sits between `backend/spi` and interpreter `core`.  Contains:

```
scalascript.plugin.api
  ├── PluginValue           // opaque alias for interpreter Value
  ├── PluginError           // opaque alias for InterpretError
  ├── PluginComputation     // opaque alias for Computation
  ├── JsonCodec             // stable JSON read/write surface (no JsonParser)
  └── PluginContext         // entry point replacing NativeContext
```

`PluginValue`, `PluginError`, `PluginComputation` are opaque type aliases
defined in `scalascript-plugin-api` that expand to the real types inside
the module but are opaque at the boundary.  Plugins that only call SPI
methods never see `scalascript.interpreter.*` directly.

### 3b. Capability-based `NativeContext` replacement

Expose `PluginContext` composed of capability traits:

```scala
trait HttpCap { def httpGet(url: String, headers: Map[String, String]): Future[HttpResponse] }
trait WsCap   { def openWs(url: String): Future[WsHandle] }
trait DbCap   { def dbConnect(url: String, props: Map[String,String]): Future[DbHandle] }
trait StorageCap { def storeGet(key: String): Option[String]; def storePut(...) }
trait ValidateCap { def validate(value: PluginValue, schema: String): List[String] }
trait MountCap    { def mountRoute(method: String, path: String, handler: ...): Unit }

type PluginContext = HttpCap & WsCap & DbCap & StorageCap & ValidateCap & MountCap
```

A plugin declares only the caps it needs:

```scala
// Before:
NativeImpl("jsonParse") { (ctx: NativeContext, args: List[Any]) => ... }

// After:
NativeImpl("jsonParse") { (ctx: StorageCap, args: List[PluginValue]) =>
  PluginComputation.pure(JsonCodec.parse(args.head.asString))
}
```

For source compatibility, Phase 2 keeps the existing
`NativeImpl.eval: (NativeContext, List[Any]) => Any` signature and adds
`PluginNative.eval`, a typed bridge that adapts `NativeContext` into
`PluginContext`, wraps arguments as `PluginValue`, and unwraps
`PluginComputation`.  A future breaking SPI revision may move the typed
signature directly into `NativeImpl`.

### 3c. Existing `NativeContext` compatibility shim

For the migration period a `LegacyNativeContext` adapter wraps the old
`NativeContext` and exposes the new capability traits.  Existing plugins
continue to compile unmodified; new or ported intrinsics use
`PluginNative.eval` and capability-specific context types.

### 3d. Test gating cleanup

The filename-based `isStdPluginInterpreterTest` filter (`build.sbt:43-54`)
exists only because some interpreter tests depend on plugin types.  Once
plugins no longer import `scalascript.interpreter.*`, the filter is redundant
and can be removed.

## 4. Migration

| Phase | Who does work | Change |
|-------|---------------|--------|
| A | Framework team | Publish `scalascript-plugin-api`; introduce `PluginContext`; add `LegacyNativeContext` shim |
| B | Per-plugin | Replace `NativeContext` with specific capability intersection in each `*Intrinsics.scala`; remove `scalascript.interpreter.*` imports |
| C | Cleanup | Remove `LegacyNativeContext` shim when all in-tree plugins migrated |

Callers of existing plugin APIs are not affected (API is additive).

## 5. Phases

### Phase 1 — Plugin API module (independent deliverable)

- New `runtime/scalascript-plugin-api/` sbt subproject.
- `PluginValue`, `PluginError`, `PluginComputation` opaque aliases.
- `JsonCodec` stable surface (wraps `scalascript.ir.Json` not `JsonParser`).
- `PluginContext` = full `NativeContext` re-exported as capability trait.
- All existing plugins add `scalascript-plugin-api` as a dependency (no code changes yet).
- Tests: compile `json-plugin` against `plugin-api` only (no `interpreter` on classpath).

### Phase 2 — Capability decomposition

- ✓ Landed 2026-05-29: split `PluginContext` into `HttpCap`, `WsCap`,
  `DbCap`, `StorageCap`, `ValidateCap`, and `MountCap`.
- ✓ Landed 2026-05-29: added `LegacyNativeContext` and `PluginContext.fromNative`
  as the compatibility shim over existing `NativeContext`.
- ✓ Landed 2026-05-29: added `PluginNative.eval`, the typed bridge for
  capability-specific native intrinsics without changing the legacy
  `NativeImpl` constructor.
- ✓ Landed 2026-05-29: migrated representative intrinsics in `json-plugin`,
  `http-plugin`, and `auth-plugin` to the typed bridge; full removal of direct
  `scalascript.interpreter.*` imports remains Phase 3.
- ✓ Landed 2026-05-29: fixed `auth-plugin` interpreter `verifyPassword` to call
  `scalascript.server.Password.verify` instead of returning false.

### Phase 3 — Full migration

- Migrate all 16 `runtime/std/*/…/*Intrinsics.scala` files.
- Remove `LegacyNativeContext`.
- Delete `isStdPluginInterpreterTest` filter from `build.sbt`.
- CI: add a compile-classpath check that rejects any `*.jar` containing
  `scalascript/interpreter/` in plugin subprojects.

## 6. Testing strategy

- Phase 1: add `scalascript-plugin-api` compilation test in `runtime/backend/spi/src/test`.
- Phase 2: `PluginApiTest` covers the capability adapter and typed bridge;
  `JsonPluginInterpreterTest`, `HttpPluginInterpreterTest`, and
  `AuthPluginInterpreterTest` cover migrated showcase intrinsics.
- Phase 3: add a dedicated `pluginBoundaryTest` sbt configuration that enforces
  "compile with only `plugin-api` and backend SPI on classpath" after direct
  interpreter imports are removed.
- Phase 3: regression — all existing plugin tests must stay green.

## 7. Open questions

1. Should `PluginComputation` be a true monad or stay `Future[Any]`-backed?
   The opaque alias hides this, but it matters for plugin authors.
2. Should capability traits be Scala 3 `trait`s or Tagless Final `F[_]` algebras?
   Recommendation: plain traits for Phase 1-2; TF can be added later without
   breaking the opaque surface.
3. Do we need a `PluginContext` factory for tests, or can tests keep using
   `NativeContext.stub` from `test-utils`?
