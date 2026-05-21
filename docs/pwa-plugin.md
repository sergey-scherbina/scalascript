# `std/pwa-plugin` — Progressive Web App support

## Goals

Let any `.ssc` web app declare itself as a Progressive Web App in two
lines.  The plugin registers:

- `GET /manifest.json` — W3C Web App Manifest
- `GET /sw.js` — a precaching service worker (install → cache, fetch → cache-first)

and documents how to wire the manifest link and SW registration into the
frontend HTML.

Works identically in:
- `ssc run` (interpreter)
- `ssc run-jvm` (JVM codegen)
- static export (`ssc emit-spa`) via route presence

## Non-goals

- Custom SW strategies beyond cache-first (network-first, stale-while-revalidate) — Phase 2
- Push notifications / Background Sync — Phase 2
- Automatic injection of `<link rel="manifest">` into HTML responses — Phase 2
- iOS splash screens / maskable icons — Phase 2

## Architecture

```
std/
  pwa.ssc                      extern def declarations (std.pwa package)
  pwa-plugin/
    src/main/scala/.../pwa/
      PwaInterpreterPlugin.scala   Backend impl — intrinsic-provider only
      PwaIntrinsics.scala          NativeImpl table for interpreter backend
    src/main/resources/META-INF/services/
      scalascript.backend.spi.Backend

runtime-server/jvm/src/.../jvm/
  RestRuntime.scala            + def pwa(...) helper (JvmGen / run-jvm)

examples/pwa/
  pwa-demo.ssc                 runnable golden-path example
```

### API surface

```scalascript
pwa(
  name            = "My App",
  shortName       = "App",            // optional, defaults to name
  description     = "",               // optional
  themeColor      = "#4285F4",        // optional, default #ffffff
  backgroundColor = "#ffffff",        // optional
  display         = "standalone",     // standalone | fullscreen | minimal-ui | browser
  startUrl        = "/",              // optional
  icons           = ["/icon-192.png", "/icon-512.png"],  // optional
  precache        = ["/", "/app.js", "/styles.css"]      // URLs to cache on SW install
)
```

### How it works

`pwa(...)` is called **before** `serve(port)`.  It registers two routes:

| Route | Content-Type | Description |
|---|---|---|
| `GET /manifest.json` | `application/manifest+json` | Web App Manifest built from the config |
| `GET /sw.js` | `application/javascript` | Precaching service worker |

The SW caches `precache` URLs on `install`, serves them cache-first on `fetch`,
and falls back to network for uncached URLs.

### Frontend wiring (user responsibility)

Add to your HTML `<head>`:
```html
<link rel="manifest" href="/manifest.json">
<meta name="theme-color" content="#4285F4">
```

Add before `</body>`:
```html
<script>
  if ('serviceWorker' in navigator)
    navigator.serviceWorker.register('/sw.js');
</script>
```

### Backend dispatch

| Backend | Mechanism |
|---|---|
| Interpreter (`ssc run`) | `PwaIntrinsics.table` via `NativeContext.registerRoute` |
| JvmGen (`ssc run-jvm`) | `def pwa(...)` in inlined `RestRuntime.scala` |
| JsGen / browser | routes registered in JS preamble (same `route()` mechanism) |

## Migration

No existing code changes.  `pwa(...)` is additive — existing apps that
don't call it behave identically.

## Phases

### Phase 1 — Core plugin (this PR)

- `std/pwa.ssc` extern defs
- `PwaInterpreterPlugin` + `PwaIntrinsics` (interpreter backend)
- `def pwa(...)` in `RestRuntime.scala` (JvmGen / run-jvm)
- `build.sbt` wiring + `cli/stage` packaging
- `examples/pwa/pwa-demo.ssc` golden-path example
- Docs: `README.md`, `docs/user-guide.md`

### Phase 2 — Advanced SW + auto-inject (future)

- Network-first / stale-while-revalidate strategies
- Automatic `<link rel="manifest">` injection into HTML responses
- Push notification setup helper
- `emit-spa` static manifest + SW baking

## Testing strategy

Phase 1: compile + unit tests verifying manifest JSON structure and SW
JS shape.  E2E via `ssc run examples/pwa/pwa-demo.ssc` + manual browser
"Install App" check.

## Open questions

None before Phase 1.
