package scalascript.crypto

/** Elliptic curves and signature schemes the CryptoBackend SPI understands.
 *  See docs/blockchain-spi.md §4 for usage. */
enum Curve:
  /** Bitcoin / Ethereum / EVM family / Cosmos. */
  case Secp256k1

  /** Solana / Aptos / Sui / NEAR / Polkadot (one of). */
  case Ed25519

  /** WebAuthn / passkey (ERC-4337 smart-account owner). */
  case P256

  /** Polkadot / Substrate primary. */
  case Sr25519

  /** Ethereum consensus / Filecoin / Aleo. */
  case Bls12_381
