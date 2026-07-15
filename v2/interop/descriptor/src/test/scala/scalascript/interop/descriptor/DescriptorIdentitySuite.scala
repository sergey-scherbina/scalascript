package scalascript.interop.descriptor

import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite

final class DescriptorIdentitySuite extends AnyFunSuite:
  private val I32 = AbiType.Primitive(AbiPrimitive.I32)
  private val I64 = AbiType.Primitive(AbiPrimitive.I64)

  private def basicDefinition(result: AbiType = I32): ApiSymbolDefinition =
    ApiSymbolDefinition(
      qualifiedName = "demo.zero",
      kind = ApiSymbolKind.Function,
      resultType = result
    )

  private def must[A](value: Either[DescriptorError, A]): A =
    value.fold(error => fail(s"${error.code} at ${error.path}: ${error.message}"), identity)

  test("normative symbol, overload, JVM, and named entrypoint vectors"):
    val definition = basicDefinition()
    val symbolId = must(DescriptorHashes.stableSymbolId("demo", definition))
    val overloadId = must(DescriptorHashes.overloadId("demo", definition)).getOrElse(fail("missing overload"))

    assert(symbolId.value ==
      "ssc:symbol:v1:453bfef37e9c434783110ab89039214b9f8a7a998665c1125074c0d44d82faaf")
    assert(overloadId.value ==
      "ssc:overload:v1:a4daead86b456de1fe3ab86a936448c1546f09ed2ea812c95a689591e4d69fc9")

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
      "ssc:jvm-entrypoint:v1:20859d2db58ee7508193d092b2e3933c9273de8d0341f79900a7cdcd49cfae21")

    val namedId = must(DescriptorHashes.namedEntrypointId(symbolId, "zero", "js-es2024"))
    assert(namedId.value ==
      "ssc:target-entrypoint:v1:ec9ea38732c339e777dc59f6e5b4a09adf4ed9f4d31de0f350c97e352bbe9222")

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
