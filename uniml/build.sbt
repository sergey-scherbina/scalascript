// Standalone build for the UniML library — builds independently of ScalaScript.
//
// `cd uniml && sbt test` compiles and tests core + json + yaml + markdown with
// ZERO dependency on the ScalaScript (v1/v2) trees. The root ScalaScript build
// references these same source dirs (the v1 bindings uniml-xml / uniml-markdown-
// bridge depend on them), so both builds compile the same sources; this build is
// the proof that UniML stands alone. Endgame (uniml-portable follow-up): replace
// the dual build with `publishLocal` once UniML is truly extracted.

ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "scalascript"

val scalatestV = "3.2.18"
val upickleV   = "4.4.2"
val sharedScalacOptions       = Seq("-Wunused:all", "-deprecation", "-feature")
val sharedScalacOptionsStrict = sharedScalacOptions :+ "-Werror"

lazy val unimlCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Pure)
    .in(file("core"))
    .settings(
      name := "scalascript-uniml",
      libraryDependencies ++= Seq("org.scalatest" %%% "scalatest" % scalatestV % Test),
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .jvmConfigure(_.withId("uniml"))
    .jsConfigure(_.withId("unimlJs"))
    .jsSettings(Test / fork := false)

lazy val unimlJsonCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Pure)
    .in(file("json"))
    .dependsOn(unimlCross)
    .settings(
      name := "scalascript-uniml-json",
      libraryDependencies ++= Seq(
        "com.lihaoyi" %%% "ujson" % upickleV % Test,
        "org.scalatest" %%% "scalatest" % scalatestV % Test,
      ),
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .jvmConfigure(_.withId("unimlJson"))
    .jsConfigure(_.withId("unimlJsonJs"))
    .jsSettings(Test / fork := false)

lazy val unimlYamlCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Pure)
    .in(file("yaml"))
    .dependsOn(unimlCross)
    .settings(
      name := "scalascript-uniml-yaml",
      libraryDependencies ++= Seq("org.scalatest" %%% "scalatest" % scalatestV % Test),
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .jvmConfigure(_.withId("unimlYaml"))
    .jvmSettings(
      libraryDependencies += "org.snakeyaml" % "snakeyaml-engine" % "2.9" % Test,
      Test / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "test-jvm" / "scala",
    )
    .jsConfigure(_.withId("unimlYamlJs"))
    .jsSettings(Test / fork := false)

lazy val unimlMarkdownCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Pure)
    .in(file("markdown"))
    .dependsOn(unimlCross)
    .settings(
      name := "scalascript-uniml-markdown",
      libraryDependencies ++= Seq("org.scalatest" %%% "scalatest" % scalatestV % Test),
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .jvmConfigure(_.withId("unimlMarkdown"))
    .jvmSettings(
      Test / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "test-jvm" / "scala",
    )
    .jsConfigure(_.withId("unimlMarkdownJs"))
    .jsSettings(Test / fork := false)

// P6.3 — "unify the hybrid": composes the Markdown + YAML dialects with the ScalaScript
// spike dialect (core test scope) so a whole .ssc parses as ONE lossless UniML tree.
// JVM-only: the composition spec is a differential harness that writes files (java.nio),
// exactly like the core-test spike. `test->test` on core exposes SpikeDialect/SpikeProject.
lazy val unimlScala = project
  .in(file("scala"))
  .dependsOn(unimlCross.jvm % "compile->compile;test->test", unimlMarkdownCross.jvm, unimlYamlCross.jvm, unimlJsonCross.jvm)
  .settings(
    name := "scalascript-uniml-scala",
    libraryDependencies ++= Seq("org.scalatest" %% "scalatest" % scalatestV % Test),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    unimlCross.jvm, unimlCross.js,
    unimlJsonCross.jvm, unimlJsonCross.js,
    unimlYamlCross.jvm, unimlYamlCross.js,
    unimlMarkdownCross.jvm, unimlMarkdownCross.js,
    unimlScala,
  )
  .settings(
    name := "uniml",
    publish / skip := true,
  )
