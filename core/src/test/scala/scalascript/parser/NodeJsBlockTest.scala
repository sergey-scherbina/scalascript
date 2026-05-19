package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast
import scalascript.ir
import scalascript.transform.{Normalize, Denormalize}

/** v1.25 Phase 2 — front-end recognition of `node.js` (alias `node`)
 *  fenced code blocks.
 *
 *  The contract for this phase: a `node.js` block is *opaque executable*
 *  (`Lang.isOpaqueExec` is true) — it is neither parsed nor treated as a
 *  string-valued block, and survives Parse → Normalize → Denormalize
 *  unchanged.  The `node` backend (Phase 3) is the only consumer that
 *  acts on it; every other backend simply does not match the lang tag
 *  and silently ignores the block.
 *
 *  Capability-level rejection on non-Node backends (`UnknownBlockLanguage`)
 *  is deferred to Phase 3, where the `Capabilities` SPI gains a
 *  block-language axis alongside features. */
class NodeJsBlockTest extends AnyFunSuite:

  private def firstBlock(src: String): ast.Content.CodeBlock =
    val module = Parser.parse(src)
    module.sections
      .flatMap(_.content)
      .collectFirst { case cb: ast.Content.CodeBlock => cb }
      .getOrElse(fail("no CodeBlock parsed"))

  test("node.js block: lang tag preserved verbatim from the fence") {
    val src =
      """|# Tools
         |
         |```node.js
         |globalThis.add = (a, b) => a + b;
         |```
         |""".stripMargin
    val cb = firstBlock(src)
    assert(cb.lang == "node.js")
    assert(cb.source.trim == "globalThis.add = (a, b) => a + b;")
    assert(cb.tree.isEmpty, "we do not invoke scalameta on JS")
  }

  test("node alias: same opaque-exec classification") {
    val src =
      """|# Tools
         |
         |```node
         |globalThis.hello = () => 'hi';
         |```
         |""".stripMargin
    val cb = firstBlock(src)
    assert(cb.lang == "node")
    assert(ast.Lang.isNode(cb.lang))
    assert(ast.Lang.isOpaqueExec(cb.lang))
    assert(!ast.Lang.isStringBlock(cb.lang))
    assert(!ast.Lang.isParseable(cb.lang))
  }

  test("node.js block: survives Normalize as EmbeddedBlock with source intact") {
    val src =
      """|# Tools
         |
         |```node.js
         |const fs = require('fs');
         |globalThis.readUtf8 = (p) => fs.readFileSync(p, 'utf8');
         |```
         |""".stripMargin
    val module     = Parser.parse(src)
    val normalised = Normalize(module)
    val embedded = normalised.sections
      .flatMap(_.content)
      .collectFirst { case eb: ir.Content.EmbeddedBlock => eb }
      .getOrElse(fail("Normalize did not produce an EmbeddedBlock for node.js"))
    assert(embedded.language == "node.js")
    assert(embedded.source.contains("globalThis.readUtf8"))
  }

  test("node.js block: round-trips through Normalize → Denormalize unchanged") {
    val src =
      """|# Tools
         |
         |```node.js
         |globalThis.greet = (name) => `Hello, ${name}!`;
         |```
         |""".stripMargin
    val module          = Parser.parse(src)
    val redenormalised = Denormalize(Normalize(module))
    val cb = redenormalised.sections
      .flatMap(_.content)
      .collectFirst { case cb: ast.Content.CodeBlock => cb }
      .getOrElse(fail("Denormalize produced no CodeBlock"))
    assert(cb.lang == "node.js")
    assert(cb.source.contains("globalThis.greet"))
    assert(cb.tree.isEmpty, "Denormalize must not invoke a parser on JS source")
  }
