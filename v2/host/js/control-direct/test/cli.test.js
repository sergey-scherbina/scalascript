import assert from "node:assert/strict"
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
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
