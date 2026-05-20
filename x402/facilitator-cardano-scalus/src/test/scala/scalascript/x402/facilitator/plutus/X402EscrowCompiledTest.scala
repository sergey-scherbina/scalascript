package scalascript.x402.facilitator.plutus

import org.scalatest.funsuite.AnyFunSuite

/** Phase 2 sanity tests for the compiled-validator resource shipped
 *  alongside this module. The actual `@Compile` pass that produces the
 *  CBOR runs in the Scala 3.3.7 sub-project `x402-escrow-plutus`; this
 *  module only reads the resulting hex from the classpath. */
class X402EscrowCompiledTest extends AnyFunSuite:

  test("doubleCborHex: resource exists and is non-empty hex") {
    val hex = X402EscrowCompiled.doubleCborHex
    assert(hex.nonEmpty, "compiled-script resource must be present and non-empty")
    assert(hex.length % 2 == 0, s"hex length must be even, got ${hex.length}")
    assert(hex.matches("(?i)[0-9a-f]+"),
      s"hex must be pure hex digits, saw prefix '${hex.take(32)}...'")
  }

  test("doubleCborBytes: decoded length matches hex length") {
    val bytes = X402EscrowCompiled.doubleCborBytes
    val hex   = X402EscrowCompiled.doubleCborHex
    assert(bytes.length == hex.length / 2)
  }

  test("compiled program is materially larger than a no-op") {
    // A trivial `()` Plutus program serializes to ~10 bytes; ours
    // decodes a ScriptContext, dispatches on the script purpose, and
    // performs require-signatory checks → must be much larger.
    val byteCount = X402EscrowCompiled.doubleCborBytes.length
    assert(byteCount > 100,
      s"compiled validator must exceed 100 bytes, got $byteCount")
  }

  test("doubleCborHex is deterministic across reads") {
    val a = X402EscrowCompiled.doubleCborHex
    val b = X402EscrowCompiled.doubleCborHex
    assert(a == b)
  }
