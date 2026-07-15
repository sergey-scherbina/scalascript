package scalascript.cli

/** Standard ScalaScript 2.1 execution path. This class deliberately has no
 *  reference to the Scalameta frontend or v1 PluginBridge. */
object RunNativeV2:
  def run(files: List[String], argv: List[String], bytecode: Boolean): Unit =
    run(files, argv, bytecode, mutable = false)

  def run(files: List[String], argv: List[String], bytecode: Boolean, mutable: Boolean): Unit =
    val previousArgv = _root_.ssc.Runtime.argv
    val compilation = compile(files, mutable)
    try
      _root_.ssc.Runtime.argv = argv
      _root_.ssc.plugin.NativePluginHost.loadAll(compilation.config)
      if bytecode then runBytecode(compilation.program) else runVm(compilation.program)
    finally _root_.ssc.Runtime.argv = previousArgv

  /** `run-js --v2` on the native lane: native ssc1 front → CoreIR → v2 JsGen →
   *  a temp CommonJS file executed with node. 64-bit-Int JS (the reason this lane
   *  exists), now sourced from the native front instead of the scalameta bridge. */
  def runJs(files: List[String], argv: List[String]): Unit =
    val compilation = compile(files)
    val js  = _root_.ssc.js.JsGen.generate(compilation.program)
    val tmp = java.nio.file.Files.createTempFile("ssc-native-js-", ".cjs")
    java.nio.file.Files.writeString(tmp, js, java.nio.charset.StandardCharsets.UTF_8)
    tmp.toFile.deleteOnExit()
    runNodeAndWait(Seq("node", tmp.toString) ++ argv)

  private def runNodeAndWait(cmd: Seq[String]): Unit =
    val proc = new ProcessBuilder(cmd*).inheritIO().start()
    val hook = new Thread(() => proc.destroy())
    java.lang.Runtime.getRuntime.addShutdownHook(hook)
    try
      val exitCode = proc.waitFor()
      java.lang.Runtime.getRuntime.removeShutdownHook(hook)
      if exitCode != 0 then System.exit(exitCode)
    catch case _: InterruptedException => proc.destroy(); System.exit(1)

  private[cli] def compile(files: List[String]): NativeV2Compilation =
    compile(files, mutable = false)

  private[cli] def compile(files: List[String], mutable: Boolean): NativeV2Compilation =
    val layout = nativeFrontLayout()
    // The `--mutable` compiler flag (opt-in mutable class `var` fields) is passed
    // to BOTH tower invocations; the self-hosted frontend rejects a mutable field
    // without it. `mutableFlag` is prepended so both mains can strip it before the
    // file path.
    val mutableFlag = if mutable then List("--mutable") else Nil
    val userFiles = files.map { file =>
      val f = new java.io.File(file).getCanonicalFile
      if !f.isFile then
        throw new java.io.FileNotFoundException(s"native frontend input not found: $file")
      f
    }
    // Ambient prelude: INT and JS expose certain plugin globals (jsonRead,
    // contentToolkitSection, …) WITHOUT an explicit import. On the native tier those
    // are SELF-HOSTED std modules, so a program that uses them but doesn't import them
    // fails at runtime with "unbound global". Inject each known-clean ambient std
    // module as a leading prelude source file when the program references one of its
    // distinctive exported names and doesn't already import it — the runner combines
    // all source files into one program scope, restoring parity with INT/JS.
    // (v2-native-ambient-prelude.)
    val canonicalFiles = ambientPrelude(userFiles, layout.stdRoot) ++ userFiles
    val sourceFiles = canonicalFiles.map(portablePath)
    val sourceUnits = NativeSourceClosure.resolve(canonicalFiles, layout.stdRoot)

    val previousArgv = _root_.ssc.Runtime.argv
    try
      sourceFiles.foreach { source =>
        val checked = runTower(layout.checker, mutableFlag ++ (source :: Nil), "ssc-native-checker")
        if checked.exitCode != 0 || checked.output != "OK" then
          val detail = if checked.output.nonEmpty then checked.output else s"checker exit ${checked.exitCode}"
          throw new IllegalArgumentException(detail)
      }

      val structural = lowerNative(layout.runner, layout.stdRoot, sourceFiles, canonicalFiles, mutableFlag)
      if mutableFieldSentinel(structural.program) then
        throw new IllegalArgumentException(
          "mutable class fields (a `var` field in a class) are disabled by default; " +
            "pass the --mutable flag to enable them (e.g. `ssc run --mutable <file>`)")
      if containsErrorSentinel(structural.program) then
        val inputs = sourceFiles.mkString(", ")
        throw new RuntimeException(
          s"native frontend rejected incomplete parse in $inputs: " +
            "structural CoreIR contains parser sentinel _err")

      // NativeUi site annotation. The self-hosted native frontend lowers std/ui primitive
      // calls to PLAIN globals; unlike the scalameta FrontendBridge it does NOT run the
      // `NativeUiSites.annotate` pass, so every ANONYMOUS derived signal (`computedSignal` /
      // `eqSignal`) reaches the ui plugin's fallback registration with one shared id
      // (`__computed__manual:computedSignal`). Creating a second such signal whose computed
      // default differs then fails with "duplicate native UI signal … conflicting kind/default"
      // (measured on rozum's control center: 20+ computedSignals collapse to one id). Run the
      // same annotation pass here so each anonymous-signal call site gets a unique lexical id.
      //
      // Scope: ONLY the anonymous derived-signal primitives, and only those ACTUALLY CALLED as
      // `App(Global(name))` (a bare/eta-expanded reference to an un-called primitive must not
      // fail the whole program). We deliberately do NOT yet annotate the other site-native
      // primitives (`element`/`fetchAction*`/`forKeyedView`/…): they don't hit this shared-id
      // collision, and rich SPAs that use them still fail the native frontend for an INDEPENDENT
      // reason (an `arity: 2 expected, 1 given` runtime error reproducible on the in-repo
      // `examples/control-center-live.ssc` with OR without this fix) — broadening is deferred
      // until that native-front SPA gap is closed. See BUGS.md `native-front-nativeui-site-annotation`.
      val annotatableSignals = Set("computedSignal", "eqSignal")
      val calledPrimitives = calledNativeUiPrimitives(structural.program).intersect(annotatableSignals)
      val annotatedProgram =
        if calledPrimitives.isEmpty then structural.program
        else _root_.ssc.NativeUiSites.annotate(
          structural.program,
          _root_.ssc.NativeUiSites.Config(eligibleSymbols = calledPrimitives))

      // `main: <fn>` front-matter — a program whose behaviour lives entirely in a
      // named entry function (no top-level statements) must still run it. INT/JS and
      // FrontendBridge honour `main:`; the native front dropped it, so such programs
      // (every SwiftUI/UI app: `main: run`) executed nothing. Append `fn()` to the
      // entry when the root manifest names a `main` that the program actually defines.
      // Skip `main: main` — the tower already auto-invokes a top-level `def main()`
      // (the `ssc run` convention), so appending it would double-run.
      val programWithMain = manifestMainName(structural.manifests, userFiles.lastOption) match
        case Some(fn) if fn != "main" && annotatedProgram.defs.exists(_.name == fn) =>
          val call = _root_.ssc.Term.App(_root_.ssc.Term.Global(fn), Nil)
          _root_.ssc.Program(annotatedProgram.defs,
            _root_.ssc.Term.Seq(List(annotatedProgram.entry, call)))
        case _ => annotatedProgram

      NativeV2Compilation(
        programWithMain,
        structural.config,
        structural.manifests,
        structural.contentModules,
        canonicalFiles,
        sourceUnits)
    finally _root_.ssc.Runtime.argv = previousArgv

  /** Parser/lowerer recursion for real std-heavy documents needs more stack
   *  than the general CLI thread. Isolate that cost to the frontend instead of
   *  imposing a huge `-Xss` on every thread of long-running server programs. */
  private def lowerNative(
      runner: java.io.File,
      stdRoot: java.io.File,
      sourceFiles: List[String],
      canonicalFiles: List[java.io.File],
      mutableFlag: List[String]): NativeStructuralFrontend =
    val result = runTower(
      runner,
      mutableFlag ++ ("--structural" :: "--std-root" :: portablePath(stdRoot.getCanonicalFile) :: sourceFiles),
      "ssc-native-frontend")
    if result.exitCode != 0 then
      throw new RuntimeException(s"native frontend exited with ${result.exitCode}")
    val value = result.value
    if value == null then throw new RuntimeException("native frontend emitted no structural result")
    NativeV2Structural.decode(value, canonicalFiles)

  /** The `main:` entry-function name from the root file's front-matter (if any). */
  private def manifestMainName(
      manifests: List[NativeSourceManifest],
      rootFile: Option[java.io.File]): Option[String] =
    val manifest = rootFile
      .flatMap(root => manifests.find(_.source == root))
      .orElse(manifests.lastOption)
    manifest.flatMap(_.value).collect {
      case NativeManifestObject(fields) =>
        fields.collectFirst { case ("main", NativeManifestString(v)) if v.nonEmpty => v }
    }.flatten

  private final case class TowerResult(
      output: String,
      exitCode: Int,
      value: _root_.ssc.Value | Null)
  private final class TowerExit(val code: Int) extends RuntimeException

  private def runTower(runner: java.io.File, args: List[String], threadName: String): TowerResult =
    val irOut   = new java.io.ByteArrayOutputStream()
    val irPs    = new java.io.PrintStream(irOut, true, java.nio.charset.StandardCharsets.UTF_8)
    val failure = new java.util.concurrent.atomic.AtomicReference[Throwable]()
    val exitCode = new java.util.concurrent.atomic.AtomicInteger(0)
    val resultValue = new java.util.concurrent.atomic.AtomicReference[_root_.ssc.Value | Null](null)
    val previousExitHandler = _root_.ssc.Runtime.exitHandler
    _root_.ssc.Runtime.exitHandler = code => throw new TowerExit(code)
    val task = new Runnable:
      def run(): Unit =
        try
          _root_.ssc.Runtime.argv = args
          Console.withOut(irPs) {
            val tower = _root_.ssc.Lower.module(_root_.ssc.Loader.load(runner.getCanonicalPath))
            resultValue.set(_root_.ssc.Runtime.runManaged(
              _root_.ssc.Compiler.compile(tower), Array.empty[_root_.ssc.Value]))
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
        exitCode.get(),
        resultValue.get())
    finally
      _root_.ssc.Runtime.exitHandler = previousExitHandler
      irPs.close()

  private def runVm(prog: _root_.ssc.Program): Unit =
    V2Result.report(_root_.ssc.Runtime.runManaged(
      _root_.ssc.Compiler.compile(prog), Array.empty[_root_.ssc.Value]))

  private def runBytecode(prog: _root_.ssc.Program): Unit =
    // Generated install() owns both lambda and value-def initialization. A VM
    // compileWithGlobals prepass is redundant and can take VM/plugin-specific
    // dispatch branches before the ASM class has installed its own globals.
    _root_.ssc.Emit.globalsRef = collection.mutable.HashMap.empty
    // Op-ARGUMENT lifting for the self-hosted lane: bind App/Ctor/Prim args (and
    // Match scrutinee / If condition) that may evaluate to a raw effect `Op` so
    // the kernel's Let-threading defers them into the Op's continuation
    // (head-field-effect-shadow / js-applyunary-effect-cps). Excludes
    // effect.handle/perform/pure. See ssc.bytecode.OpAnfNative.
    val bytes = _root_.ssc.bytecode.JvmByteGen.emitProgram(_root_.ssc.bytecode.OpAnfNative.lift(prog))
    val result =
      try _root_.ssc.bytecode.JvmByteGen.runProgram(bytes)
      catch case e: java.lang.reflect.InvocationTargetException =>
        throw Option(e.getCause).getOrElse(e)
    V2Result.report(result)

  private def containsErrorSentinel(program: _root_.ssc.Program): Boolean =
    program.defs.exists(definition => containsErrorSentinel(definition.body)) ||
      containsErrorSentinel(program.entry)

  private def containsErrorSentinel(term: _root_.ssc.Term): Boolean = term match
    case _root_.ssc.Term.Global("_err") => true
    case _root_.ssc.Term.Lam(_, body) => containsErrorSentinel(body)
    case _root_.ssc.Term.App(fn, args) =>
      containsErrorSentinel(fn) || args.exists(containsErrorSentinel)
    case _root_.ssc.Term.Let(rhs, body) =>
      rhs.exists(containsErrorSentinel) || containsErrorSentinel(body)
    case _root_.ssc.Term.LetRec(lams, body) =>
      lams.exists(containsErrorSentinel) || containsErrorSentinel(body)
    case _root_.ssc.Term.If(cond, yes, no) =>
      containsErrorSentinel(cond) || containsErrorSentinel(yes) || containsErrorSentinel(no)
    case _root_.ssc.Term.Ctor(_, fields) => fields.exists(containsErrorSentinel)
    case _root_.ssc.Term.Match(scrutinee, arms, default) =>
      containsErrorSentinel(scrutinee) ||
        arms.exists(arm => containsErrorSentinel(arm.body)) ||
        default.exists(containsErrorSentinel)
    case _root_.ssc.Term.Prim(_, args) => args.exists(containsErrorSentinel)
    case _root_.ssc.Term.While(cond, body) =>
      containsErrorSentinel(cond) || containsErrorSentinel(body)
    case _root_.ssc.Term.Seq(terms) => terms.exists(containsErrorSentinel)
    case _ => false

  /** The self-hosted frontend emits `Global("_err_mutable_fields")` when a class
   *  uses a mutable `var` field without the --mutable flag — surface a specific,
   *  actionable message (distinct from the generic parser `_err`). */
  private def mutableFieldSentinel(program: _root_.ssc.Program): Boolean =
    program.defs.exists(definition => mutableFieldSentinel(definition.body)) ||
      mutableFieldSentinel(program.entry)

  private def mutableFieldSentinel(term: _root_.ssc.Term): Boolean = term match
    case _root_.ssc.Term.Global("_err_mutable_fields") => true
    case _root_.ssc.Term.Lam(_, body) => mutableFieldSentinel(body)
    case _root_.ssc.Term.App(fn, args) =>
      mutableFieldSentinel(fn) || args.exists(mutableFieldSentinel)
    case _root_.ssc.Term.Let(rhs, body) =>
      rhs.exists(mutableFieldSentinel) || mutableFieldSentinel(body)
    case _root_.ssc.Term.LetRec(lams, body) =>
      lams.exists(mutableFieldSentinel) || mutableFieldSentinel(body)
    case _root_.ssc.Term.If(cond, yes, no) =>
      mutableFieldSentinel(cond) || mutableFieldSentinel(yes) || mutableFieldSentinel(no)
    case _root_.ssc.Term.Ctor(_, fields) => fields.exists(mutableFieldSentinel)
    case _root_.ssc.Term.Match(scrutinee, arms, default) =>
      mutableFieldSentinel(scrutinee) ||
        arms.exists(arm => mutableFieldSentinel(arm.body)) ||
        default.exists(mutableFieldSentinel)
    case _root_.ssc.Term.Prim(_, args) => args.exists(mutableFieldSentinel)
    case _root_.ssc.Term.While(cond, body) =>
      mutableFieldSentinel(cond) || mutableFieldSentinel(body)
    case _root_.ssc.Term.Seq(terms) => terms.exists(mutableFieldSentinel)
    case _ => false

  /** Names of std/ui site-native primitives that appear in APPLIED position
   *  (`App(Global(name), _)`) anywhere in the program — the set the NativeUiSites
   *  annotation pass may safely rewrite. Scoping eligibility to actually-called
   *  primitives keeps a bare/eta-expanded reference to an un-called primitive from
   *  failing the whole program (the pass rejects bare eligible primitives), and a
   *  never-called primitive would be a no-op anyway. */
  private[cli] def calledNativeUiPrimitives(program: _root_.ssc.Program): Set[String] =
    val found = collection.mutable.Set.empty[String]
    def walk(term: _root_.ssc.Term): Unit = term match
      case _root_.ssc.Term.App(fn, args) =>
        fn match
          case _root_.ssc.Term.Global(name) if _root_.ssc.NativeUiSites.annotatedSymbols(name) =>
            found += name
          case _ => ()
        walk(fn); args.foreach(walk)
      case _root_.ssc.Term.Lam(_, body) => walk(body)
      case _root_.ssc.Term.Let(rhs, body) => rhs.foreach(walk); walk(body)
      case _root_.ssc.Term.LetRec(lams, body) => lams.foreach(walk); walk(body)
      case _root_.ssc.Term.If(cond, yes, no) => walk(cond); walk(yes); walk(no)
      case _root_.ssc.Term.Ctor(_, fields) => fields.foreach(walk)
      case _root_.ssc.Term.Match(scrutinee, arms, default) =>
        walk(scrutinee); arms.foreach(arm => walk(arm.body)); default.foreach(walk)
      case _root_.ssc.Term.Prim(_, args) => args.foreach(walk)
      case _root_.ssc.Term.While(cond, body) => walk(cond); walk(body)
      case _root_.ssc.Term.Seq(terms) => terms.foreach(walk)
      case _ => ()
    program.defs.foreach(definition => walk(definition.body))
    walk(program.entry)
    found.toSet

  private final case class NativeFrontLayout(
      runner: java.io.File,
      checker: java.io.File,
      stdRoot: java.io.File)

  private def nativeFrontLayout(): NativeFrontLayout =
    val installRoot = Option(System.getProperty("ssc.lib.path")).map(new java.io.File(_)).getOrElse {
      throw new IllegalStateException(
        "native frontend requires a staged installation (ssc.lib.path is unset); run scripts/sbtc \"installBin\" and use bin/ssc")
    }
    val standardBase = new java.io.File(installRoot, "bin/lib/standard/native-front")
    val legacyBase = new java.io.File(installRoot, "bin/lib/native-front")
    val base = if standardBase.isDirectory then standardBase else legacyBase
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

  /** Curated ambient std modules: self-hosted, verified to compile standalone on the
   *  native frontend, mirroring the plugin globals INT/JS expose without an import.
   *  Keyed on DISTINCTIVE export names only (not common words like `lookup`/`jStr`)
   *  so a user's own identifier never spuriously pulls a module in. Grow this list as
   *  more std modules are confirmed clean + a case needs them. (v2-native-ambient-prelude.) */
  private val ambientModules: List[(String, List[String])] = List(
    "std/json.ssc" -> List("jsonRead", "jsonParse", "jsonStringify", "jsonValue")
    // NOTE: content-toolkit is NOT prelude-injectable — std/ui/content.ssc as a root trips
    // the content plugin's "structural ABI root identity" check, and contentToolkitSection is
    // an extern the v2 content NativePlugin doesn't register yet. That's a native-plugin gap.
  )

  private def ambientPrelude(userFiles: List[java.io.File], stdRoot: java.io.File): List[java.io.File] =
    val text =
      try userFiles.map(f =>
            java.nio.file.Files.readString(f.toPath, java.nio.charset.StandardCharsets.UTF_8)).mkString("\n")
      catch case _: Throwable => return Nil
    val userPaths = userFiles.map(_.getCanonicalPath).toSet
    ambientModules.flatMap { case (rel, triggers) =>
      val modFile = new java.io.File(stdRoot, rel).getCanonicalFile
      if !modFile.isFile then None
      else if userPaths.contains(modFile.getCanonicalPath) then None  // the module itself is a root
      else if text.contains(rel) then None                            // already imported ([..](std/json.ssc))
      else if triggers.exists(name => referencesWord(text, name)) then Some(modFile)
      else None
    }.distinct

  /** Whole-identifier match: `word` must not be flanked by ident chars (so `jsonRead`
   *  matches `jsonRead(...)` but not `myJsonReader`). */
  private def referencesWord(text: String, word: String): Boolean =
    val n = word.length
    var i = text.indexOf(word)
    while i >= 0 do
      val beforeOk = i == 0 || !isIdentChar(text.charAt(i - 1))
      val afterOk  = i + n >= text.length || !isIdentChar(text.charAt(i + n))
      if beforeOk && afterOk then return true
      i = text.indexOf(word, i + 1)
    false

  private def isIdentChar(c: Char): Boolean =
    c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')

private[cli] final case class NativeV2Compilation(
    program: _root_.ssc.Program,
    config: _root_.ssc.plugin.NativeRuntimeConfig,
    manifests: List[NativeSourceManifest],
    contentModules: List[_root_.ssc.plugin.NativeContentModule],
    roots: List[java.io.File],
    sourceUnits: List[NativeSourceUnit])
