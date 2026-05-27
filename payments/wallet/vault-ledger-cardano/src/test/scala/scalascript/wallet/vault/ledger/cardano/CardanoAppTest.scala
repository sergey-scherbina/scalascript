package scalascript.wallet.vault.ledger.cardano

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scalascript.crypto.Curve
import scalascript.wallet.spi.UnlockCredential
import scalascript.wallet.vault.ledger.{AppSwitchRequired, Bip32Path, CurveAppRouting, MockTransport}

class CardanoAppTest extends AnyFunSuite:

  /** Build a synthetic `getAppAndVersion` payload for the dashboard. */
  private def appInfoPayload(name: String, version: String = "7.1.2"): Array[Byte] =
    val n = name.getBytes("UTF-8"); val v = version.getBytes("UTF-8")
    val out = new Array[Byte](1 + 1 + n.length + 1 + v.length)
    out(0) = 0x01
    out(1) = n.length.toByte
    System.arraycopy(n, 0, out, 2, n.length)
    out(2 + n.length) = v.length.toByte
    System.arraycopy(v, 0, out, 2 + n.length + 1, v.length)
    out

  // ── 1. getExtendedPublicKey — mock response 64 bytes → correct split ──

  test("getExtendedPublicKey splits 64-byte response into publicKey(32) and chainCode(32)"):
    val t   = MockTransport()
    val pub = Array.tabulate[Byte](32)(i => (i + 1).toByte)
    val cc  = Array.tabulate[Byte](32)(i => (i + 100).toByte)
    t.queueOk(pub ++ cc)
    val result = Await.result(
      CardanoApp.getExtendedPublicKey(t, CardanoApp.DefaultPath),
      1.second,
    )
    assert(result.publicKey.length == 32)
    assert(result.chainCode.length == 32)
    assert(result.publicKey.sameElements(pub))
    assert(result.chainCode.sameElements(cc))

  // ── 2. getExtendedPublicKey — APDU shape for specific path ──

  test("getExtendedPublicKey encodes the derivation path correctly in the APDU"):
    val t   = MockTransport()
    val pub = Array.fill[Byte](32)(0x11)
    val cc  = Array.fill[Byte](32)(0x22)
    t.queueOk(pub ++ cc)
    val path = CardanoApp.DefaultPath   // m/1852'/1815'/0'/0/0 → 5 segments = 21 B
    Await.result(CardanoApp.getExtendedPublicKey(t, path), 1.second)
    assert(t.recorded.size == 1)
    val apdu = t.recorded.head
    // CLA INS P1 P2 Lc  <path-bytes>
    assert((apdu(0) & 0xff) == CardanoApp.Cla)
    assert((apdu(1) & 0xff) == CardanoApp.Ins_GetExtendedPublicKey)
    assert((apdu(2) & 0xff) == CardanoApp.P1First)
    assert((apdu(3) & 0xff) == CardanoApp.P2None)
    val expectedPath = Bip32Path.encode(path)
    val lc           = apdu(4) & 0xff
    assert(lc == expectedPath.length)
    val pathBody = java.util.Arrays.copyOfRange(apdu, 5, 5 + lc)
    assert(pathBody.sameElements(expectedPath))

  // ── 3. signTx single chunk → correct INS, 64-byte sig returned ──

  test("signTx single-chunk payload sends correct APDU and returns 64-byte signature"):
    val t   = MockTransport()
    val sig = Array.fill[Byte](64)(0x55)
    t.queueOk(sig)
    val txBody  = Array.fill[Byte](20)(0xAB.toByte)
    val result  = Await.result(
      CardanoApp.signTx(t, CardanoApp.DefaultPath, txBody),
      1.second,
    )
    assert(result.length == 64)
    assert(result.sameElements(sig))
    assert(t.recorded.size == 1)
    val apdu = t.recorded.head
    assert((apdu(0) & 0xff) == CardanoApp.Cla)
    assert((apdu(1) & 0xff) == CardanoApp.Ins_SignTx)
    assert((apdu(2) & 0xff) == CardanoApp.P1First)

  // ── 4. signTx multi-chunk → correct first+continuation APDUs ──

  test("signTx multi-chunk payload sends P1=0x00 first then P1=0x80 continuation APDUs"):
    val t   = MockTransport()
    val sig = Array.fill[Byte](64)(0x77)
    // path = 21 B, so to exceed 255 B per chunk the body alone needs > 234 B
    val txBody = new Array[Byte](300)
    var i = 0; while i < txBody.length do { txBody(i) = (i % 127).toByte; i += 1 }
    // First chunk → sw=9000 no payload; last chunk → sig
    t.queueOk()
    t.queueOk(sig)
    val result = Await.result(
      CardanoApp.signTx(t, CardanoApp.DefaultPath, txBody),
      1.second,
    )
    assert(result.sameElements(sig))
    assert(t.recorded.size == 2)
    assert((t.recorded(0)(2) & 0xff) == CardanoApp.P1First)    // first chunk
    assert((t.recorded(1)(2) & 0xff) == CardanoApp.P1Continue) // continuation
    // Both chunks use the same INS
    assert((t.recorded(0)(1) & 0xff) == CardanoApp.Ins_SignTx)
    assert((t.recorded(1)(1) & 0xff) == CardanoApp.Ins_SignTx)

  // ── 5. CardanoCip8.sigStructure — output starts with CBOR array header ──

  test("CardanoCip8.sigStructure output starts with CBOR array-of-4 header"):
    val payload = "test payload".getBytes("UTF-8")
    val struct  = CardanoCip8.sigStructure(payload)
    assert(struct.nonEmpty)
    // CBOR major type 4 (array), count 4 → 0x84
    assert((struct(0) & 0xff) == 0x84)

  // ── 6. CardanoCip8.protectedHeader — non-empty, starts with CBOR bstr prefix ──

  test("CardanoCip8.protectedHeader is non-empty and is a CBOR map"):
    val ph = CardanoCip8.protectedHeader
    assert(ph.nonEmpty)
    // The protected header encodes { 1: -8 } → A1 01 27
    // major type 5 (map), count 1 → 0xA1
    assert((ph(0) & 0xff) == 0xA1)

  // ── 7. Wrong app name → AppSwitchRequired ──

  test("wrong app name on device causes AppSwitchRequired"):
    val t = MockTransport()
    t.open()
    t.queueOk(appInfoPayload("Ethereum"))
    val vault = new LedgerCardanoVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val ex = intercept[AppSwitchRequired]:
      Await.result(vault.getSigner(Curve.Ed25519, CardanoApp.DefaultPath), 1.second)
    assert(ex.currentApp  == "Ethereum")
    assert(ex.requiredApp == CurveAppRouting.CardanoApp)

  // ── 8. LedgerCardanoVault.connect correct app → success ──

  test("LedgerCardanoVault.getSigner succeeds when Cardano app is active"):
    val t   = MockTransport()
    t.open()
    t.queueOk(appInfoPayload(CurveAppRouting.CardanoApp))
    val pub = Array.fill[Byte](32)(0x01)
    val cc  = Array.fill[Byte](32)(0x02)
    t.queueOk(pub ++ cc)
    val vault = new LedgerCardanoVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val signer = Await.result(vault.getSigner(Curve.Ed25519, CardanoApp.DefaultPath), 1.second)
    assert(signer.curve == Curve.Ed25519)
    assert(signer.publicKey.bytes.sameElements(pub))

  // ── 9. LedgerCardanoVault.connect wrong app → AppSwitchRequired ──

  test("LedgerCardanoVault.getSigner raises AppSwitchRequired when wrong app open"):
    val t = MockTransport()
    t.open()
    t.queueOk(appInfoPayload("Bitcoin"))
    val vault = new LedgerCardanoVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val ex = intercept[AppSwitchRequired]:
      Await.result(vault.getSigner(Curve.Ed25519, CardanoApp.DefaultPath), 1.second)
    assert(ex.currentApp  == "Bitcoin")
    assert(ex.requiredApp == CurveAppRouting.CardanoApp)

  // ── 10. LedgerCardanoVault.getSigner Ed25519 → success ──

  test("LedgerCardanoVault.getSigner Ed25519 returns a RawSigner with correct curve"):
    val t = MockTransport()
    t.open()
    t.queueOk(appInfoPayload(CurveAppRouting.CardanoApp))
    val pub = Array.tabulate[Byte](32)(i => (i * 7 % 256).toByte)
    val cc  = Array.fill[Byte](32)(0x03)
    t.queueOk(pub ++ cc)
    val vault = new LedgerCardanoVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val signer = Await.result(vault.getSigner(Curve.Ed25519, CardanoApp.DefaultPath), 1.second)
    assert(signer.curve == Curve.Ed25519)
    // Two APDUs sent: getAppName then GET_EXTENDED_PUBLIC_KEY
    assert(t.recorded.size == 2)
    assert((t.recorded(0)(0) & 0xff) == 0xB0) // dashboard CLA
    assert((t.recorded(1)(0) & 0xff) == CardanoApp.Cla)
    assert((t.recorded(1)(1) & 0xff) == CardanoApp.Ins_GetExtendedPublicKey)

  // ── 11. LedgerCardanoVault.getSigner Secp256k1 → throws (unsupported) ──

  test("LedgerCardanoVault.getSigner Secp256k1 fails with UnsupportedOperationException"):
    val t     = MockTransport()
    val vault = new LedgerCardanoVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val ex = intercept[UnsupportedOperationException]:
      Await.result(vault.getSigner(Curve.Secp256k1, "m/44'/60'/0'/0/0"), 1.second)
    assert(ex.getMessage.contains("Ed25519"))
