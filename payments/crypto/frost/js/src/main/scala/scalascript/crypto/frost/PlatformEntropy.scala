package scalascript.crypto.frost

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** JS secure randomness via the WebCrypto CSPRNG (`globalThis.crypto.getRandomValues`, present on Node ≥18
 *  and in browsers) for the `Ed25519Ops.Reference` backend. */
private[frost] object PlatformEntropy:
  def bytes(n: Int): Array[Byte] =
    val arr = new Uint8Array(n)
    js.Dynamic.global.crypto.getRandomValues(arr)
    Array.tabulate(n)(i => arr(i).toByte)
