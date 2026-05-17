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

val sharedScalacOptions = Seq("-Wunused:all", "-deprecation", "-feature")
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
    scalacOptions ++= sharedScalacOptions
  )

lazy val backendSpi = project
  .in(file("backend-spi"))
  .dependsOn(ir)
  .settings(
    name := "scalascript-backend-spi",
    scalacOptions ++= sharedScalacOptions
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
    scalacOptions ++= sharedScalacOptions
  )

lazy val backendJvm = project
  .in(file("backend-jvm"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-jvm",
    scalacOptions ++= sharedScalacOptions
  )

lazy val backendJs = project
  .in(file("backend-js"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-js",
    scalacOptions ++= sharedScalacOptions
  )

lazy val backendScalajs = project
  .in(file("backend-scalajs"))
  .dependsOn(backendSpi, core)
  .settings(
    name := "scalascript-backend-scalajs",
    scalacOptions ++= sharedScalacOptions
  )

// TRANSITIONAL DEPENDENCY: backend-interpreter → backend-js.
// `server/WebServer.scala` imports `scalascript.codegen.{JsGen, JsRuntime}`
// to inject the JS runtime into SPA-mode pages.  Stage 5 (Backend SPI §8 —
// HTTP/WS intrinsics) extracts this so the server lives behind the SPI and
// backends no longer reference each other.
lazy val backendInterpreter = project
  .in(file("backend-interpreter"))
  .dependsOn(backendSpi, core, backendJs)
  .settings(
    name := "scalascript-backend-interpreter",
    libraryDependencies ++= Seq(scalatestTest),
    scalacOptions ++= sharedScalacOptions
  )

lazy val cli = project
  .in(file("cli"))
  .dependsOn(core, backendJvm, backendJs, backendScalajs, backendInterpreter)
  .settings(
    name := "scalascript-cli",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "pprint" % "0.9.6",
      scalatestTest
    ),
    scalacOptions ++= sharedScalacOptions,
    assembly / mainClass       := Some("scalascript.cli.ssc"),
    assembly / assemblyJarName := "ssc.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _ @ _*) => MergeStrategy.discard
      case _                            => MergeStrategy.first
    }
  )

// NOTE: `bench/` exists today as a scala-cli script directory (fib/sum/
// list-ops workload comparisons across backends) — not an sbt project.
// `WsStress` lives under backend-interpreter/.../bench/ since it
// stresses the interpreter's WS runtime.

lazy val root = project
  .in(file("."))
  .aggregate(
    backendSpi, ir, core,
    backendJvm, backendJs, backendScalajs, backendInterpreter,
    cli
  )
  .settings(
    publish / skip := true
  )
