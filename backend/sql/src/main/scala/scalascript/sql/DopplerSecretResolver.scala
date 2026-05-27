package scalascript.sql

import java.net.URI
import java.net.http.{HttpClient, HttpRequest}
import java.net.http.HttpResponse.BodyHandlers

/** Doppler secret resolver via the Doppler REST API.
 *
 *  Reference format:
 *  - `${doppler:SECRET_NAME}` — token-scoped (token encodes project/config)
 *  - `${doppler:PROJECT/CONFIG/SECRET}` — explicit project and config
 *
 *  Environment:
 *  - `DOPPLER_TOKEN` — required; service token or personal token
 *
 *  Uses JDK `HttpClient` — no extra dependencies beyond JDK 11+.
 */
class DopplerSecretResolver extends SecretResolver:
  val scheme = "doppler"

  protected def dopplerToken: String = sys.env.getOrElse("DOPPLER_TOKEN",
    throw RuntimeException(
      "DOPPLER_TOKEN is not set — export DOPPLER_TOKEN=<your-token> (doppler login or service token)"
    )
  )
  protected def dopplerApiBase: String = "https://api.doppler.com"

  def resolve(ref: String): String =
    val parts = ref.split('/')
    val (projectOpt, configOpt, secret) = parts.length match
      case 1 => (None, None, parts(0))
      case 3 => (Some(parts(0)), Some(parts(1)), parts(2))
      case n => throw RuntimeException(
        s"doppler ref must be SECRET or PROJECT/CONFIG/SECRET, got $n parts: '$ref'"
      )

    val queryParts = List(
      Some(s"secrets%5B%5D=${urlEncode(secret)}"),
      projectOpt.map(p => s"project=${urlEncode(p)}"),
      configOpt.map(c => s"config=${urlEncode(c)}")
    ).flatten
    val query = queryParts.mkString("&")
    val url   = s"$dopplerApiBase/v3/configs/config/secrets/download?$query"

    val client = HttpClient.newHttpClient()
    val resp   = client.send(
      HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", s"Bearer ${dopplerToken}")
        .header("Accept", "application/json")
        .GET().build(),
      BodyHandlers.ofString()
    )

    resp.statusCode() match
      case 401 => throw RuntimeException(
          "doppler: authentication failed — check DOPPLER_TOKEN is valid and not expired"
        )
      case 200 => ()
      case n   => throw RuntimeException(
          s"doppler: HTTP $n fetching secret '$secret': ${resp.body().take(200)}"
        )

    val json = ujson.read(resp.body())
    val raw  = json.obj.get(secret).getOrElse(
      throw RuntimeException(
        s"doppler: secret '$secret' not found in response — verify the name and token scope"
      )
    )
    raw match
      case ujson.Str(v)  => v
      case ujson.Obj(m)  => m.get("computed").map(_.str).getOrElse(
          throw RuntimeException(s"doppler: unexpected value shape for secret '$secret'")
        )
      case other         => other.toString

  private def urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")
