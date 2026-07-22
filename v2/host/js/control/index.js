const internalAuthority = Object.freeze(Object.create(null))
const effectKeyStates = new WeakMap()
const operationStates = new WeakMap()
const computationStates = new WeakMap()
const reusableContinuationStates = new WeakMap()
const localContinuationStates = new WeakMap()
const delegatedContinuationStates = new WeakMap()
const promptStates = new WeakMap()
const shiftBodies = new WeakMap()
const oneShotStates = new WeakMap()
const effectKeysByOwner = new Map()

function requireInternalAuthority(value) {
  if (value !== internalAuthority) {
    throw new TypeError("@scalascript/control internal constructor is private")
  }
}

function requirePrivateState(states, value, label) {
  const state = states.get(value)
  if (state === undefined) {
    throw new TypeError(`${label} has no @scalascript/control private state`)
  }
  return state
}

function requireFunction(value, label) {
  if (typeof value !== "function") {
    throw new TypeError(`${label} must be a function`)
  }
  return value
}

function requireNonEmptyString(value, label) {
  if (typeof value !== "string" || value.length === 0) {
    throw new TypeError(`${label} must be a non-empty string`)
  }
  return value
}

function requireSymbol(value, label) {
  if (typeof value !== "symbol") {
    throw new TypeError(`${label} must be a symbol`)
  }
  return value
}

function requireEffectKey(value, label = "effect key") {
  if ((typeof value !== "object" && typeof value !== "function") || value === null || !effectKeyStates.has(value)) {
    throw new TypeError(`${label} is not a @scalascript/control EffectKey`)
  }
  return value
}

function requireOperation(value) {
  if ((typeof value !== "object" && typeof value !== "function") || value === null || !operationStates.has(value)) {
    throw new TypeError("operation is not a @scalascript/control Operation")
  }
  return value
}

function requireEff(value, label = "computation") {
  if ((typeof value !== "object" && typeof value !== "function") || value === null || !computationStates.has(value)) {
    throw new TypeError(`${label} is not a @scalascript/control Eff`)
  }
  return value
}

function requirePrompt(value) {
  if ((typeof value !== "object" && typeof value !== "function") || value === null || !promptStates.has(value)) {
    throw new TypeError("prompt is not a @scalascript/control Prompt")
  }
  return value
}

function operationLabel(operationId) {
  return `${operationId.effect.value}.${operationId.name}`
}

export const ResumeMultiplicity = Object.freeze({
  Reusable: "Reusable",
  OneShot: "OneShot"
})

export const ResumeRejected = Object.freeze({
  AlreadyResumed(operation) {
    return Object.freeze({ kind: "AlreadyResumed", operation })
  }
})

export const CaptureFailure = Object.freeze({
  UnmanagedCapture(site) {
    return Object.freeze({
      kind: "UnmanagedCapture",
      site: requireNonEmptyString(site, "capture site")
    })
  },
  CaptureBarrier(site, detail) {
    return Object.freeze({
      kind: "CaptureBarrier",
      site: requireNonEmptyString(site, "capture site"),
      detail: requireNonEmptyString(detail, "capture barrier detail")
    })
  },
  OneShotSource(site) {
    return Object.freeze({
      kind: "OneShotSource",
      site: requireNonEmptyString(site, "capture site")
    })
  },
  MissingCodec(site, typeId) {
    return Object.freeze({
      kind: "MissingCodec",
      site: requireNonEmptyString(site, "capture site"),
      typeId: requireNonEmptyString(typeId, "durable type id")
    })
  },
  UnsupportedGraph(site, detail) {
    return Object.freeze({
      kind: "UnsupportedGraph",
      site: requireNonEmptyString(site, "capture site"),
      detail: requireNonEmptyString(detail, "unsupported graph detail")
    })
  }
})

class OperationImpl {
  constructor(authority, factory, args) {
    requireInternalAuthority(authority)
    operationStates.set(this, Object.freeze({
      factory,
      effect: factory.effect,
      id: factory.id,
      multiplicity: factory.multiplicity,
      args: Object.freeze(Array.from(args))
    }))
    Object.freeze(this)
  }

  get effect() {
    return requirePrivateState(operationStates, this, "operation").effect
  }

  get id() {
    return requirePrivateState(operationStates, this, "operation").id
  }

  get multiplicity() {
    return requirePrivateState(operationStates, this, "operation").multiplicity
  }

  get args() {
    return requirePrivateState(operationStates, this, "operation").args
  }
}

function createOperationFactory(effect, name, options = undefined) {
  const key = requireEffectKey(effect)
  const operationName = requireNonEmptyString(name, "operation name")
  const multiplicity = options?.multiplicity ?? ResumeMultiplicity.Reusable
  if (multiplicity !== ResumeMultiplicity.Reusable && multiplicity !== ResumeMultiplicity.OneShot) {
    throw new TypeError(`unknown resume multiplicity: ${String(multiplicity)}`)
  }

  const id = Object.freeze({ effect: key.id, name: operationName })
  const factory = (...args) => new OperationImpl(internalAuthority, factory, args)
  Object.defineProperties(factory, {
    effect: { value: key, enumerable: true },
    id: { value: id, enumerable: true },
    multiplicity: { value: multiplicity, enumerable: true },
    is: {
      value: operation => operationStates.get(operation)?.factory === factory,
      enumerable: true
    }
  })
  return Object.freeze(factory)
}

class EffectKeyImpl {
  constructor(authority, id) {
    requireInternalAuthority(authority)
    effectKeyStates.set(this, Object.freeze({
      id: Object.freeze({ value: id })
    }))
    Object.freeze(this)
  }

  get id() {
    return requirePrivateState(effectKeyStates, this, "effect key").id
  }

  operation(name, options = undefined) {
    return createOperationFactory(this, name, options)
  }
}

function createEffectKey(id) {
  return new EffectKeyImpl(internalAuthority, requireNonEmptyString(id, "effect id"))
}

