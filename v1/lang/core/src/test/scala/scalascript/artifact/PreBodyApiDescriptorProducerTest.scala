package scalascript.artifact

import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import scala.meta.Source

import scalascript.ast.{ContentBlock, EmbeddedKind}
import scalascript.interop.descriptor.*
import scalascript.parser.Parser

class PreBodyApiDescriptorProducerTest extends AnyFunSuite:

  private def parse(source: String): scalascript.ast.Module = Parser.parse(source)

  private def descriptor(source: String): ApiDescriptor =
    right(PreBodyApiDescriptorProducer.descriptor(parse(source)))

  private def managed(source: String): scalascript.ir.ModuleInterface =
    right(InterfaceExtractor.extractManaged(parse(source), source.getBytes(StandardCharsets.UTF_8)))

  private def failure[A](value: Either[DescriptorError, A]): DescriptorError = value match
    case Left(error) => error
    case Right(result) => fail(s"expected descriptor failure, got $result")

  private def right[A](value: Either[DescriptorError, A]): A = value match
    case Right(result) => result
    case Left(error) => fail(s"unexpected ${error.code} at ${error.path}: ${error.message}")

  private def source(body: String, exports: List[String], name: String = "demo", pkg: String = "demo.api"): String =
    val exportLines = exports.map(value => s"  - $value").mkString("\n")
    s"""---
       |name: $name
       |package: $pkg
       |exports:
       |$exportLines
       |---
       |
       |# API
       |
       |```scalascript
       |$body
       |```
       |""".stripMargin

  test("managed extraction stores canonical v3 while ordinary legacy extraction keeps None"):
    val input = source(
      "def widen[A](value: Int, delta: Long = 0L)(using fallback: A): Long = delta",
      List("widen")
    )
    val module = parse(input)
    val ordinary = InterfaceExtractor.extract(module, input.getBytes(StandardCharsets.UTF_8))
    val strict = managed(input)

    assert(ordinary.apiDescriptorV3.isEmpty)
    val bytes = strict.apiDescriptorV3.get.getBytes(StandardCharsets.UTF_8)
    val api = right(DescriptorCodec.decodeApi(bytes))
    assert(right(DescriptorCodec.encodeApi(api)).sameElements(bytes))
    assert(api.moduleId == "demo")

    val definition = api.symbols.map(_.definition).find(_.qualifiedName == "demo.api.widen").get
    assert(definition.kind == ApiSymbolKind.Function)
    assert(definition.typeParameters.map(_.name) == Vector("A"))
    assert(definition.parameterLists.size == 2)
    assert(definition.parameterLists.head.parameters.map(_.mode) ==
      Vector(ParameterMode.Value, ParameterMode.Value))
    assert(definition.parameterLists.head.parameters(1).hasDefault)
    assert(definition.parameterLists(1).parameters.map(_.mode) == Vector(ParameterMode.Contextual))
    assert(definition.parameterLists(1).parameters.head.tpe ==
      AbiType.TypeParameter(TypeParameterRef(0, 0, 0)))
    assert(definition.parameterLists.head.parameters.head.tpe ==
      AbiType.Primitive(AbiPrimitive.I32))
    assert(definition.resultType == AbiType.Primitive(AbiPrimitive.I64))

  test("body-only edits keep pre-body bytes and apiHash stable while width edits do not"):
    val first = source(
      "def width(value: List[Int]): Long ! std.effect.State[Long] = value.head.toLong",
      List("width")
    )
    val second = source(
      "def width(value: List[Int]): Long ! std.effect.State[Long] = 999L",
      List("width")
    )
    val changedWidth = source(
      "def width(value: List[Long]): Int ! std.effect.State[Int] = 0",
      List("width")
    )

    val firstJson = right(PreBodyApiDescriptorProducer.canonicalJson(parse(first)))
    val secondJson = right(PreBodyApiDescriptorProducer.canonicalJson(parse(second)))
    val firstApi = descriptor(first)
    val changedApi = descriptor(changedWidth)
    val firstSymbol = firstApi.symbols.head
    val changedSymbol = changedApi.symbols.head

    assert(firstJson == secondJson)
    assert(firstApi.apiHash == descriptor(second).apiHash)
    assert(firstApi.apiHash != changedApi.apiHash)
    assert(firstSymbol.stableSymbolId != changedSymbol.stableSymbolId)
    assert(firstSymbol.overloadId != changedSymbol.overloadId)
    assert(firstApi.symbols.head.definition.resultType == AbiType.Primitive(AbiPrimitive.I64))
    assert(changedApi.symbols.head.definition.resultType == AbiType.Primitive(AbiPrimitive.I32))
    assert(firstApi.symbols.head.definition.parameterLists.head.parameters.head.tpe ==
      AbiType.Named("std.List", Vector(AbiType.Primitive(AbiPrimitive.I32))))
    assert(firstApi.symbols.head.definition.effectRow.members.head.typeArguments ==
      Vector(AbiType.Primitive(AbiPrimitive.I64)))
    assert(changedApi.symbols.head.definition.effectRow.members.head.typeArguments ==
      Vector(AbiType.Primitive(AbiPrimitive.I32)))

  test("generic modes, tuples, unions, intersections, functions and callbacks stay structured"):
    val input = source(
      """trait Left
        |trait Right
        |def route[A](seed: => Int, rest: Long*)(using value: A, callback: Int => Long): (Left | Right, Left & Right) = ???
        |""".stripMargin,
      List("Left", "Right", "route")
    )
    val api = descriptor(input)
    val definition = api.symbols.map(_.definition).find(_.qualifiedName == "demo.api.route").get

    assert(definition.parameterLists.head.parameters.map(_.mode) ==
      Vector(ParameterMode.ByName, ParameterMode.Repeated))
    assert(definition.parameterLists(1).parameters.map(_.mode) ==
      Vector(ParameterMode.Contextual, ParameterMode.Contextual))
    assert(definition.parameterLists(1).parameters.head.tpe ==
      AbiType.TypeParameter(TypeParameterRef(0, 0, 0)))
    assert(definition.callbackPolicies.size == 1)
    val callback = definition.callbackPolicies.head
    assert(callback.parameter == CallbackParameterPath(1, 1))
    assert(callback.callingConvention == CallingConvention.ForeignBarrier)
    assert(callback.invocationMultiplicity == InvocationMultiplicity.Unknown)
    assert(callback.escape == CallbackEscape.MayEscape)
    assert(callback.threadAffinity == ThreadAffinity.AnyThread)
    assert(!definition.promptAndControlMetadata.capturesContinuation)
    assert(definition.resultType == AbiType.Tuple(Vector(
      AbiType.Union(Vector(
        AbiType.Named("demo.api.Left"),
        AbiType.Named("demo.api.Right")
      )),
      AbiType.Intersection(Vector(
        AbiType.Named("demo.api.Left"),
        AbiType.Named("demo.api.Right")
      ))
    )))

  test("nominal declarations, aliases, typed values and plain/multi effects project without bodies"):
    val input = source(
      """class Box[A](value: A)
        |val count: Long = 1L
        |type Pair[A] = (A, A)
        |type Mapper[A] = [B] =>> (A, B)
        |enum Choice:
        |  case First, Second
        |  case Empty()
        |  case Number(value: Long)
        |effect Logger:
        |  def write(message: String): Unit
        |multi effect Search:
        |  def choose(value: Int): Long
        |effect Empty:
        |def log(message: String): Unit ! Logger = Logger.write(message)
        |""".stripMargin,
      List("Box", "count", "Pair", "Mapper", "Choice", "Logger", "Search", "Empty", "log"),
      name = "model",
      pkg = "demo.model"
    )
    val definitions = descriptor(input).symbols.map(_.definition)

    val box = definitions.filter(_.qualifiedName == "demo.model.Box")
    assert(box.map(_.kind).toSet == Set(ApiSymbolKind.Type, ApiSymbolKind.Constructor))
    assert(box.forall(_.resultType == AbiType.Named(
      "demo.model.Box",
      Vector(AbiType.TypeParameter(TypeParameterRef(0, 0, 0)))
    )))
    assert(definitions.find(_.qualifiedName == "demo.model.count").get.resultType ==
      AbiType.Primitive(AbiPrimitive.I64))
    assert(definitions.find(_.qualifiedName == "demo.model.Pair").get.resultType ==
      AbiType.Tuple(Vector(
        AbiType.TypeParameter(TypeParameterRef(0, 0, 0)),
        AbiType.TypeParameter(TypeParameterRef(0, 0, 0))
      )))
    assert(definitions.find(_.qualifiedName == "demo.model.Mapper").get.resultType ==
      AbiType.TypeLambda(
        Vector(AbiTypeParameter(index = 0, name = "B")),
        AbiType.Tuple(Vector(
          AbiType.TypeParameter(TypeParameterRef(1, 0, 0)),
          AbiType.TypeParameter(TypeParameterRef(0, 0, 0))
        ))
      ))
    assert(definitions.find(_.qualifiedName == "demo.model.Choice").get.kind == ApiSymbolKind.Type)
    assert(definitions.find(_.qualifiedName == "demo.model.Choice.First").get.kind == ApiSymbolKind.Value)
    assert(definitions.find(_.qualifiedName == "demo.model.Choice.Second").get.kind == ApiSymbolKind.Value)
    assert(definitions.find(_.qualifiedName == "demo.model.Choice.Empty").get.kind == ApiSymbolKind.Constructor)
    assert(definitions.find(_.qualifiedName == "demo.model.Choice.Empty").get.parameterLists.size == 1)
    assert(definitions.find(_.qualifiedName == "demo.model.Choice.Number").get.kind == ApiSymbolKind.Constructor)

    val logger = definitions.find(_.qualifiedName == "demo.model.Logger").get
    val write = definitions.find(_.qualifiedName == "demo.model.Logger.write").get
    val choose = definitions.find(_.qualifiedName == "demo.model.Search.choose").get
    val empty = definitions.find(_.qualifiedName == "demo.model.Empty").get
    val log = definitions.find(_.qualifiedName == "demo.model.log").get
    assert(logger.kind == ApiSymbolKind.Effect)
    assert(write.kind == ApiSymbolKind.Operation)
    assert(write.operationResumeMultiplicity.contains(ResumeMultiplicity.OneShot))
    assert(write.effectRow.members.map(_.stableEffectId) == Vector("demo.model.Logger"))
    assert(choose.operationResumeMultiplicity.contains(ResumeMultiplicity.Reusable))
    assert(empty.kind == ApiSymbolKind.Effect)
    assert(log.effectRow.members.map(_.stableEffectId) == Vector("demo.model.Logger"))

  test("selected local types and effects normalize to their fully qualified descriptor ids"):
    val input = source(
      """object Domain:
        |  class Box(value: Int)
        |  effect Read:
        |    def get(): Long
        |def load(value: Domain.Box): Domain.Box ! Domain.Read = value
        |""".stripMargin,
      List("Domain", "load")
    )
    val definitions = descriptor(input).symbols.map(_.definition)
    val load = definitions.find(_.qualifiedName == "demo.api.load").get

    assert(definitions.exists(_.qualifiedName == "demo.api.Domain.Box"))
    assert(definitions.exists(_.qualifiedName == "demo.api.Domain.Read"))
    assert(load.parameterLists.head.parameters.head.tpe == AbiType.Named("demo.api.Domain.Box"))
    assert(load.resultType == AbiType.Named("demo.api.Domain.Box"))
    assert(load.effectRow.members.map(_.stableEffectId) == Vector("demo.api.Domain.Read"))

  test("lexical local types shadow frozen constructors even when only the referring function is exported"):
    val input = source(
      """class List[A]
        |def local(value: List[Int]): List[Long] = ???
        |""".stripMargin,
      List("local")
    )
    val local = descriptor(input).symbols.head.definition

    assert(local.parameterLists.head.parameters.head.tpe ==
      AbiType.Named("demo.api.List", Vector(AbiType.Primitive(AbiPrimitive.I32))))
    assert(local.resultType ==
      AbiType.Named("demo.api.List", Vector(AbiType.Primitive(AbiPrimitive.I64))))

  test("transparent callback aliases still receive the conservative foreign barrier policy"):
    val input = source(
      """type Callback[A] = A => Long
        |type Nested = Callback[Int]
        |type Id = Long
        |def run(callback: Nested, id: Id): Long = callback(id.toInt)
        |""".stripMargin,
      List("Callback", "Nested", "Id", "run")
    )
    val run = descriptor(input).symbols.map(_.definition)
      .find(_.qualifiedName == "demo.api.run").get

    assert(run.callbackPolicies.map(_.parameter) == Vector(CallbackParameterPath(0, 0)))
    assert(run.callbackPolicies.head.callingConvention == CallingConvention.ForeignBarrier)

  test("variance, bounds, higher-kinded arity and an open effect tail remain structured"):
    val input = source(
      """trait Lower
        |trait Upper
        |trait Kind[-A >: Lower <: Upper, +B, F[_]]
        |def poly[E](value: Int): Long ! E = value.toLong
        |""".stripMargin,
      List("Lower", "Upper", "Kind", "poly")
    )
    val definitions = descriptor(input).symbols.map(_.definition)
    val kind = definitions.find(_.qualifiedName == "demo.api.Kind").get
    val poly = definitions.find(_.qualifiedName == "demo.api.poly").get

    assert(kind.typeParameters.map(_.variance) ==
      Vector(Variance.Contravariant, Variance.Covariant, Variance.Invariant))
    assert(kind.typeParameters(0).lowerBound.contains(AbiType.Named("demo.api.Lower")))
    assert(kind.typeParameters(0).upperBound.contains(AbiType.Named("demo.api.Upper")))
    assert(kind.typeParameters(2).kindArity == 1)
    assert(poly.effectRow.openTail.contains(TypeParameterRef(0, 0, 0)))

  test("legacy body evidence is never parsed to invent a managed descriptor"):
    val input =
      """---
        |name: demo
        |exports:
        |  - inferred
        |---
        |
        |# API
        |
        |```scalascript
        |def inferred(value: Int) = 1L
        |```
        |""".stripMargin
    val module = parse(input)
    val legacy = InterfaceExtractor.extract(module, input.getBytes(StandardCharsets.UTF_8))

    assert(legacy.apiDescriptorV3.isEmpty)
    assert(legacy.exports.find(_.name == "inferred").exists(_.tpe.contains("Long")))
    val error = failure(InterfaceExtractor.extractManaged(
      module,
      input.getBytes(StandardCharsets.UTF_8)
    ))
    assert(error.code == "MISSING_PUBLIC_TYPE")
    assert(error.path == "$.symbols[demo.inferred].resultType")

  test("strict producer rejects dynamic, unsupported-width, ambiguous and platform types stably"):
    val dynamicSource = source(
      "def dynamic(value: Any): Int = 0",
      List("dynamic")
    )
    val dynamicModule = parse(dynamicSource)
    val dynamic = failure(PreBodyApiDescriptorProducer.descriptor(dynamicModule))
    assert(dynamic.code == "DYNAMIC_PUBLIC_TYPE")
    assert(dynamic.path == "$.symbols[demo.api.dynamic].parameterLists[0].parameters[0].tpe")
    assert(InterfaceExtractor.extract(
      dynamicModule,
      dynamicSource.getBytes(StandardCharsets.UTF_8)
    ).apiDescriptorV3.isEmpty)

    val width = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      "def narrow(value: Float): Int = 0",
      List("narrow")
    ))))
    assert(width.code == "UNSUPPORTED_NUMERIC_WIDTH")

    val ambiguous = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      "def imported(value: External): Int = 0",
      List("imported")
    ))))
    assert(ambiguous.code == "AMBIGUOUS_NAMED_TYPE")

    val platform = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      "def host(value: java.lang.String): Int = 0",
      List("host")
    ))))
    assert(platform.code == "PLATFORM_TYPE_FORBIDDEN")

    val absolutePlatform = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      "def absoluteHost(value: _root_.java.lang.String): Int = 0",
      List("absoluteHost")
    ))))
    assert(absolutePlatform.code == "PLATFORM_TYPE_FORBIDDEN")

  test("strict producer rejects inheritance and public instance or effect members it cannot encode"):
    val inherited = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      """trait Base
        |class Child extends Base
        |""".stripMargin,
      List("Base", "Child")
    ))))
    assert(inherited.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(inherited.path == "$.symbols[demo.api.Child]")

    val instanceMember = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      """class Box:
        |  def value: Int = 1
        |""".stripMargin,
      List("Box")
    ))))
    assert(instanceMember.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(instanceMember.path == "$.symbols[demo.api.Box]")

    val secondaryConstructor = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      """class Box():
        |  def this(value: Int) = this()
        |""".stripMargin,
      List("Box")
    ))))
    assert(secondaryConstructor.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(secondaryConstructor.path == "$.symbols[demo.api.Box]")

    val effectMember = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      """effect Read:
        |  def get(): Long
        |  val label: String = "read"
        |""".stripMargin,
      List("Read")
    ))))
    assert(effectMember.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(effectMember.path == "$.symbols[demo.api.Read]")

    val genericEnum = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      """enum Expr[A]:
        |  case Literal(value: Int) extends Expr[Int]
        |""".stripMargin,
      List("Expr")
    ))))
    assert(genericEnum.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(genericEnum.path == "$.symbols[demo.api.Expr]")

  test("abstract classes and non-public primary constructors expose only their nominal type"):
    val input = source(
      """abstract class AbstractToken
        |class Closed private ()
        |class Protected protected ()
        |""".stripMargin,
      List("AbstractToken", "Closed", "Protected")
    )
    val byName = descriptor(input).symbols.map(_.definition).groupBy(_.qualifiedName)

    Vector("AbstractToken", "Closed", "Protected").foreach { name =>
      assert(byName(s"demo.api.$name").map(_.kind) == Vector(ApiSymbolKind.Type))
    }

  test("strict producer requires a stable manifest module id"):
    val input =
      """# API
        |
        |```scalascript
        |def typed(value: Int): Long = 0L
        |```
        |""".stripMargin
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))
    assert(error.code == "MISSING_MODULE_ID")
    assert(error.path == "$.moduleId")

  test("package and exports select the same qualified public surface as the Scala facade"):
    val input = source(
      """def publicApi(value: Int): Long = value.toLong
        |def helper(value: Int): Long = value.toLong + 1L
        |""".stripMargin,
      List("publicApi"),
      name = "facade",
      pkg = "org.acme"
    )
    val iface = managed(input)
    val api = right(DescriptorCodec.decodeApi(
      iface.apiDescriptorV3.get.getBytes(StandardCharsets.UTF_8)
    ))
    val names = api.symbols.map(_.definition.qualifiedName).toSet

    assert(names == Set("org.acme.publicApi"))
    assert(iface.scalaFacade.keySet == Set("org.acme.publicApi"))
    assert(!iface.exports.exists(_.name == "helper"))

  test("package-wrapped placeholder aliases use the same normalized declaration shape"):
    val input = source(
      "type IntKey = Map[Int, _]",
      List("IntKey")
    )
    val alias = descriptor(input).symbols.head.definition

    assert(alias.qualifiedName == "demo.api.IntKey")
    assert(alias.resultType.isInstanceOf[AbiType.TypeLambda])

  test("a package wrapper with sibling AST declarations fails closed"):
    val input = source(
      "def stable(value: Int): Long = value.toLong",
      List("stable")
    )
    val parsed = parse(input)
    val changedSections = parsed.sections.map { section =>
      section.copy(content = section.content.map {
        case block: scalascript.ast.Content.CodeBlock =>
          block.tree match
            case Some(node) => node.tree match
              case parsedSource: Source =>
                block.copy(tree = Some(scalascript.ast.ScalaNode(
                  Source(parsedSource.stats ++ parsedSource.stats)
                )))
              case _ => block
            case None => block
        case other => other
      })
    }
    val error = failure(PreBodyApiDescriptorProducer.descriptor(
      parsed.copy(sections = changedSections)
    ))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path.startsWith("$.sections["))

  test("a manifest package wrapper must have the exact plain synthetic header"):
    val input = source(
      "def stable(value: Int): Long = value.toLong",
      List("stable")
    )
    val parsed = parse(input)
    val changedSections = parsed.sections.map { section =>
      section.copy(content = section.content.map {
        case block: scalascript.ast.Content.CodeBlock if block.tree.nonEmpty =>
          val forgedSource = block.source.replace(
            "object demo:",
            "object demo extends Serializable:"
          )
          val forgedTree = Parser.parseScalaSource(forgedSource)
            .getOrElse(fail("expected forged package wrapper to parse"))
          block.copy(tree = Some(forgedTree))
        case other => other
      })
    }
    val error = failure(PreBodyApiDescriptorProducer.descriptor(
      parsed.copy(sections = changedSections)
    ))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path.startsWith("$.sections["))

  test("effect header evidence is scoped to executable blocks and missing erased evidence fails closed"):
    val input =
      """---
        |name: effects
        |package: demo.effects
        |exports:
        |  - Plain
        |---
        |
        |# Notes
        |
        |effect Plain[ThisIsOnlyProse]: is documentation, not a declaration.
        |
        |```javascript
        |effect Plain[ThisIsJavaScript]:
        |```
        |
        |```scalascript
        |effect Plain:
        |  @internal def hidden(): Int
        |  def visible(): Long
        |```
        |""".stripMargin
    val parsed = parse(input)
    val definitions = descriptor(input).symbols.map(_.definition)

    assert(definitions.find(_.qualifiedName == "demo.effects.Plain").get.kind == ApiSymbolKind.Effect)
    assert(!definitions.exists(_.qualifiedName.endsWith(".hidden")))
    assert(definitions.find(_.qualifiedName == "demo.effects.Plain.visible").get.kind == ApiSymbolKind.Operation)

    val withoutHeaderEvidence = parsed.copy(sourceText = None, document = None)
    val missing = failure(PreBodyApiDescriptorProducer.descriptor(withoutHeaderEvidence))
    assert(missing.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(missing.path == "$.symbols[demo.effects.Plain]")

  test("same bare effect names bind declaration evidence in their lexical owners"):
    val input = source(
      """object Left:
        |  effect Choice:
        |    def pick(): Int
        |object Right:
        |  multi effect Choice:
        |    def pick(): Int
        |""".stripMargin,
      List("Left", "Right")
    )
    val definitions = descriptor(input).symbols.map(_.definition)
    val left = definitions.find(_.qualifiedName == "demo.api.Left.Choice.pick").get
    val right = definitions.find(_.qualifiedName == "demo.api.Right.Choice.pick").get

    assert(left.operationResumeMultiplicity.contains(ResumeMultiplicity.OneShot))
    assert(right.operationResumeMultiplicity.contains(ResumeMultiplicity.Reusable))

  test("strict producer fails closed when a declaration-bearing code block did not parse"):
    val input = source(
      "def broken(value: Int: Long = value.toLong",
      List("broken")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path.startsWith("$.sections["))

    val missingExport = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      "def present(value: Int): Long = value.toLong",
      List("missing")
    ))))
    assert(missingExport.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(missingExport.path == "$.symbols[demo.api.missing]")

  test("generic effect headers reject rather than losing erased type parameters"):
    val input = source(
      """effect State[S]:
        |  def get(): S
        |""".stripMargin,
      List("State")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))
    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path == "$.symbols[demo.api.State]")

  test("retained executable document source cannot silently lose its section AST"):
    val input = source(
      "def present(value: Int): Long = value.toLong",
      Nil
    )
    val parsed = parse(input)
    assert(parsed.document.nonEmpty)
    assert(parsed.sections.nonEmpty)

    val error = failure(PreBodyApiDescriptorProducer.descriptor(parsed.copy(sections = Nil)))
    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path == "$.sections")

  test("effect header evidence ignores block comments and string literals"):
    val body = List(
      "/*",
      "effect BlockCommentPhantom:",
      "*/",
      "val ordinary: String = \"effect InlineStringPhantom:\"",
      "val multiline: String = \"\"\"",
      "effect TripleStringPhantom:",
      "\"\"\"",
      "effect Real:",
      "  def read(): Long"
    ).mkString("\n")
    val definitions = descriptor(source(body, List("Real"))).symbols.map(_.definition)

    assert(definitions.map(_.qualifiedName).toSet == Set(
      "demo.api.Real",
      "demo.api.Real.read"
    ))

  test("an ordinary same-name object cannot steal a later lexical effect header"):
    val input = source(
      """object Left:
        |  object Choice:
        |    val marker: Int = 1
        |object Right:
        |  effect Choice:
        |    def pick(): Int
        |""".stripMargin,
      List("Right")
    )
    val definitions = descriptor(input).symbols.map(_.definition)

    assert(definitions.exists(_.qualifiedName == "demo.api.Right.Choice"))
    val operation = definitions.find(_.qualifiedName == "demo.api.Right.Choice.pick").get
    assert(operation.operationResumeMultiplicity.contains(ResumeMultiplicity.OneShot))

  test("trait constructor clauses reject until descriptor receiver metadata exists"):
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      "trait Configured(value: Int)",
      List("Configured")
    ))))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path == "$.symbols[demo.api.Configured]")

  test("trait self types reject instead of losing their nominal constraint"):
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      """trait Base
        |trait NeedsBase:
        |  self: Base =>
        |""".stripMargin,
      List("NeedsBase")
    ))))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path == "$.symbols[demo.api.NeedsBase]")

  test("template exports reject instead of disappearing from the public surface"):
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      """class Delegate:
        |  def exposed: Int = 1
        |class Facade:
        |  private val delegate: Delegate = new Delegate
        |  export delegate.exposed
        |""".stripMargin,
      List("Facade")
    ))))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path == "$.symbols[demo.api.Facade]")

  test("constructor val var and implicit case-class accessors reject losslessly"):
    val sources = Vector(
      "class ValField(val value: Int)" -> "ValField",
      "class VarField(var value: Int)" -> "VarField",
      "case class CaseField(value: Int)" -> "CaseField"
    )

    sources.foreach { case (declaration, name) =>
      val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
        declaration,
        List(name)
      ))))
      assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
      assert(error.path == s"$$.symbols[demo.api.$name]")
    }

  test("retained declaration source must correspond exactly to its stored section AST"):
    val input = source(
      """effect Real:
        |  def read(): Long
        |""".stripMargin,
      List("Real")
    )
    val parsed = parse(input)
    assert(descriptor(input).symbols.exists(_.definition.qualifiedName == "demo.api.Real.read"))

    val document = parsed.document.getOrElse(fail("expected retained document content"))
    val tamperedSections = document.sections.map { section =>
      section.copy(blocks = section.blocks.map {
        case ContentBlock.Embedded(lang, _, EmbeddedKind.Executable, data, attrs)
            if scalascript.ast.Lang.isParseable(lang) =>
          ContentBlock.Embedded(lang, "effect Real:\n", EmbeddedKind.Executable, data, attrs)
        case other => other
      })
    }
    val tampered = parsed.copy(document = Some(document.copy(sections = tamperedSections)))
    val error = failure(PreBodyApiDescriptorProducer.descriptor(tampered))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path.startsWith("$.sections["))

  test("retained body-only source changes may reuse the same declaration AST"):
    val input = source(
      """val seed: Long = 1L
        |def stable(value: Int, delta: Long = 1L): Long = value.toLong + delta
        |""".stripMargin,
      List("seed", "stable")
    )
    val parsed = parse(input)
    val expected = descriptor(input)
    val document = parsed.document.getOrElse(fail("expected retained document content"))
    val changedSections = document.sections.map { section =>
      section.copy(blocks = section.blocks.map {
        case ContentBlock.Embedded(lang, _, EmbeddedKind.Executable, data, attrs)
            if scalascript.ast.Lang.isParseable(lang) =>
          ContentBlock.Embedded(
            lang,
            """val seed: Long = 999L
              |def stable(value: Int, delta: Long = 999L): Long = value.toLong + delta + 999L
              |""".stripMargin,
            EmbeddedKind.Executable,
            data,
            attrs
          )
        case other => other
      })
    }
    val changed = right(PreBodyApiDescriptorProducer.descriptor(
      parsed.copy(document = Some(document.copy(sections = changedSections)))
    ))

    assert(changed == expected)

  test("a documentless packaged module still verifies CodeBlock source against its AST"):
    val input = source(
      "def stable(value: Int): Long = value.toLong",
      List("stable")
    )
    val parsed = parse(input)
    val changedSections = parsed.sections.map { section =>
      section.copy(content = section.content.map {
        case block: scalascript.ast.Content.CodeBlock if block.tree.nonEmpty =>
          block.copy(source = block.source.replace(
            "def stable(value: Int): Long",
            "def stale(value: Int): Long"
          ))
        case other => other
      })
    }
    val error = failure(PreBodyApiDescriptorProducer.descriptor(
      parsed.copy(sections = changedSections, document = None)
    ))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path.startsWith("$.sections["))

  test("document and CodeBlock retained sources cannot disagree on declaration headers"):
    val input = source(
      "def stable(value: Int): Long = value.toLong",
      List("stable")
    )
    val parsed = parse(input)
    val changedSections = parsed.sections.map { section =>
      section.copy(content = section.content.map {
        case block: scalascript.ast.Content.CodeBlock if block.tree.nonEmpty =>
          block.copy(source = block.source.replace(
            "def stable(value: Int): Long",
            "def stale(value: Int): Long"
          ))
        case other => other
      })
    }
    val error = failure(PreBodyApiDescriptorProducer.descriptor(
      parsed.copy(sections = changedSections)
    ))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path.startsWith("$.sections["))

  test("unsupported definition headers cannot hide behind a product-only witness"):
    val input = source(
      """given helper: Int = 1
        |def stable(value: Int): Long = value.toLong
        |""".stripMargin,
      List("stable")
    )
    val parsed = parse(input)
    val document = parsed.document.getOrElse(fail("expected retained document content"))
    val changedSections = document.sections.map { section =>
      section.copy(blocks = section.blocks.map {
        case ContentBlock.Embedded(lang, _, EmbeddedKind.Executable, data, attrs)
            if scalascript.ast.Lang.isParseable(lang) =>
          ContentBlock.Embedded(
            lang,
            """given helper: Long = 1L
              |def stable(value: Int): Long = value.toLong
              |""".stripMargin,
            EmbeddedKind.Executable,
            data,
            attrs
          )
        case other => other
      })
    }
    val error = failure(PreBodyApiDescriptorProducer.descriptor(
      parsed.copy(document = Some(document.copy(sections = changedSections)))
    ))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path.startsWith("$.sections["))

  test("a type under a private local owner cannot fall back to an external ABI name"):
    val input = source(
      """private object Hidden:
        |  type T = Int
        |def leak(value: Hidden.T): Hidden.T = value
        |""".stripMargin,
      List("leak")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "UNSUPPORTED_PUBLIC_TYPE")
    assert(error.path == "$.symbols[demo.api.leak].parameterLists[0].parameters[0].tpe")

  test("an absolute type under a private local owner cannot fall back to an external ABI name"):
    val input = source(
      """private object Hidden:
        |  type T = Int
        |def leak(value: _root_.demo.api.Hidden.T): Int = 0
        |""".stripMargin,
      List("leak")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "UNSUPPORTED_PUBLIC_TYPE")
    assert(error.path == "$.symbols[demo.api.leak].parameterLists[0].parameters[0].tpe")

  test("an internal qualified callback alias cannot bypass callback policy"):
    val input = source(
      """object Callbacks:
        |  @internal type Hidden = Int => Long
        |def run(callback: Callbacks.Hidden): Long = 0L
        |""".stripMargin,
      List("run")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "UNSUPPORTED_PUBLIC_TYPE")
    assert(error.path == "$.symbols[demo.api.run].parameterLists[0].parameters[0].tpe")

  test("a public alias cannot hide an internal callback alias from policy classification"):
    val input = source(
      """object Callbacks:
        |  @internal type Hidden = Int => Long
        |type PublicCallback = Callbacks.Hidden
        |def run(callback: PublicCallback): Long = 0L
        |""".stripMargin,
      List("run")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "UNSUPPORTED_PUBLIC_TYPE")
    assert(error.path == "$.symbols[demo.api.run].parameterLists[0].parameters[0].tpe")

  test("a non-public local Array cannot bypass visibility through the Bytes fast path"):
    val input = source(
      """@internal type Array[A] = List[A]
        |def leak(value: Array[Byte]): Bytes = ???
        |""".stripMargin,
      List("leak")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "UNSUPPORTED_PUBLIC_TYPE")
    assert(error.path == "$.symbols[demo.api.leak].parameterLists[0].parameters[0].tpe")

  test("a public local Array follows ordinary lexical constructor projection"):
    val input = source(
      """type Byte = Int
        |type Array[A] = List[A]
        |def retain(value: Array[Byte]): Int = 0
        |""".stripMargin,
      List("retain")
    )
    val definition = descriptor(input).symbols.head.definition

    assert(definition.parameterLists.head.parameters.head.tpe == AbiType.Named(
      "demo.api.Array",
      Vector(AbiType.Named("demo.api.Byte"))
    ))

  test("unshadowed Array Byte retains the primitive Bytes shortcut"):
    val input = source(
      "def retain(value: Array[Byte]): Bytes = value",
      List("retain")
    )
    val definition = descriptor(input).symbols.head.definition

    assert(definition.parameterLists.head.parameters.head.tpe ==
      AbiType.Primitive(AbiPrimitive.Bytes))
    assert(definition.resultType == AbiType.Primitive(AbiPrimitive.Bytes))

  test("a Byte type binder disables the primitive Bytes shortcut"):
    val input = source(
      "def retain[Byte](value: Array[Byte]): Int = 0",
      List("retain")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "AMBIGUOUS_NAMED_TYPE")
    assert(error.path == "$.symbols[demo.api.retain].parameterLists[0].parameters[0].tpe")

  test("an Array type binder disables the primitive Bytes shortcut"):
    val input = source(
      "def retain[Array](value: Array[Byte]): Int = 0",
      List("retain")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "UNSUPPORTED_NUMERIC_WIDTH")
    assert(error.path == "$.symbols[demo.api.retain].parameterLists[0].parameters[0].tpe.arguments[0]")

  test("a non-public local Byte disables the primitive Bytes shortcut"):
    Vector(
      "private type Byte = Int",
      "@internal type Byte = Int"
    ).foreach { declaration =>
      val input = source(
        s"""$declaration
           |def retain(value: Array[Byte]): Int = 0
           |""".stripMargin,
        List("retain")
      )
      val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

      assert(error.code == "UNSUPPORTED_PUBLIC_TYPE")
      assert(error.path == "$.symbols[demo.api.retain].parameterLists[0].parameters[0].tpe.arguments[0]")
    }

  test("a public local Byte follows ordinary projection instead of primitive Bytes"):
    val input = source(
      """type Byte = Int
        |def retain(value: Array[Byte]): Int = 0
        |""".stripMargin,
      List("retain")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "AMBIGUOUS_NAMED_TYPE")
    assert(error.path == "$.symbols[demo.api.retain].parameterLists[0].parameters[0].tpe")

  test("a private local effect cannot fall back to an external effect-row id"):
    val input = source(
      """private object Hidden:
        |  effect Read:
        |    def get(): Int
        |def load(): Int ! Hidden.Read = 0
        |""".stripMargin,
      List("load")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "INVALID_EFFECT_ROW")
    assert(error.path == "$.symbols[demo.api.load].resultType.effects")

  test("selected mutable vars reject until descriptor v3 represents mutability"):
    val immutable = descriptor(source("val current: Int = 1", List("current")))
    assert(immutable.symbols.map(_.definition.kind) == Vector(ApiSymbolKind.Value))

    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
      "var current: Int = 1",
      List("current")
    ))))
    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path == "$.symbols[demo.api.current]")
