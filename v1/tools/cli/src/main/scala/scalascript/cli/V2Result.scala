package scalascript.cli

/** Shared successful-result contract for compatibility and native v2 runners. */
private[cli] object V2Result:
  def report(rawResult: _root_.ssc.Value): Unit =
    _root_.ssc.PortableEffects.completeManaged(rawResult) match
      case _root_.ssc.Value.UnitV => ()
      case op @ _root_.ssc.Value.DataV("Op", fields) if _root_.ssc.Runtime.isAutoThreadOp(op) =>
        val label = fields.headOption.collect { case _root_.ssc.Value.StrV(s) => s }.getOrElse("<unknown>")
        throw new RuntimeException(s"unhandled runtime effect: $label")
      case _root_.ssc.Value.DataV("Stub", fields) =>
        val label = fields.headOption.collect { case _root_.ssc.Value.StrV(s) => s }.getOrElse("<unknown>")
        throw new RuntimeException(s"unresolved runtime dispatch: $label")
      case other => println(_root_.ssc.Show.show(other))
