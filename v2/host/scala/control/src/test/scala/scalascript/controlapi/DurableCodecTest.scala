package scalascript.controlapi

import org.scalatest.funsuite.AnyFunSuite
import scalascript.control.*

final class DurableCodecTest extends AnyFunSuite:
  private def rawBits(value: Double): Long =
    java.lang.Double.doubleToRawLongBits(value)

  test("scalars round-trip through canonical bytes"):
    assert(DurableCodec.unit.decode(DurableCodec.unit.encode(())) == (()))
    assert(DurableCodec.boolean.decode(DurableCodec.boolean.encode(true)))
    assert(!DurableCodec.boolean.decode(DurableCodec.boolean.encode(false)))
    for value <- List(0, 1, -1, Int.MinValue, Int.MaxValue) do
      assert(DurableCodec.int.decode(DurableCodec.int.encode(value)) == value)
    for value <- List(0L, -1L, Long.MinValue, Long.MaxValue) do
      assert(DurableCodec.long.decode(DurableCodec.long.encode(value)) == value)
    for value <- List(BigInt(0), BigInt(-1), BigInt("123456789012345678901234567890"), BigInt(-255)) do
      assert(DurableCodec.bigInt.decode(DurableCodec.bigInt.encode(value)) == value)
    for value <- List("", "hello", "юникод ✓ 𝟛") do
      assert(DurableCodec.string.decode(DurableCodec.string.encode(value)) == value)

  test("floating-point encoding is canonical: signed zero kept, NaN normalized"):
    // -0.0 and +0.0 are `==` but must not share canonical bytes.
    assert(DurableCodec.double.encode(-0.0) != DurableCodec.double.encode(0.0))
    assert(rawBits(DurableCodec.double.decode(DurableCodec.double.encode(-0.0))) == rawBits(-0.0))
    // any NaN normalizes to one canonical NaN so the bytes match across lanes.
    val nan = java.lang.Double.longBitsToDouble(0x7ff8000000000123L)
    val decoded = DurableCodec.double.decode(DurableCodec.double.encode(nan))
    assert(java.lang.Double.isNaN(decoded))
    assert(rawBits(decoded) == 0x7ff8000000000000L)
    assert(DurableCodec.double.encode(nan) == DurableCodec.double.encode(Double.NaN))
    for value <- List(1.5, -2.25, Double.PositiveInfinity, Double.NegativeInfinity) do
      assert(DurableCodec.double.decode(DurableCodec.double.encode(value)) == value)

  test("encoding is deterministic and structural"):
    val codec = DurableCodec.pair(DurableCodec.string, DurableCodec.list(DurableCodec.int))
    val value = ("k", List(1, 2, 3))
    assert(codec.encode(value) == codec.encode(value)) // repeated calls agree
    // equal values built via different paths agree byte-for-byte.
    val rebuilt = ("k", List(1) ++ List(2, 3))
    assert(codec.encode(value) == codec.encode(rebuilt))

  test("composite codecs round-trip"):
    val codec =
      DurableCodec.either(DurableCodec.string, DurableCodec.pair(DurableCodec.int, DurableCodec.boolean))
    for value <- List(Left("x"), Right((7, true)), Left(""), Right((0, false))) do
      assert(codec.decode(codec.encode(value)) == value)
    val listCodec = DurableCodec.list(DurableCodec.string)
    for value <- List(Nil, List("a"), List("a", "", "c")) do
      assert(listCodec.decode(listCodec.encode(value)) == value)

  test("decoding is exact and bounded"):
    // truncated input.
    val fourBytes = DurableCodec.int.encode(5).toArray
    intercept[DurableDecodeError](
      DurableCodec.int.decode(DurableBytes.fromArray(fourBytes.take(3)))
    )
    // trailing bytes.
    intercept[DurableDecodeError](
      DurableCodec.int.decode(DurableBytes.fromArray(fourBytes :+ 0.toByte))
    )
    // unknown sum tag (2 is neither Left nor Right).
    intercept[DurableDecodeError](
      DurableCodec
        .either(DurableCodec.int, DurableCodec.int)
        .decode(DurableBytes.fromArray(Array[Byte](2, 0, 0, 0, 0)))
    )
    // a byte length larger than the remaining input.
    intercept[DurableDecodeError](
      DurableCodec.string.decode(
        DurableBytes.fromArray(Array[Byte](0x7f, 0x7f, 0x7f, 0x7f))
      )
    )
    // a list element count past the sane ceiling (0x02000000 = 1<<25 > 1<<24).
    intercept[DurableDecodeError](
      DurableCodec
        .list(DurableCodec.int)
        .decode(DurableBytes.fromArray(Array[Byte](0x02, 0, 0, 0)))
    )

  private final class Cell(var value: Int)

  test("a codec-backed savable frame serializes and isolates each run"):
    // imap builds a nominal codec; decode yields a fresh mutable Cell every run.
    val cellCodec: DurableCodec[Cell] =
      DurableCodec.imap(DurableCodec.int)(bits => new Cell(bits))(cell => cell.value)
    val machine = new ResumeStateMachine[Cell, Int, Nothing, Int]:
      override def resume(state: Cell, input: Int): Eff[Nothing, Int] =
        state.value += input // mutate only this run's decoded frame
        Eff.pure(state.value)

    val original = new Cell(100)
    val continuation: Continuation[Int, Nothing, Int] =
      Continuation.savable(original, machine, cellCodec)

    type Saved = SavedContinuation.Aux[Int, Nothing, Int]
    val saved: Saved = Eff.runPure(
      handle[Save.type, Nothing, Saved, Saved](continuation.save())(
        new Handler[Save.type, Nothing, Saved, Saved]:
          val effect: EffectKey[Save.type] = Save.key
          def onReturn(value: Saved): Eff[Nothing, Saved] = Eff.pure(value)
          def onOperation[X](
              operation: Operation[Save.type, X],
              resumption: Resumption[X, Nothing, Saved]
          ): Eff[Nothing, Saved] =
            throw new AssertionError("codec-backed savable save unexpectedly rejected")
      )
    )

    original.value = 999 // post-save mutation of the original must be invisible

    val first = Eff.runPure(Restore.admitLocally(saved.run(1)))
    val second = Eff.runPure(Restore.admitLocally(saved.run(5)))
    assert(first == 101) // decoded from the frozen frame (100), not 999
    assert(second == 105) // independent decoded frame, not 101 + 5

  test("a codec is directly usable as savable evidence and honors snapshot round-trip"):
    val codec = DurableCodec.int
    // snapshot is the serialization round-trip, not a structural copy.
    assert(codec.snapshot(42) == 42)
    assert(codec.decode(codec.encode(42)) == 42)

  // Cross-lane golden vectors: the exact hex every lane must produce for these
  // values (specs/durable-frame-codec.md §Golden). The JS lane asserts the SAME
  // table; matching hex on both proves byte identity without a live cross-process
  // harness. If you change the wire format, update both tables and the spec.
  test("golden byte vectors pin the canonical wire format"):
    def hex(bytes: DurableBytes): String = bytes.toString
    assert(hex(DurableCodec.boolean.encode(true)) == "01")
    assert(hex(DurableCodec.boolean.encode(false)) == "00")
    assert(hex(DurableCodec.int.encode(1)) == "00000001")
    assert(hex(DurableCodec.int.encode(-1)) == "ffffffff")
    assert(hex(DurableCodec.int.encode(Int.MinValue)) == "80000000")
    assert(hex(DurableCodec.long.encode(1L)) == "0000000000000001")
    assert(hex(DurableCodec.long.encode(-1L)) == "ffffffffffffffff")
    assert(hex(DurableCodec.double.encode(1.5)) == "3ff8000000000000")
    assert(hex(DurableCodec.double.encode(-0.0)) == "8000000000000000")
    assert(hex(DurableCodec.double.encode(Double.NaN)) == "7ff8000000000000")
    assert(hex(DurableCodec.double.encode(Double.PositiveInfinity)) == "7ff0000000000000")
    assert(hex(DurableCodec.string.encode("")) == "00000000")
    assert(hex(DurableCodec.string.encode("A")) == "0000000141")
    assert(hex(DurableCodec.string.encode("é")) == "00000002c3a9")
    assert(hex(DurableCodec.bigInt.encode(BigInt(0))) == "0000000100")
    assert(hex(DurableCodec.bigInt.encode(BigInt(127))) == "000000017f")
    assert(hex(DurableCodec.bigInt.encode(BigInt(128))) == "000000020080")
    assert(hex(DurableCodec.bigInt.encode(BigInt(-1))) == "00000001ff")
    assert(hex(DurableCodec.bigInt.encode(BigInt(256))) == "000000020100")
    assert(hex(DurableCodec.list(DurableCodec.int).encode(List(1, 2))) == "000000020000000100000002")
    assert(hex(DurableCodec.list(DurableCodec.int).encode(Nil)) == "00000000")
    assert(hex(DurableCodec.pair(DurableCodec.int, DurableCodec.boolean).encode((7, true))) == "0000000701")
    assert(
      hex(DurableCodec.either(DurableCodec.int, DurableCodec.string).encode(Left(5))) == "0000000005"
    )
    assert(
      hex(DurableCodec.either(DurableCodec.int, DurableCodec.string).encode(Right("A"))) == "010000000141"
    )

  private def bytesFromHex(hex: String): DurableBytes =
    DurableBytes.fromArray(
      hex.grouped(2).map(pair => Integer.parseInt(pair, 16).toByte).toArray
    )

  test("map codec round-trips and canonicalizes key order"):
    val codec = DurableCodec.map(DurableCodec.string, DurableCodec.int)
    for value <- List(Map.empty[String, Int], Map("a" -> 1), Map("b" -> 2, "a" -> 1, "c" -> 3)) do
      assert(codec.decode(codec.encode(value)) == value)
    // insertion / iteration order does not affect the canonical bytes (§9.1).
    assert(codec.encode(Map("b" -> 2, "a" -> 1)) == codec.encode(Map("a" -> 1, "b" -> 2)))

  test("map codec golden bytes are canonical-key-ordered"):
    val codec = DurableCodec.map(DurableCodec.string, DurableCodec.int)
    // built "b" then "a" but the bytes are sorted a, b.
    assert(
      codec.encode(Map("b" -> 2, "a" -> 1)).toString ==
        "00000002000000016100000001000000016200000002"
    )

  test("map codec rejects non-canonical key order on decode"):
    val codec = DurableCodec.map(DurableCodec.string, DurableCodec.int)
    // two entries written in descending key order ("b" then "a").
    val nonCanonical = bytesFromHex("00000002000000016200000002000000016100000001")
    intercept[DurableDecodeError](codec.decode(nonCanonical))
