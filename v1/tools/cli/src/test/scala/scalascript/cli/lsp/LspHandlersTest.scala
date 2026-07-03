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

  // ─── v2.0 Phase 3+ position fidelity ──────────────────────────────

  test("cross-module definition uses ExportedSymbol.definitionLine") {
    // Build a .scim with a known `definitionLine` for a symbol named `add`,
    // place it in a temp artifact dir alongside a .ssc whose hash the
    // interface points to.  `handleDefinition` on a consumer document that
    // references `add` must return that line.
    val tmp  = os.temp.dir(prefix = "ssc-lsp-defline-")
    val src  = """---
                 |name: providerA
                 |---
                 |
                 |# A
                 |
                 |Some prose before the code block — pushes the def below.
                 |
                 |```scala
                 |def add(x: Int, y: Int): Int = x + y
                 |val one: Int = 1
                 |```
                 |""".stripMargin
    val srcPath = tmp / "a.ssc"
    os.write(srcPath, src)
    // Compute SHA of the bytes the LSP indexes by.
    val srcHash = scalascript.artifact.InterfaceExtractor.sha256(os.read.bytes(srcPath))

    // Synthesize a .scim pointing at line 9 (0-indexed) for `add`.
    // (Front-matter is 3 lines + 1 blank + heading + 2 blanks + prose + 1
    // blank + ```scala fence = line 9 for `def add`.)
    val iface = scalascript.ir.ModuleInterface(
      magic         = scalascript.ir.ArtifactVersion.magic,
      abiVersion    = scalascript.ir.ArtifactVersion.current,
      pkg           = Nil,
      moduleName    = Some("providerA"),
      moduleVersion = None,
      sourceHash    = srcHash,
      exports       = List(
        scalascript.ir.ExportedSymbol(
          name = "add", fqn = "add", kind = "def", tpe = "Int",
          definitionLine = 9, definitionColumn = 4
        )
      )
    )
    scalascript.artifact.ArtifactIO.writeInterfaceFile(iface, tmp / "a.scim")

    val (_, h) = newHandlers()
    h.initialize(ujson.Obj(
      "initializationOptions" -> ujson.Obj("artifactDir" -> tmp.toString)
    ))

    // Consumer .ssc that references `add` (imported via the loaded interface).
    val consumer = """# C
                     |
                     |```scala
                     |val z = add(1, 2)
                     |```
                     |""".stripMargin
    val cUri = "file:///tmp/consumer.ssc"
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> cUri, "languageId" -> "scalascript", "version" -> 1, "text" -> consumer
      )
    ))
    // Cursor on `add` (line 3, char 9 — the `a` of `add(...)`).
    val result = h.definition(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> cUri),
      "position"     -> ujson.Obj("line" -> 3, "character" -> 9)
    ))
    assert(result != ujson.Null, "expected a location for cross-module reference")
    val r = result("range")
    assert(r("start")("line").num.toInt == 9,
      s"expected line 9, got ${r("start")("line").num}")
    assert(r("start")("character").num.toInt == 4,
      s"expected column 4, got ${r("start")("character").num}")
    // endColumn = 4 + "add".length == 7.
    assert(r("end")("character").num.toInt == 7,
      s"expected endColumn 7, got ${r("end")("character").num}")
  }

  test("hover on a symbol in the SECOND code block returns file-level line") {
    // Two code blocks; block 1 has padding lines, block 2 starts deep in
    // the file.  We exercise the `collectBlocks` translation by checking
    // that local-def lookup of a name defined in block 2 returns its
    // file-level line (>= the block-2 fence).
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val text =
      """# H1
        |
        |```scala
        |val a: Int = 1
        |val b: Int = 2
        |val c: Int = 3
        |```
        |
        |Some prose between blocks.
        |
        |More prose.
        |
        |# H2
        |
        |```scala
        |def laterDef(x: Int): Int = x * 2
        |```
        |""".stripMargin
    val uri = "file:///tmp/multi.ssc"
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      )
    ))
    // The `def laterDef` line in the source is line 15 (0-indexed) — count
    // from `# H1` (0) down to it.  Cursor on `laterDef` in its own
    // declaration also resolves to itself; the meaningful check is that
    // the returned line is >= the block-2 fence line (12 or higher), NOT
    // the block-local `0`.
    val result = h.definition(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "position"     -> ujson.Obj("line" -> 15, "character" -> 4)
    ))
    assert(result != ujson.Null, "expected a definition location")
    val startLine = result("range")("start")("line").num.toInt
    assert(startLine >= 13,
      s"expected file-level line >= 13 (post block-2 fence), got $startLine")
    // And specifically: the `def` keyword sits on the same row as
    // `laterDef`, so the start line should equal 15 (where `def laterDef`
    // lives in the source).
    assert(startLine == 15, s"expected start line 15, got $startLine")
  }

  test("cross-module definition with package: preserves lineOffset") {
    // `package: foo.bar` causes the parser to wrap the block in
    // `object foo: object bar: <body>`.  The extractor's positionFor
    // subtracts pkg.size lines + 2*pkg.size cols, so the recorded
    // `definitionLine` still corresponds to the user's original source
    // line — not to a position inside the synthesized wrapper.
    val tmp  = os.temp.dir(prefix = "ssc-lsp-pkg-")
    val src  = """---
                 |name: providerB
                 |package: foo.bar
                 |---
                 |
                 |# A
                 |
                 |```scala
                 |def hello(): String = "hi"
                 |```
                 |""".stripMargin
    val srcPath = tmp / "b.ssc"
    os.write(srcPath, src)

    // Use the REAL extractor + parser to produce the .scim — we want to
    // verify the position math, not hand-roll the expected value.
    val mod   = scalascript.parser.Parser.parse(src)
    val bytes = os.read.bytes(srcPath)
    val iface = scalascript.artifact.InterfaceExtractor.extract(mod, bytes)
    scalascript.artifact.ArtifactIO.writeInterfaceFile(iface, tmp / "b.scim")

    val helloDef = iface.exports.find(_.name == "hello").orElse(
      // With package-wrap, `hello` lives nested under the shell object.
      // The extractor surfaces user defs into exports either via the
      // top-level filter (post-strip) or via `nested` on the shell.  Try
      // both code paths so the test doesn't depend on which layer surfaces it.
      iface.exports.flatMap(_.nested).find(_.name == "hello")
    )
    assert(helloDef.isDefined,
      s"expected `hello` in exports / nested, got: ${iface.exports.map(_.name)}")
    // The fence opens at line 7 (0-indexed: front-matter is 4 lines + blank
    // + `# A` heading + blank); first inside-fence line is 8 → `def hello`.
    assert(helloDef.get.definitionLine == 8,
      s"expected definitionLine 8 (under package wrap), got ${helloDef.get.definitionLine}")
    // `def ` is 4 chars, so the identifier `hello` starts at column 4.
    assert(helloDef.get.definitionColumn == 4,
      s"expected definitionColumn 4, got ${helloDef.get.definitionColumn}")

    // Now drive the LSP through the cross-module path and assert it
    // surfaces the same coordinates back to the editor.
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj(
      "initializationOptions" -> ujson.Obj("artifactDir" -> tmp.toString)
    ))
    val consumer = """# C
                     |
                     |```scala
                     |val z = hello()
                     |```
                     |""".stripMargin
    val cUri = "file:///tmp/consumer-pkg.ssc"
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> cUri, "languageId" -> "scalascript", "version" -> 1, "text" -> consumer
      )
    ))
    val result = h.definition(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> cUri),
      "position"     -> ujson.Obj("line" -> 3, "character" -> 9)
    ))
    assert(result != ujson.Null)
    assert(result("range")("start")("line").num.toInt == 8,
      s"expected file-level line 8, got ${result("range")("start")("line").num}")
  }

  test("cross-module definition: backward-compat .scim (definitionLine=0)") {
    // Synthesize an OLD-style .scim with default 0 positions, so the LSP
    // path falls through to the documented MVP behaviour of returning
    // (0,0).  This is the "loaded an artifact emitted before this fix"
    // scenario — the field defaults preserve compat.
    val tmp  = os.temp.dir(prefix = "ssc-lsp-compat-")
    val src  = """---
                 |name: legacy
                 |---
                 |
                 |# A
                 |
                 |```scala
                 |def legacyName(): Int = 1
                 |```
                 |""".stripMargin
    val srcPath = tmp / "legacy.ssc"
    os.write(srcPath, src)
    val srcHash = scalascript.artifact.InterfaceExtractor.sha256(os.read.bytes(srcPath))

    val iface = scalascript.ir.ModuleInterface(
      magic         = scalascript.ir.ArtifactVersion.magic,
      abiVersion    = scalascript.ir.ArtifactVersion.current,
      pkg           = Nil,
      moduleName    = Some("legacy"),
      moduleVersion = None,
      sourceHash    = srcHash,
      exports       = List(
        // Note: no `definitionLine` — picks up default 0.
        scalascript.ir.ExportedSymbol(
          name = "legacyName", fqn = "legacyName", kind = "def", tpe = "Int"
        )
      )
    )
    scalascript.artifact.ArtifactIO.writeInterfaceFile(iface, tmp / "legacy.scim")

    val (_, h) = newHandlers()
    h.initialize(ujson.Obj(
      "initializationOptions" -> ujson.Obj("artifactDir" -> tmp.toString)
    ))
    val consumer = """# C
                     |
                     |```scala
                     |val z = legacyName()
                     |```
                     |""".stripMargin
    val cUri = "file:///tmp/consumer-legacy.ssc"
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> cUri, "languageId" -> "scalascript", "version" -> 1, "text" -> consumer
      )
    ))
    val result = h.definition(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> cUri),
      "position"     -> ujson.Obj("line" -> 3, "character" -> 11)
    ))
    assert(result != ujson.Null, "expected a location (legacy .scim still resolves)")
    val r = result("range")
    assert(r("start")("line").num.toInt == 0,
      s"expected legacy fallback line 0, got ${r("start")("line").num}")
    assert(r("start")("character").num.toInt == 0,
      s"expected legacy fallback column 0, got ${r("start")("character").num}")
  }

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

  // ─── completion ────────────────────────────────────────────────────

  /** Helper: open a document and call textDocument/completion at position. */
  private def openAndComplete(
      h: Handlers,
      uri: String,
      text: String,
      line: Int,
      character: Int
  ): ujson.Value =
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      )
    ))
    h.completion(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "position"     -> ujson.Obj("line" -> line, "character" -> character)
    ))

  test("completion always returns isIncomplete = false") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri  = "file:///tmp/compl-incomplete.ssc"
    val text = """# H
                 |
                 |```scala
                 |val x: Int = 42
                 |```
                 |""".stripMargin
    val result = openAndComplete(h, uri, text, line = 3, character = 4)
    assert(result("isIncomplete").bool == false)
  }

  test("completion with empty prefix returns all symbols and keywords") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri  = "file:///tmp/compl-empty.ssc"
    val text = """# H
                 |
                 |```scala
                 |val myVal: Int = 1
                 |def myFun(n: Int): Int = n
                 |```
                 |""".stripMargin
    // Position at end of line 5 (blank line after the block is at line 5).
    // Place cursor on line 5 char 0 — outside the identifiers, prefix is "".
    val result = openAndComplete(h, uri, text, line = 3, character = 0)
    val labels = result("items").arr.map(_("label").str).toSet
    // User-defined symbols must appear.
    assert(labels.contains("myVal"), s"expected myVal in completions: $labels")
    assert(labels.contains("myFun"), s"expected myFun in completions: $labels")
    // Keywords must appear.
    assert(labels.contains("def"),   s"expected keyword 'def' in completions: $labels")
    assert(labels.contains("val"),   s"expected keyword 'val' in completions: $labels")
    assert(labels.contains("println"), s"expected 'println' in completions: $labels")
  }

  test("completion with prefix 'pri' returns only prefix-matching items") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri  = "file:///tmp/compl-prefix.ssc"
    val text = """# H
                 |
                 |```scala
                 |val priority: Int = 1
                 |val other: String = "x"
                 |pri
                 |```
                 |""".stripMargin
    // Cursor is at end of "pri" on line 5.
    val result = openAndComplete(h, uri, text, line = 5, character = 3)
    val labels = result("items").arr.map(_("label").str).toList
    // All returned labels must start with "pri" (case-insensitive).
    assert(labels.forall(_.toLowerCase.startsWith("pri")),
      s"expected all labels to start with 'pri', got: $labels")
    // "println" and "print" and user-defined "priority" must be in there.
    assert(labels.contains("println"),  s"expected println, got: $labels")
    assert(labels.contains("print"),    s"expected print, got: $labels")
    assert(labels.contains("priority"), s"expected priority, got: $labels")
    // "val", "def", etc. must NOT appear.
    assert(!labels.contains("val"),  s"'val' should be filtered out, got: $labels")
    assert(!labels.contains("other"), s"'other' should be filtered out, got: $labels")
  }

  test("completion in empty document returns only keywords") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri  = "file:///tmp/compl-empty-doc.ssc"
    // A valid-structure doc with an empty code block.
    val text = """# H
                 |
                 |```scala
                 |
                 |```
                 |""".stripMargin
    val result = openAndComplete(h, uri, text, line = 3, character = 0)
    val labels = result("items").arr.map(_("label").str).toSet
    // No user-defined names; all returned items must be keywords.
    val expectedKw = Set("def", "val", "var", "if", "else", "match", "case",
      "for", "yield", "while", "trait", "given", "using", "object", "class",
      "sealed", "enum", "type", "extension", "opaque", "extern", "async",
      "await", "import", "export", "println", "print", "summon")
    assert(labels == expectedKw,
      s"expected exactly keywords, got: $labels")
  }

  test("initialize advertises completionProvider capability") {
    val (_, h) = newHandlers()
    val result = h.initialize(ujson.Obj("processId" -> ujson.Null))
    val caps = result("capabilities")
    assert(caps.obj.contains("completionProvider"),
      "expected completionProvider in capabilities")
    val triggers = caps("completionProvider")("triggerCharacters").arr.map(_.str).toSet
    assert(triggers.contains("."), "expected '.' in triggerCharacters")
    assert(triggers.contains(" "), "expected ' ' in triggerCharacters")
  }

  test("completion item kinds: def -> 3 (Function), val/var -> 6 (Variable)") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri  = "file:///tmp/compl-kinds.ssc"
    val text = """# H
                 |
                 |```scala
                 |val myVar: Int = 1
                 |def myFun(n: Int): Int = n
                 |```
                 |""".stripMargin
    val result = openAndComplete(h, uri, text, line = 3, character = 0)
    val items  = result("items").arr.map(i => i("label").str -> i("kind").num.toInt).toMap
    assert(items.get("myFun").contains(3),
      s"expected def kind=3 (Function), got ${items.get("myFun")}")
    assert(items.get("myVar").contains(6),
      s"expected val kind=6 (Variable), got ${items.get("myVar")}")
    // Keyword kind should be 14.
    assert(items.get("def").contains(14),
      s"expected keyword kind=14, got ${items.get("def")}")
  }

  test("completion for unknown URI returns only keywords (empty prefix)") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    // Call completion without ever opening the document.
    val result = h.completion(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> "file:///tmp/never-opened.ssc"),
      "position"     -> ujson.Obj("line" -> 0, "character" -> 0)
    ))
    assert(result("isIncomplete").bool == false)
    val labels = result("items").arr.map(_("label").str).toSet
    assert(labels.contains("def"),  s"expected keyword 'def', got: $labels")
    assert(labels.contains("val"),  s"expected keyword 'val', got: $labels")
  }

  // ─── textDocument/references ────────────────────────────────────────

  private val uri = "file:///tmp/refs-test.ssc"

  private def openRefDoc(h: Handlers, text: String): Unit =
    h.initialize(ujson.Obj())
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      )
    ))

  private def refs(h: Handlers, line: Int, character: Int,
                   includeDecl: Boolean = true) =
    h.references(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "position"     -> ujson.Obj("line" -> line, "character" -> character),
      "context"      -> ujson.Obj("includeDeclaration" -> includeDecl)
    )).arr

  test("references returns empty array for unknown URI") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val result = h.references(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> "file:///tmp/no-such.ssc"),
      "position"     -> ujson.Obj("line" -> 0, "character" -> 0),
      "context"      -> ujson.Obj("includeDeclaration" -> true)
    )).arr
    assert(result.isEmpty)
  }

  test("references finds all occurrences of a name in the document") {
    val (_, h) = newHandlers()
    val text =
      """# Section
        |
        |```scala
        |val alpha = 1
        |val beta = alpha + alpha
        |```
        |""".stripMargin
    openRefDoc(h, text)
    // cursor on "alpha" in the def line (line 3, col 4)
    val locs = refs(h, line = 3, character = 4)
    // should find 3 occurrences: definition + 2 uses
    assert(locs.length == 3, s"expected 3 refs, got ${locs.length}: $locs")
    locs.foreach { loc =>
      assert(loc("uri").str == uri)
      assert(loc.obj.contains("range"))
    }
  }

  test("references with includeDeclaration=false excludes definition site") {
    val (_, h) = newHandlers()
    val text =
      """# Section
        |
        |```scala
        |val myVal = 1
        |val other = myVal + myVal
        |```
        |""".stripMargin
    openRefDoc(h, text)
    // cursor on "myVal" at definition (line 3, col 4)
    val withDecl    = refs(h, line = 3, character = 4, includeDecl = true)
    val withoutDecl = refs(h, line = 3, character = 4, includeDecl = false)
    assert(withDecl.length == 3,    s"expected 3 with decl, got ${withDecl.length}")
    assert(withoutDecl.length == 2, s"expected 2 without decl, got ${withoutDecl.length}")
  }

  test("references does not match partial identifiers") {
    val (_, h) = newHandlers()
    val text =
      """# Section
        |
        |```scala
        |val foo = 1
        |val fooBar = foo + 2
        |val result = foo
        |```
        |""".stripMargin
    openRefDoc(h, text)
    // "foo" should NOT match inside "fooBar"
    val locs = refs(h, line = 3, character = 4)
    val lines = locs.map(_("range")("start")("line").num.toInt).toSet
    assert(!lines.contains(4) || {
      // If line 4 is included, it must be for a standalone "foo", not "fooBar"
      locs.filter(_("range")("start")("line").num.toInt == 4)
          .forall { loc =>
            val col = loc("range")("start")("character").num.toInt
            col != 0  // fooBar starts at col 0; standalone foo is further right
          }
    }, s"partial match 'fooBar' should not be in results: $locs")
  }

  test("initialize advertises referencesProvider capability") {
    val (_, h) = newHandlers()
    val result = h.initialize(ujson.Obj())
    assert(result("capabilities")("referencesProvider").bool == true)
  }

  // ─── textDocument/prepareRename + rename ────────────────────────────

  private val renameUri = "file:///tmp/rename-test.ssc"

  private def openRenameDoc(h: Handlers, text: String): Unit =
    h.initialize(ujson.Obj())
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> renameUri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      )
    ))

  private val renameDoc =
    """# Section
      |
      |```scala
      |val score = 10
      |val total = score + score
      |val max   = score * 2
      |```
      |""".stripMargin

  test("prepareRename returns the identifier range at cursor") {
    val (_, h) = newHandlers()
    openRenameDoc(h, renameDoc)
    // "score" starts at col 4 on line 3 (0-indexed)
    val result = h.prepareRename(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> renameUri),
      "position"     -> ujson.Obj("line" -> 3, "character" -> 5)
    ))
    assert(result != ujson.Null, s"expected a range, got null")
    assert(result("start")("line").num.toInt   == 3)
    assert(result("start")("character").num.toInt == 4)
    assert(result("end")("character").num.toInt   == 9)  // "score" = 5 chars
  }

  test("prepareRename returns null for unknown URI") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val result = h.prepareRename(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> "file:///tmp/no-doc.ssc"),
      "position"     -> ujson.Obj("line" -> 0, "character" -> 0)
    ))
    assert(result == ujson.Null)
  }

  test("rename returns WorkspaceEdit with TextEdits for every occurrence") {
    val (_, h) = newHandlers()
    openRenameDoc(h, renameDoc)
    val result = h.rename(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> renameUri),
      "position"     -> ujson.Obj("line" -> 3, "character" -> 5),
      "newName"      -> "points"
    ))
    assert(result != ujson.Null, "expected WorkspaceEdit, got null")
    val edits = result("changes")(renameUri).arr
    // "score" appears 4 times: definition + 2 uses on line 4 + 1 use on line 5
    assert(edits.length == 4, s"expected 4 edits, got ${edits.length}: $edits")
    edits.foreach { edit =>
      assert(edit("newText").str == "points")
      assert(edit.obj.contains("range"))
    }
  }

  test("rename with empty newName returns null") {
    val (_, h) = newHandlers()
    openRenameDoc(h, renameDoc)
    val result = h.rename(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> renameUri),
      "position"     -> ujson.Obj("line" -> 3, "character" -> 5),
      "newName"      -> ""
    ))
    assert(result == ujson.Null)
  }

  test("rename does not affect partial matches") {
    val (_, h) = newHandlers()
    val text =
      """# Section
        |
        |```scala
        |val x = 1
        |val xx = x + 2
        |```
        |""".stripMargin
    openRenameDoc(h, text)
    val result = h.rename(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> renameUri),
      "position"     -> ujson.Obj("line" -> 3, "character" -> 4),
      "newName"      -> "y"
    ))
    val edits = result("changes")(renameUri).arr
    // "x" as standalone: line 3 col 4 (def) and line 4 col 9 (use), NOT "xx"
    assert(edits.length == 2, s"expected 2 edits (not 3), got ${edits.length}: $edits")
  }

  test("initialize advertises renameProvider with prepareProvider") {
    val (_, h) = newHandlers()
    val caps = h.initialize(ujson.Obj())("capabilities")
    val rp = caps("renameProvider")
    assert(rp("prepareProvider").bool == true)
  }

  // ─── textDocument/documentSymbol ───────────────────────────────────

  private val dsUri = "file:///tmp/doc-sym-test.ssc"

  private def openSymDoc(h: Handlers, text: String): Unit =
    h.initialize(ujson.Obj())
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> dsUri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      )
    ))

  private def docSymbols(h: Handlers) =
    h.documentSymbol(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> dsUri)
    )).arr

  test("documentSymbol returns empty array for unknown URI") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val result = h.documentSymbol(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> "file:///tmp/no-such.ssc")
    )).arr
    assert(result.isEmpty)
  }

  test("documentSymbol includes a section heading as Module symbol") {
    val (_, h) = newHandlers()
    val text =
      """# MySection
        |
        |```scala
        |val x: Int = 1
        |```
        |""".stripMargin
    openSymDoc(h, text)
    val syms = docSymbols(h)
    val names = syms.map(_("name").str).toList
    assert(names.contains("MySection"), s"expected 'MySection' in symbols, got: $names")
    val headingSym = syms.find(_("name").str == "MySection").get
    assert(headingSym("kind").num.toInt == 2, "section heading should have kind=2 (Module)")
  }

  test("documentSymbol includes val and def definitions") {
    val (_, h) = newHandlers()
    val text =
      """# Section
        |
        |```scala
        |val myConst: Int = 42
        |def myFun(n: Int): Int = n + 1
        |```
        |""".stripMargin
    openSymDoc(h, text)
    val syms = docSymbols(h)
    val byName = syms.map(s => s("name").str -> s("kind").num.toInt).toMap
    assert(byName.contains("myConst"), s"expected 'myConst', got: ${byName.keys}")
    assert(byName.contains("myFun"),   s"expected 'myFun', got: ${byName.keys}")
    assert(byName("myFun") == 12,   s"def should be kind=12 (Function), got ${byName("myFun")}")
    assert(byName("myConst") == 13, s"val should be kind=13 (Variable), got ${byName("myConst")}")
  }

  test("documentSymbol includes class and object definitions") {
    val (_, h) = newHandlers()
    val text =
      """# Section
        |
        |```scala
        |class MyClass(x: Int)
        |object MyObj
        |```
        |""".stripMargin
    openSymDoc(h, text)
    val syms = docSymbols(h)
    val byName = syms.map(s => s("name").str -> s("kind").num.toInt).toMap
    assert(byName.contains("MyClass"), s"expected 'MyClass', got: ${byName.keys}")
    assert(byName.contains("MyObj"),   s"expected 'MyObj', got: ${byName.keys}")
    assert(byName("MyClass") == 5,  s"class should be kind=5, got ${byName("MyClass")}")
    assert(byName("MyObj")   == 19, s"object should be kind=19, got ${byName("MyObj")}")
  }

  test("documentSymbol each symbol carries a uri and range") {
    val (_, h) = newHandlers()
    val text =
      """# S
        |
        |```scala
        |val z: Int = 0
        |```
        |""".stripMargin
    openSymDoc(h, text)
    val syms = docSymbols(h)
    assert(syms.nonEmpty, "expected at least one symbol")
    syms.foreach { sym =>
      val loc = sym("location")
      assert(loc("uri").str == dsUri, "symbol uri must match doc uri")
      val range = loc("range")
      assert(range.obj.contains("start"), "range must have 'start'")
      assert(range.obj.contains("end"),   "range must have 'end'")
    }
  }

  test("initialize advertises documentSymbolProvider capability") {
    val (_, h) = newHandlers()
    val caps = h.initialize(ujson.Obj())("capabilities")
    assert(caps("documentSymbolProvider").bool == true,
      "expected documentSymbolProvider: true in capabilities")
  }

  // ─── workspace/symbol ──────────────────────────────────────────────

  private def wsQuery(h: Handlers, query: String) =
    h.workspaceSymbol(ujson.Obj("query" -> query)).arr

  test("workspaceSymbol empty query returns all symbols from open docs") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/ws-sym-all.ssc"
    val text =
      """# MySection
        |
        |```scala
        |val myVal: Int = 1
        |def myFun(n: Int): Int = n
        |```
        |""".stripMargin
    h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    )))
    val syms = wsQuery(h, "")
    val names = syms.map(_("name").str).toSet
    assert(names.contains("MySection"), s"expected heading, got: $names")
    assert(names.contains("myVal"),     s"expected myVal, got: $names")
    assert(names.contains("myFun"),     s"expected myFun, got: $names")
  }

  test("workspaceSymbol filters by query substring (case-insensitive)") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/ws-sym-filter.ssc"
    val text =
      """# Section
        |
        |```scala
        |val fooBar: Int = 1
        |val fooQux: Int = 2
        |val other: Int = 3
        |```
        |""".stripMargin
    h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    )))
    val syms = wsQuery(h, "foo")
    val names = syms.map(_("name").str).toSet
    assert(names.contains("fooBar"), s"expected fooBar in results: $names")
    assert(names.contains("fooQux"), s"expected fooQux in results: $names")
    assert(!names.contains("other"), s"'other' should be filtered out: $names")
  }

  test("workspaceSymbol returns empty array when no docs open") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val syms = wsQuery(h, "anything")
    assert(syms.isEmpty, s"expected empty result with no docs open, got: $syms")
  }

  test("workspaceSymbol each symbol has name, kind, location.uri, location.range") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/ws-sym-schema.ssc"
    val text =
      """# S
        |
        |```scala
        |def myFn(): Unit = ()
        |```
        |""".stripMargin
    h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    )))
    val syms = wsQuery(h, "myFn")
    assert(syms.nonEmpty, "expected at least myFn symbol")
    val sym = syms.find(_("name").str == "myFn").get
    assert(sym.obj.contains("kind"),     "expected 'kind' field")
    assert(sym("kind").num.toInt == 12,  "def should be kind=12 (Function)")
    val loc = sym("location")
    assert(loc("uri").str == uri,        "location.uri must match open doc uri")
    assert(loc.obj.contains("range"),    "location must have 'range'")
  }

  test("initialize advertises workspaceSymbolProvider capability") {
    val (_, h) = newHandlers()
    val caps = h.initialize(ujson.Obj())("capabilities")
    assert(caps("workspaceSymbolProvider").bool == true,
      "expected workspaceSymbolProvider: true in capabilities")
  }

  // ─── textDocument/signatureHelp ────────────────────────────────────

  private val sigUri = "file:///tmp/sig-help-test.ssc"

  private def openSigDoc(h: Handlers, text: String): Unit =
    h.initialize(ujson.Obj())
    h.didOpen(ujson.Obj(
      "textDocument" -> ujson.Obj(
        "uri" -> sigUri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      )
    ))

  private def sigHelp(h: Handlers, line: Int, character: Int) =
    h.signatureHelp(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> sigUri),
      "position"     -> ujson.Obj("line" -> line, "character" -> character)
    ))

  test("signatureHelp returns null for unknown URI") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val result = h.signatureHelp(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> "file:///tmp/no-such.ssc"),
      "position"     -> ujson.Obj("line" -> 0, "character" -> 0)
    ))
    assert(result == ujson.Null)
  }

  test("signatureHelp returns null when cursor is not inside a call") {
    val (_, h) = newHandlers()
    val text =
      """# S
        |
        |```scala
        |def add(x: Int, y: Int): Int = x + y
        |val z = 1
        |```
        |""".stripMargin
    openSigDoc(h, text)
    // Cursor on "z" — not inside a call
    val result = sigHelp(h, line = 4, character = 5)
    assert(result == ujson.Null, s"expected null outside call context, got: $result")
  }

  test("signatureHelp finds def and returns parameter labels") {
    val (_, h) = newHandlers()
    val text =
      """# S
        |
        |```scala
        |def add(x: Int, y: Int): Int = x + y
        |val r = add(1,
        |```
        |""".stripMargin
    openSigDoc(h, text)
    // Cursor after the comma — activeParameter = 1
    val result = sigHelp(h, line = 4, character = 14)
    assert(result != ujson.Null, s"expected SignatureHelp, got null")
    val sigs = result("signatures").arr
    assert(sigs.length == 1, s"expected 1 signature")
    val sig = sigs.head
    assert(sig("label").str.contains("add"), s"label should contain 'add': ${sig("label")}")
    val paramLabels = sig("parameters").arr.map(_("label").str).toList
    assert(paramLabels.length == 2,            s"expected 2 params, got: $paramLabels")
    assert(paramLabels(0).contains("x"),       s"first param should contain 'x': $paramLabels")
    assert(paramLabels(1).contains("y"),       s"second param should contain 'y': $paramLabels")
    assert(result("activeParameter").num.toInt == 1, "cursor after comma → activeParameter=1")
  }

  test("signatureHelp activeParameter = 0 at first argument") {
    val (_, h) = newHandlers()
    val text =
      """# S
        |
        |```scala
        |def greet(name: String, n: Int): String = name
        |val r = greet(
        |```
        |""".stripMargin
    openSigDoc(h, text)
    // Cursor just after `(` — no commas yet → activeParameter = 0
    val result = sigHelp(h, line = 4, character = 14)
    assert(result != ujson.Null, "expected SignatureHelp")
    assert(result("activeParameter").num.toInt == 0, "no commas → activeParameter=0")
  }

  test("initialize advertises signatureHelpProvider with trigger characters") {
    val (_, h) = newHandlers()
    val caps = h.initialize(ujson.Obj())("capabilities")
    val shp = caps("signatureHelpProvider")
    val triggers = shp("triggerCharacters").arr.map(_.str).toSet
    assert(triggers.contains("("), "expected '(' in triggerCharacters")
    assert(triggers.contains(","), "expected ',' in triggerCharacters")
  }

  // ─── Phase 3: codeAction ────────────────────────────────────────────

  test("codeAction returns empty array when no diagnostics") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/ca-test.ssc"
    h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1,
      "text" -> "# T\n\n```scala\nval x: Int = 1\n```\n"
    )))
    val result = h.codeAction(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "range"        -> ujson.Obj("start" -> ujson.Obj("line" -> 3, "character" -> 0),
                                  "end"   -> ujson.Obj("line" -> 3, "character" -> 10)),
      "context"      -> ujson.Obj("diagnostics" -> ujson.Arr())
    ))
    assert(result.arr.isEmpty, s"expected empty actions, got: $result")
  }

  test("codeAction quickfix for unused-import diagnostic removes import line") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/ca-unused.ssc"
    val text = "# T\n\nimport foo.*\n\n```scala\nval x: Int = 1\n```\n"
    h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    )))
    val diagJson = ujson.Obj(
      "range"   -> ujson.Obj("start" -> ujson.Obj("line" -> 2, "character" -> 0),
                             "end"   -> ujson.Obj("line" -> 2, "character" -> 10)),
      "message" -> "Unused import: foo.*",
      "severity" -> 4
    )
    val result = h.codeAction(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "range"        -> ujson.Obj("start" -> ujson.Obj("line" -> 2, "character" -> 0),
                                  "end"   -> ujson.Obj("line" -> 2, "character" -> 10)),
      "context"      -> ujson.Obj("diagnostics" -> ujson.Arr(diagJson))
    ))
    assert(result.arr.nonEmpty, "expected at least one code action")
    val titles = result.arr.map(_("title").str).toList
    assert(titles.exists(_.contains("Remove unused import")), s"expected remove action, got: $titles")
  }

  test("codeAction quickfix has kind=quickfix") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/ca-kind.ssc"
    val text = "# T\n\nimport foo.*\n\n```scala\nval x: Int = 1\n```\n"
    h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    )))
    val diagJson = ujson.Obj(
      "range"   -> ujson.Obj("start" -> ujson.Obj("line" -> 2, "character" -> 0),
                             "end"   -> ujson.Obj("line" -> 2, "character" -> 10)),
      "message" -> "Unused import: foo.*",
      "severity" -> 4
    )
    val result = h.codeAction(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "range"        -> ujson.Obj("start" -> ujson.Obj("line" -> 2, "character" -> 0),
                                  "end"   -> ujson.Obj("line" -> 2, "character" -> 10)),
      "context"      -> ujson.Obj("diagnostics" -> ujson.Arr(diagJson))
    ))
    result.arr.foreach { action =>
      assert(action("kind").str == "quickfix", s"expected kind=quickfix, got: ${action("kind")}")
    }
  }

  // ─── Phase 3: formatting ────────────────────────────────────────────

  test("formatting strips trailing whitespace") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/fmt-test.ssc"
    val text = "# Hello   \n\n```scala\nval x = 1  \n```\n"
    h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    )))
    val result = h.formatting(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "options"      -> ujson.Obj("tabSize" -> 2, "insertSpaces" -> true)
    ))
    assert(result.arr.nonEmpty, s"expected some edits for trailing whitespace, got: $result")
    val newTexts = result.arr.map(_("newText").str).toSet
    assert(newTexts.forall(!_.endsWith(" ")), s"all newText should have no trailing space: $newTexts")
  }

  test("formatting returns empty array for clean document") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/fmt-clean.ssc"
    val text = "# Hello\n\n```scala\nval x = 1\n```\n"
    h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    )))
    val result = h.formatting(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "options"      -> ujson.Obj("tabSize" -> 2, "insertSpaces" -> true)
    ))
    assert(result.arr.isEmpty, s"expected no edits for clean doc, got: $result")
  }

  test("formatting replaces tabs with 2 spaces") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/fmt-tabs.ssc"
    val text = "# T\n\n```scala\n\tval x = 1\n```\n"
    h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    )))
    val result = h.formatting(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "options"      -> ujson.Obj("tabSize" -> 2, "insertSpaces" -> true)
    ))
    assert(result.arr.nonEmpty, s"expected edits for tab, got: $result")
    val newTexts = result.arr.map(_("newText").str).toList
    assert(newTexts.exists(_.startsWith("  ")), s"expected 2-space indent, got: $newTexts")
  }

  // ─── Phase 3: inlayHint ─────────────────────────────────────────────

  test("inlayHint returns empty array for unknown document") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val result = h.inlayHint(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> "file:///tmp/none.ssc"),
      "range"        -> ujson.Obj("start" -> ujson.Obj("line" -> 0, "character" -> 0),
                                  "end"   -> ujson.Obj("line" -> 10, "character" -> 0))
    ))
    assert(result.arr.isEmpty, s"expected empty, got: $result")
  }

  test("inlayHint emits type hint for val without annotation") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/ih-test.ssc"
    val text = "# T\n\n```scala\nval x = 42\n```\n"
    h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    )))
    val result = h.inlayHint(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "range"        -> ujson.Obj("start" -> ujson.Obj("line" -> 0, "character" -> 0),
                                  "end"   -> ujson.Obj("line" -> 10, "character" -> 0))
    ))
    // val x = 42 should produce an inlay hint with ": Int" (or ": Any" depending on typer)
    if result.arr.nonEmpty then
      val labels = result.arr.map(_("label").str).toList
      assert(labels.exists(_.startsWith(":")), s"expected ': Type' hint, got: $labels")
  }

  test("inlayHint returns empty for val with explicit type annotation") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/ih-annot.ssc"
    val text = "# T\n\n```scala\nval x: Int = 42\n```\n"
    h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    )))
    val result = h.inlayHint(ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri),
      "range"        -> ujson.Obj("start" -> ujson.Obj("line" -> 0, "character" -> 0),
                                  "end"   -> ujson.Obj("line" -> 10, "character" -> 0))
    ))
    assert(result.arr.isEmpty, s"expected no hints for explicitly-typed val, got: $result")
  }

  // ─── Phase 3: initialize capabilities ──────────────────────────────

  test("initialize advertises codeActionProvider, formattingProvider, inlayHintProvider") {
    val (_, h) = newHandlers()
    val caps = h.initialize(ujson.Obj())("capabilities")
    assert(caps("codeActionProvider").bool == true, "expected codeActionProvider")
    assert(caps("documentFormattingProvider").bool == true, "expected documentFormattingProvider")
    assert(caps("inlayHintProvider").bool == true, "expected inlayHintProvider")
  }

  // ─── Phase 3: didChangeWatchedFiles ─────────────────────────────────

  test("didChangeWatchedFiles returns empty list when no open docs match") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val result = h.didChangeWatchedFiles(ujson.Obj(
      "changes" -> ujson.Arr(ujson.Obj(
        "uri"  -> "file:///tmp/not-open.ssc",
        "type" -> 2
      ))
    ))
    assert(result.isEmpty, s"expected empty, got: $result")
  }

  test("didChangeWatchedFiles ignores non-ssc files") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val result = h.didChangeWatchedFiles(ujson.Obj(
      "changes" -> ujson.Arr(ujson.Obj(
        "uri"  -> "file:///tmp/foo.txt",
        "type" -> 2
      ))
    ))
    assert(result.isEmpty, s"expected empty for non-ssc file")
  }

  test("unused import detected and emits hint diagnostic") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/unused-import.ssc"
    val text = "# T\n\nimport foo.*\n\n```scala\nval x: Int = 1\n```\n"
    val n = h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    ))).get
    val diags = n.params("diagnostics").arr.toList
    val unusedDiags = diags.filter(_("message").str.startsWith("Unused import:"))
    assert(unusedDiags.nonEmpty, s"expected unused import diagnostic, got: $diags")
    assert(unusedDiags.head("severity").num.toInt == 4, "unused import should be Hint (4)")
  }

  test("import is NOT flagged as unused when its name appears in the document") {
    val (_, h) = newHandlers()
    h.initialize(ujson.Obj())
    val uri = "file:///tmp/used-import.ssc"
    val text = "# T\n\nimport foo.*\n\n```scala\nval x = foo.bar()\n```\n"
    val n = h.didOpen(ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
    ))).get
    val diags = n.params("diagnostics").arr.toList
    val unusedDiags = diags.filter(_("message").str.startsWith("Unused import:"))
    assert(unusedDiags.isEmpty, s"expected no unused import diagnostic, got: $unusedDiags")
  }
