package scalascript.blockchain.spi

import scala.concurrent.Future
import scalascript.crypto.{Curve, PublicKey}

/** Per-chain abstraction consumed by `wallet-spi`, `x402-*`, and
 *  future indexers / agents. One implementation per chain family —
 *  `EvmChainAdapter` covers all `eip155:*` chains parameterised by
 *  `chainId`, `SolanaChainAdapter` covers `solana:*`, etc.
 *
 *  See docs/specs/blockchain-spi.md §6 for the full contract surface. */
trait ChainAdapter:

  // Path-dependent native representations. Each adapter chooses its own
  // (`EvmTx`, `SolanaTx`, `BtcTx`); generic code flows through the
  // adapter polymorphically.
  type Tx
  type SignedTx

  // ── identity ──────────────────────────────────────────────────────────

  def chainId: ChainId

  def supportedCurves: Seq[Curve]

  /** BIP-44 derivation path used by `EoaStrategy` when no override is
   *  supplied. Examples: `m/44'/60'/0'/0/0` (EVM), `m/44'/501'/0'/0'`
   *  (Solana), `m/44'/0'/0'/0/0` (Bitcoin). */
  def defaultDerivationPath: String

  // ── addresses ─────────────────────────────────────────────────────────

  def addressFromPublicKey(pk: PublicKey): String
  def isValidAddress(s: String): Boolean
  def normalizeAddress(s: String): String

  // ── typed-data hashing ────────────────────────────────────────────────

  /** Compute the digest a `RawSigner` should sign for a TypedData value.
   *  For EVM: `keccak256(0x19 0x01 domainSeparator structHash)`. */
  def typedDataDigest(data: TypedData): Array[Byte]

  // ── signature ↔ signer ────────────────────────────────────────────────

  /** Recover the signer's address from a signature over a digest. For
   *  EVM this is ecrecover (sig is `r || s || v`). Returns None if
   *  recovery fails for any reason (invalid sig, unsupported curve,
   *  malformed input). */
  def recoverAddress(digest: Array[Byte], signature: Array[Byte]): Option[String]

  /** Verify a signature against an expected address. Default
   *  implementation delegates to `recoverAddress`. */
  def verifySignature(digest: Array[Byte], signature: Array[Byte], expected: String): Boolean =
    recoverAddress(digest, signature).exists(_.equalsIgnoreCase(expected))

  // ── transactions ──────────────────────────────────────────────────────

  /** Build an unsigned transaction from a chain-agnostic intent. The
   *  `sender` is the address that will pay gas (and that the nonce is
   *  fetched for); the signing step happens later via
   *  `prepareSigningPayload` / `assembleSignedTransaction`. For EVM
   *  this also lets `eth_estimateGas` see a realistic `from`. */
  def buildTransaction(intent: TxIntent, sender: String, ctx: ChainContext): Future[Tx]

  def prepareSigningPayload(tx: Tx, signer: PublicKey): SigningPayload

  def assembleSignedTransaction(tx: Tx, signature: Array[Byte], signer: PublicKey): SignedTx

  def broadcast(signed: SignedTx, ctx: ChainContext): Future[TxHash]

  def describe(tx: Tx): TxDescription

  // ── queries ───────────────────────────────────────────────────────────

  def nativeBalance(address: String, ctx: ChainContext): Future[BigInt]

  def tokenBalance(asset: Asset, holder: String, ctx: ChainContext): Future[BigInt]

  def nonceOf(address: String, ctx: ChainContext): Future[BigInt]

  def getReceipt(hash: TxHash, ctx: ChainContext): Future[Option[TxReceipt]]

  def waitForReceipt(hash: TxHash, ctx: ChainContext, timeoutMs: Long): Future[TxReceipt]

  // ── contract reads ────────────────────────────────────────────────────

  /** Read-only contract execution. On EVM this is `eth_call`; on
   *  Solana this is a simulated invoke; on Cardano this is script
   *  evaluation. Returns raw bytes — the caller decodes per the
   *  chain's ABI / encoding model. */
  def call(target: String, calldata: Array[Byte], ctx: ChainContext): Future[Array[Byte]]

  /** Predict the address that a `TxIntent.Deploy` would produce.
   *  EVM CREATE: derived from sender + nonce. EVM CREATE2: from
   *  deployer + salt + codehash. Solana: PDA from seeds. */
  def predictDeployAddress(deploy: TxIntent.Deploy, deployer: String, ctx: ChainContext): Future[String]
