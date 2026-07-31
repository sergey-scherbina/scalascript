package scalascript.uniml.address

import scalascript.uniml.*
import scalascript.uniml.dialect.json.Json

/** The answer to a document address — the same triple SclJet uses for SQLite.
 *
 *  `typeName` is the format's own type where we know it and `Raw(n)` where we do not; `value` is
 *  the lexeme as written (never reinterpreted); `offset`/`length` are the physical half, always
 *  available because UniML spans every node.
 *
 *  `stable` is the property that makes an address safe to keep: it says whether this address names
 *  the value by IDENTITY or by POSITION. It is never a guess — see [[JsonAddress]].
 */
final case class DocAddressedValue(
    typeName: String,
    value: String,
    offset: Int,
    length: Int,
    line: Int,
    column: Int,
    stable: Boolean,
)

/** Addresses into a JSON document: `users/0/name`.
 *
 *  An address is the link between a logical location (the path) and a physical one (the bytes).
 *  Both halves come from UniML's **CST**, deliberately — not from its semantic projection.
 *  `JsonValue` carries a span only on `JsonMember`, so an array element resolved through the
 *  projection would have no physical half at all, and `users/0` would quietly become logical-only.
 *  The CST is the canonical tree; the projection is an optional view of it.
 *
 *  Spec: `specs/scljet-address.md`.
 */
object JsonAddress:

  /** Roles the JSON dialect puts on its edges (`JsonStructure.scala`). */
  private val MemberKey     = Some("member.key")
  private val MemberValue   = Some("member.value")
  private val ArrayElement  = Some("array.element")
  private val DocumentValue = Some("document.value")

  def read(text: String, path: String): Either[String, DocAddressedValue] =
    val parsed = Json.parse(SourceInput.fromString(SourceId("doc"), text))
    val fatal = parsed.diagnostics.filter(_.severity == Severity.Error)
    if fatal.nonEmpty then Left("cannot parse the document: " + fatal.head.message)
    else
      root(parsed.roots) match
        case None => Left("the document has no value")
        case Some(start) =>
          // An address into the document root names it by nothing at all — the root is unique, so
          // it is as stable as the document itself.
          walk(start, segments(path), stableSoFar = true)

  /** `a/b/c` → the segments. An empty path addresses the document root. */
  private def segments(path: String): List[String] =
    path.split('/').iterator.filter(_.nonEmpty).toList

  /** The document's single value, under the `document.value` role. */
  private def root(roots: Vector[UniNode]): Option[UniNode] =
    roots.iterator.flatMap {
      case b @ UniNode.Branch(_, edges, _, _) =>
        edges.iterator.filter(e => e.role == DocumentValue).map(_.child) ++ Iterator.single(b)
      case t: UniNode.Token => Iterator.single(t)
    }.nextOption()

  private def walk(node: UniNode, path: List[String], stableSoFar: Boolean): Either[String, DocAddressedValue] =
    path match
      case Nil => Right(describe(node, stableSoFar))
      case segment :: rest =>
        node match
          case UniNode.Branch("json.object", edges, _, _) =>
            member(edges, segment) match
              // An object key names its value. A NAME survives its neighbours changing, so the
              // address stays stable.
              case Some(child) => walk(child, rest, stableSoFar)
              case None => Left("no such key: " + segment)
          case UniNode.Branch("json.array", edges, _, _) =>
            index(segment) match
              case None => Left("not an array index: " + segment)
              case Some(i) =>
                val elements = edges.filter(_.role == ArrayElement).map(_.child)
                if i < 0 || i >= elements.length then
                  Left("array index out of range: " + segment)
                else
                  // An array index is a POSITION, not an identity: insert a sibling before it and
                  // this same address silently means a different value. Everything below it
                  // inherits that — a stable key under a positional index is still positional.
                  walk(elements(i), rest, stableSoFar = false)
          case _ =>
            Left("cannot descend into " + describe(node, stableSoFar).typeName + " at: " + segment)

  /** The value of the member whose `member.key` token spells `name`. The key and its value are
   *  separate edges of the object, so pair them by walking in order. */
  private def member(edges: Vector[UniEdge], name: String): Option[UniNode] =
    var i = 0
    var found: Option[UniNode] = None
    while i < edges.length && found.isEmpty do
      val edge = edges(i)
      if edge.role == MemberKey && keyText(edge.child).contains(name) then
        // the value is the next `member.value` edge after this key
        var j = i + 1
        while j < edges.length && found.isEmpty do
          if edges(j).role == MemberValue then found = Some(edges(j).child)
          else if edges(j).role == MemberKey then j = edges.length // a key with no value
          j += 1
      i += 1
    found

  /** A key's text, unescaped by the dialect's own lexer rather than by us re-reading the lexeme. */
  private def keyText(node: UniNode): Option[String] = node match
    case UniNode.Token(token) if token.kind == "json.string" => Some(unquote(token.lexeme))
    case _ => None

  private def unquote(lexeme: String): String =
    if lexeme.length >= 2 && lexeme.startsWith("\"") && lexeme.endsWith("\"") then
      lexeme.substring(1, lexeme.length - 1)
    else lexeme

  private def index(segment: String): Option[Int] =
    if segment.nonEmpty && segment.forall(_.isDigit) then segment.toIntOption else None

  /** The format's own type where we know it; `Raw(n)` where we do not. Knowing the extent is
   *  knowing something true, and UniML's span means we always know it. */
  private def describe(node: UniNode, stable: Boolean): DocAddressedValue = node match
    case UniNode.Branch(kind, _, span, _) =>
      val name = kind match
        case "json.object" => "object"
        case "json.array"  => "array"
        case other         => raw(span, other)
      value(name, text(span), span, stable)
    case UniNode.Token(token) =>
      val name = token.kind match
        case "json.string" => "string"
        case "json.number" => "number"
        case "json.true" | "json.false" => "boolean"
        case "json.null"   => "null"
        case _             => raw(token.span, token.kind)
      value(name, token.lexeme, token.span, stable)

  /** The floor: we do not know what this is, but we know exactly how much of it there is. */
  private def raw(span: SourceSpan, unknownKind: String): String =
    "Raw(" + ((span.end.offset - span.start.offset) * 8) + ")"

  private def value(typeName: String, lexeme: String, span: SourceSpan, stable: Boolean): DocAddressedValue =
    DocAddressedValue(
      typeName = typeName,
      value = lexeme,
      offset = span.start.offset,
      length = span.end.offset - span.start.offset,
      line = span.start.line,
      column = span.start.column,
      stable = stable)

  /** A branch's own text is its span; the caller already has the source, so the lexeme of a
   *  composite is reported as its extent rather than re-sliced here. */
  private def text(span: SourceSpan): String = ""
