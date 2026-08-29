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
        val text = tokens.iterator.map(_.lexeme).mkString
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
      var problems: Vector[Diagnostic] = Vector.empty
      def reject(message: String, span: Option[SourceSpan]): Unit =
        if problems.isEmpty then
          problems = problems :+ diagnostic("uniml.yaml.projection-invalid-cst", message, Severity.Error, span)

      val head = tokens.head
      tokens.find(_.span.source != head.span.source).foreach { other =>
        reject(
          // NOT `s"… '${…}' …"`: a bare `'…'`-wrapped interpolation splice trips this
          // toolchain's parser in a large enough merged program (`YamlStructure.scala`'s
          // `validateFlow` has the identical fix, same reasoning). Plain concatenation instead.
          "the CST spans two sources — '" + head.span.source.value + "' and '" + other.span.source.value + "'",
          Some(other.span))
      }
      if tokens.map(_.id).distinct.size != tokens.size then
        reject("the CST has duplicate token ids", Some(head.span))
      var i = 1
      while i < tokens.size && problems.isEmpty do
        val prev = tokens(i - 1)
        val cur = tokens(i)
        if cur.id <= prev.id then
          reject(
            s"traversal order and token ids disagree: id ${cur.id} follows ${prev.id}",
            Some(cur.span))
        else if cur.span.start.offset < prev.span.end.offset then
          reject(
            s"token spans overlap or go backwards: ${cur.span.start.offset} follows ${prev.span.end.offset}",
            Some(cur.span))
        i += 1
      problems

  private def validate(stream: YamlValue.Stream): Vector[Diagnostic] =
    var allDiagnostics: Vector[Diagnostic] = Vector.empty
    stream.documents.foreach { document =>
      var anchors: Map[String, YamlValue] = Map.empty
      var anchorCount = 0
      var aliasCount = 0

      def visit(value: YamlValue, span: Option[SourceSpan]): Unit =
        nodeAnchor(value).foreach { name =>
          anchorCount += 1
          if anchorCount > 1_000_000 then
            allDiagnostics = allDiagnostics :+ diagnostic("uniml.yaml.limit.anchors", "YAML document exceeds the anchor limit", Severity.Fatal, span)
          if anchors.contains(name) then
            allDiagnostics = allDiagnostics :+ diagnostic("uniml.yaml.duplicate-anchor", s"duplicate YAML anchor '&$name' replaces its previous binding", Severity.Warning, span)
          anchors = anchors + (name -> value)
        }
        value match
          case YamlValue.Stream(_) => ()
          case YamlValue.Mapping(entries, _, _) =>
            var seenKeys: Set[String] = Set.empty
            entries.foreach { entry =>
              val fingerprint = keyFingerprint(entry.key)
              if seenKeys.contains(fingerprint) then
                allDiagnostics = allDiagnostics :+ diagnostic("uniml.yaml.duplicate-key", "duplicate YAML mapping key is preserved", Severity.Warning, Some(entry.span))
              seenKeys = seenKeys + fingerprint
              visit(entry.key, Some(entry.span))
              visit(entry.value, Some(entry.span))
            }
          case YamlValue.Sequence(values, _, _) => values.foreach(child => visit(child, span))
          case YamlValue.Scalar(_, _, _) => ()
          case YamlValue.Alias(name) =>
            aliasCount += 1
            if aliasCount > 1_000_000 then
              allDiagnostics = allDiagnostics :+ diagnostic("uniml.yaml.limit.aliases", "YAML document exceeds the alias limit", Severity.Fatal, span)
            if !anchors.contains(name) then
              allDiagnostics = allDiagnostics :+ diagnostic("uniml.yaml.undefined-alias", s"alias '*$name' has no preceding anchor in this document", Severity.Error, span)

      document.value.foreach(value => visit(value, None))
    }
    allDiagnostics

  private def resolve(
      stream: YamlValue.Stream,
      options: YamlProjectionOptions,
  ): Either[Vector[Diagnostic], YamlValue.Stream] =
    var diagnostics: Vector[Diagnostic] = Vector.empty
    var documents: Vector[YamlDocument] = Vector.empty
    var expansions = 0
    var nodes = 0
    stream.documents.foreach { document =>
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
      var anchors: Map[String, YamlValue] = Map.empty

      def cloneValue(value: YamlValue, visiting: Set[String]): Option[YamlValue] =
        nodeAnchor(value).foreach(name => anchors = anchors + (name -> value))
        nodes += 1
        if nodes > options.maxExpandedNodes then
          diagnostics = diagnostics :+ diagnostic(
            "uniml.yaml.limit.expansion",
            s"resolved YAML graph exceeds ${options.maxExpandedNodes} nodes",
            Severity.Fatal,
            None,
          )
          None
        else value match
          case YamlValue.Stream(_) => None
          case YamlValue.Alias(name) =>
            expansions += 1
            if expansions > options.maxAliasExpansions then
              diagnostics = diagnostics :+ diagnostic(
                "uniml.yaml.limit.expansion",
                s"YAML alias expansion exceeds ${options.maxAliasExpansions}",
                Severity.Fatal,
                None,
              )
              None
            else if visiting.contains(name) then
              diagnostics = diagnostics :+ diagnostic("uniml.yaml.alias-cycle", s"YAML alias cycle reaches '*$name'", Severity.Error, None)
              None
            else anchors.get(name) match
              case None =>
                diagnostics = diagnostics :+ diagnostic("uniml.yaml.undefined-alias", s"undefined YAML alias '*$name'", Severity.Error, None)
                None
              case Some(target) => cloneValue(target, visiting + name)
          case YamlValue.Scalar(value, tag, anchor) => Some(YamlValue.Scalar(value, tag, anchor))
          case YamlValue.Sequence(values, tag, anchor) =>
            sequence(values.map(child => cloneValue(child, visiting))).map(resolved => YamlValue.Sequence(resolved, tag, anchor))
          case YamlValue.Mapping(entries, tag, anchor) =>
            var resolved: Vector[YamlEntry] = Vector.empty
            var valid = true
            entries.foreach { entry =>
              (cloneValue(entry.key, visiting), cloneValue(entry.value, visiting)) match
                case (Some(key), Some(value)) => resolved = resolved :+ YamlEntry(key, value, entry.span)
                case _                       => valid = false
            }
            Option.when(valid)(YamlValue.Mapping(resolved, tag, anchor))

      val resolved = document.value.flatMap(value => cloneValue(value, Set.empty))
      documents = documents :+ document.copy(value = resolved)
    }
    if diagnostics.nonEmpty then Left(diagnostics) else Right(YamlValue.Stream(documents))

  private def sequence(values: Vector[Option[YamlValue]]): Option[Vector[YamlValue]] =
    var result: Vector[YamlValue] = Vector.empty
    var valid = true
    values.foreach {
      case Some(value) => result = result :+ value
      case None        => valid = false
    }
    Option.when(valid)(result)

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
