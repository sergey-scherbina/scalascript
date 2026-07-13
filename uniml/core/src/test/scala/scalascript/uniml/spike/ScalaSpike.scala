package scalascript.uniml.spike

import scalascript.uniml.*

/** P6.0/P6.1 gate-spike: a UniML dialect for a tiny Scala subset —
  * `def NAME(p: Int, ...): Int = EXPR`, EXPR over int / id / `+ - *` (with `*`
  * binding tighter) / `( )` / call `f(a, b)` / `if c then t else e`.
  *
  * P6.0 answered the gate question — precedence is expressible: the dialect runs
  * a normal Pratt parse INTERNALLY, builds a `Node` tree, then MECHANICALLY
  * serialises it to `VmToken`s where each frame opens on the first token of its
  * subtree and closes (via `Reframe.closeAfter`) on the last. UniML's VM rebuilds
  * exactly that tree as a lossless CST.
  *
  * P6.1 makes the pipeline TOTAL and error-resilient: the parser never crashes,
  * records `Diagnostic`s, resyncs to the next `def` boundary on error, and emits
  * `spike.error` frames for salvaged junk; the projection is total and turns any
  * error / missing subtree into a `__notImplemented__` hole (generalising `???`),
  * so a broken region degrades to a hole without poisoning the rest of the file.
  */

// ── the intermediate tree the dialect builds (Pratt result), mapped 1:1 to the CST ──
enum Node:
  case Leaf(tok: SourceToken, role: Option[String])
  case Frame(kind: String, role: Option[String], children: Vector[Node])

// ── lexer ────────────────────────────────────────────────────────────────────
object SpikeLex:
  private val keywords = Set("def", "if", "then", "else")

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
      else
        val kind = c match
          case '+' => "spike.plus"
          case '-' => "spike.minus"
          case '*' => "spike.star"
          case '(' => "spike.lparen"
          case ')' => "spike.rparen"
          case ',' => "spike.comma"
          case ':' => "spike.colon"
          case '=' => "spike.eq"
          case _   => "spike.junk"
        advance(c)
        emit(kind, start, c.toString, TokenChannel.Syntax)
    out.result()

extension (n: Node)
  private def withRole(role: String): Node = n match
    case Node.Leaf(t, _)        => Node.Leaf(t, Some(role))
    case Node.Frame(k, _, kids) => Node.Frame(k, Some(role), kids)

