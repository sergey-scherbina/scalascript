package scalascript.dap

import ujson.*
import java.net.Socket
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.CountDownLatch
import scalascript.interpreter.debug.{DebugFrame, DebugHooks, StepAction, StopReason}

/** Handles one DAP client connection. Phase 3: step execution (next/stepIn/stepOut).
 *
 *  Threading model:
 *  - The DAP message loop runs on the caller's thread (inside [[run]]).
 *  - The interpreter thread calls [[SessionHooks.onStep]] from a different thread.
 *  - All writes to the socket output stream are protected by [[writeLock]].
 *  - [[readyLatch]] gates interpreter start until `configurationDone` is received.
 *  - [[suspendLatch]] handles per-stop suspend/resume.
 *
 *  Step mode:
 *  - [[stepMode]] is set by `next`/`stepIn`/`stepOut` before calling [[resume]].
 *  - [[stepSkipLine]] is set to the resumed-from line so the interpreter skips
 *    sub-expressions on the same line after a resume.
 *  - [[lastFrame]] remembers the last stopped frame for depth-based step decisions.
 */
final class DapSession(conn: Socket):
  private val in        = conn.getInputStream
  private val out       = conn.getOutputStream
  private val writeLock = Object()
  private var seq       = 0

  private val breakpoints = scalascript.interpreter.debug.BreakpointRegistry()
  private val readyLatch  = CountDownLatch(1)
  private val suspendLatch = AtomicReference[CountDownLatch](null)

  // Phase 3: step mode and associated state.
  private val stepMode     = AtomicReference[StepMode](StepMode.Off)
  private val stepSkipLine = AtomicInteger(-1)   // skip this line when checking step-stop
  private val lastFrame    = AtomicReference[Option[DebugFrame]](None)

  private enum StepMode:
    case Off
    case StepIn
    case StepOver(targetDepth: Int)   // stop when callDepth <= targetDepth
    case StepOut(targetDepth: Int)    // stop when callDepth < targetDepth

  def awaitReady(): Unit = readyLatch.await()

  /** Resume the blocked interpreter thread. Does NOT clear stepMode — callers
   *  must set stepMode before calling resume() so the interpreter picks it up. */
  private def resume(): Unit =
    val latch = suspendLatch.getAndSet(null)
    if latch != null then latch.countDown()

  /** Block the interpreter thread, send a `stopped` event, wait for resume. */
  private def suspendUntilResume(@annotation.unused frame: DebugFrame, reason: String): Unit =
    val latch = CountDownLatch(1)
    suspendLatch.set(latch)
    sendEvent("stopped", Obj(
      "reason"            -> Str(reason),
      "threadId"          -> Num(1),
      "allThreadsStopped" -> True,
    ))
    latch.await()

  def mkHooks(): DebugHooks = new SessionHooks

  private class SessionHooks extends DebugHooks:
    def isBreakpoint(sourceFile: String, line: Int): Boolean =
      breakpoints.contains(sourceFile, line)

    /** Called for every evaluated term. Decides whether to suspend based on
     *  breakpoints and current step mode.
     *
     *  Line dedup: once we stop at line L, [[stepSkipLine]] is set to L so
     *  subsequent sub-expression evals on the same line (same resume cycle)
     *  are skipped. [[stepSkipLine]] is reset only when we stop at a *different*
     *  line, preventing the interpreter from immediately re-stopping on the
     *  same line after a `next`/`stepIn`/`stepOut` resume.
     *
     *  Breakpoints bypass the skip check: a breakpoint on line L must fire even
     *  on revisits (e.g., inside a loop). Within a single stop-cycle, further
     *  sub-expressions on L are still skipped via the `suspendLatch != null` guard
     *  embedded in the single-threaded interpreter flow.
     */
    def onStep(frame: DebugFrame): StepAction =
      val docLine  = frame.line
      val skipLine = stepSkipLine.get()
      // A breakpoint on the resumed-from line is skipped for the current resume cycle.
      // This prevents re-firing on sub-expressions of the same statement after next/stepIn/stepOut.
      // The skip is cleared when we stop at a different line.
      val bp = breakpoints.contains(frame.sourceFile, docLine) && docLine != skipLine
      val sm = stepMode.get()

      val stepShouldStop = sm match
        case StepMode.Off                   => false
        case StepMode.StepIn                => docLine != skipLine
        case StepMode.StepOver(targetDepth) => docLine != skipLine && frame.callDepth <= targetDepth
        case StepMode.StepOut(targetDepth)  => frame.callDepth < targetDepth

      val shouldStop = bp || stepShouldStop

      if shouldStop then
        val reason = if bp then "breakpoint" else "step"
        stepMode.set(StepMode.Off)
        stepSkipLine.set(docLine)
        lastFrame.set(Some(frame))
        suspendUntilResume(frame, reason)
        if bp then StepAction.Stop(StopReason.Breakpoint)
        else StepAction.Stop(StopReason.Step)
      else
        StepAction.Continue

    def onOutput(category: String, msg: String): Unit = ()

  def run(): Unit =
    try
      while !conn.isClosed do
        val msg = DapProtocol.readMessage(in)
        handleMessage(msg)
    catch case _: java.io.EOFException | _: java.io.IOException => ()
    finally
      readyLatch.countDown()
      resume()
      conn.close()

  private def handleMessage(msg: Value): Unit =
    val msgType = msg("type").str
    if msgType == "request" then
      val cmd    = msg("command").str
      val reqSeq = msg("seq").num.toInt
      cmd match
        case "initialize"        => handleInitialize(reqSeq, msg)
        case "launch"            => handleLaunch(reqSeq, msg)
        case "configurationDone" =>
          sendResponse(reqSeq, "configurationDone", Obj())
          readyLatch.countDown()
        case "setBreakpoints"    => handleSetBreakpoints(reqSeq, msg)
        case "continue"          =>
          stepMode.set(StepMode.Off)
          stepSkipLine.set(-1)
          resume()
          sendResponse(reqSeq, "continue", Obj("allThreadsContinued" -> True))
        case "next"              => handleStep(reqSeq, "next"):
          f => StepMode.StepOver(f.callDepth)
        case "stepIn"            => handleStep(reqSeq, "stepIn"):
          _ => StepMode.StepIn
        case "stepOut"           => handleStep(reqSeq, "stepOut"):
          f => StepMode.StepOut(f.callDepth)
        case "pause"             =>
          // Stop at next evaluated line (no resume needed — interpreter is running).
          stepSkipLine.set(-1)
          stepMode.set(StepMode.StepIn)
          sendResponse(reqSeq, "pause", Obj())
        case "threads"           =>
          sendResponse(reqSeq, "threads", Obj(
            "threads" -> Arr(Obj("id" -> Num(1), "name" -> Str("main")))
          ))
        case "disconnect"        => handleDisconnect(reqSeq, msg)
        case other               => sendResponse(reqSeq, other, Obj(), success = false, message = s"unknown command: $other")

  /** Common handler for next/stepIn/stepOut: sets step mode then resumes. */
  private def handleStep(reqSeq: Int, cmd: String)(mkMode: DebugFrame => StepMode): Unit =
    val frame = lastFrame.get().getOrElse(DebugFrame(0, "", "", 0, 0))
    stepSkipLine.set(frame.line)
    stepMode.set(mkMode(frame))
    resume()
    sendResponse(reqSeq, cmd, Obj())

  private def handleInitialize(reqSeq: Int, @annotation.unused msg: Value): Unit =
    val caps = Obj(
      "supportsConfigurationDoneRequest" -> True,
      "supportTerminateDebuggee"         -> True,
      "supportsStepBack"                 -> False,
    )
    sendResponse(reqSeq, "initialize", caps)
    sendEvent("initialized", Obj())

  private def handleLaunch(reqSeq: Int, @annotation.unused msg: Value): Unit =
    sendResponse(reqSeq, "launch", Obj())

  private def handleSetBreakpoints(reqSeq: Int, msg: Value): Unit =
    val args  = msg("arguments")
    val src   = args("source")("path").str
    val bpArr = args("breakpoints").arr
    val lines = bpArr.map(_("line").num.toInt).toSet
    breakpoints.setBreakpoints(src, lines)
    val verified = lines.toSeq.map(l => Obj("line" -> Num(l), "verified" -> True))
    sendResponse(reqSeq, "setBreakpoints", Obj("breakpoints" -> Arr(verified*)))

  private def handleDisconnect(reqSeq: Int, @annotation.unused msg: Value): Unit =
    resume()
    sendResponse(reqSeq, "disconnect", Obj())
    sendEvent("terminated", Obj())
    conn.close()

  private[dap] def sendResponse(reqSeq: Int, command: String, body: Value,
                         success: Boolean = true, message: String = ""): Unit =
    writeLock.synchronized {
      seq += 1
      val resp = Obj(
        "seq"         -> Num(seq),
        "type"        -> Str("response"),
        "request_seq" -> Num(reqSeq),
        "success"     -> (if success then True else False),
        "command"     -> Str(command),
        "body"        -> body,
      )
      if message.nonEmpty then resp("message") = Str(message)
      DapProtocol.writeMessage(out, resp)
    }

  private[dap] def sendEvent(event: String, body: Value): Unit =
    writeLock.synchronized {
      seq += 1
      val evt = Obj(
        "seq"   -> Num(seq),
        "type"  -> Str("event"),
        "event" -> Str(event),
        "body"  -> body,
      )
      DapProtocol.writeMessage(out, evt)
    }
