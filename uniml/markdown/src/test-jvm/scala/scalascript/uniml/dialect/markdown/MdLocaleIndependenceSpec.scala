package scalascript.uniml.dialect.markdown

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import java.util.Locale

/** The same document must parse the same way whatever locale the JVM was started in.
  *
  * `UNIML-SSC3-ALPHABET` removed the host's `Char` CLASSIFICATION — `isLetter`, `isDigit` — because
  * routing the alphabet through the host makes the language's syntax host-dependent. It missed the
  * other half of the same bug: `String.toLowerCase()` with no argument uses the JVM's DEFAULT
  * LOCALE, so it is not a property of the string at all.
  *
  * In Turkish, `"I".toLowerCase` is `"ı"` — dotless i — not `"i"`. Every HTML tag name with an
  * `I` in it is therefore mis-folded: `<LI>`, `<TITLE>`, `<IFRAME>`, `<DIALOG>`, `<FIGCAPTION>`.
  * The document does not change; the environment variable does.
  *
  * That makes it strictly worse than the `isLetter` case, which at least needed a different
  * RUNTIME to diverge. This one diverges on the same runtime started with `-Duser.language=tr`.
  *
  * ⚠️ These tests MUTATE THE DEFAULT LOCALE and restore it in a `finally`. That is global state, so
  * they are deliberately few and each restores immediately. The alternative — testing the helper
  * and not the parser — would assert that a function is ASCII without ever showing that the parser
  * uses it, which is the shape of test this repository has been burned by. */
final class MdLocaleIndependenceSpec extends AnyFunSuite:

  /** Run `body` with the default locale set to `tag`, restoring it whatever happens. */
  private def inLocale[A](tag: String)(body: => A): A =
    val saved = Locale.getDefault
    Locale.setDefault(Locale.forLanguageTag(tag))
    try body finally Locale.setDefault(saved)

  private def kinds(text: String): Vector[String] =
    def walk(n: UniNode): Vector[String] = n match
      case b: UniNode.Branch => b.kind +: b.edges.flatMap(e => walk(e.child))
      case _                 => Vector.empty
    Markdown.parse(SourceInput.fromString(SourceId("memory:locale"), text), MarkdownProfile.CommonMark)
      .roots.flatMap(walk)

  /** The parse, reduced to what a reader would compare: which block kinds came out, in order. */
  private def shape(text: String): Vector[String] = kinds(text).filter(_.startsWith("markdown."))

  test("an uppercase HTML tag is an HTML block in Turkish too — `<LI>` folds to `li`, not `lı`") {
    val doc = "<LI>item</LI>\n"
    val root = shape(doc)
    val turkish = inLocale("tr")(shape(doc))
    assert(turkish == root,
           s"the same document parsed differently under a Turkish locale.\nroot=$root\ntr  =$turkish")
  }

  test("a link reference resolves in Turkish — the label is folded, and `I` is the trap") {
    // CommonMark matches a reference to its definition case-insensitively. `TITLE` contains the
    // one letter whose lowering is locale-dependent.
    val doc = "[TITLE]\n\n[title]: /url\n"
    val root = shape(doc)
    val turkish = inLocale("tr")(shape(doc))
    assert(turkish == root,
           s"a link reference resolved differently under a Turkish locale.\nroot=$root\ntr  =$turkish")
  }

  test("an autolink prefix is detected in Turkish — `WWW.` and the scheme colon") {
    val doc = "<HTTP://example.com>\n\nWWW.example.com\n"
    val root = shape(doc)
    val turkish = inLocale("tr")(shape(doc))
    assert(turkish == root,
           s"an autolink was detected differently under a Turkish locale.\nroot=$root\ntr  =$turkish")
  }

  test("the locale is actually restored — the harness itself must not leak") {
    // The control for the fixture, not for the parser. A test that mutates global state and then
    // fails to restore it makes every LATER test's result a lie, and the failure would show up
    // somewhere else entirely.
    val before = Locale.getDefault
    inLocale("tr")(())
    assert(Locale.getDefault == before, "inLocale did not restore the default locale")
  }

  test("Turkish really does fold I differently — the premise, asserted rather than assumed") {
    // If this ever stops being true the tests above become vacuous: they would compare two
    // identical runs and pass for the wrong reason. So the premise is checked.
    val turkish = inLocale("tr")("I".toLowerCase)
    assert(turkish != "i", "the JVM no longer folds Turkish `I` to a dotless i — the tests above are now vacuous")
  }
