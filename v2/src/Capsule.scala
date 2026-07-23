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
final case class PortableCapsule(version: Int, frame: Term, resume: Program)

object Capsule:
  val Version: Int = 1

  // "ssc-portable-capsule-v1\0" — domain separator for the resume-code digest.
  private val Domain: Array[Byte] =
    "ssc-portable-capsule-v1".getBytes(StandardCharsets.UTF_8) :+ 0.toByte

  private def digestOf(resume: Program): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.update(Domain)
    md.update(Writer.program(resume).getBytes(StandardCharsets.UTF_8))
    md.digest().map(b => f"${b & 0xff}%02x").mkString

  /**
   * Freeze a captured frame value (a first-order CoreIR value term — a `Lit`, or a `Ctor`
   * of `Lit`s for a multi-slot frame) plus a closed resume program into capsule bytes. Pure.
   */
  def encode(frame: Term, resume: Program): String =
    s"(portable-capsule (version $Version) (resume-digest ${digestOf(resume)}) " +
      s"(frame ${Writer.term(frame)}) (resume ${Writer.program(resume)}))"

  /**
   * Admit capsule bytes: parse the envelope, re-validate the resume program fail-closed,
   * and re-check the digest. Inert — never runs the program. A version mismatch, a
   * malformed resume, or a digest mismatch (tamper) all reject with a diagnostic.
   */
  def decode(src: String): PortableCapsule =
    Reader.parseOne(src) match
      case Sx.Lst(Sx.Atom("portable-capsule") :: fields) =>
        val version = intField(fields, "version")
        if version != Version then
          sys.error(s"portable-capsule: unsupported version $version")
        val declaredDigest = atomField(fields, "resume-digest")
        val frame = Reader.toTerm(subField(fields, "frame"))
        val resume = Reader.toProgram(subField(fields, "resume"))
        Reader.validate(resume) // fail CLOSED — untrusted resume program
        if digestOf(resume) != declaredDigest then
          sys.error("portable-capsule: resume-digest mismatch (tampered)")
        PortableCapsule(version, frame, resume)
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
    Runtime.runManaged(Compiler.compile(driver), Array.empty[Value])

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

  private def subField(fields: List[Sx], key: String): Sx =
    field(fields, key) match
      case Sx.Lst(_ :: sub :: Nil) => sub
      case _                       => sys.error(s"portable-capsule: bad ($key ...)")
