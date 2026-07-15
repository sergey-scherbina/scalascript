package ssc.bridge

import ssc.*

/** CLI entry point that runs a v1 .ssc file through FrontendBridge → v2 VM.
 *
 *  Usage:  sbt "v2FrontendBridge/run run <file.ssc> [args...]"
 *  Or:     java -jar bridge.jar run <file.ssc> [args...]
 *
 *  Commands:
 *    run  <file>        Parse .ssc via scalameta → Core IR → v2 VM
 *    emit <file>        Print Core IR text (for debugging) */
@main def bridgeCli(args: String*): Unit =
  args.toList match
  case "run" :: file :: rest =>
    Runtime.argv = rest  // BEFORE loadAll: the args global captures it
    PluginBridge.loadAll()
    val f    = new java.io.File(file)
    val src  = scala.io.Source.fromFile(f).mkString
    val prog = FrontendBridge.convertSource(src, Some(f.getParentFile))
    val v = Runtime.runManaged(Compiler.compile(prog), Array.empty[Value])
    v match
      case Value.UnitV => ()
      case other       => println(Show.show(other))

  case "run-module" :: file :: rest =>
    Runtime.argv = rest
    PluginBridge.loadAll()
    val module  = scalascript.parser.Parser.parse(scala.io.Source.fromFile(file).mkString)
    val prog    = ModuleBridge.convert(module)
    val v = Runtime.runManaged(Compiler.compile(prog), Array.empty[Value])
    v match
      case Value.UnitV => ()
      case other       => println(Show.show(other))

  case "emit" :: file :: rest =>
    PluginBridge.loadAll()
    val f    = new java.io.File(file)
    val src  = scala.io.Source.fromFile(f).mkString
    val prog = FrontendBridge.convertSource(src, Some(f.getParentFile))
    println(Writer.program(prog))

  // Run PRE-LOWERED Core IR (e.g. from the native scalameta-free ssc1 front)
  // through the PLUGIN-ENABLED v2 runtime. Lets us measure how much of the
  // native path actually runs once the stdlib/plugin registry is loaded —
  // separating "native VM lacks the intrinsic" from "bare kernel had no
  // registry loaded". See specs/62-scalameta-free-frontend-parity.md (K62.5).
  case "run-ir" :: file :: rest =>
    Runtime.argv = rest
    PluginBridge.loadAll()
    val prog = Reader.parseProgram(scala.io.Source.fromFile(file).mkString)
    val v = Runtime.runManaged(Compiler.compile(prog), Array.empty[Value])
    v match
      case Value.UnitV => ()
      case other       => println(Show.show(other))

  // Like run-ir, but loads the NATIVE-PRODUCTION plugin set (NativePluginHost /
  // ServiceLoader classOf[NativePlugin] — reactive/content/json/…) instead of the v1-compat
  // PluginBridge (Backend + stubs). The two plugin systems are mutually exclusive
  // (NativePluginHost.loadAll clears the registry + enforces exclusive ownership), so this is
  // the ACCURATE mirror for native-plugin-dependent tests (signals/content/json): a parity
  // "failure" under run-ir may just be run-ir using the wrong plugin system. See SPRINT §#1.
  case "run-ir-native" :: file :: rest =>
    Runtime.argv = rest
    _root_.ssc.plugin.NativePluginHost.loadAll()
    val prog = Reader.parseProgram(scala.io.Source.fromFile(file).mkString)
    val v = Runtime.runManaged(Compiler.compile(prog), Array.empty[Value])
    v match
      case Value.UnitV => ()
      case other       => println(Show.show(other))

  // K63.3: run MANY IRs through ONE JVM (avoids ~1s JVM startup per test — ~1.5-2 min over the
  // full parity audit). The manifest lists one `<ir-path>\t<out-path>` per line; each IR's stdout
  // is written to its out-path, byte-for-byte identical to a per-invocation `run-ir <ir> > out`.
  // The plugin registry is snapshotted after loadAll and RESTORED before every IR so
  // registrations don't leak between tests (same isolation V2ConformanceTest uses). io.println
  // writes via Console.out, so each IR runs inside Console.withOut (main-thread output; IRs that
  // spawn their own threads for output are the caveat — rerun those singly).
  case "run-ir-batch" :: manifest :: rest =>
    Runtime.argv = rest
    PluginBridge.loadAll()
    val snap = V2PluginRegistry.snapshot()
    val entries = scala.io.Source.fromFile(manifest).getLines()
      .map(_.trim).filter(_.nonEmpty).toList
    for entry <- entries do
      val tab = entry.indexOf('\t')
      val (irPath, outPath) =
        if tab >= 0 then (entry.substring(0, tab), entry.substring(tab + 1))
        else (entry, entry + ".out")
      V2PluginRegistry.restore(snap)
      val ps = new java.io.PrintStream(new java.io.FileOutputStream(outPath), true, "UTF-8")
      try
        Console.withOut(ps) {
          val prog = Reader.parseProgram(scala.io.Source.fromFile(irPath).mkString)
          Runtime.runManaged(Compiler.compile(prog), Array.empty[Value]) match
            case Value.UnitV => ()
            case other       => println(Show.show(other))
        }
      catch case e: Throwable => System.err.println(s"run-ir-batch: $irPath: $e")
      finally
        ps.flush(); ps.close()

  case _ =>
    System.err.println(
      """bridge-cli — v1 .ssc → FrontendBridge → v2 VM
        |  run        <file.ssc> [args...]   run via scalameta parse + v2 VM
        |  run-module <file.ssc> [args...]   run via v1 full parse pipeline + v2 VM
        |  emit       <file.ssc>             print Core IR text""".stripMargin)
    sys.exit(2)
