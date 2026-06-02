package scalascript.codegen

/** JVM-source std/ui primitives injected into frontend modules.
 *
 *  Extracted from `JvmGen` as a behavior-preserving runtime string split.
 *  The string is merged with the extern-filtered `std.ui.primitives` object
 *  by `JvmGen.mergeDuplicatePackageObjects`.
 */
object JvmRuntimeUiPrimitives:
  val source: String =
    """|object std:
       |  object ui:
       |    object primitives:
       |      // Signal[T] params/returns use Any: opaque Signal[T]=Any, but callers
       |      // may pass Any-typed fields (e.g. case class fields typed as Any in nodes.ssc).
       |      def signal[T](name: String, default: T): Any =
       |        new scalascript.frontend.ReactiveSignal[T](name, default)
       |
       |      def element(tag: String, attrs: Map[String, Any], events: Map[String, Any], children: List[View]): View =
       |        scalascript.frontend.View.Element(tag,
       |          _ssc_ui_decodeAttrs(attrs), _ssc_ui_decodeEvents(events),
       |          children.asInstanceOf[Seq[scalascript.frontend.View[?]]])
       |
       |      def textNode(s: String): View =
       |        scalascript.frontend.View.TextNode(() => s)
       |
       |      def signalText(s: Any): View =
       |        scalascript.frontend.View.SignalText(
       |          s.asInstanceOf[scalascript.frontend.ReactiveSignal[?]])
       |
       |      def showSignal(cond: Any, whenTrue: View, whenFalse: View): View =
       |        scalascript.frontend.View.ShowSignal(
       |          cond.asInstanceOf[scalascript.frontend.ReactiveSignal[Boolean]],
       |          whenTrue.asInstanceOf[scalascript.frontend.View[?]],
       |          whenFalse.asInstanceOf[scalascript.frontend.View[?]])
       |
       |      def fragment(children: List[View]): View =
       |        scalascript.frontend.View.Fragment(
       |          children.asInstanceOf[Seq[scalascript.frontend.View[?]]])
       |
       |      def setSignal(s: Any, v: Any): EventHandler =
       |        scalascript.frontend.EventHandler.SetSignalLiteral(
       |          s.asInstanceOf[scalascript.frontend.ReactiveSignal[Any]], v)
       |
       |      def inputChange(s: Any): EventHandler =
       |        scalascript.frontend.EventHandler.InputChange(
       |          s.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |
       |      def toggleSignal(s: Any): EventHandler =
       |        scalascript.frontend.EventHandler.ToggleSignal(
       |          s.asInstanceOf[scalascript.frontend.ReactiveSignal[Boolean]])
       |
       |      def eqSignal(s: Any, value: Any): Any =
       |        val _jsName     = s.asInstanceOf[scalascript.frontend.ReactiveSignal[?]].id
       |        val _initial    = s.asInstanceOf[scalascript.frontend.ReactiveSignal[?]].apply().asInstanceOf[Any] == value
       |        val _safeSuffix = value.toString.replaceAll("[^A-Za-z0-9]", "_")
       |        new scalascript.frontend.ReactiveSignal[Boolean](_jsName + "__eq__" + _safeSuffix, _initial)
       |
       |      def hashSignal(): Any =
       |        new scalascript.frontend.ReactiveSignal[String]("__hash__", "")
       |
       |      def emptyHeaders: Any =
       |        new scalascript.frontend.ReactiveSignal[String]("__ssc_empty_headers", "")
       |
       |      def fetchUrlSignal(name: String, url: String, refreshTick: Any, headers: Any = null): Any =
       |        val _tick = refreshTick.asInstanceOf[scalascript.frontend.ReactiveSignal[?]]
       |        val _hOpt = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |          .filter(_.id != "__ssc_empty_headers").map(_.id)
       |        new scalascript.frontend.FetchUrlSignal(name, url, _tick.id, _hOpt)
       |
       |      def fetchAction(method: String, url: String, body: Any, onSuccessTick: Any, headers: Any = null): EventHandler =
       |        val _hOpt = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |          .filter(_.id != "__ssc_empty_headers")
       |        scalascript.frontend.EventHandler.FetchAction(method, url,
       |          body.asInstanceOf[scalascript.frontend.ReactiveSignal[String]],
       |          onSuccessTick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]],
       |          headers = _hOpt)
       |
       |      def incSignal(s: Any): EventHandler =
       |        scalascript.frontend.EventHandler.IncrementSignal(
       |          s.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]], 1)
       |
       |      def fetchActionClear(method: String, url: String, body: Any, onSuccessTick: Any, headers: Any = null): EventHandler =
       |        val _hOpt = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |          .filter(_.id != "__ssc_empty_headers")
       |        scalascript.frontend.EventHandler.FetchAction(method, url,
       |          body.asInstanceOf[scalascript.frontend.ReactiveSignal[String]],
       |          onSuccessTick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]],
       |          clearBody = true,
       |          headers = _hOpt)
       |
       |      def fetchTableView(fetchUrl: String, deleteUrl: String, tick: Any, headers: Any = null): View =
       |        val _tableJsName = "sscRows_" + fetchUrl.replaceAll("[^A-Za-z0-9]", "_")
       |        val _hOpt = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |          .filter(_.id != "__ssc_empty_headers")
       |        scalascript.frontend.View.FetchTable(_tableJsName, fetchUrl, deleteUrl,
       |          tick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]],
       |          headers = _hOpt)
       |
       |      def emit(tree: View, outDir: String): Unit =
       |        _ssc_ui_emit_to_dir(tree.asInstanceOf[scalascript.frontend.View[?]], outDir)
       |
       |      def serve(tree: View, port: Int): Unit =
       |        _ssc_ui_serve(tree, port)
       |
       |""".stripMargin
