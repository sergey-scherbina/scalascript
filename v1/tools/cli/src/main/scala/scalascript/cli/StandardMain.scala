package scalascript.cli

import scala.util.control.NonFatal

/** ScalaScript 2.1 standard-tier entry point.
 *
 * This object deliberately mentions only the native frontend/runtime and the
 * direct ASM artifact command. The compatibility `Main.scala` entry remains in
 * the separate full CLI JAR for the optional tools launcher, but is never
 * initialized or spawned by the standard launcher. */
object StandardMain:
  def main(rawArgs: Array[String]): Unit =
    try dispatch(rawArgs.toList.filterNot(_ == "--quiet"))
    catch
      case failure: _root_.ssc.ControlRunFailure =>
        System.err.println(failure.rendered)
        System.exit(1)
      case NonFatal(error) =>
        System.err.println(s"ssc: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")
        System.exit(1)

  private def dispatch(args: List[String]): Unit = args match
    case ("help" | "--help" | "-h") :: _ => printHelp()
    case "run" :: rest                     => runNative(rest)
    case "build-jvm" :: rest               => NativeJvmArtifact.runCommand(rest)
    case "info" :: "--execution-plan" :: rest => printExecutionPlan(rest)
    case file :: rest if file.endsWith(".ssc") => runNative(file :: rest)
    case Nil =>
      printHelp()
      System.exit(1)
    case command :: _ => toolsRequired(command)

  private def runNative(args: List[String]): Unit =
    val separator = args.indexOf("--")
    val (beforeArgs, programArgs) =
      if separator < 0 then args -> Nil
      else args.take(separator) -> args.drop(separator + 1)
    // f5c (2026-07-22): the JVM-BYTECODE lane is now the DEFAULT execution backend — it JIT-compiles
    // numeric hot loops (~FastCode perf) and falls back to the interpreter for any construct it cannot
    // compile (link-time, side-effect-safe — RunNativeV2.runBytecode). REVERSIBLE: opt back to the
    // tree-walking VM with `--interpret` (or `SSC_EXEC=vm`), mirroring `SSC_FRONT=legacy`.
    var bytecode = defaultExecBytecode
    var mutable = false
    val files = collection.mutable.ListBuffer.empty[String]
    if beforeArgs.exists(arg => arg == "--v1" || arg == "--compat-frontend") then
      toolsRequired("run with the compatibility frontend")
    beforeArgs.foreach {
      case "--native" | "--v2" => ()
      case "--bytecode"         => bytecode = true
      case "--interpret" | "--vm" => bytecode = false
      case "--mutable"          => mutable = true
      case flag if flag.startsWith("-") =>
        throw new IllegalArgumentException(s"unknown standard run option: $flag")
      case file => files += file
    }
    if files.isEmpty then
      throw new IllegalArgumentException(
        "usage: ssc run [--native] [--bytecode|--interpret] [--mutable] file.ssc [more.ssc ...] -- [args ...]")
    RunNativeV2.run(files.toList, programArgs, bytecode, mutable)

  /** f5c: bytecode is the default execution backend. `SSC_EXEC=vm|interpret|interpreter` opts back to the
   *  tree-walking VM; `SSC_EXEC=asm|bytecode` forces bytecode; unset → bytecode. Explicit CLI flags
   *  (`--bytecode`/`--interpret`) still win over the env-derived default (handled at the call site). */
  private def defaultExecBytecode: Boolean =
    sys.env.get("SSC_EXEC").map(_.trim.toLowerCase) match
      case Some("vm" | "interpret" | "interpreter") => false
      case Some("asm" | "bytecode")                 => true
      case _                                         => true

  private def printExecutionPlan(args: List[String]): Unit =
    val bytecode =
      if args.contains("--interpret") || args.contains("--vm") then false
      else if args.contains("--bytecode") then true
      else defaultExecBytecode
    val backend = if bytecode then "asm" else "vm"
    println(
      s"""{"tier":"standard","frontend":"native","checker":"native","backend":"$backend","compiler":false}""")

  private def toolsRequired(surface: String): Nothing =
    throw new IllegalArgumentException(
      s"'$surface' requires the optional ScalaScript tools/compatibility tier; " +
      s"run ssc-tools explicitly or install the full distribution")

  private def printHelp(): Unit =
    println(
      """ScalaScript 2.1 standard tier
        |
        |  ssc run [--native] [--bytecode] file.ssc [more.ssc ...] -- [args ...]
        |  ssc build-jvm file.ssc [more.ssc ...] -o app.jar
        |  ssc info --execution-plan [--bytecode]
        |
        |The standard tier uses the self-hosted frontend/checker and v2 VM or
        |direct ASM. Legacy compiler-backed commands use the optional ssc-tools
        |launcher, which must be invoked explicitly and is never discovered or
        |spawned from the standard entry.
        |""".stripMargin)
