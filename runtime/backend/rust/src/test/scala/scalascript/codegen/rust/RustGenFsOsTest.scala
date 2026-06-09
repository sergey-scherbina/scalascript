package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** std-fs-os-p4-rust: std.fs / std.os / std.process for the Rust backend.
 *  Verifies:
 *  - All std.fs intrinsics are wired to crate::runtime::_*
 *  - All std.os path/env/platform intrinsics are wired
 *  - std.process exec is wired
 *  - env returns Option<String>, envOrElse returns String
 *  - ProcessResult struct is in the runtime
 *  - All helpers are present in RuntimeModRs */
class RustGenFsOsTest extends AnyFunSuite:

  private val emptyOpts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map.empty
  )

  private def rt(src: String = """```scalascript\n@main def run(): Unit = println("hi")\n```\n"""): String =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collectFirst { case Segment.Asset("src/runtime/mod.rs", b, _) => new String(b, "UTF-8") }
          .getOrElse(fail("runtime/mod.rs missing"))
      case other => fail(s"expected Segmented, got $other")

  // ── std.fs intrinsics ──────────────────────────────────────────────

  test("std.fs intrinsics wired in table"):
    val ic = new RustBackend().intrinsics
    import scalascript.ir.QualifiedName
    assert(ic(QualifiedName("appendFile"))  == RuntimeCall("crate::runtime::_append_file"))
    assert(ic(QualifiedName("readBytes"))   == RuntimeCall("crate::runtime::_read_bytes"))
    assert(ic(QualifiedName("writeBytes"))  == RuntimeCall("crate::runtime::_write_bytes"))
    assert(ic(QualifiedName("exists"))      == RuntimeCall("crate::runtime::_exists"))
    assert(ic(QualifiedName("isFile"))      == RuntimeCall("crate::runtime::_is_file"))
    assert(ic(QualifiedName("isDir"))       == RuntimeCall("crate::runtime::_is_dir"))
    assert(ic(QualifiedName("mkdir"))       == RuntimeCall("crate::runtime::_mkdir"))
    assert(ic(QualifiedName("mkdirs"))      == RuntimeCall("crate::runtime::_mkdirs"))
    assert(ic(QualifiedName("listDir"))     == RuntimeCall("crate::runtime::_list_dir"))
    assert(ic(QualifiedName("deleteFile"))  == RuntimeCall("crate::runtime::_delete_file"))
    assert(ic(QualifiedName("copyFile"))    == RuntimeCall("crate::runtime::_copy_file"))
    assert(ic(QualifiedName("moveFile"))    == RuntimeCall("crate::runtime::_move_file"))

  test("runtime ships _append_file + _read_bytes + _exists"):
    val r = rt()
    assert(r.contains("pub fn _append_file"), s"_append_file missing in mod.rs:\n$r")
    assert(r.contains("pub fn _read_bytes"),  s"_read_bytes missing in mod.rs:\n$r")
    assert(r.contains("pub fn _exists"),      s"_exists missing in mod.rs:\n$r")
    assert(r.contains("pub fn _list_dir"),    s"_list_dir missing in mod.rs:\n$r")
    assert(r.contains("pub fn _delete_file"), s"_delete_file missing in mod.rs:\n$r")
    assert(r.contains("pub fn _copy_file"),   s"_copy_file missing in mod.rs:\n$r")

  // ── std.os intrinsics ──────────────────────────────────────────────

  test("std.os intrinsics wired in table"):
    val ic = new RustBackend().intrinsics
    import scalascript.ir.QualifiedName
    assert(ic(QualifiedName("envOrElse"))     == RuntimeCall("crate::runtime::_env_or_else"))
    assert(ic(QualifiedName("cwd"))           == RuntimeCall("crate::runtime::_cwd"))
    assert(ic(QualifiedName("sep"))           == RuntimeCall("crate::runtime::_sep"))
    assert(ic(QualifiedName("pathJoin"))      == RuntimeCall("crate::runtime::_path_join"))
    assert(ic(QualifiedName("pathDirname"))   == RuntimeCall("crate::runtime::_path_dirname"))
    assert(ic(QualifiedName("pathBasename"))  == RuntimeCall("crate::runtime::_path_basename"))
    assert(ic(QualifiedName("pathExtname"))   == RuntimeCall("crate::runtime::_path_extname"))
    assert(ic(QualifiedName("pathResolve"))   == RuntimeCall("crate::runtime::_path_resolve"))
    assert(ic(QualifiedName("pathIsAbsolute")) == RuntimeCall("crate::runtime::_path_is_absolute"))
    assert(ic(QualifiedName("tempDir"))       == RuntimeCall("crate::runtime::_temp_dir"))
    assert(ic(QualifiedName("tempFile"))      == RuntimeCall("crate::runtime::_temp_file"))
    assert(ic(QualifiedName("platform"))      == RuntimeCall("crate::runtime::_platform"))
    assert(ic(QualifiedName("homedir"))       == RuntimeCall("crate::runtime::_homedir"))
    assert(ic(QualifiedName("hostname"))      == RuntimeCall("crate::runtime::_hostname"))

  test("env returns Option<String> in runtime"):
    val r = rt()
    assert(r.contains("pub fn _env(name: &str) -> Option<String>"),
      s"_env Option<String> signature missing:\n$r")

  test("envOrElse returns String in runtime"):
    val r = rt()
    assert(r.contains("pub fn _env_or_else(name: &str, default: &str) -> String"),
      s"_env_or_else signature missing:\n$r")

  test("runtime ships path helpers + platform + homedir + hostname"):
    val r = rt()
    assert(r.contains("pub fn _cwd"),          s"_cwd missing:\n$r")
    assert(r.contains("pub fn _sep"),          s"_sep missing:\n$r")
    assert(r.contains("pub fn _path_join"),    s"_path_join missing:\n$r")
    assert(r.contains("pub fn _path_dirname"), s"_path_dirname missing:\n$r")
    assert(r.contains("pub fn _temp_dir"),     s"_temp_dir missing:\n$r")
    assert(r.contains("pub fn _platform"),     s"_platform missing:\n$r")
    assert(r.contains("pub fn _homedir"),      s"_homedir missing:\n$r")
    assert(r.contains("pub fn _hostname"),     s"_hostname missing:\n$r")

  test("platform() returns 'Native'"):
    val r = rt()
    assert(r.contains("\"Native\".to_string()"), s"Native platform missing:\n$r")

  // ── std.process intrinsics ────────────────────────────────────────

  test("std.process exec wired in table"):
    val ic = new RustBackend().intrinsics
    import scalascript.ir.QualifiedName
    assert(ic(QualifiedName("exec")) == RuntimeCall("crate::runtime::_exec"))

  test("runtime ships ProcessResult struct + _exec"):
    val r = rt()
    assert(r.contains("pub struct ProcessResult"), s"ProcessResult missing:\n$r")
    assert(r.contains("pub stdout:"),              s"stdout field missing:\n$r")
    assert(r.contains("pub stderr:"),              s"stderr field missing:\n$r")
    assert(r.contains("pub exitCode:"),            s"exitCode field missing:\n$r")
    assert(r.contains("pub fn _exec"),             s"_exec missing:\n$r")
    assert(r.contains("std::process::Command"),    s"Command missing:\n$r")

  // ── Cargo stays dep-free for all std.fs/os/process ───────────────

  test("std.fs/os/process stays dep-free — no extra crates"):
    new RustBackend().compile(Normalize(Parser.parse(
      """```scalascript
        |@main def run(): Unit =
        |  writeFile("/tmp/t.txt", "hello")
        |  val s = readFile("/tmp/t.txt")
        |  println(cwd)
        |  println(platform)
        |```
        |""".stripMargin
    )), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        val toml = segs.collectFirst {
          case Segment.Asset("Cargo.toml", b, _) => new String(b, "UTF-8")
        }.getOrElse(fail("Cargo.toml missing"))
        assert(!toml.contains("sha2"))
        assert(!toml.contains("base64"))
        assert(!toml.contains("serde_json"))
        assert(!toml.contains("argon2"))
        assert(!toml.contains("tokio"))
      case other => fail(s"expected Segmented, got $other")
