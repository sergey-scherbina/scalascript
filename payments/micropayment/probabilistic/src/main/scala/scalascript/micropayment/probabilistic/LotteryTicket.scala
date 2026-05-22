package scalascript.micropayment.probabilistic

/** A probabilistic payment ticket. The payer generates a random preimage,
 *  commits to SHA-256(preimage), and this ticket travels to the payee.
 *  The payee challenges with a server salt; win condition is checked after
 *  the payer reveals the preimage. */
case class LotteryTicket(
  commitment:    Array[Byte],   // SHA-256(preimage)
  claimedAmount: BigInt,
  payerAddress:  String,
  expiry:        Long,          // unix millis
)

/** Payer reveals the preimage after receiving the server salt.
 *  The payee can verify: SHA-256(reveal.preimage) == ticket.commitment
 *  and then check the win condition via LotteryMath.isWinner. */
case class LotteryReveal(
  preimage: Array[Byte],
  salt:     Array[Byte],        // server salt echoed back by payer
)
