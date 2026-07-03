package scalascript.codegen

import java.util.concurrent.TimeUnit
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

class WasmBackendTest extends AnyFunSuite with Matchers:

  private val backend = WasmBackend()

  // ── Identity ────────────────────────────────────────────────────────────

  test("backend id is 'wasm'"):
    backend.id shouldBe "wasm"

  test("backend declares WasmBytecode output"):
    backend.capabilities.outputs should contain(OutputKind.WasmBytecode)

  test("backend declares JavaScriptSource output (JS glue)"):
    backend.capabilities.outputs should contain(OutputKind.JavaScriptSource)

  test("backend accepts scala source language"):
    backend.acceptedSources should contain("scala")

  test("backend accepts scalascript source language"):
    backend.acceptedSources should contain("scalascript")

  test("backend accepts ssc source language alias"):
    backend.acceptedSources should contain("ssc")

  // ── Block detection ─────────────────────────────────────────────────────

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def moduleScala(code: String) =
    Parser.parse(s"# Test\n\n```scala\n$code\n```\n")

  private def moduleHtml(code: String) =
    Parser.parse(s"# Test\n\n```html\n$code\n```\n")

  test("WasmGen.hasBlocks: true for scalascript block"):
    WasmGen.hasBlocks(module("val x = 1")) shouldBe true

  test("WasmGen.hasBlocks: true for scala block"):
    WasmGen.hasBlocks(moduleScala("@main def run() = println(42)")) shouldBe true

  test("WasmGen.hasBlocks: false for html-only module"):
    WasmGen.hasBlocks(moduleHtml("<h1>Hello</h1>")) shouldBe false

  test("WasmGen.collectSource: concatenates scala blocks"):
    val src = WasmGen.collectSource(moduleScala("val x = 1\n\nval y = 2"))
    src should include("val x = 1")
    src should include("val y = 2")

  test("WasmGen.collectSource: includes scalascript blocks"):
    val src = WasmGen.collectSource(module("val x = 1"))
    src should include("val x = 1")

  test("WasmGen.collectSource: combines scala and scalascript blocks"):
    val combined = Parser.parse(
      """|# Test
         |
         |```scala
         |val a = 1
         |```
         |
         |```scalascript
         |val b = 2
         |```
         |""".stripMargin)
    val src = WasmGen.collectSource(combined)
    src should include("val a = 1")
    src should include("val b = 2")

  // ── arch-ffi: @wasm extern lowering ──────────────────────────────────────

  test("collectSource lowers @wasm extern to a real def (with $N substitution)"):
    val src = WasmGen.collectSource(module(
      """@wasm("scala.scalajs.js.Math.max($0, $1)")
        |extern def maxOf(a: Int, b: Int): Int
        |
        |@main def run(): Unit = println(maxOf(2, 5))""".stripMargin))
    withClue(s"generated:\n$src\n") {
      src should not include "__extern__"
      src should not include "extern def"
      src should not include "@wasm"
      src should include ("def maxOf")
      src should include ("scala.scalajs.js.Math.max(a, b)")   // $0/$1 → a/b
    }

  test("collectSource drops an extern with no @wasm implementation"):
    val src = WasmGen.collectSource(module(
      """extern def unsupported(): Int
        |
        |@main def run(): Unit = ()""".stripMargin))
    withClue(s"generated:\n$src\n") {
      src should not include "__extern__"
      src should not include "unsupported"
      src should include ("def run")
    }

  test("collectSource leaves an extern-free block byte-identical (raw passthrough)"):
    val raw = "val x = 1\n\nval y = 2"
    WasmGen.collectSource(module(raw)).trim shouldBe raw

  // ── cross-module: import inlining + macro expansion ──────────────────────

  test("collectSource inlines a local .ssc import (cross-module)"):
    val dir = os.temp.dir(prefix = "ssc-wasm-import-")
    os.write(dir / "lib.ssc", "# Lib\n\n```scalascript\ndef helper(x: Int): Int = x * 10\n```\n")
    val consumer = Parser.parse(
      "# Consumer\n\n[helper](lib.ssc)\n\n```scalascript\n@main def run(): Unit = println(helper(4))\n```\n")
    val src = WasmGen.collectSource(consumer, Some(dir))
    withClue(s"generated:\n$src\n") {
      src should include ("def helper")          // imported def inlined
      src should include ("helper(4)")           // consumer call
    }

  test("collectSource inlines transitively + dedupes a diamond import"):
    val dir = os.temp.dir(prefix = "ssc-wasm-diamond-")
    os.write(dir / "base.ssc", "# Base\n\n```scalascript\ndef base(): Int = 7\n```\n")
    os.write(dir / "a.ssc", "# A\n\n[base](base.ssc)\n\n```scalascript\ndef a(): Int = base()\n```\n")
    os.write(dir / "b.ssc", "# B\n\n[base](base.ssc)\n\n```scalascript\ndef b(): Int = base()\n```\n")
    val consumer = Parser.parse(
      "# C\n\n[a](a.ssc)\n\n[b](b.ssc)\n\n```scalascript\n@main def run(): Unit = println(a() + b())\n```\n")
    val src = WasmGen.collectSource(consumer, Some(dir))
    withClue(s"generated:\n$src\n") {
      src should include ("def a()")
      src should include ("def b()")
      ("def base\\(\\)".r.findAllIn(src).size) shouldBe 1   // base inlined exactly once (deduped)
    }

  test("collectSource: a single-module macro is expanded (no __ssc_macro__ leakage)"):
    val m = Parser.parse(
      "# Test\n\n```scalascript\n" +
        "inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }\n" +
        "def plusOneImpl(x: Expr[Int])(using q: QuotedContext): Expr[Int] = '{ $x + 1 }\n" +
        "@main def run(): Unit = println(plusOne(41))\n```\n")
    val src = WasmGen.collectSource(scalascript.artifact.MacroCodegen.expand(m))
    withClue(s"generated:\n$src\n") {
      src should not include "__ssc_macro__"
      src should not include "plusOneImpl"
      src should include ("41")
    }

  private val effectProg =
    """effect Log:
      |  def write(s: String): Unit
      |
      |def shout(): Unit =
      |  Log.write("hello")
      |  Log.write("world")
      |
      |@main def main(): Unit =
      |  handle(shout()) {
      |    case Log.write(msg, resume) => println(msg); resume(())
      |  }""".stripMargin

  test("effects compile end-to-end to a WASM binary (CPS lowering + Scala.js effect runtime)"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    val bytes = runWasmSsc(effectProg)
    bytes should not be empty
    bytes.take(4) shouldBe Array(0x00, 0x61, 0x73, 0x6d).map(_.toByte)   // \0asm

  private lazy val hasNode: Boolean =
    try os.proc("node", "--version").call(check = false).exitCode == 0 catch case _: Throwable => false

  private def runBundleOnNode(bundle: WasmGen.WasmBundle, prefix: String, args: Seq[String] = Nil): String =
    bundle.wasmBytes should not be empty
    val dir = os.temp.dir(prefix = prefix)
    os.write(dir / "main.wasm", bundle.wasmBytes)
    os.write(dir / "main.mjs",  bundle.mainJs)
    os.write(dir / "__loader.js", bundle.loaderJs)
    val p = ProcessBuilder((Seq("node", (dir / "main.mjs").toString) ++ args)*).directory(dir.toIO).start()
    val out = StringBuilder()
    val err = StringBuilder()
    def drain(in: java.io.InputStream, sb: StringBuilder): Thread =
      val t = new Thread(() =>
        try
          val r = java.io.BufferedReader(java.io.InputStreamReader(in))
          var line = r.readLine()
          while line != null do
            sb.synchronized { sb.append(line).append('\n') }
            line = r.readLine()
        catch case _: Throwable => ()
      )
      t.setDaemon(true); t.start(); t
    val ot = drain(p.getInputStream, out)
    val et = drain(p.getErrorStream, err)
    val finished = p.waitFor(180, TimeUnit.SECONDS)
    if !finished then p.destroyForcibly()
    ot.join(5000); et.join(5000)
    val exit = if finished then p.exitValue() else -1
    withClue(s"node exit=$exit timedOut=${!finished}\nstderr:\n$err\nstdout:\n$out\n") {
      exit shouldBe 0
    }
    out.toString.trim

  test("effects RUN correctly on wasm (handler + resume, matches interpreter)"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    assume(hasNode, "node not available")
    val bundle = WasmGen.compileToWasm(module(effectProg))
    bundle.wasmBytes should not be empty
    val dir = os.temp.dir(prefix = "ssc-wasm-eff-run-")
    os.write(dir / "main.wasm", bundle.wasmBytes)
    os.write(dir / "main.mjs",  bundle.mainJs)
    os.write(dir / "__loader.js", bundle.loaderJs)
    val p = ProcessBuilder("node", (dir / "main.mjs").toString).directory(dir.toIO).start()
    val out = scala.io.Source.fromInputStream(p.getInputStream).mkString
    p.waitFor()
    out.trim shouldBe "hello\nworld"

  private val effectMainNonUnitProg =
    """effect Counter:
      |  def next(): Int
      |
      |def total(): Int =
      |  Counter.next() + 1
      |
      |@main def main(): Int =
      |  val result = handle(total()) {
      |    case Counter.next(resume) => resume(41)
      |  }
      |  println(result)
      |  result""".stripMargin

  test("effectful wasm main may return a non-Unit value (wrapper discards it)"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    assume(hasNode, "node not available")
    val bundle = WasmGen.compileToWasm(module(effectMainNonUnitProg))
    runBundleOnNode(bundle, "ssc-wasm-main-nonunit-") shouldBe "42"

  private val effectMainArgsUnitProg =
    """effect Log:
      |  def write(s: String): Unit
      |
      |def emit(s: String): Unit =
      |  Log.write(s)
      |
      |@main def main(args: String*): Unit =
      |  val msg = if args.isEmpty then "empty" else args.mkString("-")
      |  handle(emit(msg)) {
      |    case Log.write(value, resume) => println(value); resume(())
      |  }""".stripMargin

  test("effectful wasm main with String* args compiles and runs"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    assume(hasNode, "node not available")
    val bundle = WasmGen.compileToWasm(module(effectMainArgsUnitProg))
    runBundleOnNode(bundle, "ssc-wasm-main-args-") shouldBe "empty"

  private val effectMainArgsNonUnitProg =
    """effect Counter:
      |  def next(): Int
      |
      |def total(seed: Int): Int =
      |  Counter.next() + seed
      |
      |@main def main(args: String*): Int =
      |  val seed = if args.isEmpty then 41 else args(0).toInt
      |  val result = handle(total(seed)) {
      |    case Counter.next(resume) => resume(1)
      |  }
      |  println(result)
      |  result""".stripMargin

  test("effectful wasm main may declare String* args and return a non-Unit value"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    assume(hasNode, "node not available")
    val bundle = WasmGen.compileToWasm(module(effectMainArgsNonUnitProg))
    runBundleOnNode(bundle, "ssc-wasm-main-args-nonunit-") shouldBe "42"

  test("effectful wasm main rejects raw Array[String] args with a clear error"):
    val ex = intercept[RuntimeException] {
      WasmGen.compileToWasm(module(
        """effect Log:
          |  def write(s: String): Unit
          |
          |@main def main(args: Array[String]): Unit =
          |  handle(Log.write(args.mkString("-"))) {
          |    case Log.write(value, resume) => println(value); resume(())
          |  }""".stripMargin))
    }
    ex.getMessage should include("use args: String*")

  // wasm-effects-2: arithmetic over Any-typed effect-op results uses `_binOp`,
  // now part of WasmEffectRuntime. handle(total())=20, doubled=40.
  private val effectArithProg =
    """effect Counter:
      |  def next(): Int
      |
      |def total(): Int =
      |  val a = Counter.next()
      |  val b = Counter.next()
      |  a + b
      |
      |@main def main(): Unit =
      |  val sum = handle(total()) {
      |    case Counter.next(resume) => resume(10)
      |  }
      |  val doubled = sum * 2
      |  println(doubled)""".stripMargin

  test("effects with arithmetic in body RUN on wasm (_binOp over Any)"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    assume(hasNode, "node not available")
    val bundle = WasmGen.compileToWasm(module(effectArithProg))
    bundle.wasmBytes should not be empty
    val dir = os.temp.dir(prefix = "ssc-wasm-eff-arith-")
    os.write(dir / "main.wasm", bundle.wasmBytes)
    os.write(dir / "main.mjs",  bundle.mainJs)
    os.write(dir / "__loader.js", bundle.loaderJs)
    val p = ProcessBuilder("node", (dir / "main.mjs").toString).directory(dir.toIO).start()
    val out = scala.io.Source.fromInputStream(p.getInputStream).mkString
    p.waitFor()
    out.trim shouldBe "40"

  // wasm-effects-3: collection HOFs on an Any-typed effect result lower to
  // `_dispatch` (which calls _seqMap/_seqFilter/…). map(*2)=[2,4,6], filter(>4)=[6], head=6.
  private val effectCollProg =
    """effect Items:
      |  def get(): List[Int]
      |
      |def process(): Int =
      |  val xs = Items.get()
      |  val doubled = xs.map(x => x * 2)
      |  val big = doubled.filter(x => x > 4)
      |  big.head
      |
      |@main def main(): Unit =
      |  val r = handle(process()) {
      |    case Items.get(resume) => resume(List(1, 2, 3))
      |  }
      |  println(r)""".stripMargin

  test("effects with collection HOFs in body RUN on wasm (_dispatch over Any)"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    assume(hasNode, "node not available")
    val bundle = WasmGen.compileToWasm(module(effectCollProg))
    bundle.wasmBytes should not be empty
    val dir = os.temp.dir(prefix = "ssc-wasm-eff-coll-")
    os.write(dir / "main.wasm", bundle.wasmBytes)
    os.write(dir / "main.mjs",  bundle.mainJs)
    os.write(dir / "__loader.js", bundle.loaderJs)
    val p = ProcessBuilder("node", (dir / "main.mjs").toString).directory(dir.toIO).start()
    val out = scala.io.Source.fromInputStream(p.getInputStream).mkString
    p.waitFor()
    out.trim shouldBe "6"

  // wasm-effects-4: multi-shot resume. The handler calls resume once per option
  // and flatMaps (`_anyFlatMap`); resume returns each branch's collected results.
  // {1,2} × {10,20} → 4 combinations → all.length == 4.
  private val effectMultiShotProg =
    """multi effect NonDet:
      |  def choose(opts: List[Int]): Int
      |
      |def prog(): Int ! NonDet =
      |  val a = NonDet.choose(List(1, 2))
      |  val b = NonDet.choose(List(10, 20))
      |  a + b
      |
      |@main def main(): Unit =
      |  val all = handle(prog()) {
      |    case NonDet.choose(opts, resume) => opts.flatMap(o => resume(o))
      |  }
      |  println(all.length)""".stripMargin

  test("multi-shot effects RUN on wasm (_anyFlatMap; resume called per option)"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    assume(hasNode, "node not available")
    val bundle = WasmGen.compileToWasm(module(effectMultiShotProg))
    bundle.wasmBytes should not be empty
    val dir = os.temp.dir(prefix = "ssc-wasm-eff-multishot-")
    os.write(dir / "main.wasm", bundle.wasmBytes)
    os.write(dir / "main.mjs",  bundle.mainJs)
    os.write(dir / "__loader.js", bundle.loaderJs)
    val p = ProcessBuilder("node", (dir / "main.mjs").toString).directory(dir.toIO).start()
    val out = scala.io.Source.fromInputStream(p.getInputStream).mkString
    p.waitFor()
    out.trim shouldBe "4"

  // wasm-effects-5: cross-module effect — `effect Log` + `shout()` are declared in
  // an imported lib.ssc; the consumer only handles them. generateUserOnly resolves
  // the import (via baseDir) and lowers the whole graph.
  test("cross-module effects RUN on wasm (effect declared in an imported .ssc)"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    assume(hasNode, "node not available")
    val proj = os.temp.dir(prefix = "ssc-wasm-eff-xmod-")
    os.write(proj / "lib.ssc",
      "# Lib\n\n```scalascript\n" +
        "effect Log:\n  def write(s: String): Unit\n\n" +
        "def shout(): Unit =\n  Log.write(\"hello\")\n  Log.write(\"world\")\n```\n")
    val consumer = Parser.parse(
      "# Consumer\n\n[shout](lib.ssc)\n\n```scalascript\n" +
        "@main def main(): Unit =\n" +
        "  handle(shout()) {\n" +
        "    case Log.write(msg, resume) => println(msg); resume(())\n" +
        "  }\n```\n")
    val bundle = WasmGen.compileToWasm(consumer, Some(proj))
    bundle.wasmBytes should not be empty
    val dir = os.temp.dir(prefix = "ssc-wasm-eff-xmod-run-")
    os.write(dir / "main.wasm", bundle.wasmBytes)
    os.write(dir / "main.mjs",  bundle.mainJs)
    os.write(dir / "__loader.js", bundle.loaderJs)
    val p = ProcessBuilder("node", (dir / "main.mjs").toString).directory(dir.toIO).start()
    val out = scala.io.Source.fromInputStream(p.getInputStream).mkString
    p.waitFor()
    out.trim shouldBe "hello\nworld"

  // ── Phase 3: //> using directive hoisting ────────────────────────────────

  test("collectSource hoists //> using dep directive to top of output"):
    val src = WasmGen.collectSource(module(
      """|//> using dep "org.scala-js::scalajs-dom::2.8.0"
         |val x = 1
         |""".stripMargin))
    val lines = src.linesIterator.toList
    lines.head shouldBe """//> using dep "org.scala-js::scalajs-dom::2.8.0""""
    src should include("val x = 1")

  test("collectSource deduplicates //> using dep directives across blocks"):
    val combined = Parser.parse(
      """|# Test
         |
         |```scala
         |//> using dep "org.scala-js::scalajs-dom::2.8.0"
         |val a = 1
         |```
         |
         |```scalascript
         |//> using dep "org.scala-js::scalajs-dom::2.8.0"
         |val b = 2
         |```
         |""".stripMargin)
    val src   = WasmGen.collectSource(combined)
    val count = src.linesIterator.count(_.startsWith("//> using dep"))
    count shouldBe 1

  test("collectSource preserves multiple distinct //> using dep directives"):
    val src = WasmGen.collectSource(module(
      """|//> using dep "org.scala-js::scalajs-dom::2.8.0"
         |//> using dep "com.lihaoyi::upickle::3.3.1"
         |val x = 1
         |""".stripMargin))
    src should include("""//> using dep "org.scala-js::scalajs-dom::2.8.0"""")
    src should include("""//> using dep "com.lihaoyi::upickle::3.3.1"""")
    val lines = src.linesIterator.toList
    lines.head should startWith("//> using dep")

  // ── Backend.compile with no compilable blocks ────────────────────────────

  test("compile returns Segmented(Nil) when only html blocks present"):
    val ir = Normalize(moduleHtml("<h1>Hello</h1>"))
    backend.compile(ir, BackendOptions()) shouldBe CompileResult.Segmented(Nil)

  // ── Integration: compile scala blocks to WASM ───────────────────────────
  // Skipped when scala-cli does not support --js-wasm.

  private lazy val hasWasmSupport: Boolean =
    try
      val tmp = os.temp("@main def probe() = ()", suffix = ".sc", deleteOnExit = true)
      val outDir = os.temp.dir(deleteOnExit = true)
      val result = os.proc(
        "scala-cli", "--power", "package", "--server=false", "--js", "--js-emit-wasm",
        "-o", (outDir / "probe").toString, tmp
      ).call(check = false, stderr = os.Pipe)
      !result.err.text().contains("Unrecognized argument")
    catch case _: Throwable => false

  private def runWasmFromSrc(sscSrc: String): Array[Byte] =
    val ir = Normalize(Parser.parse(sscSrc))
    backend.compile(ir, BackendOptions()) match
      case CompileResult.Segmented(segs) =>
        segs.collectFirst { case Segment.Asset(_, bytes, "application/wasm") => bytes }
          .getOrElse(fail("no wasm asset in result"))
      case CompileResult.Failed(diags) =>
        fail(s"compilation failed: ${diags.mkString(", ")}")
      case other =>
        fail(s"unexpected result: ${other.getClass.getSimpleName}")

  private def runWasm(code: String): Array[Byte] =
    runWasmFromSrc(s"# Test\n\n```scala\n$code\n```\n")

  private def runWasmSsc(code: String): Array[Byte] =
    runWasmFromSrc(s"# Test\n\n```scalascript\n$code\n```\n")

  test("compile simple scala block produces non-empty WASM binary"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    val bytes = runWasm("@main def run() = println(\"hello wasm\")")
    bytes should not be empty
    // WASM magic number: \0asm
    bytes.take(4) shouldBe Array(0x00, 0x61, 0x73, 0x6d).map(_.toByte)

  test("compile a @wasm extern end-to-end to a WASM binary"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    val bytes = runWasmSsc(
      """@wasm("$0 + $1")
        |extern def add(a: Int, b: Int): Int
        |
        |@main def run(): Unit = println(add(20, 22))""".stripMargin)
    bytes should not be empty
    bytes.take(4) shouldBe Array(0x00, 0x61, 0x73, 0x6d).map(_.toByte)

  test("compile simple scalascript block produces non-empty WASM binary"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    val bytes = runWasmSsc("@main def run(): Unit = println(\"hello from scalascript\")")
    bytes should not be empty
    bytes.take(4) shouldBe Array(0x00, 0x61, 0x73, 0x6d).map(_.toByte)

  test("compile scalascript block with collections compiles to WASM"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    val code =
      """|case class Item(name: String, value: Int)
         |
         |@main def run(): Unit =
         |  val items = List(Item("a", 1), Item("b", 2), Item("c", 3))
         |  val total = items.map(_.value).sum
         |  println(s"total = $total")
         |""".stripMargin
    val bytes = runWasmSsc(code)
    bytes should not be empty
    bytes.take(4) shouldBe Array(0x00, 0x61, 0x73, 0x6d).map(_.toByte)

  test("compile mixed scala and scalascript blocks to single WASM binary"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    val src =
      """|# Test
         |
         |```scala
         |def greet(name: String): String = s"Hello, $name!"
         |```
         |
         |```scalascript
         |@main def run(): Unit =
         |  println(greet("WASM"))
         |```
         |""".stripMargin
    val bytes = runWasmFromSrc(src)
    bytes should not be empty
    bytes.take(4) shouldBe Array(0x00, 0x61, 0x73, 0x6d).map(_.toByte)

  test("compile produces JS glue alongside WASM binary"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    val ir = Normalize(Parser.parse("# T\n\n```scala\n@main def run() = println(1)\n```\n"))
    backend.compile(ir, BackendOptions()) match
      case CompileResult.Segmented(segs) =>
        segs.exists { case Segment.Code("javascript", _) => true; case _ => false } shouldBe true
      case other =>
        fail(s"unexpected: $other")

  test("compile scalascript block: JS glue ships alongside WASM binary"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    val ir = Normalize(Parser.parse("# T\n\n```scalascript\n@main def run() = println(42)\n```\n"))
    backend.compile(ir, BackendOptions()) match
      case CompileResult.Segmented(segs) =>
        segs.exists { case Segment.Code("javascript", _) => true; case _ => false } shouldBe true
      case other =>
        fail(s"unexpected: $other")

  test("CapabilityCheck: wasm backend accepts sql blocks (v1.27 Phase 5 — no diagnostic)"):
    // v1.27 Phase 5 — Wasm declares `sql` in `blockLanguages` so the
    // generic `UnknownBlockLanguage` diagnostic no longer fires.  sql
    // blocks are routed through the JS shim that already accompanies
    // the .wasm blob; the Wasm body itself is unaffected.  Phase 6 will
    // add a build-time `UnsupportedJdbcUrl` diagnostic for `jdbc:` URLs
    // on non-JVM targets; until then jdbc: URLs surface a runtime error
    // from `sql-runtime.mjs`'s `UnsupportedJdbcUrl` class.
    import scalascript.validate.CapabilityCheck
    val src =
      """|# Query
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    val module = Normalize(Parser.parse(src))
    val diags  = CapabilityCheck.validate(module, backend.capabilities, backend.id)
    diags.exists {
      case Diagnostic.UnknownBlockLanguage("sql") => true
      case _                                      => false
    } shouldBe false
