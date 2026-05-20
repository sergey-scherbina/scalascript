package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.frontend.*

/** v1.29 Phase 7a — `std/ui` primitive intrinsics.
 *
 *  Nine `extern def`s are the only JVM-side bridge needed by `std/ui/primitives.ssc`.
 *  All widget ADTs and lowering logic live in pure `.ssc` on top of these. */
val UiPrimitivesIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(

  // ── signal[T](name, default): Signal[T] ────────────────────────────────
  QualifiedName("signal") -> NativeImpl((_, args) =>
    args match
      case List(name: String, default: String)  =>
        Value.Foreign("ReactiveSignal", new ReactiveSignal[String](name, default))
      case List(name: String, default: Boolean) =>
        Value.Foreign("ReactiveSignal", new ReactiveSignal[Boolean](name, default))
      case List(name: String, default: Long)    =>
        Value.Foreign("ReactiveSignal", new ReactiveSignal[Int](name, default.toInt))
      case List(name: String, default: Double)  =>
        Value.Foreign("ReactiveSignal", new ReactiveSignal[Double](name, default))
      case _ => throw InterpretError("signal(name, default)")
  ),

  // ── element(tag, attrs, events, children): View ─────────────────────────
  QualifiedName("element") -> NativeImpl((ctx, args) =>
    args match
      case List(tag: String, attrsV, eventsV, childrenV) =>
        val attrs    = uiDecodeAttrs(attrsV.asInstanceOf[Value])
        val events   = uiDecodeEvents(eventsV.asInstanceOf[Value], ctx)
        val children = uiDecodeViewList(childrenV.asInstanceOf[Value])
        Value.Foreign("View", View.Element(tag, attrs, events, children))
      case _ => throw InterpretError("element(tag, attrs, events, children)")
  ),

  // ── textNode(s): View ────────────────────────────────────────────────────
  QualifiedName("textNode") -> NativeImpl((_, args) =>
    args match
      case List(s: String) => Value.Foreign("View", View.TextNode(() => s))
      case _               => throw InterpretError("textNode(s)")
  ),

  // ── signalText[T](s: Signal[T]): View ───────────────────────────────────
  QualifiedName("signalText") -> NativeImpl((_, args) =>
    args match
      case List(Value.Foreign("ReactiveSignal", rs: ReactiveSignal[?])) =>
        Value.Foreign("View", View.SignalText(rs))
      case _ => throw InterpretError("signalText(signal)")
  ),

  // ── showSignal(cond, whenTrue, whenFalse): View ──────────────────────────
  QualifiedName("showSignal") -> NativeImpl((_, args) =>
    args match
      case List(Value.Foreign("ReactiveSignal", cond: ReactiveSignal[?]),
                Value.Foreign("View", tv: View),
                Value.Foreign("View", fv: View)) =>
        Value.Foreign("View",
          View.ShowSignal(cond.asInstanceOf[ReactiveSignal[Boolean]], tv, fv))
      case _ => throw InterpretError("showSignal(cond, whenTrue, whenFalse)")
  ),

  // ── fragment(children: List[View]): View ────────────────────────────────
  QualifiedName("fragment") -> NativeImpl((_, args) =>
    args match
      case List(childrenV) =>
        Value.Foreign("View", View.Fragment(uiDecodeViewList(childrenV.asInstanceOf[Value])))
      case _ => throw InterpretError("fragment(children)")
  ),

  // ── setSignal[T](s: Signal[T], v: T): EventHandler ──────────────────────
  // `raw` arrives already unwrapped by installNativeIntrinsics (String/Boolean/Long/Double
  // for primitives; Value unchanged for complex types).
  QualifiedName("setSignal") -> NativeImpl((_, args) =>
    args match
      case List(Value.Foreign("ReactiveSignal", rs: ReactiveSignal[?]), raw) =>
        Value.Foreign("EventHandler",
          EventHandler.SetSignalLiteral(rs.asInstanceOf[ReactiveSignal[Any]], raw))
      case _ => throw InterpretError("setSignal(signal, value)")
  ),

  // ── eqSignal[T](s: Signal[T], value: T): Signal[Boolean] ─────────────────
  // Produces a derived boolean signal for routing guards.
  // jsName pattern "<base>__eq__<value>" is picked up by the React emitter.
  QualifiedName("eqSignal") -> NativeImpl((_, args) =>
    args match
      case List(Value.Foreign("ReactiveSignal", rs: ReactiveSignal[?]), raw) =>
        val initial = rs.apply().asInstanceOf[Any] == raw
        val safeSuffix = raw.toString.replaceAll("[^A-Za-z0-9]", "_")
        Value.Foreign("ReactiveSignal",
          new ReactiveSignal[Boolean](s"${rs.jsName}__eq__${safeSuffix}", initial))
      case _ => throw InterpretError("eqSignal(signal, value)")
  ),

  // ── hashSignal(): Signal[String] ────────────────────────────────────────
  // JVM: always returns ""; React emitter wires "__hash__" to hashchange.
  QualifiedName("hashSignal") -> NativeImpl((_, args) =>
    args match
      case List() => Value.Foreign("ReactiveSignal", new ReactiveSignal[String]("__hash__", ""))
      case _      => throw InterpretError("hashSignal()")
  ),

  // ── serve — frontend and REST variants ───────────────────────────────────
  // serve(tree, port)              — emit frontend, serve from temp dir
  // serve(port)                    — serve cwd as static/REST
  // serve(port, dir)               — serve dir as static/REST
  // serve(port, tls(cert, key))    — serve cwd with TLS
  QualifiedName("serve") -> NativeImpl((ctx, args) =>
    args match
      case List(Value.Foreign("View", view: View), port) =>
        val p      = port match { case n: Long => n.toInt; case _ => 8080 }
        val outDir = uiEmitToTempDir(view)
        if !ctx.headless then scalascript.server.WebServer.start(p, outDir, ctx.out)
      case List(port: Long) =>
        ctx.registerHealthDefaults()
        ctx.startServer(port.toInt, ".")
      case List(port: Long, dir: String) =>
        ctx.registerHealthDefaults()
        ctx.startServer(port.toInt, dir)
      case List(port: Long, Value.InstanceV("TlsContext", tlsFields)) =>
        ctx.registerHealthDefaults()
        val cert = tlsFields.get("cert").collect { case Value.StringV(s) => s }.getOrElse("")
        val key  = tlsFields.get("key").collect  { case Value.StringV(s) => s }.getOrElse("")
        ctx.startTlsServer(port.toInt, ".", cert, key)
      case _ => throw InterpretError("serve(tree, port), serve(port), serve(port, dir), or serve(port, tls(cert, key))")
  ),

  // ── emit(tree: View, outDir: String): Unit ───────────────────────────────
  QualifiedName("emit") -> NativeImpl((ctx, args) =>
    args match
      case List(Value.Foreign("View", view: View), dir: String) =>
        uiEmitToDir(view, dir, ctx.out)
      case _ => throw InterpretError("emit(tree, outDir)")
  ),
)

