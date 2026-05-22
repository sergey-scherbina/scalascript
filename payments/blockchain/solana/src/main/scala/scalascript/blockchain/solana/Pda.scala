package scalascript.blockchain.solana

import java.security.MessageDigest

/** Solana Program-Derived Address derivation. A PDA is a SHA-256
 *  hash of `[seeds..., bump, programId, "ProgramDerivedAddress"]`
 *  that lands *off* the ed25519 curve — so no private key can ever
 *  produce a signature for it, but a program can sign on its
 *  behalf via runtime-issued seeds. */
private[solana] object Pda:

  /** Standard PDA tag appended before hashing. */
  private val Tag: Array[Byte] = "ProgramDerivedAddress".getBytes("UTF-8")

  /** Try every bump from 255 down to 0; return the first that
   *  yields an off-curve hash. The bump is stored alongside the
   *  derived address so callers (programs) can re-sign without
   *  re-searching. */
  def findProgramAddress(seeds: Seq[Array[Byte]], programId: Array[Byte]): (Array[Byte], Int) =
    require(programId.length == 32, "programId must be 32 bytes")
    require(seeds.size <= 16, s"max 16 seeds, got ${seeds.size}")
    require(seeds.forall(_.length <= 32), "each seed must be ≤ 32 bytes")
    var bump   = 255
    var result: Option[(Array[Byte], Int)] = None
    while bump >= 0 && result.isEmpty do
      tryCreateProgramAddress(seeds :+ Array(bump.toByte), programId) match
        case Some(addr) => result = Some((addr, bump))
        case None       => bump -= 1
    result.getOrElse(
      throw new RuntimeException("unable to find PDA bump for the given seeds")
    )

  /** Hash the seeds + programId; return Some(addr) if the result
   *  lands off-curve, None otherwise. */
  private def tryCreateProgramAddress(
    seeds:     Seq[Array[Byte]],
    programId: Array[Byte],
  ): Option[Array[Byte]] =
    val md = MessageDigest.getInstance("SHA-256")
    seeds.foreach(md.update)
    md.update(programId)
    md.update(Tag)
    val hash = md.digest()
    if Ed25519Curve.isOnCurve(hash) then None else Some(hash)
