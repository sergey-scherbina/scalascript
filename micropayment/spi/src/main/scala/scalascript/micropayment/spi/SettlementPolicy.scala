package scalascript.micropayment.spi

trait SettlementPolicy:
  def shouldSettle(state: ChannelState): Boolean

object SettlementPolicy:
  def threshold(minAmount: BigInt): SettlementPolicy =
    state => state.offChainPaid >= minAmount

  def timeInterval(d: scala.concurrent.duration.Duration): SettlementPolicy =
    state =>
      state.lastActivity.exists { t =>
        java.time.Instant.now().toEpochMilli - t.toEpochMilli >= d.toMillis
      }

  val onClose: SettlementPolicy = _ => false

  def probabilistic(p: Double): SettlementPolicy =
    _ => scala.util.Random.nextDouble() < p

  def any(ps: SettlementPolicy*): SettlementPolicy =
    state => ps.exists(_.shouldSettle(state))

  def all(ps: SettlementPolicy*): SettlementPolicy =
    state => ps.forall(_.shouldSettle(state))
