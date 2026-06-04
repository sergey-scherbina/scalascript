package scalascript.payments.bankrails

/** Proxy type for a Singapore PayNow recipient.
 *
 *  PayNow uses proxy-based addressing: instead of a bank account number, the
 *  payer provides a proxy (mobile number, NRIC/FIN, UEN, or VPA) which is
 *  resolved to the recipient's bank account via the PayNow proxy directory.
 *
 *  The resolved proxy is stored in `BankAccount.paynowProxy`.  The proxy type
 *  determines how the aggregator routes the proxy resolution request to the
 *  PayNow directory.
 *
 *  See docs/specs/international-bank-rails.md §v1.56.8.
 */
enum PayNowProxyType:
  /** Singapore mobile number in international format, e.g. "+6591234567". */
  case Mobile
  /** Singapore NRIC (citizen/PR) or FIN (foreigner), e.g. "S1234567A". */
  case NricFin
  /** Unique Entity Number — identifies a business registered with ACRA, e.g. "201912345K". */
  case Uen
  /** Virtual Payment Address — `name@handle` format, similar to UPI VPA. */
  case Vpa

object PayNowProxyType:
  /** Convert a `PayNowProxyType` to the wire string expected by the aggregator API. */
  def toWireString(pt: PayNowProxyType): String = pt match
    case Mobile  => "MOBILE"
    case NricFin => "NRIC"
    case Uen     => "UEN"
    case Vpa     => "VPA"

  /** Parse a wire string from an aggregator response back to a `PayNowProxyType`.
   *  Returns `None` for unrecognised strings. */
  def fromWireString(s: String): Option[PayNowProxyType] = s.toUpperCase match
    case "MOBILE" | "PHONE" => Some(Mobile)
    case "NRIC"   | "FIN"   => Some(NricFin)
    case "UEN"              => Some(Uen)
    case "VPA"              => Some(Vpa)
    case _                  => None
