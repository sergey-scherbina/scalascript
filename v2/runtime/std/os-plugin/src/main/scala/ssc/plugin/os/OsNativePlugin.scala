package ssc.plugin.os

import java.nio.file.{Files, Paths}
import ssc.{Runtime, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free JVM environment/path provider for the standard ScalaScript 2.1 runtime. */
final class OsNativePlugin extends NativePlugin:
  def id: String = "20-os"

  private def text(args: List[Value], index: Int, operation: String): String = args.lift(index) match
    case Some(Value.StrV(value)) => value
    case _ => throw new RuntimeException(s"$operation argument ${index + 1} must be String")

  private def integer(args: List[Value], index: Int, operation: String): Long = args.lift(index) match
    case Some(Value.IntV(value)) => value
    case _ => throw new RuntimeException(s"$operation argument ${index + 1} must be Int")

  private def strings(value: Value): List[String] =
    val out = collection.mutable.ListBuffer.empty[String]
    var current = value
    var done = false
    while !done do
      current match
        case Value.DataV("Cons", Seq(Value.StrV(value), rest)) =>
          out += value
          current = rest
        case Value.DataV("Nil", _) => done = true
        case _ => throw new RuntimeException("pathJoin arguments must be String")
    out.toList

  private def pathParts(args: List[Value]): List[String] = args match
    case List(value @ Value.DataV("Cons" | "Nil", _)) => strings(value)
    case values => values.zipWithIndex.map { case (value, index) => value match
      case Value.StrV(text) => text
      case _ => throw new RuntimeException(s"pathJoin argument ${index + 1} must be String")
    }

  private def native(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, -1)(fn)

  def install(context: NativePluginContext): Unit =
    native(context, "env") { args =>
      Option(System.getenv(text(args, 0, "env"))) match
        case Some(value) => Value.DataV("Some", Vector(Value.StrV(value)))
        case None => Value.DataV("None", Vector.empty)
    }
    native(context, "envOrElse") { args =>
      Value.StrV(Option(System.getenv(text(args, 0, "envOrElse")))
        .getOrElse(text(args, 1, "envOrElse")))
    }
    native(context, "exit") { args => Runtime.exitHandler(integer(args, 0, "exit").toInt) }
    native(context, "pathJoin") { args =>
      pathParts(args) match
        case Nil => Value.StrV(".")
        case head :: Nil => Value.StrV(head)
        case head :: tail => Value.StrV(Paths.get(head, tail*).toString)
    }
    native(context, "pathDirname") { args =>
      val parent = Paths.get(text(args, 0, "pathDirname")).getParent
      Value.StrV(if parent == null then "." else parent.toString)
    }
    native(context, "pathBasename") { args =>
      val name = Paths.get(text(args, 0, "pathBasename")).getFileName
      Value.StrV(if name == null then "" else name.toString)
    }
    native(context, "pathExtname") { args =>
      val fileName = Paths.get(text(args, 0, "pathExtname")).getFileName
      if fileName == null then Value.StrV("")
      else
        val name = fileName.toString
        val dot = name.lastIndexOf('.')
        Value.StrV(if dot > 0 then name.substring(dot) else "")
    }
    native(context, "pathResolve") { args =>
      Value.StrV(Paths.get(text(args, 0, "pathResolve")).toAbsolutePath.normalize.toString)
    }
    native(context, "pathIsAbsolute") { args =>
      Value.BoolV(Paths.get(text(args, 0, "pathIsAbsolute")).isAbsolute)
    }
    native(context, "tempFile") { args =>
      Value.StrV(Files.createTempFile(text(args, 0, "tempFile"), text(args, 1, "tempFile")).toString)
    }

    context.registerValue("tempDir", Value.StrV(System.getProperty("java.io.tmpdir", "/tmp")))
    context.registerValue("homedir", Value.StrV(System.getProperty("user.home", "/")))
    val hostname = try java.net.InetAddress.getLocalHost.getHostName
      catch case _: Throwable => "localhost"
    context.registerValue("hostname", Value.StrV(hostname))
