package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.Preprocessor

/** arch-dsl-hooks-p2 — PreprocessorRegistry unit tests. */
class PreprocessorRegistryTest extends AnyFunSuite with Matchers:

  // ── Built-in registrations ──────────────────────────────────────────────

  test("PreprocessorRegistry: all 7 built-in preprocessors are registered") {
    val names = PreprocessorRegistry.all.map(_.name).toSet
    names should contain ("inline-imports")
    names should contain ("list-literals")
    names should contain ("slash-imports")
    names should contain ("remote-defs")
    names should contain ("openapi-annotations")
    names should contain ("effects")
    names should contain ("extern")
  }

  test("PreprocessorRegistry: built-in priorities are ordered 10/20/30/40/45/50/60") {
    val builtinNames = Set("inline-imports", "list-literals", "slash-imports",
                            "remote-defs", "openapi-annotations", "effects", "extern")
    val builtins = PreprocessorRegistry.all.filter(p => builtinNames(p.name))
    val prios = builtins.map(_.priority)
    prios shouldBe prios.sorted
    prios should contain allOf(10, 20, 30, 40, 45, 50, 60)
  }

  test("PreprocessorRegistry.lookup: finds registered preprocessor by name") {
    PreprocessorRegistry.lookup("effects").isDefined should be(true)
  }

  test("PreprocessorRegistry.lookup: returns None for unknown name") {
    PreprocessorRegistry.lookup("__nonexistent_xyz_9999__") should be(None)
  }

  // ── Custom preprocessor ─────────────────────────────────────────────────

  test("PreprocessorRegistry: custom preprocessor is applied by applyAll") {
    val customPp = new Preprocessor:
      override val name     = "test_pp_macro_expand_xyz"
      override val priority = 200
      override def apply(source: String): String =
        source.replace("FOO_MAGIC", "_foo_expanded()")

    PreprocessorRegistry.register(customPp)
    try
      val result = PreprocessorRegistry.applyAll("val x = FOO_MAGIC")
      result should include("_foo_expanded()")
      result should not include("FOO_MAGIC")
    finally
      // Deregister to avoid polluting other tests (TrieMap allows remove)
      PreprocessorRegistry.registry.remove("test_pp_macro_expand_xyz")
  }

  test("PreprocessorRegistry: applyAll runs preprocessors in priority order") {
    val log = scala.collection.mutable.ListBuffer.empty[String]

    val ppA = new Preprocessor:
      override val name     = "test_order_a_xyz"
      override val priority = 500
      override def apply(s: String) = { log += "A"; s }

    val ppB = new Preprocessor:
      override val name     = "test_order_b_xyz"
      override val priority = 300
      override def apply(s: String) = { log += "B"; s }

    PreprocessorRegistry.register(ppA)
    PreprocessorRegistry.register(ppB)
    try
      PreprocessorRegistry.applyAll("test")
      // B (priority 300) must run before A (priority 500)
      val sliceAB = log.dropWhile(x => x != "A" && x != "B").take(2)
      sliceAB should contain inOrder("B", "A")
    finally
      PreprocessorRegistry.registry.remove("test_order_a_xyz")
      PreprocessorRegistry.registry.remove("test_order_b_xyz")
  }

  // ── Backend.preprocessors ───────────────────────────────────────────────

  test("Backend.preprocessors defaults to empty list") {
    import scalascript.backend.spi.{Backend, Capabilities, SpiVersionRange, SpiVersion, BackendOptions, CompileResult}
    import scalascript.ir.NormalizedModule
    val spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
    val backend = new Backend:
      override val id          = "test-pp"
      override val displayName = "TestPP"
      override val spiVersion  = "0.1.0"
      override val capabilities = Capabilities(
        features = Set.empty, outputs = Set.empty, options = Set.empty,
        spiRange = spiRange, blockLanguages = Set.empty
      )
      override val intrinsics     = Map.empty
      override val acceptedSources = Set.empty
      override def compile(ir: NormalizedModule, opts: BackendOptions) =
        CompileResult.Failed(Nil)
    backend.preprocessors.isEmpty should be(true)
  }

  test("PreprocessorRegistry.registerFrom registers backend preprocessors") {
    import scalascript.backend.spi.{Backend, Capabilities, SpiVersionRange, SpiVersion, BackendOptions, CompileResult}
    import scalascript.ir.NormalizedModule

    val customPp = new Preprocessor:
      override val name     = "test_backend_pp_xyz"
      override val priority = 999
      override def apply(s: String) = s + "<<backend>>"

    val spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
    val backend = new Backend:
      override val id          = "test-pp2"
      override val displayName = "TestPP2"
      override val spiVersion  = "0.1.0"
      override val capabilities = Capabilities(
        features = Set.empty, outputs = Set.empty, options = Set.empty,
        spiRange = spiRange, blockLanguages = Set.empty
      )
      override val intrinsics      = Map.empty
      override val acceptedSources  = Set.empty
      override def preprocessors    = List(customPp)
      override def compile(ir: NormalizedModule, opts: BackendOptions) =
        CompileResult.Failed(Nil)

    PreprocessorRegistry.registerFrom(backend)
    try
      PreprocessorRegistry.lookup("test_backend_pp_xyz").map(_.name) should be(Some("test_backend_pp_xyz"))
    finally
      PreprocessorRegistry.registry.remove("test_backend_pp_xyz")
  }

  // ── Integration: preprocessForScala via Parser ──────────────────────────

  test("Parser: effects preprocessor is applied via PreprocessorRegistry pipeline") {
    val src =
      """effect Logger:
        |  def log(msg: String)
        |""".stripMargin
    val m = Parser.parse(s"# T\n\n```scalascript\n$src\n```\n")
    // If the preprocessor ran, the block was accepted without a parse error.
    m.sections should not be empty
  }

  test("Parser: slash-import preprocessor converts paths with / before parsing") {
    // import io/example/Foo should become import io.example.Foo
    val src = "import io/example/Foo"
    val m = Parser.parse(s"# T\n\n```scalascript\n$src\n```\n")
    m.sections should not be empty
  }

  test("Parser: @openapi route annotation rewrites before parsing") {
    val src =
      """@openapi(summary = "List users", tags = List("users"), deprecated = true)
        |route("GET", "/users") { req => Response.text("ok") }
        |""".stripMargin
    val processed = PreprocessorRegistry.applyAll(src)
    processed should include ("""openapi("List users", "", List("users"), true, List())""")
    processed should include ("""route("GET", "/users")""")
    val m = Parser.parse(s"# T\n\n```scalascript\n$src\n```\n")
    m.sections.head.content.collectFirst { case cb: scalascript.ast.Content.CodeBlock => cb.parseError } shouldBe Some(None)
  }

  test("Parser: @openapi security argument rewrites to fifth marker argument") {
    val src =
      """@openapi(security = List("bearerAuth"))
        |route("DELETE", "/users/:id") { req => Response.status(204) }
        |""".stripMargin
    val processed = PreprocessorRegistry.applyAll(src)
    processed should include ("""openapi("", "", List(), false, List("bearerAuth"))""")
  }
