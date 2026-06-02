package scalascript.cli

/** `ssc lsp` - run the Language Server Protocol server over stdio.
 *
 *  No options for now. Reads framed JSON-RPC from stdin, writes to stdout,
 *  logs to stderr. Exits with the JSON-RPC negotiated exit code (0 if
 *  `shutdown` preceded `exit`, 1 otherwise). */
final class LspCmd extends CliCommand:
  def name = "lsp"
  override def summary = "Run the Language Server Protocol server over stdio"
  override def category = "Services & tooling"
  override def runResult(args: List[String]): CommandResult =
    import scalascript.cli.lsp.LspServer
    // Currently unused; reserved for future flags such as --log-file or
    // --artifact-dir. Ignored silently in the MVP.
    val _ = args
    CommandResult.exit(LspServer.runStdio())

  def run(args: List[String]): Unit =
    runResult(args).exitNow()

/** `ssc generate-facade <artifactDir> [-o <outputDir>]`
 *
 *  Reads all `.scim` artifacts from `artifactDir`, runs
 *  `FacadeGenerator.generate`, and writes the resulting Scala 3 source
 *  files to `outputDir` (default: current working directory).
 *
 *  Exits 0 even when no facade is emitted (identity-mapped Tier-5
 *  artifacts produce no file; that is expected, not an error). */
final class GenerateFacadeCmd extends CliCommand:
  def name = "generate-facade"
  override def summary = "Emit Scala 3 facade sources from .scim artifacts"
  override def category = "Separate compilation (v2.0)"
  def run(args: List[String]): Unit =
    var artifactDir: Option[String] = None
    var outputDir:   Option[String] = None
    var rest = args
    while rest.nonEmpty do
      rest.head match
        case "-o" | "--output" =>
          rest = rest.tail
          if rest.isEmpty then
            System.err.println("generate-facade: -o requires a directory argument")
            System.exit(1)
          outputDir = Some(rest.head)
          rest = rest.tail
        case flag if flag.startsWith("-") =>
          System.err.println(s"generate-facade: unknown flag $flag")
          System.err.println("Usage: ssc generate-facade <artifactDir> [-o <outputDir>]")
          System.exit(1)
        case dir =>
          if artifactDir.isDefined then
            System.err.println("generate-facade: too many positional arguments")
            System.err.println("Usage: ssc generate-facade <artifactDir> [-o <outputDir>]")
            System.exit(1)
          artifactDir = Some(dir)
          rest = rest.tail

    if artifactDir.isEmpty then
      System.err.println("Usage: ssc generate-facade <artifactDir> [-o <outputDir>]")
      System.err.println("  Read .scim artifacts from <artifactDir>, emit Scala 3 facade sources.")
      System.err.println("  Outputs to <outputDir> (default: current directory).")
      System.exit(1)

    val artPath = os.Path(java.nio.file.Paths.get(artifactDir.get).toAbsolutePath)
    val outPath = os.Path(java.nio.file.Paths.get(outputDir.getOrElse(".")).toAbsolutePath)

    if !os.exists(artPath) || !os.isDir(artPath) then
      System.err.println(s"generate-facade: artifact directory not found: $artPath")
      System.exit(1)

    os.makeDir.all(outPath)

    val sources = scalascript.interop.facade.FacadeGenerator.generate(artPath)
    if sources.isEmpty then
      // Tier-5 identity artifacts produce no facade; this is normal.
      System.err.println("[ssc] generate-facade: no legacy facade entries found; nothing written.")
    else
      for (relPath, content) <- sources.toList.sortBy(_._1) do
        val target = outPath / os.RelPath(relPath)
        os.makeDir.all(target / os.up)
        os.write.over(target, content)
        System.err.println(s"[ssc] generate-facade: wrote ${outPath.relativeTo(os.pwd)}/${relPath}")
