package scalascript.blockchain.spi

/** Chain-agnostic transaction intents. The cross-chain shapes live
 *  here; chain-specific variants (e.g. `InvokeProgram` for Solana,
 *  `ConsumeWithRedeemer` for Cardano Plutus) extend this hierarchy
 *  from their respective `blockchain-<chain>` modules.
 *
 *  See specs/blockchain-spi.md §6.1 for the smart-contract surface. */
sealed trait TxIntent

object TxIntent:

  /** Transfer the chain's native coin. */
  case class NativeTransfer(to: String, amount: BigInt) extends TxIntent

  /** Transfer a fungible token (ERC-20, SPL, …) per the asset's chain. */
  case class TokenTransfer(asset: Asset, to: String, amount: BigInt) extends TxIntent

  /** Generic contract invocation. `calldata` is chain-native
   *  (ABI-encoded selector + args on EVM, instruction data on Solana,
   *  …). `value` only meaningful where the chain attaches native coin
   *  to a call (EVM). */
  case class ContractCall(
    target:   String,
    calldata: Array[Byte],
    value:    BigInt = BigInt(0),
  ) extends TxIntent

  /** EIP-3009 / x402 settlement: a signed off-chain authorization that
   *  the adapter encodes into the on-chain `transferWithAuthorization`
   *  call. The chain adapter knows the encoding; clients don't need to. */
  case class TokenTransferAuthorized(
    asset:       Asset,
    from:        String,
    to:          String,
    amount:      BigInt,
    validAfter:  BigInt,
    validBefore: BigInt,
    nonce:       Array[Byte],
    signature:   Array[Byte],
  ) extends TxIntent

  /** Deploy a contract. `bytecode` is the chain-native binary
   *  (EVM init bytecode; UPLC for Cardano; BPF for Solana; WASM
   *  elsewhere). `args` are ABI-encoded constructor arguments
   *  (chain-specific encoding). `salt = Some(_)` selects CREATE2
   *  on EVM (counterfactual address). */
  case class Deploy(
    bytecode: Array[Byte],
    args:     Array[Byte]         = Array.emptyByteArray,
    salt:     Option[Array[Byte]] = None,
  ) extends TxIntent
