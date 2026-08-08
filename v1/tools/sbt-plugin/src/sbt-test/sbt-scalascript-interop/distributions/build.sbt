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
