package scalascript.interpreter

import org.scalatest.funsuite.AnyFunSuite

class FrameMapTest extends AnyFunSuite:

  test("fromMap uses small frame implementations for empty, one, and two fields"):
    val parent = Map("parent" -> Value.StringV("p"))

    val empty = FrameMap.fromMap(Map.empty, parent)
    assert(empty eq parent)

    val one = FrameMap.fromMap(Map("a" -> Value.intV(1)), parent)
    assert(one.isInstanceOf[FrameMap1])
    assert(one("a") == Value.intV(1))
    assert(one("parent") == Value.StringV("p"))

    val two = FrameMap.fromMap(Map("a" -> Value.intV(1), "b" -> Value.intV(2)), parent)
    assert(two.isInstanceOf[FrameMap2])
    assert(two("a") == Value.intV(1))
    assert(two("b") == Value.intV(2))
    assert(two("parent") == Value.StringV("p"))

  test("fromMapWithSelf avoids array frame for zero and one instance field"):
    val parent = Map("parent" -> Value.StringV("p"))
    val self = Value.NativeFnV("self", _ => Computation.PureUnit)

    val onlySelf = FrameMap.fromMapWithSelf(Map.empty, "self", self, parent)
    assert(onlySelf.isInstanceOf[FrameMap1])
    assert(onlySelf("self") eq self)
    assert(onlySelf("parent") == Value.StringV("p"))

    val fieldAndSelf = FrameMap.fromMapWithSelf(Map("a" -> Value.intV(1)), "self", self, parent)
    assert(fieldAndSelf.isInstanceOf[FrameMap2])
    assert(fieldAndSelf("a") == Value.intV(1))
    assert(fieldAndSelf("self") eq self)
    assert(fieldAndSelf("parent") == Value.StringV("p"))
