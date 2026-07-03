package scalascript.interpreter

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.SpiValue

/** core-min-value-unification (slice 1): the `Value ↔ SpiValue` boundary must be LOSSLESS for the
 *  pure-data cases. `Char` and `Vector` used to be coerced (`Char`→`StrV`, `Vector`→`ListV`), so a
 *  value crossing the block-form SPI boundary silently changed type. They now have dedicated
 *  `SpiValue.CharV` / `SpiValue.VectorV` cases. This locks the round-trip identity. */
class SpiValueDataRoundTripTest extends AnyFunSuite with Matchers:

  private val interp = Interpreter(java.io.PrintStream(java.io.ByteArrayOutputStream()))
  private def roundTrip(v: Value): Value = interp.spiToValue(interp.valueToSpi(v))

  test("Char survives the SPI boundary as a Char (not a 1-char String)"):
    interp.valueToSpi(Value.CharV('x')) shouldBe SpiValue.CharV('x')
    roundTrip(Value.CharV('x')) shouldBe Value.CharV('x')

  test("Vector survives the SPI boundary as a Vector (not a List)"):
    val v = Value.VectorV(Vector(Value.intV(1), Value.intV(2), Value.intV(3)))
    interp.valueToSpi(v) shouldBe SpiValue.VectorV(
      List(SpiValue.IntV(1), SpiValue.IntV(2), SpiValue.IntV(3)))
    roundTrip(v) shouldBe v

  test("nested Vector-of-Char round-trips losslessly (no element coercion)"):
    val v = Value.VectorV(Vector(Value.CharV('a'), Value.CharV('b')))
    roundTrip(v) shouldBe v

  test("List stays a List and String stays a String (no regression on the close cousins)"):
    roundTrip(Value.ListV(List(Value.intV(1)))) shouldBe Value.ListV(List(Value.intV(1)))
    roundTrip(Value.StringV("x")) shouldBe Value.StringV("x")
