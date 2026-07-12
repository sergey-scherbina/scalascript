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
      Value.StrV("CREATE TABLE people (id BIGINT PRIMARY KEY, name VARCHAR(80), active BOOLEAN, amount DECIMAL(12,4))"),
      list()) == Value.IntV(0))
    assert(call("Db.execute",
      Value.StrV("default"),
      Value.StrV("INSERT INTO people VALUES (?, ?, ?, ?)"),
      list(Value.IntV(7), Value.StrV("Ada"), Value.BoolV(true), Value.DecimalV("1000.0100"))) == Value.IntV(1))

    val rows = call("Db.query",
      Value.StrV("default"),
      Value.StrV("SELECT id, name, active, amount FROM people WHERE id = ?"),
      list(Value.IntV(7)))
    val Value.DataV("Cons", Seq(Value.ForeignV(rawRow: collection.Map[?, ?]), Value.DataV("Nil", _))) = rows: @unchecked
    val row = rawRow.asInstanceOf[collection.Map[Value, Value]]
    assert(row(Value.StrV("ID")) == Value.IntV(7))
    assert(row(Value.StrV("NAME")) == Value.StrV("Ada"))
    assert(row(Value.StrV("ACTIVE")) == Value.BoolV(true))
    assert(row(Value.StrV("AMOUNT")) == Value.DecimalV("1000.0100"))

  test("unknown named database reports configured names"):
    install("native-sql-unknown")

    val error = intercept[RuntimeException] {
      call("Db.query", Value.StrV("missing"), Value.StrV("SELECT 1"), list())
    }

    assert(error.getMessage.contains("no JDBC connection named `missing`"))

  test("raw SQL fences preserve row/update results and structural section access"):
    install("native-sql-fence")

    assert(call("Db.sql", Value.StrV("default"),
      Value.StrV("CREATE TABLE notes (id INT PRIMARY KEY, text VARCHAR(80))"), list()) ==
      Value.IntV(0))
    assert(call("Db.sql", Value.StrV("default"),
      Value.StrV("INSERT INTO notes VALUES (?, ?)"),
      list(Value.IntV(1), Value.StrV("bound"))) == Value.IntV(1))
    val rows = call("Db.sql", Value.StrV("default"),
      Value.StrV("SELECT text FROM notes WHERE id = ?"), list(Value.IntV(1)))
    val Value.DataV("Cons", Seq(Value.ForeignV(rawRow: collection.Map[?, ?]), _)) = rows: @unchecked
    assert(rawRow.asInstanceOf[collection.Map[Value, Value]](Value.StrV("TEXT")) ==
      Value.StrV("bound"))
    assert(V2PluginRegistry.lookupFieldNames("SqlSection", 1).contains(Vector("sql")))

  test("derived portable products support typed query insert update and bounded errors"):
    install("native-typed-sql")
    V2PluginRegistry.registerFieldNames("Todo", Vector("id", "text", "done"))
    val mirror = Value.DataV("Mirror", Vector(
      Value.StrV("Todo"),
      list(Value.StrV("id"), Value.StrV("text"), Value.StrV("done")),
      list(Value.StrV("Int"), Value.StrV("String"), Value.StrV("Boolean"))))
    val codec = call("RowCodec_derived", mirror)
    assert(codec.isInstanceOf[Value.DataV])

    assert(call("Db.sql", Value.StrV("default"), Value.StrV(
      "CREATE TABLE todos (id INT PRIMARY KEY, text VARCHAR(120), done BOOLEAN)"), list()) ==
      Value.IntV(0))
    val first = Value.DataV("Todo", Vector(
      Value.IntV(1), Value.StrV("Buy milk"), Value.BoolV(false)))
    val changed = Value.DataV("Todo", Vector(
      Value.IntV(1), Value.StrV("Buy oat milk"), Value.BoolV(true)))
    assert(call("Db.insert", Value.StrV("default"), Value.StrV("todos"), first) ==
      Value.IntV(1))
    assert(call("Db.update", Value.StrV("default"), Value.StrV("todos"),
      Value.StrV("id"), Value.IntV(1), changed) == Value.IntV(1))
    assert(call("Db.queryTyped", Value.StrV("Todo"), Value.StrV("default"),
      Value.StrV("SELECT ID, TEXT, DONE FROM todos"), list()) ==
      list(changed))

    val missing = intercept[RuntimeException] {
      call("Db.queryTyped", Value.StrV("Todo"), Value.StrV("default"),
        Value.StrV("SELECT id, text FROM todos"), list())
    }
    assert(missing.getMessage.contains("Db.query[Todo] missing column: done"))
    val badIdentifier = intercept[RuntimeException] {
      call("Db.insert", Value.StrV("default"), Value.StrV("todos;drop"), first)
    }
    assert(badIdentifier.getMessage.contains("invalid SQL identifier"))

    val badProduct = intercept[RuntimeException] {
      call("Db.insert", Value.StrV("default"), Value.StrV("todos"), Value.StrV("not a product"))
    }
    assert(badProduct.getMessage.contains("expects a derived product value"))
    val badBind = intercept[RuntimeException] {
      call("Db.execute", Value.StrV("default"), Value.StrV("SELECT ?"),
        list(Value.DataV("Unsupported", Vector.empty)))
    }
    assert(badBind.getMessage.contains("unsupported native SQL bind value"))

    V2PluginRegistry.registerFieldNames("MaybeTodo", Vector("id", "note"))
    call("RowCodec_derived", Value.DataV("Mirror", Vector(
      Value.StrV("MaybeTodo"),
      list(Value.StrV("id"), Value.StrV("note")),
      list(Value.StrV("Int"), Value.StrV("Option[String]")))))
    call("Db.sql", Value.StrV("default"),
      Value.StrV("CREATE TABLE maybe_todos (id INT PRIMARY KEY, note VARCHAR(120))"), list())
    val nullable = Value.DataV("MaybeTodo", Vector(
      Value.IntV(2), Value.DataV("None", Vector.empty)))
    assert(call("Db.insert", Value.StrV("default"), Value.StrV("maybe_todos"), nullable) ==
      Value.IntV(1))
    assert(call("Db.queryTyped", Value.StrV("MaybeTodo"), Value.StrV("default"),
      Value.StrV("SELECT id, note FROM maybe_todos"), list()) == list(nullable))
