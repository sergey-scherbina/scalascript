package scalascript.wallet.ledger

/** Thrown when the user declined the operation on the Ledger device
 *  (status word `0x6985`). */
final class LedgerUserDeclinedException(context: String)
  extends RuntimeException(
    s"User declined on Ledger device during $context (sw=0x6985)"
  )

object LedgerUserDeclinedException:
  def apply(context: String): LedgerUserDeclinedException =
    new LedgerUserDeclinedException(context)

/** Thrown when the device returns status word `0x6700` (wrong data length /
 *  invalid `Lc`). */
final class LedgerInvalidLengthException(context: String, sw: Int)
  extends RuntimeException(
    s"Ledger invalid length during $context: sw=${Apdu.swHex(sw)}"
  )

object LedgerInvalidLengthException:
  def apply(context: String, sw: Int): LedgerInvalidLengthException =
    new LedgerInvalidLengthException(context, sw)
