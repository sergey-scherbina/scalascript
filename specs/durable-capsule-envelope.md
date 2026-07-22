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

/** Typed rejection when a capsule cannot be admitted. `kind` is a stable category:
  * FormatVersion / ResumePointMismatch / FrameTampered (integrity), TamperedCapsule /
  * ResourceLimit (security envelope), CodecMismatch / AbiMismatch / MissingDependency. */
final class CapsuleRejected(val kind: String, message: String) extends RuntimeException
```

## 3. Envelope format

`DurableCapsule` is encoded with the Part 1 combinators (so it inherits the
canonical/deterministic/bounded guarantees):

```
capsule := formatVersion:int
        || resumePointId:string
        || codecAbiVersion:int         // pinned value/frame codec ABI (§10, §12)
        || artifactAbiId:string        // pinned ExactArtifact/control identity
        || requiredDependencies:list(string)  // sorted target/toolchain/plugin ids
        || frame:bytes                 // the DurableCodec[S] encoding of the state
        || frameDigest:bytes           // SHA-256, domain-separated
        || audience:string             // security envelope (§11.1 step 2, §12)
        || tenant:string               //   the runner this capsule is addressed to
        || requiredBudget:long         //   the execution/decode budget it demands
        || signature:bytes             // keyed HMAC-SHA256 over the body (empty = unsigned)
```

`frameDigest = SHA-256("ssc-frame-v1\0" || frameBytes)` (§10) covers the frame only, so
adding the ABI manifest does not change the digest. The domain-separation prefix keeps
this hash distinct from any other SHA-256 use. The current `formatVersion` is `3`. The
`codecAbiVersion`, `artifactAbiId`, and `requiredDependencies` are the `ArtifactProfile`
a resume point pins at `freeze`; `audience`, `tenant`, `requiredBudget`, and `signature`
are the `AdmissionPolicy` security envelope (see §4). The signature is
`HMAC-SHA256(signingKey, "ssc-capsule-sig-v1\0" || canonical(capsule with an empty
signature slot))`; the `signingKey` is held by the resume point and never travels in the
capsule. An unsigned (trusted in-process) capsule carries an empty signature.

## 4. Semantics

- `freeze(state)`: `frame = codec.encode(state)`; capsule = `(version=1, id,
  frame, digest(frame))`. Pure; cannot fail for a valid codec.
- `capsule.encode()` / `DurableCapsule.decode(bytes)`: canonical bytes for transport;
  decode is **inert** (§9.2) — it parses the envelope without checking the digest or
  touching a resume program, so a hostile capsule decodes to inert data, never runs.
- `restore(capsule, availableBudget)`: **admission** — verify, then rebind. Rejects with
  `CapsuleRejected` when `formatVersion != 3`, `capsule.resumePointId != this.id`, or
  the recomputed `frameDigest` differs (tamper). Then it runs the §11.1 step-2 security
  admission (before the codec/ABI/dependency checks): a missing/forged/tampered keyed
  signature or a mismatched `audience`/`tenant` rejects as `TamperedCapsule`, and a
  `requiredBudget` exceeding `availableBudget` rejects as `ResourceLimit`. On success it decodes the frame with
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
- [ ] a signed capsule presented to a runner with the wrong key, audience, or tenant is
      rejected `TamperedCapsule`; one whose `requiredBudget` exceeds the runner's
      `availableBudget` is rejected `ResourceLimit` — both proven non-vacuously.
- [ ] `DurableCapsule.decode` is bounded/exact (truncated or trailing bytes rejected)
      and does not run or resolve anything.
- [ ] `encode` is deterministic; equal capsules ⇒ equal bytes.
- [ ] ABI gate green; no forbidden runtime reference leaks (`MessageDigest` stays
      internal, never in a public signature).

## 5a. Cross-lane golden capsule

Both host lanes are implemented (Scala `v2/host/scala/control`, JS `v2/host/js/control`)
and both assert one shared golden capsule to prove byte identity of the *whole* envelope,
including the SHA-256 digest — Java `MessageDigest` on one lane, a self-contained sync
SHA-256 on the other. For resume point `"cell"` freezing the state `100`
(`int` frame `00000064`):

```
000000010000000463656c6c00000004000000640000002 0
4b458482422640f4fb818274ec2b4f3d1de3a487c25f991d751e483fdc0aea9b
```

(`version=1 | id="cell" | frame=int(100) | digest=SHA-256("ssc-frame-v1\0" ‖ 00000064)`,
concatenated with no separators). The embedded 32-byte digest was computed independently
by Node's `crypto`, so a match on both lanes cross-checks each SHA-256 implementation as
well as the envelope layout. Changing the format means updating both golden tables.

## 6. Follow-on (queued in SPRINT)

Landed since Part 2: the `DurableRef` (§9.2) post-admission resolve effect, the
`ArtifactProfile` ABI manifest (codec/artifact/dependency admission, format v2), and the
`AdmissionPolicy` signature + audience/tenant + quota envelope (format v3, this slice).
Still queued: the `Portable` CoreIR resume-program payload; a broader capability policy;
a dynamic id→resume-point registry for fully-decoupled restore; lifecycle/expiry and
revocation; `RunOutcomeUnknown` on post-admission disconnect; and the Rust/Swift lane
mirrors (the JS-lane codec + envelope + admission mirrors all landed). The
Portable/ExactArtifact runners consume this envelope.
