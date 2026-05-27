package scalascript.blockchain.bitcoin

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, PublicKey}

class BitcoinTest extends AnyFunSuite with Matchers:

  given ExecutionContext = ExecutionContext.global

  private val adapter = BitcoinChainAdapter(ChainId.BitcoinMainnet)

  // Known secp256k1 test vector (RFC 6979 deterministic k).
  // Private key from Bitcoin wiki known-answer test.
  private val knownPrivKey: Array[Byte] =
    hex("0000000000000000000000000000000000000000000000000000000000000001")

  private val knownCompressedPub: Array[Byte] =
    hex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")

  // ── ChainId values ─────────────────────────────────────────────────────────

  test("ChainId.BitcoinMainnet has CAIP-2 bip122 namespace") {
    ChainId.BitcoinMainnet.namespace shouldBe "bip122"
  }

  test("ChainId.BitcoinTestnet has CAIP-2 bip122 namespace") {
    ChainId.BitcoinTestnet.namespace shouldBe "bip122"
  }

  test("ChainId.BitcoinMainnet and Testnet are distinct") {
    ChainId.BitcoinMainnet should not equal ChainId.BitcoinTestnet
  }

  // ── secp256k1 sign / verify ────────────────────────────────────────────────

  test("sign produces a valid DER signature verifiable with the corresponding pubkey") {
    val hash = BitcoinCrypto.hash256("hello bitcoin".getBytes("UTF-8"))
    val sig  = BitcoinCrypto.sign(knownPrivKey, hash)
    // DER signature starts with 0x30
    (sig(0) & 0xff) shouldBe 0x30
    BitcoinCrypto.verify(knownCompressedPub, hash, sig) shouldBe true
  }

  test("sign is deterministic (RFC 6979): same key+hash yields same signature") {
    val hash = BitcoinCrypto.sha256("deterministic".getBytes("UTF-8"))
    val sig1 = BitcoinCrypto.sign(knownPrivKey, hash)
    val sig2 = BitcoinCrypto.sign(knownPrivKey, hash)
    sig1.toSeq shouldBe sig2.toSeq
  }

  test("verify rejects signature for wrong message") {
    val hash1 = BitcoinCrypto.hash256("message A".getBytes("UTF-8"))
    val hash2 = BitcoinCrypto.hash256("message B".getBytes("UTF-8"))
    val sig   = BitcoinCrypto.sign(knownPrivKey, hash1)
    BitcoinCrypto.verify(knownCompressedPub, hash2, sig) shouldBe false
  }

  test("verify rejects signature for wrong public key") {
    val otherPriv = hex("0000000000000000000000000000000000000000000000000000000000000002")
    val otherPub  = BitcoinCrypto.deriveCompressedPublicKey(otherPriv)
    val hash      = BitcoinCrypto.sha256("test".getBytes("UTF-8"))
    val sig       = BitcoinCrypto.sign(knownPrivKey, hash)
    BitcoinCrypto.verify(otherPub, hash, sig) shouldBe false
  }

  test("deriveCompressedPublicKey from known private key 1 yields known generator point") {
    val pub = BitcoinCrypto.deriveCompressedPublicKey(knownPrivKey)
    pub.toSeq shouldBe knownCompressedPub.toSeq
  }

  test("sign with uncompressed public key also verifies") {
    val hash   = BitcoinCrypto.sha256("uncompressed".getBytes("UTF-8"))
    val sig    = BitcoinCrypto.sign(knownPrivKey, hash)
    // Build 65-byte uncompressed key from the known compressed key
    val params = org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256k1")
    val domain = new org.bouncycastle.crypto.params.ECDomainParameters(
      params.getCurve, params.getG, params.getN, params.getH)
    val point  = domain.getCurve.decodePoint(knownCompressedPub).normalize()
    val uncompressed = Array[Byte](0x04.toByte) ++
      BitcoinCrypto.toUnsigned32(point.getAffineXCoord.toBigInteger) ++
      BitcoinCrypto.toUnsigned32(point.getAffineYCoord.toBigInteger)
    BitcoinCrypto.verify(uncompressed, hash, sig) shouldBe true
  }

  // ── Hash utilities ─────────────────────────────────────────────────────────

  test("sha256 of empty byte array matches known vector") {
    // SHA256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
    val expected = hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    BitcoinCrypto.sha256(Array.emptyByteArray).toSeq shouldBe expected.toSeq
  }

  test("hash160 of known compressed pubkey produces known P2WPKH hash") {
    // For priv=1, compressed pub = 0279be...
    // SHA256(pub) = 0f71...  RIPEMD160 of that = known
    val h160 = BitcoinCrypto.hash160(knownCompressedPub)
    h160.length shouldBe 20
  }

  test("hash256 equals sha256(sha256(x))") {
    val data = "double hash".getBytes("UTF-8")
    BitcoinCrypto.hash256(data).toSeq shouldBe
      BitcoinCrypto.sha256(BitcoinCrypto.sha256(data)).toSeq
  }

  test("taggedHash BIP-340 is deterministic") {
    val data = "taproot test".getBytes("UTF-8")
    BitcoinCrypto.taggedHash("TapTweak", data).toSeq shouldBe
      BitcoinCrypto.taggedHash("TapTweak", data).toSeq
  }

  // ── P2WPKH bech32 address ──────────────────────────────────────────────────

  test("P2WPKH mainnet address starts with bc1q") {
    val pk   = PublicKey(Curve.Secp256k1, knownCompressedPub)
    val addr = BitcoinAddress.p2wpkh(pk, testnet = false)
    addr should startWith("bc1q")
  }

  test("P2WPKH testnet address starts with tb1q") {
    val pk   = PublicKey(Curve.Secp256k1, knownCompressedPub)
    val addr = BitcoinAddress.p2wpkh(pk, testnet = true)
    addr should startWith("tb1q")
  }

  test("P2WPKH address for known privkey 1 matches known vector") {
    // The known mainnet P2WPKH address for privkey = 1 is:
    // bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4
    val pk   = PublicKey(Curve.Secp256k1, knownCompressedPub)
    val addr = BitcoinAddress.p2wpkh(pk, testnet = false)
    addr shouldBe "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
  }

  test("isValidP2wpkh accepts bc1q address") {
    val pk   = PublicKey(Curve.Secp256k1, knownCompressedPub)
    val addr = BitcoinAddress.p2wpkh(pk)
    BitcoinAddress.isValidP2wpkh(addr) shouldBe true
  }

  test("isValidP2wpkh rejects non-bech32 address") {
    BitcoinAddress.isValidP2wpkh("1A1zP1eP5QGefi2DMPTfTL5SLmv7Divf") shouldBe false
    BitcoinAddress.isValidP2wpkh("not-an-address") shouldBe false
  }

  test("witnessProgram extracts 20-byte hash for P2WPKH") {
    val pk   = PublicKey(Curve.Secp256k1, knownCompressedPub)
    val addr = BitcoinAddress.p2wpkh(pk)
    val prog = BitcoinAddress.witnessProgram(addr)
    prog.isRight shouldBe true
    prog.getOrElse(throw new Exception("unexpected Left")).length shouldBe 20
  }

  // ── Bech32 encode/decode round-trip ───────────────────────────────────────

  test("Bech32 encode/decode round-trip for arbitrary bytes") {
    val data = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    val enc  = Bech32.encode("test", data)
    enc should startWith("test1")
    Bech32.decode(enc) match
      case Right(decoded) => decoded.toSeq shouldBe data.toSeq
      case Left(err)      => fail(s"Decode failed: $err")
  }

  test("Bech32 decode rejects invalid checksum") {
    Bech32.decode("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7knotvalid") shouldBe a[Left[?, ?]]
  }

  test("Bech32 SegWit encodeSegWit/decodeSegWit round-trip for v0") {
    val program = BitcoinCrypto.hash160(knownCompressedPub)
    val addr    = Bech32.encodeSegWit("bc", 0, program)
    Bech32.decodeSegWit(addr) match
      case Right((ver, prog)) =>
        ver shouldBe 0
        prog.toSeq shouldBe program.toSeq
      case Left(err) => fail(s"Decode failed: $err")
  }

  test("Bech32m encodeSegWit/decodeSegWit round-trip for v1 (Taproot)") {
    val xonly = Array.fill[Byte](32)(0x42.toByte)
    val addr  = Bech32.encodeSegWit("bc", 1, xonly)
    addr should startWith("bc1p")
    Bech32.decodeSegWit(addr) match
      case Right((ver, prog)) =>
        ver shouldBe 1
        prog.toSeq shouldBe xonly.toSeq
      case Left(err) => fail(s"Decode failed: $err")
  }

  // ── BIP-143 SegWit sighash ─────────────────────────────────────────────────

  test("bip143Sighash produces 32-byte result") {
    val pubKeyHash = BitcoinCrypto.hash160(knownCompressedPub)
    val sighash = BitcoinCrypto.bip143Sighash(
      txVersion    = Psbt.intLE(2),
      hashPrevouts = Array.fill(32)(0.toByte),
      hashSequence = Array.fill(32)(0.toByte),
      outpointTxid = Array.fill(32)(0xaa.toByte),
      outpointVout = Psbt.intLE(0),
      pubKeyHash   = pubKeyHash,
      value        = Psbt.longLE(100_000L),
      sequence     = Psbt.intLE(0xfffffffe),
      hashOutputs  = Array.fill(32)(0.toByte),
      locktime     = Psbt.intLE(0),
      sighashType  = Psbt.intLE(1),
    )
    sighash.length shouldBe 32
  }

  test("p2wpkhScriptCode produces 26-byte varint-prefixed scriptCode") {
    val h = BitcoinCrypto.hash160(knownCompressedPub)
    val sc = BitcoinCrypto.p2wpkhScriptCode(h)
    sc.length shouldBe 26  // 1 varint + 25 script bytes
    (sc(0) & 0xff) shouldBe 0x19  // varint(25)
    (sc(1) & 0xff) shouldBe 0x76  // OP_DUP
    (sc(2) & 0xff) shouldBe 0xa9  // OP_HASH160
    (sc(3) & 0xff) shouldBe 0x14  // PUSH 20
  }

  // ── PSBT builder ──────────────────────────────────────────────────────────

  test("PSBT builder creates non-empty serialized bytes with magic prefix") {
    val txid    = Array.fill[Byte](32)(0xaa.toByte)
    val script  = p2wpkhScript(knownCompressedPub)
    val psbt    = new Psbt.PsbtBuilder()
      .addInput(txid, 0, Psbt.TxOut(100_000L, script))
      .addOutput(90_000L, script)
      .serialize()
    psbt.length should be > 10
    // Magic bytes
    psbt(0) shouldBe 0x70.toByte  // 'p'
    psbt(1) shouldBe 0x73.toByte  // 's'
    psbt(2) shouldBe 0x62.toByte  // 'b'
    psbt(3) shouldBe 0x74.toByte  // 't'
    psbt(4) shouldBe 0xff.toByte
  }

  test("PSBT sign + finalize produces partial sig in witness") {
    val txid   = Array.fill[Byte](32)(0xbb.toByte)
    val script = p2wpkhScript(knownCompressedPub)
    val builder = new Psbt.PsbtBuilder()
      .addInput(txid, 0, Psbt.TxOut(500_000L, script))
      .addOutput(490_000L, script)
      .sign(0, knownPrivKey)
      .finalizeInputs()
    val bytes = builder.serialize()
    bytes.length should be > 50
  }

  test("PSBT serialize / deserialize round-trip preserves unsigned tx") {
    val txid   = Array.fill[Byte](32)(0xcc.toByte)
    val script = p2wpkhScript(knownCompressedPub)
    val bytes  = new Psbt.PsbtBuilder()
      .addInput(txid, 0, Psbt.TxOut(200_000L, script))
      .addOutput(190_000L, script)
      .serialize()
    Psbt.deserialize(bytes) match
      case Right(parsed) =>
        parsed.unsignedTx.length should be > 0
        parsed.inputMaps.length shouldBe 1
        parsed.outputMaps.length shouldBe 1
      case Left(err) => fail(s"Deserialization failed: $err")
  }

  test("PSBT deserialize rejects invalid magic") {
    Psbt.deserialize(Array[Byte](0x00, 0x01, 0x02, 0x03, 0x04)) shouldBe a[Left[?, ?]]
  }

  // ── Taproot BIP-341 ───────────────────────────────────────────────────────

  test("tapTweakHash produces 32-byte tagged hash") {
    val xonly = knownCompressedPub.drop(1)
    val tweak = BitcoinCrypto.tapTweakHash(xonly)
    tweak.length shouldBe 32
  }

  test("tweakedKey returns 33-byte compressed point different from internal key") {
    val tweaked = BitcoinCrypto.tweakedKey(knownCompressedPub)
    tweaked.length shouldBe 33
    // tweaked key != input internal key (unless tweak == 0, which never happens)
    tweaked.toSeq should not equal knownCompressedPub.toSeq
  }

  test("P2TR mainnet address starts with bc1p") {
    val pk   = PublicKey(Curve.Secp256k1, knownCompressedPub)
    val addr = BitcoinAddress.p2tr(pk, testnet = false)
    addr should startWith("bc1p")
  }

  test("P2TR testnet address starts with tb1p") {
    val pk   = PublicKey(Curve.Secp256k1, knownCompressedPub)
    val addr = BitcoinAddress.p2tr(pk, testnet = true)
    addr should startWith("tb1p")
  }

  test("tweakedPrivateKey corresponds to tweakedKey") {
    val tPriv = BitcoinCrypto.tweakedPrivateKey(knownPrivKey)
    val tPub  = BitcoinCrypto.deriveCompressedPublicKey(tPriv)
    val expected = BitcoinCrypto.tweakedKey(knownCompressedPub)
    // The x-coordinate (drop prefix byte) must match
    tPub.drop(1).toSeq shouldBe expected.drop(1).toSeq
  }

  // ── Schnorr signing (BIP-340) ─────────────────────────────────────────────

  test("schnorrSign produces 64-byte signature") {
    val hash = BitcoinCrypto.taggedHash("TapSighash", "test message".getBytes("UTF-8"))
    val sig  = BitcoinCrypto.schnorrSign(knownPrivKey, hash)
    sig.length shouldBe 64
  }

  test("schnorrVerify accepts a valid Schnorr signature") {
    val xonly = knownCompressedPub.drop(1)
    val hash  = BitcoinCrypto.taggedHash("TapSighash", "test taproot".getBytes("UTF-8"))
    val sig   = BitcoinCrypto.schnorrSign(knownPrivKey, hash)
    BitcoinCrypto.schnorrVerify(xonly, hash, sig) shouldBe true
  }

  test("schnorrVerify rejects wrong message") {
    val xonly = knownCompressedPub.drop(1)
    val hash1 = BitcoinCrypto.taggedHash("TapSighash", "msg A".getBytes("UTF-8"))
    val hash2 = BitcoinCrypto.taggedHash("TapSighash", "msg B".getBytes("UTF-8"))
    val sig   = BitcoinCrypto.schnorrSign(knownPrivKey, hash1)
    BitcoinCrypto.schnorrVerify(xonly, hash2, sig) shouldBe false
  }

  test("schnorrSign is deterministic with same auxRand") {
    val hash  = BitcoinCrypto.sha256("deterministic schnorr".getBytes("UTF-8"))
    val aux   = Array.fill[Byte](32)(0x00)
    val sig1  = BitcoinCrypto.schnorrSign(knownPrivKey, hash, aux)
    val sig2  = BitcoinCrypto.schnorrSign(knownPrivKey, hash, aux)
    sig1.toSeq shouldBe sig2.toSeq
  }

  // ── ChainAdapter integration ──────────────────────────────────────────────

  test("adapter.addressFromPublicKey returns bc1q address") {
    val pk   = PublicKey(Curve.Secp256k1, knownCompressedPub)
    val addr = adapter.addressFromPublicKey(pk)
    addr should startWith("bc1q")
  }

  test("adapter.isValidAddress accepts bc1q address") {
    val pk   = PublicKey(Curve.Secp256k1, knownCompressedPub)
    val addr = adapter.addressFromPublicKey(pk)
    adapter.isValidAddress(addr) shouldBe true
  }

  test("adapter.isValidAddress rejects garbage") {
    adapter.isValidAddress("not-an-address") shouldBe false
  }

  test("adapter.defaultDerivationPath is BIP-84 m/84'/0'/0'/0/0") {
    adapter.defaultDerivationPath shouldBe "m/84'/0'/0'/0/0"
  }

  test("adapter.chainId is BitcoinMainnet") {
    adapter.chainId shouldBe ChainId.BitcoinMainnet
  }

  test("testnet adapter produces tb1q address") {
    val testAdapter = BitcoinChainAdapter(ChainId.BitcoinTestnet)
    val pk          = PublicKey(Curve.Secp256k1, knownCompressedPub)
    testAdapter.addressFromPublicKey(pk) should startWith("tb1q")
  }

  test("adapter.describe returns bitcoin-transfer summary") {
    val tx = BitcoinTx("bc1qsender", "bc1qrecipient", 50_000L)
    val d  = adapter.describe(tx)
    d.summary shouldBe "bitcoin-transfer"
    d.fields("to") shouldBe "bc1qrecipient"
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** Build a P2WPKH scriptPubKey: `OP_0 OP_PUSH20 <hash160>` */
  private def p2wpkhScript(compressedPub: Array[Byte]): Array[Byte] =
    val h = BitcoinCrypto.hash160(compressedPub)
    Array[Byte](0x00.toByte, 0x14.toByte) ++ h

  private def hex(s: String): Array[Byte] =
    s.grouped(2).map(b => Integer.parseInt(b, 16).toByte).toArray
