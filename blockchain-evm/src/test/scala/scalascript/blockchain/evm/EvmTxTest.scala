package scalascript.blockchain.evm

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, CryptoBackend, HashAlgo, PublicKey}

/** End-to-end tests for the Phase 2 write-side: buildTransaction →
 *  prepareSigningPayload → assembleSignedTransaction → broadcast.
 *
 *  No live RPC — uses a stub `ChainContext` that records JSON-RPC
 *  calls and returns canned responses. Real on-chain integration
 *  arrives in Slice D against an Anvil node. */
class EvmTxTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val adapter = new EvmChainAdapter(ChainId.BaseSepolia)
  private val be      = CryptoBackend.get()

  // Vitalik's well-known 0x4646…46 private key from the EIP-712 example.
  private val priv = hex("4646464646464646464646464646464646464646464646464646464646464646")
  private val pub  = PublicKey(Curve.Secp256k1, be.derivePublic(Curve.Secp256k1, priv))
  private val addr = adapter.addressFromPublicKey(pub)

  /** ChainContext stub: returns canned values per RPC method.
   *  Recorded params are useful for assertions when needed. */
  private def stubCtx(replies: Map[String, ujson.Value]): ChainContext = new ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      replies.get(method) match
        case Some(v) => Future.successful(v)
        case None    => Future.failed(new RuntimeException(s"Unmocked RPC: $method"))
    def nowSeconds: Long = 1700000000L

  private val standardReplies: Map[String, ujson.Value] = Map(
    "eth_getTransactionCount" -> ujson.Str("0x5"),
    "eth_estimateGas"         -> ujson.Str("0x5208"),       // 21000
    "eth_maxPriorityFeePerGas" -> ujson.Str("0x77359400"),  // 2 gwei
    "eth_getBlockByNumber"    -> ujson.Obj("baseFeePerGas" -> ujson.Str("0x77359400")),
    "eth_sendRawTransaction"  -> ujson.Str("0xabc123"),
  )

  test("buildTransaction(NativeTransfer) produces an EIP-1559 EvmTx with correct fields") {
    val ctx = stubCtx(standardReplies)
    val tx  = Await.result(
      adapter.buildTransaction(
        TxIntent.NativeTransfer(to = "0x1111111111111111111111111111111111111111", amount = BigInt(1_000_000_000_000_000_000L)),
        addr,
        ctx,
      ),
      5.seconds,
    )
    assert(tx.chainId  == BigInt(84532))
    assert(tx.nonce    == BigInt(5))
    assert(tx.gasLimit == BigInt(21000) + BigInt(2100)) // 10% margin
    assert(tx.to.contains("0x1111111111111111111111111111111111111111"))
    assert(tx.value    == BigInt(1_000_000_000_000_000_000L))
    assert(tx.data.isEmpty)
  }

  test("buildTransaction(TokenTransfer) embeds ERC-20 transfer calldata") {
    val ctx   = stubCtx(standardReplies)
    val asset = Asset(ChainId.BaseSepolia, "0x036CbD53842c5426634e7929541eC2318f3dCF7e", "USDC", 6)
    val tx    = Await.result(
      adapter.buildTransaction(
        TxIntent.TokenTransfer(asset, "0x2222222222222222222222222222222222222222", BigInt(1_000_000)),
        addr, ctx,
      ),
      5.seconds,
    )
    assert(tx.to.contains(asset.address))
    assert(tx.value == BigInt(0))
    // calldata = 0xa9059cbb || pad32(to) || pad32(amount)
    val expected =
      hex("a9059cbb000000000000000000000000222222222222222222222222222222222222222200000000000000000000000000000000000000000000000000000000000f4240")
    assert(tx.data.sameElements(expected))
  }

  test("sign + verify round-trip on the unsigned tx envelope") {
    val ctx = stubCtx(standardReplies)
    val tx  = Await.result(
      adapter.buildTransaction(
        TxIntent.NativeTransfer("0x1111111111111111111111111111111111111111", BigInt(123)),
        addr, ctx,
      ),
      5.seconds,
    )
    val payload = adapter.prepareSigningPayload(tx, pub)
    val sig     = be.sign(Curve.Secp256k1, priv, payload.bytes, HashAlgo.None)
    assert(sig.length == 65)
    val signed  = adapter.assembleSignedTransaction(tx, sig, pub)
    // Verify the signature recovers our address back from the sighash.
    val recovered = adapter.recoverAddress(payload.bytes, sig)
    assert(recovered.exists(_.equalsIgnoreCase(addr)))
    // Signed envelope starts with the EIP-1559 type byte 0x02.
    val raw = signed.rawHex.stripPrefix("0x")
    assert(raw.startsWith("02"))
  }

  test("broadcast posts the signed raw hex via eth_sendRawTransaction") {
    var captured: Option[ujson.Value] = None
    val ctx = new ChainContext:
      def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
        if method == "eth_sendRawTransaction" then
          captured = Some(params.head)
          Future.successful(ujson.Str("0xdeadbeef"))
        else standardReplies.get(method) match
          case Some(v) => Future.successful(v)
          case None    => Future.failed(new RuntimeException(s"Unmocked RPC: $method"))
      def nowSeconds: Long = 0L

    val tx     = Await.result(
      adapter.buildTransaction(
        TxIntent.NativeTransfer("0x1111111111111111111111111111111111111111", BigInt(1)),
        addr, ctx,
      ),
      5.seconds,
    )
    val sig    = be.sign(Curve.Secp256k1, priv, tx.sighash, HashAlgo.None)
    val signed = adapter.assembleSignedTransaction(tx, sig, pub)
    val hash   = Await.result(adapter.broadcast(signed, ctx), 5.seconds)

    assert(hash.value == "0xdeadbeef")
    assert(captured.isDefined)
    // The serialised tx we sent must start with the EIP-1559 type byte.
    val sent = captured.get.str
    assert(sent.startsWith("0x02"))
    assert(sent == signed.rawHex)
  }

  test("predictDeployAddress matches CREATE formula") {
    // Known reference: sender = 0x6ac7ea33f8831ea9dcc53393aaa88b25a785dbf0, nonce = 0
    //   keccak256(rlp([sender, 0]))[12..32]
    //   = 0xcd234a471b72ba2f1ccf0a70fcaba648a5eecd8d
    val ctx = stubCtx(Map(
      "eth_getTransactionCount" -> ujson.Str("0x0"),
    ))
    val predicted = Await.result(
      adapter.predictDeployAddress(
        TxIntent.Deploy(bytecode = Array.emptyByteArray),
        deployer = "0x6ac7ea33f8831ea9dcc53393aaa88b25a785dbf0",
        ctx,
      ),
      5.seconds,
    )
    assert(predicted.equalsIgnoreCase("0xcd234a471b72ba2f1ccf0a70fcaba648a5eecd8d"))
  }

  // ── util ──────────────────────────────────────────────────────────────

  private def hex(s: String): Array[Byte] =
    val clean = s.stripPrefix("0x")
    val out = new Array[Byte](clean.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out
