package sscplugin.json

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, JsonParser,
                                 jsonToJson, wrapJson, lookupKey, jsonAnyToValue}

object JsonIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("jsonStringify") -> NativeImpl((_, args) =>
      args match
        case List(v) => jsonToJson(jsonAnyToValue(v))
        case _       => throw InterpretError("jsonStringify(v)")
    ),

    QualifiedName("jsonParse") -> NativeImpl((_, args) =>
      args match
        case List(s: String) =>
          try JsonParser.parse(s)
          catch case e: JsonParser.ParseError => throw InterpretError(e.getMessage)
        case _ => throw InterpretError("jsonParse(s: String)")
    ),

    QualifiedName("jsonRead") -> NativeImpl((_, args) =>
      args match
        case List(s: String) =>
          try wrapJson(JsonParser.parse(s))
          catch case e: JsonParser.ParseError => throw InterpretError(e.getMessage)
        case List(v) => wrapJson(jsonAnyToValue(v))
        case _       => throw InterpretError("jsonRead(s: String) or jsonRead(parsedAny)")
    ),

    QualifiedName("lookup") -> NativeImpl((_, args) =>
      args match
        case List(v, k) =>
          val vv = jsonAnyToValue(v)
          val kk = jsonAnyToValue(k)
          lookupKey(vv, kk) match
            case Some(x) => x
            case None    => throw InterpretError(s"lookup: key ${Value.show(kk)} not found in ${Value.show(vv)}")
        case _ => throw InterpretError("lookup(v, key)")
    ),

    QualifiedName("lookupOpt") -> NativeImpl((_, args) =>
      args match
        case List(v, k) => Value.OptionV(lookupKey(jsonAnyToValue(v), jsonAnyToValue(k)))
        case _          => throw InterpretError("lookupOpt(v, key)")
    ),

  )
