package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 — tests that `ModuleGraph.build` topologically sorts a tree of
 *  `.ssc` modules and that `ModuleGraph.isStale` correctly detects
 *  out-of-date artifacts by comparing SHA-256 source hashes. */
class ModuleGraphTest extends AnyFunSuite:

  private def withTempDir[A](body: os.Path => A): A =
    val d = os.temp.dir(prefix = "ssc-v2-test-")
    try body(d) finally os.remove.all(d)

  /** Markdown source for a module with the given file-name to import (via a
   *  relative-path link).  `extra` lets a test add custom imports. */
  private def moduleWith(imports: List[String], bodyValName: String): String =
    val importLinks = imports.map(p => s"[X]($p)").mkString("\n\n")
    s"""# M
       |
       |$importLinks
       |
       |```scalascript
       |val $bodyValName = 1
       |```
       |""".stripMargin

  // ── Topological sort ───────────────────────────────────────────────────

  test("build — topo-sort on a 3-module chain A → B → C returns [A, B, C]"):
  // A is depended on by B; B is depended on by C.  In Kahn's algorithm the
  // module with no incoming edges (A) comes out first.
    withTempDir { d =>
      os.write(d / "a.ssc", moduleWith(imports = Nil,            bodyValName = "a"))
      os.write(d / "b.ssc", moduleWith(imports = List("./a.ssc"), bodyValName = "b"))
      os.write(d / "c.ssc", moduleWith(imports = List("./b.ssc"), bodyValName = "c"))
      val res = ModuleGraph.build(d)
      assert(res.cycles.isEmpty, s"unexpected cycles: ${res.cycles}")
      val order = res.orderedNodes.map(_.path.last)
      assert(order == List("a.ssc", "b.ssc", "c.ssc"),
        s"expected [a.ssc, b.ssc, c.ssc], got $order")
    }

  test("build — isolated modules each appear in the output (no edges)"):
    withTempDir { d =>
      os.write(d / "x.ssc", moduleWith(Nil, "x"))
      os.write(d / "y.ssc", moduleWith(Nil, "y"))
      val res = ModuleGraph.build(d)
      assert(res.cycles.isEmpty)
      val names = res.orderedNodes.map(_.path.last).toSet
      assert(names == Set("x.ssc", "y.ssc"))
    }

  test("build — skips files under `target` / `node_modules` / hidden dirs"):
    withTempDir { d =>
      os.write(d / "keep.ssc", moduleWith(Nil, "keep"))
      os.makeDir.all(d / "target")
      os.write(d / "target" / "drop.ssc", moduleWith(Nil, "drop"))
      os.makeDir.all(d / ".hidden")
      os.write(d / ".hidden" / "drop.ssc", moduleWith(Nil, "drop"))
      val res = ModuleGraph.build(d)
      val names = res.orderedNodes.map(_.path.last)
      assert(names.contains("keep.ssc"))
      assert(!names.exists(_ == "drop.ssc"),
        s"build should not visit `target` / dot-dirs, got $names")
    }

  // ── Cycle detection ────────────────────────────────────────────────────

  test("build — cycle A → B → A is detected and reported in `cycles`"):
    withTempDir { d =>
      os.write(d / "a.ssc", moduleWith(imports = List("./b.ssc"), bodyValName = "a"))
      os.write(d / "b.ssc", moduleWith(imports = List("./a.ssc"), bodyValName = "b"))
      val res = ModuleGraph.build(d)
      assert(res.cycles.nonEmpty, "expected at least one detected cycle")
      val cyclePaths = res.cycles.head.map(_.last).toSet
      assert(cyclePaths == Set("a.ssc", "b.ssc"),
        s"cycle should contain both files, got $cyclePaths")
    // The cyclic nodes do NOT appear in the topo order — only acyclic
    // prefix is emitted by Kahn.
      val ordered = res.orderedNodes.map(_.path.last).toSet
      assert(!ordered.contains("a.ssc") || !ordered.contains("b.ssc"),
        s"cyclic nodes must not all appear in topo order, got: $ordered")
    }

  // ── isStale ────────────────────────────────────────────────────────────

  test("isStale — true when no `.scim` artifact exists"):
    withTempDir { d =>
      val src = d / "a.ssc"
      os.write(src, "# Plain\n")
      assert(ModuleGraph.isStale(src, d),
        "module with no artifact must be stale")
    }

  test("isStale — false when source hash matches stored artifact hash"):
    withTempDir { d =>
      val src = d / "m.ssc"
      val srcBytes = "# Hello\n\n```scalascript\nval x = 1\n```\n".getBytes("UTF-8")
      os.write(src, srcBytes)
      val module = scalascript.parser.Parser.parse(new String(srcBytes, "UTF-8"))
      val iface  = InterfaceExtractor.extract(module, srcBytes)
      ArtifactIO.writeInterfaceFile(iface, d / "m.scim")
      assert(!ModuleGraph.isStale(src, d),
        "freshly-extracted interface should not be stale")
    }

  test("isStale — true when source hash diverges from stored artifact hash"):
    withTempDir { d =>
      val src = d / "m.ssc"
      val origBytes = "# Hello\n\n```scalascript\nval x = 1\n```\n".getBytes("UTF-8")
      os.write(src, origBytes)
      val module = scalascript.parser.Parser.parse(new String(origBytes, "UTF-8"))
      val iface  = InterfaceExtractor.extract(module, origBytes)
      ArtifactIO.writeInterfaceFile(iface, d / "m.scim")
      assert(!ModuleGraph.isStale(src, d))
    // Now mutate the source so the hash diverges from the stored artifact.
      val mutated = "# Hello\n\n```scalascript\nval x = 2\n```\n"
      os.write.over(src, mutated)
      assert(ModuleGraph.isStale(src, d),
        "after editing the source, the module must be reported stale")
    }

  test("isStale — true when the `.scim` file is corrupt / unparseable"):
    withTempDir { d =>
      val src = d / "m.ssc"
      os.write(src, "# Hi\n")
      os.write(d / "m.scim", "this is not valid JSON")
      assert(ModuleGraph.isStale(src, d),
        "unreadable artifact must be treated as stale")
    }
