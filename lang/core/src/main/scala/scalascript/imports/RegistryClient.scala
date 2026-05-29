package scalascript.imports

import scala.util.Try
import java.net.{HttpURLConnection, URI}

/** HTTP client for the ScalaScript package registry.
 *
 *  Fetches `packages.yaml` from the registry URL.  URL priority:
 *    1. `--registry <url>` CLI flag (callers pass this explicitly)
 *    2. `registry.url` key in `~/.config/scalascript/config.yaml`
 *    3. Built-in default `https://registry.scalascript.io/packages.yaml`
 *
 *  Results cached to `~/.cache/scalascript/registry/packages.yaml` with a
 *  1-hour TTL.  Search runs locally via substring + keyword matching.
 *
 *  See `docs/arch-registry.md §3c, §3d Phase 4`. */
object RegistryClient:

  val DefaultRegistryUrl  = "https://registry.scalascript.io/packages.yaml"
  val CacheTtlMs: Long    = 60L * 60 * 1000   // 1 hour
  val ConfigFile: os.Path = os.home / ".config" / "scalascript" / "config.yaml"

  private def cacheFile: os.Path =
    os.home / ".cache" / "scalascript" / "registry" / "packages.yaml"

  private def cacheMetaFile: os.Path =
    os.home / ".cache" / "scalascript" / "registry" / "packages.yaml.ts"

  /** Read `registry.url` from `~/.config/scalascript/config.yaml`, if set. */
  def registryUrlFromConfig(): Option[String] =
    if !os.exists(ConfigFile) then None
    else
      Try {
        import scalascript.parser.SimpleYaml
        import scala.jdk.CollectionConverters.*
        val raw = SimpleYaml.load[java.util.Map[String, Any]](os.read(ConfigFile))
        Option(raw).flatMap(_.asScala.get("registry.url")).map(_.toString.trim).filter(_.nonEmpty)
      }.toOption.flatten

  /** Effective registry URL: explicit override > config file > default. */
  def effectiveUrl(registryArg: Option[String] = None): String =
    registryArg.filter(_.nonEmpty)
      .orElse(registryUrlFromConfig())
      .getOrElse(DefaultRegistryUrl)

  /** Fetch-or-load the registry.  Returns all entries (empty list on error).
   *
   *  Cache hit: if the cache is fresh (< `CacheTtlMs`), read from disk.
   *  Cache miss / `refresh = true`: fetch from `url`, write to cache. */
  def load(url: String = DefaultRegistryUrl, refresh: Boolean = false): List[RegistryEntry] =
    if !refresh && isCacheFresh then
      readCache().getOrElse(fetchAndCache(url))
    else
      fetchAndCache(url)

  def isCacheFresh: Boolean =
    if !os.exists(cacheFile) || !os.exists(cacheMetaFile) then false
    else
      Try(os.read(cacheMetaFile).trim.toLong).toOption.exists { ts =>
        System.currentTimeMillis() - ts < CacheTtlMs
      }

  private def readCache(): Option[List[RegistryEntry]] =
    if !os.exists(cacheFile) then None
    else
      RegistryEntry.parseAll(os.read(cacheFile)).toOption

  def fetchAndCache(url: String = DefaultRegistryUrl): List[RegistryEntry] =
    fetchYaml(url) match
      case None       => Nil
      case Some(yaml) =>
        val entries = RegistryEntry.parseAll(yaml).getOrElse(Nil)
        os.makeDir.all(cacheFile / os.up)
        os.write.over(cacheFile, yaml)
        os.write.over(cacheMetaFile, System.currentTimeMillis().toString)
        entries

  private def fetchYaml(url: String): Option[String] =
    if url.startsWith("file://") || url.startsWith("file:") then
      // Local file URL — read directly (used for tests and local mirrors).
      Try {
        val path = java.nio.file.Paths.get(URI.create(url))
        Some(new String(java.nio.file.Files.readAllBytes(path), "UTF-8"))
      }.toOption.flatten
    else
      Try {
        val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
        conn.setConnectTimeout(8000)
        conn.setReadTimeout(15000)
        conn.setRequestProperty("Accept", "text/plain, application/x-yaml, */*")
        conn.setRequestProperty("User-Agent", "ssc/registry-client")
        try
          if conn.getResponseCode == 200 then
            Some(new String(conn.getInputStream.readAllBytes(), "UTF-8"))
          else None
        finally conn.disconnect()
      }.toOption.flatten

  /** Search entries by name, description, and keywords.
   *  Returns entries sorted by relevance (name prefix > name contains > keyword/desc). */
  def search(query: String, entries: List[RegistryEntry]): List[RegistryEntry] =
    val q = query.toLowerCase.trim
    if q.isEmpty then entries
    else
      def score(e: RegistryEntry): Int =
        val n = e.name.toLowerCase
        val d = e.description.toLowerCase
        val k = e.keywords.map(_.toLowerCase)
        if n == q then 10
        else if n.startsWith(q) then 8
        else if n.contains(q) then 6
        else if d.contains(q) then 4
        else if k.exists(_.contains(q)) then 2
        else 0
      entries.filter(score(_) > 0).sortBy(-score(_))

  /** Format a compact one-line search result row. */
  def formatRow(e: RegistryEntry): String =
    val namePad = f"${e.name}%-32s"
    val verPad  = f"${e.version}%-8s"
    val desc    = if e.description.nonEmpty then e.description else ""
    val backs   = if e.backends.nonEmpty then s"  [${e.backends.mkString(", ")}]" else ""
    val dep = if e.deprecated then "  [DEPRECATED]" else ""
    s"  $namePad $verPad $desc$backs$dep".stripTrailing

  /** Format a multi-line detail view for `ssc info`. */
  def formatInfo(e: RegistryEntry): String =
    val sb = new StringBuilder
    sb.append(s"${e.name} ${e.version}")
    if e.description.nonEmpty then sb.append(s" — ${e.description}")
    sb.append('\n')
    if e.author.nonEmpty          then sb.append(f"  Author:   ${e.author}\n")
    if e.license.nonEmpty         then sb.append(f"  License:  ${e.license}\n")
    if e.backends.nonEmpty        then sb.append(f"  Backends: ${e.backends.mkString(", ")}\n")
    if e.scalaScriptVersion.nonEmpty then sb.append(f"  Requires: ssc ${e.scalaScriptVersion}\n")
    if e.url.nonEmpty             then sb.append(f"  URL:      ${e.url}\n")
    if e.homepage.nonEmpty        then sb.append(f"  Homepage: ${e.homepage}\n")
    if e.changelog.nonEmpty       then sb.append(f"  Changelog: ${e.changelog}\n")
    if e.deprecated               then sb.append("  [DEPRECATED]\n")
    sb.append('\n')
    sb.append("  Install:\n")
    sb.append(s"""    //> using dep "${e.name}:${e.version}"\n""")
    sb.append(s"""    [${e.name.split('/').last}](dep:${e.name}:${e.version})\n""")
    sb.toString

  /** Parse the cache timestamp (for testing). */
  def cacheTimestamp: Option[Long] =
    if os.exists(cacheMetaFile) then Try(os.read(cacheMetaFile).trim.toLong).toOption
    else None

  def clearCache(): Unit =
    if os.exists(cacheFile)     then os.remove(cacheFile)
    if os.exists(cacheMetaFile) then os.remove(cacheMetaFile)
