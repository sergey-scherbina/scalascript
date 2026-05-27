// sbt-scalascript-interop — build-tool integration for ScalaScript v2.0 interop.
//
// This is a standalone sbt build (nested inside the monorepo under
// tools/sbt-plugin/). It is intended to be extracted into a separate
// `scalascript-sbt-plugin` repository when the plugin is published. The
// build has no dependency on the parent build.sbt — it only needs `ssc`
// on PATH (or a configured `sscBinary` setting) at task-execution time.

ThisBuild / organization := "org.scalascript"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.20"
ThisBuild / sbtPlugin    := true
ThisBuild / licenses     := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / homepage     := Some(url("https://github.com/scalascript/scalascript-sbt-plugin"))

lazy val root = (project in file("."))
  .enablePlugins(ScriptedPlugin)
  .settings(
    name        := "sbt-scalascript-interop",
    description := "sbt plugin: generate Scala 3 facade sources from ScalaScript .scim artifacts",

    scriptedLaunchOpts ++= Seq(
      "-Xmx512m",
      s"-Dplugin.version=${version.value}"
    ),
    scriptedBufferLog := false,
  )
