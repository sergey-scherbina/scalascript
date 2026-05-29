package scalascript.interpreter

import org.scalatest.funsuite.AnyFunSuite

class ComputationTest extends AnyFunSuite:

  test("sequence preserves leading pure values before first suspended computation"):
    val delayed = Computation.FlatMap(Computation.Pure(Value.intV(3)), v => Computation.Pure(v))
    val result = Computation.run(Computation.sequence(
      List(
        Computation.Pure(Value.intV(1)),
        Computation.Pure(Value.intV(2)),
        delayed,
        Computation.Pure(Value.intV(4))
      )
    ))

    assert(result == Value.ListV(List(Value.intV(1), Value.intV(2), Value.intV(3), Value.intV(4))))

  test("sequence handles non-pure first computation"):
    val first = Computation.FlatMap(Computation.Pure(Value.StringV("a")), v => Computation.Pure(v))
    val result = Computation.run(Computation.sequence(
      List(first, Computation.Pure(Value.StringV("b")))
    ))

    assert(result == Value.ListV(List(Value.StringV("a"), Value.StringV("b"))))

  test("mapSequence handles three pure values"):
    val result = Computation.run(Computation.mapSequence(
      List(Value.intV(1), Value.intV(2), Value.intV(3)),
      v => Computation.Pure(v)
    ))

    assert(result == Value.ListV(List(Value.intV(1), Value.intV(2), Value.intV(3))))

  test("mapSequence handles suspended third value"):
    val result = Computation.run(Computation.mapSequence(
      List(Value.intV(1), Value.intV(2), Value.intV(3)),
      v =>
        if v == Value.intV(3) then Computation.FlatMap(Computation.Pure(v), x => Computation.Pure(x))
        else Computation.Pure(v)
    ))

    assert(result == Value.ListV(List(Value.intV(1), Value.intV(2), Value.intV(3))))
