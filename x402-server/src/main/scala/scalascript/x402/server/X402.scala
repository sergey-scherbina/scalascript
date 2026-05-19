package scalascript.x402.server

import scalascript.x402.*
// ── Test mode helpers ─────────────────────────────────────────────────────────

object X402:

  def isTestMode: Boolean =
    sys.env.getOrElse("X402_ENV", "test") == "test"

  def testConfig(
    payTo:  String,
    scheme: PaymentScheme = PaymentScheme.Exact(1_000_000L),
  ): PaymentConfig =
    PaymentConfig(
      requirements = PaymentRequirements(
        scheme      = scheme,
        network     = Network.BaseSepolia,
        asset       = Assets.usdc(Network.BaseSepolia),
        payTo       = payTo,
        resource    = "*",
        description = "Test payment",
      ),
      facilitator    = Facilitators.testnet(),
      nonceStore     = NonceStore.inMemory(),
      settlementMode = SettlementMode.Synchronous,
    )
