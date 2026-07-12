package scalascript.uniml

import scala.collection.mutable.ArrayBuffer

final case class ParseResult(
    roots: Vector[UniNode],
    diagnostics: Vector[Diagnostic],
    status: CompletionStatus,
)

object UniML:
  def parse(
      source: SourceInput,
      dialect: DialectAdapter,
      limits: Limits = Limits.default,
  ): ParseResult =
    val pipeline = dialect.instructions(source).andThen(TreeVm(limits))
    val roots = Vector.newBuilder[UniNode]
    val diagnostics = ArrayBuffer.empty[Diagnostic]
    source.chunks.foreach { chunk =>
      val batch = pipeline.push(chunk)
      roots ++= batch.values
      diagnostics ++= batch.diagnostics
    }
    val finalBatch = pipeline.finish()
    roots ++= finalBatch.values
    diagnostics ++= finalBatch.diagnostics
    val allDiagnostics = diagnostics.toVector
    val status =
      if allDiagnostics.exists(_.severity == Severity.Fatal) then CompletionStatus.Halted
      else if allDiagnostics.exists(_.severity == Severity.Error) then CompletionStatus.Incomplete
      else CompletionStatus.Complete
    ParseResult(roots.result(), allDiagnostics, status)
