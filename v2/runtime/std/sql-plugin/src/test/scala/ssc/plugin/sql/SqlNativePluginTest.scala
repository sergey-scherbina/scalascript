package ssc.plugin.sql

import org.scalatest.funsuite.AnyFunSuite
import ssc.{V2PluginRegistry, Value}
import ssc.plugin.{NativeDatabaseConfig, NativePluginHost, NativeRuntimeConfig}

class SqlNativePluginTest extends AnyFunSuite:
  private def list(values: Value*): Value =
    values.toList.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, rest) =>
      Value.DataV("Cons", Vector(value, rest))
    }

  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).get(args.toList)

  private def install(name: String): Unit =
    NativePluginHost.installProviders(
      List(SqlNativePlugin()),
      NativeRuntimeConfig(Map(
        "default" -> NativeDatabaseConfig(s"jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1"))))

  test("named H2 database executes DDL, parameterized writes, and row reads"):
    install("native-sql-roundtrip")

    assert(call("Db.execute",
      Value.StrV("default"),
      Value.StrV("CREATE TABLE people (id BIGINT PRIMARY KEY, name VARCHAR(80), active BOOLEAN)"),
      list()) == Value.IntV(0))
    assert(call("Db.execute",
      Value.StrV("default"),
      Value.StrV("INSERT INTO people VALUES (?, ?, ?)"),
      list(Value.IntV(7), Value.StrV("Ada"), Value.BoolV(true))) == Value.IntV(1))

    val rows = call("Db.query",
      Value.StrV("default"),
      Value.StrV("SELECT id, name, active FROM people WHERE id = ?"),
      list(Value.IntV(7)))
    val Value.DataV("Cons", Seq(Value.ForeignV(rawRow: collection.Map[?, ?]), Value.DataV("Nil", _))) = rows: @unchecked
    val row = rawRow.asInstanceOf[collection.Map[Value, Value]]
    assert(row(Value.StrV("ID")) == Value.IntV(7))
    assert(row(Value.StrV("NAME")) == Value.StrV("Ada"))
    assert(row(Value.StrV("ACTIVE")) == Value.BoolV(true))

  test("unknown named database reports configured names"):
    install("native-sql-unknown")

    val error = intercept[RuntimeException] {
      call("Db.query", Value.StrV("missing"), Value.StrV("SELECT 1"), list())
    }

    assert(error.getMessage.contains("no JDBC connection named `missing`"))
