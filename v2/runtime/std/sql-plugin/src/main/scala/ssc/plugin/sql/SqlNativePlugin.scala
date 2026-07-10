package ssc.plugin.sql

import java.sql.Connection
import scala.collection.mutable
import ssc.{Done, Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}
import scalascript.sql.{ConnectionRegistry, DatabaseSpec, SqlResult, SqlRuntime}

/** Core-free JDBC provider for the ScalaScript 2.1 standard runtime. */
final class SqlNativePlugin extends NativePlugin:
  def id: String = "60-sql"

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
    case number: java.math.BigDecimal => Value.ForeignV(number)
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

  def install(context: NativePluginContext): Unit =
    val specs = context.databases.toList.sortBy(_._1).map { case (name, config) =>
      DatabaseSpec(name, config.url, config.user, config.password, config.driver)
    }
    val registry = ConnectionRegistry(specs)

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

    native(context, "DriverManager.getConnection")(getConnection)
    native(context, "Db.query")(query)
    native(context, "Db.execute")(execute)

    val driverMethods = Map("getConnection" -> closure(-1)(getConnection))
    context.registerValue("DriverManager", Value.ForeignV(new Value.NamedMethodObj:
      def underlying: AnyRef = driverMethods
      def getField(name: String): Option[Value] = driverMethods.get(name)))

    val dbMethods = Map(
      "query" -> closure(3)(query),
      "execute" -> closure(3)(execute))
    context.registerValue("Db", Value.ForeignV(new Value.NamedMethodObj:
      def underlying: AnyRef = dbMethods
      def getField(name: String): Option[Value] = dbMethods.get(name)))
