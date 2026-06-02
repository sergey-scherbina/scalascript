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
ThisBuild / Test / javaOptions += {
  val root = (ThisBuild / baseDirectory).value
  s"-Dssc.std.path=${root.getAbsolutePath}/runtime"
}
ThisBuild / Test / fork         := true
// Export sub-module classes as JARs so cli/stage sees actual JAR files
// (not class directories) when collecting the classpath for lib/jars/.
ThisBuild / exportJars          := true

// Production code is held to fatal-warnings; test code stays warning-tolerant
// because scalatest macros, mocks, and intentional unused vals are common
// patterns that aren't worth silencing one-by-one.
val sharedScalacOptions       = Seq("-Wunused:all", "-deprecation", "-feature")
val sharedScalacOptionsStrict = sharedScalacOptions :+ "-Werror"

// ── Dependency version catalog ───────────────────────────────────────────
// Single source of truth for versions shared across many modules. Bump here,
// not at 45 scattered call sites. The lihaoyi family (upickle/ujson/upack)
// must move together — they share one release train.
val upickleV   = "4.4.2"
val scalatestV = "3.2.18"

val scalatestTest       = "org.scalatest" %% "scalatest" % scalatestV % Test

val javafxVersion: String = "21.0.5"
val javafxClassifier: String = {
  val os   = Option(System.getProperty("os.name")).getOrElse("").toLowerCase
  val arch = Option(System.getProperty("os.arch")).getOrElse("")
  if (os.startsWith("mac") && arch == "aarch64") "mac-aarch64"
  else if (os.startsWith("mac"))                  "mac"
  else if (os.startsWith("win"))                  "win"
  else                                            "linux"
}

// ── Plugin registry (arch-build-registry-p1) ─────────────────────────────
// Single source of truth for all standard-library plugin projects.
// `allPlugins` is the canonical seq; all five derived lists (cli test deps,
// installBin jarPrefix set, installBin pluginPkgs, root aggregate, and
// backendInterpreterPluginTests deps) are computed from it.
//
// PluginSpec is defined in project/PluginSpec.scala so it is visible in all
// build.sbt segments (sbt limitation: bare class defs in .sbt are not always
// in scope across segment compilation boundaries).
//
// Note on `pluginPkgs` inside `installBin`: sbt's task-macro prevents
// dynamic `.value` resolution in a loop, so that list remains explicit.
// Every other place uses `allPlugins.*` derivation. `PluginSpec` lives under
// `project/` so sbt exposes it consistently across the generated build units.

// ---------------------------------------------------------------------------
// Backend SPI v0.1 — module layout (docs/backend-spi.md §4.1)
//
// Stage 1.2: sources moved out of compiler/ into the new modules.
// ---------------------------------------------------------------------------

