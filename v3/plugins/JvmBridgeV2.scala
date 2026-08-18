package ssc3.plugins

/** THE SAME JVM CALL, FROM THE BRIDGE — v2's values instead of v3's.
  *
  * Lowering is SHARED, so `DatasetWirePartition(7, Vector())` becomes one `Prim` for both lanes and
  * both must answer it. Teaching only the executor is the divergence this repository has paid for
  * twice: the fleet's globals did it in August and `exec-gate` caught this one on its first run,
  * with the fixture passing as `7/0` on the executor and empty on the bridge.
  *
  * IT IS A SECOND CONVERTER AND NOT A SHARED ONE, deliberately: `JvmBridge` speaks `ssc3.Value` and
  * this speaks `ssc.Value`, and the two runtimes represent a datum differently — v3 numbers a
  * constructor and v2 names one. A single converter would need a translation between them at every
  * call, which is what `V2Fleet` already does for the fleet and is the more expensive of the two. */
object JvmBridgeV2:

  /** EVERY OBJECT IN THE DECLARED LIBRARIES, by its SIMPLE name.
    *
    * The bridge is a separate process and the IR reaching it carries `DatasetWirePartition`, not
    * `scalascript.typeddata.DatasetWirePartition` — the program wrote the short name and lowering
    * kept it. The executor resolves it because the loader told the provider which package was
    * imported; this process never saw that import.
    *
    * So the classpath is read instead, and it is the DECLARED one (`v3/.jars/jvm.cp`, written by
    * `v3/jvm-classpath.sh`) rather than the whole of it — the same list that decides what a program
    * may import in the first place, so the two lanes can only ever answer the same names.
    *
    * Scanning rather than enumerating a package: a class loader cannot list a package, and this is
    * one small library, walked once. */
  def companions(): Map[String, AnyRef] =
    val cpFile = java.nio.file.Paths.get(sys.props.getOrElse("ssc3.jvmcp", "v3/.jars/jvm.cp"))
    if !java.nio.file.Files.exists(cpFile) then Map.empty
    else
      val entries = new String(java.nio.file.Files.readAllBytes(cpFile), "UTF-8").split(":").toList
      val out = collection.mutable.HashMap.empty[String, AnyRef]
      entries.filter(_.nonEmpty).foreach { e =>
        val f = java.io.File(e)
        val names =
          if f.isDirectory then walk(f, f.getAbsolutePath.length + 1)
          else if e.endsWith(".jar") then jarNames(f)
          else Nil
        names.filter(_.endsWith("$")).foreach { fqn =>
          val simple = fqn.substring(fqn.lastIndexOf('.') + 1).dropRight(1)
          if !simple.contains("$") && !out.contains(simple) then
            try
              val c = Class.forName(fqn, false, getClass.getClassLoader)
              out(simple) = c.getField("MODULE$").get(null)
            catch case _: Throwable => ()
        }
      }
      out.toMap

  private def walk(dir: java.io.File, prefix: Int): List[String] =
    val here = Option(dir.listFiles).map(_.toList).getOrElse(Nil)
    here.flatMap { f =>
      if f.isDirectory then walk(f, prefix)
      else if f.getName.endsWith(".class") then
        List(f.getAbsolutePath.substring(prefix).dropRight(6).replace('/', '.'))
      else Nil
    }

  private def jarNames(f: java.io.File): List[String] =
    try
      val jar = java.util.jar.JarFile(f)
      try
        val out = collection.mutable.ListBuffer.empty[String]
        val en = jar.entries()
        while en.hasMoreElements do
          val n = en.nextElement().getName
          if n.endsWith(".class") then out += n.dropRight(6).replace('/', '.')
        out.toList
      finally jar.close()
    catch case _: Throwable => Nil

  def call(module: AnyRef, args: List[ssc.Value]): ssc.Value =
    val jargs = args.map(toJvm)
    val candidates = module.getClass.getMethods.filter(x =>
      x.getName == "apply" && x.getParameterCount == args.length)
    candidates.toList match
      case one :: Nil =>
        val fitted = one.getParameterTypes.toList.zip(jargs).map(fit)
        one.invoke(module, fitted*) match
          case r => fromJvm(r)
      case Nil => throw new RuntimeException(
        "no `apply` of " + args.length + " argument(s) on " + module.getClass.getName)
      case _ => throw new RuntimeException(
        "more than one `apply` of " + args.length + " argument(s) on " + module.getClass.getName)

  /** The declared parameter type decides, exactly as on the other side: one integer here, `Int` and
    * `Long` there, and only the signature says which was meant. */
  private def fit(pair: (Class[?], AnyRef)): AnyRef =
    val (want, got) = pair
    (want.getName, got) match
      case (("int" | "java.lang.Integer"), l: java.lang.Long)   => java.lang.Integer.valueOf(l.intValue)
      case (("short" | "java.lang.Short"), l: java.lang.Long)   => java.lang.Short.valueOf(l.shortValue)
      case (("byte" | "java.lang.Byte"), l: java.lang.Long)     => java.lang.Byte.valueOf(l.byteValue)
      case (("float" | "java.lang.Float"), d: java.lang.Double) => java.lang.Float.valueOf(d.floatValue)
      case (("double" | "java.lang.Double"), l: java.lang.Long) => java.lang.Double.valueOf(l.doubleValue)
      // AN ARRAY IS A MUTABLE BUFFER ON THIS SIDE. v2 has no array VALUE — `arr.new` answers
      // `ForeignV(ArrayBuffer)` — so a parameter asking for an immutable `Vector` or `Seq` gets a
      // buffer unless it is converted here, where the declared type is in hand. Measured: the
      // fixture reached `argument type mismatch` with `want=Vector got=ArrayBuffer`.
      case (w, b: scala.collection.mutable.Buffer[?])
          if w.startsWith("scala.collection.immutable.") || w == "scala.collection.Seq" =>
        b.toVector
      case _ => got

  private def toJvm(v: ssc.Value): AnyRef = v match
    case ssc.Value.IntV(n)   => java.lang.Long.valueOf(n)
    case ssc.Value.StrV(s)   => s
    case ssc.Value.BoolV(b)  => java.lang.Boolean.valueOf(b)
    case ssc.Value.FloatV(d) => java.lang.Double.valueOf(d)
    case ssc.Value.BytesV(b) => b.toArray
    case ssc.Value.UnitV     => scala.runtime.BoxedUnit.UNIT
    case ssc.Value.ForeignV(h) => h
    case d: ssc.Value.DataV  =>
      // A LIST crosses as a `Vector`, by TAG NAME — v2 names its constructors, so `Cons`/`Nil` is
      // the walk. Anything else is refused rather than smuggled.
      listOf(d) match
        case Some(xs) => xs.map(toJvm).toVector
        case None => throw new RuntimeException(
          "a JVM call was passed the constructor '" + d.tag + "', which this interop does not convert")
    case other => throw new RuntimeException(
      "a JVM call was passed " + other.getClass.getSimpleName + ", which this interop does not convert")

  private def listOf(v: ssc.Value): Option[List[ssc.Value]] =
    def go(x: ssc.Value, acc: List[ssc.Value]): Option[List[ssc.Value]] = x match
      case ssc.Value.DataV("Nil", _)  => Some(acc.reverse)
      case ssc.Value.DataV("Cons", fs) if fs.length == 2 => go(fs(1), fs(0) :: acc)
      case _ => None
    go(v, Nil)

  private def fromJvm(o: Any): ssc.Value = o match
    case null                       => ssc.Value.UnitV
    case s: String                  => ssc.Value.StrV(s)
    case i: java.lang.Integer       => ssc.Value.IntV(i.longValue)
    case l: java.lang.Long          => ssc.Value.IntV(l.longValue)
    case b: java.lang.Boolean       => ssc.Value.BoolV(b.booleanValue)
    case d: java.lang.Double        => ssc.Value.FloatV(d.doubleValue)
    case b: Array[Byte]             => ssc.Value.BytesV(b.toVector)
    case _: scala.runtime.BoxedUnit => ssc.Value.UnitV
    case xs: scala.collection.immutable.Seq[?] =>
      // v2 has no array value; a sequence is a LIST there, built from the same two constructors the
      // walk above reads.
      xs.foldRight[ssc.Value](ssc.Value.DataV("Nil", IndexedSeq.empty))((x, acc) =>
        ssc.Value.DataV("Cons", IndexedSeq(fromJvm(x), acc)))
    case p: Product =>
      // THE FIELD NAMES GO INTO v2's OWN TABLE, which is how a `DataV` gets `.partitionId` on this
      // side — the same `registerFieldNames` the ui provider uses. Registered as the value crosses,
      // the one moment both the names and the datum are in hand. Without it the bridge built the
      // object and then died on the field with v2's `was called but does not exist`.
      val names = p.productElementNames.toVector
      if names.nonEmpty then ssc.V2PluginRegistry.registerFieldNames(p.productPrefix, names)
      ssc.Value.DataV(p.productPrefix, p.productIterator.map(fromJvm).toIndexedSeq)
    case other: AnyRef => ssc.Value.ForeignV(other)
