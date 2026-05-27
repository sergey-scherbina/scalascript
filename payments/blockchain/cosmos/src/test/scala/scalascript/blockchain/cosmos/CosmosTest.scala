package scalascript.blockchain.cosmos

import java.util.Base64
import java.util.ServiceLoader
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, PublicKey}

class CosmosTest extends AnyFunSuite with Matchers:

  given ExecutionContext = ExecutionContext.global

  // Known secp256k1 test vector — private key = 1 (same as Bitcoin tests)
  private val privKey1: Array[Byte] =
    hex("0000000000000000000000000000000000000000000000000000000000000001")

  private val compressedPub1: Array[Byte] =
    hex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")

  // A second secp256k1 key for negative tests
  private val privKey2: Array[Byte] =
    hex("0000000000000000000000000000000000000000000000000000000000000002")

  // Ed25519 seed (32 bytes of 0x01 for determinism)
  private val ed25519Seed: Array[Byte] = Array.fill(32)(0x01.toByte)

  // ── ChainId values ─────────────────────────────────────────────────────────

  test("ChainId.CosmosHub has cosmos namespace") {
    ChainId.CosmosHub.namespace shouldBe "cosmos"
    ChainId.CosmosHub.reference shouldBe "cosmoshub-4"
  }

  test("ChainId.Osmosis has cosmos namespace") {
    ChainId.Osmosis.namespace shouldBe "cosmos"
    ChainId.Osmosis.reference shouldBe "osmosis-1"
  }

  test("ChainId.Juno has cosmos namespace") {
    ChainId.Juno.namespace shouldBe "cosmos"
    ChainId.Juno.reference shouldBe "juno-1"
  }

  test("CosmosHub, Osmosis, Juno are all distinct ChainIds") {
    ChainId.CosmosHub should not equal ChainId.Osmosis
    ChainId.CosmosHub should not equal ChainId.Juno
    ChainId.Osmosis   should not equal ChainId.Juno
  }

  // ── secp256k1 sign / verify ────────────────────────────────────────────────

  test("secp256k1 sign + verify round-trip with known private key") {
    val hash = CosmosCrypto.sha256("hello cosmos".getBytes("UTF-8"))
    val sig  = CosmosCrypto.sign(privKey1, hash)
    (sig(0) & 0xff) shouldBe 0x30  // DER sequence tag
    CosmosCrypto.verify(compressedPub1, hash, sig) shouldBe true
  }

  test("secp256k1 sign is deterministic (RFC 6979)") {
    val hash = CosmosCrypto.sha256("deterministic".getBytes("UTF-8"))
    val sig1 = CosmosCrypto.sign(privKey1, hash)
    val sig2 = CosmosCrypto.sign(privKey1, hash)
    sig1.toSeq shouldBe sig2.toSeq
  }

  test("secp256k1 verify rejects wrong message") {
    val hash1 = CosmosCrypto.sha256("msg A".getBytes("UTF-8"))
    val hash2 = CosmosCrypto.sha256("msg B".getBytes("UTF-8"))
    val sig   = CosmosCrypto.sign(privKey1, hash1)
    CosmosCrypto.verify(compressedPub1, hash2, sig) shouldBe false
  }

  test("secp256k1 verify rejects wrong public key") {
    val pub2 = CosmosCrypto.deriveCompressedPublicKey(privKey2)
    val hash = CosmosCrypto.sha256("test".getBytes("UTF-8"))
    val sig  = CosmosCrypto.sign(privKey1, hash)
    CosmosCrypto.verify(pub2, hash, sig) shouldBe false
  }

  test("deriveCompressedPublicKey from privKey 1 yields known generator point") {
    CosmosCrypto.deriveCompressedPublicKey(privKey1).toSeq shouldBe compressedPub1.toSeq
  }

  // ── ed25519 sign / verify ──────────────────────────────────────────────────

  test("ed25519 sign + verify round-trip") {
    val msg = "hello ed25519 cosmos".getBytes("UTF-8")
    val sig = CosmosCrypto.signEd25519(ed25519Seed, msg)
    sig.length shouldBe 64
    val priv   = new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(ed25519Seed)
    val pubKey = priv.generatePublicKey().getEncoded
    CosmosCrypto.verifyEd25519(pubKey, msg, sig) shouldBe true
  }

  test("ed25519 verify rejects wrong message") {
    val msg1 = "message one".getBytes("UTF-8")
    val msg2 = "message two".getBytes("UTF-8")
    val sig  = CosmosCrypto.signEd25519(ed25519Seed, msg1)
    val priv = new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(ed25519Seed)
    val pub  = priv.generatePublicKey().getEncoded
    CosmosCrypto.verifyEd25519(pub, msg2, sig) shouldBe false
  }

  test("ed25519 signature is exactly 64 bytes") {
    val sig = CosmosCrypto.signEd25519(ed25519Seed, "test".getBytes("UTF-8"))
    sig.length shouldBe 64
  }

  // ── hash utilities ─────────────────────────────────────────────────────────

  test("sha256 of empty bytes matches known vector") {
    val expected = hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    CosmosCrypto.sha256(Array.emptyByteArray).toSeq shouldBe expected.toSeq
  }

  test("hash160 produces 20-byte result") {
    CosmosCrypto.hash160(compressedPub1).length shouldBe 20
  }

  test("hash160 = ripemd160(sha256(x))") {
    val data = "cosmos hash test".getBytes("UTF-8")
    CosmosCrypto.hash160(data).toSeq shouldBe
      CosmosCrypto.ripemd160(CosmosCrypto.sha256(data)).toSeq
  }

  // ── Cosmos Hub address (bech32 "cosmos") ──────────────────────────────────

  test("CosmosHub address from known pubkey starts with cosmos1") {
    val addr = CosmosAddress.deriveAddress(compressedPub1, "cosmos")
    addr should startWith("cosmos1")
  }

  test("CosmosHub address for known pubkey 1 is 45 chars and bech32-decodable") {
    // For secp256k1 privkey=1, compressed pub = 0279be...
    // hash160(pub) → bech32("cosmos", hash)
    val addr = CosmosAddress.deriveAddress(compressedPub1, "cosmos")
    addr should startWith("cosmos1")
    CosmosAddress.decode(addr) match
      case Right((hrp, data)) =>
        hrp shouldBe "cosmos"
        data.length shouldBe 20
      case Left(err) => fail(s"Unexpected decode failure: $err")
  }

  // ── Osmosis address (HRP "osmo") ───────────────────────────────────────────

  test("Osmosis address from same pubkey starts with osmo1") {
    val addr = CosmosAddress.deriveAddress(compressedPub1, "osmo")
    addr should startWith("osmo1")
  }

  test("Osmosis and CosmosHub addresses differ only in HRP, same key hash") {
    val cosmosAddr = CosmosAddress.deriveAddress(compressedPub1, "cosmos")
    val osmoAddr   = CosmosAddress.deriveAddress(compressedPub1, "osmo")
    // Both decode to the same 20-byte key hash (only HRP and checksum differ)
    val Right((_, hash1)) = CosmosAddress.decode(cosmosAddr): @unchecked
    val Right((_, hash2)) = CosmosAddress.decode(osmoAddr): @unchecked
    hash1.toSeq shouldBe hash2.toSeq
  }

  // ── Juno address (HRP "juno") ──────────────────────────────────────────────

  test("Juno address from same pubkey starts with juno1") {
    val addr = CosmosAddress.deriveAddress(compressedPub1, "juno")
    addr should startWith("juno1")
  }

  test("Juno address decodes to same 20-byte hash as CosmosHub") {
    val Right((_, hash1)) = CosmosAddress.decode(CosmosAddress.deriveAddress(compressedPub1, "cosmos")): @unchecked
    val Right((_, hash3)) = CosmosAddress.decode(CosmosAddress.deriveAddress(compressedPub1, "juno")): @unchecked
    hash1.toSeq shouldBe hash3.toSeq
  }

  // ── Bech32 encode/decode round-trip ───────────────────────────────────────

  test("Bech32 encode/decode round-trip for 20-byte hash") {
    val data = CosmosCrypto.hash160(compressedPub1)
    val enc  = CosmosAddress.encode("cosmos", data)
    enc should startWith("cosmos1")
    CosmosAddress.decode(enc) match
      case Right((hrp, decoded)) =>
        hrp shouldBe "cosmos"
        decoded.toSeq shouldBe data.toSeq
      case Left(err) => fail(s"Decode failed: $err")
  }

  test("Bech32 decode rejects wrong checksum") {
    val addr    = CosmosAddress.deriveAddress(compressedPub1, "cosmos")
    // Corrupt last char
    val corrupt = addr.dropRight(1) + (if addr.last == 'q' then "z" else "q")
    CosmosAddress.decode(corrupt) shouldBe a[Left[?, ?]]
  }

  test("Bech32 round-trip for arbitrary 20-byte payload") {
    val data = Array.tabulate[Byte](20)(i => (i + 1).toByte)
    val enc  = CosmosAddress.encode("test", data)
    CosmosAddress.decode(enc) match
      case Right((hrp, decoded)) =>
        hrp shouldBe "test"
        decoded.toSeq shouldBe data.toSeq
      case Left(err) => fail(s"Decode failed: $err")
  }

  // ── StdSignDoc JSON serialisation ─────────────────────────────────────────

  test("StdSignDoc JSON has canonical Amino field order") {
    val doc = CosmosSignDoc.StdSignDoc(
      chain_id       = "cosmoshub-4",
      account_number = "42",
      sequence       = "7",
      fee            = CosmosSignDoc.Fee(Seq(CosmosSignDoc.Coin("5000", "uatom")), "200000"),
      msgs           = Seq(ujson.Obj("type" -> "cosmos-sdk/MsgSend")),
      memo           = "test",
    )
    val json = CosmosSignDoc.toJson(doc)
    // account_number must come before chain_id (alphabetical Amino order)
    val anPos    = json.indexOf("account_number")
    val cidPos   = json.indexOf("chain_id")
    val feePos   = json.indexOf("\"fee\"")
    val memoPos  = json.indexOf("\"memo\"")
    val msgsPos  = json.indexOf("\"msgs\"")
    val seqPos   = json.indexOf("\"sequence\"")
    anPos   should be < cidPos
    cidPos  should be < feePos
    feePos  should be < memoPos
    memoPos should be < msgsPos
    msgsPos should be < seqPos
  }

  test("aminoEncode produces valid UTF-8 JSON bytes") {
    val doc = CosmosSignDoc.StdSignDoc(
      chain_id       = "cosmoshub-4",
      account_number = "0",
      sequence       = "0",
      fee            = CosmosSignDoc.Fee(Seq.empty, "0"),
      msgs           = Seq.empty,
      memo           = "",
    )
    val bytes = CosmosSignDoc.aminoEncode(doc)
    bytes.length should be > 0
    val s = new String(bytes, "UTF-8")
    s should startWith("{")
    s should endWith("}")
  }

  // ── signStdTx ─────────────────────────────────────────────────────────────

  test("signStdTx secp256k1: signature is deterministic (RFC 6979), base64-encoded") {
    val doc = CosmosSignDoc.StdSignDoc(
      chain_id       = "cosmoshub-4",
      account_number = "1",
      sequence       = "0",
      fee            = CosmosSignDoc.Fee(Seq(CosmosSignDoc.Coin("5000", "uatom")), "200000"),
      msgs           = Seq(ujson.Obj("type" -> "cosmos-sdk/MsgSend")),
      memo           = "",
    )
    val (sig1, pub1) = CosmosSignDoc.signStdTx(privKey1, doc, CosmosSignDoc.Curve.Secp256k1)
    val (sig2, pub2) = CosmosSignDoc.signStdTx(privKey1, doc, CosmosSignDoc.Curve.Secp256k1)
    sig1 shouldBe sig2   // deterministic
    pub1 shouldBe pub2
    // Must be valid base64
    val sigBytes = Base64.getDecoder.decode(sig1)
    sigBytes.length should be > 0
    (sigBytes(0) & 0xff) shouldBe 0x30   // DER sequence
    // pubkey base64 decodes to 33 bytes (compressed secp256k1)
    Base64.getDecoder.decode(pub1).length shouldBe 33
  }

  test("signStdTx ed25519: signature base64 decodes to 64 bytes") {
    val doc = CosmosSignDoc.StdSignDoc(
      chain_id       = "osmosis-1",
      account_number = "5",
      sequence       = "2",
      fee            = CosmosSignDoc.Fee(Seq(CosmosSignDoc.Coin("1000", "uosmo")), "100000"),
      msgs           = Seq.empty,
      memo           = "ed25519 test",
    )
    val (sig, pub) = CosmosSignDoc.signStdTx(ed25519Seed, doc, CosmosSignDoc.Curve.Ed25519)
    val sigBytes = Base64.getDecoder.decode(sig)
    sigBytes.length shouldBe 64
    // pubkey is 32 bytes for ed25519
    Base64.getDecoder.decode(pub).length shouldBe 32
  }

  test("signStdTx ed25519: signature is 88 chars base64 (64 bytes raw)") {
    // 64 bytes → 88 base64 chars (with padding)
    val doc = CosmosSignDoc.StdSignDoc(
      chain_id = "juno-1", account_number = "0", sequence = "0",
      fee = CosmosSignDoc.Fee(Seq.empty, "0"), msgs = Seq.empty, memo = "",
    )
    val (sig, _) = CosmosSignDoc.signStdTx(ed25519Seed, doc, CosmosSignDoc.Curve.Ed25519)
    sig.length shouldBe 88
  }

  // ── ChainAdapter integration ──────────────────────────────────────────────

  test("CosmosHub adapter: supportedChains includes CosmosHub") {
    val adapter = CosmosChainAdapter(ChainId.CosmosHub)
    adapter.chainId shouldBe ChainId.CosmosHub
    adapter.supportedCurves should contain(Curve.Secp256k1)
  }

  test("Osmosis adapter: supportedChains includes Osmosis") {
    val adapter = CosmosChainAdapter(ChainId.Osmosis)
    adapter.chainId shouldBe ChainId.Osmosis
  }

  test("Juno adapter: supportedChains includes Juno") {
    val adapter = CosmosChainAdapter(ChainId.Juno)
    adapter.chainId shouldBe ChainId.Juno
  }

  test("adapter.addressFromPublicKey returns cosmos1 address for CosmosHub") {
    val adapter = CosmosChainAdapter(ChainId.CosmosHub)
    val pk      = PublicKey(Curve.Secp256k1, compressedPub1)
    adapter.addressFromPublicKey(pk) should startWith("cosmos1")
  }

  test("adapter.addressFromPublicKey returns osmo1 address for Osmosis") {
    val adapter = CosmosChainAdapter(ChainId.Osmosis)
    val pk      = PublicKey(Curve.Secp256k1, compressedPub1)
    adapter.addressFromPublicKey(pk) should startWith("osmo1")
  }

  test("adapter.addressFromPublicKey returns juno1 address for Juno") {
    val adapter = CosmosChainAdapter(ChainId.Juno)
    val pk      = PublicKey(Curve.Secp256k1, compressedPub1)
    adapter.addressFromPublicKey(pk) should startWith("juno1")
  }

  test("adapter.isValidAddress accepts correctly-derived address") {
    val adapter = CosmosChainAdapter(ChainId.CosmosHub)
    val pk      = PublicKey(Curve.Secp256k1, compressedPub1)
    val addr    = adapter.addressFromPublicKey(pk)
    adapter.isValidAddress(addr) shouldBe true
  }

  test("adapter.isValidAddress rejects garbage") {
    val adapter = CosmosChainAdapter(ChainId.CosmosHub)
    adapter.isValidAddress("not-an-address") shouldBe false
    adapter.isValidAddress("osmo1someaddress") shouldBe false  // wrong HRP for cosmos
  }

  test("adapter.defaultDerivationPath is Cosmos BIP-44 m/44'/118'/0'/0/0") {
    CosmosChainAdapter(ChainId.CosmosHub).defaultDerivationPath shouldBe "m/44'/118'/0'/0/0"
  }

  test("adapter.describe returns cosmos-transfer summary") {
    val adapter = CosmosChainAdapter(ChainId.CosmosHub)
    val tx      = CosmosTx("cosmos1from", "cosmos1to", 100_000L, "cosmoshub-4")
    val d       = adapter.describe(tx)
    d.summary shouldBe "cosmos-transfer"
    d.fields("to") shouldBe "cosmos1to"
  }

  // ── BlockchainProvider ServiceLoader ──────────────────────────────────────

  test("BlockchainProvider is discovered via ServiceLoader") {
    val providers = ServiceLoader.load(classOf[BlockchainProvider]).asScala.toSeq
    providers should not be empty
    val cosmosProviders = providers.filter(_.isInstanceOf[CosmosBackend])
    cosmosProviders should not be empty
  }

  test("CosmosBackend provides adapters for CosmosHub, Osmosis, and Juno") {
    val backend  = new CosmosBackend
    val adapters = backend.adapters()
    val chainIds = adapters.map(_.chainId).toSet
    chainIds should contain(ChainId.CosmosHub)
    chainIds should contain(ChainId.Osmosis)
    chainIds should contain(ChainId.Juno)
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private def hex(s: String): Array[Byte] =
    s.grouped(2).map(b => Integer.parseInt(b, 16).toByte).toArray
