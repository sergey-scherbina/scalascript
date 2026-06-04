package scalascript.ast

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** `.sscc` v3 — token-stream encoding.
 *
 *  Format layout:
 *  {{{
 *    bytes 0-3   magic           "sscc" = 0x73 0x73 0x63 0x63
 *    byte  4     version         = 0x03
 *    byte  5     compressionFlag = 0x00 (no outer compression for Phase A)
 *    bytes 6-9   crc32           big-endian CRC32 of payload (bytes 10+)
 *    bytes 10+   payload         = trieSectionLen(4) + trieBytes + streamBytes
 *  }}}
 *
 *  payload structure (raw, before any optional outer compression):
 *    trieSection:  4-byte big-endian length, then patricia-trie bytes (SsccV3Trie)
 *    streamSection: raw token stream bytes (self-delimiting via ModuleEnd sentinel)
 *
 *  All string values in the stream are referenced as varint IDs into the trie dictionary.
 *
 *  ── Phase A token vocabulary ──────────────────────────────────────────────
 *  For Phase A, code-block source is stored as a raw byte blob (not as a
 *  scalameta token stream — that comes in Phase B).  Manifest is stored as
 *  a v2-compatible ManifestPickle CBOR blob (to be replaced in Phase C).
 *
 *  Token stream grammar (EBNF-ish):
 *  {{{
 *    stream      ::= ModuleStart manifest? section* ModuleEnd
 *    manifest    ::= ManifestBlob
 *    section     ::= SectionStart content* section* SectionEnd
 *    content     ::= ProseBlock | CodeFence | ImportDecl | DataList
 *    ProseBlock  ::= ProseBlock(textRef, spanOpt)
 *    CodeFence   ::= CodeFenceStart(langRef, lineOffset, attrsCount, attr*) CodeFenceBlob CodeFenceEnd
 *    ImportDecl  ::= ImportDecl(pathRef, count, binding*)
 *    DataList    ::= ListStart itemBlock* ListEnd
 *    itemBlock   ::= ListItemStart itemBlock* ListItemEnd
 *  }}}
 */
