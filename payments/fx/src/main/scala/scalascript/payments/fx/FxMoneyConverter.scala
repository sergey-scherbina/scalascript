package scalascript.payments.fx

import scalascript.payments.money.{Currency, Money}
import scala.concurrent.{Future, ExecutionContext}

/** Utility that converts `Money` values using a backing `FxProvider`.
 *
 *  `convert`    — single-amount conversion.
 *  `convertAll` — convert a list of amounts all to the same target currency,
 *                 batching the rate lookups where the provider supports it.
 *
 *  @param provider the `FxProvider` to use for rate lookup
 */
class FxMoneyConverter(provider: FxProvider):

  /** Convert a single `Money` amount to the target currency.
   *
   *  Short-circuits immediately if `money.currency == to`, otherwise delegates
   *  to `provider.convert(money, to)`.
   *
   *  @param money  amount to convert
   *  @param to     target currency
   *  @return       converted amount in `to`
   */
  def convert(money: Money, to: Currency)(using ExecutionContext): Future[Money] =
    if money.currency == to then Future.successful(money)
    else provider.convert(money, to)

  /** Convert a list of `Money` amounts all to the same target currency.
   *
   *  Collects the distinct currency pairs, fetches rates in one batch via
   *  `provider.getRates`, then applies each rate.  Amounts already in `to`
   *  are returned unchanged without a rate lookup.
   *
   *  @param moneys  amounts to convert (may mix currencies)
   *  @param to      common target currency
   *  @return        converted amounts in the same order as input
   */
  def convertAll(moneys: List[Money], to: Currency)(using ExecutionContext): Future[List[Money]] =
    // Short-circuit: nothing to do if the list is empty
    if moneys.isEmpty then return Future.successful(Nil)

    // Collect pairs that need a rate lookup
    val pairs: Set[CurrencyPair] = moneys
      .filter(_.currency != to)
      .map(m => CurrencyPair(m.currency, to))
      .toSet

    if pairs.isEmpty then
      // All amounts are already in `to`
      Future.successful(moneys)
    else
      provider.getRates(pairs).flatMap { rateMap =>
        val results = moneys.map { m =>
          if m.currency == to then Future.successful(m)
          else
            val pair = CurrencyPair(m.currency, to)
            rateMap.get(pair) match
              case Some(rate) =>
                Future.successful(Money(m.toDecimal * rate.mid, to))
              case None =>
                Future.failed(FxError.RateUnavailable(m.currency, to))
        }
        Future.sequence(results)
      }
