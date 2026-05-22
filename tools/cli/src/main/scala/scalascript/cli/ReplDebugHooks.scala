package scalascript.cli

import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scalascript.interpreter.debug.{BreakpointRegistry, DebugFrame, DebugHooks, StepAction}

/** Interactive debugger hooks for `ssc repl`.
 *
 *  The interpreter runs on a background virtual thread.  When it hits a
 *  breakpoint or step stop, it puts the frame on [[stoppedQueue]] and blocks
 *  on an internal latch.  The REPL main thread drains [[stoppedQueue]],
 *  shows the `(debug) ` sub-prompt, and calls [[resume]] to release the latch.
 *
 *  @param blockDocLine  0-based doc-line of the first user code line inside a
 *                       REPL snippet.  For the standard template
 *                       `# Snippet\n\n```scala\n…` this is always 3.
 */
final class ReplDebugHooks(val blockDocLine: Int = 3):
  import ReplDebugHooks.StepMode

  private val registry    = BreakpointRegistry()
  private val bpLines     = collection.mutable.Set[Int]()

  private val _stepMode    = AtomicReference[StepMode](StepMode.Off)
  private val stepSkipLine = AtomicInteger(-1)
  private val suspendLatch = AtomicReference[CountDownLatch](null)

  /** Last stopped frame; read by the REPL main thread for locals/stack display. */
  val lastFrame = AtomicReference[Option[DebugFrame]](None)

  /** Main thread polls this queue: `Some(frame)` = stopped, `None` = finished. */
  val stoppedQueue = LinkedBlockingQueue[Option[DebugFrame]]()

  // ── Breakpoints ─────────────────────────────────────────────────────────

  private def toDocLine(userLine: Int): Int = blockDocLine + userLine

  def setBreakpoint(userLine: Int): Unit =
    bpLines += userLine
    registry.setBreakpoints("<repl>", bpLines.map(toDocLine).toSet)

  def clearBreakpoint(userLine: Int): Unit =
    bpLines -= userLine
    registry.setBreakpoints("<repl>", bpLines.map(toDocLine).toSet)

  def clearAllBreakpoints(): Unit =
    bpLines.clear()
    registry.clear()

  def listBreakpoints: List[Int] = bpLines.toList.sorted

  def hasBreakpoints: Boolean = bpLines.nonEmpty

  // ── Step mode ────────────────────────────────────────────────────────────

  def enableStepIn(): Unit = _stepMode.set(StepMode.StepIn)

  def clearStepMode(): Unit = _stepMode.set(StepMode.Off)

  def isDebugActive: Boolean =
    bpLines.nonEmpty || _stepMode.get() != StepMode.Off

  def resume(next: StepMode = StepMode.Off): Unit =
    _stepMode.set(next)
    stepSkipLine.set(lastFrame.get().map(_.line).getOrElse(-1))
    val latch = suspendLatch.getAndSet(null)
    if latch != null then latch.countDown()

  /** Reset transient state at the start of each snippet run.
   *  Step mode is intentionally preserved so `:step` entered at the `ssc>` prompt
   *  carries through to the immediately following snippet. */
  def resetForNewSnippet(): Unit =
    lastFrame.set(None)
    stoppedQueue.clear()
    val latch = suspendLatch.getAndSet(null)
    if latch != null then latch.countDown()

  /** Signal that the interpreter thread has finished (or crashed). */
  def signalFinished(): Unit = stoppedQueue.put(None)

  // ── DebugHooks factory ───────────────────────────────────────────────────

  def mkHooks(): DebugHooks = new HooksImpl

  private class HooksImpl extends DebugHooks:
    def isBreakpoint(sourceFile: String, line: Int): Boolean =
      registry.contains(sourceFile, line)

    def onOutput(cat: String, msg: String): Unit = ()

    def onStep(frame: DebugFrame): StepAction =
      val mode     = _stepMode.get()
      val skipLine = stepSkipLine.get()
      val stop = mode match
        case StepMode.Off               => isBreakpoint(frame.sourceFile, frame.line)
        case StepMode.StepIn            => frame.line != skipLine
        case StepMode.StepOver(td)      => frame.callDepth <= td && frame.line != skipLine
        case StepMode.StepOut(td)       => frame.callDepth <  td && frame.line != skipLine
      if stop then
        _stepMode.set(StepMode.Off)
        stepSkipLine.set(-1)
        lastFrame.set(Some(frame))
        val latch = CountDownLatch(1)
        suspendLatch.set(latch)
        stoppedQueue.put(Some(frame))
        try latch.await()
        catch case _: InterruptedException => ()
      StepAction.Continue

object ReplDebugHooks:
  enum StepMode:
    case Off
    case StepIn
    case StepOver(targetDepth: Int)
    case StepOut(targetDepth: Int)
