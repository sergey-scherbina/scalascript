package ssc.plugin.nfc

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Prims, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

final class NfcNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).get(args.toList)

  test("host capability snapshot and permission are deterministic") {
    NativePluginHost.installProviders(List(NfcNativePlugin()))
    val capabilities = call("nfcCapabilities").asInstanceOf[Value.DataV]
    assert(capabilities.tag == "NfcCapabilities")
    assert(capabilities.fields.head == Value.BoolV(false))
    assert(capabilities.fields.last == Value.StrV("jvm-host"))
    assert(call("nfcPermissionStatus") == Value.DataV("NfcPermissionUnknown", Vector.empty))
  }

  test("record constructors preserve UTF-8 and bounded bytes") {
    NativePluginHost.installProviders(List(NfcNativePlugin()))
    val none = Value.DataV("None", Vector.empty)
    val text = call("textRecord", Value.StrV("Hi"), Value.StrV("en"), none)
      .asInstanceOf[Value.DataV]
    assert(text.fields.head == Value.StrV("text"))
    assert(Prims.unlistPub(text.fields.last) == List(Value.IntV(72), Value.IntV(105)))
    val data = Value.DataV("Cons", Vector(Value.IntV(255), Value.DataV("Nil", Vector.empty)))
    val mime = call("mimeRecord", Value.StrV("application/octet-stream"), data, none)
      .asInstanceOf[Value.DataV]
    assert(mime.fields.head == Value.StrV("mime"))
    assert(Prims.unlistPub(mime.fields.last) == List(Value.IntV(255)))
  }

  test("hardware reads fail through a bounded provider diagnostic") {
    NativePluginHost.installProviders(List(NfcNativePlugin()))
    val error = intercept[UnsupportedOperationException] { call("readNdef") }
    assert(error.getMessage.contains("current host provider"))
  }
