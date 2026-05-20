package scalascript.wallet.strategy.erc4337

import scalascript.blockchain.evm.Hex
import scalascript.blockchain.evm.abi.{Abi, AbiType, AbiValue}
import scalascript.crypto.{CryptoBackend, HashAlgo}

/** Factory for an ERC-4337 PasskeyAccount whose `validateUserOp` expects
 *  the WebAuthn signature blob produced by [[PasskeySigner]].
 *
 *  The factory mirrors [[SimpleAccountFactory]] in shape (same CREATE2
 *  arithmetic, same `execute(target,value,data)` calldata), but the
 *  account's initCode wires a P-256 (X, Y) point instead of an EOA
 *  address.
 *
 *  The on-chain factory function this targets is the de-facto standard
 *  used by Coinbase Smart Wallet and the Daimo P256Account reference:
 *
 *      function createAccount(uint256 x, uint256 y, uint256 salt)
 *          external returns (address);
 *
 *  Some PasskeyAccount deployments use `bytes` rather than two
 *  uint256 fields for the public key — for that variant, subclass and
 *  override [[initCodeFor]]. The CREATE2 prediction itself only needs
 *  `accountCodeHash` (the proxy creation-bytecode hash) and `salt`, so
 *  [[addressFor]] is independent of the exact init-arg layout.
 *
 *  Citations:
 *    - Coinbase Smart Wallet `CoinbaseSmartWalletFactory.sol`
 *    - Daimo `DaimoAccountFactoryV2`
 *    - ERC-7836 deployment recipe
 */
class SimplePasskeyAccountFactory(
  /** Factory contract address (20 bytes, hex with 0x). */
  val factoryAddress: String,
  /** keccak256 of the PasskeyAccount creation bytecode (proxy +
   *  constructor-args), needed for CREATE2 prediction. Callers
   *  pre-compute this per deployment. */
  val accountCodeHash: Array[Byte],
  val salt:            BigInt = BigInt(0),
) extends SmartAccountFactory:

  /** [[SmartAccountFactory]] adapter — the "owner" parameter is the
   *  passkey's public-key (X || Y) hex, not an EOA address. This lets
   *  [[SmartAccountAdapter]] route Passkey accounts through the same
   *  build-pipeline. */
  def addressFor(owner: String): String = addressForPubkey(owner)
  def initCodeFor(owner: String): Array[Byte] = initCodeForPubkey(owner)

  /** SimpleAccount-shape execute(address dest, uint256 value, bytes func). */
  private val ExecuteTypes: Seq[AbiType] =
    Seq(AbiType.Address, AbiType.UInt(256), AbiType.Bytes)

  /** CREATE2 prediction:
   *      keccak256(0xff || factory || salt || keccak(initCode))[12:32]
   *  See EIP-1014. Same arithmetic as `SimpleAccountFactory`. */
  def addressForPubkey(pubkeyXYHex: String): String =
    val saltBytes = Hex.bigIntTo32(salt)
    val payload = new Array[Byte](1 + 20 + 32 + 32)
    payload(0) = 0xff.toByte
    val deployer = Hex.decode(factoryAddress)
    System.arraycopy(deployer, 0, payload, 1, 20)
    System.arraycopy(saltBytes, 0, payload, 21, 32)
    System.arraycopy(accountCodeHash, 0, payload, 53, 32)
    val hash = CryptoBackend.get().hash(HashAlgo.Keccak256, payload)
    val _ = pubkeyXYHex  // CREATE2 input is independent of the pubkey here
    "0x" + Hex.encode(java.util.Arrays.copyOfRange(hash, 12, 32), withPrefix = false)

  /** initCode = factoryAddress || createAccount(uint256 x, uint256 y, uint256 salt).
   *
   *  `pubkeyXYHex` is the 64-byte uncompressed P-256 point (X || Y) in
   *  hex, with or without 0x prefix. The factory's createAccount
   *  unmarshals it into the P-256 verifier's expected form. */
  def initCodeForPubkey(pubkeyXYHex: String): Array[Byte] =
    val raw = Hex.decode(pubkeyXYHex)
    require(raw.length == 64,
      s"P-256 public key must be 64 bytes uncompressed X||Y, got ${raw.length}")
    val x = BigInt(1, java.util.Arrays.copyOfRange(raw, 0, 32))
    val y = BigInt(1, java.util.Arrays.copyOfRange(raw, 32, 64))
    val factoryBytes = Hex.decode(factoryAddress)
    val calldata = Abi.encodeFunctionCall(
      "createAccount",
      Seq(AbiType.UInt(256), AbiType.UInt(256), AbiType.UInt(256)),
      Seq(
        AbiValue.UInt(256, x),
        AbiValue.UInt(256, y),
        AbiValue.UInt(256, salt),
      ),
    )
    factoryBytes ++ calldata

  /** `execute(address dest, uint256 value, bytes func)` calldata. The
   *  PasskeyAccount's outer `validateUserOp` decodes the assertion;
   *  the inner call is the same shape as SimpleAccount. */
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
