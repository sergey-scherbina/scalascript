package scalascript.payments.fx

import scalascript.payments.money.Currency

/** An ordered pair of currencies representing an FX direction.
 *
 *  `CurrencyPair(from, to)` means "how many units of `to` per one unit of `from`".
 *
 *  Uses `Currency` opaque type from `scalascript.payments.money`.
 */
case class CurrencyPair(from: Currency, to: Currency):
  override def toString: String = s"${from.code}/${to.code}"
