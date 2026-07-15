import assert from "node:assert/strict"
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync
} from "node:fs"
import { tmpdir } from "node:os"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { spawnSync } from "node:child_process"
import test from "node:test"

const packageRoot = dirname(dirname(fileURLToPath(import.meta.url)))

function withProject(source, run) {
  const directory = mkdtempSync(join(packageRoot, ".cli-tmp-"))
  try {
    writeFileSync(join(directory, "input.ts"), source)
    writeFileSync(join(directory, "tsconfig.json"), JSON.stringify({
      compilerOptions: {
        target: "ES2022",
        module: "NodeNext",
        moduleResolution: "NodeNext",
        strict: true,
        rootDir: ".",
        outDir: "out",
        sourceMap: true
      },
      include: ["input.ts"]
    }))
    run(directory)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

test("ssc-control-tsc emits transformed JavaScript and source maps", () => {
  withProject(`
    import { Eff, freshPrompt } from "@scalascript/control"
    import { direct } from "@scalascript/control-direct"
    export const answer = freshPrompt<number, unknown>(prompt => Eff.runPure(
      direct.reset(prompt, (): number => {
        const value: number = direct.shift(prompt, k => k.resume(41))
        return value + 1
      })
    ))
  `, directory => {
    const result = spawnSync(
      process.execPath,
      [join(packageRoot, "cli.js"), "--project", join(directory, "tsconfig.json")],
      { cwd: packageRoot, encoding: "utf8" }
    )
    assert.equal(result.status, 0, result.stderr)
    const output = join(directory, "out", "input.js")
    assert.equal(existsSync(output), true)
    assert.equal(existsSync(`${output}.map`), true)
    assert.match(readFileSync(output, "utf8"), /@scalascript\/control/)
    assert.doesNotMatch(readFileSync(output, "utf8"), /@scalascript\/control-direct/)
    assert.doesNotMatch(readFileSync(output, "utf8"), /direct\.shift/)
  })
})

test("ssc-control-tsc preserves native TypeScript diagnostics and emits nothing", () => {
  withProject(`
    import { direct } from "@scalascript/control-direct"
    const value: number = "not a number"
    void direct
    void value
  `, directory => {
    const result = spawnSync(
      process.execPath,
      [join(packageRoot, "cli.js"), "--project", join(directory, "tsconfig.json")],
      { cwd: packageRoot, encoding: "utf8" }
    )
    assert.equal(result.status, 1)
    assert.match(result.stderr, /TS2322/)
    assert.equal(existsSync(join(directory, "out", "input.js")), false)
  })
})

test("ssc-control-tsc rejects transform diagnostics before emit", () => {
  withProject(`
    import { Eff, freshPrompt } from "@scalascript/control"
    import { direct } from "@scalascript/control-direct"
    export const answer = freshPrompt<number, unknown>(prompt => Eff.runPure(
      direct.reset(prompt, (): number => {
        return direct.shift(prompt, k => k.resume(42))
      })
    ))
  `, directory => {
    const result = spawnSync(
      process.execPath,
      [join(packageRoot, "cli.js"), "--project", join(directory, "tsconfig.json")],
      { cwd: packageRoot, encoding: "utf8" }
    )
    assert.equal(result.status, 1)
    assert.match(result.stderr, /JS_DIRECT_UNSUPPORTED/)
    assert.equal(existsSync(join(directory, "out", "input.js")), false)
  })
})

test("packed installed bin uses the consumer compiler and emits production-only imports", () => {
  const directory = mkdtempSync(join(tmpdir(), "ssc-control-direct-packed-"))
  const packDirectory = join(directory, "pack")
  const store = join(directory, "store")
  const consumer = join(directory, "consumer")
  const missingCompiler = join(directory, "missing-compiler")
  const unsupportedCompiler = join(directory, "unsupported-compiler")
  const npm = process.platform === "win32" ? "npm.cmd" : "npm"
  try {
    for (const path of [
      packDirectory,
      store,
      consumer,
      missingCompiler,
      unsupportedCompiler
    ]) {
      mkdirSync(path, { recursive: true })
      writeFileSync(
        join(path, "package.json"),
        JSON.stringify({ private: true, type: "module" })
      )
    }

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

    const installed = spawnSync(
      npm,
      [
        "install",
        "--ignore-scripts",
        "--no-audit",
        "--no-fund",
        "--no-package-lock",
        "--omit=dev",
        tarball
      ],
      { cwd: store, encoding: "utf8" }
    )
    assert.equal(installed.status, 0, installed.stderr)

    const installedPackage = join(
      store,
      "node_modules",
      "@scalascript",
      "control-direct"
    )
    const installedBin = join(store, "node_modules", ".bin", "ssc-control-tsc")
    assert.equal(existsSync(installedPackage), true)
    assert.equal(existsSync(installedBin), true)
    symlinkSync(
      join(packageRoot, "..", "control"),
      join(store, "node_modules", "@scalascript", "control"),
      "dir"
    )

    const consumerModules = join(consumer, "node_modules")
    const consumerScope = join(consumerModules, "@scalascript")
    mkdirSync(consumerScope, { recursive: true })
    symlinkSync(join(packageRoot, "node_modules", "typescript"), join(consumerModules, "typescript"), "dir")
    symlinkSync(installedPackage, join(consumerScope, "control-direct"), "dir")
    symlinkSync(join(packageRoot, "..", "control"), join(consumerScope, "control"), "dir")

    writeFileSync(join(consumer, "input.ts"), `
      import { Eff, freshPrompt } from "@scalascript/control"
      import { direct } from "@scalascript/control-direct"
      const answer = freshPrompt<number, number>(prompt => Eff.runPure(
        direct.reset(prompt, (): number => {
          const value: number = direct.shift(prompt, k => k.resume(41))
          return value + 1
        })
      ))
      console.log(answer)
    `)
    writeFileSync(join(consumer, "tsconfig.json"), JSON.stringify({
      compilerOptions: {
        target: "ES2022",
        module: "NodeNext",
        moduleResolution: "NodeNext",
        strict: true,
        rootDir: ".",
        outDir: "out",
        sourceMap: true
      },
      include: ["input.ts"]
    }))

    const compiled = spawnSync(
      installedBin,
      ["--project", join(consumer, "tsconfig.json")],
      { cwd: consumer, encoding: "utf8" }
    )
    assert.equal(compiled.status, 0, compiled.stderr)
    const output = join(consumer, "out", "input.js")
    assert.equal(existsSync(output), true)
    assert.doesNotMatch(readFileSync(output, "utf8"), /@scalascript\/control-direct/)

    const productionOutputs = [{ path: output, expected: "42" }]
    for (const verbatimModuleSyntax of [false, true]) {
      const emitProject = join(consumer, `emit-channels-${verbatimModuleSyntax}`)
      mkdirSync(emitProject, { recursive: true })
      writeFileSync(join(emitProject, "input.ts"), `
        import {
          direct,
          type DirectMarkerContractError as ErrorType
        } from "@scalascript/control-direct"
        export { type direct as Marker } from "@scalascript/control-direct"
        export type PublicError = ErrorType
        export const answer = 42
        console.log(answer)
      `)
      writeFileSync(join(emitProject, "tsconfig.json"), JSON.stringify({
        compilerOptions: {
          target: "ES2022",
          module: "NodeNext",
          moduleResolution: "NodeNext",
          strict: true,
          declaration: true,
          verbatimModuleSyntax,
          rootDir: ".",
          outDir: "out"
        },
        include: ["input.ts"]
      }))
      const emitted = spawnSync(
        installedBin,
        ["--project", join(emitProject, "tsconfig.json")],
        { cwd: emitProject, encoding: "utf8" }
      )
      assert.equal(emitted.status, 0, emitted.stderr)
      const emittedJavaScript = join(emitProject, "out", "input.js")
      const emittedDeclaration = join(emitProject, "out", "input.d.ts")
      const javascriptText = readFileSync(emittedJavaScript, "utf8")
      assert.doesNotMatch(javascriptText, /@scalascript\/control-direct/)
      const syntax = spawnSync(process.execPath, ["--check", emittedJavaScript], {
        cwd: emitProject,
        encoding: "utf8"
      })
      assert.equal(syntax.status, 0, syntax.stderr)
      const declarationText = readFileSync(emittedDeclaration, "utf8")
      assert.match(declarationText, /DirectMarkerContractError as ErrorType/)
      assert.match(declarationText, /type direct as Marker/)
      assert.match(declarationText, /PublicError = ErrorType/)
      productionOutputs.push({ path: emittedJavaScript, expected: "42" })
    }

    const runtimeImportEquals = join(consumer, "runtime-import-equals")
    mkdirSync(runtimeImportEquals, { recursive: true })
    writeFileSync(join(runtimeImportEquals, "input.ts"), `
      import markers = require("@scalascript/control-direct")
      void markers
    `)
    writeFileSync(join(runtimeImportEquals, "tsconfig.json"), JSON.stringify({
      compilerOptions: {
        target: "ES2022",
        module: "CommonJS",
        moduleResolution: "Node10",
        baseUrl: ".",
        paths: {
          "@scalascript/control-direct": [
            "../node_modules/@scalascript/control-direct/index.d.ts"
          ]
        },
        strict: true,
        rootDir: ".",
        outDir: "out"
      },
      include: ["input.ts"]
    }))
    const rejectedImportEquals = spawnSync(
      installedBin,
      ["--project", join(runtimeImportEquals, "tsconfig.json")],
      { cwd: runtimeImportEquals, encoding: "utf8" }
    )
    assert.equal(rejectedImportEquals.status, 1)
    assert.match(rejectedImportEquals.stderr, /JS_DIRECT_UNSUPPORTED/)
    assert.equal(existsSync(join(runtimeImportEquals, "out", "input.js")), false)

    for (const verbatimModuleSyntax of [false, true]) {
      const typeImportEquals = join(
        consumer,
        `type-import-equals-${verbatimModuleSyntax}`
      )
      mkdirSync(typeImportEquals, { recursive: true })
      writeFileSync(join(typeImportEquals, "input.ts"), `
        import type markers = require("@scalascript/control-direct")
        export type MarkerNamespace = typeof markers
      `)
      writeFileSync(join(typeImportEquals, "tsconfig.json"), JSON.stringify({
        compilerOptions: {
          target: "ES2022",
          module: "CommonJS",
          moduleResolution: "Node10",
          baseUrl: ".",
          paths: {
            "@scalascript/control-direct": [
              "../node_modules/@scalascript/control-direct/index.d.ts"
            ]
          },
          strict: true,
          declaration: true,
          verbatimModuleSyntax,
          rootDir: ".",
          outDir: "out"
        },
        include: ["input.ts"]
      }))
      const acceptedImportEquals = spawnSync(
        installedBin,
        ["--project", join(typeImportEquals, "tsconfig.json")],
        { cwd: typeImportEquals, encoding: "utf8" }
      )
      assert.equal(acceptedImportEquals.status, 0, acceptedImportEquals.stderr)
      const typeJavaScript = join(typeImportEquals, "out", "input.js")
      const typeDeclaration = join(typeImportEquals, "out", "input.d.ts")
      assert.doesNotMatch(
        readFileSync(typeJavaScript, "utf8"),
        /@scalascript\/control-direct/
      )
      const syntax = spawnSync(process.execPath, ["--check", typeJavaScript], {
        cwd: typeImportEquals,
        encoding: "utf8"
      })
      assert.equal(syntax.status, 0, syntax.stderr)
      const declarationText = readFileSync(typeDeclaration, "utf8")
      assert.match(declarationText, /import type markers = require/)
      assert.match(declarationText, /MarkerNamespace = typeof markers/)
    }

    rmSync(join(consumerScope, "control-direct"), { recursive: true, force: true })
    for (const productionOutput of productionOutputs) {
      const production = spawnSync(process.execPath, [productionOutput.path], {
        cwd: consumer,
        encoding: "utf8"
      })
      assert.equal(production.status, 0, production.stderr)
      assert.equal(production.stdout.trim(), productionOutput.expected)
    }

    const invalidOption = spawnSync(installedBin, ["--definitely-invalid"], {
      cwd: consumer,
      encoding: "utf8"
    })
    assert.equal(invalidOption.status, 1)
    assert.match(invalidOption.stderr, /TS5023/)

    const missing = spawnSync(
      installedBin,
      ["--project", join(missingCompiler, "tsconfig.json")],
      { cwd: missingCompiler, encoding: "utf8" }
    )
    assert.equal(missing.status, 1)
    assert.match(missing.stderr, /install typescript@5\.9 in the consuming project/)
    assert.match(missing.stderr, new RegExp(missingCompiler.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")))

    const unsupportedPackage = join(
      unsupportedCompiler,
      "node_modules",
      "typescript"
    )
    mkdirSync(unsupportedPackage, { recursive: true })
    writeFileSync(
      join(unsupportedPackage, "package.json"),
      JSON.stringify({ name: "typescript", version: "6.0.0", main: "index.cjs" })
    )
    writeFileSync(
      join(unsupportedPackage, "index.cjs"),
      "module.exports = { version: '6.0.0', versionMajorMinor: '6.0' }\n"
    )
    const unsupported = spawnSync(
      installedBin,
      ["--project", join(unsupportedCompiler, "tsconfig.json")],
      { cwd: unsupportedCompiler, encoding: "utf8" }
    )
    assert.equal(unsupported.status, 1)
    assert.match(
      unsupported.stderr,
      /@scalascript\/control-direct supports TypeScript 5\.9\.x; received 6\.0\.0/
    )
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
