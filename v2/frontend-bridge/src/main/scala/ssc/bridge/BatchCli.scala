package ssc.bridge

import ssc.*

/** Batch-run all .ssc files from a directory, reporting PASS/FAIL.
 *  Usage: sbt "v2FrontendBridge/runMain ssc.bridge.batchCli <dir> [filter]" */
/** Thrown instead of a real process exit while batch-running (see Runtime.exitHandler). */
private final class BatchExit(val code: Int) extends RuntimeException(s"exit($code)")

@main def batchCli(args: String*): Unit =
  PluginBridge.loadAll()
  PluginBridge.installBatchStubs()
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
  var pass = 0; var fail = 0; var skip = 0
  // Front-matter `backend: jvm` declares a JVM-codegen-lane example
  // (scala imports like scalascript.typeddata.* — nothing the v2 VM can
  // honestly run). Running them here either false-passed (the unresolved
  // call chain stayed a lazy Free Op and exit-0'd without executing) or
  // false-failed the v2 gate; skip them with an explicit marker instead.
  def declaredBackend(f: java.io.File): Option[String] =
    val head = scala.util.Try(scala.io.Source.fromFile(f).getLines().take(15).toList).getOrElse(Nil)
    head.collectFirst { case s if s.startsWith("backend:") => s.stripPrefix("backend:").trim }
  // Snapshot the plugin registry ONCE after loadAll; restore before each file so
  // runtime registrations (databases, cells, namespaces) can't leak across files.
  val registrySnap = V2PluginRegistry.snapshot()
  // Per-file watchdog: a test that blocks forever (e.g. a collector stuck in
  // `receive` after its workers never got messages) used to hang the WHOLE
  // batch — there was no timeout in the shipped loop. Override with
  // SSC_BATCH_TIMEOUT_MS; a timed-out test is reported as FAIL(TIMEOUT).
  val timeoutMs = sys.env.get("SSC_BATCH_TIMEOUT_MS").flatMap(_.toLongOption).getOrElse(60000L)
  for f <- files do
    val decl = declaredBackend(f)
    if decl.exists(d => Set("jvm", "spark", "js", "rust", "wasm").exists(d.contains)) then
      println(s"SKIP ${f.getName} (backend: ${decl.get})")
      skip += 1
    else {
    V2PluginRegistry.restore(registrySnap)
    FrontendBridge.resetState()  // clear per-compilation state so examples don't cross-pollinate
    val resultRef = new java.util.concurrent.atomic.AtomicReference[scala.util.Try[Unit]](null)
    val worker = Thread.ofVirtual().start { () =>
      resultRef.set(scala.util.Try {
        val src  = scala.io.Source.fromFile(f).mkString
        val prog = FrontendBridge.convertSource(src, Some(f.getParentFile))
        Runtime.runManaged(Compiler.compile(prog), Array.empty[Value])
        ()
      })
    }
    worker.join(timeoutMs)
    val result =
      if resultRef.get() == null then
        worker.interrupt()
        scala.util.Failure(new RuntimeException(s"TIMEOUT after ${timeoutMs}ms"))
      else resultRef.get()
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
    }
  println(s"=== PASS: $pass  FAIL: $fail  SKIP(lane): $skip  TOTAL: ${pass + fail} ===")
