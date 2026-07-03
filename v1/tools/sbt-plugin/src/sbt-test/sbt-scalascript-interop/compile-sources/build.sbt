enablePlugins(ScalascriptInteropPlugin)

scalaVersion := "3.5.2"

sscBinary := (baseDirectory.value / "mock-ssc").getAbsolutePath
Compile / sscArtifactDir := target.value / "ssc-artifacts"
