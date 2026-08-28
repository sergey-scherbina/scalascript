package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

class NewProjectTest extends AnyFunSuite:
  test("app template is default, sbt off by default, and creates a runnable project skeleton"):
    val out = os.temp.dir(prefix = "ssc-new-app")
    try
      val opts = NewProject.parseOptions(Nil)
      assert(opts.template == "app")
      assert(!opts.sbt, "sbt should be off unless --sbt is passed")
      val dir = NewProject.create("demo-app", outputDir = out)
      assert(!os.exists(dir / "build.sbt"), "no build.sbt without --sbt")
      assert(!os.exists(dir / "project"), "no project/ without --sbt")
      assert(os.exists(dir / "src" / "main" / "scalascript" / "Main.ssc"))
      if gitAvailable then assert(os.exists(dir / ".git"), "ssc new should git-init projects when git is available")
      assert(os.read(dir / "README.md").contains("DemoApp"))
      assert(os.read(dir / "README.md").contains("ssc run src/main/scalascript/Main.ssc"))
      assert(!os.read(dir / "README.md").contains("sbt"), "no sbt mention in the README without --sbt")
      assert(os.read(dir / "src" / "main" / "scalascript" / "Main.ssc").contains("Hello from DemoApp"))
    finally os.remove.all(out)

  test("--sbt adds build.sbt + project/ and an extra README section, for every non-plugin template"):
    val out = os.temp.dir(prefix = "ssc-new-sbt")
    try
      val templates = Seq("app", "lib", "dsl", "web-app", "wasm-app")
      templates.foreach { id =>
        val dir = NewProject.create(s"demo-$id-sbt", template = id, outputDir = out, sbt = true)
        assert(os.exists(dir / "build.sbt"), s"$id --sbt should create build.sbt")
        assert(os.exists(dir / "project" / "plugins.sbt"), s"$id --sbt should create project/plugins.sbt")
        assert(os.read(dir / "README.md").contains("## Build (sbt)"), s"$id --sbt README should document the sbt build")
      }
    finally os.remove.all(out)

  test("lib template creates substituted library source"):
    val out = os.temp.dir(prefix = "ssc-new-lib")
    try
      val dir = NewProject.create("demo-lib", template = "lib", outputDir = out)
      assert(!os.exists(dir / "build.sbt"))
      assert(os.exists(dir / "src" / "main" / "scalascript" / "DemoLib.ssc"))
      assert(os.read(dir / "README.md").contains("[DemoLib]"))
    finally os.remove.all(out)

  test("additional templates create their expected entry files"):
    val out = os.temp.dir(prefix = "ssc-new-extra")
    try
      val dsl = NewProject.create("demo-dsl", template = "dsl", outputDir = out)
      assert(os.exists(dsl / "src" / "main" / "scalascript" / "DemoDsl.ssc"))
      assert(os.exists(dsl / "examples" / "example.ssc"))

      val web = NewProject.create("demo-web", template = "web-app", outputDir = out)
      assert(os.exists(web / "src" / "main" / "scalascript" / "App.ssc"))
      assert(os.read(web / "README.md").contains("ssc emit-spa"))

      val wasm = NewProject.create("demo-wasm", template = "wasm-app", outputDir = out)
      assert(os.exists(wasm / "src" / "main" / "scalascript" / "Main.ssc"))
      assert(os.read(wasm / "README.md").contains("ssc compile-wasm"))

      // sscBackend selection only matters once build.sbt exists at all.
      val webSbt = NewProject.create("demo-web-sbt", template = "web-app", outputDir = out, sbt = true)
      assert(os.read(webSbt / "build.sbt").contains("""sscBackend := "js""""))
      val wasmSbt = NewProject.create("demo-wasm-sbt", template = "wasm-app", outputDir = out, sbt = true)
      assert(os.read(wasmSbt / "build.sbt").contains("""sscBackend := "wasm""""))
    finally os.remove.all(out)

  test("plugin template creates substituted project files (sbt always on, --sbt not required)"):
    val out = os.temp.dir(prefix = "ssc-new-plugin")
    try
      val dir = NewProject.create("demo-plugin", template = "plugin", outputDir = out)
      assert(os.exists(dir / "build.sbt"), "plugin needs sbt even though --sbt was not passed")
      assert(os.exists(dir / "plugin" / "manifest.yaml"))
      assert(os.exists(dir / ".github" / "workflows" / "release.yml"))
      assert(os.exists(dir / "src" / "main" / "scala" / "com" / "example" / "demo" / "plugin" / "DemoPlugin.scala"))
      assert(os.read(dir / "plugin" / "manifest.yaml").contains("id: com.example.demo-plugin"))
      assert(os.read(dir / "src" / "main" / "resources" / "META-INF" / "services" / "scalascript.backend.spi.Backend")
        .contains("com.example.demo.plugin.DemoPlugin"))
    finally os.remove.all(out)

  test("a name starting with a digit produces a valid (non-digit-leading) package segment"):
    val out = os.temp.dir(prefix = "ssc-new-digit")
    try
      // `123plugin` used to render `package com.example.123plugin` in the `plugin` template's
      // real .scala sources -- invalid Scala/Java syntax, since no identifier segment may start
      // with a digit. `manifest.yaml`'s `id:` field is a free-form string (no identifier
      // constraint) and legitimately keeps the raw "123plugin"; only ${packageName}-derived
      // output (the .scala sources, and the service-loader file naming one by its FQCN) must be
      // a syntactically valid package path.
      val dir = NewProject.create("123plugin", template = "plugin", outputDir = out)
      val scalaSrc = os.walk(dir / "src" / "main" / "scala").find(p => os.isFile(p) && p.ext == "scala").get
      assert(!os.read(scalaSrc).contains("package com.example.123"), s"invalid package declaration in $scalaSrc")
      val serviceFile = os.read(dir / "src" / "main" / "resources" / "META-INF" / "services" / "scalascript.backend.spi.Backend")
      assert(serviceFile.trim.split('.').forall(seg => seg.isEmpty || !seg.head.isDigit),
        s"every package segment must be a valid identifier: $serviceFile")
    finally os.remove.all(out)

  test("parseOptions rejects an unknown template with the real list, not a resource-lookup leak"):
    val err = intercept[IllegalArgumentException](NewProject.parseOptions(List("--template", "bogus")))
    assert(err.getMessage.contains("unknown template 'bogus'"), err.getMessage)
    assert(err.getMessage.contains("app"), "should list the real template names")

  test("create rejects an unknown template even bypassing parseOptions"):
    val out = os.temp.dir(prefix = "ssc-new-badtpl")
    try
      val err = intercept[IllegalArgumentException](NewProject.create("x", template = "bogus", outputDir = out))
      assert(err.getMessage.contains("unknown template 'bogus'"), err.getMessage)
    finally os.remove.all(out)

  test("parseOptions accepts --sbt, every bundled template, and output-dir aliases"):
    val out = os.temp.dir(prefix = "ssc-new-options")
    try
      val templates = Seq("app", "lib", "plugin", "dsl", "web-app", "wasm-app")
      templates.foreach { id =>
        val opts = NewProject.parseOptions(List("--template", id, "--output-dir", out.toString))
        assert(opts.template == id)
        assert(opts.outputDir == out)
        assert(!opts.sbt)
      }
      val short = NewProject.parseOptions(List("-t", "web-app", "-o", out.toString))
      assert(short.template == "web-app")
      assert(short.outputDir == out)
      val dirAlias = NewProject.parseOptions(List("-t", "wasm-app", "--dir", out.toString))
      assert(dirAlias.template == "wasm-app")
      assert(dirAlias.outputDir == out)
      val withSbt = NewProject.parseOptions(List("-t", "app", "--sbt"))
      assert(withSbt.sbt)
    finally os.remove.all(out)

  test("all bundled templates render without leftover placeholders, with sbt off and on"):
    val out = os.temp.dir(prefix = "ssc-new-all")
    try
      val templates = Seq("app", "lib", "plugin", "dsl", "web-app", "wasm-app")
      for
        id <- templates
        sbt <- Seq(false, true)
      do
        val dir = NewProject.create(s"demo-$id-${if sbt then "sbt" else "nosbt"}", template = id, outputDir = out, sbt = sbt)
        val files = os.walk(dir).filter(p => os.isFile(p) && !p.toString.contains("/.git/"))
        assert(files.nonEmpty, s"$id template should render at least one file")
        files.foreach { file =>
          assert(!file.toString.contains("${"), s"unsubstituted placeholder in path: $file")
          val text = os.read(file)
          assert(!text.contains("${"), s"unsubstituted placeholder in $file")
        }
    finally os.remove.all(out)

  private def gitAvailable: Boolean =
    scala.util.Try {
      os.proc("git", "--version")
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        .exitCode == 0
    }.getOrElse(false)
