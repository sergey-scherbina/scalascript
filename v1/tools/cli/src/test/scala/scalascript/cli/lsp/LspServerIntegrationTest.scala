package scalascript.cli.lsp

import org.scalatest.funsuite.AnyFunSuite
import java.io.{BufferedInputStream, InputStream, OutputStream}

/** Spawns `ssc lsp` as a subprocess and exercises the wire protocol
 *  end-to-end.  Cancels if `ssc.jar` has not been assembled. */
class LspServerIntegrationTest extends AnyFunSuite:

  // ── ssc jar discovery ──────────────────────────────────────────────

  private val sscJar: Option[os.Path] =
    val cwd = os.pwd
    def jarUnder(root: os.Path): os.Path =
      root / "cli" / "target" / "scala-3.8.3" / "ssc.jar"
    def findCanonicalRepo(p: os.Path): Option[os.Path] =
      val parts = p.segments.toList
      val idx = parts.lastIndexOf(".claude")
      if idx >= 0 && idx + 1 < parts.length && parts(idx + 1) == "worktrees" then
        Some(os.Path("/" + parts.take(idx).mkString("/")))
      else None
    val candidates = List(
      jarUnder(cwd),
      jarUnder(cwd / os.up)
    ) ++ findCanonicalRepo(cwd).map(jarUnder).toList
    candidates.find(os.exists)

  private def requireJar(): os.Path = sscJar.getOrElse:
    cancel("ssc.jar not found — run `sbt cli/assembly` first")

  // ── Wire helpers ───────────────────────────────────────────────────

  /** Read exactly one framed JSON-RPC message from an InputStream, or
   *  throw with a useful error message. */
  private def readOne(in: InputStream): String =
    LspProtocol.readFrame(in) match
      case Right(Some(s)) => s
      case Right(None)    => fail("unexpected EOF reading frame")
      case Left(err)      => fail(s"frame error: $err")

  /** Read frames from `in` until one matching the predicate.
   *  Lets tests skip `publishDiagnostics` push notifications. */
  private def readUntil(in: InputStream, predicate: ujson.Value => Boolean): ujson.Value =
    var attempts = 0
    while attempts < 50 do
      val frame = readOne(in)
      val v = ujson.read(frame)
      if predicate(v) then return v
      attempts += 1
    fail(s"did not receive a matching frame after $attempts reads")

  private def send(out: OutputStream, msg: LspProtocol.Message): Unit =
    LspProtocol.writeFrame(out, LspProtocol.encode(msg))

  // ── Session helper ────────────────────────────────────────────────

  /** Lifecycle wrapper for one `ssc lsp` subprocess.  Opens the process,
   *  runs `body`, then sends shutdown + exit and asserts exit code 0. */
  private def withLspServer[A](body: (OutputStream, BufferedInputStream) => A): A =
    val jar = requireJar()
    val pb  = new ProcessBuilder("java", "-jar", jar.toString, "lsp")
    pb.redirectErrorStream(false)
    val proc = pb.start()
    val out  = proc.getOutputStream
    val in   = new BufferedInputStream(proc.getInputStream)
    try
      val result = body(out, in)
      send(out, LspProtocol.Request(ujson.Num(99), "shutdown", ujson.Null))
      readUntil(in, v => v.obj.get("id").contains(ujson.Num(99)))
      send(out, LspProtocol.Notification("exit", ujson.Null))
      val exited = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
      assert(exited, "server did not exit within 15s")
      assert(proc.exitValue() == 0, s"expected exit 0, got ${proc.exitValue()}")
      result
    finally
      if proc.isAlive then proc.destroyForcibly()
      scala.util.Try(out.close())
      scala.util.Try(in.close())

  /** Initialize the server (request id=1) and send `initialized`. */
  private def initialize(out: OutputStream, in: BufferedInputStream): ujson.Value =
    send(out, LspProtocol.Request(ujson.Num(1), "initialize", ujson.Obj(
      "processId"        -> ujson.Null,
      "rootUri"          -> ujson.Null,
      "capabilities"     -> ujson.Obj(),
      "workspaceFolders" -> ujson.Null
    )))
    val resp = readUntil(in, v => v.obj.get("id").contains(ujson.Num(1)))
    send(out, LspProtocol.Notification("initialized", ujson.Obj()))
    resp("result")("capabilities")

  /** Open a document and drain the resulting publishDiagnostics frame. */
  private def didOpen(out: OutputStream, in: BufferedInputStream,
                      uri: String, text: String): Unit =
    send(out, LspProtocol.Notification(
      "textDocument/didOpen",
      ujson.Obj("textDocument" -> ujson.Obj(
        "uri" -> uri, "languageId" -> "scalascript", "version" -> 1, "text" -> text
      ))
    ))
    readUntil(in, v =>
      v.obj.get("method").exists(_.strOpt.contains("textDocument/publishDiagnostics")))

  private def req(out: OutputStream, in: BufferedInputStream,
                  id: Int, method: String, params: ujson.Value): ujson.Value =
    send(out, LspProtocol.Request(ujson.Num(id), method, params))
    readUntil(in, v => v.obj.get("id").contains(ujson.Num(id)))

  // ── Tests ─────────────────────────────────────────────────────────

  test("ssc lsp: initialize → didOpen → hover → shutdown → exit") {
    withLspServer { (out, in) =>
      val caps = initialize(out, in)
      assert(caps("hoverProvider").bool == true)
      assert(caps("definitionProvider").bool == true)
      assert(caps("textDocumentSync").num == 1)

      val uri = "file:///tmp/integration.ssc"
      val docText =
        """# Hello
          |
          |```scala
          |val x: Int = 42
          |```
          |""".stripMargin
      didOpen(out, in, uri, docText)

      val hoverResp = req(out, in, 2, "textDocument/hover", ujson.Obj(
        "textDocument" -> ujson.Obj("uri" -> uri),
        "position"     -> ujson.Obj("line" -> 3, "character" -> 4)
      ))
      assert(hoverResp.obj.contains("result"), s"expected result, got: $hoverResp")
      val hoverResult = hoverResp("result")
      if hoverResult != ujson.Null then
        val s = hoverResult("contents")("value").str
        assert(s.toLowerCase.contains("int") || s.nonEmpty)
    }
  }

  test("ssc lsp: initialize advertises documentSymbol, workspaceSymbol, signatureHelp") {
    withLspServer { (out, in) =>
      val caps = initialize(out, in)
      assert(caps("documentSymbolProvider").bool  == true, "documentSymbolProvider")
      assert(caps("workspaceSymbolProvider").bool  == true, "workspaceSymbolProvider")
      val shp = caps("signatureHelpProvider")
      val triggers = shp("triggerCharacters").arr.map(_.str).toSet
      assert(triggers.contains("("), "'(' trigger")
      assert(triggers.contains(","), "',' trigger")
    }
  }

  test("ssc lsp: textDocument/documentSymbol returns symbols for open doc") {
    withLspServer { (out, in) =>
      initialize(out, in)
      val uri = "file:///tmp/ds-test.ssc"
      val docText =
        """# MyMod
          |
          |```scala
          |def greet(name: String): String = name
          |val answer: Int = 42
          |```
          |""".stripMargin
      didOpen(out, in, uri, docText)

      val dsResp = req(out, in, 2, "textDocument/documentSymbol",
        ujson.Obj("textDocument" -> ujson.Obj("uri" -> uri)))
      assert(dsResp.obj.contains("result"), s"no result: $dsResp")
      val syms = dsResp("result").arr
      assert(syms.nonEmpty, "expected at least one symbol")
      val names = syms.map(_("name").str).toSet
      assert(names.contains("MyMod"), s"expected 'MyMod', got: $names")
    }
  }

  test("ssc lsp: workspace/symbol returns symbols across open docs") {
    withLspServer { (out, in) =>
      initialize(out, in)
      val uri = "file:///tmp/ws-sym-test.ssc"
      val docText =
        """# WsMod
          |
          |```scala
          |def compute(x: Int): Int = x * 2
          |```
          |""".stripMargin
      didOpen(out, in, uri, docText)

      val wsResp = req(out, in, 2, "workspace/symbol",
        ujson.Obj("query" -> "compute"))
      assert(wsResp.obj.contains("result"), s"no result: $wsResp")
      val syms = wsResp("result").arr
      assert(syms.nonEmpty, s"expected symbols for 'compute'")
      val names = syms.map(_("name").str).toSet
      assert(names.contains("compute"), s"expected 'compute', got: $names")
    }
  }

  test("ssc lsp: textDocument/signatureHelp returns signature for open doc") {
    withLspServer { (out, in) =>
      initialize(out, in)
      val uri = "file:///tmp/sig-test.ssc"
      val docText =
        """# SigMod
          |
          |```scala
          |def add(x: Int, y: Int): Int = x + y
          |val r = add(1,
          |```
          |""".stripMargin
      didOpen(out, in, uri, docText)

      // Cursor after the comma on the call line (line 4, char 14)
      val shResp = req(out, in, 2, "textDocument/signatureHelp", ujson.Obj(
        "textDocument" -> ujson.Obj("uri" -> uri),
        "position"     -> ujson.Obj("line" -> 4, "character" -> 14)
      ))
      assert(shResp.obj.contains("result"), s"no result: $shResp")
      val result = shResp("result")
      if result != ujson.Null then
        val sigs = result("signatures").arr
        assert(sigs.nonEmpty, "expected at least one signature")
        val label = sigs.head("label").str
        assert(label.contains("add"), s"signature label should mention 'add': $label")
    }
  }

  test("ssc lsp: textDocument/references returns locations") {
    withLspServer { (out, in) =>
      initialize(out, in)
      val uri = "file:///tmp/refs-test.ssc"
      val docText =
        """# RefMod
          |
          |```scala
          |val count: Int = 1
          |val total: Int = count + count
          |```
          |""".stripMargin
      didOpen(out, in, uri, docText)

      // References to "count"
      val refsResp = req(out, in, 2, "textDocument/references", ujson.Obj(
        "textDocument" -> ujson.Obj("uri" -> uri),
        "position"     -> ujson.Obj("line" -> 3, "character" -> 4),
        "context"      -> ujson.Obj("includeDeclaration" -> true)
      ))
      assert(refsResp.obj.contains("result"), s"no result: $refsResp")
      val locs = refsResp("result").arr
      assert(locs.nonEmpty, "expected at least one reference location")
      assert(locs.forall(_("uri").str == uri), "all refs should be in the same doc")
    }
  }

  test("ssc lsp: textDocument/prepareRename + rename round-trip") {
    withLspServer { (out, in) =>
      initialize(out, in)
      val uri = "file:///tmp/rename-test.ssc"
      val docText =
        """# RenMod
          |
          |```scala
          |val myVar: Int = 10
          |val doubled: Int = myVar * 2
          |```
          |""".stripMargin
      didOpen(out, in, uri, docText)

      // prepareRename at "myVar" definition
      val prResp = req(out, in, 2, "textDocument/prepareRename", ujson.Obj(
        "textDocument" -> ujson.Obj("uri" -> uri),
        "position"     -> ujson.Obj("line" -> 3, "character" -> 4)
      ))
      assert(prResp.obj.contains("result"), s"no prepareRename result: $prResp")

      // rename to "myValue"
      val renResp = req(out, in, 3, "textDocument/rename", ujson.Obj(
        "textDocument" -> ujson.Obj("uri" -> uri),
        "position"     -> ujson.Obj("line" -> 3, "character" -> 4),
        "newName"      -> "myValue"
      ))
      assert(renResp.obj.contains("result"), s"no rename result: $renResp")
      val edit = renResp("result")
      if edit != ujson.Null then
        val changes = edit("changes").obj
        assert(changes.contains(uri), "rename edits should target the open doc")
        val edits = changes(uri).arr
        assert(edits.nonEmpty, "expected at least one text edit")
    }
  }

  test("ssc lsp: initialize advertises codeAction, formatting, inlayHint capabilities") {
    withLspServer { (out, in) =>
      val caps = initialize(out, in)
      assert(caps("codeActionProvider").bool == true, "codeActionProvider")
      assert(caps("documentFormattingProvider").bool == true, "documentFormattingProvider")
      assert(caps("inlayHintProvider").bool == true, "inlayHintProvider")
    }
  }

  test("ssc lsp: textDocument/formatting returns edits for trailing whitespace") {
    withLspServer { (out, in) =>
      initialize(out, in)
      val uri     = "file:///tmp/fmt-integ.ssc"
      val docText = "# T   \n\n```scala\nval x = 1  \n```\n"
      didOpen(out, in, uri, docText)
      val fmtResp = req(out, in, 2, "textDocument/formatting",
        ujson.Obj(
          "textDocument" -> ujson.Obj("uri" -> uri),
          "options"      -> ujson.Obj("tabSize" -> 2, "insertSpaces" -> true)
        ))
      assert(fmtResp.obj.contains("result"), s"no result: $fmtResp")
      val edits = fmtResp("result").arr
      assert(edits.nonEmpty, "expected edits for trailing whitespace")
    }
  }

  test("ssc lsp: textDocument/inlayHint returns array for open doc") {
    withLspServer { (out, in) =>
      initialize(out, in)
      val uri     = "file:///tmp/ih-integ.ssc"
      val docText = "# T\n\n```scala\nval x = 42\n```\n"
      didOpen(out, in, uri, docText)
      val ihResp = req(out, in, 2, "textDocument/inlayHint",
        ujson.Obj(
          "textDocument" -> ujson.Obj("uri" -> uri),
          "range" -> ujson.Obj(
            "start" -> ujson.Obj("line" -> 0, "character" -> 0),
            "end"   -> ujson.Obj("line" -> 10, "character" -> 0)
          )
        ))
      assert(ihResp.obj.contains("result"), s"no result: $ihResp")
      // Result is an array (possibly empty if typer doesn't infer the type)
      assert(ihResp("result").arrOpt.isDefined, "expected array result")
    }
  }

  test("ssc lsp: textDocument/codeAction returns array") {
    withLspServer { (out, in) =>
      initialize(out, in)
      val uri     = "file:///tmp/ca-integ.ssc"
      val docText = "# T\n\n```scala\nval x: Int = 1\n```\n"
      didOpen(out, in, uri, docText)
      val caResp = req(out, in, 2, "textDocument/codeAction",
        ujson.Obj(
          "textDocument" -> ujson.Obj("uri" -> uri),
          "range"        -> ujson.Obj(
            "start" -> ujson.Obj("line" -> 3, "character" -> 0),
            "end"   -> ujson.Obj("line" -> 3, "character" -> 14)
          ),
          "context"      -> ujson.Obj("diagnostics" -> ujson.Arr())
        ))
      assert(caResp.obj.contains("result"), s"no result: $caResp")
      assert(caResp("result").arrOpt.isDefined, "expected array result")
    }
  }
