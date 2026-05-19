package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.*

/** v2.0 — round-trip and ABI-guard tests for `.scim` / `.scir` artifacts.
 *
 *  Covers `ArtifactIO.write*` / `read*` for both flavours and locks down the
 *  envelope validation (magic + version) that protects against silent
 *  miscompilation. */
class ArtifactIOTest extends AnyFunSuite:

  // ── Fixture helpers ────────────────────────────────────────────────────

  private def sampleInterface: ModuleInterface =
    ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = List("org", "example", "ui"),
      moduleName    = Some("ui-kit"),
      moduleVersion = Some("0.1.0"),
      sourceHash    = "deadbeef" * 8,                       // 64-char hex
      exports       = List(
        ExportedSymbol("Card",     "org_example_ui_Card",     "object"),
        ExportedSymbol("Button",   "org_example_ui_Button",   "object", "Any"),
        ExportedSymbol("render",   "org_example_ui_render",   "def",    "String => Unit")
      ),
      instances     = List(
        InstanceDecl("Eq",  "Int", "eqInt",  "org_example_ui_eqInt"),
        InstanceDecl("Ord", "Int", "ordInt", "org_example_ui_ordInt")
      ),
      capabilities  = List(CapabilityDecl("Http"), CapabilityDecl("WebSocket")),
      externDefs    = List(
        ExportedSymbol("serve",  "org_example_ui_serve",  "extern", "Int => Unit"),
        ExportedSymbol("nowMs",  "org_example_ui_nowMs",  "extern", "Long")
      ),
      dependencies  = Map("std" -> "1.0.0", "http" -> "0.3.2")
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

  private def withTempDir[A](body: os.Path => A): A =
    val d = os.temp.dir(prefix = "ssc-v2-test-")
    try body(d) finally os.remove.all(d)

  // ── .scim round-trip ───────────────────────────────────────────────────

  test(".scim round-trip via writeInterface / readInterface preserves structure"):
    val iface = sampleInterface
    val json  = ArtifactIO.writeInterface(iface)
    ArtifactIO.readInterface(json) match
      case Right(parsed) => assert(parsed == iface)
      case Left(err)     => fail(s"round-trip failed: $err")

  test(".scim round-trip via file write / file read preserves structure"):
    withTempDir { d =>
      val path  = d / "foo.scim"
      val iface = sampleInterface
      ArtifactIO.writeInterfaceFile(iface, path)
      assert(os.exists(path))
      ArtifactIO.readInterfaceFile(path) match
        case Right(parsed) => assert(parsed == iface)
        case Left(err)     => fail(s"file round-trip failed: $err")
    }

  test(".scim source hash is preserved across round-trip"):
    val iface  = sampleInterface
    val json   = ArtifactIO.writeInterface(iface)
    val parsed = ArtifactIO.readInterface(json).toOption.get
    assert(parsed.sourceHash == iface.sourceHash)
    assert(parsed.sourceHash.nonEmpty)

  // ── .scir round-trip ───────────────────────────────────────────────────

  test(".scir round-trip via writeIr / readIr preserves NormalizedModule"):
    val nm   = sampleIr
    val pkg  = List("org", "example")
    val name = Some("demo")
    val hash = "cafef00d" * 8
    val json = ArtifactIO.writeIr(nm, pkg, name, hash)
    ArtifactIO.readIr(json) match
      case Right((parsed, parsedPkg, parsedName, parsedHash)) =>
        assert(parsed     == nm)
        assert(parsedPkg  == pkg)
        assert(parsedName == name)
        assert(parsedHash == hash)
      case Left(err) => fail(s".scir round-trip failed: $err")

  test(".scir round-trip via file write / file read preserves NormalizedModule"):
    withTempDir { d =>
      val path = d / "demo.scir"
      val nm   = sampleIr
      val pkg  = List("org", "example")
      val name = Some("demo")
      val hash = "1234abcd" * 8
      ArtifactIO.writeIrFile(nm, pkg, name, hash, path)
      assert(os.exists(path))
      ArtifactIO.readIrFile(path) match
        case Right((parsed, parsedPkg, parsedName, parsedHash)) =>
          assert(parsed     == nm)
          assert(parsedPkg  == pkg)
          assert(parsedName == name)
          assert(parsedHash == hash)
        case Left(err) => fail(s".scir file round-trip failed: $err")
    }

  // ── ABI version guard — magic byte mismatch ────────────────────────────

  test(".scim read rejects tampered magic field"):
    val iface   = sampleInterface
    val json    = ArtifactIO.writeInterface(iface)
    val tampered = json.replace(s""""magic": "${ArtifactVersion.magic}"""",
                                """"magic": "BADART"""")
    assert(tampered != json, "tamper string must actually change the JSON")
    ArtifactIO.readInterface(tampered) match
      case Right(_) => fail("readInterface should have rejected the tampered magic")
      case Left(err) =>
        assert(err.toLowerCase.contains("magic"), s"error message should mention magic: $err")
        assert(err.contains(ArtifactVersion.magic), s"error should reference expected magic: $err")

  test(".scim read rejects mismatched ABI version"):
    val iface    = sampleInterface
    val json     = ArtifactIO.writeInterface(iface)
    val tampered = json.replace(s""""abiVersion": "${ArtifactVersion.current}"""",
                                """"abiVersion": "99.99"""")
    assert(tampered != json, "tamper string must actually change the JSON")
    ArtifactIO.readInterface(tampered) match
      case Right(_) => fail("readInterface should have rejected the mismatched ABI version")
      case Left(err) =>
        assert(err.toLowerCase.contains("abi") || err.toLowerCase.contains("version"),
          s"error message should mention ABI/version: $err")
        assert(err.contains(ArtifactVersion.current), s"error should reference expected version: $err")
        assert(err.contains("99.99"), s"error should reference the offending version: $err")

  test(".scir read rejects tampered magic field"):
    val nm       = sampleIr
    val json     = ArtifactIO.writeIr(nm, Nil, None, "0" * 64)
    val tampered = json.replace(s""""magic": "${ArtifactVersion.magic}"""",
                                """"magic": "BOGUS!"""")
    assert(tampered != json)
    ArtifactIO.readIr(tampered) match
      case Right(_) => fail("readIr should have rejected the tampered magic")
      case Left(err) =>
        assert(err.toLowerCase.contains("magic"), s"error message should mention magic: $err")

  test(".scir read rejects mismatched ABI version"):
    val nm       = sampleIr
    val json     = ArtifactIO.writeIr(nm, Nil, None, "0" * 64)
    val tampered = json.replace(s""""abiVersion": "${ArtifactVersion.current}"""",
                                """"abiVersion": "0.0"""")
    assert(tampered != json)
    ArtifactIO.readIr(tampered) match
      case Right(_) => fail("readIr should have rejected the mismatched ABI version")
      case Left(err) =>
        assert(err.toLowerCase.contains("abi") || err.toLowerCase.contains("version"),
          s"error message should mention ABI/version: $err")

  // ── Malformed input handling ───────────────────────────────────────────

  test(".scim read returns Left on malformed JSON"):
    ArtifactIO.readInterface("{ this is not json") match
      case Right(_) => fail("malformed JSON should not parse")
      case Left(err) => assert(err.nonEmpty)

  test(".scim file read returns Left when file is missing"):
    withTempDir { d =>
      ArtifactIO.readInterfaceFile(d / "does-not-exist.scim") match
        case Right(_) => fail("missing file should return Left")
        case Left(err) => assert(err.toLowerCase.contains("not found") || err.nonEmpty)
    }

  test(".scir file read returns Left when file is missing"):
    withTempDir { d =>
      ArtifactIO.readIrFile(d / "does-not-exist.scir") match
        case Right(_) => fail("missing file should return Left")
        case Left(err) => assert(err.toLowerCase.contains("not found") || err.nonEmpty)
    }

  // ── Envelope-construction invariants ───────────────────────────────────

  test("writeInterface refuses an interface with the wrong magic (BUG check)"):
    val bogus = sampleInterface.copy(magic = "WRONG")
    val ex = intercept[IllegalArgumentException](ArtifactIO.writeInterface(bogus))
    assert(ex.getMessage.contains("magic"))

  test("writeInterface refuses an interface with the wrong abiVersion (BUG check)"):
    val bogus = sampleInterface.copy(abiVersion = "0.0")
    val ex = intercept[IllegalArgumentException](ArtifactIO.writeInterface(bogus))
    assert(ex.getMessage.contains("abiVersion") || ex.getMessage.contains("ABI") ||
           ex.getMessage.contains("version"))
