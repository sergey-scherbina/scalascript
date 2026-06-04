package scalascript.payments.bankrails

import java.time.Instant

/** UUID v4 assigned at SWIFT initiation; unique across the entire GPI network.
 *  Format: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
 *  See specs/international-bank-rails.md §4.3. */
opaque type Uetr = String
object Uetr:
  def generate(): Uetr        = java.util.UUID.randomUUID().toString
  def apply(s: String): Uetr  = s
  def isValid(s: String): Boolean =
    s.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
  extension (u: Uetr)
    def value: String       = u
    def valid: Boolean      = Uetr.isValid(u)

/** SWIFT charge-bearer instruction (ISO 20022 ChrgBr field).
 *  SHA is the most common default.
 *  See specs/international-bank-rails.md §3.4. */
enum ChargeBearer:
  case OUR  // sender pays all intermediary + receiving-bank charges
  case SHA  // sender pays sending bank; receiver pays rest (default)
  case BEN  // receiver bears all charges; received amount may differ

/** One hop in a SWIFT GPI tracker chain.
 *  Populated from pacs.002 status updates delivered by the GPI tracker webhook.
 *  See specs/international-bank-rails.md §3.1. */
case class GpiHop(
  agentBic:     String,                        // BIC of the institution at this hop
  status:       String,                        // ACSP / ACCC / RJCT (pacs.002 status codes)
  updatedAt:    Instant,
  debitAmount:  Option[scalascript.payments.money.Money] = None,   // amount debited at this hop
  creditAmount: Option[scalascript.payments.money.Money] = None,   // amount credited at this hop
)
