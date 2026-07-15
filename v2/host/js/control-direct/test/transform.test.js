import assert from "node:assert/strict"
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { dirname, join } from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import test from "node:test"

import ts from "typescript"

import { createDirectTransform } from "../transform.js"

const packageRoot = dirname(dirname(fileURLToPath(import.meta.url)))
const scratchRoot = join(packageRoot, ".test-tmp")

function compilerOptions(outDir) {
  return {
    target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.NodeNext,
    moduleResolution: ts.ModuleResolutionKind.NodeNext,
    strict: true,
    exactOptionalPropertyTypes: true,
    noUncheckedIndexedAccess: true,
    skipLibCheck: false,
    rootDir: dirname(outDir),
    outDir,
    sourceMap: true
  }
}

function formatTsDiagnostics(diagnostics) {
  return ts.formatDiagnostics(diagnostics, {
    getCanonicalFileName: value => value,
    getCurrentDirectory: () => packageRoot,
    getNewLine: () => "\n"
  })
}

function prepare(source, name = "case") {
  const directory = mkdtempSync(`${scratchRoot}-`)
  const input = join(directory, `${name}.ts`)
  const outDir = join(directory, "out")
  writeFileSync(input, source)
  const program = ts.createProgram([input], compilerOptions(outDir))
  const transform = createDirectTransform(ts, program)
  return {
    directory,
    input,
    outDir,
    program,
    transform,
    cleanup() {
      rmSync(directory, { recursive: true, force: true })
    }
  }
}

async function compile(source, name = "case") {
  const prepared = prepare(source, name)
  const diagnostics = ts.getPreEmitDiagnostics(prepared.program)
  assert.equal(formatTsDiagnostics(diagnostics), "")
  assert.deepEqual(prepared.transform.diagnostics, [])
  const emit = prepared.program.emit(
    undefined,
    undefined,
    undefined,
    false,
    prepared.transform.transformers
  )
  assert.equal(emit.emitSkipped, false)
  assert.equal(formatTsDiagnostics(emit.diagnostics), "")
  const javascript = join(prepared.outDir, `${name}.js`)
  const sourceMap = `${javascript}.map`
  assert.equal(existsSync(javascript), true)
  assert.equal(existsSync(sourceMap), true)
  const module = await import(`${pathToFileURL(javascript).href}?${Date.now()}`)
  return { ...prepared, javascript, sourceMap, module }
}

const semanticSource = `
import { Eff, freshPrompt, reset, shift } from "@scalascript/control"
import { direct } from "@scalascript/control-direct"

export function zeroMarker(): number {
  return freshPrompt<number, number>(prompt => Eff.runPure(
    direct.reset(prompt, (): number => {
      const value = 40
      return value + 2
    })
  ))
}

export function prefixSuffix(): string {
  let prefix = 0
  let suffix = 0
  const value = freshPrompt<number, number>(prompt => Eff.runPure(
    direct.reset(prompt, (): number => {
      prefix += 1
      const selected: number = direct.shift(prompt, continuation =>
        continuation.resume(41)
      )
      suffix += 1
      return selected + 1
    })
  ))
  return \`${"${value}|${prefix}|${suffix}"}\`
}

export function zeroResume(): string {
  let suffix = 0
  const value = freshPrompt<number, number>(prompt => Eff.runPure(
    direct.reset(prompt, (): number => {
      const selected: number = direct.shift(prompt, () => Eff.pure(-1))
      suffix += 1
      return selected + 1000
    })
  ))
  return \`${"${value}|${suffix}"}\`
}

export function sequential(): number {
  return freshPrompt<number, number>(prompt => Eff.runPure(
    direct.reset(prompt, (): number => {
      const first: number = direct.shift(prompt, continuation =>
        continuation.resume(10)
      )
      const second: number = direct.shift(prompt, continuation =>
        continuation.resume(20)
      )
      return first + second
    })
  ))
}

export function vector18(): string {
  return String(freshPrompt<number, number>(prompt => Eff.runPure(
    reset(prompt, () => direct.reset(prompt, (): number => {
      const value: number = direct.shift(prompt, continuation =>
        continuation.resume(7)
      )
      return value + 1000
    }))
  )))
}

export function vector22(): string {
  return String(freshPrompt<number, number>(p =>
    freshPrompt<number, number>(q => Eff.runPure(
      reset(q, () => direct.reset(p, (): number => {
        const value: number = direct.shift(p, () => Eff.pure(7))
        return value + 1000
      }))
    ))
  ))
}

export function vector23(): string {
  return String(freshPrompt<number, number>(prompt => Eff.runPure(
    direct.reset(prompt, (): number => {
      const value: number = direct.shift(prompt, () =>
        shift(prompt, inner => inner.resume(11))
      )
      return value
    })
  )))
}

export function vector24(): string {
  const values = freshPrompt<readonly number[], readonly number[]>(prompt =>
    Eff.runPure(direct.reset(prompt, (): readonly number[] => {
      let cell = 0
      const ignored: number = direct.shift(prompt, continuation =>
        continuation.resume(1).flatMap(first =>
          continuation.resume(2).map(second => first.concat(second))
        )
      )
      void ignored
      cell += 1
      return [cell]
    }))
  )
  return \`${"${values.join(\",\")}|${values.at(-1)}"}\`
}
`

