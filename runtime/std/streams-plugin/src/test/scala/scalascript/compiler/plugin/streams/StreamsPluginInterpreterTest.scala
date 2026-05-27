package scalascript.compiler.plugin.streams

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class StreamsPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(StreamsInterpreterPlugin()))

  test("Source.from emits all elements in order"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3)).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L))

  test("stream + emit produces elements"):
    val result = interp.eval(
      """
      val s = stream { () =>
        emit(10)
        emit(20)
        emit(30)
      }
      s.runToList()
      """
    )
    assert(result == List(10L, 20L, 30L))

  test("Source.from with integer range"):
    val result = interp.eval(
      """
      Source.from(1 to 5).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L, 4L, 5L))

  test("map transforms elements"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3)).map(x => x * 2).runToList()
      """
    )
    assert(result == List(2L, 4L, 6L))

  test("filter removes non-matching elements"):
    val result = interp.eval(
      """
      Source.from(1 to 10).filter(x => x % 2 == 0).runToList()
      """
    )
    assert(result == List(2L, 4L, 6L, 8L, 10L))

  test("map + filter pipeline"):
    val result = interp.eval(
      """
      Source.from(1 to 100)
        .map(x => x * 2)
        .filter(x => x > 50)
        .take(5)
        .runToList()
      """
    )
    assert(result == List(52L, 54L, 56L, 58L, 60L))

  test("runForeach runs side effects"):
    val result = interp.eval(
      """
      var sum = 0
      Source.from(List(1, 2, 3, 4)).runForeach(x => { sum = sum + x })
      sum
      """
    )
    assert(result == 10L)

  test("runFold accumulates"):
    val result = interp.eval(
      """
      Source.from(1 to 5).runFold(0)((acc, x) => acc + x)
      """
    )
    assert(result == 15L)

  test("take limits output"):
    val result = interp.eval(
      """
      Source.from(1 to 1000).take(3).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L))

  test("drop skips elements"):
    val result = interp.eval(
      """
      Source.from(1 to 5).drop(2).runToList()
      """
    )
    assert(result == List(3L, 4L, 5L))

  test("Source.single emits one element"):
    val result = interp.eval(
      """
      Source.single(42).runToList()
      """
    )
    assert(result == List(42L))

  test("Source.empty emits nothing"):
    val result = interp.eval(
      """
      Source.empty.runToList()
      """
    )
    assert(result == Nil)

  test("runDrain discards all elements"):
    val result = interp.eval(
      """
      Source.from(1 to 100).runDrain()
      1
      """
    )
    assert(result == 1L)

  test("flatMap chains sources"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3))
        .flatMap(x => Source.from(List(x, x * 10)))
        .runToList()
      """
    )
    assert(result == List(1L, 10L, 2L, 20L, 3L, 30L))

  test("concat sequences two sources"):
    val result = interp.eval(
      """
      Source.from(List(1, 2)).concat(Source.from(List(3, 4))).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L, 4L))

  test("zip pairs elements"):
    val result = interp.eval(
      """
      Source.from(List(1, 2, 3)).zip(Source.from(List(10, 20, 30))).runToList()
      """
    )
    assert(result == List(
      List(1L, 10L),
      List(2L, 20L),
      List(3L, 30L)
    ))

  test("Source.fromGenerator wraps a generator"):
    val result = interp.eval(
      """
      val gen = generator { () =>
        suspend(100)
        suspend(200)
        suspend(300)
      }
      Source.fromGenerator(gen).runToList()
      """
    )
    assert(result == List(100L, 200L, 300L))

  test("backpressure: large stream does not OOM"):
    val result = interp.eval(
      """
      Source.from(1 to 100000).take(3).runToList()
      """
    )
    assert(result == List(1L, 2L, 3L))
