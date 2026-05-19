package scalascript.wallet.strategy.erc4337

import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.evm.EvmChainAdapter
import scalascript.blockchain.evm.abi.{Abi, AbiType, AbiValue}
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, HashAlgo, PublicKey}

/** A ChainAdapter that wraps an EvmChainAdapter to produce ERC-4337
 *  UserOperations instead of regular EIP-1559 txs. Drop-in for any
 *  caller that uses the SPI (EoaStrategy.signTransaction, the
 *  x402-client autopay loop, etc.) — sign with the EOA owner, get
 *  back a smart-account-routed UserOp ready for the bundler.
 *
 *  All read methods (balance, address, ABI calls) pass through to
 *  the underlying EVM adapter; only the write-side (build / sign /
 *  broadcast / receipt) is replaced. */
class SmartAccountAdapter(
  val underlying: EvmChainAdapter,
  val owner:      String,             // EOA owner address (EIP-55)
  val factory:    SmartAccountFactory,
  val bundler:    BundlerClient,
  /** Override the smart account address. By default it's
   *  factory.addressFor(owner) (the counterfactual). Useful when
   *  the account has been deployed via a path the factory doesn't
   *  know about. */
  val accountAddressOverride: Option[String] = None,
)(using ec: ExecutionContext) extends ChainAdapter:

  type Tx       = UserOperation         // unsigned (signature.length == 0)
  type SignedTx = UserOperation         // signed (signature.length == 65)

  /** The smart account's address — what the EntryPoint calls when
   *  processing this account's UserOps. Static across the lifetime
   *  of this adapter. */
  val smartAccount: String =
    accountAddressOverride.getOrElse(factory.addressFor(owner))

  // ── identity / addresses pass through ────────────────────────────────

  def chainId: ChainId                         = underlying.chainId
  def supportedCurves: Seq[Curve]              = underlying.supportedCurves
  def defaultDerivationPath: String            = underlying.defaultDerivationPath
  def addressFromPublicKey(pk: PublicKey)      = underlying.addressFromPublicKey(pk)
  def isValidAddress(s: String): Boolean       = underlying.isValidAddress(s)
  def normalizeAddress(s: String): String      = underlying.normalizeAddress(s)
  def typedDataDigest(d: TypedData)            = underlying.typedDataDigest(d)
  def recoverAddress(d: Array[Byte], s: Array[Byte]) = underlying.recoverAddress(d, s)
  override def verifySignature(d: Array[Byte], s: Array[Byte], a: String): Boolean =
    underlying.verifySignature(d, s, a)

  // ── balance / call queries pass through, scoped to the smart account
  //    address rather than the EOA owner where it matters ───────────────

  def nativeBalance(address: String, ctx: ChainContext): Future[BigInt] =
    underlying.nativeBalance(address, ctx)

  def tokenBalance(asset: Asset, holder: String, ctx: ChainContext): Future[BigInt] =
    underlying.tokenBalance(asset, holder, ctx)

  /** Smart-account "nonce" lives on the EntryPoint, not the EOA's
   *  eth_getTransactionCount. We query EntryPoint.getNonce(sender, key=0). */
  def nonceOf(address: String, ctx: ChainContext): Future[BigInt] =
    val calldata = Abi.encodeFunctionCall(
      "getNonce",
      Seq(AbiType.Address, AbiType.UInt(192)),
      Seq(AbiValue.Address(address), AbiValue.UInt(192, BigInt(0))),
    )
    underlying.call(bundler.entryPoint, calldata, ctx).map { ret =>
      BigInt(1, ret)
    }

  def call(target: String, calldata: Array[Byte], ctx: ChainContext): Future[Array[Byte]] =
    underlying.call(target, calldata, ctx)

  // ── transactions: this is where SmartAccountAdapter actually does
  //    something different ────────────────────────────────────────────────

  def buildTransaction(intent: TxIntent, sender: String, ctx: ChainContext): Future[Tx] =
    // The "sender" param in the SPI semantics is the EOA — but for
    // a UserOp, the sender field is the smart account itself. We
    // ignore the param and use smartAccount.
    val (target, value, innerCalldata) = intent match
      case TxIntent.NativeTransfer(to, amount) =>
        (Some(to), amount, Array.emptyByteArray)
      case TxIntent.TokenTransfer(asset, to, amount) =>
        require(asset.chain == chainId, s"asset.chain ${asset.chain} ≠ adapter $chainId")
        val cd = Abi.encodeFunctionCall(
          "transfer",
          Seq(AbiType.Address, AbiType.UInt(256)),
          Seq(AbiValue.Address(to), AbiValue.UInt(256, amount)),
        )
        (Some(asset.address), BigInt(0), cd)
      case TxIntent.ContractCall(to, data, v) =>
        (Some(to), v, data)
      case other =>
        throw new NotImplementedError(s"SmartAccountAdapter does not yet support $other")

    val callData = factory.executeCalldata(target, value, innerCalldata)

    // Parallel reads: nonce + initCode-needed (probed via account
    // code presence) + fee market via the underlying.
    val nonceF = nonceOf(smartAccount, ctx)
    val codeF  = ctx.rpcCall("eth_getCode", ujson.Str(smartAccount), ujson.Str("latest"))
      .map(v => v.str.stripPrefix("0x"))
      .recover { case _ => "" }
    val feeF   = currentFeeMarket(ctx)
    for
      nonce      <- nonceF
      code       <- codeF
      (mF, mP)   <- feeF
      initCode    = if code.isEmpty || code == "0x" then factory.initCodeFor(owner) else Array.emptyByteArray
      unsigned    = UserOperation(
        sender               = smartAccount,
        nonce                = nonce,
        initCode             = initCode,
        callData             = callData,
        // Conservative defaults — bundler estimate refines these.
        callGasLimit         = BigInt(100_000),
        verificationGasLimit = BigInt(150_000) + (if initCode.length > 0 then BigInt(300_000) else BigInt(0)),
        preVerificationGas   = BigInt(50_000),
        maxFeePerGas         = mF,
        maxPriorityFeePerGas = mP,
        paymasterAndData     = Array.emptyByteArray,
        signature            = Array.emptyByteArray,
      )
      estimate    <- bundler.estimateUserOperationGas(unsigned)
        .recover { case _ => bundler.GasEstimate(unsigned.callGasLimit, unsigned.verificationGasLimit, unsigned.preVerificationGas) }
    yield unsigned.copy(
      callGasLimit         = estimate.callGasLimit,
      verificationGasLimit = estimate.verificationGasLimit,
      preVerificationGas   = estimate.preVerificationGas,
    )

  def prepareSigningPayload(tx: Tx, signer: PublicKey): SigningPayload =
    val cidBig = BigInt(chainId.reference)
    SigningPayload(UserOpHash.compute(tx, bundler.entryPoint, cidBig), HashAlgo.None)

  def assembleSignedTransaction(tx: Tx, signature: Array[Byte], signer: PublicKey): SignedTx =
    require(signature.length == 65, s"ERC-4337 signature must be 65 bytes (r||s||v), got ${signature.length}")
    // Normalise v to 27/28 (Ethereum / SimpleAccount convention).
    val r = java.util.Arrays.copyOfRange(signature, 0, 32)
    val s = java.util.Arrays.copyOfRange(signature, 32, 64)
    val rawV = signature(64).toInt & 0xff
    val v = if rawV < 27 then (rawV + 27).toByte else rawV.toByte
    val normalised = r ++ s ++ Array(v)
    tx.copy(signature = normalised)

  def broadcast(signed: SignedTx, ctx: ChainContext): Future[TxHash] =
    bundler.sendUserOperation(signed).map(TxHash(_))

  def describe(tx: Tx): TxDescription =
    TxDescription(
      summary = s"ERC-4337 UserOp via $smartAccount on $chainId (initCode=${tx.initCode.length}B, callData=${tx.callData.length}B)",
      fields  = Map(
        "sender"               -> tx.sender,
        "nonce"                -> tx.nonce.toString,
        "callGasLimit"         -> tx.callGasLimit.toString,
        "verificationGasLimit" -> tx.verificationGasLimit.toString,
        "preVerificationGas"   -> tx.preVerificationGas.toString,
        "maxFeePerGas"         -> tx.maxFeePerGas.toString,
        "maxPriorityFeePerGas" -> tx.maxPriorityFeePerGas.toString,
        "initCodeBytes"        -> tx.initCode.length.toString,
        "callDataBytes"        -> tx.callData.length.toString,
      ),
    )

  def getReceipt(hash: TxHash, ctx: ChainContext): Future[Option[TxReceipt]] =
    bundler.getUserOperationReceipt(hash.value)

  def waitForReceipt(hash: TxHash, ctx: ChainContext, timeoutMs: Long): Future[TxReceipt] =
    val deadline = System.currentTimeMillis() + timeoutMs
    def loop(): Future[TxReceipt] =
      getReceipt(hash, ctx).flatMap {
        case Some(r) => Future.successful(r)
        case None    =>
          if System.currentTimeMillis() >= deadline then
            Future.failed(new RuntimeException(s"UserOp receipt for $hash not found within ${timeoutMs}ms"))
          else
            Thread.sleep(2000)
            loop()
      }
    Future(loop()).flatten

  def predictDeployAddress(deploy: TxIntent.Deploy, deployer: String, ctx: ChainContext): Future[String] =
    // Smart-account-deployed contracts: the smart account itself
    // becomes the deployer, so CREATE prediction routes through it.
    underlying.predictDeployAddress(deploy, smartAccount, ctx)

  // ── helpers ─────────────────────────────────────────────────────────

  /** Reuse the underlying EVM adapter's fee-market lookup. */
  private def currentFeeMarket(ctx: ChainContext): Future[(BigInt, BigInt)] =
    // EvmChainAdapter's currentFeeMarket is private. Recreate the
    // same heuristic locally (priority + 2*baseFee) so we don't
    // widen the EVM SPI just for this consumer.
    val prioF = ctx.rpcCall("eth_maxPriorityFeePerGas")
      .map(v => BigInt(v.str.stripPrefix("0x"), 16))
      .recoverWith { case _ =>
        ctx.rpcCall("eth_gasPrice").map(v =>
          BigInt(v.str.stripPrefix("0x"), 16) / 10)
      }
    val baseFeeF = ctx.rpcCall("eth_getBlockByNumber", ujson.Str("latest"), ujson.False)
      .map { block =>
        block.obj.get("baseFeePerGas").map(b => BigInt(b.str.stripPrefix("0x"), 16))
          .getOrElse(BigInt(0))
      }
    for
      prio    <- prioF
      baseFee <- baseFeeF
    yield (baseFee * 2 + prio, prio)
