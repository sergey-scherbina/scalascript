package scalascript.cli

/** Standard ScalaScript 2.1 execution path. This class deliberately has no
 *  reference to the Scalameta frontend or v1 PluginBridge. */
object RunNativeV2:
  def run(files: List[String], argv: List[String], bytecode: Boolean): Unit =
    val previousArgv = _root_.ssc.Runtime.argv
    val compilation = compile(files)
    try
      _root_.ssc.Runtime.argv = argv
      _root_.ssc.plugin.NativePluginHost.loadAll(compilation.config)
      if bytecode then runBytecode(compilation.program) else runVm(compilation.program)
    finally _root_.ssc.Runtime.argv = previousArgv

  private[cli] def compile(files: List[String]): NativeV2Compilation =
    val layout = nativeFrontLayout()
    val canonicalFiles = files.map { file =>
      val f = new java.io.File(file).getCanonicalFile
      if !f.isFile then
        throw new java.io.FileNotFoundException(s"native frontend input not found: $file")
      f
    }
    val sourceFiles = canonicalFiles.map(portablePath)
    val nativeConfig = NativeFrontmatter.fromFiles(canonicalFiles)

    val previousArgv = _root_.ssc.Runtime.argv
    try
      sourceFiles.foreach { source =>
        val checked = runTower(layout.checker, source :: Nil, "ssc-native-checker")
        if checked.exitCode != 0 || checked.output != "OK" then
          val detail = if checked.output.nonEmpty then checked.output else s"checker exit ${checked.exitCode}"
          throw new IllegalArgumentException(detail)
      }

      val ir = lowerNative(layout.runner, layout.stdRoot, sourceFiles)
      if ir.isEmpty then throw new RuntimeException("native frontend emitted no CoreIR")
      if ir.contains("(global _err)") then
        throw new RuntimeException(
          "native frontend rejected incomplete parse: emitted CoreIR contains parser sentinel _err")

      val prog = _root_.ssc.Reader.parseProgram(ir)
      NativeV2Compilation(prog, nativeConfig, canonicalFiles, ir)
    finally _root_.ssc.Runtime.argv = previousArgv

  /** Parser/lowerer recursion for real std-heavy documents needs more stack
   *  than the general CLI thread. Isolate that cost to the frontend instead of
   *  imposing a huge `-Xss` on every thread of long-running server programs. */
  private def lowerNative(runner: java.io.File, stdRoot: java.io.File, sourceFiles: List[String]): String =
    val result = runTower(
      runner,
      "--std-root" :: portablePath(stdRoot.getCanonicalFile) :: sourceFiles,
      "ssc-native-frontend")
    if result.exitCode != 0 then
      throw new RuntimeException(s"native frontend exited with ${result.exitCode}")
    result.output

  private final case class TowerResult(output: String, exitCode: Int)
  private final class TowerExit(val code: Int) extends RuntimeException

  private def runTower(runner: java.io.File, args: List[String], threadName: String): TowerResult =
    val irOut   = new java.io.ByteArrayOutputStream()
    val irPs    = new java.io.PrintStream(irOut, true, java.nio.charset.StandardCharsets.UTF_8)
    val failure = new java.util.concurrent.atomic.AtomicReference[Throwable]()
    val exitCode = new java.util.concurrent.atomic.AtomicInteger(0)
    val previousExitHandler = _root_.ssc.Runtime.exitHandler
    _root_.ssc.Runtime.exitHandler = code => throw new TowerExit(code)
    val task = new Runnable:
      def run(): Unit =
        try
          _root_.ssc.Runtime.argv = args
          Console.withOut(irPs) {
            val tower = _root_.ssc.Lower.module(_root_.ssc.Loader.load(runner.getCanonicalPath))
            _root_.ssc.Runtime.run(_root_.ssc.Compiler.compile(tower), Array.empty[_root_.ssc.Value])
          }
        catch
          case e: TowerExit => exitCode.set(e.code)
          case t: Throwable => failure.set(t)
    val thread = new Thread(null, task, threadName, 64L * 1024L * 1024L)
    try
      thread.start()
      thread.join()
      val err = failure.get()
      if err != null then throw err
      TowerResult(
        irOut.toString(java.nio.charset.StandardCharsets.UTF_8).stripTrailing(),
        exitCode.get())
    finally
      _root_.ssc.Runtime.exitHandler = previousExitHandler
      irPs.close()

  private def runVm(prog: _root_.ssc.Program): Unit =
    V2Result.report(_root_.ssc.Runtime.run(
      _root_.ssc.Compiler.compile(prog), Array.empty[_root_.ssc.Value]))

  private def runBytecode(prog: _root_.ssc.Program): Unit =
    val (_, globals) = _root_.ssc.Compiler.compileWithGlobals(prog)
    _root_.ssc.Emit.globalsRef = globals
    val bytes = _root_.ssc.bytecode.JvmByteGen.emitProgram(prog)
    val result =
      try _root_.ssc.bytecode.JvmByteGen.runProgram(bytes)
      catch case e: java.lang.reflect.InvocationTargetException =>
        throw Option(e.getCause).getOrElse(e)
    V2Result.report(result)

  private final case class NativeFrontLayout(
      runner: java.io.File,
      checker: java.io.File,
      stdRoot: java.io.File)

  private def nativeFrontLayout(): NativeFrontLayout =
    val installRoot = Option(System.getProperty("ssc.lib.path")).map(new java.io.File(_)).getOrElse {
      throw new IllegalStateException(
        "native frontend requires a staged installation (ssc.lib.path is unset); run scripts/sbtc \"installBin\" and use bin/ssc")
    }
    val base    = new java.io.File(installRoot, "bin/lib/native-front")
    val runner  = new java.io.File(base, "tower/bin/ssc1-run.ssc0")
    val checker = new java.io.File(base, "tower/bin/ssc1-check-run.ssc0")
    val stdRoot = new java.io.File(base, "runtime")
    if !runner.isFile || !checker.isFile || !stdRoot.isDirectory then
      throw new IllegalStateException(
        s"native frontend resources are not staged under ${base.getPath}; run scripts/sbtc \"installBin\"")
    NativeFrontLayout(runner, checker, stdRoot)

  /** The self-hosted resolver uses `/` as its target-independent separator;
   *  `java.nio.file.Path` accepts that spelling on Windows as well. */
  private def portablePath(file: java.io.File): String =
    file.getPath.replace(java.io.File.separatorChar, '/')

private[cli] final case class NativeV2Compilation(
    program: _root_.ssc.Program,
    config: _root_.ssc.plugin.NativeRuntimeConfig,
    sources: List[java.io.File],
    coreIr: String)
