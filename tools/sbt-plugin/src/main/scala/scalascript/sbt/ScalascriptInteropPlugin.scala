package scalascript.sbt

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

/** sbt plugin for ScalaScript v2.0 interop (Tier 3).
 *
 *  Wires `FacadeGenerator` source generation into the standard sbt
 *  `Compile / sourceGenerators` lifecycle.  Plugin authors must opt in
 *  explicitly — no auto-trigger.
 *
 *  Minimal usage:
 *  {{{
 *  // project/plugins.sbt
 *  addSbtPlugin("org.scalascript" % "sbt-scalascript-interop" % "0.1.0")
 *
 *  // build.sbt
 *  enablePlugins(ScalascriptInteropPlugin)
 *  sscArtifactDir := baseDirectory.value / ".ssc-artifacts"
 *  }}}
 *
 *  The plugin forks `ssc generate-facade <artifactDir> -o <managedSrc>` to
 *  produce Scala 3 source files in `target/scala-<v>/src_managed/main/ssc-facade/`.
 *  No compile-time dependency on the ScalaScript runtime — facade sources
 *  only contain `export` aliases that are resolved by the Scala 3 compiler.
 *
 *  Settings:
 *  - `sscArtifactDir`  — directory containing `.scim` artifacts (required).
 *  - `sscLinkedJar`    — linked ScalaScript runnable JAR output.
 *  - `sscTestResultsDir` — directory for `ssc test` JUnit XML output.
 *  - `sscBinary`       — path to the `ssc` binary (default: "ssc" on PATH).
 *  - `sscSourceDirectories` — source directories containing `.ssc` files.
 *  - `sscBackend`      — backend passed to `ssc build --incremental`.
 *
 *  Tasks:
 *  - `sscCompile` — compile `.ssc` sources via `ssc build --incremental`.
 *  - `sscLink` — link `.ssc` artifacts via `ssc link`.
 *  - `sscTest` — run `.ssc` tests via `ssc test`.
 *  - `sscGenerateFacade` — generate facade sources (hooked into sourceGenerators).
 */
object ScalascriptInteropPlugin extends AutoPlugin {

  object autoImport {
    val sscSourceDirectories = settingKey[Seq[File]](
      "ScalaScript source directories."
    )
    val sscArtifactDir = settingKey[File](
      "Directory containing ScalaScript .scim artifacts for facade generation."
    )
    val sscLinkedJar = settingKey[File](
      "Runnable JAR produced by `ssc link`."
    )
    val sscTestResultsDir = settingKey[File](
      "Directory where `ssc test --output-format junit-xml` writes results."
    )
    val sscBinary = settingKey[String](
      "Path to the ssc binary used by sbt-scalascript tasks (default: 'ssc')."
    )
    val sscBackend = settingKey[String](
      "ScalaScript backend passed to `ssc build --incremental`."
    )
    val sscExtraArgs = settingKey[Seq[String]](
      "Extra arguments appended to `ssc build --incremental`."
    )
    val sscCompile = taskKey[Seq[File]](
      "Compile .ssc sources via `ssc build --incremental`."
    )
    val sscLink = taskKey[File](
      "Link .ssc artifacts into a runnable JAR via `ssc link`."
    )
    val sscTest = taskKey[TestResult](
      "Run .ssc tests via `ssc test --output-format junit-xml`."
    )
    val sscGenerateFacade = taskKey[Seq[File]](
      "Generate Scala 3 facade sources from .scim artifacts via `ssc generate-facade`."
    )
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin

  // Opt-in: users call `enablePlugins(ScalascriptInteropPlugin)`.
  override def trigger: PluginTrigger = noTrigger

