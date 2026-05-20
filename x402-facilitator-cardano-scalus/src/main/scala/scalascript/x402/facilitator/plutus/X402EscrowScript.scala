package scalascript.x402.facilitator.plutus

import scalus.*
import scalus.Compile
import scalus.uplc.builtin.{ByteString, Data, FromData, ToData}
import scalus.cardano.onchain.plutus.prelude.*
import scalus.cardano.onchain.plutus.v1.PubKeyHash
import scalus.cardano.onchain.plutus.v3.{ScriptContext, ScriptInfo}
import scalus.uplc.PlutusV3

// ── Datum + Redeemer types ────────────────────────────────────────────────
//
// Lifted to top level so Scalus's @Compile macro can derive FromData /
// ToData. Nested-in-object case classes don't pick up derivation in
// Scalus 0.15.1 (see docs/x402-cardano-scalus.md §5 spike findings).

case class EscrowDatum(
  payerKeyHash:     PubKeyHash,
  claimMessageHash: ByteString,
  receiverHash:     PubKeyHash,
  amount:           BigInt,
  validBefore:      BigInt,
  refundAfter:      BigInt,
) derives FromData, ToData

enum EscrowRedeemer derives FromData, ToData:
  case Claim(coseSign1: ByteString, coseKey: ByteString)
  case Refund

/** Plutus V3 escrow validator for x402 Cardano payments.
 *
 *  Compiled-validator-only design: rather than extending `Validator`
 *  (whose six deferred-inline purpose methods all need to be supplied
 *  for compilation, even when only `spend` is meaningful), we write a
 *  single `validate(scData: Data): Unit` that decodes the
 *  `ScriptContext` and dispatches on `scriptInfo`. Non-spend
 *  purposes fail the script — this validator is single-purpose.
 *
 *  Datum + Redeemer types live at top level (Scalus 0.15.1 derivation
 *  doesn't work for nested types — see spec §5).
 *
 *  Phase 2 only enforces the structural checks reachable without
 *  on-chain CBOR primitives:
 *    - Claim must be signed by the `receiverHash` (proxy for the
 *      facilitator's relayer)
 *    - Refund must be signed by the `payerKeyHash`
 *
 *  The full CIP-8 / Ed25519 / payload-hash check lands in Phase 2.5
 *  once on-chain CBOR helpers are wired through Scalus's BuiltinByteString
 *  primitives.
 *
 *  See [`docs/x402-cardano-scalus.md`](../../../../../../../../../docs/x402-cardano-scalus.md). */
@Compile
object X402EscrowScript:

  inline def validate(scData: Data): Unit =
    val ctx = scData.to[ScriptContext]
    ctx.scriptInfo match
      case ScriptInfo.SpendingScript(_, datumOpt) =>
        val d = datumOpt.getOrFail("Missing escrow datum").to[EscrowDatum]
        val r = ctx.redeemer.to[EscrowRedeemer]
        r match
          case _: EscrowRedeemer.Claim =>
            require(
              ctx.txInfo.signatories.contains(d.receiverHash),
              "Claim must be signed by the receiver (proxy: receiverHash)",
            )
          case EscrowRedeemer.Refund =>
            require(
              ctx.txInfo.signatories.contains(d.payerKeyHash),
              "Refund must be signed by the payer",
            )
      case _ =>
        // Single-purpose validator: only spending is meaningful here.
        require(false, "X402EscrowScript only supports the spending purpose")

/** Off-chain handle to the compiled UPLC program.
 *
 *  **Phase 2 status**: the source above type-checks against the Scalus
 *  library jar, but the **Scalus compiler plugin is not enabled in
 *  this build** — it depends on internal dotty APIs that exist in
 *  Scala 3.3.7 (Scalus's build target) but were removed in 3.8.3 (our
 *  target). Without the plugin, `PlutusV3.compile(...)` throws a
 *  helpful runtime exception ("This method call is handled by the
 *  Scalus compiler plugin..."). See [`docs/x402-cardano-scalus.md`](../../../../../../../../../docs/x402-cardano-scalus.md)
 *  §5 for the Phase 2.5 plan once a Scalus version targeting 3.8.x ships.
 *
 *  We keep the unused import warning suppressed at the module level
 *  (`scalacOptions` drops `-Werror`) so the source can travel with the
 *  rest of the build.
 *
 *  Downstream phases (3 = address, 4 = bloxbean claim Tx) import this
 *  object — they will fail at the first `doubleCborHex` read, which is
 *  the correct fail-loud behavior until the plugin lands. */
object X402EscrowCompiled:
  private given scalus.compiler.Options = scalus.compiler.Options.default

  lazy val compiled = PlutusV3.compile(X402EscrowScript.validate)

  /** Double-CBOR-hex of the compiled UPLC program. Will throw until
   *  the Scalus compiler plugin is enabled (see object docstring). */
  lazy val doubleCborHex: String = compiled.program.doubleCborHex
