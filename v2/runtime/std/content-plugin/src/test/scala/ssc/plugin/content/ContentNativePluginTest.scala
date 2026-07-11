package ssc.plugin.content

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Prims, Runtime, V2PluginRegistry, Value}
import ssc.plugin.{NativeContentModule, NativePluginHost, NativeRuntimeConfig}

class ContentNativePluginTest extends AnyFunSuite:
  private val nil = Value.DataV("Nil", Vector.empty)
  private val none = Value.DataV("None", Vector.empty)
  private def list(values: Value*): Value =
    values.foldRight[Value](nil)((head, tail) => Value.DataV("Cons", Vector(head, tail)))
  private def str(value: String): Value = Value.DataV("Str", Vector(Value.StrV(value)))
  private def attrs(values: (String, String)*): Value =
    Value.MapV.from(values.map { case (key, value) => Value.StrV(key) -> str(value) })
  private def emptyManifest: Value = Value.DataV("MapV", Vector(Value.MapV.empty))

  private def section(id: String, title: String, blockValues: Value*): Value =
    Value.DataV("SectionContent", Vector(
      Value.StrV(id), Value.IntV(1), Value.StrV(title), attrs("id" -> id),
      list(blockValues*), nil))

  private def document(sectionValues: Value*): Value =
    Value.DataV("DocumentContent", Vector(
      emptyManifest, none, none, Value.MapV.empty, list(sectionValues*), nil))

  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookupGlobal(name).get match
      case closure: Value.ClosV =>
        Runtime.run(closure.code, Runtime.extend(closure.env, args.toArray))
      case other => fail(s"$name is not callable: $other")

  test("current and imported structural content lookup and rendering are deterministic"):
    val paragraph = Value.DataV("Paragraph", Vector(
      list(Value.DataV("Text", Vector(Value.StrV("Hello")))), attrs("kind" -> "summary")))
    val embedded = Value.DataV("Embedded", Vector(
      Value.StrV("yaml"), Value.StrV("answer: 42"), Value.DataV("StructuredData", Vector.empty),
      none, attrs("id" -> "data")))
    val rootDoc = document(section("brief", "Brief", paragraph, embedded))
    val importedDoc = document(section("minor-units", "Minor units"))
    val root = NativeContentModule(
      "main.ssc", explicitRoot = true,
      List("std/content.ssc", "std/money.ssc"), "main", rootDoc)
    val imported = NativeContentModule(
      "std/money.ssc", explicitRoot = false, Nil, "std-money", importedDoc)
    NativePluginHost.installProviders(
      List(ContentNativePlugin()),
      NativeRuntimeConfig(contentModules = List(root, imported)))

    val brief = call("contentSection", Value.StrV("brief"))
    assert(brief match
      case Value.DataV("Some", IndexedSeq(Value.DataV("SectionContent", fields))) =>
        fields.head == Value.StrV("brief") && fields(2) == Value.StrV("Brief")
      case _ => false)
    assert(call("contentBlock", Value.StrV("data")) == Value.DataV("Some", Vector(embedded)))
    assert(call("contentModuleSection", Value.StrV("std-money"), Value.StrV("minor-units")) match
      case Value.DataV("Some", IndexedSeq(Value.DataV("SectionContent", fields))) =>
        fields.head == Value.StrV("minor-units") && fields(2) == Value.StrV("Minor units")
      case _ => false)
    val modules = call("contentModules") match
      case Value.MapV(entries) => entries
      case other => fail(s"expected content module map, got $other")
    assert(modules.keys.toList == List(Value.StrV("std-money")))

    val briefValue: Value = brief match
      case Value.DataV("Some", fields) => fields.head
      case _ => fail(s"expected Some section, got $brief")
    val rendered = call("contentToMarkdown", briefValue)
    assert(rendered == Value.StrV(
      "# Brief {#brief}\n\n" +
        "<!-- @meta kind=summary -->\nHello\n\n" +
        "```yaml @id=data\nanswer: 42\n```"))

  test("duplicate direct namespaces fail deterministically"):
    val root = NativeContentModule(
      "main.ssc", explicitRoot = true, List("a.ssc", "b.ssc"), "main", document())
    val first = NativeContentModule("a.ssc", false, Nil, "same", document())
    val second = NativeContentModule("b.ssc", false, Nil, "same", document())
    NativePluginHost.installProviders(
      List(ContentNativePlugin()),
      NativeRuntimeConfig(contentModules = List(root, first, second)))
    val error = intercept[IllegalArgumentException](call("contentModule", Value.StrV("same")))
    assert(error.getMessage == "contentModule(namespace): duplicate imported content namespace 'same'")
