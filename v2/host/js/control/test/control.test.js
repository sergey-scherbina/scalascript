import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

import {
  CapsuleRejected,
  CaptureFailure,
  Continuation,
  DurableBytes,
  DurableCapsule,
  DurableCodec,
  DurableDecodeError,
  DurableRef,
  DurableValue,
  Eff,
  MachineStep,
  ResumeMultiplicity,
  ResumePoint,
  Restore,
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

const One = defineEffect("One", Symbol("One.owner"))
const OneOp = One.operation("op", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Choice = defineEffect("Vector.Choice", Symbol("Vector.Choice.owner"))
const Pick = Choice.operation("pick")

const Yield = defineEffect("Vector.Yield", Symbol("Vector.Yield.owner"))
const Emit = Yield.operation("emit", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Abort = defineEffect("Vector.Abort", Symbol("Vector.Abort.owner"))
const Stop = Abort.operation("stop", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Amb = defineEffect("Vector.Amb", Symbol("Vector.Amb.owner"))
const Flip = Amb.operation("flip", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Get = defineEffect("Vector.Get", Symbol("Vector.Get.owner"))
const GetValue = Get.operation("get", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Read = defineEffect("Vector.Read", Symbol("Vector.Read.owner"))
const ReadValue = Read.operation("read", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Write = defineEffect("Vector.Write", Symbol("Vector.Write.owner"))
const WriteValue = Write.operation("write", {
  multiplicity: ResumeMultiplicity.OneShot
})

const Tick = defineEffect("Vector.Tick", Symbol("Vector.Tick.owner"))
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

// Axis 14 — a savable continuation is frozen with save() and run twice in the same
// process; the value is reusable, each run resumes at the capture point.
function durableSaveRunSameProcess() {
  const machine = { resume: (state, input) => Eff.pure(input * 10) }
  const continuation = Continuation.savable(0, machine, DurableCodec.int)
  const saved = Eff.runPure(
    handle(continuation.save(), {
      effect: Save.key,
      onReturn: value => Eff.pure(value),
      onOperation() {
        assert.fail("durable save unexpectedly rejected")
      }
    })
  )
  const first = Eff.runPure(Restore.admitLocally(saved.run(1)))
  const second = Eff.runPure(Restore.admitLocally(saved.run(2)))
  return `${first},${second}`
}

// Axis 17 — the prefix (state construction) runs once at save; running the saved
// continuation twice never re-executes it (the counter stays 1).
function noPrefixMainReplay() {
  let prefixRuns = 0
  const buildState = () => {
    prefixRuns += 1
    return 0
  }
  const machine = { resume: (state, input) => Eff.pure(input * 10) }
  const continuation = Continuation.savable(buildState(), machine, DurableCodec.int)
  const saved = Eff.runPure(
    handle(continuation.save(), {
      effect: Save.key,
      onReturn: value => Eff.pure(value),
      onOperation() {
        assert.fail("durable save unexpectedly rejected")
      }
    })
  )
  const first = Eff.runPure(Restore.admitLocally(saved.run(1)))
  const second = Eff.runPure(Restore.admitLocally(saved.run(2)))
  return `${first},${second},${prefixRuns}`
}

// Axis 11 — a capsule whose frame holds a DurableRef naming resolver "db" is rejected
// at admission (before any run) when "db" is absent; when present it admits and resolves.
function missingResolverReject() {
  const machine = {
    resume: (state, input) => Restore.resolve(state).map(value => value + input)
  }
  const point = ResumePoint.define(
    "resolver-point",
    machine,
    DurableRef.codec(),
    new Set(["db"])
  )
  const capsule = point.freeze(DurableRef.of("db", DurableCodec.int.encode(5)))
  const resolver = { resolve: ref => DurableCodec.int.decode(ref.opaqueReference) }
  // present resolver admits and resolves mid-run (non-vacuous): 5 + 1 = 6
  const admitted = Eff.runPure(
    Restore.withResolver(resolver, point.restore(capsule, new Set(["db"])).run(1))
  )
  assert.equal(admitted, 6)
  // absent resolver rejected at admission, before any run
  try {
    point.restore(capsule, new Set())
    return "NotRejected"
  } catch (error) {
    if (error instanceof CapsuleRejected) return error.kind
    throw error
  }
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
  ["11", missingResolverReject],
  ["14", durableSaveRunSameProcess],
  ["17", noPrefixMainReplay],
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

function freezeSavable(continuation) {
  return Eff.runPure(
    handle(continuation.save(), {
      effect: Save.key,
      onReturn: value => Eff.pure(value),
      onOperation() {
        assert.fail("savable save unexpectedly rejected as an unmanaged capture")
      }
    })
  )
}

test("a savable continuation saves and reruns from the capture point", () => {
  let resumeCalls = 0
  const continuation = Continuation.savable(
    40,
    {
      resume(state, input) {
        resumeCalls += 1
        return Eff.pure(state + input)
      }
    },
    DurableValue.immutable()
  )

  const saved = freezeSavable(continuation)
  assert.equal(resumeCalls, 0) // save does not resume

  assert.equal(Eff.runPure(Restore.admitLocally(saved.run(2))), 42)
  assert.equal(Eff.runPure(Restore.admitLocally(saved.run(3))), 43)
  assert.equal(resumeCalls, 2)
})

test("savable save/run never replays the prefix", () => {
  let prefixRuns = 0
  const prefixState = () => {
    prefixRuns += 1
    return 40
  }
  const continuation = Continuation.savable(
    prefixState(),
    { resume: (state, input) => Eff.pure(state + input) },
    DurableValue.immutable()
  )

  const saved = freezeSavable(continuation)
  assert.equal(prefixRuns, 1)

  Eff.runPure(Restore.admitLocally(saved.run(1)))
  Eff.runPure(Restore.admitLocally(saved.run(2)))
  assert.equal(prefixRuns, 1) // the prefix is never re-executed
})

test("each savable run reconstructs an independent frame (snapshot law)", () => {
  const codec = DurableValue.copying(array => array.slice())
  const continuation = Continuation.savable(
    [100],
    {
      resume(state, input) {
        state[0] += input // mutate only this run's frame
        return Eff.pure(state[0])
      }
    },
    codec
  )

  const saved = freezeSavable(continuation)
  // The original array captured above is snapshotted at save time; mutating a
  // fresh reference cannot reach it, and each run clones the saved frame.
  assert.equal(Eff.runPure(Restore.admitLocally(saved.run(1))), 101)
  assert.equal(Eff.runPure(Restore.admitLocally(saved.run(5))), 105)
})

test("same stable effect id does not collapse distinct runtime owners", () => {
  assert.throws(
    () => defineEffect("missing.owner"),
    /effect owner must be a symbol/
  )
  const firstOwner = Symbol("same.id.first")
  const secondOwner = Symbol("same.id.second")
  const first = defineEffect("same.id", firstOwner)
  const firstAgain = defineEffect("same.id", firstOwner)
  const second = defineEffect("same.id", secondOwner)
  assert.equal(firstAgain, first)
  assert.throws(
    () => defineEffect("different.id", firstOwner),
    /already bound to descriptor same\.id/
  )
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

test("durable codec scalars and combinators round-trip", () => {
  assert.equal(DurableCodec.boolean.decode(DurableCodec.boolean.encode(true)), true)
  for (const value of [0, 1, -1, -2147483648, 2147483647]) {
    assert.equal(DurableCodec.int.decode(DurableCodec.int.encode(value)), value)
  }
  for (const value of [0n, -1n, 9223372036854775807n, -9223372036854775808n]) {
    assert.equal(DurableCodec.long.decode(DurableCodec.long.encode(value)), value)
  }
  for (const value of [0n, -1n, 128n, 255n, 123456789012345678901234567890n]) {
    assert.equal(DurableCodec.bigInt.decode(DurableCodec.bigInt.encode(value)), value)
  }
  for (const value of ["", "hello", "юникод ✓ 𝟛"]) {
    assert.equal(DurableCodec.string.decode(DurableCodec.string.encode(value)), value)
  }
  const composite = DurableCodec.either(
    DurableCodec.string,
    DurableCodec.pair(DurableCodec.int, DurableCodec.boolean)
  )
  assert.deepEqual(composite.decode(composite.encode({ right: [7, true] })), { right: [7, true] })
  assert.deepEqual(composite.decode(composite.encode({ left: "x" })), { left: "x" })
  const listCodec = DurableCodec.list(DurableCodec.int)
  assert.deepEqual(listCodec.decode(listCodec.encode([1, 2, 3])), [1, 2, 3])
})

test("durable double encoding is canonical: signed zero kept, NaN normalized", () => {
  assert.notEqual(
    DurableCodec.double.encode(-0.0).toHex(),
    DurableCodec.double.encode(0.0).toHex()
  )
  assert.equal(DurableCodec.double.encode(NaN).toHex(), "7ff8000000000000")
  assert.ok(Number.isNaN(DurableCodec.double.decode(DurableCodec.double.encode(NaN))))
  for (const value of [1.5, -2.25, Infinity, -Infinity]) {
    assert.equal(DurableCodec.double.decode(DurableCodec.double.encode(value)), value)
  }
})

// The SAME golden hex table asserted by the Scala lane (DurableCodecTest). Matching
// hex on both lanes proves the wire format is byte-identical across lanes.
test("durable codec golden byte vectors match the cross-lane format", () => {
  const hex = value => value.toHex()
  assert.equal(hex(DurableCodec.boolean.encode(true)), "01")
  assert.equal(hex(DurableCodec.boolean.encode(false)), "00")
  assert.equal(hex(DurableCodec.int.encode(1)), "00000001")
  assert.equal(hex(DurableCodec.int.encode(-1)), "ffffffff")
  assert.equal(hex(DurableCodec.int.encode(-2147483648)), "80000000")
  assert.equal(hex(DurableCodec.long.encode(1n)), "0000000000000001")
  assert.equal(hex(DurableCodec.long.encode(-1n)), "ffffffffffffffff")
  assert.equal(hex(DurableCodec.double.encode(1.5)), "3ff8000000000000")
  assert.equal(hex(DurableCodec.double.encode(-0.0)), "8000000000000000")
  assert.equal(hex(DurableCodec.double.encode(NaN)), "7ff8000000000000")
  assert.equal(hex(DurableCodec.double.encode(Infinity)), "7ff0000000000000")
  assert.equal(hex(DurableCodec.string.encode("")), "00000000")
  assert.equal(hex(DurableCodec.string.encode("A")), "0000000141")
  assert.equal(hex(DurableCodec.string.encode("é")), "00000002c3a9")
  assert.equal(hex(DurableCodec.bigInt.encode(0n)), "0000000100")
  assert.equal(hex(DurableCodec.bigInt.encode(127n)), "000000017f")
  assert.equal(hex(DurableCodec.bigInt.encode(128n)), "000000020080")
  assert.equal(hex(DurableCodec.bigInt.encode(-1n)), "00000001ff")
  assert.equal(hex(DurableCodec.bigInt.encode(256n)), "000000020100")
  assert.equal(hex(DurableCodec.list(DurableCodec.int).encode([1, 2])), "000000020000000100000002")
  assert.equal(hex(DurableCodec.list(DurableCodec.int).encode([])), "00000000")
  assert.equal(hex(DurableCodec.pair(DurableCodec.int, DurableCodec.boolean).encode([7, true])), "0000000701")
  assert.equal(
    hex(DurableCodec.either(DurableCodec.int, DurableCodec.string).encode({ left: 5 })),
    "0000000005"
  )
  assert.equal(
    hex(DurableCodec.either(DurableCodec.int, DurableCodec.string).encode({ right: "A" })),
    "010000000141"
  )
})

test("durable codec decoding is bounded and exact", () => {
  const fourBytes = DurableCodec.int.encode(5).toArray()
  assert.throws(
    () => DurableCodec.int.decode(DurableBytes.fromArray(fourBytes.slice(0, 3))),
    DurableDecodeError
  )
  assert.throws(
    () => DurableCodec.int.decode(DurableBytes.fromArray([...fourBytes, 0])),
    DurableDecodeError
  )
  assert.throws(
    () => DurableCodec.either(DurableCodec.int, DurableCodec.int).decode(DurableBytes.fromArray([2, 0, 0, 0, 0])),
    DurableDecodeError
  )
  assert.throws(
    () => DurableCodec.string.decode(DurableBytes.fromArray([0x7f, 0x7f, 0x7f, 0x7f])),
    DurableDecodeError
  )
  assert.throws(
    () => DurableCodec.list(DurableCodec.int).decode(DurableBytes.fromArray([0x02, 0, 0, 0])),
    DurableDecodeError
  )
})

test("a durable codec is usable as savable evidence", () => {
  const cellCodec = DurableCodec.imap(
    DurableCodec.int,
    bits => ({ value: bits }),
    cell => cell.value
  )
  const machine = {
    resume(state, input) {
      state.value += input
      return Eff.pure(state.value)
    }
  }
  const continuation = Continuation.savable({ value: 100 }, machine, cellCodec)
  const saved = Eff.runPure(
    handle(continuation.save(), {
      effect: Save.key,
      onReturn: value => Eff.pure(value),
      onOperation() {
        assert.fail("codec-backed savable save unexpectedly rejected")
      }
    })
  )
  assert.equal(Eff.runPure(Restore.admitLocally(saved.run(1))), 101)
  assert.equal(Eff.runPure(Restore.admitLocally(saved.run(5))), 105)
})

const cellCodec = DurableCodec.imap(
  DurableCodec.int,
  bits => ({ value: bits }),
  cell => cell.value
)
const cellMachine = {
  resume(state, input) {
    state.value += input
    return Eff.pure(state.value)
  }
}
const runRestored = (saved, input) => Eff.runPure(Restore.admitLocally(saved.run(input)))

test("freeze -> encode -> decode -> restore -> run reproduces the state", () => {
  const point = ResumePoint.define("cell", cellMachine, cellCodec)
  const capsule = point.freeze({ value: 100 })
  const transported = DurableCapsule.decode(capsule.encode())
  assert.equal(transported.resumePointId, "cell")
  assert.equal(transported.formatVersion, 1)
  const saved = point.restore(transported)
  assert.equal(runRestored(saved, 1), 101)
  assert.equal(runRestored(saved, 5), 105)
})

test("a tampered frame is rejected at restore, not at decode", () => {
  const point = ResumePoint.define("cell", cellMachine, cellCodec)
  const raw = point.freeze({ value: 7 }).encode().toArray()
  raw[raw.length - 1] ^= 0xff // corrupt the stored digest
  const tampered = DurableCapsule.decode(DurableBytes.fromArray(raw)) // inert decode
  assert.throws(() => point.restore(tampered), CapsuleRejected)
  // non-vacuous: the clean capsule still admits.
  const clean = point.restore(DurableCapsule.decode(point.freeze({ value: 7 }).encode()))
  assert.equal(runRestored(clean, 0), 7)
})

test("a capsule cannot be restored on a different resume point", () => {
  const producer = ResumePoint.define("producer", cellMachine, cellCodec)
  const other = ResumePoint.define("other", cellMachine, cellCodec)
  const capsule = producer.freeze({ value: 3 })
  assert.throws(() => other.restore(capsule), CapsuleRejected)
  assert.equal(runRestored(producer.restore(capsule), 4), 7)
})

test("an unsupported capsule format version is rejected", () => {
  const point = ResumePoint.define("cell", cellMachine, cellCodec)
  const raw = point.freeze({ value: 1 }).encode().toArray()
  raw[3] = 2 // version int is big-endian in bytes 0..3
  const badVersion = DurableCapsule.decode(DurableBytes.fromArray(raw))
  assert.equal(badVersion.formatVersion, 2)
  assert.throws(() => point.restore(badVersion), CapsuleRejected)
})

test("capsule decoding is bounded and exact", () => {
  const raw = ResumePoint.define("cell", cellMachine, cellCodec).freeze({ value: 9 }).encode().toArray()
  assert.throws(
    () => DurableCapsule.decode(DurableBytes.fromArray(raw.slice(0, raw.length - 1))),
    DurableDecodeError
  )
  assert.throws(
    () => DurableCapsule.decode(DurableBytes.fromArray([...raw, 0])),
    DurableDecodeError
  )
})

// Cross-lane golden capsule: the exact bytes for resume point "cell" freezing the
// int state 100. The embedded 32-byte digest was computed independently by Node's
// crypto SHA-256, so a match proves this lane's hand-rolled SHA-256 AND the envelope
// encoding agree with the Scala reference lane byte-for-byte.
test("golden capsule bytes match the cross-lane format", () => {
  const point = ResumePoint.define("cell", cellMachine, cellCodec)
  assert.equal(
    point.freeze({ value: 100 }).encode().toHex(),
    "000000010000000463656c6c0000000400000064000000204b458482422640f4fb818274ec2b4f3d1de3a487c25f991d751e483fdc0aea9b"
  )
})

const memRef = value => DurableRef.of("mem", DurableCodec.int.encode(value))

function countingResolver() {
  const resolver = {
    calls: 0,
    resolve(ref) {
      resolver.calls += 1
      assert.equal(ref.providerId, "mem")
      return DurableCodec.int.decode(ref.opaqueReference)
    }
  }
  return resolver
}

const addAfterResolve = {
  resume(state, input) {
    return Restore.resolve(state).map(value => value + input)
  }
}

function freezeRefSavable(continuation) {
  return Eff.runPure(
    handle(continuation.save(), {
      effect: Save.key,
      onReturn: value => Eff.pure(value),
      onOperation() {
        assert.fail("DurableRef savable save unexpectedly rejected")
      }
    })
  )
}

test("DurableRef codec round-trips inert reference data without resolving", () => {
  const codec = DurableRef.codec()
  const decoded = codec.decode(codec.encode(memRef(99)))
  assert.equal(decoded.providerId, "mem")
  assert.equal(DurableCodec.int.decode(decoded.opaqueReference), 99)
})

test("a savable frame that is a DurableRef resolves post-admission", () => {
  const resolver = countingResolver()
  const continuation = Continuation.savable(memRef(40), addAfterResolve, DurableRef.codec())
  const saved = freezeRefSavable(continuation)
  assert.equal(resolver.calls, 0)
  assert.equal(Eff.runPure(Restore.withResolver(resolver, saved.run(2))), 42)
  assert.equal(Eff.runPure(Restore.withResolver(resolver, saved.run(5))), 45)
  assert.equal(resolver.calls, 2) // once per admitted run, independently
})

test("withResolver resolves once per resolve in a run", () => {
  const resolver = countingResolver()
  const twiceMachine = {
    resume(state, input) {
      return Restore.resolve(state).flatMap(first =>
        Restore.resolve(state).map(second => first + second + input)
      )
    }
  }
  const continuation = Continuation.savable(memRef(10), twiceMachine, DurableRef.codec())
  const saved = freezeRefSavable(continuation)
  assert.equal(Eff.runPure(Restore.withResolver(resolver, saved.run(1))), 21)
  assert.equal(resolver.calls, 2)
})

test("admitLocally rejects a run that resolves a DurableRef", () => {
  const continuation = Continuation.savable(memRef(1), addAfterResolve, DurableRef.codec())
  const saved = freezeRefSavable(continuation)
  assert.throws(() => Eff.runPure(Restore.admitLocally(saved.run(1))), TypeError)
})

test("a capsule whose frame is a DurableRef decodes inert and resolves on run", () => {
  const resolver = countingResolver()
  const point = ResumePoint.define("ref-point", addAfterResolve, DurableRef.codec())
  const capsule = point.freeze(memRef(100))
  const transported = DurableCapsule.decode(capsule.encode())
  assert.equal(resolver.calls, 0) // decoding contacts no resource
  const saved = point.restore(transported)
  assert.equal(resolver.calls, 0) // restore admits but does not resolve
  assert.equal(Eff.runPure(Restore.withResolver(resolver, saved.run(1))), 101)
  assert.equal(resolver.calls, 1)
})

const bytesFromHex = hex =>
  DurableBytes.fromArray(hex.match(/../g).map(pair => parseInt(pair, 16)))

test("map codec round-trips and canonicalizes key order", () => {
  const codec = DurableCodec.map(DurableCodec.string, DurableCodec.int)
  for (const value of [new Map(), new Map([["a", 1]]), new Map([["b", 2], ["a", 1], ["c", 3]])]) {
    assert.deepEqual(codec.decode(codec.encode(value)), value)
  }
  // insertion order does not affect the canonical bytes.
  assert.equal(
    codec.encode(new Map([["b", 2], ["a", 1]])).toHex(),
    codec.encode(new Map([["a", 1], ["b", 2]])).toHex()
  )
})

// The SAME golden hex as the Scala lane: built "b" then "a", bytes sorted a, b.
test("map codec golden bytes match the cross-lane canonical order", () => {
  const codec = DurableCodec.map(DurableCodec.string, DurableCodec.int)
  assert.equal(
    codec.encode(new Map([["b", 2], ["a", 1]])).toHex(),
    "00000002000000016100000001000000016200000002"
  )
})

test("map codec rejects non-canonical key order on decode", () => {
  const codec = DurableCodec.map(DurableCodec.string, DurableCodec.int)
  const nonCanonical = bytesFromHex("00000002000000016200000002000000016100000001")
  assert.throws(() => codec.decode(nonCanonical), DurableDecodeError)
})

// Nominal versioned-schema identity (§9.1, specs/durable-nominal-schema.md): header
// is string(schemaId) ++ int(version); decode rejects a mismatched name or version.
const pointCodec = (id, version) =>
  DurableCodec.schema(id, version, DurableCodec.pair(DurableCodec.int, DurableCodec.int))

test("nominal schema codec round-trips a stamped value", () => {
  const codec = pointCodec("Point", 1)
  assert.deepEqual(codec.decode(codec.encode([3, 4])), [3, 4])
})

test("nominal schema codec rejects a mismatched schema name on decode", () => {
  assert.throws(() => pointCodec("Line", 1).decode(pointCodec("Point", 1).encode([3, 4])), DurableDecodeError)
})

test("nominal schema codec rejects a mismatched schema version on decode", () => {
  assert.throws(() => pointCodec("Point", 2).decode(pointCodec("Point", 1).encode([3, 4])), DurableDecodeError)
})

// The SAME golden hex as the Scala lane (DurableCodecTest) — matching hex proves the
// schema header is byte-identical across lanes.
test("nominal schema codec golden bytes match the cross-lane format", () => {
  assert.equal(
    pointCodec("Point", 1).encode([3, 4]).toHex(),
    "00000005506f696e74000000010000000300000004"
  )
})
