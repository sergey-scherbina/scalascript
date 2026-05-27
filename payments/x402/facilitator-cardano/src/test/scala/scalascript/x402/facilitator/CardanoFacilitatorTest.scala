package scalascript.x402.facilitator

import scalascript.x402.*
import scalascript.blockfrost.{BlockfrostConfig, AddressInfo, BlockfrostClient, BlockfrostUtxo}
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global

// ── Test helpers ──────────────────────────────────────────────────────────────

// Mocks only need to override the BlockfrostClient methods this suite
// actually exercises (`getAddressInfo` + `isTxConfirmed`); UTxO/submit are
// stubbed here so each test stays focused.
private trait MockBlockfrostBase extends BlockfrostClient:
  def getUtxos(address: String): Future[Seq[BlockfrostUtxo]] =
    Future.failed(NotImplementedError("getUtxos not used in this test"))
  def submitTx(cbor: Array[Byte]): Future[String] =
    Future.failed(NotImplementedError("submitTx not used in this test"))

class CardanoFacilitatorTest extends AnyFunSuite:

  // Generates a real Ed25519 COSE_Sign1 proof using BouncyCastle
  private def makeCip8Proof(message: Array[Byte]): CardanoPaymentProof =
    import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
    import org.bouncycastle.crypto.params.{Ed25519KeyGenerationParameters, Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters}
    import java.security.SecureRandom

    val gen = Ed25519KeyPairGenerator()
    gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
    val pair    = gen.generateKeyPair()
    val privKey = pair.getPrivate.asInstanceOf[Ed25519PrivateKeyParameters]
    val pubKey  = pair.getPublic.asInstanceOf[Ed25519PublicKeyParameters]

    // Protected header: {1: -8}  (alg: EdDSA)
    val protMap = MiniCbor.encode(MiniCbor.Map(IndexedSeq(
      MiniCbor.UInt(1) -> MiniCbor.NInt(7),   // alg: -(7+1) = -8 = EdDSA
    )))

    // Sig_Structure = ["Signature1", prot_bstr, h"", payload]
    val sigStructure = MiniCbor.encode(MiniCbor.Arr(IndexedSeq(
      MiniCbor.Text("Signature1"),
      MiniCbor.Bytes(protMap),
      MiniCbor.Bytes(Array.empty),
      MiniCbor.Bytes(message),
    )))

    // Sign
    import org.bouncycastle.crypto.signers.Ed25519Signer
    val signer = Ed25519Signer()
    signer.init(true, privKey)
    signer.update(sigStructure, 0, sigStructure.length)
    val sig = signer.generateSignature()

    // COSE_Sign1 = [prot_bstr, {}, payload_bstr, sig_bstr]
    val coseSign1 = MiniCbor.encode(MiniCbor.Arr(IndexedSeq(
      MiniCbor.Bytes(protMap),
      MiniCbor.Map(IndexedSeq.empty),
      MiniCbor.Bytes(message),
      MiniCbor.Bytes(sig),
    )))

    // COSE_Key = {1: 1, 3: -8, -1: 6, -2: pubkey_bytes}
    val coseKey = MiniCbor.encode(MiniCbor.Map(IndexedSeq(
      MiniCbor.UInt(1)  -> MiniCbor.UInt(1),                    // kty: OKP
      MiniCbor.UInt(3)  -> MiniCbor.NInt(7),                    // alg: EdDSA
      MiniCbor.NInt(0)  -> MiniCbor.UInt(6),                    // crv: Ed25519
      MiniCbor.NInt(1)  -> MiniCbor.Bytes(pubKey.getEncoded),   // x: raw pubkey
    )))

    CardanoPaymentProof(
      address   = "addr_test1",
      signature = bytesToHex(coseSign1),
      key       = bytesToHex(coseKey),
    )

  private def bytesToHex(b: Array[Byte]): String =
    b.map(x => f"${x & 0xFF}%02x").mkString

  private def scalusClaimMessage(receiver: String, lovelace: BigInt, validBefore: BigInt): Array[Byte] =
    import scalascript.blockchain.cardano.CardanoAddress
    ScalusClaimMessageCodec.encode(CardanoAddress.toBytes(receiver), lovelace, validBefore)

  private val testReq = PaymentRequirements(
    scheme      = PaymentScheme.CardanoExact(BigInt(2_000_000), None),
    network     = Network.Base,
    asset       = Assets.USDC_BASE,
    payTo       = "addr1receiver",
    resource    = "/api/premium",
    description = "Cardano test payment",
  )

  private val testAuth = TransferAuthorization(
    from = "addr_test1", to = "addr1receiver",
    value = BigInt(2_000_000), validAfter = BigInt(0),
    validBefore = BigInt(9_999_999_999L), nonce = "0x" + "aa" * 32,
  )

  // ── MiniCbor round-trip ───────────────────────────────────────────────────────

  test("MiniCbor: encode/decode UInt round-trip") {
    val v = MiniCbor.UInt(42)
    assert(MiniCbor.decode(MiniCbor.encode(v)) == v)
  }

  test("MiniCbor: encode/decode NInt round-trip") {
    val v = MiniCbor.NInt(7)   // represents -8
    assert(MiniCbor.decode(MiniCbor.encode(v)) == v)
  }

  test("MiniCbor: encode/decode Bytes round-trip") {
    val v = MiniCbor.Bytes(Array[Byte](1, 2, 3, 4))
    val decoded = MiniCbor.decode(MiniCbor.encode(v))
    decoded match
      case MiniCbor.Bytes(b) => assert(b.toSeq == Seq[Byte](1, 2, 3, 4))
      case _                  => fail("expected Bytes")
  }

  test("MiniCbor: encode/decode Text round-trip") {
    val v = MiniCbor.Text("Signature1")
    assert(MiniCbor.decode(MiniCbor.encode(v)) == v)
  }

  test("MiniCbor: encode/decode Arr round-trip") {
    val v = MiniCbor.Arr(IndexedSeq(MiniCbor.UInt(1), MiniCbor.Text("hello")))
    assert(MiniCbor.decode(MiniCbor.encode(v)) == v)
  }

  test("MiniCbor: encode/decode Map round-trip") {
    val v = MiniCbor.Map(IndexedSeq(MiniCbor.UInt(1) -> MiniCbor.NInt(7)))
    assert(MiniCbor.decode(MiniCbor.encode(v)) == v)
  }

  // ── Cip8Verifier ─────────────────────────────────────────────────────────────

  test("Cip8Verifier: valid signature verifies Ok") {
    val message = "Cardano test payment".getBytes("UTF-8")
    val proof   = makeCip8Proof(message)
    assert(Cip8Verifier.verify(proof, message))
  }

  test("Cip8Verifier: wrong message → false") {
    val proof = makeCip8Proof("correct message".getBytes("UTF-8"))
    assert(!Cip8Verifier.verify(proof, "wrong message".getBytes("UTF-8")))
  }

  test("Cip8Verifier: corrupted signature bytes → false") {
    val message = "test".getBytes("UTF-8")
    val proof   = makeCip8Proof(message)
    val corrupted = proof.copy(signature = "deadbeef")
    assert(!Cip8Verifier.verify(corrupted, message))
  }

  test("Cip8Verifier: invalid key bytes → false") {
    val message = "test".getBytes("UTF-8")
    val proof   = makeCip8Proof(message)
    val badKey  = proof.copy(key = "00" * 4)
    assert(!Cip8Verifier.verify(badKey, message))
  }

  // ── CardanoFacilitator: missing proof ─────────────────────────────────────────

  test("verify: missing cardanoProof → Fail") {
    val mockClient = new MockBlockfrostBase:
      def getAddressInfo(a: String)   = Future.failed(RuntimeException("should not be called"))
      def isTxConfirmed(h: String)    = Future.failed(RuntimeException("should not be called"))

    val cfg = CardanoFacilitatorConfig(
      CardanoNetwork.Preprod,
      CardanoProvider.Blockfrost(BlockfrostConfig("test")),
      "addr1receiver",
    )
    val fac     = CardanoFacilitator(cfg, mockClient)
    val payload = PaymentPayload(
      scheme = PaymentScheme.CardanoExact(BigInt(2_000_000), None),
      network = Network.Base,
      authorization = testAuth,
      signature = "",
      cardanoProof = None,
    )
    val result = Await.result(fac.verify(payload, testReq), 5.seconds)
    assert(result == VerifyResult.Fail("Missing Cardano payment proof"))
  }

  // ── CardanoFacilitator: wrong scheme ──────────────────────────────────────────

  test("verify: non-Cardano scheme → Fail") {
    val mockClient = new MockBlockfrostBase:
      def getAddressInfo(a: String)   = Future.failed(RuntimeException("should not be called"))
      def isTxConfirmed(h: String)    = Future.failed(RuntimeException("should not be called"))

    val cfg = CardanoFacilitatorConfig(
      CardanoNetwork.Mainnet,
      CardanoProvider.Blockfrost(BlockfrostConfig("test")),
      "addr1receiver",
    )
    val fac  = CardanoFacilitator(cfg, mockClient)
    val evmReq = testReq.copy(scheme = PaymentScheme.Exact(BigInt(1_000_000)))
    val payload = PaymentPayload(
      scheme = PaymentScheme.Exact(BigInt(1_000_000)),
      network = Network.Base,
      authorization = testAuth,
      signature = "0xsig",
    )
    val result = Await.result(fac.verify(payload, evmReq), 5.seconds)
    result match
      case VerifyResult.Fail(msg) => assert(msg.contains("CardanoFacilitator only supports"))
      case _                       => fail("expected Fail")
  }

  // ── CardanoFacilitator: balance check ─────────────────────────────────────────

  test("verify: valid proof + sufficient balance → Ok") {
    val message = "Cardano test payment".getBytes("UTF-8")
    val proof   = makeCip8Proof(message)

    val mockClient = new MockBlockfrostBase:
      def getAddressInfo(a: String) = Future.successful(
        AddressInfo(a, BigInt(5_000_000), Map.empty))
      def isTxConfirmed(h: String)  = Future.successful(true)

    val cfg = CardanoFacilitatorConfig(
      CardanoNetwork.Preprod,
      CardanoProvider.Blockfrost(BlockfrostConfig("test")),
      "addr1receiver",
    )
    val fac     = CardanoFacilitator(cfg, mockClient)
    val payload = PaymentPayload(
      scheme = PaymentScheme.CardanoExact(BigInt(2_000_000), None),
      network = Network.Base,
      authorization = testAuth.copy(from = proof.address),
      signature = "",
      cardanoProof = Some(proof),
    )
    val result = Await.result(fac.verify(payload, testReq), 5.seconds)
    assert(result == VerifyResult.Ok)
  }

  test("verify: Scalus provider validates structured claim message without payer balance check") {
    val receiver    = "addr_test1wzj0t77w5k08xqpsslzw4rljksp7ev9stduxrzqgyg7w35qqkq0cd"
    val validBefore = BigInt(9_999_999_999L)
    val req = testReq.copy(
      network         = Network.CardanoPreprod,
      payTo           = receiver,
      scalusEscrowRef = Some("e" * 64 + "#0"),
    )
    val message = scalusClaimMessage(receiver, BigInt(2_000_000), validBefore)
    val proof   = makeCip8Proof(message)

    val mockClient = new MockBlockfrostBase:
      def getAddressInfo(a: String) = Future.failed(RuntimeException("balance check should not run"))
      def isTxConfirmed(h: String)  = Future.failed(RuntimeException("confirmation check should not run"))

    val cfg = CardanoFacilitatorConfig(
      CardanoNetwork.Preprod,
      CardanoProvider.Scalus("/tmp/node.socket"),
      receiver,
    )
    val fac = CardanoFacilitator(cfg, mockClient)
    val payload = PaymentPayload(
      scheme = PaymentScheme.CardanoExact(BigInt(2_000_000), None),
      network = Network.CardanoPreprod,
      authorization = testAuth.copy(
        from        = proof.address,
        to          = receiver,
        validBefore = validBefore,
        nonce       = "e" * 64 + "#0",
      ),
      signature = "",
      cardanoProof = Some(proof),
    )
    val result = Await.result(fac.verify(payload, req), 5.seconds)
    assert(result == VerifyResult.Ok)
  }

  test("verify: Scalus provider rejects missing escrowRef") {
    val receiver    = "addr_test1wzj0t77w5k08xqpsslzw4rljksp7ev9stduxrzqgyg7w35qqkq0cd"
    val validBefore = BigInt(9_999_999_999L)
    val req         = testReq.copy(network = Network.CardanoPreprod, payTo = receiver)
    val proof       = makeCip8Proof(scalusClaimMessage(receiver, BigInt(2_000_000), validBefore))

    val mockClient = new MockBlockfrostBase:
      def getAddressInfo(a: String) = Future.failed(RuntimeException("balance check should not run"))
      def isTxConfirmed(h: String)  = Future.failed(RuntimeException("confirmation check should not run"))

    val cfg = CardanoFacilitatorConfig(
      CardanoNetwork.Preprod,
      CardanoProvider.Scalus("/tmp/node.socket"),
      receiver,
    )
    val fac = CardanoFacilitator(cfg, mockClient)
    val payload = PaymentPayload(
      scheme = PaymentScheme.CardanoExact(BigInt(2_000_000), None),
      network = Network.CardanoPreprod,
      authorization = testAuth.copy(to = receiver, validBefore = validBefore, nonce = ""),
      signature = "",
      cardanoProof = Some(proof),
    )
    val result = Await.result(fac.verify(payload, req), 5.seconds)
    result match
      case VerifyResult.Fail(msg) => assert(msg.contains("escrowRef"))
      case other                  => fail(s"expected Fail, got $other")
  }

  test("verify: valid proof + insufficient ADA → Fail") {
    val message = "Cardano test payment".getBytes("UTF-8")
    val proof   = makeCip8Proof(message)

    val mockClient = new MockBlockfrostBase:
      def getAddressInfo(a: String) = Future.successful(
        AddressInfo(a, BigInt(1_000_000), Map.empty))  // only 1 ADA, need 2
      def isTxConfirmed(h: String)  = Future.successful(false)

    val cfg = CardanoFacilitatorConfig(
      CardanoNetwork.Preprod,
      CardanoProvider.Blockfrost(BlockfrostConfig("test")),
      "addr1receiver",
    )
    val fac     = CardanoFacilitator(cfg, mockClient)
    val payload = PaymentPayload(
      scheme = PaymentScheme.CardanoExact(BigInt(2_000_000), None),
      network = Network.Base,
      authorization = testAuth.copy(from = proof.address),
      signature = "",
      cardanoProof = Some(proof),
    )
    val result = Await.result(fac.verify(payload, testReq), 5.seconds)
    result match
      case VerifyResult.Fail(msg) => assert(msg.contains("Insufficient ADA"))
      case _                       => fail("expected Fail")
  }

  // ── settle ────────────────────────────────────────────────────────────────────

  test("settle: Blockfrost provider → Ok (optimistic)") {
    val mockClient = new MockBlockfrostBase:
      def getAddressInfo(a: String) = Future.successful(AddressInfo(a, BigInt(0), Map.empty))
      def isTxConfirmed(h: String)  = Future.successful(true)

    val cfg = CardanoFacilitatorConfig(
      CardanoNetwork.Preprod,
      CardanoProvider.Blockfrost(BlockfrostConfig("test")),
      "addr1receiver",
    )
    val fac     = CardanoFacilitator(cfg, mockClient)
    val payload = PaymentPayload(
      scheme = PaymentScheme.CardanoExact(BigInt(2_000_000), None),
      network = Network.Base,
      authorization = testAuth,
      signature = "",
    )
    val result = Await.result(fac.settle(payload, testReq), 5.seconds)
    result match
      case SettleResult.Ok(_)   => succeed
      case SettleResult.Fail(m) => fail(s"expected Ok, got Fail: $m")
  }

  test("settle: Scalus provider → Fail (not implemented)") {
    val mockClient = new MockBlockfrostBase:
      def getAddressInfo(a: String) = Future.successful(AddressInfo(a, BigInt(0), Map.empty))
      def isTxConfirmed(h: String)  = Future.successful(false)

    val cfg = CardanoFacilitatorConfig(
      CardanoNetwork.Mainnet,
      CardanoProvider.Scalus("/tmp/node.socket"),
      "addr1receiver",
    )
    val fac     = CardanoFacilitator(cfg, mockClient)
    val payload = PaymentPayload(
      scheme = PaymentScheme.CardanoExact(BigInt(2_000_000), None),
      network = Network.Base,
      authorization = testAuth,
      signature = "",
    )
    val result = Await.result(fac.settle(payload, testReq), 5.seconds)
    result match
      case SettleResult.Fail(msg) => assert(msg.contains("Scalus"))
      case _                       => fail("expected Fail")
  }

  // ── native asset balance ──────────────────────────────────────────────────────

  test("verify: valid proof + sufficient native asset balance → Ok") {
    val nativeAsset = CardanoAsset("policy123", "assetABC", "TOK")
    val req = testReq.copy(scheme = PaymentScheme.CardanoExact(BigInt(100), Some(nativeAsset)))
    val message = req.description.getBytes("UTF-8")
    val proof   = makeCip8Proof(message)

    val mockClient = new MockBlockfrostBase:
      def getAddressInfo(a: String) = Future.successful(
        AddressInfo(a, BigInt(2_000_000), Map("policy123assetABC" -> BigInt(500))))
      def isTxConfirmed(h: String)  = Future.successful(true)

    val cfg = CardanoFacilitatorConfig(
      CardanoNetwork.Preprod,
      CardanoProvider.Blockfrost(BlockfrostConfig("test")),
      "addr1receiver",
    )
    val fac     = CardanoFacilitator(cfg, mockClient)
    val payload = PaymentPayload(
      scheme = PaymentScheme.CardanoExact(BigInt(100), Some(nativeAsset)),
      network = Network.Base,
      authorization = testAuth.copy(from = proof.address),
      signature = "",
      cardanoProof = Some(proof),
    )
    val result = Await.result(fac.verify(payload, req), 5.seconds)
    assert(result == VerifyResult.Ok)
  }
