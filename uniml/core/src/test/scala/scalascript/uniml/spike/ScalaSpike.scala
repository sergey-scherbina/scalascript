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
  private val keywords = Set("def", "val", "if", "then", "else")
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
        emit("spike.int", start, sb.toString, TokenChannel.Syntax)
      else if c.isLetter || c == '_' then
        val sb = new StringBuilder
        while i < n && (text.charAt(i).isLetterOrDigit || text.charAt(i) == '_') do { sb.append(text.charAt(i)); advance(text.charAt(i)) }
        val w = sb.toString
        emit(if keywords(w) then "spike.kw" else "spike.id", start, w, TokenChannel.Syntax)
      else if isOpChar(c) then
        val sb = new StringBuilder
        while i < n && isOpChar(text.charAt(i)) do { sb.append(text.charAt(i)); advance(text.charAt(i)) }
        val op = sb.toString
        val kind = op match
          case "=" => "spike.eq"
          case ":" => "spike.colon"
          case _   => "spike.op"
        emit(kind, start, op, TokenChannel.Syntax)
      else
        val kind = c match
          case '(' => "spike.lparen"
          case ')' => "spike.rparen"
          case ',' => "spike.comma"
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
    case ":="                               => 1
    case _                                  => 0

  private final class Cur(val toks: Vector[SourceToken]):
    private var p = 0
    private val diags = Vector.newBuilder[Diagnostic]
    private def skipTrivia(): Unit = while p < toks.length && toks(p).kind == "spike.ws" do p += 1
    def eof: Boolean = { skipTrivia(); p >= toks.length }
    def peek: Option[SourceToken] = { skipTrivia(); if p < toks.length then Some(toks(p)) else None }
    def peekKind: String = peek.map(_.kind).getOrElse("spike.eof")
    def peekLexeme: String = peek.map(_.lexeme).getOrElse("<eof>")
    def peekLine: Int = peek.map(_.span.start.line).getOrElse(-1)
    def peekCol: Int = peek.map(_.span.start.column).getOrElse(-1)
    def peekPrec: Int = if peekKind == "spike.op" then opPrec(peekLexeme) else 0
    def advance(): Option[SourceToken] = { skipTrivia(); if p < toks.length then { val t = toks(p); p += 1; Some(t) } else None }
    def diagnostics: Vector[Diagnostic] = diags.result()
    def report(code: String, msg: String): Unit =
      val span = peek.map(_.span).orElse(toks.lastOption.map(_.span))
      diags += Diagnostic(code, msg, Severity.Error, span, Some("scalascript.spike"))

  private def isDefStart(c: Cur): Boolean = c.peekKind == "spike.kw" && c.peekLexeme == "def"
  private def isKw(c: Cur, w: String): Boolean = c.peekKind == "spike.kw" && c.peekLexeme == w

  def parseProgram(toks: Vector[SourceToken]): Parsed =
    val c = new Cur(toks)
    val defs = Vector.newBuilder[Node]
    while !c.eof do
      if isDefStart(c) then defs += parseDef(c)
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

  private def parseDef(c: Cur): Node =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("def.kw"))) // `def`
    expect(c, "spike.id", "def.name", "def name").foreach(kids += _)
    expect(c, "spike.lparen", "def.lparen", "'('").foreach(kids += _)
    var moreParams = c.peekKind != "spike.rparen" && !c.eof && !isDefStart(c)
    while moreParams do
      val name = expect(c, "spike.id", "def.param", "parameter name")
      name.foreach(kids += _)
      if name.isEmpty then moreParams = false
      else
        expect(c, "spike.colon", "def.paramColon", "':'").foreach(kids += _)
        expect(c, "spike.id", "def.paramType", "parameter type").foreach(kids += _)
        if c.peekKind == "spike.comma" then c.advance().foreach(t => kids += Node.Leaf(t, Some("def.comma")))
        else moreParams = false
    expect(c, "spike.rparen", "def.rparen", "')'").foreach(kids += _)
    expect(c, "spike.colon", "def.retColon", "':'").foreach(kids += _)
    expect(c, "spike.id", "def.retType", "return type").foreach(kids += _)
    val eqLine = c.peekLine // line of `=` before consuming
    expect(c, "spike.eq", "def.eq", "'='").foreach(kids += _)
    // offside: a body starting on a LATER line is an indented block (Scala optional-braces)
    if !c.eof && c.peekLine > eqLine then kids += parseBlock(c, c.peekCol).withRole("def.body")
    else parseExpr(c, 1) match
      case Some(b) => kids += b.withRole("def.body")
      case None    => c.report("spike.missing-body", "missing def body expression")
    Node.Frame("spike.def", None, kids.result())

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

  private def parseExpr(c: Cur, minPrec: Int): Option[Node] =
    parseAtom(c) match
      case None => None
      case Some(first) =>
        var left = first
        var p = c.peekPrec
        while p >= minPrec && p > 0 do
          val op = c.advance().get // spike.op
          val kids = parseExpr(c, p + 1) match
            case Some(r) => Vector(left.withRole("bin.left"), Node.Leaf(op, Some("bin.op")), r.withRole("bin.right"))
            case None =>
              c.report("spike.missing-operand", s"missing right operand after '${op.lexeme}'")
              Vector(left.withRole("bin.left"), Node.Leaf(op, Some("bin.op")))
          left = Node.Frame("spike.infix", None, kids)
          p = c.peekPrec
        Some(left)

  private def parseAtom(c: Cur): Option[Node] =
    c.peekKind match
      case "spike.int"    => c.advance().map(t => Node.Leaf(t, Some("int")))
      case "spike.lparen" => parseParen(c)
      case "spike.kw" if c.peekLexeme == "if" => parseIf(c)
      case "spike.id"     => parseIdOrCall(c)
      case "spike.junk" =>
        c.report("spike.unexpected-expr", s"unexpected token '${c.peekLexeme}' in expression")
        c.advance().map(t => Node.Frame("spike.error", None, Vector(Node.Leaf(t, Some("error.token")))))
      case _ => None // eof / kw boundary / operator / `)` / `=` / `:` / `,` — not an atom

  private def parseParen(c: Cur): Option[Node] =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("paren.open")))
    parseExpr(c, 1).foreach(i => kids += i.withRole("paren.inner"))
    if c.peekKind == "spike.rparen" then c.advance().foreach(t => kids += Node.Leaf(t, Some("paren.close")))
    else c.report("spike.expected", "expected ')' to close '('")
    Some(Node.Frame("spike.paren", None, kids.result()))

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
    consList(kids(root).collect { case (_, c) if kindOf(c) == "spike.def" => defNode(c) })

  private def consList(xs: Vector[String]): String =
    xs.foldRight("Nil")((h, acc) => s"Cons($h, $acc)")

  private def defNode(n: UniNode): String =
    val ks = kids(n)
    val name = ks.collectFirst { case (Some("def.name"), c) => lexeme(c) }.getOrElse("main")
    val params = ks.collect { case (Some("def.param"), c) => lexeme(c) }
    val body = ks.collectFirst { case (role, c) if role.contains("def.body") => expr(c) }.getOrElse(hole)
    s"""mkDef("${esc(name)}", ${consList(params.map(p => "\"" + esc(p) + "\"").toVector)}, $body)"""

  private def expr(n: UniNode): String = n match
    case UniNode.Token(t) if t.kind == "spike.int" => s"""mkInt("${esc(t.lexeme)}")"""
    case UniNode.Token(t) if t.kind == "spike.id"  => s"""mkVar("${esc(t.lexeme)}")"""
    case b: UniNode.Branch => b.kind match
      case "spike.infix" => infix(b)
      case "spike.paren" => kids(b).collectFirst { case (Some("paren.inner"), c) => expr(c) }.getOrElse(hole)
      case "spike.call"  => call(b)
      case "spike.if"    => ifExpr(b)
      case "spike.block" => block(b)
      case "spike.error" => hole
      case _             => hole
    case _ => hole

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
