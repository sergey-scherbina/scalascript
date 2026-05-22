package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class MetricsTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  override def beforeEach(): Unit = Metrics.reset()

  test("counters start at zero after reset") {
    val snap = Metrics.snapshot()
    snap.values.forall(_ == 0L) shouldBe true
  }

  test("wsActive increments and is reflected in snapshot") {
    Metrics.wsActive.incrementAndGet()
    Metrics.wsActive.incrementAndGet()
    Metrics.snapshot()("ws.active") shouldBe 2L
  }

  test("wsUpgraded / wsRejected track independently") {
    Metrics.wsUpgraded.addAndGet(3)
    Metrics.wsRejected.addAndGet(1)
    val snap = Metrics.snapshot()
    snap("ws.upgraded") shouldBe 3L
    snap("ws.rejected") shouldBe 1L
  }

  test("http counters are tracked separately") {
    Metrics.httpRequests.incrementAndGet()
    Metrics.http4xx.incrementAndGet()
    Metrics.http5xx.addAndGet(2)
    val snap = Metrics.snapshot()
    snap("http.requests") shouldBe 1L
    snap("http.4xx")      shouldBe 1L
    snap("http.5xx")      shouldBe 2L
  }

  test("snapshot contains all expected keys") {
    val keys = Metrics.snapshot().keySet
    keys should contain allOf (
      "ws.active", "ws.upgraded", "ws.rejected",
      "ws.messages.in", "ws.messages.out",
      "ws.bytes.in", "ws.bytes.out",
      "http.requests", "http.4xx", "http.5xx"
    )
  }

  test("reset zeroes all counters") {
    Metrics.wsActive.set(99)
    Metrics.httpRequests.set(42)
    Metrics.reset()
    Metrics.snapshot().values.forall(_ == 0L) shouldBe true
  }
