package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite

import scalascript.interop.descriptor.*
import scalascript.parser.Parser

/** ABI numeric-width vectors — `specs/numeric-width-reconciliation.md` §6.
 *
 *  This item exists because the descriptor declared a 32-bit width for a 64-bit value, so a
 *  host that believed it truncated silently. A vector that passes when it should fail would
 *  therefore defeat the entire point. Every vector here is paired with a **negative control**
 *  that exercises the same check against a deliberately truncating 32-bit marshaller and
 *  asserts it is caught. If the marshalling model below is ever made vacuous, the negative
 *  controls fail first.
 *
 *  SCOPE — measured 2026-07-17, stated plainly so nobody reads more into this file than is
 *  here: there is **no host binding generator, FFI emitter, or marshaller** for JS/TS, Rust,
 *  Swift or WASM-WASI anywhere in the tree, and all five generated host lanes in
 *  `tests/interop-conformance/lanes.tsv` are `pending` with adapter `none`. So `marshalAs`
 *  is an explicit MODEL of a conforming host, not a real bridge, and `HostCarriers` pins the
 *  contract each host profile must satisfy WHEN its lane lands. What is genuinely exercised
 *  end-to-end is the real seam that exists today: ScalaScript source spelling -> declared ABI
 *  width, through the real `PreBodyApiDescriptorProducer`.
 */
final class NumericWidthAbiVectorTest extends AnyFunSuite:

  private def source(body: String, exports: List[String]): String =
    val exportLines = exports.map(value => s"  - $value").mkString("\n")
    s"""---
       |name: demo
       |package: demo.api
       |exports:
       |$exportLines
       |---
       |
       |```scalascript
       |$body
       |```
       |""".stripMargin

  private def declaredType(spelling: String): AbiType =
    val input = source(s"def value(input: $spelling): $spelling = input", List("value"))
    PreBodyApiDescriptorProducer.descriptor(Parser.parse(input)) match
      case Right(descriptor) => descriptor.symbols.head.definition.parameterLists.head.parameters.head.tpe
      case Left(error) => fail(s"${error.code} at ${error.path}: ${error.message}")

  /** A model of a host marshalling a value at the width the descriptor declares. */
  private def marshalAs(primitive: AbiPrimitive, value: Long): Long = primitive match
    case AbiPrimitive.I32 => value.toInt.toLong // a 32-bit carrier: this is the truncation
    case AbiPrimitive.I64 => value
    case other            => fail(s"$other is not an integer carrier")

  // The pinned vectors. `overflow32` is the truncation witness: it is the smallest value that
  // a 32-bit lie destroys.
  private val Boundary = 2147483647L // 2^31 - 1
  private val Overflow32 = 2147483648L // 2^31
  private val Max64 = 9223372036854775807L
  private val Min64 = -9223372036854775808L

  private val Vectors: Vector[(String, Long)] = Vector(
    "boundary" -> Boundary,
    "overflow32" -> Overflow32,
    "max64" -> Max64,
    "min64" -> Min64
  )

  test("ssc Int and Long both declare a 64-bit ABI width, told apart by retained evidence"):
    // The truthfulness fix itself, read out of the REAL producer rather than restated.
    assert(declaredType("Int") ==
      AbiType.Primitive(AbiPrimitive.I64, Some(NumericWidthEvidence.DeclaredInt)))
    assert(declaredType("Long") ==
      AbiType.Primitive(AbiPrimitive.I64, Some(NumericWidthEvidence.DeclaredLong)))

  test("every pinned vector round-trips at the width ssc declares for Int"):
    val AbiType.Primitive(declared, _) = declaredType("Int"): @unchecked
    Vectors.foreach { case (name, value) =>
      assert(marshalAs(declared, value) == value,
        s"vector $name: a host marshalling an ssc Int per the descriptor changed the value " +
          s"from $value to ${marshalAs(declared, value)}")
    }

  test("NEGATIVE CONTROL: the same vectors detect a 32-bit carrier"):
    // If this test ever passes silently, the vectors above are vacuous. `boundary` survives a
    // 32-bit carrier by construction; everything above 2^31 must not.
    assert(marshalAs(AbiPrimitive.I32, Boundary) == Boundary, "boundary must survive 32 bits")

    val truncated = Vector("overflow32" -> Overflow32, "max64" -> Max64).map { case (name, value) =>
      name -> marshalAs(AbiPrimitive.I32, value)
    }
    assert(truncated == Vector("overflow32" -> -2147483648L, "max64" -> -1L),
      s"a 32-bit carrier failed to truncate as expected: $truncated")

    // ... and the declared width must be one that does NOT truncate them.
    val AbiType.Primitive(declared, _) = declaredType("Int"): @unchecked
    assert(declared != AbiPrimitive.I32,
      "ssc Int declared a 32-bit ABI width -- foreign hosts would truncate above 2^31-1")

  test("the declared width matches the measured Core IR wrapping semantics"):
    // `I64` claims 64-bit two's-complement wrapping. Measured in the real runtime:
    // 2147483647 + 1 => 2147483648 (no 32-bit wrap); 9223372036854775807 + 1 => Min64.
    val AbiType.Primitive(declared, _) = declaredType("Int"): @unchecked
    assert(marshalAs(declared, Boundary + 1L) == Overflow32)
    assert(marshalAs(declared, Max64) + 1L == Min64)

  test("host carriers for the declared width are 64-bit capable in every required profile"):
    // The per-host contract, pinned. No host lane executes this yet (all five generated lanes
    // are `pending`/adapter `none`), so this asserts the CONTRACT a profile must meet, and is
    // deliberately not dressed up as a passing cross-language round-trip.
    val hostCarriers: Map[String, String] = Map(
      "js-ts" -> "bigint", // NOT `number`: representing I64 as number is prohibited
      "rust" -> "i64",
      "swift" -> "Int64",
      "wasm-wasi" -> "i64",
      "jvm" -> "Long"
    )
    val thirtyTwoBitCarriers = Set("number", "i32", "Int32", "Int")

    val AbiType.Primitive(declared, _) = declaredType("Int"): @unchecked
    assert(declared == AbiPrimitive.I64)
    hostCarriers.foreach { case (host, carrier) =>
      assert(!thirtyTwoBitCarriers.contains(carrier),
        s"host $host would marshal an ssc Int through a 32-bit carrier $carrier")
    }
    assert(hostCarriers.keySet == Set("js-ts", "rust", "swift", "wasm-wasi", "jvm"))
