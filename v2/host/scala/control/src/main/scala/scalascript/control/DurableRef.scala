package scalascript.control

/**
 * An inert reference to external state (`specs/durable-ref.md`,
 * control-interoperability §9.2). It carries only a `providerId` and an opaque,
 * provider-specific reference; `A` is the resolved type and is phantom in the
 * bytes. Decoding never opens or contacts the resource — resolution is the
 * explicit post-admission `Restore.resolve` effect. A forged reference simply
 * fails resolution, so the constructor is public and unguarded.
 */
final class DurableRef[+A] private[control] (
    val providerId: String,
    val opaqueReference: DurableBytes
)

object DurableRef:
  def of[A](providerId: String, opaqueReference: DurableBytes): DurableRef[A] =
    if providerId == null then throw new NullPointerException("provider id")
    if opaqueReference == null then
      throw new NullPointerException("opaque reference")
    new DurableRef[A](providerId, opaqueReference)

  /** Codec for embedding an inert reference inside a durable frame. */
  def codec[A]: DurableCodec[DurableRef[A]] =
    DurableCodec.imap(
      DurableCodec.pair(DurableCodec.string, DurableCodec.bytes)
    ) { case (providerId, opaqueReference) =>
      new DurableRef[A](providerId, opaqueReference)
    }(ref => (ref.providerId, ref.opaqueReference))