export function defineEffect(id, owner) {
  const descriptor = requireNonEmptyString(id, "effect id")
  const identity = requireSymbol(owner, "effect owner")
  const existing = effectKeysByOwner.get(identity)
  if (existing !== undefined) {
    if (existing.id.value !== descriptor) {
      throw new TypeError(
        `effect owner is already bound to descriptor ${existing.id.value}`
      )
    }
    return existing
  }

  const key = createEffectKey(descriptor)
  effectKeysByOwner.set(identity, key)
  return key
}

const saveOwner = Symbol("scalascript.control.Save.owner")
const saveKey = defineEffect("scalascript.control.Save", saveOwner)
const saveRejected = saveKey.operation("rejected", {
  multiplicity: ResumeMultiplicity.OneShot
})

export const Save = Object.freeze({
  key: saveKey,
  Rejected: saveRejected
})

class EffImpl {
  constructor(authority) {
    requireInternalAuthority(authority)
  }

  flatMap(next) {
    const source = requireEff(this, "flatMap receiver")
    return new Bind(
      internalAuthority,
      source,
      requireFunction(next, "flatMap continuation")
    )
  }

  map(transform) {
    const f = requireFunction(transform, "map function")
    return this.flatMap(value => Eff.pure(f(value)))
  }
}

class Pure extends EffImpl {
  constructor(authority, value) {
    super(authority)
    computationStates.set(this, Object.freeze({ kind: "Pure", value }))
    Object.freeze(this)
  }
}

class Deferred extends EffImpl {
  constructor(authority, thunk) {
    super(authority)
    computationStates.set(this, Object.freeze({ kind: "Deferred", thunk }))
    Object.freeze(this)
  }
}

class Bind extends EffImpl {
  constructor(authority, source, next) {
    super(authority)
    computationStates.set(this, Object.freeze({ kind: "Bind", source, next }))
    Object.freeze(this)
  }
}

class Attached extends EffImpl {
  constructor(authority, source, frames) {
    super(authority)
    computationStates.set(this, Object.freeze({
      kind: "Attached",
      source,
      frames: Object.freeze(frames.slice())
    }))
    Object.freeze(this)
  }
}

class Pending extends EffImpl {
  constructor(authority, operation, key, resumption) {
    super(authority)
    computationStates.set(this, Object.freeze({
      kind: "Pending",
      operation,
      key,
      resumption
    }))
    Object.freeze(this)
  }
}

class ReusableContinuationImpl {
  constructor(authority, site, resume) {
    requireInternalAuthority(authority)
    reusableContinuationStates.set(this, Object.freeze({ site, resume }))
    Object.freeze(this)
  }

  resume(value) {
    const state = requirePrivateState(
      reusableContinuationStates,
      this,
      "reusable continuation"
    )
    return requireEff(state.resume(value), "resumed computation")
  }

  save() {
    const state = requirePrivateState(
      reusableContinuationStates,
      this,
      "reusable continuation"
    )
    return perform(Save.Rejected(CaptureFailure.UnmanagedCapture(state.site)))
  }
}

class LocalContinuationImpl {
  constructor(authority, state, machine) {
    requireInternalAuthority(authority)
    localContinuationStates.set(this, Object.freeze({ state, machine }))
    Object.freeze(this)
  }

  resume(value) {
    const state = requirePrivateState(
      localContinuationStates,
      this,
      "local continuation"
    )
    return requireEff(
      state.machine.resume(state.state, value),
      "local continuation result"
    )
  }

  save() {
    return perform(
      Save.Rejected(CaptureFailure.UnmanagedCapture("Continuation.local"))
    )
  }
}

class OneShotContinuationImpl {
  constructor(authority, operation, resume) {
    requireInternalAuthority(authority)
    oneShotStates.set(this, { claimed: false, operation, resume })
    Object.freeze(this)
  }

  tryResume(value) {
    const state = requirePrivateState(
      oneShotStates,
      this,
      "one-shot continuation"
    )
    if (state.claimed) {
      return Object.freeze({
        ok: false,
        rejection: ResumeRejected.AlreadyResumed(state.operation)
      })
    }

    state.claimed = true
    const computation = requireEff(state.resume(value), "one-shot resumed computation")
    return Object.freeze({ ok: true, computation })
  }
}

class DelegatedOneShotContinuationImpl {
  constructor(authority, source, transform) {
    requireInternalAuthority(authority)
    delegatedContinuationStates.set(this, Object.freeze({ source, transform }))
    Object.freeze(this)
  }

  tryResume(value) {
    const state = requirePrivateState(
      delegatedContinuationStates,
      this,
      "delegated one-shot continuation"
    )
    const attempt = state.source.tryResume(value)
    if (!attempt.ok) return attempt
    return Object.freeze({
      ok: true,
      computation: requireEff(
        state.transform(attempt.computation),
        "delegated one-shot computation"
      )
    })
  }
}

function reusableResumption(continuation) {
  return Object.freeze({ kind: "Reusable", continuation })
}

function oneShotResumption(continuation) {
  return Object.freeze({ kind: "OneShot", continuation })
}

function attach(source, frames) {
  const computation = requireEff(source)
  return frames.length === 0
    ? computation
    : new Attached(internalAuthority, computation, frames)
}

function mapResumption(source, frames) {
  const captured = frames.slice()
  if (source.kind === "Reusable") {
    return reusableResumption(
      new ReusableContinuationImpl(internalAuthority, "Eff.step", value =>
        attach(source.continuation.resume(value), captured)
      )
    )
  }
  return oneShotResumption(
    new DelegatedOneShotContinuationImpl(
      internalAuthority,
      source.continuation,
      next => attach(next, captured)
    )
  )
}

