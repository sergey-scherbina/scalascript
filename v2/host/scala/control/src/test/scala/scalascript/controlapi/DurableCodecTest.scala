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

  test("floating-point encoding preserves bit identity"):
    // -0.0 and +0.0 are `==` but must not share canonical bytes.
    assert(DurableCodec.double.encode(-0.0) != DurableCodec.double.encode(0.0))
    assert(rawBits(DurableCodec.double.decode(DurableCodec.double.encode(-0.0))) == rawBits(-0.0))
    // an exact NaN payload survives the round-trip.
    val nan = java.lang.Double.longBitsToDouble(0x7ff8000000000123L)
    val decoded = DurableCodec.double.decode(DurableCodec.double.encode(nan))
    assert(rawBits(decoded) == 0x7ff8000000000123L)
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
