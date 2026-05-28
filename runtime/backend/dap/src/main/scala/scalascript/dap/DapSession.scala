package scalascript.dap

import ujson.*
import java.net.Socket
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, ConcurrentHashMap}
import scalascript.interpreter.{Value => IValue}
import scalascript.interpreter.debug.{DebugFrame, DebugHooks, StepAction, StopReason}

/** Handles one DAP client connection. Phase 5: stack frames + source mapping (stackTrace).
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
 *  - [[lastFrame]] remembers the last stopped frame for depth-based step decisions
 *    and also carries the locals snapshot for variable inspection.
 *
 *  Variable registry:
 *  - [[varRefCounter]] allocates variablesReference integers (non-zero = has children).
 *  - [[varRegistry]] maps each reference to its (name, Value) pairs.
 *  - Registry is reset on each [[handleScopes]] call so stale refs from a previous
 *    stop can't be dereferenced after the interpreter resumes.
 */
final class DapSession(conn: Socket):
  private val in        = conn.getInputStream
  private val out       = conn.getOutputStream
  private val writeLock = Object()
  private var seq       = 0

  private val breakpoints  = scalascript.interpreter.debug.BreakpointRegistry()
  private val readyLatch   = CountDownLatch(1)
  private val suspendLatch = AtomicReference[CountDownLatch](null)

  // Phase 3: step mode and associated state.
  private val stepMode     = AtomicReference[StepMode](StepMode.Off)
  private val stepSkipLine = AtomicInteger(-1)
  private val lastFrame    = AtomicReference[Option[DebugFrame]](None)

  // Phase 4: variable registry.
  private val varRefCounter = AtomicInteger(0)
  private val varRegistry   = ConcurrentHashMap[Int, IndexedSeq[(String, IValue)]]()

  private enum StepMode:
    case Off
    case StepIn
    case StepOver(targetDepth: Int)
    case StepOut(targetDepth: Int)

  def awaitReady(): Unit = readyLatch.await()

  private def resume(): Unit =
    val latch = suspendLatch.getAndSet(null)
    if latch != null then latch.countDown()

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

    def onStep(frame: DebugFrame): StepAction =
      val docLine  = frame.line
      val skipLine = stepSkipLine.get()
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
        case "next"    => handleStep(reqSeq, "next")(f => StepMode.StepOver(f.callDepth))
        case "stepIn"  => handleStep(reqSeq, "stepIn")(_ => StepMode.StepIn)
        case "stepOut" => handleStep(reqSeq, "stepOut")(f => StepMode.StepOut(f.callDepth))
        case "pause"  =>
          stepSkipLine.set(-1)
          stepMode.set(StepMode.StepIn)
          sendResponse(reqSeq, "pause", Obj())
        case "threads" =>
          sendResponse(reqSeq, "threads", Obj(
            "threads" -> Arr(Obj("id" -> Num(1), "name" -> Str("main")))
          ))
        case "stackTrace" => handleStackTrace(reqSeq)
        case "scopes"     => handleScopes(reqSeq, msg)
        case "variables"  => handleVariables(reqSeq, msg)
        case "disconnect" => handleDisconnect(reqSeq, msg)
        case other        => sendResponse(reqSeq, other, Obj(), success = false, message = s"unknown command: $other")

  private def handleStep(reqSeq: Int, cmd: String)(mkMode: DebugFrame => StepMode): Unit =
    val frame = lastFrame.get().getOrElse(DebugFrame(0, "", "", 0, 0))
    stepSkipLine.set(frame.line)
    stepMode.set(mkMode(frame))
    resume()
    sendResponse(reqSeq, cmd, Obj())

  // ─── Stack trace ──────────────────────────────────────────────────────────

  private def handleStackTrace(reqSeq: Int): Unit =
    val stopped = lastFrame.get()
    // Build innermost-first list: stopped frame + callers from callStack (reversed).
    val frames: Seq[Value] = stopped match
      case None => Seq.empty
      case Some(f) =>
        val callers = f.callFrames.reverseIterator.zipWithIndex.map { case (entry, i) =>
          Obj(
            "id"     -> Num(i + 1),
            "name"   -> Str(entry.name),
            "source" -> Obj("path" -> Str(entry.sourceFile)),
            "line"   -> Num(entry.line),
            "column" -> Num(1),
          )
        }.toSeq
        val top = Obj(
          "id"     -> Num(0),
          "name"   -> Str(f.name),
          "source" -> Obj("path" -> Str(f.sourceFile)),
          "line"   -> Num(f.line),
          "column" -> Num(1),
        )
        top +: callers
    sendResponse(reqSeq, "stackTrace", Obj(
      "stackFrames" -> Arr(frames*),
      "totalFrames" -> Num(frames.length),
    ))

  // ─── Variable inspection ──────────────────────────────────────────────────

  /** Reset the variable registry and allocate a new ref for the given pairs. */
  private def allocRef(pairs: IndexedSeq[(String, IValue)]): Int =
    val ref = varRefCounter.incrementAndGet()
    varRegistry.put(ref, pairs)
    ref

  /** Allocate a variablesReference for a Value's children, or 0 if leaf. */
  private def childrenRef(v: IValue): Int = v match
    case IValue.InstanceV(_, fields) =>
      allocRef(fields.toIndexedSeq.sortBy(_._1))
    case IValue.ListV(items) =>
      allocRef(items.zipWithIndex.map { case (v, i) => (s"[$i]", v) }.toIndexedSeq)
    case IValue.MapV(entries) =>
      allocRef(entries.toIndexedSeq.map { case (k, v) => (IValue.show(k), v) })
    case IValue.TupleV(elems) =>
      allocRef(elems.zipWithIndex.map { case (v, i) => (s"_${i + 1}", v) }.toIndexedSeq)
    case IValue.OptionV(Some(inner)) =>
      allocRef(IndexedSeq(("value", inner)))
    case _ => 0

  /** Convert one (name, Value) pair to a DAP Variable JSON object. */
  private def valueToDap(name: String, v: IValue): Value =
    val (display, typeName) = v match
      case IValue.IntV(n)            => (n.toString, "Int")
      case IValue.DoubleV(d)         =>
        val s = if d == d.toLong.toDouble then d.toLong.toString else d.toString
        (s, "Double")
      case IValue.StringV(s)         => (s""""$s"""", "String")
      case IValue.BoolV(b)           => (b.toString, "Boolean")
      case IValue.CharV(c)           => (s"'$c'", "Char")
      case IValue.UnitV              => ("()", "Unit")
      case IValue.NullV              => ("null", "Null")
      case IValue.FunV(ps, _, _, n, _, _, _, _) =>
        val nm = if n.nonEmpty then n else "<anon>"
        (s"<function($nm/${ps.length})>", "Function")
      case IValue.NativeFnV(nm, _)   => (s"<native:$nm>", "Function")
      case IValue.InstanceV(t, fs)   =>
        val preview = if fs.isEmpty then t else s"$t { ${fs.size} field(s) }"
        (preview, t)
      case IValue.ListV(items)       => (s"List(${items.length})", "List")
      case IValue.MapV(m)            => (s"Map(${m.size})", "Map")
      case IValue.TupleV(elems)      => (s"Tuple${elems.length}", s"Tuple${elems.length}")
      case IValue.NoneV      => ("None", "Option")
      case IValue.OptionV(Some(i))   => (s"Some(${IValue.show(i)})", "Option")
      case IValue.DocV(_)            => ("<doc>", "Doc")
      case IValue.MarkupV(_)         => ("<markup>", "Markup")
      case IValue.Foreign(t, _)      => (s"<foreign:$t>", t)
    Obj(
      "name"               -> Str(name),
      "value"              -> Str(display),
      "type"               -> Str(typeName),
      "variablesReference" -> Num(childrenRef(v)),
    )

  /** Filter env snapshot to user-visible variables (drop built-in native fns and internal names). */
  private def visibleLocals(env: Map[String, IValue]): IndexedSeq[(String, IValue)] =
    env.toIndexedSeq
      .filter { case (name, value) =>
        !name.startsWith("_") &&
        !name.startsWith("$") &&
        !value.isInstanceOf[IValue.NativeFnV]
      }
      .sortBy(_._1)

  private def handleScopes(reqSeq: Int, @annotation.unused msg: Value): Unit =
    // Reset variable registry for this stop — client will fetch fresh refs.
    varRefCounter.set(0)
    varRegistry.clear()
    val locals    = lastFrame.get().map(f => visibleLocals(f.locals)).getOrElse(IndexedSeq.empty)
    val localsRef = allocRef(locals)
    sendResponse(reqSeq, "scopes", Obj(
      "scopes" -> Arr(Obj(
        "name"               -> Str("Locals"),
        "variablesReference" -> Num(localsRef),
        "presentationHint"   -> Str("locals"),
        "expensive"          -> False,
      ))
    ))

  private def handleVariables(reqSeq: Int, msg: Value): Unit =
    val ref   = msg("arguments")("variablesReference").num.toInt
    val pairs = Option(varRegistry.get(ref)).getOrElse(IndexedSeq.empty)
    val dapVars = pairs.map { case (name, value) => valueToDap(name, value) }
    sendResponse(reqSeq, "variables", Obj("variables" -> Arr(dapVars.toSeq*)))

  // ─── Protocol helpers ─────────────────────────────────────────────────────

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
