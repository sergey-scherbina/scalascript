import assert from "node:assert/strict"
import { spawnSync } from "node:child_process"
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

function prepare(source, name = "case", optionOverrides = {}) {
  const directory = mkdtempSync(`${scratchRoot}-`)
  const fixtureScope = join(directory, "node_modules", "@scalascript")
  mkdirSync(fixtureScope, { recursive: true })
  symlinkSync(
    join(packageRoot, "..", "control"),
    join(fixtureScope, "control"),
    "dir"
  )
  const extension = optionOverrides.allowJs === true ? "js" : "ts"
  const input = join(directory, `${name}.${extension}`)
  const outDir = join(directory, "out")
  writeFileSync(input, source)
  const program = ts.createProgram(
    [input],
    { ...compilerOptions(outDir), ...optionOverrides }
  )
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

async function compile(source, name = "case", optionOverrides = {}) {
  const prepared = prepare(source, name, optionOverrides)
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
    const output = readFileSync(compiled.javascript, "utf8")
    assert.match(output, /\.flatMap\(/)
    assert.doesNotMatch(output, /direct\.shift/)
    assert.doesNotMatch(output, /@scalascript\/control-direct/)
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
    assert.doesNotMatch(output, /@scalascript\/control-direct/)
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
  diagnosticFor(
    `export const optional = freshPrompt<number, number>(p =>
      direct?.reset(p, (): number => { return 1 }))`,
    "JS_DIRECT_UNSUPPORTED"
  )
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
    assert.deepEqual(prepared.transform.transformedFiles, [prepared.input])
    const emit = prepared.program.emit(
      undefined,
      undefined,
      undefined,
      false,
      prepared.transform.transformers
    )
    assert.equal(emit.emitSkipped, false)
    assert.doesNotMatch(
      readFileSync(join(prepared.outDir, "ownership.js"), "utf8"),
      /@scalascript\/control-direct/
    )
  } finally {
    prepared.cleanup()
  }
})

test("transparent TypeScript wrappers preserve exact marker ownership", async () => {
  const compiled = await compile(`
    import { Eff, freshPrompt } from "@scalascript/control"
    import { direct } from "@scalascript/control-direct"
    export function answer(): number {
      return freshPrompt<number, number>(prompt => Eff.runPure(
        (direct as typeof direct).reset(prompt, (): number => {
          const first: number = (direct!).shift(prompt, k => k.resume(20))
          const second: number = (direct).shift(prompt, k => k.resume(22))
          return first + second
        })
      ))
    }
  `, "wrapped")
  try {
    assert.equal(compiled.module.answer(), 42)
    const output = readFileSync(compiled.javascript, "utf8")
    assert.doesNotMatch(output, /@scalascript\/control-direct/)
    assert.doesNotMatch(output, /\bdirect\b/)
  } finally {
    compiled.cleanup()
  }
})

test("shift bodies fail closed on own or forward lexical capture", () => {
  const forward = diagnosticFor(
    `let saved: () => number = () => -1
    export const bad = freshPrompt<number, number>(p => Eff.runPure(
      direct.reset(p, (): number => {
        const selected: number = direct.shift(p, k => {
          saved = () => later
          return k.resume(1)
        })
        const later = 42
        return selected + saved()
      })
    ))`,
    "JS_DIRECT_CAPTURE_BARRIER"
  )
  assert.equal(forward.message.includes("later continuation binding"), true)

  const own = diagnosticFor(
    `let saved: () => number = () => -1
    export const bad = freshPrompt<number, number>(p => Eff.runPure(
      direct.reset(p, (): number => {
        const selected: number = direct.shift(p, k => {
          saved = () => selected
          return k.resume(1)
        })
        return saved()
      })
    ))`,
    "JS_DIRECT_CAPTURE_BARRIER"
  )
  assert.equal(own.message.includes("own marker"), true)
})

