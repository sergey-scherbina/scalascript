package ssc.plugin.crypto

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Runtime, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

class CryptoNativePluginTest extends AnyFunSuite:
  test("sha256 registers as a standard intrinsic and global") {
    NativePluginHost.installProviders(List(CryptoNativePlugin()))
    assert(V2PluginRegistry.lookup("sha256").map(_(List(Value.StrV("hello")))).contains(
      Value.StrV("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")))
    val global = V2PluginRegistry.lookupGlobal("sha256").get.asInstanceOf[Value.ClosV]
    assert(Runtime.run(global.code, Array(Value.StrV("hello"))) ==
      Value.StrV("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"))
  }

  test("base64 and HMAC helpers preserve the legacy surface") {
    NativePluginHost.installProviders(List(CryptoNativePlugin()))
    val encode = V2PluginRegistry.lookup("base64Encode").get
    val decode = V2PluginRegistry.lookup("base64Decode").get
    assert(encode(List(Value.StrV("hello"))) == Value.StrV("aGVsbG8="))
    assert(decode(List(Value.StrV("aGVsbG8="))) == Value.StrV("hello"))
    assert(V2PluginRegistry.lookup("hmacSha256").get(List(Value.StrV("key"), Value.StrV("data"))) ==
      Value.StrV("5031fe3d989c6d1537a013fa6e739da23463fdaec3b70137d828e36ace221bd0"))
  }
