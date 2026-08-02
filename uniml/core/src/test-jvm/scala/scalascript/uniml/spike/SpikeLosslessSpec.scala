package scalascript.uniml.spike

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The ScalaScript dialect's losslessness gate.
  *
  * Source reconstruction is the reason UniML belongs in a front end at all, and
  * before this gate existed it was checked by a throwaway probe — so a lexer
  * change could silently undo it. Every lexeme is a SOURCE SLICE and their
  * concatenation is the file, byte for byte; every value normalisation (string
  * escapes, hex folding, digit separators, `L` suffixes, char codes) belongs to
  * the projection.
  *
  * Chunk-invariance is checked with the same inputs: a dialect that answered
  * differently depending on how the reader split the file could not be a front
  * end. */
final class SpikeLosslessSpec extends AnyFunSuite:

  private def reconstruct(n: UniNode): String = n match
    case UniNode.Token(t)            => t.lexeme
    case UniNode.Branch(_, es, _, _) => es.map(e => reconstruct(e.child)).mkString

  private def parseWhole(text: String): ParseResult =
    UniML.parse(SourceInput.fromString(SourceId("lossless"), text), SpikeDialect)

  /** Same source, handed over in chunks, which is the read path a real front end
    * has. Must give the identical token stream. */
  private def parseChunked(text: String, size: Int): ParseResult =
    val chunks = text.grouped(size).toVector
    UniML.parse(SourceInput(SourceId("lossless"), chunks.map(SourceChunk.apply)), SpikeDialect)

  private val handWritten: Vector[(String, String)] = Vector(
    "def"            -> "def add(a: Int, b: Int): Int = a + b\n",
    "leading-trivia" -> "\n\n  # a comment\ndef f(): Int = 1\n\n",
    "using"          -> "def f(using ev: Int)(a: Int): Int = a\n",
    "case-class"     -> "case class Circle(r: Int) extends Shape\n",
    "enum"           -> "enum Color:\n  case Red\n  case Green\n",
    "string-escape"  -> "def f(): String = \"a\\nb\\tc\"\n",
    "triple-quoted"  -> "def f(): String = \"\"\"raw\ntext\"\"\"\n",
    "interpolation"  -> "def f(x: Int): String = s\"v=${x}!\"\n",
    "digit-sep"      -> "def f(): Int = 30_000\n",
    "hex"            -> "def f(): Int = 0xFF\n",
    "long-suffix"    -> "def f(): Int = 5L\n",
    "float-exponent" -> "def f(): Float = 1.5e10\n",
    "char"           -> "def f(): Int = 'a'\n",
    "char-escape"    -> "def f(): Int = '\\n'\n",
    "no-final-nl"    -> "def f(): Int = 1",
    "crlf"           -> "def f(): Int = 1\r\ndef g(): Int = 2\r\n",
    // the two operators ssc1-front REWRITES while lexing; the CST must keep the
    // spelling while the projection keeps the meaning
    "concat-3colon"  -> "def f(xs: List, ys: List): List = xs ::: ys\n",
    "prepend-plus"   -> "def f(x: Int, xs: List): List = x +: xs\n",
  )

  /** Real ScalaScript, not only hand-written shapes — the handWritten cases are the
    * ones a fix targets, these are the ones that catch what it missed. */
  private def repoFile(relative: String): Option[String] =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null).take(6)
      .map((p: Path) => p.resolve(relative))
      .find(Files.exists(_))
      .map(p => new String(Files.readAllBytes(p), "UTF-8"))

  private def repoRoot: Path =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("AGENTS.md")))
      .getOrElse(throw new IllegalStateException("repository root not found"))

  /** EVERY `.ssc` in the repository, not a hand-picked pair.
    *
    * The pair this started as passed while `xs ::: ys` was still losing a
    * character — the sweep is what found it, in one file out of 1,140. A gate
    * over examples somebody chose only ever proves the examples somebody chose. */
  private val fromRepo: Vector[(String, String)] =
    val root = repoRoot
    Files.walk(root).iterator.asScala
      .filter(p => p.toString.endsWith(".ssc"))
      .filterNot(p =>
        // `bin/` is INSTALLED OUTPUT — `install.sh --dev` copies the whole runtime there,
        // so leaving it in made the corpus grow from 1,145 files to 1,406 the moment
        // somebody ran the installer, and every count moved with it.
        p.toString.contains("/target/") || p.toString.contains("/.git/") ||
          p.toString.contains("/.worktrees/") || p.toString.contains("/bin/lib/"))
      .toVector
      .sortBy(_.toString)
      .map(p => root.relativize(p).toString -> new String(Files.readAllBytes(p), "UTF-8"))
      ++ Vector("specs/v2.2-p6.6-cmin.L").flatMap(rel => repoFile(rel).map(text => rel -> text))

  test("every lexeme is a source slice — the CST reconstructs the source exactly") {
    val cases = handWritten ++ fromRepo
    assert(fromRepo.sizeIs > 500, s"only ${fromRepo.size} repo files found — the sweep silently shrank")
    val broken = cases.filter { (_, text) => parseWhole(text).roots.map(reconstruct).mkString != text }
    assert(
      broken.isEmpty,
      broken.map { (label, text) =>
        val got = parseWhole(text).roots.map(reconstruct).mkString
        val at = text.zip(got).indexWhere((a, b) => a != b)
        s"$label: lost ${text.length - got.length} of ${text.length} chars, first difference at $at"
      }.mkString("\n"),
    )
  }

  test("the parse is invariant to how the source is chunked") {
    // hand-written shapes at every size, plus a deterministic slice of the repo —
    // 1,140 files x 4 chunk sizes is minutes, and the shapes are what varies
    val cases = handWritten ++ fromRepo.grouped(40).map(_.head).toVector
    val differing = cases.flatMap { (label, text) =>
      Vector(1, 7, 64, 4096).flatMap { size =>
        val whole = parseWhole(text).roots.map(reconstruct).mkString
        val split = parseChunked(text, size).roots.map(reconstruct).mkString
        if whole == split then None else Some(s"$label at chunk size $size")
      }
    }
    assert(differing.isEmpty, differing.mkString("\n"))
  }

  test("a token appears at most once — a tree that repeats one cannot reconstruct") {
    val offenders = (handWritten ++ fromRepo).flatMap { (label, text) =>
      val parsed = SpikeParse.parseProgram(SpikeLex.scan(SourceId(label), text))
      def leaves(n: Node): Vector[SourceToken] = n match
        case Node.Leaf(t, _)      => Vector(t)
        case Node.Frame(_, _, ks) => ks.flatMap(leaves)
      val ids = leaves(parsed.tree).map(_.id)
      val dups = ids.groupBy(identity).filter(_._2.sizeIs > 1)
      if dups.isEmpty then None else Some(s"$label: ${dups.size} duplicated token id(s)")
    }
    assert(offenders.isEmpty, offenders.mkString("\n"))
  }