function stepInternal(body) {
  let current = requireEff(body)
  let frames = []

  for (;;) {
    const state = requirePrivateState(computationStates, current, "computation")

    if (state.kind === "Pure") {
      if (frames.length === 0) {
        return { kind: "Done", value: state.value }
      }
      const frame = frames.pop()
      current = requireEff(frame(state.value), "flatMap continuation result")
      continue
    }

    if (state.kind === "Deferred") {
      current = requireEff(state.thunk(), "deferred computation")
      continue
    }

    if (state.kind === "Bind") {
      frames.push(state.next)
      current = state.source
      continue
    }

    if (state.kind === "Attached") {
      current = state.source
      if (state.frames.length !== 0) {
        frames = frames.concat(state.frames)
      }
      continue
    }

    if (state.kind === "Pending") {
      const resumption = frames.length === 0
        ? state.resumption
        : mapResumption(state.resumption, frames)
      return {
        kind: "Request",
        operation: state.operation,
        key: state.key,
        resumption
      }
    }

    throw new TypeError("unknown @scalascript/control Eff node")
  }
}

export const Eff = Object.freeze({
  pure(value) {
    return new Pure(internalAuthority, value)
  },

  defer(body) {
    return new Deferred(
      internalAuthority,
      requireFunction(body, "deferred body")
    )
  },

  runPure(body) {
    const result = stepInternal(body)
    if (result.kind === "Done") return result.value
    throw new TypeError(
      `Eff.runPure received unhandled operation ${operationLabel(result.operation.id)}`
    )
  }
})

export function perform(operation) {
  const request = requireOperation(operation)
  const key = requireEffectKey(request.effect, "operation effect")
  if (request.id.effect !== key.id) {
    throw new TypeError("operation descriptor does not belong to its effect key")
  }

  if (request.multiplicity === ResumeMultiplicity.Reusable) {
    return new Pending(
      internalAuthority,
      request,
      key,
      reusableResumption(
        new ReusableContinuationImpl(
          internalAuthority,
          operationLabel(request.id),
          value => Eff.pure(value)
        )
      )
    )
  }

  if (request.multiplicity === ResumeMultiplicity.OneShot) {
    return new Pending(
      internalAuthority,
      request,
      key,
      oneShotResumption(
        new OneShotContinuationImpl(
          internalAuthority,
          request.id,
          value => Eff.pure(value)
        )
      )
    )
  }

  throw new TypeError(`unknown resume multiplicity: ${String(request.multiplicity)}`)
}

function deepResumption(source, handler, handledKey) {
  if (source.kind === "Reusable") {
    return reusableResumption(
      new ReusableContinuationImpl(internalAuthority, "handle", value =>
        handleWithKey(
          source.continuation.resume(value),
          handler,
          handledKey
        )
      )
    )
  }

  return oneShotResumption(
    new DelegatedOneShotContinuationImpl(
      internalAuthority,
      source.continuation,
      next => handleWithKey(next, handler, handledKey)
    )
  )
}

function handleRequest(request, handler, handledKey) {
  const next = deepResumption(request.resumption, handler, handledKey)
  if (request.key === handledKey) {
    return requireEff(
      handler.onOperation(request.operation, next),
      "handler operation clause"
    )
  }
  return new Pending(
    internalAuthority,
    request.operation,
    request.key,
    next
  )
}

function handleWithKey(body, handler, handledKey) {
  return Eff.defer(() => {
    const current = stepInternal(body)
    if (current.kind === "Done") {
      return requireEff(handler.onReturn(current.value), "handler return clause")
    }
    return handleRequest(current, handler, handledKey)
  })
}

export function handle(body, handler) {
  const computation = requireEff(body)
  if (handler === null || typeof handler !== "object") {
    throw new TypeError("handler must be an object")
  }
  const handledKey = requireEffectKey(handler.effect, "handler effect")
  requireFunction(handler.onReturn, "handler return clause")
  requireFunction(handler.onOperation, "handler operation clause")
  return handleWithKey(computation, handler, handledKey)
}

class PromptImpl {
  constructor(authority) {
    requireInternalAuthority(authority)
    const key = createEffectKey("scalascript.control.Control")
    promptStates.set(this, Object.freeze({
      key,
      shiftOperation: key.operation("shift")
    }))
    Object.freeze(this)
  }
}

function resetBody(prompt, body) {
  const delimiter = requirePrompt(prompt)
  const promptState = requirePrivateState(promptStates, delimiter, "prompt")
  return handleWithKey(
    body,
    {
      effect: promptState.key,
      onReturn: value => Eff.pure(value),
      onOperation: (operation, resumption) => {
        const shiftBody = shiftBodies.get(operation)
        if (shiftBody === undefined) {
          throw new TypeError("matching control effect contained a non-shift operation")
        }
        if (resumption.kind !== "Reusable") {
          throw new TypeError("shift unexpectedly exposed a one-shot resumption")
        }
        return resetBody(
          delimiter,
          requireEff(
            shiftBody(resumption.continuation),
            "shift body computation"
          )
        )
      }
    },
    promptState.key
  )
}

export function freshPrompt(body) {
  const scope = requireFunction(body, "freshPrompt body")
  return scope(new PromptImpl(internalAuthority))
}

export function reset(prompt, body) {
  const delimiter = requirePrompt(prompt)
  const resetThunk = requireFunction(body, "reset body")
  return resetBody(delimiter, Eff.defer(resetThunk))
}

export function shift(prompt, body) {
  const delimiter = requirePrompt(prompt)
  const promptState = requirePrivateState(promptStates, delimiter, "prompt")
  const shiftBody = requireFunction(body, "shift body")
  const operation = promptState.shiftOperation()
  shiftBodies.set(operation, shiftBody)
  return perform(operation)
}

export const MachineStep = Object.freeze({
  Continue(next) {
    return Object.freeze({ kind: "Continue", next })
  },
  Evaluate(next) {
    return Object.freeze({ kind: "Evaluate", next: requireEff(next) })
  },
  Done(value) {
    return Object.freeze({ kind: "Done", value })
  }
})

