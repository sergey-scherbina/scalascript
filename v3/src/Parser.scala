package ssc3

// The SSC3 parser — Tier 0 of v3/specs/20-core-language.md §2.
//
// THIS IS THE INTERIM HALF OF THE FRONT. v3/specs/40-front-on-uniml.md settles that the front is
// built on UniML: a lossless CST is the storage and a typed projection is the AST. This file
// produces that same typed AST directly, so when UniML's projection lands it replaces THIS file
// and nothing downstream moves — the AST and the lowering are already the parts that survive.
//
// Recursive descent with precedence climbing, over the token list the lexer produced. No regex, no
// backtracking, and every node carries the position it was parsed from.

final case class ParseFail(pos: Pos, message: String)
    extends RuntimeException(pos.show + ": " + message)

object Parser:

  private val keywords: List[String] =
    List("def", "val", "var", "if", "then", "else", "while", "do", "true", "false")

  /** Binary operator precedence, tightest last. `&&` and `||` are here for PARSING only — the
    * lowering turns them into `If`, because they short-circuit and an IR that lets them be strict
    * binary operators has already lost the semantics (v3/specs/10-ssc-ir.md §3). */
  private def prec(op: String): Int = op match
    case "||"                            => 1
    case "&&"                            => 2
    case "==" | "!="                     => 3
    case "<" | "<=" | ">" | ">="         => 4
    case "+" | "-"                       => 5
    case "*" | "/" | "%"                 => 6
    case _                               => 0

  // ── cursor ──────────────────────────────────────────────────────────────────
  private def peek(ts: List[Tok]): Tok = ts.head
  private def posOf(ts: List[Tok]): Pos = Lexer.posOf(peek(ts))

  private def isId(t: Tok, s: String): Boolean = t match
    case Tok.TId(n, _) => n == s
    case _             => false
  private def isPunct(t: Tok, s: String): Boolean = t match
    case Tok.TPunct(n, _) => n == s
    case _                => false
  private def isOp(t: Tok, s: String): Boolean = t match
    case Tok.TOp(n, _) => n == s
    case _             => false

  private def expectPunct(ts: List[Tok], s: String): List[Tok] =
    if isPunct(peek(ts), s) then ts.tail
    else throw ParseFail(posOf(ts), "expected '" + s + "', found " + Lexer.show(peek(ts)))
  private def expectOp(ts: List[Tok], s: String): List[Tok] =
    if isOp(peek(ts), s) then ts.tail
    else throw ParseFail(posOf(ts), "expected '" + s + "', found " + Lexer.show(peek(ts)))
  private def expectKw(ts: List[Tok], s: String): List[Tok] =
    if isId(peek(ts), s) then ts.tail
    else throw ParseFail(posOf(ts), "expected '" + s + "', found " + Lexer.show(peek(ts)))

  private def expectName(ts: List[Tok]): (String, Pos, List[Tok]) = peek(ts) match
    case Tok.TId(n, p) if !keywords.contains(n) => (n, p, ts.tail)
    case other => throw ParseFail(Lexer.posOf(other), "expected a name, found " + Lexer.show(other))

  private def skipNewlines(ts: List[Tok]): List[Tok] =
    var t = ts
    while peek(t).isInstanceOf[Tok.TNewline] do t = t.tail
    t

  /** A type annotation is CONSUMED AND DISCARDED at Tier 0. v3 has no type checker yet, and
    * pretending to read the annotation would put a second, unenforced notion of a program's types
    * into the front. When the checker arrives this is where it starts. */
  private def skipTypeAnn(ts: List[Tok]): List[Tok] =
    if !isPunct(peek(ts), ":") then ts
    else
      var t = ts.tail
      // A type is one name here; nothing at Tier 0 has type arguments.
      val (_, _, t2) = expectName(t)
      t2

  // ── expressions ─────────────────────────────────────────────────────────────
  private def parseExpr(ts: List[Tok]): (Expr, List[Tok]) = parseBin(ts, 1)

  private def parseBin(ts0: List[Tok], minPrec: Int): (Expr, List[Tok]) =
    var (lhs, ts) = parseUnary(ts0)
    var go = true
    while go do
      peek(ts) match
        case Tok.TOp(op, p) if prec(op) >= minPrec && prec(op) > 0 =>
          val (rhs, ts2) = parseBin(ts.tail, prec(op) + 1)
          lhs = Expr.Bin(op, lhs, rhs, p)
          ts = ts2
        case _ => go = false
    (lhs, ts)

  private def parseUnary(ts: List[Tok]): (Expr, List[Tok]) = peek(ts) match
    case Tok.TOp("-", p) =>
      val (e, t) = parseUnary(ts.tail); (Expr.Neg(e, p), t)
    case Tok.TOp("!", p) =>
      val (e, t) = parseUnary(ts.tail); (Expr.Not(e, p), t)
    case _ => parsePrimary(ts)

  private def parsePrimary(ts: List[Tok]): (Expr, List[Tok]) = peek(ts) match
    case Tok.TInt(v, p) => (Expr.IntLit(v, p), ts.tail)
    case Tok.TStr(v, p) => (Expr.StrLit(v, p), ts.tail)
    case Tok.TId("true", p)  => (Expr.BoolLit(true, p), ts.tail)
    case Tok.TId("false", p) => (Expr.BoolLit(false, p), ts.tail)
    case Tok.TId("if", p)    => parseIf(ts.tail, p)
    case Tok.TId("while", p) => parseWhile(ts.tail, p)
    case Tok.TId(n, p) if !keywords.contains(n) =>
      if isPunct(peek(ts.tail), "(") then
        val (as, t) = parseArgs(ts.tail.tail)
        (Expr.Call(n, as, p), t)
      else (Expr.Name(n, p), ts.tail)
    case Tok.TPunct("(", p) =>
      if isPunct(peek(ts.tail), ")") then (Expr.UnitLit(p), ts.tail.tail)
      else
        val (e, t) = parseExpr(ts.tail)
        (e, expectPunct(t, ")"))
    case other => throw ParseFail(Lexer.posOf(other), "expected an expression, found " + Lexer.show(other))

  private def parseArgs(ts0: List[Tok]): (List[Expr], List[Tok]) =
    if isPunct(peek(ts0), ")") then (Nil, ts0.tail)
    else
      var out: List[Expr] = Nil
      var ts = ts0
      var go = true
      while go do
        val (e, t) = parseExpr(ts)
        out = e :: out
        ts = t
        if isPunct(peek(ts), ",") then ts = ts.tail else go = false
      (out.reverse, expectPunct(ts, ")"))

  private def parseIf(ts0: List[Tok], p: Pos): (Expr, List[Tok]) =
    val (c, t1) = parseExpr(ts0)
    val t2 = expectKw(t1, "then")
    val (thenE, t3) = parseBody(t2)
    val t4 = skipNewlines(t3)
    if isId(peek(t4), "else") then
      val (elseE, t5) = parseBody(t4.tail)
      (Expr.If(c, thenE, Some(elseE), p), t5)
    else (Expr.If(c, thenE, None, p), t3)

  private def parseWhile(ts0: List[Tok], p: Pos): (Expr, List[Tok]) =
    val (c, t1) = parseExpr(ts0)
    val t2 = expectKw(t1, "do")
    val (body, t3) = parseBody(t2)
    (Expr.While(c, body, p), t3)

  /** A body is either an indented block or a single expression on the same line. Both spellings are
    * ordinary `.ssc`, so both are here rather than one being the "real" one. */
  private def parseBody(ts0: List[Tok]): (Expr, List[Tok]) =
    val ts = if peek(ts0).isInstanceOf[Tok.TNewline] then skipNewlines(ts0) else ts0
    if peek(ts).isInstanceOf[Tok.TIndent] then parseBlock(ts.tail)
    else parseExpr(ts)

  private def parseBlock(ts0: List[Tok]): (Expr, List[Tok]) =
    val p = posOf(ts0)
    var stmts: List[Stmt] = Nil
    var ts = ts0
    var last: Option[Expr] = None
    var go = true
    while go do
      ts = skipNewlines(ts)
      if peek(ts).isInstanceOf[Tok.TDedent] then
        ts = ts.tail; go = false
      else if peek(ts).isInstanceOf[Tok.TEof] then go = false
      else
        val (st, t) = parseStmt(ts)
        ts = t
        // The LAST expression of a block is its value, which is why this is decided at the end
        // rather than by looking ahead: whether a statement is the result depends on what follows.
        st match
          case Stmt.Exp(e) => last = Some(e); stmts = st :: stmts
          case _           => last = None; stmts = st :: stmts
    val body = stmts.reverse
    val (init, result) = last match
      case Some(e) => (body.dropRight(1), Some(e))
      case None    => (body, None)
    (Expr.Block(init, result, p), ts)

  private def parseStmt(ts: List[Tok]): (Stmt, List[Tok]) = peek(ts) match
    case Tok.TId(kw, p) if kw == "val" || kw == "var" =>
      val mutable = kw == "var"
      val (n, _, t1) = expectName(ts.tail)
      val t2 = skipTypeAnn(t1)
      val t3 = expectOp(t2, "=")
      val (e, t4) = parseExpr(t3)
      (Stmt.Val(n, e, mutable, p), t4)
    case Tok.TId(n, p) if !keywords.contains(n) && isOp(peek(ts.tail), "=") =>
      val (e, t) = parseExpr(ts.tail.tail)
      (Stmt.Exp(Expr.Assign(n, e, p)), t)
    case _ =>
      val (e, t) = parseExpr(ts)
      (Stmt.Exp(e), t)

  // ── definitions ─────────────────────────────────────────────────────────────
  private def parseDef(ts0: List[Tok]): (Def, List[Tok]) =
    val p = posOf(ts0)
    val t0 = expectKw(ts0, "def")
    val (name, _, t1) = expectName(t0)
    val t2 = expectPunct(t1, "(")
    var params: List[Param] = Nil
    var ts = t2
    if !isPunct(peek(ts), ")") then
      var go = true
      while go do
        val (pn, pp, t) = expectName(ts)
        ts = skipTypeAnn(t)
        params = Param(pn, pp) :: params
        if isPunct(peek(ts), ",") then ts = ts.tail else go = false
    ts = expectPunct(ts, ")")
    ts = skipTypeAnn(ts)
    ts = expectOp(ts, "=")
    val (body, t3) = parseBody(ts)
    (Def(name, params.reverse, body, p), t3)

  def parse(src: String): Program =
    var ts = Lexer.lex(src)
    var defs: List[Def] = Nil
    var go = true
    while go do
      ts = skipNewlines(ts)
      if peek(ts).isInstanceOf[Tok.TEof] then go = false
      else if peek(ts).isInstanceOf[Tok.TDedent] then ts = ts.tail
      else if isId(peek(ts), "def") then
        val (d, t) = parseDef(ts)
        defs = d :: defs
        ts = t
      else
        throw ParseFail(posOf(ts), "expected a `def`, found " + Lexer.show(peek(ts)))
    Program(defs.reverse)
