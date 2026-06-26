package ssc

// ssc0 front: source -> IR  (specs/15-ssc0.md). Lexer -> Parser -> Lower.
// ssc0 is the thin untyped surface; it lowers to the Core IR bytecode the VM runs.

// ── ssc0 surface AST ─────────────────────────────────────────────────────────

enum S0:
  case Lit(c: Const)
  case Var(name: String)
  case Lam(params: List[String], body: S0)
  case App(fn: S0, args: List[S0])
  case Let(binds: List[(String, S0)], body: S0)
  case LetRec(binds: List[(String, S0)], body: S0)
  case If(c: S0, t: S0, e: S0)
  case Ctor(tag: String, args: List[S0])
  case Match(scrut: S0, arms: List[S0Arm])
  case Prim(op: String, args: List[S0])

case class S0Arm(pat: Pat, body: S0)
enum Pat:
  case PCtor(tag: String, vars: List[String])
  case PWild
case class S0Def(name: String, body: S0)
case class S0File(imports: List[String], defs: List[S0Def])   // one parsed source file
case class S0Module(defs: List[S0Def])                        // all files merged (flat namespace)

// ── Lexer ────────────────────────────────────────────────────────────────────

enum Tok:
  case LParen, RParen, LBrace, RBrace, Comma, Arrow, Eq, Underscore
  case KwDef, KwLet, KwRec, KwIn, KwIf, KwThen, KwElse, KwMatch, KwCase, KwImport
  case Lower(s: String)
  case Upper(s: String)
  case PrimName(s: String)
  case IntLit(n: Long)
  case BigLit(n: BigInt)
  case FloatLit(d: Double)
  case StrLit(s: String)
  case TrueLit, FalseLit
  case EOF

object Lexer:
  def lex(src: String): Vector[Tok] =
    val out = collection.mutable.ArrayBuffer[Tok]()
    var i = 0
    val n = src.length
    def isIdStart(c: Char) = c.isLetter || c == '_'
    def isIdPart(c: Char)  = c.isLetterOrDigit || c == '_'
    while i < n do
      val c = src.charAt(i)
      if c == '-' && i + 1 < n && src.charAt(i + 1) == '-' then          // -- line comment
        while i < n && src.charAt(i) != '\n' do i += 1
      else if c == '{' && i + 1 < n && src.charAt(i + 1) == '-' then     // {- block comment -}
        i += 2
        while i + 1 < n && !(src.charAt(i) == '-' && src.charAt(i + 1) == '}') do i += 1
        i += 2
      else if c.isWhitespace then i += 1
      else if c == '(' then { out += Tok.LParen; i += 1 }
      else if c == ')' then { out += Tok.RParen; i += 1 }
      else if c == '{' then { out += Tok.LBrace; i += 1 }
      else if c == '}' then { out += Tok.RBrace; i += 1 }
      else if c == ',' then { out += Tok.Comma; i += 1 }
      else if c == '=' && i + 1 < n && src.charAt(i + 1) == '>' then { out += Tok.Arrow; i += 2 }
      else if c == '=' then { out += Tok.Eq; i += 1 }
      else if c == '#' then                                              // #prim.op  (incl. names like i->big)
        var j = i + 1
        while j < n && { val ch = src.charAt(j); ch.isLetterOrDigit || ch == '.' || ch == '_' || ch == '-' || ch == '>' } do j += 1
        out += Tok.PrimName(src.substring(i + 1, j)); i = j
      else if c == '"' then
        val (s, j) = lexString(src, i + 1); out += Tok.StrLit(s); i = j
      else if c.isDigit then
        val (tok, j) = lexNumber(src, i); out += tok; i = j
      else if c == '_' && !(i + 1 < n && isIdPart(src.charAt(i + 1))) then { out += Tok.Underscore; i += 1 }
      else if isIdStart(c) then
        var j = i
        while j < n && isIdPart(src.charAt(j)) do j += 1
        val word = src.substring(i, j); i = j
        out += keyword(word)
      else sys.error(s"lex error at $i: '$c'")
    out += Tok.EOF
    out.toVector

  private def keyword(w: String): Tok = w match
    case "def" => Tok.KwDef;   case "let" => Tok.KwLet; case "rec" => Tok.KwRec
    case "in" => Tok.KwIn;     case "if" => Tok.KwIf;   case "then" => Tok.KwThen
    case "else" => Tok.KwElse; case "match" => Tok.KwMatch; case "case" => Tok.KwCase
    case "import" => Tok.KwImport; case "true" => Tok.TrueLit; case "false" => Tok.FalseLit
    case _ => if w.charAt(0).isUpper then Tok.Upper(w) else Tok.Lower(w)

  private def lexString(src: String, start: Int): (String, Int) =
    val sb = new StringBuilder; var i = start
    while i < src.length && src.charAt(i) != '"' do
      val c = src.charAt(i)
      if c == '\\' then
        i += 1; src.charAt(i) match
          case '"' => sb += '"'; case '\\' => sb += '\\'
          case 'n' => sb += '\n'; case 'r' => sb += '\r'; case 't' => sb += '\t'
          case 'u' => sb += Integer.parseInt(src.substring(i + 1, i + 5), 16).toChar; i += 4
          case o => sys.error(s"bad escape \\$o")
        i += 1
      else { sb += c; i += 1 }
    (sb.toString, i + 1)

  private def lexNumber(src: String, start: Int): (Tok, Int) =
    var j = start; val n = src.length
    while j < n && src.charAt(j).isDigit do j += 1
    if j < n && src.charAt(j) == 'n' then (Tok.BigLit(BigInt(src.substring(start, j))), j + 1)
    else if (j < n && (src.charAt(j) == '.' || src.charAt(j) == 'e' || src.charAt(j) == 'E')) then
      if src.charAt(j) == '.' then { j += 1; while j < n && src.charAt(j).isDigit do j += 1 }
      if j < n && (src.charAt(j) == 'e' || src.charAt(j) == 'E') then
        j += 1; if j < n && (src.charAt(j) == '+' || src.charAt(j) == '-') then j += 1
        while j < n && src.charAt(j).isDigit do j += 1
      (Tok.FloatLit(src.substring(start, j).toDouble), j)
    else (Tok.IntLit(src.substring(start, j).toLong), j)

