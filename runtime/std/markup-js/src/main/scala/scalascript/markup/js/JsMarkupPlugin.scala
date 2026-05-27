package scalascript.markup.js

import scala.scalajs.js.annotation.JSExportTopLevel

import scalascript.markup.MarkupCodec

/** ServiceLoader-style registration for [[JsMarkupCodec]].
 *
 *  Two equivalent entry points:
 *
 *  1. **From Scala.js** — call `JsMarkupPlugin.install()` once at app init.
 *  2. **From JS host** — call the exported `registerJsMarkupCodec()` function,
 *     e.g. after the Scala.js bundle is loaded.
 *
 *  Both are idempotent. */
object JsMarkupPlugin:

  def install(): Unit =
    MarkupCodec.setDefault(JsMarkupCodec)

  @JSExportTopLevel("registerJsMarkupCodec")
  def installJs(): Unit = install()
