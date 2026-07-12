package scalascript.uniml

import scala.collection.mutable.ArrayBuffer

final case class ProcessBatch[+A](values: Vector[A], diagnostics: Vector[Diagnostic]):
  def map[B](f: A => B): ProcessBatch[B] = ProcessBatch(values.map(f), diagnostics)

object ProcessBatch:
  def empty[A]: ProcessBatch[A] = ProcessBatch(Vector.empty, Vector.empty)

  def value[A](value: A): ProcessBatch[A] = ProcessBatch(Vector(value), Vector.empty)

trait Processor[I, O]:
  def push(input: I): ProcessBatch[O]

  def finish(): ProcessBatch[O]

  final def andThen[P](next: Processor[O, P]): Processor[I, P] =
    val upstream = this
    new Processor[I, P]:
      private var finished = false

      def push(input: I): ProcessBatch[P] =
        if finished then ProcessBatch(Vector.empty, Vector(Processor.afterFinishDiagnostic))
        else forward(upstream.push(input))

      def finish(): ProcessBatch[P] =
        if finished then ProcessBatch(Vector.empty, Vector(Processor.afterFinishDiagnostic))
        else
          finished = true
          val forwarded = forward(upstream.finish())
          val downstream = next.finish()
          ProcessBatch(
            forwarded.values ++ downstream.values,
            forwarded.diagnostics ++ downstream.diagnostics,
          )

      private def forward(batch: ProcessBatch[O]): ProcessBatch[P] =
        val values = Vector.newBuilder[P]
        val diagnostics = ArrayBuffer.empty[Diagnostic]
        diagnostics ++= batch.diagnostics
        batch.values.foreach { value =>
          val nextBatch = next.push(value)
          values ++= nextBatch.values
          diagnostics ++= nextBatch.diagnostics
        }
        ProcessBatch(values.result(), diagnostics.toVector)

object Processor:
  private val afterFinishDiagnostic = Diagnostic(
    code = "uniml.processor.finished",
    message = "processor cannot accept input or finish more than once",
    severity = Severity.Error,
    span = None,
  )

  def stateless[I, O](f: I => ProcessBatch[O]): Processor[I, O] =
    new Processor[I, O]:
      private var finished = false

      def push(input: I): ProcessBatch[O] =
        if finished then ProcessBatch(Vector.empty, Vector(afterFinishDiagnostic))
        else f(input)

      def finish(): ProcessBatch[O] =
        if finished then ProcessBatch(Vector.empty, Vector(afterFinishDiagnostic))
        else
          finished = true
          ProcessBatch.empty
