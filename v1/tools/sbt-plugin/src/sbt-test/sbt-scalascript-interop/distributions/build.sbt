// Scripted test: the distribution tasks exist, call the right ssc command, and REFUSE to guess.
enablePlugins(ScalascriptInteropPlugin)
scalaVersion := "3.5.2"
sscBinary := (baseDirectory.value / "mock-ssc").getAbsolutePath

TaskKey[Unit]("checkJvmCall") := {
  val calls = IO.readLines(baseDirectory.value / "calls.log")
  val line = calls.find(_.startsWith("build-jvm")).getOrElse(sys.error("ssc build-jvm was never called"))
  // build-jvm takes MANY sources: both must be on the command line.
  if (!line.contains("demo.ssc") || !line.contains("other.ssc"))
    sys.error(s"build-jvm did not receive both sources: $line")
  val jar = (Compile / sscDistDir).value / (moduleName.value + ".jar")
  if (!jar.exists()) sys.error(s"no jar at $jar")
}

TaskKey[Unit]("checkRustRefused") := {
  val calls = IO.readLines(baseDirectory.value / "calls.log")
  if (calls.exists(_.startsWith("build-rust")))
    sys.error("build-rust ran with two candidate entry points — it must refuse, not pick one")
}

TaskKey[Unit]("checkRustCall") := {
  val calls = IO.readLines(baseDirectory.value / "calls.log")
  val line = calls.find(_.startsWith("build-rust")).getOrElse(sys.error("ssc build-rust was never called"))
  // build-rust takes exactly ONE file: the chosen entry, and not the other one.
  if (!line.contains("demo.ssc") || line.contains("other.ssc"))
    sys.error(s"build-rust got the wrong entry point: $line")
}

TaskKey[Unit]("checkBundleCall") := {
  val calls = IO.readLines(baseDirectory.value / "calls.log")
  val line = calls.find(_.startsWith("bundle")).getOrElse(sys.error("ssc bundle was never called"))
  if (!line.contains("demo.ssc") || !line.contains("other.ssc"))
    sys.error(s"bundle did not receive both sources: $line")
  val pkg = (Compile / sscDistDir).value / (moduleName.value + ".sscpkg")
  if (!pkg.exists()) sys.error(s"no .sscpkg at $pkg")
}

TaskKey[Unit]("checkEmitLibCall") := {
  val calls = IO.readLines(baseDirectory.value / "calls.log")
  val line = calls.find(_.startsWith("emit-lib")).getOrElse(sys.error("ssc emit-lib was never called"))
  // The project's own version must be passed: letting the CLI default to 0.1.0 would ship a lib
  // whose version disagrees with what sbt publishes.
  if (!line.contains("--version " + version.value))
    sys.error(s"emit-lib did not receive the project version ${version.value}: $line")
  val dir = (Compile / sscDistDir).value / (moduleName.value + "-lib")
  if (!dir.isDirectory) sys.error(s"emit-lib produced no directory at $dir")
}
