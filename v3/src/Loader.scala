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
    lines.foreach { raw =>
      ln = ln + 1
      out = scanLine(raw, ln, out)
    }
    out.reverse

  /** ALL links on a line, not the first. The corpus writes
    * `[a](std/x.ssc) [b](std/y.ssc) [c](std/z.ssc)` on one line, and taking from the first `](` to
    * the end of the line glued three targets into one nonexistent path — with a diagnostic naming
    * the glued string, which is at least how it was found. */
  private def scanLine(raw: String, ln: Int, acc0: List[(String, Int)]): List[(String, Int)] =
    var acc = acc0
    var i = 0
    val l = raw
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
    * "cannot find x" without saying where it looked is a message that costs the reader the search. */
  def candidates(target: String, fromFile: String): List[String] =
    val dir =
      val i = fromFile.lastIndexOf('/')
      if i < 0 then "." else fromFile.substring(0, i)
    val raw =
      if target.startsWith("std/") then List(stdRoot + target, target, dir + "/" + target)
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
  def closure(rootPath: String): List[Unit3] =
    var seen: List[String] = Nil
    var out: List[Unit3] = Nil

    def visit(path: String): scala.Unit =
      val canon = new java.io.File(path).getPath
      if !seen.contains(canon) then
        seen = canon :: seen
        val text = read(path)
        importsOf(text).foreach { (t, ln) => visit(resolve(t, path, ln)) }
        // A parse failure inside an IMPORTED unit must name THAT unit. Without this the message
        // carried the ROOT file's path with the imported file's line number — pointing at a line
        // that has nothing to do with the error, in a file the reader did not write. Measured on
        // `std-index.ssc`: it reported `trait` at a line holding a `println`.
        val prog =
          try Parser.parse(Source.program(text))
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
    var top: List[Stmt] = Nil
    units.foreach { u =>
      defs = defs ++ u.program.defs
      classes = classes ++ u.program.classes
      objects = objects ++ u.program.objects
      val keep =
        if u.path == root.path then u.program.topLevel
        else u.program.topLevel.filter { s => s match
          case Stmt.Val(_, _, _, _) => true
          case _                    => false
        }
      top = top ++ keep
    }
    Program(defs, top, classes, objects)
