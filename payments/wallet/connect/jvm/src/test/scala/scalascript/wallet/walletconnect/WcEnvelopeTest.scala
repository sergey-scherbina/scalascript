package scalascript.wallet.walletconnect

/** JVM concrete subclass.  BouncyCastle is auto-registered via
 *  `ServiceLoader` so no explicit `CryptoBackend.register` call is
 *  needed before the shared spec runs. */
class WcEnvelopeTest extends WcEnvelopeTestBase
