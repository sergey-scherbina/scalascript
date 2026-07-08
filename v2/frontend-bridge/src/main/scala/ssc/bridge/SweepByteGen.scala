package ssc.bridge
/** Coverage instrument for the bytecode lane: compile-sweeps a directory of
 *  .ssc files through emitProgram and reports the Unsupported-form census.
 *  Usage: sbt "v2FrontendBridge/runMain ssc.bridge.sweepByteGen examples" */
@main def sweepByteGen(dir: String): Unit =
  PluginBridge.loadAll()
  var ok = 0; var convFail = 0
  val unsupported = collection.mutable.HashMap[String, Int]().withDefaultValue(0)
  val files = new java.io.File(dir).listFiles().filter(_.getName.endsWith(".ssc")).sortBy(_.getName)
  for f <- files do
    try
      FrontendBridge.resetState()
      val prog = FrontendBridge.convertSource(scala.io.Source.fromFile(f).mkString, Some(f.getParentFile))
      try
        ssc.bytecode.JvmByteGen.emitProgram(prog)
        ok += 1
      catch
        case u: ssc.bytecode.Unsupported => unsupported(u.form) += 1
        case e: Throwable => unsupported(s"EMIT:${e.getClass.getSimpleName}") += 1
    catch case _: Throwable => convFail += 1
  println(s"compiled-to-bytecode: $ok / ${files.length} (conversion failures: $convFail)")
  unsupported.toList.sortBy(-_._2).foreach((form, n) => println(f"$n%5d  $form"))
