package ssc.plugin.os

import java.nio.file.{Files, Paths}
import ssc.{Runtime, V2PluginRegistry, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free JVM environment/path provider for the standard ScalaScript 2.1 runtime. */
final class OsNativePlugin extends NativePlugin:
  def id: String = "20-os"

  private def text(args: List[Value], index: Int, operation: String): String = args.lift(index) match
    case Some(Value.StrV(value)) => value
    case _ => throw new RuntimeException(s"$operation argument ${index + 1} must be String")

  private def integer(args: List[Value], index: Int, operation: String): Long = args.lift(index) match
    case Some(Value.IntV(value)) => value
    case _ => throw new RuntimeException(s"$operation argument ${index + 1} must be Int")

  private def strings(value: Value, operation: String = "pathJoin"): List[String] =
    val out = collection.mutable.ListBuffer.empty[String]
    var current = value
    var done = false
    while !done do
      current match
        case Value.DataV("Cons", Seq(Value.StrV(value), rest)) =>
          out += value
          current = rest
        case Value.DataV("Nil", _) => done = true
        case _ => throw new RuntimeException(s"$operation arguments must be String")
    out.toList

  private def pathParts(args: List[Value]): List[String] = args match
    case List(value @ Value.DataV("Cons" | "Nil", _)) => strings(value)
    case values => values.zipWithIndex.map { case (value, index) => value match
      case Value.StrV(text) => text
      case _ => throw new RuntimeException(s"pathJoin argument ${index + 1} must be String")
    }

  private def native(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, -1)(fn)

  private def exit(args: List[Value]): Value = args match
    case Value.IntV(code) :: Nil => Runtime.exitHandler(code.toInt)
    case _ =>
      V2PluginRegistry.lookup("actor.exit") match
        case Some(actorExit) => actorExit(args)
        case None => throw new RuntimeException(
          "exit expects exit(code: Int); actor exit(pid, reason) requires the Actors provider")

  /** `std.process.exec` on the v2 native tier.
   *
   *  `std/process.ssc` declares `extern def exec` and the int, js, jvm and rust lanes each
   *  implement it — the native tier did not, so any `.ssc` program that shells out died with
   *  `unbound global: exec` on the DEFAULT lane while passing everywhere else. Found by writing
   *  the smoke-CI runner in `.ssc`: a CI runner is subprocesses and nothing else, so the lane it
   *  was meant to dogfood could not host it.
   *
   *  Semantics are the v1 plugin's, verbatim in shape (`OsIntrinsics.exec`), including the part
   *  that is easy to get wrong: stdout AND stderr are drained on their own threads. Reading either
   *  stream to EOF inline blocks until the child exits, which deadlocks on >64 KB of output and
   *  defeats `opts.timeout` (the read cannot return before the process it is timing has finished).
   */
  private def exec(args: List[Value]): Value =
    def result(stdout: String, stderr: String, code: Long): Value =
      // Positional, in `case class ProcessResult(stdout, stderr, exitCode)` declaration order —
      // v2 field access is by INDEX, so the order here IS the ABI.
      Value.DataV("ProcessResult", Vector(Value.StrV(stdout), Value.StrV(stderr), Value.IntV(code)))

    args match
      case cmdV :: argsV :: rest =>
        val cmd  = cmdV match
          case Value.StrV(value) => value
          case _ => throw new RuntimeException("exec argument 1 must be String")
        val argv = strings(argsV, "exec")
        // `opts` is a ProcessOptions instance: (cwd, env, timeout, inheritEnv), again positional.
        val fields = rest.headOption match
          case Some(Value.DataV("ProcessOptions", fs)) => fs
          case _                                       => IndexedSeq.empty[Value]
        def field(index: Int): Option[Value] = fields.lift(index)
        val cwd = field(0) match
          case Some(Value.DataV("Some", Seq(Value.StrV(dir)))) => Some(dir)
          case _                                               => None
        val env = field(1) match
          case Some(Value.MapV(entries)) => entries.collect {
            case (Value.StrV(key), Value.StrV(value)) => key -> value
          }.toMap
          case _ => Map.empty[String, String]
        val timeoutMs = field(2) match
          case Some(Value.DataV("Some", Seq(Value.IntV(ms)))) => Some(ms)
          case _                                              => None
        val inheritEnv = field(3) match
          case Some(Value.BoolV(value)) => value
          case _                        => true

        val builder = new ProcessBuilder((cmd :: argv)*)
        builder.redirectErrorStream(false)
        cwd.foreach(dir => builder.directory(new java.io.File(dir)))
        if !inheritEnv then builder.environment().clear()
        if env.nonEmpty then
          val target = builder.environment()
          env.foreach { case (key, value) => target.put(key, value) }
        val process = builder.start()
        process.getOutputStream.close()   // no stdin: a child that reads would otherwise hang
        val outBuf = new java.util.concurrent.atomic.AtomicReference[String]("")
        val errBuf = new java.util.concurrent.atomic.AtomicReference[String]("")
        val outT = new Thread(() =>
          outBuf.set(new String(process.getInputStream.readAllBytes(), "UTF-8")))
        val errT = new Thread(() =>
          errBuf.set(new String(process.getErrorStream.readAllBytes(), "UTF-8")))
        outT.setDaemon(true); errT.setDaemon(true)
        outT.start(); errT.start()
        val code = timeoutMs match
          case Some(ms) =>
            if process.waitFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS) then
              process.exitValue().toLong
            else
              process.destroyForcibly()
              process.waitFor()
              -1L                          // v1 parity: a timeout kill reports -1, not the signal
          case None => process.waitFor().toLong
        outT.join(1000); errT.join(1000)
        result(outBuf.get(), errBuf.get(), code)

      case _ => result("", "", 1L)

  def install(context: NativePluginContext): Unit =
    native(context, "exec")(exec)
    native(context, "env") { args =>
      Option(System.getenv(text(args, 0, "env"))) match
        case Some(value) => Value.DataV("Some", Vector(Value.StrV(value)))
        case None => Value.DataV("None", Vector.empty)
    }
    native(context, "envOrElse") { args =>
      Value.StrV(Option(System.getenv(text(args, 0, "envOrElse")))
        .getOrElse(text(args, 1, "envOrElse")))
    }
    // One line from stdin, `None` at EOF — the DEFAULT lane's half of `std.os.readLine`.
    // `scala.io.StdIn.readLine()` yields null at end of input, and that null is exactly the
    // case the Option exists for: an empty string would conflate "user pressed enter" with
    // "input is over", and a REPL needs to tell those apart to know when to stop.
    native(context, "readLine") { _ =>
      val line = scala.io.StdIn.readLine()
      if line == null then Value.DataV("None", Vector.empty)
      else Value.DataV("Some", Vector(Value.StrV(line)))
    }
    native(context, "exit")(exit)
    // Monotonic clock nanoseconds (System.nanoTime parity with the interp core
    // builtin). Used for job ids / elapsed timing; not wall-clock.
    native(context, "nanoTime") { _ => Value.IntV(System.nanoTime()) }
    // The QUALIFIED spellings. `System.nanoTime()` is a core builtin on the v1
    // interpreter, but on the native lane the front emits a method call on the
    // bare `System` receiver: Runtime.methodOp sees DataV("System", []), finds no
    // "System.nanoTime" native, and PERFORMS an effect nobody handles — the
    // program dies with `unhandled runtime effect: System.nanoTime`. Registering
    // the tag-qualified name is the same shape EffectRunnersNativePlugin uses for
    // `Random.uuid`; methodOp checks V2PluginRegistry.lookup(s"$tag.$name")
    // BEFORE it falls through to perform, so this resolves in place.
    // Found by `ssc bench --backend v2`, whose generated wrapper times itself with
    // System.nanoTime(): the whole v2 bench lane had been reporting `n/a` for this.
    context.register("System.nanoTime") { _ => Value.IntV(System.nanoTime()) }
    context.register("System.currentTimeMillis") { _ => Value.IntV(System.currentTimeMillis()) }
    native(context, "pathJoin") { args =>
      pathParts(args) match
        case Nil => Value.StrV(".")
        case head :: Nil => Value.StrV(head)
        case head :: tail => Value.StrV(Paths.get(head, tail*).toString)
    }
    native(context, "pathDirname") { args =>
      val parent = Paths.get(text(args, 0, "pathDirname")).getParent
      Value.StrV(if parent == null then "." else parent.toString)
    }
    native(context, "pathBasename") { args =>
      val name = Paths.get(text(args, 0, "pathBasename")).getFileName
      Value.StrV(if name == null then "" else name.toString)
    }
    native(context, "pathExtname") { args =>
      val fileName = Paths.get(text(args, 0, "pathExtname")).getFileName
      if fileName == null then Value.StrV("")
      else
        val name = fileName.toString
        val dot = name.lastIndexOf('.')
        Value.StrV(if dot > 0 then name.substring(dot) else "")
    }
    native(context, "pathResolve") { args =>
      Value.StrV(Paths.get(text(args, 0, "pathResolve")).toAbsolutePath.normalize.toString)
    }
    native(context, "pathIsAbsolute") { args =>
      Value.BoolV(Paths.get(text(args, 0, "pathIsAbsolute")).isAbsolute)
    }
    native(context, "tempFile") { args =>
      Value.StrV(Files.createTempFile(text(args, 0, "tempFile"), text(args, 1, "tempFile")).toString)
    }

    context.registerValue("tempDir", Value.StrV(System.getProperty("java.io.tmpdir", "/tmp")))
    context.registerValue("homedir", Value.StrV(System.getProperty("user.home", "/")))
    val hostname = try java.net.InetAddress.getLocalHost.getHostName
      catch case _: Throwable => "localhost"
    context.registerValue("hostname", Value.StrV(hostname))
