package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import scalascript.interpreter.{Interpreter, Value}
import scalascript.ir.{NormalizedModule, QualifiedName}
import scalascript.parser.Parser

class NativeContextStateBagTest extends AnyFunSuite:

  test("NativeContext shared feature state persists across native calls"):
    val result = evalWithPlugin(
      """
        featureSet("demo.count", "1")
        val before = featureGet("demo.count")
        featureUpdate("demo.count", "2")
        val after = featureGet("demo.count")
        val removed = featureRemove("demo.count")
        val missing = featureGet("demo.count")
        List(before, after, removed, missing)
      """
    )

    assert(result == Value.ListV(List(
      Value.StringV("1"),
      Value.StringV("2"),
      Value.StringV("2"),
      Value.StringV("<missing>")
    )))

  test("NativeContext feature-local state scopes and restores httpClient base URL"):
    val result = evalWithPlugin(
      """
        val before = httpBaseFeature()
        val inside = httpClient("http://example.test") {
          httpBaseFeature()
        }
        val after = httpBaseFeature()
        List(before, inside, after)
      """
    )

    assert(result == Value.ListV(List(
      Value.StringV("<missing>"),
      Value.StringV("http://example.test"),
      Value.StringV("<missing>")
    )))

  private def evalWithPlugin(code: String): Value =
    val interp = Interpreter(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
    interp.installPlugins(List(StateBagTestPlugin))
    interp.run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    interp.lastResult

object StateBagTestPlugin extends Backend:
  def id: String = "state-bag-test"
  def displayName: String = "State Bag Test"
  def spiVersion: String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs = Set.empty,
    options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )
  def acceptedSources: Set[String] = Set.empty
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("test plugin only")))
  def intrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("featureSet") -> NativeImpl((ctx, args) =>
      args match
        case List(key: String, value: String) => ctx.featureSet(key, value); Value.UnitV
        case _ => throw RuntimeException("featureSet(key, value)")
    ),
    QualifiedName("featureGet") -> NativeImpl((ctx, args) =>
      args match
        case List(key: String) => Value.StringV(ctx.featureGet(key).map(String.valueOf).getOrElse("<missing>"))
        case _ => throw RuntimeException("featureGet(key)")
    ),
    QualifiedName("featureUpdate") -> NativeImpl((ctx, args) =>
      args match
        case List(key: String, value: String) =>
          Value.StringV(String.valueOf(ctx.featureUpdate(key)(_.map(_ => value).getOrElse(value))))
        case _ => throw RuntimeException("featureUpdate(key, value)")
    ),
    QualifiedName("featureRemove") -> NativeImpl((ctx, args) =>
      args match
        case List(key: String) => Value.StringV(ctx.featureRemove(key).map(String.valueOf).getOrElse("<missing>"))
        case _ => throw RuntimeException("featureRemove(key)")
    ),
    QualifiedName("httpBaseFeature") -> NativeImpl((ctx, args) =>
      args match
        case Nil => Value.StringV(ctx.featureLocalGet(NativeContextFeatureKeys.HttpBaseUrl).map(String.valueOf).getOrElse("<missing>"))
        case _ => throw RuntimeException("httpBaseFeature()")
    ),
  )
