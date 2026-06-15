package scalascript.compiler.plugin.nfc

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.{Feature, NativeContext, NativeImpl}
import scalascript.interpreter.Value
import scalascript.ir.QualifiedName
import scalascript.testkit.TestInterpreter

class NfcPluginTest extends AnyFunSuite:

  private val plugin = NfcInterpreterPlugin()

  private val ctx = new NativeContext:
    val out: java.io.PrintStream = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val err: java.io.PrintStream = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  private def native(name: String): NativeImpl =
    plugin.intrinsics(QualifiedName(name)).asInstanceOf[NativeImpl]

  private val declaredIntrinsics = Set(
    "nfcCapabilities",
    "nfcPermissionStatus",
    "requestNfcPermission",
    "readNdef",
    "writeNdef",
    "textRecord",
    "uriRecord",
    "mimeRecord",
  )

  test("plugin advertises the NDEF capability"):
    assert(plugin.capabilities.features.contains(Feature.NfcNdef))
    assert(!plugin.capabilities.features.contains(Feature.NfcTagTech))
    assert(!plugin.capabilities.features.contains(Feature.NfcCardEmulation))

  test("every std.nfc extern has an intrinsic entry"):
    val actual = plugin.intrinsics.keySet
    val expected = declaredIntrinsics.map(QualifiedName.apply)
    assert(expected.subsetOf(actual))

  test("nfcCapabilities returns a stable unsupported interpreter snapshot"):
    val result = native("nfcCapabilities").eval(ctx, Nil)
    val fields = result.asInstanceOf[Value.InstanceV].effectiveFields
    assert(fields("supported") == Value.False)
    assert(fields("enabled") == Value.False)
    assert(fields("ndefRead") == Value.False)
    assert(fields("ndefWrite") == Value.False)
    assert(fields("tagTech") == Value.False)
    assert(fields("cardEmulation") == Value.False)
    assert(fields("platform") == Value.StringV("interpreter"))

  test("permission status is total and unknown on the interpreter adapter"):
    val status = native("nfcPermissionStatus").eval(ctx, Nil)
    val request = native("requestNfcPermission").eval(ctx, Nil)
    assert(status == Value.InstanceV("NfcPermissionUnknown", Map.empty))
    assert(request == Value.InstanceV("NfcPermissionUnknown", Map.empty))

  test("read and write fail with a clear unsupported diagnostic"):
    val readError = intercept[Throwable]:
      native("readNdef").eval(ctx, Nil)
    assert(readError.getMessage.contains("NFC hardware access is not supported"))

    val writeError = intercept[Throwable]:
      native("writeNdef").eval(ctx, Nil)
    assert(writeError.getMessage.contains("NFC hardware access is not supported"))

  test("textRecord encodes UTF-8 bytes and preserves language/id fields"):
    val result = native("textRecord").eval(ctx, List("Hi", "en", Value.someV(Value.StringV("greeting"))))
    val fields = result.asInstanceOf[Value.InstanceV].effectiveFields
    assert(fields("recordType") == Value.StringV("text"))
    assert(fields("mediaType") == Value.NoneV)
    assert(fields("id") == Value.someV(Value.StringV("greeting")))
    assert(fields("encoding") == Value.StringV("utf-8"))
    assert(fields("lang") == Value.StringV("en"))
    assert(fields("data") == Value.ListV(List(Value.IntV(72), Value.IntV(105))))

  test("uriRecord and mimeRecord produce portable record shapes"):
    val uriFields = native("uriRecord").eval(ctx, List("https://example.test/nfc", Value.NoneV))
      .asInstanceOf[Value.InstanceV].effectiveFields
    assert(uriFields("recordType") == Value.StringV("uri"))
    assert(uriFields("mediaType") == Value.NoneV)
    assert(uriFields("lang") == Value.StringV(""))

    val mimeFields = native("mimeRecord").eval(ctx, List("application/octet-stream", Value.ListV(List(Value.IntV(0), Value.IntV(255))), Value.NoneV))
      .asInstanceOf[Value.InstanceV].effectiveFields
    assert(mimeFields("recordType") == Value.StringV("mime"))
    assert(mimeFields("mediaType") == Value.someV(Value.StringV("application/octet-stream")))
    assert(mimeFields("encoding") == Value.StringV("binary"))
    assert(mimeFields("data") == Value.ListV(List(Value.IntV(0), Value.IntV(255))))

  test("mimeRecord rejects bytes outside 0..255"):
    val error = intercept[Throwable]:
      native("mimeRecord").eval(ctx, List("application/octet-stream", Value.ListV(List(Value.IntV(256))), Value.NoneV))
    assert(error.getMessage.contains("0..255"))

  test("helper constructors run through the normal interpreter path"):
    val result = TestInterpreter(List(plugin)).eval(
      """
      case class NdefRecord(
        recordType: String,
        mediaType:  Option[String],
        id:         Option[String],
        encoding:   String,
        lang:       String,
        data:       List[Int]
      )
      extern def textRecord(text: String, lang: String = "en", id: Option[String] = None): NdefRecord

      val r = textRecord("OK", "en", None)
      r.recordType + ":" + r.lang + ":" + r.data.mkString(",")
      """
    )
    assert(result == "text:en:79,75")
