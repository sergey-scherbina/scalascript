package scalascript.x402.server

import scalascript.x402.*
import org.scalatest.funsuite.AnyFunSuite

class X402Test extends AnyFunSuite:

  test("X402.testConfig uses BaseSepolia network") {
    val cfg = X402.testConfig("0xpayto")
    assert(cfg.requirements.network == Network.BaseSepolia)
  }

  test("X402.testConfig uses USDC_BASE_SEPOLIA") {
    val cfg = X402.testConfig("0xpayto")
    assert(cfg.requirements.asset.symbol == "USDC")
    assert(cfg.requirements.asset.network == Network.BaseSepolia)
  }

  test("X402.testConfig sets payTo correctly") {
    val cfg = X402.testConfig("0xmywallet")
    assert(cfg.requirements.payTo == "0xmywallet")
  }

  test("X402.testConfig default scheme is Exact(1_000_000)") {
    val cfg = X402.testConfig("0xpayto")
    cfg.requirements.scheme match
      case PaymentScheme.Exact(amt) => assert(amt == BigInt(1_000_000))
      case _                        => fail("expected Exact scheme")
  }

  test("X402.testConfig uses testnet facilitator (always verifies Ok)") {
    import scala.concurrent.Await
    import scala.concurrent.duration.*
    val cfg   = X402.testConfig("0xpayto")
    val auth  = TransferAuthorization("0xf", "0xt", BigInt(1), BigInt(0), BigInt(1), "0x00")
    val req   = cfg.requirements
    val payload = PaymentPayload(scheme = req.scheme, network = req.network, authorization = auth, signature = "0xsig")
    val result  = Await.result(cfg.facilitator.verify(payload, req), 5.seconds)
    assert(result == VerifyResult.Ok)
  }

  test("X402.testConfig uses in-memory nonce store") {
    val cfg = X402.testConfig("0xpayto")
    assert(cfg.nonceStore != null)
  }

  test("X402.testConfig uses Synchronous settlement mode") {
    val cfg = X402.testConfig("0xpayto")
    assert(cfg.settlementMode == SettlementMode.Synchronous)
  }

  test("X402.testConfig accepts custom scheme") {
    val cfg = X402.testConfig("0xpayto", PaymentScheme.Exact(500_000L))
    cfg.requirements.scheme match
      case PaymentScheme.Exact(amt) => assert(amt == BigInt(500_000))
      case _                        => fail("expected custom Exact scheme")
  }

  test("X402.isTestMode returns true by default (X402_ENV not set or = test)") {
    // In CI the env var is typically absent → defaults to "test"
    val env = sys.env.getOrElse("X402_ENV", "test")
    assert(X402.isTestMode == (env == "test"))
  }
