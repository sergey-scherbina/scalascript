package scalascript.compiler.plugin.scljetjdbc

import java.sql.{Connection, DatabaseMetaData, ParameterMetaData, ResultSet, Types}
import ProxySupport.*

object ScljetVersion:
  val ProductName = "SclJet"
  val DriverName  = "scljet-jdbc"
  val Version     = "0.1.0"
  val Major       = 0
  val Minor       = 1

/** Minimal identifying `DatabaseMetaData` + `ParameterMetaData` shims
 *  (specs/scljet-jdbc.md §"DatabaseMetaData (minimal)").  Everything outside the
 *  identifying subset throws `SQLFeatureNotSupportedException`. */
object ScljetMeta:
  def databaseMetaData(state: ScljetConnectionState, connProxy: Connection): DatabaseMetaData =
    ProxySupport.proxy(classOf[DatabaseMetaData], DatabaseMetaHandler(state, connProxy))

  def parameterMetaData(count: Int): ParameterMetaData =
    ProxySupport.proxy(classOf[ParameterMetaData], ParameterMetaHandler(count))

final class DatabaseMetaHandler(state: ScljetConnectionState, connProxy: Connection)
    extends ProxyHandler("DatabaseMetaData"):
  def dispatch(proxy: AnyRef, name: String, args: Array[AnyRef]): AnyRef = name match
    case "getDatabaseProductName"    => ScljetVersion.ProductName
    case "getDatabaseProductVersion" => ScljetVersion.Version
    case "getDriverName"             => ScljetVersion.DriverName
    case "getDriverVersion"          => ScljetVersion.Version
    case "getDriverMajorVersion"     => boxI(ScljetVersion.Major)
    case "getDriverMinorVersion"     => boxI(ScljetVersion.Minor)
    case "getDatabaseMajorVersion"   => boxI(ScljetVersion.Major)
    case "getDatabaseMinorVersion"   => boxI(ScljetVersion.Minor)
    case "getJDBCMajorVersion"       => boxI(4)
    case "getJDBCMinorVersion"       => boxI(2)
    case "getURL"                    => state.url
    case "getConnection"             => connProxy
    case "getUserName"               => ""
    case "isReadOnly"                => boxB(state.readOnly)
    case "supportsTransactions"      => boxB(true)
    case "getDefaultTransactionIsolation" => boxI(Connection.TRANSACTION_SERIALIZABLE)
    case "supportsTransactionIsolationLevel" => boxB(argInt(args, 0) == Connection.TRANSACTION_SERIALIZABLE)
    case "supportsResultSetType"     => boxB(argInt(args, 0) == ResultSet.TYPE_FORWARD_ONLY)
    case "supportsResultSetConcurrency" =>
      boxB(argInt(args, 0) == ResultSet.TYPE_FORWARD_ONLY && argInt(args, 1) == ResultSet.CONCUR_READ_ONLY)
    case "getIdentifierQuoteString"  => "\""
    case "getCatalogSeparator"       => ""
    case "getSearchStringEscape"     => "\\"
    case "getSQLKeywords"            => ""
    case "storesLowerCaseIdentifiers" => boxB(false)
    case "storesUpperCaseIdentifiers" => boxB(false)
    case "storesMixedCaseIdentifiers" => boxB(true)
    case "supportsBatchUpdates"      => boxB(true)
    case "jdbcCompliant"             => boxB(false)
    // ── catalog queries ─────────────────────────────────────────────────────
    case "getTables"      => ScljetCatalogMeta.getTables(state, strOrNull(args, 2), strArrOrNull(args, 3))
    case "getColumns"     => ScljetCatalogMeta.getColumns(state, strOrNull(args, 2), strOrNull(args, 3))
    case "getTableTypes"  => ScljetCatalogMeta.getTableTypes()
    case "getCatalogs"    => ScljetCatalogMeta.emptyCatalogs()
    case "getSchemas"     => ScljetCatalogMeta.emptySchemas()
    case _                           => throw nse(name)

  private def strOrNull(args: Array[AnyRef], k: Int): String | Null =
    if k < args.length then args(k).asInstanceOf[String | Null] else null

  private def strArrOrNull(args: Array[AnyRef], k: Int): Array[String] | Null =
    if k < args.length then args(k).asInstanceOf[Array[String] | Null] else null

