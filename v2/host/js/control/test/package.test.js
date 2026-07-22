import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { posix } from "node:path"
import test from "node:test"

import * as control from "@scalascript/control"

function assertNoOwnAuthorityState(value) {
  assert.deepEqual(Object.keys(value), [])
  assert.deepEqual(Object.getOwnPropertySymbols(value), [])
}

function assertPrivateConstructor(value, ...args) {
  assert.equal(typeof value.constructor, "function")
  assert.throws(
    () => Reflect.construct(value.constructor, args),
    /internal constructor is private/
  )
}

const expectedRuntimeExports = [
  "ArtifactProfile",
  "CapsuleRejected",
  "CaptureFailure",
  "Continuation",
  "DurableBytes",
  "DurableCapsule",
  "DurableCodec",
  "DurableDecodeError",
  "DurableRef",
  "DurableValue",
  "Eff",
  "ResumePoint",
  "MachineStep",
  "ResumeMultiplicity",
  "Restore",
  "ResumeRejected",
  "Save",
  "StateMachine",
  "defineEffect",
  "freshPrompt",
  "handle",
  "perform",
  "reset",
  "shift"
]

test("package self-reference exposes exactly the frozen runtime API", () => {
  assert.deepEqual(Object.keys(control).sort(), expectedRuntimeExports.sort())
})

test("package metadata freezes ESM-only subpaths and zero production dependencies", () => {
  const packageJson = JSON.parse(
    readFileSync(new URL("../package.json", import.meta.url), "utf8")
  )
  assert.equal(packageJson.name, "@scalascript/control")
  assert.equal(packageJson.type, "module")
  assert.equal(packageJson.license, "Apache-2.0")
  assert.deepEqual(packageJson.exports, {
    ".": {
      types: "./index.d.ts",
      import: "./index.js",
      default: "./index.js"
    },
    "./package.json": "./package.json"
  })
  assert.equal(packageJson.dependencies, undefined)
  assert.equal(packageJson.optionalDependencies, undefined)
  assert.equal(packageJson.peerDependencies, undefined)
  assert.deepEqual(packageJson.files, [
    "LICENSE",
    "index.js",
    "index.d.ts",
    "README.md"
  ])
  for (const name of ["preinstall", "install", "postinstall", "prepare"]) {
    assert.equal(packageJson.scripts[name], undefined)
  }
})

test("package carries the repository Apache 2.0 license verbatim", () => {
  const packageLicense = readFileSync(
    new URL("../LICENSE", import.meta.url),
    "utf8"
  )
  const repositoryLicense = readFileSync(
    new URL("../../../../../LICENSE", import.meta.url),
    "utf8"
  )
  assert.equal(packageLicense, repositoryLicense)
})

