package scalascript.cli

/** `ssc run <file.ssc>` / `ssc run --v2 <file.ssc>` — route a v1 `.ssc` through the v1 frontend → `FrontendBridge` → the v2 VM
 *  (the clean-room ssc 2.0 runtime), instead of the v1 tree-walking interpreter.
 *
 *  This is the Phase-3 default runner for plain `ssc run` programs after the v1→v2 migration.
 *  `ssc run --v1` remains the rollback path for the old tree-walking interpreter. This runner
 *  also underpins v1-vs-v2 output parity checks. Mirrors `ssc.bridge.bridgeCli`'s `run` path. */
object RunV2:
  // `_root_.ssc` disambiguates the ssc 2.0 package from the `def ssc(...)` CLI command in this package.
  def run(files: List[String], argv: List[String]): Unit =
    loadPluginJars()
    _root_.ssc.bridge.PluginBridge.loadAll()
    _root_.ssc.Runtime.argv = argv
    for file <- files do
      val f    = new java.io.File(file)
      val src  = scala.io.Source.fromFile(f).mkString
      val prog = _root_.ssc.bridge.FrontendBridge.convertSource(src, Some(f.getParentFile))
      _root_.ssc.Runtime.run(_root_.ssc.Compiler.compile(prog), Array.empty[_root_.ssc.Value]) match
        case _root_.ssc.Value.UnitV => ()
        case other                  => println(_root_.ssc.Show.show(other))

  /** Make plugin Backends discoverable to `PluginBridge.loadAll()`'s ServiceLoader.
   *
   *  The `bin/ssc` launcher deliberately keeps plugin jars OFF the startup classpath — they ship as
   *  `.sscpkg` archives under `bin/lib/compiler/plugins/` and v1 loads them lazily on import. But the v2
   *  bridge registers plugin intrinsics via `ServiceLoader.load(classOf[Backend])` on the context class
   *  loader, which therefore sees none of them (so `signal` / `element` / … are unbound and every plugin
   *  example fails on `ssc run --v2`, even though the same program runs on the full classpath).
   *
   *  Extract each `.sscpkg`'s the `intrinsics/` jar into a fresh `URLClassLoader` (parent = the current
   *  context loader) and install it as the context loader BEFORE `loadAll()`, so ServiceLoader finds every
   *  plugin Backend. No-op — and harmless — when the plugins dir isn't present (sbt / test runs already
   *  have every plugin jar on the classpath); any failure falls back to the previous behaviour. */
  private[cli] def loadPluginJars(): Unit =
    try
      val dirs = pluginDirs
      if dirs.isEmpty then return
      val tmp  = java.nio.file.Files.createTempDirectory("ssc-v2-plugins").toFile
      tmp.deleteOnExit()
      val urls = dirs
        .flatMap(d => Option(d.listFiles).getOrElse(Array.empty[java.io.File]).toList)
        .filter(_.getName.endsWith(".sscpkg"))
        .flatMap(pkg => extractIntrinsicsJars(pkg, tmp))
      if urls.nonEmpty then
        val cl = new java.net.URLClassLoader(urls.toArray, Thread.currentThread().getContextClassLoader)
        Thread.currentThread().setContextClassLoader(cl)
    catch case _: Throwable => ()  // best-effort: fall back to the classpath as-is

  /** `bin/lib/compiler/plugins` (essential) PLUS `plugin-available` (advanced,
   *  opt-in on v1), located relative to the running `ssc.jar`. v1 lazy-loads
   *  the advanced set when an import demands it (plugin-lazyload-extern-imports),
   *  so the v2 parity runner must expose the same effective surface — with only
   *  the essential dir, every advanced-plugin native (Db.insert/update from
   *  sql-plugin, crypto, smtp, …) silently escaped as a Free Op on `run --v2`
   *  while the same program worked on v1. Natives register but only run when
   *  called, so loading both dirs is behaviour-neutral otherwise. */
  private def pluginDirs: List[java.io.File] =
    val libDir: Option[java.io.File] =
      try Some(new java.io.File(getClass.getProtectionDomain.getCodeSource.getLocation.toURI).getParentFile)
      catch case _: Throwable => Option(System.getProperty("ssc.lib.path")).map(p => new java.io.File(p, "bin/lib"))
    libDir.toList.flatMap { lib =>
      List(new java.io.File(lib, "compiler/plugins"), new java.io.File(lib, "compiler/plugin-available"))
    }.filter(_.isDirectory)

  private def extractIntrinsicsJars(pkg: java.io.File, tmp: java.io.File): List[java.net.URL] =
    import scala.jdk.CollectionConverters.*
    val zf = new java.util.zip.ZipFile(pkg)
    try
      zf.entries().asScala.toList
        // ALL bundled jars, not only intrinsics/: plugin natives reference
        // sibling helper jars inside the package (oauth's OidcIntrinsicHelpers
        // lives outside intrinsics/ and NoClassDefFound'ed at runtime).
        .filter(e => !e.isDirectory && e.getName.endsWith(".jar"))
        .map { e =>
          val leaf = e.getName.substring(e.getName.lastIndexOf('/') + 1)
          val out  = new java.io.File(tmp, s"${pkg.getName}-$leaf")
          val in   = zf.getInputStream(e)
          try java.nio.file.Files.copy(in, out.toPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          finally in.close()
          out.toURI.toURL
        }
    finally zf.close()
