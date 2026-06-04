//> using scala 3.7.2
//> using dep com.lihaoyi::os-lib:0.11.4
//> using dep com.lihaoyi::upickle:4.4.2

// A 50-line backend plugin that returns a canned TextOutput regardless
// of the input.  Smoke-test for the SubprocessBackend wire protocol —
// proves the round-trip works end-to-end without needing real codegen.
//
// Usage (manual):
//   echo '{"method":"describe","id":1}' | scala-cli plugin.scala
//
// Discovery: drop a plugin.yaml next to this file pointing `executable`
// at the resulting binary (or at `scala-cli run plugin.scala`).
//
// See specs/backend-spi-protocol.md for the wire shape.

import scala.io.StdIn
import upickle.default.*

case class Request(method: String, params: ujson.Value = ujson.Obj(), id: Long = 0L) derives ReadWriter
case class Response(id: Long = 0L, result: Option[ujson.Value] = None, error: Option[ResponseError] = None) derives ReadWriter
case class ResponseError(code: Int, message: String) derives ReadWriter

@main def main(): Unit =
  var line = StdIn.readLine()
  while line != null do
    val req = read[Request](line)
    val resp = req.method match
      case "describe" =>
        Response(id = req.id, result = Some(ujson.Obj(
          "id"              -> "canned",
          "displayName"     -> "Canned (smoke)",
          "spiVersion"      -> "0.1.0",
          "role"            -> "backend",
          "acceptedSources" -> ujson.Arr(),
          "features"        -> ujson.Arr("MutableState", "PatternMatching"),
          "outputs"         -> ujson.Arr("ExecutionResult")
        )))
      case "compile" =>
        Response(id = req.id, result = Some(ujson.Obj(
          "kind"     -> "text",
          "code"     -> "// canned-backend produced this",
          "language" -> "text"
        )))
      case "shutdown" =>
        Response(id = req.id, result = Some(ujson.Obj("ok" -> true)))
      case other =>
        Response(id = req.id, error = Some(ResponseError(-32601, s"unknown method: $other")))
    println(write(resp))
    if req.method == "shutdown" then System.exit(0)
    line = StdIn.readLine()
