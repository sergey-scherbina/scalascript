package scalascript.imports

import scalascript.backend.spi.{DepResolver, DepSpec}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Path

/** Resolves `github:owner/repo@tag[#asset]` from GitHub Releases. */
class GithubReleaseResolver extends DepResolver:
  override val scheme: String = "github"

  override def resolve(spec: DepSpec): Path =
    val parsed = GithubReleaseResolver.parse(spec.raw)
    val coord = s"${parsed.owner}/${parsed.repo}/${parsed.tag}"
    val assetHint = parsed.assetPath.map(_.split('/').last).getOrElse(s"${parsed.repo}.sscpkg")
    val key = DepCache.CacheKey(scheme, coord, spec.sha256)
    val cacheRoot = os.Path(spec.cacheRoot.toString, os.pwd)
    DepCache.get(key, assetHint, cacheRoot).map(_.toNIO).getOrElse {
      val release = fetchRelease(parsed.owner, parsed.repo, parsed.tag)
      val asset = selectAsset(release, parsed.assetPath)
      val cacheName = asset.name
      DepCache.get(key, cacheName, cacheRoot).map(_.toNIO).getOrElse {
        val bytes = download(asset.downloadUrl)
        DepCache.put(key, cacheName, bytes, cacheRoot).toNIO
      }
    }

  private def fetchRelease(owner: String, repo: String, tag: String): ujson.Value =
    val base = sys.props.getOrElse("ssc.github.api.base", "https://api.github.com")
    val url = s"${base.stripSuffix("/")}/repos/$owner/$repo/releases/tags/$tag"
    val req = request(url).GET().build()
    val resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
    if resp.statusCode() != 200 then
      throw new RuntimeException(s"github release $owner/$repo@$tag: HTTP ${resp.statusCode()} from $url")
    ujson.read(resp.body())

  private def selectAsset(release: ujson.Value, assetPath: Option[String]): GithubReleaseResolver.Asset =
    val wanted = assetPath.map(_.split('/').last)
    val assets = release("assets").arr.toVector.map { item =>
      GithubReleaseResolver.Asset(
        name = item("name").str,
        downloadUrl = item("browser_download_url").str,
      )
    }
    wanted match
      case Some(name) =>
        assets.find(_.name == name).getOrElse {
          throw new RuntimeException(s"github release asset '$name' not found")
        }
      case None =>
        assets.find(_.name.endsWith(".sscpkg")).getOrElse {
          throw new RuntimeException("github release has no .sscpkg asset")
        }

  private def download(url: String): Array[Byte] =
    if sys.env.get("SSC_NO_NETWORK").contains("1") then
      throw new RuntimeException(s"SSC_NO_NETWORK=1 blocks github asset download: $url")
    val resp = HttpClient.newHttpClient().send(request(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
    if resp.statusCode() != 200 then
      throw new RuntimeException(s"github asset download failed: HTTP ${resp.statusCode()} from $url")
    resp.body()

  private def request(url: String): HttpRequest.Builder =
    val b = HttpRequest.newBuilder(URI.create(url))
      .header("Accept", "application/vnd.github+json")
    sys.env.get("GITHUB_TOKEN").foreach(token => b.header("Authorization", s"Bearer $token"))
    b

object GithubReleaseResolver:
  final case class Parsed(owner: String, repo: String, tag: String, assetPath: Option[String])
  final case class Asset(name: String, downloadUrl: String)

  def parse(raw: String): Parsed =
    val body = raw.stripPrefix("github:")
    val hashIdx = body.indexOf('#')
    val main = if hashIdx >= 0 then body.substring(0, hashIdx) else body
    val asset = Option.when(hashIdx >= 0)(body.substring(hashIdx + 1)).filter(_.nonEmpty)
    val atIdx = main.lastIndexOf('@')
    if atIdx <= 0 then
      throw new RuntimeException(s"Invalid github: URI '$raw' — expected github:owner/repo@tag[#asset]")
    val repoPart = main.substring(0, atIdx)
    val tag = main.substring(atIdx + 1)
    val slashIdx = repoPart.indexOf('/')
    if slashIdx <= 0 || slashIdx == repoPart.length - 1 || tag.isEmpty then
      throw new RuntimeException(s"Invalid github: URI '$raw' — expected github:owner/repo@tag[#asset]")
    Parsed(repoPart.substring(0, slashIdx), repoPart.substring(slashIdx + 1), tag, asset)
