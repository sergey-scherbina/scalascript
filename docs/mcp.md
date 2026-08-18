# MCP — Model Context Protocol

Write an MCP server in ScalaScript and an LLM client can call your code as tools, read your data as
resources, and reuse your prompts.

> **Every example on this page was RUN, and the outputs below are pasted from the run.** That is not
> a style note. The MCP examples that used to live in `docs/tutorial.md` were written from member
> names and did not compile: `Message.user(...)` and `Transport.stdio` do not exist, and the errors
> are `No method 'user' on NativeFnV(<native:Message>)` and `No field 'stdio'`. The complete server
> below is extracted from this file and driven on both lanes by
> `tests/e2e/v21-standard-mcp-smoke.sh`, so a change that breaks it turns the gate red instead of
> rotting quietly here.

## Which lanes serve MCP

| Lane | `ssc run` flag | MCP server |
|---|---|---|
| interpreter | `--v1` | yes, over `Transport.Stdio` and `Transport.Http` |
| native (the default) | `--v2` | yes, over `Transport.Stdio` and `Transport.Http` |
| `jvm`, `js` (Node) | — | yes, wrapping the vendor SDKs |
| `scalajs-spa` | — | no — it declares `Feature.McpClient` only |

`Transport.Ws` exists in the type and the native provider still refuses it by name. Stdio is what
Claude Desktop and most editors launch; HTTP is what you want when the server has to be reachable
over a network or to check a token — see below.

### Serving over HTTP

`serveMcp(Transport.Http(8080, "/mcp"))` starts a server that takes one JSON-RPC frame per
`POST /mcp` and answers with one frame. A `GET` on that path is `405`; a request that produced no
reply (a notification) is `204`.

#### Streaming with SSE

Send `Accept: text/event-stream` and the reply is held open as an event stream instead: every frame
the server emits while your call is running arrives as a `data:` event, and the tool's own result
comes last. That is what gives the server a way to speak first, so `notify`, and the blocking asks
`elicit` / `listRoots` / `request`, work over HTTP.

Their answers come back on a **separate** `POST` to the same path, while the first request is still
open. Post the JSON-RPC response frame with the `id` of the server's request; the route matches it
up and answers `204`.

A tool that asks the client a question:

```scalascript
[mcpServer, serveMcp, Transport, Tool, Content](../std/mcp/server.ssc)

def main(): Unit =
  mcpServer(srv =>
    srv.tool("ask", "asks the client")(args =>
      val r = srv.elicit("your name?", Map("type" -> "object"), 8000)
      Tool.text("action=" + r.action + " content=" + r.content.isDefined.toString)))
  serveMcp(Transport.Http(8080, "/mcp"))
```

Calling it with a stream open:

```text
$ curl -sN -X POST http://127.0.0.1:8080/mcp -H 'Accept: text/event-stream' \
    -d '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"ask","arguments":{}}}'
data: {"jsonrpc":"2.0","method":"elicitation/create","params":{"message":"your name?","requestedSchema":{"type":"object"}},"id":1}
```

The stream stops there — the tool is parked inside `elicit`. Answer it on another connection:

```text
$ curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8080/mcp \
    -d '{"jsonrpc":"2.0","id":1,"result":{"action":"accept","content":{"name":"ann"}}}'
204
```

and the first request finishes:

```text
data: {"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"action=accept content=true"}],"isError":false}}
```

Without the header there is no stream, so there is no one to ask, and the call refuses rather than
hanging:

```text
{"jsonrpc":"2.0","id":8,"result":{"content":[{"type":"text","text":"srv.elicit: srv.request: no active client subscribers"}],"isError":true}}
```

### Authorisation

Auth runs on the HTTP route and nowhere else — over stdio the parent process is implicitly trusted,
and no validator is consulted.

```scalascript
srv.setAuthRealm("notes")
srv.setTokenValidator(token =>
  if token == "good" then Map("subject" -> "ann", "scopes" -> List("read"))
  else false)
```

