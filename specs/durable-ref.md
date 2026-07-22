# DurableRef — inert references + a real `Restore` effect (save-run Part 3a)

> Part 3a of the durable-continuation chain. Adds the `DurableRef` durable-value
> shape ([`control-interoperability.md`](control-interoperability.md) §9.2) and turns
> the `Restore` effect row from a phantom capability marker into a **real** row with a
> `resolve` operation performed post-admission. Scala reference row; the JS mirror is a
> follow-on.
>
> Scope: a minimal inert reference (`providerId` + `opaqueReference`), a codec so refs
> live inside frames, the `Restore.resolve` operation, and a resolver handler. **Out of
> scope** (follow-on): schema/resolver-digest identity, audience/tenant/capability
> policy, secret/bearer codecs, expiry/revocation lifecycle, and `RunOutcomeUnknown`.

## 1. Why this exists

Everything durable so far is a *value* — encode it, ship it, decode an independent
copy. But real saved state often references external resources (a row id, an object
key, a channel handle) that must **not** be copied into the frame and must be
re-authorized on each run (§8.2). §9.2 models that as a `DurableRef`: inert reference
data that decodes purely (it never opens or contacts the resource) and resolves only
as an explicit typed effect **after admission**.

This also fills a real gap: `run` returns `Eff[Effects | Restore, R]`, but until now
nothing ever performed a `Restore` operation — the row was a phantom that
`Restore.admitLocally` discharged trivially. `DurableRef` gives `Restore` its first
real operation.

## 2. New public surface (`scalascript.control`)

```scala
/** Inert reference to external state; `A` is the resolved type (phantom in bytes). */
final class DurableRef[+A]:
  def providerId: String
  def opaqueReference: DurableBytes

object DurableRef:
  def of[A](providerId: String, opaqueReference: DurableBytes): DurableRef[A]
  def codec[A]: DurableCodec[DurableRef[A]]   // encodes providerId + opaqueReference

object Restore:                                // extended
  def resolve[A](ref: DurableRef[A]): Eff[Restore, A]
  trait Resolver:
    def resolve[A](ref: DurableRef[A]): A
  def withResolver[Fx <: Effect, R](resolver: Resolver)(
      body: Eff[Fx | Restore, R]
  ): Eff[Fx, R]
  // admitLocally stays: it now throws if the body performs a resolve (no provider).
```

## 3. Semantics

- **Inert decode.** `DurableRef.codec` encodes only the reference data (`providerId`,
  `opaqueReference`); decoding reconstructs the inert `DurableRef` and never resolves
  it (§9.2). A capsule whose frame contains a `DurableRef` decodes without contacting
  any resource.
- **Resolution is post-admission.** A saved machine that needs the referenced value
  performs `Restore.resolve(ref)`, which lands in the `Restore` row of `run`'s result.
  The call site discharges that row with `Restore.withResolver(resolver)`, which
  invokes the resolver once per resolve and resumes with the value. Each run resolves
  independently, so a resource deleted/moved/revoked between runs is a per-run
  resolution outcome, not capsule corruption.
- **No provider ⇒ typed failure.** `Restore.admitLocally` still discharges a run that
  performs no resolve; if such a run *does* perform a resolve, it throws (there is no
  bound provider). `withResolver` is the handler that actually resolves.
- A `DurableRef` is inert data: user code may construct one, but a forged ref simply
  fails (or is rejected by) resolution — it grants no capability by itself. So the
  constructor is public and unguarded, unlike the unforgeable `SavedContinuation`.

## 4. Behavior checklist

- [ ] `DurableRef.codec` round-trips the inert reference data; decoding never resolves.
- [ ] a `savable` (and a capsule `restore`) whose frame is a `DurableRef` runs by
      performing `Restore.resolve`, discharged by `withResolver` to the resolved value;
      multi-shot, each run resolves independently.
- [ ] `withResolver` invokes the resolver exactly once per `resolve` and reinstalls
      around the suffix (a run that resolves twice calls the resolver twice).
- [ ] `Restore.admitLocally` on a body that performs a `resolve` fails (no provider),
      while a body that performs none still returns.
- [ ] a `DurableRef` inside a decoded capsule is not resolved by `decode` (inert).
- [ ] ABI gate green; no forbidden runtime reference leaks from the new surface.

## 5. Follow-on (queued in SPRINT)

Schema/resolver-implementation-digest identity, audience/tenant/capability policy,
protected secret/bearer codecs, expiry/revocation lifecycle, `RunOutcomeUnknown` on
post-admission disconnect, and the JS-lane mirror of `DurableRef` + the real `Restore`
operation. A dynamic id→resume-point registry and canonical-key maps / nominal schema
remain separate queued slices.
