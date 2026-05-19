package scalascript.artifact

import scalascript.ir.ModuleInterface
import scalascript.typer.{Symbol, Scope, SType, SymbolKind as TSymbolKind}

/** A `Scope` populated from a pre-compiled `ModuleInterface`.
 *
 *  Allows the `Typer` to resolve cross-module references by consuming
 *  a `.scim` interface artifact without re-parsing the source module.
 *
 *  All symbols are initialised with best-effort types: the current typer
 *  produces `SType.Any` for most definitions; richer types will flow through
 *  once the typer is extended.  The interface still provides name-level
 *  resolution, which is sufficient to avoid "undefined symbol" errors on
 *  cross-module imports.
 *
 *  v2.0 / Stage 4 — typer consuming interfaces.
 */
object InterfaceScope:

  /** Build a `Scope` from a `ModuleInterface`.
   *
   *  The returned scope can be passed as the `parent` of a module's
   *  top-level scope so that names exported by the pre-compiled module
   *  are in scope during type-checking.
   *
   *  @param iface The pre-compiled module interface.
   *  @param parent An optional parent scope (e.g., the prelude scope).
   *  @return A scope containing all exported symbols from `iface`.
   */
  def fromInterface(iface: ModuleInterface, parent: Option[Scope] = None): Scope =
    val scope = Scope(parent, iface.moduleName.getOrElse(iface.pkg.mkString(".")))
    iface.exports.foreach { sym =>
      val tpe  = parseSType(sym.tpe)
      val kind = parseKind(sym.kind)
      scope.define(Symbol(sym.name, tpe, kind))
    }
    iface.externDefs.foreach { sym =>
      val tpe = parseSType(sym.tpe)
      scope.define(Symbol(sym.name, tpe, TSymbolKind.Def))
    }
    scope

  /** Build a merged scope from multiple module interfaces.
   *
   *  Used when a module imports from several pre-compiled dependencies.
   *  Names are merged in order; later modules shadow earlier ones if
   *  there are conflicts (same behaviour as source-level import ordering).
   *
   *  @param ifaces List of `(alias, interface)` pairs — the alias is the
   *                import binding name as it appears in the `.ssc` source.
   *  @param parent Optional parent scope (prelude).
   *  @return A flat scope with all exported names from all interfaces.
   */
  def fromInterfaces(
      ifaces: List[(String, ModuleInterface)],
      parent: Option[Scope] = None
  ): Scope =
    val merged = Scope(parent, "<imported>")
    ifaces.foreach { (_, iface) =>
      iface.exports.foreach { sym =>
        val tpe  = parseSType(sym.tpe)
        val kind = parseKind(sym.kind)
        merged.define(Symbol(sym.name, tpe, kind))
      }
      iface.externDefs.foreach { sym =>
        val tpe = parseSType(sym.tpe)
        merged.define(Symbol(sym.name, tpe, TSymbolKind.Def))
      }
    }
    merged

  // ─── Type + kind parsing ────────────────────────────────────────────────

  /** Parse the best-effort type string stored in an `ExportedSymbol`.
   *
   *  The format is the same as `SType.show` output:
   *    - `"Any"`, `"Int"`, `"String"` etc. → `SType.Named`
   *    - `"A => B"` → `SType.Function`
   *    - `"(A, B)"` → `SType.Tuple`
   *    - Anything unrecognised → `SType.Any`
   */
  private def parseSType(tpeStr: String): SType =
    tpeStr.trim match
      case "Any"     => SType.Any
      case "Unit"    => SType.Unit
      case "Boolean" => SType.Boolean
      case "Int"     => SType.Int
      case "Long"    => SType.Long
      case "Double"  => SType.Double
      case "String"  => SType.String
      case "Nothing" => SType.Nothing
      case s if s.contains(" => ") =>
        // Simple function: "A => B" (doesn't handle nested arrows correctly
        // but good enough for the current interface representation).
        val idx = s.indexOf(" => ")
        val lhs = s.substring(0, idx).trim
        val rhs = s.substring(idx + 4).trim
        SType.Function(List(parseSType(lhs)), parseSType(rhs))
      case s =>
        // Named type — possibly parameterised like "List[Int]" but we don't
        // parse args here; use the raw name with no args.
        val baseName = s.takeWhile(c => c != '[' && c != '(' && c != ' ')
        if baseName.isEmpty then SType.Any
        else SType.Named(baseName, Nil)

  private def parseKind(kind: String): TSymbolKind = kind match
    case "val"       => TSymbolKind.Val
    case "var"       => TSymbolKind.Var
    case "def"       => TSymbolKind.Def
    case "extern"    => TSymbolKind.Def
    case "type"      => TSymbolKind.Type
    case "class"     => TSymbolKind.Class
    case "object"    => TSymbolKind.Object
    case "trait"     => TSymbolKind.Trait
    case "enum"      => TSymbolKind.Enum
    case _           => TSymbolKind.Val
