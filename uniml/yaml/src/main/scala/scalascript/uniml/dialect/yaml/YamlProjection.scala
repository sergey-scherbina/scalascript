package scalascript.uniml.dialect.yaml

import scalascript.uniml.*

object YamlProjection:
  def project(result: ParseResult, options: YamlProjectionOptions): YamlProjectionResult =
    val blocking = result.diagnostics.exists(diagnostic =>
      diagnostic.severity == Severity.Error || diagnostic.severity == Severity.Fatal
    )
    if blocking then YamlProjectionResult(None, result.diagnostics)
    else
      // TRAVERSAL ORDER IS THE SOURCE ORDER, and it is VALIDATED rather than imposed. This used to
      // read `.sortBy(_.id)`, which silently repaired a tree whose traversal order and token ids
      // disagreed — and then reparsed the repaired text, manufacturing a semantic success out of an
      // invalid CST. YAML is source-ORDERED (anchors bind before aliases, directives before the
      // document they govern), so a reordering does not merely hide the defect: it can change what
      // the document means. `uniml.yaml.projection-invalid-cst` is the answer instead.
      //
      // The sibling projection already worked this way — `JsonProjection` refuses with
      // `uniml.json.projection-invalid-cst` rather than fixing its input — so this was the second
      // decision site of one rule, and the wrong one.
      val tokens = result.roots.flatMap(UniNode.sourceTokens)
      val cstDiagnostics = validateCst(tokens)
      if cstDiagnostics.nonEmpty then YamlProjectionResult(None, result.diagnostics ++ cstDiagnostics)
      else
        val source = tokens.headOption.map(_.span.source).getOrElse(SourceId("memory:yaml"))
        val text = tokens.iterator.map(_.lexeme).mkString("")
        val parsed = YamlSemanticParser.parse(source, text, options.schema)
        val validationDiagnostics = validate(parsed.stream)
        val diagnostics = result.diagnostics ++ parsed.diagnostics ++ validationDiagnostics
        if diagnostics.exists(diagnostic => diagnostic.severity == Severity.Error || diagnostic.severity == Severity.Fatal) then
          YamlProjectionResult(None, diagnostics)
        else options.aliases match
          case AliasPolicy.Preserve => YamlProjectionResult(Some(parsed.stream), diagnostics)
          case AliasPolicy.Resolve =>
            resolve(parsed.stream, options) match
              case Left(resolveDiagnostics) => YamlProjectionResult(None, diagnostics ++ resolveDiagnostics)
              case Right(value)             => YamlProjectionResult(Some(value), diagnostics)

  /** The CST invariants the projection RELIES on, checked before it flattens the tree to text.
    *
    * Each one is a way the flattened text could differ from the source the tree came from, which is
    * the only reason this projection can reparse at all. Named in `BUGS.md`
    * `uniml-yaml-projection-reorders-invalid-cst`; the first four are structural and checkable here,
    * and what is NOT checkable is stated rather than implied: the ORIGINAL TEXT is not available to
    * this function, so a token's lexeme cannot be compared against a slice of it. Contiguity of
    * spans is the structural stand-in — a gap or an overlap means the tree is not a partition of the
    * source, which is the condition that made the old `sortBy` reachable.
    */
  private def validateCst(tokens: Vector[SourceToken]): Vector[Diagnostic] =
    if tokens.isEmpty then Vector.empty
    else
      // The imperative `reject` kept only the FIRST violation (`if problems.isEmpty`), and the pair
      // scan stopped once one was recorded — so the whole function is "the first violation, in this
      // check order", which is what an orElse chain says directly.
      val head = tokens.head
      def make(message: String, span: Option[SourceSpan]): Diagnostic =
        diagnostic("uniml.yaml.projection-invalid-cst", message, Severity.Error, span)

      val sourceMismatch = tokens.find(_.span.source != head.span.source).map { other =>
        make(
          // NOT `s"… '${…}' …"`: a bare `'…'`-wrapped interpolation splice trips this
          // toolchain's parser in a large enough merged program (`YamlStructure.scala`'s
          // `validateFlow` has the identical fix, same reasoning). Plain concatenation instead.
          "the CST spans two sources — '" + head.span.source.value + "' and '" + other.span.source.value + "'",
          Some(other.span))
      }
      def duplicateIds =
        if tokens.map(_.id).distinct.size != tokens.size then
          Some(make("the CST has duplicate token ids", Some(head.span)))
        else None
      def pairViolation(i: Int): Option[Diagnostic] =
        if i >= tokens.size then None
        else
          val prev = tokens(i - 1)
          val cur = tokens(i)
          if cur.id <= prev.id then
            Some(make(
              s"traversal order and token ids disagree: id ${cur.id} follows ${prev.id}",
              Some(cur.span)))
          else if cur.span.start.offset < prev.span.end.offset then
            Some(make(
              s"token spans overlap or go backwards: ${cur.span.start.offset} follows ${prev.span.end.offset}",
              Some(cur.span)))
          else pairViolation(i + 1)
      sourceMismatch.orElse(duplicateIds).orElse(pairViolation(1)).toVector

  /** validate's walk state: anchors and both counters are PER DOCUMENT, the diagnostics survive
    * across documents — reset the first three at each document boundary, keep the fourth. */
  private final case class ValidateWalk(
      anchors: Map[String, YamlValue],
      anchorCount: Int,
      aliasCount: Int,
      diagnostics: Vector[Diagnostic],
  )

  private def validate(stream: YamlValue.Stream): Vector[Diagnostic] =
    def visit(w0: ValidateWalk, value: YamlValue, span: Option[SourceSpan]): ValidateWalk =
      val w = nodeAnchor(value).fold(w0) { name =>
        val counted = w0.copy(anchorCount = w0.anchorCount + 1)
        val limited =
          if counted.anchorCount > 1_000_000 then
            counted.copy(diagnostics = counted.diagnostics :+ diagnostic("uniml.yaml.limit.anchors", "YAML document exceeds the anchor limit", Severity.Fatal, span))
          else counted
        val warned =
          if limited.anchors.contains(name) then
            limited.copy(diagnostics = limited.diagnostics :+ diagnostic("uniml.yaml.duplicate-anchor", s"duplicate YAML anchor '&$name' replaces its previous binding", Severity.Warning, span))
          else limited
        warned.copy(anchors = warned.anchors + (name -> value))
      }
      value match
        case YamlValue.Stream(_) => w
        case YamlValue.Mapping(entries, _, _) =>
          // seenKeys is LOCAL to one mapping, exactly as the imperative var was — it rides the fold
          // over this mapping's entries and is dropped, while the walk state threads on through.
          final case class EntryWalk(walk: ValidateWalk, seenKeys: Set[String])
          entries.foldLeft(EntryWalk(w, Set.empty)) { (acc, entry) =>
            val fingerprint = keyFingerprint(entry.key)
            val flagged =
              if acc.seenKeys.contains(fingerprint) then
                acc.walk.copy(diagnostics = acc.walk.diagnostics :+ diagnostic("uniml.yaml.duplicate-key", "duplicate YAML mapping key is preserved", Severity.Warning, Some(entry.span)))
              else acc.walk
            val afterKey = visit(flagged, entry.key, Some(entry.span))
            EntryWalk(visit(afterKey, entry.value, Some(entry.span)), acc.seenKeys + fingerprint)
          }.walk
        case YamlValue.Sequence(values, _, _) => values.foldLeft(w)((acc, child) => visit(acc, child, span))
        case YamlValue.Scalar(_, _, _) => w
        case YamlValue.Alias(name) =>
          val counted = w.copy(aliasCount = w.aliasCount + 1)
          val limited =
            if counted.aliasCount > 1_000_000 then
              counted.copy(diagnostics = counted.diagnostics :+ diagnostic("uniml.yaml.limit.aliases", "YAML document exceeds the alias limit", Severity.Fatal, span))
            else counted
          if !limited.anchors.contains(name) then
            limited.copy(diagnostics = limited.diagnostics :+ diagnostic("uniml.yaml.undefined-alias", s"alias '*$name' has no preceding anchor in this document", Severity.Error, span))
          else limited

    stream.documents.foldLeft(Vector.empty[Diagnostic]) { (diags, document) =>
      val start = ValidateWalk(Map.empty, 0, 0, diags)
      document.value.fold(start)(value => visit(start, value, None)).diagnostics
    }

  private def resolve(
      stream: YamlValue.Stream,
      options: YamlProjectionOptions,
  ): Either[Vector[Diagnostic], YamlValue.Stream] =
    // One record per former var; `anchors` is PER DOCUMENT (it was declared inside the loop) while
    // diagnostics/expansions/nodes are CROSS-document — the per-document reset below copies only
    // anchors back to empty. `Cloned` is a named result record rather than a (state, Option) pair:
    // the file's v2 rules forbid destructuring a tuple in a val pattern.
    final case class ResolveState(
        anchors: Map[String, YamlValue],
        diagnostics: Vector[Diagnostic],
        expansions: Int,
        nodes: Int,
    )
    final case class Cloned(state: ResolveState, value: Option[YamlValue])

    // SOURCE-ORDERED, which is what `validate` above already does and this did not. An alias binds
    // to the nearest PRECEDING anchor: a duplicate `&name` rebinds from its own position onward
    // and leaves earlier aliases pointing at the earlier value.
    //
    // This used to pre-walk the whole document into a last-wins `Map` before cloning anything, so
    // in
    //
    //     first: &slot one
    //     before: *slot
    //     second: &slot two
    //     after: *slot
    //
    // BOTH aliases resolved to `two`. The two halves of this file disagreed about the same rule
    // while `validate` reported the duplicate as a warning and moved on — so the document was
    // accepted and then resolved with the wrong graph
    // (`uniml-yaml-alias-resolution-last-wins`).
    //
    // Registering at node ENTRY, rather than after the children, is what makes a recursive anchor
    // (`&a [*a]`) still reachable from inside its own subtree; the `visiting` set is what stops it
    // looping.
    def cloneValue(st0: ResolveState, value: YamlValue, visiting: Set[String]): Cloned =
      // Registration at node ENTRY, before the node count and its limit — the comment above says
      // why that order is load-bearing (a recursive anchor is reachable from its own subtree).
      val registered = nodeAnchor(value).fold(st0)(name => st0.copy(anchors = st0.anchors + (name -> value)))
      val counted = registered.copy(nodes = registered.nodes + 1)
      if counted.nodes > options.maxExpandedNodes then
        Cloned(
          counted.copy(diagnostics = counted.diagnostics :+ diagnostic(
            "uniml.yaml.limit.expansion",
            s"resolved YAML graph exceeds ${options.maxExpandedNodes} nodes",
            Severity.Fatal,
            None,
          )),
          None,
        )
      else value match
        case YamlValue.Stream(_) => Cloned(counted, None)
        case YamlValue.Alias(name) =>
          val expanded = counted.copy(expansions = counted.expansions + 1)
          if expanded.expansions > options.maxAliasExpansions then
            Cloned(
              expanded.copy(diagnostics = expanded.diagnostics :+ diagnostic(
                "uniml.yaml.limit.expansion",
                s"YAML alias expansion exceeds ${options.maxAliasExpansions}",
                Severity.Fatal,
                None,
              )),
              None,
            )
          else if visiting.contains(name) then
            Cloned(
              expanded.copy(diagnostics = expanded.diagnostics :+ diagnostic("uniml.yaml.alias-cycle", s"YAML alias cycle reaches '*$name'", Severity.Error, None)),
              None,
            )
          else expanded.anchors.get(name) match
            case None =>
              Cloned(
                expanded.copy(diagnostics = expanded.diagnostics :+ diagnostic("uniml.yaml.undefined-alias", s"undefined YAML alias '*$name'", Severity.Error, None)),
                None,
              )
            case Some(target) => cloneValue(expanded, target, visiting + name)
        case YamlValue.Scalar(value, tag, anchor) => Cloned(counted, Some(YamlValue.Scalar(value, tag, anchor)))
        case YamlValue.Sequence(values, tag, anchor) =>
          // EVERY child is cloned, exactly as the imperative `values.map` visited every child even
          // after one failed — the counters and diagnostics of the later children must accumulate.
          final case class SeqWalk(state: ResolveState, cloned: Vector[Option[YamlValue]])
          val walked = values.foldLeft(SeqWalk(counted, Vector.empty)) { (acc, child) =>
            val result = cloneValue(acc.state, child, visiting)
            SeqWalk(result.state, acc.cloned :+ result.value)
          }
          Cloned(walked.state, sequence(walked.cloned).map(resolved => YamlValue.Sequence(resolved, tag, anchor)))
        case YamlValue.Mapping(entries, tag, anchor) =>
          // Key first, then value, BOTH always — the original built the pair before matching, so a
          // failed key still cloned (and counted) its value.
          final case class MapWalk(state: ResolveState, resolved: Vector[YamlEntry], valid: Boolean)
          val walked = entries.foldLeft(MapWalk(counted, Vector.empty, true)) { (acc, entry) =>
            val keyResult = cloneValue(acc.state, entry.key, visiting)
            val valueResult = cloneValue(keyResult.state, entry.value, visiting)
            keyResult.value match
              case Some(key) => valueResult.value match
                case Some(resolvedValue) =>
                  MapWalk(valueResult.state, acc.resolved :+ YamlEntry(key, resolvedValue, entry.span), acc.valid)
                case None => MapWalk(valueResult.state, acc.resolved, false)
              case None => MapWalk(valueResult.state, acc.resolved, false)
          }
          Cloned(walked.state, Option.when(walked.valid)(YamlValue.Mapping(walked.resolved, tag, anchor)))

    final case class DocWalk(state: ResolveState, documents: Vector[YamlDocument])
    val walked = stream.documents.foldLeft(DocWalk(ResolveState(Map.empty, Vector.empty, 0, 0), Vector.empty)) { (acc, document) =>
      // per-document anchor scope: only `anchors` resets at the boundary
      val fresh = acc.state.copy(anchors = Map.empty)
      document.value match
        case Some(value) =>
          val result = cloneValue(fresh, value, Set.empty)
          DocWalk(result.state, acc.documents :+ document.copy(value = result.value))
        case None =>
          DocWalk(fresh, acc.documents :+ document.copy(value = None))
    }
    if walked.state.diagnostics.nonEmpty then Left(walked.state.diagnostics) else Right(YamlValue.Stream(walked.documents))

  private def sequence(values: Vector[Option[YamlValue]]): Option[Vector[YamlValue]] =
    if values.forall(_.isDefined) then Some(values.flatMap(value => value)) else None

  private def nodeAnchor(value: YamlValue): Option[String] = value match
    case YamlValue.Mapping(_, _, anchor) => anchor
    case YamlValue.Sequence(_, _, anchor) => anchor
    case YamlValue.Scalar(_, _, anchor) => anchor
    case _ => None

  private def keyFingerprint(value: YamlValue): String = value match
    case YamlValue.Scalar(YamlScalar.StringValue(cooked, _, _), _, _) => s"s:$cooked"
    case YamlValue.Scalar(YamlScalar.NullValue(_), _, _)              => "n:"
    case YamlValue.Scalar(YamlScalar.BooleanValue(cooked, _), _, _)   => s"b:$cooked"
    case YamlValue.Scalar(YamlScalar.IntegerValue(lexeme), _, _)      => s"i:$lexeme"
    case YamlValue.Scalar(YamlScalar.FloatValue(lexeme), _, _)        => s"f:$lexeme"
    case YamlValue.Alias(name)                                        => s"a:$name"
    case YamlValue.Sequence(values, _, _)                              => values.map(keyFingerprint).mkString("q:[", ",", "]")
    case YamlValue.Mapping(entries, _, _) =>
      entries.map(entry => s"${keyFingerprint(entry.key)}=${keyFingerprint(entry.value)}").mkString("m:{", ",", "}")
    case YamlValue.Stream(documents) => s"d:${documents.size}"

  private def diagnostic(
      code: String,
      message: String,
      severity: Severity,
      span: Option[SourceSpan],
  ): Diagnostic = Diagnostic(code, message, severity, span, Some(YamlDialect.id))
