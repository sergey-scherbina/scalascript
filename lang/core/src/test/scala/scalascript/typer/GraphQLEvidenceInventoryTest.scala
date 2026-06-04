package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.*

/** P4d-β — `GraphQLEvidenceInventory.count` unit tests.
 *
 *  Verifies that the inventory tallies object/interface/input types and their
 *  fields correctly across `graphql` blocks; legacy blocks without evidence are
 *  counted as 1 unknown type. */
class GraphQLEvidenceInventoryTest extends AnyFunSuite:

  private def module(blocks: Content*): NormalizedModule =
    val section = Section(Heading(1, "Schema"), blocks.toList, Nil)
    NormalizedModule(manifest = None, sections = List(section))

  private def graphqlBlock(ev: Option[GraphQLBlockEvidenceWire]): Content.EmbeddedBlock =
    Content.EmbeddedBlock("graphql", "type Query { id: ID! }", evidence = ev)

  private def declaredType(name: String, fieldCount: Int): GraphQLTypeEvidenceWire =
    GraphQLTypeEvidenceWire(name, "Object",
      List.tabulate(fieldCount)(i => GraphQLFieldEvidenceWire(s"f$i", "String", "Declared")))

  private def unknownType(name: String): GraphQLTypeEvidenceWire =
    GraphQLTypeEvidenceWire(name, "Object",
      List(GraphQLFieldEvidenceWire("f0", "Ghost", "Unknown")))

  // ── 1. Empty module → zero counts ────────────────────────────────────────

  test("empty module produces zero GraphQL evidence counts"):
    val counts = GraphQLEvidenceInventory.count(module())
    assert(counts == GraphQLEvidenceCounts())
    assert(counts.allDeclared)

  // ── 2. Block with all declared types ─────────────────────────────────────

  test("block with all-declared types counts correctly"):
    val ev = GraphQLBlockEvidenceWire(List(
      declaredType("Query", 2),
      declaredType("User",  3)
    ))
    val counts = GraphQLEvidenceInventory.count(module(graphqlBlock(Some(ev))))
    assert(counts.typesDeclared  == 2)
    assert(counts.typesUnknown   == 0)
    assert(counts.fieldsDeclared == 5)
    assert(counts.fieldsUnknown  == 0)
    assert(counts.allDeclared)

  // ── 3. Block with mixed declared/unknown ─────────────────────────────────

  test("block with mixed type evidence counts unknown types"):
    val ev = GraphQLBlockEvidenceWire(List(
      declaredType("Query", 1),
      unknownType("BadType")
    ))
    val counts = GraphQLEvidenceInventory.count(module(graphqlBlock(Some(ev))))
    assert(counts.typesDeclared  == 1)
    assert(counts.typesUnknown   == 1)
    assert(counts.fieldsDeclared == 1)
    assert(counts.fieldsUnknown  == 1)
    assert(!counts.allDeclared)

  // ── 4. Block without evidence (legacy) treated as 1 unknown type ─────────

  test("block with missing evidence is counted as 1 unknown type"):
    val counts = GraphQLEvidenceInventory.count(module(graphqlBlock(None)))
    assert(counts.typesDeclared == 0)
    assert(counts.typesUnknown  == 1)
    assert(!counts.allDeclared)

  // ── 5. Multiple graphql blocks summed ────────────────────────────────────

  test("counts are summed across multiple graphql blocks"):
    val ev1 = GraphQLBlockEvidenceWire(List(declaredType("Query", 2)))
    val ev2 = GraphQLBlockEvidenceWire(List(unknownType("Mutation")))
    val counts = GraphQLEvidenceInventory.count(module(
      graphqlBlock(Some(ev1)),
      graphqlBlock(Some(ev2))
    ))
    assert(counts.typesDeclared  == 1)
    assert(counts.typesUnknown   == 1)
    assert(counts.fieldsDeclared == 2)
    assert(counts.fieldsUnknown  == 1)
    assert(!counts.allDeclared)

  // ── 6. Union/Enum/Scalar types are not counted ───────────────────────────

  test("Union, Enum, and Scalar types are excluded from type counts"):
    val ev = GraphQLBlockEvidenceWire(List(
      GraphQLTypeEvidenceWire("Status", "Enum"),
      GraphQLTypeEvidenceWire("Search", "Union"),
      GraphQLTypeEvidenceWire("URL",    "Scalar"),
      declaredType("Query", 1)
    ))
    val counts = GraphQLEvidenceInventory.count(module(graphqlBlock(Some(ev))))
    assert(counts.typesDeclared  == 1)
    assert(counts.typesUnknown   == 0)
    assert(counts.fieldsDeclared == 1)
    assert(counts.fieldsUnknown  == 0)

  // ── 7. Non-graphql EmbeddedBlock is ignored ───────────────────────────────

  test("non-graphql embedded blocks are not counted"):
    val jsBlock = Content.EmbeddedBlock("javascript", "console.log(1)")
    val ev      = GraphQLBlockEvidenceWire(List(declaredType("Query", 1)))
    val gqlBlock = graphqlBlock(Some(ev))
    val counts   = GraphQLEvidenceInventory.count(module(jsBlock, gqlBlock))
    assert(counts.typesDeclared == 1)
    assert(counts.fieldsDeclared == 1)
