package scalascript.cli

/** Standard ScalaScript 2.1 execution path. This class deliberately has no
 *  reference to the Scalameta frontend or v1 PluginBridge. */
object RunNativeV2:
  def run(files: List[String], argv: List[String], bytecode: Boolean): Unit =
    val (runner, stdRoot) = nativeFrontLayout()
    val sourceFiles = files.map { file =>
      val f = new java.io.File(file).getCanonicalFile
      if !f.isFile then
        throw new java.io.FileNotFoundException(s"native frontend input not found: $file")
      portablePath(f)
    }

    val previousArgv = _root_.ssc.Runtime.argv
    try
      val ir = lowerNative(runner, stdRoot, sourceFiles)
      if ir.isEmpty then throw new RuntimeException("native frontend emitted no CoreIR")
      if ir.contains("(global _err)") then
        throw new RuntimeException(
          "native frontend rejected incomplete parse: emitted CoreIR contains parser sentinel _err")

      _root_.ssc.Runtime.argv = argv
      _root_.ssc.plugin.NativePluginHost.loadAll()
      val prog = _root_.ssc.Reader.parseProgram(ir)
      if bytecode then runBytecode(prog) else runVm(prog)
    finally _root_.ssc.Runtime.argv = previousArgv

  /** Parser/lowerer recursion for real std-heavy documents needs more stack
   *  than the general CLI thread. Isolate that cost to the frontend instead of
   *  imposing a huge `-Xss` on every thread of long-running server programs. */
  private def lowerNative(runner: java.io.File, stdRoot: java.io.File, sourceFiles: List[String]): String =
    val irOut   = new java.io.ByteArrayOutputStream()
    val irPs    = new java.io.PrintStream(irOut, true, java.nio.charset.StandardCharsets.UTF_8)
    val failure = new java.util.concurrent.atomic.AtomicReference[Throwable]()
    val task = new Runnable:
      def run(): Unit =
        try
          _root_.ssc.Runtime.argv = "--std-root" :: portablePath(stdRoot.getCanonicalFile) :: sourceFiles
          Console.withOut(irPs) {
            val tower = _root_.ssc.Lower.module(_root_.ssc.Loader.load(runner.getCanonicalPath))
            _root_.ssc.Runtime.run(_root_.ssc.Compiler.compile(tower), Array.empty[_root_.ssc.Value])
          }
        catch case t: Throwable => failure.set(t)
    val thread = new Thread(null, task, "ssc-native-frontend", 64L * 1024L * 1024L)
    try
      thread.start()
      thread.join()
      val err = failure.get()
      if err != null then throw err
      irOut.toString(java.nio.charset.StandardCharsets.UTF_8).stripTrailing()
    finally irPs.close()

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

  private def nativeFrontLayout(): (java.io.File, java.io.File) =
    val installRoot = Option(System.getProperty("ssc.lib.path")).map(new java.io.File(_)).getOrElse {
      throw new IllegalStateException(
        "native frontend requires a staged installation (ssc.lib.path is unset); run scripts/sbtc \"installBin\" and use bin/ssc")
    }
    val base    = new java.io.File(installRoot, "bin/lib/native-front")
    val runner  = new java.io.File(base, "tower/bin/ssc1-run.ssc0")
    val stdRoot = new java.io.File(base, "runtime")
    if !runner.isFile || !stdRoot.isDirectory then
      throw new IllegalStateException(
        s"native frontend resources are not staged under ${base.getPath}; run scripts/sbtc \"installBin\"")
    runner -> stdRoot

  /** The self-hosted resolver uses `/` as its target-independent separator;
   *  `java.nio.file.Path` accepts that spelling on Windows as well. */
  private def portablePath(file: java.io.File): String =
    file.getPath.replace(java.io.File.separatorChar, '/')
