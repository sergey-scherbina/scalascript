package ssc.plugin.sql

import java.sql.Connection
import java.util.Locale
import scala.collection.mutable
import ssc.{Done, Runtime, V2PluginRegistry, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}
import scalascript.sql.{ConnectionRegistry, DatabaseSpec, SqlResult, SqlRuntime}

/** Core-free JDBC provider for the ScalaScript 2.1 standard runtime. */
final class SqlNativePlugin extends NativePlugin:
  def id: String = "60-sql"

  private final case class RowSchema(fields: Vector[String], types: Vector[String])

  private def closure(arity: Int)(fn: List[Value] => Value): Value.ClosV =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def native(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, -1)(fn)

  private def text(args: List[Value], index: Int, operation: String): String = args.lift(index) match
    case Some(Value.StrV(value)) => value
    case _ => throw new RuntimeException(s"$operation argument ${index + 1} must be String")

  private def list(values: IterableOnce[Value]): Value =
    values.iterator.toList.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, rest) =>
      Value.DataV("Cons", Vector(value, rest))
    }

  private def unlist(value: Value, operation: String): List[Value] =
    val out = mutable.ListBuffer.empty[Value]
    var current = value
    var done = false
    while !done do
      current match
        case Value.DataV("Cons", Seq(head, tail)) => out += head; current = tail
        case Value.DataV("Nil", _) => done = true
        case _ => throw new RuntimeException(s"$operation argument 3 must be List")
    out.toList

  private def unwrap(value: Value): Any = value match
    case Value.UnitV => null
    case Value.BoolV(boolean) => boolean
    case Value.IntV(number) => number
    case Value.BigV(number) => number.bigInteger
    case Value.FloatV(number) => number
    case Value.DecimalV(text) => new java.math.BigDecimal(text)
    case Value.StrV(text) => text
    case Value.BytesV(bytes) => bytes.toArray
    case Value.DataV("Some", Seq(inner)) => unwrap(inner)
    case Value.DataV("None", _) => null
    case Value.ForeignV(handle) => handle
    case other => throw new RuntimeException(s"unsupported native SQL bind value: $other")

  private def wrap(value: Any): Value = value match
    case null => Value.UnitV
    case boolean: Boolean => Value.BoolV(boolean)
    case number: Byte => Value.IntV(number.toLong)
    case number: Short => Value.IntV(number.toLong)
    case number: Int => Value.IntV(number.toLong)
    case number: Long => Value.IntV(number)
    case number: java.math.BigInteger => Value.BigV(BigInt(number))
    case number: java.math.BigDecimal => Value.DecimalV(number.toPlainString)
    case number: Float => Value.FloatV(number.toDouble)
    case number: Double => Value.FloatV(number)
    case text: String => Value.StrV(text)
    case bytes: Array[Byte] => Value.BytesV(bytes.toVector)
    case temporal: java.time.temporal.TemporalAccessor => Value.StrV(temporal.toString)
    case uuid: java.util.UUID => Value.StrV(uuid.toString)
    case other => Value.StrV(other.toString)

  private def row(columns: IndexedSeq[String], values: IndexedSeq[Any]): Value =
    val entries = mutable.LinkedHashMap.empty[Value, Value]
    columns.zip(values).foreach { case (column, value) => entries(Value.StrV(column)) = wrap(value) }
    Value.ForeignV(entries)

  private def identifier(value: String, operation: String): String =
    if value.matches("[A-Za-z_][A-Za-z0-9_]*") then value
    else throw new RuntimeException(s"$operation invalid SQL identifier: $value")

  private def stringList(value: Value, operation: String): Vector[String] =
    unlist(value, operation).map {
      case Value.StrV(text) => text
      case _ => throw new RuntimeException(s"$operation Mirror metadata must contain Strings")
    }.toVector

  private def wrapTyped(value: Any, fieldType: String, operation: String): Value =
    val optional = fieldType.startsWith("Option[")
    if value == null then
      if optional then Value.DataV("None", Vector.empty)
      else throw new RuntimeException(s"$operation non-optional column was NULL")
    else
      val wrapped = wrap(value)
      if optional then Value.DataV("Some", Vector(wrapped)) else wrapped

  def install(context: NativePluginContext): Unit =
    val specs = context.databases.toList.sortBy(_._1).map { case (name, config) =>
      DatabaseSpec(name, config.url, config.user, config.password, config.driver)
    }
    val registry = ConnectionRegistry(specs)
    val rowSchemas = mutable.HashMap.empty[String, RowSchema]

    val schemaFor = (tag: String, operation: String) => rowSchemas.getOrElse(tag,
      throw new RuntimeException(s"$operation requires derives RowCodec for $tag"))

    val product = (value: Value, operation: String) => value match
      case Value.DataV(tag, fields) =>
        val schema = schemaFor(tag, operation)
        if fields.length != schema.fields.length then
          throw new RuntimeException(s"$operation field-count mismatch for $tag")
        (tag, schema, fields)
      case _ => throw new RuntimeException(s"$operation expects a derived product value")

    val executeCount = (dbName: String, sqlText: String, binds: List[Value], operation: String) =>
      SqlRuntime.execute(registry.connect(dbName), sqlText, binds.map(unwrap)) match
        case SqlResult.UpdateCount(count) => Value.IntV(count.toLong)
        case SqlResult.Rows(_) => throw new RuntimeException(s"$operation expected update count, got rows")

    val getConnection: List[Value] => Value = args => args match
      case List(Value.StrV(url)) => Value.ForeignV(java.sql.DriverManager.getConnection(url))
      case List(Value.StrV(url), Value.StrV(user), Value.StrV(password)) =>
        Value.ForeignV(java.sql.DriverManager.getConnection(url, user, password))
      case _ => throw new RuntimeException(
        "DriverManager.getConnection expects (url) or (url, user, password)")

    val query: List[Value] => Value = args =>
      val dbName = text(args, 0, "Db.query")
      val sql = text(args, 1, "Db.query")
      val binds = args.lift(2).map(unlist(_, "Db.query")).getOrElse(
        throw new RuntimeException("Db.query expects (dbName, sql, params)"))
      SqlRuntime.execute(registry.connect(dbName), sql, binds.map(unwrap)) match
        case SqlResult.Rows(rows) => list(rows.map(result => row(result.columns, result.values)))
        case SqlResult.UpdateCount(count) =>
          throw new RuntimeException(s"Db.query expected rows, got update count $count")

    val execute: List[Value] => Value = args =>
      val dbName = text(args, 0, "Db.execute")
      val sql = text(args, 1, "Db.execute")
      val binds = args.lift(2).map(unlist(_, "Db.execute")).getOrElse(
        throw new RuntimeException("Db.execute expects (dbName, sql, params)"))
      SqlRuntime.execute(registry.connect(dbName), sql, binds.map(unwrap)) match
        case SqlResult.UpdateCount(count) => Value.IntV(count.toLong)
        case SqlResult.Rows(_) => throw new RuntimeException("Db.execute expected update count, got rows")

    val sql: List[Value] => Value = args =>
      val dbName = text(args, 0, "Db.sql")
      val source = text(args, 1, "Db.sql")
      val binds = args.lift(2).map(unlist(_, "Db.sql")).getOrElse(
        throw new RuntimeException("Db.sql expects (dbName, sql, params)"))
      SqlRuntime.execute(registry.connect(dbName), source, binds.map(unwrap)) match
        case SqlResult.Rows(rows) => list(rows.map(result => row(result.columns, result.values)))
        case SqlResult.UpdateCount(count) => Value.IntV(count.toLong)

    val rowCodecDerived: List[Value] => Value = args => args match
      case List(mirror @ Value.DataV("Mirror", Seq(
            Value.StrV(tag), fieldValues, typeValues))) =>
        val fields = stringList(fieldValues, "RowCodec.derived")
        val types = stringList(typeValues, "RowCodec.derived")
        if fields.length != types.length then
          throw new RuntimeException(s"RowCodec.derived metadata mismatch for $tag")
        val registered = V2PluginRegistry.lookupFieldNames(tag, fields.length)
        if registered.exists(_ != fields) then
          throw new RuntimeException(s"RowCodec.derived field metadata mismatch for $tag")
        rowSchemas(tag) = RowSchema(fields, types)
        Value.DataV("NativeRowCodec", Vector(Value.StrV(tag), mirror))
      case _ => throw new RuntimeException("RowCodec.derived expects Mirror metadata")

    val queryTyped: List[Value] => Value = args =>
      val tag = text(args, 0, "Db.queryTyped")
      val schema = schemaFor(tag, "Db.queryTyped")
      val dbName = text(args, 1, "Db.queryTyped")
      val source = text(args, 2, "Db.queryTyped")
      val binds = args.lift(3).map(unlist(_, "Db.queryTyped")).getOrElse(
        throw new RuntimeException("Db.queryTyped expects (type, dbName, sql, params)"))
      SqlRuntime.execute(registry.connect(dbName), source, binds.map(unwrap)) match
        case SqlResult.Rows(rows) =>
          list(rows.map { result =>
            val byName = result.columns.zip(result.values).iterator.map { case (name, value) =>
              name.toLowerCase(Locale.ROOT) -> value
            }.toMap
            val fields = schema.fields.zip(schema.types).map { case (field, fieldType) =>
              val raw = byName.getOrElse(field.toLowerCase(Locale.ROOT),
                throw new RuntimeException(s"Db.query[$tag] missing column: $field"))
              wrapTyped(raw, fieldType, s"Db.query[$tag].$field")
            }
            Value.DataV(tag, fields)
          })
        case SqlResult.UpdateCount(count) =>
          throw new RuntimeException(s"Db.query[$tag] expected rows, got update count $count")

    val insert: List[Value] => Value = args =>
      val dbName = text(args, 0, "Db.insert")
      val table = identifier(text(args, 1, "Db.insert"), "Db.insert")
      val (_, schema, fields) = args.lift(2).map(product(_, "Db.insert")).getOrElse(
        throw new RuntimeException("Db.insert expects (dbName, table, value)"))
      val columns = schema.fields.map(identifier(_, "Db.insert"))
      val placeholders = Vector.fill(columns.length)("?").mkString(", ")
      val source = s"INSERT INTO $table (${columns.mkString(", ")}) VALUES ($placeholders)"
      executeCount(dbName, source, fields.toList, "Db.insert")

    val update: List[Value] => Value = args =>
      val dbName = text(args, 0, "Db.update")
      val table = identifier(text(args, 1, "Db.update"), "Db.update")
      val key = identifier(text(args, 2, "Db.update"), "Db.update")
      val keyValue = args.lift(3).getOrElse(
        throw new RuntimeException("Db.update expects (dbName, table, key, keyValue, value)"))
      val (_, schema, fields) = args.lift(4).map(product(_, "Db.update")).getOrElse(
        throw new RuntimeException("Db.update expects (dbName, table, key, keyValue, value)"))
      val assignments = schema.fields.zip(fields).filterNot { case (name, _) =>
        name.equalsIgnoreCase(key)
      }
      if assignments.isEmpty then throw new RuntimeException("Db.update requires a non-key field")
      val setSql = assignments.map { case (name, _) =>
        s"${identifier(name, "Db.update") } = ?"
      }.mkString(", ")
      val source = s"UPDATE $table SET $setSql WHERE $key = ?"
      executeCount(dbName, source, assignments.map(_._2).toList :+ keyValue, "Db.update")

    native(context, "DriverManager.getConnection")(getConnection)
    native(context, "Db.query")(query)
    native(context, "Db.execute")(execute)
    native(context, "Db.sql")(sql)
    native(context, "RowCodec_derived")(rowCodecDerived)
    native(context, "Db.queryTyped")(queryTyped)
    native(context, "Db.insert")(insert)
    native(context, "Db.update")(update)
    context.registerGlobal("sqlSection", 1) {
      case result :: Nil => Value.DataV("SqlSection", Vector(result))
      case _ => throw new RuntimeException("sqlSection expects one result")
    }
    context.registerFields("SqlSection", Vector("sql"))
    context.registerFields("NativeRowCodec", Vector("typeName", "mirror"))

    val driverMethods = Map("getConnection" -> closure(-1)(getConnection))
    context.registerValue("DriverManager", Value.ForeignV(new Value.NamedMethodObj:
      def underlying: AnyRef = driverMethods
      def getField(name: String): Option[Value] = driverMethods.get(name)))

    val dbMethods = Map(
      "query" -> closure(3)(query),
      "execute" -> closure(3)(execute),
      "sql" -> closure(3)(sql),
      "queryTyped" -> closure(4)(queryTyped),
      "insert" -> closure(3)(insert),
      "update" -> closure(5)(update))
    context.registerValue("Db", Value.ForeignV(new Value.NamedMethodObj:
      def underlying: AnyRef = dbMethods
      def getField(name: String): Option[Value] = dbMethods.get(name)))
