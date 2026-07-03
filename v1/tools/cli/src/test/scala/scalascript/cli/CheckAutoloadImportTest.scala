package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.compiler.plugin.BackendRegistry
import scalascript.parser.Parser

/** check-autoload-plugin-by-import: `ssc check` auto-loads a bundled-but-opt-in plugin's
 *  `preludeSymbols` when the file imports a namespace the plugin declares via `providesImports`.
 *  This locks the two pure pieces (the prefix match + the module import-prefix extraction) and the
 *  empty-input fast paths. The end-to-end discovery (scanning the `plugin-available` dir for `.sscpkg`
 *  packages) is verified separately against a real staged package. */
class CheckAutoloadImportTest extends AnyFunSuite:

  test("importMatchesPrefix: exact + under-namespace match, with no boundary false-positives"):
    assert(BackendRegistry.importMatchesPrefix(Set("scalascript.x402"), "scalascript.x402"))         // exact
    assert(BackendRegistry.importMatchesPrefix(Set("scalascript.x402.client"), "scalascript.x402"))  // under
    assert(!BackendRegistry.importMatchesPrefix(Set("scalascript.oauth"), "scalascript.x402"))       // unrelated
    // a shared textual prefix that is NOT a dotted-namespace boundary must NOT match
    assert(!BackendRegistry.importMatchesPrefix(Set("scalascript.x402client"), "scalascript.x402"))

  test("importPrefixesOf extracts dotted import prefixes from a parsed module"):
    val src =
      "# T\n\n```scalascript\n" +
        "import scalascript.x402.client.{X402Client, Wallets}\n" +
        "import scala.concurrent.Await\n" +
        "def f(): Int = 1\n```\n"
    val prefixes = importPrefixesOf(Parser.parse(src))
    assert(prefixes.contains("scalascript.x402.client"), s"got: $prefixes")
    assert(prefixes.contains("scala.concurrent"), s"got: $prefixes")

  test("importMatchedPreludeSymbols is a no-op for empty imports or no available dirs"):
    assert(BackendRegistry.importMatchedPreludeSymbols(Set.empty, Nil).isEmpty)
    assert(BackendRegistry.importMatchedPreludeSymbols(Set("scalascript.x402"), Nil).isEmpty)
