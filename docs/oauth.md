# OAuth 2.1 + OIDC in ScalaScript

ScalaScript v1.17 ships a complete, self-contained OAuth 2.1
Authorization Server and OpenID Connect Identity Provider in the
`scalascript.oauth.*` and `scalascript.oidc.*` packages — fully
decoupled from MCP, reusable by any HTTP service.

This document covers:

  - [The big picture](#the-big-picture)
  - [Authorization Server (AS)](#authorization-server-as)
  - [Resource Server (RS) with `oauth.guard`](#resource-server-rs-with-oauthguard)
  - [OpenID Connect (OIDC)](#openid-connect-oidc)
  - [Production hardening — RSA + JWKS](#production-hardening--rsa--jwks)
  - [MCP integration](#mcp-integration)
  - [Architecture map](#architecture-map)
  - [Spec compliance](#spec-compliance)

## The big picture

OAuth divides responsibilities across three roles:

  - **Authorization Server (AS)** — issues tokens.  Knows clients,
    users (indirectly, via your UI), grants.
  - **Resource Server (RS)** — serves the actual API.  Accepts bearer
    tokens, decides whether to honour each request.
  - **Client** — third-party app that asks the user "may I act on
    your behalf?" and presents the resulting token to the RS.

The packages map cleanly to these roles:

  - `scalascript.oauth.AuthServer`        — the AS
  - `scalascript.oauth.OAuthGuard`        — the RS SDK
  - `scalascript.oauth.OAuthRoutes`       — pure HTTP handlers (any
                                            framework)
  - `scalascript.oidc.OidcServer`         — composes AS with an
                                            OIDC IdP layer
  - `scalascript.interpreter.intrinsics.OAuthHttp`
  - `scalascript.interpreter.intrinsics.OidcHttp`
                                           — adapters that install
                                             all routes on the
                                             ScalaScript WebServer

All three can be wired separately or together in a single process.

## Authorization Server (AS)

### From a `.ssc` script

```ssc
val as = oauth.authServer(Map(
  "issuer"        -> "https://auth.example.com",
  "signingSecret" -> System.getenv("JWT_SECRET"),
  "scopes"        -> List("read", "write")
))

// Pre-register clients (or rely on Dynamic Client Registration —
// the AS publishes the /register endpoint by default).
val client = as.registerClient(Map(
  "redirect_uris" -> List("https://app.example.com/callback"),
  "grant_types"   -> List("authorization_code", "refresh_token"),
  "scope"         -> "read write"
))
println(client("client_id"), client("client_secret"))

// Install every OAuth endpoint at once.
oauth.serveAuthServer(as)

serve(8080)
```

Endpoints registered by `oauth.serveAuthServer`:

| Method | Path                                              | RFC      |
|--------|---------------------------------------------------|----------|
| POST   | `/token`                                          | 6749 §3.2 |
| POST   | `/introspect`                                     | 7662     |
| POST   | `/revoke`                                         | 7009     |
| POST   | `/register`                                       | 7591     |
| GET    | `/authorize`                                      | 6749 §3.1 |
| GET    | `/.well-known/oauth-authorization-server`         | 8414     |
| GET    | `/.well-known/jwks.json`                          | 7517     |

### Grant flows supported

- **`authorization_code`** with mandatory PKCE (OAuth 2.1 default).
  The AS records the user's subject when you call
  `as.issueAuthorizationCode(req, subject)`; clients then redeem
  the code at `/token`.
- **`refresh_token`** with single-use rotation per OAuth 2.1 §6.1.
  Narrower scope on refresh allowed; wider scopes rejected.
- **`client_credentials`** for machine-to-machine.  Requires a
  confidential client.

The implicit and password grants are **not** supported — OAuth 2.1
forbids them.

### From Scala

If your service is JVM-only and you want full control:

```scala
import scalascript.oauth.*

val as = new AuthServer(AuthServerConfig(
  issuer        = "https://auth.example.com",
  signingSecret = sys.env("JWT_SECRET"),
  supportedScopes = Set("read", "write")
))

// Wire your own ClientStore / TokenStore for persistence
val customStore = new MyDbClientStore
val as2 = new AuthServer(cfg, clients = customStore)
```

All AS internals — code generation, PKCE verification, token rotation
— are pure decision functions over the supplied stores.

## Resource Server (RS) with `oauth.guard`

Any HTTP service can require OAuth bearer tokens on its endpoints in
one line:

```ssc
val readOnly = oauth.guard(as, List("read"))

route("GET", "/api/data", readOnly { (req, claims) =>
  Response.json(Map(
    "data"    -> myData(),
    "viewedBy" -> claims.subject
  ))
})
```

`oauth.guard` returns a curried wrapper.  Inside, the handler signature
gains a second `claims` parameter — the decoded `AuthClaims` for the
validated bearer token (subject + scopes + extra JWT claims).

Failure modes the guard handles:

  - Missing/malformed `Authorization` header → **401 invalid_request**
  - Expired/tampered/revoked token           → **401 invalid_token**
  - Token scopes don't cover required        → **403 insufficient_scope**
    (response includes the required scopes in `WWW-Authenticate` so
     the client knows what to request)

For non-AS validators (e.g. external JWKS-backed JWT signers),
use `oauth.guardWithValidator(validatorFn, scopes?)`:

```ssc
val externalValidator = oauth.hmacValidator("shared-with-external-as")
val guarded = oauth.guardWithValidator(externalValidator, List("read"))

route("GET", "/api/external", guarded { (req, claims) =>
  Response.json(Map("subject" -> claims.subject))
})
```

## OpenID Connect (OIDC)

OIDC is a thin identity layer on top of OAuth.  When the granted scope
includes `openid`, the token response carries an `id_token` (signed
JWT with the user's identity claims).  Clients also gain access to
`/userinfo` for fresh claim lookups.

```ssc
val as  = oauth.authServer(Map(
  "issuer"        -> "https://idp.example.com",
  "signingSecret" -> System.getenv("JWT_SECRET"),
  "scopes"        -> List("openid", "profile", "email")
))
val idp = oidc.server(as)

// Populate your user store
idp.addUser(Map(
  "subject"       -> "alice",
  "name"          -> "Alice Anderson",
  "email"         -> "alice@example.com",
  "emailVerified" -> true,
  "picture"       -> "https://example.com/alice.png"
))

oidc.serve(idp)
serve(8080)
```

`oidc.serve` registers all OAuth endpoints *plus*:

| Method | Path                                          | Spec  |
|--------|-----------------------------------------------|-------|
| GET    | `/userinfo`                                   | OIDC §5.3 |
| POST   | `/userinfo`                                   | OIDC §5.3 |
| GET    | `/.well-known/openid-configuration`           | OIDC Discovery |

Scope-driven claim disclosure on `id_token` + `/userinfo`:

  - `openid`   → `sub` (always)
  - `profile`  → `name`, `picture`, `locale`, `preferred_username`
  - `email`    → `email`, `email_verified`
  - `extra` claims (custom roles/groups) are emitted unconditionally

## Production hardening — RSA + JWKS

Default deployments use HS256 (HMAC) — AS and RS share a symmetric
secret.  Fine for single-process services and tests.

For multi-service production, switch to RS256 (RSA-SHA256):

  - AS holds a private key, signs tokens
  - AS publishes the **public** key at `/.well-known/jwks.json`
  - Resource servers fetch the public key, validate locally
  - Compromising one RS doesn't compromise the AS

### From a script

Pass `signer: "RS256"` in the config; a fresh 2048-bit RSA key pair
is generated automatically.  `signingKid` (optional) controls the
JWT header `kid` + the JWKS entry id.

```ssc
val as = oauth.authServer(Map(
  "issuer"        -> "https://auth.example.com",
  "signingSecret" -> "unused-with-rsa-but-config-shape-requires-it",
  "scopes"        -> List("read", "write"),
  "signer"        -> "RS256",
  "signingKid"    -> "prod-key-1"
))
oauth.serveAuthServer(as)
```

Discovery + JWKS metadata automatically pick up:

```
GET /.well-known/oauth-authorization-server
  →  ..., "token_endpoint_auth_signing_alg_values_supported": ["RS256"],
     "jwks_uri": "https://auth.example.com/.well-known/jwks.json"

GET /.well-known/jwks.json
  →  {"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"prod-key-1","n":"…","e":"AQAB"}]}
```

### From Scala

If you need to bring your own key pair (e.g. loaded from PEM or
a KMS), drop down to the Scala constructor:

```scala
import scalascript.oauth.*

val signer = OAuth.RsaTokenSigner.generate("prod-key-1")
// or: new RsaTokenSigner(privateKey, publicKey, Some("prod-key-1"))

val as = new AuthServer(
  AuthServerConfig(
    issuer        = "https://auth.example.com",
    signingSecret = "unused-when-rsa-signer-set",
    supportedScopes = Set("read", "write")
  ),
  customSigner = Some(signer)
)
```

The OIDC `id_token` is automatically signed with the same RSA key when
the AS uses an asymmetric signer.

## MCP integration

MCP servers are Resource Servers — they accept bearer tokens to gate
tool/resource invocations.  Two integration paths:

### Same process — AS embedded in the MCP server

```ssc
val as = oauth.authServer(Map(
  "issuer"        -> "http://localhost:8080",
  "signingSecret" -> System.getenv("JWT_SECRET"),
  "scopes"        -> List("mcp:read", "mcp:invoke")
))
oauth.serveAuthServer(as)  // /token, /introspect, …

mcpServer { srv =>
  srv.useAuthServer(as)    // every /mcp request now needs a valid bearer

  srv.tool("ping", "Ping the server") { _ => Tool.text("pong") }
}

serveMcp(Transport.Http(8080, "/mcp"))
```

The MCP server automatically publishes
`/.well-known/oauth-protected-resource` (RFC 9728) pointing clients at
the AS via the `authorization_servers` field.

### Separate AS — MCP server validates externally-issued tokens

If your AS lives elsewhere, give the MCP server a validator that knows
its signing secret:

```ssc
mcpServer { srv =>
  srv.useHmacValidator(System.getenv("EXTERNAL_AS_SECRET"))
}
```

Or wire a custom validator that fetches keys via JWKS (today: Scala
side; script intrinsic queued).

## Architecture map

```
                  scalascript.oauth                  scalascript.oidc
        ┌───────────────────────────────────┐   ┌──────────────────┐
        │  OAuth.* — primitives             │   │  OidcServer       │
        │   - AuthClaims / AuthResult       │   │   - composes AS   │
        │   - TokenSigner trait             │   │   - id_token mint │
        │     HmacTokenSigner (HS256)       │   │   - userInfoFor   │
        │     RsaTokenSigner (RS256)        │   │   - discoveryJson │
        │   - extractBearer / wwwAuthenticate│  │  OidcRoutes       │
        │   - PKCE helpers                  │   │   - handleToken   │
        │  AuthServer                       │   │   - handleUserInfo│
        │   - issueAuthorizationCode        │   │   - handleDiscovery│
        │   - issueToken (3 grants)         │   │  UserClaims       │
        │   - introspect / revokeToken      │   │  UserInfoStore    │
        │   - tokenValidator: TokenValidator│   └──────────────────┘
        │   - registerClient (RFC 7591)     │
        │   - metadataJson (RFC 8414)       │
        │   - jwksJson (RFC 7517)           │            ▲
        │  OAuthRoutes — pure HTTP handlers │            │
        │   - handleToken / handleIntrospect│            │
        │   - handleRevoke / handleRegister │            │
        │   - handleAuthorize / handleMetadata          │
        │   - handleJwks                    │            │
        │  OAuthGuard — RS SDK              │            │
        │   - check(...) → GuardDecision    │            │
        │   - allows(...) boolean shortcut  │            │
        └───────────────────────────────────┘            │
                            ▲                            │
                            │                            │
                            └────────────┬───────────────┘
                                         │
              scalascript.interpreter.intrinsics
        ┌───────────────────────────────────┐
        │  OAuthHttp.installRoutes(...)     │
        │  OidcHttp.installRoutes(...)      │   ←── script entry points
        │  OAuth.scala (intrinsics)         │       (oauth.authServer,
        │   - oauth.* QualifiedNames        │        oidc.server, …)
        │  Oidc.scala (helpers)             │
        └───────────────────────────────────┘
                            ▲
                            │
                  scalascript.mcp.McpServerCore
        ┌───────────────────────────────────┐
        │  McpServerBuilder                 │
        │   - setTokenValidator(...)        │
        │   - useAuthServer(as)             │   ←── one-call MCP↔OAuth
        │   - setProtectedResourceMetadata  │
        │   - currentAuth: Option[AuthClaims]│
        │  McpAuth — re-export shim         │
        │   over scalascript.oauth.OAuth    │
        └───────────────────────────────────┘
```

## Spec compliance

| Spec                              | Status |
|-----------------------------------|--------|
| RFC 6749 OAuth 2.0 framework      | ✓ (OAuth 2.1 subset) |
| OAuth 2.1 draft                   | ✓ (PKCE mandatory, no implicit, no password grant) |
| RFC 6750 Bearer Token Usage       | ✓ (extractBearer, WWW-Authenticate) |
| RFC 7009 Token Revocation         | ✓ (/revoke endpoint, access + refresh) |
| RFC 7517 JSON Web Key             | ✓ (rsaPublicJwk, jwksDocument) |
| RFC 7518 JOSE algorithms          | ✓ (HS256, RS256) |
| RFC 7591 Dynamic Client Registration | ✓ (/register endpoint) |
| RFC 7636 PKCE                     | ✓ (S256 + plain, RFC 7636 test vector verified) |
| RFC 7662 Token Introspection      | ✓ (/introspect endpoint) |
| RFC 8414 AS Metadata              | ✓ (/.well-known/oauth-authorization-server) |
| RFC 9728 Protected Resource Metadata | ✓ (/.well-known/oauth-protected-resource on RS side) |
| OpenID Connect Core 1.0           | ✓ (id_token, /userinfo, scope filtering) |
| OpenID Connect Discovery 1.0      | ✓ (/.well-known/openid-configuration) |

Test coverage: see `OAuthAuthServerTest`, `OAuthRoutesTest`,
`OAuthRevocationTest`, `OAuthHttpInstallerTest`, `OAuthScriptTest`,
`OAuthRsaJwksTest`, `OAuthGuardTest`, `OAuthGuardScriptTest`,
`OidcServerTest`, `OidcScriptTest`, `McpOAuthBridgeTest` —
**295 tests** total across the OAuth/OIDC/MCP suites as of v1.17.x.
