package scalascript.x402.facilitator

import scalascript.x402.*
import scalascript.evm.*
import scalascript.blockchain.spi.ChainId
import scalascript.blockchain.evm.{Eip3009, EvmChainAdapter}
import scalascript.crypto.{CryptoBackend, Curve, HashAlgo, PublicKey}
import scala.concurrent.duration.Duration
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global

class EvmFacilitatorTest extends AnyFunSuite:

  // ── Real signing infrastructure: derive an address from a known
  // private key and sign EIP-3009 typed data so signature recovery
  // succeeds during verify. The address used as `payTo` is irrelevant
  // to signature verification (only `from` is recovered and matched).
  private val privKey   = decodeHex("0x4646464646464646464646464646464646464646464646464646464646464646")
  private val backend   = CryptoBackend.get()
  private val pubBytes  = backend.derivePublic(Curve.Secp256k1, privKey)
  private val adapter   = new EvmChainAdapter(ChainId.Base)
  private val signerAddr = adapter.addressFromPublicKey(PublicKey(Curve.Secp256k1, pubBytes))
  private val payToAddr  = "0x1111111111111111111111111111111111111111"

  private val testReq = PaymentRequirements(
    scheme      = PaymentScheme.Exact(1_000_000L),
    network     = Network.Base,
    asset       = Assets.USDC_BASE,
    payTo       = payToAddr,
    resource    = "/api/premium",
    description = "Test",
  )

  /** Make a payload with a real EIP-3009 signature over the given fields.
   *  Use `breakSignature = true` to emit a payload that will fail
   *  signature recovery. */
  private def makeSignedPayload(
    from:            String  = "",                // empty = signerAddr (correct recovery)
    to:              String  = payToAddr,
    value:           BigInt  = BigInt(1_000_000),
    validBefore:    BigInt  = BigInt(9_999_999_999L),
    breakSignature: Boolean = false,
  ): PaymentPayload =
    val authFrom = if from.isEmpty then signerAddr else from
    val nonceHex = "0x" + "ab" * 32
    val typedData = Eip3009.usdcTransferWithAuthorization(
      tokenAddress = testReq.asset.address,
      chainId      = Network.Base.chainId,
      from         = authFrom,
      to           = to,
      value        = value,
      validAfter   = BigInt(0),
      validBefore  = validBefore,
      nonceHex     = nonceHex,
    )
    val digest = adapter.typedDataDigest(typedData)
    val sigRaw = backend.sign(Curve.Secp256k1, privKey, digest, HashAlgo.None)
    // Encode as r||s||v with v = recId + 27 (Ethereum convention)
    val sig = sigRaw.clone()
    sig(64) = (sigRaw(64) + 27).toByte
    val sigHex =
      if breakSignature then "0x" + "cc" * 65
      else "0x" + sig.map(b => f"${b & 0xff}%02x").mkString
    PaymentPayload(
      scheme        = PaymentScheme.Exact(value),
      network       = Network.Base,
      authorization = TransferAuthorization(
        from        = authFrom,
        to          = to,
        value       = value,
        validAfter  = BigInt(0),
        validBefore = validBefore,
        nonce       = nonceHex,
      ),
      signature     = sigHex,
    )

  private def mockEvm(balance: BigInt): EvmClient = new EvmClient:
    def blockNumber(): Future[BigInt]                                     = ???
    def getBalance(address: String): Future[BigInt]                       = ???
    def getCode(address: String): Future[String]                          = ???
    def erc20Balance(token: String, address: String): Future[BigInt]      = Future.successful(balance)
    def erc20Allowance(t: String, o: String, s: String): Future[BigInt]   = ???
    def erc20Decimals(token: String): Future[Int]                         = ???
    def erc20Symbol(token: String): Future[String]                        = ???
    def getTransaction(hash: String): Future[Option[EvmTransaction]]      = ???
    def getReceipt(hash: String): Future[Option[EvmReceipt]]              = ???
    def waitForReceipt(hash: String, timeout: Duration): Future[EvmReceipt] = ???
    def call(to: String, data: String): Future[String]                    = ???
    def rpc(method: String, params: ujson.Value*): Future[ujson.Value]    = ???

  // ── Expired authorization ─────────────────────────────────────────────

  test("verify: expired validBefore → Fail") {
    val payload = makeSignedPayload(validBefore = BigInt(1))
    val fac     = EvmFacilitator(mockEvm(BigInt(1_000_000)))
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) => assert(reason.contains("expired"))
      case _                         => fail("expected Fail")
  }

  // ── Destination mismatch ──────────────────────────────────────────────

  test("verify: wrong payTo → Fail") {
    val payload = makeSignedPayload(to = "0x2222222222222222222222222222222222222222")
    val fac     = EvmFacilitator(mockEvm(BigInt(1_000_000)))
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) => assert(reason.contains("mismatch"))
      case _                         => fail("expected Fail")
  }

  // ── Insufficient balance ──────────────────────────────────────────────

  test("verify: balance < required value → Fail") {
    val payload = makeSignedPayload(value = BigInt(1_000_000))
    val fac     = EvmFacilitator(mockEvm(BigInt(500_000)))
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) => assert(reason.contains("Insufficient"))
      case _                         => fail("expected Fail")
  }

  // ── Sufficient balance ────────────────────────────────────────────────

  test("verify: balance >= required value → Ok") {
    val payload = makeSignedPayload()
    val fac     = EvmFacilitator(mockEvm(BigInt(2_000_000)))
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    assert(result == VerifyResult.Ok)
  }

  test("verify: exact balance match → Ok") {
    val payload = makeSignedPayload()
    val fac     = EvmFacilitator(mockEvm(BigInt(1_000_000)))
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    assert(result == VerifyResult.Ok)
  }

  // ── EVM error ─────────────────────────────────────────────────────────

  test("verify: EVM RPC error → Fail") {
    val failEvm = new EvmClient:
      def blockNumber(): Future[BigInt]                                     = ???
      def getBalance(address: String): Future[BigInt]                       = ???
      def getCode(address: String): Future[String]                          = ???
      def erc20Balance(token: String, address: String): Future[BigInt]      = Future.failed(RuntimeException("RPC unavailable"))
      def erc20Allowance(t: String, o: String, s: String): Future[BigInt]   = ???
      def erc20Decimals(token: String): Future[Int]                         = ???
      def erc20Symbol(token: String): Future[String]                        = ???
      def getTransaction(hash: String): Future[Option[EvmTransaction]]      = ???
      def getReceipt(hash: String): Future[Option[EvmReceipt]]              = ???
      def waitForReceipt(hash: String, timeout: Duration): Future[EvmReceipt] = ???
      def call(to: String, data: String): Future[String]                    = ???
      def rpc(method: String, params: ujson.Value*): Future[ujson.Value]    = ???
    val payload = makeSignedPayload()
    val fac     = EvmFacilitator(failEvm)
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) => assert(reason.contains("RPC unavailable"))
      case _                         => fail("expected Fail")
  }

  // ── Mismatched signature — the bug-fix this slice closes ─────────────

  test("verify: tampered signature → Fail") {
    val payload = makeSignedPayload(breakSignature = true)
    val fac     = EvmFacilitator(mockEvm(BigInt(2_000_000)))
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) =>
        assert(
          reason.toLowerCase.contains("signature"),
          s"expected signature-related Fail, got: $reason",
        )
      case _ => fail("expected Fail")
  }

  test("verify: signature signed by a different key → Fail") {
    // Build a payload claiming auth.from = signerAddr but signed with a
    // different private key. Signature recovery succeeds but the recovered
    // address won't match auth.from.
    val otherPriv = decodeHex("0x0102030405060708091011121314151617181920212223242526272829303132")
    val nonceHex  = "0x" + "ab" * 32
    val typedData = Eip3009.usdcTransferWithAuthorization(
      tokenAddress = testReq.asset.address,
      chainId      = Network.Base.chainId,
      from         = signerAddr,
      to           = payToAddr,
      value        = BigInt(1_000_000),
      validAfter   = BigInt(0),
      validBefore  = BigInt(9_999_999_999L),
      nonceHex     = nonceHex,
    )
    val digest = adapter.typedDataDigest(typedData)
    val sigRaw = backend.sign(Curve.Secp256k1, otherPriv, digest, HashAlgo.None)
    val sig    = sigRaw.clone()
    sig(64)    = (sigRaw(64) + 27).toByte
    val payload = PaymentPayload(
      scheme        = PaymentScheme.Exact(BigInt(1_000_000)),
      network       = Network.Base,
      authorization = TransferAuthorization(
        from        = signerAddr,
        to          = payToAddr,
        value       = BigInt(1_000_000),
        validAfter  = BigInt(0),
        validBefore = BigInt(9_999_999_999L),
        nonce       = nonceHex,
      ),
      signature     = "0x" + sig.map(b => f"${b & 0xff}%02x").mkString,
    )
    val fac    = EvmFacilitator(mockEvm(BigInt(2_000_000)))
    val result = Await.result(fac.verify(payload, testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) =>
        assert(reason.contains("mismatch") || reason.toLowerCase.contains("signature"))
      case _ => fail("expected Fail (signer mismatch)")
  }

  // ── Settle ────────────────────────────────────────────────────────────

  test("settle: no settler configured → Ok(zeroed hash)") {
    val fac    = EvmFacilitator(mockEvm(BigInt(1_000_000)))
    val result = Await.result(fac.settle(makeSignedPayload(), testReq), 5.seconds)
    result match
      case SettleResult.Ok(hash) => assert(hash.startsWith("0x") && hash.length > 2)
      case _                     => fail("expected Ok")
  }

  test("settle: custom settler is called") {
    var called = false
    val settler = (_: PaymentPayload, _: PaymentRequirements) =>
      called = true
      Future.successful(SettleResult.Ok("0xmytxhash"))
    val fac    = EvmFacilitator.withSettler(mockEvm(BigInt(1_000_000)), settler)
    val result = Await.result(fac.settle(makeSignedPayload(), testReq), 5.seconds)
    assert(called)
    result match
      case SettleResult.Ok(hash) => assert(hash == "0xmytxhash")
      case _                     => fail("expected Ok")
  }

  test("settle: settler failure propagates") {
    val settler = (_: PaymentPayload, _: PaymentRequirements) =>
      Future.successful(SettleResult.Fail("chain down"))
    val fac    = EvmFacilitator.withSettler(mockEvm(BigInt(1_000_000)), settler)
    val result = Await.result(fac.settle(makeSignedPayload(), testReq), 5.seconds)
    result match
      case SettleResult.Fail(reason) => assert(reason.contains("chain down"))
      case _                         => fail("expected Fail")
  }

  // ── util ──────────────────────────────────────────────────────────────

  private def decodeHex(s: String): Array[Byte] =
    val clean = s.stripPrefix("0x")
    val out   = new Array[Byte](clean.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out
