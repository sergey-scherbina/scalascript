package scalascript.wallet.connector.walletstd

import scala.concurrent.ExecutionContext
import scala.scalajs.js

/** JS-native wallet-info shape passed to [[WalletStandardJs.register]].
 *
 *  Intentionally uses `js.Array` / `js.Dictionary` so host JS callers
 *  can construct it with plain object literals without needing Scala
 *  collection conversions.  Example:
 *
 *  {{{
 *  val info = js.Dynamic.literal(
 *    name     = "My Wallet",
 *    icon     = "data:image/svg+xml;base64,...",
 *    chains   = js.Array("solana:mainnet"),
 *    features = js.Dictionary.empty,
 *  ).asInstanceOf[WalletInfo]
 *  }}}
 */
trait WalletInfo extends js.Object:
  /** Human-readable wallet name shown in dApp wallet-picker UIs. */
  val name:     String
  /** Data URI of the wallet icon (PNG or SVG, base64-encoded).
   *  Minimum recommended size: 96×96 px. */
  val icon:     String
  /** CAIP-2 chain IDs this wallet supports, e.g. `"solana:mainnet"`. */
  val chains:   js.Array[String]
  /** Opaque feature-override map — merged into the Wallet Standard
   *  `features` property.  Pass `js.Dictionary.empty` or `{}` to accept
   *  all defaults. */
  val features: js.Dictionary[js.Any]

/** Entry point for Solana Wallet Standard browser registration.
 *
 *  `WalletStandardJs.register` wraps a [[WalletStandardConnectorBase]]
 *  in the Wallet Standard event/object protocol and announces it to the
 *  current browser page so dApps using `@wallet-standard/core` pick it up
 *  automatically.
 *
 *  The announcement fires two paths (both are idempotent):
 *
 *  1. **Pull** — dispatches `wallet-standard:register-wallet` on `window`
 *     carrying a `register(callback)` closure.  The Wallet Standard runtime
 *     calls `callback(walletObject)` to receive the wallet.
 *  2. **Push** — calls `window.standard.wallets.registerWallet(walletObject)`
 *     directly for dApps still using the older convention.
 *
 *  Relationship to [[WalletStandardRegister]]:  this object is a thin
 *  JS-idiomatic facade over [[WalletStandardRegister]].  It accepts the
 *  browser-native [[WalletInfo]] shape and converts it to the Scala-side
 *  [[WalletStandardInfo]] before delegating.  Browser hosts that prefer
 *  the Scala API may use [[WalletStandardRegister]] directly; JS host
 *  scripts should use this entry point. */
object WalletStandardJs:

  /** Register `connector` as a Wallet Standard wallet in the current browser
   *  page.
   *
   *  @param info      JS wallet-info object (name, icon, chains, features).
   *  @param connector Underlying [[WalletStandardConnectorBase]] that handles
   *                   `standard:connect`, `solana:signMessage`, etc.
   *  @param ec        ExecutionContext used for async feature handlers.
   *  @return The Wallet Standard `Wallet` JS object that was registered (useful
   *          for testing and introspection). */
  def register(info: WalletInfo, connector: WalletStandardConnectorBase)(
    using ec: ExecutionContext,
  ): js.Dynamic =
    val scalaInfo = WalletStandardInfo(
      name     = info.name,
      icon     = info.icon,
      chains   = (0 until info.chains.length).map(info.chains(_)),
      accounts = Seq.empty,
    )
    new WalletStandardRegister(connector, scalaInfo).register()

/** Scala.js feature-object bridge: wraps a [[WalletStandardConnectorBase]]
 *  in the five Wallet Standard feature contracts and exposes them as a
 *  plain JS `features` dictionary.
 *
 *  The feature objects follow the `@wallet-standard/core` shape:
 *
 *  {{{
 *  {
 *    "standard:connect":              { version, connect }
 *    "standard:disconnect":           { version, disconnect }
 *    "solana:signMessage":            { version, signMessage }
 *    "solana:signTransaction":        { version, signTransaction }
 *    "solana:signAndSendTransaction": { version, signAndSendTransaction }
 *  }
 *  }}}
 *
 *  Callers that need fine-grained feature access (e.g. building a custom
 *  wallet object without using [[WalletStandardRegister]]) can instantiate
 *  this class and read [[featuresJs]] directly. */
class StandardWalletConnectorJs(
  val connector: WalletStandardConnectorBase,
)(using ec: ExecutionContext):

  /** Build and return the complete Wallet Standard features JS object.
   *  Delegates to [[WalletStandardRegister]] — a short-lived instance is
   *  created here solely to reuse its `featuresJs` logic.  Extracted into
   *  its own class so tests can introspect the feature map without going
   *  through the full registration flow. */
  def featuresJs: js.Dynamic =
    // Reuse WalletStandardRegister as a builder: give it a sentinel info
    // object (name/icon don't matter for features) and extract `featuresJs`.
    // The `walletJs` lazy val triggers feature construction.
    val sentinel = WalletStandardInfo(
      name     = "",
      icon     = "",
      chains   = Seq.empty,
      accounts = Seq.empty,
    )
    val reg = new WalletStandardRegister(connector, sentinel)
    reg.walletJs.features.asInstanceOf[js.Dynamic]
