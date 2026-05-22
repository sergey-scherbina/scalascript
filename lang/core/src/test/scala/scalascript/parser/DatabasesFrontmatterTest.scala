package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast
import scalascript.ir
import scalascript.transform.{Normalize, Denormalize}

/** v1.26 Phase 5 — `databases:` front-matter map parses into typed
 *  `ast.DatabaseDecl` records, survives Normalize → ir.DatabaseDecl,
 *  and round-trips through Denormalize unchanged.
 *
 *  `${env:NAME}` substrings are preserved verbatim — env resolution is
 *  a runtime concern (Phase 6), not a parse-time one, so a module
 *  that references secret env vars still parses cleanly on a machine
 *  where the vars aren't set. */
class DatabasesFrontmatterTest extends AnyFunSuite:

  private def withFrontmatter(yaml: String): ast.Module =
    val src =
      s"""|---
          |$yaml
          |---
          |
          |# Test
          |""".stripMargin
    Parser.parse(src)

  test("databases: empty / absent → empty list") {
    val mod = withFrontmatter("name: x")
    assert(mod.manifest.exists(_.databases.isEmpty))
  }

  test("databases: single entry with just url") {
    val mod = withFrontmatter(
      """databases:
        |  default:
        |    url: jdbc:h2:mem:dev""".stripMargin)
    val dbs = mod.manifest.get.databases
    assert(dbs.size == 1)
    val d = dbs.head
    assert(d.name == "default")
    assert(d.url  == "jdbc:h2:mem:dev")
    assert(d.user.isEmpty && d.password.isEmpty && d.driver.isEmpty)
  }

  test("databases: full entry — url + user + password + driver") {
    val mod = withFrontmatter(
      """databases:
        |  reports:
        |    url: jdbc:postgresql://localhost:5432/warehouse
        |    user: rpt
        |    password: secret
        |    driver: org.postgresql.Driver""".stripMargin)
    val d = mod.manifest.get.databases.head
    assert(d.name     == "reports")
    assert(d.url      == "jdbc:postgresql://localhost:5432/warehouse")
    assert(d.user     == Some("rpt"))
    assert(d.password == Some("secret"))
    assert(d.driver   == Some("org.postgresql.Driver"))
  }

  test("databases: multiple entries, ${env:NAME} references preserved verbatim") {
    val mod = withFrontmatter(
      """databases:
        |  default:
        |    url: jdbc:h2:mem:dev
        |  reports:
        |    url: jdbc:postgresql://reports.internal:5432/warehouse
        |    user: ${env:RPT_USER}
        |    password: ${env:RPT_PASSWORD}""".stripMargin)
    val byName = mod.manifest.get.databases.map(d => d.name -> d).toMap
    assert(byName.keySet == Set("default", "reports"))
    assert(byName("reports").user     == Some("${env:RPT_USER}"))
    assert(byName("reports").password == Some("${env:RPT_PASSWORD}"))
  }

  test("databases: missing `url` skips the entry silently") {
    // The runtime surfaces a precise diagnostic when an sql block tries
    // to resolve a name that isn't in the registry; parse-time skipping
    // means a typo elsewhere doesn't block the rest of the document.
    val mod = withFrontmatter(
      """databases:
        |  good:
        |    url: jdbc:h2:mem:dev
        |  broken:
        |    user: oops_no_url""".stripMargin)
    assert(mod.manifest.get.databases.map(_.name) == List("good"))
  }

  test("databases: survives Normalize → ir.DatabaseDecl with all fields") {
    val mod = withFrontmatter(
      """databases:
        |  reports:
        |    url: jdbc:postgresql://r:5432/db
        |    user: u
        |    password: p
        |    driver: org.postgresql.Driver""".stripMargin)
    val ir1 = Normalize(mod)
    val d   = ir1.manifest.get.databases.head
    assert(d == ir.DatabaseDecl(
      name = "reports", url = "jdbc:postgresql://r:5432/db",
      user = Some("u"), password = Some("p"),
      driver = Some("org.postgresql.Driver")
    ))
  }

  test("databases: round-trips Normalize → Denormalize unchanged") {
    val mod = withFrontmatter(
      """databases:
        |  default:
        |    url: jdbc:h2:mem:dev
        |  with_secrets:
        |    url: jdbc:postgresql://x:5432/y
        |    user: ${env:U}
        |    password: ${env:P}""".stripMargin)
    val round = Denormalize(Normalize(mod))
    val byName = round.manifest.get.databases.map(d => d.name -> d).toMap
    assert(byName("default").url == "jdbc:h2:mem:dev")
    assert(byName("with_secrets").user     == Some("${env:U}"))
    assert(byName("with_secrets").password == Some("${env:P}"))
  }
