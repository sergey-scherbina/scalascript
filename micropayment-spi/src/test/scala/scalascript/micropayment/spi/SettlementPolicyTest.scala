package scalascript.micropayment.spi

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.duration.*

import java.time.Instant

class SettlementPolicyTest extends AnyFunSuite:

  private def state(
    offChainPaid: BigInt = BigInt(0),
    lastActivity: Option[Instant] = None,
  ): ChannelState =
    ChannelState(
      channelId    = "test-channel",
      sequence     = 0L,
      offChainPaid = offChainPaid,
      onChainPaid  = BigInt(0),
      openSince    = Instant.EPOCH,
      lastActivity = lastActivity,
    )

  // ── threshold ────────────────────────────────────────────────────────────────

  test("threshold: below minimum → no settle") {
    val p = SettlementPolicy.threshold(BigInt(1_000_000))
    assert(!p.shouldSettle(state(offChainPaid = BigInt(999_999))))
  }

  test("threshold: at minimum → settle") {
    val p = SettlementPolicy.threshold(BigInt(1_000_000))
    assert(p.shouldSettle(state(offChainPaid = BigInt(1_000_000))))
  }

  test("threshold: above minimum → settle") {
    val p = SettlementPolicy.threshold(BigInt(1_000_000))
    assert(p.shouldSettle(state(offChainPaid = BigInt(2_000_000))))
  }

  // ── onClose ──────────────────────────────────────────────────────────────────

  test("onClose: never triggers automatically") {
    assert(!SettlementPolicy.onClose.shouldSettle(state(offChainPaid = BigInt(Long.MaxValue))))
  }

  // ── timeInterval ─────────────────────────────────────────────────────────────

  test("timeInterval: no lastActivity → no settle") {
    val p = SettlementPolicy.timeInterval(1.second)
    assert(!p.shouldSettle(state(lastActivity = None)))
  }

  test("timeInterval: recent activity → no settle") {
    val p = SettlementPolicy.timeInterval(1.hour)
    assert(!p.shouldSettle(state(lastActivity = Some(Instant.now()))))
  }

  test("timeInterval: old activity → settle") {
    val p = SettlementPolicy.timeInterval(1.second)
    val old = Instant.now().minusSeconds(5)
    assert(p.shouldSettle(state(lastActivity = Some(old))))
  }

  // ── probabilistic ─────────────────────────────────────────────────────────────

  test("probabilistic(0.0): never settles") {
    val p = SettlementPolicy.probabilistic(0.0)
    assert((1 to 100).forall(_ => !p.shouldSettle(state())))
  }

  test("probabilistic(1.0): always settles") {
    val p = SettlementPolicy.probabilistic(1.0)
    assert((1 to 100).forall(_ => p.shouldSettle(state())))
  }

  // ── any ──────────────────────────────────────────────────────────────────────

  test("any: false + false → false") {
    val p = SettlementPolicy.any(SettlementPolicy.onClose, SettlementPolicy.onClose)
    assert(!p.shouldSettle(state()))
  }

  test("any: false + true → true") {
    val p = SettlementPolicy.any(
      SettlementPolicy.onClose,
      SettlementPolicy.threshold(BigInt(0)),
    )
    assert(p.shouldSettle(state()))
  }

  test("any: true + false → true") {
    val p = SettlementPolicy.any(
      SettlementPolicy.threshold(BigInt(0)),
      SettlementPolicy.onClose,
    )
    assert(p.shouldSettle(state()))
  }

  // ── all ──────────────────────────────────────────────────────────────────────

  test("all: true + true → true") {
    val p = SettlementPolicy.all(
      SettlementPolicy.threshold(BigInt(0)),
      SettlementPolicy.probabilistic(1.0),
    )
    assert(p.shouldSettle(state()))
  }

  test("all: true + false → false") {
    val p = SettlementPolicy.all(
      SettlementPolicy.threshold(BigInt(0)),
      SettlementPolicy.onClose,
    )
    assert(!p.shouldSettle(state()))
  }

  test("all: false + true → false") {
    val p = SettlementPolicy.all(
      SettlementPolicy.onClose,
      SettlementPolicy.threshold(BigInt(0)),
    )
    assert(!p.shouldSettle(state()))
  }

  // ── composition ──────────────────────────────────────────────────────────────

  test("any(all(threshold, probabilistic(1.0)), onClose): threshold met → settle") {
    val p = SettlementPolicy.any(
      SettlementPolicy.all(
        SettlementPolicy.threshold(BigInt(500_000)),
        SettlementPolicy.probabilistic(1.0),
      ),
      SettlementPolicy.onClose,
    )
    assert(p.shouldSettle(state(offChainPaid = BigInt(500_000))))
  }

  test("any(all(threshold, probabilistic(1.0)), onClose): threshold not met → no settle") {
    val p = SettlementPolicy.any(
      SettlementPolicy.all(
        SettlementPolicy.threshold(BigInt(500_000)),
        SettlementPolicy.probabilistic(1.0),
      ),
      SettlementPolicy.onClose,
    )
    assert(!p.shouldSettle(state(offChainPaid = BigInt(100_000))))
  }
