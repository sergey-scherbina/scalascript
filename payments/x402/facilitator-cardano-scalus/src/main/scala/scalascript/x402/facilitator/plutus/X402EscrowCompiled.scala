package scalascript.x402.facilitator.plutus

import java.io.IOException

/** Off-chain handle to the compiled X402 Plutus V3 escrow validator.
 *
 *  The actual `@Compile`-lowered UPLC bytes are emitted by the Scala
 *  3.3.7 sub-project `x402-escrow-plutus` (run
 *  `sbt x402EscrowPlutus/emitEscrowHex` to refresh) and committed as a
 *  static resource at `src/main/resources/x402-escrow.plutus.hex`.
 *
 *  Splitting the build this way lets the main 3.8.3 module consume the
 *  compiled script without depending on Scalus's compiler plugin —
 *  which targets dotty 3.3.x internals and is incompatible with our
 *  Scala 3.8.3. See [`specs/x402-cardano-scalus.md`](../../../../../../../../../specs/x402-cardano-scalus.md)
 *  §5 "Phase 2 retry — Scala-version split build". */
object X402EscrowCompiled:

  /** Resource path inside `x402-facilitator-cardano-scalus.jar`. */
  val resourcePath: String = "x402-escrow.plutus.hex"

  /** Double-CBOR-hex of the compiled Plutus V3 validator. Lazy so the
   *  resource read happens once on first access; subsequent reads are
   *  served from the cached `String`. */
  lazy val doubleCborHex: String =
    val stream = Option(getClass.getClassLoader.getResourceAsStream(resourcePath))
      .getOrElse(throw IOException(
        s"Missing classpath resource '$resourcePath' — run `sbt x402EscrowPlutus/emitEscrowHex` to (re)generate it",
      ))
    try
      val hex = scala.io.Source.fromInputStream(stream, "UTF-8").mkString.trim
      require(hex.nonEmpty,        s"Resource '$resourcePath' is empty")
      require(hex.matches("(?i)[0-9a-f]+"),
        s"Resource '$resourcePath' is not a hex string (saw prefix '${hex.take(32)}...')")
      hex
    finally stream.close()

  /** Raw double-CBOR bytes of the compiled program. */
  lazy val doubleCborBytes: Array[Byte] =
    val hex = doubleCborHex
    val out = new Array[Byte](hex.length / 2)
    var i   = 0
    while i < out.length do
      out(i) = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    out
