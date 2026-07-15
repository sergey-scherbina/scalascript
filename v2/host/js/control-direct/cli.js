#!/usr/bin/env node

import { realpathSync, statSync } from "node:fs"
import { createRequire } from "node:module"
import { dirname, join, resolve } from "node:path"
import { fileURLToPath } from "node:url"

import { createDirectTransform, formatDirectDiagnostic } from "./transform.js"

const SupportedTypeScriptMajorMinor = "5.9"

function projectArgument(argv) {
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === "--project" || argument === "-p") return argv[index + 1]
    if (argument.startsWith("--project=")) return argument.slice("--project=".length)
  }
  return undefined
}

function compilerIssuer(argv) {
  const cwd = process.cwd()
  const project = projectArgument(argv)
  if (project === undefined || project.length === 0) return cwd
  const candidate = resolve(cwd, project)
  try {
    return statSync(candidate).isDirectory() ? candidate : dirname(candidate)
  } catch {
    return dirname(candidate)
  }
}

function loadTypeScript(argv) {
  const issuer = compilerIssuer(argv)
  try {
    const requireFromConsumer = createRequire(join(issuer, "package.json"))
    const loaded = requireFromConsumer("typescript")
    const ts = loaded.default ?? loaded
    if (ts.versionMajorMinor !== SupportedTypeScriptMajorMinor) {
      const version = typeof ts.version === "string" ? ts.version : "unknown"
      throw new RangeError(
        `@scalascript/control-direct supports TypeScript 5.9.x; received ${version}`
      )
    }
    return ts
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error)
    process.stderr.write(
      "ssc-control-tsc: compatible TypeScript compiler API not found from " +
      `${issuer}; install typescript@5.9 in the consuming project (${detail})\n`
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
  const ts = loadTypeScript(argv)
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

function sameEntryFile(moduleUrl, argvEntry) {
  if (argvEntry === undefined) return false
  try {
    const modulePath = realpathSync(fileURLToPath(moduleUrl))
    const entryPath = realpathSync(resolve(argvEntry))
    if (modulePath === entryPath) return true
    const moduleStat = statSync(modulePath)
    const entryStat = statSync(entryPath)
    return moduleStat.dev === entryStat.dev && moduleStat.ino === entryStat.ino
  } catch {
    return false
  }
}

const invoked = sameEntryFile(import.meta.url, process.argv[1])

if (invoked) {
  process.exitCode = await main()
}
