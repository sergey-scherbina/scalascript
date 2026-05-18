package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.parser.Parser
import scala.jdk.CollectionConverters.*

/** Tests for v0.9 `ssc preview` front-matter parsing:
 *  - `variants:` entries survive the YAML → Manifest.raw round-trip
 *  - Each variant has the expected name and args structure
 */
class SscPreviewVariantsTest extends AnyFunSuite with Matchers:

  private def parseVariants(yaml: String): List[(String, Map[String, String])] =
    val src = s"---\n$yaml\n---\n\n# C\n"
    val module = Parser.parse(src)
    val rawFM  = module.manifest.map(_.raw).getOrElse(Map.empty)
    rawFM.get("variants").collect {
      case xs: java.util.List[?] =>
        xs.asScala.toList.flatMap {
          case m: java.util.Map[?, ?] =>
            val mm    = m.asScala.toMap.map((k, v) => k.toString -> v)
            val label = mm.get("name").map(_.toString).getOrElse("default")
            val args  = mm.get("args").collect {
              case am: java.util.Map[?, ?] =>
                am.asScala.toMap.map((k, v) => k.toString -> v.toString)
            }.getOrElse(Map.empty)
            Some(label -> args)
          case _ => None
        }
    }.getOrElse(Nil)

  test("variants: absent → empty list") {
    parseVariants("name: spinner") shouldBe Nil
  }

  test("variants: single entry with args") {
    val yaml =
      """|name: spinner
         |variants:
         |  - name: "small"
         |    args: {size: "sm"}
         |""".stripMargin
    val variants = parseVariants(yaml)
    variants.size shouldBe 1
    variants.head._1 shouldBe "small"
    variants.head._2 shouldBe Map("size" -> "sm")
  }

  test("variants: multiple entries with distinct args") {
    val yaml =
      """|name: badge
         |variants:
         |  - name: "neutral"
         |    args: {label: "Neutral", tone: "neutral"}
         |  - name: "info"
         |    args: {label: "Info", tone: "info"}
         |  - name: "danger"
         |    args: {label: "Danger", tone: "danger"}
         |""".stripMargin
    val variants = parseVariants(yaml)
    variants.size shouldBe 3
    variants.map(_._1) shouldBe List("neutral", "info", "danger")
    variants.head._2("tone") shouldBe "neutral"
    variants(1)._2("tone")   shouldBe "info"
    variants(2)._2("tone")   shouldBe "danger"
  }

  test("variants: entry without args yields empty map") {
    val yaml =
      """|name: spinner
         |variants:
         |  - name: "default"
         |""".stripMargin
    val variants = parseVariants(yaml)
    variants.size shouldBe 1
    variants.head._1 shouldBe "default"
    variants.head._2 shouldBe Map.empty
  }

  test("spinner.ssc front-matter round-trip preserves three variants") {
    // Resolve spinner.ssc relative to the project root regardless of cwd.
    val root   = os.pwd / os.up  // backend-interpreter -> project root
    val spinner = root / "examples" / "std-ui" / "spinner.ssc"
    assume(os.exists(spinner), s"spinner.ssc not found at $spinner")
    val src    = os.read(spinner)
    val module = Parser.parse(src)
    val rawFM  = module.manifest.map(_.raw).getOrElse(Map.empty)
    val variantNames = rawFM.get("variants").collect {
      case xs: java.util.List[?] =>
        xs.asScala.toList.flatMap {
          case m: java.util.Map[?, ?] =>
            m.asScala.toMap.get("name").map(_.toString)
          case _ => None
        }
    }.getOrElse(Nil)
    variantNames should have size 3
    variantNames should contain ("small (sm)")
    variantNames should contain ("medium (md)")
    variantNames should contain ("large (lg)")
  }
