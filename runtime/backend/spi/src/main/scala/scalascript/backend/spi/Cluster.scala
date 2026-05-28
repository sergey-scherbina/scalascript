package scalascript.backend.spi

/** Snapshot of the local process' cluster capability.
 *
 *  This is a capability value, not a cluster manager.  It describes what the
 *  current backend can see and gives higher-level APIs a common typed handle
 *  for local, remote, and distributed placement decisions.
 */
final case class Cluster(
    localNodeId:  String,
    peers:        List[String],
    authToken:    Option[String],
    seedResolver: SeedResolver,
    codeIdentity: CodeIdentity
):
  def resolveSeeds(): List[String] = seedResolver.resolve()
