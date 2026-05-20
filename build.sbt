ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "io.scalascript"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Scala.js cross-compile plumbing (docs/wallet-spi-scalajs.md).
// `crossProject(JVMPlatform, JSPlatform).in(file("..."))` lays out
// `shared/` + `jvm/` + `js/`; `.jvm` / `.js` give us the per-platform
// sbt sub-projects.  Each cross-compiled SPI below keeps a JVM-only
// `xxx` alias (= `xxxCross.jvm`) so the rest of the build — which
// remains JVM-only for now — continues to `.dependsOn(walletSpi)` /
// `.dependsOn(cryptoSpi)` / `.dependsOn(blockchainSpi)` unchanged.
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

// Forked test JVMs default to ~512 KB stack which trips
// `mutual-TCO` / `stack-safe bind chains` / Async tests under
// parallel suite execution.  4 MB held the flake down most of the
// time; 8 MB is what finally stopped it once the WS suites started
// spawning per-connection virtual threads (each parked VT briefly
// consumes carrier stack).  Cost is negligible.
ThisBuild / Test / javaOptions += "-Xss8m"
ThisBuild / Test / fork         := true
// Export sub-module classes as JARs so cli/stage sees actual JAR files
// (not class directories) when collecting the classpath for lib/jars/.
ThisBuild / exportJars          := true

// Production code is held to fatal-warnings; test code stays warning-tolerant
// because scalatest macros, mocks, and intentional unused vals are common
// patterns that aren't worth silencing one-by-one.
val sharedScalacOptions       = Seq("-Wunused:all", "-deprecation", "-feature")
val sharedScalacOptionsStrict = sharedScalacOptions :+ "-Werror"
val scalatestTest       = "org.scalatest" %% "scalatest" % "3.2.18" % Test

// ---------------------------------------------------------------------------
// Backend SPI v0.1 — module layout (docs/backend-spi.md §4.1)
//
// Stage 1.2: sources moved out of compiler/ into the new modules.
// ---------------------------------------------------------------------------

