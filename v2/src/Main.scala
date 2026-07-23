package ssc

// ssc CLI — the three modes of the pipeline  ssc0 -> ir -> ssc -> cpu:
//   run     : ssc0 source -> IR -> compile-to-closures -> execute        (front built in)
//   compile : ssc0 source -> canonical Core IR bytecode (stdout)         (the ssc0 -> ir stage)
//   run-ir  : Core IR bytecode -> compile-to-closures -> execute         (pure VM)

/** The VM evaluates a non-tail call by recursing on the JVM stack (~3 frames per
  * user-level call), so usable recursion depth is set by the *thread* stack — and the
  * platform default is both small and inconsistent: 1 MB on Linux/CI against 2 MB on
  * macOS. That asymmetry is what let a whole family pass locally while CI stayed red for
  * 192 consecutive runs, and it is why the launchers pass `-Xss512m`.
  *
  * Measured 2026-07-20: `ssc0c` compiling `examples/uselib.ssc0` — the largest such
  * workload in the tree — overflows at 1m and 2m and succeeds at **4m**. The 512m in the
  * launchers is a 128x overshoot of the real requirement.
  *
  * Running the pipeline on an explicitly sized thread makes a bare `java -jar ssc.jar`
  * behave identically on every platform, with no launcher flag and no reliance on an OS
  * default. `-Dssc.stackSize=<bytes>` overrides it; `0` keeps the caller's thread, which
  * is what a host embedding its own sized thread wants.
  *
  * This bounds the *runtime* recursion pragmatically. It is not a substitute for an
  * explicit continuation stack: a browser gives ~1 MB and no way to ask for more, so a
  * client-side VM still needs the evaluator change tracked in BACKLOG `site-playground`. */
private def onSizedStack(body: () => Unit): Unit =
  val requested = Option(System.getProperty("ssc.stackSize")).flatMap(_.toLongOption)
  val size = requested.getOrElse(64L * 1024 * 1024)
  if size <= 0 then body()
  else
    var escaped: Throwable = null
    val worker = new Thread(null, () => {
      try body()
      catch case t: Throwable => escaped = t
    }, "ssc-vm", size)
    worker.start()
    worker.join()
    // Re-raise on the caller so exit status and stderr stay exactly as before.
    if escaped != null then throw escaped

@main def cli(args: String*): Unit = onSizedStack(() => dispatch(args.toList))

private def dispatch(args: List[String]): Unit = args match
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
  case "freeze-capsule" :: file :: rest =>      // freeze a Portable capsule (hand-authored resume) -> file
    val frame = rest.headOption.flatMap(_.toLongOption).getOrElse(0L)
    java.nio.file.Files.writeString(
      java.nio.file.Paths.get(file),
      Capsule.encode(Term.Lit(Const.CInt(frame)), Capsule.demoResume)
    )
  case "freeze-region" :: file :: _ =>          // reify a §10.2 saveable region -> Portable capsule
    val (frame, resume) = SaveRegion.reify(SaveRegion.demoRegionSlots, SaveRegion.demoRegionResume)
    java.nio.file.Files.writeString(
      java.nio.file.Paths.get(file),
      Capsule.encode(frame, resume)
    )
  case "run-capsule" :: file :: rest =>         // admit + run a Portable capsule holding NO machine
    val inputN = rest.headOption
      .flatMap(_.toLongOption)
      .getOrElse(sys.error("run-capsule: integer input required"))
    out(Capsule.run(Capsule.decode(read(file)), inputN))
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
        |  ssc freeze-capsule <file> [frame]    freeze a Portable capsule (demo resume) to file
        |  ssc run-capsule <file> <input>       admit + run a Portable capsule (holds no machine)
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
