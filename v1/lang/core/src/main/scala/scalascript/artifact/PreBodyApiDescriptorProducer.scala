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
      rawSource: Option[String],
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

  private final case class EffectCandidate(
      owner: Vector[String],
      definition: Defn.Object
  )

  private final case class LocalAlias(
      definition: Defn.Type,
      owner: Vector[String]
  )

  private final case class LocalIdentity(
      stableId: String,
      isPublic: Boolean
  )

  private final case class ProjectionContext(
      moduleId: String,
      rootNamespace: Vector[String],
      prefix: Vector[String],
      localTypes: Map[(Vector[String], String), LocalIdentity],
      localEffects: Map[(Vector[String], String), LocalIdentity],
      localAliases: Map[String, LocalAlias],
      requiredTargets: Vector[String],
      effectHeaders: Map[(Vector[String], String), EffectHeader]
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
      localTypes = collectLocalTypes(allStats, rootNamespace)
      localEffects = collectLocalEffects(allStats, rootNamespace, effectHeaders)
      localAliases = collectLocalAliases(allStats, rootNamespace, effectHeaders)
      context = ProjectionContext(
        moduleId = moduleId,
        rootNamespace = rootNamespace,
        prefix = rootNamespace,
        localTypes = localTypes,
        localEffects = localEffects,
        localAliases = localAliases,
        requiredTargets = module.manifest.map(_.targets.toVector).getOrElse(Vector.empty),
        effectHeaders = effectHeaders
      )
      definitions <- projectStats(selectedStats, context)
      api <- DescriptorFactory.api(ControlAbiVersion, moduleId, definitions)
    yield api

  def canonicalJson(module: ast.Module): Either[DescriptorError, String] =
    descriptor(module)
      .flatMap(DescriptorCodec.encodeApi)
      .map(bytes => new String(bytes, StandardCharsets.UTF_8))

  private def topLevelStats(module: ast.Module): Either[DescriptorError, ParsedDeclarations] =
    val pkg = module.manifest.flatMap(_.pkg).getOrElse(Nil)

    def unwrapPackage(stats: List[Stat], remaining: List[String]): Option[List[Stat]] =
      remaining match
        case Nil => Some(stats)
        case head :: tail =>
          stats match
            case List(obj: Defn.Object) if obj.name.value == head =>
              unwrapPackage(obj.templ.body.stats, tail)
            case _ => None

    def statsFromTree(
        stats: List[Stat],
        path: String
    ): Either[DescriptorError, Vector[Stat]] =
      if pkg.isEmpty then Right(stats.toVector)
      else unwrapPackage(stats, pkg)
        .map(_.toVector)
        .toRight(error(
          "UNSUPPORTED_PUBLIC_DECLARATION",
          path,
          s"parsed code block does not retain the declared package wrapper ${pkg.mkString(".")}"
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
                        rawSource = if module.document.isEmpty && pkg.isEmpty then Some(block.source) else None,
                        lineShift = pkg.size
                      ))
                    }
                  case termBlock: Term.Block =>
                    statsFromTree(termBlock.stats, contentPath).map { stats =>
                      Vector(ParsedDeclarationBlock(
                        path = contentPath,
                        stats = stats,
                        rawSource = if module.document.isEmpty && pkg.isEmpty then Some(block.source) else None,
                        lineShift = pkg.size + 1
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
            block.copy(rawSource = Some(source))
          })
        case None => Right(sectionBlocks)
      verifiedBlocks <- traverse(pairedBlocks)(verifyDeclarationCorrespondence)
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
      block: ParsedDeclarationBlock
  ): Either[DescriptorError, ParsedDeclarationBlock] =
    block.rawSource match
      case None => Right(block)
      case Some(source) =>
        val reparsed = Parser.parseScalaSource(source).toRight(error(
          "UNSUPPORTED_PUBLIC_DECLARATION",
          block.path,
          "retained executable source cannot be reparsed for declaration correspondence"
        ))
        reparsed.flatMap { node =>
          val reparsedStats = node.tree match
            case parsed: Source => Right(parsed.stats.toVector)
            case parsed: Term.Block => Right(parsed.stats.toVector)
            case other => Left(error(
              "UNSUPPORTED_PUBLIC_DECLARATION",
              block.path,
              s"retained executable source reparsed as unsupported root ${other.productPrefix}"
            ))
          reparsedStats.flatMap { stats =>
            val normalizedRetained = normalizeDeclarationStats(stats)
            val normalizedStored = normalizeDeclarationStats(block.stats)
            val retainedWitness = declarationWitnesses(normalizedRetained)
            val storedWitness = declarationWitnesses(normalizedStored)
            Either.cond(
              retainedWitness == storedWitness,
              block.copy(stats = normalizedStored),
              error(
                "UNSUPPORTED_PUBLIC_DECLARATION",
                block.path,
                "retained executable source declaration headers do not match the stored section AST"
              )
            )
          }
        }

  private def normalizeDeclarationStats(stats: Vector[Stat]): Vector[Stat] =
    Parser.desugarTypeLambdaAliases(Source(stats.toList)) match
      case normalized: Source => normalized.stats.toVector
      case _ => stats

  private def declarationWitnesses(stats: Iterable[Stat]): Vector[DeclarationWitness] =
    stats.iterator.flatMap(declarationWitness).toVector

  private def declarationWitness(stat: Stat): Option[DeclarationWitness] = stat match
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
      encodeWitnessParts(template.inits.map(initWitness)),
      template.body.selfOpt.map(_.structure).getOrElse("")
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
      isPublic(stat) && (allowed.isEmpty || declarationNames(stat).exists(allowed.contains))
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

  private def collectLocalTypes(
      stats: Vector[Stat],
      prefix: Vector[String]
  ): Map[(Vector[String], String), LocalIdentity] =
    val builder = Map.newBuilder[(Vector[String], String), LocalIdentity]

    def add(definition: Stat.WithMods & Member, owner: Vector[String], name: String, ownerIsPublic: Boolean): Unit =
      val stableId = (owner :+ name).mkString(".")
      builder += (owner -> name) -> LocalIdentity(stableId, ownerIsPublic && isPublic(definition))

    def visit(items: Iterable[Stat], owner: Vector[String], ownerIsPublic: Boolean): Unit =
      items.foreach {
        case definition: Defn.Class =>
          add(definition, owner, definition.name.value, ownerIsPublic)
        case definition: Defn.Trait =>
          add(definition, owner, definition.name.value, ownerIsPublic)
        case definition: Defn.Enum =>
          add(definition, owner, definition.name.value, ownerIsPublic)
        case definition: Defn.Type =>
          add(definition, owner, definition.name.value, ownerIsPublic)
        case definition: Defn.Object =>
          val nestedOwner = owner :+ definition.name.value
          visit(
            definition.templ.body.stats,
            nestedOwner,
            ownerIsPublic && isPublic(definition)
          )
        case _ => ()
      }

    visit(stats, prefix, ownerIsPublic = true)
    builder.result()

  private def collectLocalEffects(
      stats: Vector[Stat],
      prefix: Vector[String],
      effectHeaders: Map[(Vector[String], String), EffectHeader]
  ): Map[(Vector[String], String), LocalIdentity] =
    val builder = Map.newBuilder[(Vector[String], String), LocalIdentity]

    def visit(items: Iterable[Stat], owner: Vector[String], ownerIsPublic: Boolean): Unit =
      items.foreach {
        case definition: Defn.Object =>
          val definitionIsPublic = ownerIsPublic && isPublic(definition)
          if isEffectObject(definition) || effectHeaders.contains(owner -> definition.name.value) then
            builder += (owner -> definition.name.value) ->
              LocalIdentity((owner :+ definition.name.value).mkString("."), definitionIsPublic)
          else
            visit(
              definition.templ.body.stats,
              owner :+ definition.name.value,
              definitionIsPublic
            )
        case _ => ()
      }

    visit(stats, prefix, ownerIsPublic = true)
    builder.result()

  private def collectLocalAliases(
      stats: Vector[Stat],
      prefix: Vector[String],
      effectHeaders: Map[(Vector[String], String), EffectHeader]
  ): Map[String, LocalAlias] =
    val builder = Map.newBuilder[String, LocalAlias]

    def visit(items: Iterable[Stat], owner: Vector[String]): Unit =
      items.foreach {
        case definition: Defn.Type if isPublic(definition) =>
          builder += (owner :+ definition.name.value).mkString(".") ->
            LocalAlias(definition, owner)
        case definition: Defn.Object
            if isPublic(definition) &&
              !isEffectObject(definition) &&
              !effectHeaders.contains(owner -> definition.name.value) =>
          visit(definition.templ.body.stats, owner :+ definition.name.value)
        case _ => ()
      }

    visit(stats, prefix)
    builder.result()

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
    def candidates(items: Iterable[Stat], owner: Vector[String]): Vector[EffectCandidate] =
      val found = Vector.newBuilder[EffectCandidate]
      items.foreach {
        case definition: Defn.Object =>
          found += EffectCandidate(owner, definition)
          found ++= candidates(definition.templ.body.stats, owner :+ definition.name.value)
        case _ => ()
      }
      found.result()

    traverse(blocks) { block =>
      val blockCandidates = candidates(block.stats, rootNamespace)
      val headers = block.rawSource.map(declaredEffectHeaders).getOrElse(Vector.empty)
      var usedCandidates = Set.empty[Int]
      traverse(headers) { header =>
        val expectedLine = header.sourceLine + block.lineShift
        val exactMatches = blockCandidates.zipWithIndex.filter { case (candidate, candidateIndex) =>
          val position = candidate.definition.pos
          !usedCandidates.contains(candidateIndex) &&
            candidate.definition.name.value == header.name &&
            (!header.expectsStructuralMarker || isStructurallyMarkedEffect(candidate.definition)) &&
            !position.isEmpty && position.startLine == expectedLine
        }
        val fallbackMatches = blockCandidates.zipWithIndex.filter { case (candidate, candidateIndex) =>
          !usedCandidates.contains(candidateIndex) &&
            candidate.definition.name.value == header.name &&
            (!header.expectsStructuralMarker || isStructurallyMarkedEffect(candidate.definition))
        }
        val selected = exactMatches match
          case Vector(single) => Right(single)
          case Vector() if header.expectsStructuralMarker => fallbackMatches.headOption.toRight(error(
            "UNSUPPORTED_PUBLIC_DECLARATION",
            block.path,
            s"effect declaration header ${header.name} at source line ${header.sourceLine + 1} has no structurally marked parsed declaration"
          ))
          case Vector() if fallbackMatches.size == 1 => Right(fallbackMatches.head)
          case Vector() => Left(error(
            "UNSUPPORTED_PUBLIC_DECLARATION",
            block.path,
            s"empty effect declaration header ${header.name} at source line ${header.sourceLine + 1} has ambiguous parsed declarations"
          ))
          case _ => Left(error(
            "UNSUPPORTED_PUBLIC_DECLARATION",
            block.path,
            s"effect declaration header ${header.name} at source line ${header.sourceLine + 1} has ambiguous parsed declarations"
          ))
        /*
         * The positional match is exact when preprocessing preserved line count.
         * Effect preprocessing itself inserts marker/closing lines; in that case
         * the source-declared operation marker plus per-block declaration order
         * selects the only lossless owner. Empty effects deliberately require a
         * unique remaining same-name candidate.
         */
        selected match
          case Right((candidate, candidateIndex)) =>
            usedCandidates += candidateIndex
            Right((candidate.owner -> candidate.definition.name.value) -> header)
          case Left(problem) => Left(problem)
      }
    }.map(_.flatten).flatMap { bindings =>
      bindings.groupBy(_._1).collectFirst { case (key, values) if values.size > 1 => key } match
        case Some((owner, name)) => Left(error(
          "UNSUPPORTED_PUBLIC_DECLARATION",
          s"$$.symbols[${(owner :+ name).mkString(".")}]",
          s"effect declaration ${(owner :+ name).mkString(".")} has duplicate source-header evidence"
        ))
        case None => Right(bindings.toMap)
    }

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

  private def projectStats(
      stats: Iterable[Stat],
      context: ProjectionContext
  ): Either[DescriptorError, Vector[ApiSymbolDefinition]] =
    traverse(stats.toVector)(projectStat(_, context)).map(_.flatten)

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
    val members = definition.templ.body.stats.filter(isPublic)
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
          _ <- rejectUnsupportedParents(definition.templ, path, "effect")
          _ <- rejectUnsupportedTemplateMembers(
            definition.templ.body.stats,
            path,
            "effect",
            stat => isMultiShotMarker(stat) || (stat match
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
    definition.templ.body.stats.exists {
      case operation: Defn.Def => EffectAnalysis.isEffectOpDef(operation.body)
      case _ => false
    }

  private def isStructurallyMarkedEffect(definition: Defn.Object): Boolean =
    isEffectObject(definition) || definition.templ.body.stats.exists(isMultiShotMarker)

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

  private def projectType(
      sourceType: Type,
      binders: BinderStack,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, AbiType] = sourceType match
    case Type.Name(name) => projectSimpleName(name, Vector.empty, binders, path, context)
    case Type.Apply.After_4_6_0(Type.Name("Array"), arguments)
        if isByteArgument(arguments.values.toList) =>
      resolveLocalTypeForAbi("Array", path, context).flatMap {
        case None => Right(AbiType.Primitive(AbiPrimitive.Bytes))
        case Some(stableId) =>
          traverse(arguments.values.toVector.zipWithIndex) { case (argument, index) =>
            projectType(argument, binders, s"$path.arguments[$index]", context)
          }.map(projectedArguments => AbiType.Named(stableId, projectedArguments))
      }
    case Type.Apply.After_4_6_0(Type.Name(name), arguments) =>
      for
        projectedArguments <- traverse(arguments.values.toVector.zipWithIndex) { case (argument, index) =>
          projectType(argument, binders, s"$path.arguments[$index]", context)
        }
        projected <- projectSimpleName(name, projectedArguments, binders, path, context)
      yield projected
    case Type.Apply.After_4_6_0(selected: Type.Select, arguments) =>
      for
        stableName <- showTypePath(selected, path)
        _ <- rejectPlatformType(stableName, path)
        resolved <- resolveSelectedLocalTypeForAbi(stableName, path, context)
        projectedArguments <- traverse(arguments.values.toVector.zipWithIndex) { case (argument, index) =>
          projectType(argument, binders, s"$path.arguments[$index]", context)
        }
      yield AbiType.Named(resolved.getOrElse(normalizeRoot(stableName)), projectedArguments)
    case selected: Type.Select =>
      for
        stableName <- showTypePath(selected, path)
        _ <- rejectPlatformType(stableName, path)
        resolved <- resolveSelectedLocalTypeForAbi(stableName, path, context)
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
        resolveLocalTypeForAbi(name, path, context).flatMap {
          case Some(stableId) => Right(AbiType.Named(stableId, arguments))
          case None if DynamicTypes.contains(name) =>
            Left(error("DYNAMIC_PUBLIC_TYPE", path, s"dynamic type $name is not a managed public ABI"))
          case None if UnsupportedNumericTypes.contains(name) =>
            Left(error(
              "UNSUPPORTED_NUMERIC_WIDTH",
              path,
              s"numeric type $name has no frozen descriptor v3 width"
            ))
          case None if arguments.isEmpty => name match
            case "Unit" => Right(AbiType.Primitive(AbiPrimitive.Unit))
            case "Boolean" => Right(AbiType.Primitive(AbiPrimitive.Boolean))
            case "Int" => Right(AbiType.Primitive(AbiPrimitive.I32))
            case "Long" => Right(AbiType.Primitive(AbiPrimitive.I64))
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
              s"bare type $local is neither bound, local, nor a frozen standard type"
            ))
          case None =>
            StandardTypes.get(name)
              .map(id => AbiType.Named(id, arguments))
              .toRight(error(
                "AMBIGUOUS_NAMED_TYPE",
                path,
                s"bare type constructor $name is neither local nor a frozen standard type"
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
        case None => resolveLocalEffectForAbi(name, path, context).flatMap {
          case Some(id) =>
            validateLocalEffectUse(id, hasTypeArguments = false, path, context)
              .map(stableId => EffectRow(Vector(EffectRef(stableId))))
          case None => Left(error(
            "INVALID_EFFECT_ROW",
            path,
            s"bare effect $name is neither a bound row tail nor a local declared effect"
          ))
        }
    case Type.Apply.After_4_6_0(Type.Name(name), arguments) =>
      resolveLocalEffectForAbi(name, path, context).flatMap {
        case None => Left(error(
          "INVALID_EFFECT_ROW",
          path,
          s"parameterized effect $name is not a local declared effect"
        ))
        case Some(id) =>
          validateLocalEffectUse(id, hasTypeArguments = arguments.values.nonEmpty, path, context)
            .flatMap { stableId =>
              traverse(arguments.values.toVector.zipWithIndex) { case (argument, index) =>
                projectType(argument, binders, s"$path.typeArguments[$index]", context)
              }.map(args => EffectRow(Vector(EffectRef(stableId, args))))
            }
      }
    case selected: Type.Select =>
      for
        id <- showTypePath(selected, path)
        _ <- rejectPlatformType(id, path)
        resolved <- resolveSelectedLocalEffectForAbi(id, path, context)
        stableId <- resolved match
          case Some(localId) => validateLocalEffectUse(localId, hasTypeArguments = false, path, context)
          case None => Right(normalizeRoot(id))
      yield EffectRow(Vector(EffectRef(stableId)))
    case Type.Apply.After_4_6_0(selected: Type.Select, arguments) =>
      for
        id <- showTypePath(selected, path)
        _ <- rejectPlatformType(id, path)
        resolved <- resolveSelectedLocalEffectForAbi(id, path, context)
        stableId <- resolved match
          case Some(localId) =>
            validateLocalEffectUse(localId, hasTypeArguments = arguments.values.nonEmpty, path, context)
          case None => Right(normalizeRoot(id))
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
      resolveLocalTypeForAbi(name, path, context).flatMap {
        case Some(stableId) => context.localAliases.get(stableId) match
          case Some(alias) => expandAliasCallback(alias, Vector.empty, substitutions, seenAliases, path, context)
          case None => Right(false)
        case None => Right(false)
      }
    case Type.Apply.After_4_6_0(Type.Name(name), arguments) =>
      resolveLocalTypeForAbi(name, path, context).flatMap {
        case Some(stableId) => context.localAliases.get(stableId) match
          case Some(alias) =>
            expandAliasCallback(alias, arguments.values.toVector, substitutions, seenAliases, path, context)
          case None => Right(false)
        case None => Right(false)
      }
    case selected: Type.Select =>
      showTypePath(selected, path).flatMap { selectedName =>
        resolveSelectedLocalTypeForAbi(selectedName, path, context).flatMap {
          case Some(stableId) => context.localAliases.get(stableId) match
            case Some(alias) => expandAliasCallback(alias, Vector.empty, substitutions, seenAliases, path, context)
            case None => Right(false)
          case None => Right(false)
        }
      }
    case Type.Apply.After_4_6_0(selected: Type.Select, arguments) =>
      showTypePath(selected, path).flatMap { selectedName =>
        resolveSelectedLocalTypeForAbi(selectedName, path, context).flatMap {
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
          context.copy(prefix = alias.owner)
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

  private def resolveLocalTypeForAbi(
      name: String,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, Option[String]] =
    resolveLocalIdentityForAbi(
      resolveLocal(name, context.prefix, context.rootNamespace, context.localTypes),
      path,
      "type"
    )

  private def resolveSelectedLocalTypeForAbi(
      name: String,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, Option[String]] =
    resolveLocalIdentityForAbi(
      resolveSelectedLocal(name, context, context.localTypes),
      path,
      "type"
    )

  private def resolveLocalEffectForAbi(
      name: String,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, Option[String]] =
    resolveEffectIdentityForAbi(
      resolveLocal(name, context.prefix, context.rootNamespace, context.localEffects),
      path
    )

  private def resolveSelectedLocalEffectForAbi(
      name: String,
      path: String,
      context: ProjectionContext
  ): Either[DescriptorError, Option[String]] =
    resolveEffectIdentityForAbi(
      resolveSelectedLocal(name, context, context.localEffects),
      path
    )

  private def resolveLocalIdentityForAbi(
      identity: Option[LocalIdentity],
      path: String,
      kind: String
  ): Either[DescriptorError, Option[String]] = identity match
    case Some(local) if !local.isPublic => Left(error(
      "UNSUPPORTED_PUBLIC_TYPE",
      path,
      s"known local $kind ${local.stableId} is not public in this managed ABI"
    ))
    case Some(local) => Right(Some(local.stableId))
    case None => Right(None)

  private def resolveEffectIdentityForAbi(
      identity: Option[LocalIdentity],
      path: String
  ): Either[DescriptorError, Option[String]] = identity match
    case Some(local) if !local.isPublic => Left(error(
      "INVALID_EFFECT_ROW",
      path,
      s"known local effect ${local.stableId} is not public in this managed ABI"
    ))
    case Some(local) => Right(Some(local.stableId))
    case None => Right(None)

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
    if template.inits.nonEmpty then
      Left(error(
        "UNSUPPORTED_PUBLIC_DECLARATION",
        path,
        s"public $declarationKind inheritance is not represented by descriptor v3"
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
