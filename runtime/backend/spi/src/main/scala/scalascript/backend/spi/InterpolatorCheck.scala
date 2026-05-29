package scalascript.backend.spi

/** Compile-time validation hook for string interpolators.
 *
 *  A check sees only the static string parts of `name"...${expr}..."`.
 *  Dynamic arguments are intentionally opaque at this phase.
 */
trait InterpolatorCheck:
  def interpolatorName: String
  def check(parts: List[String]): List[Diagnostic]
