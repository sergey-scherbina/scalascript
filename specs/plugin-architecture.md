# Pluggable Intrinsics — Architecture

This document specifies how new intrinsic families are added to
ScalaScript **without recompiling the interpreter**, how they are
discovered at runtime, and how the user-facing surface can be
expressed as ordinary `.ssc` library files.

## 1. The problem

Today every new intrinsic family (HTTP, WebSocket, Auth, JDBC, MCP,
OAuth, Frontend, Toolkit, …) follows the same three-step loop:

1. Write a Scala file under
   `backend-interpreter/src/…/interpreter/intrinsics/`.
2. Add `++ FamilyIntrinsics` to the map in
   `InterpreterCapabilities.scala:68-79`.
3. Recompile the interpreter.

After twelve families the map already has 82 entries and grows with
every release.  The problem is not just size; it is the coupling:
changing the toolkit means a recompile and redeploy of the whole
interpreter binary.

The goal is to eliminate steps 2–3 entirely:

- **new domain** → ship a `.sscpkg` archive, drop it on the
  classpath or reference it with a `pkg:` import;
- **interpreter learns nothing new** — it only needs to be told "here
  are intrinsics from a plugin, install them."

## 2. What already works (infrastructure inventory)

The plumbing exists.  It is used by every backend except the
interpreter.

| Component | Path | Status |
|---|---|---|
| `.sscpkg` archive format | `core/src/…/plugin/SscpkgManifest.scala` | ✅ |
| Plugin loader (unzip + JAR register) | `core/src/…/plugin/SscpkgLoader.scala` | ✅ |
| Unified runtime registry facade | `backend-spi/src/…/PluginRegistry.scala` | ✅ |
| Remote/path installer | `core/src/…/plugin/RemotePluginInstaller.scala` | ✅ |
| Local registry compatibility wrapper | `core/src/…/plugin/LocalRegistry.scala` | ✅ |
| `Backend` SPI exposes `intrinsics: Map[QN, IntrinsicImpl]` | `backend-spi/src/…/Backend.scala` | ✅ |
| `BackendRegistry.loadSscpkg` — adds JAR to URLClassLoader, invalidates ServiceLoader cache | `core/src/…/plugin/BackendRegistry.scala:67` | ✅ |
| `ServiceLoader[Backend]` discovery | `BackendRegistry.scala:134` | ✅ (used by React/Vue/Solid/Custom, Netty/Jetty, crypto, blockchain) |
| End-to-end reference plugin | `examples/plugins/crypto-plugin/` | ✅ for JVM/JS targets |
| `dep:` URI for library imports | `core/src/…/imports/ImportResolver.scala:39` | ✅ |
| **Missing**: interpreter merges plugin intrinsics | `BuiltinsRuntime.scala:17` (only installs `InterpreterIntrinsics`) | ❌ |
| **Missing**: `pkg:` URI (plugin imports with version pinning) | `ImportResolver.scala:56` (no `pkg:` branch) | ❌ |
| `NativeContext` generic state bag | `backend-spi/src/…/IntrinsicImpl.scala:50` | ✅ |
| Stable plugin capability bridge | `scalascript-plugin-api/src/…/PluginApi.scala` | ✅ |

**Bottom line**: adding a single line to `BuiltinsRuntime.initBuiltins`
lights up the full `.sscpkg` plugin path for the interpreter.

## 3. The three-tier model

Every "intrinsic family" falls into one of three tiers.  The right
tier is determined by whether the code crosses the JVM/native
boundary.

### Tier 1 — Pure ScalaScript library

Composition over existing primitives.  Distributed as plain `.ssc`
files.  No new interpreter mechanism required; the standard import
system already handles this.

```scalascript
// std/ui/layout.ssc — builds on the 9 element/signal/… extern prims
[element, textNode](pkg:scalascript/ui-prim:1.0)

def vstack(gap: Int = 0)(children: View*): View =
  element("div",
    Map("style" -> s"display:flex; flex-direction:column; gap:${gap}px"),
    Map.empty,
    children.toList)
```

