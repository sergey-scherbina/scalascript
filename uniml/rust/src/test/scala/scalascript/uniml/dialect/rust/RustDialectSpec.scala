package scalascript.uniml.dialect.rust

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** Every assertion here is one of the spec's Behavior items (docs/specs/rag-rust-dialect.md in
  * rozum). The sharp ones are the brace-hiding cases: they are the entire reason this dialect
  * lexes at all instead of matching braces with a regex. */
final class RustDialectSpec extends AnyFunSuite:

  private def parse(text: String): ParseResult =
    UniML.parse(SourceInput.fromString(SourceId("t.rs"), text), RustDialect, Limits.default)

  /** Top-level branch kinds, in order. */
  private def topKinds(text: String): Vector[String] =
    parse(text).roots.collect { case UniNode.Branch(kind, _, _, _) => kind }

  /** The exact source slice a branch covers, which is what the chunker will use. */
  private def topSlices(text: String): Vector[String] =
    parse(text).roots.collect { case UniNode.Branch(_, _, span, _) =>
      text.substring(span.start.offset, span.end.offset)
    }

  test("lossless: concatenating every lexeme reproduces the input"):
    val samples = Vector(
      "fn main() {}\n",
      "// c\n/* /* n */ */\nlet s = \"}{\";\n",
      "fn f<'a>(x: &'a str) -> char { 'x' }\n",
      "let r = r#\"raw \"quoted\" }\"#;\n",
      "",
      "   \n\t\n",
      "let n = 1.0_f64; let m = 0xFF_u8; let r = 0..n;\n",
    )
    samples.foreach { s =>
      assert(RustLexer.lex(s).map(_.lexeme).mkString == s, s"not lossless for: $s")
    }

  test("a brace inside a string, a char or a comment does not open or close an item"):
    // Every `{` and `}` below is hidden. If the lexer missed one, `f`'s body would close early
    // and `g` would either vanish or nest inside it — which is exactly the failure a regex
    // splitter has and the reason this dialect exists.
    val src =
      """fn f() {
        |    let a = "{";
        |    let b = '}';
        |    // }
        |    /* } { */
        |    let c = r#"} still raw {"#;
        |}
        |fn g() {}
        |""".stripMargin
    assert(topKinds(src) == Vector("rust.fn", "rust.fn"), topKinds(src).toString)
    assert(topSlices(src).head.contains("still raw"), "f's chunk must span its whole body")

  test("nested block comments and hashed raw strings terminate correctly"):
    val nested = RustLexer.lex("/* a /* b */ c */x")
    assert(nested.head.kind == "rust.block-comment")
    assert(nested.head.lexeme == "/* a /* b */ c */", nested.head.lexeme)
    val raw = RustLexer.lex("r##\"a \"# b\"##x")
    assert(raw.head.kind == "rust.string")
    assert(raw.head.lexeme == "r##\"a \"# b\"##", raw.head.lexeme)

  test("'a is a lifetime, 'a' is a char"):
    val lt = RustLexer.lex("fn f<'a>(x: &'a str)")
    assert(lt.exists(t => t.kind == "rust.lifetime" && t.lexeme == "'a"), lt.toString)
    assert(!lt.exists(_.kind == "rust.char"), "no char literal in a lifetime-only signature")
    val ch = RustLexer.lex("let c = 'a';")
    assert(ch.exists(t => t.kind == "rust.char" && t.lexeme == "'a'"), ch.toString)
    // The failure this guards: reading `'a` as an unterminated char would swallow to the next
    // quote and take every brace in between with it.
    val both = "fn f<'a>() { let c = '}'; }\nfn g() {}\n"
    assert(topKinds(both) == Vector("rust.fn", "rust.fn"), topKinds(both).toString)

  test("a doc comment and attributes belong to the item that follows"):
    val src =
      """/// Doc line.
        |#[inline]
        |pub fn f() {}
        |fn g() {}
        |""".stripMargin
    val slices = topSlices(src)
    assert(slices.length == 2, slices.toString)
    assert(slices.head.startsWith("/// Doc line."), slices.head)
    assert(slices.head.contains("#[inline]"), slices.head)
    assert(slices.head.trim.endsWith("pub fn f() {}"), slices.head)
    // Disjoint: the next item's slice starts after the first one's, never repeating it.
    assert(!slices(1).contains("fn f"), slices(1))

  test("methods inside an impl are their own items, nested in it"):
    val src =
      """impl Foo {
        |    pub fn a(&self) {}
        |    fn b(&self) {}
        |}
        |""".stripMargin
    val roots = parse(src).roots
    val impls = roots.collect { case b @ UniNode.Branch("rust.impl", _, _, _) => b }
    assert(impls.length == 1, roots.toString)
    val inner = impls.head.edges.map(_.child).collect { case UniNode.Branch(k, _, _, _) => k }
    assert(inner == Vector("rust.fn", "rust.fn"), inner.toString)

  test("item kinds and body-less declarations"):
    val src =
      """use std::fmt;
        |const MAX: usize = 8;
        |struct S { a: u8 }
        |enum E { A }
        |trait T { fn m(&self); }
        |mod m {}
        |type Alias = u8;
        |const fn cf() {}
        |""".stripMargin
    assert(
      topKinds(src) == Vector(
        "rust.use", "rust.const", "rust.struct", "rust.enum",
        "rust.trait", "rust.mod", "rust.type", "rust.fn",
      ),
      topKinds(src).toString,
    )
    // `const fn` is a FUNCTION — `const` is a modifier there, an item keyword everywhere else.
    assert(topSlices(src).last.trim == "const fn cf() {}", topSlices(src).last)

  test("a file with no items still lexes losslessly and yields no branches"):
    val src = "pub mod a;\npub mod b;\n"
    // `mod a;` IS an item (body-less), so this file has two — the "no items at all" case is a
    // file of comments, which must not crash and must not invent a branch.
    assert(topKinds(src) == Vector("rust.mod", "rust.mod"))
    val commentsOnly = "// just a note\n/* and another */\n"
    assert(topKinds(commentsOnly).isEmpty, topKinds(commentsOnly).toString)
    assert(RustLexer.lex(commentsOnly).map(_.lexeme).mkString == commentsOnly)

  /** The lexemes the DIALECT hands the VM, in order. Distinct from `RustLexer.lex` because the
    * processor runs whole item bodies together into one token. */
  private def vmLexemes(text: String): Vector[String] =
    val processor = RustDialect.instructions(SourceInput.fromString(SourceId("t.rs"), text))
    processor.stop(text).values.map(_.token.lexeme)

  test("coalescing bodies keeps the token stream lossless and the item heads intact"):
    // Bodies reach the VM as ONE token so that a frame holds a handful of edges instead of
    // thousands (see RustDialect.stop). The two things that must survive that: the lexemes still
    // reproduce the source exactly, and the head — which is where the chunker reads the item's
    // name — is still token by token.
    val src =
      """/// Doc.
        |pub fn parse_header(line: &str) -> Option<u8> {
        |    let x = 1;
        |    if x > 0 { return None; }
        |    Some(0)
        |}
        |struct S { a: u8 }
        |""".stripMargin
    assert(vmLexemes(src).mkString == src, "the VM token stream must still reproduce the source")
    // The head survives as separate tokens: `fn` and the name are individually findable.
    val lexemes = vmLexemes(src)
    assert(lexemes.contains("fn"), lexemes.toString)
    assert(lexemes.contains("parse_header"), lexemes.toString)
    // The body did NOT survive as separate tokens — that is the whole point.
    assert(!lexemes.contains("Some"), "the body should have been run together into one token")
    // And the branch span is unchanged by any of it.
    assert(topKinds(src) == Vector("rust.fn", "rust.struct"), topKinds(src).toString)
    assert(topSlices(src).head.startsWith("/// Doc."), topSlices(src).head)
    assert(topSlices(src).head.trim.endsWith("}"), topSlices(src).head)

  test("windowing is invisible: tokens that straddle or exceed a window are still whole"):
    // The lexer works in 1024-code-unit windows so that slicing a lexeme costs O(window) rather
    // than O(offset) — see RustLexer.lex. Every sample above is shorter than one window, so this
    // is the test that actually exercises it. What must hold is that the seams leave no trace:
    // the token stream is exactly what an unwindowed lex would produce.
    val filler = "fn f() { let x = 1; }\n" * 400 // ~8 KB: several windows
    assert(RustLexer.lex(filler).map(_.lexeme).mkString == filler, "lossless across window seams")
    // Every `fn` survived: a seam inside an identifier must not split it into two idents.
    assert(RustLexer.lex(filler).count(t => t.kind == "rust.ident" && t.lexeme == "fn") == 400)

    // A SINGLE token far longer than a window — the case that forces the retry-at-double-size
    // path. If the window were not grown, this comment would be chopped into fragments and the
    // `}` inside it would close `f` early.
    val bigComment = "/* " + ("pad } { " * 900) + " */" // ~7 KB, one token
    val src = "fn f() {\n" + bigComment + "\nlet y = 2;\n}\nfn g() {}\n"
    val toks = RustLexer.lex(src)
    assert(toks.map(_.lexeme).mkString == src, "lossless with an over-long token")
    val comments = toks.filter(_.kind == "rust.block-comment")
    assert(comments.length == 1, s"expected one block comment, got ${comments.length}")
    assert(comments.head.lexeme == bigComment, "the comment must be one whole token")
    assert(topKinds(src) == Vector("rust.fn", "rust.fn"), topKinds(src).toString)

    // Same for an over-long string literal, whose closing quote is what the retry must reach.
    val bigString = "\"" + ("}{" * 900) + "\""
    val strSrc = "fn f() { let s = " + bigString + "; }\nfn g() {}\n"
    assert(RustLexer.lex(strSrc).map(_.lexeme).mkString == strSrc)
    assert(topKinds(strSrc) == Vector("rust.fn", "rust.fn"), topKinds(strSrc).toString)

  test("an unbalanced file does not hang and stays lossless"):
    val src = "fn f() { let a = \"unterminated;\nfn g() {}\n"
    assert(RustLexer.lex(src).map(_.lexeme).mkString == src)
    val res = parse(src)
    // Whatever it decides, it must terminate and produce a result the caller can inspect.
    assert(res.roots.nonEmpty || res.diagnostics.nonEmpty)
