// Scripted test: artifact dir doesn't exist — plugin warns and produces no sources.

enablePlugins(ScalascriptInteropPlugin)

// Point at a non-existent directory — plugin should warn rather than error.
sscArtifactDir := baseDirectory.value / "no-such-dir"
sscBinary      := (baseDirectory.value / "mock-ssc").getAbsolutePath

scalaVersion := "3.5.2"
