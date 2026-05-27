package scalascript.wallet.vault.ledger.bitcoin

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scalascript.crypto.Curve
import scalascript.wallet.spi.UnlockCredential
import scalascript.wallet.vault.ledger.{Apdu, AppSwitchRequired, Bip32Path, MockTransport}

class BitcoinAppTest extends AnyFunSuite:

  // ── helpers ──────────────────────────────────────────────────────────────

  /** Build a synthetic `getAppAndVersion` payload for the dashboard. */
  private def appInfoPayload(name: String, version: String = "2.1.0"): Array[Byte] =
    val n = name.getBytes("UTF-8"); val v = version.getBytes("UTF-8")
    val out = new Array[Byte](1 + 1 + n.length + 1 + v.length)
    out(0) = 0x01
    out(1) = n.length.toByte
    System.arraycopy(n, 0, out, 2, n.length)
    out(2 + n.length) = v.length.toByte
    System.arraycopy(v, 0, out, 2 + n.length + 1, v.length)
    out

  /** Build a synthetic 74-byte GET_EXTENDED_PUBKEY response.
   *
   *  Layout: `[chain_code(32) || pubkey(33) || fingerprint(4) || depth(1) || child_number(4)]`
   */
  private def extPubkeyPayload(
    chainCode:   Array[Byte] = Array.fill[Byte](32)(0x42),
    pubKey:      Array[Byte] = Array.fill[Byte](33)(0x03),
    fingerprint: Array[Byte] = Array.fill[Byte](4)(0x01),
    depth:       Int         = 3,
    childNumber: Int         = 0,
  ): Array[Byte] =
    val out = new Array[Byte](74)
    System.arraycopy(chainCode,   0, out,  0, 32)
    System.arraycopy(pubKey,      0, out, 32, 33)
    System.arraycopy(fingerprint, 0, out, 65,  4)
    out(69) = depth.toByte
    out(70) = ((childNumber >>> 24) & 0xff).toByte
    out(71) = ((childNumber >>> 16) & 0xff).toByte
    out(72) = ((childNumber >>>  8) & 0xff).toByte
    out(73) = ( childNumber         & 0xff).toByte
    out

  /** Build a synthetic SIGN_PSBT response for `n` signatures, each `sigLen` bytes. */
  private def signPsbtPayload(sigs: Array[Byte]*): Array[Byte] =
    val buf = new java.io.ByteArrayOutputStream()
    buf.write(sigs.length & 0xff)
    for sig <- sigs do
      buf.write(sig.length & 0xff)
      buf.write(sig)
    buf.toByteArray

  // ── getExtendedPubkey ────────────────────────────────────────────────────

  test("getExtendedPubkey — chainCode.length == 32, publicKey.length == 33"):
    val t = MockTransport()
    t.queueOk(extPubkeyPayload())
    val xpub = Await.result(BitcoinApp.getExtendedPubkey(t, BitcoinApp.DefaultPath), 1.second)
    assert(xpub.chainCode.length == 32)
    assert(xpub.publicKey.length == 33)
    assert(xpub.fingerprint.length == 4)
    assert(xpub.depth == 3)
    assert(t.recorded.size == 1)
    val apdu = t.recorded.head
    assert((apdu(0) & 0xff) == 0xE1) // CLA
    assert((apdu(1) & 0xff) == 0x00) // INS GET_EXTENDED_PUBKEY

  test("getExtendedPubkey with depth=2 — depth field parsed correctly"):
    val t = MockTransport()
    t.queueOk(extPubkeyPayload(depth = 2, childNumber = 0x80000054))
    val xpub = Await.result(BitcoinApp.getExtendedPubkey(t, "m/84'/0'"), 1.second)
    assert(xpub.depth == 2)
    assert(xpub.childNumber == 0x80000054.toInt)

  test("getExtendedPubkey payload encodes BIP-32 path correctly"):
    val t = MockTransport()
    t.queueOk(extPubkeyPayload())
    Await.result(BitcoinApp.getExtendedPubkey(t, BitcoinApp.DefaultPath), 1.second)
    val apdu     = t.recorded.head
    // payload = [displayAddr(1)] ++ Bip32Path.encode(path)
    val pathEnc  = Bip32Path.encode(BitcoinApp.DefaultPath)
    val lc       = apdu(4) & 0xff
    assert(lc == 1 + pathEnc.length)
    val body = java.util.Arrays.copyOfRange(apdu, 5, 5 + lc)
    assert(body(0) == 0x00.toByte) // display=false
    val pathBody = java.util.Arrays.copyOfRange(body, 1, body.length)
    assert(pathBody.sameElements(pathEnc))

  // ── signPsbt ─────────────────────────────────────────────────────────────

  test("signPsbt single-input — correct INS sent, signature bytes returned"):
    val t   = MockTransport()
    val sig = Array.tabulate[Byte](71)(i => (i + 0x30).toByte)  // DER sig
    t.queueOk(signPsbtPayload(sig))
    val psbt   = Array.fill[Byte](50)(0xAB.toByte)
    val result = Await.result(BitcoinApp.signPsbt(t, psbt), 1.second)
    assert(result.size == 1)
    assert(result.contains(0))
    assert(result(0).sameElements(sig))
    val apdu = t.recorded.head
    assert((apdu(0) & 0xff) == 0xE1) // CLA
    assert((apdu(1) & 0xff) == 0x04) // INS SIGN_PSBT

  test("signPsbt multi-input — Map has correct size"):
    val t    = MockTransport()
    val sig0 = Array.fill[Byte](71)(0x11.toByte)
    val sig1 = Array.fill[Byte](72)(0x22.toByte)
    val sig2 = Array.fill[Byte](70)(0x33.toByte)
    t.queueOk(signPsbtPayload(sig0, sig1, sig2))
    val psbt   = Array.fill[Byte](100)(0xAA.toByte)
    val result = Await.result(BitcoinApp.signPsbt(t, psbt), 1.second)
    assert(result.size == 3)
    assert(result(0).sameElements(sig0))
    assert(result(1).sameElements(sig1))
    assert(result(2).sameElements(sig2))

  test("signPsbt chunked (large PSBT > 255 bytes) — two chunks sent"):
    val t    = MockTransport()
    val sig  = Array.fill[Byte](71)(0x55.toByte)
    // Intermediate chunk gets sw=9000, final chunk gets the response
    t.queueOk()                        // first chunk
    t.queueOk(signPsbtPayload(sig))    // final chunk
    val psbt = new Array[Byte](300)    // > 255 → must be chunked
    java.util.Arrays.fill(psbt, 0xCC.toByte)
    val result = Await.result(BitcoinApp.signPsbt(t, psbt), 1.second)
    assert(t.recorded.size == 2)
    assert((t.recorded(0)(2) & 0xff) == 0x00) // p1 first
    assert((t.recorded(1)(2) & 0xff) == 0x80) // p1 continue
    assert((t.recorded(0)(1) & 0xff) == 0x04) // INS SIGN_PSBT
    assert((t.recorded(1)(1) & 0xff) == 0x04)
    assert(result.size == 1)

  test("signPsbt empty response — returns empty Map"):
    val t = MockTransport()
    t.queueOk(Array.emptyByteArray)
    val result = Await.result(BitcoinApp.signPsbt(t, Array.fill[Byte](10)(0x00)), 1.second)
    assert(result.isEmpty)

  test("signPsbt wrong-app status — surfaces as failed Future"):
    val t = MockTransport()
    t.queueStatus(Apdu.Sw_UnknownCla)   // 0x6E00 — wrong CLA
    val ex = intercept[RuntimeException]:
      Await.result(BitcoinApp.signPsbt(t, Array.fill[Byte](20)(0xBB.toByte)), 1.second)
    assert(ex.getMessage.contains("SIGN_PSBT failed"))
    assert(ex.getMessage.contains("0x6E00"))

  // ── LedgerBitcoinVault ───────────────────────────────────────────────────

  test("LedgerBitcoinVault.connect correct app — success"):
    val t = MockTransport()
    t.queueOk(appInfoPayload("Bitcoin"))
    val vault = new LedgerBitcoinVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    // After unlock the vault is open; getSigner probes the app
    t.queueOk(appInfoPayload("Bitcoin"))
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, BitcoinApp.DefaultPath), 1.second)
    assert(signer.curve == Curve.Secp256k1)

  test("LedgerBitcoinVault.connect wrong app — AppSwitchRequired"):
    val t = MockTransport()
    val vault = new LedgerBitcoinVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    t.queueOk(appInfoPayload("Ethereum"))
    val ex = intercept[AppSwitchRequired]:
      Await.result(vault.getSigner(Curve.Secp256k1, BitcoinApp.DefaultPath), 1.second)
    assert(ex.currentApp  == "Ethereum")
    assert(ex.requiredApp == "Bitcoin")

  test("LedgerBitcoinVault.getSigner Secp256k1 — success"):
    val t = MockTransport()
    val vault = new LedgerBitcoinVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    t.queueOk(appInfoPayload("Bitcoin"))
    val signer = Await.result(vault.getSigner(Curve.Secp256k1, BitcoinApp.DefaultPath), 1.second)
    assert(signer.isInstanceOf[LedgerBitcoinRawSigner])
    assert(signer.curve == Curve.Secp256k1)

  test("LedgerBitcoinVault.getSigner Ed25519 — throws UnsupportedOperationException"):
    val t = MockTransport()
    val vault = new LedgerBitcoinVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val ex = intercept[UnsupportedOperationException]:
      Await.result(vault.getSigner(Curve.Ed25519, "m/44'/501'/0'/0'"), 1.second)
    assert(ex.getMessage.contains("Secp256k1"))

  test("DefaultPath is native SegWit m/84'/0'/0'"):
    assert(BitcoinApp.DefaultPath == "m/84'/0'/0'")

  // ── LedgerBitcoinRawSigner ───────────────────────────────────────────────

  test("LedgerBitcoinRawSigner.sign concatenates all input sigs as [len || sig]*"):
    val t   = MockTransport()
    val sig0 = Array.fill[Byte](71)(0xAA.toByte)
    val sig1 = Array.fill[Byte](72)(0xBB.toByte)
    t.queueOk(signPsbtPayload(sig0, sig1))
    val signer = new LedgerBitcoinRawSigner(t, BitcoinApp.DefaultPath)
    val psbt   = Array.fill[Byte](60)(0xDD.toByte)
    val result = Await.result(signer.sign(psbt), 1.second)
    // result should be [71, sig0(71), 72, sig1(72)] = 1+71+1+72 = 145 bytes
    assert(result.length == 1 + 71 + 1 + 72)
    assert((result(0) & 0xff) == 71)
    val part0 = java.util.Arrays.copyOfRange(result, 1, 72)
    assert(part0.sameElements(sig0))
    assert((result(72) & 0xff) == 72)
    val part1 = java.util.Arrays.copyOfRange(result, 73, 145)
    assert(part1.sameElements(sig1))
