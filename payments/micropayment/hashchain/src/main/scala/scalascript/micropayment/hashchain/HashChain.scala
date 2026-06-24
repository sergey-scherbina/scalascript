package scalascript.micropayment.hashchain

import scalascript.crypto.Sha256

/** PayWord-style hash chains for off-chain micropayments.
 *
 *  A chain of length `n` is `wₙ = SHA256ⁿ(seed)`. The payer commits to the **tip** `wₙ` at channel open; to have
 *  paid `u` units it reveals `w₍ₙ₋ᵤ₎ = SHA256ⁿ⁻ᵘ(seed)`, and anyone can check `SHA256ᵘ(reveal) == tip`. So each
 *  micropayment is a single 32-byte preimage that the payee verifies with one (or a few) cheap hashes — no
 *  per-payment signature, no round-trip. Monotonic: a deeper reveal supersedes a shallower one, and the deepest
 *  reveal alone proves the cumulative amount (ideal for on-chain redemption). Uses the portable [[Sha256]]. */
object HashChain:

  /** `SHA256` applied `times` times to `data` (`times == 0` returns `data` unchanged). */
  def hashTimes(data: Array[Byte], times: Int): Array[Byte] =
    require(times >= 0, "times must be >= 0")
    var h = data
    var i = 0
    while i < times do { h = Sha256.digest(h); i += 1 }
    h

  /** The commitment tip `wₙ = SHA256ⁿ(seed)`. */
  def tip(seed: Array[Byte], length: Int): Array[Byte] =
    require(length >= 1, "chain length must be >= 1")
    hashTimes(seed, length)

  /** The preimage revealed once `unitsPaid` units have been paid: `w₍ₙ₋ᵤₙᵢₜₛ₎`. */
  def preimage(seed: Array[Byte], length: Int, unitsPaid: Int): Array[Byte] =
    require(unitsPaid >= 0 && unitsPaid <= length, s"unitsPaid must be in [0, $length], got $unitsPaid")
    hashTimes(seed, length - unitsPaid)

  /** Verify a revealed preimage proves exactly `unitsPaid` units against the committed `tip`:
   *  `SHA256ᵘ(reveal) == tip`. */
  def verify(reveal: Array[Byte], unitsPaid: Int, tip: Array[Byte]): Boolean =
    unitsPaid >= 0 && java.util.Arrays.equals(hashTimes(reveal, unitsPaid), tip)

  /** Cheap incremental check: a new reveal `step` units deeper than `prevReveal` is valid iff
   *  `SHA256ˢᵗᵉᵖ(reveal) == prevReveal`. Lets the payee verify each payment with just `step` hashes instead of
   *  re-hashing from the tip. */
  def verifyStep(reveal: Array[Byte], step: Int, prevReveal: Array[Byte]): Boolean =
    step >= 1 && java.util.Arrays.equals(hashTimes(reveal, step), prevReveal)
