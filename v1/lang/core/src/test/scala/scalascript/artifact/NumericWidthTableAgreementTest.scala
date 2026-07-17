package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite

import scalascript.interop.descriptor.*
import scalascript.parser.Parser

/** Every consumer must AGREE WITH the one normative numeric table — `specs/numeric-widths.md`.
 *
 *  WHY THIS EXISTS. `coreir-abi-int-width-declared-i32-actually-i64` (the v3 descriptor declaring
 *  32 bits for a 64-bit value, truncating silently at every foreign-host boundary) happened
 *  because **every consumer independently guessed name -> width by reading a type name that lies**:
 *  ssc `Int` is 64-bit and reads as "32 bits" to everyone alive. Measured 2026-07-17, `SPEC.md`
 *  §4.1 — the canonical language spec — had been asserting "`Int` | 32-bit integer" the whole time.
 *  A width that lives in N places is a width that gets re-derived N ways.
 *
 *  SO THIS TEST PARSES THE TABLE OUT OF THE SPEC and compares it against the REAL consumers. It
 *  deliberately does NOT restate the widths in Scala: a restated table is just an (N+1)th guess,
 *  and it would agree with itself forever while the spec drifted. Per `AGENTS.md` ("measurement
 *  apparatus must COMPARE, never PRE-JUDGE"): compute both sides, then compare.
 *
 *  WHAT IS AND IS NOT COVERED. Checked here: the real `PreBodyApiDescriptorProducer` (run, not
 *  read), and the four host-profile spec tables. The RUNTIME backends (interpreter / v2 / v1
 *  codegen) cannot execute inside this unit test; their agreement is gated by
 *  `tests/conformance/int-width.ssc`, which runs on every eligible backend with the v1 codegen
 *  declared known-red. Stated plainly so nobody reads more into a green run than is here.
 */
