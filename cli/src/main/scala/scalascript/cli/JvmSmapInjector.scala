package scalascript.cli

import org.objectweb.asm.{
  ClassReader, ClassWriter, ClassVisitor, Opcodes, Attribute, ByteVector
}

/** Inject a JSR-45 SMAP into the `SourceDebugExtension` attribute of each
 *  `.class` file under a given directory.
 *
 *  After this rewrite, stack-trace tools (including the standard JVM,
 *  IntelliJ, and `jdb`) recognise the SMAP and rewrite line numbers in
 *  back-traces from the compiled-Scala line numbers stored in the
 *  `LineNumberTable` to the .ssc source line numbers the user actually
 *  wrote.  No SOURCE attribute changes are needed — the SMAP carries its
 *  own file name.
 *
 *  Reference: [JSR-45 §3.4 — `SourceDebugExtension`](https://www.jcp.org/en/jsr/detail?id=45).
 *
 *  ### Implementation notes
 *
 *  ASM ships a built-in helper for `SourceDebugExtension` (via
 *  `ClassVisitor.visitSource(source, debug)`) but it has two awkward
 *  properties that bit us:
 *
 *    1. It interleaves SOURCE rewrites with SMAP injection — if a user
 *       only wants SMAP we'd have to round-trip the existing SourceFile
 *       through `visitSource`, which is fragile when the existing value
 *       contains non-ASCII bytes.
 *    2. The helper splits the SMAP into UTF-8 chunks at semi-arbitrary
 *       boundaries when the string is "long" (definitions vary by ASM
 *       release).  Most JVMs accept the chunked form but some tools
 *       (notably the IntelliJ debugger before 2022.1) tripped on it.
 *
 *  We sidestep both by writing a custom [[Attribute]] that emits the
 *  SMAP's UTF-8 bytes verbatim, NO length prefix (ASM's own attribute
 *  framework handles the `attribute_length` u4 prefix in the class-file
 *  format).  The resulting `SourceDebugExtension` is the exact byte
 *  stream the JVM spec mandates: `u2 attribute_name_index; u4
 *  attribute_length; u1 debug_extension[attribute_length]` where
 *  `debug_extension` is the modified-UTF-8 encoding of the SMAP string.
 *
 *  We emit UTF-8 (not modified-UTF-8) for simplicity: the SMAP we
 *  produce is pure ASCII, and the two encodings agree byte-for-byte on
 *  the ASCII subset.
 *
 *  v2.0 Phase 4 (Option A). */
