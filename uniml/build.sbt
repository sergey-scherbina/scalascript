// Standalone build for the UniML library — builds independently of ScalaScript.
//
// `cd uniml && sbt test` compiles and tests core + json + yaml + markdown + the ScalaScript dialect
// with ZERO dependency on the ScalaScript (v1/v2) trees. The root ScalaScript build references these
// same source dirs, so both builds compile the same sources; this build is the proof that UniML
// stands alone — and it is a real proof, not a convention: planting `import
// scalascript.ast.DocumentContent` into a source here fails with "value ast is not a member of
// scalascript".
//
// 2026-08-05: the markdown->DocumentContent bridge, which was the ONLY thing under `uniml/` that
// imported v1, moved to `v1/lang/uniml-bridge`. An adapter that produces v1's model belongs on v1's
// side. Nothing under `uniml/` reaches v1 now, and `tests/e2e/project-partition-gate.sh` enforces it
// with no exemption. Endgame (uniml-portable follow-up): replace
// the dual build with `publishLocal` once UniML is truly extracted.

ThisBuild / scalaVersion := "3.8.3"

// MATCH THE ROOT BUILD'S COORDINATES. Both builds compile the same sources, so they must publish
// the same artifact — otherwise `publishLocal` from here produced
// `scalascript:scalascript-uniml_3:0.1.0-SNAPSHOT` while the root produced
// `io.scalascript:scalascript-uniml_3:0.1.0`: same code, two coordinates, not interchangeable in a
// consumer's build. Measured 2026-08-05 by publishing from both and listing ~/.ivy2/local.
// Kept in step with `build.sbt` lines 2-3 by `UnimlCoordinatesSpec`, which reads them.
ThisBuild / organization := "io.scalascript"
ThisBuild / version      := "0.1.0"

// Publishing metadata, so a released artifact says what it is and where it came from. The root
// build declares none of this; when it publishes for real it should read from the same source.
ThisBuild / licenses     := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / homepage     := Some(url("https://github.com/sergey-scherbina/scalascript"))
ThisBuild / scmInfo      := Some(ScmInfo(
  url("https://github.com/sergey-scherbina/scalascript"),
  "scm:git:https://github.com/sergey-scherbina/scalascript.git",
))
ThisBuild / description  := "UniML — a lossless token-to-tree framework with dialects for JSON, YAML, Markdown, XML and ScalaScript."

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
    // `UniAlphabetSweepSpec` compares the classifier against the host's Unicode tables, which only
    // means something on the lane that HAS them; the same split `unimlYaml` and `unimlMarkdown` use.
    .jvmSettings(
      Test / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "test-jvm" / "scala",
    )
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
// The ScalaScript dialect + the `.ssc` composer. CROSS-BUILT since UPR-4b: the
// production sources use no JVM API at all — no `java.*`, no threads, no files —
// so the front end a ScalaScript 3 toolchain needs is available on Scala.js too.
// The tests that WALK THE REPOSITORY are JVM-only by nature and live in
// `src/test-jvm`, the same split `unimlYaml` and `unimlMarkdown` already use.
lazy val unimlScalaCross =
  crossProject(JVMPlatform, JSPlatform)
    .crossType(CrossType.Pure)
    .in(file("scala"))
    .dependsOn(unimlCross, unimlMarkdownCross, unimlYamlCross, unimlJsonCross)
    .settings(
      name := "scalascript-uniml-scalascript",
      libraryDependencies ++= Seq("org.scalatest" %%% "scalatest" % scalatestV % Test),
      Compile / scalacOptions ++= sharedScalacOptionsStrict,
      Test    / scalacOptions ++= sharedScalacOptions,
    )
    .settings(standaloneTargetSettings)
    .jvmConfigure(_.withId("unimlScala"))
    .jvmSettings(
      Test / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "test-jvm" / "scala",
    )
    .jsConfigure(_.withId("unimlScalaJs"))
    .jsSettings(Test / fork := false)

lazy val unimlScala = unimlScalaCross.jvm

// SSC3-M measurement arm. NOT aggregated on purpose: a benchmark has no tests to contribute to
// `sbt test`, and the standalone isolation gate below enumerates exactly ten projects. Run with
//   sbt "unimlBench/Jmh/run -i 5 -wi 3 -f 1 .*SpikeParserBench.*"
// and read specs/uniml-ssc3-frontend.md §4.2b for the method and its one honest gap.
lazy val unimlBench = project
  .in(file("bench"))
  .dependsOn(unimlScalaCross.jvm)
  .enablePlugins(JmhPlugin)
  .settings(
    name := "scalascript-uniml-bench",
    publish / skip := true,
    Jmh / scalacOptions ++= sharedScalacOptions,
    Jmh / javaOptions ++= Seq("-Xmx4g", "-XX:+UseG1GC"),
  )
  .settings(standaloneTargetSettings)

lazy val root = project
  .in(file("."))
  .aggregate(
    unimlCross.jvm, unimlCross.js,
    unimlJsonCross.jvm, unimlJsonCross.js,
    unimlYamlCross.jvm, unimlYamlCross.js,
    unimlMarkdownCross.jvm, unimlMarkdownCross.js,
    unimlScalaCross.jvm, unimlScalaCross.js,
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
        "unimlScala"      -> (unimlScalaCross.jvm / target).value,
        "unimlScalaJs"    -> (unimlScalaCross.js / target).value,
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
        resolved.size != 10 ||
        resolved.map(_._2).distinct.size != 10 ||
        invalidSuffix.nonEmpty ||
        collisions.nonEmpty
      ) {
        val paths = resolved.map { case (id, path) => s"  $id=$path" }.mkString("\n")
        val duplicatePaths = collisions.map { case (path, members) =>
          s"  $path <- ${members.map(_._1).sorted.mkString(",")}"
        }.mkString("\n")
        sys.error(
          "Standalone UniML target isolation failed.\n" +
            s"Expected 10 distinct paths ending in target/standalone; resolved:\n$paths\n" +
            (if (duplicatePaths.isEmpty) "" else s"Collisions:\n$duplicatePaths\n")
        )
      }
      streams.value.log.info(
        "Standalone UniML target isolation: 10 distinct target/standalone namespaces",
      )
    },
    Test / test := ((Test / test) dependsOn verifyStandaloneTargetIsolation).value,
  )
