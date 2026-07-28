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
      // The program's tail is USER-FACING OUTPUT, so it renders through the same display the
      // kernel's println uses -- NOT Show.show, which is the debug rendering and quotes every
      // string. Measured against the v1 reference, which is what the conformance goldens encode:
      //
      //   value              Show.show (was)        Prims.display (v1, now)
      //   "HELLO!"           "HELLO!"               HELLO!
      //   List("a","b")      List("a", "b")         List(a, b)
      //   Some("x")          Some("x")              Some(x)
      //   Map("k" -> "v")    Map("k" -> "v")        Map(k -> v)
      //
      // BUGS.md v2-native-program-tail-quotes-strings. The divergence was NOT top-level-only, as
      // that entry first guessed -- v1 leaves nested strings unquoted here too.
      case other => println(_root_.ssc.Prims.display(other))
