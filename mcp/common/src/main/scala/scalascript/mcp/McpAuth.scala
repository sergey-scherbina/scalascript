package scalascript.mcp

/** v1.17.x — MCP authorization layer.  The MCP server plays the OAuth
 *  2.1 Resource Server role: bearer tokens on the HTTP transport are
 *  validated through a pluggable `TokenValidator` (see
 *  `McpServerCore.setTokenValidator`).
 *
 *  All the OAuth primitives — `AuthClaims`, `AuthResult`,
 *  `TokenValidator`, HMAC issuer/validator, RFC 6750 helpers,
 *  RFC 9728 metadata — live in `scalascript.oauth.OAuth` and are
 *  re-exported here for backward-compatible MCP-facing imports.
 *
 *  See `scalascript.oauth.AuthServer` for the Authorization Server
 *  (issuer) role; MCP integrates with an `AuthServer` instance via
 *  `srv.useAuthServer(...)`. */
object McpAuth:
  export scalascript.oauth.OAuth.{
    AuthClaims,
    AuthResult,
    TokenValidator,
    ProtectedResourceMetadata,
    extractBearer,
    wwwAuthenticate,
    issueHmacToken,
    hmacValidator,
    decodeHmacToken
  }
