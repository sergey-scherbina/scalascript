package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CommandRegistryTest extends AnyFunSuite with Matchers:

  // Every subcommand token the legacy match dispatched must resolve to a provider.
  private val expectedTokens = List(
    "parse", "check", "run", "watch", "watch-bench", "repl",
    "emit-js", "emit-wasm", "emit-rust", "build-rust", "run-rust",
    "emit-openapi", "emit-spa", "emit-scala", "emit-spark",
    "submit", "emit-wc", "emit-interface", "emit-ir",
    "run-jvm", "run-js", "compile-jvm", "compile-js", "compile-runtime",
    "check-with-iface", "link", "generate-facade", "info", "clean", "verify",
    "check-compat", "deps", "deploy", "package", "publish", "serve", "render",
    "build", "bundle", "new", "plugin", "install", "lock", "update", "search",
    "add", "test", "preview", "fmt", "bench", "profile", "lsp", "debug",
    "cluster", "oauth", "toolchain",
    "help", "--help", "-h", "--list-backends",
    "check-types", "lint-jit",
  )

  test("ServiceLoader discovers the command providers"):
    CommandRegistry.all should not be empty

  test("every legacy subcommand token resolves to a provider"):
    val missing = expectedTokens.filter(t => CommandRegistry.lookup(t).isEmpty)
    missing shouldBe empty

  test("help aliases resolve to the same command"):
    val h = CommandRegistry.lookup("help")
    h shouldBe defined
    CommandRegistry.lookup("--help") shouldBe h
    CommandRegistry.lookup("-h")     shouldBe h

  test("no duplicate name/alias tokens across providers"):
    val tokens = CommandRegistry.all.flatMap(c => c.name :: c.aliases)
    tokens.distinct.size shouldBe tokens.size

  test("unknown token does not resolve (falls through to script dispatch)"):
    CommandRegistry.lookup("definitely-not-a-command") shouldBe None

  test("every listed command carries help metadata"):
    val missing = CommandRegistry.all
      .filterNot(_.hidden)
      .filter(c => c.summary.trim.isEmpty)
      .map(_.name)
    missing shouldBe empty

  test("every command category is in the help ordering"):
    val unknown = CommandRegistry.all
      .filterNot(_.hidden)
      .map(_.category)
      .distinct
      .filterNot(Help.categoryOrder.contains)
    unknown shouldBe empty

  test("rendered help lists a representative command under its group"):
    val help = Help.renderCommands()
    help should include ("Run & develop:")
    help should include ("build")
    help should include ("emit-js")
