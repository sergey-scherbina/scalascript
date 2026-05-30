package scalascript.cli

import scalascript.parser.Parser

// Dependency-management commands: lock / lock check / update (+ collectDepImports)
// and the registry shortcuts search / add. Extracted from Main.scala; all type
// deps are imported locally per method or referenced fully-qualified.

def lockCommand(args: List[String]): Unit =
  args match
    case "check" :: rest => lockCheckCommand(rest)
    case rest            => lockPinCommand(rest)

private def collectUrlImports(module: scalascript.ast.Module): List[String] =
  def fromSection(s: scalascript.ast.Section): List[String] =
    val direct = s.content.collect {
      case scalascript.ast.Content.Import(path, _, _)
          if path.startsWith("http://") || path.startsWith("https://") || path.startsWith("dep:") => path
    }
    direct ++ s.subsections.flatMap(fromSection)
  module.sections.flatMap(fromSection)

private def lockPinCommand(args: List[String]): Unit =
  if args.isEmpty then { System.err.println("lock: no file specified"); System.exit(1) }
  val file = args.head
  val path = os.Path(file, os.pwd)
  if !os.exists(path) then { System.err.println(s"lock: file not found: $file"); System.exit(1) }
  val lockPath = path / os.up / "ssc.lock"
  try
    val module  = Parser.parse(os.read(path))
    val urls    = collectUrlImports(module)
    if urls.isEmpty then
      println("lock: no URL/dep imports found")
    else
      import scalascript.imports.{ImportResolver, LockFile}
      val baseDir = path / os.up
      val deps    = module.manifest.map(_.dependencies).getOrElse(Map.empty)
      var lock    = LockFile.read(lockPath).getOrElse(LockFile.empty)
      for url <- urls do
        val resolved = ImportResolver.resolve(url, baseDir, deps, lockPath = Some(lockPath))
        val content  = os.read.bytes(resolved)
        lock = lock.pin(url, content)
        println(s"  pinned $url")
      LockFile.write(lock, lockPath)
      println(s"lock: wrote ${lockPath.relativeTo(os.pwd)}")
  catch case e: Exception =>
    System.err.println(s"lock error: ${e.getMessage}")
    System.exit(1)

private def lockCheckCommand(args: List[String]): Unit =
  if args.isEmpty then { System.err.println("lock check: no file specified"); System.exit(1) }
  val file = args.head
  val path = os.Path(file, os.pwd)
  if !os.exists(path) then { System.err.println(s"lock check: file not found: $file"); System.exit(1) }
  val lockPath = path / os.up / "ssc.lock"
  if !os.exists(lockPath) then
    System.err.println(s"lock check: no ssc.lock at ${lockPath.relativeTo(os.pwd)}")
    System.exit(1)
  try
    import scalascript.imports.{ImportResolver, LockFile}
    val module  = Parser.parse(os.read(path))
    val urls    = collectUrlImports(module)
    val lock    = LockFile.read(lockPath).fold(e => throw e, identity)
    val baseDir = path / os.up
    val deps    = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    var hasErrors = false
    for url <- urls do
      lock.entries.get(url) match
        case None =>
          System.err.println(s"  MISSING in lock: $url")
          hasErrors = true
        case Some(_) =>
          val resolved = ImportResolver.resolve(url, baseDir, deps, lockPath = Some(lockPath))
          val content  = os.read.bytes(resolved)
          lock.check(url, content) match
            case Left(err)  => System.err.println(s"  FAIL: $err"); hasErrors = true
            case Right(_)   => println(s"  ok $url")
    if hasErrors then System.exit(1) else println("lock check: all OK")
  catch case e: Exception =>
    System.err.println(s"lock check error: ${e.getMessage}")
    System.exit(1)

