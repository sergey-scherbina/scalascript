ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "io.scalascript"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Forked test JVMs default to ~512 KB stack which trips
// `mutual-TCO` / `stack-safe bind chains` / Async tests under
// parallel suite execution.  4 MB held the flake down most of the
// time; 8 MB is what finally stopped it once the WS suites started
// spawning per-connection virtual threads (each parked VT briefly
// consumes carrier stack).  Cost is negligible.
ThisBuild / Test / javaOptions += "-Xss8m"
ThisBuild / Test / fork         := true

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
  .in(file("backend-spi"))
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
  .in(file("runtime-server-common"))
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
  .in(file("mcp-common"))
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
  .in(file("runtime-server-spi"))
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
  .in(file("runtime-server-jvm"))
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
  .in(file("runtime-server-jvm-jetty"))
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
  .in(file("runtime-server-jvm-netty"))
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
  .in(file("frontend-core"))
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
  .in(file("frontend-custom"))
  .dependsOn(frontendCore)
  .settings(
    name := "scalascript-frontend-custom",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

// v1.18 / Phase A3 stub — React backend.  Signal lowers to useState,
// Component to function components, View to React.createElement.
// Today: stub.
lazy val frontendReact = project
  .in(file("frontend-react"))
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
  .in(file("frontend-solid"))
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
  .in(file("frontend-vue"))
  .dependsOn(frontendCore)
  .settings(
    name := "scalascript-frontend-vue",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendJvm = project
  .in(file("backend-jvm"))
  .dependsOn(backendSpi, core, runtimeServerCommon, runtimeServerSpi, runtimeServerJvm, backendSqlRuntime)
  .settings(
    name := "scalascript-backend-jvm",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendJs = project
  .in(file("backend-js"))
  .dependsOn(backendSpi, core)
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
  .in(file("backend-node"))
  .dependsOn(backendSpi, core, backendJs)
  .settings(
    name := "scalascript-backend-node",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendScalajs = project
  .in(file("backend-scalajs"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-scalajs",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )

lazy val backendWasm = project
  .in(file("backend-wasm"))
  .dependsOn(backendSpi, core)
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
  .in(file("backend-scala-source"))
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
  .in(file("backend-html"))
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
  .in(file("backend-css"))
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
  .in(file("backend-interpreter"))
  .dependsOn(backendSpi, core, runtimeServerCommon, runtimeServerJvm, mcpCommon, backendJs, backendSqlRuntime, backendJvm % Test)
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
  .in(file("backend-spark"))
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

lazy val cli = project
  .in(file("cli"))
  .enablePlugins(SbtProguard)
  .dependsOn(core, interop, backendJvm, backendJs, backendNode, backendScalajs, backendWasm, backendInterpreter, backendScalaSource, backendHtml, backendCss, backendSpark)
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
    }
  )

// NOTE: `bench/` exists today as a scala-cli script directory (fib/sum/
// list-ops workload comparisons across backends) — not an sbt project.
// `WsStress` lives under backend-interpreter/.../bench/ since it
// stresses the interpreter's WS runtime.

lazy val clientPostgres = project
  .in(file("client-postgres"))
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
  .in(file("backend-sql-runtime"))
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

lazy val clientRedis = project
  .in(file("client-redis"))
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
  .in(file("client-evm"))
  .settings(
    name := "scalascript-client-evm",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"  % "4.0.0-M17",
      "com.softwaremill.sttp.client4" %% "upickle" % "4.0.0-M17",
      "com.lihaoyi"                   %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val clientKafka = project
  .in(file("client-kafka"))
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
  .in(file("client-coinbase"))
  .settings(
    name := "scalascript-client-coinbase",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"  % "4.0.0-M17",
      "com.lihaoyi"                   %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402Core = project
  .in(file("x402-core"))
  .settings(
    name := "scalascript-x402-core",
    libraryDependencies ++= Seq(
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402Server = project
  .in(file("x402-server"))
  .dependsOn(x402Core, runtimeServerCommon)
  .settings(
    name := "scalascript-x402-server",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402Client = project
  .in(file("x402-client"))
  .dependsOn(x402Core, walletStrategyEoa, blockchainEvm, blockchainCardano, cryptoBouncycastle)
  .settings(
    name := "scalascript-x402-client",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402FacilitatorCoinbase = project
  .in(file("x402-facilitator-coinbase"))
  .dependsOn(x402Core, clientCoinbase)
  .settings(
    name := "scalascript-x402-facilitator-coinbase",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402FacilitatorEvm = project
  .in(file("x402-facilitator-evm"))
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
  .in(file("x402-queue-kafka"))
  .dependsOn(x402Core, clientKafka)
  .settings(
    name := "scalascript-x402-queue-kafka",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402QueuePostgres = project
  .in(file("x402-queue-postgres"))
  .dependsOn(x402Core, clientPostgres)
  .settings(
    name := "scalascript-x402-queue-postgres",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402NoncePostgres = project
  .in(file("x402-nonce-postgres"))
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
  .in(file("x402-nonce-redis"))
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
  .in(file("client-blockfrost"))
  .settings(
    name := "scalascript-client-blockfrost",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core"    % "4.0.0-M17",
      "com.lihaoyi"                   %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val x402FacilitatorCardano = project
  .in(file("x402-facilitator-cardano"))
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

// ---------------------------------------------------------------------------
// Wallet / blockchain SPI tracks (docs/blockchain-spi.md + docs/wallet-spi.md)
//
// Phase 1 lands four pure-trait modules below. Phase 2 adds
// crypto-bouncycastle (JVM CryptoBackend impl) and blockchain-evm.
// All modules are JVM-only for now; Scala.js cross-compile follows in
// Phase 3 (crypto-noble-js) per the spec.
// ---------------------------------------------------------------------------

lazy val cryptoSpi = project
  .in(file("crypto-spi"))
  .settings(
    name := "scalascript-crypto-spi",
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val cryptoBouncycastle = project
  .in(file("crypto-bouncycastle"))
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

lazy val blockchainSpi = project
  .in(file("blockchain-spi"))
  .dependsOn(cryptoSpi)
  .settings(
    name := "scalascript-blockchain-spi",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletSpi = project
  .in(file("wallet-spi"))
  .dependsOn(blockchainSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-spi",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletVaultEncrypted = project
  .in(file("wallet-vault-encrypted"))
  .dependsOn(walletSpi, cryptoSpi, cryptoBouncycastle)
  .settings(
    name := "scalascript-wallet-vault-encrypted",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletStrategyEoa = project
  .in(file("wallet-strategy-eoa"))
  .dependsOn(walletSpi, blockchainSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-strategy-eoa",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

// wallet-spi Phase 8 — MPC Vault (docs/wallet-spi.md §10).
// HTTP client to an external multi-party-computation signing provider.
// No private keys local; every signature is a round-trip to a TSS /
// FROST / GG18 quorum. The trait abstracts over actual MPC vendors;
// `HttpRemoteSigningClient` is a reference "Fireblocks-shaped" REST
// integration with sync + async-polling support.
lazy val walletVaultMpc = project
  .in(file("wallet-vault-mpc"))
  .dependsOn(walletSpi, cryptoSpi)
  .settings(
    name := "scalascript-wallet-vault-mpc",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletStrategyErc4337 = project
  .in(file("wallet-strategy-erc4337"))
  .dependsOn(walletSpi, blockchainSpi, cryptoSpi, blockchainEvm, blockchainEvmAbi, cryptoBouncycastle % Test, walletStrategyEoa % Test)
  .settings(
    name := "scalascript-wallet-strategy-erc4337",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletConnectorEip1193 = project
  .in(file("wallet-connector-eip1193"))
  .dependsOn(walletSpi, blockchainSpi, walletStrategyEoa % Test, blockchainEvm % Test, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-wallet-connector-eip1193",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletConnect = project
  .in(file("wallet-connect"))
  .dependsOn(walletSpi, blockchainSpi, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-wallet-connect",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      "org.bouncycastle" % "bcprov-jdk18on" % "1.78.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val walletConnectorWalletStd = project
  .in(file("wallet-connector-wallet-std"))
  .dependsOn(walletSpi, blockchainSpi, blockchainSolana, walletStrategyEoa % Test, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-wallet-connector-wallet-std",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val mcpWallet = project
  .in(file("mcp-wallet"))
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
  .in(file("mcp-x402"))
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

lazy val blockchainEvmAbi = project
  .in(file("blockchain-evm-abi"))
  .dependsOn(cryptoSpi, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-blockchain-evm-abi",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val blockchainEvm = project
  .in(file("blockchain-evm"))
  .dependsOn(blockchainSpi, cryptoSpi, blockchainEvmAbi, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-blockchain-evm",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val blockchainSolana = project
  .in(file("blockchain-solana"))
  .dependsOn(blockchainSpi, cryptoSpi, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-blockchain-solana",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val blockchainCardano = project
  .in(file("blockchain-cardano"))
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
  .in(file("micropayment-spi"))
  .dependsOn(blockchainSpi, walletSpi)
  .settings(
    name := "scalascript-micropayment-spi",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentThreshold = project
  .in(file("micropayment-threshold"))
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
  .in(file("micropayment-server"))
  .dependsOn(micropaymentSpi, runtimeServerCommon)
  .settings(
    name := "scalascript-micropayment-server",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentClient = project
  .in(file("micropayment-client"))
  .dependsOn(micropaymentSpi)
  .settings(
    name := "scalascript-micropayment-client",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "3.3.1",
      scalatestTest,
    ),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentProbabilistic = project
  .in(file("micropayment-probabilistic"))
  .dependsOn(micropaymentSpi, blockchainSpi)
  .settings(
    name := "scalascript-micropayment-probabilistic",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentChannelEvm = project
  .in(file("micropayment-channel-evm"))
  .dependsOn(micropaymentSpi, blockchainSpi, blockchainEvm, walletSpi, cryptoBouncycastle % Test)
  .settings(
    name := "scalascript-micropayment-channel-evm",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val micropaymentHydra = project
  .in(file("micropayment-hydra"))
  .dependsOn(micropaymentSpi, blockchainSpi)
  .settings(
    name := "scalascript-micropayment-hydra",
    libraryDependencies ++= Seq("com.lihaoyi" %% "upickle" % "3.3.1", scalatestTest),
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
    cli, clientPostgres, clientRedis, clientEvm, clientKafka, clientCoinbase, backendSqlRuntime,
    clientBlockfrost,
    x402Core, x402Server, x402Client,
    x402FacilitatorCoinbase, x402FacilitatorEvm, x402FacilitatorCardano,
    x402QueueKafka, x402QueuePostgres, x402NoncePostgres, x402NonceRedis,
    cryptoSpi, cryptoBouncycastle, blockchainSpi, blockchainEvm, blockchainEvmAbi, blockchainSolana, blockchainCardano, walletSpi, walletVaultEncrypted, walletVaultMpc, walletStrategyEoa, walletStrategyErc4337, walletConnectorEip1193, walletConnect, walletConnectorWalletStd, mcpWallet, mcpX402,
    micropaymentSpi, micropaymentThreshold, micropaymentServer, micropaymentClient, micropaymentProbabilistic, micropaymentChannelEvm, micropaymentHydra,
    frontendCore, frontendCustom, frontendReact, frontendSolid, frontendVue,
  )
  .settings(
    publish / skip := true
  )
