// Do v3's lexical alphabet and UniML's agree, on EVERY code point in the BMP?
//
// They are two copies of the same 606 ranges, and they must be, for reasons that pull in opposite
// directions: v3's kernel may not depend on UniML (invariant I-1) and neither may call the host
// (`20-core-language.md` §3 — route an alphabet through the host and the same source lexes
// differently on the JVM, on JS and on the v2 VM). Two copies with no check is the duplicated-helper
// shape this repository keeps paying for; two copies with this check is a decision.
//
// It also prints where BOTH deliberately differ from Java, which §3 asks for in as many words:
// "a sweep over the whole code-point range on the jvm lane, comparing this classifier against
// Java's, states exactly where we differ on purpose — which is the difference between a decision
// and an accident."
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
