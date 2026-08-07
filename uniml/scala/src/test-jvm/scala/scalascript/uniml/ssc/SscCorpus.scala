package scalascript.uniml.ssc

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The repository's `.ssc` corpus — the set every ScalaScript-dialect spec measures, defined once.
  *
  * WHY THIS EXISTS. Until 2026-08-07 this walk was copy-pasted into EIGHT places across seven
  * files, each with its own root discovery, its own filter, its own exclusion list and its own
  * floor. They agreed, which is exactly what made the duplication invisible: nothing was red, and
  * nothing would have gone red if a ninth exclusion had been added to seven of the eight — the
  * specs would simply have started measuring different corpora while all reporting green.
  *
  * It had already drifted, and in the direction that is hardest to notice. The second walk in
  * `Ssc3ProjectionCensusSpec` (the source-level second-parent scan) carried neither the sort nor
  * the floor assertion the other seven had. Nobody chose that; it is what a copy becomes.
  *
  * WHAT THE EXCLUSIONS ARE FOR, kept from the copies rather than reinvented:
  *   - `/target/`, `/.git/`  — build and VCS internals, never source.
  *   - `/.worktrees/`        — sibling agents' checkouts; including them counts the same file
  *                             once per live worktree, so the corpus size would track how many
  *                             agents happen to be running.
  *   - `/bin/lib/`           — INSTALLED OUTPUT. `install.sh --dev` copies the whole runtime
  *                             there, which grew the corpus from 1,145 to 1,406 files the moment
  *                             somebody ran the installer, and every count moved with it.
  *
  * TWO CHECKS, BECAUSE THEY CATCH DIFFERENT FAILURES. "The sweep silently shrank" was one sentence
  * covering two things, and the single `> 500` floor only ever caught one of them.
  *
  *   - **Collapse** — a wrong root, a walk that threw, a build that ran the spec from somewhere
  *     unexpected. The number falls off a cliff, and a loose floor catches it. That is `MinFiles`.
  *   - **Erosion** — an exclusion widened by one character, a filter typo. Ten files vanish and a
  *     floor of 500 against 1,240 never notices. Nothing here caught this until now.
  *
  * Erosion is checked against an INDEPENDENT enumeration rather than a frozen number, because a
  * frozen number is what went stale in the first place: `git ls-files` answers "which `.ssc` files
  * are in this repository" without walking the filesystem or knowing this filter exists. Measured
  * 2026-08-07 — the walk finds 1,240 and git tracks 1,240, exactly, and git tracks nothing under
  * `bin/lib/` or `target/` for the exclusions to disagree about.
  *
  * The comparison is deliberately ONE-SIDED (`found >= tracked`). A new `.ssc` you have not
  * committed yet makes the walk find MORE, which is normal and must not fail; a filter that drops
  * files makes it find FEWER, which is the defect. A gate that reddens on an uncommitted file is a
  * gate people learn to ignore.
  *
  * WHY THIS LIVES HERE AND NOT IN A SHELL GATE. A shell script would have to re-implement the walk
  * to compare it, and then it would be checking its own copy — green while the real sweep is
  * broken. The check has to run where the sweep runs.
  *
  * WHAT WAS REJECTED: freezing the exact count, the way the UniML CI aggregate check now does. That
  * corpus is a closed set that changes deliberately; this one grows whenever anyone adds a `.ssc`,
  * so an exact number would redden on every unrelated commit and be raised without being read.
  */
object SscCorpus:

  /** The repository root, found by walking up to the directory that holds `AGENTS.md`.
    *
    * Not `user.dir` and not a relative path: sbt runs tests with the working directory set to the
    * sub-build, and the same spec is run from the root build and from `uniml/`'s standalone one.
    */
  def repoRoot: Path =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("AGENTS.md")))
      .getOrElse(throw new IllegalStateException("repository root not found"))

  /** A collapse is anything under this. Loose ON PURPOSE — it is the cliff detector, and erosion is
    * the other check's job. Kept at the value all eleven copies carried so that adding the second
    * check is not also a silent change to the first.
    */
  private val MinFiles = 500

  /** Every `.ssc` file in the repository, sorted, with both checks applied.
    *
    * Sorted because a `Files.walk` order is filesystem-dependent, and a census that reports "the
    * first five offenders" would otherwise name different files on different machines.
    */
  def files(root: Path = repoRoot): Vector[Path] =
    val found = paths(root)
    assert(found.sizeIs > MinFiles,
      s"only ${found.size} .ssc found — the sweep collapsed (wrong root, or the walk threw)")
    val tracked = trackedCount(root)
    assert(found.sizeIs >= tracked,
      s"""the sweep found ${found.size} .ssc files but git tracks $tracked in the same repository.
         |Fewer than tracked means the walk's filter is dropping files it should keep — check the
         |exclusions in `SscCorpus.paths`. (The other direction is fine and not asserted: an
         |uncommitted new file makes the walk find more.)""".stripMargin)
    found

  /** How many `.ssc` files git tracks under `root` — the independent answer the walk is checked
    * against.
    *
    * NOT tolerant of a missing git, deliberately. A check that quietly passes when it could not run
    * is the failure mode this whole object exists because of; and these specs already require the
    * repository itself, since `repoRoot` looks for `AGENTS.md` and the sweep measures this tree.
    * Memoised because eight specs call `files`, and the answer cannot change within a run.
    */
  private val trackedCache = scala.collection.mutable.Map.empty[String, Int]

  private def trackedCount(root: Path): Int = trackedCache.getOrElseUpdate(root.toString, {
    import scala.sys.process.*
    val cmd = Seq("git", "-C", root.toString, "ls-files", "--", "*.ssc")
    val out =
      try cmd.!!
      catch
        case e: Exception =>
          throw new IllegalStateException(
            s"cannot ask git how many .ssc files it tracks (${cmd.mkString(" ")}): ${e.getMessage}. " +
            "This check compares the sweep against an independent enumeration and is not allowed to " +
            "pass by skipping.", e)
    out.linesIterator.count(_.nonEmpty)
  })

  /** The same set without the assertion, for the one caller that legitimately wants it: a check
    * that itself reports on corpus contents and would otherwise assert twice in one test.
    */
  def paths(root: Path = repoRoot): Vector[Path] =
    Files.walk(root).iterator.asScala
      .filter(_.toString.endsWith(".ssc"))
      .filterNot(p =>
        p.toString.contains("/target/") || p.toString.contains("/.git/") ||
          p.toString.contains("/.worktrees/") || p.toString.contains("/bin/lib/"))
      .toVector
      .sortBy(_.toString)

  /** A file's text, decoded as UTF-8 — the other line every one of these specs repeated. */
  def read(p: Path): String = new String(Files.readAllBytes(p), "UTF-8")
