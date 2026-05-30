package scalascript.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

/** GraphQL JS/Node codegen — `graphql` block capture, intrinsic emission,
 *  runtime inclusion, `package.json` dependency, and an end-to-end Node
 *  round-trip (POST /graphql against a `serveGraphQL` server).
 *
 *  The round-trip is skipped gracefully when `node` / `npm` aren't on PATH. */
class NodeBackendGraphqlTest extends AnyFunSuite:

  private val backend = NodeBackend()

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def hasNpm: Boolean =
    try ProcessBuilder("npm", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def compileToOutputs(src: String): (String, List[SourceArtifact]) =
    val ir = Normalize(Parser.parse(src))
    backend.compile(ir, BackendOptions()) match
      case CompileResult.TextOutput(code, "javascript", sources) => (code, sources)
      case other => fail(s"expected TextOutput(javascript, …), got: $other")

  private def packageJson(sources: List[SourceArtifact]): String =
    sources.collectFirst { case SourceArtifact("package.json", c) => c }
      .getOrElse(fail(s"expected a package.json in sources, got: ${sources.map(_.name)}"))

  private val serverProgram =
    """|# GraphQL server
       |
       |```graphql
       |type Query { hello: String! greet(name: String!): String! }
       |type Mutation { shout(text: String!): String! }
       |```
       |
       |```scalascript
       |val resolvers = GraphQL.resolvers(
       |  query = Map(
       |    "hello" -> (_ => "world"),
       |    "greet" -> (args => "Hi " + args("name"))
       |  ),
       |  mutation = Map(
       |    "shout" -> (args => args("text") + "!")
       |  )
       |)
       |serveGraphQL(4071, resolvers)
       |```
       |""".stripMargin

  /** Server exercising a custom scalar (`Tagged`) plus nested-object and
   *  list-of-object output.  `Tagged.serialize` wraps output in `[...]`;
   *  `Tagged.coerce` prefixes input with `got:`. */
  private val scalarProgram =
    """|# GraphQL scalar server
       |
       |```graphql
       |scalar Tagged
       |type Author { name: String! }
       |type Book { title: String! author: Author! }
       |type Query {
       |  tag(text: String!): Tagged!
       |  echo(val: Tagged!): String!
       |  books: [Book!]!
       |}
       |```
       |
       |```scalascript
       |val tagged = GraphQL.scalar("Tagged",
       |  serialize = (v: String) => "[" + v + "]",
       |  coerce    = (raw: String) => "got:" + raw
       |)
       |val resolvers = GraphQL.resolvers(
       |  query = Map(
       |    "tag"   -> (args => args("text")),
       |    "echo"  -> (args => args("val")),
       |    "books" -> (_ => List(
       |      Map("title" -> "A", "author" -> Map("name" -> "X")),
       |      Map("title" -> "B", "author" -> Map("name" -> "Y"))
       |    ))
       |  ),
       |  scalars = Map("Tagged" -> tagged)
       |)
       |serveGraphQL(4072, resolvers)
       |```
       |""".stripMargin

  // ── Codegen-shape unit tests (no node required) ────────────────────────

  test("no graphql usage → no package.json artifact"):
    val (_, sources) = compileToOutputs(
      """|# Plain
         |
         |```scalascript
         |val x = 1
         |println(x)
         |```
         |""".stripMargin)
    assert(sources.forall(_.name != "package.json"))

  test("graphql server program → package.json lists the graphql dep"):
    val (_, sources) = compileToOutputs(serverProgram)
    val pkg = packageJson(sources)
    assert(pkg.contains("\"graphql\""), s"expected graphql dep in:\n$pkg")
    assert(pkg.contains("\"main\": \"main.cjs\""))

  test("graphql block lowers to _registerGraphqlSdl with the SDL"):
    val (code, _) = compileToOutputs(serverProgram)
    assert(code.contains("_registerGraphqlSdl("))
    assert(code.contains("type Query"), "SDL text should be embedded in the call")
    assert(code.contains("greet(name: String!)"))

  test("server intrinsics emit their runtime call sites"):
    val (code, _) = compileToOutputs(serverProgram)
    assert(code.contains("GraphQL.resolvers("))
    assert(code.contains("serveGraphQL("))

  test("named resolver args lower to an options object"):
    val (code, _) = compileToOutputs(serverProgram)
    // `GraphQL.resolvers(query = ..., mutation = ...)` → `({query: ..., mutation: ...})`
    assert(code.contains("GraphQL.resolvers({"), "expected options-object call site")
    assert(code.contains("query:"))
    assert(code.contains("mutation:"))

  test("graphql runtime preamble is included"):
    val (code, _) = compileToOutputs(serverProgram)
    assert(code.contains("function graphqlHandler("))
    assert(code.contains("function graphqlMount("))
    assert(code.contains("function serveGraphQL("))
    assert(code.contains("async function graphqlQuery("))
    assert(code.contains("require('graphql')"))

  test("graphql forces the HTTP runtime (serve / route)"):
    val (code, _) = compileToOutputs(serverProgram)
    assert(code.contains("function route("))
    assert(code.contains("function _ssc_http_serve("))

  // ── End-to-end Node round-trip ─────────────────────────────────────────

  test("serveGraphQL answers a POST query end-to-end via node"):
    assume(hasNode, "node not available")
    assume(hasNpm, "npm not available")
    val (code, sources) = compileToOutputs(serverProgram)
    val pkg = packageJson(sources)

    val dir = Path.of(sys.props.getOrElse("user.dir", "."))
      .resolve("target/node-backend-graphql-test")
    Files.createDirectories(dir)

    // Driver: after the server binds, POST a combined query+mutation, print
    // the response body prefixed with RESULT:, then exit.
    val driver =
      """|setTimeout(() => {
         |  const http = require('http');
         |  const payload = JSON.stringify({
         |    query: 'mutation { shout(text: "hey") }'
         |  });
         |  const q = JSON.stringify({ query: '{ hello greet(name: "Bob") }' });
         |  const post = (body, cb) => {
         |    const r = http.request({
         |      hostname: 'localhost', port: 4071, path: '/graphql', method: 'POST',
         |      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }
         |    }, res => { let b = ''; res.on('data', c => b += c); res.on('end', () => cb(b)); });
         |    r.on('error', e => { process.stdout.write('ERR:' + e.message + '\n'); process.exit(1); });
         |    r.write(body); r.end();
         |  };
         |  post(q, qres => post(payload, mres => {
         |    process.stdout.write('QUERY:' + qres + '\n');
         |    process.stdout.write('MUT:' + mres + '\n');
         |    process.exit(0);
         |  }));
         |}, 700);
         |""".stripMargin

    Files.writeString(dir.resolve("main.cjs"), code + "\n" + driver, StandardCharsets.UTF_8)
    Files.writeString(dir.resolve("package.json"), pkg, StandardCharsets.UTF_8)

    val stamp     = dir.resolve(".npm-install-stamp")
    val pkgMtime  = Files.getLastModifiedTime(dir.resolve("package.json")).toMillis
    val installed = Files.exists(dir.resolve("node_modules")) &&
                    Files.exists(stamp) &&
                    Files.getLastModifiedTime(stamp).toMillis >= pkgMtime
    if !installed then
      val inst = ProcessBuilder("npm", "install", "--no-audit", "--no-fund", "--silent")
        .directory(dir.toFile).redirectErrorStream(true).start()
      val instOut  = new String(inst.getInputStream.readAllBytes(), "UTF-8")
      val instCode = inst.waitFor()
      assert(instCode == 0, s"npm install failed (exit $instCode):\n$instOut")
      Files.writeString(stamp, "ok")

    val run = ProcessBuilder("node", "main.cjs")
      .directory(dir.toFile).redirectErrorStream(true).start()
    val out  = new String(run.getInputStream.readAllBytes(), "UTF-8")
    val code2 = run.waitFor()
    assert(code2 == 0, s"node run failed (exit $code2):\n$out")
    assert(out.contains("\"hello\":\"world\""), s"missing hello in:\n$out")
    assert(out.contains("\"greet\":\"Hi Bob\""), s"missing greet in:\n$out")
    assert(out.contains("\"shout\":\"hey!\""), s"missing mutation result in:\n$out")

  // ── Custom scalars + nested/list output ────────────────────────────────

  test("GraphQL.scalar lowers to a runtime call + scalars option"):
    val (code, _) = compileToOutputs(scalarProgram)
    assert(code.contains("GraphQL.scalar("), "expected scalar call site")
    assert(code.contains("scalars:"), "expected scalars option in resolvers object")

  test("custom scalar runtime wiring is included"):
    val (code, _) = compileToOutputs(scalarProgram)
    assert(code.contains("function _graphqlApplyScalars("))
    assert(code.contains("scalar:"), "GraphQL.scalar runtime fn")

  test("scalar SDL + nested object types are embedded"):
    val (code, _) = compileToOutputs(scalarProgram)
    assert(code.contains("_registerGraphqlSdl("))
    assert(code.contains("scalar Tagged"))
    assert(code.contains("type Book"))

  test("serveGraphQL applies scalars + resolves nested/list output via node"):
    assume(hasNode, "node not available")
    assume(hasNpm, "npm not available")
    val (code, sources) = compileToOutputs(scalarProgram)
    val pkg = packageJson(sources)

    val dir = Path.of(sys.props.getOrElse("user.dir", "."))
      .resolve("target/node-backend-graphql-scalar-test")
    Files.createDirectories(dir)

    val driver =
      """|setTimeout(() => {
         |  const http = require('http');
         |  const q = JSON.stringify({
         |    query: '{ tag(text: "hi") echo(val: "x") books { title author { name } } }'
         |  });
         |  const r = http.request({
         |    hostname: 'localhost', port: 4072, path: '/graphql', method: 'POST',
         |    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(q) }
         |  }, res => { let b = ''; res.on('data', c => b += c); res.on('end', () => {
         |    process.stdout.write('RESULT:' + b + '\n'); process.exit(0);
         |  }); });
         |  r.on('error', e => { process.stdout.write('ERR:' + e.message + '\n'); process.exit(1); });
         |  r.write(q); r.end();
         |}, 700);
         |""".stripMargin

    Files.writeString(dir.resolve("main.cjs"), code + "\n" + driver, StandardCharsets.UTF_8)
    Files.writeString(dir.resolve("package.json"), pkg, StandardCharsets.UTF_8)

    val stamp     = dir.resolve(".npm-install-stamp")
    val pkgMtime  = Files.getLastModifiedTime(dir.resolve("package.json")).toMillis
    val installed = Files.exists(dir.resolve("node_modules")) &&
                    Files.exists(stamp) &&
                    Files.getLastModifiedTime(stamp).toMillis >= pkgMtime
    if !installed then
      val inst = ProcessBuilder("npm", "install", "--no-audit", "--no-fund", "--silent")
        .directory(dir.toFile).redirectErrorStream(true).start()
      val instOut  = new String(inst.getInputStream.readAllBytes(), "UTF-8")
      val instCode = inst.waitFor()
      assert(instCode == 0, s"npm install failed (exit $instCode):\n$instOut")
      Files.writeString(stamp, "ok")

    val run = ProcessBuilder("node", "main.cjs")
      .directory(dir.toFile).redirectErrorStream(true).start()
    val out  = new String(run.getInputStream.readAllBytes(), "UTF-8")
    val code2 = run.waitFor()
    assert(code2 == 0, s"node run failed (exit $code2):\n$out")
    assert(out.contains("\"tag\":\"[hi]\""), s"scalar serialize not applied in:\n$out")
    assert(out.contains("\"echo\":\"got:x\""), s"scalar coerce not applied in:\n$out")
    assert(out.contains("\"title\":\"A\""), s"missing list element in:\n$out")
    assert(out.contains("\"author\":{\"name\":\"X\"}"), s"missing nested object in:\n$out")
    assert(out.contains("\"name\":\"Y\""), s"missing second list element nested field in:\n$out")
