package scalascript.interop.descriptor

object DescriptorCodec:
  val MaxBytes: Int = 4_194_304
  val MaxDepth: Int = 256
  val MaxContainerItems: Int = 100_000

  def encodeApi(value: ApiDescriptor): Either[DescriptorError, Array[Byte]] =
    DescriptorValidator.api(value).flatMap(validated => CanonicalJson.bytes(ApiWire.writeApi(validated)))

  def decodeApi(bytes: Array[Byte]): Either[DescriptorError, ApiDescriptor] =
    decode(
      bytes,
      ApiWire.readApi,
      DescriptorValidator.apiVersion,
      DescriptorValidator.apiRaw,
      DescriptorNormalization.api,
      DescriptorValidator.api,
      value => ApiWire.writeApi(value)
    )

  def encodeControlSummary(value: ControlSummary): Either[DescriptorError, Array[Byte]] =
    DescriptorValidator.controlSummary(value).flatMap { validated =>
      CanonicalJson.bytes(ControlWire.writeControl(validated))
    }

  def decodeControlSummary(bytes: Array[Byte]): Either[DescriptorError, ControlSummary] =
    decode(
      bytes,
      ControlWire.readControl,
      DescriptorValidator.controlSummaryVersion,
      DescriptorValidator.controlSummaryRaw,
      DescriptorNormalization.control,
      DescriptorValidator.controlSummary,
      value => ControlWire.writeControl(value)
    )

  def encodeArtifactManifest(value: ArtifactManifest): Either[DescriptorError, Array[Byte]] =
    DescriptorValidator.artifact(value).flatMap { validated =>
      CanonicalJson.bytes(ArtifactWire.writeArtifact(validated))
    }

  def decodeArtifactManifest(bytes: Array[Byte]): Either[DescriptorError, ArtifactManifest] =
    decode(
      bytes,
      ArtifactWire.readArtifact,
      DescriptorValidator.artifactVersion,
      DescriptorValidator.artifactRaw,
      DescriptorNormalization.artifact,
      DescriptorValidator.artifact,
      ArtifactWire.writeArtifact
    )

  private def decode[A](
      bytes: Array[Byte],
      read: (ujson.Value, String) => Either[DescriptorError, A],
      version: A => Either[DescriptorError, Unit],
      rawValidate: A => Either[DescriptorError, Unit],
      normalize: A => A,
      validate: A => Either[DescriptorError, A],
      write: A => ujson.Value
  ): Either[DescriptorError, A] =
    for
      parsed <- Jcs.parseUtf8(bytes)
      _ <- Jcs.render(parsed)
      decoded <- read(parsed, "$" )
      _ <- version(decoded)
      _ <- rawValidate(decoded)
      normalized = normalize(decoded)
      canonical <- CanonicalJson.bytes(write(normalized))
      _ <- if java.util.Arrays.equals(bytes, canonical) then Right(())
      else Left(DescriptorError(
        "NON_CANONICAL_JSON",
        "$",
        "input bytes differ from normalized canonical descriptor JSON"
      ))
      validated <- validate(normalized)
    yield validated