lazy val ir = project
  .in(file("lang/ir"))
  .settings(
    name := "scalascript-ir",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendSpi = project
  .in(file("runtime/backend/spi"))
  .dependsOn(ir, markupCore)
  .settings(
    name := "scalascript-backend-spi",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// ── Stable Plugin API (arch-stable-spi) ──────────────────────────────────
// Stable surface for plugin authors: PluginValue, PluginError,
// PluginComputation, JsonCodec, PluginContext.  Plugins depend only on
// this module; they never import scalascript.interpreter.* directly.
lazy val pluginApi = project
  .in(file("runtime/scalascript-plugin-api"))
  .dependsOn(backendSpi, ir)
  .settings(
    name := "scalascript-plugin-api",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// ── Wire protocol shared module (v1.62.1) ─────────────────────────────────
// WireValue, WireEnvelope, WireCodec[A], JSON/MsgPack/CBOR profiles.
// No dependency on backendSpi — usable from any module.
lazy val wireCore = project
  .in(file("backend/wire"))
  .settings(
    name := "scalascript-wire-core",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle"   % upickleV,    // JSON (ujson) + MsgPack (upack)
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Self-contained logger SPI — no external dependencies.
// Logger.scala is also packaged as a JAR resource so JvmGen can inline
// it verbatim into generated scala-cli scripts, making the same
// scalascript.logging.Logger type available in those scripts without
// an external dep or publishLocal.
lazy val logger = project
  .in(file("backend/logger"))
  .settings(
    name := "scalascript-logger",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    Compile / resourceGenerators += Def.task {
      val srcFile = (Compile / scalaSource).value / "scalascript" / "logging" / "Logger.scala"
      val outBase = (Compile / resourceManaged).value / "logger-sources" / "scalascript" / "logging"
      IO.createDirectory(outBase)
      val target = outBase / "Logger.scala"
      IO.copyFile(srcFile, target)
      Seq(target)
    }.taskValue
  )

lazy val yaml = project
  .in(file("lang/yaml"))
  .settings(
    name := "scalascript-yaml",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val core = project
  .in(file("lang/core"))
  .dependsOn(backendSpi, backendSqlRuntime, logger, yaml, markupCore)
  .settings(
    name := "scalascript-core",
    libraryDependencies ++= Seq(
      "com.lihaoyi"    %% "os-lib"           % "0.11.4",
      "com.lihaoyi"    %% "upickle"          % upickleV,
      "org.scalameta"  %% "scalameta"        % "4.17.0",
      "org.commonmark" %  "commonmark"       % "0.28.0",
      "io.bullet"      %% "borer-core"       % "1.16.2",
      "io.bullet"      %% "borer-derivation" % "1.16.2",
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
  .in(file("lang/interop"))
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

// ── Publish shortcuts ─────────────────────────────────────────────────────────
// Publish shortcuts — replaces the old bundle aggregate projects
addCommandAlias("publishCore",        ";ir/publishLocal;backendSpi/publishLocal;pluginApi/publishLocal;core/publishLocal")
addCommandAlias("publishInterpreter", ";ir/publishLocal;backendSpi/publishLocal;core/publishLocal;backendInterpreter/publishLocal;backendInterpreterServer/publishLocal")

// Phase 1a of the runtime-consolidation refactor (see
// PLAN-runtime-consolidation.md): pure protocol primitives extracted out
// of backend-interpreter so they can later be reused by the JVM codegen
// runtime instead of being duplicated as a string template inside
// JvmGen.serveRuntime.  No interpreter coupling — all definitions are
// self-contained Scala classes / objects.
lazy val runtimeServerCommon = project
  .in(file("runtime/http-server/common"))
  .dependsOn(logger)
  .settings(
    name := "scalascript-runtime-server-common",
    libraryDependencies ++= Seq(
      scalatestTest
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    // Phase 1b: copy our own .scala sources into the classpath under
    // `http-server-common-sources/scalascript/server/*.scala` so the JVM
    // codegen backend can read them at codegen time and inline them into
    // generated scala-cli scripts (replacing the duplicated copies that
    // previously lived inside JvmGen.serveRuntime as a string template).
    Compile / resourceGenerators += Def.task {
      val srcDir  = (Compile / scalaSource).value / "scalascript" / "server"
      val outBase = (Compile / resourceManaged).value / "http-server-common-sources" / "scalascript" / "server"
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
      "com.lihaoyi" %% "upickle" % upickleV,
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
  .in(file("runtime/http-server/spi"))
  .dependsOn(runtimeServerCommon)
  .settings(
    name := "scalascript-runtime-server-spi",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    // v1.17.6 / Phase S1c — copy SPI sources into the classpath under
    // `http-server-spi-sources/scalascript/server/spi/` so JvmGen can
    // inline them into generated scala-cli scripts.  Mirrors the
    // `runtimeServerCommon` / `runtimeServerJvm` resource-bundle pattern.
    // The inlined SPI traits let the codegen-emitted `serve(port, tls)`
    // route through `HttpServerBackends.current().start(...)` like the
    // interpreter does.
    Compile / resourceGenerators += Def.task {
      val srcDir  = (Compile / scalaSource).value / "scalascript" / "server" / "spi"
      val outBase = (Compile / resourceManaged).value / "http-server-spi-sources" / "scalascript" / "server" / "spi"
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
// `http-server-jvm-sources/...` so JvmGen.scala can read them
// at codegen time and inline them into generated scala-cli scripts.
//
// Depends on `runtimeServerCommon` so the JVM-specific code can
// import the POJO HTTP model + RequestBuilder / ResponseWriter /
// HttpDispatchLoop / WsFraming / WsFrameDispatch / … helpers that
// already live there.  Now also dependsOn(runtimeServerSpi) so the
// JdkServerBackend can implement HttpServerSpi.
lazy val runtimeServerJvm = project
  .in(file("runtime/http-server/jvm"))
  .dependsOn(runtimeServerCommon, runtimeServerSpi)
  .settings(
    name := "scalascript-runtime-server-jvm",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    Compile / resourceGenerators += Def.task {
      val srcDir  = (Compile / scalaSource).value / "scalascript" / "server" / "jvm"
      val outBase = (Compile / resourceManaged).value / "http-server-jvm-sources" / "scalascript" / "server" / "jvm"
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
  .in(file("runtime/http-server/jvm-jetty"))
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
  .in(file("runtime/http-server/jvm-netty"))
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
  .dependsOn(core)
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

// P3 — Electron desktop renderer.  Wraps the custom (StaticJs) emitter output
// in an Electron project bundle: main.js + preload.js + package.json +
// index.html + app.js.  CLI: ssc run --frontend electron  /  ssc build --target desktop.
lazy val frontendElectron = project
  .in(file("frontend/electron"))
  .dependsOn(frontendCore, frontendCustom, backendJs)
  .settings(
    name := "scalascript-frontend-electron",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.45 / Phase 1 — Swing JVM desktop frontend skeleton.  JDK-only
// native frontend target: no npm, Electron, Node.js, browser host, or HTTP
// socket requirement for future monolithic JVM apps.
lazy val frontendSwing = project
  .in(file("frontend/swing"))
  .dependsOn(frontendCore)
  .settings(
    name := "scalascript-frontend-swing",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val frontendJavaFx = project
  .in(file("frontend/javafx"))
  .dependsOn(frontendCore)
  .settings(
    name := "scalascript-frontend-javafx",
    libraryDependencies ++= {
      val fxArts = Seq("javafx-controls", "javafx-base", "javafx-graphics")
      fxArts.flatMap { art =>
        Seq(
          "org.openjfx" % art % javafxVersion % Provided classifier javafxClassifier,
          "org.openjfx" % art % javafxVersion % Test     classifier javafxClassifier
        )
      } ++ Seq(scalatestTest)
    },
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.48 — Swift/SwiftUI native frontend backend (P5 of native platform roadmap).
// Targets Platform.Mobile(iOS) and Platform.Desktop(macOS).
// Emits a Swift Package (Package.swift + ContentView.swift + App entry).
lazy val frontendSwiftUI = project
  .in(file("frontend/swiftui"))
  .dependsOn(frontendCore)
  .settings(
    name := "scalascript-frontend-swiftui",
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
  .in(file("runtime/backend/jvm"))
  .dependsOn(backendSpi, core, runtimeServerCommon, runtimeServerSpi, runtimeServerJvm, backendSqlRuntime, backendSqlRuntimeJs, backendTypedDataRuntime)
  .settings(
    name := "scalascript-backend-jvm",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendJs = project
  .in(file("runtime/backend/js"))
  .dependsOn(backendSpi, core, backendSqlRuntimeJs, backendTypedDataRuntime)
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
  .in(file("runtime/backend/node"))
  .dependsOn(backendSpi, core, backendJs)
  .settings(
    name := "scalascript-backend-node",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendScalajs = project
  .in(file("runtime/backend/scalajs"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-scalajs",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendWasm = project
  .in(file("runtime/backend/wasm"))
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
  .in(file("runtime/backend/scala-source"))
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
  .in(file("runtime/backend/html"))
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
  .in(file("runtime/backend/css"))
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
  .in(file("runtime/backend/interpreter"))
  .dependsOn(wireCore, backendSpi, markupCore, core, runtimeServerCommon, runtimeServerJvm, mcpCommon, backendJs, backendSqlRuntime, backendConfigRuntime, frontendCore, backendJvm % Test, backendGraphRuntime % Test, frontendCustom % Test, frontendReact % Test, frontendSolid % Test, frontendVue % Test)
  .settings(
    name := "scalascript-backend-interpreter",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    // JvmGen scala-cli runtime smoke tests read these resources to find
    // locally-built internal runtime JARs and avoid resolving unpublished
    // io.scalascript artifacts from Maven Central.
    Test / resourceGenerators += Def.task {
      val sqlJar       = (backendSqlRuntime / Compile / packageBin).value
      val typedDataJar = (backendTypedDataRuntime / Compile / packageBin).value
      val graphJar     = (backendGraphRuntime / Compile / packageBin).value
      val outDir       = (Test / resourceManaged).value / "scalascript"
      val sqlOut       = outDir / "sql-runtime-jar.path"
      val typedOut     = outDir / "typed-data-runtime-jar.path"
      val graphOut     = outDir / "graph-runtime-jar.path"
      IO.createDirectory(outDir)
      IO.write(sqlOut, sqlJar.getAbsolutePath)
      IO.write(typedOut, typedDataJar.getAbsolutePath)
      IO.write(graphOut, graphJar.getAbsolutePath)
      Seq(sqlOut, typedOut, graphOut)
    }.taskValue
  )

// JMH microbenchmarks for the interpreter.
// Run: sbt "interpreterBench/Jmh/run"
// Save JSON: sbt "interpreterBench/Jmh/run -rff bench/jmh-results.json -rf json"
lazy val interpreterBench = project
  .in(file("runtime/backend/interpreter-bench"))
  .dependsOn(backendInterpreter, backendJvm, backendJs)
  .enablePlugins(JmhPlugin)
  .settings(
    name := "scalascript-interpreter-bench",
    Jmh / scalacOptions ++= sharedScalacOptions
  )

// JMH microbenchmarks for the compiler hot paths (parser + typer + unifier).
// Run: sbt "compilerBench/Jmh/run"
// Run with GC profiler: sbt "compilerBench/Jmh/run -prof gc -wi 3 -i 5"
lazy val compilerBench = project
  .in(file("lang/core-bench"))
  .dependsOn(core)
  .enablePlugins(JmhPlugin)
  .settings(
    name := "scalascript-compiler-bench",
    Jmh / scalacOptions ++= sharedScalacOptions
  )

// Interpreter-owned HTTP/WS server runtime.  The route registries
// (`Routes` / `WsRoutes`) remain in backend-interpreter for now because
// plugins and cluster helpers still register handlers directly, but socket
// startup, fallback page rendering, WS sessions, and in-process transport now
// compile as a separate module behind InterpreterServerSupport.
lazy val backendInterpreterServer = project
  .in(file("runtime/backend/interpreter-server"))
  .dependsOn(backendInterpreter, httpPlugin % Test, wsPlugin % Test, frontendPlugin % Test, sqlPlugin % Test, frontendReact % Test, fetchPlugin % Test)
  .settings(
    name := "scalascript-backend-interpreter-server",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val testUtils = project
  .in(file("runtime/backend/test-utils"))
  .dependsOn(backendSpi, backendInterpreter)
  .settings(
    name := "scalascript-test-utils",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// DAP debugger backend — Phase 1 TCP skeleton (docs/dap-debugger.md).
// Provides Content-Length framing, DapServer TCP accept loop, and DapSession
// lifecycle handler (initialize / launch / configurationDone / disconnect).
// cli depends on this % Test only so the DAP classes are on cli's test CP;
// DebugCommand links against it at compile time via the normal dependency.
lazy val backendDap = project
  .in(file("runtime/backend/dap"))
  .dependsOn(backendInterpreter, ir)
  .settings(
    name := "scalascript-backend-dap",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// Apache Spark backend — Phase 1 (local SparkSession).
// SparkGen is a pure code-emitter (generates Scala 3 source strings) — it
// does not import any Spark classes itself.  Spark JARs are therefore NOT
// needed on the sbt compile classpath; they are resolved at runtime via
// `scala-cli --dep` flags when the generated program is executed.
lazy val backendSpark = project
  .in(file("runtime/backend/spark"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-spark",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendKafkaStreams = project
  .in(file("runtime/backend/kafka-streams"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-kafka-streams",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendFlink = project
  .in(file("runtime/backend/flink"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-flink",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendConformance = project
  .in(file("runtime/backend/conformance"))
  .dependsOn(backendSpark, backendKafkaStreams, backendFlink)
  .settings(
    name := "scalascript-backend-conformance",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// ── compiler/driver — scala3-compiler isolated from startup classpath ────────
// Does NOT appear in cli's .dependsOn so dotty is never on the startup CP.
// cli/stage copies this module's JAR to lib/compiler/jars/ and CompilerLoader
// picks it up via URLClassLoader + ServiceLoader on first compile command.
lazy val compilerDriver = project
  .in(file("lang/compiler/driver"))
  .dependsOn(core)
  .settings(
    name := "scalascript-compiler-driver",
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
      scalatestTest
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// ── ProGuard shrink task (run: sbt cli/shrinkJar) ──────────────────────────
// sbt-proguard is used ONLY for config generation + ProGuard JAR resolution;
// we bypass its runner (hardcoded -Xmx256M) and fork java with -Xmx1G.
val shrinkJar     = taskKey[File]("Shrink the assembled ssc.jar with ProGuard 7.5 (1 G heap)")
val installBin           = taskKey[Unit]("Stage lib/ssc.jar + lib/jars/ + lib/compiler/ for classpath-based launch")
val packagePlugin        = taskKey[File]("Package this plugin as a .sscpkg ZIP archive (manifest.yaml + intrinsics/<name>.jar)")
val checkPluginBoundary  = taskKey[Unit]("CI: assert no interpreter JAR is on the plugin Compile classpath")

def sscpkgSettings(pluginId: String): Seq[Def.Setting[?]] = Seq(
  checkPluginBoundary := {
    val cp  = (Compile / fullClasspath).value
    val bad = cp.files.filter { f =>
      val n = f.getName
      n.contains("scalascript-backend-interpreter") && !n.contains("spi")
    }
    if (bad.nonEmpty)
      throw new RuntimeException(
        s"Plugin boundary violation: interpreter JAR on Compile classpath: ${bad.map(_.getName).mkString(", ")}" +
        " — plugin subprojects must not depend on scalascript-backend-interpreter directly."
      )
  },
  packagePlugin := {
    val jar       = (Compile / packageBin).value
    val pkgName   = name.value.stripPrefix("scalascript-")
    val outFile   = target.value / s"$pkgName.sscpkg"
    val manifest  = s"id: $pluginId\nversion: ${version.value}\nkind:\n  - plugin\n"
    val manifestF = target.value / "sscpkg-manifest.yaml"
    IO.write(manifestF, manifest)
    IO.zip(Seq(manifestF -> "manifest.yaml", jar -> s"intrinsics/$pkgName.jar"), outFile, None)
    outFile
  }
)

lazy val cli = project
  .in(file("tools/cli"))
  .enablePlugins(SbtProguard, GraalVMNativeImagePlugin)
  .dependsOn(core, interop, backendJvm, backendJs, backendNode, backendScalajs, backendWasm, backendInterpreter, backendInterpreterServer, backendScalaSource, backendHtml, backendCss, backendSpark, backendKafkaStreams, backendFlink, backendDap, frontendCore, frontendCustom, frontendReact, frontendSolid, frontendVue, frontendElectron, frontendSwing, frontendJavaFx, frontendSwiftUI, graphPlugin, deployPlugin, httpPlugin, wsPlugin, frontendPlugin, fetchPlugin)
  .settings(
    name := "scalascript-cli",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "pprint" % "0.9.6",
      // SLF4J simple binding — silences the "Failed to load StaticLoggerBinder"
      // warning printed by the SLF4J 1.7.x API that arrives transitively (e.g.
      // via commonmark).  Logs to stderr at WARN+ by default; tunable via
      // simplelogger.properties on the classpath or system properties at runtime
      // (e.g. -Dorg.slf4j.simpleLogger.defaultLogLevel=debug).
      "org.slf4j" % "slf4j-simple" % "2.0.18",
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
    // ── stage: thin JAR + split runtime/compiler deps ────────────────────
    // Produces:
    //   $ROOT/lib/ssc.jar              ← cli entry-point (no dotty)
    //   $ROOT/lib/jars/*.jar           ← runtime deps (os-lib, scalameta, …)
    //   $ROOT/lib/compiler/jars/*.jar  ← scala3-compiler, asm, compiler-driver
    //   $ROOT/lib/compiler/plugins/    ← .sscpkg files (auto-loaded at startup)
    // Launcher: java -cp "$ROOT/lib/jars/*:$ROOT/lib/ssc.jar" scalascript.cli.ssc
    // (lib/compiler/jars/ is NOT on startup CP — loaded lazily by CompilerLoader)
    installBin := {
      val log          = streams.value.log
      val root         = (ThisBuild / baseDirectory).value
      val libDir       = root / "bin" / "lib"
      val runtimeDir   = libDir / "jars"
      val compilerDir  = libDir / "compiler" / "jars"
      val plugDir      = libDir / "compiler" / "plugins"
      IO.delete(runtimeDir);  IO.createDirectory(runtimeDir)
      IO.delete(compilerDir); IO.createDirectory(compilerDir)
      IO.delete(plugDir);     IO.createDirectory(plugDir)
      // Thin cli entry-point JAR (no scala3-compiler dep).
      val appJar = (Compile / packageBin).value
      IO.copyFile(appJar, libDir / "ssc.jar")
      log.info(s"bin/lib/ssc.jar  (${appJar.length / 1024} KB)")
      // compiler-driver JAR → lib/compiler/jars/
      val driverJar = (compilerDriver / Compile / packageBin).value
      IO.copyFile(driverJar, compilerDir / driverJar.getName)
      // Compiler-only JARs: the dotty compiler itself and compile-only tooling.
      // scala3-library and scala-library are runtime deps too — keep in lib/jars/.
      val compilerCp = (compilerDriver / Compile / fullClasspath).value.files
      val compilerOnlyPrefixes = Set("scala3-compiler", "scala3-interfaces", "tasty-core",
                                     "scala-asm", "compiler-interface", "zinc-")
      val isCompilerJar = (f: java.io.File) =>
        f.isFile && f.getName.endsWith(".jar") &&
        compilerOnlyPrefixes.exists(n => f.getName.startsWith(n)) &&
        f.getAbsolutePath != appJar.getAbsolutePath &&
        f.getAbsolutePath != driverJar.getAbsolutePath
      val compilerJars = compilerCp.filter(isCompilerJar)
      compilerJars.foreach(j => IO.copyFile(j, compilerDir / j.getName))
      log.info(s"bin/lib/compiler/jars/  (${compilerJars.size + 1} JARs incl. compiler-driver)")
      // Runtime JARs: cli fullClasspath minus compiler JARs, app JAR, and plugin JARs.
      // Plugin JARs are loaded at runtime via .sscpkg archives, not the startup CP.
      val runtimeCp = (Compile / fullClasspath).value.files
      val compilerAbsPaths = (compilerJars :+ driverJar).map(_.getAbsolutePath).toSet
      val pluginJarPrefixes = allPlugins.map(_.jarPrefix).toSet
      // The CLI deploy subcommand directly imports deploy SPI/runtime types
      // (DeployManifest, DeployGroup, DeployError, ...). Most std plugins are
      // loaded lazily from .sscpkg archives, but deploy must also be present on
      // the startup classpath for the thin launcher to reach `ssc --help`.
      val startupPluginJars = Set((deployPlugin / Compile / packageBin).value.getAbsolutePath)
      val isPluginJar = (f: java.io.File) => pluginJarPrefixes.exists(f.getName.startsWith)
      val runtimeJars = runtimeCp.filter { f =>
        f.isFile && f.getName.endsWith(".jar") &&
        f.getAbsolutePath != appJar.getAbsolutePath &&
        !compilerAbsPaths.contains(f.getAbsolutePath) &&
        !isCompilerJar(f) &&
        (!isPluginJar(f) || startupPluginJars.contains(f.getAbsolutePath))
      }
      runtimeJars.foreach(j => IO.copyFile(j, runtimeDir / j.getName))
      log.info(s"bin/lib/jars/           (${runtimeJars.size} JARs)")
      // Package and install standard-library plugins as .sscpkg archives.
      // NOTE: sbt task-macro prevents dynamic .value in a loop, so this list
      // is explicit.  It must stay in sync with allPlugins (arch-build-registry-p1).
      val pluginPkgs = Seq(
        (jsonPlugin            / packagePlugin).value,
        (frontendPlugin        / packagePlugin).value,
        (requestPlugin         / packagePlugin).value,
        (authPlugin            / packagePlugin).value,
        (oauthPlugin           / packagePlugin).value,
        (fetchPlugin           / packagePlugin).value,
        (graphPlugin           / packagePlugin).value,
        (sqlPlugin             / packagePlugin).value,
        (httpPlugin            / packagePlugin).value,
        (wsPlugin              / packagePlugin).value,
        (mcpPlugin             / packagePlugin).value,
        (remotePlugin          / packagePlugin).value,
        (swingPlugin           / packagePlugin).value,
        (pwaPlugin             / packagePlugin).value,
        (streamsPlugin         / packagePlugin).value,
        (dstreamsPlugin        / packagePlugin).value,
        (graphqlPlugin         / packagePlugin).value,
        (deployPlugin          / packagePlugin).value,
        (paymentRequestPlugin  / packagePlugin).value,
        (paymentsPlugin        / packagePlugin).value,
      )
      pluginPkgs.foreach(pkg => IO.copyFile(pkg, plugDir / pkg.getName))
      log.info(s"bin/lib/compiler/plugins/  (${pluginPkgs.size} .sscpkg files)")
    },
    // ── GraalVM native-image (v1.50-native-p2) ───────────────────────────
    // Build: sbt cli/graalvm-native-image:packageBin
    // Requires GraalVM 21+ on PATH.  See native-image-configs/ for reflection
    // and resource configuration.  Regenerate with:
    //   java -agentlib:native-image-agent=config-output-dir=native-image-configs \
    //     -jar ssc.jar run examples/hello.ssc [other CLI paths …]
    GraalVMNativeImage / mainClass  := Some("scalascript.cli.ssc"),
    graalVMNativeImageOptions ++= Seq(
      "--no-fallback",
      "--initialize-at-build-time=scala",
      "--initialize-at-build-time=scalascript",
      "-H:ReflectionConfigurationFiles=" + (baseDirectory.value / ".." / ".." / "native-image-configs" / "reflect-config.json").getAbsolutePath,
      "-H:ResourceConfigurationFiles="   + (baseDirectory.value / ".." / ".." / "native-image-configs" / "resource-config.json").getAbsolutePath,
      // Enable service loader support — reads META-INF/services/* at build time
      "--features=org.graalvm.home.HomeFinder",
      "-H:+ReportExceptionStackTraces",
      // Increase image heap for Scala + scalameta class loading at build time
      "-J-Xmx8g"
    )
  )

// v1.50-native-p3 — minimal JVM-only subprocess host for native `ssc`.
//
// Ships alongside the native `ssc` binary as `lib/ssc-plugin-host.jar`.
// When native `ssc` receives `--plugin foo.jar`, it spawns:
//   java -cp foo.jar:lib/ssc-plugin-host.jar scalascript.plugin.SubprocessHost foo.jar
// The host loads the plugin JAR via URLClassLoader (works fine in JVM),
// discovers Backend via ServiceLoader, and enters the stdio-json wire
// protocol loop.  Plugin authors change nothing.
//
// Build: sbt pluginHost/assembly
// Output: tools/plugin-host/target/scala-3.8.3/ssc-plugin-host.jar
lazy val pluginHost = project
  .in(file("tools/plugin-host"))
  .dependsOn(core)
  .settings(
    name                           := "ssc-plugin-host",
    assembly / mainClass           := Some("scalascript.plugin.SubprocessHost"),
    assembly / assemblyJarName     := "ssc-plugin-host.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _ @ _*)         => MergeStrategy.discard
      case _                                    => MergeStrategy.first
    },
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// NOTE: `bench/` exists today as a scala-cli script directory (fib/sum/
// list-ops workload comparisons across backends) — not an sbt project.
// `WsStress` lives under backend-interpreter/.../bench/ since it
// stresses the interpreter's WS runtime.

lazy val clientPostgres = project
  .in(file("backend/postgres"))
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

// v1.28 — Config system runtime.  Spec: docs/config-system.md.
//
// Standalone module — no dependency on ir/spi/core — so the CLI,
// backendInterpreter, and all backend codegen modules can depend on it
// without pulling in the full compiler frontend.
//
// Responsibilities:
//   ConfigValue   — unified value ADT (Str/Num/Bool/Null/Lst/Map)
//   ConfigParser  — YAML + JSON → ConfigValue (SimpleYaml)
//   SubstitutionEngine — ${scheme:ref}, ${env:VAR | default}, ${?VAR}
//   MergeEngine   — priority-based multi-source merge
//   ConfigLoader  — ties all of the above together
lazy val backendConfigRuntime = project
  .in(file("backend/config"))
  .dependsOn(yaml, markupCore)
  .settings(
    name := "scalascript-backend-config-runtime",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// v1.26 — JDBC execution runtime consumed by interpreter + JvmGen.
// Canonical database URL scheme registry.  Pure Scala, no external deps.
// Maps user-facing prefixes (sqlite:, duckdb:, postgres:, …) to their
// Self-contained (no `ir` / `backend-spi` / `core` deps): takes a
// `java.sql.Connection`, a `?`-templated SQL string, and an ordered
// bind list, returns a `Row`-based result.  Bundles H2 + SQLite so
// the standard examples / quickstarts work with zero configuration.
// DbUrl (canonical DB URL scheme mapping) lives here.
lazy val backendSqlRuntime = project
  .in(file("backend/sql"))
  .dependsOn(backendConfigRuntime, backendTypedDataRuntime)
  .settings(
    name := "scalascript-backend-sql-runtime",
    libraryDependencies ++= Seq(
      "com.lihaoyi"       %% "ujson"          % upickleV,
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
lazy val backendSqlRuntimeJs = project
  .in(file("backend/sql-js"))
  .dependsOn(backendSqlRuntime)
  .settings(
    name := "scalascript-backend-sql-runtime-js",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Cloud-provider secret resolver plugins (docs/secret-resolvers.md §aws-secret §gcp-secret §azure-kv).
// Each is a separate sbt sub-project that registers via ServiceLoader.
// Kept as optional thin adapters so the main `backendSqlRuntime` stays
// free of heavy cloud-SDK deps.

lazy val sqlAws = project
  .in(file("backend/sql-aws"))
  .dependsOn(backendSqlRuntime)
  .settings(
    name := "scalascript-sql-aws",
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "secretsmanager" % "2.26.31",
      "com.lihaoyi"           %% "ujson"           % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val sqlGcp = project
  .in(file("backend/sql-gcp"))
  .dependsOn(backendSqlRuntime)
  .settings(
    name := "scalascript-sql-gcp",
    libraryDependencies ++= Seq(
      "com.google.cloud" % "google-cloud-secretmanager" % "2.46.0",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val sqlAzure = project
  .in(file("backend/sql-azure"))
  .dependsOn(backendSqlRuntime)
  .settings(
    name := "scalascript-sql-azure",
    libraryDependencies ++= Seq(
      "com.azure" % "azure-security-keyvault-secrets" % "4.8.7",
      "com.azure" % "azure-identity"                  % "1.13.3",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Shared typed-data mapping runtime foundation.
// Phase 4 starts with emitted JSON codec facade snippets used by typed
// route clients; later phases grow explicit/derived user codecs here.
lazy val backendTypedDataRuntime = project
  .in(file("backend/typed-data"))
  .dependsOn(wireCore)
  .settings(
    name := "scalascript-backend-typed-data-runtime",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Shared graph runtime bootstrap: portable SPI + in-memory JVM backend.
// TinkerGraph/RDF4J adapters layer on top of this contract in later slices.
lazy val backendGraphRuntime = project
  .in(file("backend/graph"))
  .dependsOn(backendTypedDataRuntime)
  .settings(
    name := "scalascript-backend-graph-runtime",
    libraryDependencies ++= Seq(
      "org.apache.tinkerpop" % "tinkergraph-gremlin"    % "3.8.1",
      "org.apache.tinkerpop" % "gremlin-driver"         % "3.8.1",
      "org.eclipse.rdf4j"    % "rdf4j-repository-sail"  % "5.3.1",
      "org.eclipse.rdf4j"    % "rdf4j-repository-http"  % "5.3.1",
      "org.eclipse.rdf4j"    % "rdf4j-sail-memory"      % "5.3.1",
      "org.neo4j.driver"     % "neo4j-java-driver"      % "5.28.5",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val clientRedis = project
  .in(file("backend/redis"))
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
  .in(file("payments/client/evm"))
  .settings(
    name := "scalascript-client-evm",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"  % "4.0.23",
      "com.softwaremill.sttp.client4" %% "upickle" % "4.0.23",
      "com.lihaoyi"                   %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val clientKafka = project
  .in(file("backend/kafka"))
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
  .in(file("payments/client/coinbase"))
  .settings(
    name := "scalascript-client-coinbase",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"  % "4.0.23",
      "com.lihaoyi"                   %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402Core = project
  .in(file("payments/x402/core"))
  .settings(
    name := "scalascript-x402-core",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402Server = project
  .in(file("payments/x402/server"))
  .dependsOn(x402Core, runtimeServerCommon)
  .settings(
    name := "scalascript-x402-server",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402Client = project
  .in(file("payments/x402/client"))
  .dependsOn(x402Core, walletStrategyEoa, blockchainEvm, blockchainCardano, cryptoBouncycastle)
  .settings(
    name := "scalascript-x402-client",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402ClientJs = project
  .in(file("payments/x402/client-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "scalascript-x402-client-js",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % upickleV,
      "org.scalatest" %%% "scalatest" % scalatestV % Test,
    ),
    scalaJSLinkerConfig ~= { _.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule) },
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    Test / fork := false,
  )

lazy val x402FacilitatorCoinbase = project
  .in(file("payments/x402/facilitator-coinbase"))
  .dependsOn(x402Core, clientCoinbase)
  .settings(
    name := "scalascript-x402-facilitator-coinbase",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402FacilitatorEvm = project
  .in(file("payments/x402/facilitator-evm"))
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
  .in(file("payments/x402/queue-kafka"))
  .dependsOn(x402Core, clientKafka)
  .settings(
    name := "scalascript-x402-queue-kafka",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402QueuePostgres = project
  .in(file("payments/x402/queue-postgres"))
  .dependsOn(x402Core, clientPostgres)
  .settings(
    name := "scalascript-x402-queue-postgres",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402NoncePostgres = project
  .in(file("payments/x402/nonce-postgres"))
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
  .in(file("payments/x402/nonce-redis"))
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
  .in(file("payments/client/blockfrost"))
  .settings(
    name := "scalascript-client-blockfrost",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"    % "4.0.23",
      "com.lihaoyi"                   %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402FacilitatorCardano = project
  .in(file("payments/x402/facilitator-cardano"))
  .dependsOn(x402Core, clientBlockfrost, blockchainCardano)
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
  .in(file("payments/x402/facilitator-cardano-scalus"))
  .dependsOn(x402Core, x402FacilitatorCardano, blockchainCardano, x402Client % Test)
  .settings(
    name := "scalascript-x402-facilitator-cardano-scalus",
    libraryDependencies ++= Seq(
      "com.bloxbean.cardano" % "cardano-client-lib" % "0.8.0-preview1",
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
  .in(file("payments/x402/escrow-plutus"))
  .settings(
    name := "scalascript-x402-escrow-plutus",
    scalaVersion := "3.3.7",
    libraryDependencies ++= Seq(
      "org.scalus" %% "scalus" % "0.15.1",
      scalatestTest,
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
        "payments/x402/facilitator-cardano-scalus/src/main/resources/x402-escrow.plutus.hex")
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
    .in(file("payments/crypto/spi"))
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
  .in(file("payments/crypto/bouncycastle"))
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
  .in(file("payments/crypto/noble-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(cryptoSpiJs)
  .settings(
    name                := "scalascript-crypto-noble-js",
    scalaJSLinkerConfig ~= { _.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule) },
    libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestV % Test,
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
    .in(file("payments/blockchain/spi"))
    .dependsOn(cryptoSpiCross)
    .settings(
      name := "scalascript-blockchain-spi",
      libraryDependencies += "com.lihaoyi" %%% "upickle" % upickleV,
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
    .in(file("payments/wallet/spi"))
    .dependsOn(blockchainSpiCross, cryptoSpiCross)
    .settings(
      name := "scalascript-wallet-spi",
      libraryDependencies ++= Seq(
        "com.lihaoyi"   %%% "upickle"   % upickleV,
        "org.scalatest" %%% "scalatest" % scalatestV % Test,
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
// callers and existing tests keep working).
//
// `js/` adds `EncryptedLocalVaultJs` plus IndexedDB / localStorage /
// in-memory `VaultFileStore` implementations.  The JS helper wraps the
// shared core with browser persistence while preserving the same VaultFile
// JSON shape used by the JVM filesystem wrapper.
//
// JS tests depend on `cryptoNobleJs` so `CryptoBackend.get()` resolves
// to the Stage-2 noble backend (Stage 5a added PBKDF2 / Argon2id /
// AES-GCM to it).  Module kind is CommonJS to match crypto-noble-js.
lazy val walletVaultEncryptedCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("payments/wallet/vault-encrypted"))
    .dependsOn(walletSpiCross, cryptoSpiCross)
    .settings(
      name := "scalascript-wallet-vault-encrypted",
      libraryDependencies += "com.lihaoyi"   %%% "upickle"   % upickleV,
      libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestV % Test,
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
    .in(file("payments/wallet/strategy-eoa"))
    .dependsOn(walletSpiCross, blockchainSpiCross, cryptoSpiCross)
    .settings(
      name := "scalascript-wallet-strategy-eoa",
      libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestV % Test,
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
  .in(file("payments/wallet/vault-mpc"))
  .dependsOn(walletSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-vault-mpc",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultTrezor = project
  .in(file("payments/wallet/vault-trezor"))
  .dependsOn(walletSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-vault-trezor",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultMpcFireblocks = project
  .in(file("payments/wallet/wallet-vault-mpc-fireblocks"))
  .dependsOn(walletVaultMpc, walletSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-vault-mpc-fireblocks",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultMpcCoinbase = project
  .in(file("payments/wallet/wallet-vault-mpc-coinbase"))
  .dependsOn(walletVaultMpc, walletSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-vault-mpc-coinbase",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultMpcLit = project
  .in(file("payments/wallet/wallet-vault-mpc-lit"))
  .dependsOn(walletVaultMpc, walletSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-vault-mpc-lit",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultMpcZengo = project
  .in(file("payments/wallet/wallet-vault-mpc-zengo"))
  .dependsOn(walletVaultMpc, walletSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-vault-mpc-zengo",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
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
    .in(file("payments/wallet/strategy-erc4337"))
    .dependsOn(walletSpiCross, blockchainSpiCross, cryptoSpiCross, blockchainEvmAbiCross)
    .settings(
      name := "scalascript-wallet-strategy-erc4337",
      libraryDependencies += "com.lihaoyi"   %%% "upickle"   % upickleV,
      libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestV % Test,
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
    .in(file("payments/wallet/connector-eip1193"))
    .dependsOn(walletSpiCross, blockchainSpiCross)
    .settings(
      name := "scalascript-wallet-connector-eip1193",
      libraryDependencies += "com.lihaoyi"   %%% "upickle"   % upickleV,
      libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestV % Test,
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
    .in(file("payments/wallet/connect"))
    .dependsOn(walletSpiCross, blockchainSpiCross, cryptoSpiCross)
    .settings(
      name := "scalascript-wallet-connect",
      libraryDependencies += "com.lihaoyi"   %%% "upickle"   % upickleV,
      libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestV % Test,
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
  .in(file("payments/wallet/vault-ledger"))
  .dependsOn(walletSpi, cryptoSpi, blockchainSpi)
  .settings(
    name := "scalascript-wallet-vault-ledger",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultLedgerJvm = project
  .in(file("payments/wallet/vault-ledger-jvm"))
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

// Scala.js-only WebHID transport for Ledger hardware wallets in the browser.
// Implements the same LedgerTransport SPI as Hid4JavaTransport (JVM) but uses
// navigator.hid.requestDevice / navigator.hid.getDevices (WebHID API, Chrome ≥ 89).
// Includes: HidTransport (WebHID), HidFraming (64-byte APDU packet framing),
// LedgerApdu (command/response helpers), EthApp (secp256k1 + EIP-712),
// CardanoApp (Ed25519 + CIP-8 CBOR framing), LedgerVault (Vault SPI).
// Shared vault-ledger and vault-ledger-ethereum sources are compiled inline via
// unmanagedSourceDirectories so the JS module bundles all required types.
// 12 tests via MockTransport; no real Ledger device required.
lazy val walletVaultLedgerJs = project
  .in(file("payments/wallet/vault-ledger-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(walletSpiJs, cryptoSpiJs, blockchainSpiJs)
  .settings(
    name := "scalascript-wallet-vault-ledger-js",
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "2.8.0",
      "org.scalatest" %%% "scalatest" % scalatestV % Test,
    ),
    // Inline the shared (JVM-source-compatible) vault-ledger and
    // vault-ledger-ethereum sources so they compile under Scala.js.
    // Those sources only use JDK classes that Scala.js emulates
    // (java.util.Arrays.copyOfRange, java.nio.charset.StandardCharsets).
    Compile / unmanagedSourceDirectories ++= Seq(
      baseDirectory.value / ".." / "vault-ledger" / "src" / "main" / "scala",
      baseDirectory.value / ".." / "vault-ledger-ethereum" / "src" / "main" / "scala",
    ),
    scalaJSLinkerConfig ~= { _.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule) },
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    Test / fork := false,
  )

lazy val walletVaultLedgerBluetoothJs = project
  .in(file("payments/wallet/vault-ledger-bluetooth-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(walletSpiJs, cryptoSpiJs, blockchainSpiJs)
  .settings(
    name := "scalascript-wallet-vault-ledger-bluetooth-js",
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "2.8.0",
      "org.scalatest" %%% "scalatest" % scalatestV % Test,
    ),
    Compile / unmanagedSourceDirectories +=
      baseDirectory.value / ".." / "vault-ledger" / "src" / "main" / "scala",
    scalaJSLinkerConfig ~= { _.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule) },
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    Test / fork := false,
  )

lazy val walletVaultLedgerEthereum = project
  .in(file("payments/wallet/vault-ledger-ethereum"))
  .dependsOn(walletVaultLedger, walletVaultLedger % "test->test", walletSpi, cryptoSpi, blockchainEvm, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-wallet-vault-ledger-ethereum",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultLedgerSolana = project
  .in(file("payments/wallet/vault-ledger-solana"))
  .dependsOn(walletVaultLedger, walletVaultLedger % "test->test", walletSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-vault-ledger-solana",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultLedgerBitcoin = project
  .in(file("payments/wallet/vault-ledger-bitcoin"))
  .dependsOn(walletVaultLedger, walletVaultLedger % "test->test", walletSpi, blockchainBitcoin % "compile->compile;test->test")
  .settings(
    name := "scalascript-wallet-vault-ledger-bitcoin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultLedgerCardano = project
  .in(file("payments/wallet/vault-ledger-cardano"))
  .dependsOn(walletVaultLedger, walletVaultLedger % "test->test", walletSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-vault-ledger-cardano",
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
    .in(file("payments/wallet/connector-wallet-std"))
    .dependsOn(walletSpiCross, blockchainSpiCross)
    .settings(
      name := "scalascript-wallet-connector-wallet-std",
      libraryDependencies += "com.lihaoyi"   %%% "upickle"   % upickleV,
      libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestV % Test,
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
      "com.lihaoyi" %% "upickle" % upickleV,
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
      "com.lihaoyi" %% "upickle" % upickleV,
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
    .in(file("payments/blockchain/evm-abi"))
    .dependsOn(cryptoSpiCross)
    .settings(
      name := "scalascript-blockchain-evm-abi",
      libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestV % Test,
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
  .in(file("payments/blockchain/evm"))
  .dependsOn(blockchainSpi, cryptoSpi, blockchainEvmAbi, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-blockchain-evm",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val blockchainSolana = project
  .in(file("payments/blockchain/solana"))
  .dependsOn(blockchainSpi, cryptoSpi, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-blockchain-solana",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val blockchainCardano = project
  .in(file("payments/blockchain/cardano"))
  .dependsOn(blockchainSpi, cryptoSpi, cryptoBouncycastle, clientBlockfrost)
  .settings(
    name := "scalascript-blockchain-cardano",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val blockchainBitcoin = project
  .in(file("payments/blockchain/bitcoin"))
  .dependsOn(blockchainSpi, cryptoSpi, cryptoBouncycastle)
  .settings(
    name := "scalascript-blockchain-bitcoin",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val blockchainCosmos = project
  .in(file("payments/blockchain/cosmos"))
  .dependsOn(blockchainSpi, cryptoSpi, cryptoBouncycastle)
  .settings(
    name := "scalascript-blockchain-cosmos",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// Micropayment platform SPI (docs/micropayment-spi.md)
// ---------------------------------------------------------------------------

lazy val micropaymentSpi = project
  .in(file("payments/micropayment/spi"))
  .dependsOn(blockchainSpi, walletSpi)
  .settings(
    name := "scalascript-micropayment-spi",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentThreshold = project
  .in(file("payments/micropayment/threshold"))
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
  .in(file("payments/micropayment/server"))
  .dependsOn(micropaymentSpi, runtimeServerCommon)
  .settings(
    name := "scalascript-micropayment-server",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentClient = project
  .in(file("payments/micropayment/client"))
  .dependsOn(micropaymentSpi)
  .settings(
    name := "scalascript-micropayment-client",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentProbabilistic = project
  .in(file("payments/micropayment/probabilistic"))
  .dependsOn(micropaymentSpi, blockchainSpi)
  .settings(
    name := "scalascript-micropayment-probabilistic",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentChannelEvm = project
  .in(file("payments/micropayment/channel-evm"))
  .dependsOn(micropaymentSpi, blockchainSpi, blockchainEvm, walletSpi, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-micropayment-channel-evm",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentHydra = project
  .in(file("payments/micropayment/hydra"))
  .dependsOn(micropaymentSpi, blockchainSpi)
  .settings(
    name := "scalascript-micropayment-hydra",
    libraryDependencies ++= Seq("com.lihaoyi" %% "upickle" % upickleV, scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Phase 1 intrinsic plugins (§ intrinsics-migration.md) ────────────────────
// json, frontend, and request families extracted from the bundled interpreter
// into ServiceLoader plugins.  Legacy plugin-backed interpreter tests live in
// `backendInterpreterPluginTests`; per-plugin suites use `testUtils % Test`.

lazy val jsonPlugin = project
  .in(file("runtime/std/json-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, testUtils % Test)
  .settings(
    name := "scalascript-json-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.json"))

lazy val frontendPlugin = project
  .in(file("runtime/std/frontend-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, frontendCore, frontendCustom % Test, frontendReact % Test, frontendSolid % Test, frontendVue % Test, frontendSwing % Test, frontendSwiftUI % Test, testUtils % Test)
  .settings(
    name := "scalascript-frontend-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.frontend"))

lazy val swingPlugin = project
  .in(file("runtime/std/swing-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, testUtils % Test)
  .settings(
    name := "scalascript-swing-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.swing"))

lazy val requestPlugin = project
  .in(file("runtime/std/request-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, testUtils % Test)
  .settings(
    name := "scalascript-request-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.request"))

lazy val authPlugin = project
  .in(file("runtime/std/auth-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, runtimeServerCommon, testUtils % Test)
  .settings(
    name := "scalascript-auth-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.auth"))

lazy val oauthPlugin = project
  .in(file("runtime/std/oauth-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, mcpCommon, runtimeServerCommon, testUtils % Test)
  .settings(
    name := "scalascript-oauth-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.oauth"))

lazy val fetchPlugin = project
  .in(file("runtime/std/fetch-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, frontendCore, frontendPlugin % Test, testUtils % Test)
  .settings(
    name := "scalascript-fetch-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.fetch"))

lazy val graphPlugin = project
  .in(file("runtime/std/graph-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, testUtils % Test)
  .settings(
    name := "scalascript-graph-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.graph"))

lazy val sqlPlugin = project
  .in(file("runtime/std/sql-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, backendSqlRuntime, testUtils % Test)
  .settings(
    name := "scalascript-sql-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.sql"))

lazy val httpPlugin = project
  .in(file("runtime/std/http-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, testUtils % Test)
  .settings(
    name := "scalascript-http-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.http"))

lazy val wsPlugin = project
  .in(file("runtime/std/ws-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, runtimeServerCommon, runtimeServerSpi, testUtils % Test)
  .settings(
    name := "scalascript-ws-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.ws"))

lazy val mcpPlugin = project
  .in(file("runtime/std/mcp-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, mcpCommon, runtimeServerCommon, testUtils % Test)
  .settings(
    name := "scalascript-mcp-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.mcp"))

lazy val remotePlugin = project
  .in(file("runtime/std/remote-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, testUtils % Test)
  .settings(
    name := "scalascript-remote-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.remote"))

lazy val pwaPlugin = project
  .in(file("runtime/std/pwa-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core)
  .settings(
    name := "scalascript-pwa-plugin",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.pwa"))

lazy val backendInterpreterPluginTests = project
  .in(file("runtime/backend/interpreter-plugin-tests"))
  .dependsOn(
    backendInterpreter % "compile->compile;test->test",
    backendInterpreterServer,
  )
  .dependsOn(allPlugins.map(spec => ClasspathDependency(spec.project, None)): _*)
  .settings(
    name := "scalascript-backend-interpreter-plugin-tests",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Streams — interpreter plugin ─────────────────────────────────────────
lazy val streamsPlugin = project
  .in(file("runtime/std/streams-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, frontendCore, frontendPlugin % Test, testUtils % Test)
  .settings(
    name := "scalascript-streams-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.streams"))

// ── DStreams — interpreter plugin ─────────────────────────────────────────
lazy val dstreamsPlugin = project
  .in(file("runtime/std/dstreams-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, testUtils % Test, streamsPlugin % Test)
  .settings(
    name := "scalascript-dstreams-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.dstreams"))

// ── GraphQL — interpreter plugin (Phase 1) ────────────────────────────────
lazy val graphqlPlugin = project
  .in(file("runtime/std/graphql-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, testUtils % Test)
  .settings(
    name := "scalascript-graphql-plugin",
    libraryDependencies ++= Seq(
      "com.graphql-java" % "graphql-java" % "22.3",
      "com.lihaoyi"     %% "ujson"        % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.graphql"))

// ── Deploy — CLI-time plugin ───────────────────────────────────────────────
lazy val deployPlugin = project
  .in(file("runtime/std/deploy-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, testUtils % Test)
  .settings(
    name := "scalascript-deploy-plugin",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.10.7",
      scalatestTest
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.deploy"))

// ── Payment Request API — interpreter plugin ──────────────────────────────
lazy val paymentRequestPlugin = project
  .in(file("runtime/std/payment-request-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core)
  .settings(
    name := "scalascript-payment-request-plugin",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.payment"))

// ── Payment Request API — server-side Apple Pay + Google Pay (JVM) ────────
lazy val paymentRequest = project
  .in(file("payments/payment-request"))
  .settings(
    name := "scalascript-payment-request",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Payments — fiat Money type ────────────────────────────────────────────
lazy val paymentsMoney = project
  .in(file("payments/money"))
  .settings(
    name := "scalascript-payments-money",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Payments — WebhookReceiver SPI ────────────────────────────────────────
lazy val paymentsWebhook = project
  .in(file("payments/webhook"))
  .settings(
    name := "scalascript-payments-webhook",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Payments — Redis-backed cluster-safe SeenKeyStore ────────────────────
lazy val paymentsWebhookRedis = project
  .in(file("payments/webhook-redis"))
  .dependsOn(paymentsWebhook, clientRedis, testUtils % Test)
  .settings(
    name := "scalascript-payments-webhook-redis",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Payments — Postgres-backed cluster-safe SeenKeyStore ─────────────────
lazy val paymentsWebhookPostgres = project
  .in(file("payments/webhook-postgres"))
  .dependsOn(paymentsWebhook, clientPostgres, testUtils % Test)
  .settings(
    name := "scalascript-payments-webhook-postgres",
    libraryDependencies ++= Seq(
      "com.h2database" % "h2" % "2.2.224" % Test,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Payments — PaymentProvider SPI plugin ────────────────────────────────
lazy val paymentsPlugin = project
  .in(file("runtime/std/payments-plugin"))
  .dependsOn(backendSpi, pluginApi, ir, core, paymentsMoney, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-plugin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(sscpkgSettings("scalascript.std.payments"))

// ── Plugin registry — all standard-library plugins ───────────────────────
// Add an entry here when introducing a new std plugin; the five derived
// lists (pluginJarPrefixes, pluginPkgs comment, backendInterpreterPluginTests,
// root aggregate, and cli test deps) all follow automatically.
lazy val allPlugins: Seq[PluginSpec] = Seq(
  PluginSpec("json",            jsonPlugin,            "scalascript-json-plugin"),
  PluginSpec("frontend",        frontendPlugin,        "scalascript-frontend-plugin"),
  PluginSpec("swing",           swingPlugin,           "scalascript-swing-plugin"),
  PluginSpec("request",         requestPlugin,         "scalascript-request-plugin"),
  PluginSpec("auth",            authPlugin,            "scalascript-auth-plugin"),
  PluginSpec("oauth",           oauthPlugin,           "scalascript-oauth-plugin"),
  PluginSpec("fetch",           fetchPlugin,           "scalascript-fetch-plugin"),
  PluginSpec("graph",           graphPlugin,           "scalascript-graph-plugin"),
  PluginSpec("sql",             sqlPlugin,             "scalascript-sql-plugin"),
  PluginSpec("http",            httpPlugin,            "scalascript-http-plugin"),
  PluginSpec("ws",              wsPlugin,              "scalascript-ws-plugin"),
  PluginSpec("mcp",             mcpPlugin,             "scalascript-mcp-plugin"),
  PluginSpec("remote",          remotePlugin,          "scalascript-remote-plugin"),
  PluginSpec("pwa",             pwaPlugin,             "scalascript-pwa-plugin"),
  PluginSpec("streams",         streamsPlugin,         "scalascript-streams-plugin"),
  PluginSpec("dstreams",        dstreamsPlugin,        "scalascript-dstreams-plugin"),
  PluginSpec("graphql",         graphqlPlugin,         "scalascript-graphql-plugin"),
  PluginSpec("deploy",          deployPlugin,          "scalascript-deploy-plugin"),
  PluginSpec("payment-request", paymentRequestPlugin,  "scalascript-payment-request-plugin"),
  PluginSpec("payments",        paymentsPlugin,        "scalascript-payments-plugin"),
)

// ── Payments — Stripe adapter ─────────────────────────────────────────────
lazy val paymentsStripe = project
  .in(file("runtime/std/payments-stripe"))
  .dependsOn(paymentsPlugin, testUtils % Test)
  .settings(
    name := "scalascript-payments-stripe",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"   % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val paymentsPaypal = project
  .in(file("runtime/std/payments-paypal"))
  .dependsOn(paymentsPlugin, testUtils % Test)
  .settings(
    name := "scalascript-payments-paypal",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"   % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val paymentsBraintree = project
  .in(file("runtime/std/payments-braintree"))
  .dependsOn(paymentsPlugin, testUtils % Test)
  .settings(
    name := "scalascript-payments-braintree",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"   % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val paymentsAdyen = project
  .in(file("runtime/std/payments-adyen"))
  .dependsOn(paymentsPlugin, testUtils % Test)
  .settings(
    name := "scalascript-payments-adyen",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"   % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val paymentsCheckout = project
  .in(file("runtime/std/payments-checkout"))
  .dependsOn(paymentsPlugin, testUtils % Test)
  .settings(
    name := "scalascript-payments-checkout",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"   % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val paymentsSquare = project
  .in(file("runtime/std/payments-square"))
  .dependsOn(paymentsPlugin, testUtils % Test)
  .settings(
    name := "scalascript-payments-square",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"   % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val paymentsMock = project
  .in(file("runtime/std/payments-mock"))
  .dependsOn(paymentsPlugin, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-mock",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"   % upickleV,
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — BankRailsProvider SPI core types ────────────────────────
lazy val paymentsBankRails = project
  .in(file("payments/bank-rails"))
  .dependsOn(paymentsMoney, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-bank-rails",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Markup — Generic XML/HTML AST + pure-Scala codec (v1.55) ─────────────
// Cross-compiled (JVM + Scala.js) so that markup-js and markup-node can
// depend on the shared Markup ADT and PureMarkupCodec.
// JVM alias `markupCore` keeps all existing `.dependsOn(markupCore)` intact.
lazy val markupCoreCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Pure)
    .in(file("runtime/std/markup-core"))
    .settings(
      name := "scalascript-markup-core",
      libraryDependencies ++= Seq("org.scalatest" %%% "scalatest" % scalatestV % Test),
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .jvmConfigure(_.withId("markupCore"))
    .jsConfigure(_.withId("markupCoreJs"))
    .jsSettings(Test / fork := false)

lazy val markupCoreJvm = markupCoreCross.jvm
lazy val markupCoreJs  = markupCoreCross.js
// JVM alias — all existing `.dependsOn(markupCore)` continue to work.
lazy val markupCore    = markupCoreJvm

// ── Markup — Scala.js browser DOMParser/XMLSerializer codec (v1.55.7) ────
// Requires browser globals DOMParser + XMLSerializer.  No npm deps.
// Test: sbt markupJs/test (Scala.js Node.js runner — DOMParser tests are
// structural mock tests; real DOMParser integration requires a browser or jsdom).
lazy val markupJs = project
  .in(file("runtime/std/markup-js"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(markupCoreJs)
  .settings(
    name := "scalascript-markup-js",
    scalaJSLinkerConfig ~= { _.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule) },
    libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestV % Test,
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    Test / fork := false,
  )

// ── Markup — Node.js @xmldom/xmldom codec (v1.55.7) ──────────────────────
// Requires `npm install` in runtime/std/markup-node/ before running tests.
// sbt markupNode/test  — real @xmldom/xmldom parse + walk integration tests.
lazy val markupNode = project
  .in(file("runtime/std/markup-node"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(markupCoreJs)
  .settings(
    name := "scalascript-markup-node",
    scalaJSLinkerConfig ~= { _.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule) },
    libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestV % Test,
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
    Test / fork := false,
  )

// ── Bank Rails — SEPA CT + DD adapter ────────────────────────────────────
lazy val paymentsSepa = project
  .in(file("runtime/std/payments-sepa"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, markupCore, testUtils % Test)
  .settings(
    name := "scalascript-payments-sepa",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — ACH Nacha flat-file adapter ─────────────────────────────
lazy val paymentsAch = project
  .in(file("runtime/std/payments-ach"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-ach",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — FedNow ISO 20022 instant payments adapter ───────────────
lazy val paymentsFednow = project
  .in(file("runtime/std/payments-fednow"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, markupCore, testUtils % Test)
  .settings(
    name := "scalascript-payments-fednow",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — Pix instant payments adapter (Brazil) ───────────────────
lazy val paymentsPix = project
  .in(file("runtime/std/payments-pix"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-pix",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — SWIFT MT103 + ISO 20022 pacs.008 CBPR+ adapter ──────────
lazy val paymentsSwift = project
  .in(file("runtime/std/payments-swift"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-swift",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — UK Faster Payments Service adapter ───────────────────────
lazy val paymentsUkFps = project
  .in(file("runtime/std/payments-uk-fps"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-uk-fps",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — UK BACS Direct Debit adapter ────────────────────────────
lazy val paymentsUkBacs = project
  .in(file("runtime/std/payments-uk-bacs"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-uk-bacs",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — UK CHAPS adapter ────────────────────────────────────────
lazy val paymentsUkChaps = project
  .in(file("runtime/std/payments-uk-chaps"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-uk-chaps",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — India UPI adapter ───────────────────────────────────────
lazy val paymentsIndiaUpi = project
  .in(file("runtime/std/payments-india-upi"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-india-upi",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — Japan Zengin (全銀) domestic bank transfer adapter ────────
lazy val paymentsJapanZengin = project
  .in(file("runtime/std/payments-japan-zengin"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-japan-zengin",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — Singapore PayNow adapter ────────────────────────────────
lazy val paymentsSgPaynow = project
  .in(file("runtime/std/payments-sg-paynow"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-sg-paynow",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val paymentsMxSpei = project
  .in(file("runtime/std/payments-mx-spei"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-mx-spei",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — Canada Interac e-Transfer + EFT adapter ─────────────────
lazy val paymentsCaEft = project
  .in(file("runtime/std/payments-ca-eft"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-ca-eft",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── FX rate provider — SPI module ────────────────────────────────────────
lazy val fxSpi = project
  .in(file("payments/fx"))
  .dependsOn(paymentsBankRails, testUtils % Test)
  .settings(
    name := "payments-fx",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── FX rate provider — ECB daily reference rates adapter ─────────────────
lazy val fxEcb = project
  .in(file("payments/fx-ecb"))
  .dependsOn(fxSpi, markupCore, testUtils % Test)
  .settings(
    name := "payments-fx-ecb",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── FX rate provider — Open Exchange Rates adapter ────────────────────────
lazy val fxOer = project
  .in(file("payments/fx-openexchangerates"))
  .dependsOn(fxSpi, testUtils % Test)
  .settings(
    name := "payments-fx-openexchangerates",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Bank Rails — Australia NPP (New Payments Platform) / PayID adapter ───
lazy val paymentsAuNpp = project
  .in(file("runtime/std/payments-au-npp"))
  .dependsOn(backendSpi, paymentsBankRails, paymentsWebhook, testUtils % Test)
  .settings(
    name := "scalascript-payments-au-npp",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Compliance provider — SPI module ─────────────────────────────────────
lazy val paymentsCompliance = project
  .in(file("payments/compliance"))
  .dependsOn(testUtils % Test)
  .settings(
    name := "payments-compliance",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Compliance provider — ComplyAdvantage REST v1 adapter ─────────────────
lazy val paymentsComplianceComplyAdvantage = project
  .in(file("payments/compliance-complyadvantage"))
  .dependsOn(paymentsCompliance, testUtils % Test)
  .settings(
    name := "payments-compliance-complyadvantage",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Compliance provider — Chainalysis KYT v2 adapter ─────────────────────
lazy val paymentsComplianceChainalysis = project
  .in(file("payments/compliance-chainalysis"))
  .dependsOn(paymentsCompliance, testUtils % Test)
  .settings(
    name := "payments-compliance-chainalysis",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Compliance provider — Mock (testing) ─────────────────────────────────
lazy val paymentsComplianceMock = project
  .in(file("payments/compliance-mock"))
  .dependsOn(paymentsCompliance, testUtils % Test)
  .settings(
    name := "payments-compliance-mock",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Tax provider — SPI module ─────────────────────────────────────────────
lazy val paymentsTax = project
  .in(file("payments/tax"))
  .dependsOn(paymentsMoney, testUtils % Test)
  .settings(
    name := "payments-tax",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Tax provider — Stripe Tax Calculations API v1 adapter ────────────────
lazy val paymentsTaxStripe = project
  .in(file("payments/tax-stripe"))
  .dependsOn(paymentsTax, testUtils % Test)
  .settings(
    name := "payments-tax-stripe",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Tax provider — Avalara AvaTax REST v2 adapter ─────────────────────────
lazy val paymentsTaxAvalara = project
  .in(file("payments/tax-avalara"))
  .dependsOn(paymentsTax, testUtils % Test)
  .settings(
    name := "payments-tax-avalara",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Tax provider — TaxJar SmartCalcs v2 adapter ───────────────────────────
lazy val paymentsTaxJar = project
  .in(file("payments/tax-taxjar"))
  .dependsOn(paymentsTax, testUtils % Test)
  .settings(
    name := "payments-tax-taxjar",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Government Interaction — bureau-core SPI ──────────────────────────────
lazy val bureauCore = project
  .in(file("gov/bureau-core"))
  .dependsOn(paymentsMoney, testUtils % Test)
  .settings(
    name := "scalascript-bureau-core",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Government Interaction — bureau-signing SPI ───────────────────────────
lazy val bureauSigning = project
  .in(file("gov/bureau-signing"))
  .dependsOn(bureauCore, testUtils % Test)
  .settings(
    name := "scalascript-bureau-signing",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Government Interaction — Poland social (ZUS) ──────────────────────────
lazy val bureauPlSocial = project
  .in(file("gov/bureau-pl-social"))
  .dependsOn(bureauCore, testUtils % Test)
  .settings(
    name := "scalascript-bureau-pl-social",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Government Interaction — Poland fiscal (KSeF) ─────────────────────────
lazy val bureauPlFiscal = project
  .in(file("gov/bureau-pl-fiscal"))
  .dependsOn(bureauCore, bureauSigning, testUtils % Test)
  .settings(
    name := "scalascript-bureau-pl-fiscal",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Government Interaction — Poland registry (CEIDG/REGON/BiałaLista/KRS) ─
lazy val bureauPlRegistry = project
  .in(file("gov/bureau-pl-registry"))
  .dependsOn(bureauCore, testUtils % Test)
  .settings(
    name := "scalascript-bureau-pl-registry",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Government Interaction — EU (VIES VAT verification) ───────────────────
lazy val bureauEu = project
  .in(file("gov/bureau-eu"))
  .dependsOn(bureauCore, testUtils % Test)
  .settings(
    name := "scalascript-bureau-eu",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Government Interaction — bureau-mock ──────────────────────────────────
lazy val bureauMock = project
  .in(file("gov/bureau-mock"))
  .dependsOn(bureauCore, testUtils % Test)
  .settings(
    name := "scalascript-bureau-mock",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// ── Government Interaction — bureau-scheduler ─────────────────────────────
lazy val bureauScheduler = project
  .in(file("gov/bureau-scheduler"))
  .dependsOn(bureauCore, testUtils % Test)
  .settings(
    name := "scalascript-bureau-scheduler",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    backendSpi, pluginApi, ir, logger, yaml, core, interop, testUtils, pluginHost, wireCore,

    runtimeServerCommon, runtimeServerSpi, runtimeServerJvm,
    runtimeServerJvmJetty, runtimeServerJvmNetty, mcpCommon,
    backendJvm, backendJs, backendNode, backendScalajs, backendWasm, backendInterpreter, backendInterpreterServer, backendInterpreterPluginTests,
    backendScalaSource, backendHtml, backendCss, backendSpark, backendKafkaStreams, backendFlink, backendDap,
    cli, clientPostgres, clientRedis, clientEvm, clientKafka, clientCoinbase,  backendSqlRuntime, backendSqlRuntimeJs, sqlAws, sqlGcp, sqlAzure, backendTypedDataRuntime, backendGraphRuntime, backendConfigRuntime,
    clientBlockfrost,
    x402Core, x402Server, x402Client, x402ClientJs,
    x402FacilitatorCoinbase, x402FacilitatorEvm, x402FacilitatorCardano,
    x402QueueKafka, x402QueuePostgres, x402NoncePostgres, x402NonceRedis,
    cryptoSpi, cryptoSpiJs, cryptoBouncycastle, cryptoNobleJs, blockchainSpi, blockchainSpiJs, blockchainEvm, blockchainEvmAbi, blockchainEvmAbiJs, blockchainSolana, blockchainCardano, blockchainBitcoin, blockchainCosmos, walletSpi, walletSpiJs, walletVaultEncrypted, walletVaultEncryptedJs, walletVaultMpc, walletVaultTrezor, walletVaultMpcFireblocks, walletVaultMpcCoinbase, walletVaultMpcLit, walletVaultMpcZengo, walletVaultLedger, walletVaultLedgerJvm, walletVaultLedgerJs, walletVaultLedgerBluetoothJs, walletVaultLedgerEthereum, walletVaultLedgerSolana, walletVaultLedgerBitcoin, walletVaultLedgerCardano, walletStrategyEoa, walletStrategyEoaJs, walletStrategyErc4337, walletStrategyErc4337Js, walletConnectorEip1193, walletConnectorEip1193Js, walletConnect, walletConnectJs, walletConnectorWalletStd, walletConnectorWalletStdJs, mcpWallet, mcpX402,
    micropaymentSpi, micropaymentThreshold, micropaymentServer, micropaymentClient, micropaymentProbabilistic, micropaymentChannelEvm, micropaymentHydra,
    frontendCore, frontendCustom, frontendReact, frontendSolid, frontendVue, frontendElectron, frontendSwing, frontendJavaFx, frontendSwiftUI,
    // frontendToolkit retired — replaced by std/ui/*.ssc (Phase 7a-7d)
    frontendExamples,
    // std plugins are aggregated via allPlugins registry below
    paymentRequest,
    paymentsMoney, paymentsWebhook, paymentsWebhookRedis, paymentsWebhookPostgres, paymentsStripe, paymentsPaypal, paymentsBraintree, paymentsAdyen, paymentsCheckout, paymentsSquare, paymentsMock,
    paymentsBankRails, paymentsSepa, paymentsAch, paymentsFednow, paymentsPix, paymentsSwift, paymentsUkFps, paymentsUkBacs, paymentsUkChaps, paymentsIndiaUpi, paymentsJapanZengin, paymentsSgPaynow, paymentsAuNpp, paymentsMxSpei, paymentsCaEft,
    fxSpi, fxEcb, fxOer,
    paymentsTax, paymentsTaxStripe, paymentsTaxAvalara, paymentsTaxJar,
    paymentsCompliance, paymentsComplianceComplyAdvantage, paymentsComplianceChainalysis, paymentsComplianceMock,
    markupCore, markupCoreJs, markupJs, markupNode,
    bureauCore, bureauSigning, bureauPlFiscal, bureauPlRegistry, bureauPlSocial, bureauEu, bureauScheduler, bureauMock,
  )
  // Std plugins — derived from allPlugins registry (arch-build-registry-p1)
  .aggregate(allPlugins.map(_.project: ProjectReference): _*)
  .settings(
    publish / skip := true
  )
