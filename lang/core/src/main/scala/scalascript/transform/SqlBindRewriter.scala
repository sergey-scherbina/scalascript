package scalascript.transform

/** Lexer-level rewriter for `sql` fenced blocks (SPEC.md § 3.3.1).
 *
 *  Walks the source character-by-character, replacing every `${expr}`
 *  interpolation with a backend-specific placeholder (`?` for JDBC,
 *  `:bind<N>` for Spark SQL).  Returns the rewritten SQL alongside an
 *  ordered list of the expression source fragments — the consumer
 *  re-parses each fragment as a Scala expression at codegen time.
 *
 *  Design rules pinned by the SPEC:
 *
 *    - Every `${...}` becomes a bind parameter.  No exceptions.  No
 *      `$!{...}` escape, no `raw"..."` mode.  String substitution
 *      into SQL is intentionally absent from the language.
 *    - The rewriter is *lexer-level*, not SQL-aware.  It does not parse
 *      SQL, does not track string literal boundaries, does not
 *      distinguish "safe" from "unsafe" syntactic positions.  An
 *      `${...}` inside a `'...'` string literal *also* binds — that's
 *      the entire point.
 *    - Brace-balanced expressions: `${m("k")}`, `${ if x then a else b }`,
 *      `${ { val n = 5; n * 2 } }` all parse to a single bind.  The
 *      scanner counts `{` / `}` depth, ignoring braces inside Scala
 *      string literals (`"..."` and `'...'`) within the expression.
 *    - `$$` is an escape — a literal `$` in the emitted SQL.  Any
 *      other `$x` (`$` not followed by `{` and not doubled) is a
 *      diagnostic: forbidden so a future syntax can land without
 *      breaking existing code.
 *
 *  The output `binds` list contains the raw expression source for each
 *  `${...}` in document order.  `binds(0)` corresponds to the first
 *  placeholder (`?` or `:bind0`), `binds(1)` to the second, etc.  The
 *  caller is responsible for parsing each fragment back to an `ast.Term`
 *  / `ir.IrExpr` and emitting target-specific bind code. */
object SqlBindRewriter:

  /** Replace `${expr}` interpolations in `src` with positional
   *  `?` placeholders.  Suitable for JDBC `PreparedStatement` consumers
   *  (`backend-sql-runtime` per v1.26). */
  def rewriteJdbc(src: String): SqlBindRewriter.Result =
    rewrite(src, _ => "?")

  /** Replace `${expr}` interpolations in `src` with `:bind<N>` named
   *  placeholders.  Suitable for Spark SQL 3.4+ which accepts a
   *  `Map[String, Object]` of named parameters via
   *  `spark.sql(query, args)`.  Names are stable across emissions —
   *  the n-th interpolation always becomes `:bind<n>`. */
  def rewriteSparkSql(src: String): SqlBindRewriter.Result =
    rewrite(src, n => s":bind$n")

  case class Result(sql: String, binds: List[String])

  /** Diagnostic raised when the SQL source contains a malformed
   *  interpolation (unbalanced `${`, empty `${}`, single `$` not part
   *  of `$$` and not followed by `{`).  Surfaces to the user as a
   *  compile-time error from the consuming backend. */
  case class RewriteError(message: String, position: Int) extends RuntimeException(message)

  /** Generic rewrite: caller supplies the placeholder function
   *  `placeholder(n)` which returns the textual placeholder for the
   *  n-th bind (n starts at 0). */
  def rewrite(src: String, placeholder: Int => String): Result =
    val out   = StringBuilder()
    val binds = scala.collection.mutable.ListBuffer.empty[String]
    var i     = 0
    val len   = src.length
    while i < len do
      val c = src.charAt(i)
      if c == '$' then
        if i + 1 < len && src.charAt(i + 1) == '$' then
          // $$ escape — literal $ in the output.
          out.append('$')
          i += 2
        else if i + 1 < len && src.charAt(i + 1) == '{' then
          val close = findMatchingClose(src, i + 2)
          if close < 0 then
            throw RewriteError(
              s"unterminated $${...} interpolation starting at offset $i",
              i
            )
          val expr = src.substring(i + 2, close).trim
          if expr.isEmpty then
            throw RewriteError(s"empty $${} at offset $i", i)
          out.append(placeholder(binds.size))
          binds += expr
          i = close + 1
        else
          throw RewriteError(
            s"bare '$$' at offset $i — use $$$$ for a literal $$ or " +
              "$${expr} for a bind parameter",
            i
          )
      else
        out.append(c)
        i += 1
    Result(out.toString, binds.toList)

  /** Split `src` on `;` that lie outside any `${...}` interpolation block.
   *  Used by `transaction` fenced blocks to recover individual SQL statements.
   *  Returns non-empty, trimmed fragments; a trailing `;` produces no extra entry.
   *
   *  `$$` sequences are treated as a literal `$` (not an interpolation open),
   *  consistent with `rewrite`.  Malformed `${` (unterminated) causes the
   *  remainder of the source to be appended verbatim to the current fragment
   *  rather than silently discarding it — the downstream `rewriteJdbc` call
   *  on each fragment will surface the diagnostic. */
  def splitStatements(src: String): List[String] =
    val stmts = scala.collection.mutable.ListBuffer.empty[String]
    val cur   = StringBuilder()
    var i     = 0
    val len   = src.length
    while i < len do
      val c = src.charAt(i)
      if c == '$' && i + 1 < len && src.charAt(i + 1) == '{' then
        val close = findMatchingClose(src, i + 2)
        if close < 0 then
          cur.append(src.substring(i)); i = len
        else
          cur.append(src.substring(i, close + 1)); i = close + 1
      else if c == ';' then
        val stmt = cur.toString.trim
        if stmt.nonEmpty then stmts += stmt
        cur.clear(); i += 1
      else
        cur.append(c); i += 1
    val last = cur.toString.trim
    if last.nonEmpty then stmts += last
    stmts.toList

  /** Scan forward from `from` (the position just after `${`) until the
   *  matching `}` is found.  Counts brace depth, but skips over braces
   *  that appear inside Scala string literals (`"..."`, `'...'`,
   *  triple-quoted `"""..."""`) within the expression so that a `}`
   *  embedded in a Scala string doesn't prematurely close.  Returns
   *  the index of the matching `}` or -1 if not found. */
  private def findMatchingClose(src: String, from: Int): Int =
    var depth = 1
    var i     = from
    val len   = src.length
    while i < len do
      val c = src.charAt(i)
      c match
        case '{' => depth += 1
        case '}' =>
          depth -= 1
          if depth == 0 then return i
        case '"' =>
          // Detect Scala triple-quoted strings ("""...""").
          if i + 2 < len && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"' then
            i += 3
            // scan until closing """
            while i + 2 < len && !(src.charAt(i) == '"' && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') do
              i += 1
            if i + 2 < len then i += 2 // step onto last "; loop increment moves past
          else
            // single-line "..."
            i += 1
            while i < len && src.charAt(i) != '"' do
              if src.charAt(i) == '\\' && i + 1 < len then i += 2
              else i += 1
        case '\'' =>
          // Single-char or char literal — '...' may contain escape.
          i += 1
          while i < len && src.charAt(i) != '\'' do
            if src.charAt(i) == '\\' && i + 1 < len then i += 2
            else i += 1
        case _ => ()
      i += 1
    -1
