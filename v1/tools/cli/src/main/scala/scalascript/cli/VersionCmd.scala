package scalascript.cli

/** `ssc version` — print the ssc version and runtime facts.
 *
 *  The version string comes from the jar manifest (Implementation-Version,
 *  stamped by sbt packageBin); a dev tree without a manifest shows "dev".
 *  The runtime line records the ENGINE default — v2 since 2026-07-08 — so a
 *  bug report's first line answers "which runtime?". */
final class VersionCmd extends CliCommand:
  def name: String = "version"
  override def aliases: List[String] = List("--version", "-V")
  override def summary: String = "Print the ssc version"
  override def category: String = "Other"

  def run(args: List[String]): Unit =
    val v = Option(getClass.getPackage)
      .flatMap(p => Option(p.getImplementationVersion))
      .getOrElse("dev")
    println(s"ssc $v")
    println(s"runtime: v2 (default; --v1 opts back)  ·  jvm ${System.getProperty("java.version")}")
