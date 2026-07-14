package scalascript.uniml.scala

import scalascript.uniml.*
import scalascript.uniml.dialect.markdown.{Markdown, MarkdownProfile}
import scalascript.uniml.dialect.yaml.YamlDialect
import scalascript.uniml.spike.SpikeDialect

/** P6.3 — "unify the hybrid".
  *
  * A whole `.ssc` file is a hybrid: an optional YAML front-matter block, Markdown
  * prose, and fenced ScalaScript code. This composer parses it as ONE lossless UniML
  * tree by *injection*, never reinterpretation:
  *
  *   1. the Markdown ScalaScript profile frames the file (front-matter, prose, fences);
  *   2. every `markdown.code-block` whose info string selects ScalaScript has its code
  *      re-parsed by [[SpikeDialect]] and the resulting CST spliced under the block;
  *   3. the `markdown.front-matter` YAML is re-parsed by the YAML dialect and spliced in.
  *
  * Each dialect only ever sees its own bytes — the fence body for ScalaScript, the
  * front-matter body for YAML — so composition is the explicit boundary-injection the
  * v2.2 spec prescribes, not a silent re-lex. The extracted [[Composed.scalaSource]] is
  * exactly the bare `.ssc0` the fences carry, so the code half compiles byte-identically
  * to the hand-written `ssc1-front` (proven by the differential harness).
  */
object SscCompose:
  /** info-strings that mark a fence as ScalaScript code (empty = an untyped fence). */
  val ScalaLangs: Set[String] = Set("scalascript", "scala", "ssc", "")

  /** `code` is the raw, lossless fence body (including its trailing line terminator). */
  final case class Fence(lang: String, code: String, injected: Boolean)

  final case class Composed(
      root: UniNode,
      fences: Vector[Fence],
      frontMatter: Option[String],
      diagnostics: Vector[Diagnostic],
      status: CompletionStatus,
  ):
    /** the injected ScalaScript fences, in source order, joined as one program — the bare
      * `.ssc0` the file's code half carries. Each fence's trailing line terminator is a fence
      * artifact and pure trivia to both the ScalaScript dialect and `ssc1-front`, so this
      * accessor drops it: presentation, never a correctness crutch (see the tolerance test). */
    def scalaSource: String = fences.filter(_.injected).map(f => stripTrailingEol(f.code)).mkString("\n")

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

  def parse(source: String): Composed =
    val md = Markdown.parse(SourceInput.fromString(SourceId("ssc:file"), source), MarkdownProfile.ScalaScript)
    var fences = Vector.empty[Fence]
    var front  = Option.empty[String]
    var diags  = md.diagnostics
    var worst  = md.status

    def worsen(s: CompletionStatus): Unit =
      if s.ordinal > worst.ordinal then worst = s

    def inject(b: UniNode.Branch, dropRole: String, body: String, adapter: DialectAdapter, edgeRole: String, id: String): UniNode.Branch =
      val sub = UniML.parse(SourceInput.fromString(SourceId(id), body), adapter)
      diags = diags ++ sub.diagnostics
      worsen(sub.status)
      val kept = b.edges.filterNot(_.role.contains(dropRole))
      b.copy(edges = kept ++ sub.roots.headOption.map(r => UniEdge(Some(edgeRole), r)).toVector)

    def transform(n: UniNode): UniNode = n match
      case b: UniNode.Branch if b.kind == "markdown.code-block" =>
        val lang = infoOf(b)
        val code = textOfRole(b, "code") // raw, lossless fence body — trailing EOL included
        if ScalaLangs(lang) then
          fences = fences :+ Fence(lang, code, injected = true)
          inject(b, "code", code, SpikeDialect, "scalascript", "ssc:fence") // trailing EOL is tolerated
        else
          fences = fences :+ Fence(lang, code, injected = false)
          b // a foreign fence stays inert — no dialect reinterprets it
      case b: UniNode.Branch if b.kind == "markdown.front-matter" =>
        val yaml = textOfRole(b, "yaml")
        front = Some(yaml)
        inject(b, "yaml", yaml, YamlDialect, "yaml", "ssc:frontmatter")
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
