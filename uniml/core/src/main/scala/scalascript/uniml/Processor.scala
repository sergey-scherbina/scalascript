package scalascript.uniml

/** A batch produced by one step of a processor: the emitted values plus any
  * diagnostics. Immutable. */
final case class ProcessBatch[A](values: Vector[A], diagnostics: Vector[Diagnostic]):
  def map[B](f: A => B): ProcessBatch[B] = ProcessBatch(values.map(f), diagnostics)

  /** Concatenate two batches (values then values, diagnostics then diagnostics). */
  def concat(other: ProcessBatch[A]): ProcessBatch[A] =
    ProcessBatch(values ++ other.values, diagnostics ++ other.diagnostics)

object ProcessBatch:
  def empty[A]: ProcessBatch[A] = ProcessBatch(Vector.empty, Vector.empty)

  def value[A](value: A): ProcessBatch[A] = ProcessBatch(Vector(value), Vector.empty)

/** The result of one `step`: the next (immutable) state and the batch it emitted. */
final case class Stepped[S, O](state: S, batch: ProcessBatch[O])

/** A pure, incremental processor. All state is the immutable `S` value, threaded
  * by the driver — there are **no mutable object fields**. `step` folds one input
  * into the next state plus a batch; `stop` flushes the final state. This keeps a
  * genuine streaming/incremental interface while staying purely functional (so the
  * same source compiles both with scalac and with the ScalaScript v2 frontend —
  * see specs/uniml-portable-gapmap.md). */
trait Processor[S, I, O]:
  def start: S
  def step(state: S, input: I): Stepped[S, O]
  def stop(state: S): ProcessBatch[O]
