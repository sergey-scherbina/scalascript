package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import scalascript.interpreter.{Interpreter, Value}
import scalascript.server.Routes
import scalascript.wire.{WireEnvelope, WireValue}
import scalascript.wire.msgpack.MsgPackWireCodec
import scalascript.wire.cbor.CborWireCodec

import java.nio.file.{Files, Path}
import java.util.Base64

/** v1.62.3 — binary content negotiation for typed route handlers.
 *
 *  Tests:
 *  - MsgPack request body decode
 *  - CBOR request body decode
 *  - MsgPack response encode (Accept header)
 *  - CBOR response encode (Accept header)
 *  - 415 for unknown wire format
 *  - 406 for unsatisfiable Accept
 *  - JSON fallback (no content negotiation headers)
 *  - Malformed Base64 body → 400
 *  - Empty binary body → normal empty body handling */
class TypedRpcBinaryTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  private var tmpDir: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    Routes.clear()
    tmpDir = Files.createTempDirectory("ssc-typed-rpc-binary-test")

  override def afterEach(): Unit =
    tmpDir.toFile.listFiles().nn.foreach(_.delete())
    tmpDir.toFile.delete()

  // ── helpers ──────────────────────────────────────────────────────────────

  private def handlerFile(name: String, content: String): String =
    val path = tmpDir.resolve(name)
    Files.writeString(path, s"# Handler\n\n```scala\n$content\n```\n")
    path.toAbsolutePath.toString

  private def makeInterp(): Interpreter =
    val buf  = java.io.ByteArrayOutputStream()
    val ps   = java.io.PrintStream(buf, true)
    val interp = Interpreter(ps, Some(os.Path(tmpDir.toAbsolutePath.toString)))
    interp.run(scalascript.parser.Parser.parse("# Test\n"))
    interp.runSnippet("Response.text(\"init\")")
    interp

  private def mkReq(
    method:  String,
    path:    String,
    body:    Option[String] = None,
    headers: Map[String, String],
  ): Value.InstanceV =
    def strMap(m: Map[String, String]): Value =
      Value.MapV(m.map { (k, v) => (Value.StringV(k): Value) -> (Value.StringV(v): Value) })
    Value.InstanceV("Request", Map(
      "method"  -> Value.StringV(method),
      "path"    -> Value.StringV(path),
      "params"  -> Value.MapV(Map.empty),
      "query"   -> Value.MapV(Map.empty),
      "headers" -> strMap(headers.map { case (k, v) => k.toLowerCase -> v }),
      "body"    -> body.fold[Value](Value.EmptyStr)(Value.StringV(_)),
    ))

  private def invoke(interp: Interpreter, method: String, path: String, file: String, req: Value.InstanceV): Value =
    interp.mountFileAsRoute(method, path, file, Map.empty)
    val Some((entry, _)) = Routes.matchRequest(method, path): @unchecked
    entry.interpreter.invoke(entry.handler, List(req))

  private def statusOf(v: Value): Long = v match
    case Value.InstanceV("Response", f) => f.get("status") match
      case Some(Value.IntV(n)) => n
      case _                   => -1L
    case _ => -1L

  private def bodyOf(v: Value): String = v match
    case Value.InstanceV("Response", f) => f.get("body") match
      case Some(Value.StringV(s)) => s
      case Some(other)            => Value.show(other)
      case None                   => ""
    case other => Value.show(other)

  private def contentTypeOf(v: Value): String = v match
    case Value.InstanceV("Response", f) => f.get("headers") match
      case Some(Value.MapV(m)) =>
        m.collectFirst {
          case (Value.StringV(k), Value.StringV(v)) if k.equalsIgnoreCase("Content-Type") => v
        }.getOrElse("")
      case _ => ""
    case _ => ""

  private def encodeMsgPack(json: String): String =
    val env   = WireEnvelope.rpc("msgpack", "request", WireValue.Str(json))
    val bytes = MsgPackWireCodec.encodeEnvelope(env)
    Base64.getEncoder.encodeToString(bytes)

  private def encodeCbor(json: String): String =
    val env   = WireEnvelope.rpc("cbor", "request", WireValue.Str(json))
    val bytes = CborWireCodec.encodeEnvelope(env)
    Base64.getEncoder.encodeToString(bytes)

  private def decodeMsgPackBody(b64: String): String =
    val bytes = Base64.getDecoder.decode(b64.trim)
    MsgPackWireCodec.decodeEnvelope(bytes) match
      case Right(env) => env.payload match
        case WireValue.Str(s) => s
        case other            => s"unexpected: ${other}"
      case Left(err) => s"decode error: $err"

  private def decodeCborBody(b64: String): String =
    val bytes = Base64.getDecoder.decode(b64.trim)
    CborWireCodec.decodeEnvelope(bytes) match
      case Right(env) => env.payload match
        case WireValue.Str(s) => s
        case other            => s"unexpected: ${other}"
      case Left(err) => s"decode error: $err"

  private val echoHandler =
    """case class EchoIn(msg: String)
      |case class EchoOut(echo: String)
      |(input: EchoIn) => EchoOut(input.msg)
      |""".stripMargin

  // ── tests ────────────────────────────────────────────────────────────────

  test("binary: plain JSON body still works (no content negotiation)"):
    val file = handlerFile("echo-json.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some("""{"msg":"hello"}"""),
      headers = Map("Content-Type" -> "application/json"))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(200L)
    bodyOf(resp).should(include("hello"))

  test("binary: MsgPack request body decode"):
    val b64  = encodeMsgPack("""{"msg":"msgpack-hello"}""")
    val file = handlerFile("echo-mp.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some(b64),
      headers = Map("Content-Type" -> "application/vnd.scalascript.wire+msgpack"))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(200L)
    bodyOf(resp).should(include("msgpack-hello"))

  test("binary: CBOR request body decode"):
    val b64  = encodeCbor("""{"msg":"cbor-hello"}""")
    val file = handlerFile("echo-cbor.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some(b64),
      headers = Map("Content-Type" -> "application/vnd.scalascript.wire+cbor"))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(200L)
    bodyOf(resp).should(include("cbor-hello"))

  test("binary: MsgPack response when Accept prefers msgpack"):
    val file = handlerFile("echo-accept-mp.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some("""{"msg":"resp-msg"}"""),
      headers = Map(
        "Content-Type" -> "application/json",
        "Accept"       -> "application/vnd.scalascript.wire+msgpack, application/json;q=0.5",
      ))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(200L)
    contentTypeOf(resp).should(include("vnd.scalascript.wire+msgpack"))
    decodeMsgPackBody(bodyOf(resp)).should(include("resp-msg"))

  test("binary: CBOR response when Accept prefers cbor"):
    val file = handlerFile("echo-accept-cbor.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some("""{"msg":"resp-cbor"}"""),
      headers = Map(
        "Content-Type" -> "application/json",
        "Accept"       -> "application/vnd.scalascript.wire+cbor, application/json;q=0.5",
      ))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(200L)
    contentTypeOf(resp).should(include("vnd.scalascript.wire+cbor"))
    decodeCborBody(bodyOf(resp)).should(include("resp-cbor"))

  test("binary: JSON response when Accept is application/json"):
    val file = handlerFile("echo-json-accept.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some("""{"msg":"json-resp"}"""),
      headers = Map("Accept" -> "application/json"))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(200L)
    contentTypeOf(resp).should(include("application/json"))
    bodyOf(resp).should(include("json-resp"))

  test("binary: CBOR preferred over MsgPack in Accept header"):
    val file = handlerFile("echo-pref.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some("""{"msg":"pref"}"""),
      headers = Map(
        "Accept" -> "application/vnd.scalascript.wire+cbor, application/vnd.scalascript.wire+msgpack",
      ))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(200L)
    contentTypeOf(resp).should(include("vnd.scalascript.wire+cbor"))

  test("binary: MsgPack roundtrip — binary request + binary response"):
    val b64  = encodeMsgPack("""{"msg":"roundtrip"}""")
    val file = handlerFile("echo-rt.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some(b64),
      headers = Map(
        "Content-Type" -> "application/vnd.scalascript.wire+msgpack",
        "Accept"       -> "application/vnd.scalascript.wire+msgpack, application/json;q=0.5",
      ))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(200L)
    contentTypeOf(resp).should(include("vnd.scalascript.wire+msgpack"))
    decodeMsgPackBody(bodyOf(resp)).should(include("roundtrip"))

  test("binary: CBOR roundtrip — binary request + binary response"):
    val b64  = encodeCbor("""{"msg":"cbor-rt"}""")
    val file = handlerFile("echo-cbor-rt.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some(b64),
      headers = Map(
        "Content-Type" -> "application/vnd.scalascript.wire+cbor",
        "Accept"       -> "application/vnd.scalascript.wire+cbor, application/json;q=0.5",
      ))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(200L)
    contentTypeOf(resp).should(include("vnd.scalascript.wire+cbor"))
    decodeCborBody(bodyOf(resp)).should(include("cbor-rt"))

  test("binary: 415 for unsupported wire format in Content-Type"):
    val file = handlerFile("echo-415.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some("junk"),
      headers = Map("Content-Type" -> "application/vnd.scalascript.wire+xml"))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(415L)
    bodyOf(resp).should(include("unsupported wire format"))

  test("binary: 406 for unsatisfiable Accept header"):
    val file = handlerFile("echo-406.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some("""{"msg":"nope"}"""),
      headers = Map(
        "Content-Type" -> "application/json",
        "Accept"       -> "application/xml",
      ))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(406L)
    bodyOf(resp).should(include("not acceptable"))

  test("binary: 400 for malformed Base64 body with binary Content-Type"):
    val file = handlerFile("echo-400.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some("this-is-not-valid-base64!!!"),
      headers = Map("Content-Type" -> "application/vnd.scalascript.wire+msgpack"))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(400L)

  test("binary: empty body with binary Content-Type is OK"):
    val file = handlerFile("echo-empty.ssc", echoHandler)
    val req  = mkReq("GET", "/echo",
      headers = Map("Content-Type" -> "application/vnd.scalascript.wire+msgpack"))
    val resp = invoke(makeInterp(), "GET", "/echo", file, req)
    // GET with no body: handler gets invoked with defaults, responds without error
    statusOf(resp).should(be >= 200L)
    statusOf(resp).should(be < 500L)

  test("binary: */* Accept accepts JSON"):
    val file = handlerFile("echo-star.ssc", echoHandler)
    val req  = mkReq("POST", "/echo", body = Some("""{"msg":"star"}"""),
      headers = Map("Accept" -> "*/*"))
    val resp = invoke(makeInterp(), "POST", "/echo", file, req)
    statusOf(resp).shouldBe(200L)
    bodyOf(resp).should(include("star"))
