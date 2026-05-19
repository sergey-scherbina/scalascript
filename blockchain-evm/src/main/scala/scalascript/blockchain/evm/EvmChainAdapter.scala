package scalascript.blockchain.evm

import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.spi.*
import scalascript.crypto.{CryptoBackend, Curve, HashAlgo, PublicKey}

/** EVM-side `ChainAdapter`. Phase 1 covers the read-side of the trait
 *  (address derivation, EIP-712 hashing, ecrecover, ERC-20 balance,
 *  generic `eth_call`). The write-side (`buildTransaction` /
 *  `broadcast` / receipts) lands in Phase 2.
 *
 *  One instance per EVM chain id — Base / BaseSepolia / Ethereum /
 *  Polygon / Arbitrum / Optimism / etc. are different instances of
 *  the same class. */
class EvmChainAdapter(val chainId: ChainId)(using ec: ExecutionContext) extends ChainAdapter:

  type Tx       = EvmTx
  type SignedTx = EvmSignedTx

  require(chainId.namespace == "eip155", s"EvmChainAdapter requires eip155 namespace, got $chainId")

  val supportedCurves: Seq[Curve] = Seq(Curve.Secp256k1)

  val defaultDerivationPath: String = "m/44'/60'/0'/0/0"

  // ── addresses ─────────────────────────────────────────────────────────

  def addressFromPublicKey(pk: PublicKey): String =
    require(pk.curve == Curve.Secp256k1, s"EVM expects Secp256k1 public key, got ${pk.curve}")
    val uncompressed = pk.bytes.length match
      case 64 => pk.bytes                                       // X || Y, no prefix
      case 65 if pk.bytes(0) == 0x04 => pk.bytes.tail           // drop 0x04
      case n  => throw new IllegalArgumentException(s"Unsupported pubkey length: $n")
    val hash = CryptoBackend.get().hash(HashAlgo.Keccak256, uncompressed)
    val addr = java.util.Arrays.copyOfRange(hash, 12, 32)
    Eip55.checksum(Hex.encode(addr, withPrefix = false))

  def isValidAddress(s: String): Boolean =
    val clean = s.stripPrefix("0x").stripPrefix("0X")
    clean.length == 40 && clean.forall(c =>
      (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'),
    )

  def normalizeAddress(s: String): String =
    Eip55.checksum(s)

  // ── typed-data hashing ────────────────────────────────────────────────

  def typedDataDigest(data: TypedData): Array[Byte] = data match
    case eip712: TypedData.Eip712 => Eip712.digest(eip712)
    case TypedData.Raw(bytes)     =>
      // EVM `personal_sign` convention:
      //   keccak256("\x19Ethereum Signed Message:\n" + len(msg) + msg)
      val prefix = s"Ethereum Signed Message:\n${bytes.length}".getBytes("UTF-8")
      CryptoBackend.get().hash(HashAlgo.Keccak256, prefix ++ bytes)
    case other =>
      throw new IllegalArgumentException(s"EVM adapter does not support $other")

  // ── signature ↔ signer ────────────────────────────────────────────────

  def recoverAddress(digest: Array[Byte], signature: Array[Byte]): Option[String] =
    if signature.length != 65 then None
    else
      val recId = signature(64).toInt & 0xff
      // x402-style signatures may store `v` as 27/28 instead of the raw
      // 0/1 recovery id. Normalise into the [0, 3] range expected by the
      // CryptoBackend.
      val rawRec =
        if recId >= 27 && recId <= 30 then recId - 27
        else recId
      try
        val pubBytes = CryptoBackend.get().recoverPublic(Curve.Secp256k1, digest, signature, rawRec)
        Some(addressFromPublicKey(PublicKey(Curve.Secp256k1, pubBytes)))
      catch
        case _: Exception => None

  // ── transactions (Phase 2) ────────────────────────────────────────────

  def buildTransaction(intent: TxIntent, ctx: ChainContext): Future[Tx] =
    Future.failed(new NotImplementedError("EvmChainAdapter.buildTransaction lands in Phase 2"))

  def prepareSigningPayload(tx: Tx, signer: PublicKey): SigningPayload =
    throw new NotImplementedError("EvmChainAdapter.prepareSigningPayload lands in Phase 2")

  def assembleSignedTransaction(tx: Tx, signature: Array[Byte], signer: PublicKey): SignedTx =
    throw new NotImplementedError("EvmChainAdapter.assembleSignedTransaction lands in Phase 2")

  def broadcast(signed: SignedTx, ctx: ChainContext): Future[TxHash] =
    Future.failed(new NotImplementedError("EvmChainAdapter.broadcast lands in Phase 2"))

  def describe(tx: Tx): TxDescription =
    throw new NotImplementedError("EvmChainAdapter.describe lands in Phase 2")

  // ── queries ───────────────────────────────────────────────────────────

  def nativeBalance(address: String, ctx: ChainContext): Future[BigInt] =
    ctx.rpcCall("eth_getBalance", ujson.Str(address), ujson.Str("latest")).map { v =>
      BigInt(v.str.stripPrefix("0x"), 16)
    }

  def tokenBalance(asset: Asset, holder: String, ctx: ChainContext): Future[BigInt] =
    require(asset.chain == chainId, s"Asset chain ${asset.chain} ≠ adapter chain $chainId")
    if asset.isNative then nativeBalance(holder, ctx)
    else
      call(asset.address, AbiHelpers.erc20BalanceOfCalldata(holder), ctx).map(AbiHelpers.decodeUint256)

  def nonceOf(address: String, ctx: ChainContext): Future[BigInt] =
    ctx.rpcCall("eth_getTransactionCount", ujson.Str(address), ujson.Str("latest")).map { v =>
      BigInt(v.str.stripPrefix("0x"), 16)
    }

  def getReceipt(hash: TxHash, ctx: ChainContext): Future[Option[TxReceipt]] =
    ctx.rpcCall("eth_getTransactionReceipt", ujson.Str(hash.value)).map {
      case ujson.Null => None
      case obj        =>
        Some(TxReceipt(
          hash        = TxHash(obj("transactionHash").str),
          success     = BigInt(obj("status").str.stripPrefix("0x"), 16).toInt == 1,
          blockNumber = BigInt(obj("blockNumber").str.stripPrefix("0x"), 16),
          gasUsed     = BigInt(obj("gasUsed").str.stripPrefix("0x"), 16),
        ))
    }

  def waitForReceipt(hash: TxHash, ctx: ChainContext, timeoutMs: Long): Future[TxReceipt] =
    Future.failed(new NotImplementedError("EvmChainAdapter.waitForReceipt lands in Phase 2"))

  // ── contract reads ────────────────────────────────────────────────────

  def call(target: String, calldata: Array[Byte], ctx: ChainContext): Future[Array[Byte]] =
    val params = ujson.Obj(
      "to"   -> ujson.Str(target),
      "data" -> ujson.Str("0x" + Hex.encode(calldata, withPrefix = false)),
    )
    ctx.rpcCall("eth_call", params, ujson.Str("latest")).map { v =>
      Hex.decode(v.str)
    }

  def predictDeployAddress(deploy: TxIntent.Deploy, deployer: String, ctx: ChainContext): Future[String] =
    Future.failed(new NotImplementedError("EvmChainAdapter.predictDeployAddress lands in Phase 2"))

object EvmChainAdapter:
  def base(using ExecutionContext):        EvmChainAdapter = new EvmChainAdapter(ChainId.Base)
  def baseSepolia(using ExecutionContext): EvmChainAdapter = new EvmChainAdapter(ChainId.BaseSepolia)
  def mainnet(using ExecutionContext):     EvmChainAdapter = new EvmChainAdapter(ChainId.EthereumMainnet)
  def polygon(using ExecutionContext):     EvmChainAdapter = new EvmChainAdapter(ChainId.Polygon)
  def arbitrum(using ExecutionContext):    EvmChainAdapter = new EvmChainAdapter(ChainId.Arbitrum)
  def optimism(using ExecutionContext):    EvmChainAdapter = new EvmChainAdapter(ChainId.Optimism)

/** Phase-1 placeholder; full shape (RLP fields, EIP-1559 access list,
 *  …) lands in Phase 2. */
case class EvmTx()

case class EvmSignedTx(rawHex: String, hash: TxHash)