object JvmSmapInjector:

  /** Custom ASM Attribute that emits a raw byte payload as the body of
   *  the `SourceDebugExtension` attribute.  ASM wraps the payload with
   *  the standard `u2 name_index, u4 attribute_length` header at write
   *  time, so the body is just the SMAP's UTF-8 bytes.
   *
   *  We mark the attribute as `unknown` to ASM (we don't extend its
   *  knowledge of well-known attribute names), but that does NOT prevent
   *  it from being emitted — `unknown` only affects how ASM treats the
   *  attribute on READ (it would skip it instead of decoding).  Writing
   *  always honours `visitAttribute`. */
  final class SmapAttribute(smap: String)
      extends Attribute("SourceDebugExtension"):

    override def isUnknown: Boolean = true
    override def isCodeAttribute: Boolean = false

    /** Emit the SMAP bytes into the class-file's attribute area.
     *
     *  `write` is called by ASM during class serialisation; we ignore
     *  the `classWriter` (no constant-pool references), `code` /
     *  `maxStack` / `maxLocals` (this is a class-level attribute, not
     *  a Code attribute), and just append the SMAP's UTF-8 bytes. */
    override def write(
        classWriter: ClassWriter,
        code:        Array[Byte],
        codeLength:  Int,
        maxStack:    Int,
        maxLocals:   Int
    ): ByteVector =
      val bv    = new ByteVector()
      val bytes = smap.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      bv.putByteArray(bytes, 0, bytes.length)
      bv

  /** Walk `classDir` recursively, rewrite each `.class` file in place with
   *  a `SourceDebugExtension` attribute holding `smap`.  Existing
   *  `SourceDebugExtension` attributes are replaced.
   *
   *  Class files with no `LineNumberTable` are still rewritten — the SMAP
   *  is harmless on a class with no line info — so we don't have to
   *  inspect each class first.
   *
   *  @param classDir absolute path to a directory of extracted `.class`
   *                  files (FQN-shaped layout, e.g. produced by
   *                  [[JvmBytecode.extractBundleTo]]).
   *  @param smap     the SMAP text built via [[JvmSmap.build]]. */
  def injectAll(classDir: os.Path, smap: String): Unit =
    if !os.exists(classDir) then return
    for classFile <- os.walk(classDir).filter(p =>
          os.isFile(p) && p.last.endsWith(".class")) do
      val bytes = os.read.bytes(classFile)
      val out   = injectInto(bytes, smap)
      os.write.over(classFile, out)

  /** Inject `smap` into a single class-file byte array.  Exposed so unit
   *  tests can validate the rewrite without spinning up a temp dir. */
  def injectInto(classBytes: Array[Byte], smap: String): Array[Byte] =
    val reader = new ClassReader(classBytes)
    // Pass `reader` to `ClassWriter` so it can reuse the constant pool
    // unchanged when no method bodies need re-encoding — but we're not
    // touching any code, so the constant pool stays identical and the
    // copy is essentially zero-cost.
    val writer = new ClassWriter(reader, 0)
    val visitor: ClassVisitor = new ClassVisitor(Opcodes.ASM9, writer):

      // Suppress any existing `SourceDebugExtension` the upstream
      // compiler may have emitted — ASM exposes it via visitSource's
      // second parameter (a String).  Pass `null` for the second
      // argument so the writer drops the prior value; we'll inject our
      // own via visitAttribute.
      override def visitSource(source: String, debug: String): Unit =
        super.visitSource(source, null)

      // Inject the SMAP just before the visitor's end-of-class signal,
      // so it appears alongside other class-level attributes.  The ASM
      // ClassWriter doesn't care about attribute order — the JVM spec
      // is order-agnostic too — but emitting late keeps the class-file
      // hex dumps human-readable (SOURCE first, debug-extension last).
      override def visitEnd(): Unit =
        super.visitAttribute(new SmapAttribute(smap))
        super.visitEnd()

    reader.accept(visitor, 0)
    writer.toByteArray

  /** Read the (UTF-8-decoded) `SourceDebugExtension` attribute body from a
   *  `.class` file byte array, or `None` if the class has no such
   *  attribute.  Used by tests to validate that [[injectInto]] actually
   *  wrote what we asked it to.
   *
   *  Implementation walks the class-file structure manually — ASM's
   *  visitor API does not expose unknown attributes through the
   *  ClassReader by default (we mark our Attribute as `unknown`, so the
   *  reader would skip it on re-read).  The manual scan is short
   *  enough that pulling a dep just for it isn't justified. */
  def readSourceDebugExtension(classBytes: Array[Byte]): Option[String] =
    import java.io.{ByteArrayInputStream, DataInputStream}
    val dis = new DataInputStream(new ByteArrayInputStream(classBytes))
    try
      // ClassFile header — see JVMS §4.1.
      val magic = dis.readInt()
      if magic != 0xCAFEBABE then return None
      dis.readUnsignedShort() // minor_version
      dis.readUnsignedShort() // major_version
      val cpCount = dis.readUnsignedShort()
      // Constant pool — keep a String per CONSTANT_Utf8 entry; other
      // tags we only need to skip past.
      val cpStrings = new Array[String](cpCount)
      var i = 1
      while i < cpCount do
        val tag = dis.readUnsignedByte()
        tag match
          case 1 => // CONSTANT_Utf8
            val len = dis.readUnsignedShort()
            val buf = new Array[Byte](len)
            dis.readFully(buf)
            cpStrings(i) = new String(buf, "UTF-8")
            i += 1
          case 3 | 4 => // Integer | Float
            dis.readInt(); i += 1
          case 5 | 6 => // Long | Double (occupy 2 entries per JVMS §4.4.5)
            dis.readLong(); i += 2
          case 7 | 8 | 16 | 19 | 20 => // Class | String | MethodType | Module | Package
            dis.readUnsignedShort(); i += 1
          case 9 | 10 | 11 | 12 | 17 | 18 => // FieldRef | MethodRef | IfaceMethodRef | NameAndType | Dynamic | InvokeDynamic
            dis.readInt(); i += 1
          case 15 => // MethodHandle
            dis.readUnsignedByte(); dis.readUnsignedShort(); i += 1
          case _ =>
            return None // unknown tag — bail
      // Skip access_flags, this_class, super_class, interfaces, fields, methods.
      dis.readUnsignedShort() // access_flags
      dis.readUnsignedShort() // this_class
      dis.readUnsignedShort() // super_class
      val ifCount = dis.readUnsignedShort()
      var k = 0; while k < ifCount do { dis.readUnsignedShort(); k += 1 }
      // Skip fields[].  Each field has its own attributes table, which
      // we just bytewise-skip via the attribute_length prefix.
      def skipAttributes(): Unit =
        val attrCount = dis.readUnsignedShort()
        var j = 0
        while j < attrCount do
          dis.readUnsignedShort() // attribute_name_index
          val len = dis.readInt()
          dis.skipBytes(len)
          j += 1
      def skipMembers(): Unit =
        val n = dis.readUnsignedShort()
        var j = 0
        while j < n do
          dis.readUnsignedShort() // access_flags
          dis.readUnsignedShort() // name_index
          dis.readUnsignedShort() // descriptor_index
          skipAttributes()
          j += 1
      skipMembers() // fields
      skipMembers() // methods
      // Class-level attributes — what we're after.
      val attrCount = dis.readUnsignedShort()
      var j = 0
      while j < attrCount do
        val nameIdx = dis.readUnsignedShort()
        val len     = dis.readInt()
        val name    = cpStrings(nameIdx)
        if name == "SourceDebugExtension" then
          val buf = new Array[Byte](len)
          dis.readFully(buf)
          return Some(new String(buf, "UTF-8"))
        else
          dis.skipBytes(len)
        j += 1
      None
    finally dis.close()
