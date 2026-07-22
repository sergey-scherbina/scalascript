declare const effectBrand: unique symbol
declare const effectKeyBrand: unique symbol
declare const operationResultBrand: unique symbol
declare const effBrand: unique symbol
declare const continuationBrand: unique symbol
declare const oneShotContinuationBrand: unique symbol
declare const savedContinuationBrand: unique symbol
declare const promptScopeBrand: unique symbol
declare const promptBrand: unique symbol
declare const controlBrand: unique symbol
declare const saveEffectOwner: unique symbol
declare const restoreEffectOwner: unique symbol
declare const controlEffectOwner: unique symbol

export interface Effect<
  Id extends string = string,
  Owner extends symbol = symbol
> {
  readonly [effectBrand]: Readonly<{
    descriptor: Id
    owner: Owner
  }>
}

export interface EffectId {
  readonly value: string
}

export interface OperationId {
  readonly effect: EffectId
  readonly name: string
}

export type ResumeMultiplicity = "Reusable" | "OneShot"

export const ResumeMultiplicity: Readonly<{
  Reusable: "Reusable"
  OneShot: "OneShot"
}>

export type ResumeRejected = Readonly<{
  kind: "AlreadyResumed"
  operation: OperationId
}>

export const ResumeRejected: Readonly<{
  AlreadyResumed(operation: OperationId): ResumeRejected
}>

export interface EffectKey<Fx extends Effect> {
  readonly id: EffectId
  readonly [effectKeyBrand]: (effect: Fx) => Fx
  operation<A, Args extends readonly unknown[] = readonly []>(
    name: string,
    options?: Readonly<{ multiplicity?: ResumeMultiplicity }>
  ): OperationFactory<Fx, A, Args>
}

type IsUnion<T, Whole = T> =
  T extends unknown ? ([Whole] extends [T] ? false : true) : never

type SingleUniqueSymbol<T extends symbol> =
  [T] extends [never]
    ? never
    : symbol extends T
      ? never
      : true extends IsUnion<T>
        ? never
        : T

export function defineEffect<
  const Id extends string,
  const Owner extends symbol
>(
  id: Id,
  owner: SingleUniqueSymbol<Owner>
): EffectKey<Effect<Id, Owner>>

export interface Operation<
  Fx extends Effect,
  A,
  Args extends readonly unknown[] = readonly unknown[]
> {
  readonly effect: EffectKey<Fx>
  readonly id: OperationId
  readonly multiplicity: ResumeMultiplicity
  readonly args: Args
  readonly [operationResultBrand]: () => A
}

export interface OperationFactory<
  Fx extends Effect,
  A,
  Args extends readonly unknown[] = readonly []
> {
  (...args: Args): Operation<Fx, A, Args>
  readonly effect: EffectKey<Fx>
  readonly id: OperationId
  readonly multiplicity: ResumeMultiplicity
  is(operation: Operation<Fx, unknown>): operation is Operation<Fx, A, Args>
}

export interface Eff<Fx extends Effect, A> {
  readonly [effBrand]: Readonly<{
    effects: () => Fx
    value: () => A
  }>
  flatMap<Fx2 extends Effect, B>(
    next: (value: A) => Eff<Fx2, B>
  ): Eff<Fx | Fx2, B>
  map<B>(f: (value: A) => B): Eff<Fx, B>
}

export const Eff: Readonly<{
  pure<A>(value: A): Eff<never, A>
  defer<Fx extends Effect, A>(body: () => Eff<Fx, A>): Eff<Fx, A>
  runPure<A>(body: Eff<never, A>): A
}>

export function perform<
  Fx extends Effect,
  A,
  Args extends readonly unknown[]
>(operation: Operation<Fx, A, Args>): Eff<Fx, A>

export interface Handler<
  Handled extends Effect,
  Residual extends Effect,
  A,
  B