  override def projectSettings: Seq[Setting[_]] = Seq(
    sscBinary := "ssc",
    sscBackend := "jvm",
    sscExtraArgs := Seq.empty,
    Compile / sscSourceDirectories := Seq((Compile / sourceDirectory).value / "scalascript"),
    Test / sscSourceDirectories := Seq((Test / sourceDirectory).value / "scalascript"),
    Compile / sscArtifactDir := (Compile / target).value / "ssc-artifacts",
    Compile / sscLinkedJar := (Compile / sscArtifactDir).value / "linked.jar",
    Test / sscTestResultsDir := (Test / target).value / "ssc-test-results",
    sscArtifactDir := (Compile / sscArtifactDir).value,
    sscLinkedJar := (Compile / sscLinkedJar).value,
    sscTestResultsDir := (Test / sscTestResultsDir).value,

    Compile / sscCompile := {
      val dirs = (Compile / sscSourceDirectories).value.filter(_.isDirectory)
      val sourceDirs = dirs.filter(dir => (dir ** "*.ssc").get().nonEmpty)
      val artifactDir = (Compile / sscArtifactDir).value
      val log = streams.value.log
      if (sourceDirs.isEmpty) {
        log.info("[ssc] no .ssc sources found")
        Seq.empty[File]
      } else {
        IO.createDirectory(artifactDir)
        sourceDirs.foreach { dir =>
          SscRunner.run(
            binary = sscBinary.value,
            args = Seq(
              "build",
              "--incremental",
              dir.getAbsolutePath,
              "--artifact-dir",
              artifactDir.getAbsolutePath,
              "--backend",
              sscBackend.value
            ) ++ sscExtraArgs.value,
            log = log
          )
        }
        (artifactDir ** "*").get().filter(_.isFile).toSeq
      }
    },

    Compile / sscLink := {
      val compileArtifacts = (Compile / sscCompile).value
      val artifactDir = (Compile / sscArtifactDir).value
      val linkedJar = (Compile / sscLinkedJar).value
      val log = streams.value.log
      if (!artifactDir.exists() || compileArtifacts.isEmpty) {
        log.info("[ssc] no .ssc artifacts to link")
      } else {
        IO.createDirectory(linkedJar.getParentFile)
        SscRunner.run(
          binary = sscBinary.value,
          args = Seq(
            "link",
            "--backend",
            sscBackend.value,
            "--output",
            linkedJar.getAbsolutePath,
            artifactDir.getAbsolutePath
          ) ++ sscExtraArgs.value,
          log = log
        )
      }
      linkedJar
    },

    sscGenerateFacade := {
      val artifactDir = sscArtifactDir.value
      val outDir      = (Compile / sourceManaged).value / "ssc-facade"
      val ssc         = sscBinary.value
      val log         = streams.value.log

      if (!artifactDir.exists()) {
        log.warn(s"[ssc] sscArtifactDir does not exist: $artifactDir — skipping facade generation.")
        Seq.empty[File]
      } else {
        IO.createDirectory(outDir)

        SscRunner.run(
          binary = ssc,
          args = Seq("generate-facade", artifactDir.getAbsolutePath, "-o", outDir.getAbsolutePath),
          log = log
        )

        (outDir ** "*.scala").get().toSeq
      }
    },

    Test / sscTest := {
      val dirs = (Test / sscSourceDirectories).value.filter(_.isDirectory)
      val testDirs = dirs.filter(dir => (dir ** "*.ssc").get().nonEmpty)
      val resultsDir = (Test / sscTestResultsDir).value
      val log = streams.value.log

      if (testDirs.isEmpty) {
        log.info("[ssc] no .ssc tests found")
        TestResult.Passed
      } else {
        IO.delete(resultsDir)
        IO.createDirectory(resultsDir)
        testDirs.foreach { dir =>
          SscRunner.run(
            binary = sscBinary.value,
            args = Seq(
              "test",
              dir.getAbsolutePath,
              "--backend",
              sscBackend.value,
              "--output-format",
              "junit-xml",
              "--output",
              resultsDir.getAbsolutePath
            ) ++ sscExtraArgs.value,
            log = log
          )
        }
        val result = SscTestFramework.parseJUnitXml(resultsDir, log)
        result match {
          case TestResult.Passed => result
          case TestResult.Failed => sys.error("ssc tests failed")
          case TestResult.Error  => sys.error("ssc tests errored")
        }
      }
    },

    Compile / compile := ((Compile / compile) dependsOn (Compile / sscCompile)).value,
    Compile / packageBin := ((Compile / packageBin) dependsOn (Compile / sscLink)).value,
    Test / test := ((Test / test) dependsOn (Test / sscTest)).value,
    Compile / sourceGenerators += sscGenerateFacade.taskValue
  )
}
