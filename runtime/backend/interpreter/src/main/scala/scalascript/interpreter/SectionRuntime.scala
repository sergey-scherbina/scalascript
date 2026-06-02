package scalascript.interpreter

import scalascript.ast.*
import scala.collection.mutable
import scala.meta.*

/** Section / block execution: runSection, SQL blocks, HTML/CSS blocks,
 *  imports, and statement dispatch (`execBlock` / `execBlockStats`).
 */
private[interpreter] object SectionRuntime:

  private val inlineImportPat =
    """^\s*// list-import: \[([^\]]+)\]\(([^)]+)\)\s*$""".r

  private def runInlineImports(source: String, interp: Interpreter): Unit =
    if !source.contains("](") then return
    import scalascript.parser.Parser
    val preprocessed = Parser.rewriteInlineImports(source)
    if !preprocessed.contains("// list-import:") then return
    val asPattern = """^([A-Za-z_]\w*)\s+as\s+([A-Za-z_]\w*)$""".r
    preprocessed.linesIterator.foreach { line =>
      inlineImportPat.findFirstMatchIn(line).foreach { m =>
        val bindingStr = m.group(1)
        val path       = m.group(2).trim
        val bindings = bindingStr.split(",").map(_.trim).filter(_.nonEmpty).map { s =>
          s.trim match
            case asPattern(name, alias) => ImportBinding(name, alias = Some(alias))
            case bare                   => ImportBinding(bare)
        }.toList
        if bindings.nonEmpty && path.nonEmpty then
          runImport(Content.Import(path, bindings), interp)
      }
    }

  def runSection(section: Section, interp: Interpreter): Unit =
    section.content.foreach {
      case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
        interp.currentSource = cb.source
        interp.lineOffset = cb.tree match
          case Some(t) => ScalaNode.fold(t) {
            case _: Term.Block => 1
            case _             => 0
          }
          case None => 0
        // Phase 2 DAP: record document-level line where this code block starts.
        interp.debugBlockDocLine = cb.lineOffset
        runInlineImports(cb.source, interp)
        cb.tree.foreach(t => execBlock(t, interp))
      case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
        runStringBlock(cb, section, interp)
      case cb: Content.CodeBlock if Lang.isSql(cb.lang) =>
        runSqlBlock(cb, section, interp)
      case cb: Content.CodeBlock if Lang.isTransaction(cb.lang) =>
        runTransactionBlock(cb, section, interp)
      case cb: Content.CodeBlock if Lang.isXml(cb.lang) =>
        runXmlBlock(cb, section, interp)
      case cb: Content.CodeBlock if Lang.isGraphql(cb.lang) =>
        interp.ensurePluginsLoaded()
        interp.graphqlBlockRunner.getOrElse(
          throw InterpretError("No GraphQL block runner installed — add graphql-plugin to the interpreter classpath")
        ).registerSdl(cb.source)
      case imp: Content.Import =>
        runImport(imp, interp)
      case _ => ()
    }
    section.subsections.foreach(s => runSection(s, interp))

  def runSqlBlock(cb: Content.CodeBlock, section: Section, interp: Interpreter): Unit =
    interp.ensurePluginsLoaded()
    val runner = interp.sqlBlockRunner.getOrElse(
      throw InterpretError("No SQL block runner installed — add the SQL plugin to the interpreter classpath")
    )
    val ctx = new scalascript.backend.spi.SqlBlockContext:
      def evalExpression(source: String): Any =
        val expr = scala.meta.dialects.Scala3(scala.meta.Input.VirtualFile(
          "<sql-bind>", source
        )).parse[scala.meta.Term].get
        Computation.run(interp.eval(expr, interp.globals.toMap))
      def global(name: String): Option[Any] =
        interp.globals.get(name)
      def dbConnect(dbName: String): java.sql.Connection =
        interp.sqlRegistry.connect(dbName)
      def withTransaction[A](dbName: String)(run: java.sql.Connection => A): A =
        interp.sqlRegistry.withTransaction(dbName)(run)
    val resultValue = runner.run(cb.source, cb.attrs, ctx) match
      case v: Value => v
      case other    => Value.StringV(String.valueOf(other))
    val ordinal = interp.sqlBlockCounter
    interp.sqlBlockCounter += 1
    interp.globals(s"_sqlBlock_$ordinal") = resultValue
    sectionIdent(section.heading.text).foreach { id =>
      val existing = interp.globals.get(id) match
        case Some(Value.InstanceV(_, fields)) => fields
        case _                                => Map.empty[String, Value]
      interp.globals(id) = Value.InstanceV(id, existing + ("sql" -> resultValue))
    }

  def runTransactionBlock(cb: Content.CodeBlock, section: Section, interp: Interpreter): Unit =
    interp.ensurePluginsLoaded()
    val runner = interp.sqlBlockRunner.getOrElse(
      throw InterpretError("No SQL block runner installed — add the SQL plugin to the interpreter classpath")
    )
    val ctx = new scalascript.backend.spi.SqlBlockContext:
      def evalExpression(source: String): Any =
        val expr = scala.meta.dialects.Scala3(scala.meta.Input.VirtualFile(
          "<tx-bind>", source
        )).parse[scala.meta.Term].get
        Computation.run(interp.eval(expr, interp.globals.toMap))
      def global(name: String): Option[Any] =
        interp.globals.get(name)
      def dbConnect(dbName: String): java.sql.Connection =
        interp.sqlRegistry.connect(dbName)
      def withTransaction[A](dbName: String)(run: java.sql.Connection => A): A =
        interp.sqlRegistry.withTransaction(dbName)(run)
    val resultValue = runner.runTransaction(cb.source, cb.attrs, ctx) match
      case v: Value => v
      case other    => Value.StringV(String.valueOf(other))
    val ordinal = interp.sqlBlockCounter
    interp.sqlBlockCounter += 1
    interp.globals(s"_sqlBlock_$ordinal") = resultValue
    sectionIdent(section.heading.text).foreach { id =>
      val existing = interp.globals.get(id) match
        case Some(Value.InstanceV(_, fields)) => fields
        case _                                => Map.empty[String, Value]
      interp.globals(id) = Value.InstanceV(id, existing + ("sql" -> resultValue))
    }

  def runStringBlock(cb: Content.CodeBlock, section: Section, interp: Interpreter): Unit =
    val rendered = renderStringBlock(cb.source, cb.lang == Lang.Html, interp)
    sectionIdent(section.heading.text).foreach { id =>
      val existing = interp.globals.get(id) match
        case Some(Value.InstanceV(_, fields)) => fields
        case _                                => Map.empty[String, Value]
      val updated = existing + (cb.lang -> Value.StringV(rendered))
      interp.globals(id) = Value.InstanceV(id, updated)
    }

  def runXmlBlock(cb: Content.CodeBlock, section: Section, interp: Interpreter): Unit =
    val rendered = renderStringBlock(
      src      = cb.source,
      escape   = false,
      interp   = interp,
      escapeFn = Some(scalascript.markup.XmlEscape.escape)
    )
    val doc = scalascript.markup.PureMarkupCodec.parse(rendered) match
      case Right(d) => d
      case Left(e)  => throw InterpretError(e.getMessage)
    val result = Value.MarkupV(doc)
    sectionIdent(section.heading.text).foreach { id =>
      val existing = interp.globals.get(id) match
        case Some(Value.InstanceV(_, fields)) => fields
        case _                                => Map.empty[String, Value]
      interp.globals(id) = Value.InstanceV(id, existing + (Lang.Xml -> result))
    }

  def sectionIdent(text: String): Option[String] =
    val parts = text.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    if parts.isEmpty then None
    else
      val head = parts.head
      val tail = parts.tail.map(p => s"${p.head.toUpper}${p.tail}")
      val raw  = head + tail.mkString
      Some(if raw.head.isDigit then "_" + raw else raw)

  def renderStringBlock(
    src:       String,
    escape:    Boolean,
    interp:    Interpreter,
    escapeFn:  Option[String => String] = None
  ): String =
    val sb  = StringBuilder()
    var i   = 0
    val len = src.length
    while i < len do
      if i + 1 < len && src.charAt(i) == '$' && src.charAt(i + 1) == '{' then
        val end = findClosingBrace(src, i + 2)
        if end < 0 then
          sb.append(src.substring(i)); i = len
        else
          val exprSrc = src.substring(i + 2, end)
          val parsed  = scala.meta.dialects.Scala3(exprSrc).parse[scala.meta.Term].get
          val v       = Computation.run(interp.eval(parsed, interp.globals.toMap))
          val shown   = Value.show(v)
          val out     = escapeFn match
            case Some(fn) => fn(shown)
            case None     => if escape then interp.htmlEscapeUnlessRaw(v, shown) else shown
          sb.append(out)
          i = end + 1
      else
        sb.append(src.charAt(i)); i += 1
    sb.toString

  def findClosingBrace(src: String, from: Int): Int =
    val len = src.length
    var depth = 1
    var i = from
    while i < len && depth > 0 do
      src.charAt(i) match
        case '{' =>
          depth += 1
          i += 1
        case '}' =>
          depth -= 1
          if depth == 0 then return i
          i += 1
        case '"' =>
          if i + 2 < len && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"' then
            i += 3
            var closed = false
            while i < len && !closed do
              if i + 2 < len && src.charAt(i) == '"' && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"' then
                i += 3
                closed = true
              else
                i += 1
          else
            i += 1
            var closed = false
            while i < len && !closed do
              src.charAt(i) match
                case '\\' => i += 2
                case '"'  => i += 1; closed = true
                case _    => i += 1
        case '\'' =>
          i += 1
          if i < len then
            if src.charAt(i) == '\\' then i += 1
            if i < len then i += 1
          if i < len && src.charAt(i) == '\'' then i += 1
        case _ =>
          i += 1
    -1

  def runImport(imp: Content.Import, interp: Interpreter): Unit =
    import scalascript.parser.Parser
    val base = interp.baseDir.getOrElse(os.pwd)
    val resolvedPath =
      try scalascript.imports.ImportResolver.resolve(imp.path, base, interp.moduleDeps, interp.lockPath)
      catch case e: Throwable => throw InterpretError(s"Import ${imp.path}: ${e.getMessage}")
    if !os.exists(resolvedPath) then
      throw InterpretError(s"Import not found: ${imp.path}")
    val childDir = resolvedPath / os.up
    val child    = Interpreter(interp.out, Some(childDir), lockPath = interp.lockPath)
    child.run(Parser.parse(os.read(resolvedPath)))
    val exported    = child.exportedGlobals
    val childPkg    = child.exportedPkg
    // Snapshot all child globals so exported FunVs can reference sibling imports
    // (e.g. VStackNode, element) when called from a parent interpreter that lacks them.
    val childCtx: Map[String, Value] = exported
    for binding <- imp.bindings do
      val sourceName = binding.name
      val targetName = binding.alias.getOrElse(binding.name)
      lookupExport(exported, childPkg, sourceName) match
        case Some(v) =>
          val enriched = enrichFnClosures(v, childCtx)
          interp.globals(targetName) = enriched
          enriched match
            case inst: Value.InstanceV if inst.typeName.contains('[') =>
              if !interp.globals.contains(inst.typeName) then interp.globals(inst.typeName) = inst
            case _ => ()
        case None    => throw InterpretError(s"'$sourceName' not found in ${imp.path}")
    child.exportedExtensions.foreach { case ((typeName, method), fn) =>
      interp.extensions.getOrElseUpdate(typeName, mutable.HashMap.empty)(method) = fn
    }
    interp.parentTypes    ++= child.exportedParentTypes
    interp.typeFieldOrder ++= child.exportedTypeFieldOrder
    interp.typeFieldSchemas ++= child.exportedTypeFieldSchemas
    interp.rejectUnknownTypes ++= child.exportedRejectUnknownTypes
    // Transitive call-time resolution.  A dependency's exported function may
    // internally call its own helpers, or names it *imported* but did not
    // re-export (e.g. `validateEntry` using `minorUnits` that the module pulled
    // from `std/money`).  Free names are resolved against `interp.globals` at
    // call time, so make the dependency's module-level names available here.
    // Never overwrite a name the parent already has — that skips the identical
    // builtins (so HTML-tag builtins can't shadow params, the #503a7e6c case)
    // and preserves the parent's own / explicitly-bound names.
    for (name, value) <- childCtx do
      if !interp.globals.contains(name) then
        interp.globals(name) = value

  /** Enrich `FunV` closures with `ctx` so that exported functions can reference
   *  sibling-module bindings (case-class constructors, helpers) when called
   *  from a parent interpreter that doesn't have those names in its globals. */
  private def enrichFnClosures(v: Value, ctx: Map[String, Value]): Value = v match
    case fn: Value.FunV =>
      fn.copy(closure = ctx ++ fn.closure)
    case inst: Value.InstanceV =>
      val enrichedFields = inst.fields.view.mapValues(enrichFnClosures(_, ctx)).toMap
      inst.copy(fields = enrichedFields)
    case _ => v

  def lookupExport(exported: Map[String, Value], pkg: List[String], name: String): Option[Value] =
    if pkg.isEmpty then exported.get(name)
    else
      val root = exported.get(pkg.head)
      val pkgObj = pkg.tail.foldLeft(root) {
        case (Some(Value.InstanceV(_, fields)), seg) => fields.get(seg)
        case (acc, _)                                => acc
      }
      pkgObj.collect {
        case Value.InstanceV(_, fields) => fields.get(name)
      }.flatten
        .orElse(exported.get(name))

  def execBlockStats(stats: List[Stat], interp: Interpreter): Unit =
    var rest = stats
    while rest.nonEmpty do
      val stat = rest.head
      rest = rest.tail
      interp.execStat(stat, interp.globals, printResult = rest.isEmpty)

  def execBlock(node: ScalaNode, interp: Interpreter): Unit =
    ScalaNode.fold(node) {
      case Source(stats)     => execBlockStats(stats, interp)
      case Term.Block(stats) => execBlockStats(stats, interp)
      case t: Term           => Computation.run(interp.eval(t, interp.globals.toMap)); ()
      case other             => interp.located(s"Expected Source/Block, got ${other.productPrefix}")
    }
