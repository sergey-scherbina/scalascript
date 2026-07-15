package scalascript.interop.descriptor

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object DescriptorHashes:
  private val ApiDomain = "ssc-api-v1\u0000"
  private val SymbolDomain = "ssc-symbol-v1\u0000"
  private val OverloadDomain = "ssc-overload-v1\u0000"
  private val ControlSummaryDomain = "ssc-control-summary-v1\u0000"
  private val DependencyDomain = "ssc-dependencies-v1\u0000"
  private val JvmEntrypointDomain = "ssc-jvm-entrypoint-v1\u0000"
  private val NamedEntrypointDomain = "ssc-target-entrypoint-v1\u0000"

  def stableSymbolId(
      moduleId: String,
      definition: ApiSymbolDefinition
  ): Either[DescriptorError, StableSymbolId] =
    DescriptorValidator.definitionRaw(definition).flatMap { _ =>
      val normalized = DescriptorNormalization.symbolDefinition(ApiWire.identityDefinition(definition))
      CanonicalJson.bytes(ApiWire.writeSymbolIdentity(moduleId, normalized)).map { bytes =>
        StableSymbolId("ssc:symbol:v1:" + domainHash(SymbolDomain, bytes))
      }
    }

  def overloadId(
      moduleId: String,
      definition: ApiSymbolDefinition
  ): Either[DescriptorError, Option[OverloadId]] =
    DescriptorValidator.definitionRaw(definition).flatMap { _ =>
      if !definition.kind.isCallable then Right(None)
      else
        val normalized = DescriptorNormalization.symbolDefinition(ApiWire.identityDefinition(definition))
        CanonicalJson.bytes(ApiWire.writeSymbolIdentity(moduleId, normalized)).map { bytes =>
          Some(OverloadId("ssc:overload:v1:" + domainHash(OverloadDomain, bytes)))
        }
    }

  def apiHash(descriptor: ApiDescriptor): Either[DescriptorError, ApiHash] =
    DescriptorValidator.apiRaw(descriptor).flatMap { _ =>
      val normalized = DescriptorNormalization.api(descriptor)
      CanonicalJson.bytes(ApiWire.writeApi(normalized, includeHash = false)).map { bytes =>
        ApiHash(domainHash(ApiDomain, bytes))
      }
    }

  def controlSummaryDigest(
    summary: ControlSummary
  ): Either[DescriptorError, ControlSummaryDigest] =
    DescriptorValidator.controlSummaryRaw(summary).flatMap { _ =>
      val normalized = DescriptorNormalization.control(summary)
      CanonicalJson.bytes(ControlWire.writeControl(normalized, includeDigest = false)).map { bytes =>
        ControlSummaryDigest(domainHash(ControlSummaryDomain, bytes))
      }
    }

  def dependencyProfileDigest(
      dependencies: Vector[DependencyBinding]
  ): Either[DescriptorError, DependencyProfileDigest] =
    DescriptorValidator.dependenciesRaw(dependencies).flatMap { _ =>
      val normalized = DescriptorNormalization.dependencies(dependencies)
      CanonicalJson.bytes(WireSupport.arr(normalized.map(ArtifactWire.writeDependency))).map { bytes =>
        DependencyProfileDigest(domainHash(DependencyDomain, bytes))
      }
    }

  def jvmEntrypointId(
      stableSymbolId: StableSymbolId,
      ownerInternalName: String,
      methodName: String,
      methodDescriptor: String,
      invocationKind: JvmInvocationKind,
      bridgeFlags: Vector[String],
      classLoaderProfile: String
  ): Either[DescriptorError, EntrypointId] =
    DescriptorValidator.bridgeFlagsRaw(bridgeFlags).flatMap { _ =>
      val identity = ArtifactWire.writeJvmIdentity(
        stableSymbolId,
        ownerInternalName,
        methodName,
        methodDescriptor,
        invocationKind,
        DescriptorNormalization.strings(bridgeFlags),
        classLoaderProfile
      )
      CanonicalJson.bytes(identity).map { bytes =>
        EntrypointId("ssc:jvm-entrypoint:v1:" + domainHash(JvmEntrypointDomain, bytes))
      }
    }

  def namedEntrypointId(
      stableSymbolId: StableSymbolId,
      externalName: String,
      targetAbi: String
  ): Either[DescriptorError, EntrypointId] =
    CanonicalJson.bytes(ArtifactWire.writeNamedIdentity(stableSymbolId, externalName, targetAbi)).map { bytes =>
      EntrypointId("ssc:target-entrypoint:v1:" + domainHash(NamedEntrypointDomain, bytes))
    }

  def program(bytes: Array[Byte]): ProgramDigest = ProgramDigest(rawSha256(bytes))
  def artifact(bytes: Array[Byte]): ArtifactDigest = ArtifactDigest(rawSha256(bytes))
  def implementation(bytes: Array[Byte]): ImplementationDigest = ImplementationDigest(rawSha256(bytes))

  private def domainHash(domain: String, bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(domain.getBytes(StandardCharsets.UTF_8))
    digest.update(bytes)
    hex(digest.digest())

  private def rawSha256(bytes: Array[Byte]): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def hex(bytes: Array[Byte]): String =
    val chars = new Array[Char](bytes.length * 2)
    val alphabet = "0123456789abcdef"
    var i = 0
    while i < bytes.length do
      val value = bytes(i) & 0xff
      chars(i * 2) = alphabet.charAt(value >>> 4)
      chars(i * 2 + 1) = alphabet.charAt(value & 0x0f)
      i += 1
    new String(chars)
