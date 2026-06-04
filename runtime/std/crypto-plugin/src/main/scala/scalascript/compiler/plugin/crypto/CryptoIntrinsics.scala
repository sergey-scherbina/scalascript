package scalascript.compiler.plugin.crypto

import scalascript.backend.spi.*
import scalascript.interpreter.Value
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

object CryptoIntrinsics:

  private def native(f: List[Any] => Value): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("sha256") -> native {
      case List(s: String) =>
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Value.StringV(md.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString)
      case _ =>
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Value.StringV(md.digest(Array.emptyByteArray).map("%02x".format(_)).mkString)
    },

    QualifiedName("hmacSha256") -> native {
      case List(key: String, data: String) =>
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"))
        Value.StringV(mac.doFinal(data.getBytes("UTF-8")).map("%02x".format(_)).mkString)
      case _ => Value.StringV("")
    },

    QualifiedName("base64Encode") -> native {
      case List(s: String) =>
        Value.StringV(java.util.Base64.getEncoder.encodeToString(s.getBytes("UTF-8")))
      case _ => Value.StringV("")
    },

    QualifiedName("base64Decode") -> native {
      case List(s: String) =>
        try Value.StringV(String(java.util.Base64.getDecoder.decode(s), "UTF-8"))
        catch case _: Throwable => Value.StringV("")
      case _ => Value.StringV("")
    },

  )
