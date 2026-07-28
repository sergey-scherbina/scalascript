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
    catch
      case _: InterruptedException =>
        proc.destroy()
        Thread.currentThread().interrupt()
        System.exit(1)

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
    val canonicalFiles =
      ambientPrelude(userFiles, layout.stdRoot) ++
        dottedStdImportPrelude(userFiles, layout.stdRoot) ++
        userFiles
    val sourceFiles = canonicalFiles.map(portablePath)
    val sourceUnits = NativeSourceClosure.resolve(canonicalFiles, layout.stdRoot, layout.installRoot)

    val previousArgv = _root_.ssc.Runtime.argv
    try
      sourceFiles.foreach { source =>
        val checked = runTower(layout.checker, mutableFlag ++ (source :: Nil), "ssc-native-checker")
        if checked.exitCode != 0 || checked.output != "OK" then
          val detail = if checked.output.nonEmpty then checked.output else s"checker exit ${checked.exitCode}"
          throw new IllegalArgumentException(detail)
      }

      // F4a DELEGATE-FALLBACK (SSC_FRONT=F). F covers its subset; where it does not — the 12 single-file
      // gaps AND the ambient-prelude/plugin class (e.g. json's `__jsonCoreWrap`, `generator`) — it emits
      // Core IR with an UNBOUND global. `Reader.validate` catches exactly that (globalOk = a top-level def
      // or an @-cell), and `#coreir.decode` inside the F runner already rejects the same at lower time
      // (so `lowerNative` may throw first). On EITHER signal we transparently re-lower the file through
      // the DEFAULT runner (ssc1-front+ssc1-lower) and use its result — so F is NEVER worse than default.
      // Legit user-error sentinels (_err / _err_int_range / _err_mutable_fields) are also unbound globals,
      // so they route through the fallback too; the default front reproduces the same sentinel and the
      // checks below surface the correct message. Runtime-only F gaps (e.g. tagless-multi-file's arity
      // error) pass this pre-check and are a DOCUMENTED known-gap (see specs/v2.2-p6.5-dualrun.expected):
      // a static pre-check on untyped IR can't see them, and a run-time rerun would duplicate side effects.
      def lowerWith(runner: java.io.File, fsub: Option[java.io.File]): NativeStructuralFrontend =
        lowerNative(runner, layout.stdRoot, layout.installRoot, sourceFiles, canonicalFiles, mutableFlag, fsub)
      val structural = layout.fsubSrc match
        case Some(_) =>
          // Keep WHY F was rejected, not just THAT it was. `validateNoReader` names the offending
          // global, and that name is the difference between "F cannot compile this construct" and
          // "the program calls a plugin extern, which F is punished for and the legacy front is not"
          // (BUGS f-validateNoReader-rejects-plugin-externs). Without it the delegation census can
          // count both as the same thing, which is exactly the caveat that entry asks not to repeat.
          var fFailure: String = ""
          val fResult =
            try
              val s = lowerWith(layout.runner, layout.fsubSrc)
              validateNoReader(s.program) // throws on any unbound global (F coverage gap)
              Some(s)
            catch
              case failure: FNestedBytecodeFailure =>
                throw failure.original
              case e: Throwable =>
                fFailure = Option(e.getMessage).map(_.linesIterator.next()).getOrElse(e.getClass.getName)
                None
          fResult.getOrElse {
            val viaDefault = lowerWith(layout.defaultRunner, None)
            // ANNOUNCE a real coverage gap; stay quiet for a user error.
            //
            // This delegation used to be visible only under SSC_FRONT_TRACE, an env var nobody sets.
            // That was fine while F was opt-IN staging; F is now the DEFAULT front, so the silence
            // means every F gap looks like success at the CLI — and any corpus run driven through
            // `bin/ssc` measures the LEGACY front for those programs while reporting them as F.
            // Same shape as the `--bytecode` fallback (BUGS scljet-jdbc-facade-bytecode-class-too-large):
            // a silent fallback turns a differential measurement into a self-comparison.
            //
            // The condition is the point. The fallback fires for TWO different reasons, because a
            // user error and an F gap both show up as an unbound global: `undefinedThing()` in user
            // code routes here exactly like a construct F cannot lower. Announcing both would print
            // a compiler-internals line at every typo — noise that gets filtered out mentally within
            // a day, taking the signal with it. So: if the default front reproduces a sentinel, the
            // user's own program is at fault and this says nothing; if the default front SUCCEEDS
            // where F failed, that is an F coverage gap and it is worth exactly one line.
            // The test for "was this the user's fault or F's?" is to run the DEFAULT front's result
            // through the very check F failed. If the default front also produces an unbound global,
            // the file is broken whichever front reads it. Checking only for `_err*` SENTINELS was
            // not enough and the gate caught it: a plain `undefinedThing()` is an ordinary unbound
            // global, not a sentinel, so every typo still printed the coverage-gap line.
            val userErrorNotGap =
              try
                validateNoReader(viaDefault.program)
                false
              catch case _: Throwable => true
            if !userErrorNotGap then
              val why = if fFailure.isEmpty then "" else s" [$fFailure]"
              System.err.println(
                s"$FDelegationMarker ${sourceFiles.mkString(", ")}$why")
            else if sys.env.contains("SSC_FRONT_TRACE") then
              // Still a delegation — double lowering, F's output discarded — just not F's fault.
              // Traced with its reason so the two categories can be counted separately; a plugin
              // `extern def` lands here, because BOTH fronts emit it as an unbound global.
              val why = if fFailure.isEmpty then "" else s" [$fFailure]"
              System.err.println(
                s"[SSC_FRONT=F] delegated, both fronts unbound (not an F gap): ${sourceFiles.mkString(", ")}$why")
            viaDefault
          }
        case None =>
          lowerWith(layout.runner, None)
      if mutableFieldSentinel(structural.program) then
        throw new IllegalArgumentException(
          "mutable class fields (a `var` field in a class) are disabled by default; " +
            "pass the --mutable flag to enable them (e.g. `ssc run --mutable <file>`)")
      if intRangeSentinel(structural.program) then
        throw new IllegalArgumentException(
          "integer literal out of range for Int (64-bit two's-complement): the value does not " +
            "fit in [-9223372036854775808, 9223372036854775807]. Use BigInt(...) for arbitrary " +
            "precision (specs/numeric-widths.md §2).")
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
      libRoot: java.io.File,
      sourceFiles: List[String],
      canonicalFiles: List[java.io.File],
      mutableFlag: List[String],
      fsubSrc: Option[java.io.File]): NativeStructuralFrontend =
    // SSC_FRONT=F: point the F runner at F's staged source; harmless flag order (the runner's arg
    // parser accepts flags in any order before the file paths). Absent in the default lane.
    val fsubFlag = fsubSrc.toList.flatMap(f => List("--fsub-src", portablePath(f.getCanonicalFile)))
    val result = runTower(
      runner,
      mutableFlag ++ fsubFlag ++ ("--structural" :: "--std-root" :: portablePath(stdRoot.getCanonicalFile) ::
        "--lib-root" :: portablePath(libRoot.getCanonicalFile) :: sourceFiles),
      "ssc-native-frontend",
      useFNestedBytecode = fsubSrc.nonEmpty)
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

  /** Stack for the tower thread — the COMPILER's stack, deliberately independent of
   * `-Xss`/`SSC_XSS`.
   *
   * Split of responsibilities: this thread loads, lowers and runs the self-hosted
   * front (and `Compiler.compile`s it) to produce the user's `Program`; the USER's
   * program is then compiled and run on the calling thread, where `-Xss` applies.
   * So `-Xss`/`SSC_XSS` bounds the user program, and this constant bounds the
   * compiler. They are different jobs with very different depth needs and must not
   * share a knob: v21-direct-asm-recursion-smoke pins 256k to prove the COMPILED
   * lanes need no big stack, and that must not starve the compiler that gets there.
   *
   * Was hardcoded 64m, which made `-Xss` look inert — raising it changed nothing,
   * because the overflow was never on `main`. It only LOOKED like main: the catch
   * below stores the Throwable and rethrows it on the joining thread, so a tower
   * StackOverflowError prints as "Exception in thread main" carrying the tower's
   * trace. 64m was not enough for the scljet examples: `run --bytecode` overflowed
   * here ~80% of the time, flaky because frame sizes depend on how much the JIT has
   * compiled, which depends on machine load. Stack is reserved address space, not
   * committed memory, so a generous value is cheap. */
  private val TowerStackBytes: Long = 512L * 1024L * 1024L

  private def runTower(
      runner: java.io.File,
      args: List[String],
      threadName: String,
      useFNestedBytecode: Boolean = false): TowerResult =
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
            val code = _root_.ssc.Compiler.compile(tower)
            val value =
              if useFNestedBytecode then
                _root_.ssc.Runtime.withCoreIrEvaluator(
                  fNestedBytecodeEvaluator(sys.env.contains("SSC_FRONT_TRACE"))) {
                  _root_.ssc.Runtime.runManaged(
                    code, Array.empty[_root_.ssc.Value])
                }
              else
                _root_.ssc.Runtime.runManaged(
                  code, Array.empty[_root_.ssc.Value])
            resultValue.set(value)
          }
        catch
          case e: TowerExit => exitCode.set(e.code)
          case t: Throwable => failure.set(t)
    val thread = new Thread(null, task, threadName, TowerStackBytes)
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

  /** Keeps a nested bytecode failure out of F's broad coverage fallback. Once
    * install/entry begins, retrying through either F0's VM or the legacy front
    * could hide a miscompile and duplicate compiler-side effects. */
  private[cli] final class FNestedBytecodeFailure(
      val original: Throwable
  ) extends RuntimeException(original)

  /** One evaluator per F tower. The first coreir.eval is F0; consuming the
    * one-shot before classification guarantees later content/YAML evals stay
    * on their existing VM path even when the first program is small. */
  private[cli] def fNestedBytecodeEvaluator(
      trace: Boolean
  ): _root_.ssc.Program => Option[_root_.ssc.Value] =
    var first = true
    program =>
      if !first then None
      else
        first = false
        if !_root_.ssc.bytecode.JvmBytecodeAdmission.requiresStringChunking(program) then
          None
        else
          runNestedF0Bytecode(program, trace)

  private def runNestedF0Bytecode(
      program: _root_.ssc.Program,
      trace: Boolean
  ): Option[_root_.ssc.Value] =
    val bytecode =
      try
        Some(_root_.ssc.bytecode.JvmByteGen.emitProgram(
          _root_.ssc.bytecode.OpAnfNative.lift(program)))
      catch
        case e: _root_.ssc.bytecode.Unsupported =>
          noteFNestedBytecodeVm("unsupported-construct", e.getMessage, trace)
          None
        case e: RuntimeException if isAsmSizeLimit(e) =>
          noteFNestedBytecodeVm("class-size-limit", e.getClass.getName, trace)
          None
        case t: Throwable =>
          throw new FNestedBytecodeFailure(t)

    bytecode.map { bytes =>
      val previousGlobals = _root_.ssc.Emit.globalsRef
      _root_.ssc.Emit.globalsRef = collection.mutable.HashMap.empty
      try
        if trace then System.err.println(FNestedBytecodeMarker)
        try _root_.ssc.bytecode.JvmByteGen.runProgram(bytes)
        catch
          case e: java.lang.reflect.InvocationTargetException =>
            throw new FNestedBytecodeFailure(
              Option(e.getCause).getOrElse(e))
          case t: Throwable =>
            throw new FNestedBytecodeFailure(t)
      finally
        _root_.ssc.Emit.globalsRef = previousGlobals
    }

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
    //
    // f5c prereq #1 — LINK-TIME fallback to the interpreter. `emitProgram` is pure code generation, so a
    // failure HERE happens BEFORE any program side effect and is safe to recover by running the VM instead
    // (no double execution). Two classes fall back cleanly: (a) a construct JvmByteGen cannot compile
    // (`Unsupported`); (b) an ASM `MethodTooLargeException`/`ClassTooLargeException` — the generated
    // `install`/method exceeds the JVM 64 KB limit (large top-level init; e.g. scljet-hello/-jdbc). A
    // RUNTIME failure — during `install`/`entry`, after side effects — is deliberately NOT caught here: it
    // must never re-run on the VM (side-effect duplication; same reason the front swap uses a static
    // pre-check). Deep effectful loops that StackOverflow on this lane are that runtime class — a separate
    // fix (stack-safe effectful loops in JvmByteGen), not a fallback.
    //
    // The fallback ANNOUNCES itself on stderr. It used to be silent, and silence made it a lie
    // detector's blind spot: `scripts/bc-parity-sweep` compares the `run` and `run --bytecode`
    // stdout, so a program that fell back was compared AGAINST ITSELF and recorded `identical` —
    // certified bytecode/VM parity for a program the bytecode backend never compiled. The note goes
    // to stderr, never stdout, so output comparisons are unaffected; the sweep keys on it to
    // classify the case honestly. See BUGS.md `scljet-jdbc-facade-bytecode-class-too-large`.
    val bytecode: Option[Array[Byte]] =
      try Some(_root_.ssc.bytecode.JvmByteGen.emitProgram(_root_.ssc.bytecode.OpAnfNative.lift(prog)))
      catch
        case e: _root_.ssc.bytecode.Unsupported =>
          noteBytecodeFallback("unsupported-construct", e.getMessage)
          None
        // Match ASM Method/Class-too-large by CLASS NAME, not by type. Referencing `org.objectweb.asm.*`
        // in a catch clause makes the JVM EAGERLY load ASM when it verifies this method — even on the VM
        // path that never calls emitProgram — which breaks native-VM backend isolation
        // (v21-plugin-backend-isolation). RuntimeException is already loaded; these ASM size exceptions
        // are IndexOutOfBoundsException (⊂ RuntimeException), so this catches them without the reference.
        case e: RuntimeException if isAsmSizeLimit(e) =>
          noteBytecodeFallback("class-size-limit", e.getClass.getName)
          None
    bytecode match
      case None =>
        runVm(prog) // link-time coverage gap → interpreter (correct, just not JIT-fast)
      case Some(bytes) =>
        val result =
          try _root_.ssc.bytecode.JvmByteGen.runProgram(bytes)
          catch case e: java.lang.reflect.InvocationTargetException =>
            throw Option(e.getCause).getOrElse(e)
        V2Result.report(result)

  /** Stable, greppable marker for "you asked for `--bytecode` and did not get it".
   *
   *  Machine-readable on purpose: `scripts/bc-parity-sweep` matches this exact prefix to tell a real
   *  bytecode run from a VM run wearing its name. Changing the wording without updating the sweep
   *  turns every fallback back into a fake `identical`, so the marker is asserted by
   *  `tests/e2e/bytecode-fallback-visible.sh`.
   */
  private[cli] val BytecodeFallbackMarker = "ssc: --bytecode fell back to the VM lane"

  /** Stable, greppable marker for "the default front is F, and F did not compile this file".
    *
    *  Machine-readable on purpose: it is what makes "how much of the corpus does F actually cover?"
    *  a measurement instead of a claim, and `tests/e2e/f-front-delegation-visible.sh` pins the exact
    *  string so a reworded message cannot quietly switch the measurement off. */
  private[cli] val FDelegationMarker = "ssc: F did not lower this file; compiled with the default front instead —"

  private[cli] val FNestedBytecodeMarker = "[SSC_FRONT=F] nested F0 direct-ASM"

  private def noteFNestedBytecodeVm(
      reason: String,
      detail: String,
      trace: Boolean
  ): Unit =
    if trace then
      val suffix =
        Option(detail).filter(_.nonEmpty).map(d => s" ($d)").getOrElse("")
      System.err.println(
        s"[SSC_FRONT=F] nested F0 stayed on VM [$reason]$suffix")

  private def noteBytecodeFallback(reason: String, detail: String): Unit =
    val suffix = Option(detail).filter(_.nonEmpty).map(d => s" ($d)").getOrElse("")
    System.err.println(s"$BytecodeFallbackMarker [$reason]$suffix")

  /** ASM `MethodTooLargeException`/`ClassTooLargeException`, matched by class name so the type is not
   *  referenced (which would eagerly load ASM on the VM path — see runBytecode's fallback comment). */
  private def isAsmSizeLimit(e: Throwable): Boolean =
    val n = e.getClass.getName
    n == "org.objectweb.asm.MethodTooLargeException" || n == "org.objectweb.asm.ClassTooLargeException"

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

  /** The self-hosted lowerer emits `Global("_err_int_range")` when a decimal
   *  integer literal does not fit a signed 64-bit `Int` (and is not the min64
   *  literal, which is folded). Surface a specific, actionable message so a
   *  too-big literal fails CLOSED with a clear diagnostic instead of silently
   *  lowering to `0` (`v2-native-min64-literal-prints-0`). */
  /** D2 (f-runner-structural-data): the F4a delegate-fallback pre-check — a Reader-FREE port of
   *  `ssc.Reader.validate`, throwing on the same signals (unbound global, out-of-range local,
   *  non-lam letrec binding). Calling `ssc.Reader.validate` here class-loaded `ssc.Reader` on the
   *  F native path, which the v21-native-plugin-boundary / v21-plugin-backend-isolation smokes
   *  forbid post-flip. This walk stays byte-for-byte behaviourally identical so the fallback (F ->
   *  default front on a coverage gap) is unchanged; it just no longer touches the textual reader. */
  private def validateNoReader(p: _root_.ssc.Program): Unit =
    val defNames = p.defs.iterator.map(_.name).toSet
    def globalOk(g: String): Boolean = defNames.contains(g) || g.startsWith("@")
    def go(t: _root_.ssc.Term, depth: Int): Unit = t match
      case _root_.ssc.Term.Lit(_) => ()
      case _root_.ssc.Term.Local(i) =>
        if i < 0 || i >= depth then
          sys.error(s"local index out of range: (local $i) with $depth binder(s) in scope")
      case _root_.ssc.Term.Global(g) =>
        if !globalOk(g) then
          sys.error(s"unbound global: (global $g) is neither a top-level def nor an @-cell")
      case _root_.ssc.Term.Lam(ar, b)      => go(b, depth + ar)
      case _root_.ssc.Term.App(fn, as)     => go(fn, depth); as.foreach(go(_, depth))
      case _root_.ssc.Term.Let(rhs, body)  =>
        rhs.iterator.zipWithIndex.foreach { case (r, i) => go(r, depth + i) }
        go(body, depth + rhs.length)
      case _root_.ssc.Term.LetRec(lams, body) =>
        val d2 = depth + lams.length
        lams.foreach {
          case l: _root_.ssc.Term.Lam => go(l, d2)
          case _                      => sys.error("letrec binding must be a lam")
        }
        go(body, d2)
      case _root_.ssc.Term.If(c, th, el)   => go(c, depth); go(th, depth); go(el, depth)
      case _root_.ssc.Term.Ctor(_, fs)     => fs.foreach(go(_, depth))
      case _root_.ssc.Term.Prim(_, as)     => as.foreach(go(_, depth))
      case _root_.ssc.Term.While(c, b)     => go(c, depth); go(b, depth)
      case _root_.ssc.Term.Seq(ts)         => ts.foreach(go(_, depth))
      case _root_.ssc.Term.Match(scrut, arms, default) =>
        go(scrut, depth)
        arms.foreach(a => go(a.body, depth + a.arity))
        default.foreach(go(_, depth))
    p.defs.foreach(d => go(d.body, 0))
    go(p.entry, 0)

  private def intRangeSentinel(program: _root_.ssc.Program): Boolean =
    program.defs.exists(definition => intRangeSentinel(definition.body)) ||
      intRangeSentinel(program.entry)

  private def intRangeSentinel(term: _root_.ssc.Term): Boolean = term match
    case _root_.ssc.Term.Global("_err_int_range") => true
    case _root_.ssc.Term.Lam(_, body) => intRangeSentinel(body)
    case _root_.ssc.Term.App(fn, args) =>
      intRangeSentinel(fn) || args.exists(intRangeSentinel)
    case _root_.ssc.Term.Let(rhs, body) =>
      rhs.exists(intRangeSentinel) || intRangeSentinel(body)
    case _root_.ssc.Term.LetRec(lams, body) =>
      lams.exists(intRangeSentinel) || intRangeSentinel(body)
    case _root_.ssc.Term.If(cond, yes, no) =>
      intRangeSentinel(cond) || intRangeSentinel(yes) || intRangeSentinel(no)
    case _root_.ssc.Term.Ctor(_, fields) => fields.exists(intRangeSentinel)
    case _root_.ssc.Term.Match(scrutinee, arms, default) =>
      intRangeSentinel(scrutinee) ||
        arms.exists(arm => intRangeSentinel(arm.body)) ||
        default.exists(intRangeSentinel)
    case _root_.ssc.Term.Prim(_, args) => args.exists(intRangeSentinel)
    case _root_.ssc.Term.While(cond, body) =>
      intRangeSentinel(cond) || intRangeSentinel(body)
    case _root_.ssc.Term.Seq(terms) => terms.exists(intRangeSentinel)
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
      stdRoot: java.io.File,
      installRoot: java.io.File,
      fsubSrc: Option[java.io.File],
      // The default runner (ssc1-front + ssc1-lower) — the delegate-fallback target when F cannot fully
      // lower a program. Equals `runner` in the default lane; the ssc1-run.ssc0 path in F-mode.
      defaultRunner: java.io.File)

  /** F4 front swap — FLIPPED (2026-07-23, Sergiy). The self-hosting subset compiler F
   *  (specs/v2.2-p6.5-fsub.ssc, staged as tower/bin/fsub.ssc, run via tower/bin/ssc1-run-fsub.ssc0) is
   *  now the DEFAULT native lowerer; `SSC_FRONT=legacy` is the escape hatch back to the untyped
   *  ssc1-front+ssc1-lower runner ssc1-run.ssc0 (kept as the F4a delegate-fallback `defaultRunner`, so F
   *  is never-worse-than-legacy). `SSC_FRONT=F`/`fsub` still select F (backward compatible). The checker
   *  (ssc1-check-run.ssc0) is kept beside F in BOTH modes — F is a parser+lowerer, not a checker. The
   *  flip is REVERSIBLE: set `SSC_FRONT=legacy`, or revert this one line to the opt-in form. Cleared to
   *  flip after every blocker landed: multi-file residuals (mcp-types last), int-literal-overflow,
   *  md-interpolator, and the plugin-boundary/isolation `ssc.Reader` load (③.2, fixed by D2 —
   *  ssc1-run-fsub emits IrProg Data directly, RunNativeV2 uses validateNoReader). Readiness verified:
   *  the full e2e smoke set (72 scripts) is A/B-green under F vs legacy — zero F-only regressions. */
  private def frontIsF: Boolean =
    !sys.env.get("SSC_FRONT").exists(_.equalsIgnoreCase("legacy"))

  private def nativeFrontLayout(): NativeFrontLayout =
    val installRoot = Option(System.getProperty("ssc.lib.path")).map(new java.io.File(_)).getOrElse {
      throw new IllegalStateException(
        "native frontend requires a staged installation (ssc.lib.path is unset); run scripts/sbtc \"installBin\" and use bin/ssc")
    }
    val standardBase = new java.io.File(installRoot, "bin/lib/standard/native-front")
    val legacyBase = new java.io.File(installRoot, "bin/lib/native-front")
    val base = if standardBase.isDirectory then standardBase else legacyBase
    val useF = frontIsF
    val defaultRunner = new java.io.File(base, "tower/bin/ssc1-run.ssc0")
    val runnerName = if useF then "ssc1-run-fsub.ssc0" else "ssc1-run.ssc0"
    val runner  = new java.io.File(base, s"tower/bin/$runnerName")
    val checker = new java.io.File(base, "tower/bin/ssc1-check-run.ssc0")
    val stdRoot = new java.io.File(base, "runtime")
    val fsubSrc = if useF then Some(new java.io.File(base, "tower/bin/fsub.ssc")) else None
    if !runner.isFile || !defaultRunner.isFile || !checker.isFile || !stdRoot.isDirectory then
      throw new IllegalStateException(
        s"native frontend resources are not staged under ${base.getPath}; run scripts/sbtc \"installBin\"")
    fsubSrc.foreach { f =>
      if !f.isFile then throw new IllegalStateException(
        s"SSC_FRONT=F requested but F source is not staged at ${f.getPath}; run scripts/sbtc \"installBin\"")
    }
    NativeFrontLayout(runner, checker, stdRoot, installRoot, fsubSrc, defaultRunner)

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

  /** Dotted-package std imports — `import std.mapreduce.*`, `import std.mapreduce.{Cluster}`,
   *  `import std.mapreduce.cluster.Cluster` — inside a fenced block. The self-hosted front only
   *  follows standalone Markdown-link imports (`[names](path.ssc)`); the dotted form was a silent
   *  no-op, so pure-`.ssc` definitions (case classes, defs) in the package were never loaded and
   *  hit "unbound global" at runtime. Bridge the gap on the JDK side (no tower change): for each
   *  dotted `std.*` module referenced, synthesize a tiny prelude source that Markdown-link-imports
   *  the package's `index.ssc` (a module file, or a directory's index), and prepend it as a leading
   *  root — the runner combines all roots into one program scope, so the package's exported names
   *  become available, exactly like the Markdown-link form already does.
   *  (v2-native-import-wildcard-drops-ssc-case-classes.) */
  private val dottedStdImportPat =
    """(?m)^\s*import\s+(std(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\.(?:\*|\{[^}]*\}|[A-Za-z_][A-Za-z0-9_]*)\s*$""".r

  private def dottedStdImportPrelude(userFiles: List[java.io.File], stdRoot: java.io.File): List[java.io.File] =
    val text =
      try userFiles.map(f =>
            java.nio.file.Files.readString(f.toPath, java.nio.charset.StandardCharsets.UTF_8)).mkString("\n")
      catch case _: Throwable => return Nil
    // Distinct module reference paths (`std/mapreduce`, `std/mapreduce/cluster`, …).
    val refs = dottedStdImportPat.findAllMatchIn(text).map(_.group(1).replace('.', '/')).toList.distinct
    val linkPaths = refs.flatMap { rel =>
      val asFile = new java.io.File(stdRoot, rel + ".ssc")
      val asDir  = new java.io.File(stdRoot, rel + "/index.ssc")
      if asFile.isFile then Some(rel + ".ssc")
      else if asDir.isFile then Some(rel + "/index.ssc")
      else None
    }.distinct
    if linkPaths.isEmpty then Nil
    else
      // One synthetic prelude carrying every Markdown-link import plus a trivial code block
      // (a source with no scalascript block fails the per-file checker). `std/…` targets resolve
      // against the std root regardless of the prelude file's own location.
      val body = new StringBuilder
      linkPaths.foreach(p => body.append(s"[stdImport]($p)\n\n"))
      body.append("```scalascript\nval __ssc_dotted_import_prelude__ = 0\n```\n")
      val tmp = java.nio.file.Files.createTempFile("ssc-dotted-std-import-", ".ssc")
      java.nio.file.Files.writeString(tmp, body.toString, java.nio.charset.StandardCharsets.UTF_8)
      tmp.toFile.deleteOnExit()
      List(tmp.toFile.getCanonicalFile)

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
