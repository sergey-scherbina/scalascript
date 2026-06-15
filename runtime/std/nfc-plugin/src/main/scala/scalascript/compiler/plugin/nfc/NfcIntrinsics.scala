package scalascript.compiler.plugin.nfc

import scalascript.backend.spi.*
import scalascript.interpreter.{InterpretError, Value}
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

import java.nio.charset.StandardCharsets

object NfcIntrinsics:

  private val UnsupportedMessage =
    "std.nfc: NFC hardware access is not supported by the interpreter backend"

  private def native(f: List[Any] => Value): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  private def none: Value.OptionV =
    Value.NoneV

  private def someString(value: String): Value.OptionV =
    Value.someV(Value.StringV(value))

  private def optionString(value: Option[String]): Value.OptionV =
    value match
      case Some(s) => someString(s)
      case None    => none

  private def optionalStringArg(value: Any, label: String): Option[String] =
    value match
      case null                         => None
      case None                         => None
      case Some(s: String)              => Some(s)
      case s: String                    => Some(s)
      case Value.StringV(s)             => Some(s)
      case Value.OptionV(null)          => None
      case Value.OptionV(Value.StringV(s)) => Some(s)
      case inst @ Value.InstanceV("Some", _) =>
        inst.effectiveFields.get("value") match
          case Some(Value.StringV(s)) => Some(s)
          case Some(other)            => throw InterpretError(s"std.nfc: $label must be Option[String], got ${Value.show(other)}")
          case None                   => None
      case other =>
        throw InterpretError(s"std.nfc: $label must be Option[String], got $other")

  private def utf8Bytes(s: String): List[Int] =
    s.getBytes(StandardCharsets.UTF_8).iterator.map(b => b & 0xff).toList

  private def byteValue(value: Any): Int =
    val n: Long = value match
      case b: Byte          => (b & 0xff).toLong
      case s: Short         => s.toLong
      case i: Int           => i.toLong
      case l: Long          => l
      case bi: BigInt       => if bi.isValidLong then bi.toLong else Long.MinValue
      case Value.IntV(v)    => v
      case Value.BigIntV(v) => if v.isValidLong then v.toLong else Long.MinValue
      case other            => throw InterpretError(s"std.nfc: byte values must be Int in 0..255, got $other")
    if n < 0 || n > 255 then
      throw InterpretError(s"std.nfc: byte values must be in 0..255, got $n")
    n.toInt

  private def byteList(value: Any): List[Int] =
    value match
      case Value.ListV(items) => items.map(byteValue)
      case xs: List[?]       => xs.map(byteValue)
      case xs: Seq[?]        => xs.toList.map(byteValue)
      case other             => throw InterpretError(s"std.nfc: bytes must be List[Int], got $other")

  private def intList(values: List[Int]): Value.ListV =
    Value.ListV(values.map(n => Value.intV(n.toLong)))

  private def record(
      recordType: String,
      mediaType:  Option[String],
      id:         Option[String],
      encoding:   String,
      lang:       String,
      data:       List[Int]
  ): Value.InstanceV =
    Value.InstanceV("NdefRecord", Map(
      "recordType" -> Value.StringV(recordType),
      "mediaType"  -> optionString(mediaType),
      "id"         -> optionString(id),
      "encoding"   -> Value.StringV(encoding),
      "lang"       -> Value.StringV(lang),
      "data"       -> intList(data),
    ))

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("nfcCapabilities") -> native { _ =>
      Value.InstanceV("NfcCapabilities", Map(
        "supported"     -> Value.False,
        "enabled"       -> Value.False,
        "ndefRead"      -> Value.False,
        "ndefWrite"     -> Value.False,
        "tagTech"       -> Value.False,
        "cardEmulation" -> Value.False,
        "platform"      -> Value.StringV("interpreter"),
      ))
    },

    QualifiedName("nfcPermissionStatus") -> native { _ =>
      Value.InstanceV("NfcPermissionUnknown", Map.empty)
    },

    QualifiedName("requestNfcPermission") -> native { _ =>
      Value.InstanceV("NfcPermissionUnknown", Map.empty)
    },

    QualifiedName("readNdef") -> native { _ =>
      throw InterpretError(UnsupportedMessage)
    },

    QualifiedName("writeNdef") -> native { _ =>
      throw InterpretError(UnsupportedMessage)
    },

    QualifiedName("textRecord") -> native {
      case List(text: String) =>
        record("text", None, None, "utf-8", "en", utf8Bytes(text))
      case List(text: String, lang: String) =>
        record("text", None, None, "utf-8", lang, utf8Bytes(text))
      case List(text: String, lang: String, id) =>
        record("text", None, optionalStringArg(id, "id"), "utf-8", lang, utf8Bytes(text))
      case _ =>
        throw InterpretError("std.nfc: textRecord(text: String, lang: String = \"en\", id: Option[String] = None)")
    },

    QualifiedName("uriRecord") -> native {
      case List(uri: String) =>
        record("uri", None, None, "utf-8", "", utf8Bytes(uri))
      case List(uri: String, id) =>
        record("uri", None, optionalStringArg(id, "id"), "utf-8", "", utf8Bytes(uri))
      case _ =>
        throw InterpretError("std.nfc: uriRecord(uri: String, id: Option[String] = None)")
    },

    QualifiedName("mimeRecord") -> native {
      case List(mediaType: String, bytes) =>
        record("mime", Some(mediaType), None, "binary", "", byteList(bytes))
      case List(mediaType: String, bytes, id) =>
        record("mime", Some(mediaType), optionalStringArg(id, "id"), "binary", "", byteList(bytes))
      case _ =>
        throw InterpretError("std.nfc: mimeRecord(mediaType: String, bytes: List[Int], id: Option[String] = None)")
    },
  )
