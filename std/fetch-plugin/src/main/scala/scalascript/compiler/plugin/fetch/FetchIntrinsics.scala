package scalascript.compiler.plugin.fetch

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{InterpretError, Value}
import scalascript.frontend.{ReactiveSignal, FetchUrlSignal, EventHandler}

object FetchIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // fetchUrlSignal(name, url, refreshTick): Signal[String]
    // Creates a ReactiveSignal[String] that re-fetches `url` whenever `refreshTick` increments.
    // On JVM (interpreter) the initial value is just ""; the React emitter generates useEffect hooks.
    QualifiedName("fetchUrlSignal") -> NativeImpl((_, args) =>
      args match
        case List(name: String, url: String,
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          Value.Foreign("ReactiveSignal",
            new FetchUrlSignal(name, url, tick.jsName))
        case _ => throw InterpretError("fetchUrlSignal(name, url, refreshTick)")
    ),

    // fetchAction(method, url, body, onSuccessTick): EventHandler
    // On click: fetch(url, {method, body: bodySignal.value}) then increment onSuccessTick.
    QualifiedName("fetchAction") -> NativeImpl((_, args) =>
      args match
        case List(method: String, url: String,
                  Value.Foreign("ReactiveSignal", body: ReactiveSignal[?]),
                  Value.Foreign("ReactiveSignal", tick: ReactiveSignal[?])) =>
          Value.Foreign("EventHandler",
            EventHandler.FetchAction(method, url,
              body.asInstanceOf[ReactiveSignal[String]],
              tick.asInstanceOf[ReactiveSignal[Int]]))
        case _ => throw InterpretError("fetchAction(method, url, body, onSuccessTick)")
    ),

    // incSignal(s): EventHandler — increment an Int signal by 1 on click.
    QualifiedName("incSignal") -> NativeImpl((_, args) =>
      args match
        case List(Value.Foreign("ReactiveSignal", rs: ReactiveSignal[?])) =>
          Value.Foreign("EventHandler",
            EventHandler.IncrementSignal(rs.asInstanceOf[ReactiveSignal[Int]], 1))
        case _ => throw InterpretError("incSignal(signal)")
    ),
  )
