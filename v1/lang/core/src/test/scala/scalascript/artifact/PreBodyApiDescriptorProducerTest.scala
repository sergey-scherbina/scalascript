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

  private def mapDocumentExecutableSources(
      module: scalascript.ast.Module
  )(f: String => String): scalascript.ast.Module =
    val document = module.document.getOrElse(fail("expected retained document content"))
    val sections = document.sections.map { section =>
      section.copy(blocks = section.blocks.map {
        case ContentBlock.Embedded(lang, body, EmbeddedKind.Executable, data, attrs)
            if scalascript.ast.Lang.isParseable(lang) =>
          ContentBlock.Embedded(lang, f(body), EmbeddedKind.Executable, data, attrs)
        case other => other
      })
    }
    module.copy(document = Some(document.copy(sections = sections)))

  private def mapSectionCodeBlockSources(
      module: scalascript.ast.Module
  )(f: String => String): scalascript.ast.Module =
    def rewrite(section: scalascript.ast.Section): scalascript.ast.Section =
      section.copy(
        content = section.content.map {
          case block: scalascript.ast.Content.CodeBlock if block.tree.nonEmpty =>
            block.copy(source = f(block.source))
          case other => other
        },
        subsections = section.subsections.map(rewrite)
      )
    module.copy(sections = module.sections.map(rewrite))

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

  test("derives clauses reject on every directly parseable nominal form"):
    val cases = Vector(
      "class Derived derives Eq" -> "Derived",
      "trait Derived derives Eq" -> "Derived",
      "enum Derived derives Eq:\n  case One" -> "Derived"
    )

    cases.foreach { case (declaration, name) =>
      val input = source(declaration, List(name))
      val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))
      assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
      assert(error.path == s"$$.symbols[demo.api.$name]")
    }

  test("early initializer clauses reject on directly parseable nominal forms"):
    val declarations = Vector(
      "trait Base\nclass Early extends { val seed: Int = 1 } with Base",
      "trait Base\ntrait Early extends { val seed: Int = 1 } with Base",
      "trait Base\nobject Early extends { val seed: Int = 1 } with Base"
    )

    declarations.foreach { declaration =>
      val input = source(declaration, List("Early"))
      val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))
      assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
      assert(error.path == "$.symbols[demo.api.Early]")
    }

  test("derives headers participate in both retained source carriers"):
    val derivesInput = source("class Derived derives Eq", List("Derived"))
    val parsedDerives = parse(derivesInput)
    val staleDocument = mapDocumentExecutableSources(parsedDerives)(
      _.replace(" derives Eq", "")
    )
    val documentError = failure(PreBodyApiDescriptorProducer.descriptor(staleDocument))
    assert(documentError.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(documentError.path.startsWith("$.sections["))

    val staleCodeBlock = mapSectionCodeBlockSources(parsedDerives)(
      _.replace(" derives Eq", "")
    )
    val codeBlockError = failure(PreBodyApiDescriptorProducer.descriptor(staleCodeBlock))
    assert(codeBlockError.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(codeBlockError.path.startsWith("$.sections["))

  test("early initializer headers participate in both retained source carriers"):
    val earlyInput = source(
      "trait Base\nclass Early extends { val seed: Int = 1 } with Base",
      List("Early")
    )
    val parsedEarly = parse(earlyInput)
    val staleDocument = mapDocumentExecutableSources(parsedEarly)(
      _.replace("extends { val seed: Int = 1 } with Base", "extends Base")
    )
    val documentError = failure(PreBodyApiDescriptorProducer.descriptor(staleDocument))
    assert(documentError.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(documentError.path.startsWith("$.sections["))

    val staleCodeBlock = mapSectionCodeBlockSources(parsedEarly)(
      _.replace("extends { val seed: Int = 1 } with Base", "extends Base")
    )
    val codeBlockError = failure(PreBodyApiDescriptorProducer.descriptor(staleCodeBlock))
    assert(codeBlockError.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(codeBlockError.path.startsWith("$.sections["))

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

  test("dual carriers distinguish an empty effect from an ordinary object"):
    val effectInput = source("effect Empty:\n", List("Empty"))
    assert(descriptor(effectInput).symbols.head.definition.kind == ApiSymbolKind.Effect)
    val staleObject = mapDocumentExecutableSources(parse(effectInput))(
      _.replace("effect Empty:", "object Empty:")
    )
    val effectError = failure(PreBodyApiDescriptorProducer.descriptor(staleObject))
    assert(effectError.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(effectError.path.startsWith("$.sections["))

    val objectInput = source("object Empty:\n  ()\n", List("Empty"))
    assert(descriptor(objectInput).symbols.head.definition.kind == ApiSymbolKind.Value)
    val staleEffect = mapDocumentExecutableSources(parse(objectInput))(
      _.replace("object Empty:", "effect Empty:")
    )
    val objectError = failure(PreBodyApiDescriptorProducer.descriptor(staleEffect))
    assert(objectError.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(objectError.path.startsWith("$.sections["))

  test("dual carriers compare plain and multi effect evidence before selection"):
    val multiInput = source("multi effect Signal:\n", List("Signal"))
    val stalePlain = mapDocumentExecutableSources(parse(multiInput))(
      _.replace("multi effect Signal:", "effect Signal:")
    )
    val multiError = failure(PreBodyApiDescriptorProducer.descriptor(stalePlain))
    assert(multiError.code == "UNSUPPORTED_PUBLIC_DECLARATION")

    val plainInput = source(
      """effect Signal:
        |  def read(): Int
        |""".stripMargin,
      List("Signal")
    )
    val staleMulti = mapDocumentExecutableSources(parse(plainInput))(
      _.replace("effect Signal:", "multi effect Signal:")
    )
    val plainError = failure(PreBodyApiDescriptorProducer.descriptor(staleMulti))
    assert(plainError.code == "UNSUPPORTED_PUBLIC_DECLARATION")

  test("raw effect evidence ignores carrier line offsets but not erased unsupported shape"):
    val input = source("effect Empty:\n", List("Empty"))
    val expected = descriptor(input)
    val shifted = mapDocumentExecutableSources(parse(input))(body => s"\n\n$body")
    assert(right(PreBodyApiDescriptorProducer.descriptor(shifted)) == expected)

    val genericInput = source("effect State[A]:\n", List("State"))
    val staleObject = mapDocumentExecutableSources(parse(genericInput))(
      _.replace("effect State[A]:", "object State:")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(staleObject))
    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path.startsWith("$.sections["))

  test("documentless empty effects fail closed while ordinary objects remain safe"):
    val effectInput = source("effect Empty:\n", List("Empty"))
    val effectError = failure(PreBodyApiDescriptorProducer.descriptor(
      parse(effectInput).copy(document = None)
    ))
    assert(effectError.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(effectError.path == "$.symbols[demo.api.Empty]")

    val objectInput = source("object Empty:\n  ()\n", List("Empty"))
    val ordinary = right(PreBodyApiDescriptorProducer.descriptor(
      parse(objectInput).copy(document = None)
    ))
    assert(ordinary.symbols.head.definition.kind == ApiSymbolKind.Value)

  test("reserved effect origin sentinels are non-API and collisions fail closed"):
    val effect = descriptor(source("effect Empty:\n", List("Empty")))
    assert(effect.symbols.map(_.definition.qualifiedName) == Vector("demo.api.Empty"))
    assert(!effect.symbols.exists(_.definition.qualifiedName.contains("__effect")))

    val collision = source(
      """object Empty:
        |  private type __effectDecl__ = true
        |""".stripMargin,
      List("Empty")
    )
    val parsedCollision = parse(collision)
    val collisionError = failure(PreBodyApiDescriptorProducer.descriptor(parsedCollision))
    assert(collisionError.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(collisionError.path.startsWith("$.sections["))

    val documentlessError = failure(PreBodyApiDescriptorProducer.descriptor(
      parsedCollision.copy(document = None)
    ))
    assert(documentlessError.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(documentlessError.path == "$.symbols[demo.api.Empty]")

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

  test("direct imports resolve both Array and Byte before the Bytes shortcut"):
    val input = source(
      """import foo.Array
        |import foo.Byte
        |def retain(value: Array[Byte]): Int = 0
        |""".stripMargin,
      List("retain")
    )
    val parameter = descriptor(input).symbols.head.definition.parameterLists.head.parameters.head

    assert(parameter.tpe == AbiType.Named(
      "foo.Array",
      Vector(AbiType.Named("foo.Byte"))
    ))

  test("rename-to-name imports resolve before the Bytes shortcut"):
    val input = source(
      """import foo.Array
        |import foo.{Token as Byte}
        |def retain(value: Array[Byte]): Int = 0
        |""".stripMargin,
      List("retain")
    )
    val parameter = descriptor(input).symbols.head.definition.parameterLists.head.parameters.head

    assert(parameter.tpe == AbiType.Named(
      "foo.Array",
      Vector(AbiType.Named("foo.Token"))
    ))

  test("a wildcard import makes Array Byte ambiguous instead of primitive Bytes"):
    val input = source(
      """import foo.*
        |def retain(value: Array[Byte]): Int = 0
        |""".stripMargin,
      List("retain")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "AMBIGUOUS_NAMED_TYPE")
    assert(error.path == "$.symbols[demo.api.retain].parameterLists[0].parameters[0].tpe")

  test("exact and wildcard imports precede representative Int and List builtins"):
    val exactInput = source(
      """import foo.Int
        |def retain(value: Int): Int = value
        |""".stripMargin,
      List("retain")
    )
    val exact = descriptor(exactInput).symbols.head.definition
    assert(exact.parameterLists.head.parameters.head.tpe == AbiType.Named("foo.Int"))
    assert(exact.resultType == AbiType.Named("foo.Int"))

    val wildcardInput = source(
      """import foo.*
        |def retain(value: List[Int]): Int = 0
        |""".stripMargin,
      List("retain")
    )
    val wildcard = failure(PreBodyApiDescriptorProducer.descriptor(parse(wildcardInput)))
    assert(wildcard.code == "AMBIGUOUS_NAMED_TYPE")
    assert(wildcard.path == "$.symbols[demo.api.retain].parameterLists[0].parameters[0].tpe")

  test("imports are source ordered and nested import scopes do not leak"):
    val input = source(
      """def before(value: Int): Int = value
        |import foo.Int
        |def after(value: Int): Int = value
        |object Nested:
        |  import bar.Long
        |  def inside(value: Long): Long = value
        |def outside(value: Long): Long = value
        |""".stripMargin,
      List("before", "after", "Nested", "outside")
    )
    val definitions = descriptor(input).symbols.map(_.definition).map(d => d.qualifiedName -> d).toMap

    assert(definitions("demo.api.before").resultType == AbiType.Primitive(AbiPrimitive.I32))
    assert(definitions("demo.api.after").resultType == AbiType.Named("foo.Int"))
    assert(definitions("demo.api.Nested.inside").resultType == AbiType.Named("bar.Long"))
    assert(definitions("demo.api.outside").resultType == AbiType.Primitive(AbiPrimitive.I64))

  test("conflicting exact imports fail while given-only selectors do not bind type names"):
    val conflict = source(
      """import foo.Int
        |import bar.Int
        |def retain(value: Int): Int = value
        |""".stripMargin,
      List("retain")
    )
    val conflictError = failure(PreBodyApiDescriptorProducer.descriptor(parse(conflict)))
    assert(conflictError.code == "AMBIGUOUS_NAMED_TYPE")
    assert(conflictError.path == "$.symbols[demo.api.retain].parameterLists[0].parameters[0].tpe")

    val givenOnly = descriptor(source(
      """import foo.{given Int}
        |def retain(value: Int): Int = value
        |""".stripMargin,
      List("retain")
    )).symbols.head.definition
    assert(givenOnly.resultType == AbiType.Primitive(AbiPrimitive.I32))

  test("renamed-away and unimported names preserve provable builtin resolution"):
    val renamedAway = descriptor(source(
      """import foo.{Int as Other}
        |def retain(value: Int): Int = value
        |""".stripMargin,
      List("retain")
    )).symbols.head.definition
    assert(renamedAway.resultType == AbiType.Primitive(AbiPrimitive.I32))

    val unimported = descriptor(source(
      """import foo.{Int as _, *}
        |def retain(value: Int): Int = value
        |""".stripMargin,
      List("retain")
    )).symbols.head.definition
    assert(unimported.resultType == AbiType.Primitive(AbiPrimitive.I32))

    val qualified = descriptor(source(
      "def retain(value: foo.Int): foo.Int = value",
      List("retain")
    )).symbols.head.definition
    assert(qualified.resultType == AbiType.Named("foo.Int"))

  test("an exact platform import cannot launder a bare builtin spelling"):
    val input = source(
      """import java.lang.{Integer as Int}
        |def retain(value: Int): Int = value
        |""".stripMargin,
      List("retain")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "PLATFORM_TYPE_FORBIDDEN")
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

  test("ordered imports participate in retained source and AST correspondence"):
    val input = source(
      """import foo.Int
        |def stable(value: Int): Int = value
        |""".stripMargin,
      List("stable")
    )
    val parsed = parse(input)
    val staleDocument = mapDocumentExecutableSources(parsed)(
      _.replace("import foo.Int", "import bar.Int")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(staleDocument))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path.startsWith("$.sections["))

  test("an imported qualifier cannot launder a selected platform type"):
    val input = source(
      """import java.{lang as jl}
        |def stable(value: jl.String): jl.String = value
        |""".stripMargin,
      List("stable")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "PLATFORM_TYPE_FORBIDDEN")
    assert(error.path == "$.symbols[demo.api.stable].parameterLists[0].parameters[0].tpe")

  test("a chained importer alias cannot launder a bare platform type"):
    val input = source(
      """import java.{lang as jl}
        |import jl.{Integer as Int}
        |def stable(value: Int): Int = value
        |""".stripMargin,
      List("stable")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "PLATFORM_TYPE_FORBIDDEN")
    assert(error.path == "$.symbols[demo.api.stable].parameterLists[0].parameters[0].tpe")

  test("an imported local callback alias receives conservative callback policy"):
    val input = source(
      """object Types:
        |  type Callback = Int => Long
        |import Types.Callback
        |def stable(callback: Callback): Long = 0L
        |""".stripMargin,
      List("stable")
    )
    val definition = descriptor(input).symbols.head.definition

    assert(definition.callbackPolicies.map(_.parameter) ==
      Vector(CallbackParameterPath(0, 0)))
    assert(definition.callbackPolicies.head.callingConvention ==
      CallingConvention.ForeignBarrier)

  test("a real effect cannot duplicate its parser-owned origin sentinel"):
    val input = source(
      """effect Stable:
        |  private type __effectDecl__ = true
        |""".stripMargin,
      List("Stable")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "UNSUPPORTED_PUBLIC_DECLARATION")
    assert(error.path.startsWith("$.sections[") || error.path == "$.symbols[demo.api.Stable]")

  test("body-local effects do not participate in pre-body descriptor evidence"):
    val plain = source(
      "def stable(value: Int): Int = value",
      List("stable")
    )
    val withLocalEffect = source(
      """def stable(value: Int): Int =
        |  effect Local:
        |    def read(): Int
        |  value
        |""".stripMargin,
      List("stable")
    )

    val expectedJson = right(PreBodyApiDescriptorProducer.canonicalJson(parse(plain)))
    val actualJson = right(PreBodyApiDescriptorProducer.canonicalJson(parse(withLocalEffect)))
    assert(actualJson == expectedJson)
    assert(descriptor(withLocalEffect).apiHash == descriptor(plain).apiHash)

  test("nested identities under private nominal owners cannot fall back external"):
    val declarations = Vector(
      """private class Hidden:
        |  type T = Int
        |""".stripMargin,
      """private trait Hidden:
        |  type T = Int
        |""".stripMargin,
      """private enum Hidden:
        |  case One
        |  type T = Int
        |""".stripMargin
    )

    declarations.foreach { declaration =>
      val input = source(
        s"""$declaration
           |def leak(value: Hidden.T): Int = 0
           |""".stripMargin,
        List("leak")
      )
      val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

      assert(error.code == "UNSUPPORTED_PUBLIC_TYPE")
      assert(error.path == "$.symbols[demo.api.leak].parameterLists[0].parameters[0].tpe")
    }

  test("a transparent alias keeps the import scope active at its declaration"):
    val input = source(
      """object Types:
        |  type Callback = Int => Long
        |object Facade:
        |  import demo.api.Types.Callback
        |  type Public = Callback
        |def stable(callback: Facade.Public): Long = 0L
        |""".stripMargin,
      List("stable")
    )
    val definition = descriptor(input).symbols.head.definition

    assert(definition.callbackPolicies.map(_.parameter) ==
      Vector(CallbackParameterPath(0, 0)))
    assert(definition.callbackPolicies.head.callingConvention ==
      CallingConvention.ForeignBarrier)

  test("an imported leading qualifier resolves a selected local callback alias"):
    val input = source(
      """object Types:
        |  type Callback = Int => Long
        |import demo.api.{Types as T}
        |def stable(callback: T.Callback): Long = 0L
        |""".stripMargin,
      List("stable")
    )
    val definition = descriptor(input).symbols.head.definition

    assert(definition.callbackPolicies.map(_.parameter) ==
      Vector(CallbackParameterPath(0, 0)))

  test("the lexical resolver is shared by imported local effect rows"):
    val input = source(
      """object Effects:
        |  effect Read:
        |    def get(): Int
        |import demo.api.{Effects as E}
        |def load(): Int ! E.Read = 0
        |""".stripMargin,
      List("load")
    )
    val definition = descriptor(input).symbols.head.definition

    assert(definition.effectRow.members.map(_.stableEffectId) ==
      Vector("demo.api.Effects.Read"))

  test("a wildcard import makes an unresolved selected prefix ambiguous"):
    val input = source(
      """import remote.*
        |def stable(value: Types.Value): Int = 0
        |""".stripMargin,
      List("stable")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "AMBIGUOUS_NAMED_TYPE")
    assert(error.path == "$.symbols[demo.api.stable].parameterLists[0].parameters[0].tpe")

  test("an imported private local identity rejects before external fallback"):
    val input = source(
      """private object Hidden:
        |  type T = Int
        |import demo.api.Hidden.T
        |def leak(value: T): Int = 0
        |""".stripMargin,
      List("leak")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "UNSUPPORTED_PUBLIC_TYPE")
    assert(error.path == "$.symbols[demo.api.leak].parameterLists[0].parameters[0].tpe")

  test("abstract and receiver-owned nominal identities remain known but nonrepresentable"):
    val declarations = Vector(
      """private class Hidden:
        |  type T
        |def leak(value: Hidden.T): Int = 0
        |""".stripMargin,
      """class Owner:
        |  type T = Int
        |def leak(value: Owner.T): Int = 0
        |""".stripMargin,
      """class Owner:
        |  object Inner:
        |    type T = Int
        |def leak(value: Owner.Inner.T): Int = 0
        |""".stripMargin
    )

    declarations.foreach { declaration =>
      val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(source(
        declaration,
        List("leak")
      ))))

      assert(error.code == "UNSUPPORTED_PUBLIC_TYPE")
      assert(error.path == "$.symbols[demo.api.leak].parameterLists[0].parameters[0].tpe")
    }

  test("a public object remains a representable local namespace"):
    val input = source(
      """object Types:
        |  type T = Int
        |def stable(value: Types.T): Types.T = value
        |""".stripMargin,
      List("stable")
    )
    val definition = descriptor(input).symbols.head.definition

    assert(definition.parameterLists.head.parameters.head.tpe ==
      AbiType.Named("demo.api.Types.T"))
    assert(definition.resultType == AbiType.Named("demo.api.Types.T"))

  test("a known local owner with an unknown member cannot become external"):
    val input = source(
      """object Types:
        |  type Known = Int
        |def leak(value: Types.Missing): Int = 0
        |""".stripMargin,
      List("leak")
    )
    val error = failure(PreBodyApiDescriptorProducer.descriptor(parse(input)))

    assert(error.code == "AMBIGUOUS_NAMED_TYPE")
    assert(error.path == "$.symbols[demo.api.leak].parameterLists[0].parameters[0].tpe")
