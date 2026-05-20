package scalascript.wallet.strategy.erc4337

import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockchain.evm.abi.Selector
import scalascript.blockchain.evm.abi.{AbiType, AbiValue}
import scalascript.crypto.{CryptoBackend, HashAlgo}

abstract class PasskeyFactoryTestBase extends AnyFunSuite:

  // `lazy` so the backend lookup happens after `beforeAll` registers
  // a `CryptoBackend` on Scala.js (the JVM `ServiceLoader` resolves
  // eagerly anyway, so the laziness is invisible there).
  private lazy val be = CryptoBackend.get()

  // Synthetic deployment: a fake factory address + arbitrary code hash.
  private val factory = "0x9999999999999999999999999999999999999999"
  // Some deterministic 32-byte code-hash. In production this is
  // keccak256 of the PasskeyAccount creation bytecode.
  private lazy val accountCodeHash = be.hash(HashAlgo.Keccak256, "passkey-account-code".getBytes("UTF-8"))

  // A throwaway 64-byte uncompressed P-256 point. Not on-curve — that's
  // fine, the factory doesn't validate the point client-side.
  private val pubkeyXY =
    Hex.encode(Array.tabulate[Byte](64)(i => (i + 1).toByte), withPrefix = false)

  test("addressForPubkey matches keccak(0xff || deployer || salt || codeHash)[12:32]") {
    val f = new SimplePasskeyAccountFactory(factory, accountCodeHash)
    val predicted = f.addressForPubkey(pubkeyXY)

    // Reproduce the CREATE2 derivation manually.
    val saltBytes = Hex.bigIntTo32(BigInt(0))
    val payload = new Array[Byte](1 + 20 + 32 + 32)
    payload(0) = 0xff.toByte
    System.arraycopy(Hex.decode(factory), 0, payload, 1, 20)
    System.arraycopy(saltBytes,             0, payload, 21, 32)
    System.arraycopy(accountCodeHash,       0, payload, 53, 32)
    val hash = be.hash(HashAlgo.Keccak256, payload)
    val expected = "0x" + Hex.encode(java.util.Arrays.copyOfRange(hash, 12, 32), withPrefix = false)
    assert(predicted.equalsIgnoreCase(expected))

    // Custom salt changes the prediction.
    val f2 = new SimplePasskeyAccountFactory(factory, accountCodeHash, salt = BigInt(7))
    assert(!f2.addressForPubkey(pubkeyXY).equalsIgnoreCase(predicted),
      "Address must depend on salt")
  }

  test("addressForPubkey routes through the SmartAccountFactory trait alias") {
    val f: SmartAccountFactory = new SimplePasskeyAccountFactory(factory, accountCodeHash)
    val viaTrait    = f.addressFor(pubkeyXY)
    val viaConcrete = new SimplePasskeyAccountFactory(factory, accountCodeHash).addressForPubkey(pubkeyXY)
    assert(viaTrait.equalsIgnoreCase(viaConcrete))
  }

  test("initCodeForPubkey starts with the factory address and is followed by createAccount(x,y,salt)") {
    val f = new SimplePasskeyAccountFactory(factory, accountCodeHash, salt = BigInt(0x1234))
    val initCode = f.initCodeForPubkey(pubkeyXY)
    val factoryBytes = Hex.decode(factory)
    assert(initCode.take(20).toSeq == factoryBytes.toSeq,
      "initCode must begin with the 20-byte factory address")

    // The remainder is the createAccount selector + ABI-encoded args.
    val calldata = initCode.drop(20)
    val expectedSelector =
      Selector.forFunction("createAccount", Seq(AbiType.UInt(256), AbiType.UInt(256), AbiType.UInt(256)))
    assert(calldata.take(4).toSeq == expectedSelector.toSeq,
      "function selector mismatch")

    // After the selector, the three uint256s should round-trip back.
    val args = scalascript.blockchain.evm.abi.AbiDecoder.decodeTuple(
      Seq(AbiType.UInt(256), AbiType.UInt(256), AbiType.UInt(256)),
      calldata.drop(4),
    )
    val x = args(0).asInstanceOf[AbiValue.UInt].value
    val y = args(1).asInstanceOf[AbiValue.UInt].value
    val salt = args(2).asInstanceOf[AbiValue.UInt].value
    val raw = Hex.decode(pubkeyXY)
    assert(x == BigInt(1, java.util.Arrays.copyOfRange(raw, 0, 32)))
    assert(y == BigInt(1, java.util.Arrays.copyOfRange(raw, 32, 64)))
    assert(salt == BigInt(0x1234))
  }

  test("initCodeForPubkey rejects a public key that isn't 64 bytes uncompressed") {
    val f = new SimplePasskeyAccountFactory(factory, accountCodeHash)
    intercept[IllegalArgumentException] {
      f.initCodeForPubkey("0x1234")
    }
  }

  test("executeCalldata starts with the execute(address,uint256,bytes) selector") {
    val f = new SimplePasskeyAccountFactory(factory, accountCodeHash)
    val target = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    val data = Hex.decode("0xdeadbeef")
    val calldata = f.executeCalldata(Some(target), BigInt(42), data)
    val expectedSelector =
      Selector.forFunction("execute", Seq(AbiType.Address, AbiType.UInt(256), AbiType.Bytes))
    assert(calldata.take(4).toSeq == expectedSelector.toSeq)
  }
