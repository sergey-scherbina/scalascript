package scalascript.interpreter

import Computation.{Pure, Perform}

/** Built-in global definitions: standard library companions, HTML DSL,
 *  exception constructors, effect runners, and integration shims.
 *  Called once from `Interpreter.run` before user sections execute.
 */
private[interpreter] object BuiltinsRuntime:

  def initBuiltins(interp: Interpreter): Unit =
    def nativeP(name: String)(f: List[Value] => Value): Unit =
      interp.globals(name) = Value.NativeFnV(name, Computation.pureFn(f))
    // Deferred lookup: always returns a proxy NativeFnV that resolves the real
    // global at call time.  This supports Phase 2 lazy loading — plugin intrinsics
    // are not registered during initBuiltins, so we cannot look them up eagerly.
    // On first call, if the global is still missing, ensurePluginsLoaded() runs
    // to give the ServiceLoader a chance to register it.
    def globalOrStub(name: String): Value =
      Value.NativeFnV(name, args =>
        interp.globals.get(name) match
          case Some(fn) => interp.callValue(fn, args, Map.empty)
          case None if interp._pluginsLoaded =>
            throw InterpretError(s"'$name' requires a plugin that is not loaded")
          case None =>
            interp.ensurePluginsLoaded()
            interp.globals.get(name) match
              case Some(fn) => interp.callValue(fn, args, Map.empty)
              case None     => throw InterpretError(s"'$name' requires a plugin that is not loaded")
      )

    // Phase 2 lazy loading: install only the built-in interpreter intrinsics
    // (Console.println, assert, etc.) eagerly.  Plugin intrinsics (HTTP, SQL,
    // auth, …) are installed on first use via Interpreter.ensurePluginsLoaded()
    // so scripts like `hello.ssc` that never call a plugin never pay the
    // ServiceLoader scan cost.
    interp.installNativeIntrinsics(InterpreterIntrinsics)

    // Stage 5+/B.3 — Console companion object.
    // Normalize rewrites bare `println` → `Console.println`; the companion lets
    // user code also call `Console.println(...)` explicitly without the rewrite.
    interp.globals("Console") = Value.InstanceV("Console", Map(
      "println" -> interp.globals("Console.println"),
      "print"   -> interp.globals("Console.print")
    ))
    // Backward-compat aliases for tests / runSnippet callers that bypass Normalize.
    interp.globals("println") = interp.globals("Console.println")
    interp.globals("print")   = interp.globals("Console.print")

    // Plugin-provided companion objects (Db, DriverManager, Graph) are set up in
    // setupPluginCompanions, called from ensurePluginsLoaded after the plugin
    // intrinsics have been registered.

    // assert / require / nanoTime / getenv / doc / render / Some / List now
    // live in CoreIntrinsics (Stage 5+/E); installNativeIntrinsics routes them.
    // httpClient(baseUrl) { block } — handled as a special form in eval
    // (double-apply pattern) so the block is evaluated directly rather than
    // wrapped as a thunk.  See the Term.Apply case in eval below.
    // List companion object — fill/tabulate are curried (List.fill(n)(elem))
    interp.globals("List.fill") = Value.NativeFnV("List.fill", {
      case List(Value.IntV(n)) =>
        Pure(Value.NativeFnV("List.fill.n", {
          case List(elem) => Pure(Value.ListV(List.fill(n.toInt)(elem)))
          case _          => throw InterpretError("List.fill(n)(elem)")
        }))
      case _ => throw InterpretError("List.fill(n)(elem)")
    })
    interp.globals("List.tabulate") = Value.NativeFnV("List.tabulate", {
      case List(Value.IntV(n)) =>
        Pure(Value.NativeFnV("List.tabulate.n", {
          case List(f) =>
            // f(i) may perform effects — sequence the computations
            Computation.sequence((0 until n.toInt).toList.map(i =>
              interp.callValue(f, List(Value.intV(i)), Map.empty)))
          case _ => throw InterpretError("List.tabulate(n)(f)")
        }))
      case _ => throw InterpretError("List.tabulate(n)(f)")
    })
    interp.globals("List.range") = Value.NativeFnV("List.range", {
      case List(Value.IntV(from), Value.IntV(until)) =>
        Pure(Value.ListV((from.toInt until until.toInt).map(i => Value.intV(i)).toList))
      case List(Value.IntV(from), Value.IntV(until), Value.IntV(step)) =>
        Pure(Value.ListV((from.toInt until until.toInt by step.toInt).map(i => Value.intV(i)).toList))
      case _ => throw InterpretError("List.range(from, until[, step])")
    })
    val listNative = interp.globals("List")
    interp.globals("List") = Value.InstanceV("List", Map(
      "fill"     -> interp.globals("List.fill"),
      "tabulate" -> interp.globals("List.tabulate"),
      "range"    -> interp.globals("List.range"),
      "empty"    -> Value.EmptyList,
      "apply"    -> listNative
    ))
    // Map / math.sqrt-round now live in CoreIntrinsics (Stage 5+/E).
    interp.globals("None") = Value.NoneV
    interp.globals("Some") = Value.NativeFnV("Some", { case List(v) => Pure(Value.OptionV(Some(v))); case _ => throw InterpretError("Some requires exactly one argument") })
    interp.globals("Nil")  = Value.EmptyList

    // ── Exception constructors ────────────────────────────────────────
    // Allow `throw RuntimeException("msg")` and `try ... catch { case e: ... }`
    // in ScalaScript code.  Each factory produces an InstanceV so field access
    // like `e.message` works naturally.
    def exceptionCtor(typeName: String): Value.NativeFnV =
      Value.NativeFnV(typeName, {
        case Nil               => Pure(Value.InstanceV(typeName, Map("message" -> Value.StringV(typeName))))
        case List(v)           => Pure(Value.InstanceV(typeName, Map("message" -> v)))
        case msg :: cause :: _ => Pure(Value.InstanceV(typeName, Map("message" -> msg, "cause" -> cause)))
      })
    List("RuntimeException", "Exception", "IllegalArgumentException",
         "IllegalStateException", "NumberFormatException", "ArithmeticException",
         "NullPointerException", "IndexOutOfBoundsException", "UnsupportedOperationException",
         "NoSuchElementException").foreach { n => interp.globals(n) = exceptionCtor(n) }

    // ── attemptCatch — wrap a thunk that might throw into Either ─────────
    interp.globals("attemptCatch") = Value.NativeFnV("attemptCatch", {
      case List(thunk) =>
        try
          val result = Computation.run(interp.callValue(thunk, Nil, Map.empty))
          Pure(Value.InstanceV("Right", Map("value" -> result)))
        catch
          case se: ScriptException =>
            Pure(Value.InstanceV("Left", Map("value" -> se.value)))
          case t: Throwable =>
            val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
            Pure(Value.InstanceV("Left", Map("value" ->
              Value.InstanceV("RuntimeException", Map("message" -> Value.StringV(msg))))))
      case _ => interp.located("attemptCatch(thunk)")
    })

    // ── attemptCatchRaw — like attemptCatch but returns raw value (no Either boxing) ─
    interp.globals("attemptCatchRaw") = Value.NativeFnV("attemptCatchRaw", {
      case List(thunk) =>
        try
          val result = Computation.run(interp.callValue(thunk, Nil, Map.empty))
          Pure(result)
        catch
          case se: ScriptException => Pure(se.value)
          case t: Throwable =>
            val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
            Pure(Value.InstanceV(t.getClass.getSimpleName, Map("message" -> Value.StringV(msg))))
      case _ => interp.located("attemptCatchRaw(thunk)")
    })

    // ── v1.16 Restart object — decisions for restartable { } { } ────────
    // Restart.resume(v)  — resume the suspended computation with value v
    // Restart.useDefault — resume with Unit (null/default)
    // Restart.rethrow    — re-throw the original error as a ScriptException
    interp.globals("Restart") = Value.InstanceV("Restart$", Map(
      "resume" -> Value.NativeFnV("Restart.resume", {
        case List(v) => Pure(Value.InstanceV("Restart$resume", Map("value" -> v)))
        case _       => throw InterpretError("Restart.resume(value)")
      }),
      "useDefault" -> Value.InstanceV("Restart$useDefault", Map.empty),
      "rethrow"    -> Value.InstanceV("Restart$rethrow",    Map.empty)
    ))

    // ── currentStackTrace — returns call stack as List[Frame] ────────────
    // By default filters out anonymous (<anon>) and _-prefixed synthetic frames.
    // Call setTraceVerbose(true) to include all frames.
    interp.globals("currentStackTrace") = Value.NativeFnV("currentStackTrace", _ =>
      Pure(Value.ListV(interp.callStack.toList.reverse
        .filter { case (fn, _, _) =>
          interp.traceVerbose || (fn != "<anon>" && !fn.startsWith("_"))
        }
        .map { case (fn, file, line) =>
          Value.InstanceV("Frame", Map(
            "file" -> Value.StringV(file),
            "line" -> Value.intV(line),
            "fn"   -> Value.StringV(fn)
          ))
        }))
    )
    interp.globals("setTraceVerbose") = Value.NativeFnV("setTraceVerbose", {
      case List(Value.BoolV(v)) => interp.traceVerbose = v; Computation.PureUnit
      case _                    => Computation.PureUnit
    })

    // ── Using — RAII resource management (try-finally close) ─────────────
    //
    // `Using.resource(r) { r => block }` runs the block with the resource
    // and unconditionally calls `r.close()` afterwards (whether the block
    // returned normally or threw).  Mirrors `scala.util.Using.resource`
    // semantics but without the typeclass dance — the resource is closed
    // ducktyped: any value with a `.close` member is honoured.
    //
    // Typical use:
    //
    //   Using.resource(mcpConnect(Transport.Spawn("node", ["srv.js"]))) { client =>
    //     client.callTool("echo", Map("msg" -> "hi"))
    //   }
    //
    // Resources without a `.close` member are still supported (the
    // resource is just released to GC at block end) — useful when the
    // user wants the same scoping shape without commitment.
    interp.globals("Using") = Value.InstanceV("Using", Map(
      "resource" -> Value.NativeFnV("Using.resource", {
        case List(res) =>
          Pure(Value.NativeFnV("Using.resource.block", Computation.pureFn {
            case List(block) =>
              // Locate the close member: works on case-class instances
              // (InstanceV.fields("close")) and on plain Map literals
              // (MapV with key "close") alike.
              val closeOpt: Option[Value] = res match
                case Value.InstanceV(_, fields) => fields.get("close")
                case Value.MapV(m)              => m.get(Value.StringV("close"))
                case _                          => None
              try Computation.run(interp.callValue(block, List(res), Map.empty))
              finally
                closeOpt.foreach { closeFn =>
                  try { Computation.run(interp.callValue(closeFn, Nil, Map.empty)); () }
                  catch case _: Throwable => ()
                }
            case _ => throw InterpretError("Using.resource(r) { r => block }")
          }))
        case _ => throw InterpretError("Using.resource(r) { r => block }")
      })
    ))

    // ── McpSchema — derives target for case-class → JSON Schema ────────
    //
    // `case class WeatherArgs(city: String, units: String) derives McpSchema`
    // synthesises a `given McpSchema[WeatherArgs]` whose `schema` field is
    // a Map representation of the JSON Schema:
    //
    //   { type: "object",
    //     properties: { city: {}, units: {} },
    //     required: ["city", "units"] }
    //
    // The v1.14 Mirror exposes only field NAMES (no types) so the
    // properties stay loose — fine for MCP, where the LLM consumer
    // typically infers value shapes from descriptions anyway.  When the
    // user wants strict types, they can override the schema via
    // `srv.toolWithSchema(name, customSchema)(handler)`.
    interp.globals("McpSchema") = Value.InstanceV("McpSchema", Map(
      "derived" -> Value.NativeFnV("McpSchema.derived", {
        case List(Value.InstanceV("Mirror", mfields)) =>
          val fieldNames: List[String] = mfields.get("fields") match
            case Some(Value.ListV(xs)) => xs.collect { case Value.StringV(s) => s }
            case _                     => Nil
          val properties = Value.MapV(fieldNames.map(n =>
            (Value.StringV(n): Value) -> (Value.EmptyMap: Value)
          ).toMap)
          val required = Value.ListV(fieldNames.map(Value.StringV.apply))
          val schemaV = Value.MapV(Map(
            (Value.StringV("type"):       Value) -> (Value.StringV("object"): Value),
            (Value.StringV("properties"): Value) -> (properties:              Value),
            (Value.StringV("required"):   Value) -> (required:                Value)
          ))
          Pure(Value.InstanceV("McpSchema", Map("schema" -> schemaV)))
        case _ => Pure(Value.InstanceV("McpSchema", Map("schema" -> Value.EmptyMap)))
      })
    ))

    // ── compiletime — metaprogramming primitives ─────────────────────────
    interp.globals("compiletime") = Value.InstanceV("compiletime", Map(
      "error" -> Value.NativeFnV("compiletime.error", {
        case List(Value.StringV(msg)) => interp.located(s"compiletime.error: $msg")
        case List(v)                  => interp.located(s"compiletime.error: ${Value.show(v)}")
        case _                        => interp.located("compiletime.error: (no message)")
      }),
      // constValue and summonInline are handled as Term.ApplyType in eval;
      // these stubs exist so `compiletime` resolves as a namespace object.
      "constValue"    -> Value.NativeFnV("compiletime.constValue",    _ => Computation.PureUnit),
      "summonInline"  -> Value.NativeFnV("compiletime.summonInline",  _ => Computation.PureUnit)
    ))

    interp.globals("math.Pi")   = Value.doubleV(math.Pi)
    interp.globals("math.E")    = Value.doubleV(math.E)
    // math as an object so `math.sqrt(x)` works via field dispatch
    interp.globals("math") = Value.InstanceV("math", Map(
      "sqrt"  -> interp.globals("math.sqrt"),
      "abs"   -> interp.globals("math.abs"),
      "pow"   -> interp.globals("math.pow"),
      "floor" -> interp.globals("math.floor"),
      "ceil"  -> interp.globals("math.ceil"),
      "round" -> interp.globals("math.round"),
      "Pi"    -> interp.globals("math.Pi"),
      "E"     -> interp.globals("math.E")
    ))

    // v1.17.x — oauth namespace: standalone OAuth 2.1 Authorization Server.
    // Mirrors the `math` companion-object pattern: dotted QualifiedName
    // entries from `OAuthIntrinsics` get a sibling InstanceV bound to
    // `interp.globals("oauth")` so scripts can write `oauth.authServer(...)`.
    // v1.17.x — nested `oauth.client.*` namespace: OAuth client SDK
    // for .ssc apps (auth-code+PKCE, refresh, client_credentials,
    // TokenHolder).
    val oauthClient = Value.InstanceV("oauth.client", Map(
      "discoverAs"                 -> globalOrStub("oauth.client.discoverAs"),
      "discoverRs"                 -> globalOrStub("oauth.client.discoverRs"),
      "freshPkce"                  -> globalOrStub("oauth.client.freshPkce"),
      "freshState"                 -> globalOrStub("oauth.client.freshState"),
      "verifyState"                -> globalOrStub("oauth.client.verifyState"),
      "authorizationUrl"           -> globalOrStub("oauth.client.authorizationUrl"),
      "exchangeAuthorizationCode"  -> globalOrStub("oauth.client.exchangeAuthorizationCode"),
      "refresh"                    -> globalOrStub("oauth.client.refresh"),
      "clientCredentials"          -> globalOrStub("oauth.client.clientCredentials"),
      "tokenHolder"                -> globalOrStub("oauth.client.tokenHolder")
    ))
    interp.globals("oauth") = Value.InstanceV("oauth", Map(
      "authServer"          -> globalOrStub("oauth.authServer"),
      "serveAuthServer"     -> globalOrStub("oauth.serveAuthServer"),
      "issueHmacToken"      -> globalOrStub("oauth.issueHmacToken"),
      "pkceVerifier"        -> globalOrStub("oauth.pkceVerifier"),
      "pkceChallenge"       -> globalOrStub("oauth.pkceChallenge"),
      "guard"               -> globalOrStub("oauth.guard"),
      "guardWithValidator"  -> globalOrStub("oauth.guardWithValidator"),
      "hmacValidator"       -> globalOrStub("oauth.hmacValidator"),
      "client"              -> oauthClient
    ))
    // v1.17.x — oidc namespace: OpenID Connect Identity Provider on top
    // of the OAuth Authorization Server.
    interp.globals("oidc") = Value.InstanceV("oidc", Map(
      "server" -> globalOrStub("oidc.server"),
      "serve"  -> globalOrStub("oidc.serve")
    ))

    // escape / collectCss / collectJs / scope now live in CoreIntrinsics
    // (Stage 5+/E–F); installNativeIntrinsics routes them.

    // ─── i18n intrinsics: t / setLocale / wc ────────────────────────────
    interp.globals("t") = Value.NativeFnV("t", {
      case List(Value.StringV(key)) =>
        val v = interp.i18nTranslations.get(interp.i18nLocale).flatMap(_.get(key)).getOrElse(key)
        Pure(Value.StringV(v))
      case _ => Pure(Value.StringV(""))
    })
    interp.globals("setLocale") = Value.NativeFnV("setLocale", {
      case List(Value.StringV(code)) => interp.i18nLocale = code; Computation.PureUnit
      case _                         => Computation.PureUnit
    })
    interp.globals("wc") = Value.NativeFnV("wc", {
      case tag :: component :: rest =>
        val tagStr = Value.show(tag)
        val css = component match
          case Value.InstanceV(_, fields) =>
            fields.get("css").map(Value.show).getOrElse("")
          case _ => ""
        val renderFn = component match
          case Value.InstanceV(_, fields) => fields.get("render")
          case _                          => None
        renderFn match
          case Some(fn) =>
            interp.callValue(fn, rest, Map.empty).map { inner =>
              val innerHtml = inner match
                case Value.InstanceV("_Raw", fields) =>
                  fields.get("html").map(Value.show).getOrElse("")
                case v => Value.show(v)
              val shadow = s"<template shadowrootmode=\"open\"><style>$css</style>$innerHtml</template>"
              Value.InstanceV("_Raw", Map("html" -> Value.StringV(s"<$tagStr-component>$shadow</$tagStr-component>")))
            }
          case None =>
            Pure(Value.InstanceV("_Raw", Map("html" ->
              Value.StringV(s"<$tagStr-component></$tagStr-component>"))))
      case _ => Computation.PureUnit
    })

    // ─── Typed HTML DSL — `div(cls := "x", h1("hi"))` style ───────────
    //
    // Each tag is a native fn that takes a list of mixed args: Attr values
    // (key=value pairs from `<key> := <value>`) and children (Strings, _Raw
    // markers, or arbitrary Values rendered via Value.show).  The result is
    // a _Raw HTML node so it composes with html"..." without re-escaping.

    def attrKey(htmlName: String): Value.InstanceV =
      Value.InstanceV("AttrKey", Map("name" -> Value.StringV(htmlName)))

    // Attribute keys live under an `attr` namespace to avoid clobbering
    // very common user-side bindings like `name`, `id`, `title`, `value`.
    // Usage: `div(attr.cls := "hero", attr.id := "main")`.  Names that
    // collide with Scala reserved words use an underscore suffix
    // (`attr.type_`, `attr.for_`, `attr.method_`).
    interp.globals("attr") = Value.InstanceV("attr", Map(
      "cls"         -> attrKey("class"),
      "id"          -> attrKey("id"),
      "href"        -> attrKey("href"),
      "src"         -> attrKey("src"),
      "alt"         -> attrKey("alt"),
      "name"        -> attrKey("name"),
      "title"       -> attrKey("title"),
      "style"       -> attrKey("style"),
      "type_"       -> attrKey("type"),
      "value_"      -> attrKey("value"),
      "placeholder" -> attrKey("placeholder"),
      "method_"     -> attrKey("method"),
      "action"      -> attrKey("action"),
      "target"      -> attrKey("target"),
      "rel"         -> attrKey("rel"),
      "for_"        -> attrKey("for"),
      "role"        -> attrKey("role"),
      "colspan"     -> attrKey("colspan"),
      "rowspan"     -> attrKey("rowspan"),
      "disabled"    -> attrKey("disabled"),
    ))

    def htmlNode(s: String): Value.InstanceV =
      Value.InstanceV("_Raw", Map("html" -> Value.StringV(s)))

    /** Render a single child node: trusted html (_Raw) passes through,
     *  Lists flatten so `xs.map(li)` composes naturally inside a parent
     *  tag, everything else goes through `Value.show` + `htmlEscape`. */
    def renderChild(v: Value): String = v match
      case Value.InstanceV("_Raw", fields) =>
        fields.get("html").map(Value.show).getOrElse("")
      case Value.ListV(items) =>
        items.map(renderChild).mkString
      case other => interp.htmlEscape(Value.show(other))

    /** Split a tag's arg-list into attribute pairs (from `key := value`)
     *  and children (everything else, rendered as HTML).  A `ListV` arg
     *  flattens into multiple children. */
    def renderTag(name: String, args: List[Value], voidTag: Boolean = false): Value.InstanceV =
      val attrs    = scala.collection.mutable.LinkedHashMap.empty[String, String]
      val children = StringBuilder()
      def handle(v: Value): Unit = v match
        case Value.InstanceV("Attr", fields) =>
          val k = fields.get("name").map(Value.show).getOrElse("")
          val vv = fields.get("value").map(Value.show).getOrElse("")
          attrs(k) = vv
        case Value.ListV(items) =>
          items.foreach(handle)
        case other =>
          children ++= renderChild(other)
      args.foreach(handle)
      val attrStr =
        if attrs.isEmpty then ""
        else attrs.map((k, v) => s""" $k="${interp.htmlEscape(v)}"""").mkString
      if voidTag then htmlNode(s"<$name$attrStr>")
      else            htmlNode(s"<$name$attrStr>${children.toString}</$name>")

    // All tags live at the top level for ergonomics — `div(...)`, `h1(...)`,
    // `body(...)`.  When user code rebinds one of these names (`val body =
    // req.body` inside a route handler) the local binding shadows the tag
    // global the usual way, just like any other top-level definition.
    val containerTags = List(
      "html", "head", "body", "title", "style", "script", "main",
      "section", "header", "footer", "nav", "article", "aside",
      "div", "span", "p", "a", "em", "strong", "small", "code", "pre",
      "h1", "h2", "h3", "h4", "h5", "h6",
      "ul", "ol", "li", "dl", "dt", "dd",
      "table", "thead", "tbody", "tfoot", "tr", "td", "th",
      "form", "button", "label", "select", "option", "textarea",
      "figure", "figcaption", "blockquote",
    )
    containerTags.foreach { t => nativeP(t) { args => renderTag(t, args) } }

    // Void tags: no children, no closing tag.  `<br>`, `<img src=...>`, etc.
    val voidTags = List("br", "hr", "img", "input", "link", "meta")
    voidTags.foreach { t => nativeP(t) { args => renderTag(t, args, voidTag = true) } }

    // raw(s) marks a string as pre-escaped HTML so html"..." doesn't re-escape.
    nativeP("raw") {
      case List(Value.StringV(s)) => Value.InstanceV("_Raw", Map("html" -> Value.StringV(s)))
      case List(v)                => Value.InstanceV("_Raw", Map("html" -> Value.StringV(Value.show(v))))
      case _                      => throw InterpretError("raw(s)")
    }

    // mkResponse / bodyOf / toJson / jsonStringify / jsonParse now live in
    // JsonIntrinsics + HttpIntrinsics (Stage 5+/E).

    // wrapJson / jsonRead / lookupKey / lookup / lookupOpt now live in
    // JsonIntrinsics (Stage 5+/E).

    // lookupKey / lookup / lookupOpt — see above comment (Stage 5+/E).

    // fieldOf / recordOrThrow / requireX / optionalX / requireRange* / requireOneOf
    // now live in RequestIntrinsics (Stage 5+/E); validationRecord hook bridges
    // NativeContext to validationStack.
    // Response.html/text/json/redirect/notFound/status now live in HttpIntrinsics.
    // Response companion object — fields call the underlying natives.
    // Response.basicAuthChallenge now lives in AuthIntrinsics (Stage 5+/D).
    interp.globals("Response") = Value.InstanceV("Response", Map(
      "apply"              -> Value.NativeFnV("Response.apply", {
        case List(status, headers, body) =>
          Pure(Value.InstanceV("Response", Map("status" -> status, "headers" -> headers, "body" -> body)))
        case _ => throw InterpretError("Response(status, headers, body) expects 3 arguments")
      }),
      "html"               -> globalOrStub("Response.html"),
      "text"               -> globalOrStub("Response.text"),
      "json"               -> globalOrStub("Response.json"),
      "redirect"           -> globalOrStub("Response.redirect"),
      "notFound"           -> globalOrStub("Response.notFound"),
      "status"             -> globalOrStub("Response.status"),
      "basicAuthChallenge" -> globalOrStub("Response.basicAuthChallenge")
    ))

    // csrfToken / csrfValid / base64Url* / webauthn* / rateLimit* / totp* /
    // hashPassword / verifyPassword / cookieConfig / useSessionStore /
    // jwt* / oauth* / Response.basicAuthChallenge now live in AuthIntrinsics.
    // metrics / setMaxWsConnections / WsRoom now live in WsIntrinsics.
    // (Stage 5+/D)

    // route / serve / stop / tls / httpGet / httpPost / httpPut / httpPatch /
    // httpDelete / httpGetStream / httpPostStream / wsConnect / cors / useGzip /
    // cacheable / noCache / streamResponse / sse / maxBodySize /
    // uploadSpoolThreshold / uploadDir / use / httpTimeout / httpRetry /
    // onWebSocket / onWebSocketAuth / Response.* now live in HttpIntrinsics.
    // assert / require / nanoTime / getenv / doc / render / Some / List / Map /
    // math.* / escape now live in CoreIntrinsics.
    // jsonStringify / jsonParse / jsonRead / lookup / lookupOpt in JsonIntrinsics.
    // requireX / optionalX / requireRange* / requireOneOf in RequestIntrinsics.
    // (Stage 5+/B through 5+/E)

    // ── Storage: built-in effect for key-value persistence ──────────
    //
    // Same Free-Monad shape as Async: each op produces a `Perform`
    // node; `runStorage(body)` is the JSON file-backed handler and
    // `runEphemeralStorage(body)` is the in-memory test handler.
    interp.globals("Storage") = Value.InstanceV("Storage", Map(
      "get"    -> Value.NativeFnV("Storage.get",
        args => Perform("Storage", "get", args)),
      "put"    -> Value.NativeFnV("Storage.put",
        args => Perform("Storage", "put", args)),
      "remove" -> Value.NativeFnV("Storage.remove",
        args => Perform("Storage", "remove", args)),
      "has"    -> Value.NativeFnV("Storage.has",
        args => Perform("Storage", "has", args)),
      "keys"   -> Value.NativeFnV("Storage.keys",
        args => Perform("Storage", "keys", args)),
    ))

    // ── Async: built-in effect for async-style code ──────────────────
    //
    // Four operations — each produces a Perform node; `runAsync(body)`
    // is the default handler.  See `evalRunAsync` / `asyncDispatch`
    // below.  The model is single-threaded: thunks passed to
    // `async` / `parallel` execute immediately on the calling thread
    // (so output is deterministic and identical across all three
    // backends).  Real concurrency on the JVM is a handler-swap away.
    interp.globals("Async") = Value.InstanceV("Async", Map(
      "delay"    -> Value.NativeFnV("Async.delay",
        args => Perform("Async", "delay", args)),
      "async"    -> Value.NativeFnV("Async.async",
        args => Perform("Async", "async", args)),
      "await"    -> Value.NativeFnV("Async.await",
        args => Perform("Async", "await", args)),
      "parallel" -> Value.NativeFnV("Async.parallel",
        args => Perform("Async", "parallel", args)),
      "recvFrom" -> Value.NativeFnV("Async.recvFrom",
        args => Perform("Async", "recvFrom", args)),
    ))
    // `Future(v)` — wrap a value in a Future cell.  Used by handlers
    // to materialise the result of an `async` thunk; users normally
    // only construct Futures via `Async.async(...)`.
    interp.globals("Future") = Value.NativeFnV("Future", {
      case List(v) => Pure(Value.InstanceV("Future", Map("value" -> v)))
      case _       => throw InterpretError("Future(value)")
    })

    // ── v1.4 standard-library effects ────────────────────────────────────
    StdEffectsRuntime.install(interp)

    // ── v1.6 Actors — Phase 1/2/3 natives ──────────────────────────────
    ActorGlobals.install(interp)

    // ── v1.9 Coroutines + v1.10 Generator + suspend ────────────────────
    CoroutineRuntime.install(interp)

    // ── Reactive primitives: Signal / computed / effect ────────────────
    SignalRuntime.install(interp)

    // ── v1.21 Dataset — lazy local map-reduce pipeline ──────────────────
    DatasetRuntime.install(interp)

    // ── v2.x std.fs — synchronous file primitives ───────────────────────
    // Gated by Feature.FileSystem; mirrors the JS Node fs.* and JVM
    // java.nio.file calls so the same script works on all three backends.
    interp.globals("writeFile") = Value.NativeFnV("writeFile", {
      case List(Value.StringV(path), Value.StringV(contents)) =>
        val p = java.nio.file.Paths.get(path)
        java.nio.file.Files.write(p, contents.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        Computation.PureUnit
      case _ => throw InterpretError("writeFile(path: String, contents: String): Unit")
    })
    interp.globals("readFile") = Value.NativeFnV("readFile", {
      case List(Value.StringV(path)) =>
        val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))
        Pure(Value.StringV(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
      case _ => throw InterpretError("readFile(path: String): String")
    })
    interp.globals("deleteFile") = Value.NativeFnV("deleteFile", {
      case List(Value.StringV(path)) =>
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path))
        Computation.PureUnit
      case _ => throw InterpretError("deleteFile(path: String): Unit")
    })
    interp.globals("exists") = Value.NativeFnV("exists", {
      case List(Value.StringV(path)) =>
        Computation.pureBool(java.nio.file.Files.exists(java.nio.file.Paths.get(path)))
      case _ => throw InterpretError("exists(path: String): Boolean")
    })

  /** Install plugin-provided companion objects (Db, DriverManager, Graph) after their
   *  NativeImpl intrinsics have been registered via `ensurePluginsLoaded`.
   *  Must be called after `installNativeIntrinsics(pluginImpls)` so the
   *  underlying globals are present. */
  def setupPluginCompanions(interp: Interpreter): Unit =
    interp.globals.get("DriverManager.getConnection").foreach { impl =>
      interp.globals("DriverManager") = Value.InstanceV("DriverManager", Map(
        "getConnection" -> impl
      ))
    }
    (interp.globals.get("Db.query"), interp.globals.get("Db.execute")) match
      case (Some(queryFn), Some(executeFn)) =>
        interp.globals("Db") = Value.InstanceV("Db", Map(
          "query"   -> queryFn,
          "execute" -> executeFn,
        ) ++ interp.globals.get("Db.insert").map("insert" -> _) ++
          interp.globals.get("Db.update").map("update" -> _))
      case _ => ()
    interp.globals.get("Graph.putVertex").foreach { putVertexFn =>
      interp.globals("Graph") = Value.InstanceV("Graph", Map(
        "putVertex"      -> putVertexFn,
        "getVertex"      -> interp.globals("Graph.getVertex"),
        "vertices"       -> interp.globals("Graph.vertices"),
        "putEdge"        -> interp.globals("Graph.putEdge"),
        "edges"          -> interp.globals("Graph.edges"),
        "neighborValues" -> interp.globals("Graph.neighborValues"),
        "neighbors"      -> interp.globals("Graph.neighbors"),
        "putRdf"         -> interp.globals("Graph.putRdf"),
        "getRdf"         -> interp.globals("Graph.getRdf"),
        "triples"        -> interp.globals("Graph.triples")
      ))
    }
    // v1.51 Streams — assemble Source companion from individual Source.* intrinsics
    interp.globals.get("Source.from").foreach { fromFn =>
      interp.globals("Source") = Value.InstanceV("Source", Map(
        "from"          -> fromFn,
        "single"        -> interp.globals.getOrElse("Source.single",        Value.UnitV),
        "empty"         -> interp.globals.getOrElse("Source.empty",         Value.UnitV),
        "fromGenerator" -> interp.globals.getOrElse("Source.fromGenerator", Value.UnitV),
        "signal"        -> interp.globals.getOrElse("Source.signal",        Value.UnitV),
        "bracket"       -> interp.globals.getOrElse("Source.bracket",       Value.UnitV),
        "fromSse"       -> interp.globals.getOrElse("Source.fromSse",       Value.UnitV),
        "fromWebSocket" -> interp.globals.getOrElse("Source.fromWebSocket", Value.UnitV),
        "tick"          -> interp.globals.getOrElse("Source.tick",          Value.UnitV),
        "unfold"        -> interp.globals.getOrElse("Source.unfold",        Value.UnitV),
        "fromCallback"  -> interp.globals.getOrElse("Source.fromCallback",  Value.UnitV),
      ))
    }
    interp.globals.get("OverflowStrategy.Backpressure").foreach { bp =>
      interp.globals("OverflowStrategy") = Value.InstanceV("OverflowStrategy", Map(
        "Backpressure" -> bp,
        "Block"        -> interp.globals.getOrElse("OverflowStrategy.Block",       Value.UnitV),
        "Drop"         -> interp.globals.getOrElse("OverflowStrategy.Drop",        Value.UnitV),
        "DropHead"     -> interp.globals.getOrElse("OverflowStrategy.DropHead",    Value.UnitV),
        "DropOldest"   -> interp.globals.getOrElse("OverflowStrategy.DropOldest",  Value.UnitV),
        "Fail"         -> interp.globals.getOrElse("OverflowStrategy.Fail",        Value.UnitV),
      ))
    }
    // v1.51.3 Sink + Flow companions
    interp.globals.get("Sink.foreach").foreach { fe =>
      interp.globals("Sink") = Value.InstanceV("Sink", Map(
        "foreach" -> fe,
        "fold"    -> interp.globals.getOrElse("Sink.fold",   Value.UnitV),
        "ignore"  -> interp.globals.getOrElse("Sink.ignore", Value.UnitV),
        "toList"      -> interp.globals.getOrElse("Sink.toList",      Value.UnitV),
        "toSseStream" -> interp.globals.getOrElse("Sink.toSseStream", Value.UnitV),
        "toWsRoom"    -> interp.globals.getOrElse("Sink.toWsRoom",    Value.UnitV),
      ))
    }
    interp.globals.get("Flow.map").foreach { fm =>
      interp.globals("Flow") = Value.InstanceV("Flow", Map(
        "map"          -> fm,
        "filter"       -> interp.globals.getOrElse("Flow.filter",       Value.UnitV),
        "fromFunction" -> interp.globals.getOrElse("Flow.fromFunction", Value.UnitV),
        "take"         -> interp.globals.getOrElse("Flow.take",         Value.UnitV),
        "drop"         -> interp.globals.getOrElse("Flow.drop",         Value.UnitV),
        "flatMap"      -> interp.globals.getOrElse("Flow.flatMap",      Value.UnitV),
        "scan"         -> interp.globals.getOrElse("Flow.scan",         Value.UnitV),
        "mapAsync"     -> interp.globals.getOrElse("Flow.mapAsync",     Value.UnitV),
        "recover"      -> interp.globals.getOrElse("Flow.recover",      Value.UnitV),
        "throttle"     -> interp.globals.getOrElse("Flow.throttle",     Value.UnitV),
        "debounce"     -> interp.globals.getOrElse("Flow.debounce",     Value.UnitV),
      ))
    }
    // v2.1.1 DStreams — assemble companions from individual DStream.* intrinsics
    interp.globals.get("Pipeline.create").foreach { createFn =>
      interp.globals("Pipeline") = Value.InstanceV("Pipeline", Map("create" -> createFn))
    }
    interp.globals.get("InMemory.source").foreach { sourceFn =>
      interp.globals("InMemory") = Value.InstanceV("InMemory", Map(
        "source"               -> sourceFn,
        "sourceWithTimestamps" -> interp.globals.getOrElse("InMemory.sourceWithTimestamps", Value.UnitV),
        "sink"                 -> interp.globals.getOrElse("InMemory.sink",                 Value.UnitV),
        "runAndCollect"        -> interp.globals.getOrElse("InMemory.runAndCollect",        Value.UnitV),
      ))
    }
    interp.globals.get("DSource.fromLocalSource").foreach { fromLocalFn =>
      interp.globals("DSource") = Value.InstanceV("DSource", Map(
        "fromLocalSource" -> fromLocalFn,
      ))
    }
    interp.globals.get("Backend.Direct").foreach { directFn =>
      interp.globals("Backend") = Value.InstanceV("Backend", Map(
        "Direct" -> directFn,
        "Native" -> interp.globals.getOrElse("Backend.Native", Value.UnitV),
        "Spark"  -> interp.globals.getOrElse("Backend.Spark",  Value.UnitV),
      ))
    }
    interp.globals.get("Window.fixed").foreach { fixedFn =>
      interp.globals("Window") = Value.InstanceV("Window", Map(
        "fixed"   -> fixedFn,
        "sliding" -> interp.globals.getOrElse("Window.sliding", Value.UnitV),
        "session" -> interp.globals.getOrElse("Window.session", Value.UnitV),
        "global"  -> interp.globals.getOrElse("Window.global",  Value.UnitV),
      ))
    }
    interp.globals.get("Trigger.afterWatermark").foreach { awFn =>
      interp.globals("Trigger") = Value.InstanceV("Trigger", Map(
        "afterWatermark"      -> awFn,
        "afterProcessingTime" -> interp.globals.getOrElse("Trigger.afterProcessingTime", Value.UnitV),
        "afterCount"          -> interp.globals.getOrElse("Trigger.afterCount",          Value.UnitV),
        "repeatedly"          -> interp.globals.getOrElse("Trigger.repeatedly",          Value.UnitV),
      ))
    }
    interp.globals.get("WatermarkStrategy.atEnd").foreach { atEndFn =>
      interp.globals("WatermarkStrategy") = Value.InstanceV("WatermarkStrategy", Map(
        "atEnd"                      -> atEndFn,
        "monotonicallyIncreasing"    -> interp.globals.getOrElse("WatermarkStrategy.monotonicallyIncreasing", Value.UnitV),
        "boundedOutOfOrder"          -> interp.globals.getOrElse("WatermarkStrategy.boundedOutOfOrder",      Value.UnitV),
      ))
    }
    interp.globals.get("AccumulationMode.Discarding").foreach { discFn =>
      interp.globals("AccumulationMode") = Value.InstanceV("AccumulationMode", Map(
        "Discarding"   -> discFn,
        "Accumulating" -> interp.globals.getOrElse("AccumulationMode.Accumulating", Value.UnitV),
      ))
    }
    interp.globals.get("KV").foreach { kvFn =>
      // KV is also used as a constructor directly; ensure it's accessible
      interp.globals("KV") = kvFn
    }
    // v2.1.6 — Production connector companions
    interp.globals.get("Kafka.source").foreach { srcFn =>
      interp.globals("Kafka") = Value.InstanceV("Kafka", Map(
        "source"         -> srcFn,
        "sourceAssigned" -> interp.globals.getOrElse("Kafka.sourceAssigned", Value.UnitV),
        "changelog"      -> interp.globals.getOrElse("Kafka.changelog",      Value.UnitV),
        "sink"           -> interp.globals.getOrElse("Kafka.sink",           Value.UnitV),
      ))
    }
    interp.globals.get("Files.source").foreach { srcFn =>
      interp.globals("Files") = Value.InstanceV("Files", Map(
        "source" -> srcFn,
        "sink"   -> interp.globals.getOrElse("Files.sink", Value.UnitV),
      ))
    }
    interp.globals.get("FileFormat.Text").foreach { textFn =>
      interp.globals("FileFormat") = Value.InstanceV("FileFormat", Map(
        "Text"    -> textFn,
        "Json"    -> interp.globals.getOrElse("FileFormat.Json",    Value.UnitV),
        "Parquet" -> interp.globals.getOrElse("FileFormat.Parquet", Value.UnitV),
        "Avro"    -> interp.globals.getOrElse("FileFormat.Avro",    Value.UnitV),
        "Csv"     -> interp.globals.getOrElse("FileFormat.Csv",     Value.UnitV),
      ))
    }
    interp.globals.get("Jdbc.source").foreach { srcFn =>
      interp.globals("Jdbc") = Value.InstanceV("Jdbc", Map(
        "source" -> srcFn,
        "sink"   -> interp.globals.getOrElse("Jdbc.sink", Value.UnitV),
      ))
    }
    interp.globals.get("Pulsar.source").foreach { srcFn =>
      interp.globals("Pulsar") = Value.InstanceV("Pulsar", Map(
        "source" -> srcFn,
        "sink"   -> interp.globals.getOrElse("Pulsar.sink", Value.UnitV),
      ))
    }
    interp.globals.get("Kinesis.source").foreach { srcFn =>
      interp.globals("Kinesis") = Value.InstanceV("Kinesis", Map(
        "source" -> srcFn,
        "sink"   -> interp.globals.getOrElse("Kinesis.sink", Value.UnitV),
      ))
    }
    interp.globals.get("DSource.fromDataset").foreach { fromDatasetFn =>
      val existing = interp.globals.get("DSource").collect {
        case Value.InstanceV(_, fs) => fs
      }.getOrElse(Map.empty)
      interp.globals("DSource") = Value.InstanceV("DSource", existing + ("fromDataset" -> fromDatasetFn))
    }
    // v2.1.7 — KeyedStateSpec companion
    interp.globals.get("KeyedStateSpec.value").foreach { valueFn =>
      interp.globals("KeyedStateSpec") = Value.InstanceV("KeyedStateSpec", Map("value" -> valueFn))
    }
    // v2.1.8 — SideInput + OutputTag companions
    interp.globals.get("SideInput.of").foreach { ofFn =>
      interp.globals("SideInput") = Value.InstanceV("SideInput", Map(
        "of"        -> ofFn,
        "singleton" -> interp.globals.getOrElse("SideInput.singleton", Value.UnitV),
        "asMap"     -> interp.globals.getOrElse("SideInput.asMap",     Value.UnitV),
      ))
    }
    interp.globals.get("OutputTag").foreach { tagFn =>
      interp.globals("OutputTag") = Value.InstanceV("OutputTag", Map(
        "apply"      -> tagFn,
        "withFilter" -> interp.globals.getOrElse("OutputTag.withFilter", Value.UnitV),
      ))
    }

  /** Invoke an interpreter Value (closure or native fn) from outside —
   *  used by WebServer to call route handlers in response to HTTP requests. */