The entire frontend toolkit (25 widgets) sits in this tier: only the
9 primitives that genuinely cross the JVM↔JS boundary need native
support; the rest is pure `.ssc`.

**Limit**: cannot introduce new JVM/native I/O.  If you need to open
a socket, Tier 2 is required.

### Tier 2 — `.sscpkg` plugin (native bridge)

For genuine JVM↔JS boundary crossings: network I/O, filesystem, FFI,
cryptography, database drivers, etc.  A `.sscpkg` archive ships:

| File | Purpose |
|---|---|
| `manifest.yaml` | Identity, version, target list, declared `extern def` exports |
| `sources/*.ssc` | User-facing API — `extern def` declarations |
| `intrinsics/*.jar` | `Backend` SPI impl + `NativeImpl` table |
| `runtime/jvm.scala` | JVM-side helpers injected into compiled output |
| `runtime/js.js` | JS-side helpers injected into compiled output |
| `META-INF/services/scalascript.backend.spi.Backend` | ServiceLoader registration line |

The crypto plugin (`examples/plugins/crypto-plugin/`) is a complete
reference.  `CryptoInterpreterPlugin` provides the `NativeImpl` table
for the interpreter; `CryptoBackendPlugin` provides `RuntimeCall` entries
for code-generating backends.  Both are discovered via `ServiceLoader`.

**Limit**: intrinsics that need interpreter-specific runtime state
(connection pools, WebSocket handles, per-request contexts) currently
need `NativeContext` methods — which means amending the SPI.  The
`NativeContext` state bag (§5.2) removes this limit.

### Tier 3 — Effect-handler plugin

For cross-cutting concerns (logging, retries, tracing, auth,
transactional boundaries) and for DSLs whose semantics depend on
which handler is in scope.

A Tier-3 plugin is pure `.ssc`: it declares an `effect` ADT and
ships a default handler.  The effect is the public interface; the
handler is the implementation:

```scalascript
// std/email.ssc (Tier-3 plugin — no new extern def needed)
[sendSmtp](pkg:scalascript/smtp:1.0)  // Tier-2 primitive for the actual socket

effect Email:
  def send(to: String, subject: String, body: String): Unit

def withSmtp(host: String, port: Int)(body: => A): A =
  handle(body) {
    case Email.send(to, subj, b, resume) =>
      sendSmtp(host, port, to, subj, b)   // Tier-2 call
      resume(())
  }
```

User code calls `Email.send(...)` without knowing whether it lands on
SMTP, a test stub, or a queue.  The handler is swapped at the call
site — no interpreter changes, no recompile.  Handlers compose:

```scalascript
val result = withSmtp("mail.example.com", 587) {
  withRateLimiter(10) {
    withRetry(maxAttempts = 3) {
      Email.send("user@example.com", "Hello", "World")
    }
  }
}
```

The effect machinery already runs in the interpreter (Computation
Free monad, `Perform`/`Resume`).  See `examples/effects.ssc`,
`std/free.ssc`, `examples/mapreduce/handlers.ssc` for live examples.

**Limit**: the bottom of the stack still hits a Tier-2 native for any
real I/O.  Tier 3 is not a substitute for Tier 2; it is a protocol
layered on top of it.

## 4. Discovery story

Two complementary mechanisms, not alternatives.  Both should work.

### 4.1 ServiceLoader (zero-syntax, classpath-driven)

Drop a `.sscpkg` (or its JAR) on the classpath; the interpreter
discovers and installs its intrinsics automatically at startup:

```
ssc --plugin ./org.example.crypto-1.0.0.sscpkg run script.ssc
# or install permanently:
ssc plugin install ./org.example.crypto-1.0.0.sscpkg
```

Good for dev shells, REPLs, and scripts that don't want to name their
dependencies.  Mirrors exactly how all other SPIs work today.

### 4.2 `pkg:` URI (explicit, versioned, reproducible)

A `.ssc` script declares its plugin deps inline using a `pkg:` import:

