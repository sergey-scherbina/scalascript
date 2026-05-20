package scalascript.wallet.strategy.erc4337

import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.evm.Hex
import scalascript.blockchain.spi.{ChainContext, TxHash, TxReceipt, Log}

/** ERC-4337 bundler RPC client. Bundlers expose a superset of the
 *  normal Ethereum JSON-RPC surface — every `eth_*` method works,
 *  plus the AA-specific:
 *
 *      eth_sendUserOperation(userOp, entryPoint)
 *      eth_estimateUserOperationGas(userOp, entryPoint)
 *      eth_getUserOperationByHash(userOpHash)
 *      eth_getUserOperationReceipt(userOpHash)
 *      eth_supportedEntryPoints()
 *
 *  Implementations: Pimlico, Stackup, Alchemy AA, Biconomy,
 *  Candide. We talk to whichever the user configures via the
 *  ChainContext.
 *
 *  The client is version-aware: pass `EntryPoint.Version.V07` (and
 *  optionally an explicit V0.7 EntryPoint address) and the on-wire
 *  JSON shape, the on-chain `userOpHash` formula, and the default
 *  EntryPoint address all switch in lockstep. The default remains
 *  v0.6 because that's still the dominant deployment in 2026.
 *
 *  Gas-estimate results come back with keys keyed differently
 *  per provider (preVerificationGas vs preVerificationGasLimit
 *  etc); we normalise on the way in. */
class BundlerClient(
  val ctx:         ChainContext,
  val entryPoint:  String              = EntryPoint.V06Address,
  val version:     EntryPoint.Version  = EntryPoint.Version.V06,
)(using ec: ExecutionContext):

  private def encodeOp(op: UserOperation): ujson.Value = version match
    case EntryPoint.Version.V06 => UserOpJson.toJsonV06(op)
    case EntryPoint.Version.V07 => UserOpJson.toJsonV07(op)

  /** Compute the userOpHash a wallet must sign for this client's
   *  EntryPoint + chain. Routes through v0.6 or v0.7 hashing per
   *  the configured version. */
  def userOpHash(op: UserOperation, chainId: BigInt): Array[Byte] = version match
    case EntryPoint.Version.V06 => UserOpHash.compute(op, entryPoint, chainId)
    case EntryPoint.Version.V07 => UserOpHashV07.compute(op, entryPoint, chainId)

  /** Submit a signed UserOp. Returns the userOpHash the bundler
   *  acknowledges — the hash is computed deterministically from the
   *  userOp + entryPoint + chainId, so the bundler's reply must
   *  match what we computed locally. */
  def sendUserOperation(op: UserOperation): Future[String] =
    ctx.rpcCall("eth_sendUserOperation", encodeOp(op), ujson.Str(entryPoint)).map { v =>
      v.str
    }

  /** Ask the bundler to fill in the three gas knobs.
   *
   *  callGasLimit          — gas for execute() after validation
   *  verificationGasLimit  — gas for validateUserOp + paymaster.validatePaymasterUserOp
   *  preVerificationGas    — gas the bundler can't deduct from the
   *                          actual handleOps call (calldata cost +
   *                          bundler overhead)
   *
   *  Some bundlers pad the estimates; we surface them as-is. */
  case class GasEstimate(callGasLimit: BigInt, verificationGasLimit: BigInt, preVerificationGas: BigInt)

  def estimateUserOperationGas(op: UserOperation): Future[GasEstimate] =
    ctx.rpcCall("eth_estimateUserOperationGas", encodeOp(op), ujson.Str(entryPoint)).map { v =>
      // Provider keys vary: some use `verificationGas`, some
      // `verificationGasLimit`. Accept either.
      val obj = v.obj
      def hexInt(keys: String*): BigInt =
        val k = keys.find(obj.contains).getOrElse(
          throw new RuntimeException(s"missing key (${keys.mkString(",")}) in estimateUserOperationGas: ${v.render()}"),
        )
        BigInt(obj(k).str.stripPrefix("0x"), 16)
      GasEstimate(
        callGasLimit         = hexInt("callGasLimit"),
        verificationGasLimit = hexInt("verificationGasLimit", "verificationGas"),
        preVerificationGas   = hexInt("preVerificationGas"),
      )
    }

  /** Look up the receipt for a previously-submitted UserOp.
   *  Returns None if the bundler hasn't seen it yet (still in the
   *  mempool) or if the on-chain handleOps batch hasn't included
   *  it. The shape — once present — is essentially a regular tx
   *  receipt with an embedded `userOpHash` reference. */
  def getUserOperationReceipt(userOpHash: String): Future[Option[TxReceipt]] =
    ctx.rpcCall("eth_getUserOperationReceipt", ujson.Str(userOpHash)).map {
      case ujson.Null => None
      case obj        =>
        val r = obj.obj.get("receipt").map(_.obj).getOrElse(obj.obj)
        val txHash = r("transactionHash").str
        val blockNumber = BigInt(r("blockNumber").str.stripPrefix("0x"), 16)
        val gasUsed = BigInt(r.getOrElse("actualGasUsed",
          r.getOrElse("gasUsed", ujson.Str("0x0"))).str.stripPrefix("0x"), 16)
        val status = r.get("status").map(_.str.stripPrefix("0x"))
          .map(s => BigInt(s, 16).toInt).getOrElse(1)
        val logs = r.get("logs").toSeq.flatMap(_.arr).map { logJson =>
          Log(
            address = logJson("address").str,
            topics  = logJson("topics").arr.toSeq.map(t => Hex.decode(t.str)),
            data    = Hex.decode(logJson("data").str),
          )
        }
        Some(TxReceipt(
          hash        = TxHash(txHash),
          success     = status == 1,
          blockNumber = blockNumber,
          gasUsed     = gasUsed,
          logs        = logs,
        ))
    }

  def supportedEntryPoints(): Future[Seq[String]] =
    ctx.rpcCall("eth_supportedEntryPoints").map { v =>
      v.arr.toSeq.map(_.str)
    }

