package scalascript.artifact

import scalascript.ir.ModuleInterface
import scalascript.typer.{Symbol, Scope, SType, SymbolKind as TSymbolKind, RefMember, MatchCase}

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
   *    Func    ::= Union `=>` Func | Union                  // right-assoc
   *    Union   ::= Inter { `|` Inter }                      // flat, lower-prec
   *    Inter   ::= Primary { `&` Primary }                  // flat, tighter
   *    Primary ::= `(` `)`                                  // Unit / nullary fn params
   *              | `(` Type `,` Type {`,` Type} `)`         // tuple / multi-arg fn params
   *              | `(` Type `)`                             // parenthesised
   *              | Path [ `[` TArg {`,` TArg} `]` ]         // named / type app / HK
   *    TArg    ::= `_` | Type                               // `_` only inside `[...]`
   *    Path    ::= Ident { `.` Ident }
   *
   *  Precedence (Scala-3 official): `&` binds tighter than `|`; both are
   *  tighter than `=>` (function arrow).  Inside `[...]`, a bare `_`
   *  marks a higher-kinded slot, so `F[_]` becomes
   *  `SType.HigherKinded("F", 1)` and `F[_, _]` becomes arity 2.
   *
   *  Unrecognised input collapses to `SType.Any` so that a malformed
   *  interface artifact never crashes the typer — it simply offers no
   *  type information for the affected symbol.
   *
   *  v2.0 — round-trips with `SType.show` for `Named`, `Function`,
   *  `Tuple`, `Union`, `Intersection`, `HigherKinded`, `Refinement`
   *  (`A { def foo: Int }`) and `Match` (`T match { case Int => String }`).
   *  A refinement / match type binds to the immediately-preceding primary
   *  (so `A => B { def foo: Int }` parses as `A => (B { def foo: Int })`).
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
     *  precedence and is right-associative).
     *
     *  We start by parsing a `Union`-level expression and only then
     *  decide whether a `=>` follows.  This gives the intended
     *  precedence: `&` < `|` < `=>` — so `Int | String => Boolean`
     *  is `Function(List(Union(Int, String)), Boolean)`.
     *
     *  A parenthesised primary that wasn't promoted to a function
     *  param/tuple keeps its `Primary` flavour through the `Union`
     *  level only when it stands alone (no `|` / `&` follows).
     */
    def parseType(): Option[SType] =
      parseUnion().flatMap { lhs =>
        skipWs()
        if consumeArrow() then
          parseEffectfulResult().map { (rhs, effs) =>
            lhs match
              case Primary.Group(elems)  => SType.Function(elems, rhs, effs)
              case Primary.Empty         => SType.Function(Nil, rhs, effs)
              case Primary.Single(inner) => SType.Function(List(inner), rhs, effs)
          }
        else
          lhs match
            case Primary.Group(elems)  => Some(SType.Tuple(elems))
            case Primary.Empty         => Some(SType.Unit)
            case Primary.Single(inner) => Some(inner)
      }

    /** Parse a Union-level expression — a chain of `|`-separated
     *  Intersection terms.  Returns a `Primary` so the caller can
     *  still distinguish a paren-shaped prefix waiting for an `=>`. */
    private def parseUnion(): Option[Primary] =
      parseIntersection().flatMap { first =>
        skipWs()
        if peek != '|' then Some(first)
        else
          val firstS = primaryToSType(first)
          val parts  = scala.collection.mutable.ListBuffer(firstS)
          var ok     = true
          while ok && consumePipe() do
            parseIntersection() match
              case Some(p) => parts += primaryToSType(p)
              case None    => ok = false
          if !ok then None
          else Some(Primary.Single(SType.Union(parts.toList)))
      }

    /** Parse an Intersection-level expression — a chain of
     *  `&`-separated Primaries. */
    private def parseIntersection(): Option[Primary] =
      parsePrimary().flatMap { first =>
        skipWs()
        if peek != '&' then Some(first)
        else
          val firstS = primaryToSType(first)
          val parts  = scala.collection.mutable.ListBuffer(firstS)
          var ok     = true
          while ok && consume('&') do
            parsePrimary() match
              case Some(p) => parts += primaryToSType(p)
              case None    => ok = false
          if !ok then None
          else Some(Primary.Single(SType.Intersection(parts.toList)))
      }

    /** Lift a `Primary` (which may still be a paren-shape) into a
     *  concrete `SType` for use as a `Union` / `Intersection` member.
     *  An empty paren `()` becomes `Unit`; a group becomes a `Tuple`. */
    private def primaryToSType(p: Primary): SType = p match
      case Primary.Single(t)     => t
      case Primary.Empty         => SType.Unit
      case Primary.Group(elems)  => SType.Tuple(elems)

    /** Match a single `|` (but not `||`) preceded by optional ws. */
    private def consumePipe(): Boolean =
      skipWs()
      if pos < len && s.charAt(pos) == '|' &&
         (pos + 1 >= len || s.charAt(pos + 1) != '|')
      then { pos += 1; true }
      else false

    /** Primary — either a real `SType` (`Single`) or a paren-shaped
     *  prefix (`Group` / `Empty`) whose meaning depends on whether
     *  `=>` follows.
     *
     *  After the base primary is parsed, we look for refinement / match
     *  suffixes (`{ ... }` and `match { ... }`) and wrap the primary if
     *  found.  These bind tighter than `&` / `|` / `=>`, so they attach
     *  to the closest preceding primary — `A => B { def foo: Int }`
     *  becomes `A => (B { def foo: Int })`.
     */
    private def parsePrimary(): Option[Primary] =
      skipWs()
      if pos >= len then None
      else
        val base =
          val c = s.charAt(pos)
          if c == '(' then parseParen()
          else if isIdentStart(c) then parseNamedOrApp()
          else None
        base.flatMap(parseTypeSuffix)

    /** Apply zero or more postfix `{ ... }` (refinement) or
     *  `match { ... }` clauses to a primary.  Each suffix consumes the
     *  current primary and wraps it. */
    private def parseTypeSuffix(p: Primary): Option[Primary] =
      skipWs()
      if peek == '{' then
        parseRefinementBody().flatMap { members =>
          val base = primaryToSType(p)
          parseTypeSuffix(Primary.Single(SType.Refinement(base, members)))
        }
      else if peekKeyword("match") then
        parseMatchBody().flatMap { cases =>
          val scrut = primaryToSType(p)
          parseTypeSuffix(Primary.Single(SType.Match(scrut, cases)))
        }
      else Some(p)

    /** True if the upcoming non-ws token is exactly the keyword `kw`
     *  (not a longer identifier that starts with `kw`). */
    private def peekKeyword(kw: String): Boolean =
      val save = pos
      skipWs()
      val ok =
        pos + kw.length <= len &&
        s.substring(pos, pos + kw.length) == kw &&
        (pos + kw.length >= len || !isIdentPart(s.charAt(pos + kw.length)))
      pos = save
      ok

    /** Consume the literal keyword `kw` (with optional leading ws).
     *  Caller must have already verified with `peekKeyword`. */
    private def consumeKeyword(kw: String): Boolean =
      skipWs()
      if pos + kw.length <= len &&
         s.substring(pos, pos + kw.length) == kw &&
         (pos + kw.length >= len || !isIdentPart(s.charAt(pos + kw.length)))
      then { pos += kw.length; true }
      else false

    /** Parse a `{ <member>; <member>; ... }` refinement body where each
     *  member is `<kind> <name>: <Type>` and `<kind>` ∈ {def, val, type}. */
    private def parseRefinementBody(): Option[List[RefMember]] =
      if !consume('{') then return None
      val members = scala.collection.mutable.ListBuffer.empty[RefMember]
      skipWs()
      // Empty `{ }` is valid — yields a refinement with no members.
      if pos < len && s.charAt(pos) == '}' then
        pos += 1
        return Some(Nil)
      var ok = true
      var first = true
      while ok && first || ok && consume(';') do
        first = false
        parseRefMember() match
          case Some(m) => members += m
          case None    => ok = false
        skipWs()
        if ok && pos < len && s.charAt(pos) == '}' then
          pos += 1
          return Some(members.toList)
      None

    /** Parse a single refinement member.
     *
     *  Accepted shapes (only `:` is emitted by `SType.show`; `=` is
     *  tolerated for type aliases on the input side so source-style
     *  `type T = Int` parses without an extra normalisation step):
     *
     *  - `def <ident>: <Type>`
     *  - `val <ident>: <Type>`
     *  - `type <ident>: <Type>`
     *  - `type <ident> = <Type>`
     */
    private def parseRefMember(): Option[RefMember] =
      skipWs()
      val kindStart = pos
      val kind =
        if consumeKeyword("def") then "def"
        else if consumeKeyword("val") then "val"
        else if consumeKeyword("type") then "type"
        else { pos = kindStart; return None }
      skipWs()
      val nameStart = pos
      if !isIdentStart(peek) then return None
      while pos < len && isIdentPart(s.charAt(pos)) do pos += 1
      val name = s.substring(nameStart, pos)
      skipWs()
      // For `type` members, accept either `:` or `=` as the separator.
      val sep =
        if consume(':') then true
        else if kind == "type" && consumeSingleEquals() then true
        else false
      if !sep then return None
      parseType().map(t => RefMember(kind, name, t))

    /** Consume a single `=` (but not `=>`) preceded by optional ws. */
    private def consumeSingleEquals(): Boolean =
      skipWs()
      if pos < len && s.charAt(pos) == '=' &&
         (pos + 1 >= len || s.charAt(pos + 1) != '>')
      then { pos += 1; true }
      else false

    /** Parse a `{ case <pat> => <rhs>; case <pat> => <rhs>; ... }` body
     *  of a match-type. */
    private def parseMatchBody(): Option[List[MatchCase]] =
      if !consumeKeyword("match") then return None
      skipWs()
      if !consume('{') then return None
      val cases = scala.collection.mutable.ListBuffer.empty[MatchCase]
      var ok = true
      var first = true
      while ok && first || ok && consume(';') do
        first = false
        parseMatchCase() match
          case Some(c) => cases += c
          case None    => ok = false
        skipWs()
        if ok && pos < len && s.charAt(pos) == '}' then
          pos += 1
          return Some(cases.toList)
      None

    /** Parse a single match-case: `case <pattern> => <rhs>`.
     *  A bare `_` pattern is recorded as `SType.Named("_", Nil)`.
     *
     *  The pattern parser intentionally stops at the case arrow — we
     *  parse only at the `Union` precedence level (no `=>`) so that
     *  `case Int => String` keeps `Int` as the pattern, not
     *  `Int => String`.  A function-typed pattern can still be written
     *  with explicit parens: `case (Int => String) => Any`. */
    private def parseMatchCase(): Option[MatchCase] =
      if !consumeKeyword("case") then return None
      skipWs()
      val pattern =
        if pos < len && s.charAt(pos) == '_' &&
           (pos + 1 >= len || !isIdentPart(s.charAt(pos + 1)))
        then { pos += 1; Some(SType.Named("_", Nil)) }
        else parseUnion().map(primaryToSType)
      pattern.flatMap { pat =>
        if !consumeArrow() then None
        else parseType().map(rhs => MatchCase(pat, rhs))
      }

    /** Parse the RHS of a `=>` — a type optionally followed by `! EffSet`. */
    private def parseEffectfulResult(): Option[(SType, SType.EffectRow)] =
      parseType().map { resultType =>
        skipWs()
        if consumeBang() then
          parseEffectSet() match
            case Some(effs) => (resultType, effs)
            case None       => (resultType, SType.EffectRow(None, Set.empty))
        else
          (resultType, SType.EffectRow(None, Set.empty))
      }

    /** Consume a single `!` (but not `!=`) preceded by optional ws. */
    private def consumeBang(): Boolean =
      skipWs()
      if pos < len && s.charAt(pos) == '!' &&
         (pos + 1 >= len || s.charAt(pos + 1) != '=')
      then { pos += 1; true }
      else false

    /** Parse an effect set after `!`: either a single name or `(Name1, Name2, ...)`. */
    private def parseEffectSet(): Option[SType.EffectRow] =
      skipWs()
      if peek == '(' then
        pos += 1  // consume '('
        val names = scala.collection.mutable.ListBuffer.empty[String]
        var ok    = true
        var first = true
        while ok && (first || consume(',')) do
          first = false
          skipWs()
          parsePath() match
            case Some(n) => names += n
            case None    => ok = false
        if !ok || !consume(')') then None
        else Some(SType.EffectRow(None, names.toSet))
      else
        parsePath().map(n => SType.EffectRow(None, Set(n)))

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
            parseTypeArg() match
              case None => None
              case Some(firstArg) =>
                val args = scala.collection.mutable.ListBuffer(firstArg)
                var ok   = true
                while ok && consume(',') do
                  parseTypeArg() match
                    case Some(t) => args += t
                    case None    => ok = false
                if !ok || !consume(']') then None
                else
                  // All-`_` args ⇒ higher-kinded placeholder; otherwise
                  // a regular type application.  A `_` mixed with other
                  // args is preserved as `Named("_", Nil)` so partial
                  // shapes like `Map[String, _]` keep their structure.
                  val argList = args.toList
                  val isWild  = (t: SType) => t == SType.Named("_", Nil)
                  if argList.nonEmpty && argList.forall(isWild) then
                    Some(Primary.Single(SType.HigherKinded(name, argList.length)))
                  else
                    Some(Primary.Single(SType.Named(name, argList)))
          else
            Some(Primary.Single(SType.Named(name, Nil)))

    /** A single type argument inside `[...]`.  A bare `_` is recognised
     *  as the higher-kinded placeholder marker (rendered as
     *  `SType.Named("_", Nil)` here; the enclosing app collapses it
     *  into `HigherKinded` when all arguments are wildcards). */
    private def parseTypeArg(): Option[SType] =
      skipWs()
      if pos < len && s.charAt(pos) == '_' &&
         (pos + 1 >= len || !isIdentPart(s.charAt(pos + 1)))
      then
        pos += 1
        Some(SType.Named("_", Nil))
      else
        parseType()
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
