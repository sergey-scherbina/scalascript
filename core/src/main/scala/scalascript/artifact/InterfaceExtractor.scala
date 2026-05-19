package scalascript.artifact

import scalascript.ir.*
import scalascript.ast
import scalascript.typer.{Typer, DefSummary, SymbolKind as TSymbolKind}
import scala.meta.*
import scala.collection.mutable.ListBuffer

/** Extracts a `ModuleInterface` from a compiled `NormalizedModule` + its
 *  original AST `Module`.
 *
 *  Strategy:
 *  1. Run `Typer` over the AST to collect `DefSummary` entries (names +
 *     best-effort types) for every top-level definition.
 *  2. Walk the AST to detect `given` instances and `extern def` declarations.
 *  3. Detect capability usage from `serve()`, `fetch()`, `connect()` etc.
 *  4. Build the `ModuleInterface` with the ABI magic + version guard.
 *
 *  The extractor is intentionally conservative: if it can't determine a
 *  type it falls back to `"Any"`.  Richer type information will arrive when
 *  the typer is extended in a later sprint.
 *
 *  v2.0 / Stage 2 — interface extraction.
 */
object InterfaceExtractor:

  /** Compute a SHA-256 hex digest of raw bytes. */
  def sha256(bytes: Array[Byte]): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map("%02x".format(_)).mkString

  /** Extract a `ModuleInterface` from a parsed AST module + its source bytes.
   *
   *  @param module     The parsed AST module (from `Parser.parse`).
   *  @param sourceBytes The raw `.ssc` source bytes for the SHA-256 hash.
   *  @return A `ModuleInterface` ready to be serialised to `.scim` JSON.
   */
  def extract(module: ast.Module, sourceBytes: Array[Byte]): ModuleInterface =
    val pkg         = module.manifest.flatMap(_.pkg).getOrElse(Nil)
    val moduleName  = module.manifest.flatMap(_.name)
    val moduleVer   = module.manifest.flatMap(_.version)
    val deps        = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    val srcHash     = sha256(sourceBytes)

    // Run the typer to collect definitions with best-effort types.
    val typed = Typer.typeCheck(module)

    // Collect all DefSummary entries from the typed module.
    val allDefs = ListBuffer.empty[DefSummary]
    def gatherSection(s: scalascript.typer.TypedSection): Unit =
      s.definitions.foreach {
        case scalascript.typer.TypedDef.CodeBlock(_, _, defs) => allDefs ++= defs
        case _ => ()
      }
      s.subsections.foreach(gatherSection)
    typed.sections.foreach(gatherSection)

    // Also scan for `given` instances and `extern def` by walking the AST.
    val instances  = ListBuffer.empty[InstanceDecl]
    val externDefs = ListBuffer.empty[ExportedSymbol]

    def fqn(name: String): String =
      if pkg.isEmpty then name
      else (pkg :+ name).mkString("_")

    def scanStats(stats: List[Stat]): Unit =
      stats.foreach {
        // given instance: `given eqInt: Eq[Int] = ...`  or `given Eq[Int] = ...`
        case d: Defn.Given =>
          val witnessName = d.name.value
          // Attempt to pull the typeclass + type-param from the template
          // (best-effort: look at the parent type list).
          d.templ.inits.headOption.foreach { init =>
            init.tpe match
              case Type.Apply(Type.Name(tc), args) =>
                val typeParam = args.headOption.map(_.toString).getOrElse("_")
                instances += InstanceDecl(
                  typeclass   = tc,
                  typeParam   = typeParam,
                  witnessName = witnessName,
                  fqn         = fqn(witnessName)
                )
              case Type.Name(tc) =>
                instances += InstanceDecl(
                  typeclass   = tc,
                  typeParam   = "_",
                  witnessName = witnessName,
                  fqn         = fqn(witnessName)
                )
              case _ => ()
          }
        // extern def: in ScalaScript these are `def foo(...): T = ???` or annotated
        // We detect by looking for `extern` modifier or the `extern` keyword in source.
        // In the current AST representation, check for the Mod.Opaque or just
        // surface by name heuristic.  For now treat defs with body = `???` as extern.
        case dd: Defn.Def if isExternBody(dd.body) =>
          val tpeStr = dd.decltpe.map(_.toString).getOrElse("Any")
          externDefs += ExportedSymbol(
            name = dd.name.value,
            fqn  = fqn(dd.name.value),
            kind = "extern",
            tpe  = tpeStr
          )
        case _ => ()
      }

    def scanSection(sec: ast.Section): Unit =
      sec.content.foreach {
        case cb: ast.Content.CodeBlock if ast.Lang.isParseable(cb.lang) =>
          cb.tree.foreach { node =>
            scalascript.ast.ScalaNode.fold(node) {
              case Source(stats)     => scanStats(stats)
              case Term.Block(stats) => scanStats(stats)
              case _                 => ()
            }
          }
        case _ => ()
      }
      sec.subsections.foreach(scanSection)
    module.sections.foreach(scanSection)

    // Build exports from DefSummary list (excluding prelude builtins and params).
    val preludeNames = Set(
      "println", "print", "assert", "require", "Some", "None",
      "List", "Map", "math", "doc", "render", "serve"
    )
    val exports = allDefs
      .filterNot(d => preludeNames.contains(d.name) || d.kind == TSymbolKind.Param)
      .map { d =>
        ExportedSymbol(
          name = d.name,
          fqn  = fqn(d.name),
          kind = kindString(d.kind),
          tpe  = d.tpe.show
        )
      }
      .toList

    // Detect capabilities from the module source (text-scan heuristic).
    val capabilities = detectCapabilities(module)

    ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = pkg,
      moduleName    = moduleName,
      moduleVersion = moduleVer,
      sourceHash    = srcHash,
      exports       = exports,
      instances     = instances.toList,
      capabilities  = capabilities,
      externDefs    = externDefs.toList,
      dependencies  = deps
    )

  /** Detect well-known capability markers by scanning code-block sources.
   *  Heuristic: if any code block calls `serve(`, `fetch(`, `connect(` etc.
   *  we record the corresponding capability.  This will be superseded by
   *  the real `CapabilityCheck` in Stage 4. */
  private def detectCapabilities(module: ast.Module): List[CapabilityDecl] =
    val caps = scala.collection.mutable.Set.empty[String]
    def scan(src: String): Unit =
      if src.contains("serve(")   then caps += "Http"
      if src.contains("fetch(")   then caps += "Http"
      if src.contains("connect(") then caps += "WebSocket"
      if src.contains("readFile") || src.contains("writeFile") then caps += "FileSystem"
    def scanSec(sec: ast.Section): Unit =
      sec.content.foreach {
        case cb: ast.Content.CodeBlock => scan(cb.source)
        case _ => ()
      }
      sec.subsections.foreach(scanSec)
    module.sections.foreach(scanSec)
    caps.toList.sorted.map(CapabilityDecl(_))

  /** True when a `def` body is the `???` placeholder, indicating an extern. */
  private def isExternBody(body: Term): Boolean = body match
    case Term.Name("???") | Term.Select(_, Term.Name("???")) => true
    case Term.Throw(_)                                        => false
    case _                                                    => false

  private def kindString(k: TSymbolKind): String = k match
    case TSymbolKind.Val     => "val"
    case TSymbolKind.Var     => "var"
    case TSymbolKind.Def     => "def"
    case TSymbolKind.Type    => "type"
    case TSymbolKind.Class   => "class"
    case TSymbolKind.Object  => "object"
    case TSymbolKind.Trait   => "trait"
    case TSymbolKind.Enum    => "enum"
    case TSymbolKind.Param   => "param"
    case TSymbolKind.TypeParam => "typeparam"
