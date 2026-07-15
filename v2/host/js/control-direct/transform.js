const DirectModule = "@scalascript/control-direct"
const ControlModule = "@scalascript/control"

const Codes = Object.freeze({
  OutsideReset: "JS_DIRECT_OUTSIDE_RESET",
  CaptureBarrier: "JS_DIRECT_CAPTURE_BARRIER",
  Unsupported: "JS_DIRECT_UNSUPPORTED",
  PromptMismatch: "JS_DIRECT_PROMPT_MISMATCH"
})

function assertCompilerApi(ts) {
  if (
    ts === null ||
    typeof ts !== "object" ||
    typeof ts.forEachChild !== "function" ||
    typeof ts.isCallExpression !== "function" ||
    typeof ts.factory !== "object"
  ) {
    throw new TypeError("typescript must be a TypeScript compiler API object")
  }
}

function assertProgram(program) {
  if (
    program === null ||
    typeof program !== "object" ||
    typeof program.getTypeChecker !== "function" ||
    typeof program.getSourceFiles !== "function"
  ) {
    throw new TypeError("program must be a TypeScript Program")
  }
}

export function createDirectTransform(ts, program) {
  assertCompilerApi(ts)
  assertProgram(program)

  const checker = program.getTypeChecker()
  const plans = new Map()
  const analyzedResets = new Set()
  const claimedShifts = new Set()
  const diagnostics = []
  const diagnosticKeys = new Set()
  const transformedFiles = new Set()

  function textRange(node) {
    const source = node.getSourceFile()
    const start = node.getStart(source, false)
    const end = node.getEnd()
    return { source, start, length: Math.max(1, end - start) }
  }

  function addDiagnostic(code, message, node) {
    const { source, start, length } = textRange(node)
    const key = `${source.fileName}\u0000${start}\u0000${length}\u0000${code}`
    if (diagnosticKeys.has(key)) return
    diagnosticKeys.add(key)
    const location = source.getLineAndCharacterOfPosition(start)
    diagnostics.push(Object.freeze({
      code,
      message,
      fileName: source.fileName,
      start,
      length,
      line: location.line + 1,
      column: location.character + 1
    }))
  }

  function importedDirect(identifier) {
    if (!ts.isIdentifier(identifier)) return false
    const symbol = checker.getSymbolAtLocation(identifier)
    if (symbol === undefined) return false
    return (symbol.declarations ?? []).some(declaration => {
      if (!ts.isImportSpecifier(declaration)) return false
      const importedName = declaration.propertyName ?? declaration.name
      const importDeclaration = declaration.parent.parent.parent
      return importedName.text === "direct" &&
        ts.isImportDeclaration(importDeclaration) &&
        ts.isStringLiteral(importDeclaration.moduleSpecifier) &&
        importDeclaration.moduleSpecifier.text === DirectModule
    })
  }

  function directCall(node, method) {
    return ts.isCallExpression(node) &&
      ts.isPropertyAccessExpression(node.expression) &&
      node.expression.name.text === method &&
      importedDirect(node.expression.expression)
  }

  function canonicalSymbol(identifier) {
    if (!ts.isIdentifier(identifier)) return undefined
    const symbol = checker.getSymbolAtLocation(identifier)
    if (symbol === undefined) return undefined
    if ((symbol.flags & ts.SymbolFlags.Alias) !== 0) {
      try {
        return checker.getAliasedSymbol(symbol)
      } catch {
        return symbol
      }
    }
    return symbol
  }

  function samePrompt(left, right) {
    const leftSymbol = canonicalSymbol(left)
    const rightSymbol = canonicalSymbol(right)
    return leftSymbol !== undefined && leftSymbol === rightSymbol
  }

  function isFunctionNode(node) {
    return ts.isFunctionDeclaration(node) ||
      ts.isFunctionExpression(node) ||
      ts.isArrowFunction(node) ||
      ts.isMethodDeclaration(node) ||
      ts.isGetAccessorDeclaration(node) ||
      ts.isSetAccessorDeclaration(node) ||
      ts.isConstructorDeclaration(node)
  }

  function isClassNode(node) {
    return ts.isClassDeclaration(node) || ts.isClassExpression(node)
  }

  function isLoopNode(node) {
    return ts.isForStatement(node) ||
      ts.isForInStatement(node) ||
      ts.isForOfStatement(node) ||
      ts.isWhileStatement(node) ||
      ts.isDoStatement(node)
  }

  function barrierKind(node, branchHasMarker) {
    if (isFunctionNode(node)) return "callback/function"
    if (isClassNode(node)) return "class"
    if (ts.isAwaitExpression(node)) return "await"
    if (ts.isYieldExpression(node)) return "yield"
    if (ts.isTryStatement(node)) return "try/finally"
    if (isLoopNode(node)) return "loop"
    if (ts.isSwitchStatement(node)) return "switch"
    if (branchHasMarker && (ts.isIfStatement(node) || ts.isConditionalExpression(node))) {
      return "branch"
    }
    return undefined
  }

  function collectShifts(node, result, barrier, rootReset) {
    if (node !== rootReset && directCall(node, "reset")) return
    if (directCall(node, "shift")) {
      result.push({ call: node, barrier })
      return
    }
    const ownBarrier = barrierKind(node, false)
    const nextBarrier = barrier ?? (ownBarrier === undefined ? undefined : node)
    ts.forEachChild(node, child => collectShifts(child, result, nextBarrier, rootReset))
  }

  function shiftsIn(node, rootReset) {
    const result = []
    collectShifts(node, result, undefined, rootReset)
    return result
  }

  function findFirstBarrier(node, branchHasMarker, rootReset, skipRootFunction) {
    let found
    function visit(current, isRoot = false) {
      if (found !== undefined) return
      if (current !== rootReset && directCall(current, "reset")) return
      if (directCall(current, "shift")) return
      const kind = !(skipRootFunction && isRoot)
        ? barrierKind(current, branchHasMarker)
        : undefined
      if (kind !== undefined) {
        found = { node: current, kind }
        return
      }
      ts.forEachChild(current, child => visit(child, false))
    }
    visit(node, true)
    return found
  }

  function unsupported(node, detail) {
    addDiagnostic(Codes.Unsupported, `unsupported direct-control shape: ${detail}`, node)
  }

  function analyzeShiftMarker(call, declaration, prompt, rootReset) {
    claimedShifts.add(call)
    if (call.arguments.length !== 2) {
      unsupported(call, "direct.shift requires exactly prompt and body arguments")
      return undefined
    }
    const shiftPrompt = call.arguments[0]
    const shiftBody = call.arguments[1]
    if (!ts.isIdentifier(shiftPrompt)) {
      unsupported(shiftPrompt, "shift prompt must be an identifier")
      return undefined
    }
    if (!samePrompt(prompt, shiftPrompt)) {
      addDiagnostic(
        Codes.PromptMismatch,
        "direct.shift prompt does not match its nearest direct.reset prompt",
        shiftPrompt
      )
      return undefined
    }
    if (!ts.isArrowFunction(shiftBody) && !ts.isFunctionExpression(shiftBody)) {
      unsupported(shiftBody, "shift body must be a synchronous function")
      return undefined
    }
    if (
      shiftBody.modifiers?.some(modifier => modifier.kind === ts.SyntaxKind.AsyncKeyword) ||
      shiftBody.asteriskToken !== undefined
    ) {
      addDiagnostic(
        Codes.CaptureBarrier,
        "direct.shift body crosses an async or generator capture barrier",
        shiftBody
      )
      return undefined
    }

    const nestedShifts = shiftsIn(shiftBody.body, rootReset)
    for (const nested of nestedShifts) {
      claimedShifts.add(nested.call)
      addDiagnostic(
        Codes.CaptureBarrier,
        "direct.shift nested in an untransformed callback crosses a capture barrier",
        nested.barrier ?? shiftBody
      )
    }

    const barrier = findFirstBarrier(shiftBody.body, false, rootReset, false)
    if (barrier !== undefined && nestedShifts.length === 0) {
      const barrierShifts = shiftsIn(barrier.node, rootReset)
      if (barrierShifts.length !== 0) {
        addDiagnostic(
          Codes.CaptureBarrier,
          `direct.shift crosses ${barrier.kind} capture barrier`,
          barrier.node
        )
      }
    }
    return { call, declaration, shiftPrompt, shiftBody }
  }

  function markerFromStatement(statement, prompt, rootReset) {
    if (!ts.isVariableStatement(statement)) return undefined
    if (statement.declarationList.declarations.length !== 1) return undefined
    const declaration = statement.declarationList.declarations[0]
    if (declaration.initializer === undefined || !directCall(declaration.initializer, "shift")) {
      return undefined
    }
    const flags = statement.declarationList.flags
    if ((flags & ts.NodeFlags.Const) === 0 && (flags & ts.NodeFlags.Let) === 0) {
      claimedShifts.add(declaration.initializer)
      unsupported(statement, "shift marker must use const or let, not var")
      return null
    }
    if (!ts.isIdentifier(declaration.name)) {
      claimedShifts.add(declaration.initializer)
      unsupported(declaration.name, "shift marker binding must be one identifier")
      return null
    }
    return analyzeShiftMarker(declaration.initializer, declaration, prompt, rootReset) ?? null
  }

  function analyzeReset(call) {
    if (analyzedResets.has(call)) return
    analyzedResets.add(call)
    const before = diagnostics.length

    if (call.arguments.length !== 2) {
      unsupported(call, "direct.reset requires exactly prompt and body arguments")
      return
    }
    const prompt = call.arguments[0]
    const body = call.arguments[1]
    if (!ts.isIdentifier(prompt)) {
      unsupported(prompt, "reset prompt must be an identifier")
      return
    }
    if (
      (ts.isFunctionExpression(body) && body.asteriskToken !== undefined) ||
      ((ts.isFunctionExpression(body) || ts.isArrowFunction(body)) &&
        body.modifiers?.some(modifier => modifier.kind === ts.SyntaxKind.AsyncKeyword))
    ) {
      addDiagnostic(
        Codes.CaptureBarrier,
        "direct.reset body crosses an async or generator capture barrier",
        body
      )
      return
    }
    if (!ts.isArrowFunction(body)) {
      unsupported(body, "reset body must be a zero-parameter arrow function")
      return
    }
    if (body.parameters.length !== 0 || !ts.isBlock(body.body)) {
      unsupported(body, "reset body must be a zero-parameter arrow with a block body")
      return
    }
    const statements = Array.from(body.body.statements)
    if (statements.length === 0 || !ts.isReturnStatement(statements.at(-1))) {
      unsupported(body.body, "reset block must end with one return statement")
      return
    }
    const returnStatement = statements.at(-1)
    if (returnStatement.expression === undefined) {
      unsupported(returnStatement, "reset return must have a value")
      return
    }

    const markers = []
    for (let index = 0; index < statements.length - 1; index += 1) {
      const statement = statements[index]
      const marker = markerFromStatement(statement, prompt, call)
      if (marker === null) continue
      if (marker !== undefined) {
        markers.push({ index, ...marker })
        continue
      }

      const shifts = shiftsIn(statement, call)
      for (const shift of shifts) claimedShifts.add(shift.call)
      const branchHasMarker = shifts.length !== 0
      const barrier = findFirstBarrier(statement, branchHasMarker, call, false)
      if (barrier !== undefined) {
        addDiagnostic(
          Codes.CaptureBarrier,
          `direct control crosses ${barrier.kind} capture barrier`,
          barrier.node
        )
        continue
      }
      if (shifts.length !== 0) {
        unsupported(shifts[0].call, "direct.shift must be a top-level const/let initializer")
        continue
      }
      if (ts.isReturnStatement(statement)) {
        unsupported(statement, "only the final reset statement may return")
        continue
      }
      if (ts.isVariableStatement(statement)) {
        const flags = statement.declarationList.flags
        const lexical = (flags & ts.NodeFlags.Const) !== 0 || (flags & ts.NodeFlags.Let) !== 0
        const identifiers = statement.declarationList.declarations.every(declaration =>
          ts.isIdentifier(declaration.name)
        )
        if (!lexical || !identifiers) {
          unsupported(statement, "pure declarations must use const/let identifier bindings")
        }
        continue
      }
      if (ts.isExpressionStatement(statement) || ts.isEmptyStatement(statement)) continue
      unsupported(statement, "statement is outside the closed lexical grammar")
    }

    const returnShifts = shiftsIn(returnStatement.expression, call)
    for (const shift of returnShifts) claimedShifts.add(shift.call)
    if (returnShifts.length !== 0) {
      const barrier = findFirstBarrier(returnStatement.expression, true, call, false)
      if (barrier !== undefined) {
        addDiagnostic(
          Codes.CaptureBarrier,
          `direct control crosses ${barrier.kind} capture barrier`,
          barrier.node
        )
      } else {
        unsupported(returnShifts[0].call, "direct.shift cannot appear in return expression")
      }
    }

    const returnBarrier = findFirstBarrier(returnStatement.expression, false, call, false)
    if (returnBarrier !== undefined && returnShifts.length === 0) {
      addDiagnostic(
        Codes.CaptureBarrier,
        `direct return crosses ${returnBarrier.kind} capture barrier`,
        returnBarrier.node
      )
    }

    if (diagnostics.length === before) {
      plans.set(call, Object.freeze({
        call,
        prompt,
        body,
        statements,
        returnStatement,
        markers
      }))
      transformedFiles.add(call.getSourceFile().fileName)
    }
  }

  function walkForResets(node) {
    if (directCall(node, "reset")) analyzeReset(node)
    ts.forEachChild(node, walkForResets)
  }

  function walkForOutsideShifts(node) {
    if (directCall(node, "shift") && !claimedShifts.has(node)) {
      addDiagnostic(
        Codes.OutsideReset,
        "direct.shift is outside a recognized direct.reset region",
        node
      )
      return
    }
    ts.forEachChild(node, walkForOutsideShifts)
  }

  const sources = program.getSourceFiles().filter(source => !source.isDeclarationFile)
  for (const source of sources) walkForResets(source)
  for (const source of sources) walkForOutsideShifts(source)

  diagnostics.sort((left, right) =>
    left.fileName.localeCompare(right.fileName) ||
    left.start - right.start ||
    left.length - right.length ||
    left.code.localeCompare(right.code)
  )

  function transformer(context) {
    const factory = context.factory
    let controlNamespace

    function ranged(created, original) {
      ts.setOriginalNode(created, original)
      ts.setTextRange(created, original)
      return created
    }

    function controlMember(name, original) {
      return ranged(
        factory.createPropertyAccessExpression(controlNamespace, name),
        original
      )
    }

    function callMember(receiver, name, argumentsList, original) {
      const member = ranged(factory.createPropertyAccessExpression(receiver, name), original)
      return ranged(factory.createCallExpression(member, undefined, argumentsList), original)
    }

    function visit(node) {
      if (directCall(node, "reset")) {
        const plan = plans.get(node)
        if (plan !== undefined) return lowerReset(plan)
      }
      return ts.visitEachChild(node, visit, context)
    }

    function visitStatement(statement) {
      return ts.visitEachChild(statement, visit, context)
    }

    function lowerTail(plan, markerPosition) {
      const nextMarker = plan.markers[markerPosition]
      const start = markerPosition === 0
        ? 0
        : plan.markers[markerPosition - 1].index + 1
      const stop = nextMarker === undefined
        ? plan.statements.length - 1
        : nextMarker.index
      const prefix = plan.statements
        .slice(start, stop)
        .map(visitStatement)

      if (nextMarker === undefined) {
        const value = ts.visitNode(plan.returnStatement.expression, visit)
        const pureCall = callMember(
          controlMember("Eff", plan.returnStatement.expression),
          "pure",
          [value],
          plan.returnStatement.expression
        )
        return factory.createBlock([
          ...prefix,
          ranged(factory.createReturnStatement(pureCall), plan.returnStatement)
        ], true)
      }

      const shifted = ranged(
        factory.createCallExpression(
          controlMember("shift", nextMarker.call.expression),
          undefined,
          [
            ts.visitNode(nextMarker.shiftPrompt, visit),
            ts.visitNode(nextMarker.shiftBody, visit)
          ]
        ),
        nextMarker.call
      )
      const parameter = ranged(
        factory.createParameterDeclaration(
          undefined,
          undefined,
          nextMarker.declaration.name,
          undefined,
          nextMarker.declaration.type,
          undefined
        ),
        nextMarker.declaration.name
      )
      const continuation = ranged(
        factory.createArrowFunction(
          undefined,
          undefined,
          [parameter],
          undefined,
          factory.createToken(ts.SyntaxKind.EqualsGreaterThanToken),
          lowerTail(plan, markerPosition + 1)
        ),
        nextMarker.declaration
      )
      const bound = callMember(shifted, "flatMap", [continuation], nextMarker.call)
      return factory.createBlock([
        ...prefix,
        ranged(factory.createReturnStatement(bound), plan.statements[nextMarker.index])
      ], true)
    }

    function lowerReset(plan) {
      const resetBody = ranged(
        factory.createArrowFunction(
          undefined,
          undefined,
          [],
          undefined,
          factory.createToken(ts.SyntaxKind.EqualsGreaterThanToken),
          lowerTail(plan, 0)
        ),
        plan.body
      )
      return ranged(
        factory.createCallExpression(
          controlMember("reset", plan.call.expression),
          undefined,
          [ts.visitNode(plan.prompt, visit), resetBody]
        ),
        plan.call
      )
    }

    return source => {
      if (!transformedFiles.has(source.fileName)) return source
      const identifiers = new Set()
      function collectIdentifiers(node) {
        if (ts.isIdentifier(node)) identifiers.add(node.text)
        ts.forEachChild(node, collectIdentifiers)
      }
      collectIdentifiers(source)
      let namespaceText = "__sscControl"
      let suffix = 0
      while (identifiers.has(namespaceText)) {
        suffix += 1
        namespaceText = `__sscControl${suffix}`
      }
      controlNamespace = factory.createUniqueName(namespaceText, ts.GeneratedIdentifierFlags.Optimistic)
      const namespaceImport = factory.createImportDeclaration(
        undefined,
        factory.createImportClause(
          false,
          undefined,
          factory.createNamespaceImport(controlNamespace)
        ),
        factory.createStringLiteral(ControlModule),
        undefined
      )
      const statements = source.statements.map(statement => visitStatement(statement))
      return factory.updateSourceFile(source, [namespaceImport, ...statements])
    }
  }

  return Object.freeze({
    diagnostics: Object.freeze(diagnostics.slice()),
    transformedFiles: Object.freeze(Array.from(transformedFiles).sort()),
    transformers: Object.freeze({ before: Object.freeze([transformer]) })
  })
}

export function formatDirectDiagnostic(diagnostic) {
  if (diagnostic === null || typeof diagnostic !== "object") {
    throw new TypeError("diagnostic must be a DirectDiagnostic")
  }
  return `${diagnostic.fileName}:${diagnostic.line}:${diagnostic.column} ` +
    `${diagnostic.code}: ${diagnostic.message}`
}
