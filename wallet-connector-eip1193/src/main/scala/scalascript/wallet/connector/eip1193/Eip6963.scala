package scalascript.wallet.connector.eip1193

/** EIP-6963: multi-injected-provider discovery. Browsers historically
 *  exposed a single `window.ethereum`; with multiple wallets installed
 *  this collides. EIP-6963 lets each wallet announce itself via a
 *  `eip6963:announceProvider` window event carrying a structured
 *  metadata payload; dApps listen for `eip6963:requestProvider` to
 *  trigger announcements.
 *
 *  This module ships the cross-platform value types. The Scala.js
 *  browser-side glue (firing the DOM events) lands in the JS-only
 *  follow-up; the JVM tests only verify the value shape. */

/** Provider metadata bundled in `eip6963:announceProvider` detail. */
case class Eip6963ProviderInfo(
  /** UUIDv4. Stable per wallet install for the lifetime of the page. */
  uuid: String,
  /** Human-readable wallet name ("MetaMask", "Rabby", …). */
  name: String,
  /** Data-URI icon (typically image/svg+xml or image/png). */
  icon: String,
  /** Reverse-DNS identifier, e.g. "io.metamask", "com.coinbase.wallet". */
  rdns: String,
)

/** The full announce payload — `info` plus a handle to the underlying
 *  EIP-1193 provider. On the JS side the provider is the bound JS
 *  object the dApp calls `.request({...})` on. */
case class Eip6963Announcement(
  info:     Eip6963ProviderInfo,
  provider: Eip1193Provider,
)
