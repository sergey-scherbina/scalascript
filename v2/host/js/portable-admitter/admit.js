// Admission for a Portable capsule on a NON-JVM runtime — the second admitting backend conformance
// vector 15 requires (§14.4 N→M). `specs/portable-capsule-seal.md` is the contract; this file
// reproduces it rather than paraphrasing it, because a second admitter that is more permissive than
// the first proves nothing about cross-host parity.
//
// The two domain separators, the "canonical body with an EMPTY signature slot" signing message, and
// the admission ORDER are all copied deliberately. Where the JVM side rejects, this must reject.

import { createHash, createHmac, timingSafeEqual } from "node:crypto"
import { parseOne, validateProgram } from "./reader.js"

const DIGEST_DOMAIN = Buffer.concat([Buffer.from("ssc-portable-capsule-v1", "utf8"), Buffer.from([0])])
const SIG_DOMAIN = Buffer.concat([Buffer.from("ssc-portable-capsule-sig-v1", "utf8"), Buffer.from([0])])

const VERSION = 2
const LEGACY_VERSION = 1

function reject(message) {
  throw new Error(`portable-capsule: ${message}`)
}

function field(items, key) {
  const found = items.find(
    (sx) => sx.kind === "list" && sx.items[0]?.kind === "atom" && sx.items[0].value === key
  )
  return found ?? null
}

/** An optional atom field — absent in a v1 envelope and legitimately EMPTY in a v2 one (an unsigned
 *  capsule, or no audience binding), so absence and "" are the same answer, as on the JVM side. */
function optAtom(items, key) {
  const found = field(items, key)
  if (found === null) return ""
  const value = found.items[1]
  return value === undefined ? "" : value.kind === "atom" ? value.value : ""
}

function requiredSub(items, key) {
  const found = field(items, key)
  if (found === null || found.items.length !== 2) reject(`missing (${key} ...)`)
  return found.items[1]
}

/** Render an s-expression back to canonical text — byte-identical to what `Writer` produced, which
 *  is what makes recomputing the digest and the signature meaningful. */
export function render(sx) {
  if (sx.kind === "atom") return sx.value
  return `(${sx.items.map(render).join(" ")})`
}

function sha256(...parts) {
  const hash = createHash("sha256")
  for (const part of parts) hash.update(part)
  return hash.digest("hex")
}

function digestOf(programSx) {
  return sha256(DIGEST_DOMAIN, Buffer.from(render(programSx), "utf8"))
}

/** The canonical body with a GIVEN signature slot — the exact string the JVM signer builds. */
function body(fields, signature) {
  const digest = optAtom(fields, "resume-digest")
  const audience = optAtom(fields, "audience")
  const tenant = optAtom(fields, "tenant")
  const budget = optAtom(fields, "budget")
  const frame = render(requiredSub(fields, "frame"))
  const resume = render(requiredSub(fields, "resume"))
  return `(portable-capsule (version ${VERSION}) (resume-digest ${digest}) ` +
    `(audience ${audience}) (tenant ${tenant}) (budget ${budget}) (signature ${signature}) ` +
    `(frame ${frame}) (resume ${resume}))`
}

function signatureFor(fields, key) {
  const message = Buffer.concat([SIG_DOMAIN, Buffer.from(body(fields, ""), "utf8")])
  return createHmac("sha256", Buffer.from(key, "utf8")).update(message).digest("hex")
}

function constantTimeEquals(a, b) {
  const left = Buffer.from(a, "utf8")
  const right = Buffer.from(b, "utf8")
  if (left.length !== right.length) return false
  return timingSafeEqual(left, right)
}

/** A frame is DATA, never code: literals and constructors, recursively. Same rule as the JVM's
 *  `Capsule.validateFrame` — `lam` is rejected too, because a lambda is a value at runtime but it is
 *  CODE in the bytes, and admitting it re-opens the boundary the guard exists to close. */
function validateFrame(sx, path = "frame") {
  if (sx.kind === "atom") reject(`frame must be a first-order value, got the atom '${sx.value}' at ${path}`)
  const head = sx.items[0]
  if (head?.kind !== "atom") reject(`frame node has no head at ${path}`)
  if (head.value === "lit") return
  if (head.value === "ctor") {
    sx.items.slice(2).forEach((fieldSx, i) => validateFrame(fieldSx, `${path}/${sx.items[1]?.value}[${i}]`))
    return
  }
  reject(
    `frame must be a first-order value (literals and constructors only), got (${head.value} ...) ` +
      `at ${path} — a frame carries DATA, never code`
  )
}

/**
 * Admit capsule bytes on this host. Inert: it never runs the program.
 *
 * Admission order, copied from `specs/portable-capsule-seal.md` §5 — cheapest and most specific
 * first, all fail-closed: version, signature, audience/tenant, budget, program scope validation,
 * frame validation, resume digest.
 *
 * `policy` mirrors the JVM runner's environment: `{ key, audience, tenant, runnerBudget }`. An empty
 * key is the trusted in-process path — the host lane's own contract, adopted rather than tightened,
 * because "both lanes promise the same thing" is the point.
 */
export function admit(text, policy = {}) {
  const key = policy.key ?? ""
  const runnerAudience = policy.audience ?? ""
  const runnerTenant = policy.tenant ?? ""
  const runnerBudget = policy.runnerBudget ?? Number.MAX_SAFE_INTEGER

  const envelope = parseOne(text)
  if (!(envelope.kind === "list" && envelope.items[0]?.value === "portable-capsule")) {
    reject("bad envelope")
  }
  const fields = envelope.items.slice(1)

  const version = Number(optAtom(fields, "version"))
  if (version !== VERSION && version !== LEGACY_VERSION) reject(`unsupported version ${version}`)

  const audience = optAtom(fields, "audience")
  const tenant = optAtom(fields, "tenant")
  const budget = Number(optAtom(fields, "budget") || "0")
  const declaredSig = optAtom(fields, "signature")

  if (key !== "") {
    if (version === LEGACY_VERSION) {
      reject("a keyed runner does not admit a v1 (unsigned legacy) capsule")
    }
    if (declaredSig === "") {
      reject("signature missing — a keyed runner does not admit an unsigned capsule")
    }
    if (!constantTimeEquals(signatureFor(fields, key), declaredSig)) {
      reject("signature mismatch (tampered)")
    }
    if (audience !== runnerAudience) {
      reject(`audience mismatch (capsule '${audience}', runner '${runnerAudience}')`)
    }
    if (tenant !== runnerTenant) {
      reject(`tenant mismatch (capsule '${tenant}', runner '${runnerTenant}')`)
    }
  }
  if (budget > runnerBudget) {
    reject(`required budget ${budget} exceeds the runner's ${runnerBudget} (resource limit)`)
  }

  const resume = requiredSub(fields, "resume")
  const program = validateProgram(resume)
  const frame = requiredSub(fields, "frame")
  validateFrame(frame)

  const declaredDigest = optAtom(fields, "resume-digest")
  if (digestOf(resume) !== declaredDigest) reject("resume-digest mismatch (tampered)")

  return { version, audience, tenant, budget, frame, resume, program }
}
