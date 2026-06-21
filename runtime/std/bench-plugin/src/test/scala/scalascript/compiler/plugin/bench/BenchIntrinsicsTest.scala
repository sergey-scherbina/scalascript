package scalascript.compiler.plugin.bench

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.{NativeContext, NativeImpl}
import scalascript.ir.QualifiedName

import java.io.{OutputStream, PrintStream}
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

class BenchIntrinsicsTest extends AnyFunSuite:

  test("Bench.opaque returns its argument unchanged through PluginNative"):
    val impl = BenchIntrinsics.table(QualifiedName("Bench.opaque")).asInstanceOf[NativeImpl]

    assert(impl.eval(NoopContext, List(42L)) == 42L)
    assert(impl.eval(NoopContext, List("x")) == "x")
    assert(impl.eval(NoopContext, Nil) == ())

  test("bench plugin main sources do not import interpreter internals"):
    val root = locateRepoRoot(Paths.get(System.getProperty("user.dir")).toAbsolutePath)
    val srcDir = root.resolve("runtime/std/bench-plugin/src/main")
    val offenders =
      Using.resource(Files.walk(srcDir)) { stream =>
        stream.iterator.asScala
          .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
          .filter(p => Files.readString(p).contains("scalascript.interpreter"))
          .map(root.relativize(_).toString)
          .toList
      }

    assert(offenders.isEmpty, s"bench plugin leaked interpreter imports: ${offenders.mkString(", ")}")

  private def locateRepoRoot(start: Path): Path =
    Iterator.iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")) && Files.exists(p.resolve("runtime/std/bench-plugin")))
      .getOrElse(fail(s"could not locate repo root from $start"))

  private object NoopContext extends NativeContext:
    private val nullOut = new PrintStream(OutputStream.nullOutputStream())
    def out: PrintStream = nullOut
    def err: PrintStream = nullOut
