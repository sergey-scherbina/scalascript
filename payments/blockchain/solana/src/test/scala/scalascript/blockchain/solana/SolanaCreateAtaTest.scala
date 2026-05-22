package scalascript.blockchain.solana

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, CryptoBackend, HashAlgo, PublicKey}

/** Slice 4 tests: idempotent CreateAssociatedTokenAccount prepended
 *  when the destination ATA does not yet exist, so first-time
 *  recipients work in a single transaction. */
class SolanaCreateAtaTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val adapter = new SolanaChainAdapter(SolanaChainAdapter.Mainnet)
  private val be      = CryptoBackend.get()

  private val priv     = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
  private val pubBytes = be.derivePublic(Curve.Ed25519, priv)
  private val sender   = adapter.addressFromPublicKey(PublicKey(Curve.Ed25519, pubBytes))
  private val recipient      = "11111111111111111111111111111112"
  private val blockhashStr   = "EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N"
  private val usdcMintBase58 = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
  private val usdc           = Asset(SolanaChainAdapter.Mainnet, usdcMintBase58, "USDC", 6)

  // ── missing destination ATA → CreateATA prepended ────────────────────

  test("buildTransaction(TokenTransfer) prepends CreateAssociatedTokenAccountIdempotent when dest ATA is missing") {
    val ctx: ChainContext = new ChainContext:
      def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
        method match
          case "getLatestBlockhash" =>
            Future.successful(ujson.Obj(
              "value" -> ujson.Obj("blockhash" -> ujson.Str(blockhashStr)),
            ))
          case "getAccountInfo" =>
            // Mock missing destination ATA — { value: null }.
            Future.successful(ujson.Obj("value" -> ujson.Null))
          case other =>
            Future.failed(new RuntimeException(s"unmocked: $other"))
      def nowSeconds: Long = 1700000000L

    val intent = TxIntent.TokenTransfer(usdc, recipient, BigInt(2_500_000))
    val tx     = Await.result(adapter.buildTransaction(intent, sender, ctx), 5.seconds)
    val m      = tx.message

    // Header reflects the 8-key layout: 1 signer, 5 read-only unsigned.
    assert(m.numRequiredSignatures == 1)
    assert(m.numReadonlySignedAccounts == 0)
    assert(m.numReadonlyUnsignedAccounts == 5)
    assert(m.accountKeys.size == 8)

    // Account-key positions: [sender, srcATA, destATA, mint, tokenProg,
    //                         recipient, sysProg, ataProg].
    assert(m.accountKeys(0).toSeq == pubBytes.toSeq)
    assert(m.accountKeys(3).toSeq == Base58.decode(usdcMintBase58).toSeq)
    assert(m.accountKeys(4).toSeq == SplToken.ProgramId.toSeq)
    assert(m.accountKeys(5).toSeq == Base58.decode(recipient).toSeq)
    assert(m.accountKeys(6).toSeq == new Array[Byte](32).toSeq)              // System Program
    assert(m.accountKeys(7).toSeq == SplToken.AssociatedProgramId.toSeq)

    // Two instructions: CreateATA-idempotent first, TransferChecked second.
    assert(m.instructions.size == 2)
    val createIx   = m.instructions.head
    val transferIx = m.instructions(1)

    // CreateATA-idempotent
    assert(createIx.programIdIndex == 7)
    assert(createIx.accountIndexes.toSeq == Seq[Byte](0, 2, 5, 3, 6, 4))
    assert(createIx.data.length == 1)
    assert(createIx.data(0) == 1)

    // TransferChecked
    assert(transferIx.programIdIndex == 4)
    assert(transferIx.accountIndexes.toSeq == Seq[Byte](1, 3, 2, 0))
    assert(transferIx.data(0) == 12)
    assert(transferIx.data(9) == 6)
  }

  test("buildTransaction(TokenTransfer) treats getAccountInfo failure as missing → CreateATA prepended") {
    // Robustness: if the probe RPC fails (e.g. transient network), we
    // fall back to "missing" rather than dropping the transfer.
    // Prepending an idempotent create is always safe.
    val ctx: ChainContext = new ChainContext:
      def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
        method match
          case "getLatestBlockhash" =>
            Future.successful(ujson.Obj("value" -> ujson.Obj("blockhash" -> ujson.Str(blockhashStr))))
          case "getAccountInfo" =>
            Future.failed(new RuntimeException("network down"))
          case other =>
            Future.failed(new RuntimeException(s"unmocked: $other"))
      def nowSeconds: Long = 0L

    val intent = TxIntent.TokenTransfer(usdc, recipient, BigInt(1))
    val tx     = Await.result(adapter.buildTransaction(intent, sender, ctx), 5.seconds)
    assert(tx.message.instructions.size == 2)
    assert(tx.message.accountKeys.size == 8)
  }

  // ── full sign/broadcast over the 8-key tx ─────────────────────────────

  test("create + transfer 8-key tx signs and broadcasts as one transaction") {
    var lastBroadcastBase64: Option[String] = None
    val ctx: ChainContext = new ChainContext:
      def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
        method match
          case "getLatestBlockhash" =>
            Future.successful(ujson.Obj("value" -> ujson.Obj("blockhash" -> ujson.Str(blockhashStr))))
          case "getAccountInfo" =>
            Future.successful(ujson.Obj("value" -> ujson.Null))
          case "sendTransaction" =>
            lastBroadcastBase64 = Some(params.head.str)
            Future.successful(ujson.Str("CreateTransferSig"))
          case other =>
            Future.failed(new RuntimeException(s"unmocked: $other"))
      def nowSeconds: Long = 0L

    val intent  = TxIntent.TokenTransfer(usdc, recipient, BigInt(500_000))
    val tx      = Await.result(adapter.buildTransaction(intent, sender, ctx), 5.seconds)
    val payload = adapter.prepareSigningPayload(tx, PublicKey(Curve.Ed25519, pubBytes))
    val sig     = be.sign(Curve.Ed25519, priv, payload.bytes, HashAlgo.None)
    val signed  = adapter.assembleSignedTransaction(tx, sig, PublicKey(Curve.Ed25519, pubBytes))
    val hash    = Await.result(adapter.broadcast(signed, ctx), 5.seconds)
    assert(hash.value == "CreateTransferSig")
    assert(lastBroadcastBase64.isDefined)

    // The signature in the broadcast verifies against the message bytes.
    val raw         = java.util.Base64.getDecoder.decode(lastBroadcastBase64.get)
    val messageBody = raw.drop(1 + 64)
    assert(be.verify(Curve.Ed25519, pubBytes, messageBody, sig, HashAlgo.None))
  }

  // ── util ──────────────────────────────────────────────────────────────

  private def hex(s: String): Array[Byte] =
    val out = new Array[Byte](s.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out