```scalascript
[sha256, hmacSha256](pkg:scalascript/crypto:2.1)
[signal, element](pkg:scalascript/ui-prim:1.0)
```

The import resolver:

1. Looks the `<org>/<name>:<version>` triple up in the local registry
   (`~/.scalascript/compiler/plugins/`) and the dep-cache
   (`~/.cache/scalascript/deps/`).
2. If absent, downloads from configured endpoints (same
   `dep-sources` chain already used by `dep:` imports,
   `core/src/…/imports/ImportResolver.scala:80`).
3. Calls `BackendRegistry.loadSscpkg(path)` to register the JAR.
4. Binds the named exports from the package's `sources/*.ssc` into
   the current scope — same mechanics as a file import.

For reproducibility, `ssc.lock` pins the SHA-256 of each resolved
`.sscpkg` (extending the existing lock semantics in
`core/src/…/imports/LockFile.scala`).

**URI grammar** (informally):

```
pkg: <org> "/" <name> ":" <version>
```

Analogous to the existing `dep:` scheme but resolves to a `.sscpkg`
rather than a plain `.ssc` file.

### 4.3 Interaction with existing import forms

All three forms resolve through `ImportResolver.resolve`:

| Form | Example | Resolves to |
|---|---|---|
| Relative path | `[f](std/ui/layout.ssc)` | local `.ssc` file |
| `dep:` URI | `[f](dep:org.foo/lib:1.0)` | remote `.ssc` file (library only) |
| **`pkg:` URI** (new) | `[f](pkg:scalascript/crypto:1.0)` | `.sscpkg` (plugin + sources) |
| URL | `[f](https://…/lib.ssc)` | remote `.ssc` file |

`pkg:` is the only form that triggers `loadSscpkg` + intrinsic
installation.  `dep:` stays for plain `.ssc` library downloads.

## 5. Minimum wiring work

Three small changes unlock the full picture.

### 5.1 Wire plugin intrinsics into the interpreter (one line)

`BuiltinsRuntime.initBuiltins` (`BuiltinsRuntime.scala:17`) currently
installs only `InterpreterIntrinsics`:

```scala
interp.installNativeIntrinsics(InterpreterIntrinsics)
```

Change to:

```scala
val pluginIntrinsics: Map[QualifiedName, IntrinsicImpl] =
  BackendRegistry.inProcess
    .flatMap(_.intrinsics)
    .toMap
interp.installNativeIntrinsics(InterpreterIntrinsics ++ pluginIntrinsics)
```

(`BackendRegistry.inProcess` returns all ServiceLoader-discovered
`Backend`s; each exposes `intrinsics: Map[QualifiedName, IntrinsicImpl]`
— the existing `Backend` SPI field, `Backend.scala`.)

Once a `.sscpkg` is loaded via `BackendRegistry.loadSscpkg` before
the interpreter starts, its `NativeImpl`s merge in automatically.

### 5.2 `NativeContext` generic state bag

Any plugin that needs per-session mutable state (connection pools,
WebSocket handles, etc.) today has to add a method to `NativeContext`
— a SPI break that forces a recompile.

Add a key-value bag:

```scala
// IntrinsicImpl.scala — NativeContext additions
def featureGet[T](key: String): Option[T]      = None
def featureSet[T](key: String, value: T): Unit = ()
```

A plugin's `NativeImpl` calls `ctx.featureSet("smtp.client", client)`
on first use and `ctx.featureGet[SmtpClient]("smtp.client")` on
subsequent calls.  The interpreter's `NativeContext` implementation
backs the bag with a `ConcurrentHashMap[String, Any]`.

No further SPI changes are needed for new families.

### 5.3 `pkg:` branch in `ImportResolver`

`ImportResolver.resolve` (`ImportResolver.scala:38`) currently handles
`dep:`, absolute URLs, and relative paths.  Add:

```scala
if rawPath.startsWith("pkg:") then
  return resolvePkg(rawPath, lockPath)
```

