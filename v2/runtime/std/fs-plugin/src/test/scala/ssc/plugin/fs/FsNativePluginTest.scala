package ssc.plugin.fs

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import ssc.{Prims, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

class FsNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).get(args.toList)

  test("text and byte operations round-trip through the native registry") {
    NativePluginHost.installProviders(List(FsNativePlugin()))
    val dir = Files.createTempDirectory("ssc-v21-fs-test")
    val text = dir.resolve("sample.txt")
    val bytes = dir.resolve("sample.bin")
    try
      assert(call("writeFile", Value.StrV(text.toString), Value.StrV("one")) == Value.UnitV)
      call("appendFile", Value.StrV(text.toString), Value.StrV("-two"))
      assert(call("readFile", Value.StrV(text.toString)) == Value.StrV("one-two"))

      val byteList = Value.DataV("Cons", Vector(Value.IntV(0),
        Value.DataV("Cons", Vector(Value.IntV(255), Value.DataV("Nil", Vector.empty)))))
      call("writeBytes", Value.StrV(bytes.toString), byteList)
      assert(Prims.unlistPub(call("readBytes", Value.StrV(bytes.toString))) ==
        List(Value.IntV(0), Value.IntV(255)))
    finally
      Files.deleteIfExists(text)
      Files.deleteIfExists(bytes)
      Files.deleteIfExists(dir)
  }

  test("directory and file-management operations preserve the std.fs surface") {
    NativePluginHost.installProviders(List(FsNativePlugin()))
    val root = Files.createTempDirectory("ssc-v21-fs-tree")
    val nested = root.resolve("a").resolve("b")
    val source = nested.resolve("source.txt")
    val copied = nested.resolve("copied.txt")
    val moved = nested.resolve("moved.txt")
    try
      call("mkdirs", Value.StrV(nested.toString))
      call("writeFile", Value.StrV(source.toString), Value.StrV("data"))
      assert(call("exists", Value.StrV(source.toString)) == Value.BoolV(true))
      assert(call("isFile", Value.StrV(source.toString)) == Value.BoolV(true))
      assert(call("isDir", Value.StrV(nested.toString)) == Value.BoolV(true))
      call("copyFile", Value.StrV(source.toString), Value.StrV(copied.toString))
      call("moveFile", Value.StrV(copied.toString), Value.StrV(moved.toString))
      assert(Prims.unlistPub(call("listDir", Value.StrV(nested.toString))) ==
        List(Value.StrV("moved.txt"), Value.StrV("source.txt")))
      call("deleteFile", Value.StrV(moved.toString))
      assert(call("exists", Value.StrV(moved.toString)) == Value.BoolV(false))
    finally
      Files.deleteIfExists(source)
      Files.deleteIfExists(copied)
      Files.deleteIfExists(moved)
      Files.deleteIfExists(nested)
      Files.deleteIfExists(root.resolve("a"))
      Files.deleteIfExists(root)
  }