// ── Attr / event / view decoders ──────────────────────────────────────────

private def uiDecodeAttrs(v: Value): Map[String, AttrValue] = v match
  case Value.MapV(m) => m.collect {
    case (Value.StringV(k), Value.StringV(s))  => k -> AttrValue.Str(s)
    case (Value.StringV(k), Value.BoolV(b))    => k -> AttrValue.Bool(b)
    case (Value.StringV(k), Value.IntV(n))     => k -> AttrValue.Num(n.toDouble)
    case (Value.StringV(k), Value.DoubleV(d))  => k -> AttrValue.Num(d)
    case (Value.StringV(k), Value.Foreign("AttrValue",  a: AttrValue))  => k -> a
    case (Value.StringV(k), Value.Foreign("ReactiveSignal", rs: ReactiveSignal[?])) =>
      k -> AttrValue.Reactive(rs)
  }.toMap
  case _ => Map.empty

private def uiDecodeEvents(v: Value, ctx: NativeContext): Map[String, EventHandler] = v match
  case Value.MapV(m) => m.collect {
    case (Value.StringV(k), Value.Foreign("EventHandler", h: EventHandler)) => k -> h
    case (Value.StringV(k), fn: Value.FunV) =>
      k -> EventHandler.Simple(() => { ctx.invokeCallback(fn, Nil); () })
    case (Value.StringV(k), fn: Value.NativeFnV) =>
      k -> EventHandler.Simple(() => { ctx.invokeCallback(fn, Nil); () })
  }.toMap
  case _ => Map.empty

private def uiDecodeViewList(v: Value): Seq[View] = v match
  case Value.ListV(items) => items.collect { case Value.Foreign("View", view: View) => view }
  case Value.Foreign("View", view: View) => Seq(view)
  case _ => Seq.empty

// ── Emit helpers ──────────────────────────────────────────────────────────

private def uiBuildModule(view: View): FrontendModule =
  val app = ComponentDef("App", Nil, _ =>
    View.Element("div", Map("id" -> AttrValue.Str("ui-app")), Map.empty, Seq(view)))
  FrontendModule(List(app), "App", "/")

private def uiEmitToTempDir(view: View): String =
  val module  = uiBuildModule(view)
  val emitted = FrontendFrameworks.current().emit(module)
  val tmpDir  = java.nio.file.Files.createTempDirectory("ssc-ui")
  java.nio.file.Files.writeString(tmpDir.resolve("index.html"), emitted.html)
  java.nio.file.Files.writeString(tmpDir.resolve("app.js"),     emitted.js)
  if emitted.css.nonEmpty then
    java.nio.file.Files.writeString(tmpDir.resolve("app.css"), emitted.css)
  tmpDir.toString

private def uiEmitToDir(view: View, dir: String, out: java.io.PrintStream): Unit =
  val module  = uiBuildModule(view)
  val emitted = FrontendFrameworks.current().emit(module)
  val p = java.nio.file.Paths.get(dir)
  java.nio.file.Files.createDirectories(p)
  java.nio.file.Files.writeString(p.resolve("index.html"), emitted.html)
  java.nio.file.Files.writeString(p.resolve("app.js"),     emitted.js)
  if emitted.css.nonEmpty then
    java.nio.file.Files.writeString(p.resolve("app.css"), emitted.css)
  out.println(s"[ui.emit] wrote index.html + app.js → $dir")
