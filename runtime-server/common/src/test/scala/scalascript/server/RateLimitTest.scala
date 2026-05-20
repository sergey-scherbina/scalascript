package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class RateLimitTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  override def beforeEach(): Unit = RateLimit.clear()

  test("allows up to limit requests in the window") {
    val key = "test-key"
    (1 to 5).foreach(_ => RateLimit.tryAcquire(key, limit = 5, windowSeconds = 60) shouldBe true)
  }

  test("denies the request exceeding the limit") {
    val key = "over-key"
    (1 to 3).foreach(_ => RateLimit.tryAcquire(key, limit = 3, windowSeconds = 60))
    RateLimit.tryAcquire(key, limit = 3, windowSeconds = 60) shouldBe false
  }

  test("different keys are tracked independently") {
    RateLimit.tryAcquire("a", limit = 1, windowSeconds = 60) shouldBe true
    RateLimit.tryAcquire("a", limit = 1, windowSeconds = 60) shouldBe false
    RateLimit.tryAcquire("b", limit = 1, windowSeconds = 60) shouldBe true
  }

  test("reset allows requests again after being over limit") {
    val key = "reset-key"
    RateLimit.tryAcquire(key, limit = 1, windowSeconds = 60)
    RateLimit.tryAcquire(key, limit = 1, windowSeconds = 60) shouldBe false
    RateLimit.reset(key)
    RateLimit.tryAcquire(key, limit = 1, windowSeconds = 60) shouldBe true
  }

  test("limit=1 allows exactly one request") {
    val key = "limit1"
    RateLimit.tryAcquire(key, limit = 1, windowSeconds = 60) shouldBe true
    RateLimit.tryAcquire(key, limit = 1, windowSeconds = 60) shouldBe false
  }

  test("expired window resets counter") {
    val key = "expiring"
    RateLimit.tryAcquire(key, limit = 1, windowSeconds = 60) shouldBe true
    RateLimit.tryAcquire(key, limit = 1, windowSeconds = 60) shouldBe false
    // Window of 0 seconds expired immediately — next call starts a fresh window.
    RateLimit.tryAcquire(key, limit = 1, windowSeconds = 0) shouldBe true
  }
