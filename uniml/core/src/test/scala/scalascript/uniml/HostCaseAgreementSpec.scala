package scalascript.uniml

import org.scalatest.funsuite.AnyFunSuite

/** Does the HOST's character classification actually differ between our lanes?
  *
  * `v3/specs/20-core-language.md` §3 rejects calling the host on the grounds that "the same source
  * would then lex differently on the JVM, on JS and on the v2 VM, making the language's syntax
  * host-dependent". That is an empirical claim and nothing in this repository had measured it.
  *
  * Measured 2026-08-05: the JVM and Scala.js lanes agree EXACTLY (1,169 uppercase, identical
  * hash), so as compile hosts they were never the problem. The divergence is between ScalaScript's
  * OWN runtimes — the interpreter delegates to `Character.isUpperCase` while the js backend tests
  * `/\p{Lu}/u`, and 42 BMP characters differ (Roman numerals among them). That is what made a
  * baked table the right answer rather than a host call.
  *
  * This spec is in the SHARED test scope so it runs on both lanes, and it freezes the fingerprint:
  * it is a canary for the two lanes drifting apart, not a test of the alphabet, which no longer
  * consults the host at all.
  */
final class HostCaseAgreementSpec extends AnyFunSuite:

  test("fingerprint the host's own idea of uppercase and letters over the BMP") {
    var upper = 0
    var letter = 0
    var upperHash = 0
    var letterHash = 0
    var cp = 0
    while cp <= 0xffff do
      val char = cp.toChar
      if char.isUpper then { upper += 1; upperHash = upperHash * 31 + cp }
      if char.isLetter then { letter += 1; letterHash = letterHash * 31 + cp }
      cp += 1
    info(s"HOSTCASE upper=$upper upperHash=$upperHash letter=$letter letterHash=$letterHash")
    // Frozen from a run where JVM and JS produced byte-identical answers. A red here means one
    // lane's Unicode data moved — worth knowing deliberately, since the language no longer depends
    // on it but every `.isUpper` call in the compiler's own Scala source still does.
    assert(upper == 1169 && upperHash == 2030252041, "this lane's uppercase set no longer matches the frozen fingerprint")
    assert(letter == 48965 && letterHash == -716495195, "this lane's letter set no longer matches the frozen fingerprint")

    // A handful of individual answers, so a disagreement is readable rather than just a hash gap.
    val probes = Vector('Ч', 'ч', 'Δ', 'δ', '日', 'İ', 'ǅ', 'Ǆ', 'ﬁ')
    info("HOSTPROBE " + probes.map(c => s"${c.toInt}:${if c.isUpper then "U" else "-"}${if c.isLetter then "L" else "-"}").mkString(" "))
  }
