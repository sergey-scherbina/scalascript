package scalascript.payments.zengin

/** Validates that an account holder name contains only characters allowed in
 *  the Zengin 21 accountName field:
 *
 *  - Half-width Katakana (U+FF66–U+FF9F, range ｦ–ﾟ)
 *  - ASCII space (U+0020)
 *  - Hyphen/dash (U+002D '-')
 *
 *  Full-width katakana (U+30A0–U+30FF), kanji (CJK), hiragana, and ASCII
 *  letters are all rejected.  The Zengin scheme requires the name in the
 *  half-width (半角) kana form as stored in bank records.
 *
 *  Note: transliteration from full-width or romaji is the caller's responsibility;
 *  this object only validates.
 */
object KatakanaValidator:

  /** Half-width Katakana block U+FF66–U+FF9F. */
  private val HalfWidthKanaLow  = 'ｦ'
  private val HalfWidthKanaHigh = 'ﾟ'

  /** Returns `Right(name)` if all characters are valid, or
   *  `Left(invalidChars)` listing the distinct offending characters. */
  def validate(name: String): Either[Set[Char], String] =
    val invalid = name.toSet.filter(c => !isAllowed(c))
    if invalid.isEmpty then Right(name)
    else Left(invalid)

  /** Returns `true` iff the character is allowed in a Zengin kana name field. */
  def isAllowed(c: Char): Boolean =
    c == ' ' || c == '-' || (c >= HalfWidthKanaLow && c <= HalfWidthKanaHigh)

  /** Convenience: returns `true` iff every character in `name` is allowed. */
  def isValid(name: String): Boolean =
    name.forall(isAllowed)
