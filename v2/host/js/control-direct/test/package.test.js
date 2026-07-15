import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { posix } from "node:path"
import test from "node:test"

import * as directPackage from "@scalascript/control-direct"
import * as transformPackage from "@scalascript/control-direct/transform"

const expectedPayload = [
  "LICENSE",
  "README.md",
  "cli.js",
  "index.d.ts",
  "index.js",
  "package.json",
  "transform.d.ts",
  "transform.js"
]

test("root markers expose only the frozen contract and fail if untransformed", () => {
  assert.deepEqual(
    Object.keys(directPackage).sort(),
    ["DirectMarkerContractError", "direct"]
  )
  for (const marker of ["reset", "shift"]) {
    assert.throws(
      () => directPackage.direct[marker](),
      error => error instanceof directPackage.DirectMarkerContractError &&
        error.code === "JS_DIRECT_UNTRANSFORMED" &&
        error.marker === marker
    )
  }
})

test("transform subpath exposes exactly the frozen tooling API", () => {
  assert.deepEqual(
    Object.keys(transformPackage).sort(),
    ["createDirectTransform", "formatDirectDiagnostic"]
  )
})

test("package metadata freezes subpaths, CLI, and zero production dependencies", () => {
  const metadata = JSON.parse(
    readFileSync(new URL("../package.json", import.meta.url), "utf8")
  )
  assert.equal(metadata.name, "@scalascript/control-direct")
  assert.equal(metadata.type, "module")
  assert.equal(metadata.sideEffects, false)
  assert.equal(metadata.license, "Apache-2.0")
  assert.deepEqual(metadata.exports, {
    ".": {
      types: "./index.d.ts",
      import: "./index.js",
      default: "./index.js"
    },
    "./transform": {
      types: "./transform.d.ts",
      import: "./transform.js",
      default: "./transform.js"
    },
    "./package.json": "./package.json"
  })
  assert.deepEqual(metadata.bin, { "ssc-control-tsc": "./cli.js" })
  assert.deepEqual(metadata.files, expectedPayload.filter(name => name !== "package.json"))
  assert.equal(metadata.dependencies, undefined)
  assert.equal(metadata.optionalDependencies, undefined)
  assert.equal(metadata.peerDependencies, undefined)
  for (const name of ["preinstall", "install", "postinstall", "prepare"]) {
    assert.equal(metadata.scripts[name], undefined)
  }
})

test("package carries the repository Apache license verbatim", () => {
  assert.equal(
    readFileSync(new URL("../LICENSE", import.meta.url), "utf8"),
    readFileSync(new URL("../../../../../LICENSE", import.meta.url), "utf8")
  )
})

test("published README links are canonical or resolve inside exact payload", () => {
  const readme = readFileSync(new URL("../README.md", import.meta.url), "utf8")
  const canonical =
    "https://github.com/sergey-scherbina/scalascript/blob/main/" +
    "specs/javascript-typescript-control-direct.md"
  const destinations = Array.from(
    readme.matchAll(/\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g),
    match => match[1]
  )
  assert.equal(destinations.includes(canonical), true)
  const payload = new Set(expectedPayload)
  for (const destination of destinations) {
    if (
      destination.startsWith("#") ||
      destination.startsWith("//") ||
      /^[a-z][a-z0-9+.-]*:/i.test(destination)
    ) continue
    const target = posix.normalize(
      decodeURIComponent(destination.split(/[?#]/, 1)[0])
    ).replace(/^\.\//, "")
    assert.equal(target === ".." || target.startsWith("../") || posix.isAbsolute(target), false)
    assert.equal(payload.has(target), true)
  }
})
