package scalascript.wallet.walletconnect

/** JVM concrete subclass — pure protocol logic, no `CryptoBackend`
 *  needed.  ServiceLoader still wires BouncyCastle for any incidental
 *  callers. */
class WalletConnectConnectorTest extends WalletConnectConnectorTestBase
