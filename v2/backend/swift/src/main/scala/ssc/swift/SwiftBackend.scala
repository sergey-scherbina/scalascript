package ssc.swift

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import ssc.{Arm, Const, Def, NativeUiSites, Program, Term}

enum SwiftPlatform:
  case MacOS, IOS

final case class SwiftPackage(files: Vector[(String, String)], executable: String):
  def writeTo(root: Path): Unit =
    files.foreach { (relative, content) =>
      val path = root.resolve(relative)
      Files.createDirectories(path.getParent)
      Files.writeString(path, content, StandardCharsets.UTF_8)
    }

object SwiftBackend:
  private val coreBuiltinGlobals = Set("print", "println", "Decimal", "BigInt", "RoundingMode", "RuntimeException", "handle", "effect", "__throw__")
  private val nativeUiPublicGlobals = Set(
    "signal", "seedSignal", "computedSignal", "eqSignal", "hashSignal", "emptyHeaders",
    "fetchUrlSignal", "fetchUrlSignalTo",
    "textNode", "signalText", "showSignal", "fragment", "element", "forKeyedView",
    "setSignal", "inputChange", "toggleSignal", "incSignal",
    "onBumpTick", "onSetSignal", "onNavigate", "onOpenJson", "formBody",
    "fetchAction", "fetchActionTo", "fetchActionClear", "fetchCaptureAction", "fetchActionWith",
    "staticRowsSource", "signalRowsSource", "fetchRowsSource",
    "fieldPayload", "wholeRowPayload", "fieldsPayload",
    "fieldColumn", "dateColumn", "moneyColumn", "statusColumn", "linkColumn", "stackedColumn",
    "rowDeleteAction", "rowPostAction", "rowLinkAction", "rowEditAction", "dataTableView",
    "localStorageGet", "localStorageSet", "localStorageRemove", "onlineSignal", "persistedSignal",
    "componentScope", "emit", "serve",
  )
  private val nativeUiInternalGlobals = NativeUiSites.annotatedSymbols.map(NativeUiSites.internalName)
  private val nativeUiGlobals = nativeUiPublicGlobals ++ nativeUiInternalGlobals
  private val builtinGlobals = coreBuiltinGlobals ++ nativeUiGlobals

  // This first executable slice intentionally freezes the structural runtime
  // vocabulary. Portable Decimal/effect primitives are added to this same
  // table/runtime in the next green sub-slice; generation fails honestly until
  // then instead of emitting a latent no-op.
  private val supportedPrimitives = Set(
    "i.add", "i.sub", "i.mul", "i.div", "i.mod", "i.neg",
    "i.and", "i.or", "i.xor", "i.not", "i.shl", "i.shr", "i.ushr",
    "i.eq", "i.lt", "i.le", "i.gt", "i.ge", "not",
    "big.add", "big.sub", "big.mul", "big.div", "big.mod", "big.neg",
    "big.eq", "big.lt", "big.le", "big.gt", "big.ge", "i->big", "big->str",
    "dec.parse", "dec.from-unscaled", "dec.add", "dec.sub", "dec.mul",
    "dec.div", "dec.rem", "dec.compare", "dec.set-scale", "dec.pow",
    "dec.abs", "dec.negate", "dec.signum", "dec.scale", "dec.unscaled",
    "dec.to-bigint", "dec.to-string",
    "effect.pure", "effect.perform", "effect.handle",
    "f.add", "f.sub", "f.mul", "f.div", "f.neg",
    "f.sqrt", "f.floor", "f.ceil", "f.round", "f.trunc",
    "f.eq", "f.lt", "f.le", "f.gt", "f.ge", "f.isNaN", "f.isInf",
    "i->f", "f->i", "i->str", "f->str",
    "slen", "sconcat", "sslice", "scodeAt", "sfromCodes",
    "seq", "scmp", "sindexOf", "str.trim", "str.replace", "str.lines",
    "blen", "bget", "bslice", "bconcat", "str->utf8", "utf8->str",
    "tagOf", "arity", "fieldAt", "__isTag__", "__autoPrint__",
    "cell.new", "cell.get", "cell.set", "lcell.new", "lcell.get", "lcell.set",
    "map.new", "map.get", "map.put", "map.has", "map.del", "map.keys", "map.size",
    "arr.new", "arr.len", "arr.get", "arr.set", "arr.push", "arr.pop", "arr.slice",
    "__mk_arr__", "__mk_map__", "__math_obj__", "__match_fail_prim__",
    "__method__", "__effect__", "__arith__", "__unary__",
    "io.print", "io.println", "io.nanoTime", "io.args", "global.reg",
  )

  def generate(
      program: Program,
      packageName: String = "SscApp",
      platform: SwiftPlatform = SwiftPlatform.MacOS,
  ): SwiftPackage =
    validate(program)
    val product = productName(packageName)
    val nativeUi = usesNativeUi(program)
    val executable = if nativeUi then s"${product}Cli" else product
    val coreFiles = Vector(
      "Package.swift" -> packageManifest(product, executable, platform),
      "Sources/AppCore/SscRuntime.swift" -> SwiftRuntime.source,
    ) ++ (if nativeUi then Vector("Sources/AppCore/NativeUiHost.swift" -> SwiftNativeUiHost.source) else Vector.empty) ++ Vector(
      "Sources/AppCore/GeneratedProgram.swift" -> generatedProgram(program, nativeUi),
      s"Sources/$executable/main.swift" ->
        "import AppCore\n\nSscGeneratedProgram.run()\n",
    ) ++ (if nativeUi then Vector(
      s"AppleApp/${product}App.swift" -> SwiftNativeUiApple.appSource(product),
      "AppleApp/NativeUiStore.swift" -> SwiftNativeUiApple.storeSource,
      "AppleApp/NativeUiRenderer.swift" -> SwiftNativeUiApple.rendererSource,
      "AppleApp/NativeUiStyles.swift" -> SwiftNativeUiApple.stylesSource,
      "AppleApp/NativeUiHtml.swift" -> SwiftNativeUiApple.htmlSource,
    ) else Vector.empty)
    SwiftPackage(coreFiles, executable)

  def productName(raw: String): String =
    val cleaned = raw.replaceAll("[^A-Za-z0-9_]", "_")
    val nonEmpty = if cleaned.isEmpty then "SscApp" else cleaned
    if nonEmpty.head.isDigit then s"Ssc_$nonEmpty" else nonEmpty

  private def packageManifest(product: String, executable: String, platform: SwiftPlatform): String =
    val platformDeclaration = platform match
      case SwiftPlatform.MacOS => ".macOS(.v13)"
      case SwiftPlatform.IOS => ".iOS(.v16)"
    s"""// swift-tools-version: 6.0
       |import PackageDescription
       |
       |let package = Package(
       |    name: ${swiftString(product)},
       |    platforms: [$platformDeclaration],
       |    products: [
       |        .library(name: "AppCore", targets: ["AppCore"]),
       |        .executable(name: ${swiftString(executable)}, targets: [${swiftString(executable)}])
       |    ],
       |    targets: [
       |        .target(name: "AppCore"),
       |        .executableTarget(name: ${swiftString(executable)}, dependencies: ["AppCore"])
       |    ]
       |)
       |""".stripMargin

  private def generatedProgram(program: Program, nativeUi: Boolean): String =
    if nativeUi then
      s"""// Generated by ScalaScript v2 SwiftBackend. DO NOT EDIT.
         |import Foundation
         |
         |public enum SscGeneratedProgram {
         |    static func makeNativeUiRoot(
         |        persistedRead: @escaping (String) -> String? = { _ in nil },
         |        persistedWrite: @escaping (String, String) -> Void = { _, _ in }
         |    ) throws -> NativeUiSession {
         |        let program = ${emitProgram(program)}
         |        let host = NativeUiHost(persistedRead: persistedRead, persistedWrite: persistedWrite)
         |        return try host.evaluate(program)
         |    }
         |
         |    public static func run() {
         |        do {
         |            let session = try makeNativeUiRoot()
         |            Swift.print(nativeUiDebug(session.root))
         |        } catch {
         |            fatalError(String(describing: error))
         |        }
         |    }
         |}
         |""".stripMargin
    else
      s"""// Generated by ScalaScript v2 SwiftBackend. DO NOT EDIT.
         |import Foundation
         |
         |public enum SscGeneratedProgram {
         |    public static func run() {
         |        let program = ${emitProgram(program)}
         |        SscRuntime.execute(program)
         |    }
         |}
         |""".stripMargin

  private def usesNativeUi(program: Program): Boolean =
    val userDefinitions = program.defs.iterator.map(_.name).toSet
    def loop(term: Term): Boolean = term match
      case Term.Global(name) => nativeUiInternalGlobals(name) || (nativeUiPublicGlobals(name) && !userDefinitions(name))
      case Term.Lam(_, body) => loop(body)
      case Term.App(fn, args) => loop(fn) || args.exists(loop)
      case Term.Let(rhs, body) => rhs.exists(loop) || loop(body)
      case Term.LetRec(lams, body) => lams.exists(loop) || loop(body)
      case Term.If(c, t, e) => loop(c) || loop(t) || loop(e)
      case Term.Ctor(_, fields) => fields.exists(loop)
      case Term.Match(scrut, arms, default) => loop(scrut) || arms.exists(a => loop(a.body)) || default.exists(loop)
      case Term.Prim(_, args) => args.exists(loop)
      case Term.While(c, body) => loop(c) || loop(body)
      case Term.Seq(terms) => terms.exists(loop)
      case Term.Lit(_) | Term.Local(_) => false
    program.defs.exists(d => loop(d.body)) || loop(program.entry)

  private def emitProgram(program: Program): String =
    val defs = program.defs.map(emitDef).mkString("[", ", ", "]")
    val layouts = constructorShapes(program).toVector.sorted.flatMap { (tag, arity) =>
      ssc.V2PluginRegistry.lookupFieldNames(tag, arity).map { names =>
        val values = names.map(swiftString).mkString("[", ", ", "]")
        swiftString(s"$tag#$arity") -> values
      }
    }
    val layoutSource =
      if layouts.isEmpty then "[:]"
      else layouts.map((key, values) => s"$key: $values").mkString("[", ", ", "]")
    s"SscProgram(definitions: $defs, entry: ${emitTerm(program.entry)}, fieldLayouts: $layoutSource)"

  private def constructorShapes(program: Program): Set[(String, Int)] =
    def loop(term: Term): Set[(String, Int)] = term match
      case Term.Ctor(tag, fields) => fields.flatMap(loop).toSet + (tag -> fields.length)
      case Term.Lam(_, body) => loop(body)
      case Term.App(fn, args) => loop(fn) ++ args.flatMap(loop)
      case Term.Let(rhs, body) => rhs.flatMap(loop).toSet ++ loop(body)
      case Term.LetRec(lams, body) => lams.flatMap(loop).toSet ++ loop(body)
      case Term.If(c, t, e) => loop(c) ++ loop(t) ++ loop(e)
      case Term.Match(scrut, arms, default) =>
        loop(scrut) ++ arms.flatMap(a => loop(a.body)) ++ default.toSet.flatMap(loop)
      case Term.Prim(_, args) => args.flatMap(loop).toSet
      case Term.While(c, body) => loop(c) ++ loop(body)
      case Term.Seq(terms) => terms.flatMap(loop).toSet
      case Term.Lit(_) | Term.Local(_) | Term.Global(_) => Set.empty
    program.defs.flatMap(d => loop(d.body)).toSet ++ loop(program.entry)

  private def emitDef(d: Def): String =
    s"SscDefinition(name: ${swiftString(d.name)}, body: ${emitTerm(d.body)})"

  private def emitArm(a: Arm): String =
    s"SscArm(tag: ${swiftString(a.tag)}, arity: ${a.arity}, body: ${emitTerm(a.body)})"

  private def emitTerm(term: Term): String = term match
    case Term.Lit(c) => s".literal(${emitConst(c)})"
    case Term.Local(i) => s".local($i)"
    case Term.Global(name) => s".global(${swiftString(name)})"
    case Term.Lam(arity, body) => s".lambda($arity, ${emitTerm(body)})"
    case Term.App(fn, args) => s".apply(${emitTerm(fn)}, ${emitTerms(args)})"
    case Term.Let(rhs, body) => s".letBindings(${emitTerms(rhs)}, ${emitTerm(body)})"
    case Term.LetRec(lams, body) => s".letRecursive(${emitTerms(lams)}, ${emitTerm(body)})"
    case Term.If(cond, ifTrue, ifFalse) =>
      s".ifThenElse(${emitTerm(cond)}, ${emitTerm(ifTrue)}, ${emitTerm(ifFalse)})"
    case Term.Ctor(tag, fields) => s".constructor(${swiftString(tag)}, ${emitTerms(fields)})"
    case Term.Match(scrutinee, arms, default) =>
      val as = arms.map(emitArm).mkString("[", ", ", "]")
      val d = default.map(emitTerm).map(t => s"Optional.some($t)").getOrElse("Optional.none")
      s".matchValue(${emitTerm(scrutinee)}, $as, $d)"
    case Term.Prim(op, args) => s".primitive(${swiftString(op)}, ${emitTerms(args)})"
    case Term.While(cond, body) => s".whileLoop(${emitTerm(cond)}, ${emitTerm(body)})"
    case Term.Seq(terms) => s".sequence(${emitTerms(terms)})"

  private def emitTerms(terms: List[Term]): String =
    terms.map(emitTerm).mkString("[", ", ", "]")

  private def emitConst(c: Const): String = c match
    case Const.CUnit => ".unit"
    case Const.CBool(value) => s".bool($value)"
    case Const.CInt(value) => s".int($value)"
    case Const.CBig(value) => s".big(${swiftString(value.toString)})"
    case Const.CFloat(value) if value.isNaN => ".float(Double.nan)"
    case Const.CFloat(value) if value == Double.PositiveInfinity => ".float(Double.infinity)"
    case Const.CFloat(value) if value == Double.NegativeInfinity => ".float(-Double.infinity)"
    case Const.CFloat(value) => s".float(${java.lang.Double.toString(value)})"
    case Const.CStr(value) => s".string(${swiftString(value)})"
    case Const.CBytes(value) => s".bytes(${value.map(_ & 0xff).mkString("[", ", ", "]")})"

  private def swiftString(value: String): String =
    val out = new StringBuilder("\"")
    value.foreach {
      case '"' => out ++= "\\\""
      case '\\' => out ++= "\\\\"
      case '\n' => out ++= "\\n"
      case '\r' => out ++= "\\r"
      case '\t' => out ++= "\\t"
      case c if c.isControl => out ++= f"\\u{${c.toInt}%x}"
      case c => out += c
    }
    out += '"'
    out.toString

  private def validate(program: Program): Unit =
    val definitions = program.defs.map(_.name).toSet
    program.defs.foreach(d => validateTerm(d.body, definitions))
    validateTerm(program.entry, definitions)

  private def validateTerm(term: Term, definitions: Set[String]): Unit = term match
    case Term.Lit(_) | Term.Local(_) => ()
    case Term.Global(name) if definitions(name) || builtinGlobals(name) => ()
    case Term.Global(name) => fail(s"unsupported global '$name'")
    case Term.Lam(_, body) => validateTerm(body, definitions)
    case Term.App(fn, args) => validateTerm(fn, definitions); args.foreach(validateTerm(_, definitions))
    case Term.Let(rhs, body) => rhs.foreach(validateTerm(_, definitions)); validateTerm(body, definitions)
    case Term.LetRec(lams, body) =>
      lams.foreach {
        case lam @ Term.Lam(_, _) => validateTerm(lam, definitions)
        case _ => fail("letrec binding must be a lambda")
      }
      validateTerm(body, definitions)
    case Term.If(c, t, e) => List(c, t, e).foreach(validateTerm(_, definitions))
    case Term.Ctor(_, fields) => fields.foreach(validateTerm(_, definitions))
    case Term.Match(scrut, arms, default) =>
      validateTerm(scrut, definitions)
      arms.foreach(a => validateTerm(a.body, definitions))
      default.foreach(validateTerm(_, definitions))
    case Term.Prim(op, args) =>
      if !supportedPrimitives(op) then fail(s"unsupported primitive '$op'")
      args.foreach(validateTerm(_, definitions))
    case Term.While(c, body) => validateTerm(c, definitions); validateTerm(body, definitions)
    case Term.Seq(terms) => terms.foreach(validateTerm(_, definitions))

  private def fail(message: String): Nothing =
    throw new IllegalArgumentException(s"swift backend: $message")