`resolvePkg` mirrors `resolveDep` (line 83) but:
- resolves to a `.sscpkg` file (not a `.ssc`)
- calls `RemotePluginInstaller.install(...)`, then
  `BackendRegistry.loadSscpkg(path)` after download
- returns the path to the package's `sources/` index (the `.ssc` file
  the import merger uses to bind the named exports)

Also extend `SectionRuntime.runImport` to detect a `.sscpkg`
source path and handle the name-binding step correctly (the package
exports come from the `.ssc` surface file inside `sources/`, same as
any other file import).

## 6. Anatomy of a `.sscpkg` (walkthrough)

The `examples/plugins/crypto-plugin/` package is the reference:

```
manifest.yaml                 ← package identity + extern def exports
sources/
  crypto.ssc                  ← extern def sha256(...) etc. (Tier-1 surface)
intrinsics/
  crypto-plugin-1.0.0.jar     ← CryptoInterpreterPlugin + CryptoIntrinsics
runtime/
  jvm.scala                   ← _cryptoSha256() helper for JVM target
  js.js                       ← _cryptoSha256() helper for JS target
src/…/
  CryptoBackendPlugin.scala   ← Backend impl (id "crypto-intrinsics-jvm")
  CryptoInterpreterPlugin.scala ← Backend impl (id "crypto-intrinsics-interpreter")
  CryptoIntrinsics.scala      ← IntrinsicImpl table (RuntimeCall + NativeImpl)
META-INF/services/
  scalascript.backend.spi.Backend   ← "scalascript.compiler.plugin.crypto.CryptoBackendPlugin\nscalascript.compiler.plugin.crypto.CryptoInterpreterPlugin"
```

`manifest.yaml` declares:

```yaml
id:         org.example.crypto
version:    1.0.0
spiVersion: "0.1.0"
kind:       [library, plugin]
targets:    [jvm, interpreter]
exports:
  externDefs:
    - std.crypto.sha256
    - std.crypto.base64Encode
    - std.crypto.base64Decode
    - std.crypto.hmacSha256
```

The `CryptoInterpreterPlugin.intrinsics` returns
`CryptoIntrinsics.interpreterTable` — a `Map[QualifiedName, NativeImpl]`
that the interpreter installs under names like `"std.crypto.sha256"`.

A `.ssc` script then writes:

```scalascript
[sha256](pkg:scalascript/crypto:2.1)
val digest = sha256("hello")
```

The `extern def sha256(input: String): String` in `sources/crypto.ssc`
provides the `.ssc`-side type signature; the installed `NativeImpl`
provides the runtime implementation.

## 7. Anatomy of a Tier-3 effect-handler plugin

A Tier-3 plugin is a pure `.ssc` file plus (optionally) a Tier-2
package for the native bottom:

```scalascript
// std/email.ssc
[sendSmtp](pkg:scalascript/smtp-prim:1.0)   // Tier-2 socket primitive

// Public interface — the "plugin contract"
effect Email:
  def send(to: String, subject: String, body: String): Unit

// Default production handler (in .ssc, not a JAR)
def withSmtp(host: String)(body: => A): A =
  handle(body) {
    case Email.send(to, subj, b, resume) =>
      sendSmtp(host, to, subj, b)
      resume(())
  }

// Test double — pure, no network
def withMockEmail(captured: List[(String, String)] = Nil)(body: => A):
    (A, List[(String, String)]) =
  var log = captured
  val result = handle(body) {
    case Email.send(to, subj, _, resume) =>
      log = log :+ (to, subj)
      resume(())
  }
  (result, log)
```

User code imports from `std/email.ssc` and calls `Email.send(...)`.
The handler is supplied at the outermost call site.  Production code
uses `withSmtp`; tests use `withMockEmail`.  No interpreter change,
no recompile, no plugin registry entry for the effect itself.

## 8. Migration: existing in-tree intrinsic families

Once §5 is wired, families can migrate incrementally.  The table
below classifies each current family:

