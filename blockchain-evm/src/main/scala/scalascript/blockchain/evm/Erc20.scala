package scalascript.blockchain.evm

import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.evm.abi.*
import scalascript.blockchain.spi.*

/** Typed proxy over an ERC-20 token contract.
 *
 *  Reads (`balanceOf`, `allowance`, `decimals`, `symbol`, `name`)
 *  go through `adapter.call` + ABI decode. Writes (`transfer`,
 *  `approve`, `transferWithAuthorization`) return `TxIntent`
 *  builders — the caller pairs them with their `AccountStrategy`
 *  + `EvmChainAdapter.buildTransaction` to produce a signed,
 *  broadcastable tx.
 *
 *  Pre-Phase-2 (`AbiHelpers.erc20BalanceOfCalldata` etc.) hand-coded
 *  the same selectors; the typed proxy now generates them via the
 *  ABI codec from `blockchain-evm-abi`. The hand-coded helpers stay
 *  in place so existing callers keep working until they migrate. */
class Erc20(val address: String, val chain: EvmChainAdapter)(using ec: ExecutionContext):

  // ── reads ─────────────────────────────────────────────────────────────

  def balanceOf(holder: String, ctx: ChainContext): Future[BigInt] =
    val cd = Abi.encodeFunctionCall(
      "balanceOf",
      Seq(AbiType.Address),
      Seq(AbiValue.Address(holder)),
    )
    chain.call(address, cd, ctx).map { ret =>
      val decoded = Abi.decode(AbiType.UInt(256), ret).asInstanceOf[AbiValue.UInt]
      decoded.value
    }

  def allowance(owner: String, spender: String, ctx: ChainContext): Future[BigInt] =
    val cd = Abi.encodeFunctionCall(
      "allowance",
      Seq(AbiType.Address, AbiType.Address),
      Seq(AbiValue.Address(owner), AbiValue.Address(spender)),
    )
    chain.call(address, cd, ctx).map { ret =>
      Abi.decode(AbiType.UInt(256), ret).asInstanceOf[AbiValue.UInt].value
    }

  def decimals(ctx: ChainContext): Future[Int] =
    val cd = Abi.encodeFunctionCall("decimals", Seq.empty, Seq.empty)
    chain.call(address, cd, ctx).map { ret =>
      Abi.decode(AbiType.UInt(8), ret).asInstanceOf[AbiValue.UInt].value.toInt
    }

  def symbol(ctx: ChainContext): Future[String] =
    val cd = Abi.encodeFunctionCall("symbol", Seq.empty, Seq.empty)
    chain.call(address, cd, ctx).map { ret =>
      Abi.decode(AbiType.Str, ret).asInstanceOf[AbiValue.Str].value
    }

  def name(ctx: ChainContext): Future[String] =
    val cd = Abi.encodeFunctionCall("name", Seq.empty, Seq.empty)
    chain.call(address, cd, ctx).map { ret =>
      Abi.decode(AbiType.Str, ret).asInstanceOf[AbiValue.Str].value
    }

  // ── writes (returns a TxIntent for the caller to sign+broadcast) ─────

  /** Returns a `ContractCall` intent. Pair with
   *  `chain.buildTransaction(intent, sender, ctx)` →
   *  `strategy.signTransaction` → `chain.broadcast`. */
  def transfer(to: String, amount: BigInt): TxIntent.ContractCall =
    TxIntent.ContractCall(
      target   = address,
      calldata = Abi.encodeFunctionCall(
        "transfer",
        Seq(AbiType.Address, AbiType.UInt(256)),
        Seq(AbiValue.Address(to), AbiValue.UInt(256, amount)),
      ),
    )

  def approve(spender: String, amount: BigInt): TxIntent.ContractCall =
    TxIntent.ContractCall(
      target   = address,
      calldata = Abi.encodeFunctionCall(
        "approve",
        Seq(AbiType.Address, AbiType.UInt(256)),
        Seq(AbiValue.Address(spender), AbiValue.UInt(256, amount)),
      ),
    )

  /** Build the `transferWithAuthorization(...)` calldata used by x402
   *  settlement. The 65-byte `signature` is the EIP-712 sig from the
   *  authorising party; we split it into r/s/v inside the ABI args. */
  def transferWithAuthorization(
    from:        String,
    to:          String,
    value:       BigInt,
    validAfter:  BigInt,
    validBefore: BigInt,
    nonce:       Array[Byte],
    signature:   Array[Byte],
  ): TxIntent.ContractCall =
    require(nonce.length == 32, s"nonce must be 32 bytes, got ${nonce.length}")
    require(signature.length == 65, s"signature must be 65 bytes, got ${signature.length}")
    val r = java.util.Arrays.copyOfRange(signature, 0,  32)
    val s = java.util.Arrays.copyOfRange(signature, 32, 64)
    val v = signature(64).toInt & 0xff
    val cd = Abi.encodeFunctionCall(
      "transferWithAuthorization",
      Seq(
        AbiType.Address, AbiType.Address,
        AbiType.UInt(256), AbiType.UInt(256), AbiType.UInt(256),
        AbiType.FixedBytes(32),
        AbiType.UInt(8),
        AbiType.FixedBytes(32), AbiType.FixedBytes(32),
      ),
      Seq(
        AbiValue.Address(from), AbiValue.Address(to),
        AbiValue.UInt(256, value),
        AbiValue.UInt(256, validAfter), AbiValue.UInt(256, validBefore),
        AbiValue.FixedBytes(32, nonce),
        AbiValue.UInt(8, BigInt(v)),
        AbiValue.FixedBytes(32, r), AbiValue.FixedBytes(32, s),
      ),
    )
    TxIntent.ContractCall(target = address, calldata = cd)

