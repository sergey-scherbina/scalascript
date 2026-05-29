package scalascript.interpreter

import org.scalatest.funsuite.AnyFunSuite

class ValueTest extends AnyFunSuite:

  test("charV reuses ASCII characters and preserves non-ASCII values"):
    assert(Value.charV('a') eq Value.charV('a'))
    assert(Value.charV('\n') eq Value.charV('\n'))
    assert(Value.charV('\u0416') == Value.CharV('\u0416'))
