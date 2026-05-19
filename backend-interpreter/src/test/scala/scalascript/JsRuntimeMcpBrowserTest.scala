package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JsRuntimeMcpBrowser

/** v1.17 Phase 3 — sanity tests on the browser MCP client preamble.
 *
 *  Can't run JS in this suite directly, so we assert:
 *  - The preamble defines the expected user-facing symbols.
 *  - It does NOT pull in Node-only APIs (require / worker_threads /
 *    SharedArrayBuffer / Atomics) — those would crash in a browser.
 *  - mcpServer / serveMcp / unsupported transports raise an actionable
 *    error message (substring check on the preamble source).
 *
 *  An end-to-end test would need a headless browser harness
 *  (Playwright / Puppeteer) — deferred to a future CI iteration. */
class JsRuntimeMcpBrowserTest extends AnyFunSuite with Matchers:

  test("preamble defines the user-facing client surface"):
    JsRuntimeMcpBrowser should include ("function mcpConnect(")
    JsRuntimeMcpBrowser should include ("listTools:")
    JsRuntimeMcpBrowser should include ("listResources:")
    JsRuntimeMcpBrowser should include ("listPrompts:")
    JsRuntimeMcpBrowser should include ("callTool:")
    JsRuntimeMcpBrowser should include ("readResource:")
    JsRuntimeMcpBrowser should include ("getPrompt:")
    JsRuntimeMcpBrowser should include ("close:")
    JsRuntimeMcpBrowser should include ("isClosed:")

  test("preamble has no Node-only API references"):
    // require() — Node module loader; throws ReferenceError in browser
    JsRuntimeMcpBrowser should not include ("require(")
    // worker_threads — Node-only API
    JsRuntimeMcpBrowser should not include ("worker_threads")
    // SharedArrayBuffer / Atomics — work in browsers only with COOP/COEP
    // headers; we deliberately avoid them in this variant.
    JsRuntimeMcpBrowser should not include ("SharedArrayBuffer")
    JsRuntimeMcpBrowser should not include ("Atomics.wait")

  test("preamble uses synchronous XHR for the request/response transport"):
    JsRuntimeMcpBrowser should include ("XMLHttpRequest")
    JsRuntimeMcpBrowser should include ("xhr.open('POST', url, false)")

  test("mcpServer / serveMcp raise actionable not-supported errors"):
    // The strings the user sees should hint at the right alternative.
    JsRuntimeMcpBrowser should (include ("mcpServer:") and include ("not available in scalajs-spa") and include ("browser cannot host"))
    JsRuntimeMcpBrowser should (include ("serveMcp:")  and include ("not available in scalajs-spa") and include ("browser cannot host"))

  test("mcpConnect rejects non-Http transports with a clear message"):
    JsRuntimeMcpBrowser should include ("scalajs-spa only supports Transport.Http")

  test("handshake — preamble sends initialize before returning"):
    // Whitespace-tolerant: just look for the method name + the protocol
    // version constant.
    JsRuntimeMcpBrowser should include ("'initialize'")
    JsRuntimeMcpBrowser should include ("'2024-11-05'")
    JsRuntimeMcpBrowser should include ("'notifications/initialized'")

  // ── Async / Promise variant ─────────────────────────────────────────

  test("async variant — mcpConnectAsync uses fetch + returns Promise-based client"):
    JsRuntimeMcpBrowser should include ("async function mcpConnectAsync")
    JsRuntimeMcpBrowser should include ("await fetch(")
    JsRuntimeMcpBrowser should include ("McpClientAsync")

  test("async variant — every client method is async"):
    JsRuntimeMcpBrowser should include ("listTools:     async ()")
    JsRuntimeMcpBrowser should include ("listResources: async ()")
    JsRuntimeMcpBrowser should include ("listPrompts:   async ()")
    JsRuntimeMcpBrowser should include ("callTool: async (name, args)")
    JsRuntimeMcpBrowser should include ("readResource: async (uri)")
    JsRuntimeMcpBrowser should include ("getPrompt: async (name, args)")

  test("async variant — supports request abort via AbortController on timeout"):
    JsRuntimeMcpBrowser should include ("AbortController")
    JsRuntimeMcpBrowser should include ("ctrl.abort()")

  test("async variant — sync and async share the wire-shape adapters"):
    // _mcpToolResultB / _mcpDescriptorsToListB / etc. are defined once and
    // used by both mcpConnect (sync) and mcpConnectAsync.
    JsRuntimeMcpBrowser should include ("_mcpToolResultB(await")
    JsRuntimeMcpBrowser should include ("_mcpDescriptorsToListB(await")
