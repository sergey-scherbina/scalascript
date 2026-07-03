package scalascript

import org.scalatest.funsuite.AnyFunSuite

import scalascript.codegen.JsGen
import scalascript.parser.Parser

/** backend-specific-blocks Phase 4 — JsGen verbatim emission of `javascript`
 *  fenced blocks.  Text-shape assertions only.
 *  See specs/backend-specific-blocks.md §6 Phase 4. */
class JsGenBackendBlockTest extends AnyFunSuite:

  private def emit(ssc: String): String =
    JsGen.generate(Parser.parse(ssc))

  // ── javascript blocks emitted verbatim ─────────────────────────────────

  test("javascript block: function definition emitted verbatim into JS bundle"):
    val code = emit(
      """|# Module
         |
         |```scalascript
         |extern def currentPid(): Int
         |```
         |
         |```javascript
         |function currentPid() { return (typeof process !== 'undefined') ? process.pid : 0; }
         |```
         |""".stripMargin
    )
    assert(code.contains("function currentPid()"),
      "javascript block function definition must appear verbatim in output")
    assert(code.contains("process.pid"),
      "process.pid reference in javascript block must be preserved")

  test("javascript block: emitted before or alongside ScalaScript user code"):
    val code = emit(
      """|# Module
         |
         |```javascript
         |const _nativePid = () => process.pid;
         |```
         |
         |```scalascript
         |val x = 1
         |```
         |""".stripMargin
    )
    assert(code.contains("_nativePid"), "javascript block content must appear in bundle")

  test("javascript block: multiple blocks all emitted"):
    val code = emit(
      """|# Module
         |
         |```javascript
         |function foo() { return 1; }
         |```
         |
         |```javascript
         |function bar() { return 2; }
         |```
         |""".stripMargin
    )
    assert(code.contains("function foo()"), "first javascript block must appear")
    assert(code.contains("function bar()"), "second javascript block must appear")

  test("javascript block: source preserved verbatim without interpolation"):
    val src = "const x = `${Math.random()}`;  // raw JS template literal"
    val code = emit(
      s"""|# Module
          |
          |```javascript
          |$src
          |```
          |""".stripMargin
    )
    assert(code.contains("Math.random"), "javascript block source must be preserved verbatim")

  test("module without javascript blocks: no javascript block comment emitted"):
    val code = emit(
      """|# Module
         |
         |```scalascript
         |val x = 42
         |```
         |""".stripMargin
    )
    assert(!code.contains("── javascript block ─"),
      "no javascript block comment when no javascript blocks present")

  // ── html/css still use template-value path ─────────────────────────────

  test("html block: still creates template value (not verbatim)"):
    val code = emit(
      """|# Widget
         |
         |```html
         |<div>hello</div>
         |```
         |""".stripMargin
    )
    assert(code.contains("Widget.html") || code.contains("widget.html"),
      "html block must still create a template value in the bundle")
    assert(!code.contains("── javascript block ─"),
      "html block must not trigger the javascript verbatim path")
