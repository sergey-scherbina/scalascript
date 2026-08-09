package scalascript.compiler.plugin.frontend

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.frontend.{ReactiveSignal, SeedSignal, EventHandler, View, AttrValue, FrontendModule, ComponentDef, FrontendFrameworks, FrontendFrameworkSpi, Platform}
import scalascript.ast.ModelDef
import scalascript.plugin.api.{PluginContext, PluginError, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Dbl, Bool, Lst, Inst, MapVal, Foreign, Fn}

object FrontendIntrinsics:

  private val computedIdCounter = new java.util.concurrent.atomic.AtomicInteger(0)

  // std/ui/offline.ssc backing state: per-process localStorage stand-in and
  // the single online-state signal (constant true off-browser).
  private val localStore = new java.util.concurrent.ConcurrentHashMap[String, String]()
  private lazy val onlineSignalInstance = new ReactiveSignal[Boolean]("__online__", true)

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
        case List(name: String, Lst(items)) =>
          PluginValue.foreign("ReactiveSignal", new ReactiveSignal[List[Any]](name, items))
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

    // Legacy lanes do not own NativeUi component lifetime. Preserve the public
    // generic result and exact-once evaluation while v2's UiNativePlugin adds
    // the scoped signal semantics behind the same source-level extern.
    QualifiedName("componentScope") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(_: String, body) => ctx.invokeCallback(body, Nil)
        case _ => PluginError.raise("componentScope(scopeId, bodyThunk)")
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

    // ── forKeyedView(items, key, render): View ─────────────────────────────
    // Interpreter/JVM fallback: render the current signal list once. Dynamic
    // keyed reconciliation is implemented in the JS emit-spa runtime where the
    // original render callback remains available in the browser.
    QualifiedName("forKeyedView") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Foreign("ReactiveSignal", rs: ReactiveSignal[?]), Fn(_), Fn(renderFn)) =>
          val rows = rs.apply() match
            case xs: Iterable[?] => xs.toList
            case _               => Nil
          val views = rows.flatMap { item =>
            ctx.invokeCallback(renderFn, List(item)) match
              case Foreign("View", view: View[?]) => Some(view)
              case _                              => None
          }
          PluginValue.foreign("View", View.Fragment(views))
        case _ => PluginError.raise("forKeyedView(items, key, render)")
    },

    // ── forJsonView(items, key, render): View ──────────────────────────────
    // `items` is a Signal[String] holding a JSON-array string. The dynamic parse +
    // keyed reconciliation lives in the JS emit-spa runtime (_ssc_ui_forJsonView /
    // _mountForJson), where the render callback stays live in-browser; the
    // interpreter/JVM fallback renders empty (the browser runtime is authoritative,
    // same stance as the forKeyedView/selectFromView notes above).
    QualifiedName("forJsonView") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Foreign("ReactiveSignal", _: ReactiveSignal[?]), _: String, Fn(_)) =>
          PluginValue.foreign("View", View.Fragment(Nil))
        case _ => PluginError.raise("forJsonView(items, key, render)")
    },

    // ── itemField(item, name): String ──────────────────────────────────────
    // Read a String field off a parsed forJson row (a Map here); "" when absent/null.
    QualifiedName("itemField") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(item, name: String) =>
          item match
            case m: scala.collection.Map[?, ?] =>
              m.asInstanceOf[scala.collection.Map[String, Any]].get(name) match
                case Some(v) if v != null => v.toString
                case _                    => ""
            case _ => ""
        case _ => PluginError.raise("itemField(item, name)")
    },

    // ── selectFromView[A](items, key, optionFn, selected, style, placeholder, disabled): View ──
    // Interpreter/JVM fallback: like forKeyedView above, render the current
    // signal list ONCE (no dynamic reconciliation here — that lives in the JS
    // emit-spa runtime's `_ssc_ui_selectFromView`/`_mountSelectFrom`, see
    // specs/std-ui-select.md § "Reactive options (selectFrom)"). Builds the
    // whole <select> element directly (mirrors SelectNode's own
    // element()-composed shape) rather than delegating to `element(...)`,
    // since this extern owns attrs/events/children together.
    QualifiedName("selectFromView") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Foreign("ReactiveSignal", rs: ReactiveSignal[?]), Fn(_), Fn(optionFn),
                  Foreign("ReactiveSignal", selRs: ReactiveSignal[?]),
                  style: String, placeholder: String, disabled: Boolean) =>
          val rows = rs.apply() match
            case xs: Iterable[?] => xs.toList
            case _               => Nil
          val cur = String.valueOf(selRs.apply())
          val optionEls: List[View[Nothing]] = rows.flatMap { item =>
            PluginValue.wrap(ctx.invokeCallback(optionFn, List(item))).asTuple match
              case Some(List(v, l)) =>
                val value    = v.asString.getOrElse("")
                val optLabel = l.asString.getOrElse("")
                Some(View.Element("option",
                  Map("value" -> AttrValue.Str(value), "selected" -> AttrValue.Bool(value == cur)),
                  Map.empty, Seq(View.TextNode(() => optLabel))))
              case _ => None
          }
          val placeholderEl: List[View[Nothing]] =
            if placeholder.isEmpty then Nil
            else List(View.Element("option",
              Map("value" -> AttrValue.Str(""), "selected" -> AttrValue.Bool(cur == ""),
                  "disabled" -> AttrValue.Bool(true), "hidden" -> AttrValue.Bool(true)),
              Map.empty, Seq(View.TextNode(() => placeholder))))
          val events: Map[String, EventHandler] =
            if disabled then Map.empty
            else Map("change" -> EventHandler.InputChange(selRs.asInstanceOf[ReactiveSignal[String]]))
          val attrs = Map(
            "style"    -> AttrValue.Str(style),
            "disabled" -> AttrValue.Bool(disabled),
            "value"    -> AttrValue.Reactive(selRs))
          PluginValue.foreign("View", View.Element("select", attrs, events, placeholderEl ++ optionEls))
        case _ => PluginError.raise("selectFromView(items, key, optionFn, selected, style, placeholder, disabled)")
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
          // Re-read the source on every read (see computedSignal): the JS
          // lane's eq wiring re-evaluates reactively; matching read-freshness
          // keeps derived state (e.g. std/ui/form formValid) INT==JS.
          PluginValue.foreign("ReactiveSignal",
            new ReactiveSignal[Boolean](s"${rs.id}__eq__${safeSuffix}", initial):
              override def apply(): Boolean = rs.apply().asInstanceOf[Any] == raw)
        case _ => PluginError.raise("eqSignal(signal, value)")
    },

    // ── hashSignal(): Signal[String] ────────────────────────────────────────
    // JVM: always returns ""; React emitter wires "__hash__" to hashchange.
    QualifiedName("hashSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List() => PluginValue.foreign("ReactiveSignal", new ReactiveSignal[String]("__hash__", ""))
        case _      => PluginError.raise("hashSignal()")
    },

    // ── localStorageGet/Set/Remove (std/ui/offline.ssc) ────────────────────
    // Off-browser lowering: a per-process map, so server-side/test code
    // exercises the same offline-first logic the browser runs over the real
    // localStorage (JsGen `_ssc_ui_localStorage*` shims).
    QualifiedName("localStorageGet") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(key: String) =>
          PluginValue.option(Option(localStore.get(key)).map(PluginValue.string))
        case _ => PluginError.raise("localStorageGet(key)")
    },
    QualifiedName("localStorageSet") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(key: String, value: String) => localStore.put(key, value); ()
        case _ => PluginError.raise("localStorageSet(key, value)")
    },
    QualifiedName("localStorageRemove") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(key: String) => localStore.remove(key); ()
        case _ => PluginError.raise("localStorageRemove(key)")
    },

    // ── onlineSignal(): Signal[Boolean] (std/ui/offline.ssc) ───────────────
    // One process-wide instance; constant true off-browser. The browser
    // runtime tracks navigator.onLine + online/offline window events.
    QualifiedName("onlineSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List() => PluginValue.foreign("ReactiveSignal", onlineSignalInstance)
        case _      => PluginError.raise("onlineSignal()")
    },

    // ── persistedSignal(name, default): Signal[String] (std/ui/offline.ssc) ─
    // Initialized from storage (else default); every set writes back, so the
    // value survives a reload (browser) / is observable via localStorageGet
    // (everywhere).
    QualifiedName("persistedSignal") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String, default: String) =>
          val init = Option(localStore.get(name)).getOrElse(default)
          val rs = new ReactiveSignal[String](name, init)
          rs.subscribe(v => localStore.put(name, v))
          PluginValue.foreign("ReactiveSignal", rs)
        case _ => PluginError.raise("persistedSignal(name, default)")
    },

    // ── WebAuthn browser actions (std/ui/webauthn.ssc) ───────────────────
    // Off-browser fallback: construct an EventHandler so static rendering and
    // interpreter tests can build the view tree. Invoking it reports a clear
    // unavailable error instead of pretending a passkey ceremony happened.
    QualifiedName("webauthnRegister") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case (_: String) :: (_: String) :: (_: String) ::
             Foreign("ReactiveSignal", _: ReactiveSignal[?]) ::
             Foreign("ReactiveSignal", err: ReactiveSignal[?]) :: _ =>
          PluginValue.foreign("EventHandler", EventHandler.Simple(() =>
            err.asInstanceOf[ReactiveSignal[String]].set("WebAuthn is only available in a browser")))
        case _ => PluginError.raise(
          "webauthnRegister(beginUrl, completeUrl, rpName, result, error[, headers, timeoutMs, userVerification])")
    },

    QualifiedName("webauthnAssert") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case (_: String) :: (_: String) ::
             Foreign("ReactiveSignal", _: ReactiveSignal[?]) ::
             Foreign("ReactiveSignal", err: ReactiveSignal[?]) :: _ =>
          PluginValue.foreign("EventHandler", EventHandler.Simple(() =>
            err.asInstanceOf[ReactiveSignal[String]].set("WebAuthn is only available in a browser")))
        case _ => PluginError.raise(
          "webauthnAssert(beginUrl, completeUrl, result, error[, headers, timeoutMs, userVerification])")
    },

    // ── computedSignal(f: () => String): Signal[String] ───────────────────────
    // JVM: evaluates f() once for the static initial value.
    // JS emitter wires "__computed__N" to a computed() ref that re-runs f reactively.
    QualifiedName("computedSignal") -> PluginNative.evalLegacy { (ctx, args) =>
      def asStr(raw: Any): String = raw match
        case s: String => s
        case Str(s)    => s
        case null      => ""
        case other     => other.toString
      args match
        case List(Fn(fn)) =>
          // Recompute on every read (tkv2-forms): the JS lane's computed()
          // re-runs the thunk on dependency change, so reads there are always
          // fresh — matching that read-freshness on the interpreter makes
          // reactive composition (e.g. form validation over draft signals)
          // conformance-testable INT==JS. Thunks are pure by contract. The
          // initial value is still computed eagerly for emitters that read
          // the signal once at build time.
          val initial = asStr(ctx.invokeCallback(fn, Nil))
          val rs = new ReactiveSignal[String](s"__computed__${computedIdCounter.getAndIncrement()}", initial):
            override def apply(): String = asStr(ctx.invokeCallback(fn, Nil))
          PluginValue.foreign("ReactiveSignal", rs)
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
    val backend = FrontendFrameworks.current()
    if !backend.supportedPlatforms.contains(Platform.Web) then
      PluginError.raise(
        s"serve(view, port) renders a web SPA, but the active frontend backend " +
        s"'${backend.name}' is native-only (${backend.supportedPlatforms.mkString(", ")}). " +
        s"Use emit(view, outDir) to write the native app sources, then build them " +
        s"(e.g. `cargo run` for the tui backend)."
      )
    val module  = uiBuildModule(ctx, view, extraCss)
    val emitted = backend.emit(module)
    val tmpDir  = java.nio.file.Files.createTempDirectory("ssc-ui")
    java.nio.file.Files.writeString(tmpDir.resolve("index.html"), emitted.html)
    java.nio.file.Files.writeString(tmpDir.resolve("app.js"),     emitted.js)
    if emitted.css.nonEmpty then
      java.nio.file.Files.writeString(tmpDir.resolve("app.css"), emitted.css)
    tmpDir.toString

  private def uiEmitToDir(ctx: scalascript.plugin.api.StorageCap, view: View[?], dir: String, out: java.io.PrintStream): Unit =
    val module = uiBuildModule(ctx, view)
    val p      = java.nio.file.Paths.get(dir)
    java.nio.file.Files.createDirectories(p)
    out.println(emitFrontendArtifact(FrontendFrameworks.current(), module, p))

  /** Dispatch the active frontend backend to the right artifact form: a web
   *  SPA (`emit` → html/js/css) or a native app bundle (`emitNative` → a
   *  source/resource file tree, e.g. the ratatui `Cargo.toml` + `src/main.rs`
   *  for the `tui` backend). Returns the human-facing log line. */
  private[frontend] def emitFrontendArtifact(
      backend: FrontendFrameworkSpi,
      module:  FrontendModule,
      p:       java.nio.file.Path,
  ): String =
    if backend.supportedPlatforms.contains(Platform.Web) then
      val emitted = backend.emit(module)
      java.nio.file.Files.writeString(p.resolve("index.html"), emitted.html)
      java.nio.file.Files.writeString(p.resolve("app.js"),     emitted.js)
      if emitted.css.nonEmpty then
        java.nio.file.Files.writeString(p.resolve("app.css"), emitted.css)
      s"[ui.emit] wrote index.html + app.js → $p"
    else
      val platform = backend.supportedPlatforms.headOption.getOrElse(Platform.All)
      backend.emitNative(module, platform) match
        case Some(app) =>
          (app.sources.iterator.map(s => (s._1, Right(s._2): Either[Array[Byte], String])) ++
           app.resources.iterator.map(r => (r._1, Left(r._2): Either[Array[Byte], String]))).foreach {
            case (rel, payload) =>
              val f = p.resolve(rel)
              Option(f.getParent).foreach(java.nio.file.Files.createDirectories(_))
              payload match
                case Right(text)  => java.nio.file.Files.writeString(f, text)
                case Left(bytes)  => java.nio.file.Files.write(f, bytes)
          }
          s"[ui.emit] wrote ${backend.name} app (${app.format}) → $p   —   build: ${app.buildScript}"
        case None =>
          s"[ui.emit] backend '${backend.name}' does not support platform $platform"
