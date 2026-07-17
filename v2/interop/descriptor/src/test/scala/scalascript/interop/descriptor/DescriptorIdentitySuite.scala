package scalascript.interop.descriptor

import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite

final class DescriptorIdentitySuite extends AnyFunSuite:
  private val I32 = AbiType.Primitive(AbiPrimitive.I32, Some(NumericWidthEvidence.DeclaredInt))
  private val I64 = AbiType.Primitive(AbiPrimitive.I64, Some(NumericWidthEvidence.DeclaredLong))

  private def basicDefinition(result: AbiType = I32): ApiSymbolDefinition =
    ApiSymbolDefinition(
      qualifiedName = "demo.zero",
      kind = ApiSymbolKind.Function,
      resultType = result
    )

  private def must[A](value: Either[DescriptorError, A]): A =
    value.fold(error => fail(s"${error.code} at ${error.path}: ${error.message}"), identity)

  // RE-FROZEN 2026-07-17 (numeric-width-reconciliation, option A). These normative vectors moved
  // on purpose: `AbiType.Primitive` now retains source width evidence, and that evidence is part
  // of the identity bytes -- which is exactly what keeps `f(x: Int)` and `f(x: Long)` distinct
  // once both truthfully declare I64. A descriptor identity change is the accepted cost of the
  // decision; see `specs/numeric-width-reconciliation.md` §4.5.
  test("normative symbol, overload, JVM, and named entrypoint vectors"):
    val definition = basicDefinition()
    val symbolId = must(DescriptorHashes.stableSymbolId("demo", definition))
    val overloadId = must(DescriptorHashes.overloadId("demo", definition)).getOrElse(fail("missing overload"))

    assert(symbolId.value ==
      "ssc:symbol:v1:c6231fac17f3e3c57d6208e679aa040be9cf46c8de69f88697eeccca0191fb4a")
    assert(overloadId.value ==
      "ssc:overload:v1:2e2d67015b0a2ff6f9acf981eccd746866055d4e4d256ab143f340f7c6178ee8")

    val jvmId = must(DescriptorHashes.jvmEntrypointId(
      symbolId,
      "demo/Main",
      "zero",
      "()I",
      JvmInvocationKind.Static,
      Vector.empty,
      "application"
    ))
    assert(jvmId.value ==
      "ssc:jvm-entrypoint:v1:5c6533ab8b69e70bd7872c5a97c212518144fb85b08aa874a9accd14a8299140")

    val namedId = must(DescriptorHashes.namedEntrypointId(symbolId, "zero", "js-es2024"))
    assert(namedId.value ==
      "ssc:target-entrypoint:v1:d1a0d6b6ab0d6fea59ee7f6d16085159cca546fa1704dcf24e76fc14bbd40a72")

  test("nested type-lambda alpha rename preserves ids after erase then renormalize"):
    def lambda(name: String, variance: Variance): AbiType =
      AbiType.TypeLambda(
        Vector(AbiTypeParameter(0, name, variance)),
        I32
      )

    val first = basicDefinition(AbiType.Union(Vector(
      lambda("z", Variance.Contravariant),
      lambda("a", Variance.Covariant)
    )))
    val renamed = basicDefinition(AbiType.Union(Vector(
      lambda("a", Variance.Contravariant),
      lambda("z", Variance.Covariant)
    )))

    val firstApi = must(DescriptorFactory.api("ssc-control-v1", "demo", Vector(first)))
    val renamedApi = must(DescriptorFactory.api("ssc-control-v1", "demo", Vector(renamed)))

    assert(firstApi.symbols.head.stableSymbolId == renamedApi.symbols.head.stableSymbolId)
    assert(firstApi.symbols.head.overloadId == renamedApi.symbols.head.overloadId)
    assert(firstApi.apiHash != renamedApi.apiHash)

    val semanticChange = basicDefinition(AbiType.Union(Vector(
      lambda("x", Variance.Contravariant),
      AbiType.TypeLambda(Vector(AbiTypeParameter(0, "y", Variance.Covariant)), I64)
    )))
    assert(
      must(DescriptorHashes.stableSymbolId("demo", semanticChange)) !=
        firstApi.symbols.head.stableSymbolId
    )

  test("alpha erasure preserves variance, bounds, and body semantics"):
    def lambda(
        name: String,
        variance: Variance = Variance.Invariant,
        lowerBound: Option[AbiType] = None,
        upperBound: AbiType = I32,
        body: AbiType = AbiType.TypeParameter(TypeParameterRef(0, 0))
    ): AbiType =
      AbiType.TypeLambda(
        Vector(AbiTypeParameter(
          0,
          name,
          variance,
          lowerBound = lowerBound,
          upperBound = Some(upperBound)
        )),
        body
      )

    def ids(value: AbiType): (StableSymbolId, Option[OverloadId]) =
      val definition = basicDefinition(value)
      must(DescriptorHashes.stableSymbolId("demo", definition)) ->
        must(DescriptorHashes.overloadId("demo", definition))

    val baseline = ids(lambda("T"))
    assert(ids(lambda("Renamed")) == baseline)
    assert(ids(lambda("T", variance = Variance.Covariant)) != baseline)
    assert(ids(lambda("T", lowerBound = Some(I32))) != baseline)
    assert(ids(lambda("T", upperBound = I64)) != baseline)
    assert(ids(lambda("T", body = I64)) != baseline)

  test("entrypoint identity excludes implementation digest and raw digest wrappers stay distinct"):
    val symbolId = must(DescriptorHashes.stableSymbolId("demo", basicDefinition()))
    val firstImplementation = DescriptorHashes.implementation("one".getBytes(StandardCharsets.UTF_8))
    val secondImplementation = DescriptorHashes.implementation("two".getBytes(StandardCharsets.UTF_8))
    val first = must(DescriptorFactory.jvmEntrypoint(
      symbolId,
      "demo/Main",
      "zero",
      "()I",
      JvmInvocationKind.Static,
      Vector("bridge"),
      "application",
      firstImplementation
    ))
    val second = must(DescriptorFactory.jvmEntrypoint(
      symbolId,
      "demo/Main",
      "zero",
      "()I",
      JvmInvocationKind.Static,
      Vector("bridge"),
      "application",
      secondImplementation
    ))
    assert(first.entrypointId == second.entrypointId)
    assert(first.implementationDigest != second.implementationDigest)

    val bytes = "same".getBytes(StandardCharsets.UTF_8)
    val program = DescriptorHashes.program(bytes)
    val artifact = DescriptorHashes.artifact(bytes)
    assert(program.value == artifact.value)
    assert(program != artifact)

  test("all string sets use unsigned canonical UTF-8 byte ordering"):
    val supplementary = "\uD800\uDC00" // U+10000; UTF-16 sorts before U+E000.
    val bmp = "\uE000"
    assert(DescriptorNormalization.strings(Vector(supplementary, bmp, supplementary)) == Vector(bmp, supplementary))
