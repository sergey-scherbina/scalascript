package ssc.plugin.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.IdentityHashMap
import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite
import ssc.{Done, NativeUiSites, Prims, Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

class UiNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).get(args.toList)

  private def invoke(fn: Value, args: Value*): Value = fn match
    case closure: Value.ClosV =>
      val env = if args.isEmpty then closure.env else Runtime.extend(closure.env, args.toArray)
      Runtime.run(closure.code, env)
    case Value.ForeignV(named: Value.NamedMethodObj) => invoke(named.getField("apply").get, args*)
    case signal @ Value.DataV("NativeUiSignal", _) if args.isEmpty => Prims.methodOp("apply", signal, Nil)
    case _ => fail("value is not callable")

  private def method(value: Value, name: String): Value =
    Value.ClosV(Runtime.emptyEnv, -1, env => Done(Prims.methodOp(name, value, env.toList)))

  private def list(values: Value*): Value =
    values.toList.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, rest) =>
      Value.DataV("Cons", Vector(value, rest))
    }

  private def map(entries: (String, Value)*): Value =
    Value.MapV.from(entries.map { case (key, value) => Value.StrV(key) -> value })

  private def assertPortable(root: Value): Unit =
    val seen = IdentityHashMap[AnyRef, java.lang.Boolean]()
    def loop(value: Value): Unit =
      val ref = value.asInstanceOf[AnyRef]
      if seen.put(ref, java.lang.Boolean.TRUE) == null then value match
        case Value.ForeignV(_) => fail(s"ForeignV escaped in ${ssc.Show.show(root)}")
        case Value.DataV(_, fields) => fields.foreach(loop)
        case closure: Value.ClosV => closure.env.foreach(loop)
        case Value.MapV(entries) => entries.foreach { case (key, item) => loop(key); loop(item) }
        case _ => ()
    loop(root)

  test("mutable and derived signals use the native callback boundary"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val count = call("signal", Value.StrV("count"), Value.IntV(1))
    assert(invoke(count) == Value.IntV(1))
    invoke(method(count, "set"), Value.IntV(2))
    assert(invoke(count) == Value.IntV(2))
    val plusOne = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.IntV(number) => ssc.Done(Value.IntV(number + 1))
      case _ => ssc.Done(Value.UnitV))
    invoke(method(count, "update"), plusOne)
    assert(invoke(count) == Value.IntV(3))
    val equal = call("eqSignal", count, Value.IntV(3))
    assert(invoke(equal) == Value.BoolV(true))
    assert(invoke(method(count, "id")) == Value.StrV("count"))

  test("seed first write detaches even when it equals the stale registration default"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val source = call("signal", Value.StrV("source"), Value.StrV("A"))
    val seed = call("seedSignal", Value.StrV("seed"), source)
    invoke(method(source, "set"), Value.StrV("B"))
    assert(invoke(seed) == Value.StrV("B"))
    invoke(method(seed, "set"), Value.StrV("A"))
    invoke(method(source, "set"), Value.StrV("C"))
    assert(invoke(seed) == Value.StrV("A"))

  test("fetch signal and action helpers remain declarative on the standard JVM lane"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val tick = call("signal", Value.StrV("tick"), Value.IntV(0))
    val headers = call("signal", Value.StrV("headers"), Value.StrV(""))
    val fetched = call(
      "fetchUrlSignal",
      Value.StrV("summary"),
      Value.StrV("/api/summary"),
      tick,
      headers)
    assert(invoke(fetched) == Value.StrV(""))

    val body = call("computedSignal", Value.ClosV(Runtime.emptyEnv, 0, _ =>
      ssc.Done(Value.StrV("{\"ok\":true}"))))
    val action = call(
      "fetchAction",
      Value.StrV("PUT"),
      Value.StrV("/api/save"),
      body,
      tick,
      headers)
    val Value.DataV("NativeUiFetchAction", actionFields) = action: @unchecked
    assert(actionFields.head == Value.StrV("manual:fetchAction"))
    val Value.DataV("NativeUiFetchRequest", request) = actionFields(1): @unchecked
    assert(request == Vector(Value.StrV("PUT"), Value.StrV("/api/save"), body, headers))
    val Value.DataV("Cons", Seq(Value.DataV("NativeUiSuccessEffect", effect), Value.DataV("Nil", _))) = actionFields(2): @unchecked
    assert(effect.head == Value.StrV("bumpTick"))
    assert(effect(1).asInstanceOf[Value.DataV].fields.head == Value.StrV("tick"))
    assert(effect(2) == Value.UnitV)

  test("static emit escapes and deterministically renders current signal values"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val name = call("signal", Value.StrV("name"), Value.StrV("Grace"))
    invoke(method(name, "set"), Value.StrV("Ada"))
    val visible = call("signal", Value.StrV("visible"), Value.BoolV(true))
    val heading = call("element",
      Value.StrV("h1"), map(), map(),
      list(call("textNode", Value.StrV("Hi <native>"))))
    val conditional = call("showSignal", visible,
      call("element", Value.StrV("span"), map(), map(), list(call("textNode", Value.StrV("shown")))),
      call("textNode", Value.StrV("hidden")))
    val root = call("element",
      Value.StrV("main"), map("id" -> Value.StrV("app"), "class" -> Value.StrV("card")), map(),
      list(heading, call("signalText", name), conditional))
    val out = Files.createTempDirectory("ssc-native-ui-")

    assert(call("emit", root, Value.StrV(out.toString)) == Value.UnitV)

    val html = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8)
    assert(html ==
      "<!doctype html>\n<main class=\"card\" id=\"app\"><h1>Hi &lt;native&gt;</h1>Ada<span>shown</span></main>\n")

  test("reserved ABI-v1 globals accept hidden site and source metadata"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val source = Value.DataV("NativeUiSourceRef", Vector(
      Value.StrV("app.ssc"), Value.IntV(4), Value.IntV(2), Value.StrV("element")))
    val value = call(
      NativeUiSites.internalName("element"),
      Value.StrV("d0:entry/root"), source,
      Value.StrV("div"), map(), map(), list())

    val Value.DataV("NativeUiElement", fields) = value: @unchecked
    assert(fields.head == Value.StrV("d0:entry/root"))
    assert(fields(1) == Value.StrV("div"))
    assert(fields(2).asInstanceOf[Value.MapV].entries.isEmpty)
    assert(fields(3).asInstanceOf[Value.MapV].entries.isEmpty)
    assert(fields(4) == list())

    val sourceSignal = call("signal", Value.StrV("route"), Value.StrV("home"))
    val equality = call(
      NativeUiSites.internalName("eqSignal"),
      Value.StrV("d0:entry/root/s1"), source,
      sourceSignal, Value.StrV("home"))
    val Value.DataV("NativeUiSignal", equalityFields) = equality: @unchecked
    assert(equalityFields.head == Value.StrV("__equality__d0:entry/root/s1"))
    assert(invoke(equality) == Value.BoolV(true))

  test("Apple root capture preserves Unit source behavior and enforces one root"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val source = Value.DataV("NativeUiSourceRef", Vector(
      Value.StrV("app.ssc"), Value.IntV(8), Value.IntV(1), Value.StrV("emit")))
    call("__nativeUiBeginApple")
    val tree = call("textNode", Value.StrV("hello"))
    assert(call(NativeUiSites.internalName("emit"), source, tree, Value.StrV("out")) == Value.UnitV)
    val abi = call("__nativeUiTakeRoot")
    val Value.DataV("NativeUiAbi", Vector(Value.IntV(version), root, config)) = abi: @unchecked
    assert(version == 1)
    assert(root == tree)
    assert(config == Value.DataV("NativeUiRootConfig", Vector(
      Value.StrV("emit"), Value.StrV("out"), Value.IntV(0), Value.StrV(""))))
    assertPortable(abi)

    call("__nativeUiBeginApple")
    call(NativeUiSites.internalName("emit"), source, tree, Value.StrV("one"))
    val duplicate = intercept[RuntimeException](
      call(NativeUiSites.internalName("emit"), source, tree, Value.StrV("two")))
    assert(duplicate.getMessage.contains("registered multiple roots"))

    call("__nativeUiBeginApple")
    val missing = intercept[RuntimeException](call("__nativeUiTakeRoot"))
    assert(missing.getMessage.contains("call emit(...) or serve(...) exactly once"))

    call("__nativeUiBeginApple")
    call("signal", Value.StrV("rolled-back"), Value.IntV(1))
    assert(call("__nativeUiAbortApple") == Value.UnitV)

    call("__nativeUiBeginApple")
    val tick = call("signal", Value.StrV("apple-tick"), Value.IntV(0))
    val body = call("signal", Value.StrV("apple-body"), Value.StrV("{}"))
    assertPortable(call("fetchAction", Value.StrV("POST"), Value.StrV("/save"), body, tick))
    val emptyHeadersConflict = intercept[RuntimeException](
      call("signal", Value.StrV("__empty_headers__"), Value.StrV("not-owned")))
    assert(emptyHeadersConflict.getMessage.contains("conflicting kind/default"))
    call("__nativeUiAbortApple")

    call("__nativeUiBeginApple")
    call("signal", Value.StrV("rolled-back"), Value.IntV(2))
    call(NativeUiSites.internalName("emit"), source, tree, Value.StrV("clean"))
    assert(call("__nativeUiTakeRoot").asInstanceOf[Value.DataV].tag == "NativeUiAbi")

  test("signals are portable scoped data and conflicting duplicates fail"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val body = Value.ClosV(Runtime.emptyEnv, 0, _ =>
      Done(call("signal", Value.StrV("counter__a__count"), Value.IntV(1))))
    val scoped = call("componentScope", Value.StrV("counter__a"), body)
    val Value.DataV("NativeUiSignal", fields) = scoped: @unchecked
    assert(fields.take(3) == Vector(
      Value.StrV("counter__a__count"), Value.StrV("counter__a"), Value.StrV("mutable")))
    assertPortable(scoped)

    invoke(method(scoped, "set"), Value.IntV(7))
    val same = call("componentScope", Value.StrV("counter__a"), body)
    assert(invoke(same) == Value.IntV(7))
    val conflictBody = Value.ClosV(Runtime.emptyEnv, 0, _ =>
      Done(call("signal", Value.StrV("counter__a__count"), Value.IntV(2))))
    val conflict = intercept[RuntimeException](
      call("componentScope", Value.StrV("counter__a"), conflictBody))
    assert(conflict.getMessage.contains("conflicting kind/default"))

  test("action, table, offline, and trusted HTML families are portable ABI-v1 data"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val tick = call("signal", Value.StrV("tick"), Value.IntV(0))
    val body = call("signal", Value.StrV("body"), Value.StrV("{}"))
    val into = call("signal", Value.StrV("into"), Value.StrV(""))
    val headers = call("signal", Value.StrV("headers"), Value.StrV("{}"))
    val url = call("computedSignal", Value.ClosV(Runtime.emptyEnv, 0, _ => Done(Value.StrV("/items/1"))))
    val effects = list(
      call("onSetSignal", into, Value.StrV("done")),
      call("onNavigate", Value.StrV("#/done")))
    val values = List(
      call("fetchAction", Value.StrV("POST"), Value.StrV("/items"), body, tick, headers),
      call("fetchActionTo", Value.StrV("PUT"), url, body, tick, headers),
      call("fetchActionClear", Value.StrV("POST"), Value.StrV("/items"), body, tick, headers),
      call("fetchCaptureAction", Value.StrV("POST"), Value.StrV("/login"), body, into, tick, headers),
      call("fetchActionWith", Value.StrV("POST"), Value.StrV("/items"),
        call("formBody", list(Value.StrV("name"))), effects, headers),
      call("dateColumn", Value.StrV("When"), Value.StrV("createdAt"), Value.StrV(""), Value.StrV("yyyy-MM-dd")),
      call("rowDeleteAction", Value.StrV("/items"), Value.StrV("id"), tick, headers),
      call("dataTableView", call("staticRowsSource", list(map("id" -> Value.IntV(1)))),
        list(call("fieldColumn", Value.StrV("ID"), Value.StrV("id"), Value.StrV(""), Value.UnitV)), list()))
    values.foreach(assertPortable)
    assert(values.take(5).forall { case Value.DataV("NativeUiFetchAction", _) => true; case _ => false })
    assert(values(5).isInstanceOf[Value.DataV])
    assert(values(6).isInstanceOf[Value.DataV])
    assert(values(7).asInstanceOf[Value.DataV].tag == "NativeUiDataTable")

    call("localStorageSet", Value.StrV("token"), Value.StrV("abc"))
    val persisted = call("persistedSignal", Value.StrV("token"), Value.StrV("fallback"))
    assert(invoke(persisted) == Value.StrV("abc"))
    assert(invoke(call("onlineSignal")) == Value.BoolV(true))

    val trusted = call("element", Value.StrV("span"),
      map("style" -> Value.StrV("display:contents"),
        "data-ssc-raw-html" -> Value.StrV("<strong data-x=\"1\">ok</strong>")), map(), list())
    assert(trusted == Value.DataV("NativeUiTrustedHtml", Vector(
      Value.StrV("manual:element"), Value.StrV("<strong data-x=\"1\">ok</strong>"))))

    val malformed = List(
      map("data-ssc-raw-html" -> Value.StrV("x")),
      map("style" -> Value.StrV("display:block"), "data-ssc-raw-html" -> Value.StrV("x")),
      map("style" -> Value.StrV("display:contents"), "data-ssc-raw-html" -> Value.StrV("x"), "class" -> Value.StrV("extra")))
    malformed.foreach { attrs =>
      val Value.DataV(tag, _) = call("element", Value.StrV("span"), attrs, map(), list()): @unchecked
      assert(tag == "NativeUiUnsupported")
    }

  test("column defaults, row delete payload, and String-keyed rows match ABI-v1"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val tick = call("signal", Value.StrV("tick"), Value.IntV(0))
    val shortColumns = List(
      call("fieldColumn", Value.StrV("Name"), Value.StrV("name")),
      call("dateColumn", Value.StrV("Date"), Value.StrV("date")),
      call("moneyColumn", Value.StrV("Amount"), Value.StrV("amount")),
      call("statusColumn", Value.StrV("Status"), Value.StrV("status")),
      call("linkColumn", Value.StrV("Link"), Value.StrV("href")),
      call("stackedColumn", Value.StrV("Name"), Value.StrV("name"), Value.StrV("subtitle")))
    shortColumns.foreach {
      case Value.DataV("NativeUiColumn", fields) => assert(fields(3) == Value.StrV("")); assertPortable(fields(4))
      case other => fail(s"unexpected column: $other")
    }

    val action = call("rowDeleteAction", Value.StrV("/items"), Value.StrV("id"), tick)
    val Value.DataV("NativeUiRowAction", actionFields) = action: @unchecked
    val Value.DataV("NativeUiFetchRequest", requestFields) = actionFields(2): @unchecked
    assert(requestFields.head == Value.StrV("POST"))
    assert(requestFields(2) == actionFields(3))

    val nonStringRow = Value.MapV.from(List(Value.IntV(1) -> Value.StrV("bad")))
    val badRow = intercept[RuntimeException](call("staticRowsSource", list(nonStringRow)))
    assert(badRow.getMessage.contains("NativeUiTableSource.rows[0] requires String keys"))

    val badOption = Value.ForeignV(collection.mutable.LinkedHashMap[Value, Value](
      Value.StrV("bad") -> Value.ForeignV(new Object)))
    val badColumn = intercept[RuntimeException](call(
      "statusColumn", Value.StrV("Status"), Value.StrV("status"), Value.StrV(""), badOption))
    assert(badColumn.getMessage.contains("NativeUiColumn[status].options[\"colorMap\"][\"bad\"]"))

  test("deep canonicalization preserves MapV cycles and reports ForeignV paths"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val cycle = Value.MapV.empty
    cycle.entries(Value.StrV("self")) = cycle
    val source = call("staticRowsSource", list(cycle))
    assertPortable(source)

    val bad = intercept[RuntimeException](call("element",
      Value.StrV("div"), map("aria-label" -> Value.ForeignV(new Object)), map(), list()))
    assert(bad.getMessage.contains("NativeUiElement.attrs[\"aria-label\"]"))

  test("canonicalization converts cyclic DataV host maps without mutating closures"):
    val back = Value.MapV.empty
    val host = collection.mutable.LinkedHashMap[Value, Value](Value.StrV("answer") -> Value.IntV(42))
    val data = Value.DataV("Box", Vector(back, Value.ForeignV(host)))
    back.entries(Value.StrV("owner")) = data

    val converted = NativeUiPortable.canonical(data, "root").asInstanceOf[Value.DataV]
    assert(!(converted eq data))
    val convertedBack = converted.fields.head.asInstanceOf[Value.MapV]
    assert(convertedBack.entries(Value.StrV("owner")).asInstanceOf[AnyRef] eq converted.asInstanceOf[AnyRef])
    assert(converted.fields(1).isInstanceOf[Value.MapV])
    assertPortable(converted)

    val captured = Value.ForeignV(collection.mutable.LinkedHashMap[Value, Value](
      Value.StrV("x") -> Value.IntV(1)))
    val env = Array[Value](captured)
    val fn = Value.ClosV(env, 0, _ => Done(Value.UnitV))
    val error = intercept[RuntimeException](NativeUiPortable.canonical(fn, "callback"))
    assert(error.getMessage.contains("callback.<closure-env>[0]"))
    assert(fn.env(0).asInstanceOf[AnyRef] eq captured.asInstanceOf[AnyRef])

    val shared = Value.MapV.empty
    shared.entries(Value.StrV("host")) = captured
    val aliasingClosure = Value.ClosV(Array[Value](shared), 0, _ => Done(Value.UnitV))
    val aliasingRoot = Value.DataV("Aliasing", Vector(shared, aliasingClosure))
    val aliasError = intercept[RuntimeException](NativeUiPortable.canonical(aliasingRoot, "alias"))
    assert(aliasError.getMessage.contains("alias.Aliasing[1].<closure-env>[0][\"host\"]"))
    assert(aliasingClosure.env(0).asInstanceOf[AnyRef] eq shared.asInstanceOf[AnyRef])

    val portableShared = Value.MapV.from(List(Value.StrV("value") -> Value.IntV(7)))
    val sharingClosure = Value.ClosV(Array[Value](portableShared), 0, _ => Done(Value.UnitV))
    val unrelatedHost = Value.ForeignV(collection.mutable.LinkedHashMap[Value, Value](
      Value.StrV("converted") -> Value.BoolV(true)))
    val sharingRoot = Value.DataV("Sharing", Vector(portableShared, sharingClosure, unrelatedHost))
    val sharingCopy = NativeUiPortable.canonical(sharingRoot, "sharing").asInstanceOf[Value.DataV]
    assert(sharingCopy.fields(1).asInstanceOf[AnyRef] eq sharingClosure.asInstanceOf[AnyRef])
    assert(sharingCopy.fields.head.asInstanceOf[AnyRef] eq portableShared.asInstanceOf[AnyRef])
    assert(sharingClosure.env.head.asInstanceOf[AnyRef] eq sharingCopy.fields.head.asInstanceOf[AnyRef])
    assert(sharingCopy.fields(2).isInstanceOf[Value.MapV])

  test("portable equality backtracks unordered cyclic map candidates soundly"):
    def cyclicKey(): Value.MapV =
      val key = Value.MapV.empty
      key.entries(Value.StrV("self")) = key
      key.entries(Value.StrV("kind")) = Value.StrV("same")
      key

    val left = Value.MapV.empty
    left.entries(cyclicKey()) = Value.StrV("A")
    left.entries(cyclicKey()) = Value.StrV("B")
    val reordered = Value.MapV.empty
    reordered.entries(cyclicKey()) = Value.StrV("B")
    reordered.entries(cyclicKey()) = Value.StrV("A")
    val negative = Value.MapV.empty
    negative.entries(cyclicKey()) = Value.StrV("B")
    negative.entries(cyclicKey()) = Value.StrV("C")

    assert(NativeUiPortable.portableEquals(left, reordered))
    assert(!NativeUiPortable.portableEquals(left, negative))

  test("keyed render preserves moves, disposes deletions, rejects duplicates, and rolls back"):
    NativePluginHost.installProviders(List(UiNativePlugin()))
    val items = call("signal", Value.StrV("items"), list(Value.StrV("a"), Value.StrV("b")))
    val observed = mutable.LinkedHashMap.empty[String, Value]
    var failKey: Option[String] = None
    val keyFn = Value.ClosV(Runtime.emptyEnv, 1, env => Done(env.last))
    val renderFn = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.StrV(key) =>
        val scope = s"row__$key"
        val body = Value.ClosV(Runtime.emptyEnv, 0, _ =>
          val signal = call("signal", Value.StrV(s"${scope}__count"), Value.IntV(0))
          observed(key) = signal
          if failKey.contains(key) then throw new RuntimeException(s"render failed for $key")
          Done(call("signalText", signal)))
        Done(call("componentScope", Value.StrV(scope), body))
      case _ => Done(Value.UnitV))
    val root = call("forKeyedView", items, keyFn, renderFn)
    val out = Files.createTempDirectory("ssc-native-keyed-")
    def emit(): String =
      call("emit", root, Value.StrV(out.toString))
      Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8)

    assert(emit().contains("00"))
    val originalA = observed("a")
    invoke(method(originalA, "set"), Value.IntV(7))
    invoke(method(items, "set"), list(Value.StrV("b"), Value.StrV("a")))
    assert(emit().contains("07"))
    assert(invoke(observed("a")) == Value.IntV(7))
    assert(invoke(originalA) == Value.IntV(7))

    invoke(method(items, "set"), list(Value.StrV("b")))
    assert(emit().contains("0"))
    invoke(method(items, "set"), list(Value.StrV("a"), Value.StrV("b")))
    assert(emit().contains("00"))
    assert(invoke(observed("a")) == Value.IntV(0))
    assert(invoke(originalA) == Value.IntV(7))

    failKey = Some("b")
    val renderFailure = intercept[RuntimeException](emit())
    assert(renderFailure.getMessage.contains("render failed for b"))
    failKey = None
    assert(emit().contains("00"))

    invoke(method(items, "set"), list(Value.StrV("a"), Value.StrV("a")))
    val duplicate = intercept[RuntimeException](emit())
    assert(duplicate.getMessage.contains("duplicate NativeUiForKeyed key 'a'"))
    invoke(method(items, "set"), list(Value.StrV("a"), Value.StrV("b")))
    assert(emit().contains("00"))

  test("component paths, lexical occurrences, and shared-scope refs isolate keyed owners"):
    def output(root: Value, dir: java.nio.file.Path): String =
      call("emit", root, Value.StrV(dir.toString))
      Files.readString(dir.resolve("index.html"), StandardCharsets.UTF_8)

    val componentPlugin = UiNativePlugin()
    NativePluginHost.installProviders(List(componentPlugin))
    val keyFn = Value.ClosV(Runtime.emptyEnv, 1, env => Done(env.last))
    val leftItems = call("signal", Value.StrV("left-items"), list(Value.StrV("same")))
    val rightItems = call("signal", Value.StrV("right-items"), list(Value.StrV("same")))
    val componentSignals = mutable.LinkedHashMap.empty[String, Value]
    def componentNode(scope: String, items: Value): Value =
      val row = Value.ClosV(Runtime.emptyEnv, 1, _ =>
        val body = Value.ClosV(Runtime.emptyEnv, 0, _ =>
          val signal = call("signal", Value.StrV(s"${scope}__count"), Value.IntV(0))
          componentSignals(scope) = signal
          Done(call("signalText", signal)))
        Done(call("componentScope", Value.StrV(scope), body)))
      val body = Value.ClosV(Runtime.emptyEnv, 0, _ => Done(call("forKeyedView", items, keyFn, row)))
      call("componentScope", Value.StrV(s"outer-$scope"), body)

    val componentRoot = call("fragment", list(
      componentNode("left", leftItems), componentNode("right", rightItems)))
    val componentOut = Files.createTempDirectory("ssc-native-component-owner-")
    assert(output(componentRoot, componentOut).contains("00"))
    val initialBindingCount = componentPlugin.componentBindingCount
    (1 to 25).foreach(_ => output(componentRoot, componentOut))
    assert(componentPlugin.componentBindingCount == initialBindingCount)
    invoke(method(componentSignals("right"), "set"), Value.IntV(9))
    assert(output(componentRoot, componentOut).contains("09"))
    invoke(method(leftItems, "set"), list())
    assert(output(componentRoot, componentOut).contains("9"))
    assert(componentPlugin.componentBindingCount < initialBindingCount)
    assert(invoke(componentSignals("right")) == Value.IntV(9))
    invoke(method(leftItems, "set"), list(Value.StrV("same")))
    assert(output(componentRoot, componentOut).contains("09"))

    NativePluginHost.installProviders(List(UiNativePlugin()))
    val firstItems = call("signal", Value.StrV("first-items"), list(Value.StrV("same")))
    val secondItems = call("signal", Value.StrV("second-items"), list(Value.StrV("same")))
    val occurrenceSignals = mutable.LinkedHashMap.empty[String, Value]
    def repeatedNode(scope: String, items: Value): Value =
      val row = Value.ClosV(Runtime.emptyEnv, 1, _ =>
        val body = Value.ClosV(Runtime.emptyEnv, 0, _ =>
          val signal = call("signal", Value.StrV(s"${scope}__count"), Value.IntV(0))
          occurrenceSignals(scope) = signal
          Done(call("signalText", signal)))
        Done(call("componentScope", Value.StrV(scope), body)))
      call("forKeyedView", items, keyFn, row)

    val occurrenceRoot = call("fragment", list(
      repeatedNode("first", firstItems), repeatedNode("second", secondItems)))
    val occurrenceOut = Files.createTempDirectory("ssc-native-site-occurrence-")
    assert(output(occurrenceRoot, occurrenceOut).contains("00"))
    invoke(method(occurrenceSignals("second"), "set"), Value.IntV(8))
    invoke(method(firstItems, "set"), list())
    assert(output(occurrenceRoot, occurrenceOut).contains("8"))
    assert(invoke(occurrenceSignals("second")) == Value.IntV(8))

    NativePluginHost.installProviders(List(UiNativePlugin()))
    val sharedItems = call("signal", Value.StrV("shared-items"), list(Value.StrV("a"), Value.StrV("b")))
    val sharedSignals = mutable.LinkedHashMap.empty[String, Value]
    val sharedRow = Value.ClosV(Runtime.emptyEnv, 1, env => env.last match
      case Value.StrV(key) =>
        val body = Value.ClosV(Runtime.emptyEnv, 0, _ =>
          val signal = call("signal", Value.StrV("shared__count"), Value.IntV(0))
          sharedSignals(key) = signal
          Done(call("signalText", signal)))
        Done(call("componentScope", Value.StrV("shared"), body))
      case _ => Done(Value.UnitV))
    val sharedRoot = call("forKeyedView", sharedItems, keyFn, sharedRow)
    val sharedOut = Files.createTempDirectory("ssc-native-shared-scope-")
    assert(output(sharedRoot, sharedOut).contains("00"))
    invoke(method(sharedSignals("a"), "set"), Value.IntV(5))
    invoke(method(sharedItems, "set"), list(Value.StrV("b")))
    assert(output(sharedRoot, sharedOut).contains("5"))
    assert(invoke(sharedSignals("b")) == Value.IntV(5))
    invoke(method(sharedItems, "set"), list())
    output(sharedRoot, sharedOut)
    invoke(method(sharedItems, "set"), list(Value.StrV("a")))
    assert(output(sharedRoot, sharedOut).contains("0"))