// ── parser: total, error-resilient (RD for defs, Pratt for expression precedence) ──
object SpikeParse:
  final case class Parsed(tree: Node, diagnostics: Vector[Diagnostic])

  private final class Cur(val toks: Vector[SourceToken]):
    private var p = 0
    private val diags = Vector.newBuilder[Diagnostic]
    private def skipTrivia(): Unit = while p < toks.length && toks(p).kind == "spike.ws" do p += 1
    def eof: Boolean = { skipTrivia(); p >= toks.length }
    def peek: Option[SourceToken] = { skipTrivia(); if p < toks.length then Some(toks(p)) else None }
    def peekKind: String = peek.map(_.kind).getOrElse("spike.eof")
    def peekLexeme: String = peek.map(_.lexeme).getOrElse("<eof>")
    def advance(): Option[SourceToken] = { skipTrivia(); if p < toks.length then { val t = toks(p); p += 1; Some(t) } else None }
    def diagnostics: Vector[Diagnostic] = diags.result()
    def report(code: String, msg: String): Unit =
      val span = peek.map(_.span).orElse(toks.lastOption.map(_.span))
      diags += Diagnostic(code, msg, Severity.Error, span, Some("scalascript.spike"))

  private def prec(kind: String): Int = kind match
    case "spike.star"                 => 2
    case "spike.plus" | "spike.minus" => 1
    case _                            => 0
  private def binKind(kind: String): String = kind match
    case "spike.star"  => "spike.mul"
    case "spike.plus"  => "spike.add"
    case "spike.minus" => "spike.sub"
    case _             => "spike.bin"

  private def isDefStart(c: Cur): Boolean = c.peekKind == "spike.kw" && c.peekLexeme == "def"

  def parseProgram(toks: Vector[SourceToken]): Parsed =
    val c = new Cur(toks)
    val defs = Vector.newBuilder[Node]
    while !c.eof do
      if isDefStart(c) then defs += parseDef(c)
      else
        // resync: skip junk up to the next `def` / eof into an error frame
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
    expect(c, "spike.eq", "def.eq", "'='").foreach(kids += _)
    parseExpr(c, 1) match
      case Some(b) => kids += b.withRole("def.body")
      case None    => c.report("spike.missing-body", "missing def body expression")
    Node.Frame("spike.def", None, kids.result())

  private def parseExpr(c: Cur, minPrec: Int): Option[Node] =
    parseAtom(c) match
      case None => None
      case Some(first) =>
        var left = first
        var p = prec(c.peekKind)
        while p >= minPrec && p > 0 do
          val op = c.advance().get // safe: peek was an operator
          val kids = parseExpr(c, p + 1) match
            case Some(r) => Vector(left.withRole("bin.left"), Node.Leaf(op, Some("bin.op")), r.withRole("bin.right"))
            case None =>
              c.report("spike.missing-operand", s"missing right operand after '${op.lexeme}'")
              Vector(left.withRole("bin.left"), Node.Leaf(op, Some("bin.op"))) // right absent → hole in projection
          left = Node.Frame(binKind(op.kind), None, kids)
          p = prec(c.peekKind)
        Some(left)

  private def parseAtom(c: Cur): Option[Node] =
    c.peekKind match
      case "spike.int"    => c.advance().map(t => Node.Leaf(t, Some("int")))
      case "spike.lparen" => parseParen(c)
      case "spike.kw" if c.peekLexeme == "if" => parseIf(c)
      case "spike.id"     => parseIdOrCall(c)
      case "spike.eof"    => None
      case "spike.kw"     => None // `def`/`then`/`else` — a boundary; do not consume
      case _ => // junk token — consume into an error frame so we make progress
        c.report("spike.unexpected-expr", s"unexpected token '${c.peekLexeme}' in expression")
        c.advance().map(t => Node.Frame("spike.error", None, Vector(Node.Leaf(t, Some("error.token")))))

  private def parseParen(c: Cur): Option[Node] =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("paren.open")))
    parseExpr(c, 1).foreach(i => kids += i.withRole("paren.inner"))
    if c.peekKind == "spike.rparen" then c.advance().foreach(t => kids += Node.Leaf(t, Some("paren.close")))
    else c.report("spike.expected", "expected ')' to close '('")
    Some(Node.Frame("spike.paren", None, kids.result()))

  private def parseIf(c: Cur): Option[Node] =
    val kids = Vector.newBuilder[Node]
    c.advance().foreach(t => kids += Node.Leaf(t, Some("if.kw"))) // `if`
    parseExpr(c, 1).foreach(e => kids += e.withRole("if.cond"))
    if c.peekKind == "spike.kw" && c.peekLexeme == "then" then c.advance().foreach(t => kids += Node.Leaf(t, Some("if.then")))
    else c.report("spike.expected", "expected 'then'")
    parseExpr(c, 1).foreach(e => kids += e.withRole("if.thenE"))
    if c.peekKind == "spike.kw" && c.peekLexeme == "else" then c.advance().foreach(t => kids += Node.Leaf(t, Some("if.else")))
    else c.report("spike.expected", "expected 'else'")
    parseExpr(c, 1).foreach(e => kids += e.withRole("if.elseE"))
    Some(Node.Frame("spike.if", None, kids.result()))

  private def parseIdOrCall(c: Cur): Option[Node] =
    val id = c.advance().get // spike.id
    if c.peekKind != "spike.lparen" then Some(Node.Leaf(id, Some("var")))
    else
      val kids = Vector.newBuilder[Node]
      kids += Node.Leaf(id, Some("call.fn"))
      c.advance().foreach(t => kids += Node.Leaf(t, Some("call.open"))) // `(`
      while c.peekKind != "spike.rparen" && !c.eof && !isDefStart(c) do
        parseExpr(c, 1) match
          case Some(a) => kids += a.withRole("call.arg")
          case None =>
            c.report("spike.expected", "expected call argument")
            if c.peekKind != "spike.rparen" && !c.eof then c.advance() // progress on junk
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
      if evs.isEmpty then Vector.empty // token-less frame emits nothing (a hole)
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
// TOTAL: any error / missing subtree becomes a `__notImplemented__` hole (the ssc-v2
// generalisation of `???`) — compiles fine, throws only if evaluated.
object SpikeProject:
  /** the typed-hole placeholder in the ssc-v2 AST (a prim → IrPrim). */
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

  /** program CST → ssc0 statement-list expression (Cons(def, ... Nil)). Never throws. */
  def program(root: UniNode): String =
    val defs = kids(root).collect { case (_, c) if kindOf(c) == "spike.def" => defNode(c) }
    consList(defs)

  private def consList(xs: Vector[String]): String =
    xs.foldRight("Nil")((h, acc) => s"Cons($h, $acc)")

  private def defNode(n: UniNode): String =
    val ks = kids(n)
    val name = ks.collectFirst { case (Some("def.name"), c) => lexeme(c) }.getOrElse("main")
    val params = ks.collect { case (Some("def.param"), c) => lexeme(c) }
    val body = ks.collectFirst { case (role, c) if role.contains("def.body") => expr(c) }.getOrElse(hole)
    val paramList = consList(params.map(p => "\"" + esc(p) + "\"").toVector)
    s"""mkDef("${esc(name)}", $paramList, $body)"""

  private def expr(n: UniNode): String = n match
    case UniNode.Token(t) if t.kind == "spike.int" => s"""mkInt("${esc(t.lexeme)}")"""
    case UniNode.Token(t) if t.kind == "spike.id"  => s"""mkVar("${esc(t.lexeme)}")"""
    case b: UniNode.Branch => b.kind match
      case "spike.int"   => s"""mkInt("${esc(firstLeaf(b))}")"""
      case "spike.id"    => s"""mkVar("${esc(firstLeaf(b))}")"""
      case "spike.add"   => bin("+", b)
      case "spike.sub"   => bin("-", b)
      case "spike.mul"   => bin("*", b)
      case "spike.paren" => kids(b).collectFirst { case (Some("paren.inner"), c) => expr(c) }.getOrElse(hole)
      case "spike.call"  => call(b)
      case "spike.if"    => ifExpr(b)
      case "spike.error" => hole
      case _             => hole
    case _ => hole

  private def firstLeaf(n: UniNode): String = n match
    case UniNode.Token(t)  => t.lexeme
    case b: UniNode.Branch => kids(b).headOption.map((_, c) => firstLeaf(c)).getOrElse("")

  private def bin(op: String, b: UniNode.Branch): String =
    val l = kids(b).collectFirst { case (Some("bin.left"), c) => expr(c) }.getOrElse(hole)
    val r = kids(b).collectFirst { case (Some("bin.right"), c) => expr(c) }.getOrElse(hole)
    s"""mkInf("$op", $l, $r)"""

  private def call(b: UniNode.Branch): String =
    val fn = kids(b).collectFirst { case (Some("call.fn"), c) => expr(c) }.getOrElse(hole)
    val args = kids(b).collect { case (Some("call.arg"), c) => expr(c) }
    s"""mkApp($fn, ${consList(args.toVector)})"""

  private def ifExpr(b: UniNode.Branch): String =
    val cnd = kids(b).collectFirst { case (Some("if.cond"), c) => expr(c) }.getOrElse(hole)
    val thn = kids(b).collectFirst { case (Some("if.thenE"), c) => expr(c) }.getOrElse(hole)
    val els = kids(b).collectFirst { case (Some("if.elseE"), c) => expr(c) }.getOrElse(hole)
    s"""mkIf($cnd, $thn, $els)"""
