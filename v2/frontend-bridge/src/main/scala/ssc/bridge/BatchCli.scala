package ssc.bridge

import ssc.*

/** Batch-run all .ssc files from a directory, reporting PASS/FAIL.
 *  Usage: sbt "v2FrontendBridge/runMain ssc.bridge.batchCli <dir> [filter]" */
/** Thrown instead of a real process exit while batch-running (see Runtime.exitHandler). */
private final class BatchExit(val code: Int) extends RuntimeException(s"exit($code)")

@main def batchCli(args: String*): Unit =
  PluginBridge.loadAll()
  // A program's exit(0) must not kill the batch JVM (actors-pingpong ends with
  // exit() — this silently killed sbt and looked like a "hang"). Exit code 0
  // counts as PASS; nonzero as FAIL.
  Runtime.exitHandler = code => throw new BatchExit(code)
  val dir = new java.io.File(args.headOption.getOrElse("examples"))
  val filterArg = args.lift(1).getOrElse("")
  // T4.5 (2026-07-05): the historical hang-list is GONE — all 16 entries were
  // re-probed with a per-file watchdog and every one TERMINATES now (the actor
  // dead-flag/interrupt fixes and the Dataset executor landed since the list was
  // written; 2 listed files no longer exist). Kept as an empty escape hatch.
  val hangingExamples = Set.empty[String]
  val files = dir.listFiles()
    .filter(f => f.getName.endsWith(".ssc") && !hangingExamples.contains(f.getName))
    .filter(f => filterArg.isEmpty || f.getName.contains(filterArg))
    .sortBy(_.getName)
  var pass = 0; var fail = 0
  // Snapshot the plugin registry ONCE after loadAll; restore before each file so
  // runtime registrations (databases, cells, namespaces) can't leak across files.
  val registrySnap = V2PluginRegistry.snapshot()
  for f <- files do
    V2PluginRegistry.restore(registrySnap)
    FrontendBridge.resetState()  // clear per-compilation state so examples don't cross-pollinate
    val result = scala.util.Try {
      val src  = scala.io.Source.fromFile(f).mkString
      val prog = FrontendBridge.convertSource(src, Some(f.getParentFile))
      Runtime.run(Compiler.compile(prog), Array.empty[Value])
    }
    result match
      case scala.util.Success(_) =>
        println(s"PASS ${f.getName}")
        pass += 1
      case scala.util.Failure(be: BatchExit) if be.code == 0 =>
        println(s"PASS ${f.getName} (exit 0)")
        pass += 1
      case scala.util.Failure(e) =>
        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName).linesIterator.next().take(100)
        println(s"FAIL ${f.getName}: $msg")
        if System.getProperty("batchDebug") != null then e.printStackTrace()
        fail += 1
  println(s"=== PASS: $pass  FAIL: $fail  TOTAL: ${pass + fail} ===")
