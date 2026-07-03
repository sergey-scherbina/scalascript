// Scripted test: two .scim files for the same package are merged into one facade file.

enablePlugins(ScalascriptInteropPlugin)

sscArtifactDir := baseDirectory.value / ".ssc-artifacts"
sscBinary      := (baseDirectory.value / "mock-ssc").getAbsolutePath

scalaVersion := "3.5.2"
