import assert from "node:assert/strict"
import { spawnSync } from "node:child_process"
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync
} from "node:fs"
import { tmpdir } from "node:os"
import { dirname, join, posix } from "node:path"
import { fileURLToPath } from "node:url"
import test from "node:test"

import * as directPackage from "@scalascript/control-direct"
import * as transformPackage from "@scalascript/control-direct/transform"

const packageRoot = dirname(dirname(fileURLToPath(import.meta.url)))

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
  assert.deepEqual(metadata.devDependencies, { typescript: "5.9.3" })
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

test("exact tarball manifest has no local dependency and installs at a clean boundary", () => {
  const directory = mkdtempSync(join(tmpdir(), "ssc-control-direct-clean-pack-"))
  const packDirectory = join(directory, "pack")
  const extracted = join(directory, "extracted")
  const npm = process.platform === "win32" ? "npm.cmd" : "npm"
  try {
    mkdirSync(packDirectory, { recursive: true })
    mkdirSync(extracted, { recursive: true })
    const packed = spawnSync(
      npm,
      ["pack", packageRoot, "--json", "--pack-destination", packDirectory],
      { cwd: directory, encoding: "utf8" }
    )
    assert.equal(packed.status, 0, packed.stderr)
    const payload = JSON.parse(packed.stdout)
    assert.equal(payload.length, 1)
    const tarball = join(packDirectory, payload[0].filename)
    assert.equal(existsSync(tarball), true)

    const unpacked = spawnSync("tar", ["-xzf", tarball, "-C", extracted], {
      cwd: directory,
      encoding: "utf8"
    })
    assert.equal(unpacked.status, 0, unpacked.stderr)
    const cleanPackage = join(extracted, "package")
    const manifest = JSON.parse(
      readFileSync(join(cleanPackage, "package.json"), "utf8")
    )
    assert.deepEqual(manifest.devDependencies, { typescript: "5.9.3" })
    for (const field of [
      "dependencies",
      "devDependencies",
      "optionalDependencies",
      "peerDependencies",
      "overrides",
      "resolutions"
    ]) {
      for (const specification of Object.values(manifest[field] ?? {})) {
        if (typeof specification !== "string") continue
        assert.doesNotMatch(
          specification,
          /^(?:file:|link:|workspace:|\.\.?[\\/]|[\\/]|[A-Za-z]:[\\/])/
        )
      }
    }
    assert.equal(manifest.bundledDependencies, undefined)
    assert.equal(manifest.bundleDependencies, undefined)
    assert.equal(existsSync(join(extracted, "control")), false)

    const installed = spawnSync(
      npm,
      [
        "install",
        "--ignore-scripts",
        "--no-audit",
        "--no-fund",
        "--no-package-lock"
      ],
      { cwd: cleanPackage, encoding: "utf8" }
    )
    assert.equal(installed.status, 0, installed.stderr)
    assert.equal(
      existsSync(join(cleanPackage, "node_modules", "@scalascript", "control")),
      false
    )
    assert.equal(
      existsSync(join(cleanPackage, "node_modules", "typescript", "package.json")),
      true
    )
    const imported = spawnSync(
      process.execPath,
      [
        "--input-type=module",
        "--eval",
        "await import('@scalascript/control-direct');" +
          "await import('@scalascript/control-direct/transform')"
      ],
      { cwd: cleanPackage, encoding: "utf8" }
    )
    assert.equal(imported.status, 0, imported.stderr)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
