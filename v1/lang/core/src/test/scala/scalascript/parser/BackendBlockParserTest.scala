package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast
import scalascript.ast.Lang

/** backend-specific-blocks Phase 1 — parser recognition of `java`, `rust`, `wasm`
 *  fenced blocks as opaque native-backend code (specs/backend-specific-blocks.md).
 *
 *  Contract: each of these tags is classified as `EmbeddedKind.Opaque`
 *  (not parsed, not string-interpolated); the source is preserved verbatim.
 *  The backends that act on them are wired in Phases 3–5. */
class BackendBlockParserTest extends AnyFunSuite:

  private def firstBlock(src: String): ast.Content.CodeBlock =
    val module = Parser.parse(src)
    module.sections
      .flatMap(_.content)
      .collectFirst { case cb: ast.Content.CodeBlock => cb }
      .getOrElse(fail("no CodeBlock found"))

  private def allBlocks(src: String): List[ast.Content.CodeBlock] =
    val module = Parser.parse(src)
    module.sections
      .flatMap(_.content)
      .collect { case cb: ast.Content.CodeBlock => cb }

  // ── java ────────────────────────────────────────────────────────────────

  test("java block: lang tag preserved verbatim") {
    val src =
      """|# Module
         |
         |```java
         |public class Pid {
         |  public static int get() { return ProcessHandle.current().pid().intValue(); }
         |}
         |```
         |""".stripMargin
    val cb = firstBlock(src)
    assert(cb.lang == "java")
    assert(cb.source.contains("ProcessHandle"))
    assert(cb.tree.isEmpty, "java source must not be parsed by scalameta")
  }

  test("java block: classified as opaque exec, not parseable or string block") {
    assert(Lang.isJava("java"))
    assert(Lang.isNativeBackendBlock("java"))
    assert(Lang.isOpaqueExec("java"))
    assert(!Lang.isParseable("java"))
    assert(!Lang.isStringBlock("java"))
  }

  // ── rust ────────────────────────────────────────────────────────────────

  test("rust block: lang tag preserved verbatim") {
    val src =
      """|# Module
         |
         |```rust
         |fn current_pid() -> i64 { std::process::id() as i64 }
         |```
         |""".stripMargin
    val cb = firstBlock(src)
    assert(cb.lang == "rust")
    assert(cb.source.contains("std::process::id"))
    assert(cb.tree.isEmpty, "rust source must not be parsed by scalameta")
  }

  test("rust block: classified as opaque exec, not parseable or string block") {
    assert(Lang.isRust("rust"))
    assert(Lang.isNativeBackendBlock("rust"))
    assert(Lang.isOpaqueExec("rust"))
    assert(!Lang.isParseable("rust"))
    assert(!Lang.isStringBlock("rust"))
  }

  // ── wasm ────────────────────────────────────────────────────────────────

  test("wasm block: lang tag preserved verbatim") {
    val src =
      """|# Module
         |
         |```wasm
         |(module
         |  (func $add (param $a i32) (param $b i32) (result i32)
         |    local.get $a
         |    local.get $b
         |    i32.add))
         |```
         |""".stripMargin
    val cb = firstBlock(src)
    assert(cb.lang == "wasm")
    assert(cb.source.contains("i32.add"))
    assert(cb.tree.isEmpty, "wasm source must not be parsed by scalameta")
  }

  test("wasm block: classified as opaque exec, not parseable or string block") {
    assert(Lang.isWasm("wasm"))
    assert(Lang.isNativeBackendBlock("wasm"))
    assert(Lang.isOpaqueExec("wasm"))
    assert(!Lang.isParseable("wasm"))
    assert(!Lang.isStringBlock("wasm"))
  }

  // ── mixed-block file ────────────────────────────────────────────────────

  test("mixed-block file: scalascript + java + rust + wasm all parse in one module") {
    val src =
      """|# CrossPlatform
         |
         |```scalascript
         |extern def currentPid(): Int
         |```
         |
         |```scala
         |def currentPid(): Int = 42
         |```
         |
         |```java
         |public class Jvm { public static int currentPid() { return 42; } }
         |```
         |
         |```rust
         |fn current_pid() -> i64 { 42 }
         |```
         |
         |```wasm
         |(module (func (export "currentPid") (result i32) (i32.const 42)))
         |```
         |""".stripMargin
    val blocks = allBlocks(src)
    assert(blocks.size == 5, s"expected 5 blocks, got ${blocks.size}")
    val langs = blocks.map(_.lang)
    assert(langs.contains("scalascript"), "scalascript block missing")
    assert(langs.contains("scala"),       "scala block missing")
    assert(langs.contains("java"),        "java block missing")
    assert(langs.contains("rust"),        "rust block missing")
    assert(langs.contains("wasm"),        "wasm block missing")
    // backend-native blocks have no scalameta tree
    for cb <- blocks if Lang.isNativeBackendBlock(cb.lang) do
      assert(cb.tree.isEmpty, s"${cb.lang} block must have no scalameta tree")
  }

  test("backend blocks: source is preserved verbatim with no transformation") {
    val javaSource = "public class X { public static int v = 99; }"
    val rustSource = "fn answer() -> i64 { 42 }"
    val wasmSource = "(module (func (result i32) i32.const 42))"
    val src =
      s"""|# Native
          |
          |```java
          |$javaSource
          |```
          |
          |```rust
          |$rustSource
          |```
          |
          |```wasm
          |$wasmSource
          |```
          |""".stripMargin
    val blocks = allBlocks(src).filter(cb => Lang.isNativeBackendBlock(cb.lang))
    assert(blocks.size == 3)
    val byLang = blocks.map(cb => cb.lang -> cb.source.trim).toMap
    assert(byLang("java") == javaSource)
    assert(byLang("rust") == rustSource)
    assert(byLang("wasm") == wasmSource)
  }
