package ssc.swift

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.file.StandardCopyOption.{ATOMIC_MOVE, REPLACE_EXISTING}
import java.net.URI
import scala.jdk.CollectionConverters.*

import ssc.{Const, NativeUiSites, Program, Term, Writer}

enum SwiftPlatform:
  case MacOS, IOS

final case class SwiftPackage(
    files: Vector[(String, String)],
    debugCli: String,
    xcodeApp: Option[XcodeAppArtifact] = None,
):
  private val ownershipManifest = ".ssc-swift-generated.json"

  def writeTo(root: Path): Unit =
    Files.createDirectories(root)
    val previous = readOwned(root.resolve(ownershipManifest))
    previous.foreach { relative =>
      val path = ownedPath(root, relative)
      Files.deleteIfExists(path)
      pruneEmpty(path.getParent, root)
    }
    files.sortBy(_._1).foreach { (relative, content) =>
      val path = ownedPath(root, relative)
      Files.createDirectories(path.getParent)
      Files.writeString(path, content, StandardCharsets.UTF_8)
    }
    val rendered = files.map(_._1).sorted.map(jsonString).mkString("[\n  ", ",\n  ", "\n]\n")
    val manifest = root.resolve(ownershipManifest)
    val temporary = root.resolve(s"$ownershipManifest.tmp")
    Files.writeString(temporary, rendered, StandardCharsets.UTF_8)
    Files.move(temporary, manifest, ATOMIC_MOVE, REPLACE_EXISTING)

  private def ownedPath(root: Path, relative: String): Path =
    val candidate = Path.of(relative)
    if candidate.isAbsolute || candidate.iterator().asScala.exists(_.toString == "..") then
      throw new IllegalArgumentException(s"invalid generated Swift ownership path '$relative'")
    val resolved = root.resolve(candidate).normalize()
    if !resolved.startsWith(root.normalize()) then
      throw new IllegalArgumentException(s"invalid generated Swift ownership path '$relative'")
    resolved

  private def readOwned(path: Path): Vector[String] =
    if !Files.isRegularFile(path) then Vector.empty
    else
      val text = Files.readString(path, StandardCharsets.UTF_8).trim
      if !text.startsWith("[") || !text.endsWith("]") then
        throw new IllegalArgumentException("invalid generated Swift ownership manifest")
      val stringPattern = """"((?:\\.|[^"\\])*)"""".r
      val values = stringPattern.findAllMatchIn(text).map(m => unescapeJson(m.group(1))).toVector
      val remainder = stringPattern.replaceAllIn(text, "\"\"")
      if !remainder.matches("(?s)\\[\\s*(?:\"\"\\s*(?:,\\s*\"\"\\s*)*)?\\]") then
        throw new IllegalArgumentException("invalid generated Swift ownership manifest")
      values.foreach(ownedPath(path.getParent, _))
      values

  private def unescapeJson(value: String): String =
    value.replace("\\\"", "\"").replace("\\\\", "\\")

  private def jsonString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def pruneEmpty(start: Path, root: Path): Unit =
    var current = start
    while current != null && current != root && current.startsWith(root) do
      if Files.isDirectory(current) then
        val stream = Files.list(current)
        val empty = try !stream.findFirst().isPresent finally stream.close()
        if empty then Files.delete(current) else return
      current = current.getParent

