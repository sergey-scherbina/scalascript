// `ssc`, NOT `scala`, and the difference is load-bearing: a package named
// `scalascript.uniml.scala` SHADOWS the root `scala` package for every source
// compiled beside it, so `scala.collection.mutable` stops resolving. That is not
// hypothetical — this package was named after its DIRECTORY (`uniml/scala`), and
// the day the ScalaScript dialect moved in here it stopped compiling until every
// reference was rewritten to `_root_.scala.…`. Those workarounds are gone with
// the rename. If you are tempted to align the package back to the directory
// name, this is the reason not to.
package scalascript.uniml.ssc

import scalascript.uniml.*
import scalascript.uniml.dialect.markdown.{Markdown, MarkdownProfile, CommonMarkDialect}
import scalascript.uniml.dialect.yaml.YamlDialect
import scalascript.uniml.dialect.json.JsonDialect
import scalascript.uniml.dialect.scalascript.SpikeDialect

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

  /** Does the fence's info string carry `@doc` — the marker that makes a block documentation
    * rather than program code? Mirrors v1's `Content.CodeBlock.isDocOnly`, which reads the same
    * `@key=value` attributes and treats any value other than `"false"` as set. */
  private def docAttr(b: UniNode.Branch): Boolean =
    val info = b.edges
      .collectFirst { case UniEdge(Some("info"), UniNode.Token(t)) => t.lexeme }
      .getOrElse("")
    "@doc(?:=(\\S+))?".r.findFirstMatchIn(info).exists(m => Option(m.group(1)).forall(_ != "false"))

  /** the fence language: first whitespace-delimited word of the info string. */
  private def infoOf(b: UniNode.Branch): String =
    b.edges
      .collectFirst { case UniEdge(Some("info"), UniNode.Token(t)) => t.lexeme }
      .getOrElse("")
      .trim
      .takeWhile(c => !UniAlphabet.isWhitespace(c))

  def parse(source: String, registry: DialectRegistry = builtins): Composed =
    val md = Markdown.parse(SourceInput.fromString(SourceId("ssc:file"), source), MarkdownProfile.ScalaScript)
    var fences = Vector.empty[Fence]
    var front  = Option.empty[String]
    var diags  = md.diagnostics
    var worst  = md.status

    def worsen(s: CompletionStatus): Unit =
      if s.ordinal > worst.ordinal then worst = s

    /** Positions of a child parse, in the FILE's coordinates.
      *
      * A child dialect sees only its own bytes, so everything it produces is
      * measured from offset 0 of that fence. Splicing it in unchanged left the
      * whole subtree — and every diagnostic — in a coordinate space no reader
      * shares: for a fence starting at file offset 42, a diagnostic said
      * `ssc:fence` line 1 offset 15 when the reader needed line 6 offset 57, and
      * every fence carried the SAME source id, so a diagnostic could not even be
      * traced to one. A child span was not inside its parent's either.
      *
      * Built as an index over the body so the remap is one lookup per position
      * rather than a rescan, and walked by CODE POINT so a surrogate pair is
      * advanced whole. */
    def positionIndex(body: String, start: SourcePosition): Array[SourcePosition] =
      val index = Array.fill(body.length + 1)(start)
      var pos = start
      var i = 0
      while i < body.length do
        // A surrogate pair is two chars, anything else is one — the same rule `Unicode` already
        // applies when it advances a position, and tableless unlike `Character.charCount`.
        val width =
          if Unicode.isHighSurrogate(body.charAt(i)) && i + 1 < body.length &&
            Unicode.isLowSurrogate(body.charAt(i + 1))
          then 2
          else 1
        var k = 0
        while k < width do { index(i + k) = pos; k += 1 }
        pos = Unicode.advance(pos, body.substring(i, i + width))
        i += width
      index(body.length) = pos
      index

    def inject(b: UniNode.Branch, dropRole: String, body: String, adapter: DialectAdapter, id: String): UniNode.Branch =
      val sub = UniML.parse(SourceInput.fromString(SourceId(id), body), adapter)
      // where this body sits in the file: the first token carrying the dropped role
      val bodyStart = b.edges.collectFirst {
        case UniEdge(Some(r), UniNode.Token(t)) if r == dropRole => t.span.start
      }
      val remapped = bodyStart match
        case None => sub // no anchor: leave it rather than invent a position
        case Some(start) =>
          val index = positionIndex(body, start)
          val fileSource = b.span.source
          def at(p: SourcePosition): SourcePosition =
            index(math.min(math.max(p.offset, 0), body.length))
          def span(sp: SourceSpan): SourceSpan = SourceSpan(fileSource, at(sp.start), at(sp.end))
          def node(n: UniNode): UniNode = n match
            case UniNode.Token(t) => UniNode.Token(t.copy(span = span(t.span)))
            case br: UniNode.Branch =>
              br.copy(span = span(br.span), edges = br.edges.map(e => e.copy(child = node(e.child))))
          sub.copy(
            roots = sub.roots.map(node),
            diagnostics = sub.diagnostics.map(d => d.copy(span = d.span.map(span))),
          )
      diags = diags ++ remapped.diagnostics
      worsen(remapped.status)
      // SPLICE the subtree where the body was, do not append it. Appending put the injected code
      // after the closing fence marker, so an in-order walk of the composed tree reconstructed the
      // right characters in the WRONG ORDER — `\u0060\u0060\u0060` before the code instead of after. The
      // dialect's own losslessness spec cannot see this: it parses a bare dialect with no injection
      // at all. Found by consuming the published artifact from outside the build and checking
      // round-trip there.
      val at = b.edges.indexWhere(_.role.contains(dropRole))
      val kept = b.edges.filterNot(_.role.contains(dropRole))
      val inj = remapped.roots.headOption.map(r => UniEdge(Some(tagOf(adapter)), r)).toVector
      val before = if at < 0 then kept.length else b.edges.take(at).count(e => !e.role.contains(dropRole))
      b.copy(edges = kept.take(before) ++ inj ++ kept.drop(before))

    def transform(n: UniNode): UniNode = n match
      // ONLY a fenced block. An INDENTED code block is a `markdown.code-block` too, with no info
      // string, so it used to fall through to the untyped-fence default and be parsed as
      // ScalaScript — a four-space-indented table or program output handed to the compiler front.
      // It also broke round-trip by construction: an indented block's body is INTERLEAVED with a
      // per-line indent token, so replacing the body with one subtree cannot preserve the order,
      // and the indents ended up behind the code. Six corpus files failed exactly that way.
      case b: UniNode.Branch if b.kind == "markdown.code-block" && !b.edges.exists(_.role.contains("fence.open")) =>
        b
      case b: UniNode.Branch if b.kind == "markdown.code-block" =>
        val lang = infoOf(b)
        val code = textOfRole(b, "code") // raw, lossless fence body — trailing EOL included
        // WHICH FENCES ARE CODE — measured against the reference front on 2026-08-06, not assumed:
        //
        //   no fences in the file at all   the whole file is code   (bare `.ssc`)
        //   ``` with no info string        NOT code, even when it parses
        //   scala / scalascript / ssc      code
        //   any of those with `@doc`       NOT code
        //
        // This used to default an untyped fence to ScalaScript, which is where the whole `untagged`
        // column of the breadth probe came from: 33 fences, 52 diagnostics, none of them ever
        // compiled by any lane. `Lang.isParseable` in v1 knows only the three names, and
        // `isProgramCode` is `isParseable(lang) && !isDocOnly`.
        if lang.isEmpty || docAttr(b) then b
        else registry.get(lang) match
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
