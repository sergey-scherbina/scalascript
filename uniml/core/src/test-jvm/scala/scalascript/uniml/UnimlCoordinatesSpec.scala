package scalascript.uniml

import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite

/** The two builds must publish the SAME artifact.
  *
  * `uniml/build.sbt` and the root `build.sbt` compile the same sources. Until 2026-08-05 they
  * published them under different coordinates — `scalascript:…:0.1.0-SNAPSHOT` from here,
  * `io.scalascript:…:0.1.0` from the root — so the same code existed as two artifacts that a
  * consumer's build treats as unrelated. Nothing noticed, because each build published successfully
  * on its own; the drift is only visible when you look at both.
  *
  * Reading the root build is allowed to FAIL SOFT for one reason and one only: the stated endgame
  * is that UniML is extracted and the root build stops existing. That is a legitimate absence. Any
  * other absence — a rename, a moved file — makes this print what it could not find rather than
  * pass quietly.
  */
final class UnimlCoordinatesSpec extends AnyFunSuite:

  private def repoRoot: Option[Path] =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("AGENTS.md")))

  private def setting(text: String, key: String): Option[String] =
    raw"""ThisBuild\s*/\s*$key\s*:=\s*"([^"]+)"""".r.findFirstMatchIn(text).map(_.group(1))

  test("the standalone build publishes the same coordinates as the root") {
    val root = repoRoot.getOrElse(fail("repository root not found"))
    val rootBuild = root.resolve("build.sbt")
    val ourBuild = root.resolve("uniml").resolve("build.sbt")
    assert(Files.isRegularFile(ourBuild), s"$ourBuild is missing — this spec cannot check anything")

    val ours = new String(Files.readAllBytes(ourBuild), "UTF-8")
    val ourOrg = setting(ours, "organization").getOrElse(fail("uniml/build.sbt declares no ThisBuild / organization"))
    val ourVer = setting(ours, "version").getOrElse(fail("uniml/build.sbt declares no ThisBuild / version — without it sbt invents 0.1.0-SNAPSHOT"))

    if !Files.isRegularFile(rootBuild) then
      info(s"root build.sbt absent — UniML is extracted; nothing to compare. ours: $ourOrg : $ourVer")
    else
      val theirs = new String(Files.readAllBytes(rootBuild), "UTF-8")
      val theirOrg = setting(theirs, "organization").getOrElse(fail("root build.sbt declares no ThisBuild / organization"))
      val theirVer = setting(theirs, "version").getOrElse(fail("root build.sbt declares no ThisBuild / version"))
      info(s"standalone $ourOrg:$ourVer   root $theirOrg:$theirVer")
      assert(ourOrg == theirOrg,
        s"organization drifted: standalone '$ourOrg' vs root '$theirOrg'. The same sources would publish as two unrelated artifacts.")
      assert(ourVer == theirVer,
        s"version drifted: standalone '$ourVer' vs root '$theirVer'. The same sources would publish as two versions of one artifact.")
  }
