package scalascript.compiler.plugin.fetch

import scala.annotation.nowarn
import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{InterpretError, Value}
import scalascript.frontend.{ReactiveSignal, FetchUrlSignal, FetchJsonSignal, EventHandler, View}
import scalascript.plugin.api.PluginNative

object FetchIntrinsics:

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

    // fetchTableView(fetchUrl, deleteUrl, tick[, headers]): View
    // Returns a View.FetchTable that the JS runtime lowers to a reactive table
    // with per-row Delete buttons.  The tableJsName is derived from fetchUrl.
    QualifiedName("fetchTableView") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(fetchUrl: String, deleteUrl: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          val tableJsName = "sscRows_" + fetchUrl.replaceAll("[^A-Za-z0-9]", "_")
          Value.Foreign("View",
            View.FetchTable(tableJsName, fetchUrl, deleteUrl, tick.asInstanceOf[ReactiveSignal[Int]]))
        case List(fetchUrl: String, deleteUrl: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", headers: ReactiveSignal[?])) =>
          val tableJsName = "sscRows_" + fetchUrl.replaceAll("[^A-Za-z0-9]", "_")
          val h = headers.asInstanceOf[ReactiveSignal[String]]
          Value.Foreign("View",
            View.FetchTable(tableJsName, fetchUrl, deleteUrl, tick.asInstanceOf[ReactiveSignal[Int]],
              headers = if h.id == "__ssc_empty_headers" then None else Some(h)))
        case _ => throw InterpretError("fetchTableView(fetchUrl, deleteUrl, tick[, headers])")
    },
  )
