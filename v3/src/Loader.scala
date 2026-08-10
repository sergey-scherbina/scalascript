package ssc3

// Cross-file `.ssc`: markdown-link imports.
//
//     [Node, Cluster](std/mapreduce/cluster.ssc)
//
// Measured on the corpus: 199 of 383 cases use this form and 3 use a Scala-style `import`, so this
// IS how a multi-file ScalaScript program is written. It was also the dominant remaining blocker —
// 73 of 123 unknown-name refusals were three names defined in other files.
//
// The resolution rules are v2's, read from `v2/bin/ssc1-run.ssc0` rather than invented: `std/…`
// maps to the std root (`SSC_STD`, else the in-tree `v1/runtime/`), and everything else is relative
// to the IMPORTING file.
//
// A module is imported for its DECLARATIONS. Its `def`s, `case class`/`enum`/`object` declarations
// and top-level `val`/`var` come across; its other top-level statements do NOT, because running a
// library's `println` on import would make the output depend on what you imported.

final case class LoadError(message: String) extends RuntimeException(message)

final case class Unit3(path: String, program: Program)

object Loader:

  private def stdRoot: String =
    val e = System.getenv("SSC_STD")
    if e == null || e.isEmpty then "v1/runtime/" else if e.endsWith("/") then e else e + "/"

  /** The names a file imports, as (link text is ignored, path is what matters).
    *
    * Only a target ending in `.ssc` counts. A prose link like `./logo.png "Brand"` must not be
    * treated as an import — the same exclusion v2 makes, for the same reason. */
  def importsOf(text: String): List[(String, Int)] =
    val lines = text.split("\n", -1).toList
    var out: List[(String, Int)] = Nil
    var ln = 0
    // FENCE STATE, for the second spelling only. A markdown link is prose and is scanned wherever
    // it appears — that is unchanged. A Scala-style `import` is CODE, and counting one outside a
    // fence would import a documentation example: `std/actors.ssc` and `std/nodes.ssc` each show
    // `import actors.ChildSpec` inside a ```text block, and `std/geo.ssc` shows `import std.geo.*`
    // inside a ```scalascript one. Only the third is code. The first two are invisible to this
    // scanner because of `inCode`, and they are the reason it exists — both happen to name their
    // own file, so a wrong reading would have been a no-op and would have survived review.
    var inCode = false
    lines.foreach { raw =>
      ln = ln + 1
      if inCode then
        if Source.isFenceClose(raw) then inCode = false
        else
          Source.scalaImportPath(raw) match
            case Some(p) => out = (scalaImportTarget(p), ln) :: out
            // AN UNSUPPORTED SPELLING IS REFUSED HERE, IN THE LOADER, AND NOT IN A PARSER.
            //
            // There are two fronts, and putting the refusal in `Parser.scala` puts it in one of
            // them: the uniml front drops an `import` line it does not understand, so `import
            // credential` ran and printed on the default front while v3's own front refused it —
            // measured on this exact fixture before this branch existed. That is invariant I-3, a
            // program that behaves differently on the two lanes, and the module graph is the one
            // place both fronts already go through.
            case None if startsImport(raw) =>
              throw ParseFail(Pos(ln, 1),
                "an `import` line must be a dotted path and nothing else — `import std.geo.*` or " +
                "`import actors.Overflow`, which name a module in the standard library. Renaming " +
                "(`as`), selector lists (`{a, b}`) and a single bare name have no meaning here, " +
                "because an import brings the WHOLE module either way; for a module beside this " +
                "file, write a markdown link — `[name](./other.ssc)`")
            case None => ()
      else if Source.isCodeFenceOpen(raw) then inCode = true
      out = scanLine(raw, ln, out)
    }
    out.reverse

  /** The line opens with the WORD `import`, whatever follows it.
    *
    * The whitespace test is the whole of it: `importCount = 3` starts with those six letters and is
    * an ordinary assignment, and refusing it would be this rule breaking programs to enforce itself.
    */
  private def startsImport(l: String): Boolean =
    val t = l.trim
    t.startsWith("import") && t.length > 6 && (t.charAt(6) == ' ' || t.charAt(6) == '\t')

  /** The link target a Scala-style import means: `import actors.Overflow` is `std/actors.ssc`.
    *
    * THE LAST SEGMENT IS A MEMBER, not a directory — `Overflow` is an enum inside
    * `v1/runtime/std/actors.ssc`, so the module is the path WITHOUT it. `.*` is the same shape with
    * the member left unnamed, so it drops identically.
    *
    * `std/` IS PREPENDED when the path does not already start with it, and that is the whole of the
    * mapping's opinion: a dotted name resolves in the standard library, while anything relative to
    * the importing file keeps saying so with a link, where a `../` can be written and a dotted path
    * cannot. Both real spellings land right — `actors.Overflow` on `std/actors.ssc`, `std.geo.*` on
    * `std/geo.ssc` — and `candidates` then does the searching, so nothing here knows about
    * `SSC_STD` or the layout.
    *
    * WHAT IT DOES NOT DO: the member name is not checked, and importing one name brings the whole
    * module, because Tier 0 has no namespaces to hide the rest behind. That is a real difference
    * from Scala and it is written here rather than discovered: `import actors.Overflow` gives you
    * every definition in `std/actors.ssc`, exactly as the equivalent link would. */
  def scalaImportTarget(dotted: String): String =
    val segs = dotted.split("\\.", -1).toList
    val mod  = segs.dropRight(1)
    val full = if mod.headOption.contains("std") then mod else "std" :: mod
    full.mkString("/") + ".ssc"

  /** ALL links on a line, not the first. The corpus writes
    * `[a](std/x.ssc) [b](std/y.ssc) [c](std/z.ssc)` on one line, and taking from the first `](` to
    * the end of the line glued three targets into one nonexistent path — with a diagnostic naming
    * the glued string, which is at least how it was found. */
  private def scanLine(raw: String, ln: Int, acc0: List[(String, Int)]): List[(String, Int)] =
    var acc = acc0
    var i = 0
    val l = raw
    // AN IMPORT LINE BEGINS WITH ITS LINK. Anything else on a line — a doc comment mentioning a
    // module, a `.ssc` path inside a STRING LITERAL — is not an import, and reading it as one
    // produced `cannot find the import 'one.ssc'` for four corpus cases whose only sin was a
    // markdown sample in a string, plus `v3/src/Loader.scala:44`, where this very comment's own
    // example was read as an import of a file that does not exist.
    //
    // MEASURED before changing the rule: of 382 import lines across the corpus and the standard
    // library, every one starts with `[`. The lines that do not are prose and string literals —
    // exactly the false positives. Several links on one line still work, because the line still
    // begins with the first of them.
    val lead = l.dropWhile(c => c == ' ' || c == '\t')
    if !lead.startsWith("[") then return acc
    while i < l.length do
      if l.charAt(i) == '[' then
        val close = l.indexOf("](", i)
        if close < 0 then i = l.length
        else
          val end = l.indexOf(')', close + 2)
          if end < 0 then i = l.length
          else
            val target = l.substring(close + 2, end).trim
            if target.endsWith(".ssc") then acc = (target, ln) :: acc
            i = end + 1
      else i = i + 1
    acc

  /** Candidate paths, in the order they are tried. Reported IN FULL when none exists, because
    * "cannot find x" without saying where it looked is a message that costs the reader the search.
    *
    * THE LAST CANDIDATE IS A MIGRATION, not a guess. `std-to-repo-root` (2026-08-09) moved the dev
    * tree's modules to the repo root and PROMOTED whole directories out of `std/` to the top level
    * — `scljet/` is the big one. The import spelling did not move with them: the corpus writes
    * `std/scljet/index.ssc` in 200+ places and the reference still resolves it, because the
    * reference reads the INSTALLED staged tree, where `std/scljet/` physically survives. So the
    * logical name keeps its `std/` prefix while the dev tree's physical path has dropped it, and
    * the resolver is the one place that knows both.
    *
    * Stripping the prefix is tried LAST so nothing that resolves today changes — `std/actors.ssc`
    * still lands on `<root>/std/actors.ssc` via the bare-target candidate and never reaches this
    * one. Same ordering discipline, for the same migration, as `AutoResolve.scala:109`.
    *
    * Measured before it was written: over the whole conformance corpus this was 116 of 169
    * refusals — 69% of everything the default front turned away, and every one of them the SAME
    * unresolved target. `stdRoot`'s `v1/runtime/` default now names a directory holding ZERO `.ssc`
    * modules, so candidate one is dead in a dev tree; it stays because an INSTALLED tree still
    * stages `std/` under a root, and `SSC_STD` still points there. */
  def candidates(target: String, fromFile: String): List[String] =
    val dir =
      val i = fromFile.lastIndexOf('/')
      if i < 0 then "." else fromFile.substring(0, i)
    val raw =
      if target.startsWith("std/") then
        List(stdRoot + target, target, dir + "/" + target, target.substring("std/".length))
      else List(dir + "/" + target, target, stdRoot + target)
    raw.map(normalise).distinct

  /** Collapse `.` and `..` segments. Without it `v1/runtime/std/` + `./std/index.ssc` is a path
    * with a `.` in the middle, which no filesystem lookup will match and which reads as a typo in
    * the diagnostic rather than as the missing normalisation it is. */
  def normalise(p: String): String =
    var out: List[String] = Nil
    p.split("/", -1).foreach { seg =>
      if seg == "." || seg.isEmpty then ()
      else if seg == ".." then out = if out.isEmpty then out else out.tail
      else out = seg :: out
    }
    val joined = out.reverse.mkString("/")
    if p.startsWith("/") then "/" + joined else joined

  private def exists(p: String): Boolean =
    val f = new java.io.File(p)
    f.exists && f.isFile

  /** The `line` is carried so the diagnostic can point AT THE IMPORT, like every other v3
    * diagnostic. "cannot find x" with no position makes the reader search a file they did not
    * write; the candidate list says where it looked, and the position says what to edit. */
  def resolve(target: String, fromFile: String, line: Int): String =
    val tried = candidates(target, fromFile)
    tried.find(exists) match
      case Some(p) => p
      case None =>
        throw LoadError(fromFile + ":" + line + ":1: cannot find the import '" + target +
          "' — looked in: " + tried.mkString(", "))

  private def read(p: String): String =
    new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p)), "UTF-8")

  /** Depth-first, imports before importers, each path loaded ONCE.
    *
    * The order matters and the deduplication matters: a diamond (two modules importing a third)
    * is ordinary, and loading the shared module twice would declare every one of its names twice. */
  // `Front.default`, not `Front.v3`: with the uniml front registered this is what makes `build`,
  // `ir`, `exec` and `emit-v2` run on it rather than only `ast`, which took a front argument and so
  // was the ONLY command the differential could ever have reached.
  def closure(rootPath: String): List[Unit3] = closure(rootPath, Front.default)

  def closure(rootPath: String, front: String): List[Unit3] =
    closureWith(rootPath, t => Front.parse(t, front))

  /** The parse step is a PARAMETER, not a name looked up in a table.
    *
    * A second front lives outside the kernel — `v3/uniml` is a separate artifact so that `v3/src`
    * keeps its zero dependencies — so the kernel cannot name it. Passing the function lets that
    * front reuse the module graph instead of reimplementing it, which it briefly did NOT: the first
    * version of the uniml front parsed one file and every cross-file import silently vanished. */
  def closureWith(rootPath: String, parseWith: String => Program): List[Unit3] =
    var seen: List[String] = Nil
    var out: List[Unit3] = Nil

    def visit(path: String): scala.Unit =
      val canon = new java.io.File(path).getPath
      if !seen.contains(canon) then
        seen = canon :: seen
        val text = read(path)
        // INSIDE the same guard as the parse, because `importsOf` now refuses an unsupported
        // `import` spelling and that refusal carries a line number with no file attached to it.
        val imports =
          try importsOf(text)
          catch case e: ParseFail => throw LoadError(path + ":" + e.getMessage)
        imports.foreach { (t, ln) => visit(resolve(t, path, ln)) }
        // A parse failure inside an IMPORTED unit must name THAT unit. Without this the message
        // carried the ROOT file's path with the imported file's line number — pointing at a line
        // that has nothing to do with the error, in a file the reader did not write. Measured on
        // `std-index.ssc`: it reported `trait` at a line holding a `println`.
        val prog =
          try parseWith(text)
          catch
            case e: ParseFail => throw LoadError(path + ":" + e.getMessage)
            case e: LexError  => throw LoadError(path + ":" + e.getMessage)
        out = out :+ Unit3(canon, prog)

    visit(rootPath)
    out

  /** One program from the closure: every unit's declarations, but only the ROOT's statements.
    *
    * An imported unit's `val`/`var` survive as declarations — they are what its `def`s read — while
    * its bare expressions are dropped. Keeping them would mean importing a module printed its
    * examples, which is the behaviour that makes people stop factoring code into modules. */
  def merge(units: List[Unit3]): Program =
    if units.isEmpty then throw LoadError("no units to merge")
    val root = units.last
    var defs: List[Def] = Nil
    var classes: List[ClassDef] = Nil
    var objects: List[ObjectDef] = Nil
    var traits: List[TraitDef] = Nil
    var effects: List[TraitDef] = Nil
    var top: List[Stmt] = Nil
    units.foreach { u =>
      defs = defs ++ u.program.defs
      classes = classes ++ u.program.classes
      objects = objects ++ u.program.objects
      traits = traits ++ u.program.traits
      effects = effects ++ u.program.effects
      val keep =
        if u.path == root.path then u.program.topLevel
        else u.program.topLevel.filter { s => s match
          case Stmt.Val(_, _, _, _) => true
          case _                    => false
        }
      top = top ++ keep
    }
    // EVERY field, listed explicitly. `effects` was added to `Program` with a default and this
    // rebuild silently dropped it — a merged program had no effect declarations at all, and the
    // symptom was `unknown name 'Bump'` in the LOWERING, three layers from the cause. A defaulted
    // field is invisible exactly where a case class is reconstructed by hand.
    Program(defs, top, classes, objects, traits, effects)
