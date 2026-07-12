package ssc.plugin.nfc

import java.nio.charset.StandardCharsets
import ssc.{Prims, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Explicit host NFC provider. Hosts without an adapter return a successful,
 * deterministic unsupported capability snapshot; read/write remain bounded. */
final class NfcNativePlugin extends NativePlugin:
  def id: String = "90-nfc-explicit"

  private val unsupported =
    "std.nfc: NFC hardware access is not supported by the current host provider"

  private def text(value: Value, label: String): String = value match
    case Value.StrV(result) => result
    case _ => throw new IllegalArgumentException(s"std.nfc: $label must be String")

  private def optionText(value: Value, label: String): Option[String] = value match
    case Value.DataV("None", IndexedSeq()) => None
    case Value.DataV("Some", IndexedSeq(inner)) => Some(text(inner, label))
    case Value.StrV(result) => Some(result)
    case _ => throw new IllegalArgumentException(s"std.nfc: $label must be Option[String]")

  private def option(value: Option[String]): Value = value
    .map(item => Value.DataV("Some", Vector(Value.StrV(item))))
    .getOrElse(Value.DataV("None", Vector.empty))

  private def list(values: IterableOnce[Value]): Value =
    Vector.from(values).reverseIterator.foldLeft[Value](Value.DataV("Nil", Vector.empty)) {
      (tail, head) => Value.DataV("Cons", Vector(head, tail))
    }

  private def bytes(value: Value): List[Int] =
    Prims.unlistPub(value).map {
      case Value.IntV(number) if number >= 0 && number <= 255 => number.toInt
      case Value.BigV(number) if number >= 0 && number <= 255 => number.toInt
      case _ => throw new IllegalArgumentException("std.nfc: byte values must be in 0..255")
    }

  private def record(
      recordType: String,
      mediaType: Option[String],
      id: Option[String],
      encoding: String,
      lang: String,
      data: List[Int]): Value =
    Value.DataV("NdefRecord", Vector(
      Value.StrV(recordType),
      option(mediaType),
      option(id),
      Value.StrV(encoding),
      Value.StrV(lang),
      list(data.map(number => Value.IntV(number.toLong)))))

  private def utf8(value: String): List[Int] =
    value.getBytes(StandardCharsets.UTF_8).iterator.map(_ & 0xff).toList

  def install(context: NativePluginContext): Unit =
    context.registerFields("NfcCapabilities", Vector(
      "supported", "enabled", "ndefRead", "ndefWrite", "tagTech", "cardEmulation", "platform"))
    context.registerFields("NdefRecord", Vector(
      "recordType", "mediaType", "id", "encoding", "lang", "data"))

    context.register("nfcCapabilities") { _ =>
      Value.DataV("NfcCapabilities", Vector(
        Value.BoolV(false), Value.BoolV(false), Value.BoolV(false), Value.BoolV(false),
        Value.BoolV(false), Value.BoolV(false), Value.StrV("jvm-host")))
    }
    context.register("nfcPermissionStatus") { _ =>
      Value.DataV("NfcPermissionUnknown", Vector.empty)
    }
    context.register("requestNfcPermission") { _ =>
      Value.DataV("NfcPermissionUnknown", Vector.empty)
    }
    context.register("readNdef") { _ => throw new UnsupportedOperationException(unsupported) }
    context.register("writeNdef") { _ => throw new UnsupportedOperationException(unsupported) }
    context.register("textRecord") {
      case value :: Nil => record("text", None, None, "utf-8", "en", utf8(text(value, "text")))
      case value :: language :: Nil =>
        val content = text(value, "text")
        record("text", None, None, "utf-8", text(language, "lang"), utf8(content))
      case value :: language :: id :: Nil =>
        val content = text(value, "text")
        record("text", None, optionText(id, "id"), "utf-8", text(language, "lang"), utf8(content))
      case _ => throw new IllegalArgumentException("std.nfc: textRecord(text, lang, id)")
    }
    context.register("uriRecord") {
      case value :: Nil =>
        val uri = text(value, "uri")
        record("uri", None, None, "utf-8", "", utf8(uri))
      case value :: id :: Nil =>
        val uri = text(value, "uri")
        record("uri", None, optionText(id, "id"), "utf-8", "", utf8(uri))
      case _ => throw new IllegalArgumentException("std.nfc: uriRecord(uri, id)")
    }
    context.register("mimeRecord") {
      case mediaType :: data :: Nil =>
        record("mime", Some(text(mediaType, "mediaType")), None, "binary", "", bytes(data))
      case mediaType :: data :: id :: Nil =>
        record("mime", Some(text(mediaType, "mediaType")), optionText(id, "id"),
          "binary", "", bytes(data))
      case _ => throw new IllegalArgumentException("std.nfc: mimeRecord(mediaType, bytes, id)")
    }
