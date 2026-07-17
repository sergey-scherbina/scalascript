package ssc.plugin.scljetvfs

import scalascript.compiler.plugin.scljet.{HostLockLevel, HostResult, HostShmMode, SclJetJvmVfsHost}
import ssc.Value
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Host-file VFS intrinsics (`jvmVfs*`) for the standard ScalaScript 2.1 runtime — the native
 *  half of what `v1/runtime/std/scljet-vfs-plugin` gives the interpreter.
 *
 *  Both plugins are thin adapters over the SAME implementation: `SclJetJvmVfsHost`
 *  (`scljetVfsHost`, zero deps), which owns every FileChannel, advisory lock and shm region.
 *  Only the value marshalling differs — v1 speaks `PluginValue`, this speaks `ssc.Value` — so the
 *  two lanes cannot drift on locking or durability, which is the part that would hurt.
 *
 *  Without this, `bin/ssc run` (the DEFAULT command) could not open a real SQLite file at all:
 *  the `jvmVfs*` externs were resolved only through the v1 interpreter's ServiceLoader, while the
 *  native tier loads `ssc.plugin.NativePlugin` — a different SPI it never consulted.
 */
final class ScljetVfsNativePlugin extends NativePlugin:
  def id: String = "40-scljet-vfs"

  // ── ssc.Value marshalling ─────────────────────────────────────────────────

  private def list(values: IterableOnce[Value]): Value =
    values.iterator.toList.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, rest) =>
      Value.DataV("Cons", Vector(value, rest))
    }

  /** `JvmVfsResult(code, message, value)` — the single shape every intrinsic returns
   *  (`scljet/jvm-vfs.ssc`). A failure carries `Unit` in `value`; the `.ssc` never reads it
   *  without checking `code` first. */
  private def result[A](value: HostResult[A])(convert: A => Value): Value =
    Value.DataV("JvmVfsResult", Vector(
      Value.StrV(value.code),
      Value.StrV(value.message),
      value.value.map(convert).getOrElse(Value.UnitV)))

  private def unit(r: HostResult[Unit]): Value       = result(r)(_ => Value.UnitV)
  private def integer(r: HostResult[Long]): Value    = result(r)(v => Value.IntV(v))
  private def integer32(r: HostResult[Int]): Value   = result(r)(v => Value.IntV(v.toLong))
  private def bool(r: HostResult[Boolean]): Value    = result(r)(v => Value.BoolV(v))
  private def string(r: HostResult[String]): Value   = result(r)(v => Value.StrV(v))
  private def byteList(r: HostResult[Array[Byte]]): Value =
    result(r)(a => list(a.iterator.map(b => Value.IntV((b & 0xff).toLong))))
  private def strings(r: HostResult[List[String]]): Value =
    result(r)(vs => list(vs.map(Value.StrV(_))))

  // ── argument extraction ───────────────────────────────────────────────────

  private def argLong(args: List[Value], i: Int, op: String): Long = args.lift(i) match
    case Some(Value.IntV(v)) => v
    case _ => throw RuntimeException(s"$op argument ${i + 1} must be Int")

  private def argInt(args: List[Value], i: Int, op: String): Int = argLong(args, i, op).toInt

  private def argString(args: List[Value], i: Int, op: String): String = args.lift(i) match
    case Some(Value.StrV(v)) => v
    case _ => throw RuntimeException(s"$op argument ${i + 1} must be String")

  private def argBool(args: List[Value], i: Int, op: String): Boolean = args.lift(i) match
    case Some(Value.BoolV(v)) => v
    case _ => throw RuntimeException(s"$op argument ${i + 1} must be Boolean")

  /** `List[Int]` → bytes. Accepts the `Cons/Nil` spine the engine builds, and `BytesV` in case a
   *  future caller hands one over. */
  private def argBytes(args: List[Value], i: Int, op: String): Array[Byte] =
    args.lift(i) match
      case Some(Value.BytesV(values)) => values.toArray
      case Some(other) =>
        val out = collection.mutable.ArrayBuffer.empty[Byte]
        var current = other
        var done = false
        while !done do
          current match
            case Value.DataV("Cons", Seq(Value.IntV(v), rest)) => out += v.toByte; current = rest
            case Value.DataV("Nil", _) => done = true
            case _ => throw RuntimeException(s"$op argument ${i + 1} must be List[Int]")
        out.toArray
      case None => throw RuntimeException(s"$op argument ${i + 1} must be List[Int]")

  /** Lock levels and shm modes arrive as the `.ssc` case objects, i.e. bare data tags. An
   *  unrecognised tag degrades to the weakest option, exactly as the v1 adapter does — the host
   *  is the one enforcing the real lock ordering. */
  private def lockLevel(args: List[Value], i: Int): HostLockLevel = args.lift(i) match
    case Some(Value.DataV("LockShared", _))    => HostLockLevel.Shared
    case Some(Value.DataV("LockReserved", _))  => HostLockLevel.Reserved
    case Some(Value.DataV("LockPending", _))   => HostLockLevel.Pending
    case Some(Value.DataV("LockExclusive", _)) => HostLockLevel.Exclusive
    case _                                     => HostLockLevel.None

  private def shmMode(args: List[Value], i: Int): HostShmMode = args.lift(i) match
    case Some(Value.DataV("ShmExclusiveLock", _))   => HostShmMode.ExclusiveLock
    case Some(Value.DataV("ShmSharedUnlock", _))    => HostShmMode.SharedUnlock
    case Some(Value.DataV("ShmExclusiveUnlock", _)) => HostShmMode.ExclusiveUnlock
    case _                                          => HostShmMode.SharedLock

  private def native(context: NativePluginContext, name: String)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, -1)(fn)

  // ── installation ──────────────────────────────────────────────────────────

  def install(context: NativePluginContext): Unit =
    // The `.ssc` reads these by NAME (`result.code`, `result.message`, `result.value` —
    // scljet/jvm-vfs.ssc:72), not only by pattern, so the field order must be declared.
    context.registerFields("JvmVfsResult", Vector("code", "message", "value"))
    context.registerFields("JvmVfsRead", Vector("bytes", "short"))

    native(context, "jvmVfsFullPath") { a =>
      string(SclJetJvmVfsHost.fullPath(argString(a, 0, "jvmVfsFullPath")))
    }
    native(context, "jvmVfsOpen") { a =>
      integer(SclJetJvmVfsHost.open(
        argString(a, 0, "jvmVfsOpen"), argBool(a, 1, "jvmVfsOpen"), argBool(a, 2, "jvmVfsOpen")))
    }
    native(context, "jvmVfsDelete") { a =>
      unit(SclJetJvmVfsHost.delete(argString(a, 0, "jvmVfsDelete")))
    }
    native(context, "jvmVfsExists") { a =>
      bool(SclJetJvmVfsHost.exists(argString(a, 0, "jvmVfsExists")))
    }
    native(context, "jvmVfsReadAt") { a =>
      val read = SclJetJvmVfsHost.readAt(
        argLong(a, 0, "jvmVfsReadAt"), argLong(a, 1, "jvmVfsReadAt"), argInt(a, 2, "jvmVfsReadAt"))
      result(read)(r => Value.DataV("JvmVfsRead", Vector(
        list(r.bytes.iterator.map(b => Value.IntV((b & 0xff).toLong))),
        Value.BoolV(r.short))))
    }
    native(context, "jvmVfsWriteAt") { a =>
      integer32(SclJetJvmVfsHost.writeAt(
        argLong(a, 0, "jvmVfsWriteAt"), argLong(a, 1, "jvmVfsWriteAt"), argBytes(a, 2, "jvmVfsWriteAt")))
    }
    native(context, "jvmVfsTruncate") { a =>
      unit(SclJetJvmVfsHost.truncate(argLong(a, 0, "jvmVfsTruncate"), argLong(a, 1, "jvmVfsTruncate")))
    }
    native(context, "jvmVfsSync") { a =>
      unit(SclJetJvmVfsHost.sync(argLong(a, 0, "jvmVfsSync"), argBool(a, 1, "jvmVfsSync")))
    }
    native(context, "jvmVfsSize") { a =>
      integer(SclJetJvmVfsHost.size(argLong(a, 0, "jvmVfsSize")))
    }
    native(context, "jvmVfsLock") { a =>
      unit(SclJetJvmVfsHost.lock(argLong(a, 0, "jvmVfsLock"), lockLevel(a, 1)))
    }
    native(context, "jvmVfsUnlock") { a =>
      unit(SclJetJvmVfsHost.unlock(argLong(a, 0, "jvmVfsUnlock"), lockLevel(a, 1)))
    }
    native(context, "jvmVfsCheckReservedLock") { a =>
      bool(SclJetJvmVfsHost.checkReserved(argLong(a, 0, "jvmVfsCheckReservedLock")))
    }
    native(context, "jvmVfsShmMap") { a =>
      integer32(SclJetJvmVfsHost.shmMap(
        argLong(a, 0, "jvmVfsShmMap"), argInt(a, 1, "jvmVfsShmMap"),
        argInt(a, 2, "jvmVfsShmMap"), argBool(a, 3, "jvmVfsShmMap")))
    }
    native(context, "jvmVfsShmRead") { a =>
      byteList(SclJetJvmVfsHost.shmRead(
        argLong(a, 0, "jvmVfsShmRead"), argInt(a, 1, "jvmVfsShmRead"),
        argInt(a, 2, "jvmVfsShmRead"), argInt(a, 3, "jvmVfsShmRead")))
    }
    native(context, "jvmVfsShmWrite") { a =>
      unit(SclJetJvmVfsHost.shmWrite(
        argLong(a, 0, "jvmVfsShmWrite"), argInt(a, 1, "jvmVfsShmWrite"),
        argInt(a, 2, "jvmVfsShmWrite"), argBytes(a, 3, "jvmVfsShmWrite")))
    }
    native(context, "jvmVfsShmLock") { a =>
      unit(SclJetJvmVfsHost.shmLock(
        argLong(a, 0, "jvmVfsShmLock"), argInt(a, 1, "jvmVfsShmLock"),
        argInt(a, 2, "jvmVfsShmLock"), shmMode(a, 3)))
    }
    native(context, "jvmVfsShmBarrier") { a =>
      unit(SclJetJvmVfsHost.shmBarrier(argLong(a, 0, "jvmVfsShmBarrier")))
    }
    native(context, "jvmVfsShmUnmap") { a =>
      unit(SclJetJvmVfsHost.shmUnmap(argLong(a, 0, "jvmVfsShmUnmap"), argBool(a, 1, "jvmVfsShmUnmap")))
    }
    native(context, "jvmVfsSectorSize") { a =>
      integer32(SclJetJvmVfsHost.sectorSize(argLong(a, 0, "jvmVfsSectorSize")))
    }
    native(context, "jvmVfsDeviceCharacteristics") { a =>
      strings(SclJetJvmVfsHost.deviceCharacteristics(argLong(a, 0, "jvmVfsDeviceCharacteristics")))
    }
    native(context, "jvmVfsClose") { a =>
      unit(SclJetJvmVfsHost.close(argLong(a, 0, "jvmVfsClose")))
    }
