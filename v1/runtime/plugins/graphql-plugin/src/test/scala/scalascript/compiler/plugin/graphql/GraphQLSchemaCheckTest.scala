package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.{BackendOptions, Diagnostic, ScopeContext, SymbolKind}
import scalascript.backend.spi.Diagnostic.GraphQLSdlError
import scalascript.ir.QualifiedName

/** Phase 4 — compile-time SDL validation in `GraphQLSourceLanguage.compileBlock`.
 *
 *  Tests call `compileBlock` directly and inspect `BlockArtifact.diagnostics`. */
class GraphQLSchemaCheckTest extends AnyFunSuite with Matchers:

  private val lang    = new GraphQLSourceLanguage
  private val noOpts  = BackendOptions()
  private val noScope = new ScopeContext:
    def isInScope(name: QualifiedName): Boolean         = false
    def resolve(name: QualifiedName): Option[SymbolKind] = None

  private def check(sdl: String): List[GraphQLSdlError] =
    lang.compileBlock(sdl, noScope, noOpts)
       .diagnostics.collect { case e: GraphQLSdlError => e }

  // ── valid SDL — no diagnostics ──────────────────────────────────────────

  test("valid minimal schema produces no diagnostics"):
    check("type Query { hello: String }") shouldBe empty

  test("valid schema with multiple types produces no diagnostics"):
    check("""
      type Query { user(id: ID!): User }
      type User { id: ID! name: String! }
    """) shouldBe empty

  test("valid schema with mutation and input type produces no diagnostics"):
    check("""
      type Query  { dummy: String }
      type Mutation { createUser(input: CreateUserInput!): User! }
      input CreateUserInput { name: String! email: String! }
      type User { id: ID! name: String! email: String! }
    """) shouldBe empty

  test("valid schema with enum produces no diagnostics"):
    check("""
      type Query { status: Status }
      enum Status { ACTIVE INACTIVE PENDING }
    """) shouldBe empty

  test("valid schema with interface produces no diagnostics"):
    check("""
      type Query { node(id: ID!): Node }
      interface Node { id: ID! }
      type User implements Node { id: ID! name: String! }
    """) shouldBe empty

  // ── invalid SDL — diagnostics emitted ──────────────────────────────────

  test("SDL with syntax error emits GraphQLSdlError"):
    val diags = check("type Query { !!invalid }}")
    diags should not be empty

  test("SDL with syntax error — invalid token — emits GraphQLSdlError"):
    val diags = check("type Query { !!bad }")
    diags should not be empty

  test("empty SDL emits GraphQLSdlError"):
    val diags = check("")
    diags should not be empty

  test("SDL with unclosed brace emits GraphQLSdlError"):
    val diags = check("type Query { hello: String")
    diags should not be empty

  test("SDL with duplicate type definition emits GraphQLSdlError"):
    val diags = check("""
      type Query { dummy: String }
      type User { id: ID! }
      type User { name: String! }
    """)
    diags should not be empty

  test("GraphQLSdlError carries non-empty message"):
    val diags = check("!!! not valid graphql !!!")
    diags should not be empty
    diags.head.message should not be empty

  test("compileBlock returns EmbeddedBlock fragment even on SDL errors"):
    import scalascript.ir.Content.EmbeddedBlock
    val artifact = lang.compileBlock("!!! bad sdl !!!", noScope, noOpts)
    artifact.diagnostics should not be empty
    artifact.fragment shouldBe a [EmbeddedBlock]
