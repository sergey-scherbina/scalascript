package scalascript.interpreter

import java.util.concurrent.ConcurrentHashMap

/** Shared id → AuthServer registry between the oauth-plugin and Mcp intrinsics.
 *  Using Any avoids a compile-time dep on mcpCommon from core. */
object OAuthBridge:
  val authServers: ConcurrentHashMap[String, Any] = ConcurrentHashMap()
