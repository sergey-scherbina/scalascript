package scalascript.uniml.spike

import scalascript.uniml.*

/** v2.2 spike — a UniML dialect for a growing Scala subset.
  *   `def NAME(p: Int, ...): Int = BODY` where BODY is either an inline EXPR or an
  *   OFFSIDE (indented) BLOCK of `val NAME = EXPR` statements ending in an expr;
  *   EXPR over int / id / call `f(a,b)` / `( )` / `if c then t else e` / the full
  *   ssc-v2 infix operator set with the `ssc1-front` precedence table.
  *
  * P6.0 gate: precedence via a Pratt parse INSIDE the dialect, serialised to
  *   `VmToken`s (open-on-first-token / `Reframe.closeAfter`-on-last). P6.1: total,
  *   error-resilient (never throws, diagnostics, `def`-boundary resync, holes). P6.2a:
  *   full infix table (byte-identical Core IR vs `ssc1-front`). P6.2b (this slice):
  *   OFFSIDE LAYOUT — an indented def body is a block; block structure is computed in
  *   the parser from token COLUMNS (no synthetic tokens, so the CST stays lossless),
  *   and the block frame is emitted uniformly. Statement separation is implicit
  *   (`parseExpr` stops at a non-operator; a leading infix op / call continues the
  *   line, matching ssc1-front's continuation rule). `mkVal`/`Pair("block",…)`/
  *   `Pair("expr",…)` mirror ssc1-front, so `lowerBlock` produces identical nested lets.
  */

enum Node:
  case Leaf(tok: SourceToken, role: Option[String])
  case Frame(kind: String, role: Option[String], children: Vector[Node])

