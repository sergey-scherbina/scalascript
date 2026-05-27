package scalascript.payments.fx

import scalascript.payments.money.Currency
import java.time.Instant

/** A foreign-exchange rate snapshot.
 *
 *  @param from      source currency
 *  @param to        target currency
 *  @param rate      mid-rate (same as `mid`); kept for backwards compat
 *  @param mid       mid-market rate (average of bid and ask, or sole quote)
 *  @param bid       bid rate (what the market buys `from` for) — None if unavailable
 *  @param ask       ask rate (what the market sells `from` for) — None if unavailable
 *  @param timestamp when this rate was last refreshed
 */
case class FxRate(
  from:      Currency,
  to:        Currency,
  rate:      BigDecimal,
  mid:       BigDecimal,
  bid:       Option[BigDecimal],
  ask:       Option[BigDecimal],
  timestamp: Instant,
)

object FxRate:
  /** Convenience constructor for a mid-only rate (ECB / OER style). */
  def mid(from: Currency, to: Currency, midRate: BigDecimal, timestamp: Instant): FxRate =
    FxRate(
      from      = from,
      to        = to,
      rate      = midRate,
      mid       = midRate,
      bid       = None,
      ask       = None,
      timestamp = timestamp,
    )
