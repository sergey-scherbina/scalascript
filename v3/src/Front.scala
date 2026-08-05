package ssc3

// The ONE door from source text to a `Program`.
//
// It has a single implementation today and exists anyway, because the UniML swap
// (`v3/specs/40-front-on-uniml.md`) is only safe if a second front is a PARAMETER rather than an
// edit: the two must be runnable side by side on the same file, or the differential that decides
// the swap cannot be written.
//
// Choosing by name rather than by a boolean so that a third front — a future one, or a deliberately
// broken one used to watch the gate fail — costs nothing to add and names itself in a diagnostic.
object Front:

  val v3: String = "v3"
  val uniml: String = "uniml"

  /** The fronts that can actually run. `uniml` is deliberately ABSENT rather than listed and
    * failing later: a name that appears in the list and then refuses is worse than one that never
    * appeared, because the first reads as a regression and the second as unfinished work. */
  val available: List[String] = List(v3)

  def parse(text: String, which: String): Program =
    if which == v3 then Parser.parse(Source.program(text))
    else if which == uniml then
      throw LoadError("the `uniml` front is not wired yet — see v3/specs/40-front-on-uniml.md §5 " +
        "for what UniML still owes v3, and §6 for what lands on this side first")
    else throw LoadError("unknown front '" + which + "'; available: " + available.mkString(", "))