test("zero/one/many lexical lowering preserves direct semantics", async () => {
  const compiled = await compile(semanticSource, "semantic")
  try {
    assert.equal(compiled.module.zeroMarker(), 42)
    assert.equal(compiled.module.prefixSuffix(), "42|1|1")
    assert.equal(compiled.module.zeroResume(), "-1|0")
    assert.equal(compiled.module.sequential(), 30)
    assert.match(readFileSync(compiled.javascript, "utf8"), /\.flatMap\(/)
    assert.doesNotMatch(readFileSync(compiled.javascript, "utf8"), /direct\.shift/)
  } finally {
    compiled.cleanup()
  }
})

test("catalog 18/22/23/24 direct differentials retain canonical oracles", async () => {
  const compiled = await compile(semanticSource, "catalog")
  try {
    const vectors = new Map([
      ["18", compiled.module.vector18],
      ["22", compiled.module.vector22],
      ["23", compiled.module.vector23],
      ["24", compiled.module.vector24]
    ])
    const rows = readFileSync(
      new URL("../../../../../tests/interop-conformance/vectors.tsv", import.meta.url),
      "utf8"
    ).trimEnd().split("\n").slice(1).map(line => {
      const [id, , , , , , , oracle] = line.split("\t")
      return { id, oracle }
    })
    for (const [id, run] of vectors) {
      const row = rows.find(candidate => candidate.id === id)
      assert.notEqual(row, undefined)
      assert.equal(run(), row.oracle, `catalog vector ${id}`)
    }
  } finally {
    compiled.cleanup()
  }
})

test("emitted source maps retain the original TypeScript source", async () => {
  const compiled = await compile(semanticSource, "mapped")
  try {
    const map = JSON.parse(readFileSync(compiled.sourceMap, "utf8"))
    assert.equal(map.sources.some(source => source.endsWith("mapped.ts")), true)
    assert.equal(map.mappings.length > 0, true)
  } finally {
    compiled.cleanup()
  }
})

test("an aliased exact import transforms and generated names avoid user bindings", async () => {
  const compiled = await compile(`
    import { Eff, freshPrompt } from "@scalascript/control"
    import { direct as d } from "@scalascript/control-direct"
    const __sscControl = "user binding"
    export function answer(): string {
      const value = freshPrompt<number, number>(prompt => Eff.runPure(
        d.reset(prompt, (): number => {
          const selected: number = d.shift(prompt, k => k.resume(42))
          return selected
        })
      ))
      return \`${"${__sscControl}|${value}"}\`
    }
  `, "alias")
  try {
    assert.equal(compiled.module.answer(), "user binding|42")
    const output = readFileSync(compiled.javascript, "utf8")
    assert.doesNotMatch(output, /\bd\.(?:reset|shift)\b/)
    assert.match(output, /__sscControl\d+/)
  } finally {
    compiled.cleanup()
  }
})

function diagnosticFor(body, expectedCode) {
  const source = `
import { Eff, freshPrompt } from "@scalascript/control"
import { direct } from "@scalascript/control-direct"
${body}
`
  const prepared = prepare(source, "negative")
  try {
    const matching = prepared.transform.diagnostics.filter(
      diagnostic => diagnostic.code === expectedCode
    )
    assert.equal(
      matching.length,
      1,
      `${expectedCode}: ${JSON.stringify(prepared.transform.diagnostics)}`
    )
    const diagnostic = matching[0]
    assert.equal(diagnostic.line >= 1, true)
    assert.equal(diagnostic.column >= 1, true)
    assert.equal(diagnostic.length >= 1, true)
    assert.equal(source.slice(diagnostic.start, diagnostic.start + diagnostic.length).length > 0, true)
    return diagnostic
  } finally {
    prepared.cleanup()
  }
}

test("outside reset and prompt mismatch have stable codes and spans", () => {
  const outside = diagnosticFor(
    `export const bad = freshPrompt<number, unknown>(p =>
      direct.shift(p, continuation => continuation.resume(1)))`,
    "JS_DIRECT_OUTSIDE_RESET"
  )
  assert.equal(outside.message.includes("outside"), true)
  assert.equal(outside.length > "direct.shift".length, true)

  const mismatch = diagnosticFor(
    `export const bad = freshPrompt<number, unknown>(p =>
      freshPrompt<number, unknown>(q => direct.reset(p, () => {
        const value: number = direct.shift(q, continuation => continuation.resume(1))
        return value
      })))`,
    "JS_DIRECT_PROMPT_MISMATCH"
  )
  assert.equal(mismatch.message.includes("does not match"), true)
  assert.equal(mismatch.length, 1)
})

test("capture barriers fail closed", () => {
  const barriers = [
    `freshPrompt<number, unknown>(p => direct.reset(p, async () => { return 1 }))`,
    `freshPrompt<number, unknown>(p => direct.reset(p, function* () { yield 1; return 1 }))`,
    `freshPrompt<number, unknown>(p => direct.reset(p, () => { for (;;) { break }; return 1 }))`,
    `freshPrompt<number, unknown>(p => direct.reset(p, () => { while (false) {}; return 1 }))`,
    `freshPrompt<number, unknown>(p => direct.reset(p, () => { switch (1) { case 1: break }; return 1 }))`,
    `freshPrompt<number, unknown>(p => direct.reset(p, () => { try { void 0 } finally { void 0 }; return 1 }))`,
    `freshPrompt<number, unknown>(p => direct.reset(p, () => {
      if (true) { const value = direct.shift(p, k => k.resume(1)); void value }
      return 1
    }))`,
    `freshPrompt<number, unknown>(p => direct.reset(p, () => {
      const callback = () => direct.shift(p, k => k.resume(1)); void callback
      return 1
    }))`,
    `freshPrompt<number, unknown>(p => direct.reset(p, () => {
      class Box { value() { return direct.shift(p, k => k.resume(1)) } }; void Box
      return 1
    }))`
  ]
  for (const [index, expression] of barriers.entries()) {
    diagnosticFor(`export const bad${index} = ${expression}`, "JS_DIRECT_CAPTURE_BARRIER")
  }
})

test("unsupported marker declarations and arbitrary positions fail closed", () => {
  const cases = [
    `freshPrompt<number, unknown>(p => direct.reset(p, () => {
      var value = direct.shift(p, k => k.resume(1)); return value
    }))`,
    `freshPrompt<number, unknown>(p => direct.reset(p, () => {
      const [value] = direct.shift(p, k => k.resume(1)); return value
    }))`,
    `freshPrompt<number, unknown>(p => direct.reset(p, () => {
      const value = 1 + direct.shift(p, k => k.resume(1)); return value
    }))`,
    `freshPrompt<number, unknown>(p => direct.reset(p, () => {
      return direct.shift(p, k => k.resume(1))
    }))`
  ]
  for (const [index, expression] of cases.entries()) {
    diagnosticFor(`export const bad${index} = ${expression}`, "JS_DIRECT_UNSUPPORTED")
  }
})

test("exact imported ownership ignores spelling, comments, strings, and shadowing", () => {
  const source = `
import { direct as marker } from "@scalascript/control-direct"
const text = "marker.reset and marker.shift"
// marker.shift is not syntax
const object = { reset() { return 1 }, shift() { return 2 } }
function local(marker: typeof object) { return marker.reset() + marker.shift() }
export const ordinary = local(object) + text.length
`
  const prepared = prepare(source, "ownership")
  try {
    assert.deepEqual(prepared.transform.diagnostics, [])
    assert.deepEqual(prepared.transform.transformedFiles, [])
  } finally {
    prepared.cleanup()
  }
})