> {
  readonly effect: EffectKey<Handled>
  onReturn(value: A): Eff<Residual, B>
  onOperation<X, Args extends readonly unknown[]>(
    operation: Operation<Handled, X, Args>,
    resumption: Resumption<X, Residual, B>
  ): Eff<Residual, B>
}

export function handle<
  Handled extends Effect,
  Residual extends Effect,
  A,
  B
>(
  body: Eff<Handled | Residual, A>,
  handler: Handler<Handled, Residual, A, B>
): Eff<Residual, B>

export interface Continuation<A, Fx extends Effect, R> {
  readonly [continuationBrand]: Readonly<{
    input: (value: A) => void
    effects: () => Fx
    result: () => R
  }>
  resume(value: A): Eff<Fx, R>
  save(): Eff<Save, SavedContinuation<A, Fx, R>>
}

export interface OneShotContinuation<A, Fx extends Effect, R> {
  readonly [oneShotContinuationBrand]: Readonly<{
    input: (value: A) => void
    effects: () => Fx
    result: () => R
  }>
  tryResume(value: A): ResumeAttempt<Fx, R>
}

export type ResumeAttempt<Fx extends Effect, R> =
  | Readonly<{ ok: true, computation: Eff<Fx, R> }>
  | Readonly<{ ok: false, rejection: ResumeRejected }>

export type Resumption<A, Fx extends Effect, R> =
  | Readonly<{
      kind: "Reusable"
      continuation: Continuation<A, Fx, R>
    }>
  | Readonly<{
      kind: "OneShot"
      continuation: OneShotContinuation<A, Fx, R>
    }>

export type CaptureFailure =
  | Readonly<{ kind: "UnmanagedCapture", site: string }>
  | Readonly<{ kind: "CaptureBarrier", site: string, detail: string }>
  | Readonly<{ kind: "OneShotSource", site: string }>
  | Readonly<{ kind: "MissingCodec", site: string, typeId: string }>
  | Readonly<{ kind: "UnsupportedGraph", site: string, detail: string }>

export const CaptureFailure: Readonly<{
  UnmanagedCapture(site: string): CaptureFailure
  CaptureBarrier(site: string, detail: string): CaptureFailure
  OneShotSource(site: string): CaptureFailure
  MissingCodec(site: string, typeId: string): CaptureFailure
  UnsupportedGraph(site: string, detail: string): CaptureFailure
}>

export interface Save
  extends Effect<"scalascript.control.Save", typeof saveEffectOwner> {}

export const Save: Readonly<{
  key: EffectKey<Save>
  Rejected: OperationFactory<Save, never, readonly [CaptureFailure]>
}>

export interface Restore
  extends Effect<"scalascript.control.Restore", typeof restoreEffectOwner> {}

/** An inert reference to external state, resolved only post-admission (§9.2). */
export class DurableRef<out A> {
  private constructor()
  readonly providerId: string
  readonly opaqueReference: DurableBytes
  static of<A>(providerId: string, opaqueReference: DurableBytes): DurableRef<A>
  static codec<A>(): DurableCodec<DurableRef<A>>
}

export interface Resolver {
  resolve<A>(ref: DurableRef<A>): A
}

export const Restore: Readonly<{
  key: EffectKey<Restore>
  Resolve: OperationFactory<Restore, unknown, readonly [DurableRef<unknown>]>
  resolve<A>(ref: DurableRef<A>): Eff<Restore, A>
  withResolver<Fx extends Effect, R>(
    resolver: Resolver,
    body: Eff<Fx | Restore, R>
  ): Eff<Fx, R>
  admitLocally<Fx extends Effect, R>(body: Eff<Fx | Restore, R>): Eff<Fx, R>
}>

export interface SavedContinuation<A, Fx extends Effect, R> {
  readonly [savedContinuationBrand]: Readonly<{
    input: (value: A) => void
    effects: () => Fx
    result: () => R
  }>
  run(value: A): Eff<Fx | Restore, R>
}