/** The `DatabaseMetaData` catalog queries, in the exact row shapes the JDBC
 *  javadoc mandates (`getTables` = 10 columns, `getColumns` = 24).  Rows come
 *  from [[ScljetCatalog]] and are served over [[ScljetStaticResultSet]].
 *
 *  Everything here reports catalog and schema as NULL: a scljet connection is a
 *  single image with one anonymous namespace, so there is nothing to name. */
object ScljetCatalogMeta:
  private def s(v: String | Null): AnyRef = v.asInstanceOf[AnyRef]
  private def i(v: Int): AnyRef = Integer.valueOf(v)

  private val TableColumns: List[StaticColumn] = List(
    StaticColumn("TABLE_CAT", Types.VARCHAR),
    StaticColumn("TABLE_SCHEM", Types.VARCHAR),
    StaticColumn("TABLE_NAME", Types.VARCHAR),
    StaticColumn("TABLE_TYPE", Types.VARCHAR),
    StaticColumn("REMARKS", Types.VARCHAR),
    StaticColumn("TYPE_CAT", Types.VARCHAR),
    StaticColumn("TYPE_SCHEM", Types.VARCHAR),
    StaticColumn("TYPE_NAME", Types.VARCHAR),
    StaticColumn("SELF_REFERENCING_COL_NAME", Types.VARCHAR),
    StaticColumn("REF_GENERATION", Types.VARCHAR),
  )

  /** `getTables` — ordered by TABLE_TYPE then TABLE_NAME, per the JDBC contract. */
  def getTables(state: ScljetConnectionState, tableNamePattern: String | Null, types: Array[String] | Null): ResultSet =
    state.checkOpen()
    val wanted = Option(types.asInstanceOf[Array[String] | Null]).map(_.toSet)
    val rows = ScljetCatalog.tables(state.currentImage)
      .filter(t => ScljetCatalog.matchesPattern(tableNamePattern, t.name))
      .filter(t => wanted.forall(_.contains(t.kind)))
      .sortBy(t => (t.kind, t.name))
      .map(t => List[AnyRef](null, null, s(t.name), s(t.kind), s(""), null, null, null, null, null))
    ScljetStaticResultSet.make(TableColumns, rows)

  private val ColumnColumns: List[StaticColumn] = List(
    StaticColumn("TABLE_CAT", Types.VARCHAR),
    StaticColumn("TABLE_SCHEM", Types.VARCHAR),
    StaticColumn("TABLE_NAME", Types.VARCHAR),
    StaticColumn("COLUMN_NAME", Types.VARCHAR),
    StaticColumn("DATA_TYPE", Types.INTEGER),
    StaticColumn("TYPE_NAME", Types.VARCHAR),
    StaticColumn("COLUMN_SIZE", Types.INTEGER),
    StaticColumn("BUFFER_LENGTH", Types.INTEGER),
    StaticColumn("DECIMAL_DIGITS", Types.INTEGER),
    StaticColumn("NUM_PREC_RADIX", Types.INTEGER),
    StaticColumn("NULLABLE", Types.INTEGER),
    StaticColumn("REMARKS", Types.VARCHAR),
    StaticColumn("COLUMN_DEF", Types.VARCHAR),
    StaticColumn("SQL_DATA_TYPE", Types.INTEGER),
    StaticColumn("SQL_DATETIME_SUB", Types.INTEGER),
    StaticColumn("CHAR_OCTET_LENGTH", Types.INTEGER),
    StaticColumn("ORDINAL_POSITION", Types.INTEGER),
    StaticColumn("IS_NULLABLE", Types.VARCHAR),
    StaticColumn("SCOPE_CATALOG", Types.VARCHAR),
    StaticColumn("SCOPE_SCHEMA", Types.VARCHAR),
    StaticColumn("SCOPE_TABLE", Types.VARCHAR),
    StaticColumn("SOURCE_DATA_TYPE", Types.SMALLINT),
    StaticColumn("IS_AUTOINCREMENT", Types.VARCHAR),
    StaticColumn("IS_GENERATED", Types.VARCHAR),
  )

  /** `getColumns` — ordered by TABLE_NAME then ORDINAL_POSITION.
   *
   *  Nullability is reported as `columnNullableUnknown`/`""` rather than parsed
   *  out of the NOT NULL constraints: the engine does not enforce them, so
   *  claiming to know would be a lie a client could act on. */
  def getColumns(state: ScljetConnectionState, tableNamePattern: String | Null, columnNamePattern: String | Null): ResultSet =
    state.checkOpen()
    val image = state.currentImage
    val rows = ScljetCatalog.tables(image)
      .filter(t => ScljetCatalog.matchesPattern(tableNamePattern, t.name))
      .sortBy(_.name)
      .flatMap { t =>
        ScljetCatalog.columns(t).zipWithIndex
          .filter((c, _) => ScljetCatalog.matchesPattern(columnNamePattern, c.name))
          .map { (c, idx) =>
            List[AnyRef](
              null,                                        // TABLE_CAT
              null,                                        // TABLE_SCHEM
              s(t.name),                                   // TABLE_NAME
              s(c.name),                                   // COLUMN_NAME
              i(c.sqlType),                                // DATA_TYPE
              s(c.declaredType),                           // TYPE_NAME
              i(0),                                        // COLUMN_SIZE — SQLite is dynamically typed
              null,                                        // BUFFER_LENGTH (unused)
              i(0),                                        // DECIMAL_DIGITS
              i(10),                                       // NUM_PREC_RADIX
              i(DatabaseMetaData.columnNullableUnknown),   // NULLABLE
              s(""),                                       // REMARKS
              null,                                        // COLUMN_DEF
              i(0),                                        // SQL_DATA_TYPE (unused)
              i(0),                                        // SQL_DATETIME_SUB (unused)
              i(0),                                        // CHAR_OCTET_LENGTH
              i(idx + 1),                                  // ORDINAL_POSITION (1-based)
              s(""),                                       // IS_NULLABLE — "" = unknown
              null, null, null, null,                      // SCOPE_* / SOURCE_DATA_TYPE
              s(""),                                       // IS_AUTOINCREMENT — "" = unknown
              s(""),                                       // IS_GENERATED — "" = unknown
            )
          }
      }
    ScljetStaticResultSet.make(ColumnColumns, rows)

  def getTableTypes(): ResultSet =
    ScljetStaticResultSet.make(
      List(StaticColumn("TABLE_TYPE", Types.VARCHAR)),
      List(List[AnyRef]("TABLE"), List[AnyRef]("VIEW")))

  def emptyCatalogs(): ResultSet =
    ScljetStaticResultSet.make(List(StaticColumn("TABLE_CAT", Types.VARCHAR)), Nil)

  def emptySchemas(): ResultSet =
    ScljetStaticResultSet.make(
      List(StaticColumn("TABLE_SCHEM", Types.VARCHAR), StaticColumn("TABLE_CATALOG", Types.VARCHAR)), Nil)

final class ParameterMetaHandler(count: Int) extends ProxyHandler("ParameterMetaData"):
  def dispatch(proxy: AnyRef, name: String, args: Array[AnyRef]): AnyRef = name match
    case "getParameterCount"     => boxI(count)
    case "getParameterMode"      => boxI(ParameterMetaData.parameterModeIn)
    case "isNullable"            => boxI(ParameterMetaData.parameterNullableUnknown)
    case "isSigned"              => boxB(true)
    case "getParameterType"      => boxI(java.sql.Types.OTHER)
    case "getParameterTypeName"  => ""
    case "getParameterClassName" => "java.lang.Object"
    case "getPrecision"          => boxI(0)
    case "getScale"              => boxI(0)
    case _                       => throw nse(name)
