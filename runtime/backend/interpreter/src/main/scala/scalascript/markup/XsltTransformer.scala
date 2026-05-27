package scalascript.markup

import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.{StreamResult, StreamSource}
import java.io.{StringReader, StringWriter}

/** Pure-JVM XSLT 1.0 transformer backed by `javax.xml.transform.TransformerFactory`.
 *
 *  Usage:
 *  {{{
 *    XsltTransformer(sourceDoc, xsltString, Map("title" -> "Hello"))
 *  }}}
 *
 *  - `source` is serialized to a string via [[PureMarkupCodec.serialize]], then
 *    fed to the JAXP transformer as a `StreamSource`.
 *  - The stylesheet receives any `params` as top-level parameters (`<xsl:param>`).
 *  - The output string is parsed back with [[JvmMarkupCodec.parse]] so the returned
 *    [[Markup.Doc]] reflects any namespace bindings produced by the transform.
 *  - Errors at any stage are caught and wrapped in [[TransformError]]. */
object XsltTransformer:

  def apply(
    source: Markup.Doc,
    xslt:   String,
    params: Map[String, String] = Map.empty
  ): Either[TransformError, Markup.Doc] =
    try
      val factory    = TransformerFactory.newInstance()
      val xsltSource = new StreamSource(new StringReader(xslt))
      val transformer =
        try factory.newTransformer(xsltSource)
        catch
          case e: javax.xml.transform.TransformerConfigurationException =>
            return Left(TransformError(
              s"Invalid XSLT stylesheet: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
            ))

      // Pass caller-supplied parameters as top-level XSLT parameters.
      params.foreach { (k, v) => transformer.setParameter(k, v) }

      val xmlSource = PureMarkupCodec.serialize(source, SerializeOpts.default)
      val inputSrc  = new StreamSource(new StringReader(xmlSource))
      val writer    = new StringWriter()
      val result    = new StreamResult(writer)

      try transformer.transform(inputSrc, result)
      catch
        case e: javax.xml.transform.TransformerException =>
          return Left(TransformError(
            s"XSLT transform failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
          ))

      val output = writer.toString
      // Normalised output: strip the XML declaration and surrounding whitespace
      // to decide whether the stylesheet produced any actual XML content.
      val stripped = output
        .replaceFirst("""<\?xml[^?]*\?>""", "")
        .trim
      if stripped.isEmpty then
        // Stylesheet produced empty output (or only an XML declaration) —
        // return a minimal placeholder document rather than a parse error.
        Right(Markup.Doc(root = Markup.Element(Markup.QName.local("empty"))))
      else
        JvmMarkupCodec.parse(output, Dialect.Xml1_0) match
          case Right(doc) => Right(doc)
          case Left(e)    => Left(TransformError(
            s"XSLT output is not valid XML: ${e.getMessage}"
          ))
    catch
      case e: Exception =>
        Left(TransformError(
          s"Unexpected error during XSLT transform: ${Option(e.getMessage).getOrElse(e.getClass.getName)}"
        ))
