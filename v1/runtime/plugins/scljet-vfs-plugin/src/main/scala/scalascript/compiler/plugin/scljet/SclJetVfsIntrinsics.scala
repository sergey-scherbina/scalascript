package scalascript.compiler.plugin.scljet

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

object SclJetVfsIntrinsics:

  private def native(f: List[PluginValue] => PluginValue): NativeImpl =
    PluginNative.eval((_, args) => PluginComputation.pure(f(args)))

  private def result[A](value: HostResult[A])(convert: A => PluginValue): PluginValue =
    PluginValue.orderedInstance("JvmVfsResult", Seq(
      "code" -> PluginValue.string(value.code),
      "message" -> PluginValue.string(value.message),
      "value" -> value.value.map(convert).getOrElse(PluginValue.unit),
    ))

  private def unit(value: HostResult[Unit]): PluginValue = result(value)(_ => PluginValue.unit)
  private def integer(value: HostResult[Long]): PluginValue = result(value)(PluginValue.int)
  private def integer32(value: HostResult[Int]): PluginValue = result(value)(n => PluginValue.int(n.toLong))
  private def bool(value: HostResult[Boolean]): PluginValue = result(value)(PluginValue.bool)
  private def string(value: HostResult[String]): PluginValue = result(value)(PluginValue.string)
  private def bytes(value: HostResult[Array[Byte]]): PluginValue = result(value)(array =>
    PluginValue.list(array.iterator.map(b => PluginValue.int((b & 0xff).toLong)).toList))
  private def strings(value: HostResult[List[String]]): PluginValue = result(value)(values =>
    PluginValue.list(values.map(PluginValue.string)))

  private def argLong(value: PluginValue): Long = value.asInt.getOrElse(-1L)
  private def argInt(value: PluginValue): Int = argLong(value).toInt
  private def argString(value: PluginValue): String = value.asString.getOrElse("")
  private def argBool(value: PluginValue): Boolean = value.asBool.getOrElse(false)
  private def argBytes(value: PluginValue): Array[Byte] = value.asList.getOrElse(Nil).map(v => argInt(v).toByte).toArray

  private def lockLevel(value: PluginValue): HostLockLevel = value.typeNameOf.getOrElse(value.show) match
    case "LockShared"    => HostLockLevel.Shared
    case "LockReserved"  => HostLockLevel.Reserved
    case "LockPending"   => HostLockLevel.Pending
    case "LockExclusive" => HostLockLevel.Exclusive
    case _               => HostLockLevel.None

  private def shmMode(value: PluginValue): HostShmMode = value.typeNameOf.getOrElse(value.show) match
    case "ShmExclusiveLock"   => HostShmMode.ExclusiveLock
    case "ShmSharedUnlock"    => HostShmMode.SharedUnlock
    case "ShmExclusiveUnlock" => HostShmMode.ExclusiveUnlock
    case _                    => HostShmMode.SharedLock

  val table: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("jvmVfsFullPath") -> native { case List(path) => string(SclJetJvmVfsHost.fullPath(argString(path))); case _ => string(HostResult.fail("misuse", "fullPath arguments")) },
    QualifiedName("jvmVfsOpen") -> native { case List(path, ro, create) => integer(SclJetJvmVfsHost.open(argString(path), argBool(ro), argBool(create))); case _ => integer(HostResult.fail("misuse", "open arguments")) },
    QualifiedName("jvmVfsDelete") -> native { case List(path) => unit(SclJetJvmVfsHost.delete(argString(path))); case _ => unit(HostResult.fail("misuse", "delete arguments")) },
    QualifiedName("jvmVfsExists") -> native { case List(path) => bool(SclJetJvmVfsHost.exists(argString(path))); case _ => bool(HostResult.fail("misuse", "exists arguments")) },
    QualifiedName("jvmVfsReadAt") -> native { case List(handle, offset, length) =>
      val read = SclJetJvmVfsHost.readAt(argLong(handle), argLong(offset), argInt(length))
      result(read)(r => PluginValue.orderedInstance("JvmVfsRead", Seq(
        "bytes" -> PluginValue.list(r.bytes.iterator.map(b => PluginValue.int((b & 0xff).toLong)).toList),
        "short" -> PluginValue.bool(r.short))))
      case _ => result(HostResult.fail("misuse", "readAt arguments"))(_ => PluginValue.unit) },
    QualifiedName("jvmVfsWriteAt") -> native { case List(handle, offset, data) => integer32(SclJetJvmVfsHost.writeAt(argLong(handle), argLong(offset), argBytes(data))); case _ => integer32(HostResult.fail("misuse", "writeAt arguments")) },
    QualifiedName("jvmVfsTruncate") -> native { case List(handle, size) => unit(SclJetJvmVfsHost.truncate(argLong(handle), argLong(size))); case _ => unit(HostResult.fail("misuse", "truncate arguments")) },
    QualifiedName("jvmVfsSync") -> native { case List(handle, dataOnly) => unit(SclJetJvmVfsHost.sync(argLong(handle), argBool(dataOnly))); case _ => unit(HostResult.fail("misuse", "sync arguments")) },
    QualifiedName("jvmVfsSize") -> native { case List(handle) => integer(SclJetJvmVfsHost.size(argLong(handle))); case _ => integer(HostResult.fail("misuse", "size arguments")) },
    QualifiedName("jvmVfsLock") -> native { case List(handle, level) => unit(SclJetJvmVfsHost.lock(argLong(handle), lockLevel(level))); case _ => unit(HostResult.fail("misuse", "lock arguments")) },
    QualifiedName("jvmVfsUnlock") -> native { case List(handle, level) => unit(SclJetJvmVfsHost.unlock(argLong(handle), lockLevel(level))); case _ => unit(HostResult.fail("misuse", "unlock arguments")) },
    QualifiedName("jvmVfsCheckReservedLock") -> native { case List(handle) => bool(SclJetJvmVfsHost.checkReserved(argLong(handle))); case _ => bool(HostResult.fail("misuse", "checkReserved arguments")) },
    QualifiedName("jvmVfsShmMap") -> native { case List(handle, region, size, extend) => integer32(SclJetJvmVfsHost.shmMap(argLong(handle), argInt(region), argInt(size), argBool(extend))); case _ => integer32(HostResult.fail("misuse", "shmMap arguments")) },
    QualifiedName("jvmVfsShmRead") -> native { case List(handle, region, offset, length) => bytes(SclJetJvmVfsHost.shmRead(argLong(handle), argInt(region), argInt(offset), argInt(length))); case _ => bytes(HostResult.fail("misuse", "shmRead arguments")) },
    QualifiedName("jvmVfsShmWrite") -> native { case List(handle, region, offset, data) => unit(SclJetJvmVfsHost.shmWrite(argLong(handle), argInt(region), argInt(offset), argBytes(data))); case _ => unit(HostResult.fail("misuse", "shmWrite arguments")) },
    QualifiedName("jvmVfsShmLock") -> native { case List(handle, offset, count, mode) => unit(SclJetJvmVfsHost.shmLock(argLong(handle), argInt(offset), argInt(count), shmMode(mode))); case _ => unit(HostResult.fail("misuse", "shmLock arguments")) },
    QualifiedName("jvmVfsShmBarrier") -> native { case List(handle) => unit(SclJetJvmVfsHost.shmBarrier(argLong(handle))); case _ => unit(HostResult.fail("misuse", "shmBarrier arguments")) },
    QualifiedName("jvmVfsShmUnmap") -> native { case List(handle, delete) => unit(SclJetJvmVfsHost.shmUnmap(argLong(handle), argBool(delete))); case _ => unit(HostResult.fail("misuse", "shmUnmap arguments")) },
    QualifiedName("jvmVfsSectorSize") -> native { case List(handle) => integer32(SclJetJvmVfsHost.sectorSize(argLong(handle))); case _ => integer32(HostResult.fail("misuse", "sectorSize arguments")) },
    QualifiedName("jvmVfsDeviceCharacteristics") -> native { case List(handle) => strings(SclJetJvmVfsHost.deviceCharacteristics(argLong(handle))); case _ => strings(HostResult.fail("misuse", "deviceCharacteristics arguments")) },
    QualifiedName("jvmVfsClose") -> native { case List(handle) => unit(SclJetJvmVfsHost.close(argLong(handle))); case _ => unit(HostResult.fail("misuse", "close arguments")) },
  )
