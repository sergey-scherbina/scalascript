package scalascript.uniml.scala

import scalascript.uniml.*
import scalascript.uniml.dialect.markdown.{Markdown, MarkdownProfile, CommonMarkDialect}
import scalascript.uniml.dialect.yaml.YamlDialect
import scalascript.uniml.dialect.json.JsonDialect
import scalascript.uniml.spike.SpikeDialect

/** P6.3 — "unify the hybrid" + the registry hook.
  *
  * A whole `.ssc` file is a hybrid: an optional YAML front-matter block, Markdown
  * prose, and fenced code. This composer parses it as ONE lossless UniML tree by
  * *injection*, never reinterpretation:
  *
  *   1. the Markdown ScalaScript profile frames the file (front-matter, prose, fences);
  *   2. every `markdown.code-block` whose info string names a *registered* dialect has its
  *      fence body re-parsed by that dialect and the resulting CST spliced under the block;
  *   3. the `markdown.front-matter` YAML is re-parsed by the YAML dialect and spliced in.
  *
  * Which dialect a fence selects is resolved through a [[DialectRegistry]] — the "registry
  * hook". [[builtins]] is the closed built-in set (ScalaScript, Markdown, YAML, JSON); users
  * may add MORE via [[registryWith]] but cannot override a built-in name (the registry rejects
  * duplicates). Each dialect only ever sees its own bytes — the fence body, the front-matter
  * body — so composition is explicit boundary-injection, never a silent re-lex. A fence whose
  * info string names no registered dialect stays an inert `markdown.code-block`.
  *
  * The extracted [[Composed.scalaSource]] is exactly the bare `.ssc0` the ScalaScript fences
  * carry, so the code half compiles byte-identically to the hand-written `ssc1-front`.
  */
object SscCompose:
  /** the closed built-in dialect set every `.ssc` composer resolves fence languages against. */
  def builtins: DialectRegistry =
    DialectRegistry(SpikeDialect, CommonMarkDialect, YamlDialect, JsonDialect) match
      case Right(r)  => r
      case Left(err) => throw new IllegalStateException(s"built-in dialects collide: $err")

  /** the built-in set plus `extra` user dialects — built-in names win: a name collision is a
    * `Left`, never a silent override (that is what "user-closed" means). */
  def registryWith(extra: DialectAdapter*): Either[String, DialectRegistry] =
    extra.foldLeft[Either[String, DialectRegistry]](Right(builtins))((acc, d) => acc.flatMap(_.register(d)))

  /** canonical short edge-role/label for each built-in injected subtree. */
  private val CanonicalTag: Map[String, String] = Map(
    SpikeDialect.id      -> "scalascript",
    YamlDialect.id       -> "yaml",
    JsonDialect.id       -> "json",
    CommonMarkDialect.id -> "markdown",
  )
  private def tagOf(a: DialectAdapter): String = CanonicalTag.getOrElse(a.id, a.id)

  /** `code` is the raw, lossless fence body (including its trailing line terminator);
    * `dialectId` is the id of the dialect that parsed it, or `None` for an inert fence. */
  final case class Fence(lang: String, code: String, dialectId: Option[String]):
    def injected: Boolean = dialectId.isDefined
    def isScala: Boolean  = dialectId.contains(SpikeDialect.id)

  final case class Composed(
      root: UniNode,
      fences: Vector[Fence],
      frontMatter: Option[String],
      diagnostics: Vector[Diagnostic],
      status: CompletionStatus,
  ):
    /** the ScalaScript fences, in source order, joined as one program — the bare `.ssc0` the
      * file's code half carries. Each fence's trailing line terminator is a fence artifact and
      * pure trivia to both the ScalaScript dialect and `ssc1-front`, so this accessor drops it:
      * presentation, never a correctness crutch (see the tolerance test). */
    def scalaSource: String = fences.filter(_.isScala).map(f => stripTrailingEol(f.code)).mkString("\n")

  /** concat the lexemes of the direct edges carrying `role`, in order — lossless. */
  private def textOfRole(b: UniNode.Branch, role: String): String =
    b.edges.collect { case UniEdge(Some(r), UniNode.Token(t)) if r == role => t.lexeme }.mkString

  /** drop the single fence-artifact line terminator that closes a fence body. */
  private def stripTrailingEol(s: String): String = s.stripSuffix("\n").stripSuffix("\r")

  /** the fence language: first whitespace-delimited word of the info string. */
  private def infoOf(b: UniNode.Branch): String =
    b.edges
      .collectFirst { case UniEdge(Some("info"), UniNode.Token(t)) => t.lexeme }
      .getOrElse("")
      .trim
      .takeWhile(!_.isWhitespace)

  def parse(source: String, registry: DialectRegistry = builtins): Composed =
    val md = Markdown.parse(SourceInput.fromString(SourceId("ssc:file"), source), MarkdownProfile.ScalaScript)
    var fences = Vector.empty[Fence]
    var front  = Option.empty[String]
    var diags  = md.diagnostics
    var worst  = md.status

    def worsen(s: CompletionStatus): Unit =
      if s.ordinal > worst.ordinal then worst = s

    def inject(b: UniNode.Branch, dropRole: String, body: String, adapter: DialectAdapter, id: String): UniNode.Branch =
      val sub = UniML.parse(SourceInput.fromString(SourceId(id), body), adapter)
      diags = diags ++ sub.diagnostics
      worsen(sub.status)
      val kept = b.edges.filterNot(_.role.contains(dropRole))
      b.copy(edges = kept ++ sub.roots.headOption.map(r => UniEdge(Some(tagOf(adapter)), r)).toVector)

    def transform(n: UniNode): UniNode = n match
      case b: UniNode.Branch if b.kind == "markdown.code-block" =>
        val lang = infoOf(b)
        val code = textOfRole(b, "code") // raw, lossless fence body — trailing EOL included
        // an untyped fence (```) defaults to ScalaScript; otherwise resolve via the registry.
        registry.get(if lang.isEmpty then "scalascript" else lang) match
          case Some(adapter) =>
            fences = fences :+ Fence(lang, code, Some(adapter.id))
            inject(b, "code", code, adapter, "ssc:fence") // trailing EOL is tolerated
          case None =>
            fences = fences :+ Fence(lang, code, None)
            b // a fence naming no registered dialect stays inert — no dialect reinterprets it
      case b: UniNode.Branch if b.kind == "markdown.front-matter" =>
        val yaml = textOfRole(b, "yaml")
        front = Some(yaml)
        registry.get("yaml") match
          case Some(adapter) => inject(b, "yaml", yaml, adapter, "ssc:frontmatter")
          case None          => b
      case b: UniNode.Branch =>
        b.copy(edges = b.edges.map(e => e.copy(child = transform(e.child))))
      case t => t

    val roots = md.roots.map(transform)
    val span = roots.headOption match
      case Some(b: UniNode.Branch) => b.span
      case Some(UniNode.Token(t))  => t.span
      case None                    => SourceSpan(SourceId("ssc:file"), SourcePosition.Start, SourcePosition.Start)
    val fileRoot = UniNode.Branch("ssc.file", roots.map(r => UniEdge(None, r)), span, Origin.Synthetic("ssc.compose"))
    Composed(fileRoot, fences, front, diags, worst)
