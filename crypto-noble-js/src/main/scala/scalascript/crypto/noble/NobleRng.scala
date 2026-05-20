package scalascript.crypto.noble

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.typedarray.Uint8Array

/** Bridge to a cryptographically-secure RNG.  Browser and Node ≥ 19
 *  expose `globalThis.crypto.getRandomValues(view)` (WebCrypto); older
 *  Node (16, 18) exposes it only through the CommonJS `crypto` module
 *  (`require('crypto').webcrypto.getRandomValues` / `randomFillSync`).
 *
 *  We pull `globalThis.crypto` through a `@JSGlobal` facade rather than
 *  `js.Dynamic.global` so the Scala.js linker accepts it (the
 *  "loading the global scope as a value" check trips on
 *  `js.Dynamic.global`). */
private[noble] object NobleRng:

  @js.native
  @JSGlobal("crypto")
  private object GlobalCrypto extends js.Object:
    def getRandomValues(view: Uint8Array): Uint8Array = js.native

  /** Fill `view` in place with random bytes.  Throws if no secure RNG
   *  is reachable (extremely old Node without WebCrypto). */
  def fill(view: Uint8Array): Unit =
    if js.typeOf(GlobalCrypto.asInstanceOf[js.Any]) != "undefined" then
      GlobalCrypto.getRandomValues(view): Unit
    else
      throw new IllegalStateException(
        "No WebCrypto available — upgrade Node to ≥ 19 or run in a browser."
      )
