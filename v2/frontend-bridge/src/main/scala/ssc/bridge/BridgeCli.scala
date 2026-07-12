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
    val v = Runtime.run(Compiler.compile(prog), Array.empty[Value])
    v match
      case Value.UnitV => ()
      case other       => println(Show.show(other))

  case "run-module" :: file :: rest =>
    Runtime.argv = rest
    PluginBridge.loadAll()
    val module  = scalascript.parser.Parser.parse(scala.io.Source.fromFile(file).mkString)
    val prog    = ModuleBridge.convert(module)
    val v = Runtime.run(Compiler.compile(prog), Array.empty[Value])
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
    val v = Runtime.run(Compiler.compile(prog), Array.empty[Value])
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
    val v = Runtime.run(Compiler.compile(prog), Array.empty[Value])
    v match
      case Value.UnitV => ()
      case other       => println(Show.show(other))

  case _ =>
    System.err.println(
      """bridge-cli — v1 .ssc → FrontendBridge → v2 VM
        |  run        <file.ssc> [args...]   run via scalameta parse + v2 VM
        |  run-module <file.ssc> [args...]   run via v1 full parse pipeline + v2 VM
        |  emit       <file.ssc>             print Core IR text""".stripMargin)
    sys.exit(2)
