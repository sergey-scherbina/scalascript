package scalascript.cli

import scalascript.backend.spi.*

/** `ssc run-rust [--debug] [--target <triple>] [--offline] [--verbose]
 *  <file.ssc> [-- <program args>…]` — emit a Cargo crate to a temp dir,
 *  build it with `cargo build`, spawn the produced binary with the argv
 *  after `--`, forward the exit code, then delete the temp dir.  Same
 *  cargo-presence semantics as `build-rust` (see specs/rust-backend.md
 *  §10). */
final class RunRustCmd extends CliCommand:
  def name: String = "run-rust"
  override def summary: String = "Build .ssc with rust backend and run the resulting binary"
  override def category: String = "Emit & transpile"
  override def details: List[String] = List(
    "Flags: --debug, --target <triple>, --offline, --verbose; argv after `--`"
  )

  def run(args: List[String]): Unit =
    // Split user args at the first `--` separator.  Anything after `--`
    // becomes argv for the built binary (cargo convention).
    val (sscArgs, binArgs) = args.span(_ != "--") match
      case (lhs, Nil)       => (lhs, Nil)
      case (lhs, _ :: rest) => (lhs, rest)

    var debug                     = false
    var target:    Option[String] = None
    var offline                   = false
    var verbose                   = false
    val files = scala.collection.mutable.ArrayBuffer.empty[String]
    val it = sscArgs.iterator
    while it.hasNext do
      it.next() match
        case "--debug"               => debug   = true
        case "--target"  if it.hasNext => target = Some(it.next())
        case "--offline"             => offline = true
        case "--verbose"             => verbose = true
        case f                       => files += f
    if files.size != 1 then
      System.err.println("Usage: ssc run-rust [flags] <file.ssc> [-- <program args>…]")
      System.exit(1)
    val file = files.head
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then
      System.err.println(s"run-rust: file not found: $file"); System.exit(1)

    val cargo = RustToolchain.findCargo()
      .getOrElse(RustToolchain.failMissingCargo("run-rust"))

    val stem     = path.last.stripSuffix(".ssc")
    val crateDir = os.temp.dir(prefix = "ssc-rust-")

    def cleanup(): Unit = try os.remove.all(crateDir) catch case _: Throwable => ()

    try
      // 1) emit.
      val extras = Map("binName" -> stem)
      compileViaBackend("rust", path, extras) match
        case CompileResult.Failed(diags) =>
          diags.foreach(d => System.err.println(s"[error] $d"))
          cleanup(); System.exit(1)
        case CompileResult.Segmented(segs) =>
          val assets = segs.collect { case a: Segment.Asset => a }
          if assets.isEmpty then
            System.err.println("run-rust: backend produced no assets")
            cleanup(); System.exit(1)
          for a <- assets do
            val out = crateDir / os.RelPath(a.name)
            os.makeDir.all(out / os.up)
            os.write.over(out, a.bytes)
          if verbose then
            System.err.println(s"run-rust: emitted ${assets.size} files to $crateDir")
        case other =>
          System.err.println(s"run-rust: unexpected ${other.getClass.getSimpleName}")
          cleanup(); System.exit(1)

      // 2) cargo build.
      val cargoArgs = scala.collection.mutable.ArrayBuffer[String](
        cargo.toString, "build"
      )
      if !debug   then cargoArgs += "--release"
      if !verbose then cargoArgs += "--quiet"
      if offline  then cargoArgs += "--offline"
      target.foreach { t => cargoArgs += "--target"; cargoArgs += t }
      val cargoProc = os.proc(cargoArgs.toList)
        .spawn(cwd = crateDir, stdout = os.Inherit, stderr = os.Inherit)
      val cargoHook = new Thread(() => killTree(cargoProc.wrapped.toHandle))
      Runtime.getRuntime.addShutdownHook(cargoHook)
      cargoProc.waitFor()
      try Runtime.getRuntime.removeShutdownHook(cargoHook) catch case _: IllegalStateException => ()
      val buildCode = cargoProc.wrapped.exitValue()
      if buildCode != 0 then
        System.err.println(s"run-rust: cargo build failed (exit $buildCode)")
        cleanup(); System.exit(buildCode)

      // 3) execute.
      val profile = if debug then "debug" else "release"
      val targetSubdir = target match
        case Some(t) => crateDir / "target" / t / profile
        case None    => crateDir / "target" / profile
      val binExt = if scala.util.Properties.isWin then ".exe" else ""
      val binary = targetSubdir / s"${RustToolchain.sanitizeBinName(stem)}$binExt"
      if !os.exists(binary) then
        System.err.println(s"run-rust: expected binary not found at $binary")
        cleanup(); System.exit(1)
      val binCmd  = (binary.toString :: binArgs)
      val binProc = os.proc(binCmd).spawn(stdout = os.Inherit, stderr = os.Inherit)
      val binHook = new Thread(() => killTree(binProc.wrapped.toHandle))
      Runtime.getRuntime.addShutdownHook(binHook)
      binProc.waitFor()
      try Runtime.getRuntime.removeShutdownHook(binHook) catch case _: IllegalStateException => ()
      val runCode = binProc.wrapped.exitValue()
      cleanup()
      if runCode != 0 then System.exit(runCode)
    catch case e: Throwable =>
      cleanup()
      throw e

  private def killTree(ph: ProcessHandle): Unit =
    ph.descendants().forEach(killTree(_))
    ph.destroyForcibly()
