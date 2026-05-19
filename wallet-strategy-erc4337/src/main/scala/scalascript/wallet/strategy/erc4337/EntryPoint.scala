package scalascript.wallet.strategy.erc4337

/** EIP-4337 EntryPoint constants. v0.6 is the most-deployed version
 *  across mainnet + L2s as of 2026; v0.7 / v0.8 introduce a
 *  PackedUserOperation wire format that we'll layer on top in a
 *  follow-on slice. */
object EntryPoint:

  /** Canonical EntryPoint v0.6 deployment address — identical on
   *  every EVM chain (Ethereum, Base, Optimism, Arbitrum, Polygon,
   *  …) because it was deployed with CREATE2 from the canonical
   *  deployer. */
  val V06Address: String = "0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789"

  /** EntryPoint v0.7 address (PackedUserOperation; tracked for the
   *  future v0.7 slice). */
  val V07Address: String = "0x0000000071727De22E5E9d8BAf0edAc6f37da032"
