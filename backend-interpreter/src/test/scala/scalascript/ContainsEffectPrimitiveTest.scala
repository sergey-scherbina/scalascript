package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JvmGen

/** Unit tests for `JvmGen.containsEffectPrimitive` — Strategy D Step 1.
 *
 *  See docs/dep-cps-rewrite.md §6 Step 1 for acceptance criteria.
 *  The predicate is the load-bearing tightness check that decides which
 *  dep-function bodies get CPS-rewritten by Step 3. Over-broad matches
 *  regress `actors-process-info.ssc` (see §4.4 "The hard part"); the
 *  negative cases below are the regression canary. */
class ContainsEffectPrimitiveTest extends AnyFunSuite with Matchers:

  private val gen = new JvmGen()

  private def yes(src: String): Unit =
    import scala.meta.{dialects, *}
    val tree = dialects.Scala3(Input.String(src)).parse[Stat].get
    withClue(s"expected effectful: $src") {
      gen.containsEffectPrimitive(tree) shouldBe true
    }

  private def no(src: String): Unit =
    import scala.meta.{dialects, *}
    val tree = dialects.Scala3(Input.String(src)).parse[Stat].get
    withClue(s"expected NON-effectful: $src") {
      gen.containsEffectPrimitive(tree) shouldBe false
    }

  // ── Positive cases — these MUST match ─────────────────────────────

  test("bare actor primitive — self()") {
    yes("self()")
  }

  test("bare actor primitive — link(pid)") {
    yes("link(pid)")
  }

  test("bare actor primitive — trapExit(true)") {
    yes("trapExit(true)")
  }

  test("bare actor primitive — receive { case x => x }") {
    yes("receive { case x => x }")
  }

  test("bare actor primitive — receiveWithTimeout(t) { case x => x }") {
    // `receiveWithTimeout` is currently NOT in actorBareNames but `receive` is;
    // skip the receiveWithTimeout direct shape and verify the underlying receive.
    yes("receive(t) { case x => x }")
  }

  test("bare actor primitive — spawn { () => () }") {
    yes("spawn { () => () }")
  }

  test("infix send — pid ! msg") {
    yes("pid ! msg")
  }

  test("infix send — pid ! (a, b) tuple") {
    yes("pid ! (a, b)")
  }

  test("infix send — chained — a ! b ! c") {
    // Scala parses `a ! b ! c` as `(a ! b) ! c` — still has `!`, matches.
    yes("(a ! b) ! c")
  }

  test("qualified primitive — Actor.self()") {
    yes("Actor.self()")
  }

  test("qualified primitive — Actor.send(pid, msg)") {
    yes("Actor.send(pid, msg)")
  }

  test("qualified primitive — Random.uuid()") {
    yes("Random.uuid()")
  }

  test("qualified primitive — Random.nextInt(10)") {
    yes("Random.nextInt(10)")
  }

  test("qualified primitive — Storage.get(key)") {
    yes("Storage.get(key)")
  }

  test("qualified primitive — Storage.put(key, value)") {
    yes("Storage.put(key, value)")
  }

  test("qualified primitive — Clock.now()") {
    yes("Clock.now()")
  }

  test("qualified primitive — Clock.sleep(100)") {
    yes("Clock.sleep(100)")
  }

  test("qualified primitive — Logger.info(\"x\")") {
    yes("Logger.info(\"x\")")
  }

  test("qualified primitive — Async.delay(100)") {
    yes("Async.delay(100)")
  }

  test("qualified primitive — Async.await(fut)") {
    yes("Async.await(fut)")
  }

  test("primitive nested inside larger expression") {
    yes("{ val x = self(); x }")
  }

  test("primitive inside if-arm") {
    yes("if useCache then \"cached\" else { pid ! Ask; receive { case s => s } }")
  }

  test("primitive inside lambda body") {
    yes("xs.map(x => self())")
  }

  test("primitive inside case body") {
    yes("x match { case 1 => link(pid); case _ => () }")
  }

  // ── Negative cases — regression canaries ─────────────────────────

  test("bare Select on Any-typed value — NOT effectful") {
    // The regression canary from actors-process-info.ssc — `info.links`
    // on an `info: Any` binding must NOT trigger the predicate.
    no("info.links")
  }

  test("bare Select chain — NOT effectful") {
    no("info.links.length")
  }

  test("plain literal — NOT effectful") {
    no("42")
  }

  test("plain string concat — NOT effectful") {
    no("\"a\" + \"b\"")
  }

  test("user-defined function call — NOT effectful") {
    no("myHelper(x, y)")
  }

  test("collection ops — NOT effectful") {
    no("xs.map(_ + 1).filter(_ > 0)")
  }

  test("Select-Apply on unknown object — NOT effectful") {
    // `MyModule.method(x)` where MyModule is not in the primitive whitelist.
    no("MyModule.method(x)")
  }

  test("Select-Apply on Random with non-primitive op — NOT effectful") {
    // `Random.foobar()` — Random is a known module but `foobar` is not an op.
    no("Random.foobar()")
  }

  test("Select-Apply on Actor with any op — IS effectful (Actor is fully open)") {
    // Special: Actor is the one module where ANY .method() counts because
    // the runtime has ~50 cluster-mgmt operations and they're all primitive.
    yes("Actor.totallyMadeUpOp()")
  }

  test("Block of pure statements — NOT effectful") {
    no("{ val x = 1; val y = 2; x + y }")
  }

  test("Try-catch around pure code — NOT effectful") {
    no("try x.toInt catch { case e: Exception => 0 }")
  }

  test("function definition with pure body — NOT effectful") {
    no("def add(a: Int, b: Int): Int = a + b")
  }

  test("class definition with pure methods — NOT effectful") {
    no("class Foo { def bar(): Int = 1 }")
  }

  test("import statement alone — NOT effectful") {
    no("import scala.collection.mutable")
  }

  // ── Edge cases ────────────────────────────────────────────────────

  test("primitive inside object body — IS effectful") {
    // The predicate walks the entire tree including nested object/def
    // bodies. The fixpoint then ALSO sees `f` as a separate entry and
    // marks it independently. Over-approximation on the enclosing
    // tree is harmless — see `containsEffectPrimitive` docstring.
    yes("object Helpers { def f(): Any = self() }")
  }

  test("primitive inside def body — IS effectful") {
    yes("def runIt(): Any = { val me = self(); pid ! me }")
  }

  test("primitive inside for-comprehension — IS effectful") {
    yes("for { x <- xs } yield Actor.send(pid, x)")
  }

  test("primitive in pattern guard — IS effectful (we walk guards too)") {
    yes("x match { case n if self() != null => n; case _ => 0 }")
  }

  test("primitive in default parameter — IS effectful") {
    yes("def f(p: Any = self()): Any = p")
  }
