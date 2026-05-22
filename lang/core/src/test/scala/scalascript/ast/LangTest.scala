package scalascript.ast

import org.scalatest.funsuite.AnyFunSuite

/** Pin the lang-tag predicates used across the parser and every backend.
 *  These classifications drive routing in `Parser`, `Normalize`, `Typer`,
 *  `JsGen`, `JvmGen`, and the interpreter — getting them wrong silently
 *  drops blocks or sends them down the wrong codegen path. */
class LangTest extends AnyFunSuite:

  test("scalascript / ssc are parseable") {
    assert(Lang.isParseable("scalascript"))
    assert(Lang.isParseable("ssc"))
    assert(Lang.isScalaScript("scalascript"))
    assert(Lang.isScalaScript("ssc"))
    assert(!Lang.isStringBlock("scalascript"))
    assert(!Lang.isOpaqueExec("scalascript"))
  }

  test("scala is parseable but not scalascript") {
    assert(Lang.isParseable("scala"))
    assert(Lang.isStandardScala("scala"))
    assert(!Lang.isScalaScript("scala"))
    assert(!Lang.isStringBlock("scala"))
    assert(!Lang.isOpaqueExec("scala"))
  }

  test("html / css are string blocks, not parseable or exec") {
    assert(Lang.isStringBlock("html"))
    assert(Lang.isStringBlock("css"))
    assert(!Lang.isParseable("html"))
    assert(!Lang.isParseable("css"))
    assert(!Lang.isOpaqueExec("html"))
    assert(!Lang.isOpaqueExec("css"))
  }

  test("javascript and js are string blocks (Phase 1)") {
    assert(Lang.isJavaScript("javascript"))
    assert(Lang.isJavaScript("js"))
    assert(Lang.isStringBlock("javascript"))
    assert(Lang.isStringBlock("js"))
    assert(!Lang.isParseable("javascript"))
    assert(!Lang.isParseable("js"))
    assert(!Lang.isOpaqueExec("javascript"))
    assert(!Lang.isOpaqueExec("js"))
  }

  test("node.js and node are opaque exec, not string blocks (Phase 2)") {
    assert(Lang.isNode("node.js"))
    assert(Lang.isNode("node"))
    assert(Lang.isOpaqueExec("node.js"))
    assert(Lang.isOpaqueExec("node"))
    assert(!Lang.isStringBlock("node.js"))
    assert(!Lang.isStringBlock("node"))
    assert(!Lang.isParseable("node.js"))
    assert(!Lang.isParseable("node"))
    assert(!Lang.isJavaScript("node.js"), "node.js must not collide with the `js` alias")
    assert(!Lang.isJavaScript("node"))
  }

  test("sql is parameterised opaque exec (v1.26 Phase 2)") {
    assert(Lang.isSql("sql"))
    assert(Lang.isOpaqueExec("sql"))
    assert(Lang.isParameterizedExec("sql"))
    assert(!Lang.isStringBlock("sql"))
    assert(!Lang.isParseable("sql"))
    assert(!Lang.isJavaScript("sql"))
    assert(!Lang.isNode("sql"))
    // The other opaque-exec lang must not be confused with the parameterised
    // class — node.js is verbatim-linked, sql is bind-rewritten.
    assert(!Lang.isParameterizedExec("node.js"))
    assert(!Lang.isParameterizedExec("scalascript"))
  }

  test("labels are human-readable") {
    assert(Lang.label("scalascript") == "ScalaScript")
    assert(Lang.label("ssc")         == "ScalaScript")
    assert(Lang.label("scala")       == "Scala 3")
    assert(Lang.label("html")        == "HTML")
    assert(Lang.label("css")         == "CSS")
    assert(Lang.label("javascript")  == "JavaScript")
    assert(Lang.label("js")          == "JavaScript")
    assert(Lang.label("node.js")     == "Node.js")
    assert(Lang.label("node")        == "Node.js")
    assert(Lang.label("sql")         == "SQL")
    assert(Lang.label("python")      == "python") // unknown tags echo verbatim
  }
