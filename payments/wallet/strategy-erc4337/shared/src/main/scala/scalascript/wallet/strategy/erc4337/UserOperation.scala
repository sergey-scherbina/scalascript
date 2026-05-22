package scalascript.wallet.strategy.erc4337

/** EIP-4337 v0.6 UserOperation. Replaces a regular EVM transaction
 *  in the account-abstraction model: the bundler submits a batch of
 *  these to the EntryPoint contract, which calls the smart account's
 *  validateUserOp + execute methods.
 *
 *  v0.7 / v0.8 changed the wire format (`PackedUserOperation` with
 *  packed gas limits + paymaster fields). We target v0.6 first
 *  because it's still by far the most deployed; a follow-on slice
 *  adds v0.7 once the v0.6 surface is exercised. */
case class UserOperation(
  /** The smart account's address (20 bytes, hex-encoded). */
  sender:               String,
  /** EntryPoint-managed nonce. Distinct from EOA nonces — the
   *  smart account can have multiple independent nonce sequences
   *  via the upper 192 bits of this u256 (the "key"). */
  nonce:                BigInt,
  /** Account-deployment bootstrap data. Empty for already-deployed
   *  accounts; for the first UserOp of a counterfactual account it
   *  carries [factoryAddress(20B), factoryCalldata(...)]. */
  initCode:             Array[Byte],
  /** The call the smart account should execute (typically a call
   *  to its own `execute(to, value, data)` selector). */
  callData:             Array[Byte],
  callGasLimit:         BigInt,
  verificationGasLimit: BigInt,
  preVerificationGas:   BigInt,
  maxFeePerGas:         BigInt,
  maxPriorityFeePerGas: BigInt,
  /** Paymaster sponsorship. Empty array = no paymaster (sender
   *  pays). Otherwise [paymasterAddress(20B), paymasterData(...)]. */
  paymasterAndData:     Array[Byte],
  /** EOA-signed userOpHash. Populated by SmartAccountStrategy
   *  during assembleSignedTransaction. Empty on the unsigned form. */
  signature:            Array[Byte],
):
  def isUnsigned: Boolean = signature.length == 0
