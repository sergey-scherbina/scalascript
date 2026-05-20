package scalascript.wallet.connector.eip1193

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel
import org.scalajs.dom

/** Scala.js EIP-1193 / EIP-6963 browser glue.
 *
 *  Wraps the cross-compiled [[Eip1193Provider]] in a JS-facing object
 *  that:
 *
 *    1. Exposes a Promise-based `request({method, params})` matching
 *       the EIP-1193 surface every dApp's `window.ethereum.request`
 *       implementation expects.
 *    2. Implements EIP-6963 multi-injected-provider discovery — emits
 *       `eip6963:announceProvider` on the window, listens for
 *       `eip6963:requestProvider` to re-announce on demand.
 *
 *  Construction:
 *
 *  ```scala
 *  val provider = new Eip1193Provider(manager, ctxFor, ChainId.Base)
 *  provider.attach(manager)
 *  val window   = new WindowEthereumProvider(
 *    provider,
 *    Eip6963ProviderInfo(uuid = "...", name = "...", icon = "data:...",
 *                        rdns = "io.example"),
 *  )
 *  window.install()
 *  ```
 *
 *  After `install()` the dApp can call
 *  `window.ethereum.request({method:"eth_chainId"}).then(...)`. */
class WindowEthereumProvider(
  val provider: Eip1193Provider,
  val info:     Eip6963ProviderInfo,
)(using ec: ExecutionContext):

  private var installed: Boolean = false

  /** Bind the provider as `window.ethereum` (idempotent) and start
   *  listening for `eip6963:requestProvider` events. */
  def install(): Unit =
    if installed then return
    installed = true
    bindWindowEthereum()
    dom.window.addEventListener("eip6963:requestProvider", listener)
    announce()

  /** Stop responding to EIP-6963 requests.  Does not clear
   *  `window.ethereum` — that would surprise dApps that captured the
   *  reference. */
  def uninstall(): Unit =
    if !installed then return
    installed = false
    dom.window.removeEventListener("eip6963:requestProvider", listener)

  /** Emit an `eip6963:announceProvider` event.  Public so hosts can
   *  re-announce after a wallet flip / chain switch / icon change. */
  def announce(): Unit =
    val detail = js.Dynamic.literal(
      info = providerInfoJs,
      provider = jsProvider,
    )
    val ev = WindowEthereumProvider.customEvent("eip6963:announceProvider", detail)
    dom.window.dispatchEvent(ev)

  /** Promise wrapper around the underlying Scala provider's request
   *  surface — exported so dApp JS can call it directly through the
   *  `window.ethereum.request({method, params})` contract. */
  def requestJs(req: js.Dynamic): js.Promise[js.Any] =
    val method = req.method.asInstanceOf[String]
    val paramsValue: ujson.Value = WindowEthereumProvider.jsToUjson(req.params)
    provider.request(method, paramsValue)
      .map(v => WindowEthereumProvider.ujsonToJs(v))
      .toJSPromise

  // ── internals ─────────────────────────────────────────────────────────

  private lazy val jsProvider: js.Dynamic =
    js.Dynamic.literal(
      request = (req: js.Dynamic) => requestJs(req),
    )

  private lazy val providerInfoJs: js.Dynamic =
    js.Dynamic.literal(
      uuid = info.uuid,
      name = info.name,
      icon = info.icon,
      rdns = info.rdns,
    )

  private val listener: js.Function1[dom.Event, Unit] = _ => announce()

  private def bindWindowEthereum(): Unit =
    // Last-writer-wins: a fresher provider clobbers the previous one.
    // EIP-6963 expects dApps to discover via the announce event, not
    // by reading `window.ethereum`, so this is mostly for legacy dApps.
    dom.window.asInstanceOf[js.Dynamic].ethereum = jsProvider

object WindowEthereumProvider:

  /** Cross-host `CustomEvent` constructor.  Real browsers have
   *  `dom.CustomEvent`; jsdom v22+ supports it too; the unit-tests in
   *  this module stub `dom.window` so the constructor is always
   *  reachable. */
  private[eip1193] def customEvent(name: String, detail: js.Dynamic): dom.Event =
    val init = js.Dynamic.literal(detail = detail)
    js.Dynamic.newInstance(js.Dynamic.global.CustomEvent)(name, init)
      .asInstanceOf[dom.Event]

  /** Convert a JS value into ujson.Value.  EIP-1193 dApps pass numbers
   *  / strings / arrays / plain objects through `request({method,
   *  params})`; this mirrors the standard JSON shape.  Discrimination
   *  uses `js.typeOf` because Scala.js `js.Any` is not aligned with
   *  Scala `String` / `Boolean` / `Double` types at compile time. */
  private[eip1193] def jsToUjson(v: js.Any): ujson.Value =
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

  /** Convert a ujson.Value to a JS value the dApp can introspect. */
  private[eip1193] def ujsonToJs(v: ujson.Value): js.Any = v match
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

/** Top-level Scala.js export so host JS can install a wallet from the
 *  bootstrap script: `registerScalaScriptEip1193(provider, info)`. */
object WindowEthereumProviderJsExports:

  @JSExportTopLevel("registerScalaScriptEip1193")
  def registerJs(
    provider:    Eip1193Provider,
    uuid:        String,
    name:        String,
    icon:        String,
    rdns:        String,
  )(using ec: ExecutionContext): WindowEthereumProvider =
    val info = Eip6963ProviderInfo(uuid = uuid, name = name, icon = icon, rdns = rdns)
    val w    = new WindowEthereumProvider(provider, info)
    w.install()
    w
