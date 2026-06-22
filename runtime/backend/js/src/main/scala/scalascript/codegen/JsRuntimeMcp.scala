package scalascript.codegen

/** MCP (Model Context Protocol) JavaScript runtime preamble.
 *
 *  Wraps `@modelcontextprotocol/sdk` (npm) for both server and client.
 *  Server side: `mcpServer(setup)` + `serveMcp(transport)` — same shape
 *  as `route(method, path)(handler)` + `serve(port)`.
 *  Client side: `mcpConnect(transport)` returns a synchronous McpClient
 *  backed by a Worker thread + Atomics.wait bridge (same pattern as the
 *  OAuth sync-fetch bridge in JsRuntimeJwtAuth). */
val JsRuntimeMcp: String = JsRuntimeResource.load("mcp.mjs")