lazy val ir = project
  .in(file("ir"))
  .settings(
    name := "scalascript-ir",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2"
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendSpi = project
  .in(file("backend/spi"))
  .dependsOn(ir)
  .settings(
    name := "scalascript-backend-spi",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val core = project
  .in(file("core"))
  .dependsOn(backendSpi)
  .settings(
    name := "scalascript-core",
    libraryDependencies ++= Seq(
      "org.yaml"       %  "snakeyaml"  % "2.6",
      "com.lihaoyi"    %% "os-lib"     % "0.11.4",
      "org.scalameta"  %% "scalameta"  % "4.17.0",
      "org.commonmark" %  "commonmark" % "0.28.0",
      scalatestTest
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v2.0 / Tier 2 of Scala ↔ ScalaScript interop — see docs/scala-interop.md.
//
// A small Scala 3 library that lets regular Scala projects consume
// `.ssc`-compiled JARs with natural-FQN imports (`import std.foo.add`)
// instead of the v2.0 mangling (`_ssc_runtime.std_foo_add`).
//
// Depends only on `ir` (for `ModuleInterface` round-trip) and `core`
// (for `ArtifactIO.readInterfaceFile`).  Deliberately NOT depending on
// any backend module — the interop library is meant to be classpath-
// friendly for downstream Scala consumers, who shouldn't pull in
// JvmGen / Interpreter / scalameta when all they need is the facade
// table + reflection helpers.
lazy val interop = project
  .in(file("interop"))
  .dependsOn(ir, core)
  .settings(
    name := "scalascript-interop",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.11.4",
      scalatestTest
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// Phase 1a of the runtime-consolidation refactor (see
// PLAN-runtime-consolidation.md): pure protocol primitives extracted out
// of backend-interpreter so they can later be reused by the JVM codegen
// runtime instead of being duplicated as a string template inside
// JvmGen.serveRuntime.  No interpreter coupling — all definitions are
// self-contained Scala classes / objects.
lazy val runtimeServerCommon = project
  .in(file("runtime-server/common"))
  .settings(
    name := "scalascript-runtime-server-common",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    // Phase 1b: copy our own .scala sources into the classpath under
    // `runtime-server-common-sources/scalascript/server/*.scala` so the JVM
    // codegen backend can read them at codegen time and inline them into
    // generated scala-cli scripts (replacing the duplicated copies that
    // previously lived inside JvmGen.serveRuntime as a string template).
    Compile / resourceGenerators += Def.task {
      val srcDir  = (Compile / scalaSource).value / "scalascript" / "server"
      val outBase = (Compile / resourceManaged).value / "runtime-server-common-sources" / "scalascript" / "server"
      IO.createDirectory(outBase)
      (srcDir ** "*.scala").get.map { f =>
        val target = outBase / f.getName
        IO.copyFile(f, target)
        target
      }
    }.taskValue
  )

// v1.17 — MCP own-implementation shared runtime.  Pure Scala 3, depends
// only on `upickle` / `ujson`.  Houses JsonRpc framing, the MCP protocol
// shapes, the transport-agnostic server dispatch loop, and the client
// pending-request map.  Phase 1 (interpreter stdio + spawn) and Phase 2
// (interpreter HTTP+SSE) consume it directly; the scalajs-spa Phase 3
// client lives in `backend-scalajs` as a JS preamble (uses Scala.js DOM
// APIs that can't cross-build with the JVM types here).
lazy val mcpCommon = project
  .in(file("mcp/common"))
  .settings(
    name := "scalascript-mcp-common",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.17.6 / Phase S1 (HTTP server SPI — docs/http-server-spi-plan.md).
// Trait definitions for pluggable HTTP/WS network-layer backends.
// Three impl modules downstream consume this:
//   - runtimeServerJvm                (default JDK impl)
//   - runtimeServerJvmJetty           (optional, Jetty 12)
//   - runtimeServerJvmNetty           (optional, Netty 4)
// Depends on runtimeServerCommon for the POJO HTTP model
// (Request / Response / StreamResponse) the traits reference.
lazy val runtimeServerSpi = project
  .in(file("runtime-server/spi"))
  .dependsOn(runtimeServerCommon)
  .settings(
    name := "scalascript-runtime-server-spi",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    // v1.17.6 / Phase S1c — copy SPI sources into the classpath under
    // `runtime-server-spi-sources/scalascript/server/spi/` so JvmGen can
    // inline them into generated scala-cli scripts.  Mirrors the
    // `runtimeServerCommon` / `runtimeServerJvm` resource-bundle pattern.
    // The inlined SPI traits let the codegen-emitted `serve(port, tls)`
    // route through `HttpServerBackends.current().start(...)` like the
    // interpreter does.
    Compile / resourceGenerators += Def.task {
      val srcDir  = (Compile / scalaSource).value / "scalascript" / "server" / "spi"
      val outBase = (Compile / resourceManaged).value / "runtime-server-spi-sources" / "scalascript" / "server" / "spi"
      IO.createDirectory(outBase)
      (srcDir ** "*.scala").get.map { f =>
        val target = outBase / f.getName
        IO.copyFile(f, target)
        target
      }
    }.taskValue
  )

// Phase 3 (Option A from docs/runtime-server-strategic-plan.md) —
// JVM-specific server runtime that used to live inside the
// `serveRuntime` triple-quoted string in JvmGen.scala.  Same
// resource-bundle pattern as `runtimeServerCommon`: real .scala
// sources here are copied into the classpath under
// `runtime-server-jvm-sources/...` so JvmGen.scala can read them
// at codegen time and inline them into generated scala-cli scripts.
//
// Depends on `runtimeServerCommon` so the JVM-specific code can
// import the POJO HTTP model + RequestBuilder / ResponseWriter /
// HttpDispatchLoop / WsFraming / WsFrameDispatch / … helpers that
// already live there.  Now also dependsOn(runtimeServerSpi) so the
// JdkServerBackend can implement HttpServerSpi.
lazy val runtimeServerJvm = project
  .in(file("runtime-server/jvm"))
  .dependsOn(runtimeServerCommon, runtimeServerSpi)
  .settings(
    name := "scalascript-runtime-server-jvm",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    Compile / resourceGenerators += Def.task {
      val srcDir  = (Compile / scalaSource).value / "scalascript" / "server" / "jvm"
      val outBase = (Compile / resourceManaged).value / "runtime-server-jvm-sources" / "scalascript" / "server" / "jvm"
      IO.createDirectory(outBase)
      (srcDir ** "*.scala").get.map { f =>
        val target = outBase / f.getName
        IO.copyFile(f, target)
        target
      }
    }.taskValue
  )

// v1.17.6 / Phase S2 (HTTP server SPI) — Jetty 12 backend.  Optional;
// pulls Jetty as a runtime dependency.  Enables HTTP/2 + mature WS +
// permessage-deflate.  Discovered via ServiceLoader[HttpServerSpi]
// when on the classpath.  Currently a stub — `start` throws
// NotImplementedError; module declaration + dep + ServiceLoader
// registration in place so the SPI is discoverable.  S2 fills in
// the actual Jetty integration.
lazy val runtimeServerJvmJetty = project
  .in(file("runtime-server/jvm-jetty"))
  .dependsOn(runtimeServerSpi, runtimeServerCommon)
  .settings(
    name := "scalascript-runtime-server-jvm-jetty",
    libraryDependencies ++= Seq(
      // Jetty 12 — modern API, HTTP/2 + WS + servlet-compat.
      "org.eclipse.jetty"           %  "jetty-server"             % "12.0.13",
      "org.eclipse.jetty.ee10"      %  "jetty-ee10-servlet"       % "12.0.13",
      "org.eclipse.jetty.websocket" %  "jetty-websocket-jetty-api" % "12.0.13",
      "org.eclipse.jetty.websocket" %  "jetty-websocket-jetty-server" % "12.0.13",
      scalatestTest
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.17.6 / Phase S3 (HTTP server SPI) — Netty 4 backend.  Optional;
// pulls Netty as a runtime dependency.  Highest throughput per core,
// HTTP/3 incubator, custom protocol support.  Discovered via
// ServiceLoader[HttpServerSpi] when on the classpath.  Currently a
// stub — module declaration + dep + ServiceLoader registration in
// place.  S3 fills in the actual Netty integration.
lazy val runtimeServerJvmNetty = project
  .in(file("runtime-server/jvm-netty"))
  .dependsOn(runtimeServerSpi, runtimeServerCommon)
  .settings(
    name := "scalascript-runtime-server-jvm-netty",
    libraryDependencies ++= Seq(
      "io.netty" %  "netty-codec-http"   % "4.1.118.Final",
      "io.netty" %  "netty-codec-http2"  % "4.1.118.Final",
      "io.netty" %  "netty-handler-ssl-ocsp" % "4.1.118.Final",
      scalatestTest
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.18 / Phase A1 (Frontend framework SPI — docs/frontend-framework-spi-plan.md
// + docs/frontend-abstract-model.md).  Framework-agnostic primitive
// trait definitions: Signal[T], Computed[T], Effect, View, Component[P],
// Capability enum, FrontendFrameworkSpi + FrontendFrameworks registry.
// Four impl modules downstream consume this (frontendCustom /
// frontendReact / frontendVue / frontendSolid).  Pure Scala 3, JVM-side
// — the codegen lowering pass reads the IR and the chosen backend's
// emit() produces framework-specific JS source.
lazy val frontendCore = project
  .in(file("frontend/core"))
  .settings(
    name := "scalascript-frontend-core",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.18 / Phase A2 stub — Custom backend (default, zero npm deps).
// Will interpret primitives via a tiny Scala-compiled-JS runtime
// (signals + Set-of-subscribers + direct DOM ops; ~3-5 KB bundle).
// Today: stub.
lazy val frontendCustom = project
  .in(file("frontend/custom"))
  .dependsOn(frontendCore)
  .settings(
    name := "scalascript-frontend-custom",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.18 / Phase B (Frontend toolkit — docs/frontend-toolkit-spec.md).
// High-level declarative widget library (Stack, Box, Text, Button,
// TextField, Card, Alert, ...) that lowers to View primitives.
// Backend-agnostic: same toolkit code emits to React / Vue / Solid /
// Custom via the existing FrontendFrameworkSpi.
lazy val frontendToolkit = project
  .in(file("frontend/toolkit"))
  .dependsOn(frontendCore)
  .settings(
    name := "scalascript-frontend-toolkit",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.18 / Phase A3 stub — React backend.  Signal lowers to useState,
// Component to function components, View to React.createElement.
// Today: stub.
lazy val frontendReact = project
  .in(file("frontend/react"))
  .dependsOn(frontendCore)
  .settings(
    name := "scalascript-frontend-react",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.18 / Phase A4 stub — Solid backend.  Signal lowers to
// createSignal; fine-grained subscriptions; component runs once.
// Today: stub.
lazy val frontendSolid = project
  .in(file("frontend/solid"))
  .dependsOn(frontendCore)
  .settings(
    name := "scalascript-frontend-solid",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.18 / Phase A5 stub — Vue backend.  Signal lowers to ref,
// setup-function components, render-function emission.
// Today: stub.
lazy val frontendVue = project
  .in(file("frontend/vue"))
  .dependsOn(frontendCore)
  .settings(
    name := "scalascript-frontend-vue",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.18 / Phase A8 — reference apps exercising the frontend SPI across
// all four backends.  Three canonical demos (counter, show-hide,
// todo-list) each rendered by Custom + React + Solid + Vue.  Ships
// both a runnable `main` that writes per-backend HTML + JS files to
// `target/frontend-examples/` and a small test suite that asserts the
// emitted JS shape per backend — usable as a smoke-test + a worked
// example of how to drive the SPI from Scala.
lazy val frontendExamples = project
  .in(file("frontend/examples"))
  .dependsOn(frontendCore, frontendCustom, frontendReact, frontendSolid, frontendVue)
  .settings(
    name := "scalascript-frontend-examples",
    publish / skip := true,
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendJvm = project
  .in(file("backend/jvm"))
  .dependsOn(backendSpi, core, runtimeServerCommon, runtimeServerSpi, runtimeServerJvm, backendSqlRuntime)
  .settings(
    name := "scalascript-backend-jvm",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendJs = project
  .in(file("backend/js"))
  .dependsOn(backendSpi, core, backendSqlRuntimeJs)
  .settings(
    name := "scalascript-backend-js",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.25 Phase 3b — Node.js target.  Reuses JsGen for the
// `scalascript` / `scala` body and concatenates `node.js` opaque-exec
// blocks verbatim as a glue prefix.  Depends on backendJs to share
// JsGen + JsCapabilities helpers and intrinsic tables.
lazy val backendNode = project
  .in(file("backend/node"))
  .dependsOn(backendSpi, core, backendJs)
  .settings(
    name := "scalascript-backend-node",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendScalajs = project
  .in(file("backend/scalajs"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-scalajs",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendWasm = project
  .in(file("backend/wasm"))
  // v1.27 Phase 5 — backendSqlRuntimeJs for ProviderId / SqlRuntimeJsEmit
  // shared with backend-js + backend-node.  sql blocks routed through the
  // JS shim that already accompanies the .wasm blob.
  .dependsOn(backendSpi, core, backendSqlRuntimeJs)
  .settings(
    name := "scalascript-backend-wasm",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// SourceLanguage plugin for `scala` fence blocks (docs/backend-spi.md §9 / Phase 9).
// Stage 9 skeleton: SourceLanguage impl + ServiceLoader entry; the
// existing in-core `scala`-block handling stays in place until the
// follow-up actually routes through here.
lazy val backendScalaSource = project
  .in(file("backend/scala-source"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-scala-source",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// SourceLanguage plugin for `html` fence blocks + (eventually) the
// `html"…"` interpolator and `Html` / DSL tag bindings via prelude.
// Stage 9+/B.1 skeleton: ServiceLoader entry only — Normalize routes
// `html` blocks through this plugin from now on, though the
// plugin's compileBlock passes source through unchanged (same shape
// as the old EmbeddedBlock fallback).  Stage 9+/B.2-B.4 migrate the
// real DSL / interpolator / containerTagNames logic out of the
// codegens.
lazy val backendHtml = project
  .in(file("backend/html"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-html",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// SourceLanguage plugin for `css` fence blocks + (eventually) the
// `css"…"` interpolator and `Css` type via prelude.  Stage 9+/C.1
// skeleton — same shape as backend-html.
lazy val backendCss = project
  .in(file("backend/css"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-css",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// TRANSITIONAL DEPENDENCY: backend-interpreter → backend-js.
// `server/WebServer.scala` imports `scalascript.codegen.{JsGen, JsRuntime}`
// to inject the JS runtime into SPA-mode pages.  Stage 5 (Backend SPI §8 —
// HTTP/WS intrinsics) extracts this so the server lives behind the SPI and
// backends no longer reference each other.
lazy val backendInterpreter = project
  .in(file("backend/interpreter"))
  .dependsOn(backendSpi, core, runtimeServerCommon, runtimeServerJvm, mcpCommon, backendJs, backendSqlRuntime, frontendCore, backendJvm % Test, frontendCustom % Test, frontendReact % Test, frontendSolid % Test, frontendVue % Test, jsonPlugin % Test, frontendPlugin, requestPlugin % Test, authPlugin % Test, oauthPlugin % Test)
  .settings(
    name := "scalascript-backend-interpreter",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    // v1.26 — JvmGen scala-cli runtime smoke test reads this resource
    // to find the locally-built backend-sql-runtime JAR.  Generating
    // the resource depends on `backendSqlRuntime/Compile/packageBin`
    // so the jar is always fresh when the test reads its path.
    Test / resourceGenerators += Def.task {
      val jar  = (backendSqlRuntime / Compile / packageBin).value
      val out  = (Test / resourceManaged).value / "scalascript" / "sql-runtime-jar.path"
      IO.createDirectory(out.getParentFile)
      IO.write(out, jar.getAbsolutePath)
      Seq(out)
    }.taskValue
  )

// Apache Spark backend — Phase 1 (local SparkSession).
// SparkGen is a pure code-emitter (generates Scala 3 source strings) — it
// does not import any Spark classes itself.  Spark JARs are therefore NOT
// needed on the sbt compile classpath; they are resolved at runtime via
// `scala-cli --dep` flags when the generated program is executed.
lazy val backendSpark = project
  .in(file("backend/spark"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-spark",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// ── ProGuard shrink task (run: sbt cli/shrinkJar) ──────────────────────────
// sbt-proguard is used ONLY for config generation + ProGuard JAR resolution;
// we bypass its runner (hardcoded -Xmx256M) and fork java with -Xmx1G.
val shrinkJar = taskKey[File]("Shrink the assembled ssc.jar with ProGuard 7.5 (1 G heap)")
val stage     = taskKey[Unit]("Stage lib/ssc.jar + lib/jars/ + lib/plugins/ for classpath-based launch")

lazy val cli = project
  .in(file("cli"))
  .enablePlugins(SbtProguard)
  .dependsOn(core, interop, backendJvm, backendJs, backendNode, backendScalajs, backendWasm, backendInterpreter, backendScalaSource, backendHtml, backendCss, backendSpark, frontendCore, frontendCustom, frontendReact, frontendSolid, frontendVue)
  .settings(
    name := "scalascript-cli",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "pprint" % "0.9.6",
      // v2.0 Phase 3 — in-process Scala 3 compiler driver
      // (`scalascript.cli.Scala3Driver`).  Replaces the per-module
      // `scala-cli compile` subprocess (~1 s JVM startup × N modules) with
      // a single in-JVM `dotty.tools.dotc.Driver` invocation per module.
      // Pinned to ThisBuild/scalaVersion (3.8.3) so the API we depend on
      // (`Driver.process(String[], Reporter)`, `Reporter.doReport`,
      // `Diagnostic`/`SourcePosition`) matches the compiler we link.
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
      // v2.0 Phase 4 (Option A) — JSR-45 SMAP source-map injection.  ASM
      // is used by `JvmSmapInjector` to walk packed `.class` files and
      // attach a `SourceDebugExtension` attribute carrying the .ssc → .class
      // line mapping.  ~150 KB; no transitive deps.
      "org.ow2.asm"    %  "asm"             % "9.7",
      scalatestTest
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    assembly / mainClass       := Some("scalascript.cli.ssc"),
    assembly / assemblyJarName := "ssc.jar",
    assembly / assemblyMergeStrategy := {
      // ServiceLoader records — concatenate, never discard or first-win
      // (BackendRegistry needs every backend module's entry).
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _ @ _*)         => MergeStrategy.discard
      case _                                    => MergeStrategy.first
    },
    // ── ProGuard configuration (used by shrinkJar, not by proguard task) ─
    Proguard / proguardVersion   := "7.5.0",
    Proguard / proguardInputs    := Seq(assembly.value),
    Proguard / proguardOutputs   := Seq((assembly / assemblyOutputPath).value.getParentFile / "ssc-min.jar"),
    Proguard / proguardLibraries := Nil,
    Proguard / proguardOptions   := Seq(
      "-keep class scalascript.cli.ssc { public static void main(java.lang.String[]); }",
      "-keep class scalascript.** { *; }",
      "-keep class scala.runtime.** { *; }",
      "-keep class scala.collection.** { *; }",
      "-keep class scala.reflect.** { *; }",
      "-keep class scala.quoted.** { *; }",
      "-keep class scala.io.** { *; }",
      "-keep class scala.sys.** { *; }",
      // Scala 3 lazy val backing fields use $lzy1 suffix — keep across all classes
      "-keepclassmembers class ** { private ** *$lzy1; }",
      // scala-meta: targeted keeps — excludes internal.quasiquotes (TreeLifts 1 MB +
      // ReificationMacros) which are compile-time-only macro impls unreachable at runtime.
      // scala.meta.package$ implements quasiquotes.Api so ProGuard will keep the public
      // quasiquotes API (XTension* companions), but not the internal implementation.
      // Direct AST classes (Term, Type, Defn, …) live in scala.meta package itself:
      "-keep class scala.meta.* { *; }",
      "-keep class scala.meta.classifiers.** { *; }",
      "-keep class scala.meta.parsers.** { *; }",
      "-keep class scala.meta.prettyprinters.** { *; }",
      "-keep class scala.meta.dialects.** { *; }",
      "-keep class scala.meta.inputs.** { *; }",
      "-keep class scala.meta.tokens.** { *; }",
      "-keep class scala.meta.tokenizers.** { *; }",
      "-keep class scala.meta.transversers.** { *; }",
      "-keep class scala.meta.internal.classifiers.** { *; }",
      "-keep class scala.meta.internal.dialects.** { *; }",
      "-keep class scala.meta.internal.inputs.** { *; }",
      "-keep class scala.meta.internal.io.** { *; }",
      "-keep class scala.meta.internal.parsers.** { *; }",
      "-keep class scala.meta.internal.platform.** { *; }",
      "-keep class scala.meta.internal.prettyprinters.** { *; }",
      "-keep class scala.meta.internal.tokenizers.** { *; }",
      "-keep class scala.meta.internal.tokens.** { *; }",
      "-keep class scala.meta.internal.transversers.** { *; }",
      "-keep class scala.meta.internal.trees.** { *; }",
      "-keep class scala.meta.shaded.** { *; }",
      "-keep class upickle.** { *; }",
      "-keep class ujson.** { *; }",
      "-keep class os.** { *; }",
      "-keep class geny.** { *; }",
      "-keep class pprint.** { *; }",
      "-keep class fansi.** { *; }",
      "-keep class sourcecode.** { *; }",
      "-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,LineNumberTable,SourceFile",
      "-dontwarn scala.**",
      "-dontwarn java.lang.invoke.**",
      "-dontwarn sun.**",
      "-dontwarn com.sun.**",
      "-dontoptimize",
      "-dontobfuscate",
      "-ignorewarnings"
    ) ++ {
      val jHome = file(System.getProperty("java.home"))
      if ((jHome / "jmods").exists)
        Seq("-libraryjars <java.home>/jmods(!**.jar,!module-info.class)")
      else
        Seq("-libraryjars <java.home>/lib/rt.jar")
    },
    // ── shrinkJar: bypass sbt-proguard runner; fork java with -Xmx1G ─────
    // proguardConfiguration in sbt-proguard 0.5.0 is a settingKey (path only,
    // no file written), so we write the config ourselves from proguardOptions.
    shrinkJar := {
      val log    = streams.value.log
      val opts   = (Proguard / proguardOptions).value
      val pgCp   = (Proguard / managedClasspath).value.files
                     .mkString(java.io.File.pathSeparator)
      val outJar = (Proguard / proguardOutputs).value.head
      val inJar  = (Proguard / proguardInputs).value.head
      val cfgDir = (Proguard / proguardConfiguration).value.getParentFile
      cfgDir.mkdirs()
      val cfgFile = cfgDir / "configuration.pro"
      val lines   = Seq(s"-injars  '${inJar.getAbsolutePath}'",
                        s"-outjars '${outJar.getAbsolutePath}'") ++
                    opts
      IO.writeLines(cfgFile, lines)
      log.info(s"ProGuard 7.5: ${inJar.getName} (${inJar.length / (1024*1024)} MB) → ${outJar.getName}")
      val cmd = Seq("java", "-Xmx1G", "-cp", pgCp, "proguard.ProGuard", s"@${cfgFile.getAbsolutePath}")
      val ret = scala.sys.process.Process(cmd, baseDirectory.value).!
      if (ret != 0) sys.error(s"ProGuard failed with exit code $ret")
      val saved = (inJar.length - outJar.length) / (1024 * 1024)
      log.info(s"Shrunk to ${outJar.length / (1024*1024)} MB  (saved ~${saved} MB)")
      outJar
    },
    // ── stage: thin JAR + exploded deps, no fat jar needed ───────────────
    // Produces:
    //   $ROOT/lib/ssc.jar        ← cli module classes only
    //   $ROOT/lib/jars/*.jar     ← all transitive deps (sub-modules + external)
    //   $ROOT/lib/plugins/       ← empty dir, ready for .sscpkg installation
    // Launcher: java -cp "$ROOT/lib/jars/*:$ROOT/lib/ssc.jar" scalascript.cli.ssc
    stage := {
      val log      = streams.value.log
      val root     = (ThisBuild / baseDirectory).value
      val libDir   = root / "lib"
      val depsDir  = libDir / "jars"
      val plugDir  = libDir / "plugins"
      IO.createDirectory(depsDir)
      IO.createDirectory(plugDir)
      val appJar   = (Compile / packageBin).value
      IO.copyFile(appJar, libDir / "ssc.jar")
      log.info(s"lib/ssc.jar  (${appJar.length / 1024} KB)")
      val cp       = (Compile / fullClasspath).value.files
      val depJars  = cp.filter(f => f.isFile && f.getName.endsWith(".jar") && f.getAbsolutePath != appJar.getAbsolutePath)
      depJars.foreach(j => IO.copyFile(j, depsDir / j.getName))
      log.info(s"lib/jars/    (${depJars.size} JARs)")
      log.info(s"lib/plugins/ ready")
    }
  )

// NOTE: `bench/` exists today as a scala-cli script directory (fib/sum/
// list-ops workload comparisons across backends) — not an sbt project.
// `WsStress` lives under backend-interpreter/.../bench/ since it
// stresses the interpreter's WS runtime.

lazy val clientPostgres = project
  .in(file("client/postgres"))
  .dependsOn(backendSqlRuntime)
  .settings(
    name := "scalascript-client-postgres",
    libraryDependencies ++= Seq(
      "org.postgresql"     %  "postgresql"      % "42.7.3",
      "com.zaxxer"         %  "HikariCP"        % "5.1.0",
      "com.h2database"     %  "h2"              % "2.2.224"   % Test,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// v1.26 — JDBC execution runtime consumed by interpreter + JvmGen.
// Self-contained (no `ir` / `backend-spi` / `core` deps): takes a
// `java.sql.Connection`, a `?`-templated SQL string, and an ordered
// bind list, returns a `Row`-based result.  Bundles H2 + SQLite so
// the standard examples / quickstarts work with zero configuration.
lazy val backendSqlRuntime = project
  .in(file("backend/sql-runtime"))
  .settings(
    name := "scalascript-backend-sql-runtime",
    libraryDependencies ++= Seq(
      "com.h2database"     %  "h2"              % "2.2.224",
      "org.xerial"         %  "sqlite-jdbc"     % "3.45.3.0",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// v1.27 — browser-side sql runtime.  Spec: docs/browser-sql.md.
//
// Companion to `backendSqlRuntime` for the JS-family backends.
// Mostly a packaging vehicle for the hand-written `sql-runtime.mjs`
// (lives in `src/main/resources`), plus a thin Scala layer:
//   * `ProviderId.fromUrl` — URL prefix → provider id, used by
//     backend-js/node/wasm to decide which npm deps to emit
//   * `SqlRuntimeJsEmit` — codegen helper that exposes the bundled
//     `.mjs` source as a String for JsGen to prepend to its output
//
// No backend SPI / ir dependency — same standalone shape as
// `backendSqlRuntime` so backend-js, backend-node, backend-wasm can
// all dependsOn without dragging the compiler frontend.
lazy val backendSqlRuntimeJs = project
  .in(file("backend/sql-runtime-js"))
  .settings(
    name := "scalascript-backend-sql-runtime-js",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val clientRedis = project
  .in(file("client/redis"))
  .settings(
    name := "scalascript-client-redis",
    libraryDependencies ++= Seq(
      "io.lettuce"  %  "lettuce-core"  % "6.3.2.RELEASE",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val clientEvm = project
  .in(file("client/evm"))
  .settings(
    name := "scalascript-client-evm",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"  % "4.0.23",
      "com.softwaremill.sttp.client4" %% "upickle" % "4.0.23",
      "com.lihaoyi"                   %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val clientKafka = project
  .in(file("client/kafka"))
  .settings(
    name := "scalascript-client-kafka",
    libraryDependencies ++= Seq(
      "org.apache.kafka" %  "kafka-clients" % "3.7.0",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val clientCoinbase = project
  .in(file("client/coinbase"))
  .settings(
    name := "scalascript-client-coinbase",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"  % "4.0.23",
      "com.lihaoyi"                   %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402Core = project
  .in(file("x402/core"))
  .settings(
    name := "scalascript-x402-core",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402Server = project
  .in(file("x402/server"))
  .dependsOn(x402Core, runtimeServerCommon)
  .settings(
    name := "scalascript-x402-server",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402Client = project
  .in(file("x402/client"))
  .dependsOn(x402Core, walletStrategyEoa, blockchainEvm, blockchainCardano, cryptoBouncycastle)
  .settings(
    name := "scalascript-x402-client",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402FacilitatorCoinbase = project
  .in(file("x402/facilitator-coinbase"))
  .dependsOn(x402Core, clientCoinbase)
  .settings(
    name := "scalascript-x402-facilitator-coinbase",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402FacilitatorEvm = project
  .in(file("x402/facilitator-evm"))
  .dependsOn(x402Core, clientEvm, blockchainEvm, walletStrategyEoa, cryptoBouncycastle)
  .settings(
    name := "scalascript-x402-facilitator-evm",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402QueueKafka = project
  .in(file("x402/queue-kafka"))
  .dependsOn(x402Core, clientKafka)
  .settings(
    name := "scalascript-x402-queue-kafka",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402QueuePostgres = project
  .in(file("x402/queue-postgres"))
  .dependsOn(x402Core, clientPostgres)
  .settings(
    name := "scalascript-x402-queue-postgres",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402NoncePostgres = project
  .in(file("x402/nonce-postgres"))
  .dependsOn(x402Core, clientPostgres)
  .settings(
    name := "scalascript-x402-nonce-postgres",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402NonceRedis = project
  .in(file("x402/nonce-redis"))
  .dependsOn(x402Core, clientRedis)
  .settings(
    name := "scalascript-x402-nonce-redis",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val clientBlockfrost = project
  .in(file("client/blockfrost"))
  .settings(
    name := "scalascript-client-blockfrost",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"    % "4.0.23",
      "com.lihaoyi"                   %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402FacilitatorCardano = project
  .in(file("x402/facilitator-cardano"))
  .dependsOn(x402Core, clientBlockfrost)
  .settings(
    name := "scalascript-x402-facilitator-cardano",
    libraryDependencies ++= Seq(
      "org.bouncycastle" % "bcprov-jdk18on" % "1.78.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Plutus-escrow settlement for x402 on Cardano. Phase 1 is scaffolding —
// trait + stub. Phase 2+ adds Scalus (validator) and bloxbean (off-chain
// Tx building) dependencies. See docs/x402-cardano-scalus.md.
lazy val x402FacilitatorCardanoScalus = project
  .in(file("x402/facilitator-cardano-scalus"))
  .dependsOn(x402Core, x402FacilitatorCardano)
  .settings(
    name := "scalascript-x402-facilitator-cardano-scalus",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    // The compiled Plutus V3 script ships as a checked-in resource
    // (`src/main/resources/x402-escrow.plutus.hex`) emitted by the
    // `x402EscrowPlutus` 3.3.7 sub-build below. The main 3.8.3 module
    // reads the hex at runtime via `getResourceAsStream` — no Scalus
    // dependency on this side. See `docs/x402-cardano-scalus.md` §5
    // "Phase 2 retry — Scala-version split build".
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Plutus V3 escrow validator — Scala 3.3.7 sub-project so the Scalus
// compiler plugin (built against dotty 3.3.x) can actually run.
// Output is a single resource file (CBOR hex) committed under
// `x402-facilitator-cardano-scalus/src/main/resources/` and consumed
// by the main 3.8.3 module. See `docs/x402-cardano-scalus.md` §5.
//
// This sub-project deliberately depends on NO other modules in this
// repo — every other module is built for Scala 3.8.3, and TASTy isn't
// cross-version-compatible.
lazy val emitEscrowHex = taskKey[File](
  "Re-emit the compiled Plutus escrow CBOR hex into the main module's resources",
)

lazy val x402EscrowPlutus = project
  .in(file("x402/escrow-plutus"))
  .settings(
    name := "scalascript-x402-escrow-plutus",
    scalaVersion := "3.3.7",
    libraryDependencies ++= Seq(
      "org.scalus" %% "scalus" % "0.15.1",
    ),
    // Required so `PlutusV3.compile(...)` actually lowers @Compile
    // objects to Plutus Core. Fails to load against Scala 3.8.x —
    // works against 3.3.7 (this module's scalaVersion override above).
    addCompilerPlugin("org.scalus" %% "scalus-plugin" % "0.15.1"),
    // Append the lighter warning flags (no `-Werror`, no `-Wunused:all`)
    // so Scalus's @Compile-generated synthetic vals + the unused-import
    // warnings from the on-chain DSL don't fail the build. We use `++=`
    // (not `:=`) so the `-Xplugin:...` directive `addCompilerPlugin`
    // injected above survives.
    Compile / scalacOptions ++= Seq("-deprecation", "-feature"),
    // Explicit refresh task. Re-run after editing the validator
    // source: `sbt x402EscrowPlutus/emitEscrowHex`. Result is committed
    // as a static resource of `x402-facilitator-cardano-scalus`.
    emitEscrowHex := {
      val log    = streams.value.log
      val _      = (Compile / compile).value
      val cp     = (Compile / fullClasspath).value.files
      val target = ((ThisBuild / baseDirectory).value /
        "x402/facilitator-cardano-scalus/src/main/resources/x402-escrow.plutus.hex")
      IO.createDirectory(target.getParentFile)
      val classpath = cp.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
      val pb = new java.lang.ProcessBuilder(
        "java", "-cp", classpath,
        "scalascript.x402.escrow.plutus.EmitEscrowCbor",
        target.getAbsolutePath,
      )
      pb.redirectErrorStream(true)
      val proc   = pb.start()
      val output = scala.io.Source.fromInputStream(proc.getInputStream).mkString
      val rc     = proc.waitFor()
      if (rc != 0) sys.error(s"EmitEscrowCbor failed (exit $rc):\n$output")
      log.info(s"Plutus escrow hex → ${target.getAbsolutePath} (${target.length} bytes)")
      target
    },
  )

// ---------------------------------------------------------------------------
// Wallet / blockchain SPI tracks (docs/blockchain-spi.md + docs/wallet-spi.md)
//
// Phase 1 lands four pure-trait modules below. Phase 2 adds
// crypto-bouncycastle (JVM CryptoBackend impl) and blockchain-evm.
// All modules are JVM-only for now; Scala.js cross-compile follows in
// Phase 3 (crypto-noble-js) per the spec.
// ---------------------------------------------------------------------------

// Cross-compiled (JVM + Scala.js) — docs/wallet-spi-scalajs.md §3.2.
// Stage 1: traits + value classes in `shared/`; ServiceLoader-backed
// `object CryptoBackend` (JVM) and explicit-registration variant (JS)
// live in their platform source dirs.
lazy val cryptoSpiCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("crypto/spi"))
    .settings(
      name := "scalascript-crypto-spi",
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    // Rename auto-generated project IDs ("cryptoSpiCrossJVM" /
    // "cryptoSpiCrossJS") to the shorter "cryptoSpi" / "cryptoSpiJs"
    // so `sbt cryptoSpi/test` keeps working for downstream agents.
    .jvmConfigure(_.withId("cryptoSpi"))
    .jsConfigure(_.withId("cryptoSpiJs"))
    // Scala.js spawns its own Node subprocess for tests; the
    // ThisBuild-level `Test / fork := true` would break that pipe.
    .jsSettings(Test / fork := false)

lazy val cryptoSpiJvm = cryptoSpiCross.jvm
lazy val cryptoSpiJs  = cryptoSpiCross.js
// JVM alias — the rest of the build is still JVM-only and depends on
// `cryptoSpi` by this name in dozens of places.
lazy val cryptoSpi    = cryptoSpiJvm

lazy val cryptoBouncycastle = project
  .in(file("crypto/bouncycastle"))
  .dependsOn(cryptoSpi)
  .settings(
    name := "scalascript-crypto-bouncycastle",
    libraryDependencies ++= Seq(
      "org.bouncycastle" % "bcprov-jdk18on" % "1.78.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Scala.js-only `CryptoBackend` impl — docs/wallet-spi-scalajs.md §5
// Stage 2.  Backed by `@noble/curves` + `@noble/hashes` (npm pkgs at
// `crypto-noble-js/package.json`; install with `npm install` from that
// directory before running `sbt cryptoNobleJs/test`).  Output bytes
// match the JVM BouncyCastle backend bit-for-bit so the same SPI call
// is platform-agnostic — see the cross-verification fixtures in
// `crypto-noble-js/src/test/scala/.../NobleCryptoBackendTest.scala`.
//
// CommonJS module kind is set so noble's `require()`-style exports
// resolve at link time; the default `NoModule` would refuse `@JSImport`.
// Node ≥ 18 on PATH is required for `sbt cryptoNobleJs/test`.
lazy val cryptoNobleJs = project
  .in(file("crypto/noble-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(cryptoSpiJs)
  .settings(
    name                := "scalascript-crypto-noble-js",
    scalaJSLinkerConfig ~= { _.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule) },
    libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.18" % Test,
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    // Scala.js spawns its own Node.js subprocess for the test runner;
    // ThisBuild's `Test / fork := true` would break that pipe.
    Test / fork         := false,
  )

// Cross-compiled (JVM + Scala.js) — docs/wallet-spi-scalajs.md §3.2.
// Stage 1: SPI traits in `shared/`; `object Blockchain` ServiceLoader
// registry (JVM) and explicit-registration variant (JS) live in their
// platform source dirs.  upickle's `%%%` resolves to the right artefact
// per platform (Scala.js 1.x build of upickle 4.4.2 ships natively).
lazy val blockchainSpiCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("blockchain/spi"))
    .dependsOn(cryptoSpiCross)
    .settings(
      name := "scalascript-blockchain-spi",
      libraryDependencies += "com.lihaoyi" %%% "upickle" % "4.4.2",
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .jvmConfigure(_.withId("blockchainSpi"))
    .jsConfigure(_.withId("blockchainSpiJs"))
    .jsSettings(Test / fork := false)

lazy val blockchainSpiJvm = blockchainSpiCross.jvm
lazy val blockchainSpiJs  = blockchainSpiCross.js
lazy val blockchainSpi    = blockchainSpiJvm

// Cross-compiled (JVM + Scala.js) — docs/wallet-spi-scalajs.md.
// Stage 1: pure SPI traits + value classes live in `shared/`; `jvm/`
// and `js/` source dirs are empty for now (platform-specific helpers
// like Scala.js connector glue land in later stages).  Smoke test in
// `shared/src/test/` runs on both platforms.
//
// CI hint: `sbt walletSpiJs/test` runs the Scala.js test suite on
// Node.js (default sbt-scalajs runner); requires `node >= 18` on the
// PATH.  No CI wiring in this slice; first JS-side CI hookup follows
// the broader cross-compile sweep (Stage 3+).
lazy val walletSpiCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("wallet/spi"))
    .dependsOn(blockchainSpiCross, cryptoSpiCross)
    .settings(
      name := "scalascript-wallet-spi",
      libraryDependencies ++= Seq(
        "com.lihaoyi"   %%% "upickle"   % "4.4.2",
        "org.scalatest" %%% "scalatest" % "3.2.18" % Test,
      ),
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .jvmConfigure(_.withId("walletSpi"))
    .jsConfigure(_.withId("walletSpiJs"))
    // Scala.js tests run inside the same JVM as sbt (the Node.js
    // subprocess is spawned by the test runner itself, not by `fork`).
    // The ThisBuild-level `Test / fork := true` would break that.
    .jsSettings(Test / fork := false)

lazy val walletSpiJvm = walletSpiCross.jvm
lazy val walletSpiJs  = walletSpiCross.js
// JVM alias — every downstream module that `.dependsOn(walletSpi)`
// stays JVM-only for now and continues to use this name.
lazy val walletSpi    = walletSpiJvm

// Cross-compiled (JVM + Scala.js) — docs/wallet-spi-scalajs.md § Stage 5.
//
// `shared/` holds the platform-neutral pieces — BIP-39 (with the
// English wordlist embedded as a Scala const so the JS build doesn't
// need a classpath resource), the `VaultFile` data + JSON codec, and
// the `EncryptedLocalVault` core (CryptoBackend-driven Argon2id +
// AES-GCM + HD derivation; takes a pluggable `save: VaultFile => Unit`
// sink).
//
// `jvm/` adds the `java.nio.file`-based persistence wrappers:
// `VaultFileIo.{read,write}` and `EncryptedLocalVaultFs.{create,load,generate}`
// (preserves the pre-Stage-5 JVM-side API surface so all downstream
// callers and existing tests keep working).  The Scala.js side wires
// its own `save` callback (IndexedDB persistence is deferred — the
// shared core lights up unchanged once that lands).
//
// JS tests depend on `cryptoNobleJs` so `CryptoBackend.get()` resolves
// to the Stage-2 noble backend (Stage 5a added PBKDF2 / Argon2id /
// AES-GCM to it).  Module kind is CommonJS to match crypto-noble-js.
lazy val walletVaultEncryptedCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("wallet/vault-encrypted"))
    .dependsOn(walletSpiCross, cryptoSpiCross)
    .settings(
      name := "scalascript-wallet-vault-encrypted",
      libraryDependencies += "com.lihaoyi"   %%% "upickle"   % "4.4.2",
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.18" % Test,
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    // Preserve the legacy `walletVaultEncrypted/test` invocation.
    .jvmConfigure(_.withId("walletVaultEncrypted"))
    .jvmConfigure(_.dependsOn(cryptoBouncycastle))
    .jsConfigure(_.withId("walletVaultEncryptedJs"))
    .jsConfigure(_.dependsOn(cryptoNobleJs % Test))
    .jsSettings(
      Test / fork := false,
      // crypto-noble-js links as CommonJSModule (so @noble/* `require()`
      // exports resolve); downstream modules that depend on it in
      // tests must use the same module kind.
      scalaJSLinkerConfig ~= { _.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule) },
    )

lazy val walletVaultEncryptedJvm = walletVaultEncryptedCross.jvm
lazy val walletVaultEncryptedJs  = walletVaultEncryptedCross.js
// JVM alias — every downstream module that `.dependsOn(walletVaultEncrypted)`
// stays JVM-only and continues to use this name.
lazy val walletVaultEncrypted    = walletVaultEncryptedJvm

// Cross-compiled (JVM + Scala.js) — docs/wallet-spi-scalajs.md § Stage 3.
// Pure SPI usage; no JVM-only deps in `shared/`.  The Stage 1 / Stage 2
// CryptoBackend registry resolves correctly on both platforms, so the
// same `RawPrivateKeyVault` + `EoaStrategy` link unchanged on JS.
lazy val walletStrategyEoaCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("wallet/strategy-eoa"))
    .dependsOn(walletSpiCross, blockchainSpiCross, cryptoSpiCross)
    .settings(
      name := "scalascript-wallet-strategy-eoa",
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.18" % Test,
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    // Preserve the legacy `walletStrategyEoa/test` invocation.
    .jvmConfigure(_.withId("walletStrategyEoa"))
    .jsConfigure(_.withId("walletStrategyEoaJs"))
    .jsSettings(Test / fork := false)

lazy val walletStrategyEoaJvm = walletStrategyEoaCross.jvm
lazy val walletStrategyEoaJs  = walletStrategyEoaCross.js
// JVM alias — every downstream module that `.dependsOn(walletStrategyEoa)`
// stays JVM-only for now and continues to use this name.
lazy val walletStrategyEoa    = walletStrategyEoaJvm

// wallet-spi Phase 8 — MPC Vault (docs/wallet-spi.md §10).
// HTTP client to an external multi-party-computation signing provider.
// No private keys local; every signature is a round-trip to a TSS /
// FROST / GG18 quorum. The trait abstracts over actual MPC vendors;
// `HttpRemoteSigningClient` is a reference "Fireblocks-shaped" REST
// integration with sync + async-polling support.
lazy val walletVaultMpc = project
  .in(file("wallet/vault-mpc"))
  .dependsOn(walletSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-vault-mpc",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Cross-compiled (JVM + Scala.js) — docs/wallet-spi-scalajs.md § Stage 4.
//
// `shared/` holds the pure-data + crypto-driven types: UserOperation,
// UserOpHash{,V07}, EntryPoint, SmartAccountFactory (incl.
// SimpleAccountFactory), PasskeyAssertion, PasskeySigner,
// SimplePasskeyAccountFactory, plus a small inlined Hex codec so the
// shared sources don't reach into JVM-only `blockchain-evm`.
//
// `jvm/` keeps the modules that need `EvmChainAdapter` / HTTP RPC and
// therefore stay JVM-only for now: BundlerClient, SmartAccountAdapter,
// SmartAccount (the `wrap(...)` helper).  These are exactly the
// pieces that depend on `blockchain-evm`, which itself is JVM-only
// because of `java.net.http.HttpClient`.
//
// `js/` adds the WebAuthn / `navigator.credentials.get(...)` facade
// (`WebAuthnFacade` + `PasskeySignerJs.fromBrowserPasskey`) that
// implements `PasskeySigner.assertChallenge` in the browser.
lazy val walletStrategyErc4337Cross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("wallet/strategy-erc4337"))
    .dependsOn(walletSpiCross, blockchainSpiCross, cryptoSpiCross, blockchainEvmAbiCross)
    .settings(
      name := "scalascript-wallet-strategy-erc4337",
      libraryDependencies += "com.lihaoyi"   %%% "upickle"   % "4.4.2",
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.18" % Test,
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    // Preserve the legacy `walletStrategyErc4337/test` invocation.
    .jvmConfigure(_.withId("walletStrategyErc4337"))
    .jvmConfigure(_.dependsOn(blockchainEvm, cryptoBouncycastle % Test, walletStrategyEoa % Test))
    .jsConfigure(_.withId("walletStrategyErc4337Js"))
    .jsConfigure(_.dependsOn(cryptoNobleJs % Test))
    .jsSettings(
      Test / fork := false,
      // scalajs-dom 2.8+ — DOM facades for WebAuthn
      // (`navigator.credentials.get` and `PublicKeyCredential`).
      libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.8.0",
      // crypto-noble-js compiles as CommonJSModule; the test-scope
      // dependency forces the same module kind here too.
      scalaJSLinkerConfig ~= { _.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule) },
    )

lazy val walletStrategyErc4337Jvm = walletStrategyErc4337Cross.jvm
lazy val walletStrategyErc4337Js  = walletStrategyErc4337Cross.js
// JVM alias — every downstream consumer that `.dependsOn(walletStrategyErc4337)`
// stays JVM-only and continues to use this name.
lazy val walletStrategyErc4337    = walletStrategyErc4337Jvm

// Cross-compiled (JVM + Scala.js) — docs/wallet-spi-scalajs.md § Stage 3.
// `shared/` holds the protocol translator + EIP-6963 value types (no
// java.* deps); `js/` adds the Scala.js browser glue that wires the
// translator to `window.ethereum` and the EIP-6963 announce / request
// event flow via scalajs-dom.  JVM-only tests (real EVM signer +
// adapter) live in `jvm/src/test/scala/`.
lazy val walletConnectorEip1193Cross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("wallet/connector-eip1193"))
    .dependsOn(walletSpiCross, blockchainSpiCross)
    .settings(
      name := "scalascript-wallet-connector-eip1193",
      libraryDependencies += "com.lihaoyi"   %%% "upickle"   % "4.4.2",
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.18" % Test,
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .jvmConfigure(_.withId("walletConnectorEip1193"))
    .jvmConfigure(_.dependsOn(walletStrategyEoa % Test, blockchainEvm % Test, cryptoBouncycastle % Test))
    .jsConfigure(_.withId("walletConnectorEip1193Js"))
    .jsSettings(
      Test / fork := false,
      // scalajs-dom 2.8+ — DOM facades (window, CustomEvent, Event).
      libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.8.0",
    )

lazy val walletConnectorEip1193Jvm = walletConnectorEip1193Cross.jvm
lazy val walletConnectorEip1193Js  = walletConnectorEip1193Cross.js
lazy val walletConnectorEip1193    = walletConnectorEip1193Jvm

// Cross-compiled (JVM + Scala.js) — docs/wallet-spi-scalajs.md § Stage 6.
//
// `shared/` holds the WC v2 protocol guts: `WcTypes`, `WcRelayTransport`
// (trait), `WsChannel` (trait), `RelayJsonRpc`, `WcSessionStore`,
// `RelayJwt`, `WcEnvelope`, `WcKeyAgreement` (all CryptoBackend-driven
// — zero `java.*` / `javax.*` / `org.bouncycastle.*` after the Stage 6
// refactor), `RelayTransportBase` (the demux + JSON-RPC core shared by
// both platform transports), and `WalletConnectConnector`.
//
// `jvm/` adds the JVM-only platform glue: `JdkWsChannel` (over
// `java.net.http.WebSocket`) and `JvmRelayTransport` (the legacy entry
// point — now a thin sub-class of `RelayTransportBase`).
//
// `js/` adds the browser-side glue: `BrowserWsChannel` (over native
// `WebSocket`) and `JsRelayTransport`.  Tests inject a mock
// `WsChannel` exactly like JVM.
//
// JVM test crypto is BouncyCastle (auto-registered via ServiceLoader);
// JS test crypto is `crypto-noble-js` (registered explicitly in the
// per-suite `beforeAll`).  Module kind is CommonJS to match
// crypto-noble-js.
lazy val walletConnectCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("wallet/connect"))
    .dependsOn(walletSpiCross, blockchainSpiCross, cryptoSpiCross)
    .settings(
      name := "scalascript-wallet-connect",
      libraryDependencies += "com.lihaoyi"   %%% "upickle"   % "4.4.2",
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.18" % Test,
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .jvmConfigure(_.withId("walletConnect"))
    .jvmConfigure(_.dependsOn(cryptoBouncycastle % Test))
    .jsConfigure(_.withId("walletConnectJs"))
    .jsConfigure(_.dependsOn(cryptoNobleJs % Test))
    .jsSettings(
      Test / fork := false,
      // crypto-noble-js compiles as CommonJSModule (so @noble/*
      // `require()` exports resolve); downstream modules that depend
      // on it in tests must use the same module kind.
      scalaJSLinkerConfig ~= { _.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule) },
    )

lazy val walletConnectJvm = walletConnectCross.jvm
lazy val walletConnectJs  = walletConnectCross.js
// JVM alias — every downstream module that `.dependsOn(walletConnect)`
// stays JVM-only and continues to use this name.
lazy val walletConnect    = walletConnectJvm

// Phase 7 (docs/wallet-spi.md §5.1) — Ledger hardware-wallet vault.
// Three modules: shared types here (cross-compile-ready, JVM-only for
// now), `wallet-vault-ledger-jvm` providing the hid4java transport,
// and `wallet-vault-ledger-ethereum` providing the Ethereum-app signer.
lazy val walletVaultLedger = project
  .in(file("wallet/vault-ledger"))
  .dependsOn(walletSpi, cryptoSpi, blockchainSpi)
  .settings(
    name := "scalascript-wallet-vault-ledger",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultLedgerJvm = project
  .in(file("wallet/vault-ledger-jvm"))
  .dependsOn(walletVaultLedger)
  .settings(
    name := "scalascript-wallet-vault-ledger-jvm",
    libraryDependencies ++= Seq(
      // Cross-platform USB HID for the JVM. Pulls in JNA transitively.
      "org.hid4java" % "hid4java" % "0.7.0",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultLedgerEthereum = project
  .in(file("wallet/vault-ledger-ethereum"))
  .dependsOn(walletVaultLedger, walletVaultLedger % "test->test", walletSpi, cryptoSpi, blockchainEvm, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-wallet-vault-ledger-ethereum",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Cross-compiled (JVM + Scala.js) — docs/wallet-spi-scalajs.md § Stage 3.
// `shared/` holds a `WalletStandardConnectorBase` + a small inlined
// subset of the Solana legacy-message wire protocol (`SolanaMessage`,
// `SolanaInstruction`, `Base58`, `CompactU16`) — both platforms parse
// / serialise message bytes from the same code.  The JVM-side concrete
// `WalletStandardConnector` bridges to the existing
// `blockchain-solana` `SolanaTx` / `SolanaSignedTx` types so legacy
// JVM consumers (mcp-wallet, gRPC bridge tests) keep working; the
// Scala.js variant exposes `window.standard.wallets.registerWallet`
// + `wallet-standard:register-wallet` DOM-event registration via
// scalajs-dom.
lazy val walletConnectorWalletStdCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("wallet/connector-wallet-std"))
    .dependsOn(walletSpiCross, blockchainSpiCross)
    .settings(
      name := "scalascript-wallet-connector-wallet-std",
      libraryDependencies += "com.lihaoyi"   %%% "upickle"   % "4.4.2",
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.18" % Test,
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .jvmConfigure(_.withId("walletConnectorWalletStd"))
    .jvmConfigure(_.dependsOn(blockchainSolana, walletStrategyEoa % Test, cryptoBouncycastle % Test))
    .jsConfigure(_.withId("walletConnectorWalletStdJs"))
    .jsSettings(
      Test / fork := false,
      libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.8.0",
    )

lazy val walletConnectorWalletStdJvm = walletConnectorWalletStdCross.jvm
lazy val walletConnectorWalletStdJs  = walletConnectorWalletStdCross.js
lazy val walletConnectorWalletStd    = walletConnectorWalletStdJvm

lazy val mcpWallet = project
  .in(file("mcp/wallet"))
  .dependsOn(
    mcpCommon,
    walletSpi,
    blockchainSpi,
    blockchainEvm,                    // Eip3009 typed-data helper for payX402
    walletStrategyEoa  % Test,
    cryptoBouncycastle % Test,
  )
  .settings(
    name := "scalascript-mcp-wallet",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    // mcp-common pins upickle 4.4.2 while wallet-spi / blockchain-spi
    // are on 3.3.1 (matching client-evm's pin). The two ujson APIs we
    // touch are compatible; allow the conflict to resolve to the
    // higher version.
    libraryDependencySchemes ++= Seq(
      "com.lihaoyi" %% "upickle"    % "always",
      "com.lihaoyi" %% "ujson"      % "always",
      "com.lihaoyi" %% "upickle-implicits" % "always",
      "com.lihaoyi" %% "upickle-core"      % "always",
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val mcpX402 = project
  .in(file("mcp/x402"))
  .dependsOn(
    mcpCommon, x402Core,
    // Test-scope: the composition demo wires mcp-wallet + blockchain-evm
    // into the agent flow. Production callers don't need these on
    // compile classpath — McpWalletPaymentSigner only needs an opaque
    // `callTool: (name, args) => Future[ujson.Value]` function.
    mcpWallet          % Test,
    walletStrategyEoa  % Test,
    blockchainEvm      % Test,
    cryptoBouncycastle % Test,
  )
  .settings(
    name := "scalascript-mcp-x402",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    libraryDependencySchemes ++= Seq(
      "com.lihaoyi" %% "upickle"           % "always",
      "com.lihaoyi" %% "ujson"             % "always",
      "com.lihaoyi" %% "upickle-implicits" % "always",
      "com.lihaoyi" %% "upickle-core"      % "always",
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Cross-compiled (JVM + Scala.js) — docs/wallet-spi-scalajs.md § Stage 4.
// `shared/` holds the entire ABI codec (zero `java.*` deps; the few
// `java.util.Arrays.copyOfRange` / `java.io.ByteArrayOutputStream` /
// `java.lang.StringBuilder` calls are all in the Scala.js stdlib).
// JS-side tests register `cryptoNobleJs` so `Selector` (keccak256)
// resolves to a real backend; JVM-side tests stay on
// `cryptoBouncycastle` as before.
lazy val blockchainEvmAbiCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("blockchain/evm-abi"))
    .dependsOn(cryptoSpiCross)
    .settings(
      name := "scalascript-blockchain-evm-abi",
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.18" % Test,
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .jvmConfigure(_.withId("blockchainEvmAbi"))
    .jvmConfigure(_.dependsOn(cryptoBouncycastle % Test))
    .jsConfigure(_.withId("blockchainEvmAbiJs"))
    .jsConfigure(_.dependsOn(cryptoNobleJs % Test))
    .jsSettings(
      Test / fork := false,
      // crypto-noble-js compiles as CommonJSModule (so @noble/* `require()`
      // exports resolve); a downstream that links it in tests must use
      // the same module kind.
      scalaJSLinkerConfig ~= { _.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule) },
    )

lazy val blockchainEvmAbiJvm = blockchainEvmAbiCross.jvm
lazy val blockchainEvmAbiJs  = blockchainEvmAbiCross.js
// JVM alias — every downstream module that `.dependsOn(blockchainEvmAbi)`
// stays JVM-only and continues to use this name.
lazy val blockchainEvmAbi    = blockchainEvmAbiJvm

lazy val blockchainEvm = project
  .in(file("blockchain/evm"))
  .dependsOn(blockchainSpi, cryptoSpi, blockchainEvmAbi, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-blockchain-evm",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val blockchainSolana = project
  .in(file("blockchain/solana"))
  .dependsOn(blockchainSpi, cryptoSpi, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-blockchain-solana",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val blockchainCardano = project
  .in(file("blockchain/cardano"))
  .dependsOn(blockchainSpi, cryptoSpi, cryptoBouncycastle, clientBlockfrost)
  .settings(
    name := "scalascript-blockchain-cardano",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Micropayment platform SPI (docs/micropayment-spi.md)
// ---------------------------------------------------------------------------

lazy val micropaymentSpi = project
  .in(file("micropayment/spi"))
  .dependsOn(blockchainSpi, walletSpi)
  .settings(
    name := "scalascript-micropayment-spi",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentThreshold = project
  .in(file("micropayment/threshold"))
  .dependsOn(micropaymentSpi, walletSpi, blockchainSpi)
  .settings(
    name := "scalascript-micropayment-threshold",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentServer = project
  .in(file("micropayment/server"))
  .dependsOn(micropaymentSpi, runtimeServerCommon)
  .settings(
    name := "scalascript-micropayment-server",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentClient = project
  .in(file("micropayment/client"))
  .dependsOn(micropaymentSpi)
  .settings(
    name := "scalascript-micropayment-client",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.2",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentProbabilistic = project
  .in(file("micropayment/probabilistic"))
  .dependsOn(micropaymentSpi, blockchainSpi)
  .settings(
    name := "scalascript-micropayment-probabilistic",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentChannelEvm = project
  .in(file("micropayment/channel-evm"))
  .dependsOn(micropaymentSpi, blockchainSpi, blockchainEvm, walletSpi, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-micropayment-channel-evm",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentHydra = project
  .in(file("micropayment/hydra"))
  .dependsOn(micropaymentSpi, blockchainSpi)
  .settings(
    name := "scalascript-micropayment-hydra",
    libraryDependencies ++= Seq("com.lihaoyi" %% "upickle" % "4.4.2", scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Phase 1 intrinsic plugins (§ intrinsics-migration.md) ────────────────────
// json, frontend, and request families extracted from the bundled interpreter
// into ServiceLoader plugins.  Registered as `% Test` on backendInterpreter so
// the existing end-to-end test suite continues to pass with the families loaded
// as plugins rather than hardcoded intrinsics.

lazy val jsonPlugin = project
  .in(file("std/json-plugin"))
  .dependsOn(backendSpi, ir, core)
  .settings(
    name := "scalascript-json-plugin",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val frontendPlugin = project
  .in(file("std/frontend-plugin"))
  .dependsOn(backendSpi, ir, core, frontendCore, frontendCustom % Test, frontendReact % Test, frontendSolid % Test, frontendVue % Test)
  .settings(
    name := "scalascript-frontend-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val requestPlugin = project
  .in(file("std/request-plugin"))
  .dependsOn(backendSpi, ir, core)
  .settings(
    name := "scalascript-request-plugin",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val authPlugin = project
  .in(file("std/auth-plugin"))
  .dependsOn(backendSpi, ir, core, runtimeServerCommon)
  .settings(
    name := "scalascript-auth-plugin",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val oauthPlugin = project
  .in(file("std/oauth-plugin"))
  .dependsOn(backendSpi, ir, core, mcpCommon, runtimeServerCommon)
  .settings(
    name := "scalascript-oauth-plugin",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    backendSpi, ir, core, interop,
    runtimeServerCommon, runtimeServerSpi, runtimeServerJvm,
    runtimeServerJvmJetty, runtimeServerJvmNetty, mcpCommon,
    backendJvm, backendJs, backendNode, backendScalajs, backendWasm, backendInterpreter,
    backendScalaSource, backendHtml, backendCss, backendSpark,
    cli, clientPostgres, clientRedis, clientEvm, clientKafka, clientCoinbase, backendSqlRuntime, backendSqlRuntimeJs,
    clientBlockfrost,
    x402Core, x402Server, x402Client,
    x402FacilitatorCoinbase, x402FacilitatorEvm, x402FacilitatorCardano,
    x402QueueKafka, x402QueuePostgres, x402NoncePostgres, x402NonceRedis,
    cryptoSpi, cryptoSpiJs, cryptoBouncycastle, cryptoNobleJs, blockchainSpi, blockchainSpiJs, blockchainEvm, blockchainEvmAbi, blockchainEvmAbiJs, blockchainSolana, blockchainCardano, walletSpi, walletSpiJs, walletVaultEncrypted, walletVaultEncryptedJs, walletVaultMpc, walletVaultLedger, walletVaultLedgerJvm, walletVaultLedgerEthereum, walletStrategyEoa, walletStrategyEoaJs, walletStrategyErc4337, walletStrategyErc4337Js, walletConnectorEip1193, walletConnectorEip1193Js, walletConnect, walletConnectJs, walletConnectorWalletStd, walletConnectorWalletStdJs, mcpWallet, mcpX402,
    micropaymentSpi, micropaymentThreshold, micropaymentServer, micropaymentClient, micropaymentProbabilistic, micropaymentChannelEvm, micropaymentHydra,
    frontendCore, frontendCustom, frontendReact, frontendSolid, frontendVue,
    // frontendToolkit retired — replaced by std/ui/*.ssc (Phase 7a-7d)
    frontendExamples,
    jsonPlugin, frontendPlugin, requestPlugin,
    authPlugin, oauthPlugin,
  )
  .settings(
    publish / skip := true
  )