| Family | Path | Recommendation |
|---|---|---|
| `nowMillis`, `Console.println/print` | `InterpreterCapabilities.scala:55-67` | **Keep in-tree** — interpreter core, tiny, stable |
| `CoreIntrinsics` (assert, typeof, …) | `intrinsics/Core.scala` | **Keep in-tree** — language primitives |
| `HttpIntrinsics` | `intrinsics/Http.scala` | Move to `std-packages/http.sscpkg` |
| `WsIntrinsics` | `intrinsics/Ws.scala` | Move to `std-packages/websocket.sscpkg` |
| `AuthIntrinsics` | `intrinsics/Auth.scala` | Move to `std-packages/auth.sscpkg` |
| `JsonIntrinsics` | `intrinsics/Json.scala` | Move to `std-packages/json.sscpkg` |
| `RequestIntrinsics` | `intrinsics/Request.scala` | Part of `http.sscpkg` |
| `McpIntrinsics` | `intrinsics/Mcp.scala` | Move to `std-packages/mcp.sscpkg` |
| `OAuthIntrinsics`, `OAuthClientIntrinsics` | `intrinsics/OAuth*.scala` | Move to `auth.sscpkg` |
| `JdbcIntrinsics` | `intrinsics/Jdbc.scala` | Move to `std-packages/jdbc.sscpkg` |
| `FrontendIntrinsics` | `intrinsics/Frontend.scala` | Move to `std-packages/frontend.sscpkg` |
| `ToolkitDslIntrinsics` | `intrinsics/ToolkitDsl.scala` | **Delete** (replaced by `std-packages/ui-prim.sscpkg` + pure `.ssc` Tier-1 toolkit) |

Migration is **not an immediate commitment** — the in-tree families
keep working exactly as before until a migration PR moves them.  The
only prerequisite is §5.1; the rest is incremental.

## 9. Phase plan

1. **Spec** (this document) — committed to `docs/`, reviewed.
2. **`pkg:` import syntax** — extend `ImportResolver` + `SectionRuntime` (§5.3).
3. **BuiltinsRuntime wire** — merge plugin intrinsics into the interpreter (§5.1).  One line + a test.
4. **NativeContext state bag** — add `featureGet`/`featureSet` to avoid future SPI breaks (§5.2).
5. **First `.sscpkg` — UI primitives** — ship the 9 `extern def`s from the frontend toolkit spec (`specs/frontend-toolkit-spec.md §Phase 7a`) as `std-packages/ui-prim.sscpkg`.  Resumes the toolkit refactor.
6. **Migrate remaining in-tree families** — incremental, one family per PR, starting with Http and Json (highest user demand for standalone use).

## 10. Open questions (deferred)

1. **Plugin sandboxing / capability model** — should a plugin be
   restricted to specific `NativeContext` capabilities?  E.g., a
   "pure math" plugin should not be able to call `registerRoute`.
   Deferred to a future spec pass.

2. **Remote registry** — v1 is local-only (local registry + `dep-sources`
   endpoints).  A central `registry.scalascript.io` is deferred; the
   `pkg:` URI grammar is designed to slot in once one exists.

3. **Conflict resolution** — if two loaded `.sscpkg`s both declare
   `std.crypto.sha256`, the last one wins (map merge order).  A
   smarter conflict policy (error, version-compare, explicit override)
   is deferred.

4. **Backward compatibility** — `InterpreterIntrinsics` stays in the
   binary for the foreseeable future.  Removing a family requires
   confirming no `.ssc` scripts call its `extern def`s without a
   `pkg:` import.  The existing `CapabilityCheck` stage already
   enforces extern-def coverage; removing an in-tree family without
   shipping a `.sscpkg` replacement would surface there.

5. **Tier-3 distribution** — currently a Tier-3 plugin is just a
   `.ssc` file (no packaging, no version manifest).  Should
   `SscpkgManifest` gain a `library`-only mode that ships `.ssc` alone
   with a version number?  The manifest `kind: [library]` shape
   (`SscpkgManifest.scala:25`) is already there; wiring it into the
   version-pinning flow is future work.
