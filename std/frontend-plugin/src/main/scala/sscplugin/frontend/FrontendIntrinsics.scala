package sscplugin.frontend

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{InterpretError, Value}
import scalascript.frontend.{ReactiveSignal, EventHandler}

object FrontendIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // NB: `args` is `List[Any]` post-unwrap (Interpreter.installNativeIntrinsics
    // strips `Value.StringV(s)` to a raw `String` before invoking the eval),
    // so the pattern matches the primitive type — not the `Value` wrapper.
    QualifiedName("setFrontendFramework") -> NativeImpl((_, args) =>
      args match
        case List(name: String) =>
          scalascript.frontend.FrontendFrameworks.setBackend(name)
          ()
        case _ => throw InterpretError("setFrontendFramework(name)")
    ),

    // ── inputChange(s: Signal[String]): EventHandler ──────────────────────
    // Wires a text input's onChange to update the signal with e.target.value.
    QualifiedName("inputChange") -> NativeImpl((_, args) =>
      args match
        case List(Value.Foreign("ReactiveSignal", rs: ReactiveSignal[?])) =>
          Value.Foreign("EventHandler", EventHandler.InputChange(rs.asInstanceOf[ReactiveSignal[String]]))
        case _ => throw InterpretError("inputChange(signal)")
    ),

    // ── toggleSignal(s: Signal[Boolean]): EventHandler ────────────────────
    // Wires a checkbox's onChange to flip the boolean signal.
    QualifiedName("toggleSignal") -> NativeImpl((_, args) =>
      args match
        case List(Value.Foreign("ReactiveSignal", rs: ReactiveSignal[?])) =>
          Value.Foreign("EventHandler", EventHandler.ToggleSignal(rs.asInstanceOf[ReactiveSignal[Boolean]]))
        case _ => throw InterpretError("toggleSignal(signal)")
    ),

  )
