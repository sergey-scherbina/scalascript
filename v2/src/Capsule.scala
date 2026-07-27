package ssc

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * A **Portable-CodeMode** capsule for the reference VM (control-interoperability §10.1,
 * `Portable(resumeCodeDigest, closedResumeProgram)`): the resume PROGRAM travels as
 * closed CoreIR bytes, so a runner that holds **no machine** can execute it. This is the
 * VM-side counterpart of the host SDK's ExactArtifact capsule
 * (`v2/host/scala/control/.../DurableCapsule.scala`), where the machine instead stays in
 * memory and only the frame/id/ABI travel.
 *
 * Envelope (canonical S-expr, parsed by the existing bounded, fail-closed `Reader`):
 * {{{
 * (portable-capsule (version N) (resume-digest HEX) (frame-int K) (resume PROGRAM))
 * }}}
 * `PROGRAM` is `Writer.program(resume)`; the resume program's `entry` is a `Lam(2, body)`
 * = `(decodedFrame, input) => R`. `resume-digest` is a domain-separated SHA-256 over the
 * canonical resume bytes. Admission (`decode`) re-parses and **re-validates** the resume
 * with `Reader.validate` (the untrusted-capsule fail-closed contract) and re-checks the
 * digest before anything runs; it never executes the program (§9.2 inert decode).
 *
 * Scope: this is the fresh-process / no-original-artifact milestone (§14.3 items 10-11).
 * The resume program here is hand-authored; the §10.2 compiler pass that GENERATES a
 * closed resume program from an arbitrary `.ssc` saveable region, and a second admitting
 * backend for the full §14.4 cross-backend N→M matrix, remain separate work.
 */
final case class PortableCapsule(
    version: Int,
    frame: Term,
    resume: Program,
    audience: String = "",
    tenant: String = "",
    budget: Long = 0L
)

/** The admission policy a runner applies, and a freezer binds into the capsule — the CLI-side
 *  counterpart of the host lane's `AdmissionPolicy` (`v2/host/scala/control/.../DurableCapsule.scala`).
 *  Read from the environment because this lane is a CLI where the host takes an object.
 *  `specs/portable-capsule-seal.md` §4. The KEY never travels in the capsule. */
final case class CapsulePolicy(key: String, audience: String, tenant: String, budget: Long)

object CapsulePolicy:
  private def env(n: String): String = sys.env.getOrElse(n, "")
  /** Empty key = the trusted in-process path (unsigned) — the host's default, adopted verbatim. */
  def fromEnv: CapsulePolicy =
    CapsulePolicy(env("SSC_CAPSULE_KEY"), env("SSC_CAPSULE_AUDIENCE"), env("SSC_CAPSULE_TENANT"),
      env("SSC_CAPSULE_BUDGET").toLongOption.getOrElse(0L))
  def runnerBudget: Long = sys.env.get("SSC_CAPSULE_RUNNER_BUDGET").flatMap(_.toLongOption).getOrElse(Long.MaxValue)

