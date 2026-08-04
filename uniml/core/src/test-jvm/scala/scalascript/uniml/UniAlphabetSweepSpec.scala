package scalascript.uniml

import org.scalatest.funsuite.AnyFunSuite

/** The sweep `v3/specs/20-core-language.md` §3 asks for:
  *
  * > a sweep over the whole code-point range on the jvm lane, comparing this classifier against
  * > Java's, states exactly where we differ on purpose — which is the difference between a decision
  * > and an accident.
  *
  * JVM-only by construction: the point is to compare against the host's Unicode tables, and those
  * are what [[UniAlphabet]] exists to avoid depending on.
  *
  * **These are theorems, not frozen counts.** A test asserting "exactly 1,432 code points differ"
  * goes red when a JDK ships a new Unicode version — for a reason that has nothing to do with this
  * repository — and the next person raises the number to make it green, which is the opposite of a
  * gate. Every assertion below is a structural claim that holds for any Unicode version; the counts
  * are printed for the record and asserted only where they are ASCII-range and therefore fixed.
  */
final class UniAlphabetSweepSpec extends AnyFunSuite:

  private val AllChars: Range = 0 to 0xffff

  test("no valid Scala identifier stops being one — the safety direction, over the whole range") {
    // The claim that makes `>= U+0080` safe to adopt: we may accept MORE than the host, never less.
    // If this ever fails, an existing program has changed meaning and the decision was mis-stated.
    val falseRejects = AllChars.filter { cp =>
      val char = cp.toChar
      Character.isLetter(char) && !UniAlphabet.isIdStart(char)
    }
    assert(falseRejects.isEmpty, s"characters Java calls letters that we refuse to start an identifier: ${falseRejects.take(20).map(c => f"U+$c%04X").mkString(", ")}")

    val falseRejectParts = AllChars.filter { cp =>
      val char = cp.toChar
      Character.isLetterOrDigit(char) && !UniAlphabet.isIdPart(char)
    }
    assert(falseRejectParts.isEmpty, s"letters-or-digits refused inside an identifier: ${falseRejectParts.take(20).map(c => f"U+$c%04X").mkString(", ")}")
  }

  test("every extra character we accept is at or above U+0080 — the divergence is confined, not scattered") {
    val extras = AllChars.filter { cp =>
      val char = cp.toChar
      UniAlphabet.isIdStart(char) && !Character.isJavaIdentifierStart(char)
    }
    val belowBoundary = extras.filter(_ < 128)
    assert(belowBoundary.isEmpty, s"ASCII characters accepted that Java rejects — not what §3 decided: ${belowBoundary.map(c => f"U+$c%04X").mkString(", ")}")
    info(s"accepted beyond Java's identifier start, all >= U+0080: ${extras.size} code points")
  }

  test("whitespace is the five §3 names and narrower than the host — deliberately") {
    val ours = AllChars.filter(cp => UniAlphabet.isWhitespace(cp.toChar)).toSet
    assert(ours == Set(' '.toInt, '\t'.toInt, '\n'.toInt, '\r'.toInt, '\f'.toInt))

    // Narrowing is the intended direction here, unlike the identifier classes: §3 says "and nothing
    // else". What must NOT happen is the reverse — a character we call whitespace that the host
    // does not — because that would silently split a token the host keeps whole.
    val weSayHostDoesNot = ours.filterNot(cp => Character.isWhitespace(cp.toChar))
    assert(weSayHostDoesNot.isEmpty, s"we call these whitespace and Java does not: $weSayHostDoesNot")

    val hostSaysWeDoNot = AllChars.filter(cp => Character.isWhitespace(cp.toChar) && !ours.contains(cp))
    info(s"host whitespace we deliberately exclude: ${hostSaysWeDoNot.map(c => f"U+$c%04X").mkString(", ")}")
  }

  test("case agrees with the host EXACTLY over the whole range — the table's whole purpose") {
    // Before the table this was one-directional: ASCII exact, everything above U+0080 answered
    // false. That silently changed what `case Число =>` meant. With the generated table there is no
    // divergence left to describe, so the assertion is equality — the strongest form this can take,
    // and the one that goes red the moment the table drifts from the JDK it was generated against.
    val disagreement = AllChars.filter { cp =>
      UniAlphabet.isTypeNameStart(cp.toChar) != Character.isUpperCase(cp.toChar)
    }
    assert(
      disagreement.isEmpty,
      s"${disagreement.size} code points disagree with the host, first few: ${disagreement.take(20).map(c => f"U+$c%04X").mkString(", ")}. " +
        "Regenerate upperRanges from this JDK, or decide deliberately to pin an older Unicode version.",
    )

    // The two characters the whole question was about, asserted by name so the intent survives.
    assert(UniAlphabet.isTypeNameStart('Ч'), "a Cyrillic capital must be a type-name start")
    assert(!UniAlphabet.isTypeNameStart('ч'), "a Cyrillic lowercase must not be")

    // Other_Uppercase, which is where ScalaScript's OWN runtimes disagree with each other: the
    // interpreter delegates to Character.isUpperCase, the js lane tests /\\p{Lu}/u, and 42 BMP
    // characters differ — Roman numerals among them. A baked table is what makes every lane agree.
    assert(UniAlphabet.isTypeNameStart('\u2167'), "Other_Uppercase must be included, or the js lane and the interpreter part ways")
  }

  test("the classifier is total — no character is both a digit and an identifier-start-only class") {
    // Cheap structural sanity that catches a copy-paste error in a range bound, which is the way
    // these predicates actually break.
    assert(AllChars.forall(cp => !UniAlphabet.isDigit(cp.toChar) || UniAlphabet.isIdPart(cp.toChar)))
    assert(AllChars.forall(cp => !UniAlphabet.isOctDigit(cp.toChar) || UniAlphabet.isDigit(cp.toChar)))
    assert(AllChars.forall(cp => !UniAlphabet.isDigit(cp.toChar) || UniAlphabet.isHexDigit(cp.toChar)))
    assert(AllChars.forall(cp => !UniAlphabet.isAsciiLetter(cp.toChar) || UniAlphabet.isIdStart(cp.toChar)))
    assert((0 to 127).forall(cp => UniAlphabet.isHexDigit(cp.toChar) == (Character.digit(cp.toChar, 16) >= 0)))
  }