A validator returns `false` to reject, or a `Map` with `subject` (and optionally `scopes`) to
accept. Anything it returns that is neither — including a throw — is a REJECTION: a check that
cannot be read must not become an open door. `srv.useHmacValidator(secret)` is the batteries-included
version.

What a client sees, measured:

```
no Authorization header  -> 401, WWW-Authenticate: Bearer realm="notes", error="invalid_request", …
Bearer nope              -> 401
Bearer good              -> 200 {"jsonrpc":"2.0","id":1,"result":{…}}
```

Inside a handler, `srv.currentAuth()` is `Some(Map("subject" -> …, "scopes" -> …))` for an
authenticated request and `None` otherwise; `srv.authEnabled()` says whether a validator is
installed at all. `srv.setProtectedResourceMetadata(Map("resource" -> "https://…"))` publishes
`/.well-known/oauth-protected-resource` so a client can discover where to get a token — without it
that path answers `404`.

`srv.useAuthServer(...)` is the one auth member the native lane does NOT have: it resolves through
the v1 oauth plugin's registry, and there is no oauth plugin on this lane.

## A complete server

Save as `notes.ssc` and run it with `ssc run notes.ssc`.

<!-- gate:runnable-example — v21-standard-mcp-smoke extracts the NEXT fenced block and runs it on
     both lanes. Keep this marker on the one complete, terminating program; other blocks on the page
     may declare `def main` and call `serveMcp` without being the one to run. -->

```scalascript
[mcpServer, serveMcp, Transport, Tool, Resource, Prompt,
 Message, Role, Content, requireString](../std/mcp/server.ssc)

def main(): Unit =
  mcpServer(srv =>
    srv.tool("greet", "Greet someone by name")(args =>
      Tool.text("hello " + requireString(args, "who")))

    srv.toolWithSchema("add", "Add two numbers", Map(
      "type" -> "object",
      "properties" -> Map(
        "a" -> Map("type" -> "number"),
        "b" -> Map("type" -> "number"))))(args =>
      Tool.text("sum"))

    srv.resource("notes://today", "today")(uri =>
      Resource.textWithUri(uri, "buy milk", "text/plain"))

    srv.prompt("summarise", "Summarise the notes")(args =>
      Prompt.messages(Message(Role.User, Content.Text("Summarise my notes."))))
  )
  serveMcp(Transport.Stdio)
```

Four things to notice, each of which is a mistake someone has already made here:

* **The import is a bracketed list**, `[names](../std/mcp/server.ssc)` — not `import std.mcp`.
* **`tool` and `prompt` are CURRIED**: `srv.tool(name, description)(handler)`. Two argument lists.
* **`toolWithSchema` is NOT curried in its first list** — it takes name, description and schema
  together, then the handler: `srv.toolWithSchema(name, desc, schema)(handler)`.
* **`Message` is a case class, not an object.** Write `Message(Role.User, Content.Text("…"))`.
  There is no `Message.user(...)`.

## Driving it by hand

MCP speaks JSON-RPC over stdin/stdout, one frame per line, so you can drive a server with `printf`.

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"greet","arguments":{"who":"ann"}}}' \
  '{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"notes://today"}}' \
  '{"jsonrpc":"2.0","id":5,"method":"prompts/get","params":{"name":"summarise","arguments":{}}}' \
  | ssc run notes.ssc
