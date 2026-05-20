package scalascript.blockchain.cardano

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration.*
import scalascript.blockchain.spi.*
import scalascript.blockfrost.{BlockfrostClient, AddressInfo, BlockfrostUtxo}
import scalascript.crypto.{CryptoBackend, Curve, HashAlgo, PublicKey}

class CardanoChainAdapterTest extends AnyFunSuite with Matchers:

  given ExecutionContext = ExecutionContext.global

  // ── stub helpers ─────────────────────────────────────────────────────────

  val stubCtx: ChainContext = new ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      Future.failed(new UnsupportedOperationException("stub"))
    def nowSeconds: Long = System.currentTimeMillis() / 1000

  class StubBlockfrostClient(
    utxos:   Seq[BlockfrostUtxo] = Nil,
    balance: BigInt              = BigInt(10_000_000),
    txHash:  String              = "abc123",
  ) extends BlockfrostClient:
    def getAddressInfo(address: String): Future[AddressInfo] =
      Future.successful(AddressInfo(address, balance, Map.empty))
    def isTxConfirmed(hash: String): Future[Boolean] =
      Future.successful(true)
    def getUtxos(address: String): Future[Seq[BlockfrostUtxo]] =
      Future.successful(utxos)
    def submitTx(cbor: Array[Byte]): Future[String] =
      Future.successful(txHash)

  val utxo1 = BlockfrostUtxo("a" * 64, 0, BigInt(5_000_000), Map.empty)
  val utxo2 = BlockfrostUtxo("b" * 64, 1, BigInt(8_000_000), Map.empty)

  val adapter = CardanoChainAdapter(ChainId.CardanoMainnet, StubBlockfrostClient(Seq(utxo1, utxo2)))

  private def derivePath(seed: Array[Byte], path: String): (Array[Byte], PublicKey) =
    val backend  = CryptoBackend.get()
    val segments = path.drop(2).split('/').map { seg =>
      if seg.endsWith("'") then (seg.dropRight(1).toLong, true)
      else                      (seg.toLong, false)
    }.toSeq
    val master   = backend.deriveMaster(Curve.Ed25519, seed)
    val leaf     = segments.foldLeft(master) { case (node, (idx, hard)) =>
      backend.deriveChild(Curve.Ed25519, node, idx, hard)
    }
    val pubBytes = backend.derivePublic(Curve.Ed25519, leaf.privateKey)
    (leaf.privateKey, PublicKey(Curve.Ed25519, pubBytes))

  // ── address derivation ───────────────────────────────────────────────────

  test("addressFromPublicKey produces mainnet addr1 bech32") {
    val (_, pk) = derivePath(Array.fill[Byte](64)(0x42.toByte), "m/1852'/1815'/0'/0'/0'")
    val addr    = adapter.addressFromPublicKey(pk)
    addr should startWith("addr1")
  }

  test("isValidAddress accepts addr1 bech32") {
    val (_, pk) = derivePath(Array.fill[Byte](64)(0x99.toByte), "m/1852'/1815'/0'/0'/0'")
    val addr    = adapter.addressFromPublicKey(pk)
    adapter.isValidAddress(addr) shouldBe true
  }

  test("isValidAddress rejects garbage") {
    adapter.isValidAddress("not-an-address") shouldBe false
  }

  test("testnet adapter produces addr_test1 addresses") {
    val testnetAdapter = CardanoChainAdapter(ChainId.CardanoPreprod, StubBlockfrostClient())
    val (_, pk)        = derivePath(Array.fill[Byte](64)(0x11.toByte), "m/1852'/1815'/0'/0'/0'")
    testnetAdapter.addressFromPublicKey(pk) should startWith("addr_test1")
  }

  // ── base addresses (CIP-19 type 0/1) ─────────────────────────────────────

  test("CardanoAddress.fromPublicKeys: mainnet base address starts with addr1") {
    val (_, payment) = derivePath(Array.fill[Byte](64)(0x42.toByte), "m/1852'/1815'/0'/0'/0'")
    val (_, stake)   = derivePath(Array.fill[Byte](64)(0x42.toByte), "m/1852'/1815'/0'/2'/0'")
    val addr         = CardanoAddress.fromPublicKeys(payment, stake, testnet = false)
    addr should startWith("addr1")
    val bytes        = Bech32.decode(addr).getOrElse(fail("must decode"))
    bytes.length shouldBe 57   // header + 28 payment + 28 stake
    (bytes(0) & 0xFF) shouldBe 0x00
  }

  test("CardanoAddress.fromPublicKeys: testnet base address starts with addr_test1") {
    val (_, payment) = derivePath(Array.fill[Byte](64)(0x33.toByte), "m/1852'/1815'/0'/0'/0'")
    val (_, stake)   = derivePath(Array.fill[Byte](64)(0x33.toByte), "m/1852'/1815'/0'/2'/0'")
    val addr         = CardanoAddress.fromPublicKeys(payment, stake, testnet = true)
    addr should startWith("addr_test1")
    val bytes        = Bech32.decode(addr).getOrElse(fail("must decode"))
    (bytes(0) & 0xFF) shouldBe 0x10
  }

  test("CardanoAddress.fromPublicKeys: payment+stake hash prefix matches enterprise payment hash") {
    val (_, payment) = derivePath(Array.fill[Byte](64)(0x55.toByte), "m/1852'/1815'/0'/0'/0'")
    val (_, stake)   = derivePath(Array.fill[Byte](64)(0x55.toByte), "m/1852'/1815'/0'/2'/0'")
    val baseAddr = CardanoAddress.fromPublicKeys(payment, stake, testnet = false)
    val entAddr  = CardanoAddress.fromPublicKey(payment, testnet = false)
    val baseBytes = Bech32.decode(baseAddr).getOrElse(fail("base must decode"))
    val entBytes  = Bech32.decode(entAddr).getOrElse(fail("enterprise must decode"))
    // header byte differs (0x00 vs 0x60); the next 28 bytes (payment hash) must match
    baseBytes.slice(1, 29).toSeq shouldBe entBytes.slice(1, 29).toSeq
  }

  test("CardanoAddress.kindOf: distinguishes enterprise from base") {
    val (_, payment) = derivePath(Array.fill[Byte](64)(0x77.toByte), "m/1852'/1815'/0'/0'/0'")
    val (_, stake)   = derivePath(Array.fill[Byte](64)(0x77.toByte), "m/1852'/1815'/0'/2'/0'")
    CardanoAddress.kindOf(CardanoAddress.fromPublicKey(payment))             shouldBe CardanoAddress.Kind.Enterprise
    CardanoAddress.kindOf(CardanoAddress.fromPublicKeys(payment, stake))     shouldBe CardanoAddress.Kind.Base
  }

  // ── balances ─────────────────────────────────────────────────────────────

  test("nativeBalance returns lovelace from stub") {
    val result = Await.result(adapter.nativeBalance("addr1test", stubCtx), 5.seconds)
    result shouldBe BigInt(10_000_000)
  }

  test("tokenBalance returns 0 when asset absent") {
    val asset  = Asset(ChainId.CardanoMainnet, "policyidassetname", "TOKEN", 0)
    val result = Await.result(adapter.tokenBalance(asset, "addr1test", stubCtx), 5.seconds)
    result shouldBe BigInt(0)
  }

  // ── buildTransaction ─────────────────────────────────────────────────────

  test("buildTransaction NativeTransfer selects UTxOs and calculates change") {
    val intent = TxIntent.NativeTransfer("addr1recipient", BigInt(3_000_000))
    val tx     = Await.result(adapter.buildTransaction(intent, "addr1sender", stubCtx), 5.seconds)
    tx.lovelace shouldBe BigInt(3_000_000)
    tx.fee      shouldBe BigInt(180_000)
    tx.inputs.nonEmpty shouldBe true
    (tx.change + tx.lovelace + tx.fee) shouldBe tx.inputs.map(_.lovelace).sum
  }

  test("buildTransaction fails when insufficient UTxOs") {
    val poorAdapter = CardanoChainAdapter(
      ChainId.CardanoMainnet,
      StubBlockfrostClient(utxos = Seq(BlockfrostUtxo("c" * 64, 0, BigInt(100_000), Map.empty)))
    )
    val intent = TxIntent.NativeTransfer("addr1to", BigInt(5_000_000))
    intercept[Exception](Await.result(poorAdapter.buildTransaction(intent, "addr1from", stubCtx), 5.seconds))
  }

  // ── signing + broadcast ──────────────────────────────────────────────────

  test("prepareSigningPayload returns 32-byte Blake2b-256 hash") {
    val (_, pk) = derivePath(Array.fill[Byte](64)(0x55.toByte), "m/1852'/1815'/0'/0'/0'")
    val intent  = TxIntent.NativeTransfer("addr1to", BigInt(2_000_000))
    val tx      = Await.result(adapter.buildTransaction(intent, "addr1from", stubCtx), 5.seconds)
    val payload = adapter.prepareSigningPayload(tx, pk)
    payload.bytes.length shouldBe 32
  }

  test("broadcast returns TxHash from stub") {
    val backend     = CryptoBackend.get()
    val (privKey, pk) = derivePath(Array.fill[Byte](64)(0x77.toByte), "m/1852'/1815'/0'/0'/0'")
    val intent      = TxIntent.NativeTransfer("addr1to", BigInt(1_000_000))
    val tx          = Await.result(adapter.buildTransaction(intent, "addr1from", stubCtx), 5.seconds)
    val payload     = adapter.prepareSigningPayload(tx, pk)
    val sig         = backend.sign(Curve.Ed25519, privKey, payload.bytes, HashAlgo.None)
    val signedTx    = adapter.assembleSignedTransaction(tx, sig, pk)
    val result      = Await.result(adapter.broadcast(signedTx, stubCtx), 5.seconds)
    result.value shouldBe "abc123"
  }

  // ── describe ─────────────────────────────────────────────────────────────

  test("describe returns cardano-transfer summary") {
    val intent = TxIntent.NativeTransfer("addr1to", BigInt(1_000_000))
    val tx     = Await.result(adapter.buildTransaction(intent, "addr1from", stubCtx), 5.seconds)
    val desc   = adapter.describe(tx)
    desc.summary shouldBe "cardano-transfer"
    desc.fields("to") shouldBe "addr1to"
  }

  // ── CIP-8 typed data ─────────────────────────────────────────────────────

  test("typedDataDigest returns non-empty bytes for Raw payload") {
    val digest = adapter.typedDataDigest(TypedData.Raw("hello cardano".getBytes("UTF-8")))
    digest.nonEmpty shouldBe true
  }

  test("getReceipt returns confirmed receipt from stub") {
    val result = Await.result(adapter.getReceipt(TxHash("abc123"), stubCtx), 5.seconds)
    result.isDefined   shouldBe true
    result.get.success shouldBe true
  }

  // ── nonceOf ──────────────────────────────────────────────────────────────

  test("nonceOf returns 0 (UTxO model)") {
    Await.result(adapter.nonceOf("addr1any", stubCtx), 5.seconds) shouldBe BigInt(0)
  }

  // ── CBOR codec ───────────────────────────────────────────────────────────

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

  // ── Bech32 ───────────────────────────────────────────────────────────────

  test("Bech32 encode/decode roundtrip") {
    val data = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
    val enc  = Bech32.encode("test", data)
    enc should startWith("test1")
    Bech32.decode(enc) match
      case Right(decoded) => decoded.toSeq shouldBe data.toSeq
      case Left(err)      => fail(s"Decode failed: $err")
  }

  test("Bech32 decode rejects wrong checksum") {
    Bech32.decode("test1qqqqqqq") shouldBe a[Left[?, ?]]
  }
