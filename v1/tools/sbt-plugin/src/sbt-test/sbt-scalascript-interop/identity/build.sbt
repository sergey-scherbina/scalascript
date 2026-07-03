// Scripted test: Tier-5 identity artifact produces no facade file.

enablePlugins(ScalascriptInteropPlugin)

sscArtifactDir := baseDirectory.value / ".ssc-artifacts"
sscBinary      := (baseDirectory.value / "mock-ssc").getAbsolutePath

scalaVersion := "3.5.2"
