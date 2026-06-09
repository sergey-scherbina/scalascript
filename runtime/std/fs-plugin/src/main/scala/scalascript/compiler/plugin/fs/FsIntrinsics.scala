package scalascript.compiler.plugin.fs

import scalascript.backend.spi.*
import scalascript.interpreter.Value
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*

object FsIntrinsics:

  private def native(f: List[Any] => Value): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("readFile") -> native {
      case List(p: String) =>
        Value.StringV(Files.readString(Paths.get(p)))
      case _ => Value.StringV("")
    },

    QualifiedName("writeFile") -> native {
      case List(p: String, content: String) =>
        Files.writeString(Paths.get(p), content)
        Value.UnitV
      case _ => Value.UnitV
    },

    QualifiedName("appendFile") -> native {
      case List(p: String, content: String) =>
        Files.writeString(Paths.get(p), content, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        Value.UnitV
      case _ => Value.UnitV
    },

    QualifiedName("readBytes") -> native {
      case List(p: String) =>
        val bytes = Files.readAllBytes(Paths.get(p))
        Value.ListV(bytes.map(b => Value.IntV(b & 0xFF).asInstanceOf[Value]).toList)
      case _ => Value.ListV(Nil)
    },

    QualifiedName("writeBytes") -> native {
      case List(p: String, lst: List[?]) =>
        val bytes = lst.map {
          case i: Long => i.toByte
          case i: Int  => i.toByte
          case _       => 0.toByte
        }.toArray
        Files.write(Paths.get(p), bytes)
        Value.UnitV
      case _ => Value.UnitV
    },

    QualifiedName("exists") -> native {
      case List(p: String) => Value.boolV(Files.exists(Paths.get(p)))
      case _               => Value.False
    },

    QualifiedName("isFile") -> native {
      case List(p: String) => Value.boolV(Files.isRegularFile(Paths.get(p)))
      case _               => Value.False
    },

    QualifiedName("isDir") -> native {
      case List(p: String) => Value.boolV(Files.isDirectory(Paths.get(p)))
      case _               => Value.False
    },

    QualifiedName("mkdir") -> native {
      case List(p: String) =>
        val path = Paths.get(p)
        if !Files.exists(path) then Files.createDirectory(path)
        Value.UnitV
      case _ => Value.UnitV
    },

    QualifiedName("mkdirs") -> native {
      case List(p: String) =>
        Files.createDirectories(Paths.get(p))
        Value.UnitV
      case _ => Value.UnitV
    },

    QualifiedName("listDir") -> native {
      case List(p: String) =>
        val stream = Files.list(Paths.get(p))
        try
          val names = stream.iterator.asScala.map(_.getFileName.toString).toList
          Value.ListV(names.map(Value.StringV(_)))
        finally
          stream.close()
      case _ => Value.ListV(Nil)
    },

    QualifiedName("deleteFile") -> native {
      case List(p: String) =>
        Files.deleteIfExists(Paths.get(p))
        Value.UnitV
      case _ => Value.UnitV
    },

    QualifiedName("copyFile") -> native {
      case List(src: String, dst: String) =>
        Files.copy(Paths.get(src), Paths.get(dst))
        Value.UnitV
      case _ => Value.UnitV
    },

    QualifiedName("moveFile") -> native {
      case List(src: String, dst: String) =>
        Files.move(Paths.get(src), Paths.get(dst))
        Value.UnitV
      case _ => Value.UnitV
    },

  )
