const effectKeys = new WeakSet()
const operations = new WeakSet()
const computations = new WeakSet()
const prompts = new WeakSet()
const shiftBodies = new WeakMap()
const oneShotStates = new WeakMap()

const operationFactoryToken = Symbol("operationFactory")

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

function requireEffectKey(value, label = "effect key") {
  if ((typeof value !== "object" && typeof value !== "function") || value === null || !effectKeys.has(value)) {
    throw new TypeError(`${label} is not a @scalascript/control EffectKey`)
  }
  return value
}

function requireOperation(value) {
  if ((typeof value !== "object" && typeof value !== "function") || value === null || !operations.has(value)) {
    throw new TypeError("operation is not a @scalascript/control Operation")
  }
  return value
}

function requireEff(value, label = "computation") {
  if ((typeof value !== "object" && typeof value !== "function") || value === null || !computations.has(value)) {
    throw new TypeError(`${label} is not a @scalascript/control Eff`)
  }
  return value
}

function requirePrompt(value) {
  if ((typeof value !== "object" && typeof value !== "function") || value === null || !prompts.has(value)) {
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
  constructor(factory, args) {
    this.effect = factory.effect
    this.id = factory.id
    this.multiplicity = factory.multiplicity
    this.args = Object.freeze(Array.from(args))
    this[operationFactoryToken] = factory
    operations.add(this)
    Object.freeze(this)
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
  const factory = (...args) => new OperationImpl(factory, args)
  Object.defineProperties(factory, {
    effect: { value: key, enumerable: true },
    id: { value: id, enumerable: true },
    multiplicity: { value: multiplicity, enumerable: true },
    is: {
      value: operation => operations.has(operation) && operation[operationFactoryToken] === factory,
      enumerable: true
    }
  })
  return Object.freeze(factory)
}

class EffectKeyImpl {
  constructor(id) {
    this.id = Object.freeze({ value: id })
    effectKeys.add(this)
    Object.freeze(this)
  }

  operation(name, options = undefined) {
    return createOperationFactory(this, name, options)
  }
}

export function defineEffect(id) {
  return new EffectKeyImpl(requireNonEmptyString(id, "effect id"))
}

const saveKey = defineEffect("scalascript.control.Save")
const saveRejected = saveKey.operation("rejected", {
  multiplicity: ResumeMultiplicity.OneShot
})

export const Save = Object.freeze({
  key: saveKey,
  Rejected: saveRejected
})

class EffImpl {
  constructor() {
    computations.add(this)
  }

  flatMap(next) {
    return new Bind(this, requireFunction(next, "flatMap continuation"))
  }

  map(transform) {
    const f = requireFunction(transform, "map function")
    return this.flatMap(value => Eff.pure(f(value)))
  }
}

class Pure extends EffImpl {
  constructor(value) {
    super()
    this.value = value
    Object.freeze(this)
  }
}

class Deferred extends EffImpl {
  constructor(thunk) {
    super()
    this.thunk = thunk
    Object.freeze(this)
  }
}

class Bind extends EffImpl {
  constructor(source, next) {
    super()
    this.source = source
    this.next = next
    Object.freeze(this)
  }
}

class Attached extends EffImpl {
  constructor(source, frames) {
    super()
    this.source = source
    this.frames = Object.freeze(frames.slice())
    Object.freeze(this)
  }
}

class Pending extends EffImpl {
  constructor(operation, key, resumption) {
    super()
    this.operation = operation
    this.key = key
    this.resumption = resumption
    Object.freeze(this)
  }
}

class ReusableContinuationImpl {
  constructor(site, resume) {
    this.site = site
    this.resumeBody = resume
    Object.freeze(this)
  }

  resume(value) {
    return requireEff(this.resumeBody(value), "resumed computation")
  }

  save() {
    return perform(Save.Rejected(CaptureFailure.UnmanagedCapture(this.site)))
  }
}

class LocalContinuationImpl {
  constructor(state, machine) {
    this.state = state
    this.machine = machine
    Object.freeze(this)
  }

  resume(value) {
    return requireEff(this.machine.resume(this.state, value), "local continuation result")
  }

  save() {
    return perform(
      Save.Rejected(CaptureFailure.UnmanagedCapture("Continuation.local"))
    )
  }
}

class OneShotContinuationImpl {
  constructor(operation, resume) {
    oneShotStates.set(this, { claimed: false, operation, resume })
    Object.freeze(this)
  }

  tryResume(value) {
    const state = oneShotStates.get(this)
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
  constructor(source, transform) {
    this.source = source
    this.transform = transform
    Object.freeze(this)
  }

  tryResume(value) {
    const attempt = this.source.tryResume(value)
    if (!attempt.ok) return attempt
    return Object.freeze({
      ok: true,
      computation: requireEff(
        this.transform(attempt.computation),
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
  return frames.length === 0 ? computation : new Attached(computation, frames)
}

function mapResumption(source, frames) {
  const captured = frames.slice()
  if (source.kind === "Reusable") {
    return reusableResumption(
      new ReusableContinuationImpl("Eff.step", value =>
        attach(source.continuation.resume(value), captured)
      )
    )
  }
  return oneShotResumption(
    new DelegatedOneShotContinuationImpl(source.continuation, next =>
      attach(next, captured)
    )
  )
}

function stepInternal(body) {
  let current = requireEff(body)
  let frames = []

  for (;;) {
    if (current instanceof Pure) {
      if (frames.length === 0) {
        return { kind: "Done", value: current.value }
      }
      const frame = frames.pop()
      current = requireEff(frame(current.value), "flatMap continuation result")
      continue
    }

    if (current instanceof Deferred) {
      current = requireEff(current.thunk(), "deferred computation")
      continue
    }

    if (current instanceof Bind) {
      frames.push(current.next)
      current = current.source
      continue
    }

    if (current instanceof Attached) {
      const attached = current
      current = attached.source
      if (attached.frames.length !== 0) {
        frames = frames.concat(attached.frames)
      }
      continue
    }

    if (current instanceof Pending) {
      const resumption = frames.length === 0
        ? current.resumption
        : mapResumption(current.resumption, frames)
      return {
        kind: "Request",
        operation: current.operation,
        key: current.key,
        resumption
      }
    }

    throw new TypeError("unknown @scalascript/control Eff node")
  }
}

export const Eff = Object.freeze({
  pure(value) {
    return new Pure(value)
  },

  defer(body) {
    return new Deferred(requireFunction(body, "deferred body"))
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
      request,
      key,
      reusableResumption(
        new ReusableContinuationImpl(operationLabel(request.id), value => Eff.pure(value))
      )
    )
  }

  if (request.multiplicity === ResumeMultiplicity.OneShot) {
    return new Pending(
      request,
      key,
      oneShotResumption(
        new OneShotContinuationImpl(request.id, value => Eff.pure(value))
      )
    )
  }

  throw new TypeError(`unknown resume multiplicity: ${String(request.multiplicity)}`)
}

function deepResumption(source, handler, handledKey) {
  if (source.kind === "Reusable") {
    return reusableResumption(
      new ReusableContinuationImpl("handle", value =>
        handleWithKey(
          source.continuation.resume(value),
          handler,
          handledKey
        )
      )
    )
  }

  return oneShotResumption(
    new DelegatedOneShotContinuationImpl(source.continuation, next =>
      handleWithKey(next, handler, handledKey)
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
  return new Pending(request.operation, request.key, next)
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
  constructor() {
    this.key = defineEffect("scalascript.control.Control")
    this.shiftOperation = this.key.operation("shift")
    prompts.add(this)
    Object.freeze(this)
  }
}

function resetBody(prompt, body) {
  const delimiter = requirePrompt(prompt)
  return handleWithKey(
    body,
    {
      effect: delimiter.key,
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
    delimiter.key
  )
}

export function freshPrompt(body) {
  const scope = requireFunction(body, "freshPrompt body")
  return scope(new PromptImpl())
}

export function reset(prompt, body) {
  const delimiter = requirePrompt(prompt)
  const resetThunk = requireFunction(body, "reset body")
  return resetBody(delimiter, Eff.defer(resetThunk))
}

export function shift(prompt, body) {
  const delimiter = requirePrompt(prompt)
  const shiftBody = requireFunction(body, "shift body")
  const operation = delimiter.shiftOperation()
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

export const Continuation = Object.freeze({
  local(state, machine) {
    if (machine === null || typeof machine !== "object") {
      throw new TypeError("resume state machine must be an object")
    }
    requireFunction(machine.resume, "resume state machine method")
    return new LocalContinuationImpl(state, machine)
  }
})
