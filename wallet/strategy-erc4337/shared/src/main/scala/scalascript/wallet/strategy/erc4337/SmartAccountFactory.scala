package scalascript.wallet.strategy.erc4337

import scalascript.blockchain.evm.abi.{Abi, AbiType, AbiValue}
import scalascript.crypto.{CryptoBackend, HashAlgo}

/** Pluggable smart-account factory abstraction.
 *
 *  A factory tells us two things about a (smart account, owner)
 *  pair: what address the smart account would have (counterfactual
 *  CREATE2 derivation), and what initCode the first UserOp should
 *  carry to deploy it. After the first successful UserOp the
 *  initCode is empty and the address is just an on-chain contract.
 *
 *  Concrete implementations exist for SimpleAccount (the reference
 *  ERC-4337 v0.6 sample), Kernel, SafeAA, Biconomy modular, etc. */
trait SmartAccountFactory:

  /** Compute the counterfactual smart-account address for an EOA
   *  owner. This is just the CREATE2 prediction: deterministic from
   *  (factoryAddress, salt, initCodeHash). */
  def addressFor(owner: String): String

  /** initCode for the first UserOp: factoryAddress || createAccount
   *  selector + args. Returns an empty array once the account is
   *  known to be deployed. */
  def initCodeFor(owner: String): Array[Byte]

  /** Build the calldata the EntryPoint should pass to the smart
   *  account's `execute(...)` selector. Differs per smart-account
   *  ABI: SimpleAccount uses `execute(address,uint256,bytes)`,
   *  Safe uses `execute(target, value, data, operation)`, etc. */
  def executeCalldata(target: Option[String], value: BigInt, data: Array[Byte]): Array[Byte]

/** Reference factory matching the ERC-4337 v0.6 SimpleAccountFactory
 *  shape. The canonical deployment address is the same on every EVM
 *  chain (deployed via the deterministic deployer). Salt is a
 *  uint256; we default to 0 — callers that want multiple smart
 *  accounts per owner pass a custom salt. */
class SimpleAccountFactory(
  /** Factory contract address (20 bytes, hex with 0x). */
  val factoryAddress: String,
  /** keccak256 of the SimpleAccount creation bytecode. Needed for
   *  CREATE2 address prediction. Callers must pre-compute this for
   *  the deployment they're targeting. */
  val accountCodeHash: Array[Byte],
  /** Deterministic deployer used to deploy the proxy (CREATE2). For
   *  the v0.6 SimpleAccount this is the factory itself. */
  val salt:           BigInt = BigInt(0),
) extends SmartAccountFactory:

  /** SimpleAccount v0.6 execute(address dest, uint256 value, bytes func). */
  private val ExecuteTypes: Seq[AbiType] =
    Seq(AbiType.Address, AbiType.UInt(256), AbiType.Bytes)

  def addressFor(owner: String): String =
    // CREATE2: keccak256(0xff || deployer || salt || keccak(initCode))[12:32]
    val saltBytes = Hex.leftPad32(saltAsBytes)
    val initCodeHash = accountCodeHash
    val payload = new Array[Byte](1 + 20 + 32 + 32)
    payload(0) = 0xff.toByte
    val deployer = Hex.decode(factoryAddress)
    System.arraycopy(deployer, 0, payload, 1, 20)
    System.arraycopy(saltBytes, 0, payload, 21, 32)
    System.arraycopy(initCodeHash, 0, payload, 53, 32)
    val hash = CryptoBackend.get().hash(HashAlgo.Keccak256, payload)
    "0x" + Hex.encode(java.util.Arrays.copyOfRange(hash, 12, 32), withPrefix = false)

  def initCodeFor(owner: String): Array[Byte] =
    val factoryBytes = Hex.decode(factoryAddress)
    val calldata     = Abi.encodeFunctionCall(
      "createAccount",
      Seq(AbiType.Address, AbiType.UInt(256)),
      Seq(AbiValue.Address(owner), AbiValue.UInt(256, salt)),
    )
    factoryBytes ++ calldata

  def executeCalldata(target: Option[String], value: BigInt, data: Array[Byte]): Array[Byte] =
    Abi.encodeFunctionCall(
      "execute",
      ExecuteTypes,
      Seq(
        AbiValue.Address(target.getOrElse("0x0000000000000000000000000000000000000000")),
        AbiValue.UInt(256, value),
        AbiValue.Bytes(data),
      ),
    )

  private def saltAsBytes: Array[Byte] =
    // `BigInt.toByteArray` returns the underlying `java.math.BigInteger`
    // two's-complement representation, which Scala.js shims faithfully.
    val bi  = salt.toByteArray
    if bi.length == 32 then bi
    else if bi.length < 32 then
      val out = new Array[Byte](32)
      System.arraycopy(bi, 0, out, 32 - bi.length, bi.length)
      out
    else
      // BigInteger.toByteArray prepends 0x00 sign byte; strip it.
      java.util.Arrays.copyOfRange(bi, bi.length - 32, bi.length)
