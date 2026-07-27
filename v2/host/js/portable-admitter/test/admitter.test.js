// Cross-host tests for the non-JVM Portable admitter (conformance vector 15, §14.4 N→M).
//
// Every fixture here was frozen by the JVM lane and is committed alongside these tests. That is the
// point: an admitter tested against capsules it produced itself is a self-consistent oracle and
// cannot observe a cross-lane divergence — the same trap `specs/newfront-diff.sh` and the scljet
// address cases were caught by. The bytes must come from the OTHER lane.

import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"
import { admit } from "../admit.js"
import { evaluate } from "../eval.js"

const fixture = (name) =>
  readFileSync(new URL(`./fixtures/${name}`, import.meta.url), "utf8")

const KEYED = { key: "xkey", audience: "aud1", tenant: "ten1" }

test("a JVM-frozen unsigned capsule is admitted and RUN, matching the JVM's own results", () => {
  const capsule = admit(fixture("unsigned.portable"))
  // quad(input) + a, with a = 5 — the JVM runner prints 17 and 9 for these inputs.
  assert.equal(evaluate(capsule.program, capsule.frame, 3n), 17n)
  assert.equal(evaluate(capsule.program, capsule.frame, 1n), 9n)
})

test("a JVM-SEALED capsule verifies under the same key, and runs to the same values", () => {
  // This is the cross-lane statement that matters: the HMAC construction, the domain separator and
  // the canonical body layout agree bit-for-bit with the JVM signer, or this cannot verify at all.
  const capsule = admit(fixture("sealed.portable"), KEYED)
  assert.equal(evaluate(capsule.program, capsule.frame, 3n), 17n)
})

test("Int is 64-BIT — the admitter agrees with the JVM including two's-complement wrap", () => {
  // The trap this whole lane has to survive: using JS `number` diverges at 2^31, max64 and 2^53+1.
  // These three expectations were produced by the JVM runner on the SAME bytes.
  const cases = [
    ["int64-max.portable", -9223372036854775797n], // max64 + 12 wraps
    ["int64-2p31.portable", 2147483660n],
    ["int64-2p53.portable", 9007199254741005n]
  ]
  for (const [name, expected] of cases) {
    const capsule = admit(fixture(name))
    assert.equal(evaluate(capsule.program, capsule.frame, 3n), expected, name)
  }
})

test("every tamper the JVM rejects is rejected here, with the same reason", () => {
  const sealed = fixture("sealed.portable")
  const unsigned = fixture("unsigned.portable")
  const rejects = (name, run, needle) => {
    assert.throws(run, (error) => error.message.includes(needle), name)
  }
  rejects("wrong key", () => admit(sealed, { ...KEYED, key: "nope" }), "signature mismatch")
  rejects("audience", () => admit(sealed, { ...KEYED, audience: "other" }), "audience mismatch")
  rejects("tenant", () => admit(sealed, { ...KEYED, tenant: "other" }), "tenant mismatch")
  rejects(
    "frame edited under a key",
    () => admit(sealed.replace("(lit (int 5))", "(lit (int 99))"), KEYED),
    "signature mismatch"
  )
  rejects(
    "resume program edited",
    () => admit(unsigned.replace("i.mul", "i.sub")),
    "resume-digest mismatch"
  )
  rejects(
    "unsigned capsule at a keyed runner",
    () => admit(unsigned.replace(/\(signature [0-9a-f]*\)/, "(signature )"), KEYED),
    "signature missing"
  )
  rejects("budget over the runner's", () => admit(unsigned, { runnerBudget: -1 }), "resource limit")
})

test("a frame carrying CODE is refused — a frame is DATA (parity with the JVM's validateFrame)", () => {
  const unsigned = fixture("unsigned.portable")
  for (const injected of ["(global dbl)", "(local 0)", "(lam 1 (local 0))"]) {
    assert.throws(
      () => admit(unsigned.replace("(lit (int 5))", injected)),
      (error) => error.message.includes("frame") || error.message.includes("digest"),
      injected
    )
  }
  // …and a DATA-only edit still admits on the unsigned path, so the three above cannot be passing
  // for the trivial reason that any edit fails.
  const edited = admit(unsigned.replace("(lit (int 5))", "(lit (int 99))"))
  assert.equal(evaluate(edited.program, edited.frame, 3n), 111n)
})

test("the reader refuses what it cannot represent exactly, rather than guessing", () => {
  assert.throws(() => admit("(portable-capsule (version 2)) trailing"), /trailing content/)
  assert.throws(() => admit("(portable-capsule (version 9) (frame (lit (int 1))) (resume x))"),
    /unsupported version 9/)
  assert.throws(() => admit("not-a-list"), /bad envelope/)
})

test("an unsupported prim is an ERROR, never a silent wrong answer", () => {
  // An admitter that quietly mis-evaluates a node it does not really support is worse than one that
  // refuses it — the refusal is what keeps the two lanes honest as the IR grows.
  const capsule = admit(fixture("unsigned.portable"))
  const broken = JSON.parse(JSON.stringify(capsule.program))
  const rewrite = (node) => {
    if (node.kind === "list") {
      if (node.items[0]?.value === "prim" && node.items[1]) node.items[1].value = "totally.unknown"
      node.items.forEach(rewrite)
    }
  }
  rewrite(broken.entry)
  broken.defs.forEach((d) => rewrite(d))
  assert.throws(() => evaluate(broken, capsule.frame, 3n), /unsupported prim/)
})
