package scalascript.blockchain.bitcoin

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.{ByteBuffer, ByteOrder}

/** PSBT (Partially Signed Bitcoin Transaction) builder — BIP-174.
 *
 *  Covers the essential workflow for P2WPKH transactions:
 *  1. `PsbtBuilder.addInput(txid, vout, witnessUtxo)` — add a SegWit input
 *  2. `PsbtBuilder.addOutput(value, scriptPubKey)` — add an output
 *  3. `PsbtBuilder.sign(inputIndex, privateKey)` — compute sighash and sign
 *  4. `PsbtBuilder.finalize()` — build final witness data
 *  5. `PsbtBuilder.serialize()` — produce the binary PSBT
 *
 *  The PSBT binary format:
 *  - Magic: `70736274 ff` (ASCII "psbt\xff")
 *  - Global map: `{0x00 → unsigned-tx-bytes}`
 *  - Per-input maps: one map per input
 *  - Per-output maps: one map per output
 *  - Each map: `<key-type><key-data>=<value-data>` key-value pairs, terminated by `0x00` */
object Psbt:

  // ── PSBT key types ──────────────────────────────────────────────────────────
  private val GlobalUnsignedTx   = 0x00.toByte
  private val InputWitnessUtxo   = 0x01.toByte
  private val InputPartialSig    = 0x02.toByte
  private val InputFinalScriptWitness = 0x08.toByte

  // ── Data types ─────────────────────────────────────────────────────────────

  /** A PSBT input: outpoint + witness UTXO (TxOut for the spent output). */
  case class PsbtInput(
    txid:         Array[Byte],   // 32 bytes, internal byte order (reversed from display)
    vout:         Int,
    witnessUtxo:  TxOut,
    partialSigs:  Map[Array[Byte], Array[Byte]] = Map.empty,  // pubkey → DER sig + sighash type
    finalWitness: Option[Seq[Array[Byte]]]      = None,
  )

  /** A PSBT output: value (satoshis) + scriptPubKey. */
  case class PsbtOutput(value: Long, scriptPubKey: Array[Byte])

  /** A TxOut (value + scriptPubKey) — used as the witness UTXO for SegWit inputs. */
  case class TxOut(value: Long, scriptPubKey: Array[Byte])

  // ── Builder ────────────────────────────────────────────────────────────────

  class PsbtBuilder:
    private val inputs   = scala.collection.mutable.ArrayBuffer.empty[PsbtInput]
    private val outputs  = scala.collection.mutable.ArrayBuffer.empty[PsbtOutput]
    private val version  = 2

    /** Add a SegWit input. `txid` is the 32-byte txid in internal byte order (reversed from display hex). */
    def addInput(txid: Array[Byte], vout: Int, witnessUtxo: TxOut): this.type =
      inputs += PsbtInput(txid, vout, witnessUtxo)
      this

    /** Add an output. */
    def addOutput(value: Long, scriptPubKey: Array[Byte]): this.type =
      outputs += PsbtOutput(value, scriptPubKey)
      this

    /** Sign the input at `inputIndex` with `privateKey` (secp256k1, 32 bytes).
     *  Computes the BIP-143 sighash for the P2WPKH input and appends a partial sig. */
    def sign(inputIndex: Int, privateKey: Array[Byte], sighashType: Int = BitcoinCrypto.SIGHASH_ALL): this.type =
      require(inputIndex >= 0 && inputIndex < inputs.length, s"Input index $inputIndex out of range")
      val input = inputs(inputIndex)

      val compressedPub = BitcoinCrypto.deriveCompressedPublicKey(privateKey)
      val pubKeyHash    = BitcoinCrypto.hash160(compressedPub)

      // Build the unsigned tx for hashing
      val allInputs  = inputs.toSeq
      val allOutputs = outputs.toSeq

      // hashPrevouts: double-SHA256 of all outpoints (for SIGHASH_ALL)
      val hashPrevouts = if (sighashType & BitcoinCrypto.SIGHASH_ANYONECANPAY) != 0 then
        Array.fill(32)(0.toByte)
      else
        val buf = new ByteArrayOutputStream()
        for inp <- allInputs do
          buf.write(inp.txid)
          buf.write(intLE(inp.vout))
        BitcoinCrypto.hash256(buf.toByteArray)

      // hashSequence: double-SHA256 of all sequences 0xFFFFFFFE (for SIGHASH_ALL)
      val hashSequence = if (sighashType & 0x1f) != BitcoinCrypto.SIGHASH_ALL ||
                            (sighashType & BitcoinCrypto.SIGHASH_ANYONECANPAY) != 0 then
        Array.fill(32)(0.toByte)
      else
        val buf = new ByteArrayOutputStream()
        for _ <- allInputs do buf.write(intLE(0xfffffffe))
        BitcoinCrypto.hash256(buf.toByteArray)

      // hashOutputs: double-SHA256 of all outputs (for SIGHASH_ALL)
      val hashOutputs = (sighashType & 0x1f) match
        case BitcoinCrypto.SIGHASH_ALL =>
          val buf = new ByteArrayOutputStream()
          for out <- allOutputs do
            buf.write(longLE(out.value))
            buf.write(varInt(out.scriptPubKey.length))
            buf.write(out.scriptPubKey)
          BitcoinCrypto.hash256(buf.toByteArray)
        case BitcoinCrypto.SIGHASH_SINGLE if inputIndex < allOutputs.length =>
          val out = allOutputs(inputIndex)
          val buf = new ByteArrayOutputStream()
          buf.write(longLE(out.value))
          buf.write(varInt(out.scriptPubKey.length))
          buf.write(out.scriptPubKey)
          BitcoinCrypto.hash256(buf.toByteArray)
        case _ =>
          Array.fill(32)(0.toByte)

      val sighash = BitcoinCrypto.bip143Sighash(
        txVersion    = intLE(version),
        hashPrevouts = hashPrevouts,
        hashSequence = hashSequence,
        outpointTxid = input.txid,
        outpointVout = intLE(input.vout),
        pubKeyHash   = pubKeyHash,
        value        = longLE(input.witnessUtxo.value),
        sequence     = intLE(0xfffffffe),
        hashOutputs  = hashOutputs,
        locktime     = intLE(0),
        sighashType  = intLE(sighashType),
      )

      val derSig   = BitcoinCrypto.sign(privateKey, sighash)
      val sigBytes = derSig :+ sighashType.toByte

      // Store partial sig keyed by compressed pubkey bytes (as boxed Array for map key)
      val oldInput = inputs(inputIndex)
      inputs(inputIndex) = oldInput.copy(
        partialSigs = oldInput.partialSigs + (compressedPub -> sigBytes)
      )
      this

    /** Finalize all signed inputs: move partial sigs into witness data. */
    def finalizeInputs(): this.type =
      for i <- inputs.indices do
        val inp = inputs(i)
        if inp.finalWitness.isEmpty && inp.partialSigs.nonEmpty then
          val (pubKey, sig) = inp.partialSigs.head
          inputs(i) = inp.copy(
            finalWitness = Some(Seq(sig, pubKey)),
            partialSigs  = Map.empty,
          )
      this

    /** Serialize the PSBT to binary per BIP-174. */
    def serialize(): Array[Byte] =
      val out = new ByteArrayOutputStream()

      // Magic
      out.write(Array[Byte](0x70, 0x73, 0x62, 0x74, 0xff.toByte))  // "psbt\xff"

      // Global map: key=<00>, value=<unsigned-tx>
      val unsignedTx = buildUnsignedTx()
      writeKeyValue(out, Array(GlobalUnsignedTx), unsignedTx)
      out.write(0x00)  // end of global map

      // Per-input maps
      for inp <- inputs do
        // Witness UTXO (key type 0x01)
        val witnessUtxoBytes = serializeTxOut(inp.witnessUtxo)
        writeKeyValue(out, Array(InputWitnessUtxo), witnessUtxoBytes)

        // Partial signatures (key type 0x02, key = 0x02 || pubkey)
        for (pubKey, sig) <- inp.partialSigs do
          writeKeyValue(out, Array[Byte](InputPartialSig) ++ pubKey, sig)

        // Final witness (key type 0x08)
        for items <- inp.finalWitness do
          val wBuf = new ByteArrayOutputStream()
          wBuf.write(varInt(items.length))
          for item <- items do
            wBuf.write(varInt(item.length))
            wBuf.write(item)
          writeKeyValue(out, Array(InputFinalScriptWitness), wBuf.toByteArray)

        out.write(0x00)  // end of input map

      // Per-output maps (all empty for P2WPKH outputs)
      for _ <- outputs do
        out.write(0x00)  // end of output map

      out.toByteArray

    private def buildUnsignedTx(): Array[Byte] =
      val buf = new ByteArrayOutputStream()
      buf.write(intLE(version))
      buf.write(varInt(inputs.length))
      for inp <- inputs do
        buf.write(inp.txid)
        buf.write(intLE(inp.vout))
        buf.write(Array[Byte](0x00))  // empty scriptSig
        buf.write(intLE(0xfffffffe))  // sequence
      buf.write(varInt(outputs.length))
      for out <- outputs do
        buf.write(longLE(out.value))
        buf.write(varInt(out.scriptPubKey.length))
        buf.write(out.scriptPubKey)
      buf.write(intLE(0))  // locktime
      buf.toByteArray

    private def serializeTxOut(txOut: TxOut): Array[Byte] =
      longLE(txOut.value) ++ varInt(txOut.scriptPubKey.length) ++ txOut.scriptPubKey

    private def writeKeyValue(out: ByteArrayOutputStream, key: Array[Byte], value: Array[Byte]): Unit =
      out.write(varInt(key.length))
      out.write(key)
      out.write(varInt(value.length))
      out.write(value)

  end PsbtBuilder

  // ── Deserialization ────────────────────────────────────────────────────────

  /** Parsed PSBT structure (for verification / round-trip tests). */
  case class ParsedPsbt(
    unsignedTx:  Array[Byte],
    inputMaps:   Seq[Map[Array[Byte], Array[Byte]]],
    outputMaps:  Seq[Map[Array[Byte], Array[Byte]]],
  )

  def deserialize(bytes: Array[Byte]): Either[String, ParsedPsbt] =
    try
      val in    = new ByteArrayInputStream(bytes)
      val magic = new Array[Byte](5)
      in.read(magic)
      val magicOk = java.util.Arrays.equals(magic, Array[Byte](0x70, 0x73, 0x62, 0x74, 0xff.toByte))
      if !magicOk then Left("Invalid PSBT magic")
      else
        val globalMap = readMap(in)
        globalMap.find { case (k, _) => k.length == 1 && k(0) == GlobalUnsignedTx } match
          case None => Left("Missing global unsigned tx")
          case Some((_, unsignedTx)) =>
            val (inputCount, outputCount) = countTxInOut(unsignedTx)
            val inputMaps  = (0 until inputCount).map(_ => readMap(in)).toSeq
            val outputMaps = (0 until outputCount).map(_ => readMap(in)).toSeq
            Right(ParsedPsbt(unsignedTx, inputMaps, outputMaps))
    catch
      case e: Exception => Left(s"PSBT parse error: ${e.getMessage}")

  private def readMap(in: ByteArrayInputStream): Map[Array[Byte], Array[Byte]] =
    val map = scala.collection.mutable.LinkedHashMap.empty[java.nio.ByteBuffer, Array[Byte]]
    var b   = in.read()
    while b != 0 && b != -1 do
      val keyLen = readVarInt(b, in)
      val key    = new Array[Byte](keyLen)
      in.read(key)
      val valLen = readVarIntByte(in)
      val value  = new Array[Byte](valLen)
      in.read(value)
      map(ByteBuffer.wrap(key)) = value
      b = in.read()
    map.map { case (k, v) => k.array() -> v }.toMap

  private def readVarIntByte(in: ByteArrayInputStream): Int =
    val b = in.read()
    readVarInt(b, in)

  private def readVarInt(firstByte: Int, in: ByteArrayInputStream): Int =
    firstByte match
      case 0xfd => readLE2(in)
      case 0xfe => readLE4(in).toInt
      case b    => b & 0xff

  private def readLE2(in: ByteArrayInputStream): Int =
    val b = new Array[Byte](2)
    in.read(b)
    (b(0) & 0xff) | ((b(1) & 0xff) << 8)

  private def readLE4(in: ByteArrayInputStream): Long =
    val b = new Array[Byte](4)
    in.read(b)
    (b(0) & 0xffL) | ((b(1) & 0xffL) << 8) | ((b(2) & 0xffL) << 16) | ((b(3) & 0xffL) << 24)

  private def countTxInOut(txBytes: Array[Byte]): (Int, Int) =
    val in = new ByteArrayInputStream(txBytes)
    in.skip(4)  // version
    val inputCount = readVarIntByte(in)
    for _ <- 0 until inputCount do
      in.skip(32 + 4)   // txid + vout
      val scriptLen = readVarIntByte(in)
      in.skip(scriptLen + 4)  // script + sequence
    val outputCount = readVarIntByte(in)
    (inputCount, outputCount)

  // ── Little-endian helpers ──────────────────────────────────────────────────

  def intLE(v: Int): Array[Byte] =
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
    bb.putInt(v)
    bb.array()

  def longLE(v: Long): Array[Byte] =
    val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    bb.putLong(v)
    bb.array()

  def varInt(n: Int): Array[Byte] =
    if n < 0xfd then Array(n.toByte)
    else if n <= 0xffff then Array[Byte](0xfd.toByte, (n & 0xff).toByte, ((n >> 8) & 0xff).toByte)
    else Array[Byte](0xfe.toByte, (n & 0xff).toByte, ((n >> 8) & 0xff).toByte, ((n >> 16) & 0xff).toByte, ((n >> 24) & 0xff).toByte)
