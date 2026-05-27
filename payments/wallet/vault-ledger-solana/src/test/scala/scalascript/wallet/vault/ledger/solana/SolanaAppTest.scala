package scalascript.wallet.vault.ledger.solana

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.wallet.spi.UnlockCredential
import scalascript.wallet.vault.ledger.{Apdu, AppSwitchRequired, Bip32Path, MockTransport}

class SolanaAppTest extends AnyFunSuite:

  /** Build a synthetic `getAppAndVersion` response payload. */
  private def appInfoPayload(name: String, version: String = "1.3.0"): Array[Byte] =
    val n = name.getBytes("UTF-8")
    val v = version.getBytes("UTF-8")
    val out = new Array[Byte](1 + 1 + n.length + 1 + v.length)
    out(0) = 0x01
    out(1) = n.length.toByte
    System.arraycopy(n, 0, out, 2, n.length)
    out(2 + n.length) = v.length.toByte
    System.arraycopy(v, 0, out, 2 + n.length + 1, v.length)
    out

  // ─────────────────────────────────────────
  //  1. getPublicKey — all-zero 32-byte key
  // ─────────────────────────────────────────

  test("getPublicKey: all-zero 32-byte response produces non-empty Base58"):
    val t      = MockTransport()
    val zeroes = new Array[Byte](32)
    t.queueOk(zeroes)
    val pk = Await.result(SolanaApp.getPublicKey(t, SolanaApp.DefaultPath), 1.second)
    assert(pk.bytes.sameElements(zeroes))
    assert(pk.toBase58.nonEmpty)
    // All-zero bytes → all '1' characters in Base58
    assert(pk.toBase58.forall(_ == '1'))

  // ─────────────────────────────────────────
  //  2. getPublicKey — Base58 round-trip
  // ─────────────────────────────────────────

  test("getPublicKey: Base58 round-trip for a known 32-byte key"):
    val t    = MockTransport()
    val key  = Array.tabulate[Byte](32)(i => (i + 1).toByte)
    t.queueOk(key)
    val pk = Await.result(SolanaApp.getPublicKey(t, SolanaApp.DefaultPath), 1.second)
    val b58 = pk.toBase58
    assert(b58.nonEmpty)
    // Decode must round-trip back to the same bytes
    val decoded = Base58.decode(b58)
    // Strip any leading zero padding from decoded
    val significant = decoded.dropWhile(_ == 0)
    val keySignificant = key.dropWhile(_ == 0)
    assert(significant.sameElements(keySignificant))

  // ─────────────────────────────────────────
  //  3. signTransaction — correct APDU shape (single chunk)
  // ─────────────────────────────────────────

  test("signTransaction single-chunk: correct APDU bytes sent"):
    val t   = MockTransport()
    val sig = new Array[Byte](64)
    java.util.Arrays.fill(sig, 0xAB.toByte)
    t.queueOk(sig)
    // Small tx that fits in one chunk together with the path
    val tx = Array.tabulate[Byte](50)(i => (i % 256).toByte)
    Await.result(SolanaApp.signTransaction(t, SolanaApp.DefaultPath, tx), 1.second)

    assert(t.recorded.size == 1)
    val apdu = t.recorded.head
    // CLA = 0xE0, INS = 0x04
    assert((apdu(0) & 0xff) == 0xE0)
    assert((apdu(1) & 0xff) == 0x04)
    // P1 = 0x00 (first), P2 = 0x00
    assert((apdu(2) & 0xff) == SolanaApp.P1First)
    assert((apdu(3) & 0xff) == SolanaApp.P2None)
    // Lc
    val expectedPath = Bip32Path.encode(SolanaApp.DefaultPath)
    val lc = apdu(4) & 0xff
    assert(lc == expectedPath.length + tx.length)
    // Path bytes match
    val pathBytes = java.util.Arrays.copyOfRange(apdu, 5, 5 + expectedPath.length)
    assert(pathBytes.sameElements(expectedPath))
    // Tx bytes follow
    val txBytes = java.util.Arrays.copyOfRange(apdu, 5 + expectedPath.length, apdu.length)
    assert(txBytes.sameElements(tx))

  // ─────────────────────────────────────────
  //  4. signTransaction — multi-chunk (> 255 bytes total)
  // ─────────────────────────────────────────

  test("signTransaction multi-chunk: correct first + continuation APDUs"):
    val t   = MockTransport()
    val sig = new Array[Byte](64)
    java.util.Arrays.fill(sig, 0xCD.toByte)
    // First chunk returns 9000 (no payload), last chunk returns signature
    t.queueOk()
    t.queueOk(sig)
    // 300-byte tx + ~17-byte path = > 255 bytes total → 2 chunks
    val tx = new Array[Byte](300)
    java.util.Arrays.fill(tx, 0x55.toByte)
    val result = Await.result(SolanaApp.signTransaction(t, SolanaApp.DefaultPath, tx), 1.second)

    assert(result.sameElements(sig))
    assert(t.recorded.size == 2)
    // Both APDUs: CLA=0xE0, INS=0x04
    assert((t.recorded(0)(0) & 0xff) == 0xE0)
    assert((t.recorded(0)(1) & 0xff) == 0x04)
    assert((t.recorded(1)(0) & 0xff) == 0xE0)
    assert((t.recorded(1)(1) & 0xff) == 0x04)
    // P1: first=0x00, continuation=0x80
    assert((t.recorded(0)(2) & 0xff) == SolanaApp.P1First)
    assert((t.recorded(1)(2) & 0xff) == SolanaApp.P1Continue)

  // ─────────────────────────────────────────
  //  5. signOffchainMessage — single chunk
  // ─────────────────────────────────────────

  test("signOffchainMessage single-chunk: INS=0x07, correct payload"):
    val t   = MockTransport()
    val sig = new Array[Byte](64)
    java.util.Arrays.fill(sig, 0x77.toByte)
    t.queueOk(sig)
    val msg = "Hello, Solana!".getBytes("UTF-8")
    val result = Await.result(
      SolanaApp.signOffchainMessage(t, SolanaApp.DefaultPath, msg),
      1.second
    )
    assert(result.sameElements(sig))
    assert(t.recorded.size == 1)
    val apdu = t.recorded.head
    assert((apdu(0) & 0xff) == 0xE0)
    assert((apdu(1) & 0xff) == 0x07)
    assert((apdu(2) & 0xff) == SolanaApp.P1First)
    val expectedPath = Bip32Path.encode(SolanaApp.DefaultPath)
    val lc = apdu(4) & 0xff
    assert(lc == expectedPath.length + msg.length)
    val msgBytes = java.util.Arrays.copyOfRange(apdu, 5 + expectedPath.length, apdu.length)
    assert(msgBytes.sameElements(msg))

  // ─────────────────────────────────────────
  //  6. signOffchainMessage — multi-chunk
  // ─────────────────────────────────────────

  test("signOffchainMessage multi-chunk: P1Continue on subsequent chunks"):
    val t   = MockTransport()
    val sig = new Array[Byte](64)
    java.util.Arrays.fill(sig, 0xEE.toByte)
    t.queueOk()
    t.queueOk(sig)
    val msg = new Array[Byte](280)
    java.util.Arrays.fill(msg, 0x42.toByte)
    val result = Await.result(
      SolanaApp.signOffchainMessage(t, SolanaApp.DefaultPath, msg),
      1.second
    )
    assert(result.sameElements(sig))
    assert(t.recorded.size == 2)
    assert((t.recorded(0)(1) & 0xff) == 0x07)
    assert((t.recorded(1)(1) & 0xff) == 0x07)
    assert((t.recorded(0)(2) & 0xff) == SolanaApp.P1First)
    assert((t.recorded(1)(2) & 0xff) == SolanaApp.P1Continue)

  // ─────────────────────────────────────────
  //  7. Wrong app → AppSwitchRequired
  // ─────────────────────────────────────────

  test("wrong app open: AppSwitchRequired raised from ensureSolanaApp"):
    val t = MockTransport()
    t.open()
    t.queueOk(appInfoPayload("Ethereum"))
    val vault = new LedgerSolanaVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val ex = intercept[AppSwitchRequired]:
      Await.result(vault.getSigner(Curve.Ed25519, SolanaApp.DefaultPath), 1.second)
    assert(ex.currentApp  == "Ethereum")
    assert(ex.requiredApp == "Solana")

  // ─────────────────────────────────────────
  //  8. LedgerSolanaVault.connect with correct app name
  // ─────────────────────────────────────────

  test("LedgerSolanaVault.getSigner succeeds when Solana app is active"):
    val t = MockTransport()
    t.open()
    // getAppName response
    t.queueOk(appInfoPayload("Solana"))
    // GET_PUBKEY response (32 zero bytes)
    t.queueOk(new Array[Byte](32))
    val vault = new LedgerSolanaVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val signer = Await.result(vault.getSigner(Curve.Ed25519, SolanaApp.DefaultPath), 1.second)
    assert(signer.curve == Curve.Ed25519)
    assert(signer.publicKey.bytes.length == 32)
    // Two APDUs: getAppName (CLA=0xB0) + GET_PUBKEY (CLA=0xE0, INS=0x05)
    assert(t.recorded.size == 2)
    assert((t.recorded(0)(0) & 0xff) == 0xB0)
    assert((t.recorded(1)(0) & 0xff) == 0xE0)
    assert((t.recorded(1)(1) & 0xff) == 0x05)

  // ─────────────────────────────────────────
  //  9. LedgerSolanaVault.connect with wrong app
  // ─────────────────────────────────────────

  test("LedgerSolanaVault.getSigner raises AppSwitchRequired for non-Solana app"):
    val t = MockTransport()
    t.open()
    t.queueOk(appInfoPayload("Bitcoin", "2.2.4"))
    val vault = new LedgerSolanaVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val ex = intercept[AppSwitchRequired]:
      Await.result(vault.getSigner(Curve.Ed25519, SolanaApp.DefaultPath), 1.second)
    assert(ex.currentApp  == "Bitcoin")
    assert(ex.requiredApp == "Solana")

  // ─────────────────────────────────────────
  //  10. Base58 known vectors
  // ─────────────────────────────────────────

  test("Base58.encode: 32 zero bytes encodes to 32 '1' characters"):
    val zeroes = new Array[Byte](32)
    val b58    = Base58.encode(zeroes)
    assert(b58.length == 32)
    assert(b58.forall(_ == '1'))

  test("Base58.encode: single 0x00 byte encodes to '1'"):
    assert(Base58.encode(Array(0x00.toByte)) == "1")

  test("Base58.encode: known vector [0x00, 0x01] = '12'"):
    // 0x00 → leading '1'; remaining value is 1 → '2'
    val enc = Base58.encode(Array(0x00.toByte, 0x01.toByte))
    assert(enc == "12")

  // ─────────────────────────────────────────
  //  11. LedgerSolanaVault.getSigner returns RawSigner for Ed25519
  // ─────────────────────────────────────────

  test("LedgerSolanaVault.getSigner returns a RawSigner for Ed25519"):
    val t = MockTransport()
    t.open()
    t.queueOk(appInfoPayload("Solana"))
    t.queueOk(new Array[Byte](32))
    val vault = new LedgerSolanaVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val signer = Await.result(vault.getSigner(Curve.Ed25519, SolanaApp.DefaultPath), 1.second)
    // Must implement RawSigner and advertise Ed25519
    assert(signer.isInstanceOf[scalascript.wallet.spi.RawSigner])
    assert(signer.curve == Curve.Ed25519)

  test("LedgerSolanaVault.getSigner rejects non-Ed25519 curves"):
    val t     = MockTransport()
    val vault = new LedgerSolanaVault(t)
    Await.result(vault.unlock(UnlockCredential.None), 1.second)
    val ex = intercept[UnsupportedOperationException]:
      Await.result(vault.getSigner(Curve.Secp256k1, SolanaApp.DefaultPath), 1.second)
    assert(ex.getMessage.contains("Ed25519"))

  test("LedgerSolanaVault refuses signing while locked"):
    val t     = MockTransport()
    val vault = new LedgerSolanaVault(t)
    val ex = intercept[IllegalStateException]:
      Await.result(vault.getSigner(Curve.Ed25519, SolanaApp.DefaultPath), 1.second)
    assert(ex.getMessage.contains("locked"))
