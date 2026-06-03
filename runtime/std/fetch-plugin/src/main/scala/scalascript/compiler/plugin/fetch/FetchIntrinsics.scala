package scalascript.compiler.plugin.fetch

import scala.annotation.nowarn
import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{InterpretError, Value}
import scalascript.frontend.{ReactiveSignal, FetchUrlSignal, FetchJsonSignal, EventHandler, View, FieldColumnDef, RowActionDef}
import scalascript.plugin.api.PluginNative

object FetchIntrinsics:
  private def isNullish(value: Any): Boolean =
    value == null || value == Value.UnitV || value == Value.NullV

  private def isEmptyHeadersArg(value: Any): Boolean =
    isNullish(value) || (value match
      case Value.NativeFnV("emptyHeaders", _) => true
      case _                                  => false)

  @nowarn("cat=deprecation")
  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // emptyHeaders: Signal[String] — no-op headers Signal (value always "").
    QualifiedName("emptyHeaders") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List() => Value.Foreign("ReactiveSignal", new ReactiveSignal[String]("__ssc_empty_headers", ""))
        case _ => throw InterpretError("emptyHeaders")
    },

    // fetchUrlSignal(name, url, refreshTick[, headers]): Signal[String]
    // Creates a ReactiveSignal[String] that re-fetches `url` whenever `refreshTick` increments.
    // On JVM (interpreter) the initial value is just ""; the JS runtime performs fetch on mount.
    QualifiedName("fetchUrlSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String, url: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          Value.Foreign("ReactiveSignal", new FetchUrlSignal(name, url, tick.id))
        case List(name: String, url: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val hId = headers.id
          Value.Foreign("ReactiveSignal",
            new FetchUrlSignal(name, url, tick.id,
              if hId == "__ssc_empty_headers" then None else Some(hId)))
        case _ => throw InterpretError("fetchUrlSignal(name, url, refreshTick[, headers])")
    },

    // fetchJsonSignal(name, url, refreshTick, modelTypeName[, headers]): Signal[String]
    // Like fetchUrlSignal but creates a FetchJsonSignal that decodes JSON into a named model type.
    // The runtime value is still ReactiveSignal[String] (raw JSON text on JVM); backends switch
    // on FetchJsonSignal.codec to emit the typed decode call.
    QualifiedName("fetchJsonSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String, url: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  modelTypeName: String) =>
          Value.Foreign("ReactiveSignal", new FetchJsonSignal(name, url, tick.id, modelTypeName))
        case List(name: String, url: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  modelTypeName: String,
                  Value.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val hId = headers.id
          Value.Foreign("ReactiveSignal",
            new FetchJsonSignal(name, url, tick.id, modelTypeName,
              if hId == "__ssc_empty_headers" then None else Some(hId)))
        case _ => throw InterpretError("fetchJsonSignal(name, url, refreshTick, modelTypeName[, headers])")
    },

    // fetchAction(method, url, body, onSuccessTick[, headers]): EventHandler
    // On click: fetch(url, {method, body: bodySignal.value, headers}) then increment onSuccessTick.
    QualifiedName("fetchAction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(method: String, url: String,
                  Value.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          Value.Foreign("EventHandler",
            EventHandler.FetchAction(method, url,
              body.asInstanceOf[ReactiveSignal[String]],
              tick.asInstanceOf[ReactiveSignal[Int]]))
        case List(method: String, url: String,
                  Value.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          Value.Foreign("EventHandler",
            EventHandler.FetchAction(method, url,
              body.asInstanceOf[ReactiveSignal[String]],
              tick.asInstanceOf[ReactiveSignal[Int]],
              headers = if h.id == "__ssc_empty_headers" then None else Some(h)))
        case _ => throw InterpretError("fetchAction(method, url, body, onSuccessTick[, headers])")
    },

    // fetchActionClear(method, url, body, onSuccessTick[, headers]): EventHandler
    // Like fetchAction but also clears the body signal to "" on success.
    QualifiedName("fetchActionClear") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(method: String, url: String,
                  Value.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          Value.Foreign("EventHandler",
            EventHandler.FetchAction(method, url,
              body.asInstanceOf[ReactiveSignal[String]],
              tick.asInstanceOf[ReactiveSignal[Int]],
              clearBody = true))
        case List(method: String, url: String,
                  Value.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          Value.Foreign("EventHandler",
            EventHandler.FetchAction(method, url,
              body.asInstanceOf[ReactiveSignal[String]],
              tick.asInstanceOf[ReactiveSignal[Int]],
              clearBody = true,
              headers = if h.id == "__ssc_empty_headers" then None else Some(h)))
        case _ => throw InterpretError("fetchActionClear(method, url, body, onSuccessTick[, headers])")
    },

    // incSignal(s): EventHandler — increment an Int signal by 1 on click.
    QualifiedName("incSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Value.Foreign("ReactiveSignal", rs: ReactiveSignal[?])) =>
          Value.Foreign("EventHandler",
            EventHandler.IncrementSignal(rs.asInstanceOf[ReactiveSignal[Int]], 1))
        case _ => throw InterpretError("incSignal(signal)")
    },

    // fieldColumn(title, fieldPath[, align[, editAction]]): FieldColumnDef
    QualifiedName("fieldColumn") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(title: String, fieldPath: String) =>
          Value.Foreign("FieldColumnDef", FieldColumnDef(title, fieldPath))
        case List(title: String, fieldPath: String, align: String) =>
          Value.Foreign("FieldColumnDef", FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty)))
        case List(title: String, fieldPath: String, align: String,
                  Value.Foreign("RowActionDef", ea: RowActionDef.RowInlineEdit)) =>
          Value.Foreign("FieldColumnDef",
            FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty), Some(ea)))
        case List(title: String, fieldPath: String, align: String, editAction)
            if isNullish(editAction) =>
          Value.Foreign("FieldColumnDef",
            FieldColumnDef(title, fieldPath, Some(align).filter(_.nonEmpty)))
        case _ => throw InterpretError("fieldColumn(title, fieldPath[, align[, editAction]])")
    },

    // rowDeleteAction(url, idField, tick[, headers]): RowActionDef
    QualifiedName("rowDeleteAction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(url: String, idField: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          Value.Foreign("RowActionDef",
            RowActionDef.RowDelete(url, idField, tick.asInstanceOf[ReactiveSignal[Int]]))
        case List(url: String, idField: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          Value.Foreign("RowActionDef",
            RowActionDef.RowDelete(url, idField, tick.asInstanceOf[ReactiveSignal[Int]],
              if h.id == "__ssc_empty_headers" then None else Some(h)))
        case List(url: String, idField: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]), headers)
            if isEmptyHeadersArg(headers) =>
          Value.Foreign("RowActionDef",
            RowActionDef.RowDelete(url, idField, tick.asInstanceOf[ReactiveSignal[Int]]))
        case _ => throw InterpretError("rowDeleteAction(url, idField, tick[, headers])")
    },

    // rowPostAction(label, method, url, bodyField, tick[, headers]): RowActionDef
    QualifiedName("rowPostAction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(label: String, method: String, url: String, bodyField: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          Value.Foreign("RowActionDef",
            RowActionDef.RowPost(label, method, url, bodyField, tick.asInstanceOf[ReactiveSignal[Int]]))
        case List(label: String, method: String, url: String, bodyField: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          Value.Foreign("RowActionDef",
            RowActionDef.RowPost(label, method, url, bodyField, tick.asInstanceOf[ReactiveSignal[Int]],
              if h.id == "__ssc_empty_headers" then None else Some(h)))
        case List(label: String, method: String, url: String, bodyField: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]), headers)
            if isEmptyHeadersArg(headers) =>
          Value.Foreign("RowActionDef",
            RowActionDef.RowPost(label, method, url, bodyField, tick.asInstanceOf[ReactiveSignal[Int]]))
        case _ => throw InterpretError("rowPostAction(label, method, url, bodyField, tick[, headers])")
    },

    // rowLinkAction(label, signal, fieldPath): RowActionDef
    QualifiedName("rowLinkAction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(label: String,
                  Value.Foreign("ReactiveSignal", sig: ReactiveSignal[?]),
                  fieldPath: String) =>
          Value.Foreign("RowActionDef",
            RowActionDef.RowLink(label, sig.asInstanceOf[ReactiveSignal[String]], fieldPath))
        case _ => throw InterpretError("rowLinkAction(label, signal, fieldPath)")
    },

    // rowEditAction(method, url, idField, tick[, headers]): RowActionDef
    QualifiedName("rowEditAction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(method: String, url: String, idField: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          Value.Foreign("RowActionDef",
            RowActionDef.RowInlineEdit(method, url, idField, tick.asInstanceOf[ReactiveSignal[Int]]))
        case List(method: String, url: String, idField: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          Value.Foreign("RowActionDef",
            RowActionDef.RowInlineEdit(method, url, idField, tick.asInstanceOf[ReactiveSignal[Int]],
              if h.id == "__ssc_empty_headers" then None else Some(h)))
        case List(method: String, url: String, idField: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]), headers)
            if isEmptyHeadersArg(headers) =>
          Value.Foreign("RowActionDef",
            RowActionDef.RowInlineEdit(method, url, idField, tick.asInstanceOf[ReactiveSignal[Int]]))
        case _ => throw InterpretError("rowEditAction(method, url, idField, tick[, headers])")
    },

    // dataTableView(signal, columns, actions): View
    // Builds a View.DataTable from a FetchUrlSignal + lists of FieldColumnDef/RowActionDef.
    QualifiedName("dataTableView") -> PluginNative.evalLegacy { (_, args) =>
      def toColumns(v: Value): List[FieldColumnDef] = v match
        case Value.ListV(items) => items.collect {
          case Value.Foreign("FieldColumnDef", c: FieldColumnDef) => c }
        case _ => Nil
      def toActions(v: Value): List[RowActionDef] = v match
        case Value.ListV(items) => items.collect {
          case Value.Foreign("RowActionDef", a: RowActionDef) => a }
        case _ => Nil
      args match
        case List(Value.Foreign("ReactiveSignal", sig: FetchUrlSignal),
                  cols: Value, acts: Value) =>
          Value.Foreign("View",
            View.DataTable(sig, toColumns(cols), toActions(acts)))
        case _ => throw InterpretError("dataTableView(signal, columns, actions)")
    },
  )
