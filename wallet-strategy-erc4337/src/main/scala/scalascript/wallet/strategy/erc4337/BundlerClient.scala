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
 *  Gas-estimate results come back with keys keyed differently
 *  per provider (preVerificationGas vs preVerificationGasLimit
 *  etc); we normalise on the way in. */
class BundlerClient(val ctx: ChainContext, val entryPoint: String = EntryPoint.V06Address)(using ec: ExecutionContext):

  /** Submit a signed UserOp. Returns the userOpHash the bundler
   *  acknowledges — the hash is computed deterministically from the
   *  userOp + entryPoint + chainId, so the bundler's reply must
   *  match what we computed locally. */
  def sendUserOperation(op: UserOperation): Future[String] =
    ctx.rpcCall("eth_sendUserOperation", UserOpJson.toJson(op), ujson.Str(entryPoint)).map { v =>
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
    ctx.rpcCall("eth_estimateUserOperationGas", UserOpJson.toJson(op), ujson.Str(entryPoint)).map { v =>
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

/** JSON serialisation for UserOperation per the v0.6 bundler RPC
 *  spec — all integers as `0x`-prefixed hex strings, all byte
 *  arrays as `0x`-prefixed hex. */
private[erc4337] object UserOpJson:
  def toJson(op: UserOperation): ujson.Value =
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
