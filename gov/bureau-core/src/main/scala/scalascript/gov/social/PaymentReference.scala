package scalascript.gov.social

import scalascript.gov.BusinessEntity
import scalascript.payments.money.Money
import java.time.{LocalDate, YearMonth}

case class PaymentReference(
  accountNumber: String,
  amount:        Money,
  dueDate:       LocalDate,
  period:        YearMonth,
  description:   String,
  metadata:      Map[String, String] = Map.empty
)

case class ContributionParams(
  entity:           BusinessEntity,
  period:           YearMonth,
  baseAmount:       Money,
  contributionBase: ContributionBase
)

enum ContributionBase:
  case Employee(record: EmployeeRecord)
  case SelfEmployed
  case PreferentialBase

case class ContributionCalculation(
  period:     YearMonth,
  pension:    Money,
  disability: Money,
  sickness:   Money,
  accident:   Money,
  health:     Money,
  laborFund:  Money,
  fgsp:       Money,
  total:      Money
)
