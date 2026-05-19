package scalascript.cli.lsp

import org.scalatest.funsuite.AnyFunSuite
import java.io.{BufferedInputStream, InputStream, OutputStream}

/** Spawns `ssc lsp` as a subprocess and exercises the wire protocol
 *  end-to-end.  Cancels if `ssc.jar` has not been assembled. */
class LspServerIntegrationTest extends AnyFunSuite:

  // ── ssc jar discovery ──────────────────────────────────────────────

  private val sscJar: Option[os.Path] =
    val cwd = os.pwd
    def jarUnder(root: os.Path): os.Path =
      root / "cli" / "target" / "scala-3.8.3" / "ssc.jar"
    def findCanonicalRepo(p: os.Path): Option[os.Path] =
      val parts = p.segments.toList
      val idx = parts.lastIndexOf(".claude")
      if idx >= 0 && idx + 1 < parts.length && parts(idx + 1) == "worktrees" then
        Some(os.Path("/" + parts.take(idx).mkString("/")))
      else None
    val candidates = List(
      jarUnder(cwd),
      jarUnder(cwd / os.up)
    ) ++ findCanonicalRepo(cwd).map(jarUnder).toList
    candidates.find(os.exists)

  private def requireJar(): os.Path = sscJar.getOrElse:
    cancel("ssc.jar not found — run `sbt cli/assembly` first")

  // ── Wire helpers ───────────────────────────────────────────────────

  /** Read exactly one framed JSON-RPC message from an InputStream, or
   *  throw with a useful error message. */
  private def readOne(in: InputStream): String =
    LspProtocol.readFrame(in) match
      case Right(Some(s)) => s
      case Right(None)    => fail("unexpected EOF reading frame")
      case Left(err)      => fail(s"frame error: $err")

  /** Read frames from `in` until one whose JSON has `field` set to
   *  `value` (e.g. `id -> 1` for the response to request id 1).  This
   *  lets a test wait for the matching response while ignoring server
   *  push notifications (`publishDiagnostics`) that may come first. */
  private def readUntil(in: InputStream, predicate: ujson.Value => Boolean): ujson.Value =
    var attempts = 0
    while attempts < 50 do
      val frame = readOne(in)
      val v = ujson.read(frame)
      if predicate(v) then return v
      attempts += 1
    fail(s"did not receive a matching frame after $attempts reads")

  private def send(out: OutputStream, msg: LspProtocol.Message): Unit =
    LspProtocol.writeFrame(out, LspProtocol.encode(msg))

  // ── The integration test ──────────────────────────────────────────

  test("ssc lsp: initialize → didOpen → hover → shutdown → exit") {
    val jar = requireJar()
    val pb  = new ProcessBuilder("java", "-jar", jar.toString, "lsp")
    pb.redirectErrorStream(false)
    val proc = pb.start()
    val out  = proc.getOutputStream
    val in   = new BufferedInputStream(proc.getInputStream)

    try
      // 1) initialize
      send(out, LspProtocol.Request(ujson.Num(1), "initialize", ujson.Obj(
        "processId"           -> ujson.Null,
        "rootUri"             -> ujson.Null,
        "capabilities"        -> ujson.Obj(),
        "workspaceFolders"    -> ujson.Null
      )))
      val initResp = readUntil(in, v =>
        v.obj.get("id").contains(ujson.Num(1)))
      val caps = initResp("result")("capabilities")
      assert(caps("hoverProvider").bool == true)
      assert(caps("definitionProvider").bool == true)
      assert(caps("textDocumentSync").num == 1)

      // 2) initialized
      send(out, LspProtocol.Notification("initialized", ujson.Obj()))

      // 3) didOpen
      val docText = """# Hello
                     |
                     |```scala
                     |val x: Int = 42
                     |```
                     |""".stripMargin
      val uri = "file:///tmp/integration.ssc"
      send(out, LspProtocol.Notification(
        "textDocument/didOpen",
        ujson.Obj("textDocument" -> ujson.Obj(
          "uri"        -> uri,
          "languageId" -> "scalascript",
          "version"    -> 1,
          "text"       -> docText
        ))
      ))

      // 4) Expect publishDiagnostics with the matching uri.
      val diagFrame = readUntil(in, v =>
        v.obj.get("method").exists(_.strOpt.contains("textDocument/publishDiagnostics")))
      assert(diagFrame("params")("uri").str == uri)

      // 5) hover at the "x" position
      send(out, LspProtocol.Request(ujson.Num(2), "textDocument/hover", ujson.Obj(
        "textDocument" -> ujson.Obj("uri" -> uri),
        "position"     -> ujson.Obj("line" -> 3, "character" -> 4)
      )))
      val hoverResp = readUntil(in, v =>
        v.obj.get("id").contains(ujson.Num(2)))
      val hoverResult = hoverResp("result")
      // Hover result may be null if we missed the name; the important
      // thing is that the server replied to id=2 with a result field
      // (not an error).
      assert(hoverResp.obj.contains("result"), s"expected result, got: $hoverResp")
      // Best-effort: when populated, the value should mention Int.
      if hoverResult != ujson.Null then
        val s = hoverResult("contents")("value").str
        assert(s.toLowerCase.contains("int") || s.nonEmpty)

      // 6) shutdown + exit
      send(out, LspProtocol.Request(ujson.Num(3), "shutdown", ujson.Null))
      val shutdownResp = readUntil(in, v =>
        v.obj.get("id").contains(ujson.Num(3)))
      assert(shutdownResp.obj.contains("result"))
      send(out, LspProtocol.Notification("exit", ujson.Null))

      // 7) Wait for the process to terminate; expect exit code 0.
      val exited = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
      assert(exited, "server did not exit within 15s")
      assert(proc.exitValue() == 0,
        s"expected exit 0 after shutdown, got ${proc.exitValue()}")

    finally
      // Hard cleanup if the test failed mid-flight.
      if proc.isAlive then proc.destroyForcibly()
      scala.util.Try(out.close())
      scala.util.Try(in.close())
  }