final class NumericWidthTableAgreementTest extends AnyFunSuite:

  // ─── locating + parsing the normative spec ──────────────────────────────

  private def repoRoot: os.Path =
    val starts =
      List(os.pwd, os.Path(sys.props("user.dir"))) ++
        List(os.Path(getClass.getProtectionDomain.getCodeSource.getLocation.toURI))
    starts.iterator
      .flatMap(start => start.segments.scanLeft(start)((p, _) => p / os.up))
      .find(candidate => os.isFile(candidate / "specs" / "numeric-widths.md"))
      .getOrElse(fail("could not locate repo root (no specs/numeric-widths.md above any start dir)"))

  private def spec(name: String): String = os.read(repoRoot / "specs" / name)

  /** Rows of the fenced normative table `marker`, as already-split cells. */
  private def normativeTable(source: String, marker: String, specName: String): List[List[String]] =
    val begin = s"<!-- BEGIN NORMATIVE TABLE: $marker -->"
    val end   = s"<!-- END NORMATIVE TABLE: $marker -->"
    val from  = source.indexOf(begin)
    val to    = source.indexOf(end)
    assert(from >= 0 && to > from, s"$specName: normative table markers for '$marker' not found")
    val rows = source
      .substring(from + begin.length, to)
      .linesIterator
      .map(_.trim)
      .filter(line => line.startsWith("|") && line.endsWith("|"))
      .map(line => line.stripPrefix("|").stripSuffix("|").split("\\|", -1).map(_.trim).toList)
      .toList
    // drop the header row and the |---|---| separator
    val body = rows.drop(2)
    assert(body.nonEmpty, s"$specName: normative table '$marker' has no rows")
    body

  /** ``Int`` -> Int */
  private def unCode(cell: String): String = cell.replace("`", "").trim

  /** The first code span in a cell — the carrier a host profile names first. */
  private def firstCodeSpan(cell: String): Option[String] =
    "`([^`]+)`".r.findFirstMatchIn(cell).map(_.group(1).trim)

  // ─── the normative rows ─────────────────────────────────────────────────

  private case class WidthRow(spelling: String, width: String, abi: String, evidence: String)

  private lazy val widthRows: List[WidthRow] =
    normativeTable(spec("numeric-widths.md"), "ssc-numeric-widths", "numeric-widths.md").map {
      case spelling :: width :: _ :: abi :: evidence :: Nil =>
        WidthRow(unCode(spelling), unCode(width), unCode(abi), unCode(evidence))
      case other => fail(s"numeric-widths.md: malformed width row: $other")
    }

  private lazy val hostRows: List[(String, Map[String, String])] =
    val table = normativeTable(spec("numeric-widths.md"), "ssc-host-carriers", "numeric-widths.md")
    // `normativeTable` drops the header, so re-read it here for the host column names.
    val headerCells = spec("numeric-widths.md")
      .linesIterator
      .dropWhile(!_.contains("BEGIN NORMATIVE TABLE: ssc-host-carriers"))
      .find(line => line.trim.startsWith("| Canonical"))
      .getOrElse(fail("numeric-widths.md: host-carrier header row not found"))
      .trim.stripPrefix("|").stripSuffix("|").split("\\|", -1).map(_.trim).toList
    val hosts = headerCells.tail // drop the "Canonical" column
    table.map { row =>
      val canonical = unCode(row.head)
      canonical -> hosts.zip(row.tail.map(unCode)).toMap
    }

  // ─── the real consumer: the descriptor producer, RUN not read ───────────

  private def source(spelling: String): String =
    s"""---
       |name: demo
       |package: demo.api
       |exports:
       |  - value
       |---
       |
       |```scalascript
       |def value(input: $spelling): $spelling = input
       |```
       |""".stripMargin

  /** Right(declared type) / Left(error code) — whatever the REAL producer does. */
  private def declared(spelling: String): Either[String, AbiType] =
    PreBodyApiDescriptorProducer.descriptor(Parser.parse(source(spelling))) match
      case Right(descriptor) =>
        Right(descriptor.symbols.head.definition.parameterLists.head.parameters.head.tpe)
      case Left(error) => Left(error.code)

  private def expectedAbi(row: WidthRow): Option[AbiPrimitive] = row.abi match
    case "I64"      => Some(AbiPrimitive.I64)
    case "F64"      => Some(AbiPrimitive.F64)
    case "BigInt"   => Some(AbiPrimitive.BigInt)
    case "REJECTED" => None
    case other      => fail(s"numeric-widths.md: unknown ABI '$other' — teach this test the mapping")

  private def expectedEvidence(row: WidthRow): Option[NumericWidthEvidence] = row.evidence match
    case "DeclaredInt"  => Some(NumericWidthEvidence.DeclaredInt)
    case "DeclaredLong" => Some(NumericWidthEvidence.DeclaredLong)
    case "none"         => None
    case other          => fail(s"numeric-widths.md: unknown evidence '$other'")

  // ─── tests ──────────────────────────────────────────────────────────────

  test("the normative table is non-vacuous and pins the spellings this project keeps re-guessing"):
    // Guards the parser itself: if the markers ever stop matching, every other test in this file
    // would pass over an EMPTY table and prove nothing. That is the failure mode this suite exists
    // to prevent, so it must not be possible here either.
    assert(widthRows.map(_.spelling).toSet == Set("Int", "Long", "Double", "Float", "BigInt"),
      s"parsed spellings ${widthRows.map(_.spelling)} — the table moved or the parser broke")
    assert(hostRows.map(_._1).toSet == Set("I64", "Double"),
      s"parsed host ABI rows ${hostRows.map(_._1)} — the table moved or the parser broke")

  test("EVERY spelling in the normative table declares the width the table says (real producer)"):
    widthRows.foreach { row =>
      (expectedAbi(row), declared(row.spelling)) match
        case (Some(abi), Right(AbiType.Primitive(actual, actualEvidence))) =>
          assert(actual == abi,
            s"${row.spelling}: normative table declares ABI ${row.abi}, producer declared $actual")
          assert(actualEvidence == expectedEvidence(row),
            s"${row.spelling}: normative table declares evidence '${row.evidence}', " +
              s"producer declared $actualEvidence")
        case (Some(abi), other) =>
          fail(s"${row.spelling}: normative table declares $abi, producer gave $other")
        case (None, Left(code)) =>
          // A REJECTED row must fail CLOSED, and for the stated reason.
          assert(code == "UNSUPPORTED_NUMERIC_WIDTH",
            s"${row.spelling}: table says REJECTED; producer rejected with '$code' instead of " +
              "UNSUPPORTED_NUMERIC_WIDTH")
        case (None, Right(actual)) =>
          fail(s"${row.spelling}: normative table says REJECTED (no ABI width exists for it), " +
            s"but the producer declared $actual — a width was invented somewhere")
    }

  test("no spelling in the table is declared at a width NARROWER than the table states"):
    // The bug class, stated directly: the descriptor declaring fewer bits than the value has is
    // what silently truncates in a foreign host. `Int` is the one that bit us.
    widthRows.filter(_.width == "64").foreach { row =>
      declared(row.spelling) match
        case Right(AbiType.Primitive(AbiPrimitive.I32, _)) =>
          fail(s"${row.spelling} is ${row.width}-bit per the normative table but declares I32 — " +
            "every foreign host would truncate it above 2^31-1")
        case _ => () // REJECTED / I64 / F64 / BigInt are all >= the stated width
    }

  test("Int and Long: identical declared width, distinct retained evidence"):
    // §2.1: they are the SAME runtime type. The descriptor must therefore declare the same width
    // and keep them apart by evidence alone — otherwise their overload identities collide
    // (measured: DUPLICATE_SYMBOL_ID, fail-closed, but the overloads become unexportable).
    val List(int, long) = List("Int", "Long").map(declared): @unchecked
    (int, long) match
      case (Right(AbiType.Primitive(intAbi, intEv)), Right(AbiType.Primitive(longAbi, longEv))) =>
        assert(intAbi == longAbi, s"Int declared $intAbi but Long declared $longAbi — the table " +
          "says both are 64-bit, so both must declare the same ABI primitive")
        assert(intEv != longEv, s"Int and Long both declared evidence $intEv — with the same ABI " +
          "primitive and the same evidence they are byte-identical in the descriptor and collide")
      case other => fail(s"Int/Long did not both declare a primitive: $other")

  test("the four host profiles agree with the normative host-carrier table"):
    // The host profile specs are the contract a binding author reads. If one drifts from the
    // normative table, a host marshals at the wrong width -- in another language, silently.
    val profiles = Map(
      "js-ts"     -> "javascript-typescript-bidirectional-control.md",
      "rust"      -> "rust-bidirectional-control.md",
      "swift"     -> "swift-bidirectional-control.md",
      "wasm-wasi" -> "wasm-wasi-control-runner.md"
    )
    profiles.foreach { case (host, file) =>
      val text = spec(file)
      hostRows.foreach { case (abi, carriers) =>
        val required = carriers.getOrElse(host, fail(s"normative host table has no '$host' column"))
        // The host spec's row for this ABI primitive, e.g. "| `I64` | `bigint`; conversion ... |"
        val row = text.linesIterator
          .map(_.trim)
          .find(line => line.startsWith(s"| `$abi` |"))
          .getOrElse(fail(s"$file: no carrier row for `$abi` — the profile does not state a " +
            s"width for it, so a binding author must guess"))
        val cell = row.stripPrefix(s"| `$abi` |").stripSuffix("|").trim
        val named = firstCodeSpan(cell).getOrElse(
          fail(s"$file: the `$abi` row names no carrier in code span: $cell"))
        assert(named == required,
          s"$file: `$abi` names carrier `$named` first, normative table requires `$required` " +
            s"(specs/numeric-widths.md §3). One of the two is a bug -- they must not disagree.")
      }
    }

  test("NEGATIVE CONTROL: the host-agreement check actually rejects a 32-bit carrier"):
    // If the check above can't fail, it proves nothing. Same comparison, deliberately wrong input.
    val required = hostRows.toMap.apply("I64")("js-ts") // `bigint` per the normative table
    val truncatingCell = "`number`; conversion through `bigint` rejects" // the lie, inverted
    val named = firstCodeSpan(truncatingCell).getOrElse(fail("negative control: no code span"))
    assert(named != required,
      "the host-agreement comparison would accept `number` as an I64 carrier — it is vacuous")
    assert(named == "number")

  test("NEGATIVE CONTROL: the descriptor-agreement check actually rejects a narrower declaration"):
    // Prove the width comparison discriminates: I32 must not compare equal to the table's I64.
    val intRow = widthRows.find(_.spelling == "Int").getOrElse(fail("no Int row"))
    assert(expectedAbi(intRow).contains(AbiPrimitive.I64), "the table stopped saying Int is I64")
    assert(!expectedAbi(intRow).contains(AbiPrimitive.I32),
      "the width comparison treats I32 and I64 as interchangeable — it is vacuous")
