package scalascript.parser

import scalascript.ast.*
import org.commonmark.node.{
  Node            as CmNode,
  Document        as CmDocument,
  Heading         as CmHeading,
  Paragraph       as CmParagraph,
  FencedCodeBlock as CmFenced,
  BulletList      as CmBulletList,
  OrderedList     as CmOrderedList,
  ListItem        as CmListItem,
  Link            as CmLink,
  Text            as CmText,
  Code            as CmCode,
}
import org.commonmark.parser.{Parser as CmParser}
import org.yaml.snakeyaml.Yaml
import scala.collection.mutable.{ListBuffer, Stack}
import scala.jdk.CollectionConverters.*

object Parser:
  private val mdParser  = CmParser.builder().build()
  private val snakeYaml = Yaml()

  def parse(source: String): Module =
    val (fmOpt, body) = splitFrontMatter(source)
    val doc = mdParser.parse(body).asInstanceOf[CmDocument]
    Module(fmOpt.map(parseManifest), extractSections(doc))

  def parseFile(path: os.Path): Module = parse(os.read(path))

  // ─── Front-matter ────────────────────────────────────────────────

  private def splitFrontMatter(src: String): (Option[String], String) =
    if !src.startsWith("---") then return (None, src)
    val nl = src.indexOf('\n')
    if nl < 0 then return (None, src)
    val rest = src.substring(nl + 1)
    val end  = rest.indexOf("\n---")
    if end < 0 then return (None, src)
    (Some(rest.substring(0, end)), rest.substring(end + 4).dropWhile(_ == '\n'))

  private def parseManifest(yaml: String): Manifest =
    val raw = Option(snakeYaml.load[java.util.Map[String, Any]](yaml))
      .map(_.asScala.toMap).getOrElse(Map.empty)
    Manifest(
      name         = raw.get("name").collect { case s: String => s },
      version      = raw.get("version").collect { case s: String => s },
      description  = raw.get("description").collect { case s: String => s },
      dependencies = raw.get("dependencies").collect {
        case m: java.util.Map[?, ?] =>
          m.asScala.map { case (k, v) => k.toString -> v.toString }.toMap
      }.getOrElse(Map.empty),
      exports = raw.get("exports").collect {
        case l: java.util.List[?] => l.asScala.map(_.toString).toList
      }.getOrElse(Nil),
      targets = raw.get("targets").collect {
        case l: java.util.List[?] => l.asScala.map(_.toString).toList
      }.getOrElse(Nil),
      raw = raw
    )

  // ─── Section extraction from the flat CommonMark tree ────────────
  //
  // CommonMark produces a flat sequence of block nodes; headings are siblings,
  // not parents, of the content that follows them.  We use a mutable stack to
  // fold that flat sequence into our nested Section tree.

  private case class Frame(
    level: Int,
    heading: Heading,
    content: ListBuffer[Content],
    subsections: ListBuffer[Section]
  )

  private def extractSections(doc: CmDocument): List[Section] =
    val roots = ListBuffer[Section]()
    val stack = Stack[Frame]()

    // Pop all frames whose level >= toLevel and wire them into their parents.
    def flush(toLevel: Int): Unit =
      while stack.nonEmpty && stack.top.level >= toLevel do
        val f = stack.pop()
        val s = Section(f.heading, f.content.toList, f.subsections.toList)
        if stack.nonEmpty then stack.top.subsections += s else roots += s

    var node = doc.getFirstChild
    while node != null do
      node match
        case h: CmHeading =>
          flush(h.getLevel)
          stack.push(Frame(
            level      = h.getLevel,
            heading    = Heading(h.getLevel, textOf(h)),
            content    = ListBuffer.empty,
            subsections = ListBuffer.empty
          ))
        case other =>
          toContent(other).foreach { c =>
            if stack.nonEmpty then stack.top.content += c
          }
      node = node.getNext

    flush(0)
    roots.toList

  // ─── Node → Content ──────────────────────────────────────────────

  private def toContent(node: CmNode): Option[Content] = node match
    case f: CmFenced =>
      val lang = Option(f.getInfo).map(_.trim.takeWhile(!_.isWhitespace)).getOrElse("").toLowerCase
      val src  = Option(f.getLiteral).getOrElse("")
      val tree = if lang == "scala" || lang == "ssc" then parseScala(src) else None
      Some(Content.CodeBlock(lang, src, tree))

    case p: CmParagraph =>
      asImport(p).orElse {
        val text = textOf(p)
        if text.nonEmpty then Some(Content.Prose(text)) else None
      }

    case l: CmBulletList  => Some(Content.DataList(listItems(l), ordered = false))
    case l: CmOrderedList => Some(Content.DataList(listItems(l), ordered = true))
    case _                => None

  private def asImport(para: CmParagraph): Option[Content.Import] =
    val child = para.getFirstChild
    if child == null || child.getNext != null then return None
    child match
      case link: CmLink =>
        val path     = link.getDestination
        val bindings = textOf(link).split(",").map(_.trim).filter(_.nonEmpty)
          .map(b => ImportBinding(b, None)).toList
        if path.nonEmpty && bindings.nonEmpty then Some(Content.Import(path, bindings)) else None
      case _ => None

  private def listItems(list: CmNode): List[ListItem] =
    val buf = ListBuffer[ListItem]()
    var item = list.getFirstChild
    while item != null do
      item match
        case li: CmListItem => buf += ListItem(textOf(li), Nil)
        case _              => ()
      item = item.getNext
    buf.toList

  // ─── Scala parsing via scalameta ─────────────────────────────────

  private def parseScala(code: String): Option[ScalaNode] =
    import scala.meta.*
    given Dialect = dialects.Scala3
    code.parse[Source] match
      case Parsed.Success(tree) => Some(ScalaNode(tree))
      case _: Parsed.Error      =>
        // Script mode: code may contain top-level expressions.
        // Wrap in a block so scalameta accepts arbitrary statement sequences.
        s"{\n$code\n}".parse[Term] match
          case Parsed.Success(tree) => Some(ScalaNode(tree))
          case _: Parsed.Error      => None

  // ─── Text extraction from CommonMark nodes ────────────────────────

  private def textOf(node: CmNode): String =
    val buf = StringBuilder()
    def walk(n: CmNode): Unit =
      n match
        case t: CmText => buf ++= t.getLiteral
        case c: CmCode => buf ++= "`" ++= c.getLiteral ++= "`"
        case _         => ()
      var child = n.getFirstChild
      while child != null do
        walk(child)
        child = child.getNext
    walk(node)
    buf.toString.trim
