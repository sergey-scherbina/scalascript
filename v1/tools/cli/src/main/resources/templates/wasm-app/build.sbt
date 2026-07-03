ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "${packageName}"
ThisBuild / version := "${version}"

enablePlugins(ScalascriptInteropPlugin)

name := "${name}"

sscBackend := "wasm"
sscBinary := sys.env.getOrElse("SSC_BINARY", "ssc")
