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
                "an `import` line must be a dotted path, optionally ending in `.*` or a selector " +
                "list — `import std.geo.*`, `import actors.Overflow`, `import std.geo.{Point, " +
                "Region}` — which name a module in the standard library. The names in a selector " +
                "list are not read: an import brings the WHOLE module either way, so the list is " +
                "accepted and ignored. Renaming the MODULE (`import a.b as c`) and a single bare " +
                "name have no meaning here; for a module beside this file, write a markdown link " +
                "— `[name](./other.ssc)`")
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
  /** THE PRELUDE — a module loaded before the user's program, so its declarations are ambient.
    *
    * WHY IT EXISTS. `v3/BACKLOG.md`'s DATASET decision offered three ways to give v3 host surface
    * and the owner took the third: write the library IN ScalaScript, over lists, so both lanes get
    * it with no host surface at all. That is the option the project's own rule points at — new
    * intrinsics go to a plugin, never the core — and the backlog recorded it as blocked on exactly
    * one missing mechanism: the corpus calls `Dataset.of` with NO import, v3's module system is
    * markdown links, and there was no way for a name to be in scope without one.
    *
    * WHY IT IS NOT IN THE PRINTED AST, and this is the part that matters. `ssc3 ast` renders
    * `merge(closure(path, front))` — the TWO-argument overload — and every fixture in
    * `.expected` fixture under `v3/tests/front/` is a byte-comparison against that render. A prelude visible there
    * would rewrite all of them and put its own declarations into every future diff, so the front
    * differential would spend most of its comparison on a module nobody is editing.
    *
    * The split is principled rather than convenient: a prelude changes what NAMES RESOLVE TO in the
    * lowering, and changes nothing about how the user's text parses. `ssc3 ast` shows what the user
    * wrote. Every execution path — `exec`, `run`, `run --bridge`, `ir`, `Specialize` — goes through
    * the ONE-argument overload, and that is the one that carries the prelude.
    *
    * ABSENT BY DEFAULT IN A TREE THAT HAS NO PRELUDE FILE: `preludeRoot` returns `None` when the
    * path does not exist, so a checkout without `v3/prelude/` behaves exactly as before this
    * change. `SSC3_PRELUDE=` (empty) turns it off; `SSC3_PRELUDE=<path>` points it elsewhere. A
    * mechanism that cannot be turned off cannot be measured — the gate needs both states to show
    * what the prelude actually changed. */
  private def preludeRoot: Option[String] =
    val e = System.getenv("SSC3_PRELUDE")
    val p = if e == null then "v3/prelude/index.ssc" else e
    if p.isEmpty then None
    else
      val n = normalise(p)
      if exists(n) then Some(n) else None

  def closure(rootPath: String): List[Unit3] =
    closureWith(rootPath, t => Front.parse(t, Front.default), preludeRoot)

  /** The same closure with NO prelude — the first attempt `Driver.moduleOf` makes.
    *
    * A separate name rather than a `Boolean` on `closure`, because `closure(path, front)` already
    * exists and a second two-argument overload taking a flag is the shape where a caller passes the
    * wrong one and nothing complains. */
  def closureBare(rootPath: String): List[Unit3] =
    closureWith(rootPath, t => Front.parse(t, Front.default), None)

  /** Is this the prelude the current invocation would load? Asked by `Driver.render` so a
    * diagnostic can SAY it is the standard prelude rather than print a path the reader never wrote.
    *
    * It compares against `preludeRoot`, not against a hard-coded `v3/prelude/index.ssc`: the path is
    * overridable (`SSC3_PRELUDE`), the gates rely on that, and a check that knew only the default
    * would fall silent for exactly the trees that moved it. */
  def isPrelude(path: String): Boolean =
    preludeRoot.contains(normalise(path))

  def closure(rootPath: String, front: String): List[Unit3] =
    closureWith(rootPath, t => Front.parse(t, front), None)

  /** The parse step is a PARAMETER, not a name looked up in a table.
    *
    * A second front lives outside the kernel — `v3/uniml` is a separate artifact so that `v3/src`
    * keeps its zero dependencies — so the kernel cannot name it. Passing the function lets that
    * front reuse the module graph instead of reimplementing it, which it briefly did NOT: the first
    * version of the uniml front parsed one file and every cross-file import silently vanished. */
  def closureWith(rootPath: String, parseWith: String => Program): List[Unit3] =
    closureWith(rootPath, parseWith, None)

  def closureWith(rootPath: String, parseWith: String => Program, prelude: Option[String]): List[Unit3] =
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

    // THE ROOT IS VISITED FIRST AND THE PRELUDE IS PREPENDED, which reads backwards until you see
    // what it buys.
    //
    // The ORDER of the result is fixed: `merge` reads `units.last` as the root — the only unit
    // whose bare statements survive — so the user's file must come last, or a prelude would run the
    // program's statements and drop its own. That is why the prelude is PREPENDED rather than
    // appended.
    //
    // But it is visited SECOND, because whether to load it at all depends on the root. A file that
    // declares nothing and executes nothing is refused with `empty program`, and
    // `v3/tests/front/trait-refused.ssc` — a trait with one abstract `def` and nothing else — is a
    // fixture for exactly that. A prelude loaded unconditionally makes EVERY program non-empty, so
    // that refusal disappeared and the front gate went red saying "the front emits for anything".
    // It was right.
    //
    // The rule: a prelude exists to put names in scope FOR CODE, and a unit with no declarations
    // and no statements has no code. It is the SAME question `Lower` asks when it refuses an empty
    // program, and it used to be written out separately in each file — two predicates that had to
    // agree, kept honest only by a gate. `Program.hasCode` is now the one predicate and both read
    // it.
    //
    // `seen` carries over, so a module the prelude and the program BOTH import is loaded once and
    // keeps its position in the root's half. Loading the prelude as the root is a no-op for the
    // same reason: it is already seen, so the result is the single unit it always was.
    visit(rootPath)
    val rootUnits = out
    val rootProg = rootUnits.last.program
    val rootHasCode = rootProg.hasCode
    if prelude.isEmpty || !rootHasCode then rootUnits
    else
      out = Nil
      prelude.foreach(visit)
      out ++ rootUnits

  /** ONE NAME DECLARED BY TWO MODULES — P-4 and P-6 of `v3/PRELUDE-CORRECTNESS.md`, which turned
    * out to be one question asked from two ends, answered here by one rule.
    *
    * P-6 — A UNIT CALLS ITS OWN. Two modules declaring one name at two arities, and a call written
    * inside one of them binding to the other's.
    *
    * REPRODUCED, not reported: `std/scljet/mutate.ssc` declares `filterRows(rows, drop)` and
    * `std/scljet/sql.ssc` declares `filterRows(rows, where, colNames)`; `sql.ssc` imports
    * `mutate.ssc` and `mutate.ssc` has never heard of `sql.ssc`. `merge` concatenates both into one
    * flat table, `Lower` resolves a call with `fns.indexOf` — the FIRST match — and the module that
    * lost the race calls a function it does not know exists. They never appeared in one program
    * before; with a prelude in every program they do.
    *
    * THE RULE THE DEPENDENCY DIRECTION GIVES, which is narrower than "prefer your own module": a
    * module sees its OWN declarations and those of what it IMPORTS, never those of a module that
    * imports IT. Direction is what makes this decidable without heuristics.
    *
    * REJECTED: resolving names against a per-call-site visibility set. That is the general answer
    * and it touches every resolution in the lowering, so every program pays for a defect a handful
    * have. The blast radius is the whole compiler; the bug is six modules wide.
    *
    * CHOSEN: rename only what actually collides. On a program where no name is declared by two
    * units — which is nearly all of them — `targets` is empty and NOTHING below runs, so the corpus
    * cannot move by accident. That property is the reason for this shape rather than a nicer one.
    *
    * WHICH COPY KEEPS THE NAME is not a free choice: it must be the one that wins TODAY, or a third
    * unit that calls the name without declaring it would silently change target. Today's winner is
    * the ROOT if the root declares it, and otherwise the FIRST declaring unit in closure order,
    * because `fns.indexOf` takes the first. So the owner keeps the name and every other declaring
    * unit is renamed.
    *
    * AND THAT SUBSUMES P-4, which is why there is one pass here and not two. P-4 said a name the
    * ROOT declares displaces every module's `def` of that name — measured, because `def` had it
    * BACKWARDS and a user's own function was silently ignored in favour of an imported one, while
    * `case class` was already right, so the two kinds disagreed about one question. It was written
    * as a FILTER: the module's copy was dropped. Dropping is the same rule as renaming for everyone
    * who calls the name from outside — the root's def is what they reach either way — and it is a
    * WRONG answer for the module itself, whose own calls then went to the root's function. The
    * `v3/loader-gate.sh` probe that found it is three modules and a root, all of it legal
    * ScalaScript, refused with `call to 'shared' passes 2 argument(s), it takes 1`.
    *
    * So: the owner keeps the name, everyone else is renamed, nobody is dropped. The root is simply
    * the owner of everything it declares. P-4's rule survives exactly — what changes is that a
    * module keeps its own function instead of losing it.
    *
    * THE NARROWNESS OF P-4 ALSO SURVIVES, and it is the part that cost most to learn. My first
    * attempt at that rule kept the LAST declaration of every name, which reads identically in
    * English and is not: several `def`s of one name inside ONE unit are a working mechanism of this
    * compiler, not a collision, and dropping them took the corpus from 204 to 132. Hence `.distinct`
    * per unit below — a unit's own duplicates are that unit's business and are renamed together.
    *
    * WHAT THIS DELIBERATELY DOES NOT DECIDE: a unit that CALLS a name without declaring it, where
    * two imported modules both provide one. Answering that needs the import EDGES rather than the
    * unit list, and it is a separate change with a separate measurement. The behaviour there is
    * unchanged and it is ambiguous; saying so is better than pretending the rename closed it. */
  private def disambiguate(units: List[Unit3]): List[Unit3] =
    // A name is only a candidate if it is declared by more than one unit AS A `def`. Counted per
    // unit — `.distinct` — because several `def`s of one name inside ONE unit are that unit's own
    // business and renaming them all together is exactly right.
    val declarers: Map[String, List[String]] =
      units.flatMap(u => u.program.defs.map(_.name).distinct.map(n => (n, u.path)))
        .groupBy((n, _) => n).map((n, ps) => (n, ps.map((_, p) => p)))
    // EVERY OTHER KIND OF DECLARATION IS AN EXCLUSION, because those names resolve through tables
    // this rewrite does not reach: a class is looked up by name in `classes`, a top-level `val` is
    // a module GLOBAL, an object is a namespace. Renaming a `def` that shares a name with one of
    // them would move half of a name and leave the other half behind.
    val otherKinds: Set[String] =
      units.flatMap(u =>
        u.program.classes.map(_.name) ++ u.program.objects.map(_.name) ++
        u.program.traits.map(_.name) ++ u.program.effects.map(_.name) ++
        u.program.topLevel.collect { case Stmt.Val(n, _, _, _) => n }).toSet
    val targets: Set[String] =
      declarers.filter((n, ps) => ps.length > 1 && !otherKinds.contains(n)).keySet
    if targets.isEmpty then units
    else
      val root = units.last
      val rootDeclares = root.program.defs.map(_.name).toSet
      def owner(n: String): String =
        if rootDeclares.contains(n) then root.path else declarers(n).head

      // The new name is checked against every name in the program rather than assumed unique. A
      // user CAN write `filterRows__2`; the check costs three lines and removes the assumption.
      var taken: Set[String] = units.flatMap(u => u.program.defs.map(_.name)).toSet ++ otherKinds
      def fresh(n: String): String =
        var i = 2
        while taken.contains(n + "__" + i.toString) do i = i + 1
        val c = n + "__" + i.toString
        taken = taken + c
        c

      /** A reference is rewritten only if the name is not BOUND inside the expression.
        *
        * `Expr.boundNames` is collected over the whole expression rather than per scope, so a
        * parameter or local of the same name anywhere inside leaves every reference in that body
        * alone. That is the conservative direction here: an un-renamed call keeps today's
        * behaviour, while a wrongly renamed one would redirect a call to a LOCAL — which is P-1's
        * defect wearing a different hat. */
      def rw(e: Expr, extra: Set[String], ren: Map[String, String]): Expr =
        val bound = extra ++ Expr.boundNames(e)
        val live = ren.filter((n, _) => !bound.contains(n))
        if live.isEmpty then e
        else Expr.mapDeep(e, x => x match
          case Expr.Call(fn, as, p) if live.contains(fn) => Expr.Call(live(fn), as, p)
          // A BARE NAME CAN BE A CALL: `def empty: List[A] = Nil` is referenced as `empty`, and
          // `Lower` turns a name that is a zero-arity def into a call. Leaving this case out would
          // rename the declaration and not its parenless uses.
          case Expr.Name(n, p) if live.contains(n) => Expr.Name(live(n), p)
          case other => other)

      def rwDef(d: Def, ren: Map[String, String], extra: Set[String]): Def =
        val inner = extra ++ (d.params ++ d.givenParams).map(_.name).toSet
        def rwParam(pm: Param): Param = pm.copy(default = pm.default.map(x => rw(x, inner, ren)))
        d.copy(params = d.params.map(rwParam), givenParams = d.givenParams.map(rwParam),
               body = rw(d.body, inner, ren))

      units.map { u =>
        val mine = u.program.defs.map(_.name).distinct
          .filter(n => targets.contains(n) && owner(n) != u.path)
        if mine.isEmpty then u
        else
          val ren = mine.map(n => (n, fresh(n))).toMap
          val p = u.program
          Unit3(u.path, p.copy(
            // The DECLARATION is renamed and so is every reference to it inside this unit, its own
            // recursive calls included — they are references like any other.
            defs = p.defs.map(d => rwDef(d.copy(name = ren.getOrElse(d.name, d.name)), ren, Set.empty)),
            // A METHOD's name is not a module-level name and is never renamed; only its body is
            // rewritten. Its OWN class's members go into the bound set, because `Lower.selfCalls`
            // reads an unqualified call to a sibling method as `this.m(…)` — renaming that would
            // redirect a method call to a top-level function.
            classes = p.classes.map { c =>
              val own = (c.methods.map(_.name) ++ c.fields.map(_.name)).toSet
              c.copy(methods = c.methods.map(m => rwDef(m, ren, own)),
                     fields = c.fields.map(f => f.copy(default = f.default.map(x => rw(x, own, ren)))))
            },
            objects = p.objects.map { o =>
              val own = (o.defs.map(_.name) ++ o.vals.map(_.name)).toSet
              o.copy(defs = o.defs.map(m => rwDef(m, ren, own)),
                     vals = o.vals.map(v => v.copy(value = rw(v.value, own, ren))))
            },
            traits = p.traits.map(t =>
              t.copy(methods = t.methods.map(m => rwDef(m, ren, t.methods.map(_.name).toSet)))),
            topLevel = p.topLevel.map { s => s match
              case Stmt.Val(n, v, mu, q) => Stmt.Val(n, rw(v, Set.empty, ren), mu, q)
              case Stmt.Exp(x)           => Stmt.Exp(rw(x, Set.empty, ren))
              case Stmt.LocalDef(d)      => Stmt.LocalDef(rwDef(d, ren, Set.empty))
            }))
      }

  /** One program from the closure: every unit's declarations, but only the ROOT's statements.
    *
    * An imported unit's `val`/`var` survive as declarations — they are what its `def`s read — while
    * its bare expressions are dropped. Keeping them would mean importing a module printed its
    * examples, which is the behaviour that makes people stop factoring code into modules. */
  def merge(units0: List[Unit3]): Program =
    if units0.isEmpty then throw LoadError("no units to merge")
    // ONE NAME, TWO MODULES — see `disambiguate`. Nothing is dropped: the owner keeps the name and
    // every other declaring unit gets its own copy back under a fresh one.
    val units = disambiguate(units0)
    val root = units.last
    var defs: List[Def] = Nil
    var classes: List[ClassDef] = Nil
    var objects: List[ObjectDef] = Nil
    var traits: List[TraitDef] = Nil
    var effects: List[TraitDef] = Nil
    var top: List[Stmt] = Nil
    var origin: Map[String, String] = Map.empty
    units.foreach { u =>
      // Only for units that are NOT the root: a declaration in the file the user named needs no
      // redirection, and recording it would make every ordinary diagnostic print a path twice.
      // Read AFTER the two passes above, so a renamed def maps its NEW name to its own file and a
      // diagnostic about it still names the module it was written in (P-2).
      if u.path != root.path then u.program.defs.foreach(d => origin = origin.updated(d.name, u.path))
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
    Program(defs, top, classes, objects, traits, effects, origin)
