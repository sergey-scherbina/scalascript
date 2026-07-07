package scalascript.codegen

import scalascript.ast.*

/** Compiles `scala` and `scalascript` blocks to a Scala.js WASM bundle via
 *  `scala-cli --power package --js --js-module-kind es --js-emit-wasm`.
 *
 *  scala-cli writes a directory with three artefacts:
 *    main.wasm      — the WebAssembly binary
 *    main.js        — ES-module entry point (imports __loader.js + main.wasm)
 *    __loader.js    — WASM loader / runtime glue
 *
 *  All three are returned so the CLI / server can write them together.
 *
 *  `scalascript` blocks are treated as Scala 3 source and compiled as-is, with
 *  three ScalaScript-aware passes layered on the raw collection: restricted
 *  quoted macros are expanded (`MacroCodegen`), local `.ssc` imports are inlined
 *  (transitively, deduped), and `@wasm("expr")` externs are lowered to real
 *  `def`s. Blocks must otherwise contain valid Scala.js code; algebraic effects
 *  / handlers are NOT transpiled (they need the CPS codegen — out of scope for
 *  this backend), and `std` externs without a `@wasm` impl are dropped. An
 *  `@main def main(): Unit` entry point is required for executable bundles.
 */
object WasmGen:

  case class WasmBundle(
    wasmBytes:  Array[Byte],
    mainJs:     String,
    loaderJs:   String,
  )

  private def isCompilable(lang: String): Boolean =
    Lang.isStandardScala(lang) || Lang.isScalaScript(lang)

  def hasBlocks(module: Module): Boolean =
    module.sections.exists(sectionHas)

  private def sectionHas(s: Section): Boolean =
    s.content.exists {
      case cb: Content.CodeBlock => isCompilable(cb.lang)
      case _                     => false
    } || s.subsections.exists(sectionHas)

  /** Collect all compilable block sources, hoisting `//> using` directives
   *  to the top of the output so scala-cli sees them before any code.
   *  This lets users embed `//> using dep "org.scala-js::scalajs-dom::2.8.0"`
   *  (or similar) inside their `scalascript` / `scala` blocks.
   */
  def collectSource(module: Module, baseDir: Option[os.Path] = None): String =
    val directives = List.newBuilder[String]  // //> using … lines, deduped
    val body       = StringBuilder()
    val seen       = scala.collection.mutable.LinkedHashSet.empty[String]
    val seenFiles  = scala.collection.mutable.HashSet.empty[String]   // inlined imports (dedup)
    val deps       = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    val ctx        = CollectCtx(directives, seen, body, baseDir, deps, seenFiles)
    module.sections.foreach(collectSection(_, ctx))
    val dirs = directives.result()
    if dirs.isEmpty then body.toString
    else dirs.mkString("", "\n", "\n\n") + body.toString

  private case class CollectCtx(
    directives: scala.collection.mutable.Builder[String, List[String]],
    seen:       scala.collection.mutable.Set[String],
    body:       StringBuilder,
    baseDir:    Option[os.Path],
    moduleDeps: Map[String, String],
    seenFiles:  scala.collection.mutable.Set[String],
  )

  private def collectSection(s: Section, ctx: CollectCtx): Unit =
    s.content.foreach {
      case Content.CodeBlock(lang, src, tree, _, _, _, _) if isCompilable(lang) =>
        val (dirs, rest) = src.linesIterator.partition(_.startsWith("//>"))
        dirs.foreach { d =>
          val trimmed = d.trim
          if ctx.seen.add(trimmed) then ctx.directives += trimmed
        }
        // FFI: a block carrying `extern def` declarations can't be passed to
        // scala-cli verbatim (`extern`/`__extern__` aren't Scala). When the
        // block has externs, re-emit it from the parsed tree, lowering each
        // `@wasm("expr")` extern to a real `def` and dropping unimplemented
        // ones. Extern-free blocks keep the raw passthrough (byte-identical).
        val bodyStr = tree match
          case Some(node) if blockHasExtern(node) => transformExternBlock(node)
          case _                                  => rest.mkString("\n").stripTrailing()
        if bodyStr.nonEmpty then ctx.body.append(bodyStr).append("\n\n")
      case imp: Content.Import => inlineImport(imp, ctx)
      case _                   => ()
    }
    s.subsections.foreach(collectSection(_, ctx))

  /** Inline a local `.ssc` import: resolve (no scheme/download), parse,
   *  macro-expand (strips the imported module's own quoted macros), and recurse
   *  — so cross-module wasm code compiles. Deduped + cycle-safe via `seenFiles`;
   *  scheme imports (`pkg:`/`dep:`/`github:`) and non-`.ssc` are skipped. */
  private def inlineImport(imp: Content.Import, ctx: CollectCtx): Unit =
    if imp.path.contains(":") then return   // scheme import (compiled artifact) — not source-inlinable
    ctx.baseDir.foreach { base =>
      scala.util.Try {
        val resolved = scalascript.imports.ImportResolver.resolve(imp.path, base, ctx.moduleDeps, None)
        val key      = resolved.toString
        if os.exists(resolved) && resolved.ext == "ssc" && ctx.seenFiles.add(key) then
          val child     = scalascript.artifact.MacroCodegen.expand(scalascript.parser.Parser.parse(os.read(resolved)))
          val childDeps = child.manifest.map(_.dependencies).getOrElse(Map.empty)
          val childCtx  = ctx.copy(baseDir = Some(resolved / os.up), moduleDeps = childDeps)
          child.sections.foreach(collectSection(_, childCtx))
      }
    }

  // ── FFI: @wasm extern lowering (arch-ffi) ─────────────────────────────

  import scala.meta.*

  private def topStats(node: ScalaNode): List[scala.meta.Stat] =
    ScalaNode.fold(node) {
      case s: Source           => s.stats
      case b: Term.Block       => b.stats
      case other: scala.meta.Stat => List(other)
      case _                   => Nil
    }

  /** `extern def f(...)` is preprocessed to `def f(...) = __extern__`. */
  private def isExternStat(st: scala.meta.Stat): Boolean = st match
    case d: Defn.Def => d.body match { case Term.Name("__extern__") => true; case _ => false }
    case _           => false

  private def blockHasExtern(node: ScalaNode): Boolean =
    scala.util.Try(topStats(node).exists(isExternStat)).getOrElse(false)

  private val ffiAnnotNames = Set("wasm", "jvm", "js", "rust", "interpreterUnsupported")

  private def annotName(m: Mod): Option[String] = m match
    case Mod.Annot(init) => init.tpe match
      case Type.Name(n)                 => Some(n)
      case Type.Select(_, Type.Name(n)) => Some(n)
      case _                            => None
    case _ => None

  private def wasmArg(mods: List[Mod]): Option[String] =
    mods.collectFirst {
      case m @ Mod.Annot(init) if annotName(m).contains("wasm") =>
        init.argClauses.headOption.flatMap(_.values.collectFirst { case Lit.String(s) => s })
    }.flatten

  private def stripFfiAnnots(mods: List[Mod]): List[Mod] =
    mods.filterNot(m => annotName(m).exists(ffiAnnotNames.contains))

  private def substituteWasmArgs(expr: String, params: List[String]): String =
    params.zipWithIndex.foldLeft(expr) { case (e, (n, i)) => e.replace(s"$$$i", n) }

  /** Re-emit a block's statements, lowering `@wasm("expr")` externs to real
   *  `def`s (with `$0`/`$1` → param substitution, FFI annotations stripped) and
   *  dropping externs that have no `@wasm` implementation. */
  private def transformExternBlock(node: ScalaNode): String =
    given Dialect = dialects.Scala3
    topStats(node).flatMap {
      case d: Defn.Def if isExternStat(d) =>
        wasmArg(d.mods).flatMap { rawExpr =>
          val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
          substituteWasmArgs(rawExpr, params).parse[Term].toOption
            .map(bodyTerm => d.copy(mods = stripFfiAnnots(d.mods), body = bodyTerm).syntax)
        }                                   // None (no @wasm or unparseable) → drop
      case other => Some(other.syntax)
    }.mkString("\n\n")

  /** Compile all `scala` and `scalascript` blocks to a WASM bundle.
   *  Throws `RuntimeException` on compilation failure.
   */
  def compileToWasm(module: Module, baseDir: Option[os.Path] = None): WasmBundle =
    // Expand restricted quoted macros (incl. cross-module via local .ssc imports)
    // before collecting source; collectSource then inlines imports + lowers
    // @wasm externs. No-op for macro-free modules.
    val expanded = scalascript.artifact.MacroCodegen.expand(module, baseDir)
    val src      = collectSource(expanded, baseDir)
    if src.isBlank then return WasmBundle(Array.emptyByteArray, "", "")
    if usesEffects(src) then compileEffectfulToWasm(module, baseDir)
    else compileSourceToWasm(src, baseDir)

  /** Detect ScalaScript algebraic-effect usage. Keys on the unambiguous
   *  `effect <Capitalised>:` declaration (after import inlining the declaring
   *  module's source is present), which is not valid plain Scala — so it never
   *  false-positives onto an extern-free / effect-free block. */
  private def usesEffects(src: String): Boolean =
    // Mirror the parser's effect-decl recognition (`effectLinePat`), incl. the
    // `multi effect` (multi-shot) form — otherwise a multi-shot module skips the
    // CPS-lowering path and its `multi`/`!` surface syntax reaches scala-cli raw.
    """(?m)^\s*(?:multi\s+)?effect\s+[A-Z]\w*""".r.findFirstIn(src).isDefined

  /** WASM effect path: reuse `JvmGen.generateUserOnly`'s CPS lowering of the
   *  effect ops (perform/handle threaded through `_bind`), drop the JVM preamble
   *  (its `Thread`/`java.nio` parts crash the Scala.js linker), and supply a
   *  minimal pure-Scala effect runtime (`WasmEffectRuntime`) in `_ssc_runtime`.
   *  `generateUserOnly` strips the user's `@main` (returning a plain `def`), so
   *  re-add a wasm entry that calls it and preserves the supported main-args
   *  shape. The WASM path asks `JvmGen` not to preserve declared return
   *  types on effectful user defs: direct `_bind`/`_perform` results must
   *  remain `Any` until `_handle` interprets them, otherwise a helper such as
   *  `def shout(): Unit = Log.write(...)` casts the computation to `Unit`
   *  before the handler sees it. */
  private def compileEffectfulToWasm(module: Module, baseDir: Option[os.Path]): WasmBundle =
    val lowered = scalascript.codegen.JvmGen.generateUserOnly(
      module,
      baseDir,
      preserveTotalEffectfulReturnTypes = false
    )
    val entry   = mainEntry(module).getOrElse(throw RuntimeException(
      "An effectful WASM module needs an `@main def` entry point (effects run from it)."))
    val full =
      WasmEffectRuntime.source + "\n" + lowered +
      "\n" + entry.wrapperSource + "\n"
    compileSourceToWasm(full, baseDir)

  private case class MainEntry(name: String, paramSig: String, callSig: String):
    def wrapperSource: String =
      s"@main def _ssc_wasm_main$paramSig: Unit = { $name$callSig; () }"

  /** User `@main def` lowered by `generateUserOnly` to a plain `def` of the
   *  same name. Only the Scala.js-compatible shapes documented in
   *  `specs/wasm-main-edge.md` are wrapped here. */
  private def mainEntry(module: Module): Option[MainEntry] =
    def entries(s: Section): List[MainEntry] =
      val here = s.content.collect {
        case Content.CodeBlock(lang, _, Some(node), _, _, _, _) if isCompilable(lang) =>
          ScalaNode.fold(node) { tree =>
            tree.collect {
              case d: Defn.Def if d.mods.exists(m => annotName(m).contains("main")) =>
                mainEntryForDef(d)
            }
          }
      }.flatten
      here ++ s.subsections.flatMap(entries)
    module.sections.flatMap(entries).headOption

  private def mainEntryForDef(d: Defn.Def): MainEntry =
    val clauses = d.paramClauseGroups.flatMap(_.paramClauses).toList
    if clauses.length > 1 then
      throw RuntimeException(
        s"Effectful WASM @main def ${d.name.syntax} must use a single parameter clause")
    val paramSig = if d.paramClauseGroups.isEmpty then "()" else d.paramClauseGroups.map(_.syntax).mkString
    val callSig = clauses match
      case Nil => "()"
      case clause :: Nil =>
        clause.values.foreach { p =>
          if p.decltpe.exists(isStringArrayType) then
            throw RuntimeException(
              s"Effectful WASM @main def ${d.name.syntax} uses Array[String], but Scala 3 @main expects typed parameters; use args: String* for raw CLI args")
        }
        clause.values.map(mainCallArg).mkString("(", ", ", ")")
      case _ =>
        throw RuntimeException(
          s"Effectful WASM @main def ${d.name.syntax} must use a single parameter clause")
    MainEntry(d.name.syntax, paramSig, callSig)

  private def mainCallArg(p: Term.Param): String =
    val name = p.name.syntax
    if p.decltpe.exists(isRepeatedType) then s"$name*" else name

  private def isRepeatedType(t: Type): Boolean =
    t.syntax.filterNot(_.isWhitespace).endsWith("*")

  private def isStringArrayType(t: Type): Boolean =
    val compact = t.syntax.filterNot(_.isWhitespace)
    compact == "Array[String]" ||
      compact == "scala.Array[String]" ||
      compact == "_root_.scala.Array[String]"

  def compileSourceToWasm(source: String, baseDir: Option[os.Path] = None): WasmBundle =
    if source.isBlank then return WasmBundle(Array.emptyByteArray, "", "")

    // scala-cli requires .scala (not .sc) for @main with WASM output
    val tmp    = os.temp(source, suffix = ".scala", deleteOnExit = true)
    val outDir = os.temp.dir(deleteOnExit = true)
    val name   = "module"
    try
      val result = os.proc(
        "scala-cli", "--power", "package",
        "--js",
        "--js-module-kind", "es",
        "--js-emit-wasm",
        "--force",
        "-o", (outDir / name).toString,
        tmp
      ).call(
        cwd    = baseDir.getOrElse(os.pwd),
        check  = false,
        stderr = os.Pipe
      )
      if result.exitCode != 0 then
        throw RuntimeException(
          s"Scala.js WASM compilation failed (exit ${result.exitCode}):\n${result.err.text()}"
        )

      val bundleDir = outDir / name
      val wasmPath  = bundleDir / "main.wasm"
      val jsPath    = bundleDir / "main.js"
      val loaderPath = bundleDir / "__loader.js"

      if !os.exists(wasmPath) then
        throw RuntimeException(
          s"WASM output not found at $wasmPath — files: ${os.list(bundleDir).map(_.last).mkString(", ")}"
        )

      WasmBundle(
        wasmBytes = os.read.bytes(wasmPath),
        mainJs    = if os.exists(jsPath)    then os.read(jsPath)    else "",
        loaderJs  = if os.exists(loaderPath) then os.read(loaderPath) else "",
      )
    finally
      if os.exists(tmp) then os.remove(tmp)
