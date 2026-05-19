package scalascript.blockchain.solana

/** Solana legacy transaction message body. Versioned (v0) txs with
 *  address lookup tables are a separate type; this slice ships
 *  only legacy because the simple NativeTransfer flow doesn't need
 *  ALT lookups. */
case class SolanaMessage(
  numRequiredSignatures:        Int,
  numReadonlySignedAccounts:    Int,
  numReadonlyUnsignedAccounts:  Int,
  /** Account keys ordered: writable signers first, then read-only
   *  signers, then writable non-signers, then read-only non-signers
   *  (the System Program is read-only). The first one is the fee
   *  payer. Each key is the 32-byte ed25519 public key. */
  accountKeys:        Seq[Array[Byte]],
  /** 32-byte recent blockhash from `getLatestBlockhash` — Solana's
   *  replay-protection / freshness primitive in lieu of an EVM
   *  nonce. */
  recentBlockhash:    Array[Byte],
  instructions:       Seq[SolanaInstruction],
):
  /** Serialise to the canonical wire format that gets ed25519-signed
   *  and embedded in the eventual transaction. */
  def serialize: Array[Byte] =
    val out = new java.io.ByteArrayOutputStream()
    out.write(numRequiredSignatures)
    out.write(numReadonlySignedAccounts)
    out.write(numReadonlyUnsignedAccounts)
    out.write(CompactU16.encode(accountKeys.size))
    accountKeys.foreach { k =>
      require(k.length == 32, s"account key must be 32 bytes, got ${k.length}")
      out.write(k)
    }
    require(recentBlockhash.length == 32, s"recent blockhash must be 32 bytes")
    out.write(recentBlockhash)
    out.write(CompactU16.encode(instructions.size))
    instructions.foreach { ix =>
      out.write(ix.programIdIndex & 0xff)
      out.write(CompactU16.encode(ix.accountIndexes.length))
      out.write(ix.accountIndexes)
      out.write(CompactU16.encode(ix.data.length))
      out.write(ix.data)
    }
    out.toByteArray

case class SolanaInstruction(
  /** Index into the parent message's `accountKeys` for the program
   *  being invoked. */
  programIdIndex: Int,
  /** Indexes into the parent message's `accountKeys` for the
   *  accounts this instruction reads or writes. */
  accountIndexes: Array[Byte],
  /** Program-specific instruction payload. */
  data:           Array[Byte],
)