test("shorthand value symbols cannot hide a suffix capture", () => {
  const bodies = [
    `const selected = direct.shift(p, k => {
      const captured = ({ later }).later
      return k.resume(captured)
    })`,
    `const selected = direct.shift(p, k => {
      ({ later = 0 } = {})
      return k.resume(1)
    })`
  ]
  for (const [index, marker] of bodies.entries()) {
    const source = `
import { Eff, freshPrompt } from "@scalascript/control"
import { direct } from "@scalascript/control-direct"
export function bad${index}() {
  return freshPrompt(p => Eff.runPure(direct.reset(p, () => {
    ${marker}
    const later = 42
    return selected + later
  })))
}
`
    const prepared = prepare(
      source,
      `shorthand-capture-${index}`,
      { allowJs: true, checkJs: false, verbatimModuleSyntax: true }
    )
    try {
      const matching = prepared.transform.diagnostics.filter(
        diagnostic => diagnostic.code === "JS_DIRECT_CAPTURE_BARRIER"
      )
      assert.equal(matching.length, 1)
      assert.equal(
        source.slice(matching[0].start, matching[0].start + matching[0].length),
        "later"
      )
      assert.deepEqual(prepared.transform.transformedFiles, [])
      const emit = prepared.program.emit(
        undefined,
        undefined,
        undefined,
        false,
        prepared.transform.transformers
      )
      assert.equal(emit.emitSkipped, false)
      const output = readFileSync(
        join(prepared.outDir, `shorthand-capture-${index}.js`),
        "utf8"
      )
      assert.match(output, /@scalascript\/control-direct/)
      assert.match(output, /direct\.reset/)
      assert.doesNotMatch(output, /__sscControl/)
    } finally {
      prepared.cleanup()
    }
  }
})

test("real JavaScript prefix references cannot cross into a marker suffix binding", () => {
  const source = `
import { Eff, freshPrompt } from "@scalascript/control"
import { direct } from "@scalascript/control-direct"
const later = 99
export function bad() {
  return freshPrompt(prompt => Eff.runPure(direct.reset(prompt, () => {
    const before = later
    const selected = direct.shift(prompt, continuation => continuation.resume(1))
    const later = 42
    return before + selected + later
  })))
}
`
  const prepared = prepare(
    source,
    "prefix-tdz",
    { allowJs: true, checkJs: false, verbatimModuleSyntax: true }
  )
  try {
    const matching = prepared.transform.diagnostics.filter(
      diagnostic => diagnostic.code === "JS_DIRECT_CAPTURE_BARRIER"
    )
    assert.equal(matching.length, 1)
    const diagnostic = matching[0]
    assert.equal(
      source.slice(diagnostic.start, diagnostic.start + diagnostic.length),
      "later"
    )
    assert.deepEqual(prepared.transform.transformedFiles, [])

    const emit = prepared.program.emit(
      undefined,
      undefined,
      undefined,
      false,
      prepared.transform.transformers
    )
    assert.equal(emit.emitSkipped, false)
    const output = readFileSync(join(prepared.outDir, "prefix-tdz.js"), "utf8")
    assert.match(output, /@scalascript\/control-direct/)
    assert.match(output, /direct\.reset/)
    assert.doesNotMatch(output, /__sscControl/)
  } finally {
    prepared.cleanup()
  }
})

test("type-only prefix references do not create a runtime suffix capture", async () => {
  const compiled = await compile(`
    import { Eff, freshPrompt } from "@scalascript/control"
    import { direct } from "@scalascript/control-direct"
    export const answer = freshPrompt<number, number>(prompt => Eff.runPure(
      direct.reset(prompt, (): number => {
        const before: typeof later = 42
        const selected: number = direct.shift(
          prompt,
          continuation => continuation.resume(1)
        )
        const later = 42
        return before + selected + later
      })
    ))
  `, "prefix-type-only")
  try {
    assert.equal(compiled.module.answer, 85)
  } finally {
    compiled.cleanup()
  }
})

test("preceding and shadowed bindings retain source evaluation order", async () => {
  const compiled = await compile(`
    import { Eff, freshPrompt } from "@scalascript/control"
    import { direct } from "@scalascript/control-direct"
    export function answer(): string {
      const events: string[] = []
      const value = freshPrompt<number, number>(p => Eff.runPure(
        direct.reset(p, (): number => {
          const before = (events.push("before"), 40)
          const selected: number = direct.shift(p, k => {
            const later = before + 1
            return k.resume(later)
          })
          const later = (events.push("later"), 1)
          return selected + later
        })
      ))
      return \`${"${value}|${events.join(\",\")}"}\`
    }
  `, "lexical-safe")
  try {
    assert.equal(compiled.module.answer(), "42|before,later")
  } finally {
    compiled.cleanup()
  }
})

