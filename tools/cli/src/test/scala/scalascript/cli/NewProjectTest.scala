package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

class NewProjectTest extends AnyFunSuite:
  test("app template is default and creates runnable project skeleton"):
    val out = os.temp.dir(prefix = "ssc-new-app")
    try
      val opts = NewProject.parseOptions(Nil)
      assert(opts.template == "app")
      val dir = NewProject.create("demo-app", outputDir = out)
      assert(os.exists(dir / "build.sbt"))
      assert(os.exists(dir / "project" / "plugins.sbt"))
      assert(os.exists(dir / "src" / "main" / "scalascript" / "Main.ssc"))
      assert(os.read(dir / "README.md").contains("DemoApp"))
      assert(os.read(dir / "src" / "main" / "scalascript" / "Main.ssc").contains("Hello from DemoApp"))
    finally os.remove.all(out)

  test("lib template creates substituted library source"):
    val out = os.temp.dir(prefix = "ssc-new-lib")
    try
      val dir = NewProject.create("demo-lib", template = "lib", outputDir = out)
      assert(os.exists(dir / "build.sbt"))
      assert(os.exists(dir / "project" / "plugins.sbt"))
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
      assert(os.read(web / "build.sbt").contains("""sscBackend := "js""""))

      val wasm = NewProject.create("demo-wasm", template = "wasm-app", outputDir = out)
      assert(os.exists(wasm / "src" / "main" / "scalascript" / "Main.ssc"))
      assert(os.read(wasm / "build.sbt").contains("""sscBackend := "wasm""""))
    finally os.remove.all(out)

  test("plugin template creates substituted project files"):
    val out = os.temp.dir(prefix = "ssc-new-plugin")
    try
      val dir = NewProject.create("demo-plugin", template = "plugin", outputDir = out)
      assert(os.exists(dir / "build.sbt"))
      assert(os.exists(dir / "plugin" / "manifest.yaml"))
      assert(os.exists(dir / ".github" / "workflows" / "release.yml"))
      assert(os.exists(dir / "src" / "main" / "scala" / "com" / "example" / "demo" / "plugin" / "DemoPlugin.scala"))
      assert(os.read(dir / "plugin" / "manifest.yaml").contains("id: com.example.demo-plugin"))
      assert(os.read(dir / "src" / "main" / "resources" / "META-INF" / "services" / "scalascript.backend.spi.Backend")
        .contains("com.example.demo.plugin.DemoPlugin"))
    finally os.remove.all(out)
