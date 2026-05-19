package scalascript.codegen

import scalascript.ast
import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.Denormalize

/** Backend SPI adapter for the Node.js target (target id `"node"`).
 *
 *  Pipeline:
 *
 *    1. Collect every `node.js` / `node` opaque-executable fenced
 *       block in document order — concatenated verbatim as the
 *       *glue prefix*.
 *    2. Run `JsGen.generate` over the rest (the `scalascript` /
 *       `scala` blocks via the existing JS code generator, with
 *       html / css / javascript string blocks rendered as values).
 *    3. Emit `<runtime preamble>\n<glue prefix>\n<jsgen output>` as
 *       a single `.mjs`-ready JavaScript source.
 *
 *  No JS parser is invoked on the glue blocks — they are linked, not
 *  compiled.  Phase 4 of v1.25 wires `extern def` declarations to
 *  `globalThis.<name>` so ScalaScript can call into JS-defined
 *  symbols; for Phase 3b the contract is purely "you can ship JS
 *  bytes alongside your ssc". */
class NodeBackend extends Backend:
  def id:              String                               = "node"
  def displayName:     String                               = "Node.js"
  def spiVersion:      String                               = SpiVersion.Current
  def capabilities:    Capabilities                         = NodeCapabilities
  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = JsIntrinsics
  def acceptedSources: Set[String]                          = Set.empty

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    val gluePrefix = collectNodeGlue(module)
    val astModule  = Denormalize(module)
    val baseDir    = opts.baseDir.map(p => os.Path(p.toAbsolutePath.toString))
    val caps       = JsGen.detectCapabilities(astModule, baseDir, intrinsics)
    val jsRuntime  = JsGen.generateRuntime(caps)
    val js         = JsGen.generate(astModule, baseDir, intrinsics)
    val parts      = List(jsRuntime, gluePrefix, js, NodeFlushEpilogue).filter(_.nonEmpty)
    val code       = parts.mkString("\n")
    CompileResult.TextOutput(code = code, language = "javascript", sources = Nil)

  /** JsRuntime's `_println` buffers into `_output: string[]`; nothing in the
   *  user code flushes it.  For browser/JsBackend builds the host flushes
   *  on its own schedule (DOM render, SSR pickup, etc.).  For the Node
   *  target the natural sink is stdout — emit the flush after all user
   *  code has executed so the program does what `println` looks like
   *  it does. */
  private val NodeFlushEpilogue: String =
    """|if (typeof process !== 'undefined' && process.stdout) {
       |  for (const _line of _output) process.stdout.write(_line + '\n');
       |}
       |""".stripMargin

  /** Walk the module's IR in document order and concatenate the
   *  source of every `node.js` / `node` `EmbeddedBlock`.  An empty
   *  string when no such block exists — the resulting bundle is
   *  then equivalent to a plain JS-backend compile. */
  private def collectNodeGlue(module: ir.NormalizedModule): String =
    val sb = StringBuilder()

    def walkContent(c: ir.Content): Unit = c match
      case ir.Content.EmbeddedBlock(language, source, _) if ast.Lang.isNode(language) =>
        if sb.nonEmpty then sb.append("\n")
        sb.append(source.stripTrailing())
      case _ => ()

    def walkSection(s: ir.Section): Unit =
      s.content.foreach(walkContent)
      s.subsections.foreach(walkSection)

    module.sections.foreach(walkSection)
    sb.toString
