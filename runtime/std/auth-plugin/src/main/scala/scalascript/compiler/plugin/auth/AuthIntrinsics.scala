package scalascript.compiler.plugin.auth

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginError, PluginNative, PluginValue}

object AuthIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // ── Response.basicAuthChallenge ──────────────────────────────────────

    QualifiedName("Response.basicAuthChallenge") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(realm: String) =>
          val safe = realm.replace("\\", "\\\\").replace("\"", "\\\"")
          PluginValue.instance("Response", Map(
            "status"  -> PluginValue.int(401),
            "headers" -> PluginValue.mapOf(Map(
              PluginValue.string("WWW-Authenticate") -> PluginValue.string(s"""Basic realm="$safe"""")
            )),
            "body"    -> PluginValue.string("Authentication required")
          ))
        case _ => PluginError.raise("Response.basicAuthChallenge(realm: String)")
    },

    // ── CSRF ─────────────────────────────────────────────────────────────

    QualifiedName("csrfToken") -> PluginNative.eval { (_, _) =>
      val bytes = new Array[Byte](24)
      java.security.SecureRandom().nextBytes(bytes)
      PluginComputation.pure(
        PluginValue.wrap(PluginValue.string(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)))
      )
    },

    QualifiedName("csrfValid") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.Inst("Request", fields)) =>
          def asMap(v: Option[PluginValue]): Map[String, String] = v match
            case Some(PluginValue.MapVal(m)) =>
              m.collect { case (PluginValue.Str(k), PluginValue.Str(s)) => k -> s }.toMap
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
          PluginValue.bool(ok)
        case _ => PluginError.raise("csrfValid(req)")
    },

    // ── Base64-url codec ──────────────────────────────────────────────────

    QualifiedName("base64UrlEncode") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(s: String) =>
          PluginValue.string(java.util.Base64.getUrlEncoder.withoutPadding
            .encodeToString(s.getBytes("UTF-8")))
        case _ => PluginError.raise("base64UrlEncode(s: String)")
    },

    QualifiedName("base64UrlDecode") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(s: String) =>
          try PluginValue.string(String(java.util.Base64.getUrlDecoder.decode(s), "UTF-8"))
          catch case _: Throwable => PluginValue.string("")
        case _ => PluginError.raise("base64UrlDecode(s: String)")
    },

    // ── WebAuthn / passkeys ───────────────────────────────────────────────

    QualifiedName("webauthnChallenge") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(uid: String) => PluginValue.string(scalascript.server.WebAuthn.challenge(uid))
        case _ => PluginError.raise("webauthnChallenge(userId: String)")
    },

    QualifiedName("webauthnConsumeChallenge") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(s: String) =>
          scalascript.server.WebAuthn.consumeChallenge(s) match
            case Some(uid) => PluginValue.some(PluginValue.string(uid))
            case None      => PluginValue.none
        case _ => PluginError.raise("webauthnConsumeChallenge(challenge: String)")
    },

    QualifiedName("webauthnStorePut") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(uid: String, cid: String, pk: String, cnt: Long) =>
          scalascript.server.WebAuthn.storePut(uid,
            scalascript.server.WebAuthn.Credential(cid, pk, cnt))
          ()
        case _ => PluginError.raise(
          "webauthnStorePut(userId, credentialId, publicKeyB64, signCount)")
    },

    QualifiedName("webauthnStoreGet") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(uid: String) =>
          PluginValue.list(scalascript.server.WebAuthn.storeGet(uid).map { c =>
            PluginValue.mapOf(Map(
              PluginValue.string("credentialId") -> PluginValue.string(c.credentialId),
              PluginValue.string("publicKey")    -> PluginValue.string(c.publicKey),
              PluginValue.string("signCount")    -> PluginValue.int(c.signCount),
            ))
          })
        case _ => PluginError.raise("webauthnStoreGet(userId: String)")
    },

    QualifiedName("webauthnStoreFind") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(uid: String, cid: String) =>
          scalascript.server.WebAuthn.storeFind(uid, cid) match
            case Some(c) =>
              PluginValue.some(PluginValue.mapOf(Map(
                PluginValue.string("credentialId") -> PluginValue.string(c.credentialId),
                PluginValue.string("publicKey")    -> PluginValue.string(c.publicKey),
                PluginValue.string("signCount")    -> PluginValue.int(c.signCount),
              )))
            case None => PluginValue.none
        case _ => PluginError.raise("webauthnStoreFind(userId, credentialId)")
    },

    QualifiedName("webauthnUpdateSignCount") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(_: String, _: String, _: Long) =>
          PluginValue.bool(false)
        case _ => PluginError.raise(
          "webauthnUpdateSignCount(userId, credentialId, newSignCount)")
    },

    QualifiedName("webauthnVerifyAssertion") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(cd: String, ad: String, sig: String, cid: String, origin: String) =>
          scalascript.server.WebAuthn.verifyAssertion(cd, ad, sig, cid, origin) match
            case Some(a) =>
              PluginValue.some(PluginValue.mapOf(Map(
                PluginValue.string("userId")    -> PluginValue.string(a.userId),
                PluginValue.string("signCount") -> PluginValue.int(a.signCount),
              )))
            case None => PluginValue.none
        case _ => PluginError.raise(
          "webauthnVerifyAssertion(clientDataJSONb64, authenticatorDataB64, " +
          "signatureB64, credentialIdB64, expectedOrigin)")
    },

    QualifiedName("webauthnVerifyRegistration") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(cd: String, att: String, origin: String) =>
          scalascript.server.WebAuthn.verifyRegistration(cd, att, origin) match
            case Some(r) =>
              PluginValue.some(PluginValue.mapOf(Map(
                PluginValue.string("userId")       -> PluginValue.string(r.userId),
                PluginValue.string("credentialId") -> PluginValue.string(r.credentialId),
                PluginValue.string("publicKey")    -> PluginValue.string(r.publicKey),
                PluginValue.string("signCount")    -> PluginValue.int(r.signCount),
              )))
            case None => PluginValue.none
        case _ => PluginError.raise(
          "webauthnVerifyRegistration(clientDataJSONb64, attestationObjectB64, expectedOrigin)")
    },

    // ── Rate limiting ─────────────────────────────────────────────────────

    QualifiedName("rateLimit") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(_: String, _: Long, _: Long) =>
          PluginValue.bool(false)
        case _ => PluginError.raise("rateLimit(key: String, limit: Int, windowSeconds: Int)")
    },

    QualifiedName("rateLimitReset") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(key: String) => scalascript.server.RateLimit.reset(key); ()
        case _ => PluginError.raise("rateLimitReset(key: String)")
    },

    // ── TOTP / 2FA (RFC 6238) ─────────────────────────────────────────────

    QualifiedName("totpSecret") -> PluginNative.evalLegacy { (_, _) =>
      PluginValue.string(scalascript.server.Totp.secret())
    },

    QualifiedName("totpUri") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(s: String, account: String) =>
          PluginValue.string(scalascript.server.Totp.uri(s, account))
        case List(s: String, account: String, issuer: String) =>
          PluginValue.string(scalascript.server.Totp.uri(s, account, issuer))
        case _ => PluginError.raise("totpUri(secret, account[, issuer])")
    },

    QualifiedName("totpCode") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(s: String) => PluginValue.string(scalascript.server.Totp.code(s))
        case _ => PluginError.raise("totpCode(secret)")
    },

    QualifiedName("totpValid") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(_: String, _: String) =>
          PluginValue.bool(false)
        case List(_: String, _: String, _: Long) =>
          PluginValue.bool(false)
        case _ => PluginError.raise("totpValid(secret, code[, skew])")
    },

    // ── Password hashing (PBKDF2-HMAC-SHA256) ────────────────────────────

    QualifiedName("hashPassword") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(pass: String)             => PluginValue.string(scalascript.server.Password.hash(pass))
        case List(pass: String, iter: Long) => PluginValue.string(scalascript.server.Password.hash(pass, iter.toInt))
        case _ => PluginError.raise("hashPassword(password[, iter])")
    },

    QualifiedName("verifyPassword") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(pass: String, encoded: String) =>
          PluginValue.bool(scalascript.server.Password.verify(pass, encoded))
        case _ => PluginError.raise("verifyPassword(password, encoded)")
    },

    // ── Cookie / session ──────────────────────────────────────────────────

    QualifiedName("cookieConfig") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(secure: Boolean) =>
          scalascript.server.SessionCookie.setCookieConfig(
            secure, scalascript.server.SessionCookie.cookieSameSite)
          ()
        case List(secure: Boolean, sameSite: String) =>
          scalascript.server.SessionCookie.setCookieConfig(secure, sameSite)
          ()
        case _ => PluginError.raise("cookieConfig(secure: Boolean[, sameSite: String])")
    },

    QualifiedName("useSessionStore") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case Nil             => scalascript.server.SessionStore.useStore(); ()
        case List(ttl: Long) => scalascript.server.SessionStore.useStore(ttl); ()
        case _ => PluginError.raise("useSessionStore() or useSessionStore(ttlSeconds: Int)")
    },

    // ── JWT HS256 ─────────────────────────────────────────────────────────

    QualifiedName("jwtSign") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.MapVal(m)) =>
          val claims = m.collect {
            case (PluginValue.Str(k), PluginValue.Str(v)) => k -> v
            case (PluginValue.Str(k), other)            => k -> other.show
          }.toMap
          PluginValue.string(scalascript.server.Jwt.sign(claims))
        case _ => PluginError.raise("jwtSign(Map[String, String])")
    },

    QualifiedName("jwtVerify") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(token: String) =>
          scalascript.server.Jwt.verify(token) match
            case Some(claims) =>
              PluginValue.some(PluginValue.mapOf(claims.map((k, v) => PluginValue.string(k) -> PluginValue.string(v))))
            case None => PluginValue.none
        case _ => PluginError.raise("jwtVerify(token: String)")
    },

    // ── JWT RS256 (asymmetric) ────────────────────────────────────────────

    QualifiedName("jwtSignRsa") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(PluginValue.MapVal(m)) =>
          val claims = m.collect {
            case (PluginValue.Str(k), PluginValue.Str(v)) => k -> v
            case (PluginValue.Str(k), other)            => k -> other.show
          }.toMap
          PluginValue.string(scalascript.server.JwtRsa.sign(claims))
        case _ => PluginError.raise("jwtSignRsa(Map[String, String])")
    },

    QualifiedName("jwtVerifyRsa") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(token: String) =>
          scalascript.server.JwtRsa.verify(token) match
            case Some(claims) =>
              PluginValue.some(PluginValue.mapOf(claims.map((k, v) => PluginValue.string(k) -> PluginValue.string(v))))
            case None => PluginValue.none
        case _ => PluginError.raise("jwtVerifyRsa(token: String)")
    },

    // ── OAuth2 ───────────────────────────────────────────────────────────

    QualifiedName("oauthAuthorizeUrl") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(prov: String, cid: String, redir: String, state: String) =>
          PluginValue.string(scalascript.server.OAuth.authorizeUrl(prov, cid, redir, state))
        case List(prov: String, cid: String, redir: String, state: String, scope: String) =>
          PluginValue.string(scalascript.server.OAuth.authorizeUrl(prov, cid, redir, state, scope))
        case _ => PluginError.raise(
          "oauthAuthorizeUrl(provider, clientId, redirectUri, state[, scope])")
    },

    QualifiedName("oauthExchangeCode") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(prov: String, code: String, cid: String, csec: String, redir: String) =>
          scalascript.server.OAuth.exchangeCode(prov, code, cid, csec, redir) match
            case Some(m) =>
              PluginValue.some(PluginValue.mapOf(m.map((k, v) => PluginValue.string(k) -> PluginValue.string(v))))
            case None => PluginValue.none
        case _ => PluginError.raise(
          "oauthExchangeCode(provider, code, clientId, clientSecret, redirectUri)")
    },

    QualifiedName("oauthUserinfo") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(prov: String, token: String) =>
          scalascript.server.OAuth.userinfo(prov, token) match
            case Some(m) =>
              PluginValue.some(PluginValue.mapOf(m.map((k, v) => PluginValue.string(k) -> PluginValue.string(v))))
            case None => PluginValue.none
        case _ => PluginError.raise("oauthUserinfo(provider, accessToken)")
    },

    QualifiedName("oauthRefreshToken") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(prov: String, refresh: String, cid: String, csec: String) =>
          scalascript.server.OAuth.refreshToken(prov, refresh, cid, csec) match
            case Some(m) =>
              PluginValue.some(PluginValue.mapOf(m.map((k, v) => PluginValue.string(k) -> PluginValue.string(v))))
            case None => PluginValue.none
        case _ => PluginError.raise(
          "oauthRefreshToken(provider, refreshToken, clientId, clientSecret)")
    },

    QualifiedName("oauthRegisterProvider") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String, PluginValue.MapVal(m)) =>
          val cfg = m.collect { case (PluginValue.Str(k), PluginValue.Str(v)) => k -> v }.toMap
          scalascript.server.OAuth.registerProvider(name, cfg)
          ()
        case _ => PluginError.raise(
          "oauthRegisterProvider(name, Map[String, String])")
    },
  )
