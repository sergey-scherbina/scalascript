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
  left(value) {
    return { left: value }
  },
  right(value) {
    return { right: value }
  }
})

const restoreOwner = Symbol("scalascript.control.Restore.owner")
const restoreKey = defineEffect("scalascript.control.Restore", restoreOwner)

// Discharge the Restore capability for an in-process run with no provider-backed
// restore. Nothing constructs a Restore operation yet, so onOperation is
// unreachable; provider-backed admission is a follow-on slice.
export const Restore = Object.freeze({
  key: restoreKey,
  admitLocally(body) {
    const computation = requireEff(body, "restore body")
    return handle(computation, {
      effect: restoreKey,
      onReturn: value => Eff.pure(value),
      onOperation: () => {
        throw new TypeError(
          "Restore.admitLocally received a restore operation; no provider is bound"
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
    // Snapshot the live state now so a later mutation of the original cannot
    // change the saved frame (§8.2). No admission service in-process, so this
    // success value inhabits the Save row alongside the Rejected path.
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
