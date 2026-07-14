package scalascript.control

/** A deep handler that removes `Handled` and leaves `Residual`. */
trait Handler[Handled <: Effect, Residual <: Effect, A, B]:
  def effect: EffectKey[Handled]

  def onReturn(value: A): Eff[Residual, B]

  def onOperation[X](
      operation: Operation[Handled, X],
      resumption: Resumption[X, Residual, B]
  ): Eff[Residual, B]

/**
 * Handle exactly one runtime effect key. Unmatched requests are forwarded with
 * their original multiplicity gate, and every accepted resume reinstalls this
 * handler around the suffix.
 */
def handle[Handled <: Effect, Residual <: Effect, A, B](
    body: Eff[Handled | Residual, A]
)(
    handler: Handler[Handled, Residual, A, B]
): Eff[Residual, B] =
  Eff.handleKernel[Handled, Residual, A, B](body, handler)
