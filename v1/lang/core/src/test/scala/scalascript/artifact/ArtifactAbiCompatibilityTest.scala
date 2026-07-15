package scalascript.artifact

import java.security.MessageDigest
import java.util.Base64
import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.*
import upickle.default.{ReadWriter, read, readBinary, writeBinary}

/** v2.0 — forensic ABI / wire-format compatibility tests for all seven
 *  artifact formats:
 *
 *    `.scim`             — module interface          (`ModuleInterface`)
 *    `.scir`             — module IR body            (`ModuleIrArtifact`)
 *    `.scjvm`            — JVM cached source         (`ModuleJvmArtifact`)
 *    `.scjs`             — JS  cached source         (`ModuleJsArtifact`)
 *    `.scjvm-runtime`    — shared JVM runtime bundle (`ModuleJvmRuntimeArtifact`)
 *    `.scjs-runtime`     — shared JS  runtime bundle (`ModuleJsRuntimeArtifact`)
 *
 *  These pin down the contract between the compiler/linker and any
 *  third-party consumer (e.g. IDE tooling, alternative linkers) that
 *  reads the on-disk wire format:
 *
 *    1. Round-trip is byte-stable.
 *    2. Magic + ABI version are guarded at read time.
 *    3. Optional fields fall back to their schema defaults when absent.
 *    4. Required envelope fields (`magic`) are mandatory on read.
 *    5. Unknown future fields are tolerated (forward-compat).
 *    6. Source hash is preserved verbatim, not normalised.
 *    7. The seven artifact formats share the same envelope (`magic`,
 *       `abiVersion`) — the consumer disambiguates by file extension.
 *
 *  Pair-reads with `docs/v2.0-artifact-format.md` (the prose wire-spec).
 */
