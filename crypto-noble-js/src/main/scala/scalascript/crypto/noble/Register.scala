package scalascript.crypto.noble

import scala.scalajs.js.annotation.JSExportTopLevel

import scalascript.crypto.CryptoBackend

/** Entry-point glue that installs [[NobleCryptoBackend]] into the shared
 *  `object CryptoBackend` registry.
 *
 *  Two equivalent installers — pick whichever fits the host:
 *
 *  1. **From Scala.js code** — call `Register.install()` once at app
 *     startup (typically from a Scala.js test setup or app `main`).
 *  2. **From JS host code** — call the top-level
 *     `registerNobleCryptoBackend()` function (e.g.
 *     `require('.../crypto-noble-js-fastopt.js'); registerNobleCryptoBackend();`).
 *
 *  Both are idempotent — re-registration overwrites the previous entry
 *  for `id="noble-js"`. */
object Register:

  /** Install the Noble.js CryptoBackend into the shared registry.
   *  Idempotent — the registry's `register` overwrites on duplicate id. */
  def install(): Unit =
    CryptoBackend.register(new NobleCryptoBackend)

  /** Same as [[install]] — exported to JS so host bundles can call it
   *  from the bootstrap script without needing a Scala-side entry. */
  @JSExportTopLevel("registerNobleCryptoBackend")
  def installJs(): Unit = install()
