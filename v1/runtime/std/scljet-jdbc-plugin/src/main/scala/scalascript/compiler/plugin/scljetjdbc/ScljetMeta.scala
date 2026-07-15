package scalascript.compiler.plugin.scljetjdbc

import java.sql.{Connection, DatabaseMetaData, ParameterMetaData, ResultSet}
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
    case _                           => throw nse(name)

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
