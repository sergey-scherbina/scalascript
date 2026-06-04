package scalascript.wire.security

/** mTLS configuration hook for wire transports.
 *
 *  Carries the paths and credentials needed to configure mutual TLS on the
 *  JVM transport layer.  Wire-transport implementations receive a
 *  `WireTlsConfig` and configure `SSLContext` accordingly.
 *
 *  Spec: specs/distributed-wire-protocol.md §Transport Security */
case class WireTlsConfig(
  /** Path to the JKS/PKCS12 keystore (server/client identity). */
  keystorePath:     String,
  keystorePassword: String,
  keystoreType:     String = "PKCS12",

  /** Path to the JKS/PKCS12 truststore (peer CA certificates). */
  truststorePath:     String,
  truststorePassword: String,
  truststoreType:     String = "PKCS12",

  /** Require and verify client certificates (mutual TLS). */
  requireClientAuth: Boolean = true,

  /** TLS protocol versions to enable. */
  protocols: List[String] = List("TLSv1.3", "TLSv1.2"),

  /** Cipher suites to enable (empty = JVM defaults). */
  cipherSuites: List[String] = Nil,
)

object WireTlsConfig:
  /** Parse from a `tls:` front-matter sub-block. */
  def fromMap(m: Map[String, Any]): Option[WireTlsConfig] =
    for
      ks   <- m.get("keystorePath").collect    { case s: String => s }
      kspw <- m.get("keystorePassword").collect { case s: String => s }
      ts   <- m.get("truststorePath").collect   { case s: String => s }
      tspw <- m.get("truststorePassword").collect { case s: String => s }
    yield
      WireTlsConfig(
        keystorePath       = ks,
        keystorePassword   = kspw,
        keystoreType       = m.get("keystoreType").collect { case s: String => s }.getOrElse("PKCS12"),
        truststorePath     = ts,
        truststorePassword = tspw,
        truststoreType     = m.get("truststoreType").collect { case s: String => s }.getOrElse("PKCS12"),
        requireClientAuth  = m.get("requireClientAuth").collect { case b: Boolean => b }.getOrElse(true),
        protocols          = m.get("protocols").collect {
          case xs: List[?] => xs.collect { case s: String => s }
        }.getOrElse(List("TLSv1.3", "TLSv1.2")),
        cipherSuites       = m.get("cipherSuites").collect {
          case xs: List[?] => xs.collect { case s: String => s }
        }.getOrElse(Nil),
      )
