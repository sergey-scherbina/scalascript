package scalascript

/** Test-fixture helper: resolve the repository root regardless of the
 *  shell's working directory.
 *
 *  Previously every test that imports `std/...ssc` from a Markdown link
 *  did:
 *
 *    private val repoRoot = TestPaths.repoRoot
 *
 *  This assumes sbt was launched from the `backend-interpreter/`
 *  subproject so that `os.pwd / os.up` lands on the repo root.  It
 *  silently breaks when:
 *
 *    - sbt is launched from the repo root itself (then `repoRoot`
 *      points at the parent directory of the repo).
 *    - tests run from a git worktree under `.claude/worktrees/<name>/`
 *      (then `repoRoot` points at `.claude/worktrees/` — no `std/`
 *      anywhere, every `[Symbol](std/x.ssc)` import 404s).
 *
 *  Walking up from `os.pwd` until we hit a directory containing
 *  `build.sbt` is the robust resolution.  We also accept the rooted
 *  path the sbt baseDirectory sets via the `user.dir` JVM property —
 *  if running tests with `-Duser.dir=<repo>`, `os.pwd` already points
 *  at the right place and the loop terminates after zero iterations. */
object TestPaths:

  /** The directory containing the project's `build.sbt`.  Computed
   *  once at class-load time and cached. */
  lazy val repoRoot: os.Path =
    var p = os.pwd
    while !os.exists(p / "build.sbt") do
      val up = p / os.up
      if up == p then
        throw new RuntimeException(
          s"could not locate repo root (no `build.sbt` found walking up from ${os.pwd})"
        )
      p = up
    p
