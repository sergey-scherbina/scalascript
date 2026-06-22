package scalascript.codegen

/** v1.17 Phase 3 — browser-compatible MCP client preamble.
 *
 *  Unlike `JsRuntimeMcp` (which uses Node's `worker_threads` +
 *  `@modelcontextprotocol/sdk`), this variant runs in any browser
 *  with **no dependencies**: HTTP transport via synchronous
 *  `XMLHttpRequest`, no Workers, no SDK.
 *
 *  Scope:
 *    - **Client only.** `mcpServer { ... }` / `serveMcp(...)` raise an
 *      actionable error — a browser can't be an MCP server.
 *    - **`Transport.Http(port, path)` only.**  Stdio / Spawn / Ws are
 *      nonsensical in a browser sandbox.
 *    - **Synchronous API** via sync XHR — matches `std/mcp/client.ssc`'s
 *      `def callTool(name, args): ToolResult` signature.  Sync XHR is
 *      deprecated by browsers (blocks the main thread) but works
 *      everywhere with zero setup; an async-Promise variant can ship
 *      later as `mcpConnectAsync` once a real consumer asks.
 *
 *  Selected by the CLI's `spa` command (alongside `JsRuntimeBrowserPatch`)
 *  in place of the Node-flavoured `JsRuntimeMcp`.  Detection of MCP
 *  usage happens upstream: if a user `scalascript` block calls
 *  `mcpConnect(...)`, the SPA HTML output includes this preamble. */
val JsRuntimeMcpBrowser: String = JsRuntimeResource.load("mcpbrowser.mjs")
