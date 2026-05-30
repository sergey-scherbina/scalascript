package scalascript

object TestPaths:

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
