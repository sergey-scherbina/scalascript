package scalascript.cli

import scalascript.backend.spi.*

/** Shared emit + `cargo run` logic for the live terminal UI path, used by both
 *  the `tui` command and `run --frontend tui` (rust-tui-toolkit S5).
 *
 *  Emits the rust crate with `uiTarget=tui` (so the ratatui `tui.rs` renderer +
 *  the fetch/DataTable overlay are wired) and `cargo run`s it with the terminal
 *  inherited on all three std streams — the crossterm event loop needs live
 *  stdin. */
object TuiRunner:

  /** True when a `cargo` binary is on PATH — the live path is only taken when so. */
  def cargoAvailable: Boolean = RustToolchain.findCargo().isDefined

  /** Parse `tui`-command args (`[--debug] [--offline] [--verbose] <file> [-- args]`),
   *  then emit + run. Returns the process exit code (1 on usage/emit error). */
  def run(args: List[String]): Int =
    val (sscArgs, binArgs) = args.span(_ != "--") match
      case (lhs, Nil)       => (lhs, Nil)
      case (lhs, _ :: rest) => (lhs, rest)
    var debug   = false
    var offline = false
    var verbose = false
    val files   = scala.collection.mutable.ArrayBuffer.empty[String]
    val it = sscArgs.iterator
    while it.hasNext do
      it.next() match
        case "--debug"   => debug   = true
        case "--offline" => offline = true
        case "--verbose" => verbose = true
        case f           => files += f
    if files.size != 1 then
      System.err.println("Usage: ssc tui [--debug] [--offline] [--verbose] <file.ssc> [-- <program args>…]")
      return 1
    val path = os.Path(files.head, os.pwd)
    if !os.exists(path) then
      System.err.println(s"tui: file not found: ${files.head}")
      return 1
    runFile(path, binArgs, debug, offline, verbose)

  /** Emit the tui crate for an already-resolved `path` and `cargo run` it.
   *  Shared with `run --frontend tui` (which passes the defaults). */
  def runFile(
      path:    os.Path,
      binArgs: List[String] = Nil,
      debug:   Boolean      = false,
      offline: Boolean      = false,
      verbose: Boolean      = false
  ): Int =
    val cargo    = RustToolchain.findCargo().getOrElse(RustToolchain.failMissingCargo("tui"))
    val stem     = path.last.stripSuffix(".ssc")
    val crateDir = os.temp.dir(prefix = "ssc-tui-")
    def cleanup(): Unit = try os.remove.all(crateDir) catch case _: Throwable => ()
    try
      // 1) emit the rust crate with the tui View renderer.
      val extras = Map("binName" -> stem, "uiTarget" -> "tui")
      compileViaBackend("rust", path, extras) match
        case CompileResult.Failed(diags) =>
          diags.foreach(d => System.err.println(s"[error] $d")); cleanup(); return 1
        case CompileResult.Segmented(segs) =>
          val assets = segs.collect { case a: Segment.Asset => a }
          if assets.isEmpty then
            System.err.println("tui: backend produced no assets"); cleanup(); return 1
          for a <- assets do
            val out = crateDir / os.RelPath(a.name)
            os.makeDir.all(out / os.up)
            os.write.over(out, a.bytes)
          if verbose then System.err.println(s"tui: emitted ${assets.size} files to $crateDir")
        case other =>
          System.err.println(s"tui: unexpected ${other.getClass.getSimpleName}"); cleanup(); return 1

      // 2) cargo run — inherit ALL three streams (the crossterm loop reads stdin).
      val cargoArgs = scala.collection.mutable.ArrayBuffer[String](cargo.toString, "run")
      if !debug   then cargoArgs += "--release"
      if !verbose then cargoArgs += "--quiet"
      if offline  then cargoArgs += "--offline"
      if binArgs.nonEmpty then { cargoArgs += "--"; cargoArgs ++= binArgs }
      val proc = os.proc(cargoArgs.toList)
        .spawn(cwd = crateDir, stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
      val hook = new Thread(() => proc.wrapped.toHandle.descendants().forEach(_.destroyForcibly()))
      Runtime.getRuntime.addShutdownHook(hook)
      proc.waitFor()
      try Runtime.getRuntime.removeShutdownHook(hook) catch case _: IllegalStateException => ()
      val code = proc.wrapped.exitValue()
      cleanup()
      code
    catch case e: Throwable =>
      cleanup(); throw e
