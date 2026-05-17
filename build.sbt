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

// ---------------------------------------------------------------------------
// Backend SPI v0.1 — module layout (docs/backend-spi.md §4.1)
//
// Stage 1.1 of the SPI rollout: empty modules + dependency arrows.
// Sources still live in `compiler/` until Stage 1.2 moves them.
// ---------------------------------------------------------------------------

lazy val backendSpi = project
  .in(file("backend-spi"))
  .settings(
    name := "scalascript-backend-spi",
    scalacOptions ++= sharedScalacOptions
  )

lazy val ir = project
  .in(file("ir"))
  .dependsOn(backendSpi)
  .settings(
    name := "scalascript-ir",
    scalacOptions ++= sharedScalacOptions
  )

lazy val core = project
  .in(file("core"))
  .dependsOn(backendSpi, ir)
  .settings(
    name := "scalascript-core",
    scalacOptions ++= sharedScalacOptions
  )

lazy val backendJvm = project
  .in(file("backend-jvm"))
  .dependsOn(backendSpi, ir)
  .settings(
    name := "scalascript-backend-jvm",
    scalacOptions ++= sharedScalacOptions
  )

lazy val backendJs = project
  .in(file("backend-js"))
  .dependsOn(backendSpi, ir)
  .settings(
    name := "scalascript-backend-js",
    scalacOptions ++= sharedScalacOptions
  )

lazy val backendScalajs = project
  .in(file("backend-scalajs"))
  .dependsOn(backendSpi, ir)
  .settings(
    name := "scalascript-backend-scalajs",
    scalacOptions ++= sharedScalacOptions
  )

lazy val backendInterpreter = project
  .in(file("backend-interpreter"))
  .dependsOn(backendSpi, ir)
  .settings(
    name := "scalascript-backend-interpreter",
    scalacOptions ++= sharedScalacOptions
  )

lazy val cli = project
  .in(file("cli"))
  .dependsOn(core, backendJvm, backendJs, backendScalajs, backendInterpreter)
  .settings(
    name := "scalascript-cli",
    scalacOptions ++= sharedScalacOptions
  )

// NOTE: `bench/` exists today as a scala-cli script directory (fib/sum/
// list-ops workload comparisons across backends) — not an sbt project.
// WsStress (currently in compiler/.../bench/) needs an sbt home but the
// placement is deferred to Stage 1.2; see docs/backend-spi-plan.md
// "Open questions" #1.

// ---------------------------------------------------------------------------
// Transitional: the existing single-module compiler.  Sources move out into
// the new modules in Stage 1.2; this entry disappears at that point.
// ---------------------------------------------------------------------------

lazy val compiler = project
  .in(file("compiler"))
  .settings(
    name := "scalascript",
    libraryDependencies ++= Seq(
      "org.yaml"         %  "snakeyaml"  % "2.6",
      "com.lihaoyi"      %% "os-lib"     % "0.11.4",
      "com.lihaoyi"      %% "upickle"    % "4.4.2",
      "com.lihaoyi"      %% "pprint"     % "0.9.6",
      "org.scalameta"    %% "scalameta"  % "4.17.0",
      "org.commonmark"   %  "commonmark" % "0.28.0",
      "org.scalatest"    %% "scalatest"  % "3.2.18" % Test
    ),
    scalacOptions ++= sharedScalacOptions
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    backendSpi, ir, core,
    backendJvm, backendJs, backendScalajs, backendInterpreter,
    cli,
    compiler
  )
  .settings(
    publish / skip := true
  )
