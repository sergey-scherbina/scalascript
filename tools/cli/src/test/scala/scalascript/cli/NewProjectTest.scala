package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

class NewProjectTest extends AnyFunSuite:
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
