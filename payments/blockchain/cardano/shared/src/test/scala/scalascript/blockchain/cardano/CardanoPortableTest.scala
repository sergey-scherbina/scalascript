package scalascript.blockchain.cardano

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.crypto.{Blake2b, Curve, PublicKey}

/** Cross-platform (JVM + Scala.js) fixtures for the backend-agnostic Cardano
 *  core: BLAKE2b hashing, CIP-19 address derivation, Bech32 and the CBOR codec.
 *
 *  This suite uses **no `CryptoBackend`** — public keys are supplied as fixed raw
 *  bytes, so every assertion is a pure function of the shared sources. Because the
 *  same source compiles to both targets, identical results here prove the address
 *  and transaction-hash bytes a browser wallet (Scala.js) produces are byte-for-byte
 *  identical to the JVM. The BLAKE2b vectors are the published RFC 7693 values, so
 *  the suite also pins correctness, not just JVM/JS agreement. */
class CardanoPortableTest extends AnyFunSuite with Matchers:

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  // Fixed Ed25519 verification keys (raw 32 bytes) — no derivation, no backend.
  private val pk42 = PublicKey(Curve.Ed25519, Array.fill[Byte](32)(0x42.toByte))
  private val pk99 = PublicKey(Curve.Ed25519, Array.fill[Byte](32)(0x99.toByte))

  // ── BLAKE2b — published RFC 7693 vectors ─────────────────────────────────────

  test("BLAKE2b-256(\"abc\") matches the RFC 7693 vector") {
    hex(Blake2b.hash256("abc".getBytes("UTF-8"))) shouldBe
      "bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319"
  }

  test("BLAKE2b-224(\"abc\") matches the published vector") {
    hex(Blake2b.hash224("abc".getBytes("UTF-8"))) shouldBe
      "9bd237b02a29e43bdd6738afa5b53ff0eee178d6210b618e4511aec8"
  }

  test("BLAKE2b-224(empty) is deterministic") {
    hex(Blake2b.hash224(Array.empty[Byte])) shouldBe
      "836cc68931c2e4e3e838602eca1902591d216837bafddfe6f0c8cb07"
  }

  // ── CIP-19 addresses — byte-exact goldens from fixed keys ────────────────────

  test("enterprise mainnet address is byte-exact (golden)") {
    CardanoAddress.fromPublicKey(pk42, testnet = false) shouldBe
      "addr1vre7m485xypftj8eshds69h7pdhmlyg9qx9x0qhszc9qcnquf3hyw"
  }

  test("enterprise testnet address is byte-exact (golden)") {
    CardanoAddress.fromPublicKey(pk42, testnet = true) shouldBe
      "addr_test1wre7m485xypftj8eshds69h7pdhmlyg9qx9x0qhszc9qcnqwmf6uv"
  }

  test("base mainnet address is byte-exact (golden)") {
    CardanoAddress.fromPublicKeys(pk42, pk99, testnet = false) shouldBe
      "addr1qre7m485xypftj8eshds69h7pdhmlyg9qx9x0qhszc9qcn9cuhewkqqlklcsdss0ram30zce9cawdtpr8h94tz0dzv5qs94fjl"
  }

  test("base testnet address is byte-exact (golden)") {
    CardanoAddress.fromPublicKeys(pk42, pk99, testnet = true) shouldBe
      "addr_test1zre7m485xypftj8eshds69h7pdhmlyg9qx9x0qhszc9qcn9cuhewkqqlklcsdss0ram30zce9cawdtpr8h94tz0dzv5qd2c4jf"
  }

  // ── CIP-19 structure + self-consistency ──────────────────────────────────────

  test("enterprise layout: header 0x60 + 28-byte payment hash") {
    val bytes = Bech32.decode(CardanoAddress.fromPublicKey(pk42)).getOrElse(fail("must decode"))
    bytes.length          shouldBe 29
    (bytes(0) & 0xFF)     shouldBe 0x60
  }

  test("base layout: header 0x00 + 28 payment + 28 stake = 57 bytes") {
    val bytes = Bech32.decode(CardanoAddress.fromPublicKeys(pk42, pk99)).getOrElse(fail("must decode"))
    bytes.length          shouldBe 57
    (bytes(0) & 0xFF)     shouldBe 0x00
  }

  test("base address payment-hash prefix equals the enterprise payment hash") {
    val base = Bech32.decode(CardanoAddress.fromPublicKeys(pk42, pk99)).getOrElse(fail("base"))
    val ent  = Bech32.decode(CardanoAddress.fromPublicKey(pk42)).getOrElse(fail("enterprise"))
    base.slice(1, 29).toSeq shouldBe ent.slice(1, 29).toSeq
  }

  test("kindOf distinguishes enterprise from base") {
    CardanoAddress.kindOf(CardanoAddress.fromPublicKey(pk42))        shouldBe CardanoAddress.Kind.Enterprise
    CardanoAddress.kindOf(CardanoAddress.fromPublicKeys(pk42, pk99)) shouldBe CardanoAddress.Kind.Base
  }

  test("isValidAddress accepts the golden addresses and rejects garbage") {
    CardanoAddress.isValid(CardanoAddress.fromPublicKey(pk42))        shouldBe true
    CardanoAddress.isValid(CardanoAddress.fromPublicKeys(pk42, pk99)) shouldBe true
    CardanoAddress.isValid("not-an-address")                         shouldBe false
  }

  // ── Bech32 ────────────────────────────────────────────────────────────────────

  test("Bech32 encode is byte-exact (golden)") {
    Bech32.encode("test", Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)) shouldBe "test1qypqxpq9qcrssh0alak"
  }

  test("Bech32 encode/decode roundtrip") {
    val data = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
    Bech32.decode(Bech32.encode("test", data)) match
      case Right(decoded) => decoded.toSeq shouldBe data.toSeq
      case Left(err)      => fail(s"Decode failed: $err")
  }

  test("Bech32 decode rejects a wrong checksum") {
    Bech32.decode("test1qqqqqqq") shouldBe a[Left[?, ?]]
  }

  // ── CBOR codec ────────────────────────────────────────────────────────────────

  test("CardanoCbor roundtrip UInt") {
    val v = CardanoCbor.UInt(42L)
    CardanoCbor.decode(CardanoCbor.encode(v)) shouldBe v
  }

  test("CardanoCbor roundtrip Bytes") {
    val v       = CardanoCbor.Bytes(Array[Byte](1, 2, 3, 4, 5))
    val decoded = CardanoCbor.decode(CardanoCbor.encode(v)).asInstanceOf[CardanoCbor.Bytes]
    decoded.b.toSeq shouldBe v.b.toSeq
  }

  test("CardanoCbor roundtrip nested Arr") {
    val v = CardanoCbor.Arr(Seq(
      CardanoCbor.UInt(1),
      CardanoCbor.Text("hello"),
      CardanoCbor.Bytes(Array[Byte](0xde.toByte, 0xad.toByte)),
    ))
    CardanoCbor.decode(CardanoCbor.encode(v)) match
      case CardanoCbor.Arr(items) =>
        items(0) shouldBe CardanoCbor.UInt(1)
        items(1) shouldBe CardanoCbor.Text("hello")
      case other => fail(s"Expected Arr, got $other")
  }

  // ── Transaction body hash ─────────────────────────────────────────────────────

  test("CardanoTx.txBodyHash is a deterministic byte-exact BLAKE2b-256 (golden)") {
    val tx = CardanoTx("addr_sender", "addr_to", BigInt(1_000_000), None, BigInt(180_000), BigInt(820_000),
      Seq(CardanoUtxo("a" * 64, 0, BigInt(2_000_000), Map.empty)))
    tx.txBodyHash.length shouldBe 32
    hex(tx.txBodyHash)   shouldBe "f9a6f4f20494ecbfedafd1ef0ededa60fab8e41da876f89b4dd8c99aca3ce916"
  }
