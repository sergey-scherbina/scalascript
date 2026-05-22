package scalascript.codegen.jvm

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Auth intrinsics for the JVM (Scala-CLI) backend (Stage 5+/D). */
val JvmAuthIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("Response.basicAuthChallenge") -> RuntimeCall("Response.basicAuthChallenge"),
  QualifiedName("csrfToken")                   -> RuntimeCall("csrfToken"),
  QualifiedName("csrfValid")                   -> RuntimeCall("csrfValid"),
  QualifiedName("base64UrlEncode")             -> RuntimeCall("base64UrlEncode"),
  QualifiedName("base64UrlDecode")             -> RuntimeCall("base64UrlDecode"),
  QualifiedName("webauthnChallenge")           -> RuntimeCall("webauthnChallenge"),
  QualifiedName("webauthnConsumeChallenge")    -> RuntimeCall("webauthnConsumeChallenge"),
  QualifiedName("webauthnStorePut")            -> RuntimeCall("webauthnStorePut"),
  QualifiedName("webauthnStoreGet")            -> RuntimeCall("webauthnStoreGet"),
  QualifiedName("webauthnStoreFind")           -> RuntimeCall("webauthnStoreFind"),
  QualifiedName("webauthnUpdateSignCount")     -> RuntimeCall("webauthnUpdateSignCount"),
  QualifiedName("webauthnVerifyAssertion")     -> RuntimeCall("webauthnVerifyAssertion"),
  QualifiedName("webauthnVerifyRegistration")  -> RuntimeCall("webauthnVerifyRegistration"),
  QualifiedName("rateLimit")                   -> RuntimeCall("rateLimit"),
  QualifiedName("rateLimitReset")              -> RuntimeCall("rateLimitReset"),
  QualifiedName("totpSecret")                  -> RuntimeCall("totpSecret"),
  QualifiedName("totpUri")                     -> RuntimeCall("totpUri"),
  QualifiedName("totpCode")                    -> RuntimeCall("totpCode"),
  QualifiedName("totpValid")                   -> RuntimeCall("totpValid"),
  QualifiedName("hashPassword")                -> RuntimeCall("hashPassword"),
  QualifiedName("verifyPassword")              -> RuntimeCall("verifyPassword"),
  QualifiedName("cookieConfig")                -> RuntimeCall("cookieConfig"),
  QualifiedName("useSessionStore")             -> RuntimeCall("useSessionStore"),
  QualifiedName("jwtSign")                     -> RuntimeCall("jwtSign"),
  QualifiedName("jwtVerify")                   -> RuntimeCall("jwtVerify"),
  QualifiedName("jwtSignRsa")                  -> RuntimeCall("jwtSignRsa"),
  QualifiedName("jwtVerifyRsa")                -> RuntimeCall("jwtVerifyRsa"),
  QualifiedName("oauthAuthorizeUrl")           -> RuntimeCall("oauthAuthorizeUrl"),
  QualifiedName("oauthExchangeCode")           -> RuntimeCall("oauthExchangeCode"),
  QualifiedName("oauthUserinfo")               -> RuntimeCall("oauthUserinfo"),
  QualifiedName("oauthRefreshToken")           -> RuntimeCall("oauthRefreshToken"),
  QualifiedName("oauthRegisterProvider")       -> RuntimeCall("oauthRegisterProvider"),
)
