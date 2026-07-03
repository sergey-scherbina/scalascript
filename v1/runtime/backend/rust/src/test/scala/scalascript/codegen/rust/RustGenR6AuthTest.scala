package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.6 — auth: argon2 password hashing + HS256 JWT.
 *  Verifies:
 *  - `RustCapabilities` declares `Feature.Auth`
 *  - Intrinsics map carries hashPassword, verifyPassword, jwtSign, jwtVerify
 *  - hello-world stays auth-dep-free in Cargo.toml
 *  - program using hashPassword pulls in argon2 + jsonwebtoken + serde
 *  - program using jwtSign/jwtVerify also pulls the auth crates
 *  - `src/runtime/auth.rs` is emitted and contains the Rust helpers
 *  - `src/runtime/mod.rs` re-exports the `auth` submodule */
class RustGenR6AuthTest extends AnyFunSuite:

  private val emptyOpts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map.empty
  )

  private def assets(src: String): Map[String, String] =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collect { case Segment.Asset(n, b, _) => n -> new String(b, "UTF-8") }.toMap
      case other => fail(s"expected Segmented, got $other")

  test("RustCapabilities declares Auth"):
    assert(new RustBackend().capabilities.features.contains(Feature.Auth))

  test("Intrinsics map carries hashPassword, verifyPassword, jwtSign, jwtVerify"):
    val ic = new RustBackend().intrinsics
    import scalascript.ir.QualifiedName
    assert(ic(QualifiedName("hashPassword"))   == RuntimeCall("crate::runtime::auth::_hash_password"))
    assert(ic(QualifiedName("verifyPassword")) == RuntimeCall("crate::runtime::auth::_verify_password"))
    assert(ic(QualifiedName("jwtSign"))        == RuntimeCall("crate::runtime::auth::_jwt_sign"))
    assert(ic(QualifiedName("jwtVerify"))      == RuntimeCall("crate::runtime::auth::_jwt_verify"))

  test("hello-world stays auth-dep-free in Cargo.toml"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println("hi")
        |```
        |""".stripMargin)
    val toml = a("Cargo.toml")
    assert(!toml.contains("argon2"),       "argon2 dep should not appear for hello-world")
    assert(!toml.contains("jsonwebtoken"), "jsonwebtoken dep should not appear for hello-world")
    assert(!toml.contains("serde"),        "serde dep should not appear for hello-world")
    assert(!a.contains("src/runtime/auth.rs"), "auth.rs should not be emitted for hello-world")
    assert(!a("src/runtime/mod.rs").contains("pub mod auth;"),
      "mod.rs should not re-export auth for hello-world")

  test("program with hashPassword pulls in auth crates"):
    val a = assets(
      """```scalascript
        |def workload(): String = hashPassword("s3cr3t")
        |```
        |""".stripMargin)
    val toml = a("Cargo.toml")
    assert(toml.contains("argon2"),       s"argon2 dep missing in:\n$toml")
    assert(toml.contains("jsonwebtoken"), s"jsonwebtoken dep missing in:\n$toml")
    assert(toml.contains("serde"),        s"serde dep missing in:\n$toml")

  test("program with hashPassword emits src/runtime/auth.rs"):
    val a = assets(
      """```scalascript
        |def workload(): String = hashPassword("s3cr3t")
        |```
        |""".stripMargin)
    assert(a.contains("src/runtime/auth.rs"), "auth.rs asset missing")
    val auth = a("src/runtime/auth.rs")
    assert(auth.contains("pub fn _hash_password"),   s"_hash_password missing in:\n$auth")
    assert(auth.contains("pub fn _verify_password"), s"_verify_password missing in:\n$auth")
    assert(auth.contains("pub fn _jwt_sign"),        s"_jwt_sign missing in:\n$auth")
    assert(auth.contains("pub fn _jwt_verify"),      s"_jwt_verify missing in:\n$auth")
    assert(auth.contains("Argon2"),                  s"Argon2 missing in:\n$auth")
    assert(auth.contains("jsonwebtoken"),             s"jsonwebtoken use missing in:\n$auth")

  test("runtime/mod.rs re-exports auth submodule when auth is used"):
    val a = assets(
      """```scalascript
        |def workload(): String = jwtSign("{}", "k")
        |```
        |""".stripMargin)
    val m = a("src/runtime/mod.rs")
    assert(m.contains("pub mod auth;"), s"'pub mod auth;' missing in runtime/mod.rs:\n$m")

  test("call to hashPassword lowers to crate::runtime::auth::_hash_password"):
    val g = assets(
      """```scalascript
        |def workload(): String = hashPassword("pw")
        |```
        |""".stripMargin)("src/generated/ssc_program.rs")
    assert(g.contains("crate::runtime::auth::_hash_password("),
      s"call site missing in:\n$g")

  test("call to verifyPassword lowers to crate::runtime::auth::_verify_password"):
    val g = assets(
      """```scalascript
        |def workload(): Boolean = verifyPassword("hash", "pw")
        |```
        |""".stripMargin)("src/generated/ssc_program.rs")
    assert(g.contains("crate::runtime::auth::_verify_password("),
      s"call site missing in:\n$g")

  test("program using only jwtSign pulls in auth crates"):
    val a = assets(
      """```scalascript
        |def workload(): String = jwtSign("{\"user\":\"alice\"}", "secret")
        |```
        |""".stripMargin)
    val toml = a("Cargo.toml")
    assert(toml.contains("argon2"),       s"argon2 dep missing in:\n$toml")
    assert(toml.contains("jsonwebtoken"), s"jsonwebtoken dep missing in:\n$toml")
