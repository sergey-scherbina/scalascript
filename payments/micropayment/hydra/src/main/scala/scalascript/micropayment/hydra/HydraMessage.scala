package scalascript.micropayment.hydra

/** Hydra node WebSocket API message types (subset used by the payment channel).
 *
 *  Reference: https://hydra.family/head-protocol/api-reference/
 *  The Hydra node speaks JSON over WebSocket; these are the events we care about.
 *  Unknown message tags are ignored by the client. */
sealed trait HydraServerMsg

object HydraServerMsg:
  /** Head is initialised; waiting for both parties to commit. */
  case class HeadIsInitializing(headId: String, parties: Seq[String]) extends HydraServerMsg

  /** One party committed their UTxO to the head. */
  case class Committed(party: String) extends HydraServerMsg

  /** Both parties committed; head is open and ready for off-chain txs. */
  case class HeadIsOpen(headId: String) extends HydraServerMsg

  /** An off-chain transaction was validated and applied inside the head. */
  case class TxValid(headId: String, txId: String) extends HydraServerMsg

  /** An off-chain transaction was rejected by the head's local ledger. */
  case class TxInvalid(headId: String, txId: String, reason: String) extends HydraServerMsg

  /** A multi-party snapshot was confirmed — safe point to close from. */
  case class SnapshotConfirmed(headId: String, snapshotNumber: Long) extends HydraServerMsg

  /** A close was posted on-chain; contestation window is now active. */
  case class HeadIsClosed(headId: String, snapshotNumber: Long) extends HydraServerMsg

  /** Head finalised on-chain after contestation period. */
  case class HeadIsFinalized(headId: String) extends HydraServerMsg

  /** Parse a Hydra WebSocket JSON message. Returns None for unknown tags. */
  def parse(json: String): Option[HydraServerMsg] =
    val v = ujson.read(json)
    v("tag").str match
      case "HeadIsInitializing" =>
        val parties = v.obj.get("parties").map(_.arr.map(_("vkey").str).toSeq).getOrElse(Seq.empty)
        Some(HeadIsInitializing(v("headId").str, parties))
      case "Committed" =>
        Some(Committed(v.obj.get("party").flatMap(_.obj.get("vkey")).map(_.str).getOrElse("")))
      case "HeadIsOpen"    => Some(HeadIsOpen(v("headId").str))
      case "TxValid"       => Some(TxValid(v("headId").str, v("transaction")("id").str))
      case "TxInvalid"     => Some(TxInvalid(v("headId").str,
                                              v("transaction")("id").str,
                                              v.obj.get("validationError").flatMap(_.obj.get("reason")).map(_.str).getOrElse("")))
      case "SnapshotConfirmed" =>
        Some(SnapshotConfirmed(v("headId").str, v("snapshot")("snapshotNumber").num.toLong))
      case "HeadIsClosed"    => Some(HeadIsClosed(v("headId").str,
                                                    v.obj.get("snapshotNumber").map(_.num.toLong).getOrElse(0L)))
      case "HeadIsFinalized" => Some(HeadIsFinalized(v("headId").str))
      case _                 => None

sealed trait HydraClientMsg:
  def toJson: String

object HydraClientMsg:
  /** Submit an off-chain transaction to the head. `transaction` is CBOR-hex. */
  case class NewTx(transaction: String) extends HydraClientMsg:
    def toJson: String = ujson.write(ujson.Obj("tag" -> "NewTx", "transaction" -> transaction))

  /** Initiate cooperative close; posts a Close tx on-chain. */
  case object Close extends HydraClientMsg:
    def toJson: String = """{"tag":"Close"}"""

  /** After HeadIsClosed, fanout UTxOs back to mainchain. */
  case object Fanout extends HydraClientMsg:
    def toJson: String = """{"tag":"Fanout"}"""
