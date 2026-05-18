// Runtime helpers for org.example.crypto plugin — JVM backend.
// These functions are injected into the generated Scala preamble when
// a program imports std.crypto and is compiled with the jvm backend.

private def _cryptoSha256(input: Any): String =
  val md = java.security.MessageDigest.getInstance("SHA-256")
  md.digest(input.toString.getBytes("UTF-8"))
    .map("%02x".format(_)).mkString

private def _cryptoBase64Encode(s: Any): String =
  java.util.Base64.getEncoder.encodeToString(s.toString.getBytes("UTF-8"))

private def _cryptoBase64Decode(s: Any): String =
  new String(java.util.Base64.getDecoder.decode(s.toString), "UTF-8")

private def _cryptoHmacSha256(key: Any, data: Any): String =
  val mac = javax.crypto.Mac.getInstance("HmacSHA256")
  mac.init(new javax.crypto.spec.SecretKeySpec(key.toString.getBytes("UTF-8"), "HmacSHA256"))
  mac.doFinal(data.toString.getBytes("UTF-8")).map("%02x".format(_)).mkString
