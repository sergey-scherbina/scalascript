package scalascript.wallet.vault.ledger

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

class DashboardTest extends AnyFunSuite:

  /** Build a synthetic dashboard `getAppAndVersion` response. */
  private def appInfoPayload(name: String, version: String): Array[Byte] =
    val n = name.getBytes("UTF-8"); val v = version.getBytes("UTF-8")
    val out = new Array[Byte](1 + 1 + n.length + 1 + v.length)
    out(0) = 0x01
    out(1) = n.length.toByte
    System.arraycopy(n, 0, out, 2, n.length)
    out(2 + n.length) = v.length.toByte
    System.arraycopy(v, 0, out, 2 + n.length + 1, v.length)
    out

  test("parse decodes the dashboard payload"):
    val info = Dashboard.parse(appInfoPayload("Ethereum", "1.10.4"))
    assert(info.name    == "Ethereum")
    assert(info.version == "1.10.4")

  test("getAppName sends B0 01 00 00 and parses the response"):
    val t = MockTransport()
    t.queueOk(appInfoPayload("Solana", "1.4.1"))
    val info = Await.result(Dashboard.getAppName(t), 1.second)
    assert(info.name    == "Solana")
    assert(info.version == "1.4.1")
    assert(t.recorded.size == 1)
    val cmd = t.recorded.head
    assert((cmd(0) & 0xff) == 0xB0)
    assert((cmd(1) & 0xff) == 0x01)
    assert((cmd(2) & 0xff) == 0x00)
    assert((cmd(3) & 0xff) == 0x00)
    assert((cmd(4) & 0xff) == 0x00)

  test("requireApp fails with AppSwitchRequired on mismatch"):
    val t = MockTransport()
    t.queueOk(appInfoPayload("Bitcoin", "2.2.4"))
    val ex = intercept[AppSwitchRequired]:
      Await.result(Dashboard.requireApp(t, "Ethereum"), 1.second)
    assert(ex.currentApp  == "Bitcoin")
    assert(ex.requiredApp == "Ethereum")

  test("requireApp succeeds on match"):
    val t = MockTransport()
    t.queueOk(appInfoPayload("Ethereum", "1.10.4"))
    val info = Await.result(Dashboard.requireApp(t, "Ethereum"), 1.second)
    assert(info.name == "Ethereum")