class ArtifactAbiCompatibilityTest extends AnyFunSuite:

  private val canonicalApiDescriptorV3 =
    "{\"apiHash\":{\"value\":\"b4786dafad1b156b0d9e86c430b71fe68c1e41883426f1847e3de779bbe3b1b2\"}," +
      "\"controlAbiVersion\":\"ssc-control-v1\",\"moduleId\":\"legacy\"," +
      "\"schemaVersion\":\"3.0\",\"symbols\":[]}"

  /** Literal fixture emitted by the last schema before `apiDescriptorV3`.
   *  It is intentionally not produced by the current test process. */
  private val preV3JsonFixture =
    """{"magic":"SSCART","abiVersion":"2.0","pkg":["legacy"],"moduleName":"pre-v3","moduleVersion":null,"sourceHash":"1111111111111111111111111111111111111111111111111111111111111111","exports":[]}"""

  /** MessagePack for the same pre-v3 value, captured before adding the field.
   *  SHA-256: a9436cdc278bda4c4da919e55da89a8986d477645fe31d35d0c8f99389c40c32. */
  private val preV3MessagePackBase64 =
    "h6VtYWdpY6ZTU0NBUlSqYWJpVmVyc2lvbqMyLjCjcGtnkaZsZWdhY3mqbW9kdWxlTmFtZaZwcmUtdjOtbW9kdWxlVmVyc2lvbsCqc291cmNlSGFzaNlAMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMadleHBvcnRzkA=="

  /** Reader shape compiled against the schema immediately before v3. */
  private case class LegacyModuleInterface(
      magic: String,
      abiVersion: String,
      pkg: List[String],
      moduleName: Option[String],
      moduleVersion: Option[String],
      sourceHash: String,
      exports: List[ExportedSymbol],
      instances: List[InstanceDecl] = Nil,
      capabilities: List[CapabilityDecl] = Nil,
      externDefs: List[ExportedSymbol] = Nil,
      dependencies: Map[String, String] = Map.empty,
      sectionHashes: Map[String, String] = Map.empty,
      sectionOwnHashes: Map[String, String] = Map.empty,
      sectionInterfaceHashes: Map[String, String] = Map.empty,
      scalaFacade: Map[String, String] = Map.empty
  ) derives ReadWriter

  // ── Canonical fixtures ────────────────────────────────────────────────

  private def sampleInterface: ModuleInterface =
    ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = List("org", "example", "ui"),
      moduleName    = Some("ui-kit"),
      moduleVersion = Some("0.1.0"),
      sourceHash    = "deadbeef" * 8,
      exports       = List(
        ExportedSymbol("Card",   "org_example_ui_Card",   "object"),
        ExportedSymbol("Button", "org_example_ui_Button", "object", "Any",
          nested = List(
            ExportedSymbol("apply", "org_example_ui_Button_apply", "def", "Any")
          )
        ),
        ExportedSymbol("render", "org_example_ui_render", "def", "String => Unit")
      ),
      instances     = List(
        InstanceDecl("Eq",  "Int", "eqInt",  "org_example_ui_eqInt"),
        InstanceDecl("Ord", "Int", "ordInt", "org_example_ui_ordInt")
      ),
      capabilities  = List(CapabilityDecl("Http"), CapabilityDecl("WebSocket")),
      externDefs    = List(
        ExportedSymbol("serve", "org_example_ui_serve", "extern", "Int => Unit"),
        ExportedSymbol("nowMs", "org_example_ui_nowMs", "extern", "Long")
      ),
      dependencies  = Map("std" -> "1.0.0", "http" -> "0.3.2"),
      // Tier 5 — facade is identity (natural FQN == JVM FQN since the
      // package-clause emission lands user code directly under its
      // declared `package:`).
      scalaFacade   = Map(
        "org.example.ui.Card"         -> "org.example.ui.Card",
        "org.example.ui.Button"       -> "org.example.ui.Button",
        "org.example.ui.Button.apply" -> "org.example.ui.Button.apply",
        "org.example.ui.render"       -> "org.example.ui.render"
      ),
      apiDescriptorV3 = Some(canonicalApiDescriptorV3)
    )

  private def sampleIr: NormalizedModule =
    val heading = Heading(level = 1, text = "Demo", span = None)
    val section = Section(
      heading     = heading,
      content     = List(
        Content.Prose("This is a demo module."),
        Content.CodeBlock(source = "val x = 1", body = Nil)
      ),
      subsections = Nil
    )
    NormalizedModule(
      manifest = Some(Manifest(
        name         = Some("demo"),
        version      = Some("0.1.0"),
        description  = Some("demo module"),
        dependencies = Map.empty,
        exports      = Nil,
        targets      = Nil,
        routes       = Nil,
        pkg          = Some(List("org", "example"))
      )),
      sections = List(section)
    )

  private def sampleJvm: ModuleJvmArtifact =
    ModuleJvmArtifact(
      magic        = ArtifactVersion.magic,
      abiVersion   = ArtifactVersion.current,
      moduleId     = "org/example/ui.ssc",
      pkg          = List("org", "example", "ui"),
      moduleName   = Some("ui-kit"),
      sourceHash   = "feedface" * 8,
      scalaSource  = "package org.example.ui\n\nobject Card { def render: String = \"ok\" }\n",
      imports      = List("std.io.println", "std.string.concat"),
      classBundle  = Some("UEsDBBQACAAIAA==.fakebase64=="),
      capabilities = List("effects", "serve")
    )

  private def sampleJs: ModuleJsArtifact =
    ModuleJsArtifact(
      magic        = ArtifactVersion.magic,
      abiVersion   = ArtifactVersion.current,
      moduleId     = "org/example/ui.ssc",
      pkg          = List("org", "example", "ui"),
      moduleName   = Some("ui-kit"),
      sourceHash   = "f00dbabe" * 8,
      jsSource     = "export function render() { return 'ok'; }\n",
      imports      = List("std.io.println"),
      capabilities = List("async", "core")
    )

  private def sampleJvmRuntime: ModuleJvmRuntimeArtifact =
    ModuleJvmRuntimeArtifact(
      magic        = ArtifactVersion.magic,
      abiVersion   = ArtifactVersion.current,
      capabilities = List("effects", "serve"),
      sourceHash   = "1234abcd" * 8,
      classBundle  = "UEsDBBQACAAIAA==.fakeruntimezip=="
    )

  private def sampleJsRuntime: ModuleJsRuntimeArtifact =
    ModuleJsRuntimeArtifact(
      magic        = ArtifactVersion.magic,
      abiVersion   = ArtifactVersion.current,
      capabilities = List("async", "core"),
      sourceHash   = "abcd1234" * 8,
      jsSource     = "(function(){ /* runtime preamble */ })();\n"
    )

  // ── Helper: read-then-write should equal the original JSON ────────────

  private def assertByteStableRoundtrip(name: String, json1: String, json2: String): Unit =
    assert(json1 == json2,
      s"$name: read+write should be byte-identical to the original.\n" +
      s"  first  length=${json1.length}\n" +
      s"  second length=${json2.length}\n" +
      s"  if this fails, the canonical serialisation drifted; document the\n" +
      s"  diff or fix the writer.")

  private def preV3Expected: ModuleInterface =
    ModuleInterface(
      magic = "SSCART",
      abiVersion = "2.0",
      pkg = List("legacy"),
      moduleName = Some("pre-v3"),
      moduleVersion = None,
      sourceHash = "1" * 64,
      exports = Nil
    )

  private def assertLegacyFields(actual: ModuleInterface, expected: ModuleInterface): Unit =
    assert(actual.magic == expected.magic)
    assert(actual.abiVersion == expected.abiVersion)
    assert(actual.pkg == expected.pkg)
    assert(actual.moduleName == expected.moduleName)
    assert(actual.moduleVersion == expected.moduleVersion)
    assert(actual.sourceHash == expected.sourceHash)
    assert(actual.exports == expected.exports)
    assert(actual.instances == expected.instances)
    assert(actual.capabilities == expected.capabilities)
    assert(actual.externDefs == expected.externDefs)
    assert(actual.dependencies == expected.dependencies)
    assert(actual.sectionHashes == expected.sectionHashes)
    assert(actual.sectionOwnHashes == expected.sectionOwnHashes)
    assert(actual.sectionInterfaceHashes == expected.sectionInterfaceHashes)
    assert(actual.scalaFacade == expected.scalaFacade)

  private def assertLegacyReaderFields(
      actual: LegacyModuleInterface,
      expected: ModuleInterface
  ): Unit =
    assert(actual.magic == expected.magic)
    assert(actual.abiVersion == expected.abiVersion)
    assert(actual.pkg == expected.pkg)
    assert(actual.moduleName == expected.moduleName)
    assert(actual.moduleVersion == expected.moduleVersion)
    assert(actual.sourceHash == expected.sourceHash)
    assert(actual.exports == expected.exports)
    assert(actual.instances == expected.instances)
    assert(actual.capabilities == expected.capabilities)
    assert(actual.externDefs == expected.externDefs)
    assert(actual.dependencies == expected.dependencies)
    assert(actual.sectionHashes == expected.sectionHashes)
    assert(actual.sectionOwnHashes == expected.sectionOwnHashes)
    assert(actual.sectionInterfaceHashes == expected.sectionInterfaceHashes)
    assert(actual.scalaFacade == expected.scalaFacade)

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
      .iterator.map(byte => f"${byte & 0xff}%02x").mkString

  // =====================================================================
  //  1.  Round-trip is byte-stable.
  // =====================================================================

  test(".scim byte-stable round-trip: write -> read -> write yields the same JSON"):
    val iface = sampleInterface
    val json1 = ArtifactIO.writeInterface(iface)
    val read  = ArtifactIO.readInterface(json1).toOption.get
    val json2 = ArtifactIO.writeInterface(read)
    assertByteStableRoundtrip(".scim", json1, json2)

  test(".scim literal pre-v3 JSON and MessagePack fixtures default apiDescriptorV3 to None"):
    val expected = preV3Expected
    val fromJson = ArtifactIO.readInterface(preV3JsonFixture)
      .fold(error => fail(error), identity)
    assertLegacyFields(fromJson, expected)
    assert(fromJson.apiDescriptorV3.isEmpty)

    val messagePack = Base64.getDecoder.decode(preV3MessagePackBase64)
    assert(sha256Hex(messagePack) ==
      "a9436cdc278bda4c4da919e55da89a8986d477645fe31d35d0c8f99389c40c32")
    val fromMessagePack = ArtifactIO.readInterface(messagePack)
      .fold(error => fail(error), identity)
    assertLegacyFields(fromMessagePack, expected)
    assert(fromMessagePack.apiDescriptorV3.isEmpty)

  test(".scim apiDescriptorV3 canonical text round-trips through JSON and MessagePack"):
    val expected = sampleInterface
    val json = ArtifactIO.writeInterface(expected)
    assert(json.contains("\"apiDescriptorV3\""))
    val fromJson = ArtifactIO.readInterface(json).fold(error => fail(error), identity)
    assertLegacyFields(fromJson, expected)
    assert(fromJson.apiDescriptorV3.contains(canonicalApiDescriptorV3))

    val messagePack = writeBinary(expected)
    val fromMessagePack = ArtifactIO.readInterface(messagePack).fold(error => fail(error), identity)
    assertLegacyFields(fromMessagePack, expected)
    assert(fromMessagePack.apiDescriptorV3.contains(canonicalApiDescriptorV3))

  test(".scim stripping apiDescriptorV3 restores None without changing legacy fields"):
    val json = ArtifactIO.writeInterface(sampleInterface)
    val stripped = stripFieldFromJson(json, "apiDescriptorV3")
    assert(stripped != json)
    val parsed = ArtifactIO.readInterface(stripped).fold(error => fail(error), identity)
    assertLegacyFields(parsed, sampleInterface)
    assert(parsed.apiDescriptorV3.isEmpty)

  test("pre-v3 reader DTO ignores apiDescriptorV3 in new JSON and MessagePack"):
    val json = ArtifactIO.writeInterface(sampleInterface)
    val legacyJson = read[LegacyModuleInterface](json)
    assertLegacyReaderFields(legacyJson, sampleInterface)

    val messagePack = writeBinary(sampleInterface)
    val legacyMessagePack = readBinary[LegacyModuleInterface](messagePack)
    assertLegacyReaderFields(legacyMessagePack, sampleInterface)

  test(".scir byte-stable round-trip: write -> read -> write yields the same JSON"):
    val nm   = sampleIr
    val pkg  = List("org", "example")
    val name = Some("demo")
    val hash = "cafef00d" * 8
    val json1 = ArtifactIO.writeIr(nm, pkg, name, hash)
    val (nm2, pkg2, name2, hash2) = ArtifactIO.readIr(json1).toOption.get
    val json2 = ArtifactIO.writeIr(nm2, pkg2, name2, hash2)
    assertByteStableRoundtrip(".scir", json1, json2)

  test(".scjvm byte-stable round-trip: write -> read -> write yields the same JSON"):
    val art   = sampleJvm
    val json1 = JvmArtifactIO.writeJvm(art)
    val read  = JvmArtifactIO.readJvm(json1).toOption.get
    val json2 = JvmArtifactIO.writeJvm(read)
    assertByteStableRoundtrip(".scjvm", json1, json2)

  test(".scjs byte-stable round-trip: write -> read -> write yields the same JSON"):
    val art   = sampleJs
    val json1 = JsArtifactIO.writeJs(art)
    val read  = JsArtifactIO.readJs(json1).toOption.get
    val json2 = JsArtifactIO.writeJs(read)
    assertByteStableRoundtrip(".scjs", json1, json2)

  test(".scjvm-runtime byte-stable round-trip: write -> read -> write yields the same JSON"):
    val art   = sampleJvmRuntime
    val json1 = JvmArtifactIO.writeRuntime(art)
    val read  = JvmArtifactIO.readRuntime(json1).toOption.get
    val json2 = JvmArtifactIO.writeRuntime(read)
    assertByteStableRoundtrip(".scjvm-runtime", json1, json2)

  test(".scjs-runtime byte-stable round-trip: write -> read -> write yields the same JSON"):
    val art   = sampleJsRuntime
    val json1 = JsArtifactIO.writeRuntime(art)
    val read  = JsArtifactIO.readRuntime(json1).toOption.get
    val json2 = JsArtifactIO.writeRuntime(read)
    assertByteStableRoundtrip(".scjs-runtime", json1, json2)

  // =====================================================================
  //  2.  Magic mismatch is rejected with a clear message.
  // =====================================================================

  /** Tamper the magic field in raw JSON.  `SSCARZ` is intentionally the
   *  same length as `SSCART` so the textual swap is byte-neutral. */
  private val tamperedMagic = "SSCARZ"

  private def tamperMagic(json: String): String =
    val before = s""""magic": "${ArtifactVersion.magic}""""
    val after  = s""""magic": "$tamperedMagic""""
    val out    = json.replace(before, after)
    assert(out != json, "tamper string must actually change the JSON")
    out

  test(".scim read rejects mismatched magic and mentions the magic field"):
    val json = ArtifactIO.writeInterface(sampleInterface)
    ArtifactIO.readInterface(tamperMagic(json)) match
      case Right(_)  => fail(".scim with bad magic should be rejected")
      case Left(err) =>
        assert(err.toLowerCase.contains("magic"), s"err must mention 'magic': $err")
        assert(err.contains(ArtifactVersion.magic), s"err must show expected magic: $err")

  test(".scir read rejects mismatched magic and mentions the magic field"):
    val json = ArtifactIO.writeIr(sampleIr, Nil, None, "0" * 64)
    ArtifactIO.readIr(tamperMagic(json)) match
      case Right(_)  => fail(".scir with bad magic should be rejected")
      case Left(err) => assert(err.toLowerCase.contains("magic"), s"err must mention 'magic': $err")

  test(".scjvm read rejects mismatched magic and mentions the magic field"):
    val json = JvmArtifactIO.writeJvm(sampleJvm)
    JvmArtifactIO.readJvm(tamperMagic(json)) match
      case Right(_)  => fail(".scjvm with bad magic should be rejected")
      case Left(err) => assert(err.toLowerCase.contains("magic"), s"err must mention 'magic': $err")

  test(".scjs read rejects mismatched magic and mentions the magic field"):
    val json = JsArtifactIO.writeJs(sampleJs)
    JsArtifactIO.readJs(tamperMagic(json)) match
      case Right(_)  => fail(".scjs with bad magic should be rejected")
      case Left(err) => assert(err.toLowerCase.contains("magic"), s"err must mention 'magic': $err")

  test(".scjvm-runtime read rejects mismatched magic"):
    val json = JvmArtifactIO.writeRuntime(sampleJvmRuntime)
    JvmArtifactIO.readRuntime(tamperMagic(json)) match
      case Right(_)  => fail(".scjvm-runtime with bad magic should be rejected")
      case Left(err) => assert(err.toLowerCase.contains("magic"), s"err must mention 'magic': $err")

  test(".scjs-runtime read rejects mismatched magic"):
    val json = JsArtifactIO.writeRuntime(sampleJsRuntime)
    JsArtifactIO.readRuntime(tamperMagic(json)) match
      case Right(_)  => fail(".scjs-runtime with bad magic should be rejected")
      case Left(err) => assert(err.toLowerCase.contains("magic"), s"err must mention 'magic': $err")

  // =====================================================================
  //  3.  ABI version bump (`3.0`) is rejected.
  // =====================================================================

  private def bumpAbi(json: String, to: String = "3.0"): String =
    val before = s""""abiVersion": "${ArtifactVersion.current}""""
    val after  = s""""abiVersion": "$to""""
    val out    = json.replace(before, after)
    assert(out != json, "version bump must change the JSON")
    out

  test(".scim read rejects ABI bump to 3.0 with a version-mentioning error"):
    val json = ArtifactIO.writeInterface(sampleInterface)
    ArtifactIO.readInterface(bumpAbi(json)) match
      case Right(_)  => fail(".scim with bumped ABI should be rejected")
      case Left(err) =>
        val low = err.toLowerCase
        assert(low.contains("abi") || low.contains("version"),
          s"err must mention ABI/version: $err")
        assert(err.contains(ArtifactVersion.current),
          s"err must show expected version: $err")
        assert(err.contains("3.0"), s"err must show offending version: $err")

  test(".scir read rejects ABI bump"):
    val json = ArtifactIO.writeIr(sampleIr, Nil, None, "0" * 64)
    ArtifactIO.readIr(bumpAbi(json)) match
      case Right(_)  => fail()
      case Left(err) =>
        val low = err.toLowerCase
        assert(low.contains("abi") || low.contains("version"), err)

  test(".scjvm read rejects ABI bump"):
    val json = JvmArtifactIO.writeJvm(sampleJvm)
    JvmArtifactIO.readJvm(bumpAbi(json)) match
      case Right(_)  => fail()
      case Left(err) => assert(err.toLowerCase.contains("abi") || err.toLowerCase.contains("version"), err)

  test(".scjs read rejects ABI bump"):
    val json = JsArtifactIO.writeJs(sampleJs)
    JsArtifactIO.readJs(bumpAbi(json)) match
      case Right(_)  => fail()
      case Left(err) => assert(err.toLowerCase.contains("abi") || err.toLowerCase.contains("version"), err)

  test(".scjvm-runtime read rejects ABI bump"):
    val json = JvmArtifactIO.writeRuntime(sampleJvmRuntime)
    JvmArtifactIO.readRuntime(bumpAbi(json)) match
      case Right(_)  => fail()
      case Left(err) => assert(err.toLowerCase.contains("abi") || err.toLowerCase.contains("version"), err)

  test(".scjs-runtime read rejects ABI bump"):
    val json = JsArtifactIO.writeRuntime(sampleJsRuntime)
    JsArtifactIO.readRuntime(bumpAbi(json)) match
      case Right(_)  => fail()
      case Left(err) => assert(err.toLowerCase.contains("abi") || err.toLowerCase.contains("version"), err)

  // =====================================================================
  //  4.  ABI minor bump is also rejected (any mismatch is breaking).
  //
  //  Per `ArtifactIO.checkEnvelope`, the version check is `!=` (exact
  //  equality), not a major-only check.  This pins the current behaviour:
  //  any version drift — including a minor bump — is fatal at read time.
  //  Document this so the deprecation policy can't slip.
  // =====================================================================

  test(".scim read rejects minor-only ABI bump (2.1) — current policy is exact match"):
    val json = ArtifactIO.writeInterface(sampleInterface)
    ArtifactIO.readInterface(bumpAbi(json, to = "2.1")) match
      case Right(_) =>
        fail("Current policy: any ABI mismatch (even minor) is rejected. " +
             "If you intentionally relaxed this to major-match, update " +
             "docs/v2.0-artifact-format.md and this test.")
      case Left(err) => assert(err.toLowerCase.contains("abi") || err.toLowerCase.contains("version"), err)

  // =====================================================================
  //  5.  Missing optional field falls back to its schema default.
  //
  //  The `nested` field on `ExportedSymbol` was added in v2.0 Stage 5.6.
  //  Older `.scim` artifacts written before it existed must still parse
  //  with `nested = Nil` — this is the backward-compat property the
  //  schema relies on for any future additive change.
  // =====================================================================

  test(".scim with stripped `nested` field on every ExportedSymbol still parses (default = Nil)"):
    val iface = sampleInterface
    val json  = ArtifactIO.writeInterface(iface)
    // Strip every `"nested": [ ... ]` block by hand.  upickle's pretty-
    // printer always emits the field on every ExportedSymbol because
    // upickle 4.x writes defaults too — so the regex has a fixed shape.
    val withoutNested = stripFieldFromJson(json, "nested")
    assert(withoutNested != json, "stripping nested must change the JSON")
    ArtifactIO.readInterface(withoutNested) match
      case Right(parsed) =>
        parsed.exports.foreach(s => assert(s.nested == Nil,
          s"stripped `nested` should default to Nil, got ${s.nested}"))
        parsed.externDefs.foreach(s => assert(s.nested == Nil))
      case Left(err) => fail(s".scim should tolerate stripped `nested`: $err")

  test(".scjvm with stripped `classBundle` field still parses (default = None)"):
    val art  = sampleJvm.copy(classBundle = Some("xyz"))
    val json = JvmArtifactIO.writeJvm(art)
    val without = stripFieldFromJson(json, "classBundle")
    assert(without != json)
    JvmArtifactIO.readJvm(without) match
      case Right(parsed) => assert(parsed.classBundle == None,
        s"stripped classBundle should default to None, got ${parsed.classBundle}")
      case Left(err) => fail(s".scjvm should tolerate stripped classBundle: $err")

  test(".scjvm with stripped `capabilities` field still parses (default = Nil)"):
    val art  = sampleJvm
    val json = JvmArtifactIO.writeJvm(art)
    val without = stripFieldFromJson(json, "capabilities")
    assert(without != json)
    JvmArtifactIO.readJvm(without) match
      case Right(parsed) => assert(parsed.capabilities == Nil,
        s"stripped capabilities should default to Nil, got ${parsed.capabilities}")
      case Left(err) => fail(s".scjvm should tolerate stripped capabilities: $err")

  test(".scjs with stripped `capabilities` field still parses (default = Nil)"):
    val art  = sampleJs
    val json = JsArtifactIO.writeJs(art)
    val without = stripFieldFromJson(json, "capabilities")
    assert(without != json)
    JsArtifactIO.readJs(without) match
      case Right(parsed) => assert(parsed.capabilities == Nil)
      case Left(err) => fail(s".scjs should tolerate stripped capabilities: $err")

  test(".scim with stripped optional default-bearing fields still parses"):
    val iface = sampleInterface
    val json  = ArtifactIO.writeInterface(iface)
    // `instances`, `capabilities`, `externDefs`, `dependencies` all have defaults.
    val stripped =
      stripFieldFromJson(
        stripFieldFromJson(
          stripFieldFromJson(
            stripFieldFromJson(json, "instances"),
            "capabilities"),
          "externDefs"),
        "dependencies")
    ArtifactIO.readInterface(stripped) match
      case Right(parsed) =>
        assert(parsed.instances    == Nil)
        assert(parsed.capabilities == Nil)
        assert(parsed.externDefs   == Nil)
        assert(parsed.dependencies == Map.empty)
      case Left(err) => fail(s".scim should tolerate stripped optional fields: $err")

  // =====================================================================
  //  6.  Missing required envelope field is rejected.
  //
  //  `magic` is required on every artifact.  upickle's derivation makes
  //  it mandatory because the case-class field has no default.
  // =====================================================================

  test(".scim read rejects a payload with no `magic` field"):
    val json = ArtifactIO.writeInterface(sampleInterface)
    val without = stripFieldFromJson(json, "magic")
    assert(without != json)
    ArtifactIO.readInterface(without) match
      case Right(_)  => fail(".scim with no magic field should be rejected")
      case Left(err) => assert(err.toLowerCase.contains("magic") ||
                               err.toLowerCase.contains("missing") ||
                               err.toLowerCase.contains("failed to parse"),
        s"err: $err")

  test(".scir read rejects a payload with no `magic` field"):
    val json = ArtifactIO.writeIr(sampleIr, Nil, None, "0" * 64)
    val without = stripFieldFromJson(json, "magic")
    assert(without != json)
    ArtifactIO.readIr(without) match
      case Right(_)  => fail()
      case Left(err) => assert(err.nonEmpty, s"err: $err")

  test(".scjvm read rejects a payload with no `magic` field"):
    val json = JvmArtifactIO.writeJvm(sampleJvm)
    val without = stripFieldFromJson(json, "magic")
    JvmArtifactIO.readJvm(without) match
      case Right(_)  => fail()
      case Left(err) => assert(err.nonEmpty)

  test(".scjs read rejects a payload with no `magic` field"):
    val json = JsArtifactIO.writeJs(sampleJs)
    val without = stripFieldFromJson(json, "magic")
    JsArtifactIO.readJs(without) match
      case Right(_)  => fail()
      case Left(err) => assert(err.nonEmpty)

  // =====================================================================
  //  7.  Unknown extra envelope field is silently ignored.
  //
  //  This is the forward-compat property: a future compiler can introduce
  //  a new envelope field without bumping the ABI version, provided
  //  current consumers can safely treat it as absent.
  // =====================================================================

  /** Insert an extra JSON field directly after the opening `{` of the
   *  outermost object.  Pretty-printed by upickle so the first `{` is
   *  the envelope root. */
  private def injectExtraField(json: String, name: String, value: String): String =
    val idx = json.indexOf('{')
    assert(idx >= 0, "JSON must contain an object")
    json.patch(idx + 1, s"\n  \"$name\": $value,", 0)

  test(".scim read tolerates unknown extra envelope fields (forward-compat)"):
    val json = ArtifactIO.writeInterface(sampleInterface)
    val withExtra = injectExtraField(json, "extraFutureField", "\"unused\"")
    ArtifactIO.readInterface(withExtra) match
      case Right(parsed) => assert(parsed == sampleInterface)
      case Left(err)     => fail(s"unknown fields should be ignored: $err")

  test(".scir read tolerates unknown extra envelope fields"):
    val json = ArtifactIO.writeIr(sampleIr, Nil, None, "0" * 64)
    val withExtra = injectExtraField(json, "extraFutureField", "42")
    ArtifactIO.readIr(withExtra) match
      case Right(_)  => succeed
      case Left(err) => fail(s"unknown fields should be ignored: $err")

  test(".scjvm read tolerates unknown extra envelope fields"):
    val json = JvmArtifactIO.writeJvm(sampleJvm)
    val withExtra = injectExtraField(json, "extraFutureField", "true")
    JvmArtifactIO.readJvm(withExtra) match
      case Right(_)  => succeed
      case Left(err) => fail(s"unknown fields should be ignored: $err")

  test(".scjs read tolerates unknown extra envelope fields"):
    val json = JsArtifactIO.writeJs(sampleJs)
    val withExtra = injectExtraField(json, "extraFutureField", "null")
    JsArtifactIO.readJs(withExtra) match
      case Right(_)  => succeed
      case Left(err) => fail(s"unknown fields should be ignored: $err")

  test(".scjvm-runtime read tolerates unknown extra envelope fields"):
    val json = JvmArtifactIO.writeRuntime(sampleJvmRuntime)
    val withExtra = injectExtraField(json, "extraFutureField", "\"x\"")
    JvmArtifactIO.readRuntime(withExtra) match
      case Right(_)  => succeed
      case Left(err) => fail(s"unknown fields should be ignored: $err")

  test(".scjs-runtime read tolerates unknown extra envelope fields"):
    val json = JsArtifactIO.writeRuntime(sampleJsRuntime)
    val withExtra = injectExtraField(json, "extraFutureField", "[]")
    JsArtifactIO.readRuntime(withExtra) match
      case Right(_)  => succeed
      case Left(err) => fail(s"unknown fields should be ignored: $err")

  // =====================================================================
  //  8.  Programmatic enumeration of every optional field per format.
  //
  //  A schema test: for each (format, optional-field) pair, strip the
  //  field from a freshly-written canonical sample and assert read
  //  succeeds with the documented default.  This catches the case where
  //  a future refactor accidentally removes an `= Nil` / `= None`
  //  default and turns an additive change into a breaking change.
  // =====================================================================

  /** Map of (format -> list of fields whose absence on read MUST yield a
   *  successful parse with the schema's documented default).
   *
   *  Forensic finding: only fields declared with an explicit `= None` /
   *  `= Nil` / `= Map.empty` default in `Ir.scala` count as "optional"
   *  in the wire-format sense.  Fields typed `Option[T]` WITHOUT a
   *  default (e.g. `ModuleInterface.moduleName`) require the key to be
   *  present in the JSON (as `null` for `None`) — upickle's derived
   *  `ReadWriter` only treats a missing key as the default when a
   *  Scala-level default exists.  This is intentional but easy to
   *  miss; callers wanting tolerant reads must add `= None` to the
   *  case-class field.
   *
   *  Keep in lockstep with `ir/.../Ir.scala` and
   *  `docs/v2.0-artifact-format.md` § "Optional fields". */
  private val optionalFields: Map[String, List[String]] = Map(
    ".scim"          -> List(
      "instances",
      "capabilities",
      "externDefs",
      "dependencies",
      "apiDescriptorV3"
    ),
    ".scir"          -> Nil,
    ".scjvm"         -> List("classBundle", "capabilities"),
    ".scjs"          -> List("capabilities"),
    ".scjvm-runtime" -> Nil,
    ".scjs-runtime"  -> Nil
  )

  /** Fields whose Scala declaration is `Option[T]` WITHOUT a default —
   *  required at the JSON-key level (must be present, possibly `null`).
   *  These are documented for transparency: a future schema change that
   *  adds `= None` would correctly make them tolerant. */
  private val requiredOptionTypedFields: Map[String, List[String]] = Map(
    ".scim"  -> List("moduleName", "moduleVersion"),
    ".scir"  -> List("moduleName"),
    ".scjvm" -> List("moduleName"),
    ".scjs"  -> List("moduleName")
  )

  private def writeCanonical(format: String): String = format match
    case ".scim"          => ArtifactIO.writeInterface(sampleInterface)
    case ".scir"          => ArtifactIO.writeIr(sampleIr, Nil, Some("demo"), "0" * 64)
    case ".scjvm"         => JvmArtifactIO.writeJvm(sampleJvm)
    case ".scjs"          => JsArtifactIO.writeJs(sampleJs)
    case ".scjvm-runtime" => JvmArtifactIO.writeRuntime(sampleJvmRuntime)
    case ".scjs-runtime"  => JsArtifactIO.writeRuntime(sampleJsRuntime)
    case other            => sys.error(s"unknown format: $other")

  private def readResult(format: String, json: String): Either[String, Any] = format match
    case ".scim"          => ArtifactIO.readInterface(json)
    case ".scir"          => ArtifactIO.readIr(json)
    case ".scjvm"         => JvmArtifactIO.readJvm(json)
    case ".scjs"          => JsArtifactIO.readJs(json)
    case ".scjvm-runtime" => JvmArtifactIO.readRuntime(json)
    case ".scjs-runtime"  => JsArtifactIO.readRuntime(json)
    case other            => sys.error(s"unknown format: $other")

  for (format, fields) <- optionalFields; field <- fields do
    test(s"$format: stripping optional field `$field` still parses (schema default applies)"):
      val json = writeCanonical(format)
      val stripped = stripFieldFromJson(json, field)
      assert(stripped != json, s"$format/$field: stripping must alter JSON")
      readResult(format, stripped) match
        case Right(_)  => succeed
        case Left(err) => fail(s"$format/$field: stripped optional must still parse: $err")

  /** Documents current behaviour: `Option[T]` fields without a Scala
   *  default in the case class definition are NOT tolerant — the key
   *  must be present in the JSON.  If a future schema change adds an
   *  `= None` default, the corresponding entry should move to
   *  `optionalFields`. */
  for (format, fields) <- requiredOptionTypedFields; field <- fields do
    test(s"$format: Option-typed field `$field` (no scala default) " +
         s"is required at the JSON level — stripping breaks parsing"):
      val json = writeCanonical(format)
      val stripped = stripFieldFromJson(json, field)
      assert(stripped != json, s"$format/$field: stripping must alter JSON")
      readResult(format, stripped) match
        case Right(_) =>
          fail(s"$format/$field: stripping should fail unless schema added `= None`. " +
               s"If you intentionally relaxed this, move `$field` from " +
               s"`requiredOptionTypedFields` to `optionalFields` and update the docs.")
        case Left(_) => succeed

    test(s"$format: Option-typed field `$field` accepts JSON `null` (None)"):
      // Rewrite the value to `null` and confirm the read succeeds.
      val canonical = writeCanonical(format)
      val nulled = canonical.replaceFirst(
        s""""$field":\\s*"[^"]*"""",
        s""""$field": null"""
      )
      assert(nulled != canonical, s"$format/$field: null-rewrite must alter JSON")
      readResult(format, nulled) match
        case Right(_)  => succeed
        case Left(err) => fail(s"$format/$field: null should parse as None: $err")

  // =====================================================================
  //  9.  `sourceHash` is preserved verbatim (not normalised or hashed
  //      again on read).  Future tooling that hand-rewrites a .scim
  //      relies on this.
  // =====================================================================

  test(".scim sourceHash is preserved exactly through round-trip"):
    val sentinel = "abc123" + ("0" * 58)  // 64 chars, non-hex-like prefix
    val iface  = sampleInterface.copy(sourceHash = sentinel)
    val json   = ArtifactIO.writeInterface(iface)
    val parsed = ArtifactIO.readInterface(json).toOption.get
    assert(parsed.sourceHash == sentinel)

  test(".scir sourceHash is preserved exactly through round-trip"):
    val sentinel = "deadbeef" * 8
    val json = ArtifactIO.writeIr(sampleIr, Nil, None, sentinel)
    val (_, _, _, hash) = ArtifactIO.readIr(json).toOption.get
    assert(hash == sentinel)

  test(".scjvm sourceHash is preserved exactly through round-trip"):
    val sentinel = "feedface" * 8
    val art   = sampleJvm.copy(sourceHash = sentinel)
    val json  = JvmArtifactIO.writeJvm(art)
    val read  = JvmArtifactIO.readJvm(json).toOption.get
    assert(read.sourceHash == sentinel)

  test(".scjs sourceHash is preserved exactly through round-trip"):
    val sentinel = "1234abcd" * 8
    val art   = sampleJs.copy(sourceHash = sentinel)
    val json  = JsArtifactIO.writeJs(art)
    val read  = JsArtifactIO.readJs(json).toOption.get
    assert(read.sourceHash == sentinel)

  test(".scjvm-runtime sourceHash is preserved exactly through round-trip"):
    val sentinel = "abcd1234" * 8
    val art   = sampleJvmRuntime.copy(sourceHash = sentinel)
    val json  = JvmArtifactIO.writeRuntime(art)
    val read  = JvmArtifactIO.readRuntime(json).toOption.get
    assert(read.sourceHash == sentinel)

  test(".scjs-runtime sourceHash is preserved exactly through round-trip"):
    val sentinel = "deadc0de" * 8
    val art   = sampleJsRuntime.copy(sourceHash = sentinel)
    val json  = JsArtifactIO.writeRuntime(art)
    val read  = JsArtifactIO.readRuntime(json).toOption.get
    assert(read.sourceHash == sentinel)

  // =====================================================================
  //  10.  Cross-format envelope ambiguity.
  //
  //  All seven artifact types share the same `SSCART` magic + `2.0`
  //  version — the envelope alone does NOT disambiguate them.  The
  //  consumer is REQUIRED to choose its reader based on the file
  //  extension; reading a `.scjvm` payload with `ArtifactIO.readInterface`
  //  fails because the payload schema differs.  These tests document
  //  that behaviour.
  // =====================================================================

  test("envelope magic + version are shared across all formats (no extra disambiguator)"):
    val scimJson         = ArtifactIO.writeInterface(sampleInterface)
    val scirJson         = ArtifactIO.writeIr(sampleIr, Nil, None, "0" * 64)
    val scjvmJson        = JvmArtifactIO.writeJvm(sampleJvm)
    val scjsJson         = JsArtifactIO.writeJs(sampleJs)
    val scjvmRuntimeJson = JvmArtifactIO.writeRuntime(sampleJvmRuntime)
    val scjsRuntimeJson  = JsArtifactIO.writeRuntime(sampleJsRuntime)
    val all = List(scimJson, scirJson, scjvmJson, scjsJson, scjvmRuntimeJson, scjsRuntimeJson)
    all.foreach { j =>
      assert(j.contains(s""""magic": "${ArtifactVersion.magic}""""),
        s"every artifact must carry the shared magic: $j")
      assert(j.contains(s""""abiVersion": "${ArtifactVersion.current}""""),
        s"every artifact must carry the shared ABI version")
    }

  test("cross-format read: .scjvm payload fed to readInterface fails (schema mismatch)"):
    val scjvmJson = JvmArtifactIO.writeJvm(sampleJvm)
    // Envelope passes magic + version, but payload-specific fields differ.
    ArtifactIO.readInterface(scjvmJson) match
      case Right(_)  => fail(".scjvm should not parse as .scim — consumer must check extension")
      case Left(err) => assert(err.nonEmpty)

  test("cross-format read: .scim payload fed to readJvm fails (schema mismatch)"):
    val scimJson = ArtifactIO.writeInterface(sampleInterface)
    JvmArtifactIO.readJvm(scimJson) match
      case Right(_)  => fail(".scim should not parse as .scjvm")
      case Left(err) => assert(err.nonEmpty)

  test("cross-format read: .scjs-runtime payload fed to readJs fails"):
    val rtJson = JsArtifactIO.writeRuntime(sampleJsRuntime)
    JsArtifactIO.readJs(rtJson) match
      case Right(_)  => fail(".scjs-runtime should not parse as .scjs")
      case Left(err) => assert(err.nonEmpty)

  test("ArtifactVersion constants are the documented v2.0 values"):
    assert(ArtifactVersion.magic   == "SSCART", "magic must remain SSCART through v2.x")
    assert(ArtifactVersion.current == "2.0",    "ABI version must remain 2.0 through v2.x")

  // ── Helpers ───────────────────────────────────────────────────────────

  /** Strip the JSON field `name` (and its value, however deep) from
   *  every nested object in `json`.  Works on the pretty-printed
   *  output upickle produces with `indent = 2`.
   *
   *  Implementation: scan for `"name":`, locate the value (skipping
   *  matched braces / brackets / strings), then drop the entire
   *  key–value pair plus the surrounding comma + whitespace so the
   *  resulting JSON is still well-formed. */
  private def stripFieldFromJson(json: String, name: String): String =
    val key = "\"" + name + "\""
    // Use java.lang.StringBuilder for its `append(CharSequence, start, end)`
    // overload — Scala's mutable.StringBuilder lacks the same shape and may
    // append the tuple as a string instead.
    val sb  = new java.lang.StringBuilder(json.length)
    var i   = 0
    while i < json.length do
      // Look for the key at the current position
      val keyStart = json.indexOf(key, i)
      if keyStart < 0 then
        sb.append(json, i, json.length)
        i = json.length
      else
        // Confirm this `key` is actually a JSON key — preceding non-whitespace
        // char (skipping whitespace) must be `{` or `,`.
        var p = keyStart - 1
        while p >= i && json(p).isWhitespace do p -= 1
        val precCh = if p >= 0 then json(p) else ' '
        if precCh != '{' && precCh != ',' then
          // Not a key (e.g. embedded in a string value).  Copy through.
          sb.append(json, i, keyStart + key.length)
          i = keyStart + key.length
        else
          // Find `:` after key.
          var c = keyStart + key.length
          while c < json.length && json(c) != ':' do c += 1
          c += 1
          // Skip whitespace after `:`.
          while c < json.length && json(c).isWhitespace do c += 1
          // Scan value: track JSON nesting.
          var depthBrace  = 0
          var depthBracket = 0
          var inString = false
          var escaped  = false
          var done     = false
          while !done && c < json.length do
            val ch = json(c)
            if inString then
              if escaped then escaped = false
              else if ch == '\\' then escaped = true
              else if ch == '"' then inString = false
              c += 1
            else
              ch match
                case '"' => inString = true; c += 1
                case '{' => depthBrace += 1;   c += 1
                case '}' =>
                  if depthBrace == 0 then done = true
                  else { depthBrace -= 1; c += 1 }
                case '[' => depthBracket += 1; c += 1
                case ']' =>
                  if depthBracket == 0 then done = true
                  else { depthBracket -= 1; c += 1 }
                case ',' if depthBrace == 0 && depthBracket == 0 =>
                  done = true
                case _ => c += 1
          val valEnd = c
          // Now drop the range [p+1 .. valEnd) — i.e., remove leading comma
          // before the key as well, or if the key is the first field, drop
          // the trailing comma after the value.  Strategy:
          //   - If precCh is ',' (this was not the first field), copy up to
          //     and including precCh's position - 1, then resume from valEnd
          //     (skipping past trailing whitespace + optional ',').
          //   - If precCh is '{' (this is the first field), copy up to and
          //     including precCh, then skip the key/value/comma after value.
          if precCh == ',' then
            // Output everything before the comma (so the previous field's
            // trailing whitespace is kept), then jump to valEnd.
            sb.append(json, i, p) // up to (not including) comma
            // Skip trailing whitespace after value.  If next non-ws is `,`
            // it's an inner separator we already removed; if it's `}` /
            // `]` we just resume.
            var k = valEnd
            while k < json.length && json(k).isWhitespace do k += 1
            i = valEnd
            // No further adjustment — the preceding comma is dropped which
            // correctly closes the previous field.
          else
            // precCh == '{': key is the FIRST field.
            sb.append(json, i, keyStart) // up to start of "<key>"
            // valEnd is positioned either at `,` or at `}`.
            var k = valEnd
            // If valEnd is `,`, skip it and following whitespace so the next
            // field becomes the new first field.
            if k < json.length && json(k) == ',' then
              k += 1
              while k < json.length && json(k).isWhitespace do k += 1
            i = k
    sb.toString

  test("[meta] stripFieldFromJson removes a leaf field and leaves JSON parseable"):
    val src = """{
  "a": 1,
  "b": "hello",
  "c": [1, 2, 3]
}"""
    val out = stripFieldFromJson(src, "b")
    assert(!out.contains("\"b\""), s"`b` should be removed: $out")
    // Should still be parseable as JSON.
    ujson.read(out)

  test("[meta] stripFieldFromJson removes a nested array-of-objects field"):
    val src = """{
  "items": [
    { "k": 1, "v": "x" },
    { "k": 2, "v": "y" }
  ]
}"""
    val out = stripFieldFromJson(src, "v")
    assert(!out.contains("\"v\""), s"`v` should be removed everywhere: $out")
    ujson.read(out)

  // =====================================================================
  //  10. scalaFacade — Scala interop Tier 1 (docs/scala-interop.md).
  //
  //  The field is additive, optional, default Map.empty.  These four
  //  tests pin the ABI contract for v0.x of the interop:
  //   - byte-stable round-trip with non-empty entries
  //   - legacy .scim without the field is absent-tolerant (default empty)
  //   - keys are preserved case-sensitively (Scala FQNs are case-sensitive)
  //   - empty-map default serialises canonically (no field skipping drift)
  // =====================================================================

  test(".scim scalaFacade round-trips byte-stably with non-empty entries"):
    val iface = sampleInterface
    assert(iface.scalaFacade.nonEmpty, "sample must include facade entries")
    val json1 = ArtifactIO.writeInterface(iface)
    val read  = ArtifactIO.readInterface(json1).toOption.get
    assert(read.scalaFacade == iface.scalaFacade,
      s"facade round-trip mismatch:\n  before=${iface.scalaFacade}\n  after =${read.scalaFacade}")
    val json2 = ArtifactIO.writeInterface(read)
    assertByteStableRoundtrip(".scim+facade", json1, json2)

  test(".scim with stripped scalaFacade field still parses (default Map.empty)"):
    val iface = sampleInterface
    val json  = ArtifactIO.writeInterface(iface)
    val without = stripFieldFromJson(json, "scalaFacade")
    assert(without != json, "stripping should change the JSON")
    ArtifactIO.readInterface(without) match
      case Right(parsed) =>
        assert(parsed.scalaFacade == Map.empty,
          s".scim without scalaFacade should default to Map.empty, got: ${parsed.scalaFacade}")
      case Left(err) => fail(s".scim should tolerate stripped scalaFacade: $err")

  test(".scim scalaFacade keys preserve case sensitivity through round-trip"):
    // Tier 5 — facade is identity; both sides carry the same case-distinct
    // FQN.  Round-trip must preserve key-side case-distinctness regardless
    // of the values' specific shape (the ABI guarantee is byte-stability,
    // not a particular value convention).
    val iface = sampleInterface.copy(
      scalaFacade = Map(
        "org.acme.MyClass"   -> "org.acme.MyClass",
        "org.acme.myclass"   -> "org.acme.myclass",  // distinct lowercase
        "ORG.ACME.MyClass"   -> "ORG.ACME.MyClass"   // distinct uppercase
      )
    )
    val json = ArtifactIO.writeInterface(iface)
    val read = ArtifactIO.readInterface(json).toOption.get
    assert(read.scalaFacade.keySet ==
      Set("org.acme.MyClass", "org.acme.myclass", "ORG.ACME.MyClass"),
      s"case-distinct keys must round-trip distinctly, got: ${read.scalaFacade.keySet}")
    assert(read.scalaFacade("org.acme.MyClass") == "org.acme.MyClass")
    assert(read.scalaFacade("org.acme.myclass") == "org.acme.myclass")
    assert(read.scalaFacade("ORG.ACME.MyClass") == "ORG.ACME.MyClass")

  test(".scim with empty scalaFacade round-trips byte-stably (canonical)"):
    // upickle's `derives ReadWriter` omits a field when its value equals the
    // schema default — so an empty `scalaFacade: Map.empty` is dropped from
    // the JSON.  That is fine and even desirable: legacy readers, the
    // absent-field path, and the empty-map path all converge on the same
    // canonical form.  Pin the byte-stability so the writer can't quietly
    // drift to a non-canonical encoding.
    val iface = sampleInterface.copy(scalaFacade = Map.empty)
    val json1 = ArtifactIO.writeInterface(iface)
    val read  = ArtifactIO.readInterface(json1).toOption.get
    assert(read.scalaFacade.isEmpty, "empty round-trips as empty")
    val json2 = ArtifactIO.writeInterface(read)
    assertByteStableRoundtrip(".scim+empty-facade", json1, json2)

end ArtifactAbiCompatibilityTest