export interface DurableValue<S> {
  snapshot(value: S): S
  /**
   * If present, the frame this evidence describes cannot cross the save boundary
   * (a raw foreign value with no durable codec, the §8.3 FrameGate); `save()`
   * rejects with this `CaptureFailure` instead of producing a `SavedContinuation`.
   */
  readonly captureBarrier?: CaptureFailure
}

export const DurableValue: Readonly<{
  immutable<S>(): DurableValue<S>
  copying<S>(copy: (value: S) => S): DurableValue<S>
  unsavable<S>(failure: CaptureFailure): DurableValue<S>
}>

export class DurableDecodeError extends Error {}

export class DurableBytes {
  private constructor()
  readonly length: number
  toArray(): Uint8Array
  toHex(): string
  static fromArray(source: ArrayLike<number>): DurableBytes
}

/** A canonical, deterministic, bounded byte codec that is also a DurableValue. */
export interface DurableCodec<S> extends DurableValue<S> {
  encode(value: S): DurableBytes
  decode(bytes: DurableBytes): S
}

export type DurableEither<A, B> = { left: A } | { right: B }

export const DurableCodec: Readonly<{
  unit: DurableCodec<void>
  boolean: DurableCodec<boolean>
  int: DurableCodec<number>
  long: DurableCodec<bigint>
  bigInt: DurableCodec<bigint>
  double: DurableCodec<number>
  string: DurableCodec<string>
  bytes: DurableCodec<DurableBytes>
  pair<A, B>(left: DurableCodec<A>, right: DurableCodec<B>): DurableCodec<[A, B]>
  either<A, B>(
    left: DurableCodec<A>,
    right: DurableCodec<B>
  ): DurableCodec<DurableEither<A, B>>
  list<A>(element: DurableCodec<A>): DurableCodec<A[]>
  map<K, V>(keyCodec: DurableCodec<K>, valueCodec: DurableCodec<V>): DurableCodec<Map<K, V>>
  imap<A, B>(codec: DurableCodec<A>, to: (value: A) => B, from: (value: B) => A): DurableCodec<B>
  schema<S>(schemaId: string, version: number, codec: DurableCodec<S>): DurableCodec<S>
  left<A, B>(value: A): DurableEither<A, B>
  right<A, B>(value: B): DurableEither<A, B>
}>

export class CapsuleRejected extends Error {
  readonly kind:
    | "FormatVersion"
    | "ResumePointMismatch"
    | "FrameTampered"
    | "TamperedCapsule"
    | "ResourceLimit"
    | "CodecMismatch"
    | "AbiMismatch"
    | "MissingDependency"
}

/** The pinned ABI/dependency identity a capsule carries for admission (§10, §12). */
export interface ArtifactProfile {
  readonly codecAbiVersion: number
  readonly artifactAbiId: string
  readonly requiredDependencies: ReadonlySet<string>
}

export const ArtifactProfile: Readonly<{
  default: ArtifactProfile
  of(
    codecAbiVersion: number,
    artifactAbiId: string,
    requiredDependencies: ReadonlySet<string>
  ): ArtifactProfile
}>

/**
 * The admission-security policy a resume point binds and enforces (§11.1 step 2, §12).
 * A non-empty `signingKey` signs the capsule (HMAC-SHA256) and makes restore reject a
 * missing/forged/tampered signature as `TamperedCapsule`; `audience`/`tenant` bind the
 * capsule to a runner; `requiredBudget` is the demanded budget (`ResourceLimit` when it
 * exceeds the runner's available budget). The key never travels in the capsule.
 */
export interface AdmissionPolicy {
  readonly audience: string
  readonly tenant: string
  readonly requiredBudget: bigint
  readonly signingKey: DurableBytes
}

export const AdmissionPolicy: Readonly<{
  open: AdmissionPolicy
  of(
    audience: string,
    tenant: string,
    requiredBudget: bigint | number,
    signingKey: DurableBytes
  ): AdmissionPolicy
}>

