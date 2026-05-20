package scalascript.wallet.strategy.erc4337

/** JVM-side concrete instantiation — `crypto-bouncycastle` auto-registers
 *  via `ServiceLoader`, so no per-suite setup is needed. */
class UserOpHashTest extends UserOpHashTestBase
