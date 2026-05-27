package scalascript.wallet.vault.ledger.js.cardano

import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext
import scalascript.wallet.vault.ledger.js.{MockWebHidDevice, WebHidLedgerTransport}

class CardanoAppTest extends AsyncFunSuite:
  implicit override def executionContext: ExecutionContext = ExecutionContext.global

  private def ok(payload: Array[Byte]): Array[Byte] = payload ++ Array[Byte](0x90.toByte, 0x00)

  test("protected header encodes EdDSA alg and address"):
    val header = CardanoCip8.protectedHeader(Array[Byte](1, 2, 3))
    assert(header.nonEmpty)
    assert(header.contains("address".getBytes("UTF-8").head))

  test("sigStructure includes Signature1 context and payload"):
    val payload = "hello".getBytes("UTF-8")
    val sig = CardanoCip8.sigStructure(Array[Byte](0xA1.toByte), payload)
    assert(new String(sig, "ISO-8859-1").contains("Signature1"))
    assert(sig.takeRight(payload.length).sameElements(payload))

  test("COSE_Key carries the Ed25519 public key"):
    val key = Array.fill[Byte](32)(0x7a)
    val cose = CardanoCip8.coseKeyEd25519(key)
    assert(cose.takeRight(32).sameElements(key))

  test("decodePublicKey accepts raw and length-prefixed responses"):
    val key = Array.tabulate[Byte](32)(_.toByte)
    assert(CardanoApp.decodePublicKey(key).sameElements(key))
    assert(CardanoApp.decodePublicKey(Array[Byte](32) ++ key).sameElements(key))

  test("signCip8 sends get-public-key and sign APDUs"):
    val device = MockWebHidDevice()
    val key = Array.tabulate[Byte](32)(_.toByte)
    val signature = Array.fill[Byte](64)(0x55)
    device.queueApdu(ok(Array[Byte](32) ++ key))
    device.queueApdu(ok(signature))
    val transport = WebHidLedgerTransport(device)
    for
      _ <- transport.open()
      proof <- CardanoApp.signCip8(
        transport,
        "m/1852'/1815'/0'/0/0",
        address = Array[Byte](1, 2, 3),
        payload = "payload".getBytes("UTF-8"),
      )
    yield
      assert(proof.signature.sameElements(signature))
      assert(proof.publicKey.sameElements(key))
      assert(proof.coseSign1.nonEmpty)
      assert(proof.coseKey.nonEmpty)
      assert(device.sentReports.size >= 2)