object BundlerClient:
  /** Convenience: construct a v0.7 client with the canonical
   *  EntryPoint address (or an override if the bundler uses a
   *  custom deployment). */
  def v07(ctx: ChainContext, entryPoint: String = EntryPoint.V07Address)
         (using ExecutionContext): BundlerClient =
    new BundlerClient(ctx, entryPoint, EntryPoint.Version.V07)

/** JSON serialisation for UserOperation, per the v0.6 and v0.7
 *  bundler RPC specs:
 *
 *  - **v0.6**: a flat 11-field object — `initCode` and
 *    `paymasterAndData` stay as opaque hex blobs.
 *  - **v0.7**: a 15-field object that splits `initCode` into
 *    `factory` / `factoryData` (first 20B = factory address) and
 *    `paymasterAndData` into `paymaster` /
 *    `paymasterVerificationGasLimit` / `paymasterPostOpGasLimit` /
 *    `paymasterData` (paymaster + two u128 gas limits at fixed
 *    offsets + tail data).
 *
 *  Numeric fields are `0x`-prefixed hex; byte fields are
 *  `0x`-prefixed hex. Empty initCode / paymaster sections omit their
 *  split keys entirely on the v0.7 wire — that's what bundlers
 *  expect for the "already-deployed" / "self-paying" cases. */
private[erc4337] object UserOpJson:

  def toJsonV06(op: UserOperation): ujson.Value =
    ujson.Obj(
      "sender"               -> ujson.Str(op.sender),
      "nonce"                -> ujson.Str("0x" + op.nonce.toString(16)),
      "initCode"             -> ujson.Str("0x" + Hex.encode(op.initCode, withPrefix = false)),
      "callData"             -> ujson.Str("0x" + Hex.encode(op.callData, withPrefix = false)),
      "callGasLimit"         -> ujson.Str("0x" + op.callGasLimit.toString(16)),
      "verificationGasLimit" -> ujson.Str("0x" + op.verificationGasLimit.toString(16)),
      "preVerificationGas"   -> ujson.Str("0x" + op.preVerificationGas.toString(16)),
      "maxFeePerGas"         -> ujson.Str("0x" + op.maxFeePerGas.toString(16)),
      "maxPriorityFeePerGas" -> ujson.Str("0x" + op.maxPriorityFeePerGas.toString(16)),
      "paymasterAndData"     -> ujson.Str("0x" + Hex.encode(op.paymasterAndData, withPrefix = false)),
      "signature"            -> ujson.Str("0x" + Hex.encode(op.signature, withPrefix = false)),
    )

  /** Back-compat alias for the v0.6 form. */
  @deprecated("use toJsonV06 — v0.7 has a different shape", "2026-05-20")
  def toJson(op: UserOperation): ujson.Value = toJsonV06(op)

  def toJsonV07(op: UserOperation): ujson.Value =
    val out = ujson.Obj()
    out("sender")               = ujson.Str(op.sender)
    out("nonce")                = ujson.Str("0x" + op.nonce.toString(16))
    if op.initCode.length >= 20 then
      out("factory")     = ujson.Str("0x" + Hex.encode(op.initCode.take(20), withPrefix = false))
      out("factoryData") = ujson.Str("0x" + Hex.encode(op.initCode.drop(20), withPrefix = false))
    out("callData")             = ujson.Str("0x" + Hex.encode(op.callData, withPrefix = false))
    out("callGasLimit")         = ujson.Str("0x" + op.callGasLimit.toString(16))
    out("verificationGasLimit") = ujson.Str("0x" + op.verificationGasLimit.toString(16))
    out("preVerificationGas")   = ujson.Str("0x" + op.preVerificationGas.toString(16))
    out("maxFeePerGas")         = ujson.Str("0x" + op.maxFeePerGas.toString(16))
    out("maxPriorityFeePerGas") = ujson.Str("0x" + op.maxPriorityFeePerGas.toString(16))
    if op.paymasterAndData.length >= 52 then
      val pAddr    = op.paymasterAndData.take(20)
      val pVerGas  = BigInt(1, op.paymasterAndData.slice(20, 36))   // 16 B u128
      val pPostGas = BigInt(1, op.paymasterAndData.slice(36, 52))   // 16 B u128
      val pData    = op.paymasterAndData.drop(52)
      out("paymaster")                     = ujson.Str("0x" + Hex.encode(pAddr, withPrefix = false))
      out("paymasterVerificationGasLimit") = ujson.Str("0x" + pVerGas.toString(16))
      out("paymasterPostOpGasLimit")       = ujson.Str("0x" + pPostGas.toString(16))
      out("paymasterData")                 = ujson.Str("0x" + Hex.encode(pData, withPrefix = false))
    out("signature")            = ujson.Str("0x" + Hex.encode(op.signature, withPrefix = false))
    out
