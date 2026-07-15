const DirectModule = "@scalascript/control-direct"
const ControlModule = "@scalascript/control"
const SupportedTypeScriptMajorMinor = "5.9"

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
  if (ts.versionMajorMinor !== SupportedTypeScriptMajorMinor) {
    const version = typeof ts.version === "string" ? ts.version : "unknown"
    throw new RangeError(
      `@scalascript/control-direct supports TypeScript 5.9.x; received ${version}`
    )
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
  const candidateFiles = new Set()
  const filesWithPlans = new Set()
  const markerImportsByFile = new Map()

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

  function directImportSpecifier(declaration) {
    if (!ts.isImportSpecifier(declaration)) return false
    const importedName = declaration.propertyName ?? declaration.name
    const importDeclaration = declaration.parent.parent.parent
    return importedName.text === "direct" &&
      ts.isImportDeclaration(importDeclaration) &&
      ts.isStringLiteral(importDeclaration.moduleSpecifier) &&
      importDeclaration.moduleSpecifier.text === DirectModule
  }

  function importedDirect(identifier) {
    if (!ts.isIdentifier(identifier)) return false
    const symbol = checker.getSymbolAtLocation(identifier)
    if (symbol === undefined) return false
    return (symbol.declarations ?? []).some(directImportSpecifier)
  }

  function unwrapTransparent(expression) {
    let current = expression
    while (
      ts.isParenthesizedExpression(current) ||
      ts.isAsExpression(current) ||
      ts.isNonNullExpression(current) ||
      ts.isTypeAssertionExpression(current)
    ) {
      current = current.expression
    }
    return current
  }

  function directCall(node, method) {
    if (!ts.isCallExpression(node) || node.questionDotToken !== undefined) return false
    const callee = unwrapTransparent(node.expression)
    return ts.isPropertyAccessExpression(callee) &&
      callee.questionDotToken === undefined &&
      callee.name.text === method &&
      importedDirect(unwrapTransparent(callee.expression))
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

  function isTypeOnlyReference(node) {
    let current = node
    while (current.parent !== undefined) {
      const parent = current.parent
      if (ts.isTypeQueryNode(parent)) return true
      if (
        (ts.isAsExpression(parent) || ts.isTypeAssertionExpression(parent)) &&
        parent.type === current
      ) return true
      if (
        ts.isImportDeclaration(parent) ||
        ts.isExportDeclaration(parent) ||
        ts.isStatement(parent)
      ) return false
      current = parent
    }
    return false
  }

  function markerCallForReceiver(identifier) {
    let receiver = identifier
    while (
      receiver.parent !== undefined &&
      (ts.isParenthesizedExpression(receiver.parent) ||
        ts.isAsExpression(receiver.parent) ||
        ts.isNonNullExpression(receiver.parent) ||
        ts.isTypeAssertionExpression(receiver.parent)) &&
      receiver.parent.expression === receiver
    ) {
      receiver = receiver.parent
    }
    const property = receiver.parent
    if (!ts.isPropertyAccessExpression(property) || property.expression !== receiver) {
      return undefined
    }
    let callee = property
    while (
      callee.parent !== undefined &&
      (ts.isParenthesizedExpression(callee.parent) ||
        ts.isAsExpression(callee.parent) ||
        ts.isNonNullExpression(callee.parent) ||
        ts.isTypeAssertionExpression(callee.parent)) &&
      callee.parent.expression === callee
    ) {
      callee = callee.parent
    }
    const call = callee.parent
    if (!ts.isCallExpression(call) || call.expression !== callee) return undefined
    if (directCall(call, "reset") || directCall(call, "shift")) return call
    return undefined
  }

  function collectMarkerImports(source) {
    const imports = []
    for (const statement of source.statements) {
      if (
        !ts.isImportDeclaration(statement) ||
        !ts.isStringLiteral(statement.moduleSpecifier) ||
        statement.moduleSpecifier.text !== DirectModule
      ) continue
      const clause = statement.importClause
      if (clause === undefined) continue
      if (clause.name !== undefined) {
        candidateFiles.add(source.fileName)
        unsupported(clause.name, "default marker imports are outside the closed grammar")
      }
      if (clause.namedBindings !== undefined && ts.isNamespaceImport(clause.namedBindings)) {
        candidateFiles.add(source.fileName)
        unsupported(clause.namedBindings.name, "namespace marker imports are outside the closed grammar")
        continue
      }
      if (clause.namedBindings === undefined) continue
      for (const specifier of clause.namedBindings.elements) {
        if (!directImportSpecifier(specifier)) continue
        imports.push(specifier)
        candidateFiles.add(source.fileName)
      }
    }
    if (imports.length !== 0) markerImportsByFile.set(source.fileName, imports)

    for (const statement of source.statements) {
      if (
        !ts.isExportDeclaration(statement) ||
        statement.moduleSpecifier === undefined ||
        !ts.isStringLiteral(statement.moduleSpecifier) ||
        statement.moduleSpecifier.text !== DirectModule
      ) continue
      if (statement.exportClause === undefined) {
        candidateFiles.add(source.fileName)
        unsupported(statement, "star re-export of the marker package is outside the closed grammar")
        continue
      }
      if (ts.isNamespaceExport(statement.exportClause)) {
        candidateFiles.add(source.fileName)
        unsupported(statement.exportClause, "namespace re-export of the marker package is outside the closed grammar")
        continue
      }
      for (const specifier of statement.exportClause.elements) {
        const importedName = specifier.propertyName ?? specifier.name
        if (importedName.text !== "direct") continue
        candidateFiles.add(source.fileName)
        unsupported(specifier, "re-export of the direct marker is outside the closed grammar")
      }
    }
  }

  function scanMarkerUses(source) {
    const imports = markerImportsByFile.get(source.fileName)
    if (imports === undefined) return
    const declarations = new Set(imports.map(specifier => specifier.name))
    function visit(node) {
      if (ts.isIdentifier(node) && importedDirect(node) && !declarations.has(node)) {
        if (!isTypeOnlyReference(node) && markerCallForReceiver(node) === undefined) {
          unsupported(node, "owned direct marker value use would survive transformation")
        }
      }
      ts.forEachChild(node, visit)
    }
    visit(source)
  }

  function intrinsicDirectEval(node) {
    if (!ts.isCallExpression(node) || node.questionDotToken !== undefined) return false
    const callee = unwrapTransparent(node.expression)
    if (!ts.isIdentifier(callee) || callee.text !== "eval") return false
    const symbol = checker.getSymbolAtLocation(callee)
    if (symbol === undefined) return true
    const declarations = symbol.declarations ?? []
    return declarations.length === 0 || declarations.every(declaration =>
      declaration.getSourceFile().isDeclarationFile ||
      declaration.modifiers?.some(modifier =>
        modifier.kind === ts.SyntaxKind.DeclareKeyword
      ) === true
    )
  }

  function scanDirectEval(source) {
    if (!candidateFiles.has(source.fileName)) return
    function visit(node) {
      if (intrinsicDirectEval(node)) {
        addDiagnostic(
          Codes.CaptureBarrier,
          "intrinsic direct eval crosses the transformed file's lexical capture boundary",
          node
        )
        return
      }
      ts.forEachChild(node, visit)
    }
    visit(source)
  }

  function declarationSymbols(statement, result) {
    if (!ts.isVariableStatement(statement)) return
    for (const declaration of statement.declarationList.declarations) {
      if (!ts.isIdentifier(declaration.name)) continue
      const symbol = checker.getSymbolAtLocation(declaration.name)
      if (symbol !== undefined) result.add(symbol)
    }
  }

  function findForbiddenReference(node, forbidden) {
    let found
    function visit(current) {
      if (found !== undefined) return
      if (
        ts.isIdentifier(current) &&
        !isTypeOnlyReference(current) &&
        forbidden.has(checker.getSymbolAtLocation(current))
      ) {
        found = current
        return
      }
      ts.forEachChild(current, visit)
    }
    visit(node)
    return found
  }

  function checkMarkerLexicalCapture(statements, markers) {
    let layerStart = 0
    for (const marker of markers) {
      const nextLayerStart = marker.index + 1
      const forbidden = new Set()
      for (let index = marker.index; index < statements.length - 1; index += 1) {
        declarationSymbols(statements[index], forbidden)
      }
      let reference
      for (let index = layerStart; index < marker.index; index += 1) {
        reference = findForbiddenReference(statements[index], forbidden)
        if (reference !== undefined) break
      }
      reference ??= findForbiddenReference(marker.shiftBody, forbidden)
      if (reference !== undefined) {
        addDiagnostic(
          Codes.CaptureBarrier,
          "direct marker layer references its own marker or a later continuation binding",
          reference
        )
      }
      layerStart = nextLayerStart
    }
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
        markers.push({ index, statement, ...marker })
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

    checkMarkerLexicalCapture(statements, markers)

    if (diagnostics.length === before) {
      plans.set(call, Object.freeze({
        call,
        prompt,
        body,
        statements,
        returnStatement,
        markers
      }))
      candidateFiles.add(call.getSourceFile().fileName)
      filesWithPlans.add(call.getSourceFile().fileName)
    }
  }

  function walkForResets(node) {
    if (directCall(node, "reset")) {
      analyzeReset(node)
    }
    ts.forEachChild(node, walkForResets)
  }

  function walkForOutsideShifts(node) {
    if (directCall(node, "shift")) {
      if (!claimedShifts.has(node)) {
        addDiagnostic(
          Codes.OutsideReset,
          "direct.shift is outside a recognized direct.reset region",
          node
        )
        return
      }
    }
    ts.forEachChild(node, walkForOutsideShifts)
  }

  const sources = program.getSourceFiles().filter(source => !source.isDeclarationFile)
  for (const source of sources) collectMarkerImports(source)
  for (const source of sources) walkForResets(source)
  for (const source of sources) walkForOutsideShifts(source)
  for (const source of sources) scanMarkerUses(source)
  for (const source of sources) scanDirectEval(source)

  const diagnosticFiles = new Set(diagnostics.map(diagnostic => diagnostic.fileName))
  const transformedFiles = new Set(
    Array.from(candidateFiles).filter(fileName => !diagnosticFiles.has(fileName))
  )

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
      const resumeName = factory.createUniqueName(
        "__sscResume",
        ts.GeneratedIdentifierFlags.Optimistic
      )
      const parameter = ranged(
        factory.createParameterDeclaration(
          undefined,
          undefined,
          resumeName,
          undefined,
          undefined,
          undefined
        ),
        nextMarker.declaration.name
      )
      const bindingDeclaration = ranged(
        factory.updateVariableDeclaration(
          nextMarker.declaration,
          nextMarker.declaration.name,
          nextMarker.declaration.exclamationToken,
          nextMarker.declaration.type,
          resumeName
        ),
        nextMarker.declaration
      )
      const bindingList = factory.updateVariableDeclarationList(
        nextMarker.statement.declarationList,
        [bindingDeclaration]
      )
      const bindingStatement = ranged(
        factory.updateVariableStatement(
          nextMarker.statement,
          nextMarker.statement.modifiers,
          bindingList
        ),
        nextMarker.statement
      )
      const continuationTail = lowerTail(plan, markerPosition + 1)
      const continuationBody = factory.updateBlock(
        continuationTail,
        [bindingStatement, ...continuationTail.statements]
      )
      const continuation = ranged(
        factory.createArrowFunction(
          undefined,
          undefined,
          [parameter],
          undefined,
          factory.createToken(ts.SyntaxKind.EqualsGreaterThanToken),
          continuationBody
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

    function rewriteMarkerImport(statement) {
      if (
        !ts.isImportDeclaration(statement) ||
        !ts.isStringLiteral(statement.moduleSpecifier) ||
        statement.moduleSpecifier.text !== DirectModule ||
        statement.importClause === undefined ||
        statement.importClause.namedBindings === undefined ||
        !ts.isNamedImports(statement.importClause.namedBindings)
      ) return statement

      const retained = statement.importClause.namedBindings.elements.filter(
        specifier => !directImportSpecifier(specifier)
      )
      if (retained.length === statement.importClause.namedBindings.elements.length) {
        return statement
      }
      if (retained.length === 0 && statement.importClause.name === undefined) {
        return undefined
      }
      const namedBindings = retained.length === 0
        ? undefined
        : factory.updateNamedImports(statement.importClause.namedBindings, retained)
      const clause = factory.updateImportClause(
        statement.importClause,
        statement.importClause.isTypeOnly,
        statement.importClause.name,
        namedBindings
      )
      return factory.updateImportDeclaration(
        statement,
        statement.modifiers,
        clause,
        statement.moduleSpecifier,
        statement.attributes
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
      const needsControl = filesWithPlans.has(source.fileName)
      let namespaceImport
      if (needsControl) {
        controlNamespace = factory.createUniqueName(
          namespaceText,
          ts.GeneratedIdentifierFlags.Optimistic
        )
        namespaceImport = factory.createImportDeclaration(
          undefined,
          factory.createImportClause(
            false,
            undefined,
            factory.createNamespaceImport(controlNamespace)
          ),
          factory.createStringLiteral(ControlModule),
          undefined
        )
      }
      const statements = []
      for (const statement of source.statements) {
        const visited = visitStatement(statement)
        const rewritten = rewriteMarkerImport(visited)
        if (rewritten !== undefined) statements.push(rewritten)
      }
      return factory.updateSourceFile(
        source,
        namespaceImport === undefined ? statements : [namespaceImport, ...statements]
      )
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
