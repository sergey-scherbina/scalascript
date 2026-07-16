package scalascript.interpreter

import scalascript.ast.*
import scalascript.backend.spi.NativeContextFeatureKeys
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

  def runModuleSections(module: Module, interp: Interpreter): Unit =
    runSectionList(module.sections, module.document.map(_.sections).getOrElse(Nil), interp)

  def runSectionList(sections: List[Section], contentSections: List[SectionContent], interp: Interpreter): Unit =
    sections.zipWithIndex.foreach { case (section, index) =>
      runSection(section, interp, contentSections.lift(index))
    }

  def runSection(section: Section, interp: Interpreter, contentSection: Option[SectionContent] = None): Unit =
    section.content.foreach {
      case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
        withCurrentContentSection(interp, contentSection) {
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
        }
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
      // std-yaml-p4: yaml/yml fenced blocks → parse + bind to section.yaml in globals
      case cb: Content.CodeBlock if cb.lang == "yaml" || cb.lang == "yml" =>
        runYamlBlock(cb, section, interp)
      case imp: Content.Import =>
        runImport(imp, interp)
      case _ => ()
    }
    section.subsections.zipWithIndex.foreach { case (child, index) =>
      runSection(child, interp, contentSection.flatMap(_.children.lift(index)))
    }

  private def withCurrentContentSection[A](
      interp:          Interpreter,
      contentSection:  Option[SectionContent]
  )(body: => A): A =
    val key = NativeContextFeatureKeys.ContentCurrentSection
    val previous = interp.nativeFeatureLocalRemove(key)
    try
      contentSection.foreach(section => interp.nativeFeatureLocalSet(key, section))
      body
    finally
      interp.nativeFeatureLocalRemove(key)
      previous.foreach(value => interp.nativeFeatureLocalSet(key, value))

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

  // std-yaml-p4: parse yaml/yml fenced block + bind result to section.yaml
  def runYamlBlock(cb: Content.CodeBlock, section: Section, interp: Interpreter): Unit =
    import scalascript.parser.SimpleYaml
    val parsed =
      try
        val raw = SimpleYaml.load[Any](cb.source)
        yamlAnyToValue(raw)
      catch case _: Throwable => Value.InstanceV("YNull", Map.empty)
    sectionIdent(section.heading.text).foreach { id =>
      val existing = interp.globals.get(id) match
        case Some(Value.InstanceV(_, fields)) => fields
        case _                                => Map.empty[String, Value]
      interp.globals(id) = Value.InstanceV(id, existing + ("yaml" -> parsed))
    }

  private def yamlAnyToValue(raw: Any): Value =
    import scala.jdk.CollectionConverters.*
    raw match
      case null                  => Value.InstanceV("YNull", Map.empty)
      case b: java.lang.Boolean  => Value.InstanceV("YBool", Map("value" -> Value.boolV(b)))
      case i: java.lang.Integer  => Value.InstanceV("YNum",  Map("value" -> Value.DoubleV(i.toDouble)))
      case l: java.lang.Long     => Value.InstanceV("YNum",  Map("value" -> Value.DoubleV(l.toDouble)))
      case d: java.lang.Double   => Value.InstanceV("YNum",  Map("value" -> Value.DoubleV(d)))
      case f: java.lang.Float    => Value.InstanceV("YNum",  Map("value" -> Value.DoubleV(f.toDouble)))
      case s: String             => Value.InstanceV("YStr",  Map("value" -> Value.StringV(s)))
      case m: java.util.Map[?,?] =>
        val fields = m.asScala.map { case (k, v) =>
          Value.StringV(k.toString).asInstanceOf[Value] -> yamlAnyToValue(v)
        }.toMap
        Value.InstanceV("YObj", Map("fields" -> Value.MapV(fields)))
      case lst: java.util.List[?] =>
        Value.InstanceV("YArr", Map("items" -> Value.ListV(lst.asScala.map(yamlAnyToValue).toList)))
      case other =>
        Value.InstanceV("YStr", Map("value" -> Value.StringV(other.toString)))

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

  // True if the module declares a TOP-LEVEL `var` (mutable module state) — checked on the direct
  // statements of each code block only (a function-local `var` is nested, not flagged). Used to
  // decide whether the module's exported functions must run in the child interp (live module
  // globals) instead of an import-time snapshot. See bug interp-module-var-home.
  private def moduleDeclaresTopLevelVar(m: Module): Boolean =
    def hasVarStat(stats: List[Stat]): Boolean = stats.exists(_.isInstanceOf[Defn.Var])
    def loop(section: Section): Boolean =
      section.content.exists {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.exists(t => ScalaNode.fold(t) {
            case s: Source     => hasVarStat(s.stats)
            case b: Term.Block => hasVarStat(b.stats)
            case _: Defn.Var   => true
            case _             => false
          })
        case _ => false
      } || section.subsections.exists(loop)
    m.sections.exists(loop)

  private def moduleTopLevelDefNames(m: Module): Set[String] =
    def names(stats: List[Stat]): Set[String] =
      stats.collect { case d: Defn.Def => d.name.value }.toSet
    def loop(section: Section): Set[String] =
      section.content.iterator.flatMap {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.iterator.flatMap(t => ScalaNode.fold(t) {
            case s: Source     => names(s.stats)
            case b: Term.Block => names(b.stats)
            case d: Defn.Def   => Set(d.name.value)
            case _             => Set.empty[String]
          })
        case _ => Iterator.empty
      }.toSet ++ section.subsections.iterator.flatMap(loop).toSet
    m.sections.iterator.flatMap(loop).toSet

  // True if the module already calls a UI render entry (`serve`/`emit`/`mount`/`serveAsync`)
  // at top level. Used by the `def view()` convention (Interpreter.autoRunView) to NOT
  // auto-render when the module renders itself explicitly.
  private val uiEntryNames = Set("serve", "emit", "mount", "serveAsync")
  private[interpreter] def moduleCallsUiEntry(m: Module): Boolean =
    def fnName(fun: Term): Option[String] = fun match
      case n: Term.Name   => Some(n.value)
      case s: Term.Select => Some(s.name.value)
      case _              => None
    def isEntry(t: Stat): Boolean = t match
      case a: Term.Apply => fnName(a.fun).exists(uiEntryNames)
      case _             => false
    def hasEntry(stats: List[Stat]): Boolean = stats.exists(isEntry)
    def loop(section: Section): Boolean =
      section.content.exists {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.exists(t => ScalaNode.fold(t) {
            case s: Source     => hasEntry(s.stats)
            case b: Term.Block => hasEntry(b.stats)
            case _             => false
          })
        case _ => false
      } || section.subsections.exists(loop)
    m.sections.exists(loop)

  def runImport(imp: Content.Import, interp: Interpreter): Unit =
    import scalascript.parser.Parser
    val base = interp.baseDir.getOrElse(os.pwd)
    val resolvedPath =
      try scalascript.imports.ImportResolver.resolve(imp.path, base, interp.moduleDeps, interp.lockPath)
      catch case e: Throwable => throw InterpretError(s"Import ${imp.path}: ${e.getMessage}")
    if !os.exists(resolvedPath) then
      throw InterpretError(s"Import not found: ${imp.path}")
    val childDir = resolvedPath / os.up
    val childModule = Parser.parse(os.read(resolvedPath))
    // Evaluate the imported module at most once per import-graph run.  Keyed by the
    // resolved absolute path and shared via `interp.moduleCache` (threaded into the
    // child below), so a module reachable through a diamond — busi `dispatch.ssc`
    // imported both directly and via an SPI module — is run a single time instead of
    // once per DAG path (which was exponential in diamond layers → OOM/hang at load
    // time, busi seq-132).  The importer below still does its own binding/merge of the
    // (now shared) child's exports, so per-importer aliasing/registration is unchanged.
    // Detect a true import CYCLE (A→B→A) before re-entering the load. `moduleCache`
    // alone can't: `getOrElseUpdate` only inserts after the thunk returns, so while a
    // module's body is still running its path is absent from the cache and a cyclic
    // re-import re-runs it forever → StackOverflowError. `moduleLoading` tracks paths
    // currently on the resolution stack; a hit here is a genuine cycle, reported as a
    // legible error instead of overflowing the stack. (A diamond is acyclic and never
    // re-enters a still-loading path, so this is invisible to the dedup path above.)
    if interp.moduleLoading.contains(resolvedPath) then
      val chain = (interp.moduleLoading.toList :+ resolvedPath).map(_.last).mkString(" → ")
      throw InterpretError(s"Import cycle detected: $chain")
    val child = interp.moduleCache.getOrElseUpdate(resolvedPath, {
      interp.moduleLoading += resolvedPath
      try
        val c = Interpreter(interp.out, Some(childDir), lockPath = interp.lockPath,
                            moduleCache = interp.moduleCache, moduleLoading = interp.moduleLoading)
        c.run(childModule)
        c
      finally interp.moduleLoading -= resolvedPath
    })
    if !isContentHelperImport(imp.path) then
      registerImportedContent(interp, resolvedPath, childModule)
    val exported    = child.exportedGlobals
    val childPkg    = child.exportedPkg
    // Snapshot all child globals so exported FunVs can reference sibling imports
    // (e.g. VStackNode, element) when called from a parent interpreter that lacks them.
    val childCtx = bindModuleFunctions(
      exported ++ packageMembers(exported, childPkg),
      moduleTopLevelDefNames(childModule))
    // Does the imported module hold mutable top-level state? If so its exported functions are
    // bound to run in the child interp (live module globals) rather than a frozen snapshot.
    val childHasVars = moduleDeclaresTopLevelVar(childModule)
    for binding <- imp.bindings do
      val sourceName = binding.name
      val targetName = binding.alias.getOrElse(binding.name)
      // Honor the module's declared `exports:` surface (mirrors the JS/JVM backends, which
      // gate bindings by `manifest.exports`).  A name that is reachable only transitively —
      // present in `childCtx` for call-time helper resolution, or dumped into the module's
      // globals by a dependency — but NOT listed in the module's `exports:` is not importable
      // by name from it.  A module that declares no exports stays permissive (legacy).  This
      // closes an interpreter↔codegen gap: programs importing a non-exported name ran under the
      // interpreter but failed under emit-js/emit-scala.
      if child.exportedNames.nonEmpty && !child.exportedNames.contains(sourceName) then
        throw InterpretError(
          s"'$sourceName' is not exported by ${imp.path} — add it to that module's `exports:` to re-export it")
      lookupExport(exported, childPkg, sourceName) match
        case Some(v) =>
          // If the imported module declares mutable top-level `var` state (a shared registry),
          // bind each exported function as a wrapper that runs IN THE CHILD interpreter, so its
          // reads AND `var` writes hit the module's LIVE globals. A plain `enrichFnClosures` merges
          // an import-time SNAPSHOT of the child globals into the (empty top-level) closure, which
          // freezes the var at its initial value and routes writes to the caller's globals — so
          // cross-module mutable module state was incoherent. (Pure/effectful modules with no
          // top-level var keep the snapshot binding: running them in the child would change their
          // effect/plugin context.) See bug interp-module-var-home.
          val enriched =
            if childHasVars then v match
              case fv: Value.FunV =>
                Value.NativeFnV(targetName,
                  (args: List[Value]) => CallRuntime.callValue(fv, args, Map.empty[String, Value], child))
              case _ => enrichFnClosures(v, childCtx)
            else enrichFnClosures(v, childCtx)
          val imported = rebindPluginNative(sourceName, targetName, enriched, interp)
          // busi-p0-statusval-eventcase-collision — if an imported binding
          // would overwrite an existing same-name binding of a different
          // kind (e.g. a status-wrapper InstanceV val being shadowed by a
          // newly-imported case-constructor NativeFnV from a sibling
          // module), record the displaced side so a typed `val` ascription
          // can disambiguate back to it later via `shadowedAlternatives`.
          StatRuntime.rememberShadowedAlternativeForImport(interp, targetName, imported)
          // busi-p3-module-fn-name-conflict — last-import-wins + warning. If a
          // function-like name was already bound by a *different* import path,
          // warn (the overwrite still proceeds: last import wins). Scoped to
          // callable-vs-callable so it never fires for the intentional
          // status-val / case-constructor disambiguation (InstanceV side).
          interp.importedFnOrigin.get(targetName) match
            case Some(prevPath) if prevPath != imp.path
                && isCallableBinding(interp.globals.get(targetName)) && isCallableBinding(Some(imported)) =>
              interp.warnImportConflict(targetName, prevPath, imp.path)
            case _ => ()
          interp.importedFnOrigin(targetName) = imp.path
          interp.globals(targetName) = imported
          imported match
            case inst: Value.InstanceV if inst.typeName.contains('[') =>
              if !interp.globals.contains(inst.typeName) then interp.globals(inst.typeName) = inst
            case _ => ()
        case None    => throw InterpretError(s"'$sourceName' not found in ${imp.path}")
    child.exportedExtensions.foreach { case ((typeName, method), fn) =>
      interp.extensions.getOrElseUpdate(typeName, mutable.HashMap.empty)(method) = fn
    }
    interp.parentTypes    ++= child.exportedParentTypes
    // Concrete trait/class methods of imported types — so `e.kind` (a method on a
    // trait an imported enum-case extends) dispatches across the import boundary,
    // like the parent chain above (busi seq-121 cross-module).  Union per type so a
    // type with methods in two modules keeps both.
    child.exportedTypeMethods.foreach { case (typeName, methods) =>
      interp.typeMethods.updateWith(typeName) {
        case Some(existing) => Some(existing ++ methods)
        case None           => Some(methods)
      }
    }
    interp.typeFieldOrder ++= child.exportedTypeFieldOrder
    interp.typeFieldDefaults ++= child.exportedTypeFieldDefaults
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
        // Carry the dependency's val-ness for this name across the import boundary.
        // The closure-capture optimization re-reads eq-global *vars* live at call time
        // but must CAPTURE eq-global *vals* (stable, and a lambda may be invoked under a
        // different interpreter than the one that created it — e.g. a `computedSignal`
        // thunk in an imported library module). Without this, a dumped module val looks
        // like a var to the importer and gets re-read-live → Undefined cross-interp.
        if child.valNames.contains(name) then interp.valNames += name

  private def registerImportedContent(interp: Interpreter, resolvedPath: os.Path, childModule: Module): Unit =
    childModule.document.foreach { doc =>
      val namespace = importedContentNamespace(resolvedPath, childModule)
      val key = NativeContextFeatureKeys.ContentImportedModules
      val current = interp.nativeFeatureGet(key).collect {
        case table: Map[?, ?] =>
          table.toList.collect {
            case (ns: String, docs: List[?]) =>
              ns -> docs.collect { case d: DocumentContent => d }
          }.toMap
      }.getOrElse(Map.empty[String, List[DocumentContent]])
      interp.nativeFeatureSet(key, current.updated(namespace, current.getOrElse(namespace, Nil) :+ doc))
    }

  private def importedContentNamespace(resolvedPath: os.Path, childModule: Module): String =
    childModule.manifest.flatMap(_.name).map(_.trim).filter(_.nonEmpty).getOrElse {
      val last = resolvedPath.last
      if last.endsWith(".ssc") then last.stripSuffix(".ssc") else last
    }

  private def isContentHelperImport(path: String): Boolean =
    path == "std/content.ssc" || path.endsWith("std/content.ssc") ||
      path == "std/ui/content.ssc" || path.endsWith("std/ui/content.ssc")

  /** A binding is "callable" (function-like) if it is a user FunV or a native
   *  fn. Used to scope the import-conflict warning to genuine function-name
   *  collisions, leaving the status-val / case-constructor disambiguation alone. */
  private def isCallableBinding(v: Option[Value]): Boolean =
    v match
      case Some(_: Value.FunV) | Some(_: Value.NativeFnV) => true
      case _                                              => false

  private def rebindPluginNative(
      sourceName: String,
      targetName: String,
      value:      Value,
      interp:     Interpreter
  ): Value =
    value match
      case _: Value.NativeFnV =>
        if !interp._pluginsLoaded then interp.ensurePluginsLoaded()
        if interp.pluginNativeNames.contains(sourceName) then
          interp.globals.get(sourceName).collect {
            case fn: Value.NativeFnV => fn.copy(name = targetName)
          }.getOrElse(value)
        else value
      case _ => value

  /** Enrich `FunV` closures with `ctx` so that exported functions can reference
   *  sibling-module bindings (case-class constructors, helpers) when called
   *  from a parent interpreter that doesn't have those names in its globals. */
  private def enrichFnClosures(v: Value, ctx: Map[String, Value]): Value = v match
    case fn: Value.FunV =>
      fn.copy(closure = ctx ++ fn.closure)
    case inst: Value.InstanceV =>
      val enrichedFields = inst.effectiveFields.view.mapValues(enrichFnClosures(_, ctx)).toMap
      inst.copy(fields = enrichedFields)
    case _ => v

  /** Bind every module-level function to one live lexical view of the module.
   *  Exported functions already receive `childCtx`, but an internal helper pulled
   *  from that context used to retain its empty top-level closure.  A second helper
   *  call then fell back to the importer's globals, where a same-named binding from
   *  another module could win.  The shared view keeps arbitrarily deep helper chains
   *  in their defining module while still executing them in the caller interpreter. */
  private final class ModuleEnvView(backing: mutable.Map[String, Value])
      extends scala.collection.immutable.AbstractMap[String, Value]:
    override def get(key: String): Option[Value] = backing.get(key)
    override def getOrElse[V1 >: Value](key: String, default: => V1): V1 =
      backing.getOrElse(key, default)
    override def contains(key: String): Boolean = backing.contains(key)
    override def iterator: Iterator[(String, Value)] = backing.iterator
    override def foreachEntry[U](f: (String, Value) => U): Unit = backing.foreachEntry(f)
    override def updated[V1 >: Value](key: String, value: V1): Map[String, V1] =
      backing.toMap.updated(key, value)
    override def removed(key: String): Map[String, Value] = backing.toMap.removed(key)
    override def equals(other: Any): Boolean =
      other.asInstanceOf[AnyRef] eq this
    override def hashCode(): Int = System.identityHashCode(this)

  private def bindModuleFunctions(ctx: Map[String, Value], localDefNames: Set[String]): Map[String, Value] =
    val bound = mutable.HashMap.from(ctx)
    val moduleEnv: Env = new ModuleEnvView(bound)
    localDefNames.foreach { name =>
      ctx.get(name) match
        case Some(fn: Value.FunV) =>
          val closure =
            if fn.closure.isEmpty then moduleEnv
            else FrameMap.fromMap(fn.closure.iterator.toMap, moduleEnv)
          bound(name) = fn.copy(closure = closure)
        case _ => ()
    }
    bound.toMap

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

  private def packageMembers(exported: Map[String, Value], pkg: List[String]): Map[String, Value] =
    if pkg.isEmpty then Map.empty
    else
      val root = exported.get(pkg.head)
      val pkgObj = pkg.tail.foldLeft(root) {
        case (Some(Value.InstanceV(_, fields)), seg) => fields.get(seg)
        case (acc, _)                                => acc
      }
      pkgObj.collect { case Value.InstanceV(_, fields) => fields }.getOrElse(Map.empty)

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
