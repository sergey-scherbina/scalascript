package scalascript.gov.pl.social

/** ZUS NRB bank account number generator.
 *
 *  ZUS account numbers for social contribution payments are deterministic:
 *  derived from the payer's NIP and contribution type using the ISO 7064
 *  MOD-97-10 check digit algorithm (same as IBAN).
 *
 *  Reference: ZUS official formula published at www.zus.pl/przelew
 *
 *  NRB format: 2 check digits + 8-digit sort code + 16-digit account number
 *  Total: 26 digits (PL standard), prefixed with "PL" for IBAN = 28 chars.
 *
 *  ZUS uses dedicated settlement accounts per NIP + contribution type.
 *  The 8-digit sort code identifies the ZUS branch handling the account.
 *  The 16-digit account part encodes the NIP and contribution type code. */
object ZusNrbGenerator:

  /** ZUS sort codes per contribution type (published by ZUS). */
  private val sortCodes: Map[ContributionType, String] = Map(
    ContributionType.SocialInsurance         -> "10101023",
    ContributionType.HealthInsurance         -> "10101024",
    ContributionType.LaborFund               -> "10101025",
    ContributionType.FunduszGwarantowanych   -> "10101026",
  )

  /** Contribution-type code suffix (2 digits, ZUS specification). */
  private val typeCodes: Map[ContributionType, String] = Map(
    ContributionType.SocialInsurance         -> "52",
    ContributionType.HealthInsurance         -> "51",
    ContributionType.LaborFund               -> "52",
    ContributionType.FunduszGwarantowanych   -> "52",
  )

  /** Generates a ZUS NRB account number for a given NIP and contribution type.
   *  @param nip 10-digit NIP (digits only, no dashes)
   *  @param ctype contribution type (selects the ZUS sub-account)
   *  @return 26-digit NRB (without "PL" IBAN country prefix) */
  def generate(nip: String, ctype: ContributionType): String =
    val sortCode  = sortCodes.getOrElse(ctype, "10101023")
    val typeCode  = typeCodes.getOrElse(ctype, "52")
    val nipDigits = nip.filter(_.isDigit).padTo(10, '0').take(10)
    val accountPart = s"${nipDigits}${typeCode}0000"    // 16 digits: 10 NIP + 2 typeCode + 4 zeros
    val raw28     = s"${sortCode}${accountPart}"        // 24 digits base
    val checkInput = s"${raw28}252100"                  // PL→2521, then "00" placeholder per IBAN MOD-97
    val check     = 98 - (BigInt(checkInput) % 97).toInt
    val checkStr  = f"$check%02d"
    s"$checkStr$sortCode$accountPart"

  /** Returns the full IBAN (PL prefix + 26 digits). */
  def generateIban(nip: String, ctype: ContributionType): String =
    s"PL${generate(nip, ctype)}"

enum ContributionType:
  case SocialInsurance
  case HealthInsurance
  case LaborFund
  case FunduszGwarantowanych
