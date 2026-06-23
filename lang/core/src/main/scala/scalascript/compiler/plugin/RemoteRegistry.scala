package scalascript.compiler.plugin

import java.security.MessageDigest
import scala.util.Try

/** Remote package registry — protocol model + a file-backed reference implementation
 *  (remote-package-registry slice 1; `specs/arch-build-registry.md` §"Remote registry").
 *
 *  This is the SERVER-SIDE catalog that unlocks the third-party plugin ecosystem: it stores published
 *  `.sscpkg` artifacts by `(id, version)` with a SHA-256 checksum and answers publish / search / resolve /
 *  fetch. It is distinct from [[LocalRegistry]] (a static client-side name→URL alias map). The index +
 *  entries serialize as JSON — the wire format a future HTTP `registry.scalascript.io` will serve — so the
 *  file-backed [[FileRegistry]] below is at once the round-trip test harness AND the reference an HTTP
 *  service wraps. CLI (`ssc publish`/`ssc search`) and remote `pkg:` resolution are follow-up slices. */
object RemoteRegistry:

  /** One published artifact: a name+version with the SHA-256 of its `.sscpkg` bytes. */
  case class Entry(id: String, version: String, sha256: String, description: String = ""):
    def toJson: ujson.Obj =
      ujson.Obj("id" -> id, "version" -> version, "sha256" -> sha256, "description" -> description)

  object Entry:
    def fromJson(j: ujson.Value): Entry =
      Entry(
        id          = j("id").str,
        version     = j("version").str,
        sha256      = j("sha256").str,
        description = j.obj.get("description").map(_.str).getOrElse(""))

  /** SHA-256 hex digest of artifact bytes (the integrity contract between publish and install). */
  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  /** Order dotted versions numerically (`1.2.10` > `1.2.9`); a non-numeric segment falls back to a
   *  lexical compare of that segment, and a shorter prefix is the smaller version (`1.2` < `1.2.0`). */
  def compareVersions(a: String, b: String): Int =
    val as = a.split('.'); val bs = b.split('.')
    val n = math.max(as.length, bs.length)
    var i = 0
    while i < n do
      val x = if i < as.length then as(i) else ""
      val y = if i < bs.length then bs(i) else ""
      val c = (x.toIntOption, y.toIntOption) match
        case (Some(xi), Some(yi)) => xi.compare(yi)
        case _                    => x.compare(y)
      if c != 0 then return c
      i += 1
    0

  /** Serialize a catalog of entries to the JSON index wire format. */
  def indexToJson(entries: List[Entry]): String =
    ujson.write(ujson.Obj("packages" -> ujson.Arr(entries.map(_.toJson)*)), indent = 2)

  /** Parse the JSON index wire format (tolerant: missing/empty → no entries). */
  def indexFromJson(json: String): List[Entry] =
    Try {
      val root = ujson.read(json)
      root.obj.get("packages") match
        case Some(arr: ujson.Arr) => arr.arr.map(Entry.fromJson).toList
        case _                    => Nil
    }.getOrElse(Nil)

/** A directory-backed registry: the reference server, the test harness, and the seed an HTTP
 *  `registry.scalascript.io` wraps. Layout under `root`:
 *  {{{
 *  root/index.json                       # the catalog (JSON, the wire format)
 *  root/packages/<id>/<version>.sscpkg   # the stored artifact bytes
 *  }}}
 */
