package scalascript.uniml

import org.scalatest.funsuite.AnyFunSuite

final class ProcessorSpec extends AnyFunSuite:
  // A pure processor whose immutable state is a running count. Each input emits
  // its running index; `stop` emits the final count.
  private object Counter extends Processor[Int, String, Int]:
    def start: Int = 0
    def step(state: Int, input: String): Stepped[Int, Int] =
      Stepped(state + 1, ProcessBatch(Vector(state + 1), Vector(diagnostic(s"in-$input"))))
    def stop(state: Int): ProcessBatch[Int] = ProcessBatch(Vector(state), Vector(diagnostic("stop")))

  test("a pure processor threads immutable state and emits batches") {
    var s = Counter.start
    val b1 = Counter.step(s, "a"); s = b1.state
    val b2 = Counter.step(s, "b"); s = b2.state
    val fin = Counter.stop(s)

    assert(b1.state == 1 && b1.batch.values == Vector(1))
    assert(b2.state == 2 && b2.batch.values == Vector(2))
    assert(fin.values == Vector(2))
    assert((b1.batch.diagnostics ++ b2.batch.diagnostics ++ fin.diagnostics).map(_.code)
      == Vector("in-a", "in-b", "stop"))
  }

  test("step is pure — the same state and input give the same result") {
    assert(Counter.step(0, "a") == Counter.step(0, "a"))
  }

  test("ProcessBatch.concat merges values then diagnostics in order") {
    val a = ProcessBatch(Vector(1), Vector(diagnostic("a")))
    val b = ProcessBatch(Vector(2, 3), Vector(diagnostic("b")))
    assert(a.concat(b) == ProcessBatch(Vector(1, 2, 3), Vector(diagnostic("a"), diagnostic("b"))))
  }

  private def diagnostic(code: String): Diagnostic =
    Diagnostic(code, code, Severity.Warning, None)