test("published README links are canonical or resolve inside the payload", () => {
  const packageJson = JSON.parse(
    readFileSync(new URL("../package.json", import.meta.url), "utf8")
  )
  const readme = readFileSync(new URL("../README.md", import.meta.url), "utf8")
  const canonicalProfile =
    "https://github.com/sergey-scherbina/scalascript/blob/main/" +
    "specs/javascript-typescript-bidirectional-control.md"
  const destinations = Array.from(
    readme.matchAll(/\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g),
    match => match[1]
  )
  assert.equal(destinations.includes(canonicalProfile), true)

  const payload = new Set(["package.json", ...packageJson.files])
  for (const destination of destinations) {
    if (
      destination.startsWith("#") ||
      destination.startsWith("//") ||
      /^[a-z][a-z0-9+.-]*:/i.test(destination)
    ) {
      continue
    }

    const target = posix.normalize(
      decodeURIComponent(destination.split(/[?#]/, 1)[0])
    ).replace(/^\.\//, "")
    assert.equal(
      target === ".." || target.startsWith("../") || posix.isAbsolute(target),
      false,
      `README link escapes package payload: ${destination}`
    )
    assert.equal(
      payload.has(target),
      true,
      `README link target is absent from package payload: ${destination}`
    )
  }
})

test("opaque requests hide resumptions and cannot be pre-claimed", () => {
  const owner = Symbol("test.Opacity.owner")
  const effect = control.defineEffect("test.Opacity", owner)
  const once = effect.operation("once", {
    multiplicity: control.ResumeMultiplicity.OneShot
  })
  const operation = once()
  const pending = control.perform(operation)

  assertNoOwnAuthorityState(effect)
  assertNoOwnAuthorityState(operation)
  assertNoOwnAuthorityState(pending)
  assert.equal(effect.id.value, "test.Opacity")
  assert.equal(operation.effect, effect)
  assert.equal(operation.id, once.id)
  assert.equal(operation.multiplicity, control.ResumeMultiplicity.OneShot)
  assert.deepEqual(operation.args, [])
  for (const property of ["operation", "key", "resumption"]) {
    assert.equal(pending[property], undefined)
  }

  assertPrivateConstructor(effect, "test.Forged", Symbol("forged"))
  assertPrivateConstructor(operation, once, [])
  assertPrivateConstructor(pending, operation, effect, {})
  assertPrivateConstructor(control.Eff.pure(0), 42)
  assertPrivateConstructor(control.Eff.defer(() => control.Eff.pure(0)), () => {})
  assertPrivateConstructor(control.Eff.pure(0).map(value => value), null, null)

  const clonedOperation = Object.create(Object.getPrototypeOf(operation))
  assert.throws(() => control.perform(clonedOperation), /not a .* Operation/)
  const graftedComputation = Object.create(Object.getPrototypeOf(pending))
  assert.throws(
    () => graftedComputation.flatMap(value => control.Eff.pure(value)),
    /flatMap receiver is not a .* Eff/
  )
  assert.throws(
    () => control.Eff.runPure(graftedComputation),
    /computation is not a .* Eff/
  )

  let continuation
  const handled = control.handle(pending, {
    effect,
    onReturn: control.Eff.pure,
    onOperation(request, resumption) {
      assert.equal(once.is(request), true)
      assert.equal(resumption.kind, "OneShot")
      continuation = resumption.continuation
      const attempt = continuation.tryResume(42)
      assert.equal(attempt.ok, true)
      return attempt.computation
    }
  })
  assert.equal(control.Eff.runPure(handled), 42)
  assertNoOwnAuthorityState(continuation)
  assertPrivateConstructor(continuation, operation.id, () => control.Eff.pure(0))
})

test("opaque local continuations and prompts hide authority and reject forgery", () => {
  const local = control.Continuation.local(40, {
    resume: (state, input) => control.Eff.pure(state + input)
  })
  assertNoOwnAuthorityState(local)
  assert.equal(local.state, undefined)
  assert.equal(local.machine, undefined)
  assertPrivateConstructor(local, 40, {})
  assert.equal(control.Eff.runPure(local.resume(2)), 42)

  const answer = control.freshPrompt(prompt => {
    assertNoOwnAuthorityState(prompt)
    assert.equal(prompt.key, undefined)
    assert.equal(prompt.shiftOperation, undefined)
    assertPrivateConstructor(prompt)

    const forgedPrompt = Object.create(Object.getPrototypeOf(prompt))
    Object.defineProperties(forgedPrompt, {
      key: { value: {}, enumerable: true },
      shiftOperation: { value: () => {}, enumerable: true }
    })
    assert.throws(
      () => control.reset(forgedPrompt, () => control.Eff.pure(0)),
      /not a .* Prompt/
    )
    assert.throws(
      () => control.shift(forgedPrompt, () => control.Eff.pure(0)),
      /not a .* Prompt/
    )

    return control.Eff.runPure(
      control.reset(prompt, () =>
        control.shift(prompt, continuation => continuation.resume(41))
          .map(value => value + 1)
      )
    )
  })
  assert.equal(answer, 42)
})

test("raw Pure, Op, request, prompt identity, and authority helpers are private", () => {
  for (const name of [
    "Pure",
    "Op",
    "Request",
    "PromptId",
    "step",
    "Authority"
  ]) {
    assert.equal(Object.hasOwn(control, name), false)
  }
})
