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

lazy val verifyStandaloneTargetIsolation = taskKey[Unit](
  "Verify that every standalone UniML subproject has a distinct target namespace",
)

val standaloneTargetSettings = Seq(
  target := baseDirectory.value / "target" / "standalone",
)

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
    .settings(standaloneTargetSettings)
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
    .settings(standaloneTargetSettings)
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
    .settings(standaloneTargetSettings)
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
    .settings(standaloneTargetSettings)
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
  // The ScalaScript dialect and the `.ssc` composer are PRODUCTION sources here since
  // UPR-4a; they used to live in `uniml/core`'s TEST scope, which is why this project
  // needed core's test classes. It no longer does — dropping `test->test` is how that
  // move is checked, not merely asserted.
  .dependsOn(unimlCross.jvm, unimlMarkdownCross.jvm, unimlYamlCross.jvm, unimlJsonCross.jvm)
  .settings(
    name := "scalascript-uniml-scala",
    libraryDependencies ++= Seq("org.scalatest" %% "scalatest" % scalatestV % Test),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions,
  )
  .settings(standaloneTargetSettings)

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
    verifyStandaloneTargetIsolation := {
      val resolved = Vector(
        "uniml"           -> (unimlCross.jvm / target).value,
        "unimlJs"         -> (unimlCross.js / target).value,
        "unimlJson"       -> (unimlJsonCross.jvm / target).value,
        "unimlJsonJs"     -> (unimlJsonCross.js / target).value,
        "unimlYaml"       -> (unimlYamlCross.jvm / target).value,
        "unimlYamlJs"     -> (unimlYamlCross.js / target).value,
        "unimlMarkdown"   -> (unimlMarkdownCross.jvm / target).value,
        "unimlMarkdownJs" -> (unimlMarkdownCross.js / target).value,
        "unimlScala"      -> (unimlScala / target).value,
      ).map { case (id, path) => id -> path.getAbsoluteFile.toPath.normalize }
      val invalidSuffix = resolved.filterNot { case (_, path) =>
        val count = path.getNameCount
        count >= 2 &&
        path.getName(count - 2).toString == "target" &&
        path.getName(count - 1).toString == "standalone"
      }
      val collisions =
        resolved.groupBy(_._2).toVector.filter(_._2.size > 1).sortBy(_._1.toString)
      if (
        resolved.size != 9 ||
        resolved.map(_._2).distinct.size != 9 ||
        invalidSuffix.nonEmpty ||
        collisions.nonEmpty
      ) {
        val paths = resolved.map { case (id, path) => s"  $id=$path" }.mkString("\n")
        val duplicatePaths = collisions.map { case (path, members) =>
          s"  $path <- ${members.map(_._1).sorted.mkString(",")}"
        }.mkString("\n")
        sys.error(
          "Standalone UniML target isolation failed.\n" +
            s"Expected 9 distinct paths ending in target/standalone; resolved:\n$paths\n" +
            (if (duplicatePaths.isEmpty) "" else s"Collisions:\n$duplicatePaths\n")
        )
      }
      streams.value.log.info(
        "Standalone UniML target isolation: 9 distinct target/standalone namespaces",
      )
    },
    Test / test := ((Test / test) dependsOn verifyStandaloneTargetIsolation).value,
  )
