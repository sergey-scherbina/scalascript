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
    List("def", "val", "var", "if", "then", "else", "while", "do", "true", "false", "case", "class", "match", "enum", "object", "trait", "try", "catch", "finally", "throw", "for", "yield")

  /** Binary operator precedence, tightest last. `&&` and `||` are here for PARSING only — the
    * lowering turns them into `If`, because they short-circuit and an IR that lets them be strict
    * binary operators has already lost the semantics (v3/specs/10-ssc-ir.md §3). */
  /** Scala's rule: precedence follows the operator's FIRST CHARACTER. Following it rather than
    * inventing a table means a program that groups one way in Scala groups the same way here —
    * `a | b & c` is `a | (b & c)` because `&` binds tighter than `|`, and a reader who knows Scala
    * does not have to learn a second set of rules to read SSC3. */
  private def prec(op: String): Int =
    // Scala's rule is by the operator's FIRST CHARACTER, not by the whole token. The table used to
    // list exact strings, which gave every operator outside the list precedence 0 — `:+` had no
    // precedence at all and could not be parsed. Keying on the first character is both faithful and
    // total: it reproduces every row the old table had, and a new operator needs no edit.
    // SYNTAX, not operators. They are spelled out of operator characters, so a rule keyed on the
    // first character claims them: `=>` would get `=`'s precedence 4 and `<-` would get `<`'s 5,
    // making both infix operators. That is a regression the exact-string table did not have — it
    // simply had no row for them — and it stayed hidden until pattern guards became the first place
    // an EXPRESSION is parsed immediately before a `=>`. Everywhere else `=>` is either consumed by
    // `expectOp` or detected by `lambdaAhead` before this function is reached.
    // ALPHANUMERIC operators sit BELOW every symbolic one, which is Scala's rule and the reason the
    // symbolic rows are 2..9 rather than 1..8: `0 until n * 2` must be `0 until (n * 2)`, and an
    // alphanumeric level equal to `|` would have made it `(0 until n) * 2`. There is no integer
    // between 0 and 1, and 0 already means "not an operator" — so the table shifts up by one and
    // keeps every relative order it had. `parseBin(ts, 1)`, the one caller that passes a literal,
    // still admits everything.
    if op == "=>" || op == "<-" || op == "=" || op.isEmpty then 0
    else if op.charAt(0).isLetter || op.charAt(0) == '_' then 1
    else op.charAt(0) match
      case '|'             => 2
      case '^'             => 3
      case '&'             => 4
      case '=' | '!'       => 5
      case '<' | '>'       => 6
      case ':'             => 7
      case '+' | '-'       => 8
      case '*' | '/' | '%' => 9
      case _               => 0

  /** Scala's other half of the same rule: precedence comes from the FIRST character, associativity
    * from the LAST. An operator ending in `:` is right-associative — `1 :: 2 :: Nil` must group as
    * `1 :: (2 :: Nil)`, and left association would build a list whose tail is a number.
    *
    * `++` was in the right-associative set before this and should not have been: it ends in `+`.
    * It was invisible because concatenation is associative, which is exactly the kind of latent
    * wrongness that surfaces later on an operator where it is not. */
  private def rightAssoc(op: String): Boolean = op.nonEmpty && op.charAt(op.length - 1) == ':'

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
    if !isPunct(peek(ts), ":") then ts else skipType(ts.tail)

  /** A type, DISCARDED. It used to be "a name, then optional brackets", which is only the simplest
    * type there is: `(Int, Int)` — a tuple type, which is what a function taking or returning a
    * tuple is annotated with — and `Int => Int` both failed to parse, and the diagnostic pointed at
    * the type rather than saying types were the limitation.
    *
    * Discarding rather than reading is invariant I-2's consequence: there is no checker at Tier 0,
    * and half-reading types would put an unenforced notion of them into the front. */
  /** `= expr` after a parameter's type. Parsed with `parseBin` at the comma's precedence rather
    * than `parseExpr`, so the default ends at the `,` or `)` that separates parameters instead of
    * swallowing them — `,` and `)` are punctuation and have no precedence, so the loop stops on
    * its own. */
  private def parseDefault(ts: List[Tok]): (Option[Expr], List[Tok]) =
    if !isOp(peek(ts), "=") then (None, ts)
    else
      val (e, t) = parseExpr(ts.tail)
      (Some(e), t)

  /** After the word `type`: the tokens FOLLOWING the alias's `=`, or `None` if this is not an
    * alias. Recognising before consuming is what keeps the top-level loop from spinning on a `type`
    * it cannot finish reading.
    *
    * Accepts an optional parameter list, `type F[A] = …`, by scanning to the matching `]` — the
    * brackets nest in `type M[F[_]] = …`, so counting them is not optional.
    */
  private def typeAliasEq(ts0: List[Tok]): Option[List[Tok]] =
    ts0 match
      case Tok.TId(n, _) :: rest if !keywords.contains(n) =>
        var ts = rest
        if isPunct(peek(ts), "[") then
          var depth = 0
          var go = true
          while go do
            peek(ts) match
              case Tok.TPunct("[", _) => depth += 1; ts = ts.tail
              case Tok.TPunct("]", _) =>
                depth -= 1; ts = ts.tail
                if depth == 0 then go = false
              case Tok.TEof(_) => go = false
              case _           => ts = ts.tail
          if depth != 0 then return None
        if isOp(peek(ts), "=") then Some(ts.tail) else None
      case _ => None

  private def skipType(ts0: List[Tok]): List[Tok] =
    var ts =
      if isPunct(peek(ts0), "(") then
        var t = ts0.tail
        var depth = 1
        while depth > 0 do
          if peek(t).isInstanceOf[Tok.TEof] then throw ParseFail(posOf(t), "unclosed '(' in a type")
          if isPunct(peek(t), "(") then depth = depth + 1
          else if isPunct(peek(t), ")") then depth = depth - 1
          t = t.tail
        t
      else
        val (_, _, t) = expectName(ts0)
        skipBrackets(t)
    // A FUNCTION type continues past the arrow: `f: Int => Int`. Safe here because every caller is
    // a declaration position — a parameter, a field, a `val`, a return type — never a match arm,
    // where a `=>` separates the pattern from the body and must survive.
    if isOp(peek(ts), "=>") then skipType(ts.tail) else ts

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

  /** Can the next token BEGIN an operand, right here on this line?
    *
    * Only infix identifiers need this. A symbolic operator with no right operand cannot mean
    * anything else, so `parseBin` consumes its layout unconditionally; an identifier can perfectly
    * well be the last thing in an expression, and treating that one as infix would eat the token
    * after it. Deliberately does NOT look through TNewline/TIndent — an operand on the next line is
    * a continuation only after an operator the parser already committed to.
    */
  /** May this expression be the LEFT operand of an alphanumeric infix operator?
    *
    * No, if it is a block-shaped statement. This is not a style rule, it is the difference between
    * parsing `demo.ssc` and losing half of it. A `while`/`if`/`match` body ends by consuming its
    * DEDENT, so the token stream hands the next STATEMENT straight to the operator loop with no
    * newline in between — and `println(total)` on the line after a `while` block looked exactly
    * like an infix use: an identifier, then `(`, which begins an operand.
    *
    * The parser produced `(bin "println" (while …) (name "total"))` and silently dropped two
    * `println` calls. Both differential gates caught it, and neither could have been read as this
    * from its output alone: the executor and the bridge AGREED, because they were faithfully
    * running the same wrong tree.
    *
    * Scala says the same thing for the same reason — `while (…) …` is a statement, not an operand.
    */
  private def canBeInfixLhs(e: Expr): Boolean = e match
    case _: Expr.While | _: Expr.If | _: Expr.Match | _: Expr.Block => false
    case _                                                          => true

  private def startsOperand(ts: List[Tok]): Boolean = ts match
    case Tok.TInt(_, _) :: _ | Tok.TFloat(_, _) :: _ | Tok.TStr(_, _) :: _ => true
    case Tok.TChar(_, _) :: _ | Tok.TInterp(_, _) :: _                     => true
    case Tok.TId(n, _) :: _   => !keywords.contains(n) || n == "true" || n == "false"
    case Tok.TPunct("(", _) :: _                                           => true
    // Prefix minus and not. `a to -1` is an operand; `a to )` is not.
    case Tok.TOp("-", _) :: _ | Tok.TOp("!", _) :: _                       => true
    case _                                                                 => false

  private def parseBin(ts0: List[Tok], minPrec: Int): (Expr, List[Tok]) =
    var (lhs, ts) = parseUnary(ts0)
    var go = true
    var indents = 0
    while go do
      peek(ts) match
        // `a to b`, `a until b`, `xs map f` — an ordinary identifier used INFIX, which is how Scala
        // spells `a.to(b)`. The lexer has no reason to call these operators, so they arrive as
        // `TId` and this loop never looked at them: `(0 until 50)` failed with
        // `expected ')', found until` (bench/corpus/range-sum.ssc, SSC3-7g/h).
        //
        // Two guards, and the second is what keeps this from swallowing programs. A keyword is
        // never an operator — `if x then y` must not read `x then y` as infix. And there has to be
        // a RIGHT OPERAND on the same line: statement boundaries are real tokens here
        // (TNewline/TIndent/TDedent), so `f(x)` followed by `g(y)` on the next line cannot join,
        // but `f(x) g` at the end of a line would otherwise consume whatever came after.
        case Tok.TId(n, p)
            if !keywords.contains(n) && prec(n) >= minPrec
              && canBeInfixLhs(lhs) && startsOperand(ts.tail) =>
          val (afterOp, moreIndents) = skipContinuation(ts.tail)
          indents = indents + moreIndents
          val (rhs, ts2) = parseBin(afterOp, prec(n) + 1)
          lhs = Expr.Bin(n, lhs, rhs, p)
          ts = ts2
        case Tok.TOp(op, p) if prec(op) >= minPrec && prec(op) > 0 =>
          // `::` is RIGHT-associative, so it recurses at its own precedence rather than one above:
          // `1 :: 2 :: Nil` must be `1 :: (2 :: Nil)`, and left association would build a list
          // whose tail is a number.
          //
          // A TRAILING operator continues the expression onto the next line:
          //
          //     journalU32(record.pageNumber.toLong) ++ byteSliceToList(record.page) ++
          //       journalU32(checksum)
          //
          // which is Scala's rule and was the top refusal at 119 cases. The layout after the
          // operator is consumed unconditionally here — unlike the `else` continuation, there is
          // nothing to guess: an operator with no right operand cannot mean anything else.
          val (afterOp, moreIndents) = skipContinuation(ts.tail)
          indents = indents + moreIndents
          val (rhs, ts2) = parseBin(afterOp, if rightAssoc(op) then prec(op) else prec(op) + 1)
          lhs = Expr.Bin(op, lhs, rhs, p)
          ts = ts2
        case _ => go = false
    (lhs, dropDedents(ts, indents))

  /** Layout after a trailing binary operator, reporting how many INDENTs were crossed so their
    * DEDENTs can be taken back — an unmatched DEDENT ends the enclosing block one statement early,
    * which is the half of this that the `else` continuation had to learn the hard way. */
  private def skipContinuation(ts0: List[Tok]): (List[Tok], Int) =
    var ts = ts0
    var n = 0
    var go = true
    while go do
      if peek(ts).isInstanceOf[Tok.TNewline] then ts = ts.tail
      else if peek(ts).isInstanceOf[Tok.TIndent] then
        ts = ts.tail; n = n + 1
      else go = false
    (ts, n)

  /** Postfix `.name` / `.name(args)`, left-associative so `a.b.c(1).d` chains. Measured on the
    * corpus, `.` was ~104 of 343 remaining refusals — the largest single cause by a wide margin. */
  private def parsePostfix(ts0: List[Tok]): (Expr, List[Tok]) =
    var (e, ts) = parsePrimary(ts0)
    var go = true
    while go do
      if isId(peek(ts), "match") then
        val (arms, t) = parseMatchArms(ts.tail)
        e = Expr.Match(e, arms, Expr.posOf(e)); ts = t
      // A `(` DIRECTLY after an expression applies it: `f(a)(b)`, and with it `foldLeft(z)(f)`,
      // which is the shape that made this worth doing — a fold is daily work and it could not be
      // written. No newline may intervene, and none can: a newline is its own token, so a `(`
      // opening the next line is a new statement and is not reached here.
      else if isPunct(peek(ts), "(") then
        val (as, t) = parseArgs(ts.tail)
        e = Expr.Apply(e, as, Expr.posOf(e)); ts = t
      else if isPunct(peek(ts), ".") && ts.tail.nonEmpty then
        peek(ts.tail) match
          case Tok.TId(nm, p) =>
            val afterName = skipBrackets(ts.tail.tail)
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
        // `case k if k > 0 =>`. The guard parses as an ordinary expression and stops at `=>` on its
        // own: `=>` has precedence 0, so the binary-operator loop does not take it.
        val (guard, t1g) =
          if isId(peek(t1), "if") then
            val (g, t) = parseExpr(t1.tail)
            (Some(g), t)
          else (None, t1)
        val t2 = expectOp(t1g, "=>")
        val (body, t3) = parseArmBody(t2, braced)
        arms = MatchArm(pat, guard, body) :: arms
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
          val (sts, t) = parseStmt(ts)
          ts = t
          sts.foreach { st =>
            st match
              case Stmt.Exp(e) => last = Some(e); stmts = st :: stmts
              case _           => last = None; stmts = st :: stmts
          }
      val body = stmts.reverse
      last match
        case Some(e) => (Expr.Block(body.dropRight(1), Some(e), p), ts)
        case None    => (Expr.Block(body, None, p), ts)

  /** A pattern, with the infix cons form `h :: t` folded into the ordinary `Cons(h, t)`. It is
    * spelled differently and means the same thing, so it becomes the same node. */
  private def parsePat(ts0: List[Tok]): (Pat, List[Tok]) =
    val (first, tsA) = parseConsPat(ts0)
    // `A | B | C`. Collected here rather than in `parsePatAtom` so that `h :: t` binds tighter,
    // which is Scala's grouping: `a :: b | c` is `(a :: b) | c`.
    if !isOp(peek(tsA), "|") then (first, tsA)
    else
      var alts = List(first)
      var ts = tsA
      while isOp(peek(ts), "|") do
        val (nxt, t) = parseConsPat(ts.tail)
        alts = alts :+ nxt
        ts = t
      (Pat.PAlt(alts, Pat.posOf(first)), ts)

  private def parseConsPat(ts0: List[Tok]): (Pat, List[Tok]) =
    val (head0, ts1) = parsePatAtom(ts0)
    // `case s: String =>` — a TYPE ASCRIPTION on the pattern. Read here rather than in the atom so
    // that `h :: t` still binds tighter and `case x: Int | y: String` groups as Scala does.
    //
    // The type is taken by its HEAD: `List[Int]` tests `List`, because the test is nominal and a
    // type argument carries no runtime evidence. Passing the whole text would compare the tag to
    // the literal "List[Int]" — a pattern that silently never matches.
    val (head, ts) =
      if !isPunct(peek(ts1), ":") then (head0, ts1)
      else
        val (tname, tp, tsT) = expectName(ts1.tail)
        (Pat.PType(tname, head0, tp), skipBrackets(tsT))
    if isOp(peek(ts), "::") then
      val (tail, t) = parseConsPat(ts.tail)
      (Pat.PCtor("Cons", List(head, tail), Pat.posOf(head)), t)
    else (head, ts)

  private def parsePatAtom(ts: List[Tok]): (Pat, List[Tok]) = peek(ts) match
    // `case (a, b) =>`. A tuple pattern is a constructor pattern over the same synthetic `TupleN`
    // the literal builds, so nesting, wildcards and guards all work without a second mechanism.
    case Tok.TPunct("(", p) =>
      var items: List[Pat] = Nil
      var ts2 = ts.tail
      var go = true
      while go do
        val (x, tn) = parsePat(ts2)
        items = items :+ x
        ts2 = tn
        if isPunct(peek(ts2), ",") then ts2 = ts2.tail else go = false
      val t = expectPunct(ts2, ")")
      if items.length == 1 then (items.head, t)
      else (Pat.PCtor("Tuple" + items.length, items, p), t)
    case Tok.TId("_", p) => (Pat.PWild(p), ts.tail)
    case Tok.TInt(t, p)  => (Pat.PLit(Expr.IntLit(longOf(t, p), p), p), ts.tail)
    case Tok.TFloat(t, p) => (Pat.PLit(Expr.DoubleLit(t.toDouble, p), p), ts.tail)
    case Tok.TStr(v, p)  => (Pat.PLit(Expr.StrLit(v, p), p), ts.tail)
    case Tok.TChar(c, p) => (Pat.PLit(Expr.CharLit(c, p), p), ts.tail)
    case Tok.TId("true", p)  => (Pat.PLit(Expr.BoolLit(true, p), p), ts.tail)
    case Tok.TId("false", p) => (Pat.PLit(Expr.BoolLit(false, p), p), ts.tail)
    case Tok.TId(n, p) if !keywords.contains(n) =>
      // `case C.Red =>` — a QUALIFIED constructor. The qualifier is dropped: v3 flattens an enum
      // into one class per case, so `C.Red` and `Red` name the same constructor and keeping the
      // prefix would mean two spellings of one thing for the lowering to reconcile.
      if isPunct(peek(ts.tail), ".") && ts.tail.tail.nonEmpty then
        peek(ts.tail.tail) match
          case Tok.TId(inner, ip) if !keywords.contains(inner) =>
            return parsePatAtom(Tok.TId(inner, ip) :: ts.tail.tail.tail)
          case _ => ()
      if isPunct(peek(ts.tail), "(") then
        var t = ts.tail.tail
        var args: List[Pat] = Nil
        if !isPunct(peek(t), ")") then
          var go = true
          while go do
            // A constructor argument is a full PATTERN, so patterns nest to any depth. `Pat` was
            // recursive from the start (`PCtor(name, args: List[Pat], …)`); only this parser and
            // the lowering restricted it, and nested patterns were the top corpus blocker at 116
            // cases — `case Right(ByteRead(value, _))` in one heavily-imported std module.
            val (a, t2) = parsePat(t)
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
    case Tok.TChar(c, p) => (Expr.CharLit(c, p), ts.tail)
    case Tok.TInterp(raw, p) => (interp(raw, p), ts.tail)
    case Tok.TId("true", p)  => (Expr.BoolLit(true, p), ts.tail)
    case Tok.TId("false", p) => (Expr.BoolLit(false, p), ts.tail)
    case Tok.TId("if", p)    => parseIf(ts.tail, p)
    case Tok.TId("while", p) => parseWhile(ts.tail, p)
    case Tok.TId("for", p)   => parseFor(ts.tail, p)
    case Tok.TId("try", p)   => parseTry(ts.tail, p)
    case Tok.TId("throw", p) =>
      val (e, t) = parseExpr(ts.tail)
      (Expr.Call("__throw__", List(e), p), t)
    case Tok.TId(n, p) if !keywords.contains(n) =>
      // TYPE ARGUMENTS in an expression: `List[Int]()`, `Map[String, Int]()`, `empty[A]`. Skipped,
      // like every other type at Tier 0 — there is no checker, and half-reading them would put an
      // unenforced notion of types into the front. `a[0]` is not indexing in this language (arrays
      // use `a(0)`), so a `[` after a name is unambiguously a type argument list.
      val afterTy = skipBrackets(ts.tail)
      if isPunct(peek(afterTy), "(") then
        val (as, t) = parseArgs(afterTy.tail)
        (Expr.Call(n, as, p), t)
      else (Expr.Name(n, p), afterTy)
    case Tok.TPunct("(", p) =>
      if isPunct(peek(ts.tail), ")") then (Expr.UnitLit(p), ts.tail.tail)
      else
        val (e, t) = parseExpr(ts.tail)
        // A comma turns the parenthesised expression into a TUPLE. `(e)` stays exactly what it was,
        // which is why the comma decides rather than a lookahead scan: there is nothing to guess.
        if isPunct(peek(t), ",") then
          var items = List(e)
          var ts2 = t
          while isPunct(peek(ts2), ",") do
            val (x, tn) = parseExpr(ts2.tail)
            items = items :+ x
            ts2 = tn
          (Expr.Call("Tuple" + items.length, items, p), expectPunct(ts2, ")"))
        else (e, expectPunct(t, ")"))
    case other => throw ParseFail(Lexer.posOf(other), "expected an expression, found " + Lexer.show(other))

  /** Split `s"a $x b ${e} c"` into text parts and expressions.
    *
    * The holes are parsed by RE-LEXING the substring, which is why the lexer handed over the raw
    * content: `${…}` may contain anything an expression may contain, including nested braces and
    * strings, and a lexer that tried to tokenize it inline would need the expression grammar. */
  /** The interpolation splitter, PUBLIC so a second front can reuse it. UniML's dialect keeps
    * `s"…"` as raw text with the holes still inside — deliberately, since `spike.interp` holds two
    * tokens and nothing is lost — so its projection has to split them. Two implementations of
    * `${…}` nesting is two implementations that will disagree about a brace inside a string. */
  def interpFor(raw: String, p: Pos): Expr = interp(raw, p)

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
        // `name = expr` is a NAMED argument. A lone `=`, never `==`, and never a bare name that
        // simply happens to be followed by an assignment — inside an argument list there is no
        // assignment statement to confuse it with.
        val named = peek(ts) match
          case Tok.TId(n, np) if !keywords.contains(n) && isOp(peek(ts.tail), "=") => Some((n, np))
          case _                                                                   => None
        val (e, t) = named match
          case Some((n, np)) =>
            val (v, tv) = parseExpr(ts.tail.tail)
            (Expr.NamedArg(n, v, np), tv)
          case None => parseExpr(ts)
        out = e :: out
        ts = t
        if isPunct(peek(ts), ",") then ts = ts.tail else go = false
      (out.reverse, expectPunct(ts, ")"))

  private def parseIf(ts0: List[Tok], p: Pos): (Expr, List[Tok]) =
    val (c, t1) = parseExpr(ts0)
    val t2 = expectKw(t1, "then")
    val (thenE, t3) = parseBody(t2)
    // A CONTINUATION `else`, on its own line and indented deeper than the statement:
    //
    //     val value = if little then readLeLoop(…)
    //                 else readBeLoop(…)
    //
    // The layout tokens between are consumed ONLY when `else` is what follows — skipping them
    // unconditionally would swallow the INDENT that opens the next block, and the parser would read
    // an unrelated statement as the `else` branch. Every INDENT taken is matched by taking its
    // DEDENT after the branch, or the enclosing block would end early.
    val (t4, indents) = skipToElse(t3)
    if isId(peek(t4), "else") then
      val (elseE, t5) = parseBody(t4.tail)
      (Expr.If(c, thenE, Some(elseE), p), dropDedents(t5, indents))
    else (Expr.If(c, thenE, None, p), t3)

  /** Look past newlines and indents for an `else`, reporting how many INDENTs were crossed. If
    * there is no `else` the caller keeps its original position, so nothing is consumed on a guess. */
  private def skipToElse(ts0: List[Tok]): (List[Tok], Int) =
    var ts = ts0
    var indents = 0
    var go = true
    while go do
      if peek(ts).isInstanceOf[Tok.TNewline] then ts = ts.tail
      else if peek(ts).isInstanceOf[Tok.TIndent] then
        ts = ts.tail
        indents = indents + 1
      else go = false
    if isId(peek(ts), "else") then (ts, indents) else (ts0, 0)

  /** Remove `n` DEDENTs, KEEPING the newlines they sit behind.
    *
    * The DEDENT closing a continuation line arrives AFTER that line's newline, so a check on the
    * very first token finds a newline and removes nothing — the DEDENT then reaches the enclosing
    * block, which ends one statement early. The newline is a statement SEPARATOR and must survive;
    * only the DEDENT is ours to take. */
  private def dropDedents(ts0: List[Tok], n: Int): List[Tok] =
    var head: List[Tok] = Nil
    var ts = ts0
    var left = n
    var go = left > 0
    while go do
      if peek(ts).isInstanceOf[Tok.TNewline] then
        head = peek(ts) :: head
        ts = ts.tail
      else if peek(ts).isInstanceOf[Tok.TDedent] then
        ts = ts.tail
        left = left - 1
        go = left > 0
      else go = false
    head.reverse ++ ts

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
    // A single-line body may be an ASSIGNMENT, which is a statement and not an expression:
    //
    //     if curCount > 0 then leaves = leaves + 1
    //
    // Parsed as a statement and wrapped in a block with no result, which is correct rather than a
    // workaround: an assignment's value is Unit either way. One place, so `if`, `while`, a `def`
    // body and a lambda body all get it — `for … do` had to be fixed on its own before this.
    else if assignAhead(ts) then
      val (sts, t) = parseStmt(ts)
      (Expr.Block(sts, None, posOf(ts)), t)
    else parseExpr(ts)

  /** Is an ASSIGNMENT statement starting here — `x = e` or `a(i) = e`? A lone `=`, never `==`,
    * which the operator lexer keeps as one token so there is nothing to disambiguate. */
  /** `.name =` with a LONE `=`, never `==`. Checked as a shape rather than by backtracking, for the
    * reason every other lookahead in this file gives. */
  private def isQualifiedAssign(ts: List[Tok]): Boolean = peek(ts) match
    case Tok.TId(n, _) if !keywords.contains(n) => ts.tail.nonEmpty && isOp(peek(ts.tail), "=")
    case _                                      => false

  private def assignAhead(ts: List[Tok]): Boolean = peek(ts) match
    case Tok.TId(n, _) if !keywords.contains(n) =>
      if isOp(peek(ts.tail), "=") then true
      else isPunct(peek(ts.tail), "(") && updateAhead(ts.tail)
    case _ => false

  /** A `{ … }` block. Inside braces the layout tokens carry no meaning — the braces already say
    * where the block ends — so INDENT/DEDENT/NEWLINE are skipped rather than parsed. Measured on
    * the corpus, `{` was 27 of the first 60 refusals, the single largest cause. */
  private def parseBraceBlock(ts0: List[Tok]): (Expr, List[Tok]) =
    val p = posOf(ts0)
    // `{ case (k, v) => … }` is a LAMBDA that matches its argument, not a block. It is how a
    // destructuring callback is written — `pairs.foreach { case (n, s) => … }` — and desugaring it
    // to `x => x match { case … }` means it inherits guards, nesting and fall-through from the
    // match that already exists rather than getting a second, quieter implementation.
    if isId(peek(skipLayout(ts0)), "case") then
      val (arms, t) = parseMatchArms(braceArms(ts0))
      val v = "$m" + p.line + "_" + p.col
      return (Expr.Lambda(List(Param(v, p)), Expr.Match(Expr.Name(v, p), arms, p), p), t)
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
        val (sts, t) = parseStmt(ts)
        ts = t
        sts.foreach { st =>
          st match
            case Stmt.Exp(e) => last = Some(e); stmts = st :: stmts
            case _           => last = None; stmts = st :: stmts
        }
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
        val (sts, t) = parseStmt(ts)
        ts = t
        // The LAST expression of a block is its value, which is why this is decided at the end
        // rather than by looking ahead: whether a statement is the result depends on what follows.
        sts.foreach { st =>
          st match
            case Stmt.Exp(e) => last = Some(e); stmts = st :: stmts
            case _           => last = None; stmts = st :: stmts
        }
    val body = stmts.reverse
    val (init, result) = last match
      case Some(e) => (body.dropRight(1), Some(e))
      case None    => (body, None)
    (Expr.Block(init, result, p), ts)

  /** A statement may expand into SEVERAL — `val (x, y) = e` is three — so this returns a list.
    * Expanding here rather than adding a statement kind means the lowering, the free-variable scan,
    * auto-output and the top-level global hoist all keep seeing ordinary `val`s, and none of them
    * had to learn about destructuring. */
  /** `for` is DESUGARED here, into the collection methods it already means:
    *
    *     for x <- xs do e            xs.foreach(x => e)
    *     for x <- xs yield e         xs.map(x => e)
    *     for x <- xs if p yield e    xs.filter(x => p).map(x => e)
    *     for a <- xs; b <- ys yield e    xs.flatMap(a => ys.map(b => e))
    *
    * Desugaring in the parser rather than adding an IR form is the same decision tuples got: the
    * lowering, the verifier and both backends keep seeing calls they already handle, and `for`
    * cannot drift away from what the methods do because it IS those methods. It also means `for`
    * works over anything with `map`/`flatMap`/`foreach`, which is what makes it worth having.
    *
    * The generators are separated by newlines or `;`, and the whole list may be parenthesised. */
  private def parseFor(ts0: List[Tok], p: Pos): (Expr, List[Tok]) =
    val paren = isPunct(peek(ts0), "(")
    var ts = if paren then ts0.tail else skipLayoutTokens(ts0)
    var gens: List[(String, Expr, List[Expr])] = Nil   // name, source, guards
    var go = true
    while go do
      ts = skipLayoutTokens(ts)
      val (n, _, t1) = expectName(ts)
      val t2 = expectOp(t1, "<-")
      val (src, t3) = parseExpr(t2)
      var t = skipLayoutTokens(t3)
      var guards: List[Expr] = Nil
      while isId(peek(t), "if") do
        val (g, tg) = parseExpr(t.tail)
        guards = guards :+ g
        t = skipLayoutTokens(tg)
      gens = gens :+ ((n, src, guards))
      if isPunct(peek(t), ";") then ts = t.tail
      else if isId(peek(t), "do") || isId(peek(t), "yield") || isPunct(peek(t), ")") then
        ts = t
        go = false
      else ts = t
      if !go then ()
      else if isId(peek(ts), "do") || isId(peek(ts), "yield") || isPunct(peek(ts), ")") then go = false
    if paren then ts = expectPunct(ts, ")")
    ts = skipLayoutTokens(ts)
    val yields = isId(peek(ts), "yield")
    if !yields && !isId(peek(ts), "do") then
      throw ParseFail(posOf(ts), "expected `do` or `yield` after a `for`, found " + Lexer.show(peek(ts)))
    // A `do` body is a STATEMENT — `for i <- xs do total = total + a(i)` is the ordinary shape of an
    // imperative loop, and `parseBody` parses an expression, so an assignment there failed with the
    // `=` blamed. A `yield` body is an expression by definition and keeps the expression parse.
    val (body, tEnd) =
      if yields then parseBody(ts.tail)
      else if peek(ts.tail).isInstanceOf[Tok.TNewline] &&
              peek(skipNewlines(ts.tail)).isInstanceOf[Tok.TIndent] then parseBody(ts.tail)
      else
        val (sts, t) = parseStmt(ts.tail)
        (Expr.Block(sts, None, p), t)
    // Built INSIDE OUT: the innermost generator carries the body, and each enclosing one wraps what
    // it produced in a `flatMap`. Only the innermost decides between `map` and `foreach`.
    var acc = body
    var first = true
    gens.reverse.foreach { g =>
      val (n, src, guards) = g
      val filtered = guards.foldLeft(src) { (sofar, cond) =>
        Expr.MethodCall(sofar, "filter", List(Expr.Lambda(List(Param(n, p)), cond, p)), p)
      }
      val method = if first then (if yields then "map" else "foreach") else "flatMap"
      acc = Expr.MethodCall(filtered, method, List(Expr.Lambda(List(Param(n, p)), acc, p)), p)
      first = false
    }
    (acc, tEnd)

  /** Newlines and INDENTs between a `for`'s generators carry no meaning — the `do`/`yield` says
    * where the header ends. DEDENTs are left alone: one of them closes the enclosing block. */
  /** `parseMatchArms` wants to see the `{` so it can end at the matching `}`. `parseBraceBlock` is
    * called with it already consumed, so it is put back — one token, and the alternative is a
    * second arm-reading loop that would have to agree with the first about fall-through. */
  private def braceArms(ts: List[Tok]): List[Tok] = Tok.TPunct("{", posOf(ts)) :: ts

  /** Does a balanced `( … )` starting here end at a lone `=`? `=` and not `==`, because `a(i) == v`
    * is an ordinary comparison and misreading it as an assignment would silently discard it. */
  private def updateAhead(ts0: List[Tok]): Boolean =
    var t = ts0.tail
    var depth = 1
    while depth > 0 && t.nonEmpty && !peek(t).isInstanceOf[Tok.TEof] do
      if isPunct(peek(t), "(") then depth = depth + 1
      else if isPunct(peek(t), ")") then depth = depth - 1
      t = t.tail
    t.nonEmpty && isOp(peek(t), "=")

  private def skipLayoutTokens(ts0: List[Tok]): List[Tok] =
    var ts = ts0
    while peek(ts).isInstanceOf[Tok.TNewline] || peek(ts).isInstanceOf[Tok.TIndent] do ts = ts.tail
    ts

  private def parseStmt(ts: List[Tok]): (List[Stmt], List[Tok]) = peek(ts) match
    case Tok.TId("def", _) =>
      val (d, t) = parseDef(ts)
      (List(Stmt.LocalDef(d)), t)
    // `val (x, y) = e` — bind the tuple ONCE to a temporary, then read its fields. Binding once is
    // the point: expanding to `e._1` and `e._2` would evaluate `e` twice, and `e` may print, read a
    // file or advance a counter. The temporary's name is derived from the SOURCE POSITION, which
    // makes it unique without the parser having to carry a counter.
    case Tok.TId(kw, p) if (kw == "val" || kw == "var") && isPunct(peek(ts.tail), "(") =>
      val (pat, t1) = parsePat(ts.tail)
      val t2 = expectOp(skipTypeAnn(t1), "=")
      val (e, t3) = parseBody(t2)
      val names = pat match
        case Pat.PCtor(cn, args, _) if cn.startsWith("Tuple") => args
        case other => throw ParseFail(Pat.posOf(other), "a destructuring `val` binds a tuple at Tier 0")
      // Named after what it BINDS, not where it is. A position-derived name made the two fronts
      // disagree on a temporary nobody writes and nobody reads — a difference with no meaning that
      // the gate would report forever.
      val tmp = "$tup_" + names.flatMap { a => a match
        case Pat.PBind(n, _) => List(n)
        case _               => Nil
      }.mkString("_")
      var out: List[Stmt] = List(Stmt.Val(tmp, e, false, p))
      names.zipWithIndex.foreach { (ap, i) =>
        ap match
          case Pat.PBind(n, np) =>
            out = out :+ Stmt.Val(n, Expr.MethodCall(Expr.Name(tmp, np), "_" + (i + 1), Nil, np),
                                  kw == "var", np)
          case Pat.PWild(_) => ()
          case other =>
            throw ParseFail(Pat.posOf(other), "a destructuring `val` binds names at Tier 0")
      }
      (out, t3)
    case Tok.TId(kw, p) if kw == "val" || kw == "var" =>
      val mutable = kw == "var"
      val (n, _, t1) = expectName(ts.tail)
      val t2 = skipTypeAnn(t1)
      val t3 = expectOp(t2, "=")
      // `parseBody`, not `parseExpr`: the value may be an INDENTED BLOCK on the following lines —
      //
      //     val result =
      //       compute(x)
      //
      // which is ordinary Scala and was 116 of 120 cases in the top refusal bucket, all reaching
      // one line through an import. A `def` body already used `parseBody`; a `val` did not, and
      // that asymmetry had no reason behind it.
      val (e, t4) = parseBody(t3)
      (List(Stmt.Val(n, e, mutable, p)), t4)
    // `a(i) = v`. Told apart from a call by SCANNING to the matching `)` and looking for a single
    // `=` after it — a scan rather than a backtracking attempt, for the reason `lambdaAhead` gives:
    // a parser that retries reports the error from whichever attempt failed last.
    case Tok.TId(n, p) if !keywords.contains(n) && isPunct(peek(ts.tail), "(") && updateAhead(ts.tail) =>
      val (idx, t1) = parseExpr(ts.tail.tail)
      val t2 = expectPunct(t1, ")")
      val t3 = expectOp(t2, "=")
      val (v, t4) = parseExpr(t3)
      (List(Stmt.Exp(Expr.Update(Expr.Name(n, p), idx, v, p))), t4)
    // `Cfg.count = 5` — assignment to an object MEMBER. The dotted name is one name here, which is
    // how the member was stored in the first place.
    case Tok.TId(n, p) if !keywords.contains(n) && isPunct(peek(ts.tail), ".") &&
                          ts.tail.tail.nonEmpty && isQualifiedAssign(ts.tail.tail) =>
      val m = ts.tail.tail.head match
        case Tok.TId(x, _) => x
        case other         => throw ParseFail(Lexer.posOf(other), "expected a member name")
      val (e, t) = parseBody(ts.tail.tail.tail.tail)
      (List(Stmt.Exp(Expr.Assign(n + "." + m, e, p))), t)
    case Tok.TId(n, p) if !keywords.contains(n) && isOp(peek(ts.tail), "=") =>
      val (e, t) = parseBody(ts.tail.tail)
      (List(Stmt.Exp(Expr.Assign(n, e, p))), t)
    case _ =>
      val (e, t) = parseExpr(ts)
      (List(Stmt.Exp(e)), t)

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
        val (dflt, tD) = parseDefault(ts)
        ts = tD
        fields = Param(fn, fp, dflt) :: fields
        if isPunct(peek(ts), ",") then ts = ts.tail else go = false
    ts = expectPunct(ts, ")")
    val (parents, tp) = parseParents(ts)
    ts = tp
    // A BODY. It used to be refused by name, which was the honest thing while methods could not be
    // lowered; they can now, so the refusal would be the dishonest one.
    if isPunct(peek(ts), ":") then
      val (members, vs, t2) = parseMembers(ts.tail, "class")
      if vs.nonEmpty then throw ParseFail(p, "a `case class` member that is not a `def` is outside SSC3 core Tier 0")
      (ClassDef(name, fields.reverse, members, parents, p), t2)
    else (ClassDef(name, fields.reverse, Nil, parents, p), ts)

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
          out = ClassDef(n, fields.reverse, Nil, Nil, np) :: out
          ts = t2
          if isPunct(peek(ts), ",") then ts = ts.tail else more = false
    // REVERSED before this. The list is built by prepending and was returned unreversed, so an
    // enum's cases reached the AST back to front. Nothing observable depended on it — tags are
    // assigned on first use, not by declaration order — which is exactly why it survived: the
    // front-to-front differential is what noticed, because the OTHER front had them in source
    // order and the two trees could then be compared.
    (out.reverse, ts)

  /** `object Name:` / `object Name { … }` holding `def` members.
    *
    * A `val` member is REFUSED by name rather than skipped: it needs a module-level cell, which the
    * IR has (`GlobGet`/`GlobSet`) and the v2 bridge does not translate yet. Skipping it would leave
    * the program referring to a name that silently does not exist. */
  /** `extends A with B` — the names are KEPT (a class inherits its parents' concrete methods) and
    * nothing is checked with them. Type arguments are discarded like every other type at Tier 0. */
  private def parseParents(ts0: List[Tok]): (List[String], List[Tok]) =
    if !isId(peek(ts0), "extends") then (Nil, ts0)
    else
      var out: List[String] = Nil
      var ts = ts0.tail
      var go = true
      while go do
        val (n, _, t) = expectName(ts)
        out = out :+ n
        ts = skipBrackets(t)
        // `extends T(args)` — a parent constructor call. Discarded: at Tier 0 a parent contributes
        // methods, not initialisation.
        if isPunct(peek(ts), "(") then
          var depth = 1
          ts = ts.tail
          while depth > 0 && !peek(ts).isInstanceOf[Tok.TEof] do
            if isPunct(peek(ts), "(") then depth = depth + 1
            else if isPunct(peek(ts), ")") then depth = depth - 1
            ts = ts.tail
        if isId(peek(ts), "with") then ts = ts.tail else go = false
      (out, ts)

  /** The `def` members of a `class`/`trait`/`object` body, indented or braced. One reader for all
    * three, because they differ in what the members MEAN, not in how they are written. */
  private def parseMembers(ts0: List[Tok], what: String): (List[Def], List[Stmt.Val], List[Tok]) =
    var vals: List[Stmt.Val] = Nil
    var ts = ts0
    var braced = false
    if isPunct(peek(skipNewlines(ts)), "{") then
      braced = true
      ts = skipNewlines(ts).tail
    else
      val t = skipNewlines(ts)
      if t.head.isInstanceOf[Tok.TIndent] then ts = t.tail
      else throw ParseFail(posOf(t), "expected the " + what + "'s members, indented or in braces")
    var members: List[Def] = Nil
    var go = true
    while go do
      ts = if braced then skipLayout(ts) else skipNewlines(ts)
      if braced && isPunct(peek(ts), "}") then
        ts = ts.tail; go = false
      else if !braced && ts.head.isInstanceOf[Tok.TDedent] then
        ts = ts.tail; go = false
      else if ts.head.isInstanceOf[Tok.TEof] then go = false
      else if isId(peek(ts), "override") || isId(peek(ts), "final") then ts = ts.tail
      else if isId(peek(ts), "def") then
        val (d, t) = parseDef(ts)
        members = d :: members
        ts = t
      // A `val`/`var` member — collected here and sorted out by the caller, since only an `object`
      // can hold one: a `trait`'s abstract state and a `case class`'s fields are different things.
      else if isId(peek(ts), "val") || isId(peek(ts), "var") then
        val (sts, t) = parseStmt(ts)
        sts.foreach { st => st match
          case v: Stmt.Val => vals = vals :+ v
          case _           => throw ParseFail(posOf(ts), "a member of a " + what + " must be a definition")
        }
        ts = t
      else
        throw ParseFail(posOf(ts), "only `def` members are supported in a " + what +
          " at Tier 0, found " + Lexer.show(peek(ts)))
    (members.reverse, vals, ts)

  /** `trait T:` — the members it declares. An ABSTRACT member (`def f(x: Int): Int` with no `=`)
    * is a declaration and contributes nothing to run time; a CONCRETE one is inherited by every
    * class that extends the trait. No dispatch table and no type checker: a call on a value is
    * resolved by the receiver's tag, which is what the field-by-name `Switch` already does. */
  private def parseTrait(ts0: List[Tok], p: Pos): (TraitDef, List[Tok]) =
    val (name, _, t0) = expectName(ts0)
    var ts = skipBrackets(t0)
    val (parents, t1) = parseParents(ts)
    ts = t1
    if !isPunct(peek(ts), ":") && !isPunct(peek(skipNewlines(ts)), "{") then
      // `trait Marker` with no body is a name and nothing else.
      (TraitDef(name, Nil, parents, p), ts)
    else
      if isPunct(peek(ts), ":") then ts = ts.tail
      val (members, vs, t2) = parseMembers(ts, "trait")
      if vs.nonEmpty then throw ParseFail(p, "a `trait` member that is not a `def` is outside SSC3 core Tier 0")
      (TraitDef(name, members, parents, p), t2)

  private def parseObject(ts0: List[Tok], p: Pos): (ObjectDef, List[Tok]) =
    val (name, _, t0) = expectName(ts0)
    var ts = skipBrackets(t0)
    val (_, tp) = parseParents(ts)
    ts = tp
    if isPunct(peek(ts), ":") then ts = ts.tail
    val (members, vals, t2) = parseMembers(ts, "object")
    (ObjectDef(name, members, vals, p), t2)

  // ── definitions ─────────────────────────────────────────────────────────────
  private def parseDef(ts0: List[Tok]): (Def, List[Tok]) =
    val p = posOf(ts0)
    val t0 = expectKw(ts0, "def")
    val (name, _, t1) = expectName(t0)
    val afterName = skipBrackets(t1)
    // A PARAMETERLESS `def` — `def empty: List[A] = Nil` — has no parameter clause at all. It was
    // 116 of 333 remaining refusals, the single largest cause, and every one of them came from the
    // standard library rather than from a test: `def empty:` is how a library writes a constant.
    //
    // `def f()` and `def f` differ in Scala — the second auto-applies on a bare reference — and
    // both parse to zero parameters here. The difference lives in the LOWERING, where a bare name
    // that resolves to a zero-arity def becomes a call.
    if !isPunct(peek(afterName), "(") then
      var ts0 = skipTypeAnn(afterName)
      // ABSTRACT: no `=`, so no body. Only meaningful inside a trait, and given a placeholder body
      // rather than an Option because every later phase then keeps ONE shape of `Def` to handle.
      if !isOp(peek(ts0), "=") then return (Def(name, Nil, Expr.Name("__abstract__", p), p), ts0)
      ts0 = expectOp(ts0, "=")
      val (body0, tEnd) = parseBody(ts0)
      return (Def(name, Nil, body0, p), tEnd)
    val t2 = expectPunct(afterName, "(")
    var params: List[Param] = Nil
    var ts = t2
    if !isPunct(peek(ts), ")") then
      var go = true
      while go do
        val (pn, pp, t) = expectName(ts)
        ts = skipTypeAnn(t)
        val (dflt, tD) = parseDefault(ts)
        ts = tD
        params = Param(pn, pp, dflt) :: params
        if isPunct(peek(ts), ",") then ts = ts.tail else go = false
    ts = expectPunct(ts, ")")
    ts = skipTypeAnn(ts)
    if !isOp(peek(ts), "=") then (Def(name, params.reverse, Expr.Name("__abstract__", p), p), ts)
    else
      ts = expectOp(ts, "=")
      val (body, t3) = parseBody(ts)
      (Def(name, params.reverse, body, p), t3)

  def parse(src: String): Program =
    var ts = Lexer.lex(src)
    var defs: List[Def] = Nil
    var top: List[Stmt] = Nil
    var classes: List[ClassDef] = Nil
    var objects: List[ObjectDef] = Nil
    var traits: List[TraitDef] = Nil
    var go = true
    while go do
      ts = skipLayout(ts)
      if peek(ts).isInstanceOf[Tok.TEof] then go = false
      // `sealed trait Shape` / `abstract class …` — a MODIFIER on the declaration that follows.
      // Dropped here, exactly as `override`/`final` are dropped inside a body. Without this the
      // word fell through to the expression parser and became a top-level statement reading an
      // unbound name: `sealed-traits` printed `(do (name "sealed"))` before its trait.
      else if (isId(peek(ts), "sealed") || isId(peek(ts), "abstract")) && ts.tail.nonEmpty &&
              (isId(peek(ts.tail), "trait") || isId(peek(ts.tail), "class") ||
               isId(peek(ts.tail), "sealed") || isId(peek(ts.tail), "abstract")) then
        ts = ts.tail
      // `extern def readFile(path: String): String` — the modifier on a HOST function declaration.
      // Same shape as `sealed` above and found the same way: the word fell through to the
      // expression parser and became a top-level statement reading an unbound name, so
      // `node-basic` printed `(do (name "extern"))` before its declaration. The body-less `def`
      // that follows already becomes `__abstract__`, which `Lower` reads at top level as "a host
      // function this lane does not implement" and drops.
      else if isId(peek(ts), "extern") && ts.tail.nonEmpty && isId(peek(ts.tail), "def") then
        ts = ts.tail
      // `type RightInt = Either[_, Int]` — a type ALIAS, consumed and discarded.
      //
      // Discarding is the whole of it, and it is not laziness: types are erased at Tier 0
      // (`specs/20-core-language.md` §2), which is why `skipType` exists and why `asInstanceOf` is
      // the identity in the executor. An alias names a type; with no types at run time it names
      // nothing, and every USE of it is already skipped by `skipTypeAnn`.
      //
      // Same failure shape as `sealed` and `extern` directly above, and found the same way: the
      // word fell through to the expression parser, became a top-level statement reading an unbound
      // name, and the file died at `unknown name 'type'` — a message about the KEYWORD, pointing at
      // a line whose actual content is a type. Three occurrences of one pattern now.
      //
      // NOT the type LAMBDA (`type Pair = [A] =>> (A, A)`, SSC3-7i): that one needs the generics
      // decision. `skipType` will consume its right-hand side too, but declaring that as support
      // would be claiming a feature on the strength of the parser not objecting.
      // The `=` is part of the TEST, not just of the consumption. A branch that matched on `type`
      // alone and then found no `=` would have to leave the tokens untouched, and this loop would
      // spin on them forever — the shape has to be recognised before anything is consumed.
      else if isId(peek(ts), "type") && typeAliasEq(ts.tail).isDefined then
        ts = skipType(typeAliasEq(ts.tail).get)
      else if isId(peek(ts), "def") then
        val (d, t) = parseDef(ts)
        defs = d :: defs
        ts = t
      else if isId(peek(ts), "case") && ts.tail.nonEmpty && isId(peek(ts.tail), "object") then
        // `case object X extends T` is a NULLARY CONSTRUCTOR — the same thing an enum's `case Red`
        // already produces — not an `object`, which at Tier 0 is a namespace. Measured: 116 of the
        // 123 cases in the top refusal bucket were one line, `case object SqlNull extends
        // SqliteValue`, in one imported module.
        val cp = posOf(ts)
        val (cn, _, t0) = expectName(ts.tail.tail)
        var t = skipBrackets(t0)
        val (parents, tParents) = parseParents(t)
        t = tParents
        if isPunct(peek(t), ":") then
          val (members, vs2, t2) = parseMembers(t.tail, "class")
          if vs2.nonEmpty then throw ParseFail(cp, "a `case object` member that is not a `def` is outside SSC3 core Tier 0")
          classes = ClassDef(cn, Nil, members, parents, cp) :: classes
          ts = t2
        else
          classes = ClassDef(cn, Nil, Nil, parents, cp) :: classes
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
        val (tr, t) = parseTrait(ts.tail, posOf(ts))
        traits = tr :: traits
        ts = t
      else if isId(peek(ts), "enum") then
        val (cs, t) = parseEnum(ts.tail)
        classes = cs.reverse ++ classes
        ts = t
      else
        // Not a `def`, so it is program body. Refusing here is what made 48 of the first 60 corpus
        // cases unreadable: a `.ssc` file is a script, and requiring every line to be inside a
        // definition was my assumption rather than the language's.
        val (sts, t) = parseStmt(ts)
        sts.foreach { st => top = st :: top }
        ts = t
    Program(defs.reverse, top.reverse, classes.reverse, objects.reverse, traits.reverse)
