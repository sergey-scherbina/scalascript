package ssc.plugin.mcp

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import scala.jdk.CollectionConverters.*
import scalascript.mcp.{McpClientCore, McpProtocol}
import ssc.{Done, Prims, Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}
import ssc.plugin.json.NativeJsonCodec

/** Explicit core-free MCP Spawn client backed by the pure mcp-common protocol
 * runtime. No v1 interpreter, frontend, or compatibility bridge is involved. */
final class McpNativePlugin extends NativePlugin:
  def id: String = "90-mcp-explicit"

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
      val init = ujson.Obj(
        "protocolVersion" -> McpProtocol.ProtocolVersion,
        "capabilities" -> ujson.Obj(),
        "clientInfo" -> ujson.Obj("name" -> "ssc-native", "version" -> "2.1"))
      requireResult(client, McpProtocol.Method.Initialize, init, timeoutMs)
      client.notify(McpProtocol.Method.Initialized, ujson.Obj())
      Value.ForeignV(ClientValue(client, process, timeoutMs))
    case _ => throw new IllegalArgumentException(
      "mcpConnect: explicit native provider supports Transport.Spawn")

  def install(context: NativePluginContext): Unit =
    context.registerFields("ToolDescriptor", Vector("name", "description", "schema"))
    context.registerFields("ResourceDescriptor", Vector("uri", "name", "mimeType"))
    context.registerFields("PromptDescriptor", Vector("name", "description", "args"))
    context.registerFields("ArgSpec", Vector("name", "typeName", "required"))
    context.registerFields("ToolResult", Vector("content", "isError"))
    context.registerFields("AgentTool", Vector("name", "description", "parametersJson", "handler"))
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