test("real JavaScript retains const/let declarations and fresh resume names", async () => {
  const compiled = await compile(`
    import { Eff, freshPrompt } from "@scalascript/control"
    import { direct } from "@scalascript/control-direct"

    export function constAssignment() {
      return freshPrompt(p => Eff.runPure(direct.reset(p, () => {
        const value = direct.shift(p, k => k.resume(41))
        value = 0
        return value + 1
      })))
    }

    export function letMutation() {
      return freshPrompt(p => Eff.runPure(direct.reset(p, () => {
        let value = direct.shift(p, k => k.resume(41))
        value += 1
        return value
      })))
    }

    export function collision() {
      return freshPrompt(p => Eff.runPure(direct.reset(p, () => {
        const value = direct.shift(p, k => k.resume(41))
        const __sscResume = 1
        return value + __sscResume
      })))
    }
  `, "javascript-marker", { allowJs: true, checkJs: false })
  try {
    assert.throws(() => compiled.module.constAssignment(), TypeError)
    assert.equal(compiled.module.letMutation(), 42)
    assert.equal(compiled.module.collision(), 42)
    const output = readFileSync(compiled.javascript, "utf8")
    assert.match(output, /const value = __sscResume/)
    assert.match(output, /let value = __sscResume/)
    assert.doesNotMatch(output, /@scalascript\/control-direct/)
    const map = JSON.parse(readFileSync(compiled.sourceMap, "utf8"))
    assert.equal(map.sources.some(source => source.endsWith("javascript-marker.js")), true)
  } finally {
    compiled.cleanup()
  }
})

test("intrinsic direct eval is a transparent-wrapper-aware file barrier", () => {
  const reset = `export const answer = freshPrompt<number, number>(p => Eff.runPure(
    direct.reset(p, (): number => { return 42 })
  ))`
  const forms = [
    `eval("void 0")`,
    `(eval)("void 0")`,
    `(eval as typeof eval)("void 0")`,
    `eval!("void 0")`,
    `(<typeof eval>eval)("void 0")`,
    `(() => eval("void 0"))`
  ]
  for (const [index, expression] of forms.entries()) {
    const diagnostic = diagnosticFor(
      `const blocked${index} = ${expression}\n${reset}`,
      "JS_DIRECT_CAPTURE_BARRIER"
    )
    assert.equal(diagnostic.message.includes("direct eval"), true)
  }
  diagnosticFor(
    `declare function eval(source: string): unknown
    eval("void 0")
    ${reset}`,
    "JS_DIRECT_CAPTURE_BARRIER"
  )
})

test("import-only marker erasure is also protected from intrinsic direct eval", async () => {
  const source = `
import { direct } from "@scalascript/control-direct"
export const observed = eval("typeof direct")
`
  const prepared = prepare(
    source,
    "import-only-eval",
    { verbatimModuleSyntax: true }
  )
  try {
    assert.equal(prepared.transform.diagnostics.length, 1)
    assert.equal(
      prepared.transform.diagnostics[0].code,
      "JS_DIRECT_CAPTURE_BARRIER"
    )
    assert.deepEqual(prepared.transform.transformedFiles, [])
    const emit = prepared.program.emit(
      undefined,
      undefined,
      undefined,
      false,
      prepared.transform.transformers
    )
    assert.equal(emit.emitSkipped, false)
    const javascript = join(prepared.outDir, "import-only-eval.js")
    const output = readFileSync(javascript, "utf8")
    assert.match(output, /@scalascript\/control-direct/)
    assert.match(output, /eval\("typeof direct"\)/)
    const module = await import(`${pathToFileURL(javascript).href}?${Date.now()}`)
    assert.equal(module.observed, "object")
  } finally {
    prepared.cleanup()
  }
})

test("shadowed and indirect eval plus Function remain global-only ordinary code", () => {
  const source = `
import { Eff, freshPrompt } from "@scalascript/control"
import { direct } from "@scalascript/control-direct"
function localScope(): number {
  function eval(source: string): number { return source.length }
  return eval("x")
}
const local = localScope()
const indirect = (0, globalThis.eval)("1")
const optional = eval?.("1")
const constructed = Function("return 1")()
export const answer = freshPrompt<number, number>(p => Eff.runPure(
  direct.reset(p, (): number => { return local + indirect + optional + constructed })
))
`
  const prepared = prepare(source, "eval-allowed")
  try {
    assert.deepEqual(prepared.transform.diagnostics, [])
  } finally {
    prepared.cleanup()
  }
})

