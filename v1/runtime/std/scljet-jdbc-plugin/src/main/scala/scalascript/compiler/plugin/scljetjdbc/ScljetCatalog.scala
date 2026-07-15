package scalascript.compiler.plugin.scljetjdbc

import scalascript.interpreter.Value

import java.sql.{SQLException, Types}

/** The database catalog behind `DatabaseMetaData.getTables`/`getColumns`.
 *
 *  The engine exports no "list the tables" function, and this lane must not
 *  change the engine, so the schema is read structurally from the JVM: the
 *  exported `openReadonly(ImageVfs(image), …)` yields a `ReadonlyDatabase` whose
 *  `schema.entries` are the `sqlite_schema` rows (`scljet/schema.ssc`).
 *
 *  Two bridge details worth keeping in mind (both cost a debugging round once):
 *
 *   - `ImageVfs` must be built as `InstanceV("ImageVfs", Map("main" -> image))`.
 *     `Value.singleValue` names the field `value`, but `ImageVfs`'s field is
 *     `main` — and a missing field does not fail loudly: the interpreter falls
 *     back to the GLOBAL `main`, so `openReadonly` dies deep inside with
 *     "No method 'length' on NativeFnV(<native:main>)".  A hand-built
 *     `InstanceV` does dispatch its trait methods correctly.
 *   - Column names and types are parsed from each entry's `CREATE TABLE` text
 *     using the ENGINE's own `tokenize`, mirroring `tableColumns`
 *     (`scljet/sql.ssc:1059`) — the first ident at paren-depth 1 after each comma
 *     is a column name.  Reusing the engine lexer is what keeps this from
 *     drifting into a second, subtly different SQL parser; `ScljetCatalogTest`
 *     asserts the names equal the engine's own `imageTableColumns`.
 */
