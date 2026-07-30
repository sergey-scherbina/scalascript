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

  // `exec` was MISSING on this tier while int/js/jvm/rust all had it, so a `.ssc` program that
  // shelled out died with `unbound global: exec` on the DEFAULT lane and passed everywhere else.
  // The conformance case `std-process-import` covers the happy path across lanes; what it cannot
  // express is the three failure modes that are silent — a >64 KB stream that deadlocks an inline
  // drain, a timeout that a blocked read makes unobservable, and an env that is not scrubbed.
  private def options(cwd: Option[String], env: Map[String, String],
                      timeoutMs: Option[Long], inheritEnv: Boolean): Value =
    Value.DataV("ProcessOptions", Vector(
      cwd.fold(Value.DataV("None", Vector.empty))(d =>
        Value.DataV("Some", Vector(Value.StrV(d)))),
      Value.MapV.from(env.map { case (k, v) => Value.StrV(k) -> Value.StrV(v) }),
      timeoutMs.fold(Value.DataV("None", Vector.empty))(ms =>
        Value.DataV("Some", Vector(Value.IntV(ms)))),
      Value.BoolV(inheritEnv)))

  private def list(values: String*): Value =
    values.foldRight(Value.DataV("Nil", Vector.empty): Value)((head, tail) =>
      Value.DataV("Cons", Vector(Value.StrV(head), tail)))

  private def fields(result: Value): (String, String, Long) = result match
    case Value.DataV("ProcessResult", IndexedSeq(Value.StrV(out), Value.StrV(err), Value.IntV(code))) =>
      (out, err, code)
    case other => fail(s"expected ProcessResult, got $other")

  test("exec reports stdout, stderr and a non-zero exit code separately") {
    NativePluginHost.installProviders(List(OsNativePlugin()))
    val (out, err, code) = fields(call("exec", Value.StrV("/bin/sh"),
      list("-c", "echo out; echo err 1>&2; exit 7"),
      options(None, Map.empty, None, true)))
    assert(out.trim == "out")
    assert(err.trim == "err")     // NOT merged into stdout — redirectErrorStream(false)
    assert(code == 7L)
  }

  test("exec drains a stream far larger than a pipe buffer without deadlocking") {
    NativePluginHost.installProviders(List(OsNativePlugin()))
    // ~220 KB, i.e. several times the ~64 KB pipe buffer. Drained inline this never returns:
    // the child blocks writing stdout while the parent blocks reading stderr.
    val (out, _, code) = fields(call("exec", Value.StrV("/bin/sh"),
      list("-c", "yes ABCDEFGHIJ | head -20000"),
      options(None, Map.empty, None, true)))
    assert(code == 0L)
    assert(out.length == 20000 * 11)
  }

  test("exec honours opts.timeout and reports the kill as -1") {
    NativePluginHost.installProviders(List(OsNativePlugin()))
    val started = System.nanoTime()
    val (out, _, code) = fields(call("exec", Value.StrV("/bin/sh"),
      list("-c", "sleep 5; echo late"),
      options(None, Map.empty, Some(300L), true)))
    val elapsedMs = (System.nanoTime() - started) / 1000000L
    assert(code == -1L)           // v1 parity: a timeout kill reports -1
    assert(!out.contains("late"))
    assert(elapsedMs < 4000L)     // it really was killed, not merely reported as killed
  }

  test("exec scrubs the parent environment when inheritEnv is false") {
    NativePluginHost.installProviders(List(OsNativePlugin()))
    val (out, _, _) = fields(call("exec", Value.StrV("/usr/bin/env"), list(),
      options(None, Map("SSC_SMOKE_PROBE" -> "kept"), None, false)))
    val names = out.linesIterator.map(_.takeWhile(_ != '=')).toSet
    assert(names == Set("SSC_SMOKE_PROBE"))
  }

  test("exec honours opts.cwd") {
    NativePluginHost.installProviders(List(OsNativePlugin()))
    val dir = Files.createTempDirectory("ssc-exec-cwd").toRealPath()
    try
      val (out, _, code) = fields(call("exec", Value.StrV("/bin/pwd"), list(),
        options(Some(dir.toString), Map.empty, None, true)))
      assert(code == 0L)
      assert(out.trim == dir.toString)
    finally Files.deleteIfExists(dir)
  }
