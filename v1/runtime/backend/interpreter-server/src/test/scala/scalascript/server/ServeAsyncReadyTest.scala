package scalascript.server

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetSocketAddress, ServerSocket, Socket}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Interpreter, InterpreterServerSupport}

/** specs/serveasync-ready.md — `serveAsync(port)` must not return until the
 *  listen socket is actually bound, so a client (or driver test) that connects
 *  immediately afterwards no longer races the bind (ConnectException /
 *  ClosedChannelException).  Exercised at the server-support seam that
 *  `serveAsync` delegates to. */
class ServeAsyncReadyTest extends AnyFunSuite:

  private def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort finally s.close()

  private def support: InterpreterServerSupport = InterpreterServerSupport.current
  private def sink: PrintStream = new PrintStream(new ByteArrayOutputStream())

  test("startServer(async=true) returns only after the port is bound — immediate connect succeeds"):
    val interp = Interpreter(out = sink)
    val port   = freePort()
    try
      support.startServer(interp, port, ".", sink, "", "", async = true)
      // No warmup/retry loop: the bind has completed by the time the call returns.
      val sock = new Socket()
      try sock.connect(new InetSocketAddress("127.0.0.1", port), 2000)
      finally sock.close()
      assert(sock.isConnected || true) // connect() would have thrown on failure
    finally
      support.stopServer()

  test("startServer(async=true) on a contended port returns within a bounded time, never hangs"):
    // The invariant busi relies on: serveAsync must not block forever.  Whether
    // the platform reports a bind conflict (then we surface a `serveAsync(port)`
    // RuntimeException) or tolerates it via socket reuse (then we return after the
    // bind), the call must complete well inside the readiness timeout.
    val interp = Interpreter(out = sink)
    val taken  = new ServerSocket()
    taken.bind(new InetSocketAddress("127.0.0.1", 0))
    val port = taken.getLocalPort
    try
      val started = System.nanoTime()
      try support.startServer(interp, port, ".", sink, "", "", async = true)
      catch case ex: RuntimeException =>
        assert(ex.getMessage.contains(s"serveAsync($port)"), s"unexpected error: ${ex.getMessage}")
      val elapsedMs = (System.nanoTime() - started) / 1000000
      assert(elapsedMs < 16000, s"serveAsync must not hang on a contended port; took ${elapsedMs}ms")
    finally
      support.stopServer()
      taken.close()

  test("two concurrent startServer(async=true) on different ports both bind (busi federation A↔B)"):
    // busi peer-ceremony runs two servers in one process. The block-until-bind
    // rewrite serialized the starts and exposed a latent single-server limit
    // (the cached singleton backend's `if _running then return` no-op'd the
    // second start). Both ports must accept a connection.
    val interp = Interpreter(out = sink)
    val portA  = freePort()
    val portB  = freePort()
    try
      support.startServer(interp, portA, ".", sink, "", "", async = true)
      support.startServer(interp, portB, ".", sink, "", "", async = true)
      for (p, label) <- List((portA, "A"), (portB, "B")) do
        val sock = new Socket()
        try sock.connect(new InetSocketAddress("127.0.0.1", p), 2000)
        catch case e: Throwable =>
          fail(s"server $label on port $p did not bind: ${e.getClass.getSimpleName} ${e.getMessage}")
        finally sock.close()
    finally
      support.stopServer()

  test("async TLS startup surfaces a bad-cert failure fast instead of swallowing it"):
    // The TLS serveAsync(port, tls(...)) form routes through the same async
    // readiness path now.  Previously it ran on a detached thread that swallowed
    // exceptions — a bad cert silently never started and the caller returned as
    // if successful.  Now the failure surfaces synchronously and does not hang.
    val interp = Interpreter(out = sink)
    val port   = freePort()
    try
      val started = System.nanoTime()
      val ex = intercept[RuntimeException](
        support.startServer(interp, port, ".", sink,
          "/no/such/cert.pem", "/no/such/key.pem", async = true))
      val elapsedMs = (System.nanoTime() - started) / 1000000
      assert(ex.getMessage.contains(s"serveAsync($port)"), s"unexpected error: ${ex.getMessage}")
      assert(elapsedMs < 16000, s"bad-cert TLS startup must not hang; took ${elapsedMs}ms")
    finally
      support.stopServer()
