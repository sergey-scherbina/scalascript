// Scripted test: sbt must SEE the ssc compile — skip it when nothing changed, redo it when
// something did. A cache that only ever skips is not a speed-up, it is staleness.
enablePlugins(ScalascriptInteropPlugin)
scalaVersion := "3.5.2"
sscBinary := (baseDirectory.value / "mock-ssc").getAbsolutePath

TaskKey[Unit]("resetCalls") := IO.write(baseDirectory.value / "calls.log", "")

InputKey[Unit]("expectBuilds") := {
  import complete.DefaultParsers._
  val want = spaceDelimited("<n>").parsed.head.toInt
  val log = baseDirectory.value / "calls.log"
  val got = IO.readLines(log).count(_.startsWith("build"))
  if (got != want) sys.error(s"expected ssc forked for `build` $want time(s), saw $got")
}

TaskKey[Unit]("touchSource") := {
  val f = baseDirectory.value / "src" / "main" / "scalascript" / "demo.ssc"
  IO.write(f, IO.read(f) + "\n// touched\n")
}
