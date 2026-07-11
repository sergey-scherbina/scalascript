package ssc.plugin.host

import ssc.{Prims, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Process/runtime globals owned by the standard native host. */
final class HostNativePlugin extends NativePlugin:
  def id: String = "00-host"

  def install(context: NativePluginContext): Unit =
    val args = context.argv.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (arg, rest) =>
      Value.DataV("Cons", Vector(Value.StrV(arg), rest))
    }
    context.registerValue("args", args)
    context.registerValue("cwd", Value.StrV(System.getProperty("user.dir", ".")))
    context.registerValue("sep", Value.StrV(java.io.File.separator))
    context.registerValue("platform", Value.DataV("JVM", Vector.empty))

    context.registerGlobal("doc", -1) { parts =>
      Value.DataV("NativeDoc", parts.toVector)
    }
    context.registerGlobal("render", 1) {
      case Value.DataV("NativeDoc", parts) :: Nil =>
        Console.out.println(parts.map(Prims.display).mkString("\n"))
        Value.UnitV
      case value :: Nil =>
        Console.out.println(Prims.display(value))
        Value.UnitV
      case _ => throw new IllegalArgumentException("render(value)")
    }
