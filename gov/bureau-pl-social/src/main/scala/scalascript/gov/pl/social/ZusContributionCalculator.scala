package scalascript.gov.pl.social

import scalascript.gov.social.*
import scalascript.payments.money.Money

/** Replicates the ZUS contribution calculation formula.
 *
 *  Rates (2024, published by ZUS):
 *  - Pension (emerytalne):  9.76% employee + 9.76% employer = 19.52% base
 *  - Disability (rentowe):  1.50% employee + 6.50% employer = 8.00% base
 *  - Sickness (chorobowe):  2.45% employee only (voluntary for self-employed)
 *  - Accident (wypadkowe):  1.67% employer only (varies by sector; use standard rate)
 *  - Health (zdrowotne NFZ): 9.00% of assessment base (deducted from PIT/CIT)
 *  - Labor Fund (FP):       2.45% employer only
 *  - FGŚP:                  0.10% employer only
 *
 *  For employees: base = gross salary.
 *  For self-employed (JDG): base = 60% of national average salary (standard)
 *    or actual income for "mały ZUS plus" (PreferentialBase). */
object ZusContributionCalculator:

  /** ZUS rates for 2024 (combined employer + employee). */
  val PensionRate:    BigDecimal = BigDecimal("0.1952") // emerytalne
  val DisabilityRate: BigDecimal = BigDecimal("0.0800") // rentowe
  val SicknessRate:   BigDecimal = BigDecimal("0.0245") // chorobowe (employee share)
  val AccidentRate:   BigDecimal = BigDecimal("0.0167") // wypadkowe
  val HealthRate:     BigDecimal = BigDecimal("0.0900") // zdrowotne
  val LaborFundRate:  BigDecimal = BigDecimal("0.0245") // FP
  val FgspRate:       BigDecimal = BigDecimal("0.0010") // FGŚP

  def calculate(params: ContributionParams): ContributionCalculation =
    val base = params.baseAmount.minorUnits
    val cur  = params.baseAmount.currency

    def contrib(rate: BigDecimal): Money =
      Money((base * rate).setScale(0, scala.math.BigDecimal.RoundingMode.HALF_UP).toLong, cur)

    val pension    = contrib(PensionRate)
    val disability = contrib(DisabilityRate)
    val sickness   = contrib(SicknessRate)
    val accident   = contrib(AccidentRate)
    val health     = contrib(HealthRate)
    val laborFund  = contrib(LaborFundRate)
    val fgsp       = contrib(FgspRate)
    val total      = Money(pension.minorUnits + disability.minorUnits + sickness.minorUnits +
                           accident.minorUnits + health.minorUnits + laborFund.minorUnits +
                           fgsp.minorUnits, cur)
    ContributionCalculation(
      period     = params.period,
      pension    = pension,
      disability = disability,
      sickness   = sickness,
      accident   = accident,
      health     = health,
      laborFund  = laborFund,
      fgsp       = fgsp,
      total      = total,
    )
