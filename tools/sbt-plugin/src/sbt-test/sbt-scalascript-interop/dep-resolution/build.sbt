import sbt.librarymanagement.CrossVersion

enablePlugins(ScalascriptInteropPlugin)

scalaVersion := "3.5.2"

sscBinary := (baseDirectory.value / "mock-ssc").getAbsolutePath

// Phase 5 dep-resolution: assert front-matter `dependencies:` Maven coords were
// lifted into sscManagedDependencies / libraryDependencies. Reads settings only —
// no Coursier resolution, so the test needs no network.
TaskKey[Unit]("checkDeps") := {
  val managed = sscManagedDependencies.value
  val libs    = libraryDependencies.value
  val log     = streams.value.log
  log.info("[checkDeps] sscManagedDependencies = " + managed.mkString(", "))

  // Java dep:  dep:io.example:demo:1.0.0  ->  io.example % demo % 1.0.0
  assert(
    managed.exists(m => m.organization == "io.example" && m.name == "demo" && m.revision == "1.0.0"),
    "expected io.example:demo:1.0.0 in sscManagedDependencies, got: " + managed.mkString(", ")
  )

  // Scala-cross dep:  dep:com.lihaoyi::os-lib:0.11.4  ->  com.lihaoyi %% os-lib % 0.11.4
  val osLib = managed.find(m => m.organization == "com.lihaoyi" && m.name == "os-lib")
  assert(
    osLib.exists(_.revision == "0.11.4"),
    "expected com.lihaoyi:os-lib:0.11.4 in sscManagedDependencies, got: " + managed.mkString(", ")
  )
  assert(
    osLib.exists(_.crossVersion match { case _: CrossVersion.Binary => true; case _ => false }),
    "expected os-lib (::) to carry a binary CrossVersion (%%), got: " + osLib.map(_.crossVersion)
  )

  // a local .ssc path dependency value must be ignored (not a Maven coordinate)
  assert(
    !managed.exists(_.name == "helper"),
    "a local .ssc path leaked into managed deps: " + managed.mkString(", ")
  )

  // both lifted into libraryDependencies
  assert(
    libs.exists(m => m.organization == "io.example" && m.name == "demo"),
    "demo missing from libraryDependencies"
  )
  assert(
    libs.exists(m => m.organization == "com.lihaoyi" && m.name == "os-lib"),
    "os-lib missing from libraryDependencies"
  )

  log.info("[checkDeps] OK")
}
