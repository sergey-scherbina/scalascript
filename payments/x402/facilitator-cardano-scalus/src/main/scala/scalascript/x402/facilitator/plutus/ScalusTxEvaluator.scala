package scalascript.x402.facilitator.plutus

import com.bloxbean.cardano.client.api.TransactionEvaluator
import com.bloxbean.cardano.client.api.model.Utxo
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag
import com.bloxbean.cardano.client.transaction.spec.Transaction
import scalascript.blockfrost.{BlockfrostClient, BlockfrostEvaluationResult}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

trait ScalusTxEvaluator:
  def evaluate(tx: Transaction): Future[Seq[ScalusEvaluatedRedeemer]]

case class ScalusEvaluatedRedeemer(
  tag:     String,
  index:   Int,
  exUnits: ScalusExUnits,
)

object ScalusTxEvaluator:
  def bloxbean(
    evaluator: TransactionEvaluator,
    utxos:     util.Set[Utxo] = util.Collections.emptySet(),
  )(using ec: ExecutionContext): ScalusTxEvaluator =
    (tx: Transaction) => Future {
      val result = evaluator.evaluateTx(tx, utxos)
      if !result.isSuccessful then
        throw RuntimeException(s"Scalus Tx evaluation failed: ${result.getResponse}")
      Option(result.getValue).map(_.asScala.toSeq).getOrElse(Seq.empty).map { item =>
        val ex = item.getExUnits
        ScalusEvaluatedRedeemer(
          tag     = Option(item.getRedeemerTag).map(_.name()).getOrElse(""),
          index   = item.getIndex,
          exUnits = ScalusExUnits(BigInt(ex.getMem), BigInt(ex.getSteps)),
        )
      }
    }

  def claimSpendExUnits(results: Seq[ScalusEvaluatedRedeemer]): Option[ScalusExUnits] =
    results.find(r => isSpend(r.tag) && r.index == 0).map(_.exUnits)

  def blockfrost(client: BlockfrostClient)(using ec: ExecutionContext): ScalusTxEvaluator =
    (tx: Transaction) =>
      client.evaluateTx(tx.serialize()).map(fromEndpointResults)

  def ogmiosHttp(url: String)(using ec: ExecutionContext): ScalusTxEvaluator =
    val http = HttpClient.newHttpClient()
    (tx: Transaction) => Future {
      val body = ujson.Obj(
        "jsonrpc" -> "2.0",
        "method"  -> "evaluateTransaction",
        "params"  -> ujson.Obj("transaction" -> ujson.Obj("cbor" -> hex(tx.serialize()))),
        "id"      -> "scalascript-evaluate",
      ).render()
      val request = HttpRequest.newBuilder(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      if response.statusCode() / 100 != 2 then
        throw RuntimeException(s"Ogmios evaluate ${response.statusCode()}: ${response.body()}")
      val json = ujson.read(response.body())
      json.obj.get("error").foreach(err => throw RuntimeException(s"Ogmios evaluate failed: $err"))
      fromEndpointResults(BlockfrostEvaluationResult.parseAll(json("result")))
    }

  def fromEndpointResults(results: Seq[BlockfrostEvaluationResult]): Seq[ScalusEvaluatedRedeemer] =
    results.map { result =>
      val (tag, index) = parseValidator(result.validator)
      ScalusEvaluatedRedeemer(tag, index, ScalusExUnits(result.memory, result.steps))
    }

  private def parseValidator(validator: String): (String, Int) =
    validator.split(":", 2).toList match
      case rawTag :: rawIndex :: Nil => normalizeTag(rawTag) -> rawIndex.toInt
      case _                         => validator -> 0

  private def normalizeTag(tag: String): String =
    tag.toLowerCase match
      case "spend"       => RedeemerTag.Spend.name()
      case "mint"        => RedeemerTag.Mint.name()
      case "certificate" => RedeemerTag.Cert.name()
      case "cert"        => RedeemerTag.Cert.name()
      case "withdrawal"  => RedeemerTag.Reward.name()
      case "reward"      => RedeemerTag.Reward.name()
      case other         => other

  private def isSpend(tag: String): Boolean =
    tag == RedeemerTag.Spend.name() || tag.equalsIgnoreCase("spend")

  private def hex(bytes: Array[Byte]): String =
    bytes.iterator.map(b => f"${b & 0xff}%02x").mkString