// ── lexer ────────────────────────────────────────────────────────────────────
object SpikeLex:
  private val keywords = Set("def", "val", "if", "then", "else", "match", "case", "class", "given", "enum", "extension")
  private def isOpChar(c: Char): Boolean = "+-*/%<>=!&|^~:".indexOf(c.toInt) >= 0

  def scan(src: SourceId, text: String): Vector[SourceToken] =
    val out = Vector.newBuilder[SourceToken]
    var i = 0
    var line = 1
    var col = 1
    var id = 0L
    val n = text.length
    def pos = SourcePosition(i, line, col)
    def advance(c: Char): Unit =
      if c == '\n' then { line += 1; col = 1 } else col += 1
      i += 1
    def emit(kind: String, start: SourcePosition, lexeme: String, chan: TokenChannel): Unit =
      out += SourceToken(id, kind, lexeme, SourceSpan(src, start, pos), chan)
      id += 1
    while i < n do
      val c = text.charAt(i)
      val start = pos
      if c == ' ' || c == '\t' || c == '\n' || c == '\r' then
        val sb = new StringBuilder
        while i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t' || text.charAt(i) == '\n' || text.charAt(i) == '\r') do
          sb.append(text.charAt(i)); advance(text.charAt(i))
        emit("spike.ws", start, sb.toString, TokenChannel.Trivia)
      else if c.isDigit then
        val sb = new StringBuilder
        while i < n && text.charAt(i).isDigit do { sb.append(text.charAt(i)); advance(text.charAt(i)) }
        // `1.5` is a float; `1.field` (dot NOT followed by a digit) stays int + `.` + selector
        if i + 1 < n && text.charAt(i) == '.' && text.charAt(i + 1).isDigit then
          sb.append('.'); advance('.')
          while i < n && text.charAt(i).isDigit do { sb.append(text.charAt(i)); advance(text.charAt(i)) }
          emit("spike.float", start, sb.toString, TokenChannel.Syntax)
        else emit("spike.int", start, sb.toString, TokenChannel.Syntax)
      else if c.isLetter || c == '_' then
        val sb = new StringBuilder
        while i < n && (text.charAt(i).isLetterOrDigit || text.charAt(i) == '_') do { sb.append(text.charAt(i)); advance(text.charAt(i)) }
        val w = sb.toString
        val idKind = if keywords(w) then "spike.kw" else if w.head.isUpper then "spike.uid" else "spike.id"
        emit(idKind, start, w, TokenChannel.Syntax)
      else if c == '/' && i + 1 < n && text.charAt(i + 1) == '/' then
        // line comment → trivia (parser skips it via skipTrivia); lossless, text kept in the token
        val sb = new StringBuilder
        while i < n && text.charAt(i) != '\n' do { sb.append(text.charAt(i)); advance(text.charAt(i)) }
        emit("spike.ws", start, sb.toString, TokenChannel.Trivia)
      else if c == '/' && i + 1 < n && text.charAt(i + 1) == '*' then
        // block comment → trivia (non-nested, matches ssc1-front skipBlockComment)
        val sb = new StringBuilder
        sb.append('/'); advance('/'); sb.append('*'); advance('*')
        while i < n && !(text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/') do
          { sb.append(text.charAt(i)); advance(text.charAt(i)) }
        if i + 1 < n then { sb.append('*'); advance('*'); sb.append('/'); advance('/') }
        emit("spike.ws", start, sb.toString, TokenChannel.Trivia)
      else if isOpChar(c) then
        val sb = new StringBuilder
        while i < n && isOpChar(text.charAt(i)) do { sb.append(text.charAt(i)); advance(text.charAt(i)) }
        val op = sb.toString
        val kind = op match
          case "=" => "spike.eq"
          case ":" => "spike.colon"
          case _   => "spike.op"
        emit(kind, start, op, TokenChannel.Syntax)
      else if c == '"' then
        // string literal → spike.str whose lexeme is the DECODED value (mirrors ssc1-front
        // buildStr: `\n`→NL, `\t`→TAB, `\<c>`→c; triple-quoted is raw). Interpolation prefixes
        // (s/f/md) are a separate slice — a bare string is a plain literal here.
        val sb = new StringBuilder
        if i + 2 < n && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"' then
          advance('"'); advance('"'); advance('"')
          while i < n && !(i + 2 < n && text.charAt(i) == '"' && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"') do
            sb.append(text.charAt(i)); advance(text.charAt(i))
          if i + 2 < n then { advance('"'); advance('"'); advance('"') }
        else
          advance('"')
          while i < n && text.charAt(i) != '"' do
            if text.charAt(i) == '\\' && i + 1 < n then
              val e = text.charAt(i + 1)
              sb.append(if e == 'n' then '\n' else if e == 't' then '\t' else e)
              advance('\\'); advance(e)
            else if text.charAt(i) == '$' && i + 1 < n && text.charAt(i + 1) == '{' then
              // copy a balanced `${ … }` verbatim so its inner quotes don't end the string
              // (matches ssc1-front scanStr → scanInterpEnd); the parts split later, in projection.
              sb.append('$'); advance('$')
              sb.append('{'); advance('{')
              var depth = 1
              while depth > 0 && i < n do
                val ch = text.charAt(i)
                if ch == '{' then depth += 1 else if ch == '}' then depth -= 1
                sb.append(ch); advance(ch)
            else { sb.append(text.charAt(i)); advance(text.charAt(i)) }
          if i < n then advance('"')
        emit("spike.str", start, sb.toString, TokenChannel.Syntax)
      else
        val kind = c match
          case '(' => "spike.lparen"
          case ')' => "spike.rparen"
          case '{' => "spike.lbrace"
          case '}' => "spike.rbrace"
          case ',' => "spike.comma"
          case ';' => "spike.semi"
          case '.' => "spike.dot"
          case '[' => "spike.lbracket"
          case ']' => "spike.rbracket"
          case '@' => "spike.at"
          case _   => "spike.junk"
        advance(c)
        emit(kind, start, c.toString, TokenChannel.Syntax)
    out.result()

extension (n: Node)
  private def withRole(role: String): Node = n match
    case Node.Leaf(t, _)        => Node.Leaf(t, Some(role))
    case Node.Frame(k, _, kids) => Node.Frame(k, Some(role), kids)

// ── parser: total, error-resilient, offside-aware ─────────────────────────────
object SpikeParse:
  final case class Parsed(tree: Node, diagnostics: Vector[Diagnostic])

  // exact mirror of v2/lib/ssc1-front.ssc0 `opPrec` (0 = not an infix op).
  private def opPrec(op: String): Int = op match
    case "~" | "~>"                         => 9
    case "*" | "/" | "%"                    => 8
    case "+" | "-"                          => 7
    case "++" | ":+" | "<<" | ">>" | ">>>"  => 6
    case "==" | "!=" | "<" | "<~" | ">" | "<=" | ">=" | "&" => 5
    case "&&" | "^" | "|"                   => 4
    case "||"                               => 3
    case "!"                                => 2
    case "::"                               => 5 // cons, right-associative (see parseExpr)
    case "->"                               => 1 // pair
    case ":="                               => 1
    case _                                  => 0

  private final class Cur(val toks: Vector[SourceToken]):
    private var p = 0
    private val diags = Vector.newBuilder[Diagnostic]
    def mark: Int = p
    def reset(m: Int): Unit = p = m
    private def skipTrivia(): Unit = while p < toks.length && toks(p).kind == "spike.ws" do p += 1
    def eof: Boolean = { skipTrivia(); p >= toks.length }
    def peek: Option[SourceToken] = { skipTrivia(); if p < toks.length then Some(toks(p)) else None }
    def peekKind: String = peek.map(_.kind).getOrElse("spike.eof")
    def peekLexeme: String = peek.map(_.lexeme).getOrElse("<eof>")
    def peekLine: Int = peek.map(_.span.start.line).getOrElse(-1)
    def peekCol: Int = peek.map(_.span.start.column).getOrElse(-1)
    def peekPrec: Int = if peekKind == "spike.op" then opPrec(peekLexeme) else 0
    def peek2Lexeme: String = // the second significant (non-trivia) token's lexeme
      skipTrivia()
      var q = p + 1
      while q < toks.length && toks(q).kind == "spike.ws" do q += 1
      if q < toks.length then toks(q).lexeme else ""
    def peek2Kind: String = // the second significant (non-trivia) token's kind
      skipTrivia()
      var q = p + 1
      while q < toks.length && toks(q).kind == "spike.ws" do q += 1
      if q < toks.length then toks(q).kind else "spike.eof"
    def advance(): Option[SourceToken] = { skipTrivia(); if p < toks.length then { val t = toks(p); p += 1; Some(t) } else None }
    def skipSemis(): Unit = while peekKind == "spike.semi" do advance()
    def diagnostics: Vector[Diagnostic] = diags.result()
    def report(code: String, msg: String): Unit =
      val span = peek.map(_.span).orElse(toks.lastOption.map(_.span))
      diags += Diagnostic(code, msg, Severity.Error, span, Some("scalascript.spike"))

  private def isDefStart(c: Cur): Boolean = c.peekKind == "spike.kw" && c.peekLexeme == "def"

  // consume a `[ … ]` type-parameter clause (erased). Plain params only — a context bound
  // `[A: TC]` would need the `__tc_TC`-param rewrite (deferred; finicky even in ssc1-front).
  private def skipTypeParams(c: Cur): Unit =
    if c.peekKind == "spike.lbracket" then
      c.advance()
      var depth = 1
      while depth > 0 && !c.eof do
        c.peekKind match
          case "spike.lbracket" => depth += 1; c.advance()
          case "spike.rbracket" => depth -= 1; c.advance()
          case _                => c.advance()
  private def isKw(c: Cur, w: String): Boolean = c.peekKind == "spike.kw" && c.peekLexeme == w

  def parseProgram(toks: Vector[SourceToken]): Parsed =
    val c = new Cur(toks)
    val defs = Vector.newBuilder[Node]
    while !c.eof do
      if isDefStart(c) then defs += parseDef(c)
      else if isKw(c, "case") then defs += parseCaseClass(c)
      else if isKw(c, "given") then defs += parseGiven(c)
      else if isKw(c, "enum") then defs += parseEnum(c)
      else if isKw(c, "extension") then defs += parseExtension(c)
      else
        c.report("spike.unexpected-toplevel", s"unexpected token '${c.peekLexeme}' at top level")
        val skipped = Vector.newBuilder[Node]
        while !c.eof && !isDefStart(c) do c.advance().foreach(t => skipped += Node.Leaf(t, Some("error.skipped")))
        val sk = skipped.result()
        if sk.nonEmpty then defs += Node.Frame("spike.error", None, sk)
    Parsed(Node.Frame("spike.program", None, defs.result()), c.diagnostics)

  private def expect(c: Cur, kind: String, role: String, what: String): Option[Node] =
    if c.peekKind == kind then c.advance().map(t => Node.Leaf(t, Some(role)))
    else { c.report("spike.expected", s"expected $what, found '${c.peekLexeme}'"); None }

  // a type name is an uppercase `uid` (Int, String) or a lowercase type param (`id`).
  private def expectType(c: Cur, role: String): Option[Node] =
    if c.peekKind == "spike.uid" || c.peekKind == "spike.id" then c.advance().map(t => Node.Leaf(t, Some(role)))
    else { c.report("spike.expected", s"expected type, found '${c.peekLexeme}'"); None }

  private def parseDef(c: Cur): Node =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("def.kw"))) // `def`
    expect(c, "spike.id", "def.name", "def name").foreach(kids += _)
    skipTypeParams(c) // plain `[A, B]` are erased (like ssc1-front); context bounds `[A: TC]` deferred
    // the `( … )` param clause is OPTIONAL — `def f: T = e` is a parameterless def.
    if c.peekKind == "spike.lparen" then
      c.advance().foreach(t => kids += Node.Leaf(t, Some("def.lparen")))
      var moreParams = c.peekKind != "spike.rparen" && !c.eof && !isDefStart(c)
      while moreParams do
        val name = expect(c, "spike.id", "def.param", "parameter name")
        name.foreach(kids += _)
        if name.isEmpty then moreParams = false
        else
          expect(c, "spike.colon", "def.paramColon", "':'").foreach(kids += _)
          expectType(c, "def.paramType").foreach(kids += _)
          if c.peekKind == "spike.comma" then c.advance().foreach(t => kids += Node.Leaf(t, Some("def.comma")))
          else moreParams = false
      expect(c, "spike.rparen", "def.rparen", "')'").foreach(kids += _)
    // no `(` → parameterless def; the projection detects it by the absent `def.lparen` child.
    expect(c, "spike.colon", "def.retColon", "':'").foreach(kids += _)
    expectType(c, "def.retType").foreach(kids += _)
    val eqLine = c.peekLine // line of `=` before consuming
    expect(c, "spike.eq", "def.eq", "'='").foreach(kids += _)
    // offside: a body starting on a LATER line is an indented block (Scala optional-braces)
    if !c.eof && c.peekLine > eqLine then kids += parseBlock(c, c.peekCol).withRole("def.body")
    else parseExpr(c, 1) match
      case Some(b) => kids += b.withRole("def.body")
      case None    => c.report("spike.missing-body", "missing def body expression")
    Node.Frame("spike.def", None, kids.result())

  // `case class Name(f1: T1, f2: T2)` — a top-level declaration. lowerProg does all the work
  // (ctor def + Mirror + `_sel_<field>` accessors + `__regfields__`) from the `casecls` AST node.
  private def parseCaseClass(c: Cur): Node =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("cc.case"))) // `case`
    if isKw(c, "class") then c.advance().foreach(t => kids += Node.Leaf(t, Some("cc.class")))
    else c.report("spike.expected", "expected 'class' after 'case'")
    expect(c, "spike.uid", "cc.name", "class name").foreach(kids += _)
    skipTypeParams(c) // `case class Box[A](…)`
    expect(c, "spike.lparen", "cc.lparen", "'('").foreach(kids += _)
    var more = c.peekKind != "spike.rparen" && !c.eof && !isDefStart(c) && !isKw(c, "case")
    while more do
      val fname = expect(c, "spike.id", "cc.field", "field name")
      fname.foreach(kids += _)
      if fname.isEmpty then more = false
      else
        expect(c, "spike.colon", "cc.fieldColon", "':'").foreach(kids += _)
        expectType(c, "cc.fieldType").foreach(kids += _)
        if c.peekKind == "spike.comma" then c.advance().foreach(t => kids += Node.Leaf(t, Some("cc.comma")))
        else more = false
    expect(c, "spike.rparen", "cc.rparen", "')'").foreach(kids += _)
    Node.Frame("spike.casecls", None, kids.result())

  // `enum E: case A; case B(x: Int); case Red, Green` (offside or `{ … }`). Emits
  // ("enum", (name, [(caseName, [fieldNames])…])); lowerProg reuses the case-class ctor path.
  private def parseEnum(c: Cur): Node =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("enum.kw"))) // `enum`
    expect(c, "spike.uid", "enum.name", "enum name").foreach(kids += _)
    skipTypeParams(c) // `enum Opt[A]: …`
    val braced = c.peekKind == "spike.lbrace"
    if c.peekKind == "spike.colon" then c.advance().foreach(t => kids += Node.Leaf(t, Some("enum.colon")))
    else if braced then c.advance().foreach(t => kids += Node.Leaf(t, Some("enum.lbrace")))
    c.skipSemis()
    // a following top-level `case class` (peek2 == "class") is NOT an enum case
    while isKw(c, "case") && c.peek2Lexeme != "class" do
      c.advance().foreach(t => kids += Node.Leaf(t, Some("enum.casekw"))) // `case`
      kids += parseEnumCase(c, allowParams = true)
      while c.peekKind == "spike.comma" do
        c.advance().foreach(t => kids += Node.Leaf(t, Some("enum.comma")))
        kids += parseEnumCase(c, allowParams = false) // comma tail = nullary cases
      c.skipSemis()
    if braced && c.peekKind == "spike.rbrace" then c.advance().foreach(t => kids += Node.Leaf(t, Some("enum.rbrace")))
    Node.Frame("spike.enum", None, kids.result())

  private def parseEnumCase(c: Cur, allowParams: Boolean): Node =
    val kids = Vector.newBuilder[Node]
    expect(c, "spike.uid", "ec.name", "case name").foreach(kids += _)
    if allowParams && c.peekKind == "spike.lparen" then
      c.advance().foreach(t => kids += Node.Leaf(t, Some("ec.lparen")))
      var more = c.peekKind != "spike.rparen" && !c.eof
      while more do
        val fn = expect(c, "spike.id", "ec.field", "field name")
        fn.foreach(kids += _)
        if fn.isEmpty then more = false
        else
          expect(c, "spike.colon", "ec.fieldColon", "':'").foreach(kids += _)
          expectType(c, "ec.fieldType").foreach(kids += _)
          if c.peekKind == "spike.comma" then c.advance().foreach(t => kids += Node.Leaf(t, Some("ec.comma")))
          else more = false
      expect(c, "spike.rparen", "ec.rparen", "')'").foreach(kids += _)
    Node.Frame("spike.enumcase", None, kids.result())

  // `extension (recv: T) def m: R = body` — the receiver is prepended to the method's params
  // (projected) and the group is bracketed by `extension_start`/`extension_end` markers, so
  // lowerProg's collectExtensionMethods registers `m` for `.m` dispatch.
  private def parseExtension(c: Cur): Node =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("ext.kw"))) // `extension`
    expect(c, "spike.lparen", "ext.open", "'('").foreach(kids += _)
    expect(c, "spike.id", "ext.recv", "receiver name").foreach(kids += _)
    expect(c, "spike.colon", "ext.colon", "':'").foreach(kids += _)
    expectType(c, "ext.recvType").foreach(kids += _)
    expect(c, "spike.rparen", "ext.close", "')'").foreach(kids += _)
    // a single inline method def (multi-method offside groups deferred)
    if isDefStart(c) then kids += parseDef(c)
    else c.report("spike.expected", "expected a method def in extension")
    Node.Frame("spike.extension", None, kids.result())

  // `given name: T = expr` — a named typeclass instance (dictionary). lowerProg's resolve pass
  // does the dict-passing; the projection only emits the `("given", (name, typeStr, body))` node.
  private def parseGiven(c: Cur): Node =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("given.kw"))) // `given`
    expect(c, "spike.id", "given.name", "given name").foreach(kids += _)
    expect(c, "spike.colon", "given.colon", "':'").foreach(kids += _)
    expectType(c, "given.type").foreach(kids += _)
    expect(c, "spike.eq", "given.eq", "'='").foreach(kids += _)
    parseExpr(c, 1) match
      case Some(b) => kids += b.withRole("given.body")
      case None    => c.report("spike.missing-given-body", "missing given body")
    Node.Frame("spike.given", None, kids.result())

  // `summon[T]` — resolved to the matching given by lowerProg. A bare `summon` (no `[`) is a var.
  private def parseSummon(c: Cur): Node =
    val id = c.advance().get // `summon`
    if c.peekKind != "spike.lbracket" then Node.Leaf(id, Some("var"))
    else
      val kids = Vector.newBuilder[Node]
      kids += Node.Leaf(id, Some("summon.kw"))
      c.advance().foreach(t => kids += Node.Leaf(t, Some("summon.open"))) // `[`
      expectType(c, "summon.type").foreach(kids += _)
      if c.peekKind == "spike.rbracket" then c.advance().foreach(t => kids += Node.Leaf(t, Some("summon.close")))
      else c.report("spike.expected", "expected ']' after summon type")
      Node.Frame("spike.summon", None, kids.result())

  // an indented block: statements at column >= blockCol; a dedent (col < blockCol),
  // EOF, or a top-level `def` ends it. parseStmt always consumes ≥1 token (progress).
  private def parseBlock(c: Cur, blockCol: Int): Node =
    val stmts = Vector.newBuilder[Node]
    while !c.eof && c.peekCol >= blockCol && !isDefStart(c) do stmts += parseStmt(c)
    Node.Frame("spike.block", None, stmts.result())

  private def parseStmt(c: Cur): Node =
    if isKw(c, "val") then parseVal(c)
    else parseExpr(c, 1) match
      case Some(e) => Node.Frame("spike.exprStmt", None, Vector(e.withRole("stmt.expr")))
      case None =>
        c.report("spike.expected", s"expected statement, found '${c.peekLexeme}'")
        c.advance() match
          case Some(t) => Node.Frame("spike.error", None, Vector(Node.Leaf(t, Some("error.token"))))
          case None    => Node.Frame("spike.error", None, Vector.empty)

  private def parseVal(c: Cur): Node =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("val.kw"))) // `val`
    expect(c, "spike.id", "val.name", "val name").foreach(kids += _)
    expect(c, "spike.eq", "val.eq", "'='").foreach(kids += _)
    parseExpr(c, 1) match
      case Some(e) => kids += e.withRole("val.rhs")
      case None    => c.report("spike.missing-rhs", "missing val right-hand side")
    Node.Frame("spike.val", None, kids.result())

  // postfix layer: an atom followed by chained `.field` selections and/or `match { … }`
  // (mirroring ssc1-front's buildPostfix so precedence vs. infix ops matches exactly).
  private def parsePostfix(c: Cur): Option[Node] =
    parseAtom(c).map(atom => postfix(c, atom))

  private def postfix(c: Cur, atom: Node): Node =
    if c.peekKind == "spike.dot" then
      val kids = Vector.newBuilder[Node]
      kids += atom.withRole("sel.obj")
      c.advance().foreach(t => kids += Node.Leaf(t, Some("sel.dot"))) // `.`
      c.advance() match
        case Some(f) => kids += Node.Leaf(f, Some("sel.field"))
        case None    => c.report("spike.expected", "expected field name after '.'")
      postfix(c, Node.Frame("spike.sel", None, kids.result()))
    else if isKw(c, "match") then parseMatch(c, atom)
    else atom

  private def parseMatch(c: Cur, scrut: Node): Node =
    val kids = Vector.newBuilder[Node]
    kids += scrut.withRole("match.scrut")
    c.advance().foreach(t => kids += Node.Leaf(t, Some("match.kw"))) // `match`
    val braced = c.peekKind == "spike.lbrace"
    if braced then c.advance().foreach(t => kids += Node.Leaf(t, Some("match.open")))
    c.skipSemis()
    while isKw(c, "case") do { kids += parseArm(c); c.skipSemis() }
    if braced && c.peekKind == "spike.rbrace" then c.advance().foreach(t => kids += Node.Leaf(t, Some("match.close")))
    Node.Frame("spike.match", None, kids.result())

  private def parseArm(c: Cur): Node =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("case.kw"))) // `case`
    kids += parseArmPattern(c).withRole("case.pat")
    if isKw(c, "if") then
      c.advance().foreach(t => kids += Node.Leaf(t, Some("case.ifkw")))
      parseExpr(c, 1).foreach(g => kids += g.withRole("case.guard"))
    if c.peekKind == "spike.op" && c.peekLexeme == "=>" then c.advance().foreach(t => kids += Node.Leaf(t, Some("case.arrow")))
    else c.report("spike.expected", "expected '=>' in case arm")
    parseExpr(c, 1) match
      case Some(b) => kids += b.withRole("case.body")
      case None    => c.report("spike.missing-case-body", "missing case body")
    Node.Frame("spike.arm", None, kids.result())

  // a full arm pattern: `alias @ PAT` (bind, bpat) around `PAT | PAT | …` (alternatives, apat).
  private def parseArmPattern(c: Cur): Node =
    val bindAlias =
      if c.peekKind == "spike.id" && c.peekLexeme != "_" && c.peek2Lexeme == "@" then
        val a = c.advance().get; c.advance(); Some(a) // consume name + `@`
      else None
    val first = parsePattern(c)
    val alts = Vector.newBuilder[Node]
    alts += first
    while c.peekKind == "spike.op" && c.peekLexeme == "|" do
      c.advance() // `|`
      alts += parsePattern(c)
    val altList = alts.result()
    val base =
      if altList.length > 1 then Node.Frame("spike.apat", None, altList.map(_.withRole("apat.alt")))
      else first
    // type ascription `p: T` → tpat
    val typed =
      if c.peekKind == "spike.colon" then
        c.advance() // `:`
        val tk = Vector.newBuilder[Node]
        tk += base.withRole("tpat.pat")
        expectType(c, "tpat.type").foreach(tk += _)
        Node.Frame("spike.tpat", None, tk.result())
      else base
    bindAlias match
      case Some(a) => Node.Frame("spike.bpat", None, Vector(Node.Leaf(a, Some("bpat.alias")), typed.withRole("bpat.inner")))
      case None    => typed

  // patterns: int literal (lpat) / `_` (wpat) / lowercase binder (vpat) / ctor `Name(subpats)`
  // (cpat) / tuple `(a, b)` (→ cpat "Pair"/"TupleN"). Recursive for sub-patterns.
  private def parsePattern(c: Cur): Node =
    c.peekKind match
      case "spike.int" => c.advance().map(t => Node.Leaf(t, Some("pat.lit"))).get
      case "spike.id" if c.peekLexeme == "_" => c.advance().map(t => Node.Leaf(t, Some("pat.wild"))).get
      case "spike.id"  => c.advance().map(t => Node.Leaf(t, Some("pat.var"))).get
      case "spike.uid" => parseCtorPat(c)
      case "spike.lparen" => parseTuplePat(c)
      case _ =>
        c.report("spike.bad-pattern", s"unsupported pattern '${c.peekLexeme}'")
        c.advance().map(t => Node.Frame("spike.error", None, Vector(Node.Leaf(t, Some("error.token")))))
          .getOrElse(Node.Frame("spike.error", None, Vector.empty))

  private def parseCtorPat(c: Cur): Node =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("cpat.name"))) // uid ctor name
    if c.peekKind == "spike.lparen" then
      c.advance().foreach(t => kids += Node.Leaf(t, Some("cpat.open")))
      while c.peekKind != "spike.rparen" && !c.eof && !isKw(c, "case") do
        kids += parsePattern(c).withRole("cpat.arg")
        if c.peekKind == "spike.comma" then c.advance().foreach(t => kids += Node.Leaf(t, Some("cpat.comma")))
      if c.peekKind == "spike.rparen" then c.advance().foreach(t => kids += Node.Leaf(t, Some("cpat.close")))
      else c.report("spike.expected", "expected ')' in constructor pattern")
    Node.Frame("spike.cpat", None, kids.result())

  private def parseTuplePat(c: Cur): Node =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("tup.open")))
    while c.peekKind != "spike.rparen" && !c.eof && !isKw(c, "case") do
      kids += parsePattern(c).withRole("tup.arg")
      if c.peekKind == "spike.comma" then c.advance().foreach(t => kids += Node.Leaf(t, Some("tup.comma")))
    if c.peekKind == "spike.rparen" then c.advance().foreach(t => kids += Node.Leaf(t, Some("tup.close")))
    else c.report("spike.expected", "expected ')' in tuple pattern")
    Node.Frame("spike.tuppat", None, kids.result())

  // `x => body` and `(x, y) => body` are lambdas; they bind loosest, so only at the outer level
  // (minPrec ≤ 1), mirroring ssc1-front parseExprCore. `(…)` may instead be a paren/tuple, so the
  // paren form is tried with backtracking (mark/reset).
  private def tryParseLambda(c: Cur): Option[Node] =
    if c.peekKind == "spike.id" && c.peek2Lexeme == "=>" then
      val name = c.advance().get
      c.advance() // =>
      val body = parseExpr(c, 1).getOrElse(Node.Frame("spike.error", None, Vector.empty))
      Some(Node.Frame("spike.lambda", None, Vector(Node.Leaf(name, Some("lam.param")), body.withRole("lam.body"))))
    else if c.peekKind == "spike.lparen" then
      val m = c.mark
      tryLambdaParams(c) match
        case Some(ps) if c.peekLexeme == "=>" =>
          c.advance() // =>
          val body = parseExpr(c, 1).getOrElse(Node.Frame("spike.error", None, Vector.empty))
          Some(Node.Frame("spike.lambda", None, ps.map(p => Node.Leaf(p, Some("lam.param"))) :+ body.withRole("lam.body")))
        case _ => c.reset(m); None
    else None

  // scan `( id [: T] (, id [: T])* )` — the shape of a lambda parameter clause. Returns the param
  // tokens on success (cursor left just after `)`), else None (caller resets).
  private def tryLambdaParams(c: Cur): Option[Vector[SourceToken]] =
    c.advance() // (
    val params = Vector.newBuilder[SourceToken]
    var ok = true
    var first = true
    while ok && c.peekKind != "spike.rparen" && !c.eof do
      if !first then (if c.peekKind == "spike.comma" then c.advance() else ok = false)
      if ok && c.peekKind == "spike.id" then
        params += c.advance().get
        if c.peekKind == "spike.colon" then { c.advance(); skipTypeRef(c) }
      else ok = false
      first = false
    if ok && c.peekKind == "spike.rparen" then { c.advance(); Some(params.result()) } else None

  // skip a simple type reference after `:` in a lambda param (`Int`, `List[Int]`, `A => B`).
  private def skipTypeRef(c: Cur): Unit =
    if c.peekKind == "spike.uid" || c.peekKind == "spike.id" then c.advance()
    if c.peekKind == "spike.lbracket" then skipTypeParams(c)

  private def parseExpr(c: Cur, minPrec: Int): Option[Node] =
    val lam = if minPrec <= 1 then tryParseLambda(c) else None
    if lam.isDefined then lam else parseInfixExpr(c, minPrec)

  private def parseInfixExpr(c: Cur, minPrec: Int): Option[Node] =
    parsePostfix(c) match
      case None => None
      case Some(first) =>
        var left = first
        var more = true
        while more do
          val p = c.peekPrec
          // `to`/`until` are id-infix range words that bind loosest (only at the outer level)
          val isRange = minPrec <= 1 && c.peekKind == "spike.id" && (c.peekLexeme == "to" || c.peekLexeme == "until")
          if p >= minPrec && p > 0 then
            val op = c.advance().get // spike.op
            val rightMin = if op.lexeme == "::" then p else p + 1 // `::` is right-associative
            val kids = parseExpr(c, rightMin) match
              case Some(r) => Vector(left.withRole("bin.left"), Node.Leaf(op, Some("bin.op")), r.withRole("bin.right"))
              case None =>
                c.report("spike.missing-operand", s"missing right operand after '${op.lexeme}'")
                Vector(left.withRole("bin.left"), Node.Leaf(op, Some("bin.op")))
            left = Node.Frame("spike.infix", None, kids)
          else if isRange then
            val word = c.advance().get
            val rhs = parsePostfix(c).getOrElse(Node.Frame("spike.error", None, Vector.empty))
            left = Node.Frame("spike.rangeop", None,
              Vector(left.withRole("range.lhs"), Node.Leaf(word, Some("range.op")), rhs.withRole("range.rhs")))
          else more = false
        Some(left)

  private def parseAtom(c: Cur): Option[Node] =
    c.peekKind match
      case "spike.int"    => c.advance().map(t => Node.Leaf(t, Some("int")))
      case "spike.float"  => c.advance().map(t => Node.Leaf(t, Some("float")))
      case "spike.str"    => c.advance().map(t => Node.Leaf(t, Some("str")))
      case "spike.lparen" => parseParen(c)
      case "spike.kw" if c.peekLexeme == "if" => parseIf(c)
      case "spike.op" if c.peekLexeme == "-" || c.peekLexeme == "!" || c.peekLexeme == "~" => Some(parsePrefix(c))
      case "spike.id" if c.peekLexeme == "summon" => Some(parseSummon(c))
      // interpolator: an `s`/`f`/`raw`/`md` prefix immediately before a string token (ssc1-front
      // detects interpolation the same way — id value + following str, no adjacency check).
      case "spike.id" if isInterpPrefix(c.peekLexeme) && c.peek2Kind == "spike.str" => Some(parseInterp(c))
      case "spike.id" | "spike.uid" => parseIdOrCall(c) // uid = uppercase ctor/type ref → mkUVar
      case "spike.junk" =>
        c.report("spike.unexpected-expr", s"unexpected token '${c.peekLexeme}' in expression")
        c.advance().map(t => Node.Frame("spike.error", None, Vector(Node.Leaf(t, Some("error.token")))))
      case _ => None // eof / kw boundary / operator / `)` / `=` / `:` / `,` — not an atom

  private def isInterpPrefix(w: String): Boolean = w == "s" || w == "f" || w == "raw" || w == "md"

  // `s"a $x b ${e}"` → spike.interp holding the prefix + the (decoded) string token. The parts
  // split + concatenation happen in the projection (mirroring ssc1-front interpParts/partsToExpr).
  private def parseInterp(c: Cur): Node =
    val pfx = c.advance().get
    val str = c.advance().get
    Node.Frame("spike.interp", None, Vector(Node.Leaf(pfx, Some("interp.prefix")), Node.Leaf(str, Some("interp.raw"))))

  // `( e )` is grouping (spike.paren); `( a, b, … )` and `()` are tuple literals (spike.tuple).
  // prefix operator `- e` / `! e` / `~ e` → mkPre(op, e). Binds at the atom level.
  private def parsePrefix(c: Cur): Node =
    val op = c.advance().get
    val sub = parseAtom(c).getOrElse(Node.Frame("spike.error", None, Vector.empty))
    Node.Frame("spike.pre", None, Vector(Node.Leaf(op, Some("pre.op")), sub.withRole("pre.sub")))

  private def parseParen(c: Cur): Option[Node] =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("group.open")))
    var elems = 0
    var isTuple = false
    if c.peekKind != "spike.rparen" then
      parseExpr(c, 1).foreach { e => kids += e.withRole("group.elem"); elems += 1 }
      while c.peekKind == "spike.comma" do
        isTuple = true
        c.advance().foreach(t => kids += Node.Leaf(t, Some("group.comma")))
        parseExpr(c, 1).foreach { e => kids += e.withRole("group.elem"); elems += 1 }
    if c.peekKind == "spike.rparen" then c.advance().foreach(t => kids += Node.Leaf(t, Some("group.close")))
    else c.report("spike.expected", "expected ')'")
    Some(Node.Frame(if isTuple || elems == 0 then "spike.tuple" else "spike.paren", None, kids.result()))

  private def parseIf(c: Cur): Option[Node] =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("if.kw")))
    parseExpr(c, 1).foreach(e => kids += e.withRole("if.cond"))
    if isKw(c, "then") then c.advance().foreach(t => kids += Node.Leaf(t, Some("if.then")))
    else c.report("spike.expected", "expected 'then'")
    parseExpr(c, 1).foreach(e => kids += e.withRole("if.thenE"))
    if isKw(c, "else") then c.advance().foreach(t => kids += Node.Leaf(t, Some("if.else")))
    else c.report("spike.expected", "expected 'else'")
    parseExpr(c, 1).foreach(e => kids += e.withRole("if.elseE"))
    Some(Node.Frame("spike.if", None, kids.result()))

  private def parseIdOrCall(c: Cur): Option[Node] =
    val id = c.advance().get
    if c.peekKind != "spike.lparen" then Some(Node.Leaf(id, Some("var")))
    else
      val kids = Vector.newBuilder[Node]
      kids += Node.Leaf(id, Some("call.fn"))
      c.advance().foreach(t => kids += Node.Leaf(t, Some("call.open")))
      while c.peekKind != "spike.rparen" && !c.eof && !isDefStart(c) do
        parseExpr(c, 1) match
          case Some(a) => kids += a.withRole("call.arg")
          case None =>
            c.report("spike.expected", "expected call argument")
            if c.peekKind != "spike.rparen" && !c.eof then c.advance()
        if c.peekKind == "spike.comma" then c.advance().foreach(t => kids += Node.Leaf(t, Some("call.comma")))
      if c.peekKind == "spike.rparen" then c.advance().foreach(t => kids += Node.Leaf(t, Some("call.close")))
      else c.report("spike.expected", "expected ')' to close call")
      Some(Node.Frame("spike.call", None, kids.result()))

