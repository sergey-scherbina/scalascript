package ssc

// ssc CLI — the three modes of the pipeline  ssc0 -> ir -> ssc -> cpu:
//   run     : ssc0 source -> IR -> compile-to-closures -> execute        (front built in)
//   compile : ssc0 source -> canonical Core IR bytecode (stdout)         (the ssc0 -> ir stage)
//   run-ir  : Core IR bytecode -> compile-to-closures -> execute         (pure VM)

@main def cli(args: String*): Unit = args.toList match
  case "run" :: file :: Nil =>
    val prog = Lower.module(Parser.parse(Lexer.lex(read(file))))
    println(Show.show(Runtime.run(Compiler.compile(prog), Nil)))
  case "compile" :: file :: Nil =>
    val prog = Lower.module(Parser.parse(Lexer.lex(read(file))))
    println(Writer.program(prog))
  case "run-ir" :: file :: Nil =>
    val prog = Reader.parseProgram(read(file))
    println(Show.show(Runtime.run(Compiler.compile(prog), Nil)))
  case _ =>
    Console.err.println(
      """ssc — the ssc 2.0 runtime compiler  (ssc0 -> ir -> ssc -> cpu)
        |  ssc run     <file.ssc0>     compile ssc0 to IR and run it
        |  ssc compile <file.ssc0>     emit canonical Core IR bytecode (stdout)
        |  ssc run-ir  <file.coreir>   run pre-compiled Core IR bytecode""".stripMargin)
    sys.exit(2)

private def read(path: String): String =
  scala.io.Source.fromFile(path)(using scala.io.Codec.UTF8).mkString
