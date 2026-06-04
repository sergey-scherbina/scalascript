package scalascript.wallet.spi

import org.scalatest.funsuite.AnyFunSuite
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.blockchain.spi.ChainId

/** Compile-only / value-level smoke test for the wallet-spi
 *  cross-compile (docs/specs/wallet-spi-scalajs.md, Stage 1).
 *
 *  This spec lives in `shared/src/test/scala/` so the JVM and JS
 *  test runners both execute it. The goal is **not** to test signing
 *  logic (no `CryptoBackend` impl is available on JS yet) — it's to
 *  prove that the SPI's pure data types round-trip correctly through
 *  the Scala.js linker and Node.js test runner. */
class CrossCompileSmokeTest extends AnyFunSuite:

  // ── crypto-spi pure types ─────────────────────────────────────────────

  test("Curve enum has the five canonical curves"):
    val all = Set(Curve.Secp256k1, Curve.Ed25519, Curve.P256, Curve.Sr25519, Curve.Bls12_381)
    assert(all.size == 5)
    assert(Curve.values.length == 5)

  test("HashAlgo enum exposes the canonical digests"):
    assert(HashAlgo.values.toSet.contains(HashAlgo.None))
    assert(HashAlgo.values.toSet.contains(HashAlgo.Sha256))
    assert(HashAlgo.values.toSet.contains(HashAlgo.Keccak256))

  test("PublicKey equality is by curve + content, not array identity"):
    val a = PublicKey(Curve.Secp256k1, Array[Byte](1, 2, 3))
    val b = PublicKey(Curve.Secp256k1, Array[Byte](1, 2, 3))
    val c = PublicKey(Curve.Secp256k1, Array[Byte](1, 2, 4))
    val d = PublicKey(Curve.Ed25519,   Array[Byte](1, 2, 3))
    assert(a == b)
    assert(a.hashCode == b.hashCode)
    assert(a != c)
    assert(a != d)
    assert(a.toString.contains("Secp256k1"))

  // ── blockchain-spi pure types ─────────────────────────────────────────

  test("ChainId parses CAIP-2 namespace + reference"):
    val base = ChainId("eip155:8453")
    assert(base.namespace == "eip155")
    assert(base.reference == "8453")
    assert(base.toString == "eip155:8453")
    assert(ChainId.Base == base)

  test("ChainId rejects malformed input"):
    intercept[IllegalArgumentException]:
      ChainId("not-a-caip2-id")

  // ── wallet-spi pure types ─────────────────────────────────────────────

  test("VaultKind exposes the five storage backings"):
    val all = Set(
      VaultKind.EncryptedLocal,
      VaultKind.Mpc,
      VaultKind.Hardware,
      VaultKind.Passkey,
      VaultKind.InMemory,
    )
    assert(all.size == 5)
    assert(VaultKind.values.length == 5)

  test("UnlockCredential ADT covers password / biometric / none"):
    val pwd: UnlockCredential = UnlockCredential.Password("hunter2")
    val bio: UnlockCredential = UnlockCredential.Biometric
    val non: UnlockCredential = UnlockCredential.None
    val tag = (c: UnlockCredential) => c match
      case _: UnlockCredential.Password => "password"
      case UnlockCredential.Biometric   => "biometric"
      case UnlockCredential.None        => "none"
    assert(tag(pwd) == "password")
    assert(tag(bio) == "biometric")
    assert(tag(non) == "none")

  test("AccountDescriptor is a plain immutable record"):
    val pk = PublicKey(Curve.Secp256k1, Array[Byte](0xde.toByte, 0xad.toByte))
    val desc = AccountDescriptor(
      id             = "acc-0",
      label          = "Primary",
      publicKeys     = Map(Curve.Secp256k1 -> pk),
      derivationPath = "m/44'/60'/0'/0/0",
    )
    assert(desc.id == "acc-0")
    assert(desc.publicKeys(Curve.Secp256k1) == pk)
    assert(desc.derivationPath.startsWith("m/44'"))