// ─────────────────────────────────────────────────────────────────────────────
// ssc search — search the ScalaScript package registry
// ssc info <name> — show registry entry details  (see also infoCommand above)
// ssc add <name> [<version>] — add a dep to the current project manifest
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc search [<query>] [--refresh]`
 *
 *  Downloads + caches `packages.yaml` from the registry, then performs
 *  substring/keyword matching on the query.  `--refresh` bypasses the cache. */
def registrySearchCommand(args: List[String]): Unit =
  import scalascript.imports.RegistryClient
  var refresh     = false
  var query       = ""
  var registryArg: Option[String] = None
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--refresh"                      => refresh = true
      case "--registry" if it.hasNext       => registryArg = Some(it.next())
      case q                                => query = q
  val url    = RegistryClient.effectiveUrl(registryArg)
  val cached = registryArg.isEmpty && RegistryClient.isCacheFresh
  if refresh || !cached then print("Fetching registry... ")
  val entries = RegistryClient.load(url, refresh = refresh || registryArg.isDefined)
  if refresh || !cached then println(s"(${entries.length} packages)")
  if entries.isEmpty then
    println("Registry is empty or could not be fetched.  Try --refresh.")
    return
  val results = RegistryClient.search(query, entries)
  if results.isEmpty then
    println(s"No packages found for '${query}'.")
    println("Run `ssc search` (no query) to list all packages.")
  else
    if query.nonEmpty then println(s"${results.length} result(s) for '${query}':")
    results.foreach(e => println(RegistryClient.formatRow(e)))
    if results.length == 1 then println(s"\nUse `ssc info ${results.head.name}` for details.")

/** `ssc add <name> [<version>] [--file <manifest>]`
 *
 *  Looks up `name` in the registry, then appends a dep: entry to the current
 *  project's `ssclib-manifest.yaml` (creates it if absent) or to the
 *  front-matter `dependencies:` of a single-file `.ssc`. */
def registryAddCommand(args: List[String]): Unit =
  import scalascript.imports.{RegistryClient, SsclibManifest}
  var nameArg:    String         = ""
  var versionArg: Option[String] = None
  var fileArg:    Option[String] = None
  var registryArg: Option[String] = None
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--file" | "-f" if it.hasNext    => fileArg     = Some(it.next())
      case "--registry" if it.hasNext       => registryArg = Some(it.next())
      case a if nameArg.isEmpty             => nameArg     = a
      case v                                => versionArg  = Some(v)

  if nameArg.isEmpty then
    System.err.println("Usage: ssc add <name> [<version>] [--file <manifest>] [--registry <url>]")
    System.exit(1)

  // Resolve the version: explicit arg > registry lookup > error.
  val version = versionArg.getOrElse {
    val url     = RegistryClient.effectiveUrl(registryArg)
    val entries = RegistryClient.load(url, refresh = registryArg.isDefined)
    entries.find(_.name == nameArg) match
      case None =>
        System.err.println(s"add: package '$nameArg' not found in registry.")
        System.err.println(s"Specify a version explicitly: ssc add $nameArg <version>")
        System.exit(1); ""
      case Some(e) => e.version
  }

  val depEntry = s"dep:$nameArg:$version"

  // Find / create a ssclib-manifest.yaml.
  val manifestPath = fileArg
    .map(f => os.Path(f, os.pwd))
    .getOrElse(os.pwd / SsclibManifest.FileName)

  if os.exists(manifestPath) then
    val existing = SsclibManifest.parseString(os.read(manifestPath)).getOrElse {
      System.err.println(s"add: cannot parse ${manifestPath.last}"); System.exit(1)
      SsclibManifest(name = "")
    }
    if existing.dependencies.contains(depEntry) then
      println(s"$nameArg:$version is already in ${manifestPath.last}")
      return
    val updated = existing.copy(dependencies = existing.dependencies :+ depEntry)
    os.write.over(manifestPath, SsclibManifest.toYaml(updated))
  else
    val manifest = SsclibManifest(
      name         = "my-project",
      dependencies = List(depEntry),
    )
    os.write(manifestPath, SsclibManifest.toYaml(manifest))

  println(s"Added $nameArg:$version to ${manifestPath.last}")
  println(s"Run `ssc update` to download and lock.")

// ─────────────────────────────────────────────────────────────────────────────
// ssc update — refresh transitive dep resolution + write ssc-lock.yaml
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc update [<file.ssc>] [--strict-deps]`
 *
 *  Re-resolves all `dep:` imports in the project (including transitive deps),
 *  clears the `.ssclib` extraction cache for changed versions, and writes
 *  `ssc-lock.yaml` recording the full resolved-version map.
 *
 *  Without `--strict-deps` (default), version conflicts are resolved by picking
 *  the highest version.  With `--strict-deps`, any conflict is an error. */
