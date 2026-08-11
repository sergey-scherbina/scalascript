package ssc3

// THE LOWERED MODULE FOR A PATH — and the one place that decides whether the prelude is loaded.
//
// The seven call sites that used to write `Lower.programOf(Loader.merge(Loader.closure(path)), …)`
// by hand now come here. Unifying them is not tidiness: the decision below has to be taken once, or
// the eighth site takes it differently and the two lanes disagree about what a program means.
//
// ── THE PRELUDE IS LOADED LAZILY, AND THE MEASUREMENT IS WHY ──────────────────────────────────────
//
// P-5 of `v3/PRELUDE-CORRECTNESS.md`, measured 2026-08-11 against a floor arm because this host runs
// other agents' JVMs: the prelude costs 75–113 ms per invocation, 18.8x the floor, and it is 22–26%
// of a whole run. A one-line prelude costs +6 ms — AT the floor — so the mechanism is free and the
// LIBRARY is the entire cost. And of 398 conformance cases, **28 mention a name the prelude declares
// and 370 mention none**. Ninety-three percent of invocations parsed and lowered a standard library
// they could not reach.
//
// A CACHE WAS THE WRONG INSTINCT and the measurement is what says so. Every invocation is a fresh
// JVM, so it would have to be on disk, keyed on the prelude's CONTENT rather than its path — in a
// repository already burned twice by digest-keyed caches serving the wrong state — and it would
// have optimised work that 93% of programs should not do at all.
//
// So: lower WITHOUT the prelude first. If that works, the program never needed it and nothing was
// read. If it fails, lower again with the prelude in scope. No key, nothing to invalidate, and the
// fast path is the common one.
//
// ── WHY THE RETRY IS ON *ANY* FAILURE, NOT ON "unknown name" ──────────────────────────────────────
//
// The obvious version inspects the first failure and retries only when it looks like a missing name.
// That means matching on a DIAGNOSTIC'S TEXT, which makes every message in `Lower` load-bearing:
// reword `unknown name '…'` and programs silently stop finding the standard library. Retrying on any
// `LowerFail` costs one extra lowering on the failure path only — where the compiler is about to
// print an error and exit anyway — and costs the 370 succeeding cases nothing.
//
// ── WHICH ERROR THE USER SEES ─────────────────────────────────────────────────────────────────────
//
// The one from the attempt WITH the prelude, always. That is exactly the message this compiler
// printed before this change, because the prelude was always loaded — so no diagnostic moves, and
// the case that worried me (a program broken for unrelated reasons being lowered twice and reporting
// the second message) turns out to be the case where the second message IS today's. Reporting the
// first would have been the regression.
//
// ── WHAT THIS CHANGES ON PURPOSE ──────────────────────────────────────────────────────────────────
//
// A module that declares a name the prelude also declares now WINS. Before, the prelude was loaded
// first and therefore owned the name under `Loader.disambiguate`'s rule, so the module's copy was
// renamed; now a program that lowers cleanly without the prelude uses the module's. Decided by the
// owner, not fallen into: a module you imported is nearer than an ambient library.
object Driver:

  /** The lowered module for `path`, whose text is `src`.
    *
    * `src` is passed in rather than re-read because the caller already has it — `Source.blockEnds`
    * needs the same text, and reading a file twice is how the two copies come to differ. */
  def moduleOf(path: String, src: String): Module =
    val ends = Source.blockEnds(src)
    try Lower.programOf(Loader.merge(Loader.closureBare(path)), ends)
    catch
      // `LoadError` is NOT caught: a missing import or an unreadable file fails the same way with or
      // without the prelude, and retrying would read every module in the closure a second time to
      // arrive at the identical message.
      case _: LowerFail =>
        val units = Loader.closure(path)
        try Lower.programOf(Loader.merge(units), ends)
        catch case e: LowerFail => throw e.copy(message = e.message + preludeNote(e.message, units))

  /** The names the prelude declares, in every spelling a diagnostic can quote.
    *
    * Read off the UNITS rather than the merged `Program.origin`, which records top-level `def`s
    * only: `Dataset.fromList` is an object member and `Dataset.map` a class method, and those are
    * exactly the names a user gets wrong. Both are flattened to `Owner.member` by the lowering, so
    * that is the spelling to match. */
  private def preludeNames(units: List[Unit3]): Set[String] =
    units.filter(u => Loader.isPrelude(u.path)).flatMap { u =>
      val p = u.program
      p.defs.map(_.name) ++ p.classes.map(_.name) ++ p.traits.map(_.name) ++
      p.objects.map(_.name) ++
      p.objects.flatMap(o => o.defs.map(d => o.name + "." + d.name)) ++
      p.classes.flatMap(c => c.methods.map(m => c.name + "." + m.name))
    }.toSet

  /** An ADVISORY clause when the failure names something the prelude declares.
    *
    * `call to 'Dataset.fromList' passes 3 argument(s), it takes 1` is accurate and leaves the reader
    * hunting: they never imported `Dataset`, it is in no file they wrote, and nothing on the line
    * says where it came from. The clause answers that, and names the file so the signature can be
    * read.
    *
    * YES, THIS MATCHES ON THE MESSAGE TEXT, and that is defensible HERE for the reason it was
    * refused for the retry above: nothing depends on it. Every quoted name that does not resolve is
    * simply not annotated, and a reworded diagnostic loses a clause rather than losing the standard
    * library. Control flow may not read a message; a decoration may. */
  private def preludeNote(message: String, units: List[Unit3]): String =
    val names = preludeNames(units)
    if names.isEmpty then ""
    else
      var hit: Option[String] = None
      var i = message.indexOf('\'')
      while i >= 0 && hit.isEmpty do
        val j = message.indexOf('\'', i + 1)
        if j < 0 then i = -1
        else
          val q = message.substring(i + 1, j)
          if names.contains(q) then hit = Some(q)
          i = message.indexOf('\'', j + 1)
      hit match
        case Some(n) =>
          " — '" + n + "' comes from the standard prelude (" +
            units.filter(u => Loader.isPrelude(u.path)).map(_.path).mkString(", ") +
            "), which is in scope for every program"
        case None => ""

  /** Render a `LowerFail` the way every command renders it, and SAY WHEN IT CAME FROM THE PRELUDE.
    *
    * `origin` already carries the file a declaration was written in (P-2), so an error inside the
    * standard library has always named `v3/prelude/index.ssc` at its own line. That is correct and
    * it is not clear: the reader is looking at a path they never wrote, in a file they did not
    * import, with no hint of why it is in their program at all. The clause is the difference between
    * "what is this file" and "this is the standard prelude, which is in scope everywhere".
    *
    * `fallback` is the path the user typed, used when the failure carries no origin — a position in
    * the user's own file needs no redirection. */
  def render(e: LowerFail, fallback: String): String =
    val where = e.origin.getOrElse(fallback)
    val note = if Loader.isPrelude(where) then " — this is the standard prelude, in scope for every program" else ""
    "ssc3: " + where + ":" + e.getMessage + note
