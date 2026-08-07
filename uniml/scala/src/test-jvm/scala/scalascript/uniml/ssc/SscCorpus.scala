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
  * THE FLOOR IS INHERITED, NOT ENDORSED. `> 500` is what all seven carried and it is kept here
  * unchanged, so this commit is a pure de-duplication with no behavioural argument smuggled into
  * it. It is also loose: the corpus is ~1,238 files, so it would survive losing more than half.
  * Deciding the right instrument is its own task — `uniml/BACKLOG.md`, "the `> 500` corpus floors
  * guard a number that has more than doubled" — and it is filed separately BECAUSE the obvious fix
  * (freeze the exact count, as the UniML CI aggregate check now does) is wrong here: that corpus is
  * a closed set that changes deliberately, this one grows whenever anyone adds a `.ssc` file.
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

  /** Every `.ssc` file in the repository, sorted, with the floor asserted.
    *
    * Sorted because a `Files.walk` order is filesystem-dependent, and a census that reports "the
    * first five offenders" would otherwise name different files on different machines.
    */
  def files(root: Path = repoRoot): Vector[Path] =
    val found = paths(root)
    assert(found.sizeIs > 500, s"only ${found.size} .ssc found — the sweep silently shrank")
    found

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
