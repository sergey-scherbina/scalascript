package scalascript.blockchain.evm

import org.scalatest.{BeforeAndAfterAll, Assertions}
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, CryptoBackend, HashAlgo, PublicKey}

/** End-to-end EVM integration test against a locally-spawned anvil
 *  node. Exercises the full pipeline the autopay layer relies on:
 *  build → sign → broadcast → receipt → balance check, against a
 *  real (in-process) EVM client speaking real JSON-RPC.
 *
 *  Skips cleanly when `anvil` isn't on PATH so CI without Foundry
 *  doesn't fail. To run locally: `curl -L https://foundry.paradigm.xyz | bash`
 *  then `foundryup` to install the toolchain. */
class EvmAnvilIntegrationTest extends AnyFunSuite with BeforeAndAfterAll:
  given ExecutionContext = ExecutionContext.global

  private var nodeOpt: Option[AnvilNode] = None

  override def beforeAll(): Unit =
    if AnvilTestHarness.isAvailable then
      nodeOpt = Some(AnvilTestHarness.start())

  override def afterAll(): Unit =
    nodeOpt.foreach(_.stop())
    nodeOpt = None

  private def withNode(body: AnvilNode => Unit): Unit =
    nodeOpt match
      case Some(node) => body(node)
      case None       => cancel("anvil not on PATH — skipping integration test")

  private lazy val adapter = new EvmChainAdapter(ChainId(s"eip155:${AnvilTestHarness.ChainIdInt}"))
  private lazy val priv    = Hex.decode(AnvilTestHarness.Account0PrivHex)
  private lazy val be      = CryptoBackend.get()
  private lazy val pub     = be.derivePublic(Curve.Secp256k1, priv)
  private lazy val sender  = AnvilTestHarness.Account0Address
  private lazy val to      = AnvilTestHarness.Account1Address

  // ── sanity: harness brought up a node ────────────────────────────────

  test("anvil reports its chain id + accounts when available") {
    withNode { node =>
      val chainIdHex = Await.result(node.context.rpcCall("eth_chainId"), 5.seconds).str
      val cid = BigInt(chainIdHex.stripPrefix("0x"), 16)
      assert(cid == BigInt(AnvilTestHarness.ChainIdInt))

      // Account 0 is pre-funded with 10000 ETH (per anvil's defaults).
      val bal = Await.result(adapter.nativeBalance(sender, node.context), 5.seconds)
      assert(bal > BigInt("9000000000000000000000"),   // > 9000 ETH in wei
        s"expected ~10000 ETH pre-funded, got $bal wei")
    }
  }

  // ── full NativeTransfer round-trip ──────────────────────────────────

  test("native ETH transfer round-trips through the adapter end-to-end") {
    withNode { node =>
      val before   = Await.result(adapter.nativeBalance(to, node.context), 5.seconds)
      val oneEth   = BigInt("1000000000000000000")    // 1 ETH in wei
      val intent   = TxIntent.NativeTransfer(to, oneEth)
      val tx       = Await.result(adapter.buildTransaction(intent, sender, node.context), 5.seconds)
      val payload  = adapter.prepareSigningPayload(tx, PublicKey(Curve.Secp256k1, pub))
      val sig      = be.sign(Curve.Secp256k1, priv, payload.bytes, HashAlgo.None)
      val signed   = adapter.assembleSignedTransaction(tx, sig, PublicKey(Curve.Secp256k1, pub))
      val hash     = Await.result(adapter.broadcast(signed, node.context), 5.seconds)
      assert(hash.value.startsWith("0x"))

      val receipt  = Await.result(adapter.waitForReceipt(hash, node.context, 10_000), 10.seconds)
      assert(receipt.success, s"tx failed on-chain: $receipt")
      assert(receipt.blockNumber > 0)

      val after = Await.result(adapter.nativeBalance(to, node.context), 5.seconds)
      assert(after == before + oneEth,
        s"balance delta: expected +$oneEth, got ${after - before}")
    }
  }

  // ── nonce advance, receipt parsing ──────────────────────────────────

  test("nonce advances after a successful transfer") {
    withNode { node =>
      val nonceBefore = Await.result(adapter.nonceOf(sender, node.context), 5.seconds)
      val intent      = TxIntent.NativeTransfer(to, BigInt(1_000_000_000L))    // 1 gwei
      val tx          = Await.result(adapter.buildTransaction(intent, sender, node.context), 5.seconds)
      val payload     = adapter.prepareSigningPayload(tx, PublicKey(Curve.Secp256k1, pub))
      val sig         = be.sign(Curve.Secp256k1, priv, payload.bytes, HashAlgo.None)
      val signed      = adapter.assembleSignedTransaction(tx, sig, PublicKey(Curve.Secp256k1, pub))
      val hash        = Await.result(adapter.broadcast(signed, node.context), 5.seconds)
      Await.result(adapter.waitForReceipt(hash, node.context, 10_000), 10.seconds)
      val nonceAfter  = Await.result(adapter.nonceOf(sender, node.context), 5.seconds)
      assert(nonceAfter == nonceBefore + 1)
    }
  }

  // ── recovery sanity: signature ecrecover matches the sender ─────────

  test("ecrecover on a signed tx hash returns the sender's address") {
    withNode { node =>
      val intent  = TxIntent.NativeTransfer(to, BigInt(42))
      val tx      = Await.result(adapter.buildTransaction(intent, sender, node.context), 5.seconds)
      val payload = adapter.prepareSigningPayload(tx, PublicKey(Curve.Secp256k1, pub))
      val sig     = be.sign(Curve.Secp256k1, priv, payload.bytes, HashAlgo.None)
      val recovered = adapter.recoverAddress(payload.bytes, sig)
      assert(recovered.exists(_.equalsIgnoreCase(sender)),
        s"recovered $recovered, expected $sender")
    }
  }
