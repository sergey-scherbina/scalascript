// A CoreIR evaluator for admitted Portable capsules, on a non-JVM runtime.
//
// Scope is deliberately the node set a REIFIED RESUME PROGRAM uses (`SaveRegion`): lit / local /
// global / lam / app / prim / let / letrec / if / ctor / match / seq. It is not a general ssc
// interpreter and does not pretend to be — an admitter that silently mis-evaluates a node it does
// not really support would be worse than one that refuses it, so every unknown node is an error.
//
// ⚠️ `Int` IS 64-BIT. Every integer is a BigInt, never a JS number. Using `number` would diverge on
// 2^31, max64 and 2^53+1 — the exact class that once reverted the bytecode-default switch
// (`specs/v2-f5c-typed-bytecode.md` §8). The only place a Number appears is an arity or an index.

const INT64_MIN = -(2n ** 63n)
const INT64_MAX = 2n ** 63n - 1n

function fail(message) {
  throw new Error(`portable-capsule: ${message}`)
}

function head(sx) {
  return sx.kind === "list" && sx.items[0]?.kind === "atom" ? sx.items[0].value : null
}

function wrap64(value) {
  // Two's-complement wrap, matching the kernel's Long semantics rather than JS's unbounded BigInt.
  const span = 2n ** 64n
  let wrapped = ((value - INT64_MIN) % span + span) % span + INT64_MIN
  if (wrapped > INT64_MAX) wrapped -= span
  return wrapped
}

function litValue(sx) {
  const kind = head(sx)
  if (kind === "int") return wrap64(BigInt(sx.items[1].value))
  if (kind === "str") {
    const raw = sx.items[1].value
    if (!raw.startsWith('"')) fail("string literal is not quoted")
    return raw.slice(1, -1).replace(/\\"/g, '"').replace(/\\\\/g, "\\")
  }
  if (kind === null && sx.kind === "atom") {
    if (sx.value === "true") return true
    if (sx.value === "false") return false
    if (sx.value === "unit") return { unit: true }
  }
  fail(`unsupported literal ${JSON.stringify(sx)}`)
}

const closure = (arity, body, env) => ({ closure: true, arity, body, env })
const ctor = (tag, fields) => ({ ctor: tag, fields })

/** Integer prims. Names and semantics track the kernel's typed `i.*` family. */
const INT_PRIMS = {
  "i.add": (a, b) => wrap64(a + b),
  "i.sub": (a, b) => wrap64(a - b),
  "i.mul": (a, b) => wrap64(a * b),
  "i.div": (a, b) => (b === 0n ? fail("integer division by zero") : wrap64(a / b)),
  "i.mod": (a, b) => (b === 0n ? fail("integer modulo by zero") : wrap64(a % b)),
  "i.lt": (a, b) => a < b,
  "i.le": (a, b) => a <= b,
  "i.gt": (a, b) => a > b,
  "i.ge": (a, b) => a >= b,
  "i.eq": (a, b) => a === b
}

export function evaluate(program, frameValue, input) {
  const globals = new Map()
  for (const def of program.defs) {
    globals.set(def.items[1].value, { thunk: def.items[2] })
  }

  const lookupGlobal = (name) => {
    const slot = globals.get(name)
    if (slot === undefined) fail(`unbound global: (global ${name})`)
    // Defs are closed by construction, so a def body evaluates in the empty environment. Memoised
    // so a recursive def does not re-evaluate its own lambda on every call.
    if (slot.thunk !== undefined) {
      const value = go(slot.thunk, [])
      globals.set(name, { value })
      return value
    }
    return slot.value
  }

  const apply = (fn, args) => {
    if (!fn || fn.closure !== true) fail("applied a non-function")
    if (args.length !== fn.arity) {
      fail(`arity mismatch: expected ${fn.arity} argument(s), got ${args.length}`)
    }
    // The env is innermost-first, matching the de-Bruijn convention: the LAST argument is index 0.
    return go(fn.body, [...args].reverse().concat(fn.env))
  }

  function go(sx, env) {
    if (sx.kind === "atom") fail(`bare atom '${sx.value}' where a term was expected`)
    const kind = head(sx)
    switch (kind) {
      case "lit":
        return litValue(sx.items[1])
      case "local": {
        const index = Number(sx.items[1].value)
        if (index >= env.length) fail(`local index out of range: (local ${index})`)
        return env[index]
      }
      case "global":
        return lookupGlobal(sx.items[1].value)
      case "lam":
        return closure(Number(sx.items[1].value), sx.items[2], env)
      case "app": {
        const fn = go(sx.items[1], env)
        return apply(fn, sx.items.slice(2).map((arg) => go(arg, env)))
      }
      case "prim": {
        const op = sx.items[1].value
        const args = sx.items.slice(2).map((arg) => go(arg, env))
        const intPrim = INT_PRIMS[op]
        if (intPrim !== undefined) return intPrim(args[0], args[1])
        if (op === "sconcat") return String(args[0]) + String(args[1])
        if (op === "slen") return BigInt(String(args[0]).length)
        fail(`unsupported prim '${op}' — this admitter refuses what it cannot evaluate exactly`)
        return undefined
      }
      case "let": {
        // let* : each rhs sees the binders before it; the body sees them all.
        let scope = env
        for (const rhs of sx.items.slice(1, sx.items.length - 1)) {
          scope = [go(rhs, scope), ...scope]
        }
        return go(sx.items[sx.items.length - 1], scope)
      }
      case "letrec": {
        const lams = sx.items.slice(1, sx.items.length - 1)
        const scope = []
        const extended = scope.concat(env)
        const made = lams.map((lam) => closure(Number(lam.items[1].value), lam.items[2], extended))
        // Tie the knot: each lambda's env must contain all the letrec bindings, innermost-first.
        made.slice().reverse().forEach((value) => extended.unshift(value))
        return go(sx.items[sx.items.length - 1], extended)
      }
      case "if":
        return go(sx.items[1], env) === true ? go(sx.items[2], env) : go(sx.items[3], env)
      case "ctor":
        return ctor(sx.items[1].value, sx.items.slice(2).map((f) => go(f, env)))
      case "match": {
        const scrutinee = go(sx.items[1], env)
        const arms = sx.items[2].items
        for (const arm of arms) {
          if (head(arm) !== "arm") fail("malformed match arm")
          const tag = arm.items[1].value
          if (scrutinee && scrutinee.ctor === tag) {
            // Arm binders are innermost-first, same as application.
            return go(arm.items[3], [...scrutinee.fields].reverse().concat(env))
          }
        }
        const fallback = sx.items[3]
        if (fallback !== undefined && head(fallback) === "default") return go(fallback.items[1], env)
        fail("match: no matching case")
        return undefined
      }
      case "seq": {
        let last
        for (const child of sx.items.slice(1)) last = go(child, env)
        return last
      }
      default:
        fail(`unsupported node (${kind} ...) — refused rather than guessed`)
        return undefined
    }
  }

  const entry = go(program.entry, [])
  return apply(entry, [go(frameValue, []), input])
}

export { wrap64 }
