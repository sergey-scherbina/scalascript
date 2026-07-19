package scalascript.compiler.plugin.fetch

import scala.annotation.nowarn
import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.frontend.{ReactiveSignal, FetchUrlSignal, FetchJsonSignal, FetchStreamSignal, IntervalTick, EventHandler, View, FieldColumnDef, RowActionDef, TableDataSource, ColumnKind, RowPayload}
import scalascript.plugin.api.{PluginNative, PluginValue, PluginError}

object FetchIntrinsics:
  private def isNullish(value: Any): Boolean =
    value == null || value == PluginValue.unit || value == PluginValue.nullV

  private def isEmptyHeadersArg(value: Any): Boolean =
    isNullish(value) || (value match
      case PluginValue.NativeFn("emptyHeaders", _) => true
      case _                                  => false)

  private def validDottedName(name: String): Boolean =
    name.nonEmpty && name.split("\\.", -1).forall(_.nonEmpty)

  private def requireDottedName(name: String, operation: String): String =
    if validDottedName(name) then name
    else PluginError.raise(s"$operation requires a non-empty dotted field path")

  private def validateRowPayload(payload: RowPayload, operation: String): RowPayload = payload match
    case RowPayload.Field(name) => RowPayload.Field(requireDottedName(name, operation))
    case RowPayload.WholeRow => RowPayload.WholeRow
    case RowPayload.Fields(names)
        if names.nonEmpty && names.distinct.length == names.length && names.forall(validDottedName) =>
      RowPayload.Fields(names)
    case RowPayload.Fields(_) =>
      PluginError.raise(s"$operation fields must be unique non-empty dotted field paths")

  @nowarn("cat=deprecation")
  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // emptyHeaders: Signal[String] — no-op headers Signal (value always "").
    QualifiedName("emptyHeaders") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List() => PluginValue.foreign("ReactiveSignal", new ReactiveSignal[String]("__ssc_empty_headers", ""))
        case _ => PluginError.raise("emptyHeaders")
    },

    // fetchUrlSignal(name, url, refreshTick[, headers]): Signal[String]
    // Creates a ReactiveSignal[String] that re-fetches `url` whenever `refreshTick` increments.
    // On JVM (interpreter) the initial value is just ""; the JS runtime performs fetch on mount.
    QualifiedName("fetchUrlSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          PluginValue.foreign("ReactiveSignal", new FetchUrlSignal(name, url, tick.id))
        case List(name: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val hId = headers.id
          PluginValue.foreign("ReactiveSignal",
            new FetchUrlSignal(name, url, tick.id,
              if hId == "__ssc_empty_headers" then None else Some(hId)))
        case _ => PluginError.raise("fetchUrlSignal(name, url, refreshTick[, headers])")
    },

    // fetchJsonSignal(name, url, refreshTick, modelTypeName[, headers]): Signal[String]
    // Like fetchUrlSignal but creates a FetchJsonSignal that decodes JSON into a named model type.
    // The runtime value is still ReactiveSignal[String] (raw JSON text on JVM); backends switch
    // on FetchJsonSignal.codec to emit the typed decode call.
    QualifiedName("fetchJsonSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  modelTypeName: String) =>
          PluginValue.foreign("ReactiveSignal", new FetchJsonSignal(name, url, tick.id, modelTypeName))
        case List(name: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  modelTypeName: String,
                  PluginValue.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val hId = headers.id
          PluginValue.foreign("ReactiveSignal",
            new FetchJsonSignal(name, url, tick.id, modelTypeName,
              if hId == "__ssc_empty_headers" then None else Some(hId)))
        case _ => PluginError.raise("fetchJsonSignal(name, url, refreshTick, modelTypeName[, headers])")
    },

    // fetchStreamSignal(name, url, body, tick[, headers]): Signal[String]
    // Creates a FetchStreamSignal that POSTs `body`'s value to `url` on mount + whenever
    // `tick` increments, streaming the response body into the signal (accumulated text).
    // On JVM (interpreter) the initial value is just ""; the JS runtime performs the stream.
    QualifiedName("fetchStreamSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          PluginValue.foreign("ReactiveSignal", new FetchStreamSignal(name, url, body.id, tick.id))
        case List(name: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val hId = headers.id
          PluginValue.foreign("ReactiveSignal",
            new FetchStreamSignal(name, url, body.id, tick.id,
              if hId == "__ssc_empty_headers" then None else Some(hId)))
        case _ => PluginError.raise("fetchStreamSignal(name, url, body, tick[, headers])")
    },

    // intervalTick(name, ms): Signal[Int] — Int signal that auto-increments every `ms`
    // milliseconds (setInterval in the JS runtime). Initial value 0; headless on the JVM.
    QualifiedName("intervalTick") -> PluginNative.evalLegacy { (_, args) =>
      def toMs(v: Any): Option[Int] = v match
        case i: Int    => Some(i)
        case l: Long   => Some(l.toInt)
        case d: Double => Some(d.toInt)
        case s: String => s.toIntOption
        case _         => None
      args match
        case List(name: String, msArg) if toMs(msArg).isDefined =>
          PluginValue.foreign("ReactiveSignal", new IntervalTick(name, toMs(msArg).get))
        case _ => PluginError.raise("intervalTick(name, ms)")
    },

    // fetchAction(method, url, body, onSuccessTick[, headers]): EventHandler
    // On click: fetch(url, {method, body: bodySignal.value, headers}) then increment onSuccessTick.
    QualifiedName("fetchAction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(method: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          PluginValue.foreign("EventHandler",
            EventHandler.FetchAction(method, url,
              body.asInstanceOf[ReactiveSignal[String]],
              tick.asInstanceOf[ReactiveSignal[Int]]))
        case List(method: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          PluginValue.foreign("EventHandler",
            EventHandler.FetchAction(method, url,
              body.asInstanceOf[ReactiveSignal[String]],
              tick.asInstanceOf[ReactiveSignal[Int]],
              headers = if h.id == "__ssc_empty_headers" then None else Some(h)))
        case _ => PluginError.raise("fetchAction(method, url, body, onSuccessTick[, headers])")
    },

    // fetchActionTo(method, urlSig, body, onSuccessTick[, headers]): EventHandler
    // Like fetchAction but the URL is a Signal[String] resolved at click time on the
    // reactive (react/JS SPA) backend. The interpreter is headless (no SPA click
    // dispatch), so it snapshots the urlSig's current value into the descriptor.
    QualifiedName("fetchActionTo") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(method: String,
                  PluginValue.Foreign("ReactiveSignal", urlSig: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          PluginValue.foreign("EventHandler",
            EventHandler.FetchAction(method, urlSig.asInstanceOf[ReactiveSignal[String]].apply(),
              body.asInstanceOf[ReactiveSignal[String]],
              tick.asInstanceOf[ReactiveSignal[Int]]))
        case List(method: String,
                  PluginValue.Foreign("ReactiveSignal", urlSig: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          PluginValue.foreign("EventHandler",
            EventHandler.FetchAction(method, urlSig.asInstanceOf[ReactiveSignal[String]].apply(),
              body.asInstanceOf[ReactiveSignal[String]],
              tick.asInstanceOf[ReactiveSignal[Int]],
              headers = if h.id == "__ssc_empty_headers" then None else Some(h)))
        case _ => PluginError.raise("fetchActionTo(method, urlSig, body, onSuccessTick[, headers])")
    },

    // fetchActionClear(method, url, body, onSuccessTick[, headers]): EventHandler
    // Like fetchAction but also clears the body signal to "" on success.
    QualifiedName("fetchActionClear") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(method: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          PluginValue.foreign("EventHandler",
            EventHandler.FetchAction(method, url,
              body.asInstanceOf[ReactiveSignal[String]],
              tick.asInstanceOf[ReactiveSignal[Int]],
              clearBody = true))
        case List(method: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          PluginValue.foreign("EventHandler",
            EventHandler.FetchAction(method, url,
              body.asInstanceOf[ReactiveSignal[String]],
              tick.asInstanceOf[ReactiveSignal[Int]],
              clearBody = true,
              headers = if h.id == "__ssc_empty_headers" then None else Some(h)))
        case _ => PluginError.raise("fetchActionClear(method, url, body, onSuccessTick[, headers])")
    },

    // ── Scope B.4: structured onSuccess effects ────────────────────────────────
    // onBumpTick / onSetSignal / onNavigate build effect descriptors consumed by
    // fetchActionWith; the browser runtime runs them in order after a 2xx.
    QualifiedName("onBumpTick") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          PluginValue.foreign("OnSuccessEffect", ("bumpTick", tick))
        case _ => PluginError.raise("onBumpTick(tickSignal)")
    },
    QualifiedName("onSetSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Foreign("ReactiveSignal", sig: ReactiveSignal[?]), value) =>
          PluginValue.foreign("OnSuccessEffect", ("setSignal", sig, value))
        case _ => PluginError.raise("onSetSignal(signal, value)")
    },
    QualifiedName("onNavigate") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(path) => PluginValue.foreign("OnSuccessEffect", ("navigate", String.valueOf(path)))
        case _ => PluginError.raise("onNavigate(path)")
    },

    // formBody(fields): a request-body descriptor (Scope B.4+) — the browser
    // assembles `{ field: <signal value> }` from the named field signals at submit.
    QualifiedName("formBody") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(fields) => PluginValue.foreign("FormBody", fields)
        case _                   => PluginError.raise("formBody(fields)")
    },

    // fetchActionWith(method, url, body, onSuccess[, headers]): EventHandler
    // Like fetchAction but carries a structured onSuccess effect list (run in order
    // on a 2xx by the browser runtime).  The interpreter builds a plain FetchAction
    // (first onBumpTick → onSuccessTick); the rich effects are browser-scoped.
    // `body` is a Signal[String] or a `formBody(...)` descriptor (Scope B.4+) — the
    // latter is browser-assembled, so the interpreter uses a synthetic empty body.
    QualifiedName("fetchActionWith") -> PluginNative.evalLegacy { (_, args) =>
      def resolveBody(b: Any): ReactiveSignal[?] = b match
        case PluginValue.Foreign("ReactiveSignal", s: ReactiveSignal[?]) => s
        case PluginValue.Foreign("FormBody", _) => new ReactiveSignal[String]("__ssc_form_body", "")
        case _ => PluginError.raise("fetchActionWith: body must be a Signal[String] or formBody(...)")
      def mk(method: String, url: String, body: ReactiveSignal[?], onSuccess: Any,
             headers: Option[ReactiveSignal[String]]): PluginValue =
        val effects = onSuccess match
          case PluginValue.Lst(es) => es
          case _               => Nil
        val firstTick = effects.collectFirst {
          case PluginValue.Foreign("OnSuccessEffect", ("bumpTick", t: ReactiveSignal[?])) =>
            t.asInstanceOf[ReactiveSignal[Int]]
        }
        val tick = firstTick.getOrElse(new ReactiveSignal[Int]("__ssc_no_tick", 0))
        PluginValue.foreign("EventHandler",
          EventHandler.FetchAction(method, url,
            body.asInstanceOf[ReactiveSignal[String]], tick, headers = headers))
      args match
        case List(method: String, url: String, body, onSuccess) =>
          mk(method, url, resolveBody(body), onSuccess, None)
        case List(method: String, url: String, body, onSuccess,
                  PluginValue.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          mk(method, url, resolveBody(body), onSuccess,
             if h.id == "__ssc_empty_headers" then None else Some(h))
        case _ => PluginError.raise("fetchActionWith(method, url, body, onSuccess[, headers])")
    },

    // fetchCaptureAction(method, url, body, into, onSuccessTick[, headers]): EventHandler
    // On a 2xx, the response BODY is captured into `into` (read back with
    // jsonOf), then onSuccessTick is bumped.  The capture is realized by the
    // emit-spa / browser runtime (the data-ssc-fetch-into wiring); this
    // interpreter binding resolves the symbol and degrades to a plain fetch
    // button on non-browser render paths, where capturing into a client signal
    // has no meaning.
    QualifiedName("fetchCaptureAction") -> PluginNative.evalLegacy { (_, args) =>
      def mk(method: String, url: String, body: ReactiveSignal[?], tick: ReactiveSignal[?],
             headers: Option[ReactiveSignal[String]]) =
        PluginValue.foreign("EventHandler",
          EventHandler.FetchAction(method, url,
            body.asInstanceOf[ReactiveSignal[String]],
            tick.asInstanceOf[ReactiveSignal[Int]],
            headers = headers))
      args match
        case List(method: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", _: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          mk(method, url, body, tick, None)
        case List(method: String, url: String,
                  PluginValue.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", _: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          mk(method, url, body, tick, if h.id == "__ssc_empty_headers" then None else Some(h))
        case _ => PluginError.raise("fetchCaptureAction(method, url, body, into, onSuccessTick[, headers])")
    },

    // incSignal(s): EventHandler — increment an Int signal by 1 on click.
    QualifiedName("incSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Foreign("ReactiveSignal", rs: ReactiveSignal[?])) =>
          PluginValue.foreign("EventHandler",
            EventHandler.IncrementSignal(rs.asInstanceOf[ReactiveSignal[Int]], 1))
        case _ => PluginError.raise("incSignal(signal)")
    },

    // fieldColumn(title, fieldPath[, align[, editAction]]): FieldColumnDef
    QualifiedName("fieldColumn") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(title: String, fieldPath: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath))
        case List(title: String, fieldPath: String, align: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty)))
        case List(title: String, fieldPath: String, align: String,
                  PluginValue.Foreign("RowActionDef", ea: RowActionDef.RowInlineEdit)) =>
          PluginValue.foreign("FieldColumnDef",
            FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty), Some(ea)))
        case List(title: String, fieldPath: String, align: String, editAction)
            if isNullish(editAction) =>
          PluginValue.foreign("FieldColumnDef",
            FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty)))
        case _ => PluginError.raise("fieldColumn(title, fieldPath[, align[, editAction]])")
    },

    // rowDeleteAction(url, idField, tick[, headers]): RowActionDef
    QualifiedName("rowDeleteAction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(url: String, idField: String,
          PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          PluginValue.foreign("RowActionDef",
            RowActionDef.RowDelete(url, requireDottedName(idField, "rowDeleteAction"), tick.asInstanceOf[ReactiveSignal[Int]]))
        case List(url: String, idField: String,
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          PluginValue.foreign("RowActionDef",
            RowActionDef.RowDelete(url, requireDottedName(idField, "rowDeleteAction"), tick.asInstanceOf[ReactiveSignal[Int]],
              if h.id == "__ssc_empty_headers" then None else Some(h)))
        case List(url: String, idField: String,
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]), headers)
            if isEmptyHeadersArg(headers) =>
          PluginValue.foreign("RowActionDef",
            RowActionDef.RowDelete(url, requireDottedName(idField, "rowDeleteAction"), tick.asInstanceOf[ReactiveSignal[Int]]))
        case _ => PluginError.raise("rowDeleteAction(url, idField, tick[, headers])")
    },

    // rowPostAction(label, method, url, payload, tick[, headers]): RowActionDef
    // payload may be a String (backward compat → RowPayload.Field) or a Foreign("RowPayload", ...)
    QualifiedName("rowPostAction") -> PluginNative.evalLegacy { (_, args) =>
      def toPayload(v: Any): RowPayload = v match
        case s: String => validateRowPayload(RowPayload.Field(s), "rowPostAction")
        case PluginValue.Foreign("RowPayload", p: RowPayload) => validateRowPayload(p, "rowPostAction")
        case _                                            => PluginError.raise("rowPostAction: invalid payload argument")
      args match
        case List(label: String, method: String, url: String, payloadArg,
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          PluginValue.foreign("RowActionDef",
            RowActionDef.RowPost(label, method, url, toPayload(payloadArg), tick.asInstanceOf[ReactiveSignal[Int]]))
        case List(label: String, method: String, url: String, payloadArg,
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          PluginValue.foreign("RowActionDef",
            RowActionDef.RowPost(label, method, url, toPayload(payloadArg), tick.asInstanceOf[ReactiveSignal[Int]],
              if h.id == "__ssc_empty_headers" then None else Some(h)))
        case List(label: String, method: String, url: String, payloadArg,
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]), headers)
            if isEmptyHeadersArg(headers) =>
          PluginValue.foreign("RowActionDef",
            RowActionDef.RowPost(label, method, url, toPayload(payloadArg), tick.asInstanceOf[ReactiveSignal[Int]]))
        case _ => PluginError.raise("rowPostAction(label, method, url, payload, tick[, headers])")
    },

    // rowLinkAction(label, signal, fieldPath): RowActionDef
    QualifiedName("rowLinkAction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(label: String,
                  PluginValue.Foreign("ReactiveSignal", sig: ReactiveSignal[?]),
                  fieldPath: String) =>
          PluginValue.foreign("RowActionDef",
            RowActionDef.RowLink(label, sig.asInstanceOf[ReactiveSignal[String]], requireDottedName(fieldPath, "rowLinkAction")))
        case _ => PluginError.raise("rowLinkAction(label, signal, fieldPath)")
    },

    // rowEditAction(method, url, idField, tick[, headers]): RowActionDef
    QualifiedName("rowEditAction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(method: String, url: String, idField: String,
          PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          PluginValue.foreign("RowActionDef",
            RowActionDef.RowInlineEdit(method, url, requireDottedName(idField, "rowEditAction"), tick.asInstanceOf[ReactiveSignal[Int]]))
        case List(method: String, url: String, idField: String,
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  PluginValue.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          PluginValue.foreign("RowActionDef",
            RowActionDef.RowInlineEdit(method, url, requireDottedName(idField, "rowEditAction"), tick.asInstanceOf[ReactiveSignal[Int]],
              if h.id == "__ssc_empty_headers" then None else Some(h)))
        case List(method: String, url: String, idField: String,
                  PluginValue.Foreign("ReactiveSignal", tick: ReactiveSignal[?]), headers)
            if isEmptyHeadersArg(headers) =>
          PluginValue.foreign("RowActionDef",
            RowActionDef.RowInlineEdit(method, url, requireDottedName(idField, "rowEditAction"), tick.asInstanceOf[ReactiveSignal[Int]]))
        case _ => PluginError.raise("rowEditAction(method, url, idField, tick[, headers])")
    },

    // ── RowPayload constructors ──────────────────────────────────────────────

    // fieldPayload(name: String): RowPayload.Field
    QualifiedName("fieldPayload") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String) => PluginValue.foreign("RowPayload", validateRowPayload(RowPayload.Field(name), "fieldPayload"))
        case _ => PluginError.raise("fieldPayload(name)")
    },

    // wholeRowPayload(): RowPayload.WholeRow
    QualifiedName("wholeRowPayload") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case Nil => PluginValue.foreign("RowPayload", RowPayload.WholeRow)
        case _ => PluginError.raise("wholeRowPayload()")
    },

    // fieldsPayload(names: List[String]): RowPayload.Fields
    QualifiedName("fieldsPayload") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Lst(items)) =>
          val names = items.map {
            case PluginValue.Str(s) => s
            case _ => PluginError.raise("fieldsPayload(names) requires only Strings")
          }
          PluginValue.foreign("RowPayload", validateRowPayload(RowPayload.Fields(names), "fieldsPayload"))
        case _ => PluginError.raise("fieldsPayload(names)")
    },

    // ── Column kind constructors ─────────────────────────────────────────────

    // dateColumn(title, fieldPath, align?, format?): FieldColumnDef
    QualifiedName("dateColumn") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(title: String, fieldPath: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, None, None, ColumnKind.Date(), None))
        case List(title: String, fieldPath: String, align: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty), None, ColumnKind.Date(), None))
        case List(title: String, fieldPath: String, align: String, fmt) =>
          val fmtOpt = if isNullish(fmt) then None else Some(fmt.toString).filter(_.nonEmpty)
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty), None, ColumnKind.Date(fmtOpt), None))
        case _ => PluginError.raise("dateColumn(title, fieldPath[, align[, format]])")
    },

    // moneyColumn(title, fieldPath, align?, currency?, locale?): FieldColumnDef
    QualifiedName("moneyColumn") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(title: String, fieldPath: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, None, None, ColumnKind.Money(), None))
        case List(title: String, fieldPath: String, align: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty), None, ColumnKind.Money(), None))
        case List(title: String, fieldPath: String, align: String, currency: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty), None,
            ColumnKind.Money(Some(currency).filter(_.nonEmpty)), None))
        case List(title: String, fieldPath: String, align: String, currency: String, locale: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty), None,
            ColumnKind.Money(Some(currency).filter(_.nonEmpty), Some(locale).filter(_.nonEmpty)), None))
        case _ => PluginError.raise("moneyColumn(title, fieldPath[, align[, currency[, locale]]])")
    },

    // statusColumn(title, fieldPath, align?, colorMap?): FieldColumnDef
    QualifiedName("statusColumn") -> PluginNative.evalLegacy { (_, args) =>
      def toColorMap(v: Any): Map[String, String] = v match
        case PluginValue.Foreign("Map", m: Map[?, ?])    => m.asInstanceOf[Map[String, String]]
        case PluginValue.Foreign("Object", m: Map[?, ?]) => m.asInstanceOf[Map[String, String]]
        case _ => Map.empty
      args match
        case List(title: String, fieldPath: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, None, None, ColumnKind.StatusBadge(), None))
        case List(title: String, fieldPath: String, align: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty), None, ColumnKind.StatusBadge(), None))
        case List(title: String, fieldPath: String, align: String, colorMap) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty), None,
            ColumnKind.StatusBadge(toColorMap(colorMap)), None))
        case _ => PluginError.raise("statusColumn(title, fieldPath[, align[, colorMap]])")
    },

    // linkColumn(title, fieldPath, align?, urlTemplate?): FieldColumnDef
    QualifiedName("linkColumn") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(title: String, fieldPath: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, None, None, ColumnKind.Link(), None))
        case List(title: String, fieldPath: String, align: String) =>
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty), None, ColumnKind.Link(), None))
        case List(title: String, fieldPath: String, align: String, urlTemplate) =>
          val tplOpt = if isNullish(urlTemplate) then None else Some(urlTemplate.toString).filter(_.nonEmpty)
          PluginValue.foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty), None,
            ColumnKind.Link(tplOpt), None))
        case _ => PluginError.raise("linkColumn(title, fieldPath[, align[, urlTemplate]])")
    },

    // dataTableView(source, columns, actions[, rowKeyPath]): View
    // Builds a View.DataTable from a TableDataSource + lists of FieldColumnDef/RowActionDef.
    // Accepts either a TableDataSource Foreign value, or a FetchUrlSignal directly (legacy path).
    QualifiedName("dataTableView") -> PluginNative.evalLegacy { (_, args) =>
      def toColumns(v: Any): List[FieldColumnDef] = v match
        case PluginValue.Lst(items) => items.collect {
          case PluginValue.Foreign("FieldColumnDef", c: FieldColumnDef) => c }
        case _ => Nil
      def toActions(v: Any): List[RowActionDef] = v match
        case PluginValue.Lst(items) => items.collect {
          case PluginValue.Foreign("RowActionDef", a: RowActionDef) => a }
        case _ => Nil
      def build(source: Any, cols: Any, acts: Any, rowKeyPath: String): PluginValue = source match
        case PluginValue.Foreign("TableDataSource", src: TableDataSource) =>
          PluginValue.foreign("View",
            View.DataTable(src, toColumns(cols), toActions(acts), rowKeyPath = if rowKeyPath.isEmpty then "id" else rowKeyPath))
        case PluginValue.Foreign("ReactiveSignal", sig: FetchUrlSignal) =>
          PluginValue.foreign("View",
            View.DataTable(TableDataSource.Remote(sig), toColumns(cols), toActions(acts), rowKeyPath = if rowKeyPath.isEmpty then "id" else rowKeyPath))
        case _ => PluginError.raise("dataTableView: invalid source")
      args match
        case List(source, cols, acts) => build(source, cols, acts, "id")
        case List(source, cols, acts, rowKeyPath: String) => build(source, cols, acts, rowKeyPath)
        case _ => PluginError.raise("dataTableView(source, columns, actions[, rowKeyPath])")
    },

    // staticRowsSource(rows: List[Map[String, Any]]): TableDataSource.StaticRows
    QualifiedName("staticRowsSource") -> PluginNative.evalLegacy { (_, args) =>
      def toRows(v: Any): List[Map[String, Any]] = v match
        case PluginValue.Lst(items) => items.collect {
          case PluginValue.Foreign("Map", m: Map[?, ?]) => m.asInstanceOf[Map[String, Any]]
          case PluginValue.Foreign("Object", m: Map[?, ?]) => m.asInstanceOf[Map[String, Any]]
        }
        case _ => Nil
      args match
        case List(rows) =>
          PluginValue.foreign("TableDataSource", TableDataSource.StaticRows(toRows(rows)))
        case _ => PluginError.raise("staticRowsSource(rows)")
    },

    // signalRowsSource(sig: ReactiveSignal[?]): TableDataSource.SignalRows
    QualifiedName("signalRowsSource") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Foreign("ReactiveSignal", sig: ReactiveSignal[?])) =>
          PluginValue.foreign("TableDataSource", TableDataSource.SignalRows(sig))
        case _ => PluginError.raise("signalRowsSource(signal)")
    },

    // fetchRowsSource(sig: FetchUrlSignal, rowsPath: String): TableDataSource.Remote
    // Scope B.3 — a managed-fetch row source.  `rowsPath` (a dotted envelope path,
    // e.g. "result.items") is carried on the Remote source so the server-rendered
    // (custom / emit-jvm) fetch path drills it, matching the JS browser runtime's
    // `_ssc_ui_rowsOf(v, rowsPath)`.  Empty string = use the built-in envelope keys.
    QualifiedName("fetchRowsSource") -> PluginNative.evalLegacy { (_, args) =>
      def pathOf(v: Any): String = String.valueOf(v) match { case "null" => ""; case s => s }
      args match
        case List(PluginValue.Foreign("ReactiveSignal", sig: FetchUrlSignal), rowsPath) =>
          PluginValue.foreign("TableDataSource", TableDataSource.Remote(sig, pathOf(rowsPath)))
        case List(PluginValue.Foreign("ReactiveSignal", sig: FetchUrlSignal)) =>
          PluginValue.foreign("TableDataSource", TableDataSource.Remote(sig))
        case _ => PluginError.raise("fetchRowsSource(fetchSignal, rowsPath)")
    },
  )
