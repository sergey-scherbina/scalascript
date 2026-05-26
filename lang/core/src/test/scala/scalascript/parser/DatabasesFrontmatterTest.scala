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

  test("schemas: parses typed storage metadata") {
    val mod = withFrontmatter(
      """schemas:
        |  Person:
        |    rejectUnknown: true
        |    fields:
        |      id:
        |        key: true
        |      displayName:
        |        name: display_name
        |        aliases: [name]
        |      active:
        |        default: true
        |      legacyName: legacy_name""".stripMargin)

    val schema = mod.manifest.get.schemas.head
    assert(schema.typeName == "Person")
    assert(schema.rejectUnknown)

    val fields = schema.fields.map(f => f.fieldName -> f).toMap
    assert(fields("id").key)
    assert(fields("displayName").storageName == Some("display_name"))
    assert(fields("displayName").aliases == List("name"))
    assert(fields("active").default == Some(ast.SchemaDefault.Bool(true)))
    assert(fields("legacyName").storageName == Some("legacy_name"))
  }

  test("schemas: survives Normalize → Denormalize unchanged") {
    val mod = withFrontmatter(
      """schemas:
        |  Person:
        |    reject-unknown: true
        |    fields:
        |      id:
        |        key: true
        |      displayName:
        |        column: display_name
        |        aliases:
        |          - name
        |      active:
        |        default: false""".stripMargin)

    val ir1 = Normalize(mod)
    val schema = ir1.manifest.get.schemas.head
    assert(schema == ir.TypeSchemaDecl(
      typeName = "Person",
      fields = List(
        ir.FieldSchemaDecl("id", key = true),
        ir.FieldSchemaDecl("displayName", storageName = Some("display_name"), aliases = List("name")),
        ir.FieldSchemaDecl("active", default = Some(ir.SchemaDefault.Bool(false)))
      ),
      rejectUnknown = true
    ))

    val round = Denormalize(ir1)
    assert(round.manifest.get.schemas == mod.manifest.get.schemas)
  }

  test("schemas: survives .sscc serialization") {
    val mod = withFrontmatter(
      """schemas:
        |  Person:
        |    rejectUnknown: true
        |    fields:
        |      id:
        |        key: true
        |      displayName:
        |        name: display_name
        |        aliases: [name]
        |      active:
        |        default: true""".stripMargin)

    val round = ast.SsccFormat.read(ast.SsccFormat.write(mod)).toOption.get
    assert(round.manifest.get.schemas == mod.manifest.get.schemas)
  }

  test("objectStores: parses generated sync route metadata") {
    val mod = withFrontmatter(
      """objectStores:
        |  drafts:
        |    type: Draft
        |    sync: client-server
        |    database: default
        |    store: draft_docs
        |    table: app_object_store
        |    key: id
        |    conflict: manual""".stripMargin)

    val store = mod.manifest.get.objectStores.head
    assert(store.name == "drafts")
    assert(store.valueType == "Draft")
    assert(store.sync == "client-server")
    assert(store.database == "default")
    assert(store.store == Some("draft_docs"))
    assert(store.table == Some("app_object_store"))
    assert(store.key == Some("id"))
    assert(store.conflict == "manual")
  }

  test("objectStores: survives Normalize, Denormalize, and .sscc serialization") {
    val mod = withFrontmatter(
      """objectStores:
        |  drafts:
        |    type: Draft
        |    sync: client-server
        |    server:
        |      database: server
        |      store: draft_docs""".stripMargin)

    val ir1 = Normalize(mod)
    assert(ir1.manifest.get.objectStores.head == ir.ObjectStoreDecl(
      name = "drafts",
      valueType = "Draft",
      sync = "client-server",
      database = "server",
      store = Some("draft_docs")
    ))

    assert(Denormalize(ir1).manifest.get.objectStores == mod.manifest.get.objectStores)
    val round = ast.SsccFormat.read(ast.SsccFormat.write(mod)).toOption.get
    assert(round.manifest.get.objectStores == mod.manifest.get.objectStores)
  }
