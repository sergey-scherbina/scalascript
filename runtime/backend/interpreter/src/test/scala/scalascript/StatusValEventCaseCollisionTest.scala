package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** busi-p0-statusval-eventcase-collision — same-named bindings in the
 *  same import scope: a stable-identifier `val` of type X, plus an
 *  enum/case-class case constructor that returns type Y.  Today,
 *  every bare-reference of the name in expression-position resolves
 *  to the case-constructor (returning a `NativeFnV` companion stub);
 *  the `val` binding is silently shadowed, so a downstream `.code`
 *  call throws `No method 'code' on NativeFnV(...)`.
 *
 *  Per the design discussed in the rozum meeting on 2026-06-07
 *  (sergiy chose hybrid B-then-A):
 *
 *  - Pattern position → case-constructor (already correct).
 *  - Expression-call with args → case-constructor (already correct).
 *  - Bare expression reference with an EXPECTED TYPE that matches one
 *    of the two bindings → resolve to that one (semantic split, like
 *    Scala 2).
 *  - Bare reference where the expected type matches BOTH or NEITHER,
 *    or where there is no expected type → compile-time error
 *    (fallback to direction A, like Scala 3).
 */
class StatusValEventCaseCollisionTest extends AnyFunSuite:

  /** Run a `.ssc` snippet through the interpreter and return its
   *  stdout (trimmed of the trailing newline). */
  private def runProgram(body: String): String =
    val src =
      s"""# T
         |
         |```scalascript
         |$body
         |```
         |""".stripMargin
    val baos = new java.io.ByteArrayOutputStream()
    val ps   = new java.io.PrintStream(baos, true, "UTF-8")
    interpreter.Interpreter(ps).run(Parser.parse(src))
    baos.toString("UTF-8").stripLineEnd

  // ── Single-file shape of the busi-86a collision ────────────────────────
  //
  // Mirrors the structural problem without the cross-module import path:
  // a `case class` wraps a String code, a `val` of that wrapper class is
  // bound to the same name as a case of a separate enum.

  test("expression bare-ref with expected type matching the val resolves to the val"):
    val body =
      """case class PeerLinkStatus(code: String)
        |val PeerLinkInvited = PeerLinkStatus("invited")
        |
        |case class PeerLink(linkId: String, remote: String)
        |enum PeerLinkEvent:
        |  case PeerLinkInvited(link: PeerLink)
        |  case PeerLinkAccepted(linkId: String, at: String)
        |
        |val status: PeerLinkStatus = PeerLinkInvited
        |println(status.code)""".stripMargin
    assert(runProgram(body) == "invited")

  test("expression-call with args resolves to the case-constructor (unchanged behaviour)"):
    val body =
      """case class PeerLinkStatus(code: String)
        |val PeerLinkInvited = PeerLinkStatus("invited")
        |
        |case class PeerLink(linkId: String, remote: String)
        |enum PeerLinkEvent:
        |  case PeerLinkInvited(link: PeerLink)
        |  case PeerLinkAccepted(linkId: String, at: String)
        |
        |val link = PeerLink("L1", "remote-org")
        |val ev: PeerLinkEvent = PeerLinkInvited(link)
        |val described = ev match
        |  case PeerLinkInvited(l) => l.linkId
        |  case PeerLinkAccepted(id, at) => id + "@" + at
        |println(described)""".stripMargin
    assert(runProgram(body) == "L1")

  test("expression bare-ref with expected type matching the case-constructor return resolves to it"):
    // `val ev: PeerLinkEvent = PeerLinkInvited` (no args) — eta-expanded
    // FunV reference to the case-constructor companion, since the expected
    // type matches `PeerLinkEvent`.
    val body =
      """case class PeerLinkStatus(code: String)
        |val PeerLinkInvited = PeerLinkStatus("invited")
        |
        |case class PeerLink(linkId: String, remote: String)
        |enum PeerLinkEvent:
        |  case PeerLinkInvited(link: PeerLink)
        |  case PeerLinkAccepted(linkId: String, at: String)
        |
        |val ctor: PeerLink => PeerLinkEvent = PeerLinkInvited
        |val ev = ctor(PeerLink("L42", "remote"))
        |val out = ev match
        |  case PeerLinkInvited(l) => "got " + l.linkId
        |  case PeerLinkAccepted(id, at) => "accepted " + id
        |println(out)""".stripMargin
    assert(runProgram(body) == "got L42")

  test("pattern position resolves to case-constructor (unchanged behaviour)"):
    val body =
      """case class PeerLinkStatus(code: String)
        |val PeerLinkInvited = PeerLinkStatus("invited")
        |
        |case class PeerLink(linkId: String, remote: String)
        |enum PeerLinkEvent:
        |  case PeerLinkInvited(link: PeerLink)
        |  case PeerLinkAccepted(linkId: String, at: String)
        |
        |val ev: PeerLinkEvent = PeerLinkInvited(PeerLink("L7", "r"))
        |val out = ev match
        |  case PeerLinkInvited(l) => l.linkId
        |  case _                  => "other"
        |println(out)""".stripMargin
    assert(runProgram(body) == "L7")

  test("no collision: standalone val without competing case-constructor still works"):
    val body =
      """case class PeerLinkStatus(code: String)
        |val PeerLinkInvited = PeerLinkStatus("invited")
        |println(PeerLinkInvited.code)""".stripMargin
    assert(runProgram(body) == "invited")

  test("no collision: standalone case-constructor without competing val still works"):
    val body =
      """case class PeerLink(linkId: String, remote: String)
        |enum PeerLinkEvent:
        |  case PeerLinkInvited(link: PeerLink)
        |  case PeerLinkAccepted(linkId: String, at: String)
        |
        |val ev: PeerLinkEvent = PeerLinkInvited(PeerLink("L0", "r"))
        |val out = ev match
        |  case PeerLinkInvited(l) => l.linkId
        |  case _                  => "x"
        |println(out)""".stripMargin
    assert(runProgram(body) == "L0")

  // ── Cross-module import shape — the actual busi-86a repro ──────────────
  //
  // Saved at `/tmp/p0-3-collision/{mod_status, mod_events, user}.ssc`,
  // these mirror the real shape that surfaced in busi phase 86a where
  // the val and case-constructor live in separate files and only
  // collide in the consumer's import scope.  We rebuild them in a
  // temp dir and run the user module through the interpreter.

  private def runMultiFile(body: String, otherFiles: Map[String, String]): String =
    val tmp = os.temp.dir(prefix = "ssc-p0-3-collision-")
    try
      otherFiles.foreach { (name, src) => os.write(tmp / name, src) }
      val userPath = tmp / "user.ssc"
      os.write(userPath, body)
      val module = Parser.parseFile(userPath)
      val baos = new java.io.ByteArrayOutputStream()
      val ps   = new java.io.PrintStream(baos, true, "UTF-8")
      interpreter.Interpreter(ps, baseDir = Some(tmp)).run(module)
      baos.toString("UTF-8").stripLineEnd
    finally os.remove.all(tmp)

  test("cross-module: typed val ascription disambiguates back to imported status-val"):
    val statusMod =
      """---
        |exports:
        |  - PeerLinkStatus
        |  - PeerLinkInvited
        |  - PeerLinkActive
        |---
        |# Status
        |
        |```scalascript
        |case class PeerLinkStatus(code: String)
        |val PeerLinkInvited = PeerLinkStatus("invited")
        |val PeerLinkActive  = PeerLinkStatus("active")
        |```
        |""".stripMargin
    val eventsMod =
      """---
        |exports:
        |  - PeerLink
        |  - PeerLinkEvent
        |  - PeerLinkInvited
        |  - PeerLinkAccepted
        |---
        |# Events
        |
        |```scalascript
        |case class PeerLink(linkId: String, remote: String)
        |enum PeerLinkEvent:
        |  case PeerLinkInvited(link: PeerLink)
        |  case PeerLinkAccepted(linkId: String, at: String)
        |```
        |""".stripMargin
    val user =
      """# User
        |
        |[PeerLinkStatus, PeerLinkInvited, PeerLinkActive](mod_status.ssc)
        |
        |[PeerLink, PeerLinkEvent, PeerLinkInvited, PeerLinkAccepted](mod_events.ssc)
        |
        |```scalascript
        |val link = PeerLink("L1", "remote-org")
        |val ev: PeerLinkEvent = PeerLinkInvited(link)
        |println("write-side event built")
        |
        |val described = ev match
        |  case PeerLinkInvited(l) => "invited link " + l.linkId
        |  case PeerLinkAccepted(id, at) => "accepted " + id + " at " + at
        |println("read-side: " + described)
        |
        |val status: PeerLinkStatus = PeerLinkInvited
        |println("status code: " + status.code)
        |```
        |""".stripMargin
    val out = runMultiFile(user, Map(
      "mod_status.ssc" -> statusMod,
      "mod_events.ssc" -> eventsMod
    ))
    assert(out ==
      """write-side event built
        |read-side: invited link L1
        |status code: invited""".stripMargin,
      s"unexpected output:\n$out")
