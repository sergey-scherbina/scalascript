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

  /** An index over `table`: `unique` comes from `CREATE UNIQUE INDEX`, `columns`
   *  from its `ON t(a, b)` list (in key order). */
  final case class IndexDef(name: String, table: String, unique: Boolean, columns: List[String])

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

  /** The indexes of an image, in schema order.
   *
   *  `internal` entries (SQLite's own `sqlite_autoindex_*`, created for a
   *  UNIQUE/PRIMARY KEY constraint) are skipped: they have no `CREATE INDEX`
   *  text to read columns from, and the reference driver does not report them
   *  either. */
  def indexes(image: Value): List[IndexDef] =
    schemaEntries(image).flatMap { e =>
      ScljetEngine.field(e, "kind") match
        case Value.InstanceV("SchemaIndex", _) =>
          val internal = ScljetEngine.field(e, "internal") match
            case Value.BoolV(b) => b
            case _              => false
          optionalText(ScljetEngine.field(e, "sql")).filter(_ => !internal).map { sql =>
            IndexDef(
              name    = decodedText(ScljetEngine.field(e, "name")),
              table   = decodedText(ScljetEngine.field(e, "tableName")),
              unique  = isUniqueIndex(sql),
              columns = indexColumns(sql))
          }
        case _ => None
    }

  /** `CREATE UNIQUE INDEX …` — the qualifier sits between CREATE and INDEX. */
  private def isUniqueIndex(sql: String): Boolean =
    tokenize(sql).map((_, text) => text.toUpperCase).takeWhile(_ != "INDEX").contains("UNIQUE")

  /** The `ON t(a, b)` key list: the idents at paren-depth 1 of the FIRST paren
   *  group, which for a CREATE INDEX is the key list. */
  private def indexColumns(sql: String): List[String] =
    val body = tokenize(sql).dropWhile((kind, _) => kind != "lparen").drop(1)
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    var depth = 1
    var expectName = true
    var rest = body
    while rest.nonEmpty && depth > 0 do
      val (kind, text) = rest.head
      if kind == "lparen" then depth += 1
      else if kind == "rparen" then depth -= 1
      else if kind == "comma" && depth == 1 then expectName = true
      else if depth == 1 && expectName && kind == "ident" then
        out += text; expectName = false
      rest = rest.tail
    out.toList

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

  /** A column definition before interpretation: its name and the words that
   *  follow it (declared type + any column constraints). */
  private final case class RawColumn(name: String, words: List[String])

  /** Columns of `table`, in declaration order — parsed from the `CREATE TABLE`
   *  text the schema entry already carries. */
  def columns(table: TableDef): List[ColumnDef] =
    table.sql match
      case None      => Nil
      case Some(sql) => parseTable(sql)._1.map(rc => columnDef(rc.name, rc.words))

  /** The primary-key columns of `table` in KEY_SEQ order; empty when it has no
   *  PK.  SQLite spells a PK two ways and both must work: on the column
   *  (`id INTEGER PRIMARY KEY`) or as a table constraint (`PRIMARY KEY (a, b)`,
   *  optionally named — `CONSTRAINT pk PRIMARY KEY (a, b)`).  A table
   *  constraint wins; a table cannot have both. */
  def primaryKeyColumns(table: TableDef): List[String] =
    table.sql match
      case None => Nil
      case Some(sql) =>
        val (cols, tablePk) = parseTable(sql)
        if tablePk.nonEmpty then tablePk
        else cols.filter(rc => mentionsPrimaryKey(rc.words)).map(_.name)

  /** `PRIMARY` immediately followed by `KEY`, anywhere in a definition's words. */
  private def mentionsPrimaryKey(words: List[String]): Boolean =
    words.map(_.toUpperCase).sliding(2).exists {
      case List("PRIMARY", "KEY") => true
      case _                      => false
    }

  /** Mirrors the engine's `tableColumns` scan, additionally keeping each
   *  column's post-name words and the table-level PRIMARY KEY column list.
   *  Returns (column definitions, table-level PK columns). */
  private def parseTable(sql: String): (List[RawColumn], List[String]) =
    val toks = tokenize(sql)
    // advance past the first '('
    val body = toks.dropWhile((kind, _) => kind != "lparen").drop(1)
    val out  = scala.collection.mutable.ArrayBuffer.empty[RawColumn]
    var tablePk: List[String] = Nil
    var depth = 1
    var expectName = true
    var name: String | Null = null
    var typeWords = scala.collection.mutable.ArrayBuffer.empty[String]
    // Non-null while inside a TABLE-level constraint: its depth-1 words (which
    // tell PRIMARY KEY from FOREIGN KEY / UNIQUE / CHECK) and its parenthesised
    // idents (the key list).
    var constraintWords: scala.collection.mutable.ArrayBuffer[String] | Null = null
    var constraintArgs = scala.collection.mutable.ArrayBuffer.empty[String]

    def flush(): Unit =
      val cw = constraintWords
      if cw != null then
        if mentionsPrimaryKey(cw.toList) then tablePk = constraintArgs.toList
      else if name != null then out += RawColumn(name.nn, typeWords.toList)
      name = null
      typeWords = scala.collection.mutable.ArrayBuffer.empty
      constraintWords = null
      constraintArgs = scala.collection.mutable.ArrayBuffer.empty

    var rest = body
    while rest.nonEmpty && depth > 0 do
      val (kind, text) = rest.head
      if kind == "lparen" then
        depth += 1
        if constraintWords == null && name != null then typeWords += "("
      else if kind == "rparen" then
        depth -= 1
        if depth > 0 && constraintWords == null && name != null then typeWords += ")"
      else if kind == "comma" && depth == 1 then
        flush(); expectName = true
      else if depth == 1 && expectName && kind == "ident" then
        // A table-level constraint (PRIMARY KEY(…), FOREIGN KEY…) is not a column.
        if TableConstraintStarters.contains(text.toUpperCase) then
          constraintWords = scala.collection.mutable.ArrayBuffer(text)
        else name = text
        expectName = false
      else
        val cw = constraintWords
        if cw != null then
          if depth == 1 then cw += text
          else if depth == 2 && kind == "ident" then constraintArgs += text
        else if name != null then typeWords += text
      rest = rest.tail
    flush()
    (out.toList, tablePk)

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
