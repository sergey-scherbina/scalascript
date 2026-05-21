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

    // println / print / route / serve / stop now live in InterpreterIntrinsics
    // (Stage 5+/B); installNativeIntrinsics routes them through Backend.intrinsics.
    // Plugin intrinsics (loaded via ServiceLoader) are merged in at the right side.
    // Only NativeImpl entries are relevant for the interpreter; RuntimeCall/InlineCode
    // entries from code-generating backends on the classpath are ignored so they
    // cannot accidentally shadow a bundled NativeImpl.
    import scalascript.backend.spi.NativeImpl
    val pluginNativeImpls: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl] =
      scalascript.compiler.plugin.BackendRegistry.inProcess
        .iterator.flatMap(_.intrinsics)
        .collect { case entry @ (_, _: NativeImpl) => entry }
        .toMap
    interp.installNativeIntrinsics(InterpreterIntrinsics ++ pluginNativeImpls)

    // Stage 5+/B.3 — Console companion object mirrors math / Response companions.
    // Normalize rewrites bare `println` → `Console.println`; the companion lets
    // user code also call `Console.println(...)` explicitly without the rewrite.
    interp.globals("Console") = Value.InstanceV("Console", Map(
      "println" -> interp.globals("Console.println"),
      "print"   -> interp.globals("Console.print")
    ))
    // Backward-compat aliases: bare `println` / `print` still work in code that
    // bypasses the Normalize pass (tests, runSnippet, direct Interpreter.run calls).
    interp.globals("println") = interp.globals("Console.println")
    interp.globals("print")   = interp.globals("Console.print")

    // v1.26 — DriverManager companion so user code can write
    // `DriverManager.getConnection("jdbc:h2:mem:test")` directly (resolves
    // through the Select-on-interp.globals path).  The actual native impl is
    // registered as `QualifiedName("DriverManager.getConnection")` via
    // `JdbcIntrinsics`; the companion just routes the name lookup.
    interp.globals.get("DriverManager.getConnection").foreach { impl =>
      interp.globals("DriverManager") = Value.InstanceV("DriverManager", Map(
        "getConnection" -> impl
      ))
    }

    // Db companion object — dynamic SQL from route handlers.
    // `Db.query(dbName, sql, params)` and `Db.execute(dbName, sql, params)`
    // are registered via JdbcIntrinsics; the companion routes name lookup.
    (interp.globals.get("Db.query"), interp.globals.get("Db.execute")) match
      case (Some(queryFn), Some(executeFn)) =>
        interp.globals("Db") = Value.InstanceV("Db", Map(
          "query"   -> queryFn,
          "execute" -> executeFn,
        ))
      case _ => ()

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
              interp.callValue(f, List(Value.IntV(i)), Map.empty)))
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
      "empty"    -> Value.ListV(Nil),
      "apply"    -> listNative
    ))
    // Map / math.sqrt-round now live in CoreIntrinsics (Stage 5+/E).
    interp.globals("None") = Value.OptionV(None)
    interp.globals("Some") = Value.NativeFnV("Some", { case List(v) => Pure(Value.OptionV(Some(v))); case _ => throw InterpretError("Some requires exactly one argument") })
    interp.globals("Nil")  = Value.ListV(Nil)

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
        .filter { case (fn, _) =>
          interp.traceVerbose || (fn != "<anon>" && !fn.startsWith("_"))
        }
        .map { case (fn, line) =>
          Value.InstanceV("Frame", Map(
            "file" -> Value.StringV(""),
            "line" -> Value.IntV(line),
            "fn"   -> Value.StringV(fn)
          ))
        }))
    )
    interp.globals("setTraceVerbose") = Value.NativeFnV("setTraceVerbose", {
      case List(Value.BoolV(v)) => interp.traceVerbose = v; Pure(Value.UnitV)
      case _                    => Pure(Value.UnitV)
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
            (Value.StringV(n): Value) -> (Value.MapV(Map.empty): Value)
          ).toMap)
          val required = Value.ListV(fieldNames.map(Value.StringV.apply))
          val schemaV = Value.MapV(Map(
            (Value.StringV("type"):       Value) -> (Value.StringV("object"): Value),
            (Value.StringV("properties"): Value) -> (properties:              Value),
            (Value.StringV("required"):   Value) -> (required:                Value)
          ))
          Pure(Value.InstanceV("McpSchema", Map("schema" -> schemaV)))
        case _ => Pure(Value.InstanceV("McpSchema", Map("schema" -> Value.MapV(Map.empty))))
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
      "constValue"    -> Value.NativeFnV("compiletime.constValue",    _ => Pure(Value.UnitV)),
      "summonInline"  -> Value.NativeFnV("compiletime.summonInline",  _ => Pure(Value.UnitV))
    ))

    interp.globals("math.Pi")   = Value.DoubleV(math.Pi)
    interp.globals("math.E")    = Value.DoubleV(math.E)
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
      "discoverAs"                 -> interp.globals("oauth.client.discoverAs"),
      "discoverRs"                 -> interp.globals("oauth.client.discoverRs"),
      "freshPkce"                  -> interp.globals("oauth.client.freshPkce"),
      "freshState"                 -> interp.globals("oauth.client.freshState"),
      "verifyState"                -> interp.globals("oauth.client.verifyState"),
      "authorizationUrl"           -> interp.globals("oauth.client.authorizationUrl"),
      "exchangeAuthorizationCode"  -> interp.globals("oauth.client.exchangeAuthorizationCode"),
      "refresh"                    -> interp.globals("oauth.client.refresh"),
      "clientCredentials"          -> interp.globals("oauth.client.clientCredentials"),
      "tokenHolder"                -> interp.globals("oauth.client.tokenHolder")
    ))
    interp.globals("oauth") = Value.InstanceV("oauth", Map(
      "authServer"          -> interp.globals("oauth.authServer"),
      "serveAuthServer"     -> interp.globals("oauth.serveAuthServer"),
      "issueHmacToken"      -> interp.globals("oauth.issueHmacToken"),
      "pkceVerifier"        -> interp.globals("oauth.pkceVerifier"),
      "pkceChallenge"       -> interp.globals("oauth.pkceChallenge"),
      "guard"               -> interp.globals("oauth.guard"),
      "guardWithValidator"  -> interp.globals("oauth.guardWithValidator"),
      "hmacValidator"       -> interp.globals("oauth.hmacValidator"),
      "client"              -> oauthClient
    ))
    // v1.17.x — oidc namespace: OpenID Connect Identity Provider on top
    // of the OAuth Authorization Server.
    interp.globals("oidc") = Value.InstanceV("oidc", Map(
      "server" -> interp.globals("oidc.server"),
      "serve"  -> interp.globals("oidc.serve")
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
      case List(Value.StringV(code)) => interp.i18nLocale = code; Pure(Value.UnitV)
      case _                         => Pure(Value.UnitV)
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
      case _ => Pure(Value.UnitV)
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
      "html"               -> interp.globals("Response.html"),
      "text"               -> interp.globals("Response.text"),
      "json"               -> interp.globals("Response.json"),
      "redirect"           -> interp.globals("Response.redirect"),
      "notFound"           -> interp.globals("Response.notFound"),
      "status"             -> interp.globals("Response.status"),
      "basicAuthChallenge" -> interp.globals("Response.basicAuthChallenge")
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
        Pure(Value.UnitV)
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
        Pure(Value.UnitV)
      case _ => throw InterpretError("deleteFile(path: String): Unit")
    })
    interp.globals("exists") = Value.NativeFnV("exists", {
      case List(Value.StringV(path)) =>
        Pure(Value.BoolV(java.nio.file.Files.exists(java.nio.file.Paths.get(path))))
      case _ => throw InterpretError("exists(path: String): Boolean")
    })

  /** Invoke an interpreter Value (closure or native fn) from outside —
   *  used by WebServer to call route handlers in response to HTTP requests. */

