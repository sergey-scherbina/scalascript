package scalascript.compiler.plugin.remote

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{InterpretError, Value}

object RemoteIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("remoteFunction") -> NativeImpl((ctx, args) =>
      args match
        case List(name: String) => remoteFunctionValue(ctx, name)
        case _ => throw InterpretError("remoteFunction[A, B](name: String)")
    ),

    QualifiedName("remoteCall") -> NativeImpl((ctx, args) =>
      args match
        case List(name: String, payload) =>
          ctx.invokeRemoteHandler(name, payload) match
            case Right(value) => value
            case Left(err)    => throw InterpretError(remoteErrorMessage(err))
        case _ => throw InterpretError("remoteCall[A, B](name: String, value: A)")
    ),

    QualifiedName("remoteTryCall") -> NativeImpl((ctx, args) =>
      args match
        case List(name: String, payload) =>
          ctx.invokeRemoteHandler(name, payload) match
            case Right(value) => Value.InstanceV("Right", Map("value" -> value.asInstanceOf[Value]))
            case Left(err)    => Value.InstanceV("Left", Map("value" -> remoteErrorValue(err)))
        case _ => throw InterpretError("remoteTryCall[A, B](name: String, value: A)")
    ),

    QualifiedName("remoteHandlers") -> NativeImpl((ctx, args) =>
      args match
        case Nil => Value.ListV(ctx.remoteHandlers.map(handlerInfoValue))
        case _   => throw InterpretError("remoteHandlers(): List[RemoteHandlerInfo]")
    ),
  )

  private def remoteFunctionValue(ctx: NativeContext, name: String): Value =
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

  private def handlerInfoValue(info: RemoteHandlerInfo): Value =
    Value.InstanceV("RemoteHandlerInfo", Map(
      "name"         -> Value.StringV(info.name),
      "function"     -> Value.StringV(info.function),
      "path"         -> info.path.map(s => Value.OptionV(Some(Value.StringV(s)))).getOrElse(Value.NoneV),
      "requestType"  -> info.requestType.map(s => Value.OptionV(Some(Value.StringV(s)))).getOrElse(Value.NoneV),
      "responseType" -> info.responseType.map(s => Value.OptionV(Some(Value.StringV(s)))).getOrElse(Value.NoneV),
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
