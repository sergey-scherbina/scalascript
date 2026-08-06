# Front-matter `routes:` on the native lane

**Entry:** `v2/BUGS.md` `native-lane-ignores-declarative-route-registration` (the second half; the
built-in `/_health` and `/_ready` half landed 2026-08-06).
**Gate:** `tests/e2e/fm-routes-smoke.sh` — NATIVE declared a gap today, five rows red, the other
three lanes green.

## The defect

A module declares its routes in front matter instead of calling `route(...)` in its body:

```yaml
---
routes:
  - method: GET
    path: /api/todos
    handler: listTodos
---
```

```
int · jvm · js   ->  200  ["Buy milk", …]
native           ->  404  Not Found   text/plain
```

Reproduced 2026-08-06 with the entry's own gate: five NATIVE rows red
(`GET/POST /api/todos`, `GET after POST`, `DELETE`, and the page title), `[PASS] INT`, `[PASS] JVM`,
`[PASS] JS`.

## Why this sat open, and what actually closed it

The entry sized it as a design question:

> closing this needs front-matter data to cross into a plugin on the run path, which is a design
> choice about where that boundary sits

**That crossing already exists, and `databases:` already uses it.** Measured, not reasoned:

| step | file | what it does today |
|---|---|---|
| read | `NativeV2Structural.scala:199-230` `runtimeConfig` | walks `structural.manifests`, takes the `databases:` field out of the front-matter tree, builds `NativeRuntimeConfig` |
| carry | `RunNativeV2.scala:52` | `NativePluginHost.loadAll(compilation.config)` |
| expose | `NativePluginHost.scala:37-38` | `def databases = config.databases`, `def contentModules = config.contentModules` |

So there is no boundary to design for the DECLARATION. `routes:` is the same shape as `databases:`
— a front-matter key that a plugin consumes — and the work is to add it beside, not to invent a
channel.

### The half this spec got wrong, twice, and what it cost

The first draft said the piece a *lazy* handler needs was on the SPI already —
`NativePluginContext.resolveGlobal(name)`. **It was not, and the correction took two measurements:**

1. `resolveGlobal` reads `V2PluginRegistry.lookupGlobal`, the PLUGIN registry's own map. Its own doc
   comment says so ("another plugin's native, for cross-plugin construction"); the draft cited that
   line and still assumed it covered program globals. Symptom: `404` became
   `500 route handler 'listTodos' is not defined` — the channel worked, only the resolution failed.
2. Reaching the program's globals is not one map either. `Runtime.compileWithGlobals` hands its map
   to `Jit.onProgram`, so that looked like the hook — but **`bin/ssc` runs the BYTECODE tier by
   default**, which owns `Emit.globalsRef` and fills it from the generated `install()`. Instrumented
   rather than read: the map live at request time held 449 prelude names and none of the user's, and
   collecting every VM map gave eight of them and still no `listTodos`.

`Jit.scala` says the same thing about the same pair — *"so both tiers share one namespace"*. The
lookup therefore consults, in order: plugin registrations, `Emit.globalsRef`, then the VM maps.

**Where it lives is decided by the module graph, not by taste.** Putting the two-tier lookup in
`v2/src/Runtime.scala` does not compile — the kernel may not depend on `v2/jvm-runtime` — and that
refusal is the boundary working. `v2NativePluginSpi` depends on `v2JvmRuntime`, so
`NativePluginHost` is where it belongs.

## Design

Four edits, each with a precedent named above.

1. **SPI** (`v2/plugin-spi/.../NativePlugin.scala`)

   ```scala
   final case class NativeRouteDecl(method: String, path: String, handler: String)

   final case class NativeRuntimeConfig(
       databases: Map[String, NativeDatabaseConfig] = Map.empty,
       contentModules: List[NativeContentModule] = Nil,
       routes: List[NativeRouteDecl] = Nil)          // NEW, defaulted
   ```

   plus `def declaredRoutes: List[NativeRouteDecl] = Nil` on `NativePluginContext` — defaulted for
   the same reason `resolveGlobal` is.

2. **Host** (`NativePluginHost.scala`): `def declaredRoutes = config.routes`, one line beside the
   two that are there.

3. **CLI** (`NativeV2Structural.scala`): in `runtimeConfig`, read `routes:` beside `databases:`. It
   is a SEQUENCE of mappings rather than a mapping of mappings, so it needs `sequence`/`mapping`
   rather than `mapping` twice. `method`/`path`/`handler` are all required; a missing one is an
   `IllegalArgumentException` naming the file, exactly as a database without a `url` is.

4. **Plugin** (`HttpFastNativePlugin.scala`): beside `registerHealthDefaults()`, which already runs
   before `serverHost.serve` and only registers what is absent:

   ```scala
   def registerDeclaredRoutes(): Unit =
     context.declaredRoutes.foreach { r =>
       if !serverHost.hasRoute(r.method, r.path) then
         serverHost.register(r.method, r.path, lazyHandler(r.handler))
     }
   ```

   where `lazyHandler` resolves `r.handler` through `context.resolveGlobal` **at request time**, not
   at registration time. That is the interpreter's model (`registerFrontmatterRoutes` binds a lazy
   handler resolving the name from `globals`), and it is what makes handler ORDER IN THE FILE
   irrelevant — a handler defined after the `serve(...)` call still resolves.

## Precedence, stated because three lanes already agreed on it

- A program's own `route(...)` for the same method+path **wins** over a declared one — same
  `hasRoute` guard the built-ins use.
- The built-in `/_health` / `/_ready` stay lowest priority: a declared route for `/_health` wins,
  because declared routes register first.

## Limits

- **A handler that does not resolve is a 500 at request time, not a startup failure.** Registering
  eagerly would turn a typo in `handler:` into "the server does not start", which is worse and is
  not what the other three lanes do.
- **Only the entry root's front matter is read**, like `databases:` — `runtimeConfig` walks every
  manifest, so an imported module declaring `routes:` also contributes. That is the existing
  behaviour of this function and is not changed here; if it turns out to be wrong for routes it is
  wrong for databases too and belongs in one entry, not two.

## Acceptance

- `tests/e2e/fm-routes-smoke.sh`: NATIVE goes from five red rows to green, and its declaration is
  DELETED — the gate fails a declared row that starts passing, so this is forced rather than
  optional.
- The other three lanes unchanged in the same run.
- `/_health` still 200 on native (the half that already landed must not regress).
- A program whose handler is defined AFTER its `serve(...)` call still answers — the property the
  lazy resolution exists for, and the one an eager implementation would pass without.
