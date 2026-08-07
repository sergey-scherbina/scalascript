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

  /** Fronts REGISTERED at startup by an artifact the kernel cannot name.
    *
    * `v3/uniml` is a separate artifact — the kernel keeps zero dependencies (invariant I-1) and
    * must build and run with UniML absent — so the registration goes the other way: the outer
    * artifact installs its parser into the kernel, rather than the kernel importing it.
    *
    * A `var` in an object is global mutable state, which is worth a sentence of justification.
    * It is written EXACTLY ONCE, by the entry point, before any parsing; the alternative is
    * threading a parse function through `Loader`, `Main` and every command, and `closureWith`
    * shows what that costs — it already exists and every caller has to remember to use it. */
  private var registered: List[(String, String => Program)] = Nil

  def register(name: String, parse: String => Program): scala.Unit =
    if !registered.exists((n, _) => n == name) then registered = registered :+ (name, parse)

  /** The fronts that can actually run — v3's own, plus whatever registered.
    *
    * It is a `def` and it used to be a `val`. A `val` was right when the list was fixed and became
    * a silent lie the moment registration existed: `available` was captured before `main` ran, so
    * the second front could be running while the list said it did not exist. */
  def available: List[String] = v3 :: registered.map((n, _) => n)

  /** The front a command uses when none is named.
    *
    * UniML WINS WHEN IT IS PRESENT. That is the swap (`40-front-on-uniml.md` §7), and the number
    * that earned it is 48 of 48 fixtures and 101 of 101 corpus cases printing the same `Ast`.
    *
    * It depends on the WORKING TREE, which is the shape this repository has been bitten by — a
    * gate that cannot see which front answered proves nothing about either. That is why `ssc3
    * front` exists and why `front-report-gate.sh` asserts on it rather than on output: v3's own
    * front and UniML's agree on every fixture, so ANY fixture's output is identical either way and
    * could never distinguish them. */
  def default: String = if registered.exists((n, _) => n == uniml) then uniml else v3

  def parse(text: String, which: String): Program =
    if which == v3 then Parser.parse(Source.program(text))
    else
      registered.find((n, _) => n == which) match
        case Some((_, f)) => f(text)
        case None if which == uniml =>
          throw LoadError("the `uniml` front is not registered in this artifact — it lives in " +
            "v3/uniml and needs UniML's classpath; run v3/uniml-classpath.sh")
        case None =>
          throw LoadError("unknown front '" + which + "'; available: " + available.mkString(", "))
