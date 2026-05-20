package scalascript.x402.facilitator.plutus

import org.scalatest.funsuite.AnyFunSuite
import scalus.uplc.builtin.ByteString
import scalus.cardano.onchain.plutus.v1.PubKeyHash

/** Phase 2 sanity tests for the X402Escrow validator skeleton.
 *
 *  Two layers are exercised here:
 *
 *  1. **Plain Scala 3 typing** — the `EscrowDatum` / `EscrowRedeemer`
 *     types compile against Scalus's builtin / prelude types, derive
 *     FromData / ToData, and can be constructed from off-chain code.
 *
 *  2. **`PlutusV3.compile` behavior** — without the Scalus compiler
 *     plugin (intentionally not enabled in this build, see
 *     `X402EscrowCompiled` docstring), `doubleCborHex` is expected to
 *     throw a marker RuntimeException pointing at the missing plugin.
 *     This test pins that behavior so the day we enable the plugin
 *     we'll see this test flip naturally. */
class X402EscrowScriptTest extends AnyFunSuite:

  test("EscrowDatum is constructible from off-chain Scalus types") {
    val d = EscrowDatum(
      payerKeyHash     = PubKeyHash(ByteString.fromArray(Array.fill[Byte](28)(0x01))),
      claimMessageHash = ByteString.fromArray(Array.fill[Byte](32)(0x02)),
      receiverHash     = PubKeyHash(ByteString.fromArray(Array.fill[Byte](28)(0x03))),
      amount           = BigInt(2_000_000),
      validBefore      = BigInt(System.currentTimeMillis() / 1000 + 300),
      refundAfter      = BigInt(System.currentTimeMillis() / 1000 + 86_400),
    )
    assert(d.amount      == BigInt(2_000_000))
    assert(d.validBefore <  d.refundAfter)
  }

  test("EscrowRedeemer.Refund is a singleton") {
    val r1 = EscrowRedeemer.Refund
    val r2 = EscrowRedeemer.Refund
    assert(r1 == r2)
  }

  test("EscrowRedeemer.Claim carries cose bytes") {
    val coseSign1 = ByteString.fromArray(Array[Byte](0x84.toByte, 0x44, 0xA1.toByte, 0x01, 0x27))
    val coseKey   = ByteString.fromArray(Array[Byte](0xA4.toByte, 0x01, 0x01, 0x03, 0x27))
    val claim: EscrowRedeemer.Claim = EscrowRedeemer.Claim(coseSign1, coseKey)
    assert(claim.coseSign1.size == 5)
    assert(claim.coseKey.size   == 5)
  }

  test("X402EscrowCompiled.doubleCborHex: throws until Scalus plugin enabled") {
    // The Scalus compiler plugin lowers `@Compile`-annotated objects to
    // Plutus Core. It's not enabled in this build (see X402EscrowCompiled
    // docstring); without it, `PlutusV3.compile(...)` throws a marker
    // RuntimeException at runtime. Pin that until the plugin lands.
    val ex = intercept[RuntimeException] {
      X402EscrowCompiled.doubleCborHex
    }
    val msg = Option(ex.getMessage).getOrElse(ex.toString)
    assert(
      msg.contains("compiler plugin") || msg.contains("scalus-plugin"),
      s"Expected the missing-plugin marker exception, got: $msg",
    )
  }
