package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.logging.Logger
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
  private val log = Logger(getClass)

  def id:              String                              = "int"
  def displayName:     String                              = "Interpreter (tree-walking)"
  def spiVersion:      String                              = SpiVersion.Current
  def capabilities:    Capabilities                        = InterpreterCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = InterpreterIntrinsics
  def acceptedSources: Set[String]                         = Set("scala", "html", "css")
  override def markupCodec: Option[scalascript.markup.MarkupCodec] = Some(scalascript.markup.JvmMarkupCodec)

  /** One-shot run.  Streams stdout / stderr directly through the JVM's
   *  System streams — `Executed.stdout` / `.stderr` come back empty by
   *  design (output is already on the wire).  Callers that need
   *  capture wrap stdout via `System.setOut(...)` before invoking.
   *
   *  Intrinsics from `InterpreterIntrinsics` are installed by the
   *  `Interpreter` constructor itself via `installNativeIntrinsics` in
   *  `initBuiltins`, so the bare `Interpreter()` path (REPL,
   *  renderCommand, buildCommand) and the `InterpreterBackend.compile`
   *  path share the same intrinsic surface. */
  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val astModule = Denormalize(module)
    val baseDir   = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val exit =
      try
        val interp = Interpreter(baseDir = baseDir)
        opts.extra.get("frontendName")
          .foreach(n => interp.injectGlobal("_ssc_frontend_name", Value.StringV(n)))
        interp.run(astModule)
        0
      catch case t: Throwable =>
        log.error(t.getMessage, t)
        1
    CompileResult.Executed(stdout = "", stderr = "", exit = exit)

  /** wide-jit C-2: in-process run on the ORIGINAL `ast.Module` (whose
   *  `Content.CodeBlock.tree` is already populated from the first parse),
   *  skipping the `Normalize`→`Denormalize` round-trip and its source re-parse.
   *  Same interpreter setup as `compile`; `SectionRuntime` consumes `cb.tree`
   *  directly, so no re-parse occurs. Used by the CLI `run` fast-path for `"int"`
   *  so a later phase can key a static-type map on the very trees the JIT compiles. */
  def compileAstModule(
      astModule: scalascript.ast.Module,
      opts: BackendOptions,
      nodeTypes: java.util.Map[scala.meta.Tree, scalascript.typer.SType] =
        java.util.Collections.emptyMap[scala.meta.Tree, scalascript.typer.SType]()
  ): CompileResult =
    val baseDir = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val exit =
      try
        val interp = Interpreter(baseDir = baseDir)
        interp.nodeTypes = nodeTypes // wide-jit C-3: static types for the JIT (empty = no-op)
        opts.extra.get("frontendName")
          .foreach(n => interp.injectGlobal("_ssc_frontend_name", Value.StringV(n)))
        interp.run(astModule)
        0
      catch case t: Throwable =>
        log.error(t.getMessage, t)
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
