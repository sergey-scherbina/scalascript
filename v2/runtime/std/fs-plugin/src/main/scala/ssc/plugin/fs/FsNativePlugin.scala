package ssc.plugin.fs

import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import ssc.Value
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free JVM file-system provider for the standard ScalaScript 2.1 runtime. */
final class FsNativePlugin extends NativePlugin:
  def id: String = "30-fs"

  private def text(args: List[Value], index: Int, operation: String): String = args.lift(index) match
    case Some(Value.StrV(value)) => value
    case _ => throw new RuntimeException(s"$operation argument ${index + 1} must be String")

  private def list(values: IterableOnce[Value]): Value =
    values.iterator.toList.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, rest) =>
      Value.DataV("Cons", Vector(value, rest))
    }

  private def bytes(value: Value): Array[Byte] = value match
    case Value.BytesV(values) => values.toArray
    case other =>
      val out = collection.mutable.ArrayBuffer.empty[Byte]
      var current = other
      var done = false
      while !done do
        current match
          case Value.DataV("Cons", Seq(Value.IntV(value), rest)) =>
            out += value.toByte
            current = rest
          case Value.DataV("Nil", _) => done = true
          case _ => throw new RuntimeException("writeBytes argument 2 must be List[Int]")
      out.toArray

  private def native(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, -1)(fn)

  def install(context: NativePluginContext): Unit =
    native(context, "readFile") { args =>
      Value.StrV(Files.readString(Paths.get(text(args, 0, "readFile"))))
    }
    native(context, "writeFile") { args =>
      Files.writeString(Paths.get(text(args, 0, "writeFile")), text(args, 1, "writeFile"))
      Value.UnitV
    }
    native(context, "appendFile") { args =>
      Files.writeString(
        Paths.get(text(args, 0, "appendFile")),
        text(args, 1, "appendFile"),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND)
      Value.UnitV
    }
    native(context, "readBytes") { args =>
      list(Files.readAllBytes(Paths.get(text(args, 0, "readBytes"))).iterator
        .map(byte => Value.IntV((byte & 0xff).toLong)))
    }
    native(context, "writeBytes") { args =>
      val path = Paths.get(text(args, 0, "writeBytes"))
      val value = args.lift(1).getOrElse(
        throw new RuntimeException("writeBytes argument 2 must be List[Int]"))
      Files.write(path, bytes(value))
      Value.UnitV
    }
    native(context, "exists") { args =>
      Value.BoolV(Files.exists(Paths.get(text(args, 0, "exists"))))
    }
    native(context, "isFile") { args =>
      Value.BoolV(Files.isRegularFile(Paths.get(text(args, 0, "isFile"))))
    }
    native(context, "isDir") { args =>
      Value.BoolV(Files.isDirectory(Paths.get(text(args, 0, "isDir"))))
    }
    native(context, "mkdir") { args =>
      val path = Paths.get(text(args, 0, "mkdir"))
      if !Files.exists(path) then Files.createDirectory(path)
      Value.UnitV
    }
    native(context, "mkdirs") { args =>
      Files.createDirectories(Paths.get(text(args, 0, "mkdirs")))
      Value.UnitV
    }
    native(context, "listDir") { args =>
      val stream = Files.list(Paths.get(text(args, 0, "listDir")))
      try list(stream.iterator.asScala.map(_.getFileName.toString).toList.sorted.map(Value.StrV(_)))
      finally stream.close()
    }
    native(context, "deleteFile") { args =>
      Files.deleteIfExists(Paths.get(text(args, 0, "deleteFile")))
      Value.UnitV
    }
    native(context, "copyFile") { args =>
      Files.copy(
        Paths.get(text(args, 0, "copyFile")),
        Paths.get(text(args, 1, "copyFile")))
      Value.UnitV
    }
    native(context, "moveFile") { args =>
      Files.move(
        Paths.get(text(args, 0, "moveFile")),
        Paths.get(text(args, 1, "moveFile")))
      Value.UnitV
    }
