package scalascript.uniml.spike

import scalascript.uniml.*

/** P6.0 gate-spike: a UniML dialect for a tiny Scala subset —
  * `def NAME(p: Int, ...): Int = EXPR`, EXPR over int / id / `+ - *` (with `*`
  * binding tighter) / `( )` / call `f(a, b)` / `if c then t else e`.
  *
  * The point of the spike is the gate question: does UniML's frame VM express
  * operator precedence tolerably? Answer demonstrated here: the dialect runs a
  * normal Pratt parse INTERNALLY (UniML gives no precedence for free), builds a
  * `Node` tree, then MECHANICALLY serialises it to `VmToken`s where each frame
  * opens on the first token of its subtree and closes (via `Reframe.closeAfter`)
  * on the last — source-order and lossless. UniML's VM then rebuilds exactly that
  * tree. See ScalaSpikeSpec for the CST-shape proofs and SpikeProject for the
  * projection into the ssc-v2 `Pair(tag, data)` AST.
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
          case _   => "spike.error"
        advance(c)
        emit(kind, start, c.toString, TokenChannel.Syntax)
    out.result()

// ── parser (RD for defs, Pratt for expression precedence) ─────────────────────
object SpikeParse:
  private final class Cur(val toks: Vector[SourceToken]):
    // significant tokens only (skip trivia) with an index into the full stream
    private var p = 0
    def skipTrivia(): Unit = while p < toks.length && toks(p).kind == "spike.ws" do p += 1
    def peekKind: String = { skipTrivia(); if p < toks.length then toks(p).kind else "spike.eof" }
    def peekLexeme: String = { skipTrivia(); if p < toks.length then toks(p).lexeme else "" }
    def next(): SourceToken = { skipTrivia(); val t = toks(p); p += 1; t }
    def eof: Boolean = { skipTrivia(); p >= toks.length }

  // Pratt: * binds tighter (prec 2) than + - (prec 1).
  private def prec(kind: String): Int = kind match
    case "spike.star"              => 2
    case "spike.plus" | "spike.minus" => 1
    case _                         => 0
  private def binKind(kind: String): String = kind match
    case "spike.star"  => "spike.mul"
    case "spike.plus"  => "spike.add"
    case "spike.minus" => "spike.sub"
    case _             => "spike.bin"

  def parseProgram(toks: Vector[SourceToken]): Node =
    val c = new Cur(toks)
    val defs = Vector.newBuilder[Node]
    while !c.eof do defs += parseDef(c)
    Node.Frame("spike.program", None, defs.result())

  private def parseDef(c: Cur): Node =
    val kw = c.next() // def
    val name = c.next() // id
    val lp = c.next() // (
    val kids = Vector.newBuilder[Node]
    kids += Node.Leaf(kw, Some("def.kw"))
    kids += Node.Leaf(name, Some("def.name"))
    kids += Node.Leaf(lp, Some("def.lparen"))
    while c.peekKind != "spike.rparen" do
      val pn = c.next()          // param name
      val colon = c.next()       // :
      val ty = c.next()          // type id
      kids += Node.Leaf(pn, Some("def.param"))
      kids += Node.Leaf(colon, Some("def.paramColon"))
      kids += Node.Leaf(ty, Some("def.paramType"))
      if c.peekKind == "spike.comma" then kids += Node.Leaf(c.next(), Some("def.comma"))
    kids += Node.Leaf(c.next(), Some("def.rparen"))   // )
    kids += Node.Leaf(c.next(), Some("def.retColon"))  // :
    kids += Node.Leaf(c.next(), Some("def.retType"))   // return type id
    kids += Node.Leaf(c.next(), Some("def.eq"))        // =
    kids += parseExpr(c, 1).withRole("def.body")
    Node.Frame("spike.def", None, kids.result())

  private def parseExpr(c: Cur, minPrec: Int): Node =
    var left = parseAtom(c)
    var p = prec(c.peekKind)
    while p >= minPrec && p > 0 do
      val op = c.next()
      val right = parseExpr(c, p + 1) // left-assoc
      left = Node.Frame(binKind(op.kind), None,
        Vector(left.withRole("bin.left"), Node.Leaf(op, Some("bin.op")), right.withRole("bin.right")))
      p = prec(c.peekKind)
    left

  private def parseAtom(c: Cur): Node =
    c.peekKind match
      case "spike.int" => Node.Leaf(c.next(), Some("int"))
      case "spike.lparen" =>
        val lp = c.next()
        val inner = parseExpr(c, 1)
        val rp = c.next()
        Node.Frame("spike.paren", None,
          Vector(Node.Leaf(lp, Some("paren.open")), inner.withRole("paren.inner"), Node.Leaf(rp, Some("paren.close"))))
      case "spike.kw" if c.peekLexeme == "if" =>
        val kwIf = c.next()
        val cond = parseExpr(c, 1)
        val kwThen = c.next()
        val thn = parseExpr(c, 1)
        val kwElse = c.next()
        val els = parseExpr(c, 1)
        Node.Frame("spike.if", None, Vector(
          Node.Leaf(kwIf, Some("if.kw")), cond.withRole("if.cond"),
          Node.Leaf(kwThen, Some("if.then")), thn.withRole("if.thenE"),
          Node.Leaf(kwElse, Some("if.else")), els.withRole("if.elseE")))
      case "spike.id" =>
        val id = Node.Leaf(c.next(), Some("var"))
        if c.peekKind == "spike.lparen" then
          val lp = c.next()
          val kids = Vector.newBuilder[Node]
          kids += id.withRole("call.fn")
          kids += Node.Leaf(lp, Some("call.open"))
          while c.peekKind != "spike.rparen" do
            kids += parseExpr(c, 1).withRole("call.arg")
            if c.peekKind == "spike.comma" then kids += Node.Leaf(c.next(), Some("call.comma"))
          kids += Node.Leaf(c.next(), Some("call.close"))
          Node.Frame("spike.call", None, kids.result())
        else id
      case other => // error node: consume one token so we make progress
        Node.Frame("spike.errExpr", None, Vector(Node.Leaf(c.next(), Some("error." + other))))

  extension (n: Node)
    private def withRole(role: String): Node = n match
      case Node.Leaf(t, _)       => Node.Leaf(t, Some(role))
      case Node.Frame(k, _, kids) => Node.Frame(k, Some(role), kids)

// ── serialise the Node tree → VmTokens (open on first token, closeAfter on last) ──
object SpikeEmit:
  private final case class Ev(tok: SourceToken, opens: Vector[FrameSpec], closes: Vector[String], role: Option[String])

  private def walk(n: Node): Vector[Ev] = n match
    case Node.Leaf(t, role) => Vector(Ev(t, Vector.empty, Vector.empty, role))
    case Node.Frame(kind, role, kids) =>
      val evs = kids.flatMap(walk)
      // frame opens at the first token of its subtree, closes after the last
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
        val tree = SpikeParse.parseProgram(toks)
        ProcessBatch(SpikeEmit.emit(tree), Vector.empty)

// ── projection: UniML CST → ssc-v2 `Pair(tag,data)` AST as ssc0 source text ────
// Emits an ssc0 expression that, given the ssc1-front `mk*` builders in scope,
// reconstructs the statement list (for `#coreir.encode(lowerProg(<that>))`).
object SpikeProject:
  private def esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

  private def lexeme(n: UniNode): String = n match
    case UniNode.Token(t) => t.lexeme
    case _                => ""
  private def kindOf(n: UniNode): String = n match
    case b: UniNode.Branch => b.kind
    case UniNode.Token(_)  => "token"

  // list of significant (non-trivia) child nodes with their roles
  private def kids(n: UniNode): Vector[(Option[String], UniNode)] = n match
    case b: UniNode.Branch =>
      b.edges.collect {
        case UniEdge(role, c) if !(c.isInstanceOf[UniNode.Token] && c.asInstanceOf[UniNode.Token].value.kind == "spike.ws") =>
          (role, c)
      }
    case _ => Vector.empty

  /** program CST → ssc0 statement-list expression (Cons(def, ... Nil)). */
  def program(root: UniNode): String =
    val defs = kids(root).map((_, c) => defNode(c))
    consList(defs)

  private def consList(xs: Vector[String]): String =
    xs.foldRight("Nil")((h, acc) => s"Cons($h, $acc)")

  private def defNode(n: UniNode): String =
    val ks = kids(n)
    val name = ks.collectFirst { case (Some("def.name"), c) => lexeme(c) }.getOrElse("main")
    val params = ks.collect { case (Some("def.param"), c) => lexeme(c) }
    val body = ks.collectFirst { case (role, c) if role.contains("def.body") => expr(c) }
      .getOrElse(exprBody(ks))
    val paramList = consList(params.map(p => "\"" + esc(p) + "\"").toVector)
    // no-param `def f(): T = e` still carries the `parameterless` marker off — use empty param list
    s"""mkDef("${esc(name)}", $paramList, $body)"""

  // body may not carry the def.body role if it is a bare leaf; fall back to the trailing expr child
  private def exprBody(ks: Vector[(Option[String], UniNode)]): String =
    ks.reverseIterator.collectFirst { case (_, c) if isExpr(c) => expr(c) }.getOrElse("mkTup(Nil)")
  private def isExpr(n: UniNode): Boolean = kindOf(n) match
    case "spike.int" | "spike.id" | "spike.add" | "spike.sub" | "spike.mul"
       | "spike.paren" | "spike.call" | "spike.if" => true
    case "token" => n match { case UniNode.Token(t) => t.kind == "spike.int" || t.kind == "spike.id"; case _ => false }
    case _ => false

  private def expr(n: UniNode): String = n match
    case UniNode.Token(t) if t.kind == "spike.int" => s"""mkInt("${esc(t.lexeme)}")"""
    case UniNode.Token(t) if t.kind == "spike.id"  => s"""mkVar("${esc(t.lexeme)}")"""
    case b: UniNode.Branch => b.kind match
      case "spike.int"   => s"""mkInt("${esc(firstLeaf(b))}")"""
      case "spike.id"    => s"""mkVar("${esc(firstLeaf(b))}")"""
      case "spike.add"   => bin("+", b)
      case "spike.sub"   => bin("-", b)
      case "spike.mul"   => bin("*", b)
      case "spike.paren" => kids(b).collectFirst { case (Some("paren.inner"), c) => expr(c) }.getOrElse("mkTup(Nil)")
      case "spike.call"  => call(b)
      case "spike.if"    => ifExpr(b)
      case _             => "mkTup(Nil)"
    case _ => "mkTup(Nil)"

  private def firstLeaf(n: UniNode): String = n match
    case UniNode.Token(t) => t.lexeme
    case b: UniNode.Branch => kids(b).headOption.map((_, c) => firstLeaf(c)).getOrElse("")

  private def bin(op: String, b: UniNode.Branch): String =
    val l = kids(b).collectFirst { case (Some("bin.left"), c) => expr(c) }.getOrElse("mkTup(Nil)")
    val r = kids(b).collectFirst { case (Some("bin.right"), c) => expr(c) }.getOrElse("mkTup(Nil)")
    s"""mkInf("$op", $l, $r)"""

  private def call(b: UniNode.Branch): String =
    val fn = kids(b).collectFirst { case (Some("call.fn"), c) => expr(c) }.getOrElse("mkTup(Nil)")
    val args = kids(b).collect { case (Some("call.arg"), c) => expr(c) }
    s"""mkApp($fn, ${consList(args.toVector)})"""

  private def ifExpr(b: UniNode.Branch): String =
    val cnd = kids(b).collectFirst { case (Some("if.cond"), c) => expr(c) }.getOrElse("mkTup(Nil)")
    val thn = kids(b).collectFirst { case (Some("if.thenE"), c) => expr(c) }.getOrElse("mkTup(Nil)")
    val els = kids(b).collectFirst { case (Some("if.elseE"), c) => expr(c) }.getOrElse("mkTup(Nil)")
    s"""mkIf($cnd, $thn, $els)"""
