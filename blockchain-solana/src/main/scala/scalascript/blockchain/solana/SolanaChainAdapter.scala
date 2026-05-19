package scalascript.blockchain.solana

import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, HashAlgo, PublicKey}

/** Solana-side `ChainAdapter`. This slice (Phase 3.1) covers the
 *  read-side of the trait — address derivation, balance queries,
 *  signature verification, generic JSON-RPC pass-through. The
 *  write-side (`buildTransaction` / `broadcast` for SOL transfer
 *  and SPL Token transfer, versioned transactions, Address
 *  Lookup Tables) follows in subsequent slices.
 *
 *  See docs/blockchain-spi.md §6 for the chain-adapter contract. */
class SolanaChainAdapter(val chainId: ChainId)(using ec: ExecutionContext) extends ChainAdapter:

  type Tx       = SolanaTx
  type SignedTx = SolanaSignedTx

  require(
    chainId.namespace == "solana",
    s"SolanaChainAdapter requires solana namespace, got $chainId",
  )

  val supportedCurves: Seq[Curve] = Seq(Curve.Ed25519)

  // SLIP-0010 ed25519 default path; per the Solana ecosystem
  // convention many wallets use `m/44'/501'/0'/0'`.
  val defaultDerivationPath: String = "m/44'/501'/0'/0'"

  // ── addresses ─────────────────────────────────────────────────────────

  def addressFromPublicKey(pk: PublicKey): String =
    require(pk.curve == Curve.Ed25519, s"Solana expects Ed25519 public key, got ${pk.curve}")
    require(pk.bytes.length == 32, s"Solana ed25519 pubkey must be 32 bytes, got ${pk.bytes.length}")
    Base58.encode(pk.bytes)

  /** Solana addresses are 32-byte ed25519 public keys encoded in
   *  base58 — typically 43 or 44 characters. We accept any string
   *  that decodes to exactly 32 bytes. */
  def isValidAddress(s: String): Boolean =
    try
      val bytes = Base58.decode(s)
      bytes.length == 32
    catch case _: Throwable => false

  /** Solana addresses are case-sensitive base58 — no normalisation
   *  beyond a round-trip decode/encode (which strips any extraneous
   *  framing characters). */
  def normalizeAddress(s: String): String =
    val bytes = Base58.decode(s)
    require(bytes.length == 32, s"Not a 32-byte Solana address: $s")
    Base58.encode(bytes)

  // ── typed-data hashing ────────────────────────────────────────────────

  /** Solana doesn't have an EIP-712 equivalent; off-chain message
   *  signing is governed by per-application conventions. For now
   *  the adapter supports `TypedData.Raw` (sign the bytes as-is via
   *  ed25519's internal hashing) and `TypedData.CosmosSignDoc`
   *  (sha-256 then sign — the Solana SignIn-With shape). EIP-712
   *  is explicitly unsupported. */
  def typedDataDigest(data: TypedData): Array[Byte] = data match
    case TypedData.Raw(bytes)           => bytes
    case TypedData.CosmosSignDoc(bytes) =>
      scalascript.crypto.CryptoBackend.get().hash(HashAlgo.Sha256, bytes)
    case other =>
      throw new IllegalArgumentException(s"Solana adapter does not support $other")

  // ── signature ↔ signer ────────────────────────────────────────────────

  /** ed25519 doesn't support public-key recovery in the
   *  ecrecover sense — verifying a Solana signature requires the
   *  signer's public key up front, not the signature alone. The
   *  trait method exists for symmetry with EVM; we return None and
   *  callers fall through to the explicit `verify(pubKey, msg, sig)`
   *  path through CryptoBackend. */
  def recoverAddress(digest: Array[Byte], signature: Array[Byte]): Option[String] = None

  /** Verify a 64-byte ed25519 signature against the expected
   *  signer's base58 address (= the ed25519 public key encoded in
   *  base58). */
  override def verifySignature(digest: Array[Byte], signature: Array[Byte], expectedAddress: String): Boolean =
    if signature.length != 64 then false
    else
      try
        val pubBytes = Base58.decode(expectedAddress)
        if pubBytes.length != 32 then false
        else
          scalascript.crypto.CryptoBackend.get()
            .verify(Curve.Ed25519, pubBytes, digest, signature, HashAlgo.None)
      catch case _: Throwable => false

  // ── transactions (deferred to a later slice) ──────────────────────────

  def buildTransaction(intent: TxIntent, sender: String, ctx: ChainContext): Future[Tx] =
    Future.failed(new NotImplementedError(
      "SolanaChainAdapter.buildTransaction lands in a later slice (NativeTransfer + SPL Token)",
    ))

  def prepareSigningPayload(tx: Tx, signer: PublicKey): SigningPayload =
    throw new NotImplementedError("SolanaChainAdapter.prepareSigningPayload lands later")

  def assembleSignedTransaction(tx: Tx, signature: Array[Byte], signer: PublicKey): SignedTx =
    throw new NotImplementedError("SolanaChainAdapter.assembleSignedTransaction lands later")

  def broadcast(signed: SignedTx, ctx: ChainContext): Future[TxHash] =
    Future.failed(new NotImplementedError("SolanaChainAdapter.broadcast lands later"))

  def describe(tx: Tx): TxDescription =
    throw new NotImplementedError("SolanaChainAdapter.describe lands later")

  // ── queries ───────────────────────────────────────────────────────────

  /** Native SOL balance in lamports (1 SOL = 1_000_000_000 lamports). */
  def nativeBalance(address: String, ctx: ChainContext): Future[BigInt] =
    ctx.rpcCall("getBalance", ujson.Str(address)).map { v =>
      // Standard JSON-RPC v2 envelope shape: { context: ..., value: <lamports> }
      val lamports = v.obj.get("value").orElse(v.obj.get("result")).getOrElse(v).num.toLong
      BigInt(lamports)
    }

  /** SPL Token balance via `getTokenAccountsByOwner` filtered by
   *  mint, summed across all owner accounts for that mint. */
  def tokenBalance(asset: Asset, holder: String, ctx: ChainContext): Future[BigInt] =
    require(asset.chain == chainId, s"Asset chain ${asset.chain} ≠ adapter chain $chainId")
    if asset.isNative then nativeBalance(holder, ctx)
    else
      val params = ujson.Arr(
        ujson.Str(holder),
        ujson.Obj("mint" -> ujson.Str(asset.address)),
        ujson.Obj("encoding" -> ujson.Str("jsonParsed")),
      )
      ctx.rpcCall("getTokenAccountsByOwner", params).map { resp =>
        val accounts = resp.obj.get("value").map(_.arr).getOrElse(resp.arr)
        accounts.foldLeft(BigInt(0)) { (acc, accJson) =>
          val parsed = accJson("account")("data")("parsed")("info")("tokenAmount")
          acc + BigInt(parsed("amount").str)
        }
      }

  def nonceOf(address: String, ctx: ChainContext): Future[BigInt] =
    // Solana doesn't have EVM-style sequential nonces; transactions
    // are deduplicated by their recent blockhash + signature. Return
    // 0 so generic callers don't break; tx builders should use the
    // blockhash mechanism instead.
    Future.successful(BigInt(0))

  def getReceipt(hash: TxHash, ctx: ChainContext): Future[Option[TxReceipt]] =
    ctx.rpcCall("getTransaction", ujson.Str(hash.value), ujson.Obj("encoding" -> ujson.Str("json"))).map {
      case ujson.Null => None
      case obj        =>
        val meta = obj("meta").obj
        val err  = meta.get("err").exists(_ != ujson.Null)
        Some(TxReceipt(
          hash        = hash,
          success     = !err,
          blockNumber = BigInt(obj("slot").num.toLong),
          gasUsed     = BigInt(meta.get("fee").map(_.num.toLong).getOrElse(0L)),
          logs        = Seq.empty, // Solana logs are per-instruction; map later if needed
        ))
    }.recover { case _ => None }

  def waitForReceipt(hash: TxHash, ctx: ChainContext, timeoutMs: Long): Future[TxReceipt] =
    val deadline = System.currentTimeMillis() + timeoutMs
    def loop(): Future[TxReceipt] =
      getReceipt(hash, ctx).flatMap {
        case Some(r) => Future.successful(r)
        case None    =>
          if System.currentTimeMillis() >= deadline then
            Future.failed(new RuntimeException(s"Receipt for $hash not found within ${timeoutMs}ms"))
          else
            Thread.sleep(2000)
            loop()
      }
    Future(loop()).flatten

  // ── contract reads ────────────────────────────────────────────────────

  /** Solana doesn't have a single `eth_call` analog — read-only
   *  execution is `simulateTransaction`, but it requires a full
   *  signed transaction. We expose the simpler getAccountInfo as the
   *  primitive any "read state" caller actually wants. */
  def call(target: String, calldata: Array[Byte], ctx: ChainContext): Future[Array[Byte]] =
    // Convention: treat `calldata` as ignored for getAccountInfo —
    // callers who need full simulateTransaction reach for
    // ChainContext.rpcCall directly.
    ctx.rpcCall(
      "getAccountInfo",
      ujson.Str(target),
      ujson.Obj("encoding" -> ujson.Str("base64")),
    ).map { resp =>
      val data = resp.obj.get("value").flatMap(_.objOpt).flatMap(_.get("data"))
      data match
        case Some(ujson.Arr(items)) if items.nonEmpty =>
          // jsonParsed returns ["<base64>", "base64"]; raw bytes are in items.head
          java.util.Base64.getDecoder.decode(items.head.str)
        case _ =>
          Array.emptyByteArray
    }

  def predictDeployAddress(deploy: TxIntent.Deploy, deployer: String, ctx: ChainContext): Future[String] =
    Future.failed(new NotImplementedError(
      "Solana program deployment + PDA prediction lands in a later slice",
    ))

object SolanaChainAdapter:
  val Mainnet: ChainId = ChainId("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc8AS5w")
  val Devnet:  ChainId = ChainId("solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG")
  val Testnet: ChainId = ChainId("solana:4uhcVJyU9pJkvQyS88uRDiswHXSCkY3z")

  def mainnet(using ExecutionContext): SolanaChainAdapter = new SolanaChainAdapter(Mainnet)
  def devnet(using ExecutionContext):  SolanaChainAdapter = new SolanaChainAdapter(Devnet)
  def testnet(using ExecutionContext): SolanaChainAdapter = new SolanaChainAdapter(Testnet)

/** Placeholder; filled in by later slices. */
case class SolanaTx()

case class SolanaSignedTx(rawBase64: String, hash: TxHash)
