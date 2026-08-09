package scalascript.compiler.plugin.scljet

import java.io.IOException
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

enum HostLockLevel(val rank: Int):
  case None      extends HostLockLevel(0)
  case Shared    extends HostLockLevel(1)
  case Reserved  extends HostLockLevel(2)
  case Pending   extends HostLockLevel(3)
  case Exclusive extends HostLockLevel(4)

enum HostShmMode:
  case SharedLock, ExclusiveLock, SharedUnlock, ExclusiveUnlock

final case class HostResult[+A](code: String, message: String, value: Option[A]):
  def isOk: Boolean = code == "ok"

object HostResult:
  def ok[A](value: A): HostResult[A] = HostResult("ok", "", Some(value))
  val unit: HostResult[Unit] = ok(())
  def fail(code: String, message: String): HostResult[Nothing] = HostResult(code, message, None)

final case class HostRead(bytes: Array[Byte], short: Boolean)

private final class HostHandle(
    val id: Long,
    val path: Path,
    val channel: FileChannel,
    val readOnly: Boolean):
  val shmRegions = ConcurrentHashMap[Int, MappedByteBuffer]()

private object RollbackLocks:
  private val PendingByte = 1073741824L
  private val ReservedByte = 1073741825L
  private val SharedFirst = 1073741826L
  private val SharedSize = 510L

  private final class Entry(path: Path):
    private val channel = FileChannel.open(path,
      StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    private val levels = mutable.HashMap.empty[Long, HostLockLevel]
    private var sharedLock: FileLock | Null = null
    private var reservedLock: FileLock | Null = null
    private var reservedOwner: Option[Long] = None
    private var pendingLock: FileLock | Null = null
    private var pendingOwner: Option[Long] = None
    private var exclusiveLock: FileLock | Null = null
    private var exclusiveOwner: Option[Long] = None

    private def safeRelease(lock: FileLock | Null): Unit =
      if lock != null then
        try lock.release()
        catch case _: IOException => ()

    private def tryRegion(position: Long, size: Long, shared: Boolean): FileLock | Null =
      try channel.tryLock(position, size, shared)
      catch
        case _: OverlappingFileLockException => null
        case _: IOException                  => null

    private def otherShared(handle: Long): Boolean =
      levels.exists((id, level) => id != handle && level.rank >= HostLockLevel.Shared.rank)

    def register(handle: Long): Unit = synchronized:
      levels.getOrElseUpdate(handle, HostLockLevel.None)

    def level(handle: Long): HostLockLevel = synchronized:
      levels.getOrElse(handle, HostLockLevel.None)

    def hasReserved: Boolean = synchronized:
      reservedOwner.nonEmpty || pendingOwner.nonEmpty || exclusiveOwner.nonEmpty

    def acquire(handle: Long, target: HostLockLevel): Boolean = synchronized:
      val current = levels.getOrElse(handle, HostLockLevel.None)
      if target.rank < current.rank then false
      else if target == current then true
      else target match
        case HostLockLevel.None => true
        case HostLockLevel.Shared =>
          if pendingOwner.exists(_ != handle) || exclusiveOwner.exists(_ != handle) then false
          else
            if sharedLock == null && exclusiveLock == null then sharedLock = tryRegion(SharedFirst, SharedSize, true)
            if sharedLock == null && exclusiveLock == null then false
            else { levels(handle) = target; true }
        case HostLockLevel.Reserved =>
          if current.rank < HostLockLevel.Shared.rank || reservedOwner.exists(_ != handle) then false
          else
            if reservedLock == null then reservedLock = tryRegion(ReservedByte, 1L, false)
            if reservedLock == null then false
            else { reservedOwner = Some(handle); levels(handle) = target; true }
        case HostLockLevel.Pending =>
          if reservedOwner != Some(handle) || pendingOwner.exists(_ != handle) then false
          else
            if pendingLock == null then pendingLock = tryRegion(PendingByte, 1L, false)
            if pendingLock == null then false
            else { pendingOwner = Some(handle); levels(handle) = target; true }
        case HostLockLevel.Exclusive =>
          if reservedOwner != Some(handle) || otherShared(handle) || exclusiveOwner.exists(_ != handle) then false
          else
            if pendingLock == null then pendingLock = tryRegion(PendingByte, 1L, false)
            if pendingLock == null then false
            else
              pendingOwner = Some(handle)
              safeRelease(sharedLock); sharedLock = null
              exclusiveLock = tryRegion(SharedFirst, SharedSize, false)
              if exclusiveLock == null then
                sharedLock = tryRegion(SharedFirst, SharedSize, true)
                false
              else { exclusiveOwner = Some(handle); levels(handle) = target; true }

    def unlock(handle: Long, target: HostLockLevel): Boolean = synchronized:
      val current = levels.getOrElse(handle, HostLockLevel.None)
      if target.rank > current.rank then false
      else
        if current == HostLockLevel.Exclusive && target.rank < HostLockLevel.Exclusive.rank then
          safeRelease(exclusiveLock); exclusiveLock = null; exclusiveOwner = None
          if target.rank >= HostLockLevel.Shared.rank && sharedLock == null then
            sharedLock = tryRegion(SharedFirst, SharedSize, true)
            if sharedLock == null then return false
        if target.rank < HostLockLevel.Pending.rank && pendingOwner.contains(handle) then
          safeRelease(pendingLock); pendingLock = null; pendingOwner = None
        if target.rank < HostLockLevel.Reserved.rank && reservedOwner.contains(handle) then
          safeRelease(reservedLock); reservedLock = null; reservedOwner = None
        levels(handle) = target
        if target.rank < HostLockLevel.Shared.rank && !levels.values.exists(_.rank >= HostLockLevel.Shared.rank) then
          safeRelease(sharedLock); sharedLock = null
        true

    def unregister(handle: Long): Boolean = synchronized:
      unlock(handle, HostLockLevel.None)
      levels.remove(handle)
      levels.isEmpty

    def close(): Unit = synchronized:
      safeRelease(exclusiveLock); safeRelease(pendingLock); safeRelease(reservedLock); safeRelease(sharedLock)
      channel.close()

  private val entries = ConcurrentHashMap[Path, Entry]()

  private def entry(path: Path): Entry = entries.computeIfAbsent(path, p => Entry(p))
  def register(path: Path, handle: Long): Unit = entry(path).register(handle)
  def acquire(path: Path, handle: Long, level: HostLockLevel): Boolean = entry(path).acquire(handle, level)
  def unlock(path: Path, handle: Long, level: HostLockLevel): Boolean = entry(path).unlock(handle, level)
  def level(path: Path, handle: Long): HostLockLevel = entry(path).level(handle)
  def hasReserved(path: Path): Boolean = entry(path).hasReserved
  def unregister(path: Path, handle: Long): Unit =
    val e = entries.get(path)
    if e != null && e.unregister(handle) && entries.remove(path, e) then e.close()

private object ShmLocks:
  private val LockBase = 120L

  private final class ByteState:
    val sharedOwners = mutable.HashSet.empty[Long]
    var exclusiveOwner: Option[Long] = None
    var osLock: FileLock | Null = null

  private final class Entry(val path: Path):
    val channel: FileChannel = FileChannel.open(path,
      StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    val bytes: Array[ByteState] = Array.fill(8)(ByteState())
    val mappedOwners = mutable.HashSet.empty[Long]

    private def safeRelease(lock: FileLock | Null): Unit =
      if lock != null then try lock.release() catch case _: IOException => ()

    private def tryByte(index: Int, shared: Boolean): FileLock | Null =
      try channel.tryLock(LockBase + index, 1L, shared)
      catch
        case _: OverlappingFileLockException => null
        case _: IOException                  => null

    def map(handle: Long, region: Int, size: Int, extend: Boolean): HostResult[MappedByteBuffer] = synchronized:
      if region < 0 || size <= 0 then HostResult.fail("range", "invalid SHM region/size")
      else
        val position = region.toLong * 32768L
        val required = position + size.toLong
        if channel.size() < required && !extend then HostResult.fail("not-found", "SHM region does not exist")
        else
          if channel.size() < required then
            channel.position(required - 1L)
            channel.write(ByteBuffer.wrap(Array(0.toByte)))
          mappedOwners += handle
          HostResult.ok(channel.map(FileChannel.MapMode.READ_WRITE, position, size.toLong))

    def lock(handle: Long, offset: Int, count: Int, mode: HostShmMode): Boolean = synchronized:
      if offset < 0 || count <= 0 || offset + count > 8 then false
      else
        val range = offset until offset + count
        mode match
          case HostShmMode.SharedLock =>
            if range.exists(i => bytes(i).exclusiveOwner.exists(_ != handle)) then false
            else
              val acquired = mutable.ArrayBuffer.empty[Int]
              var ok = true
              var i = offset
              while i < offset + count && ok do
                val b = bytes(i)
                if b.sharedOwners.isEmpty && b.exclusiveOwner.isEmpty then
                  b.osLock = tryByte(i, true)
                  if b.osLock == null then
                    acquired.foreach(j => releaseShared(handle, j))
                    ok = false
                if ok then { b.sharedOwners += handle; acquired += i }
                i += 1
              ok
          case HostShmMode.ExclusiveLock =>
            if range.exists(i => bytes(i).exclusiveOwner.exists(_ != handle) || bytes(i).sharedOwners.exists(_ != handle)) then false
            else
              val acquired = mutable.ArrayBuffer.empty[Int]
              var ok = true
              var i = offset
              while i < offset + count && ok do
                val b = bytes(i)
                safeRelease(b.osLock); b.osLock = tryByte(i, false)
                if b.osLock == null then
                  acquired.foreach(j => releaseExclusive(handle, j))
                  ok = false
                if ok then { b.sharedOwners -= handle; b.exclusiveOwner = Some(handle); acquired += i }
                i += 1
              ok
          case HostShmMode.SharedUnlock => range.foreach(i => releaseShared(handle, i)); true
          case HostShmMode.ExclusiveUnlock => range.foreach(i => releaseExclusive(handle, i)); true

    private def releaseShared(handle: Long, index: Int): Unit =
      val b = bytes(index); b.sharedOwners -= handle
      if b.sharedOwners.isEmpty && b.exclusiveOwner.isEmpty then { safeRelease(b.osLock); b.osLock = null }

    private def releaseExclusive(handle: Long, index: Int): Unit =
      val b = bytes(index)
      if b.exclusiveOwner.contains(handle) then
        safeRelease(b.osLock); b.osLock = null; b.exclusiveOwner = None

    def unmap(handle: Long): Boolean = synchronized:
      (0 until 8).foreach { i => releaseShared(handle, i); releaseExclusive(handle, i) }
      mappedOwners -= handle
      mappedOwners.isEmpty

    def close(): Unit = synchronized:
      bytes.foreach(b => safeRelease(b.osLock))
      channel.close()

  private val entries = ConcurrentHashMap[Path, Entry]()
  private def entry(dbPath: Path): Entry =
    val shm = Paths.get(dbPath.toString + "-shm")
    entries.computeIfAbsent(dbPath, _ => Entry(shm))

  def map(dbPath: Path, handle: Long, region: Int, size: Int, extend: Boolean): HostResult[MappedByteBuffer] =
    entry(dbPath).map(handle, region, size, extend)
  def lock(dbPath: Path, handle: Long, offset: Int, count: Int, mode: HostShmMode): Boolean =
    entry(dbPath).lock(handle, offset, count, mode)
  def unmap(dbPath: Path, handle: Long, delete: Boolean): Unit =
    val e = entries.get(dbPath)
    if e != null && e.unmap(handle) && entries.remove(dbPath, e) then
      e.close()
      if delete then Files.deleteIfExists(e.path)

object SclJetJvmVfsHost:
  private val ids = AtomicLong(1L)
  private val handles = ConcurrentHashMap[Long, HostHandle]()

  private def bounded[A](body: => HostResult[A]): HostResult[A] =
    try body
    catch
      case e: SecurityException => HostResult.fail("permission", Option(e.getMessage).getOrElse("permission denied"))
      case e: IOException       => HostResult.fail("io", Option(e.getMessage).getOrElse("I/O failure"))
      case NonFatal(e)          => HostResult.fail("io", Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  def canonical(path: String): Path =
    val absolute = Paths.get(path).toAbsolutePath.normalize()
    if Files.exists(absolute) then absolute.toRealPath()
    else
      val parent = Option(absolute.getParent).getOrElse(Paths.get(".").toAbsolutePath.normalize())
      val realParent = if Files.exists(parent) then parent.toRealPath() else parent
      realParent.resolve(absolute.getFileName).normalize()

  def fullPath(path: String): HostResult[String] = bounded(HostResult.ok(canonical(path).toString))

  def open(path: String, readOnly: Boolean, create: Boolean): HostResult[Long] = bounded:
    val canonicalPath = canonical(path)
    if !create && !Files.exists(canonicalPath) then HostResult.fail("not-found", "file does not exist")
    else
      val options = mutable.ArrayBuffer[StandardOpenOption](StandardOpenOption.READ)
      if !readOnly then options += StandardOpenOption.WRITE
      if create then options += StandardOpenOption.CREATE
      val channel = FileChannel.open(canonicalPath, options.toSeq*)
      val id = ids.getAndIncrement()
      handles.put(id, HostHandle(id, canonicalPath, channel, readOnly))
      RollbackLocks.register(canonicalPath, id)
      HostResult.ok(id)

  private def handle(id: Long): HostResult[HostHandle] =
    Option(handles.get(id)).map(HostResult.ok).getOrElse(HostResult.fail("bad-handle", "unknown or closed handle"))

  def delete(path: String): HostResult[Unit] = bounded:
    Files.deleteIfExists(canonical(path)); HostResult.unit

  def exists(path: String): HostResult[Boolean] = bounded(HostResult.ok(Files.exists(canonical(path))))

  def readAt(id: Long, offset: Long, length: Int): HostResult[HostRead] = bounded:
    if offset < 0L || length < 0 then HostResult.fail("range", "negative read offset/length")
    else handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[HostRead]]
      case success =>
        val h = success.value.get
        val buffer = ByteBuffer.allocate(length)
        var position = offset
        var eof = false
        while buffer.hasRemaining && !eof do
          val n = h.channel.read(buffer, position)
          if n < 0 then eof = true else if n == 0 then eof = true else position += n
        val read = buffer.position()
        HostResult.ok(HostRead(buffer.array(), read < length))

  def writeAt(id: Long, offset: Long, bytes: Array[Byte]): HostResult[Int] = bounded:
    if offset < 0L then HostResult.fail("range", "negative write offset")
    else handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Int]]
      case success =>
        val h = success.value.get
        if h.readOnly then HostResult.fail("readonly", "handle is read-only")
        else
          val buffer = ByteBuffer.wrap(bytes)
          var position = offset
          while buffer.hasRemaining do
            val n = h.channel.write(buffer, position)
            if n <= 0 then throw IOException("positioned write made no progress")
            position += n
          HostResult.ok(bytes.length)

  def truncate(id: Long, size: Long): HostResult[Unit] = bounded:
    if size < 0L then HostResult.fail("range", "negative truncate size")
    else handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Unit]]
      case success => success.value.get.channel.truncate(size); HostResult.unit

  def sync(id: Long, dataOnly: Boolean): HostResult[Unit] = bounded:
    handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Unit]]
      case success => success.value.get.channel.force(!dataOnly); HostResult.unit

  def size(id: Long): HostResult[Long] = bounded:
    handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Long]]
      case success => HostResult.ok(success.value.get.channel.size())

  def lock(id: Long, level: HostLockLevel): HostResult[Unit] = bounded:
    handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Unit]]
      case success =>
        if RollbackLocks.acquire(success.value.get.path, id, level) then HostResult.unit
        else HostResult.fail("busy", s"cannot acquire $level lock")

  def unlock(id: Long, level: HostLockLevel): HostResult[Unit] = bounded:
    handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Unit]]
      case success =>
        if RollbackLocks.unlock(success.value.get.path, id, level) then HostResult.unit
        else HostResult.fail("misuse", s"cannot downgrade to $level")

  def checkReserved(id: Long): HostResult[Boolean] = bounded:
    handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Boolean]]
      case success => HostResult.ok(RollbackLocks.hasReserved(success.value.get.path))

  def shmMap(id: Long, region: Int, size: Int, extend: Boolean): HostResult[Int] = bounded:
    handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Int]]
      case success => ShmLocks.map(success.value.get.path, id, region, size, extend) match
        case failure if !failure.isOk => failure.asInstanceOf[HostResult[Int]]
        case mapped => success.value.get.shmRegions.put(region, mapped.value.get); HostResult.ok(size)

  def shmRead(id: Long, region: Int, offset: Int, length: Int): HostResult[Array[Byte]] = bounded:
    handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Array[Byte]]]
      case success =>
        val mapped = success.value.get.shmRegions.get(region)
        if mapped == null then HostResult.fail("not-found", "SHM region is not mapped")
        else if offset < 0 || length < 0 || offset + length > mapped.capacity() then HostResult.fail("range", "SHM read outside region")
        else
          val duplicate = mapped.duplicate(); duplicate.position(offset)
          val bytes = Array.ofDim[Byte](length); duplicate.get(bytes)
          HostResult.ok(bytes)

  def shmWrite(id: Long, region: Int, offset: Int, bytes: Array[Byte]): HostResult[Unit] = bounded:
    handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Unit]]
      case success =>
        val mapped = success.value.get.shmRegions.get(region)
        if mapped == null then HostResult.fail("not-found", "SHM region is not mapped")
        else if offset < 0 || offset + bytes.length > mapped.capacity() then HostResult.fail("range", "SHM write outside region")
        else { val duplicate = mapped.duplicate(); duplicate.position(offset); duplicate.put(bytes); HostResult.unit }

  def shmLock(id: Long, offset: Int, count: Int, mode: HostShmMode): HostResult[Unit] = bounded:
    handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Unit]]
      case success =>
        if ShmLocks.lock(success.value.get.path, id, offset, count, mode) then HostResult.unit
        else HostResult.fail(if offset < 0 || count <= 0 || offset + count > 8 then "range" else "busy", "cannot change SHM lock")

  def shmBarrier(id: Long): HostResult[Unit] = bounded:
    handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Unit]]
      case success =>
        VarHandle.fullFence()
        success.value.get.shmRegions.values().asScala.foreach(_.force())
        HostResult.unit

  def shmUnmap(id: Long, delete: Boolean): HostResult[Unit] = bounded:
    handle(id) match
      case failure if !failure.isOk => failure.asInstanceOf[HostResult[Unit]]
      case success =>
        success.value.get.shmRegions.clear()
        ShmLocks.unmap(success.value.get.path, id, delete)
        HostResult.unit

  def close(id: Long): HostResult[Unit] = bounded:
    val h = handles.remove(id)
    if h == null then HostResult.fail("bad-handle", "unknown or closed handle")
    else
      h.shmRegions.clear(); ShmLocks.unmap(h.path, id, false); RollbackLocks.unregister(h.path, id); h.channel.close()
      HostResult.unit

  def sectorSize(id: Long): HostResult[Int] =
    if handles.containsKey(id) then HostResult.ok(4096) else HostResult.fail("bad-handle", "unknown or closed handle")

  def deviceCharacteristics(id: Long): HostResult[List[String]] =
    if handles.containsKey(id) then HostResult.ok(Nil) else HostResult.fail("bad-handle", "unknown or closed handle")
