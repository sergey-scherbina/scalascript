package ssc.plugin.crypto

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** IntrinsicImpl table for the org.example.crypto plugin.
 *
 *  Each entry maps a fully-qualified `extern def` name to an
 *  implementation strategy:
 *
 *  - `RuntimeCall(sym)` — for code-generating backends (jvm, js):
 *    the emitted call site becomes `sym(args...)`.  The definition of
 *    `sym` lives in `runtime/jvm.scala` (or `runtime/js.js`) inside the
 *    `.sscpkg`, which BackendRegistry prepends to every compiled file.
 *
 *  - `NativeImpl(fn)` — for the interpreter backend:
 *    `fn` is called directly with the argument list; no code emission
 *    needed since the interpreter never generates text output. */
object CryptoIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("std.crypto.sha256") -> IntrinsicImpl.RuntimeCall("_cryptoSha256"),

    QualifiedName("std.crypto.base64Encode") -> IntrinsicImpl.RuntimeCall("_cryptoBase64Encode"),

    QualifiedName("std.crypto.base64Decode") -> IntrinsicImpl.RuntimeCall("_cryptoBase64Decode"),

    QualifiedName("std.crypto.hmacSha256") -> IntrinsicImpl.RuntimeCall("_cryptoHmacSha256"),
  )

  /** Interpreter NativeImpl table (identical symbols, direct JVM calls). */
  val interpreterTable: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("std.crypto.sha256") -> IntrinsicImpl.NativeImpl { (_, args) =>
      val input = args.headOption.map(_.toString).getOrElse("")
      val md    = java.security.MessageDigest.getInstance("SHA-256")
      md.digest(input.getBytes("UTF-8")).map("%02x".format(_)).mkString
    },

    QualifiedName("std.crypto.base64Encode") -> IntrinsicImpl.NativeImpl { (_, args) =>
      java.util.Base64.getEncoder.encodeToString(
        args.headOption.map(_.toString).getOrElse("").getBytes("UTF-8"))
    },

    QualifiedName("std.crypto.base64Decode") -> IntrinsicImpl.NativeImpl { (_, args) =>
      new String(
        java.util.Base64.getDecoder.decode(args.headOption.map(_.toString).getOrElse("")), "UTF-8")
    },

    QualifiedName("std.crypto.hmacSha256") -> IntrinsicImpl.NativeImpl { (_, args) =>
      val key  = args.lift(0).map(_.toString).getOrElse("")
      val data = args.lift(1).map(_.toString).getOrElse("")
      val mac  = javax.crypto.Mac.getInstance("HmacSHA256")
      mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"))
      mac.doFinal(data.getBytes("UTF-8")).map("%02x".format(_)).mkString
    },
  )
