package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen

/** std-fs-os-p3-js — JsGen preamble includes std.fs / std.os / std.process
 *  implementations for Node.js and browser-safe stubs.  Text-shape assertions. */
class JsGenFsRuntimeTest extends AnyFunSuite:

  private def preamble: String =
    JsGen.generateRuntime(JsGen.Capability.all)

  // ── std.fs ─────────────────────────────────────────────────────────────────

  test("preamble defines readFile"):
    assert(preamble.contains("function readFile("),
      "readFile must be defined in the JS preamble")

  test("preamble defines writeFile"):
    assert(preamble.contains("function writeFile("))

  test("preamble defines exists"):
    assert(preamble.contains("function exists("))

  test("preamble defines listDir"):
    assert(preamble.contains("function listDir("))

  test("preamble defines copyFile"):
    assert(preamble.contains("function copyFile("))

  test("preamble defines deleteFile"):
    assert(preamble.contains("function deleteFile("))

  test("preamble defines mkdirs"):
    assert(preamble.contains("function mkdirs("))

  // ── std.os ─────────────────────────────────────────────────────────────────

  test("preamble defines env"):
    assert(preamble.contains("function env("))

  test("preamble defines envOrElse"):
    assert(preamble.contains("function envOrElse("))

  test("preamble defines pathJoin"):
    assert(preamble.contains("function pathJoin("))

  test("preamble defines pathDirname"):
    assert(preamble.contains("function pathDirname("))

  test("preamble defines pathBasename"):
    assert(preamble.contains("function pathBasename("))

  test("preamble defines platform"):
    assert(preamble.contains("function platform("))

  test("preamble defines hostname"):
    assert(preamble.contains("function hostname("))

  test("preamble defines homedir"):
    assert(preamble.contains("function homedir("))

  // ── std.process ────────────────────────────────────────────────────────────

  test("preamble defines exec"):
    assert(preamble.contains("function exec("))

  // ── Node.js wiring ─────────────────────────────────────────────────────────

  test("preamble loads node:fs via require"):
    assert(preamble.contains("require('fs')") || preamble.contains("require(\"fs\")"),
      "Node.js fs module must be loaded conditionally")

  test("preamble loads node:path via require"):
    assert(preamble.contains("require('path')") || preamble.contains("require(\"path\")"))

  test("preamble loads node:os via require"):
    assert(preamble.contains("require('os')") || preamble.contains("require(\"os\")"))

  // ── Browser stubs ──────────────────────────────────────────────────────────

  test("preamble has FsNotSupported throw for browser"):
    assert(preamble.contains("FsNotSupported"),
      "Browser fallback must mention FsNotSupported")

  test("preamble has ProcessNotSupported throw for browser"):
    assert(preamble.contains("ProcessNotSupported"),
      "Browser fallback must mention ProcessNotSupported")
