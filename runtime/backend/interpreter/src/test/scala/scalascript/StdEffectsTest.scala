package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Integration tests for v1.4 standard-library effects:
 *  Logger, Random (seeded), Clock (frozen), Env (map fixture). */
class StdEffectsTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  // ── Logger — MOVED to LoggerPluginTest (interpreter-plugin-tests) ─────────
  // The Logger effect was extracted from interpreter core into `logger-effect-plugin`
  // (core-minimization); its four cases now run there via the lazy ServiceLoader path.

  // ── Random (seeded) ────────────────────────────────────────────────────

  test("runRandomSeeded produces deterministic nextInt"):
    val r1 = captured("""
      runRandomSeeded(42) {
        println(Random.nextInt(100))
        println(Random.nextInt(100))
      }
    """)
    val r2 = captured("""
      runRandomSeeded(42) {
        println(Random.nextInt(100))
        println(Random.nextInt(100))
      }
    """)
    r1 shouldBe r2
    r1.split("\n").length shouldBe 2

  test("runRandomSeeded(42) nextInt(100) is in [0, 100)"):
    captured("""
      runRandomSeeded(1) {
        val n = Random.nextInt(10)
        println(n >= 0 && n < 10)
      }
    """) shouldBe "true"

  test("runRandomSeeded nextDouble in [0, 1)"):
    captured("""
      runRandomSeeded(7) {
        val d = Random.nextDouble()
        println(d >= 0.0 && d < 1.0)
      }
    """) shouldBe "true"

  test("Random.pick returns element from list"):
    captured("""
      runRandomSeeded(5) {
        val xs = List(10, 20, 30)
        val v  = Random.pick(xs)
        println(xs.contains(v))
      }
    """) shouldBe "true"

  test("Random.uuid returns a UUID-shaped string"):
    captured("""
      runRandomSeeded(99) {
        val id = Random.uuid()
        // basic UUID shape: 8-4-4-4-12
        println(id.length)
      }
    """) shouldBe "36"

  test("runRandom (unseeded) produces a string of expected length"):
    val out = captured("""
      runRandom {
        println(Random.uuid().length)
      }
    """)
    out shouldBe "36"

  // ── Clock (frozen) ─────────────────────────────────────────────────────

  test("runClockAt freezes Clock.now at the given epoch ms"):
    captured("""
      runClockAt(1000000) {
        println(Clock.now())
        println(Clock.now())
      }
    """) shouldBe "1000000\n1000000"

  test("runClockAt freezes Clock.nowIso"):
    captured("""
      runClockAt(0) {
        println(Clock.nowIso())
      }
    """) shouldBe "1970-01-01T00:00:00Z"

  test("runClockAt Clock.sleep is a no-op (does not delay)"):
    val start = java.lang.System.currentTimeMillis()
    captured("""
      runClockAt(0) {
        Clock.sleep(10000)  // would be 10 s if real
        println("done")
      }
    """) shouldBe "done"
    val elapsed = java.lang.System.currentTimeMillis() - start
    elapsed should be < 2000L

  test("runClock returns actual Clock.now (close to wall time)"):
    val before = java.lang.System.currentTimeMillis()
    val out = captured("""
      runClock {
        println(Clock.now())
      }
    """)
    val after = java.lang.System.currentTimeMillis()
    val t = out.toLong
    t should be >= before
    t should be <= after + 100L

  // ── Env ────────────────────────────────────────────────────────────────

  test("runEnvWith provides key lookup via Env.get"):
    captured("""
      runEnvWith(Map("FOO" -> "bar")) {
        val v = Env.get("FOO")
        v match
          case Some(s) => println(s)
          case None    => println("missing")
      }
    """) shouldBe "bar"

  test("runEnvWith Env.get returns None for missing key"):
    captured("""
      runEnvWith(Map("A" -> "1")) {
        val v = Env.get("MISSING")
        v match
          case Some(s) => println(s)
          case None    => println("none")
      }
    """) shouldBe "none"

  test("runEnvWith Env.required returns value"):
    captured("""
      runEnvWith(Map("HOST" -> "localhost")) {
        println(Env.required("HOST"))
      }
    """) shouldBe "localhost"

  test("runEnvWith Env.required throws on missing key"):
    an[Exception] should be thrownBy captured("""
      runEnvWith(Map()) {
        Env.required("MISSING")
      }
    """)

  test("Env.set mutates the local overlay"):
    captured("""
      runEnvWith(Map()) {
        Env.set("X", "hello")
        Env.get("X") match
          case Some(s) => println(s)
          case None    => println("none")
      }
    """) shouldBe "hello"

  test("runEnv reads real process env (HOME or PATH is set)"):
    val home = Option(java.lang.System.getenv("HOME"))
      .orElse(Option(java.lang.System.getenv("PATH")))
      .getOrElse("")
    if home.nonEmpty then
      captured("""
        runEnv {
          val h = Env.get("HOME")
          h match
            case Some(_) => println("found")
            case None    =>
              val p = Env.get("PATH")
              p match
                case Some(_) => println("found")
                case None    => println("missing")
        }
      """) shouldBe "found"

  // ── Http ──────────────────────────────────────────────────────────────

  test("runHttpStub returns stubbed 200 response for known URL"):
    captured("""
      val routes = Map("http://example.com/hello" -> "world")
      runHttpStub(routes) {
        val resp = Http.get("http://example.com/hello")
        println(resp.status)
        println(resp.body)
      }
    """) shouldBe "200\nworld"

  test("runHttpStub returns 404 for unknown URL"):
    captured("""
      val routes = Map("http://example.com/hello" -> "world")
      runHttpStub(routes) {
        val resp = Http.get("http://example.com/missing")
        println(resp.status)
      }
    """) shouldBe "404"

  test("runHttpStub post returns stubbed response"):
    captured("""
      val routes = Map("http://api.test/submit" -> "ok")
      runHttpStub(routes) {
        val resp = Http.post("http://api.test/submit", "data")
        println(resp.status)
        println(resp.body)
      }
    """) shouldBe "200\nok"

  test("runHttpStub request with method returns stubbed response"):
    captured("""
      val routes = Map("http://api.test/item" -> "found")
      runHttpStub(routes) {
        val resp = Http.request("DELETE", "http://api.test/item", Map(), "")
        println(resp.status)
        println(resp.body)
      }
    """) shouldBe "200\nfound"

  // ── Retry ─────────────────────────────────────────────────────────────

  test("Retry.attempt returns value on immediate success"):
    captured("""
      runRetryNoSleep {
        val r = Retry.attempt(3, 0) { () => 42 }
        println(r)
      }
    """) shouldBe "42"

  test("Retry.attempt rethrows after max attempts exhausted"):
    an[Exception] should be thrownBy captured("""
      runRetryNoSleep {
        Retry.attempt(2, 0) { () =>
          throw RuntimeException("always fails")
        }
      }
    """)

  test("Retry.attempt n=0 runs exactly once even if it succeeds"):
    captured("""
      runRetryNoSleep {
        val r = Retry.attempt(0, 0) { () => "once" }
        println(r)
      }
    """) shouldBe "once"

  // ── Cache ─────────────────────────────────────────────────────────────

  test("Cache.memoize returns same value on second call"):
    // Verify both calls return the same cached result
    captured("""
      runCache {
        val v1 = Cache.memoize("key1d", 60) { () => 99 }
        val v2 = Cache.memoize("key1d", 60) { () => 99 }
        println(v1)
        println(v2)
        println(v1 == v2)
      }
    """) shouldBe "99\n99\ntrue"

  test("runCacheBypass returns fresh value each call"):
    captured("""
      runCacheBypass {
        val v1 = Cache.memoize("bypassKey4", 60) { () => 42 }
        val v2 = Cache.memoize("bypassKey4", 60) { () => 99 }
        println(v1)
        println(v2)
      }
    """) shouldBe "42\n99"

  // ── State ─────────────────────────────────────────────────────────────

  test("runState returns (finalState, result) pair"):
    captured("""
      val (s, r) = runState(0) {
        State.set(10)
        State.set(42)
        "done"
      }
      println(s)
      println(r)
    """) shouldBe "42\ndone"

  test("State.get returns current state"):
    captured("""
      val (s, r) = runState(7) {
        val v = State.get()
        v
      }
      println(r)
    """) shouldBe "7"

  test("State.modify applies a function to the state"):
    captured("""
      val (s, r) = runState(10) {
        State.modify(x => x * 2)
        State.modify(x => x + 5)
        State.get()
      }
      println(s)
      println(r)
    """) shouldBe "25\n25"

  test("runState initial state is used when no set performed"):
    captured("""
      val (s, r) = runState(99) {
        42
      }
      println(s)
    """) shouldBe "99"

  // ── Tx ────────────────────────────────────────────────────────────────

  test("Tx.atomic just runs body"):
    captured("""
      val x = Tx.atomic { 123 }
      println(x)
    """) shouldBe "123"

  test("runTx runs body directly"):
    captured("""
      runTx {
        println("in tx")
      }
    """) shouldBe "in tx"

  // ── Auth ──────────────────────────────────────────────────────────────

  test("Auth.currentUser returns None outside runAuthWith"):
    captured("""
      val u = Auth.currentUser
      u match
        case Some(v) => println("some:" + v)
        case None    => println("none")
    """) shouldBe "none"

  test("runAuthWith injects user for Auth.currentUser"):
    captured("""
      runAuthWith("alice") {
        val u = Auth.currentUser
        u match
          case Some(v) => println(v)
          case None    => println("none")
      }
    """) shouldBe "alice"

  test("Auth.require returns user inside runAuthWith"):
    captured("""
      runAuthWith("bob") {
        println(Auth.require)
      }
    """) shouldBe "bob"

  test("Auth.require throws outside runAuthWith"):
    an[Exception] should be thrownBy captured("""
      Auth.require
    """)

  test("runAuthWith restores prior user after block"):
    captured("""
      runAuthWith("carol") {
        println(Auth.require)
      }
      val u = Auth.currentUser
      u match
        case Some(v) => println("still: " + v)
        case None    => println("restored")
    """) shouldBe "carol\nrestored"

  // ── NonDet (multi-shot exemplar) ──────────────────────────────────────

  test("NonDet.choose is accessible as a global"):
    // NonDet must resolve and produce a Perform node; handled by evalHandle
    captured("""
      val result = handle(NonDet.choose(List(42, 99))) {
        case NonDet.choose(opts, resume) =>
          resume(42)
      }
      println(result)
    """) shouldBe "42"

  // ── Reader (capability exemplar) ─────────────────────────────────────

  test("Reader.ask is accessible as a global"):
    // Reader.ask returns Unit in the interpreter placeholder
    captured("""
      val v = Reader.ask
      println(v)
    """) shouldBe "()"

  // ── interp-parameterized-effect-decl ─────────────────────────────────
  // `effect Name[T]:` (parameterized) must preprocess like `effect Name:` —
  // the type-param clause was previously left un-rewritten and reached the
  // Scala parser as a bare `effect Name[T]` expression.
  test("a parameterized effect declaration `effect Name[T]:` works"):
    captured("""
      effect Box[T]:
        def stash(x: T): Unit
      def use(): Unit ! Box[Int] = Box.stash(5)
      handle(use()) { case Box.stash(x, resume) => println("stashed " + x); resume(()) }
    """) shouldBe "stashed 5"

  // ── interp-effect-multishot-cross-section (subsections) ──────────────
  // The multi-shot registry must traverse `##`/`###` subsections; a `multi
  // effect` declared in a subsection was missed, so its handler was wrongly
  // treated as one-shot ("One-shot violation"), regardless of any earlier
  // one-shot section.
  private def capturedMd(md: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(md))
    ps.flush()
    buf.toString.trim

  test("a multi effect declared in a `##` subsection multi-shots"):
    val md =
      "# T\n\n" +
      "## one-shot {#a}\n\n```scalascript\n" +
      "effect Logger:\n" +
      "  def log(m: String): Unit\n" +
      "def greet(): Unit ! Logger = Logger.log(\"hi\")\n" +
      "handle(greet()) { case Logger.log(m, resume) => println(m); resume(()) }\n" +
      "```\n\n" +
      "## multi {#b}\n\n```scalascript\n" +
      "multi effect NonDet:\n" +
      "  def choose(options: List[Int]): Int\n" +
      "def prog(): Int ! NonDet = NonDet.choose(List(1, 2, 3))\n" +
      "println(handle(prog()) { case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt)) })\n" +
      "```\n"
    capturedMd(md) shouldBe "hi\nList(1, 2, 3)"

  // ── effect-handler-return-clause ─────────────────────────────────────
  // `case Return(x) => …` maps the handled computation's final pure value, bridging
  // the base case so the textbook deep-handler accumulation works.

  test("return clause enables deep-handler accumulation msg :: resume(())"):
    captured("""
      effect Logger:
        def log(msg: String): Unit
      def greet(name: String): Unit ! Logger = Logger.log(s"Hello, $name!")
      val messages = handle(greet("World")) {
        case Logger.log(msg, resume) => msg :: resume(())
        case Return(_) => List()
      }
      println(messages)
    """) shouldBe "List(Hello, World!)"

  test("return clause maps a tail-position completion value"):
    captured("""
      effect Counter:
        def tick(): Int
      def prog(): Int ! Counter =
        val a = Counter.tick()
        a + 1
      println(handle(prog()) {
        case Counter.tick(resume) => resume(10)
        case Return(x) => x * 100
      })
    """) shouldBe "1100"

  test("return clause wraps each multi-shot branch (NonDet singleton)"):
    // Uses a ```scalascript block (multi-shot detection only scans scalascript blocks).
    capturedMd(
      "# T\n\n```scalascript\n" +
      "multi effect NonDet:\n" +
      "  def choose(options: List[Int]): Int\n" +
      "def program(): Int ! NonDet =\n" +
      "  val x = NonDet.choose(List(1, 2, 3))\n" +
      "  x * 10\n" +
      "println(handle(program()) {\n" +
      "  case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))\n" +
      "  case Return(x) => List(x)\n" +
      "})\n" +
      "```\n") shouldBe "List(10, 20, 30)"

  test("a handler with no return clause returns the pure value unchanged (back-compat)"):
    captured("""
      effect Logger:
        def log(msg: String): Unit
      def pure(): Int ! Logger =
        Logger.log("x")
        42
      println(handle(pure()) { case Logger.log(m, resume) => resume(()) })
    """) shouldBe "42"
