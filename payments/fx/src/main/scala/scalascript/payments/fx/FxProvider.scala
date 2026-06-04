package scalascript.payments.fx

import scalascript.payments.money.{Currency, Money}
import scala.concurrent.{Future, ExecutionContext}

/** SPI for foreign-exchange rate lookup and money conversion.
 *
 *  Adapters implement this trait and register via `FxPlugin` / ServiceLoader.
 *  Built-in adapters:
 *   - `EcbFxProvider`  — ECB daily reference rates (EUR base, 1-hour TTL)
 *   - `OerFxProvider`  — Open Exchange Rates API v6 (USD base, 1-hour TTL)
 *
 *  All methods return `Future` so network-bound adapters can be non-blocking.
 *  The `ExecutionContext` is passed explicitly as a `using` parameter so callers
 *  retain full control over the threading model.
 *
 *  Implementations must be thread-safe (lazy-cache with synchronized refresh).
 *
 *  See `docs/specs/traditional-payments.md §FxProvider`.
 */
trait FxProvider:

  /** Unique identifier for this adapter, e.g. "ecb" or "openexchangerates". */
  def id: String

  /** Human-readable name for logging / diagnostics. */
  def displayName: String

  /** Look up the exchange rate from one currency to another.
   *
   *  @param from source currency
   *  @param to   target currency
   *  @return     `FxRate` with mid/bid/ask and timestamp
   *  @throws     `FxError.RateUnavailable` (wrapped in failed Future) if the pair is not known
   */
  def getRate(from: Currency, to: Currency)(using ExecutionContext): Future[FxRate]

  /** Convert a `Money` amount to the target currency.
   *
   *  Uses `getRate(money.currency, to)` and multiplies by the mid rate.
   *
   *  @param money amount to convert
   *  @param to    target currency
   *  @return      converted `Money` rounded via HALF_EVEN
   */
  def convert(money: Money, to: Currency)(using ExecutionContext): Future[Money]

  /** Look up multiple currency pairs in a single call.
   *
   *  Adapters may batch the network request; the default implementation
   *  fans out to `getRate` sequentially.
   *
   *  @param pairs set of `(from, to)` pairs to look up
   *  @return      map from pair to rate; pairs not found are absent (not failed)
   */
  def getRates(pairs: Set[CurrencyPair])(using ExecutionContext): Future[Map[CurrencyPair, FxRate]]
