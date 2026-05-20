package scalascript.wallet.vault.ledger.ethereum

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.wallet.spi.UnlockCredential
import scalascript.wallet.vault.ledger.{Apdu, AppSwitchRequired, Bip32Path, MockTransport}

class EthereumAppSignerTest extends AnyFunSuite:

  /** Build a synthetic `getAppAndVersion` payload for the dashboard. */
  private def appInfoPayload(name: String, version: String = "1.10.4"): Array[Byte] =
    val n = name.getBytes("UTF-8"); val v = version.getBytes("UTF-8")
    val out = new Array[Byte](1 + 1 + n.length + 1 + v.length)
    out(0) = 0x01
    out(1) = n.length.toByte
    System.arraycopy(n, 0, out, 2, n.length)
    out(2 + n.length) = v.length.toByte
    System.arraycopy(v, 0, out, 2 + n.length + 1, v.length)
    out

  /** Build a synthetic GET_PUBLIC_KEY response payload (no chain code).
   *  `[keyLen(1) | key | addrLen(1) | ascii-addr]`. */
  private def getPubKeyPayload(key: Array[Byte], asciiAddr: String): Array[Byte] =
    val addrBytes = asciiAddr.getBytes("US-ASCII")
    val out = new Array[Byte](1 + key.length + 1 + addrBytes.length)
    out(0) = key.length.toByte
    System.arraycopy(key, 0, out, 1, key.length)
    out(1 + key.length) = addrBytes.length.toByte
    System.arraycopy(addrBytes, 0, out, 1 + key.length + 1, addrBytes.length)
    out

  test("getPublicKey APDU shape: [E0 02 00 00 21 <bip32path(33B)>]"):
    val t = MockTransport()
    val key = Array.tabulate[Byte](65)(i => (i % 256).toByte)
    t.queueOk(getPubKeyPayload(key, "0x52908400098527886E0F7030069857D2E4169EE7"))
    val info = Await.result(
      EthereumApp.getPublicKey(t, Bip32Path.DefaultEthereum),
      1.second
    )
    assert(t.recorded.size == 1)
    val apdu = t.recorded.head
    assert(apdu.length == 5 + 21) // 5-byte header + 21 path bytes (1+5*4)
    assert((apdu(0) & 0xff) == 0xE0)
    assert((apdu(1) & 0xff) == 0x02)
    assert((apdu(2) & 0xff) == 0x00) // p1 = don't confirm
    assert((apdu(3) & 0xff) == 0x00) // p2 = no chain code
    assert((apdu(4) & 0xff) == 21)
    // Path body
    val expectedPath = Bip32Path.encode(Bip32Path.DefaultEthereum)
    val pathBody = java.util.Arrays.copyOfRange(apdu, 5, 5 + 21)
    assert(pathBody.sameElements(expectedPath))
    // Surfaced address
    assert(info.address == "0x52908400098527886E0F7030069857D2E4169EE7")
    assert(info.publicKey.length == 65)
    assert(info.chainCode == None)

  test("getPublicKey with requestChainCode=true sets p2 and parses the 32-byte tail"):
    val t = MockTransport()
    val key  = Array.tabulate[Byte](65)(i => (i * 3 % 256).toByte)
    val addr = "0xAbCdEf0123456789abCDef0123456789AbCDef01"
    val cc   = Array.fill[Byte](32)(0x7A)
    val payload = getPubKeyPayload(key, addr) ++ cc
    t.queueOk(payload)
    val info = Await.result(
      EthereumApp.getPublicKey(t, Bip32Path.DefaultEthereum, requestChainCode = true),
      1.second
    )
    assert((t.recorded.head(3) & 0xff) == 0x01) // p2 = include chain code
    assert(info.chainCode.exists(_.sameElements(cc)))

  test("signTransaction chunks a > 200 B RLP payload and returns 65-byte signature"):
    val t = MockTransport()
    // First chunk returns sw=9000 with no payload; final chunk returns [v|r|s].
    val v = 0x1B
    val r = Array.tabulate[Byte](32)(i => (i + 1).toByte)
    val s = Array.tabulate[Byte](32)(i => (i + 100).toByte)
    val sigResp = (v.toByte +: r) ++ s
    // 300-byte RLP payload + 21-byte path = 321 total; chunkSize 255 ⇒ 2 chunks.
    val rlp = new Array[Byte](300)
    var i = 0
    while i < rlp.length do { rlp(i) = (i % 256).toByte; i += 1 }
    t.queueOk()                  // first chunk
    t.queueOk(sigResp)           // last chunk → signature
    val sig = Await.result(EthereumApp.signTransaction(t, Bip32Path.DefaultEthereum, rlp), 1.second)
    assert(sig.v == v)
    assert(sig.r.sameElements(r))
    assert(sig.s.sameElements(s))
    assert(sig.toBytes65.length == 65)
    assert(sig.toBytes65(64) == v.toByte)
    // 2 chunks sent
    assert(t.recorded.size == 2)
    assert((t.recorded(0)(2) & 0xff) == 0x00) // p1 first
    assert((t.recorded(1)(2) & 0xff) == 0x80) // p1 continue
    // First chunk INS is SIGN_TRANSACTION on both
    assert((t.recorded(0)(1) & 0xff) == 0x04)
    assert((t.recorded(1)(1) & 0xff) == 0x04)

  test("signPersonalMessage builds the [path|len(4)|msg] payload"):
    val t = MockTransport()
    val v = 0x1C
    val r = Array.tabulate[Byte](32)(i => i.toByte)
    val s = Array.tabulate[Byte](32)(i => (255 - i).toByte)
    val sigResp = (v.toByte +: r) ++ s
    t.queueOk(sigResp)
    val message = "Hello, ScalaScript!".getBytes("UTF-8")
    val sig = Await.result(
      EthereumApp.signPersonalMessage(t, Bip32Path.DefaultEthereum, message),
      1.second
    )
    assert(sig.v == v)
    // INS == 0x08
    assert(t.recorded.size == 1)
    val apdu = t.recorded.head
    assert((apdu(1) & 0xff) == 0x08)
    val pathLen = 21
    val lcOff   = 4
    val lc      = apdu(lcOff) & 0xff
    val body    = java.util.Arrays.copyOfRange(apdu, 5, 5 + lc)
    // path || msgLen(4) || msg
    assert(body.length == pathLen + 4 + message.length)
    val msgLen =
      ((body(pathLen) & 0xff) << 24) | ((body(pathLen + 1) & 0xff) << 16) |
      ((body(pathLen + 2) & 0xff) <<  8) |  (body(pathLen + 3) & 0xff)
    assert(msgLen == message.length)
    val msgBody = java.util.Arrays.copyOfRange(body, pathLen + 4, body.length)
    assert(msgBody.sameElements(message))

  test("signEip712Hashed sends 21+32+32 bytes and parses the signature"):
    val t = MockTransport()
    val v = 0x1B
    val r = Array.fill[Byte](32)(0x11)
    val s = Array.fill[Byte](32)(0x22)
    t.queueOk((v.toByte +: r) ++ s)
    val domain = Array.fill[Byte](32)(0xAA.toByte)
    val mh     = Array.fill[Byte](32)(0xBB.toByte)
    val sig = Await.result(
      EthereumApp.signEip712Hashed(t, Bip32Path.DefaultEthereum, domain, mh),
      1.second
    )
    assert(sig.r.sameElements(r))
    assert(sig.s.sameElements(s))
    val apdu = t.recorded.head
    assert((apdu(1) & 0xff) == 0x0C)
    assert((apdu(4) & 0xff) == 21 + 32 + 32)
    val body = java.util.Arrays.copyOfRange(apdu, 5, apdu.length)
    val sentDomain = java.util.Arrays.copyOfRange(body, 21, 21 + 32)
    val sentMh     = java.util.Arrays.copyOfRange(body, 21 + 32, 21 + 64)
    assert(sentDomain.sameElements(domain))
    assert(sentMh.sameElements(mh))

  test("parseSignature rejects non-65-byte responses"):
    intercept[IllegalArgumentException]:
      EthereumApp.parseSignature(new Array[Byte](64))
    intercept[IllegalArgumentException]:
      EthereumApp.parseSignature(new Array[Byte](66))

  test("LedgerEthereumVault.getSigner probes getAppName before signing"):
    val t = MockTransport()
    t.open()
    // 1) getAppName for the Ethereum app
    t.queueOk(appInfoPayload("Ethereum"))
    // 2) GET_PUBLIC_KEY response
    val key = Array.tabulate[Byte](65)(i => (i + 1).toByte)
    t.queueOk(getPubKeyPayload(key, "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
    val vault = new LedgerEthereumVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, Bip32Path.DefaultEthereum), 1.second)
    assert(signer.curve == Curve.Secp256k1)
    assert(signer.publicKey.bytes.length == 65)
    // Two APDUs were sent: getAppName then GET_PUBLIC_KEY
    assert(t.recorded.size == 2)
    assert((t.recorded(0)(0) & 0xff) == 0xB0)
    assert((t.recorded(1)(0) & 0xff) == 0xE0)
    assert((t.recorded(1)(1) & 0xff) == 0x02)

  test("LedgerEthereumVault surfaces AppSwitchRequired when wrong app is open"):
    val t = MockTransport()
    t.open()
    t.queueOk(appInfoPayload("Bitcoin", "2.2.4"))
    val vault = new LedgerEthereumVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val ex = intercept[AppSwitchRequired]:
      Await.result(vault.getSigner(Curve.Secp256k1, Bip32Path.DefaultEthereum), 1.second)
    assert(ex.currentApp  == "Bitcoin")
    assert(ex.requiredApp == "Ethereum")

  test("LedgerEthereumVault rejects non-secp256k1 curves"):
    val t     = MockTransport()
    val vault = new LedgerEthereumVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val ex = intercept[UnsupportedOperationException]:
      Await.result(vault.getSigner(Curve.Ed25519, "m/44'/501'/0'/0'"), 1.second)
    assert(ex.getMessage.contains("Secp256k1"))

  test("LedgerEthereumVault refuses signing while locked"):
    val t     = MockTransport()
    val vault = new LedgerEthereumVault(t)
    val ex = intercept[IllegalStateException]:
      Await.result(vault.getSigner(Curve.Secp256k1, Bip32Path.DefaultEthereum), 1.second)
    assert(ex.getMessage.contains("locked"))

  test("LedgerEthereumSigner.sign with Keccak256 hash routes to signTransaction"):
    val t = MockTransport()
    t.open()
    // Pre-prime the dashboard + getPubKey responses needed to construct the signer.
    t.queueOk(appInfoPayload("Ethereum"))
    val key = Array.tabulate[Byte](65)(i => i.toByte)
    t.queueOk(getPubKeyPayload(key, "0x0000000000000000000000000000000000000001"))
    val vault = new LedgerEthereumVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, Bip32Path.DefaultEthereum), 1.second)

    val v = 0x1B
    val r = Array.fill[Byte](32)(0x33)
    val s = Array.fill[Byte](32)(0x44)
    t.queueOk((v.toByte +: r) ++ s)
    val rlp = Array.fill[Byte](80)(0x55)
    val sig = Await.result(signer.sign(rlp, HashAlgo.Keccak256), 1.second)
    assert(sig.length == 65)
    assert((sig(64) & 0xff) == v)
    // last recorded APDU should be a SIGN_TRANSACTION (INS=0x04)
    val last = t.recorded.last
    assert((last(1) & 0xff) == 0x04)

  test("LedgerEthereumSigner.sign with HashAlgo.None routes EIP-712 with 64-byte input"):
    val t = MockTransport()
    t.open()
    t.queueOk(appInfoPayload("Ethereum"))
    val key = Array.tabulate[Byte](65)(i => i.toByte)
    t.queueOk(getPubKeyPayload(key, "0x0000000000000000000000000000000000000001"))
    val vault  = new LedgerEthereumVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, Bip32Path.DefaultEthereum), 1.second)
    val v = 0x1C
    val r = Array.fill[Byte](32)(0x55)
    val s = Array.fill[Byte](32)(0x66)
    t.queueOk((v.toByte +: r) ++ s)
    val pair = Array.fill[Byte](64)(0x77)
    val sig  = Await.result(signer.sign(pair, HashAlgo.None), 1.second)
    assert(sig.length == 65)
    val last = t.recorded.last
    assert((last(1) & 0xff) == 0x0C)

  test("signTransaction surfaces user-declined as a failed Future"):
    val t = MockTransport()
    t.queueStatus(Apdu.Sw_UserDeclined)
    val ex = intercept[RuntimeException]:
      Await.result(
        EthereumApp.signTransaction(t, Bip32Path.DefaultEthereum, Array.fill[Byte](20)(0)),
        1.second
      )
    assert(ex.getMessage.contains("SIGN_TRANSACTION failed"))
    assert(ex.getMessage.contains("0x6985"))
