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
 *  - `sscBinary`       — path to the `ssc` binary (default: "ssc" on PATH).
 *
 *  Tasks:
 *  - `sscGenerateFacade` — generate facade sources (hooked into sourceGenerators).
 */
object ScalascriptInteropPlugin extends AutoPlugin {

  object autoImport {
    val sscArtifactDir = settingKey[File](
      "Directory containing ScalaScript .scim artifacts for facade generation."
    )
    val sscBinary = settingKey[String](
      "Path to the ssc binary used for facade generation (default: 'ssc')."
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

        val cmd = Seq(ssc, "generate-facade", artifactDir.getAbsolutePath, "-o", outDir.getAbsolutePath)
        log.info(s"[ssc] ${cmd.mkString(" ")}")

        val rc = scala.sys.process.Process(cmd) ! log
        if (rc != 0) sys.error(s"ssc generate-facade failed with exit code $rc")

        (outDir ** "*.scala").get().toSeq
      }
    },

    Compile / sourceGenerators += sscGenerateFacade.taskValue
  )
}
