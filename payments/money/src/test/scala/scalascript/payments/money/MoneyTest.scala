package scalascript.payments.money

import org.scalatest.funsuite.AnyFunSuite

class MoneyTest extends AnyFunSuite:

  test("Currency.minorUnitsPower: USD → 2"):
    assert(Currency.minorUnitsPower(Currency.USD) == 2)

  test("Currency.minorUnitsPower: JPY → 0"):
    assert(Currency.minorUnitsPower(Currency.JPY) == 0)

  test("Currency.minorUnitsPower: KWD → 3"):
    assert(Currency.minorUnitsPower(Currency.KWD) == 3)

  test("Currency.minorUnitsPower: BTC → 8"):
    assert(Currency.minorUnitsPower(Currency.BTC) == 8)

  test("Currency.apply: known code accepted"):
    assert(Currency("usd").code == "USD")

  test("Currency.apply: unknown code throws"):
    intercept[IllegalArgumentException] { Currency("XYZ") }

  test("Money.apply(BigDecimal): rounds to minor units"):
    val m = Money(BigDecimal("49.99"), Currency.USD)
    assert(m.minorUnits == 4999L)

  test("Money.apply(BigDecimal, JPY): no sub-units"):
    val m = Money(BigDecimal("500"), Currency.JPY)
    assert(m.minorUnits == 500L)

  test("Money.toDecimal: USD"):
    val m = Money(4999L, Currency.USD)
    assert(m.toDecimal == BigDecimal("49.99"))

  test("Money.+: same currency"):
    val a = Money(1000L, Currency.USD)
    val b = Money(500L, Currency.USD)
    assert((a + b).minorUnits == 1500L)

  test("Money.+: different currency throws CurrencyMismatch"):
    val a = Money(1000L, Currency.USD)
    val b = Money(1000L, Currency.EUR)
    intercept[CurrencyMismatch] { a + b }

  test("Money.-: subtraction"):
    val a = Money(2000L, Currency.USD)
    val b = Money(500L, Currency.USD)
    assert((a - b).minorUnits == 1500L)

  test("Money.*: 20% VAT on $49.99 → $10.00 HALF_EVEN"):
    val subtotal = Money(4999L, Currency.USD)
    val tax      = subtotal * BigDecimal("0.2")
    assert(tax.minorUnits == 1000L)

  test("Money.unary_-"):
    assert((-Money(100L, Currency.USD)).minorUnits == -100L)

  test("Money.allocate: $1.00 across 3 equal parts"):
    val parts = Money.allocate(Money(100L, Currency.USD), List(BigDecimal(1), BigDecimal(1), BigDecimal(1)))
    assert(parts.map(_.minorUnits).sum == 100L)
    assert(parts.map(_.minorUnits).sorted == List(33L, 33L, 34L))

  test("Money.allocate: no remainder lost"):
    val parts = Money.allocate(Money(1000L, Currency.USD), List(BigDecimal(1), BigDecimal(1)))
    assert(parts.map(_.minorUnits).sum == 1000L)
    assert(parts.forall(_.minorUnits == 500L))

  test("Money.zero"):
    assert(Money.zero(Currency.EUR).minorUnits == 0L)

  test("Money.comparison: < and >"):
    val a = Money(100L, Currency.USD)
    val b = Money(200L, Currency.USD)
    assert(a < b)
    assert(b > a)
    assert(!(a > b))

  test("Money.comparison: currency mismatch throws"):
    val a = Money(100L, Currency.USD)
    val b = Money(100L, Currency.EUR)
    intercept[CurrencyMismatch] { a < b }
