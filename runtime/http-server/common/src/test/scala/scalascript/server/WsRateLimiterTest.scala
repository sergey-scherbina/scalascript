package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WsRateLimiterTest extends AnyFunSuite with Matchers:

  test("admit — maxMessagesPerSec <= 0 disables the cap entirely") {
    val rl = new WsRateLimiter(0)
    (1 to 10_000).foreach { _ => rl.admit(System.currentTimeMillis()) shouldBe true }

    val rlNeg = new WsRateLimiter(-5)
    (1 to 1000).foreach { _ => rlNeg.admit(System.currentTimeMillis()) shouldBe true }
  }

  test("admit — within cap returns true; over cap returns false") {
    val rl = new WsRateLimiter(maxMessagesPerSec = 3)
    val t0 = 1_000_000L
    rl.admit(t0)     shouldBe true   // 1
    rl.admit(t0)     shouldBe true   // 2
    rl.admit(t0)     shouldBe true   // 3
    rl.admit(t0)     shouldBe false  // 4 — over cap
    rl.admit(t0 + 1) shouldBe false  // still in same window
  }

  test("admit — window resets after 1000ms; budget refreshes") {
    val rl = new WsRateLimiter(maxMessagesPerSec = 2)
    val t0 = 5_000_000L
    rl.admit(t0)       shouldBe true   // 1
    rl.admit(t0 + 500) shouldBe true   // 2
    rl.admit(t0 + 999) shouldBe false  // 3rd in same window
    // After 1000ms boundary the window resets and the counter starts at 1.
    rl.admit(t0 + 1000) shouldBe true
    rl.admit(t0 + 1100) shouldBe true
    rl.admit(t0 + 1200) shouldBe false // 3rd in the new window
  }

  test("admit — large clock jump still uses fixed-window semantics") {
    val rl = new WsRateLimiter(maxMessagesPerSec = 1)
    rl.admit(0L)            shouldBe true   // first window
    rl.admit(0L)            shouldBe false  // over cap
    rl.admit(10_000_000L)   shouldBe true   // many-second jump → new window
    rl.admit(10_000_000L)   shouldBe false  // over cap in new window
  }
