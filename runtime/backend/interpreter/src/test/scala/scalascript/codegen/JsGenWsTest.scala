package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scalascript.server.WsFraming

import java.net.{ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/** Generate a tiny `onWebSocket("/echo")` program with JsGen, hand it to
 *  `node`, and verify the WS upgrade + echo round-trip works against the
 *  Node-side runtime (no `ws` package — pure stdlib).
 *
 *  Skipped at runtime if `node` is unavailable; everything else uses
 *  only the JVM stdlib. */
class JsGenWsTest extends AnyFunSuite with Matchers:

  test("echo round-trip — JsGen runtime via node") {
    assume(hasNode, "node binary not available on PATH")

    val script = """# Test
```scalascript
onWebSocket("/echo") { ws =>
  ws.onMessage { msg =>
    ws.send("echo: " + msg)
  }
}

serve(__PORT__)
```
"""
    // Pick a free port on the JVM side, then patch the script before
    // generating JS — otherwise we'd race with anything else listening.
    val freePort = pickFreePort()
    val module = Parser.parse(script.replace("__PORT__", freePort.toString))
    val jsBody = JsGen.generate(module)
    val full   = JsRuntime + "\n" + jsBody

    val tmp = java.io.File.createTempFile("jsgen-ws-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, full.getBytes(StandardCharsets.UTF_8))

    val nodeLog = java.io.File.createTempFile("jsgen-ws-node-", ".log")
    nodeLog.deleteOnExit()
    val proc = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .redirectOutput(nodeLog)
      .start()

    try
      // Wait for the listener to come up — try-and-retry the TCP connect
      // for up to 5s.  Node's `listen` log lands on stderr but timing
      // is unreliable, so we probe.
      val sock =
        try openWithRetry("127.0.0.1", freePort, 5.seconds)
        catch case e: Throwable =>
          val out = scala.io.Source.fromFile(nodeLog).mkString
          throw new RuntimeException(s"node didn't bind:\n$out", e)
      sock.setSoTimeout(5000)
      try
        val out = sock.getOutputStream
        val in  = sock.getInputStream

        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val req =
          "GET /echo HTTP/1.1\r\n" +
          "Host: 127.0.0.1\r\n" +
          "Upgrade: websocket\r\n" +
          "Connection: Upgrade\r\n" +
          s"Sec-WebSocket-Key: $key\r\n" +
          "Sec-WebSocket-Version: 13\r\n\r\n"
        out.write(req.getBytes(StandardCharsets.US_ASCII))
        out.flush()

        readHttpLine(in) should startWith("HTTP/1.1 101")
        var accept: String = null
        var line = readHttpLine(in)
        while line != null && line.nonEmpty do
          if line.toLowerCase.startsWith("sec-websocket-accept:") then
            accept = line.split(":", 2)(1).trim
          line = readHttpLine(in)
        accept shouldBe WsFraming.acceptKey(key)

        val payload = "hi".getBytes(StandardCharsets.UTF_8)
        val mask    = Array[Byte](1, 2, 3, 4)
        val masked  = payload.zipWithIndex.map { (b, i) => (b ^ mask(i % 4)).toByte }
        val frame   = Array[Byte](0x81.toByte, (0x80 | payload.length).toByte) ++ mask ++ masked
        out.write(frame); out.flush()

        val replyBuf = new Array[Byte](64)
        val n        = readAtLeast(in, replyBuf, 2, 5.seconds)
        val parsed   = WsFraming.tryParse(replyBuf, 0, n).get
        parsed.opcode shouldBe WsFraming.Opcode.Text
        parsed.textPayload shouldBe "echo: hi"
      finally
        try sock.close() catch case _: Throwable => ()
    finally
      proc.destroyForcibly()
      proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
      tmp.delete()
  }

  private def hasNode: Boolean =
    try
      val p = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
      p.waitFor(); p.exitValue() == 0
    catch case _: Throwable => false

  private def pickFreePort(): Int =
    val s = ServerSocket(0)
    val p = s.getLocalPort
    s.close(); p

  private def openWithRetry(host: String, port: Int, within: FiniteDuration): Socket =
    val deadline = System.nanoTime() + within.toNanos
    while System.nanoTime() < deadline do
      try return Socket(host, port)
      catch case _: java.io.IOException => Thread.sleep(50)
    throw java.net.ConnectException(s"timed out waiting for $host:$port")

  private def readHttpLine(in: java.io.InputStream): String =
    val sb = StringBuilder()
    var prev = -1
    while true do
      val b = in.read()
      if b < 0 then return sb.toString
      if prev == '\r' && b == '\n' then return sb.toString.dropRight(1)
      sb.append(b.toChar)
      prev = b
    ""

  private def readAtLeast(
      in:       java.io.InputStream,
      buf:      Array[Byte],
      minBytes: Int,
      within:   FiniteDuration
  ): Int =
    val deadline = System.nanoTime() + within.toNanos
    var off      = 0
    while off < minBytes && System.nanoTime() < deadline do
      val n = in.read(buf, off, buf.length - off)
      if n < 0 then return off
      off += n
    off
