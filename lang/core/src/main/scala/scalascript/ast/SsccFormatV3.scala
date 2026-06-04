package scalascript.ast

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** `.sscc` v3 — token-stream encoding.
 *
 *  Format layout:
 *  {{{
 *    bytes 0-3   magic           "sscc"
 *    byte  4     version         = 0x03
 *    byte  5     compressionFlag = 0x00 (no outer compression for Phase A/B)
 *    bytes 6-9   crc32           big-endian CRC32 of payload (bytes 10+)
 *    bytes 10+   payload         = trieSectionLen(4) + trieBytes + streamBytes
 *  }}}
 *
 *  payload structure:
 *    trieSection:   4-byte big-endian length, then patricia-trie bytes (SsccV3Trie)
 *    streamSection: raw token stream bytes (self-delimiting via ModuleEnd sentinel)
 *
 *  All string values in the stream are referenced as varint IDs into the trie dictionary.
 *
 *  ── Phase B token vocabulary (code blocks) ─────────────────────────────────
 *  For parseable code blocks, source is stored as a scalameta token stream
 *  (KCodeSmTokens). Non-parseable blocks use a raw byte blob (KCodeBlob).
 *  Preprocessor runs at write-time; tokens stored post-preprocess.
 *  On read, source is reconstructed from token texts; no preprocessor is called.
 *
 *  ── Sm token sub-stream ─────────────────────────────────────────────────────
 *  KCodeSmTokens is followed by: tokenCount(varint), then tokenCount × smToken.
 *  smToken = kindVarint, [dictRef varint if the kind is a dict-ref kind]
 *  Fixed-text tokens (keywords, common punct) emit kind only; text is implied.
 *  Variable-text tokens emit kind + dictRef (ident, string literal, comment, etc.).
 */
