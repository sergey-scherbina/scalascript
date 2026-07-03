package scalascript.cli.lsp

import org.scalatest.funsuite.AnyFunSuite

/** Tests for LSP Phase 3 handlers:
 *  - textDocument/codeAction (quickfix for unknown-name + unused-import diagnostics)
 *  - textDocument/formatting  (trailing whitespace, tab-to-space, clean doc)
 *  - textDocument/inlayHint   (type hints on unannotated vals, range filtering)
 *  - workspace/didChangeWatchedFiles (re-parse on change, close on delete)
 *  - initialize capabilities  (codeActionProvider, documentFormattingProvider,
 *                               inlayHintProvider, workspace.fileOperations)
 *
 *  These tests complement the 14 Phase-3 tests already in LspHandlersTest
 *  with additional coverage for edge cases and the workspace.fileOperations
 *  capability registration.
 */
class LspPhase3Test extends AnyFunSuite:

  private def newHandlers(): (Documents, Handlers) =
    val docs = new Documents
    (docs, new Handlers(docs))

  private def openDoc(h: Handlers, uri: String, text: String): Unit =
    h.initialize(ujson.Obj())
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      )
    ))

  // ─── initialize capabilities ─────────────────────────────────────────

  test("initialize advertises codeActionProvider") {
    val (_, h) = newHandlers()
    val caps = h.initialize(ujson.Obj())("capabilities")
    assert(caps.obj.contains("codeActionProvider"),
      s"expected codeActionProvider in capabilities, got: ${caps.obj.keys.mkString(", ")}")
    assert(caps("codeActionProvider").bool == true)
  }

  test("initialize advertises documentFormattingProvider") {
    val (_, h) = newHandlers()
    val caps = h.initialize(ujson.Obj())("capabilities")
    assert(caps.obj.contains("documentFormattingProvider"),
      "expected documentFormattingProvider in capabilities")
    assert(caps("documentFormattingProvider").bool == true)
  }

  test("initialize advertises inlayHintProvider") {
    val (_, h) = newHandlers()
    val caps = h.initialize(ujson.Obj())("capabilities")
    assert(caps.obj.contains("inlayHintProvider"),
      "expected inlayHintProvider in capabilities")
    assert(caps("inlayHintProvider").bool == true)
  }

  test("initialize advertises workspace.fileOperations with **/*.ssc watcher") {
    val (_, h) = newHandlers()
    val caps = h.initialize(ujson.Obj())("capabilities")
    assert(caps.obj.contains("workspace"),
      "expected workspace in capabilities")
    val ws = caps("workspace")
    assert(ws.obj.contains("fileOperations"),
      "expected fileOperations in workspace capabilities")
    val fo = ws("fileOperations")
    assert(fo.obj.contains("didChangeWatchedFiles"),
      "expected didChangeWatchedFiles in fileOperations")
    val watchers = fo("didChangeWatchedFiles")("watchers").arr
    assert(watchers.exists(_("globPattern").str == "**/*.ssc"),
      s"expected **/*.ssc watcher, got: $watchers")
  }

  // ─── textDocument/codeAction ──────────────────────────────────────────

  test("codeAction returns empty array for unknown URI") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val result = h.codeAction(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> "file:///tmp/no-such.ssc"),
      "range" -> ujson.Obj(
        "start" -> ujson.Obj("line" -> 0, "character" -> 0),
        "end"   -> ujson.Obj("line" -> 1, "character" -> 0)
      ),
      "context" -> ujson.Obj("diagnostics" -> ujson.Arr())
    )).arr
    assert(result.isEmpty, "expected empty array for unknown URI")
  }

  test("codeAction returns empty array when context has no relevant diagnostics") {
    val (_, h) = newHandlers()
    val uri = "file:///tmp/ca-empty.ssc"
    val text =
      """# S
        |
        |```scala
        |val x: Int = 42
        |```
        |""".stripMargin
    openDoc(h, uri, text)
    // Unrelated diagnostic message — should produce no actions.
    val diag = ujson.Obj(
      "range"   -> ujson.Obj("start" -> ujson.Obj("line" -> 3, "character" -> 0),
                             "end"   -> ujson.Obj("line" -> 3, "character" -> 10)),
      "message" -> "some unrelated error"
    )
    val result = h.codeAction(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "range" -> ujson.Obj(
        "start" -> ujson.Obj("line" -> 3, "character" -> 0),
        "end"   -> ujson.Obj("line" -> 3, "character" -> 20)
      ),
      "context" -> ujson.Obj("diagnostics" -> ujson.Arr(diag))
    )).arr
    assert(result.isEmpty, s"expected no actions for unrelated diagnostic, got: $result")
  }

  test("codeAction for unused-import diagnostic produces quickfix with WorkspaceEdit") {
    val (_, h) = newHandlers()
    val uri = "file:///tmp/ca-unused-import.ssc"
    val text =
      """# S
        |
        |```scala
        |val x: Int = 42
        |```
        |""".stripMargin
    openDoc(h, uri, text)
    // Simulate an "Unused import" diagnostic at line 0
    val diag = ujson.Obj(
      "range" -> ujson.Obj(
        "start" -> ujson.Obj("line" -> 0, "character" -> 0),
        "end"   -> ujson.Obj("line" -> 0, "character" -> 20)
      ),
      "severity" -> 4,
      "message"  -> "Unused import: scala.util.Try"
    )
    val result = h.codeAction(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "range" -> ujson.Obj(
        "start" -> ujson.Obj("line" -> 0, "character" -> 0),
        "end"   -> ujson.Obj("line" -> 0, "character" -> 20)
      ),
      "context" -> ujson.Obj("diagnostics" -> ujson.Arr(diag))
    )).arr
    assert(result.nonEmpty, "expected quickfix actions for unused-import diagnostic")
    result.foreach { action =>
      assert(action("kind").str == "quickfix", s"expected kind=quickfix, got: ${action("kind")}")
      assert(action.obj.contains("edit"), "expected WorkspaceEdit in action")
    }
    // Should suggest removing the import.
    val titles = result.map(_("title").str).toList
    assert(titles.exists(_.toLowerCase.contains("import")),
      s"expected import-related title, got: $titles")
  }

  test("codeAction: all returned actions have kind=quickfix") {
    val (_, h) = newHandlers()
    val uri = "file:///tmp/ca-kinds.ssc"
    openDoc(h, uri, "# S\n\n```scala\nval x = 1\n```\n")
    val diag = ujson.Obj(
      "range"   -> ujson.Obj("start" -> ujson.Obj("line" -> 0, "character" -> 0),
                             "end"   -> ujson.Obj("line" -> 0, "character" -> 10)),
      "message" -> "Unused import: something"
    )
    val result = h.codeAction(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "range" -> ujson.Obj(
        "start" -> ujson.Obj("line" -> 0, "character" -> 0),
        "end"   -> ujson.Obj("line" -> 0, "character" -> 10)
      ),
      "context" -> ujson.Obj("diagnostics" -> ujson.Arr(diag))
    )).arr
    for action <- result do
      assert(action("kind").str == "quickfix",
        s"all actions must have kind=quickfix, got ${action("kind")}")
  }

  // ─── textDocument/formatting ─────────────────────────────────────────

  test("formatting: trailing whitespace is stripped") {
    val (_, h) = newHandlers()
    val uri = "file:///tmp/fmt-trailing.ssc"
    // Trailing spaces after text
    val text = "# S  \n\n```scala\nval x = 1   \n```\n"
    openDoc(h, uri, text)
    val result = h.formatting(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "options"      -> ujson.Obj("tabSize" -> 2, "insertSpaces" -> true)
    )).arr
    // If edits are returned, verify the new text has no trailing whitespace.
    for edit <- result do
      val newText = edit("newText").str
      // newText is a single-line replacement; it should not end with spaces.
      assert(!newText.endsWith(" "),
        s"edited text should not have trailing whitespace: '$newText'")
  }

  test("formatting: tabs in code blocks are replaced with 2 spaces") {
    val (_, h) = newHandlers()
    val uri = "file:///tmp/fmt-tabs.ssc"
    // Tab-indented code block
    val text = "# S\n\n```scala\n\tval x = 1\n```\n"
    openDoc(h, uri, text)
    val result = h.formatting(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "options"      -> ujson.Obj("tabSize" -> 2, "insertSpaces" -> true)
    )).arr
    assert(result.nonEmpty, "expected an edit to replace tabs")
    result.foreach { edit =>
      assert(!edit("newText").str.contains("\t"),
        s"expected no tabs after formatting, got: '${edit("newText").str}'")
    }
  }

  test("formatting: returns empty array for already-clean document") {
    val (_, h) = newHandlers()
    val uri = "file:///tmp/fmt-clean.ssc"
    // No trailing whitespace, no tabs.
    val text = "# S\n\n```scala\nval x = 1\n```\n"
    openDoc(h, uri, text)
    val result = h.formatting(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "options"      -> ujson.Obj("tabSize" -> 2, "insertSpaces" -> true)
    )).arr
    assert(result.isEmpty, s"expected no edits for clean document, got: $result")
  }

  test("formatting: returns empty array for unknown URI") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val result = h.formatting(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> "file:///tmp/no-such.ssc"),
      "options"      -> ujson.Obj("tabSize" -> 2, "insertSpaces" -> true)
    )).arr
    assert(result.isEmpty, "expected empty array for unknown URI")
  }

  // ─── textDocument/inlayHint ──────────────────────────────────────────

  test("inlayHint: returns empty array for unknown URI") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val result = h.inlayHint(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> "file:///tmp/no-such.ssc"),
      "range" -> ujson.Obj(
        "start" -> ujson.Obj("line" -> 0, "character" -> 0),
        "end"   -> ujson.Obj("line" -> 10, "character" -> 0)
      )
    )).arr
    assert(result.isEmpty, "expected empty array for unknown URI")
  }

  test("inlayHint: val with explicit type annotation gets no hint") {
    val (_, h) = newHandlers()
    val uri = "file:///tmp/ih-explicit.ssc"
    val text =
      """# S
        |
        |```scala
        |val x: Int = 42
        |```
        |""".stripMargin
    openDoc(h, uri, text)
    val result = h.inlayHint(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "range" -> ujson.Obj(
        "start" -> ujson.Obj("line" -> 0, "character" -> 0),
        "end"   -> ujson.Obj("line" -> 10, "character" -> 0)
      )
    )).arr
    // Explicit annotation → no inlay hint should be emitted for `x`.
    // Line 3 is where `val x: Int = 42` lives; if any hints are there they are wrong.
    val line3Hints = result.filter(_("position")("line").num.toInt == 3)
    assert(line3Hints.isEmpty,
      s"expected no hint for explicitly-typed val, got: $line3Hints")
  }

  test("inlayHint: out-of-range bindings produce no hints") {
    val (_, h) = newHandlers()
    val uri = "file:///tmp/ih-range.ssc"
    val text =
      """# S
        |
        |```scala
        |val a = 1
        |val b = 2
        |val c = 3
        |```
        |""".stripMargin
    openDoc(h, uri, text)
    // Request hints only for line 3 (val a).
    val result = h.inlayHint(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "range" -> ujson.Obj(
        "start" -> ujson.Obj("line" -> 3, "character" -> 0),
        "end"   -> ujson.Obj("line" -> 3, "character" -> 100)
      )
    )).arr
    // Any returned hints must be on line 3 only.
    for hint <- result do
      val hintLine = hint("position")("line").num.toInt
      assert(hintLine == 3,
        s"expected hint only on line 3, got hint on line $hintLine")
  }

  test("inlayHint: returned hints have required fields (position, label, kind)") {
    val (_, h) = newHandlers()
    val uri = "file:///tmp/ih-fields.ssc"
    val text =
      """# S
        |
        |```scala
        |val n = 99
        |```
        |""".stripMargin
    openDoc(h, uri, text)
    val result = h.inlayHint(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "range" -> ujson.Obj(
        "start" -> ujson.Obj("line" -> 0, "character" -> 0),
        "end"   -> ujson.Obj("line" -> 10, "character" -> 0)
      )
    )).arr
    for hint <- result do
      assert(hint.obj.contains("position"), "hint must have position")
      assert(hint.obj.contains("label"),    "hint must have label")
      assert(hint.obj.contains("kind"),     "hint must have kind")
      assert(hint("label").str.startsWith(": "),
        s"type hint label should start with ': ', got: '${hint("label").str}'")
      assert(hint("kind").num.toInt == 1,
        s"type hint kind should be 1 (Type), got: ${hint("kind").num}")
  }

  // ─── workspace/didChangeWatchedFiles ─────────────────────────────────

  test("didChangeWatchedFiles: changed open doc triggers publishDiagnostics notification") {
    val (docs, h) = newHandlers()
    h.initialize(ujson.Obj())

    // Write a valid .ssc file to a temp location.
    val tmp     = os.temp.dir(prefix = "ssc-lsp-p3watch-")
    val sscPath = tmp / "watched.ssc"
    val goodText =
      """# S
        |
        |```scala
        |val x: Int = 1
        |```
        |""".stripMargin
    os.write(sscPath, goodText)
    val uri = Documents.pathToUri(sscPath)

    // Open the document first so it's tracked.
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> goodText
      )
    ))
    assert(docs.get(uri).isDefined, "expected doc to be open")

    // Update the file on disk.
    val updatedText = "# S\n\n```scala\nval y: Int = 2\n```\n"
    os.write.over(sscPath, updatedText)

    // Notify server of file change (FileChangeType.Changed = 1).
    val notifications = h.didChangeWatchedFiles(ujson.Obj(
      "changes" -> ujson.Arr(
        ujson.Obj("uri" -> uri, "type" -> 1)
      )
    ))

    assert(notifications.nonEmpty, "expected at least one notification for changed file")
    val diagNotif = notifications.find(_.method == "textDocument/publishDiagnostics")
    assert(diagNotif.isDefined, "expected publishDiagnostics notification")
    assert(diagNotif.get.params("uri").str == uri, "notification uri must match file uri")
  }

  test("didChangeWatchedFiles: deleted file closes document in store") {
    val (docs, h) = newHandlers()
    h.initialize(ujson.Obj())

    val tmp     = os.temp.dir(prefix = "ssc-lsp-p3del-")
    val sscPath = tmp / "deleted.ssc"
    val text    = "# S\n\n```scala\nval y = 2\n```\n"
    os.write(sscPath, text)
    val uri = Documents.pathToUri(sscPath)

    // Open the file first.
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      )
    ))
    assert(docs.get(uri).isDefined, "expected doc to be open")

    // Delete the file.
    os.remove(sscPath)

    // Notify server of file deletion (FileChangeType.Deleted = 3).
    h.didChangeWatchedFiles(ujson.Obj(
      "changes" -> ujson.Arr(
        ujson.Obj("uri" -> uri, "type" -> 3)
      )
    ))

    // Document should be removed from the store.
    assert(docs.get(uri).isEmpty, "expected doc to be removed from store after delete")
  }

  test("didChangeWatchedFiles: non-.ssc file changes produce no notifications") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    // A non-.ssc URI should be ignored.
    val notifications = h.didChangeWatchedFiles(ujson.Obj(
      "changes" -> ujson.Arr(
        ujson.Obj("uri" -> "file:///tmp/some-file.json", "type" -> 1)
      )
    ))
    assert(notifications.isEmpty,
      s"expected no notifications for non-.ssc file, got: $notifications")
  }

  test("didChangeWatchedFiles: empty changes list produces no notifications") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val notifications = h.didChangeWatchedFiles(ujson.Obj(
      "changes" -> ujson.Arr()
    ))
    assert(notifications.isEmpty, "expected no notifications for empty changes list")
  }
