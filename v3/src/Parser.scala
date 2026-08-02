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
    List("def", "val", "var", "if", "then", "else", "while", "do", "true", "false", "case", "class", "match", "enum", "object", "trait", "try", "catch", "finally", "throw")

  /** Binary operator precedence, tightest last. `&&` and `||` are here for PARSING only — the
    * lowering turns them into `If`, because they short-circuit and an IR that lets them be strict
    * binary operators has already lost the semantics (v3/specs/10-ssc-ir.md §3). */
  private def prec(op: String): Int = op match
    case "||"                            => 1
    case "&&"                            => 2
    case "==" | "!="                     => 3
    case "<" | "<=" | ">" | ">="         => 4
    case "::"                            => 5
    case "+" | "-"                       => 5
    case "*" | "/" | "%"                 => 6
    case _                               => 0

  /** An integer literal that does not fit is a DIAGNOSTIC WITH A POSITION, not an exception from
    * the JDK. The difference is the difference between the UNSUPPORTED and CRASH buckets. */
  private def longOf(text: String, p: Pos): Long =
    try java.lang.Long.parseLong(text)
    catch case _: NumberFormatException => throw ParseFail(p, "integer literal out of 64-bit range: " + text)

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
      val (_, _, t2) = expectName(ts.tail)
      skipBrackets(t2)

  /** Consume a balanced `[…]` if one is here. Type arguments and type parameters are DISCARDED at
    * Tier 0 for the same reason annotations are: there is no checker, and half-reading them would
    * put an unenforced notion of types into the front. Measured on the corpus, `[` was 9 of the
    * first 60 refusals — the second largest cause after `{`. */
  private def skipBrackets(ts: List[Tok]): List[Tok] =
    if !isPunct(peek(ts), "[") then ts
    else
      var t = ts.tail
      var depth = 1
      while depth > 0 do
        if peek(t).isInstanceOf[Tok.TEof] then throw ParseFail(posOf(t), "unclosed '['")
        if isPunct(peek(t), "[") then depth = depth + 1
        else if isPunct(peek(t), "]") then depth = depth - 1
        t = t.tail
      t

  // ── expressions ─────────────────────────────────────────────────────────────
  private def parseExpr(ts: List[Tok]): (Expr, List[Tok]) =
    if lambdaAhead(ts) then parseLambda(ts) else parseBin(ts, 1)

  /** Is a lambda starting here? `x => …` or `(a, b) => …`. Decided by SCANNING to the matching
    * paren rather than by backtracking: a parser that can fail and retry gives error messages from
    * whichever attempt failed last, which is rarely the one the author meant. */
  private def lambdaAhead(ts: List[Tok]): Boolean =
    if ts.isEmpty then false
    else
      peek(ts) match
        case Tok.TId(n, _) if !keywords.contains(n) => ts.tail.nonEmpty && isOp(peek(ts.tail), "=>")
        case Tok.TPunct("(", _) =>
          var t = ts.tail
          var depth = 1
          while depth > 0 && t.nonEmpty && !t.head.isInstanceOf[Tok.TEof] do
            if isPunct(peek(t), "(") then depth = depth + 1
            else if isPunct(peek(t), ")") then depth = depth - 1
            t = t.tail
          t.nonEmpty && isOp(peek(t), "=>")
        case _ => false

  private def parseLambda(ts0: List[Tok]): (Expr, List[Tok]) =
    val p = posOf(ts0)
    var params: List[Param] = Nil
    var ts = ts0
    if isPunct(peek(ts), "(") then
      ts = ts.tail
      if !isPunct(peek(ts), ")") then
        var go = true
        while go do
          val (n, np, t) = expectName(ts)
          ts = skipTypeAnn(t)
          params = Param(n, np) :: params
          if isPunct(peek(ts), ",") then ts = ts.tail else go = false
      ts = expectPunct(ts, ")")
    else
      val (n, np, t) = expectName(ts)
      params = List(Param(n, np))
      ts = t
    ts = expectOp(ts, "=>")
    val (body, t2) = parseBody(ts)
    (Expr.Lambda(params.reverse, body, p), t2)

  private def parseBin(ts0: List[Tok], minPrec: Int): (Expr, List[Tok]) =
    var (lhs, ts) = parseUnary(ts0)
    var go = true
    while go do
      peek(ts) match
        case Tok.TOp(op, p) if prec(op) >= minPrec && prec(op) > 0 =>
          // `::` is RIGHT-associative, so it recurses at its own precedence rather than one above:
          // `1 :: 2 :: Nil` must be `1 :: (2 :: Nil)`, and left association would build a list
          // whose tail is a number.
          val (rhs, ts2) = parseBin(ts.tail, if op == "::" then prec(op) else prec(op) + 1)
          lhs = Expr.Bin(op, lhs, rhs, p)
          ts = ts2
        case _ => go = false
    (lhs, ts)

  /** Postfix `.name` / `.name(args)`, left-associative so `a.b.c(1).d` chains. Measured on the
    * corpus, `.` was ~104 of 343 remaining refusals — the largest single cause by a wide margin. */
  private def parsePostfix(ts0: List[Tok]): (Expr, List[Tok]) =
    var (e, ts) = parsePrimary(ts0)
    var go = true
    while go do
      if isId(peek(ts), "match") then
        val (arms, t) = parseMatchArms(ts.tail)
        e = Expr.Match(e, arms, Expr.posOf(e)); ts = t
      else if isPunct(peek(ts), ".") && ts.tail.nonEmpty then
        peek(ts.tail) match
          case Tok.TId(nm, p) =>
            val afterName = ts.tail.tail
            if isPunct(peek(afterName), "(") then
              val (as, t) = parseArgs(afterName.tail)
              e = Expr.MethodCall(e, nm, as, p); ts = t
            // `xs.map { x => … }` — a brace block as the single argument. Ordinary `.ssc`, and the
            // form the corpus uses far more often than `map(x => …)`.
            else if isPunct(peek(afterName), "{") then
              val (arg, t) = parseBody(afterName)
              e = Expr.MethodCall(e, nm, List(arg), p); ts = t
            else
              e = Expr.MethodCall(e, nm, Nil, p); ts = afterName
          case _ => go = false
      else go = false
    (e, ts)

  /** `match` arms, brace-delimited or indented. An arm's body runs until the next `case` or the end
    * of the block, which is why the body is parsed as a statement sequence rather than one
    * expression: `case X => a; b` is ordinary. */
  private def parseMatchArms(ts0: List[Tok]): (List[MatchArm], List[Tok]) =
    var ts = ts0
    // Two spellings, and the INDENTED one is why this cannot just skip layout tokens: the arms live
    // inside an INDENT block, and the DEDENT that closes it is the only thing separating the last
    // arm's body from whatever follows the match. Skipping DEDENTs indiscriminately made
    // `val r = p match … ` swallow the next statement into the final arm.
    var braced = false
    var indented = false
    if isPunct(peek(skipNewlines(ts)), "{") then
      braced = true
      ts = skipNewlines(ts).tail
    else
      val t = skipNewlines(ts)
      if peek(t).isInstanceOf[Tok.TIndent] then
        indented = true
        ts = t.tail
      else ts = t
    var arms: List[MatchArm] = Nil
    var go = true
    while go do
      ts = skipNewlines(ts)
      if braced && isPunct(peek(ts), "}") then
        ts = ts.tail
        go = false
      else if indented && peek(ts).isInstanceOf[Tok.TDedent] then
        ts = ts.tail
        go = false
      else if peek(ts).isInstanceOf[Tok.TEof] then go = false
      else if !isId(peek(ts), "case") then
        if arms.isEmpty then throw ParseFail(posOf(ts), "expected `case`, found " + Lexer.show(peek(ts)))
        else go = false
      else
        val (pat, t1) = parsePat(ts.tail)
        val t2 = expectOp(t1, "=>")
        val (body, t3) = parseArmBody(t2, braced)
        arms = MatchArm(pat, body) :: arms
        ts = t3
    (arms.reverse, ts)

  /** An arm body: an indented block, or statements on the arm's own line. It stops at the next
    * `case`, at the DEDENT that closes the arm list, at `}`, or at end of input — never past them. */
  private def parseArmBody(ts0: List[Tok], braced: Boolean): (Expr, List[Tok]) =
    val p = posOf(ts0)
    if peek(ts0).isInstanceOf[Tok.TNewline] && peek(skipNewlines(ts0)).isInstanceOf[Tok.TIndent] then
      parseBlock(skipNewlines(ts0).tail)
    else
      var ts = ts0
      var stmts: List[Stmt] = Nil
      var last: Option[Expr] = None
      var go = true
      while go do
        if isId(peek(ts), "case") || peek(ts).isInstanceOf[Tok.TEof] ||
           peek(ts).isInstanceOf[Tok.TDedent] || (braced && isPunct(peek(ts), "}")) then go = false
        else if peek(ts).isInstanceOf[Tok.TNewline] then
          // A newline ends the arm unless the next line is another `case`, which the loop head
          // then sees. Consuming it here and stopping is what keeps one arm to a line.
          ts = ts.tail
          go = false
        else
          val (st, t) = parseStmt(ts)
          ts = t
          st match
            case Stmt.Exp(e) => last = Some(e); stmts = st :: stmts
            case _           => last = None; stmts = st :: stmts
      val body = stmts.reverse
      last match
        case Some(e) => (Expr.Block(body.dropRight(1), Some(e), p), ts)
        case None    => (Expr.Block(body, None, p), ts)

  /** A pattern, with the infix cons form `h :: t` folded into the ordinary `Cons(h, t)`. It is
    * spelled differently and means the same thing, so it becomes the same node. */
  private def parsePat(ts0: List[Tok]): (Pat, List[Tok]) =
    val (head, ts) = parsePatAtom(ts0)
    if isOp(peek(ts), "::") then
      val (tail, t) = parsePat(ts.tail)
      (Pat.PCtor("Cons", List(head, tail), Pat.posOf(head)), t)
    else (head, ts)

  private def parsePatAtom(ts: List[Tok]): (Pat, List[Tok]) = peek(ts) match
    case Tok.TId("_", p) => (Pat.PWild(p), ts.tail)
    case Tok.TInt(t, p)  => (Pat.PLit(Expr.IntLit(longOf(t, p), p), p), ts.tail)
    case Tok.TFloat(t, p) => (Pat.PLit(Expr.DoubleLit(t.toDouble, p), p), ts.tail)
    case Tok.TStr(v, p)  => (Pat.PLit(Expr.StrLit(v, p), p), ts.tail)
    case Tok.TId("true", p)  => (Pat.PLit(Expr.BoolLit(true, p), p), ts.tail)
    case Tok.TId("false", p) => (Pat.PLit(Expr.BoolLit(false, p), p), ts.tail)
    case Tok.TId(n, p) if !keywords.contains(n) =>
      if isPunct(peek(ts.tail), "(") then
        var t = ts.tail.tail
        var args: List[Pat] = Nil
        if !isPunct(peek(t), ")") then
          var go = true
          while go do
            val (a, t2) = parsePatAtom(t)
            a match
              case Pat.PCtor(_, _, ap) =>
                throw ParseFail(ap, "a nested pattern is outside SSC3 core Tier 0")
              case _ => ()

            args = a :: args
            t = t2
            if isPunct(peek(t), ",") then t = t.tail else go = false
        (Pat.PCtor(n, args.reverse, p), expectPunct(t, ")"))
      // An UPPERCASE bare name is a nullary constructor (`Nil`, `None`); a lowercase one binds.
      else if n.charAt(0) >= 'A' && n.charAt(0) <= 'Z' then (Pat.PCtor(n, Nil, p), ts.tail)
      else (Pat.PBind(n, p), ts.tail)
    case other => throw ParseFail(Lexer.posOf(other), "expected a pattern, found " + Lexer.show(other))

  private def parseUnary(ts: List[Tok]): (Expr, List[Tok]) = peek(ts) match
    // `-` immediately before digits FOLDS into the literal rather than negating one. It has to:
    // Long.MinValue's magnitude is 2^63, which is not itself a Long, so `Neg(IntLit(2^63))` cannot
    // be built out of parts that each fit.
    case Tok.TOp("-", p) if ts.tail.nonEmpty && ts.tail.head.isInstanceOf[Tok.TInt] =>
      val Tok.TInt(text, _) = ts.tail.head: @unchecked
      (Expr.IntLit(longOf("-" + text, p), p), ts.tail.tail)
    case Tok.TOp("-", p) =>
      val (e, t) = parseUnary(ts.tail); (Expr.Neg(e, p), t)
    case Tok.TOp("!", p) =>
      val (e, t) = parseUnary(ts.tail); (Expr.Not(e, p), t)
    case _ => parsePostfix(ts)

  private def parsePrimary(ts: List[Tok]): (Expr, List[Tok]) = peek(ts) match
    case Tok.TInt(text, p) => (Expr.IntLit(longOf(text, p), p), ts.tail)
    case Tok.TFloat(text, p) => (Expr.DoubleLit(text.toDouble, p), ts.tail)
    case Tok.TStr(v, p) => (Expr.StrLit(v, p), ts.tail)
    case Tok.TInterp(raw, p) => (interp(raw, p), ts.tail)
    case Tok.TId("true", p)  => (Expr.BoolLit(true, p), ts.tail)
    case Tok.TId("false", p) => (Expr.BoolLit(false, p), ts.tail)
    case Tok.TId("if", p)    => parseIf(ts.tail, p)
    case Tok.TId("while", p) => parseWhile(ts.tail, p)
    case Tok.TId("try", p)   => parseTry(ts.tail, p)
    case Tok.TId("throw", p) =>
      val (e, t) = parseExpr(ts.tail)
      (Expr.Call("__throw__", List(e), p), t)
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

  /** Split `s"a $x b ${e} c"` into text parts and expressions.
    *
    * The holes are parsed by RE-LEXING the substring, which is why the lexer handed over the raw
    * content: `${…}` may contain anything an expression may contain, including nested braces and
    * strings, and a lexer that tried to tokenize it inline would need the expression grammar. */
  private def interp(raw: String, p: Pos): Expr =
    var parts: List[String] = Nil
    var exprs: List[Expr] = Nil
    var cur = ""
    var i = 0
    while i < raw.length do
      val c = raw.charAt(i)
      if c == '$' && i + 1 < raw.length then
        val n = raw.charAt(i + 1)
        if n == '$' then
          cur = cur + "$"
          i = i + 2
        else if n == '{' then
          var depth = 1
          var j = i + 2
          var body = ""
          while depth > 0 && j < raw.length do
            val d = raw.charAt(j)
            if d == '{' then depth = depth + 1
            else if d == '}' then depth = depth - 1
            if depth > 0 then body = body + d
            j = j + 1
          if depth > 0 then throw ParseFail(p, "unclosed `${` in an interpolated string")
          parts = cur :: parts
          cur = ""
          exprs = parseHole(body, p) :: exprs
          i = j
        else if Chars.isIdStart(n) then
          var j = i + 1
          var name = ""
          while j < raw.length && Chars.isIdPart(raw.charAt(j)) do
            name = name + raw.charAt(j)
            j = j + 1
          parts = cur :: parts
          cur = ""
          exprs = Expr.Name(name, p) :: exprs
          i = j
        else
          cur = cur + c
          i = i + 1
      else
        cur = cur + c
        i = i + 1
    parts = cur :: parts
    if exprs.isEmpty then Expr.StrLit(parts.head, p)
    else Expr.Interp(parts.reverse, exprs.reverse, p)

  private def parseHole(src: String, p: Pos): Expr =
    val toks = Lexer.lex(src)
    val (e, rest) = parseExpr(skipLayout(toks))
    if !skipLayout(rest).head.isInstanceOf[Tok.TEof] then
      throw ParseFail(p, "trailing input inside `${…}`: " + src)
    e

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

  /** `try <body> catch { case e => … }`, in either spelling. The handler is ONE arm binding the
    * caught value; typed arms (`case e: IOException =>`) need a type checker and are refused by
    * name rather than matched on and silently ignored. */
  private def parseTry(ts0: List[Tok], p: Pos): (Expr, List[Tok]) =
    val (body, t1) = parseBody(ts0)
    val t2 = skipLayout(t1)
    if !isId(peek(t2), "catch") then
      throw ParseFail(posOf(t2), "`try` without `catch` is outside SSC3 core Tier 0")
    var ts = skipLayout(t2.tail)
    val braced = isPunct(peek(ts), "{")
    if braced then ts = skipLayout(ts.tail)
    else if ts.head.isInstanceOf[Tok.TIndent] then ts = ts.tail
    if !isId(peek(ts), "case") then
      throw ParseFail(posOf(ts), "expected `case` in a `catch`, found " + Lexer.show(peek(ts)))
    val (pat, t3) = parsePat(ts.tail)
    val name = pat match
      case Pat.PBind(n, _) => n
      case Pat.PWild(_)    => "_caught"
      case other           => throw ParseFail(Pat.posOf(other), "a `catch` arm binds one name at Tier 0")
    var t4 = t3
    if isPunct(peek(t4), ":") then
      // `case e: SomeException =>` — the type is CONSUMED and discarded, like every other
      // annotation at Tier 0, so the arm catches everything. Said out loud because it is a
      // semantic difference from Scala, not just a parsing convenience.
      t4 = skipTypeAnn(t4)
    val t5 = expectOp(t4, "=>")
    val (handler, t6) = parseArmBody(t5, braced)
    // Only the BRACED form may skip layout here. In the indented form the DEDENT after the handler
    // is what closes the enclosing `def`, and eating it pulled every following top-level statement
    // into the function body — the program then ran, printed nothing, and exited 0.
    var t7 = if braced then skipLayout(t6) else t6
    if braced && isPunct(peek(t7), "}") then t7 = t7.tail
    (Expr.Try(body, name, handler, p), t7)

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
    else if isPunct(peek(ts), "{") then
      if lambdaAhead(skipLayout(ts.tail)) then
        val (l, t) = parseLambda(skipLayout(ts.tail))
        (l, expectPunct(skipLayout(t), "}"))
      else parseBraceBlock(ts.tail)
    else parseExpr(ts)

  /** A `{ … }` block. Inside braces the layout tokens carry no meaning — the braces already say
    * where the block ends — so INDENT/DEDENT/NEWLINE are skipped rather than parsed. Measured on
    * the corpus, `{` was 27 of the first 60 refusals, the single largest cause. */
  private def parseBraceBlock(ts0: List[Tok]): (Expr, List[Tok]) =
    val p = posOf(ts0)
    var stmts: List[Stmt] = Nil
    var last: Option[Expr] = None
    var ts = ts0
    var go = true
    while go do
      ts = skipLayout(ts)
      if isPunct(peek(ts), "}") then
        ts = ts.tail; go = false
      else if peek(ts).isInstanceOf[Tok.TEof] then throw ParseFail(posOf(ts), "unclosed '{'")
      else
        val (st, t) = parseStmt(ts)
        ts = t
        st match
          case Stmt.Exp(e) => last = Some(e); stmts = st :: stmts
          case _           => last = None; stmts = st :: stmts
    val body = stmts.reverse
    val (init, result) = last match
      case Some(e) => (body.dropRight(1), Some(e))
      case None    => (body, None)
    (Expr.Block(init, result, p), ts)

  private def skipLayout(ts: List[Tok]): List[Tok] =
    var t = ts
    var go = true
    while go do
      if t.head.isInstanceOf[Tok.TNewline] || t.head.isInstanceOf[Tok.TIndent] ||
         t.head.isInstanceOf[Tok.TDedent] then t = t.tail
      else go = false
    t

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

  /** `case class Name(f: T, …)`. A BODY (`… ):` followed by an indented block of methods) is
    * refused by name rather than skipped: skipping would silently drop methods the author wrote and
    * the program would fail later, somewhere else, as an unknown name. */
  private def parseCaseClass(ts0: List[Tok], p: Pos): (ClassDef, List[Tok]) =
    val (name, _, t0) = expectName(ts0)
    var ts = expectPunct(skipBrackets(t0), "(")
    var fields: List[Param] = Nil
    if !isPunct(peek(ts), ")") then
      var go = true
      while go do
        // `val`/`var` before a field name is ordinary and carries no meaning at Tier 0.
        if isId(peek(ts), "val") || isId(peek(ts), "var") then ts = ts.tail
        val (fn, fp, t) = expectName(ts)
        ts = skipTypeAnn(t)
        fields = Param(fn, fp) :: fields
        if isPunct(peek(ts), ",") then ts = ts.tail else go = false
    ts = expectPunct(ts, ")")
    if isPunct(peek(ts), ":") then
      throw ParseFail(posOf(ts), "a `case class` body is outside SSC3 core Tier 0 — only the constructor is supported")
    (ClassDef(name, fields.reverse, p), ts)

  /** `enum E:` / `enum E { … }` with `case A` and `case B(f: T)` members.
    *
    * Each case becomes a `ClassDef` — the SAME thing a `case class` produces — because that is what
    * an enum case is downstream: a constructor with a tag and some fields. Giving enums their own
    * representation would mean every later phase asking which of the two it had. */
  private def parseEnum(ts0: List[Tok]): (List[ClassDef], List[Tok]) =
    val (_, _, t0) = expectName(ts0)
    var ts = skipBrackets(t0)
    var braced = false
    if isPunct(peek(ts), ":") then ts = ts.tail
    if isPunct(peek(skipNewlines(ts)), "{") then
      braced = true
      ts = skipNewlines(ts).tail
    else
      val t = skipNewlines(ts)
      if t.head.isInstanceOf[Tok.TIndent] then ts = t.tail
      else throw ParseFail(posOf(t), "expected the enum's cases, indented or in braces")
    var out: List[ClassDef] = Nil
    var go = true
    while go do
      ts = if braced then skipLayout(ts) else skipNewlines(ts)
      if braced && isPunct(peek(ts), "}") then
        ts = ts.tail
        go = false
      else if !braced && ts.head.isInstanceOf[Tok.TDedent] then
        ts = ts.tail
        go = false
      else if ts.head.isInstanceOf[Tok.TEof] then go = false
      else if !isId(peek(ts), "case") then
        throw ParseFail(posOf(ts), "expected `case`, found " + Lexer.show(peek(ts)))
      else
        // `case A, B, C` on one line is ordinary and each name is its own constructor.
        var more = true
        ts = ts.tail
        while more do
          val (n, np, t1) = expectName(ts)
          var t2 = skipBrackets(t1)
          var fields: List[Param] = Nil
          if isPunct(peek(t2), "(") then
            t2 = t2.tail
            if !isPunct(peek(t2), ")") then
              var g2 = true
              while g2 do
                if isId(peek(t2), "val") || isId(peek(t2), "var") then t2 = t2.tail
                val (fn, fp, t3) = expectName(t2)
                t2 = skipTypeAnn(t3)
                fields = Param(fn, fp) :: fields
                if isPunct(peek(t2), ",") then t2 = t2.tail else g2 = false
            t2 = expectPunct(t2, ")")
          out = ClassDef(n, fields.reverse, np) :: out
          ts = t2
          if isPunct(peek(ts), ",") then ts = ts.tail else more = false
    (out, ts)

  /** `object Name:` / `object Name { … }` holding `def` members.
    *
    * A `val` member is REFUSED by name rather than skipped: it needs a module-level cell, which the
    * IR has (`GlobGet`/`GlobSet`) and the v2 bridge does not translate yet. Skipping it would leave
    * the program referring to a name that silently does not exist. */
  private def parseObject(ts0: List[Tok], p: Pos): (ObjectDef, List[Tok]) =
    val (name, _, t0) = expectName(ts0)
    var ts = skipBrackets(t0)
    var braced = false
    if isPunct(peek(ts), ":") then ts = ts.tail
    if isPunct(peek(skipNewlines(ts)), "{") then
      braced = true
      ts = skipNewlines(ts).tail
    else
      val t = skipNewlines(ts)
      if t.head.isInstanceOf[Tok.TIndent] then ts = t.tail
      else throw ParseFail(posOf(t), "expected the object's members, indented or in braces")
    var members: List[Def] = Nil
    var go = true
    while go do
      // Inside braces the layout tokens mean nothing — the braces already say where the block ends
      // — so they are skipped wholesale. Outside them the DEDENT IS the end, so only newlines go.
      ts = if braced then skipLayout(ts) else skipNewlines(ts)
      if braced && isPunct(peek(ts), "}") then
        ts = ts.tail
        go = false
      else if !braced && ts.head.isInstanceOf[Tok.TDedent] then
        ts = ts.tail
        go = false
      else if ts.head.isInstanceOf[Tok.TEof] then go = false
      else if isId(peek(ts), "def") then
        val (d, t) = parseDef(ts)
        members = d :: members
        ts = t
      else
        throw ParseFail(posOf(ts), "only `def` members are supported in an `object` at Tier 0, found " + Lexer.show(peek(ts)))
    (ObjectDef(name, members.reverse, p), ts)

  // ── definitions ─────────────────────────────────────────────────────────────
  private def parseDef(ts0: List[Tok]): (Def, List[Tok]) =
    val p = posOf(ts0)
    val t0 = expectKw(ts0, "def")
    val (name, _, t1) = expectName(t0)
    val t2 = expectPunct(skipBrackets(t1), "(")
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
    var top: List[Stmt] = Nil
    var classes: List[ClassDef] = Nil
    var objects: List[ObjectDef] = Nil
    var go = true
    while go do
      ts = skipLayout(ts)
      if peek(ts).isInstanceOf[Tok.TEof] then go = false
      else if isId(peek(ts), "def") then
        val (d, t) = parseDef(ts)
        defs = d :: defs
        ts = t
      else if isId(peek(ts), "case") && ts.tail.nonEmpty && isId(peek(ts.tail), "class") then
        val (c, t) = parseCaseClass(ts.tail.tail, posOf(ts))
        classes = c :: classes
        ts = t
      else if isId(peek(ts), "object") then
        val (o, t) = parseObject(ts.tail, posOf(ts))
        objects = o :: objects
        ts = t
      else if isId(peek(ts), "trait") then
        throw ParseFail(posOf(ts), "`trait` is outside SSC3 core Tier 0 — it needs dispatch, which needs a type checker")
      else if isId(peek(ts), "enum") then
        val (cs, t) = parseEnum(ts.tail)
        classes = cs.reverse ++ classes
        ts = t
      else
        // Not a `def`, so it is program body. Refusing here is what made 48 of the first 60 corpus
        // cases unreadable: a `.ssc` file is a script, and requiring every line to be inside a
        // definition was my assumption rather than the language's.
        val (st, t) = parseStmt(ts)
        top = st :: top
        ts = t
    Program(defs.reverse, top.reverse, classes.reverse, objects.reverse)
