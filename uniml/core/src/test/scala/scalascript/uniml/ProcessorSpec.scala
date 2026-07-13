package scalascript.uniml

import org.scalatest.funsuite.AnyFunSuite

final class ProcessorSpec extends AnyFunSuite:
  test("processor composition forwards values, diagnostics, and finish output in order") {
    val upstream = new Processor[Int, Int]:
      def push(input: Int): ProcessBatch[Int] =
        ProcessBatch(Vector(input, input + 1), Vector(diagnostic(s"up-$input")))

      def finish(): ProcessBatch[Int] = ProcessBatch(Vector(99), Vector(diagnostic("up-finish")))

    val downstream = new Processor[Int, String]:
      def push(input: Int): ProcessBatch[String] =
        ProcessBatch(Vector(s"v$input"), Vector(diagnostic(s"down-$input")))

      def finish(): ProcessBatch[String] = ProcessBatch(Vector("done"), Vector(diagnostic("down-finish")))

    val pipeline = upstream.andThen(downstream)
    val first = pipeline.push(1)
    assert(first.values == Vector("v1", "v2"))
    assert(first.diagnostics.map(_.code) == Vector("up-1", "down-1", "down-2"))

    val last = pipeline.finish()
    assert(last.values == Vector("v99", "done"))
    assert(last.diagnostics.map(_.code) == Vector("up-finish", "down-99", "down-finish"))
  }

  private def diagnostic(code: String): Diagnostic =
    Diagnostic(code, code, Severity.Warning, None)
