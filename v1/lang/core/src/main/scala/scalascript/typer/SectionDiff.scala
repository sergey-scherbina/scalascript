package scalascript.typer

/** Section-level diff between two consecutive watch-cycle type-check runs.
 *
 *  Compares two lists of [[SectionSnapshot]]s (previous vs current) and
 *  classifies each section as added, modified, or removed by comparing their
 *  heading names and `sectionHash` values.  Used by `ssc watch` to:
 *
 *  - Skip the interpreter re-run entirely when nothing changed (false-positive
 *    OS watch event: editor touched mtime without altering content).
 *  - Log which sections actually changed so the developer can see at a glance
 *    which part of the file triggered the reload.
 */
object SectionDiff:

  /** Result of comparing previous and current section snapshots. */
  case class Diff(
    added:    List[String],   // heading names of newly-added sections
    modified: List[String],   // heading names of sections whose hash changed
    removed:  List[String]    // heading names of sections no longer present
  ):
    def isEmpty: Boolean = added.isEmpty && modified.isEmpty && removed.isEmpty

    /** Human-readable summary, e.g. `"§ Usage, +§ Examples, -§ Old"`. */
    def show: String =
      val parts =
        modified.map(h => s"§ $h") ++
        added.map(h => s"+§ $h") ++
        removed.map(h => s"-§ $h")
      parts.mkString(", ")

  /** Compute the diff between `prev` and `next` snapshot lists.
   *
   *  Section identity is the heading name (`typedSection.name`).  Sections
   *  with the same name are compared by `sectionHash`; a hash change counts
   *  as modified.  Order within each result list is alphabetical so that
   *  output is stable across runs. */
  def compute(prev: List[SectionSnapshot], next: List[SectionSnapshot]): Diff =
    val prevMap  = prev.map(s => s.typedSection.name -> s.sectionHash).toMap
    val nextMap  = next.map(s => s.typedSection.name -> s.sectionHash).toMap
    val prevKeys = prevMap.keySet
    val nextKeys = nextMap.keySet
    val added    = (nextKeys -- prevKeys).toList.sorted
    val removed  = (prevKeys -- nextKeys).toList.sorted
    val modified = nextMap.collect {
      case (name, hash) if prevMap.get(name).exists(_ != hash) => name
    }.toList.sorted
    Diff(added, modified, removed)
