package scalascript.backend.spi

import java.net.InetAddress

/** Resolves cluster seed endpoints to actor WebSocket URLs.
 *
 *  Implementations are intentionally simple and side-effect-light.  External
 *  discovery systems such as Consul/Ogmios-style HTTP registries can provide
 *  their own implementation later without changing cluster users.
 */
trait SeedResolver:
  def resolve(): List[String]

object SeedResolver:
  def staticList(urls: List[String]): SeedResolver =
    StaticSeedResolver(urls)

  def dnsSrv(serviceName: String, port: Int = 9100, scheme: String = "ws"): SeedResolver =
    DnsSeedResolver(serviceName, port, scheme)

  def k8sHeadlessService(
      serviceName: String,
      namespace:   String = "default",
      port:        Int = 9100,
      scheme:      String = "ws"
  ): SeedResolver =
    DnsSeedResolver(s"$serviceName.$namespace.svc", port, scheme)

  def unsupported(kind: String, reason: String): SeedResolver =
    UnsupportedSeedResolver(kind, reason)

final case class StaticSeedResolver(urls: List[String]) extends SeedResolver:
  def resolve(): List[String] = urls

final case class DnsSeedResolver(host: String, port: Int, scheme: String) extends SeedResolver:
  def resolve(): List[String] =
    InetAddress.getAllByName(host).toList
      .map(addr => s"$scheme://${addr.getHostAddress}:$port/_ssc-actors")

final case class UnsupportedSeedResolver(kind: String, reason: String) extends SeedResolver:
  def resolve(): List[String] =
    throw new IllegalArgumentException(s"unsupported seed resolver '$kind': $reason")
