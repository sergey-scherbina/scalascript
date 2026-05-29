package scalascript.compiler.plugin.json

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, JsonParser,
                                 jsonToJson, wrapJson, lookupKey, jsonAnyToValue}
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

object JsonIntrinsics:

  private def stableNative(f: List[Any] => Any): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("jsonStringify") -> stableNative(args =>
      args match
        case List(v) => jsonToJson(jsonAnyToValue(v))
        case _       => throw InterpretError("jsonStringify(v)")
    ),

    QualifiedName("jsonParse") -> stableNative(args =>
      args match
        case List(s: String) =>
          try JsonParser.parse(s)
          catch case e: JsonParser.ParseError => throw InterpretError(e.getMessage)
        case _ => throw InterpretError("jsonParse(s: String)")
    ),

    QualifiedName("jsonRead") -> stableNative(args =>
      args match
        case List(s: String) =>
          try wrapJson(JsonParser.parse(s))
          catch case e: JsonParser.ParseError => throw InterpretError(e.getMessage)
        case List(v) => wrapJson(jsonAnyToValue(v))
        case _       => throw InterpretError("jsonRead(s: String) or jsonRead(parsedAny)")
    ),

    QualifiedName("lookup") -> stableNative(args =>
      args match
        case List(v, k) =>
          val vv = jsonAnyToValue(v)
          val kk = jsonAnyToValue(k)
          lookupKey(vv, kk) match
            case Some(x) => x
            case None    => throw InterpretError(s"lookup: key ${Value.show(kk)} not found in ${Value.show(vv)}")
        case _ => throw InterpretError("lookup(v, key)")
    ),

    QualifiedName("lookupOpt") -> stableNative(args =>
      args match
        case List(v, k) => Value.optionV(lookupKey(jsonAnyToValue(v), jsonAnyToValue(k)))
        case _          => throw InterpretError("lookupOpt(v, key)")
    ),

  )
