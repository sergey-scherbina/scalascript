package scalascript.compiler.plugin.auth

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError}

object AuthIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // ── Response.basicAuthChallenge ──────────────────────────────────────

    QualifiedName("Response.basicAuthChallenge") -> NativeImpl((_, args) =>
      args match
        case List(realm: String) =>
          val safe = realm.replace("\\", "\\\\").replace("\"", "\\\"")
          Value.InstanceV("Response", Map(
            "status"  -> Value.IntV(401),
            "headers" -> Value.MapV(Map(
              Value.StringV("WWW-Authenticate") -> Value.StringV(s"""Basic realm="$safe"""")
            )),
            "body"    -> Value.StringV("Authentication required")
          ))
        case _ => throw InterpretError("Response.basicAuthChallenge(realm: String)")
    ),

    // ── CSRF ─────────────────────────────────────────────────────────────

    QualifiedName("csrfToken") -> NativeImpl((_, _) =>
      val bytes = new Array[Byte](24)
      java.security.SecureRandom().nextBytes(bytes)
      Value.StringV(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))
    ),

    QualifiedName("csrfValid") -> NativeImpl((_, args) =>
      args match
        case List(Value.InstanceV("Request", fields)) =>
          def asMap(v: Option[Value]): Map[String, String] = v match
            case Some(Value.MapV(m)) =>
              m.collect { case (Value.StringV(k), Value.StringV(s)) => k -> s }.toMap
            case _ => Map.empty
          val form     = asMap(fields.get("form"))
          val headers  = asMap(fields.get("headers"))
          val session  = asMap(fields.get("session"))
          val expected = session.getOrElse("csrf", "")
          val supplied = form.get("csrf")
            .orElse(headers.collectFirst { case (k, v) if k.equalsIgnoreCase("X-CSRF-Token") => v })
            .getOrElse("")
          val ok =
            if expected.isEmpty || supplied.isEmpty then false
            else java.security.MessageDigest.isEqual(
              expected.getBytes("UTF-8"), supplied.getBytes("UTF-8"))
          Value.BoolV(ok)
        case _ => throw InterpretError("csrfValid(req)")
    ),

    // ── Base64-url codec ──────────────────────────────────────────────────

    QualifiedName("base64UrlEncode") -> NativeImpl((_, args) =>
      args match
        case List(s: String) =>
          Value.StringV(java.util.Base64.getUrlEncoder.withoutPadding
            .encodeToString(s.getBytes("UTF-8")))
        case _ => throw InterpretError("base64UrlEncode(s: String)")
    ),

    QualifiedName("base64UrlDecode") -> NativeImpl((_, args) =>
      args match
        case List(s: String) =>
          try Value.StringV(String(java.util.Base64.getUrlDecoder.decode(s), "UTF-8"))
          catch case _: Throwable => Value.StringV("")
        case _ => throw InterpretError("base64UrlDecode(s: String)")
    ),

    // ── WebAuthn / passkeys ───────────────────────────────────────────────

    QualifiedName("webauthnChallenge") -> NativeImpl((_, args) =>
      args match
        case List(uid: String) => Value.StringV(scalascript.server.WebAuthn.challenge(uid))
        case _ => throw InterpretError("webauthnChallenge(userId: String)")
    ),

    QualifiedName("webauthnConsumeChallenge") -> NativeImpl((_, args) =>
      args match
        case List(s: String) =>
          scalascript.server.WebAuthn.consumeChallenge(s) match
            case Some(uid) => Value.OptionV(Some(Value.StringV(uid)))
            case None      => Value.OptionV(None)
        case _ => throw InterpretError("webauthnConsumeChallenge(challenge: String)")
    ),

    QualifiedName("webauthnStorePut") -> NativeImpl((_, args) =>
      args match
        case List(uid: String, cid: String, pk: String, cnt: Long) =>
          scalascript.server.WebAuthn.storePut(uid,
            scalascript.server.WebAuthn.Credential(cid, pk, cnt))
          ()
        case _ => throw InterpretError(
          "webauthnStorePut(userId, credentialId, publicKeyB64, signCount)")
    ),

    QualifiedName("webauthnStoreGet") -> NativeImpl((_, args) =>
      args match
        case List(uid: String) =>
          Value.ListV(scalascript.server.WebAuthn.storeGet(uid).map { c =>
            Value.MapV(Map(
              Value.StringV("credentialId") -> Value.StringV(c.credentialId),
              Value.StringV("publicKey")    -> Value.StringV(c.publicKey),
              Value.StringV("signCount")    -> Value.IntV(c.signCount),
            ))
          })
        case _ => throw InterpretError("webauthnStoreGet(userId: String)")
    ),

    QualifiedName("webauthnStoreFind") -> NativeImpl((_, args) =>
      args match
        case List(uid: String, cid: String) =>
          scalascript.server.WebAuthn.storeFind(uid, cid) match
            case Some(c) =>
              Value.OptionV(Some(Value.MapV(Map(
                Value.StringV("credentialId") -> Value.StringV(c.credentialId),
                Value.StringV("publicKey")    -> Value.StringV(c.publicKey),
                Value.StringV("signCount")    -> Value.IntV(c.signCount),
              ))))
            case None => Value.OptionV(None)
        case _ => throw InterpretError("webauthnStoreFind(userId, credentialId)")
    ),

    QualifiedName("webauthnUpdateSignCount") -> NativeImpl((_, args) =>
      args match
        case List(uid: String, cid: String, cnt: Long) =>
          Value.BoolV(scalascript.server.WebAuthn.storeUpdateSignCount(uid, cid, cnt))
        case _ => throw InterpretError(
          "webauthnUpdateSignCount(userId, credentialId, newSignCount)")
    ),

    QualifiedName("webauthnVerifyAssertion") -> NativeImpl((_, args) =>
      args match
        case List(cd: String, ad: String, sig: String, cid: String, origin: String) =>
          scalascript.server.WebAuthn.verifyAssertion(cd, ad, sig, cid, origin) match
            case Some(a) =>
              Value.OptionV(Some(Value.MapV(Map(
                Value.StringV("userId")    -> Value.StringV(a.userId),
                Value.StringV("signCount") -> Value.IntV(a.signCount),
              ))))
            case None => Value.OptionV(None)
        case _ => throw InterpretError(
          "webauthnVerifyAssertion(clientDataJSONb64, authenticatorDataB64, " +
          "signatureB64, credentialIdB64, expectedOrigin)")
    ),

    QualifiedName("webauthnVerifyRegistration") -> NativeImpl((_, args) =>
      args match
        case List(cd: String, att: String, origin: String) =>
          scalascript.server.WebAuthn.verifyRegistration(cd, att, origin) match
            case Some(r) =>
              Value.OptionV(Some(Value.MapV(Map(
                Value.StringV("userId")       -> Value.StringV(r.userId),
                Value.StringV("credentialId") -> Value.StringV(r.credentialId),
                Value.StringV("publicKey")    -> Value.StringV(r.publicKey),
                Value.StringV("signCount")    -> Value.IntV(r.signCount),
              ))))
            case None => Value.OptionV(None)
        case _ => throw InterpretError(
          "webauthnVerifyRegistration(clientDataJSONb64, attestationObjectB64, expectedOrigin)")
    ),

    // ── Rate limiting ─────────────────────────────────────────────────────

    QualifiedName("rateLimit") -> NativeImpl((_, args) =>
      args match
        case List(key: String, lim: Long, win: Long) =>
          Value.BoolV(scalascript.server.RateLimit.tryAcquire(key, lim, win))
        case _ => throw InterpretError("rateLimit(key: String, limit: Int, windowSeconds: Int)")
    ),

    QualifiedName("rateLimitReset") -> NativeImpl((_, args) =>
      args match
        case List(key: String) => scalascript.server.RateLimit.reset(key); ()
        case _ => throw InterpretError("rateLimitReset(key: String)")
    ),

    // ── TOTP / 2FA (RFC 6238) ─────────────────────────────────────────────

    QualifiedName("totpSecret") -> NativeImpl((_, _) =>
      Value.StringV(scalascript.server.Totp.secret())
    ),

    QualifiedName("totpUri") -> NativeImpl((_, args) =>
      args match
        case List(s: String, account: String) =>
          Value.StringV(scalascript.server.Totp.uri(s, account))
        case List(s: String, account: String, issuer: String) =>
          Value.StringV(scalascript.server.Totp.uri(s, account, issuer))
        case _ => throw InterpretError("totpUri(secret, account[, issuer])")
    ),

    QualifiedName("totpCode") -> NativeImpl((_, args) =>
      args match
        case List(s: String) => Value.StringV(scalascript.server.Totp.code(s))
        case _ => throw InterpretError("totpCode(secret)")
    ),

    QualifiedName("totpValid") -> NativeImpl((_, args) =>
      args match
        case List(s: String, code: String) =>
          Value.BoolV(scalascript.server.Totp.valid(s, code))
        case List(s: String, code: String, skew: Long) =>
          Value.BoolV(scalascript.server.Totp.valid(s, code, skew.toInt))
        case _ => throw InterpretError("totpValid(secret, code[, skew])")
    ),

    // ── Password hashing (PBKDF2-HMAC-SHA256) ────────────────────────────

    QualifiedName("hashPassword") -> NativeImpl((_, args) =>
      args match
        case List(pass: String)             => Value.StringV(scalascript.server.Password.hash(pass))
        case List(pass: String, iter: Long) => Value.StringV(scalascript.server.Password.hash(pass, iter.toInt))
        case _ => throw InterpretError("hashPassword(password[, iter])")
    ),

    QualifiedName("verifyPassword") -> NativeImpl((_, args) =>
      args match
        case List(pass: String, encoded: String) =>
          Value.BoolV(scalascript.server.Password.verify(pass, encoded))
        case _ => throw InterpretError("verifyPassword(password, encoded)")
    ),

    // ── Cookie / session ──────────────────────────────────────────────────

    QualifiedName("cookieConfig") -> NativeImpl((_, args) =>
      args match
        case List(secure: Boolean) =>
          scalascript.server.SessionCookie.setCookieConfig(
            secure, scalascript.server.SessionCookie.cookieSameSite)
          ()
        case List(secure: Boolean, sameSite: String) =>
          scalascript.server.SessionCookie.setCookieConfig(secure, sameSite)
          ()
        case _ => throw InterpretError("cookieConfig(secure: Boolean[, sameSite: String])")
    ),

    QualifiedName("useSessionStore") -> NativeImpl((_, args) =>
      args match
        case Nil             => scalascript.server.SessionStore.useStore(); ()
        case List(ttl: Long) => scalascript.server.SessionStore.useStore(ttl); ()
        case _ => throw InterpretError("useSessionStore() or useSessionStore(ttlSeconds: Int)")
    ),

    // ── JWT HS256 ─────────────────────────────────────────────────────────

    QualifiedName("jwtSign") -> NativeImpl((_, args) =>
      args match
        case List(Value.MapV(m)) =>
          val claims = m.collect {
            case (Value.StringV(k), Value.StringV(v)) => k -> v
            case (Value.StringV(k), other)            => k -> Value.show(other)
          }.toMap
          Value.StringV(scalascript.server.Jwt.sign(claims))
        case _ => throw InterpretError("jwtSign(Map[String, String])")
    ),

    QualifiedName("jwtVerify") -> NativeImpl((_, args) =>
      args match
        case List(token: String) =>
          scalascript.server.Jwt.verify(token) match
            case Some(claims) =>
              Value.OptionV(Some(Value.MapV(claims.map((k, v) => Value.StringV(k) -> Value.StringV(v)))))
            case None => Value.OptionV(None)
        case _ => throw InterpretError("jwtVerify(token: String)")
    ),

    // ── JWT RS256 (asymmetric) ────────────────────────────────────────────

    QualifiedName("jwtSignRsa") -> NativeImpl((_, args) =>
      args match
        case List(Value.MapV(m)) =>
          val claims = m.collect {
            case (Value.StringV(k), Value.StringV(v)) => k -> v
            case (Value.StringV(k), other)            => k -> Value.show(other)
          }.toMap
          Value.StringV(scalascript.server.JwtRsa.sign(claims))
        case _ => throw InterpretError("jwtSignRsa(Map[String, String])")
    ),

    QualifiedName("jwtVerifyRsa") -> NativeImpl((_, args) =>
      args match
        case List(token: String) =>
          scalascript.server.JwtRsa.verify(token) match
            case Some(claims) =>
              Value.OptionV(Some(Value.MapV(claims.map((k, v) => Value.StringV(k) -> Value.StringV(v)))))
            case None => Value.OptionV(None)
        case _ => throw InterpretError("jwtVerifyRsa(token: String)")
    ),

    // ── OAuth2 ───────────────────────────────────────────────────────────

    QualifiedName("oauthAuthorizeUrl") -> NativeImpl((_, args) =>
      args match
        case List(prov: String, cid: String, redir: String, state: String) =>
          Value.StringV(scalascript.server.OAuth.authorizeUrl(prov, cid, redir, state))
        case List(prov: String, cid: String, redir: String, state: String, scope: String) =>
          Value.StringV(scalascript.server.OAuth.authorizeUrl(prov, cid, redir, state, scope))
        case _ => throw InterpretError(
          "oauthAuthorizeUrl(provider, clientId, redirectUri, state[, scope])")
    ),

    QualifiedName("oauthExchangeCode") -> NativeImpl((_, args) =>
      args match
        case List(prov: String, code: String, cid: String, csec: String, redir: String) =>
          scalascript.server.OAuth.exchangeCode(prov, code, cid, csec, redir) match
            case Some(m) =>
              Value.OptionV(Some(Value.MapV(m.map((k, v) => Value.StringV(k) -> Value.StringV(v)))))
            case None => Value.OptionV(None)
        case _ => throw InterpretError(
          "oauthExchangeCode(provider, code, clientId, clientSecret, redirectUri)")
    ),

    QualifiedName("oauthUserinfo") -> NativeImpl((_, args) =>
      args match
        case List(prov: String, token: String) =>
          scalascript.server.OAuth.userinfo(prov, token) match
            case Some(m) =>
              Value.OptionV(Some(Value.MapV(m.map((k, v) => Value.StringV(k) -> Value.StringV(v)))))
            case None => Value.OptionV(None)
        case _ => throw InterpretError("oauthUserinfo(provider, accessToken)")
    ),

    QualifiedName("oauthRefreshToken") -> NativeImpl((_, args) =>
      args match
        case List(prov: String, refresh: String, cid: String, csec: String) =>
          scalascript.server.OAuth.refreshToken(prov, refresh, cid, csec) match
            case Some(m) =>
              Value.OptionV(Some(Value.MapV(m.map((k, v) => Value.StringV(k) -> Value.StringV(v)))))
            case None => Value.OptionV(None)
        case _ => throw InterpretError(
          "oauthRefreshToken(provider, refreshToken, clientId, clientSecret)")
    ),

    QualifiedName("oauthRegisterProvider") -> NativeImpl((_, args) =>
      args match
        case List(name: String, Value.MapV(m)) =>
          val cfg = m.collect { case (Value.StringV(k), Value.StringV(v)) => k -> v }.toMap
          scalascript.server.OAuth.registerProvider(name, cfg)
          ()
        case _ => throw InterpretError(
          "oauthRegisterProvider(name, Map[String, String])")
    ),
  )