class FileRegistry(root: os.Path):
  import RemoteRegistry.*

  private def indexPath: os.Path                       = root / "index.json"
  private def pkgPath(id: String, version: String): os.Path = root / "packages" / id / s"$version.sscpkg"

  /** Current catalog (empty if the registry has no index yet). */
  def index: List[Entry] =
    if os.exists(indexPath) then indexFromJson(os.read(indexPath)) else Nil

  private def writeIndex(entries: List[Entry]): Unit =
    os.makeDir.all(root)
    os.write.over(indexPath, indexToJson(entries.sortBy(e => (e.id, e.version))))

  /** Publish artifact `bytes` under `(id, version)`. Stores the bytes, records the SHA-256 in the index,
   *  and returns the [[Entry]]. Re-publishing the SAME `(id, version)` with byte-identical content is
   *  idempotent; re-publishing with DIFFERENT content is rejected (immutable releases — a published
   *  version never changes under consumers). Throws on a malformed id/version. */
  def publish(id: String, version: String, bytes: Array[Byte], description: String = ""): Entry =
    require(id.nonEmpty && !id.contains('/'), s"invalid package id: '$id'")
    require(version.nonEmpty && !version.contains('/'), s"invalid version: '$version'")
    val digest  = sha256Hex(bytes)
    val current = index
    current.find(e => e.id == id && e.version == version) match
      case Some(existing) if existing.sha256 != digest =>
        throw new IllegalStateException(
          s"$id@$version already published with a different checksum (immutable releases)")
      case Some(existing) =>
        existing // idempotent re-publish of identical content
      case None =>
        val p = pkgPath(id, version)
        os.makeDir.all(p / os.up)
        os.write.over(p, bytes)
        val entry = Entry(id, version, digest, description)
        writeIndex(current :+ entry)
        entry

  /** Case-insensitive substring search over id + description; all matching entries (every version),
   *  ordered by id then ascending version. An empty query matches the whole catalog. */
  def search(query: String): List[Entry] =
    val q = query.toLowerCase
    index
      .filter(e => q.isEmpty || e.id.toLowerCase.contains(q) || e.description.toLowerCase.contains(q))
      .sortWith((a, b) => if a.id != b.id then a.id < b.id else compareVersions(a.version, b.version) < 0)

  /** Resolve `(id, version)` to an [[Entry]]. An empty version or `"latest"` selects the highest version
   *  (per [[RemoteRegistry.compareVersions]]); otherwise an exact match. `None` if absent. */
  def resolve(id: String, version: String = ""): Option[Entry] =
    val forId = index.filter(_.id == id)
    if forId.isEmpty then None
    else if version.isEmpty || version == "latest" then
      Some(forId.sortWith((a, b) => compareVersions(a.version, b.version) < 0).last)
    else forId.find(_.version == version)

  /** All published versions of `id`, oldest→newest. */
  def versions(id: String): List[String] =
    index.filter(_.id == id).map(_.version).sortWith((a, b) => compareVersions(a, b) < 0)

  /** Fetch the stored artifact bytes for a resolved `(id, version)`, verifying the SHA-256 against the
   *  index (a corrupted store is an error, not silently served). `None` if the entry is absent. */
  def fetch(id: String, version: String = ""): Option[Array[Byte]] =
    resolve(id, version).flatMap { e =>
      val p = pkgPath(e.id, e.version)
      if !os.exists(p) then None
      else
        val bytes = os.read.bytes(p)
        val got   = sha256Hex(bytes)
        if got != e.sha256 then
          throw new IllegalStateException(s"checksum mismatch for ${e.id}@${e.version}: index ${e.sha256} != store $got")
        Some(bytes)
    }

  /** Project the catalog into the client-facing `packages.yaml` format ([[LocalRegistry.Entry]]:
   *  id → url + version + description), ONE entry per id at its latest version, with `url` pointing at the
   *  stored artifact under `baseUrl`. This is exactly what the existing `RegistryClient` / `ssc search` /
   *  `ssc install` consume — so a `FileRegistry`-served directory works with the current client unchanged
   *  (the richer `index.json` with checksums/all-versions stays the publish-side record). */
  def exportPackagesYaml(baseUrl: String): String =
    val base = baseUrl.stripSuffix("/")
    val latest = index.groupBy(_.id).values.toList.map { es =>
      es.sortWith((a, b) => compareVersions(a.version, b.version) < 0).last
    }
    val entries = latest.sortBy(_.id).map { e =>
      LocalRegistry.Entry(
        id          = e.id,
        url         = s"$base/packages/${e.id}/${e.version}.sscpkg",
        version     = e.version,
        description = e.description)
    }
    LocalRegistry.toYaml(entries)

  /** Write the client-facing `packages.yaml` into the registry root (call after publish so the served
   *  directory exposes the index the client fetches). Returns the written path. */
  def writePackagesYaml(baseUrl: String): os.Path =
    os.makeDir.all(root)
    val p = root / "packages.yaml"
    os.write.over(p, exportPackagesYaml(baseUrl))
    p
