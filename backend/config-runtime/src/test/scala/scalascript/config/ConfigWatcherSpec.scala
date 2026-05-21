package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}

class ConfigWatcherSpec extends AnyFunSuite with Matchers:

  def withTempDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("ssc-watcher-test")
    try body(dir)
    finally
      dir.toFile.listFiles().nn.foreach(_.delete())
      dir.toFile.delete()

  test("detects file modification"):
    withTempDir { dir =>
      val file  = dir.resolve("app.yaml")
      Files.writeString(file, "port: 8080")
      val latch = new CountDownLatch(1)
      val w     = new ConfigWatcher(List(file), () => latch.countDown())
      Thread.sleep(200)
      Files.writeString(file, "port: 9090")
      val triggered = latch.await(3, TimeUnit.SECONDS)
      w.close()
      triggered shouldBe true
    }

  test("does not trigger for unregistered files"):
    withTempDir { dir =>
      val watched   = dir.resolve("watched.yaml")
      val unwatched = dir.resolve("other.yaml")
      Files.writeString(watched, "a: 1")
      Files.writeString(unwatched, "b: 2")
      var triggered = false
      val w = new ConfigWatcher(List(watched), () => triggered = true)
      Thread.sleep(200)
      Files.writeString(unwatched, "b: 99")
      Thread.sleep(600)
      w.close()
      triggered shouldBe false
    }

  test("ConfigWatcher.fromLoader: None when no external files"):
    val loader  = ConfigLoader(envLookup = _ => None)
    ConfigWatcher.fromLoader(loader, () => ()) shouldBe None

  test("ConfigWatcher.fromLoader: Some when external files present"):
    withTempDir { dir =>
      Files.writeString(dir.resolve("cfg.yaml"), "x: 1")
      val loader  = ConfigLoader(
        externalFiles = List(ExternalConfigFile("cfg.yaml", basePath = dir)),
        envLookup     = _ => None,
      )
      val w = ConfigWatcher.fromLoader(loader, () => ())
      w.isDefined shouldBe true
      w.foreach(_.close())
    }