private[ast] object SsccFormatV3:

  // ─── Main stream token kinds ─────────────────────────────────────────────

  private val KModuleStart   = 0
  private val KModuleEnd     = 1
  private val KManifestBlob  = 2   // payload: 4-byte len + ManifestPickle CBOR bytes
  private val KSectionStart  = 3   // payload: levelVarint, headingTextRef, headingSpanOpt, sectionSpanOpt
  private val KSectionEnd    = 4
  private val KProse         = 5   // payload: textRef, spanOpt
  private val KCodeStart     = 6   // payload: langRef, lineOffsetVarint, attrsCount, (keyRef,valRef)*, spanOpt
  private val KCodeBlob      = 7   // payload: 4-byte source-len + UTF-8 source bytes
  private val KCodeEnd       = 8   // payload: hasErrByte, [msgRef, lineV, colV, snippetRef]
  private val KImport        = 9   // payload: pathRef, bindingsCount, binding*(nameRef,hasAlias,aliasRef?,hasFrom,fromRef?), spanOpt
  private val KListStart     = 10  // payload: orderedByte, spanOpt
  private val KListItemStart = 11  // payload: contentRef, spanOpt
  private val KListItemEnd   = 12
  private val KListEnd       = 13
  private val KCodeSmTokens  = 14  // payload: tokenCount(varint), then sm token sub-stream

  // Span encoding: 0x00 = None, 0x01 startLine startCol startOffset endLine endCol endOffset (all varints)

  // ─── Scalameta (sm) token kind constants ─────────────────────────────────
  // Range 0–119, numbered to keep frequent tokens < 128 (→ 1-byte varint each).
  // Null entries in SmFixedText ↔ dict-ref tokens (emit kind + dictRef varint).

  private object Sm:
    // dict-ref tokens (variable text):
    val Ident              = 0
    val ConstInt           = 15
    val ConstIntXL         = 41
    val ConstLong          = 47
    val ConstFloat         = 48
    val ConstFloatXL       = 49
    val ConstDouble        = 50
    val ConstString        = 16
    val ConstChar          = 51
    val ConstSymbol        = 52
    val Comment            = 17
    val CommentStart       = 109
    val CommentPart        = 110
    val CommentEnd         = 111
    val CommentUnquote     = 112
    val InterpolId         = 42
    val InterpolStart      = 43
    val InterpolPart       = 44
    val InterpolEnd        = 45
    val InterpolSpliceStart = 46
    val InterpolSpliceEnd  = 53
    val XmlStart           = 114
    val XmlPart            = 115
    val XmlEnd             = 116
    val XmlSpliceStart     = 117
    val XmlSpliceEnd       = 118
    val Symbolic           = 93
    val HSpace             = 94
    val HTrivia            = 113
    val EOL                = 95
    val LFLF               = 96
    val InfixLF            = 97
    val AtEOL              = 98
    val AtEOLorF           = 99
    val MultiEOL           = 100
    val MultiHS            = 101
    val MultiNL            = 102
    val MultiToken         = 103
    val Invalid            = 104
    val Shebang            = 105
    val Unquote            = 106
    val MacroQuote         = 107
    val MacroSplice        = 108
    val Unknown            = 119   // catch-all

    // fixed-text tokens (kind only, text implied):
    val Space              = 1
    val LF                 = 2
    val Dot                = 3
    val Comma              = 4
    val Colon              = 5
    val LeftParen          = 6
    val RightParen         = 7
    val LeftBrace          = 8
    val RightBrace         = 9
    val LeftBracket        = 10
    val RightBracket       = 11
    val KwDef              = 12
    val KwVal              = 13
    val KwVar              = 14
    val Equals             = 18
    val FunctionArrow      = 19
    val RightArrow         = 20
    val At                 = 21
    val Hash               = 22
    val Underscore         = 23
    val KwCase             = 24
    val KwClass            = 25
    val KwObject           = 26
    val KwTrait            = 27
    val KwIf               = 28
    val KwElse             = 29
    val KwMatch            = 30
    val KwReturn           = 31
    val KwNew              = 32
    val KwImport           = 33
    val KwType             = 34
    val KwExtends          = 35
    val KwFor              = 36
    val KwWith             = 37
    val EOF                = 38
    val BOF                = 39
    val Semicolon          = 40
    val Ellipsis           = 54
    val TypeLambdaArrow    = 55
    val ContextArrow       = 56
    val Subtype            = 57
    val Supertype          = 58
    val Viewbound          = 59
    val KwAbstract         = 60
    val KwCatch            = 61
    val KwDo               = 62
    val KwEnum             = 63
    val KwExport           = 64
    val KwFalse            = 65
    val KwFinal            = 66
    val KwFinally          = 67
    val KwForsome          = 68
    val KwGiven            = 69
    val KwImplicit         = 70
    val KwLazy             = 71
    val KwMacro            = 72
    val KwNull             = 73
    val KwOverride         = 74
    val KwPackage          = 75
    val KwPrivate          = 76
    val KwProtected        = 77
    val KwSealed           = 78
    val KwSuper            = 79
    val KwThen             = 80
    val KwThis             = 81
    val KwThrow            = 82
    val KwTrue             = 83
    val KwTry              = 84
    val KwWhile            = 85
    val KwYield            = 86
    val Tab                = 87
    val CR                 = 88
    val CRLF               = 89
    val FF                 = 90
    val Indent             = 91
    val Outdent            = 92

  // Fixed texts indexed by sm kind (null = dict-ref kind, must read dictRef varint)
  private val SmFixedText: Array[String] = {
    val a = new Array[String](120)
    a(Sm.Space)         = " "
    a(Sm.LF)            = "\n"
    a(Sm.Dot)           = "."
    a(Sm.Comma)         = ","
    a(Sm.Colon)         = ":"
    a(Sm.LeftParen)     = "("
    a(Sm.RightParen)    = ")"
    a(Sm.LeftBrace)     = "{"
    a(Sm.RightBrace)    = "}"
    a(Sm.LeftBracket)   = "["
    a(Sm.RightBracket)  = "]"
    a(Sm.KwDef)         = "def"
    a(Sm.KwVal)         = "val"
    a(Sm.KwVar)         = "var"
    a(Sm.Equals)        = "="
    a(Sm.FunctionArrow) = "=>"
    a(Sm.RightArrow)    = "<-"
    a(Sm.At)            = "@"
    a(Sm.Hash)          = "#"
    a(Sm.Underscore)    = "_"
    a(Sm.KwCase)        = "case"
    a(Sm.KwClass)       = "class"
    a(Sm.KwObject)      = "object"
    a(Sm.KwTrait)       = "trait"
    a(Sm.KwIf)          = "if"
    a(Sm.KwElse)        = "else"
    a(Sm.KwMatch)       = "match"
    a(Sm.KwReturn)      = "return"
    a(Sm.KwNew)         = "new"
    a(Sm.KwImport)      = "import"
    a(Sm.KwType)        = "type"
    a(Sm.KwExtends)     = "extends"
    a(Sm.KwFor)         = "for"
    a(Sm.KwWith)        = "with"
    a(Sm.EOF)           = ""
    a(Sm.BOF)           = ""
    a(Sm.Semicolon)     = ";"
    a(Sm.Ellipsis)      = "..."
    a(Sm.TypeLambdaArrow) = "=>>"
    a(Sm.ContextArrow)  = "?=>"
    a(Sm.Subtype)       = "<:"
    a(Sm.Supertype)     = ">:"
    a(Sm.Viewbound)     = "<%"
    a(Sm.KwAbstract)    = "abstract"
    a(Sm.KwCatch)       = "catch"
    a(Sm.KwDo)          = "do"
    a(Sm.KwEnum)        = "enum"
    a(Sm.KwExport)      = "export"
    a(Sm.KwFalse)       = "false"
    a(Sm.KwFinal)       = "final"
    a(Sm.KwFinally)     = "finally"
    a(Sm.KwForsome)     = "forSome"
    a(Sm.KwGiven)       = "given"
    a(Sm.KwImplicit)    = "implicit"
    a(Sm.KwLazy)        = "lazy"
    a(Sm.KwMacro)       = "macro"
    a(Sm.KwNull)        = "null"
    a(Sm.KwOverride)    = "override"
    a(Sm.KwPackage)     = "package"
    a(Sm.KwPrivate)     = "private"
    a(Sm.KwProtected)   = "protected"
    a(Sm.KwSealed)      = "sealed"
    a(Sm.KwSuper)       = "super"
    a(Sm.KwThen)        = "then"
    a(Sm.KwThis)        = "this"
    a(Sm.KwThrow)       = "throw"
    a(Sm.KwTrue)        = "true"
    a(Sm.KwTry)         = "try"
    a(Sm.KwWhile)       = "while"
    a(Sm.KwYield)       = "yield"
    a(Sm.Tab)           = "\t"
    a(Sm.CR)            = "\r"
    a(Sm.CRLF)          = "\r\n"
    a(Sm.FF)            = "\f"
    a(Sm.Indent)        = ""
    a(Sm.Outdent)       = ""
    a
  }

  // Class → Sm-kind lookup.  Keyed by concrete runtime Class so abstract supertypes
  // (T.Symbolic, T.HSpace, T.EOL, T.MultiToken) never appear as keys.
  // Private[meta] types (T.LFLF, T.InfixLF, T.Invalid, T.Unquote, T.MacroSplice,
  // T.Ellipsis) are absent; they fall to Sm.Unknown via getOrDefault.
  private val SmKindMap: java.util.HashMap[Class[?], java.lang.Integer] = {
    import scala.meta.tokens.{Token => T}
    val m = new java.util.HashMap[Class[?], java.lang.Integer](256)
    // variable-text tokens
    m.put(classOf[T.Ident],                    Sm.Ident)
    m.put(classOf[T.Constant.Int],             Sm.ConstInt)
    m.put(classOf[T.Constant.IntXL],           Sm.ConstIntXL)
    m.put(classOf[T.Constant.Long],            Sm.ConstLong)
    m.put(classOf[T.Constant.Float],           Sm.ConstFloat)
    m.put(classOf[T.Constant.FloatXL],         Sm.ConstFloatXL)
    m.put(classOf[T.Constant.Double],          Sm.ConstDouble)
    m.put(classOf[T.Constant.String],          Sm.ConstString)
    m.put(classOf[T.Constant.Char],            Sm.ConstChar)
    m.put(classOf[T.Constant.Symbol],          Sm.ConstSymbol)
    m.put(classOf[T.Comment],                  Sm.Comment)
    m.put(classOf[T.CommentStart],             Sm.CommentStart)
    m.put(classOf[T.CommentPart],              Sm.CommentPart)
    m.put(classOf[T.CommentEnd],               Sm.CommentEnd)
    m.put(classOf[T.CommentUnquote],           Sm.CommentUnquote)
    m.put(classOf[T.Interpolation.Id],         Sm.InterpolId)
    m.put(classOf[T.Interpolation.Start],      Sm.InterpolStart)
    m.put(classOf[T.Interpolation.Part],       Sm.InterpolPart)
    m.put(classOf[T.Interpolation.End],        Sm.InterpolEnd)
    m.put(classOf[T.Interpolation.SpliceStart],Sm.InterpolSpliceStart)
    m.put(classOf[T.Interpolation.SpliceEnd],  Sm.InterpolSpliceEnd)
    m.put(classOf[T.Xml.Start],                Sm.XmlStart)
    m.put(classOf[T.Xml.Part],                 Sm.XmlPart)
    m.put(classOf[T.Xml.End],                  Sm.XmlEnd)
    m.put(classOf[T.Xml.SpliceStart],          Sm.XmlSpliceStart)
    m.put(classOf[T.Xml.SpliceEnd],            Sm.XmlSpliceEnd)
    m.put(classOf[T.HTrivia],                  Sm.HTrivia)
    m.put(classOf[T.AtEOL],                    Sm.AtEOL)
    m.put(classOf[T.AtEOLorF],                 Sm.AtEOLorF)
    m.put(classOf[T.MultiEOL],                 Sm.MultiEOL)
    m.put(classOf[T.MultiHS],                  Sm.MultiHS)
    m.put(classOf[T.MultiNL],                  Sm.MultiNL)
    m.put(classOf[T.MultiToken],               Sm.MultiToken)
    m.put(classOf[T.Shebang],                  Sm.Shebang)
    m.put(classOf[T.MacroQuote],               Sm.MacroQuote)
    // fixed-text tokens
    m.put(classOf[T.BOF],                      Sm.BOF)
    m.put(classOf[T.EOF],                      Sm.EOF)
    m.put(classOf[T.Indentation.Indent],       Sm.Indent)
    m.put(classOf[T.Indentation.Outdent],      Sm.Outdent)
    m.put(classOf[T.Space],                    Sm.Space)
    m.put(classOf[T.Tab],                      Sm.Tab)
    m.put(classOf[T.LF],                       Sm.LF)
    m.put(classOf[T.CR],                       Sm.CR)
    m.put(classOf[T.CRLF],                     Sm.CRLF)
    m.put(classOf[T.FF],                       Sm.FF)
    m.put(classOf[T.At],                       Sm.At)
    m.put(classOf[T.Colon],                    Sm.Colon)
    m.put(classOf[T.Comma],                    Sm.Comma)
    m.put(classOf[T.Dot],                      Sm.Dot)
    m.put(classOf[T.Equals],                   Sm.Equals)
    m.put(classOf[T.FunctionArrow],            Sm.FunctionArrow)
    m.put(classOf[T.ContextArrow],             Sm.ContextArrow)
    m.put(classOf[T.Hash],                     Sm.Hash)
    m.put(classOf[T.LeftBrace],                Sm.LeftBrace)
    m.put(classOf[T.LeftBracket],              Sm.LeftBracket)
    m.put(classOf[T.LeftParen],                Sm.LeftParen)
    m.put(classOf[T.RightBrace],               Sm.RightBrace)
    m.put(classOf[T.RightBracket],             Sm.RightBracket)
    m.put(classOf[T.RightParen],               Sm.RightParen)
    m.put(classOf[T.Semicolon],                Sm.Semicolon)
    m.put(classOf[T.Subtype],                  Sm.Subtype)
    m.put(classOf[T.Supertype],                Sm.Supertype)
    m.put(classOf[T.RightArrow],               Sm.RightArrow)
    m.put(classOf[T.TypeLambdaArrow],          Sm.TypeLambdaArrow)
    m.put(classOf[T.Viewbound],                Sm.Viewbound)
    m.put(classOf[T.Underscore],               Sm.Underscore)
    m.put(classOf[T.KwAbstract],               Sm.KwAbstract)
    m.put(classOf[T.KwCase],                   Sm.KwCase)
    m.put(classOf[T.KwCatch],                  Sm.KwCatch)
    m.put(classOf[T.KwClass],                  Sm.KwClass)
    m.put(classOf[T.KwDef],                    Sm.KwDef)
    m.put(classOf[T.KwDo],                     Sm.KwDo)
    m.put(classOf[T.KwElse],                   Sm.KwElse)
    m.put(classOf[T.KwEnum],                   Sm.KwEnum)
    m.put(classOf[T.KwExport],                 Sm.KwExport)
    m.put(classOf[T.KwExtends],                Sm.KwExtends)
    m.put(classOf[T.KwFalse],                  Sm.KwFalse)
    m.put(classOf[T.KwFinal],                  Sm.KwFinal)
    m.put(classOf[T.KwFinally],                Sm.KwFinally)
    m.put(classOf[T.KwFor],                    Sm.KwFor)
    m.put(classOf[T.KwForsome],                Sm.KwForsome)
    m.put(classOf[T.KwGiven],                  Sm.KwGiven)
    m.put(classOf[T.KwIf],                     Sm.KwIf)
    m.put(classOf[T.KwImplicit],               Sm.KwImplicit)
    m.put(classOf[T.KwImport],                 Sm.KwImport)
    m.put(classOf[T.KwLazy],                   Sm.KwLazy)
    m.put(classOf[T.KwMacro],                  Sm.KwMacro)
    m.put(classOf[T.KwMatch],                  Sm.KwMatch)
    m.put(classOf[T.KwNew],                    Sm.KwNew)
    m.put(classOf[T.KwNull],                   Sm.KwNull)
    m.put(classOf[T.KwObject],                 Sm.KwObject)
    m.put(classOf[T.KwOverride],               Sm.KwOverride)
    m.put(classOf[T.KwPackage],                Sm.KwPackage)
    m.put(classOf[T.KwPrivate],                Sm.KwPrivate)
    m.put(classOf[T.KwProtected],              Sm.KwProtected)
    m.put(classOf[T.KwReturn],                 Sm.KwReturn)
    m.put(classOf[T.KwSealed],                 Sm.KwSealed)
    m.put(classOf[T.KwSuper],                  Sm.KwSuper)
    m.put(classOf[T.KwThen],                   Sm.KwThen)
    m.put(classOf[T.KwThis],                   Sm.KwThis)
    m.put(classOf[T.KwThrow],                  Sm.KwThrow)
    m.put(classOf[T.KwTrait],                  Sm.KwTrait)
    m.put(classOf[T.KwTrue],                   Sm.KwTrue)
    m.put(classOf[T.KwTry],                    Sm.KwTry)
    m.put(classOf[T.KwType],                   Sm.KwType)
    m.put(classOf[T.KwVal],                    Sm.KwVal)
    m.put(classOf[T.KwVar],                    Sm.KwVar)
    m.put(classOf[T.KwWhile],                  Sm.KwWhile)
    m.put(classOf[T.KwWith],                   Sm.KwWith)
    m.put(classOf[T.KwYield],                  Sm.KwYield)
    m
  }

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
    case Content.CodeBlock(lang, source, _, _, parseError, _, attrs) =>
      t.intern(lang)
      attrs.foreach { (k, v) => t.intern(k); t.intern(v) }
      parseError.foreach { pe => t.intern(pe.message); t.intern(pe.snippet) }
      if Lang.isParseable(lang) then
        internSmSource(source, t)
      // non-parseable: source stored as raw blob, no trie entries

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

  // Preprocess + tokenize rawSrc, intern all variable-text token texts into trie.
  // Named rawSrc/trie to avoid shadowing by `import scala.meta.*`.
  private def internSmSource(rawSrc: String, trie: TrieBuilder): Unit =
    import scalascript.parser.PreprocessorRegistry
    import scala.meta.*
    val preprocessed = PreprocessorRegistry.applyAll(rawSrc)
    dialects.Scala3(Input.VirtualFile("<sscc>", preprocessed)).tokenize match
      case Tokenized.Success(tokens) => internSmTokens(tokens, trie)
      case _ => // tokenization failed; blob path needs no trie entries

  private def internSmTokens(tokens: scala.meta.tokens.Tokens, trie: TrieBuilder): Unit =
    for tok <- tokens do
      val smKind = SmKindMap.getOrDefault(tok.getClass, Sm.Unknown)
      if SmFixedText(smKind) == null then trie.intern(tok.text)

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

    case Content.CodeBlock(lang, source, _, span, parseError, lineOffset, attrs) =>
      putVarint(out, KCodeStart)
      putVarint(out, t.intern(lang))
      putVarint(out, lineOffset)
      putVarint(out, attrs.size)
      for (k, v) <- attrs do
        putVarint(out, t.intern(k))
        putVarint(out, t.intern(v))
      putSpanOpt(out, span)

      if Lang.isParseable(lang) then
        emitSmSource(source, t, out)
      else
        val srcBytes = source.getBytes(UTF_8)
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

  // Preprocess + tokenize rawSrc, emit scalameta token sub-stream into out.
  // Named rawSrc/trie to avoid shadowing by `import scala.meta.*`.
  private def emitSmSource(rawSrc: String, trie: TrieBuilder, out: ByteArrayOutputStream): Unit =
    import scalascript.parser.PreprocessorRegistry
    import scala.meta.*
    val preprocessed = PreprocessorRegistry.applyAll(rawSrc)
    dialects.Scala3(Input.VirtualFile("<sscc>", preprocessed)).tokenize match
      case Tokenized.Success(tokens) =>
        putVarint(out, KCodeSmTokens)
        putVarint(out, tokens.length)
        for tok <- tokens do emitSmToken(tok, trie, out)
      case _ =>
        // Tokenization failed — fall back to raw blob
        val srcBytes = preprocessed.getBytes(UTF_8)
        putVarint(out, KCodeBlob)
        putBE32(out, srcBytes.length)
        out.write(srcBytes)

  private def emitSmToken(tok: scala.meta.tokens.Token, trie: TrieBuilder, out: ByteArrayOutputStream): Unit =
    val boxed  = SmKindMap.get(tok.getClass)
    val smKind = if boxed != null then boxed.intValue else Sm.Unknown
    putVarint(out, smKind)
    if SmFixedText(smKind) == null then putVarint(out, trie.intern(tok.text))

  // ─── Reader ───────────────────────────────────────────────────────────────

  /** `payloadBytes` is the raw payload (after header stripping and optional decompression).
   *  Returns `Right(module)` on success.  Trees are populated in parallel after decode. */
  def read(payloadBytes: Array[Byte], manifestReader: Array[Byte] => Manifest): Either[String, Module] =
    scala.util.Try {
      val m = decode(payloadBytes, manifestReader)
      populateTrees(m)
    }.toEither.left.map(_.getMessage)

  /** Decode-only: trie + token stream, `tree = None` for all code blocks.
   *  For benchmarking the irreducible I/O floor without scalameta parse. */
  def readNoTrees(payloadBytes: Array[Byte], manifestReader: Array[Byte] => Manifest): Either[String, Module] =
    scala.util.Try(decode(payloadBytes, manifestReader)).toEither.left.map(_.getMessage)

  private def decode(payloadBytes: Array[Byte], manifestReader: Array[Byte] => Manifest): Module =
    val pos = Array(0)

    // Decode trie section
    val trieLen    = getBE32(payloadBytes, pos(0)); pos(0) += 4
    val trieEnd    = pos(0) + trieLen
    val dict       = TrieDecoder.decode(payloadBytes, pos)
    pos(0)         = trieEnd

    // Decode token stream (trees are left None; populated by populateTrees)
    readModule(payloadBytes, pos, dict, manifestReader)

  /** Parse scalameta trees for all parseable code blocks in parallel, then
   *  return a new Module with the trees populated.  Code blocks that had a
   *  stored parse error keep `tree = None`. */
  private def populateTrees(m: Module): Module =
    import java.util.concurrent.{Callable, ForkJoinPool}
    import scala.collection.mutable.ArrayBuffer

    // Collect all parseable blocks that need a tree (parseError == None only)
    val blocks = ArrayBuffer.empty[Content.CodeBlock]
    def collect(c: Content): Unit = c match
      case cb: Content.CodeBlock if Lang.isParseable(cb.lang) && cb.parseError.isEmpty => blocks += cb
      case _ => ()
    def collectSection(s: Section): Unit =
      s.content.foreach(collect)
      s.subsections.foreach(collectSection)
    m.sections.foreach(collectSection)

    if blocks.isEmpty then return m

    // Parse all blocks in parallel using the common ForkJoin pool.
    // Each call to dialects.Scala3(...).parse is stateless and thread-safe.
    import scala.jdk.CollectionConverters.*
    val tasks: java.util.List[Callable[Content.CodeBlock]] =
      blocks.map { cb =>
        new Callable[Content.CodeBlock]:
          def call(): Content.CodeBlock =
            import scala.meta.*
            val tree = dialects.Scala3(Input.VirtualFile("<sscc>", cb.source)).parse[Source] match
              case Parsed.Success(t) => Some(ScalaNode(t))
              case _                 => None
            cb.copy(tree = tree)
      }.asJava

    val results = ForkJoinPool.commonPool().invokeAll(tasks)

    // Build a map from (lang, source) → parsed CodeBlock
    val parsedMap = new java.util.IdentityHashMap[Content.CodeBlock, Content.CodeBlock](blocks.length * 2)
    var i = 0
    while i < results.size do
      parsedMap.put(blocks(i), results.get(i).get())
      i += 1

    // Rebuild Module, replacing CodeBlocks with parsed versions
    def repopulate(c: Content): Content =
      val parsed = parsedMap.get(c)
      if parsed != null then parsed else c
    def rebuildSection(s: Section): Section =
      s.copy(
        content     = s.content.map(repopulate),
        subsections = s.subsections.map(rebuildSection)
      )
    m.copy(sections = m.sections.map(rebuildSection))

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

        val src = peekKind(bytes, pos) match
          case KCodeSmTokens =>
            Varint.read(bytes, pos) // consume kind
            val count = Varint.read(bytes, pos).toInt
            val sb = new java.lang.StringBuilder(count * 6)
            var i = 0
            while i < count do
              val smKind = Varint.read(bytes, pos).toInt
              sb.append(readSmTokenText(smKind, bytes, pos, dict))
              i += 1
            sb.toString
          case KCodeBlob =>
            Varint.read(bytes, pos) // consume kind
            val srcLen   = getBE32(bytes, pos(0)); pos(0) += 4
            val srcBytes = bytes.slice(pos(0), pos(0) + srcLen); pos(0) += srcLen
            new String(srcBytes, UTF_8)
          case other =>
            throw new IllegalStateException(s"expected KCodeSmTokens($KCodeSmTokens) or KCodeBlob($KCodeBlob) after KCodeStart, got $other at offset ${pos(0)}")

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

        // Tree is populated by populateTrees() after full decode (parallel parse).
        Content.CodeBlock(lang, src, tree = None, span, parseError, lineOffset, attrs.toMap)

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

  private def readSmTokenText(kind: Int, bytes: Array[Byte], pos: Array[Int], dict: Array[String]): String =
    if kind < 0 || kind >= SmFixedText.length then
      throw new IllegalStateException(s"unexpected sm token kind $kind")
    val fixed = SmFixedText(kind)
    if fixed != null then fixed
    else dict(Varint.read(bytes, pos).toInt)

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
