package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.ir
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.1.3a — Cargo.toml emit. */
class RustGenCargoTomlTest extends AnyFunSuite:

  private val emptyOpts = BackendOptions(
    baseDir           = None,
    outputDir         = None,
    optimizationLevel = 0,
    emitSourceMaps    = false,
    emitAssertions    = false,
    target            = None,
    extra             = Map.empty
  )

  private def compile(module: ir.NormalizedModule): List[Segment] =
    new RustBackend().compile(module, emptyOpts) match
      case CompileResult.Segmented(segs) => segs
      case other => fail(s"expected Segmented, got ${other.getClass.getSimpleName}")

  private def cargoToml(segs: List[Segment]): String =
    segs.collectFirst {
      case Segment.Asset("Cargo.toml", bytes, _) => new String(bytes, "UTF-8")
    }.getOrElse(fail("Cargo.toml segment missing"))

  private def moduleWith(
      name:        Option[String] = None,
      version:     Option[String] = None,
      description: Option[String] = None,
      sources:     List[String]   = Nil
  ): ir.NormalizedModule =
    val manifest =
      if name.isEmpty && version.isEmpty && description.isEmpty then None
      else Some(ir.Manifest(
        name = name, version = version, description = description,
        dependencies = Map.empty, exports = Nil, targets = Nil,
        routes = Nil, pkg = None
      ))
    val section = ir.Section(
      heading     = ir.Heading(level = 1, text = "test"),
      content     = sources.map(src => ir.Content.CodeBlock(source = src)),
      subsections = Nil
    )
    ir.NormalizedModule(manifest = manifest, sections = List(section))

  test("emits Cargo.toml with defaults for an empty module"):
    val toml = cargoToml(compile(moduleWith()))
    assert(toml.contains("""name = "ssc_program""""))
    assert(toml.contains("""version = "0.1.0""""))
    assert(toml.contains("""edition = "2021""""))
    assert(toml.contains("[dependencies]"))
    // No @main → emit a [lib] target.
    assert(toml.contains("[lib]"))
    assert(!toml.contains("[[bin]]"))

  test("emits [[bin]] when an @main is detected in any code block"):
    val toml = cargoToml(compile(moduleWith(
      sources = List("@main def run(): Unit = println(\"Hello from Rust\")")
    )))
    assert(toml.contains("[[bin]]"))
    assert(!toml.contains("[lib]"))
    assert(toml.contains("""path = "src/main.rs""""))

  test("uses manifest name/version/description when present"):
    val toml = cargoToml(compile(moduleWith(
      name = Some("hello"), version = Some("0.2.5"),
      description = Some("Greeting program")
    )))
    assert(toml.contains("""name = "hello""""))
    assert(toml.contains("""version = "0.2.5""""))
    assert(toml.contains("""description = "Greeting program""""))

  test("sanitizes manifest name to [a-z0-9_] (Rust module-name alphabet)"):
    // Hyphens collapse to `_` because the same name doubles as a Rust
    // module name (`pub mod <name>;` rejects hyphens).
    val toml = cargoToml(compile(moduleWith(name = Some("My.App Name"))))
    assert(toml.contains("""name = "my_app_name""""))

  test("prefixes a leading digit so the crate name is a valid Rust identifier"):
    val toml = cargoToml(compile(moduleWith(name = Some("42-game"))))
    assert(toml.contains("""name = "_42_game""""))

  test("escapes TOML basic-string metacharacters in the description"):
    val toml = cargoToml(compile(moduleWith(description = Some("with \"quotes\" and \\ slash"))))
    assert(toml.contains("""description = "with \"quotes\" and \\ slash""""))

  test("golden — Cargo.toml for the hello-world fixture"):
    val src = "@main def run(): Unit = println(\"Hello from Rust\")\n"
    val toml = cargoToml(compile(moduleWith(
      name = Some("hello"), version = Some("0.1.0"),
      description = None, sources = List(src)
    )))
    val expected =
      """[package]
        |name = "hello"
        |version = "0.1.0"
        |edition = "2021"
        |
        |[dependencies]
        |
        |[[bin]]
        |name = "hello"
        |path = "src/main.rs"
        |""".stripMargin
    assert(toml == expected, s"actual:\n$toml")
