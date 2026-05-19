package scalascript.blockchain.solana

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, CryptoBackend, HashAlgo, PublicKey}

/** Slice 2 tests: SolanaMessage serialisation, CompactU16, full
 *  NativeTransfer build → sign → assemble → broadcast round-trip
 *  against a mocked Solana JSON-RPC. */
class SolanaTxTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val adapter = new SolanaChainAdapter(SolanaChainAdapter.Mainnet)
  private val be      = CryptoBackend.get()

  // Known keys / addresses.
  private val priv     = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
  private val pubBytes = be.derivePublic(Curve.Ed25519, priv)
  private val sender   = adapter.addressFromPublicKey(PublicKey(Curve.Ed25519, pubBytes))
  private val recipient = "11111111111111111111111111111112"   // any valid 32-byte base58 (System Program + 1)
  private val recipientBytes = Base58.decode(recipient)
  private val blockhashStr = "EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N"   // example real Solana blockhash
  private val blockhashBytes = Base58.decode(blockhashStr)

  // ── CompactU16 ────────────────────────────────────────────────────────

  test("CompactU16 encodes < 0x80 as a single byte") {
    assert(CompactU16.encode(0).toSeq   == Seq[Byte](0))
    assert(CompactU16.encode(1).toSeq   == Seq[Byte](1))
    assert(CompactU16.encode(127).toSeq == Seq[Byte](127.toByte))
  }

  test("CompactU16 encodes 128 as two bytes (top bit set)") {
    // 128 = 0x80 → first byte 0x80 | 0x00 = 0x80, second byte 0x01
    assert(CompactU16.encode(128).toSeq == Seq[Byte](0x80.toByte, 0x01))
  }

  test("CompactU16 encodes 16383 as two bytes") {
    // 16383 = 0x3fff → first byte 0xff (0x7f | 0x80), second byte 0x7f
    assert(CompactU16.encode(16383).toSeq == Seq[Byte](0xff.toByte, 0x7f))
  }

  test("CompactU16 encodes 16384 as three bytes") {
    // 16384 = 0x4000 → 0x80 0x80 0x01
    assert(CompactU16.encode(16384).toSeq == Seq[Byte](0x80.toByte, 0x80.toByte, 0x01))
  }

  test("CompactU16 round-trips") {
    Seq(0, 1, 127, 128, 16383, 16384, 65535).foreach { n =>
      val bytes = CompactU16.encode(n)
      val (v, _) = CompactU16.decode(bytes, 0)
      assert(v == n, s"round-trip failed for $n")
    }
  }

  // ── SolanaMessage serialisation ──────────────────────────────────────

  test("SolanaMessage.serialize lays out header + accounts + blockhash + instructions") {
    val msg = SolanaMessage(
      numRequiredSignatures        = 1,
      numReadonlySignedAccounts    = 0,
      numReadonlyUnsignedAccounts  = 1,
      accountKeys                  = Seq(pubBytes, recipientBytes, new Array[Byte](32)),
      recentBlockhash              = blockhashBytes,
      instructions                 = Seq(SolanaInstruction(
        programIdIndex = 2,
        accountIndexes = Array[Byte](0, 1),
        data           = Array[Byte](2, 0, 0, 0, 0xe8.toByte, 3, 0, 0, 0, 0, 0, 0),  // transfer 1000 lamports
      )),
    )
    val bytes = msg.serialize
    // Header (3 bytes)
    assert(bytes(0) == 1)
    assert(bytes(1) == 0)
    assert(bytes(2) == 1)
    // CompactU16 account count = 3
    assert(bytes(3) == 3)
    // 3 × 32 = 96 bytes of account keys → ends at offset 4+96 = 100
    assert(bytes.length == 4 + 96 + 32 + 1 + (1 + 1 + 2 + 1 + 12))
    // Recent blockhash starts at 100
    assert(bytes.slice(100, 132).toSeq == blockhashBytes.toSeq)
    // Instruction count = 1
    assert(bytes(132) == 1)
    // Program id index = 2
    assert(bytes(133) == 2)
  }

  // ── full NativeTransfer round-trip ───────────────────────────────────

  test("build → sign → broadcast NativeTransfer end-to-end through mocked RPC") {
    var lastBroadcastBase64: Option[String] = None
    val ctx: ChainContext = new ChainContext:
      def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
        method match
          case "getLatestBlockhash" =>
            Future.successful(ujson.Obj(
              "context" -> ujson.Obj("slot" -> ujson.Num(100)),
              "value"   -> ujson.Obj(
                "blockhash" -> ujson.Str(blockhashStr),
                "lastValidBlockHeight" -> ujson.Num(200),
              ),
            ))
          case "sendTransaction" =>
            lastBroadcastBase64 = Some(params.head.str)
            // Solana returns the signature as the result.
            Future.successful(ujson.Str("3pVbA2…"))
          case other =>
            Future.failed(new RuntimeException(s"unmocked: $other"))
      def nowSeconds: Long = 1700000000L

    val intent  = TxIntent.NativeTransfer(recipient, BigInt(1_000))
    val tx      = Await.result(adapter.buildTransaction(intent, sender, ctx), 5.seconds)
    val payload = adapter.prepareSigningPayload(tx, PublicKey(Curve.Ed25519, pubBytes))
    val sig     = be.sign(Curve.Ed25519, priv, payload.bytes, HashAlgo.None)
    assert(sig.length == 64)
    val signed  = adapter.assembleSignedTransaction(tx, sig, PublicKey(Curve.Ed25519, pubBytes))
    val hash    = Await.result(adapter.broadcast(signed, ctx), 5.seconds)

    assert(hash.value == "3pVbA2…")
    assert(lastBroadcastBase64.isDefined)
    // Decode back and verify shape: 1 (sig count) + 64 (sig) + ... message
    val raw = java.util.Base64.getDecoder.decode(lastBroadcastBase64.get)
    assert(raw(0) == 1, "signature compact-array count should be 1")
    // First 65 bytes = [1, sig]; the rest is the message
    val messageBytes = raw.drop(1 + 64)
    assert(messageBytes(0) == 1, "numRequiredSignatures should be 1")
    assert(messageBytes(3) == 3, "should encode 3 account keys")
    // The recovered signature verifies against the message.
    assert(be.verify(Curve.Ed25519, pubBytes, messageBytes, sig, HashAlgo.None))
  }

  test("describe summarises a built tx") {
    val ctx: ChainContext = new ChainContext:
      def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
        Future.successful(ujson.Obj("value" -> ujson.Obj("blockhash" -> ujson.Str(blockhashStr))))
      def nowSeconds: Long = 0L
    val tx = Await.result(
      adapter.buildTransaction(TxIntent.NativeTransfer(recipient, BigInt(1)), sender, ctx),
      5.seconds,
    )
    val d = adapter.describe(tx)
    assert(d.fields("numAccountKeys") == "3")
    assert(d.fields("numInstructions") == "1")
    assert(d.fields("recentBlockhash") == blockhashStr)
  }

  // ── unsupported intents still error cleanly ──────────────────────────

  test("buildTransaction(TokenTransfer) errors with the deferred message") {
    val ctx: ChainContext = new ChainContext:
      def rpcCall(method: String, params: ujson.Value*) = Future.failed(new RuntimeException(s"$method"))
      def nowSeconds: Long = 0L
    val usdc   = Asset(SolanaChainAdapter.Mainnet, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDC", 6)
    val intent = TxIntent.TokenTransfer(usdc, recipient, BigInt(1_000_000))
    val ex = intercept[Throwable] {
      Await.result(adapter.buildTransaction(intent, sender, ctx), 5.seconds)
    }
    // The Future-wrapped NotImplementedError surfaces as the cause.
    val cause = Option(ex.getCause).getOrElse(ex)
    assert(cause.getMessage.toLowerCase.contains("spl"))
  }

  // ── util ──────────────────────────────────────────────────────────────

  private def hex(s: String): Array[Byte] =
    val out = new Array[Byte](s.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out