def updateCommand(args: List[String]): Unit =
  import scalascript.imports.{ImportResolver, SscLibLock}

  var strictDeps = false
  var fileArg: Option[String] = None
  val it = args.iterator
  while it.hasNext do
    it.next() match
      case "--strict-deps"     => strictDeps = true
      case "--no-strict-deps"  => strictDeps = false
      case f                   => fileArg = Some(f)

  val projectFile = fileArg
    .map(f => os.Path(f, os.pwd))
    .orElse(findProjectSsc())
    .getOrElse {
      System.err.println("ssc update: no project file found (pass a .ssc file or run from a project directory)")
      System.exit(1); os.pwd / "not-found.ssc"
    }

  if !os.exists(projectFile) then
    System.err.println(s"ssc update: file not found: $projectFile"); System.exit(1)

  try
    val module  = Parser.parse(os.read(projectFile))
    val depUris = collectDepImports(module)
    if depUris.isEmpty then
      println("ssc update: no dep: imports found")
      return

    println(s"Resolving ${depUris.size} top-level dep(s) transitively...")
    val lock = ImportResolver.resolveAll(depUris, lockPath = None, strictDeps = strictDeps)
    val lockPath = projectFile / os.up / SscLibLock.FileName
    SscLibLock.write(lock, lockPath)
    println(s"Resolved ${lock.locked.size} dep(s):")
    lock.locked.toList.sortBy(_._1).foreach { (dep, ver) =>
      println(s"  $dep  $ver")
    }
    println(s"\nWrote ${lockPath.relativeTo(os.pwd)}")
  catch case e: Exception =>
    System.err.println(s"ssc update error: ${e.getMessage}")
    System.exit(1)

private def collectDepImports(module: scalascript.ast.Module): List[String] =
  def fromSection(s: scalascript.ast.Section): List[String] =
    val direct = s.content.collect {
      case scalascript.ast.Content.Import(path, _, _) if path.startsWith("dep:") => path
    }
    direct ++ s.subsections.flatMap(fromSection)
  module.sections.flatMap(fromSection)

// ─────────────────────────────────────────────────────────────────────────────
// ssc test <file(s)>  —  v0.9 component-level unit test runner
// ─────────────────────────────────────────────────────────────────────────────

/** `ssc test <file.ssc> [<file.ssc>...]`
 *
 *  Runs each file through the interpreter with an injected `test(name, thunk)`
 *  builtin.  At runtime a `.ssc` test file calls:
 *
 *    test("renders sm", () => Spinner.render("sm").contains("sm"))
 *    test("renders lg", () => Spinner.render("lg").contains("lg"))
 *
 *  The runner collects all registrations, then after the module finishes it
 *  executes each thunk and prints a coloured PASS / FAIL line.  Exit status is
 *  1 if any test failed, 0 if all passed.
 *
 *  If no file is given the runner looks for `*-test.ssc` siblings next to the
 *  component being tested (same directory).
 *
 *  Backend matrix: interpreter only (cross-backend conformance is handled by
 *  `conformance/`; this runner is for component-authored fast unit tests).
 */
