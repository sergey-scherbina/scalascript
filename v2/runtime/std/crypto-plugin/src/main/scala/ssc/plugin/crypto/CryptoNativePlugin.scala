package ssc.plugin.crypto

import java.nio.charset.StandardCharsets.UTF_8
import ssc.Value
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free JVM crypto provider for the standard ScalaScript 2.1 runtime. */
final class CryptoNativePlugin extends NativePlugin:
  def id: String = "10-crypto"

  private def text(args: List[Value], index: Int): String = args.lift(index) match
    case Some(Value.StrV(value)) => value
    case _ => throw new RuntimeException(s"crypto argument ${index + 1} must be String")

  private def integer(args: List[Value], index: Int): Long = args.lift(index) match
    case Some(Value.IntV(value)) => value
    case _ => throw new RuntimeException(s"crypto argument ${index + 1} must be Int")

  private def bytes64(value: String): Array[Byte] = java.util.Base64.getDecoder.decode(value)
  private def base64(bytes: Array[Byte]): String = java.util.Base64.getEncoder.encodeToString(bytes)
  private def sha256Bytes(bytes: Array[Byte]): Array[Byte] =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  private def native(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, -1)(fn)

  def install(context: NativePluginContext): Unit =
    native(context, "sha256") { args =>
      Value.StrV(hex(sha256Bytes(args.headOption.collect { case Value.StrV(s) => s.getBytes(UTF_8) }
        .getOrElse(Array.emptyByteArray))))
    }
    native(context, "sha256Base64") { args =>
      Value.StrV(base64(sha256Bytes(args.headOption.collect { case Value.StrV(s) => s.getBytes(UTF_8) }
        .getOrElse(Array.emptyByteArray))))
    }
    native(context, "sha256OfBase64") { args =>
      try Value.StrV(base64(sha256Bytes(bytes64(text(args, 0)))))
      catch case e: Throwable => throw new RuntimeException(s"sha256OfBase64: ${e.getMessage}")
    }
    native(context, "byteLengthUtf8") { args =>
      Value.IntV(text(args, 0).getBytes(UTF_8).length.toLong)
    }
    native(context, "hmacSha256") { args =>
      val mac = javax.crypto.Mac.getInstance("HmacSHA256")
      mac.init(new javax.crypto.spec.SecretKeySpec(text(args, 0).getBytes(UTF_8), "HmacSHA256"))
      Value.StrV(hex(mac.doFinal(text(args, 1).getBytes(UTF_8))))
    }
    native(context, "pbkdf2") { args =>
      val spec = new javax.crypto.spec.PBEKeySpec(
        text(args, 0).toCharArray,
        bytes64(text(args, 1)),
        integer(args, 2).toInt,
        integer(args, 3).toInt * 8)
      try
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
          .generateSecret(spec).getEncoded
        Value.StrV(base64(key))
      catch case e: Throwable => throw new RuntimeException(s"pbkdf2: ${e.getMessage}")
      finally spec.clearPassword()
    }
    native(context, "secureRandomBytesB64") { args =>
      val size = integer(args, 0)
      if size < 0 || size > Int.MaxValue then
        throw new RuntimeException("secureRandomBytesB64: n must be between 0 and Int.MaxValue")
      val bytes = new Array[Byte](size.toInt)
      java.security.SecureRandom().nextBytes(bytes)
      Value.StrV(base64(bytes))
    }
    native(context, "base64Encode") { args => Value.StrV(base64(text(args, 0).getBytes(UTF_8))) }
    native(context, "base64Decode") { args =>
      try Value.StrV(String(bytes64(text(args, 0)), UTF_8))
      catch case _: Throwable => Value.StrV("")
    }
