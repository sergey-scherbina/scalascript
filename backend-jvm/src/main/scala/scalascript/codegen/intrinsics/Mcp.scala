package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** MCP server + client intrinsics for the JVM (Scala-CLI) backend.
 *
 *  All symbols are Scala def declarations emitted into the MCP runtime
 *  preamble that JvmGen prepends when `blocksUseMcp` fires.
 *  `RuntimeCall` entries satisfy `CapabilityCheck` so programs importing
 *  `std/mcp/server` or `std/mcp/client` are accepted by the JVM backend. */
val JvmMcpIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  // Server — setup + launcher
  QualifiedName("mcpServer")               -> RuntimeCall("mcpServer"),
  QualifiedName("serveMcp")                -> RuntimeCall("serveMcp"),
  // McpServer builder methods
  QualifiedName("McpServer.tool")          -> RuntimeCall("McpServer.tool"),
  QualifiedName("McpServer.resource")      -> RuntimeCall("McpServer.resource"),
  QualifiedName("McpServer.prompt")        -> RuntimeCall("McpServer.prompt"),
  QualifiedName("McpServer.onConnected")   -> RuntimeCall("McpServer.onConnected"),
  QualifiedName("McpServer.onDisconnected")-> RuntimeCall("McpServer.onDisconnected"),
  // Client — factory
  QualifiedName("mcpConnect")              -> RuntimeCall("mcpConnect"),
  // McpClient methods
  QualifiedName("McpClient.listTools")     -> RuntimeCall("McpClient.listTools"),
  QualifiedName("McpClient.listResources") -> RuntimeCall("McpClient.listResources"),
  QualifiedName("McpClient.listPrompts")   -> RuntimeCall("McpClient.listPrompts"),
  QualifiedName("McpClient.callTool")      -> RuntimeCall("McpClient.callTool"),
  QualifiedName("McpClient.readResource")  -> RuntimeCall("McpClient.readResource"),
  QualifiedName("McpClient.getPrompt")     -> RuntimeCall("McpClient.getPrompt"),
  QualifiedName("McpClient.close")         -> RuntimeCall("McpClient.close"),
  QualifiedName("McpClient.isClosed")      -> RuntimeCall("McpClient.isClosed"),
)
