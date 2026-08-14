package ssc.plugin.content

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Compiler, Const, Prims, Program, Runtime, Term, V2PluginRegistry, Value}
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

  // v1 renders both `<!-- @meta ... -->` and a fence's attr group in a CANONICAL order —
  // `metadataDirective` sorts every key (ContentIntrinsics.scala:1735) and `fenceAttrTokens`
  // puts `id` first and sorts the rest (:1741). v2 kept its LinkedHashMap SOURCE order, so the
  // same document rendered differently on the two lanes. Measured on `content-tables`:
  // v1 `<!-- @meta component=PlanTable id=plan-table -->` vs
  // v2 `<!-- @meta id=plan-table component=PlanTable -->`.
  // The attrs below are built in deliberately non-alphabetical insertion order, so an
  // insertion-order renderer cannot pass by accident. (v2-content-attr-order-not-canonical.)
  test("@meta and fence attrs render in v1's canonical order, not source order"):
    val paragraph = Value.DataV("Paragraph", Vector(
      list(Value.DataV("Text", Vector(Value.StrV("Body")))),
      attrs("id" -> "plan-table", "component" -> "PlanTable", "align" -> "end")))
    val embedded = Value.DataV("Embedded", Vector(
      Value.StrV("yaml"), Value.StrV("k: 1"), Value.DataV("StructuredData", Vector.empty),
      none, attrs("zone" -> "eu", "id" -> "cfg", "component" -> "Cfg")))
    val doc = document(section("s", "S", paragraph, embedded))
    NativePluginHost.installProviders(
      List(ContentNativePlugin()),
      NativeRuntimeConfig(contentModules = List(
        NativeContentModule("main.ssc", explicitRoot = true, Nil, "main", doc))))
    val rendered = call("contentToMarkdown", Value.StrV("s") match
      case _ => call("contentSection", Value.StrV("s")) match
        case Value.DataV("Some", fields) => fields.head
        case other => fail(s"expected Some section, got $other"))
    val text = rendered match
      case Value.StrV(t) => t
      case other => fail(s"expected a string, got $other")
    // @meta: every key sorted (align, component, id).
    assert(text.contains("<!-- @meta align=end component=PlanTable id=plan-table -->"),
      s"@meta attrs are not in canonical sorted order:\n$text")
    // fence: `@id` first, then the remaining keys sorted (component, zone).
    assert(text.contains("```yaml @id=cfg @component=Cfg @zone=eu"),
      s"fence attrs are not in canonical id-first-then-sorted order:\n$text")

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

  test("portable record copy accepts positional and explicit-name overrides"):
    NativePluginHost.installProviders(List(ContentNativePlugin()), NativeRuntimeConfig())
    def run(term: Term): Value =
      Runtime.run(Compiler.compile(Program(Nil, term)), Array.empty)
    def text(value: String): Term = Term.Lit(Const.CStr(value))
    val original = Term.Ctor("Text", List(text("old")))
    val positional = Term.Prim("__method__", List(text("copy"), original, text("new")))
    val named = Term.Prim("__method__", List(text("copy"), original, text("value"), text("named")))
    assert(run(positional) == Value.DataV("Text", Vector(Value.StrV("new"))))
    assert(run(named) == Value.DataV("Text", Vector(Value.StrV("named"))))

  // `contentToolkitBlock(id)` — the registration that was missing while its helper was present.
  //
  // The failure this pins was not a wrong answer: `V2PluginRegistry.lookupGlobal` had no entry, so
  // every program calling it was refused at LOAD with `unbound global: contentToolkitBlock` and
  // nothing ran at all. The first assertion below is therefore the absent-state control — it is
  // `false` on the code as it stood, and no later assertion can even be reached.
  test("contentToolkitBlock renders one @ui=toolkit block, and refuses the two ways it can miss"):
    def cvmap(entries: (String, Value)*): Value =
      Value.DataV("MapV", Vector(Value.MapV.from(entries.map { case (k, v) => Value.StrV(k) -> v })))
    val control  = cvmap("type" -> str("heading"), "text" -> str("Team"))
    val toolkit  = Value.DataV("Embedded", Vector(
      Value.StrV("yaml"), Value.StrV("controls: …"), Value.DataV("StructuredData", Vector.empty),
      Value.DataV("Some", Vector(cvmap("controls" -> control))),
      attrs("id" -> "team-controls", "ui" -> "toolkit")))
    // ANTI-ROW subject: same block shape, no `@ui=toolkit`. It is findable and NOT renderable,
    // which is the pair that tells a typo'd id apart from an unsupported one.
    val plainYaml = Value.DataV("Embedded", Vector(
      Value.StrV("yaml"), Value.StrV("answer: 42"), Value.DataV("StructuredData", Vector.empty),
      Value.DataV("Some", Vector(cvmap("answer" -> str("42")))),
      attrs("id" -> "plain")))
    val root = NativeContentModule(
      "main.ssc", explicitRoot = true, Nil, "main",
      document(section("team", "Team", toolkit, plainYaml)))
    NativePluginHost.installProviders(
      List(ContentNativePlugin()), NativeRuntimeConfig(contentModules = List(root)))

    // The control: the intrinsic EXISTS. Without the registration this is None and the suite stops.
    assert(V2PluginRegistry.lookupGlobal("contentToolkitBlock").isDefined,
      "contentToolkitBlock is not registered — a caller is refused at load, before anything runs")

    // One arg and two args, because the callers spell it both ways
    // (`examples/content-introspection.ssc` uses the one-arg form).
    val node = call("contentToolkitBlock", Value.StrV("team-controls"))
    assert(node == Value.DataV("HeadingNode", Vector(Value.IntV(2), Value.StrV("Team"))), node.toString)
    assert(call("contentToolkitBlock", Value.StrV("team-controls"), Value.UnitV) == node)

    // The section twin must still answer, and its children must CONTAIN the very value the block
    // selector returned — the same `toolkitBlockNode` serves both, so this is what says the two
    // selectors did not drift apart. Compared as VALUES, not as a rendered substring: the first
    // spelling of this row matched on `toString` and failed on `IntV(2)` vs `2`, i.e. on the
    // printer rather than on the tree.
    val sectionNode = call("contentToolkitSection", Value.StrV("team"))
    def items(v: Value): List[Value] = v match
      case Value.DataV("Cons", IndexedSeq(head, tail)) => head :: items(tail)
      case _                                           => Nil
    val sectionKids = sectionNode match
      case Value.DataV("VStackNode", IndexedSeq(_, kids)) => items(kids)
      case other => fail(s"contentToolkitSection did not answer a VStackNode: $other")
    assert(sectionKids.contains(node), sectionNode.toString)
    // …and the section adds its own heading on top of it, so this is not vacuously true.
    assert(sectionKids.head == Value.DataV("HeadingNode", Vector(Value.IntV(1), Value.StrV("Team"))),
      sectionKids.head.toString)

    // MISS 1 — no such id.
    val missing = intercept[IllegalArgumentException](call("contentToolkitBlock", Value.StrV("nope")))
    assert(missing.getMessage == "contentToolkitBlock: no block with id 'nope'", missing.getMessage)

    // MISS 2 — the id resolves to a block this selector cannot render. It must REFUSE and say so:
    // answering an empty fragment here would make an unsupported block look like an empty one.
    val notToolkit = intercept[IllegalArgumentException](call("contentToolkitBlock", Value.StrV("plain")))
    assert(notToolkit.getMessage.startsWith("contentToolkitBlock: block 'plain' is not a `@ui=toolkit` block"),
      notToolkit.getMessage)
