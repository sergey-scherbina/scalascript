package scalascript.server

/** JVM-specific server runtime — the half of the HTTP/WS stack that
 *  was historically expressed as a `"""|..."""` triple-quoted string
 *  inside `JvmGen.serveRuntime`.  Files in this package are real
 *  Scala (type-checked at our build time, IDE-aware, refactor-safe,
 *  unit-testable) AND are copied into a classpath resource bundle so
 *  the JVM codegen backend can inline their source text into the
 *  generated scala-cli scripts — exactly the same pattern
 *  `runtime-server-common` already uses.
 *
 *  The package declaration is stripped when the loader inlines the
 *  file; the types end up at top level in the generated script
 *  (alongside the `runtime-server-common` types).  So adding files
 *  here is equivalent to adding sections to `serveRuntime` — except
 *  written in real Scala.
 *
 *  Phases:
 *    - 3a — module setup (this file)
 *    - 3b — REST routing + serve(port)
 *    - 3c — WebSocket support
 *    - 3d — Proxy + TLS
 *    - 3e — outbound HTTP/WS clients
 *    - 3f — JvmGen.serveRuntime collapses to a small emitter
 *
 *  See `specs/runtime-server-strategic-plan.md` Option A. */
package object jvm
