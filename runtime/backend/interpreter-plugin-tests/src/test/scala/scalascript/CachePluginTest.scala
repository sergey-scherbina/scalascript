package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** The Cache effect, after extraction from interpreter core into `cache-effect-plugin`
 *  (core-minimization). Formerly in `StdEffectsTest`, now run with NO explicit `installPlugins` —
 *  `runCache` / `runCacheBypass` resolve via the lazy ServiceLoader path, exactly as in
 *  production. `Cache.memoize(key, ttl)(thunk)` re-invokes the thunk via `BlockContext.applyFn`;
 *  the TTL store now lives in the plugin (process-local, was `interp._cacheStore`). */
class CachePluginTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))   // no installPlugins — lazy ServiceLoader dispatch
    ps.flush()
    buf.toString.trim

  test("Cache.memoize returns same value on second call (via plugin)"):
    captured("""
      runCache {
        val v1 = Cache.memoize("key1d", 60) { () => 99 }
        val v2 = Cache.memoize("key1d", 60) { () => 99 }
        println(v1)
        println(v2)
        println(v1 == v2)
      }
    """) shouldBe "99\n99\ntrue"

  test("runCacheBypass returns fresh value each call (via plugin)"):
    captured("""
      runCacheBypass {
        val v1 = Cache.memoize("bypassKey4", 60) { () => 42 }
        val v2 = Cache.memoize("bypassKey4", 60) { () => 99 }
        println(v1)
        println(v2)
      }
    """) shouldBe "42\n99"
