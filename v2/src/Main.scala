package ssc

// ssc CLI — the three modes of the pipeline  ssc0 -> ir -> ssc -> cpu:
//   run     : ssc0 source -> IR -> compile-to-closures -> execute        (front built in)
//   compile : ssc0 source -> canonical Core IR bytecode (stdout)         (the ssc0 -> ir stage)
//   run-ir  : Core IR bytecode -> compile-to-closures -> execute         (pure VM)

@main def cli(args: String*): Unit = args.toList match
  case "run" :: file :: rest =>                 // trailing args -> the program's #io.args()
    Runtime.argv = rest
    val prog = Lower.module(Loader.load(file))  // Loader resolves `import`s
    out(Runtime.runManaged(Compiler.compile(prog), Array.empty[Value]))
  case "compile" :: file :: Nil =>
    val prog = Lower.module(Loader.load(file))
    println(Writer.program(prog))
  case "run-ir" :: file :: rest =>              // same argv forwarding for raw bytecode
    Runtime.argv = rest
    val prog = Reader.parseProgram(read(file))
    out(Runtime.runManaged(Compiler.compile(prog), Array.empty[Value]))
  case "bench-ir" :: file :: rest =>            // in-process bench: warmup + timed reps, print median ms
    val warmup = intArg(rest, "--warmup", 10)
    val reps   = intArg(rest, "--reps",  100)
    val prog   = Reader.parseProgram(read(file))
    val (_, globals) = Compiler.compileWithGlobals(prog)
    val fn = globals.get("main").orElse(globals.get("workload"))
      .getOrElse(sys.error("bench-ir: no 'main' or 'workload' def"))
      .asInstanceOf[Value.ClosV]
    // suppress program stdout (io.println etc.) during all bench runs
    val devNull = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    def call(): Unit = Console.withOut(devNull)(Runtime.runManaged(fn.code, fn.env))
    for _ <- 1 to warmup do call()
    val times = Array.ofDim[Long](reps)
    for i <- 0 until reps do
      val t0 = System.nanoTime(); call(); times(i) = System.nanoTime() - t0
    java.util.Arrays.sort(times)
    val medNs = if reps % 2 == 0 then (times(reps/2-1) + times(reps/2)) / 2 else times(reps/2)
    println(f"${medNs / 1e6}%.4f")
  case _ =>
    Console.err.println(
      """ssc — the ssc 2.0 runtime compiler  (ssc0 -> ir -> ssc -> cpu)
        |  ssc run     <file.ssc0> [args...]    compile ssc0 to IR and run it
        |  ssc compile <file.ssc0>              emit canonical Core IR bytecode (stdout)
        |  ssc run-ir  <file.coreir> [args...]  run pre-compiled Core IR bytecode
        |  ssc bench-ir <file.coreir> [--warmup N] [--reps N]
        |                                        in-process bench: prints median ms/op
        |
        |  program args are read inside the program via #io.args()""".stripMargin)
    sys.exit(2)

private def intArg(args: List[String], flag: String, default: Int): Int =
  val idx = args.indexOf(flag)
  if idx >= 0 && idx + 1 < args.length then args(idx + 1).toIntOption.getOrElse(default)
  else default

// print a program's result, but stay silent on Unit (so io.print output is clean —
// e.g. a program that emits Core IR bytecode via #coreir.encode + #io.print)
private def out(v: Value): Unit = v match
  case Value.UnitV => ()
  case _ => println(Show.show(v))

private def read(path: String): String =
  scala.io.Source.fromFile(path)(using scala.io.Codec.UTF8).mkString