// ── Parser (recursive descent over a token vector, with backtracking) ─────────

object Parser:
  def parse(toks: Vector[Tok]): S0File = new P(toks).module()

  final class P(toks: Vector[Tok]):
    var pos = 0
    def peek = toks(pos)
    def peekAt(k: Int) = if pos + k < toks.length then toks(pos + k) else Tok.EOF
    def advance(): Tok = { val t = toks(pos); pos += 1; t }
    def expect(t: Tok): Unit = if peek == t then pos += 1 else sys.error(s"expected $t, got ${peek} at $pos")
    def expectLower(): String = peek match { case Tok.Lower(s) => pos += 1; s; case _ => sys.error(s"expected identifier, got ${peek}") }
    def expectStr(): String = peek match { case Tok.StrLit(s) => pos += 1; s; case _ => sys.error(s"expected string, got ${peek}") }

    def module(): S0File =
      val imports = collection.mutable.ListBuffer[String]()
      val defs = collection.mutable.ListBuffer[S0Def]()
      while peek != Tok.EOF do peek match
        case Tok.KwDef =>
          pos += 1; val name = expectLower(); expect(Tok.Eq); defs += S0Def(name, expr())
        case Tok.KwImport => pos += 1; imports += expectStr()
        case other => sys.error(s"expected `def` or `import`, got $other")
      S0File(imports.toList, defs.toList)

    def expr(): S0 = peek match
      case Tok.KwLet   => parseLet()
      case Tok.KwIf    => parseIf()
      case Tok.KwMatch => parseMatch()
      case _           => tryLambda().getOrElse(app())

    def tryLambda(): Option[S0] =
      tryParenParamsArrow() match
        case Some(ps) => Some(S0.Lam(ps, expr()))
        case None => peek match
          case Tok.Lower(x) if peekAt(1) == Tok.Arrow => pos += 2; Some(S0.Lam(List(x), expr()))
          case _ => None

    // attempt `( (Lower (, Lower)*)? ) =>`, restoring pos on failure
    def tryParenParamsArrow(): Option[List[String]] =
      val save = pos
      if peek != Tok.LParen then return None
      pos += 1
      val ps = collection.mutable.ListBuffer[String]()
      if peek == Tok.RParen then pos += 1
      else
        var go = true
        while go do
          peek match
            case Tok.Lower(x) => ps += x; pos += 1
            case _ => pos = save; return None
          peek match
            case Tok.Comma  => pos += 1
            case Tok.RParen => pos += 1; go = false
            case _ => pos = save; return None
      if peek == Tok.Arrow then { pos += 1; Some(ps.toList) } else { pos = save; None }

    def app(): S0 =
      var e = atom()
      while peek == Tok.LParen do { pos += 1; e = S0.App(e, args()) }
      e

    def atom(): S0 = peek match
      case Tok.Upper(tag) =>
        pos += 1
        if peek == Tok.LParen then { pos += 1; S0.Ctor(tag, args()) } else S0.Ctor(tag, Nil)
      case Tok.PrimName(op) =>
        pos += 1
        if peek == Tok.LParen then { pos += 1; S0.Prim(op, args()) }
        else sys.error(s"bare primitive #$op as a value is not supported in v1; wrap it, e.g. (x) => #$op(x)")
      case Tok.Lower(x)   => pos += 1; S0.Var(x)
      case Tok.IntLit(n)  => pos += 1; S0.Lit(Const.CInt(n))
      case Tok.BigLit(n)  => pos += 1; S0.Lit(Const.CBig(n))
      case Tok.FloatLit(d)=> pos += 1; S0.Lit(Const.CFloat(d))
      case Tok.StrLit(s)  => pos += 1; S0.Lit(Const.CStr(s))
      case Tok.TrueLit    => pos += 1; S0.Lit(Const.CBool(true))
      case Tok.FalseLit   => pos += 1; S0.Lit(Const.CBool(false))
      case Tok.LParen =>
        pos += 1
        if peek == Tok.RParen then { pos += 1; S0.Lit(Const.CUnit) }       // () = unit
        else { val e = expr(); expect(Tok.RParen); e }
      case other => sys.error(s"unexpected token in expression: $other at $pos")

    def args(): List[S0] =                                                  // '(' already consumed
      if peek == Tok.RParen then { pos += 1; Nil }
      else
        val xs = collection.mutable.ListBuffer[S0](expr())
        while peek == Tok.Comma do { pos += 1; xs += expr() }
        expect(Tok.RParen); xs.toList

    def parseLet(): S0 =
      expect(Tok.KwLet)
      val rec = if peek == Tok.KwRec then { pos += 1; true } else false
      val binds = collection.mutable.ListBuffer[(String, S0)](parseBind())
      while peek == Tok.Comma do { pos += 1; binds += parseBind() }
      expect(Tok.KwIn)
      val body = expr()
      if rec then S0.LetRec(binds.toList, body) else S0.Let(binds.toList, body)
    def parseBind(): (String, S0) = { val x = expectLower(); expect(Tok.Eq); (x, expr()) }

    def parseIf(): S0 =
      expect(Tok.KwIf); val c = expr(); expect(Tok.KwThen); val t = expr(); expect(Tok.KwElse); S0.If(c, t, expr())

    def parseMatch(): S0 =
      expect(Tok.KwMatch); val s = expr(); expect(Tok.LBrace)
      val arms = collection.mutable.ListBuffer[S0Arm]()
      while peek == Tok.KwCase do
        pos += 1; val p = parsePat(); expect(Tok.Arrow); arms += S0Arm(p, expr())
      expect(Tok.RBrace)
      S0.Match(s, arms.toList)
    def parsePat(): Pat = peek match
      case Tok.Underscore => pos += 1; Pat.PWild
      case Tok.Upper(tag) =>
        pos += 1
        if peek == Tok.LParen then
          pos += 1
          val vs = collection.mutable.ListBuffer[String]()
          if peek == Tok.RParen then pos += 1
          else
            vs += expectLower()
            while peek == Tok.Comma do { pos += 1; vs += expectLower() }
            expect(Tok.RParen)
          Pat.PCtor(tag, vs.toList)
        else Pat.PCtor(tag, Nil)
      case other => sys.error(s"bad pattern: $other")

