// Scripted test: the linked ScalaScript jar reaches sbt's artifacts.
//
// Before the wiring, `sscLink` produced a jar and nothing consumed it -- so a ScalaScript library
// could not be published from sbt at all. This publishes LOCALLY (~/.ivy2) and looks for the
// classified jar; nothing here talks to Maven.

enablePlugins(ScalascriptInteropPlugin)

organization := "com.example.ssctest"
name         := "publish-artifacts-demo"
version      := "0.0.1-SCRIPTED"
scalaVersion := "3.5.2"

sscArtifactDir := baseDirectory.value / ".ssc-artifacts"
sscBinary      := (baseDirectory.value / "mock-ssc").getAbsolutePath

// The mock writes the linked jar wherever it is told; point at the default so the task has a file.
TaskKey[Unit]("checkSscArtifact") := {
  val ivy = file(sys.props("user.home")) / ".ivy2" / "local" / organization.value /
            (name.value + "_3") / version.value / "jars"
  val expected = name.value + "_3-ssc.jar"
  val found = Option(ivy.listFiles()).getOrElse(Array.empty).map(_.getName).toSeq
  if (!found.contains(expected))
    sys.error(s"the ssc-classified jar is not published: expected $expected in $ivy, found: " +
              (if (found.isEmpty) "<nothing>" else found.mkString(", ")))
}
