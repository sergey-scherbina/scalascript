package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Backend SPI adapter for the tree-walking Interpreter (target id `"int"`).
 *
 *  Implements `InteractiveBackend` because the interpreter underpins
 *  `ssc serve` — handlers are invoked per HTTP request through a
 *  long-lived `Interpreter` instance.
 *
 *  Stage 5.1: one-shot `compile` runs the module to completion and
 *  returns `CompileResult.Executed` with captured stdout / stderr.
 *  `openSession` exposes the live interpreter for incremental use. */
class InterpreterBackend extends InteractiveBackend:
  def id:              String                              = "int"
  def displayName:     String                              = "Interpreter (tree-walking)"
  def spiVersion:      String                              = SpiVersion.Current
  def capabilities:    Capabilities                        = InterpreterCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                         = Set("scala", "html", "css")

  /** One-shot run.  Streams stdout / stderr directly through the JVM's
   *  System streams — `Executed.stdout` / `.stderr` come back empty by
   *  design (output is already on the wire).  Callers that need
   *  capture wrap stdout via `System.setOut(...)` before invoking. */
  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule = Denormalize(module)
    val baseDir   = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val exit =
      try
        Interpreter.run(astModule, System.out, baseDir)
        0
      catch case t: Throwable =>
        System.err.println(t.getMessage)
        1
    CompileResult.Executed(stdout = "", stderr = "", exit = exit)

  def openSession(opts: BackendOptions): Session =
    val baseDir = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    new InterpreterSession(Interpreter(baseDir = baseDir, headless = false))

/** Session adapter for the live `Interpreter`.  Used by `ssc serve`
 *  (handler invocation per request).  Stage 5.4+ moves the WS / HTTP
 *  wiring through `Backend.intrinsics`; for now the session just owns
 *  the interpreter instance. */
class InterpreterSession(val interp: Interpreter) extends Session:
  def feed(block: ir.NormalizedBlock): CompileResult =
    block match
      case ir.Content.CodeBlock(source, _, _) =>
        interp.runSnippet(source)
        CompileResult.Executed("", "", 0)
      case other =>
        CompileResult.Failed(List(Diagnostic.Generic(
          message = s"InterpreterSession.feed: unsupported block ${other.getClass.getSimpleName}",
          source  = None
        )))

  def invokeHandler(handlerRef: ir.SymbolRef, args: List[ir.Value]): ir.Value =
    // Stage 5.1: handler lookup goes through the interpreter's
    // existing globals; ir.Value plumbing is a Stage 5.4+ change.
    throw UnsupportedOperationException(
      s"InterpreterSession.invokeHandler not yet implemented (Stage 5.4 — Value/SymbolRef bridging)"
    )

  def close(): Unit = ()
