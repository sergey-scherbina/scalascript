package scalascript.transform

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.transform.ContentToolkitLint.{Registrations, RefKind}

/** declarative-ui Scope B.7 v1 — build-time id-existence lint.
 *
 *  Each test builds a tiny `.ssc` module pairing a `@ui=toolkit` control block
 *  with a scalascript registration block, then asserts on the harvested ids and
 *  the cross-check warnings. */
class ContentToolkitLintTest extends AnyFunSuite:

  /** Module with a toolkit YAML panel + a registration code block. */
  private def mod(yaml: String, code: String): scalascript.ast.Module =
    Parser.parse(
      s"""# Panel
         |
         |```yaml @ui=toolkit
         |$yaml
         |```
         |
         |```scalascript
         |$code
         |```
         |""".stripMargin
    )

  private val panel =
    """controls:
      |  type: vstack
      |  children:
      |    - type: button
      |      action: refresh
      |      label: Refresh
      |    - type: table
      |      source: invoices""".stripMargin

  // ── harvesting ────────────────────────────────────────────────────────────

  test("collectReferences harvests action + source ids from a @ui=toolkit block"):
    val refs = ContentToolkitLint.collectReferences(mod(panel, "val x = 1"))
    assert(refs.map(r => (r.kind, r.id)).toSet ==
      Set((RefKind.Action, "refresh"), (RefKind.Source, "invoices")))

  test("collectReferences treats `rows:` as a source reference"):
    val yaml = "controls:\n  type: table\n  rows: invoices"
    val refs = ContentToolkitLint.collectReferences(mod(yaml, "val x = 1"))
    assert(refs.map(_.kind) == List(RefKind.Source))
    assert(refs.map(_.id) == List("invoices"))

  test("collectReferences ignores a key embedded inside a quoted value"):
    // `label` is not a control key, and the inner `source:` is inside a string.
    val yaml = "controls:\n  type: text\n  label: \"data source: none\""
    assert(ContentToolkitLint.collectReferences(mod(yaml, "val x = 1")).isEmpty)

  test("collectReferences skips interpolated/dynamic ids ($...)"):
    val yaml = "controls:\n  type: button\n  action: $dynamicId"
    assert(ContentToolkitLint.collectReferences(mod(yaml, "val x = 1")).isEmpty)

  test("collectRegistrations harvests contentAction + contentRows ids nested in Map(...)"):
    val code =
      """val opts = contentToolkitOptionsWithRows(
        |  Map(contentRows("invoices", rows, cols)),
        |  Map(contentAction("refresh", act)))""".stripMargin
    val regs = ContentToolkitLint.collectRegistrations(mod(panel, code))
    assert(regs.actions == Set("refresh"))
    assert(regs.sources == Set("invoices"))

  // ── cross-check ───────────────────────────────────────────────────────────

  test("a correct id registered locally → no warning"):
    val code =
      """val o = contentToolkitOptionsWithRows(
        |  Map(contentRows("invoices", r, c)), Map(contentAction("refresh", a)))""".stripMargin
    assert(ContentToolkitLint.lint(mod(panel, code), Registrations.empty).isEmpty)

  test("an unknown action id with a non-empty action registry → one warning"):
    val code = """val a = contentAction("refersh", h)
                 |val s = contentRows("invoices", r, c)""".stripMargin
    val ws = ContentToolkitLint.lint(mod(panel, code), Registrations.empty)
    assert(ws.size == 1, s"expected 1 warning, got ${ws.map(_.msg)}")
    assert(ws.head.isWarning)
    assert(ws.head.msg.contains("action 'refresh'"))
    // edit-distance hint points at the registered typo.
    assert(ws.head.msg.contains("did you mean 'refersh'"))

  test("an unknown source id → warning; the registered action stays clean"):
    val code = """val a = contentAction("refresh", h)
                 |val s = contentRows("bills", r, c)""".stripMargin
    val ws = ContentToolkitLint.lint(mod(panel, code), Registrations.empty)
    assert(ws.size == 1)
    assert(ws.head.msg.contains("data source 'invoices'"))

  test("empty registry for a kind → no warning (conservative)"):
    // Only an action is registered; the source registry is empty → `invoices`
    // must NOT warn even though it is unregistered.
    val code = """val a = contentAction("refresh", h)"""
    val ws = ContentToolkitLint.lint(mod(panel, code), Registrations.empty)
    assert(ws.isEmpty, s"expected no warnings, got ${ws.map(_.msg)}")

  test("ids registered only in `extra` (imported graph) satisfy references"):
    val extra = Registrations(Set("refresh"), Set("invoices"))
    assert(ContentToolkitLint.lint(mod(panel, "val x = 1"), extra).isEmpty)

  test("warning carries a plausible file-level line for the bad reference"):
    val code = """val a = contentAction("refresh", h)
                 |val s = contentRows("bills", r, c)""".stripMargin
    val ws = ContentToolkitLint.lint(mod(panel, code), Registrations.empty)
    // `source: invoices` is on the last line of the 7-line yaml panel.
    assert(ws.head.span.exists(_.start.line > 0))
