#!/usr/bin/env node

import { resolve } from "node:path"
import { pathToFileURL } from "node:url"

import { createDirectTransform, formatDirectDiagnostic } from "./transform.js"

async function loadTypeScript() {
  try {
    const loaded = await import("typescript")
    return loaded.default ?? loaded
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error)
    process.stderr.write(
      "ssc-control-tsc: TypeScript compiler API not found; install typescript " +
      `in the consuming project (${detail})\n`
    )
    process.exitCode = 1
    return undefined
  }
}

function host(ts) {
  return {
    getCanonicalFileName: fileName => ts.sys.useCaseSensitiveFileNames
      ? fileName
      : fileName.toLowerCase(),
    getCurrentDirectory: ts.sys.getCurrentDirectory,
    getNewLine: () => ts.sys.newLine
  }
}

function printTypeScriptDiagnostics(ts, diagnostics) {
  if (diagnostics.length === 0) return
  process.stderr.write(ts.formatDiagnosticsWithColorAndContext(diagnostics, host(ts)))
}

function parseConfig(ts, argv) {
  const command = ts.parseCommandLine(argv)
  if (command.errors.length !== 0) return { errors: command.errors }

  const project = command.options.project
  if (project !== undefined && command.fileNames.length !== 0) {
    return {
      errors: [{
        category: ts.DiagnosticCategory.Error,
        code: 5042,
        file: undefined,
        start: undefined,
        length: undefined,
        messageText: "Option 'project' cannot be mixed with source files on a command line."
      }]
    }
  }

  let configPath
  if (project !== undefined) {
    const candidate = resolve(project)
    configPath = ts.sys.directoryExists(candidate)
      ? ts.combinePaths(candidate, "tsconfig.json")
      : candidate
  } else if (command.fileNames.length === 0) {
    configPath = ts.findConfigFile(ts.sys.getCurrentDirectory(), ts.sys.fileExists)
    if (configPath === undefined) {
      return {
        errors: [{
          category: ts.DiagnosticCategory.Error,
          code: 5057,
          file: undefined,
          start: undefined,
          length: undefined,
          messageText: "Cannot find a tsconfig.json file at the current directory or any parent directory."
        }]
      }
    }
  }

  if (configPath === undefined) {
    return {
      errors: [],
      fileNames: command.fileNames,
      options: command.options,
      projectReferences: command.projectReferences
    }
  }

  const read = ts.readConfigFile(configPath, ts.sys.readFile)
  if (read.error !== undefined) return { errors: [read.error] }
  const parsed = ts.parseJsonConfigFileContent(
    read.config,
    ts.sys,
    ts.getDirectoryPath(configPath),
    command.options,
    configPath
  )
  return {
    errors: parsed.errors,
    fileNames: parsed.fileNames,
    options: parsed.options,
    projectReferences: parsed.projectReferences
  }
}

export async function main(argv = process.argv.slice(2)) {
  const ts = await loadTypeScript()
  if (ts === undefined) return 1

  const parsed = parseConfig(ts, argv)
  if (parsed.errors.length !== 0) {
    printTypeScriptDiagnostics(ts, parsed.errors)
    return 1
  }

  const program = ts.createProgram({
    rootNames: parsed.fileNames,
    options: parsed.options,
    projectReferences: parsed.projectReferences
  })
  const directTransform = createDirectTransform(ts, program)
  const compilerDiagnostics = ts.getPreEmitDiagnostics(program)
  printTypeScriptDiagnostics(ts, compilerDiagnostics)
  for (const diagnostic of directTransform.diagnostics) {
    process.stderr.write(`${formatDirectDiagnostic(diagnostic)}\n`)
  }
  if (
    compilerDiagnostics.some(diagnostic => diagnostic.category === ts.DiagnosticCategory.Error) ||
    directTransform.diagnostics.length !== 0
  ) {
    return 1
  }

  if (parsed.options.noEmit) return 0
  const emitted = program.emit(
    undefined,
    undefined,
    undefined,
    false,
    directTransform.transformers
  )
  printTypeScriptDiagnostics(ts, emitted.diagnostics)
  return emitted.emitSkipped ||
    emitted.diagnostics.some(diagnostic => diagnostic.category === ts.DiagnosticCategory.Error)
    ? 1
    : 0
}

const invoked = process.argv[1] === undefined
  ? false
  : import.meta.url === pathToFileURL(resolve(process.argv[1])).href

if (invoked) {
  process.exitCode = await main()
}
