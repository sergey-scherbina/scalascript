package scalascript.compiler.plugin

import java.net.URI

/** Shared installer for local paths, remote URLs, and registry short names.
 *
 *  Short-name resolution goes through the ONE package registry
 *  (`scalascript.imports.RegistryClient`, `packages.yaml`) — the same catalog `ssc plugin search`
 *  and `ssc add` use, filtered to nothing in particular (a plugin's flat id resolves the same way
 *  a library's bare artifact name does; see `RegistryClient.resolveByName`).
 */
object RemotePluginInstaller:

  case class Installed(manifest: SscpkgManifest, path: os.Path)

  val defaultInstallDir: os.Path =
    os.home / ".scalascript" / "compiler" / "plugins"

  def install(source: String, installDir: os.Path = defaultInstallDir): Installed =
    val resolved = resolveSource(source)
    installBytes(readBytes(resolved), installDir)

  def install(uri: URI, installDir: os.Path): Installed =
    install(uri.toString, installDir)

  def installBytes(bytes: Array[Byte], installDir: os.Path = defaultInstallDir): Installed =
    val tmp = os.temp(bytes, suffix = ".sscpkg")
    val manifest =
      try SscpkgLoader.load(tmp).manifest
      finally os.remove(tmp)
    os.makeDir.all(installDir)
    val dest = installDir / s"${manifest.id}-${manifest.version}.sscpkg"
    os.write.over(dest, bytes)
    Installed(manifest, dest)

  def resolveSource(source: String): String =
    if isHttp(source) || os.exists(os.Path(source, os.pwd)) then source
    else
      val entries = scalascript.imports.RegistryClient.load()
      scalascript.imports.RegistryClient.resolveByName(source, entries) match
        case Some(entry) => entry.url
        case None =>
          throw RuntimeException(s"'$source' is not a file, URL, or known registry entry")

  private def readBytes(source: String): Array[Byte] =
    if isHttp(source) then
      if sys.env.get("SSC_NO_NETWORK").contains("1") then
        throw RuntimeException(s"SSC_NO_NETWORK=1 blocks auto-download from $source")
      val req = java.net.http.HttpRequest.newBuilder(URI.create(source)).GET().build()
      val resp = java.net.http.HttpClient.newHttpClient()
        .send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
      if resp.statusCode() != 200 then
        throw RuntimeException(s"HTTP ${resp.statusCode()} from $source")
      resp.body()
    else
      val path = os.Path(source, os.pwd)
      if !os.exists(path) then throw RuntimeException(s"not found: $source")
      os.read.bytes(path)

  private def isHttp(source: String): Boolean =
    source.startsWith("http://") || source.startsWith("https://")
