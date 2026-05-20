package scalascript.wallet.strategy.erc4337

/** EIP-4337 EntryPoint constants. v0.6 is the most widely deployed
 *  version across mainnet + L2s as of 2026 and remains the default;
 *  v0.7 introduces the `PackedUserOperation` wire format (compressed
 *  gas + paymaster fields, split factory/factoryData on RPC) and
 *  ships under a separate canonical address. */
object EntryPoint:

  /** Canonical EntryPoint v0.6 deployment address — identical on
   *  every EVM chain (Ethereum, Base, Optimism, Arbitrum, Polygon,
   *  …) because it was deployed with CREATE2 from the canonical
   *  deployer. */
  val V06Address: String = "0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789"

  /** Canonical EntryPoint v0.7 deployment address — same multi-chain
   *  determinism as v0.6. */
  val V07Address: String = "0x0000000071727De22E5E9d8BAf0edAc6f37da032"

  /** EntryPoint protocol version the wallet talks to. Determines
   *  hash layout, JSON wire format, and the default contract
   *  address — but not the SPI surface, so a single wallet can
   *  service both via separate adapters. */
  enum Version:
    case V06, V07

    def address: String = this match
      case V06 => V06Address
      case V07 => V07Address
