package scalascript.compiler.plugin.fs

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*

object FsIntrinsics:

  private def native(f: List[Any] => PluginValue): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("readFile") -> native {
      case List(p: String) =>
        PluginValue.string(Files.readString(Paths.get(p)))
      case _ => PluginValue.string("")
    },

    QualifiedName("writeFile") -> native {
      case List(p: String, content: String) =>
        Files.writeString(Paths.get(p), content)
        PluginValue.unit
      case _ => PluginValue.unit
    },

    QualifiedName("appendFile") -> native {
      case List(p: String, content: String) =>
        Files.writeString(Paths.get(p), content, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        PluginValue.unit
      case _ => PluginValue.unit
    },

    QualifiedName("readBytes") -> native {
      case List(p: String) =>
        val bytes = Files.readAllBytes(Paths.get(p))
        PluginValue.list(bytes.map(b => PluginValue.int(b & 0xFF)).toList)
      case _ => PluginValue.list(Nil)
    },

    QualifiedName("writeBytes") -> native {
      case List(p: String, lst: List[?]) =>
        val bytes = lst.map {
          case i: Long => i.toByte
          case i: Int  => i.toByte
          case _       => 0.toByte
        }.toArray
        Files.write(Paths.get(p), bytes)
        PluginValue.unit
      case _ => PluginValue.unit
    },

    QualifiedName("exists") -> native {
      case List(p: String) => PluginValue.bool(Files.exists(Paths.get(p)))
      case _               => PluginValue.bool(false)
    },

    QualifiedName("isFile") -> native {
      case List(p: String) => PluginValue.bool(Files.isRegularFile(Paths.get(p)))
      case _               => PluginValue.bool(false)
    },

    QualifiedName("isDir") -> native {
      case List(p: String) => PluginValue.bool(Files.isDirectory(Paths.get(p)))
      case _               => PluginValue.bool(false)
    },

    QualifiedName("mkdir") -> native {
      case List(p: String) =>
        val path = Paths.get(p)
        if !Files.exists(path) then Files.createDirectory(path)
        PluginValue.unit
      case _ => PluginValue.unit
    },

    QualifiedName("mkdirs") -> native {
      case List(p: String) =>
        Files.createDirectories(Paths.get(p))
        PluginValue.unit
      case _ => PluginValue.unit
    },

    QualifiedName("listDir") -> native {
      case List(p: String) =>
        val stream = Files.list(Paths.get(p))
        try
          val names = stream.iterator.asScala.map(_.getFileName.toString).toList
          PluginValue.list(names.map(PluginValue.string(_)))
        finally
          stream.close()
      case _ => PluginValue.list(Nil)
    },

    QualifiedName("deleteFile") -> native {
      case List(p: String) =>
        Files.deleteIfExists(Paths.get(p))
        PluginValue.unit
      case _ => PluginValue.unit
    },

    QualifiedName("copyFile") -> native {
      case List(src: String, dst: String) =>
        Files.copy(Paths.get(src), Paths.get(dst))
        PluginValue.unit
      case _ => PluginValue.unit
    },

    QualifiedName("moveFile") -> native {
      case List(src: String, dst: String) =>
        Files.move(Paths.get(src), Paths.get(dst))
        PluginValue.unit
      case _ => PluginValue.unit
    },

  )
