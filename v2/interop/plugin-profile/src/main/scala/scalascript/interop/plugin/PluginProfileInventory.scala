package scalascript.interop.plugin

import scala.collection.mutable
import scalascript.interop.descriptor.*

object PluginProfileInventory:

  def validate(
      dependencyManifest: Vector[DependencyBinding],
      availableProfiles: Vector[PluginCapabilityProfile],
      target: TargetProfile,
      admittedCapabilities: Set[String]
  ): Either[PluginProfileError, Vector[PluginCapabilityProfile]] =
    for
      _ <- CanonicalProfile.nonEmpty("$.target.id", target.id)
      _ <- CanonicalProfile.nonEmpty("$.target.abi", target.abi)
      targetCapabilities <- CanonicalProfile.capabilities(target.features, "$.target.features")
      admitted <- CanonicalProfile.capabilities(
        admittedCapabilities.toVector,
        "$.admittedCapabilities")
      _ <- CanonicalProfile.container("$.availableProfiles", availableProfiles.size)
      _ <- uniqueProfiles(availableProfiles)
      checkedProfiles <- traverse(availableProfiles)(PluginCapabilityProfiles.validated)
      pluginBindings <- validateBindings(dependencyManifest, target.id)
      available = checkedProfiles.map { profile =>
        (profile.declaration.pluginId -> profile.implementation.target) -> profile
      }.toMap
      selected <- traverse(pluginBindings) { case (binding, index) =>
        selectProfile(
          binding,
          index,
          available,
          target,
          (targetCapabilities ++ admitted).toSet)
      }
      selectedById = selected.map(profile => profile.declaration.pluginId -> profile).toMap
      bindingIds = pluginBindings.map(_._1.logicalId).toSet
      _ <- validateRequiredBindings(selected, bindingIds)
      ordered <- topological(selected, selectedById)
      _ <- validateRequirements(ordered, selectedById)
    yield ordered

  private def validateBindings(
      dependencyManifest: Vector[DependencyBinding],
      targetId: String
  ): Either[PluginProfileError, Vector[(DependencyBinding, Int)]] =
    val plugins = dependencyManifest.zipWithIndex.collect {
      case (binding, index) if binding.kind == DependencyKind.Plugin => binding -> index
    }
    for
      _ <- CanonicalProfile.container("$.dependencyManifest", plugins.size)
      _ <- duplicateBinding(plugins.map(_._1))
      normalized <- traverse(plugins) { case (binding, index) =>
        val path = s"$$.dependencyManifest[$index]"
        for
          _ <- CanonicalProfile.nonEmpty(s"$path.logicalId", binding.logicalId)
          _ <- CanonicalProfile.aggregateId(
            s"$path.semanticAbiId",
            binding.semanticAbiId,
            "ssc:plugin-abi:v1:")
          _ <- binding.schemaId
            .map(CanonicalProfile.aggregateId(
              s"$path.schemaId",
              _,
              "ssc:plugin-schema:v1:"))
            .getOrElse(Right(()))
          _ <- CanonicalProfile.digest(
            s"$path.implementationDigest",
            binding.implementationDigest.value)
          _ <- binding.target match
            case Some(value) if value == targetId => Right(())
            case Some(value) => Left(PluginProfileError(
              "TARGET_MISMATCH",
              s"$path.target",
              s"expected target '$targetId', found '$value'"))
            case None => Left(PluginProfileError(
              "TARGET_MISMATCH",
              s"$path.target",
              "plugin bindings must name their target"))
          capabilities <- CanonicalProfile.capabilities(
            binding.requiredCapabilities,
            s"$path.requiredCapabilities")
        yield binding.copy(requiredCapabilities = capabilities) -> index
      }
    yield normalized

  private def selectProfile(
      binding: DependencyBinding,
      index: Int,
      available: Map[(String, String), PluginCapabilityProfile],
      target: TargetProfile,
      allowedCapabilities: Set[String]
  ): Either[PluginProfileError, PluginCapabilityProfile] =
    val path = s"$$.dependencyManifest[$index]"
    available.get(binding.logicalId -> target.id) match
      case None => Left(PluginProfileError(
        "MISSING_PLUGIN_PROFILE",
        path,
        s"no profile for plugin '${binding.logicalId}' on target '${target.id}'"))
      case Some(profile) =>
        for
          _ <-
            if profile.implementation.targetAbi == target.abi then Right(())
            else Left(PluginProfileError(
              "TARGET_ABI_MISMATCH",
              s"$path.targetAbi",
              s"expected '${target.abi}', found '${profile.implementation.targetAbi}'"))
          projected <- PluginCapabilityProfiles.dependencyBinding(profile)
          _ <- same(
            binding.semanticAbiId,
            projected.semanticAbiId,
            "SEMANTIC_ABI_MISMATCH",
            s"$path.semanticAbiId")
          _ <-
            if binding.schemaId == projected.schemaId then Right(())
            else Left(PluginProfileError(
              "SCHEMA_MISMATCH",
              s"$path.schemaId",
              "plugin schema id does not match the available profile"))
          _ <- same(
            binding.implementationDigest.value,
            projected.implementationDigest.value,
            "IMPLEMENTATION_DIGEST_MISMATCH",
            s"$path.implementationDigest")
          _ <-
            if binding.requiredCapabilities == projected.requiredCapabilities then Right(())
            else Left(PluginProfileError(
              "CAPABILITY_DENIED",
              s"$path.requiredCapabilities",
              "binding capabilities do not match the target implementation"))
          _ <- profile.implementation.requiredCapabilities
            .find(capability => !allowedCapabilities.contains(capability)) match
            case Some(capability) => Left(PluginProfileError(
              "CAPABILITY_DENIED",
              s"$path.requiredCapabilities",
              s"capability '$capability' was not admitted"))
            case None => Right(())
        yield profile

  private def validateRequirements(
      selected: Vector[PluginCapabilityProfile],
      selectedById: Map[String, PluginCapabilityProfile]
  ): Either[PluginProfileError, Unit] =
    traverse(selected) { profile =>
      traverse(profile.declaration.dependencies.zipWithIndex) { case (requirement, index) =>
        val path = s"$$.profiles.${profile.declaration.pluginId}.dependencies[$index]"
        selectedById.get(requirement.pluginId) match
          case None => Left(PluginProfileError(
            "MISSING_PLUGIN_PROFILE",
            path,
            s"dependency profile '${requirement.pluginId}' is unavailable"))
          case Some(dependency) =>
            for
              _ <- same(
                requirement.semanticAbiId,
                dependency.aggregateSemanticAbiId,
                "SEMANTIC_ABI_MISMATCH",
                s"$path.semanticAbiId")
              _ <-
                if requirement.schemaId == dependency.aggregateSchemaId then Right(())
                else Left(PluginProfileError(
                  "SCHEMA_MISMATCH",
                  s"$path.schemaId",
                  "dependency schema id does not match"))
              _ <- requirement.requiredCapabilities
                .find(capability =>
                  !dependency.declaration.providedCapabilities.contains(capability)) match
                case Some(capability) => Left(PluginProfileError(
                  "CAPABILITY_DENIED",
                  s"$path.requiredCapabilities",
                  s"dependency does not provide capability '$capability'"))
                case None => Right(())
            yield ()
      }.map(_ => ())
    }.map(_ => ())

  private def validateRequiredBindings(
      selected: Vector[PluginCapabilityProfile],
      bindingIds: Set[String]
  ): Either[PluginProfileError, Unit] =
    traverse(selected) { profile =>
      traverse(profile.declaration.dependencies.zipWithIndex) { case (requirement, index) =>
        val path = s"$$.profiles.${profile.declaration.pluginId}.dependencies[$index]"
        if bindingIds.contains(requirement.pluginId) then Right(())
        else Left(PluginProfileError(
          "HIDDEN_PLUGIN_DEPENDENCY",
          path,
          s"dependency '${requirement.pluginId}' has no exact manifest binding"))
      }.map(_ => ())
    }.map(_ => ())

  private def topological(
      selected: Vector[PluginCapabilityProfile],
      selectedById: Map[String, PluginCapabilityProfile]
  ): Either[PluginProfileError, Vector[PluginCapabilityProfile]] =
    val dependencyIds = selected.map { profile =>
      profile.declaration.pluginId -> profile.declaration.dependencies.map(_.pluginId).toSet
    }.toMap
    val indegree = mutable.Map.from(dependencyIds.map { case (id, dependencies) =>
      id -> dependencies.size
    })
    val dependents = mutable.Map.empty[String, mutable.Set[String]]
    dependencyIds.foreach { case (consumer, dependencies) =>
      dependencies.foreach { dependency =>
        dependents.getOrElseUpdate(dependency, mutable.Set.empty) += consumer
      }
    }
    val ready = mutable.TreeSet.empty[String](using CanonicalProfile.framedStringOrdering)
    ready ++= indegree.collect { case (id, 0) => id }
    val ordered = Vector.newBuilder[PluginCapabilityProfile]
    var completed = 0
    while ready.nonEmpty do
      val next = ready.head
      ready -= next
      ordered += selectedById(next)
      completed += 1
      dependents.getOrElse(next, mutable.Set.empty).toVector
        .sorted(using CanonicalProfile.framedStringOrdering).foreach { consumer =>
        val remaining = indegree(consumer) - 1
        indegree(consumer) = remaining
        if remaining == 0 then ready += consumer
      }
    if completed == selected.size then Right(ordered.result())
    else Left(PluginProfileError(
      "PLUGIN_DEPENDENCY_CYCLE",
      "$.dependencyManifest",
      "plugin dependency graph contains a cycle"))

  private def uniqueProfiles(
      values: Vector[PluginCapabilityProfile]
  ): Either[PluginProfileError, Unit] =
    values.groupBy(profile => profile.declaration.pluginId -> profile.implementation.target)
      .collectFirst { case ((pluginId, target), profiles) if profiles.size > 1 =>
        pluginId -> target
      } match
        case Some((pluginId, target)) => Left(PluginProfileError(
          "AMBIGUOUS_PLUGIN_IMPLEMENTATION",
          "$.availableProfiles",
          s"multiple profiles for plugin '$pluginId' and target '$target'"))
        case None => Right(())

  private def duplicateBinding(
      values: Vector[DependencyBinding]
  ): Either[PluginProfileError, Unit] =
    values.groupBy(binding => binding.logicalId -> binding.target)
      .collectFirst { case ((pluginId, target), bindings) if bindings.size > 1 =>
        pluginId -> target
      } match
        case Some((pluginId, target)) => Left(PluginProfileError(
          "AMBIGUOUS_PLUGIN_IMPLEMENTATION",
          "$.dependencyManifest",
          s"multiple bindings for plugin '$pluginId' and target '$target'"))
        case None => Right(())

  private def same(
      actual: String,
      expected: String,
      code: String,
      path: String
  ): Either[PluginProfileError, Unit] =
    if actual == expected then Right(())
    else Left(PluginProfileError(code, path, s"expected '$expected', found '$actual'"))

  private def traverse[A, B](
      values: Vector[A]
  )(f: A => Either[PluginProfileError, B]): Either[PluginProfileError, Vector[B]] =
    values.foldLeft[Either[PluginProfileError, Vector[B]]](Right(Vector.empty)) { (done, value) =>
      for
        accumulated <- done
        next <- f(value)
      yield accumulated :+ next
    }
