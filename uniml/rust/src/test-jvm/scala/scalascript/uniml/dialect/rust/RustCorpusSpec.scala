package scalascript.uniml.dialect.rust

import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*
import scalascript.uniml.*

/** Losslessness over a REAL corpus of Rust, which is the property the chunker's byte-exact
  * slicing depends on: if the lexer ever dropped or normalised a byte, every span after it would
  * silently shift.
  *
  * The corpus is opt-in via `UNIML_RUST_CORPUS` (a directory walked for `*.rs`) so the module
  * stays self-contained and this suite is skipped, not failed, when nobody points it anywhere.
  * Run against rozum's own `crates/` with:
  *
  *     UNIML_RUST_CORPUS=/path/to/rozum/crates sbt "unimlRust/testOnly *RustCorpusSpec"
  */
final class RustCorpusSpec extends AnyFunSuite:

  private def corpusFiles: Vector[Path] =
    sys.env.get("UNIML_RUST_CORPUS").map(Paths.get(_)).filter(Files.isDirectory(_)) match
      case None => Vector.empty
      case Some(root) =>
        Files.walk(root).iterator().asScala
          .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".rs"))
          .toVector

  test("every .rs file in the corpus lexes losslessly"):
    val files = corpusFiles
    assume(files.nonEmpty, "set UNIML_RUST_CORPUS to a directory of .rs files")
    var checked = 0
    var bytes = 0L
    files.foreach { p =>
      val text = Files.readString(p)
      val rebuilt = RustLexer.lex(text).map(_.lexeme).mkString
      assert(rebuilt == text, s"lexer is not lossless for $p")
      checked += 1
      bytes += text.length.toLong
    }
    info(s"lossless over $checked files, $bytes chars")

  test("every .rs file in the corpus survives the DIALECT losslessly, not just the lexer"):
    // The lexer test above is not enough on its own: the processor runs whole item bodies together
    // into one token, so this is the check that the stream the VM actually receives — and that the
    // chunker's spans are computed from — still reproduces the file byte for byte.
    val files = corpusFiles
    assume(files.nonEmpty, "set UNIML_RUST_CORPUS to a directory of .rs files")
    var checked = 0
    files.foreach { p =>
      val text = Files.readString(p)
      val processor = RustDialect.instructions(SourceInput.fromString(SourceId(p.toString), text))
      val rebuilt = processor.stop(text).values.map(_.token.lexeme).mkString
      assert(rebuilt == text, s"the dialect is not lossless for $p")
      checked += 1
    }
    info(s"dialect-lossless over $checked files")

  test("every .rs file in the corpus produces items without hanging"):
    val files = corpusFiles
    assume(files.nonEmpty, "set UNIML_RUST_CORPUS to a directory of .rs files")
    var withItems = 0
    var totalItems = 0
    files.foreach { p =>
      val text = Files.readString(p)
      val tokens = RustLexer.lex(text)
      val items = RustStructure.collect(tokens, 0, tokens.length, 0)
      // Every item must be a well-formed, in-range, non-empty span.
      items.foreach { it =>
        assert(it.start >= 0 && it.end < tokens.length && it.start <= it.end, s"bad span in $p: $it")
      }
      if items.nonEmpty then withItems += 1
      totalItems += items.length
    }
    info(s"$totalItems items across ${files.length} files; $withItems files had at least one")
