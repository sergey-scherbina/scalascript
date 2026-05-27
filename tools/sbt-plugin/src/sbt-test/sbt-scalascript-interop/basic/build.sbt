// Scripted test: basic facade generation from a legacy Tier-1 .scim artifact.
// The test uses a local mock-ssc script so it does not require a real ssc install.

enablePlugins(ScalascriptInteropPlugin)

sscArtifactDir := baseDirectory.value / ".ssc-artifacts"

// Point to the local mock-ssc so the scripted test works without ssc on PATH.
sscBinary := (baseDirectory.value / "mock-ssc").getAbsolutePath

scalaVersion := "3.5.2"
