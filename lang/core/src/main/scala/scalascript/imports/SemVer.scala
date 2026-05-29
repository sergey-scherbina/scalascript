package scalascript.imports

/** Minimal semver comparator used for dependency conflict resolution. */
object SemVer:

  /** Compare two version strings segment-by-segment.
   *  Returns negative/zero/positive like `Ordering.compare`. */
  def compare(a: String, b: String): Int =
    val segsA = a.split('.').toList
    val segsB = b.split('.').toList
    val maxLen = math.max(segsA.length, segsB.length)
    val paddedA = segsA.padTo(maxLen, "0")
    val paddedB = segsB.padTo(maxLen, "0")
    paddedA.zip(paddedB).foldLeft(0) { (acc, pair) =>
      if acc != 0 then acc
      else
        val (sa, sb) = pair
        (sa.toIntOption, sb.toIntOption) match
          case (Some(ia), Some(ib)) => ia.compare(ib)
          case _                    => sa.compareTo(sb)
    }

  def max(a: String, b: String): String = if compare(a, b) >= 0 then a else b
