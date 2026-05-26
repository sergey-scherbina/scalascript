package scalascript.sql

import java.sql.Connection
import java.time.Instant
import scalascript.typeddata.{DecodeError, JsonValue, ObjectCodec, ObjectValue}

final case class Stored[A](
    key:       String,
    value:     Option[A],
    version:   Long,
    updatedAt: Instant,
    deleted:   Boolean
)

final case class ObjectStoreConflict(store: String, key: String, expectedVersion: Option[Long], actualVersion: Option[Long])
    extends RuntimeException(
      s"object store conflict for $store/$key: expected ${expectedVersion.fold("missing")(_.toString)}, actual ${actualVersion.fold("missing")(_.toString)}"
    )

trait ObjectStoreBackend:
  def get[A](store: String, key: String)(using ObjectCodec[A]): Option[Stored[A]]
  def put[A](store: String, value: A, key: Option[String] = None, expectedVersion: Option[Long] = None)(using ObjectCodec[A]): Stored[A]
  def delete(store: String, key: String, expectedVersion: Option[Long] = None): Stored[Nothing]
  def changes[A](store: String, sinceVersion: Long, limit: Int = 100)(using ObjectCodec[A]): Vector[Stored[A]]

object ObjectStoreRuntime:
  val DefaultTable = "ssc_object_store"

  def ensureSchema(conn: Connection, table: String = DefaultTable): Unit =
    val tableSql = SqlRuntime.validateIdentifierPath(table, "object store table")
    SqlRuntime.execute(conn,
      s"""CREATE TABLE IF NOT EXISTS $tableSql (
         |  store_name VARCHAR(255) NOT NULL,
         |  object_key VARCHAR(1024) NOT NULL,
         |  value_json VARCHAR(1048576) NOT NULL,
         |  version BIGINT NOT NULL,
         |  updated_at VARCHAR(64) NOT NULL,
         |  deleted BOOLEAN NOT NULL DEFAULT FALSE,
         |  PRIMARY KEY (store_name, object_key)
         |)""".stripMargin,
      Nil)
    ()

  def jdbc(conn: Connection, table: String = DefaultTable): ObjectStoreBackend =
    new JdbcObjectStoreBackend(conn, table)

  def get[A](conn: Connection, store: String, key: String, table: String = DefaultTable)(using ObjectCodec[A]): Option[A] =
    getStored[A](conn, store, key, table).flatMap(_.value)

  def getStored[A](conn: Connection, store: String, key: String, table: String = DefaultTable)(using ObjectCodec[A]): Option[Stored[A]] =
    jdbc(conn, table).get[A](store, key)

  def put[A](
      conn: Connection,
      store: String,
      value: A,
      key: Option[String] = None,
      expectedVersion: Option[Long] = None,
      table: String = DefaultTable
  )(using ObjectCodec[A]): Stored[A] =
    jdbc(conn, table).put[A](store, value, key, expectedVersion)

  def delete(
      conn: Connection,
      store: String,
      key: String,
      expectedVersion: Option[Long] = None,
      table: String = DefaultTable
  ): Stored[Nothing] =
    jdbc(conn, table).delete(store, key, expectedVersion)

  def all[A](conn: Connection, store: String, table: String = DefaultTable)(using ObjectCodec[A]): Vector[A] =
    changes[A](conn, store, sinceVersion = 0L, limit = Int.MaxValue, table).flatMap(_.value)

  def changes[A](
      conn: Connection,
      store: String,
      sinceVersion: Long,
      limit: Int = 100,
      table: String = DefaultTable
  )(using ObjectCodec[A]): Vector[Stored[A]] =
    jdbc(conn, table).changes[A](store, sinceVersion, limit)

  def decodeAny[A](value: Any)(using codec: ObjectCodec[A]): A =
    anyToJson(value) match
      case JsonValue.Obj(fields) =>
        codec.decode(ObjectValue(fields)) match
          case Right(value) => value
          case Left(error)  => throw RowProjectionError(s"object store decode failed: ${error.render}")
      case other =>
        throw RowProjectionError(s"object store decode expected JSON object, got ${JsonValue.kind(other)}")

  private final class JdbcObjectStoreBackend(conn: Connection, table: String) extends ObjectStoreBackend:
    private val tableSql = SqlRuntime.validateIdentifierPath(table, "object store table")

    def get[A](store: String, key: String)(using ObjectCodec[A]): Option[Stored[A]] =
      ensureSchema(conn, table)
      selectStored[A](store, key)

    def put[A](store: String, value: A, key: Option[String], expectedVersion: Option[Long])(using codec: ObjectCodec[A]): Stored[A] =
      ensureSchema(conn, table)
      val encoded = codec.encode(value)
      val objectKey = key.orElse(codec.key(value)).getOrElse {
        throw IllegalArgumentException("ObjectStore.put requires an explicit key or an ObjectCodec @key field")
      }
      val current = selectRaw(store, objectKey)
      checkExpected(store, objectKey, expectedVersion, current.map(_.version))
      val nextVersion = nextGlobalVersion()
      val updatedAt = Instant.now()
      val json = writeJson(JsonValue.Obj(encoded.fields))
      current match
        case Some(_) =>
          SqlRuntime.execute(conn,
            s"UPDATE $tableSql SET value_json = ?, version = ?, updated_at = ?, deleted = FALSE WHERE store_name = ? AND object_key = ?",
            List(json, nextVersion, updatedAt.toString, store, objectKey))
        case None =>
          SqlRuntime.execute(conn,
            s"INSERT INTO $tableSql (store_name, object_key, value_json, version, updated_at, deleted) VALUES (?, ?, ?, ?, ?, FALSE)",
            List(store, objectKey, json, nextVersion, updatedAt.toString))
      Stored(objectKey, Some(value), nextVersion, updatedAt, deleted = false)

    def delete(store: String, key: String, expectedVersion: Option[Long]): Stored[Nothing] =
      ensureSchema(conn, table)
      val current = selectRaw(store, key)
      checkExpected(store, key, expectedVersion, current.map(_.version))
      val nextVersion = nextGlobalVersion()
      val updatedAt = Instant.now()
      current match
        case Some(_) =>
          SqlRuntime.execute(conn,
            s"UPDATE $tableSql SET value_json = ?, version = ?, updated_at = ?, deleted = TRUE WHERE store_name = ? AND object_key = ?",
            List("{}", nextVersion, updatedAt.toString, store, key))
        case None =>
          SqlRuntime.execute(conn,
            s"INSERT INTO $tableSql (store_name, object_key, value_json, version, updated_at, deleted) VALUES (?, ?, ?, ?, ?, TRUE)",
            List(store, key, "{}", nextVersion, updatedAt.toString))
      Stored(key, None, nextVersion, updatedAt, deleted = true)

    def changes[A](store: String, sinceVersion: Long, limit: Int)(using ObjectCodec[A]): Vector[Stored[A]] =
      ensureSchema(conn, table)
      val effectiveLimit = math.max(1, limit)
      SqlRuntime.execute(conn,
        s"SELECT object_key, value_json, version, updated_at, deleted FROM $tableSql WHERE store_name = ? AND version > ? ORDER BY version ASC LIMIT ?",
        List(store, sinceVersion, effectiveLimit)) match
        case SqlResult.Rows(rows) =>
          rows.map(rowToStored[A]).toVector
        case SqlResult.UpdateCount(count) =>
          throw RowProjectionError(s"object store changes expected rows, got update count $count")

    private def selectStored[A](store: String, key: String)(using ObjectCodec[A]): Option[Stored[A]] =
      selectRaw(store, key).map(rowToStored[A])

    private def selectRaw(store: String, key: String): Option[ObjectStoreRow] =
      SqlRuntime.execute(conn,
        s"SELECT object_key, value_json, version, updated_at, deleted FROM $tableSql WHERE store_name = ? AND object_key = ?",
        List(store, key)) match
        case SqlResult.Rows(rows) => rows.headOption.map { row =>
          ObjectStoreRow(
            row("OBJECT_KEY").toString,
            row("VALUE_JSON").toString,
            row("VERSION").asInstanceOf[Number].longValue,
            Instant.parse(row("UPDATED_AT").toString),
            readBoolean(row("DELETED"))
          )
        }
        case SqlResult.UpdateCount(count) =>
          throw RowProjectionError(s"object store get expected rows, got update count $count")

    private def rowToStored[A](row: Row)(using codec: ObjectCodec[A]): Stored[A] =
      val raw = ObjectStoreRow(
        row("OBJECT_KEY").toString,
        row("VALUE_JSON").toString,
        row("VERSION").asInstanceOf[Number].longValue,
        Instant.parse(row("UPDATED_AT").toString),
        readBoolean(row("DELETED"))
      )
      rowToStored(raw)

    private def rowToStored[A](raw: ObjectStoreRow)(using codec: ObjectCodec[A]): Stored[A] =
      val value =
        if raw.deleted then None
        else
          readObject(raw.valueJson) match
            case Right(objectValue) =>
              codec.decode(objectValue) match
                case Right(value) => Some(value)
                case Left(error) => throw RowProjectionError(s"object store decode failed for ${raw.key}: ${error.render}")
            case Left(error) => throw RowProjectionError(s"object store JSON decode failed for ${raw.key}: ${error.render}")
      Stored(raw.key, value, raw.version, raw.updatedAt, raw.deleted)

    private def nextGlobalVersion(): Long =
      SqlRuntime.execute(conn, s"SELECT COALESCE(MAX(version), 0) AS version FROM $tableSql", Nil) match
        case SqlResult.Rows(rows) if rows.nonEmpty => rows.head("VERSION").asInstanceOf[Number].longValue + 1L
        case SqlResult.Rows(_) => 1L
        case SqlResult.UpdateCount(count) =>
          throw RowProjectionError(s"object store version query expected rows, got update count $count")

    private def checkExpected(store: String, key: String, expected: Option[Long], actual: Option[Long]): Unit =
      expected.foreach { wanted =>
        if actual.forall(_ != wanted) then throw ObjectStoreConflict(store, key, expected, actual)
      }

    private def readBoolean(value: Any): Boolean = value match
      case value: java.lang.Boolean => value.booleanValue
      case value: java.lang.Number => value.intValue != 0
      case value: String => value.equalsIgnoreCase("true") || value == "1"
      case other => other.toString.equalsIgnoreCase("true")

  private final case class ObjectStoreRow(key: String, valueJson: String, version: Long, updatedAt: Instant, deleted: Boolean)

  private def writeJson(value: JsonValue): String =
    ujson.write(toUjson(value))

  private def readObject(json: String): Either[DecodeError, ObjectValue] =
    try
      fromUjson(ujson.read(json)) match
        case JsonValue.Obj(fields) => Right(ObjectValue(fields))
        case other => Left(DecodeError(s"expected JSON object, got ${JsonValue.kind(other)}"))
    catch
      case e: ujson.ParseException => Left(DecodeError(e.getMessage))

  private def toUjson(value: JsonValue): ujson.Value = value match
    case JsonValue.Null => ujson.Null
    case JsonValue.Bool(value) => ujson.Bool(value)
    case JsonValue.Num(value) => ujson.Num(value.toDouble)
    case JsonValue.Str(value) => ujson.Str(value)
    case JsonValue.Arr(values) => ujson.Arr.from(values.map(toUjson))
    case JsonValue.Obj(fields) => ujson.Obj.from(fields.iterator.map((name, value) => name -> toUjson(value)))

  private def fromUjson(value: ujson.Value): JsonValue = value match
    case ujson.Null => JsonValue.Null
    case ujson.Bool(value) => JsonValue.Bool(value)
    case ujson.Num(value) => JsonValue.Num(BigDecimal(value))
    case ujson.Str(value) => JsonValue.Str(value)
    case arr: ujson.Arr => JsonValue.Arr(arr.value.map(fromUjson).toVector)
    case obj: ujson.Obj => JsonValue.Obj(obj.value.iterator.map((name, value) => name -> fromUjson(value)).toMap)

  private def anyToJson(value: Any): JsonValue = value match
    case null => JsonValue.Null
    case None => JsonValue.Null
    case Some(value) => anyToJson(value)
    case value: Boolean => JsonValue.Bool(value)
    case value: Byte => JsonValue.Num(BigDecimal(value.toLong))
    case value: Short => JsonValue.Num(BigDecimal(value.toLong))
    case value: Int => JsonValue.Num(BigDecimal(value))
    case value: Long => JsonValue.Num(BigDecimal(value))
    case value: Float => JsonValue.Num(BigDecimal.decimal(value.toDouble))
    case value: Double => JsonValue.Num(BigDecimal.decimal(value))
    case value: BigDecimal => JsonValue.Num(value)
    case value: java.math.BigDecimal => JsonValue.Num(BigDecimal(value))
    case value: String => JsonValue.Str(value)
    case fields: Map[?, ?] =>
      JsonValue.Obj(fields.iterator.map { case (name, value) => name.toString -> anyToJson(value) }.toMap)
    case values: Iterable[?] => JsonValue.Arr(values.iterator.map(anyToJson).toVector)
    case values: Array[?] => JsonValue.Arr(values.iterator.map(anyToJson).toVector)
    case other =>
      JsonValue.Str(other.toString)
