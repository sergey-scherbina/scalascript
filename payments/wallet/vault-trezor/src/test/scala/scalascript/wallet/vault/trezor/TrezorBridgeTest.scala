package scalascript.wallet.vault.trezor

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.Await
import scala.concurrent.duration.*

class TrezorBridgeTest extends AnyFunSuite:

  test("MockTrezorBridge.version returns mock version") {
    val bridge = MockTrezorBridge()
    assert(Await.result(bridge.version(), 5.seconds) == "mock-2.0.0")
  }

  test("MockTrezorBridge.enumerate returns one mock device") {
    val bridge = MockTrezorBridge()
    val devices = Await.result(bridge.enumerate(), 5.seconds)
    assert(devices.size == 1)
    assert(devices.head.path == "mock-path-0")
    assert(devices.head.session.isEmpty)
  }

  test("MockTrezorBridge.acquire returns session") {
    val bridge = MockTrezorBridge()
    val session = Await.result(bridge.acquire("mock-path-0", None), 5.seconds)
    assert(session.startsWith("mock-session-"))
  }

  test("MockTrezorBridge.acquire increments session counter") {
    val bridge = MockTrezorBridge()
    val s1 = Await.result(bridge.acquire("mock-path-0", None), 5.seconds)
    val s2 = Await.result(bridge.acquire("mock-path-0", None), 5.seconds)
    assert(s1 != s2)
  }

  test("MockTrezorBridge.release succeeds") {
    val bridge = MockTrezorBridge()
    val session = Await.result(bridge.acquire("mock-path-0", None), 5.seconds)
    Await.result(bridge.release(session), 5.seconds)  // no exception
  }

  test("MockTrezorBridge.call records call and returns queued response") {
    val bridge = MockTrezorBridge()
    bridge.enqueueFeatures()
    val session = Await.result(bridge.acquire("mock-path-0", None), 5.seconds)
    val resp = Await.result(bridge.call(session, TrezorMessageType.Initialize), 5.seconds)
    assert(resp.messageType == TrezorMessageType.Features)
    assert(resp.message.obj("initialized").bool)
    assert(bridge.calls.size == 1)
    assert(bridge.calls.head.messageType == TrezorMessageType.Initialize)
  }

  test("MockTrezorBridge.call fails when no response queued") {
    val bridge = MockTrezorBridge()
    val session = Await.result(bridge.acquire("mock-path-0", None), 5.seconds)
    val ex = intercept[RuntimeException] {
      Await.result(bridge.call(session, TrezorMessageType.Initialize), 5.seconds)
    }
    assert(ex.getMessage.contains("Initialize"))
  }

  test("MockTrezorBridge.enqueuePublicKey produces correct structure") {
    val bridge = MockTrezorBridge()
    bridge.enqueuePublicKey("xpub6test", "02" + "ab" * 32)
    val session = Await.result(bridge.acquire("mock-path-0", None), 5.seconds)
    val resp = Await.result(bridge.call(session, TrezorMessageType.GetPublicKey), 5.seconds)
    assert(resp.messageType == TrezorMessageType.PublicKey)
    assert(resp.message.obj("xpub").str == "xpub6test")
    assert(resp.message.obj("node").obj("public_key").str == "02" + "ab" * 32)
  }

  test("MockTrezorBridge.enqueueEthSignature produces correct structure") {
    val bridge = MockTrezorBridge()
    bridge.enqueueEthSignature("0x1234", "aabbcc" * 10 + "dd" * 1)
    val session = Await.result(bridge.acquire("mock-path-0", None), 5.seconds)
    val resp = Await.result(bridge.call(session, TrezorMessageType.EthereumSignMessage), 5.seconds)
    assert(resp.messageType == TrezorMessageType.EthereumMessageSig)
    assert(resp.message.obj("address").str == "0x1234")
  }

  test("Bip32.parse: default Ethereum path") {
    val path = Bip32.parse("m/44'/60'/0'/0/0")
    assert(path.length == 5)
    assert(path(0) == (44 | Bip32.Hardened))
    assert(path(1) == (60 | Bip32.Hardened))
    assert(path(2) == (0  | Bip32.Hardened))
    assert(path(3) == 0)
    assert(path(4) == 0)
  }

  test("Bip32.parse: non-hardened path") {
    val path = Bip32.parse("m/0/1/2")
    assert(path.sameElements(Array(0, 1, 2)))
  }
