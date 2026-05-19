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

  // ── transactions ──────────────────────────────────────────────────────

  /** System Program address (32 zero bytes, base58
   *  "11111111111111111111111111111111"). */
  private val SystemProgramKey: Array[Byte] = new Array[Byte](32)

  def buildTransaction(intent: TxIntent, sender: String, ctx: ChainContext): Future[Tx] =
    intent match
      case TxIntent.NativeTransfer(to, lamports) =>
        buildNativeTransfer(sender, to, lamports, ctx)
      case TxIntent.TokenTransfer(_, _, _) =>
        Future.failed(new NotImplementedError(
          "SolanaChainAdapter SPL Token transfers land in a later slice (need ATA derivation)",
        ))
      case TxIntent.ContractCall(_, _, _) =>
        Future.failed(new NotImplementedError(
          "Solana program calls (TxIntent.InvokeProgram) land in a later slice",
        ))
      case other =>
        Future.failed(new NotImplementedError(s"Solana buildTransaction does not yet support $other"))

  /** Build a legacy SOL-transfer message via the System Program's
   *  `transfer` instruction (instruction index = 2). The fee payer
   *  is `sender`; the message becomes a signing payload for the
   *  caller's RawSigner. */
  private def buildNativeTransfer(sender: String, to: String, lamports: BigInt, ctx: ChainContext): Future[Tx] =
    recentBlockhash(ctx).map { hash =>
      val senderKey    = Base58.decode(sender)
      val recipientKey = Base58.decode(to)
      require(senderKey.length == 32 && recipientKey.length == 32, "Solana addresses must be 32 bytes")
      // Account-keys order:
      //   [0] signer-writable (fee payer + sender)
      //   [1] non-signer-writable (recipient)
      //   [2] non-signer-readonly (System Program)
      val accountKeys = Seq(senderKey, recipientKey, SystemProgramKey)
      val instruction = SolanaInstruction(
        programIdIndex = 2,                               // SystemProgram at index 2
        accountIndexes = Array[Byte](0, 1),               // [from=0, to=1]
        data           = systemTransferData(lamports),
      )
      val message = SolanaMessage(
        numRequiredSignatures        = 1,
        numReadonlySignedAccounts    = 0,
        numReadonlyUnsignedAccounts  = 1,
        accountKeys                  = accountKeys,
        recentBlockhash              = hash,
        instructions                 = Seq(instruction),
      )
      SolanaTx(message)
    }

  /** Solana System Program `transfer` instruction body:
   *      [u32 LE instruction = 2] || [u64 LE lamports]. */
  private def systemTransferData(lamports: BigInt): Array[Byte] =
    require(lamports.signum >= 0, s"lamports must be non-negative: $lamports")
    require(lamports <= BigInt("18446744073709551615"), s"lamports overflows u64: $lamports")
    val out = new Array[Byte](12)
    out(0) = 2          // u32 LE instruction index
    var v = lamports
    var i = 4
    while i < 12 do
      out(i) = (v & BigInt(0xff)).toByte
      v = v >> 8
      i += 1
    out

  /** Fetch the most recent finalized blockhash from the network. */
  private def recentBlockhash(ctx: ChainContext): Future[Array[Byte]] =
    ctx.rpcCall("getLatestBlockhash").map { v =>
      val hashStr = v.obj.get("value").map(_.obj("blockhash").str)
        .getOrElse(v.obj("blockhash").str)
      val bytes = Base58.decode(hashStr)
      require(bytes.length == 32, s"recent blockhash must be 32 bytes, got ${bytes.length}")
      bytes
    }

  /** Solana signs the message body directly — there's no separate
   *  domain-separator or message prefix. The signer is ed25519
   *  which hashes internally. */
  def prepareSigningPayload(tx: Tx, signer: PublicKey): SigningPayload =
    SigningPayload(tx.message.serialize, scalascript.crypto.HashAlgo.None)

  /** Assemble the wire form:
   *      compact-array of signatures || serialized message.
   *  For single-signature txs the prefix is `[1, sig(64)]`. */
  def assembleSignedTransaction(tx: Tx, signature: Array[Byte], signer: PublicKey): SignedTx =
    require(signature.length == 64, s"Solana signature must be 64 bytes, got ${signature.length}")
    require(signer.curve == scalascript.crypto.Curve.Ed25519, s"Solana signer must be Ed25519")
    val out = new java.io.ByteArrayOutputStream()
    out.write(CompactU16.encode(1))
    out.write(signature)
    out.write(tx.message.serialize)
    val raw = out.toByteArray
    // Tx hash on Solana is the first signature, base58-encoded.
    SolanaSignedTx(
      rawBase64 = java.util.Base64.getEncoder.encodeToString(raw),
      hash      = TxHash(Base58.encode(signature)),
    )

  def broadcast(signed: SignedTx, ctx: ChainContext): Future[TxHash] =
    ctx.rpcCall(
      "sendTransaction",
      ujson.Str(signed.rawBase64),
      ujson.Obj("encoding" -> ujson.Str("base64")),
    ).map { v =>
      val sig = v match
        case ujson.Str(s) => s
        case obj          => obj.obj.get("result").map(_.str).getOrElse(signed.hash.value)
      TxHash(sig)
    }

  def describe(tx: Tx): TxDescription =
    val m  = tx.message
    val to = if m.accountKeys.size >= 2 then Base58.encode(m.accountKeys(1)) else "(unknown)"
    TxDescription(
      summary = s"Solana legacy tx on $chainId — ${m.instructions.size} instruction(s), to $to",
      fields  = Map(
        "chainId"          -> chainId.caip2,
        "numSignatures"    -> m.numRequiredSignatures.toString,
        "numAccountKeys"   -> m.accountKeys.size.toString,
        "numInstructions"  -> m.instructions.size.toString,
        "recentBlockhash"  -> Base58.encode(m.recentBlockhash),
      ),
    )

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

/** A built but unsigned Solana transaction — wraps the message
 *  body the caller's `RawSigner` will sign over. */
case class SolanaTx(message: SolanaMessage)

/** A signed Solana transaction ready for `sendTransaction`. The
 *  raw bytes carry the full wire form (compact-array of
 *  signatures || serialized message), base64-encoded for the
 *  JSON-RPC envelope. */
case class SolanaSignedTx(rawBase64: String, hash: TxHash)
