package scalascript

package object markup:

  extension (sc: StringContext)
    /** Build a `Markup.Doc` from an interpolated XML string.
     *  Every argument is XML-escaped by default.  Wrap with `Markup.raw(...)` to
     *  splice pre-formed XML verbatim (caller's responsibility: well-formedness). */
    def xml(args: Any*): Markup.Doc =
      val escaped = args.map {
        case Markup.Raw(s)     => s
        case d: Markup.Doc     => MarkupCodec.default.serialize(d, SerializeOpts(omitXmlDecl = true))
        case e: Markup.Element => MarkupCodec.default.serialize(
                                    Markup.Doc(root = e), SerializeOpts(omitXmlDecl = true))
        case other             => XmlEscape.escape(String.valueOf(other))
      }
      val src = sc.parts.iterator.zipAll(escaped.iterator, "", "")
                   .map((p, a) => p + a).mkString
      MarkupCodec.default.parse(src, Dialect.Xml1_0) match
        case Right(doc) => doc
        case Left(e)    => throw e
