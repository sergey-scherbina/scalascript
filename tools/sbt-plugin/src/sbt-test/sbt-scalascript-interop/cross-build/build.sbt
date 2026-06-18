enablePlugins(ScalascriptInteropPlugin)

scalaVersion := "3.5.2"

sscBinary := (baseDirectory.value / "mock-ssc").getAbsolutePath
Compile / sscArtifactDir := target.value / "ssc-artifacts"

// Cross-build to two backends in one `compile` (sscBackends, spec open-Q #2 = A).
sscBackends := Seq("jvm", "js")

// Assert each per-backend marker carries the right backend id.
TaskKey[Unit]("checkBackends") := {
  val art = (Compile / sscArtifactDir).value
  val jvm = IO.read(art / "jvm" / "compiled.marker")
  val js  = IO.read(art / "js"  / "compiled.marker")
  assert(jvm.contains("backend=jvm"), s"jvm marker wrong: $jvm")
  assert(js.contains("backend=js"),   s"js marker wrong: $js")
  streams.value.log.info("[checkBackends] OK")
}
