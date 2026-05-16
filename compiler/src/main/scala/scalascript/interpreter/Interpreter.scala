package scalascript.interpreter

import scalascript.ast.*
import scala.collection.mutable
import scala.meta.*
import Computation.{Pure, Perform, FlatMap}

/** Tree-walking interpreter for ScalaScript documents.
 *
 *  Execution model:
 *  - Sections are processed in document order.
 *  - Each scala/ssc code block is executed eagerly (defs bind, exprs run).
 *  - After all sections, `main()` is auto-called if defined and not already invoked.
 *
 *  Algebraic effects use a **Free Monad** representation: `eval` returns a
 *  `Computation` (Pure | Perform). Effect ops produce `Perform` nodes; handlers
 *  walk the tree and dispatch them. `resume(v)` invokes the captured Scala
 *  continuation directly — no replay; side effects in body run exactly once;
 *  multi-shot works by calling the continuation multiple times.
 */
class Interpreter(out: java.io.PrintStream = System.out, baseDir: Option[os.Path] = None):
  private val globals      = mutable.Map.empty[String, Value]
  private val extensions   = mutable.Map.empty[(String, String), Value.FunV]
  // Methods declared inside a `class` / `case class` body, keyed by type name.
  // Stored separately from instance fields so `show` and pattern matching see
  // only data fields.
  private val typeMethods  = mutable.Map.empty[String, Map[String, Value.FunV]]
  private var mainCalled   = false
  private var placeholderIdx = 0
  // Tracks the last known source position for error messages (0-based line, 0-based column).
  private var currentSpan: Option[(Int, Int)] = None
  // Source of the code block currently being executed — used to print the
  // offending line under the error message with a caret.
  private var currentSource: String = ""
  // When the parser falls back to wrapping the block in `{ ... }` to accept
  // top-level expressions, every scalameta position is shifted down by one
  // line. `lineOffset` compensates so error messages report the user's line.
  private var lineOffset: Int = 0

  /** Per-FunV cache of the TCO classification — three full body walks
   *  (`tailCallTargets`, `callsInTailPos`, `hasNonTailSelfCall`) used to
   *  cost a tail-recursive call up-front on every invocation. The body is
   *  immutable per FunV, so the result is too. Keyed by FunV identity. */
  private case class TcoInfo(
    tailTargets:   Set[String],
    isSelfTailRec: Boolean,
    noNonTailSelf: Boolean
  )
  private val tcoCache: java.util.IdentityHashMap[Value.FunV, TcoInfo] =
    java.util.IdentityHashMap()

  private def tcoInfoFor(f: Value.FunV): TcoInfo =
    val cached = tcoCache.get(f)
    if cached != null then cached
    else
      val info =
        if f.name.isEmpty then TcoInfo(Set.empty, false, false)
        else
          val targets = tailCallTargets(f.body, f.name, tailPos = true)
          val selfTR  = callsInTailPos(f.body, f.name)
          val noNTS   = !hasNonTailSelfCall(f.body, f.name, tailPos = true)
          TcoInfo(targets, selfTR, noNTS)
      tcoCache.put(f, info)
      info

  /** Format a position prefix like "[line 5, col 3] " or "" if unknown. */
  private def posPrefix: String = currentSpan match
    case Some((line, col)) => s"[line ${line + 1}, col ${col + 1}] "
    case None              => ""

  /** Render the source line at `currentSpan` with a caret underneath, or
   *  empty string if no position / source is known. Two-line output, indented
   *  so it lines up cleanly under the error message. */
  private def sourceContext: String = currentSpan match
    case Some((line, col)) if currentSource.nonEmpty =>
      val lines = currentSource.split("\n", -1)
      if line < 0 || line >= lines.length then ""
      else
        val src    = lines(line).stripTrailing
        val gutter = s"${line + 1}"
        val pad    = " " * gutter.length
        val caret  = " " * col.max(0).min(src.length) + "^"
        s"\n  $gutter | $src\n  $pad | $caret"
    case _ => ""

  /** Prefix `msg` with position info and throw InterpretError with source
   *  context appended underneath. */
  private def located(msg: String): Nothing =
    throw InterpretError(s"$posPrefix$msg$sourceContext")

  /** Update currentSpan from a scalameta tree's position (no-op if position is empty). */
  private def trackPos(tree: scala.meta.Tree): Unit =
    val p = tree.pos
    if !p.isEmpty then currentSpan = Some((p.startLine - lineOffset, p.startColumn))

  // ─── Public API ──────────────────────────────────────────────────

  def run(module: Module): Unit =
    initBuiltins()
    module.sections.foreach(runSection)
    if !mainCalled then
      globals.get("main").foreach {
        case f: Value.FunV if f.params.isEmpty => Computation.run(callFun(f, Nil)); mainCalled = true
        case _ => ()
      }

  // ─── Built-ins ───────────────────────────────────────────────────

  private def initBuiltins(): Unit =
    def nativeP(name: String)(f: List[Value] => Value): Unit =
      globals(name) = Value.NativeFnV(name, Computation.pureFn(f))

    nativeP("println") { args => out.println(args.map(Value.show).mkString(" ")); Value.UnitV }
    nativeP("print")   { args => out.print(args.map(Value.show).mkString(" "));   Value.UnitV }
    nativeP("assert") {
      case List(Value.BoolV(true))             => Value.UnitV
      case List(Value.BoolV(false))            => throw InterpretError("Assertion failed")
      case List(Value.BoolV(true),  _)         => Value.UnitV
      case List(Value.BoolV(false), msg)       => throw InterpretError(s"Assertion failed: ${Value.show(msg)}")
      case _                                   => Value.UnitV
    }
    nativeP("require") {
      case List(Value.BoolV(true))        => Value.UnitV
      case List(Value.BoolV(false), msg)  => throw InterpretError(s"Requirement failed: ${Value.show(msg)}")
      case _                              => Value.UnitV
    }
    nativeP("serve") {
      case List(Value.IntV(port)) =>
        scalascript.server.WebServer.start(port.toInt, ".", out); Value.UnitV
      case List(Value.IntV(port), Value.StringV(dir)) =>
        scalascript.server.WebServer.start(port.toInt, dir, out); Value.UnitV
      case _ => throw InterpretError("serve(port) or serve(port, dir)")
    }

    // Wall-clock for benchmarks — same name across all three backends.
    nativeP("nanoTime") { _ => Value.IntV(java.lang.System.nanoTime()) }

    // doc(...) builds a DocV; render(...) prints it
    nativeP("doc")    { args => Value.DocV(args) }
    nativeP("render") { args =>
      val text = args match
        case List(Value.DocV(parts)) => parts.map(Value.show).mkString("\n")
        case List(v)                 => Value.show(v)
        case vs                      => vs.map(Value.show).mkString("\n")
      out.println(text)
      Value.UnitV
    }

    nativeP("Some")  { case List(v) => Value.OptionV(Some(v)); case _ => throw InterpretError("Some takes 1 arg") }
    nativeP("List")  { args => Value.ListV(args) }
    // List companion object — fill/tabulate are curried (List.fill(n)(elem))
    globals("List.fill") = Value.NativeFnV("List.fill", {
      case List(Value.IntV(n)) =>
        Pure(Value.NativeFnV("List.fill.n", {
          case List(elem) => Pure(Value.ListV(List.fill(n.toInt)(elem)))
          case _          => throw InterpretError("List.fill(n)(elem)")
        }))
      case _ => throw InterpretError("List.fill(n)(elem)")
    })
    globals("List.tabulate") = Value.NativeFnV("List.tabulate", {
      case List(Value.IntV(n)) =>
        Pure(Value.NativeFnV("List.tabulate.n", {
          case List(f) =>
            // f(i) may perform effects — sequence the computations
            Computation.sequence((0 until n.toInt).toList.map(i =>
              callValue(f, List(Value.IntV(i)), Map.empty)))
          case _ => throw InterpretError("List.tabulate(n)(f)")
        }))
      case _ => throw InterpretError("List.tabulate(n)(f)")
    })
    globals("List.range") = Value.NativeFnV("List.range", {
      case List(Value.IntV(from), Value.IntV(until)) =>
        Pure(Value.ListV((from.toInt until until.toInt).map(i => Value.IntV(i)).toList))
      case List(Value.IntV(from), Value.IntV(until), Value.IntV(step)) =>
        Pure(Value.ListV((from.toInt until until.toInt by step.toInt).map(i => Value.IntV(i)).toList))
      case _ => throw InterpretError("List.range(from, until[, step])")
    })
    val listNative = globals("List")
    globals("List") = Value.InstanceV("List", Map(
      "fill"     -> globals("List.fill"),
      "tabulate" -> globals("List.tabulate"),
      "range"    -> globals("List.range"),
      "empty"    -> Value.ListV(Nil),
      "apply"    -> listNative
    ))
    nativeP("Map") { args =>
      val entries = args.collect { case Value.TupleV(List(k, v)) => k -> v }.toMap
      Value.MapV(entries)
    }
    globals("None") = Value.OptionV(None)

    nativeP("math.sqrt")  { case List(Value.DoubleV(d)) => Value.DoubleV(math.sqrt(d))
                            case List(Value.IntV(n))    => Value.DoubleV(math.sqrt(n.toDouble))
                            case _ => Value.UnitV }
    nativeP("math.abs")   { case List(Value.DoubleV(d)) => Value.DoubleV(math.abs(d))
                            case List(Value.IntV(n))    => Value.IntV(math.abs(n))
                            case _ => Value.UnitV }
    nativeP("math.pow")   { case List(Value.DoubleV(a), Value.DoubleV(b)) => Value.DoubleV(math.pow(a, b))
                            case List(Value.IntV(a),    Value.DoubleV(b)) => Value.DoubleV(math.pow(a.toDouble, b))
                            case List(Value.DoubleV(a), Value.IntV(b))    => Value.DoubleV(math.pow(a, b.toDouble))
                            case List(Value.IntV(a),    Value.IntV(b))    => Value.DoubleV(math.pow(a.toDouble, b.toDouble))
                            case _ => Value.UnitV }
    nativeP("math.floor") { case List(Value.DoubleV(d)) => Value.DoubleV(math.floor(d)); case _ => Value.UnitV }
    nativeP("math.ceil")  { case List(Value.DoubleV(d)) => Value.DoubleV(math.ceil(d));  case _ => Value.UnitV }
    nativeP("math.round") { case List(Value.DoubleV(d)) => Value.IntV(math.round(d));    case _ => Value.UnitV }
    globals("math.Pi")   = Value.DoubleV(math.Pi)
    globals("math.E")    = Value.DoubleV(math.E)
    // math as an object so `math.sqrt(x)` works via field dispatch
    globals("math") = Value.InstanceV("math", Map(
      "sqrt"  -> globals("math.sqrt"),
      "abs"   -> globals("math.abs"),
      "pow"   -> globals("math.pow"),
      "floor" -> globals("math.floor"),
      "ceil"  -> globals("math.ceil"),
      "round" -> globals("math.round"),
      "Pi"    -> globals("math.Pi"),
      "E"     -> globals("math.E")
    ))

    // ─── Web primitives: escape, raw, route, Request, Response ────────

    nativeP("escape") {
      case List(Value.StringV(s)) => Value.StringV(htmlEscape(s))
      case List(v)                => Value.StringV(htmlEscape(Value.show(v)))
      case _                      => throw InterpretError("escape(s)")
    }

    // raw(s) marks a string as pre-escaped HTML so html"..." doesn't re-escape.
    nativeP("raw") {
      case List(Value.StringV(s)) => Value.InstanceV("_Raw", Map("html" -> Value.StringV(s)))
      case List(v)                => Value.InstanceV("_Raw", Map("html" -> Value.StringV(Value.show(v))))
      case _                      => throw InterpretError("raw(s)")
    }

    // Response(status, headers, body) — Map-based, all optional.
    def mkResponse(
        status:  Int,
        headers: Map[Value, Value] = Map.empty,
        body:    String = ""
    ): Value.InstanceV =
      Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(status),
        "headers" -> Value.MapV(headers),
        "body"    -> Value.StringV(body)
      ))

    def bodyOf(v: Value): String = v match
      case Value.StringV(s)                                    => s
      case Value.InstanceV("_Raw", fields)                     => fields.get("html").map(Value.show).getOrElse("")
      case other                                               => Value.show(other)

    nativeP("Response.html") {
      case List(v) =>
        Value.InstanceV("Response", Map(
          "status"  -> Value.IntV(200),
          "headers" -> Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV("text/html; charset=utf-8"))),
          "body"    -> Value.StringV(bodyOf(v))
        ))
      case _ => throw InterpretError("Response.html(body)")
    }
    nativeP("Response.text") {
      case List(v) => mkResponse(200, Map(Value.StringV("Content-Type") -> Value.StringV("text/plain; charset=utf-8")), bodyOf(v))
      case _       => throw InterpretError("Response.text(body)")
    }
    nativeP("Response.json") {
      case List(v) => mkResponse(200, Map(Value.StringV("Content-Type") -> Value.StringV("application/json")), bodyOf(v))
      case _       => throw InterpretError("Response.json(body)")
    }
    nativeP("Response.redirect") {
      case List(Value.StringV(loc)) => mkResponse(302, Map(Value.StringV("Location") -> Value.StringV(loc)), "")
      case _ => throw InterpretError("Response.redirect(url)")
    }
    nativeP("Response.notFound") {
      case Nil      => mkResponse(404, body = "Not Found")
      case List(v)  => mkResponse(404, body = bodyOf(v))
      case _        => throw InterpretError("Response.notFound([body])")
    }
    nativeP("Response.status") {
      case List(Value.IntV(s))                       => mkResponse(s.toInt)
      case List(Value.IntV(s), v)                    => mkResponse(s.toInt, body = bodyOf(v))
      case _                                         => throw InterpretError("Response.status(code[, body])")
    }
    // Response companion object — fields call the underlying natives.
    globals("Response") = Value.InstanceV("Response", Map(
      "html"     -> globals("Response.html"),
      "text"     -> globals("Response.text"),
      "json"     -> globals("Response.json"),
      "redirect" -> globals("Response.redirect"),
      "notFound" -> globals("Response.notFound"),
      "status"   -> globals("Response.status")
    ))

    // route(method, path)(handler) — registers a handler in the global table.
    // Path syntax: literal segments + `:name` captures (e.g. /users/:id).
    nativeP("route") {
      case List(Value.StringV(method), Value.StringV(path)) =>
        // Curried — return a fn that takes the handler.
        Value.NativeFnV("route.handler", Computation.pureFn {
          case List(handler) =>
            scalascript.server.Routes.register(method, path, handler, this)
            Value.UnitV
          case _ => throw InterpretError("route(method, path) { handler }")
        })
      case _ => throw InterpretError("route(method, path) { handler }")
    }

  /** Invoke an interpreter Value (closure or native fn) from outside —
   *  used by WebServer to call route handlers in response to HTTP requests. */
  def invoke(fn: Value, args: List[Value]): Value =
    Computation.run(callValue(fn, args, Map.empty))

  /** HTML-escape a string for safe interpolation in an html block. */
  private def htmlEscape(s: String): String =
    val sb = StringBuilder()
    s.foreach {
      case '&'  => sb ++= "&amp;"
      case '<'  => sb ++= "&lt;"
      case '>'  => sb ++= "&gt;"
      case '"'  => sb ++= "&quot;"
      case '\'' => sb ++= "&#39;"
      case c    => sb += c
    }
    sb.toString

  /** Escape `rendered` unless the underlying value is a `raw(...)` marker,
   *  in which case the marker's body is already trusted HTML. */
  private def htmlEscapeUnlessRaw(v: Value, rendered: String): String = v match
    case Value.InstanceV("_Raw", _) => rendered
    case _                          => htmlEscape(rendered)

  def exportedGlobals: Map[String, Value] = globals.toMap

  // ─── Section / block execution ───────────────────────────────────

  private def runSection(section: Section): Unit =
    section.content.foreach {
      case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
        currentSource = cb.source
        // The parser falls back to wrapping the block in `{\n...\n}` when
        // scalameta's Source parser rejects the script (e.g. top-level
        // expressions). Detect that by tree shape and offset position lines
        // so error messages quote the user's line numbers, not the wrapper's.
        lineOffset = cb.tree match
          case Some(t) => ScalaNode.fold(t) {
            case _: Term.Block => 1
            case _             => 0
          }
          case None => 0
        cb.tree.foreach(execBlock)
      case imp: Content.Import =>
        runImport(imp)
      case _ => ()
    }
    section.subsections.foreach(runSection)

  private def runImport(imp: Content.Import): Unit =
    import scalascript.parser.Parser
    val resolvedPath = baseDir match
      case Some(dir) => dir / os.RelPath(imp.path)
      case None      => os.Path(imp.path, os.pwd)
    if !os.exists(resolvedPath) then
      throw InterpretError(s"Import not found: ${imp.path}")
    val childDir = resolvedPath / os.up
    val child    = Interpreter(out, Some(childDir))
    child.run(Parser.parse(os.read(resolvedPath)))
    val exported = child.exportedGlobals
    for binding <- imp.bindings do
      val sourceName = binding.name
      val targetName = binding.alias.getOrElse(binding.name)
      exported.get(sourceName) match
        case Some(v) => globals(targetName) = v
        case None    => throw InterpretError(s"'$sourceName' not found in ${imp.path}")

  private def execBlockStats(stats: List[Stat]): Unit =
    stats.zipWithIndex.foreach { (s, i) =>
      execStat(s, globals, printResult = i == stats.length - 1)
    }

  private def execBlock(node: ScalaNode): Unit =
    ScalaNode.fold(node) {
      case Source(stats)     => execBlockStats(stats)
      case Term.Block(stats) => execBlockStats(stats)
      case t: Term           => Computation.run(eval(t, globals.toMap)); ()
      case other             => located(s"Expected Source/Block, got ${other.productPrefix}")
    }

  // ─── Statement execution ─────────────────────────────────────────

  private def execStat(stat: Stat, env: mutable.Map[String, Value], printResult: Boolean = false): Unit =
    trackPos(stat)
    stat match
    case Defn.Val(_, pats, _, rhs) =>
      val rhsVal = Computation.run(eval(rhs, env.toMap))
      pats match
        case List(Pat.Var(n)) => env(n.value) = rhsVal
        case List(pat) =>
          matchPat(pat, rhsVal, env.toMap) match
            case Some(patEnv) => patEnv.foreach { (k, v) => env(k) = v }
            case None         => located(s"Val pattern match failed")
        case _ => ()

    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      env(n.value) = Computation.run(eval(rhs, env.toMap))

    case d: Defn.Def =>
      val paramVals = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
      val params    = paramVals.map(_.name.value)
      val defaults  = paramVals.map(_.default)
      // See Term.Function above for why we drop globals from the capture.
      val capturedEnv = env.toMap -- globals.keys
      val fn: Value.FunV = Value.FunV(params, d.body, capturedEnv, d.name.value, defaults)
      env(d.name.value) = fn
      if d.name.value == "main" && params.isEmpty then mainCalled = false

    case d: Defn.Object =>
      val objectName = d.name.value
      val members = mutable.Map.empty[String, Value]
      d.templ.body.stats.foreach {
        case dd: Defn.Def if isEffectOpDef(dd.body) =>
          val effName = objectName
          val opName  = dd.name.value
          // Effect op: a bare Perform request. The "rest of the computation" is
          // captured in an outer FlatMap by the bind chain that consumed it.
          members(opName) = Value.NativeFnV(s"$effName.$opName",
            args => Perform(effName, opName, args))
        case s => execStat(s, members)
      }
      env(objectName) = Value.InstanceV(objectName, members.toMap)

    case d: Defn.Class =>
      val params = d.ctor.paramClauses.flatMap(_.values).toList
      val paramNames    = params.map(_.name.value)
      val paramDefaults = params.map(_.default)
      val typeName = d.name.value
      val ctorEnv = env.toMap
      env(typeName) = Value.NativeFnV(typeName, args => {
        val filled = applyDefaults(paramNames, paramDefaults, args, ctorEnv)
        Pure(Value.InstanceV(typeName, paramNames.zip(filled).toMap))
      })
      // Methods defined inside the class body are stored in a separate
      // type-keyed registry; dispatch on an InstanceV consults it and re-binds
      // each method's closure with the instance's data fields so the body can
      // refer to them by name (`x`, `y` in `def distanceTo(other) = ...x...`).
      val classEnv = env.toMap
      val methodPairs: List[(String, Value.FunV)] = d.templ.body.stats.collect {
        case dd: Defn.Def =>
          val mparamVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
          val mparams    = mparamVals.map(_.name.value)
          val mdefaults  = mparamVals.map(_.default)
          (dd.name.value, Value.FunV(mparams, dd.body, classEnv, dd.name.value, mdefaults))
      }
      val methodDefs: Map[String, Value.FunV] = methodPairs.toMap
      if methodDefs.nonEmpty then typeMethods(typeName) = methodDefs

    case d: Defn.Enum =>
      val enumName = d.name.value
      val caseFields = mutable.Map.empty[String, Value]
      val ctorEnv = env.toMap
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseName = ec.name.value
          val ecParams      = ec.ctor.paramClauses.flatMap(_.values).toList
          val paramNames    = ecParams.map(_.name.value)
          val paramDefaults = ecParams.map(_.default)
          val v: Value =
            if paramNames.isEmpty then Value.InstanceV(caseName, Map.empty)
            else Value.NativeFnV(caseName, args => {
              val filled = applyDefaults(paramNames, paramDefaults, args, ctorEnv)
              Pure(Value.InstanceV(caseName, paramNames.zip(filled).toMap))
            })
          env(caseName) = v
          caseFields(caseName) = v
        case _ => ()
      }
      env(enumName) = Value.InstanceV(enumName, caseFields.toMap)

    case _: Defn.Trait => () // trait is erased — only the given instances matter

    case d: Defn.Given =>
      d.templ.inits.headOption.foreach { init =>
        val typeKeyOpt: Option[String] = init.tpe match
          case n: Type.Name  => Some(n.value)
          case ta: Type.Apply =>
            (ta.tpe match { case n: Type.Name => Some(n.value); case _ => None }).map { tc =>
              val arg = ta.argClause.values match
                case List(n: Type.Name) => n.value
                case _                  => "_"
              s"$tc[$arg]"
            }
          case _ => None
        typeKeyOpt.foreach { typeKey =>
          val members = mutable.Map.from(globals)
          d.templ.body.stats.foreach(s => execStat(s, members))
          val implNames = d.templ.body.stats.collect { case dd: Defn.Def => dd.name.value }.toSet
          val instance  = Value.InstanceV(typeKey, members.view.filterKeys(implNames.contains).toMap)
          env(typeKey) = instance
          val explicitName = d.name.value
          if explicitName.nonEmpty then env(explicitName) = instance
        }
      }

    case _: Decl.Def => () // abstract method declaration — no body

    case d: Defn.ExtensionGroup =>
      d.paramClauseGroup.foreach { pcg =>
        pcg.paramClauses.headOption.flatMap(_.values.headOption).foreach { recvParam =>
          val recvName = recvParam.name.value
          val recvTypeName = recvParam.decltpe match
            case Some(Type.Name(n))   => n
            case Some(ta: Type.Apply) => ta.tpe match { case Type.Name(n) => n; case _ => "Any" }
            case _                    => "Any"
          def registerDef(defn: Defn.Def): Unit =
            val mparamVals   = defn.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
            val methodParams = mparamVals.map(_.name.value)
            // Receiver param has no default; method params keep theirs.
            val methodDefaults: List[Option[Term]] = None :: mparamVals.map(_.default)
            extensions((recvTypeName, defn.name.value)) =
              Value.FunV(recvName :: methodParams, defn.body, env.toMap, "", methodDefaults)
          d.body match
            case defn: Defn.Def    => registerDef(defn)
            case Term.Block(stats) => stats.foreach { case defn: Defn.Def => registerDef(defn); case _ => () }
            case _                 => ()
        }
      }

    case t: Term =>
      val result = Computation.run(eval(t, env.toMap))
      t match
        case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
        case _                                => ()
      if printResult then autoOutput(result)
      else result: @annotation.nowarn("msg=Discarded")

    case _ => () // type aliases, imports, exports, etc.

  // ─── Expression evaluation ───────────────────────────────────────

  private def eval(term: Term, env: Env): Computation =
    trackPos(term)
    term match
    // Literals
    case Lit.Int(v)     => Pure(Value.IntV(v.toLong))
    case Lit.Long(v)    => Pure(Value.IntV(v))
    case Lit.Double(v)  => Pure(Value.DoubleV(v.toString.toDouble))
    case Lit.Float(v)   => Pure(Value.DoubleV(v.toString.toDouble))
    case Lit.String(v)  => Pure(Value.StringV(v))
    case Lit.Boolean(v) => Pure(Value.BoolV(v))
    case Lit.Char(v)    => Pure(Value.CharV(v))
    case Lit.Unit()     => Pure(Value.UnitV)
    case Lit.Null()     => Pure(Value.NullV)

    // Name lookup: local env first, then globals
    case Term.Name(name) =>
      Pure(env.getOrElse(name, globals.getOrElse(name, located(s"Undefined: $name"))))

    // Special form: handle(body) { case Eff.op(args, resume) => ... }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          evalHandle(bodyArgClause.values.head.asInstanceOf[Term], pf.cases, env)
        case _ => located("handle expects a partial function { case Eff.op(args, resume) => ... }")

    // Function application: detect obj.method(args) and dispatch directly.
    // All sub-terms are evaluated eagerly here; the FlatMap chain composes
    // already-built Computations so placeholderIdx and other eval-time state
    // is observed correctly.
    case app: Term.Apply =>
      app.fun match
        case Term.Select(qual, Term.Name(method)) =>
          val qualC    = eval(qual, env)
          val argComps = app.argClause.values.map(eval(_, env))
          qualC match
            case Pure(qualV) if argComps.forall(_.isInstanceOf[Pure]) =>
              val argVs = argComps.map { case Pure(v) => v; case _ => Value.UnitV }
              dispatch(qualV, method, argVs, env)
            case _ =>
              FlatMap(qualC, qualV =>
                threadValues(argComps)(argVals => dispatch(qualV, method, argVals, env)))
        case fun =>
          val funC     = eval(fun, env)
          val argComps = app.argClause.values.map(eval(_, env))
          funC match
            case Pure(fv) if argComps.forall(_.isInstanceOf[Pure]) =>
              val argVs = argComps.map { case Pure(v) => v; case _ => Value.UnitV }
              callValue(fv, argVs, env)
            case _ =>
              FlatMap(funC, fv =>
                threadValues(argComps)(argVals => callValue(fv, argVals, env)))

    // Infix operators: a op b
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val lhsC     = eval(lhs, env)
      val argComps = argClause.values.map(eval(_, env))
      // Fast path: both sides already pure (the typical case for hot
      // arithmetic like `n - 1`, `acc + n`, `n < 2`). Skip the FlatMap
      // chain and call infix directly.
      lhsC match
        case Pure(lhsV) if argComps.forall(_.isInstanceOf[Pure]) =>
          val argVs = argComps.map { case Pure(v) => v; case _ => Value.UnitV }
          infix(lhsV, op.value, argVs, env)
        case _ =>
          FlatMap(lhsC, lhsV =>
            threadValues(argComps)(argVs => infix(lhsV, op.value, argVs, env)))

    // Field / method selection: a.b  (no-arg call)
    case Term.Select(qual, name) =>
      val qualC = eval(qual, env)
      FlatMap(qualC, qualV => dispatch(qualV, name.value, Nil, env))

    // Block { stmts; expr }
    case Term.Block(stats) =>
      evalBlock(stats, env)

    // if/then/else
    case t: Term.If =>
      eval(t.cond, env) match
        // Fast path: cond evaluated eagerly to a pure BoolV (the typical
        // case after pure-value shortcuts kick in). Skip the FlatMap.
        case Pure(Value.BoolV(true))  => eval(t.thenp, env)
        case Pure(Value.BoolV(false)) => eval(t.elsep, env)
        case Pure(other)              => located(s"if condition must be Boolean, got ${Value.show(other)}")
        case condC                    => condC.flatMap {
          case Value.BoolV(true)  => eval(t.thenp, env)
          case Value.BoolV(false) => eval(t.elsep, env)
          case other              => located(s"if condition must be Boolean, got ${Value.show(other)}")
        }

    // String interpolation s"..." / f"..." / md"..." / html"..." / css"..."
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md"
        || prefix == "html" || prefix == "css" =>
      evalArgs(args.map(_.asInstanceOf[Term]), env) { argVs =>
        val sb = StringBuilder()
        for i <- parts.indices do
          sb ++= parts(i).asInstanceOf[Lit.String].value
          if i < argVs.length then
            val rendered = Value.show(argVs(i))
            // html"..." escapes interpolated values unless wrapped in raw(...).
            sb ++= (if prefix == "html" then htmlEscapeUnlessRaw(argVs(i), rendered) else rendered)
        val raw = sb.toString
        Pure(Value.StringV(if prefix == "md" then stripIndent(raw) else raw))
      }

    // Anonymous function with _ placeholders: _.field, _ + 1, _ + _, etc.
    case t: Term.AnonymousFunction =>
      Pure(Value.NativeFnV("anon", args => {
        val saved = placeholderIdx
        placeholderIdx = 0
        val phEnv = env ++ args.zipWithIndex.map { (v, i) => s"_$$${i}" -> v }
        try eval(t.body, phEnv)
        finally placeholderIdx = saved
      }))

    // _ placeholder — numbered left-to-right via mutable counter
    case _: Term.Placeholder =>
      val i = placeholderIdx
      placeholderIdx += 1
      Pure(env.getOrElse(s"_$$${i}", located("Unexpected _")))

    // Lambda  x => body  or  (x, y) => body
    case Term.Function.After_4_6_0(paramClause, body) =>
      // Capture only true locals — keys that aren't shadowed by mutable
      // module-level state in `globals`.  Otherwise a `var` reassigned after
      // the lambda is built would still read its capture-time snapshot.
      Pure(Value.FunV(paramClause.values.map(_.name.value), body, env -- globals.keys))

    // Partial function  { case pat => body; ... }  — e.g. xs.map { case (k, v) => ... }
    case Term.PartialFunction(cases) =>
      Pure(Value.NativeFnV("partial", args => {
        val arg = args match
          case List(v) => v
          case vs      => Value.TupleV(vs)
        cases.iterator
          .flatMap { c =>
            matchPat(c.pat, arg, env).flatMap { patEnv =>
              val guardOk = c.cond.forall(g => Computation.run(eval(g, patEnv)) match
                case Value.BoolV(b) => b
                case _              => false)
              if guardOk then Some(eval(c.body, patEnv)) else None
            }
          }
          .nextOption()
          .getOrElse(located(s"Partial function match failure: ${Value.show(arg)}"))
      }))

    // Match / pattern match
    case t: Term.Match =>
      eval(t.expr, env).flatMap { scrutV =>
        t.casesBlock.cases.iterator
          .flatMap { c =>
            matchPat(c.pat, scrutV, env).flatMap { patEnv =>
              val guardOk = c.cond.forall(g => Computation.run(eval(g, patEnv)) match
                case Value.BoolV(b) => b
                case _              => false)
              if guardOk then Some(eval(c.body, patEnv)) else None
            }
          }
          .nextOption()
          .getOrElse(located(s"Match failure: ${Value.show(scrutV)}"))
      }

    // Tuple  (a, b, ...)
    case Term.Tuple(elems) =>
      evalArgs(elems, env)(vs => Pure(Value.TupleV(vs)))

    // new ClassName(args)
    case Term.New(Init.After_4_6_0(tpe, _, argClauses)) =>
      val typeName = tpe match { case Type.Name(n) => n; case _ => "?" }
      val argTerms = argClauses.toList.flatMap(_.values)
      evalArgs(argTerms, env) { argVals =>
        env.getOrElse(typeName, globals.getOrElse(typeName,
          located(s"Unknown constructor: $typeName"))) match
            case c: Value.NativeFnV => c.f(argVals)
            case f: Value.FunV      => callFun(f, argVals)
            case _ => located(s"$typeName is not a constructor")
      }

    // for x <- xs yield f(x)
    case t: Term.ForYield =>
      evalForYield(t.enumsBlock.enums, t.body, env)

    // for x <- xs do f(x)
    case t: Term.For =>
      evalForDo(t.enumsBlock.enums, t.body, env, Map.empty).map(_ => Value.UnitV)

    // while cond do body  — refresh env from globals each iteration so mutations are visible
    case t: Term.While =>
      def loop: Computation =
        val freshEnv = env.map { (k, v) => k -> globals.getOrElse(k, v) }
        eval(t.expr, freshEnv).flatMap {
          case Value.BoolV(true) => eval(t.body, freshEnv).flatMap(_ => loop)
          case _                 => Pure(Value.UnitV)
        }
      loop

    // return expr  (non-local via exception)
    case Term.Return(expr) =>
      eval(expr, env).flatMap(v => throw ReturnSignal(v))

    // var/field assignment
    case Term.Assign(Term.Name(name), rhs) =>
      eval(rhs, env).flatMap { v => globals(name) = v; Pure(Value.UnitV) }

    // summon[TC[T]] — retrieve a given instance from the table
    case t: Term.ApplyType =>
      (t.fun, t.argClause.values) match
        case (Term.Name("summon"), List(typeArg)) =>
          val key = typeArg match
            case n: Type.Name  => n.value
            case ta: Type.Apply =>
              val tc  = ta.tpe match { case n: Type.Name => n.value; case _ => located("summon: bad type") }
              val arg = ta.argClause.values match { case List(n: Type.Name) => n.value; case _ => "_" }
              s"$tc[$arg]"
            case _ => located("summon: unsupported type")
          Pure(env.getOrElse(key, globals.getOrElse(key, located(s"No given for $key"))))
        case _ => eval(t.fun, env)  // other type applications — erase type args

    case other => located(s"Cannot eval: ${other.productPrefix}")

  /** Evaluate a list of argument terms eagerly to a list of Computations, then
   *  thread their values into `k` via FlatMap chain.
   *
   *  Eager evaluation matters because `eval` mutates `placeholderIdx`; deferring
   *  sub-term evaluation into a FlatMap continuation would observe a wrong index
   *  later. After this call all sub-Computations are fully built; only the final
   *  composition (the FlatMap chain) is interpreted lazily. */
  private def evalArgs(args: List[Term], env: Env)(k: List[Value] => Computation): Computation =
    val argComps = args.map(eval(_, env))
    threadValues(argComps)(k)

  /** Thread a list of already-built Computations: bind each in order and feed
   *  the resulting values to `k`. */
  private def threadValues(comps: List[Computation])(k: List[Value] => Computation): Computation =
    def chain(remaining: List[Computation], acc: List[Value]): Computation = remaining match
      case Nil       => k(acc.reverse)
      case c :: rest => FlatMap(c, v => chain(rest, v :: acc))
    chain(comps, Nil)

  /** Evaluate a block of statements; effects propagate through statements via flatMap.
   *  val/var declarations are threaded as Computation so effects in their rhs work. */
  private def evalBlock(stats: List[Stat], env: Env): Computation =
    val local = mutable.Map.from(env)
    def step(remaining: List[Stat], lastVal: Value): Computation = remaining match
      case Nil => Pure(lastVal)
      case s :: rest =>
        // Re-read globals into local so mutations from prior statements are visible
        local.keys.foreach { k => globals.get(k).foreach(local(k) = _) }
        s match
          case Defn.Val(_, pats, _, rhs) =>
            eval(rhs, local.toMap).flatMap { rhsVal =>
              pats match
                case List(Pat.Var(n)) => local(n.value) = rhsVal
                case List(pat) =>
                  matchPat(pat, rhsVal, local.toMap) match
                    case Some(patEnv) => patEnv.foreach { (k, v) => local(k) = v }
                    case None         => located("Val pattern match failed")
                case _ => ()
              step(rest, Value.UnitV)
            }
          case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
            eval(rhs, local.toMap).flatMap { v =>
              local(n.value) = v
              step(rest, Value.UnitV)
            }
          case t: Term =>
            eval(t, local.toMap).flatMap(v => step(rest, v))
          case stat =>
            execStat(stat, local)
            step(rest, Value.UnitV)
    step(stats, Value.UnitV)

  // ─── Call helpers ─────────────────────────────────────────────────

  private def callValue(fn: Value, args: List[Value], env: Env): Computation = fn match
    case f: Value.FunV      => callFun(f, args)
    case f: Value.NativeFnV => f.f(args)
    case Value.InstanceV(_, fields) =>
      fields.get("apply") match
        case Some(f) => callValue(f, args, env)
        case None    => located(s"Instance is not callable")
    // `xs(i)` and `m(k)` — apply-as-indexing on collections.
    case _: Value.ListV | _: Value.MapV => dispatch(fn, "apply", args, env)
    case _ => located(s"Not callable: ${Value.show(fn)}")

  private def callFun(f: Value.FunV, args: List[Value]): Computation =
    // Auto-tuple: an N-parameter lambda passed where a 1-arg function on
    // an N-tuple is expected (e.g. `pairs.foreach((n, s) => ...)`) gets
    // its single tuple argument destructured into the N parameters.
    val tupledArgs = args match
      case List(Value.TupleV(elems))
        if f.params.length > 1 && elems.length == f.params.length =>
        elems
      case _ => args
    // Only allocate the defaults-pass base env when defaults are actually
    // needed — most calls pass enough args and skip the allocation.
    // `eval` for Term.Name already falls back through `globals`, so locals
    // (closure + self ref + params) are all the env we need.
    val effArgs =
      if tupledArgs.length >= f.params.length then tupledArgs
      else
        val selfEntry = if f.name.nonEmpty then Map(f.name -> f) else Map.empty
        applyDefaults(f.params, f.defaults, tupledArgs, f.closure ++ selfEntry)
    val info      = tcoInfoFor(f)
    val hasMutualTail = info.tailTargets.nonEmpty && info.tailTargets.exists { n =>
      (globals.get(n) orElse f.closure.get(n)).exists(_.isInstanceOf[Value.FunV])
    }
    if info.noNonTailSelf && (info.isSelfTailRec || hasMutualTail) then
      tcoTrampoline(f, effArgs, null)
    else
      // Build callEnv directly: closure + (self ref if named) + params bound
      // to args.  Specialise the common 1- and 2-parameter cases so we use
      // `.updated` chains (which produce HashMap2 / HashMap3 specialisations)
      // instead of allocating an intermediate Map via `zip.toMap`.
      val withSelf =
        if f.name.nonEmpty then f.closure.updated(f.name, f) else f.closure
      val callEnv = f.params match
        case Nil               => withSelf
        case p :: Nil          => withSelf.updated(p, effArgs.head)
        case p1 :: p2 :: Nil   => withSelf.updated(p1, effArgs.head).updated(p2, effArgs(1))
        case _                 => withSelf ++ f.params.iterator.zip(effArgs.iterator).toMap
      try runUntilSuspension(eval(f.body, callEnv))
      catch case r: ReturnSignal => Pure(r.value)

  /** Extend `args` with default values for any missing trailing parameters.
   *  Each default is evaluated in `baseEnv` augmented with the bindings of all
   *  parameters to its left (provided ones plus already-filled defaults), so
   *  defaults like `def f(x: Int, y: Int = x + 1)` see `x` correctly. */
  private def applyDefaults(
    params:   List[String],
    defaults: List[Option[Term]],
    args:     List[Value],
    baseEnv:  Env
  ): List[Value] =
    if args.length >= params.length then args
    else
      val provided = args
      var env = baseEnv ++ params.zip(provided).toMap
      val filled = (provided.length until params.length).map { i =>
        val pname      = params(i)
        val defaultOpt = defaults.lift(i).flatten
        defaultOpt match
          case Some(defaultTerm) =>
            val v = Computation.run(eval(defaultTerm, env))
            env = env + (pname -> v)
            v
          case None =>
            located(s"missing argument for parameter '$pname'")
      }.toList
      provided ++ filled

  /** TCO trampoline that survives effect suspensions.
   *
   *  Body code is evaluated under an env that maps the function name (and any
   *  mutually-recursive friends) to native fns that throw `TailCall` /
   *  `MutualTailCall`. The outer while-loop catches those and re-runs the body
   *  with the new arg vector — same trick as the classic trampoline, but here
   *  the inner step uses `runUntilSuspension` so Performs propagate.
   *
   *  When the body suspends at `FlatMap(Perform, k)`, the trampoline returns
   *  the Perform to its caller (the enclosing handler) but wraps `k` so that
   *  when resume invokes it, control re-enters `tcoTrampoline` with `k(v)` as
   *  the initial Computation. This way TailCalls thrown by the post-suspension
   *  code are caught by the trampoline, not by an already-exited frame.
   *
   *  Each `resume` invocation pays one Scala stack frame for re-entering the
   *  trampoline; subsequent tail iterations and bind-chain stepping use the
   *  while-loops and stay O(1). */
  private def tcoTrampoline(
    initialFun:  Value.FunV,
    initialArgs: List[Value],
    initialComp: Computation
  ): Computation =
    var curFun: Value.FunV   = initialFun
    var curArgs: List[Value] = initialArgs
    var current: Computation = initialComp
    // Stable, per-curFun part of the call env: closure + self-tco stub +
    // mutual-tail-call stubs. The only thing that varies across tail
    // iterations is the param binding, so build this once per `curFun`
    // and refresh it only when a mutual jump changes `curFun`.
    var envStable: Map[String, Value] = null
    var envStableFor: Value.FunV      = null
    while true do
      try
        if current == null then
          if (envStable eq null) || (envStableFor ne curFun) then
            val targets = tcoInfoFor(curFun).tailTargets
            val mutualEntries: Map[String, Value] = targets.flatMap { name =>
              (globals.get(name) orElse curFun.closure.get(name)).collect {
                case fn: Value.FunV =>
                  name -> (Value.NativeFnV(name, a => throw new MutualTailCall(fn, a)): Value)
              }
            }.toMap
            val selfTco = Value.NativeFnV(curFun.name, a => throw new TailCall(a))
            envStable     = curFun.closure.updated(curFun.name, selfTco) ++ mutualEntries
            envStableFor  = curFun
          val callEnv = curFun.params match
            case Nil               => envStable
            case p :: Nil          => envStable.updated(p, curArgs.head)
            case p1 :: p2 :: Nil   => envStable.updated(p1, curArgs.head).updated(p2, curArgs(1))
            case _                 => envStable ++ curFun.params.iterator.zip(curArgs.iterator).toMap
          current = eval(curFun.body, callEnv)
        // Inner step loop — re-associate FlatMaps and step Pure short-circuits.
        // Exits via `return` inside the match; the condition stays `true`.
        while true do
          current match
            case Pure(_)              => return current
            case Perform(_, _, _)     => return current
            case FlatMap(sub, k) => sub match
              case Pure(v)               => current = k(v)
              case Perform(eff, op, a)   =>
                val funSnapshot  = curFun
                val argsSnapshot = curArgs
                return FlatMap(Perform(eff, op, a),
                  v => tcoTrampoline(funSnapshot, argsSnapshot, k(v)))
              case FlatMap(sub2, g)      =>
                current = FlatMap(sub2, x => FlatMap(g(x), k))
      catch
        case r: ReturnSignal    => return Pure(r.value)
        case tc: TailCall       =>
          curArgs = tc.args
          current = null
        case mc: MutualTailCall =>
          val next = mc.f
          if next.name.nonEmpty && tcoInfoFor(next).noNonTailSelf then
            curFun  = next
            curArgs = mc.args
            current = null
          else
            return callFun(next, mc.args)
    throw InterpretError("unreachable")

  /** Run a Computation through Pure short-circuits and FlatMap re-associations
   *  until it either resolves to Pure, or hits a Perform that needs to escape
   *  to an outer handler. The while-loop with right-association makes this
   *  stack-safe regardless of how deep the bind chain is (Bjarnason 2012).
   *
   *  ReturnSignal / TailCall / MutualTailCall propagate to the caller. */
  private def runUntilSuspension(c: Computation): Computation =
    var current: Computation = c
    while true do
      current match
        case Pure(_)             => return current
        case Perform(_, _, _)    => return current
        case FlatMap(sub, f) => sub match
          case Pure(v)              => current = f(v)
          case Perform(eff, op, args) =>
            return FlatMap(Perform(eff, op, args), f)
          case FlatMap(sub2, g)     =>
            current = FlatMap(sub2, x => FlatMap(g(x), f))
    throw InterpretError("unreachable")

  /** True if `fname` appears in a tail position of `tree`. */
  private def callsInTailPos(tree: scala.meta.Tree, fname: String): Boolean = tree match
    case Term.Apply.After_4_6_0(Term.Name(`fname`), _) => true
    case t: Term.If =>
      callsInTailPos(t.thenp, fname) || callsInTailPos(t.elsep, fname)
    case Term.Block(stats) =>
      stats.lastOption.exists { case t: Term => callsInTailPos(t, fname); case _ => false }
    case t: Term.Match =>
      t.casesBlock.cases.exists(c => callsInTailPos(c.body, fname))
    case _ => false

  // Returns names of functions called in tail position in term (excluding selfName).
  private def tailCallTargets(tree: scala.meta.Tree, selfName: String, tailPos: Boolean): Set[String] =
    tree match
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) =>
        if tailPos && n != selfName then Set(n)
        else argClause.values.flatMap(a => tailCallTargets(a, selfName, false)).toSet
      case t: Term.If =>
        tailCallTargets(t.cond,  selfName, false) ++
        tailCallTargets(t.thenp, selfName, tailPos) ++
        tailCallTargets(t.elsep, selfName, tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).flatMap(s => tailCallTargets(s, selfName, false)).toSet ++
        stats.lastOption.map(s => tailCallTargets(s, selfName, tailPos)).getOrElse(Set.empty)
      case t: Term.Match =>
        tailCallTargets(t.expr, selfName, false) ++
        t.casesBlock.cases.flatMap(c => tailCallTargets(c.body, selfName, tailPos)).toSet
      case other =>
        other.children.flatMap(c => tailCallTargets(c, selfName, false)).toSet

  // Returns true if term has a self-call to fname NOT in tail position.
  private def hasNonTailSelfCall(term: Term, fname: String, tailPos: Boolean): Boolean =
    import scala.meta.*
    term match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
        if tailPos then argClause.values.collect { case t: Term => t }
                                        .exists(hasNonTailSelfCall(_, fname, tailPos = false))
        else true
      case t: Term.If =>
        hasNonTailSelfCall(t.cond,  fname, tailPos = false) ||
        hasNonTailSelfCall(t.thenp, fname, tailPos = tailPos) ||
        hasNonTailSelfCall(t.elsep, fname, tailPos = tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).exists {
          case t: Term => hasNonTailSelfCall(t, fname, tailPos = false)
          case _       => false
        } || stats.lastOption.exists {
          case t: Term => hasNonTailSelfCall(t, fname, tailPos = tailPos)
          case _       => false
        }
      case t: Term.Match =>
        hasNonTailSelfCall(t.expr, fname, tailPos = false) ||
        t.casesBlock.cases.exists(c => hasNonTailSelfCall(c.body, fname, tailPos = tailPos))
      case other =>
        anywhereContainsSelfCall(other, fname)

  private def anywhereContainsSelfCall(tree: scala.meta.Tree, fname: String): Boolean =
    import scala.meta.*
    tree match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), _) => true
      case t => t.children.exists(anywhereContainsSelfCall(_, fname))

  // ─── Algebraic effects (Free Monad interpreter) ──────────────────

  private def isEffectOpDef(body: Term): Boolean = body match
    case Term.Name("__effectOp__") => true
    case _                         => false

  /** Interpret `handle(body) { cases }` — trampolined.
   *
   *  The body is evaluated to a Computation tree. We walk it with a while-loop:
   *
   *    Pure(v)                     → return Pure(v)
   *    Perform(eff, op, args)      → handler matched? dispatch with resume = identity
   *                                   else propagate as-is (no continuation to wrap)
   *    FlatMap(Pure(v), f)         → step to f(v)
   *    FlatMap(FlatMap(c, g), f)   → re-associate to FlatMap(c, x => FlatMap(g(x), f))
   *    FlatMap(Perform(...), f)    → handler matched? dispatch with resume = v => interp(f(v))
   *                                   else propagate as FlatMap(Perform, v => interp(f(v)))
   *
   *  The re-association keeps the Scala call stack O(1) regardless of how deeply
   *  the bind chain is nested. `resume(v) = interp(k(v))` is itself a closure;
   *  invoking it (from the handler case body) starts a fresh trampoline.
   *  Multi-shot is calling that closure more than once — each invocation walks
   *  a fresh branch of the tree.
   */
  private def evalHandle(body: Term, cases: List[Case], env: Env): Computation =
    val handledOps: Set[(String, String)] = cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), _) => Some((eff, op))
        case _ => None
    }.toSet

    def dispatchCase(eff: String, op: String, args: List[Value], resume: Value): Computation =
      cases.iterator.flatMap { c =>
        c.pat match
          case Pat.Extract.After_4_6_0(Term.Select(Term.Name(`eff`), Term.Name(`op`)), argClause) =>
            val patArgs   = argClause.values
            val argPats   = patArgs.dropRight(1).map(_.asInstanceOf[Pat])
            val resumePat = patArgs.lastOption
            argPats.zip(args).foldLeft(Option(env): Option[Env]) {
              case (Some(e), (pat, v)) => matchPat(pat, v, e)
              case (None, _)           => None
            }.flatMap { argEnv =>
              val finalEnv = resumePat match
                case Some(pv: Pat.Var) => argEnv + (pv.name.value -> resume)
                case _                 => argEnv
              val guardOk = c.cond.forall { g =>
                Computation.run(eval(g, finalEnv)) match
                  case Value.BoolV(b) => b
                  case _              => false
              }
              if guardOk then Some(eval(c.body, finalEnv)) else None
            }
          case _ => None
      }.nextOption()
        .getOrElse(throw InterpretError(s"Unhandled effect: $eff.$op (no matching case)"))

    def interp(initial: Computation): Computation =
      var current: Computation = initial
      while true do
        current match
          case Pure(_) => return current
          case Perform(eff, op, args) =>
            if !handledOps.contains((eff, op)) then return current
            else
              val resume = Value.NativeFnV("resume", rargs => {
                val v = rargs match
                  case List(v) => v; case Nil => Value.UnitV; case vs => Value.TupleV(vs)
                Pure(v)  // bare Perform: no rest — resume returns the injected value
              })
              current = dispatchCase(eff, op, args, resume)
          case FlatMap(sub, f) => sub match
            case Pure(v) =>
              current = f(v)
            case FlatMap(sub2, g) =>
              current = FlatMap(sub2, x => FlatMap(g(x), f))
            case Perform(eff, op, args) =>
              if !handledOps.contains((eff, op)) then
                // Unhandled: propagate, but re-enter this handler's interp on
                // resume so nested Performs handled here still get dispatched
                // by us when an outer handler resumes the continuation.
                return FlatMap(Perform(eff, op, args), v => interp(f(v)))
              else
                // Handled: resume runs the captured continuation through interp
                // to a Value (multi-shot: each call interprets a fresh branch
                // and the case body composes the values directly via JS-level
                // flatMap/etc.). interp's inner while-loop is stack-safe in the
                // bind-chain depth; the recursion across sequential handler
                // dispatches grows stack linearly with handler-dispatch depth.
                val resume = Value.NativeFnV("resume", rargs => {
                  val v = rargs match
                    case List(v) => v; case Nil => Value.UnitV; case vs => Value.TupleV(vs)
                  interp(f(v))
                })
                current = dispatchCase(eff, op, args, resume)
      throw InterpretError("unreachable")

    interp(eval(body, env))

  // ─── Infix operators ──────────────────────────────────────────────

  private def infix(lhs: Value, op: String, args: List[Value], env: Env): Computation =
    val rhs = args.headOption.getOrElse(Value.UnitV)
    (lhs, op, rhs) match
      case (Value.IntV(a),    "+",  Value.IntV(b))    => Pure(Value.IntV(a + b))
      case (Value.IntV(a),    "-",  Value.IntV(b))    => Pure(Value.IntV(a - b))
      case (Value.IntV(a),    "*",  Value.IntV(b))    => Pure(Value.IntV(a * b))
      case (Value.IntV(a),    "/",  Value.IntV(b))    => Pure(Value.IntV(a / b))
      case (Value.IntV(a),    "%",  Value.IntV(b))    => Pure(Value.IntV(a % b))
      case (Value.DoubleV(a), "+",  Value.DoubleV(b)) => Pure(Value.DoubleV(a + b))
      case (Value.DoubleV(a), "-",  Value.DoubleV(b)) => Pure(Value.DoubleV(a - b))
      case (Value.DoubleV(a), "*",  Value.DoubleV(b)) => Pure(Value.DoubleV(a * b))
      case (Value.DoubleV(a), "/",  Value.DoubleV(b)) => Pure(Value.DoubleV(a / b))
      case (Value.IntV(a),    "+",  Value.DoubleV(b)) => Pure(Value.DoubleV(a + b))
      case (Value.DoubleV(a), "+",  Value.IntV(b))    => Pure(Value.DoubleV(a + b))
      case (Value.IntV(a),    "*",  Value.DoubleV(b)) => Pure(Value.DoubleV(a * b))
      case (Value.DoubleV(a), "*",  Value.IntV(b))    => Pure(Value.DoubleV(a * b))
      case (Value.IntV(a),    "-",  Value.DoubleV(b)) => Pure(Value.DoubleV(a - b))
      case (Value.DoubleV(a), "-",  Value.IntV(b))    => Pure(Value.DoubleV(a - b))
      case (Value.IntV(a),    "/",  Value.DoubleV(b)) => Pure(Value.DoubleV(a / b))
      case (Value.DoubleV(a), "/",  Value.IntV(b))    => Pure(Value.DoubleV(a / b))
      case (Value.StringV(a), "+",  b)                => Pure(Value.StringV(a + Value.show(b)))
      case (Value.StringV(a), "*",  Value.IntV(n))    => Pure(Value.StringV(a * n.toInt))
      case (a, "==",  b) => Pure(Value.BoolV(a == b))
      case (a, "!=",  b) => Pure(Value.BoolV(a != b))
      case (Value.IntV(a),    "<",  Value.IntV(b))    => Pure(Value.BoolV(a < b))
      case (Value.IntV(a),    ">",  Value.IntV(b))    => Pure(Value.BoolV(a > b))
      case (Value.IntV(a),    "<=", Value.IntV(b))    => Pure(Value.BoolV(a <= b))
      case (Value.IntV(a),    ">=", Value.IntV(b))    => Pure(Value.BoolV(a >= b))
      case (Value.DoubleV(a), "<",  Value.DoubleV(b)) => Pure(Value.BoolV(a < b))
      case (Value.DoubleV(a), ">",  Value.DoubleV(b)) => Pure(Value.BoolV(a > b))
      case (Value.DoubleV(a), "<=", Value.DoubleV(b)) => Pure(Value.BoolV(a <= b))
      case (Value.DoubleV(a), ">=", Value.DoubleV(b)) => Pure(Value.BoolV(a >= b))
      case (Value.BoolV(a),   "&&", Value.BoolV(b))   => Pure(Value.BoolV(a && b))
      case (Value.BoolV(a),   "||", Value.BoolV(b))   => Pure(Value.BoolV(a || b))
      case (v, "::",  Value.ListV(ls))                => Pure(Value.ListV(v :: ls))
      case (Value.ListV(a), "++", Value.ListV(b))     => Pure(Value.ListV(a ++ b))
      case (Value.ListV(a), ":::", Value.ListV(b))    => Pure(Value.ListV(a ++ b))
      case (Value.ListV(ls), ":+", v)                 => Pure(Value.ListV(ls :+ v))
      case (v, "+:",  Value.ListV(ls))                => Pure(Value.ListV(v +: ls))
      case (k, "->", v)                               => Pure(Value.TupleV(List(k, v)))
      // Fallback: method call on lhs
      case _ => dispatch(lhs, op, args, env)

  // ─── Dispatch ─────────────────────────────────────────────────────

  private def dispatch(recv: Value, name: String, args: List[Value], env: Env): Computation =
    (recv, name, args) match
      // ── String ──────────────────────────────────────────────────
      case (Value.StringV(s), "length",       Nil) => Pure(Value.IntV(s.length.toLong))
      case (Value.StringV(s), "size",         Nil) => Pure(Value.IntV(s.length.toLong))
      case (Value.StringV(s), "isEmpty",      Nil) => Pure(Value.BoolV(s.isEmpty))
      case (Value.StringV(s), "nonEmpty",     Nil) => Pure(Value.BoolV(s.nonEmpty))
      case (Value.StringV(s), "trim",         Nil) => Pure(Value.StringV(s.trim))
      case (Value.StringV(s), "toUpperCase",  Nil) => Pure(Value.StringV(s.toUpperCase))
      case (Value.StringV(s), "toLowerCase",  Nil) => Pure(Value.StringV(s.toLowerCase))
      case (Value.StringV(s), "reverse",      Nil) => Pure(Value.StringV(s.reverse))
      case (Value.StringV(s), "toInt",        Nil) => Pure(Value.IntV(s.toLong))
      case (Value.StringV(s), "toDouble",     Nil) => Pure(Value.DoubleV(s.toDouble))
      case (Value.StringV(s), "toString",     Nil) => Pure(Value.StringV(s))
      case (Value.StringV(s), "contains",     List(Value.StringV(t))) => Pure(Value.BoolV(s.contains(t)))
      case (Value.StringV(s), "startsWith",   List(Value.StringV(t))) => Pure(Value.BoolV(s.startsWith(t)))
      case (Value.StringV(s), "endsWith",     List(Value.StringV(t))) => Pure(Value.BoolV(s.endsWith(t)))
      case (Value.StringV(s), "split",        List(Value.StringV(sep))) =>
        Pure(Value.ListV(s.split(java.util.regex.Pattern.quote(sep)).toList.map(Value.StringV(_))))
      case (Value.StringV(s), "mkString",     _)  => Pure(Value.StringV(s))
      case (Value.StringV(s), "take",         List(Value.IntV(n))) => Pure(Value.StringV(s.take(n.toInt)))
      case (Value.StringV(s), "drop",         List(Value.IntV(n))) => Pure(Value.StringV(s.drop(n.toInt)))
      case (Value.StringV(s), "replace",      List(Value.StringV(a), Value.StringV(b))) => Pure(Value.StringV(s.replace(a, b)))
      case (Value.StringV(s), "map",          List(f)) =>
        Computation.sequence(s.toList.map(c => callValue(f, List(Value.CharV(c)), env))).map {
          case Value.ListV(items) => Value.StringV(items.map(Value.show).mkString)
          case _                  => Value.StringV(s)
        }
      // ── List ────────────────────────────────────────────────────
      case (Value.ListV(ls), "length",     Nil)  => Pure(Value.IntV(ls.length.toLong))
      case (Value.ListV(ls), "size",       Nil)  => Pure(Value.IntV(ls.size.toLong))
      case (Value.ListV(ls), "indices",    Nil)  => Pure(Value.ListV(ls.indices.map(i => Value.IntV(i.toLong)).toList))
      case (Value.ListV(ls), "apply",      List(Value.IntV(i))) =>
        if i < 0 || i >= ls.length then located(s"index $i out of bounds for list of length ${ls.length}")
        else Pure(ls(i.toInt))
      case (Value.ListV(ls), "isEmpty",    Nil)  => Pure(Value.BoolV(ls.isEmpty))
      case (Value.ListV(ls), "nonEmpty",   Nil)  => Pure(Value.BoolV(ls.nonEmpty))
      case (Value.ListV(ls), "head",       Nil)  => Pure(ls.headOption.getOrElse(located("head on Nil")))
      case (Value.ListV(ls), "tail",       Nil)  => Pure(Value.ListV(ls.tail))
      case (Value.ListV(ls), "last",       Nil)  => Pure(ls.lastOption.getOrElse(located("last on Nil")))
      case (Value.ListV(ls), "init",       Nil)  => Pure(Value.ListV(ls.init))
      case (Value.ListV(ls), "reverse",    Nil)  => Pure(Value.ListV(ls.reverse))
      case (Value.ListV(ls), "distinct",   Nil)  => Pure(Value.ListV(ls.distinct))
      case (Value.ListV(ls), "sorted",     Nil)  => Pure(Value.ListV(ls.sortBy(Value.show)))
      case (Value.ListV(ls), "toList",     Nil)  => Pure(Value.ListV(ls))
      case (Value.ListV(ls), "toSet",      Nil)  => Pure(Value.ListV(ls.distinct))
      case (Value.ListV(ls), "contains",   List(v)) => Pure(Value.BoolV(ls.contains(v)))
      case (Value.ListV(ls), "indexOf",    List(v)) => Pure(Value.IntV(ls.indexOf(v).toLong))
      case (Value.ListV(ls), "take",       List(Value.IntV(n))) => Pure(Value.ListV(ls.take(n.toInt)))
      case (Value.ListV(ls), "drop",       List(Value.IntV(n))) => Pure(Value.ListV(ls.drop(n.toInt)))
      case (Value.ListV(ls), "takeRight",  List(Value.IntV(n))) => Pure(Value.ListV(ls.takeRight(n.toInt)))
      case (Value.ListV(ls), "dropRight",  List(Value.IntV(n))) => Pure(Value.ListV(ls.dropRight(n.toInt)))
      // Higher-order: sequence the callback computations
      case (Value.ListV(ls), "map",        List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env)))
      case (Value.ListV(ls), "flatMap",    List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map {
          case Value.ListV(results) => Value.ListV(results.flatMap {
            case Value.ListV(inner) => inner
            case v                  => List(v)
          })
          case other => other
        }
      case (Value.ListV(ls), "filter",     List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map {
          case Value.ListV(flags) =>
            Value.ListV(ls.zip(flags).collect { case (v, Value.BoolV(true)) => v })
          case other => other
        }
      case (Value.ListV(ls), "filterNot",  List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map {
          case Value.ListV(flags) =>
            Value.ListV(ls.zip(flags).collect { case (v, Value.BoolV(false)) => v })
          case other => other
        }
      case (Value.ListV(ls), "foreach",    List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map(_ => Value.UnitV)
      case (Value.ListV(ls), "count",      List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map {
          case Value.ListV(flags) =>
            Value.IntV(flags.count { case Value.BoolV(true) => true; case _ => false }.toLong)
          case _ => Value.IntV(0)
        }
      case (Value.ListV(ls), "find",       List(f)) =>
        def loop(remaining: List[Value]): Computation = remaining match
          case Nil => Pure(Value.OptionV(None))
          case h :: rest =>
            callValue(f, List(h), env).flatMap {
              case Value.BoolV(true) => Pure(Value.OptionV(Some(h)))
              case _                 => loop(rest)
            }
        loop(ls)
      case (Value.ListV(ls), "exists",     List(f)) =>
        def loop(remaining: List[Value]): Computation = remaining match
          case Nil => Pure(Value.BoolV(false))
          case h :: rest =>
            callValue(f, List(h), env).flatMap {
              case Value.BoolV(true) => Pure(Value.BoolV(true))
              case _                 => loop(rest)
            }
        loop(ls)
      case (Value.ListV(ls), "forall",     List(f)) =>
        def loop(remaining: List[Value]): Computation = remaining match
          case Nil => Pure(Value.BoolV(true))
          case h :: rest =>
            callValue(f, List(h), env).flatMap {
              case Value.BoolV(false) => Pure(Value.BoolV(false))
              case _                  => loop(rest)
            }
        loop(ls)
      case (Value.ListV(ls), "sortBy",     List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map {
          case Value.ListV(keys) =>
            Value.ListV(ls.zip(keys).sortBy(p => Value.show(p._2)).map(_._1))
          case _ => Value.ListV(ls)
        }
      case (Value.ListV(ls), "zip",        List(Value.ListV(other))) =>
        Pure(Value.ListV(ls.zip(other).map { case (a, b) => Value.TupleV(List(a, b)) }))
      case (Value.ListV(ls), "zipWithIndex", Nil) =>
        Pure(Value.ListV(ls.zipWithIndex.map { case (a, i) => Value.TupleV(List(a, Value.IntV(i.toLong))) }))
      case (Value.ListV(ls), "mkString",   Nil)  => Pure(Value.StringV(ls.map(Value.show).mkString))
      case (Value.ListV(ls), "mkString",   List(Value.StringV(sep))) =>
        Pure(Value.StringV(ls.map(Value.show).mkString(sep)))
      case (Value.ListV(ls), "mkString",   List(Value.StringV(s), Value.StringV(sep), Value.StringV(e))) =>
        Pure(Value.StringV(ls.map(Value.show).mkString(s, sep, e)))
      case (Value.ListV(ls), "sum",        Nil)  =>
        Pure(ls.foldLeft[Value](Value.IntV(0)) {
          case (Value.IntV(a),    Value.IntV(b))    => Value.IntV(a + b)
          case (Value.DoubleV(a), Value.DoubleV(b)) => Value.DoubleV(a + b)
          case (Value.IntV(a),    Value.DoubleV(b)) => Value.DoubleV(a + b)
          case (Value.DoubleV(a), Value.IntV(b))    => Value.DoubleV(a + b)
          case (a, b) => located(s"Cannot sum $a and $b")
        })
      case (Value.ListV(ls), "min",        Nil)  =>
        Pure(ls.minBy { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 })
      case (Value.ListV(ls), "max",        Nil)  =>
        Pure(ls.maxBy { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 })
      case (Value.ListV(ls), "foldLeft",   List(init)) =>
        Pure(Value.NativeFnV("foldLeft", {
          case List(f) =>
            def loop(remaining: List[Value], acc: Value): Computation = remaining match
              case Nil       => Pure(acc)
              case h :: rest => callValue(f, List(acc, h), env).flatMap(v => loop(rest, v))
            loop(ls, init)
          case _ => throw InterpretError("foldLeft expects one function argument")
        }))
      case (Value.ListV(ls), "foldRight",  List(init)) =>
        Pure(Value.NativeFnV("foldRight", {
          case List(f) =>
            def loop(remaining: List[Value], acc: Value): Computation = remaining match
              case Nil       => Pure(acc)
              case h :: rest => callValue(f, List(h, acc), env).flatMap(v => loop(rest, v))
            loop(ls.reverse, init)
          case _ => throw InterpretError("foldRight expects one function argument")
        }))
      case (Value.ListV(ls), "reduceLeft", List(f)) =>
        ls match
          case Nil => located("reduceLeft on empty list")
          case h :: t =>
            def loop(remaining: List[Value], acc: Value): Computation = remaining match
              case Nil       => Pure(acc)
              case x :: rest => callValue(f, List(acc, x), env).flatMap(v => loop(rest, v))
            loop(t, h)
      case (Value.ListV(ls), "flatten",    Nil) =>
        Pure(Value.ListV(ls.flatMap { case Value.ListV(inner) => inner; case v => List(v) }))
      case (Value.ListV(ls), "sliding",    List(Value.IntV(n))) =>
        Pure(Value.ListV(ls.sliding(n.toInt).map(Value.ListV(_)).toList))
      case (Value.ListV(ls), "grouped",    List(Value.IntV(n))) =>
        Pure(Value.ListV(ls.grouped(n.toInt).map(Value.ListV(_)).toList))
      case (Value.ListV(ls), "appended",   List(v)) => Pure(Value.ListV(ls :+ v))
      case (Value.ListV(ls), "prepended",  List(v)) => Pure(Value.ListV(v +: ls))
      // ── Map ─────────────────────────────────────────────────────
      case (Value.MapV(m), "size",       Nil)     => Pure(Value.IntV(m.size.toLong))
      case (Value.MapV(m), "isEmpty",    Nil)     => Pure(Value.BoolV(m.isEmpty))
      case (Value.MapV(m), "nonEmpty",   Nil)     => Pure(Value.BoolV(m.nonEmpty))
      case (Value.MapV(m), "keys",       Nil)     => Pure(Value.ListV(m.keys.toList))
      case (Value.MapV(m), "values",     Nil)     => Pure(Value.ListV(m.values.toList))
      case (Value.MapV(m), "toList",     Nil)     =>
        Pure(Value.ListV(m.toList.map { (k, v) => Value.TupleV(List(k, v)) }))
      case (Value.MapV(m), "contains",   List(k)) => Pure(Value.BoolV(m.contains(k)))
      case (Value.MapV(m), "get",        List(k)) => Pure(Value.OptionV(m.get(k)))
      case (Value.MapV(m), "apply",      List(k)) =>
        Pure(m.getOrElse(k, located(s"Key not found: ${Value.show(k)}")))
      case (Value.MapV(m), "getOrElse",  List(k, d)) => Pure(m.getOrElse(k, d))
      case (Value.MapV(m), "updated",    List(k, v)) => Pure(Value.MapV(m + (k -> v)))
      case (Value.MapV(m), "removed",    List(k))    => Pure(Value.MapV(m - k))
      case (Value.MapV(m), "map",        List(f)) =>
        Computation.sequence(m.toList.map { (k, v) =>
          callValue(f, List(Value.TupleV(List(k, v))), env)
        }).map {
          case Value.ListV(entries) =>
            Value.MapV(entries.collect {
              case Value.TupleV(List(nk, nv)) => nk -> nv
            }.toMap)
          case _ => Value.MapV(Map.empty)
        }
      case (Value.MapV(m), "filter",     List(f)) =>
        val items = m.toList
        Computation.sequence(items.map { (k, v) =>
          callValue(f, List(Value.TupleV(List(k, v))), env)
        }).map {
          case Value.ListV(flags) =>
            Value.MapV(items.zip(flags).collect {
              case ((k, v), Value.BoolV(true)) => k -> v
            }.toMap)
          case _ => Value.MapV(Map.empty)
        }
      case (Value.MapV(m), "foreach",    List(f)) =>
        Computation.sequence(m.toList.map { (k, v) =>
          callValue(f, List(Value.TupleV(List(k, v))), env)
        }).map(_ => Value.UnitV)
      case (Value.MapV(m), "mkString",   Nil)  => Pure(Value.StringV(Value.show(Value.MapV(m))))
      // String-key access shorthand: map.key
      case (Value.MapV(m), key, Nil) =>
        Pure(m.getOrElse(Value.StringV(key), located(s"No key '$key' in map")))
      // ── Option ──────────────────────────────────────────────────
      case (Value.OptionV(Some(v)), "get",        Nil) => Pure(v)
      case (Value.OptionV(opt),     "isDefined",  Nil) => Pure(Value.BoolV(opt.isDefined))
      case (Value.OptionV(opt),     "isEmpty",    Nil) => Pure(Value.BoolV(opt.isEmpty))
      case (Value.OptionV(opt),     "nonEmpty",   Nil) => Pure(Value.BoolV(opt.nonEmpty))
      case (Value.OptionV(Some(v)), "getOrElse",  _)   => Pure(v)
      case (Value.OptionV(None),    "getOrElse",  List(d)) => Pure(d)
      case (Value.OptionV(opt),     "map",        List(f)) =>
        opt match
          case None    => Pure(Value.OptionV(None))
          case Some(v) => callValue(f, List(v), env).map(r => Value.OptionV(Some(r)))
      case (Value.OptionV(opt),     "flatMap",    List(f)) =>
        opt match
          case None    => Pure(Value.OptionV(None))
          case Some(v) => callValue(f, List(v), env).map {
            case o: Value.OptionV => o
            case other            => Value.OptionV(Some(other))
          }
      case (Value.OptionV(opt),     "filter",     List(f)) =>
        opt match
          case None    => Pure(Value.OptionV(None))
          case Some(v) => callValue(f, List(v), env).map {
            case Value.BoolV(true) => Value.OptionV(Some(v))
            case _                 => Value.OptionV(None)
          }
      case (Value.OptionV(opt),     "foreach",    List(f)) =>
        opt match
          case None    => Pure(Value.UnitV)
          case Some(v) => callValue(f, List(v), env).map(_ => Value.UnitV)
      case (Value.OptionV(opt),     "toList",     Nil) => Pure(Value.ListV(opt.toList))
      case (Value.OptionV(None),    "orElse",     List(other)) => Pure(other)
      case (opt: Value.OptionV,     "orElse",     _) => Pure(opt)
      // ── Int / Double ─────────────────────────────────────────────
      case (Value.IntV(n),    "toDouble",  Nil) => Pure(Value.DoubleV(n.toDouble))
      case (Value.IntV(n),    "toLong",    Nil) => Pure(Value.IntV(n))
      case (Value.IntV(n),    "toInt",     Nil) => Pure(Value.IntV(n))
      case (Value.IntV(n),    "abs",       Nil) => Pure(Value.IntV(math.abs(n)))
      case (Value.IntV(n),    "toString",  Nil) => Pure(Value.StringV(n.toString))
      case (Value.IntV(n),    "to",        List(Value.IntV(m))) =>
        Pure(Value.ListV((n to m).map(Value.IntV(_)).toList))
      case (Value.IntV(n),    "until",     List(Value.IntV(m))) =>
        Pure(Value.ListV((n until m).map(Value.IntV(_)).toList))
      case (Value.DoubleV(d), "toInt",     Nil) => Pure(Value.IntV(d.toLong))
      case (Value.DoubleV(d), "toLong",    Nil) => Pure(Value.IntV(d.toLong))
      case (Value.DoubleV(d), "abs",       Nil) => Pure(Value.DoubleV(math.abs(d)))
      case (Value.DoubleV(d), "toString",  Nil) => Pure(Value.StringV(d.toString))
      case (Value.DoubleV(d), "round",     Nil) => Pure(Value.IntV(math.round(d)))
      case (Value.DoubleV(d), "floor",     Nil) => Pure(Value.DoubleV(math.floor(d)))
      case (Value.DoubleV(d), "ceil",      Nil) => Pure(Value.DoubleV(math.ceil(d)))
      // ── Tuple ────────────────────────────────────────────────────
      case (Value.TupleV(es), "_1", Nil) => Pure(es(0))
      case (Value.TupleV(es), "_2", Nil) => Pure(es(1))
      case (Value.TupleV(es), "_3", Nil) => Pure(es(2))
      case (Value.TupleV(es), "_4", Nil) => Pure(es(3))
      // ── Class method (declared inside `class`/`case class` body) ──
      case (Value.InstanceV(typeName, fields), fname, fargs)
        if typeMethods.get(typeName).exists(_.contains(fname)) =>
        val fn = typeMethods(typeName)(fname)
        // Re-bind the method's closure with this instance's data fields so the
        // body can refer to them by name (`x`, `y`, …).
        callFun(fn.copy(closure = fn.closure ++ fields), fargs)
      // ── Instance (case class / enum case) field access ───────────
      // No-arg defs and no-arg native fns are called automatically on access
      case (Value.InstanceV(_, fields), fname, Nil) =>
        fields.get(fname) match
          case Some(f: Value.FunV)      if f.params.isEmpty => callFun(f, Nil)
          case Some(f: Value.NativeFnV)                     => f.f(Nil)
          case Some(v)                                       => Pure(v)
          case None                                          => located(s"No field '$fname'")
      // ── Enum companion call (Color.RGB(1,2,3)) ───────────────────
      case (Value.InstanceV(_, fields), fname, fargs) if fields.contains(fname) =>
        callValue(fields(fname), fargs, env)
      // ── Generic ──────────────────────────────────────────────────
      case (v, "toString", Nil)   => Pure(Value.StringV(Value.show(v)))
      case (v, "apply",    fargs) => callValue(v, fargs, env)
      // ── Extension method via given: "hello".show → Show[String].show("hello")
      case _ =>
        extensionDispatch(recv, name, args, env)
          .getOrElse(located(s"No method '$name' on ${recv.getClass.getSimpleName}(${Value.show(recv)})"))

  private def extensionDispatch(recv: Value, method: String, args: List[Value], env: Env): Option[Computation] =
    val typeName = recv match
      case _: Value.IntV        => "Int"
      case _: Value.DoubleV     => "Double"
      case _: Value.StringV     => "String"
      case _: Value.BoolV       => "Boolean"
      case _: Value.ListV       => "List"
      case _: Value.OptionV     => "Option"
      case _: Value.MapV        => "Map"
      case Value.InstanceV(t,_) => t
      case _                    => "Any"
    extensions.get((typeName, method)).map { fn =>
      callValue(fn, recv :: args, env)
    }.orElse {
      globals.values.collectFirst {
        case Value.InstanceV(name, fields)
          if name.endsWith(s"[$typeName]") && fields.contains(method) =>
          callValue(fields(method), recv :: args, env)
      }
    }

  // ─── Pattern matching ────────────────────────────────────────────

  private def matchPat(pat: Pat, scrutinee: Value, env: Env): Option[Env] = pat match
    case Pat.Wildcard()  => Some(env)
    case Pat.Var(name)   => Some(env + (name.value -> scrutinee))
    case lit: Lit =>
      val litV: Value = lit match
        case Lit.Int(v)     => Value.IntV(v.toLong)
        case Lit.Long(v)    => Value.IntV(v)
        case Lit.String(v)  => Value.StringV(v)
        case Lit.Boolean(v) => Value.BoolV(v)
        case Lit.Double(v)  => Value.DoubleV(v.toString.toDouble)
        case Lit.Null()     => Value.NullV
        case _              => Value.NullV
      Option.when(litV == scrutinee)(env)
    case Pat.Tuple(pats) =>
      scrutinee match
        case Value.TupleV(elems) if elems.length == pats.length =>
          pats.zip(elems).foldLeft(Option(env)) { (acc, pe) =>
            acc.flatMap(e => matchPat(pe._1, pe._2, e))
          }
        case _ => None
    case Pat.Extract.After_4_6_0(fn, argClause) =>
      val typeNameOpt = fn match
        case Term.Name(n)                 => Some(n)
        case Term.Select(_, Term.Name(n)) => Some(n)
        case _                            => None
      typeNameOpt.flatMap { typeName =>
        val args = argClause.values
        scrutinee match
          case Value.InstanceV(t, fields) if t == typeName =>
            val fieldVals = fields.values.toList
            if args.length != fieldVals.length then None
            else args.zip(fieldVals).foldLeft(Option(env)) { (acc, pe) =>
              acc.flatMap(e => matchPat(pe._1, pe._2, e))
            }
          case Value.OptionV(Some(v)) if typeName == "Some" && args.length == 1 =>
            matchPat(args.head, v, env)
          case Value.OptionV(None) if typeName == "None" && args.isEmpty =>
            Some(env)
          case _ => None
      }
    case Pat.Typed(inner, _)       => matchPat(inner, scrutinee, env)
    case Pat.Alternative(lhs, rhs) =>
      List(lhs, rhs).iterator.flatMap(p => matchPat(p, scrutinee, env)).nextOption()
    case t: Term.Name =>
      env.get(t.value).orElse(globals.get(t.value))
        .flatMap(v => Option.when(v == scrutinee)(env))
    case Term.Select(_, Term.Name(n)) =>
      env.get(n).orElse(globals.get(n))
        .flatMap(v => Option.when(v == scrutinee)(env))
    case _ => None

  // ─── For comprehension helpers ────────────────────────────────────

  private def evalCollection(v: Value): List[Value] = v match
    case Value.ListV(ls)        => ls
    case Value.OptionV(Some(v)) => List(v)
    case Value.OptionV(None)    => Nil
    case _ => located(s"Cannot iterate over ${Value.show(v)}")

  private def patVarNames(pat: Pat): Set[String] = pat match
    case Pat.Var(n)           => Set(n.value)
    case Pat.Wildcard()       => Set.empty
    case Pat.Tuple(pats)      => pats.flatMap(patVarNames).toSet
    case Pat.Extract.After_4_6_0(_, argClause) => argClause.values.flatMap(patVarNames).toSet
    case Pat.Typed(inner, _)  => patVarNames(inner)
    case _                    => Set.empty

  private def evalForYield(enums: List[Enumerator], body: Term, env: Env): Computation =
    enums match
      case Nil => eval(body, env)
      case Enumerator.Generator(pat, rhs) :: rest =>
        eval(rhs, env).flatMap { rhsV =>
          val items = evalCollection(rhsV)
          val isLast = rest.isEmpty
          // Evaluate each branch; matches that fail are skipped.
          val branches = items.flatMap { item =>
            matchPat(pat, item, env).map(patEnv => evalForYield(rest, body, patEnv))
          }
          Computation.sequence(branches).map {
            case Value.ListV(results) if isLast =>
              Value.ListV(results)
            case Value.ListV(results) =>
              Value.ListV(results.flatMap {
                case Value.ListV(ls) => ls
                case v               => List(v)
              })
            case other => other
          }
        }
      case Enumerator.Guard(cond) :: rest =>
        eval(cond, env).flatMap {
          case Value.BoolV(true) => evalForYield(rest, body, env)
          case _                 => Pure(Value.ListV(Nil))
        }
      case Enumerator.Val(pat, rhs) :: rest =>
        eval(rhs, env).flatMap { v =>
          matchPat(pat, v, env) match
            case Some(patEnv) => evalForYield(rest, body, patEnv)
            case None         => Pure(Value.ListV(Nil))
        }
      case _ :: rest => evalForYield(rest, body, env)

  // evalForDo keeps loop vars separate from globals so assignments to outer vars are visible.
  private def evalForDo(enums: List[Enumerator], body: Term, outerEnv: Env, loopVars: Env): Computation =
    val env = outerEnv ++ globals.toMap ++ loopVars
    enums match
      case Nil => eval(body, env).map(_ => Value.UnitV)
      case Enumerator.Generator(pat, rhs) :: rest =>
        eval(rhs, env).flatMap { rhsV =>
          val items = evalCollection(rhsV)
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil => Pure(Value.UnitV)
            case item :: tail =>
              matchPat(pat, item, env) match
                case Some(patEnv) =>
                  val newVars = patVarNames(pat).map(k => k -> patEnv(k)).toMap
                  evalForDo(rest, body, outerEnv, loopVars ++ newVars).flatMap(_ => loop(tail))
                case None => loop(tail)
          loop(items)
        }
      case Enumerator.Guard(cond) :: rest =>
        eval(cond, env).flatMap {
          case Value.BoolV(true) => evalForDo(rest, body, outerEnv, loopVars)
          case _                 => Pure(Value.UnitV)
        }
      case Enumerator.Val(pat, rhs) :: rest =>
        eval(rhs, env).flatMap { v =>
          matchPat(pat, v, env) match
            case Some(patEnv) =>
              val newVars = patVarNames(pat).map(k => k -> patEnv(k)).toMap
              evalForDo(rest, body, outerEnv, loopVars ++ newVars)
            case None => Pure(Value.UnitV)
        }
      case _ :: rest => evalForDo(rest, body, outerEnv, loopVars)

  private def autoOutput(v: Value): Unit = v match
    case Value.UnitV => ()
    case _           => out.println(Value.show(v))

  private def stripIndent(s: String): String =
    val lines = s.split('\n').toList
    val body  = lines.dropWhile(_.isBlank).reverse.dropWhile(_.isBlank).reverse
    if body.isEmpty then ""
    else
      val minIndent = body.filter(_.exists(_ != ' ')).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
      body.map(l => if l.isBlank then "" else l.drop(minIndent)).mkString("\n")

  def runSnippet(code: String): Unit =
    import scalascript.parser.Parser
    val src    = s"# Snippet\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    module.sections.foreach(runSection)

object Interpreter:
  def run(module: Module, out: java.io.PrintStream = System.out, baseDir: Option[os.Path] = None): Unit =
    Interpreter(out, baseDir).run(module)