// ── serialise the Node tree → VmTokens (open on first token, closeAfter on last) ──
object SpikeEmit:
  private final case class Ev(tok: SourceToken, opens: Vector[FrameSpec], closes: Vector[String], role: Option[String])

  private def walk(n: Node): Vector[Ev] = n match
    case Node.Leaf(t, role) => Vector(Ev(t, Vector.empty, Vector.empty, role))
    case Node.Frame(kind, role, kids) =>
      val evs = kids.flatMap(walk)
      if evs.isEmpty then Vector.empty
      else
        val head = evs.head
        val opened = FrameSpec(kind, role)
        val withOpen = evs.updated(0, head.copy(opens = opened +: head.opens))
        val li = withOpen.length - 1
        withOpen.updated(li, withOpen(li).copy(closes = withOpen(li).closes :+ kind))

  def emit(root: Node): Vector[VmToken] =
    walk(root).map { ev =>
      val instr =
        if ev.opens.isEmpty && ev.closes.isEmpty then VmInstruction.Emit(ev.role)
        else VmInstruction.Reframe(open = ev.opens, closeAfter = ev.closes, role = ev.role)
      VmToken(ev.tok, instr)
    }

// ── the dialect ───────────────────────────────────────────────────────────────
object SpikeDialect extends DialectAdapter:
  def id: String = "scalascript.spike"

  override val aliases: Set[String] = Set("scalascript", "scala", "ssc")

  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    new Processor[String, SourceChunk, VmToken]:
      def start: String = ""
      def step(state: String, input: SourceChunk): Stepped[String, VmToken] =
        Stepped(state + input.text, ProcessBatch.empty)
      def stop(state: String): ProcessBatch[VmToken] =
        val toks = SpikeLex.scan(source.source, state)
        val parsed = SpikeParse.parseProgram(toks)
        ProcessBatch(SpikeEmit.emit(parsed.tree), parsed.diagnostics)