object SwiftBackend:
  private val coreBuiltinGlobals = Set(
    "print", "println", "Decimal", "BigInt", "RoundingMode", "RuntimeException", "handle", "effect", "__throw__",
    "__jsonCoreInstallRenderer", "__jsonCoreWrap", "__jsonCoreWrapStrict", "__jsonCoreRawStrict",
    "__jsonCoreEncodeValue", "lookup", "lookupOpt",
    "contentDocument", "contentCurrentSection", "contentSection", "contentBlock", "contentData",
    "contentMetadata", "contentPlainText", "contentToMarkdown", "contentModules", "contentModule",
    "contentModuleSection", "contentModuleBlock", "contentModuleData", "contentModuleMetadata",
    "contentToolkitNode", "contentToolkitBlock", "contentToolkitSection",
    "installLocalAssets", "publishLocalLocale", "webauthnRegister", "webauthnAssert",
  )
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
    "effect.pure", "effect.perform", "effect.perform.oneshot", "effect.handle",
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
    "__method__", "__effect__", "__effect_oneshot__", "__arith__", "__unary__", "__try__",
    "io.print", "io.println", "io.nanoTime", "io.args", "global.reg",
    // native-front lowering emits these where FrontendBridge lowered differently
    "str->i", "str.split", "__eq__", "__throw__", "__tryCatch__", "__regfields__",
  )

  def generate(
      program: Program,
      packageName: String = "SscApp",
      platform: SwiftPlatform = SwiftPlatform.MacOS,
      backendBaseUrl: Option[String] = None,
      appMetadata: Option[SwiftAppMetadata] = None,
      forceNativeUi: Boolean = false,
      appleResourcePaths: Vector[String] = Vector.empty,
      contentModulesBase64: Option[String] = None,
  ): SwiftPackage =
    validate(program)
    val product = productName(packageName)
    val nativeUi = forceNativeUi || usesNativeUi(program)
    val normalizedBaseUrl = if nativeUi then backendBaseUrl.map(normalizeBackendBaseUrl) else None
    val executable = if nativeUi then s"${product}Cli" else product
    val baseFiles = Vector(
      "Package.swift" -> packageManifest(product, executable, platform),
      "Sources/AppCore/SscRuntime.swift" -> SwiftRuntime.source,
    ) ++ (if nativeUi then Vector("Sources/AppCore/NativeUiHost.swift" -> SwiftNativeUiHost.source) else Vector.empty) ++ Vector(
      "Sources/AppCore/GeneratedProgram.swift" -> generatedProgram(program, nativeUi, normalizedBaseUrl),
      s"Sources/$executable/main.swift" ->
        "import AppCore\n\nSscGeneratedProgram.run()\n",
    ) ++ (if nativeUi then Vector(
      s"AppleApp/${product}App.swift" -> SwiftNativeUiApple.appSource(product),
      "AppleApp/NativeUiStore.swift" -> SwiftNativeUiApple.storeSource,
      "AppleApp/NativeUiRenderer.swift" -> SwiftNativeUiApple.rendererSource,
      "AppleApp/NativeUiStyles.swift" -> SwiftNativeUiApple.stylesSource,
      "AppleApp/NativeUiHtml.swift" -> SwiftNativeUiApple.htmlSource,
    ) else Vector.empty) ++ Vector(
      "Sources/AppCore/ContentModules.swift" -> contentModulesSource(contentModulesBase64.getOrElse("")),
    )
    val (xcodeFiles, xcodeApp) =
      if nativeUi && appMetadata.nonEmpty then
        val metadata = appMetadata.get
        val (files, artifact) = SwiftXcodeProject.generate(product, metadata, appleResourcePaths)
        files -> Some(artifact)
      else Vector.empty -> None
    SwiftPackage(baseFiles ++ xcodeFiles, executable, xcodeApp)

  def requiresNativeUi(program: Program): Boolean = usesNativeUi(program)

  def validateAppMetadata(metadata: SwiftAppMetadata): Unit =
    SwiftXcodeProject.validate(metadata)

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

  def normalizeBackendBaseUrl(raw: String): String =
    val text = raw.trim
    val uri =
      try URI.create(text)
      catch case _: IllegalArgumentException =>
        throw new IllegalArgumentException("Swift --server-url must be an absolute http/https URL with a host")
    val scheme = Option(uri.getScheme).map(_.toLowerCase)
    if !scheme.exists(s => s == "http" || s == "https") ||
        Option(uri.getHost).forall(_.isEmpty) || uri.getUserInfo != null ||
        uri.getQuery != null || uri.getFragment != null then
      throw new IllegalArgumentException(
        "Swift --server-url must be absolute http/https with a host and no credentials, query, or fragment")
    if text.endsWith("/") then text else text + "/"

  /** Base64 is `[A-Za-z0-9+/=]` only — a plain Swift string literal, no escaping
   *  needed. The decoder (SscContentModules.swift-side, reading the exact byte
   *  layout NativeContentCodec.encode wrote) lives in SwiftRuntime.source, not
   *  here — this file only carries the per-app data. */
  private def contentModulesSource(base64: String): String =
    s"""// Generated by ssc-tools emit-swift — encoded content modules (std/ui/content.ssc).
       |// Decoded by sscContentModules() in SscRuntime.swift.
       |let sscContentModulesBase64: String = ${swiftString(base64)}
       |""".stripMargin

  private def generatedProgram(program: Program, nativeUi: Boolean, backendBaseUrl: Option[String]): String =
    val dataSource = programDataSource(program)
    if nativeUi then
      val base = backendBaseUrl.fold("nil")(value => s"Optional(${swiftString(value)})")
      s"""// Generated by ScalaScript v2 SwiftBackend. DO NOT EDIT.
         |import Foundation
         |
         |$dataSource
         |public enum SscGeneratedProgram {
         |    public static let nativeUiBackendBaseURL: String? = $base
         |
         |    static func makeNativeUiRoot(
         |        persistedRead: @escaping (String) -> String? = { _ in nil },
         |        persistedWrite: @escaping (String, String) -> Void = { _, _ in }
         |    ) throws -> NativeUiSession {
         |        let program = sscDecodeProgram(sscProgramSExpr, fieldLayouts: sscProgramFieldLayouts)
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
         |$dataSource
         |public enum SscGeneratedProgram {
         |    public static func run() {
         |        let program = sscDecodeProgram(sscProgramSExpr, fieldLayouts: sscProgramFieldLayouts)
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

  /** Encodes the whole Core IR Program as canonical S-expr TEXT (the same
   *  format `ssc.Writer.program`/`ssc.Reader.parseProgram` already use as the
   *  portable interchange format for the JS/Rust/JVM backends,
   *  specs/12-ir-format.md) rather than as a nested Swift literal expression.
   *  Swift 6's compiler enforces a hard "structure nesting level exceeded
   *  maximum of 256" limit per expression, which real-world-sized programs
   *  (e.g. busi's own 3305-line production app.ssc) exceed. The text is
   *  decoded at runtime by `sscDecodeProgram` (SscRuntime.swift) via ordinary
   *  recursive Swift function calls, which are bound only by the real call
   *  stack, not the compiler's expression-nesting limit — mirroring the same
   *  embed-as-string-and-decode-at-runtime shape already used for content
   *  modules (contentModulesSource / SscContentDecoder). fieldLayouts stays a
   *  flat (non-nested) Swift dictionary literal — it is plugin metadata, not
   *  part of Core IR, and was never a nesting-limit risk. */
  private def programDataSource(program: Program): String =
    val sExpr = Writer.program(program)
    val layouts = constructorShapes(program).toVector.sorted.flatMap { (tag, arity) =>
      ssc.V2PluginRegistry.lookupFieldNames(tag, arity).map { names =>
        val values = names.map(swiftString).mkString("[", ", ", "]")
        swiftString(s"$tag#$arity") -> values
      }
    }
    val layoutSource =
      if layouts.isEmpty then "[:]"
      else layouts.map((key, values) => s"$key: $values").mkString("[", ", ", "]")
    s"""let sscProgramSExpr: String = ${swiftString(sExpr)}
       |let sscProgramFieldLayouts: [String: [String]] = $layoutSource
       |""".stripMargin

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

  /** Literal globals installed by FrontendBridge's unconditional top-level
    * initialization continuation. Do not recursively search value expressions,
    * lambdas, definitions, or control-flow branches: those registrations are
    * not guaranteed to execute and therefore cannot authorize a Global use. */
  private def collectEntryRegisteredGlobals(term: Term): Set[String] = term match
    case Term.Let(_, body) => collectEntryRegisteredGlobals(body)
    case Term.LetRec(_, body) => collectEntryRegisteredGlobals(body)
    case Term.Seq(terms) => terms.iterator.flatMap(collectEntryRegisteredGlobals).toSet
    case Term.Prim("global.reg", List(Term.Lit(Const.CStr(name)), _)) => Set(name)
    case _ => Set.empty

  private def validate(program: Program): Unit =
    val defNames = program.defs.map(_.name).toSet
    val registeredNames = collectEntryRegisteredGlobals(program.entry)
    val definitions = defNames ++ registeredNames
    program.defs.foreach(d => validateTerm(d.body, definitions))
    validateTerm(program.entry, definitions)

  /** Diagnostic (not used by generate/validate): collects every unsupported global and
   *  primitive the whole program references, in one pass, instead of validate's
   *  fail-fast single-error behavior. Useful for scoping a real, large source's full
   *  gap list up front rather than discovering it one failure at a time. */
  def findAllGaps(program: Program): (Set[String], Set[String]) =
    val defNames = program.defs.map(_.name).toSet
    val registeredNames = collectEntryRegisteredGlobals(program.entry)
    val definitions = defNames ++ registeredNames
    var globals = Set.empty[String]
    var prims = Set.empty[String]
    def walk(term: Term): Unit = term match
      case Term.Lit(_) | Term.Local(_) => ()
      case Term.Global(name) => if !(definitions(name) || builtinGlobals(name) || name.startsWith("@")) then globals += name
      case Term.Lam(_, body) => walk(body)
      case Term.App(fn, args) => walk(fn); args.foreach(walk)
      case Term.Let(rhs, body) => rhs.foreach(walk); walk(body)
      case Term.LetRec(lams, body) => lams.foreach(walk); walk(body)
      case Term.If(c, t, e) => List(c, t, e).foreach(walk)
      case Term.Ctor(_, fields) => fields.foreach(walk)
      case Term.Match(scrut, arms, default) =>
        walk(scrut); arms.foreach(a => walk(a.body)); default.foreach(walk)
      case Term.Prim(op, args) =>
        if !supportedPrimitives(op) then prims += op
        args.foreach(walk)
      case Term.While(c, body) => walk(c); walk(body)
      case Term.Seq(terms) => terms.foreach(walk)
    program.defs.foreach(d => walk(d.body))
    walk(program.entry)
    (globals, prims)

  private def validateTerm(term: Term, definitions: Set[String]): Unit = term match
    case Term.Lit(_) | Term.Local(_) => ()
    case Term.Global(name) if definitions(name) || builtinGlobals(name) => ()
    // `@`-prefixed globals are lazily-vivified cells (a `val x = Signal(0)`
    // mutated via `x += 1` lowers to cell.set(Global("@x"), …) with no
    // global.reg). The runtime auto-creates them on first access, mirroring
    // v2/src/Runtime.scala:686-689, so they are not "unsupported".
    case Term.Global(name) if name.startsWith("@") => ()
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
