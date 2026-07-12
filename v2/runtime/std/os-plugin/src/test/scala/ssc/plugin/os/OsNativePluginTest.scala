package ssc.plugin.os

import java.nio.file.{Files, Paths}
import org.scalatest.funsuite.AnyFunSuite
import ssc.{V2PluginRegistry, Value}
import ssc.plugin.{NativePlugin, NativePluginContext, NativePluginHost}

class OsNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookup(name).get(args.toList)

  test("environment and paths preserve the std.os surface") {
    NativePluginHost.installProviders(List(OsNativePlugin()))
    assert(call("envOrElse", Value.StrV("SSC_V21_MISSING_ENV_4E0EAC"), Value.StrV("fallback")) ==
      Value.StrV("fallback"))
    assert(call("env", Value.StrV("SSC_V21_MISSING_ENV_4E0EAC")) ==
      Value.DataV("None", Vector.empty))
    val joined = call("pathJoin", Value.StrV("root"), Value.StrV("nested"), Value.StrV("file.txt"))
    assert(joined == Value.StrV(Paths.get("root", "nested", "file.txt").toString))
    assert(call("pathBasename", joined) == Value.StrV("file.txt"))
    assert(call("pathExtname", joined) == Value.StrV(".txt"))
    assert(call("pathDirname", joined) == Value.StrV(Paths.get("root", "nested").toString))
    assert(call("pathIsAbsolute", joined) == Value.BoolV(false))
  }

  test("host-derived values and temp files are registered without the compatibility bridge") {
    NativePluginHost.installProviders(List(OsNativePlugin()))
    assert(V2PluginRegistry.lookupGlobal("tempDir").exists(_.isInstanceOf[Value.StrV]))
    assert(V2PluginRegistry.lookupGlobal("homedir").exists(_.isInstanceOf[Value.StrV]))
    assert(V2PluginRegistry.lookupGlobal("hostname").exists(_.isInstanceOf[Value.StrV]))
    val temp = call("tempFile", Value.StrV("ssc-v21-os"), Value.StrV(".tmp")) match
      case Value.StrV(path) => Paths.get(path)
      case other => fail(s"expected temp-file path, got $other")
    try assert(Files.isRegularFile(temp))
    finally Files.deleteIfExists(temp)
  }

  test("bare exit explicitly dispatches non-process shapes to the Actors provider") {
    val actorExit = new NativePlugin:
      def id: String = "60-actors-test"
      def install(context: NativePluginContext): Unit =
        context.register("actor.exit") {
          case Value.StrV(pid) :: Value.StrV(reason) :: Nil => Value.StrV(s"$pid:$reason")
          case _ => throw new IllegalArgumentException("exit(pid, reason)")
        }

    NativePluginHost.installProviders(List(OsNativePlugin(), actorExit))
    assert(call("exit", Value.StrV("worker"), Value.StrV("kill")) ==
      Value.StrV("worker:kill"))

    NativePluginHost.installProviders(List(OsNativePlugin()))
    val error = intercept[RuntimeException] {
      call("exit", Value.StrV("worker"), Value.StrV("kill"))
    }
    assert(error.getMessage.contains("requires the Actors provider"))
  }
