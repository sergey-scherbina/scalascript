package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

class JsGenTypedRouteClientTest extends AnyFunSuite:

  private val source =
    """---
      |apiClients:
      |  Messages:
      |    endpoints:
      |      - name: create
      |        method: POST
      |        path: /api/messages
      |        request: CreateMessage
      |        response: Message
      |      - name: list
      |        method: GET
      |        path: /api/messages
      |        request: Unit
      |        response: List[Message]
      |      - name: get
      |        method: GET
      |        path: /api/messages/:id
      |        request: Int
      |        response: Message
      |---
      |
      |# Test
      |
      |```scalascript
      |case class CreateMessage(text: String)
      |case class Message(id: Int, text: String)
      |```
      |""".stripMargin

  test("JS codegen emits auth header helpers alongside typed route clients"):
    val code = JsGen.generate(Parser.parse(source))

    assert(code.contains("let _ssc_api_extra_headers = {};"))
    assert(code.contains("function _ssc_api_set_headers(headers)"))
    assert(code.contains("function _ssc_set_auth_token(token)"))
    assert(code.contains("headers: Object.assign({}, _ssc_api_extra_headers, callHeaders || {})"))
    assert(code.contains("let _ssc_api_retry_policy = {maxRetries: 0, delayMs: 0};"))
    assert(code.contains("function _ssc_api_set_retry(maxRetries, delayMs)"))
    assert(code.contains("attempt < maxRetries"))
    assert(code.contains("function _ssc_api_cancel_token()"))
    assert(code.contains("cancelToken.cancelled"))
    assert(code.contains("typed route client: request cancelled"))

  test("JS typed route client request merges extra headers from _ssc_api_extra_headers"):
    assume(hasNode, "node not available")
    val code = JsGen.generate(Parser.parse(source))
    val harness =
      """
        |const capturedHeaders = [];
        |globalThis.fetch = async function(url, init) {
        |  capturedHeaders.push(Object.assign({}, init.headers));
        |  return {
        |    ok: true, status: 200,
        |    headers: { get: function() { return "application/json"; } },
        |    text: async function() { return "[]"; }
        |  };
        |};
        |(async function() {
        |  await Messages.list();
        |  _ssc_set_auth_token("tok123");
        |  await Messages.list();
        |  _ssc_api_set_headers({"X-Custom": "val", "Authorization": "Basic abc"});
        |  await Messages.list();
        |  _ssc_set_auth_token(null);
        |  await Messages.list();
        |  process.stdout.write([
        |    capturedHeaders[0]['Authorization'] || "none",
        |    capturedHeaders[1]['Authorization'] || "none",
        |    capturedHeaders[2]['Authorization'] || "none",
        |    capturedHeaders[3]['Authorization'] || "none",
        |    capturedHeaders[2]['X-Custom'] || "none",
        |  ].join(":"));
        |})().catch(e => { process.stdout.write("ERR:" + e); process.exitCode = 1; });
        |""".stripMargin
    assert(runJs(code + "\n" + harness) == "none:Bearer tok123:Basic abc:none:val")

  test("JS typed route client per-call headers override module headers"):
    assume(hasNode, "node not available")
    val code = JsGen.generate(Parser.parse(source))
    val harness =
      """
        |const capturedHeaders = [];
        |globalThis.fetch = async function(url, init) {
        |  capturedHeaders.push(Object.assign({}, init.headers));
        |  return {
        |    ok: true, status: 200,
        |    headers: { get: function() { return "application/json"; } },
        |    text: async function() { return "[]"; }
        |  };
        |};
        |(async function() {
        |  _ssc_set_auth_token("global-tok");
        |  await Messages.list();
        |  await Messages.list({"Authorization": "Bearer per-call", "X-Req": "yes"});
        |  await Messages.list();
        |  process.stdout.write([
        |    capturedHeaders[0]['Authorization'] || "none",
        |    capturedHeaders[1]['Authorization'] || "none",
        |    capturedHeaders[1]['X-Req'] || "none",
        |    capturedHeaders[2]['Authorization'] || "none",
        |  ].join(":"));
        |})().catch(e => { process.stdout.write("ERR:" + e); process.exitCode = 1; });
        |""".stripMargin
    assert(runJs(code + "\n" + harness) == "Bearer global-tok:Bearer per-call:yes:Bearer global-tok")

  test("JS typed route client retries on 5xx and succeeds on eventual 2xx"):
    assume(hasNode, "node not available")
    val code = JsGen.generate(Parser.parse(source))
    val harness =
      """
        |let callCount = 0;
        |globalThis.fetch = async function(url, init) {
        |  callCount++;
        |  if (callCount <= 2) {
        |    return { ok: false, status: 503, headers: { get: () => "" }, text: async () => "unavailable" };
        |  }
        |  return {
        |    ok: true, status: 200,
        |    headers: { get: () => "application/json" },
        |    text: async () => "[]"
        |  };
        |};
        |(async function() {
        |  _ssc_api_set_retry(3, 0);
        |  await Messages.list();
        |  process.stdout.write("calls=" + callCount);
        |})().catch(e => { process.stdout.write("ERR:" + e); process.exitCode = 1; });
        |""".stripMargin
    assert(runJs(code + "\n" + harness) == "calls=3")

  test("JS typed route client retries on network error"):
    assume(hasNode, "node not available")
    val code = JsGen.generate(Parser.parse(source))
    val harness =
      """
        |let callCount = 0;
        |globalThis.fetch = async function() {
        |  callCount++;
        |  if (callCount <= 1) throw new Error("network failure");
        |  return {
        |    ok: true, status: 200,
        |    headers: { get: () => "application/json" },
        |    text: async () => "[]"
        |  };
        |};
        |(async function() {
        |  _ssc_api_set_retry(2, 0);
        |  await Messages.list();
        |  process.stdout.write("calls=" + callCount);
        |})().catch(e => { process.stdout.write("ERR:" + e); process.exitCode = 1; });
        |""".stripMargin
    assert(runJs(code + "\n" + harness) == "calls=2")

  test("JS typed route client does not retry 4xx errors"):
    assume(hasNode, "node not available")
    val code = JsGen.generate(Parser.parse(source))
    val harness =
      """
        |let callCount = 0;
        |globalThis.fetch = async function() {
        |  callCount++;
        |  return { ok: false, status: 404, headers: { get: () => "" }, text: async () => "not found" };
        |};
        |(async function() {
        |  _ssc_api_set_retry(3, 0);
        |  try { await Messages.list(); } catch (e) { process.stdout.write("err:calls=" + callCount); }
        |})();
        |""".stripMargin
    assert(runJs(code + "\n" + harness) == "err:calls=1")

  test("JS typed route client pre-cancelled token throws before calling fetch"):
    assume(hasNode, "node not available")
    val code = JsGen.generate(Parser.parse(source))
    val harness =
      """
        |let fetchCalled = false;
        |globalThis.fetch = async function() { fetchCalled = true; return {ok:true,status:200,headers:{get:()=>"application/json"},text:async()=>"[]"}; };
        |(async function() {
        |  const token = _ssc_api_cancel_token();
        |  token.cancel();
        |  try {
        |    await Messages.list(undefined, token);
        |    process.stdout.write("no-error");
        |  } catch(e) {
        |    process.stdout.write(e.message.includes("request cancelled") ? "cancelled:fetch=" + fetchCalled : "wrong-error:" + e.message);
        |  }
        |})();
        |""".stripMargin
    assert(runJs(code + "\n" + harness) == "cancelled:fetch=false")

  test("JS typed route client active token does not block uncancelled calls"):
    assume(hasNode, "node not available")
    val code = JsGen.generate(Parser.parse(source))
    val harness =
      """
        |globalThis.fetch = async function() { return {ok:true,status:200,headers:{get:()=>"application/json"},text:async()=>"[]"}; };
        |(async function() {
        |  const token = _ssc_api_cancel_token();
        |  await Messages.list(undefined, token);
        |  process.stdout.write("ok");
        |})().catch(e => { process.stdout.write("ERR:" + e); process.exitCode = 1; });
        |""".stripMargin
    assert(runJs(code + "\n" + harness) == "ok")

  test("JS codegen emits HTTP typed route client metadata and Promise methods"):
    val code = JsGen.generate(Parser.parse(source))

    assert(code.contains("const _ssc_typedRouteClients = ["))
    assert(code.contains("""{client: "Messages", name: "create", method: "POST", path: "/api/messages", requestType: "CreateMessage", responseType: "Message"}"""))
    assert(code.contains("async function _ssc_api_request(methodRaw, pathTemplate, input, requestType, responseType, callHeaders, cancelToken)"))
    assert(code.contains("function _ssc_api_cancel_token()"))
    assert(code.contains("function _ssc_typed_json_encode(value, typeName)"))
    assert(code.contains("function _ssc_typed_json_decode_response(text, contentType, typeName)"))
    assert(code.contains("return _ssc_typed_json_encode(input, requestType);"))
    assert(code.contains("response = await fetch(url, init);"))
    assert(code.contains("return _ssc_typed_json_decode_response(text, contentType, responseType);"))
    assert(code.contains("const Messages = {"))
    assert(code.contains("""create(input, headers, cancelToken) { return _ssc_api_request("POST", "/api/messages", input, "CreateMessage", "Message", headers, cancelToken); }"""))
    assert(code.contains("""list(headers, cancelToken) { return _ssc_api_request("GET", "/api/messages", undefined, "Unit", "List[Message]", headers, cancelToken); }"""))
    assert(code.contains("""get(input, headers, cancelToken) { return _ssc_api_request("GET", "/api/messages/:id", input, "Int", "Message", headers, cancelToken); }"""))
    assert(code.contains("""_ssc_typed_json_register_product("CreateMessage", ["text"], CreateMessage)"""))
    assert(code.contains("""_ssc_typed_json_register_product("Message", ["id", "text"], Message)"""))

  test("JS typed route runtime builds path params and GET query strings"):
    val code = JsGen.generate(Parser.parse(source))

    assert(code.contains("function _ssc_api_path(pathTemplate, input)"))
    assert(code.contains("const primitiveForSingleParam ="))
    assert(code.contains("throw new Error(\"typed route client: missing path field '\" + name + \"'\");"))
    assert(code.contains("function _ssc_api_query(pathTemplate, input)"))
    assert(code.contains("const url = _ssc_api_path(pathTemplate, input) + (method === \"GET\" ? _ssc_api_query(pathTemplate, input) : \"\");"))

  test("segmented JS output includes HTTP typed route clients before user code"):
    val segments = JsGen.generateSegmented(Parser.parse(source))
    val js = segments.collect { case JsGen.Segment.ScalaScriptJs(code) => code }.mkString("\n")

    assert(js.contains("const _ssc_typedRouteClients = ["))
    assert(js.contains("const Messages = {"))
    assert(js.indexOf("const Messages = {") < js.indexOf("function CreateMessage("))

  test("awaitClient lowers typed client Promises through an async wrapper"):
    val src =
      source +
        """
          |
          |```scalascript
          |val rows = awaitClient(Messages.list())
          |println("rows=" + rows.size.toString)
          |```
          |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(code.contains("(async () => {"))
    assert(code.contains("""const rows = await _dispatch(Messages, 'list', []);"""))
    assert(code.contains("document.body.textContent = msg"))

  test("segmented output wraps awaitClient and skips server-only ScalaScript blocks"):
    val src =
      source +
        """
          |
          |```scalascript @side=server
          |val serverOnly = awaitClient(Messages.list())
          |```
          |
          |```scalascript @side=client
          |val clientRows = awaitClient(Messages.list())
          |```
          |""".stripMargin

    val segments = JsGen.generateSegmented(Parser.parse(src))
    val js = segments.collect { case JsGen.Segment.ScalaScriptJs(code) => code }.mkString("\n")

    assert(js.contains("(async () => {"))
    assert(js.contains("""const clientRows = await _dispatch(Messages, 'list', []);"""))
    assert(!js.contains("serverOnly"))

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runJs(js: String): String =
    val tmp = java.io.File.createTempFile("ssc-js-typed-client-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val out = Source.fromInputStream(proc.getInputStream).mkString
    ProcTestUtil.awaitExit(proc)
    out.trim

  test("def with awaitClient in body emits async function"):
    val src =
      source +
        """
          |
          |```scalascript @side=client
          |def loadAll() = awaitClient(Messages.list())
          |val rows = awaitClient(loadAll())
          |```
          |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(code.contains("async function loadAll()"))
    // loadAll is a known zero-param def, so JsGen emits a direct call (no _call
    // wrapper) — see JsGen zeroParamFns/emptyParamFns lane.
    assert(code.contains("const rows = await loadAll();"))

  test("for-yield with multiple awaitClient generators lowers to async IIFE"):
    val src =
      source +
        """
          |
          |```scalascript @side=client
          |val combined = awaitClient(for {
          |  msgs <- awaitClient(Messages.list())
          |  drafts <- awaitClient(Messages.list())
          |} yield msgs ++ drafts)
          |```
          |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(code.contains("(async () => {"))
    assert(code.contains("await _dispatch(Messages, 'list',"))
    assert(!code.contains("'flatMap'"))

  test("for-do with multiple awaitClient generators lowers to async IIFE"):
    val src =
      source +
        """
          |
          |```scalascript @side=client
          |for {
          |  msgs <- awaitClient(Messages.list())
          |  drafts <- awaitClient(Messages.list())
          |} {
          |  println(msgs.size.toString + "+" + drafts.size.toString)
          |}
          |```
          |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(code.contains("(async () => {"))
    assert(code.contains("await _dispatch(Messages, 'list',"))
    assert(!code.contains("'forEach'"))

  test("path param validation: Unit request type with path param emits warning comment"):
    val src =
      """|---
         |apiClients:
         |  Items:
         |    endpoints:
         |      - name: get
         |        method: GET
         |        path: /api/items/:id
         |        request: Unit
         |        response: String
         |---
         |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))
    assert(code.contains("// [ssc warning] apiClient Items.get: path param ':id' cannot be filled — request type is Unit"))

  test("path param validation: matching case class fields produce no warning"):
    val src =
      """|---
         |apiClients:
         |  Items:
         |    endpoints:
         |      - name: get
         |        method: GET
         |        path: /api/items/:id
         |        request: ItemQuery
         |        response: String
         |---
         |
         |# Test
         |
         |```scalascript
         |case class ItemQuery(id: Int, category: String)
         |```
         |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))
    assert(!code.contains("// [ssc warning]"))

  test("path param validation: case class missing path param field emits warning"):
    val src =
      """|---
         |apiClients:
         |  Items:
         |    endpoints:
         |      - name: get
         |        method: GET
         |        path: /api/items/:id
         |        request: ItemQuery
         |        response: String
         |---
         |
         |# Test
         |
         |```scalascript
         |case class ItemQuery(name: String)
         |```
         |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))
    assert(code.contains("// [ssc warning] apiClient Items.get: path param ':id' not found in case class 'ItemQuery'"))

  test("path param validation: primitive with single path param produces no warning"):
    val src =
      """|---
         |apiClients:
         |  Items:
         |    endpoints:
         |      - name: get
         |        method: GET
         |        path: /api/items/:id
         |        request: Int
         |        response: String
         |---
         |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))
    assert(!code.contains("// [ssc warning]"))

  test("path param validation: primitive with multiple path params emits warning"):
    val src =
      """|---
         |apiClients:
         |  Items:
         |    endpoints:
         |      - name: get
         |        method: GET
         |        path: /api/items/:category/:id
         |        request: Int
         |        response: String
         |---
         |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))
    assert(code.contains("// [ssc warning] apiClient Items.get: 2 path params but request type 'Int' provides at most one value"))

  test("JS typed route clients encode and decode through generated codecs"):
    assume(hasNode, "node not available")
    val code = JsGen.generate(Parser.parse(source))
    val harness =
      """
        |globalThis.fetch = async function(url, init) {
        |  const responseBody =
        |    url === "/api/messages"
        |      ? (init.method === "GET" ? [{id: 1, text: "Ada"}] : {id: 2, text: JSON.parse(init.body).text})
        |      : {id: 3, text: "Grace"};
        |  return {
        |    ok: true,
        |    status: 200,
        |    headers: { get: function() { return "application/json"; } },
        |    text: async function() { return JSON.stringify(responseBody); }
        |  };
        |};
        |
        |(async function() {
        |  const created = await Messages.create(CreateMessage("hello"));
        |  const listed = await Messages.list();
        |  const one = await Messages.get(3);
        |  process.stdout.write(created._type + ":" + created.text + ":" + listed[0]._type + ":" + listed[0].text + ":" + one.id);
        |})().catch(function(e) {
        |  process.stdout.write("ERR:" + (e && e.stack ? e.stack : e));
        |  process.exitCode = 1;
        |});
        |""".stripMargin
    assert(runJs(code + "\n" + harness) == "Message:hello:Message:Ada:3")

  // ── SSE / streaming endpoints ─────────────────────────────────────

  private val sseSource =
    """---
      |apiClients:
      |  Events:
      |    endpoints:
      |      - name: subscribe
      |        method: GET
      |        path: /api/events/stream
      |        request: Unit
      |        response: Event
      |        stream: sse
      |      - name: watch
      |        method: POST
      |        path: /api/events/watch
      |        request: WatchRequest
      |        response: Event
      |        stream: sse
      |---
      |
      |# Test
      |
      |```scalascript
      |case class Event(id: Int, msg: String)
      |case class WatchRequest(topic: String)
      |```
      |""".stripMargin

  test("JS codegen emits _ssc_api_stream_request runtime function") {
    val code = JsGen.generate(Parser.parse(sseSource))
    assert(code.contains("function _ssc_api_stream_request("))
    assert(code.contains("EventSource"))
    assert(code.contains("ReadableStream"))
    assert(code.contains("text/event-stream"))
  }

  test("JS codegen emits SSE method for Unit-request endpoint without input arg") {
    val code = JsGen.generate(Parser.parse(sseSource))
    assert(code.contains("""subscribe(onEvent, onError, headers) { return _ssc_api_stream_request("GET", "/api/events/stream", undefined, onEvent, onError, "Event", headers); }"""))
  }

  test("JS codegen emits SSE method for non-Unit-request endpoint with input arg") {
    val code = JsGen.generate(Parser.parse(sseSource))
    assert(code.contains("""watch(input, onEvent, onError, headers) { return _ssc_api_stream_request("POST", "/api/events/watch", input, onEvent, onError, "Event", headers); }"""))
  }

  test("JS SSE runtime delivers events and close() stops the stream") {
    assume(hasNode, "node not available")
    val code = JsGen.generate(Parser.parse(sseSource))
    val harness =
      """
        |const {Readable} = require('stream');
        |
        |// Simulate an SSE endpoint: 3 events then close
        |const sseBody = 'data: {"_type":"Event","id":1,"msg":"hello"}\n\ndata: {"_type":"Event","id":2,"msg":"world"}\n\ndata: {"_type":"Event","id":3,"msg":"done"}\n\n';
        |
        |globalThis.fetch = async function(url, init) {
        |  const readable = Readable.from([Buffer.from(sseBody)]);
        |  readable.getReader = function() {
        |    let iter = readable[Symbol.asyncIterator]();
        |    return {
        |      read: async function() {
        |        const r = await iter.next();
        |        return r.done ? {done: true, value: undefined} : {done: false, value: r.value};
        |      },
        |      cancel: function() {}
        |    };
        |  };
        |  return {
        |    ok: true, status: 200,
        |    headers: { get: function(h) { return h.toLowerCase() === 'content-type' ? 'text/event-stream' : ''; } },
        |    body: { getReader: readable.getReader.bind(readable) }
        |  };
        |};
        |
        |// Force fetch path (no EventSource in Node)
        |if (typeof EventSource !== 'undefined') delete globalThis.EventSource;
        |
        |const received = [];
        |const handle = Events.subscribe(
        |  function(ev) { received.push(ev.id + ":" + ev.msg); },
        |  function(err) { process.stdout.write("ERR:" + err); process.exitCode = 1; }
        |);
        |
        |setTimeout(function() {
        |  process.stdout.write(received.join(","));
        |}, 200);
        |""".stripMargin
    val out = runJs(code + "\n" + harness)
    assert(out == "1:hello,2:world,3:done", s"Got: $out")
  }

  // ── WebSocket endpoints ───────────────────────────────────────────

  private val wsSource =
    """---
      |apiClients:
      |  Chat:
      |    endpoints:
      |      - name: connect
      |        method: GET
      |        path: /ws/chat
      |        request: Unit
      |        response: ChatMsg
      |        stream: ws
      |      - name: join
      |        method: GET
      |        path: /ws/rooms/:room
      |        request: JoinRequest
      |        response: ChatMsg
      |        stream: websocket
      |---
      |
      |# Test
      |
      |```scalascript
      |case class ChatMsg(user: String, text: String)
      |case class JoinRequest(room: String)
      |```
      |""".stripMargin

  test("JS codegen emits _ssc_api_ws_request runtime function") {
    val code = JsGen.generate(Parser.parse(wsSource))
    assert(code.contains("function _ssc_api_ws_request("))
    assert(code.contains("new WebSocket("))
    assert(code.contains("addEventListener('message'"))
    assert(code.contains("addEventListener('error'"))
  }

  test("JS codegen emits WS method for Unit-request endpoint without input arg") {
    val code = JsGen.generate(Parser.parse(wsSource))
    assert(code.contains("""connect(onEvent, onError, onOpen, headers) { return _ssc_api_ws_request("/ws/chat", undefined, onEvent, onError, onOpen, "ChatMsg", headers); }"""))
  }

  test("JS codegen emits WS method for typed-request endpoint with input arg") {
    val code = JsGen.generate(Parser.parse(wsSource))
    assert(code.contains("""join(input, onEvent, onError, onOpen, headers) { return _ssc_api_ws_request("/ws/rooms/:room", input, onEvent, onError, onOpen, "ChatMsg", headers); }"""))
  }

  test("JS codegen: stream: websocket is also recognised as WS") {
    val src =
      """|---
         |apiClients:
         |  Notifications:
         |    endpoints:
         |      - name: listen
         |        method: GET
         |        path: /ws/notify
         |        request: Unit
         |        response: String
         |        stream: websocket
         |---
         |""".stripMargin
    val code = JsGen.generate(Parser.parse(src))
    assert(code.contains("_ssc_api_ws_request"))
    assert(code.contains("""listen(onEvent, onError, onOpen, headers)"""))
  }

  test("JS WS runtime delivers messages via fake WebSocket") {
    assume(hasNode, "node not available")
    val code = JsGen.generate(Parser.parse(wsSource))
    val harness =
      """
        |const EventEmitter = require('events');
        |class FakeWS extends EventEmitter {
        |  constructor(url) {
        |    super();
        |    this.readyState = 1;
        |    this.url = url;
        |    process.nextTick(() => {
        |      this.emit('open');
        |      ['{"_type":"ChatMsg","user":"alice","text":"hi"}',
        |       '{"_type":"ChatMsg","user":"bob","text":"hey"}'].forEach(d => {
        |        this.emit('message', { data: d });
        |      });
        |      this.emit('close', { wasClean: true });
        |    });
        |  }
        |  send(msg) {}
        |  close() { this.readyState = 3; }
        |  addEventListener(ev, fn) { this.on(ev, fn); }
        |}
        |globalThis.WebSocket = FakeWS;
        |
        |const received = [];
        |Chat.connect(
        |  function(msg) { received.push(msg.user + ":" + msg.text); },
        |  function(err) { process.stdout.write("ERR:" + err); process.exitCode = 1; }
        |);
        |
        |setTimeout(function() {
        |  process.stdout.write(received.join(","));
        |}, 100);
        |""".stripMargin
    val out = runJs(code + "\n" + harness)
    assert(out == "alice:hi,bob:hey", s"Got: $out")
  }

  test("JS codegen emits Paged method for paginated Unit-request endpoint") {
    val src =
      """---
        |apiClients:
        |  Items:
        |    endpoints:
        |      - name: list
        |        method: GET
        |        path: /api/items
        |        request: Unit
        |        response: List[String]
        |        paginated: true
        |---
        |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(code.contains("list(headers, cancelToken)"))
    assert(code.contains("listPaged(page, size, headers, cancelToken)"))
    assert(code.contains(""""?page=" + page + "&size=" + size"""))
  }

  test("JS codegen emits Paged method for paginated typed-request endpoint") {
    val src =
      """---
        |apiClients:
        |  Items:
        |    endpoints:
        |      - name: search
        |        method: GET
        |        path: /api/items
        |        request: String
        |        response: List[String]
        |        paginated: true
        |---
        |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(code.contains("search(input, headers, cancelToken)"))
    assert(code.contains("searchPaged(input, page, size, headers, cancelToken)"))
    assert(code.contains(""""?page=" + page + "&size=" + size"""))
  }

  test("JS codegen does not emit Paged method for non-paginated endpoints") {
    val src =
      """---
        |apiClients:
        |  Items:
        |    endpoints:
        |      - name: list
        |        method: GET
        |        path: /api/items
        |        request: Unit
        |        response: List[String]
        |---
        |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(!code.contains("Paged"))
  }

  test("JS paginated client with multiple endpoints has correct comma placement") {
    val src =
      """---
        |apiClients:
        |  Items:
        |    endpoints:
        |      - name: list
        |        method: GET
        |        path: /api/items
        |        request: Unit
        |        response: List[String]
        |        paginated: true
        |      - name: get
        |        method: GET
        |        path: /api/items/:id
        |        request: Int
        |        response: String
        |---
        |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(code.contains("listPaged(page, size, headers, cancelToken)"))
    assert(code.contains("get(input, headers, cancelToken)"))
    assert(!code.contains("getPaged"))
  }
