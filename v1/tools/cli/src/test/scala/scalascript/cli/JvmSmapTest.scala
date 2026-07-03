package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Unit tests for [[JvmSmap]] and [[JvmSmapInjector]].
 *
 *  These tests run without scala-cli or the scala3-compiler stdlib —
 *  they only exercise pure functions over byte arrays and string
 *  builders.  Always-on; no `cancel(...)` fallbacks.
 *
 *  v2.0 Phase 4 (Option A). */
class JvmSmapTest extends AnyFunSuite:

  // ── JvmSmap.build ───────────────────────────────────────────────────────

  test("build emits a well-formed JSR-45 SMAP header + footer"):
    val smap = JvmSmap.build("a", "a.ssc", Map(10 -> 5))
    val lines = smap.linesIterator.toList
    assert(lines.head == "SMAP", s"first line must be 'SMAP', got: ${lines.head}")
    assert(lines(1) == "a_sc.scala", s"compiled-name line; got: ${lines(1)}")
    assert(lines(2) == "SSC", s"default stratum; got: ${lines(2)}")
    assert(lines(3) == "*S SSC", s"stratum decl; got: ${lines(3)}")
    assert(lines(4) == "*F", s"file section; got: ${lines(4)}")
    assert(lines(5) == "+ 1 a.ssc", s"file entry; got: ${lines(5)}")
    assert(lines(6) == "a.ssc", s"file repeat; got: ${lines(6)}")
    assert(lines(7) == "*L", s"line section; got: ${lines(7)}")
    assert(lines(8) == "10#1:5", s"mapping; got: ${lines(8)}")
    assert(lines.last == "*E", s"end marker; got: ${lines.last}")

  test("build sorts the *L section ascending by generated line"):
    val smap = JvmSmap.build("a", "a.ssc", Map(50 -> 25, 10 -> 5, 30 -> 15))
    val lSection = smap.linesIterator.dropWhile(_ != "*L").drop(1).takeWhile(_ != "*E").toList
    assert(lSection == List("10#1:5", "30#1:15", "50#1:25"),
      s"mappings should be sorted by gen-line; got: $lSection")

  test("build with empty map emits an empty *L section but valid framing"):
    val smap = JvmSmap.build("m", "m.ssc", Map.empty)
    // *L immediately followed by *E with no mapping lines.
    val idxL = smap.indexOf("*L\n")
    val idxE = smap.indexOf("*E\n")
    assert(idxL >= 0 && idxE >= 0 && idxE > idxL)
    val between = smap.substring(idxL + 3, idxE)
    assert(between.isEmpty, s"expected empty *L section; got: '$between'")

  // ── JvmSmapInjector round-trip on a tiny synthetic class ────────────────

  /** Build the simplest possible `.class` bytes — a class named `Empty`
   *  with no methods and no fields — via ASM, so the inject/read round
   *  trip has something to operate on.  Returns the raw class bytes. */
  private def buildEmptyClass(name: String): Array[Byte] =
    import org.objectweb.asm.{ClassWriter, Opcodes}
    val cw = new ClassWriter(0)
    cw.visit(
      Opcodes.V1_8,
      Opcodes.ACC_PUBLIC,
      name,
      null,
      "java/lang/Object",
      null
    )
    cw.visitEnd()
    cw.toByteArray

  test("injectInto adds a SourceDebugExtension that readSourceDebugExtension can read back"):
    val classBytes = buildEmptyClass("Empty")
    val smap = JvmSmap.build("a", "a.ssc", Map(7 -> 3))
    val rewritten = JvmSmapInjector.injectInto(classBytes, smap)
    val extracted = JvmSmapInjector.readSourceDebugExtension(rewritten)
    assert(extracted.contains(smap),
      s"expected the injected SMAP to round-trip;\nexpected: $smap\ngot: $extracted")

  test("injectInto replaces an existing SourceDebugExtension (second injection wins)"):
    val classBytes = buildEmptyClass("Empty")
    val smap1 = JvmSmap.build("a", "a.ssc", Map(1 -> 1))
    val smap2 = JvmSmap.build("a", "a.ssc", Map(2 -> 2))
    val once  = JvmSmapInjector.injectInto(classBytes, smap1)
    val twice = JvmSmapInjector.injectInto(once, smap2)
    // ASM's `visitSource` + our custom Attribute write `null` to the
    // `debug` slot during the re-read, so the OLD SMAP is dropped from
    // the class file's attribute table; only the SECOND injection's
    // SMAP survives.
    val finalSmap = JvmSmapInjector.readSourceDebugExtension(twice)
    assert(finalSmap.contains(smap2),
      s"expected the second SMAP to win; got: $finalSmap")
    val asString = new String(twice, "ISO-8859-1")
    assert(!asString.contains("1#1:1"),
      "first SMAP should have been replaced (no `1#1:1` lingering)")
    assert(asString.contains("2#1:2"), "second SMAP should be present")

  test("readSourceDebugExtension returns None when no such attribute exists"):
    val classBytes = buildEmptyClass("Empty")
    val extracted = JvmSmapInjector.readSourceDebugExtension(classBytes)
    assert(extracted.isEmpty, s"expected None; got: $extracted")

  // ── injectAll walks a directory of .class files ─────────────────────────

  test("injectAll rewrites every .class file under the given directory"):
    val workDir = os.temp.dir(prefix = "ssc-smap-test-")
    try
      val classBytes1 = buildEmptyClass("A")
      val classBytes2 = buildEmptyClass("pkg/B")
      os.write(workDir / "A.class", classBytes1)
      os.makeDir.all(workDir / "pkg")
      os.write(workDir / "pkg" / "B.class", classBytes2)
      // Drop in a non-class file too — should be ignored.
      os.write(workDir / "README.txt", "hello")

      val smap = JvmSmap.build("a", "a.ssc", Map(5 -> 1))
      JvmSmapInjector.injectAll(workDir, smap)

      val s1 = JvmSmapInjector.readSourceDebugExtension(os.read.bytes(workDir / "A.class"))
      val s2 = JvmSmapInjector.readSourceDebugExtension(os.read.bytes(workDir / "pkg" / "B.class"))
      assert(s1.contains(smap), s"A.class: expected SMAP; got: $s1")
      assert(s2.contains(smap), s"pkg/B.class: expected SMAP; got: $s2")
      // README must be untouched.
      assert(os.read(workDir / "README.txt") == "hello")
    finally os.remove.all(workDir)
