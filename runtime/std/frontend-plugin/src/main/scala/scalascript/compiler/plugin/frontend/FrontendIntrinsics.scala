package scalascript.compiler.plugin.frontend

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.frontend.{ReactiveSignal, SeedSignal, EventHandler, View, AttrValue, FrontendModule, ComponentDef, FrontendFrameworks}
import scalascript.ast.ModelDef
import scalascript.plugin.api.{PluginContext, PluginError, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Dbl, Bool, Lst, Inst, MapVal, Foreign, Fn}

object FrontendIntrinsics:

  private val computedIdCounter = new java.util.concurrent.atomic.AtomicInteger(0)

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // NB: `args` is `List[Any]` post-unwrap (Interpreter.installNativeIntrinsics
    // strips `Value.StringV(s)` to a raw `String` before invoking the eval),
    // so the pattern matches the primitive type — not the `Value` wrapper.
    QualifiedName("setFrontendFramework") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String) =>
          scalascript.frontend.FrontendFrameworks.setBackend(name)
          ()
        case _ => PluginError.raise("setFrontendFramework(name)")
    },

    // ── inputChange(s: Signal[String]): EventHandler ──────────────────────
    // Wires a text input's onChange to update the signal with e.target.value.
    QualifiedName("inputChange") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Foreign("ReactiveSignal", rs: ReactiveSignal[?])) =>
          PluginValue.foreign("EventHandler", EventHandler.InputChange(rs.asInstanceOf[ReactiveSignal[String]]))
        case _ => PluginError.raise("inputChange(signal)")
    },

    // ── toggleSignal(s: Signal[Boolean]): EventHandler ────────────────────
    // Wires a checkbox's onChange to flip the boolean signal.
    QualifiedName("toggleSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Foreign("ReactiveSignal", rs: ReactiveSignal[?])) =>
          PluginValue.foreign("EventHandler", EventHandler.ToggleSignal(rs.asInstanceOf[ReactiveSignal[Boolean]]))
        case _ => PluginError.raise("toggleSignal(signal)")
    },

    // ── signal[T](name, default): Signal[T] ────────────────────────────────
    QualifiedName("signal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String, default: String)  =>
          PluginValue.foreign("ReactiveSignal", new ReactiveSignal[String](name, default))
        case List(name: String, default: Boolean) =>
          PluginValue.foreign("ReactiveSignal", new ReactiveSignal[Boolean](name, default))
        case List(name: String, default: Long)    =>
          PluginValue.foreign("ReactiveSignal", new ReactiveSignal[Int](name, default.toInt))
        case List(name: String, default: Double)  =>
          PluginValue.foreign("ReactiveSignal", new ReactiveSignal[Double](name, default))
        case _ => PluginError.raise("signal(name, default)")
    },

    // ── seedSignal(name, source): Signal[String] ───────────────────────────
    QualifiedName("seedSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String, Foreign("ReactiveSignal", source: ReactiveSignal[?])) =>
          PluginValue.foreign("ReactiveSignal",
            new SeedSignal(name, source.asInstanceOf[ReactiveSignal[String]]))
        case _ => PluginError.raise("seedSignal(name, source)")
    },

    // ── element(tag, attrs, events, children): View ─────────────────────────
    QualifiedName("element") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(tag: String, attrsV, eventsV, childrenV) =>
          val attrs    = uiDecodeAttrs(attrsV)
          val events   = uiDecodeEvents(eventsV, ctx)
          val children = uiDecodeViewList(childrenV)
          PluginValue.foreign("View", View.Element(tag, attrs, events, children))
        case _ => PluginError.raise("element(tag, attrs, events, children)")
    },

    // ── textNode(s): View ────────────────────────────────────────────────────
    QualifiedName("textNode") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(s: String) => PluginValue.foreign("View", View.TextNode(() => s))
        case _               => PluginError.raise("textNode(s)")
    },

    // ── signalText[T](s: Signal[T]): View ───────────────────────────────────
    QualifiedName("signalText") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Foreign("ReactiveSignal", rs: ReactiveSignal[?])) =>
          PluginValue.foreign("View", View.SignalText(rs))
        case _ => PluginError.raise("signalText(signal)")
    },

    // ── showSignal(cond, whenTrue, whenFalse): View ──────────────────────────
    QualifiedName("showSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Foreign("ReactiveSignal", cond: ReactiveSignal[?]),
                  Foreign("View", tv: View[?]),
                  Foreign("View", fv: View[?])) =>
          PluginValue.foreign("View",
            View.ShowSignal(cond.asInstanceOf[ReactiveSignal[Boolean]], tv, fv))
        case _ => PluginError.raise("showSignal(cond, whenTrue, whenFalse)")
    },

    // ── fragment(children: List[View]): View ────────────────────────────────
    QualifiedName("fragment") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(childrenV) =>
          PluginValue.foreign("View", View.Fragment(uiDecodeViewList(childrenV)))
        case _ => PluginError.raise("fragment(children)")
    },

    // ── setSignal[T](s: Signal[T], v: T): EventHandler ──────────────────────
    // `raw` arrives already unwrapped by installNativeIntrinsics (String/Boolean/Long/Double
    // for primitives; Value unchanged for complex types).
    QualifiedName("setSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Foreign("ReactiveSignal", rs: ReactiveSignal[?]), raw) =>
          PluginValue.foreign("EventHandler",
            EventHandler.SetSignalLiteral(rs.asInstanceOf[ReactiveSignal[Any]], raw))
        case _ => PluginError.raise("setSignal(signal, value)")
    },

    // ── eqSignal[T](s: Signal[T], value: T): Signal[Boolean] ─────────────────
    // Produces a derived boolean signal for routing guards.
    // jsName pattern "<base>__eq__<value>" is picked up by the React emitter.
    QualifiedName("eqSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Foreign("ReactiveSignal", rs: ReactiveSignal[?]), raw) =>
          val initial = rs.apply().asInstanceOf[Any] == raw
          val safeSuffix = raw.toString.replaceAll("[^A-Za-z0-9]", "_")
          PluginValue.foreign("ReactiveSignal",
            new ReactiveSignal[Boolean](s"${rs.id}__eq__${safeSuffix}", initial))
        case _ => PluginError.raise("eqSignal(signal, value)")
    },

    // ── hashSignal(): Signal[String] ────────────────────────────────────────
    // JVM: always returns ""; React emitter wires "__hash__" to hashchange.
    QualifiedName("hashSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List() => PluginValue.foreign("ReactiveSignal", new ReactiveSignal[String]("__hash__", ""))
        case _      => PluginError.raise("hashSignal()")
    },

    // ── computedSignal(f: () => String): Signal[String] ───────────────────────
    // JVM: evaluates f() once for the static initial value.
    // JS emitter wires "__computed__N" to a computed() ref that re-runs f reactively.
    QualifiedName("computedSignal") -> PluginNative.evalLegacy { (ctx, args) =>
      def mkSignal(raw: Any): PluginValue =
        val str = raw match
          case s: String => s
          case Str(s)    => s
          case null      => ""
          case other     => other.toString
        PluginValue.foreign("ReactiveSignal",
          new ReactiveSignal[String](s"__computed__${computedIdCounter.getAndIncrement()}", str))
      args match
        case List(Fn(fn)) => mkSignal(ctx.invokeCallback(fn, Nil))
        case _ => PluginError.raise("computedSignal(f)")
    },

    // ── serve — frontend and REST variants ───────────────────────────────────
    // serve(tree, port)              — emit frontend, serve from temp dir
    // serve(tree, port, extraCss)    — emit frontend with extra CSS injected
    // serve(port)                    — serve cwd as static/REST
    // serve(port, dir)               — serve dir as static/REST
    // serve(port, tls(cert, key))    — serve cwd with TLS
    QualifiedName("serve") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Foreign("View", view: View[?]), port) =>
          if ctx.openApiDryRun then ctx.abortOpenApiDryRun()
          val p      = port match { case n: Long => n.toInt; case _ => 8080 }
          val outDir = uiEmitToTempDir(ctx, view, "")
          if !ctx.headless then ctx.startServer(p, outDir)
        case List(Foreign("View", view: View[?]), port, extraCss: String) =>
          if ctx.openApiDryRun then ctx.abortOpenApiDryRun()
          val p      = port match { case n: Long => n.toInt; case _ => 8080 }
          val outDir = uiEmitToTempDir(ctx, view, extraCss)
          if !ctx.headless then ctx.startServer(p, outDir)
        case List(port: Long) =>
          ctx.registerHealthDefaults()
          ctx.registerOpenApiDefaults()
          if ctx.openApiDryRun then ctx.abortOpenApiDryRun()
          ctx.startServer(port.toInt, ".")
        case List(port: Long, dir: String) =>
          ctx.registerHealthDefaults()
          ctx.registerOpenApiDefaults()
          if ctx.openApiDryRun then ctx.abortOpenApiDryRun()
          ctx.startServer(port.toInt, dir)
        case List(port: Long, Inst("TlsContext", tlsFields)) =>
          ctx.registerHealthDefaults()
          ctx.registerOpenApiDefaults()
          if ctx.openApiDryRun then ctx.abortOpenApiDryRun()
          val cert = tlsFields.get("cert").collect { case Str(s) => s }.getOrElse("")
          val key  = tlsFields.get("key").collect  { case Str(s) => s }.getOrElse("")
          ctx.startTlsServer(port.toInt, ".", cert, key)
        case _ => PluginError.raise("serve(tree, port), serve(tree, port, extraCss), serve(port), serve(port, dir), or serve(port, tls(cert, key))")
    },

    // ── emit(tree: View, outDir: String): Unit ───────────────────────────────
    QualifiedName("emit") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Foreign("View", view: View[?]), dir: String) =>
          uiEmitToDir(ctx, view, dir, ctx.out)
        case _ => PluginError.raise("emit(tree, outDir)")
    },

  )

  // ── Attr / event / view decoders ──────────────────────────────────────────

  private def uiDecodeAttrs(v: Any): Map[String, AttrValue] = v match
    case MapVal(m) => m.collect {
      case (Str(k), Str(s))  => k -> AttrValue.Str(s)
      case (Str(k), Bool(b)) => k -> AttrValue.Bool(b)
      case (Str(k), Num(n))  => k -> AttrValue.Num(n.toDouble)
      case (Str(k), Dbl(d))  => k -> AttrValue.Num(d)
      case (Str(k), Foreign("AttrValue",  a: AttrValue))  => k -> a
      case (Str(k), Foreign("ReactiveSignal", rs: ReactiveSignal[?])) =>
        k -> AttrValue.Reactive(rs)
    }.toMap
    case _ => Map.empty

  private def uiDecodeEvents(v: Any, ctx: PluginContext): Map[String, EventHandler] = v match
    case MapVal(m) => m.collect {
      case (Str(k), Foreign("EventHandler", h: EventHandler)) => k -> h
      case (Str(k), Fn(fn)) =>
        k -> EventHandler.Simple(() => { ctx.invokeCallback(fn, Nil); () })
    }.toMap
    case _ => Map.empty

  private def uiDecodeViewList(v: Any): Seq[View[?]] = v match
    case Lst(items) => items.collect { case Foreign("View", view: View[?]) => view }
    case Foreign("View", view: View[?]) => Seq(view)
    case _ => Seq.empty

  // ── Emit helpers ──────────────────────────────────────────────────────────

  private val ModelsFeatureKey = "scalascript.frontend.models"

  private def contextModels(ctx: scalascript.plugin.api.StorageCap): List[ModelDef] =
    ctx.featureGet(ModelsFeatureKey).collect { case ms: List[?] =>
      ms.asInstanceOf[List[ModelDef]]
    }.getOrElse(Nil)

  private def uiBuildModule(ctx: scalascript.plugin.api.StorageCap, view: View[?], extraCss: String = ""): FrontendModule =
    val models = contextModels(ctx)
    val app = ComponentDef("App", Nil, _ =>
      View.Element("div", Map("id" -> AttrValue.Str("ui-app")), Map.empty, Seq(view)))
    FrontendModule(List(app), "App", "/", extraCss, models = models)

  private def uiEmitToTempDir(ctx: scalascript.plugin.api.StorageCap, view: View[?], extraCss: String): String =
    val module  = uiBuildModule(ctx, view, extraCss)
    val emitted = FrontendFrameworks.current().emit(module)
    val tmpDir  = java.nio.file.Files.createTempDirectory("ssc-ui")
    java.nio.file.Files.writeString(tmpDir.resolve("index.html"), emitted.html)
    java.nio.file.Files.writeString(tmpDir.resolve("app.js"),     emitted.js)
    if emitted.css.nonEmpty then
      java.nio.file.Files.writeString(tmpDir.resolve("app.css"), emitted.css)
    tmpDir.toString

  private def uiEmitToDir(ctx: scalascript.plugin.api.StorageCap, view: View[?], dir: String, out: java.io.PrintStream): Unit =
    val module  = uiBuildModule(ctx, view)
    val emitted = FrontendFrameworks.current().emit(module)
    val p = java.nio.file.Paths.get(dir)
    java.nio.file.Files.createDirectories(p)
    java.nio.file.Files.writeString(p.resolve("index.html"), emitted.html)
    java.nio.file.Files.writeString(p.resolve("app.js"),     emitted.js)
    if emitted.css.nonEmpty then
      java.nio.file.Files.writeString(p.resolve("app.css"), emitted.css)
    out.println(s"[ui.emit] wrote index.html + app.js → $dir")