// ── Loader: resolve `import "path"` across files into one flat module ─────────

object Loader:
  // Load an entry ssc0 file and all its (transitive) imports into one S0Module.
  // Imports resolve relative to the importing file; each file is loaded once
  // (cycle-safe); top-level def names must be unique across the whole program.
  def load(entry: String): S0Module =
    val files = collection.mutable.LinkedHashMap[String, S0File]()  // canonical path -> parsed
    def go(path: String): Unit =
      val canon = new java.io.File(path).getCanonicalPath
      if !files.contains(canon) then
        val src = scala.io.Source.fromFile(canon)(using scala.io.Codec.UTF8).mkString
        val file = Parser.parse(Lexer.lex(src))
        files(canon) = file                                        // record BEFORE recursing (cycles)
        val dir = new java.io.File(canon).getParent
        for imp <- file.imports do go(new java.io.File(dir, imp).getPath)
    go(entry)
    val defs = files.values.flatMap(_.defs).toList
    val dup = defs.groupBy(_.name).collectFirst { case (n, ds) if ds.length > 1 => n }
    dup.foreach(n => sys.error(s"duplicate top-level def `$n` across imported files"))
    S0Module(defs)

// ── Lower: ssc0 -> Core IR (name resolution to de Bruijn) ─────────────────────

