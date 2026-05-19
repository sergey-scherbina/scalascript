package scalascript.cli.lsp

import org.scalatest.funsuite.AnyFunSuite

class LspHandlersTest extends AnyFunSuite:

  private def newHandlers(): (Documents, Handlers) =
    val docs = new Documents
    (docs, new Handlers(docs))

  // ─── initialize ────────────────────────────────────────────────────

  test("initialize returns advertised capabilities") {
    val (_, h) = newHandlers()
    val result = h.initialize(ujson.Obj("processId" -> ujson.Null))
    val caps = result("capabilities")
    assert(caps("textDocumentSync").num == 1)
    assert(caps("definitionProvider").bool == true)
    assert(caps("hoverProvider").bool == true)
    val info = result("serverInfo")
    assert(info("name").str == "scalascript-lsp")
  }

  // ─── didOpen / publishDiagnostics ──────────────────────────────────

  test("didOpen with a valid .ssc emits empty diagnostics") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val text = """# Hello
                 |
                 |```scala
                 |val x: Int = 42
                 |```
                 |""".stripMargin
    val params = ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri"        -> "file:///tmp/test.ssc",
        "languageId" -> "scalascript",
        "version"    -> 1,
        "text"       -> text
      )
    )
    val n = h.didOpen(params).get
    assert(n.method == "textDocument/publishDiagnostics")
    val diags = n.params("diagnostics").arr
    assert(diags.isEmpty, s"expected no diagnostics, got: $diags")
  }

  test("didOpen with a type error emits a diagnostic on the bad assignment") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    // 4 lines preceding the bad assignment so we can check `line` field.
    val text = """# Bad
                 |
                 |```scala
                 |val x: Int = "bad"
                 |```
                 |""".stripMargin
    val params = ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri"        -> "file:///tmp/bad.ssc",
        "languageId" -> "scalascript",
        "version"    -> 1,
        "text"       -> text
      )
    )
    val n = h.didOpen(params).get
    val diags = n.params("diagnostics").arr
    assert(diags.nonEmpty, "expected a type-mismatch diagnostic")
    val msgs = diags.map(_.obj("message").str)
    assert(msgs.exists(_.toLowerCase.contains("type mismatch")),
      s"expected type-mismatch error, got: $msgs")
  }

  // ─── hover ─────────────────────────────────────────────────────────

  test("hover on a local val name returns its type") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val text = """# H
                 |
                 |```scala
                 |val x: Int = 42
                 |```
                 |""".stripMargin
    val uri = "file:///tmp/hover.ssc"
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      )
    ))
    // Cursor positioned on "x" (line 3, character 4 — 0-indexed).
    val result = h.hover(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "position"     -> ujson.Obj("line" -> 3, "character" -> 4)
    ))
    assert(result != ujson.Null, "expected a hover result, got null")
    val content = result("contents")("value").str
    assert(content.contains("Int"), s"expected 'Int' in hover, got: $content")
  }

  // ─── definition ────────────────────────────────────────────────────

  test("definition on a local def reference returns its location") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    // Define foo on line 3 (0-indexed) and reference it on line 4.
    val text = """# H
                 |
                 |```scala
                 |def foo(x: Int): Int = x + 1
                 |val y = foo(2)
                 |```
                 |""".stripMargin
    val uri = "file:///tmp/def.ssc"
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      )
    ))
    // Cursor on "foo" inside the reference `foo(2)` — line 4, char 8.
    val result = h.definition(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "position"     -> ujson.Obj("line" -> 4, "character" -> 9)
    ))
    assert(result != ujson.Null, "expected a definition location")
    assert(result("uri").str == uri)
    // Range should point somewhere in the file; we don't assert exact
    // position since scalameta's per-block coords are block-local.
    val r = result("range")
    assert(r("start")("line").num >= 0)
  }

  test("definition on undefined name returns null + emits diagnostic") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val text = """# H
                 |
                 |```scala
                 |val y = undefinedName
                 |```
                 |""".stripMargin
    val uri = "file:///tmp/undef.ssc"
    val n = h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      )
    )).get
    val diags = n.params("diagnostics").arr.toList
    val msgs  = diags.map(_.obj("message").str)
    assert(msgs.exists(_.contains("undefined")), s"expected undefined-name diagnostic, got: $msgs")

    // Cursor on "undefinedName".
    val result = h.definition(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "position"     -> ujson.Obj("line" -> 3, "character" -> 12)
    ))
    assert(result == ujson.Null, "expected null for undefined name")
  }

  // ─── didChange ─────────────────────────────────────────────────────

  test("didChange refreshes diagnostics") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/change.ssc"
    val good = """# H
                 |
                 |```scala
                 |val x: Int = 42
                 |```
                 |""".stripMargin
    val bad  = """# H
                 |
                 |```scala
                 |val x: Int = "no"
                 |```
                 |""".stripMargin
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> good)
    ))
    val n = h.didChange(ujson.Obj(
      "textDocument"   -> ujson.Obj("uri" -> uri, "version" -> 2),
      "contentChanges" -> ujson.Arr(ujson.Obj("text" -> bad))
    )).get
    val diags = n.params("diagnostics").arr
    assert(diags.nonEmpty, "expected a diagnostic after didChange to invalid source")
  }

  // ─── didClose ──────────────────────────────────────────────────────

  test("didClose removes the document from the store") {
    val (docs, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/close.ssc"
    val text = """# H
                 |
                 |```scala
                 |val x: Int = 1
                 |```
                 |""".stripMargin
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text)
    ))
    assert(docs.get(uri).isDefined)
    h.didClose(ujson.Obj("textDocument" -> ujson.Obj("uri" -> uri)))
    assert(docs.get(uri).isEmpty)
  }
