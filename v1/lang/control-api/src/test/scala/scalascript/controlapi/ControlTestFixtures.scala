package scalascript.controlapi

import scalascript.control.*

private[controlapi] object ControlTestFixtures:
  object Choice extends Effect:
    val key: EffectKey[Choice.type] =
      EffectKey.named(EffectId("test.choice"), this)

  final case class Choose(values: Vector[Int])
      extends Operation[Choice.type, Int]:
    val effect: EffectKey[Choice.type] = Choice.key
    val id: OperationId = OperationId(effect.id, "choose")

  object ReadEffect extends Effect:
    val key: EffectKey[ReadEffect.type] =
      EffectKey.named(EffectId("test.read"), this)

  case object Read extends Operation[ReadEffect.type, Int]:
    val effect: EffectKey[ReadEffect.type] = ReadEffect.key
    val id: OperationId = OperationId(effect.id, "read")

  object TraceEffect extends Effect:
    val key: EffectKey[TraceEffect.type] =
      EffectKey.named(EffectId("test.trace"), this)

  final case class Trace(message: String)
      extends Operation[TraceEffect.type, Unit]:
    val effect: EffectKey[TraceEffect.type] = TraceEffect.key
    val id: OperationId = OperationId(effect.id, "trace")

  object TickEffect extends Effect:
    val key: EffectKey[TickEffect.type] =
      EffectKey.named(EffectId("test.tick"), this)

  case object Tick extends Operation[TickEffect.type, Unit]:
    val effect: EffectKey[TickEffect.type] = TickEffect.key
    val id: OperationId = OperationId(effect.id, "tick")

  object OnceEffect extends Effect:
    val key: EffectKey[OnceEffect.type] =
      EffectKey.named(EffectId("test.once"), this)

  case object TakeOnce extends Operation[OnceEffect.type, Int]:
    val effect: EffectKey[OnceEffect.type] = OnceEffect.key
    val id: OperationId = OperationId(effect.id, "take")
    override val multiplicity: ResumeMultiplicity =
      ResumeMultiplicity.OneShot

  object PrimaryOwner extends Effect:
    val descriptor: EffectId = EffectId("test.owner")
    val key: EffectKey[PrimaryOwner.type] =
      EffectKey.named(descriptor, this)
    val equivalentKey: EffectKey[PrimaryOwner.type] =
      EffectKey.named(descriptor, this)
    val conflictingKey: EffectKey[PrimaryOwner.type] =
      EffectKey.named(EffectId("test.owner.conflict"), this)

  object SecondaryOwner extends Effect:
    val key: EffectKey[SecondaryOwner.type] =
      EffectKey.named(PrimaryOwner.descriptor, this)

  final case class OwnerRequest[Fx <: Effect](
      effect: EffectKey[Fx],
      operationName: String
  ) extends Operation[Fx, Int]:
    val id: OperationId = OperationId(effect.id, operationName)

  object SnapshotEffect extends Effect:
    val key: EffectKey[SnapshotEffect.type] =
      EffectKey.named(EffectId("test.snapshot"), this)

  final class ChangingKeyOperation
      extends Operation[SnapshotEffect.type, Int]:
    private var reads = 0
    val id: OperationId = OperationId(SnapshotEffect.key.id, "changing")

    def effect: EffectKey[SnapshotEffect.type] =
      reads += 1
      if reads == 1 then SnapshotEffect.key else null

    def effectReads: Int = reads

  object NullKeyOperation extends Operation[SnapshotEffect.type, Int]:
    def effect: EffectKey[SnapshotEffect.type] = null
    val id: OperationId = OperationId(SnapshotEffect.key.id, "null")

  object WrongDescriptorOperation
      extends Operation[SnapshotEffect.type, Int]:
    val effect: EffectKey[SnapshotEffect.type] = SnapshotEffect.key
    val id: OperationId =
      OperationId(EffectId("test.wrong-descriptor"), "wrong")

  def resumeReusable[A, Fx <: Effect, R](
      resumption: Resumption[A, Fx, R],
      value: A
  ): Eff[Fx, R] =
    resumption match
      case Resumption.Reusable(continuation) => continuation.resume(value)
      case Resumption.OneShot(_) =>
        throw new AssertionError("expected a reusable resumption")
