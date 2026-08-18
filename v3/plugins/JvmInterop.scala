package ssc3.plugins

import ssc3.{Module, Plugins, Value}

/** JVM CLASSES, REACHED FROM v3 — and reached from OUTSIDE the kernel, like everything else that is
  * not the JDK.
  *
  * `std/mapreduce/distributed.ssc` writes `import scalascript.typeddata.{DatasetWire,
  * DatasetWirePartition}` — a Scala PACKAGE, not an `.ssc` module. v2 resolves such an import
  * through its interop descriptor; v3 refused it with four candidate FILE paths, which is a message
  * about a file that cannot exist. The owner decided v3 should admit these, and this is where the
  * admitting happens: `v3/plugins` is compiled beside the kernel and the kernel itself still
  * references nothing outside the JDK (invariant I-1).
  *
  * THE SURFACE IS WHAT THE PROGRAMS USE, not what the package contains. Read off
  * `distributed.ssc`: construct `DatasetWirePartition(partId, values)`, read `.partitionId` and
  * `.values`, call `DatasetWire.encodePartition` / `.decodePartition` on the object, and match the
  * `Either` that comes back. Generics, derived-schema macros and three wire codecs sit behind those
  * five points and are never named.
  *
  * A PACKAGE CANNOT BE ENUMERATED BY REFLECTION, so names resolve ONE AT A TIME through
  * `Plugins.canProvide` — the class loader already answers "does this name exist", and scanning the
  * classpath to pretend otherwise would be a second implementation of it. */
object JvmInterop:

  private var imported: List[String] = Nil

  def install(): Unit =
    Plugins.registerPackages(pkg => if probe(pkg) then { remember(pkg); true } else false)
    Plugins.registerNameResolver(name => imported.iterator.map(p => bind(p, name)).find(_.isDefined).flatten)

  private def remember(pkg: String): Unit =
    if !imported.contains(pkg) then imported = imported :+ pkg

  /** IS THIS A PACKAGE WE CAN SERVE? A package has no class of its own, so the question is answered
    * the only way a class loader can answer it: does ANY class live under that name. Scala compiles
    * a package object to `<pkg>.package$`, and a plain package still has its members — so a hit on
    * either is proof, and a miss on both leaves the import failing exactly as it did. */
  private def probe(pkg: String): Boolean =
    load(pkg + ".package$").isDefined || load(pkg + ".package").isDefined ||
      // No package object: try the names this repository actually imports, which is honest about
      // being a probe rather than an enumeration.
      List("DatasetWire", "DatasetWirePartition", "JsonValue")
        .exists(n => load(pkg + "." + n).isDefined || load(pkg + "." + n + "$").isDefined)

  private def load(fqn: String): Option[Class[?]] =
    try Some(Class.forName(fqn, false, getClass.getClassLoader))
    catch case _: Throwable => None

  /** A NAME INSIDE AN IMPORTED PACKAGE, as something callable.
    *
    * A Scala `object` compiles to `X$` with a `MODULE$` field, and a `case class` gets an `apply` on
    * its companion — so both spellings are the same lookup, and both answer a CALL. Anything else is
    * declined by returning `None`, which leaves v3's own refusal in place rather than replacing it
    * with a reflection error. */
  private def bind(pkg: String, name: String): Option[Plugins.Fn] =
    load(pkg + "." + name + "$").flatMap { c =>
      try
        val module = c.getField("MODULE$").get(null)
        Some((m: Module, args: List[Value]) => JvmBridge.callApply(m, module, args))
      catch case _: Throwable => None
    }
