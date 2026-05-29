package scalascript.sbt

import sbt._

object SscRunner {
  def run(binary: String, args: Seq[String], log: Logger): Unit = {
    val cmd = binary +: args
    log.info(s"[ssc] ${cmd.mkString(" ")}")
    val rc = scala.sys.process.Process(cmd) ! log
    if (rc != 0) sys.error(s"ssc failed with exit code $rc")
  }
}