// ── projection: UniML CST → ssc-v2 `Pair(tag,data)` AST as ssc0 source text ────
// TOTAL: any error / missing subtree becomes a `__notImplemented__` hole.
object SpikeProject:
  private val hole = """Pair("prim", Pair("__notImplemented__", Nil))"""

  private def esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

  /** escape a decoded string VALUE back into an ssc0 string literal that round-trips to it
    * (ssc0 buildStr decodes `\n`/`\t`; `\`/`"` are escaped by `esc`). */
  private def escStr(s: String): String = esc(s).replace("\n", "\\n").replace("\t", "\\t")

  // ── string interpolation (mirrors ssc1-front interpParts / partsToExpr, KC12) ──────────────────
  private def isAlpha(c: Char): Boolean = c.isLetter || c == '_'
  private def isAlphaNum(c: Char): Boolean = c.isLetterOrDigit || c == '_'

  /** skip a nested `"…"` inside a `${…}` body; returns the index just after the closing quote. */
  private def scanNestedStr(s: String, i0: Int): Int =
    var i = i0
    val n = s.length
    var res = -1
    while res < 0 && i < n do
      if s.charAt(i) == '"' then res = i + 1
      else if s.charAt(i) == '\\' then i += 2
      else i += 1
    if res >= 0 then res else i

  /** index just after the `}` that matches a `${` opened at depth `depth0`; balances nested
    * braces and string literals; malformed input returns EOF. */
  private def scanInterpEnd(s: String, i0: Int, depth0: Int): Int =
    var i = i0
    var depth = depth0
    val n = s.length
    var res = -1
    while res < 0 && i < n do
      s.charAt(i) match
        case '"' => i = scanNestedStr(s, i + 1)
        case '{' => depth += 1; i += 1
        case '}' => if depth == 1 then res = i + 1 else { depth -= 1; i += 1 }
        case _   => i += 1
    if res >= 0 then res else i

  /** split a decoded interpolated string into ("str",lit) | ("var",name) | ("expr",src) parts. */
  private def interpParts(raw: String): Vector[(String, String)] =
    val n = raw.length
    var out = Vector.empty[(String, String)]
    var i = 0
    var litStart = 0
    def flush(end: Int): Unit = if end > litStart then out = out :+ (("str", raw.substring(litStart, end)))
    while i < n do
      if raw.charAt(i) == '$' && i + 1 < n then
        val c2 = raw.charAt(i + 1)
        if c2 == '{' then
          val iAfter  = scanInterpEnd(raw, i + 2, 1)
          val exprEnd = if iAfter > i + 2 && iAfter <= n && raw.charAt(iAfter - 1) == '}' then iAfter - 1 else n
          flush(i)
          out = out :+ (("expr", raw.substring(i + 2, exprEnd)))
          i = iAfter; litStart = iAfter
        else if isAlpha(c2) then
          flush(i)
          var endId = i + 1
          while endId < n && isAlphaNum(raw.charAt(endId)) do endId += 1
          out = out :+ (("var", raw.substring(i + 1, endId)))
          i = endId; litStart = endId
        else i += 1 // a literal `$`
      else i += 1
    flush(n)
    out

  /** fold parts into the right-associative `++` concatenation partsToExpr builds. */
  private def partsToExpr(parts: Vector[(String, String)]): String =
    if parts.isEmpty then """mkStr("")"""
    else
      val (tag, v) = parts.head
      val pe = tag match
        case "str"  => s"""mkStr("${escStr(v)}")"""
        case "expr" => exprOfSource(v)
        case _      => s"""mkVar("${esc(v)}")"""
      if parts.tail.isEmpty then pe else s"""mkInf("++", $pe, ${partsToExpr(parts.tail)})"""

  private def sInterp(raw: String): String = partsToExpr(interpParts(raw))

  // ── f-interpolation: printf specs → __fInterpolate__ (mirrors buildFInterp/goFArgs) ────────────
  private def isFmtFlag(c: Char): Boolean = "-#+ 0,(<".indexOf(c.toInt) >= 0
  private def isDigitC(c: Char): Boolean = c >= '0' && c <= '9'

  /** peel a leading printf spec `%[flags][width][.prec]<letter>` off `part`; default `"%s"`. */
  private def splitFFormatPrefix(part: String): (String, String) =
    val len = part.length
    if len == 0 || part.charAt(0) != '%' then ("%s", part)
    else
      var i = 1
      while i < len && isFmtFlag(part.charAt(i)) do i += 1
      while i < len && isDigitC(part.charAt(i)) do i += 1
      if i < len && part.charAt(i) == '.' then { i += 1; while i < len && isDigitC(part.charAt(i)) do i += 1 }
      if i < len && part.charAt(i).isLetter then (part.substring(0, i + 1), part.substring(i + 1, len))
      else ("%s", part)

  private def fArgExpr(part: (String, String)): String =
    if part._1 == "expr" then exprOfSource(part._2) else s"""mkVar("${esc(part._2)}")"""

  /** interleave [spec, arg, restLiteral] triples across the arg parts (goFArgs). */
  private def goFArgs(parts: Vector[(String, String)]): Vector[String] =
    if parts.isEmpty then Vector.empty
    else
      val ae = fArgExpr(parts.head)
      val rest = parts.tail
      if rest.isEmpty then Vector(s"""mkStr("%s")""", ae, """mkStr("")""")
      else if rest.head._1 == "str" then
        val (spec, r) = splitFFormatPrefix(rest.head._2)
        Vector(s"""mkStr("${escStr(spec)}")""", ae, s"""mkStr("${escStr(r)}")""") ++ goFArgs(rest.tail)
      else Vector(s"""mkStr("%s")""", ae, """mkStr("")""") ++ goFArgs(rest)

  private def fInterp(raw: String): String =
    val parts = interpParts(raw)
    if parts.isEmpty then """mkStr("")"""
    else
      val p0 = parts.head
      val args =
        if p0._1 == "str" then Vector(s"""mkStr("${escStr(p0._2)}")""") ++ goFArgs(parts.tail)
        else """mkStr("")""" +: goFArgs(parts)
      s"""mkApp(mkVar("__fInterpolate__"), ${consList(args)})"""

  /** re-parse an inner `${…}` expression with the spike's own front and project it. Wrapping it
    * as a parameterless def yields a program the dialect parses; then lift the def body. */
  private def exprOfSource(src: String): String =
    val pr = UniML.parse(SourceInput.fromString(SourceId("interp:expr"), s"def __e__ = $src"), SpikeDialect)
    val body = for
      prog <- pr.roots.headOption
      defn <- kids(prog).collectFirst { case (_, c) if kindOf(c) == "spike.def" => c }
      b    <- kids(defn).collectFirst { case (Some("def.body"), c) => c }
    yield expr(b)
    body.getOrElse(hole)

  private def lexeme(n: UniNode): String = n match
    case UniNode.Token(t) => t.lexeme
    case _                => ""
  private def kindOf(n: UniNode): String = n match
    case b: UniNode.Branch => b.kind
    case UniNode.Token(_)  => "token"

  private def kids(n: UniNode): Vector[(Option[String], UniNode)] = n match
    case b: UniNode.Branch =>
      b.edges.collect {
        case UniEdge(role, c) if !(c.isInstanceOf[UniNode.Token] && c.asInstanceOf[UniNode.Token].value.kind == "spike.ws") =>
          (role, c)
      }
    case _ => Vector.empty

  def program(root: UniNode): String =
    consList(kids(root).flatMap {
      case (_, c) if kindOf(c) == "spike.def"       => Vector(defNode(c))
      case (_, c) if kindOf(c) == "spike.casecls"   => Vector(caseClsNode(c))
      case (_, c) if kindOf(c) == "spike.given"     => Vector(givenNode(c))
      case (_, c) if kindOf(c) == "spike.enum"      => Vector(enumNode(c))
      case (_, c) if kindOf(c) == "spike.extension" => extensionNodes(c)
      case _                                        => Vector.empty[String]
    }.toVector)

  // an extension group → three statements: extension_start, the method def (receiver prepended
  // to its params), extension_end. lowerProg's collectExtensionMethods registers it for `.m`.
  private def extensionNodes(n: UniNode): Vector[String] =
    val recv = kids(n).collectFirst { case (Some("ext.recv"), c) => lexeme(c) }.getOrElse("_")
    val defs = kids(n).collect { case (_, c) if kindOf(c) == "spike.def" => defNode(c, Vector("\"" + esc(recv) + "\"")) }
    (Vector("""Pair("extension_start", "")""") ++ defs ++ Vector("""Pair("extension_end", "")""")).toVector

  // Pair("enum", Pair(name, [Pair(caseName, [fieldNames])…])) — lowerProg makes each case a ctor.
  private def enumNode(n: UniNode): String =
    val ks = kids(n)
    val name = ks.collectFirst { case (Some("enum.name"), c) => lexeme(c) }.getOrElse("_")
    val cases = ks.collect { case (_, c) if kindOf(c) == "spike.enumcase" => enumCase(c.asInstanceOf[UniNode.Branch]) }
    s"""Pair("enum", Pair("${esc(name)}", ${consList(cases)}))"""

  private def enumCase(b: UniNode.Branch): String =
    val cname = kids(b).collectFirst { case (Some("ec.name"), c) => lexeme(c) }.getOrElse("_")
    val fields = kids(b).collect { case (Some("ec.field"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector
    s"""Pair("${esc(cname)}", ${consList(fields)})"""

  // Pair("given", Pair(name, Pair(typeStr, body))) — lowerProg builds the given table + IrDef.
  private def givenNode(n: UniNode): String =
    val ks = kids(n)
    val name = ks.collectFirst { case (Some("given.name"), c) => lexeme(c) }.getOrElse("_")
    val ty   = ks.collectFirst { case (Some("given.type"), c) => lexeme(c) }.getOrElse("_")
    val body = ks.collectFirst { case (Some("given.body"), c) => expr(c) }.getOrElse(hole)
    s"""Pair("given", Pair("${esc(name)}", Pair("${esc(ty)}", $body)))"""

  // Pair("casecls", Pair(name, Pair(fieldNames, Pair(fieldTypes, derives)))) via mkCaseCls;
  // lowerProg generates the ctor def, Mirror, `_sel_<field>` accessors and `__regfields__`.
  private def caseClsNode(n: UniNode): String =
    val ks = kids(n)
    val name  = ks.collectFirst { case (Some("cc.name"), c) => lexeme(c) }.getOrElse("_")
    val names = ks.collect { case (Some("cc.field"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector
    val types = ks.collect { case (Some("cc.fieldType"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector
    s"""mkCaseCls("${esc(name)}", ${consList(names)}, ${consList(types)}, Nil)"""

  private def consList(xs: Vector[String]): String =
    xs.foldRight("Nil")((h, acc) => s"Cons($h, $acc)")

  private def defNode(n: UniNode, prefixParams: Vector[String] = Vector.empty): String =
    val ks = kids(n)
    val name = ks.collectFirst { case (Some("def.name"), c) => lexeme(c) }.getOrElse("main")
    val params = prefixParams ++ ks.collect { case (Some("def.param"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector
    val body = ks.collectFirst { case (role, c) if role.contains("def.body") => expr(c) }.getOrElse(hole)
    s"""mkDef("${esc(name)}", ${consList(params)}, $body)"""

  private def expr(n: UniNode): String = n match
    case UniNode.Token(t) if t.kind == "spike.int"   => s"""mkInt("${esc(t.lexeme)}")"""
    case UniNode.Token(t) if t.kind == "spike.float" => s"""mkFloat("${esc(t.lexeme)}")"""
    case UniNode.Token(t) if t.kind == "spike.str"   => s"""mkStr("${escStr(t.lexeme)}")"""
    // `true`/`false` are literal booleans, not variables (ssc1-front does the same)
    case UniNode.Token(t) if t.kind == "spike.id" && (t.lexeme == "true" || t.lexeme == "false") => s"""mkBool("${t.lexeme}")"""
    case UniNode.Token(t) if t.kind == "spike.id"    => s"""mkVar("${esc(t.lexeme)}")"""
    case UniNode.Token(t) if t.kind == "spike.uid"   => s"""mkUVar("${esc(t.lexeme)}")"""
    case b: UniNode.Branch => b.kind match
      case "spike.infix" => infix(b)
      case "spike.paren" => kids(b).collectFirst { case (Some("group.elem"), c) => expr(c) }.getOrElse(hole)
      case "spike.tuple" => s"""mkTup(${consList(kids(b).collect { case (Some("group.elem"), c) => expr(c) }.toVector)})"""
      case "spike.call"  => call(b)
      case "spike.if"    => ifExpr(b)
      case "spike.block" => block(b)
      case "spike.match" => matchExpr(b)
      case "spike.sel"   => sel(b)
      case "spike.summon" =>
        s"""Pair("summon", "${esc(kids(b).collectFirst { case (Some("summon.type"), c) => lexeme(c) }.getOrElse("_"))}")"""
      case "spike.pre" =>
        val op  = kids(b).collectFirst { case (Some("pre.op"), c) => lexeme(c) }.getOrElse("-")
        val sub = kids(b).collectFirst { case (Some("pre.sub"), c) => expr(c) }.getOrElse(hole)
        s"""mkPre("${esc(op)}", $sub)"""
      case "spike.lambda" =>
        val ps   = kids(b).collect { case (Some("lam.param"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector
        val body = kids(b).collectFirst { case (Some("lam.body"), c) => expr(c) }.getOrElse(hole)
        s"""mkLam(${consList(ps)}, $body)"""
      case "spike.interp" =>
        val pfx = kids(b).collectFirst { case (Some("interp.prefix"), c) => lexeme(c) }.getOrElse("s")
        val raw = kids(b).collectFirst { case (Some("interp.raw"), c) => lexeme(c) }.getOrElse("")
        pfx match
          case "md" => s"""Pair("prim", Pair("__mdStrip__", Cons(${sInterp(raw)}, Nil)))"""
          case "f"  => fInterp(raw) // printf format specifiers → __fInterpolate__
          case _    => sInterp(raw) // s / raw
      case "spike.rangeop" =>
        val word = kids(b).collectFirst { case (Some("range.op"), c) => lexeme(c) }.getOrElse("to")
        val lhs  = kids(b).collectFirst { case (Some("range.lhs"), c) => expr(c) }.getOrElse(hole)
        val rhs  = kids(b).collectFirst { case (Some("range.rhs"), c) => expr(c) }.getOrElse(hole)
        s"""mkApp(mkSel($lhs, "${esc(word)}"), ${consList(Vector(rhs))})"""
      case "spike.error" => hole
      case _             => hole
    case _ => hole

  private def sel(b: UniNode.Branch): String =
    val obj = kids(b).collectFirst { case (Some("sel.obj"), c) => expr(c) }.getOrElse(hole)
    val field = kids(b).collectFirst { case (Some("sel.field"), c) => lexeme(c) }.getOrElse("_")
    s"""mkSel($obj, "${esc(field)}")"""

  // Pair("match", Pair(scrut, [arm…])); arm = Pair(pattern, body); guard → Pair("gpat", …).
  private def matchExpr(b: UniNode.Branch): String =
    val scrut = kids(b).collectFirst { case (Some("match.scrut"), c) => expr(c) }.getOrElse(hole)
    val arms  = kids(b).collect { case (_, c) if kindOf(c) == "spike.arm" => arm(c.asInstanceOf[UniNode.Branch]) }
    s"""mkMatch($scrut, ${consList(arms)})"""

  private def arm(b: UniNode.Branch): String =
    val pat  = kids(b).collectFirst { case (Some("case.pat"), c) => patProj(c) }.getOrElse("""Pair("wpat", "")""")
    val body = kids(b).collectFirst { case (Some("case.body"), c) => expr(c) }.getOrElse(hole)
    val guarded = kids(b).collectFirst { case (Some("case.guard"), c) => expr(c) } match
      case Some(g) => s"""Pair("gpat", Pair($pat, $g))"""
      case None    => pat
    s"""Pair($guarded, $body)"""

  private def patProj(n: UniNode): String = n match
    case UniNode.Token(t) if t.kind == "spike.int"                    => s"""Pair("lpat", Pair("int", "${esc(t.lexeme)}"))"""
    case UniNode.Token(t) if t.kind == "spike.id" && t.lexeme == "_"  => """Pair("wpat", "")"""
    case UniNode.Token(t) if t.kind == "spike.id"                     => s"""Pair("vpat", "${esc(t.lexeme)}")"""
    case b: UniNode.Branch if b.kind == "spike.cpat"                  => cpatProj(b)
    case b: UniNode.Branch if b.kind == "spike.tuppat"                => tuppatProj(b)
    case b: UniNode.Branch if b.kind == "spike.apat"                  =>
      s"""Pair("apat", ${consList(kids(b).collect { case (Some("apat.alt"), c) => patProj(c) }.toVector)})"""
    case b: UniNode.Branch if b.kind == "spike.bpat"                  =>
      val alias = kids(b).collectFirst { case (Some("bpat.alias"), c) => lexeme(c) }.getOrElse("_")
      val inner = kids(b).collectFirst { case (Some("bpat.inner"), c) => patProj(c) }.getOrElse("""Pair("wpat", "")""")
      s"""Pair("bpat", Pair("${esc(alias)}", $inner))"""
    case b: UniNode.Branch if b.kind == "spike.tpat"                  =>
      val pat = kids(b).collectFirst { case (Some("tpat.pat"), c) => patProj(c) }.getOrElse("""Pair("wpat", "")""")
      val ty  = kids(b).collectFirst { case (Some("tpat.type"), c) => lexeme(c) }.getOrElse("_")
      s"""Pair("tpat", Pair($pat, "${esc(ty)}"))"""
    case _ => """Pair("wpat", "")"""

  // ctor pattern → Pair("cpat", Pair(name, [subpats])); mirrors ssc1-front finishCtorPat.
  private def cpatProj(b: UniNode.Branch): String =
    val name = kids(b).collectFirst { case (Some("cpat.name"), c) => lexeme(c) }.getOrElse("_")
    val subs = kids(b).collect { case (Some("cpat.arg"), c) => patProj(c) }.toVector
    s"""Pair("cpat", Pair("${esc(name)}", ${consList(subs)}))"""

  // tuple pattern → ssc1-front lowers it to cpat "Pair" (2) / "TupleN" (≥3); 1 collapses.
  private def tuppatProj(b: UniNode.Branch): String =
    val subs = kids(b).collect { case (Some("tup.arg"), c) => patProj(c) }.toVector
    subs.length match
      case 0 => """Pair("wpat", "")"""
      case 1 => subs.head
      case 2 => s"""Pair("cpat", Pair("Pair", ${consList(subs)}))"""
      case n => s"""Pair("cpat", Pair("Tuple$n", ${consList(subs)}))"""

  // Pair("block", [stmt…]) — mirrors ssc1-front; lowerBlock folds vals into nested lets.
  private def block(b: UniNode.Branch): String =
    s"""Pair("block", ${consList(kids(b).map((_, c) => stmt(c)))})"""

  private def stmt(n: UniNode): String = n match
    case b: UniNode.Branch if b.kind == "spike.val" =>
      val name = kids(b).collectFirst { case (Some("val.name"), c) => lexeme(c) }.getOrElse("_")
      val rhs  = kids(b).collectFirst { case (Some("val.rhs"), c) => expr(c) }.getOrElse(hole)
      s"""mkVal("${esc(name)}", $rhs)"""
    case b: UniNode.Branch if b.kind == "spike.exprStmt" =>
      s"""mkSExpr(${kids(b).collectFirst { case (Some("stmt.expr"), c) => expr(c) }.getOrElse(hole)})"""
    case _ => s"""mkSExpr($hole)"""

  private def infix(b: UniNode.Branch): String =
    val op = kids(b).collectFirst { case (Some("bin.op"), c) => lexeme(c) }.getOrElse("+")
    val l  = kids(b).collectFirst { case (Some("bin.left"), c) => expr(c) }.getOrElse(hole)
    val r  = kids(b).collectFirst { case (Some("bin.right"), c) => expr(c) }.getOrElse(hole)
    s"""mkInf("${esc(op)}", $l, $r)"""

  private def call(b: UniNode.Branch): String =
    val fn = kids(b).collectFirst { case (Some("call.fn"), c) => expr(c) }.getOrElse(hole)
    val args = kids(b).collect { case (Some("call.arg"), c) => expr(c) }
    s"""mkApp($fn, ${consList(args.toVector)})"""

  private def ifExpr(b: UniNode.Branch): String =
    val cnd = kids(b).collectFirst { case (Some("if.cond"), c) => expr(c) }.getOrElse(hole)
    val thn = kids(b).collectFirst { case (Some("if.thenE"), c) => expr(c) }.getOrElse(hole)
    val els = kids(b).collectFirst { case (Some("if.elseE"), c) => expr(c) }.getOrElse(hole)
    s"""mkIf($cnd, $thn, $els)"""
