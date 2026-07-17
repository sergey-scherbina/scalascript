package scalascript.artifact

import java.nio.charset.StandardCharsets

import scala.meta.*

import scalascript.ast
import scalascript.interop.descriptor.*
import scalascript.parser.Parser
import scalascript.transform.EffectAnalysis

/** Strict declaration-only producer for the v3 managed API descriptor.
 *
 *  This projection intentionally consumes scala.meta declaration nodes rather
 *  than typer summaries or legacy `.scim` strings. It can therefore run before
 *  body type checking, retain exact source widths, and reject every public ABI
 *  shape that would otherwise require a guess.
 */
object PreBodyApiDescriptorProducer:

  val ControlAbiVersion: String = "ssc-control-v1"

  private val StandardTypes: Map[String, String] = Map(
    "List" -> "std.List",
    "Vector" -> "std.Vector",
    "Seq" -> "std.Seq",
    "Set" -> "std.Set",
    "Map" -> "std.Map",
    "Option" -> "std.Option",
    "Either" -> "std.Either"
  )

  private val DynamicTypes: Set[String] =
    Set("Any", "AnyRef", "Object", "Dynamic")

  private val UnsupportedNumericTypes: Set[String] =
    Set("Byte", "Short", "Float", "Decimal", "BigDecimal")

  private val ReservedEffectMarkerNames: Set[String] =
    Set("__effectDecl__", "__effectUnsupportedShape__")

  private final case class Binder(name: String, index: Int, kindArity: Int)
  private type BinderStack = Vector[Vector[Binder]]

  private final case class EffectHeader(
      name: String,
      reusable: Boolean,
      unsupportedShape: Boolean,
      sourceLine: Int,
      expectsStructuralMarker: Boolean
  )

  private final case class ParsedDeclarationBlock(
      path: String,
      stats: Vector[Stat],
      sectionSource: String,
      documentSource: Option[String],
      effectBindings: Vector[((Vector[String], String), EffectHeader)] = Vector.empty
  )

  private final case class ReparsedDeclarationCarrier(
      stats: Vector[Stat],
      lineShift: Int
  )

  private final case class ParsedDeclarations(
      stats: Vector[Stat],
      blocks: Vector[ParsedDeclarationBlock]
  )

  private final case class DeclarationWitness(
      kind: String,
      attributes: Vector[String],
      members: Vector[DeclarationWitness] = Vector.empty
  )

  private final case class EffectEvidenceWitness(
      owner: Vector[String],
      name: String,
      isEffect: Boolean,
      reusable: Boolean,
      unsupportedShape: Boolean,
      expectsStructuralMarker: Boolean
  )

  private final case class ValidatedEffectEvidence(
      witnesses: Vector[EffectEvidenceWitness],
      bindings: Vector[((Vector[String], String), EffectHeader)] = Vector.empty
  )

  private final case class EffectCandidate(
      owner: Vector[String],
      definition: Defn.Object
  )

  private final case class LocalAlias(
      definition: Defn.Type,
      owner: Vector[String],
      imports: ImportScope
  )

  private final case class LocalIdentity(
      stableId: String,
      isPublic: Boolean,
      ownerRepresentable: Boolean
  )

  private final case class LocalInventory(
      types: Map[(Vector[String], String), LocalIdentity],
      effects: Map[(Vector[String], String), LocalIdentity],
      owners: Map[String, LocalIdentity]
  )

  private enum IdentityDomain:
    case TypeIdentity, EffectIdentity

  private final case class WildcardImport(excludedNames: Set[String])

  private final case class ImportScope(
      exactBindings: Map[String, Vector[Option[String]]] = Map.empty,
      wildcardImports: Vector[WildcardImport] = Vector.empty
  ):
    def addExact(name: String, target: Option[String]): ImportScope =
      copy(exactBindings = exactBindings.updated(
        name,
        exactBindings.getOrElse(name, Vector.empty) :+ target
      ))

    def addWildcard(excludedNames: Set[String]): ImportScope =
      copy(wildcardImports = wildcardImports :+ WildcardImport(excludedNames))

  private final case class ProjectionContext(
      moduleId: String,
      rootNamespace: Vector[String],
      prefix: Vector[String],
      localTypes: Map[(Vector[String], String), LocalIdentity],
      localEffects: Map[(Vector[String], String), LocalIdentity],
      localOwners: Map[String, LocalIdentity],
      localAliases: Map[String, LocalAlias],
      requiredTargets: Vector[String],
      effectHeaders: Map[(Vector[String], String), EffectHeader],
      imports: ImportScope
  ):
    def nested(name: String): ProjectionContext = copy(prefix = prefix :+ name)
    def qualified(name: String): String = (prefix :+ name).mkString(".")

  def descriptor(module: ast.Module): Either[DescriptorError, ApiDescriptor] =
    for
      moduleId <- module.manifest.flatMap(_.name).map(_.trim).filter(_.nonEmpty)
        .toRight(error(
          "MISSING_MODULE_ID",
          "$.moduleId",
          "managed API descriptor production requires a non-empty manifest name"
        ))
      rootNamespace = module.manifest.flatMap(_.pkg)
        .filter(_.nonEmpty)
        .map(_.toVector)
        .getOrElse(Vector(moduleId))
      declarations <- topLevelStats(module)
      allStats = declarations.stats
      effectHeaders <- bindEffectHeaders(declarations.blocks, rootNamespace)
      requestedExports = module.manifest.map(_.exports).getOrElse(Nil)
      _ <- validateRequestedExports(allStats, requestedExports, rootNamespace)
      selectedStats = selectExports(allStats, requestedExports)
      localInventory = collectLocalInventory(allStats, rootNamespace, effectHeaders)
      inventoryContext = ProjectionContext(
        moduleId = moduleId,
        rootNamespace = rootNamespace,
        prefix = rootNamespace,
        localTypes = localInventory.types,
        localEffects = localInventory.effects,
        localOwners = localInventory.owners,
        localAliases = Map.empty,
        requiredTargets = module.manifest.map(_.targets.toVector).getOrElse(Vector.empty),
        effectHeaders = effectHeaders,
        imports = ImportScope()
      )
      localAliases = collectLocalAliases(allStats, inventoryContext)
      context = inventoryContext.copy(localAliases = localAliases)
      definitions <- projectStats(selectedStats, context)
      api <- DescriptorFactory.api(ControlAbiVersion, moduleId, definitions)
    yield api

  def canonicalJson(module: ast.Module): Either[DescriptorError, String] =
    descriptor(module)
      .flatMap(DescriptorCodec.encodeApi)
      .map(bytes => new String(bytes, StandardCharsets.UTF_8))

  private def isPlainPackageWrapper(definition: Defn.Object): Boolean =
    definition.mods.isEmpty &&
      definition.templ.earlyClause.isEmpty &&
      definition.templ.inits.isEmpty &&
      definition.templ.derives.isEmpty &&
      definition.templ.body.selfOpt.isEmpty

  private def unwrapExactPackage(
      stats: List[Stat],
      remaining: List[String]
  ): Option[List[Stat]] = remaining match
    case Nil => Some(stats)
    case head :: tail => stats match
      case List(obj: Defn.Object)
          if obj.name.value == head && isPlainPackageWrapper(obj) =>
        unwrapExactPackage(obj.templ.body.stats, tail)
      case _ => None

  private def topLevelStats(module: ast.Module): Either[DescriptorError, ParsedDeclarations] =
    val pkg = module.manifest.flatMap(_.pkg).getOrElse(Nil)

    def statsFromTree(
        stats: List[Stat],
        path: String
    ): Either[DescriptorError, Vector[Stat]] =
      if pkg.isEmpty then Right(stats.toVector)
      else unwrapExactPackage(stats, pkg)
        .map(_.toVector)
        .toRight(error(
          "UNSUPPORTED_PUBLIC_DECLARATION",
          path,
          s"parsed code block does not retain the exact plain package wrapper ${pkg.mkString(".")}"
        ))

    def sectionBlocks(
        section: ast.Section,
        path: String
    ): Either[DescriptorError, Vector[ParsedDeclarationBlock]] =
      for
        own <- traverse(section.content.toVector.zipWithIndex) { case (content, contentIndex) =>
          val contentPath = s"$path.content[$contentIndex]"
          content match
            case block: ast.Content.CodeBlock if ast.Lang.isParseable(block.lang) =>
              block.tree match
                case None if block.source.trim.isEmpty => Right(Vector.empty[ParsedDeclarationBlock])
                case None => Left(error(
                  "UNSUPPORTED_PUBLIC_DECLARATION",
                  contentPath,
                  block.parseError
                    .map(parseError => s"declaration block did not parse: ${parseError.message}")
                    .getOrElse("declaration block has no parsed syntax tree")
                ))
                case Some(node) => node.tree match
                  case source: Source =>
                    statsFromTree(source.stats, contentPath).map { stats =>
                      Vector(ParsedDeclarationBlock(
                        path = contentPath,
                        stats = stats,
                        sectionSource = block.source,
                        documentSource = None
                      ))
                    }
                  case termBlock: Term.Block =>
                    statsFromTree(termBlock.stats, contentPath).map { stats =>
                      Vector(ParsedDeclarationBlock(
                        path = contentPath,
                        stats = stats,
                        sectionSource = block.source,
                        documentSource = None
                      ))
                    }
                  case other => Left(error(
                    "UNSUPPORTED_PUBLIC_DECLARATION",
                    contentPath,
                    s"declaration block root ${other.productPrefix} is unsupported"
                  ))
            case _ => Right(Vector.empty[ParsedDeclarationBlock])
        }
        nested <- traverse(section.subsections.toVector.zipWithIndex) { case (child, childIndex) =>
          sectionBlocks(child, s"$path.subsections[$childIndex]")
        }
      yield own.flatten ++ nested.flatten

    for
      sectionBlocks <- traverse(module.sections.toVector.zipWithIndex) { case (section, sectionIndex) =>
        sectionBlocks(section, s"$$.sections[$sectionIndex]")
      }.map(_.flatten)
      pairedBlocks <- module.document match
        case Some(document) =>
          val retainedSources = retainedExecutableSources(document)
          if retainedSources.size != sectionBlocks.size then
            Left(error(
              "UNSUPPORTED_PUBLIC_DECLARATION",
              "$.sections",
              s"retained executable source blocks (${retainedSources.size}) do not match parsed section blocks (${sectionBlocks.size})"
            ))
          else Right(sectionBlocks.zip(retainedSources).map { case (block, source) =>
            block.copy(
              documentSource = Some(source)
            )
          })
        case None => Right(sectionBlocks)
      verifiedBlocks <- traverse(pairedBlocks)(verifyDeclarationCorrespondence(_, pkg))
    yield ParsedDeclarations(
      stats = verifiedBlocks.flatMap(_.stats),
      blocks = verifiedBlocks
    )

  private def retainedExecutableSources(document: ast.DocumentContent): Vector[String] =
    def fromBlocks(blocks: Iterable[ast.ContentBlock]): Vector[String] =
      blocks.iterator.collect {
        case ast.ContentBlock.Embedded(lang, source, ast.EmbeddedKind.Executable, _, _)
            if ast.Lang.isParseable(lang) => source
      }.toVector

    def fromSection(section: ast.SectionContent): Vector[String] =
      fromBlocks(section.blocks) ++ section.children.iterator.flatMap(fromSection).toVector

    fromBlocks(document.blocks) ++ document.sections.iterator.flatMap(fromSection).toVector

  private def verifyDeclarationCorrespondence(
      block: ParsedDeclarationBlock,
      pkg: List[String]
  ): Either[DescriptorError, ParsedDeclarationBlock] =
    val normalizedStored = normalizeDeclarationStats(block.stats)
    val storedWitness = declarationWitnesses(normalizedStored)

    def reparse(
        source: String,
        carrier: String,
        packageWrapped: Boolean
    ): Either[DescriptorError, ReparsedDeclarationCarrier] =
      Parser.parseScalaSource(source).toRight(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        block.path,
        s"retained $carrier source cannot be reparsed for declaration correspondence"
      )).flatMap { node =>
        val reparsed = node.tree match
          case parsed: Source => Right(parsed.stats -> 0)
          case parsed: Term.Block => Right(parsed.stats -> 1)
          case other => Left(error(
            "UNSUPPORTED_PUBLIC_DECLARATION",
            block.path,
            s"retained $carrier source reparsed as unsupported root ${other.productPrefix}"
          ))
        reparsed.flatMap { case (stats, lineShift) =>
          val unwrapped = if packageWrapped && pkg.nonEmpty then
            unwrapExactPackage(stats, pkg).toRight(error(
              "UNSUPPORTED_PUBLIC_DECLARATION",
              block.path,
              s"retained $carrier source does not contain the exact plain package wrapper ${pkg.mkString(".")}"
            ))
          else Right(stats)
          unwrapped.map(stats => ReparsedDeclarationCarrier(
            normalizeDeclarationStats(stats.toVector),
            lineShift
          ))
        }
      }

    def requireStoredWitness(
        retained: ReparsedDeclarationCarrier,
        carrier: String
    ): Either[DescriptorError, Unit] =
      Either.cond(
        declarationWitnesses(retained.stats) == storedWitness,
        (),
        error(
          "UNSUPPORTED_PUBLIC_DECLARATION",
          block.path,
          s"retained $carrier source declaration headers do not match the stored section AST"
        )
      )

    def requireStoredEffectEvidence(
        source: String,
        retained: ReparsedDeclarationCarrier,
        carrier: String,
        alreadyPreprocessed: Boolean,
        storedEffectEvidence: ValidatedEffectEvidence
    ): Either[DescriptorError, ValidatedEffectEvidence] =
      val retainedEvidence = if alreadyPreprocessed then
        effectEvidenceFromPreprocessedStats(retained.stats, block.path, carrier)
      else
        effectEvidenceFromRawSource(
          source,
          retained.stats,
          block.path,
          carrier,
          retained.lineShift
        )
      retainedEvidence.flatMap { evidence =>
        Either.cond(
          evidence.witnesses == storedEffectEvidence.witnesses,
          evidence,
          error(
            "UNSUPPORTED_PUBLIC_DECLARATION",
            block.path,
            s"retained $carrier source effect/object headers do not match the stored section AST"
          )
        )
      }

    for
      storedEffectEvidence <- effectEvidenceFromPreprocessedStats(
        normalizedStored,
        block.path,
        "stored section AST"
      )
      sectionStats <- reparse(block.sectionSource, "code-block", packageWrapped = true)
      _ <- requireStoredWitness(sectionStats, "code-block")
      codeBlockEvidence <- requireStoredEffectEvidence(
        block.sectionSource,
        sectionStats,
        "code-block",
        alreadyPreprocessed = pkg.nonEmpty,
        storedEffectEvidence
      )
      documentEvidence <- block.documentSource match
        case Some(source) =>
          for
            documentStats <- reparse(source, "document", packageWrapped = false)
            _ <- requireStoredWitness(documentStats, "document")
            evidence <- requireStoredEffectEvidence(
              source,
              documentStats,
              "document",
              alreadyPreprocessed = false,
              storedEffectEvidence
            )
          yield Some(evidence)
        case None => Right(None)
      selectedBindings = documentEvidence
        .map(_.bindings)
        .getOrElse(codeBlockEvidence.bindings)
    yield block.copy(
      stats = normalizedStored,
      effectBindings = selectedBindings
    )

  private def normalizeDeclarationStats(stats: Vector[Stat]): Vector[Stat] =
    Parser.desugarTypeLambdaAliases(Source(stats.toList)) match
      case normalized: Source => normalized.stats.toVector
      case _ => stats

  private def declarationWitnesses(stats: Iterable[Stat]): Vector[DeclarationWitness] =
    stats.iterator.flatMap(declarationWitness).toVector

  private def effectEvidenceCandidates(
      stats: Iterable[Stat],
      owner: Vector[String] = Vector.empty
  ): Vector[EffectCandidate] =
    stats.iterator.flatMap {
      case definition: Defn.Object =>
        EffectCandidate(owner, definition) +:
          effectEvidenceCandidates(definition.templ.body.stats, owner :+ definition.name.value)
      case definition: Defn.Class =>
        effectEvidenceCandidates(definition.templ.body.stats, owner :+ definition.name.value)
      case definition: Defn.Trait =>
        effectEvidenceCandidates(definition.templ.body.stats, owner :+ definition.name.value)
      case definition: Defn.Enum =>
        effectEvidenceCandidates(definition.templ.body.stats, owner :+ definition.name.value)
      case _ => Vector.empty
    }.toVector

  private def reservedEffectMarkerNames(stat: Stat): Vector[String] =
    val definitionNames = declarationNames(stat)
    val abstractNames = stat match
      case declaration: Decl.Type => Vector(declaration.name.value)
      case declaration: Decl.Def => Vector(declaration.name.value)
      case declaration: Decl.Val => declaration.pats.collect {
        case Pat.Var(name) => name.value
      }.toVector
      case declaration: Decl.Var => declaration.pats.collect {
        case Pat.Var(name) => name.value
      }.toVector
      case _ => Vector.empty
    (definitionNames ++ abstractNames).filter(ReservedEffectMarkerNames.contains)

  private def isCanonicalEffectSentinel(stat: Stat, expectedName: String): Boolean = stat match
    case definition: Defn.Type =>
      definition.name.value == expectedName &&
        definition.mods.map(_.syntax) == List("private") &&
        definition.tparamClause.values.isEmpty &&
        definition.bounds.lo.isEmpty &&
        definition.bounds.hi.isEmpty &&
        definition.bounds.context.isEmpty &&
        definition.bounds.view.isEmpty &&
        definition.body.syntax == "true"
    case _ => false

  private def validateEffectSentinels(
      stats: Iterable[Stat],
      path: String,
      carrier: String
  ): Either[DescriptorError, Unit] =
    def failure(message: String): DescriptorError = error(
      "UNSUPPORTED_PUBLIC_DECLARATION",
      path,
      s"retained $carrier $message"
    )

    def visit(items: Iterable[Stat], allowObjectSentinels: Boolean): Either[DescriptorError, Unit] =
      val values = items.toVector
      val occurrences = values.flatMap { stat =>
        reservedEffectMarkerNames(stat).map(_ -> stat)
      }
      val validateCurrent =
        if occurrences.nonEmpty && !allowObjectSentinels then
          Left(failure("contains a reserved effect sentinel outside an effect-object body"))
        else
          occurrences.collectFirst {
            case (name, stat) if !isCanonicalEffectSentinel(stat, name) => name
          } match
            case Some(name) => Left(failure(
              s"contains non-canonical reserved effect sentinel $name"
            ))
            case None =>
              val declarationCount = occurrences.count(_._1 == "__effectDecl__")
              val unsupportedCount = occurrences.count(_._1 == "__effectUnsupportedShape__")
              val structurallyMarked = values.exists(isMultiShotMarker) || values.exists {
                case operation: Defn.Def => EffectAnalysis.isEffectOpDef(operation.body)
                case _ => false
              }
              if declarationCount > 1 then
                Left(failure("contains duplicate __effectDecl__ sentinels"))
              else if unsupportedCount > 1 then
                Left(failure("contains duplicate __effectUnsupportedShape__ sentinels"))
              else if structurallyMarked && declarationCount != 1 then
                Left(failure("has structural effect markers without exactly one __effectDecl__ sentinel"))
              else if unsupportedCount == 1 && declarationCount != 1 then
                Left(failure("has __effectUnsupportedShape__ without exactly one __effectDecl__ sentinel"))
              else Right(())

      validateCurrent.flatMap { _ =>
        traverse(values) {
          case definition: Defn.Object =>
            visit(definition.templ.body.stats, allowObjectSentinels = true)
          case definition: Defn.Class =>
            visit(definition.templ.body.stats, allowObjectSentinels = false)
          case definition: Defn.Trait =>
            visit(definition.templ.body.stats, allowObjectSentinels = false)
          case definition: Defn.Enum =>
            visit(definition.templ.body.stats, allowObjectSentinels = false)
          case _ => Right(())
        }.map(_ => ())
      }

    visit(stats, allowObjectSentinels = false)

  private def effectEvidenceFromPreprocessedStats(
      stats: Iterable[Stat],
      path: String,
      carrier: String
  ): Either[DescriptorError, ValidatedEffectEvidence] =
    validateEffectSentinels(stats, path, carrier).map { _ =>
      ValidatedEffectEvidence(effectEvidenceCandidates(stats).map { candidate =>
        val effect = isStructurallyMarkedEffect(candidate.definition)
        val reusable = effect && candidate.definition.templ.body.stats.exists(isMultiShotMarker)
        val hasOperation = effect && hasEffectOperationMarker(candidate.definition)
        EffectEvidenceWitness(
          owner = candidate.owner,
          name = candidate.definition.name.value,
          isEffect = effect,
          reusable = reusable,
          unsupportedShape = effect && candidate.definition.templ.body.stats.exists(isUnsupportedEffectShapeMarker),
          expectsStructuralMarker = effect && (reusable || hasOperation)
        )
      })
    }

  private def effectEvidenceFromRawSource(
      source: String,
      stats: Vector[Stat],
      path: String,
      carrier: String,
      lineShift: Int
  ): Either[DescriptorError, ValidatedEffectEvidence] =
    val headers = declaredEffectHeaders(source)
    val candidates = effectEvidenceCandidates(stats)
    val preprocessorInsertions = effectPreprocessorInsertions(source)
    val positionedHeaders = headers.map { header =>
      val insertedBefore = preprocessorInsertions.iterator
        .takeWhile(_._1 < header.sourceLine)
        .map(_._2)
        .sum
      header -> (header.sourceLine + insertedBefore + lineShift)
    }
    val markedCandidates = candidates.zipWithIndex.filter { case (candidate, _) =>
      isStructurallyMarkedEffect(candidate.definition)
    }
    var usedHeaders = Set.empty[Int]

    def selectHeader(
        candidate: EffectCandidate
    ): Either[DescriptorError, (EffectHeader, Int)] =
      val position = candidate.definition.pos
      val exact = positionedHeaders.zipWithIndex.filter { case ((header, processedLine), headerIndex) =>
        !usedHeaders.contains(headerIndex) &&
          header.name == candidate.definition.name.value &&
          !position.isEmpty && position.startLine == processedLine
      }
      val fallback = positionedHeaders.zipWithIndex.filter { case ((header, _), headerIndex) =>
        !usedHeaders.contains(headerIndex) &&
          header.name == candidate.definition.name.value
      }
      exact match
        case Vector(((header, _), headerIndex)) => Right(header -> headerIndex)
        case Vector() if position.isEmpty && fallback.size == 1 =>
          val ((header, _), headerIndex) = fallback.head
          Right(header -> headerIndex)
        case Vector() => Left(error(
          "UNSUPPORTED_PUBLIC_DECLARATION",
          path,
          s"retained $carrier marked effect ${candidate.definition.name.value} has no unique declaration-scope source header"
        ))
        case _ => Left(error(
          "UNSUPPORTED_PUBLIC_DECLARATION",
          path,
          s"retained $carrier marked effect ${candidate.definition.name.value} has ambiguous declaration-scope source headers"
        ))

    for
      _ <- validateEffectSentinels(stats, path, carrier)
      selected <- traverse(markedCandidates) { case (candidate, candidateIndex) =>
        selectHeader(candidate).map { case (header, headerIndex) =>
          usedHeaders += headerIndex
          candidateIndex -> header
        }
      }
      selectedByCandidate = selected.toMap
      _ <- positionedHeaders.zipWithIndex.collectFirst {
        case ((header, processedLine), headerIndex)
            if !usedHeaders.contains(headerIndex) && candidates.exists { candidate =>
              val position = candidate.definition.pos
              !position.isEmpty && position.startLine == processedLine
            } =>
          error(
            "UNSUPPORTED_PUBLIC_DECLARATION",
            path,
            s"retained $carrier declaration-scope effect header ${header.name} does not bind a marked parsed declaration"
          )
      }.toLeft(())
      witnesses = candidates.zipWithIndex.map { case (candidate, candidateIndex) =>
        selectedByCandidate.get(candidateIndex) match
          case Some(header) => EffectEvidenceWitness(
            owner = candidate.owner,
            name = candidate.definition.name.value,
            isEffect = true,
            reusable = header.reusable,
            unsupportedShape = header.unsupportedShape,
            expectsStructuralMarker = header.expectsStructuralMarker
          )
          case None => EffectEvidenceWitness(
            owner = candidate.owner,
            name = candidate.definition.name.value,
            isEffect = false,
            reusable = false,
            unsupportedShape = false,
            expectsStructuralMarker = false
          )
      }
      bindings = selected.map { case (candidateIndex, header) =>
        val candidate = candidates(candidateIndex)
        (candidate.owner -> candidate.definition.name.value) -> header
      }
    yield ValidatedEffectEvidence(witnesses, bindings)

  private def importWitness(imported: Import): String =
    encodeWitnessParts(imported.importers.map { importer =>
      encodeWitnessParts(Vector(
        importer.ref.structure,
        encodeWitnessParts(importer.importees.map(importeeWitness))
      ))
    })

  private def importeeWitness(importee: Importee): String = importee match
    case Importee.Name(name) => encodeWitnessParts(Vector("name", name.value))
    case Importee.Rename(name, rename) =>
      encodeWitnessParts(Vector("rename", name.value, rename.value))
    case Importee.Unimport(name) => encodeWitnessParts(Vector("unimport", name.value))
    case Importee.Wildcard() => encodeWitnessParts(Vector("wildcard"))
    case Importee.Given(tpe) => encodeWitnessParts(Vector("given", tpe.structure))
    case Importee.GivenAll() => encodeWitnessParts(Vector("given-all"))
    case other => encodeWitnessParts(Vector(other.productPrefix, other.structure))

  private def declarationWitness(stat: Stat): Option[DeclarationWitness] = stat match
    case imported: Import => Some(DeclarationWitness(
      kind = "import",
      attributes = Vector(importWitness(imported))
    ))
    case definition: Defn.Def => Some(DeclarationWitness(
      kind = "def",
      attributes = Vector(
        modifiersWitness(definition.mods),
        definition.name.value,
        parameterGroupsWitness(definition.paramClauseGroups),
        definition.decltpe.map(_.structure).getOrElse(""),
        EffectAnalysis.isEffectOpDef(definition.body).toString
      )
    ))
    case definition: Defn.Val => Some(DeclarationWitness(
      kind = "val",
      attributes = Vector(
        modifiersWitness(definition.mods),
        encodeWitnessParts(definition.pats.map(_.structure)),
        definition.decltpe.map(_.structure).getOrElse(""),
        isMultiShotMarker(definition).toString
      )
    ))
    case definition: Defn.Var => Some(DeclarationWitness(
      kind = "var",
      attributes = Vector(
        modifiersWitness(definition.mods),
        encodeWitnessParts(definition.pats.map(_.structure)),
        definition.decltpe.map(_.structure).getOrElse("")
      )
    ))
    case definition: Defn.Class => Some(DeclarationWitness(
      kind = "class",
      attributes = nominalWitness(
        definition.mods,
        definition.name.value,
        definition.tparamClause.values,
        definition.ctor,
        definition.templ
      ),
      members = declarationWitnesses(definition.templ.body.stats)
    ))
    case definition: Defn.Trait => Some(DeclarationWitness(
      kind = "trait",
      attributes = nominalWitness(
        definition.mods,
        definition.name.value,
        definition.tparamClause.values,
        definition.ctor,
        definition.templ
      ),
      members = declarationWitnesses(definition.templ.body.stats)
    ))
    case definition: Defn.Enum => Some(DeclarationWitness(
      kind = "enum",
      attributes = nominalWitness(
        definition.mods,
        definition.name.value,
        definition.tparamClause.values,
        definition.ctor,
        definition.templ
      ),
      members = declarationWitnesses(definition.templ.body.stats)
    ))
    case definition: Defn.Object => Some(DeclarationWitness(
      kind = "object",
      attributes = Vector(
        modifiersWitness(definition.mods),
        definition.name.value,
        templateHeaderWitness(definition.templ)
      ),
      members = declarationWitnesses(definition.templ.body.stats)
    ))
    case definition: Defn.Type => Some(DeclarationWitness(
      kind = "type",
      attributes = Vector(
        modifiersWitness(definition.mods),
        definition.name.value,
        typeParametersWitness(definition.tparamClause.values),
        definition.body.structure,
        definition.bounds.structure
      )
    ))
    case definition: Defn.EnumCase => Some(DeclarationWitness(
      kind = "enum-case",
      attributes = Vector(
        modifiersWitness(definition.mods),
        definition.name.value,
        typeParametersWitness(definition.tparamClause.values),
        constructorWitness(definition.ctor),
        encodeWitnessParts(definition.inits.map(initWitness))
      )
    ))
    case definition: Defn.RepeatedEnumCase => Some(DeclarationWitness(
      kind = "repeated-enum-case",
      attributes = Vector(
        modifiersWitness(definition.mods),
        encodeWitnessParts(definition.cases.map(_.value))
      )
    ))
    case definition: Defn.Given => Some(DeclarationWitness(
      kind = definition.productPrefix,
      attributes = Vector(
        modifiersWitness(definition.mods),
        definition.name.value,
        parameterGroupsWitness(definition.paramClauseGroups),
        templateHeaderWitness(definition.templ)
      ),
      members = declarationWitnesses(definition.templ.body.stats)
    ))
    case definition: Defn.GivenAlias => Some(DeclarationWitness(
      kind = definition.productPrefix,
      attributes = Vector(
        modifiersWitness(definition.mods),
        definition.name.value,
        parameterGroupsWitness(definition.paramClauseGroups),
        definition.decltpe.structure
      )
    ))
    case definition: Defn.ExtensionGroup => Some(DeclarationWitness(
      kind = definition.productPrefix,
      attributes = Vector(
        parameterGroupsWitness(definition.paramClauseGroup.toVector)
      ),
      members = nestedDeclarationWitnesses(definition.body)
    ))
    case definition: Defn.Macro => Some(DeclarationWitness(
      kind = definition.productPrefix,
      attributes = Vector(
        modifiersWitness(definition.mods),
        definition.name.value,
        parameterGroupsWitness(definition.paramClauseGroups),
        definition.decltpe.map(_.structure).getOrElse("")
      )
    ))
    case declaration: Decl => Some(DeclarationWitness(
      kind = declaration.productPrefix,
      attributes = Vector(declaration.structure)
    ))
    case exported: Export => Some(DeclarationWitness(
      kind = "export",
      attributes = Vector(exported.structure)
    ))
    case definition: Defn => Some(DeclarationWitness(
      kind = definition.productPrefix,
      attributes = Vector(definition.structure)
    ))
    case _ => None

  private def nestedDeclarationWitnesses(stat: Stat): Vector[DeclarationWitness] = stat match
    case block: Term.Block => declarationWitnesses(block.stats)
    case other => declarationWitness(other).toVector

  private def nominalWitness(
      mods: List[Mod],
      name: String,
      typeParameters: Iterable[Type.Param],
      constructor: Ctor.Primary,
      template: Template
  ): Vector[String] = Vector(
    modifiersWitness(mods),
    name,
    typeParametersWitness(typeParameters),
    constructorWitness(constructor),
    templateHeaderWitness(template)
  )

  private def constructorWitness(constructor: Ctor.Primary): String =
    encodeWitnessParts(Vector(
      modifiersWitness(constructor.mods),
      parameterClausesWitness(constructor.paramClauses)
    ))

  private def parameterGroupsWitness(groups: Iterable[Member.ParamClauseGroup]): String =
    encodeWitnessParts(groups.map { group =>
      encodeWitnessParts(Vector(
        typeParametersWitness(group.tparamClause.values),
        parameterClausesWitness(group.paramClauses)
      ))
    })

  private def parameterClausesWitness(clauses: Iterable[Term.ParamClause]): String =
    encodeWitnessParts(clauses.map { clause =>
      encodeWitnessParts(Vector(
        clause.mod.map(_.structure).getOrElse(""),
        encodeWitnessParts(clause.values.map(parameterWitness))
      ))
    })

  private def parameterWitness(parameter: Term.Param): String =
    encodeWitnessParts(Vector(
      modifiersWitness(parameter.mods),
      parameter.name.value,
      parameter.decltpe.map(_.structure).getOrElse(""),
      parameter.default.nonEmpty.toString
    ))

  private def typeParametersWitness(parameters: Iterable[Type.Param]): String =
    encodeWitnessParts(parameters.map { parameter =>
      encodeWitnessParts(Vector(
        modifiersWitness(parameter.mods),
        parameter.name.value,
        typeParametersWitness(parameter.tparamClause.values),
        parameter.bounds.structure
      ))
    })

  private def templateHeaderWitness(template: Template): String =
    encodeWitnessParts(Vector(
      template.earlyClause.map(_.structure).getOrElse(""),
      encodeWitnessParts(template.inits.map(initWitness)),
      template.body.selfOpt.map(_.structure).getOrElse(""),
      encodeWitnessParts(template.derives.map(_.structure))
    ))

  private def initWitness(init: Init): String =
    encodeWitnessParts(Vector(
      init.tpe.structure,
      encodeWitnessParts(init.argClauses.map(clause => clause.values.size.toString))
    ))

  private def modifiersWitness(mods: Iterable[Mod]): String =
    encodeWitnessParts(mods.map(_.structure))

  private def encodeWitnessParts(parts: Iterable[String]): String =
    parts.iterator.map(part => s"${part.length}:$part").mkString

  private def selectExports(stats: Vector[Stat], requested: List[String]): Vector[Stat] =
    val allowed = requested.toSet
    stats.filter { stat =>
      stat.isInstanceOf[Import] ||
        (isPublic(stat) && (allowed.isEmpty || declarationNames(stat).exists(allowed.contains)))
    }

  private def validateRequestedExports(
      stats: Vector[Stat],
      requested: List[String],
      rootNamespace: Vector[String]
  ): Either[DescriptorError, Unit] =
    val available = stats.iterator
      .filter(isPublic)
      .flatMap(declarationNames)
      .toSet
    requested.find(name => !available.contains(name)) match
      case Some(name) => Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        s"$$.symbols[${(rootNamespace :+ name).mkString(".")}]",
        s"manifest export $name has no local parsed declaration header"
      ))
      case None => Right(())

  private def declarationNames(stat: Stat): Vector[String] = stat match
    case value: Defn.Val => value.pats.collect { case Pat.Var(name) => name.value }.toVector
    case value: Defn.Var => value.pats.collect { case Pat.Var(name) => name.value }.toVector
    case definition: Defn.Def => Vector(definition.name.value)
    case definition: Defn.Class => Vector(definition.name.value)
    case definition: Defn.Object => Vector(definition.name.value)
    case definition: Defn.Trait => Vector(definition.name.value)
    case definition: Defn.Enum => Vector(definition.name.value)
    case definition: Defn.Type => Vector(definition.name.value)
    case definition: Defn.Given if definition.name.value.nonEmpty => Vector(definition.name.value)
    case _ => Vector.empty

  private def isPublic(stat: Stat): Boolean =
    val mods = stat match
      case withMods: Stat.WithMods => withMods.mods
      case _ => Nil
    !mods.exists(mod => mod.is[Mod.Private] || mod.is[Mod.Protected]) &&
      !mods.exists(isInternalAnnotation)

  private def isInternalAnnotation(mod: Mod): Boolean = mod match
    case Mod.Annot(init) =>
      init.tpe match
        case Type.Name(name) => name == "internal"
        case Type.Select(_, Type.Name(name)) => name == "internal"
        case _ => false
    case _ => false

  private def collectLocalInventory(
      stats: Vector[Stat],
      prefix: Vector[String],
      effectHeaders: Map[(Vector[String], String), EffectHeader]
  ): LocalInventory =
    val types = Map.newBuilder[(Vector[String], String), LocalIdentity]
    val effects = Map.newBuilder[(Vector[String], String), LocalIdentity]
    val owners = Map.newBuilder[String, LocalIdentity]

    def stableIdentity(
        owner: Vector[String],
        name: String,
        effectivelyPublic: Boolean,
        ownerRepresentable: Boolean
    ): LocalIdentity =
      LocalIdentity(
        stableId = (owner :+ name).mkString("."),
        isPublic = effectivelyPublic,
        ownerRepresentable = ownerRepresentable
      )

    def addType(
        owner: Vector[String],
        name: String,
        effectivelyPublic: Boolean,
        ownerRepresentable: Boolean
    ): Unit =
      if !ReservedEffectMarkerNames.contains(name) then
        types += (owner -> name) ->
          stableIdentity(owner, name, effectivelyPublic, ownerRepresentable)

    def addOwner(
        owner: Vector[String],
        name: String,
        effectivelyPublic: Boolean,
        representableAsOwner: Boolean
    ): Unit =
      val identity = stableIdentity(owner, name, effectivelyPublic, representableAsOwner)
      owners += identity.stableId -> identity

    def visit(
        items: Iterable[Stat],
        owner: Vector[String],
        ownerIsPublic: Boolean,
        ownerRepresentable: Boolean
    ): Unit =
      items.foreach {
        case definition: Defn.Class =>
          val effectivelyPublic = ownerIsPublic && isPublic(definition)
          addType(owner, definition.name.value, effectivelyPublic, ownerRepresentable)
          addOwner(owner, definition.name.value, effectivelyPublic, representableAsOwner = false)
          visit(
            definition.templ.body.stats,
            owner :+ definition.name.value,
            effectivelyPublic,
            ownerRepresentable = false
          )
        case definition: Defn.Trait =>
          val effectivelyPublic = ownerIsPublic && isPublic(definition)
          addType(owner, definition.name.value, effectivelyPublic, ownerRepresentable)
          addOwner(owner, definition.name.value, effectivelyPublic, representableAsOwner = false)
          visit(
            definition.templ.body.stats,
            owner :+ definition.name.value,
            effectivelyPublic,
            ownerRepresentable = false
          )
        case definition: Defn.Enum =>
          val effectivelyPublic = ownerIsPublic && isPublic(definition)
          addType(owner, definition.name.value, effectivelyPublic, ownerRepresentable)
          addOwner(owner, definition.name.value, effectivelyPublic, representableAsOwner = false)
          visit(
            definition.templ.body.stats,
            owner :+ definition.name.value,
            effectivelyPublic,
            ownerRepresentable = false
          )
        case definition: Defn.Object =>
          val effectivelyPublic = ownerIsPublic && isPublic(definition)
          addOwner(
            owner,
            definition.name.value,
            effectivelyPublic,
            representableAsOwner = ownerRepresentable
          )
          if isEffectObject(definition) || effectHeaders.contains(owner -> definition.name.value) then
            effects += (owner -> definition.name.value) ->
              stableIdentity(owner, definition.name.value, effectivelyPublic, ownerRepresentable)
          visit(
            definition.templ.body.stats,
            owner :+ definition.name.value,
            effectivelyPublic,
            ownerRepresentable
          )
        case definition: Defn.Type =>
          addType(
            owner,
            definition.name.value,
            ownerIsPublic && isPublic(definition),
            ownerRepresentable
          )
        case declaration: Decl.Type =>
          addType(
            owner,
            declaration.name.value,
            ownerIsPublic && isPublic(declaration),
            ownerRepresentable
          )
        case _ => ()
      }

    visit(stats, prefix, ownerIsPublic = true, ownerRepresentable = true)
    LocalInventory(types.result(), effects.result(), owners.result())

  private def collectLocalAliases(
      stats: Vector[Stat],
      context: ProjectionContext
  ): Map[String, LocalAlias] =
    val builder = Map.newBuilder[String, LocalAlias]

    def visit(items: Iterable[Stat], initialContext: ProjectionContext): Unit =
      var activeContext = initialContext
      items.foreach {
        case imported: Import =>
          activeContext = activeContext.copy(
            imports = applyImport(activeContext.imports, imported, activeContext)
          )
        case definition: Defn.Type
            if !ReservedEffectMarkerNames.contains(definition.name.value) =>
          val stableId = (activeContext.prefix :+ definition.name.value).mkString(".")
          builder += stableId -> LocalAlias(
            definition,
            activeContext.prefix,
            activeContext.imports
          )
        case definition: Defn.Object =>
          visit(definition.templ.body.stats, activeContext.nested(definition.name.value))
        case definition: Defn.Class =>
          visit(definition.templ.body.stats, activeContext.nested(definition.name.value))
        case definition: Defn.Trait =>
          visit(definition.templ.body.stats, activeContext.nested(definition.name.value))
        case definition: Defn.Enum =>
          visit(definition.templ.body.stats, activeContext.nested(definition.name.value))
        case _ => ()
      }

    visit(stats, context)
    builder.result()

  private def effectPreprocessorInsertions(source: String): Vector[(Int, Int)] =
    val effectLine =
      """^(\s*)(multi\s+)?effect\s+(\w+)(\[[^\]]*\])?(\s+extends\s+[^:]+)?\s*:""".r
    val lines = source.linesIterator.toVector
    val insertions = Vector.newBuilder[(Int, Int)]
    var lineIndex = 0
    while lineIndex < lines.size do
      effectLine.findFirstMatchIn(lines(lineIndex)) match
        case Some(matched) =>
          val baseIndent = matched.group(1).length
          val reusable = matched.group(2) != null
          val unsupportedShape = matched.group(4) != null || matched.group(5) != null
          val inserted = 2 + (if reusable then 1 else 0) + (if unsupportedShape then 1 else 0)
          insertions += lineIndex -> inserted
          lineIndex += 1
          while lineIndex < lines.size && {
            val line = lines(lineIndex)
            line.isBlank || (line.nonEmpty && line.indexWhere(_ != ' ') > baseIndent)
          } do lineIndex += 1
        case None => lineIndex += 1
    insertions.result()

  private def declaredEffectHeaders(source: String): Vector[EffectHeader] =
    val header =
      """(?m)^[ \t]*(multi[ \t]+)?effect[ \t]+([A-Za-z_][A-Za-z0-9_]*)([ \t]*\[[^\]\r\n]*\])?([ \t]+extends[ \t]+[^\r\n:]+)?[ \t]*:""".r

    val lexicalSource = sanitizeForEffectHeaders(source)
    val lexicalLines = lexicalSource.linesIterator.toVector

    def hasOperationAfter(sourceLine: Int, baseIndent: Int): Boolean =
      var line = sourceLine + 1
      var found = false
      var inside = true
      while line < lexicalLines.size && inside && !found do
        val value = lexicalLines(line)
        val firstCode = value.indexWhere(char => char != ' ' && char != '\t')
        if value.trim.isEmpty then line += 1
        else if firstCode > baseIndent then
          found = value.drop(firstCode).startsWith("def ")
          line += 1
        else inside = false
      found

    header.findAllMatchIn(lexicalSource)
      .map { matched =>
        val sourceLine = lexicalSource.iterator.take(matched.start).count(_ == '\n')
        val baseIndent = matched.matched.indexWhere(char => char != ' ' && char != '\t') match
          case -1 => 0
          case value => value
        val reusable = matched.group(1) != null
        EffectHeader(
          name = matched.group(2),
          reusable = reusable,
          unsupportedShape = matched.group(3) != null || matched.group(4) != null,
          sourceLine = sourceLine,
          expectsStructuralMarker = reusable || hasOperationAfter(sourceLine, baseIndent)
        )
      }
      .toVector

  private def bindEffectHeaders(
      blocks: Vector[ParsedDeclarationBlock],
      rootNamespace: Vector[String]
  ): Either[DescriptorError, Map[(Vector[String], String), EffectHeader]] =
    val bindings = blocks.flatMap { block =>
      block.effectBindings.map { case ((owner, name), header) =>
        ((rootNamespace ++ owner) -> name) -> header
      }
    }
    bindings.groupBy(_._1).collectFirst { case (key, values) if values.size > 1 => key } match
      case Some((owner, name)) => Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        s"$$.symbols[${(owner :+ name).mkString(".")}]",
        s"effect declaration ${(owner :+ name).mkString(".")} has duplicate validated source-header evidence"
      ))
      case None => Right(bindings.toMap)

  private def sanitizeForEffectHeaders(source: String): String =
    val chars = source.toCharArray
    val out = source.toCharArray
    val Normal = 0
    val LineComment = 1
    val BlockComment = 2
    val StringLiteral = 3
    val TripleString = 4
    val CharacterLiteral = 5
    var state = Normal
    var blockDepth = 0
    var index = 0

    def blank(at: Int): Unit =
      if chars(at) != '\n' && chars(at) != '\r' then out(at) = ' '

    def blankRange(start: Int, endExclusive: Int): Unit =
      var at = start
      while at < endExclusive do
        blank(at)
        at += 1

    while index < chars.length do
      state match
        case `Normal` =>
          if chars(index) == '/' && index + 1 < chars.length && chars(index + 1) == '/' then
            blankRange(index, index + 2)
            state = LineComment
            index += 2
          else if chars(index) == '/' && index + 1 < chars.length && chars(index + 1) == '*' then
            blankRange(index, index + 2)
            state = BlockComment
            blockDepth = 1
            index += 2
          else if chars(index) == '"' && index + 2 < chars.length &&
              chars(index + 1) == '"' && chars(index + 2) == '"' then
            blankRange(index, index + 3)
            state = TripleString
            index += 3
          else if chars(index) == '"' then
            blank(index)
            state = StringLiteral
            index += 1
          else if chars(index) == '\'' then
            blank(index)
            state = CharacterLiteral
            index += 1
          else index += 1
        case `LineComment` =>
          if chars(index) == '\n' || chars(index) == '\r' then
            state = Normal
            index += 1
          else
            blank(index)
            index += 1
        case `BlockComment` =>
          if chars(index) == '/' && index + 1 < chars.length && chars(index + 1) == '*' then
            blankRange(index, index + 2)
            blockDepth += 1
            index += 2
          else if chars(index) == '*' && index + 1 < chars.length && chars(index + 1) == '/' then
            blankRange(index, index + 2)
            blockDepth -= 1
            if blockDepth == 0 then state = Normal
            index += 2
          else
            blank(index)
            index += 1
        case `StringLiteral` | `CharacterLiteral` =>
          val delimiter = if state == StringLiteral then '"' else '\''
          if chars(index) == '\\' && index + 1 < chars.length then
            blankRange(index, index + 2)
            index += 2
          else if chars(index) == delimiter then
            blank(index)
            state = Normal
            index += 1
          else
            blank(index)
            index += 1
        case `TripleString` =>
          if chars(index) == '"' && index + 2 < chars.length &&
              chars(index + 1) == '"' && chars(index + 2) == '"' then
            blankRange(index, index + 3)
            state = Normal
            index += 3
          else
            blank(index)
            index += 1
        case _ => index += 1

    new String(out)

  private def importReferencePath(reference: Term.Ref): Option[String] = reference match
    case Term.Name(name) => Some(name)
    case Term.Select(qualifier: Term.Ref, name) =>
      importReferencePath(qualifier).map(prefix => s"$prefix.${name.value}")
    case _ => None

  private def stableImportedPrefixForStorage(
      rawPrefix: String,
      scope: ImportScope
  ): Option[String] =
    val absolute = rawPrefix.startsWith("_root_.")
    val normalized = normalizeRoot(rawPrefix)
    if absolute then Some(normalized)
    else
      val segments = normalized.split('.').toVector.filter(_.nonEmpty)
      segments.headOption match
        case None => None
        case Some(head) =>
          val wildcardMayBind = scope.wildcardImports.exists { wildcard =>
            !wildcard.excludedNames.contains(head)
          }
          val exactTargets = scope.exactBindings.getOrElse(head, Vector.empty).distinct
          if wildcardMayBind then None
          else exactTargets match
            case Vector() => Some(normalized)
            case Vector(Some(target)) =>
              Some((normalizeRoot(target).split('.').toVector ++ segments.drop(1)).mkString("."))
            case _ => None

  private def canonicalLocalImportPath(
      rawPath: String,
      context: ProjectionContext
  ): Option[String] =
    val normalized = normalizeRoot(rawPath)
    val selected = normalized.contains('.')
    val local =
      if selected then
        resolveSelectedLocal(normalized, context, context.localTypes)
          .orElse(resolveSelectedLocal(normalized, context, context.localEffects))
      else
        resolveLocal(normalized, context.prefix, context.rootNamespace, context.localTypes)
          .orElse(resolveLocal(normalized, context.prefix, context.rootNamespace, context.localEffects))
    local.map(_.stableId)
      .orElse(resolveLocalOwner(normalized, context).map(_.stableId))

  private def importedTarget(
      scope: ImportScope,
      importer: Importer,
      importedName: String,
      context: ProjectionContext
  ): Option[String] =
    for
      rawPrefix <- importReferencePath(importer.ref)
      anchoredPrefix <- stableImportedPrefixForStorage(rawPrefix, scope)
      target = s"$anchoredPrefix.$importedName"
    yield canonicalLocalImportPath(target, context).getOrElse(normalizeRoot(target))

  private def applyImporter(
      scope: ImportScope,
      importer: Importer,
      context: ProjectionContext
  ): ImportScope =
    val excludedFromWildcard = importer.importees.iterator.collect {
      case Importee.Unimport(name) => name.value
      case Importee.Rename(name, _) => name.value
    }.toSet

    importer.importees.foldLeft(scope) { (current, importee) =>
      importee match
        case Importee.Name(name) =>
          current.addExact(
            name.value,
            importedTarget(scope, importer, name.value, context.copy(imports = scope))
          )
        case Importee.Rename(name, rename) if rename.value != "_" =>
          current.addExact(
            rename.value,
            importedTarget(scope, importer, name.value, context.copy(imports = scope))
          )
        case Importee.Wildcard() =>
          current.addWildcard(excludedFromWildcard)
        case _: Importee.Unimport | _: Importee.Given | _: Importee.GivenAll => current
        case _ => current
    }

  private def applyImport(
      scope: ImportScope,
      imported: Import,
      context: ProjectionContext
  ): ImportScope =
    imported.importers.foldLeft(scope) { (current, importer) =>
      applyImporter(current, importer, context.copy(imports = current))
    }

  private def projectStats(
      stats: Iterable[Stat],
      context: ProjectionContext
  ): Either[DescriptorError, Vector[ApiSymbolDefinition]] =
    stats.toVector.foldLeft[
      Either[DescriptorError, (Vector[ApiSymbolDefinition], ProjectionContext)]
    ](Right(Vector.empty -> context)) { (acc, stat) =>
      acc.flatMap { case (definitions, activeContext) =>
        stat match
          case imported: Import =>
            Right(definitions -> activeContext.copy(
              imports = applyImport(activeContext.imports, imported, activeContext)
            ))
          case other =>
            projectStat(other, activeContext).map(projected => (definitions ++ projected) -> activeContext)
      }
    }.map(_._1)

  private def projectStat(
      stat: Stat,
      context: ProjectionContext
  ): Either[DescriptorError, Vector[ApiSymbolDefinition]] = stat match
    case definition: Defn.Def =>
      projectFunction(definition, context, ApiSymbolKind.Function, None).map(Vector(_))
    case definition: Defn.Val => projectValues(definition, context)
    case definition: Defn.Var =>
      val names = definition.pats.collect { case Pat.Var(name) => name.value }
      val path = names match
        case name :: Nil => symbolPath(context, name)
        case _ => "$.symbols"
      Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        path,
        "public mutable variables are not represented by descriptor v3"
      ))
    case definition: Defn.Class => projectClass(definition, context)
    case definition: Defn.Trait => projectTrait(definition, context).map(Vector(_))
    case definition: Defn.Enum => projectEnum(definition, context)
    case definition: Defn.Type => projectTypeAlias(definition, context).map(Vector(_))
    case definition: Defn.Object
        if isEffectObject(definition) ||
          context.effectHeaders.contains(context.prefix -> definition.name.value) =>
      projectEffect(definition, context)
    case definition: Defn.Object => projectObject(definition, context)
    case definition: Defn.Given =>
      Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        symbolPath(context, Option(definition.name.value).filter(_.nonEmpty).getOrElse("<anonymous-given>")),
        "public given declarations do not yet have a lossless v3 symbol kind"
      ))
    case _: Export =>
      Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        s"$$.symbols[${context.prefix.mkString(".")}]",
        "public template exports cannot be projected without receiver metadata"
      ))
    case _: Import | _: Term => Right(Vector.empty)
    case definition: Defn =>
      Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        "$.symbols",
        s"public declaration ${definition.productPrefix} is not representable without guessing"
      ))
    case declaration: Decl =>
      Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        "$.symbols",
        s"public declaration ${declaration.productPrefix} is not representable without guessing"
      ))
    case _ => Right(Vector.empty)

  private def projectFunction(
      definition: Defn.Def,
      context: ProjectionContext,
      kind: ApiSymbolKind,
      resumeMultiplicity: Option[ResumeMultiplicity]
  ): Either[DescriptorError, ApiSymbolDefinition] =
    val qualifiedName = context.qualified(definition.name.value)
    val path = s"$$.symbols[$qualifiedName]"
    val rawTypeParameters = definition.paramClauseGroups
      .flatMap(_.tparamClause.values)
      .toVector
    val sourceParameterLists = definition.paramClauseGroups.flatMap(_.paramClauses).toVector
    for
      typeParametersAndBinders <- projectTypeParameters(rawTypeParameters, Vector.empty, s"$path.typeParameters", context)
      (typeParameters, binders) = typeParametersAndBinders
      parameterLists <- projectParameterLists(
        sourceParameterLists,
        binders,
        path,
        context
      )
      callbacks <- callbackPolicies(parameterLists, sourceParameterLists, path, context)
      declaredResult <- definition.decltpe.toRight(error(
        "MISSING_PUBLIC_TYPE",
        s"$path.resultType",
        s"managed export $qualifiedName needs an explicit result type"
      ))
      resultAndEffects <- projectResultAndEffects(declaredResult, binders, s"$path.resultType", context)
      (resultType, effectRow) = resultAndEffects
    yield ApiSymbolDefinition(
      qualifiedName = qualifiedName,
      kind = kind,
      typeParameters = typeParameters,
      parameterLists = parameterLists,
      resultType = resultType,
      effectRow = effectRow,
      operationResumeMultiplicity = resumeMultiplicity,
      callbackPolicies = callbacks,
      requiredTargets = context.requiredTargets
    )

  private def projectValues(
      definition: Defn.Val,
      context: ProjectionContext
  ): Either[DescriptorError, Vector[ApiSymbolDefinition]] =
    val patterns = definition.pats
    val declaredType = definition.decltpe
    val names = patterns.collect { case Pat.Var(name) => name.value }.toVector
    if names.size != patterns.size then
      Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        "$.symbols",
        "public val destructuring is not a stable managed ABI declaration"
      ))
    else
      traverse(names) { name =>
        val qualifiedName = context.qualified(name)
        val path = s"$$.symbols[$qualifiedName].resultType"
        for
          sourceType <- declaredType.toRight(error(
            "MISSING_PUBLIC_TYPE",
            path,
            s"managed export $qualifiedName needs an explicit type"
          ))
          projected <- projectType(sourceType, Vector.empty, path, context)
        yield ApiSymbolDefinition(
          qualifiedName = qualifiedName,
          kind = ApiSymbolKind.Value,
          resultType = projected,
          requiredTargets = context.requiredTargets
        )
      }

  private def projectClass(
      definition: Defn.Class,
      context: ProjectionContext
  ): Either[DescriptorError, Vector[ApiSymbolDefinition]] =
    val qualifiedName = context.qualified(definition.name.value)
    val path = s"$$.symbols[$qualifiedName]"
    val rawTypeParameters = definition.tparamClause.values.toVector
    for
      _ <- rejectUnsupportedParents(definition.templ, path, "class")
      _ <- rejectConstructorAccessors(definition, path)
      _ <- rejectUnsupportedTemplateMembers(
        definition.templ.body.stats,
        path,
        "class",
        _ => false
      )
      typeParametersAndBinders <- projectTypeParameters(rawTypeParameters, Vector.empty, s"$path.typeParameters", context)
      (typeParameters, binders) = typeParametersAndBinders
      resultType = nominalResult(qualifiedName, typeParameters)
      typeDefinition = ApiSymbolDefinition(
          qualifiedName = qualifiedName,
          kind = ApiSymbolKind.Type,
          typeParameters = typeParameters,
          resultType = resultType,
          requiredTargets = context.requiredTargets
        )
      constructor <-
        if hasPublicConstructor(definition) then
          val sourceParameterLists = definition.ctor.paramClauses.toVector
          for
            parameterLists <- projectParameterLists(sourceParameterLists, binders, path, context)
            callbacks <- callbackPolicies(parameterLists, sourceParameterLists, path, context)
          yield Vector(ApiSymbolDefinition(
              qualifiedName = qualifiedName,
              kind = ApiSymbolKind.Constructor,
              typeParameters = typeParameters,
              parameterLists = parameterLists,
              resultType = resultType,
              callbackPolicies = callbacks,
              requiredTargets = context.requiredTargets
            ))
        else Right(Vector.empty)
    yield typeDefinition +: constructor

  private def projectTrait(
      definition: Defn.Trait,
      context: ProjectionContext
  ): Either[DescriptorError, ApiSymbolDefinition] =
    val qualifiedName = context.qualified(definition.name.value)
    val path = s"$$.symbols[$qualifiedName]"
    for
      _ <- rejectUnsupportedParents(definition.templ, path, "trait")
      _ <- Either.cond(
        definition.ctor.paramClauses.isEmpty,
        (),
        error(
          "UNSUPPORTED_PUBLIC_DECLARATION",
          path,
          "public trait constructor clauses are not represented by descriptor v3"
        )
      )
      _ <- rejectUnsupportedTemplateMembers(
        definition.templ.body.stats,
        path,
        "trait",
        _ => false
      )
      typeParametersAndBinders <- projectTypeParameters(
        definition.tparamClause.values.toVector,
        Vector.empty,
        s"$path.typeParameters",
        context
      )
      (typeParameters, _) = typeParametersAndBinders
    yield ApiSymbolDefinition(
      qualifiedName = qualifiedName,
      kind = ApiSymbolKind.Type,
      typeParameters = typeParameters,
      resultType = nominalResult(qualifiedName, typeParameters),
      requiredTargets = context.requiredTargets
    )

  private def projectEnum(
      definition: Defn.Enum,
      context: ProjectionContext
  ): Either[DescriptorError, Vector[ApiSymbolDefinition]] =
    val qualifiedName = context.qualified(definition.name.value)
    val path = s"$$.symbols[$qualifiedName]"
    for
      _ <- rejectUnsupportedParents(definition.templ, path, "enum")
      _ <- Either.cond(
        definition.tparamClause.values.isEmpty,
        (),
        error(
          "UNSUPPORTED_PUBLIC_DECLARATION",
          path,
          "generic enum case result specialization is not represented by descriptor v3"
        )
      )
      _ <- rejectUnsupportedTemplateMembers(
        definition.templ.body.stats,
        path,
        "enum",
        stat => stat.isInstanceOf[Defn.EnumCase] || stat.isInstanceOf[Defn.RepeatedEnumCase]
      )
      typeParametersAndBinders <- projectTypeParameters(
        definition.tparamClause.values.toVector,
        Vector.empty,
        s"$path.typeParameters",
        context
      )
      (typeParameters, binders) = typeParametersAndBinders
      resultType = nominalResult(qualifiedName, typeParameters)
      cases <- traverse(definition.templ.body.stats.toVector) {
        case enumCase: Defn.EnumCase if isPublic(enumCase) =>
          val caseName = s"$qualifiedName.${enumCase.name.value}"
          val casePath = s"$$.symbols[$caseName]"
          for
            _ <- Either.cond(
              enumCase.tparamClause.values.isEmpty && enumCase.inits.isEmpty,
              (),
              error(
                "UNSUPPORTED_PUBLIC_DECLARATION",
                casePath,
                "generic or explicitly specialized enum cases are not represented by descriptor v3"
              )
            )
            sourceParameterLists = enumCase.ctor.paramClauses.toVector
            parameterLists <- projectParameterLists(sourceParameterLists, binders, casePath, context)
            callbacks <- callbackPolicies(parameterLists, sourceParameterLists, casePath, context)
          yield Vector(ApiSymbolDefinition(
            qualifiedName = caseName,
            kind = if enumCase.ctor.paramClauses.isEmpty then ApiSymbolKind.Value else ApiSymbolKind.Constructor,
            typeParameters = typeParameters,
            parameterLists = parameterLists,
            resultType = resultType,
            callbackPolicies = callbacks,
            requiredTargets = context.requiredTargets
          ))
        case repeated: Defn.RepeatedEnumCase if isPublic(repeated) =>
          Right(repeated.cases.toVector.map { name =>
            ApiSymbolDefinition(
              qualifiedName = s"$qualifiedName.${name.value}",
              kind = ApiSymbolKind.Value,
              typeParameters = typeParameters,
              resultType = resultType,
              requiredTargets = context.requiredTargets
            )
          })
        case _ => Right(Vector.empty)
      }.map(_.flatten)
    yield ApiSymbolDefinition(
      qualifiedName = qualifiedName,
      kind = ApiSymbolKind.Type,
      typeParameters = typeParameters,
      resultType = resultType,
      requiredTargets = context.requiredTargets
    ) +: cases

  private def projectTypeAlias(
      definition: Defn.Type,
      context: ProjectionContext
  ): Either[DescriptorError, ApiSymbolDefinition] =
    val qualifiedName = context.qualified(definition.name.value)
    val path = s"$$.symbols[$qualifiedName]"
    for
      typeParametersAndBinders <- projectTypeParameters(
        definition.tparamClause.values.toVector,
        Vector.empty,
        s"$path.typeParameters",
        context
      )
      (typeParameters, binders) = typeParametersAndBinders
      resultType <-
        if definition.mods.exists(_.is[Mod.Opaque]) then
          Right(nominalResult(qualifiedName, typeParameters))
        else projectType(definition.body, binders, s"$path.resultType", context)
    yield ApiSymbolDefinition(
      qualifiedName = qualifiedName,
      kind = ApiSymbolKind.Type,
      typeParameters = typeParameters,
      resultType = resultType,
      requiredTargets = context.requiredTargets
    )

  private def projectObject(
      definition: Defn.Object,
      context: ProjectionContext
  ): Either[DescriptorError, Vector[ApiSymbolDefinition]] =
    val qualifiedName = context.qualified(definition.name.value)
    val nestedContext = context.nested(definition.name.value)
    val own = ApiSymbolDefinition(
      qualifiedName = qualifiedName,
      kind = ApiSymbolKind.Value,
      resultType = AbiType.Named(qualifiedName),
      requiredTargets = context.requiredTargets
    )
    // Imports carry no visibility modifiers, but spell their retention out:
    // projectStats consumes them as source-ordered scope updates and emits no
    // symbols. The updated nested scope remains local to this object.
    val members = definition.templ.body.stats.filter { stat =>
      stat.isInstanceOf[Import] || isPublic(stat)
    }
    rejectUnsupportedParents(definition.templ, s"$$.symbols[$qualifiedName]", "object")
      .flatMap(_ => projectStats(members, nestedContext))
      .map(own +: _)

  private def projectEffect(
      definition: Defn.Object,
      context: ProjectionContext
  ): Either[DescriptorError, Vector[ApiSymbolDefinition]] =
    val qualifiedName = context.qualified(definition.name.value)
    val path = s"$$.symbols[$qualifiedName]"
    context.effectHeaders.get(context.prefix -> definition.name.value) match
      case None =>
        Left(error(
          "UNSUPPORTED_PUBLIC_DECLARATION",
          path,
          s"effect ${definition.name.value} has no retained declaration-header evidence"
        ))
      case Some(header) if header.unsupportedShape =>
        Left(error(
          "UNSUPPORTED_PUBLIC_DECLARATION",
          path,
          s"effect ${definition.name.value} loses generic or parent information in the compatibility parser"
        ))
      case Some(header) =>
        val markerReusable = definition.templ.body.stats.exists(isMultiShotMarker)
        val markerUnsupportedShape = definition.templ.body.stats.exists(isUnsupportedEffectShapeMarker)
        val multiplicity = if header.reusable then ResumeMultiplicity.Reusable else ResumeMultiplicity.OneShot
        val effectDefinition = ApiSymbolDefinition(
          qualifiedName = qualifiedName,
          kind = ApiSymbolKind.Effect,
          resultType = AbiType.Named(qualifiedName),
          requiredTargets = context.requiredTargets
        )
        val operationContext = context.nested(definition.name.value)
        val operations = definition.templ.body.stats.collect {
          case operation: Defn.Def
              if isPublic(operation) && EffectAnalysis.isEffectOpDef(operation.body) => operation
        }.toVector
        for
          _ <- Either.cond(
            markerReusable == header.reusable,
            (),
            error(
              "UNSUPPORTED_PUBLIC_DECLARATION",
              path,
              s"effect ${definition.name.value} declaration/header multiplicity evidence disagrees"
            )
          )
          _ <- Either.cond(
            markerUnsupportedShape == header.unsupportedShape,
            (),
            error(
              "UNSUPPORTED_PUBLIC_DECLARATION",
              path,
              s"effect ${definition.name.value} declaration/header shape evidence disagrees"
            )
          )
          _ <- rejectUnsupportedParents(definition.templ, path, "effect")
          _ <- rejectUnsupportedTemplateMembers(
            definition.templ.body.stats,
            path,
            "effect",
            stat => isEffectSyntaxMarker(stat) || isMultiShotMarker(stat) || (stat match
              case operation: Defn.Def => EffectAnalysis.isEffectOpDef(operation.body)
              case _ => false)
          )
          projectedOperations <- traverse(operations) { operation =>
            projectFunction(operation, operationContext, ApiSymbolKind.Operation, Some(multiplicity)).flatMap { projected =>
              val ownerEffect = EffectRow(Vector(EffectRef(qualifiedName)))
              mergeEffectRows(projected.effectRow, ownerEffect, s"$$.symbols[${projected.qualifiedName}].effectRow")
                .map(row => projected.copy(effectRow = row))
            }
          }
        yield effectDefinition +: projectedOperations

  private def isEffectObject(definition: Defn.Object): Boolean =
    definition.templ.body.stats.exists(isEffectDeclarationMarker) ||
      hasEffectOperationMarker(definition)

  private def isStructurallyMarkedEffect(definition: Defn.Object): Boolean =
    isEffectObject(definition) || definition.templ.body.stats.exists(isMultiShotMarker)

  private def hasEffectOperationMarker(definition: Defn.Object): Boolean =
    definition.templ.body.stats.exists {
      case operation: Defn.Def => EffectAnalysis.isEffectOpDef(operation.body)
      case _ => false
    }

  private def isEffectDeclarationMarker(stat: Stat): Boolean =
    isPrivateTrueTypeMarker(stat, "__effectDecl__")

  private def isUnsupportedEffectShapeMarker(stat: Stat): Boolean =
    isPrivateTrueTypeMarker(stat, "__effectUnsupportedShape__")

  private def isEffectSyntaxMarker(stat: Stat): Boolean =
    isEffectDeclarationMarker(stat) || isUnsupportedEffectShapeMarker(stat)

  private def isPrivateTrueTypeMarker(stat: Stat, expectedName: String): Boolean = stat match
    case _ => isCanonicalEffectSentinel(stat, expectedName)

  private def isMultiShotMarker(stat: Stat): Boolean = stat match
    case value: Defn.Val =>
      value.pats.exists {
        case Pat.Var(name) => name.value == "__multiShot__"
        case _ => false
      } && (value.rhs match
        case Lit.Boolean(true) => true
        case _ => false)
    case _ => false

  private def projectTypeParameters(
      parameters: Vector[Type.Param],
      outerBinders: BinderStack,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, (Vector[AbiTypeParameter], BinderStack)] =
    val group = parameters.zipWithIndex.map { case (parameter, index) =>
      Binder(parameter.name.value, index, parameter.tparamClause.values.size)
    }
    val binders = group +: outerBinders
    traverse(parameters.zipWithIndex) { case (parameter, index) =>
      if parameter.bounds.context.nonEmpty || parameter.bounds.view.nonEmpty then
        Left(error(
          "UNSUPPORTED_PUBLIC_TYPE",
          s"$path[$index]",
          "context/view bounds need an explicit ABI lowering before managed export"
        ))
      else
        for
          lower <- traverseOption(parameter.bounds.lo)(projectType(_, binders, s"$path[$index].lowerBound", context))
          upper <- traverseOption(parameter.bounds.hi)(projectType(_, binders, s"$path[$index].upperBound", context))
        yield AbiTypeParameter(
          index = index,
          name = parameter.name.value,
          variance =
            if parameter.mods.exists(_.is[Mod.Covariant]) then Variance.Covariant
            else if parameter.mods.exists(_.is[Mod.Contravariant]) then Variance.Contravariant
            else Variance.Invariant,
          kindArity = parameter.tparamClause.values.size,
          lowerBound = lower,
          upperBound = upper
        )
    }.map(_ -> binders)

  private def projectParameterLists(
      clauses: Vector[Term.ParamClause],
      binders: BinderStack,
      symbolPath: String,
      context: ProjectionContext
  ): Either[DescriptorError, Vector[ApiParameterList]] =
    traverse(clauses.zipWithIndex) { case (clause, listIndex) =>
      traverse(clause.values.toVector.zipWithIndex) { case (parameter, parameterIndex) =>
        val path = s"$symbolPath.parameterLists[$listIndex].parameters[$parameterIndex]"
        for
          declared <- parameter.decltpe.toRight(error(
            "MISSING_PUBLIC_TYPE",
            s"$path.tpe",
            s"managed parameter ${parameter.name.value} needs an explicit type"
          ))
          modeAndType = parameterModeAndType(parameter, clause, declared)
          (mode, sourceType) = modeAndType
          projected <- projectType(sourceType, binders, s"$path.tpe", context)
        yield ApiParameter(
          name = parameter.name.value,
          tpe = projected,
          mode = mode,
          hasDefault = parameter.default.nonEmpty
        )
      }.map(ApiParameterList.apply)
    }

  private def parameterModeAndType(
      parameter: Term.Param,
      clause: Term.ParamClause,
      declaredType: Type
  ): (ParameterMode, Type) =
    declaredType match
      case byName: Type.ByName => ParameterMode.ByName -> byName.tpe
      case repeated: Type.Repeated => ParameterMode.Repeated -> repeated.tpe
      case ordinary =>
        val contextual = clause.mod.exists(_.is[Mod.Using]) || parameter.mods.exists(_.is[Mod.Using])
        val implicitParameter = clause.mod.exists(_.is[Mod.Implicit]) || parameter.mods.exists(_.is[Mod.Implicit])
        if contextual then ParameterMode.Contextual -> ordinary
        else if implicitParameter then ParameterMode.Implicit -> ordinary
        else ParameterMode.Value -> ordinary

  private def projectResultAndEffects(
      sourceType: Type,
      binders: BinderStack,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, (AbiType, EffectRow)] = sourceType match
    case Type.ApplyInfix(result, Type.Name("!"), effects) =>
      for
        projectedResult <- projectType(result, binders, path, context)
        projectedEffects <- projectEffectRow(effects, binders, s"$path.effects", context)
      yield projectedResult -> projectedEffects
    case ordinary => projectType(ordinary, binders, path, context).map(_ -> EffectRow.Pure)

  private def isByteArgument(arguments: List[Type]): Boolean = arguments match
    case List(Type.Name("Byte")) => true
    case _ => false

  private def hasLexicalTypeIdentity(
      name: String,
      binders: BinderStack,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, Boolean] =
    if resolveBinder(name, binders).nonEmpty then Right(true)
    else resolveTypeForAbi(name, path, context).map(_.nonEmpty)

  private def projectNamedApplication(
      name: String,
      arguments: Vector[Type],
      binders: BinderStack,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, AbiType] =
    for
      // Resolve the constructor spelling before descending into arguments so a
      // wildcard/conflicting import reports the declaration's constructor path,
      // rather than whichever argument happens to be visited first.
      _ <- projectSimpleName(name, Vector.empty, binders, path, context)
      projectedArguments <- traverse(arguments.zipWithIndex) { case (argument, index) =>
        projectType(argument, binders, s"$path.arguments[$index]", context)
      }
      projected <- projectSimpleName(name, projectedArguments, binders, path, context)
    yield projected

  private def projectType(
      sourceType: Type,
      binders: BinderStack,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, AbiType] = sourceType match
    case Type.Name(name) => projectSimpleName(name, Vector.empty, binders, path, context)
    case Type.Apply.After_4_6_0(Type.Name("Array"), arguments)
        if isByteArgument(arguments.values.toList) =>
      val sourceArguments = arguments.values.toVector
      for
        arrayShadowed <- hasLexicalTypeIdentity("Array", binders, path, context)
        byteShadowed <- hasLexicalTypeIdentity(
          "Byte",
          binders,
          s"$path.arguments[0]",
          context
        )
        projected <-
          if !arrayShadowed && !byteShadowed then
            Right(AbiType.Primitive(AbiPrimitive.Bytes))
          else projectNamedApplication("Array", sourceArguments, binders, path, context)
      yield projected
    case Type.Apply.After_4_6_0(Type.Name(name), arguments) =>
      projectNamedApplication(name, arguments.values.toVector, binders, path, context)
    case Type.Apply.After_4_6_0(selected: Type.Select, arguments) =>
      for
        stableName <- showTypePath(selected, path)
        resolved <- resolveTypeForAbi(stableName, path, context)
        projectedArguments <- traverse(arguments.values.toVector.zipWithIndex) { case (argument, index) =>
          projectType(argument, binders, s"$path.arguments[$index]", context)
        }
      yield AbiType.Named(resolved.getOrElse(normalizeRoot(stableName)), projectedArguments)
    case selected: Type.Select =>
      for
        stableName <- showTypePath(selected, path)
        resolved <- resolveTypeForAbi(stableName, path, context)
      yield AbiType.Named(resolved.getOrElse(normalizeRoot(stableName)))
    case function: Type.Function =>
      for
        parameters <- traverse(function.paramClause.values.toVector.zipWithIndex) { case (parameter, index) =>
          projectType(parameter, binders, s"$path.parameterLists[0][$index]", context)
        }
        resultAndEffects <- projectResultAndEffects(function.res, binders, s"$path.result", context)
        (result, effects) = resultAndEffects
      yield AbiType.Function(Vector(parameters), result, effects)
    case Type.Tuple(elements) =>
      traverse(elements.toVector.zipWithIndex) { case (element, index) =>
        projectType(element, binders, s"$path.elements[$index]", context)
      }.map(AbiType.Tuple.apply)
    case Type.ApplyInfix(left, Type.Name("|"), right) =>
      for
        lhs <- projectType(left, binders, s"$path.alternatives[0]", context)
        rhs <- projectType(right, binders, s"$path.alternatives[1]", context)
      yield AbiType.Union(flattenUnion(lhs) ++ flattenUnion(rhs))
    case Type.ApplyInfix(left, Type.Name("&"), right) =>
      for
        lhs <- projectType(left, binders, s"$path.parts[0]", context)
        rhs <- projectType(right, binders, s"$path.parts[1]", context)
      yield AbiType.Intersection(flattenIntersection(lhs) ++ flattenIntersection(rhs))
    case Type.Lambda.After_4_6_0(parameters, body) =>
      for
        typeParametersAndBinders <- projectTypeParameters(
          parameters.values.toVector,
          binders,
          s"$path.parameters",
          context
        )
        (projectedParameters, nestedBinders) = typeParametersAndBinders
        projectedBody <- projectType(body, nestedBinders, s"$path.body", context)
      yield AbiType.TypeLambda(projectedParameters, projectedBody)
    case _: Type.Wildcard =>
      Left(error("DYNAMIC_PUBLIC_TYPE", path, "wildcard types are not a managed public ABI"))
    case _: Type.ByName | _: Type.Repeated =>
      Left(error(
        "UNSUPPORTED_PUBLIC_TYPE",
        path,
        "by-name and repeated wrappers are only valid on declared parameters"
      ))
    case Type.ApplyInfix(_, Type.Name("!"), _) =>
      Left(error(
        "UNSUPPORTED_PUBLIC_TYPE",
        path,
        "an effect row is only valid on a callable or function result"
      ))
    case other =>
      Left(error(
        "UNSUPPORTED_PUBLIC_TYPE",
        path,
        s"type ${other.syntax} is outside the closed v3 ABI algebra"
      ))

  private def projectSimpleName(
      name: String,
      arguments: Vector[AbiType],
      binders: BinderStack,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, AbiType] =
    resolveBinder(name, binders) match
      case Some(_) if arguments.nonEmpty =>
        Left(error(
          "UNSUPPORTED_PUBLIC_TYPE",
          path,
          s"higher-kinded parameter application $name[...] has no lossless v3 node"
        ))
      case Some(reference) => Right(AbiType.TypeParameter(reference))
      case None =>
        resolveTypeForAbi(name, path, context).flatMap {
          case Some(stableId) => Right(AbiType.Named(stableId, arguments))
          case None =>
            if DynamicTypes.contains(name) then
                Left(error("DYNAMIC_PUBLIC_TYPE", path, s"dynamic type $name is not a managed public ABI"))
            else if UnsupportedNumericTypes.contains(name) then
                Left(error(
                  "UNSUPPORTED_NUMERIC_WIDTH",
                  path,
                  s"numeric type $name has no frozen descriptor v3 width"
                ))
            else if arguments.isEmpty then name match
                case "Unit" => Right(AbiType.Primitive(AbiPrimitive.Unit))
                case "Boolean" => Right(AbiType.Primitive(AbiPrimitive.Boolean))
                // ssc `Int` is 64-bit (`v2/specs/10-core-ir.md` §2: `Int = Long`; measured:
                // 2147483647 + 1 => 2147483648, no 32-bit wrap). Declaring I32 told every
                // foreign host to truncate above 2^31-1 at the ABI boundary. Both spellings
                // therefore declare I64 and are told apart by retained width evidence, which
                // is what keeps their overload identities distinct.
                case "Int" =>
                  Right(AbiType.Primitive(AbiPrimitive.I64, Some(NumericWidthEvidence.DeclaredInt)))
                case "Long" =>
                  Right(AbiType.Primitive(AbiPrimitive.I64, Some(NumericWidthEvidence.DeclaredLong)))
                case "BigInt" => Right(AbiType.Primitive(AbiPrimitive.BigInt))
                case "Double" => Right(AbiType.Primitive(AbiPrimitive.F64))
                case "String" => Right(AbiType.Primitive(AbiPrimitive.String))
                case "Bytes" => Right(AbiType.Primitive(AbiPrimitive.Bytes))
                case "Char" => Right(AbiType.Primitive(AbiPrimitive.Char))
                case "Nothing" | "Null" => Left(error(
                  "UNSUPPORTED_PUBLIC_TYPE",
                  path,
                  s"type $name has no descriptor v3 representation"
                ))
                case standard if StandardTypes.contains(standard) =>
                  Right(AbiType.Named(StandardTypes(standard)))
                case local => Left(error(
                  "AMBIGUOUS_NAMED_TYPE",
                  path,
                  s"bare type $local is neither bound, local, imported, nor a frozen standard type"
                ))
            else
              StandardTypes.get(name)
                .map(id => AbiType.Named(id, arguments))
                .toRight(error(
                  "AMBIGUOUS_NAMED_TYPE",
                  path,
                  s"bare type constructor $name is neither local, imported, nor a frozen standard type"
                ))
        }

  private def projectEffectRow(
      sourceType: Type,
      binders: BinderStack,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, EffectRow] = sourceType match
    case Type.Tuple(elements) =>
      traverse(elements.toVector.zipWithIndex) { case (element, index) =>
        projectEffectRow(element, binders, s"$path.members[$index]", context)
      }.flatMap { rows =>
        rows.foldLeft[Either[DescriptorError, EffectRow]](Right(EffectRow.Pure)) { (acc, row) =>
          acc.flatMap(mergeEffectRows(_, row, path))
        }
      }
    case Type.Name(name) =>
      resolveBinder(name, binders) match
        case Some(reference) => Right(EffectRow(openTail = Some(reference)))
        case None => resolveEffectForAbi(name, path, context).flatMap {
          case Some(id) =>
            validateResolvedEffectUse(id, hasTypeArguments = false, path, context)
              .map(stableId => EffectRow(Vector(EffectRef(stableId))))
          case None => Left(error(
            "INVALID_EFFECT_ROW",
            path,
            s"bare effect $name is neither a bound row tail nor a local declared effect"
          ))
        }
    case Type.Apply.After_4_6_0(Type.Name(name), arguments) =>
      resolveEffectForAbi(name, path, context).flatMap {
        case None => Left(error(
          "INVALID_EFFECT_ROW",
          path,
          s"parameterized effect $name is neither local nor imported"
        ))
        case Some(id) =>
          validateResolvedEffectUse(id, hasTypeArguments = arguments.values.nonEmpty, path, context)
            .flatMap { stableId =>
              traverse(arguments.values.toVector.zipWithIndex) { case (argument, index) =>
                projectType(argument, binders, s"$path.typeArguments[$index]", context)
              }.map(args => EffectRow(Vector(EffectRef(stableId, args))))
            }
      }
    case selected: Type.Select =>
      for
        id <- showTypePath(selected, path)
        resolved <- resolveEffectForAbi(id, path, context)
        stableId <- resolved match
          case Some(effectId) =>
            validateResolvedEffectUse(effectId, hasTypeArguments = false, path, context)
          case None => Left(error(
            "INVALID_EFFECT_ROW",
            path,
            s"selected effect $id has no stable identity"
          ))
      yield EffectRow(Vector(EffectRef(stableId)))
    case Type.Apply.After_4_6_0(selected: Type.Select, arguments) =>
      for
        id <- showTypePath(selected, path)
        resolved <- resolveEffectForAbi(id, path, context)
        stableId <- resolved match
          case Some(effectId) =>
            validateResolvedEffectUse(effectId, hasTypeArguments = arguments.values.nonEmpty, path, context)
          case None => Left(error(
            "INVALID_EFFECT_ROW",
            path,
            s"selected effect $id has no stable identity"
          ))
        args <- traverse(arguments.values.toVector.zipWithIndex) { case (argument, index) =>
          projectType(argument, binders, s"$path.typeArguments[$index]", context)
        }
      yield EffectRow(Vector(EffectRef(stableId, args)))
    case other => Left(error(
      "INVALID_EFFECT_ROW",
      path,
      s"effect row member ${other.syntax} is unsupported or ambiguous"
    ))

  private def mergeEffectRows(
      left: EffectRow,
      right: EffectRow,
      path: String
  ): Either[DescriptorError, EffectRow] =
    (left.openTail, right.openTail) match
      case (Some(first), Some(second)) if first != second =>
        Left(error("INVALID_EFFECT_ROW", path, "an effect row cannot contain two distinct open tails"))
      case _ => Right(EffectRow(
        members = left.members ++ right.members,
        openTail = left.openTail.orElse(right.openTail)
      ))

  private def callbackPolicies(
      parameterLists: Vector[ApiParameterList],
      sourceClauses: Vector[Term.ParamClause],
      symbolPath: String,
      context: ProjectionContext
  ): Either[DescriptorError, Vector[CallbackPolicy]] =
    traverse(sourceClauses.zipWithIndex) { case (clause, listIndex) =>
      traverse(clause.values.toVector.zipWithIndex) { case (sourceParameter, parameterIndex) =>
        val path = s"$symbolPath.parameterLists[$listIndex].parameters[$parameterIndex].tpe"
        val projectedIsFunction = parameterLists.lift(listIndex)
          .flatMap(_.parameters.lift(parameterIndex))
          .exists(_.tpe.isInstanceOf[AbiType.Function])
        sourceParameter.decltpe match
          case None => Right(Vector.empty)
          case Some(_) if projectedIsFunction =>
            Right(Vector(conservativeCallbackPolicy(listIndex, parameterIndex)))
          case Some(sourceType) =>
            isCallbackType(sourceType, Map.empty, Set.empty, path, context).map { isCallback =>
              if !isCallback then Vector.empty
              else Vector(conservativeCallbackPolicy(listIndex, parameterIndex))
            }
      }.map(_.flatten)
    }.map(_.flatten)

  private def conservativeCallbackPolicy(
      listIndex: Int,
      parameterIndex: Int
  ): CallbackPolicy =
    CallbackPolicy(
      parameter = CallbackParameterPath(listIndex, parameterIndex),
      callingConvention = CallingConvention.ForeignBarrier,
      invocationMultiplicity = InvocationMultiplicity.Unknown,
      escape = CallbackEscape.MayEscape,
      reentrancy = CallbackReentrancy.Unknown,
      concurrency = CallbackConcurrency.Unknown,
      cancellation = CallbackCancellation.Unknown,
      threadAffinity = ThreadAffinity.AnyThread
    )

  private def isCallbackType(
      sourceType: Type,
      substitutions: Map[String, Type],
      seenAliases: Set[String],
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, Boolean] = sourceType match
    case byName: Type.ByName =>
      isCallbackType(byName.tpe, substitutions, seenAliases, path, context)
    case repeated: Type.Repeated =>
      isCallbackType(repeated.tpe, substitutions, seenAliases, path, context)
    case _: Type.Function => Right(true)
    case Type.Name(name) if substitutions.contains(name) =>
      isCallbackType(substitutions(name), substitutions - name, seenAliases, path, context)
    case Type.Name(name) =>
      resolveTypeForAbi(name, path, context).flatMap {
        case Some(stableId) => context.localAliases.get(stableId) match
          case Some(alias) => expandAliasCallback(alias, Vector.empty, substitutions, seenAliases, path, context)
          case None => Right(false)
        case None => Right(false)
      }
    case Type.Apply.After_4_6_0(Type.Name(name), arguments) =>
      resolveTypeForAbi(name, path, context).flatMap {
        case Some(stableId) => context.localAliases.get(stableId) match
          case Some(alias) =>
            expandAliasCallback(alias, arguments.values.toVector, substitutions, seenAliases, path, context)
          case None => Right(false)
        case None => Right(false)
      }
    case selected: Type.Select =>
      showTypePath(selected, path).flatMap { selectedName =>
        resolveTypeForAbi(selectedName, path, context).flatMap {
          case Some(stableId) => context.localAliases.get(stableId) match
            case Some(alias) => expandAliasCallback(alias, Vector.empty, substitutions, seenAliases, path, context)
            case None => Right(false)
          case None => Right(false)
        }
      }
    case Type.Apply.After_4_6_0(selected: Type.Select, arguments) =>
      showTypePath(selected, path).flatMap { selectedName =>
        resolveTypeForAbi(selectedName, path, context).flatMap {
          case Some(stableId) => context.localAliases.get(stableId) match
            case Some(alias) =>
              expandAliasCallback(alias, arguments.values.toVector, substitutions, seenAliases, path, context)
            case None => Right(false)
          case None => Right(false)
        }
      }
    case _ => Right(false)

  private def expandAliasCallback(
      alias: LocalAlias,
      arguments: Vector[Type],
      substitutions: Map[String, Type],
      seenAliases: Set[String],
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, Boolean] =
    val definition = alias.definition
    val stableId = (alias.owner :+ definition.name.value).mkString(".")
    if definition.mods.exists(_.is[Mod.Opaque]) then Right(false)
    else if seenAliases.contains(stableId) then
      Left(error(
        "UNSUPPORTED_PUBLIC_TYPE",
        path,
        s"cyclic transparent alias $stableId prevents conservative callback classification"
      ))
    else
      val parameters = definition.tparamClause.values.toVector
      if parameters.size != arguments.size then
        Left(error(
          "UNSUPPORTED_PUBLIC_TYPE",
          path,
          s"transparent alias $stableId expects ${parameters.size} type arguments, got ${arguments.size}"
        ))
      else
        val aliasSubstitutions = parameters.map(_.name.value).zip(arguments).toMap
        isCallbackType(
          definition.body,
          substitutions ++ aliasSubstitutions,
          seenAliases + stableId,
          path,
          context.copy(prefix = alias.owner, imports = alias.imports)
        )

  private def nominalResult(
      qualifiedName: String,
      parameters: Vector[AbiTypeParameter]
  ): AbiType =
    AbiType.Named(
      qualifiedName,
      parameters.map { parameter =>
        AbiType.TypeParameter(TypeParameterRef(0, parameter.index, parameter.kindArity))
      }
    )

  private def hasPublicConstructor(definition: Defn.Class): Boolean =
    !definition.mods.exists(_.is[Mod.Abstract]) &&
      !definition.ctor.mods.exists(mod => mod.is[Mod.Private] || mod.is[Mod.Protected])

  private def rejectConstructorAccessors(
      definition: Defn.Class,
      path: String
  ): Either[DescriptorError, Unit] =
    val caseClass = definition.mods.exists(_.is[Mod.Case])
    val accessor = definition.ctor.paramClauses.iterator
      .flatMap(_.values)
      .find { parameter =>
        caseClass || parameter.mods.exists(mod => mod.is[Mod.ValParam] || mod.is[Mod.VarParam])
      }
    accessor match
      case Some(parameter) => Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        path,
        s"constructor parameter ${parameter.name.value} exposes an instance accessor that descriptor v3 cannot represent"
      ))
      case None => Right(())

  private def resolveBinder(name: String, binders: BinderStack): Option[TypeParameterRef] =
    binders.zipWithIndex.iterator.flatMap { case (group, depth) =>
      group.find(_.name == name).map { binder =>
        TypeParameterRef(depth, binder.index, binder.kindArity)
      }
    }.toSeq.headOption

  private def identityLabel(domain: IdentityDomain): String = domain match
    case IdentityDomain.TypeIdentity => "type"
    case IdentityDomain.EffectIdentity => "effect"

  private def identityAmbiguityCode(domain: IdentityDomain): String = domain match
    case IdentityDomain.TypeIdentity => "AMBIGUOUS_NAMED_TYPE"
    case IdentityDomain.EffectIdentity => "INVALID_EFFECT_ROW"

  private def identityVisibilityCode(domain: IdentityDomain): String = domain match
    case IdentityDomain.TypeIdentity => "UNSUPPORTED_PUBLIC_TYPE"
    case IdentityDomain.EffectIdentity => "INVALID_EFFECT_ROW"

  private def identityValues(
      domain: IdentityDomain,
      context: ProjectionContext
  ): Map[(Vector[String], String), LocalIdentity] = domain match
    case IdentityDomain.TypeIdentity => context.localTypes
    case IdentityDomain.EffectIdentity => context.localEffects

  private def resolveLocalIdentity(
      rawName: String,
      domain: IdentityDomain,
      context: ProjectionContext
  ): Option[LocalIdentity] =
    val normalized = normalizeRoot(rawName)
    val values = identityValues(domain, context)
    if normalized.contains('.') || rawName.startsWith("_root_.") then
      resolveSelectedLocal(rawName, context, values)
    else resolveLocal(normalized, context.prefix, context.rootNamespace, values)

  private def validateLocalIdentityForAbi(
      identity: LocalIdentity,
      path: String,
      domain: IdentityDomain
  ): Either[DescriptorError, String] =
    if !identity.isPublic then
      Left(error(
        identityVisibilityCode(domain),
        path,
        s"known local ${identityLabel(domain)} ${identity.stableId} is not public in this managed ABI"
      ))
    else if !identity.ownerRepresentable then
      Left(error(
        identityVisibilityCode(domain),
        path,
        s"known local ${identityLabel(domain)} ${identity.stableId} has no representable owner surface in descriptor v3"
      ))
    else Right(identity.stableId)

  private def importedLeadingTargetForAbi(
      rawName: String,
      path: String,
      domain: IdentityDomain,
      context: ProjectionContext
  ): Either[DescriptorError, Option[String]] =
    if rawName.startsWith("_root_.") then Right(None)
    else
      val segments = normalizeRoot(rawName).split('.').toVector.filter(_.nonEmpty)
      segments.headOption match
        case None => Right(None)
        case Some(head) =>
          val wildcardMayBind = context.imports.wildcardImports.exists { wildcard =>
            !wildcard.excludedNames.contains(head)
          }
          val exactTargets = context.imports.exactBindings
            .getOrElse(head, Vector.empty)
            .distinct
          if wildcardMayBind then
            Left(error(
              identityAmbiguityCode(domain),
              path,
              s"${identityLabel(domain)} prefix $head may be supplied by an active wildcard import"
            ))
          else exactTargets match
            case Vector() => Right(None)
            case Vector(Some(target)) =>
              Right(Some((normalizeRoot(target).split('.').toVector ++ segments.drop(1)).mkString(".")))
            case Vector(None) =>
              Left(error(
                identityAmbiguityCode(domain),
                path,
                s"${identityLabel(domain)} prefix $head has an import whose qualifier is not a stable portable path"
              ))
            case _ =>
              Left(error(
                identityAmbiguityCode(domain),
                path,
                s"${identityLabel(domain)} prefix $head has conflicting explicit import bindings"
              ))

  private def knownOwnerForMember(
      rawName: String,
      context: ProjectionContext
  ): Option[LocalIdentity] =
    val normalized = normalizeRoot(rawName)
    val segments = normalized.split('.').toVector.filter(_.nonEmpty)
    if segments.size < 2 then None
    else
      val ownerName = segments.dropRight(1).mkString(".")
      resolveLocalOwner(
        if rawName.startsWith("_root_.") then s"_root_.$ownerName" else ownerName,
        context
      )

  private def rejectKnownOwnerFallback(
      rawName: String,
      owner: LocalIdentity,
      path: String,
      domain: IdentityDomain
  ): Either[DescriptorError, Option[String]] =
    validateLocalIdentityForAbi(owner, path, domain).flatMap { _ =>
      Left(error(
        identityAmbiguityCode(domain),
        path,
        s"${identityLabel(domain)} $rawName names unknown member of known local owner ${owner.stableId}"
      ))
    }

  private def resolveLexicalIdentityForAbi(
      rawName: String,
      path: String,
      domain: IdentityDomain,
      context: ProjectionContext
  ): Either[DescriptorError, Option[String]] =
    resolveLocalIdentity(rawName, domain, context) match
      case Some(identity) =>
        rejectPlatformType(identity.stableId, path)
          .flatMap(_ => validateLocalIdentityForAbi(identity, path, domain))
          .map(Some(_))
      case None =>
        knownOwnerForMember(rawName, context) match
          case Some(owner) => rejectKnownOwnerFallback(rawName, owner, path, domain)
          case None =>
            importedLeadingTargetForAbi(rawName, path, domain, context).flatMap {
              case Some(expanded) =>
                for
                  _ <- rejectPlatformType(expanded, path)
                  resolved <- resolveLocalIdentity(expanded, domain, context) match
                    case Some(identity) =>
                      validateLocalIdentityForAbi(identity, path, domain).map(Some(_))
                    case None => knownOwnerForMember(expanded, context) match
                      case Some(owner) => rejectKnownOwnerFallback(expanded, owner, path, domain)
                      case None => Right(Some(normalizeRoot(expanded)))
                yield resolved
              case None if normalizeRoot(rawName).contains('.') =>
                rejectPlatformType(rawName, path).map(_ => Some(normalizeRoot(rawName)))
              case None => Right(None)
            }

  private def resolveTypeForAbi(
      name: String,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, Option[String]] =
    resolveLexicalIdentityForAbi(name, path, IdentityDomain.TypeIdentity, context)

  private def resolveEffectForAbi(
      name: String,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, Option[String]] =
    resolveLexicalIdentityForAbi(name, path, IdentityDomain.EffectIdentity, context)

  private def validateResolvedEffectUse(
      stableId: String,
      hasTypeArguments: Boolean,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, String] =
    val isLocal = context.localEffects.valuesIterator.exists(_.stableId == stableId)
    if isLocal then validateLocalEffectUse(stableId, hasTypeArguments, path, context)
    else Right(stableId)

  private def validateLocalEffectUse(
      stableId: String,
      hasTypeArguments: Boolean,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, String] =
    val header = context.effectHeaders.iterator.collectFirst {
      case ((owner, name), value) if (owner :+ name).mkString(".") == stableId => value
    }
    header match
      case None => Left(error(
        "INVALID_EFFECT_ROW",
        path,
        s"local effect $stableId has no retained declaration-header evidence"
      ))
      case Some(value) if value.unsupportedShape => Left(error(
        "INVALID_EFFECT_ROW",
        path,
        s"local effect $stableId has unsupported generic or parent declaration shape"
      ))
      case Some(_) if hasTypeArguments => Left(error(
        "INVALID_EFFECT_ROW",
        path,
        s"non-generic local effect $stableId does not accept type arguments"
      ))
      case Some(_) => Right(stableId)

  private def resolveLocalOwner(
      rawName: String,
      context: ProjectionContext
  ): Option[LocalIdentity] =
    val absolute = rawName.startsWith("_root_.")
    val normalized = normalizeRoot(rawName)
    context.localOwners.get(normalized).orElse {
      if absolute then None
      else
        val segments = normalized.split('.').toVector.filter(_.nonEmpty)
        (context.prefix.length to context.rootNamespace.length by -1).iterator
          .map(size => (context.prefix.take(size) ++ segments).mkString("."))
          .flatMap(context.localOwners.get)
          .toSeq
          .headOption
    }

  private def resolveSelectedLocal(
      rawName: String,
      context: ProjectionContext,
      values: Map[(Vector[String], String), LocalIdentity]
  ): Option[LocalIdentity] =
    val absolute = rawName.startsWith("_root_.")
    val name = normalizeRoot(rawName)
    if absolute then values.valuesIterator.find(_.stableId == name)
    else
      values.valuesIterator.find(_.stableId == name).orElse {
        val segments = name.split('.').toVector.filter(_.nonEmpty)
        if segments.size < 2 then None
        else
          val ownerSuffix = segments.dropRight(1)
          val leaf = segments.last
          (context.prefix.length to context.rootNamespace.length by -1).iterator
            .map(size => (context.prefix.take(size) ++ ownerSuffix) -> leaf)
            .flatMap(values.get)
            .toSeq
            .headOption
      }

  private def resolveLocal(
      name: String,
      prefix: Vector[String],
      root: Vector[String],
      values: Map[(Vector[String], String), LocalIdentity]
  ): Option[LocalIdentity] =
    (prefix.length to root.length by -1).iterator
      .map(size => prefix.take(size) -> name)
      .flatMap(values.get)
      .toSeq
      .headOption

  private def rejectPlatformType(name: String, path: String): Either[DescriptorError, Unit] =
    val root = normalizeRoot(name).takeWhile(_ != '.')
    if Set("scala", "java", "javax").contains(root) then
      Left(error(
        "PLATFORM_TYPE_FORBIDDEN",
        path,
        s"platform type $name is forbidden in a portable managed ABI"
      ))
    else Right(())

  private def normalizeRoot(name: String): String = name.stripPrefix("_root_.")

  private def showTypePath(
      tpe: Type.Select,
      path: String
  ): Either[DescriptorError, String] =
    def termPath(term: Term): Option[String] = term match
      case Term.Name(name) => Some(name)
      case Term.Select(qualifier, name) => termPath(qualifier).map(prefix => s"$prefix.${name.value}")
      case _ => None
    termPath(tpe.qual)
      .map(prefix => s"$prefix.${tpe.name.value}")
      .toRight(error(
        "UNSUPPORTED_PUBLIC_TYPE",
        path,
        s"type selection ${tpe.syntax} has no stable portable type id"
      ))

  private def rejectUnsupportedParents(
      template: Template,
      path: String,
      declarationKind: String
  ): Either[DescriptorError, Unit] =
    if template.earlyClause.nonEmpty then
      Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        path,
        s"public $declarationKind early initializers are not represented by descriptor v3"
      ))
    else if template.inits.nonEmpty then
      Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        path,
        s"public $declarationKind inheritance is not represented by descriptor v3"
      ))
    else if template.derives.nonEmpty then
      Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        path,
        s"public $declarationKind derives clauses are not represented by descriptor v3"
      ))
    else if template.body.selfOpt.nonEmpty then
      Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        path,
        s"public $declarationKind self types are not represented by descriptor v3"
      ))
    else Right(())

  private def rejectUnsupportedTemplateMembers(
      stats: List[Stat],
      path: String,
      declarationKind: String,
      allowed: Stat => Boolean
  ): Either[DescriptorError, Unit] =
    stats.find { stat =>
      !allowed(stat) && (stat match
        case _: Stat.WithMods => isPublic(stat)
        case _: Export => true
        case _ => false)
    } match
      case Some(member) => Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        path,
        s"public $declarationKind member ${member.productPrefix} cannot be projected without receiver metadata"
      ))
      case None => Right(())

  private def flattenUnion(value: AbiType): Vector[AbiType] = value match
    case AbiType.Union(values) => values
    case other => Vector(other)

  private def flattenIntersection(value: AbiType): Vector[AbiType] = value match
    case AbiType.Intersection(values) => values
    case other => Vector(other)

  private def symbolPath(context: ProjectionContext, name: String): String =
    s"$$.symbols[${context.qualified(name)}]"

  private def error(code: String, path: String, message: String): DescriptorError =
    DescriptorError(code, path, message)

  private def traverse[A, B](
      values: Vector[A]
  )(f: A => Either[DescriptorError, B]): Either[DescriptorError, Vector[B]] =
    values.foldLeft[Either[DescriptorError, Vector[B]]](Right(Vector.empty)) { (acc, value) =>
      for
        done <- acc
        next <- f(value)
      yield done :+ next
    }

  private def traverseOption[A, B](
      value: Option[A]
  )(f: A => Either[DescriptorError, B]): Either[DescriptorError, Option[B]] =
    value match
      case Some(item) => f(item).map(Some(_))
      case None => Right(None)
