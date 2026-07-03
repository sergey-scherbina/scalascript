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
@main def bridgeCli(args: String*): Unit = args.toList match
  case "run" :: file :: rest =>
    Runtime.argv = rest
    val src  = scala.io.Source.fromFile(file).mkString
    val prog = FrontendBridge.convertSource(src)
    val v = Runtime.run(Compiler.compile(prog), Array.empty[Value])
    v match
      case Value.UnitV => ()
      case other       => println(Show.show(other))

  case "run-module" :: file :: rest =>
    Runtime.argv = rest
    val module  = scalascript.parser.Parser.parse(scala.io.Source.fromFile(file).mkString)
    val prog    = ModuleBridge.convert(module)
    val v = Runtime.run(Compiler.compile(prog), Array.empty[Value])
    v match
      case Value.UnitV => ()
      case other       => println(Show.show(other))

  case "emit" :: file :: rest =>
    val src  = scala.io.Source.fromFile(file).mkString
    val prog = FrontendBridge.convertSource(src)
    println(Writer.program(prog))

  case _ =>
    System.err.println(
      """bridge-cli — v1 .ssc → FrontendBridge → v2 VM
        |  run        <file.ssc> [args...]   run via scalameta parse + v2 VM
        |  run-module <file.ssc> [args...]   run via v1 full parse pipeline + v2 VM
        |  emit       <file.ssc>             print Core IR text""".stripMargin)
    sys.exit(2)
