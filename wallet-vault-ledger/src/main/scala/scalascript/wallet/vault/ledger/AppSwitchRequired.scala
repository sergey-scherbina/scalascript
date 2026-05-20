package scalascript.wallet.vault.ledger

/** Raised by the Ledger Vault when the device's currently-open
 *  on-device app does not match the chain / curve being requested.
 *  Only one Ledger app is active at a time, so the host must surface
 *  this to the user ("open the Ethereum app on your Ledger") and
 *  retry the signing operation after the user switches.
 *
 *  See docs/wallet-spi.md §5.1 ("App switching UX"). */
final case class AppSwitchRequired(currentApp: String, requiredApp: String)
  extends RuntimeException(
    s"Ledger app mismatch: currently '$currentApp', need '$requiredApp'. " +
    s"Open the '$requiredApp' app on the device and retry."
  )
