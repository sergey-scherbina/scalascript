package ssc.plugin.mcp

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import scala.jdk.CollectionConverters.*
import scalascript.mcp.{McpAuth, McpClientCore, McpProtocol, McpServerBuilder, McpServerCore,
                        PromptHandlerResult, ResourceHandlerResult, ToolHandlerResult}
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import ssc.{Done, Prims, Runtime, Show, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}
import ssc.plugin.json.NativeJsonCodec

/** Explicit core-free MCP Spawn client backed by the pure mcp-common protocol
 * runtime. No v1 interpreter, frontend, or compatibility bridge is involved. */
final class McpNativePlugin extends NativePlugin:
  def id: String = "90-mcp-explicit"

  /** `ElicitationResult(action, content)` in the DECLARED field order. `content` is an Option, and
   *  only "accept" carries one — the other two carry None, which is what the declaration says. */
  private def elicitationValue(r: scalascript.mcp.McpProtocol.ElicitationResult): Value = r match
    case scalascript.mcp.McpProtocol.ElicitationResult.Accept(content) =>
      Value.DataV("ElicitationResult", Vector(
        Value.StrV("accept"), Value.DataV("Some", Vector(json(content)))))
    case scalascript.mcp.McpProtocol.ElicitationResult.Decline =>
      Value.DataV("ElicitationResult", Vector(
        Value.StrV("decline"), Value.DataV("None", Vector.empty)))
    case scalascript.mcp.McpProtocol.ElicitationResult.Cancel =>
      Value.DataV("ElicitationResult", Vector(
        Value.StrV("cancel"), Value.DataV("None", Vector.empty)))

  private def closure(arity: Int)(fn: List[Value] => Value): Value =
    Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList)))

  private def text(value: Value, label: String): String = value match
    case Value.StrV(result) => result
    case _ => throw new IllegalArgumentException(s"mcpConnect: $label must be String")

  private def list(values: IterableOnce[Value]): Value =
    Vector.from(values).reverseIterator.foldLeft[Value](Value.DataV("Nil", Vector.empty)) {
      (tail, head) => Value.DataV("Cons", Vector(head, tail))
    }

  private def map(entries: IterableOnce[(Value, Value)]): Value =
    Value.MapV.from(entries)

  private def json(value: ujson.Value): Value = value match
    case ujson.Null => Value.DataV("None", Vector.empty)
    case ujson.Str(result) => Value.StrV(result)
    case ujson.Bool(result) => Value.BoolV(result)
    case ujson.Num(result) if result.isWhole && result >= Long.MinValue && result <= Long.MaxValue =>
      Value.IntV(result.toLong)
    case ujson.Num(result) => Value.FloatV(result)
    case array: ujson.Arr => list(array.value.iterator.map(json))
    case obj: ujson.Obj => map(obj.value.iterator.map((key, item) => Value.StrV(key) -> json(item)))

  private def json(value: Value): ujson.Value = value match
    case Value.StrV(result) => ujson.Str(result)
    case Value.BoolV(result) => ujson.Bool(result)
    case Value.IntV(result) => ujson.Num(result.toDouble)
    case Value.BigV(result) => ujson.Str(result.toString)
    case Value.FloatV(result) => ujson.Num(result)
    case Value.DecimalV(result) => ujson.Str(result)
    case Value.DataV("None", IndexedSeq()) => ujson.Null
    case Value.DataV("Some", IndexedSeq(inner)) => json(inner)
    case data @ Value.DataV("Nil" | "Cons", _) => ujson.Arr.from(Prims.unlistPub(data).map(json))
    case Value.MapV(entries) => ujson.Obj.from(entries.iterator.map { case (key, item) =>
      text(key, "map key") -> json(item)
    })
    case other => ujson.Str(Prims.display(other))

  private def obj(value: ujson.Value, key: String): ujson.Obj =
    value.obj.get(key).collect { case result: ujson.Obj => result }.getOrElse(ujson.Obj())

  private def string(value: ujson.Value, key: String, default: String = ""): String =
    value.obj.get(key).flatMap(_.strOpt).getOrElse(default)

  private def tools(value: ujson.Value): Value =
    val rows = value.obj.get("tools").map(_.arr).getOrElse(collection.mutable.ArrayBuffer.empty)
    list(rows.iterator.map { tool =>
      Value.DataV("ToolDescriptor", Vector(
        Value.StrV(string(tool, "name")),
        Value.StrV(string(tool, "description")),
        json(tool.obj.getOrElse("inputSchema", ujson.Obj()))))
    })

  private def resources(value: ujson.Value): Value =
    val rows = value.obj.get("resources").map(_.arr).getOrElse(collection.mutable.ArrayBuffer.empty)
    list(rows.iterator.map { resource =>
      Value.DataV("ResourceDescriptor", Vector(
        Value.StrV(string(resource, "uri")),
        Value.StrV(string(resource, "name")),
        Value.StrV(string(resource, "mimeType"))))
    })

  private def prompts(value: ujson.Value): Value =
    val rows = value.obj.get("prompts").map(_.arr).getOrElse(collection.mutable.ArrayBuffer.empty)
    list(rows.iterator.map { prompt =>
      val args = prompt.obj.get("arguments").map(_.arr).getOrElse(collection.mutable.ArrayBuffer.empty)
      val argValues = args.iterator.map { arg =>
        Value.DataV("ArgSpec", Vector(
          Value.StrV(string(arg, "name")),
          Value.StrV("string"),
          Value.BoolV(arg.obj.get("required").flatMap(_.boolOpt).getOrElse(false))))
      }
      Value.DataV("PromptDescriptor", Vector(
        Value.StrV(string(prompt, "name")),
        Value.StrV(string(prompt, "description")),
        list(argValues)))
    })

  private def toolResult(value: ujson.Value): Value =
    val content = value.obj.get("content").map(_.arr).getOrElse(collection.mutable.ArrayBuffer.empty)
    val contentValues = content.iterator.map { item =>
      item.obj.get("type").flatMap(_.strOpt) match
        case Some("text") => Value.DataV("Text", Vector(Value.StrV(string(item, "text"))))
        case Some("image") => Value.DataV("Image", Vector(
          Value.StrV(string(item, "data")), Value.StrV(string(item, "mimeType"))))
        case _ => Value.DataV("EmbeddedResource", Vector(Value.StrV(string(item, "uri"))))
    }
    Value.DataV("ToolResult", Vector(
      list(contentValues),
      Value.BoolV(value.obj.get("isError").flatMap(_.boolOpt).getOrElse(false))))

  private def requireResult(
      client: McpClientCore,
      method: String,
      params: ujson.Value,
      timeoutMs: Long): ujson.Value =
    client.request(method, params, timeoutMs) match
      case Right(result) => result
      case Left(error) => throw new RuntimeException(s"$method: ${error.message}")

  private final class ClientValue(
      client: McpClientCore,
      process: Process,
      timeoutMs: Long) extends Value.NamedMethodObj:
    def underlying: AnyRef = this

    def getField(name: String): Option[Value] = name match
      case "listTools" => Some(closure(0)(_ =>
        tools(requireResult(client, McpProtocol.Method.ToolsList, ujson.Obj(), timeoutMs))))
      case "listResources" => Some(closure(0)(_ =>
        resources(requireResult(client, McpProtocol.Method.ResourcesList, ujson.Obj(), timeoutMs))))
      case "listPrompts" => Some(closure(0)(_ =>
        prompts(requireResult(client, McpProtocol.Method.PromptsList, ujson.Obj(), timeoutMs))))
      case "callTool" => Some(closure(2) {
        case Value.StrV(name) :: arguments :: Nil => toolResult(requireResult(
          client,
          McpProtocol.Method.ToolsCall,
          ujson.Obj("name" -> name, "arguments" -> json(arguments)),
          timeoutMs))
        case _ => throw new IllegalArgumentException("client.callTool(name, args)")
      })
      case "close" => Some(closure(0) { _ =>
        client.close()
        process.destroy()
        Value.UnitV
      })
      case "isClosed" => Some(Value.BoolV(client.isClosed))
      case _ => None

  private def spawnFields(transport: Value): Option[IndexedSeq[Value]] = transport match
    case Value.DataV("Spawn", fields) => Some(fields)
    // Until enum companion constructors become first-class in the self-hosted
    // core, `Transport.Spawn(a, b)` uses the portable effect-shaped constructor
    // envelope. Decode only this exact label; arbitrary effects remain effects.
    case Value.DataV("Op", IndexedSeq(Value.StrV(label), payload, _))
        if label == "Transport.Spawn" =>
      payload match
        case Value.DataV("__EffArgs__", fields) => Some(fields)
        case Value.UnitV => Some(Vector.empty)
        case one => Some(Vector(one))
    case _ => None

  private def connect(transport: Value, timeoutMs: Long): Value =
    spawnFields(transport) match
    case Some(fields) if fields.nonEmpty =>
      val command = text(fields.head, "Spawn command")
      val arguments = fields.lift(1).toList.flatMap(Prims.unlistPub).map(text(_, "Spawn argument"))
      val process = ProcessBuilder((command :: arguments).asJava).redirectError(ProcessBuilder.Redirect.INHERIT).start()
      val input = BufferedReader(InputStreamReader(process.getInputStream, java.nio.charset.StandardCharsets.UTF_8))
      val output = BufferedWriter(OutputStreamWriter(process.getOutputStream, java.nio.charset.StandardCharsets.UTF_8))
      val client = McpClientCore(frame =>
        output.write(frame)
        output.flush())
      val reader = Thread.ofVirtual().name(s"ssc-mcp-${process.pid()}").start { () =>
        try
          var line = input.readLine()
          while line != null do
            client.dispatchResponse(line)
            line = input.readLine()
        catch case _: Throwable => ()
        finally client.close()
      }
      // MCP 2026-07-28 — probe the era before handshaking. `initialize` does not
      // exist in the modern revision, so sending it unconditionally is what made
      // this client fail against a modern-only server.
      client.connect("ssc-native", "2.1", timeoutMs) match
        case Left(e)  => throw new IllegalStateException(s"mcpConnect: connect failed: ${e.message}")
        case Right(_) => ()
      Value.ForeignV(ClientValue(client, process, timeoutMs))
    case _ => throw new IllegalArgumentException(
      "mcpConnect: explicit native provider supports Transport.Spawn")

  // ── SERVER surface ────────────────────────────────────────────────────────────────────────────
  //
  // The plugin shipped the CLIENT half only (`mcpConnect` and friends), so every case importing
  // `[mcpServer, serveMcp](std/mcp/server.ssc)` died with `unbound global: mcpServer` — six of the
  // eighteen v2 non-PASS rows, all one missing surface rather than six defects.
  // (v2-native-mcp-plugin-has-no-server-surface.)
  //
  // Same shape as v1's McpIntrinsics: `mcpServer { srv => … }` parks a builder in a ThreadLocal and
  // hands the block a method object; `serveMcp(transport)` picks that builder up and runs the loop.
  // The protocol work itself is mcp-common's `McpServerCore`, shared with v1 — this is a binding, not
  // a second implementation.
  private val builderTL = new ThreadLocal[McpServerBuilder]

  /** `jsonToScala` hands the handler plain Scala natives, so bridge those to runtime values. */
  private def scalaToValue(a: Any): Value = a match
    case null            => Value.DataV("None", Vector.empty)
    case s: String       => Value.StrV(s)
    case b: Boolean      => Value.BoolV(b)
    case i: Int          => Value.IntV(i.toLong)
    case l: Long         => Value.IntV(l)
    case d: Double       => if d.isWhole then Value.IntV(d.toLong) else Value.FloatV(d)
    case m: Map[?, ?]    => map(m.iterator.map((k, v) => Value.StrV(String.valueOf(k)) -> scalaToValue(v)))
    case xs: Seq[?]      => list(xs.iterator.map(scalaToValue))
    case other           => Value.StrV(String.valueOf(other))

  /** A `Content.Text/Image/…` value back to the wire shape mcp-common expects. */
  private def contentJson(value: Value): ujson.Value = value match
    case Value.DataV("Text", IndexedSeq(Value.StrV(text))) =>
      ujson.Obj("type" -> "text", "text" -> text)
    case Value.DataV("Image", IndexedSeq(Value.StrV(data), Value.StrV(mime))) =>
      ujson.Obj("type" -> "image", "data" -> data, "mimeType" -> mime)
    case Value.DataV("EmbeddedResource", IndexedSeq(Value.StrV(uri))) =>
      ujson.Obj("type" -> "resource", "resource" -> ujson.Obj("uri" -> uri))
    case other => ujson.Obj("type" -> "text", "text" -> Show.show(other))

  // `Message(role, content)` -> `{role, content}`. The role NAMES are the wire's, not the enum's:
  // `Role.User` is `"user"`. Matched positionally because v2's value model is positional — the
  // field ORDER in std/mcp/types.ssc is the contract, and `Message(role, content)` is that order.
  /** A completion handler's result: a `.ssc` `List[String]`. Non-string elements are rendered
   *  rather than dropped, and a handler that returns something which is not a list at all yields
   *  no suggestions — the same graceful-degradation the dispatcher applies to a missing handler,
   *  since a completion is a hint and a wrong shape must not fail the request. */
  private def stringList(value: Value): List[String] = value match
    case data @ Value.DataV("Nil" | "Cons", _) =>
      Prims.unlistPub(data).map {
        case Value.StrV(s) => s
        case other         => Show.show(other)
      }.toList
    case Value.StrV(s) => List(s)
    case _             => Nil

  /** String-keyed entries of a `.ssc` Map. A non-Map, or a non-string key, yields nothing rather
   *  than throwing: every caller here treats "no usable fields" as its own answer. */
  private def mapFields(value: Value): Map[String, Value] = value match
    case Value.MapV(entries) => entries.iterator.collect { case (Value.StrV(k), v) => k -> v }.toMap
    case _                   => Map.empty

  /** A `.ssc` token validator's answer -> the typed decision `McpServerCore` wants.
   *
   *  Accepted shapes, in the order a caller is likely to reach for them: a Boolean, a Map carrying
   *  `subject` (and optionally `scopes`), or anything else. ANYTHING ELSE IS INVALID, never valid —
   *  a validator whose result cannot be read must fail closed. */
  private def authResult(value: Value): McpAuth.AuthResult = value match
    case Value.BoolV(true)  => McpAuth.AuthResult.Valid(McpAuth.AuthClaims("", Set.empty))
    case Value.BoolV(false) => McpAuth.AuthResult.Invalid("invalid_token", "validator returned false")
    case other =>
      val fields = mapFields(other)
      fields.get("subject") match
        case Some(Value.StrV(subject)) =>
          val scopes = fields.get("scopes").map(stringList).getOrElse(Nil).toSet
          McpAuth.AuthResult.Valid(McpAuth.AuthClaims(subject, scopes))
        case _ =>
          McpAuth.AuthResult.Invalid("invalid_token", "validator answered no subject")

  /** `srv.setProtectedResourceMetadata(...)` takes a Map; only `resource` is required. */
  private def prmOf(value: Value): McpAuth.ProtectedResourceMetadata =
    val fields = mapFields(value)
    def str(key: String): Option[String] = fields.get(key).collect { case Value.StrV(v) => v }
    def strs(key: String): List[String]  = fields.get(key).map(stringList).getOrElse(Nil)
    McpAuth.ProtectedResourceMetadata(
      resource               = str("resource").getOrElse(""),
      authorizationServers   = strs("authorizationServers"),
      scopesSupported        = strs("scopesSupported"),
      bearerMethodsSupported = if strs("bearerMethodsSupported").isEmpty then List("header")
                               else strs("bearerMethodsSupported"),
      resourceDocumentation  = str("resourceDocumentation"))

  private def messageJson(value: Value): ujson.Value = value match
    case Value.DataV("Message", IndexedSeq(role, content)) =>
      val r = role match
        case Value.DataV("User", _)      => "user"
        case Value.DataV("Assistant", _) => "assistant"
        case Value.DataV("System", _)    => "system"
        case _                           => "user"
      ujson.Obj("role" -> r, "content" -> contentJson(content))
    case _ => ujson.Obj("role" -> "user", "content" -> ujson.Obj("type" -> "text", "text" -> ""))

  private def promptHandlerResult(value: Value): PromptHandlerResult = value match
    case Value.DataV("PromptResult", IndexedSeq(messages)) =>
      PromptHandlerResult(None, Prims.unlistPub(messages).map(messageJson))
    case other =>
      // Same choice `handlerResult` makes: a handler returning the wrong shape is a user error, and
      // answering with it beats throwing inside the serve loop and dropping the connection.
      PromptHandlerResult(None, List(ujson.Obj("role" -> "user",
        "content" -> ujson.Obj("type" -> "text", "text" -> Show.show(other)))))

  // `ResourceResult(uri, contents)` -> the `contents` array. Split out and made TOTAL because the
  // previous inline version never decoded it at all: it ran `Show.show` on whatever the handler
  // returned, so a client reading `mem://a` got the literal text
  // `ResourceResult("mem://a", List(Text("BODY-42")))` as the resource BODY. Measured against the
  // interpreter, which answers `{"type":"text","text":"BODY-42"}` for the same program.
  private def resourceHandlerResult(requested: String, value: Value): ResourceHandlerResult =
    value match
      case Value.DataV("ResourceResult", IndexedSeq(Value.StrV(uri), contents)) =>
        ResourceHandlerResult(uri, Prims.unlistPub(contents).map(contentJson))
      case Value.StrV(text) =>
        ResourceHandlerResult(requested, List(ujson.Obj("type" -> "text", "text" -> text)))
      case other =>
        ResourceHandlerResult(requested, List(ujson.Obj("type" -> "text", "text" -> Show.show(other))))

  private def handlerResult(value: Value): ToolHandlerResult = value match
    case Value.DataV("ToolResult", IndexedSeq(content, Value.BoolV(isError))) =>
      ToolHandlerResult(Prims.unlistPub(content).map(contentJson), isError)
    // A handler that returns something else is a user error, but reporting it as an MCP error beats
    // throwing inside the serve loop and dropping the connection.
    case other => ToolHandlerResult(List(ujson.Obj("type" -> "text", "text" -> Show.show(other))), true)

  private def serverValue(builder: McpServerBuilder, context: NativePluginContext): Value =
    Value.ForeignV(new Value.NamedMethodObj:
      def underlying: AnyRef = builder
      def getField(name: String): Option[Value] = name match
        // `srv.tool(name[, description])(handler)` — curried, so the first clause returns the closure
        // that takes the handler. Arity -1 is the SPI's variadic marker (NativePluginHost.invoke only
        // arity-checks when arity >= 0), which is what lets one arm accept both shapes.
        case "tool" => Some(closure(-1) { first =>
          val toolName = text(first.head, "srv.tool name")
          val desc     = first.lift(1).collect { case Value.StrV(d) => d }
          closure(1) { rest =>
            val handler = rest.head
            builder.tool(toolName, desc, ujson.Obj("type" -> "object"),
              args => handlerResult(context.invoke(handler, List(scalaToValue(args)))))
            Value.UnitV
          }
        })
        case "resource" => Some(closure(-1) { first =>
          val uri  = text(first.head, "srv.resource uri")
          val nm   = first.lift(1).collect { case Value.StrV(d) => d }
          val mime = first.lift(2).collect { case Value.StrV(d) => d }
          closure(1) { rest =>
            val handler = rest.head
            builder.resource(uri, nm, mime, requested =>
              resourceHandlerResult(requested, context.invoke(handler, List(Value.StrV(requested)))))
            Value.UnitV
          }
        })
        // `srv.prompt` is DECLARED in std/mcp/server.ssc and was missing here — so a program that
        // compiles against the declared surface died on the DEFAULT lane with
        // `no field 'prompt'`, while the interpreter served it. Curried and variadic-first for the
        // same reason `tool` is: `srv.prompt(name)(h)` and `srv.prompt(name, desc)(h)` are one
        // member. `Nil` arguments matches v1, which also registers prompts with no argument list.
        // `srv.toolWithSchema(name[, description], schema)(handler)` — the SCHEMA is what separates
        // this from `tool`, which registers `{"type":"object"}` and accepts anything. Read as
        // LAST-argument-is-the-schema rather than by arity alone, because that is the one position
        // it occupies in both spellings; the optional description sits between.
        case "toolWithSchema" => Some(closure(-1) { first =>
          if first.length < 2 then
            sys.error("srv.toolWithSchema(name[, description], schema)(handler): schema is required")
          val toolName = text(first.head, "srv.toolWithSchema name")
          val schema   = json(first.last)
          val desc     = if first.length >= 3 then first.lift(1).collect { case Value.StrV(d) => d }
                         else None
          closure(1) { rest =>
            val handler = rest.head
            builder.tool(toolName, desc, schema,
              args => handlerResult(context.invoke(handler, List(scalaToValue(args)))))
            Value.UnitV
          }
        })
        // `srv.resourceTemplate(template[, name[, description[, mimeType]]])(handler)`. The core
        // turns `{...}` segments into a match and serves the template both from
        // `resources/templates/list` and as the `resources/read` fallback when no exact resource
        // matches — so the handler receives the CONCRETE uri that was asked for, not the template.
        case "resourceTemplate" => Some(closure(-1) { first =>
          val tpl  = text(first.head, "srv.resourceTemplate template")
          val nm   = first.lift(1).collect { case Value.StrV(d) => d }
          val desc = first.lift(2).collect { case Value.StrV(d) => d }
          val mime = first.lift(3).collect { case Value.StrV(d) => d }
          closure(1) { rest =>
            val handler = rest.head
            builder.resourceTemplate(tpl, nm, desc, mime, requested =>
              resourceHandlerResult(requested, context.invoke(handler, List(Value.StrV(requested)))))
            Value.UnitV
          }
        })
        case "prompt" => Some(closure(-1) { first =>
          val promptName = text(first.head, "srv.prompt name")
          val desc       = first.lift(1).collect { case Value.StrV(d) => d }
          closure(1) { rest =>
            val handler = rest.head
            builder.prompt(promptName, desc, Nil,
              args => promptHandlerResult(context.invoke(handler, List(scalaToValue(args)))))
            Value.UnitV
          }
        })
        // ── MCP 2026-07-28 (P3/P5c) ─────────────────────────────────────
        //
        // These existed on the interpreter's `srv` and NOT here, which meant
        // the whole MRTR and Tasks surface was unreachable from `ssc run` —
        // the DEFAULT lane. Nothing was red about it because the entire MCP
        // corpus uses only `tool`, `onConnected` and `onDisconnected`: the
        // suite never crossed the boundary, so the boundary was invisible.
        case "setMrtrMode" => Some(closure(1) { args =>
          // Unknown names are refused rather than silently defaulted: a typo
          // that quietly selected a mode carrying an idempotence precondition
          // is the worst failure available here.
          text(args.head, "srv.setMrtrMode(mode)") match
            case "park"           => builder.setMrtrMode(scalascript.mcp.MrtrMode.Park)
            case "replay"         => builder.setMrtrMode(scalascript.mcp.MrtrMode.Replay)
            case "parkThenReplay" => builder.setMrtrMode(scalascript.mcp.MrtrMode.ParkThenReplay)
            case other            => sys.error(
              s"srv.setMrtrMode: unknown mode '$other' (park | replay | parkThenReplay)")
          Value.UnitV
        })
        case "asTask" => Some(closure(0) { _ => Value.BoolV(builder.asTask()) })
        case "clientSupportsTasks" =>
          Some(closure(0) { _ => Value.BoolV(builder.clientSupportsTasks) })
        case "requestState" => Some(closure(0) { _ =>
          builder.requestState match
            case Some(s) => Value.DataV("Some", Vector(Value.StrV(s)))
            case None    => Value.DataV("None", Vector.empty)
        })
        case "setRequestState" => Some(closure(1) { args =>
          builder.setRequestState(text(args.head, "srv.setRequestState(state)"))
          Value.UnitV
        })
        case "isCancelled" => Some(closure(0) { _ => Value.BoolV(builder.isCancelled) })

        // ── elicit ────────────────────────────────────────────────────────
        //
        // Builds `ElicitationResult(action, content)` — the type now DECLARED in std/mcp/types.ssc,
        // whose field ORDER is the contract a positional value model builds against. v1 answers the
        // same shape with named fields; before the declaration existed there was nothing for the two
        // to agree with, which is what kept this off v2.
        //
        // THE CONTROL SIGNAL SURVIVES THIS BOUNDARY — measured, not assumed: a Throwable raised
        // inside a v2 handler propagates out through `context.invoke` and reaches the shared core,
        // which answered `isError: true` with the message. So an MRTR `InputRequiredSignal` raised
        // by `elicit` is caught by `withRequestTracking` in the core exactly as it is on v1.
        // ONE ARITY, because that is what this protocol can express: `getField(name)` hands back a
        // value before any argument exists, and every `Value.ClosV` carries a FIXED declared arity,
        // so a name cannot serve two of them. The `.ssc` declaration was therefore collapsed to one
        // signature with `timeoutMs = 0` defaulted — v1's variadic native still serves it, and both
        // lanes now implement the same shape instead of one lane carrying an overload the other
        // cannot express.
        case "elicit" => Some(closure(3) { args =>
          val message = text(args.head, "srv.elicit(message, schema, timeoutMs)")
          val schema  = args.lift(1).map(json).getOrElse(ujson.Obj())
          val result  = args.lift(2) match
            case Some(Value.IntV(ms)) if ms > 0 => builder.elicit(message, schema, ms)
            case _                              => builder.elicit(message, schema)
          result match
            case Left(e)  => sys.error(s"srv.elicit: ${e.message}")
            case Right(r) => elicitationValue(r)
        })
        case "clientSupportsElicitation" =>
          Some(closure(0) { _ => Value.BoolV(builder.clientSupportsElicitation) })

        // ── notifications, progress and logging ──────────────────────────
        //
        // All eight are thin over `McpServerBuilder`, but THREE of them are CONDITIONAL and that
        // is the part worth stating, because it decides what a gate has to set up:
        //   * `log` drops anything ranked below the client's current level (`logging/setLevel`).
        //   * `notifyResourceUpdate` emits only for a uri the client actually SUBSCRIBED to.
        //   * `notifyProgress` emits only inside a request that carried `_meta.progressToken`.
        // A case that calls them without those preconditions passes against a no-op.
        case "notifyToolsListChanged" =>
          Some(closure(0) { _ => builder.notifyToolsListChanged(); Value.UnitV })
        case "notifyResourcesListChanged" =>
          Some(closure(0) { _ => builder.notifyResourcesListChanged(); Value.UnitV })
        case "notifyPromptsListChanged" =>
          Some(closure(0) { _ => builder.notifyPromptsListChanged(); Value.UnitV })
        case "notifyResourceUpdate" => Some(closure(1) { args =>
          builder.notifyResourceUpdate(text(args.head, "srv.notifyResourceUpdate(uri)"))
          Value.UnitV
        })
        case "notifyProgress" => Some(closure(-1) { args =>
          // Int and Float both spell a progress value in `.ssc`; accepting only one would make
          // `srv.notifyProgress(1)` a type error for no reason the caller can see.
          def num(value: Value, label: String): Double = value match
            case Value.IntV(result)   => result.toDouble
            case Value.FloatV(result) => result
            case _ => throw new IllegalArgumentException(s"srv.notifyProgress: $label must be a number")
          if args.isEmpty then
            throw new IllegalArgumentException("srv.notifyProgress(progress[, total])")
          // `total <= 0` is the declared spelling of "no total known" — std/mcp/server.ssc gives the
          // parameter a default of 0 rather than a second arity, because a v2 member is resolved by
          // getField(name) before arguments exist and cannot carry two.
          val total = args.lift(1).map(v => num(v, "total")).filter(_ > 0)
          builder.notifyProgress(num(args.head, "progress"), total)
          Value.UnitV
        })
        case "notify" => Some(closure(-1) { args =>
          val method = text(args.head, "srv.notify(method[, params])")
          builder.notify(method, args.lift(1).map(json).getOrElse(ujson.Obj()))
          Value.UnitV
        })
        case "log" => Some(closure(-1) { args =>
          if args.length < 2 then
            throw new IllegalArgumentException("srv.log(level, data[, logger])")
          val level  = text(args.head, "srv.log level")
          val logger = args.lift(2).collect { case Value.StrV(l) if l.nonEmpty => l }
          builder.log(level, json(args(1)), logger)
          Value.UnitV
        })
        case "currentLogLevel" => Some(closure(0) { _ => Value.StrV(builder.loggingLevel) })

        // ── resource subscriptions ───────────────────────────────────────
        //
        // These fire when the CLIENT sends `resources/subscribe` / `unsubscribe`. Typical wiring is
        // to start a watcher in the subscribe hook and call `srv.notifyResourceUpdate(uri)` from
        // its callback — which is also why `notifyResourceUpdate` only emits for a SUBSCRIBED uri.
        case "onResourceSubscribe" => Some(closure(1) { args =>
          val cb = args.head
          builder.setOnResourceSubscribe(uri => { context.invoke(cb, List(Value.StrV(uri))); () })
          Value.UnitV
        })
        case "onResourceUnsubscribe" => Some(closure(1) { args =>
          val cb = args.head
          builder.setOnResourceUnsubscribe(uri => { context.invoke(cb, List(Value.StrV(uri))); () })
          Value.UnitV
        })

        // ── paging ───────────────────────────────────────────────────────
        //
        // `<= 0` disables pagination, which is the default; with a positive size every `*/list`
        // answer carries a `nextCursor` while more rows remain.
        case "setPageSize" => Some(closure(1) { args =>
          val n = args.head match
            case Value.IntV(v)   => v.toInt
            case Value.FloatV(v) => v.toInt
            case _ => throw new IllegalArgumentException("srv.setPageSize(n): n must be a number")
          builder.setPageSize(n)
          Value.UnitV
        })
        case "currentPageSize" => Some(closure(0) { _ => Value.IntV(builder.currentPageSize.toLong) })

        // ── completions ──────────────────────────────────────────────────
        //
        // The handler is `String => List[String]`: it receives what the user has typed so far and
        // returns suggestions. A MISSING handler answers an empty `values` list rather than an
        // error (graceful degradation, per spec) — which is exactly why the gate for these asserts
        // the VALUES and not merely that `completion/complete` succeeded. An unimplemented member
        // would have passed a success check.
        case "completionForPrompt" => Some(closure(3) { args =>
          val promptName = text(args.head, "srv.completionForPrompt(promptName, argName, handler)")
          val argName    = text(args(1), "srv.completionForPrompt argName")
          val handler    = args(2)
          builder.completionForPrompt(promptName, argName,
            partial => stringList(context.invoke(handler, List(Value.StrV(partial)))))
          Value.UnitV
        })
        case "completionForResource" => Some(closure(3) { args =>
          val uriTemplate = text(args.head, "srv.completionForResource(uriTemplate, argName, handler)")
          val argName     = text(args(1), "srv.completionForResource argName")
          val handler     = args(2)
          builder.completionForResource(uriTemplate, argName,
            partial => stringList(context.invoke(handler, List(Value.StrV(partial)))))
          Value.UnitV
        })

        // ── roots, and the raw server-initiated request ──────────────────
        //
        // `listRoots` and `request` are server->CLIENT round trips, so over stdio they block the
        // very loop that would deliver their answer — the shape `elicit` above documents
        // (mcp-elicit-deadlocks-the-serve-loop). Implemented anyway because the defect is in the
        // transport wiring, not in these members, and because a duplex client can use them today.
        case "clientSupportsRoots" =>
          Some(closure(0) { _ => Value.BoolV(builder.clientSupportsRoots) })
        case "onRootsListChanged" => Some(closure(1) { args =>
          val cb = args.head
          builder.setOnRootsListChanged(() => { context.invoke(cb, Nil); () })
          Value.UnitV
        })
        case "listRoots" => Some(closure(-1) { args =>
          val result = args.headOption match
            case Some(Value.IntV(ms)) if ms > 0 => builder.listRoots(ms)
            case _                              => builder.listRoots()
          result match
            case Left(e)      => sys.error(s"srv.listRoots: ${e.message}")
            case Right(roots) =>
              // `Root(uri, name)` — POSITIONAL, matching the declaration in std/mcp/types.ssc.
              list(roots.iterator.map(r =>
                Value.DataV("Root", Vector(
                  Value.StrV(r.uri),
                  r.name.fold(Value.DataV("None", Vector.empty))(n =>
                    Value.DataV("Some", Vector(Value.StrV(n))))))))
        })
        case "request" => Some(closure(-1) { args =>
          if args.isEmpty then
            throw new IllegalArgumentException("srv.request(method[, params[, timeoutMs]])")
          val method  = text(args.head, "srv.request method")
          val params  = args.lift(1).map(json).getOrElse(ujson.Obj())
          val timeout = args.lift(2) match
            case Some(Value.IntV(ms)) if ms > 0 => ms
            case _                              => 30_000L
          builder.request(method, params, timeout) match
            case Left(e)     => sys.error(s"srv.request($method): ${e.message}")
            case Right(js)   => json(js)
        })

        // ── authorisation ────────────────────────────────────────────────
        //
        // These were absent from v2 for a reason that has just stopped being true: the validator
        // runs in `McpServerCore.authorizeHttp`, reached only from `handleHttpRequest`, and this
        // provider served stdio only — so they would have set state nothing read
        // (mcp-v2-auth-cannot-be-ported-until-v2-serves-http). `Transport.Http` above is the route
        // that reads them, which is why the two land together: the transport with no auth member
        // cannot be shown to enforce anything, and the members with no route do nothing.
        //
        // STILL ABSENT, and not by oversight: `useAuthServer`. It resolves its argument through
        // `OAuthBridge.authServers`, a registry the v1 oauth-plugin owns, and v2/runtime/providers
        // has no oauth plugin at all.
        case "setTokenValidator" => Some(closure(1) { args =>
          val handler = args.head
          builder.setTokenValidator(Some(token =>
            // A validator that throws, or answers something that is not a decision, must not be
            // read as "valid" — that would turn a broken check into an open door.
            try authResult(context.invoke(handler, List(Value.StrV(token))))
            catch case _: Throwable =>
              McpAuth.AuthResult.Invalid("invalid_token", "validator threw")))
          Value.UnitV
        })
        case "useHmacValidator" => Some(closure(1) { args =>
          builder.setTokenValidator(Some(
            McpAuth.hmacValidator(text(args.head, "srv.useHmacValidator(secret)"))))
          Value.UnitV
        })
        case "setAuthRealm" => Some(closure(1) { args =>
          builder.setAuthRealm(text(args.head, "srv.setAuthRealm(realm)"))
          Value.UnitV
        })
        case "setProtectedResourceMetadata" => Some(closure(1) { args =>
          builder.setProtectedResourceMetadata(prmOf(args.head))
          Value.UnitV
        })
        case "authEnabled" => Some(closure(0) { _ => Value.BoolV(builder.authEnabled) })
        case "currentAuth" => Some(closure(0) { _ =>
          builder.currentAuth match
            case None => Value.DataV("None", Vector.empty)
            case Some(c) =>
              // A Map, not a record: `AuthClaims` is DECLARED NOWHERE in std/mcp, and inventing a
              // positional shape here is what `ElicitationResult` and `Root` each cost a day for.
              // A Map is honest about being untyped and cannot silently disagree with a future
              // declaration.
              Value.DataV("Some", Vector(map(Iterator(
                Value.StrV("subject") -> Value.StrV(c.subject),
                Value.StrV("scopes")  -> list(c.scopes.iterator.toVector.sorted.map(Value.StrV.apply))))))
        })

        case "onConnected" => Some(closure(1) { args =>
          val cb = args.head
          builder.setOnConnected(() => { context.invoke(cb, Nil); () })
          Value.UnitV
        })
        case "onDisconnected" => Some(closure(1) { args =>
          val cb = args.head
          builder.setOnDisconnected(() => { context.invoke(cb, Nil); () })
          Value.UnitV
        })
        case _ => None)

  /** `Transport.Http(port, path)` — POSITIONAL, matching std/mcp/types.ssc. The default path is in
   *  the declaration, so a value that reached here already carries it. */
  private def httpConfig(transport: Value): (Int, String) = transport match
    case Value.DataV("Http", IndexedSeq(Value.IntV(port), Value.StrV(path))) => (port.toInt, path)
    case Value.DataV("Http", IndexedSeq(Value.IntV(port)))                   => (port.toInt, "/mcp")
    case other =>
      throw new IllegalArgumentException(s"serveMcp: expected Transport.Http(port[, path]), got ${Show.show(other)}")

  /** Serve MCP over HTTP on the JDK's own server.
   *
   *  WHY THIS EXISTS AT ALL, since the provider served stdio happily: the authorisation surface —
   *  `setTokenValidator`, `useHmacValidator`, `currentAuth` and the rest — runs in
   *  `McpServerCore.authorizeHttp`, which is reached only from `handleHttpRequest`. The stdio loop
   *  never consults the validator. So on a stdio-only provider those members would set state
   *  nothing reads (mcp-v2-auth-cannot-be-ported-until-v2-serves-http). This route is the thing
   *  that has to exist first.
   *
   *  `com.sun.net.httpserver` and not the http-fast plugin: this provider's dependencies are the
   *  plugin SPI, the JSON plugin and mcp-common. Reaching for another plugin's server would couple
   *  two providers to load one route, and the JDK's server is already what `JdkServerBackend` uses
   *  on the v1 side. No new dependency for a few dozen lines.
   *
   *  WHAT THIS DOES NOT DO: SSE. `Accept: text/event-stream` gets the same single JSON reply as any
   *  other request. Server-to-client pushes — `notify`, and the answer half of `elicit` /
   *  `listRoots` / `request` — need a held-open stream, which is its own piece of work; v1's route
   *  has it and this one does not. Filed as `mcp-v2-http-transport-has-no-sse` so nobody reads this
   *  route as making `elicit` work.
   */
  private def serveHttp(builder: McpServerBuilder, port: Int, path: String): Unit =
    val server = HttpServer.create(InetSocketAddress(port), 0)

    def headersOf(ex: HttpExchange): Map[String, String] =
      val out = Map.newBuilder[String, String]
      ex.getRequestHeaders.forEach((k, v) => if !v.isEmpty then out += (k -> v.get(0)))
      out.result()

    def respond(ex: HttpExchange, status: Int, body: String, extra: Map[String, String]): Unit =
      val bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      extra.foreach((k, v) => ex.getResponseHeaders.add(k, v))
      // 204 carries no body, and the JDK server insists the length be -1 rather than 0 for that.
      if status == 204 || bytes.isEmpty then
        ex.sendResponseHeaders(status, -1)
      else
        ex.sendResponseHeaders(status, bytes.length.toLong)
        val os = ex.getResponseBody
        try os.write(bytes) finally os.close()
      ex.close()

    /** `Accept: text/event-stream` — the response IS the stream.
     *
     *  WHAT THIS UNLOCKS, and it is more than tidier notifications. `McpServerCore.request` refuses
     *  outright when there is no unfiltered subscriber ("srv.request: no active client
     *  subscribers"), so without a stream `elicit`, `listRoots` and `request` could not even leave
     *  the building. With one they can — AND their answers can come back, because
     *  `handleHttpRequest` already routes an inbound JSON-RPC Response into the pending map, and
     *  because each POST here runs on its own thread. That is the difference from stdio, where the
     *  single serve loop the call blocks is the same loop that would deliver the reply
     *  (mcp-elicit-deadlocks-the-serve-loop). Over HTTP the client answers on a SECOND POST while
     *  the first is still parked.
     *
     *  THE SUBSCRIBER IS REGISTERED BEFORE DISPATCH, not after: a notification emitted while the
     *  handler runs — which is the whole point of `notifyProgress` — would otherwise be written to
     *  nobody and silently lost.
     */
    def streamed(
      ex:      HttpExchange,
      builder: McpServerBuilder,
      body:    String,
      headers: Map[String, String],
      claims:  Option[McpAuth.AuthClaims]
    ): Unit =
      ex.getResponseHeaders.add("Content-Type", "text/event-stream")
      ex.getResponseHeaders.add("Cache-Control", "no-cache")
      // 0 means chunked: the JDK server keeps the connection open and we decide when it ends.
      ex.sendResponseHeaders(200, 0)
      val os = ex.getResponseBody
      def writeSse(line: String): Unit =
        os.write(s"data: ${line.stripSuffix("\n")}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        os.flush()
      // UNFILTERED on purpose, and it has to be: `McpServerCore.request` refuses outright unless
      // some subscriber has an empty filter, so a filtered listen stream would carry notifications
      // and still leave `elicit`/`listRoots`/`request` unable to ask anything.
      //
      // TEARDOWN, because the transport is only as good as what it does when the client vanishes.
      // This subscriber lives for exactly one POST — the `finally` below removes it — so a dead
      // client cannot accumulate in the broadcast set. What it does NOT do is notice the death
      // promptly: `addSubscriber` swallows writer-side exceptions by design (one dead peer must not
      // silence the others), so a client that disappears while a handler is parked inside `elicit`
      // is discovered when that call's own timeout fires. That wait is bounded, never indefinite —
      // `request` always parks on `poll(timeoutMs, ...)`, and a 0 there returns immediately rather
      // than blocking forever. Prompt cancellation would need a heartbeat frame to make the death
      // observable; that is a separate change, not something this one half-does.
      val unsubscribe = builder.addSubscriber(writeSse)
      try
        val reply = builder.withAuth(claims) {
          McpServerCore.handleHttpRequest(builder, body, "ssc-mcp-native", "2.1", headers)
        }
        // A notification produced no reply frame; the stream still carried whatever the handler
        // emitted, so an empty reply is not an empty response.
        //
        // The final write is guarded for the same reason the broadcast one is: by the time a
        // handler finishes, the client that asked may be gone, and that is a disconnect, not a
        // server error to propagate out of the handler.
        if reply.nonEmpty then try writeSse(reply) catch case _: Throwable => ()
      finally
        unsubscribe()
        try os.close() catch case _: Throwable => ()
        ex.close()

    server.createContext(path, ex =>
      try
        if ex.getRequestMethod != "POST" then
          respond(ex, 405, """{"error":"method_not_allowed"}""",
                  Map("Content-Type" -> "application/json", "Allow" -> "POST"))
        else
          val body    = String(ex.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          val headers = headersOf(ex)
          McpServerCore.authorizeHttp(builder, McpAuth.extractBearer(headers)) match
            case McpServerCore.AuthOutcome.Reject(code, descr) =>
              // RFC 6750: the 401 has to say what is wrong and where to authenticate, or a client
              // cannot recover from it.
              val www = McpAuth.wwwAuthenticate(builder.authRealm, code, Some(descr))
              respond(ex, 401,
                ujson.Obj("error" -> code, "error_description" -> descr).render(),
                Map("WWW-Authenticate" -> www, "Content-Type" -> "application/json"))
            case McpServerCore.AuthOutcome.Allowed(claims) =>
              val wantsSse = headers.exists((k, v) =>
                k.equalsIgnoreCase("Accept") && v.toLowerCase.contains("text/event-stream"))
              // withAuth so a handler can read `srv.currentAuth` for the request it is serving.
              if wantsSse then streamed(ex, builder, body, headers, claims)
              else
                val reply = builder.withAuth(claims) {
                  McpServerCore.handleHttpRequest(builder, body, "ssc-mcp-native", "2.1", headers)
                }
                if reply.isEmpty then respond(ex, 204, "", Map.empty)
                else respond(ex, 200, reply, Map("Content-Type" -> "application/json"))
      catch
        case e: Throwable =>
          // A handler that throws must not take the server down with it, and the client is owed an
          // answer rather than a dropped connection.
          try respond(ex, 500,
                ujson.Obj("error" -> "internal_error",
                          "error_description" -> String.valueOf(e.getMessage)).render(),
                Map("Content-Type" -> "application/json"))
          catch case _: Throwable => ()
    )

    // `/.well-known/oauth-protected-resource` — how a client discovers WHERE to get a token. Served
    // only when the program set it; otherwise 404, which is the honest answer for "this server does
    // not publish that".
    server.createContext("/.well-known/oauth-protected-resource", ex =>
      try
        builder.protectedResourceMetadata match
          case Some(prm) => respond(ex, 200, prm.toJson.render(), Map("Content-Type" -> "application/json"))
          case None      => respond(ex, 404, """{"error":"not_found"}""", Map("Content-Type" -> "application/json"))
      catch case _: Throwable => ()
    )

    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
    server.start()
    // `serveMcp` BLOCKS on every transport — the stdio branch blocks on stdin, and a program whose
    // `main` returned here would exit with the server still starting. Park instead.
    try java.util.concurrent.CountDownLatch(1).await()
    finally server.stop(0)

  private def transportTag(transport: Value): String = transport match
    case Value.DataV(tag, _) => tag
    case other               => Show.show(other)

  def install(context: NativePluginContext): Unit =
    context.registerFields("ToolDescriptor", Vector("name", "description", "schema"))
    context.registerFields("ResourceDescriptor", Vector("uri", "name", "mimeType"))
    context.registerFields("PromptDescriptor", Vector("name", "description", "args"))
    context.registerFields("ArgSpec", Vector("name", "typeName", "required"))
    context.registerFields("ToolResult", Vector("content", "isError"))
    context.registerFields("AgentTool", Vector("name", "description", "parametersJson", "handler"))
    context.register("mcpServer") {
      case setup :: Nil =>
        val builder = McpServerBuilder()
        builderTL.set(builder)
        context.invoke(setup, List(serverValue(builder, context)))
        Value.UnitV
      case _ => throw new IllegalArgumentException("mcpServer { srv => ... }")
    }
    context.register("serveMcp") {
      case transport :: Nil =>
        val builder = Option(builderTL.get).getOrElse(throw new IllegalStateException(
          "serveMcp(...): no mcpServer { ... } configured first"))
        transportTag(transport) match
          case "Stdio" =>
            // Blocks on stdin until EOF, writes to stdout — single-connection and single-threaded,
            // exactly as the interpreter's Stdio transport behaves.
            val reader = BufferedReader(InputStreamReader(java.lang.System.in, java.nio.charset.StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(java.lang.System.out, java.nio.charset.StandardCharsets.UTF_8))
            McpServerCore.serve(builder,
              () => Option(reader.readLine()),
              line => { writer.write(line); writer.flush() },
              "ssc-mcp-native", "2.1")
            Value.UnitV
          case "Http" =>
            val (port, path) = httpConfig(transport)
            serveHttp(builder, port, path)
            Value.UnitV
          case other =>
            // Ws still needs a socket runtime this provider does not carry; refusing by name beats
            // pretending to serve.
            throw new IllegalArgumentException(
              s"serveMcp: the native provider supports Transport.Stdio and Transport.Http, not '$other'")
      case _ => throw new IllegalArgumentException("serveMcp(transport)")
    }
    context.register("mcpConnect") {
      case transport :: Nil => connect(transport, 30000L)
      case transport :: Value.IntV(timeout) :: Nil => connect(transport, timeout)
      case _ => throw new IllegalArgumentException("mcpConnect(transport[, timeoutMs])")
    }
    // Consumer-side std.agent companion functions are selected with this MCP
    // lane because mcpToolSource constructs AgentTools lazily. The actual
    // handler still calls the real MCP client when an agent invokes it.
    context.register("agentTool") {
      case Value.StrV(name) :: Value.StrV(description) :: Value.StrV(parameters) :: Nil =>
        closure(1) {
          case handler :: Nil => Value.DataV("AgentTool", Vector(
            Value.StrV(name), Value.StrV(description), Value.StrV(parameters), handler))
          case _ => throw new IllegalArgumentException("agentTool(...)(handler)")
        }
      case _ => throw new IllegalArgumentException("agentTool(name, description, parametersJson)(handler)")
    }
    context.register("toolOk") {
      case content :: Nil => Value.DataV("ToolResult", Vector(content, Value.BoolV(false)))
      case _ => throw new IllegalArgumentException("toolOk(contentJson)")
    }
    context.register("toolError") {
      case content :: Nil => Value.DataV("ToolResult", Vector(content, Value.BoolV(true)))
      case _ => throw new IllegalArgumentException("toolError(message)")
    }
    context.register("jsonStringify") {
      case value :: Nil => Value.StrV(NativeJsonCodec.stringify(value))
      case _ => throw new IllegalArgumentException("jsonStringify(value)")
    }