object Capsule:
  /** v2 adds the security envelope (`specs/portable-capsule-seal.md` §3). v1 stays ADMISSIBLE as
   *  legacy/unsigned — a keyed runner rejects it anyway, and it is what lets the committed
   *  `fixtures/fx-open.portable` keep testing the Fx-closed guard: the tool can no longer produce
   *  an Fx-open capsule, by design. */
  val Version: Int = 2
  val LegacyVersion: Int = 1

  // "ssc-portable-capsule-v1\0" — domain separator for the resume-code digest.
  private val Domain: Array[Byte] =
    "ssc-portable-capsule-v1".getBytes(StandardCharsets.UTF_8) :+ 0.toByte

  // "ssc-portable-capsule-sig-v1\0" — a SEPARATE domain for the keyed body signature, so a digest
  // can never be replayed as a signature or vice versa.
  private val SigDomain: Array[Byte] =
    "ssc-portable-capsule-sig-v1".getBytes(StandardCharsets.UTF_8) :+ 0.toByte

  private def hex(bs: Array[Byte]): String = bs.map(b => f"${b & 0xff}%02x").mkString

  private def sha256(bs: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bs)

  // HMAC-SHA256, mirroring the host lane's hand-rolled construction (block size 64) so the two
  // lanes agree bit-for-bit on what a signature is.
  private val HmacBlockSize = 64
  private def hmacSha256(key: Array[Byte], message: Array[Byte]): Array[Byte] =
    val normalized = if key.length > HmacBlockSize then sha256(key) else key
    val padded = java.util.Arrays.copyOf(normalized, HmacBlockSize)
    val inner = new Array[Byte](HmacBlockSize + message.length)
    val outer = new Array[Byte](HmacBlockSize + 32)
    var i = 0
    while i < HmacBlockSize do
      inner(i) = (padded(i) ^ 0x36).toByte
      outer(i) = (padded(i) ^ 0x5c).toByte
      i += 1
    System.arraycopy(message, 0, inner, HmacBlockSize, message.length)
    val innerHash = sha256(inner)
    System.arraycopy(innerHash, 0, outer, HmacBlockSize, innerHash.length)
    sha256(outer)

  /** Constant-time comparison — a signature check that leaks timing is not a signature check. */
  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then false
    else
      var diff = 0
      var i = 0
      while i < a.length do { diff |= (a.charAt(i) ^ b.charAt(i)); i += 1 }
      diff == 0

  private def digestOf(resume: Program): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.update(Domain)
    md.update(Writer.program(resume).getBytes(StandardCharsets.UTF_8))
    md.digest().map(b => f"${b & 0xff}%02x").mkString

  /**
   * Freeze a captured frame value (a first-order CoreIR value term — a `Lit`, or a `Ctor`
   * of `Lit`s for a multi-slot frame) plus a closed resume program into capsule bytes. Pure.
   */
  def encode(frame: Term, resume: Program): String = encode(frame, resume, CapsulePolicy.fromEnv)

  /** The canonical body with a GIVEN signature slot. The signature is computed over this string
   *  with an EMPTY slot, so a forged edit of any other field — including the frame, which the
   *  code-only `resume-digest` never covered — changes the message and breaks the HMAC. */
  private def body(frame: Term, resume: Program, p: CapsulePolicy, sig: String): String =
    s"(portable-capsule (version $Version) (resume-digest ${digestOf(resume)}) " +
      s"(audience ${p.audience}) (tenant ${p.tenant}) (budget ${p.budget}) (signature $sig) " +
      s"(frame ${Writer.term(frame)}) (resume ${Writer.program(resume)}))"

  private def signatureFor(frame: Term, resume: Program, p: CapsulePolicy): String =
    if p.key.isEmpty then ""
    else
      val msg = body(frame, resume, p, "").getBytes(StandardCharsets.UTF_8)
      val message = new Array[Byte](SigDomain.length + msg.length)
      System.arraycopy(SigDomain, 0, message, 0, SigDomain.length)
      System.arraycopy(msg, 0, message, SigDomain.length, msg.length)
      hex(hmacSha256(p.key.getBytes(StandardCharsets.UTF_8), message))

  def encode(frame: Term, resume: Program, p: CapsulePolicy): String =
    body(frame, resume, p, signatureFor(frame, resume, p))

  /**
   * Admit capsule bytes: parse the envelope, re-validate the resume program fail-closed,
   * and re-check the digest. Inert — never runs the program. A version mismatch, a
   * malformed resume, or a digest mismatch (tamper) all reject with a diagnostic.
   */
  def decode(src: String): PortableCapsule =
    Reader.parseOne(src) match
      case Sx.Lst(Sx.Atom("portable-capsule") :: fields) =>
        val version = intField(fields, "version")
        if version != Version && version != LegacyVersion then
          sys.error(s"portable-capsule: unsupported version $version")
        val runner = CapsulePolicy.fromEnv
        val declaredDigest = atomField(fields, "resume-digest")
        val audience = optAtom(fields, "audience")
        val tenant   = optAtom(fields, "tenant")
        val budget   = optAtom(fields, "budget").toLongOption.getOrElse(0L)
        val declaredSig = optAtom(fields, "signature")
        val frame = Reader.toTerm(subField(fields, "frame"))
        val resume = Reader.toProgram(subField(fields, "resume"))

        // §5 admission order — cheapest and most specific first, all fail CLOSED.
        // (2) SIGNATURE. A keyed runner refuses anything it cannot verify: a v1 legacy capsule, an
        // unsigned v2, or a bad signature. An UNKEYED runner has nothing to verify with, so a
        // signature present in the bytes admits nothing extra — the capsule is simply unsigned.
        // This conditionality is the host's own contract, adopted deliberately (spec §2).
        if runner.key.nonEmpty then
          if version == LegacyVersion then
            sys.error("portable-capsule: a keyed runner does not admit a v1 (unsigned legacy) capsule")
          if declaredSig.isEmpty then
            sys.error("portable-capsule: signature missing — a keyed runner does not admit an unsigned capsule")
          val bound = CapsulePolicy(runner.key, audience, tenant, budget)
          if !constantTimeEquals(signatureFor(frame, resume, bound), declaredSig) then
            sys.error("portable-capsule: signature mismatch (tampered)")
          // (3) AUDIENCE / TENANT — a capsule addressed to a different runner.
          if audience != runner.audience then
            sys.error(s"portable-capsule: audience mismatch (capsule '$audience', runner '${runner.audience}')")
          if tenant != runner.tenant then
            sys.error(s"portable-capsule: tenant mismatch (capsule '$tenant', runner '${runner.tenant}')")
        // (4) BUDGET — a RESOURCE failure, kept distinct from tampering (§13 non-collapsibility:
        // a quota problem must not be reported as an attack). Checked even unkeyed: it is a
        // resource statement, not a security one.
        if budget > CapsulePolicy.runnerBudget then
          sys.error(s"portable-capsule: required budget $budget exceeds the runner's ${CapsulePolicy.runnerBudget} (resource limit)")

        validateFrame(frame)    // (6) fail CLOSED — the frame is DATA, never code (see below)
        Reader.validate(resume) // (5) fail CLOSED — untrusted resume program
        if digestOf(resume) != declaredDigest then // (7) the CODE digest, unchanged
          sys.error("portable-capsule: resume-digest mismatch (tampered)")
        PortableCapsule(version, frame, resume, audience, tenant, budget)
      case _ => sys.error("portable-capsule: bad envelope")

  /**
   * Run the capsule on THIS host with no pre-held machine: reconstruct the resume program
   * entirely from decoded bytes and apply its entry to `(frame, input)`. The driver wraps
   * the (already validated) resume entry in an application of the captured frame + the
   * caller's input, then compiles and runs it on the ordinary VM.
   */
  def run(capsule: PortableCapsule, input: Long): Value =
    val driver = Program(
      capsule.resume.defs,
      Term.App(
        capsule.resume.entry,
        List(capsule.frame, Term.Lit(Const.CInt(input)))
      )
    )
    val result = Runtime.runManaged(Compiler.compile(driver), Array.empty[Value])
    // Defence in depth for §11.3 (Fx-closed). `SaveRegion.assertFxClosed` refuses an Fx-OPEN region
    // at reify time, but a capsule can be produced by anything — and this runner holds no machine
    // and no handlers, so an escaping `Op` has nobody to interpret it. Measured before this guard:
    // a region performing with no handler resumed to `Op("E.get", 8, <closure>)`, i.e. a LIVE
    // continuation handed out as a result. Fail closed instead.
    result match
      case Value.DataV("Op", fields) =>
        val label = fields.headOption.map(Show.show).getOrElse("?")
        sys.error(
          s"portable-capsule: the resume escaped an unhandled effect $label — a capsule must be " +
            "Fx-closed (§11.3); this runner holds no machine and no handlers to interpret it"
        )
      case other => other

  /**
   * Fail-closed admission for the capsule's **frame** — the half that used to escape it
   * (`BUGS.md` `portable-capsule-frame-unvalidated`). `decode` validated the resume program and
   * re-checked its digest, then spliced the frame into `App(entry, [frame, input])` as an
   * arbitrary `Term`: a frame carrying `(global g)` injected a **closure** into the resume (with
   * E1 carrying the reached defs, a real one), and `(local 0)` reached `Compiler.compile` as an
   * out-of-scope index and died with `ArrayIndexOutOfBoundsException` instead of a diagnostic.
   *
   * A frame is **data, never code** (§10.1: in Portable CodeMode the code is what travels as
   * *validated* bytes). This admits exactly the first-order value terms — a literal, or a
   * constructor whose fields are recursively values — which is also slice 3's definition of a
   * NOMINAL frame: `Ctor` nesting is what carries a structured slot, so allowing it here is the
   * feature, and allowing anything else is the hole.
   *
   * Everything else is rejected naming the offending node, in `Reader.validate`'s style. In
   * particular `Lam` is rejected: a lambda is a value at runtime but it is *code* in the bytes,
   * and admitting it would re-open exactly the boundary this closes.
   */
  def validateFrame(frame: Term): Unit =
    def go(t: Term, path: String): Unit = t match
      case Term.Lit(_) => ()
      case Term.Ctor(tag, fields) =>
        fields.iterator.zipWithIndex.foreach { case (f, i) => go(f, s"$path/$tag[$i]") }
      case other =>
        sys.error(
          s"portable-capsule: frame must be a first-order value (literals and constructors only), " +
            s"got ${frameNodeName(other)} at $path — a frame carries DATA, never code"
        )
    go(frame, "frame")

  // Reader.nodeName is private to Reader; this names the nodes a frame can wrongly contain.
  private def frameNodeName(t: Term): String = t match
    case Term.Local(i)  => s"(local $i)"
    case Term.Global(g) => s"(global $g)"
    case Term.Lam(a, _) => s"(lam $a ...)"
    case Term.App(_, _) => "(app ...)"
    case Term.Prim(o, _) => s"(prim $o ...)"
    case Term.Let(_, _) => "(let ...)"
    case Term.LetRec(_, _) => "(letrec ...)"
    case Term.If(_, _, _) => "(if ...)"
    case Term.Match(_, _, _) => "(match ...)"
    case Term.While(_, _) => "(while ...)"
    case Term.Seq(_) => "(seq ...)"
    case Term.Lit(_) => "(lit ...)"
    case Term.Ctor(tag, _) => s"(ctor $tag ...)"

  /** The hand-authored demo resume machine `(frame, input) => frame * 10 + input`
    * (`Local(1)` = the captured frame, `Local(0)` = the run input). */
  def demoResume: Program =
    Program(
      Nil,
      Term.Lam(
        2,
        Term.Prim(
          "i.add",
          List(
            Term.Prim("i.mul", List(Term.Local(1), Term.Lit(Const.CInt(10)))),
            Term.Local(0)
          )
        )
      )
    )

  // -- field extraction from the parsed envelope --

  private def field(fields: List[Sx], key: String): Sx =
    fields
      .collectFirst { case l @ Sx.Lst(Sx.Atom(`key`) :: _) => l }
      .getOrElse(sys.error(s"portable-capsule: missing ($key ...)"))

  private def atomField(fields: List[Sx], key: String): String =
    field(fields, key) match
      case Sx.Lst(_ :: Sx.Atom(v) :: Nil) => v
      case _                              => sys.error(s"portable-capsule: bad ($key ...)")

  private def intField(fields: List[Sx], key: String): Int =
    atomField(fields, key).toIntOption
      .getOrElse(sys.error(s"portable-capsule: $key is not an int"))

  /** An optional atom field — absent in a v1 legacy envelope, and legitimately EMPTY in a v2 one
   *  (an unsigned capsule, or no audience binding), so absence and "" are the same answer. */
  private def optAtom(fields: List[Sx], key: String): String =
    fields.collectFirst {
      case Sx.Lst(Sx.Atom(`key`) :: Sx.Atom(v) :: Nil) => v
      case Sx.Lst(Sx.Atom(`key`) :: Nil)               => ""
    }.getOrElse("")

  private def subField(fields: List[Sx], key: String): Sx =
    field(fields, key) match
      case Sx.Lst(_ :: sub :: Nil) => sub
      case _                       => sys.error(s"portable-capsule: bad ($key ...)")
