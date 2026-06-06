package scalascript.cli

import scalascript.backend.spi.*

/** `ssc build-rust [-o <path>] [--debug] [--target <triple>] [--offline]
 *  [--verbose] [--keep-crate <dir>] <file.ssc>` — emit a Cargo crate to
 *  a temp dir, run `cargo build` inside it, and copy the produced binary
 *  to `-o <path>` (default `./<stem>`).  Pipeline mirrors `run-jvm` for
 *  the JVM target.  See specs/rust-backend.md §10. */
final class BuildRustCmd extends CliCommand:
  def name: String = "build-rust"
  override def summary: String = "Compile .ssc to a native binary in one step (via rust backend + cargo)"
  override def category: String = "Emit & transpile"
  override def details: List[String] = List(
    "Flags: -o <path>, --debug, --target <triple>, --offline, --verbose, --keep-crate <dir>"
  )

  def run(args: List[String]): Unit =
    var output:    Option[String] = None
    var debug                     = false
    var target:    Option[String] = None
    var offline                   = false
    var verbose                   = false
    var keepCrate: Option[String] = None
    val files = scala.collection.mutable.ArrayBuffer.empty[String]
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "-o" | "--output"      if it.hasNext => output    = Some(it.next())
        case "--debug"                            => debug     = true
        case "--target"             if it.hasNext => target    = Some(it.next())
        case "--offline"                          => offline   = true
        case "--verbose"                          => verbose   = true
        case "--keep-crate"         if it.hasNext => keepCrate = Some(it.next())
        case f                                    => files += f
    if files.size != 1 then
      System.err.println("Usage: ssc build-rust [flags] <file.ssc>")
      System.exit(1)
    val file = files.head
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then
      System.err.println(s"build-rust: file not found: $file"); System.exit(1)

    // Cargo presence — fixed message, exit 1, do nothing else.
    val cargo = RustToolchain.findCargo()
      .getOrElse(RustToolchain.failMissingCargo("build-rust"))

    val stem       = path.last.stripSuffix(".ssc")
    val outputPath = output.map(os.Path(_, os.pwd))
      .getOrElse(os.pwd / stem)
    val crateDir = keepCrate.map(os.Path(_, os.pwd))
      .getOrElse(os.temp.dir(prefix = "ssc-rust-"))

    val cleanup: () => Unit =
      if keepCrate.isDefined then () => ()
      else                       () => try os.remove.all(crateDir) catch case _: Throwable => ()

    try
      // 1) emit the crate.
      val extras = Map("binName" -> stem)
      compileViaBackend("rust", path, extras) match
        case CompileResult.Failed(diags) =>
          diags.foreach(d => System.err.println(s"[error] $d"))
          cleanup(); System.exit(1)
        case CompileResult.Segmented(segs) =>
          val assets = segs.collect { case a: Segment.Asset => a }
          if assets.isEmpty then
            System.err.println("build-rust: backend produced no assets")
            cleanup(); System.exit(1)
          os.makeDir.all(crateDir)
          for a <- assets do
            val out = crateDir / os.RelPath(a.name)
            os.makeDir.all(out / os.up)
            os.write.over(out, a.bytes)
          if verbose then
            System.err.println(s"build-rust: emitted ${assets.size} files to $crateDir")
        case other =>
          System.err.println(s"build-rust: unexpected ${other.getClass.getSimpleName}")
          cleanup(); System.exit(1)

      // 2) cargo build.
      val cargoArgs = scala.collection.mutable.ArrayBuffer[String](
        cargo.toString, "build"
      )
      if !debug   then cargoArgs += "--release"
      if !verbose then cargoArgs += "--quiet"
      if offline  then cargoArgs += "--offline"
      target.foreach { t => cargoArgs += "--target"; cargoArgs += t }

      val proc = os.proc(cargoArgs.toList)
        .spawn(cwd = crateDir, stdout = os.Inherit, stderr = os.Inherit)
      // Tear down cargo if the user Ctrl-Cs us.
      val hook = new Thread(() => killTree(proc.wrapped.toHandle))
      Runtime.getRuntime.addShutdownHook(hook)
      proc.waitFor()
      try Runtime.getRuntime.removeShutdownHook(hook) catch case _: IllegalStateException => ()
      val code = proc.wrapped.exitValue()
      if code != 0 then
        System.err.println(s"build-rust: cargo build failed (exit $code)")
        cleanup(); System.exit(code)

      // 3) locate + copy the binary.
      val profile = if debug then "debug" else "release"
      val targetSubdir = target match
        case Some(t) => crateDir / "target" / t / profile
        case None    => crateDir / "target" / profile
      val binExt = if scala.util.Properties.isWin then ".exe" else ""
      val producedBin = targetSubdir / s"$stem$binExt"
      if !os.exists(producedBin) then
        System.err.println(s"build-rust: expected binary not found at $producedBin")
        cleanup(); System.exit(1)
      val outFinal = if outputPath.last.endsWith(binExt) || binExt.isEmpty then outputPath
                     else outputPath / os.up / s"${outputPath.last}$binExt"
      os.makeDir.all(outFinal / os.up)
      os.copy.over(producedBin, outFinal)
      // Make sure the copy is executable.
      try outFinal.toIO.setExecutable(true) catch case _: Throwable => ()
      System.err.println(s"build-rust: wrote $outFinal")
      if keepCrate.isDefined then
        System.err.println(s"build-rust: cargo crate kept at $crateDir")
    finally
      cleanup()

  /** Recursively kill a process tree.  Same pattern as `run-jvm`. */
  private def killTree(ph: ProcessHandle): Unit =
    ph.descendants().forEach(killTree(_))
    ph.destroyForcibly()