object Lower:
  import Term.*

  def module(m: S0Module): Program =
    val globals = m.defs.map(_.name).toSet
    val defs = m.defs.map(d => Def(d.name, lower(d.body, Nil, globals)))
    if !globals.contains("main") then sys.error("no `def main` entry point")
    Program(defs, App(Global("main"), Nil))   // entry = force the () => ... thunk `main`

  // scope: bound local names, head = de Bruijn index 0 (innermost / last bound)
  def lower(e: S0, scope: List[String], globals: Set[String]): Term = e match
    case S0.Lit(c) => Lit(c)
    case S0.Var(x) =>
      val i = scope.indexOf(x)
      if i >= 0 then Local(i)
      else if globals.contains(x) then Global(x)
      else sys.error(s"unbound variable: $x")
    case S0.Lam(ps, b) => Lam(ps.length, lower(b, push(ps, scope), globals))
    case S0.App(f, as) => App(lower(f, scope, globals), as.map(lower(_, scope, globals)))
    case S0.If(c, t, e2) => If(lower(c, scope, globals), lower(t, scope, globals), lower(e2, scope, globals))
    case S0.Ctor(tag, as) => Ctor(tag, as.map(lower(_, scope, globals)))
    case S0.Prim(op, as) => Prim(op, as.map(lower(_, scope, globals)))
    case S0.Let(bs, body) =>
      var sc = scope
      val rhs = bs.map { case (name, rhsE) =>
        val t = lower(rhsE, sc, globals); sc = name :: sc; t       // sequential: bind after lowering
      }
      Let(rhs, lower(body, sc, globals))
    case S0.LetRec(bs, body) =>
      val sc = push(bs.map(_._1), scope)                            // all names in scope (last = idx 0)
      val lams = bs.map {
        case (_, l @ S0.Lam(_, _)) => lower(l, sc, globals)
        case (name, _) => sys.error(s"letrec binding `$name` must be a lambda")
      }
      LetRec(lams, lower(body, sc, globals))
    case S0.Match(scrut, arms) =>
      var default: Option[Term] = None
      val armList = collection.mutable.ListBuffer[Arm]()
      for a <- arms do a.pat match
        case Pat.PCtor(tag, vars) => armList += Arm(tag, vars.length, lower(a.body, push(vars, scope), globals))
        case Pat.PWild => default = Some(lower(a.body, scope, globals))
      Match(lower(scrut, scope, globals), armList.toList, default)

  // push a binder group so the LAST name is index 0 (matches §1.3 + eval frames)
  private def push(names: List[String], scope: List[String]): List[String] = names.reverse ::: scope
