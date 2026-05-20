package scalascript.wallet.connector.walletstd

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.solana.{Base58, SolanaChainAdapter, SolanaMessage}
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, CryptoBackend, HashAlgo, PublicKey}
import scalascript.wallet.spi.*
import scalascript.wallet.strategy.eoa.{EoaStrategy, RawPrivateKeyVault}

/** Coverage for the Solana Wallet Standard connector — exercises the
 *  full request surface (`standard:connect`, `solana:signMessage`,
 *  `solana:signTransaction`, `solana:signAndSendTransaction`,
 *  `wallet:setActiveChain`) against a real EoaStrategy wired to a
 *  SolanaChainAdapter. The transport itself is mocked via a
 *  ChainContext that captures RPC calls. */
class WalletStandardConnectorTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  // RFC 8032 test vector 1 — deterministic ed25519 keypair.
  private val privHex = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
  private val be      = CryptoBackend.get()
  private val priv32  = hex(privHex)
  private val pub32   = be.derivePublic(Curve.Ed25519, priv32)

  private val adapter = new SolanaChainAdapter(SolanaChainAdapter.Mainnet)
  private val address = adapter.addressFromPublicKey(PublicKey(Curve.Ed25519, pub32))

  private val vault    = new RawPrivateKeyVault("solana-test", Map(Curve.Ed25519 -> priv32))
  private val signer   = Await.result(vault.getSigner(Curve.Ed25519, "raw"), 5.seconds)
  private val strategy = new EoaStrategy(signer)

  private def makeManager(@annotation.nowarn ctx: ChainContext): AccountManager = new AccountManager:
    def chains: Set[ChainId]                       = Set(adapter.chainId)
    def strategyFor(c: ChainId): Option[AccountStrategy] =
      if c == adapter.chainId then Some(strategy) else None
    def adapterFor(c: ChainId): Option[ChainAdapter] =
      if c == adapter.chainId then Some(adapter) else None
    def request(req: DappRequest): Future[DappResponse] =
      Future.successful(DappResponse.Error(-32601, "not used in this test"))

  private def mockCtx(handle: (String, Seq[ujson.Value]) => Future[ujson.Value]): ChainContext =
    new ChainContext:
      def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] = handle(method, params)
      def nowSeconds: Long = 0L

  // A minimal but valid legacy Solana message: 1 signer, no read-only
  // signers, 1 read-only non-signer (the System Program), 2 keys,
  // a placeholder recent blockhash, no instructions. Exercises the
  // wire decoder + signing round-trip without needing realistic
  // instruction data.
  private def sampleMessage(): SolanaMessage = SolanaMessage(
    numRequiredSignatures        = 1,
    numReadonlySignedAccounts    = 0,
    numReadonlyUnsignedAccounts  = 1,
    accountKeys                  = Seq(Base58.decode(address), new Array[Byte](32)),
    recentBlockhash              = Array.fill(32)(0x55.toByte),
    instructions                 = Seq.empty,
  )

  // ── connect ───────────────────────────────────────────────────────────

  test("standard:connect returns the wallet's account with publicKey + features") {
    val ctx  = mockCtx { (m, _) => Future.failed(new RuntimeException(s"unmocked: $m")) }
    val conn = newConnector(ctx)
    val out  = Await.result(conn.request("standard:connect", ujson.Obj()), 5.seconds)
    val accs = out.obj("accounts").arr.toSeq
    assert(accs.size == 1)
    val acc = accs.head.obj
    assert(acc("address").str == address)
    // publicKey is base64 of the raw 32-byte ed25519 pubkey
    assert(java.util.Base64.getDecoder.decode(acc("publicKey").str).toSeq == pub32.toSeq)
    val features = acc("features").arr.toSeq.map(_.str)
    assert(features.contains("solana:signMessage"))
    assert(features.contains("solana:signTransaction"))
    assert(features.contains("solana:signAndSendTransaction"))
  }

  test("standard:connect honours requested chains: empty intersection → empty accounts") {
    val ctx  = mockCtx { (m, _) => Future.failed(new RuntimeException(s"unmocked: $m")) }
    val conn = newConnector(ctx)
    val out  = Await.result(conn.request(
      "standard:connect",
      ujson.Obj("chains" -> ujson.Arr(ujson.Str("eip155:1"))),
    ), 5.seconds)
    assert(out.obj("accounts").arr.isEmpty,
      "connect must drop chains the wallet doesn't support")
  }

  // ── signMessage ────────────────────────────────────────────────────────

  test("solana:signMessage produces a valid 64-byte ed25519 signature") {
    val ctx  = mockCtx { (m, _) => Future.failed(new RuntimeException(s"unmocked: $m")) }
    val conn = newConnector(ctx)
    val msg  = "hello solana".getBytes("UTF-8")
    val input = ujson.Obj(
      "account" -> ujson.Obj("address" -> ujson.Str(address)),
      "message" -> ujson.Str(java.util.Base64.getEncoder.encodeToString(msg)),
    )
    val out = Await.result(conn.request("solana:signMessage", input), 5.seconds)
    val sig = java.util.Base64.getDecoder.decode(out.obj("signature").str)
    assert(sig.length == 64)
    // Echoed message is unmodified.
    val echoed = java.util.Base64.getDecoder.decode(out.obj("signedMessage").str)
    assert(echoed.toSeq == msg.toSeq)
    // ed25519 verifies against the wallet's pubkey.
    assert(be.verify(Curve.Ed25519, pub32, msg, sig, HashAlgo.None))
  }

  // ── signTransaction ────────────────────────────────────────────────────

  test("solana:signTransaction returns a wire-form signed tx that re-verifies") {
    val ctx  = mockCtx { (m, _) => Future.failed(new RuntimeException(s"unmocked: $m")) }
    val conn = newConnector(ctx)
    val msg  = sampleMessage()
    val msgB = msg.serialize
    val input = ujson.Obj(
      "account"     -> ujson.Obj("address" -> ujson.Str(address)),
      "transaction" -> ujson.Str(java.util.Base64.getEncoder.encodeToString(msgB)),
    )
    val out  = Await.result(conn.request("solana:signTransaction", input), 5.seconds)
    val signedBytes = java.util.Base64.getDecoder.decode(out.obj("signedTransaction").str)

    // The wire form is [CompactU16(1), sig(64), message(...)] for a
    // single-signer tx.
    assert(signedBytes(0).toInt == 1, "single-signature prefix")
    val sig = java.util.Arrays.copyOfRange(signedBytes, 1, 65)
    val tail = java.util.Arrays.copyOfRange(signedBytes, 65, signedBytes.length)
    assert(tail.toSeq == msgB.toSeq, "trailing bytes must be the serialised message")
    // ed25519 signature verifies against the wallet pubkey + message.
    assert(be.verify(Curve.Ed25519, pub32, msgB, sig, HashAlgo.None))
  }

  test("solana:signTransaction rejects malformed message bytes") {
    val ctx  = mockCtx { (m, _) => Future.failed(new RuntimeException(s"unmocked: $m")) }
    val conn = newConnector(ctx)
    val input = ujson.Obj(
      "account"     -> ujson.Obj("address" -> ujson.Str(address)),
      "transaction" -> ujson.Str("AAAA"),  // 3 bytes — far short of a message
    )
    val ex = intercept[Throwable] {
      Await.result(conn.request("solana:signTransaction", input), 5.seconds)
    }
    val msgChain = unwrap(ex)
    assert(msgChain.contains("malformed") || msgChain.contains("buffer underflow"),
      s"expected malformed/underflow error, got: $msgChain")
  }

  // ── signAndSendTransaction ─────────────────────────────────────────────

  test("solana:signAndSendTransaction posts to sendTransaction and returns the on-chain signature") {
    var lastCall: Option[(String, Seq[ujson.Value])] = None
    val ctx = mockCtx { (m, params) =>
      m match
        case "sendTransaction" =>
          lastCall = Some((m, params))
          Future.successful(ujson.Str("3J98t1WpEZ73CNm"))   // synthetic sig
        case other =>
          Future.failed(new RuntimeException(s"unmocked: $other"))
    }
    val conn = newConnector(ctx)
    val msg  = sampleMessage()
    val input = ujson.Obj(
      "account"     -> ujson.Obj("address" -> ujson.Str(address)),
      "transaction" -> ujson.Str(java.util.Base64.getEncoder.encodeToString(msg.serialize)),
    )
    val out = Await.result(conn.request("solana:signAndSendTransaction", input), 5.seconds)
    assert(out.obj("signature").str == "3J98t1WpEZ73CNm")

    val (method, params) = lastCall.getOrElse(fail("sendTransaction was not invoked"))
    assert(method == "sendTransaction")
    assert(params.head.isInstanceOf[ujson.Str], "first param is the base64 raw tx")
    assert(params(1).obj("encoding").str == "base64")
  }

  // ── chain switching + unsupported features ─────────────────────────────

  test("wallet:setActiveChain rejects chains the wallet doesn't speak") {
    val ctx  = mockCtx { (m, _) => Future.failed(new RuntimeException(s"unmocked: $m")) }
    val conn = newConnector(ctx)
    val ex = intercept[Throwable] {
      Await.result(
        conn.request("wallet:setActiveChain", ujson.Obj("chain" -> ujson.Str("eip155:1"))),
        5.seconds,
      )
    }
    val msg = unwrap(ex)
    assert(msg.toLowerCase.contains("no adapter"))
  }

  test("unknown feature surfaces as -32601") {
    val ctx  = mockCtx { (m, _) => Future.failed(new RuntimeException(s"unmocked: $m")) }
    val conn = newConnector(ctx)
    val ex = intercept[WalletStandardConnector.WsError] {
      Await.result(conn.request("solana:signIn", ujson.Obj()), 5.seconds)
    }
    assert(ex.code == -32601, s"expected method-not-found, got ${ex.code}")
  }

  test("solana:* features rejected when the active chain isn't a Solana namespace") {
    // Wire a non-Solana adapter into the manager and flip activeChain
    // before requesting a solana:* feature.
    val nonSolanaChain = ChainId("eip155:1")
    val ctx = mockCtx { (m, _) => Future.failed(new RuntimeException(s"unmocked: $m")) }
    val mgr = new AccountManager:
      def chains: Set[ChainId] = Set(adapter.chainId, nonSolanaChain)
      def strategyFor(c: ChainId): Option[AccountStrategy] = Some(strategy)
      def adapterFor(c: ChainId): Option[ChainAdapter] =
        if c == adapter.chainId then Some(adapter)
        else Some(new ChainAdapter:
          type Tx       = Array[Byte]
          type SignedTx = Array[Byte]
          def chainId: ChainId = nonSolanaChain
          def supportedCurves: Seq[Curve] = Seq(Curve.Secp256k1)
          def defaultDerivationPath: String = ""
          def addressFromPublicKey(pk: PublicKey): String = ""
          def isValidAddress(s: String): Boolean = true
          def normalizeAddress(s: String): String = s
          def typedDataDigest(d: TypedData): Array[Byte] = Array.emptyByteArray
          def recoverAddress(d: Array[Byte], s: Array[Byte]): Option[String] = None
          def nativeBalance(a: String, c: ChainContext): Future[BigInt] = Future.successful(BigInt(0))
          def tokenBalance(a: Asset, h: String, c: ChainContext): Future[BigInt] = Future.successful(BigInt(0))
          def nonceOf(a: String, c: ChainContext): Future[BigInt] = Future.successful(BigInt(0))
          def call(target: String, calldata: Array[Byte], c: ChainContext): Future[Array[Byte]] =
            Future.successful(Array.emptyByteArray)
          def buildTransaction(intent: TxIntent, sender: String, c: ChainContext): Future[Tx] =
            Future.successful(Array.emptyByteArray)
          def prepareSigningPayload(tx: Tx, signer: PublicKey): SigningPayload =
            SigningPayload(Array.emptyByteArray, HashAlgo.None)
          def assembleSignedTransaction(tx: Tx, signature: Array[Byte], signer: PublicKey): SignedTx =
            Array.emptyByteArray
          def broadcast(signed: SignedTx, c: ChainContext): Future[TxHash] = Future.successful(TxHash("0x"))
          def describe(tx: Tx): TxDescription = TxDescription("", Map.empty)
          def getReceipt(h: TxHash, c: ChainContext): Future[Option[TxReceipt]] = Future.successful(None)
          def waitForReceipt(h: TxHash, c: ChainContext, t: Long): Future[TxReceipt] =
            Future.failed(new RuntimeException("not used"))
          def predictDeployAddress(deploy: TxIntent.Deploy, deployer: String, c: ChainContext): Future[String] =
            Future.successful(""))
      def request(req: DappRequest): Future[DappResponse] =
        Future.successful(DappResponse.Error(-32601, "not used"))
    val conn = new WalletStandardConnector(mgr, _ => ctx, nonSolanaChain)
    conn.attach(mgr)
    val ex = intercept[WalletStandardConnector.WsError] {
      Await.result(
        conn.request("solana:signMessage", ujson.Obj(
          "account" -> ujson.Obj("address" -> ujson.Str(address)),
          "message" -> ujson.Str("aGk="),
        )),
        5.seconds,
      )
    }
    assert(ex.code == -32602, s"expected invalid params, got ${ex.code}")
    assert(ex.message.toLowerCase.contains("solana namespace"))
  }

  // ── helpers ───────────────────────────────────────────────────────────

  private def newConnector(ctx: ChainContext): WalletStandardConnector =
    val mgr  = makeManager(ctx)
    val conn = new WalletStandardConnector(mgr, _ => ctx, adapter.chainId)
    conn.attach(mgr)
    conn

  private def unwrap(ex: Throwable): String =
    var cur: Throwable = ex
    while cur.getCause != null do cur = cur.getCause
    Option(cur.getMessage).getOrElse("")

  private def hex(s: String): Array[Byte] =
    val clean = s.stripPrefix("0x")
    require(clean.length % 2 == 0)
    val out = new Array[Byte](clean.length / 2)
    var i = 0
    while i < out.length do
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out
