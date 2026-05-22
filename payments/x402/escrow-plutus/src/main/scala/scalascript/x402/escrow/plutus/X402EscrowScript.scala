package scalascript.x402.escrow.plutus

import scalus.*
import scalus.Compile
import scalus.uplc.builtin.{ByteString, Data, FromData, ToData}
import scalus.cardano.onchain.plutus.prelude.*
import scalus.cardano.onchain.plutus.v1.PubKeyHash
import scalus.cardano.onchain.plutus.v3.{ScriptContext, ScriptInfo}
import scalus.uplc.PlutusV3

// ── Datum + Redeemer types ────────────────────────────────────────────────
//
// Top-level so Scalus 0.15.1's @Compile macro can derive FromData /
// ToData. Nested-in-object case classes don't pick up derivation.

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
 *  Single-purpose validator written as a plain `@Compile object` with
 *  `inline def validate(scData: Data): Unit` rather than extending
 *  Scalus's `Validator` trait — that trait exposes six deferred-inline
 *  purpose methods (mint/spend/withdraw/certify/vote/propose) that all
 *  need to be supplied for compilation, even when only `spend` is
 *  meaningful. Dispatching on `ScriptInfo.SpendingScript` directly is
 *  simpler and avoids the unused-purpose boilerplate.
 *
 *  Phase 2 only enforces structural checks reachable without on-chain
 *  CBOR primitives:
 *    - Claim must be signed by the `receiverHash` (proxy for the
 *      facilitator's relayer)
 *    - Refund must be signed by the `payerKeyHash`
 *
 *  Full CIP-8 / Ed25519 / payload-hash check lands in Phase 2.5.
 *
 *  See [`docs/x402-cardano-scalus.md`](../../../../../../../docs/x402-cardano-scalus.md). */
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
        require(false, "X402EscrowScript only supports the spending purpose")

/** Off-chain handle. The compiled UPLC program's double-CBOR hex is
 *  the single artefact this sub-build produces. */
object X402EscrowCompiled:
  private given scalus.compiler.Options = scalus.compiler.Options.default
  lazy val compiled               = PlutusV3.compile(X402EscrowScript.validate)
  lazy val doubleCborHex: String  = compiled.program.doubleCborHex
