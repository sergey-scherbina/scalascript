package scalascript.uniml

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.dialect.Literal

final class LiteralSpec extends AnyFunSuite:
  test("literal fallback is lossless and invariant to chunk boundaries") {
    val source = SourceId("memory:unicode")
    val text = "a😀\nβ"
    val whole = UniML.parse(SourceInput.fromString(source, text), Literal)
    val chunked = UniML.parse(SourceInput(source, Vector(
      SourceChunk("a\uD83D"),
      SourceChunk("\uDE00\n"),
      SourceChunk("β"),
    )), Literal)

    assert(whole.status == CompletionStatus.Complete)
    assert(chunked.status == CompletionStatus.Complete)
    assert(whole.roots == chunked.roots)
    val tokens = whole.roots.collect { case UniNode.Token(token) => token }
    assert(tokens.map(_.lexeme).mkString == text)
    assert(tokens.map(_.span.start.offset) == Vector(0, 1, 2, 3))
    assert(tokens.last.span.end.offset == 4)
    assert(tokens.last.span.start.line == 2)
  }

  test("unpaired surrogates remain visible and produce a structured diagnostic") {
    val result = UniML.parse(
      SourceInput.fromString(SourceId("memory:invalid"), "x\uD83D"),
      Literal,
    )

    assert(result.status == CompletionStatus.Incomplete)
    assert(result.diagnostics.map(_.code) == Vector("uniml.literal.unpaired-surrogate"))
    assert(result.roots.collect { case UniNode.Token(token) => token.lexeme }.mkString == "x\uD83D")
  }

  test("dialect registry rejects aliases that collide") {
    val first = DialectRegistry(Literal)
    assert(first.isRight)
    assert(first.toOption.flatMap(_.get("text")).contains(Literal))
    assert(first.toOption.exists(_.register(Literal).isLeft))
  }
