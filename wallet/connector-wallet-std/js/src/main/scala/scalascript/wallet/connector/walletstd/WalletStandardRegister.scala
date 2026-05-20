package scalascript.wallet.connector.walletstd

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel
import org.scalajs.dom

/** Scala.js glue that exposes a [[WalletStandardConnector]] as a
 *  `@wallet-standard/core` `Wallet` object on `window`.
 *
 *  Wallet Standard exposes two equivalent registration paths:
 *
 *    1. **Push** — call `window.standard.wallets.registerWallet(wallet)`
 *       directly (older convention; still works with `@wallet-standard/wallet`
 *       attachment helpers).
 *    2. **Pull** — dispatch a `wallet-standard:register-wallet`
 *       `CustomEvent` carrying a callback that the dApp invokes with
 *       the wallet object (current convention, mirrors how EIP-6963
 *       does discovery on the EVM side).
 *
 *  This module fires both, so dApps using either convention pick the
 *  wallet up.  See:
 *
 *    https://github.com/wallet-standard/wallet-standard
 *
 *  for the feature-shape contract. */
class WalletStandardRegister(
  val connector: WalletStandardConnectorBase,
  val info:      WalletStandardInfo,
)(using ec: ExecutionContext):

  /** Register the wallet — fires the event-based path *and* the
   *  legacy `window.standard.wallets.registerWallet` path.  Idempotent
   *  by construction (Wallet Standard treats re-registrations as
   *  updates). */
  def register(): js.Dynamic =
    val wallet = walletJs
    // Pull-style registration (current spec).
    val ev = WalletStandardRegister.customEvent(
      "wallet-standard:register-wallet",
      js.Dynamic.literal(detail = registerCallbackJs(wallet)),
    )
    dom.window.dispatchEvent(ev)
    // Push-style registration (legacy compatibility).
    val standard = dom.window.asInstanceOf[js.Dynamic].standard
    if !js.isUndefined(standard) && !js.isUndefined(standard.wallets) then
      val wallets = standard.wallets
      if !js.isUndefined(wallets.registerWallet) then
        wallets.applyDynamic("registerWallet")(wallet)
    wallet

  /** Build the `Wallet` JS object matching the `@wallet-standard/core`
   *  shape.  Public so hosts can introspect / mutate before
   *  registration (e.g. tests). */
  lazy val walletJs: js.Dynamic =
    js.Dynamic.literal(
      version  = info.version,
      name     = info.name,
      icon     = info.icon,
      chains   = info.chains.toJSArray,
      accounts = info.accounts.toJSArray,
      features = featuresJs,
    )

  private def featuresJs: js.Dynamic =
    val out = js.Dynamic.literal()
    // standard:connect — return the wallet's accounts.
    out.updateDynamic("standard:connect")(js.Dynamic.literal(
      version = "1.0.0",
      connect = (input: js.UndefOr[js.Dynamic]) => callFeature("standard:connect", input.toOption.getOrElse(js.Dynamic.literal()).asInstanceOf[js.Dynamic]),
    ))
    out.updateDynamic("standard:disconnect")(js.Dynamic.literal(
      version    = "1.0.0",
      disconnect = (input: js.UndefOr[js.Dynamic]) => callFeature("standard:disconnect", input.toOption.getOrElse(js.Dynamic.literal()).asInstanceOf[js.Dynamic]),
    ))
    out.updateDynamic("solana:signMessage")(js.Dynamic.literal(
      version     = "1.0.0",
      signMessage = (input: js.Dynamic) => callFeature("solana:signMessage", input),
    ))
    out.updateDynamic("solana:signTransaction")(js.Dynamic.literal(
      version         = "1.0.0",
      signTransaction = (input: js.Dynamic) => callFeature("solana:signTransaction", input),
    ))
    out.updateDynamic("solana:signAndSendTransaction")(js.Dynamic.literal(
      version                = "1.0.0",
      signAndSendTransaction = (input: js.Dynamic) => callFeature("solana:signAndSendTransaction", input),
    ))
    out

  private def callFeature(name: String, input: js.Dynamic): js.Promise[js.Any] =
    val uj = WalletStandardRegister.jsToUjson(input)
    connector.request(name, uj)
      .map(v => WalletStandardRegister.ujsonToJs(v))
      .toJSPromise

  private def registerCallbackJs(wallet: js.Dynamic): js.Function1[js.Function1[js.Dynamic, Any], Unit] =
    (callback: js.Function1[js.Dynamic, Any]) => callback(wallet)

object WalletStandardRegister:

  private[walletstd] def customEvent(name: String, init: js.Dynamic): dom.Event =
    js.Dynamic.newInstance(js.Dynamic.global.CustomEvent)(name, init)
      .asInstanceOf[dom.Event]

  private[walletstd] def jsToUjson(v: js.Any): ujson.Value =
    if v == null || js.isUndefined(v) then ujson.Null
    else js.typeOf(v) match
      case "string"  => ujson.Str(v.asInstanceOf[String])
      case "boolean" => ujson.Bool(v.asInstanceOf[Boolean])
      case "number"  => ujson.Num(v.asInstanceOf[Double])
      case "object" =>
        if js.Array.isArray(v) then
          val arr = v.asInstanceOf[js.Array[js.Any]]
          ujson.Arr.from(arr.toSeq.map(x => jsToUjson(x)))
        else
          val o = v.asInstanceOf[js.Object]
          val pairs = js.Object.entries(o).toSeq.map { entry =>
            val k  = entry(0).asInstanceOf[String]
            val vv = jsToUjson(entry(1).asInstanceOf[js.Any])
            k -> vv
          }
          ujson.Obj.from(pairs)
      case _ => ujson.Null

  private[walletstd] def ujsonToJs(v: ujson.Value): js.Any = v match
    case ujson.Null      => null
    case ujson.Str(s)    => s
    case ujson.Bool(b)   => b
    case ujson.Num(d)    => d
    case ujson.Arr(xs)   => xs.toJSArray.map(ujsonToJs)
    case ujson.Obj(pairs) =>
      val out = js.Dynamic.literal()
      pairs.foreach { case (k, vv) =>
        out.updateDynamic(k)(ujsonToJs(vv).asInstanceOf[js.Any])
      }
      out

/** Metadata bundled into the Wallet Standard `Wallet` JS object. */
case class WalletStandardInfo(
  version:  String         = "1.0.0",
  name:     String,
  icon:     String,
  chains:   Seq[String],
  accounts: Seq[String]    = Seq.empty,
)

/** Top-level Scala.js exports so host JS can register a wallet without
 *  needing to import Scala types — `registerScalaScriptWallet(connector,
 *  name, icon, chains, accounts)`. */
object WalletStandardJsExports:

  @JSExportTopLevel("registerScalaScriptWallet")
  def registerJs(
    connector: WalletStandardConnectorBase,
    name:      String,
    icon:      String,
    chains:    js.Array[String],
    accounts:  js.Array[String],
  )(using ec: ExecutionContext): js.Dynamic =
    val info = WalletStandardInfo(
      name     = name,
      icon     = icon,
      chains   = chains.toSeq,
      accounts = accounts.toSeq,
    )
    new WalletStandardRegister(connector, info).register()
