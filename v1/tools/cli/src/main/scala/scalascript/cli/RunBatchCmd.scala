package scalascript.cli

/** `ssc run-batch [--emit-js] --delim <marker> <file.ssc>…` — run MANY .ssc files
 *  in ONE JVM, printing `<marker><baseName>` before each file's output.
 *
 *  Purpose: the conformance runner (tests/conformance/run.sc) previously paid a
 *  cold JVM start per case per lane (≈ 3 × corpus process spawns;
 *  specs/conformance-perf.md F4). With run-batch the INT lane is one JVM for the
 *  whole corpus and the JS lane emits all sources in one JVM.
 *
 *  Contract:
 *   - the marker line is written to STDOUT and flushed before each case;
 *   - a case that throws is reported (message on its own output) and the batch
 *     CONTINUES with the next file;
 *   - a case that calls exit() terminates the JVM (unavoidable) — consumers must
 *     fall back to per-case runs for files missing from the batch output.
 *  `--emit-js` dispatches `emit-js` per file instead of `run`. */
final class RunBatchCmd extends CliCommand:
  def name = "run-batch"
  override def summary = "Run many .ssc files in one JVM (delimiter-separated outputs)"
  override def category = "Run & develop"

  def run(args: List[String]): Unit =
    var delim: String = "<<<SSC-BATCH-CASE:"
    var emitJs = false
    val files = scala.collection.mutable.ListBuffer.empty[String]
    var rest = args
    while rest.nonEmpty do
      rest match
        case "--delim" :: v :: tail => delim = v; rest = tail
        case "--emit-js" :: tail    => emitJs = true; rest = tail
        case f :: tail              => files += f; rest = tail
        case Nil                    => ()
    if files.isEmpty then
      System.err.println("run-batch: no files given"); System.exit(2)
    val sub = if emitJs then "emit-js" else "run"
    for f <- files.toList do
      val base = f.split('/').last.stripSuffix(".ssc")
      println(s"$delim$base")
      Console.out.flush()
      try
        if !os.exists(os.Path(f, os.pwd)) then println(s"run-batch: file not found: $f")
        else CommandRegistry.dispatch(sub, List(f))
      catch
        case e: Throwable =>
          println(s"run-batch: ${e.getClass.getSimpleName}: ${e.getMessage}")
      Console.out.flush()
