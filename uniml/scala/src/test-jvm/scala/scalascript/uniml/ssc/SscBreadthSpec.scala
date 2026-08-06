package scalascript.uniml.ssc

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import org.scalatest.funsuite.AnyFunSuite

/** How much real ScalaScript the composed front parses — SSC3-B's measure.
  *
  * DIAGNOSTICS, not AST node counts. `SpikeTypedCoverageSpec` counts
  * `spike.error` NODES and is blind to this: fixing `case object`, which removed
  * 470 diagnostics and made 12 more files parse completely clean, moved its
  * numbers by exactly ZERO, because error recovery produced a similar node
  * shape either way. Two gates, two questions — this one asks whether the parse
  * SUCCEEDED, that one asks whether the AST models what the parse produced.
  *
  * Through `SscCompose`, so each dialect sees only its own bytes: a `.ssc` is a
  * literate document and measuring the bare dialect against a whole file counts
  * markdown as broken ScalaScript. */
final class SscBreadthSpec extends AnyFunSuite:

  private def repoRoot: Path =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("AGENTS.md")))
      .getOrElse(throw new IllegalStateException("repository root not found"))

  test("the composed front parses the repository's ScalaScript") {
    val root = repoRoot
    val files = Files.walk(root).iterator.asScala
      .filter(_.toString.endsWith(".ssc"))
      .filterNot(p =>
        // `bin/` is INSTALLED OUTPUT — `install.sh --dev` copies the whole runtime there,
        // so leaving it in made the corpus grow from 1,145 files to 1,406 the moment
        // somebody ran the installer, and every count moved with it.
        p.toString.contains("/target/") || p.toString.contains("/.git/") ||
          p.toString.contains("/.worktrees/") || p.toString.contains("/bin/lib/"))
      .toVector.sortBy(_.toString)
    assert(files.sizeIs > 500, s"only ${files.size} .ssc found — the sweep silently shrank")

    var clean = 0
    var total = 0L
    // Attribution BY FENCE, not by file. The note below the assertions used to say "17 files hold
    // an untagged fence and those files account for 129 diagnostics" — which cannot tell whether
    // the diagnostics came from the untagged fence or from a tagged one in the same file. These
    // two counters can: each scalascript fence is re-parsed on its own and its diagnostics land in
    // the bucket its OWN info string earns.
    var taggedDiags = 0L
    var untaggedDiags = 0L
    var taggedFences = 0
    var untaggedFences = 0
    // BARE files are a THIRD population and get their own counters for the same reason tagged and
    // untagged have theirs: a whole fenceless file is neither a declared fence nor a prose bucket,
    // and averaging it into either hides which one moved.
    var bareDiags = 0L
    var bareFiles = 0
    val bareWorst = scala.collection.mutable.Map.empty[String, Int]
    val shapes = scala.collection.mutable.Map.empty[String, Int]
    val taggedShapes = scala.collection.mutable.Map.empty[String, Int]
    val taggedExample = scala.collection.mutable.Map.empty[String, String]
    val worst = scala.collection.mutable.ArrayBuffer.empty[(String, Int)]

    files.foreach { p =>
      val composed = SscCompose.parse(new String(Files.readAllBytes(p), "UTF-8"))
      if composed.diagnostics.isEmpty then clean += 1
      else worst += ((root.relativize(p).toString, composed.diagnostics.size))
      total += composed.diagnostics.size
      composed.fences.filter(_.isScala).foreach { f =>
        // A BARE region is re-parsed AS ITSELF. Wrapping it in a fence the way a fence body is
        // wrapped emits ```<bare>, which names no registered dialect, so the fence stays inert and
        // the measurement reads zero — a column reporting 0 because it asked the wrong question,
        // which is worse than no column. Caught by the number being implausibly clean.
        val ds =
          if f.lang == "<bare>" then SscCompose.parse(f.code).diagnostics
          else SscCompose.parse("```" + (if f.lang.isEmpty then "scalascript" else f.lang) + "\n" + f.code + "```\n").diagnostics
        if f.lang == "<bare>" then
          bareFiles += 1
          bareDiags += ds.size
          if ds.nonEmpty then bareWorst(root.relativize(p).toString) = ds.size
        else if f.lang.isEmpty then { untaggedFences += 1; untaggedDiags += ds.size }
        else
          taggedFences += 1
          taggedDiags += ds.size
          // Shapes from the TAGGED column only. Picking the next construct off the mixed histogram
          // means picking off prose: the untagged bucket is 2.3% of the fences and a third of the
          // diagnostics, and none of it is a language gap.
          ds.foreach { d =>
            val token = "'([^']*)'".r.findFirstMatchIn(d.message).map(_.group(1)).getOrElse("?")
            val shape = d.message.replaceAll("'[^']*'", "'X'") + " <- " + token
            taggedShapes(shape) = taggedShapes.getOrElse(shape, 0) + 1
            if !taggedExample.contains(shape) then
              taggedExample(shape) = root.relativize(p).toString + d.span.map(sp => ":" + sp.start.line).getOrElse("")
          }
      }
      composed.diagnostics.foreach { d =>
        val token = "'([^']*)'".r.findFirstMatchIn(d.message).map(_.group(1)).getOrElse("?")
        val shape = d.message.replaceAll("'[^']*'", "'X'") + " <- " + token
        shapes(shape) = shapes.getOrElse(shape, 0) + 1
      }
    }

    val pct = 100.0 * clean / files.size
    info(f"files=${files.size} clean=$clean ($pct%.1f%%) diagnostics=$total")
    // The two columns exist so a change cannot be credited to the wrong one. Twice on 2026-08-04 a
    // correct change moved the headline because the parser became willing to swallow PROSE from an
    // untagged fence — a protocol diagram, an English sentence starting with the word `class`. The
    // TAGGED column is the one that measures the language.
    info(f"  by fence: tagged $taggedFences%4d fences / $taggedDiags%4d diags    untagged $untaggedFences%4d fences / $untaggedDiags%4d diags")
    info(f"  by file:  bare   $bareFiles%4d files  / $bareDiags%4d diags")
    bareWorst.toVector.sortBy(-_._2).take(5).foreach((f, c) => info(f"    bare worst $c%5d  $f"))
    info("  TAGGED-only shapes — the language gaps, with one example each:")
    taggedShapes.toVector.sortBy(-_._2).take(8).foreach((s, c) => info(f"    $c%4d  $s%-52s ${taggedExample.getOrElse(s, "")}"))
    shapes.toVector.sortBy(-_._2).take(8).foreach((s, c) => info(f"  $c%5d  $s"))
    worst.sortBy(-_._2).take(5).foreach((f, c) => info(f"  worst $c%5d  $f"))

    // Floors: a fix must be free to improve these without editing the test, but a
    // regression in what the front accepts has to fail.
    //
    // ZERO IS NOT THE TARGET, and the two columns above say why with numbers rather than prose.
    // An UNTAGGED fence (```) defaults to ScalaScript, so a fence holding a protocol diagram or
    // shell output reports diagnostics no parser fix can remove. Measured 2026-08-05, per FENCE
    // rather than per file: 1,650 tagged fences carry 172 diagnostics (0.10 each) while 38
    // untagged ones carry 96 (2.53 each) — **25x the density in 2.3% of the fences**. That is
    // what a bucket full of non-code looks like, and chasing the headline to zero would mean
    // teaching the parser prose or changing the untagged default, both wrong.
    //
    // So TAGGED is the number that measures the language, and it gets its own floor. Twice on
    // 2026-08-04 a correct change was credited by the headline for making prose parse; with the
    // columns split, such a change moves `untagged` and leaves `tagged` flat, which is the
    // difference between a language fix and a permissiveness win.
    //
    // tagged + untagged is slightly under `total` (268 vs 273): a handful of diagnostics come
    // from the markdown and front-matter layers, which belong to no ScalaScript fence.
    // ── the BARE column, added 2026-08-06 with the composer's bare mode ──────────────────────
    //
    // A `.ssc` with no fence is the program in its entirety (fences optional since 2026-07-09).
    // Until bare mode the composer handed such a file to NO dialect, so it contributed zero
    // diagnostics — not because it parsed, but because nobody looked. Switching it on moved the
    // headline to 7,131 — and 7,110 of that is ONE FILE. 84 of the 87 bare files parse CLEAN, and
    // the clean-file rate actually ROSE, 1,166 -> 1,176 (98.1% -> 99.0%).
    //
    // THE HEADLINE ROSE BECAUSE THE MEASUREMENT BECAME HONEST, which is the same shape as the
    // typed AST's gaps rising 2,943 -> 2,964 the day its silent drops were fixed. What was
    // previously "clean" for 3 files was clean the way an unopened box is empty.
    //
    // `specs/v2.2-p6.5-fsub.ssc` is 2,687 lines of **ssc0** — the P6.5 F compiler written in the
    // subset — in a file named `.ssc`. It is real code and bare mode is right to hand it over; the
    // ScalaScript dialect simply does not parse ssc0. Whether that file should be `.ssc0` or the
    // dialect owes ssc0 support is filed, not guessed: `uniml/BACKLOG.md`.
    //
    // So `total` is raised to admit a population that was never measured, and the bare column gets
    // its OWN floor so the concession is bounded and a regression inside it is still visible.
    // TAGGED is unchanged and must stay so: bare mode is not a language change, and if this commit
    // had moved that column it would have been a different claim entirely.
    assert(pct > 90.0, f"clean-parse rate fell to $pct%.1f%%")
    assert(total < 7400, s"diagnostics grew to $total")
    assert(bareDiags < 7300, s"diagnostics from BARE files grew to $bareDiags")
    // The floor that actually bounds this population, and it needs no filename: at most a handful
    // of bare files may fail to parse. Measured THREE — `specs/v2.2-p6.5-fsub.ssc` (the ssc0
    // program, 7,110 on its own), `v1/tools/scripts/launchers/http.ssc` (2), and
    // `v3/tests/front/unclosed-brace.ssc` (1), which is a fixture that SHOULD report. The other 84
    // parse clean, which is why the diagnostic total above is one file rather than a population.
    assert(bareWorst.size <= 5, s"${bareWorst.size} bare files now fail to parse: ${bareWorst.keys.mkString(", ")}")
    assert(taggedDiags < 250, s"diagnostics from TAGGED fences grew to $taggedDiags — this is the column that measures the language, and it regressed")
  }
