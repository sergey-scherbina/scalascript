package scalascript.payments.money

import java.math.RoundingMode

case class CurrencyMismatch(left: Currency, right: Currency)
    extends RuntimeException(s"Cannot operate on amounts in different currencies: ${left.code} vs ${right.code}")

case class Money(minorUnits: Long, currency: Currency):

  def +(other: Money): Money =
    if other.currency != currency then throw CurrencyMismatch(currency, other.currency)
    Money(Math.addExact(minorUnits, other.minorUnits), currency)

  def -(other: Money): Money =
    if other.currency != currency then throw CurrencyMismatch(currency, other.currency)
    Money(Math.subtractExact(minorUnits, other.minorUnits), currency)

  def *(factor: BigDecimal, mode: RoundingMode = RoundingMode.HALF_EVEN): Money =
    Money(
      (new java.math.BigDecimal(minorUnits)).multiply(factor.bigDecimal).setScale(0, mode).longValueExact(),
      currency
    )

  def /(divisor: BigDecimal, mode: RoundingMode = RoundingMode.HALF_EVEN): Money =
    Money(
      (new java.math.BigDecimal(minorUnits)).divide(divisor.bigDecimal, 0, mode).longValueExact(),
      currency
    )

  def unary_- : Money = Money(Math.negateExact(minorUnits), currency)

  def toDecimal: BigDecimal =
    BigDecimal(new java.math.BigDecimal(minorUnits).scaleByPowerOfTen(-Currency.minorUnitsPower(currency)))

  def format(locale: java.util.Locale): String =
    val fmt = java.text.NumberFormat.getCurrencyInstance(locale)
    scala.util.Try(fmt.setCurrency(java.util.Currency.getInstance(currency.code))).recover(_ => ()).get
    fmt.format(toDecimal.doubleValue)

  def <(other: Money): Boolean  = { requireSame(other); minorUnits < other.minorUnits }
  def <=(other: Money): Boolean = { requireSame(other); minorUnits <= other.minorUnits }
  def >(other: Money): Boolean  = { requireSame(other); minorUnits > other.minorUnits }
  def >=(other: Money): Boolean = { requireSame(other); minorUnits >= other.minorUnits }

  private def requireSame(other: Money): Unit =
    if other.currency != currency then throw CurrencyMismatch(currency, other.currency)

  override def toString: String = s"${toDecimal} ${currency.code}"

object Money:

  def apply(amount: BigDecimal, currency: Currency): Money =
    val power   = Currency.minorUnitsPower(currency)
    val scaled  = amount.bigDecimal.setScale(power, RoundingMode.HALF_EVEN)
    val shifted = scaled.scaleByPowerOfTen(power).setScale(0, RoundingMode.UNNECESSARY)
    Money(shifted.longValueExact(), currency)

  def zero(currency: Currency): Money = Money(0L, currency)

  def allocate(total: Money, ratios: List[BigDecimal]): List[Money] =
    if ratios.isEmpty then return Nil
    val totalBD  = new java.math.BigDecimal(total.minorUnits)
    val ratioSum = ratios.map(_.bigDecimal).foldLeft(java.math.BigDecimal.ZERO)(_.add(_))
    val base     = ratios.map { r =>
      totalBD.multiply(r.bigDecimal).divide(ratioSum, 0, RoundingMode.FLOOR).longValueExact()
    }
    val rem = total.minorUnits - base.sum
    val fractions = ratios.zipWithIndex
      .map { (r, i) =>
        val exact = totalBD.multiply(r.bigDecimal).divide(ratioSum, 20, RoundingMode.HALF_EVEN)
        val frac  = exact.subtract(new java.math.BigDecimal(base(i)))
        (BigDecimal(frac), i)
      }
      .sortBy(-_._1)
      .take(rem.toInt)
      .map(_._2)
      .toSet
    base.zipWithIndex.map { (s, i) => Money(if fractions.contains(i) then s + 1 else s, total.currency) }
