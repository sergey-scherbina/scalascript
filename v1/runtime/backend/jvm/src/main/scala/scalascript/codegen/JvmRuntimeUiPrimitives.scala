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
       |      def seedSignal(name: String, source: Any): Any =
       |        new scalascript.frontend.SeedSignal(
       |          name, source.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |
       |      def componentScope[N](_scopeId: String, body: () => N): N =
       |        body()
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
       |      def forKeyedView[A](items: Any, key: A => String, render: A => View): View =
       |        val _sig = items.asInstanceOf[scalascript.frontend.ReactiveSignal[?]]
       |        val _rows = _sig.apply() match
       |          case xs: Iterable[?] => xs.toSeq.asInstanceOf[Seq[A]]
       |          case _               => Seq.empty[A]
       |        scalascript.frontend.View.Fragment(
       |          _rows.map(render(_).asInstanceOf[scalascript.frontend.View[?]]))
       |
       |      // forJsonView — dynamic JSON-array rendering + keyed reconciliation lives in the
       |      // JS emit-spa runtime (_ssc_ui_forJsonView / _mountForJson). The JVM/interpreter
       |      // fallback renders empty (like the forKeyedView note, the browser is authoritative).
       |      def forJsonView(items: Any, key: String, render: Any => View): View =
       |        scalascript.frontend.View.Fragment(Seq.empty[scalascript.frontend.View[?]])
       |
       |      // itemField — read a String field off a parsed JSON row (a Map on the JVM).
       |      def itemField(item: Any, name: String): String =
       |        item match
       |          case m: scala.collection.Map[?, ?] =>
       |            m.asInstanceOf[scala.collection.Map[String, Any]].get(name) match
       |              case Some(v) if v != null => v.toString
       |              case _                    => ""
       |          case _ => ""
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
       |      def fetchStreamSignal(name: String, url: String, body: Any, tick: Any, headers: Any = null): Any =
       |        val _body = body.asInstanceOf[scalascript.frontend.ReactiveSignal[?]]
       |        val _tick = tick.asInstanceOf[scalascript.frontend.ReactiveSignal[?]]
       |        val _hOpt = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |          .filter(_.id != "__ssc_empty_headers").map(_.id)
       |        new scalascript.frontend.FetchStreamSignal(name, url, _body.id, _tick.id, _hOpt)
       |
       |      def intervalTick(name: String, ms: Int): Any =
       |        new scalascript.frontend.IntervalTick(name, ms)
       |
       |      // Scope B.3 — a managed-fetch Remote source whose optional dotted envelope
       |      // path (e.g. "result.items") is carried on the model so the served custom
       |      // frontend drills it (StaticJsEmitter __ssc_rowsOf), matching the browser.
       |      def fetchRowsSource(sig: Any, rowsPath: Any = ""): Any =
       |        scalascript.frontend.TableDataSource.Remote(
       |          sig.asInstanceOf[scalascript.frontend.FetchUrlSignal],
       |          Option(rowsPath).map(_.toString).filter(_ != "null").getOrElse(""))
       |
       |      def fetchAction(method: String, url: String, body: Any, onSuccessTick: Any, headers: Any = null): EventHandler =
       |        val _hOpt = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |          .filter(_.id != "__ssc_empty_headers")
       |        scalascript.frontend.EventHandler.FetchAction(method, url,
       |          body.asInstanceOf[scalascript.frontend.ReactiveSignal[String]],
       |          onSuccessTick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]],
       |          headers = _hOpt)
       |
       |      def fetchActionTo(method: String, urlSig: Any, body: Any, onSuccessTick: Any, headers: Any = null): EventHandler =
       |        val _hOpt = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |          .filter(_.id != "__ssc_empty_headers")
       |        // headless (no SPA click dispatch): snapshot the urlSig's current value
       |        scalascript.frontend.EventHandler.FetchAction(method,
       |          urlSig.asInstanceOf[scalascript.frontend.ReactiveSignal[String]].apply(),
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
       |      // Capture is realized by the emit-spa/browser runtime; on the JVM IR
       |      // path it degrades to a plain fetch (the `into` signal is ignored).
       |      def fetchCaptureAction(method: String, url: String, body: Any, into: Any,
       |                             onSuccessTick: Any, headers: Any = null): EventHandler =
       |        val _hOpt = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |          .filter(_.id != "__ssc_empty_headers")
       |        scalascript.frontend.EventHandler.FetchAction(method, url,
       |          body.asInstanceOf[scalascript.frontend.ReactiveSignal[String]],
       |          onSuccessTick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]],
       |          headers = _hOpt)
       |
       |      def fieldColumn(title: String, fieldPath: String, align: String = "",
       |                      editAction: Any = null): Any =
       |        val _ea = Option(editAction).collect {
       |          case a: scalascript.frontend.RowActionDef.RowInlineEdit => a }
       |        scalascript.frontend.FieldColumnDef(title, fieldPath, Option(align).filter(_.nonEmpty), _ea)
       |
       |      private def _ssc_dottedRowName(name: String, operation: String): String =
       |        if name.nonEmpty && name.split("\\.", -1).forall(_.nonEmpty) then name
       |        else throw IllegalArgumentException(s"$$operation requires a non-empty dotted field path")
       |
       |      private def _ssc_exactRowPayload(payload: Any, operation: String): scalascript.frontend.RowPayload =
       |        val candidate = payload match
       |          case name: String => scalascript.frontend.RowPayload.Field(name)
       |          case value: scalascript.frontend.RowPayload => value
       |          case _ => throw IllegalArgumentException(s"$$operation payload must be String or RowPayload")
       |        candidate match
       |          case scalascript.frontend.RowPayload.Field(name) =>
       |            scalascript.frontend.RowPayload.Field(_ssc_dottedRowName(name, operation))
       |          case scalascript.frontend.RowPayload.WholeRow => scalascript.frontend.RowPayload.WholeRow
       |          case scalascript.frontend.RowPayload.Fields(names)
       |              if names.nonEmpty && names.distinct.size == names.size && names.forall(n => n.nonEmpty && n.split("\\.", -1).forall(_.nonEmpty)) =>
       |            scalascript.frontend.RowPayload.Fields(names)
       |          case scalascript.frontend.RowPayload.Fields(_) =>
       |            throw IllegalArgumentException(s"$$operation fields must be unique non-empty dotted field paths")
       |
       |      def rowDeleteAction(url: String, idField: String, tick: Any, headers: Any = null): Any =
       |        val _hOpt = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |          .filter(_.id != "__ssc_empty_headers")
       |        scalascript.frontend.RowActionDef.RowDelete(url, _ssc_dottedRowName(idField, "rowDeleteAction"),
       |          tick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]], _hOpt)
       |
       |      def fieldPayload(name: String): Any = _ssc_exactRowPayload(name, "fieldPayload")
       |      def wholeRowPayload(): Any = scalascript.frontend.RowPayload.WholeRow
       |      def fieldsPayload(names: List[String]): Any = _ssc_exactRowPayload(scalascript.frontend.RowPayload.Fields(names), "fieldsPayload")
       |
       |      def rowPostAction(label: String, method: String, url: String, payload: Any,
       |                        tick: Any, headers: Any = null): Any =
       |        val _hOpt = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |          .filter(_.id != "__ssc_empty_headers")
       |        val _payload = _ssc_exactRowPayload(payload, "rowPostAction")
       |        scalascript.frontend.RowActionDef.RowPost(label, method, url, _payload,
       |          tick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]], _hOpt)
       |
       |      def rowLinkAction(label: String, signal: Any, fieldPath: String): Any =
       |        scalascript.frontend.RowActionDef.RowLink(label,
       |          signal.asInstanceOf[scalascript.frontend.ReactiveSignal[String]], _ssc_dottedRowName(fieldPath, "rowLinkAction"))
       |
       |      def rowEditAction(method: String, url: String, idField: String,
       |                        tick: Any, headers: Any = null): Any =
       |        val _hOpt = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
       |          .filter(_.id != "__ssc_empty_headers")
       |        scalascript.frontend.RowActionDef.RowInlineEdit(method, url, _ssc_dottedRowName(idField, "rowEditAction"),
       |          tick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]], _hOpt)
       |
       |      def dataTableView(source: Any, columns: Any, actions: Any, rowKeyPath: String = "id"): View =
       |        val _cols = columns.asInstanceOf[List[scalascript.frontend.FieldColumnDef]]
       |        val _acts = actions.asInstanceOf[List[scalascript.frontend.RowActionDef]]
       |        // Accept a TableDataSource (e.g. fetchRowsSource, carrying rowsPath) or a
       |        // bare FetchUrlSignal (legacy → wrap as a Remote with no envelope path).
       |        val _src = source match
       |          case s: scalascript.frontend.TableDataSource => s
       |          case s => scalascript.frontend.TableDataSource.Remote(s.asInstanceOf[scalascript.frontend.FetchUrlSignal])
       |        scalascript.frontend.View.DataTable(_src, _cols, _acts,
       |          rowKeyPath = if rowKeyPath.isEmpty then "id" else rowKeyPath)
       |
       |      def emit(tree: View, outDir: String): Unit =
       |        _ssc_ui_emit_to_dir(tree.asInstanceOf[scalascript.frontend.View[?]], outDir)
       |
       |      def serve(tree: View, port: Int): Unit =
       |        _ssc_ui_serve(tree, port)
       |
       |""".stripMargin
