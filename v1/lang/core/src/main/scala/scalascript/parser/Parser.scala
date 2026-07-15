package scalascript.parser

import scalascript.ast.*
import scalascript.transform.{MarkupLiteralLower, RemoteClientDeriver, RouteDeriver}
import org.commonmark.node.{
  Node            as CmNode,
  Document        as CmDocument,
  Heading         as CmHeading,
  Paragraph       as CmParagraph,
  FencedCodeBlock as CmFenced,
  IndentedCodeBlock as CmIndentedCode,
  BulletList      as CmBulletList,
  OrderedList     as CmOrderedList,
  ListItem        as CmListItem,
  Link            as CmLink,
  Image           as CmImage,
  Text            as CmText,
  Code            as CmCode,
  Emphasis        as CmEmphasis,
  StrongEmphasis  as CmStrongEmphasis,
  SoftLineBreak   as CmSoftLineBreak,
  HardLineBreak   as CmHardLineBreak,
  HtmlBlock       as CmHtmlBlock,
  HtmlInline      as CmHtmlInline,
}
import org.commonmark.Extension
import org.commonmark.ext.gfm.tables.{
  TablesExtension,
  TableBlock as CmTableBlock,
  TableCell  as CmTableCell,
  TableRow   as CmTableRow
}
import org.commonmark.parser.{Parser as CmParser, IncludeSourceSpans}
import scala.collection.mutable.{ListBuffer, Stack}
import scala.jdk.CollectionConverters.*

