package scalascript.blockchain.solana

/** SPL Token program constants and helpers.
 *
 *  SPL = "Solana Program Library". The SPL Token program is the
 *  Solana-equivalent of Ethereum's ERC-20 — except that token
 *  *balances* don't live on the mint itself, but in per-owner
 *  Token Accounts. By convention each owner has at most one
 *  canonical Token Account per mint — its Associated Token
 *  Account (ATA), a PDA of (owner, token_program, mint) under the
 *  Associated Token Account program. */
private[solana] object SplToken:

  /** Token program v1 — opcodes for legacy Transfer (3),
   *  TransferChecked (12), MintTo (7), Burn (8), etc. Base58:
   *  "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA". */
  val ProgramId: Array[Byte] = Base58.decode("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")

  /** Associated Token Account program — derives a canonical token
   *  account address per (owner, mint). Base58:
   *  "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL". */
  val AssociatedProgramId: Array[Byte] =
    Base58.decode("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")

  /** Derive the Associated Token Account (ATA) for an owner +
   *  mint. The seeds are `[owner, TOKEN_PROGRAM, mint]` and the
   *  derivation program is the Associated Token Account program. */
  def associatedTokenAddress(owner: Array[Byte], mint: Array[Byte]): Array[Byte] =
    require(owner.length == 32, s"owner pubkey must be 32 bytes, got ${owner.length}")
    require(mint.length  == 32, s"mint pubkey must be 32 bytes, got ${mint.length}")
    val (addr, _) = Pda.findProgramAddress(Seq(owner, ProgramId, mint), AssociatedProgramId)
    addr

  /** Build the data payload for a TransferChecked instruction
   *  (opcode 12) — preferred over the legacy `Transfer` (opcode 3)
   *  because it checks the mint and decimal places on-chain,
   *  preventing decimals-confusion bugs. Layout:
   *
   *      [u8 opcode = 12] || [u64 LE amount] || [u8 decimals]. */
  def transferCheckedData(amount: BigInt, decimals: Int): Array[Byte] =
    require(amount.signum >= 0, s"SPL Token amount must be non-negative: $amount")
    require(amount <= BigInt("18446744073709551615"), s"amount overflows u64: $amount")
    require(decimals >= 0 && decimals <= 255, s"decimals out of u8 range: $decimals")
    val out = new Array[Byte](10)
    out(0) = 12
    var v = amount
    var i = 1
    while i < 9 do
      out(i) = (v & BigInt(0xff)).toByte
      v = v >> 8
      i += 1
    out(9) = decimals.toByte
    out
