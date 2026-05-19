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

  /** Parse a type string stored in an `ExportedSymbol` back into an `SType`.
   *
   *  This is the inverse of `SType.show` — given any string produced by
   *  `SType.show`, `parseSType` reconstructs a structurally equal `SType`
   *  value.  The grammar handled is a small Scala-flavoured type language:
   *
   *    Type    ::= Func
   *    Func    ::= Primary `=>` Func | Primary             // right-assoc
   *    Primary ::= `(` `)`                                  // Unit / nullary fn params
   *              | `(` Type `,` Type {`,` Type} `)`         // tuple / multi-arg fn params
   *              | `(` Type `)`                             // parenthesised
   *              | Path [ `[` Type {`,` Type} `]` ]         // named / type application
   *    Path    ::= Ident { `.` Ident }
   *
   *  Unrecognised input collapses to `SType.Any` so that a malformed
   *  interface artifact never crashes the typer — it simply offers no
   *  type information for the affected symbol.
   *
   *  v2.0 / Stage 4 — round-trips with `SType.show` for every shape the
   *  current typer emits (`Named`, `Function`, `Tuple`).  Higher-kinded
   *  types, refinements, unions / intersections and match types remain
   *  out of scope and fall back to `SType.Any`.
   */
  private[artifact] def parseSType(tpeStr: String): SType =
    val parser = TypeParser(tpeStr)
    parser.parseType() match
      case Some(t) if parser.atEnd => t
      case _                       => SType.Any

  /** A paren-shaped prefix whose meaning is fixed once we know whether
   *  `=>` follows: with an arrow it's a function param list, without it
   *  it's a tuple type (or `Unit` for the empty case). */
  private enum Primary:
    case Empty
    case Group(elems: List[SType])
    case Single(inner: SType)

  /** Tiny recursive-descent parser over the type-string grammar emitted by
   *  `SType.show`.  Not a general Scala type parser — only the constructs
   *  the typer can round-trip.
   */
  private final class TypeParser(input: String):
    private val s    = input
    private val len  = s.length
    private var pos  = 0

    def atEnd: Boolean = { skipWs(); pos >= len }

    private def skipWs(): Unit =
      while pos < len && s.charAt(pos).isWhitespace do pos += 1

    private def peek: Char =
      if pos < len then s.charAt(pos) else ' '

    private def consume(ch: Char): Boolean =
      skipWs()
      if pos < len && s.charAt(pos) == ch then { pos += 1; true } else false

    /** Match the literal `=>` token (preceded by optional ws). */
    private def consumeArrow(): Boolean =
      skipWs()
      if pos + 1 < len && s.charAt(pos) == '=' && s.charAt(pos + 1) == '>' then
        pos += 2; true
      else false

    /** A dotted identifier path, e.g. `foo.Bar.Baz`.  Returns the joined
     *  name (with dots preserved) or `None` if no ident starts here. */
    private def parsePath(): Option[String] =
      skipWs()
      val start = pos
      if !isIdentStart(peek) then return None
      while pos < len && isIdentPart(s.charAt(pos)) do pos += 1
      val sb = new StringBuilder(s.substring(start, pos))
      // Trailing `.ident` segments.
      var keepGoing = true
      while keepGoing do
        val save = pos
        skipWs()
        if pos < len && s.charAt(pos) == '.' then
          pos += 1
          val segStart = pos
          if pos < len && isIdentStart(s.charAt(pos)) then
            while pos < len && isIdentPart(s.charAt(pos)) do pos += 1
            sb.append('.').append(s.substring(segStart, pos))
          else
            // Trailing dot not followed by an ident — undo.
            pos = save
            keepGoing = false
        else
          pos = save
          keepGoing = false
      Some(sb.toString)

    private def isIdentStart(c: Char): Boolean =
      c.isLetter || c == '_'
    private def isIdentPart(c: Char): Boolean =
      c.isLetterOrDigit || c == '_'

    /** Entry point — a full type (function arrow has the lowest
     *  precedence and is right-associative). */
    def parseType(): Option[SType] =
      parsePrimary().flatMap { lhs =>
        skipWs()
        if consumeArrow() then
          parseType().map { rhs =>
            lhs match
              case Primary.Group(elems)  => SType.Function(elems, rhs)
              case Primary.Empty         => SType.Function(Nil, rhs)
              case Primary.Single(inner) => SType.Function(List(inner), rhs)
          }
        else
          lhs match
            case Primary.Group(elems)  => Some(SType.Tuple(elems))
            case Primary.Empty         => Some(SType.Unit)
            case Primary.Single(inner) => Some(inner)
      }

    /** Primary — either a real `SType` (`Single`) or a paren-shaped
     *  prefix (`Group` / `Empty`) whose meaning depends on whether
     *  `=>` follows. */
    private def parsePrimary(): Option[Primary] =
      skipWs()
      if pos >= len then None
      else
        val c = s.charAt(pos)
        if c == '(' then parseParen()
        else if isIdentStart(c) then parseNamedOrApp()
        else None

    private def parseParen(): Option[Primary] =
      // Caller has verified that we sit on '('.
      pos += 1
      skipWs()
      if pos < len && s.charAt(pos) == ')' then
        pos += 1
        Some(Primary.Empty)
      else
        parseType() match
          case None => None
          case Some(first) =>
            val elems = scala.collection.mutable.ListBuffer(first)
            var ok    = true
            while ok && consume(',') do
              parseType() match
                case Some(t) => elems += t
                case None    => ok = false
            if !ok || !consume(')') then None
            else if elems.length == 1 then Some(Primary.Single(first))
            else Some(Primary.Group(elems.toList))

    private def parseNamedOrApp(): Option[Primary] =
      parsePath() match
        case None       => None
        case Some(name) =>
          skipWs()
          if pos < len && s.charAt(pos) == '[' then
            pos += 1
            parseType() match
              case None => None
              case Some(firstArg) =>
                val args = scala.collection.mutable.ListBuffer(firstArg)
                var ok   = true
                while ok && consume(',') do
                  parseType() match
                    case Some(t) => args += t
                    case None    => ok = false
                if !ok || !consume(']') then None
                else Some(Primary.Single(SType.Named(name, args.toList)))
          else
            Some(Primary.Single(SType.Named(name, Nil)))
  end TypeParser

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