test("surviving marker values diagnose and cancel the whole source-file rewrite", () => {
  const source = `
import { Eff, freshPrompt } from "@scalascript/control"
import { direct } from "@scalascript/control-direct"
void direct
export const answer = freshPrompt<number, number>(p => Eff.runPure(
  direct.reset(p, (): number => { return 42 })
))
`
  const prepared = prepare(source, "survivor")
  try {
    assert.equal(prepared.transform.diagnostics.length, 1)
    assert.equal(prepared.transform.diagnostics[0].code, "JS_DIRECT_UNSUPPORTED")
    assert.deepEqual(prepared.transform.transformedFiles, [])
    const emit = prepared.program.emit(
      undefined,
      undefined,
      undefined,
      false,
      prepared.transform.transformers
    )
    assert.equal(emit.emitSkipped, false)
    const output = readFileSync(join(prepared.outDir, "survivor.js"), "utf8")
    assert.match(output, /@scalascript\/control-direct/)
    assert.match(output, /direct\.reset/)
    assert.doesNotMatch(output, /__sscControl/)
  } finally {
    prepared.cleanup()
  }
})

test("marker shorthand and local runtime export aliases fail file atomically", () => {
  const survivors = [
    `export const leaked = { direct }`,
    `const source = {}; ({ direct = source } = source)`,
    `export { direct }`,
    `export { direct as marker }`
  ]
  for (const [index, survivor] of survivors.entries()) {
    const source = `
import { Eff, freshPrompt } from "@scalascript/control"
import { direct } from "@scalascript/control-direct"
${survivor}
export const answer = freshPrompt<number, number>(p => Eff.runPure(
  direct.reset(p, (): number => { return 42 })
))
`
    const prepared = prepare(source, `runtime-survivor-${index}`)
    try {
      const matching = prepared.transform.diagnostics.filter(
        diagnostic => diagnostic.code === "JS_DIRECT_UNSUPPORTED"
      )
      assert.equal(matching.length, 1)
      assert.equal(
        source.slice(matching[0].start, matching[0].start + matching[0].length),
        "direct"
      )
      assert.deepEqual(prepared.transform.transformedFiles, [])
      const emit = prepared.program.emit(
        undefined,
        undefined,
        undefined,
        false,
        prepared.transform.transformers
      )
      assert.equal(emit.emitSkipped, false)
      const output = readFileSync(
        join(prepared.outDir, `runtime-survivor-${index}.js`),
        "utf8"
      )
      assert.match(output, /@scalascript\/control-direct/)
      assert.doesNotMatch(output, /__sscControl/)
    } finally {
      prepared.cleanup()
    }
  }
})

test("type-only marker exports erase without runtime diagnostics", async () => {
  const sources = [
    `import { direct } from "@scalascript/control-direct"
     export type { direct as Marker }
     export const answer = 42`,
    `import { direct } from "@scalascript/control-direct"
     export { type direct as Marker }
     export const answer = 42`,
    `export type { direct } from "@scalascript/control-direct"
     export const answer = 42`,
    `export { type direct } from "@scalascript/control-direct"
     export const answer = 42`,
    `export { type direct as Marker } from "@scalascript/control-direct"
     export const answer = 42`
  ]
  for (const [index, source] of sources.entries()) {
    const compiled = await compile(source, `type-export-${index}`)
    try {
      assert.equal(compiled.module.answer, 42)
      assert.doesNotMatch(
        readFileSync(compiled.javascript, "utf8"),
        /@scalascript\/control-direct/
      )
    } finally {
      compiled.cleanup()
    }
  }
})

test("a shadowed local runtime export is not owned by the marker import", async () => {
  const compiled = await compile(`
    import { Eff, freshPrompt } from "@scalascript/control"
    import { direct as marker } from "@scalascript/control-direct"
    const direct = 40
    export { direct }
    export const answer = direct + freshPrompt<number, number>(p => Eff.runPure(
      marker.reset(p, (): number => { return 2 })
    ))
  `, "shadowed-export")
  try {
    assert.equal(compiled.module.direct, 40)
    assert.equal(compiled.module.answer, 42)
    assert.doesNotMatch(
      readFileSync(compiled.javascript, "utf8"),
      /@scalascript\/control-direct/
    )
  } finally {
    compiled.cleanup()
  }
})

