package ssc.plugin.pdf

import java.util.Base64
import org.scalatest.funsuite.AnyFunSuite
import ssc.{V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

final class PdfNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).get(args.toList)

  test("render, page count, and text extraction form a real PDF round trip") {
    NativePluginHost.installProviders(List(PdfNativePlugin()))
    val encoded = call("htmlToPdfBase64", Value.StrV(
      "<html><body><h1>PIT-11</h1><p>Jan Kowalski</p></body></html>")) match
      case Value.StrV(value) => value
      case other => fail(s"unexpected PDF result: $other")
    assert(new String(Base64.getDecoder.decode(encoded), 0, 5) == "%PDF-")
    assert(call("pdfPageCount", Value.StrV(encoded)) == Value.IntV(1))
    val markdown = call("pdfToMarkdown", Value.StrV(encoded)).asInstanceOf[Value.StrV].s
    assert(markdown.contains("PIT-11"))
    assert(markdown.contains("Jan Kowalski"))
  }

  test("malformed input reports the public operation") {
    NativePluginHost.installProviders(List(PdfNativePlugin()))
    val error = intercept[RuntimeException] {
      call("pdfToMarkdown", Value.StrV("not-a-pdf"))
    }
    assert(error.getMessage.contains("pdfToMarkdown"))
  }

  test("invoice companion assembles a deterministic MIME attachment") {
    NativePluginHost.installProviders(List(PdfNativePlugin(), MimeNativePlugin()))
    val attachment = Value.DataV("Tuple3", Vector(
      Value.StrV("invoice.pdf"), Value.StrV("application/pdf"), Value.StrV("JVBERg==")))
    val files = Value.DataV("Cons", Vector(attachment, Value.DataV("Nil", Vector.empty)))
    val message = call("buildMimeMessage",
      Value.StrV("from@example.test"),
      Value.StrV("to@example.test"),
      Value.StrV("Invoice"),
      Value.StrV("<p>Body</p>"),
      files).asInstanceOf[Value.StrV].s
    assert(message.contains("multipart/mixed"))
    assert(message.contains("invoice.pdf"))
    assert(message.contains("JVBERg=="))
  }
