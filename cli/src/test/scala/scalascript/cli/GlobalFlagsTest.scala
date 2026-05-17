package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

class GlobalFlagsTest extends AnyFunSuite:

  test("--target captures next arg, rest passes through"):
    val (flags, rest) = GlobalFlags.parse(List("--target", "jvm", "compile", "file.ssc"))
    assert(flags.target.contains("jvm"))
    assert(rest == List("compile", "file.ssc"))

  test("--backend can sit anywhere in the arg list"):
    val (flags, rest) = GlobalFlags.parse(List("compile", "--backend", "js", "file.ssc"))
    assert(flags.backend.contains("js"))
    assert(rest == List("compile", "file.ssc"))

  test("--plugin / --plugin-dir collect multiple paths"):
    val (flags, rest) = GlobalFlags.parse(List(
      "--plugin", "/a.jar",
      "--plugin", "/b.jar",
      "--plugin-dir", "/p1",
      "--plugin-dir", "/p2",
      "run", "x.ssc"
    ))
    assert(flags.pluginJars.map(_.toString) == List("/a.jar", "/b.jar"))
    assert(flags.pluginDirs.map(_.toString) == List("/p1", "/p2"))
    assert(rest == List("run", "x.ssc"))

  test("--list-backends and --describe-backend are recognised"):
    val (a, ra) = GlobalFlags.parse(List("--list-backends"))
    assert(a.listBackends && ra.isEmpty)

    val (b, rb) = GlobalFlags.parse(List("--describe-backend", "jvm"))
    assert(b.describeBackend.contains("jvm") && rb.isEmpty)

  test("unknown flags pass through to the command"):
    val (flags, rest) = GlobalFlags.parse(List("compile", "--scala-cli-flag", "value"))
    assert(flags == GlobalFlags(target = None, backend = None))
    assert(rest == List("compile", "--scala-cli-flag", "value"))

  test("trailing --flag without value passes through unchanged"):
    val (_, rest) = GlobalFlags.parse(List("--target"))
    // No value to consume — flag passes through to command so it can
    // produce a useful error or accept it as data.
    assert(rest == List("--target"))
