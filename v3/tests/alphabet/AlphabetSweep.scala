// The lexical alphabet: ONE copy, reached by both names, and where it differs from the host.
//
// This used to compare v3's table against UniML's, because there were two. There is one now
// (`alphabet/src/Alphabet.scala`, a source directory both builds include), so that comparison would
// be a table against ITSELF — green by construction, which is the vacuous-gate shape this
// repository has paid for twice. It asserts two different things instead:
//
//   1. Both names — `ssc3.Chars.isUpperStart` and `UniAlphabet.isTypeNameStart` — answer the same,
//      over every code point in the BMP. That is not tautological: either could be re-implemented
//      or mis-delegated, and this is what would catch it.
//   2. WHERE WE DIFFER FROM JAVA, counted rather than assumed. `20-core-language.md` §3 asks for
//      exactly this: "a sweep over the whole code-point range on the jvm lane, comparing this
//      classifier against Java's, states exactly where we differ on purpose — which is the
//      difference between a decision and an accident."
import scalascript.uniml.UniAlphabet

@main def alphabetSweep(): Unit =
  var disagree = 0
  var firstBad = ""
  var vsJava = 0
  var cp = 0
  while cp < 0x10000 do
    val c = cp.toChar
    val mine = ssc3.Chars.isUpperStart(c)
    val theirs = UniAlphabet.isTypeNameStart(c)
    if mine != theirs then
      disagree += 1
      if firstBad.isEmpty then firstBad = f"U+$cp%04X v3=$mine uniml=$theirs"
    if mine != Character.isUpperCase(c) then vsJava += 1
    cp += 1
  println(s"bmp-disagreements: $disagree")
  if disagree > 0 then println(s"first: $firstBad")
  println(s"deliberate-differences-from-java: $vsJava")