private[ast] object SsccFormatV3:

  // ─── Token kinds ─────────────────────────────────────────────────────────

  private val KModuleStart  = 0
  private val KModuleEnd    = 1
  private val KManifestBlob = 2   // payload: 4-byte len + ManifestPickle CBOR bytes
  private val KSectionStart = 3   // payload: levelVarint, headingTextRef, headingSpanOpt, sectionSpanOpt
  private val KSectionEnd   = 4
  private val KProse        = 5   // payload: textRef, spanOpt
  private val KCodeStart    = 6   // payload: langRef, lineOffsetVarint, attrsCount, (keyRef,valRef)*, spanOpt
  private val KCodeBlob     = 7   // payload: 4-byte source-len + UTF-8 source bytes
  private val KCodeEnd      = 8   // payload: hasErrByte, [msgRef, lineV, colV, snippetRef]
  private val KImport       = 9   // payload: pathRef, bindingsCount, binding*(nameRef,hasAlias,aliasRef?,hasFrom,fromRef?), spanOpt
  private val KListStart    = 10  // payload: orderedByte, spanOpt
  private val KListItemStart = 11 // payload: contentRef, spanOpt
  private val KListItemEnd  = 12
  private val KListEnd      = 13

  // Span encoding: 0x00 = None, 0x01 startLine startCol startOffset endLine endCol endOffset (all varints)

  // ─── Writer ───────────────────────────────────────────────────────────────

  def write(module: Module, manifestBytes: Array[Byte]): Array[Byte] =
    val trie = new TrieBuilder

    // Pass 1: intern all strings that will be dict-referenced
    internModule(module, trie)

    // Pass 2: emit token stream
    val stream = new ByteArrayOutputStream()
    emitModule(module, manifestBytes, trie, stream)

    // Assemble payload: 4-byte trie length + trie bytes + stream bytes
    val trieBytes   = trie.serialize()
    val streamBytes = stream.toByteArray
    val payload     = new ByteArrayOutputStream()
    putBE32(payload, trieBytes.length)
    payload.write(trieBytes)
    payload.write(streamBytes)
    payload.toByteArray

  // Pass 1: recursively intern all strings
  private def internModule(m: Module, t: TrieBuilder): Unit =
    m.sections.foreach(internSection(_, t))

  private def internSection(s: Section, t: TrieBuilder): Unit =
    t.intern(s.heading.text)
    s.content.foreach(internContent(_, t))
    s.subsections.foreach(internSection(_, t))

  private def internContent(c: Content, t: TrieBuilder): Unit = c match
    case Content.Prose(text, _) =>
      t.intern(text)
    case Content.CodeBlock(lang, source, tree, _, parseError, _, attrs) =>
      t.intern(lang)
      attrs.foreach { (k, v) => t.intern(k); t.intern(v) }
      parseError.foreach { pe => t.intern(pe.message); t.intern(pe.snippet) }
      // source stored as raw blob, NOT in trie (large unique strings)
    case Content.Import(path, bindings, _) =>
      t.intern(path)
      bindings.foreach { b =>
        t.intern(b.name)
        b.alias.foreach(t.intern)
        b.fromModule.foreach(t.intern)
      }
    case Content.DataList(items, _, _) =>
      items.foreach(internItem(_, t))

  private def internItem(item: ListItem, t: TrieBuilder): Unit =
    t.intern(item.content)
    item.nested.foreach(internItem(_, t))

  // Pass 2: emit token stream
  private def emitModule(m: Module, manifestBytes: Array[Byte], t: TrieBuilder, out: ByteArrayOutputStream): Unit =
    putVarint(out, KModuleStart)
    putSpanOpt(out, m.span)

    // Manifest as raw CBOR blob (only written when non-empty)
    if manifestBytes.nonEmpty then
      putVarint(out, KManifestBlob)
      putBE32(out, manifestBytes.length)
      out.write(manifestBytes)

    m.sections.foreach(emitSection(_, t, out))

    putVarint(out, KModuleEnd)

  private def emitSection(s: Section, t: TrieBuilder, out: ByteArrayOutputStream): Unit =
    putVarint(out, KSectionStart)
    putVarint(out, s.heading.level)
    putVarint(out, t.intern(s.heading.text))
    putSpanOpt(out, s.heading.span)
    putSpanOpt(out, s.span)

    s.content.foreach(emitContent(_, t, out))
    s.subsections.foreach(emitSection(_, t, out))

    putVarint(out, KSectionEnd)

  private def emitContent(c: Content, t: TrieBuilder, out: ByteArrayOutputStream): Unit = c match
    case Content.Prose(text, span) =>
      putVarint(out, KProse)
      putVarint(out, t.intern(text))
      putSpanOpt(out, span)

    case Content.CodeBlock(lang, source, tree, span, parseError, lineOffset, attrs) =>
      putVarint(out, KCodeStart)
      putVarint(out, t.intern(lang))
      putVarint(out, lineOffset)
      putVarint(out, attrs.size)
      for (k, v) <- attrs do
        putVarint(out, t.intern(k))
        putVarint(out, t.intern(v))
      putSpanOpt(out, span)

      // Source as raw blob (not tokenised in Phase A)
      val src = tree match
        case Some(n) => ScalaNode.fold(n) { tree =>
          import scala.meta.XtensionSyntax
          tree.syntax
        }
        case None => source
      val srcBytes = src.getBytes(UTF_8)
      putVarint(out, KCodeBlob)
      putBE32(out, srcBytes.length)
      out.write(srcBytes)

      putVarint(out, KCodeEnd)
      parseError match
        case None => out.write(0)
        case Some(pe) =>
          out.write(1)
          putVarint(out, t.intern(pe.message))
          putVarint(out, pe.line)
          putVarint(out, pe.column)
          putVarint(out, t.intern(pe.snippet))

    case Content.Import(path, bindings, span) =>
      putVarint(out, KImport)
      putVarint(out, t.intern(path))
      putVarint(out, bindings.length)
      for b <- bindings do
        putVarint(out, t.intern(b.name))
        b.alias match
          case None    => out.write(0)
          case Some(a) => out.write(1); putVarint(out, t.intern(a))
        b.fromModule match
          case None    => out.write(0)
          case Some(f) => out.write(1); putVarint(out, t.intern(f))
        putSpanOpt(out, b.span)
      putSpanOpt(out, span)

    case Content.DataList(items, ordered, span) =>
      putVarint(out, KListStart)
      out.write(if ordered then 1 else 0)
      putSpanOpt(out, span)
      items.foreach(emitItem(_, t, out))
      putVarint(out, KListEnd)

  private def emitItem(item: ListItem, t: TrieBuilder, out: ByteArrayOutputStream): Unit =
    putVarint(out, KListItemStart)
    putVarint(out, t.intern(item.content))
    putSpanOpt(out, item.span)
    item.nested.foreach(emitItem(_, t, out))
    putVarint(out, KListItemEnd)

  // ─── Reader ───────────────────────────────────────────────────────────────

  /** `payloadBytes` is the raw payload (after header stripping and optional decompression).
   *  Returns `Right(module)` on success. */
  def read(payloadBytes: Array[Byte], manifestReader: Array[Byte] => Manifest): Either[String, Module] =
    scala.util.Try {
      val pos = Array(0)

      // Decode trie section
      val trieLen    = getBE32(payloadBytes, pos(0)); pos(0) += 4
      val trieEnd    = pos(0) + trieLen
      val dict       = TrieDecoder.decode(payloadBytes, pos)
      pos(0)         = trieEnd

      // Decode token stream
      readModule(payloadBytes, pos, dict, manifestReader)
    }.toEither.left.map(_.getMessage)

  private def readModule(bytes: Array[Byte], pos: Array[Int], dict: Array[String], manifestReader: Array[Byte] => Manifest): Module =
    expectKind(bytes, pos, KModuleStart)
    val moduleSpan = readSpanOpt(bytes, pos)

    // Expect ManifestBlob (optional — a module might have no manifest)
    var manifest: Option[Manifest] = None
    if peekKind(bytes, pos) == KManifestBlob then
      Varint.read(bytes, pos) // consume kind
      val mLen    = getBE32(bytes, pos(0)); pos(0) += 4
      val mBytes  = bytes.slice(pos(0), pos(0) + mLen); pos(0) += mLen
      manifest    = Some(manifestReader(mBytes))

    val sections = scala.collection.mutable.ArrayBuffer.empty[Section]
    while peekKind(bytes, pos) == KSectionStart do
      sections += readSection(bytes, pos, dict)

    expectKind(bytes, pos, KModuleEnd)
    Module(manifest, sections.toList, moduleSpan, sourceText = None)

  private def readSection(bytes: Array[Byte], pos: Array[Int], dict: Array[String]): Section =
    expectKind(bytes, pos, KSectionStart)
    val level       = Varint.read(bytes, pos).toInt
    val headingText = dict(Varint.read(bytes, pos).toInt)
    val headingSpan = readSpanOpt(bytes, pos)
    val sectionSpan = readSpanOpt(bytes, pos)
    val heading     = Heading(level, headingText, headingSpan)

    val content     = scala.collection.mutable.ArrayBuffer.empty[Content]
    val subsections = scala.collection.mutable.ArrayBuffer.empty[Section]

    var done = false
    while !done do
      peekKind(bytes, pos) match
        case KSectionEnd =>
          Varint.read(bytes, pos) // consume
          done = true
        case KSectionStart =>
          subsections += readSection(bytes, pos, dict)
        case _ =>
          content += readContent(bytes, pos, dict)

    Section(heading, content.toList, subsections.toList, sectionSpan)

  private def readContent(bytes: Array[Byte], pos: Array[Int], dict: Array[String]): Content =
    val kind = Varint.read(bytes, pos).toInt
    kind match
      case KProse =>
        val text = dict(Varint.read(bytes, pos).toInt)
        val span = readSpanOpt(bytes, pos)
        Content.Prose(text, span)

      case KCodeStart =>
        val lang        = dict(Varint.read(bytes, pos).toInt)
        val lineOffset  = Varint.read(bytes, pos).toInt
        val attrsCount  = Varint.read(bytes, pos).toInt
        val attrs       = scala.collection.mutable.Map.empty[String, String]
        for _ <- 0 until attrsCount do
          attrs(dict(Varint.read(bytes, pos).toInt)) = dict(Varint.read(bytes, pos).toInt)
        val span = readSpanOpt(bytes, pos)

        expectKind(bytes, pos, KCodeBlob)
        val srcLen   = getBE32(bytes, pos(0)); pos(0) += 4
        val srcBytes = bytes.slice(pos(0), pos(0) + srcLen); pos(0) += srcLen
        val src      = new String(srcBytes, UTF_8)

        expectKind(bytes, pos, KCodeEnd)
        val parseError: Option[CodeBlockParseError] =
          if (bytes(pos(0)) & 0xff) == 0 then { pos(0) += 1; None }
          else
            pos(0) += 1
            val msg     = dict(Varint.read(bytes, pos).toInt)
            val line    = Varint.read(bytes, pos).toInt
            val col     = Varint.read(bytes, pos).toInt
            val snippet = dict(Varint.read(bytes, pos).toInt)
            Some(CodeBlockParseError(msg, line, col, snippet))

        val tree =
          if Lang.isParseable(lang) then
            import scala.meta.*
            dialects.Scala3(Input.VirtualFile("<sscc>", src)).parse[Source] match
              case Parsed.Success(t) => Some(ScalaNode(t))
              case _                 => None
          else None

        Content.CodeBlock(lang, src, tree, span, parseError, lineOffset, attrs.toMap)

      case KImport =>
        val path  = dict(Varint.read(bytes, pos).toInt)
        val count = Varint.read(bytes, pos).toInt
        val bindings = (0 until count).map { _ =>
          val name       = dict(Varint.read(bytes, pos).toInt)
          val hasAlias   = (bytes(pos(0)) & 0xff); pos(0) += 1
          val alias      = if hasAlias == 1 then Some(dict(Varint.read(bytes, pos).toInt)) else None
          val hasFrom    = (bytes(pos(0)) & 0xff); pos(0) += 1
          val fromModule = if hasFrom == 1 then Some(dict(Varint.read(bytes, pos).toInt)) else None
          val bSpan      = readSpanOpt(bytes, pos)
          ImportBinding(name, alias, fromModule, bSpan)
        }.toList
        val span = readSpanOpt(bytes, pos)
        Content.Import(path, bindings, span)

      case KListStart =>
        val ordered = (bytes(pos(0)) & 0xff) != 0; pos(0) += 1
        val span    = readSpanOpt(bytes, pos)
        val items   = scala.collection.mutable.ArrayBuffer.empty[ListItem]
        while peekKind(bytes, pos) != KListEnd do
          items += readListItem(bytes, pos, dict)
        Varint.read(bytes, pos) // consume KListEnd
        Content.DataList(items.toList, ordered, span)

      case other =>
        throw new IllegalStateException(s"unexpected token kind $other at stream offset ${pos(0) - 1}")

  private def readListItem(bytes: Array[Byte], pos: Array[Int], dict: Array[String]): ListItem =
    expectKind(bytes, pos, KListItemStart)
    val content = dict(Varint.read(bytes, pos).toInt)
    val span    = readSpanOpt(bytes, pos)
    val nested  = scala.collection.mutable.ArrayBuffer.empty[ListItem]
    while peekKind(bytes, pos) != KListItemEnd do
      nested += readListItem(bytes, pos, dict)
    Varint.read(bytes, pos) // consume KListItemEnd
    ListItem(content, nested.toList, span)

  // ─── Stream helpers ───────────────────────────────────────────────────────

  private def putVarint(out: ByteArrayOutputStream, n: Int): Unit = Varint.write(out, n.toLong)

  private def putSpanOpt(out: ByteArrayOutputStream, span: Option[Span]): Unit =
    span match
      case None => out.write(0)
      case Some(Span(Position(sl, sc, so), Position(el, ec, eo))) =>
        out.write(1)
        putVarint(out, sl); putVarint(out, sc); putVarint(out, so)
        putVarint(out, el); putVarint(out, ec); putVarint(out, eo)

  private def readSpanOpt(bytes: Array[Byte], pos: Array[Int]): Option[Span] =
    val flag = bytes(pos(0)) & 0xff; pos(0) += 1
    if flag == 0 then None
    else
      val sl = Varint.read(bytes, pos).toInt
      val sc = Varint.read(bytes, pos).toInt
      val so = Varint.read(bytes, pos).toInt
      val el = Varint.read(bytes, pos).toInt
      val ec = Varint.read(bytes, pos).toInt
      val eo = Varint.read(bytes, pos).toInt
      Some(Span(Position(sl, sc, so), Position(el, ec, eo)))

  private def peekKind(bytes: Array[Byte], pos: Array[Int]): Int =
    // peek without advancing: read varint, note final pos, restore
    val saved = pos(0)
    val k     = Varint.read(bytes, pos).toInt
    pos(0)    = saved
    k

  // actually we need to peek then consume; peekKind only peeks
  private def expectKind(bytes: Array[Byte], pos: Array[Int], expected: Int): Unit =
    val k = Varint.read(bytes, pos).toInt
    if k != expected then throw new IllegalStateException(s"expected token kind $expected, got $k at offset ${pos(0)}")

  // ─── Binary helpers ───────────────────────────────────────────────────────

  private def putBE32(out: ByteArrayOutputStream, n: Int): Unit =
    out.write((n >>> 24) & 0xff)
    out.write((n >>> 16) & 0xff)
    out.write((n >>>  8) & 0xff)
    out.write( n         & 0xff)

  private def getBE32(bytes: Array[Byte], offset: Int): Int =
    ((bytes(offset)     & 0xff) << 24) |
    ((bytes(offset + 1) & 0xff) << 16) |
    ((bytes(offset + 2) & 0xff) <<  8) |
     (bytes(offset + 3) & 0xff)
