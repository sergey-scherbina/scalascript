# Durable capsule envelope + resume points (save-run Part 2)

> Part 2 of `saved-continuation-format`. Builds on the canonical frame codec
> ([`durable-frame-codec.md`](durable-frame-codec.md), Part 1): wraps an encoded
> frame in a **versioned, digest-verified transport envelope** bound to a named
> **resume point**, and adds `freeze`/`restore` so a saved continuation survives a
> process/machine boundary in the host reference row. Governed by
> [`control-interoperability.md`](control-interoperability.md) §8 (`save`/`run`),
> §9.2 (`DurableRef` — inert decode), and §10 (capsule).
>
> Scope of this slice (reference row, `ExactArtifact` code mode, in-process):
> the envelope + `frameDigest` + resume-point binding + verification + the
> freeze→transport→restore→run round-trip. **Out of scope** (follow-on): the
> `Portable` CoreIR resume-program payload, a signature/tenant/audience layer,
> `DurableRef` resolution, a dynamic id→resume-point registry, the effect
> journal / lifecycle policy, and the JS-lane mirror of the envelope.

## 1. Why this exists

The Part 1 codec turns a frame into canonical bytes but does not say *which* resume
entry those bytes belong to, nor prove they were not tampered with. A durable
capsule crosses a boundary; the receiver must (a) find the resume program and (b)
reject a corrupted or mismatched frame before running anything. Because the host
reference model's resume program is a JVM closure (a `ResumeStateMachine`), it
cannot be serialized — so this slice uses the **`ExactArtifact`** code mode from
§8.3: the resume program is bound by a stable `resumePointId`, and only the frame
travels as bytes. (The `Portable` mode, where the resume program itself is a closed
CoreIR program, is a later slice.)

## 2. New public surface (`scalascript.control`)

```scala
/** A named binding of a resume program + its frame codec. The id is a stable
  * label carried in every capsule this point produces and checked on restore. */
final class ResumePoint[S, A, Fx <: Effect, R]:
  val id: String
  def savable(state: S): Continuation[A, Fx, R]         // in-process (Part 1), bound
  def freeze(state: S): DurableCapsule                  // state -> durable capsule
  def restore(capsule: DurableCapsule): SavedContinuation.Aux[A, Fx, R]

object ResumePoint:
  def define[S, A, Fx <: Effect, R](
      id: String,
      machine: ResumeStateMachine[S, A, Fx, R],
      codec: DurableCodec[S]
  ): ResumePoint[S, A, Fx, R]

/** The versioned transport envelope. Decoding is inert (no verification, no resume
  * program contact); verification happens at `restore`. */
final class DurableCapsule:
  def formatVersion: Int
  def resumePointId: String
  def encode(): DurableBytes

object DurableCapsule:
  def decode(bytes: DurableBytes): DurableCapsule   // pure, bounded, does not resolve

/** Typed rejection when a capsule cannot be admitted (version/id/digest). */
final class CapsuleRejected(reason: String) extends RuntimeException
```

## 3. Envelope format

`DurableCapsule` is encoded with the Part 1 combinators (so it inherits the
canonical/deterministic/bounded guarantees):

```
capsule := formatVersion:int
        || resumePointId:string
        || frame:bytes            // the DurableCodec[S] encoding of the state
        || frameDigest:bytes      // SHA-256, domain-separated
```

`frameDigest = SHA-256("ssc-frame-v1\0" || frameBytes)` (§10). The domain-separation
prefix keeps this hash distinct from any other SHA-256 use. The current
`formatVersion` is `1`.

## 4. Semantics

- `freeze(state)`: `frame = codec.encode(state)`; capsule = `(version=1, id,
  frame, digest(frame))`. Pure; cannot fail for a valid codec.
- `capsule.encode()` / `DurableCapsule.decode(bytes)`: canonical bytes for transport;
  decode is **inert** (§9.2) — it parses the envelope without checking the digest or
  touching a resume program, so a hostile capsule decodes to inert data, never runs.
- `restore(capsule)`: **admission** — verify, then rebind. Rejects with
  `CapsuleRejected` when `formatVersion != 1`, `capsule.resumePointId != this.id`, or
  the recomputed `frameDigest` differs (tamper). On success it decodes the frame with
  the registered codec and returns a reusable `SavedContinuation` bound to the
  registered machine — identical in behavior to a locally-saved one: multi-shot, each
  run reconstructs an independent frame, no prefix replay (§8.1/§8.2).
- The frame codec is `ExactArtifact`-bound: the machine is *not* in the bytes; a
  capsule can only be restored by a resume point that already holds the matching
  program. Cross-point restore is rejected by the id check.

## 5. Behavior checklist

- [ ] freeze → `encode` → `decode` → `restore` → `run` yields the same result as an
      in-process save/run of the same state; `run` is multi-shot with no prefix replay.
- [ ] a mutable field decoded per run stays isolated across restored runs (§8.2).
- [ ] tampering with any byte of the frame makes `restore` reject
      (`CapsuleRejected`, digest mismatch) — proven non-vacuously (untampered restores).
- [ ] restoring on a resume point with a different `id` is rejected.
- [ ] an unsupported `formatVersion` is rejected.
- [ ] `DurableCapsule.decode` is bounded/exact (truncated or trailing bytes rejected)
      and does not run or resolve anything.
- [ ] `encode` is deterministic; equal capsules ⇒ equal bytes.
- [ ] ABI gate green; no forbidden runtime reference leaks (`MessageDigest` stays
      internal, never in a public signature).

## 6. Follow-on (queued in SPRINT)

`Portable` CoreIR resume-program payload; signature + audience/tenant + capability
policy; `DurableRef` (§9.2) resolution as a post-admission effect; a dynamic
id→resume-point registry for fully-decoupled restore; lifecycle/expiry;
`RunOutcomeUnknown` on post-admission disconnect; and the JS-lane mirror of the
codec + envelope. The Portable/ExactArtifact runners consume this envelope.
