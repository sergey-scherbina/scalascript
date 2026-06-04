package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.{BackendOptions, ScopeContext, SymbolKind}
import scalascript.ir.{Content, QualifiedName, GraphQLBlockEvidenceWire}

/** P4d-α — GraphQL type evidence populated by `GraphQLSourceLanguage.compileBlock`.
 *
 *  Exercises the `GraphQLBlockEvidenceWire` built from the `TypeDefinitionRegistry`
 *  and attached to `Content.EmbeddedBlock.evidence` when SDL parses successfully. */
class GraphQLEvidenceTest extends AnyFunSuite with Matchers:

  private val lang   = new GraphQLSourceLanguage
  private val noOpts = BackendOptions()
  private val noScope = new ScopeContext:
    def isInScope(name: QualifiedName): Boolean          = false
    def resolve(name: QualifiedName): Option[SymbolKind] = None

  private def compile(sdl: String): Content.EmbeddedBlock =
    lang.compileBlock(sdl, noScope, noOpts).fragment match
      case eb: Content.EmbeddedBlock => eb
      case other                     => fail(s"expected EmbeddedBlock, got $other")

  private def evidence(sdl: String): GraphQLBlockEvidenceWire =
    compile(sdl).evidence.getOrElse(fail("evidence missing from valid SDL"))

  // ── 1. All scalar fields → Declared ──────────────────────────────────────

  test("all built-in scalar fields are classified as Declared"):
    val ev = evidence("type Query { id: ID!, name: String, count: Int, score: Float, ok: Boolean }")
    val query = ev.types.find(_.name == "Query").getOrElse(fail("Query type missing"))
    query.kind shouldBe "Object"
    query.fields.foreach { f =>
      f.kind shouldBe "Declared"
    }

  // ── 2. Field type references object defined in the same block → Declared ─

  test("field type referencing another type in the same schema is Declared"):
    val sdl =
      """type Query { user: User }
        |type User { id: ID! }
        |""".stripMargin
    val ev = evidence(sdl)
    val query = ev.types.find(_.name == "Query").getOrElse(fail("Query missing"))
    val userField = query.fields.find(_.name == "user").getOrElse(fail("user field missing"))
    userField.typeName shouldBe "User"
    userField.kind shouldBe "Declared"

  // ── 3. Unknown type reference → Unknown ──────────────────────────────────

  test("field type referencing undefined name is Unknown"):
    val sdl = "type Query { user: User, ghost: Useer }"
    val ev  = evidence(sdl)
    val query = ev.types.find(_.name == "Query").getOrElse(fail("Query missing"))
    val ghost = query.fields.find(_.name == "ghost").getOrElse(fail("ghost field missing"))
    ghost.typeName shouldBe "Useer"
    ghost.kind shouldBe "Unknown"
    // existing known field still Declared (User is in the block for the first half-test)
    // In this SDL "User" is not defined, so it should be Unknown too
    val user = query.fields.find(_.name == "user").getOrElse(fail("user field missing"))
    user.kind shouldBe "Unknown"

  // ── 4. List / NonNull wrappers don't affect classification ────────────────

  test("NonNull and List wrappers unwrap to the base type for classification"):
    val sdl =
      """type Query { items: [Item!]!, single: Item }
        |type Item { value: String! }
        |""".stripMargin
    val ev = evidence(sdl)
    val query = ev.types.find(_.name == "Query").getOrElse(fail("Query missing"))
    query.fields.foreach { f =>
      withClue(s"field ${f.name}") {
        f.typeName shouldBe "Item"
        f.kind shouldBe "Declared"
      }
    }

  // ── 5. Input object with mix of known/unknown fields ─────────────────────

  test("input object with mixed known/unknown field types classifies correctly"):
    val sdl =
      """input CreateUser { name: String!, role: RoleEnum, mystery: Phantom }
        |enum RoleEnum { ADMIN USER }
        |""".stripMargin
    val ev = evidence(sdl)
    val input = ev.types.find(_.name == "CreateUser").getOrElse(fail("CreateUser missing"))
    input.kind shouldBe "Input"
    val nameField    = input.fields.find(_.name == "name").getOrElse(fail("name missing"))
    val roleField    = input.fields.find(_.name == "role").getOrElse(fail("role missing"))
    val mysteryField = input.fields.find(_.name == "mystery").getOrElse(fail("mystery missing"))
    nameField.kind    shouldBe "Declared"  // String is a builtin scalar
    roleField.kind    shouldBe "Declared"  // RoleEnum is defined in the same block
    mysteryField.kind shouldBe "Unknown"   // Phantom is undefined

  // ── 6a. Invalid SDL → evidence absent, diagnostics present ───────────────

  test("invalid SDL produces no evidence field and emits diagnostics"):
    val artifact = lang.compileBlock("type Query { bad: }", noScope, noOpts)
    artifact.fragment match
      case eb: Content.EmbeddedBlock =>
        eb.evidence shouldBe None
        artifact.diagnostics should not be empty
      case other => fail(s"expected EmbeddedBlock, got $other")

  // ── 6b. Round-trip via ArtifactIO preserves evidence ─────────────────────

  test("EmbeddedBlock with evidence round-trips through ArtifactIO"):
    import scalascript.ir.*
    import scalascript.artifact.ArtifactIO

    val sdl = "type Query { id: ID! }"
    val eb  = compile(sdl)
    eb.evidence should not be empty

    val section = Section(
      heading     = Heading(1, "Schema"),
      content     = List(eb),
      subsections = Nil
    )
    val nm = NormalizedModule(
      manifest = Some(Manifest(None, None, None, Map.empty, Nil, Nil, Nil, None)),
      sections = List(section)
    )
    val json = ArtifactIO.writeIr(nm, List("test"), None, "0" * 64)
    ArtifactIO.readIr(json) match
      case Right((parsed, _, _, _)) =>
        val block = parsed.sections.head.content.head
        block match
          case parsedEb: Content.EmbeddedBlock =>
            parsedEb.evidence should not be empty
            parsedEb.evidence.get.types.find(_.name == "Query") should not be empty
          case other => fail(s"expected EmbeddedBlock after round-trip, got $other")
      case Left(err) => fail(s"ArtifactIO round-trip failed: $err")

  // ── 6c. Legacy .scir without evidence field still reads ──────────────────

  test("legacy .scir without evidence field on EmbeddedBlock still reads"):
    import scalascript.ir.*
    import scalascript.artifact.ArtifactIO

    val section = Section(
      heading     = Heading(1, "Schema"),
      content     = List(Content.EmbeddedBlock("graphql", "type Query { id: ID! }")),
      subsections = Nil
    )
    val nm = NormalizedModule(
      manifest = Some(Manifest(None, None, None, Map.empty, Nil, Nil, Nil, None)),
      sections = List(section)
    )
    // Write, then strip the `evidence` field from the JSON to simulate legacy artifact
    val doc  = ujson.read(ArtifactIO.writeIr(nm, List("test"), None, "0" * 64))
    val body = ujson.read(doc("body").str)
    body("sections").arr.foreach { sec =>
      sec("content").arr.foreach { c =>
        if c.obj.contains("evidence") then c.obj.remove("evidence")
      }
    }
    doc.obj("body") = ujson.Str(body.render())

    ArtifactIO.readIr(doc.render()) match
      case Right((parsed, _, _, _)) =>
        val block = parsed.sections.head.content.head
        block match
          case parsedEb: Content.EmbeddedBlock =>
            parsedEb.evidence shouldBe None
          case other => fail(s"expected EmbeddedBlock after legacy read, got $other")
      case Left(err) => fail(s"legacy .scir should read without evidence field: $err")
