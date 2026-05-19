package scalascript.blockchain.evm

import scalascript.blockchain.spi.TypedData

/** ERC-3009 `TransferWithAuthorization` typed-data helper.
 *
 *  Constructs the canonical `TypedData.Eip712` that USDC and other
 *  ERC-3009-compatible tokens accept for off-chain transfer
 *  authorizations. Consumed by:
 *
 *    - x402-client: signs the digest via `EoaStrategy.signTypedData`
 *    - x402-facilitator-evm: hashes the digest and calls
 *      `ChainAdapter.recoverAddress` to validate signer = `from`
 *
 *  Both sides MUST construct identical TypedData for the same
 *  authorization or signatures will not verify; this helper is the
 *  single source of that construction. */
object Eip3009:

  /** Canonical EIP-712 types for ERC-3009 TransferWithAuthorization. */
  val Types: Map[String, Seq[(String, String)]] = Map(
    "EIP712Domain" -> Seq(
      "string"  -> "name",
      "string"  -> "version",
      "uint256" -> "chainId",
      "address" -> "verifyingContract",
    ),
    "TransferWithAuthorization" -> Seq(
      "address" -> "from",
      "address" -> "to",
      "uint256" -> "value",
      "uint256" -> "validAfter",
      "uint256" -> "validBefore",
      "bytes32" -> "nonce",
    ),
  )

  /** Build the typed data for a TransferWithAuthorization. The domain
   *  matches USDC's deployment-time configuration: `name = "USD Coin"`,
   *  `version = "2"`. Caller-supplied alternatives exist for tokens
   *  with non-default names/versions. */
  def transferWithAuthorization(
    tokenAddress:      String,
    tokenName:         String,
    tokenVersion:      String,
    chainId:           Int,
    from:              String,
    to:                String,
    value:             BigInt,
    validAfter:        BigInt,
    validBefore:       BigInt,
    nonceHex:          String,
  ): TypedData.Eip712 =
    TypedData.Eip712(
      primaryType = "TransferWithAuthorization",
      domain = Map(
        "name"              -> ujson.Str(tokenName),
        "version"           -> ujson.Str(tokenVersion),
        "chainId"           -> ujson.Str(chainId.toString),
        "verifyingContract" -> ujson.Str(tokenAddress),
      ),
      types = Types,
      value = Map(
        "from"        -> ujson.Str(from),
        "to"          -> ujson.Str(to),
        "value"       -> ujson.Str(value.toString),
        "validAfter"  -> ujson.Str(validAfter.toString),
        "validBefore" -> ujson.Str(validBefore.toString),
        "nonce"       -> ujson.Str(nonceHex),
      ),
    )

  /** USDC defaults: name = "USD Coin", version = "2". */
  def usdcTransferWithAuthorization(
    tokenAddress: String,
    chainId:      Int,
    from:         String,
    to:           String,
    value:        BigInt,
    validAfter:   BigInt,
    validBefore:  BigInt,
    nonceHex:     String,
  ): TypedData.Eip712 =
    transferWithAuthorization(
      tokenAddress = tokenAddress,
      tokenName    = "USD Coin",
      tokenVersion = "2",
      chainId      = chainId,
      from         = from,
      to           = to,
      value        = value,
      validAfter   = validAfter,
      validBefore  = validBefore,
      nonceHex     = nonceHex,
    )