object Parser:
  private val mdParser = CmParser.builder()
    .extensions(List[Extension](TablesExtension.create()).asJava)
    .includeSourceSpans(IncludeSourceSpans.BLOCKS)
    .build()

  /** Parse a YAML front-matter string to a [[Manifest]].
   *  Exposed for `SsccFormatV3` YAML-event read path. */
  private[scalascript] def manifestFromYaml(yaml: String): Manifest = parseManifest(yaml)

  def parse(source: String): Module =
    // Strip shebang line so files can be self-executing: #!/usr/bin/env ssc
    val shebangLines = if source.startsWith("#!") then 1 else 0
    val noShebang =
      if shebangLines == 1 then source.dropWhile(_ != '\n').drop(1)
      else source
    val (fmOpt, body, fmStripped) = splitFrontMatterCounting(noShebang)
    val (sourceCluster, clusterStrippedBody) = extractSourceCluster(body)
    val isWrapped = isPureScala(clusterStrippedBody)
    // Pure-Scala script (no Markdown headings or fences): wrap in a synthetic section
    val mdSrc =
      if isWrapped then s"# Script\n\n```scala\n${clusterStrippedBody.trim}\n```\n"
      else clusterStrippedBody
    val doc = mdParser.parse(mdSrc).asInstanceOf[CmDocument]
    val manifest0 = mergeSourceCluster(fmOpt.map(parseManifest), sourceCluster)
    // Map a CommonMark 0-indexed line in `mdSrc` back to a 0-indexed line in
    // the ORIGINAL `source` file.  For the standard path this is a simple
    // additive offset (shebang lines + front-matter lines that were stripped
    // before parsing).  For the pure-Scala-wrap path we have to subtract the
    // 3 synthetic header lines (`# Script`, blank, ```` ```scala ```` ) we
    // prepended AND add the count of leading whitespace lines in `body` that
    // `.trim` discarded, so the first code line still maps to the user's
    // original source line.
    val mdLineToFileLine: Int => Int =
      if isWrapped then
        // Number of leading newline-terminated whitespace lines in `body`
        // that `.trim` collapsed.  Lines wholly consumed by `dropWhile(_ ==
        // '\n')` plus any whitespace-only prefix lines.
        val leadingWs = clusterStrippedBody.takeWhile(c => c == '\n' || c == ' ' || c == '\t' || c == '\r')
        val leadingWsLines = leadingWs.count(_ == '\n')
        val base = shebangLines + fmStripped + leadingWsLines
        (mdLine: Int) => base + math.max(0, mdLine - 3)
      else
        val base = shebangLines + fmStripped
        (mdLine: Int) => base + mdLine
    val pkg      = manifest0.flatMap(_.pkg).getOrElse(Nil)
    // When `package:` is set, wrapSectionInPackage replaces every code-block
    // tree with a re-parsed package-wrapped form.  Skip the initial scalameta
    // parse here so we don't do it twice.
    val sections = extractSections(doc, mdLineToFileLine, skipInitialParse = pkg.nonEmpty)
    val finalSections = if pkg.isEmpty then sections else sections.map(wrapSectionInPackage(_, pkg))
    val manifest1 = mergeSourceRemoteHandlers(manifest0, finalSections)
    val manifest  = mergeSourceModels(manifest1, finalSections)
    val document  = buildDocumentContent(doc, manifest)
    val raw       = Module(manifest, finalSections, sourceText = Some(source), document = Some(document))
    validateRemoteRegistries(raw)
    MarkupLiteralLower.lower(RouteDeriver.derive(RemoteClientDeriver.derive(raw)))

  /** Wrap every scalascript-block's contents in nested `object`s matching
   *  the front-matter `package:` segments.  `package: org.example.ui`
   *  on a block holding `object Card { … }` becomes
   *  `object org { object example { object ui { object Card { … } } } }`,
   *  so importers see the module's names under the dotted prefix and
   *  two libraries can each export `Card` without collision. */
  private def wrapSectionInPackage(section: Section, pkg: List[String]): Section =
    val newContent = section.content.map {
      case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
        // Preprocess `extern def` / `effect` / list-form imports BEFORE wrapping
        // in objects.  Otherwise the surface forms survive into the nested code
        // that scalameta sees and the whole block silently fails to parse
        // (cb.tree = None → imports from this file see no symbols).
        val preprocessed = preprocessForScala(cb.source)
        val nested = pkg.foldRight(preprocessed) { (seg, body) =>
          val indented = body.linesIterator.map("  " + _).mkString("\n")
          s"object $seg:\n$indented"
        }
        val tree = scala.util.Try {
          import scala.meta.{dialects, *}
          dialects.Scala3(Input.VirtualFile(s"<package-wrap>", nested))
            .parse[Source].toOption.map(scalascript.ast.ScalaNode(_))
        }.toOption.flatten
        // Preserve the original parseError (when present) so the CLI still sees
        // a positional diagnostic for blocks that even the package-wrap retry
        // failed to rescue.
        val pe = if tree.isEmpty then cb.parseError else None
        // Carry the ORIGINAL block's `lineOffset` through the wrap.  The
        // wrap rewrites `cb.source` into a synthetic `object pkg: …` body
        // that no longer corresponds line-for-line with the user's `.ssc`,
        // but downstream consumers (LSP, error reporting) want the file
        // line of the user's first code line — not a position into the
        // synthesized wrapper.  Preserving the field here means
        // `extractor` + LSP can keep using `cb.lineOffset` as the single
        // source of truth regardless of whether `package:` is set.
        Content.CodeBlock(cb.lang, nested, tree, cb.span, pe, cb.lineOffset)
      case other => other
    }
    section.copy(
      content     = newContent,
      subsections = section.subsections.map(wrapSectionInPackage(_, pkg))
    )

  def parseFile(path: os.Path): Module =
    RouteDeriver.derive(parse(os.read(path)), Some(path / os.up))

  def rewriteInlineImports(code: String): String = preprocessInlineImports(code)

  // A source is "pure Scala" when it has no Markdown headings (# ...) or fences (```).
  // After shebang stripping, this reliably distinguishes plain scripts from .ssc documents.
  private def isPureScala(src: String): Boolean =
    !src.linesIterator.exists(l => l.startsWith("#") || l.startsWith("```"))

  // ─── Front-matter ────────────────────────────────────────────────

  /** Returns the number of lines from the start of `src` that were consumed
   *  before `body` begins.  Used by [[parse]] to translate CommonMark's
   *  (mdSrc-local) line indices back to file-level lines for
   *  [[Content.CodeBlock.lineOffset]]. */
  private def splitFrontMatterCounting(src: String): (Option[String], String, Int) =
    if !src.startsWith("---") then return (None, src, 0)
    val nl = src.indexOf('\n')
    if nl < 0 then return (None, src, 0)
    val rest = src.substring(nl + 1)
    val end  = rest.indexOf("\n---")
    if end < 0 then return (None, src, 0)
    val tail = rest.substring(end + 4).dropWhile(_ == '\n')
    val body = tail
    // Lines consumed = everything from start of `src` up to (but not
    // including) the first character of `body`.  Equivalently: total
    // newlines in `src` − total newlines in `body`, when `body` is what
    // remains.  Compute directly from substring lengths to stay correct
    // when the front-matter is empty or has trailing whitespace.
    val stripped     = src.length - body.length
    val strippedText = src.substring(0, stripped)
    val lines = strippedText.count(_ == '\n')
    (Some(rest.substring(0, end)), body, lines)

  /** SnakeYAML's "mapping values are not allowed here" surfaces with the
   *  line/column of the offending construct but with no hint about which
   *  key/value caused it.  Wrap the exception to add a one-line hint
   *  pointing at the most common cause (unquoted colons in string values
   *  like `description: foo: bar`). */
  private def parseManifest(yaml: String): Manifest =
    val raw =
      try Option(SimpleYaml.load[java.util.Map[String, Any]](yaml))
            .map(_.asScala.toMap).getOrElse(Map.empty)
      catch case e: SimpleYaml.ParseError =>
        val lines = yaml.linesIterator.toIndexedSeq
        val likely = lines.find(l => l.contains(": ") && !l.trim.startsWith("#"))
          .map(_ => "\n  hint: this looks like an unquoted colon inside a string value;\n" +
                    "        quote the value, e.g.  description: \"foo: bar\"")
          .getOrElse("")
        throw new RuntimeException(s"YAML front-matter: ${e.getMessage}$likely")
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
      routes = parseRoutes(raw),
      pkg = raw.get("package").collect {
        case s: String if s.nonEmpty => s.split('.').toList.filter(_.nonEmpty)
      },
      translations = raw.get("translations").collect {
        case m: java.util.Map[?, ?] =>
          m.asScala.collect { case (locale: String, v: java.util.Map[?, ?]) =>
            locale -> (v.asScala.collect { case (k: String, vv) => k -> vv.toString }.toMap: Map[String, String])
          }.toMap
      }.getOrElse(Map.empty),
      databases = parseDatabases(raw),
      objectStores = parseObjectStores(raw),
      graphs = parseGraphs(raw),
      schemas = parseSchemas(raw),
      frontendFramework = raw.get("frontend").orElse(raw.get("frontend-framework")).collect { case s: String => s },
      scripts = raw.get("scripts").collect {
        case m: java.util.Map[?, ?] =>
          m.asScala.collect { case (k, v) => k.toString -> v.toString }.toMap
      }.getOrElse(Map.empty),
      cluster = parseCluster(raw),
      remoteHandlers = parseRemoteHandlers(raw),
      remoteSources = parseRemoteSources(raw),
      remoteBehaviors = parseRemoteBehaviors(raw),
      apiClients = parseApiClients(raw),
      deploy = parseRawMap(raw, "deploy"),
      groups = parseRawMap(raw, "groups"),
      environments = parseRawMap(raw, "environments"),
      deployState = parseRawMap(raw, "state"),
      raw = raw
    )

  /** Pull `routes: [{method: ..., path: ..., handler: ...}]` out of the
   *  raw YAML map.  Entries that lack any of the three required fields
   *  are silently skipped — strict validation lives in the typer, not
   *  here, so a misspelled key surfaces as an "unknown route" later
   *  rather than blocking the whole parse. */
  private def parseRoutes(raw: Map[String, Any]): List[RouteDecl] =
    raw.get("routes").collect {
      case xs: java.util.List[?] =>
        xs.asScala.toList.flatMap {
          case m: java.util.Map[?, ?] =>
            val mm: Map[String, Any] = m.asScala.iterator.collect {
              case (k: String, v) => k -> (v: Any)
            }.toMap
            val method  = mm.get("method").collect { case s: String => s.toUpperCase }
            val path    = mm.get("path").collect { case s: String => s }
            val handler = mm.get("handler").collect { case s: String => s }
            (method, path, handler) match
              case (Some(m), Some(p), Some(h)) => Some(RouteDecl(m, p, h))
              case _                            => None
          case _ => None
        }
    }.getOrElse(Nil)

  private def parseApiClients(raw: Map[String, Any]): List[ApiClientDecl] =
    val source = raw.get("apiClients").orElse(raw.get("api-clients"))
    source.collect {
      case m: java.util.Map[?, ?] =>
        m.asScala.toList.flatMap {
          case (clientName, clientMap: java.util.Map[?, ?]) =>
            val mm: Map[String, Any] = clientMap.asScala.iterator.collect {
              case (k: String, v) => k -> (v: Any)
            }.toMap
            val endpoints = parseApiEndpoints(mm.get("endpoints"))
            if endpoints.nonEmpty then Some(ApiClientDecl(clientName.toString, endpoints)) else None
          case _ => None
        }
      case xs: java.util.List[?] =>
        xs.asScala.toList.flatMap {
          case clientMap: java.util.Map[?, ?] =>
            val mm: Map[String, Any] = clientMap.asScala.iterator.collect {
              case (k: String, v) => k -> (v: Any)
            }.toMap
            val name = mm.get("name").collect { case s: String => s }
            val endpoints = parseApiEndpoints(mm.get("endpoints"))
            name.filter(_ => endpoints.nonEmpty).map(ApiClientDecl(_, endpoints))
          case _ => None
        }
    }.getOrElse(Nil)

  private def parseApiEndpoints(value: Option[Any]): List[ApiEndpointDecl] =
    value.collect {
      case xs: java.util.List[?] =>
        xs.asScala.toList.flatMap {
          case m: java.util.Map[?, ?] =>
            val mm: Map[String, Any] = m.asScala.iterator.collect {
              case (k: String, v) => k -> (v: Any)
            }.toMap
            val name     = mm.get("name").collect { case s: String => s }
            val method   = mm.get("method").collect { case s: String => s.toUpperCase }
            val path     = mm.get("path").collect { case s: String => s }
            val request  = mm.get("request").orElse(mm.get("requestType")).orElse(mm.get("request-type")).collect { case s: String => s }
            val response = mm.get("response").orElse(mm.get("responseType")).orElse(mm.get("response-type")).collect { case s: String => s }
            val stream     = mm.get("stream").collect { case s: String => s; case b: java.lang.Boolean => b.toString }
            val paginated  = mm.get("paginated").collect { case b: java.lang.Boolean => b.booleanValue(); case s: String => s.equalsIgnoreCase("true") }.getOrElse(false)
            (name, method, path, request, response) match
              case (Some(n), Some(m), Some(p), Some(req), Some(resp)) =>
                Some(ApiEndpointDecl(n, m, p, req, resp, stream, paginated))
              case _ => None
          case _ => None
        }
    }.getOrElse(Nil)

  /** Pull `databases: { name: { url, user?, password?, driver? } }` out
   *  of the raw YAML map (SPEC § 3.3.1, v1.26).  Entries that lack a
   *  `url` are silently skipped — the runtime's `ConnectionRegistry`
   *  surfaces a more specific diagnostic when an `sql` block actually
   *  tries to resolve a missing connection.  `${env:NAME}` references
   *  inside the values are *preserved verbatim* here and resolved at
   *  runtime — keeping env access out of parse time means a `.ssc`
   *  file with the right schema parses on any machine, even one that
   *  doesn't have the secrets set. */
  /** Parse `@key=value` markers that may follow the lang tag on a
   *  fence line.  Values may be unquoted (whitespace-delimited) or
   *  double-quoted (allowing whitespace and equals signs inside).
   *  Keys are lower-cased — `@DB=x` is `db -> x`.
   *
   *  Used today by `sql` blocks to read `@db=name` (v1.26).  The
   *  syntax is general; future tags can pick up their own attrs
   *  without changing the parser. */
  private val FenceAttrPat = """@([A-Za-z_][A-Za-z0-9_-]*)=(?:"([^"]*)"|(\S+))""".r

  private def parseFenceAttrs(tail: String): Map[String, String] =
    if tail.isEmpty then Map.empty
    else
      FenceAttrPat.findAllMatchIn(tail).map { m =>
        val key   = m.group(1).toLowerCase
        val value = Option(m.group(2)).getOrElse(m.group(3))
        key -> value
      }.toMap

  private def parseFenceContentAttrs(tail: String): Map[String, ContentValue] =
    parseFenceAttrs(tail).view.mapValues(ContentValue.Str.apply).toMap

  private def parseRawMap(raw: Map[String, Any], key: String): Map[String, Any] =
    raw.get(key).collect { case m: java.util.Map[?, ?] =>
      m.asScala.iterator.collect { case (k: String, v) => k -> (v: Any) }.toMap
    }.getOrElse(Map.empty)

  private def parseCluster(raw: Map[String, Any]): Option[ClusterDecl] =
    raw.get("cluster").collect { case m: java.util.Map[?, ?] =>
      val mm = stringMap(m)
      ClusterDecl(
        name         = mm.get("name").collect { case s: String => s },
        nodeId       = mm.get("nodeId").orElse(mm.get("node-id")).collect { case s: String => s },
        role         = mm.get("role").collect { case s: String => s },
        bind         = mm.get("bind").collect { case s: String => s },
        advertiseUrl = mm.get("advertiseUrl").orElse(mm.get("advertise-url")).collect { case s: String => s },
        seedNodes    = parseStringList(mm.get("seedNodes").orElse(mm.get("seed-nodes"))),
        authToken    = mm.get("authToken").orElse(mm.get("auth-token")).collect { case s: String => s },
        placement    = parseStringMap(mm.get("placement")),
        wire         = parseStringMap(mm.get("wire")),
        nodes        = parseInt(mm.get("nodes")),
        seedDiscovery = mm.get("seedDiscovery").orElse(mm.get("seed-discovery")).map(_.toString),
        leaderElection = mm.get("leaderElection").orElse(mm.get("leader-election")).map(_.toString),
        authTokenFrom = mm.get("authTokenFrom").orElse(mm.get("auth-token-from")).map(_.toString),
        heartbeat    = parseStringMap(mm.get("heartbeat")),
        quorum       = parseInt(mm.get("quorum"))
      )
    }

  private def mergeSourceCluster(manifest: Option[Manifest], sourceCluster: Option[ClusterDecl]): Option[Manifest] =
    (manifest, sourceCluster) match
      case (Some(m), Some(c)) if m.cluster.isEmpty => Some(m.copy(cluster = Some(c)))
      case (Some(m), _) => Some(m)
      case (None, Some(c)) =>
        Some(Manifest(
          name = None,
          version = None,
          description = None,
          dependencies = Map.empty,
          exports = Nil,
          targets = Nil,
          routes = Nil,
          pkg = None,
          translations = Map.empty,
          cluster = Some(c),
          raw = Map.empty
        ))
      case (None, None) => None

  private def extractSourceCluster(body: String): (Option[ClusterDecl], String) =
    val lines = body.linesIterator.toVector
    val start = lines.indexWhere(line => line.matches("""^cluster\s+[A-Za-z_][A-Za-z0-9_-]*:\s*$"""))
    if start < 0 then return (None, body)
    val name = lines(start).trim.stripPrefix("cluster").stripSuffix(":").trim
    var end = start + 1
    while end < lines.length && (lines(end).startsWith(" ") || lines(end).startsWith("\t") || lines(end).trim.isEmpty) do
      end += 1
    val block = lines.slice(start + 1, end).map(_.trim).filter(_.nonEmpty)
    val keyVals = block.collect {
      case line if line.contains("=") =>
        val i = line.indexOf("=")
        line.take(i).trim -> unquote(line.drop(i + 1).trim)
    }.toMap
    val heartbeatArgs = block.collectFirst {
      case line if line.startsWith("heartbeat(") && line.endsWith(")") =>
        parseCallArgs(line.stripPrefix("heartbeat(").stripSuffix(")"))
    }.getOrElse(Map.empty)
    val quorum = block.collectFirst {
      case line if line.startsWith("quorum(") && line.endsWith(")") =>
        line.stripPrefix("quorum(").stripSuffix(")").trim.toIntOption
    }.flatten
    val cluster = ClusterDecl(
      name = Some(name),
      nodes = keyVals.get("nodes").flatMap(_.toIntOption),
      seedDiscovery = keyVals.get("seedDiscovery"),
      leaderElection = keyVals.get("leaderElection"),
      authTokenFrom = keyVals.get("authTokenFrom"),
      heartbeat = heartbeatArgs,
      quorum = quorum
    )
    val cleaned = (lines.take(start) ++ lines.slice(start, end).map(_ => "") ++ lines.drop(end)).mkString("\n")
    (Some(cluster), cleaned)

  private def parseCallArgs(args: String): Map[String, String] =
    args.split(",").toList.flatMap { part =>
      val i = part.indexOf("=")
      if i < 0 then None else Some(part.take(i).trim -> unquote(part.drop(i + 1).trim))
    }.toMap

  private def unquote(value: String): String =
    if value.length >= 2 && ((value.head == '"' && value.last == '"') || (value.head == '\'' && value.last == '\'')) then
      value.substring(1, value.length - 1)
    else value

  private def parseRemoteHandlers(raw: Map[String, Any]): List[RemoteHandlerDecl] =
    parseNamedRegistry(raw, "remoteHandlers", "remote-handlers").flatMap { (name, mm) =>
      mm.get("function").orElse(mm.get("handler")).collect { case fn: String =>
        RemoteHandlerDecl(
          name         = name,
          function     = fn,
          path         = mm.get("path").collect { case s: String => s },
          requestType  = mm.get("request").orElse(mm.get("requestType")).orElse(mm.get("request-type")).collect { case s: String => s },
          responseType = mm.get("response").orElse(mm.get("responseType")).orElse(mm.get("response-type")).collect { case s: String => s }
        )
      }
    }

  private def mergeSourceRemoteHandlers(manifest: Option[Manifest], sections: List[Section]): Option[Manifest] =
    val discovered = collectSourceRemoteHandlers(sections)
    if discovered.isEmpty then manifest
    else
      val base = manifest.getOrElse(emptyManifest)
      val existing = base.remoteHandlers.map(_.name).toSet
      Some(base.copy(remoteHandlers = base.remoteHandlers ++ discovered.filterNot(h => existing.contains(h.name))))

  private def mergeSourceModels(manifest: Option[Manifest], sections: List[Section]): Option[Manifest] =
    val discovered = collectSourceModels(sections)
    if discovered.isEmpty then manifest
    else
      val base = manifest.getOrElse(emptyManifest)
      val existing = base.models.map(_.name).toSet
      Some(base.copy(models = base.models ++ discovered.filterNot(m => existing.contains(m.name))))

  private def collectSourceModels(sections: List[Section]): List[ModelDef] =
    def fromTree(tree: scala.meta.Tree): List[ModelDef] =
      import scala.meta.*
      def initName(init: Init): String = init.tpe match
        case Type.Name(n)                 => n
        case Type.Select(_, Type.Name(n)) => n
        case other                        => other.syntax.split('.').lastOption.getOrElse(other.syntax)
      def modelAnnotation(mods: List[Mod]): Boolean =
        mods.exists {
          case Mod.Annot(init) => initName(init) == "model"
          case _               => false
        }
      tree.collect {
        case d: Defn.Class if modelAnnotation(d.mods) =>
          val fields = d.ctor.paramClauses.flatMap(_.values).map { param =>
            val typeStr = param.decltpe.map(_.syntax).getOrElse("String")
            ModelField(param.name.value, ModelFieldType.parse(typeStr))
          }
          ModelDef(d.name.value, fields.toList)
      }
    def loop(section: Section): List[ModelDef] =
      section.content.collect {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.map(node => ScalaNode.fold(node)(fromTree)).getOrElse(Nil)
      }.flatten ++ section.subsections.flatMap(loop)
    sections.flatMap(loop)

  private def emptyManifest: Manifest =
    Manifest(
      name = None,
      version = None,
      description = None,
      dependencies = Map.empty,
      exports = Nil,
      targets = Nil,
      routes = Nil,
      pkg = None,
      translations = Map.empty,
      raw = Map.empty
    )

  private def collectSourceRemoteHandlers(sections: List[Section]): List[RemoteHandlerDecl] =
    def fromTree(tree: scala.meta.Tree): List[RemoteHandlerDecl] =
      import scala.meta.*
      def initName(init: Init): String = init.tpe match
        case Type.Name(n)                 => n
        case Type.Select(_, Type.Name(n)) => n
        case other                        => other.syntax.split('.').lastOption.getOrElse(other.syntax)
      def stringArg(init: Init, key: String): Option[String] =
        init.argClauses.flatMap(_.values).collectFirst {
          case Term.Assign(Term.Name(`key`), Lit.String(value)) => value
        }
      def remoteAnnotation(mods: List[Mod]): Option[Init] =
        mods.collectFirst {
          case Mod.Annot(init) if initName(init) == "remote" => init
        }
      def typeText(tpe: Option[Type]): Option[String] = tpe.map(_.syntax)
      def firstParamType(d: Defn.Def): Option[String] =
        d.paramClauseGroups.headOption
          .flatMap(_.paramClauses.headOption)
          .flatMap(_.values.headOption)
          .flatMap(_.decltpe)
          .map(_.syntax)
      tree.collect {
        case d: Defn.Def if remoteAnnotation(d.mods).nonEmpty =>
          val init = remoteAnnotation(d.mods).get
          RemoteHandlerDecl(
            name         = stringArg(init, "name").getOrElse(d.name.value),
            function     = d.name.value,
            path         = stringArg(init, "path"),
            requestType  = stringArg(init, "request").orElse(firstParamType(d)),
            responseType = stringArg(init, "response").orElse(typeText(d.decltpe))
          )
      }
    def loop(section: Section): List[RemoteHandlerDecl] =
      section.content.collect {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.map(node => ScalaNode.fold(node)(fromTree)).getOrElse(Nil)
      }.flatten ++ section.subsections.flatMap(loop)
    sections.flatMap(loop)

  private def parseRemoteSources(raw: Map[String, Any]): List[RemoteSourceDecl] =
    parseNamedRegistry(raw, "remoteSources", "remote-sources").flatMap { (name, mm) =>
      mm.get("source").orElse(mm.get("function")).collect { case src: String =>
        RemoteSourceDecl(
          name       = name,
          source     = src,
          paramsType = mm.get("params").orElse(mm.get("paramsType")).orElse(mm.get("params-type")).collect { case s: String => s },
          itemType   = mm.get("item").orElse(mm.get("itemType")).orElse(mm.get("item-type")).orElse(mm.get("response")).collect { case s: String => s }
        )
      }
    }

  private def parseRemoteBehaviors(raw: Map[String, Any]): List[RemoteBehaviorDecl] =
    parseNamedRegistry(raw, "remoteBehaviors", "remote-behaviors", "behaviors").flatMap { (name, mm) =>
      mm.get("behavior").orElse(mm.get("function")).collect { case behavior: String =>
        RemoteBehaviorDecl(
          name     = name,
          behavior = behavior,
          argsType = mm.get("args").orElse(mm.get("argsType")).orElse(mm.get("args-type")).collect { case s: String => s }
        )
      }
    }

  private def parseNamedRegistry(raw: Map[String, Any], keys: String*): List[(String, Map[String, Any])] =
    keys.iterator.flatMap(raw.get).collectFirst {
      case m: java.util.Map[?, ?] =>
        m.asScala.iterator.collect {
          case (name: String, v: java.util.Map[?, ?]) => name -> stringMap(v)
        }.toList
    }.getOrElse(Nil)

  private def parseDatabases(raw: Map[String, Any]): List[DatabaseDecl] =
    raw.get("databases").collect {
      case m: java.util.Map[?, ?] =>
        m.asScala.iterator.collect {
          case (k: String, v: java.util.Map[?, ?]) =>
            val mm: Map[String, Any] = v.asScala.iterator.collect {
              case (kk: String, vv) => kk -> (vv: Any)
            }.toMap
            mm.get("url").collect { case s: String => s }.map { url =>
              DatabaseDecl(
                name     = k,
                url      = url,
                user     = mm.get("user").collect { case s: String => s },
                password = mm.get("password").collect { case s: String => s },
                driver   = mm.get("driver").collect { case s: String => s }
              )
            }
        }.flatten.toList
    }.getOrElse(Nil)

  private def parseObjectStores(raw: Map[String, Any]): List[ObjectStoreDecl] =
    raw.get("objectStores").orElse(raw.get("object-stores")).collect {
      case m: java.util.Map[?, ?] =>
        m.asScala.iterator.collect {
          case (name: String, v: java.util.Map[?, ?]) =>
            val mm = stringMap(v)
            val server = mm.get("server").collect { case sm: java.util.Map[?, ?] => stringMap(sm) }.getOrElse(Map.empty)
            val valueType = mm.get("type").orElse(mm.get("valueType")).orElse(mm.get("value-type")).collect { case s: String => s }
            valueType.map { tpe =>
              ObjectStoreDecl(
                name      = name,
                valueType = tpe,
                sync      = mm.get("sync").map(_.toString).getOrElse("none"),
                database  = mm.get("database").orElse(server.get("database")).map(_.toString).getOrElse("default"),
                store     = mm.get("store").orElse(server.get("store")).collect { case s: String => s },
                table     = mm.get("table").orElse(server.get("table")).collect { case s: String => s },
                key       = mm.get("key").collect { case s: String => s },
                conflict  = mm.get("conflict").map(_.toString).getOrElse("manual")
              )
            }
        }.flatten.toList
    }.getOrElse(Nil)

  private def parseGraphs(raw: Map[String, Any]): List[GraphDecl] =
    raw.get("graphs").collect {
      case m: java.util.Map[?, ?] =>
        m.asScala.iterator.collect {
          case (name: String, v: java.util.Map[?, ?]) =>
            val mm = stringMap(v)
            GraphDecl(
              name     = name,
              model    = mm.get("model").map(_.toString).getOrElse("property"),
              side     = mm.get("side").map(_.toString).getOrElse("server"),
              backend  = mm.get("backend").map(_.toString).getOrElse("in-memory"),
              uri      = mm.get("uri").orElse(mm.get("url")).collect { case s: String => s },
              user     = mm.get("user").collect { case s: String => s },
              password = mm.get("password").collect { case s: String => s }
            )
        }.toList
    }.getOrElse(Nil)

  private def parseSchemas(raw: Map[String, Any]): List[TypeSchemaDecl] =
    raw.get("schemas").collect {
      case m: java.util.Map[?, ?] =>
        m.asScala.iterator.collect {
          case (typeName: String, v: java.util.Map[?, ?]) =>
            val mm = stringMap(v)
            val fields = mm.get("fields").collect {
              case fm: java.util.Map[?, ?] =>
                fm.asScala.iterator.collect {
                  case (fieldName: String, fieldValue: java.util.Map[?, ?]) =>
                    val ff = stringMap(fieldValue)
                    FieldSchemaDecl(
                      fieldName   = fieldName,
                      storageName = ff.get("name").orElse(ff.get("column")).orElse(ff.get("field")).collect { case s: String => s },
                      aliases     = parseStringList(ff.get("aliases")),
                      default     = ff.get("default").map(parseSchemaDefault),
                      key         = ff.get("key").contains(java.lang.Boolean.TRUE)
                    )
                  case (fieldName: String, storageName: String) =>
                    FieldSchemaDecl(fieldName, storageName = Some(storageName))
                }.toList
            }.getOrElse(Nil)
            TypeSchemaDecl(
              typeName      = typeName,
              fields        = fields,
              rejectUnknown = mm.get("rejectUnknown").orElse(mm.get("reject-unknown")).contains(java.lang.Boolean.TRUE)
            )
        }.toList
    }.getOrElse(Nil)

  private def stringMap(m: java.util.Map[?, ?]): Map[String, Any] =
    m.asScala.iterator.collect { case (k: String, v) => k -> (v: Any) }.toMap

  private def parseStringList(value: Option[Any]): List[String] =
    value.collect {
      case xs: java.util.List[?] => xs.asScala.toList.map(_.toString)
      case s: String => List(s)
    }.getOrElse(Nil)

  private def parseStringMap(value: Option[Any]): Map[String, String] =
    value.collect {
      case m: java.util.Map[?, ?] =>
        m.asScala.iterator.collect { case (k: String, v) => k -> v.toString }.toMap
    }.getOrElse(Map.empty)

  private def parseInt(value: Option[Any]): Option[Int] =
    value.flatMap {
      case n: java.lang.Integer => Some(n.intValue)
      case n: java.lang.Long    => Some(n.intValue)
      case n: java.lang.Short   => Some(n.intValue)
      case n: java.lang.Byte    => Some(n.intValue)
      case s: String            => s.toIntOption
      case other                => other.toString.toIntOption
    }

  private def validateRemoteRegistries(module: Module): Unit =
    module.manifest.foreach { m =>
      val required =
        m.remoteHandlers.map(h => h.function -> s"remoteHandlers.${h.name}") ++
        m.remoteSources.map(s => s.source -> s"remoteSources.${s.name}") ++
        m.remoteBehaviors.map(b => b.behavior -> s"remoteBehaviors.${b.name}")
      if required.nonEmpty then
        val available = collectTopLevelValueNames(module.sections)
        required.collectFirst {
          case (fn, origin) if !available.contains(fn) =>
            throw new RuntimeException(s"$origin references missing local definition '$fn'")
        }
        val availableTypes = collectTopLevelTypeNames(module.sections) ++ builtinRegistryTypes
        val requiredTypes =
          m.remoteHandlers.flatMap(h => List(h.requestType.map(_ -> s"remoteHandlers.${h.name}.request"), h.responseType.map(_ -> s"remoteHandlers.${h.name}.response")).flatten) ++
          m.remoteSources.flatMap(s => List(s.paramsType.map(_ -> s"remoteSources.${s.name}.params"), s.itemType.map(_ -> s"remoteSources.${s.name}.item")).flatten) ++
          m.remoteBehaviors.flatMap(b => b.argsType.map(_ -> s"remoteBehaviors.${b.name}.args").toList)
        requiredTypes.collectFirst {
          case (typeName, origin) if !availableTypes.contains(typeName) =>
            throw new RuntimeException(s"$origin references missing local type '$typeName'")
        }
    }

  private val builtinRegistryTypes: Set[String] =
    Set("Unit", "Any", "String", "Boolean", "Int", "Long", "Double", "Float", "Short", "Byte")

  private def collectTopLevelValueNames(sections: List[Section]): Set[String] =
    def fromTree(tree: scala.meta.Tree): Set[String] =
      import scala.meta.*
      tree.collect {
        case d: Defn.Def => d.name.value
        case v: Defn.Val => v.pats.collect { case Pat.Var(name) => name.value }
        case v: Defn.Var => v.pats.collect { case Pat.Var(name) => name.value }
      }.flatMap {
        case s: String => List(s)
        case xs: List[?] => xs.collect { case s: String => s }
      }.toSet
    def loop(section: Section): Set[String] =
      section.content.collect {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.map(node => ScalaNode.fold(node)(fromTree)).getOrElse(Set.empty)
      }.foldLeft(Set.empty[String])(_ ++ _) ++ section.subsections.flatMap(loop)
    sections.flatMap(loop).toSet

  private def collectTopLevelTypeNames(sections: List[Section]): Set[String] =
    def fromTree(tree: scala.meta.Tree): Set[String] =
      import scala.meta.*
      tree.collect {
        case d: Defn.Class => d.name.value
        case d: Defn.Trait => d.name.value
        case d: Defn.Enum  => d.name.value
        case d: Defn.Type  => d.name.value
      }.toSet
    def loop(section: Section): Set[String] =
      section.content.collect {
        case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
          cb.tree.map(node => ScalaNode.fold(node)(fromTree)).getOrElse(Set.empty)
      }.foldLeft(Set.empty[String])(_ ++ _) ++ section.subsections.flatMap(loop)
    sections.flatMap(loop).toSet

  private def parseSchemaDefault(value: Any): SchemaDefault = value match
    case null => SchemaDefault.NullValue
    case b: java.lang.Boolean => SchemaDefault.Bool(b.booleanValue)
    case n: java.lang.Integer => SchemaDefault.IntValue(n.longValue)
    case n: java.lang.Long => SchemaDefault.IntValue(n.longValue)
    case n: java.lang.Short => SchemaDefault.IntValue(n.longValue)
    case n: java.lang.Byte => SchemaDefault.IntValue(n.longValue)
    case n: java.math.BigInteger => SchemaDefault.IntValue(n.longValue)
    case n: java.lang.Float => SchemaDefault.DoubleValue(n.doubleValue)
    case n: java.lang.Double => SchemaDefault.DoubleValue(n.doubleValue)
    case n: java.math.BigDecimal => SchemaDefault.DoubleValue(n.doubleValue)
    case other => SchemaDefault.StringValue(other.toString)

  // ─── Markdown-hosted content snapshot ───────────────────────────

  private case class ParsedHeadingText(title: String, explicitId: Option[String], attrs: Map[String, ContentValue])

  private val HeadingAttrSuffix = """^(.*?)(?:\s*\{([^}]*)\})\s*$""".r
  private val MetaDirective = """(?s)<!--\s*@meta\s+(.+?)\s*-->""".r

  private def buildDocumentContent(doc: CmDocument, manifest: Option[Manifest]): DocumentContent =
    val usedIds = collection.mutable.Map.empty[String, Int]
    val explicitIds = collection.mutable.Set.empty[String]

    def nextId(title: String, explicit: Option[String]): String =
      explicit match
        case Some(id) =>
          if explicitIds.contains(id) then
            throw new RuntimeException(s"Duplicate explicit content id '$id'")
          explicitIds += id
          usedIds(id) = math.max(1, usedIds.getOrElse(id, 0))
          id
        case None =>
          val base0 = slugify(title)
          val base  = if base0.nonEmpty then base0 else "section"
          val count = usedIds.getOrElse(base, 0) + 1
          usedIds(base) = count
          if count == 1 then base else s"$base-$count"

    case class Frame(
      level: Int,
      section: SectionContent,
      children: ListBuffer[SectionContent]
    )

    val roots = ListBuffer.empty[SectionContent]
    val blocks = ListBuffer.empty[ContentBlock]
    val stack = Stack.empty[Frame]
    var pendingAttrs = Map.empty[String, ContentValue]

    def flush(toLevel: Int): Unit =
      while stack.nonEmpty && stack.top.level >= toLevel do
        val f = stack.pop()
        val complete = f.section.copy(children = f.children.toList)
        if stack.nonEmpty then stack.top.children += complete else roots += complete

    def addBlocks(bs: List[ContentBlock]): Unit =
      if bs.nonEmpty then
        val merged = if pendingAttrs.isEmpty then bs else bs.head match
          case ContentBlock.Paragraph(inlines, attrs) =>
            ContentBlock.Paragraph(inlines, mergeAttrs(attrs, pendingAttrs)) :: bs.tail
          case ContentBlock.BulletList(items, attrs) =>
            ContentBlock.BulletList(items, mergeAttrs(attrs, pendingAttrs)) :: bs.tail
          case ContentBlock.OrderedList(items, start, attrs) =>
            ContentBlock.OrderedList(items, start, mergeAttrs(attrs, pendingAttrs)) :: bs.tail
          case ContentBlock.Image(src, alt, title, attrs) =>
            ContentBlock.Image(src, alt, title, mergeAttrs(attrs, pendingAttrs)) :: bs.tail
          case ContentBlock.Table(headers, rows, alignments, attrs) =>
            ContentBlock.Table(headers, rows, alignments, mergeAttrs(attrs, pendingAttrs)) :: bs.tail
          case ContentBlock.Embedded(lang, source, kind, data, attrs) =>
            ContentBlock.Embedded(lang, source, kind, data, mergeAttrs(attrs, pendingAttrs)) :: bs.tail
        pendingAttrs = Map.empty
        if stack.nonEmpty then
          val top = stack.top
          stack.update(0, top.copy(section = top.section.copy(blocks = top.section.blocks ++ merged)))
        else blocks ++= merged

    var node = doc.getFirstChild
    while node != null do
      node match
        case h: CmHeading =>
          flush(h.getLevel)
          val parsed = parseHeadingText(textOf(h))
          val id = nextId(parsed.title, parsed.explicitId)
          val attrs = mergeAttrs(parsed.attrs, pendingAttrs)
          pendingAttrs = Map.empty
          stack.push(Frame(
            level = h.getLevel,
            section = SectionContent(id, h.getLevel, parsed.title, attrs, Nil, Nil),
            children = ListBuffer.empty
          ))
        case html: CmHtmlBlock =>
          parseMetaDirective(html.getLiteral) match
            case Some(attrs) => pendingAttrs = mergeAttrs(pendingAttrs, attrs)
            case None        => ()
        case other =>
          addBlocks(contentBlocks(other))
      node = node.getNext

    flush(0)
    val manifestValue = manifest
      .map(m => contentValueFromAny(m.raw))
      .getOrElse(ContentValue.MapV(Map.empty))
    val topTitle = roots.headOption.map(_.title)
    DocumentContent(
      manifest = manifestValue,
      title = topTitle,
      description = manifest.flatMap(_.description),
      attrs = Map.empty,
      sections = roots.toList,
      blocks = blocks.toList
    )

  private def parseHeadingText(text: String): ParsedHeadingText =
    text match
      case HeadingAttrSuffix(rawTitle, rawAttrs) if rawAttrs != null && rawAttrs.trim.nonEmpty =>
        val attrs0 = parseContentAttrs(rawAttrs)
        val explicit = attrs0.get("id").collect { case ContentValue.Str(s) => s }
        ParsedHeadingText(rawTitle.trim, explicit, attrs0 - "id")
      case _ => ParsedHeadingText(text.trim, None, Map.empty)

  private def parseContentAttrs(raw: String): Map[String, ContentValue] =
    raw.split("\\s+").iterator.filter(_.nonEmpty).foldLeft(Map.empty[String, ContentValue]) { (acc, token) =>
      if token.startsWith("#") && token.length > 1 then acc.updated("id", ContentValue.Str(token.drop(1)))
      else if token.startsWith(".") && token.length > 1 then
        val cls = token.drop(1)
        val old = acc.get("class").collect { case ContentValue.Str(s) => s }.filter(_.nonEmpty)
        acc.updated("class", ContentValue.Str((old.toList :+ cls).mkString(" ")))
      else
        token.indexOf('=') match
          case idx if idx > 0 =>
            val key = token.substring(0, idx)
            val value = token.substring(idx + 1).stripPrefix("\"").stripSuffix("\"")
            acc.updated(key, parseScalarContentValue(value))
          case _ => acc.updated(token, ContentValue.Bool(true))
    }

  private def parseMetaDirective(source: String): Option[Map[String, ContentValue]] =
    source.trim match
      case MetaDirective(raw) => Some(parseContentAttrs(raw))
      case _                  => None

  private def parseScalarContentValue(value: String): ContentValue =
    value match
      case "true"  => ContentValue.Bool(true)
      case "false" => ContentValue.Bool(false)
      case "null"  => ContentValue.NullV
      case other   => other.toDoubleOption.map(ContentValue.Num.apply).getOrElse(ContentValue.Str(other))

  private def mergeAttrs(base: Map[String, ContentValue], next: Map[String, ContentValue]): Map[String, ContentValue] =
    next.foldLeft(base) {
      case (acc, ("class", ContentValue.Str(cls))) =>
        val old = acc.get("class").collect { case ContentValue.Str(s) => s }.filter(_.nonEmpty)
        acc.updated("class", ContentValue.Str((old.toList :+ cls).mkString(" ")))
      case (acc, (k, v)) => acc.updated(k, v)
    }

  private def slugify(text: String): String =
    text.toLowerCase
      .replaceAll("[^a-z0-9]+", "-")
      .stripPrefix("-")
      .stripSuffix("-")

  private def contentBlocks(node: CmNode): List[ContentBlock] = node match
    case p: CmParagraph =>
      if asImports(p).nonEmpty then Nil
      else
        paragraphImage(p).map(List(_)).getOrElse {
          val inlines = contentInlines(p)
          if inlines.nonEmpty then List(ContentBlock.Paragraph(inlines)) else Nil
        }
    case l: CmBulletList =>
      List(ContentBlock.BulletList(listItemBlocks(l)))
    case l: CmOrderedList =>
      List(ContentBlock.OrderedList(listItemBlocks(l), 1))
    case t: CmTableBlock =>
      List(tableBlock(t))
    case f: CmFenced =>
      val info = Option(f.getInfo).getOrElse("").trim
      val lang = info.takeWhile(!_.isWhitespace).toLowerCase
      val tailAttrs = info.drop(lang.length).trim
      val source = Option(f.getLiteral).getOrElse("")
      List(embeddedContentBlock(lang, source, parseFenceContentAttrs(tailAttrs)))
    case i: CmIndentedCode =>
      List(embeddedContentBlock("", Option(i.getLiteral).getOrElse(""), Map.empty))
    case html: CmHtmlBlock =>
      if parseMetaDirective(html.getLiteral).isDefined then Nil else Nil
    case _ => Nil

  private def tableBlock(table: CmTableBlock): ContentBlock.Table =
    val rows = tableRows(table)
    val headers = rows.headOption.getOrElse(Nil)
    ContentBlock.Table(
      headers    = headers.map(cell => contentInlines(cell)),
      rows       = rows.drop(1).map(_.map(cell => contentInlines(cell))),
      alignments = headers.map(tableAlignment),
      attrs      = Map.empty
    )

  private def tableRows(table: CmTableBlock): List[List[CmTableCell]] =
    val rows = ListBuffer.empty[List[CmTableCell]]
    def walk(parent: CmNode): Unit =
      var child = parent.getFirstChild
      while child != null do
        child match
          case row: CmTableRow =>
            val cells = ListBuffer.empty[CmTableCell]
            var cell = row.getFirstChild
            while cell != null do
              cell match
                case c: CmTableCell => cells += c
                case _              => ()
              cell = cell.getNext
            rows += cells.toList
          case other =>
            walk(other)
        child = child.getNext
    walk(table)
    rows.toList

  private def tableAlignment(cell: CmTableCell): String =
    val raw = Option(cell.getAlignment).map(_.name().toLowerCase(java.util.Locale.ROOT)).getOrElse("default")
    raw match
      case "left" | "center" | "right" => raw
      case _                           => "default"

  private def paragraphImage(p: CmParagraph): Option[ContentBlock.Image] =
    val child = p.getFirstChild
    child match
      case img: CmImage if child.getNext == null =>
        Some(ContentBlock.Image(
          src = Option(img.getDestination).getOrElse(""),
          alt = inlinePlainText(contentInlines(img)),
          title = Option(img.getTitle).filter(_.nonEmpty)
        ))
      case _ => None

  private def listItemBlocks(list: CmNode): List[List[ContentBlock]] =
    val out = ListBuffer.empty[List[ContentBlock]]
    var item = list.getFirstChild
    while item != null do
      item match
        case li: CmListItem =>
          val blocks = ListBuffer.empty[ContentBlock]
          var child = li.getFirstChild
          while child != null do
            blocks ++= contentBlocks(child)
            child = child.getNext
          out += blocks.toList
        case _ => ()
      item = item.getNext
    out.toList

  private def embeddedContentBlock(lang: String, source: String, attrs: Map[String, ContentValue]): ContentBlock =
    val kind =
      if Set("yaml", "yml", "json", "toml").contains(lang) then EmbeddedKind.StructuredData
      else if Lang.isParseable(lang) || Lang.isOpaqueExec(lang) || Lang.isSql(lang) then EmbeddedKind.Executable
      else if Lang.isStringBlock(lang) then EmbeddedKind.StringBlock
      else EmbeddedKind.Opaque
    val data =
      if kind == EmbeddedKind.StructuredData then parseStructuredContentValue(lang, source) else None
    ContentBlock.Embedded(lang, source, kind, data, attrs)

  private def parseStructuredContentValue(lang: String, source: String): Option[ContentValue] =
    try
      lang match
        case "yaml" | "yml" =>
          Some(contentValueFromAny(SimpleYaml.load[Any](source)))
        case "json" =>
          Some(contentValueFromUjson(ujson.read(source)))
        case _ => None
    catch case _: Throwable => None

  private def contentValueFromAny(value: Any): ContentValue = value match
    case null => ContentValue.NullV
    case s: String => ContentValue.Str(s)
    case b: java.lang.Boolean => ContentValue.Bool(b.booleanValue)
    case b: Boolean => ContentValue.Bool(b)
    case n: java.lang.Integer => ContentValue.Num(n.doubleValue)
    case n: java.lang.Long => ContentValue.Num(n.doubleValue)
    case n: java.lang.Short => ContentValue.Num(n.doubleValue)
    case n: java.lang.Byte => ContentValue.Num(n.doubleValue)
    case n: java.lang.Float => ContentValue.Num(n.doubleValue)
    case n: java.lang.Double => ContentValue.Num(n.doubleValue)
    case n: java.math.BigInteger => ContentValue.Num(n.doubleValue)
    case n: java.math.BigDecimal => ContentValue.Num(n.doubleValue)
    case m: java.util.Map[?, ?] =>
      ContentValue.MapV(m.asScala.iterator.map { case (k, v) => k.toString -> contentValueFromAny(v) }.toMap)
    case xs: java.util.List[?] =>
      ContentValue.ListV(xs.asScala.toList.map(contentValueFromAny))
    case m: Map[?, ?] =>
      ContentValue.MapV(m.iterator.map { case (k, v) => k.toString -> contentValueFromAny(v) }.toMap)
    case xs: Iterable[?] =>
      ContentValue.ListV(xs.toList.map(contentValueFromAny))
    case other => ContentValue.Str(other.toString)

  private def contentValueFromUjson(value: ujson.Value): ContentValue = value match
    case ujson.Str(s) => ContentValue.Str(s)
    case ujson.Num(n) => ContentValue.Num(n)
    case ujson.Bool(b) => ContentValue.Bool(b)
    case ujson.Null => ContentValue.NullV
    case arr: ujson.Arr => ContentValue.ListV(arr.value.toList.map(contentValueFromUjson))
    case obj: ujson.Obj => ContentValue.MapV(obj.value.iterator.map { case (k, v) => k -> contentValueFromUjson(v) }.toMap)

  private def contentInlines(node: CmNode): List[ContentInline] =
    val out = ListBuffer.empty[ContentInline]
    def walk(n: CmNode): Unit =
      n match
        case t: CmText =>
          out ++= splitExprInlines(t.getLiteral)
        case c: CmCode =>
          out += ContentInline.Code(c.getLiteral)
        case e: CmEmphasis =>
          out += ContentInline.Emphasis(contentInlines(e))
        case s: CmStrongEmphasis =>
          out += ContentInline.Strong(contentInlines(s))
        case l: CmLink =>
          out += ContentInline.Link(contentInlines(l), Option(l.getDestination).getOrElse(""), Option(l.getTitle).filter(_.nonEmpty))
        case img: CmImage =>
          out += ContentInline.Text(inlinePlainText(contentInlines(img)))
        case _: CmSoftLineBreak =>
          out += ContentInline.Text(" ")
        case _: CmHardLineBreak =>
          out += ContentInline.Text("\n")
        case html: CmHtmlInline =>
          out += ContentInline.Text(html.getLiteral)
        case _ =>
          var child = n.getFirstChild
          while child != null do
            walk(child)
            child = child.getNext
    var child = node.getFirstChild
    while child != null do
      walk(child)
      child = child.getNext
    out.toList

  private def splitExprInlines(text: String): List[ContentInline] =
    val out = ListBuffer.empty[ContentInline]
    var i = 0
    while i < text.length do
      val start = text.indexOf("${", i)
      if start < 0 then
        if i < text.length then out += ContentInline.Text(text.substring(i))
        i = text.length
      else
        if start > i then out += ContentInline.Text(text.substring(i, start))
        val end = text.indexOf('}', start + 2)
        if end < 0 then
          out += ContentInline.Text(text.substring(start))
          i = text.length
        else
          out += ContentInline.Expr(text.substring(start + 2, end).trim)
          i = end + 1
    out.toList.filter {
      case ContentInline.Text("") => false
      case _ => true
    }

  private def inlinePlainText(inlines: List[ContentInline]): String =
    inlines.map {
      case ContentInline.Text(value) => value
      case ContentInline.Emphasis(children) => inlinePlainText(children)
      case ContentInline.Strong(children) => inlinePlainText(children)
      case ContentInline.Code(value) => value
      case ContentInline.Link(label, _, _) => inlinePlainText(label)
      case ContentInline.Expr(source) => s"$${$source}"
    }.mkString

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

  private def extractSections(
      doc:              CmDocument,
      mdLineToFileLine: Int => Int,
      skipInitialParse: Boolean
  ): List[Section] =
    val roots = ListBuffer[Section]()
    val stack = Stack[Frame]()

    // Pop all frames whose level >= toLevel and wire them into their parents.
    def flush(toLevel: Int): Unit =
      while stack.nonEmpty && stack.top.level >= toLevel do
        val f = stack.pop()
        val s = Section(f.heading, f.content.toList, f.subsections.toList)
        if stack.nonEmpty then stack.top.subsections += s else roots += s

    val preContent = ListBuffer[Content]()  // content before the first heading

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
          toContents(other, mdLineToFileLine, skipInitialParse).foreach { c =>
            if stack.nonEmpty then stack.top.content += c
            else preContent += c
          }
      node = node.getNext

    flush(0)
    // Headingless scripts: all content landed in preContent.  Wrap it in a
    // synthetic unnamed section so JvmGen and other consumers see the blocks.
    if roots.isEmpty && preContent.nonEmpty then
      roots += Section(Heading(0, ""), preContent.toList, Nil)
    roots.toList

  // ─── Node → Content ──────────────────────────────────────────────

  private def toContents(node: CmNode, mdLineToFileLine: Int => Int, skipInitialParse: Boolean): List[Content] = node match
    case f: CmFenced =>
      val info     = Option(f.getInfo).getOrElse("").trim
      val lang     = info.takeWhile(!_.isWhitespace).toLowerCase
      val tailAttrs = info.drop(lang.length).trim
      val attrs    = parseFenceAttrs(tailAttrs)
      val src  = Option(f.getLiteral).getOrElse("")
      val (tree, parseError) =
        if Lang.isParseable(lang) && !skipInitialParse then parseScalaWithDiagnostic(src)
        else (None, None)
      // CommonMark's source span on a `FencedCodeBlock` covers the whole
      // block including the opening + closing fence rows.  The first line
      // of code INSIDE the fence is one row past the fence open, so the
      // 0-indexed file-level line of `src`'s first row is
      // `fenceStartLine + 1` (translated through the mdSrc→file mapping).
      val fenceStart0 =
        val spans = f.getSourceSpans
        if spans != null && !spans.isEmpty then spans.get(0).getLineIndex
        else 0
      val lineOffset = mdLineToFileLine(fenceStart0 + 1)
      List(Content.CodeBlock(lang, src, tree, None, parseError, lineOffset, attrs))

    case p: CmParagraph =>
      val imports = asImports(p)
      if imports.nonEmpty then imports
      else
        val text = textOf(p)
        if text.nonEmpty then List(Content.Prose(text)) else Nil

    case l: CmBulletList  => List(Content.DataList(listItems(l), ordered = false))
    case l: CmOrderedList => List(Content.DataList(listItems(l), ordered = true))
    case _                => Nil

  private def asImports(para: CmParagraph): List[Content.Import] =
    val imports = ListBuffer.empty[Content.Import]
    var invalid = false
    var child = para.getFirstChild
    while child != null do
      child match
        case link: CmLink =>
          parseImportLink(link) match
            case Some(imp) => imports += imp
            case None      => invalid = true
        case text: CmText =>
          if Option(text.getLiteral).exists(_.trim.nonEmpty) then invalid = true
        case _: CmSoftLineBreak | _: CmHardLineBreak =>
          ()
        case other =>
          if textOf(other).trim.nonEmpty then invalid = true
      child = child.getNext
    if invalid || imports.isEmpty then Nil else imports.toList

  private def parseImportLink(link: CmLink): Option[Content.Import] =
    val path = Option(link.getDestination).getOrElse("").trim
    if path.isEmpty || path.startsWith("#") || path.startsWith("toolkit:") then return None
    // `Name` or `Name as Alias` or `Name from Module` - comma-separated.
    // Whitespace around the keyword is required to avoid substring collisions.
    val asPattern   = """^([A-Za-z_][\w]*)\s+as\s+([A-Za-z_][\w]*)$""".r
    val fromPattern = """^([A-Za-z_][\w]*)\s+from\s+([A-Za-z_][\w]*)$""".r
    val bindings = textOf(link).split(",").map(_.trim).filter(_.nonEmpty).map { s =>
      s match
        case asPattern(name, alias)    => ImportBinding(name, alias = Some(alias))
        case fromPattern(name, srcMod) => ImportBinding(name, fromModule = Some(srcMod))
        case bare                      => ImportBinding(bare)
    }.toList
    if bindings.nonEmpty then Some(Content.Import(path, bindings)) else None

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

  /** Rewrite route metadata annotations into an ordinary marker call that
   *  scalameta and the runtimes can consume:
   *
   *    @openapi(summary = "List users", tags = List("users"))
   *    route("GET", "/users") { ... }
   *
   *  becomes:
   *
   *    openapi("List users", "", List("users"), false, List())
   *    route("GET", "/users") { ... }
   *
   *  Scala 3 annotations cannot be applied directly to a standalone route()
   *  expression, so this keeps the user-facing syntax while preserving a
   *  plain Scala-shaped tree for downstream parsing.
   */
  private[parser] def preprocessOpenApiAnnotations(code: String): String =
    if !code.contains("@openapi") then return code

    def parenDelta(s: String): Int =
      s.count(_ == '(') - s.count(_ == ')')

    def splitTopLevelArgs(s: String): List[String] =
      val out = ListBuffer.empty[String]
      val cur = new StringBuilder
      var depth = 0
      var inString = false
      var escape = false
      s.foreach { ch =>
        if inString then
          cur.append(ch)
          if escape then escape = false
          else if ch == '\\' then escape = true
          else if ch == '"' then inString = false
        else ch match
          case '"' =>
            inString = true
            cur.append(ch)
          case '(' | '[' | '{' =>
            depth += 1
            cur.append(ch)
          case ')' | ']' | '}' =>
            depth -= 1
            cur.append(ch)
          case ',' if depth == 0 =>
            out += cur.toString.trim
            cur.clear()
          case other =>
            cur.append(other)
      }
      val tail = cur.toString.trim
      if tail.nonEmpty then out += tail
      out.toList

    def normalizeArgs(raw: String): String =
      val defaults = collection.mutable.LinkedHashMap(
        "summary" -> "\"\"",
        "description" -> "\"\"",
        "tags" -> "List()",
        "deprecated" -> "false",
        "security" -> "List()"
      )
      val positional = ListBuffer.empty[String]
      splitTopLevelArgs(raw).foreach { arg =>
        val eq = arg.indexOf('=')
        val colon = arg.indexOf(':')
        val sep =
          if eq >= 0 && (colon < 0 || eq < colon) then eq
          else if colon >= 0 then colon
          else -1
        if sep > 0 then
          val key = arg.take(sep).trim
          val value = arg.drop(sep + 1).trim
          if defaults.contains(key) && value.nonEmpty then defaults(key) = value
        else if arg.nonEmpty then positional += arg
      }
      val ordered = defaults.keys.toList.zipWithIndex.map { case (key, idx) =>
        if idx < positional.length then positional(idx) else defaults(key)
      }
      ordered.mkString(", ")

    val lines = code.split("\n", -1).toList
    val out = new StringBuilder
    var i = 0
    while i < lines.length do
      val line = lines(i)
      val trimmed = line.trim
      if trimmed.startsWith("@openapi") then
        val indent = line.takeWhile(_.isWhitespace)
        val collected = ListBuffer(line)
        var delta = parenDelta(line)
        var j = i + 1
        while delta > 0 && j < lines.length do
          collected += lines(j)
          delta += parenDelta(lines(j))
          j += 1
        val nextNonEmpty = lines.drop(j).find(_.trim.nonEmpty).map(_.trim)
        if nextNonEmpty.exists(_.startsWith("route(")) then
          val text = collected.mkString("\n").trim
          val start = text.indexOf('(')
          val end = text.lastIndexOf(')')
          val args = if start >= 0 && end > start then normalizeArgs(text.substring(start + 1, end)) else normalizeArgs("")
          out.append(indent).append("openapi(").append(args).append(")\n")
          i = j
        else
          collected.foreach(l => out.append(l).append("\n"))
          i = j
      else
        out.append(line)
        if i < lines.length - 1 then out.append("\n")
        i += 1
    out.toString

  /** Preprocess `extern` surface forms into scalameta-friendly Scala 3:
   *
   *    extern def foo(...): T          → def foo(...): T = __extern__
   *    extern class Name[T]:           → class Name[T]:
   *      def m(...): T                 →   def m(...): T = __extern__
   *      val v: T                      →   val v: T = __extern__.asInstanceOf[T]
   *    extern object Name:             → object Name:
   *      def m(...): T                 →   def m(...): T = __extern__
   *      val v: T                      →   val v: T = __extern__.asInstanceOf[T]
   *
   *  The `extern` modifier isn't a Scala 3 keyword; this preprocess step
   *  is the same pattern as `preprocessEffects` for `effect Name:` blocks
   *  — surface syntax we want, expanded into scalameta-friendly form
   *  before parsing.  The codegens recognise `__extern__` stubs as
   *  body-less declarations (`EffectAnalysis.isExternDef`).
   *
   *  For `extern class` / `extern object` we strip the `extern` modifier
   *  and rewrite every body-less `def` / `val` inside the block (detected
   *  by indentation) to use `__extern__` as a stub right-hand side.
   *
   *  Original: Stage 5+/A.6 (Б-1); class/object forms added 2026-05-19. */
  private val externDefPat   = """^(\s*)extern\s+def\s+(.+)$""".r
  private val externTypePat  = """^(\s*)extern\s+(class|object|trait)\s+(.+?:)\s*$""".r
  // Body-less member (no `=`, doesn't end with a brace/colon) — needs a stub.
  private val bodylessDefPat = """^(\s*)def\s+(.+)$""".r
  private val bodylessValPat = """^(\s*)val\s+([^=]+):\s*([^=].*)$""".r
  private[parser] def preprocessExtern(code: String): String =
    if !code.contains("extern") then return code
    val lines = code.linesIterator.toArray
    if !lines.exists(l =>
         externDefPat.findFirstIn(l).isDefined ||
         externTypePat.findFirstIn(l).isDefined
       ) then return code

    val result = new StringBuilder()

    // Track active `extern class/object` blocks by their declaration-line
    // indentation; any def/val whose indentation is STRICTLY GREATER than
    // a tracked one belongs to that block and gets `__extern__`-rewritten.
    val externBlockIndents = scala.collection.mutable.Stack.empty[Int]

    def indentOf(s: String): Int = s.takeWhile(_ == ' ').length
    def isInsideExternBlock(ind: Int): Boolean =
      externBlockIndents.headOption.exists(decl => ind > decl)

    var i = 0
    while i < lines.length do
      val line = lines(i)

      // Pop the indent stack when we leave the block (current line dedents
      // back to the declaration line's level or further).  Blank lines do
      // not pop — they're allowed inside a block.
      if line.strip.nonEmpty then
        val ind = indentOf(line)
        while externBlockIndents.nonEmpty && ind <= externBlockIndents.head do
          externBlockIndents.pop()

      externTypePat.findFirstMatchIn(line) match
        case Some(m) =>
          // `extern class Foo[T]:` / `extern object Foo:` — strip modifier,
          // keep the rest; mark the block open by remembering its indent.
          val indent = m.group(1)
          val kind   = m.group(2)
          val head   = m.group(3)
          result.append(indent).append(kind).append(" ").append(head).append("\n")
          externBlockIndents.push(indent.length)
          i += 1
        case None =>
          externDefPat.findFirstMatchIn(line) match
            case Some(m) =>
              val indent = m.group(1)
              val sigBuf = new StringBuilder(m.group(2).stripTrailing)
              // Multi-line extern def: collect continuation until paren depth = 0.
              var depth = sigBuf.count(_ == '(') - sigBuf.count(_ == ')')
              while depth > 0 && i + 1 < lines.length do
                i += 1
                val cont = lines(i).strip
                sigBuf.append(" ").append(cont)
                depth += cont.count(_ == '(') - cont.count(_ == ')')
              result.append(indent).append("def ").append(sigBuf.toString.stripTrailing)
                    .append(" = __extern__\n")
              i += 1
            case None if isInsideExternBlock(indentOf(line)) && line.strip.nonEmpty =>
              // Inside an `extern class/object` block — body-less def/val
              // members get `= __extern__` stubs so scalameta accepts them.
              line match
                case bodylessDefPat(indent, sigText) if !sigText.contains("=") =>
                  // Multi-line def signature handling.
                  val sigBuf = new StringBuilder(sigText.stripTrailing)
                  var depth = sigBuf.count(_ == '(') - sigBuf.count(_ == ')')
                  while depth > 0 && i + 1 < lines.length do
                    i += 1
                    val cont = lines(i).strip
                    sigBuf.append(" ").append(cont)
                    depth += cont.count(_ == '(') - cont.count(_ == ')')
                  result.append(indent).append("def ").append(sigBuf.toString.stripTrailing)
                        .append(" = __extern__\n")
                  i += 1
                case bodylessValPat(indent, name, tpe) =>
                  // Strip a trailing `// ...` line comment from the
                  // captured type so the `asInstanceOf[T]` we splice in
                  // doesn't end up as `asInstanceOf[String  // foo]`.
                  // Doc comments tied to vals in `extern class` blocks
                  // are common; preserve them by re-appending after the
                  // synthesised stub.
                  val cmtIdx   = tpe.indexOf("//")
                  val cleanTpe = (if cmtIdx >= 0 then tpe.substring(0, cmtIdx) else tpe).strip
                  val trailing = if cmtIdx >= 0 then "  " + tpe.substring(cmtIdx) else ""
                  result.append(indent).append("val ").append(name.strip)
                        .append(": ").append(cleanTpe)
                        .append(" = __extern__.asInstanceOf[").append(cleanTpe).append("]")
                        .append(trailing).append("\n")
                  i += 1
                case _ =>
                  result.append(line).append("\n")
                  i += 1
            case None =>
              result.append(line).append("\n")
              i += 1
    result.toString

  /** Strip Markdown-link-style list-form imports that std/ modules write
   *  *inside* scalascript code blocks:
   *
   *    [ToolResult, ResourceResult, Transport](./types.ssc)
   *    [List, Map, Card as UICard](./std/collections.ssc)
   *
   *  These are documented in SPEC.md §3.2 as top-level selective imports
   *  (Markdown links surfaced as `Content.Import` AST nodes), but the std/
   *  library convention embeds them in fenced code blocks for grouping
   *  with the code that uses them.  Inside a fence, the Markdown parser
   *  doesn't see them — scalameta does, and `[X, Y](z)` isn't valid Scala.
   *
   *  Strategy: comment the line(s) out so scalameta sees them as Scala
   *  comments.  The names they introduce are resolved upstream by the
   *  linker (FQN-rewrite via `Linker.rewriteExpr`) or by the typer's
   *  permissive default (Any fallback for undeclared names); strict-mode
   *  flags them, which is the correct surface-level diagnostic.
   *
   *  Multi-line spans (continuation after the comma) are handled by
   *  tracking bracket depth across lines.  Lines that are not list-form
   *  imports — including ordinary Scala `[...](...)` such as type-argument
   *  applications — pass through untouched. */

  /** Translate path-shaped `import` statements:
   *
   *    import std/mapreduce/dataset.{Dataset}        →
   *    import std.mapreduce.dataset.{Dataset}
   *
   *  Some std/ modules (and external libraries written by people coming
   *  from Python / ES module / Rust paths) use `/` as the segment
   *  separator inside `import` clauses.  scalameta strictly expects `.`,
   *  so the `/`-form fails parsing.  This preprocessor rewrites the
   *  segment separator BEFORE the `.{` member selector, leaving the
   *  selector contents untouched.
   *
   *  Conservative: only rewrites lines starting with `import` (allowing
   *  leading whitespace).  String/comment-position detection isn't
   *  needed because `import` outside a top-level position is rare enough
   *  to be hand-fixed; this preprocessor solves the import-statement
   *  case which is what std/ relies on. */
  /** Scala keywords that can appear directly before `[` but cannot take type parameters.
   *  Used by `preprocessListLiterals` to distinguish `else [a, b]` (list literal) from
   *  `List[Int]` (type application). */
  private object preprocessListLiterals:
    /** Scala keywords that can appear directly before `[` but cannot take type parameters. */
    val scalaKeywords: Set[String] = Set(
      "else", "then", "yield", "return", "throw", "if", "do", "while", "for",
      "match", "case", "new", "true", "false", "null", "this", "super",
      "val", "var", "def", "type", "with", "extends", "derives",
      "catch", "finally", "try", "to", "by", "in", "import", "export",
      "given", "using", "sealed", "abstract", "final", "override",
      "private", "protected", "implicit", "lazy", "inline", "opaque"
    )
    /** Operator tokens that introduce expressions (not type parameters). */
    val exprOperators: Set[String] = Set("=>", "=>>", "=", "<-", ":")
    /** True for ASCII characters that can appear in Scala operator method names. */
    def isOpChar(c: Char): Boolean =
      "~!@#%^&*-+=<>?/\\|:".indexOf(c) >= 0

  /** Rewrite `[a, b, c]` → `List(a, b, c)` and `[k -> v, ...]` → `Map(k -> v, ...)`
   *  in SSC code blocks, before Scalameta parses them.
   *
   *  Disambiguation rule: `[` is treated as a list/map literal when NOT preceded by
   *  a non-keyword identifier, `)`, or `]`. Scala keywords (else, then, yield, …)
   *  can precede list literals and are excluded from the "type-parameter" path.
   *  Map vs List: if the content contains `->` at bracket-depth 0 → Map; else → List.
   *  Handles strings (single/double/triple-quoted), line `//` and block `/* */` comments. */
  private[parser] def preprocessListLiterals(code: String): String =
    if !code.contains('[') then return code
    val in  = code.toCharArray
    val n   = in.length
    val out = new StringBuilder(n + 32)
    var i   = 0
    var changed = false

    def skipStringFrom(start: Int, q: Char): Int =
      var j = start + 1
      var esc = false
      while j < n && (esc || in(j) != q) do
        esc = !esc && in(j) == '\\'
        j += 1
      if j < n then j + 1 else j // position after closing quote

    def skipTripleFrom(start: Int): Int =
      var j = start + 3
      while j + 2 < n && !(in(j) == '"' && in(j+1) == '"' && in(j+2) == '"') do j += 1
      if j + 2 < n then j + 3 else j

    // Interpolation-aware string skip. For an interpolated string `s"…${expr}…"`, a
    // `"` inside a `${…}` splice is NOT the terminator — the splice can embed its own
    // string literals, e.g. `s"${xs.mkString("[", ", ", "]")}"`. Skipping the splice
    // keeps that inner `"`, and any `[` inside it, from leaking out and being
    // mis-rewritten as a list literal (bug: `[` in a nested interpolation string).
    // `start` points at the opening quote; returns the index after the closing quote.
    // Mutually recursive with `skipSpliceFrom` (splices may nest strings).
    def skipInterpStringFrom(start: Int): Int =
      var j = start + 1
      var esc = false
      var done = false
      while j < n && !done do
        val c = in(j)
        if esc then { esc = false; j += 1 }
        else if c == '\\' then { esc = true; j += 1 }
        else if c == '"' then { j += 1; done = true }              // closing quote
        else if c == '$' && j + 1 < n && in(j + 1) == '{' then
          j = skipSpliceFrom(j + 1)                                 // skip `${ … }`
        else j += 1
      j

    // `braceStart` points at the `{` of a `${…}` splice; returns the index after the
    // matching `}`, skipping nested strings / char literals / braces within the splice.
    def skipSpliceFrom(braceStart: Int): Int =
      var j = braceStart + 1
      var depth = 1
      while j < n && depth > 0 do
        in(j) match
          case '"' if j + 2 < n && in(j+1) == '"' && in(j+2) == '"' => j = skipTripleFrom(j)
          case '"'  => j = skipInterpStringFrom(j)   // nested string (may itself interpolate)
          case '\'' => j = skipStringFrom(j, '\'')    // char literal
          case '{'  => depth += 1; j += 1
          case '}'  => depth -= 1; j += 1
          case _    => j += 1
      j

    // A `"` at `idx` opens an interpolated string iff the preceding source char is an
    // identifier char (`s"`, `f"`, `raw"`, `md"`, …) — Scala interpolation has no space.
    def isInterpQuote(idx: Int): Boolean =
      idx > 0 && { val p = in(idx - 1); p.isLetterOrDigit || p == '_' }

    // Find the closing `]` that matches the `[` whose opening was consumed before `from`.
    // Returns index of `]`, or -1 if not found. Handles nesting and strings/comments.
    def findClose(from: Int): Int =
      var depth = 1
      var j = from
      while j < n && depth > 0 do
        in(j) match
          case '/' if j + 1 < n && in(j+1) == '/' =>
            while j < n && in(j) != '\n' do j += 1
          case '/' if j + 1 < n && in(j+1) == '*' =>
            j += 2
            while j + 1 < n && !(in(j) == '*' && in(j+1) == '/') do j += 1
            if j + 1 < n then j += 2
          case '"' if j + 2 < n && in(j+1) == '"' && in(j+2) == '"' =>
            j = skipTripleFrom(j) - 1; j += 1
          case '"' if isInterpQuote(j) =>
            j = skipInterpStringFrom(j)
          case q @ ('"' | '\'') =>
            j = skipStringFrom(j, q)
          case '(' | '{' | '[' => depth += 1; j += 1
          case ']' =>
            depth -= 1
            if depth > 0 then j += 1
            // depth==0: leave j pointing at `]`, loop exits
          case ')' | '}' => depth -= 1; if depth > 0 then j += 1
          case _ => j += 1
      if depth == 0 then j else -1

    // True when content contains `->` not inside any brackets/strings (depth-0 check).
    def hasArrow(content: String): Boolean =
      val cs = content.toCharArray
      val m  = cs.length
      var d  = 0; var k = 0; var found = false
      while k < m && !found do
        cs(k) match
          case '"' if k + 2 < m && cs(k+1) == '"' && cs(k+2) == '"' =>
            k += 3; while k + 2 < m && !(cs(k)=='"'&&cs(k+1)=='"'&&cs(k+2)=='"') do k+=1
            if k + 2 < m then k += 3
          case q @ ('"'|'\'') =>
            k += 1; var e = false
            while k < m && (e || cs(k) != q) do { e = !e && cs(k) == '\\'; k += 1 }
            if k < m then k += 1
          case '('|'{'|'[' => d += 1; k += 1
          case ')'|'}'|']' => if d > 0 then d -= 1; k += 1
          case '-' if d == 0 && k + 1 < m && cs(k+1) == '>' => found = true
          case _ => k += 1
      found

    while i < n do
      in(i) match
        case '/' if i + 1 < n && in(i+1) == '/' =>  // line comment
          while i < n && in(i) != '\n' do { out.append(in(i)); i += 1 }
        case '/' if i + 1 < n && in(i+1) == '*' =>  // block comment
          out.append(in(i)); out.append(in(i+1)); i += 2
          while i + 1 < n && !(in(i) == '*' && in(i+1) == '/') do { out.append(in(i)); i += 1 }
          if i + 1 < n then { out.append(in(i)); out.append(in(i+1)); i += 2 }
        case '"' if i + 2 < n && in(i+1) == '"' && in(i+2) == '"' =>  // triple-quoted
          val end = skipTripleFrom(i)
          out.appendAll(in, i, end - i); i = end
        case '"' if isInterpQuote(i) =>  // interpolated string s"…${…}…"
          val end = skipInterpStringFrom(i)
          out.appendAll(in, i, end - i); i = end
        case q @ ('"' | '\'') =>  // plain string / char literal
          val end = skipStringFrom(i, q)
          out.appendAll(in, i, end - i); i = end
        case '[' =>
          // Walk back past whitespace to find the previous non-whitespace char.
          val lastIdx = out.length - 1
          val j = { var k = lastIdx; while k >= 0 && out.charAt(k).isWhitespace do k -= 1; k }
          val hadSpaceBefore = j < lastIdx   // whitespace existed between prev token and `[`
          // `[X] =>> …` — a type-lambda parameter clause is a type-param list, never
          // a list literal, even after `=` / an operator (`type F = [A] =>> G[A]`).
          // Detect by peeking past the matching `]` for the `=>>` arrow.
          val isTypeLambdaParams = {
            val close = findClose(i + 1)
            close >= 0 && {
              var k = close + 1
              while k < n && in(k).isWhitespace do k += 1
              k + 2 < n && in(k) == '=' && in(k + 1) == '>' && in(k + 2) == '>'
            }
          }
          val isTypeParam = isTypeLambdaParams || j >= 0 && {
            val c = out.charAt(j)
            if c == ')' || c == ']' then true
            else if c.isLetterOrDigit || c == '_' then
              // Extract the full preceding word; reject if it is a Scala keyword.
              var ws = j
              while ws > 0 && (out.charAt(ws - 1).isLetterOrDigit || out.charAt(ws - 1) == '_') do ws -= 1
              val word = out.substring(ws, j + 1)
              !preprocessListLiterals.scalaKeywords(word)
            else if preprocessListLiterals.isOpChar(c) then
              // Operator char: type params attach without space (`list.++[A]`);
              // a space before `[` means the `[` starts a list literal (`xs ++ [item]`).
              if hadSpaceBefore then false
              else
                var os = j
                while os > 0 && preprocessListLiterals.isOpChar(out.charAt(os - 1)) do os -= 1
                val op = out.substring(os, j + 1)
                !preprocessListLiterals.exprOperators(op)
            else false
          }
          if isTypeParam then { out.append('['); i += 1 }
          else
            val closeIdx = findClose(i + 1)
            if closeIdx < 0 then { out.append('['); i += 1 }  // unmatched — pass through
            else
              val inner = new String(in, i + 1, closeIdx - i - 1)
              val innerPP = preprocessListLiterals(inner)
              val kw = if hasArrow(inner) then "Map" else "List"
              out.append(kw).append('(').append(innerPP).append(')')
              changed = true; i = closeIdx + 1
        case c => out.append(c); i += 1

    if changed then out.toString else code

  private val slashImportPat = """^(\s*import\s+)([A-Za-z_][\w]*(?:/[A-Za-z_][\w]*)+)(\.\{.*|\..*|\s*$)""".r
  private[parser] def preprocessSlashImports(code: String): String =
    if !code.contains("import") then return code
    val importPat = slashImportPat
    val lines = code.linesIterator.toArray
    if !lines.exists(l => l.startsWith("import ") || l.matches("""^\s+import\s.*""")) then
      return code
    val out = new StringBuilder
    var changed = false
    for line <- lines do
      importPat.findFirstMatchIn(line) match
        case Some(m) if m.group(2).contains("/") =>
          out.append(m.group(1))
            .append(m.group(2).replace('/', '.'))
            .append(m.group(3))
            .append('\n')
          changed = true
        case _ =>
          out.append(line).append('\n')
    if changed then out.toString else code

  // Match a single-line list-form import:
  //   leading whitespace, `[Names...]`, `(path)` where path ends in `.ssc`,
  //   contains a scheme (`://`), or starts with `dep:`.
  private val inlineImportSingle = """^(\s*)\[\s*([A-Za-z_][\w]*(?:\s+as\s+[A-Za-z_][\w]*)?(?:\s*,\s*[A-Za-z_][\w]*(?:\s+as\s+[A-Za-z_][\w]*)?)*)\s*\]\(\s*([^)]+)\s*\)\s*$""".r
  // Match the start of a multi-line list-form import: opening `[` with names
  // but no closing `]` on the same line.
  private val inlineImportMulti  = """^(\s*)\[\s*([A-Za-z_][\w]*(?:\s+as\s+[A-Za-z_][\w]*)?(?:\s*,\s*[A-Za-z_][\w]*(?:\s+as\s+[A-Za-z_][\w]*)?)*)\s*,\s*$""".r
  private[parser] def preprocessInlineImports(code: String): String =
    if !code.contains("](") then return code
    val lines = code.linesIterator.toArray
    // Quick reject: only inspect files that actually contain a `]( ... .ssc)` or
    // `]( dep:` etc.  Cheap pre-filter.
    if !lines.exists(l => l.contains("](") && (l.contains(".ssc") || l.contains("dep:") || l.contains("://"))) then
      return code
    val result = new StringBuilder()

    val singleLine = inlineImportSingle
    val multiStart = inlineImportMulti

    var i = 0
    while i < lines.length do
      val line = lines(i)
      singleLine.findFirstMatchIn(line) match
        case Some(m) =>
          // Replace whole line with a comment preserving indentation.
          result.append(m.group(1)).append("// list-import: [")
                .append(m.group(2)).append("](").append(m.group(3)).append(")\n")
          i += 1
        case None =>
          multiStart.findFirstMatchIn(line) match
            case Some(m) =>
              // Multi-line span: gather continuation until we hit `](path)` close.
              val buf = new StringBuilder(line)
              var j = i + 1
              var closed = false
              while j < lines.length && !closed do
                buf.append("\n").append(lines(j))
                if lines(j).contains("](") && lines(j).indexOf(')', lines(j).indexOf("](")) >= 0 then
                  closed = true
                j += 1
              if closed then
                val joined = buf.toString.replaceAll("\\s*\n\\s*", " ")
                singleLine.findFirstMatchIn(joined) match
                  case Some(im) =>
                    result.append(m.group(1)).append("// list-import: [")
                          .append(im.group(2)).append("](").append(im.group(3)).append(")\n")
                    i = j
                  case None =>
                    // Couldn't re-match as a list-import after merging; fall
                    // through to passthrough.
                    result.append(line).append("\n")
                    i += 1
              else
                result.append(line).append("\n")
                i += 1
            case None =>
              result.append(line).append("\n")
              i += 1
    result.toString

  // Preprocess `effect Name:` declarations into a marked
  // `object Name { def op(...) = __effectOp__ }`.  The declaration marker is
  // deliberately present even for an empty plain effect: consumers that only
  // retain the preprocessed tree must still be able to distinguish that effect
  // from an ordinary empty object. Generic/parent syntax remains unsupported by
  // the compatibility ABI producer, so a second marker preserves that erased
  // header fact and lets the producer fail closed.
  // Split a line into (code, comment) at the first `//` that is not inside a string literal.
  // Returns (line, "") when there is no trailing comment.
  private[parser] def splitLineComment(line: String): (String, String) =
    var inStr = false
    var i = 0
    var at = -1
    while i < line.length - 1 && at < 0 do
      val c = line.charAt(i)
      if c == '"' then inStr = !inStr
      else if !inStr && c == '/' && line.charAt(i + 1) == '/' then at = i
      i += 1
    if at < 0 then (line, "") else (line.substring(0, at), line.substring(at))

  private val effectLinePat =
    """^(\s*)(multi\s+)?effect\s+(\w+)(\[[^\]]*\])?(\s+extends\s+[^:]+)?\s*:""".r
  private[parser] def preprocessEffects(code: String): String =
    if !code.contains("effect") then return code
    val effectLine = effectLinePat
    val lines = code.linesIterator.toArray
    if !lines.exists(l => effectLine.findFirstIn(l).isDefined) then return code
    val result = new StringBuilder()
    var i = 0
    while i < lines.length do
      val line = lines(i)
      effectLine.findFirstMatchIn(line) match
        case Some(m) =>
          val baseIndent = m.group(1).length
          val effectName = m.group(3)
          val isMulti    = m.group(2) != null
          val hasUnsupportedShape = m.group(4) != null || m.group(5) != null
          result.append(m.group(1)).append("object ").append(effectName).append(" {\n")
          result.append(m.group(1)).append("  private type __effectDecl__ = true\n")
          if hasUnsupportedShape then
            result.append(m.group(1)).append("  private type __effectUnsupportedShape__ = true\n")
          if isMulti then
            result.append(m.group(1)).append("  val __multiShot__ = true\n")
          i += 1
          while i < lines.length && {
            val l = lines(i)
            l.isBlank || (l.nonEmpty && l.indexWhere(_ != ' ') > baseIndent)
          } do
            val bodyLine = lines(i)
            // Insert the synthetic `= __effectOp__` body BEFORE any trailing line-comment, and only
            // when the op has no real body. A `=>` in a function-type param is NOT a body; a trailing
            // `// …` must not swallow the marker (which silently degraded the whole effect to a plain
            // instance → `No method 'op' on InstanceV` at perform time).
            val processed =
              if bodyLine.trim.startsWith("def ") then
                val (codePart, commentPart) = splitLineComment(bodyLine)
                if codePart.replace("=>", "").contains("=") then bodyLine
                else codePart.stripTrailing + " = __effectOp__" +
                  (if commentPart.isEmpty then "" else "  " + commentPart)
              else bodyLine
            result.append(processed).append("\n")
            i += 1
          result.append(m.group(1)).append("}\n")
        case None =>
          result.append(line).append("\n")
          i += 1
    result.toString

  private val remoteDefPat = """^(\s*)remote\s+def\s+([A-Za-z_][A-Za-z0-9_]*)\b(.*)$""".r
  private[parser] def preprocessRemoteDefs(code: String): String =
    if !code.contains("remote") then return code
    val remoteDef = remoteDefPat
    val lines = code.linesIterator.toArray
    if !lines.exists(l => remoteDef.findFirstIn(l).isDefined) then return code
    lines.map {
      case remoteDef(indent, name, tail) => s"${indent}@remote(name = \"$name\")\n${indent}def $name$tail"
      case other => other
    }.mkString("\n")

  private val modelClassPat = """^(\s*)model\s+(case\s+class\b.*)$""".r
  private[parser] def preprocessModelDefs(code: String): String =
    if !code.contains("model") then return code
    val lines = code.linesIterator.toArray
    if !lines.exists(l => modelClassPat.findFirstIn(l).isDefined) then return code
    lines.map {
      case modelClassPat(indent, rest) => s"$indent@model\n${indent}$rest"
      case other                       => other
    }.mkString("\n")

  /** arch-meta-v2-p4 — Make the restricted quoted-macro surface parseable
   *  by Scalameta while preserving the original code block source.
   *
   *  ScalaScript treats `${ impl('x) }` and `'{ $x + 1 }` as link-time
   *  macro syntax. Scalameta does not expose that syntax in the simple
   *  source parser path we use for `.ssc` blocks, so this preprocessor
   *  rewrites it into ordinary helper calls:
   *
   *    `${ impl('x) }` -> `__ssc_macro__(impl(__ssc_quote__("x", x)))`
   *    `'{ $x + 1 }`  -> `__ssc_quote_expr__(__ssc_splice__("x", x) + 1)`
   *
   *  The original source remains in `Content.CodeBlock.source`; the helper
   *  calls exist only in the parsed tree used by InterfaceExtractor. */
  private[parser] def preprocessQuotedMacros(code: String): String =
    if !(code.contains("${") || code.contains("'{")) then return code

    val in = code.toCharArray
    val n  = in.length
    val out = new StringBuilder(n + 32)
    var i = 0
    var changed = false

    def isIdentStart(c: Char): Boolean = c.isLetter || c == '_'
    def isIdentPart(c: Char): Boolean = c.isLetterOrDigit || c == '_'

    def skipString(start: Int): Int =
      // Triple-quoted string — skip past matching """ without processing ${…} inside
      if start + 2 < n && in(start) == '"' && in(start+1) == '"' && in(start+2) == '"' then
        var j = start + 3
        while j + 2 < n && !(in(j) == '"' && in(j+1) == '"' && in(j+2) == '"') do
          j += 1
        if j + 2 < n then j + 3 else n
      else
        val q = in(start)
        var j = start + 1
        var esc = false
        while j < n && (esc || in(j) != q) do
          esc = !esc && in(j) == '\\'
          j += 1
        if j < n then j + 1 else j

    def rewriteQuotedArgs(s: String): String =
      val sb = new StringBuilder(s.length + 16)
      var j = 0
      while j < s.length do
        s.charAt(j) match
          case '"' =>
            val start = j
            j += 1
            var esc = false
            while j < s.length && (esc || s.charAt(j) != '"') do
              esc = !esc && s.charAt(j) == '\\'
              j += 1
            if j < s.length then j += 1
            sb.append(s, start, j)
          case '\'' if j + 1 < s.length && isIdentStart(s.charAt(j + 1)) =>
            var k = j + 2
            while k < s.length && isIdentPart(s.charAt(k)) do k += 1
            val name = s.substring(j + 1, k)
            sb.append("__ssc_quote__(\"").append(name).append("\", ").append(name).append(")")
            j = k
          case c =>
            sb.append(c)
            j += 1
      sb.toString

    def stringLit(s: String): String =
      "\"" + s.flatMap {
        case '\\' => "\\\\"
        case '"'  => "\\\""
        case '\n' => "\\n"
        case '\r' => "\\r"
        case '\t' => "\\t"
        case c    => c.toString
      } + "\""

    def rewriteSplices(s: String): String =
      val sb = new StringBuilder(s.length + 16)
      var j = 0
      while j < s.length do
        s.charAt(j) match
          case q @ ('"' | '\'') =>
            val start = j
            j += 1
            var esc = false
            while j < s.length && (esc || s.charAt(j) != q) do
              esc = !esc && s.charAt(j) == '\\'
              j += 1
            if j < s.length then j += 1
            sb.append(s, start, j)
          case '$' if j + 1 < s.length && isIdentStart(s.charAt(j + 1)) =>
            var k = j + 2
            while k < s.length && isIdentPart(s.charAt(k)) do k += 1
            val name = s.substring(j + 1, k)
            sb.append("__ssc_splice__(\"").append(name).append("\", ").append(name).append(")")
            j = k
          case c =>
            sb.append(c)
            j += 1
      sb.toString

    def findBalanced(openIdx: Int, open: Char, close: Char): Int =
      var depth = 1
      var j = openIdx + 1
      while j < n && depth > 0 do
        in(j) match
          case '"' =>
            j = skipString(j)
          case c if c == open =>
            depth += 1; j += 1
          case c if c == close =>
            depth -= 1
            if depth > 0 then j += 1
          case _ =>
            j += 1
      if depth == 0 then j else -1

    while i < n do
      in(i) match
        case '$' if i + 1 < n && in(i + 1) == '{' =>
          val close = findBalanced(i + 1, '{', '}')
          if close >= 0 then
            val inner = new String(in, i + 2, close - i - 2)
            val rewritten = rewriteQuotedArgs(inner)
            if rewritten.contains("__ssc_quote__(") then
              out.append("__ssc_macro__(").append(rewritten).append(")")
            else
              out.append("__ssc_macro_error__(")
                .append(stringLit("restricted quoted macro entrypoint must call an implementation with quoted arguments, e.g. ${ impl('x) }"))
                .append(")")
            i = close + 1
            changed = true
          else
            out.append(in(i)); i += 1
        case '\'' if i + 1 < n && in(i + 1) == '{' =>
          val close = findBalanced(i + 1, '{', '}')
          if close >= 0 then
            val inner = new String(in, i + 2, close - i - 2)
            out.append("__ssc_quote_expr__(").append(rewriteSplices(inner)).append(")")
            i = close + 1
            changed = true
          else
            out.append(in(i)); i += 1
        case '"' =>
          val end = skipString(i)
          out.appendAll(in, i, end - i)
          i = end
        case c =>
          out.append(c); i += 1

    if changed then out.toString else code

  private def preprocessForScala(code: String): String =
    PreprocessorRegistry.applyAll(code)

  /** busi-p0-trailing-underscore-ident — scalameta's Scala3 lexer reads
   *  `name_:` as one operator identifier (since `:` is an operator
   *  character and `_` allows operator continuation), so a perfectly
   *  reasonable `def foo(type_: Int)` or `case class E(type_: String,
   *  payload_: String)` fails with "identifier expected but `)` /
   *  `,` found".  Users hit this when porting Scala code that uses
   *  trailing-underscore identifiers (e.g. `type_` to dodge the `type`
   *  keyword) into `.ssc`.
   *
   *  Insert one space between a trailing `_` of an identifier and a
   *  following `:` when the `_` is preceded by a letter or digit (so
   *  it really is the tail of an identifier, not a standalone `_`).
   *  Skip string literals, char literals, and comments verbatim.
   *
   *  Safe because:
   *  - The vararg splice is `: _*` (colon-first) — we only rewrite
   *    `_:` (underscore-first), so it is untouched.
   *  - Right-associative cons / colon-suffix operators (`+:`, `::`,
   *    `::=`) start with a non-`_` character or are entirely operator
   *    characters with no preceding letter / digit, so they are not
   *    matched.
   *  - Operator-suffix methods like `foo_:` are virtually nonexistent
   *    in production Scala; if one ever appears, the user can request
   *    a space in source.  We err on the side of "make natural code
   *    just work". */
  private[parser] def preprocessTrailingUnderscoreColon(code: String): String =
    // Fast-path: nothing to do without both characters.
    if !(code.contains('_') && code.contains(':')) then return code
    val n  = code.length
    val sb = new StringBuilder(n + 8)
    var i  = 0
    while i < n do
      val c = code.charAt(i)
      // ── skip block comment ──
      if c == '/' && i + 1 < n && code.charAt(i + 1) == '*' then
        val end = code.indexOf("*/", i + 2)
        val to  = if end < 0 then n else end + 2
        sb.append(code.substring(i, to)); i = to
      // ── skip line comment ──
      else if c == '/' && i + 1 < n && code.charAt(i + 1) == '/' then
        var j = i + 2
        while j < n && code.charAt(j) != '\n' do j += 1
        sb.append(code.substring(i, j)); i = j
      // ── skip triple-quoted string ──
      else if c == '"' && i + 2 < n && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"' then
        val end = code.indexOf("\"\"\"", i + 3)
        val to  = if end < 0 then n else end + 3
        sb.append(code.substring(i, to)); i = to
      // ── skip single-line string ──
      else if c == '"' then
        sb.append(c); var j = i + 1
        while j < n && code.charAt(j) != '"' do
          if code.charAt(j) == '\\' && j + 1 < n then
            sb.append(code.charAt(j)); sb.append(code.charAt(j + 1)); j += 2
          else
            sb.append(code.charAt(j)); j += 1
        if j < n then sb.append('"')
        i = j + 1
      // ── skip char literal '_' / 'x' ──
      else if c == '\'' && i + 2 < n && code.charAt(i + 2) == '\'' then
        sb.append(code.substring(i, i + 3)); i += 3
      // ── the rule: letter|digit `_` `:` not followed by `:` (so `::` and `:=` survive) ──
      else if c == '_' && i + 1 < n && code.charAt(i + 1) == ':'
           && i > 0 && isIdentBodyChar(code.charAt(i - 1)) then
        // Insert a space only when the `:` is NOT followed by another `:`
        // (`::` cons constructor) and NOT followed by `=` (`:=` actor-style
        // assignment).  In both of those cases the `:` is part of an
        // operator identifier sequence, not a type ascription colon.
        val nextNext = if i + 2 < n then code.charAt(i + 2) else ' '
        if nextNext == ':' || nextNext == '=' then
          sb.append(c); i += 1
        else
          sb.append("_ "); i += 1   // emit `_` + space, leave `:` for next iter
      else
        sb.append(c); i += 1
    sb.toString

  /** scalameta's Scala3 tokenizer reads `'{` — the start of the char literal
   *  `'{'` — as a macro-quote start (`'{ … }`), then mis-tracks braces so a
   *  later `'}'` fails with "Macro quote must be followed by id, brace or
   *  bracket".  This bites parser-combinator code that matches brace characters
   *  (`Parser.char('{')` / `Parser.char('}')`, e.g. examples/dsl-json-parser).
   *  Rewrite the brace char literals to the equivalent unicode escapes
   *  (`'{'` / `'}'`) — the same `Char`, but not starting with `'{`.
   *  String / char / comment contexts are skipped verbatim, so a `'{'` inside a
   *  string is untouched; and a real quote `'{ … }` (no closing `'` after one
   *  char) is never matched. */
  private[parser] def preprocessBraceCharLiterals(code: String): String =
    if !code.contains("'{'") && !code.contains("'}'") then return code
    val n  = code.length
    val sb = new StringBuilder(n + 8)
    var i  = 0
    while i < n do
      val c = code.charAt(i)
      if c == '/' && i + 1 < n && code.charAt(i + 1) == '*' then
        val end = code.indexOf("*/", i + 2); val to = if end < 0 then n else end + 2
        sb.append(code.substring(i, to)); i = to
      else if c == '/' && i + 1 < n && code.charAt(i + 1) == '/' then
        var j = i + 2; while j < n && code.charAt(j) != '\n' do j += 1
        sb.append(code.substring(i, j)); i = j
      else if c == '"' && i + 2 < n && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"' then
        val end = code.indexOf("\"\"\"", i + 3); val to = if end < 0 then n else end + 3
        sb.append(code.substring(i, to)); i = to
      else if c == '"' then
        sb.append(c); var j = i + 1
        while j < n && code.charAt(j) != '"' do
          if code.charAt(j) == '\\' && j + 1 < n then
            sb.append(code.charAt(j)); sb.append(code.charAt(j + 1)); j += 2
          else
            sb.append(code.charAt(j)); j += 1
        if j < n then sb.append('"')
        i = j + 1
      else if c == '\'' && i + 2 < n && code.charAt(i + 2) == '\'' &&
              (code.charAt(i + 1) == '{' || code.charAt(i + 1) == '}') then
        sb.append(if code.charAt(i + 1) == '{' then "'\\u007B'" else "'\\u007D'"); i += 3
      else if c == '\'' && i + 2 < n && code.charAt(i + 2) == '\'' then
        sb.append(code.substring(i, i + 3)); i += 3   // other char literal — verbatim
      else
        sb.append(c); i += 1
    sb.toString

  /** Identifier body char: letter, digit, or `_`.  Note we test the
   *  character BEFORE the trailing `_`, which means a one-character
   *  identifier `_` followed by `:` (i.e. `_:Foo`) is NOT rewritten —
   *  that form does not exist as a type ascription in legitimate
   *  Scala anyway. */
  private def isIdentBodyChar(c: Char): Boolean =
    c.isLetterOrDigit || c == '_'

  private val _longMax = BigInt("9223372036854775807")

  private def isHexDigit(c: Char): Boolean =
    c.isDigit || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  /** exact-numerics v1.64.7 — numeric-literal sugar.  Rewrites, outside of
   *  strings/char-literals/comments:
   *   - `123n`      → `BigInt("123")`
   *   - `12.34m`    → `Decimal("12.34")`   (also `5m` → `Decimal("5")`)
   *   - an integer literal too large for `Long` → `BigInt("…")` (auto-promote)
   *  Plain `Int`/`Long`/`Double`/`Float` literals, hex/binary, `1.toString`,
   *  `t._1`, `1_000`, identifiers like `x1`, and literal text inside strings or
   *  comments are left untouched.  Underscores are stripped from the emitted
   *  string argument. */
  private[parser] def preprocessNumericLiterals(code: String): String =
    if !code.exists(c => c == 'n' || c == 'm' || c.isDigit) then return code
    val n  = code.length
    val sb = new StringBuilder(n + 16)
    var i  = 0
    def isIdentPart(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '$'
    while i < n do
      val c = code.charAt(i)
      // ── skip block comment ──
      if c == '/' && i + 1 < n && code.charAt(i + 1) == '*' then
        val end = code.indexOf("*/", i + 2)
        val to  = if end < 0 then n else end + 2
        sb.append(code.substring(i, to)); i = to
      // ── skip line comment ──
      else if c == '/' && i + 1 < n && code.charAt(i + 1) == '/' then
        var j = i + 2
        while j < n && code.charAt(j) != '\n' do j += 1
        sb.append(code.substring(i, j)); i = j
      // ── skip triple-quoted string ──
      else if c == '"' && i + 2 < n && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"' then
        val end = code.indexOf("\"\"\"", i + 3)
        val to  = if end < 0 then n else end + 3
        sb.append(code.substring(i, to)); i = to
      // ── skip single-quoted string (with escapes) ──
      else if c == '"' then
        sb.append(c); var j = i + 1
        while j < n && code.charAt(j) != '"' do
          if code.charAt(j) == '\\' && j + 1 < n then { sb.append(code.charAt(j)); sb.append(code.charAt(j + 1)); j += 2 }
          else { sb.append(code.charAt(j)); j += 1 }
        if j < n then { sb.append('"'); j += 1 }
        i = j
      // ── skip char literal ──
      else if c == '\'' && i + 1 < n then
        var j = i + 1
        sb.append(c)
        if code.charAt(j) == '\\' && j + 1 < n then { sb.append(code.charAt(j)); sb.append(code.charAt(j + 1)); j += 2 }
        else { sb.append(code.charAt(j)); j += 1 }
        if j < n && code.charAt(j) == '\'' then { sb.append('\''); j += 1 }
        i = j
      // ── numeric literal start: a digit not preceded by an identifier char ──
      else if c.isDigit && (i == 0 || !isIdentPart(code.charAt(i - 1))) then
        var j = i
        if c == '0' && j + 1 < n && (code.charAt(j + 1) == 'x' || code.charAt(j + 1) == 'X' ||
                                     code.charAt(j + 1) == 'b' || code.charAt(j + 1) == 'B') then
          j += 2
          while j < n && (isHexDigit(code.charAt(j)) || code.charAt(j) == '_') do j += 1
          if j < n && (code.charAt(j) == 'L' || code.charAt(j) == 'l') then j += 1
          sb.append(code.substring(i, j)); i = j
        else
          while j < n && (code.charAt(j).isDigit || code.charAt(j) == '_') do j += 1
          var isDecimal = false
          if j + 1 < n && code.charAt(j) == '.' && code.charAt(j + 1).isDigit then
            isDecimal = true; j += 1
            while j < n && (code.charAt(j).isDigit || code.charAt(j) == '_') do j += 1
          if j < n && (code.charAt(j) == 'e' || code.charAt(j) == 'E') then
            var k = j + 1
            if k < n && (code.charAt(k) == '+' || code.charAt(k) == '-') then k += 1
            if k < n && code.charAt(k).isDigit then
              isDecimal = true; j = k
              while j < n && code.charAt(j).isDigit do j += 1
          val raw    = code.substring(i, j)
          val digits = raw.replace("_", "")
          val suffix = if j < n then code.charAt(j) else ' '
          val suffixIsBoundary = j + 1 >= n || !isIdentPart(code.charAt(j + 1))
          if suffix == 'n' && !isDecimal && suffixIsBoundary then
            sb.append("BigInt(\"").append(digits).append("\")"); i = j + 1
          else if suffix == 'm' && suffixIsBoundary then
            sb.append("Decimal(\"").append(digits).append("\")"); i = j + 1
          else if suffix == 'L' || suffix == 'l' || suffix == 'f' || suffix == 'F' ||
                  suffix == 'd' || suffix == 'D' then
            sb.append(code.substring(i, j + 1)); i = j + 1
          else if !isDecimal && BigInt(digits) > _longMax then
            sb.append("BigInt(\"").append(digits).append("\")"); i = j
          else
            sb.append(raw); i = j
      else
        sb.append(c); i += 1
    sb.toString

  /** Public entry for the `.sscc` v3 read path (`ScalaNode.deferred`): apply the
   *  placeholder→`=>>` type-lambda desugar to an already-parsed tree. The `.sscc`
   *  read reconstructs source from a post-preprocess token stream and raw-parses it
   *  (it does NOT run a `Parser` pass), so without this a placeholder alias
   *  `type X = Map[Int, _]` comes back as a wildcard instead of a type lambda — a
   *  cache-vs-direct-parse divergence. Native `[A] =>> …` already round-trips via the
   *  stored `=>>` token. No preprocessing is re-run (tokens are stored post-preprocess);
   *  this is a pure tree rewrite. */
  def desugarTypeLambdaAliases(tree: scala.meta.Tree): scala.meta.Tree =
    desugarPlaceholderTypeAliases(tree)

  /** Desugar placeholder type-lambda aliases to the native `=>>` form so every
   *  consumer (interp/jvm/js/rust + interface artifacts) sees one canonical shape.
   *  `type X = Map[Int, _]` → `type X = [A] =>> Map[Int, A]` (each `_` → a fresh
   *  param, left→right). ScalaScript has no existentials, so an alias RHS with `_`
   *  is always a type lambda. Without this, Scala-3 codegen (jvm) reads `Map[Int, _]`
   *  as a wildcard that "does not take type parameters" when the alias is applied.
   *  Top-level + nested at any depth (object/trait/class bodies). */
  private def desugarPlaceholderTypeAliases(tree: scala.meta.Tree): scala.meta.Tree =
    import scala.meta.*
    given Dialect = dialects.Scala3
    def typeHasWildcard(t: Type): Boolean = t match
      case _: Type.Wildcard              => true
      case Type.Apply.After_4_6_0(_, ac) => ac.values.exists(typeHasWildcard)
      case Type.Tuple(elems)             => elems.exists(typeHasWildcard)
      case _                             => false
    // Recurse into a template body (object/trait/class) so a placeholder alias
    // nested in an object/trait/class is desugared too. scalameta 4.17 reads the
    // member stats via `templ.body.stats` but `Template.copy` still takes the
    // legacy flat `stats` parameter directly. Reconstruct only when a member
    // actually changed so the common (no-alias) case keeps its original node
    // (positions/origin intact) instead of a synthetic copy.
    def rewriteTemplate(t: Template): Template =
      val newStats = t.body.stats.map(rewriteStat)
      if newStats.corresponds(t.body.stats)(_ eq _) then t
      else t.copy(stats = newStats)
    def rewriteStat(s: Stat): Stat = s match
      case Defn.Type.After_4_6_0(mods, name, tparams, rhs, bounds) if typeHasWildcard(rhs) =>
        val params = scala.collection.mutable.ListBuffer.empty[String]
        def go(tt: Type): Type = tt match
          case _: Type.Wildcard =>
            val p = ('A' + params.length).toChar.toString; params += p; Type.Name(p)
          case Type.Apply.After_4_6_0(tn, ac) => Type.Apply(tn, Type.ArgClause(ac.values.toList.map(go)))
          case Type.Tuple(elems)              => Type.Tuple(elems.toList.map(go))
          case other                          => other
        val body = go(rhs)
        s"[${params.mkString(", ")}] =>> ${body.syntax}".parse[Type] match
          case Parsed.Success(lam) => Defn.Type(mods, name, tparams, lam, bounds)
          case _                   => s
      case o: Defn.Object =>
        val nt = rewriteTemplate(o.templ); if nt eq o.templ then o else o.copy(templ = nt)
      case t: Defn.Trait =>
        val nt = rewriteTemplate(t.templ); if nt eq t.templ then t else t.copy(templ = nt)
      case c: Defn.Class =>
        val nt = rewriteTemplate(c.templ); if nt eq c.templ then c else c.copy(templ = nt)
      case other          => other
    // Top-level aliases (Source / script-block) + nested object/trait/class bodies.
    tree match
      case Source(stats)     => Source(stats.map(rewriteStat))
      case Term.Block(stats) => Term.Block(stats.map(rewriteStat))
      case other             => other

  /** Re-parse a scalascript code-block body to a scalameta `ScalaNode`.
   *
   *  Public so `transform.Denormalize` can rebuild the AST trees that
   *  `Normalize` strips when serialising to IR — backends still consume
   *  the parsed tree until they migrate to IR-native traversal. */
  def parseScalaSource(code: String): Option[ScalaNode] =
    parseScalaWithDiagnostic(code)._1

  /** Same as `parseScalaSource` but also returns a structured
   *  `CodeBlockParseError` (populated when both the source-mode parse AND the
   *  block-wrapped term-mode parse fail).  The error's `line` / `column` come
   *  from scalameta's `Parsed.Error.pos` and refer to positions inside the
   *  ORIGINAL `code` argument (we map the wrapped-`{ }` position back when the
   *  fallback parse produced the diagnostic by subtracting the synthetic line).
   *
   *  Returned tuple: `(tree, parseError)`.  `tree` is `Some` and `parseError`
   *  is `None` on success; on failure `tree` is `None` and `parseError` is
   *  `Some` carrying the diagnostic. */
  def parseScalaWithDiagnostic(code: String): (Option[ScalaNode], Option[CodeBlockParseError]) =
    import scala.meta.*
    given Dialect = dialects.Scala3
    val processed = preprocessForScala(code)
    safeParse(processed.parse[Source]) match
      case Parsed.Success(tree) => (Some(ScalaNode(desugarPlaceholderTypeAliases(tree))), None)
      case sourceErr: Parsed.Error =>
        // Script mode: code may contain top-level expressions.
        // Wrap in a block so scalameta accepts arbitrary statement sequences.
        // Note: the wrap prepends a synthetic `{\n` line so positions in the
        // wrapped parse are offset by +1 line.  We undo that mapping below.
        safeParse(s"{\n$processed\n}".parse[Term]) match
          case Parsed.Success(tree) => (Some(ScalaNode(desugarPlaceholderTypeAliases(tree))), None)
          case _: Parsed.Error      =>
            // Try 3: "mixed" mode — declarations (Source) followed by a trailing
            // expression (Term).  This handles handler files of the form:
            //   case class Input(...)
            //   case class Output(...)
            //   (input: Input) => Output(...)
            // where scalameta fails to parse the typed lambda after class defs in a
            // single block.  We try every split point from the bottom up, returning
            // on the first (prefix-as-Source, suffix-as-Term) pair that works.
            val splitResult = trySplitParse(processed)
            splitResult match
              case Some(tree) => (Some(ScalaNode(desugarPlaceholderTypeAliases(tree))), None)
              case None       =>
                // All attempts failed.  We prefer the source-mode error since
                // its positions map 1:1 to the user's block body (no wrap-line
                // shift); the term-mode error tends to be misleading anyway
                // (it complains about an unexpected `{` introduction).
                (None, Some(buildParseError(code, sourceErr)))

  /** Third-pass fallback: try splitting `code` into a declarations prefix (parsed
   *  as `Source`) and a trailing expression (parsed as `Term`).  Used when both
   *  the plain Source parse AND the `{...}` block-wrap fail — this covers the
   *  handler-file pattern:
   *    case class Input(...)
   *    case class Output(...)
   *    (input: Input) => Output(...)
   *  where scalameta can't parse a typed lambda after class defs in one block.
   *
   *  Tries every split point from the bottom up (suffix = last N lines).
   *  Returns the first `Source` that combines a valid declaration prefix with
   *  a valid trailing term, or `None` if no split works. */
  /** The handler-file pattern this fallback targets always has a SHORT trailing
   *  term (a lambda after the class defs), so the useful split points are the
   *  ones nearest the end.  We therefore only try splits whose suffix is at most
   *  `MaxSplitSuffixLines` lines.  Without this bound a large block that fails
   *  both earlier parses (e.g. one that uses a Scala-3 soft keyword like `given`
   *  as an identifier) makes this loop re-parse every prefix — O(N) parses over
   *  O(N)-line prefixes, i.e. O(N²) total — which turns a fast parse error into a
   *  multi-minute hang on a multi-thousand-line module.  Small blocks (≤ the
   *  bound) keep the original full-range behaviour. */
  private val MaxSplitSuffixLines = 48

  private def trySplitParse(code: String): Option[scala.meta.Source] =
    import scala.meta.*
    given Dialect = dialects.Scala3
    val lines = code.linesIterator.toVector
    // Only attempt when there are at least 2 lines to split.
    if lines.length < 2 then return None
    val lowestPrefix = math.max(1, lines.length - MaxSplitSuffixLines)
    (lines.length - 1 to lowestPrefix by -1).view.flatMap { k =>
      val prefix = lines.take(k).mkString("\n").trim
      val suffix = lines.drop(k).mkString("\n").trim
      if prefix.isEmpty || suffix.isEmpty then None
      else
        safeParse(prefix.parse[Source]).toOption.flatMap { src =>
          safeParse(suffix.parse[Term]).toOption.map { term =>
            Source(src.stats :+ term)
          }
        }
    }.headOption

  /** Run a scalameta parse, converting a *thrown* exception into a
   *  `Parsed.Error` instead of letting it escape.  Scalameta can throw a raw
   *  `NullPointerException` from its `termParam` token handling on certain
   *  truncated inputs (e.g. `def f(` / `def f(using `), and a deeply
   *  unbalanced nesting can overflow the stack.  Without this the malformed
   *  code block crashed/hung the whole pipeline (ui-bug-jobj-failloud); now it
   *  flows into the normal located-diagnostic path. */
  private def safeParse[T <: scala.meta.Tree](thunk: => scala.meta.parsers.Parsed[T]): scala.meta.parsers.Parsed[T] =
    import scala.meta.*
    try thunk
    catch
      case scala.util.control.NonFatal(e) =>
        Parsed.Error(Position.None, "incomplete or malformed code block", new Exception(e))
      case e: StackOverflowError =>
        Parsed.Error(Position.None, "code block is too deeply nested to parse", new Exception(e))

  /** Build a `CodeBlockParseError` from scalameta's `Parsed.Error` against the
   *  ORIGINAL block source `code`.  Scalameta's `pos.startLine` / `startColumn`
   *  are 0-indexed; we expose 1-indexed numbers to match human convention. */
  private def buildParseError(code: String, err: scala.meta.parsers.Parsed.Error): CodeBlockParseError =
    val pos      = err.pos
    // 0-indexed → 1-indexed.  `code` here is the block body before
    // `preprocessExtern`/`preprocessEffects`; in the common case those
    // preprocessors are no-ops, so positions in `processed` align with `code`.
    // When they do rewrite, positions may be slightly off — still useful as
    // a coarse pointer, and far better than the previous no-position output.
    val line0    = math.max(0, pos.startLine)
    val col0     = math.max(0, pos.startColumn)
    val line     = line0 + 1
    val column   = col0 + 1
    val lines    = code.linesIterator.toArray
    // Defensive: clamp the line if the preprocessor shifted things.
    val safeLine = if line0 < lines.length then line0 else math.max(0, lines.length - 1)
    val snippet  = buildSnippet(lines, safeLine, col0)
    CodeBlockParseError(
      message = err.message,
      line    = line,
      column  = column,
      snippet = snippet
    )

  /** Render a 3-line context window (1 before + failing + 1 after) with a
   *  `^` caret line under the failing column.  Boundaries are handled by
   *  emitting fewer context lines when the failing line is at the start /
   *  end of `lines`.  Each line is rendered with a 2-space indent so the
   *  CLI can emit the snippet verbatim under its diagnostic header. */
  private def buildSnippet(lines: Array[String], lineIdx0: Int, colIdx0: Int): String =
    if lines.isEmpty then return ""
    val safeIdx = math.max(0, math.min(lineIdx0, lines.length - 1))
    val from    = math.max(0, safeIdx - 1)
    val to      = math.min(lines.length - 1, safeIdx + 1)
    val buf     = new StringBuilder()
    var i       = from
    while i <= to do
      buf.append("  ").append(lines(i)).append('\n')
      if i == safeIdx then
        // Caret line: 2-space prefix (matches the snippet indent above) +
        // colIdx0 spaces + `^`.
        buf.append("  ")
        val safeCol = math.max(0, colIdx0)
        var j = 0
        while j < safeCol do { buf.append(' '); j += 1 }
        buf.append('^').append('\n')
      i += 1
    // Trim trailing newline so callers can println without a blank line.
    if buf.nonEmpty && buf.charAt(buf.length - 1) == '\n' then buf.setLength(buf.length - 1)
    buf.toString

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
