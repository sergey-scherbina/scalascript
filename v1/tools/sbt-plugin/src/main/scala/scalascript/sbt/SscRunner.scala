package scalascript.sbt

import sbt._

object SscRunner {
  def run(binary: String, args: Seq[String], log: Logger): Unit = {
    val cmd = binary +: args
    log.info(s"[ssc] ${cmd.mkString(" ")}")
    val rc = scala.sys.process.Process(cmd) ! log
    if (rc != 0) sys.error(s"ssc failed with exit code $rc")
  }

  /** Runs ssc and returns its STDOUT, with stderr still going to the log.
   *
   *  For commands that print their product rather than writing it: `emit-spa` renders the SPA HTML
   *  to stdout and creates no file at all. A build tool has to name the artifact it produced, so the
   *  task captures the output and decides where it lands, rather than guessing at a location the CLI
   *  never chose.
   *
   *  stderr is NOT captured: diagnostics belong in the build log, and folding them into the returned
   *  string would silently paste them into the generated file. */
  def runCapture(binary: String, args: Seq[String], log: Logger): String = {
    val cmd = binary +: args
    log.info(s"[ssc] ${cmd.mkString(" ")}")
    val out = new StringBuilder
    val io = scala.sys.process.ProcessLogger(line => { out.append(line).append('\n'); () }, log.error(_))
    val rc = scala.sys.process.Process(cmd) ! io
    if (rc != 0) sys.error(s"ssc failed with exit code $rc")
    out.toString
  }

  def runInteractive(binary: String, args: Seq[String], log: Logger): Unit = {
    val cmd = binary +: args
    log.info(s"[ssc] ${cmd.mkString(" ")}")
    val rc = scala.sys.process.Process(cmd).run(log, connectInput = true).exitValue()
    if (rc != 0) sys.error(s"ssc failed with exit code $rc")
  }
}
