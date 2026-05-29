package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfter
import scalascript.backend.spi.{Feature, InterpolatorImpl, Capabilities, SpiVersionRange, SpiVersion}
import scalascript.typer.{SType, Typer, TypedDef, TypedSection, DefSummary}
import scalascript.parser.Parser

/** arch-dsl-hooks-p1 — InterpolatorRegistry + Typer integration tests.
 *
 *  The JvmGen / JsGen delegation tests live in FfiDslHooksTest in
 *  backendInterpreter (that module has access to both codegen modules).
 */
class InterpolatorRegistryTest extends AnyFunSuite with Matchers with BeforeAndAfter:

  object MockGqlInterpolator extends InterpolatorImpl:
    override val name           = "gql"
    override val returnTypeName = "GqlQuery"
    override val requiredFeatures: Set[Feature] = Set(Feature.ModuleImports)
    override def jvmEmit(parts: List[String], args: List[String]): String =
      s"""_GqlQuery("${parts.mkString("$?")}", List(${args.mkString(", ")}))"""
    override def jsEmit(parts: List[String], args: List[String]): String =
      s"""_gql("${parts.mkString("$?")}", [${args.mkString(", ")}])"""

  before:
    InterpolatorRegistry.register(MockGqlInterpolator)

  private def summaries(code: String): List[DefSummary] =
    val m  = Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")
    val tm = Typer.typeCheck(m)
    val buf = scala.collection.mutable.ListBuffer.empty[DefSummary]
    def walk(s: TypedSection): Unit =
      s.definitions.foreach {
        case TypedDef.CodeBlock(_, _, defs) => buf ++= defs
        case _ => ()
      }
      s.subsections.foreach(walk)
    tm.sections.foreach(walk)
    buf.toList

  // ── Register / Lookup ────────────────────────────────────────────────

  test("InterpolatorRegistry: lookup returns registered impl by name") {
    InterpolatorRegistry.lookup("gql").isDefined should be(true)
    InterpolatorRegistry.lookup("gql").get.name should be("gql")
  }

  test("InterpolatorRegistry: lookup returns None for unknown name") {
    InterpolatorRegistry.lookup("nonexistent_xyz_abc") should be(None)
  }

  test("InterpolatorRegistry: built-in html is pre-registered") {
    InterpolatorRegistry.lookup("html").isDefined should be(true)
  }

  test("InterpolatorRegistry: built-in css is pre-registered") {
    InterpolatorRegistry.lookup("css").isDefined should be(true)
  }

  // ── Typer — return type ──────────────────────────────────────────────

  test("Typer: registered interpolator gets registered returnType") {
    val defs = summaries(
      """val id = 1
        |val result = gql"SELECT ${id} FROM users"
        |""".stripMargin
    )
    val resultDef = defs.find(_.name == "result")
    assert(resultDef.isDefined, "result val should be typed")
    assert(resultDef.get.tpe == SType.Named("GqlQuery", Nil),
      s"expected GqlQuery, got ${resultDef.get.tpe}")
  }

  test("Typer: unregistered interpolator returns SType.String") {
    val defs = summaries("""val x = unknown42"hello ${"world"}"""")
    val xDef = defs.find(_.name == "x")
    assert(xDef.isDefined, "x val should be typed")
    assert(xDef.get.tpe == SType.String, s"expected String, got ${xDef.get.tpe}")
  }

  test("Typer: built-in s/f/md still return SType.String") {
    val defs = summaries(
      """val a = s"hello"
        |val b = f"${42}%d"
        |val c = md"# heading"
        |""".stripMargin
    )
    assert(defs.find(_.name == "a").map(_.tpe).contains(SType.String), "s-interp should be String")
    assert(defs.find(_.name == "b").map(_.tpe).contains(SType.String), "f-interp should be String")
    assert(defs.find(_.name == "c").map(_.tpe).contains(SType.String), "md-interp should be String")
  }

  // ── Backend.interpolators ────────────────────────────────────────────

  test("Backend.interpolators defaults to empty list") {
    val spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
    val backend = new scalascript.backend.spi.Backend:
      override val id          = "test"
      override val displayName = "Test"
      override val spiVersion  = "0.1.0"
      override val capabilities = Capabilities(
        features = Set.empty, outputs = Set.empty, options = Set.empty,
        spiRange = spiRange, blockLanguages = Set.empty
      )
      override val intrinsics    = Map.empty
      override val acceptedSources = Set.empty
      override def compile(ir: scalascript.ir.NormalizedModule, opts: scalascript.backend.spi.BackendOptions) =
        scalascript.backend.spi.CompileResult.Failed(Nil)
    assert(backend.interpolators.isEmpty)
  }

  test("InterpolatorRegistry.registerFrom registers backend interpolators") {
    val customImpl = new InterpolatorImpl:
      override val name           = "test_dslhooks_xyz"
      override val returnTypeName = "CustomType"
      override def jvmEmit(p: List[String], a: List[String]) = "CustomType()"
      override def jsEmit(p: List[String], a: List[String])  = "_customType()"
    val spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
    val backend = new scalascript.backend.spi.Backend:
      override val id          = "test2"
      override val displayName = "Test2"
      override val spiVersion  = "0.1.0"
      override val capabilities = Capabilities(
        features = Set.empty, outputs = Set.empty, options = Set.empty,
        spiRange = spiRange, blockLanguages = Set.empty
      )
      override val intrinsics    = Map.empty
      override val acceptedSources = Set.empty
      override def interpolators   = List(customImpl)
      override def compile(ir: scalascript.ir.NormalizedModule, opts: scalascript.backend.spi.BackendOptions) =
        scalascript.backend.spi.CompileResult.Failed(Nil)
    InterpolatorRegistry.registerFrom(backend)
    assert(InterpolatorRegistry.lookup("test_dslhooks_xyz").contains(customImpl))
  }

  // ── requiredFeatures ────────────────────────────────────────────────

  test("InterpolatorRegistry: impl requiredFeatures are accessible") {
    val features = InterpolatorRegistry.lookup("gql").map(_.requiredFeatures)
    assert(features.contains(Set(Feature.ModuleImports)))
  }

  test("InterpolatorRegistry: built-in html has no requiredFeatures") {
    val features = InterpolatorRegistry.lookup("html").map(_.requiredFeatures)
    assert(features.contains(Set.empty))
  }
