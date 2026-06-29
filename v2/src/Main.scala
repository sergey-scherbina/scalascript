package ssc

// ssc CLI — the three modes of the pipeline  ssc0 -> ir -> ssc -> cpu:
//   run     : ssc0 source -> IR -> compile-to-closures -> execute        (front built in)
//   compile : ssc0 source -> canonical Core IR bytecode (stdout)         (the ssc0 -> ir stage)
//   run-ir  : Core IR bytecode -> compile-to-closures -> execute         (pure VM)

@main def cli(args: String*): Unit = args.toList match
  case "run" :: file :: rest =>                 // trailing args -> the program's #io.args()
    Runtime.argv = rest
    val prog = Lower.module(Loader.load(file))  // Loader resolves `import`s
    out(Runtime.run(Compiler.compile(prog), Array.empty[Value]))
  case "compile" :: file :: Nil =>
    val prog = Lower.module(Loader.load(file))
    println(Writer.program(prog))
  case "run-ir" :: file :: rest =>              // same argv forwarding for raw bytecode
    Runtime.argv = rest
    val prog = Reader.parseProgram(read(file))
    out(Runtime.run(Compiler.compile(prog), Array.empty[Value]))
  case _ =>
    Console.err.println(
      """ssc — the ssc 2.0 runtime compiler  (ssc0 -> ir -> ssc -> cpu)
        |  ssc run     <file.ssc0> [args...]    compile ssc0 to IR and run it
        |  ssc compile <file.ssc0>              emit canonical Core IR bytecode (stdout)
        |  ssc run-ir  <file.coreir> [args...]  run pre-compiled Core IR bytecode
        |
        |  program args are read inside the program via #io.args()""".stripMargin)
    sys.exit(2)

// print a program's result, but stay silent on Unit (so io.print output is clean —
// e.g. a program that emits Core IR bytecode via #coreir.encode + #io.print)
private def out(v: Value): Unit = v match
  case Value.UnitV => ()
  case _ => println(Show.show(v))

private def read(path: String): String =
  scala.io.Source.fromFile(path)(using scala.io.Codec.UTF8).mkString
