package scalascript.x402.client

import scalascript.x402.*
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global
import java.util.Base64

class CardanoPayloadTest extends AnyFunSuite:

  // Fixed Ed25519 private key — deterministic test
  private val testPrivKeyHex = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
  private val testAddress    = "addr_test1qrlxs7tnpzdvqe"   // bech32 placeholder; facilitator treats opaque

  private val cardanoReq = PaymentRequirements(
    scheme      = PaymentScheme.CardanoExact(BigInt(2_000_000), None),   // 2 ADA
    network     = Network.CardanoPreprod,
    asset       = Assets.USDC_BASE,   // EVM-shaped Asset is unused by Cardano facilitator
    payTo       = "addr_test1receiver",
    resource    = "/api/premium",
    description = "Test Cardano payment",
  )

  // ── Cip8Signer structure ─────────────────────────────────────────────────────

  test("Cip8Signer.sigStructure: COSE Sig_Structure has 4 elements with empty external_aad") {
    val msg = "hello".getBytes("UTF-8")
    val ss  = Cip8Signer.sigStructure(msg)
    val decoded = MiniCbor.decode(ss)
    decoded match
      case MiniCbor.Arr(items) =>
        assert(items.length == 4)
        items(0) match
          case MiniCbor.Text(s) => assert(s == "Signature1")
          case _                => fail("Element 0 should be Text(\"Signature1\")")
        items(2) match
          case MiniCbor.Bytes(b) => assert(b.isEmpty, "external_aad must be empty bytes")
          case _                 => fail("Element 2 should be empty Bytes")
        items(3) match
          case MiniCbor.Bytes(b) => assert(b.sameElements(msg))
          case _                 => fail("Element 3 should be the payload bytes")
      case other => fail(s"Expected Arr, got $other")
  }

  test("Cip8Signer.buildProof: COSE_Sign1 has the expected 4-tuple shape") {
    val msg = "x".getBytes("UTF-8")
    val sig = new Array[Byte](64); sig(0) = 0xAB.toByte
    val pk  = new Array[Byte](32); pk(0)  = 0xCD.toByte
    val proof = Cip8Signer.buildProof(msg, sig, pk, "addr_test1abc")
    assert(proof.address == "addr_test1abc")

    val cose = MiniCbor.decode(hexToBytes(proof.signature))
    cose match
      case MiniCbor.Arr(items) =>
        assert(items.length == 4, "COSE_Sign1 must have 4 elements")
        items(2) match
          case MiniCbor.Bytes(b) => assert(b.sameElements(msg), "payload must match input message")
          case _                 => fail("payload slot wrong shape")
        items(3) match
          case MiniCbor.Bytes(b) => assert(b.sameElements(sig), "signature slot must match input")
          case _                 => fail("signature slot wrong shape")
      case other => fail(s"Expected COSE_Sign1 array, got $other")

    val key = MiniCbor.decode(hexToBytes(proof.key))
    key match
      case MiniCbor.Map(entries) =>
        // {-2: pubkey} → NInt(1) since -(1+1) = -2
        val pubFound = entries.collectFirst {
          case (MiniCbor.NInt(1), MiniCbor.Bytes(b)) => b
        }
        assert(pubFound.exists(_.sameElements(pk)), "COSE_Key must carry the raw pubkey at -2")
      case other => fail(s"Expected COSE_Key map, got $other")
  }

  // ── Wallet end-to-end ────────────────────────────────────────────────────────

  test("Wallets.cardano: produces a verifiable Ed25519 CIP-8 proof over req.description") {
    val wallet = Wallets.cardano(testPrivKeyHex, testAddress, Network.CardanoPreprod)
    val payload = Await.result(
      buildPayloadForRequest(wallet, cardanoReq),
      5.seconds,
    )
    val proof = payload.cardanoProof.getOrElse(fail("cardanoProof missing"))
    assert(proof.address == testAddress)
    assert(payload.signature == "")
    assert(payload.network  == Network.CardanoPreprod)

    // Verify the Ed25519 signature against the Sig_Structure
    val message = cardanoReq.description.getBytes("UTF-8")
    val expectedSigStruct = Cip8Signer.sigStructure(message)
    val (sigBytes, pubKey) = extractSigAndKey(proof)
    assert(verifyEd25519(pubKey, expectedSigStruct, sigBytes), "Ed25519 verification failed")
  }

  test("PayloadBuilder.encode: includes cardanoProof field for Cardano payloads") {
    val wallet  = Wallets.cardano(testPrivKeyHex, testAddress, Network.CardanoPreprod)
    val payload = Await.result(buildPayloadForRequest(wallet, cardanoReq), 5.seconds)
    val encoded = PayloadBuilder.encode(payload)
    val json    = ujson.read(Base64.getDecoder.decode(encoded))
    assert(json.obj.contains("cardanoProof"), "encoded JSON should include cardanoProof")
    assert(json("cardanoProof")("address").str == testAddress)
    assert(json("network").str == "CardanoPreprod")
    assert(json("scheme")("type").str == "cardanoExact")
  }

  // ── CIP-19 derivation ────────────────────────────────────────────────────────

  test("Wallets.cardano(hex, network): derives addr_test1 on Preprod") {
    val w = Wallets.cardano(testPrivKeyHex, Network.CardanoPreprod)
    assert(w.address.startsWith("addr_test1"),
      s"Preprod address should start with addr_test1, got ${w.address}")
    // Re-derivation is deterministic
    val w2 = Wallets.cardano(testPrivKeyHex, Network.CardanoPreprod)
    assert(w.address == w2.address)
  }

  test("Wallets.cardano(hex, network): derives addr1 on Mainnet") {
    val w = Wallets.cardano(testPrivKeyHex, Network.CardanoMainnet)
    assert(w.address.startsWith("addr1"),
      s"Mainnet address should start with addr1, got ${w.address}")
  }

  test("Wallets.cardano(hex, network): Preview network → addr_test1 (testnet header)") {
    val w = Wallets.cardano(testPrivKeyHex, Network.CardanoPreview)
    assert(w.address.startsWith("addr_test1"))
  }

  test("Wallets.cardano(hex, network) matches blockchain-cardano.CardanoAddress.fromPublicKey") {
    import scalascript.blockchain.cardano.CardanoAddress
    import scalascript.wallet.strategy.eoa.RawPrivateKeyVault
    import scalascript.crypto.Curve
    val w = Wallets.cardano(testPrivKeyHex, Network.CardanoMainnet)
    val vault  = RawPrivateKeyVault.fromHex("vec", testPrivKeyHex, Curve.Ed25519)
    val signer = Await.result(vault.getSigner(Curve.Ed25519, "raw"), 5.seconds)
    val ref    = CardanoAddress.fromPublicKey(signer.publicKey, testnet = false)
    assert(w.address == ref)
  }

  test("Wallets.cardano(hex, address, network) preserves explicit override") {
    val custom = "addr_test1custom"
    val w      = Wallets.cardano(testPrivKeyHex, custom, Network.CardanoPreprod)
    assert(w.address == custom)
  }

  test("EVM wallet rejects CIP-8 signing; Cardano wallet rejects EIP-712 signing") {
    val evm     = Wallets.privateKey("0x" + "ab" * 32, Network.Base)
    val cardano = Wallets.cardano(testPrivKeyHex, testAddress, Network.CardanoPreprod)
    intercept[UnsupportedOperationException] {
      Await.result(evm.signCip8(Array.emptyByteArray), 5.seconds)
    }
    intercept[UnsupportedOperationException] {
      Await.result(
        cardano.signEip712(
          Eip712Domain("d", "1", 1, "0x0000000000000000000000000000000000000000"),
          Map.empty, Map.empty,
        ),
        5.seconds,
      )
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private def buildPayloadForRequest(wallet: Wallet, req: PaymentRequirements) =
    PayloadBuilder.build(wallet, req)

  private def extractSigAndKey(proof: CardanoPaymentProof): (Array[Byte], Array[Byte]) =
    val cose = MiniCbor.decode(hexToBytes(proof.signature))
    val sig  = cose match
      case MiniCbor.Arr(items) =>
        items(3) match
          case MiniCbor.Bytes(b) => b
          case _                 => throw RuntimeException("bad sig slot")
      case _ => throw RuntimeException("not a COSE_Sign1")
    val key  = MiniCbor.decode(hexToBytes(proof.key))
    val pk   = key match
      case MiniCbor.Map(entries) =>
        entries.collectFirst {
          case (MiniCbor.NInt(1), MiniCbor.Bytes(b)) => b
        }.getOrElse(throw RuntimeException("pubkey missing"))
      case _ => throw RuntimeException("not a COSE_Key")
    (sig, pk)

  private def verifyEd25519(pubKey: Array[Byte], message: Array[Byte], sig: Array[Byte]): Boolean =
    import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
    import org.bouncycastle.crypto.signers.Ed25519Signer
    val v = Ed25519Signer()
    v.init(false, Ed25519PublicKeyParameters(pubKey, 0))
    v.update(message, 0, message.length)
    v.verifySignature(sig)

  private def hexToBytes(hex: String): Array[Byte] =
    val h = if hex.startsWith("0x") then hex.drop(2) else hex
    h.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
