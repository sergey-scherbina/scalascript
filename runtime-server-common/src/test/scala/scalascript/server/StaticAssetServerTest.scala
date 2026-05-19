package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

class StaticAssetServerTest extends AnyFunSuite with Matchers:

  private def withTempDir[A](f: java.io.File => A): A =
    val dir = Files.createTempDirectory("ssc-static-test").toFile
    try f(dir)
    finally { dir.listFiles().foreach(_.delete()); dir.delete(); () }

  test("resolve — existing file returns Some") {
    withTempDir { root =>
      val file = new java.io.File(root, "hello.txt")
      Files.writeString(file.toPath, "hi")
      StaticAssetServer.resolve(root.getPath, "/hello.txt") shouldBe Some(file.getCanonicalFile)
    }
  }

  test("resolve — missing file returns None") {
    withTempDir { root =>
      StaticAssetServer.resolve(root.getPath, "/missing.txt") shouldBe None
    }
  }

  test("resolve — path traversal attack returns None") {
    withTempDir { root =>
      StaticAssetServer.resolve(root.getPath, "/../etc/passwd") shouldBe None
      StaticAssetServer.resolve(root.getPath, "/../../secret") shouldBe None
    }
  }

  test("resolve — .ssc source files are blocked") {
    withTempDir { root =>
      val file = new java.io.File(root, "app.ssc")
      Files.writeString(file.toPath, "val x = 1")
      StaticAssetServer.resolve(root.getPath, "/app.ssc") shouldBe None
    }
  }

  test("resolve — empty path returns None") {
    withTempDir { root =>
      StaticAssetServer.resolve(root.getPath, "/") shouldBe None
      StaticAssetServer.resolve(root.getPath, "") shouldBe None
    }
  }

  test("resolve — nested file inside subdirectory resolves") {
    withTempDir { root =>
      val sub  = new java.io.File(root, "assets")
      sub.mkdir()
      val file = new java.io.File(sub, "style.css")
      Files.writeString(file.toPath, "body{}")
      StaticAssetServer.resolve(root.getPath, "/assets/style.css") shouldBe Some(file.getCanonicalFile)
    }
  }

  test("resolve — directory path (not a file) returns None") {
    withTempDir { root =>
      val sub = new java.io.File(root, "subdir")
      sub.mkdir()
      StaticAssetServer.resolve(root.getPath, "/subdir") shouldBe None
    }
  }
