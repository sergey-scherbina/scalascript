package scalascript.interpreter

import org.scalatest.funsuite.AnyFunSuite

class IntVPoolTest extends AnyFunSuite:

  test("intV(-128) is pooled — same instance for repeated calls"):
    val a = Value.intV(-128L)
    val b = Value.intV(-128L)
    assert(a eq b, "expected cached instance")

  test("intV(0) is pooled"):
    val a = Value.intV(0L)
    val b = Value.intV(0L)
    assert(a eq b)

  test("intV(1024) is pooled"):
    val a = Value.intV(1024L)
    val b = Value.intV(1024L)
    assert(a eq b)

  test("intV(1025) is NOT pooled — fresh allocation"):
    val a = Value.intV(1025L)
    val b = Value.intV(1025L)
    // Separate heap objects; value equality holds but reference equality does not
    assert(a.v == 1025L)
    assert(!(a eq b), "out-of-pool values should be distinct instances")

  test("intV(-129) is NOT pooled"):
    val a = Value.intV(-129L)
    val b = Value.intV(-129L)
    assert(a.v == -129L)
    assert(!(a eq b))

  test("intV produces correct values across pool range"):
    for n <- -128L to 1024L do
      assert(Value.intV(n).v == n, s"wrong value for n=$n")

  test("intV pool boundary: -128 pooled, -129 not"):
    assert(Value.intV(-128L) eq Value.intV(-128L))
    assert(!(Value.intV(-129L) eq Value.intV(-129L)))

  test("intV pool boundary: 1024 pooled, 1025 not"):
    assert(Value.intV(1024L) eq Value.intV(1024L))
    assert(!(Value.intV(1025L) eq Value.intV(1025L)))