test("completed marker specifiers are removed without rewriting other imports", async () => {
  const compiled = await compile(`
    import { Eff, freshPrompt } from "@scalascript/control"
    import { DirectMarkerContractError, direct } from "@scalascript/control-direct"
    export { DirectMarkerContractError }
    export const answer = freshPrompt<number, number>(p => Eff.runPure(
      direct.reset(p, (): number => { return 42 })
    ))
  `, "mixed-import")
  try {
    assert.equal(compiled.module.answer, 42)
    const output = readFileSync(compiled.javascript, "utf8")
    assert.match(output, /DirectMarkerContractError/)
    assert.doesNotMatch(output, /\{[^}]*\bdirect\b[^}]*\} from "@scalascript\/control-direct"/)
  } finally {
    compiled.cleanup()
  }
})

test("mixed marker imports normalize JavaScript but preserve declarations", () => {
  for (const verbatimModuleSyntax of [false, true]) {
    const name = `mixed-type-import-${verbatimModuleSyntax}`
    const prepared = prepare(`
      import {
        direct,
        type DirectMarkerContractError as ErrorType
      } from "@scalascript/control-direct"
      export type PublicError = ErrorType
      export const answer = 42
    `, name, { declaration: true, verbatimModuleSyntax })
    try {
      assert.equal(formatTsDiagnostics(ts.getPreEmitDiagnostics(prepared.program)), "")
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
      const declaration = join(prepared.outDir, `${name}.d.ts`)
      const output = readFileSync(javascript, "utf8")
      assert.doesNotMatch(output, /\btype\s+DirectMarkerContractError/)
      assert.doesNotMatch(output, /@scalascript\/control-direct/)
      const syntax = spawnSync(process.execPath, ["--check", javascript], {
        encoding: "utf8"
      })
      assert.equal(syntax.status, 0, syntax.stderr)
      const types = readFileSync(declaration, "utf8")
      assert.match(types, /DirectMarkerContractError as ErrorType/)
      assert.match(types, /PublicError = ErrorType/)
    } finally {
      prepared.cleanup()
    }
  }
})

test("type-only source exports drop empty runtime links and preserve mixed values", () => {
  for (const verbatimModuleSyntax of [false, true]) {
    const pureName = `type-source-export-${verbatimModuleSyntax}`
    const pure = prepare(`
      export { type direct as Marker } from "@scalascript/control-direct"
      export const answer = 42
    `, pureName, { declaration: true, verbatimModuleSyntax })
    try {
      assert.equal(formatTsDiagnostics(ts.getPreEmitDiagnostics(pure.program)), "")
      assert.deepEqual(pure.transform.diagnostics, [])
      const emit = pure.program.emit(
        undefined,
        undefined,
        undefined,
        false,
        pure.transform.transformers
      )
      assert.equal(emit.emitSkipped, false)
      assert.equal(formatTsDiagnostics(emit.diagnostics), "")
      const output = readFileSync(join(pure.outDir, `${pureName}.js`), "utf8")
      assert.doesNotMatch(output, /@scalascript\/control-direct/)
      const types = readFileSync(join(pure.outDir, `${pureName}.d.ts`), "utf8")
      assert.match(types, /type direct as Marker/)
    } finally {
      pure.cleanup()
    }

    const mixedName = `mixed-source-export-${verbatimModuleSyntax}`
    const mixed = prepare(`
      export {
        type direct as Marker,
        DirectMarkerContractError as RuntimeError
      } from "@scalascript/control-direct"
    `, mixedName, { declaration: true, verbatimModuleSyntax })
    try {
      assert.equal(formatTsDiagnostics(ts.getPreEmitDiagnostics(mixed.program)), "")
      assert.deepEqual(mixed.transform.diagnostics, [])
      const emit = mixed.program.emit(
        undefined,
        undefined,
        undefined,
        false,
        mixed.transform.transformers
      )
      assert.equal(emit.emitSkipped, false)
      assert.equal(formatTsDiagnostics(emit.diagnostics), "")
      const output = readFileSync(join(mixed.outDir, `${mixedName}.js`), "utf8")
      assert.match(output, /DirectMarkerContractError as RuntimeError/)
      assert.doesNotMatch(output, /\btype\s+direct/)
      const types = readFileSync(join(mixed.outDir, `${mixedName}.d.ts`), "utf8")
      assert.match(types, /type direct as Marker/)
      assert.match(types, /DirectMarkerContractError as RuntimeError/)
    } finally {
      mixed.cleanup()
    }
  }
})

