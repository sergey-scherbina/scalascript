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

  test("emits [[bin]] for a zero-argument `def main` — the entry point every other lane uses"):
    // The backend recognised ONLY `@main`, so an ordinary program — `def main(): Unit = …`, which is
    // what `ssc run` calls and what the interpreter, jvm, js and native lanes all start from —
    // emitted a `[lib]` crate with no binary. `run-rust` then said `expected binary not found at
    // <temp path>`, naming the consequence and not the cause, and it was filed as "the rust lane
    // produces no binary for a hello-world". The lane was working; `@main def run()` ran fine.
    val toml = cargoToml(compile(moduleWith(
      sources = List("def main(): Unit = println(\"Hello from Rust\")")
    )))
    assert(toml.contains("[[bin]]"))
    assert(!toml.contains("[lib]"))
    assert(toml.contains("""path = "src/main.rs""""))

  test("bare top-level statements get a synthesized entry — every other lane runs them"):
    // This backend walks only top-level `def`s, so a program that is just statements produced an
    // EMPTY generated module and a `[lib]` crate with nothing to run. The statements now become the
    // body of a synthesized `def main(): Unit`, so entry detection, `[[bin]]`, `src/main.rs` and
    // top-val inlining all apply unchanged instead of the walker learning a second shape.
    val toml = cargoToml(compile(moduleWith(
      sources = List("val x = 41\nprintln(x + 1)")
    )))
    assert(toml.contains("[[bin]]"))
    assert(!toml.contains("[lib]"))

  test("a program with its own entry point does NOT get a second one"):
    // Synthesizing beside an existing `@main` would emit two candidates and pick by accident, so
    // the synthesis is conditional. Asserted in both spellings, since either could be the one that
    // regresses.
    Vector(
      "@main def run(): Unit = println(\"a\")\nprintln(\"top level too\")",
      "def main(): Unit = println(\"a\")\nprintln(\"top level too\")"
    ).foreach { src =>
      val toml = cargoToml(compile(moduleWith(sources = List(src))))
      assert(toml.contains("[[bin]]"), s"lost the binary target for:\n$src")
    }

  test("a `main` that TAKES ARGUMENTS is not an entry point"):
    // `renderMainRs` emits `fn main() { generated::<crate>::<entry>(); }`, so a `main` with
    // parameters would generate a call missing its arguments — a Rust compile error at the end of a
    // long build, which is worse than the `[lib]` it would replace. Zero-arity is checked, not
    // assumed.
    val toml = cargoToml(compile(moduleWith(
      sources = List("def main(argv: String): Unit = println(argv)")
    )))
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
        |
        |[profile.dev]
        |overflow-checks = false
        |
        |[profile.release]
        |overflow-checks = false
        |""".stripMargin
    assert(toml == expected, s"actual:\n$toml")
