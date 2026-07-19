package ssc

/** Pure CoreIR annotation pass for the versioned NativeUi ABI.
  *
  * Public std/ui signatures stay source-compatible. Direct calls whose import
  * provenance identifies them as std/ui externs are rewritten to reserved
  * internal globals with deterministic lexical site/source metadata.
  */
object NativeUiSites:
  val ReservedPrefix = "__ssc_nativeui_v1."

  final case class SourceRef(file: String, line: Int = 0, column: Int = 0)
  final case class Config(
      eligibleSymbols: Set[String],
      sourcesByDefinition: Map[String, SourceRef] = Map.empty,
      entrySource: SourceRef = SourceRef("<entry>"))

  private val arities: Map[String, Set[Int]] = Map(
    "element" -> Set(4),
    "forKeyedView" -> Set(3),
    "dataTableView" -> Set(3, 4),
    "computedSignal" -> Set(1),
    "eqSignal" -> Set(2),
    "fetchUrlSignal" -> Set(3, 4),
    "fetchUrlSignalTo" -> Set(3, 4),
    "fetchAction" -> Set(4, 5),
    "fetchActionTo" -> Set(4, 5),
    "fetchActionClear" -> Set(4, 5),
    "fetchCaptureAction" -> Set(5, 6),
    "fetchActionWith" -> Set(4, 5),
    "emit" -> Set(2),
    "serve" -> Set(2, 3),
  )

  val annotatedSymbols: Set[String] = arities.keySet
  private val sourceOnlySymbols = Set("emit", "serve")

  def internalName(publicName: String): String = ReservedPrefix + publicName
  def hiddenArgumentCount(publicName: String): Int =
    if sourceOnlySymbols(publicName) then 1 else 2

  def annotate(program: Program, config: Config): Program =
    val reserved = program.defs.iterator.map(_.name).filter(_.startsWith(ReservedPrefix)).toList.sorted
    if reserved.nonEmpty then
      fail(s"reserved global prefix '$ReservedPrefix' is used by: ${reserved.mkString(", ")}")

    val shadowing = program.defs.iterator.map(_.name).toSet
    val eligible = config.eligibleSymbols.intersect(annotatedSymbols) -- shadowing

    def sourceTerm(ref: SourceRef, operation: String): Term =
      Term.Ctor("NativeUiSourceRef", List(
        Term.Lit(Const.CStr(ref.file)),
        Term.Lit(Const.CInt(ref.line.toLong)),
        Term.Lit(Const.CInt(ref.column.toLong)),
        Term.Lit(Const.CStr(operation))))

    def lexicalId(owner: String, ordinal: Int, path: Vector[String]): String =
      s"d$ordinal:$owner/${path.mkString("/")}"

    def rewrite(term: Term, owner: String, ordinal: Int, ref: SourceRef, path: Vector[String]): Term = term match
      case Term.App(Term.Global(name), args) if eligible(name) =>
        val allowed = arities(name)
        if !allowed(args.length) then
          fail(s"$name at ${lexicalId(owner, ordinal, path)} has arity ${args.length}; expected ${allowed.toList.sorted.mkString(" or ")}")
        val rewrittenArgs = args.zipWithIndex.map { case (arg, index) =>
          rewrite(arg, owner, ordinal, ref, path :+ s"a$index")
        }
        val hidden =
          if hiddenArgumentCount(name) == 1 then List(sourceTerm(ref, name))
          else List(Term.Lit(Const.CStr(lexicalId(owner, ordinal, path))), sourceTerm(ref, name))
        Term.App(Term.Global(internalName(name)), hidden ++ rewrittenArgs)
      case Term.Global(name) if eligible(name) =>
        fail(s"bare or eta-expanded std/ui primitive '$name' at ${lexicalId(owner, ordinal, path)}; call it directly")
      case Term.Lit(_) | Term.Local(_) => term
      case Term.Global(_) => term
      case Term.Lam(arity, body) => Term.Lam(arity, rewrite(body, owner, ordinal, ref, path :+ "body"))
      case Term.App(fn, args) =>
        Term.App(
          rewrite(fn, owner, ordinal, ref, path :+ "fn"),
          args.zipWithIndex.map { case (arg, index) => rewrite(arg, owner, ordinal, ref, path :+ s"a$index") })
      case Term.Let(rhs, body) =>
        Term.Let(
          rhs.zipWithIndex.map { case (value, index) => rewrite(value, owner, ordinal, ref, path :+ s"r$index") },
          rewrite(body, owner, ordinal, ref, path :+ "body"))
      case Term.LetRec(lambdas, body) =>
        Term.LetRec(
          lambdas.zipWithIndex.map { case (value, index) => rewrite(value, owner, ordinal, ref, path :+ s"rec$index") },
          rewrite(body, owner, ordinal, ref, path :+ "body"))
      case Term.If(condition, whenTrue, whenFalse) =>
        Term.If(
          rewrite(condition, owner, ordinal, ref, path :+ "cond"),
          rewrite(whenTrue, owner, ordinal, ref, path :+ "then"),
          rewrite(whenFalse, owner, ordinal, ref, path :+ "else"))
      case Term.Ctor(tag, fields) =>
        Term.Ctor(tag, fields.zipWithIndex.map { case (field, index) =>
          rewrite(field, owner, ordinal, ref, path :+ s"f$index")
        })
      case Term.Match(scrutinee, arms, default) =>
        Term.Match(
          rewrite(scrutinee, owner, ordinal, ref, path :+ "scrut"),
          arms.zipWithIndex.map { case (arm, index) =>
            arm.copy(body = rewrite(arm.body, owner, ordinal, ref, path :+ s"arm$index"))
          },
          default.map(rewrite(_, owner, ordinal, ref, path :+ "default")))
      case Term.Prim(operation, args) =>
        Term.Prim(operation, args.zipWithIndex.map { case (arg, index) =>
          rewrite(arg, owner, ordinal, ref, path :+ s"p$index")
        })
      case Term.While(condition, body) =>
        Term.While(
          rewrite(condition, owner, ordinal, ref, path :+ "cond"),
          rewrite(body, owner, ordinal, ref, path :+ "body"))
      case Term.Seq(terms) =>
        Term.Seq(terms.zipWithIndex.map { case (value, index) =>
          rewrite(value, owner, ordinal, ref, path :+ s"s$index")
        })

    val rewrittenDefs = program.defs.zipWithIndex.map { case (definition, ordinal) =>
      val ref = config.sourcesByDefinition.getOrElse(definition.name, config.entrySource)
      definition.copy(body = rewrite(definition.body, definition.name, ordinal, ref, Vector("root")))
    }
    val entryOrdinal = program.defs.length
    val rewrittenEntry = rewrite(program.entry, "entry", entryOrdinal, config.entrySource, Vector("root"))
    Program(rewrittenDefs, rewrittenEntry)

  private def fail(message: String): Nothing =
    throw new IllegalArgumentException(s"native UI lowering: $message")
