package scalascript.cli

/** `ssc run --v2 <file.ssc>` — route a v1 `.ssc` through the v1 frontend → `FrontendBridge` → the v2 VM
 *  (the clean-room ssc 2.0 runtime), instead of the v1 tree-walking interpreter.
 *
 *  This is the Phase-3 preview mechanism for the v1→v2 migration: it lets the same source run on the v2
 *  engine from the normal CLI, without changing the default runner. It also underpins v1-vs-v2 output
 *  parity checks. Mirrors `ssc.bridge.bridgeCli`'s `run` path. */
object RunV2:
  // `_root_.ssc` disambiguates the ssc 2.0 package from the `def ssc(...)` CLI command in this package.
  def run(files: List[String], argv: List[String]): Unit =
    _root_.ssc.bridge.PluginBridge.loadAll()
    _root_.ssc.Runtime.argv = argv
    for file <- files do
      val f    = new java.io.File(file)
      val src  = scala.io.Source.fromFile(f).mkString
      val prog = _root_.ssc.bridge.FrontendBridge.convertSource(src, Some(f.getParentFile))
      _root_.ssc.Runtime.run(_root_.ssc.Compiler.compile(prog), Array.empty[_root_.ssc.Value]) match
        case _root_.ssc.Value.UnitV => ()
        case other                  => println(_root_.ssc.Show.show(other))
