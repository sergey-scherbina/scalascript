package scalascript.wallet.connector.walletstd

import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.spi.{ChainAdapter, ChainContext, ChainId}
import scalascript.wallet.spi.*

/** Solana Wallet Standard translator (Phase 5).
 *
 *  Implements the request surface of `@wallet-standard/core` —
 *  `standard:connect`, `standard:disconnect`, `solana:signMessage`,
 *  `solana:signTransaction`, `solana:signAndSendTransaction` — in
 *  cross-compiled Scala.  The Scala.js variant exposes
 *  `window.standard.wallets.registerWallet`-shaped glue via
 *  `wallet-connector-wallet-std/js/`.
 *
 *  Byte-array fields on the wire are **base64** strings (matching the
 *  Wallet Standard's `Uint8Array` JSON convention).  Addresses are
 *  base58 ed25519 public keys.
 *
 *  Anything outside the supported feature set surfaces as
 *  `-32601 method not found`.  SIWS (`solana:signIn`) is deliberately
 *  out of scope here — a follow-up slice introduces it once the SIWS
 *  message layout is wired into `TypedData`.
 *
 *  The `signTransaction` / `signAndSendTransaction` features need a
 *  platform-specific bridge between our shared [[SolanaMessage]] and
 *  whatever `ChainAdapter.Tx` / `ChainAdapter.SignedTx` the host
 *  configured (on JVM that is `scalascript.blockchain.solana.SolanaTx`
 *  / `SolanaSignedTx`; on JS no Solana adapter ships yet so a stub
 *  reports `-32601`).  Subclasses override [[buildSolanaTx]] and
 *  [[extractSignedRawBase64]]. */
