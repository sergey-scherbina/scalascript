package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.plugin.BackendRegistry

/** Pin the output of `ssc --describe-backend <id>`.  The body is
 *  produced by the pure [[describeBackend]] function so we can assert
 *  on its return string without driving the whole `ssc` entry point.
 *
 *  Coverage focuses on Phase A/B/C additions to the Spark surface
 *  (v1.25 § 9.5) — `sparkVersion` and `sparkMaster` show up under
 *  `capabilities.options`, and the `sql` opaque-executable block tag
 *  shows up under `capabilities.blockLanguages`.  Both lines were
 *  missing from the pre-this-iteration output. */
class DescribeBackendTest extends AnyFunSuite:

  private def describe(id: String): String =
    val b = BackendRegistry.lookup(id).getOrElse(
      cancel(s"Backend '$id' not on classpath — skipping")
    )
    describeBackend(b)

  test("spark backend describes its identity and SPI version") {
    val out = describe("spark")
    assert(out.contains("id:          spark"), s"expected id line, got:\n$out")
    assert(out.contains("displayName:"))
    assert(out.contains("spiVersion:"))
  }

  test("spark backend exposes sparkVersion + sparkMaster under capabilities.options") {
    val out = describe("spark")
    val line = out.linesIterator.find(_.startsWith("capabilities.options:"))
      .getOrElse(fail(s"capabilities.options line missing from output:\n$out"))
    assert(line.contains("sparkVersion"),
      s"sparkVersion option must be visible to users, got: $line")
    assert(line.contains("sparkMaster"),
      s"sparkMaster option must be visible to users, got: $line")
  }

  test("spark backend exposes sql under capabilities.blockLanguages") {
    val out = describe("spark")
    val line = out.linesIterator.find(_.startsWith("capabilities.blockLanguages:"))
      .getOrElse(fail(s"capabilities.blockLanguages line missing from output:\n$out"))
    // The `sql` tag was registered in SparkCapabilities by Phase C.1.
    // CapabilityCheck consults the same set to gate `UnknownBlockLanguage`,
    // so this line is the authoritative answer to "what opaque block
    // tags does Spark accept?"
    assert(line.contains("sql"),
      s"sql block language must be visible, got: $line")
  }

  test("options + blockLanguages lines are deterministically sorted") {
    // The fields are emitted from Set[String] / Set[Lang]; we sort them
    // before printing so repeated invocations match byte-for-byte and
    // shell-script diffs against the output are stable.
    val out = describe("spark")
    val opt = out.linesIterator.find(_.startsWith("capabilities.options:")).get
      .stripPrefix("capabilities.options:").trim
    val optList = opt.split(",").map(_.trim).toList
    assert(optList == optList.sorted,
      s"options must be sorted, got: $optList")
    val blk = out.linesIterator.find(_.startsWith("capabilities.blockLanguages:")).get
      .stripPrefix("capabilities.blockLanguages:").trim
    val blkList = blk.split(",").map(_.trim).toList
    assert(blkList == blkList.sorted,
      s"blockLanguages must be sorted, got: $blkList")
  }

  test("intrinsics count line is present") {
    val out = describe("spark")
    assert(out.contains("intrinsics:"), s"intrinsics line missing, got:\n$out")
    assert(out.matches("(?s).*intrinsics:\\s+\\d+ registered.*"),
      s"intrinsics line must include a number, got:\n$out")
  }