function runStateMachine(initial, machine) {
  return Eff.defer(() => {
    const current = machine.step(initial)
    if (current === null || typeof current !== "object") {
      throw new TypeError("state machine step must be an object")
    }
    if (current.kind === "Continue") {
      return runStateMachine(current.next, machine)
    }
    if (current.kind === "Evaluate") {
      return requireEff(current.next, "state machine evaluation").flatMap(next =>
        runStateMachine(next, machine)
      )
    }
    if (current.kind === "Done") {
      return Eff.pure(current.value)
    }
    throw new TypeError(`unknown state machine step: ${String(current.kind)}`)
  })
}

export const StateMachine = Object.freeze({
  run(initial, machine) {
    if (machine === null || typeof machine !== "object") {
      throw new TypeError("state machine must be an object")
    }
    requireFunction(machine.step, "state machine step")
    return runStateMachine(initial, machine)
  }
})

// Typed durable-frame evidence for a captured state value. The in-process
// keystone needs only an independent snapshot so the save snapshot law
// (control-interoperability §8.2) holds. See specs/durable-continuation-save-run.md.
export const DurableValue = Object.freeze({
  immutable() {
    return Object.freeze({ snapshot(value) { return value } })
  },
  copying(copy) {
    const clone = requireFunction(copy, "durable value copy")
    return Object.freeze({ snapshot(value) { return clone(value) } })
  },
  // Evidence that a frame is NOT savable — its captured value is a raw foreign value
  // with no durable codec. A savable built with it resumes in-process, but save()
  // rejects with `failure` (the §8.3 FrameGate discriminator).
  unsavable(failure) {
    return Object.freeze({
      snapshot() {
        throw new TypeError("an unsavable frame has no snapshot")
      },
      captureBarrier: failure
    })
  }
})

function requireDurableValue(codec) {
  if (codec === null || typeof codec !== "object") {
    throw new TypeError("durable value codec must be an object")
  }
  requireFunction(codec.snapshot, "durable value snapshot")
  return codec
}

// Canonical durable-frame byte codec (specs/durable-frame-codec.md). The wire
// format is byte-identical to the Scala reference lane; the golden vectors in the
// test suite pin that agreement.
export class DurableDecodeError extends Error {
  constructor(message) {
    super(message)
    this.name = "DurableDecodeError"
  }
}

const durableTextEncoder = new TextEncoder()
const durableTextDecoder = new TextDecoder("utf-8", { fatal: false })
const durableMaxElementCount = 1 << 24
const durableCanonicalNaNBits = 0x7ff8000000000000n

export class DurableBytes {
  #bytes

  constructor(source) {
    this.#bytes = Uint8Array.from(source)
    Object.freeze(this)
  }

  get length() {
    return this.#bytes.length
  }

