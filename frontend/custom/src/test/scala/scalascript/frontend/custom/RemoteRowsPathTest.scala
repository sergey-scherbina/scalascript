package scalascript.frontend.custom

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** Scope B.3 — the custom/static frontend (used by both the interpreter
 *  `serve(view)` and emit-jvm `serve` via `FrontendFrameworks.current().emit`)
 *  must drill a Remote DataTable's dotted `rowsPath` envelope at fetch time,
 *  matching the JS browser runtime's `_ssc_ui_rowsOf`. */
class RemoteRowsPathTest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  private def emitTable(rowsPath: String): String =
    val sig   = new FetchUrlSignal("rows", "/api/orders", "tick")
    val table = View.DataTable(
      TableDataSource.Remote(sig, rowsPath),
      List(FieldColumnDef("Name", "name", None)),
      Nil)
    StaticJsEmitter.emit(table, Nil)

  /** Count non-overlapping literal occurrences of `needle` in `s`. */
  private def countOf(s: String, needle: String): Int =
    s.split(java.util.regex.Pattern.quote(needle), -1).length - 1

  test("emit — Remote rowsPath threads __ssc_rowsOf(data, path) into both fetch callbacks") {
    val js = emitTable("result.items")
    assert(js.contains("function __ssc_rowsOf("), s"helper not emitted: $js")
    // Both the initial fetch and the tick-driven re-fetch drill the envelope
    // (jsString emits single-quoted JS string literals).
    val drillCount = countOf(js, "__ssc_rowsOf(data, 'result.items')")
    assert(drillCount == 2, s"expected 2 drilled fetch callbacks, got $drillCount in: $js")
  }

  test("emit — empty rowsPath still routes through __ssc_rowsOf (built-in keys)") {
    val js = emitTable("")
    assert(js.contains("function __ssc_rowsOf("), s"helper not emitted: $js")
    assert(js.contains("__ssc_rowsOf(data, '')"), s"empty-path drill not wired: $js")
  }

  test("emit — the emitted __ssc_rowsOf normalises envelopes correctly under node") {
    assume(nodeAvailable, "node not available — skipping")
    val js = emitTable("result.items")
    // Extract exactly the emitted helper (it is emitted right before `function mount`).
    val start = js.indexOf("function __ssc_rowsOf")
    val end   = js.indexOf("function mount", start)
    assert(start >= 0 && end > start, "could not slice __ssc_rowsOf helper")
    val helper = js.substring(start, end)

    val tmp    = Files.createTempDirectory("ssc-rowspath-")
    val script = tmp.resolve("check.mjs")
    Files.writeString(script,
      helper + "\n" +
      "function eq(a, b, msg) { if (JSON.stringify(a) !== JSON.stringify(b)) throw new Error(msg + ': ' + JSON.stringify(a)); }\n" +
      // dotted path drilled out of a non-standard envelope
      "eq(__ssc_rowsOf({result:{items:[{name:'a'},{name:'b'}]}}, 'result.items'), [{name:'a'},{name:'b'}], 'dotted path');\n" +
      // already an array — returned verbatim
      "eq(__ssc_rowsOf([{name:'x'}], 'result.items'), [{name:'x'}], 'array passthrough');\n" +
      // wrong path degrades to the built-in keys, never throws
      "eq(__ssc_rowsOf({data:[{name:'d'}]}, 'no.such.path'), [{name:'d'}], 'fallback key');\n" +
      // no path + no known key → empty array (never undefined / crash)
      "eq(__ssc_rowsOf({nope:1}, ''), [], 'unknown shape -> []');\n" +
      // built-in keys honoured with empty path
      "eq(__ssc_rowsOf({results:[1,2]}, ''), [1,2], 'results key');\n" +
      "console.log('rowspath-ok');\n")
    val out = Try(s"node ${script.toString}".!!).getOrElse("")
    assert(out.trim == "rowspath-ok", s"node check failed: $out")
  }
