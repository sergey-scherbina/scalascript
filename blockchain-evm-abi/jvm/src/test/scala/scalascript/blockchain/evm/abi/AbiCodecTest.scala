package scalascript.blockchain.evm.abi

/** JVM-side concrete instantiation of [[AbiCodecTestBase]].
 *
 *  The JVM `crypto-bouncycastle` provider auto-registers via
 *  `java.util.ServiceLoader`, so no per-suite setup is needed — the
 *  base test bodies run as-is. */
class AbiCodecTest extends AbiCodecTestBase