object Erc20:

  // ── events ────────────────────────────────────────────────────────────

  /** ERC-20 Transfer(address indexed from, address indexed to,
   *  uint256 value). */
  object Transfer:
    val topic0: Array[Byte] =
      Selector.eventTopic0("Transfer", Seq(AbiType.Address, AbiType.Address, AbiType.UInt(256)))

    case class Event(token: String, from: String, to: String, value: BigInt)

    /** Decode every Transfer event present in `logs`. */
    def from(logs: Seq[Log]): Seq[Event] =
      logs.collect {
        case Log(address, topics, data)
            if topics.nonEmpty && topics(0).sameElements(topic0) && topics.size >= 3 =>
          Event(
            token = address,
            from  = "0x" + topicToAddress(topics(1)),
            to    = "0x" + topicToAddress(topics(2)),
            value = Abi.decode(AbiType.UInt(256), data).asInstanceOf[AbiValue.UInt].value,
          )
      }

  /** ERC-20 Approval(address indexed owner, address indexed spender,
   *  uint256 value). */
  object Approval:
    val topic0: Array[Byte] =
      Selector.eventTopic0("Approval", Seq(AbiType.Address, AbiType.Address, AbiType.UInt(256)))

    case class Event(token: String, owner: String, spender: String, value: BigInt)

    def from(logs: Seq[Log]): Seq[Event] =
      logs.collect {
        case Log(address, topics, data)
            if topics.nonEmpty && topics(0).sameElements(topic0) && topics.size >= 3 =>
          Event(
            token   = address,
            owner   = "0x" + topicToAddress(topics(1)),
            spender = "0x" + topicToAddress(topics(2)),
            value   = Abi.decode(AbiType.UInt(256), data).asInstanceOf[AbiValue.UInt].value,
          )
      }

  /** Indexed address topics are 32-byte words with the address in the
   *  low 20 bytes; this returns the bare 40-hex string for joining
   *  with `0x` (no EIP-55 checksum applied — leave that to UI). */
  private def topicToAddress(topic: Array[Byte]): String =
    require(topic.length == 32, s"address topic must be 32 bytes, got ${topic.length}")
    topic.drop(12).map(b => f"${b & 0xff}%02x").mkString
