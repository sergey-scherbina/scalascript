package scalascript.compiler.plugin.tcp

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

/** specs/std-tcp.md — drives the raw-TCP intrinsics end-to-end through the
 *  interpreter. A single ScalaScript snippet opens a listener, connects a client,
 *  accepts the queued connection, and exchanges a line in each direction — all
 *  single-threaded (connect completes via the OS backlog before accept runs), so
 *  no concurrency is needed to exercise both server and client paths. */
class TcpPluginTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(TcpInterpreterPlugin()))

  test("tcp listen → connect → accept → bidirectional line I/O"):
    val r = interp.eval(
      """{
        |  val srv = tcpListen(19877)
        |  val cli = tcpConnect("127.0.0.1", 19877)
        |  val con = tcpAccept(srv)
        |  tcpSend(cli, "PING\r\n")
        |  val a = tcpRecvLine(con)
        |  tcpSend(con, "PONG\r\n")
        |  val b = tcpRecvLine(cli)
        |  tcpClose(cli)
        |  tcpClose(con)
        |  tcpStop(srv)
        |  "" + (srv >= 0) + "|" + (cli >= 0) + "|" + (con >= 0) + "|" + a + "|" + b
        |}""".stripMargin).asInstanceOf[String]
    assert(r == "true|true|true|PING|PONG", s"unexpected round-trip result: $r")

  test("tcpConnect to a dead port returns -1 (bounded, never hangs)"):
    val r = interp.eval("""tcpConnect("127.0.0.1", 1)""").asInstanceOf[Long]
    assert(r == -1L, s"expected -1 for a refused connection, got: $r")
