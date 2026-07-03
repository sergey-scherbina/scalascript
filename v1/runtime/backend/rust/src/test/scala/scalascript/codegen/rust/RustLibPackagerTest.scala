package scalascript.codegen.rust

import org.scalatest.funsuite.AnyFunSuite

/** Golden + cargo-build tests for the Rust optics library packager (Task B,
 *  `specs/polyglot-libraries.md` §4 — publish the pure optics feature as a standalone host library).
 *  The cargo smoke is gated on a Rust toolchain (mirrors `RustGenCargoSmokeTest`): it writes the
 *  generated crate to a temp dir, adds an integration test exercising all four optics, and `cargo
 *  test`s it — proving the emitted Rust actually compiles AND the optics behave. */
class RustLibPackagerTest extends AnyFunSuite:

  test("opticsRustPackage emits exactly Cargo.toml + src/lib.rs + README.md"):
    assert(RustLibPackager.opticsRustPackage("0.1.0").keySet ==
      Set("Cargo.toml", "src/lib.rs", "README.md"))

  test("Cargo.toml declares a dep-free [lib] crate"):
    val toml = RustLibPackager.opticsCargoToml("1.2.3")
    assert(toml.contains("name = \"ssc-optics\""))
    assert(toml.contains("version = \"1.2.3\""))
    assert(toml.contains("[lib]"))
    assert(!toml.contains("[dependencies]"))   // dep-free

  test("lib.rs exposes the stable optics API surface"):
    val rs = RustLibPackager.opticsLibRs
    for sym <- List(
      "pub fn make_lens", "pub fn make_optional", "pub fn make_traversal", "pub fn make_prism",
      "pub struct Lens", "pub struct Optional", "pub struct Traversal", "pub struct Prism",
      "pub fn field", "pub fn index", "pub fn at", "pub fn some", "pub fn each",
      "pub enum Value", "pub enum Step")
    do assert(rs.contains(sym), s"missing API symbol: $sym")

  private def cargoAvailable: Boolean =
    try os.proc("cargo", "--version").call(check = false).exitCode == 0
    catch case _: Throwable => false

  test("the emitted optics crate compiles and all four optics behave (cargo test)"):
    assume(cargoAvailable, "cargo not on PATH — skipping the Rust optics cargo smoke")
    val crate = os.temp.dir(prefix = "ssc-optics-")
    for (name, content) <- RustLibPackager.opticsRustPackage("0.1.0") do
      val out = crate / os.RelPath(name)
      os.makeDir.all(out / os.up)
      os.write.over(out, content)
    // An integration test (tests/) exercising Lens / Optional / Traversal / Prism.
    os.makeDir.all(crate / "tests")
    os.write.over(crate / "tests" / "smoke.rs",
      """use ssc_optics::*;
        |use std::collections::BTreeMap;
        |
        |fn obj(pairs: Vec<(&str, Value)>) -> Value {
        |    let mut m = BTreeMap::new();
        |    for (k, v) in pairs { m.insert(k.to_string(), v); }
        |    Value::Obj(m)
        |}
        |
        |#[test]
        |fn lens_get_set() {
        |    let s = obj(vec![("a", obj(vec![("b", Value::Int(5))]))]);
        |    let l = make_lens(vec!["a".to_string(), "b".to_string()]);
        |    assert_eq!(l.get(&s), Value::Int(5));
        |    let s2 = l.set(&s, Value::Int(9));
        |    assert_eq!(l.get(&s2), Value::Int(9));
        |}
        |
        |#[test]
        |fn optional_get() {
        |    let s = obj(vec![("a", Value::Arr(vec![Value::Int(10), Value::Int(20)]))]);
        |    assert_eq!(make_optional(vec![field("a"), index(0)]).get_option(&s), Some(Value::Int(10)));
        |    assert_eq!(make_optional(vec![field("a"), index(9)]).get_option(&s), None);
        |}
        |
        |#[test]
        |fn traversal_each() {
        |    let s = Value::Arr(vec![obj(vec![("n", Value::Int(1))]), obj(vec![("n", Value::Int(2))])]);
        |    let t = make_traversal(vec![each(), field("n")]);
        |    assert_eq!(t.get_all(&s), vec![Value::Int(1), Value::Int(2)]);
        |    let s2 = t.modify(&s, |v| match v { Value::Int(n) => Value::Int(n + 10), x => x });
        |    assert_eq!(t.get_all(&s2), vec![Value::Int(11), Value::Int(12)]);
        |}
        |
        |#[test]
        |fn prism_variant() {
        |    let p = make_prism("Some");
        |    let some_v = obj(vec![("_type", Value::Str("Some".to_string())), ("value", Value::Int(7))]);
        |    assert!(p.get_option(&some_v).is_some());
        |    let none_v = obj(vec![("_type", Value::Str("None".to_string()))]);
        |    assert!(p.get_option(&none_v).is_none());
        |}
        |""".stripMargin)
    val res = os.proc("cargo", "test", "--quiet").call(cwd = crate, check = false)
    assert(res.exitCode == 0, s"cargo test failed:\n${res.err.text()}\n${res.out.text()}")
    os.remove.all(crate)
