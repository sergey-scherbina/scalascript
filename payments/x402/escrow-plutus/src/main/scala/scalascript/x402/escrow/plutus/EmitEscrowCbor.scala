package scalascript.x402.escrow.plutus

import java.nio.file.{Files, Paths}

/** Writes `X402EscrowCompiled.doubleCborHex` to the file path given
 *  as argv[0]. Driven from the sbt `emitEscrowHex` task (see build.sbt
 *  `x402EscrowPlutus` settings).
 *
 *  Runs under Scala 3.3.7 with the Scalus compiler plugin enabled —
 *  the main 3.8.3 build only sees the resulting hex resource. */
object EmitEscrowCbor:
  def main(args: Array[String]): Unit =
    require(args.nonEmpty, "Usage: EmitEscrowCbor <output-path>")
    val target = Paths.get(args(0))
    val hex    = X402EscrowCompiled.doubleCborHex
    Files.createDirectories(target.getParent)
    Files.writeString(target, hex)
    println(s"Wrote ${hex.length} hex chars to $target")
