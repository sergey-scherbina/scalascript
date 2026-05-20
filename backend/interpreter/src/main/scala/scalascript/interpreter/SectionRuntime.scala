package scalascript.interpreter

import scalascript.ast.*
import scala.meta.*

/** Section / block execution: runSection, SQL blocks, HTML/CSS blocks,
 *  imports, and statement dispatch (`execBlock` / `execBlockStats`).
 */
private[interpreter] object SectionRuntime:

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
        cb.tree.foreach(t => execBlock(t, interp))
      case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
        runStringBlock(cb, section, interp)
      case cb: Content.CodeBlock if Lang.isSql(cb.lang) =>
        runSqlBlock(cb, section, interp)
      case imp: Content.Import =>
        runImport(imp, interp)
      case _ => ()
    }
    section.subsections.foreach(s => runSection(s, interp))

  def runSqlBlock(cb: Content.CodeBlock, section: Section, interp: Interpreter): Unit =
    val rewritten = scalascript.transform.SqlBindRewriter.rewriteJdbc(cb.source)
    val binds: List[Any] = rewritten.binds.map { exprSrc =>
      val expr = scala.meta.dialects.Scala3(scala.meta.Input.VirtualFile(
        "<sql-bind>", exprSrc
      )).parse[scala.meta.Term].get
      val v = Computation.run(interp.eval(expr, interp.globals.toMap))
      unwrapForJdbc(v)
    }
    val conn: java.sql.Connection = resolveSqlConnection(cb, interp)
    val sqlResult = scalascript.sql.SqlRuntime.execute(conn, rewritten.sql, binds)
    val resultValue: Value = sqlResult match
      case scalascript.sql.SqlResult.Rows(rows) =>
        Value.ListV(rows.map(rowToValue).toList)
      case scalascript.sql.SqlResult.UpdateCount(n) =>
        Value.IntV(n.toLong)
    val ordinal = interp.sqlBlockCounter
    interp.sqlBlockCounter += 1
    interp.globals(s"_sqlBlock_$ordinal") = resultValue
    sectionIdent(section.heading.text).foreach { id =>
      val existing = interp.globals.get(id) match
        case Some(Value.InstanceV(_, fields)) => fields
        case _                                => Map.empty[String, Value]
      interp.globals(id) = Value.InstanceV(id, existing + ("sql" -> resultValue))
    }

  def resolveSqlConnection(cb: Content.CodeBlock, interp: Interpreter): java.sql.Connection =
    interp.globals.get("Connection") match
      case Some(Value.Foreign("Connection", c: java.sql.Connection)) => c
      case Some(Value.Foreign("DataSource", ds: javax.sql.DataSource)) => ds.getConnection
      case _ =>
        val dbName = cb.attrs.getOrElse("db", "default")
        interp.sqlRegistry.connect(dbName)

  def rowToValue(row: scalascript.sql.Row): Value =
    val pairs = row.columns.zip(row.values).map { case (col, v) =>
      Value.StringV(col) -> wrapJdbcValue(v)
    }
    Value.MapV(pairs.toMap)

  def wrapJdbcValue(v: Any): Value = v match
    case null            => Value.NullV
    case s: String       => Value.StringV(s)
    case b: Boolean      => Value.BoolV(b)
    case n: Int          => Value.IntV(n.toLong)
    case n: Long         => Value.IntV(n)
    case n: Short        => Value.IntV(n.toLong)
    case n: Byte         => Value.IntV(n.toLong)
    case d: Double       => Value.DoubleV(d)
    case f: Float        => Value.DoubleV(f.toDouble)
    case bi: java.math.BigInteger => Value.IntV(bi.longValueExact)
    case bd: java.math.BigDecimal => Value.DoubleV(bd.doubleValue)
    case other           =>
      Value.Foreign(Option(other).map(_.getClass.getSimpleName).getOrElse("?"), other)

  def unwrapForJdbc(v: Value): Any = v match
    case Value.IntV(n)              => n
    case Value.DoubleV(d)           => d
    case Value.StringV(s)           => s
    case Value.BoolV(b)             => b
    case Value.CharV(c)             => c
    case Value.UnitV                => null
    case Value.NullV                => null
    case Value.OptionV(None)        => null
    case Value.OptionV(Some(inner)) => unwrapForJdbc(inner)
    case Value.Foreign(_, h)        => h
    case other                      => Value.show(other)

  def runStringBlock(cb: Content.CodeBlock, section: Section, interp: Interpreter): Unit =
    val rendered = renderStringBlock(cb.source, cb.lang == Lang.Html, interp)
    sectionIdent(section.heading.text).foreach { id =>
      val existing = interp.globals.get(id) match
        case Some(Value.InstanceV(_, fields)) => fields
        case _                                => Map.empty[String, Value]
      val updated = existing + (cb.lang -> Value.StringV(rendered))
      interp.globals(id) = Value.InstanceV(id, updated)
    }

  def sectionIdent(text: String): Option[String] =
    val parts = text.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    if parts.isEmpty then None
    else
      val head = parts.head
      val tail = parts.tail.map(p => s"${p.head.toUpper}${p.tail}")
      val raw  = head + tail.mkString
      Some(if raw.head.isDigit then "_" + raw else raw)

  def renderStringBlock(src: String, escape: Boolean, interp: Interpreter): String =
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
          sb.append(if escape then interp.htmlEscapeUnlessRaw(v, shown) else shown)
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
    interp.extensions     ++= child.exportedExtensions
    interp.parentTypes    ++= child.exportedParentTypes
    interp.typeFieldOrder ++= child.exportedTypeFieldOrder

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
    stats.zipWithIndex.foreach { (s, i) =>
      interp.execStat(s, interp.globals, printResult = i == stats.length - 1)
    }

  def execBlock(node: ScalaNode, interp: Interpreter): Unit =
    ScalaNode.fold(node) {
      case Source(stats)     => execBlockStats(stats, interp)
      case Term.Block(stats) => execBlockStats(stats, interp)
      case t: Term           => Computation.run(interp.eval(t, interp.globals.toMap)); ()
      case other             => interp.located(s"Expected Source/Block, got ${other.productPrefix}")
    }
