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

// The repository root, found by walking up to the checkout marker rather than counting `..` from
// here. Counting levels is what broke the native-image config paths in the root build: they read
// `baseDirectory / ".." / ".."`, the tree was reorganised under v1/, and the paths silently pointed
// at a directory that had never existed. `.git` is a FILE in a worktree and a directory in a normal
// clone, so this tests existence rather than type.
def sscRepoRoot(from: File): File = {
  var d = from
  while (d != null && !(d / ".git").exists()) d = d.getParentFile
  if (d == null) sys.error(s"sbt-plugin: no .git above $from — cannot locate the repository root")
  d
}

lazy val root = (project in file("."))
  .enablePlugins(ScriptedPlugin)
  .settings(
    name        := "sbt-scalascript-interop",
    description := "sbt plugin: generate Scala 3 facade sources from ScalaScript .scim artifacts",

    scriptedLaunchOpts ++= Seq(
      "-Xmx512m",
      s"-Dplugin.version=${version.value}",
      // The repository root, so a scenario can point sscBinary at the REAL staged launcher instead
      // of a mock. Every other scenario mocks ssc, which proves the wiring and nothing about the
      // toolchain -- the same shape of gap that let `ssc new` emit a perfectly well-formed
      // coordinate for an artifact that did not exist.
      s"-Dssc.repo.root=${sscRepoRoot((ThisBuild / baseDirectory).value).getAbsolutePath}"
    ),
    scriptedBufferLog := false,
  )
