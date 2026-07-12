package scalascript.compiler.plugin.os

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginError, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Lst, Num, Bool, Inst, Opt, MapVal}

import java.nio.file.Paths

object OsIntrinsics:

  private def native(f: List[Any] => PluginValue): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(f(args.map(_.unwrap)))
    }

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // ── std.os ──────────────────────────────────────────────────────────────

    QualifiedName("env") -> native {
      case List(key: String) =>
        val v = System.getenv(key)
        if v == null then PluginValue.none else PluginValue.some(PluginValue.string(v))
      case _ => PluginValue.none
    },

    QualifiedName("envOrElse") -> native {
      case List(key: String, default: String) =>
        val v = System.getenv(key)
        PluginValue.string(if v != null then v else default)
      case _ => PluginValue.string("")
    },

    QualifiedName("args") -> native { _ =>
      val args = sys.props.getOrElse("ssc.args", "").split("\u0000").filter(_.nonEmpty)
      PluginValue.list(args.map(PluginValue.string).toList)
    },

    QualifiedName("exit") -> native {
      case List(code: Long) => sys.exit(code.toInt)
      case List(code: Int)  => sys.exit(code)
      case _                => PluginError.raise("exit(code: Int)")
    },

    QualifiedName("cwd") -> native { _ =>
      PluginValue.string(System.getProperty("user.dir", "."))
    },

    QualifiedName("sep") -> native { _ =>
      PluginValue.string(java.io.File.separator)
    },

    QualifiedName("pathJoin") -> native { parts =>
      val strs = parts.map {
        case s: String => s
        case v: List[?] => v.map(_.toString).mkString(java.io.File.separator)
        case other => other.toString
      }
      strs match
        case Nil      => PluginValue.string(".")
        case h :: Nil => PluginValue.string(h)
        case h :: t   => PluginValue.string(Paths.get(h, t*).toString)
    },

    QualifiedName("pathDirname") -> native {
      case List(p: String) =>
        val parent = Paths.get(p).getParent
        PluginValue.string(if parent == null then "." else parent.toString)
      case _ => PluginValue.string(".")
    },

    QualifiedName("pathBasename") -> native {
      case List(p: String) =>
        val fn = Paths.get(p).getFileName
        PluginValue.string(if fn == null then "" else fn.toString)
      case _ => PluginValue.string("")
    },

    QualifiedName("pathExtname") -> native {
      case List(p: String) =>
        val fn = Paths.get(p).getFileName
        if fn == null then PluginValue.string("")
        else
          val name = fn.toString
          val dot  = name.lastIndexOf('.')
          PluginValue.string(if dot > 0 then name.substring(dot) else "")
      case _ => PluginValue.string("")
    },

    QualifiedName("pathResolve") -> native {
      case List(p: String) =>
        PluginValue.string(Paths.get(p).toAbsolutePath.normalize.toString)
      case _ => PluginValue.string(System.getProperty("user.dir", "."))
    },

    QualifiedName("pathIsAbsolute") -> native {
      case List(p: String) => PluginValue.bool(Paths.get(p).isAbsolute)
      case _               => PluginValue.bool(false)
    },

    QualifiedName("tempDir") -> native { _ =>
      PluginValue.string(System.getProperty("java.io.tmpdir", "/tmp"))
    },

    QualifiedName("tempFile") -> native {
      case List(prefix: String, suffix: String) =>
        PluginValue.string(java.nio.file.Files.createTempFile(prefix, suffix).toString)
      case _ =>
        PluginValue.string(java.nio.file.Files.createTempFile("ssc", ".tmp").toString)
    },

    QualifiedName("platform") -> native { _ =>
      PluginValue.instance("Jvm", Map.empty)
    },

    QualifiedName("homedir") -> native { _ =>
      PluginValue.string(System.getProperty("user.home", "/"))
    },

    QualifiedName("hostname") -> native { _ =>
      try PluginValue.string(java.net.InetAddress.getLocalHost.getHostName)
      catch case _: Throwable => PluginValue.string("localhost")
    },

    // ── std.process ─────────────────────────────────────────────────────────

    QualifiedName("exec") -> native {
      // The runtime hands args in MIXED form: primitives unwrap to Scala (String/Long),
      // but a list stays as a raw ListV Value. So bind the 3 positions and coerce each
      // with the Str/Lst extractors (which `wrap` either form) rather than type-testing
      // `cmd: String` / `argsList: List[?]` — the latter silently missed the ListV and
      // fell through to the exit-1 stub.
      case List(c, a, o) =>
        val cmd  = Str.unapply(c).getOrElse(c.toString)
        val args = Lst.unapply(a).getOrElse(Nil).flatMap(Str.unapply)
        // ProcessOptions fields — the interp previously ignored opts entirely, so
        // cwd/env/timeout/inheritEnv were silently dropped on `ssc run`.
        val f: Map[String, PluginValue] = o match { case Inst(_, fs) => fs; case _ => Map.empty }
        val cwd        = f.get("cwd").flatMap(Opt.unapply).flatten.flatMap(Str.unapply)
        val env        = f.get("env").flatMap(MapVal.unapply).getOrElse(Map.empty).flatMap {
                           case (Str(k), Str(v)) => Some(k -> v); case _ => None }
        val timeoutMs  = f.get("timeout").flatMap(Opt.unapply).flatten.flatMap(Num.unapply)
        val inheritEnv = f.get("inheritEnv").flatMap(Bool.unapply).getOrElse(true)
        val pb   = new ProcessBuilder((cmd :: args)*)
        pb.redirectErrorStream(false)
        cwd.foreach(d => pb.directory(new java.io.File(d)))                 // honor opts.cwd
        if !inheritEnv then pb.environment().clear()                        // L3: scrub parent env
        if env.nonEmpty then { val e = pb.environment(); env.foreach { case (k, v) => e.put(k, v) } }
        val proc = pb.start()
        // M4/M5: drain stdout AND stderr on threads. Reading a stream to EOF blocks
        // until the child exits — inline it would deadlock on >64KB stderr AND defeat
        // opts.timeout (the read wouldn't return until the process already finished).
        val outBuf = new java.util.concurrent.atomic.AtomicReference[String]("")
        val errBuf = new java.util.concurrent.atomic.AtomicReference[String]("")
        val outT = new Thread(() =>
          outBuf.set(scala.io.Source.fromInputStream(proc.getInputStream).mkString))
        val errT = new Thread(() =>
          errBuf.set(scala.io.Source.fromInputStream(proc.getErrorStream).mkString))
        outT.setDaemon(true); errT.setDaemon(true)
        outT.start(); errT.start()
        val code   = timeoutMs match                                       // M4: honor opts.timeout
          case Some(ms) =>
            if proc.waitFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS) then proc.exitValue()
            else { proc.destroyForcibly(); proc.waitFor(); -1 }
          case None => proc.waitFor()
        outT.join(1000); errT.join(1000)
        val stdout = outBuf.get()
        val stderr = errBuf.get()
        PluginValue.instance("ProcessResult", Map(
          "stdout"   -> PluginValue.string(stdout),
          "stderr"   -> PluginValue.string(stderr),
          "exitCode" -> PluginValue.int(code),
        ))
      case _ => PluginValue.instance("ProcessResult", Map(
        "stdout" -> PluginValue.string(""), "stderr" -> PluginValue.string(""), "exitCode" -> PluginValue.int(1)
      ))
    },

  )