  toArray() {
    return Uint8Array.from(this.#bytes)
  }

  toHex() {
    let out = ""
    for (const value of this.#bytes) out += value.toString(16).padStart(2, "0")
    return out
  }

  static fromArray(source) {
    return new DurableBytes(source)
  }
}

class DurableWriter {
  constructor() {
    this.parts = []
  }

  writeByte(value) {
    this.parts.push(value & 0xff)
  }

  writeInt(value) {
    this.writeByte(value >>> 24)
    this.writeByte(value >>> 16)
    this.writeByte(value >>> 8)
    this.writeByte(value)
  }

  writeLong(value) {
    const unsigned = BigInt.asUintN(64, value)
    for (let shift = 56n; shift >= 0n; shift -= 8n) {
      this.writeByte(Number((unsigned >> shift) & 0xffn))
    }
  }

  writeBytes(array) {
    for (const value of array) this.writeByte(value)
  }

  toBytes() {
    return new DurableBytes(this.parts)
  }
}

class DurableReader {
  constructor(bytes) {
    this.data = bytes.toArray()
    this.position = 0
  }

  require(count) {
    if (count < 0 || this.position + count > this.data.length) {
      throw new DurableDecodeError(
        `durable frame truncated: need ${count} byte(s) at offset ${this.position} of ${this.data.length}`
      )
    }
  }

  readByte() {
    this.require(1)
    return this.data[this.position++]
  }

  readInt() {
    return (
      (this.readByte() << 24) |
      (this.readByte() << 16) |
      (this.readByte() << 8) |
      this.readByte()
    )
  }

  readLong() {
    let unsigned = 0n
    for (let index = 0; index < 8; index++) {
      unsigned = (unsigned << 8n) | BigInt(this.readByte())
    }
    return BigInt.asIntN(64, unsigned)
  }

  readByteLength(label) {
    const value = this.readInt()
    if (value < 0) {
      throw new DurableDecodeError(`${label} length out of range: ${value}`)
    }
    if (value > this.data.length - this.position) {
      throw new DurableDecodeError(
        `${label} length ${value} exceeds ${this.data.length - this.position} remaining byte(s)`
      )
    }
    return value
  }

  readElementCount(label) {
    const value = this.readInt()
    if (value < 0 || value > durableMaxElementCount) {
      throw new DurableDecodeError(`${label} element count out of range: ${value}`)
    }
    return value
  }

  readBytes(count) {
    this.require(count)
    const out = this.data.slice(this.position, this.position + count)
    this.position += count
    return out
  }

  requireExhausted() {
    if (this.position !== this.data.length) {
      throw new DurableDecodeError(
        `durable frame has ${this.data.length - this.position} trailing byte(s)`
      )
    }
  }
}

function makeDurableCodec(write, read) {
  const codec = {
    write,
    read,
    encode(value) {
      const writer = new DurableWriter()
      write(writer, value)
      return writer.toBytes()
    },
    decode(bytes) {
      const reader = new DurableReader(bytes)
      const value = read(reader)
      reader.requireExhausted()
      return value
    },
    snapshot(value) {
      return codec.decode(codec.encode(value))
    }
  }
  return Object.freeze(codec)
}

// Minimal two's-complement big-endian, byte-identical to Java BigInteger.toByteArray.
function bigIntToDurableBytes(value) {
  if (value === 0n) return Uint8Array.of(0)
  let width = 1
  while (true) {
    const bits = BigInt(width * 8)
    const min = -(1n << (bits - 1n))
    const max = (1n << (bits - 1n)) - 1n
    if (value >= min && value <= max) break
    width++
  }
  const mask = (1n << BigInt(width * 8)) - 1n
  let unsigned = value & mask
  const out = new Uint8Array(width)
  for (let index = width - 1; index >= 0; index--) {
    out[index] = Number(unsigned & 0xffn)
    unsigned >>= 8n
  }
  return out
}

function durableBytesToBigInt(bytes) {
  let unsigned = 0n
  for (const value of bytes) unsigned = (unsigned << 8n) | BigInt(value)
  return BigInt.asIntN(bytes.length * 8, unsigned)
}

export const DurableCodec = Object.freeze({
  unit: makeDurableCodec(
    (_writer, _value) => {},
    _reader => undefined
  ),
  boolean: makeDurableCodec(
    (writer, value) => writer.writeByte(value ? 1 : 0),
    reader => {
      const tag = reader.readByte()
      if (tag === 0) return false
      if (tag === 1) return true
      throw new DurableDecodeError(`invalid boolean tag: ${tag}`)
    }
  ),
  int: makeDurableCodec(
    (writer, value) => writer.writeInt(value),
    reader => reader.readInt()
  ),
  long: makeDurableCodec(
    (writer, value) => writer.writeLong(value),
    reader => reader.readLong()
  ),
  bigInt: makeDurableCodec(
    (writer, value) => {
      const magnitude = bigIntToDurableBytes(value)
      writer.writeInt(magnitude.length)
      writer.writeBytes(magnitude)
    },
    reader => {
      const count = reader.readByteLength("bigint")
      if (count === 0) {
        throw new DurableDecodeError("bigint magnitude must be non-empty")
      }
      return durableBytesToBigInt(reader.readBytes(count))
    }
  ),
  double: makeDurableCodec(
    (writer, value) => {
      const view = new DataView(new ArrayBuffer(8))
      if (Number.isNaN(value)) view.setBigUint64(0, durableCanonicalNaNBits, false)
      else view.setFloat64(0, value, false)
      for (let index = 0; index < 8; index++) writer.writeByte(view.getUint8(index))
    },
    reader => {
      const view = new DataView(new ArrayBuffer(8))
      for (let index = 0; index < 8; index++) view.setUint8(index, reader.readByte())
      return view.getFloat64(0, false)
    }
  ),
  string: makeDurableCodec(
    (writer, value) => {
      const utf8 = durableTextEncoder.encode(value)
      writer.writeInt(utf8.length)
      writer.writeBytes(utf8)
    },
    reader => {
      const count = reader.readByteLength("string")
      return durableTextDecoder.decode(reader.readBytes(count))
    }
  ),
  bytes: makeDurableCodec(
    (writer, value) => {
      const array = value.toArray()
      writer.writeInt(array.length)
      writer.writeBytes(array)
    },
    reader => {
      const count = reader.readByteLength("bytes")
      return new DurableBytes(reader.readBytes(count))
    }
  ),
  pair(left, right) {
    return makeDurableCodec(
      (writer, value) => {
        left.write(writer, value[0])
        right.write(writer, value[1])
      },
      reader => [left.read(reader), right.read(reader)]
    )
  },
  either(left, right) {
    return makeDurableCodec(
      (writer, value) => {
        if (Object.prototype.hasOwnProperty.call(value, "left")) {
          writer.writeByte(0)
          left.write(writer, value.left)
        } else {
          writer.writeByte(1)
          right.write(writer, value.right)
        }
      },
      reader => {
        const tag = reader.readByte()
        if (tag === 0) return { left: left.read(reader) }
        if (tag === 1) return { right: right.read(reader) }
        throw new DurableDecodeError(`invalid either tag: ${tag}`)
      }
    )
  },
  list(element) {
    return makeDurableCodec(
      (writer, value) => {
        writer.writeInt(value.length)
        for (const item of value) element.write(writer, item)
      },
      reader => {
        const count = reader.readElementCount("list")
        const out = []
        for (let index = 0; index < count; index++) out.push(element.read(reader))
        return out
      }
    )
  },
  imap(codec, to, from) {
    return makeDurableCodec(
      (writer, value) => codec.write(writer, from(value)),
      reader => to(codec.read(reader))
    )
  },
  // Stamp a value's canonical bytes with a nominal schema identity (a name and an
  // integer version) and reject, on decode, bytes written under a different name or
  // version (§9.1). Header is string(schemaId) ++ int(version), byte-identical to the
  // Scala reference lane. See specs/durable-nominal-schema.md.
  schema(schemaId, version, codec) {
    return makeDurableCodec(
      (writer, value) => {
        DurableCodec.string.write(writer, schemaId)
        DurableCodec.int.write(writer, version)
        codec.write(writer, value)
      },
      reader => {
        const got = DurableCodec.string.read(reader)
        const decodedVersion = DurableCodec.int.read(reader)
        if (got !== schemaId) {
          throw new DurableDecodeError(
            `schema identity mismatch: expected '${schemaId}', got '${got}'`
          )
        }
        if (decodedVersion !== version) {
          throw new DurableDecodeError(
            `schema version mismatch: expected ${version}, got ${decodedVersion}`
          )
        }
        return codec.read(reader)
      }
    )
  },
  // A canonical Map codec: entries are written sorted by the unsigned lexicographic
  // order of each key's own encoding, so bytes are independent of insertion order
  // (§9.1). Decoding rejects keys not in strictly ascending canonical order.
  map(keyCodec, valueCodec) {
    return makeDurableCodec(
      (writer, value) => {
        const entries = [...value.entries()].map(([key, item]) => ({
          keyBytes: keyCodec.encode(key).toArray(),
          key,
          item
        }))
        entries.sort((left, right) =>
          compareBytesUnsigned(left.keyBytes, right.keyBytes)
        )
        writer.writeInt(entries.length)
        for (const entry of entries) {
          keyCodec.write(writer, entry.key)
          valueCodec.write(writer, entry.item)
        }
      },
      reader => {
        const count = reader.readElementCount("map")
        const result = new Map()
        let previousKey = null
        for (let index = 0; index < count; index++) {
          const key = keyCodec.read(reader)
          const keyBytes = keyCodec.encode(key).toArray()
          if (previousKey !== null && compareBytesUnsigned(previousKey, keyBytes) >= 0) {
            throw new DurableDecodeError(
              "map keys are not in strictly ascending canonical order"
            )
          }
          previousKey = keyBytes
          result.set(key, valueCodec.read(reader))
        }
        return result
      }
    )
  },
  left(value) {
    return { left: value }
  },
  right(value) {
    return { right: value }
  }
})

function compareBytesUnsigned(left, right) {
  const length = Math.min(left.length, right.length)
  for (let index = 0; index < length; index++) {
    if (left[index] !== right[index]) return left[index] - right[index]
  }
  return left.length - right.length
}

// A self-contained, synchronous SHA-256 over a Uint8Array. The package is
// deliberately import-free so it stays portable and zero-dependency; the digest
// is a standard algorithm verified against known vectors in the test suite.
const sha256Constants = Uint32Array.from([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
  0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
  0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
  0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
  0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
  0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
  0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
  0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
])

function rotateRight32(value, count) {
  return ((value >>> count) | (value << (32 - count))) >>> 0
}

function sha256(input) {
  let h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a
  let h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19
  const bitLength = input.length * 8
  const withMark = input.length + 1
  const zeros = (56 - (withMark % 64) + 64) % 64
  const total = withMark + zeros + 8
  const message = new Uint8Array(total)
  message.set(input)
  message[input.length] = 0x80
  const view = new DataView(message.buffer)
  view.setUint32(total - 8, Math.floor(bitLength / 0x100000000) >>> 0, false)
  view.setUint32(total - 4, bitLength >>> 0, false)
  const schedule = new Uint32Array(64)
  for (let offset = 0; offset < total; offset += 64) {
    for (let i = 0; i < 16; i++) schedule[i] = view.getUint32(offset + i * 4, false)
    for (let i = 16; i < 64; i++) {
      const w15 = schedule[i - 15]
      const w2 = schedule[i - 2]
      const s0 = (rotateRight32(w15, 7) ^ rotateRight32(w15, 18) ^ (w15 >>> 3)) >>> 0
      const s1 = (rotateRight32(w2, 17) ^ rotateRight32(w2, 19) ^ (w2 >>> 10)) >>> 0
      schedule[i] = (schedule[i - 16] + s0 + schedule[i - 7] + s1) >>> 0
    }
    let a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7
    for (let i = 0; i < 64; i++) {
      const bigS1 = (rotateRight32(e, 6) ^ rotateRight32(e, 11) ^ rotateRight32(e, 25)) >>> 0
      const choose = ((e & f) ^ (~e & g)) >>> 0
      const t1 = (h + bigS1 + choose + sha256Constants[i] + schedule[i]) >>> 0
      const bigS0 = (rotateRight32(a, 2) ^ rotateRight32(a, 13) ^ rotateRight32(a, 22)) >>> 0
      const majority = ((a & b) ^ (a & c) ^ (b & c)) >>> 0
      const t2 = (bigS0 + majority) >>> 0
      h = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0
    }
    h0 = (h0 + a) >>> 0; h1 = (h1 + b) >>> 0; h2 = (h2 + c) >>> 0; h3 = (h3 + d) >>> 0
    h4 = (h4 + e) >>> 0; h5 = (h5 + f) >>> 0; h6 = (h6 + g) >>> 0; h7 = (h7 + h) >>> 0
  }
  const out = new Uint8Array(32)
  const outView = new DataView(out.buffer)
  const words = [h0, h1, h2, h3, h4, h5, h6, h7]
  for (let i = 0; i < 8; i++) outView.setUint32(i * 4, words[i] >>> 0, false)
  return out
}

// Durable capsule envelope + resume points (specs/durable-capsule-envelope.md),
// mirroring the Scala reference lane byte-for-byte.
export class CapsuleRejected extends Error {
  constructor(kind, message) {
    super(message)
    this.name = "CapsuleRejected"
    this.kind = kind
  }
}

const capsuleFormatVersion = 2
// "ssc-frame-v1\0" — NUL-terminated domain separator (§10).
const frameDigestDomain = Uint8Array.from([
  ...new TextEncoder().encode("ssc-frame-v1"),
  0
])

// The pinned ABI/dependency identity a capsule carries so admission can reject a
// codec, artifact, or dependency mismatch before any user code runs (§10, §12).
export const ArtifactProfile = Object.freeze({
  default: Object.freeze({
    codecAbiVersion: 1,
    artifactAbiId: "",
    requiredDependencies: new Set()
  }),
  of(codecAbiVersion, artifactAbiId, requiredDependencies) {
    return Object.freeze({ codecAbiVersion, artifactAbiId, requiredDependencies })
  }
})

const capsuleStates = new WeakMap()

export class DurableCapsule {
  constructor(authority, formatVersion, resumePointId, codecAbiVersion, artifactAbiId, requiredDependencies, frame, digest) {
    requireInternalAuthority(authority)
    capsuleStates.set(this, Object.freeze({
      formatVersion,
      resumePointId,
      codecAbiVersion,
      artifactAbiId,
      requiredDependencies: Object.freeze(requiredDependencies.slice()),
      frame,
      digest
    }))
    Object.freeze(this)
  }

  get formatVersion() {
    return requirePrivateState(capsuleStates, this, "durable capsule").formatVersion
  }

  get resumePointId() {
    return requirePrivateState(capsuleStates, this, "durable capsule").resumePointId
  }

  encode() {
    return capsuleCodec.encode(this)
  }

  static decode(bytes) {
    return capsuleCodec.decode(bytes)
  }
}

const capsuleCodec = DurableCodec.imap(
  DurableCodec.pair(
    DurableCodec.int,
    DurableCodec.pair(
      DurableCodec.string,
      DurableCodec.pair(
        DurableCodec.int,
        DurableCodec.pair(
          DurableCodec.string,
          DurableCodec.pair(
            DurableCodec.list(DurableCodec.string),
            DurableCodec.pair(DurableCodec.bytes, DurableCodec.bytes)
          )
        )
      )
    )
  ),
  ([version, [id, [codecAbi, [artifactAbi, [deps, [frame, digest]]]]]]) =>
    new DurableCapsule(
      internalAuthority, version, id, codecAbi, artifactAbi, deps, frame, digest
    ),
  capsule => {
    const state = requirePrivateState(capsuleStates, capsule, "durable capsule")
    return [
      state.formatVersion,
      [state.resumePointId, [state.codecAbiVersion, [state.artifactAbiId,
        [state.requiredDependencies, [state.frame, state.digest]]]]]
    ]
  }
)

function digestFrame(frame) {
  const combined = new Uint8Array(frameDigestDomain.length + frame.length)
  combined.set(frameDigestDomain)
  combined.set(frame.toArray(), frameDigestDomain.length)
  return new DurableBytes(sha256(combined))
}

function createCapsule(id, frame, profile) {
  return new DurableCapsule(
    internalAuthority,
    capsuleFormatVersion,
    id,
    profile.codecAbiVersion,
    profile.artifactAbiId,
    [...profile.requiredDependencies].sort(),
    frame,
    digestFrame(frame)
  )
}

function verifyCapsule(capsule, expectedId) {
  const state = requirePrivateState(capsuleStates, capsule, "durable capsule")
  if (state.formatVersion !== capsuleFormatVersion) {
    throw new CapsuleRejected(
      "FormatVersion",
      `unsupported capsule format version ${state.formatVersion}`
    )
  }
  if (state.resumePointId !== expectedId) {
    throw new CapsuleRejected(
      "ResumePointMismatch",
      `capsule resume point '${state.resumePointId}' does not match '${expectedId}'`
    )
  }
  if (digestFrame(state.frame).toHex() !== state.digest.toHex()) {
    throw new CapsuleRejected("FrameTampered", "capsule frame digest mismatch")
  }
  return state
}

const resumePointStates = new WeakMap()

export class ResumePoint {
  constructor(authority, id, machine, codec, requiredResolvers, profile) {
    requireInternalAuthority(authority)
    resumePointStates.set(
      this,
      Object.freeze({ id, machine, codec, requiredResolvers, profile })
    )
    Object.freeze(this)
  }

  get id() {
    return requirePrivateState(resumePointStates, this, "resume point").id
  }

  get requiredResolvers() {
    return requirePrivateState(resumePointStates, this, "resume point").requiredResolvers
  }

  get profile() {
    return requirePrivateState(resumePointStates, this, "resume point").profile
  }

  savable(state) {
    const point = requirePrivateState(resumePointStates, this, "resume point")
    return Continuation.savable(state, point.machine, point.codec)
  }

  freeze(state) {
    const point = requirePrivateState(resumePointStates, this, "resume point")
    return createCapsule(point.id, point.codec.encode(state), point.profile)
  }

  // Admit a capsule and rebind it, running every admission check atomically before any
  // frame is decoded or run (§9.2, §11, §12): capsule integrity (verifyCapsule), then a
  // pinned codec-ABI (CodecMismatch), artifact-ABI (AbiMismatch), and required
  // resolver/dependency (MissingDependency) check — each a distinct typed rejection.
  restore(capsule, availableResolvers = new Set(), availableDependencies = new Set()) {
    const point = requirePrivateState(resumePointStates, this, "resume point")
    const state = verifyCapsule(capsule, point.id)
    if (state.codecAbiVersion !== point.profile.codecAbiVersion) {
      throw new CapsuleRejected(
        "CodecMismatch",
        `capsule codec ABI v${state.codecAbiVersion} is incompatible with runtime v${point.profile.codecAbiVersion}`
      )
    }
    if (state.artifactAbiId !== point.profile.artifactAbiId) {
      throw new CapsuleRejected(
        "AbiMismatch",
        `capsule artifact ABI '${state.artifactAbiId}' differs from runtime '${point.profile.artifactAbiId}'`
      )
    }
    const missing = [
      ...[...point.requiredResolvers].filter(resolver => !availableResolvers.has(resolver)),
      ...state.requiredDependencies.filter(dependency => !availableDependencies.has(dependency))
    ]
    if (missing.length > 0) {
      throw new CapsuleRejected(
        "MissingDependency",
        `capsule requires unavailable dependency/resolver(s): ${missing.sort().join(", ")}`
      )
    }
    return new SavedContinuationImpl(
      internalAuthority,
      point.codec.decode(state.frame),
      point.machine,
      point.codec
    )
  }

  static define(id, machine, codec, requiredResolvers = new Set(), profile = ArtifactProfile.default) {
    if (typeof id !== "string" || id.length === 0) {
      throw new TypeError("resume point id must be a non-empty string")
    }
    if (machine === null || typeof machine !== "object") {
      throw new TypeError("resume state machine must be an object")
    }
    requireFunction(machine.resume, "resume state machine method")
    requireDurableValue(codec)
    if (!(requiredResolvers instanceof Set)) {
      throw new TypeError("required resolvers must be a Set")
    }
    if (profile === null || typeof profile !== "object") {
      throw new TypeError("artifact profile must be an object")
    }
    return new ResumePoint(internalAuthority, id, machine, codec, requiredResolvers, profile)
  }
}

// An inert reference to external state (specs/durable-ref.md, §9.2). It carries
// only a providerId and an opaque reference; decoding never contacts the resource.
// A forged reference simply fails resolution, so the constructor is public.
export class DurableRef {
  #providerId
  #opaqueReference

  constructor(providerId, opaqueReference) {
    this.#providerId = providerId
    this.#opaqueReference = opaqueReference
    Object.freeze(this)
  }

  get providerId() {
    return this.#providerId
  }

  get opaqueReference() {
    return this.#opaqueReference
  }

  static of(providerId, opaqueReference) {
    if (typeof providerId !== "string") {
      throw new TypeError("durable reference provider id must be a string")
    }
    if (!(opaqueReference instanceof DurableBytes)) {
      throw new TypeError("durable reference opaque reference must be DurableBytes")
    }
    return new DurableRef(providerId, opaqueReference)
  }

  static codec() {
    return durableRefCodec
  }
}

const durableRefCodec = DurableCodec.imap(
  DurableCodec.pair(DurableCodec.string, DurableCodec.bytes),
  ([providerId, opaqueReference]) => new DurableRef(providerId, opaqueReference),
  ref => [ref.providerId, ref.opaqueReference]
)

const restoreOwner = Symbol("scalascript.control.Restore.owner")
const restoreKey = defineEffect("scalascript.control.Restore", restoreOwner)
const restoreResolve = restoreKey.operation("resolve")

export const Restore = Object.freeze({
  key: restoreKey,
  // Resolve an inert DurableRef to its value — the one real restore operation.
  Resolve: restoreResolve,

  // Perform a post-admission resolution of a durable reference (§9.2).
  resolve(ref) {
    return perform(restoreResolve(ref))
  },

  // Discharge Restore by resolving every Resolve through the resolver, once per
  // resolve, reinstalling around the suffix; each run resolves independently.
  withResolver(resolver, body) {
    if (resolver === null || typeof resolver !== "object") {
      throw new TypeError("resolver must be an object")
    }
    requireFunction(resolver.resolve, "resolver resolve method")
    return handle(requireEff(body, "restore body"), {
      effect: restoreKey,
      onReturn: value => Eff.pure(value),
      onOperation(operation, resumption) {
        if (!restoreResolve.is(operation)) {
          throw new TypeError(`unknown Restore operation ${operation.id?.name}`)
        }
        const [ref] = operation.args
        const resolved = resolver.resolve(ref)
        if (resumption.kind === "Reusable") {
          return resumption.continuation.resume(resolved)
        }
        const attempt = resumption.continuation.tryResume(resolved)
        if (attempt.ok) return attempt.computation
        throw new TypeError("restore resolution resumed an already-claimed continuation")
      }
    })
  },

  // Discharge Restore for an in-process run that resolves nothing; a run that does
  // resolve fails here, because no provider is bound — use withResolver.
  admitLocally(body) {
    const computation = requireEff(body, "restore body")
    return handle(computation, {
      effect: restoreKey,
      onReturn: value => Eff.pure(value),
      onOperation(operation) {
        throw new TypeError(
          `Restore.admitLocally received ${operation.id?.name}; no provider is bound`
        )
      }
    })
  }
})

const savedContinuationStates = new WeakMap()
const savableContinuationStates = new WeakMap()

// A reusable saved continuation from a managed savable state machine: immutable,
// multi-shot, each run reconstructs an independent frame and begins at the capture
// point — no prefix replay (control-interoperability §8.1/§8.2).
class SavedContinuationImpl {
  constructor(authority, frame, machine, codec) {
    requireInternalAuthority(authority)
    savedContinuationStates.set(this, Object.freeze({ frame, machine, codec }))
    Object.freeze(this)
  }

  run(value) {
    const state = requirePrivateState(
      savedContinuationStates,
      this,
      "saved continuation"
    )
    return Eff.defer(() => {
      const fresh = state.codec.snapshot(state.frame)
      return requireEff(
        state.machine.resume(fresh, value),
        "saved continuation result"
      )
    })
  }
}

class SavableContinuationImpl {
  constructor(authority, state, machine, codec) {
    requireInternalAuthority(authority)
    savableContinuationStates.set(this, Object.freeze({ state, machine, codec }))
    Object.freeze(this)
  }

  resume(value) {
    const state = requirePrivateState(
      savableContinuationStates,
      this,
      "savable continuation"
    )
    return requireEff(
      state.machine.resume(state.state, value),
      "savable continuation result"
    )
  }

  save() {
    const state = requirePrivateState(
      savableContinuationStates,
      this,
      "savable continuation"
    )
    // A codec may declare its frame Unsavable (a raw foreign value with no durable
    // codec, the §8.3 FrameGate); save() then rejects with the typed CaptureFailure
    // instead of producing a SavedContinuation. Otherwise snapshot the live state now
    // so a later mutation of the original cannot change the saved frame (§8.2).
    if (state.codec.captureBarrier !== undefined && state.codec.captureBarrier !== null) {
      return perform(Save.Rejected(state.codec.captureBarrier))
    }
    const frame = state.codec.snapshot(state.state)
    return Eff.pure(
      new SavedContinuationImpl(internalAuthority, frame, state.machine, state.codec)
    )
  }
}

export const Continuation = Object.freeze({
  local(state, machine) {
    if (machine === null || typeof machine !== "object") {
      throw new TypeError("resume state machine must be an object")
    }
    requireFunction(machine.resume, "resume state machine method")
    return new LocalContinuationImpl(internalAuthority, state, machine)
  },

  savable(state, machine, codec) {
    if (machine === null || typeof machine !== "object") {
      throw new TypeError("resume state machine must be an object")
    }
    requireFunction(machine.resume, "resume state machine method")
    requireDurableValue(codec)
    return new SavableContinuationImpl(internalAuthority, state, machine, codec)
  }
})
