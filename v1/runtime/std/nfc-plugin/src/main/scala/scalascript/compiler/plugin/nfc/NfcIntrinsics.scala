package scalascript.compiler.plugin.nfc

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginError, PluginNative, PluginValue}

import java.nio.charset.StandardCharsets

object NfcIntrinsics:

  private val UnsupportedMessage =
    "std.nfc: NFC hardware access is not supported by the interpreter backend"

  private def native(f: List[Any] => PluginValue): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  private def none: PluginValue = PluginValue.none

  private def someString(value: String): PluginValue =
    PluginValue.some(PluginValue.string(value))

  private def optionString(value: Option[String]): PluginValue =
    value match
      case Some(s) => someString(s)
      case None    => none

  private def optionalStringArg(value: Any, label: String): Option[String] =
    value match
      case null                                       => None
      case None                                       => None
      case Some(s: String)                            => Some(s)
      case s: String                                  => Some(s)
      case PluginValue.Str(s)                         => Some(s)
      case PluginValue.Opt(None)                      => None
      case PluginValue.Opt(Some(PluginValue.Str(s)))  => Some(s)
      case PluginValue.Inst("Some", fields) =>
        fields.get("value") match
          case Some(PluginValue.Str(s)) => Some(s)
          case Some(other)              => PluginError.raise(s"std.nfc: $label must be Option[String], got ${other.show}")
          case None                     => None
      case other =>
        PluginError.raise(s"std.nfc: $label must be Option[String], got $other")

  private def utf8Bytes(s: String): List[Int] =
    s.getBytes(StandardCharsets.UTF_8).iterator.map(b => b & 0xff).toList

  private def byteValue(value: Any): Int =
    val n: Long = value match
      case b: Byte          => (b & 0xff).toLong
      case s: Short         => s.toLong
      case i: Int           => i.toLong
      case l: Long          => l
      case bi: BigInt       => if bi.isValidLong then bi.toLong else Long.MinValue
      case PluginValue.Num(v) => v
      case PluginValue.Big(v) => if v.isValidLong then v.toLong else Long.MinValue
      case other            => PluginError.raise(s"std.nfc: byte values must be Int in 0..255, got $other")
    if n < 0 || n > 255 then
      PluginError.raise(s"std.nfc: byte values must be in 0..255, got $n")
    n.toInt

  private def byteList(value: Any): List[Int] =
    value match
      case PluginValue.Lst(items) => items.map(byteValue)
      case xs: List[?]       => xs.map(byteValue)
      case xs: Seq[?]        => xs.toList.map(byteValue)
      case other             => PluginError.raise(s"std.nfc: bytes must be List[Int], got $other")

  private def intList(values: List[Int]): PluginValue =
    PluginValue.list(values.map(n => PluginValue.int(n.toLong)))

  private def record(
      recordType: String,
      mediaType:  Option[String],
      id:         Option[String],
      encoding:   String,
      lang:       String,
      data:       List[Int]
  ): PluginValue =
    PluginValue.instance("NdefRecord", Map(
      "recordType" -> PluginValue.string(recordType),
      "mediaType"  -> optionString(mediaType),
      "id"         -> optionString(id),
      "encoding"   -> PluginValue.string(encoding),
      "lang"       -> PluginValue.string(lang),
      "data"       -> intList(data),
    ))

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("nfcCapabilities") -> native { _ =>
      PluginValue.instance("NfcCapabilities", Map(
        "supported"     -> PluginValue.bool(false),
        "enabled"       -> PluginValue.bool(false),
        "ndefRead"      -> PluginValue.bool(false),
        "ndefWrite"     -> PluginValue.bool(false),
        "tagTech"       -> PluginValue.bool(false),
        "cardEmulation" -> PluginValue.bool(false),
        "platform"      -> PluginValue.string("interpreter"),
      ))
    },

    QualifiedName("nfcPermissionStatus") -> native { _ =>
      PluginValue.instance("NfcPermissionUnknown", Map.empty)
    },

    QualifiedName("requestNfcPermission") -> native { _ =>
      PluginValue.instance("NfcPermissionUnknown", Map.empty)
    },

    QualifiedName("readNdef") -> native { _ =>
      PluginError.raise(UnsupportedMessage)
    },

    QualifiedName("writeNdef") -> native { _ =>
      PluginError.raise(UnsupportedMessage)
    },

    QualifiedName("textRecord") -> native {
      case List(text: String) =>
        record("text", None, None, "utf-8", "en", utf8Bytes(text))
      case List(text: String, lang: String) =>
        record("text", None, None, "utf-8", lang, utf8Bytes(text))
      case List(text: String, lang: String, id) =>
        record("text", None, optionalStringArg(id, "id"), "utf-8", lang, utf8Bytes(text))
      case _ =>
        PluginError.raise("std.nfc: textRecord(text: String, lang: String = \"en\", id: Option[String] = None)")
    },

    QualifiedName("uriRecord") -> native {
      case List(uri: String) =>
        record("uri", None, None, "utf-8", "", utf8Bytes(uri))
      case List(uri: String, id) =>
        record("uri", None, optionalStringArg(id, "id"), "utf-8", "", utf8Bytes(uri))
      case _ =>
        PluginError.raise("std.nfc: uriRecord(uri: String, id: Option[String] = None)")
    },

    QualifiedName("mimeRecord") -> native {
      case List(mediaType: String, bytes) =>
        record("mime", Some(mediaType), None, "binary", "", byteList(bytes))
      case List(mediaType: String, bytes, id) =>
        record("mime", Some(mediaType), optionalStringArg(id, "id"), "binary", "", byteList(bytes))
      case _ =>
        PluginError.raise("std.nfc: mimeRecord(mediaType: String, bytes: List[Int], id: Option[String] = None)")
    },
  )
