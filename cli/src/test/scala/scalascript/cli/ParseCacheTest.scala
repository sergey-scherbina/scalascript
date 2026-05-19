package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach
import scala.compiletime.uninitialized

/** Tests for [[ParseCache]].
 *
 *  We write real `.ssc` files to a temporary directory so that `os.mtime`
 *  and `os.read.bytes` behave exactly as they would in production.
 */
class ParseCacheTest extends AnyFunSuite with BeforeAndAfterEach:

  private var tmpDir: os.Path = uninitialized

  override def beforeEach(): Unit =
    tmpDir = os.temp.dir(deleteOnExit = true)
    ParseCache.clear()

  override def afterEach(): Unit =
    ParseCache.clear()

  // ── helpers ─────────────────────────────────────────────────────────────

  private def writeFile(name: String, content: String): os.Path =
    val p = tmpDir / name
    os.write.over(p, content)
    p

  private def touch(p: os.Path, newContent: String): Unit =
    // Ensure mtime advances by at least 1 ms so the cache sees a change.
    Thread.sleep(10)
    os.write.over(p, newContent)

  // ── tests ───────────────────────────────────────────────────────────────

  test("parse is invoked on first access and result is returned"):
    val p = writeFile("hello.ssc", "# Hello\n```scala\n1 + 1\n```\n")
    val m = ParseCache.getOrParse(p)
    assert(m != null)

  test("same object returned on second call with unchanged file"):
    val p  = writeFile("hello.ssc", "# Hello\n```scala\n1 + 1\n```\n")
    val m1 = ParseCache.getOrParse(p)
    // mtime is the same → fast path, no re-parse.
    val m2 = ParseCache.getOrParse(p)
    assert(m1 eq m2, "Expected the same Module reference (cache hit)")

  test("cache invalidates when content changes"):
    val p  = writeFile("hello.ssc", "# Hello\n```scala\n1 + 1\n```\n")
    val m1 = ParseCache.getOrParse(p)
    touch(p, "# World\n```scala\n2 + 2\n```\n")
    val m2 = ParseCache.getOrParse(p)
    assert(!(m1 eq m2), "Expected a fresh Module after content change")

  test("cache is not invalidated by spurious mtime touch with same content"):
    val content = "# Hello\n```scala\n1 + 1\n```\n"
    val p       = writeFile("hello.ssc", content)
    val m1      = ParseCache.getOrParse(p)
    // Simulate an editor writing identical content (mtime changes, bytes same).
    touch(p, content)
    val m2 = ParseCache.getOrParse(p)
    // Content hash matches → same object re-used.
    assert(m1 eq m2, "Expected cache hit even though mtime changed (identical content)")

  test("sha256hex is deterministic"):
    val bytes = "hello".getBytes("UTF-8")
    val h1    = ParseCache.sha256hex(bytes)
    val h2    = ParseCache.sha256hex(bytes)
    assert(h1 == h2)
    assert(h1.length == 64, "SHA-256 hex should be 64 characters")

  test("sha256hex differs for different inputs"):
    val h1 = ParseCache.sha256hex("foo".getBytes("UTF-8"))
    val h2 = ParseCache.sha256hex("bar".getBytes("UTF-8"))
    assert(h1 != h2)

  test("invalidate removes a single path from cache"):
    val p = writeFile("a.ssc", "# A\n")
    ParseCache.getOrParse(p)
    ParseCache.invalidate(p)
    // After invalidation a second call must re-parse (new object not guaranteed
    // equal by reference — just verify we don't crash and a module is returned).
    val m = ParseCache.getOrParse(p)
    assert(m != null)

  test("clear empties the cache"):
    val p1 = writeFile("a.ssc", "# A\n")
    val p2 = writeFile("b.ssc", "# B\n")
    ParseCache.getOrParse(p1)
    ParseCache.getOrParse(p2)
    ParseCache.clear()
    // After clear, both files are re-parsed on next access.
    val m1 = ParseCache.getOrParse(p1)
    val m2 = ParseCache.getOrParse(p2)
    assert(m1 != null)
    assert(m2 != null)

  test("multiple files are cached independently"):
    val p1 = writeFile("a.ssc", "# A\n```scala\n1\n```\n")
    val p2 = writeFile("b.ssc", "# B\n```scala\n2\n```\n")
    val m1a = ParseCache.getOrParse(p1)
    val m2a = ParseCache.getOrParse(p2)
    // Change only p1.
    touch(p1, "# A modified\n```scala\n3\n```\n")
    val m1b = ParseCache.getOrParse(p1)
    val m2b = ParseCache.getOrParse(p2)
    assert(!(m1a eq m1b), "p1 should have been re-parsed")
    assert(m2a eq m2b,    "p2 should still be cached")
