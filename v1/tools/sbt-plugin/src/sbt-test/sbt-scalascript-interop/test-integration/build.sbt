enablePlugins(ScalascriptInteropPlugin)

scalaVersion := "3.5.2"

sscBinary := (baseDirectory.value / "mock-ssc").getAbsolutePath
Test / sscTestResultsDir := target.value / "ssc-test-results"
