// Scripted test: the plugin against the REAL ssc, not a mock.
//
// Every other scenario here mocks the binary. That is right for proving the wiring — which command
// is invoked, with which arguments, and what the task returns — and it proves nothing about whether
// the toolchain accepts those arguments at all. The same shape of gap let `ssc new` scaffold a
// perfectly well-formed coordinate for an artifact that did not exist: everything checkable by
// inspection was correct, and the thing did not work.
enablePlugins(ScalascriptInteropPlugin)
scalaVersion := "3.5.2"

// The staged launcher from this checkout. sscRepoRoot in the plugin's build.sbt finds the root by
// walking up to .git, so this survives the tree being reorganised again.
sscBinary := sys.props.getOrElse("ssc.repo.root",
  sys.error("ssc.repo.root was not passed to scripted")) + "/bin/ssc-tools"

TaskKey[Unit]("checkRealArtifacts") := {
  val dir = (Compile / sscArtifactDir).value
  val produced = Option(dir.listFiles()).getOrElse(Array.empty).filter(_.isFile).map(_.getName).toSeq
  if (produced.isEmpty)
    sys.error(s"the real ssc produced no artifacts in $dir")
  // The plugin's own bookkeeping must not masquerade as a compile output. The backend stamp used to
  // live here, so it came back from sscCompile as an artifact, fed sscLink, and would have been
  // published. Mocks could not show that: they write nothing else into this directory.
  val strays = produced.filter(n => n.startsWith(".ssc-") || n == "ssc-backends")
  if (strays.nonEmpty)
    sys.error(s"plugin bookkeeping leaked into the artifact directory: ${strays.mkString(", ")}")
  streams.value.log.info(s"[real-ssc] artifacts: ${produced.sorted.mkString(", ")}")
}
