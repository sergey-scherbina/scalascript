package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Auth intrinsics for the JS (Node.js) backend (Stage 5+/D). */
val JsAuthIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("Response.basicAuthChallenge") -> RuntimeCall("Response.basicAuthChallenge"),
  QualifiedName("csrfToken")                   -> RuntimeCall("csrfToken"),
  QualifiedName("csrfValid")                   -> RuntimeCall("csrfValid"),
  QualifiedName("base64UrlEncode")             -> RuntimeCall("base64UrlEncode"),
  QualifiedName("base64UrlDecode")             -> RuntimeCall("base64UrlDecode"),
  // → the standalone Node verifier in JsRuntimeWebAuthn (emitted under Capability.WebAuthn).
  // Non-identity targets so the std/auth extern namespace member falls back to the real
  // `_webauthn*` impl under Node (host `_ssc_ui_webauthn*` still wins in the browser).
  QualifiedName("webauthnConfigureStore")      -> RuntimeCall("_webauthnConfigureStore"),
  QualifiedName("webauthnChallenge")           -> RuntimeCall("_webauthnChallenge"),
  QualifiedName("webauthnConsumeChallenge")    -> RuntimeCall("_webauthnConsumeChallenge"),
  QualifiedName("webauthnStorePut")            -> RuntimeCall("_webauthnStorePut"),
  QualifiedName("webauthnStoreGet")            -> RuntimeCall("_webauthnStoreGet"),
  QualifiedName("webauthnStoreFind")           -> RuntimeCall("_webauthnStoreFind"),
  QualifiedName("webauthnStoreRemove")         -> RuntimeCall("_webauthnStoreRemove"),
  QualifiedName("webauthnUpdateSignCount")     -> RuntimeCall("_webauthnUpdateSignCount"),
  QualifiedName("webauthnVerifyAssertion")     -> RuntimeCall("_webauthnVerifyAssertion"),
  QualifiedName("webauthnVerifyRegistration")  -> RuntimeCall("_webauthnVerifyRegistration"),
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
