package scalascript.interop.descriptor

import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite
import scala.compiletime.testing.typeCheckErrors

final class DescriptorCodecSuite extends AnyFunSuite:
  private val Utf8 = StandardCharsets.UTF_8
  private val I32 = AbiType.Primitive(AbiPrimitive.I32)
  private val I64 = AbiType.Primitive(AbiPrimitive.I64)

  private def must[A](value: Either[DescriptorError, A]): A =
    value.fold(error => fail(s"${error.code} at ${error.path}: ${error.message}"), identity)

  private def error[A](value: Either[DescriptorError, A]): DescriptorError =
    value.swap.getOrElse(fail("expected descriptor error"))

  private def code[A](value: Either[DescriptorError, A]): String =
    error(value).code

  private def basicApi(moduleId: String = "démo"): ApiDescriptor =
    must(DescriptorFactory.api(
      "ssc-control-v1",
      moduleId,
      Vector(ApiSymbolDefinition("demo.zero", ApiSymbolKind.Function, resultType = I32))
    ))

  private def richApi: ApiDescriptor =
    val binder = AbiTypeParameter(0, "T", kindArity = 0)
    val callbackType = AbiType.Function(
      Vector(Vector(I32)),
      I64,
      EffectRow(
        Vector(EffectRef("std.effect.Async", Vector(AbiType.TypeParameter(TypeParameterRef(0, 0))))),
        openTail = Some(TypeParameterRef(0, 0))
      )
    )
    val definition = ApiSymbolDefinition(
      qualifiedName = "demo.rich",
      kind = ApiSymbolKind.Function,
      typeParameters = Vector(binder),
      parameterLists = Vector(ApiParameterList(Vector(
        ApiParameter("callback", callbackType),
        ApiParameter("value", AbiType.TypeParameter(TypeParameterRef(0, 0)), hasDefault = true)
      ))),
      resultType = AbiType.Union(Vector(I64, I32)),
      effectRow = EffectRow(Vector(EffectRef("std.effect.Console"))),
      callbackPolicies = Vector(CallbackPolicy(
        CallbackParameterPath(0, 0),
        CallingConvention.ManagedControl,
        InvocationMultiplicity.Many,
        CallbackEscape.MayEscape,
        CallbackReentrancy.Reentrant,
        CallbackConcurrency.Concurrent,
        CallbackCancellation.Cancellable,
        ThreadAffinity.EventLoop("browser-main")
      )),
      promptAndControlMetadata = PromptAndControlMetadata(
        prompts = Vector(PromptMetadata(
          "prompt:demo",
          PromptRole.Reset,
          I64,
          PromptGenerativity.Fresh,
          PromptPortability.DurableCapability
        )),
        capturesContinuation = true,
        exposesContinuation = true
      ),
      requiredCapabilities = Vector("\uD800\uDC00", "\uE000"),
      requiredTargets = Vector("jvm", "js")
    )
    must(DescriptorFactory.api("ssc-control-v1", "demo", Vector(definition)))

  test("frozen wire fragments and restricted-JCS non-ASCII golden vector"):
    assert(must(CanonicalJson.text(TypeWire.writeType(I32))) ==
      "{\"tag\":\"Primitive\",\"value\":{\"tag\":\"I32\"}}")
    assert(must(CanonicalJson.text(TypeWire.writeType(AbiType.Named("std.String")))) ==
      "{\"arguments\":[],\"stableTypeId\":\"std.String\",\"tag\":\"Named\"}")
    val minimal = ApiDescriptor(
      DescriptorVersions.ApiDescriptor,
      "ssc-control-v1",
      "example",
      ApiHash("0" * 64),
      Vector.empty
    )
    assert(must(CanonicalJson.text(ApiWire.writeApi(minimal))) ==
      "{\"apiHash\":{\"value\":\"0000000000000000000000000000000000000000000000000000000000000000\"}," +
        "\"controlAbiVersion\":\"ssc-control-v1\",\"moduleId\":\"example\"," +
        "\"schemaVersion\":\"3.0\",\"symbols\":[]}")
    assert(must(DescriptorFactory.api("ssc-control-v1", "legacy", Vector.empty)).apiHash.value ==
      "b4786dafad1b156b0d9e86c430b71fe68c1e41883426f1847e3de779bbe3b1b2")

    val golden = ujson.Obj(
      "😀" -> ujson.Str("雪"),
      "z" -> ujson.Str("line\né"),
      "a" -> ujson.Str("\u0001")
    )
    assert(must(Jcs.render(golden)) == "{\"a\":\"\\u0001\",\"z\":\"line\\né\",\"😀\":\"雪\"}")

    val supplementary = "\uD800\uDC00" // U+10000 sorts before U+E000 as raw UTF-16.
    val bmp = "\uE000"
    val utf16Order = ujson.Obj(bmp -> ujson.Str("bmp"), supplementary -> ujson.Str("supplementary"))
    assert(must(Jcs.render(utf16Order)) ==
      s"{\"$supplementary\":\"supplementary\",\"$bmp\":\"bmp\"}")

  test("public descriptor models expose no implicit generic-library ReadWriter"):
    assert(typeCheckErrors("""
      import upickle.default.ReadWriter
      summon[ReadWriter[scalascript.interop.descriptor.ApiDescriptor]]
    """).nonEmpty)
    assert(typeCheckErrors("""
      import upickle.default.ReadWriter
      summon[ReadWriter[scalascript.interop.descriptor.ControlSummary]]
    """).nonEmpty)
    assert(typeCheckErrors("""
      import upickle.default.ReadWriter
      summon[ReadWriter[scalascript.interop.descriptor.ArtifactManifest]]
    """).nonEmpty)

  test("rich API round-trips every type, callback, prompt, and option field"):
    val api = richApi
    val bytes = must(DescriptorCodec.encodeApi(api))
    val decoded = must(DescriptorCodec.decodeApi(bytes))
    assert(decoded == api)

    val text = new String(bytes, Utf8)
    assert(text.contains("\"tag\":\"ManagedControl\""))
    assert(text.contains("\"tag\":\"EventLoop\""))
    assert(text.contains("\"openTail\":[{"))
    assert(text.contains("\"hasDefault\":true"))

  test("canonical admission rejects whitespace, key order, and alternate Unicode escaping"):
    val bytes = must(DescriptorCodec.encodeApi(basicApi()))
    val text = new String(bytes, Utf8)

    assert(code(DescriptorCodec.decodeApi((" " + text).getBytes(Utf8))) == "NON_CANONICAL_JSON")

    val parsed = ujson.read(text).obj
    val reversed = ujson.Obj.from(parsed.value.toVector.reverse)
    assert(code(DescriptorCodec.decodeApi(ujson.write(reversed).getBytes(Utf8))) == "NON_CANONICAL_JSON")

    val escaped = text.replace("démo", "d\\u00e9mo")
    assert(escaped != text)
    assert(code(DescriptorCodec.decodeApi(escaped.getBytes(Utf8))) == "NON_CANONICAL_JSON")

  test("exact schema rejects unknown, missing, mistagged, wrapper, and non-array Option shapes"):
    val encoded = must(DescriptorCodec.encodeApi(basicApi("demo")))

    def mutated(update: ujson.Value => Unit): Array[Byte] =
      val root: ujson.Value = ujson.read(new String(encoded, Utf8))
      update(root)
      must(Jcs.bytes(root))

    def expectSchema(label: String, bytes: Array[Byte]): Unit =
      DescriptorCodec.decodeApi(bytes) match
        case Left(descriptorError) =>
          assert(descriptorError.code == "SCHEMA_MISMATCH", s"$label: $descriptorError")
        case Right(_) => fail(s"$label should reject")

    val unknown = mutated(root => root.obj("unknown") = ujson.Str("value"))
    assert(new String(unknown, Utf8).contains("\"unknown\":\"value\""))
    expectSchema("unknown field", unknown)

    val missing = mutated(root => root.obj.remove("moduleId"))
    expectSchema("missing field", missing)

    val badTag = mutated { root =>
      val symbol = root.obj("symbols").arr.head
      val definition = symbol.obj("definition")
      definition.obj("kind") = ujson.Obj("tag" -> ujson.Str("UnknownKind"))
    }
    expectSchema("bad tag", badTag)

    val badWrapper = mutated { root =>
      root.obj("apiHash").obj("extra") = ujson.Str("value")
    }
    expectSchema("bad wrapper", badWrapper)

    val bareOption = mutated { root =>
      val symbol = root.obj("symbols").arr.head
      symbol.obj("overloadId") = symbol.obj("overloadId").arr.head
    }
    expectSchema("bare option", bareOption)

    val nullOption = mutated { root =>
      root.obj("symbols").arr.head.obj("overloadId") = ujson.Null
    }
    expectSchema("null option", nullOption)

    val multipleOption = mutated { root =>
      val symbol = root.obj("symbols").arr.head
      val overload = symbol.obj("overloadId").arr.head
      symbol.obj("overloadId") = ujson.Arr(overload, overload)
    }
    expectSchema("multiple option", multipleOption)

    val genericLibraryShape = mutated { root =>
      val definition = root.obj("symbols").arr.head.obj("definition")
      definition.obj("kind") = ujson.Arr(ujson.Str("Function"))
    }
    expectSchema("generic-library enum shape", genericLibraryShape)

    val text = new String(encoded, Utf8)
    val duplicateKey = text.replace(
      "\"moduleId\":\"demo\"",
      "\"moduleId\":\"demo\",\"moduleId\":\"demo\""
    )
    assert(duplicateKey != text)
    assert(code(DescriptorCodec.decodeApi(duplicateKey.getBytes(Utf8))) == "NON_CANONICAL_JSON")

  test("canonical admission rejects invalid numbers, UTF-8, BOM, depth, and container count"):
    val richText = new String(must(DescriptorCodec.encodeApi(richApi)), Utf8)
    Vector("0.0", "-1", "+1", "01", "1e0", "2147483648").foreach { invalidNumber =>
      val invalidIndex = richText.replace("\"index\":0", s"\"index\":$invalidNumber")
      assert(
        code(DescriptorCodec.decodeApi(invalidIndex.getBytes(Utf8))) == "INVALID_JSON_NUMBER",
        invalidNumber
      )
    }
    assert(Jcs.parseUtf8("[2147483647]".getBytes(Utf8)).isRight)

    assert(code(DescriptorCodec.decodeApi(Array(0xc3.toByte))) == "INVALID_UTF8")
    val bom = Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte) ++
      must(DescriptorCodec.encodeApi(basicApi()))
    assert(code(DescriptorCodec.decodeApi(bom)) == "INVALID_UTF8")

    val deep = ("[" * (DescriptorCodec.MaxDepth + 1) + "]" * (DescriptorCodec.MaxDepth + 1))
      .getBytes(Utf8)
    assert(code(DescriptorCodec.decodeApi(deep)) == "JSON_DEPTH_LIMIT")

    val tooMany = ("[" + ("0," * DescriptorCodec.MaxContainerItems) + "0]").getBytes(Utf8)
    assert(code(DescriptorCodec.decodeApi(tooMany)) == "JSON_CONTAINER_LIMIT")

  test("input, output, and in-memory type depth bounds return structured errors"):
    assert(code(DescriptorCodec.decodeApi(new Array[Byte](DescriptorCodec.MaxBytes + 1))) ==
      "INPUT_TOO_LARGE")

    val oversizedModule = "x" * DescriptorCodec.MaxBytes
    assert(code(DescriptorFactory.api(
      "ssc-control-v1",
      oversizedModule,
      Vector(ApiSymbolDefinition("demo.zero", ApiSymbolKind.Function, resultType = I32))
    )) == "OUTPUT_TOO_LARGE")

    var deeplyNested: AbiType = I32
    var depth = 0
    while depth < 10000 do
      deeplyNested = AbiType.Named("N", Vector(deeplyNested))
      depth += 1
    val definition = ApiSymbolDefinition("demo.deep", ApiSymbolKind.Function, resultType = deeplyNested)
    assert(code(DescriptorFactory.api("ssc-control-v1", "demo", Vector(definition))) ==
      "TYPE_DEPTH_LIMIT")
    assert(code(DescriptorHashes.stableSymbolId("demo", definition)) == "TYPE_DEPTH_LIMIT")

  test("factories preflight complete raw inputs before hashing individual members"):
    var deeplyNested: AbiType = I32
    var depth = 0
    while depth < 10000 do
      deeplyNested = AbiType.Named("N", Vector(deeplyNested))
      depth += 1

    val nonCallable = ApiSymbolDefinition(
      "demo.DeepType",
      ApiSymbolKind.Type,
      resultType = deeplyNested
    )
    val apiFailure = error(DescriptorFactory.api(
      "ssc-control-v1",
      "demo",
      Vector.fill(DescriptorCodec.MaxContainerItems + 1)(nonCallable)
    ))
    assert(apiFailure.code == "MODEL_CONTAINER_LIMIT")
    assert(apiFailure.path == "$.definitions")

    assert(code(DescriptorHashes.overloadId("demo", nonCallable)) == "TYPE_DEPTH_LIMIT")

    val oversized = Vector.fill(DescriptorCodec.MaxContainerItems + 1)("x")
    val dependency = DependencyBinding(
      DependencyKind.Plugin,
      "demo.plugin",
      "demo-plugin-v1",
      None,
      ImplementationDigest("0" * 64),
      None,
      requiredCapabilities = oversized
    )
    val artifactFailure = error(DescriptorFactory.artifactManifest(
      "ssc-control-v1",
      ApiHash("0" * 64),
      TargetProfile("jvm", "jvm-21", features = oversized),
      Vector.empty,
      None,
      None,
      "runtime",
      Vector(dependency),
      Vector.empty
    ))
    assert(artifactFailure.code == "MODEL_CONTAINER_LIMIT")
    assert(artifactFailure.path == "$.target.features")

  test("factory success guarantees the complete returned record is bounded-encodable"):
    val api = basicApi("demo")
    assert(DescriptorCodec.encodeApi(api).isRight)

    val symbolId = api.symbols.head.stableSymbolId
    val implementation = DescriptorHashes.implementation("implementation".getBytes(Utf8))
    val jvm = must(DescriptorFactory.jvmEntrypoint(
      symbolId,
      "demo/Main",
      "zero",
      "()I",
      JvmInvocationKind.Static,
      Vector("bridge"),
      "application",
      implementation
    ))
    assert(CanonicalJson.bytes(ArtifactWire.writeJvmEntrypoint(jvm)).isRight)

    val named = must(DescriptorFactory.namedEntrypoint(
      symbolId,
      "zero",
      "js-es2024",
      implementation
    ))
    assert(CanonicalJson.bytes(ArtifactWire.writeNamedEntrypoint(named)).isRight)

    val control = must(DescriptorFactory.controlSummary(
      "ssc-control-v1",
      "demo",
      api.apiHash
    ))
    assert(DescriptorCodec.encodeControlSummary(control).isRight)

    val artifact = must(DescriptorFactory.artifactManifest(
      "ssc-control-v1",
      api.apiHash,
      TargetProfile("jvm", "jvm-21"),
      Vector(TargetEntrypoint.Jvm(jvm)),
      Some(DescriptorHashes.program("program".getBytes(Utf8))),
      Some(DescriptorHashes.artifact("artifact".getBytes(Utf8))),
      "runtime-1",
      Vector.empty,
      Vector(control.summaryDigest)
    ))
    assert(DescriptorCodec.encodeArtifactManifest(artifact).isRight)

    val oversized = "x" * DescriptorCodec.MaxBytes
    assert(code(DescriptorFactory.controlSummary(
      "ssc-control-v1",
      oversized,
      api.apiHash
    )) == "OUTPUT_TOO_LARGE")
    assert(code(DescriptorFactory.jvmEntrypoint(
      symbolId,
      oversized,
      "zero",
      "()I",
      JvmInvocationKind.Static,
      Vector.empty,
      "application",
      implementation
    )) == "OUTPUT_TOO_LARGE")
    assert(code(DescriptorFactory.namedEntrypoint(
      symbolId,
      oversized,
      "js-es2024",
      implementation
    )) == "OUTPUT_TOO_LARGE")
    assert(code(DescriptorFactory.artifactManifest(
      "ssc-control-v1",
      api.apiHash,
      TargetProfile("jvm", "jvm-21", Vector(oversized)),
      Vector.empty,
      None,
      None,
      "runtime-1",
      Vector.empty,
      Vector.empty
    )) == "OUTPUT_TOO_LARGE")

  test("canonical self-hash tampering is rejected after byte admission"):
    val bytes = must(DescriptorCodec.encodeApi(basicApi()))
    val parsed = ujson.read(bytes).obj
    val hashObject = parsed("apiHash").obj
    hashObject("value") = ujson.Str("0" * 64)
    val tampered = must(Jcs.bytes(parsed))
    assert(code(DescriptorCodec.decodeApi(tampered)) == "API_HASH_MISMATCH")

  test("lone surrogates and overlarge render output fail without incidental exceptions"):
    assert(code(Jcs.render(ujson.Str("\uD800"))) == "INVALID_UNICODE")
    assert(code(Jcs.bytes(ujson.Str("x" * DescriptorCodec.MaxBytes))) == "OUTPUT_TOO_LARGE")

  test("byte, depth, and container admission accepts the exact boundary and rejects one over"):
    val exactString = "x" * (DescriptorCodec.MaxBytes - 2)
    val exactBytes = ("\"" + exactString + "\"").getBytes(Utf8)
    assert(exactBytes.length == DescriptorCodec.MaxBytes)
    assert(Jcs.parseUtf8(exactBytes).isRight)
    assert(must(Jcs.bytes(ujson.Str(exactString))).length == DescriptorCodec.MaxBytes)
    assert(code(Jcs.parseUtf8(("\"" + exactString + "x\"").getBytes(Utf8))) ==
      "INPUT_TOO_LARGE")
    assert(code(Jcs.bytes(ujson.Str(exactString + "x"))) == "OUTPUT_TOO_LARGE")

    val exactDepthText = "[" * DescriptorCodec.MaxDepth + "0" + "]" * DescriptorCodec.MaxDepth
    val exactDepth = must(Jcs.parseUtf8(exactDepthText.getBytes(Utf8)))
    assert(Jcs.bytes(exactDepth).isRight)
    val overDepthText = "[" * (DescriptorCodec.MaxDepth + 1) + "0" +
      "]" * (DescriptorCodec.MaxDepth + 1)
    assert(code(Jcs.parseUtf8(overDepthText.getBytes(Utf8))) == "JSON_DEPTH_LIMIT")

    val exactContainer = ("[" + Vector.fill(DescriptorCodec.MaxContainerItems)("0").mkString(",") + "]")
      .getBytes(Utf8)
    assert(Jcs.parseUtf8(exactContainer).isRight)
    val overContainer = ("[" + Vector.fill(DescriptorCodec.MaxContainerItems + 1)("0").mkString(",") + "]")
      .getBytes(Utf8)
    assert(code(Jcs.parseUtf8(overContainer)) == "JSON_CONTAINER_LIMIT")
    assert(DescriptorPreflight.stringVector(
      "$.items",
      Vector.fill(DescriptorCodec.MaxContainerItems)("x")
    ).isRight)
    assert(code(DescriptorPreflight.stringVector(
      "$.items",
      Vector.fill(DescriptorCodec.MaxContainerItems + 1)("x")
    )) == "MODEL_CONTAINER_LIMIT")
