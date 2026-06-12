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
    val extra = Registrations(Set("refresh"), Set("invoices"), Set.empty)
    assert(ContentToolkitLint.lint(mod(panel, "val x = 1"), extra).isEmpty)

  test("warning carries a plausible file-level line for the bad reference"):
    val code = """val a = contentAction("refresh", h)
                 |val s = contentRows("bills", r, c)""".stripMargin
    val ws = ContentToolkitLint.lint(mod(panel, code), Registrations.empty)
    // `source: invoices` is on the last line of the 7-line yaml panel.
    assert(ws.head.span.exists(_.start.line > 0))

  // ── signal references (Scope B.7+) ──────────────────────────────────────────

  private val signalPanel =
    """controls:
      |  type: vstack
      |  children:
      |    - type: signalText
      |      signal: status
      |    - type: button
      |      action: save
      |      enabledWhen: canSave""".stripMargin

  test("collectReferences harvests signal / enabledWhen as Signal references"):
    val refs = ContentToolkitLint.collectReferences(mod(signalPanel, "val x = 1"))
    assert(refs.exists(r => r.kind == RefKind.Signal && r.id == "status"))
    assert(refs.exists(r => r.kind == RefKind.Signal && r.id == "canSave"))

  test("collectLocalSignals harvests ids from a YAML signals: block"):
    val yaml =
      """signals:
        |  status: ""
        |  count: 0
        |controls:
        |  type: signalText
        |  signal: status""".stripMargin
    assert(ContentToolkitLint.collectLocalSignals(mod(yaml, "val x = 1")) == Set("status", "count"))

  test("a signal id registered via contentComputed → no warning"):
    val code = """val a = contentAction("save", h)
                 |val c = contentComputed("status", s1)
                 |val d = contentComputed("canSave", s2)""".stripMargin
    assert(ContentToolkitLint.lint(mod(signalPanel, code), Registrations.empty).isEmpty)

  test("a signal id declared in a local YAML signals: block → no warning"):
    val yaml =
      """signals:
        |  status: ""
        |  canSave: false
        |controls:
        |  type: vstack
        |  children:
        |    - type: signalText
        |      signal: status
        |    - type: button
        |      action: save
        |      enabledWhen: canSave""".stripMargin
    // computed registry non-empty (so the universe is populated) but the refs are
    // satisfied by the local signals: block, not the computed registration.
    val code = """val a = contentAction("save", h)
                 |val c = contentComputed("other", s)""".stripMargin
    assert(ContentToolkitLint.lint(mod(yaml, code), Registrations.empty).isEmpty)

  test("an unknown signal id with a non-empty signal universe → warning"):
    val code = """val a = contentAction("save", h)
                 |val c = contentComputed("canSave", s)""".stripMargin
    // `status` is referenced but neither registered (only canSave) nor local.
    val ws = ContentToolkitLint.lint(mod(signalPanel, code), Registrations.empty)
    assert(ws.exists(w => w.msg.contains("signal 'status'") && w.isWarning),
      s"expected an unknown-signal warning, got ${ws.map(_.msg)}")

  test("an empty signal universe → no signal warning (conservative)"):
    // No contentComputed anywhere and no signals: block → universe empty → skip.
    val code = """val a = contentAction("save", h)"""
    val ws = ContentToolkitLint.lint(mod(signalPanel, code), Registrations.empty)
    assert(!ws.exists(_.msg.contains("signal")), s"expected no signal warnings, got ${ws.map(_.msg)}")

  // ── Markdown `toolkit:` link references (Scope B.7) ─────────────────────────

  /** Module pairing Markdown body (incl. `toolkit:` links) with a registration block. */
  private def modMd(markdown: String, code: String): scalascript.ast.Module =
    Parser.parse(
      s"""# Panel
         |
         |$markdown
         |
         |```scalascript
         |$code
         |```
         |""".stripMargin)

  test("collectReferences harvests rows/action/source from Markdown toolkit links"):
    val md =
      """- [Invoices](toolkit:table?rows=invoices)
        |- [Refresh](toolkit:button?action=refresh)
        |- [Bills](toolkit:table?source=bills)""".stripMargin
    val refs = ContentToolkitLint.collectReferences(modMd(md, "val x = 1"))
    assert(refs.map(r => (r.kind, r.id)).toSet ==
      Set((RefKind.Source, "invoices"), (RefKind.Action, "refresh"), (RefKind.Source, "bills")),
      s"got: ${refs.map(r => (r.kind, r.id))}")

  test("collectReferences does NOT harvest signal/enabledWhen from a Markdown link (ambiguous)"):
    // An input control declares its signal inline — linting it would false-warn.
    val md = "- [Apply](toolkit:button?signal=applyStatus&value=x&enabledWhen=enabled)"
    assert(ContentToolkitLint.collectReferences(modMd(md, "val x = 1")).isEmpty)

  test("collectReferences skips a non-toolkit Markdown link"):
    val md = "- [Docs](https://example.com/page?action=refresh)"
    assert(ContentToolkitLint.collectReferences(modMd(md, "val x = 1")).isEmpty)

  test("lint warns on an unregistered Markdown `toolkit:table?rows=` id"):
    val md = "- [Invoices](toolkit:table?rows=invioces)"   // typo'd id
    val code = """val s = contentRows("invoices", r, c)"""
    val ws = ContentToolkitLint.lint(modMd(md, code), Registrations.empty)
    assert(ws.size == 1, s"expected 1 warning, got ${ws.map(_.msg)}")
    assert(ws.head.msg.contains("data source 'invioces'"))
    assert(ws.head.msg.contains("did you mean 'invoices'"))

  test("lint does NOT warn when the Markdown link id IS registered"):
    val md = "- [Invoices](toolkit:table?rows=invoices)"
    val code = """val s = contentRows("invoices", r, c)"""
    assert(ContentToolkitLint.lint(modMd(md, code), Registrations.empty).isEmpty)

  test("lint is conservative — a Markdown link ref with an empty registry → no warning"):
    val md = "- [Invoices](toolkit:table?rows=invoices)"
    assert(ContentToolkitLint.lint(modMd(md, "val x = 1"), Registrations.empty).isEmpty)

  test("real toolkit-link examples lint clean (no false positives)"):
    // Guarded on existence so a stray CWD doesn't fail the suite. content-live-rows
    // registers its `rows=invoices` source in-module; markdown-toolkit-links uses only
    // signal/value controls (not linted) — both must produce zero warnings.
    for name <- List("content-live-rows.ssc", "markdown-toolkit-links.ssc") do
      val f = os.pwd / "examples" / name
      if os.exists(f) then
        val m  = Parser.parse(os.read(f))
        val ws = ContentToolkitLint.lint(m, Registrations.empty)
        assert(ws.isEmpty, s"$name should lint clean, got: ${ws.map(_.msg)}")
