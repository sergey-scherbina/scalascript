package scalascript.interpreter

import org.scalatest.funsuite.AnyFunSuite

class IntVPoolTest extends AnyFunSuite:

  test("intV(-2048) is pooled — same instance for repeated calls"):
    val a = Value.intV(-2048L)
    val b = Value.intV(-2048L)
    assert(a eq b, "expected cached instance")

  test("intV(0) is pooled"):
    val a = Value.intV(0L)
    val b = Value.intV(0L)
    assert(a eq b)

  test("intV(16383) is pooled"):
    val a = Value.intV(16383L)
    val b = Value.intV(16383L)
    assert(a eq b)

  test("intV(16384) is NOT pooled — fresh allocation"):
    val a = Value.intV(16384L)
    val b = Value.intV(16384L)
    assert(a.v == 16384L)
    assert(!(a eq b), "out-of-pool values should be distinct instances")

  test("intV(-2049) is NOT pooled"):
    val a = Value.intV(-2049L)
    val b = Value.intV(-2049L)
    assert(a.v == -2049L)
    assert(!(a eq b))

  test("intV produces correct values across pool range"):
    for n <- -2048L to 16383L do
      assert(Value.intV(n).v == n, s"wrong value for n=$n")

  test("intV pool boundary: -2048 pooled, -2049 not"):
    assert(Value.intV(-2048L) eq Value.intV(-2048L))
    assert(!(Value.intV(-2049L) eq Value.intV(-2049L)))

  test("intV pool boundary: 16383 pooled, 16384 not"):
    assert(Value.intV(16383L) eq Value.intV(16383L))
    assert(!(Value.intV(16384L) eq Value.intV(16384L)))
