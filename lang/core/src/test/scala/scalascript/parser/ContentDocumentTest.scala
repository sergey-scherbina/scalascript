package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast.*
import scalascript.transform.{Denormalize, Normalize}

class ContentDocumentTest extends AnyFunSuite:

  private val source =
    """---
      |name: pricing-page
      |description: Markdown rendered as frontend data
      |audience: builders
      |---
      |
      |Lead before headings with ${visitorName}.
      |
      |# Pricing {#pricing route=/pricing layout=marketing}
      |
      |Intro with [docs](/docs "Docs").
      |
      |![Hero image](/hero.png "Hero")
      |
      |<!-- @meta component=PlanList source=plans -->
      |## Plans
      |
      |- Starter
      |- Pro
      |
      |```yaml @id=plans-data
      |plans:
      |  - id: starter
      |    price: 19
      |  - id: pro
      |    price: 49
      |```
      |""".stripMargin

  test("parser builds Markdown-hosted document content snapshot"):
    val module = Parser.parse(source)
    val doc = module.document.getOrElse(fail("expected document content snapshot"))

    assert(doc.title.contains("Pricing"))
    assert(doc.description.contains("Markdown rendered as frontend data"))
    assert(doc.manifest == ContentValue.MapV(Map(
      "name" -> ContentValue.Str("pricing-page"),
      "description" -> ContentValue.Str("Markdown rendered as frontend data"),
      "audience" -> ContentValue.Str("builders")
    )))

    val lead = doc.blocks.collectFirst { case p: ContentBlock.Paragraph => p }
      .getOrElse(fail("expected top-level lead paragraph"))
    assert(lead.inlines == List(
      ContentInline.Text("Lead before headings with "),
      ContentInline.Expr("visitorName"),
      ContentInline.Text(".")
    ))

    val pricing = doc.sections.head
    assert(pricing.id == "pricing")
    assert(pricing.title == "Pricing")
    assert(pricing.attrs == Map(
      "route" -> ContentValue.Str("/pricing"),
      "layout" -> ContentValue.Str("marketing")
    ))

    val intro = pricing.blocks.collectFirst { case p: ContentBlock.Paragraph => p }
      .getOrElse(fail("expected intro paragraph"))
    assert(intro.inlines.exists {
      case ContentInline.Link(label, "/docs", Some("Docs")) =>
        label == List(ContentInline.Text("docs"))
      case _ => false
    })

    val image = pricing.blocks.collectFirst { case i: ContentBlock.Image => i }
      .getOrElse(fail("expected paragraph image block"))
    assert(image.src == "/hero.png")
    assert(image.alt == "Hero image")
    assert(image.title.contains("Hero"))

    val plans = pricing.children.head
    assert(plans.id == "plans")
    assert(plans.attrs == Map(
      "component" -> ContentValue.Str("PlanList"),
      "source" -> ContentValue.Str("plans")
    ))

    val list = plans.blocks.collectFirst { case l: ContentBlock.BulletList => l }
      .getOrElse(fail("expected bullet list"))
    assert(list.items.length == 2)

    val embedded = plans.blocks.collectFirst { case e: ContentBlock.Embedded => e }
      .getOrElse(fail("expected YAML embedded block"))
    assert(embedded.lang == "yaml")
    assert(embedded.kind == EmbeddedKind.StructuredData)
    assert(embedded.attrs == Map("id" -> ContentValue.Str("plans-data")))
    assert(embedded.data.contains(ContentValue.MapV(Map(
      "plans" -> ContentValue.ListV(List(
        ContentValue.MapV(Map(
          "id" -> ContentValue.Str("starter"),
          "price" -> ContentValue.Num(19.0)
        )),
        ContentValue.MapV(Map(
          "id" -> ContentValue.Str("pro"),
          "price" -> ContentValue.Num(49.0)
        ))
      ))
    ))))

  test("content snapshot survives Normalize and Denormalize"):
    val module = Parser.parse(source)
    val normalized = Normalize(module)
    val roundTrip = Denormalize(normalized)

    assert(normalized.document.nonEmpty)
    assert(roundTrip.document == module.document)