```

The answers, copied from that run:

```json
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":true},"resources":{"subscribe":true,"listChanged":true},"prompts":{"listChanged":true},"logging":{},"completions":{}},"serverInfo":{"name":"ssc-mcp-native","version":"2.1"}}}
{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"greet","description":"Greet someone by name","inputSchema":{"type":"object"}},{"name":"add","description":"Add two numbers","inputSchema":{"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}}}}]}}
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hello ann"}],"isError":false}}
{"jsonrpc":"2.0","id":4,"result":{"contents":[{"type":"text","text":"buy milk"}]}}
{"jsonrpc":"2.0","id":5,"result":{"messages":[{"role":"user","content":{"type":"text","text":"Summarise my notes."}}]}}
```

The interpreter answers byte-identically except for `serverInfo`, which names itself
`ssc-mcp-int` / `1.0.0` instead of `ssc-mcp-native` / `2.1`.

Note what `tools/list` shows: `greet` was registered with plain `tool`, so its `inputSchema` is the
open `{"type":"object"}` — it accepts anything. `add` used `toolWithSchema`, so the schema you wrote
is what an agent reads when deciding how to call it. If you want an LLM to pass the right arguments,
`toolWithSchema` is the one you want.

## Claude Desktop

`~/.claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "notes": {
      "command": "ssc",
      "args": ["run", "/absolute/path/to/notes.ssc"]
    }
  }
}
```

## The rest of the surface

Everything below is declared in `std/mcp/server.ssc` and implemented on both the interpreter and the
native lane. The declarations there carry the caveats; this is a map, not a reference.

**Registering things**

| Member | Notes |
|---|---|
| `tool(name[, description])(handler)` | schema is the open `{"type":"object"}` |
| `toolWithSchema(name[, description], schema)(handler)` | publishes your schema to `tools/list` |
| `resource(uri[, name[, mimeType]])(handler)` | one uri |
| `resourceTemplate(template[, name[, description[, mimeType]]])(handler)` | `{...}` segments match one path segment each; the handler receives the CONCRETE uri |
| `prompt(name[, description])(handler)` | |
| `completionForPrompt(promptName, argName, handler)` | NOT curried — three arguments in one list |
| `completionForResource(uriTemplate, argName, handler)` | same shape |

**Telling the client something changed**

`notifyToolsListChanged()`, `notifyResourcesListChanged()`, `notifyPromptsListChanged()`,
`notifyResourceUpdate(uri)`, `notifyProgress(progress[, total])`, `notify(method[, params])`,
`log(level, data[, logger])`, `currentLogLevel()`.

Three of these are CONDITIONAL and stay silent rather than failing, which is easy to mistake for a
broken member:

* `log` drops anything ranked below the level the client set with `logging/setLevel`.
* `notifyResourceUpdate` emits only for a uri the client actually subscribed to.
* `notifyProgress` emits only inside a request that carried `_meta.progressToken`.

**Asking the client something**

`elicit(message, schema[, timeoutMs])`, `listRoots([timeoutMs])`, `request(method[, params[,
timeoutMs]])`, and the predicates `clientSupportsRoots()`, `clientSupportsElicitation()`,
`clientSupportsTasks()`.

> **These block the handler that calls them, and they work on both transports.** Over stdio the
> client answers on a later line; over HTTP it answers on a second connection, with the stream held
> open by `Accept: text/event-stream` (see *Streaming with SSE*). Either way the caller resumes with
> the answer, or gives up at its `timeoutMs`.
>
> One thing to know over stdio: handlers run one at a time, so while yours waits for an answer the
> server still HEARS the client — that is how the answer gets in — but it will not reply to an
> unrelated request until your handler returns.

**Lifecycle and paging**

`onConnected(handler)`, `onDisconnected(handler)`, `onResourceSubscribe(handler)`,
`onResourceUnsubscribe(handler)`, `onRootsListChanged(handler)`, `setPageSize(n)`,
`currentPageSize()`.

## Not available

**`useAuthServer`.** The other six auth members ship on both lanes (see *Authorisation* above);
this one resolves its argument through the v1 oauth plugin's registry and `v2/runtime/providers/`
has no oauth plugin, so it is interpreter-only.

**Concurrent handlers over stdio.** Requests are handled one at a time, in arrival order. The loop
keeps reading while a handler runs — so a client answer to `elicit` gets in — but an unrelated
request waits its turn. Over HTTP each request already runs on its own thread.

**Streaming resources.** One response is one full payload. Generator-backed streaming is a backlog
item now that `std/generators.ssc` has landed.

## See also

* `std/mcp/server.ssc` — the declared surface, with the reasoning next to each member.
* `std/mcp/types.ssc` — `ToolResult`, `ResourceResult`, `PromptResult`, `Content`, `Message`,
  `Role`, `Root`, `ElicitationResult`, and the `require*` argument extractors.
* `specs/mcp.md` §11 — the open design questions, each with a verdict measured against the code.
