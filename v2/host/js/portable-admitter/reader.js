// Canonical S-expression reader + CoreIR scope validation for the Portable capsule admitter
// (control-interoperability §9.2, §10.1; `specs/portable-capsule-seal.md`).
//
// This is the FIRST slice of a second ADMITTING backend for conformance vector 15: a non-JVM
// runtime that admits and runs a capsule whose resume program travels as closed CoreIR bytes. It
// deliberately reproduces the JVM reader's rules rather than inventing lenient ones — a second
// admitter that is more permissive than the first does not prove cross-host parity, it hides it.
//
// Not a general S-expr parser: the grammar is exactly what `Writer` emits — atoms, quoted strings
// with `\\` and `\"` escapes, and lists — and anything else is an error rather than a guess.

/** @typedef {{ kind: "atom", value: string } | { kind: "list", items: Sx[] }} Sx */

const SPACE = new Set([" ", "\t", "\n", "\r"])

class Cursor {
  constructor(text) {
    this.text = text
    this.at = 0
  }

  fail(message) {
    const near = this.text.slice(Math.max(0, this.at - 24), this.at + 24)
    throw new Error(`portable-capsule: ${message} at offset ${this.at} near '${near}'`)
  }

  skipSpace() {
    while (this.at < this.text.length && SPACE.has(this.text[this.at])) this.at += 1
  }

  peek() {
    return this.at < this.text.length ? this.text[this.at] : ""
  }
}

function readString(cursor) {
  // Mirrors Writer's escaping: only \\ and \" are produced, so only those are accepted. A stray
  // backslash is an error — silently passing it through is how two lanes drift apart.
  cursor.at += 1 // opening quote
  let out = '"'
  for (;;) {
    if (cursor.at >= cursor.text.length) cursor.fail("unterminated string")
    const ch = cursor.text[cursor.at]
    if (ch === "\\") {
      const next = cursor.text[cursor.at + 1]
      if (next !== "\\" && next !== '"') cursor.fail(`unsupported escape \\${next}`)
      out += ch + next
      cursor.at += 2
      continue
    }
    out += ch
    cursor.at += 1
    if (ch === '"') return out
  }
}

function readAtom(cursor) {
  const start = cursor.at
  while (cursor.at < cursor.text.length) {
    const ch = cursor.text[cursor.at]
    if (SPACE.has(ch) || ch === "(" || ch === ")") break
    cursor.at += 1
  }
  if (cursor.at === start) cursor.fail("empty atom")
  return cursor.text.slice(start, cursor.at)
}

function readSx(cursor) {
  cursor.skipSpace()
  const ch = cursor.peek()
  if (ch === "") cursor.fail("unexpected end of input")
  if (ch === ")") cursor.fail("unexpected ')'")
  if (ch === "(") {
    cursor.at += 1
    const items = []
    for (;;) {
      cursor.skipSpace()
      if (cursor.peek() === "") cursor.fail("unterminated list")
      if (cursor.peek() === ")") {
        cursor.at += 1
        return { kind: "list", items }
      }
      items.push(readSx(cursor))
    }
  }
  if (ch === '"') return { kind: "atom", value: readString(cursor) }
  return { kind: "atom", value: readAtom(cursor) }
}

/** Parse exactly one S-expression; trailing content is an error, not ignored. */
export function parseOne(text) {
  const cursor = new Cursor(text)
  const value = readSx(cursor)
  cursor.skipSpace()
  if (cursor.at !== cursor.text.length) cursor.fail("trailing content after the capsule")
  return value
}

// ── CoreIR scope validation ──────────────────────────────────────────────────────────────────────
// The same rules the JVM reader enforces (`CoreIR.validate`): a `(local i)` must be in range for the
// binder depth, and a `(global g)` must be a def of the SAME program or an `@`-cell. There is no
// builtin escape hatch — which is exactly why the global-closure slice had to carry reached defs.

function isList(sx, head) {
  return sx.kind === "list" && sx.items.length > 0 && sx.items[0].kind === "atom" &&
    sx.items[0].value === head
}

function natOf(sx, what) {
  if (sx.kind !== "atom" || !/^[0-9]+$/.test(sx.value)) {
    throw new Error(`portable-capsule: ${what} must be a non-negative integer`)
  }
  return Number(sx.value)
}

/**
 * Validate a `(program (defs ...) (entry ...))` s-expression. Throws with the offending node named,
 * in the JVM reader's style — a diagnostic that does not say WHICH node is a diagnostic in name only.
 */
export function validateProgram(program) {
  if (!isList(program, "program")) throw new Error("portable-capsule: not a (program ...)")
  const [, defsNode, entryNode] = program.items
  if (!isList(defsNode, "defs")) throw new Error("portable-capsule: missing (defs ...)")
  if (!isList(entryNode, "entry")) throw new Error("portable-capsule: missing (entry ...)")

  const defNames = new Set()
  for (const def of defsNode.items.slice(1)) {
    if (!isList(def, "def") || def.items.length < 3 || def.items[1].kind !== "atom") {
      throw new Error("portable-capsule: malformed (def name body)")
    }
    defNames.add(def.items[1].value)
  }

  const globalOk = (g) => defNames.has(g) || g.startsWith("@")

  const go = (node, depth) => {
    if (node.kind === "atom") return
    if (isList(node, "local")) {
      const index = natOf(node.items[1], "(local i)")
      if (index >= depth) {
        throw new Error(
          `portable-capsule: local index out of range: (local ${index}) with ${depth} binder(s) in scope`
        )
      }
      return
    }
    if (isList(node, "global")) {
      const name = node.items[1]?.value ?? ""
      if (!globalOk(name)) {
        throw new Error(
          `portable-capsule: unbound global: (global ${name}) is neither a top-level def nor an @-cell`
        )
      }
      return
    }
    if (isList(node, "lam")) {
      const arity = natOf(node.items[1], "(lam arity)")
      for (const child of node.items.slice(2)) go(child, depth + arity)
      return
    }
    if (isList(node, "arm")) {
      // (arm Tag arity body) — the arm's binders extend the scope for its body only.
      const arity = natOf(node.items[2], "(arm tag arity)")
      for (const child of node.items.slice(3)) go(child, depth + arity)
      return
    }
    if (isList(node, "let")) {
      // let* : rhs i sees i earlier binders, the body sees all of them.
      const bindings = node.items.slice(1, node.items.length - 1)
      bindings.forEach((rhs, i) => go(rhs, depth + i))
      go(node.items[node.items.length - 1], depth + bindings.length)
      return
    }
    if (isList(node, "letrec")) {
      const count = node.items.length - 2
      for (const child of node.items.slice(1)) go(child, depth + count)
      return
    }
    for (const child of node.items) go(child, depth)
  }

  for (const def of defsNode.items.slice(1)) go(def.items[2], 0)
  for (const child of entryNode.items.slice(1)) go(child, 0)
  return { defNames, entry: entryNode.items[1], defs: defsNode.items.slice(1) }
}
