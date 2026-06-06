package scalascript.backend.spi

/** Current SPI / IR version.  Per specs/backend-spi.md §13: one version
 *  number for both, plain semver, no compatibility shims.  Plugins
 *  declare what they were built against; core rejects mismatches. */
object SpiVersion:
  val Current: String = "0.2.0"

/** Range of SPI versions a plugin is compatible with.  Stage 4
 *  (capability validation) implements semver intersection logic. */
case class SpiVersionRange(min: String, max: String):
  def includes(v: String): Boolean =
    // Stage 4 will replace with real semver comparison.
    v == min || v == max
