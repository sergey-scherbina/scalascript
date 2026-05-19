package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.20.1 — conformance tests for std/parsing/recovery.ssc.
 *
 *  Three recovery strategies:
 *   1. `recover` — error-node substitution (PRecover)
 *   2. `skipTo` — skip-to-sync-char (PSkipTo)
 *   3. `manyRecovering` — multi-error accumulation (PManyRecovering)
 */
class ParsingRecoveryTest extends AnyFunSuite with Matchers:

  private val repoRoot = os.pwd / os.up

  private def runWithRecovery(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test
         |
         |[Position, ParseError, ParseOk, ParseErr, Parser, PChar, PString, PRegex, PSatisfy, PSucceed, PFail](std/parsing/core.ssc)
         |
         |[PSequence, PChoice, PMany, POpt, PMapped, PFlatMapped, PNamed, runParser](std/parsing/combinators.ssc)
         |
         |[ErrorNode, RecoveryResult, PRecover, PSkipTo, PManyRecovering, runRecovery, parseWithRecovery](std/parsing/recovery.ssc)
         |
         |```scala
         |$code
         |```
         |""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── Strategy 1: recover / recoverWith ────────────────────────────────

  test("recover: success path returns value unchanged"):
    runWithRecovery("""
      val p = Parser.string("hello").recover(e => "fallback")
      p.parseRecovering("hello world") match
        case RecoveryResult(ParseOk(v, _, _), errs) =>
          println(v)
          println(errs.length)
    """) shouldBe "hello\n0"

  test("recover: failure path calls onError and accumulates error"):
    runWithRecovery("""
      val p = Parser.string("hello").recover(e => "fallback")
      p.parseRecovering("world") match
        case RecoveryResult(ParseOk(v, _, _), errs) =>
          println(v)
          println(errs.length)
    """) shouldBe "fallback\n1"

  test("recoverWith: failure returns literal fallback"):
    runWithRecovery("""
      val p = Parser.string("ok").recoverWith("??")
      p.parseRecovering("bad") match
        case RecoveryResult(ParseOk(v, _, _), errs) =>
          println(v)
          println(errs.length)
    """) shouldBe "??\n1"

  test("recover does not consume input on failure"):
    runWithRecovery("""
      val p = Parser.string("x").recover(_ => "e") ~ Parser.string("y")
      p.parseRecovering("y") match
        case RecoveryResult(ParseOk(pair, rest, _), errs) =>
          println(pair._1)
          println(pair._2)
          println(errs.length)
    """) shouldBe "e\ny\n1"

  // ── Strategy 2: skipTo ────────────────────────────────────────────────

  test("skipTo: success path passes through"):
    runWithRecovery("""
      val p = Parser.string("ok").skipTo(";")
      p.parseRecovering("ok;rest") match
        case RecoveryResult(ParseOk(v, rest, _), errs) =>
          println(v)
          println(errs.length)
    """) shouldBe "ok\n0"

  test("skipTo: failure skips to sync char and returns ErrorNode"):
    runWithRecovery("""
      val p = Parser.string("ok").skipTo(";")
      p.parseRecovering("bad stuff; next") match
        case RecoveryResult(ParseOk(v, rest, _), errs) =>
          val isError = v match
            case ErrorNode(_) => true
            case _            => false
          println(isError)
          println(errs.length)
          println(rest)
    """) shouldBe "true\n1\n next"

  test("skipTo: failure at end-of-input returns ErrorNode with empty rest"):
    runWithRecovery("""
      val p = Parser.string("ok").skipTo(";")
      p.parseRecovering("bad") match
        case RecoveryResult(ParseOk(v, rest, _), errs) =>
          val isError = v match
            case ErrorNode(_) => true
            case _            => false
          println(isError)
          println(rest)
    """) shouldBe "true\n"

  // ── Strategy 3: manyRecovering ────────────────────────────────────────

  test("manyRecovering: all succeed — returns list with no errors"):
    runWithRecovery("""
      val p = Parser.regex("[a-z]+").manyRecovering(";")
      p.parseRecovering("foo;bar;baz") match
        case RecoveryResult(ParseOk(items, _, _), errs) =>
          items.asInstanceOf[List[Any]].foreach(x => println(x))
          println(errs.length)
    """) shouldBe "foo\nbar\nbaz\n0"

  test("manyRecovering: one failure skips to sync and inserts ErrorNode"):
    runWithRecovery("""
      val p = Parser.regex("[0-9]+").manyRecovering(";")
      p.parseRecovering("123;bad;456") match
        case RecoveryResult(ParseOk(items, _, _), errs) =>
          val lst = items.asInstanceOf[List[Any]]
          println(lst.length)
          println(errs.length)
          println(lst(0))
          val hasError = lst(1) match
            case ErrorNode(_) => true
            case _            => false
          println(hasError)
          println(lst(2))
    """) shouldBe "3\n1\n123\ntrue\n456"

  test("manyRecovering: multiple failures accumulate all errors"):
    runWithRecovery("""
      val p = Parser.regex("[0-9]+").manyRecovering(";")
      p.parseRecovering("1;bad;worse;2") match
        case RecoveryResult(ParseOk(items, _, _), errs) =>
          val lst = items.asInstanceOf[List[Any]]
          println(lst.length)
          println(errs.length)
    """) shouldBe "4\n2"

  test("manyRecovering: empty input returns empty list with no errors"):
    runWithRecovery("""
      val p = Parser.regex("[a-z]+").manyRecovering(";")
      p.parseRecovering("") match
        case RecoveryResult(ParseOk(items, _, _), errs) =>
          println(items.asInstanceOf[List[Any]].length)
          println(errs.length)
    """) shouldBe "0\n0"

  // ── Cross-strategy: recovery inside sequence ──────────────────────────

  test("recover inside sequence: sibling parser still runs"):
    runWithRecovery("""
      val p = Parser.string("a").recover(_ => "?") ~ Parser.string("b")
      p.parseRecovering("b") match
        case RecoveryResult(ParseOk(pair, rest, _), errs) =>
          println(pair._1)
          println(pair._2)
          println(errs.length)
    """) shouldBe "?\nb\n1"

  test("ErrorNode carries the original error message"):
    runWithRecovery("""
      val p = Parser.string("abc").recover(e => ErrorNode(e))
      p.parseRecovering("xyz") match
        case RecoveryResult(ParseOk(v, _, _), errs) =>
          val msg = v match
            case ErrorNode(e) => e.message
            case _            => "none"
          println(msg.contains("expected"))
    """) shouldBe "true"

  // ── parseWithRecovery top-level function ─────────────────────────────

  test("parseWithRecovery returns RecoveryResult with errors list"):
    runWithRecovery("""
      val p = Parser.string("x").recover(_ => "e")
      val r = parseWithRecovery(p, "y")
      r match
        case RecoveryResult(ParseOk(v, _, _), errs) =>
          println(v)
          println(errs.length)
    """) shouldBe "e\n1"
