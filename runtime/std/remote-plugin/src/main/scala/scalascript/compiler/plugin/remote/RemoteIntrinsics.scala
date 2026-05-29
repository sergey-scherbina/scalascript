package scalascript.compiler.plugin.remote

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{InterpretError, JsonParser, Value}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scalascript.plugin.api.PluginNative
import scalascript.plugin.api.PluginContext

object RemoteIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("remoteFunction") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String) => remoteFunctionValue(ctx, name)
        case _ => throw InterpretError("remoteFunction[A, B](name: String)")
    },

    QualifiedName("remoteHttpFunction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(url: String) => remoteHttpFunctionValue(url)
        case _ => throw InterpretError("remoteHttpFunction[A, B](url: String)")
    },

    QualifiedName("remoteStub") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(baseUrl: String, typeName: String) => remoteTraitStubValue(ctx, baseUrl, typeName)
        case List(baseUrl: String)                   => remoteStubValue(baseUrl)
        case _ => throw InterpretError("remoteStub(baseUrl: String)")
    },

    QualifiedName("remoteStubFunction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(baseUrl: String, path: String) => remoteHttpFunctionValue(joinRemoteUrl(baseUrl, path))
        case _ => throw InterpretError("remoteStubFunction[A, B](baseUrl: String, path: String)")
    },

    QualifiedName("remoteCall") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String, payload) =>
          ctx.invokeRemoteHandler(name, payload) match
            case Right(value) => value
            case Left(err)    => throw InterpretError(remoteErrorMessage(err))
        case _ => throw InterpretError("remoteCall[A, B](name: String, value: A)")
    },

    QualifiedName("remoteTryCall") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String, payload) =>
          ctx.invokeRemoteHandler(name, payload) match
            case Right(value) => Value.InstanceV("Right", Map("value" -> value.asInstanceOf[Value]))
            case Left(err)    => Value.InstanceV("Left", Map("value" -> remoteErrorValue(err)))
        case _ => throw InterpretError("remoteTryCall[A, B](name: String, value: A)")
    },

    QualifiedName("remoteHttpCall") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(url: String, payload: Value) =>
          remoteHttpInvoke(url, payload) match
            case Right(value) => value
            case Left(err)    => throw InterpretError(remoteErrorMessage(err))
        case _ => throw InterpretError("remoteHttpCall[A, B](url: String, value: A)")
    },

    QualifiedName("remoteHttpTryCall") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(url: String, payload: Value) =>
          remoteHttpInvoke(url, payload) match
            case Right(value) => Value.InstanceV("Right", Map("value" -> value))
            case Left(err)    => Value.InstanceV("Left", Map("value" -> remoteErrorValue(err)))
        case _ => throw InterpretError("remoteHttpTryCall[A, B](url: String, value: A)")
    },

    QualifiedName("remoteStubCall") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(baseUrl: String, path: String, payload: Value) =>
          remoteHttpInvoke(joinRemoteUrl(baseUrl, path), payload) match
            case Right(value) => value
            case Left(err)    => throw InterpretError(remoteErrorMessage(err))
        case _ => throw InterpretError("remoteStubCall[A, B](baseUrl: String, path: String, value: A)")
    },

    QualifiedName("remoteStubTryCall") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(baseUrl: String, path: String, payload: Value) =>
          remoteHttpInvoke(joinRemoteUrl(baseUrl, path), payload) match
            case Right(value) => Value.InstanceV("Right", Map("value" -> value))
            case Left(err)    => Value.InstanceV("Left", Map("value" -> remoteErrorValue(err)))
        case _ => throw InterpretError("remoteStubTryCall[A, B](baseUrl: String, path: String, value: A)")
    },

    QualifiedName("remoteHandlers") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case Nil => Value.ListV(ctx.remoteHandlers.map(handlerInfoValue))
        case _   => throw InterpretError("remoteHandlers(): List[RemoteHandlerInfo]")
    },
  )

  private def remoteFunctionValue(ctx: PluginContext, name: String): Value =
    Value.InstanceV("RemoteFunction", Map(
      "name" -> Value.StringV(name),
      "call" -> Value.NativeFnV(s"Remote.function($name).call", {
        case List(payload) =>
          scalascript.interpreter.Computation.Pure(
            ctx.invokeRemoteHandler(name, payload) match
              case Right(value) => value.asInstanceOf[Value]
              case Left(err)    => throw InterpretError(remoteErrorMessage(err))
          )
        case _ => throw InterpretError("RemoteFunction.call(value)")
      }),
      "tryCall" -> Value.NativeFnV(s"Remote.function($name).tryCall", {
        case List(payload) =>
          scalascript.interpreter.Computation.Pure(
            ctx.invokeRemoteHandler(name, payload) match
              case Right(value) => Value.InstanceV("Right", Map("value" -> value.asInstanceOf[Value]))
              case Left(err)    => Value.InstanceV("Left", Map("value" -> remoteErrorValue(err)))
          )
        case _ => throw InterpretError("RemoteFunction.tryCall(value)")
      })
    ))

  private def remoteHttpFunctionValue(url: String): Value =
    Value.InstanceV("RemoteHttpFunction", Map(
      "url" -> Value.StringV(url),
      "call" -> Value.NativeFnV(s"Remote.http($url).call", {
        case List(payload) =>
          scalascript.interpreter.Computation.Pure(
            remoteHttpInvoke(url, payload) match
              case Right(value) => value
              case Left(err)    => throw InterpretError(remoteErrorMessage(err))
          )
        case _ => throw InterpretError("RemoteHttpFunction.call(value)")
      }),
      "tryCall" -> Value.NativeFnV(s"Remote.http($url).tryCall", {
        case List(payload) =>
          scalascript.interpreter.Computation.Pure(
            remoteHttpInvoke(url, payload) match
              case Right(value) => Value.InstanceV("Right", Map("value" -> value))
              case Left(err)    => Value.InstanceV("Left", Map("value" -> remoteErrorValue(err)))
          )
        case _ => throw InterpretError("RemoteHttpFunction.tryCall(value)")
      })
    ))

  private def remoteStubValue(baseUrl: String): Value =
    Value.InstanceV("RemoteStub", Map(
      "baseUrl" -> Value.StringV(baseUrl),
      "function" -> Value.NativeFnV(s"Remote.stub($baseUrl).function", {
        case List(Value.StringV(path)) =>
          scalascript.interpreter.Computation.Pure(remoteHttpFunctionValue(joinRemoteUrl(baseUrl, path)))
        case _ => throw InterpretError("RemoteStub.function(path)")
      }),
      "call" -> Value.NativeFnV(s"Remote.stub($baseUrl).call", {
        case List(Value.StringV(path), payload) =>
          scalascript.interpreter.Computation.Pure(
            remoteHttpInvoke(joinRemoteUrl(baseUrl, path), payload) match
              case Right(value) => value
              case Left(err)    => throw InterpretError(remoteErrorMessage(err))
          )
        case _ => throw InterpretError("RemoteStub.call(path, value)")
      }),
      "tryCall" -> Value.NativeFnV(s"Remote.stub($baseUrl).tryCall", {
        case List(Value.StringV(path), payload) =>
          scalascript.interpreter.Computation.Pure(
            remoteHttpInvoke(joinRemoteUrl(baseUrl, path), payload) match
              case Right(value) => Value.InstanceV("Right", Map("value" -> value))
              case Left(err)    => Value.InstanceV("Left", Map("value" -> remoteErrorValue(err)))
          )
        case _ => throw InterpretError("RemoteStub.tryCall(path, value)")
      })
    ))

  private def remoteTraitStubValue(ctx: PluginContext, baseUrl: String, typeName: String): Value =
    val methodNames: List[String] = ctx.featureGet(s"$$traitMethods$$${typeName}") match
      case Some(names: List[?]) => names.collect { case s: String => s }
      case _                    => Nil
    if methodNames.isEmpty then
      // No trait definition found — fall back to the path-based RemoteStub facade.
      remoteStubValue(baseUrl)
    else
      val methodFields: Map[String, Value] = methodNames.map { methodName =>
        val url = joinRemoteUrl(baseUrl, methodName)
        methodName -> Value.NativeFnV(s"$typeName.$methodName@$baseUrl", {
          case List(payload) =>
            scalascript.interpreter.Computation.Pure(
              remoteHttpInvoke(url, payload) match
                case Right(value) => value
                case Left(err)    => Value.InstanceV("Left", Map("value" -> remoteErrorValue(err)))
            )
          case Nil =>
            // Zero-arg trait methods: call with Unit payload.
            scalascript.interpreter.Computation.Pure(
              remoteHttpInvoke(url, Value.UnitV) match
                case Right(value) => value
                case Left(err)    => Value.InstanceV("Left", Map("value" -> remoteErrorValue(err)))
            )
          case _ => throw InterpretError(s"$typeName.$methodName(value)")
        })
      }.toMap
      Value.InstanceV(typeName, methodFields + ("_remoteBaseUrl" -> Value.StringV(baseUrl)))

  private def joinRemoteUrl(baseUrl: String, path: String): String =
    if baseUrl.endsWith("/") && path.startsWith("/") then baseUrl.dropRight(1) + path
    else if !baseUrl.endsWith("/") && !path.startsWith("/") then baseUrl + "/" + path
    else baseUrl + path

  private def remoteHttpInvoke(url: String, payload: Value): Either[RemoteCallError, Value] =
    try
      val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/scalascript-value+json")
        .header("Accept", "application/scalascript-value+json")
        .POST(HttpRequest.BodyPublishers.ofString(wireSerialize(payload)))
        .build()
      val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() match
        case code if code >= 200 && code < 300 =>
          try Right(wireDeserialize(response.body()))
          catch case e: Throwable => Left(RemoteCallError.Decode(e.getMessage))
        case 401 | 403 => Left(RemoteCallError.Unauthorized)
        case 404       => Left(RemoteCallError.HandlerNotFound(url))
        case 408 | 504 => Left(RemoteCallError.Timeout(url, 30000L))
        case code      => Left(RemoteCallError.RemoteFailed(code.toString, response.body()))
    catch
      case _: java.net.http.HttpTimeoutException => Left(RemoteCallError.Timeout(url, 30000L))
      case e: IllegalArgumentException           => Left(RemoteCallError.Decode(e.getMessage))
      case e: Throwable                          => Left(RemoteCallError.NetworkError(e.getMessage))

  private def wireSerialize(value: Value): String = value match
    case Value.IntV(n)    => s"{\"$$t\":\"i\",\"v\":$n}"
    case Value.StringV(s) => s"{\"$$t\":\"s\",\"v\":${jsonString(s)}}"
    case Value.BoolV(b)   => s"{\"$$t\":\"b\",\"v\":$b}"
    case Value.UnitV      => "{\"$t\":\"u\"}"
    case other            => throw InterpretError(s"remote HTTP JSON fallback cannot encode ${Value.show(other)} yet")

  private def wireDeserialize(json: String): Value =
    JsonParser.parse(json) match
      case Value.MapV(fields) =>
        def field(name: String): Option[Value] = fields.get(Value.StringV(name))
        field("$t") match
          case Some(Value.StringV("i")) => field("v") match
            case Some(Value.IntV(n))    => Value.intV(n)
            case Some(Value.DoubleV(d)) => Value.intV(d.toLong)
            case _                      => throw InterpretError("remote HTTP decode: invalid int payload")
          case Some(Value.StringV("s")) => field("v") match
            case Some(Value.StringV(s)) => Value.StringV(s)
            case _                      => throw InterpretError("remote HTTP decode: invalid string payload")
          case Some(Value.StringV("b")) => field("v") match
            case Some(Value.BoolV(b)) => Value.boolV(b)
            case _                    => throw InterpretError("remote HTTP decode: invalid boolean payload")
          case Some(Value.StringV("u")) => Value.UnitV
          case Some(Value.StringV(other)) => throw InterpretError(s"remote HTTP decode: unsupported tag '$other'")
          case _ => throw InterpretError("remote HTTP decode: missing $t tag")
      case _ => throw InterpretError("remote HTTP decode: expected object")

  private def jsonString(value: String): String =
    val sb = StringBuilder("\"")
    value.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < ' ' => sb.append(f"\\u${c.toInt}%04x")
      case c => sb.append(c)
    }
    sb.append('"').result()

  private def handlerInfoValue(info: RemoteHandlerInfo): Value =
    Value.InstanceV("RemoteHandlerInfo", Map(
      "name"         -> Value.StringV(info.name),
      "function"     -> Value.StringV(info.function),
      "path"         -> info.path.map(s => Value.OptionV(Value.StringV(s))).getOrElse(Value.NoneV),
      "requestType"  -> info.requestType.map(s => Value.OptionV(Value.StringV(s))).getOrElse(Value.NoneV),
      "responseType" -> info.responseType.map(s => Value.OptionV(Value.StringV(s))).getOrElse(Value.NoneV),
      "transports"   -> Value.ListV(info.transports.toList.sorted.map(Value.StringV.apply))
    ))

  private def remoteErrorValue(err: RemoteCallError): Value = err match
    case RemoteCallError.Unavailable(node) =>
      Value.InstanceV("Unavailable", Map("node" -> Value.StringV(node)))
    case RemoteCallError.Timeout(operation, durationMs) =>
      Value.InstanceV("Timeout", Map("operation" -> Value.StringV(operation), "durationMs" -> Value.intV(durationMs)))
    case RemoteCallError.Decode(message) =>
      Value.InstanceV("Decode", Map("message" -> Value.StringV(message)))
    case RemoteCallError.HandlerNotFound(name) =>
      Value.InstanceV("HandlerNotFound", Map("name" -> Value.StringV(name)))
    case RemoteCallError.CodeMismatch(localHash, remoteHash) =>
      Value.InstanceV("CodeMismatch", Map("localHash" -> Value.StringV(localHash), "remoteHash" -> Value.StringV(remoteHash)))
    case RemoteCallError.Unauthorized =>
      Value.InstanceV("Unauthorized", Map.empty)
    case RemoteCallError.Cancelled =>
      Value.InstanceV("Cancelled", Map.empty)
    case RemoteCallError.RemoteFailed(code, message) =>
      Value.InstanceV("RemoteFailed", Map("code" -> Value.StringV(code), "message" -> Value.StringV(message)))
    case RemoteCallError.NetworkError(message) =>
      Value.InstanceV("NetworkError", Map("message" -> Value.StringV(message)))

  private def remoteErrorMessage(err: RemoteCallError): String = err match
    case RemoteCallError.HandlerNotFound(name) => s"remote handler not found: $name"
    case RemoteCallError.Decode(message)       => s"remote decode failed: $message"
    case RemoteCallError.RemoteFailed(code, message) => s"remote handler failed ($code): $message"
    case other                                 => other.toString
