package scalascript.dap

import org.scalatest.funsuite.AnyFunSuite
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import ujson.*

class DapFramingTest extends AnyFunSuite:
  test("write then read round-trips JSON"):
    val out = ByteArrayOutputStream()
    val msg = Obj("seq" -> Num(1), "type" -> Str("request"), "command" -> Str("initialize"))
    DapProtocol.writeMessage(out, msg)
    val in  = ByteArrayInputStream(out.toByteArray)
    val got = DapProtocol.readMessage(in)
    assert(got("seq").num.toInt == 1)
    assert(got("command").str == "initialize")

  test("write then read preserves nested body"):
    val out = ByteArrayOutputStream()
    val msg = Obj("seq" -> Num(2), "type" -> Str("event"), "event" -> Str("initialized"), "body" -> Obj())
    DapProtocol.writeMessage(out, msg)
    val in = ByteArrayInputStream(out.toByteArray)
    val got = DapProtocol.readMessage(in)
    assert(got("event").str == "initialized")

  test("two messages in sequence"):
    val out = ByteArrayOutputStream()
    val m1 = Obj("seq" -> Num(1), "type" -> Str("request"), "command" -> Str("a"))
    val m2 = Obj("seq" -> Num(2), "type" -> Str("request"), "command" -> Str("b"))
    DapProtocol.writeMessage(out, m1)
    DapProtocol.writeMessage(out, m2)
    val in = ByteArrayInputStream(out.toByteArray)
    val r1 = DapProtocol.readMessage(in)
    val r2 = DapProtocol.readMessage(in)
    assert(r1("command").str == "a")
    assert(r2("command").str == "b")
