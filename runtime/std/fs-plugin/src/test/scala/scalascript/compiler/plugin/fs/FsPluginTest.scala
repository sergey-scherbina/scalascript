package scalascript.compiler.plugin.fs

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** std-fs-os Phase 2 — JVM FsPlugin intrinsics. */
class FsPluginTest extends AnyFunSuite:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val jvmInterp = Interpreter(out = ps)
    jvmInterp.installPlugins(List(FsInterpreterPlugin()))
    jvmInterp.run(Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n"))
    ps.flush()
    buf.toString.trim

  private def withTempDir[A](body: java.nio.file.Path => A): A =
    val dir = java.nio.file.Files.createTempDirectory("fs-plugin-test-")
    try body(dir)
    finally
      dir.toFile.listFiles.foreach(_.delete())
      dir.toFile.delete()

  private def check(actual: String, expected: String): Unit =
    assert(actual == expected, s"\nActual:   '$actual'\nExpected: '$expected'")

  test("writeFile then readFile round-trips content"):
    withTempDir { dir =>
      val path = dir.resolve("hello.txt").toString
      check(run(
        s"""writeFile("$path", "hello world")
           |println(readFile("$path"))""".stripMargin
      ), "hello world")
    }

  test("appendFile adds to existing content"):
    withTempDir { dir =>
      val path = dir.resolve("append.txt").toString
      check(run(
        s"""writeFile("$path", "line1")
           |appendFile("$path", "\\nline2")
           |println(readFile("$path"))""".stripMargin
      ), "line1\nline2")
    }

  test("exists returns true for an existing file"):
    withTempDir { dir =>
      val path = dir.resolve("f.txt").toString
      check(run(
        s"""writeFile("$path", "x")
           |println(exists("$path"))""".stripMargin
      ), "true")
    }

  test("exists returns false for a missing file"):
    val missing = "/tmp/fs-plugin-definitely-does-not-exist-9f3a7b.txt"
    check(run(s"""println(exists("$missing"))"""), "false")

  test("isFile and isDir are distinct"):
    withTempDir { dir =>
      val path = dir.resolve("f.txt").toString
      check(run(
        s"""writeFile("$path", "x")
           |println(isFile("$path"))
           |println(isDir("$path"))""".stripMargin
      ), "true\nfalse")
    }

  test("isDir returns true for a directory"):
    withTempDir { dir =>
      check(run(s"""println(isDir("${dir}"))"""), "true")
    }

  test("mkdir creates a directory"):
    withTempDir { dir =>
      val sub = dir.resolve("sub").toString
      check(run(
        s"""mkdir("$sub")
           |println(isDir("$sub"))""".stripMargin
      ), "true")
    }

  test("mkdirs creates nested directories"):
    withTempDir { dir =>
      val nested = dir.resolve("a/b/c").toString
      check(run(
        s"""mkdirs("$nested")
           |println(isDir("$nested"))""".stripMargin
      ), "true")
    }

  test("listDir returns filenames in a directory"):
    withTempDir { dir =>
      val f1 = dir.resolve("alpha.txt").toString
      val f2 = dir.resolve("beta.txt").toString
      check(run(
        s"""writeFile("$f1", "a")
           |writeFile("$f2", "b")
           |val names = listDir("${dir}").sorted
           |println(names.mkString(","))""".stripMargin
      ), "alpha.txt,beta.txt")
    }

  test("deleteFile removes a file"):
    withTempDir { dir =>
      val path = dir.resolve("del.txt").toString
      check(run(
        s"""writeFile("$path", "x")
           |deleteFile("$path")
           |println(exists("$path"))""".stripMargin
      ), "false")
    }

  test("deleteFile is a noop for a non-existent file"):
    check(run(
      """deleteFile("/tmp/fs-plugin-no-such-file-9f3a7b.txt")
        |println("ok")""".stripMargin
    ), "ok")

  test("copyFile copies content"):
    withTempDir { dir =>
      val src = dir.resolve("src.txt").toString
      val dst = dir.resolve("dst.txt").toString
      check(run(
        s"""writeFile("$src", "content")
           |copyFile("$src", "$dst")
           |println(readFile("$dst"))""".stripMargin
      ), "content")
    }

  test("moveFile relocates file"):
    withTempDir { dir =>
      val src = dir.resolve("src.txt").toString
      val dst = dir.resolve("dst.txt").toString
      check(run(
        s"""writeFile("$src", "data")
           |moveFile("$src", "$dst")
           |println(exists("$src"))
           |println(readFile("$dst"))""".stripMargin
      ), "false\ndata")
    }
