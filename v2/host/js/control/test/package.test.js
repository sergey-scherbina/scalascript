import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

import * as control from "@scalascript/control"

const expectedRuntimeExports = [
  "CaptureFailure",
  "Continuation",
  "Eff",
  "MachineStep",
  "ResumeMultiplicity",
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
  for (const name of ["preinstall", "install", "postinstall", "prepare"]) {
    assert.equal(packageJson.scripts[name], undefined)
  }
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
