package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, InterpretError}
import scalascript.parser.Parser

/** Conformance tests for v1.18 `package:` keyword.
 *
 *  Covers:
 *   - package: declaration wraps module symbols under nested objects
 *   - import of a packaged module by short name (bare binding)
 *   - import of a packaged module with `as` alias
 *   - collision-free: two modules export the same short name, resolved by pkg
 *   - single-segment package
 *   - multi-segment package (e.g. org.example.ui)
 */
class PackageKeywordTest extends AnyFunSuite with Matchers:

  private def captured(src: String, baseDir: Option[os.Path] = None): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps, baseDir).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def withDir[A](f: os.Path => A): A =
    val dir = os.temp.dir(deleteOnExit = true)
    try f(dir) finally os.remove.all(dir)

  // ─── Parsing / wrapping ───────────────────────────────────────────

  test("package: wraps module symbols under nested objects"):
    withDir { dir =>
      val lib = """|---
                   |package: org.example
                   |---
                   |
                   |# Math
                   |
                   |```scala
                   |object Math:
                   |  def add(a: Int, b: Int): Int = a + b
                   |```
                   |""".stripMargin
      os.write(dir / "math.ssc", lib)
      val consumer = s"""|# Consumer
                         |
                         |[Math](math.ssc)
                         |
                         |```scala
                         |println(Math.add(1, 2))
                         |```
                         |""".stripMargin
      captured(consumer, Some(dir)) shouldBe "3"
    }

  test("import from single-segment packaged module"):
    withDir { dir =>
      val lib = """|---
                   |package: mylib
                   |---
                   |
                   |# Greeter
                   |
                   |```scala
                   |object Greeter:
                   |  def hello(name: String): String = "Hello, " + name
                   |```
                   |""".stripMargin
      os.write(dir / "greeter.ssc", lib)
      val consumer = s"""|# Consumer
                         |
                         |[Greeter](greeter.ssc)
                         |
                         |```scala
                         |println(Greeter.hello("World"))
                         |```
                         |""".stripMargin
      captured(consumer, Some(dir)) shouldBe "Hello, World"
    }

  test("import with `as` alias from packaged module"):
    withDir { dir =>
      val lib = """|---
                   |package: org.example.ui
                   |---
                   |
                   |# Card
                   |
                   |```scala
                   |object Card:
                   |  def render(t: String): String = "<div>" + t + "</div>"
                   |```
                   |""".stripMargin
      os.write(dir / "ui.ssc", lib)
      val consumer = s"""|# Consumer
                         |
                         |[Card as MyCard](ui.ssc)
                         |
                         |```scala
                         |println(MyCard.render("hi"))
                         |```
                         |""".stripMargin
      captured(consumer, Some(dir)) shouldBe "<div>hi</div>"
    }

  test("collision-free: two modules export the same short name"):
    withDir { dir =>
      val libA = """|---
                    |package: org.a
                    |---
                    |
                    |# Widget
                    |
                    |```scala
                    |object Widget:
                    |  val name: String = "A-Widget"
                    |```
                    |""".stripMargin
      val libB = """|---
                    |package: org.b
                    |---
                    |
                    |# Widget
                    |
                    |```scala
                    |object Widget:
                    |  val name: String = "B-Widget"
                    |```
                    |""".stripMargin
      os.write(dir / "a.ssc", libA)
      os.write(dir / "b.ssc", libB)
      val consumer = s"""|# Consumer
                         |
                         |[Widget as WidgetA](a.ssc)
                         |
                         |[Widget as WidgetB](b.ssc)
                         |
                         |```scala
                         |println(WidgetA.name)
                         |println(WidgetB.name)
                         |```
                         |""".stripMargin
      captured(consumer, Some(dir)) shouldBe "A-Widget\nB-Widget"
    }

  test("multi-segment package path (org.example.ui)"):
    withDir { dir =>
      val lib = """|---
                   |package: org.example.ui
                   |---
                   |
                   |# Button
                   |
                   |```scala
                   |object Button:
                   |  def label: String = "Click me"
                   |```
                   |""".stripMargin
      os.write(dir / "button.ssc", lib)
      val consumer = s"""|# Consumer
                         |
                         |[Button](button.ssc)
                         |
                         |```scala
                         |println(Button.label)
                         |```
                         |""".stripMargin
      captured(consumer, Some(dir)) shouldBe "Click me"
    }

  test("module without package: still imports by short name"):
    withDir { dir =>
      val lib = """|# Utils
                   |
                   |```scala
                   |object Utils:
                   |  def double(n: Int): Int = n * 2
                   |```
                   |""".stripMargin
      os.write(dir / "utils.ssc", lib)
      val consumer = s"""|# Consumer
                         |
                         |[Utils](utils.ssc)
                         |
                         |```scala
                         |println(Utils.double(5))
                         |```
                         |""".stripMargin
      captured(consumer, Some(dir)) shouldBe "10"
    }

  test("unknown name in packaged module throws InterpretError"):
    withDir { dir =>
      val lib = """|---
                   |package: org.example
                   |---
                   |
                   |# Foo
                   |
                   |```scala
                   |object Foo:
                   |  val x: Int = 1
                   |```
                   |""".stripMargin
      os.write(dir / "foo.ssc", lib)
      val consumer = s"""|# Consumer
                         |
                         |[Bar](foo.ssc)
                         |
                         |```scala
                         |println("never")
                         |```
                         |""".stripMargin
      an[InterpretError] should be thrownBy captured(consumer, Some(dir))
    }

