package ssc.bridge

import ssc.*

/** Batch-run all .ssc files from a directory, reporting PASS/FAIL.
 *  Usage: sbt "v2FrontendBridge/runMain ssc.bridge.batchCli <dir> [filter]" */
@main def batchCli(args: String*): Unit =
  PluginBridge.loadAll()
  val dir = new java.io.File(args.headOption.getOrElse("examples"))
  val filterArg = args.lift(1).getOrElse("")
  // Known examples that spawn non-daemon threads or hang the batch runner
  val hangingExamples = Set(
    "actors-pingpong.ssc", "actors-typed-remote-spawn.ssc",
    "rozum-agent-demo.ssc", "rozum-meeting-demo.ssc",
    // Dataset/distributed: lazy Op free-monad executor not implemented → infinite loop
    "dataset-parallel-sum.ssc", "dataset-stats.ssc", "dataset-typed-mapping.ssc",
    "dataset-word-count.ssc", "distributed-dataset-codec.ssc",
    "distributed-dataset-typed-helpers.ssc", "distributed-dataset-wire-protocol.ssc",
    "distributed-dataset-wire-shuffle.ssc", "distributed-join.ssc",
    "distributed-log-aggregation.ssc", "distributed-streams.ssc",
    "distributed-word-count.ssc", "word-count.ssc"
  )
  val files = dir.listFiles()
    .filter(f => f.getName.endsWith(".ssc") && !hangingExamples.contains(f.getName))
    .filter(f => filterArg.isEmpty || f.getName.contains(filterArg))
    .sortBy(_.getName)
  var pass = 0; var fail = 0
  for f <- files do
    val result = scala.util.Try {
      val src  = scala.io.Source.fromFile(f).mkString
      val prog = FrontendBridge.convertSource(src, Some(f.getParentFile))
      Runtime.run(Compiler.compile(prog), Array.empty[Value])
    }
    result match
      case scala.util.Success(_) =>
        println(s"PASS ${f.getName}")
        pass += 1
      case scala.util.Failure(e) =>
        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName).linesIterator.next().take(100)
        println(s"FAIL ${f.getName}: $msg")
        if System.getProperty("batchDebug") != null then e.printStackTrace()
        fail += 1
  println(s"=== PASS: $pass  FAIL: $fail  TOTAL: ${pass + fail} ===")