/** A versioned, digest-verified durable capsule bound to a named resume point. */
export class DurableCapsule {
  private constructor()
  readonly formatVersion: number
  readonly resumePointId: string
  encode(): DurableBytes
  static decode(bytes: DurableBytes): DurableCapsule
}

/** A named binding of a resume program and its frame codec. */
export class ResumePoint<S, A, Fx extends Effect, R> {
  private constructor()
  readonly id: string
  readonly requiredResolvers: ReadonlySet<string>
  readonly profile: ArtifactProfile
  savable(state: S): Continuation<A, Fx, R>
  freeze(state: S): DurableCapsule
  restore(
    capsule: DurableCapsule,
    availableResolvers?: ReadonlySet<string>,
    availableDependencies?: ReadonlySet<string>,
    availableBudget?: bigint
  ): SavedContinuation<A, Fx, R>
  static define<S, A, Fx extends Effect, R>(
    id: string,
    machine: ResumeStateMachine<S, A, Fx, R>,
    codec: DurableCodec<S>,
    requiredResolvers?: ReadonlySet<string>,
    profile?: ArtifactProfile,
    policy?: AdmissionPolicy
  ): ResumePoint<S, A, Fx, R>
}

export interface PromptScope {
  readonly [promptScopeBrand]: never
}

export type PromptKeyOf<T> =
  T extends Prompt<infer P, infer _R> ? P : never

export interface Control<P extends PromptScope>
  extends Effect<"scalascript.control.Control", typeof controlEffectOwner> {
  readonly [controlBrand]: (prompt: P) => P
}

export interface Prompt<P extends PromptScope, R> {
  readonly [promptBrand]: Readonly<{
    prompt: (value: P) => P
    answer: (value: R) => R
  }>
}

export type ShiftBody<
  P extends PromptScope,
  A,
  Fx extends Effect,
  R
> = <Residual extends Effect>(
  continuation: Continuation<A, Fx | Residual, R>
) => Eff<Fx | Residual | Control<P>, R>

export function freshPrompt<R, A>(
  body: <P extends PromptScope>(prompt: Prompt<P, R>) => A
): A

export function reset<P extends PromptScope, R, Fx extends Effect>(
  prompt: Prompt<P, R>,
  body: () => Eff<Fx, R>
): Eff<Exclude<Fx, Control<P>>, R>

export function shift<
  P extends PromptScope,
  R,
  A,
  Fx extends Effect = never
>(
  prompt: Prompt<P, R>,
  body: ShiftBody<P, A, Fx, R>
): Eff<Fx | Control<P>, A>

export type MachineStep<S, Fx extends Effect, A> =
  | Readonly<{ kind: "Continue", next: S }>
  | Readonly<{ kind: "Evaluate", next: Eff<Fx, S> }>
  | Readonly<{ kind: "Done", value: A }>

export const MachineStep: Readonly<{
  Continue<S>(next: S): MachineStep<S, never, never>
  Evaluate<S, Fx extends Effect>(
    next: Eff<Fx, S>
  ): MachineStep<S, Fx, never>
  Done<A>(value: A): MachineStep<never, never, A>
}>

export interface StateMachine<S, Fx extends Effect, A> {
  step(state: S): MachineStep<S, Fx, A>
}

export const StateMachine: Readonly<{
  run<S, Fx extends Effect, A>(
    initial: S,
    machine: StateMachine<S, Fx, A>
  ): Eff<Fx, A>
}>

export interface ResumeStateMachine<S, A, Fx extends Effect, R> {
  resume(state: S, input: A): Eff<Fx, R>
}

export const Continuation: Readonly<{
  local<S, A, Fx extends Effect, R>(
    state: S,
    machine: ResumeStateMachine<S, A, Fx, R>
  ): Continuation<A, Fx, R>
  savable<S, A, Fx extends Effect, R>(
    state: S,
    machine: ResumeStateMachine<S, A, Fx, R>,
    codec: DurableValue<S>
  ): Continuation<A, Fx, R>
}>
