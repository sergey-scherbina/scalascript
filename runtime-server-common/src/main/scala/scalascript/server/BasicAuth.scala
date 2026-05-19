package scalascript.server

/** Extract `(user, password)` from an `Authorization: Basic …` header
 *  value.  Returns `None` for any non-Basic / malformed input — never
 *  throws.  Mirrors `Jwt.fromAuthHeader` (which handles `Bearer`). */
object BasicAuth:

  def fromHeader(authHeader: String): Option[(String, String)] =
    val t = Option(authHeader).map(_.trim).getOrElse("")
    if t.length < 6 || !t.substring(0, 6).equalsIgnoreCase("Basic ") then None
    else
      try
        val decoded = String(java.util.Base64.getDecoder.decode(t.substring(6).trim), "UTF-8")
        val colon   = decoded.indexOf(':')
        if colon < 0 then None
        else Some(decoded.substring(0, colon) -> decoded.substring(colon + 1))
      catch case _: Throwable => None
