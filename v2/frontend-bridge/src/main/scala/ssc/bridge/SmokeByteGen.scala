package ssc.bridge
// TEMPORARY smoke for the bytecode milestone (kept until a real CLI flag lands)
@main def smokeByteGen(file: String): Unit =
  PluginBridge.loadAll()
  val f = new java.io.File(file)
  val prog = FrontendBridge.convertSource(scala.io.Source.fromFile(f).mkString, Some(f.getParentFile))
  val (_, globals) = ssc.Compiler.compileWithGlobals(prog)
  ssc.Emit.globalsRef = globals
  try
    val bytes = ssc.bytecode.JvmByteGen.emitProgram(prog)
    println(s"[bytegen] entry bytecode: ${bytes.length} bytes")
    val v = ssc.bytecode.JvmByteGen.runProgram(bytes)
    println(s"[bytegen] result: ${ssc.Show.show(v)}")
  catch
    case u: ssc.bytecode.Unsupported => println(s"[bytegen] UNSUPPORTED: ${u.form}")
    case e: Throwable =>
      val root = { var c: Throwable = e; while c.getCause != null do c = c.getCause; c }
      println(s"[bytegen] ERROR: ${root.getClass.getSimpleName}: ${root.getMessage}")
      root.getStackTrace.take(5).foreach(f => println(s"[bytegen]   at $f"))