abstract class WalletStandardConnectorBase(
  val manager:   AccountManager,
  ctxFor:        ChainId => ChainContext,
  initialChain:  ChainId,
)(using ec: ExecutionContext) extends DappConnector:

  import WalletStandardConnectorBase.*

  def protocol: String = "wallet-standard"

  @volatile private var attached:    Boolean = false
  @volatile private var activeChain: ChainId = initialChain

  def attach(unused: AccountManager): Unit = attached = true
  def detach(): Unit = attached = false

  /** Entry point: a dApp invokes a `<namespace>:<method>` feature. */
  def request(feature: String, input: ujson.Value = ujson.Obj()): Future[ujson.Value] =
    if !attached then return Future.failed(error(Disconnected, "wallet detached"))
    feature match
      case "standard:connect"               => handleConnect(input)
      case "standard:disconnect"            => handleDisconnect()
      case "solana:signMessage"             => handleSignMessage(input)
      case "solana:signTransaction"         => handleSignTransaction(input)
      case "solana:signAndSendTransaction"  => handleSignAndSend(input)
      case "wallet:setActiveChain"          => handleSetActiveChain(input)
      case other =>
        Future.failed(error(MethodNotFound, s"unsupported wallet-standard feature: $other"))

  // ── platform bridge ───────────────────────────────────────────────────

  /** Wrap a shared [[SolanaMessage]] into the platform-specific Tx
   *  type the `adapter.signTransaction` / `adapter.broadcast` path
   *  expects.  Return value is `adapter.Tx`; cross-platform code
   *  receives it as `Any` to avoid leaking the JVM-only Solana type
   *  into the shared trait surface. */
  protected def buildSolanaTx(adapter: ChainAdapter, msg: SolanaMessage): Any

  /** Extract the base64 wire-form of a platform-specific SignedTx
   *  (i.e. unpack the rawBase64 field of `SolanaSignedTx` on JVM). */
  protected def extractSignedRawBase64(signed: Any): String

  // ── feature handlers ──────────────────────────────────────────────────

  private def handleConnect(input: ujson.Value): Future[ujson.Value] =
    val requestedChains = (input.objOpt.flatMap(_.get("chains")).toSeq.flatMap {
      case ujson.Arr(xs) => xs.toSeq.map(_.str)
      case _             => Seq.empty
    }).map(ChainId.apply)
    // If the dApp narrowed to specific chains we honour the intersection;
    // otherwise we report every chain the manager owns.  The active chain
    // is unchanged — the dApp can flip it with `wallet:setActiveChain`.
    val chains = if requestedChains.isEmpty then manager.chains.toSeq
                 else requestedChains.filter(manager.chains.contains)
    accountsFor(chains).map(arr => ujson.Obj("accounts" -> ujson.Arr.from(arr)))

  private def handleDisconnect(): Future[ujson.Value] =
    // Wallet Standard `disconnect` is purely a signal — the connector
    // stays attached, but the dApp has marked the session as ended.
    // We don't keep per-session state here, so it's a no-op response.
    Future.successful(ujson.Null)

  private def handleSignMessage(input: ujson.Value): Future[ujson.Value] =
    val obj      = input.obj
    val account  = obj("account").obj.get("address").map(_.str)
      .getOrElse(throw error(InvalidParams, "solana:signMessage requires account.address"))
    val msgB64   = obj("message").str
    val msg      = base64Decode(msgB64)
    forActiveSolana(account) { (strategy, adapter) =>
      strategy.signMessage(adapter, msg).map { sig =>
        ujson.Obj(
          // The Wallet Standard SignedMessageOutput shape — the message
          // is echoed back unchanged because signing is non-destructive.
          "signedMessage" -> ujson.Str(base64Encode(msg)),
          "signature"     -> ujson.Str(base64Encode(sig)),
        )
      }
    }

  private def handleSignTransaction(input: ujson.Value): Future[ujson.Value] =
    val obj      = input.obj
    val account  = obj("account").obj.get("address").map(_.str)
      .getOrElse(throw error(InvalidParams, "solana:signTransaction requires account.address"))
    val txBytes  = base64Decode(obj("transaction").str)
    val message  = decodeMessage(txBytes)
      .getOrElse(throw error(InvalidParams, "solana:signTransaction: malformed message bytes"))
    forActiveSolana(account) { (strategy, adapter) =>
      val tx = buildSolanaTx(adapter, message)
      strategy.signTransaction(adapter)(tx.asInstanceOf[adapter.Tx]).map { signed =>
        ujson.Obj(
          "signedTransaction" -> ujson.Str(extractSignedRawBase64(signed)),
        )
      }
    }

  private def handleSignAndSend(input: ujson.Value): Future[ujson.Value] =
    val obj      = input.obj
    val account  = obj("account").obj.get("address").map(_.str)
      .getOrElse(throw error(InvalidParams, "solana:signAndSendTransaction requires account.address"))
    val txBytes  = base64Decode(obj("transaction").str)
    val message  = decodeMessage(txBytes)
      .getOrElse(throw error(InvalidParams,
        "solana:signAndSendTransaction: malformed message bytes"))
    forActiveSolana(account) { (strategy, adapter) =>
      val tx  = buildSolanaTx(adapter, message)
      val ctx = ctxFor(activeChain)
      for
        signed <- strategy.signTransaction(adapter)(tx.asInstanceOf[adapter.Tx])
        hash   <- adapter.broadcast(signed.asInstanceOf[adapter.SignedTx], ctx)
      yield ujson.Obj("signature" -> ujson.Str(hash.value))
    }

  private def handleSetActiveChain(input: ujson.Value): Future[ujson.Value] =
    // Helper extension over the canonical Wallet Standard surface —
    // there's no standard feature for chain-flipping, but it's the
    // natural counterpart to `wallet_switchEthereumChain` in EIP-1193.
    val id = input.obj("chain").str
    val cid = ChainId(id)
    if !manager.chains.contains(cid) then
      Future.failed(error(ChainDisconnected, s"wallet has no adapter for $cid"))
    else
      activeChain = cid
      Future.successful(ujson.Null)

  // ── helpers ───────────────────────────────────────────────────────────

  private def accountsFor(chains: Seq[ChainId]): Future[Seq[ujson.Value]] =
    Future.sequence(chains.map { c =>
      (manager.strategyFor(c), manager.adapterFor(c)) match
        case (Some(s), Some(a)) =>
          s.getAddress(a).map { addr =>
            Some(ujson.Obj(
              "address"   -> ujson.Str(addr),
              // Wallet Standard wants the raw public key (base64) — for
              // Solana that's the same 32 bytes as the address, just
              // base64 instead of base58.
              "publicKey" -> ujson.Str(base64Encode(Base58.decode(addr))),
              "chains"    -> ujson.Arr(ujson.Str(c.toString)),
              "features"  -> ujson.Arr(
                ujson.Str("solana:signMessage"),
                ujson.Str("solana:signTransaction"),
                ujson.Str("solana:signAndSendTransaction"),
              ),
            ))
          }
        case _ =>
          Future.successful(None)
    }).map(_.flatten)

  /** Resolve the active-chain strategy + adapter as a `Future` (so
   *  the caller can flatMap through it cleanly).  The account address
   *  is informational here — `EoaStrategy` will sign with whatever
   *  key it owns, and `mcp-wallet`'s policy layer is responsible for
   *  matching the request to the right account. */
  private def forActiveSolana[T](address: String)(
    body: (AccountStrategy, ChainAdapter) => Future[T],
  ): Future[T] =
    val _ = address    // informational; policy layer enforces address↔key binding
    (manager.strategyFor(activeChain), manager.adapterFor(activeChain)) match
      case (Some(s), Some(a)) =>
        if a.chainId.namespace != "solana" then
          Future.failed(error(InvalidParams,
            s"solana:* features require a solana namespace, got ${a.chainId}"))
        else
          body(s, a).asInstanceOf[Future[T]]
      case _ =>
        Future.failed(error(Unauthorised, s"no wallet bound to $activeChain"))

