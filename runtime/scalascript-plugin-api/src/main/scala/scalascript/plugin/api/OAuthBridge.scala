package scalascript.plugin.api

import java.util.concurrent.ConcurrentHashMap

/** Shared id → AuthServer registry between the oauth-plugin and Mcp intrinsics.
 *  Lives in the stable plugin-api (not `scalascript.interpreter`) so both plugins
 *  can reach it without importing interpreter internals.  Values are `Any`
 *  (an `AuthServer` handle) to avoid a compile-time dep on the oauth/mcp modules. */
object OAuthBridge:
  val authServers: ConcurrentHashMap[String, Any] = ConcurrentHashMap()
