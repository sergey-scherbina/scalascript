package scalascript.compiler.plugin.remote

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scalascript.plugin.api.{JsonCodec, PluginContext, PluginError, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Bool}

object RemoteIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("remoteFunction") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String) => remoteFunctionValue(ctx, name)
        case _ => PluginError.raise("remoteFunction[A, B](name: String)")
    },

    QualifiedName("remoteHttpFunction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(url: String) => remoteHttpFunctionValue(url)
        case _ => PluginError.raise("remoteHttpFunction[A, B](url: String)")
    },

    QualifiedName("remoteStub") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(baseUrl: String, typeName: String) => remoteTraitStubValue(ctx, baseUrl, typeName)
        case List(baseUrl: String)                   => remoteStubValue(baseUrl)
        case _ => PluginError.raise("remoteStub(baseUrl: String)")
    },

    QualifiedName("remoteStubFunction") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(baseUrl: String, path: String) => remoteHttpFunctionValue(joinRemoteUrl(baseUrl, path))
        case _ => PluginError.raise("remoteStubFunction[A, B](baseUrl: String, path: String)")
    },

    QualifiedName("remoteCall") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String, payload) =>
          ctx.invokeRemoteHandler(name, payload) match
            case Right(value) => value
            case Left(err)    => PluginError.raise(remoteErrorMessage(err))
        case _ => PluginError.raise("remoteCall[A, B](name: String, value: A)")
    },

    QualifiedName("remoteTryCall") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String, payload) =>
          ctx.invokeRemoteHandler(name, payload) match
            case Right(value) => PluginValue.instance("Right", Map("value" -> PluginValue.wrap(value))).unwrap
            case Left(err)    => PluginValue.instance("Left", Map("value" -> remoteErrorValue(err))).unwrap
        case _ => PluginError.raise("remoteTryCall[A, B](name: String, value: A)")
    },

    QualifiedName("remoteHttpCall") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(url: String, payload) =>
          remoteHttpInvoke(url, payload) match
            case Right(value) => value.unwrap
            case Left(err)    => PluginError.raise(remoteErrorMessage(err))
        case _ => PluginError.raise("remoteHttpCall[A, B](url: String, value: A)")
    },

    QualifiedName("remoteHttpTryCall") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(url: String, payload) =>
          remoteHttpInvoke(url, payload) match
            case Right(value) => PluginValue.instance("Right", Map("value" -> value)).unwrap
            case Left(err)    => PluginValue.instance("Left", Map("value" -> remoteErrorValue(err))).unwrap
        case _ => PluginError.raise("remoteHttpTryCall[A, B](url: String, value: A)")
    },

    QualifiedName("remoteStubCall") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(baseUrl: String, path: String, payload) =>
          remoteHttpInvoke(joinRemoteUrl(baseUrl, path), payload) match
            case Right(value) => value.unwrap
            case Left(err)    => PluginError.raise(remoteErrorMessage(err))
        case _ => PluginError.raise("remoteStubCall[A, B](baseUrl: String, path: String, value: A)")
    },

    QualifiedName("remoteStubTryCall") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(baseUrl: String, path: String, payload) =>
          remoteHttpInvoke(joinRemoteUrl(baseUrl, path), payload) match
            case Right(value) => PluginValue.instance("Right", Map("value" -> value)).unwrap
            case Left(err)    => PluginValue.instance("Left", Map("value" -> remoteErrorValue(err))).unwrap
        case _ => PluginError.raise("remoteStubTryCall[A, B](baseUrl: String, path: String, value: A)")
    },

    QualifiedName("remoteHandlers") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case Nil => PluginValue.list(ctx.remoteHandlers.map(handlerInfoValue)).unwrap
        case _   => PluginError.raise("remoteHandlers(): List[RemoteHandlerInfo]")
    },
  )

  private def remoteFunctionValue(ctx: PluginContext, name: String): PluginValue =
    PluginValue.instance("RemoteFunction", Map(
      "name" -> PluginValue.string(name),
      "call" -> PluginValue.nativeFn(s"Remote.function($name).call", {
        case List(payload) =>
          ctx.invokeRemoteHandler(name, payload) match
            case Right(value) => PluginValue.wrap(value)
            case Left(err)    => PluginError.raise(remoteErrorMessage(err))
        case _ => PluginError.raise("RemoteFunction.call(value)")
      }),
      "tryCall" -> PluginValue.nativeFn(s"Remote.function($name).tryCall", {
        case List(payload) =>
          ctx.invokeRemoteHandler(name, payload) match
            case Right(value) => PluginValue.instance("Right", Map("value" -> PluginValue.wrap(value)))
            case Left(err)    => PluginValue.instance("Left", Map("value" -> remoteErrorValue(err)))
        case _ => PluginError.raise("RemoteFunction.tryCall(value)")
      })
    ))

  private def remoteHttpFunctionValue(url: String): PluginValue =
    PluginValue.instance("RemoteHttpFunction", Map(
      "url" -> PluginValue.string(url),
      "call" -> PluginValue.nativeFn(s"Remote.http($url).call", {
        case List(payload) =>
          remoteHttpInvoke(url, payload) match
            case Right(value) => value
            case Left(err)    => PluginError.raise(remoteErrorMessage(err))
        case _ => PluginError.raise("RemoteHttpFunction.call(value)")
      }),
      "tryCall" -> PluginValue.nativeFn(s"Remote.http($url).tryCall", {
        case List(payload) =>
          remoteHttpInvoke(url, payload) match
            case Right(value) => PluginValue.instance("Right", Map("value" -> value))
            case Left(err)    => PluginValue.instance("Left", Map("value" -> remoteErrorValue(err)))
        case _ => PluginError.raise("RemoteHttpFunction.tryCall(value)")
      })
    ))

  private def remoteStubValue(baseUrl: String): PluginValue =
    PluginValue.instance("RemoteStub", Map(
      "baseUrl" -> PluginValue.string(baseUrl),
      "function" -> PluginValue.nativeFn(s"Remote.stub($baseUrl).function", {
        case List(Str(path)) =>
          remoteHttpFunctionValue(joinRemoteUrl(baseUrl, path))
        case _ => PluginError.raise("RemoteStub.function(path)")
      }),
      "call" -> PluginValue.nativeFn(s"Remote.stub($baseUrl).call", {
        case List(Str(path), payload) =>
          remoteHttpInvoke(joinRemoteUrl(baseUrl, path), payload) match
            case Right(value) => value
            case Left(err)    => PluginError.raise(remoteErrorMessage(err))
        case _ => PluginError.raise("RemoteStub.call(path, value)")
      }),
      "tryCall" -> PluginValue.nativeFn(s"Remote.stub($baseUrl).tryCall", {
        case List(Str(path), payload) =>
          remoteHttpInvoke(joinRemoteUrl(baseUrl, path), payload) match
            case Right(value) => PluginValue.instance("Right", Map("value" -> value))
            case Left(err)    => PluginValue.instance("Left", Map("value" -> remoteErrorValue(err)))
        case _ => PluginError.raise("RemoteStub.tryCall(path, value)")
      })
    ))

  private def remoteTraitStubValue(ctx: PluginContext, baseUrl: String, typeName: String): PluginValue =
    val methodNames: List[String] = ctx.featureGet(s"$$traitMethods$$${typeName}") match
      case Some(names: List[?]) => names.collect { case s: String => s }
      case _                    => Nil
    if methodNames.isEmpty then
      // No trait definition found — fall back to the path-based RemoteStub facade.
      remoteStubValue(baseUrl)
    else
      val methodFields: Map[String, PluginValue] = methodNames.map { methodName =>
        val url = joinRemoteUrl(baseUrl, methodName)
        methodName -> PluginValue.nativeFn(s"$typeName.$methodName@$baseUrl", {
          case List(payload) =>
            remoteHttpInvoke(url, payload) match
              case Right(value) => value
              case Left(err)    => PluginValue.instance("Left", Map("value" -> remoteErrorValue(err)))
          case Nil =>
            // Zero-arg trait methods: call with Unit payload.
            remoteHttpInvoke(url, PluginValue.unit) match
              case Right(value) => value
              case Left(err)    => PluginValue.instance("Left", Map("value" -> remoteErrorValue(err)))
          case _ => PluginError.raise(s"$typeName.$methodName(value)")
        })
      }.toMap
      PluginValue.instance(typeName, methodFields + ("_remoteBaseUrl" -> PluginValue.string(baseUrl)))

  private def joinRemoteUrl(baseUrl: String, path: String): String =
    if baseUrl.endsWith("/") && path.startsWith("/") then baseUrl.dropRight(1) + path
    else if !baseUrl.endsWith("/") && !path.startsWith("/") then baseUrl + "/" + path
    else baseUrl + path

  private def remoteHttpInvoke(url: String, payload: Any): Either[RemoteCallError, PluginValue] =
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

  private def wireSerialize(value: Any): String = value match
    case Num(n)  => s"{\"$$t\":\"i\",\"v\":$n}"
    case Str(s)  => s"{\"$$t\":\"s\",\"v\":${jsonString(s)}}"
    case Bool(b) => s"{\"$$t\":\"b\",\"v\":$b}"
    case other if PluginValue.isUnitOrNull(other) => "{\"$t\":\"u\"}"
    case other   => PluginError.raise(s"remote HTTP JSON fallback cannot encode ${PluginValue.showAny(other)} yet")

  /** Decode the tiny `{"$t":..,"v":..}` wire envelope via the stable `JsonCodec`
   *  (ujson) surface rather than the interpreter's `JsonParser`. */
  private def wireDeserialize(json: String): PluginValue =
    JsonCodec.parseString(json) match
      case Left(e) => PluginError.raise(s"remote HTTP decode: $e")
      case Right(o: ujson.Obj) =>
        def field(name: String): Option[ujson.Value] = o.value.get(name)
        field("$t") match
          case Some(ujson.Str("i")) => field("v") match
            case Some(n: ujson.Num) => PluginValue.int(n.value.toLong)
            case _                  => PluginError.raise("remote HTTP decode: invalid int payload")
          case Some(ujson.Str("s")) => field("v") match
            case Some(ujson.Str(s)) => PluginValue.string(s)
            case _                  => PluginError.raise("remote HTTP decode: invalid string payload")
          case Some(ujson.Str("b")) => field("v") match
            case Some(b: ujson.Bool) => PluginValue.bool(b.value)
            case _                   => PluginError.raise("remote HTTP decode: invalid boolean payload")
          case Some(ujson.Str("u"))     => PluginValue.unit
          case Some(ujson.Str(other))   => PluginError.raise(s"remote HTTP decode: unsupported tag '$other'")
          case _ => PluginError.raise("remote HTTP decode: missing $t tag")
      case Right(_) => PluginError.raise("remote HTTP decode: expected object")

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

  private def handlerInfoValue(info: RemoteHandlerInfo): PluginValue =
    PluginValue.instance("RemoteHandlerInfo", Map(
      "name"         -> PluginValue.string(info.name),
      "function"     -> PluginValue.string(info.function),
      "path"         -> info.path.map(s => PluginValue.some(PluginValue.string(s))).getOrElse(PluginValue.none),
      "requestType"  -> info.requestType.map(s => PluginValue.some(PluginValue.string(s))).getOrElse(PluginValue.none),
      "responseType" -> info.responseType.map(s => PluginValue.some(PluginValue.string(s))).getOrElse(PluginValue.none),
      "transports"   -> PluginValue.list(info.transports.toList.sorted.map(PluginValue.string))
    ))

  private def remoteErrorValue(err: RemoteCallError): PluginValue = err match
    case RemoteCallError.Unavailable(node) =>
      PluginValue.instance("Unavailable", Map("node" -> PluginValue.string(node)))
    case RemoteCallError.Timeout(operation, durationMs) =>
      PluginValue.instance("Timeout", Map("operation" -> PluginValue.string(operation), "durationMs" -> PluginValue.int(durationMs)))
    case RemoteCallError.Decode(message) =>
      PluginValue.instance("Decode", Map("message" -> PluginValue.string(message)))
    case RemoteCallError.HandlerNotFound(name) =>
      PluginValue.instance("HandlerNotFound", Map("name" -> PluginValue.string(name)))
    case RemoteCallError.CodeMismatch(localHash, remoteHash) =>
      PluginValue.instance("CodeMismatch", Map("localHash" -> PluginValue.string(localHash), "remoteHash" -> PluginValue.string(remoteHash)))
    case RemoteCallError.Unauthorized =>
      PluginValue.instance("Unauthorized", Map.empty)
    case RemoteCallError.Cancelled =>
      PluginValue.instance("Cancelled", Map.empty)
    case RemoteCallError.RemoteFailed(code, message) =>
      PluginValue.instance("RemoteFailed", Map("code" -> PluginValue.string(code), "message" -> PluginValue.string(message)))
    case RemoteCallError.NetworkError(message) =>
      PluginValue.instance("NetworkError", Map("message" -> PluginValue.string(message)))

  private def remoteErrorMessage(err: RemoteCallError): String = err match
    case RemoteCallError.HandlerNotFound(name) => s"remote handler not found: $name"
    case RemoteCallError.Decode(message)       => s"remote decode failed: $message"
    case RemoteCallError.RemoteFailed(code, message) => s"remote handler failed ($code): $message"
    case other                                 => other.toString
