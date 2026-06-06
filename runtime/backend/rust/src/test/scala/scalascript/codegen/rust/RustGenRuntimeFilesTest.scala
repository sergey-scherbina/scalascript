package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.ir
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.1.3b — fixed-template runtime asset emit + intrinsic wiring. */
class RustGenRuntimeFilesTest extends AnyFunSuite:

  private val emptyOpts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map.empty
  )

  private def assets(module: ir.NormalizedModule): Map[String, String] =
    new RustBackend().compile(module, emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collect {
          case Segment.Asset(name, bytes, _) => name -> new String(bytes, "UTF-8")
        }.toMap
      case other => fail(s"expected Segmented, got ${other.getClass.getSimpleName}")

  private val emptyModule: ir.NormalizedModule =
    ir.NormalizedModule(manifest = None, sections = Nil)

  test("emits src/value.rs verbatim from the runtime template"):
    val m = assets(emptyModule)
    assert(m.contains("src/value.rs"))
    assert(m("src/value.rs") == RustRuntimeTemplates.ValueRs)

  test("emits src/runtime/mod.rs verbatim from the runtime template"):
    val m = assets(emptyModule)
    assert(m.contains("src/runtime/mod.rs"))
    assert(m("src/runtime/mod.rs") == RustRuntimeTemplates.RuntimeModRs)

  test("Cargo.toml is still emitted alongside the runtime files"):
    val m = assets(emptyModule)
    assert(m.contains("Cargo.toml"))
    assert(m("Cargo.toml").contains("[package]"))

  test("the asset list emits the full crate skeleton in a fixed order"):
    new RustBackend().compile(emptyModule, emptyOpts) match
      case CompileResult.Segmented(segs) =>
        val names = segs.collect { case Segment.Asset(n, _, _) => n }
        // No @main → root is src/lib.rs.
        assert(
          names == List(
            "Cargo.toml",
            "src/value.rs",
            "src/runtime/mod.rs",
            "src/generated/mod.rs",
            "src/generated/ssc_program.rs",
            "src/lib.rs"
          ),
          s"got order: $names"
        )
      case other => fail(s"expected Segmented, got $other")

  test("ValueRs template defines the closed Value enum surface (R.1)"):
    val v = RustRuntimeTemplates.ValueRs
    assert(v.contains("pub enum Value"))
    Seq("Unit,", "Bool(bool)", "Int(i64)", "Double(f64)",
        "Str(String)", "Tuple(Vec<Value>)", "List(Vec<Value>)").foreach { v_ =>
      assert(v.contains(v_), s"value.rs is missing variant: $v_")
    }
    assert(v.contains("pub fn show(&self) -> String"))

  test("RuntimeMod template exposes _show/_print/_println over crate::value::Value"):
    val r = RustRuntimeTemplates.RuntimeModRs
    assert(r.contains("use crate::value::Value;"))
    assert(r.contains("pub fn _show(v: &Value) -> String"))
    assert(r.contains("pub fn _print(s: impl AsRef<str>)"))
    assert(r.contains("pub fn _println(s: impl AsRef<str>)"))

  test("RustIntrinsics wires the four console-I/O entries to runtime helpers"):
    val ic = new RustBackend().intrinsics
    assert(ic(ir.QualifiedName("println"))         == RuntimeCall("crate::runtime::_println"))
    assert(ic(ir.QualifiedName("print"))           == RuntimeCall("crate::runtime::_print"))
    assert(ic(ir.QualifiedName("Console.println")) == RuntimeCall("crate::runtime::_println"))
    assert(ic(ir.QualifiedName("Console.print"))   == RuntimeCall("crate::runtime::_print"))
