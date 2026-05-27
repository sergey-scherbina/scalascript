package scalascript.markup.node

import scala.scalajs.js.annotation.JSExportTopLevel

import scalascript.markup.MarkupCodec

/** ServiceLoader-style registration for [[NodeMarkupCodec]].
 *
 *  Two equivalent entry points:
 *
 *  1. **From Scala.js (Node.js)** — call `NodeMarkupPlugin.install()` once
 *     at app init.
 *  2. **From JS host** — call the exported `registerNodeMarkupCodec()`
 *     function after loading the Scala.js bundle.
 *
 *  Both are idempotent. */
object NodeMarkupPlugin:

  def install(): Unit =
    MarkupCodec.setDefault(NodeMarkupCodec)

  @JSExportTopLevel("registerNodeMarkupCodec")
  def installJs(): Unit = install()