object WalletStandardConnectorBase:

  // Wallet Standard reuses the JSON-RPC numeric codes for transport
  // errors, plus a wallet-specific block for connector-layer failures.
  // We surface them as plain RuntimeException so the integrating host
  // (mcp-wallet, a gRPC bridge, …) can pattern-match.
  private[walletstd] val MethodNotFound:    Int = -32601
  private[walletstd] val InvalidParams:     Int = -32602
  private[walletstd] val Disconnected:      Int = 4900   // EIP-1193 parity
  private[walletstd] val ChainDisconnected: Int = 4901
  private[walletstd] val Unauthorised:      Int = 4100

  case class WsError(code: Int, message: String) extends RuntimeException(message)

  private[walletstd] def error(code: Int, message: String): WsError = WsError(code, message)

  /** Decode the legacy Solana transaction-message wire form.  Returns
   *  `None` on any framing error — the connector reports it as
   *  `-32602 invalid params`. */
  private[walletstd] def decodeMessage(bytes: Array[Byte]): Option[SolanaMessage] =
    try
      val r = new ByteReader(bytes)
      val numReqSigs = r.readU8()
      val numRoSigs  = r.readU8()
      val numRoUnsig = r.readU8()
      val keyCount   = r.readCompactU16()
      val keys = (0 until keyCount).map(_ => r.readBytes(32))
      val blockhash  = r.readBytes(32)
      val ixCount    = r.readCompactU16()
      val ixs = (0 until ixCount).map { _ =>
        val progIdx = r.readU8()
        val accCount = r.readCompactU16()
        val accIdx   = r.readBytes(accCount)
        val dataLen  = r.readCompactU16()
        val data     = r.readBytes(dataLen)
        SolanaInstruction(progIdx, accIdx, data)
      }
      Some(SolanaMessage(numReqSigs, numRoSigs, numRoUnsig, keys, blockhash, ixs))
    catch case _: Throwable => None

  private final class ByteReader(buf: Array[Byte]):
    private var pos = 0
    def readU8(): Int =
      require(pos < buf.length, "buffer underflow")
      val v = buf(pos) & 0xff
      pos += 1
      v
    def readBytes(n: Int): Array[Byte] =
      require(pos + n <= buf.length, s"buffer underflow: need $n, have ${buf.length - pos}")
      val out = new Array[Byte](n)
      System.arraycopy(buf, pos, out, 0, n)
      pos += n
      out
    def readCompactU16(): Int =
      // Inverse of CompactU16.encode — 1–3 byte little-endian varint
      // with a top-bit continuation flag.
      var value = 0
      var shift = 0
      var done  = false
      while !done do
        val b = readU8()
        value |= (b & 0x7f) << shift
        if (b & 0x80) == 0 then done = true
        else shift += 7
      value

  private[walletstd] def base64Encode(b: Array[Byte]): String =
    java.util.Base64.getEncoder.encodeToString(b)

  private[walletstd] def base64Decode(s: String): Array[Byte] =
    java.util.Base64.getDecoder.decode(s)
