package scalascript.uniml.ssc

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import scalascript.uniml.dialect.scalascript.{SpikeAst, SpikeTyped}

/** The four questions `v3/specs/50-uniml-projection.md` §7 says to MEASURE before the projection is
  * written. Each is a census over the real corpus, printed with `info` so the numbers land in the
  * spec rather than in someone's memory, and pinned with an assertion so the answer cannot rot
  * silently between now and whoever writes the code.
  *
  * A census answers ONLY the question it asked — so each test states the question it is answering
  * and, where the answer is "zero", what a non-zero would have meant. Zero is the fragile kind of
  * answer: it holds until one corpus file changes, which is why these are assertions and not notes.
  */
final class Ssc3ProjectionCensusSpec extends AnyFunSuite:

  private def repoRoot: Path =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("AGENTS.md")))
      .getOrElse(throw new IllegalStateException("repository root not found"))

  private lazy val corpus: Vector[(String, Vector[SpikeAst.Node])] =
    val root = repoRoot
    val files = Files.walk(root).iterator.asScala
      .filter(_.toString.endsWith(".ssc"))
      .filterNot(p => p.toString.contains("/target/") || p.toString.contains("/.git/") ||
        p.toString.contains("/.worktrees/") || p.toString.contains("/bin/lib/"))
      .toVector.sortBy(_.toString)
    assert(files.sizeIs > 500, s"only ${files.size} .ssc found — the sweep silently shrank")
    files.map { p =>
      val src = new String(Files.readAllBytes(p), "UTF-8")
      val subtrees = spikeSubtrees(SscCompose.parse(src).root)
      root.relativize(p).toString -> subtrees.flatMap(sr => SpikeAst.walk(SpikeTyped.module(sr)))
    }

  /** A SECOND parent after `extends`, at bracket depth ZERO. The naive version — "the text after
    * `extends` contains a comma" — reported four hits and ALL FOUR were false: three were commas
    * inside type ARGUMENTS (`extends Either[A, B]`, `extends Proc[Int, Int]`) and one was a
    * comment. A census answers only the question it actually asked, and that one asked the wrong
    * one, which would have filed a request to grow a node that nothing needs. */
  private def hasSecondParent(afterExtends: String): Boolean =
    var depth = 0
    var i = 0
    var found = false
    val s = afterExtends
    while i < s.length && !found do
      val c = s.charAt(i)
      if c == '[' || c == '(' then depth += 1
      else if c == ']' || c == ')' then depth -= 1
      else if depth == 0 && c == ',' then found = true
      else if depth == 0 && c == '{' then i = s.length          // body opens; parents are over
      else if depth == 0 && s.startsWith(" with ", i) then found = true
      i += 1
    found

  /** `(x: Type) =>` where the arrow follows the CLOSING PAREN of that list — a typed lambda
    * parameter. The naive regex reported 195 lines, and the sample was dominated by
    * `case Some(s: String) =>` (a typed PATTERN) and `case class C(f: () => Any)` (a
    * function-typed FIELD). Neither is a lambda parameter, and both would have made a lossless
    * projection look lossy. */
  private def typedLambdaParam(line: String): Boolean =
    val l = line.trim
    if l.startsWith("def ") || l.startsWith("extern ") || l.startsWith("case ") ||
       l.contains("case class") || l.contains("extern def") then false
    else
      var i = 0
      var found = false
      while i < l.length && !found do
        if l.charAt(i) == '(' then
          var depth = 1
          var j = i + 1
          var sawColon = false
          while j < l.length && depth > 0 do
            val c = l.charAt(j)
            if c == '(' || c == '[' then depth += 1
            else if c == ')' || c == ']' then depth -= 1
            else if c == ':' && depth == 1 then sawColon = true
            j += 1
          // the arrow must follow this list immediately (allowing spaces), or it is not its lambda
          val rest = if j <= l.length then l.substring(j).dropWhile(_ == ' ') else ""
          if sawColon && rest.startsWith("=>") then found = true
        i += 1
      found

  private def spikeSubtrees(n: UniNode): Vector[UniNode] = n match
    case b: UniNode.Branch =>
      if b.kind.startsWith("spike.") then Vector(b) else b.edges.flatMap(e => spikeSubtrees(e.child))
    case _ => Vector.empty

  test("§7 Q1 — does the corpus write `case class C(…) extends A with B`?") {
    // `CaseClass.parent` is an Option[String]; v3's `ClassDef.parents` is a list. If the corpus has
    // a case class with MORE THAN ONE parent, the projection cannot be faithful until UniML's node
    // grows — and that is a request to file, not a workaround to invent.
    //
    // `Option` cannot represent two, so a second parent would already be LOST here rather than
    // visible as a count. The census therefore asks the CST-independent question the projection
    // actually cares about: how many case classes carry a parent at all, and does any source line
    // spell a second one.
    val ccs = corpus.flatMap((f, ns) => ns.collect { case c: SpikeAst.CaseClass => f -> c })
    val withParent = ccs.filter(_._2.parent.nonEmpty)
    info(s"case classes: ${ccs.size}, of which ${withParent.size} declare a parent")
    withParent.take(5).foreach((f, c) => info(s"  $f — case class ${c.name} extends ${c.parent.get}"))

    // The SOURCE-level check, because the node cannot show a loss it cannot hold.
    val root = repoRoot
    val multi = Files.walk(root).iterator.asScala
      .filter(_.toString.endsWith(".ssc"))
      .filterNot(p => p.toString.contains("/target/") || p.toString.contains("/.git/") ||
        p.toString.contains("/.worktrees/") || p.toString.contains("/bin/lib/"))
      .toVector
      .flatMap { p =>
        val lines = new String(Files.readAllBytes(p), "UTF-8").linesIterator.toVector
        lines.zipWithIndex.collect {
          case (l, i) if !l.trim.startsWith("//") && l.contains("case class") &&
                         l.contains(" extends ") && hasSecondParent(l.substring(l.indexOf(" extends ") + 9)) =>
            s"${root.relativize(p)}:${i + 1}  ${l.trim}"
        }
      }
    info(s"source lines spelling a case class with MORE THAN ONE parent: ${multi.size}")
    multi.take(5).foreach(m => info(s"  $m"))
    assert(multi.isEmpty,
      s"Q1 is ANSWERED YES — `CaseClass.parent: Option[String]` cannot hold these, so file the node " +
      s"growth as a request before writing the projection:\n${multi.mkString("\n")}")
  }

  test("§7 Q2 — do lambda params carry types or defaults anywhere in the corpus?") {
    // `Lambda.params` is Vector[String] — no types, no defaults. v3's lambdas take neither today, so
    // this is BELIEVED lossless; §7 says confirm against the corpus rather than assume. Again the
    // node cannot show what it cannot hold, so the check is on the SOURCE.
    val lambdas = corpus.flatMap((_, ns) => ns.collect { case l: SpikeAst.Lambda => l })
    info(s"lambdas projected: ${lambdas.size}")

    val root = repoRoot
    val typed = Files.walk(root).iterator.asScala
      .filter(_.toString.endsWith(".ssc"))
      .filterNot(p => p.toString.contains("/target/") || p.toString.contains("/.git/") ||
        p.toString.contains("/.worktrees/") || p.toString.contains("/bin/lib/"))
      .toVector
      .flatMap { p =>
        val lines = new String(Files.readAllBytes(p), "UTF-8").linesIterator.toVector
        lines.zipWithIndex.collect {
          case (l, i) if !l.trim.startsWith("//") && typedLambdaParam(l) =>
            s"${root.relativize(p)}:${i + 1}  ${l.trim.take(90)}"
        }
      }
    info(s"source lines with a TYPED lambda parameter: ${typed.size}")
    typed.take(8).foreach(t => info(s"  $t"))
    // THE ANSWER IS YES, and the assertion pins the answer rather than a threshold I invented.
    // `Vector[String]` drops a type the source wrote, on 164 lines as of 2026-08-07. That is
    // lossless FOR v3 ONLY while v3's lambdas take no parameter types — it is not lossless about
    // the source. If this ever reaches zero the question is worth re-asking, which is why the
    // assertion is two-sided.
    assert(typed.nonEmpty,
      "Q2 has flipped to NO — typed lambda parameters have vanished from the corpus; re-read §7")
    assert(typed.sizeIs > 50,
      s"Q2: typed lambda parameters have become rare (${typed.size}) — the answer measured on " +
      "2026-08-07 was 164, i.e. common; a large drop means the corpus changed, not the projection")
  }

  test("§7 Q3 — does any object hold a NESTED class in the corpus?") {
    // `Def` in ObjectDecl.members is a Decl, so an object COULD hold a nested class; v3 supports
    // `def` members only. §7 asks that the refusal fire rather than the class vanish. This measures
    // whether the case occurs at all — if it does not, the refusal is untested by the corpus and
    // whoever writes it must plant a case rather than rely on a green sweep.
    val objs = corpus.flatMap((f, ns) => ns.collect { case o: SpikeAst.ObjectDecl => f -> o })
    val nested = objs.flatMap((f, o) => o.members.collect {
      case c: SpikeAst.CaseClass => s"$f — object ${o.name} holds case class ${c.name}"
      case t: SpikeAst.TraitDecl => s"$f — object ${o.name} holds ${t.keyword} ${t.name}"
      case n: SpikeAst.ObjectDecl => s"$f — object ${o.name} holds object ${n.name}"
    })
    val memberKinds = objs.flatMap((_, o) => o.members.map(_.getClass.getSimpleName))
      .groupBy(identity).view.mapValues(_.size).toVector.sortBy(-_._2)
    info(s"objects: ${objs.size}; member kinds: ${memberKinds.map((k, n) => s"$k=$n").mkString(", ")}")
    info(s"objects holding a nested CLASS/TRAIT/OBJECT: ${nested.size}")
    nested.take(5).foreach(n => info(s"  $n"))
    // THE ANSWER IS YES, BUT BARELY — AND THE FIRST ANSWER WAS AN ARTEFACT OF A BUG IN THIS
    // PARSER. Measured the same morning: 180 nested declarations across 257 objects, member
    // histogram ObjectDecl=96, CaseClass=84, Def=651. Every one of those numbers was wrong.
    //
    // `parseObject` and `parseTraitOrClassNoop` ran their member loop even when the declaration had
    // NO BODY — no braces, no colon. `bodyCol` then took the column of the NEXT TOP-LEVEL
    // DECLARATION, `peekCol >= bodyCol` held trivially, and the sibling was parsed as a member.
    // Cascading, so `trait K` / `case object A` / `case class B` / `def f` at column 1 collapsed
    // into ONE declaration four deep. The reference front does not do this — `ssc1-front.ssc0:2991`
    // requires a `{` and gives an EMPTY body otherwise — and neither does any Scala compiler.
    //
    // After the fix: 4 nested declarations, ObjectDecl=0, CaseClass=4, Def=261. So 176 of the 180
    // were SWALLOWED SIBLINGS and 390 top-level `def`s had been absorbed into a preceding object.
    // The lesson is the one this repo keeps relearning: a census answers only its own question, and
    // this one was asked of a tree the parser had rearranged. It read as a hot path and was noise.
    //
    // The refusal v3 must write is therefore on a COLD path — 4 cases, all `case class` inside an
    // `object` — which is exactly what §7 said to find out before writing it. Whoever writes it
    // plants a case; a green corpus sweep proves nothing here.
    assert(nested.nonEmpty,
      "Q3 has flipped to NO — no object holds a nested declaration any more; re-read §7")
    assert(nested.sizeIs >= 4,
      s"Q3: nested declarations dropped to ${nested.size} from the 4 measured on 2026-08-07 " +
      "AFTER the body-less-declaration swallow was fixed — that is a corpus change")
    assert(nested.sizeIs < 100,
      s"Q3: nested declarations jumped to ${nested.size}. The swallow bug is BACK: a body-less " +
      "`trait X` or `object X` is eating the declarations that follow it at the same column.")
    assert(memberKinds.toMap.getOrElse("ObjectDecl", 0) == 0,
      s"Q3: an object holds a nested OBJECT again (${memberKinds.toMap.getOrElse("ObjectDecl", 0)}). " +
      "That was the swallow bug's loudest symptom — check the offside rule in parseObject.")
  }

  test("§7 Q4 — a span's line/column agrees with its offset, on every token of every file") {
    // v3's `Pos` is line and column; `SourceSpan` carries offsets too. The mapping is mechanical,
    // and the front-diff gate deliberately does NOT compare positions — so nothing else in the
    // design would catch a one-column error, and a diagnostic pointing one column off is a real
    // regression. This recomputes line/col from the OFFSET and the source text and requires the
    // token's own numbers to match, which is the property the projection will rely on.
    var checked = 0L
    var bad = Vector.empty[String]
    val root = repoRoot
    val files = Files.walk(root).iterator.asScala
      .filter(_.toString.endsWith(".ssc"))
      .filterNot(p => p.toString.contains("/target/") || p.toString.contains("/.git/") ||
        p.toString.contains("/.worktrees/") || p.toString.contains("/bin/lib/"))
      .toVector.sortBy(_.toString)
    files.foreach { p =>
      val src = new String(Files.readAllBytes(p), "UTF-8")
      val toks = UniNode.sourceTokens(SscCompose.parse(src).root)
      // OFFSETS ARE CODE POINTS, not UTF-16 code units — which is the answer to Q4 and the reason
      // it needed its own check. The first version of this probe used `src.substring(0, offset)`
      // and reported disagreements in exactly one file: `examples/control-center-live.ssc`, whose
      // line 93 carries four emoji. 177 chars + 4 astral characters = the 181 it computed for a
      // column on a 177-char line. The TOKEN was right and the probe was wrong, and a projection
      // mapping `SourceSpan` to v3's `Pos` with `substring` would be off by one per astral
      // character — precisely the "diagnostic pointing one column off" §7 warns about.
      val cps = src.codePoints().toArray
      toks.foreach { t =>
        val off = t.span.start.offset
        if off >= 0 && off <= cps.length then
          var line = 1
          var col = 1
          var k = 0
          while k < off do
            if cps(k) == '\n'.toInt then { line += 1; col = 1 } else col += 1
            k += 1
          checked += 1
          if (t.span.start.line != line || t.span.start.column != col) && bad.sizeIs < 5 then
            bad = bad :+ s"${root.relativize(p)} offset $off: token says ${t.span.start.line}:${t.span.start.column}, source says $line:$col"
      }
    }
    info(f"span line/column checked against offset on $checked%,d tokens across ${files.size} files")
    assert(bad.isEmpty, s"Q4: a span's line/column disagrees with its offset:\n${bad.mkString("\n")}")
  }

  test("§7 Q4, the trap — UTF-16 arithmetic gives a DIFFERENT column, so the mapping is not mechanical") {
    // The finding above stated as a property rather than left in a comment: on a line containing an
    // astral character, indexing the source with a code-POINT offset as if it were a code-UNIT
    // offset lands somewhere else. One corpus file already reaches this (control-center-live.ssc,
    // whose line 93 carries four emoji), so it is not a theoretical unit mismatch.
    val text = "def f(): Unit =\n  val s = \"🤖💻\"\n  println(s)\n"
    val toks = UniNode.sourceTokens(SscCompose.parse(text).root)
    val cps = text.codePoints().toArray
    val onLast = toks.filter(t => t.span.start.offset > 0 && t.span.start.offset <= cps.length)
    assert(onLast.nonEmpty)

    def byCodePoint(off: Int): (Int, Int) =
      var line = 1; var col = 1; var k = 0
      while k < off do { if cps(k) == '\n'.toInt then { line += 1; col = 1 } else col += 1; k += 1 }
      (line, col)
    def byCodeUnit(off: Int): (Int, Int) =
      val before = text.substring(0, math.min(off, text.length))
      (before.count(_ == '\n') + 1, off - (before.lastIndexOf('\n') + 1) + 1)

    val t = onLast.find(x => byCodePoint(x.span.start.offset)._1 == 3).getOrElse(onLast.last)
    val cp = byCodePoint(t.span.start.offset)
    assert((t.span.start.line, t.span.start.column) == cp,
      s"the token disagrees with code-point arithmetic: ${t.span.start.line}:${t.span.start.column} vs $cp")
    assert(byCodeUnit(t.span.start.offset) != cp,
      "the two arithmetics AGREE here, so this case no longer exercises the trap — pick a token " +
      "after the astral characters, or the assertion above proves nothing")
  }
