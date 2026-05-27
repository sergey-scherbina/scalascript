package scalascript.compiler.plugin.deploy

/** Maps ArtifactKind to the builder that produces the artifact.
 *  Builders for existing kinds are stubs — the real invocations live in
 *  `scalascript.cli.Main` and are called through `ssc` subcommands.
 *  v1.52.1 only uses FatJar (for the local subprocess adapter). */
object ArtifactRegistry:

  /** Build the artifact for a deploy context, returning the path.
   *  Delegates to the appropriate builder based on kind. */
  def build(kind: ArtifactKind, workDir: os.Path, verbose: Boolean): String =
    kind match
      case ArtifactKind.FatJar =>
        val out = (workDir / ".ssc-artifacts" / "app.jar").toString
        if verbose then println(s"[deploy/build] buildFatJar → $out")
        out
      case ArtifactKind.NativeBinary =>
        val out = (workDir / ".ssc-artifacts" / "app").toString
        if verbose then println(s"[deploy/build] nativeBinary → $out")
        out
      case ArtifactKind.NodeBundle =>
        val out = (workDir / ".ssc-artifacts" / "app.js").toString
        if verbose then println(s"[deploy/build] nodeBundle → $out")
        out
      case ArtifactKind.SpaBundle =>
        val out = (workDir / ".ssc-artifacts" / "dist").toString
        if verbose then println(s"[deploy/build] spaBundle → $out")
        out
      case other =>
        throw DeployError(s"[deploy/artifact-build-failed] ArtifactKind.$other not yet implemented in v1.52.1")

  def artifactKindFor(kindStr: String): ArtifactKind =
    kindStr.toLowerCase match
      case "fat-jar" | "fatjar"         => ArtifactKind.FatJar
      case "thin-jar" | "thinjar"       => ArtifactKind.ThinJar
      case "native" | "native-binary"   => ArtifactKind.NativeBinary
      case "node" | "node-bundle"       => ArtifactKind.NodeBundle
      case "spa" | "spa-bundle"         => ArtifactKind.SpaBundle
      case "oci" | "oci-image"          => ArtifactKind.OciImage
      case "war"                        => ArtifactKind.War
      case "lambda" | "lambda-zip"      => ArtifactKind.LambdaZip
      case "tarball" | "tar"            => ArtifactKind.Tarball
      case "rsync" | "rsync-tree"       => ArtifactKind.RsyncTree
      case _                            => ArtifactKind.FatJar
