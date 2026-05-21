package scalascript.interpreter.debug

trait DebugHooks:
  def onStep(frame: DebugFrame): StepAction
  def isBreakpoint(sourceFile: String, line: Int): Boolean
  def onOutput(category: String, msg: String): Unit

object DebugHooks:
  val NoOp: DebugHooks = new DebugHooks:
    def onStep(frame: DebugFrame) = StepAction.Continue
    def isBreakpoint(s: String, l: Int) = false
    def onOutput(cat: String, msg: String) = ()

enum StepAction:
  case Continue
  case Stop(reason: StopReason)

enum StopReason:
  case Breakpoint, Step, Pause, Entry

case class DebugFrame(
  id:         Int,
  name:       String,
  sourceFile: String,
  line:       Int,
  callDepth:  Int = 0,
  locals:     Map[String, scalascript.interpreter.Value] = Map.empty,
)
