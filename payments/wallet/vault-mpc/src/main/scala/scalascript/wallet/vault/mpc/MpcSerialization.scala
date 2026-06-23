package scalascript.wallet.vault.mpc

import scalascript.crypto.{Curve, HashAlgo}

/** JSON marshalling helpers + base64 codec + curve / hash name mapping
 *  shared between `HttpRemoteSigningClient` and any provider-specific
 *  subclass.
 *
 *  We use `java.util.Base64` (RFC 4648, no line-wrap) and the curve /
 *  hash names follow the convention used by CAIP-2 / NIST / IETF where
 *  applicable. */
object MpcSerialization:

  // ─── Base64 ─────────────────────────────────────────────────────────

  private val encoder = java.util.Base64.getEncoder
  private val decoder = java.util.Base64.getDecoder

  def b64encode(bytes: Array[Byte]): String =
    encoder.encodeToString(bytes)

  def b64decode(s: String): Array[Byte] =
    decoder.decode(s)

  // ─── Hex ────────────────────────────────────────────────────────────

  /** Lower-case, unprefixed hex encoding. */
  def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  /** Decode hex, tolerating a leading `0x`. Requires an even length. */
  def unhex(s: String): Array[Byte] =
    val clean = if s.startsWith("0x") then s.drop(2) else s
    require(clean.length % 2 == 0, s"Invalid hex length: ${clean.length}")
    clean.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  // ─── Curve naming ───────────────────────────────────────────────────
  //
  // Wire names follow the most common provider conventions
  // (Fireblocks: "MPC_ECDSA_SECP256K1" / "MPC_EDDSA_ED25519"; others
  // use plain "secp256k1" / "ed25519"). We emit the lowercase
  // unprefixed form — the most portable across providers.

  def curveName(curve: Curve): String = curve match
    case Curve.Secp256k1 => "secp256k1"
    case Curve.Ed25519   => "ed25519"
    case Curve.P256      => "p256"
    case Curve.Sr25519   => "sr25519"
    case Curve.Bls12_381 => "bls12-381"

  def parseCurve(name: String): Option[Curve] =
    name.toLowerCase match
      case "secp256k1"            => Some(Curve.Secp256k1)
      case "ed25519"              => Some(Curve.Ed25519)
      case "p256" | "secp256r1"   => Some(Curve.P256)
      case "sr25519"              => Some(Curve.Sr25519)
      case "bls12-381" | "bls12381" => Some(Curve.Bls12_381)
      case _ => None

  // ─── Hash algo naming ───────────────────────────────────────────────

  def hashName(h: HashAlgo): String = h match
    case HashAlgo.None       => "none"
    case HashAlgo.Sha256     => "sha256"
    case HashAlgo.Sha512     => "sha512"
    case HashAlgo.Keccak256  => "keccak256"
    case HashAlgo.Ripemd160  => "ripemd160"
    case HashAlgo.HmacSha512 => "hmac-sha512"
    case HashAlgo.Blake2b224 => "blake2b-224"
    case HashAlgo.Blake2b256 => "blake2b-256"

  // ─── Sign request / response builders ───────────────────────────────

  /** Build the JSON body for `POST /v1/accounts/{id}/sign`. */
  def signRequest(
    curve:          Curve,
    payload:        Array[Byte],
    derivationPath: String,
    hashAlgo:       HashAlgo,
  ): ujson.Obj =
    ujson.Obj(
      "curve"          -> ujson.Str(curveName(curve)),
      "payload"        -> ujson.Str(b64encode(payload)),
      "derivationPath" -> ujson.Str(derivationPath),
      "hashAlgo"       -> ujson.Str(hashName(hashAlgo)),
    )

  /** Parse a `listAccounts` JSON response. Expects:
   *  `{"accounts": [{"id":..., "label":..., "publicKeys":{"secp256k1":"<b64>",...}}, ...]}`
   *  Unknown curves are silently dropped so the wallet can ignore
   *  ones it doesn't support without failing the call. */
  def parseAccountList(value: ujson.Value): Seq[McpAccount] =
    value.obj.get("accounts") match
      case Some(arr) =>
        arr.arr.map { a =>
          val obj  = a.obj
          val pks  = obj.get("publicKeys").map(_.obj).getOrElse(ujson.Obj().obj)
          val keys = pks.iterator.toSeq.flatMap { case (k, v) =>
            parseCurve(k).map(c => c -> b64decode(v.str))
          }.toMap
          McpAccount(
            id         = obj("id").str,
            label      = obj.get("label").map(_.str).getOrElse(obj("id").str),
            publicKeys = keys,
          )
        }.toSeq
      case None => Nil

  /** Parse a sync sign response: `{"status":"completed", "signature":"<b64>"}`. */
  def parseSignCompleted(value: ujson.Value): Array[Byte] =
    val obj    = value.obj
    val status = obj.get("status").map(_.str).getOrElse("completed")
    if status != "completed" then
      throw new IllegalStateException(s"Expected completed status, got: $status")
    obj.get("signature") match
      case Some(s) => b64decode(s.str)
      case None    => throw new IllegalStateException("Sign response missing 'signature' field")

  /** Pulls the operation id out of an async-acknowledged sign response:
   *  `{"operationId":"<opaque>"}`. */
  def parseOperationId(value: ujson.Value): String =
    value.obj.get("operationId") match
      case Some(s) => s.str
      case None    => throw new IllegalStateException("Async sign ack missing 'operationId'")

  /** Inspect a poll response. Returns:
   *   - `Right(Some(sig))` if the operation completed with a signature.
   *   - `Right(None)`      if it's still pending.
   *   - `Left(reason)`     if it failed permanently. */
  def pollStatus(value: ujson.Value): Either[String, Option[Array[Byte]]] =
    val obj    = value.obj
    val status = obj.get("status").map(_.str).getOrElse("pending")
    status match
      case "completed" =>
        obj.get("signature") match
          case Some(s) => Right(Some(b64decode(s.str)))
          case None    => Left("completed status missing signature field")
      case "failed" =>
        Left(obj.get("error").map(_.str).getOrElse("operation failed without reason"))
      case _ =>
        Right(None)
