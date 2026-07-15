import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

import {
  CaptureFailure,
  Continuation,
  Eff,
  MachineStep,
  ResumeMultiplicity,
  Save,
  StateMachine,
  defineEffect,
  freshPrompt,
  handle,
  perform,
  reset,
  shift
} from "@scalascript/control"

function resumeReusable(resumption, value) {
  assert.equal(resumption.kind, "Reusable")
  return resumption.continuation.resume(value)
}

function resumeOneShot(resumption, value) {
  assert.equal(resumption.kind, "OneShot")
  const attempt = resumption.continuation.tryResume(value)
  assert.equal(attempt.ok, true)
  return attempt.computation
}

const One = defineEffect("One")
const OneOp = One.operation("op", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Choice = defineEffect("Vector.Choice")
const Pick = Choice.operation("pick")

const Yield = defineEffect("Vector.Yield")
const Emit = Yield.operation("emit", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Abort = defineEffect("Vector.Abort")
const Stop = Abort.operation("stop", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Amb = defineEffect("Vector.Amb")
const Flip = Amb.operation("flip", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Get = defineEffect("Vector.Get")
const GetValue = Get.operation("get", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Read = defineEffect("Vector.Read")
const ReadValue = Read.operation("read", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Write = defineEffect("Vector.Write")
const WriteValue = Write.operation("write", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Tick = defineEffect("Vector.Tick")
const TickStep = Tick.operation("step", {
  multiplicity: ResumeMultiplicity.OneShot
})

function oneShotResume() {
  const body = perform(OneOp()).map(value => value + 1)
  const handled = handle(body, {
    effect: One,
    onReturn: value => Eff.pure(value),
    onOperation(operation, resumption) {
      assert.equal(OneOp.is(operation), true)
      return resumeOneShot(resumption, 41)
    }
  })
  return String(Eff.runPure(handled))
}

function collectReusable(operation) {
  return handle(perform(operation), {
    effect: Choice,
    onReturn: value => Eff.pure([value]),
    onOperation(request, resumption) {
      assert.equal(Pick.is(request), true)
      const [values] = request.args
      return values.reduce(
        (result, value) => result.flatMap(prefix =>
          resumeReusable(resumption, value).map(suffix => prefix.concat(suffix))
        ),
        Eff.pure([])
      )
    }
  })
}

function multiShotResume() {
  return Eff.runPure(collectReusable(Pick([1, 2]))).join(",")
}

function deepTailCalls() {
  const limit = 2_000_000
  const machine = {
    step(state) {
      return state === limit
        ? MachineStep.Done(state)
        : MachineStep.Continue(state + 1)
    }
  }
  return String(Eff.runPure(StateMachine.run(0, machine)))
}

function callbackReentry() {
  const apply3 = (callback, value) => callback(callback(callback(value)))
  return String(apply3(value => value * 2, 1))
}

function captureResumeSameHost() {
  const body = perform(Emit(10)).flatMap(() =>
    perform(Emit(20)).map(() => 0)
  )
  const handled = handle(body, {
    effect: Yield,
    onReturn: value => Eff.pure(state => value + state),
    onOperation(operation, resumption) {
      assert.equal(Emit.is(operation), true)
      const [value] = operation.args
      return resumeOneShot(resumption, undefined).map(next =>
        state => next(state + value)
      )
    }
  })
  return String(Eff.runPure(handled)(0))
}

function zeroResume() {
  const body = perform(Stop()).map(value => value + 1000)
  const handled = handle(body, {
    effect: Abort,
    onReturn: value => Eff.pure(value),
    onOperation(operation) {
      assert.equal(Stop.is(operation), true)
      return Eff.pure(-1)
    }
  })
  return String(Eff.runPure(handled))
}

function handlerReinstall() {
  function loop(remaining) {
    if (remaining === 0) return Eff.pure(0)
    return perform(Flip()).flatMap(selected =>
      Eff.defer(() => loop(remaining - 1)).map(suffix =>
        (selected ? 1 : 0) + suffix
      )
    )
  }
  const handled = handle(loop(3), {
    effect: Amb,
    onReturn: value => Eff.pure(value),
    onOperation(operation, resumption) {
      assert.equal(Flip.is(operation), true)
      return resumeOneShot(resumption, true)
    }
  })
  return String(Eff.runPure(handled))
}

function returnTransform() {
  const body = perform(GetValue()).map(value => value * 2)
  const handled = handle(body, {
    effect: Get,
    onReturn: value => Eff.pure([value, value]),
    onOperation(operation, resumption) {
      assert.equal(GetValue.is(operation), true)
      return resumeOneShot(resumption, 21)
    }
  })
  return Eff.runPure(handled).join(",")
}

function nondeterminismProduct() {
  const body = perform(Pick([1, 2])).flatMap(first =>
    perform(Pick([10, 20])).map(second => first + second)
  )
  const handled = handle(body, {
    effect: Choice,
    onReturn: value => Eff.pure([value]),
    onOperation(operation, resumption) {
      assert.equal(Pick.is(operation), true)
      const [values] = operation.args
      return values.reduce(
        (result, value) => result.flatMap(prefix =>
          resumeReusable(resumption, value).map(suffix => prefix.concat(suffix))
        ),
        Eff.pure([])
      )
    }
  })
  return Eff.runPure(handled).join(",")
}

function nearestMatchingReset() {
  return String(freshPrompt(prompt => {
    const shifted = shift(prompt, () => Eff.pure(7))
    return Eff.runPure(reset(prompt, () =>
      reset(prompt, () => shifted).map(value => value + 1000)
    ))
  }))
}

function residualForwarding() {
  const body = perform(WriteValue(7)).flatMap(() =>
    perform(ReadValue()).flatMap(value =>
      perform(WriteValue(5)).map(() => value + 7)
    )
  )
  const inner = handle(body, {
    effect: Read,
    onReturn: value => Eff.pure(value + 1),
    onOperation(operation, resumption) {
      assert.equal(ReadValue.is(operation), true)
      return resumeOneShot(resumption, 35)
    }
  })
  const outer = handle(inner, {
    effect: Write,
    onReturn: value => Eff.pure(value + 2),
    onOperation(operation, resumption) {
      assert.equal(WriteValue.is(operation), true)
      const [value] = operation.args
      return resumeOneShot(resumption, undefined).map(next => value + next)
    }
  })
  return String(Eff.runPure(outer))
}

function deepEffectStackSafety() {
  function tailLoop(remaining, sum) {
    if (remaining === 0) return Eff.pure(sum)
    return perform(TickStep()).flatMap(value =>
      Eff.defer(() => tailLoop(remaining - 1, sum + value))
    )
  }

  let sequenceCount = 0
  function sequenceLoop(remaining) {
    if (remaining === 0) return Eff.pure(sequenceCount)
    return perform(TickStep()).flatMap(() => {
      sequenceCount += 1
      return Eff.defer(() => sequenceLoop(remaining - 1))
    })
  }

  function nonTailLoop(remaining) {
    if (remaining === 0) return Eff.pure(0)
    return perform(TickStep()).flatMap(() =>
      Eff.defer(() => nonTailLoop(remaining - 1))
    )
  }

  function escapedLoop(remaining) {
    if (remaining === 0) return Eff.pure(0)
    return perform(Emit(1)).flatMap(() =>
      Eff.defer(() => escapedLoop(remaining - 1))
    )
  }

  function runTick(body, addPerResume, returnDelta) {
    return Eff.runPure(handle(body, {
      effect: Tick,
      onReturn: value => Eff.pure(value + returnDelta),
      onOperation(operation, resumption) {
        assert.equal(TickStep.is(operation), true)
        const resumed = resumeOneShot(resumption, 1)
        return addPerResume ? resumed.map(value => value + 1) : resumed
      }
    }))
  }

  const tail = runTick(tailLoop(100_000, 0), false, 0)
  const sequence = runTick(sequenceLoop(100_000), false, 0)
  const nonTail = runTick(nonTailLoop(20_000), true, 7)
  const escaped = Eff.runPure(handle(escapedLoop(20_000), {
    effect: Yield,
    onReturn: value => Eff.pure(value),
    onOperation(operation, resumption) {
      assert.equal(Emit.is(operation), true)
      const [value] = operation.args
      return resumeOneShot(resumption, undefined).map(next => next + value)
    }
  }))

  return `${tail}|${sequence}|${nonTail}|${escaped}`
}

function oneShotDiagnostic() {
  const handled = handle(perform(OneOp()).map(value => value + 1), {
    effect: One,
    onReturn: value => Eff.pure(String(value)),
    onOperation(operation, resumption) {
      assert.equal(OneOp.is(operation), true)
      assert.equal(resumption.kind, "OneShot")
      const first = resumption.continuation.tryResume(41)
      assert.equal(first.ok, true)
      assert.equal(Eff.runPure(first.computation), "42")
      const second = resumption.continuation.tryResume(0)
      assert.equal(second.ok, false)
      assert.equal(second.rejection.kind, "AlreadyResumed")
      const id = second.rejection.operation
      return Eff.pure(`AlreadyResumed(${id.effect.value}.${id.name})`)
    }
  })
  return Eff.runPure(handled)
}

function freshPromptIsolation() {
  return String(freshPrompt(p =>
    freshPrompt(q => {
      const fromP = shift(p, () => Eff.pure(7))
      const throughQ = reset(q, () => fromP)
      return Eff.runPure(reset(p, () => throughQ.map(value => value + 1000)))
    })
  ))
}

function shiftNotShift0() {
  return String(freshPrompt(prompt => {
    const nested = shift(prompt, () =>
      shift(prompt, inner => inner.resume(11))
    )
    return Eff.runPure(reset(prompt, () => nested))
  }))
}

function multiShotSharedHeap() {
  let cell = 0
  const body = perform(Pick([1, 2])).map(() => {
    cell += 1
    return cell
  })
  const handled = handle(body, {
    effect: Choice,
    onReturn: value => Eff.pure([value]),
    onOperation(operation, resumption) {
      assert.equal(Pick.is(operation), true)
      const [values] = operation.args
      return values.reduce(
        (result, value) => result.flatMap(prefix =>
          resumeReusable(resumption, value).map(suffix => prefix.concat(suffix))
        ),
        Eff.pure([])
      )
    }
  })
  return `${Eff.runPure(handled).join(",")}|${cell}`
}

function unmanagedCapture() {
  const continuation = Continuation.local(40, {
    resume: (state, input) => Eff.pure(state + input)
  })
  const rejected = handle(continuation.save(), {
    effect: Save.key,
    onReturn() {
      assert.fail("local save unexpectedly succeeded")
    },
    onOperation(operation) {
      assert.equal(Save.Rejected.is(operation), true)
      const [failure] = operation.args
      return Eff.pure(failure)
    }
  })
  const failure = Eff.runPure(rejected)
  assert.deepEqual(
    failure,
    CaptureFailure.UnmanagedCapture("Continuation.local")
  )
  return `UnmanagedCapture(${failure.site})`
}

const semanticPrograms = new Map([
  ["01", oneShotResume],
  ["02", multiShotResume],
  ["03", deepTailCalls],
  ["04", callbackReentry],
  ["05", captureResumeSameHost],
  ["06", zeroResume],
  ["07", handlerReinstall],
  ["08", returnTransform],
  ["09", nondeterminismProduct],
  ["18", nearestMatchingReset],
  ["19", residualForwarding],
  ["20", deepEffectStackSafety],
  ["21", oneShotDiagnostic],
  ["22", freshPromptIsolation],
  ["23", shiftNotShift0],
  ["24", multiShotSharedHeap],
  ["25", unmanagedCapture]
])

const catalogUrl = new URL(
  "../../../../../tests/interop-conformance/vectors.tsv",
  import.meta.url
)
const catalogRows = readFileSync(catalogUrl, "utf8")
  .trimEnd()
  .split("\n")
  .slice(1)
  .map(line => {
    const [id, slug, , , phase, , , oracle] = line.split("\t")
    return { id, slug, phase, oracle }
  })
const specified = catalogRows.filter(row => row.phase === "specified")

test("local explicit program coverage agrees with every specified catalog row", () => {
  assert.deepEqual(
    Array.from(semanticPrograms.keys()).sort(),
    specified.map(row => row.id).sort()
  )
})

for (const vector of specified) {
  test(`semantic vector ${vector.id} ${vector.slug}`, () => {
    assert.equal(semanticPrograms.get(vector.id)(), vector.oracle)
  })
}

test("one million left-associated binds are stack safe", () => {
  const limit = 1_000_000
  let body = Eff.pure(0)
  for (let index = 0; index < limit; index += 1) {
    body = body.flatMap(value => Eff.pure(value + 1))
  }
  assert.equal(Eff.runPure(body), limit)
})

test("one million mixed state transitions are stack safe", () => {
  const limit = 1_000_000
  const body = StateMachine.run(0, {
    step(state) {
      if (state === limit) return MachineStep.Done(state)
      return (state & 1) === 0
        ? MachineStep.Continue(state + 1)
        : MachineStep.Evaluate(Eff.pure(state + 1))
    }
  })
  assert.equal(Eff.runPure(body), limit)
})

test("a reusable handler resumes zero, one, and many times", () => {
  function exercise(values) {
    let suffixCalls = 0
    let returnCalls = 0
    const body = perform(Pick(values)).flatMap(value => Eff.defer(() => {
      suffixCalls += 1
      return Eff.pure(value * 10)
    }))
    const handled = handle(body, {
      effect: Choice,
      onReturn(value) {
        returnCalls += 1
        return Eff.pure([value])
      },
      onOperation(operation, resumption) {
        assert.equal(Pick.is(operation), true)
        const [alternatives] = operation.args
        return alternatives.reduce(
          (result, alternative) => result.flatMap(prefix =>
            resumeReusable(resumption, alternative).map(suffix =>
              prefix.concat(suffix)
            )
          ),
          Eff.pure([])
        )
      }
    })
    return [Eff.runPure(handled), suffixCalls, returnCalls]
  }

  assert.deepEqual(exercise([]), [[], 0, 0])
  assert.deepEqual(exercise([4]), [[40], 1, 1])
  assert.deepEqual(exercise([1, 2, 3]), [[10, 20, 30], 3, 3])
})

test("one-shot gate rejects before suffix construction or execution", () => {
  let suffixConstructions = 0
  let suffixExecutions = 0
  let accepted
  const body = perform(OneOp()).flatMap(value => {
    suffixConstructions += 1
    return Eff.defer(() => {
      suffixExecutions += 1
      return Eff.pure(value + 1)
    })
  })
  const handled = handle(body, {
    effect: One,
    onReturn: value => Eff.pure(value),
    onOperation(operation, resumption) {
      assert.equal(OneOp.is(operation), true)
      assert.equal(resumption.kind, "OneShot")
      const first = resumption.continuation.tryResume(41)
      const second = resumption.continuation.tryResume(0)
      assert.equal(first.ok, true)
      assert.deepEqual(second, {
        ok: false,
        rejection: {
          kind: "AlreadyResumed",
          operation: OneOp.id
        }
      })
      assert.equal(suffixConstructions, 0)
      assert.equal(suffixExecutions, 0)
      accepted = first.computation
      return first.computation
    }
  })

  assert.equal(Eff.runPure(handled), 42)
  assert.equal(suffixConstructions, 1)
  assert.equal(suffixExecutions, 1)
  assert.equal(Eff.runPure(accepted), 42)
  assert.equal(suffixConstructions, 2)
  assert.equal(suffixExecutions, 2)
})

test("local continuations are reusable and save rejects deterministically", () => {
  let calls = 0
  const continuation = Continuation.local(40, {
    resume(state, input) {
      calls += 1
      return Eff.pure(state + input)
    }
  })
  assert.equal(Eff.runPure(continuation.resume(2)), 42)
  assert.equal(Eff.runPure(continuation.resume(3)), 43)
  assert.equal(calls, 2)
  assert.equal(unmanagedCapture(), "UnmanagedCapture(Continuation.local)")
  assert.equal(calls, 2)
})

test("same stable effect id does not collapse distinct runtime owners", () => {
  const first = defineEffect("same.id")
  const second = defineEffect("same.id")
  const request = second.operation("read")
  const forwarded = handle(perform(request()), {
    effect: first,
    onReturn: Eff.pure,
    onOperation() {
      assert.fail("distinct effect owner was handled")
    }
  })
  const completed = handle(forwarded, {
    effect: second,
    onReturn: Eff.pure,
    onOperation(operation, resumption) {
      assert.equal(request.is(operation), true)
      return resumeReusable(resumption, 73)
    }
  })
  assert.equal(Eff.runPure(completed), 73)
})