test("external marker import-equals is fail-closed except when explicitly type-only", () => {
  const moduleOptions = {
    baseUrl: packageRoot,
    declaration: true,
    module: ts.ModuleKind.CommonJS,
    moduleResolution: ts.ModuleResolutionKind.Node10,
    paths: {
      "@scalascript/control-direct": [join(packageRoot, "index.d.ts")]
    },
    sourceMap: false
  }
  const runtimeSources = [
    `import markers = require("@scalascript/control-direct")\nexport const answer = 42`,
    `import markers = require("@scalascript/control-direct")\nexport const marker = markers.direct`
  ]
  for (const [index, source] of runtimeSources.entries()) {
    const name = `runtime-import-equals-${index}`
    const prepared = prepare(source, name, moduleOptions)
    try {
      assert.equal(prepared.transform.diagnostics.length, 1)
      assert.equal(prepared.transform.diagnostics[0].code, "JS_DIRECT_UNSUPPORTED")
      assert.equal(
        source.slice(
          prepared.transform.diagnostics[0].start,
          prepared.transform.diagnostics[0].start + prepared.transform.diagnostics[0].length
        ),
        "markers"
      )
      assert.deepEqual(prepared.transform.transformedFiles, [])
      const emit = prepared.program.emit(
        undefined,
        undefined,
        undefined,
        false,
        prepared.transform.transformers
      )
      assert.equal(emit.emitSkipped, false)
      const output = readFileSync(join(prepared.outDir, `${name}.js`), "utf8")
      if (index === 1) {
        assert.match(output, /require\("@scalascript\/control-direct"\)/)
      }
      assert.doesNotMatch(output, /__sscControl/)
    } finally {
      prepared.cleanup()
    }
  }

  for (const verbatimModuleSyntax of [false, true]) {
    const name = `type-import-equals-${verbatimModuleSyntax}`
    const prepared = prepare(`
      import type markers = require("@scalascript/control-direct")
      export type MarkerNamespace = typeof markers
    `, name, { ...moduleOptions, verbatimModuleSyntax })
    try {
      assert.equal(formatTsDiagnostics(ts.getPreEmitDiagnostics(prepared.program)), "")
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
      assert.doesNotMatch(
        readFileSync(join(prepared.outDir, `${name}.js`), "utf8"),
        /@scalascript\/control-direct/
      )
      const types = readFileSync(join(prepared.outDir, `${name}.d.ts`), "utf8")
      assert.match(types, /import type markers = require/)
      assert.match(types, /MarkerNamespace = typeof markers/)
    } finally {
      prepared.cleanup()
    }
  }
})

test("type-only marker references erase with the completed marker import", async () => {
  const compiled = await compile(`
    import { Eff, freshPrompt } from "@scalascript/control"
    import { direct } from "@scalascript/control-direct"
    type Marker = typeof direct
    const markerTypeWitness: Marker | undefined = undefined
    void markerTypeWitness
    export const answer = freshPrompt<number, number>(p => Eff.runPure(
      direct.reset(p, (): number => { return 42 })
    ))
  `, "type-only-marker")
  try {
    assert.equal(compiled.module.answer, 42)
    assert.doesNotMatch(
      readFileSync(compiled.javascript, "utf8"),
      /@scalascript\/control-direct/
    )
  } finally {
    compiled.cleanup()
  }
})

test("unsupported marker import and re-export forms fail closed", () => {
  const sources = [
    `import * as markers from "@scalascript/control-direct"; void markers`,
    `export { direct } from "@scalascript/control-direct"`,
    `export { direct as marker } from "@scalascript/control-direct"`,
    `export * from "@scalascript/control-direct"`
  ]
  for (const [index, source] of sources.entries()) {
    const prepared = prepare(source, `unsupported-import-${index}`)
    try {
      assert.equal(prepared.transform.diagnostics.length, 1)
      assert.equal(prepared.transform.diagnostics[0].code, "JS_DIRECT_UNSUPPORTED")
      assert.deepEqual(prepared.transform.transformedFiles, [])
    } finally {
      prepared.cleanup()
    }
  }
})

test("the programmatic transform gates the TypeScript compiler API line", () => {
  const prepared = prepare("export const answer = 42", "version-gate")
  try {
    const unsupported = Object.create(ts)
    Object.defineProperties(unsupported, {
      version: { value: "6.0.0" },
      versionMajorMinor: { value: "6.0" }
    })
    assert.throws(
      () => createDirectTransform(unsupported, prepared.program),
      error => error instanceof RangeError &&
        error.message ===
          "@scalascript/control-direct supports TypeScript 5.9.x; received 6.0.0"
    )
  } finally {
    prepared.cleanup()
  }
})