object ScljetCatalog:

  /** A `sqlite_schema` row we expose: `kind` is already the JDBC TABLE_TYPE. */
  final case class TableDef(name: String, kind: String, sql: Option[String])

  /** A column of a table: its name, its declared type text (`""` when the
   *  CREATE TABLE gives none) and the `java.sql.Types` its affinity implies. */
  final case class ColumnDef(name: String, declaredType: String, sqlType: Int)

  /** Constraint keywords that end a declared type in a column definition. */
  private val ConstraintKeywords: Set[String] =
    Set("CONSTRAINT", "PRIMARY", "NOT", "NULL", "UNIQUE", "CHECK", "DEFAULT",
        "COLLATE", "REFERENCES", "GENERATED", "AS", "AUTOINCREMENT")

  /** Words that start a TABLE-level constraint, i.e. not a column definition. */
  private val TableConstraintStarters: Set[String] =
    Set("CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN")

  // ── schema access ─────────────────────────────────────────────────────────

  /** The tables and views of an image, in schema order. */
  def tables(image: Value): List[TableDef] =
    schemaEntries(image).flatMap { e =>
      val kind = ScljetEngine.field(e, "kind") match
        case Value.InstanceV("SchemaTable", _) => Some("TABLE")
        case Value.InstanceV("SchemaView", _)  => Some("VIEW")
        case _                                 => None   // indexes/triggers are not tables
      val internal = ScljetEngine.field(e, "internal") match
        case Value.BoolV(b) => b
        case _              => false
      kind.filter(_ => !internal).map { k =>
        TableDef(decodedText(ScljetEngine.field(e, "name")), k, optionalText(ScljetEngine.field(e, "sql")))
      }
    }

  private def schemaEntries(image: Value): List[Value] =
    val vfs  = Value.InstanceV("ImageVfs", Map("main" -> image))
    val opts = ScljetEngine.call("sqlOptions")
    val res  = ScljetEngine.call("openReadonly", vfs, Value.StringV("image.db"), opts)
    val db   = ScljetEngine.unwrapEither(res, m => s"cannot read the schema: $m")
    ScljetEngine.field(ScljetEngine.field(db, "schema"), "entries") match
      case Value.ListV(items) => items
      case other              => throw SQLException(s"scljet JDBC: schema entries is not a list: ${Value.show(other)}")

  /** `DecodedText` → String, decoded from its code points on the JVM. */
  private def decodedText(v: Value): String =
    ScljetEngine.field(v, "codePoints") match
      case Value.ListV(cps) =>
        val sb = StringBuilder()
        cps.foreach(cp => sb.appendAll(Character.toChars(ScljetEngine.asLong(cp).toInt)))
        sb.toString
      case other => throw SQLException(s"scljet JDBC: expected DecodedText, got ${Value.show(other)}")

  /** The interpreter models `Option` as `OptionV(inner | null)` — NOT as an
   *  `InstanceV("Some"/"None")`, which would silently match nothing here. */
  private def optionalText(v: Value): Option[String] = v match
    case Value.OptionV(inner) => Option(inner).map(decodedText)
    case other                => throw SQLException(s"scljet JDBC: expected Option, got ${Value.show(other)}")

  // ── column definitions ────────────────────────────────────────────────────

  /** Columns of `table`, in declaration order — parsed from the `CREATE TABLE`
   *  text the schema entry already carries. */
  def columns(table: TableDef): List[ColumnDef] =
    table.sql match
      case None      => Nil
      case Some(sql) => parseColumnDefs(sql)

  /** Mirrors the engine's `tableColumns` scan, additionally keeping each
   *  column's post-name type words. */
  private def parseColumnDefs(sql: String): List[ColumnDef] =
    val toks = tokenize(sql)
    // advance past the first '('
    val body = toks.dropWhile((kind, _) => kind != "lparen").drop(1)
    val out  = scala.collection.mutable.ArrayBuffer.empty[ColumnDef]
    var depth = 1
    var expectName = true
    var name: String | Null = null
    var typeWords = scala.collection.mutable.ArrayBuffer.empty[String]
    var skipDef = false   // inside a table-level constraint, not a column

    def flush(): Unit =
      if name != null && !skipDef then out += columnDef(name.nn, typeWords.toList)
      name = null; typeWords = scala.collection.mutable.ArrayBuffer.empty; skipDef = false

    var rest = body
    while rest.nonEmpty && depth > 0 do
      val (kind, text) = rest.head
      if kind == "lparen" then
        depth += 1
        if name != null && !skipDef then typeWords += "("
      else if kind == "rparen" then
        depth -= 1
        if depth > 0 && name != null && !skipDef then typeWords += ")"
      else if kind == "comma" && depth == 1 then
        flush(); expectName = true
      else if depth == 1 && expectName && kind == "ident" then
        // A table-level constraint (PRIMARY KEY(…), FOREIGN KEY…) is not a column.
        if TableConstraintStarters.contains(text.toUpperCase) then skipDef = true
        else name = text
        expectName = false
      else if name != null && !skipDef then
        typeWords += text
      rest = rest.tail
    flush()
    out.toList

  private def columnDef(name: String, words: List[String]): ColumnDef =
    val declared = declaredType(words)
    ColumnDef(name, declared, sqlTypeOf(declared))

  /** The declared type is everything from the column name up to the first
   *  constraint keyword (SQLite allows a multi-word type: `UNSIGNED BIG INT`). */
  private def declaredType(words: List[String]): String =
    val typeParts = words.takeWhile(w => !ConstraintKeywords.contains(w.toUpperCase))
    // Re-join: words are space-separated (`UNSIGNED BIG INT`), but nothing hugs a
    // paren or a comma, so `DECIMAL ( 10 , 2 )` renders as `DECIMAL(10,2)`.
    val sb = StringBuilder()
    typeParts.foreach { w =>
      val tight = w == "(" || w == ")" || w == "," || (sb.nonEmpty && (sb.last == '(' || sb.last == ','))
      if sb.nonEmpty && !tight then sb.append(' ')
      sb.append(w)
    }
    sb.toString

  /** SQLite type-affinity rules (SQLite docs §"Determination of column
   *  affinity"), applied in order, then mapped to `java.sql.Types`. */
  def sqlTypeOf(declared: String): Int =
    val t = declared.toUpperCase
    if t.contains("INT") then Types.BIGINT
    else if t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT") then Types.VARCHAR
    else if t.contains("BLOB") || t.isEmpty then Types.BLOB
    else if t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB") then Types.DOUBLE
    else Types.NUMERIC

  /** The engine's own lexer — reused so this never becomes a second SQL parser.
   *
   *  `Token(kind, text, num, …)` keeps a numeric literal's value in `num` and
   *  leaves `text` EMPTY, so a type argument (`VARCHAR(255)`) has to be read
   *  from `num` or it silently renders as `VARCHAR()`. */
  private def tokenize(sql: String): List[(String, String)] =
    val res = ScljetEngine.call("tokenize", Value.StringV(sql))
    ScljetEngine.unwrapEither(res, m => s"cannot tokenize the schema SQL: $m") match
      case Value.ListV(items) =>
        items.map { t =>
          val kind = ScljetEngine.asString(ScljetEngine.field(t, "kind"))
          val text = ScljetEngine.asString(ScljetEngine.field(t, "text"))
          val body = if kind == "num" then ScljetEngine.asLong(ScljetEngine.field(t, "num")).toString else text
          (kind, body)
        }
      case other => throw SQLException(s"scljet JDBC: tokenize returned ${Value.show(other)}")

  // ── JDBC pattern matching ─────────────────────────────────────────────────

  /** A JDBC catalog pattern: `%` = any run, `_` = one char, `null` = match all.
   *  Matching is case-insensitive, as SQLite identifiers are. */
  def matchesPattern(pattern: String | Null, value: String): Boolean =
    if pattern == null then true
    else value.toUpperCase.matches(patternToRegex(pattern.nn.toUpperCase))

  private def patternToRegex(pattern: String): String =
    val sb = StringBuilder()
    var i = 0
    while i < pattern.length do
      pattern.charAt(i) match
        case '%'  => sb.append(".*")
        case '_'  => sb.append('.')
        case '\\' if i + 1 < pattern.length =>   // getSearchStringEscape() == "\"
          i += 1; sb.append(java.util.regex.Pattern.quote(pattern.charAt(i).toString))
        case c    => sb.append(java.util.regex.Pattern.quote(c.toString))
      i += 1
    sb.toString
