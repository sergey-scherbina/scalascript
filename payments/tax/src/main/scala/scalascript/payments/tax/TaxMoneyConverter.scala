package scalascript.payments.tax

import scalascript.payments.money.Money
import scala.concurrent.{Future, ExecutionContext}

/** Convenience wrapper that runs a tax calculation and returns the total tax amount.
 *
 *  For callers that only need the total tax without the full per-line breakdown.
 *
 *  @param provider the `TaxProvider` to use for calculations
 */
class TaxMoneyConverter(provider: TaxProvider):

  /** Calculate the total tax for a transaction and return just the tax `Money`.
   *
   *  @param req  tax request
   *  @return     total tax amount
   */
  def totalTax(req: TaxRequest)(using ExecutionContext): Future[Money] =
    provider.calculateTax(req).map(_.totalTax)

  /** Calculate the total amount (pre-tax + tax) for a transaction.
   *
   *  @param req  tax request
   *  @return     total amount including tax
   */
  def totalWithTax(req: TaxRequest)(using ExecutionContext): Future[Money] =
    provider.calculateTax(req).map(_.totalAmount)

  /** Calculate the effective tax rate (totalTax / preTaxTotal) for a transaction.
   *
   *  Returns `BigDecimal(0)` if all line items are tax-exempt or the total is zero.
   *
   *  @param req  tax request
   *  @return     effective tax rate (0.0725 = 7.25%)
   */
  def effectiveTaxRate(req: TaxRequest)(using ExecutionContext): Future[BigDecimal] =
    provider.calculateTax(req).map { quote =>
      val preTax = quote.lineItems.foldLeft(BigDecimal(0)) { (acc, item) =>
        acc + item.amount.toDecimal
      }
      if preTax == BigDecimal(0) then BigDecimal(0)
      else quote.totalTax.toDecimal / preTax
    }
