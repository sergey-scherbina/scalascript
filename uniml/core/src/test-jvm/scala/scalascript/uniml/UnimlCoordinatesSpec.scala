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

  /** Every quoted string a `ThisBuild / <key> := …` declaration carries, in order.
    *
    * `licenses`, `homepage` and `scmInfo` are not plain settings — they are `Seq(id -> url(u))`,
    * `Some(url(u))` and `Some(ScmInfo(url(u), "scm:…"))` — so `setting` above cannot read them.
    * Their comparable CONTENT is the quoted strings: the SPDX id, the URLs, the scm connection.
    * Comparing those catches a drift; comparing the whole expression would fail on formatting
    * instead, and a gate that cries about whitespace gets disabled rather than fixed.
    *
    * The declaration may span several lines (`scmInfo` does), so it runs to the next `ThisBuild`,
    * a `val`/`lazy val`, a BLANK LINE, or end of input — not to the next newline.
    *
    * The blank line is load-bearing and was learned the hard way. Without it the window ran from
    * the root's `scmInfo` past a blank line and two comment blocks to the next `val`, swallowing
    * `"..."` and `"SSC_SBT_TEST_CONCURRENCY"` — so the check failed on text that has nothing to do
    * with publishing. In `uniml/build.sbt` the same declaration is followed immediately by
    * `ThisBuild / description`, so the bug was invisible from that side: one file terminated the
    * window early by luck. */
  private def quotedIn(text: String, key: String): Vector[String] =
    val decl = raw"""ThisBuild\s*/\s*$key\s*:=([\s\S]*?)(?=\n\s*\n|\nThisBuild|\nval |\nlazy |\z)""".r
    decl.findFirstMatchIn(text).map(_.group(1)) match
      case None       => Vector.empty
      case Some(body) => raw""""([^"]*)"""".r.findAllMatchIn(body).map(_.group(1)).toVector

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

      // PUBLISHING METADATA, added to the root build 2026-08-06. Before that the root declared
      // NONE of it while `uniml/build.sbt` declared all of it, so the standalone build was the only
      // one that could publish a well-formed artifact — a strange property for the build that owns
      // the release.
      //
      // The second copy arrives WITH this check rather than after it, because a value written down
      // twice and agreeing only by memory is the shape this repository paid for three times in one
      // day: the uniml version (`24581733e` moved one and left the other, reddening the nightly
      // job for everyone), the `Main.scala` coordinate, and the sbt-plugin's fourth version, still
      // open. Two declarations plus a gate is one fact; two declarations alone is a defect waiting
      // for the next edit.
      for key <- Vector("licenses", "homepage", "scmInfo") do
        val o = quotedIn(ours, key)
        val t = quotedIn(theirs, key)
        assert(o.nonEmpty, s"uniml/build.sbt declares no ThisBuild / $key")
        assert(t.nonEmpty, s"root build.sbt declares no ThisBuild / $key — it must publish the same metadata")
        assert(o == t, s"$key drifted between the builds:\n  standalone $o\n  root       $t")
      info(s"metadata in step: licenses/homepage/scmInfo identical in both builds")
  }
